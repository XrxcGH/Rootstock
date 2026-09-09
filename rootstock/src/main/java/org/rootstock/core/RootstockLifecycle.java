package org.rootstock.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import org.littletonrobotics.junction.LogFileUtil;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.NT4Publisher;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.littletonrobotics.junction.wpilog.WPILOGWriter;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.config.ConfigRegistry;
import org.rootstock.core.config.ConfigSnapshot;
import org.rootstock.core.config.DeployInfo;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.SliceScheduler;
import org.rootstock.core.hid.ControlMap;
import org.rootstock.core.hid.RumbleScheduler;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.match.MatchSchedule;
import org.rootstock.core.spi.LifecycleHook;
import org.rootstock.core.spi.LogConfig;
import org.rootstock.core.spi.RobotMode;

/**
 * <b>PUBLIC.</b> Everything Rootstock needs done per loop, as an object a team can drive by hand
 * from any base class, in any order, from an existing robot they are not willing to rewrite.
 *
 * <p>This is the incremental-adoption seam and it is not negotiable (DESIGN.md <b>D29</b>). 8793 has
 * an existing {@code CommandSwerveDrivetrain} and a hand-written {@code Robot}; 9143 already extends
 * {@code LoggedRobot} with its own {@code Logger} setup. Neither team can be asked to change its base
 * class to bolt on the alert registry and the health monitors, and M1's whole value proposition is
 * that they do not have to. Nothing here is package-private, and there is a CI fixture
 * ({@code health-only}) that uses only this class and never mentions {@link RootstockRobot}.
 *
 * <h2>The two adoption shapes, which differ by exactly one thing</h2>
 *
 * <pre>{@code
 * // Convenience: extend the base class. See RootstockRobot.
 * public class Robot extends RootstockRobot {
 *   public Robot() {
 *     super(LogConfig.defaults().withWpilogFolder("/U/logs"));
 *     m_container = new RobotContainer();     // calls RootstockRegistry.addAll(...)
 *     lifecycle().init();                     // last, so the boot dump reflects what you registered
 *   }
 * }
 *
 * // Partial adoption: keep your base class, drive five methods by hand.
 * public class Robot extends LoggedRobot {
 *   private final RootstockLifecycle m_rootstock;
 *   public Robot() {
 *     m_rootstock   = RootstockLifecycle.create(LogConfig.adoptExistingLogger());
 *     m_container = new RobotContainer();
 *     m_rootstock.init();
 *   }
 *   @Override public void robotPeriodic() {
 *     m_rootstock.beforeUserPeriodic();
 *     CommandScheduler.getInstance().run();
 *     m_rootstock.afterUserPeriodic();
 *   }
 *   @Override public void disabledInit() { m_rootstock.disabledInit(); }
 *   @Override public void close()        { m_rootstock.close(); }
 * }
 * }</pre>
 *
 * <p>There is no {@code robotInit()} override in either shape. ArchUnit rule 5 bans the identifier
 * from the library's surface, D13a deleted {@link RootstockRobot}'s override of the WPILib hook, and
 * D29 named the lifecycle method {@link #init()} and made it idempotent.
 *
 * <h2>Honest limits</h2>
 *
 * <p>A plain {@code TimedRobot} that starts its own {@code Logger} <i>can</i> drive this class and
 * gets the whole platform value line — alerts, health, self-test, {@code MatchContext},
 * {@code RobotIdentity}, {@code CanIdRegistry}, {@code ControlMap}. What it does <b>not</b> get is
 * deterministic replay, because AdvantageKit's replay driver requires {@code LoggedRobot} to own the
 * loop and feed it from the log. The ranking is {@code RootstockRobot} → your own {@code LoggedRobot}
 * → {@code TimedRobot} with replay given up. The AdvantageKit <i>dependency</i> is not avoidable
 * either way: incremental adoption is about your code, not about your dependency graph.
 */
public final class RootstockLifecycle implements AutoCloseable {

  /** The log key carrying the effective {@link LogConfig}, for a reader years later. */
  public static final String kLogConfigKey = "/Rootstock/Meta/LogConfig";

  /** The alert group lifecycle problems are filed under. */
  public static final String kAlertGroup = "Lifecycle";

  /**
   * The {@code SliceScheduler} name of {@code MatchContext}'s every-loop FMS/DS/alliance latching.
   *
   * <p>Every loop rather than a slice because its entire value is catching an edge on the cycle it
   * happens: a slice would notice the FMS attach up to a full sweep late and rename the log after it
   * had already been opened.
   */
  public static final String kMatchLatchSlice = "MatchLatch";

  /** The {@code SliceScheduler} name of {@code MatchContext}'s derived-state publishing slice. */
  public static final String kMatchSlice = "Match";

  /** The {@code SliceScheduler} name of the alert registry's evaluation slice. */
  public static final String kAlertsSlice = "Alerts";

  private final LogConfig m_config;
  private final RobotMode m_mode;
  private final boolean m_startedLogger;
  private final List<LifecycleHook> m_hooks;
  private final List<String> m_hookOrigins;
  private final List<String> m_bootNotes = new ArrayList<>();

  private boolean m_initialized;
  private boolean m_closed;

  private RootstockLifecycle(
      LogConfig config,
      RobotMode mode,
      boolean startedLogger,
      List<LifecycleHook> hooks,
      List<String> hookOrigins,
      List<String> bootNotes) {
    m_config = config;
    m_mode = mode;
    m_startedLogger = startedLogger;
    m_hooks = hooks;
    m_hookOrigins = hookOrigins;
    m_bootNotes.addAll(bootNotes);

    // THE round-robin (org.rootstock.core.health.SliceScheduler). Nothing in Rootstock owns its
    // own rate gate: n independent rotations reconstruct exactly the per-loop spike the scheduler
    // exists to remove, and a wall-clocked gate fires on different cycles under 50x replay. So the
    // two polls the design names as slices are registered here, once, and the two pieces of edge
    // detection that must see every loop are registered as every-loop work so SliceScheduler.tick()
    // stays the one call site.
    HealthMonitor.installBuiltins();
    SliceScheduler.registerEveryLoop(kMatchLatchSlice, MatchContext::periodic);
    SliceScheduler.register(kMatchSlice, MatchContext::poll);
    SliceScheduler.register(kAlertsSlice, AlertRegistry::poll);
  }

  /**
   * <b>The only factory (D29).</b> There is no no-argument {@code create()}; a team that wants the
   * defaults writes {@code create(LogConfig.defaults())}.
   *
   * <p><b>Who starts the Logger is answered by the argument, not detected.</b> A double
   * {@code Logger.start()} is a crash, not a warning, and auto-detection would make "who owns the
   * logger" an assumption rather than an answer:
   *
   * <ul>
   *   <li>{@link LogConfig#defaults()} — this class configures the receivers and the replay source
   *       and <b>starts</b> AdvantageKit's {@code Logger}. The from-scratch case.
   *   <li>{@link LogConfig#adoptExistingLogger()} — your code already called {@code Logger.start()}.
   *       This class attaches to it and never starts it again. The case a team adding Rootstock to
   *       an existing {@code LoggedRobot} wants.
   * </ul>
   *
   * <p>Either way this resolves the run mode ({@link RobotMode#REAL} is downgraded to
   * {@link RobotMode#SIM} when {@code Platform.isSimulation()} — the mode is set from the platform,
   * not by user code), writes the pre-start metadata window, and builds the priority-ordered hook
   * list.
   *
   * @param config the immutable logging configuration; never a {@code Consumer} and never a builder
   * @return a lifecycle ready to be driven
   */
  public static RootstockLifecycle create(LogConfig config) {
    Objects.requireNonNull(config, "RootstockLifecycle.create: config must not be null. Write "
        + "create(LogConfig.defaults()), or create(LogConfig.adoptExistingLogger()) if your own "
        + "code has already called Logger.start().");

    List<String> notes = new ArrayList<>();
    RobotMode mode = resolveMode(config, notes);
    boolean startedLogger = false;

    if (config.adoptsExistingLogger()) {
      notes.add("Logger: ADOPTED — LogConfig.adoptExistingLogger() was used, so Rootstock added no "
          + "receivers and did not call Logger.start(). Your code owns the logger.");
    } else {
      configureLogger(config, mode, notes);
      Logger.start();
      startedLogger = true;
      notes.add("Logger: STARTED by Rootstock (LogConfig.defaults()).");
    }

    List<LifecycleHook> hooks = new ArrayList<>();
    List<String> origins = new ArrayList<>();
    collectHooks(hooks, origins);

    return new RootstockLifecycle(config, mode, startedLogger, hooks, origins, notes);
  }

  /**
   * D29's {@code init()} — <b>not</b> {@code robotInit()}, which ArchUnit rule 5 bans from the
   * library's surface.
   *
   * <p><b>Idempotent.</b> Call it at the <i>end</i> of your constructor, after
   * {@link RootstockRegistry#addAll(Object...)}, so the boot dump reflects what you actually
   * registered. If you never call it, the first {@link #beforeUserPeriodic()} calls it for you —
   * which is how {@link RootstockRobot}, whose constructor runs <i>before</i> the subclass body, gets
   * it done without overriding the WPILib hook. Both call sites are after every subclass field
   * initialiser has run, which is the property the deleted {@code robotInit()} override existed to
   * provide.
   *
   * <p>Resolves deploy metadata, loads the kill switch and the match schedule, publishes the control
   * map and the config registry, prints the boot dump, and runs every hook's {@code init()}.
   */
  public void init() {
    if (m_initialized) {
      return;
    }
    m_initialized = true;

    DeployInfo.resolve();
    MatchContext.setBuildSha(DeployInfo.gitShaShort());
    MatchSchedule.loadFromDeploy();
    Rootstock.loadDisabledFile();

    ConfigRegistry.publishAll();
    ControlMap.publishAll();
    ConfigSnapshot.driftSummary()
        .ifPresent(
            summary ->
                Alerts.warning(kAlertGroup, "Tuned values differ from the committed snapshot: "
                        + summary, MatchImpact.PIT_ONLY)
                    .set(true));

    Logger.recordOutput(kLogConfigKey, m_config.describe());
    SafeMode.publish();

    for (LifecycleHook hook : m_hooks) {
      hook.init();
    }

    System.out.println(describe());
  }

  /**
   * Runs before the team's {@code CommandScheduler.getInstance().run()}.
   *
   * <p>In order: lazy {@link #init()}, the cycle counter, the tracer's loop epoch, the
   * driver-station edge latch, one slice of the round-robin, then every hook's
   * {@code beforeUserPeriodic()} in priority order. This is where inputs are read and where health
   * monitors sample, so everything the user's commands see this cycle is already fresh.
   */
  public void beforeUserPeriodic() {
    if (!m_initialized) {
      // A team that forgot init(). This is AFTER every field initialiser has run, so the registry
      // it validates is populated — which is the whole reason init() is idempotent (D29).
      init();
    }

    Clock.tick();
    RootstockTracer.reset();
    SliceScheduler.tick();

    for (LifecycleHook hook : m_hooks) {
      hook.beforeUserPeriodic();
    }

    if (Platform.isSimulation()) {
      for (LifecycleHook hook : m_hooks) {
        hook.simulationTick(Clock.dt());
      }
    }
  }

  /**
   * Runs after the team's {@code CommandScheduler.getInstance().run()}.
   *
   * <p>Every hook's {@code afterUserPeriodic()} in priority order — telemetry flush, budget
   * governor, rumble arbitration — then the tracer's loop epoch is closed by the next
   * {@link #beforeUserPeriodic()}.
   */
  public void afterUserPeriodic() {
    for (LifecycleHook hook : m_hooks) {
      hook.afterUserPeriodic();
    }
  }

  /**
   * Runs on every disable edge.
   *
   * <p>This is where the verified, blocking, full-config re-apply happens: the robot is disabled, the
   * loop budget is irrelevant, and it guarantees that anything a mid-match device reset, a live-tuning
   * frame loss, or an unconfirmed homing restore left inconsistent is restored before the next enable.
   * It calls {@link RootstockRegistry#onDisable()} internally, so a team driving the lifecycle by hand
   * does not have to remember both.
   */
  public void disabledInit() {
    RootstockRegistry.onDisable();
    for (LifecycleHook hook : m_hooks) {
      hook.disabledInit();
    }
    RumbleScheduler.stopAll();
    SafeMode.publish();
  }

  /**
   * Releases everything, in reverse priority order, and ends the Logger if this object started it.
   *
   * <p>Idempotent. A lifecycle created with {@link LogConfig#adoptExistingLogger()} never calls
   * {@code Logger.end()} — the team's code owns the logger and owns ending it.
   */
  @Override
  public void close() {
    if (m_closed) {
      return;
    }
    m_closed = true;

    for (int i = m_hooks.size() - 1; i >= 0; i--) {
      m_hooks.get(i).close();
    }
    RootstockRegistry.closeAll();

    if (m_startedLogger) {
      Logger.end();
    }
  }

  /**
   * The merged, priority-ordered hook list, for the boot dump and for tests.
   *
   * <p>The rule, in one line: <b>out-of-jar means {@code ServiceLoader}; in-jar means the explicit
   * list.</b> In-jar hooks — telemetry, tuning, sim, viz, rumble — are registered explicitly in code
   * inside {@link #create(LogConfig)}, because D28 already puts them in the same jar as core, so the
   * compile cycle {@code core.spi} exists to break is broken by package structure alone and the
   * {@code ServiceLoader} indirection was buying nothing. {@code ServiceLoader} survives where it is
   * load-bearing: genuinely out-of-jar vendor adapters, which exist precisely so core need not name
   * them.
   *
   * <p>Reserved priorities: 10 telemetry, 30 tuning, 50 sim, 60 rumble, 70 viz. Lower runs first.
   *
   * @return an unmodifiable, priority-ordered snapshot
   */
  public List<LifecycleHook> hooks() {
    return List.copyOf(m_hooks);
  }

  /**
   * The configuration this lifecycle was created with.
   *
   * @return the immutable config value
   */
  public LogConfig config() {
    return m_config;
  }

  /**
   * The resolved run mode — {@link RobotMode#REAL} on hardware, {@link RobotMode#SIM} in
   * simulation, {@link RobotMode#REPLAY} when the config asked for it.
   *
   * @return the mode actually in force, which may differ from {@code config().mode()}
   */
  public RobotMode mode() {
    return m_mode;
  }

  /**
   * Whether {@link #init()} has run.
   *
   * @return true once initialisation has completed, by either call path
   */
  public boolean isInitialized() {
    return m_initialized;
  }

  /**
   * The boot dump: the paragraph a student reads at 11 p.m. instead of guessing.
   *
   * @return a multi-line report — version, kill switch, mode, logger ownership, hooks, registry,
   *     identity and safe-mode state
   */
  public String describe() {
    StringBuilder sb = new StringBuilder(2048);
    String rule = "======================================================================";
    sb.append(System.lineSeparator()).append(rule).append(System.lineSeparator());
    sb.append("Rootstock boot dump").append(System.lineSeparator());
    sb.append(rule).append(System.lineSeparator());

    sb.append(Rootstock.describe());
    sb.append("  mode              ").append(m_mode).append(System.lineSeparator());
    sb.append("  loop period       ").append(String.format("%.4f s", Clock.dt()))
        .append(System.lineSeparator());
    sb.append("  health rotation   ").append(SliceScheduler.sweepCycles())
        .append(" slice(s); one runs per loop, so a full sweep takes that many cycles")
        .append(System.lineSeparator());
    for (String note : m_bootNotes) {
      sb.append("  ").append(note).append(System.lineSeparator());
    }

    sb.append(System.lineSeparator()).append("Hooks (").append(m_hooks.size())
        .append(", priority order)").append(System.lineSeparator());
    if (m_hooks.isEmpty()) {
      sb.append("  none").append(System.lineSeparator());
    }
    for (int i = 0; i < m_hooks.size(); i++) {
      sb.append(String.format("  %-4d %-28s %s%n", m_hooks.get(i).priority(), m_hooks.get(i).name(),
          m_hookOrigins.get(i)));
    }

    sb.append(System.lineSeparator()).append(RootstockRegistry.describe());
    sb.append(System.lineSeparator()).append(DeployInfo.summary()).append(System.lineSeparator());
    sb.append(System.lineSeparator()).append(SafeMode.describe()).append(System.lineSeparator());
    sb.append(rule).append(System.lineSeparator());
    return sb.toString();
  }

  // -------------------------------------------------------------------------------------------
  // Construction helpers
  // -------------------------------------------------------------------------------------------

  /**
   * REAL is downgraded to SIM on a desktop; REPLAY is only ever explicit, because replaying by
   * accident silently produces a log full of numbers that came from a file rather than a robot.
   */
  private static RobotMode resolveMode(LogConfig config, List<String> notes) {
    RobotMode requested = config.mode();
    if (requested == RobotMode.REAL && Platform.isSimulation()) {
      notes.add("Mode: SIM (LogConfig said REAL; Platform.isSimulation() is true, and the mode is "
          + "set from the platform rather than by user code).");
      return RobotMode.SIM;
    }
    return requested;
  }

  /**
   * The pre-start window: metadata first (it is write-once and only readable before {@code start()}),
   * then the receivers or the replay source.
   *
   * <p>Fields this method does <b>not</b> yet honour, named rather than silently dropped:
   * {@code compress}, {@code captureConsole}, {@code captureDriverStation}, {@code minimumTier},
   * {@code perCycleByteBudget} and {@code driverMirror} belong to {@code org.rootstock.telemetry},
   * which is a later milestone; {@code ctreSignalLogger} and {@code urcl} require vendor imports that
   * ArchUnit rule 1 forbids in core and are the vendor adapters' job. Each is reported in the boot
   * dump so the gap is visible rather than assumed.
   */
  private static void configureLogger(LogConfig config, RobotMode mode, List<String> notes) {
    for (java.util.Map.Entry<String, String> entry : DeployInfo.metadata().entrySet()) {
      Logger.recordMetadata(entry.getKey(), entry.getValue());
    }
    Logger.recordMetadata("RootstockVersion", Rootstock.VERSION);
    Logger.recordMetadata("RootstockRobotMode", mode.toString());

    if (mode == RobotMode.REPLAY) {
      String source = LogFileUtil.findReplayLog();
      Logger.setReplaySource(new WPILOGReader(source));
      Logger.addDataReceiver(new WPILOGWriter(LogFileUtil.addPathSuffix(source, "_replay")));
      notes.add("Logger: REPLAY from " + source + "; output written alongside it with a _replay "
          + "suffix.");
      return;
    }

    String folder = chooseLogFolder(config, notes);
    if (folder != null) {
      Logger.addDataReceiver(new WPILOGWriter(folder));
    }
    switch (config.ntPublish()) {
      case ALWAYS -> {
        Logger.addDataReceiver(new NT4Publisher());
        notes.add("Logger: NT4 mirror ALWAYS on.");
      }
      case OFF_ON_FMS -> {
        Logger.addDataReceiver(new FmsSuppressedNt4Publisher());
        notes.add("Logger: NT4 mirror on, suppressed while the FMS is attached.");
      }
      case NEVER -> notes.add("Logger: NT4 mirror off.");
      default -> notes.add("Logger: NT4 mirror off (unrecognised policy " + config.ntPublish()
          + ").");
    }

    notes.add("Logger: telemetry-owned LogConfig fields not yet applied by core — compress, "
        + "captureConsole, captureDriverStation, minimumTier, perCycleByteBudget, driverMirror "
        + "(org.rootstock.telemetry), ctreSignalLogger, urcl (vendor adapters).");
  }

  /**
   * Picks the WPILOG folder: the configured one when it exists and has room, otherwise the fallback.
   *
   * <p>A missing USB stick is the single most common logging failure at an event, and a robot that
   * writes nothing because {@code /U} is absent is a robot with no evidence. Returns null only when
   * neither folder is usable, which is reported and is not fatal.
   */
  private static String chooseLogFolder(LogConfig config, List<String> notes) {
    Optional<String> primary = usableFolder(config.wpilogFolder(), config.minFreeMegabytes());
    if (primary.isPresent()) {
      notes.add("Logger: writing WPILOGs to " + primary.get() + ".");
      return primary.get();
    }
    Optional<String> fallback = usableFolder(config.fallbackFolder(), config.minFreeMegabytes());
    if (fallback.isPresent()) {
      notes.add("Logger: " + config.wpilogFolder() + " is missing or below "
          + config.minFreeMegabytes() + " MB free; falling back to " + fallback.get() + ".");
      Alerts.warning(kAlertGroup, "Logging to the fallback folder " + fallback.get() + " because "
              + config.wpilogFolder() + " is unavailable. Check the USB stick.",
              MatchImpact.PIT_ONLY)
          .set(true);
      return fallback.get();
    }
    notes.add("Logger: NO WPILOG receiver — neither " + config.wpilogFolder() + " nor "
        + config.fallbackFolder() + " is writable with " + config.minFreeMegabytes()
        + " MB free. NetworkTables telemetry still works; nothing is written to disk.");
    Alerts.error(kAlertGroup, "No writable log folder (" + config.wpilogFolder() + ", "
            + config.fallbackFolder() + "). This match will produce no log file.",
            MatchImpact.PIT_ONLY)
        .set(true);
    return null;
  }

  private static Optional<String> usableFolder(String folder, int minFreeMegabytes) {
    if (folder == null || folder.isBlank()) {
      return Optional.empty();
    }
    Path path = Path.of(folder);
    if (!Files.isDirectory(path) || !Files.isWritable(path)) {
      return Optional.empty();
    }
    File file = path.toFile();
    long freeMegabytes = file.getUsableSpace() / (1024L * 1024L);
    return freeMegabytes >= minFreeMegabytes ? Optional.of(folder) : Optional.empty();
  }

  /**
   * The explicit in-jar list plus the {@code ServiceLoader}-discovered out-of-jar adapters, sorted by
   * priority with the origin of each recorded so "which mechanism found this hook" is never a guess.
   */
  private static void collectHooks(List<LifecycleHook> hooks, List<String> origins) {
    List<HookEntry> entries = new ArrayList<>();

    // EXPLICIT: in-jar hooks core can name today. Telemetry (10), tuning (30), sim (50) and viz (70)
    // add themselves here as their packages land.
    entries.add(new HookEntry(RumbleScheduler.getInstance(), "EXPLICIT"));

    // EXPLICIT: anything a team handed to RootstockRegistry.addAll before creating the lifecycle.
    for (LifecycleHook hook : RootstockRegistry.hooks()) {
      entries.add(new HookEntry(hook, "EXPLICIT (RootstockRegistry)"));
    }

    // SERVICE_LOADER: out-of-jar vendor adapters only. Core must not name them, which is the one
    // place the indirection is load-bearing.
    for (LifecycleHook hook : ServiceLoader.load(LifecycleHook.class)) {
      entries.add(new HookEntry(hook, "SERVICE_LOADER"));
    }

    entries.sort(Comparator.comparingInt(e -> e.hook().priority()));
    for (HookEntry entry : entries) {
      hooks.add(entry.hook());
      origins.add(entry.origin());
    }
  }

  private record HookEntry(LifecycleHook hook, String origin) {}

  /**
   * An {@code NT4Publisher} that stops mirroring the full log stream while the FMS is attached.
   *
   * <p>6328's {@code NoFMSNT4Publisher} pattern: at an event the full log stream over NT is bandwidth
   * the match does not have. It reads {@link MatchContext#isFMSAttached()} rather than
   * {@code DriverStation} — ArchUnit rule 10 makes {@code core.match} the library's only reader — and
   * {@code MatchContext} latches on the DS-connect edge, so a driver station that drops out mid-match
   * does not un-suppress the stream in the middle of a match. The driver mirror is a different thing
   * and is never suppressed.
   *
   * <p>This lives here rather than in {@code org.rootstock.telemetry} only because telemetry is a
   * later milestone and {@code OFF_ON_FMS} is the default; telemetry takes it over unchanged.
   */
  private static final class FmsSuppressedNt4Publisher
      implements org.littletonrobotics.junction.LogDataReceiver {

    private final NT4Publisher m_delegate = new NT4Publisher();

    @Override
    public void start() {
      m_delegate.start();
    }

    @Override
    public void end() {
      m_delegate.end();
    }

    @Override
    public void putTable(LogTable table) {
      if (!MatchContext.isFMSAttached()) {
        m_delegate.putTable(table);
      }
    }
  }
}

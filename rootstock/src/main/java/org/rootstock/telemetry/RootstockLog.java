package org.rootstock.telemetry;

import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringArrayPublisher;
import edu.wpi.first.networktables.StringPublisher;
import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Unit;
import edu.wpi.first.util.WPISerializable;
import edu.wpi.first.util.struct.Struct;
import edu.wpi.first.util.struct.StructSerializable;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.LogFileUtil;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.littletonrobotics.junction.networktables.NT4Publisher;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.littletonrobotics.junction.wpilog.WPILOGWriter;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.spi.LogConfig;
import org.rootstock.core.spi.RobotMode;
import org.rootstock.core.spi.Tier;
import org.rootstock.telemetry.replay.RootstockReplay;

/**
 * <b>THE</b> logging facade. Every Rootstock domain publishes through these statics and nothing else.
 *
 * <p><b>There is no backend SPI, and there never will be again.</b> {@code LogBackend}, the
 * {@code Backend} enum, {@code Backend.AUTO}, {@code activeBackend()}, {@code replayCapable()} and the
 * NT4/Epilogue/DogLog/no-op backends were all deleted. This class writes to AdvantageKit's
 * {@code Logger} <b>directly</b>. If you find yourself adding an interface with two implementations for
 * logging, stop — that is the design that was deleted, and the reason it was deleted is the headline
 * below.
 *
 * <p><b>Deterministic replay is a guaranteed property of Rootstock. Not a mode, not a backend choice,
 * not something you opt into.</b> If you are running Rootstock, {@code Logger} is running, every
 * Rootstock hardware read is behind {@link #processInputs}, and a WPILOG written by your robot re-runs
 * through your code and produces the same outputs. That guarantee is bought with a hard dependency on
 * AdvantageKit 26.0.2 or later, and the price is stated rather than hidden: a team already on DogLog or
 * plain Epilogue cannot adopt Rootstock without switching loggers, and a team with a loop-time problem
 * can no longer escape by choosing a cheaper logger. The tiers, {@link RootstockBudget} and
 * {@code ./gradlew logBudget} are the only levers left.
 *
 * <h2>The shape of the API</h2>
 *
 * <p>Three tiers times the value shapes AdvantageScope can actually render. Every shape exists as
 * {@link #critical} (always present, including on FMS) and {@link #log} (the STANDARD default); each of
 * those has a trailing-{@link Demotable} twin; the reference-typed {@link #debug} overloads take
 * <b>suppliers</b>, so the value is never constructed when the tier is off. That last detail is what
 * makes the classic AdvantageKit CPU trap — "the logger statements were instantiating new
 * {@code Translation2d}s every cycle" — impossible here.
 *
 * <p>Parameter order mirrors {@code Logger} exactly: in particular {@code Struct<T>} comes
 * <b>before</b> the value, because {@code Logger.recordOutput(String, Struct<T>, T)} does. A facade
 * that reverses its delegate's argument order is a transcription bug waiting to happen.
 *
 * <p><b>{@code put(...)} does not exist and will not be added.</b> A tier-implicit call is exactly how a
 * key ends up in the wrong tier and vanishes on FMS. A {@code put(k, v)} is {@code critical(k, v)} or
 * {@code log(k, v)} with the tier read off the published schema.
 *
 * <p><b>There is no {@code akit()} escape hatch, and none is needed.</b>
 * {@code org.littletonrobotics.junction.Logger} is a public static class on the compile classpath of
 * every Rootstock consumer. This facade is a convenience: call {@code Logger.recordOutput(...)}
 * directly whenever it is in your way. The two interleave freely and land in the same stream — the only
 * thing you lose is the byte accounting, which is why such calls land in the framework bucket.
 *
 * <h2>The FMS tier gate</h2>
 *
 * <p>On FMS attach {@code minimumTier} is raised to {@code STANDARD} — <b>to STANDARD and no
 * higher.</b> CRITICAL and STANDARD keys are both still published; DEBUG keys stop being evaluated at
 * all. The gate removes one tier, not two, and a STANDARD key therefore <b>survives</b> an FMS attach.
 * It disappears only if a team deliberately sets {@code minimumTier = CRITICAL}, which nothing in the
 * library does. Loose wording here caused a real misunderstanding once; {@link #effectiveMinimumTier()}
 * is the single implementation of the sentence.
 */
public final class RootstockLog {

  /** The log key root. Everything Rootstock publishes lives under {@code Rootstock/}. */
  public static final String kRoot = "Rootstock";

  /** The driver mirror's NetworkTables root table name. */
  public static final String kNtRoot = "Rootstock";

  /**
   * How many alerts the driver mirror shows at once.
   *
   * <p>A half-built robot in week two has thirty active alerts. Thirty rows of red on a driver's
   * dashboard is the same information as no rows at all, so the mirror shows the three most important
   * and one {@code "+N more"} line pointing at the pit tab. The policy — only blocking alerts, capped,
   * ranked — is owned by {@code AlertRegistry}; this domain owns the transport.
   */
  public static final int kDriverAlertCap = AlertRegistry.kDriverDisplayCap;

  /** The NT topic carrying the capped driver alert rows. */
  public static final String kDriverBlockingTopic = "Driver/Blocking";

  /** The NT topic carrying the {@code "+N more"} rollup row. */
  public static final String kDriverBlockingMoreTopic = "Driver/BlockingMore";

  /** The alert group facade problems are filed under. */
  public static final String kAlertGroup = "Rootstock/Log";

  /**
   * Payload size assumed for a {@code WPISerializable} whose {@code struct} field cannot be read.
   *
   * <p>Only reachable for a geometry type that does not follow WPILib's static-{@code struct}
   * convention. Named rather than inlined so {@code logBudget} can print it and a wrong number is
   * visible instead of mysterious.
   */
  public static final int kUnknownStructBytes = 32;

  private static final Map<String, SizingInputs> m_inputs = new LinkedHashMap<>();
  private static final Map<Class<?>, Integer> m_structSizes = new LinkedHashMap<>();
  private static final Set<String> m_metadataKeys = new LinkedHashSet<>();

  private static LogConfig m_config = LogConfig.defaults();
  private static RobotMode m_mode;
  private static boolean m_configured;
  private static boolean m_started;
  private static boolean m_metadataClosed;
  private static NetworkTable m_ntRoot;
  private static StringArrayPublisher m_driverBlocking;
  private static StringPublisher m_driverBlockingMore;
  private static RootstockAlert m_metadataAlert;
  private static RootstockAlert m_bypassAlert;

  private RootstockLog() {}

  // ===============================================================================================
  // Lifecycle
  // ===============================================================================================

  /**
   * Hands the facade its configuration. Called once, from {@code RootstockLifecycle.create(LogConfig)},
   * before any hardware is constructed.
   *
   * <p>Takes the <b>immutable</b> value by argument. There is no {@code Consumer<LogConfig>} overload
   * and no mutation-after-the-fact path: a config object that can be mutated after {@code start()} is a
   * config object that can silently disagree with the log's own provenance metadata.
   *
   * <p>A team wiring manually calls {@code RootstockLifecycle.create(...)} and never reaches this method.
   * If it is never called at all the facade runs on {@link LogConfig#defaults()}, so a bare
   * {@code RootstockLog.log(...)} in a test or a scratch project still works.
   *
   * @param config the immutable logging configuration
   */
  public static void configure(LogConfig config) {
    m_config = Objects.requireNonNull(config, "RootstockLog.configure: config must not be null. Pass "
        + "LogConfig.defaults(), or LogConfig.adoptExistingLogger() if your own code has already "
        + "called Logger.start().");
    m_configured = true;
    m_metadataClosed = false;
    m_mode = resolveMode(config);
  }

  /**
   * Installs the data receivers and starts {@code Logger} — <b>unless</b> the config came from
   * {@link LogConfig#adoptExistingLogger()}, in which case {@code Logger.start()} has already run in
   * the team's code and calling it again is a crash at boot.
   *
   * <p><b>Idempotent</b>, for that reason. After this, {@link #metadata(String, String)} is closed.
   *
   * <p><b>When {@code RootstockLifecycle} is your front door, do not call this.</b>
   * {@code RootstockLifecycle.create(LogConfig)} performs the same configuration and start itself so that
   * a manually-wired robot has one front door and not two; this method exists for the facade's own
   * standalone use and for tests. Calling both is guarded here by {@link #m_started}, but the guard
   * cannot see a {@code Logger} someone else started, and AdvantageKit exposes no predicate for it.
   */
  public static void start() {
    if (m_started) {
      return;
    }
    m_started = true;
    m_metadataClosed = true;

    if (m_config.adoptsExistingLogger()) {
      if (isReplay()) {
        RootstockReplay.install();
      }
      return;
    }

    if (mode() == RobotMode.REPLAY) {
      String replayLog = LogFileUtil.findReplayLog();
      Logger.setReplaySource(new WPILOGReader(replayLog));
      Logger.addDataReceiver(new WPILOGWriter(LogFileUtil.addPathSuffix(replayLog, "_replay")));
    } else {
      Logger.addDataReceiver(new WPILOGWriter(m_config.wpilogFolder()));
      switch (m_config.ntPublish()) {
        case ALWAYS -> Logger.addDataReceiver(new NT4Publisher());
        case OFF_ON_FMS -> Logger.addDataReceiver(new FmsSuppressedNt4Publisher());
        case NEVER -> { }
        default -> { }
      }
    }

    if (!m_config.captureConsole()) {
      // AdvantageKit's console capture is built in and on by default; the only control is the
      // opt-OUT. There is no enable call, so `false` maps to *calling* this and `true` maps to
      // doing nothing.
      Logger.disableConsoleCapture();
    }

    Logger.start();

    if (isReplay()) {
      RootstockReplay.install();
    }
  }

  /**
   * Rootstock's view of the run mode.
   *
   * <p>{@code REAL} and {@code SIM} come from {@code Platform.isReal()} — not {@code RobotBase}
   * directly, which is confined to the compat tier. {@code REPLAY} is true when AdvantageKit has a
   * replay source installed. All three always work; there is no "this mode is unavailable" path any
   * more.
   *
   * @return the mode
   */
  public static RobotMode mode() {
    if (m_mode == null) {
      m_mode = resolveMode(m_config);
    }
    return m_mode;
  }

  /**
   * The configuration this facade is running under.
   *
   * <p>Read-only by construction: {@code LogConfig} is immutable, so handing it back cannot let a caller
   * reconfigure a started {@code Logger}.
   *
   * @return the configuration; {@link LogConfig#defaults()} if {@link #configure} was never called
   */
  public static LogConfig config() {
    return m_config;
  }

  /**
   * Whether {@link #configure(LogConfig)} has been called.
   *
   * @return false when the facade is running on {@link LogConfig#defaults()} by omission
   */
  public static boolean isConfigured() {
    return m_configured;
  }

  /**
   * Write-once provenance metadata. Must be called between {@link #configure} and {@link #start}.
   *
   * <p>The provenance set is the one 6328's practice established, and it is what makes any log
   * traceable to an exact commit: {@code ProjectName}, {@code BuildDate}, {@code GitSHA},
   * {@code GitBranch}, {@code GitDirty}, {@code Hostname}, {@code Platform}, {@code RobotIdentity},
   * {@code RootstockVersion}, {@code WpilibVersion}, {@code AdvantageKitVersion} and {@code Mode}.
   * {@code Backend} and {@code ReplayCapable} are gone: one had a single possible value and the other
   * was structurally true. {@code AdvantageKitVersion} replaced them and is the more useful field —
   * when a replay misbehaves after an upgrade, the first question is which AdvantageKit wrote the log.
   *
   * <p>Writing after {@code start()}, or writing the same key twice, raises a named alert and is
   * ignored. It does not throw: metadata is a diagnostic, and a diagnostic must never become the
   * outage.
   *
   * @param key the metadata key
   * @param value the value
   */
  public static void metadata(String key, String value) {
    Objects.requireNonNull(key, "RootstockLog.metadata: key must not be null.");
    Objects.requireNonNull(value, "RootstockLog.metadata: value must not be null.");
    if (m_metadataClosed) {
      metadataProblem(
          "METADATA_AFTER_START: RootstockLog.metadata(\""
              + key
              + "\", ...) was called after start(), and AdvantageKit's metadata window closes at "
              + "start(). The value was dropped. Fix: record provenance between "
              + "RootstockLog.configure(...) and RootstockLog.start().");
      return;
    }
    if (!m_metadataKeys.add(key)) {
      metadataProblem(
          "METADATA_REWRITTEN: RootstockLog.metadata(\""
              + key
              + "\", ...) was called twice. Provenance is write-once: a log whose metadata changed "
              + "mid-session cannot be traced to one commit. The second value was dropped.");
      return;
    }
    Logger.recordMetadata(key, value);
  }

  /**
   * The driver mirror's NetworkTables root, {@code /Rootstock}.
   *
   * <p>This is a NetworkTables handle for the dashboard contract, and it is <b>not</b> a logging escape
   * hatch: the driver mirror is ~30 low-rate topics written with raw NT publishers, deliberately
   * separate from the log stream and never suppressed by {@link LogConfig#ntPublish()}, which governs
   * something else entirely.
   *
   * @return the root table
   */
  public static NetworkTable ntRoot() {
    if (m_ntRoot == null) {
      m_ntRoot = NetworkTableInstance.getDefault().getTable(kNtRoot);
    }
    return m_ntRoot;
  }

  /**
   * Publishes the capped driver alert rows to {@code /Rootstock/Driver/Blocking} and
   * {@code /Rootstock/Driver/BlockingMore}.
   *
   * <p>At most {@value #kDriverAlertCap} rows, most important first, plus a {@code "+N more - see the
   * pit tab"} rollup when there are more. The cap is not cosmetic: an uncapped mirror on a half-built
   * robot is a wall of red that a driver learns to ignore, and an ignored alert is worse than no alert
   * because it also hides the one that mattered.
   *
   * <p>A no-op when {@link LogConfig#driverMirror()} is off.
   */
  public static void publishDriverAlerts() {
    if (!m_config.driverMirror()) {
      return;
    }
    if (m_driverBlocking == null) {
      m_driverBlocking = ntRoot().getStringArrayTopic(kDriverBlockingTopic).publish();
      m_driverBlockingMore = ntRoot().getStringTopic(kDriverBlockingMoreTopic).publish();
    }
    List<String> rows = AlertRegistry.driverBlockingRows();
    m_driverBlocking.set(rows.toArray(new String[0]));
    m_driverBlockingMore.set(AlertRegistry.driverBlockingMore());
  }

  /**
   * Opens a logging cycle: resets the byte counters and arms the replay tripwire's per-cycle checks.
   *
   * <p>Runs before the team's {@code periodic()} and before the command scheduler.
   */
  public static void beforeUserPeriodic() {
    RootstockBudget.beginCycle();
    RootstockReplay.beginCycle();
  }

  /**
   * Closes a logging cycle: checks that every registered IO key was processed exactly once, totals the
   * byte buckets, and runs the byte-budget governor.
   *
   * <p>Runs after the team's {@code periodic()} and after the command scheduler.
   */
  public static void afterUserPeriodic() {
    RootstockReplay.endCycle(m_inputs.keySet());
    RootstockBudget.endCycle(m_config, isReplay());
  }

  // ===============================================================================================
  // The two predicates, and the clock
  // ===============================================================================================

  /**
   * The current time in <b>seconds</b>, from the AdvantageKit-injected clock.
   *
   * <p>Identical to {@code Clock.seconds()} and to {@code Timer.getTimestamp()}; exposed here so a
   * domain that already imports {@code RootstockLog} does not also have to import {@code Clock}. This is
   * the only time source any Rootstock control path may read.
   *
   * <p>Note the unit difference from AdvantageKit: {@code Logger.getTimestamp()} returns a
   * {@code long} in <b>microseconds</b>. This returns a {@code double} in <b>seconds</b>, matching every
   * WPILib control API. The two are the same instant.
   *
   * @return the timestamp in seconds
   */
  public static double timestamp() {
    return Clock.seconds();
  }

  /**
   * Whether a replay source is installed.
   *
   * @return true iff {@code mode() == RobotMode.REPLAY}
   */
  public static boolean isReplay() {
    return Logger.hasReplaySource();
  }

  /**
   * The tier actually in force this instant, after the FMS gate.
   *
   * <p>The gate raises {@code minimumTier} to {@code STANDARD} and <b>no higher</b>: it drops DEBUG and
   * only DEBUG. A team that deliberately set {@code CRITICAL} keeps {@code CRITICAL} — the gate never
   * loosens a stricter setting either.
   *
   * @return the effective minimum tier
   */
  public static Tier effectiveMinimumTier() {
    Tier configured = m_config.minimumTier();
    if (!MatchContext.isFMSAttached()) {
      return configured;
    }
    return configured.ordinal() < Tier.STANDARD.ordinal() ? configured : Tier.STANDARD;
  }

  /**
   * Whether DEBUG-tier calls are currently being evaluated.
   *
   * <p>False once the FMS gate has raised {@code minimumTier} to STANDARD. Reference-typed
   * {@link #debug} overloads are supplier-gated, so this predicate is needed only when the <i>key set
   * itself</i> is conditional — not to guard an individual call.
   *
   * @return true when DEBUG keys are being published
   */
  public static boolean debugEnabled() {
    return effectiveMinimumTier() == Tier.DEBUG;
  }

  /** Clears every static, so one test cannot see another test's keys or bytes. */
  public static void resetForTest() {
    m_inputs.clear();
    m_structSizes.clear();
    m_metadataKeys.clear();
    m_config = LogConfig.defaults();
    m_mode = null;
    m_configured = false;
    m_started = false;
    m_metadataClosed = false;
    m_metadataAlert = null;
    m_bypassAlert = null;
    RootstockBudget.resetForTest();
    RootstockReplay.resetForTest();
  }

  // ===============================================================================================
  // IO inputs
  // ===============================================================================================

  /**
   * Pushes a hardware inputs struct into the log — and, in {@code REPLAY} mode, reads it back
   * <b>from</b> the log instead of from hardware. There is no second code path.
   *
   * <p>Call it as the second of exactly two statements at the top of a subsystem's {@code periodic()}:
   *
   * <pre>{@code
   * io.updateInputs(inputs);
   * RootstockLog.processInputs("Rootstock/Elevator", inputs);
   * // After this line, read ONLY from `inputs`. Never call io.getX().
   * }</pre>
   *
   * <p>That last comment is the rule that makes replay byte-identical, and it is not a style
   * preference: a subsystem that reads the IO object directly is reading a value that was never in the
   * log, so replay recomputes an output from an input it does not have.
   *
   * <p><b>There is no {@link Demotable} overload and there never will be.</b> Replayed inputs are
   * structurally outside the governor's reach — see {@link Demotable} for the full argument. There is
   * nothing to pass, which is the strongest form the invariant can take.
   *
   * <p>Implementation note: this does not pass {@code inputs} straight through. It passes a per-key
   * sizing wrapper, allocated once per key at first call and never per cycle, whose {@code toLog}
   * delegates and then attributes bytes per field on the once-per-second attribution cycle. Its
   * {@code fromLog} is a straight delegate, so {@code REPLAY} pays nothing.
   *
   * @param key the IO key, e.g. {@code "Rootstock/Elevator"}
   * @param inputs the hand-written {@code LoggableInputs} struct
   */
  public static void processInputs(String key, LoggableInputs inputs) {
    Objects.requireNonNull(key, "RootstockLog.processInputs: key must not be null.");
    Objects.requireNonNull(inputs, "RootstockLog.processInputs: inputs must not be null.");
    SizingInputs wrapper = m_inputs.get(key);
    if (wrapper == null) {
      wrapper = new SizingInputs(key, inputs);
      m_inputs.put(key, wrapper);
    } else {
      wrapper.rebind(inputs);
    }
    RootstockReplay.noteProcessInputs(key);
    Logger.processInputs(key, wrapper);
  }

  /**
   * Every key registered through {@link #processInputs} this session, insertion-ordered.
   *
   * <p>Insertion order rather than hash order because iteration order inside Rootstock must be
   * deterministic: two runs of the same robot produce identical output ordering, and the replay tripwire
   * walks this list every cycle.
   *
   * @return an immutable list
   */
  public static List<String> inputKeys() {
    return List.copyOf(m_inputs.keySet());
  }

  /**
   * Explicit decimation: run {@code r} on one cycle in {@code n}.
   *
   * <p>Unrelated to {@link Demotable}, and the difference matters: {@code everyN} is the <b>author</b>
   * deciding, at the call site, forever; {@code Demotable} is the <b>governor</b> deciding, at runtime,
   * reversibly, and only under sustained overload.
   *
   * @param n the period in cycles
   * @param r the work
   */
  public static void everyN(int n, Runnable r) {
    Logger.runEveryN(n, r);
  }

  // ===============================================================================================
  // CRITICAL — always present, including on FMS
  // ===============================================================================================

  /**
   * Logs a boolean at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, boolean v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 1)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, long v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, double v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at CRITICAL tier with unit metadata, so AdvantageScope labels and converts the axis.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit the value is measured in
   */
  public static void critical(String key, double v, Unit unit) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8)) {
      Logger.recordOutput(key, v, unit);
    }
  }

  /**
   * Logs a String at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, String v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, utf8Length(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an enum constant at CRITICAL tier. Enums are written as their {@code name()}.
   *
   * @param <E> the enum type
   * @param key the log key
   * @param v the value
   */
  public static <E extends Enum<E>> void critical(String key, E v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, enumBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a boolean array at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, boolean[] v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer array at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, long[] v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double array at CRITICAL tier.
   *
   * <p>The unit is mandatory on the array shapes because an unlabelled array of numbers is the single
   * hardest thing to read back off a graph six weeks later. AdvantageKit 26.0.2 has no unit-carrying
   * array overload, so the unit is not written as entry metadata today; it is required here so the call
   * site records the answer and so the layout generator has it.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit every element is measured in
   */
  public static void critical(String key, double[] v, Unit unit) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a String array at CRITICAL tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void critical(String key, String[] v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, stringArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a unit-carrying measure at CRITICAL tier.
   *
   * @param <U> the unit type
   * @param key the log key
   * @param v the value
   */
  public static <U extends Unit> void critical(String key, Measure<U> v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a WPILib geometry value at CRITICAL tier — {@code Pose2d}, {@code Pose3d},
   * {@code ChassisSpeeds}, {@code SwerveModuleState} and friends — without the caller naming a
   * {@code Struct}.
   *
   * <p>Always prefer this over packing geometry into a custom struct: AdvantageScope decodes WPILib's
   * structs natively and they carry native unit information, whereas a custom struct is one opaque blob
   * on the unit-aware line graph and one more first-publish blocking hazard at match start.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   */
  public static <T extends WPISerializable> void critical(String key, T v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, structBytes(v.getClass()))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an array of WPILib geometry values at CRITICAL tier.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   */
  public static <T extends StructSerializable> void critical(String key, T[] v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, structArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a value with an explicit struct at CRITICAL tier, for a struct Rootstock does not control.
   *
   * @param <T> the value type
   * @param key the log key
   * @param struct the struct; first, exactly as {@code Logger.recordOutput} orders it
   * @param v the value
   */
  public static <T> void critical(String key, Struct<T> struct, T v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, struct.getSize())) {
      Logger.recordOutput(key, struct, v);
    }
  }

  /**
   * Logs an array with an explicit struct at CRITICAL tier.
   *
   * @param <T> the element type
   * @param key the log key
   * @param struct the element struct
   * @param v the value
   */
  public static <T> void critical(String key, Struct<T> struct, T[] v) {
    if (gate(key, Tier.CRITICAL, Demotable.NO, struct.getSize() * v.length)) {
      Logger.recordOutput(key, struct, v);
    }
  }

  // ===============================================================================================
  // STANDARD — the default tier
  // ===============================================================================================

  /**
   * Logs a boolean at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, boolean v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 1)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, long v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, double v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at STANDARD tier with unit metadata.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit the value is measured in
   */
  public static void log(String key, double v, Unit unit) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8)) {
      Logger.recordOutput(key, v, unit);
    }
  }

  /**
   * Logs a String at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, String v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, utf8Length(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an enum constant at STANDARD tier.
   *
   * @param <E> the enum type
   * @param key the log key
   * @param v the value
   */
  public static <E extends Enum<E>> void log(String key, E v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, enumBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a boolean array at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, boolean[] v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer array at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, long[] v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double array at STANDARD tier. See {@link #critical(String, double[], Unit)} for why the
   * unit is mandatory.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit every element is measured in
   */
  public static void log(String key, double[] v, Unit unit) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a String array at STANDARD tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void log(String key, String[] v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, stringArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a unit-carrying measure at STANDARD tier.
   *
   * @param <U> the unit type
   * @param key the log key
   * @param v the value
   */
  public static <U extends Unit> void log(String key, Measure<U> v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a WPILib geometry value at STANDARD tier.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   */
  public static <T extends WPISerializable> void log(String key, T v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, structBytes(v.getClass()))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an array of WPILib geometry values at STANDARD tier.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   */
  public static <T extends StructSerializable> void log(String key, T[] v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, structArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a value with an explicit struct at STANDARD tier.
   *
   * @param <T> the value type
   * @param key the log key
   * @param struct the struct; first, exactly as {@code Logger.recordOutput} orders it
   * @param v the value
   */
  public static <T> void log(String key, Struct<T> struct, T v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, struct.getSize())) {
      Logger.recordOutput(key, struct, v);
    }
  }

  /**
   * Logs an array with an explicit struct at STANDARD tier.
   *
   * @param <T> the element type
   * @param key the log key
   * @param struct the element struct
   * @param v the value
   */
  public static <T> void log(String key, Struct<T> struct, T[] v) {
    if (gate(key, Tier.STANDARD, Demotable.NO, struct.getSize() * v.length)) {
      Logger.recordOutput(key, struct, v);
    }
  }

  // ===============================================================================================
  // Demotable twins — the parameter is ALWAYS last
  //
  // Omitting the parameter means Demotable.NO. There is no way to make a key demotable by accident,
  // and no way for the governor to demote a key the author did not mark. There is deliberately no
  // processInputs(..., Demotable) overload: see Demotable.
  // ===============================================================================================

  /**
   * Logs a boolean at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d whether the governor may sample this key slower under sustained overload
   */
  public static void critical(String key, boolean v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 1)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, long v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, double v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at CRITICAL tier with unit metadata, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit
   * @param d the demotion marking
   */
  public static void critical(String key, double v, Unit unit, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8)) {
      Logger.recordOutput(key, v, unit);
    }
  }

  /**
   * Logs a String at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, String v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, utf8Length(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an enum constant at CRITICAL tier, marking it for the governor.
   *
   * @param <E> the enum type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <E extends Enum<E>> void critical(String key, E v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, enumBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a boolean array at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, boolean[] v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer array at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, long[] v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double array at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit every element is measured in
   * @param d the demotion marking
   */
  public static void critical(String key, double[] v, Unit unit, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a String array at CRITICAL tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void critical(String key, String[] v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, stringArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a measure at CRITICAL tier, marking it for the governor.
   *
   * @param <U> the unit type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <U extends Unit> void critical(String key, Measure<U> v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a WPILib geometry value at CRITICAL tier, marking it for the governor.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <T extends WPISerializable> void critical(String key, T v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, structBytes(v.getClass()))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an array of WPILib geometry values at CRITICAL tier, marking it for the governor.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <T extends StructSerializable> void critical(String key, T[] v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, structArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a value with an explicit struct at CRITICAL tier, marking it for the governor.
   *
   * @param <T> the value type
   * @param key the log key
   * @param struct the struct
   * @param v the value
   * @param d the demotion marking
   */
  public static <T> void critical(String key, Struct<T> struct, T v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, struct.getSize())) {
      Logger.recordOutput(key, struct, v);
    }
  }

  /**
   * Logs an array with an explicit struct at CRITICAL tier, marking it for the governor.
   *
   * @param <T> the element type
   * @param key the log key
   * @param struct the element struct
   * @param v the value
   * @param d the demotion marking
   */
  public static <T> void critical(String key, Struct<T> struct, T[] v, Demotable d) {
    if (gate(key, Tier.CRITICAL, d, struct.getSize() * v.length)) {
      Logger.recordOutput(key, struct, v);
    }
  }

  /**
   * Logs a boolean at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, boolean v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 1)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, long v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, double v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at STANDARD tier with unit metadata, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit
   * @param d the demotion marking
   */
  public static void log(String key, double v, Unit unit, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8)) {
      Logger.recordOutput(key, v, unit);
    }
  }

  /**
   * Logs a String at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, String v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, utf8Length(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an enum constant at STANDARD tier, marking it for the governor.
   *
   * @param <E> the enum type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <E extends Enum<E>> void log(String key, E v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, enumBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a boolean array at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, boolean[] v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer array at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, long[] v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double array at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit every element is measured in
   * @param d the demotion marking
   */
  public static void log(String key, double[] v, Unit unit, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8 * v.length)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a String array at STANDARD tier, marking it for the governor.
   *
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static void log(String key, String[] v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, stringArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a measure at STANDARD tier, marking it for the governor.
   *
   * @param <U> the unit type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <U extends Unit> void log(String key, Measure<U> v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a WPILib geometry value at STANDARD tier, marking it for the governor.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <T extends WPISerializable> void log(String key, T v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, structBytes(v.getClass()))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an array of WPILib geometry values at STANDARD tier, marking it for the governor.
   *
   * @param <T> the geometry type
   * @param key the log key
   * @param v the value
   * @param d the demotion marking
   */
  public static <T extends StructSerializable> void log(String key, T[] v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, structArrayBytes(v))) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a value with an explicit struct at STANDARD tier, marking it for the governor.
   *
   * @param <T> the value type
   * @param key the log key
   * @param struct the struct
   * @param v the value
   * @param d the demotion marking
   */
  public static <T> void log(String key, Struct<T> struct, T v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, struct.getSize())) {
      Logger.recordOutput(key, struct, v);
    }
  }

  /**
   * Logs an array with an explicit struct at STANDARD tier, marking it for the governor.
   *
   * @param <T> the element type
   * @param key the log key
   * @param struct the element struct
   * @param v the value
   * @param d the demotion marking
   */
  public static <T> void log(String key, Struct<T> struct, T[] v, Demotable d) {
    if (gate(key, Tier.STANDARD, d, struct.getSize() * v.length)) {
      Logger.recordOutput(key, struct, v);
    }
  }

  // ===============================================================================================
  // DEBUG — dropped when FMS-attached
  //
  // Primitives take values (free to evaluate). Reference types take SUPPLIERS so the value is never
  // constructed when the tier is off. DEBUG keys are never demotable: they are already gone before
  // the governor can matter.
  // ===============================================================================================

  /**
   * Logs a boolean at DEBUG tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void debug(String key, boolean v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 1)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs an integer at DEBUG tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void debug(String key, long v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at DEBUG tier.
   *
   * @param key the log key
   * @param v the value
   */
  public static void debug(String key, double v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 8)) {
      Logger.recordOutput(key, v);
    }
  }

  /**
   * Logs a double at DEBUG tier with unit metadata.
   *
   * @param key the log key
   * @param v the value
   * @param unit the unit
   */
  public static void debug(String key, double v, Unit unit) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 8)) {
      Logger.recordOutput(key, v, unit);
    }
  }

  /**
   * Logs a lazily-evaluated boolean at DEBUG tier.
   *
   * @param key the log key
   * @param v the supplier; never invoked when the tier is off
   */
  public static void debug(String key, BooleanSupplier v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 1)) {
      Logger.recordOutput(key, v.getAsBoolean());
    }
  }

  /**
   * Logs a lazily-evaluated integer at DEBUG tier.
   *
   * @param key the log key
   * @param v the supplier; never invoked when the tier is off
   */
  public static void debug(String key, LongSupplier v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 8)) {
      Logger.recordOutput(key, v.getAsLong());
    }
  }

  /**
   * Logs a lazily-evaluated double at DEBUG tier with unit metadata.
   *
   * @param key the log key
   * @param v the supplier; never invoked when the tier is off
   * @param unit the unit
   */
  public static void debug(String key, DoubleSupplier v, Unit unit) {
    if (gate(key, Tier.DEBUG, Demotable.NO, 8)) {
      Logger.recordOutput(key, v.getAsDouble(), unit);
    }
  }

  /**
   * Logs a lazily-built String at DEBUG tier.
   *
   * <p>This is the overload that makes string concatenation on the hot path free when the tier is off,
   * which is the single most common way a debug log call becomes a loop-time problem.
   *
   * @param key the log key
   * @param v the supplier; never invoked when the tier is off
   */
  public static void debug(String key, Supplier<String> v) {
    if (!tierEnabled(Tier.DEBUG)) {
      return;
    }
    String value = v.get();
    if (gate(key, Tier.DEBUG, Demotable.NO, utf8Length(value))) {
      Logger.recordOutput(key, value);
    }
  }

  /**
   * Logs a lazily-built struct value at DEBUG tier.
   *
   * @param <T> the value type
   * @param key the log key
   * @param struct the struct
   * @param v the supplier; never invoked when the tier is off
   */
  public static <T> void debug(String key, Struct<T> struct, Supplier<T> v) {
    if (gate(key, Tier.DEBUG, Demotable.NO, struct.getSize())) {
      Logger.recordOutput(key, struct, v.get());
    }
  }

  /**
   * Logs a lazily-built struct <b>array</b> at DEBUG tier — the shape that carries
   * {@code Rootstock/Vision/<Camera>/AllTagPoses}, the single biggest documented loop-time offender in
   * 2026, which is precisely why it is supplier-gated.
   *
   * <p><b>Named {@code debugArray}, not {@code debug}.</b> The design declares this as a second
   * {@code debug(String, Struct<T>, Supplier<T[]>)} overload, and that does not compile: generic erasure
   * makes it identical to {@link #debug(String, Struct, Supplier)}, so javac rejects the pair with a
   * name clash. A distinct name is the only fix that keeps both shapes; the alternative — dropping one —
   * would leave the fattest DEBUG key in the library with no supplier-gated call.
   *
   * @param <T> the element type
   * @param key the log key
   * @param struct the element struct
   * @param v the supplier; never invoked when the tier is off
   */
  public static <T> void debugArray(String key, Struct<T> struct, Supplier<T[]> v) {
    if (!tierEnabled(Tier.DEBUG)) {
      return;
    }
    T[] value = v.get();
    if (gate(key, Tier.DEBUG, Demotable.NO, struct.getSize() * value.length)) {
      Logger.recordOutput(key, struct, value);
    }
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  /**
   * The one gate every publishing call passes through: tier, then demotion marking, then the governor's
   * current suppression, then byte accounting.
   *
   * @return true if the caller should delegate to {@code Logger}
   */
  private static boolean gate(String key, Tier tier, Demotable demotable, int payloadBytes) {
    if (!tierEnabled(tier)) {
      return false;
    }
    if (demotable == Demotable.YES) {
      RootstockBudget.markDemotable(key);
    }
    if (RootstockBudget.isSuppressedThisCycle(key)) {
      return false;
    }
    RootstockBudget.recordFacadeBytes(key, payloadBytes);
    return true;
  }

  private static boolean tierEnabled(Tier tier) {
    return tier.ordinal() <= effectiveMinimumTier().ordinal();
  }

  private static RobotMode resolveMode(LogConfig config) {
    if (Logger.hasReplaySource()) {
      return RobotMode.REPLAY;
    }
    if (Platform.isSimulation()) {
      return RobotMode.SIM;
    }
    return config.mode() == RobotMode.REPLAY ? RobotMode.REAL : config.mode();
  }

  private static void metadataProblem(String message) {
    if (m_metadataAlert == null) {
      m_metadataAlert = Alerts.warning(kAlertGroup, message, MatchImpact.PIT_ONLY);
    } else {
      m_metadataAlert.text(message);
    }
    m_metadataAlert.sticky(true).set(true);
  }

  /**
   * UTF-8 byte length without allocating a byte array.
   *
   * <p>{@code String.getBytes(UTF_8)} on a hot path allocates once per call, which is exactly the kind
   * of per-cycle garbage the tier design exists to avoid, and it would be allocated purely to measure
   * something.
   */
  private static int utf8Length(String s) {
    if (s == null) {
      return 0;
    }
    int bytes = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  private static int enumBytes(Enum<?> v) {
    return v == null ? 0 : utf8Length(v.name());
  }

  /** WPILOG string arrays carry a 4-byte count and a 4-byte length per element. */
  private static int stringArrayBytes(String[] v) {
    int bytes = 4;
    for (String s : v) {
      bytes += 4 + utf8Length(s);
    }
    return bytes;
  }

  private static int structArrayBytes(Object[] v) {
    if (v.length == 0) {
      return 0;
    }
    return structBytes(v.getClass().getComponentType()) * v.length;
  }

  /**
   * The struct payload size of a WPILib geometry type, read once per class from its static
   * {@code struct} field and cached.
   *
   * <p>Reflection is acceptable here precisely because it is once per class, at first log, and never
   * per cycle. A type that does not follow the convention falls back to {@link #kUnknownStructBytes}
   * rather than failing: a wrong byte estimate makes the governor fire slightly early, and a thrown
   * exception makes the robot stop.
   */
  private static int structBytes(Class<?> type) {
    Integer cached = m_structSizes.get(type);
    if (cached != null) {
      return cached;
    }
    int size = kUnknownStructBytes;
    try {
      Field field = type.getField("struct");
      Object value = field.get(null);
      if (value instanceof Struct<?> struct) {
        size = struct.getSize();
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      size = kUnknownStructBytes;
    }
    m_structSizes.put(type, size);
    return size;
  }

  /**
   * An {@code NT4Publisher} that stops mirroring the full log stream while the FMS is attached.
   *
   * <p>6328's {@code NoFMSNT4Publisher} pattern: at an event the full log stream over NT is bandwidth
   * the match does not have. It reads {@code MatchContext.isFMSAttached()} rather than
   * {@code DriverStation} — {@code core.match} is the library's only permitted reader — and
   * {@code MatchContext} latches on the DS-connect edge, so a driver station that drops out mid-match
   * does not un-suppress the stream in the middle of a match.
   *
   * <p>The driver mirror is a different thing and is never suppressed: it is ~30 low-rate topics and the
   * drivers need them.
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

  /**
   * The per-key wrapper that makes bucket B <i>visible</i> without making it governable.
   *
   * <p>Allocated once per key at the first {@link #processInputs} call and never per cycle. Its
   * {@code toLog} delegates to the real struct and then, on the once-per-second attribution cycle only
   * and never in {@code REPLAY}, walks the subtable the delegate just wrote and sizes each field. On
   * every other cycle it re-uses the last measured total, so the governor has a per-cycle number at
   * once-per-second measurement cost.
   *
   * <p>Walking {@code getAll(true)} is a {@code HashMap} iteration, which determinism otherwise forbids.
   * It is safe here for a stated reason and not by exception: the result is an <b>integer sum</b>, and
   * integer addition is associative and commutative, so iteration order cannot affect the total.
   *
   * <p>{@code fromLog} is a straight delegate, which is what makes replay cost nothing.
   */
  private static final class SizingInputs implements LoggableInputs {

    private final String m_key;
    private LoggableInputs m_delegate;
    private int m_lastTotalBytes;

    SizingInputs(String key, LoggableInputs delegate) {
      m_key = key;
      m_delegate = delegate;
    }

    void rebind(LoggableInputs delegate) {
      m_delegate = delegate;
    }

    @Override
    public void toLog(LogTable table) {
      m_delegate.toLog(table);
      if (isReplay()) {
        return;
      }
      if (!RootstockBudget.isAttributionCycle()) {
        RootstockBudget.addInputBytes(m_lastTotalBytes);
        return;
      }
      int total = 0;
      for (Map.Entry<String, LogTable.LogValue> entry : table.getAll(true).entrySet()) {
        int payload = payloadBytes(entry.getValue());
        RootstockBudget.recordInputBytes(m_key + "/" + entry.getKey(), payload);
        total += payload + RootstockBudget.kHeaderBytes;
      }
      m_lastTotalBytes = total;
    }

    @Override
    public void fromLog(LogTable table) {
      m_delegate.fromLog(table);
    }

    /** WPILOG payload size of one already-written field, from its logged type. */
    private static int payloadBytes(LogTable.LogValue value) {
      switch (value.type) {
        case Boolean:
          return 1;
        case Integer:
          return 8;
        case Float:
          return 4;
        case Double:
          return 8;
        case String:
          return utf8Length(value.getString(""));
        case BooleanArray:
          return value.getBooleanArray(new boolean[0]).length;
        case IntegerArray:
          return 8 * value.getIntegerArray(new long[0]).length;
        case FloatArray:
          return 4 * value.getFloatArray(new float[0]).length;
        case DoubleArray:
          return 8 * value.getDoubleArray(new double[0]).length;
        case StringArray:
          return stringArrayBytes(value.getStringArray(new String[0]));
        case Raw:
          return value.getRaw(new byte[0]).length;
        default:
          return 0;
      }
    }
  }

  /**
   * Raises the one-shot warning that a large hardware library bypassing the IO layer cannot be
   * replayed.
   *
   * <p>CTRE's {@code SwerveDrivetrain} and YAGSL's {@code SwerveDrive} own their own hardware access, so
   * a robot using either has a valid replay for everything except the drivetrain — the most important
   * thing in the log. This is a loud, named warning and not an error: the robot still drives, and
   * refusing to boot over a logging property would be the diagnostic becoming the outage.
   *
   * <p>Unconditional, where it used to fire only when a replay-capable backend had been selected. There
   * is only one backend now.
   */
  public static void warnIfDriveBypassesIo() {
    String found = null;
    for (String type :
        List.of("com.ctre.phoenix6.swerve.SwerveDrivetrain", "swervelib.SwerveDrive")) {
      try {
        Class.forName(type, false, RootstockLog.class.getClassLoader());
        found = type;
        break;
      } catch (ClassNotFoundException | LinkageError e) {
        // Not on the classpath, which is the common and good case.
      }
    }
    if (found == null) {
      return;
    }
    String message =
        "REPLAY_INCOMPLETE: "
            + found
            + " is on the classpath. It owns its own hardware access, so its reads never pass through "
            + "processInputs and cannot be replayed. Everything else in this log replays; the "
            + "drivetrain does not. Fix: drive through Rootstock's drive IO, or accept that "
            + "drivetrain traces in a replay are the replay's own recomputation and not the match's.";
    if (m_bypassAlert == null) {
      m_bypassAlert = Alerts.warning(kAlertGroup, message, MatchImpact.PIT_ONLY);
    }
    m_bypassAlert.sticky(true).set(true);
  }
}

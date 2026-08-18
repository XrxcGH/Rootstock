package org.pumpkinlib.tuning;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.pumpkinlib.config.ConfigError;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.TuningTarget;
import org.pumpkinlib.core.PumpkinRegistry;
import org.pumpkinlib.core.SafeMode;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.config.ConfigRegistry;
import org.pumpkinlib.core.config.ConfigSnapshot;
import org.pumpkinlib.core.health.SliceScheduler;
import org.pumpkinlib.core.match.FmsPolicy;
import org.pumpkinlib.core.spi.LifecycleHook;
import org.pumpkinlib.core.spi.MechanismGeometry;
import org.pumpkinlib.tuning.persist.TunedValueStore;

/**
 * Process-wide owner of every tunable value and every {@link TuningTarget}.
 *
 * <p>Tuning mode is OFF by default under FMS and ON otherwise. When off, every {@code get()} returns
 * a cached primitive with zero NetworkTables traffic and zero allocation — the poller is never read,
 * so the disabled path costs one boolean check and an early return.
 *
 * <p><b>Publication is {@code /Tuning/<namespace>/<key>} and nowhere else.</b> That is the path
 * AdvantageScope's tuning mode edits and the path Elastic's Text Display and Number Slider bind to,
 * with zero setup in either. Wizard state, plots and metadata live under {@code /PumpkinTuner/} so
 * they never clutter the tuning tab.
 *
 * <p><b>Two lifecycle entry points, not one, and neither is a free-standing {@code periodic()} the
 * team calls:</b>
 *
 * <ul>
 *   <li><b>{@link LifecycleHook} priority {@value #kHookPriority}, every loop</b> —
 *       {@link #drainPoller()} performs the single {@code NetworkTableListenerPoller.readQueue()} and
 *       dispatches by topic name. This must run every loop or the queue grows without bound.
 *   <li><b>One {@code SliceScheduler} slice named {@value #kSliceName}</b> — {@link #slice()} does
 *       the rate-gated work: the 10 Hz {@code GainSink} write-through, the {@code /applied} echoes,
 *       and the metadata republish. The slice scheduler exists precisely so this class does not own
 *       a private rate gate.
 * </ul>
 *
 * <p>Hand {@link #hook()} to {@code PumpkinRegistry.addAll(...)} — or let it be registered explicitly
 * by {@code PumpkinLifecycle} — and both entry points are wired.
 */
public final class TuningRegistry {

  /** The NetworkTables table every tunable is published under. */
  public static final String kTable = "Tuning";

  /** The NT topic prefix, with both slashes, exactly as the poller's listener is registered. */
  public static final String kPrefix = "/" + kTable + "/";

  /** The alert group every message from this domain is filed under. */
  public static final String kAlertGroup = "Tuning";

  /** The {@code SliceScheduler} slice name, and the tracer section name that budgets it. */
  public static final String kSliceName = "Tuning";

  /** The {@link LifecycleHook} priority: after telemetry input capture, before user periodic. */
  public static final int kHookPriority = 30;

  /**
   * The ten {@code ControlConfig} topics that share {@code /Tuning/<Mechanism>/} with the seven
   * gains, in schema order.
   *
   * <p>These are in <b>user units</b> and are written by the mechanism layer, not by
   * {@link TunableGains}. They are named here because the NT schema is one contract and one list:
   * seventeen editable doubles under a mechanism namespace, and nothing else.
   */
  public static final List<String> kControlTunables =
      List.of(
          "iZone",
          "iMaxVolts",
          "maxVelocity",
          "maxAcceleration",
          "jerk",
          "tolerance",
          "velocityTolerance",
          "goalDebounceSeconds",
          "manualDeadband",
          "manualScale");

  /** The subtable persisted setpoints live in, keyed by {@code RobotId}. */
  public static final String kSetpointsSubtable = "Setpoints";

  /** How closely the plant prior's reduction must agree with the mechanism's own, as a fraction. */
  public static final double kReductionAgreementTolerance = 0.01;

  private TuningRegistry() {}

  // ---- state -------------------------------------------------------------------------------
  //
  // LinkedHashMap everywhere, never HashMap: replay rule 2 forbids iteration-order dependence in
  // anything that produces an output, and every one of these maps is walked to produce one.

  private static final Map<String, TunableDouble> s_doubles = new LinkedHashMap<>();
  private static final Map<String, TunableBoolean> s_flags = new LinkedHashMap<>();
  private static final Map<String, TuningTarget> s_targets = new LinkedHashMap<>();
  private static final Map<String, TunableGains> s_gains = new LinkedHashMap<>();
  private static final List<ConfigError> s_errors = new ArrayList<>();

  private static NetworkTableInstance s_instance = NetworkTableInstance.getDefault();
  private static AdvantageKitTunableTransport s_transport;
  private static boolean s_enabledOverride = true;
  private static boolean s_routesInstalled;
  private static Runnable s_metadataPublisher;
  private static PumpkinAlert s_fmsOverrideAlert;

  // ---- global mode -------------------------------------------------------------------------

  /**
   * Enable or disable live tuning. Call once from robot construction.
   *
   * <p>The default is "enabled unless the FMS is attached", re-evaluated on every read so a mid-match
   * FMS connection disables tuning without anybody having to remember to call anything. This method
   * is the manual override on top of that: {@code setTuningEnabled(false)} turns tuning off
   * everywhere, and {@code setTuningEnabled(true)} restores the FMS-gated default — it does
   * <b>not</b> defeat the FMS lockout, which is what {@link #allowUnderFms()} is for.
   *
   * <p>{@code MatchContext}/{@code FmsPolicy}, never {@code DriverStation}: those are the only
   * classes in the library permitted to read the driver station (ArchUnit rule 10), and they latch,
   * which is what makes the answer consistent within one loop.
   *
   * @param enabled false to turn live tuning off regardless of FMS state
   */
  public static void setTuningEnabled(boolean enabled) {
    s_enabledOverride = enabled;
  }

  /**
   * Whether live tuning is in force right now.
   *
   * <p>Constant time and allocation-free: one boolean field and one latched FMS read. This is the
   * predicate every {@code get()} on the hot path consults.
   *
   * @return true when dashboard edits reach robot code
   */
  public static boolean isTuningEnabled() {
    return s_enabledOverride && !FmsPolicy.tunablesLocked();
  }

  /**
   * Opt in to live tuning while connected to an FMS.
   *
   * <p>Raises a {@code BLOCKS_MATCH} warning for as long as it is active, and
   * {@code FmsPolicy.allowTunablesAtEvent(true)} additionally reports it to the driver station.
   * There is no way to do this silently, and that is the feature: a dashboard edit that can change
   * robot behaviour mid-match must be visible in the log next to whatever happened afterwards.
   */
  public static void allowUnderFms() {
    FmsPolicy.allowTunablesAtEvent(true);
    if (s_fmsOverrideAlert == null) {
      s_fmsOverrideAlert =
          Alerts.warning(
              kAlertGroup,
              "Live tuning is UNLOCKED while FMS-attached. A dashboard slider can change robot"
                  + " behaviour in the middle of a match. Fix: call"
                  + " FmsPolicy.allowTunablesAtEvent(false) as soon as you are done.",
              MatchImpact.BLOCKS_MATCH);
    }
    s_fmsOverrideAlert.set(true);
  }

  // ---- tunable creation --------------------------------------------------------------------

  /**
   * A standalone tunable double.
   *
   * <p>Example: {@code TuningRegistry.tunable("Vision", "maxTagDistance", 6.0, "m")}, which publishes
   * {@code /Tuning/Vision/maxTagDistance}.
   *
   * @param namespace the table under {@code /Tuning/}; must not contain a slash
   * @param key the leaf topic name; must not contain a slash
   * @param defaultValue the value compiled into the jar, and the value returned when tuning is off
   * @param unit the unit this number is in, so AdvantageScope can render it and a human can read it
   * @return the tunable; asking twice for the same key returns the same object
   */
  public static TunableDouble tunable(
      String namespace, String key, double defaultValue, String unit) {
    return tunable(namespace, key, defaultValue, unit, Double.NaN, Double.NaN, false);
  }

  /**
   * A standalone tunable double with a slider range.
   *
   * <p>The range is what makes {@code ElasticLayoutGenerator} emit a Number Slider instead of a Text
   * Display. It is a display hint and is never enforced on the value: a student who types 500 into a
   * 0..100 slider gets 500, because clamping a number a human deliberately typed is how a library
   * earns distrust.
   *
   * @param namespace the table under {@code /Tuning/}
   * @param key the leaf topic name
   * @param defaultValue the compile-time default
   * @param unit the unit this number is in
   * @param min the low end of the slider
   * @param max the high end of the slider
   * @return the tunable
   */
  public static TunableDouble tunable(
      String namespace, String key, double defaultValue, String unit, double min, double max) {
    return tunable(namespace, key, defaultValue, unit, min, max, true);
  }

  /**
   * A standalone tunable <b>boolean</b> — a pit switch, not a number.
   *
   * <p>Published as a plain boolean topic at {@code /Tuning/<namespace>/<key>}, drained by the same
   * single poller as every double (D11a), and off under FMS by the same default-deny rule as every
   * other tunable (D11).
   *
   * <p><b>Why a distinct name rather than a {@code tunable(...)} overload.</b> A boolean has no unit,
   * so it cannot take the {@code String unit} argument every {@code tunable(...)} overload requires,
   * and an overload set distinguished only by the type of the third argument makes the reader count
   * arguments to learn what they get back. The returned type differs and so does the wire type, so
   * the name differs too.
   *
   * <p>A namespace that collides with a registered mechanism name is <b>refused</b>: a boolean
   * directly under {@code /Tuning/<Mechanism>/} would break the seventeen-double schema that the
   * shipped Elastic layout and the persisted file format are both built on. The refusal is collected
   * as a FATAL {@link ConfigError}, never thrown, and the returned handle is pinned to its default.
   *
   * @param namespace the table under {@code /Tuning/}
   * @param key the leaf topic name
   * @param defaultValue the compile-time default
   * @return the flag; asking twice for the same key returns the same object
   */
  public static TunableBoolean tunableFlag(String namespace, String key, boolean defaultValue) {
    String cleanNamespace = requireSegment(namespace, "namespace");
    String cleanKey = requireSegment(key, "key");
    String fullKey = kPrefix + cleanNamespace + "/" + cleanKey;

    TunableBoolean existing = s_flags.get(fullKey);
    if (existing != null) {
      return existing;
    }
    if (s_targets.containsKey(cleanNamespace)) {
      collect(
          ConfigError.fatal(
              cleanNamespace,
              "tunableFlag(\"" + cleanNamespace + "\", \"" + cleanKey + "\")",
              fullKey,
              "a namespace that is not a registered mechanism name",
              "\""
                  + cleanNamespace
                  + "\" is already a registered mechanism, and /Tuning/"
                  + cleanNamespace
                  + "/ holds exactly seventeen editable doubles — the seven gains plus the ten"
                  + " ControlConfig values. A boolean there breaks the schema the shipped Elastic"
                  + " layout and the gains.json file format are both built on. Fix: give the flag"
                  + " its own namespace, for example \""
                  + cleanNamespace
                  + "Pit\"."));
    }

    TunableBoolean flag =
        new TunableBoolean(
            cleanNamespace, cleanKey, fullKey, defaultValue, transport().registerFlag(fullKey, defaultValue));
    s_flags.put(fullKey, flag);
    return flag;
  }

  /**
   * The seven-gain set for one mechanism. This is what mechanisms use.
   *
   * <p>Idempotent: the gains for a target are built once, at {@link #register(TuningTarget)}, and
   * every later call returns the same object. Calling this for an unregistered target registers it
   * first, so a hand-rolled subsystem that only wants gains does not have to know about
   * registration.
   *
   * @param target the mechanism
   * @return its gain set
   */
  public static TunableGains gains(TuningTarget target) {
    String name = target.tuningName();
    TunableGains existing = s_gains.get(name);
    if (existing != null) {
      return existing;
    }
    register(target);
    return s_gains.get(name);
  }

  // ---- target registration -----------------------------------------------------------------

  /**
   * Register a mechanism with the tuning system.
   *
   * <p>Returns the <b>collected</b> configuration errors rather than throwing any of them —
   * {@code TravelLimits.validate}, {@code PlantPrior.validate}, a duplicate name and a bad namespace
   * all land here. {@code PumpkinRegistry.addAll} (D27) is the normal caller, and it prints every
   * error in the robot at once and enters SAFE_MODE if any is FATAL. A robot that will not boot
   * because one number is wrong tells a student nothing; a robot that boots into safe mode and prints
   * all four problems tells them everything.
   *
   * <p>Registration also performs the four-tier value resolution (code default, deploy file, robot
   * file, dashboard) and applies the resolved gains through the target's sink exactly once, before
   * the mechanism's first closed-loop command.
   *
   * @param target the mechanism
   * @return every problem found, in the order found; empty when the registration succeeded
   */
  public static List<ConfigError> register(TuningTarget target) {
    return register(target, null);
  }

  /**
   * Register a mechanism, additionally running the <b>D7 reduction cross-check</b> against the
   * geometry the mechanism actually declared.
   *
   * <p>{@code PlantPrior.reduction().rotorPerOutput()} must equal {@code geometry.rotorPerOutput()}
   * within {@value #kReductionAgreementTolerance} (1%). If they disagree the registration is refused
   * with a FATAL {@link ConfigError} naming both numbers — because the wizard sanity-bounds every
   * fitted gain against the prior, and a prior built on a different gearbox makes the wizard
   * confidently reject correct fits. A student then spends an afternoon believing their elevator is
   * broken when their constants file is.
   *
   * @param target the mechanism
   * @param geometry the mechanism's own declared geometry, or null to skip the cross-check
   * @return every problem found; empty when the registration succeeded
   */
  public static List<ConfigError> register(TuningTarget target, MechanismGeometry geometry) {
    List<ConfigError> errors = new ArrayList<>();
    if (target == null) {
      errors.add(
          ConfigError.fatal(
              "(unnamed mechanism)",
              "TuningRegistry.register(target)",
              "null",
              "a TuningTarget",
              "register(null) was called. Fix: pass the mechanism, not the field it has not been"
                  + " assigned to yet — a static field initialiser that reads another static field"
                  + " declared below it sees null."));
      return record(errors);
    }

    String name = target.tuningName();
    if (name == null || name.isBlank() || name.indexOf('/') >= 0) {
      errors.add(
          ConfigError.fatal(
              String.valueOf(name),
              "tuningName()",
              String.valueOf(name),
              "a non-blank name with no '/'",
              "a mechanism's tuning name becomes its NetworkTables namespace, so a slash in it would"
                  + " silently create a subtable and every dashboard binding would point at nothing."
                  + " Fix: return something like \"Elevator\" or \"FrontLeftSteer\"."));
      return record(errors);
    }
    if (s_targets.containsKey(name)) {
      errors.add(
          ConfigError.fatal(
              name,
              "tuningName()",
              name,
              "a name unique across every registered mechanism",
              "two mechanisms both call themselves \""
                  + name
                  + "\". They would share one /Tuning/"
                  + name
                  + "/ table, so editing one mechanism's kP would move the other's. Fix: name them"
                  + " for what they are — \"ElevatorLeft\" and \"ElevatorRight\", not \"Elevator\""
                  + " twice."));
      return record(errors);
    }

    for (String problem : target.travelLimits().validate(name)) {
      errors.add(ConfigError.of(ConfigError.Severity.FATAL, name, "travelLimits", problem));
    }
    for (String problem : target.plantPrior().validate(name)) {
      errors.add(ConfigError.of(ConfigError.Severity.FATAL, name, "plantPrior", problem));
    }
    if (geometry != null) {
      checkReductionAgreement(name, target, geometry, errors);
    }

    boolean fatal = errors.stream().anyMatch(ConfigError::isFatal);
    if (fatal) {
      return record(errors);
    }

    // Four-tier resolution happens here, once, before the first gainSink().apply(...).
    String hash = configHash(target);
    TunedValueStore.Resolved resolved =
        TunedValueStore.resolve(name, hash, target.gains() == null ? Gains.UNTUNED : target.gains());
    for (String warning : resolved.warnings()) {
      errors.add(ConfigError.of(ConfigError.Severity.WARNING, name, "gains.json", warning));
    }

    s_targets.put(name, target);
    TunableGains gains = new TunableGains(target, name, resolved.gains(), s_instance);
    s_gains.put(name, gains);

    // Push the resolved values through the sink now, so the mechanism's first closed-loop command
    // runs the number the file says rather than the number the jar says.
    gains.set(resolved.gains());

    return record(errors);
  }

  /**
   * Every registered target, in registration order.
   *
   * @return an immutable snapshot
   */
  public static List<TuningTarget> targets() {
    return List.copyOf(s_targets.values());
  }

  /**
   * One registered target by name.
   *
   * @param name the mechanism's {@code tuningName()}
   * @return the target, or empty when nothing by that name is registered
   */
  public static Optional<TuningTarget> target(String name) {
    return Optional.ofNullable(s_targets.get(name));
  }

  /**
   * One registered mechanism's gain set by name.
   *
   * @param name the mechanism's {@code tuningName()}
   * @return the gains, or empty when nothing by that name is registered
   */
  public static Optional<TunableGains> gainsFor(String name) {
    return Optional.ofNullable(s_gains.get(name));
  }

  /**
   * Every tunable double created this session, keyed by full NT key, in creation order.
   *
   * @return an immutable snapshot
   */
  public static Map<String, TunableDouble> tunables() {
    return Map.copyOf(s_doubles);
  }

  /**
   * Every tunable flag created this session, keyed by full NT key, in creation order.
   *
   * @return an immutable snapshot
   */
  public static Map<String, TunableBoolean> flags() {
    return Map.copyOf(s_flags);
  }

  /**
   * Every configuration error collected by this registry, from registrations and from refused
   * namespace collisions alike.
   *
   * <p>Collected rather than thrown, and surfaced through {@code PumpkinRegistry}'s fault extractor
   * so they arrive in the same SAFE_MODE report as every other config problem in the robot.
   *
   * @return an immutable snapshot, in the order the problems were found
   */
  public static List<ConfigError> errors() {
    return List.copyOf(s_errors);
  }

  /**
   * A stable identifier for the physical configuration a set of gains was tuned against.
   *
   * <p>Persisted alongside every tuned value. When it changes — a new gearbox, a different drum, a
   * re-rigged elevator — the persisted file is ignored rather than applied, because gains silently
   * surviving a mechanical change is how a robot gets destroyed after a rebuild.
   *
   * <p>Derived from the plant prior and the travel limits, which are exactly the quantities a
   * mechanical change moves. Rendered as sixteen hex digits so it fits on one line of a JSON file a
   * human is expected to read.
   *
   * @param target the mechanism
   * @return sixteen lowercase hex digits
   */
  public static String configHash(TuningTarget target) {
    StringBuilder sb = new StringBuilder(160);
    sb.append(target.archetype().name())
        .append('|')
        .append(target.siDomain().name())
        .append('|')
        .append(target.plantPrior().describe())
        .append('|')
        .append(target.travelLimits().describe(""));
    // FNV-1a, 64-bit: stable across JVMs and across runs, unlike String.hashCode()'s 32 bits, and
    // short enough to read aloud across a pit.
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < sb.length(); i++) {
      hash ^= sb.charAt(i);
      hash *= 0x100000001b3L;
    }
    return String.format(Locale.ROOT, "%016x", hash);
  }

  /**
   * The transport every tunable in the robot reads through.
   *
   * <p>Created lazily on first use, so a robot that never tunes anything never touches
   * NetworkTables from this package.
   *
   * @return the one shipped transport
   */
  public static TunableTransport transport() {
    if (s_transport == null) {
      s_transport = new AdvantageKitTunableTransport(s_instance);
    }
    return s_transport;
  }

  /**
   * Installs the publisher that {@link #slice()} calls to republish per-mechanism metadata.
   *
   * <p>Push rather than pull, for the same reason {@code ConfigRegistry.registerTunable} is push:
   * this class must not name the UI package, so the UI hands itself in.
   *
   * @param publisher what to run on the metadata cadence; null uninstalls
   */
  public static void setMetadataPublisher(Runnable publisher) {
    s_metadataPublisher = publisher;
  }

  /**
   * Point this registry at a specific NetworkTables instance.
   *
   * <p>For tests, which run against a private instance so one test cannot see another test's topics.
   * Must be called before the first tunable is created.
   *
   * @param instance the instance to publish and listen on
   */
  public static void setNetworkTableInstance(NetworkTableInstance instance) {
    if (instance != null) {
      s_instance = instance;
    }
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  /**
   * The {@link LifecycleHook} that wires both entry points.
   *
   * <p>Pass it to {@code PumpkinRegistry.addAll(...)} in the robot constructor, or let
   * {@code PumpkinLifecycle} register it explicitly. Its {@code init()} installs the
   * {@value #kSliceName} slice, the {@code PumpkinRegistry} route that routes every
   * {@link TuningTarget} here, and the fault extractor that carries this registry's collected errors
   * into the same SAFE_MODE report as every other config problem.
   *
   * @return the singleton hook
   */
  public static LifecycleHook hook() {
    return Hook.kInstance;
  }

  /**
   * {@code LifecycleHook} priority {@value #kHookPriority}, called from
   * {@code PumpkinLifecycle.beforeUserPeriodic()}, BEFORE subsystem periodic.
   *
   * <p>Performs exactly one {@code readQueue()} and dispatches the events. Costs one boolean check
   * and one early return when tuning is disabled and nothing has ever been logged — and once the
   * struct <i>has</i> been logged it keeps being logged every cycle, unconditionally, because a
   * registered input key that stops being processed is a replay-tripwire violation rather than an
   * optimisation.
   */
  static void drainPoller() {
    AdvantageKitTunableTransport transport = s_transport;
    if (transport == null) {
      return;
    }
    if (!isTuningEnabled() && !transport.hasLoggedInputs()) {
      return;
    }
    transport.drain();
  }

  /**
   * The {@code SliceScheduler} slice.
   *
   * <p>Rate-gated work only: {@code TunableGains.checkAndApply()}, the {@code /applied} echoes, and
   * the per-mechanism metadata republish. Nothing here may touch the wire more than the round-robin
   * allows, which is the whole reason the work is a slice and not a second every-loop hook.
   */
  static void slice() {
    if (!isTuningEnabled()) {
      return;
    }
    for (TunableGains gains : s_gains.values()) {
      gains.checkAndApply();
    }
    Runnable publisher = s_metadataPublisher;
    if (publisher != null) {
      publisher.run();
    }
  }

  /**
   * One line per fact a pit crew might need, for the boot dump.
   *
   * @return a multi-line report
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder(320);
    sb.append("TuningRegistry: ")
        .append(isTuningEnabled() ? "ENABLED" : "DISABLED")
        .append(s_enabledOverride ? "" : " (turned off in code)")
        .append(FmsPolicy.tunablesLocked() ? " (FMS lockout in force)" : "")
        .append(System.lineSeparator());
    sb.append("  ")
        .append(s_targets.size())
        .append(" mechanism(s), ")
        .append(s_doubles.size())
        .append(" tunable double(s), ")
        .append(s_flags.size())
        .append(" flag(s), ")
        .append(s_errors.size())
        .append(" collected error(s)")
        .append(System.lineSeparator());
    sb.append("  ").append(s_transport == null ? "transport not built yet" : s_transport.describe());
    return sb.toString();
  }

  /** Clears every static, so one test cannot see another test's tunables. */
  public static void resetForTest() {
    if (s_transport != null) {
      for (TunableGains gains : s_gains.values()) {
        gains.close();
      }
      s_transport.close();
    }
    s_transport = null;
    s_doubles.clear();
    s_flags.clear();
    s_targets.clear();
    s_gains.clear();
    s_errors.clear();
    s_enabledOverride = true;
    s_routesInstalled = false;
    s_metadataPublisher = null;
    s_fmsOverrideAlert = null;
    s_instance = NetworkTableInstance.getDefault();
  }

  // ---- internals ---------------------------------------------------------------------------

  private static TunableDouble tunable(
      String namespace,
      String key,
      double defaultValue,
      String unit,
      double min,
      double max,
      boolean hasRange) {
    String cleanNamespace = requireSegment(namespace, "namespace");
    String cleanKey = requireSegment(key, "key");
    String fullKey = kPrefix + cleanNamespace + "/" + cleanKey;

    TunableDouble existing = s_doubles.get(fullKey);
    if (existing != null) {
      return existing;
    }
    TunableDouble tunable =
        new TunableDouble(
            cleanNamespace,
            cleanKey,
            fullKey,
            defaultValue,
            unit == null ? "" : unit,
            min,
            max,
            hasRange,
            transport().register(fullKey, defaultValue));
    s_doubles.put(fullKey, tunable);

    // Push-register with the config registry so the snapshot and the drift check see live tuned
    // values without core ever naming this package (ArchUnit rule 9).
    ConfigRegistry.registerTunable(cleanNamespace + "/" + cleanKey, tunable, defaultValue);
    return tunable;
  }

  private static void checkReductionAgreement(
      String name, TuningTarget target, MechanismGeometry geometry, List<ConfigError> errors) {
    double prior = target.plantPrior().reduction().rotorPerOutput();
    double declared = geometry.rotorPerOutput();
    if (!(prior > 0) || !(declared > 0)) {
      return;
    }
    double disagreement = Math.abs(prior - declared) / declared;
    if (disagreement <= kReductionAgreementTolerance) {
      return;
    }
    errors.add(
        ConfigError.fatal(
            name,
            "plantPrior.reduction",
            String.format(Locale.ROOT, "%.4f rotor turns per output turn", prior),
            String.format(Locale.ROOT, "%.4f, the mechanism's own reduction", declared),
            "the plant prior and the mechanism disagree about the gearbox by "
                + String.format(Locale.ROOT, "%.1f%%", disagreement * 100.0)
                + ". The tuner sanity-bounds every fitted gain against the prior, so a prior built"
                + " on a different gearbox makes the tuner confidently reject correct fits and a"
                + " student spends an afternoon believing the mechanism is broken. Fix: build both"
                + " from the same Reduction constant — declare it once and pass it to both."));
  }

  private static String requireSegment(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "TuningRegistry: the "
              + what
              + " was "
              + (value == null ? "null" : "blank")
              + ". Every tunable is published at /Tuning/<namespace>/<key>, so both halves must be"
              + " real names — for example tunable(\"Vision\", \"maxTagDistance\", 6.0, \"m\").");
    }
    String trimmed = value.trim();
    if (trimmed.indexOf('/') >= 0) {
      throw new IllegalArgumentException(
          "TuningRegistry: the "
              + what
              + " \""
              + trimmed
              + "\" contains a '/'. A slash would silently create a subtable under /Tuning/ and"
              + " every dashboard widget bound to the key would point at nothing. Fix: use a single"
              + " name, for example \"maxTagDistance\".");
    }
    return trimmed;
  }

  private static List<ConfigError> record(List<ConfigError> errors) {
    s_errors.addAll(errors);
    return List.copyOf(errors);
  }

  private static void collect(ConfigError error) {
    s_errors.add(error);
    Alerts.error(kAlertGroup, error.summary(), MatchImpact.BLOCKS_MATCH).set(true);
  }

  private static void installRoutes() {
    if (s_routesInstalled) {
      return;
    }
    s_routesInstalled = true;

    PumpkinRegistry.addRoute(
        new PumpkinRegistry.Route(
            PumpkinRegistry.kRouteTuning,
            o -> o instanceof TuningTarget,
            o -> register((TuningTarget) o)));

    PumpkinRegistry.addFaultExtractor(
        o -> {
          if (!(o instanceof TuningTarget)) {
            return List.of();
          }
          String name = ((TuningTarget) o).tuningName();
          List<ConfigError> mine = new ArrayList<>();
          for (ConfigError error : s_errors) {
            if (error.owner().equals(name)) {
              mine.add(error);
            }
          }
          return ConfigError.toFaults(mine);
        });

    ConfigSnapshot.setTunableRestoreSink(
        (key, value) -> {
          TunableDouble tunable = s_doubles.get(kPrefix + key);
          if (tunable != null) {
            tunable.set(value);
          }
        });
  }

  /** The one hook. A separate type so {@link TuningRegistry} itself stays a static utility. */
  private static final class Hook implements LifecycleHook {

    static final Hook kInstance = new Hook();

    @Override
    public String name() {
      return kSliceName;
    }

    @Override
    public int priority() {
      return kHookPriority;
    }

    @Override
    public void init() {
      installRoutes();
      SliceScheduler.register(kSliceName, TuningRegistry::slice);
      if (!s_errors.isEmpty()) {
        SafeMode.enter(ConfigError.toFaults(s_errors));
      }
    }

    @Override
    public void beforeUserPeriodic() {
      drainPoller();
    }

    @Override
    public void close() {
      if (s_transport != null) {
        for (TunableGains gains : s_gains.values()) {
          gains.close();
        }
        s_transport.close();
        s_transport = null;
      }
    }
  }

  /** Compile-time proof that the seven published gain topics are generated, not typed. */
  static List<String> gainTopics() {
    List<String> out = new ArrayList<>(GainId.values().length);
    for (GainId id : GainId.values()) {
      out.add(id.key());
    }
    return List.copyOf(out);
  }
}

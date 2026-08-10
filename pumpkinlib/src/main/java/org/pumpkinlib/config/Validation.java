package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;

import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.core.PumpkinRegistry;
import org.pumpkinlib.core.SafeMode;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.config.CanIdRegistry;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.Axis;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;
import org.pumpkinlib.units.Range;
import org.pumpkinlib.units.RotaryAxis;
import org.pumpkinlib.units.SiDomain;

/**
 * The three-tier validation system, and the one place that decides what a wrong value <i>does</i>.
 *
 * <h2>It never throws. That is the whole design.</h2>
 *
 * <p>Every check here returns {@link ConfigError} values. They are collected by the config records'
 * compact constructors, gathered by {@code PumpkinRegistry.addAll(...)}, printed together once, and
 * — if any is fatal — used to put the robot into {@link SafeMode}, where it boots, connects, shows
 * the errors on the driver station and refuses to move. The alternative, which revision 1 of the
 * design specified and this implementation deliberately does not do, is a throw from a compact
 * constructor invoked by a {@code public static final} field initialiser: an
 * {@code ExceptionInInitializerError} whose top frames are JVM class-init machinery, no robot code,
 * and a message nobody sees.
 *
 * <h2>The three tiers</h2>
 *
 * <table border="1">
 *   <caption>What each severity does</caption>
 *   <tr><th>Tier</th><th>Meaning</th><th>Effect</th></tr>
 *   <tr><td>1 — {@link ConfigError.Severity#FATAL}</td>
 *       <td>structurally impossible: a zero reduction, swapped soft limits, a CAN id of 64, a
 *           feedback ratio that contradicts the gearbox</td>
 *       <td>the robot boots into safe mode and refuses every command</td></tr>
 *   <tr><td>2 — {@link ConfigError.Severity#WARNING}</td>
 *       <td>physically implausible but runnable: a cruise velocity above free speed, kG = 0 on a
 *           gravity-loaded arm, a tolerance narrower than one loop step</td>
 *       <td>a persistent alert and a log line; the team can still drive</td></tr>
 *   <tr><td>3 — {@link ConfigError.Severity#PLACEHOLDER}</td>
 *       <td>a value that is a blank rather than a measurement: {@code Gains.UNTUNED}, an
 *           undeclared sim mass, a 1:1 reduction nobody counted</td>
 *       <td>a line on the boot-time first-setup checklist</td></tr>
 * </table>
 *
 * <h2>Cross-config checks happen exactly once, globally (§5.6b)</h2>
 *
 * <p>Duplicate CAN ids, duplicate mechanism names and setpoint-name typos cannot be found from one
 * config alone, and they must not be found from a record constructor: every {@code with*()} copy
 * re-runs that constructor, so a registry side effect there would report a false conflict on the
 * per-robot overlay pattern that sibling robots depend on. {@link #crossChecks(Object...)} runs
 * once over the final resolved set, and the duplicate-id scan itself is
 * {@link CanIdRegistry#scanForConflicts}, which already names both colliding devices and where each
 * was declared.
 */
public final class Validation {

  private Validation() {}

  // ===========================================================================================
  // Sentinels — "this was never declared", distinguished by identity
  // ===========================================================================================

  /**
   * The motor group a config carries when {@code .motors(...)} was never called.
   *
   * <p>Compared by identity, so a team cannot accidentally construct one.
   */
  static final MotorGroup kNoMotors =
      MotorGroup.leader(MotorSpec.sim("(no motors declared)", MotorModel.BRUSHED_UNKNOWN));

  /** The axis a linear config carries when {@code .axis(...)} was never called. */
  static final Axis kUnsetLinearAxis = LinearAxis.drum(Inches.of(1.0), 1);

  /** The axis a rotary config carries when {@code .axis(...)} was never called. */
  static final Axis kUnsetRotaryAxis = RotaryAxis.turret(false);

  /** The limits a linear config carries when {@code .softLimits(...)} was never called. */
  static final PositionLimits kUnsetLinearLimits =
      PositionLimits.of(
          Meters.of(0.0), Meters.of(0.0), CurrentLimits.defaultsFor(MotorModel.BRUSHED_UNKNOWN));

  /** The limits a rotary config carries when {@code .softLimits(...)} was never called. */
  static final PositionLimits kUnsetRotaryLimits =
      PositionLimits.of(
          Degrees.of(0.0), Degrees.of(0.0), CurrentLimits.defaultsFor(MotorModel.BRUSHED_UNKNOWN));

  /** The homing strategy a config carries when {@code .homing(...)} was never called. */
  static final HomingStrategy kNoHoming = HomingStrategy.firstOf();

  /** The sim config a config carries when {@code .sim(...)} was never called. */
  static final SimConfig kUnsetSim =
      new SimConfig(null, null, null, Meters.of(SimConfig.kUnset), false);

  // ===========================================================================================
  // Thresholds — named, so a reader can see what "implausible" means here
  // ===========================================================================================

  /** A cruise velocity above this fraction of the free-speed estimate is warned about. */
  public static final double kCruiseFractionOfFreeSpeed = 1.0;

  /** The cruise velocity the warning suggests instead, as a fraction of free speed. */
  public static final double kSuggestedCruiseFraction = 0.80;

  /** Phoenix clamps {@code GravityArmPositionOffset} to this many degrees, silently. */
  public static final double kMaxGravityOffsetDegrees = 90.0;

  /** How closely the feedback ratios must reproduce the gearbox, relatively (design D2b). */
  public static final double kFeedbackRatioTolerance = 1e-3;

  // ===========================================================================================
  // Lookup misses — recorded, not thrown (see PositionConfig.setpoint)
  // ===========================================================================================

  private static final List<ConfigError> s_lookupMisses = new ArrayList<>();

  /**
   * Records a setpoint lookup that failed.
   *
   * <p>Package-private and deliberately global: a {@code Setpoint} handle is assigned to a
   * {@code public static final} field long before any registry exists, so the miss has nowhere else
   * to live. It is reported by {@link #crossChecks(Object...)} at registration time, which is the
   * first moment anything can print it.
   *
   * @param error the miss
   */
  static synchronized void recordLookupMiss(ConfigError error) {
    s_lookupMisses.add(error);
  }

  /**
   * Every setpoint lookup that failed since the JVM started.
   *
   * @return the misses, in the order they happened
   */
  public static synchronized List<ConfigError> lookupMisses() {
    return List.copyOf(s_lookupMisses);
  }

  /** Clears the recorded lookup misses. For tests only. */
  public static synchronized void resetForTest() {
    s_lookupMisses.clear();
  }

  // ===========================================================================================
  // Local checks — POSITION
  // ===========================================================================================

  /**
   * Every problem visible from a position config's own fields. <b>Never throws.</b>
   *
   * <p>Called from {@link PositionConfig}'s compact constructor, which means it is called again on
   * every {@code with*()} copy. It is therefore pure: no registry, no files, no global state.
   *
   * @param name the mechanism's name
   * @param motors the motor group
   * @param reduction the gearbox
   * @param axis the geometry
   * @param feedback the position sensor
   * @param limits the soft limits, hard stops and current limits
   * @param control the control block
   * @param homing the homing strategy
   * @param setpoints the named goals
   * @param sim the simulated plant
   * @return the errors, in the order a reader should meet them; empty when the config is clean
   */
  public static List<ConfigError> localChecks(
      String name,
      MotorGroup motors,
      Reduction reduction,
      Axis axis,
      FeedbackSpec feedback,
      PositionLimits limits,
      ControlConfig control,
      HomingStrategy homing,
      List<Setpoint> setpoints,
      SimConfig sim) {

    // A null component reaches here only when a caller invokes this method directly —
    // PositionConfig's compact constructor substitutes these same values before calling. It is
    // still normalised here, because "never throws" has to be true of the method and not merely
    // of its usual caller: a NullPointerException out of a static initialiser is precisely the
    // failure mode (§5.6) that the collected-error contract exists to prevent.
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? kNoMotors : motors;
    axis = axis == null ? kUnsetLinearAxis : axis;
    feedback = feedback == null ? new FeedbackSpec.RotorOnly() : feedback;
    limits =
        limits == null
            ? (axis.siDomain() == SiDomain.LINEAR_METERS ? kUnsetLinearLimits : kUnsetRotaryLimits)
            : limits;
    control = control == null ? ControlConfig.defaults() : control;
    homing = homing == null ? kNoHoming : homing;
    setpoints = setpoints == null ? List.of() : setpoints;
    sim = sim == null ? kUnsetSim : sim;
    // `reduction` is deliberately NOT substituted: checkAxisAndReduction() reports a null
    // reduction by name, which is a better message than silently pretending it was 1:1.

    List<ConfigError> out = new ArrayList<>();

    // ---- tier 1: the mechanism cannot be built as described ---------------------------------
    checkMotors(out, name, motors, control.location());
    checkAxisAndReduction(out, name, axis, reduction);
    checkLimits(out, name, axis, limits);
    checkFeedback(out, name, feedback, reduction);
    checkGravity(out, name, axis, control);
    lift(out, ConfigError.Severity.FATAL, name, "control", control.problems());
    checkHoming(out, name, homing, feedback);
    checkSetpoints(out, name, axis, limits, setpoints);
    checkToleranceDomain(out, name, axis, control);

    // ---- tier 2: it can be built, but it will not behave the way the numbers claim ----------
    MechanismUnits units = MechanismUnits.of(reduction, axis);
    checkCruiseVelocity(out, name, motors, units, control);
    checkGravityGain(out, name, control);
    checkTolerance(out, name, units, control);
    checkExpo(out, name, control);
    checkHomingPlausibility(out, name, homing, feedback, limits);
    checkHardStopLatency(out, name, limits);
    checkContinuousWithLimits(out, name, axis, limits);

    // ---- tier 3: values that are blanks ------------------------------------------------------
    // A rotor-only sensor is only worth listing when nothing physically finds a reference: an
    // elevator that drives into its bottom stop every boot has a real zero, and putting it on the
    // first-setup checklist would train a student to ignore the checklist.
    boolean noPhysicalReference = !homing.needsMotion();
    checkPlaceholders(
        out, name, reduction, feedback, control.gains(), sim, true, noPhysicalReference);

    return List.copyOf(out);
  }

  // ===========================================================================================
  // Local checks — VELOCITY
  // ===========================================================================================

  /**
   * Every problem visible from a velocity config's own fields. <b>Never throws.</b>
   *
   * @param name the mechanism's name
   * @param motors the motor group
   * @param reduction the gearbox
   * @param axis the geometry
   * @param feedback the speed sensor
   * @param current the current limits
   * @param control the control block
   * @param sim the simulated plant
   * @return the errors; empty when the config is clean
   */
  public static List<ConfigError> localChecks(
      String name,
      MotorGroup motors,
      Reduction reduction,
      Axis axis,
      FeedbackSpec feedback,
      CurrentLimits current,
      ControlConfig control,
      SimConfig sim) {

    // See the note on the POSITION overload: "never throws" is a property of the method, not of
    // its usual caller.
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? kNoMotors : motors;
    axis = axis == null ? kUnsetRotaryAxis : axis;
    feedback = feedback == null ? new FeedbackSpec.RotorOnly() : feedback;
    current = current == null ? CurrentLimits.defaultsFor(motors.model()) : current;
    control = control == null ? ControlConfig.defaults() : control;
    sim = sim == null ? kUnsetSim : sim;

    List<ConfigError> out = new ArrayList<>();

    checkMotors(out, name, motors, control.location());
    checkAxisAndReduction(out, name, axis, reduction);
    lift(out, ConfigError.Severity.FATAL, name, "currentLimits", current.problems(name));
    checkFeedback(out, name, feedback, reduction);
    lift(out, ConfigError.Severity.FATAL, name, "control", control.problems());

    if (control.gravity() != GravityMode.NONE) {
      out.add(
          ConfigError.fatal(
              name,
              "control.gravity",
              control.gravity().toString(),
              "NONE",
              "A velocity mechanism has no position, so a gravity term has no angle to be applied "
                  + "at and kG would be added to every output forever — the flywheel would creep "
                  + "while it was supposed to be neutral.\n"
                  + "Fix: remove the .gravity(...) call, or make this a PositionConfig if the "
                  + "mechanism really does hold an angle against gravity."));
    }

    MechanismUnits units = MechanismUnits.of(reduction, axis);
    checkCruiseVelocity(out, name, motors, units, control);
    checkExpo(out, name, control);
    checkPlaceholders(out, name, reduction, feedback, control.gains(), sim, false, false);

    return List.copyOf(out);
  }

  // ===========================================================================================
  // Local checks — SIMPLE
  // ===========================================================================================

  /**
   * Every problem visible from a simple config's own fields. <b>Never throws.</b>
   *
   * @param name the mechanism's name
   * @param motors the motor group
   * @param reduction the gearbox
   * @param current the current limits
   * @param heldSensor the sensor behind {@code holding()}, if any
   * @param stallSensor the sensor behind {@code stalled()}, if any
   * @param sim the simulated plant
   * @return the errors; empty when the config is clean
   */
  public static List<ConfigError> localChecks(
      String name,
      MotorGroup motors,
      Reduction reduction,
      CurrentLimits current,
      Optional<SensorSpec> heldSensor,
      Optional<SensorSpec> stallSensor,
      SimConfig sim) {

    // See the note on the POSITION overload: "never throws" is a property of the method, not of
    // its usual caller.
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? kNoMotors : motors;
    current = current == null ? CurrentLimits.defaultsFor(motors.model()) : current;
    heldSensor = heldSensor == null ? Optional.empty() : heldSensor;
    stallSensor = stallSensor == null ? Optional.empty() : stallSensor;
    sim = sim == null ? kUnsetSim : sim;

    List<ConfigError> out = new ArrayList<>();
    final String owner = name;

    checkMotors(out, name, motors, ControlLocation.RIO_FULL);
    lift(out, ConfigError.Severity.FATAL, name, "currentLimits", current.problems(name));
    heldSensor.ifPresent(
        sensor -> lift(out, ConfigError.Severity.FATAL, owner, "heldSensor", sensor.problems()));
    stallSensor.ifPresent(
        sensor -> lift(out, ConfigError.Severity.FATAL, owner, "stallSensor", sensor.problems()));

    if (reduction != null && !Double.isFinite(reduction.rotorPerOutput())) {
      out.add(
          ConfigError.fatal(
              name,
              "reduction",
              String.valueOf(reduction.rotorPerOutput()),
              "a finite number greater than zero",
              "Fix: Reduction.of(4.0) for a 4:1 gearbox, or Reduction.IDENTITY for a direct "
                  + "drive."));
    }

    if (sim == kUnsetSim) {
      out.add(
          ConfigError.placeholder(
              name,
              "sim",
              "not declared",
              "a moment of inertia",
              "Simulation will use a token inertia, so the roller will spin up instantly and "
                  + "nothing you learn in the simulator will transfer.\n"
                  + "Fix: .sim(KilogramSquareMeters.of(0.001)) — for a roller, "
                  + "m*r^2/2 with the wheel mass and radius is close enough to be useful."));
    } else {
      lift(out, ConfigError.Severity.FATAL, name, "sim", sim.problems());
    }

    return List.copyOf(out);
  }

  // ===========================================================================================
  // Cross-config checks — run ONCE, globally
  // ===========================================================================================

  /**
   * The checks that cannot be made from one config alone: duplicate CAN ids across mechanisms,
   * duplicate mechanism names, and every setpoint lookup that failed anywhere in the robot.
   *
   * <p><b>This must be called exactly once, over the final resolved config set</b>, and never from
   * a record constructor — see §5.6b and the class javadoc. {@link #install()} wires it into
   * {@code PumpkinRegistry.addAll(...)}, which is the one place that has the whole set.
   *
   * @param components everything that was registered; anything that is not a PumpkinLib config is
   *     ignored rather than rejected
   * @return the cross-config errors; empty when the robot is consistent
   */
  public static List<ConfigError> crossChecks(Object... components) {
    List<ConfigError> out = new ArrayList<>();
    if (components == null) {
      return List.copyOf(out);
    }

    // Duplicate mechanism names: two configs called "Arm" produce two identical log key trees and
    // two identical alert groups, and from that point on the log is unreadable.
    Map<String, Integer> nameCounts = new LinkedHashMap<>();
    List<CanIdRegistry.Device> devices = new ArrayList<>();
    for (Object component : components) {
      String name = nameOf(component);
      if (name != null) {
        nameCounts.merge(name, 1, Integer::sum);
      }
      devices.addAll(devicesOf(component));
    }
    for (Map.Entry<String, Integer> entry : nameCounts.entrySet()) {
      if (entry.getValue() > 1) {
        out.add(
            ConfigError.fatal(
                entry.getKey(),
                "name",
                entry.getValue() + " mechanisms share this name",
                "one mechanism per name",
                "Every log key, every alert group and every tuning entry is keyed by the "
                    + "mechanism's name, so two mechanisms called \""
                    + entry.getKey()
                    + "\" publish on top of each other and the log stops being readable.\n"
                    + "Fix: rename one of them — \""
                    + entry.getKey()
                    + "Left\" and \""
                    + entry.getKey()
                    + "Right\" is the usual answer."));
      }
    }

    // ONE global CAN scan, over the final resolved device set. CanIdRegistry already names both
    // colliding devices and where each was declared, so this is a lift and not a reimplementation.
    for (CanIdRegistry.Conflict conflict : CanIdRegistry.scanForConflicts(devices)) {
      out.add(
          new ConfigError(
              ConfigError.Severity.FATAL,
              conflict.devices().isEmpty() ? "(robot)" : conflict.devices().get(0).owner(),
              conflict.kind() == CanIdRegistry.Kind.DUPLICATE_ID
                  ? "CAN id " + conflict.deviceId() + " on bus \"" + conflict.bus() + '"'
                  : "CAN id",
              String.valueOf(conflict.deviceId()),
              conflict.kind() == CanIdRegistry.Kind.DUPLICATE_ID
                  ? "one device per id per bus"
                  : CanIdRegistry.kMinDeviceId + ".." + CanIdRegistry.kMaxDeviceId,
              conflict.describe(),
              ConfigError.kUnknownSite));
    }

    out.addAll(lookupMisses());
    return List.copyOf(out);
  }

  // ===========================================================================================
  // Registry integration
  // ===========================================================================================

  /**
   * Wires this package into {@code PumpkinRegistry} so that registering a config surfaces its
   * errors and its CAN devices without core ever naming a config type.
   *
   * <p>Idempotent in effect but not in cost — call it once, from the domain's own initialisation.
   * After it has run, {@code PumpkinRegistry.addAll(ELEVATOR, ARM, ROLLER)} collects every
   * {@link ConfigError}, feeds every declared device into the single global uniqueness scan, prints
   * everything together and enters safe mode when anything is fatal.
   */
  public static void install() {
    PumpkinRegistry.addFaultExtractor(
        component -> ConfigError.toFaults(errorsOf(component)));
    PumpkinRegistry.addDeviceExtractor(Validation::devicesOf);
  }

  /**
   * The errors a registered component carries, if it is one of this package's configs.
   *
   * @param component anything at all
   * @return its errors, or an empty list when it is not a PumpkinLib config
   */
  public static List<ConfigError> errorsOf(Object component) {
    if (component instanceof PositionConfig config) {
      return config.errors();
    }
    if (component instanceof VelocityConfig config) {
      return config.errors();
    }
    if (component instanceof SimpleConfig config) {
      return config.errors();
    }
    return List.of();
  }

  /**
   * The CAN devices a registered component declares, if it is one of this package's configs.
   *
   * @param component anything at all
   * @return its devices, or an empty list when it is not a PumpkinLib config
   */
  public static List<CanIdRegistry.Device> devicesOf(Object component) {
    if (component instanceof PositionConfig config) {
      return config.canDevices();
    }
    if (component instanceof VelocityConfig config) {
      return config.canDevices();
    }
    if (component instanceof SimpleConfig config) {
      return config.canDevices();
    }
    return List.of();
  }

  /**
   * The boot dump for any of this package's configs.
   *
   * @param component anything at all
   * @return its {@code describe()}, or a one-line note when it is not a PumpkinLib config
   */
  public static String describe(Object component) {
    if (component instanceof PositionConfig config) {
      return config.describe();
    }
    if (component instanceof VelocityConfig config) {
      return config.describe();
    }
    if (component instanceof SimpleConfig config) {
      return config.describe();
    }
    return String.valueOf(component);
  }

  // ===========================================================================================
  // Reporting
  // ===========================================================================================

  /**
   * Prints every error <b>together, once</b> — not one per deploy cycle, and not interleaved with
   * whatever else is starting up.
   *
   * <p>Fatal errors first, because they are the ones stopping the robot; then warnings; then the
   * first-setup checklist, which reads as a to-do list rather than as a failure.
   *
   * @param errors the errors, in any order
   */
  public static void printAll(List<ConfigError> errors) {
    if (errors == null || errors.isEmpty()) {
      System.out.println("[PumpkinLib] Config: no problems found.");
      return;
    }
    StringBuilder sb = new StringBuilder(1024);
    String nl = System.lineSeparator();
    long fatal = errors.stream().filter(e -> e.severity() == ConfigError.Severity.FATAL).count();
    long warn = errors.stream().filter(e -> e.severity() == ConfigError.Severity.WARNING).count();
    long todo =
        errors.stream().filter(e -> e.severity() == ConfigError.Severity.PLACEHOLDER).count();

    sb.append(nl)
        .append("================ PumpkinLib configuration report ================")
        .append(nl)
        .append(fatal)
        .append(" fatal, ")
        .append(warn)
        .append(" warning(s), ")
        .append(todo)
        .append(" placeholder(s)")
        .append(nl);
    if (fatal > 0) {
      sb.append(nl)
          .append("The robot HAS BOOTED and is in SAFE MODE: it is connected, the dashboard works, ")
          .append(nl)
          .append("and every mechanism refuses to move until the fatal errors below are fixed.")
          .append(nl);
    }
    for (ConfigError.Severity severity : ConfigError.Severity.values()) {
      for (ConfigError error : errors) {
        if (error.severity() == severity && severity != ConfigError.Severity.PLACEHOLDER) {
          sb.append(nl).append(error.describe());
        }
      }
    }
    if (todo > 0) {
      sb.append(nl).append(checklist(errors));
    }
    sb.append("================================================================").append(nl);
    System.out.println(sb);
  }

  /**
   * The tier-3 first-setup checklist, in the {@code ADD} / {@code VERIFY} / {@code TUNE} shape that
   * a robot template carries as comments and that this makes machine-checkable.
   *
   * @param errors the full error list; non-placeholder entries are ignored
   * @return the checklist block, or an empty string when nothing is at a placeholder
   */
  public static String checklist(List<ConfigError> errors) {
    if (errors == null) {
      return "";
    }
    List<ConfigError> placeholders =
        errors.stream().filter(e -> e.severity() == ConfigError.Severity.PLACEHOLDER).toList();
    if (placeholders.isEmpty()) {
      return "";
    }
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(256);
    sb.append("[PumpkinLib] First-setup checklist — ")
        .append(placeholders.size())
        .append(" value(s) still at placeholders:")
        .append(nl);
    for (ConfigError error : placeholders) {
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  %-7s %-32s %-24s %s",
                  error.severity().verb(),
                  error.owner() + '.' + error.field(),
                  error.value(),
                  error.expected()))
          .append(nl);
    }
    return sb.toString();
  }

  /**
   * The same list {@link #printAll} writes to the console, one entry per error, as plain strings.
   *
   * <p>Deliberately {@code String}s and not a struct array: a WPILib struct is fixed-size by
   * definition and every field of {@link ConfigError} is a {@code String}, so there is no legal
   * {@code Struct<ConfigError>}. This is what goes to {@code /Pumpkin/Config/Errors} so the network
   * topic and the riolog say the identical thing — which matters, because an FMS-attached log is
   * exactly where a student will be reading it from.
   *
   * @param errors the errors
   * @return one rendered error per element
   */
  public static List<String> toStrings(List<ConfigError> errors) {
    if (errors == null || errors.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(errors.size());
    for (ConfigError error : errors) {
      out.add(error.describe());
    }
    return List.copyOf(out);
  }

  /**
   * Convenience for a team wiring the pipeline by hand instead of through {@code PumpkinRegistry}.
   *
   * @param configs the configs
   * @return every local error plus every cross-config error, in report order
   */
  public static List<ConfigError> validateAll(Object... configs) {
    List<ConfigError> out = new ArrayList<>();
    if (configs != null) {
      for (Object config : configs) {
        out.addAll(errorsOf(config));
      }
    }
    out.addAll(crossChecks(configs == null ? new Object[0] : configs));
    return List.copyOf(out);
  }

  // ===========================================================================================
  // Tier-1 checks
  // ===========================================================================================

  private static void checkMotors(
      List<ConfigError> out, String name, MotorGroup motors, ControlLocation location) {
    if (motors == kNoMotors) {
      out.add(
          ConfigError.fatal(
              name,
              "motors",
              "not declared",
              "at least a leader",
              "Nothing drives this mechanism, so nothing it is asked to do can happen.\n"
                  + "Fix: .motors(MotorGroup.leader(MotorSpec.talonFX(20, \"rio\"))) — or "
                  + ".motor(MotorSpec.spark(24, SparkModel.MAX_NEO)) when there is only one."));
      return;
    }
    lift(out, ConfigError.Severity.FATAL, name, "motors", motors.problems());

    MotorSpec leader = motors.leader();
    if (location.runsOnMotor() && !leader.supportsOnMotorControl()) {
      out.add(
          ConfigError.fatal(
              name,
              "control.location",
              location.toString(),
              "RIO_FULL for a " + leader.deviceType(),
              "A "
                  + leader.deviceType()
                  + " has no closed loop of its own, so there is nothing on the device for the "
                  + "profile or the feedback loop to run on and the mechanism would sit still.\n"
                  + "Fix: .controlLocation(ControlLocation.RIO_FULL), or use a motor controller "
                  + "that closes its own loop (TalonFX, TalonFXS, Spark MAX/Flex)."));
    }
  }

  private static void checkAxisAndReduction(
      List<ConfigError> out, String name, Axis axis, Reduction reduction) {
    if (axis == kUnsetLinearAxis || axis == kUnsetRotaryAxis) {
      out.add(
          ConfigError.fatal(
              name,
              "axis",
              "not declared",
              "a LinearAxis or a RotaryAxis",
              "The axis is what turns output rotations into the units you think in. Without it "
                  + "every soft limit, every setpoint, every gain and the whole simulation are "
                  + "scaled by a number nobody chose.\n"
                  + "Fix (linear): .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2)) — chain "
                  + "pitch, tooth count, cascade stages.\n"
                  + "Fix (rotary): .axis(RotaryAxis.arm(Degrees.of(0.0))) — the angle at which "
                  + "the mechanism is level."));
    } else {
      lift(out, ConfigError.Severity.FATAL, name, "axis", axis.problems());
    }

    if (reduction == null || !Double.isFinite(reduction.rotorPerOutput())
        || reduction.rotorPerOutput() <= 0.0) {
      out.add(
          ConfigError.fatal(
              name,
              "reduction",
              reduction == null ? "null" : String.valueOf(reduction.rotorPerOutput()),
              "> 0 (rotor rotations per output rotation)",
              "A reduction is how many times the MOTOR turns for one turn of the OUTPUT shaft.\n"
                  + "A 12:1 gearbox is Reduction.of(12.0), not Reduction.of(1.0/12.0).\n"
                  + "If you know the tooth counts, prefer "
                  + "Reduction.ofTeeth(58, 10).then(58, 18)."));
    }
  }

  private static void checkLimits(
      List<ConfigError> out, String name, Axis axis, PositionLimits limits) {
    if (limits == kUnsetLinearLimits || limits == kUnsetRotaryLimits) {
      boolean linear = axis.siDomain() == SiDomain.LINEAR_METERS;
      out.add(
          ConfigError.fatal(
              name,
              "limits",
              "not declared",
              "a minimum and a maximum",
              "Without soft limits nothing stops this mechanism at either end of its travel — not "
                  + "the device's firmware limit, not the Java goal clamp, and not the open-loop "
                  + "clamp that stops a driver's stick from driving it into the frame.\n"
                  + "Fix: "
                  + (linear
                      ? ".softLimits(Inches.of(0.0), Inches.of(55.0))"
                      : ".softLimits(Degrees.of(-15.0), Degrees.of(105.0))")));
      return;
    }
    lift(out, ConfigError.Severity.FATAL, name, "limits", limits.problems());

    // Swapped bounds, caught BEFORE Range normalises them. Range.of(a, b) deliberately orders its
    // two numbers so that a Range is always usable; PositionLimits is where the team's claim about
    // which direction is positive still exists, so this is the only place the swap is visible.
    double rawMin = limits.isLinear() ? limits.minMeters() : limits.minDegrees();
    double rawMax = limits.isLinear() ? limits.maxMeters() : limits.maxDegrees();
    String rawUnit = limits.isLinear() ? "m" : "deg";
    if (Double.isFinite(rawMin) && Double.isFinite(rawMax) && rawMin > rawMax) {
      out.add(
          ConfigError.fatal(
              name,
              "limits.min / limits.max",
              String.format(
                  Locale.ROOT, "min = %.4f %s, max = %.4f %s", rawMin, rawUnit, rawMax, rawUnit),
              "limits.min < limits.max",
              "These look swapped. PumpkinLib will not order them for you, because on a mechanism "
                  + "with an inverted motor \"min\" and \"max\" are a real physical claim about "
                  + "which way is positive, and quietly reordering them would hide a wrong "
                  + "inversion until the mechanism drove the wrong way.\n"
                  + "Fix: check describe() — it prints which direction is positive — then swap "
                  + "the two arguments to .softLimits(...)."));
    }

    boolean axisIsLinear = axis.siDomain() == SiDomain.LINEAR_METERS;
    if (axisIsLinear && limits.isRotary()) {
      out.add(
          ConfigError.fatal(
              name,
              "limits",
              limits.range().describe(),
              "distances, because the axis is a LinearAxis",
              "The axis says this mechanism travels along a line and the soft limits are stated as "
                  + "angles. One of the two is about a different machine.\n"
                  + "Fix: .softLimits(Inches.of(0.0), Inches.of(55.0)), or switch to "
                  + "PositionConfig.rotary(\""
                  + name
                  + "\") if it really does rotate."));
    } else if (!axisIsLinear && limits.isLinear()) {
      out.add(
          ConfigError.fatal(
              name,
              "limits",
              limits.range().describe(),
              "angles, because the axis is a RotaryAxis",
              "The axis says this mechanism rotates about a joint and the soft limits are stated "
                  + "as distances.\n"
                  + "Fix: .softLimits(Degrees.of(-15.0), Degrees.of(105.0)), or switch to "
                  + "PositionConfig.linear(\""
                  + name
                  + "\") if it really does travel in a line."));
    }
  }

  private static void checkFeedback(
      List<ConfigError> out, String name, FeedbackSpec feedback, Reduction reduction) {
    lift(out, ConfigError.Severity.FATAL, name, "feedback", feedback.problems());

    double rotorPerSensor = feedback.rotorPerSensor();
    double sensorPerOutput = feedback.sensorPerOutput();
    if (Double.isNaN(rotorPerSensor) || Double.isNaN(sensorPerOutput) || reduction == null) {
      return;
    }
    double implied = rotorPerSensor * sensorPerOutput;
    double declared = reduction.rotorPerOutput();
    if (!Double.isFinite(declared) || declared <= 0.0) {
      return;
    }
    if (Math.abs(implied - declared) > kFeedbackRatioTolerance * declared) {
      out.add(
          ConfigError.fatal(
              name,
              "feedback ratios vs reduction",
              String.format(
                  Locale.ROOT,
                  "rotorPerSensor %.4f x sensorPerOutput %.4f = %.4f",
                  rotorPerSensor,
                  sensorPerOutput,
                  implied),
              String.format(Locale.ROOT, "%.4f, the reduction you declared", declared),
              "These two describe the SAME gear train from two directions, so their product must "
                  + "equal the reduction. It does not, which means one of them is wrong — and "
                  + "whichever it is, position will be scaled by "
                  + String.format(Locale.ROOT, "%.4f", implied / declared)
                  + " and every setpoint will land in the wrong place.\n"
                  + "Fix: if the encoder is ON the joint, sensorPerOutput is 1.0 and "
                  + "rotorPerSensor is the whole gearbox ratio. Count the teeth once and use the "
                  + "same numbers in both places."));
    }
  }

  private static void checkGravity(
      List<ConfigError> out, String name, Axis axis, ControlConfig control) {
    GravityMode gravity = control.gravity();
    boolean axisIsLinear = axis.siDomain() == SiDomain.LINEAR_METERS;

    if (gravity == GravityMode.COSINE && axisIsLinear) {
      out.add(
          ConfigError.fatal(
              name,
              "control.gravity",
              "COSINE",
              "CONSTANT on a linear axis",
              "A carriage on a linear axis fights the same weight everywhere in its travel, so "
                  + "the gravity term is a constant, not a cosine. A cosine term would go to zero "
                  + "at the top of the travel and the elevator would fall.\n"
                  + "Fix: remove the .gravity(...) override — LinearAxis already implies "
                  + "CONSTANT."));
      return;
    }

    if (gravity != GravityMode.COSINE) {
      return;
    }

    double horizontal = axis.horizontalReference();
    if (!Double.isFinite(horizontal)) {
      out.add(
          ConfigError.fatal(
              name,
              "axis.horizontalAt",
              "not set",
              "the angle at which the mechanism is level",
              "Cosine gravity compensation needs to know WHERE the mechanism is horizontal, or kG "
                  + "will be applied with the wrong sign over half the range: the arm sags on one "
                  + "side and slams on the other.\n"
                  + "Fix: RotaryAxis.arm(Degrees.of(<angle at which the arm is level>)). If your "
                  + "encoder reads 0 with the arm level, that is Degrees.of(0)."));
      return;
    }

    if (Math.abs(horizontal) > kMaxGravityOffsetDegrees) {
      out.add(
          ConfigError.fatal(
              name,
              "axis.horizontalAt",
              String.format(
                  Locale.ROOT, "%.1f deg  ->  %.4f output rotations", horizontal, horizontal / 360.0),
              String.format(
                  Locale.ROOT,
                  "within +/-%.2f rotations (+/-%.0f deg) of the mechanism zero",
                  kMaxGravityOffsetDegrees / 360.0,
                  kMaxGravityOffsetDegrees),
              "Phoenix Slot0Configs.GravityArmPositionOffset accepts only (-0.25, 0.25) rot. A "
                  + "larger offset is clamped by the device with NO error, so kG would be applied "
                  + "at the wrong angle over the whole range: the arm sags on one side and slams "
                  + "on the other.\n"
                  + "Fix: re-zero the encoder so it reads NEAR 0 with the mechanism level, then "
                  + "RotaryAxis.arm(Degrees.of(0)).\n"
                  + "Alternative: ControlLocation.RIO_FULL — WPILib's ArmFeedforward has no "
                  + "offset limit — and PumpkinLib will tell you it downgraded."));
    }
  }

  private static void checkHoming(
      List<ConfigError> out, String name, HomingStrategy homing, FeedbackSpec feedback) {
    if (homing == kNoHoming) {
      if (feedback.isAbsolute()) {
        out.add(
            ConfigError.warning(
                name,
                "homing",
                "not declared",
                "HomingStrategy.absoluteSeed()",
                "This mechanism has an absolute sensor, so it does know where it is — but the "
                    + "config does not say so, and the boot dump cannot tell a reader whether "
                    + "that was a decision or an oversight.\n"
                    + "Fix: .homing(HomingStrategy.absoluteSeed())."));
      } else {
        out.add(
            ConfigError.fatal(
                name,
                "homing",
                "not declared",
                "a HomingStrategy",
                "A rotor encoder reads zero at boot no matter where the mechanism physically is, "
                    + "and this mechanism has no absolute sensor. Without a homing strategy every "
                    + "position it reports is a guess, so every soft limit and every setpoint is "
                    + "measured from the wrong place.\n"
                    + "Fix: .homing(HomingStrategy.currentSpike().direction("
                    + "HomingStrategy.Direction.REVERSE).seedTo(Inches.of(0.0))) to drive gently "
                    + "into the bottom stop, or .homing(HomingStrategy.limitSwitch("
                    + "SensorSpec.motorLimit(SensorSpec.Limit.REVERSE))) if there is a switch.\n"
                    + "If you really do want to assume a position at boot, say so explicitly with "
                    + ".homing(HomingStrategy.assumeAtBoot(Degrees.of(90))) — it is allowed, and "
                    + "it warns on every boot."));
      }
      return;
    }
    lift(out, ConfigError.Severity.FATAL, name, "homing", homing.problems());
  }

  private static void checkSetpoints(
      List<ConfigError> out,
      String name,
      Axis axis,
      PositionLimits limits,
      List<Setpoint> setpoints) {

    Set<String> seen = new LinkedHashSet<>();
    boolean axisIsLinear = axis.siDomain() == SiDomain.LINEAR_METERS;
    Range travel = limits.range();
    boolean travelIsUsable = travel.problems().isEmpty() && travel.width() > 0.0;

    for (Setpoint setpoint : setpoints) {
      lift(
          out,
          ConfigError.Severity.FATAL,
          name,
          "setpoint(\"" + setpoint.name() + "\")",
          setpoint.problems());

      if (!seen.add(setpoint.name())) {
        out.add(
            ConfigError.fatal(
                name,
                "setpoint(\"" + setpoint.name() + "\")",
                "declared more than once",
                "one goal per name",
                "A lookup by name cannot choose between them, so which value you get depends on "
                    + "declaration order — and a later edit that reorders the calls silently "
                    + "changes where the mechanism goes.\n"
                    + "Fix: delete the duplicate, or give the two goals different names."));
        continue;
      }

      boolean setpointIsLinear = setpoint.asDistance().isPresent();
      boolean setpointIsAngle = setpoint.asAngle().isPresent();
      if (setpoint.isResolved() && axisIsLinear && setpointIsAngle) {
        out.add(
            ConfigError.fatal(
                name,
                "setpoint(\"" + setpoint.name() + "\")",
                setpoint.describe(),
                "a Distance, because the axis is a LinearAxis",
                "Fix: Inches.of(52.5) rather than Degrees.of(52.5)."));
        continue;
      }
      if (setpoint.isResolved() && !axisIsLinear && setpointIsLinear) {
        out.add(
            ConfigError.fatal(
                name,
                "setpoint(\"" + setpoint.name() + "\")",
                setpoint.describe(),
                "an Angle, because the axis is a RotaryAxis",
                "Fix: Degrees.of(35) rather than Inches.of(35)."));
        continue;
      }

      if (setpoint.isResolved() && travelIsUsable && !travel.contains(setpoint.valueUser())) {
        out.add(
            ConfigError.warning(
                name,
                "setpoint(\"" + setpoint.name() + "\")",
                setpoint.describe(),
                "inside the soft limits " + travel.describe(),
                "The mechanism will be commanded to this goal, the goal will be clamped to the "
                    + "nearest soft limit, and atGoal() will therefore never become true for it — "
                    + "so anything sequenced after it waits forever.\n"
                    + "Fix: move the goal inside the limits, or widen the limits if the mechanism "
                    + "really does travel that far."));
      }
    }
  }

  private static void checkToleranceDomain(
      List<ConfigError> out, String name, Axis axis, ControlConfig control) {
    boolean axisIsLinear = axis.siDomain() == SiDomain.LINEAR_METERS;
    if (Double.isNaN(control.toleranceUser())) {
      return; // ControlConfig.problems() already said so, in better words.
    }
    if (axisIsLinear && !control.toleranceIsLinear()) {
      out.add(
          ConfigError.fatal(
              name,
              "control.tolerance",
              control.tolerance().toString(),
              "a Distance, because the axis is a LinearAxis",
              "An angular tolerance on a linear mechanism is compared against a number of metres, "
                  + "so atGoal() would latch about 57 times too early or too late depending on "
                  + "which way the conversion falls.\n"
                  + "Fix: .tolerance(Inches.of(0.5), 0.05, 0.06)."));
    } else if (!axisIsLinear && control.toleranceIsLinear()) {
      out.add(
          ConfigError.fatal(
              name,
              "control.tolerance",
              control.tolerance().toString(),
              "an Angle, because the axis is a RotaryAxis",
              "Fix: .tolerance(Degrees.of(1.5), 5.0, 0.06)."));
    }
  }

  // ===========================================================================================
  // Tier-2 checks
  // ===========================================================================================

  private static void checkCruiseVelocity(
      List<ConfigError> out,
      String name,
      MotorGroup motors,
      MechanismUnits units,
      ControlConfig control) {

    if (motors == kNoMotors) {
      return;
    }
    double cruise = control.constraints().maxVelocity();
    if (!Double.isFinite(cruise) || cruise <= 0.0) {
      return; // MotionConstraints.problems() already covers it.
    }
    double rotorRps = motors.model().freeSpeedRotorRps(motors.leader().foc());
    double freeSpeed = units.freeSpeedUserPerSec(rotorRps);
    if (!Double.isFinite(freeSpeed) || freeSpeed <= 0.0) {
      return;
    }
    if (cruise <= kCruiseFractionOfFreeSpeed * freeSpeed) {
      return;
    }
    String unit = units.unitLabel();
    out.add(
        ConfigError.warning(
            name,
            "control.constraints.maxVelocity",
            String.format(Locale.ROOT, "%.3f %s/s", cruise, unit),
            String.format(
                Locale.ROOT,
                "<= %.3f %s/s (%.0f%% of the free-speed estimate)",
                kSuggestedCruiseFraction * freeSpeed,
                unit,
                kSuggestedCruiseFraction * 100.0),
            String.format(
                    Locale.ROOT,
                    "The free-speed estimate is %.3f %s/s. Computed from %d x %s (%.0f rotor rps "
                        + "free) through %s into %s.",
                    freeSpeed,
                    unit,
                    motors.count(),
                    motors.model().displayName(),
                    rotorRps,
                    units.reduction().describe(),
                    units.axis().unitLabel().equals("m")
                        ? String.format(
                            Locale.ROOT,
                            "%.6f m of travel per output rotation",
                            units.userPerOutputRotation())
                        : "one full output rotation per 360 deg")
                + "\nThe profile will never reach this cruise velocity, so moves are effectively "
                + "acceleration-limited only and every timing estimate built on the cruise number "
                + "is wrong.\n"
                + String.format(
                    Locale.ROOT,
                    "Fix: .constraints(MotionConstraints.of(%.2f, %.2f)) — %.0f%% of free speed "
                        + "leaves headroom for battery sag.",
                    kSuggestedCruiseFraction * freeSpeed,
                    control.constraints().maxAcceleration(),
                    kSuggestedCruiseFraction * 100.0)));
  }

  private static void checkGravityGain(List<ConfigError> out, String name, ControlConfig control) {
    Gains gains = control.gains();
    if (control.gravity() == GravityMode.NONE || gains.isUntuned()) {
      return;
    }
    if (gains.kG() != 0.0) {
      return;
    }
    boolean cosine = control.gravity() == GravityMode.COSINE;
    out.add(
        ConfigError.warning(
            name,
            "control.gains.kG",
            "0.00 V",
            "the volts needed to hold station against gravity",
            "GravityMode is "
                + control.gravity()
                + " but kG is zero, so nothing holds this mechanism up: it "
                + (cosine ? "sags" : "falls")
                + " whenever the loop is not actively fighting it, and kP has to make up the "
                + "difference as steady-state error.\n"
                + "Procedure: disable, hold the mechanism "
                + (cosine ? "horizontal" : "at mid travel")
                + ", enable, and raise kG with kP = 0 until it just holds station. Live-tunable "
                + "at /Tuning/"
                + name
                + "/kG, or measured for you by `pumpkin tune "
                + name
                + "`."));
  }

  private static void checkTolerance(
      List<ConfigError> out, String name, MechanismUnits units, ControlConfig control) {
    double tolerance = control.toleranceUser();
    double cruise = control.constraints().maxVelocity();
    if (!Double.isFinite(tolerance) || tolerance <= 0.0 || !Double.isFinite(cruise)
        || cruise <= 0.0) {
      return;
    }
    double dt = Clock.dt();
    double perLoop = cruise * dt;
    if (perLoop <= tolerance) {
      return;
    }
    String unit = units.unitLabel();
    out.add(
        ConfigError.warning(
            name,
            "control.tolerance",
            String.format(Locale.ROOT, "%.4f %s", tolerance, unit),
            String.format(
                Locale.ROOT,
                "wider than one loop step (%.4f %s), or a later atGoal() understood and accepted",
                perLoop,
                unit),
            String.format(
                    Locale.ROOT,
                    "At the cruise velocity of %.3f %s/s the mechanism moves %.4f %s per %.0f ms "
                        + "loop — %.1f tolerance bands per loop.",
                    cruise,
                    unit,
                    perLoop,
                    unit,
                    dt * 1000.0,
                    perLoop / tolerance)
                + "\nThe mechanism can never be OBSERVED inside the tolerance band while it is "
                + "still moving fast, so atGoal() only latches after the profile decelerates. "
                + "That is correct behaviour, and PumpkinLib's velocity gate enforces it — this "
                + "warning exists so that \"atGoal took longer than I expected\" is already "
                + "explained.\n"
                + "If you want an earlier release, use atSetpoint() or a superstructure "
                + "early-release predicate. Do NOT widen the tolerance."));
  }

  private static void checkExpo(List<ConfigError> out, String name, ControlConfig control) {
    if (!control.useExpo()) {
      return;
    }
    Gains gains = control.gains();
    if (gains.kV() != 0.0 && gains.kA() != 0.0) {
      return;
    }
    out.add(
        ConfigError.warning(
            name,
            "control.useExpo",
            String.format(Locale.ROOT, "true, with kV = %.4f and kA = %.4f", gains.kV(), gains.kA()),
            "non-zero, measured kV and kA",
            "Motion Magic Expo shapes its profile ENTIRELY from measured kV and kA. With either at "
                + "zero the library would hand the device the vendor's factory defaults "
                + "(0.12 V/rps, 0.1 V/rps^2), which describe some other mechanism entirely.\n"
                + "Fix: run `pumpkin tune "
                + name
                + " --feedforward`, or set .useExpo(false) and give MotionConstraints a cruise "
                + "velocity and an acceleration."));
  }

  private static void checkHomingPlausibility(
      List<ConfigError> out,
      String name,
      HomingStrategy homing,
      FeedbackSpec feedback,
      PositionLimits limits) {

    if (homing instanceof HomingStrategy.AssumeAtBoot assume && !feedback.isAbsolute()) {
      out.add(
          ConfigError.warning(
              name,
              "homing",
              String.format(Locale.ROOT, "assumeAtBoot(%.4f user units)", assume.seedToUserUnits()),
              "a sensor that confirms the position",
              "This assumes the mechanism is resting on a known stop every time the robot powers "
                  + "on, and there is no sensor confirming it. If it can be moved by hand while "
                  + "disabled, position will be wrong, soft limits will be measured from the wrong "
                  + "place, and gravity compensation will push the wrong way.\n"
                  + "Fix: add an absolute encoder (FeedbackSpec.SparkAbsolute / FusedCancoder) or "
                  + "a switch (HomingStrategy.limitSwitch(...)). Keeping assumeAtBoot is a valid "
                  + "choice — this warning is here so it stays a choice."));
    }

    double seed = seedPositionOf(homing);
    if (Double.isNaN(seed)) {
      return;
    }
    Range travel = limits.range();
    if (!travel.problems().isEmpty() || travel.width() <= 0.0) {
      return;
    }
    if (!travel.contains(seed)) {
      out.add(
          ConfigError.warning(
              name,
              "homing.seedTo",
              String.format(Locale.ROOT, "%.4f %s", seed, travel.unitLabel()),
              "inside the soft limits " + travel.describe(),
              "Homing would declare the mechanism to be somewhere it is not allowed to be, so the "
                  + "first goal after homing is clamped and the mechanism jumps.\n"
                  + "Fix: the seed value is the position the mechanism is AT when it reaches its "
                  + "stop — usually exactly the soft limit on that side."));
    }
  }

  private static void checkHardStopLatency(
      List<ConfigError> out, String name, PositionLimits limits) {
    for (HardStop side : HardStop.values()) {
      Optional<SensorSpec> stop = limits.hardStop(side);
      if (stop.isPresent() && stop.get() instanceof SensorSpec.Dio) {
        out.add(
            ConfigError.warning(
                name,
                "limits.hardStop(" + side + ")",
                "a roboRIO DIO switch",
                "SensorSpec.motorLimit(...) for a zero-latency firmware stop",
                "A DIO switch is read in periodic() and zeroes the output on the NEXT loop, which "
                    + "is up to 20 ms of travel after the switch closed. A switch wired to the "
                    + "motor controller's own limit input stops the motor in firmware, "
                    + "immediately, and keeps working even if robot code hangs.\n"
                    + "Fix, if the wiring allows it: SensorSpec.motorLimit(SensorSpec.Limit."
                    + side
                    + "). This warning is informational — a DIO switch is a legitimate choice when "
                    + "there is no spare limit input."));
      }
    }
  }

  private static void checkContinuousWithLimits(
      List<ConfigError> out, String name, Axis axis, PositionLimits limits) {
    if (!axis.isContinuous()) {
      return;
    }
    Range travel = limits.range();
    if (travel.problems().isEmpty() && Double.isFinite(travel.width()) && travel.width() > 0.0
        && travel.width() < 360.0) {
      out.add(
          ConfigError.warning(
              name,
              "axis.continuous",
              "true, with soft limits " + travel.describe(),
              "either continuous rotation OR bounded travel",
              "A continuous axis wraps at +/-180 deg, and soft limits narrower than a full turn "
                  + "say it cannot. Whichever the mechanism really is, one of the two will "
                  + "surprise you: continuous wrapping inside a bounded range makes the shortest "
                  + "path go straight through the end stop.\n"
                  + "Fix: RotaryAxis.turret(false) for a turret with a cable that cannot spin "
                  + "forever, or widen the soft limits past 360 deg for one that can."));
    }
  }

  // ===========================================================================================
  // Tier-3 checks
  // ===========================================================================================

  private static void checkPlaceholders(
      List<ConfigError> out,
      String name,
      Reduction reduction,
      FeedbackSpec feedback,
      Gains gains,
      SimConfig sim,
      boolean positionMechanism,
      boolean flagRotorOnly) {

    if (gains.isUntuned()) {
      out.add(
          ConfigError.placeholder(
              name,
              "control.gains",
              "Gains.UNTUNED",
              "measured kS/kV/kA and designed kP/kD",
              "Closed loop is refused on hardware while the gains are untuned, and in simulation "
                  + "the library derives a physics prior from your declared mass so something "
                  + "moves.\n"
                  + "Fix: `pumpkin tune "
                  + name
                  + "` measures kS, kV and kA and designs kP and kD from them."));
    } else if (gains.kS() == 0.0 && gains.kV() == 0.0) {
      out.add(
          ConfigError.placeholder(
              name,
              "control.gains.kS/kV",
              "both 0.0",
              "measured feedforward gains",
              "Without a feedforward the loop is pure feedback, so it must be wrong before it can "
                  + "correct: every move starts late and ends with steady-state error that kI "
                  + "then papers over.\n"
                  + "Fix: `pumpkin tune "
                  + name
                  + " --feedforward` measures both in about twenty seconds."));
    }

    if (reduction != null && reduction.rotorPerOutput() == 1.0) {
      out.add(
          ConfigError.placeholder(
              name,
              "reduction",
              "1.000:1 (direct drive)",
              "the real gearbox ratio, if there is a gearbox",
              "1:1 is correct for a direct drive and is a common unfilled blank for everything "
                  + "else. If there is a gearbox between the motor and the output, every gain, "
                  + "soft limit, profile constraint and simulated plant is currently scaled by the "
                  + "wrong number.\n"
                  + "Fix: count the teeth — Reduction.ofTeeth(58, 10).then(58, 18) — or state the "
                  + "ratio: Reduction.ofStages(3.0, 4.0)."));
    }

    if (positionMechanism && flagRotorOnly && feedback instanceof FeedbackSpec.RotorOnly) {
      out.add(
          ConfigError.placeholder(
              name,
              "feedback",
              "RotorOnly",
              "an absolute reference, or a homing routine that drives to a physical stop",
              "A rotor encoder reads zero at boot, so position is only as good as whatever seeds "
                  + "it — and nothing in this config physically finds a reference.\n"
                  + "Fix: fit an absolute encoder (FeedbackSpec.SparkAbsolute / FusedCancoder / "
                  + "DioAbsolute), or home against a stop with HomingStrategy.currentSpike() or "
                  + "HomingStrategy.limitSwitch(...)."));
    }

    if (sim == kUnsetSim) {
      out.add(
          ConfigError.placeholder(
              name,
              "sim",
              "not declared",
              positionMechanism ? "a mass or a moment of inertia" : "a moment of inertia",
              "The simulated plant has no inertia, so it accelerates instantly and nothing you "
                  + "learn from it transfers to the robot.\n"
                  + "Fix: "
                  + (positionMechanism
                      ? ".sim(Pounds.of(24.0), Inches.of(0.0)) for a carriage, or "
                          + ".sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95)))"
                          + " for an arm."
                      : ".sim(KilogramSquareMeters.of(0.004)) — m*r^2/2 for a disc.")));
    } else {
      lift(out, ConfigError.Severity.FATAL, name, "sim", sim.problems());
    }
  }

  // ===========================================================================================
  // Helpers
  // ===========================================================================================

  private static void lift(
      List<ConfigError> out,
      ConfigError.Severity severity,
      String owner,
      String field,
      List<String> problems) {
    if (problems == null) {
      return;
    }
    for (String problem : problems) {
      out.add(ConfigError.of(severity, owner, field, problem));
    }
  }

  private static double seedPositionOf(HomingStrategy homing) {
    if (homing instanceof HomingStrategy.CurrentSpike spike) {
      return spike.seedToUserUnits();
    }
    if (homing instanceof HomingStrategy.LimitSwitch limit) {
      return limit.seedToUserUnits();
    }
    if (homing instanceof HomingStrategy.AssumeAtBoot assume) {
      return assume.seedToUserUnits();
    }
    return Double.NaN;
  }

  private static String nameOf(Object component) {
    if (component instanceof PositionConfig config) {
      return config.name();
    }
    if (component instanceof VelocityConfig config) {
      return config.name();
    }
    if (component instanceof SimpleConfig config) {
      return config.name();
    }
    return null;
  }

  /**
   * A convenience for a team that wants the whole robot's boot dump in one string.
   *
   * @param configs the configs, in the order they should be printed
   * @return every config's {@code describe()}, concatenated
   */
  public static String describeAll(Object... configs) {
    if (configs == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(4096);
    for (Object config : Arrays.asList(configs)) {
      sb.append(describe(config)).append(System.lineSeparator());
    }
    return sb.toString();
  }

  /**
   * The severity a {@link SafeMode.Level} corresponds to, for code that reads faults back out of
   * core and wants to report them in this package's vocabulary.
   *
   * @param level the core-tier level
   * @return the matching severity
   */
  public static ConfigError.Severity severityOf(SafeMode.Level level) {
    return switch (level) {
      case FATAL -> ConfigError.Severity.FATAL;
      case WARNING -> ConfigError.Severity.WARNING;
      case PLACEHOLDER -> ConfigError.Severity.PLACEHOLDER;
    };
  }

  /**
   * The unit label a bare measure would print with on this axis, for messages that have an axis but
   * no {@link MechanismUnits}.
   *
   * @param axis the axis
   * @return {@code "m"} or {@code "deg"}
   */
  static String unitLabelOf(Axis axis) {
    return axis == null ? "?" : axis.unitLabel();
  }

  /**
   * The user-unit value of a distance, for messages that build their own numbers.
   *
   * @param distance the distance
   * @return metres
   */
  static double metres(Distance distance) {
    return distance == null ? Double.NaN : distance.in(Meters);
  }

  /**
   * The user-unit value of an angle, for messages that build their own numbers.
   *
   * @param angle the angle
   * @return degrees
   */
  static double degrees(Angle angle) {
    return angle == null ? Double.NaN : angle.in(Degrees);
  }
}

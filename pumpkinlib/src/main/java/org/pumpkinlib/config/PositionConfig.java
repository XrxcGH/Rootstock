package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Meters;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Mass;
import edu.wpi.first.units.measure.MomentOfInertia;
import edu.wpi.first.units.measure.Temperature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.control.PositionReference;
import org.pumpkinlib.core.config.CanIdRegistry;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.Axis;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;
import org.pumpkinlib.units.Range;

/**
 * A mechanism that goes to a place: elevator, arm, wrist, turret, hood, climber.
 *
 * <p>The structure mirrors the physical machine, one nesting level per real component, each named
 * after the real component — motors, gearbox, geometry, sensor, limits, control, homing, goals,
 * simulation. Nothing is flat, and the gear ratio appears exactly once.
 *
 * <h2>The flagship example, reproduced verbatim from {@code design/01} §5.4</h2>
 *
 * <p>Two-Kraken cascade elevator, rotor-only feedback with current-spike homing, 12:1, a 22-tooth
 * #25 sprocket, 2 cascade stages, 55 in of travel:
 *
 * <pre>{@code
 * package frc.robot;
 *
 * import static edu.wpi.first.units.Units.*;
 * import org.pumpkinlib.config.*;
 * import org.pumpkinlib.control.Gains;
 * import org.pumpkinlib.pure.units.Reduction;
 * import org.pumpkinlib.units.*;
 *
 * public final class RobotConfig {
 *
 *   public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
 *       // --- what drives it -------------------------------------------------
 *       // .foc(true) enables FIELD-ORIENTED CONTROL on the voltage requests. It does NOT
 *       // change gain units -- gains stay volts-per-SI.
 *       .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
 *                         .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
 *       // --- the gearbox: ONE place. Change this number and gains, sim, soft limits,
 *       //     Motion Magic constraints, and telemetry units ALL follow. ----------
 *       .reduction(Reduction.ofStages(3.0, 4.0))          // 12:1 -> 2.251 m/s free at the carriage
 *       // --- the geometry ----------------------------------------------------
 *       // 22 teeth x 0.250 in chain pitch x 2 cascade stages = 11.000 in = 0.279400 m per drum rot
 *       .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
 *       // --- what measures it -------------------------------------------------
 *       .feedback(new FeedbackSpec.RotorOnly())
 *       // --- limits -----------------------------------------------------------
 *       .softLimits(Inches.of(0.0), Inches.of(55.0))
 *       .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
 *       // --- control ----------------------------------------------------------
 *       // ControlLocation is OMITTED on purpose: it defaults to ON_MOTOR_PROFILED because the
 *       // leader is a TalonFX, and describe() prints that with its provenance.
 *       // GAINS ARE VOLTS-PER-SI: kP V/m, kD V/(m/s), kV V/(m/s), kA V/(m/s^2), kS and kG volts.
 *       .gains(Gains.realOrSim(
 *           Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33),
 *           Gains.pid(150.0, 0.0, 0.0).withKv(5.00).withKa(0.06).withKg(0.33)))
 *       .constraints(MotionConstraints.of(1.6, 6.0))
 *       .tolerance(Inches.of(0.5), 0.05, 0.06)
 *       .manualControl(0.10, 0.30)
 *       // --- how position becomes true at boot --------------------------------
 *       .homing(HomingStrategy.currentSpike()
 *           .direction(HomingStrategy.Direction.REVERSE)
 *           .voltage(Volts.of(-1.5))
 *           .currentThreshold(Amps.of(30))
 *           .debounce(Seconds.of(0.15))
 *           .timeout(Seconds.of(4.0))
 *           .seedTo(Inches.of(0.0)))
 *       // --- named goals -------------------------------------------------------
 *       .setpoint("STOW",  Inches.of(0.0))
 *       .setpoint("L1",    Inches.of(8.0))
 *       .setpoint("L2",    Inches.of(20.5))
 *       .setpoint("L3",    Inches.of(37.5))
 *       .setpoint("L4",    Inches.of(52.5))
 *       // --- simulation: the ONLY sim code a team writes -----------------------
 *       .sim(Pounds.of(24.0), Inches.of(0.0))
 *       .build();
 *
 *   // Typed setpoint handles -- compiler-checked, and the documented default.
 *   public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");
 *   public static final Setpoint ELEVATOR_L4   = ELEVATOR.setpoint("L4");
 * }
 * }</pre>
 *
 * <h2>Where those numbers come from — every one of them recomputed by {@link #describe()}</h2>
 *
 * <table border="1">
 *   <caption>Derivations for the elevator above</caption>
 *   <tr><th>Quantity</th><th>Derivation</th><th>Value</th></tr>
 *   <tr><td>chain advance per drum rotation</td><td>22 teeth &times; 0.250 in</td>
 *       <td>5.5000 in</td></tr>
 *   <tr><td>travel per drum rotation</td><td>5.5000 in &times; 2 cascade stages</td>
 *       <td><b>0.279400 m</b> (11.0000 in)</td></tr>
 *   <tr><td>effective radius</td><td>0.279400 m &divide; 2&pi;</td>
 *       <td><b>0.0444679 m</b> — the cascade &times;2 is <i>included</i>; dropping it is the exact
 *       bug {@link LinearAxis} exists to prevent</td></tr>
 *   <tr><td>free speed</td><td>5800 rpm &divide; 60 &divide; 12 &times; 0.279400</td>
 *       <td><b>2.251 m/s</b></td></tr>
 *   <tr><td>cruise 1.6 m/s</td><td>1.6 &divide; 2.251 = 71.1 % of free speed</td>
 *       <td>no alert</td></tr>
 *   <tr><td>soft max 55 in</td><td>1.3970 m &divide; 0.279400</td><td><b>5.0000 drum rot</b></td></tr>
 *   <tr><td>kV</td><td>&asymp; 12 V &divide; 2.251 m/s = 5.33, less kS headroom</td>
 *       <td>5.00 V/(m/s)</td></tr>
 *   <tr><td>kG</td><td>24 lb &rarr; 10.8862 kg &rarr; 106.756 N &times; 0.0444679 m = 4.74731 N&middot;m
 *       &divide; 12 &divide; 2 motors = 0.197805 N&middot;m &divide; kT 0.0194 = 10.1961 A
 *       &times; R 0.0328 &Omega;</td><td><b>0.33 V</b> (an upper bound: with FOC,
 *       R = 0.0248 &Omega; gives about 0.25 V, and neither figure includes friction — kG is a
 *       <i>measured</i> quantity)</td></tr>
 * </table>
 *
 * <h2>Change the gear ratio in exactly one place</h2>
 *
 * <p>Editing {@code Reduction.ofStages(3.0, 4.0)} to {@code (3.0, 5.0)} automatically updates the
 * device's sensor-to-mechanism ratio, both soft-limit thresholds, the Motion Magic cruise and
 * acceleration, the Expo {@code kV}/{@code kA}, the {@code ElevatorSim} gearing, every telemetry
 * key's scaling, the free-speed check, {@link #describe()}, the homing seed conversion and the
 * {@code atGoal} tolerance conversion. The <b>gains do not change</b>: they are volts-per-SI, so
 * they describe the mechanism rather than the gearbox.
 *
 * @param name the mechanism's name, as the team wrote it; used in every log key and every message
 * @param motors the leader and its followers
 * @param reduction the gearbox, rotor rotations per output rotation
 * @param axis what one output rotation does — drum and rigging, or joint and gravity reference
 * @param feedback what actually measures position
 * @param limits soft limits, hard stops, current limits and thermal thresholds
 * @param control where the loop runs, its gains, its constraints and its arrival test
 * @param homing how position becomes true after a power cycle
 * @param setpoints the named goals, in declaration order
 * @param sim the mass or moment of inertia the simulated plant is built from
 * @param errors every problem validation found, <b>collected and never thrown</b> (§5.6)
 */
public record PositionConfig(
    String name,
    MotorGroup motors,
    Reduction reduction,
    Axis axis,
    FeedbackSpec feedback,
    PositionLimits limits,
    ControlConfig control,
    HomingStrategy homing,
    List<Setpoint> setpoints,
    SimConfig sim,
    List<ConfigError> errors) {

  /**
   * Compact constructor: <b>pure, local and non-throwing.</b>
   *
   * <p>It runs only the checks that need nothing but this config's own fields, and it <i>stores</i>
   * the result. It does not touch {@link CanIdRegistry}, does not read files, does not mutate any
   * global state, and above all does not throw — because every example declares configs as
   * {@code public static final} in a {@code RobotConfig}, so a throw surfaces as
   * {@code ExceptionInInitializerError} from {@code frc.robot.RobotConfig.<clinit>}, robot code
   * never starts, and the message becomes a cause buried under JVM class-init frames (§5.6).
   *
   * <p>The absence of global side effects is also what makes {@link #withGains} free: running this
   * constructor again on a copy cannot produce a false "CAN ID conflict", which is what killed the
   * per-robot overlay pattern in revision 1 (§5.6b).
   */
  public PositionConfig {
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? Validation.kNoMotors : motors;
    reduction = reduction == null ? Reduction.IDENTITY : reduction;
    axis = axis == null ? Validation.kUnsetLinearAxis : axis;
    feedback = feedback == null ? new FeedbackSpec.RotorOnly() : feedback;
    limits = limits == null ? Validation.kUnsetLinearLimits : limits;
    control = control == null ? ControlConfig.defaults() : control;
    homing = homing == null ? Validation.kNoHoming : homing;
    setpoints = setpoints == null ? List.of() : List.copyOf(setpoints);
    sim = sim == null ? Validation.kUnsetSim : sim;
    errors =
        Validation.localChecks(
            name, motors, reduction, axis, feedback, limits, control, homing, setpoints, sim);
  }

  // -------------------------------------------------------------------------------------------
  // Factories
  // -------------------------------------------------------------------------------------------

  /**
   * A builder for a mechanism that travels along a line: elevator, climber, linear extension.
   *
   * @param name the mechanism's name, as it should appear in logs and messages
   * @return the builder
   */
  public static Builder linear(String name) {
    return new Builder(name, true);
  }

  /**
   * A builder for a mechanism that rotates about a joint: arm, wrist, turret, hood.
   *
   * @param name the mechanism's name, as it should appear in logs and messages
   * @return the builder
   */
  public static Builder rotary(String name) {
    return new Builder(name, false);
  }

  // -------------------------------------------------------------------------------------------
  // with*() copies — per-robot variation without copy-paste
  // -------------------------------------------------------------------------------------------

  /**
   * A copy driven by different motors — the practice-bot / A-bot / B-bot case that sibling-robot
   * libraries solve with a hundred abstract getters.
   *
   * @param value the motor group
   * @return a new config; this one is unchanged
   */
  public PositionConfig withMotors(MotorGroup value) {
    return new PositionConfig(
        name, value, reduction, axis, feedback, limits, control, homing, setpoints, sim, errors);
  }

  /**
   * A copy with a different gearbox. Ten derived quantities follow it; the gains do not.
   *
   * @param value the reduction
   * @return a new config; this one is unchanged
   */
  public PositionConfig withReduction(Reduction value) {
    return new PositionConfig(
        name, motors, value, axis, feedback, limits, control, homing, setpoints, sim, errors);
  }

  /**
   * A copy with different geometry.
   *
   * @param value the axis
   * @return a new config; this one is unchanged
   */
  public PositionConfig withAxis(Axis value) {
    return new PositionConfig(
        name, motors, reduction, value, feedback, limits, control, homing, setpoints, sim, errors);
  }

  /**
   * A copy with a different position sensor.
   *
   * @param value the feedback spec
   * @return a new config; this one is unchanged
   */
  public PositionConfig withFeedback(FeedbackSpec value) {
    return new PositionConfig(
        name, motors, reduction, axis, value, limits, control, homing, setpoints, sim, errors);
  }

  /**
   * A copy with different limits.
   *
   * @param value the limits
   * @return a new config; this one is unchanged
   */
  public PositionConfig withLimits(PositionLimits value) {
    return new PositionConfig(
        name, motors, reduction, axis, feedback, value, control, homing, setpoints, sim, errors);
  }

  /**
   * A copy with a different control block.
   *
   * @param value the control config
   * @return a new config; this one is unchanged
   */
  public PositionConfig withControl(ControlConfig value) {
    return new PositionConfig(
        name, motors, reduction, axis, feedback, limits, value, homing, setpoints, sim, errors);
  }

  /**
   * A copy with different gains — the single most common per-robot override.
   *
   * @param value the gains, in volts per SI unit
   * @return a new config; this one is unchanged
   */
  public PositionConfig withGains(Gains value) {
    return withControl(control.withGains(value));
  }

  /**
   * A copy with different profile constraints.
   *
   * @param value the constraints, in user units per second and second squared
   * @return a new config; this one is unchanged
   */
  public PositionConfig withConstraints(MotionConstraints value) {
    return withControl(control.withConstraints(value));
  }

  /**
   * A copy that homes a different way — for example a practice bot with no absolute encoder.
   *
   * @param value the homing strategy
   * @return a new config; this one is unchanged
   */
  public PositionConfig withHoming(HomingStrategy value) {
    return new PositionConfig(
        name, motors, reduction, axis, feedback, limits, control, value, setpoints, sim, errors);
  }

  /**
   * A copy with a different simulated plant.
   *
   * @param value the sim config
   * @return a new config; this one is unchanged
   */
  public PositionConfig withSim(SimConfig value) {
    return new PositionConfig(
        name, motors, reduction, axis, feedback, limits, control, homing, setpoints, value, errors);
  }

  /**
   * A copy in which one named goal has moved, or a new one has been added.
   *
   * <p>This is how a competition-day tweak to an L4 height stays a one-line diff.
   *
   * @param setpointName the goal's name; an existing goal with this name is replaced
   * @param value the new goal, as a {@link Distance} or an {@link Angle}
   * @return a new config; this one is unchanged
   */
  public PositionConfig withSetpoint(String setpointName, Measure<?> value) {
    List<Setpoint> next = new ArrayList<>(setpoints.size() + 1);
    boolean replaced = false;
    Setpoint replacement = new Setpoint(name, setpointName, value, true);
    for (Setpoint existing : setpoints) {
      if (existing.name().equals(setpointName)) {
        next.add(replacement);
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced) {
      next.add(replacement);
    }
    return new PositionConfig(
        name, motors, reduction, axis, feedback, limits, control, homing, next, sim, errors);
  }

  // -------------------------------------------------------------------------------------------
  // Setpoints
  // -------------------------------------------------------------------------------------------

  /**
   * A typed, compiler-checkable handle on a named goal.
   *
   * <p><b>Never throws.</b> An unknown name yields {@link Setpoint#isResolved()} {@code == false}
   * and records a {@link ConfigError} that {@code PumpkinRegistry} prints before anyone presses a
   * button. It has to work that way: every example assigns these to {@code public static final}
   * fields, and a throw there is a robot that does not start.
   *
   * @param setpointName the goal's name, exactly as written in the config
   * @return the setpoint, resolved or not
   */
  public Setpoint setpoint(String setpointName) {
    for (Setpoint candidate : setpoints) {
      if (candidate.name().equals(setpointName)) {
        return candidate;
      }
    }
    Setpoint missed = Setpoint.unresolved(name, setpointName);
    List<String> names = new ArrayList<>(setpoints.size());
    for (Setpoint declared : setpoints) {
      names.add('"' + declared.name() + '"');
    }
    Validation.recordLookupMiss(
        ConfigError.fatal(
            name,
            "setpoint(\"" + setpointName + "\")",
            "no such setpoint",
            names.isEmpty() ? "this mechanism declares no setpoints at all" : String.join(", ", names),
            "Nothing that asks for this goal will move: every command targeting it refuses with "
                + "this message rather than doing nothing quietly.\n"
                + "Fix: check the spelling and the capitalisation against the .setpoint(...) calls "
                + "in "
                + name
                + "'s config"
                + (setpointName == null || setpointName.equals(setpointName.strip())
                    ? "."
                    : ". NOTE: the name you asked for has leading or trailing whitespace, which is "
                        + "almost certainly the whole problem.")));
    return missed;
  }

  /**
   * The same lookup, for code that would rather branch than carry an unresolved handle.
   *
   * @param setpointName the goal's name
   * @return the setpoint, or empty when there is none by that name
   */
  public Optional<Setpoint> findSetpoint(String setpointName) {
    for (Setpoint candidate : setpoints) {
      if (candidate.name().equals(setpointName)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  // -------------------------------------------------------------------------------------------
  // Derived values
  // -------------------------------------------------------------------------------------------

  /**
   * The one converter every seam in the library uses: output rotations to metres or degrees, user
   * units to rotor rotations, user units to SI.
   *
   * @return the mechanism's units
   */
  public MechanismUnits units() {
    return MechanismUnits.of(reduction, axis);
  }

  /**
   * Which of the three mechanism shapes this is.
   *
   * @return always {@link MechanismKind#POSITION}
   */
  public MechanismKind kind() {
    return MechanismKind.POSITION;
  }

  /**
   * Whether this mechanism travels along a line.
   *
   * @return true for a {@link LinearAxis}
   */
  public boolean isLinear() {
    return axis instanceof LinearAxis;
  }

  /**
   * The soft limits as a {@link Range} in user units, ready for {@code contains} and {@code clamp}.
   *
   * @return the travel range
   */
  public Range travel() {
    return limits.range();
  }

  /**
   * The estimated free speed of the mechanism, in user units per second.
   *
   * <p>Free rotor speed of the leader's motor model, through the gearbox, through the geometry.
   * This is what the tier-2 cruise-velocity check compares against, and what makes
   * "{@code MotionMagicCruiseVelocity} was never reachable" a boot-time sentence instead of a
   * season-long mystery.
   *
   * @return metres or degrees per second
   */
  public double freeSpeedUserPerSec() {
    return units().freeSpeedUserPerSec(motors.model().freeSpeedRotorRps(motors.leader().foc()));
  }

  /**
   * What the tuning wizard is allowed to believe about this mechanism's position at boot.
   *
   * @return the position reference implied by the feedback spec and the homing strategy
   */
  public PositionReference positionReference() {
    return homing.positionReference(feedback);
  }

  /**
   * Every CAN device this mechanism claims, for the single global uniqueness scan (§5.6b).
   *
   * <p>Deliberately <b>not</b> called from the constructor: every {@code with*()} copy re-runs that
   * constructor, and registering there would report a false conflict on a correct config.
   *
   * @return the motors, the CANcoder if there is one, and any CAN hard-stop sensors
   */
  public List<CanIdRegistry.Device> canDevices() {
    List<CanIdRegistry.Device> out = new ArrayList<>(motors.canDevices(name));
    feedback.canDevice(name).ifPresent(out::add);
    limits.hardStop(HardStop.FORWARD).flatMap(s -> s.canDevice(name, "forward hard stop"))
        .ifPresent(out::add);
    limits.hardStop(HardStop.REVERSE).flatMap(s -> s.canDevice(name, "reverse hard stop"))
        .ifPresent(out::add);
    if (homing instanceof HomingStrategy.LimitSwitch ls && ls.sensor() != null) {
      ls.sensor().canDevice(name, "homing switch").ifPresent(out::add);
    }
    return List.copyOf(out);
  }

  /**
   * Whether this config has any fatal problem, and therefore whether registering it puts the robot
   * into safe mode.
   *
   * @return true when at least one collected error is fatal
   */
  public boolean hasFatalError() {
    return errors.stream().anyMatch(ConfigError::isFatal);
  }

  /**
   * The loggable, flattened form of this config.
   *
   * @return the snapshot
   */
  public MechanismConfigSnapshot snapshot() {
    return MechanismConfigSnapshot.of(this);
  }

  // -------------------------------------------------------------------------------------------
  // describe()
  // -------------------------------------------------------------------------------------------

  /**
   * The boot dump: every resolved derivation, printed once, so a mis-scaled mechanism is caught
   * <b>before it moves</b>.
   *
   * <p>It prints the gearbox as the team wrote it and as a single number, what one output rotation
   * does in user units and in SI, the soft limits in both user units and output rotations, the
   * gains with their units spelled out, the chosen {@link ControlLocation} <i>and why</i>, the
   * homing strategy including the clamps the library applies to it, the free-speed estimate the
   * cruise velocity is checked against, and every named goal.
   *
   * @return a multi-line block, newline-terminated
   */
  public String describe() {
    String nl = System.lineSeparator();
    MechanismUnits units = units();
    String unit = units.unitLabel();
    StringBuilder sb = new StringBuilder(2048);

    sb.append("=== ").append(name).append("  (PositionConfig, ").append(kind().describe())
        .append(") ===").append(nl);
    sb.append("  Motors              ").append(motors.describe()).append(nl);
    sb.append("  Geometry            ").append(units.describe(name)).append(nl);

    double perRot = units.userPerOutputRotation();
    sb.append(
            String.format(
                Locale.ROOT,
                "  One output rotation %.6f %s  (%.6f %s in SI)",
                perRot,
                unit,
                units.siPerOutputRotation(),
                units.siLabel()))
        .append(nl);

    Range travel = travel();
    sb.append("  Soft limits         ").append(travel.describe());
    if (units.userPerOutputRotation() != 0.0) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  =  %.4f .. %.4f output rot",
              units.toOutputRotations(travel.min()),
              units.toOutputRotations(travel.max())));
    }
    sb.append(nl);
    sb.append("  Hard stops          ")
        .append(describeHardStop(HardStop.FORWARD))
        .append(" / ")
        .append(describeHardStop(HardStop.REVERSE))
        .append(nl);
    sb.append("  Current limits      ").append(limits.current().describe()).append(nl);
    sb.append("  Feedback            ").append(feedback.describe()).append(nl);
    sb.append("  Position reference  ").append(positionReference().describe()).append(nl);
    sb.append("  Homing              ").append(homing.describe()).append(nl);

    sb.append(control.describe(unit, units.siDomain(), motors.leader().deviceType()));

    double freeSpeed = freeSpeedUserPerSec();
    sb.append(
            String.format(
                Locale.ROOT,
                "  Free speed estimate %.4f %s/s  (%d x %s at %.0f rotor rps through %s)",
                freeSpeed,
                unit,
                motors.count(),
                motors.model().displayName(),
                motors.model().freeSpeedRotorRps(motors.leader().foc()),
                reduction.describe()))
        .append(nl);
    if (Double.isFinite(freeSpeed) && freeSpeed > 0.0) {
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  Cruise velocity     %.4f %s/s = %.1f%% of free speed",
                  control.constraints().maxVelocity(),
                  unit,
                  100.0 * control.constraints().maxVelocity() / freeSpeed))
          .append(nl);
    }

    sb.append("  Simulation          ").append(sim.describe()).append(nl);

    sb.append("  Setpoints           ");
    if (setpoints.isEmpty()) {
      sb.append("(none declared)").append(nl);
    } else {
      boolean first = true;
      for (Setpoint setpoint : setpoints) {
        sb.append(first ? "" : "                      ").append(setpoint.describe()).append(nl);
        first = false;
      }
    }

    sb.append("  Config errors       ")
        .append(errors.isEmpty() ? "none" : errors.size() + " — see the list above")
        .append(nl);
    for (ConfigError error : errors) {
      sb.append("    ").append(error.summary()).append(nl);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "PositionConfig[" + name + ", " + motors.count() + " motor(s), " + reduction.ratio()
        + ":1, " + errors.size() + " error(s)]";
  }

  private String describeHardStop(HardStop side) {
    return limits
        .hardStop(side)
        .map(sensor -> side + ": " + sensor.describe())
        .orElse(side + ": none");
  }

  // -------------------------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------------------------

  /**
   * Builds a {@link PositionConfig} in the order a student thinks about the machine: what drives
   * it, what gears it, what shape it is, what measures it, how far it may go, how it is controlled,
   * how it finds zero, where it should stop, and how to simulate it.
   *
   * <p><b>No setter throws and neither does {@link #build()}.</b> A value that is wrong becomes a
   * {@link ConfigError} on the finished config, printed together with every other one, after which
   * the robot boots into safe mode and tells you about it.
   */
  public static final class Builder {
    private final String m_name;
    private final boolean m_linear;
    private final List<Setpoint> m_setpoints = new ArrayList<>();
    private final ControlConfig.Builder m_control = ControlConfig.builder();

    private MotorGroup m_motors = Validation.kNoMotors;
    private Reduction m_reduction = Reduction.IDENTITY;
    private Axis m_axis;
    private FeedbackSpec m_feedback = new FeedbackSpec.RotorOnly();
    private PositionLimits m_limits;
    private HomingStrategy m_homing = Validation.kNoHoming;
    private SimConfig m_sim = Validation.kUnsetSim;
    private CurrentLimits m_current;
    private Measure<?> m_softMin;
    private Measure<?> m_softMax;
    private Optional<SensorSpec> m_forwardStop = Optional.empty();
    private Optional<SensorSpec> m_reverseStop = Optional.empty();
    private double m_overTempCelsius = PositionLimits.kDefaultOverTempCelsius;
    private double m_followerToleranceRot = PositionLimits.kDefaultFollowerToleranceRot;
    private boolean m_gravitySetExplicitly;

    private Builder(String name, boolean linear) {
      m_name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
      m_linear = linear;
      m_axis = linear ? Validation.kUnsetLinearAxis : Validation.kUnsetRotaryAxis;
    }

    /**
     * What drives it.
     *
     * @param value the leader and its followers
     * @return this builder
     */
    public Builder motors(MotorGroup value) {
      m_motors = value;
      return this;
    }

    /**
     * A single-motor group, for the common case.
     *
     * @param leader the only motor
     * @return this builder
     */
    public Builder motor(MotorSpec leader) {
      return motors(MotorGroup.leader(leader));
    }

    /**
     * The gearbox — the one place the gear ratio appears.
     *
     * @param value rotor rotations per output rotation
     * @return this builder
     */
    public Builder reduction(Reduction value) {
      m_reduction = value;
      return this;
    }

    /**
     * What one output rotation actually does: drum and rigging, or joint and gravity reference.
     *
     * @param value the axis
     * @return this builder
     */
    public Builder axis(Axis value) {
      m_axis = value;
      return this;
    }

    /**
     * What measures position.
     *
     * @param value the feedback spec
     * @return this builder
     */
    public Builder feedback(FeedbackSpec value) {
      m_feedback = value;
      return this;
    }

    /**
     * The travel limits of a linear mechanism.
     *
     * @param min the lower limit
     * @param max the upper limit
     * @return this builder
     */
    public Builder softLimits(Distance min, Distance max) {
      m_softMin = min;
      m_softMax = max;
      return this;
    }

    /**
     * The travel limits of a rotary mechanism.
     *
     * @param min the lower limit
     * @param max the upper limit
     * @return this builder
     */
    public Builder softLimits(Angle min, Angle max) {
      m_softMin = min;
      m_softMax = max;
      return this;
    }

    /**
     * Stator and supply limits. Omit it and the library uses the leader motor's defaults.
     *
     * @param value the current limits
     * @return this builder
     */
    public Builder currentLimits(CurrentLimits value) {
      m_current = value;
      return this;
    }

    /**
     * A physical stop at one end of travel.
     *
     * @param side which end
     * @param sensor the switch that detects it
     * @return this builder
     */
    public Builder hardStop(HardStop side, SensorSpec sensor) {
      if (side == HardStop.FORWARD) {
        m_forwardStop = Optional.ofNullable(sensor);
      } else {
        m_reverseStop = Optional.ofNullable(sensor);
      }
      return this;
    }

    /**
     * The temperature at which the health monitor complains about this mechanism's motors.
     *
     * @param value the threshold
     * @return this builder
     */
    public Builder overTemperature(Temperature value) {
      m_overTempCelsius =
          value == null ? Double.NaN : value.in(edu.wpi.first.units.Units.Celsius);
      return this;
    }

    /**
     * How far a follower may drift from its leader before that is a fault, in output rotations.
     *
     * @param outputRotations the tolerance
     * @return this builder
     */
    public Builder followerTolerance(double outputRotations) {
      m_followerToleranceRot = outputRotations;
      return this;
    }

    /**
     * Overrides where the loop runs. Omit it and the library picks from your leader motor and says
     * so in {@link PositionConfig#describe()}.
     *
     * @param value the control location
     * @return this builder
     */
    public Builder controlLocation(ControlLocation value) {
      m_control.location(value);
      return this;
    }

    /**
     * The loop gains, in volts per SI unit.
     *
     * @param value the gains
     * @return this builder
     */
    public Builder gains(Gains value) {
      m_control.gains(value);
      return this;
    }

    /**
     * The profile constraints, in user units per second and per second squared.
     *
     * @param value the constraints
     * @return this builder
     */
    public Builder constraints(MotionConstraints value) {
      m_control.constraints(value);
      return this;
    }

    /**
     * Shapes the profile from measured {@code kV} and {@code kA} instead of a cruise velocity.
     *
     * @param value true to use Motion Magic Expo
     * @return this builder
     */
    public Builder useExpo(boolean value) {
      m_control.useExpo(value);
      return this;
    }

    /**
     * Overrides the gravity model the axis implies.
     *
     * @param value the gravity model
     * @return this builder
     */
    public Builder gravity(GravityMode value) {
      m_control.gravity(value);
      m_gravitySetExplicitly = true;
      return this;
    }

    /**
     * What the motor does when nothing is commanding it.
     *
     * @param value brake or coast
     * @return this builder
     */
    public Builder neutralMode(NeutralMode value) {
      m_control.neutralMode(value);
      return this;
    }

    /**
     * The arrival test, all three parts at once.
     *
     * @param position the position tolerance
     * @param velocityPerSecond the velocity gate, in user units per second
     * @param debounceSeconds how long both must hold before {@code atGoal()} latches
     * @return this builder
     */
    public Builder tolerance(
        Measure<?> position, double velocityPerSecond, double debounceSeconds) {
      m_control.tolerance(position, velocityPerSecond, debounceSeconds);
      return this;
    }

    /**
     * How a driver's stick feels on this mechanism.
     *
     * @param deadband the stick deadband, 0..1
     * @param scale the fraction of full output at full stick, 0..1
     * @return this builder
     */
    public Builder manualControl(double deadband, double scale) {
      m_control.manualControl(deadband, scale);
      return this;
    }

    /**
     * How position becomes true after a power cycle.
     *
     * @param value the homing strategy
     * @return this builder
     */
    public Builder homing(HomingStrategy value) {
      m_homing = value;
      return this;
    }

    /**
     * A named goal on a linear mechanism.
     *
     * @param setpointName the goal's name, as a driver would say it
     * @param value the height
     * @return this builder
     */
    public Builder setpoint(String setpointName, Distance value) {
      m_setpoints.add(Setpoint.of(m_name, setpointName, value));
      return this;
    }

    /**
     * A named goal on a rotary mechanism.
     *
     * @param setpointName the goal's name, as a driver would say it
     * @param value the angle
     * @return this builder
     */
    public Builder setpoint(String setpointName, Angle value) {
      m_setpoints.add(Setpoint.of(m_name, setpointName, value));
      return this;
    }

    /**
     * The simulated plant, stated in full.
     *
     * @param value the sim config
     * @return this builder
     */
    public Builder sim(SimConfig value) {
      m_sim = value;
      return this;
    }

    /**
     * The simulated plant of a linear mechanism — the only sim code a team writes.
     *
     * @param carriageMass the moving mass
     * @param startingPosition where it rests at boot
     * @return this builder
     */
    public Builder sim(Mass carriageMass, Distance startingPosition) {
      return sim(SimConfig.linear(carriageMass, startingPosition));
    }

    /**
     * The simulated plant of a rotary mechanism.
     *
     * @param momentOfInertia the moment of inertia about the joint
     * @param startingPosition where it rests at boot
     * @return this builder
     */
    public Builder sim(MomentOfInertia momentOfInertia, Angle startingPosition) {
      return sim(SimConfig.rotary(momentOfInertia, Meters.of(0.0), startingPosition));
    }

    /**
     * Assembles the config. <b>Never throws.</b>
     *
     * <p>It fills in the three things a team is allowed to leave out — the current limits (from the
     * leader motor's model), the control location (from the leader motor's capabilities) and the
     * gravity model (from the axis) — and records that it did, so {@link PositionConfig#describe()}
     * can print the provenance rather than an unexplained value.
     *
     * @return the config, carrying every problem validation found
     */
    public PositionConfig build() {
      CurrentLimits current =
          m_current != null ? m_current : CurrentLimits.defaultsFor(m_motors.model());

      // The sentinel has to keep its IDENTITY: Validation distinguishes "you never called
      // .softLimits(...)" from "you called it with two identical numbers" by reference, and
      // rebuilding it here would collapse the two into the same, much less helpful, message.
      PositionLimits limits =
          m_softMin == null || m_softMax == null
              ? (m_linear ? Validation.kUnsetLinearLimits : Validation.kUnsetRotaryLimits)
              : new PositionLimits(
                  m_softMin,
                  m_softMax,
                  current,
                  m_overTempCelsius,
                  m_followerToleranceRot,
                  m_forwardStop,
                  m_reverseStop);

      m_control.defaultedLocation(ControlConfig.defaultLocationFor(m_motors.leader()));
      m_control.defaultToleranceFor(m_linear);
      if (!m_gravitySetExplicitly) {
        m_control.gravity(m_axis.gravity());
      }

      return new PositionConfig(
          m_name,
          m_motors,
          m_reduction,
          m_axis,
          m_feedback,
          limits,
          m_control.build(),
          m_homing,
          m_setpoints,
          m_sim,
          List.of());
    }
  }
}

package org.rootstock.config;

import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.LinearVelocity;
import edu.wpi.first.units.measure.MomentOfInertia;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.Gains;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.config.CanIdRegistry;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.Axis;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.RotaryAxis;

/**
 * A mechanism that holds a speed: flywheel, indexer, a roller under closed-loop velocity.
 *
 * <p>It is the same shape as {@link PositionConfig} minus everything that only makes sense for a
 * mechanism with an <i>end</i>: no soft limits, no named position goals, no homing. What it gains
 * instead is a two-sided, debounced {@code atGoal()} — a flywheel passing <i>through</i> its
 * setpoint on the way up is not ready to shoot, and the single most common velocity-mechanism bug
 * is a shot fired on that one loop.
 *
 * <h2>A velocity mechanism does not subscribe to position</h2>
 *
 * <p>Signal subscription defaults to velocity, applied volts, stator, supply and temperature —
 * position is <b>not</b> read, so {@code MotorInputs.positionRot} is deliberately {@code NaN} and
 * {@link #describe()} lists it under "signals NOT read". The default rate is
 * {@link MechanismKind#VELOCITY}'s 50 Hz rather than a position mechanism's 100 Hz, because a
 * debounced velocity predicate over tens of milliseconds gains nothing from the extra frames and
 * the saving is real: about 2.4 % of a 1 Mbps CAN bus instead of 4.5 %. A team that wants the
 * faster loop writes {@code MotorSpec.talonFX(id, bus).signalRateHz(100.0)} and has therefore
 * decided to spend the bus budget on purpose.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public static final VelocityConfig SHOOTER = VelocityConfig.of("Shooter")
 *     .motors(MotorGroup.leader(MotorSpec.talonFX(30, "rio").foc(true))
 *                       .follower(MotorSpec.talonFX(31, "rio"), Follower.OPPOSED))
 *     .reduction(Reduction.of(1.0))                       // direct drive
 *     .currentLimits(CurrentLimits.of(Amps.of(80), Amps.of(40)))
 *     .gains(Gains.pid(0.10, 0.0, 0.0).withKs(0.15).withKv(0.13).withKa(0.01))
 *     .constraints(MotionConstraints.of(6000.0, 12000.0)) // deg/s, deg/s^2 at the wheel
 *     .tolerance(120.0, 0.10)                             // deg/s, held 0.10 s
 *     .neutralMode(NeutralMode.COAST)                     // never brake a flywheel
 *     .sim(KilogramSquareMeters.of(0.004))
 *     .build();
 * }</pre>
 *
 * @param name the mechanism's name, as the team wrote it
 * @param motors the leader and its followers
 * @param reduction the gearbox, rotor rotations per output rotation
 * @param axis what one output rotation means; almost always {@link RotaryAxis#roller()}
 * @param feedback what measures speed; a flywheel is happy with the rotor
 * @param current the stator and supply limits
 * @param control where the loop runs, its gains, its constraints and its arrival test
 * @param sim the moment of inertia the simulated flywheel is built from
 * @param errors every problem validation found, collected and never thrown (§5.6)
 */
public record VelocityConfig(
    String name,
    MotorGroup motors,
    Reduction reduction,
    Axis axis,
    FeedbackSpec feedback,
    CurrentLimits current,
    ControlConfig control,
    SimConfig sim,
    List<ConfigError> errors) {

  /**
   * Compact constructor: pure, local and non-throwing, for the reasons {@link PositionConfig} gives
   * at length. It substitutes documented defaults for nulls and stores the collected errors.
   */
  public VelocityConfig {
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? Validation.kNoMotors : motors;
    reduction = reduction == null ? Reduction.IDENTITY : reduction;
    axis = axis == null ? RotaryAxis.roller() : axis;
    feedback = feedback == null ? new FeedbackSpec.RotorOnly() : feedback;
    current = current == null ? CurrentLimits.defaultsFor(motors.model()) : current;
    control = control == null ? ControlConfig.defaults() : control;
    sim = sim == null ? Validation.kUnsetSim : sim;
    errors = Validation.localChecks(name, motors, reduction, axis, feedback, current, control, sim);
  }

  /**
   * A builder for a velocity mechanism.
   *
   * @param name the mechanism's name, as it should appear in logs and messages
   * @return the builder
   */
  public static Builder of(String name) {
    return new Builder(name);
  }

  // -------------------------------------------------------------------------------------------
  // with*() copies
  // -------------------------------------------------------------------------------------------

  /**
   * A copy driven by different motors.
   *
   * @param value the motor group
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withMotors(MotorGroup value) {
    return new VelocityConfig(name, value, reduction, axis, feedback, current, control, sim, errors);
  }

  /**
   * A copy with a different gearbox.
   *
   * @param value the reduction
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withReduction(Reduction value) {
    return new VelocityConfig(name, motors, value, axis, feedback, current, control, sim, errors);
  }

  /**
   * A copy with a different axis.
   *
   * @param value the axis
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withAxis(Axis value) {
    return new VelocityConfig(name, motors, reduction, value, feedback, current, control, sim, errors);
  }

  /**
   * A copy with a different speed sensor.
   *
   * @param value the feedback spec
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withFeedback(FeedbackSpec value) {
    return new VelocityConfig(name, motors, reduction, axis, value, current, control, sim, errors);
  }

  /**
   * A copy with different current limits.
   *
   * @param value the current limits
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withCurrentLimits(CurrentLimits value) {
    return new VelocityConfig(name, motors, reduction, axis, feedback, value, control, sim, errors);
  }

  /**
   * A copy with a different control block.
   *
   * @param value the control config
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withControl(ControlConfig value) {
    return new VelocityConfig(name, motors, reduction, axis, feedback, current, value, sim, errors);
  }

  /**
   * A copy with different gains.
   *
   * @param value the gains, in volts per SI unit
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withGains(Gains value) {
    return withControl(control.withGains(value));
  }

  /**
   * A copy with different constraints.
   *
   * @param value the constraints, in user units per second and second squared
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withConstraints(MotionConstraints value) {
    return withControl(control.withConstraints(value));
  }

  /**
   * A copy with a different simulated plant.
   *
   * @param value the sim config
   * @return a new config; this one is unchanged
   */
  public VelocityConfig withSim(SimConfig value) {
    return new VelocityConfig(
        name, motors, reduction, axis, feedback, current, control, value == null ? sim : value,
        errors);
  }

  // -------------------------------------------------------------------------------------------
  // Derived values
  // -------------------------------------------------------------------------------------------

  /**
   * The one converter every seam uses.
   *
   * @return the mechanism's units
   */
  public MechanismUnits units() {
    return MechanismUnits.of(reduction, axis);
  }

  /**
   * Which of the three mechanism shapes this is.
   *
   * @return always {@link MechanismKind#VELOCITY}
   */
  public MechanismKind kind() {
    return MechanismKind.VELOCITY;
  }

  /**
   * The estimated free speed of the mechanism, in user units per second — the ceiling any target
   * speed is checked against.
   *
   * @return degrees (or metres) per second
   */
  public double freeSpeedUserPerSec() {
    return units().freeSpeedUserPerSec(motors.model().freeSpeedRotorRps(motors.leader().foc()));
  }

  /**
   * Every CAN device this mechanism claims, for the single global uniqueness scan (§5.6b).
   *
   * @return the motors and the speed sensor, if it is on the bus
   */
  public List<CanIdRegistry.Device> canDevices() {
    List<CanIdRegistry.Device> out = new ArrayList<>(motors.canDevices(name));
    feedback.canDevice(name).ifPresent(out::add);
    return List.copyOf(out);
  }

  /**
   * Whether registering this config puts the robot into safe mode.
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

  /**
   * The boot dump for a velocity mechanism, including the signals it deliberately does not read.
   *
   * @return a multi-line block, newline-terminated
   */
  public String describe() {
    String nl = System.lineSeparator();
    MechanismUnits units = units();
    String unit = units.unitLabel();
    StringBuilder sb = new StringBuilder(1024);

    sb.append("=== ").append(name).append("  (VelocityConfig, ").append(kind().describe())
        .append(") ===").append(nl);
    sb.append("  Motors              ").append(motors.describe()).append(nl);
    sb.append("  Geometry            ").append(units.describe(name)).append(nl);
    sb.append("  Current limits      ").append(current.describe()).append(nl);
    sb.append("  Feedback            ").append(feedback.describe()).append(nl);
    sb.append(control.describe(unit, units.siDomain(), motors.leader().deviceType()));

    double freeSpeed = freeSpeedUserPerSec();
    sb.append(
            String.format(
                Locale.ROOT,
                "  Free speed estimate %.4f %s/s  (%d x %s through %s)",
                freeSpeed,
                unit,
                motors.count(),
                motors.model().displayName(),
                reduction.describe()))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Signal rate         %.0f Hz default for a VELOCITY mechanism",
                kind().defaultSignalRateHz()))
        .append(nl);
    sb.append("  Signals NOT read    position (a velocity mechanism does not subscribe to it, so ")
        .append("MotorInputs.positionRot is NaN by design)")
        .append(nl);
    sb.append("  Simulation          ").append(sim.describe()).append(nl);
    sb.append("  Config errors       ")
        .append(errors.isEmpty() ? "none" : errors.size() + " (see the list above)")
        .append(nl);
    for (ConfigError error : errors) {
      sb.append("    ").append(error.summary()).append(nl);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "VelocityConfig[" + name + ", " + motors.count() + " motor(s), " + errors.size()
        + " error(s)]";
  }

  // -------------------------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------------------------

  /**
   * Builds a {@link VelocityConfig}. No setter throws and neither does {@link #build()}.
   */
  public static final class Builder {
    private final String m_name;
    private final ControlConfig.Builder m_control = ControlConfig.builder();

    private MotorGroup m_motors = Validation.kNoMotors;
    private Reduction m_reduction = Reduction.IDENTITY;
    private Axis m_axis = RotaryAxis.roller();
    private FeedbackSpec m_feedback = new FeedbackSpec.RotorOnly();
    private CurrentLimits m_current;
    private SimConfig m_sim = Validation.kUnsetSim;

    private Builder(String name) {
      m_name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
      // A flywheel that brakes on neutral wears its gearbox out for nothing, so the default is the
      // opposite of a position mechanism's.
      m_control.neutralMode(NeutralMode.COAST);
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
     * The gearbox.
     *
     * @param value rotor rotations per output rotation
     * @return this builder
     */
    public Builder reduction(Reduction value) {
      m_reduction = value;
      return this;
    }

    /**
     * What one output rotation means. Defaults to {@link RotaryAxis#roller()}, which is what a
     * flywheel is; set it to a {@link org.rootstock.units.LinearAxis} for a surface-speed
     * mechanism such as a shooter hood roller measured in metres per second.
     *
     * @param value the axis
     * @return this builder
     */
    public Builder axis(Axis value) {
      m_axis = value;
      return this;
    }

    /**
     * What measures speed.
     *
     * @param value the feedback spec
     * @return this builder
     */
    public Builder feedback(FeedbackSpec value) {
      m_feedback = value;
      return this;
    }

    /**
     * Stator and supply limits. Omit and the leader motor's defaults are used.
     *
     * @param value the current limits
     * @return this builder
     */
    public Builder currentLimits(CurrentLimits value) {
      m_current = value;
      return this;
    }

    /**
     * Overrides where the loop runs.
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
     * The profile constraints, in user units per second and per second squared. On a velocity
     * mechanism the acceleration is the spin-up ramp.
     *
     * @param value the constraints
     * @return this builder
     */
    public Builder constraints(MotionConstraints value) {
      m_control.constraints(value);
      return this;
    }

    /**
     * The arrival test for a speed: how close, and for how long.
     *
     * @param velocityPerSecond the speed tolerance, in user units per second
     * @param debounceSeconds how long it must hold before {@code atGoal()} latches
     * @return this builder
     */
    public Builder tolerance(double velocityPerSecond, double debounceSeconds) {
      // A velocity mechanism has no position tolerance; the position slot is filled with the same
      // number so the record stays uniform and describe() never prints a blank.
      m_control.tolerance(
          edu.wpi.first.units.Units.Degrees.of(velocityPerSecond),
          velocityPerSecond,
          debounceSeconds);
      return this;
    }

    /**
     * The arrival test stated as an angular speed.
     *
     * @param tolerance the speed tolerance
     * @param debounceSeconds how long it must hold
     * @return this builder
     */
    public Builder tolerance(AngularVelocity tolerance, double debounceSeconds) {
      double perSecond =
          tolerance == null
              ? Double.NaN
              : tolerance.in(edu.wpi.first.units.Units.DegreesPerSecond);
      return tolerance(perSecond, debounceSeconds);
    }

    /**
     * The arrival test stated as a surface speed, for a mechanism on a linear axis.
     *
     * @param tolerance the speed tolerance
     * @param debounceSeconds how long it must hold
     * @return this builder
     */
    public Builder tolerance(LinearVelocity tolerance, double debounceSeconds) {
      double perSecond =
          tolerance == null
              ? Double.NaN
              : tolerance.in(edu.wpi.first.units.Units.MetersPerSecond);
      return tolerance(perSecond, debounceSeconds);
    }

    /**
     * What the motor does when nothing is commanding it. Defaults to
     * {@link NeutralMode#COAST} here, unlike a position mechanism.
     *
     * @param value brake or coast
     * @return this builder
     */
    public Builder neutralMode(NeutralMode value) {
      m_control.neutralMode(value);
      return this;
    }

    /**
     * How a driver's stick feels when this mechanism is driven open-loop.
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
     * The simulated flywheel — the only sim code a team writes.
     *
     * @param momentOfInertia the moment of inertia of everything that spins
     * @return this builder
     */
    public Builder sim(MomentOfInertia momentOfInertia) {
      return sim(SimConfig.flywheel(momentOfInertia));
    }

    /**
     * Assembles the config. <b>Never throws.</b>
     *
     * @return the config, carrying every problem validation found
     */
    public VelocityConfig build() {
      CurrentLimits current =
          m_current != null ? m_current : CurrentLimits.defaultsFor(m_motors.model());
      m_control.defaultedLocation(ControlConfig.defaultLocationFor(m_motors.leader()));
      m_control.defaultToleranceFor(false);
      return new VelocityConfig(
          m_name,
          m_motors,
          m_reduction,
          m_axis,
          m_feedback,
          current,
          m_control.build(),
          m_sim,
          List.of());
    }
  }
}

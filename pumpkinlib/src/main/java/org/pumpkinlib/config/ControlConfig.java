package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Radians;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.ControlLocationSource;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.units.SiDomain;

/**
 * Everything about <b>how the loop is closed</b>, gathered in one place: where it runs, what gains
 * it uses, how fast it is allowed to move, when it declares itself arrived, and what a driver's
 * stick means.
 *
 * <h2>Gains are volts-per-SI. Always.</h2>
 *
 * <p>{@code kP} is V/m or V/rad, {@code kD} is V/(m/s) or V/(rad/s), {@code kS} and {@code kG} are
 * volts. They describe the <i>mechanism</i>, not the gearbox and not the encoder, which is why
 * changing {@code Reduction.ofStages(3.0, 4.0)} to {@code (3.0, 5.0)} updates ten derived numbers
 * and leaves the gains alone. Each backend multiplies the position-like terms by
 * {@code MechanismUnits.siPerOutputRotation()} exactly once, at its own seam.
 *
 * <h2>{@code ControlLocation} is required here and defaulted in the builder</h2>
 *
 * <p>"Should the profile and the feedback loop run on the motor controller or on the roboRIO?" is an
 * expert question, and it was once the fifth line of a rookie's first config. It is still a required
 * field of this record — so it is always in the log, the snapshot and the boot dump — but a
 * mechanism builder fills it in from the leader's {@link MotorSpec} and records
 * {@link ControlLocationSource#DEFAULTED}. {@code describe()} then prints it unconditionally with
 * its provenance, so nothing is hidden; only the decision is removed from minute nine.
 *
 * @param location where the profile and the feedback loop run
 * @param locationSource whether the team chose {@code location} or the library defaulted it
 * @param gains the loop gains, in volts per SI unit
 * @param constraints the profile constraints, in <b>user</b> units per second, second squared and
 *     second cubed (m/s or deg/s)
 * @param useExpo whether to use an exponential profile (Motion Magic Expo), which is shaped
 *     entirely by measured {@code kV} and {@code kA} rather than by a cruise velocity
 * @param gravity which gravity model the feedforward uses; normally derived from the
 *     {@link org.pumpkinlib.units.Axis} and overridable here
 * @param tolerance the position tolerance {@code atGoal()} uses, as a {@link Distance} or an
 *     {@link Angle}
 * @param velocityTolerance the velocity gate {@code atGoal()} uses, in user units per second — a
 *     mechanism flying through its setpoint is not at its goal
 * @param goalDebounceSeconds how long both tolerances must hold before {@code atGoal()} latches
 * @param neutralMode what the motor does when nothing is commanding it
 * @param manualDeadband the driver stick deadband, 0..1
 * @param manualScale the fraction of full output a fully deflected stick asks for, 0..1
 */
public record ControlConfig(
    ControlLocation location,
    ControlLocationSource locationSource,
    Gains gains,
    MotionConstraints constraints,
    boolean useExpo,
    GravityMode gravity,
    Measure<?> tolerance,
    double velocityTolerance,
    double goalDebounceSeconds,
    NeutralMode neutralMode,
    double manualDeadband,
    double manualScale) {

  /** The tolerance a config gets when the team does not state one, in metres. */
  public static final double kDefaultLinearToleranceMeters = 0.005;

  /** The tolerance a config gets when the team does not state one, in degrees. */
  public static final double kDefaultRotaryToleranceDegrees = 1.0;

  /** The {@code atGoal()} debounce a config gets when the team does not state one, in seconds. */
  public static final double kDefaultGoalDebounceSeconds = 0.06;

  /** The stick deadband a config gets when the team does not call {@code manualControl(...)}. */
  public static final double kDefaultManualDeadband = 0.10;

  /** The stick scale a config gets when the team does not call {@code manualControl(...)}. */
  public static final double kDefaultManualScale = 0.30;

  /**
   * Canonical constructor. Substitutes documented defaults for nulls; <b>never throws</b>.
   *
   * <p>A config record is constructed from a {@code public static final} field initialiser, so a
   * throw here is a dead robot with an unreadable message (§5.6). Anything genuinely wrong is
   * reported by {@link #problems()} and collected by {@link Validation}.
   */
  public ControlConfig {
    location = location == null ? ControlLocation.RIO_FULL : location;
    locationSource = locationSource == null ? ControlLocationSource.DEFAULTED : locationSource;
    gains = gains == null ? Gains.UNTUNED : gains;
    constraints = constraints == null ? MotionConstraints.unconstrained() : constraints;
    gravity = gravity == null ? GravityMode.NONE : gravity;
    tolerance = tolerance == null ? Meters.of(kDefaultLinearToleranceMeters) : tolerance;
    neutralMode = neutralMode == null ? NeutralMode.BRAKE : neutralMode;
  }

  /**
   * The library's defaults for a mechanism whose control fields the team never touched.
   *
   * <p>Gains are {@link Gains#UNTUNED}, which is a NaN {@code kP}: closed loop is refused on
   * hardware and the placeholder shows up on the boot checklist, rather than a plausible-looking
   * zero that silently does nothing.
   *
   * @return the default control config
   */
  public static ControlConfig defaults() {
    return new ControlConfig(
        ControlLocation.RIO_FULL,
        ControlLocationSource.DEFAULTED,
        Gains.UNTUNED,
        MotionConstraints.unconstrained(),
        false,
        GravityMode.NONE,
        Meters.of(kDefaultLinearToleranceMeters),
        Double.POSITIVE_INFINITY,
        kDefaultGoalDebounceSeconds,
        NeutralMode.BRAKE,
        kDefaultManualDeadband,
        kDefaultManualScale);
  }

  /**
   * A fresh builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The {@link ControlLocation} a leader motor gets when the team does not name one, together with
   * the sentence {@code describe()} prints to explain the choice.
   *
   * <p>The mapping is {@code design/01} §5.3: a TalonFX, TalonFXS, Spark or sim leader runs Motion
   * Magic on the device, so it defaults to {@link ControlLocation#ON_MOTOR_PROFILED}; a PWM motor
   * controller has no loop of its own, so it can only be {@link ControlLocation#RIO_FULL}.
   *
   * @param leader the leader motor spec, possibly null
   * @return the defaulted location
   */
  public static ControlLocation defaultLocationFor(MotorSpec leader) {
    return leader == null ? ControlLocation.RIO_FULL : leader.defaultControlLocation();
  }

  // -------------------------------------------------------------------------------------------
  // with*() copies — immutable value semantics, same discipline as core.spi.LogConfig
  // -------------------------------------------------------------------------------------------

  /**
   * A copy that runs the loop somewhere else, marked {@link ControlLocationSource#EXPLICIT}.
   *
   * @param value where the loop should run
   * @return a new config; this one is unchanged
   */
  public ControlConfig withLocation(ControlLocation value) {
    return new ControlConfig(
        value,
        ControlLocationSource.EXPLICIT,
        gains,
        constraints,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with a defaulted location, used by the mechanism builders when the team stayed silent.
   *
   * @param value the location derived from the leader motor
   * @return a new config; this one is unchanged
   */
  public ControlConfig withDefaultedLocation(ControlLocation value) {
    return new ControlConfig(
        value,
        ControlLocationSource.DEFAULTED,
        gains,
        constraints,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with different gains. This is the per-robot override that replaces a wall of ternaries.
   *
   * @param value the gains, in volts per SI unit
   * @return a new config; this one is unchanged
   */
  public ControlConfig withGains(Gains value) {
    return new ControlConfig(
        location,
        locationSource,
        value,
        constraints,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with different profile constraints.
   *
   * @param value the constraints, in user units per second and second squared
   * @return a new config; this one is unchanged
   */
  public ControlConfig withConstraints(MotionConstraints value) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        value,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy that does or does not use an exponential profile.
   *
   * @param value true to shape the profile from {@code kV} and {@code kA} instead of a cruise
   *     velocity
   * @return a new config; this one is unchanged
   */
  public ControlConfig withUseExpo(boolean value) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        constraints,
        value,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with a different gravity model.
   *
   * @param value the gravity model
   * @return a new config; this one is unchanged
   */
  public ControlConfig withGravity(GravityMode value) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        constraints,
        useExpo,
        value,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with a different arrival test.
   *
   * @param position the position tolerance, as a {@link Distance} or an {@link Angle}
   * @param velocityPerSecond the velocity gate in user units per second
   * @param debounceSeconds how long both must hold
   * @return a new config; this one is unchanged
   */
  public ControlConfig withTolerance(
      Measure<?> position, double velocityPerSecond, double debounceSeconds) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        constraints,
        useExpo,
        gravity,
        position,
        velocityPerSecond,
        debounceSeconds,
        neutralMode,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with a different neutral behaviour.
   *
   * @param value {@link NeutralMode#BRAKE} or {@link NeutralMode#COAST}
   * @return a new config; this one is unchanged
   */
  public ControlConfig withNeutralMode(NeutralMode value) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        constraints,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        value,
        manualDeadband,
        manualScale);
  }

  /**
   * A copy with a different manual-control feel.
   *
   * @param deadband the stick deadband, 0..1
   * @param scale the fraction of full output at full stick, 0..1
   * @return a new config; this one is unchanged
   */
  public ControlConfig withManualControl(double deadband, double scale) {
    return new ControlConfig(
        location,
        locationSource,
        gains,
        constraints,
        useExpo,
        gravity,
        tolerance,
        velocityTolerance,
        goalDebounceSeconds,
        neutralMode,
        deadband,
        scale);
  }

  // -------------------------------------------------------------------------------------------
  // Derived values
  // -------------------------------------------------------------------------------------------

  /**
   * The position tolerance in user units — metres for a distance, <b>degrees</b> for an angle.
   *
   * @return the tolerance, or NaN when it is neither a distance nor an angle
   */
  public double toleranceUser() {
    if (tolerance instanceof Distance distance) {
      return distance.in(Meters);
    }
    if (tolerance instanceof Angle angle) {
      return angle.in(Degrees);
    }
    return Double.NaN;
  }

  /**
   * The position tolerance in SI — metres or radians.
   *
   * @return the tolerance, or NaN when it is neither a distance nor an angle
   */
  public double toleranceSi() {
    if (tolerance instanceof Distance distance) {
      return distance.in(Meters);
    }
    if (tolerance instanceof Angle angle) {
      return angle.in(Radians);
    }
    return Double.NaN;
  }

  /**
   * Whether the tolerance is expressed as a linear measure.
   *
   * @return true when the tolerance is a {@link Distance}
   */
  public boolean toleranceIsLinear() {
    return tolerance instanceof Distance;
  }

  /**
   * Whether the loop needs gains at all.
   *
   * @return true — a control config always describes a closed loop; kept as a method so the boot
   *     dump and the tier-3 checklist can ask the question in one place
   */
  public boolean requiresGains() {
    return true;
  }

  /**
   * Everything wrong with this control config on its own.
   *
   * <p><b>Never throws, never returns null.</b> Checks that need the mechanism's geometry — is the
   * cruise velocity above free speed, is the tolerance narrower than one loop step — need
   * {@code MechanismUnits} and therefore live in {@link Validation}.
   *
   * @return the problems; empty when the config is fine
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    out.addAll(constraints.problems());

    if (Double.isNaN(toleranceUser())) {
      out.add(
          "control.tolerance is a "
              + tolerance.getClass().getSimpleName()
              + ", which is neither a Distance nor an Angle, so atGoal() can never be evaluated. "
              + "Fix: .tolerance(Inches.of(0.5), 0.05, 0.06) on a linear mechanism, or "
              + ".tolerance(Degrees.of(1.5), 5.0, 0.06) on a rotary one.");
    } else if (toleranceUser() <= 0.0) {
      out.add(
          "control.tolerance = "
              + toleranceUser()
              + ", which must be greater than zero. A tolerance of zero means the mechanism is "
              + "never observed to be at its goal, so every goTo() command runs forever and any "
              + "sequence that waits on it deadlocks. Fix: about half of the smallest move that "
              + "matters — Inches.of(0.5) on an elevator, Degrees.of(1.5) on an arm.");
    }

    if (Double.isNaN(velocityTolerance) || velocityTolerance <= 0.0) {
      out.add(
          "control.velocityTolerance = "
              + velocityTolerance
              + " user units/s, which must be greater than zero (or infinite to disable the "
              + "velocity gate). Without it a mechanism flying through its setpoint at full speed "
              + "reports atGoal() = true for one loop, and whatever was waiting on that fires "
              + "while the mechanism is still moving. Fix: about 3% of your cruise velocity.");
    }

    if (Double.isNaN(goalDebounceSeconds) || goalDebounceSeconds < 0.0) {
      out.add(
          "control.goalDebounceSeconds = "
              + goalDebounceSeconds
              + ", which must be zero or more. Fix: 0.06 s is three robot loops and is a good "
              + "default; zero is legal and means atGoal() latches on the first loop inside "
              + "tolerance.");
    }

    if (Double.isNaN(manualDeadband) || manualDeadband < 0.0 || manualDeadband >= 1.0) {
      out.add(
          "control.manualDeadband = "
              + manualDeadband
              + ", which must be in [0, 1). It is a fraction of full stick travel, not a "
              + "percentage and not a voltage. Fix: .manualControl(0.10, 0.30) — 10% deadband, "
              + "30% of full output at full stick.");
    }

    if (Double.isNaN(manualScale) || manualScale < 0.0 || manualScale > 1.0) {
      out.add(
          "control.manualScale = "
              + manualScale
              + ", which must be in [0, 1]. It is the fraction of full output a fully deflected "
              + "stick asks for. Fix: .manualControl(0.10, 0.30); start low, because manual "
              + "control is what a driver reaches for when something has already gone wrong.");
    }

    if (location == ControlLocation.RIO_FULL && useExpo) {
      out.add(
          "control.useExpo is true but control.location is RIO_FULL. An exponential profile is a "
              + "Motion Magic Expo feature of the motor controller; the roboRIO-side profile is "
              + "trapezoidal. Fix: either .controlLocation(ControlLocation.ON_MOTOR_PROFILED) so "
              + "the device shapes the profile, or .useExpo(false) and give "
              + "MotionConstraints.of(...) a cruise velocity and an acceleration.");
    }

    return List.copyOf(out);
  }

  /**
   * The control config as the boot dump prints it, including <b>why</b> the control location is
   * what it is.
   *
   * <p>The provenance line is the whole point: a rookie who never wrote {@code .controlLocation(...)}
   * still sees which loop is running and how to change it, and an expert who did write it sees the
   * consequence spelled out.
   *
   * @param unitLabel the mechanism's user unit, {@code "m"} or {@code "deg"}
   * @param domain the mechanism's SI domain, so gain units print as V/m or V/rad
   * @param leaderDeviceType the leader's hardware type, for the defaulting explanation
   * @return a multi-line block, newline-terminated
   */
  public String describe(String unitLabel, SiDomain domain, String leaderDeviceType) {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(512);
    sb.append("  ControlLocation     ")
        .append(locationSource.describe(location, locationReason(leaderDeviceType)))
        .append(nl);
    sb.append("  Gains               ").append(gains.describe(domain)).append(nl);
    sb.append("  Gravity             ")
        .append(gravity)
        .append(gravity == GravityMode.NONE ? " (no gravity term)" : " (kG is applied)")
        .append(nl);
    sb.append("  Constraints         ").append(constraints.describe(unitLabel)).append(nl);
    sb.append("  Profile shape       ")
        .append(
            useExpo
                ? "EXPONENTIAL (Motion Magic Expo — shaped entirely by measured kV and kA)"
                : "TRAPEZOIDAL (shaped by the cruise velocity and acceleration above)")
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  atGoal              |error| <= %.4f %s AND |velocity| <= %.4f %s/s, held %.3f s",
                toleranceUser(),
                unitLabel,
                velocityTolerance,
                unitLabel,
                goalDebounceSeconds))
        .append(nl);
    sb.append("  Neutral             ").append(neutralMode).append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Manual control      deadband %.2f, scale %.2f (full stick = %.0f%% output)",
                manualDeadband,
                manualScale,
                manualScale * 100.0))
        .append(nl);
    return sb.toString();
  }

  private String locationReason(String leaderDeviceType) {
    if (locationSource == ControlLocationSource.EXPLICIT) {
      return "you called .controlLocation(" + location + ")";
    }
    return leaderDeviceType == null || leaderDeviceType.isBlank()
        ? "you did not name one and this is the library default for your leader motor"
        : "your leader is a " + leaderDeviceType;
  }

  // -------------------------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------------------------

  /**
   * Builds a {@link ControlConfig}.
   *
   * <p>Every setter is optional. {@link #build()} substitutes the documented defaults for whatever
   * was not set and <b>never throws</b>; anything genuinely wrong becomes a {@link ConfigError} on
   * the owning mechanism config.
   *
   * <p>Mechanism builders ({@link PositionConfig.Builder} and friends) expose the same setters
   * directly and delegate to one of these, so a team never has to nest a builder inside a builder.
   */
  public static final class Builder {
    private ControlLocation m_location = ControlLocation.RIO_FULL;
    private ControlLocationSource m_locationSource = ControlLocationSource.DEFAULTED;
    private Gains m_gains = Gains.UNTUNED;
    private MotionConstraints m_constraints = MotionConstraints.unconstrained();
    private boolean m_useExpo;
    private GravityMode m_gravity = GravityMode.NONE;
    private Measure<?> m_tolerance = Meters.of(kDefaultLinearToleranceMeters);
    private double m_velocityTolerance = Double.POSITIVE_INFINITY;
    private double m_goalDebounceSeconds = kDefaultGoalDebounceSeconds;
    private NeutralMode m_neutralMode = NeutralMode.BRAKE;
    private double m_manualDeadband = kDefaultManualDeadband;
    private double m_manualScale = kDefaultManualScale;

    private Builder() {}

    /**
     * Runs the loop somewhere other than the library's default for your leader motor.
     *
     * @param value where the profile and the feedback loop run
     * @return this builder
     */
    public Builder location(ControlLocation value) {
      m_location = value;
      m_locationSource = ControlLocationSource.EXPLICIT;
      return this;
    }

    /**
     * Sets the location without claiming the team chose it. Used by the mechanism builders.
     *
     * @param value the location derived from the leader motor
     * @return this builder
     */
    Builder defaultedLocation(ControlLocation value) {
      if (m_locationSource != ControlLocationSource.EXPLICIT) {
        m_location = value;
        m_locationSource = ControlLocationSource.DEFAULTED;
      }
      return this;
    }

    /**
     * Sets the loop gains, in volts per SI unit.
     *
     * @param value the gains
     * @return this builder
     */
    public Builder gains(Gains value) {
      m_gains = value;
      return this;
    }

    /**
     * Sets the profile constraints, in user units per second and second squared.
     *
     * @param value the constraints
     * @return this builder
     */
    public Builder constraints(MotionConstraints value) {
      m_constraints = value;
      return this;
    }

    /**
     * Shapes the profile from measured {@code kV} and {@code kA} instead of a cruise velocity.
     *
     * @param value true to use an exponential profile
     * @return this builder
     */
    public Builder useExpo(boolean value) {
      m_useExpo = value;
      return this;
    }

    /**
     * Overrides the gravity model the axis implies.
     *
     * @param value the gravity model
     * @return this builder
     */
    public Builder gravity(GravityMode value) {
      m_gravity = value;
      return this;
    }

    /**
     * Sets the whole arrival test at once, because its three parts only make sense together.
     *
     * @param position the position tolerance
     * @param velocityPerSecond the velocity gate in user units per second
     * @param debounceSeconds how long both must hold
     * @return this builder
     */
    public Builder tolerance(
        Measure<?> position, double velocityPerSecond, double debounceSeconds) {
      m_tolerance = position;
      m_velocityTolerance = velocityPerSecond;
      m_goalDebounceSeconds = debounceSeconds;
      return this;
    }

    /**
     * Sets what the motor does when nothing is commanding it.
     *
     * @param value {@link NeutralMode#BRAKE} or {@link NeutralMode#COAST}
     * @return this builder
     */
    public Builder neutralMode(NeutralMode value) {
      m_neutralMode = value;
      return this;
    }

    /**
     * Sets how a driver's stick feels.
     *
     * @param deadband the stick deadband, 0..1
     * @param scale the fraction of full output at full stick, 0..1
     * @return this builder
     */
    public Builder manualControl(double deadband, double scale) {
      m_manualDeadband = deadband;
      m_manualScale = scale;
      return this;
    }

    /**
     * Builds the config. Never throws.
     *
     * @return the control config
     */
    public ControlConfig build() {
      return new ControlConfig(
          m_location,
          m_locationSource,
          m_gains,
          m_constraints,
          m_useExpo,
          m_gravity,
          m_tolerance,
          m_velocityTolerance,
          m_goalDebounceSeconds,
          m_neutralMode,
          m_manualDeadband,
          m_manualScale);
    }

    /** @return whether the team explicitly chose the control location */
    boolean locationWasExplicit() {
      return m_locationSource == ControlLocationSource.EXPLICIT;
    }

    /** @return whether the team ever called {@link #tolerance(Measure, double, double)} */
    boolean toleranceWasSet() {
      return Double.isFinite(m_velocityTolerance);
    }

    /**
     * Substitutes a domain-appropriate default tolerance when the team never set one.
     *
     * @param linear whether the mechanism is linear
     */
    void defaultToleranceFor(boolean linear) {
      if (toleranceWasSet()) {
        return;
      }
      m_tolerance =
          linear
              ? Meters.of(kDefaultLinearToleranceMeters)
              : Degrees.of(kDefaultRotaryToleranceDegrees);
    }
  }
}

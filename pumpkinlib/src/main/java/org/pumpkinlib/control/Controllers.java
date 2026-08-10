package org.pumpkinlib.control;

import edu.wpi.first.math.controller.ArmFeedforward;
import edu.wpi.first.math.controller.ElevatorFeedforward;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.trajectory.ExponentialProfile;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import java.util.Objects;

/**
 * The factory that turns canonical {@link Gains} into wpimath control objects — and the only place
 * in PumpkinLib that calls a WPILib feedforward.
 *
 * <p>Team code never builds these by hand, for two reasons that are both API-truth problems rather
 * than style preferences:
 *
 * <ol>
 *   <li><b>WPILib has no common supertype for its three feedforward classes.</b> {@code
 *       ArmFeedforward}, {@code ElevatorFeedforward} and {@code SimpleMotorFeedforward} are
 *       unrelated types with <em>different method signatures</em> — only the arm one takes a
 *       position at all. Any code that wants to dispatch on gravity model has to write the sealed
 *       hierarchy below; writing it once here is the difference between a library and a snippet.
 *   <li><b>The obvious method is the wrong one.</b> The single-argument {@code calculate(velocity)}
 *       forms are <em>steady-state</em> feedforward. A profiled loop needs the discrete
 *       plant-inversion form, {@code calculateWithVelocities(current, next)}, which asks for this
 *       cycle's and next cycle's profile velocity. The older {@code calculate(position, velocity,
 *       acceleration, dt)} overloads are deprecated for removal.
 * </ol>
 *
 * <p>Everything here is <b>SI in, volts out</b>: positions in metres or radians, velocities in m/s
 * or rad/s. Constraints arrive already converted by {@code MechanismUnits}; this class takes plain
 * primitives and never names {@code org.pumpkinlib.config}, which sits <em>above</em> this package
 * in the dependency graph.
 */
public final class Controllers {

  private Controllers() {}

  /**
   * A gravity-model-agnostic feedforward, sealed over the three WPILib archetypes.
   *
   * <p>Live-mutable on purpose: retuning calls {@link #update(Gains)}, which pushes the new
   * coefficients into the existing WPILib object rather than allocating a new one. A wizard slider
   * being dragged writes gains at up to 10 Hz on the loop that is also running the mechanism, and an
   * allocation per write is a garbage-collection pause during a supervised voltage command.
   */
  public sealed interface Feedforward permits SimpleFf, ElevatorFf, ArmFf {

    /**
     * The discrete plant-inversion feedforward voltage for the current loop iteration.
     *
     * @param positionSi the mechanism position in radians <b>measured from horizontal</b> for a
     *     cosine-gravity axis; ignored entirely by the other two implementations
     * @param velocitySi this cycle's profile velocity setpoint, m/s or rad/s
     * @param nextVelocitySi next cycle's profile velocity setpoint, m/s or rad/s — one {@code
     *     Clock.dt()} later
     * @return the feedforward contribution, in volts
     */
    double calculate(double positionSi, double velocitySi, double nextVelocitySi);

    /**
     * Push new coefficients into the underlying WPILib object without reallocating it.
     *
     * <p>Only the four feedforward terms are read; kP, kI and kD belong to the feedback controller
     * and are ignored here.
     *
     * @param gains the new gains, in volts per SI unit
     */
    void update(Gains gains);

    /**
     * The gains this feedforward is currently running.
     *
     * <p>Kept so the boot dump, the tuning UI and the sanity checks can read back what is actually
     * installed rather than what somebody meant to install — the two diverge the first time an
     * {@link #update(Gains)} call is skipped by a change gate.
     *
     * @return the most recently applied gains
     */
    Gains gains();

    /**
     * Which gravity model this instance implements.
     *
     * @return the mode this feedforward was built for
     */
    GravityMode gravityMode();
  }

  /** No gravity term: flywheels, turrets, rollers, drive motors. */
  static final class SimpleFf implements Feedforward {
    private final SimpleMotorFeedforward m_ff;
    private Gains m_gains;

    SimpleFf(Gains gains, double dtSeconds) {
      m_ff = new SimpleMotorFeedforward(gains.kS(), gains.kV(), gains.kA(), dtSeconds);
      m_gains = gains;
    }

    @Override
    public double calculate(double positionSi, double velocitySi, double nextVelocitySi) {
      return m_ff.calculateWithVelocities(velocitySi, nextVelocitySi);
    }

    @Override
    public void update(Gains gains) {
      m_ff.setKs(gains.kS());
      m_ff.setKv(gains.kV());
      m_ff.setKa(gains.kA());
      m_gains = gains;
    }

    @Override
    public Gains gains() {
      return m_gains;
    }

    @Override
    public GravityMode gravityMode() {
      return GravityMode.NONE;
    }
  }

  /** Constant gravity term: elevators, cascades, linear slides. */
  static final class ElevatorFf implements Feedforward {
    private final ElevatorFeedforward m_ff;
    private Gains m_gains;

    ElevatorFf(Gains gains, double dtSeconds) {
      m_ff = new ElevatorFeedforward(gains.kS(), gains.kG(), gains.kV(), gains.kA(), dtSeconds);
      m_gains = gains;
    }

    @Override
    public double calculate(double positionSi, double velocitySi, double nextVelocitySi) {
      return m_ff.calculateWithVelocities(velocitySi, nextVelocitySi);
    }

    @Override
    public void update(Gains gains) {
      m_ff.setKs(gains.kS());
      m_ff.setKg(gains.kG());
      m_ff.setKv(gains.kV());
      m_ff.setKa(gains.kA());
      m_gains = gains;
    }

    @Override
    public Gains gains() {
      return m_gains;
    }

    @Override
    public GravityMode gravityMode() {
      return GravityMode.CONSTANT;
    }
  }

  /**
   * Cosine gravity term: arms, pivots, wrists, hoods.
   *
   * <p>WPILib's contract is explicit that the angle is measured from the horizontal — <i>"if the
   * provided angle is 0, the arm should be parallel to the floor"</i> — which is why the mechanism
   * layer subtracts {@code horizontalReferenceSi()} before calling. Passing the raw sensor position
   * makes the cosine wrong at <em>every</em> angle for any arm whose zero is not horizontal, which
   * is the single most common arm-tuning failure in the wild.
   */
  static final class ArmFf implements Feedforward {
    private final ArmFeedforward m_ff;
    private Gains m_gains;

    ArmFf(Gains gains, double dtSeconds) {
      m_ff = new ArmFeedforward(gains.kS(), gains.kG(), gains.kV(), gains.kA(), dtSeconds);
      m_gains = gains;
    }

    @Override
    public double calculate(double positionRadFromHorizontal, double velocitySi, double nextVelocitySi) {
      return m_ff.calculateWithVelocities(positionRadFromHorizontal, velocitySi, nextVelocitySi);
    }

    @Override
    public void update(Gains gains) {
      m_ff.setKs(gains.kS());
      m_ff.setKg(gains.kG());
      m_ff.setKv(gains.kV());
      m_ff.setKa(gains.kA());
      m_gains = gains;
    }

    @Override
    public Gains gains() {
      return m_gains;
    }

    @Override
    public GravityMode gravityMode() {
      return GravityMode.COSINE;
    }
  }

  /**
   * Build the feedforward for a gravity model.
   *
   * <p>Dispatch is on {@link GravityMode}, not on {@link MechanismArchetype}: how gravity loads a
   * mechanism is a property of its geometry, and {@code Axis.gravity()} already derived it. An
   * archetype is a <em>tuning recipe</em>, and two archetypes can share a gravity model.
   *
   * @param gravity which gravity model applies
   * @param siGains the gains, in volts per SI unit
   * @param dtSeconds the discretisation timestep — {@code Clock.dt()}, never a hardcoded 0.02
   * @return a live-mutable feedforward for that model
   * @throws NullPointerException if {@code gravity} or {@code siGains} is null
   */
  public static Feedforward feedforward(GravityMode gravity, Gains siGains, double dtSeconds) {
    Objects.requireNonNull(gravity, "gravity");
    Objects.requireNonNull(siGains, "siGains");
    return switch (gravity) {
      case COSINE -> new ArmFf(siGains, dtSeconds);
      case CONSTANT -> new ElevatorFf(siGains, dtSeconds);
      case NONE -> new SimpleFf(siGains, dtSeconds);
    };
  }

  /**
   * Build an exponential motion profile straight from measured kV and kA.
   *
   * <p>This is the single reason a student never has to guess a maximum velocity again. A trapezoid
   * profile asks for two numbers nobody knows on day one; an exponential profile derives the same
   * shape from the two numbers the characterisation routine just <em>measured</em>, and the result
   * is a profile the mechanism can actually follow instead of one it saturates against.
   *
   * @param gains gains whose kV and kA have been characterised, in volts per SI unit
   * @param maxInputVolts the voltage the profile is allowed to plan for — typically a little under
   *     battery nominal, so the profile stays followable on a sagging battery
   * @return the profile
   * @throws NullPointerException if {@code gains} is null
   */
  public static ExponentialProfile exponentialProfile(Gains gains, double maxInputVolts) {
    Objects.requireNonNull(gains, "gains");
    return new ExponentialProfile(
        ExponentialProfile.Constraints.fromCharacteristics(maxInputVolts, gains.kV(), gains.kA()));
  }

  /**
   * Build a trapezoidal motion profile from constraints that are <b>already in SI</b>.
   *
   * <p>The conversion from a team's user units happens in {@code MechanismUnits}, above this
   * package. Taking primitives here rather than a config object is what keeps {@code
   * org.pumpkinlib.control} beneath {@code org.pumpkinlib.config} in the dependency graph, so a team
   * with hand-rolled subsystems can use this class without adopting the config layer.
   *
   * @param maxVelocitySi cruise velocity, m/s or rad/s
   * @param maxAccelerationSi acceleration limit, m/s^2 or rad/s^2
   * @return the profile
   */
  public static TrapezoidProfile trapezoidProfile(double maxVelocitySi, double maxAccelerationSi) {
    return new TrapezoidProfile(
        new TrapezoidProfile.Constraints(maxVelocitySi, maxAccelerationSi));
  }

  /**
   * Build the feedback controller.
   *
   * <p>Continuous input is enabled automatically for a continuous axis, over {@code [-PI, PI]} — the
   * SI domain of a continuous axis is always radians, so there is no other sensible wrap range and
   * nothing for a team to get wrong.
   *
   * <p>{@code toleranceSi} and {@code iZone} arrive as primitives from {@code ControlConfig}: they
   * are policy, and D1a keeps policy off {@link Gains}. The integrator's clamp is applied by the
   * mechanism layer alongside {@code iMaxVolts}, which is supplied in the same call that enables kI
   * at all — integral action with no clamp is how arms slam.
   *
   * @param gains the gains, in volts per SI unit
   * @param continuous true for a continuously-rotating axis such as a swerve steer module
   * @param toleranceSi the at-setpoint tolerance, in metres or radians
   * @param iZone the error band inside which the integrator accumulates; ignored when kI is zero
   * @param dtSeconds the controller period — {@code Clock.dt()}
   * @return a configured {@code PIDController}
   * @throws NullPointerException if {@code gains} is null
   */
  public static PIDController pid(
      Gains gains, boolean continuous, double toleranceSi, double iZone, double dtSeconds) {
    Objects.requireNonNull(gains, "gains");
    PIDController controller =
        new PIDController(gains.kP(), gains.kI(), gains.kD(), dtSeconds);
    controller.setTolerance(toleranceSi);
    if (gains.kI() != 0.0) {
      controller.setIZone(iZone);
    }
    if (continuous) {
      controller.enableContinuousInput(-Math.PI, Math.PI);
    }
    return controller;
  }
}

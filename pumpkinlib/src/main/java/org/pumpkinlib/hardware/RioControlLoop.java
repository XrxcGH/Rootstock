package org.pumpkinlib.hardware;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.Controllers;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The one implementation of {@link ControlLocation#RIO_FULL}: profile, feedback and feedforward on
 * the robot controller, entirely in SI, producing volts.
 *
 * <p><b>One implementation, used by everybody.</b> Every backend that has no on-device loop — a PWM
 * speed controller, the pure-software simulation backend, and any vendor backend a team deliberately
 * downgrades — runs <i>this</i> object, not a private copy. A second copy is how two mechanisms on
 * the same robot end up following different profiles from the same declared constraints.
 *
 * <h2>Everything here is SI</h2>
 *
 * <p>Callers speak the seam's language — output-shaft rotations and rotations per second — and this
 * class converts once, on the way in, by multiplying by {@link
 * MechanismUnits#siPerOutputRotation()}. That single multiply is the mechanism's geometry, and doing
 * it in exactly one place is why {@link Gains} can be volts-per-SI and mean the same thing on every
 * backend.
 *
 * <h2>Why plant-inversion feedforward and not {@code calculate(velocity)}</h2>
 *
 * <p>The obvious single-argument WPILib feedforward call is the <i>steady-state</i> form. A profiled
 * loop needs this cycle's and next cycle's profile velocity, which is what {@link
 * Controllers.Feedforward#calculate(double, double, double)} takes, so the profile is asked for two
 * points per loop rather than one.
 *
 * <h2>Untuned gains refuse rather than guess</h2>
 *
 * <p>{@link Gains#UNTUNED} has a NaN kP. Feeding that to a PID controller produces a NaN voltage,
 * which a motor controller reads as zero and a plot reads as a gap. This class returns a hard {@code
 * 0.0} instead and reports it through {@link #isRefusing()}, so the mechanism layer can raise the
 * "run the tuning wizard" alert rather than the student debugging an invisible NaN.
 */
public final class RioControlLoop {

  /**
   * The finite stand-in for an infinite constraint.
   *
   * <p>{@code MotionConstraints.unconstrained()} is legitimately infinite, and an infinite
   * trapezoid constraint makes {@code TrapezoidProfile} return NaN rather than "instantly at the
   * goal". One large finite number is the honest translation: no mechanism reaches 1e6 SI units per
   * second, and the profile stays arithmetically well-defined.
   */
  public static final double kEffectivelyUnbounded = 1.0e6;

  private final MechanismUnits m_units;
  private final ControlConfig m_control;
  private final double m_siPerRot;
  private final double m_horizontalReferenceSi;
  private final PIDController m_positionPid;
  private final PIDController m_velocityPid;
  private final Controllers.Feedforward m_feedforward;

  private Gains m_gains;
  private MotionConstraints m_constraints;
  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_setpoint = new TrapezoidProfile.State();
  private boolean m_seeded;

  /**
   * Builds the loop from the mechanism's geometry and control declaration.
   *
   * @param units the mechanism's unit conversion object — the single source of the output-rotation
   *     to SI ratio
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param dtSeconds the discretisation timestep; pass {@code Clock.dt()}, never a hardcoded 0.02
   */
  public RioControlLoop(MechanismUnits units, ControlConfig control, double dtSeconds) {
    m_units = Objects.requireNonNull(units, "RioControlLoop: units must not be null");
    m_control = Objects.requireNonNull(control, "RioControlLoop: control must not be null");
    m_siPerRot = units.siPerOutputRotation();
    m_horizontalReferenceSi = units.horizontalReferenceSi();
    m_gains = control.gains();
    m_constraints = control.constraints();
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : 0.02;
    m_positionPid =
        Controllers.pid(
            m_gains, units.axis().isContinuous(), control.toleranceSi(), 0.0, dt);
    // velocityTolerance is declared in USER units per second; the loop is SI, so it converts here
    // like everything else that crosses this boundary.
    m_velocityPid =
        Controllers.pid(m_gains, false, units.toSiPerSec(control.velocityTolerance()), 0.0, dt);
    m_feedforward = Controllers.feedforward(control.gravity(), m_gains, dt);
    m_profile = profileFor(m_constraints);
  }

  /**
   * Replace the gains without reallocating the controllers.
   *
   * <p>Safe to call every loop and cheap enough to call at the 10 Hz a dragged tuning slider
   * produces: the coefficients are pushed into the existing WPILib objects, so there is no
   * allocation and therefore no garbage-collection pause during a supervised voltage command.
   *
   * @param siGains the new gains, volts per SI unit
   */
  public void applyGains(Gains siGains) {
    if (siGains == null || siGains.equals(m_gains)) {
      return;
    }
    m_gains = siGains;
    m_positionPid.setPID(siGains.kP(), siGains.kI(), siGains.kD());
    m_velocityPid.setPID(siGains.kP(), siGains.kI(), siGains.kD());
    m_feedforward.update(siGains);
  }

  /**
   * Replace the profile constraints, given in USER units per second and per second squared.
   *
   * <p>Converted to SI here, through {@link MotionConstraints#maxVelocitySi(MechanismUnits)} and its
   * sibling, so that a caller cannot convert two of the three limits and pass the third through raw
   * — a mistake that is wrong by a factor of 360 on every rotary mechanism and looks identical to
   * the two correct lines above it.
   *
   * @param constraints the new constraints; null is ignored
   */
  public void applyConstraints(MotionConstraints constraints) {
    if (constraints == null || constraints.equals(m_constraints)) {
      return;
    }
    m_constraints = constraints;
    m_profile = profileFor(constraints);
  }

  /**
   * Re-anchor the profile to where the mechanism actually is.
   *
   * <p>Called on the first closed-loop command after any period of open-loop or neutral control. A
   * profile that resumes from a stale internal setpoint commands a step, and a step on an elevator
   * is a bang.
   *
   * @param measuredOutputRotations the mechanism's current position, output-shaft rotations
   * @param measuredOutputRps the mechanism's current velocity, output-shaft rotations per second
   */
  public void reset(double measuredOutputRotations, double measuredOutputRps) {
    double posSi = measuredOutputRotations * m_siPerRot;
    double velSi = measuredOutputRps * m_siPerRot;
    m_setpoint =
        new TrapezoidProfile.State(
            Double.isFinite(posSi) ? posSi : 0.0, Double.isFinite(velSi) ? velSi : 0.0);
    m_positionPid.reset();
    m_velocityPid.reset();
    m_seeded = true;
  }

  /**
   * One loop iteration of the profiled position loop.
   *
   * @param measuredRot the measured position, output-shaft rotations
   * @param measuredRps the measured velocity, output-shaft rotations per second
   * @param goalRot the goal position, output-shaft rotations
   * @param goalRps the goal velocity at that position — zero in the common case, non-zero for a
   *     field-locked axis; this is the term the seam promises never to drop
   * @param arbFeedforwardVolts an arbitrary volt term added on top
   * @param dtSeconds the elapsed time since the last call; pass {@code Clock.dt()}
   * @return the commanded voltage, or {@code 0.0} when the gains are the untuned placeholder
   */
  public double positionVolts(
      double measuredRot,
      double measuredRps,
      double goalRot,
      double goalRps,
      double arbFeedforwardVolts,
      double dtSeconds) {
    if (!m_seeded) {
      reset(measuredRot, measuredRps);
    }
    if (m_gains.isUntuned()) {
      return 0.0;
    }
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : 0.02;
    double measuredSi = measuredRot * m_siPerRot;
    TrapezoidProfile.State goal =
        new TrapezoidProfile.State(goalRot * m_siPerRot, goalRps * m_siPerRot);
    TrapezoidProfile.State now = m_profile.calculate(dt, m_setpoint, goal);
    TrapezoidProfile.State next = m_profile.calculate(2.0 * dt, m_setpoint, goal);
    m_setpoint = now;

    double ff =
        m_feedforward.calculate(now.position - m_horizontalReferenceSi, now.velocity, next.velocity);
    double fb =
        Double.isFinite(measuredSi) ? m_positionPid.calculate(measuredSi, now.position) : 0.0;
    return sanitize(ff + fb + arbFeedforwardVolts);
  }

  /**
   * One loop iteration of the velocity loop.
   *
   * <p>No profile: a velocity mechanism's "profile" is its own acceleration limit, which arrives as
   * {@code goalRps2} and is used only to look one step ahead for the plant-inversion feedforward.
   *
   * @param measuredRps the measured velocity, output-shaft rotations per second
   * @param goalRps the goal velocity, output-shaft rotations per second
   * @param goalRps2 the goal acceleration at that instant, output rotations per second squared
   * @param arbFeedforwardVolts an arbitrary volt term added on top
   * @param dtSeconds the elapsed time since the last call; pass {@code Clock.dt()}
   * @return the commanded voltage, or {@code 0.0} when the gains are the untuned placeholder
   */
  public double velocityVolts(
      double measuredRps,
      double goalRps,
      double goalRps2,
      double arbFeedforwardVolts,
      double dtSeconds) {
    if (m_gains.isUntuned()) {
      return 0.0;
    }
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : 0.02;
    double measuredVelSi = measuredRps * m_siPerRot;
    double goalVelSi = goalRps * m_siPerRot;
    double goalAccelSi = goalRps2 * m_siPerRot;
    m_setpoint = new TrapezoidProfile.State(m_setpoint.position, goalVelSi);
    m_seeded = true;

    double ff = m_feedforward.calculate(0.0, goalVelSi, goalVelSi + goalAccelSi * dt);
    double fb =
        Double.isFinite(measuredVelSi) ? m_velocityPid.calculate(measuredVelSi, goalVelSi) : 0.0;
    return sanitize(ff + fb + arbFeedforwardVolts);
  }

  /**
   * The profile's current position reference, in output-shaft rotations.
   *
   * <p>This is what fills {@link MotorInputs#closedLoopReferenceRot} on a roboRIO-side backend, so
   * that "the setpoint did not land" and "the gains are wrong" stay distinguishable on the same plot
   * they are on for a device-side loop.
   *
   * @return the reference position, output-shaft rotations
   */
  public double referenceRot() {
    return m_siPerRot == 0.0 ? Double.NaN : m_setpoint.position / m_siPerRot;
  }

  /**
   * The profile's current velocity reference, in output-shaft rotations per second.
   *
   * @return the reference velocity, output rotations per second
   */
  public double referenceRps() {
    return m_siPerRot == 0.0 ? Double.NaN : m_setpoint.velocity / m_siPerRot;
  }

  /**
   * Whether the loop is refusing to command anything because the gains are the untuned placeholder.
   *
   * @return true when {@link Gains#isUntuned()} holds and every call returns zero volts
   */
  public boolean isRefusing() {
    return m_gains.isUntuned();
  }

  /**
   * Whether the position loop is within its declared tolerance of the profile reference.
   *
   * @return true when the underlying controller reports at-setpoint
   */
  public boolean atSetpoint() {
    return m_positionPid.atSetpoint();
  }

  /**
   * The gains currently installed, which is not necessarily the gains somebody meant to install.
   *
   * @return the live gains
   */
  public Gains gains() {
    return m_gains;
  }

  /**
   * The constraints currently installed, in user units.
   *
   * @return the live constraints
   */
  public MotionConstraints constraints() {
    return m_constraints;
  }

  /**
   * The loop as the boot dump prints it: what runs where, in which units, with which numbers.
   *
   * @return a human-readable description
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "roboRIO loop in %s: %s; %s; %.6f %s per output rotation%s",
        m_units.siLabel(),
        m_gains.describe(m_units.siDomain()),
        m_constraints.describe(m_units.unitLabel()),
        m_siPerRot,
        m_units.siLabel(),
        m_gains.isUntuned() ? " -- GAINS ARE UNTUNED, this loop commands 0 V" : "");
  }

  /**
   * The control config this loop was built from, for a backend that needs the gravity model or the
   * neutral mode without keeping its own copy.
   *
   * @return the control config
   */
  public ControlConfig control() {
    return m_control;
  }

  private TrapezoidProfile profileFor(MotionConstraints constraints) {
    double v = finite(constraints.maxVelocitySi(m_units));
    double a = finite(constraints.maxAccelerationSi(m_units));
    return Controllers.trapezoidProfile(v, a);
  }

  private static double finite(double value) {
    if (Double.isNaN(value) || value <= 0.0) {
      return kEffectivelyUnbounded;
    }
    return Double.isInfinite(value) ? kEffectivelyUnbounded : value;
  }

  private static double sanitize(double volts) {
    return Double.isFinite(volts) ? volts : 0.0;
  }
}

package org.rootstock.control;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.List;
import java.util.Locale;
import org.rootstock.pure.units.Reduction;

/**
 * What we believe about a mechanism's physics <em>before</em> anyone measures it.
 *
 * <p>Used for exactly three things, and never in place of a measurement:
 *
 * <ol>
 *   <li>sanity-bounding a fitted gain, so a statistically excellent but physically absurd fit is
 *       caught rather than installed;
 *   <li>building the simulation plant, so a mechanism moves in sim before it exists in metal;
 *   <li>deriving a first-guess kV/kA/kG — which is what {@link Gains#UNTUNED} resolves to in
 *       simulation.
 * </ol>
 *
 * <p><b>The gearbox is a {@link Reduction}, not a raw double</b> (D7): positive-only at the type
 * level, self-describing, and cross-checked at registration against the mechanism's own reduction.
 * A prior that disagrees with the real reduction makes every sanity bound wrong in the same
 * direction, so the tuner confidently rejects correct fits and blames the team's hardware. One of
 * the user's own repositories carries {@code TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0} with seven
 * downstream conversions relying on double sign cancellation; {@code Reduction} makes that
 * unrepresentable rather than merely detectable.
 *
 * <p><b>The motor must be constructed with the real motor count.</b> {@code
 * DCMotor.getKrakenX60Foc(2)} is a two-motor gearbox, not one motor used twice. Stall torque and
 * stall current both scale with the count, so {@code KtNMPerAmp} is unchanged while {@code rOhms} is
 * divided by the count — meaning {@code Kt/R} already carries the factor. Dividing by the motor
 * count again, as earlier revisions did, makes the gravity prior n times too low, which puts the
 * bisection bracket <em>below</em> the true value on every multi-motor mechanism and reports a
 * hardware fault on perfectly healthy hardware.
 *
 * @param motor the motor curve, constructed with the <b>real</b> motor count
 * @param reduction rotor rotations per mechanism (or drum) rotation; always positive
 * @param massKg moving mass for a linear mechanism, or the arm mass for a cosine-gravity one; {@link
 *     Double#NaN} when not applicable
 * @param moiKgM2 moment of inertia for a rotating mechanism; {@link Double#NaN} when not applicable.
 *     This component also selects the domain: NaN means the priors are computed for a linear axis
 * @param radiusMetres effective radius for a linear mechanism, wheel radius for a drive, or
 *     centre-of-mass length for an arm; {@link Double#NaN} otherwise
 * @param nominalVolts the battery voltage the priors assume, usually 12.0
 */
public record PlantPrior(
    DCMotor motor,
    Reduction reduction,
    double massKg,
    double moiKgM2,
    double radiusMetres,
    double nominalVolts) {

  /** Standard gravity, m/s^2. Named so the derivations below read as physics, not as magic. */
  private static final double kStandardGravity = 9.80665;

  /**
   * A linear mechanism lifted against constant gravity: elevator, cascade, telescope, climber.
   *
   * @param motor the motor curve, built with the real motor count
   * @param reduction rotor rotations per drum rotation
   * @param massKg the total moving mass, including the carriage and anything it carries
   * @param effectiveRadiusMetres the radius that converts drum rotation to travel — for a
   *     multi-stage rigging this is the <em>effective</em> radius, already multiplied by the stage
   *     count
   * @return the prior
   */
  public static PlantPrior elevator(
      DCMotor motor, Reduction reduction, double massKg, double effectiveRadiusMetres) {
    return new PlantPrior(motor, reduction, massKg, Double.NaN, effectiveRadiusMetres, 12.0);
  }

  /**
   * A rotating mechanism loaded by cosine gravity: arm, pivot, wrist, hood.
   *
   * <p>Takes both an inertia and a mass because it needs both: the inertia sets kA, and the mass
   * times the centre-of-mass length sets kG. Supplying one and not the other silently disables half
   * the prior.
   *
   * @param motor the motor curve, built with the real motor count
   * @param reduction rotor rotations per arm rotation
   * @param moiKgM2 moment of inertia about the pivot
   * @param comLengthMetres distance from the pivot to the centre of mass
   * @param massKg the mass acting at that centre of mass
   * @return the prior
   */
  public static PlantPrior arm(
      DCMotor motor,
      Reduction reduction,
      double moiKgM2,
      double comLengthMetres,
      double massKg) {
    return new PlantPrior(motor, reduction, massKg, moiKgM2, comLengthMetres, 12.0);
  }

  /**
   * A gravity-free rotating inertia: flywheel, turret, steer axis.
   *
   * <p>The motor argument is required even though a flywheel prior is "just" an inertia. A prior
   * with no motor curve cannot produce kV, kA or a free-speed ceiling, which are three of the five
   * things a prior exists to produce.
   *
   * @param motor the motor curve, built with the real motor count
   * @param reduction rotor rotations per mechanism rotation
   * @param moiKgM2 moment of inertia about the axis of rotation
   * @return the prior
   */
  public static PlantPrior flywheel(DCMotor motor, Reduction reduction, double moiKgM2) {
    return new PlantPrior(motor, reduction, Double.NaN, moiKgM2, Double.NaN, 12.0);
  }

  /**
   * Collect every problem with this prior. Pure, non-throwing, and allocation-free when the prior is
   * usable.
   *
   * <p>What survives to be checked here is only the residue: {@link Reduction} is positive-only at
   * the type level, so a negative gear ratio is unrepresentable long before validation runs. Making
   * the residual case a collected message rather than a throw is what keeps the robot booting so the
   * student can read it.
   *
   * @param owner the mechanism name, so the message names the thing the student edits
   * @return an empty list when the prior is usable, otherwise one message per problem
   */
  public List<String> validate(String owner) {
    String who = owner == null ? "this mechanism" : owner;
    if (motor == null) {
      return List.of(
          who
              + ": plantPrior.motor was null, and it must be a DCMotor built with the real motor "
              + "count. Every prior Rootstock computes is read off the motor curve; without it "
              + "the tuner cannot bracket the gravity search or sanity-bound a fit. "
              + "Fix: pass DCMotor.getKrakenX60Foc(2) for a two-Kraken gearbox — the count is the "
              + "number of motors on this mechanism, not the number of mechanisms.");
    }
    if (reduction == null || !(reduction.rotorPerOutput() > 0)) {
      return List.of(
          String.format(
              Locale.ROOT,
              "%s: plantPrior.reduction was %s, and it must be greater than zero. A reduction is "
                  + "how many times the MOTOR turns for one turn of the OUTPUT. "
                  + "Fix: encode direction with the invert flag on the device, never with a "
                  + "negative ratio — a negative ratio produces a negative kV, which is physically "
                  + "meaningless, and then a kP that drives the mechanism away from its setpoint.",
              who,
              reduction == null ? "null" : String.valueOf(reduction.rotorPerOutput())));
    }
    return List.of();
  }

  /**
   * True when the priors below are computed for a linear axis (metres) rather than a rotary one
   * (radians).
   *
   * <p>Decided by {@code moiKgM2}: a mechanism described by a mass and a radius but no inertia is a
   * linear one. This is why {@link #elevator} leaves the inertia NaN and {@link #arm} does not.
   *
   * @return true when the SI domain of these priors is metres
   */
  public boolean isLinear() {
    return Double.isNaN(moiKgM2) && !Double.isNaN(massKg);
  }

  /**
   * The velocity gain implied by the motor curve, in volts per (m/s) or volts per (rad/s).
   *
   * <p>This is the dominant feedforward term and the one a student can check by hand: it is the
   * reciprocal of the mechanism's free speed per volt.
   *
   * @return kV, or {@link Double#NaN} if the prior is not usable
   */
  public double kVprior() {
    if (!isUsable()) {
      return Double.NaN;
    }
    double gearing = reduction.rotorPerOutput();
    double radPerSecPerVolt = motor.KvRadPerSecPerVolt / gearing;
    return isLinear() ? 1.0 / (radPerSecPerVolt * radiusMetres) : 1.0 / radPerSecPerVolt;
  }

  /**
   * The acceleration gain implied by the motor curve, in volts per (m/s^2) or volts per (rad/s^2).
   *
   * <p>Derived as {@code R * inertia / (G * Kt)} — the voltage needed to push the current that
   * produces the torque that accelerates the load, with the linear case using {@code mass * radius}
   * as its equivalent inertia term.
   *
   * @return kA, or {@link Double#NaN} if the prior is not usable
   */
  public double kAprior() {
    if (!isUsable()) {
      return Double.NaN;
    }
    double gearing = reduction.rotorPerOutput();
    double inertiaTerm = isLinear() ? massKg * radiusMetres : moiKgM2;
    return motor.rOhms * inertiaTerm / (gearing * motor.KtNMPerAmp);
  }

  /**
   * The volts needed to hold the gravity load at its worst-case pose, as a <b>positive
   * magnitude</b>.
   *
   * <p>The sign is never assumed here — which way "up" is depends on motor inversion, gearing parity
   * and how the mechanism was assembled, and every one of those is a thing teams get wrong. The
   * identification routine measures the sign; this method only supplies the size, to bracket that
   * search.
   *
   * @return kG, or {@link Double#NaN} for an archetype with no gravity load
   */
  public double gravityVoltsPrior() {
    if (!isUsable() || Double.isNaN(massKg) || Double.isNaN(radiusMetres)) {
      return Double.NaN;
    }
    double gearing = reduction.rotorPerOutput();
    double gravityTorque = massKg * kStandardGravity * radiusMetres;
    return gravityTorque * motor.rOhms / (gearing * motor.KtNMPerAmp);
  }

  /**
   * Free speed at the mechanism, in m/s or rad/s, at {@link #nominalVolts()}.
   *
   * <p>Used for the velocity abort ceiling and for a gentle first profile. It is the motor's free
   * speed divided by the gearing, scaled by the radius on a linear axis.
   *
   * @return the free speed, or {@link Double#NaN} if the prior is not usable
   */
  public double freeSpeedSi() {
    if (!isUsable()) {
      return Double.NaN;
    }
    double gearing = reduction.rotorPerOutput();
    double radPerSec = nominalVolts * motor.KvRadPerSecPerVolt / gearing;
    return isLinear() ? radPerSec * radiusMetres : radPerSec;
  }

  /**
   * Voltage-limited acceleration at the mechanism, in m/s^2 or rad/s^2.
   *
   * <p>The most the mechanism can accelerate if the whole battery is spent on acceleration and
   * nothing on velocity or gravity — an upper bound, useful for rejecting a profile that asks for
   * more than the hardware can deliver.
   *
   * @return the acceleration ceiling, or {@link Double#NaN} if the prior is not usable
   */
  public double maxAccelSi() {
    double kA = kAprior();
    return Double.isNaN(kA) || kA == 0.0 ? Double.NaN : nominalVolts / kA;
  }

  /**
   * Whether the priors above can be computed at all.
   *
   * @return true when the motor curve and a positive reduction are both present
   */
  private boolean isUsable() {
    return motor != null && reduction != null && reduction.rotorPerOutput() > 0;
  }

  /**
   * The derived priors on one line, for the boot dump — so a student can compare them against what
   * the tuner eventually measures, and see whether their declared mass was right.
   *
   * @return e.g. {@code "prior: kV 5.310, kA 0.026, kG 0.253, free speed 2.260, from 12.00:1"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "prior: kV %.4f, kA %.5f, kG %.4f, free speed %.3f, from %s",
        kVprior(),
        kAprior(),
        gravityVoltsPrior(),
        freeSpeedSi(),
        reduction == null ? "no reduction" : reduction.describe());
  }
}

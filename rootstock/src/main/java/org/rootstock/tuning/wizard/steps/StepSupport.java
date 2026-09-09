package org.rootstock.tuning.wizard.steps;

import org.rootstock.control.GravityMode;
import org.rootstock.control.PlantPrior;
import org.rootstock.tuning.wizard.StepContext;
import org.rootstock.units.SiDomain;

/**
 * The handful of quantities more than one step needs, computed one way.
 *
 * <p>Package-private on purpose. Every method here is a physical definition that two steps must
 * agree on — if {@code BreakawayRampStep} and {@code HoldBisectionStep} used different drift
 * deadbands, the breakaway fallback would disagree with the drift probe about whether a mechanism
 * moved, and the resulting bug would look like flaky hardware.
 */
final class StepSupport {

  /** Floor on the "is it moving?" deadband for a linear axis, in metres per second. */
  static final double kLinearDriftFloor = 1e-3;

  /** Floor on the "is it moving?" deadband for a rotary axis, in radians per second. */
  static final double kRotaryDriftFloor = 5e-3;

  /** How many noise standard deviations count as real motion. Three is the usual convention. */
  static final double kNoiseSigmas = 3.0;

  private StepSupport() {}

  /**
   * The gravity shape factor at the mechanism's current position: 1 for constant gravity, the
   * cosine of the angle from horizontal for cosine gravity, and zero when there is no gravity term.
   *
   * @param ctx the step context
   * @return the multiplier that turns kG into the volts gravity needs right now
   */
  static double gravityShape(StepContext ctx) {
    GravityMode mode = ctx.target().gravityMode();
    if (mode == GravityMode.COSINE) {
      return Math.cos(ctx.target().measuredSi() - ctx.target().horizontalReferenceSi());
    }
    return mode == GravityMode.NONE ? 0.0 : 1.0;
  }

  /**
   * The volts currently needed just to hold the mechanism against gravity, using the kG accumulated
   * so far this session.
   *
   * <p>This is why the gravity pre-pass runs before the friction step. Without it, the kS ramp
   * measures "volts to lift the elevator" rather than "volts to overcome friction", and kS comes
   * out about ten times too large.
   *
   * @param ctx the step context
   * @return the gravity-cancelling voltage, signed
   */
  static double gravityCompensation(StepContext ctx) {
    return ctx.gains().kG() * gravityShape(ctx);
  }

  /**
   * The speed below which the mechanism counts as stationary.
   *
   * @param ctx the step context
   * @param measuredNoiseStdDev the measured velocity noise, or NaN if it has not been measured
   * @return the deadband in SI units per second
   */
  static double driftDeadband(StepContext ctx, double measuredNoiseStdDev) {
    double floor =
        ctx.target().siDomain() == SiDomain.LINEAR_METERS ? kLinearDriftFloor : kRotaryDriftFloor;
    if (Double.isFinite(measuredNoiseStdDev) && measuredNoiseStdDev > 0) {
      return Math.max(floor, kNoiseSigmas * measuredNoiseStdDev);
    }
    return floor;
  }

  /**
   * The free speed the mechanism's own mass and gearing predict, or NaN when the prior cannot say.
   *
   * @param ctx the step context
   * @return the free speed in SI units per second
   */
  static double freeSpeed(StepContext ctx) {
    PlantPrior prior = ctx.target().plantPrior();
    return prior == null ? Double.NaN : prior.freeSpeedSi();
  }

  /**
   * A provisional proportional gain, from the physics prior rather than from a measurement.
   *
   * <p>Used only to walk a mechanism back to where a probe started. It does not need to be a good
   * gain; it needs to be a gain that closes a loop instead of leaving one open, because open-loop
   * recentring on a gravity mechanism is the same hazard the probe itself is.
   *
   * @param ctx the step context
   * @return volts per SI unit of error
   */
  static double provisionalKp(StepContext ctx) {
    double effort = Math.min(4.0, ctx.supervisor().envelope().maxVolts());
    double range =
        ctx.target().travelLimits() == null ? Double.NaN : ctx.target().travelLimits().range();
    // "Four volts per half a percent of travel" rather than "four volts per tolerance": a very
    // tight tolerance would otherwise produce a gain that saturates on any error at all, and this
    // controller only ever has to walk back across three percent of the travel range.
    double floor = Double.isFinite(range) && range > 0 ? 0.005 * range : 0.002;
    double error = Math.max(ctx.toleranceSi(), floor);
    return effort / Math.max(error, 1e-4);
  }

  /**
   * How long a trapezoidal move of this distance takes, computed analytically.
   *
   * <p>Written out rather than read back from {@code TrapezoidProfile.totalTime()} because the
   * budget has to be known <em>before</em> the profile is stepped even once — it is the bound that
   * stops a stiction-held mechanism applying voltage forever.
   *
   * @param distance the distance to travel, in SI units
   * @param maxVelocity the profile's cruise speed
   * @param maxAcceleration the profile's acceleration
   * @return the duration in seconds, or zero if the constraints are not usable
   */
  static double profileDuration(double distance, double maxVelocity, double maxAcceleration) {
    double d = Math.abs(distance);
    if (!(maxVelocity > 0) || !(maxAcceleration > 0) || !Double.isFinite(d)) {
      return 0.0;
    }
    double triangularDistance = maxVelocity * maxVelocity / maxAcceleration;
    if (d <= triangularDistance) {
      return 2.0 * Math.sqrt(d / maxAcceleration);
    }
    return d / maxVelocity + maxVelocity / maxAcceleration;
  }

  /**
   * The unit label the student sees, "m" or "rad".
   *
   * @param ctx the step context
   * @return the label
   */
  static String unitLabel(StepContext ctx) {
    SiDomain domain = ctx.target().siDomain();
    return domain == null ? "" : domain.label();
  }

  /**
   * The standard deviation of a running sum-of-squares accumulation.
   *
   * @param sum the sum of the samples
   * @param sumSquares the sum of the squares of the samples
   * @param count how many samples
   * @return the standard deviation, or NaN with fewer than two samples
   */
  static double stdDev(double sum, double sumSquares, int count) {
    if (count < 2) {
      return Double.NaN;
    }
    double mean = sum / count;
    double variance = Math.max(0.0, sumSquares / count - mean * mean);
    return Math.sqrt(variance);
  }
}

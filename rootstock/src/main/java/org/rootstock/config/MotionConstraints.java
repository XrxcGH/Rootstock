package org.rootstock.config;

import static edu.wpi.first.units.Units.DegreesPerSecond;
import static edu.wpi.first.units.Units.DegreesPerSecondPerSecond;
import static edu.wpi.first.units.Units.MetersPerSecond;
import static edu.wpi.first.units.Units.MetersPerSecondPerSecond;

import edu.wpi.first.units.measure.AngularAcceleration;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.LinearAcceleration;
import edu.wpi.first.units.measure.LinearVelocity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.units.MechanismUnits;

/**
 * How fast a profiled move is allowed to be: cruise velocity, acceleration and jerk.
 *
 * <h2>The units, stated once and enforced everywhere</h2>
 *
 * <p>These three numbers are in <b>user units per second, per second squared and per second
 * cubed</b> — metres for a linear mechanism, <em>degrees</em> for a rotary one. That is the layer a
 * human types and reads: "the elevator cruises at 1.6 m/s", "the arm cruises at 180 deg/s". They are
 * converted to the output rotations a motor controller wants at the seam, exactly once, through
 * {@link MechanismUnits#toOutputRps(double)} and its two siblings.
 *
 * <p><b>All three of them, every time.</b> An earlier revision converted cruise velocity and
 * acceleration on two adjacent lines and passed jerk through raw — off by 360 on a rotary axis and
 * by one-over-travel-per-rotation on a linear one. The three helpers on this record
 * ({@link #maxVelocityRps(MechanismUnits)}, {@link #maxAccelerationRps2(MechanismUnits)},
 * {@link #jerkRps3(MechanismUnits)}) exist so that the conversion is one call rather than one
 * multiplication a reader has to check, and so that leaving one out is visible rather than invisible.
 *
 * <h2>This is an authoring-time value</h2>
 *
 * <p>A team types a {@code MotionConstraints}; a controller consumes a profile. The record carries
 * no controller state, no profile object and no clock — it is three doubles and their unit
 * contract, which is what lets it be logged as a struct, varied per robot with a {@code with*()}
 * copy, and driven from a tuning slider at runtime without reconstructing anything.
 *
 * @param maxVelocity cruise velocity, in user units per second
 * @param maxAcceleration acceleration limit, in user units per second squared
 * @param jerk third-derivative limit, in user units per second cubed; zero means "no jerk limit",
 *     which is what a plain trapezoidal profile is
 */
public record MotionConstraints(double maxVelocity, double maxAcceleration, double jerk) {

  /**
   * Cruise velocity and acceleration, with no jerk limit — the trapezoidal profile every mechanism
   * starts with.
   *
   * @param maxVelocity cruise velocity, in user units per second
   * @param maxAcceleration acceleration limit, in user units per second squared
   * @return the constraints
   */
  public static MotionConstraints of(double maxVelocity, double maxAcceleration) {
    return new MotionConstraints(maxVelocity, maxAcceleration, 0.0);
  }

  /**
   * Cruise velocity and acceleration for a linear mechanism, typed.
   *
   * <p>Stores metres per second and metres per second squared, which is what a linear mechanism's
   * user unit already is.
   *
   * @param maxVelocity cruise velocity
   * @param maxAcceleration acceleration limit
   * @return the constraints
   */
  public static MotionConstraints of(LinearVelocity maxVelocity, LinearAcceleration maxAcceleration) {
    Objects.requireNonNull(maxVelocity, kNullVelocity);
    Objects.requireNonNull(maxAcceleration, kNullAcceleration);
    return new MotionConstraints(
        maxVelocity.in(MetersPerSecond), maxAcceleration.in(MetersPerSecondPerSecond), 0.0);
  }

  /**
   * Cruise velocity and acceleration for a rotary mechanism, typed.
   *
   * <p>Stores <b>degrees</b> per second and degrees per second squared, because degrees are a rotary
   * mechanism's user unit throughout this library. Radians are the SI layer, one conversion further
   * in, and no team types them.
   *
   * @param maxVelocity cruise velocity
   * @param maxAcceleration acceleration limit
   * @return the constraints
   */
  public static MotionConstraints of(
      AngularVelocity maxVelocity, AngularAcceleration maxAcceleration) {
    Objects.requireNonNull(maxVelocity, kNullVelocity);
    Objects.requireNonNull(maxAcceleration, kNullAcceleration);
    return new MotionConstraints(
        maxVelocity.in(DegreesPerSecond), maxAcceleration.in(DegreesPerSecondPerSecond), 0.0);
  }

  /**
   * Constraints that do not constrain, for a mechanism whose motion is bounded by something else.
   *
   * <p>Both limits are infinite, which every profile implementation reads as "go as fast as the
   * motor will".
   *
   * @return the constraints
   */
  public static MotionConstraints unconstrained() {
    return new MotionConstraints(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0);
  }

  /**
   * A copy with a different cruise velocity.
   *
   * @param value the new cruise velocity, in user units per second
   * @return a copy
   */
  public MotionConstraints withMaxVelocity(double value) {
    return new MotionConstraints(value, maxAcceleration, jerk);
  }

  /**
   * A copy with a different acceleration limit.
   *
   * @param value the new acceleration limit, in user units per second squared
   * @return a copy
   */
  public MotionConstraints withMaxAcceleration(double value) {
    return new MotionConstraints(maxVelocity, value, jerk);
  }

  /**
   * A copy with a different jerk limit.
   *
   * @param value the new jerk limit, in user units per second cubed; zero for none
   * @return a copy
   */
  public MotionConstraints withJerk(double value) {
    return new MotionConstraints(maxVelocity, maxAcceleration, value);
  }

  /**
   * A copy with every limit scaled by the same factor.
   *
   * <p>Used to time-scale one axis of a synchronised pair so both arrive together, and to derate a
   * mechanism under a battery-voltage or safety policy. Scaling all three together keeps the shape
   * of the profile and only changes how long it takes.
   *
   * @param factor the scale factor
   * @return a copy
   */
  public MotionConstraints scaled(double factor) {
    return new MotionConstraints(maxVelocity * factor, maxAcceleration * factor, jerk * factor);
  }

  /**
   * Whether a jerk limit is set.
   *
   * @return true when {@link #jerk()} is finite and above zero
   */
  public boolean hasJerk() {
    return Double.isFinite(jerk) && jerk > 0.0;
  }

  /**
   * Cruise velocity in SI — metres per second, or radians per second.
   *
   * @param units the mechanism's conversion object
   * @return the cruise velocity in SI units per second
   */
  public double maxVelocitySi(MechanismUnits units) {
    return units.toSiPerSec(maxVelocity);
  }

  /**
   * Acceleration limit in SI.
   *
   * @param units the mechanism's conversion object
   * @return the acceleration limit in SI units per second squared
   */
  public double maxAccelerationSi(MechanismUnits units) {
    return units.toSiPerSec2(maxAcceleration);
  }

  /**
   * Cruise velocity in output rotations per second, which is what a motor controller's profile
   * wants.
   *
   * @param units the mechanism's conversion object
   * @return output rotations per second
   */
  public double maxVelocityRps(MechanismUnits units) {
    return units.toOutputRps(maxVelocity);
  }

  /**
   * Acceleration limit in output rotations per second squared.
   *
   * @param units the mechanism's conversion object
   * @return output rotations per second squared
   */
  public double maxAccelerationRps2(MechanismUnits units) {
    return units.toOutputRps2(maxAcceleration);
  }

  /**
   * Jerk limit in output rotations per second cubed.
   *
   * <p>This method exists because its absence was a live bug: a backend that converts velocity and
   * acceleration and then passes jerk through raw is wrong by a factor of 360 on every rotary
   * mechanism, and nothing about the two adjacent correct lines makes the third one look wrong.
   *
   * @param units the mechanism's conversion object
   * @return output rotations per second cubed
   */
  public double jerkRps3(MechanismUnits units) {
    return units.toOutputRps3(jerk);
  }

  /**
   * Every problem visible from these constraints alone.
   *
   * <p><b>Never throws, never returns null.</b> The check that catches the most useful error — a
   * cruise velocity above the mechanism's physical free speed — needs the gearing and the motor, so
   * it belongs to validation rather than here.
   *
   * @return the problems, in declaration order; empty when the constraints are fine
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    if (Double.isNaN(maxVelocity) || maxVelocity <= 0.0) {
      out.add(
          "MotionConstraints: maxVelocity = "
              + maxVelocity
              + ", which must be greater than zero (or infinite for unconstrained). A cruise "
              + "velocity of zero means every profiled move takes forever and the mechanism never "
              + "reaches its goal. Fix: MotionConstraints.of(1.6, 6.0) for metres per second, or "
              + "of(180.0, 540.0) for degrees per second.");
    }
    if (Double.isNaN(maxAcceleration) || maxAcceleration <= 0.0) {
      out.add(
          "MotionConstraints: maxAcceleration = "
              + maxAcceleration
              + ", which must be greater than zero (or infinite for unconstrained). With no "
              + "acceleration the profile can never leave zero velocity. Fix: start at about four "
              + "times the cruise velocity and tune from there.");
    }
    if (Double.isNaN(jerk) || jerk < 0.0) {
      out.add(
          "MotionConstraints: jerk = "
              + jerk
              + ", which must be zero (no jerk limit) or greater. Fix: leave it at zero unless you "
              + "have a mechanism that is visibly snapping into motion.");
    }
    return List.copyOf(out);
  }

  /**
   * The constraints as the boot dump prints them, in the unit the team typed.
   *
   * @param unitLabel the axis's user-unit label, {@code "m"} or {@code "deg"}
   * @return a human-readable description
   */
  public String describe(String unitLabel) {
    String u = unitLabel == null || unitLabel.isBlank() ? "unit" : unitLabel;
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "cruise %.4f %s/s", maxVelocity, u));
    sb.append(String.format(Locale.ROOT, ", accel %.4f %s/s^2", maxAcceleration, u));
    if (hasJerk()) {
      sb.append(String.format(Locale.ROOT, ", jerk %.4f %s/s^3", jerk, u));
    } else {
      sb.append(", no jerk limit (trapezoidal)");
    }
    return sb.toString();
  }

  private static final String kNullVelocity =
      "MotionConstraints: the cruise velocity must not be null. Type the unit you think in — "
          + "MetersPerSecond.of(1.6) or DegreesPerSecond.of(180).";

  private static final String kNullAcceleration =
      "MotionConstraints: the acceleration limit must not be null. About four times the cruise "
          + "velocity is a reasonable place to start.";
}

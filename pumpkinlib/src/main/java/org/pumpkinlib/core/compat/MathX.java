package org.pumpkinlib.core.compat;

import edu.wpi.first.math.MathUtil;

/**
 * Thin wrappers over the {@code edu.wpi.first.math.MathUtil} helpers whose home moves in 2027.
 *
 * <p>The known delta is {@code MathUtil.clamp} → {@code Math.clamp}; the rest are here so that a
 * caller who reaches for one scalar helper does not have to remember which of the two it was. Every
 * method is a one-line delegation, so the 2027 port of this file is a handful of method bodies in
 * one place rather than a search across the library.
 *
 * <p><b>Where this differs from {@code org.pumpkinlib.pure.math.PumpkinMath}.</b> {@code PumpkinMath}
 * is Tier 0 — it may not name {@code edu.wpi.first} at all (ArchUnit rule 8) — and holds the helpers
 * WPILib does not have. {@code MathX} holds the helpers WPILib does have but is going to move. If a
 * method exists in both, prefer {@code PumpkinMath}: it has nothing to port.
 */
public final class MathX {

  private MathX() {}

  /**
   * Confines a value to a range. 2027: {@code MathUtil.clamp} becomes {@code Math.clamp}.
   *
   * @param value the value to clamp
   * @param low the inclusive lower bound
   * @param high the inclusive upper bound
   * @return {@code value} confined to {@code [low, high]}
   */
  public static double clamp(double value, double low, double high) {
    return MathUtil.clamp(value, low, high);
  }

  /**
   * Confines an int to a range. 2027: {@code MathUtil.clamp} becomes {@code Math.clamp}.
   *
   * @param value the value to clamp
   * @param low the inclusive lower bound
   * @param high the inclusive upper bound
   * @return {@code value} confined to {@code [low, high]}
   */
  public static int clamp(int value, int low, int high) {
    return MathUtil.clamp(value, low, high);
  }

  /**
   * One-dimensional deadband with the surviving range rescaled to full scale.
   *
   * <p>For a joystick <i>vector</i>, use {@code PumpkinMath.deadband2d} — WPILib has no 2-D
   * overload, and deadbanding each axis separately zeroes a diagonal push that a radial deadband
   * keeps.
   *
   * @param value the raw value
   * @param deadband the deadband half-width
   * @return zero inside the band, otherwise the rescaled value
   */
  public static double applyDeadband(double value, double deadband) {
    return MathUtil.applyDeadband(value, deadband);
  }

  /**
   * One-dimensional deadband over an arbitrary full-scale magnitude.
   *
   * @param value the raw value
   * @param deadband the deadband half-width, in the same units as {@code value}
   * @param maxMagnitude the full-scale magnitude
   * @return zero inside the band, otherwise the rescaled value
   */
  public static double applyDeadband(double value, double deadband, double maxMagnitude) {
    return MathUtil.applyDeadband(value, deadband, maxMagnitude);
  }

  /**
   * Wraps a value into a half-open range — the general form of angle wrapping.
   *
   * @param input the value to wrap
   * @param minimumInput the lower bound of the range
   * @param maximumInput the upper bound of the range
   * @return the wrapped value
   */
  public static double inputModulus(double input, double minimumInput, double maximumInput) {
    return MathUtil.inputModulus(input, minimumInput, maximumInput);
  }

  /**
   * Wraps an angle into {@code (-pi, pi]}.
   *
   * @param angleRadians the angle in radians
   * @return the wrapped angle in radians
   */
  public static double angleModulus(double angleRadians) {
    return MathUtil.angleModulus(angleRadians);
  }

  /**
   * Whether two values are within a tolerance of one another.
   *
   * @param expected the expected value
   * @param actual the measured value
   * @param tolerance the absolute tolerance; must be non-negative
   * @return true if {@code |expected - actual| <= tolerance}
   */
  public static boolean isNear(double expected, double actual, double tolerance) {
    return MathUtil.isNear(expected, actual, tolerance);
  }

  /**
   * Whether two values are within a tolerance of one another, on a wrapping range.
   *
   * <p>This is the one to use for a turret or a swerve azimuth: 359° and 1° are 2° apart, and the
   * non-wrapping overload says they are 358° apart.
   *
   * @param expected the expected value
   * @param actual the measured value
   * @param tolerance the absolute tolerance; must be non-negative
   * @param min the lower bound of the wrapping range
   * @param max the upper bound of the wrapping range
   * @return true if the wrapped difference is within tolerance
   */
  public static boolean isNear(
      double expected, double actual, double tolerance, double min, double max) {
    return MathUtil.isNear(expected, actual, tolerance, min, max);
  }

  /**
   * Linear interpolation, clamped to the endpoints.
   *
   * @param startValue the value at {@code t == 0}
   * @param endValue the value at {@code t == 1}
   * @param t the interpolation parameter, clamped to {@code [0, 1]}
   * @return the interpolated value
   */
  public static double interpolate(double startValue, double endValue, double t) {
    return MathUtil.interpolate(startValue, endValue, t);
  }
}

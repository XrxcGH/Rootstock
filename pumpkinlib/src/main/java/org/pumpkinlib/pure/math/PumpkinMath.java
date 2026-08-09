package org.pumpkinlib.pure.math;

/**
 * The scalar shaping helpers PumpkinLib needs that either do not exist in WPILib, or exist in a
 * form that moves in 2027.
 *
 * <p><b>Tier 0.</b> This class has zero {@code edu.wpi.first} imports, is unit-testable with plain
 * JUnit and no HAL, and ports to the {@code org.wpilib.*} namespace by having nothing to port.
 * ArchUnit rule 8 checks that by bytecode scan.
 *
 * <p><b>Why not just call WPILib.</b> {@code MathUtil.clamp} becomes {@code Math.clamp} in 2027,
 * and {@code MathUtil} has no two-dimensional deadband and — verified against WPILib 2026.2.2 by
 * compiling against it — <b>no {@code copySignPow}</b>, despite {@code design/05} §2 stating that
 * it is "real in 2026.2.2 and we do not wrap it". It is not. {@link #expo} is that missing method.
 */
public final class PumpkinMath {

  private PumpkinMath() {}

  /** Default tolerance for the two-argument {@link #epsilonEquals(double, double)}. */
  public static final double kDefaultEpsilon = 1e-9;

  /**
   * Java 21's {@code Math.clamp}, for Java 17.
   *
   * @param value the value to clamp
   * @param low the inclusive lower bound
   * @param high the inclusive upper bound
   * @return {@code value} confined to {@code [low, high]}
   */
  public static double clamp(double value, double low, double high) {
    return Math.max(low, Math.min(high, value));
  }

  /**
   * Java 21's {@code Math.clamp}, for Java 17, integer overload.
   *
   * @param value the value to clamp
   * @param low the inclusive lower bound
   * @param high the inclusive upper bound
   * @return {@code value} confined to {@code [low, high]}
   */
  public static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }

  /**
   * One-dimensional deadband with the surviving range rescaled to full scale.
   *
   * <p>Rescaling is the whole point: a bare {@code if (abs(v) < band) return 0} leaves a step
   * discontinuity at the band edge, so a driver feels the stick "catch" as it leaves the deadband.
   * Here the output leaves zero continuously and still reaches 1.0 at full stick.
   *
   * @param value the raw axis value, nominally in {@code [-1, 1]}
   * @param band the deadband half-width, in {@code [0, 1)}
   * @return zero inside the band, otherwise the rescaled value with the sign of {@code value}
   * @throws IllegalArgumentException if {@code band} is not in {@code [0, 1)}
   */
  public static double deadband(double value, double band) {
    requireBand(band);
    double magnitude = Math.abs(value);
    if (magnitude < band) {
      return 0.0;
    }
    return Math.copySign((magnitude - band) / (1.0 - band), value);
  }

  /**
   * One-dimensional deadband over an arbitrary full-scale range.
   *
   * @param value the raw value, nominally in {@code [-maxMagnitude, maxMagnitude]}
   * @param band the deadband half-width, in the same units as {@code value}
   * @param maxMagnitude the full-scale magnitude; must be strictly greater than {@code band}
   * @return zero inside the band, otherwise the rescaled value with the sign of {@code value}
   * @throws IllegalArgumentException if {@code band} is negative or not less than
   *     {@code maxMagnitude}
   */
  public static double deadband(double value, double band, double maxMagnitude) {
    if (!(band >= 0.0) || !(band < maxMagnitude)) {
      throw new IllegalArgumentException(
          "PumpkinMath.deadband: band was "
              + band
              + " and maxMagnitude was "
              + maxMagnitude
              + "; band must be >= 0 and strictly less than maxMagnitude. "
              + "Fix: pass the deadband and the full-scale magnitude in the SAME units "
              + "(e.g. deadband(volts, 0.25, 12.0)).");
    }
    double magnitude = Math.abs(value);
    if (magnitude < band) {
      return 0.0;
    }
    return Math.copySign(maxMagnitude * (magnitude - band) / (maxMagnitude - band), value);
  }

  /**
   * Radial (two-dimensional) deadband.
   *
   * <p>Deadbands the joystick <b>vector</b>, not each axis, so a diagonal push of 0.09/0.09
   * (magnitude 0.127) is not silently zeroed while a 0.11 push on one axis survives. The surviving
   * magnitude is rescaled to the full {@code [0, 1]} range so there is no discontinuity at the band
   * edge.
   *
   * <p>WPILib's {@code MathUtil} has {@code applyDeadband(double, double)} and
   * {@code applyDeadband(double, double, double)} only — there is no 2-D overload in 2026.2.2. This
   * is that missing six lines.
   *
   * @param x the first axis, nominally in {@code [-1, 1]}
   * @param y the second axis, nominally in {@code [-1, 1]}
   * @param band the radial deadband, in {@code [0, 1)}
   * @return {@link Vec2#ZERO} inside the band, otherwise the rescaled vector in the same direction
   * @throws IllegalArgumentException if {@code band} is not in {@code [0, 1)}
   */
  public static Vec2 deadband2d(double x, double y, double band) {
    requireBand(band);
    double magnitude = Math.hypot(x, y);
    if (magnitude < band || magnitude == 0.0) {
      return Vec2.ZERO;
    }
    double scaled = (magnitude - band) / (1.0 - band);
    return new Vec2(scaled * x / magnitude, scaled * y / magnitude);
  }

  /**
   * Exponential (sign-preserving power) input shaping — {@code copySign(pow(abs(v), exponent), v)}.
   *
   * <p>An exponent of 1.0 is the identity; larger exponents flatten the response near centre, which
   * is what a driver means by "make the stick less twitchy". The sign is preserved, so a negative
   * stick still drives the robot backwards — the bug this method exists to prevent is
   * {@code Math.pow(-0.5, 2.0) == 0.25}, a forward command from a backward push.
   *
   * <p>This is hand-written rather than delegated: {@code MathUtil.copySignPow} does not exist in
   * WPILib 2026.2.2, verified by compiling against it.
   *
   * @param value the raw axis value
   * @param exponent the shaping exponent; must be strictly positive
   * @return the shaped value, with the sign of {@code value}
   * @throws IllegalArgumentException if {@code exponent} is not strictly positive and finite
   */
  public static double expo(double value, double exponent) {
    if (!(exponent > 0.0) || !Double.isFinite(exponent)) {
      throw new IllegalArgumentException(
          "PumpkinMath.expo: exponent was "
              + exponent
              + "; it must be finite and strictly positive (1.0 = linear, 2.0 = a common "
              + "driver-feel curve). Fix: pass a value like 1.5 or 2.0.");
    }
    return Math.copySign(Math.pow(Math.abs(value), exponent), value);
  }

  /**
   * Exponential shaping blended with a linear term, which is how most driver-feel curves are
   * actually written.
   *
   * <p>{@code linearFraction = 1.0} is fully linear; {@code 0.0} is fully {@link #expo}. A typical
   * swerve translation curve is {@code expo(v, 2.0, 0.15)}.
   *
   * @param value the raw axis value
   * @param exponent the shaping exponent; must be strictly positive
   * @param linearFraction how much of the raw linear response to keep, in {@code [0, 1]}
   * @return the blended shaped value, with the sign of {@code value}
   * @throws IllegalArgumentException if {@code exponent} is not strictly positive and finite, or
   *     {@code linearFraction} is outside {@code [0, 1]}
   */
  public static double expo(double value, double exponent, double linearFraction) {
    if (!(linearFraction >= 0.0) || !(linearFraction <= 1.0)) {
      throw new IllegalArgumentException(
          "PumpkinMath.expo: linearFraction was "
              + linearFraction
              + "; it must be in [0, 1] (0 = pure curve, 1 = pure linear). "
              + "Fix: pass something like 0.15.");
    }
    return linearFraction * value + (1.0 - linearFraction) * expo(value, exponent);
  }

  /**
   * Whether two doubles are within a tolerance of one another.
   *
   * @param a the first value
   * @param b the second value
   * @param epsilon the absolute tolerance; must be non-negative
   * @return true if {@code |a - b| < epsilon}
   */
  public static boolean epsilonEquals(double a, double b, double epsilon) {
    return Math.abs(a - b) < epsilon;
  }

  /**
   * Whether two doubles are within {@link #kDefaultEpsilon} of one another.
   *
   * @param a the first value
   * @param b the second value
   * @return true if {@code |a - b| < 1e-9}
   */
  public static boolean epsilonEquals(double a, double b) {
    return epsilonEquals(a, b, kDefaultEpsilon);
  }

  /**
   * Linear interpolation, clamped to the endpoints.
   *
   * <p>Clamping rather than extrapolating is deliberate: every caller in this library is
   * interpolating a physical table (shooter RPM vs distance, kG vs extension), and extrapolating one
   * of those past its measured range is how a mechanism gets commanded somewhere nobody measured.
   *
   * @param start the value at {@code t == 0}
   * @param end the value at {@code t == 1}
   * @param t the interpolation parameter; clamped to {@code [0, 1]}
   * @return the interpolated value
   */
  public static double interpolate(double start, double end, double t) {
    return start + (end - start) * clamp(t, 0.0, 1.0);
  }

  /**
   * The inverse of {@link #interpolate}: where {@code value} sits between {@code start} and
   * {@code end}, as a fraction clamped to {@code [0, 1]}.
   *
   * @param start the value that maps to 0
   * @param end the value that maps to 1
   * @param value the value to locate
   * @return the fraction in {@code [0, 1]}; zero when {@code start == end}
   */
  public static double inverseInterpolate(double start, double end, double value) {
    double span = end - start;
    if (span == 0.0) {
      return 0.0;
    }
    return clamp((value - start) / span, 0.0, 1.0);
  }

  private static void requireBand(double band) {
    if (!(band >= 0.0) || !(band < 1.0)) {
      throw new IllegalArgumentException(
          "PumpkinMath deadband: band was "
              + band
              + "; it must be in [0, 1) because the input is a normalised joystick axis. "
              + "Fix: pass something like 0.08. If your input is NOT normalised, use the "
              + "three-argument deadband(value, band, maxMagnitude) overload instead.");
    }
  }
}

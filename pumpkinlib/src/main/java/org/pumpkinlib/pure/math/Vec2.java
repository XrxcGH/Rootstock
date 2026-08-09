package org.pumpkinlib.pure.math;

/**
 * A plain two-component vector.
 *
 * <p>This type exists for one reason: ArchUnit rule 8 forbids {@code org.pumpkinlib.pure} from
 * importing anything under {@code edu.wpi.first}, and it is verified by a bytecode scan rather than
 * by convention. {@code design/05} §2 wrote {@link PumpkinMath#deadband2d} as returning a WPILib
 * {@code Translation2d}, which would have made the whole {@code pure} package fail that scan on its
 * first CI run. Returning a HAL-free value and letting the caller widen it is the smallest fix, and
 * it costs a caller in {@code org.pumpkinlib.drive} exactly one line:
 *
 * <pre>{@code
 * Vec2 v = PumpkinMath.deadband2d(x, y, 0.10);
 * Translation2d t = new Translation2d(v.x(), v.y());
 * }</pre>
 *
 * @param x the first component
 * @param y the second component
 */
public record Vec2(double x, double y) {

  /** The origin. Returned by {@link PumpkinMath#deadband2d} when the input is inside the band. */
  public static final Vec2 ZERO = new Vec2(0.0, 0.0);

  /**
   * The Euclidean magnitude, {@code hypot(x, y)}.
   *
   * @return the magnitude, always non-negative
   */
  public double norm() {
    return Math.hypot(x, y);
  }

  /**
   * Whether both components are exactly zero.
   *
   * @return true if this is {@link #ZERO} by value
   */
  public boolean isZero() {
    return x == 0.0 && y == 0.0;
  }

  /**
   * The angle of this vector measured counter-clockwise from the +x axis.
   *
   * @return the angle in radians, in {@code (-pi, pi]}; zero for {@link #ZERO}
   */
  public double angleRadians() {
    return Math.atan2(y, x);
  }

  /**
   * This vector scaled by a factor.
   *
   * @param factor the scale factor
   * @return a new scaled vector
   */
  public Vec2 times(double factor) {
    return new Vec2(x * factor, y * factor);
  }
}

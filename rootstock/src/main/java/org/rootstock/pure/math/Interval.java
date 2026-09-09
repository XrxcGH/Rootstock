package org.rootstock.pure.math;

import java.util.Optional;

/**
 * A closed one-dimensional range, {@code [min, max]}.
 *
 * <p>Used everywhere the library has to say "this value is legal and that one is not" without
 * dragging in a unit type: soft limits in SI, a tuning supervisor's voltage band, the physically
 * bracketed interval the kG bisection searches, a lookup table's domain.
 *
 * <p><b>Tier 0.</b> Zero {@code edu.wpi.first} imports (ArchUnit rule 8).
 *
 * @param min the inclusive lower bound
 * @param max the inclusive upper bound
 */
public record Interval(double min, double max) {

  /**
   * Validates that the interval is well-formed.
   *
   * @throws IllegalArgumentException if either bound is NaN, or {@code min > max}
   */
  public Interval {
    if (Double.isNaN(min) || Double.isNaN(max)) {
      throw new IllegalArgumentException(
          "Interval: bounds were [" + min + ", " + max + "]; neither bound may be NaN. "
              + "Fix: a NaN bound almost always means an unset config field reached this "
              + "constructor. Check the value you passed in.");
    }
    if (min > max) {
      throw new IllegalArgumentException(
          "Interval: bounds were ["
              + min
              + ", "
              + max
              + "]; min must be <= max. Fix: swap the arguments, or use Interval.ofUnordered("
              + min
              + ", "
              + max
              + ") if you genuinely do not know which is which.");
    }
  }

  /**
   * An interval from two bounds in order.
   *
   * @param min the inclusive lower bound
   * @param max the inclusive upper bound
   * @return the interval
   */
  public static Interval of(double min, double max) {
    return new Interval(min, max);
  }

  /**
   * An interval from two bounds in either order.
   *
   * @param a one bound
   * @param b the other bound
   * @return the interval {@code [min(a, b), max(a, b)]}
   */
  public static Interval ofUnordered(double a, double b) {
    return new Interval(Math.min(a, b), Math.max(a, b));
  }

  /**
   * An interval centred on a value.
   *
   * @param centre the centre of the interval
   * @param halfWidth the distance from centre to each bound; must be non-negative
   * @return the interval {@code [centre - halfWidth, centre + halfWidth]}
   */
  public static Interval around(double centre, double halfWidth) {
    return new Interval(centre - Math.abs(halfWidth), centre + Math.abs(halfWidth));
  }

  /** The whole real line. Useful as a "no limit declared" sentinel that still behaves. */
  public static final Interval UNBOUNDED =
      new Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

  /**
   * Whether a value lies inside this interval, bounds included.
   *
   * @param value the value to test
   * @return true if {@code min <= value <= max}
   */
  public boolean contains(double value) {
    return value >= min && value <= max;
  }

  /**
   * Whether another interval lies entirely inside this one.
   *
   * @param other the candidate sub-interval
   * @return true if {@code other} is contained
   */
  public boolean contains(Interval other) {
    return other.min >= min && other.max <= max;
  }

  /**
   * Confines a value to this interval.
   *
   * @param value the value to clamp
   * @return the clamped value
   */
  public double clamp(double value) {
    return RootstockMath.clamp(value, min, max);
  }

  /**
   * The width of this interval.
   *
   * @return {@code max - min}, always non-negative
   */
  public double length() {
    return max - min;
  }

  /**
   * The midpoint of this interval.
   *
   * @return {@code (min + max) / 2}
   */
  public double centre() {
    return (min + max) / 2.0;
  }

  /**
   * Where a value sits in this interval as a fraction, clamped to {@code [0, 1]}.
   *
   * @param value the value to locate
   * @return the fraction; zero for a zero-length interval
   */
  public double fractionOf(double value) {
    return RootstockMath.inverseInterpolate(min, max, value);
  }

  /**
   * The value at a fraction of the way through this interval.
   *
   * @param fraction the fraction, clamped to {@code [0, 1]}
   * @return the interpolated value
   */
  public double lerp(double fraction) {
    return RootstockMath.interpolate(min, max, fraction);
  }

  /**
   * Whether this interval and another share at least one point.
   *
   * @param other the other interval
   * @return true if they touch or overlap
   */
  public boolean overlaps(Interval other) {
    return min <= other.max && other.min <= max;
  }

  /**
   * The common part of this interval and another.
   *
   * @param other the other interval
   * @return the intersection, or empty if they are disjoint
   */
  public Optional<Interval> intersect(Interval other) {
    if (!overlaps(other)) {
      return Optional.empty();
    }
    return Optional.of(new Interval(Math.max(min, other.min), Math.min(max, other.max)));
  }

  /**
   * The smallest interval containing both this interval and another.
   *
   * @param other the other interval
   * @return the hull
   */
  public Interval union(Interval other) {
    return new Interval(Math.min(min, other.min), Math.max(max, other.max));
  }

  /**
   * This interval grown by an amount on each side.
   *
   * @param amount the amount to grow by on each side; may be negative to shrink
   * @return the grown interval, collapsed to its centre if a negative amount would invert it
   */
  public Interval expandedBy(double amount) {
    double newMin = min - amount;
    double newMax = max + amount;
    if (newMin > newMax) {
      double mid = centre();
      return new Interval(mid, mid);
    }
    return new Interval(newMin, newMax);
  }

  /**
   * A human-readable form for {@code describe()} output and error messages.
   *
   * @return e.g. {@code "[0.000, 1.250]"}
   */
  public String describe() {
    return String.format("[%.3f, %.3f]", min, max);
  }
}

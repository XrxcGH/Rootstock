package org.pumpkinlib.units;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;

import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.pure.math.Interval;

/**
 * A closed span of one axis, <b>in that axis's user units</b>, carrying its unit label.
 *
 * <p>{@code Interval} in {@code org.pumpkinlib.pure.math} is the same shape and is deliberately
 * unit-free — it is what the tuning bisection and the lookup tables use. {@code Range} is the
 * boundary type: it is built from {@code Measure} values, so a caller states {@code
 * Range.of(Inches.of(0), Inches.of(9))} and cannot accidentally hand nine <em>metres</em> to a
 * forbidden-zone declaration, and it remembers whether it is metres or degrees so an error message
 * can say which.
 *
 * <pre>{@code
 * SafetyModel.over(ELEVATOR, ARM)
 *     .forbid("arm-through-chassis",
 *             Range.of(Inches.of(0), Inches.of(9)),        // elevator low   -> metres
 *             Range.of(Degrees.of(-15), Degrees.of(40)),   // arm swung down -> degrees
 *             "the arm hits the chassis crossbar below 9 in")
 *     .build();
 * }</pre>
 *
 * <p><b>Bounds may be given in either order.</b> A range is a region, not a directed pair, and
 * {@code Range.of(Degrees.of(40), Degrees.of(-15))} means the same region as its reverse. Getting
 * the order backwards is the single most common typo in a forbidden-zone list, and silently
 * producing an empty region — which is what an ordered constructor would do — turns a safety zone
 * into no zone at all. So the order is normalised rather than trusted or rejected.
 *
 * <p><b>It does not throw.</b> Ranges are declared in {@code public static final} fields alongside
 * the configs they guard, and a throw from that context is an {@code ExceptionInInitializerError}
 * that kills the robot with no message. A malformed range instead reports itself through {@link
 * #problems()}.
 *
 * @param min the inclusive lower bound, in user units
 * @param max the inclusive upper bound, in user units
 * @param unitLabel the unit these bounds are in — {@code "m"} or {@code "deg"}, matching {@link
 *     Axis#unitLabel()}
 */
public record Range(double min, double max, String unitLabel) {

  /**
   * Normalises the bound order and substitutes a placeholder label for an absent one. Never throws;
   * see the class javadoc.
   */
  public Range {
    if (unitLabel == null) {
      unitLabel = "?";
    }
    if (min > max) {
      double swap = min;
      min = max;
      max = swap;
    }
  }

  /**
   * A span of a linear axis, from two distances in either order. The bounds are stored in metres,
   * which is a linear axis's user unit.
   *
   * @param a one bound
   * @param b the other bound
   * @return the range, labelled {@code "m"}
   */
  public static Range of(Distance a, Distance b) {
    return new Range(a.in(Meters), b.in(Meters), "m");
  }

  /**
   * A span of a rotary axis, from two angles in either order. The bounds are stored in
   * <b>degrees</b>, which is a rotary axis's user unit (decision P3) — not radians.
   *
   * @param a one bound
   * @param b the other bound
   * @return the range, labelled {@code "deg"}
   */
  public static Range of(Angle a, Angle b) {
    return new Range(a.in(Degrees), b.in(Degrees), "deg");
  }

  /**
   * A span already expressed in some axis's user units.
   *
   * <p>The escape hatch for code that is already working in doubles — the mechanism layer, a
   * telemetry replay, a soft limit that came out of {@code MechanismUnits}. Prefer the {@code
   * Measure} overloads at a config boundary, where the point is that the reader can see the unit.
   *
   * @param min one bound, in user units
   * @param max the other bound, in user units
   * @param unitLabel the unit label, e.g. {@code axis.unitLabel()}
   * @return the range
   */
  public static Range of(double min, double max, String unitLabel) {
    return new Range(min, max, unitLabel);
  }

  /**
   * A span in the user units of a given axis.
   *
   * @param min one bound, in the axis's user units
   * @param max the other bound, in the axis's user units
   * @param axis the axis whose unit label to carry
   * @return the range
   */
  public static Range on(double min, double max, Axis axis) {
    return new Range(min, max, axis.unitLabel());
  }

  /**
   * Whether a value lies inside this range, bounds included.
   *
   * @param value the value, in the same user units as the bounds
   * @return true if {@code min <= value <= max}
   */
  public boolean contains(double value) {
    return value >= min && value <= max;
  }

  /**
   * Whether another range lies entirely inside this one.
   *
   * @param other the candidate sub-range
   * @return true if it is contained
   */
  public boolean contains(Range other) {
    return other.min >= min && other.max <= max;
  }

  /**
   * Whether this range and another share at least one point — the test a forbidden-zone check runs
   * on each axis of a bounding box.
   *
   * @param other the other range
   * @return true if they touch or overlap
   */
  public boolean overlaps(Range other) {
    return min <= other.max && other.min <= max;
  }

  /**
   * The common part of this range and another.
   *
   * @param other the other range
   * @return the intersection, or empty if they are disjoint
   */
  public Optional<Range> intersect(Range other) {
    if (!overlaps(other)) {
      return Optional.empty();
    }
    return Optional.of(new Range(Math.max(min, other.min), Math.min(max, other.max), unitLabel));
  }

  /**
   * Confines a value to this range.
   *
   * @param value the value to clamp, in user units
   * @return the clamped value
   */
  public double clamp(double value) {
    return Math.min(max, Math.max(min, value));
  }

  /**
   * How wide this range is.
   *
   * @return {@code max - min}, always non-negative
   */
  public double width() {
    return max - min;
  }

  /**
   * The midpoint of this range.
   *
   * @return {@code (min + max) / 2}
   */
  public double centre() {
    return (min + max) / 2.0;
  }

  /**
   * This range grown by an amount on each side — the margin a route planner puts around a forbidden
   * zone before picking escape corners.
   *
   * @param amount the amount to grow by on each side, in user units; may be negative to shrink
   * @return the grown range, collapsed to its centre if a negative amount would invert it
   */
  public Range expandedBy(double amount) {
    double newMin = min - amount;
    double newMax = max + amount;
    if (newMin > newMax) {
      double mid = centre();
      return new Range(mid, mid, unitLabel);
    }
    return new Range(newMin, newMax, unitLabel);
  }

  /**
   * The same span as a unit-free {@link Interval}, for the maths that does not care about labels.
   *
   * @return the interval
   * @throws IllegalArgumentException if a bound is NaN — {@link Interval} rejects those, so check
   *     {@link #problems()} first if the range came from unvalidated config
   */
  public Interval toInterval() {
    return new Interval(min, max);
  }

  /**
   * Everything structurally wrong with this range, or an empty array if it is well formed.
   *
   * <p>Returned rather than thrown for the reason in the class javadoc. The only thing a range can
   * get wrong once the order is normalised is a NaN bound, which almost always means an unset
   * config field reached this constructor.
   *
   * @return the problems, empty when the range is usable
   */
  public List<String> problems() {
    if (Double.isNaN(min) || Double.isNaN(max)) {
      return List.of(
          String.format(
              Locale.ROOT,
              "Range bounds were [%s, %s] %s; neither bound may be NaN. "
                  + "Fix: a NaN bound almost always means a config field that was never set reached "
                  + "this range — check the value you passed in.",
              min,
              max,
              unitLabel));
    }
    return List.of();
  }

  /**
   * The range in one line, with its unit, for {@code describe()} output and alert text.
   *
   * @return e.g. {@code "[0.000, 0.229] m"}
   */
  public String describe() {
    return String.format(Locale.ROOT, "[%.3f, %.3f] %s", min, max, unitLabel);
  }

  @Override
  public String toString() {
    return "Range" + describe();
  }
}

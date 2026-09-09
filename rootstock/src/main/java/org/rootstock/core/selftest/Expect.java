package org.rootstock.core.selftest;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Unit;
import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Current;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.rootstock.core.health.HealthMonitor;

/**
 * One expectation inside a {@link SelfTestRoutine} — the thing that turns "the arm moved" into "the
 * arm reached 90.0 deg, and here is the number it actually reached".
 *
 * <p>An {@code Expect} is <strong>single-use and stateful</strong>: {@link #begin()} takes the
 * baseline, {@link #sample()} runs once per loop while the step (or the whole routine) is in flight,
 * and {@link #satisfied()} plus {@link #observed()} produce the row that ends up on the pit screen.
 * That is why routines are built from a {@code Supplier} for every run rather than cached — a reused
 * expectation would report the previous run's numbers.
 *
 * <p>Every message names the expectation and the observation, so a FAIL row is actionable without
 * anyone opening the log.
 */
public final class Expect {

  /** What kind of expectation this is, for the summary and for the routine to know where to run it. */
  public enum Kind {
    /** A measurement must settle within tolerance of a target before the step times out. */
    SETTLES,
    /** A measurement must change by at least some amount — catches a sensor reading a constant. */
    MOVED,
    /** Current must stay inside a band for the whole routine. */
    CURRENT_BETWEEN,
    /** No new health fault fingerprint may appear during the routine. */
    NO_NEW_FAULTS
  }

  private final Kind m_kind;
  private final String m_description;
  private final Runnable m_begin;
  private final Runnable m_sample;
  private final BooleanSupplier m_satisfied;
  private final Supplier<String> m_observed;

  private Expect(
      Kind kind,
      String description,
      Runnable begin,
      Runnable sample,
      BooleanSupplier satisfied,
      Supplier<String> observed) {
    m_kind = kind;
    m_description = description;
    m_begin = begin;
    m_sample = sample;
    m_satisfied = satisfied;
    m_observed = observed;
  }

  /**
   * Assert a measured quantity settles within tolerance of a target before the step times out.
   *
   * @param label what is being measured, e.g. {@code "angle"}
   * @param measured the live measurement
   * @param target the value it should reach
   * @param tolerance how close counts as reached; must be positive
   * @param unitLabel the unit, printed in the message, e.g. {@code "deg"}
   * @return the expectation
   * @throws IllegalArgumentException if {@code measured} is null or {@code tolerance} is not
   *     positive
   */
  public static Expect settles(
      String label, DoubleSupplier measured, double target, double tolerance, String unitLabel) {
    if (measured == null) {
      throw new IllegalArgumentException("Expect.settles(\"" + label + "\"): measured is null.");
    }
    if (!(tolerance > 0.0)) {
      throw new IllegalArgumentException(
          "Expect.settles(\""
              + label
              + "\"): tolerance must be positive; "
              + tolerance
              + " can never be met. Use the mechanism's real repeatability, e.g. 2 deg.");
    }
    String unit = unitLabel == null ? "" : unitLabel;
    boolean[] met = {false};
    double[] closest = {Double.NaN};
    double[] last = {Double.NaN};
    Runnable sample =
        () -> {
          double v = measured.getAsDouble();
          last[0] = v;
          double err = Math.abs(v - target);
          if (Double.isNaN(closest[0]) || err < Math.abs(closest[0] - target)) {
            closest[0] = v;
          }
          if (err <= tolerance) {
            met[0] = true;
          }
        };
    return new Expect(
        Kind.SETTLES,
        String.format("%s settles at %.3f %s (+/- %.3f %s)", label, target, unit, tolerance, unit),
        () -> {
          met[0] = false;
          closest[0] = Double.NaN;
          last[0] = Double.NaN;
        },
        sample,
        () -> met[0],
        () ->
            met[0]
                ? String.format("reached %.3f %s", closest[0], unit)
                : String.format(
                    "never got closer than %.3f %s (last %.3f %s, off by %.3f %s)",
                    closest[0], unit, last[0], unit, Math.abs(closest[0] - target), unit));
  }

  /**
   * Unit-safe form of {@link #settles(String, DoubleSupplier, double, double, String)}.
   *
   * <p>Everything is compared in the target's own unit, so the message reads in degrees when the
   * target was written in degrees.
   *
   * @param <U> the unit type
   * @param label what is being measured
   * @param measured the live measurement
   * @param target the value it should reach
   * @param tolerance how close counts as reached
   * @return the expectation
   * @throws IllegalArgumentException if any argument is null or the tolerance is not positive
   */
  public static <U extends Unit> Expect settles(
      String label, Supplier<Measure<U>> measured, Measure<U> target, Measure<U> tolerance) {
    if (measured == null || target == null || tolerance == null) {
      throw new IllegalArgumentException(
          "Expect.settles(\"" + label + "\"): measured, target and tolerance are all required.");
    }
    U unit = target.unit();
    return settles(
        label,
        () -> measured.get().in(unit),
        target.in(unit),
        Math.abs(tolerance.in(unit)),
        unit.symbol());
  }

  /**
   * Assert something changed at all.
   *
   * <p>This is the check that catches a disconnected encoder, which reads a perfect constant that
   * every closed loop happily believes. A mechanism that "reached its target instantly" and a
   * mechanism whose sensor is unplugged look identical to a settling check and completely different
   * to this one.
   *
   * @param label what is being measured
   * @param measured the live measurement
   * @param minDelta the smallest change that counts as movement; must be positive
   * @param unitLabel the unit, printed in the message
   * @return the expectation
   * @throws IllegalArgumentException if {@code measured} is null or {@code minDelta} is not positive
   */
  public static Expect moved(
      String label, DoubleSupplier measured, double minDelta, String unitLabel) {
    if (measured == null) {
      throw new IllegalArgumentException("Expect.moved(\"" + label + "\"): measured is null.");
    }
    if (!(minDelta > 0.0)) {
      throw new IllegalArgumentException(
          "Expect.moved(\"" + label + "\"): minDelta must be positive; " + minDelta + " is met by "
              + "doing nothing, which defeats the check.");
    }
    String unit = unitLabel == null ? "" : unitLabel;
    double[] baseline = {Double.NaN};
    double[] maxDelta = {0.0};
    return new Expect(
        Kind.MOVED,
        String.format("%s moves at least %.3f %s", label, minDelta, unit),
        () -> {
          baseline[0] = measured.getAsDouble();
          maxDelta[0] = 0.0;
        },
        () -> {
          double v = measured.getAsDouble();
          if (Double.isNaN(baseline[0])) {
            baseline[0] = v;
          }
          maxDelta[0] = Math.max(maxDelta[0], Math.abs(v - baseline[0]));
        },
        () -> maxDelta[0] >= minDelta,
        () ->
            String.format(
                "moved %.4f %s from %.4f %s%s",
                maxDelta[0],
                unit,
                baseline[0],
                unit,
                maxDelta[0] < minDelta
                    ? " - a sensor that never changes is usually a sensor that is unplugged"
                    : ""));
  }

  /**
   * Assert current stays inside a band for the whole routine.
   *
   * <p>Both ends matter. Above the band means something is binding; <em>below</em> it means the
   * motor is not actually driving — a disconnected phase lead or a motor that was never told to
   * move looks like a perfect pass to every position check and draws two amps.
   *
   * @param amps stator current
   * @param min the lowest acceptable draw while the routine runs
   * @param max the highest acceptable draw
   * @return the expectation
   * @throws IllegalArgumentException if any argument is null or the band is inverted
   */
  public static Expect currentBetween(DoubleSupplier amps, Current min, Current max) {
    if (amps == null || min == null || max == null) {
      throw new IllegalArgumentException(
          "Expect.currentBetween: amps, min and max are all required.");
    }
    double lo = min.in(Units.Amps);
    double hi = max.in(Units.Amps);
    if (lo >= hi) {
      throw new IllegalArgumentException(
          "Expect.currentBetween("
              + lo
              + " A, "
              + hi
              + " A): min must be below max. A band a mechanism can never be inside makes the "
              + "self-test always red.");
    }
    double[] seenMin = {Double.POSITIVE_INFINITY};
    double[] seenMax = {Double.NEGATIVE_INFINITY};
    int[] outside = {0};
    int[] samples = {0};
    return new Expect(
        Kind.CURRENT_BETWEEN,
        String.format("current stays between %.1f A and %.1f A", lo, hi),
        () -> {
          seenMin[0] = Double.POSITIVE_INFINITY;
          seenMax[0] = Double.NEGATIVE_INFINITY;
          outside[0] = 0;
          samples[0] = 0;
        },
        () -> {
          double a = amps.getAsDouble();
          if (Double.isNaN(a)) {
            return;
          }
          samples[0]++;
          seenMin[0] = Math.min(seenMin[0], a);
          seenMax[0] = Math.max(seenMax[0], a);
          if (a < lo || a > hi) {
            outside[0]++;
          }
        },
        () -> samples[0] > 0 && outside[0] == 0,
        () -> {
          if (samples[0] == 0) {
            return "no current samples were taken";
          }
          return String.format(
              "saw %.1f A to %.1f A over %d samples%s",
              seenMin[0],
              seenMax[0],
              samples[0],
              outside[0] == 0
                  ? ""
                  : String.format(
                      " - %d outside the band. Low means the motor is not driving (check the "
                          + "phase leads); high means something is binding.",
                      outside[0]));
        });
  }

  /**
   * Assert the routine caused no new health faults.
   *
   * <p>Diffs {@link HealthMonitor#faultFingerprints()} taken at the start of the routine against the
   * set at the end. A step that reaches its target while tripping a stall detector has not passed.
   *
   * @return the expectation
   */
  public static Expect noNewFaults() {
    Set<String> before = new LinkedHashSet<>();
    Set<String> added = new LinkedHashSet<>();
    return new Expect(
        Kind.NO_NEW_FAULTS,
        "no new health faults during the routine",
        () -> {
          before.clear();
          before.addAll(HealthMonitor.faultFingerprints());
          added.clear();
        },
        () -> {
          for (String fp : HealthMonitor.faultFingerprints()) {
            if (!before.contains(fp)) {
              added.add(fp);
            }
          }
        },
        added::isEmpty,
        () ->
            added.isEmpty()
                ? "no new faults"
                : added.size() + " new fault(s): " + String.join("; ", added));
  }

  /**
   * What this expectation asserts, in words. Printed as the {@code expectation} column of a step
   * result.
   *
   * @return the description
   */
  public String description() {
    return m_description;
  }

  /**
   * What kind of expectation this is.
   *
   * @return the kind
   */
  public Kind kind() {
    return m_kind;
  }

  /**
   * Whether this expectation is evaluated across the whole routine rather than within one step.
   *
   * @return true for {@link Kind#CURRENT_BETWEEN} and {@link Kind#NO_NEW_FAULTS}
   */
  public boolean isRoutineWide() {
    return m_kind == Kind.CURRENT_BETWEEN || m_kind == Kind.NO_NEW_FAULTS;
  }

  /**
   * Whether the expectation is currently met.
   *
   * @return true if satisfied
   */
  public boolean satisfied() {
    return m_satisfied.getAsBoolean();
  }

  /**
   * What was actually observed, for the {@code observed} column of a step result. Valid after at
   * least one {@link #sample()}.
   *
   * @return the observation
   */
  public String observed() {
    return m_observed.get();
  }

  /** Take the baseline. Called once, when the step or routine starts. */
  void begin() {
    m_begin.run();
  }

  /** Take one sample. Called once per loop while the step or routine is in flight. */
  void sample() {
    m_sample.run();
  }

  @Override
  public String toString() {
    return m_description;
  }
}

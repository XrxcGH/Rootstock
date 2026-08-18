package org.pumpkinlib.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.units.SiDomain;

/**
 * The one property the whole tuning domain rests on: <b>the supervisor's abort band is strictly
 * inside the device's own soft limits, for every legal mechanism shape.</b>
 *
 * <p><strong>Why this needs a numeric proof and not a spot check.</strong> Two earlier forms of this
 * rule were both plausible and both silently produced a zero-width guard, at opposite ends of the
 * margin range:
 *
 * <ul>
 *   <li>pulling in by a <em>fraction of the team's margin</em> collapses to nothing when the margin
 *       is zero, which was the default;
 *   <li>{@code max(softMargin, 0.05 * range)} collapses to exactly {@code softMargin} the moment a
 *       careful team configures a generous margin — which is what a careful team does.
 * </ul>
 *
 * <p>In both failures the supervisor band <em>equals</em> the device band, the device clamps, and
 * the student is told nothing at all. Neither failure throws, neither logs, and a test that checked
 * one mechanism with one margin would have passed. So this class sweeps the whole legal space —
 * every range from a 9 mm stage to a 3 m elevator, every margin from the 2% floor to the 40%
 * ceiling, both SI domains — and asserts strict containment at every point, plus recomputes the
 * guard from the constants rather than copying the numbers the code produced.
 *
 * <p>Native-free: {@link SafetyEnvelope#derive} reads a {@link TravelLimits}, a {@link PlantPrior}
 * and an {@link SiDomain} and does arithmetic. Nothing here touches the HAL.
 */
final class SafetyEnvelopeContainmentTest {

  /** The ranges swept, in SI: a 9 mm stage through a 3 m elevator, and a 30 degree wrist. */
  private static final double[] kRanges = {
    0.009, 0.02, 0.04, 0.12, 0.5236, 0.8, 1.397, 2.0, 3.0, 6.283185307179586
  };

  /** Margin fractions swept, spanning the legal band inclusive of both ends, plus zero. */
  private static final double[] kMarginFractions = {
    0.0, 0.005, TravelLimits.MIN_MARGIN_FRACTION, 0.05, 0.10, 0.25, TravelLimits.MAX_MARGIN_FRACTION
  };

  /** Builds a target whose only interesting properties are its limits and its domain. */
  private static FakeTarget targetWith(TravelLimits limits, SiDomain domain) {
    return new FakeTarget().named("Sweep").limits(limits).domain(domain);
  }

  /** The guard the design specifies, recomputed here from the published constants. */
  private static double expectedGuard(TravelLimits limits, SiDomain domain) {
    double floor =
        domain == SiDomain.LINEAR_METERS
            ? SafetyEnvelope.MIN_GUARD_METRES
            : SafetyEnvelope.MIN_GUARD_RADIANS;
    return limits.softMargin()
        + Math.max(SafetyEnvelope.GUARD_FRACTION_OF_TRAVEL * limits.range(), floor);
  }

  @Nested
  @DisplayName("strict containment over the whole legal space")
  final class Containment {

    @Test
    @DisplayName("the abort band is strictly inside the soft limits at every range and margin")
    void everyShapeIsStrictlyContained() {
      List<String> failures = new ArrayList<>();
      int checked = 0;

      for (SiDomain domain : SiDomain.values()) {
        for (double range : kRanges) {
          for (double fraction : kMarginFractions) {
            TravelLimits limits = new TravelLimits(0.0, range, fraction * range);
            SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, domain));
            checked++;

            double guard = expectedGuard(limits, domain);
            if (envelope.positionMin() != limits.min() + guard
                || envelope.positionMax() != limits.max() - guard) {
              failures.add(
                  String.format(
                      Locale.ROOT,
                      "%s range=%.4f margin=%.4f: expected band [%.6f, %.6f], got [%.6f, %.6f]",
                      domain,
                      range,
                      limits.softMargin(),
                      limits.min() + guard,
                      limits.max() - guard,
                      envelope.positionMin(),
                      envelope.positionMax()));
              continue;
            }
            if (!(envelope.positionMin() > limits.softMin())
                || !(envelope.positionMax() < limits.softMax())) {
              failures.add(
                  String.format(
                      Locale.ROOT,
                      "%s range=%.4f margin=%.4f: band [%.6f, %.6f] is NOT strictly inside the "
                          + "soft limits [%.6f, %.6f] — the device would clamp with no message",
                      domain,
                      range,
                      limits.softMargin(),
                      envelope.positionMin(),
                      envelope.positionMax(),
                      limits.softMin(),
                      limits.softMax()));
            }
          }
        }
      }

      assertEquals(
          SiDomain.values().length * kRanges.length * kMarginFractions.length,
          checked,
          "every combination in the sweep must actually have been evaluated");
      assertTrue(failures.isEmpty(), () -> String.join(System.lineSeparator(), failures));
    }

    @Test
    @DisplayName("the extra guard is strictly positive for every legal limit set")
    void theExtraGuardIsNeverZero() {
      for (SiDomain domain : SiDomain.values()) {
        for (double range : kRanges) {
          for (double fraction : kMarginFractions) {
            TravelLimits limits = new TravelLimits(0.0, range, fraction * range);
            SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, domain));
            double extra = envelope.positionMin() - limits.softMin();
            double floor =
                domain == SiDomain.LINEAR_METERS
                    ? SafetyEnvelope.MIN_GUARD_METRES
                    : SafetyEnvelope.MIN_GUARD_RADIANS;
            assertTrue(
                extra >= Math.min(floor, SafetyEnvelope.GUARD_FRACTION_OF_TRAVEL * range) - 1e-12,
                () ->
                    "extra guard "
                        + extra
                        + " collapsed for "
                        + domain
                        + " range "
                        + range
                        + " margin fraction "
                        + fraction);
            assertTrue(extra > 0.0, "the extra guard must be strictly positive, never zero");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("the two rejected forms, disproved numerically")
  final class RejectedForms {

    /**
     * The generous-margin failure. 0.10 m of margin on 0.8 m of travel is 12.5%, well above the 5%
     * the {@code max} form used, so that form picks the margin and adds nothing at all.
     */
    @Test
    @DisplayName("max(softMargin, 5% of range) gives exactly zero extra guard at a 12.5% margin")
    void theMaxFormCollapsesAtAGenerousMargin() {
      TravelLimits limits = new TravelLimits(0.0, 0.8, 0.10);
      double maxForm = Math.max(limits.softMargin(), 0.05 * limits.range());

      assertEquals(
          limits.softMargin(),
          maxForm,
          0.0,
          "the rejected max form is exactly the team's own margin here, so its band equals the "
              + "device band and the supervisor never trips first");

      SafetyEnvelope shipped = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));
      double shippedGuard = shipped.positionMin() - limits.min();
      assertEquals(0.10 + 0.03 * 0.8, shippedGuard, 1e-12);
      assertTrue(
          shippedGuard > maxForm,
          "the shipped additive form must beat the rejected max form at a generous margin");
      assertEquals(0.024, shipped.positionMin() - limits.softMin(), 1e-12);
    }

    /**
     * The zero-margin failure. A fraction of a zero margin is zero, whatever the fraction.
     */
    @Test
    @DisplayName("a fraction of the margin gives exactly zero extra guard at the default margin")
    void theFractionOfMarginFormCollapsesAtZeroMargin() {
      TravelLimits limits = new TravelLimits(0.0, 1.4, 0.0);
      for (double fraction : new double[] {0.1, 0.25, 0.5, 0.9}) {
        assertEquals(
            0.0,
            fraction * limits.softMargin(),
            0.0,
            "any fraction of a zero margin is zero, which is why that form was rejected");
      }

      SafetyEnvelope shipped = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));
      assertEquals(0.042, shipped.positionMin() - limits.softMin(), 1e-12);
      assertTrue(shipped.positionMin() > limits.softMin());
      assertTrue(shipped.positionMax() < limits.softMax());
    }
  }

  @Nested
  @DisplayName("the guard floor, and the one shape that genuinely has no band")
  final class Degenerate {

    @Test
    @DisplayName("a short linear axis takes the 5 mm floor, not 3% of a tiny range")
    void theLinearFloorApplies() {
      TravelLimits limits = new TravelLimits(0.0, 0.02, 0.02 * TravelLimits.MIN_MARGIN_FRACTION);
      SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));

      assertTrue(
          SafetyEnvelope.GUARD_FRACTION_OF_TRAVEL * limits.range()
              < SafetyEnvelope.MIN_GUARD_METRES,
          "3% of 20 mm is 0.6 mm, which is smaller than the 5 mm floor — this is the case the "
              + "floor exists for");
      assertEquals(
          limits.softMargin() + SafetyEnvelope.MIN_GUARD_METRES,
          envelope.positionMin() - limits.min(),
          1e-12);
      assertTrue(envelope.positionMin() > limits.softMin());
    }

    @Test
    @DisplayName("a short rotary axis takes the 20 mrad floor")
    void theRotaryFloorApplies() {
      TravelLimits limits = new TravelLimits(0.0, 0.3, 0.3 * TravelLimits.MIN_MARGIN_FRACTION);
      SafetyEnvelope envelope =
          SafetyEnvelope.derive(targetWith(limits, SiDomain.ROTATIONAL_RADIANS));

      assertTrue(
          SafetyEnvelope.GUARD_FRACTION_OF_TRAVEL * limits.range()
              < SafetyEnvelope.MIN_GUARD_RADIANS,
          "3% of 0.3 rad is 9 mrad, below the 20 mrad floor");
      assertEquals(
          limits.softMargin() + SafetyEnvelope.MIN_GUARD_RADIANS,
          envelope.positionMin() - limits.min(),
          1e-12);
      assertTrue(envelope.positionMin() > limits.softMin());
    }

    @Test
    @DisplayName("a 9 mm stage has no band at all, and says so instead of inverting silently")
    void anImpossiblyShortAxisIsReportedRatherThanInverted() {
      TravelLimits limits = new TravelLimits(0.0, 0.009, 0.009 * TravelLimits.MIN_MARGIN_FRACTION);
      SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));

      double guard = expectedGuard(limits, SiDomain.LINEAR_METERS);
      assertTrue(2.0 * guard > limits.range(), "the guard must not fit twice in 9 mm");
      assertTrue(envelope.bandWidth() <= 0.0, "the band is empty, which is the whole point");

      List<String> problems = envelope.validate("TinyStage", limits);
      assertEquals(1, problems.size(), "exactly one message, naming the one way this can fail");
      assertTrue(problems.get(0).startsWith("TinyStage:"), problems.get(0));
      assertTrue(
          problems.get(0).contains("Fix:"),
          "a config message a student reads at 11pm must name the fix");
    }

    @Test
    @DisplayName("a legal band reports no problems")
    void aLegalBandValidatesClean() {
      TravelLimits limits = new TravelLimits(0.0, 1.4, 0.04);
      SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));
      assertTrue(envelope.validate("Elevator", limits).isEmpty());
      assertTrue(envelope.bandWidth() > 0.0);
    }

    @Test
    @DisplayName("the unbounded limits a flywheel uses still produce a usable band")
    void unboundedLimitsStillValidate() {
      TravelLimits limits = TravelLimits.unbounded();
      SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, SiDomain.ROTATIONAL_RADIANS));
      assertTrue(limits.validate("Flywheel").isEmpty());
      assertTrue(envelope.validate("Flywheel", limits).isEmpty());
      assertTrue(envelope.bandWidth() > 0.0);
    }
  }

  @Nested
  @DisplayName("the band edges themselves")
  final class Edges {

    @Test
    @DisplayName("containsPosition is inclusive at both edges and false just outside")
    void theEdgesAreInclusive() {
      TravelLimits limits = new TravelLimits(0.0, 1.4, 0.04);
      SafetyEnvelope envelope = SafetyEnvelope.derive(targetWith(limits, SiDomain.LINEAR_METERS));

      assertTrue(envelope.containsPosition(envelope.positionMin()));
      assertTrue(envelope.containsPosition(envelope.positionMax()));
      assertFalse(envelope.containsPosition(Math.nextDown(envelope.positionMin())));
      assertFalse(envelope.containsPosition(Math.nextUp(envelope.positionMax())));

      // And the whole supervisor band is a strict subset of the device's soft band.
      assertTrue(limits.insideSoft(envelope.positionMin()));
      assertTrue(limits.insideSoft(envelope.positionMax()));
      assertFalse(envelope.containsPosition(limits.softMin()));
      assertFalse(envelope.containsPosition(limits.softMax()));
    }

    @Test
    @DisplayName("the legal margin window is exactly 2% to 40% of travel, inclusive")
    void theMarginWindowIsWhatTheEnvelopeAssumes() {
      double range = 1.4;
      assertTrue(
          new TravelLimits(0.0, range, TravelLimits.MIN_MARGIN_FRACTION * range)
              .validate("Elevator")
              .isEmpty());
      assertTrue(
          new TravelLimits(0.0, range, TravelLimits.MAX_MARGIN_FRACTION * range)
              .validate("Elevator")
              .isEmpty());
      assertEquals(
          1,
          new TravelLimits(0.0, range, 0.0).validate("Elevator").size(),
          "a zero margin is rejected by the limits, which is what keeps the envelope honest");
      assertEquals(
          1,
          new TravelLimits(0.0, range, 0.5 * range).validate("Elevator").size(),
          "a margin above 40% leaves no band");
    }
  }
}

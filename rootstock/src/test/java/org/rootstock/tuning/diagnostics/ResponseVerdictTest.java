package org.rootstock.tuning.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.tuning.sysid.SampleBuffer;

/**
 * The step-response classifier, driven from responses whose damping is known by construction.
 *
 * <p><strong>Why synthetic responses and not recorded ones.</strong> The classifier's whole value is
 * that it says <em>which gain to move and which way</em>. A recording proves only that it produced
 * some label; a second-order response built from an explicit damping ratio proves it produced the
 * <em>right</em> label, because the shape is known before the analyser sees it. Every window below
 * comes from
 *
 * <pre>
 *   y(t) = A * (1 - e^(-z*wn*t) * (cos(wd*t) + z/sqrt(1-z^2) * sin(wd*t))),   wd = wn*sqrt(1-z^2)
 * </pre>
 *
 * <p>which is the textbook unit step of a second-order system, sampled at the 50 Hz the robot loop
 * runs at.
 *
 * <p><strong>One classification is recorded as a gap rather than as a pass.</strong> {@link
 * ResponseClass#STEADY_STATE_ERROR} — the one the design calls the most valuable diagnosis in the
 * package, because it is what turns "it stops short" into "raise kG", not "raise kP" — cannot fire
 * for a response that settles to a genuine constant offset. The arithmetic is proved in {@link
 * SteadyStateReachability} below: settling requires the error to be inside {@code max(2% of the
 * step, tolerance)} while the rule requires it to exceed {@code max(tolerance, 3% of the step)}, and
 * the second is never smaller than the first. A mechanism that settles 10% low is therefore reported
 * as {@code SLUGGISH}, whose advice is "turn kP up" — the opposite of what it needs. That is
 * asserted here as observed behaviour, with the arithmetic beside it, so the gap is visible instead
 * of absent.
 *
 * <p>Native-free: the analyser takes a list of records and returns a record.
 */
final class ResponseVerdictTest {

  /** The robot loop period the windows are sampled at. */
  private static final double kDt = 0.02;

  /** The commanded step used everywhere below, in metres. */
  private static final double kStep = 0.30;

  /** The mechanism tolerance used everywhere below, in metres. */
  private static final double kTolerance = 0.005;

  /**
   * A textbook second-order step response, sampled at 50 Hz.
   *
   * @param zeta the damping ratio the shape is built from
   * @param wn the undamped natural frequency, rad/s
   * @param seconds how long the window runs
   * @param finalScale what fraction of the target it converges to, for a deliberate steady offset
   * @param feedbackVolts the feedback-only voltage to report, or NaN for "not separable"
   * @return the window, oldest first
   */
  private static List<SampleBuffer.Sample> stepResponse(
      double zeta, double wn, double seconds, double finalScale, double feedbackVolts) {
    List<SampleBuffer.Sample> window = new ArrayList<>();
    double wd = wn * Math.sqrt(Math.max(1e-9, 1.0 - zeta * zeta));
    for (int i = 0; i * kDt <= seconds; i++) {
      double t = i * kDt;
      double shape;
      if (zeta < 1.0) {
        shape =
            1.0
                - Math.exp(-zeta * wn * t)
                    * (Math.cos(wd * t) + zeta / Math.sqrt(1.0 - zeta * zeta) * Math.sin(wd * t));
      } else {
        shape = 1.0 - Math.exp(-wn * t) * (1.0 + wn * t);
      }
      double y = kStep * finalScale * shape;
      window.add(new SampleBuffer.Sample(t, kStep, y, 0.0, 3.0, 0.0, feedbackVolts));
    }
    return window;
  }

  /** Runs the analyser with the standard expectations for this plant. */
  private static ResponseVerdict analyse(List<SampleBuffer.Sample> window, boolean gravityLoaded) {
    return StepResponseAnalyzer.analyze(window, kTolerance, 0.18, 0.5, 12.0, gravityLoaded);
  }

  @Nested
  @DisplayName("the six shapes the classifier can produce from a real move")
  final class Shapes {

    @Test
    @DisplayName("zeta 0.7 arrives with 4.6% overshoot and is called GOOD")
    void wellDamped() {
      ResponseVerdict v = analyse(stepResponse(0.70, 12.0, 3.0, 1.0, 0.0), false);

      assertEquals(ResponseClass.GOOD, v.classification(), v.describe());
      assertTrue(v.isGood());
      assertTrue(v.settled());
      // The analytic overshoot of a zeta = 0.7 second-order step is exp(-pi*z/sqrt(1-z^2)).
      double analytic = 100.0 * Math.exp(-Math.PI * 0.70 / Math.sqrt(1.0 - 0.49));
      assertEquals(analytic, v.overshootPct(), 0.5, "measured overshoot must match the shape");
      assertTrue(analytic <= 8.0, "4.6% is inside the 8% the GOOD rule allows");
      assertEquals("On target, in time, no bounce", v.headline());
      assertTrue(v.recommendation().startsWith("Nothing to change"));
      assertFalse(v.saturated());
    }

    @Test
    @DisplayName("zeta 0.25 overshoots 44% and rings, and is called OVERSHOOT_RING")
    void underDampedRings() {
      ResponseVerdict v = analyse(stepResponse(0.25, 12.0, 3.0, 1.0, 0.0), false);

      assertEquals(ResponseClass.OVERSHOOT_RING, v.classification(), v.describe());
      double analytic = 100.0 * Math.exp(-Math.PI * 0.25 / Math.sqrt(1.0 - 0.0625));
      assertEquals(analytic, v.overshootPct(), 1.0);
      assertTrue(v.overshootPct() > 15.0);
      assertEquals("Overshoots and rings", v.headline());
      assertTrue(
          v.recommendation().contains("kD"),
          "the fix for a spring with no damper is a damper, and the text must say so");
      assertTrue(v.oscillationCrossings() >= 4);
    }

    @Test
    @DisplayName("zeta 0.05 never settles inside the window and is called OSCILLATING")
    void barelyDampedOscillates() {
      ResponseVerdict v = analyse(stepResponse(0.05, 25.0, 2.0, 1.0, 0.0), false);

      assertEquals(ResponseClass.OSCILLATING, v.classification(), v.describe());
      assertFalse(v.settled(), "the rule requires the response to still be swinging at the end");
      assertTrue(v.oscillationCrossings() >= 4);
      assertTrue(v.dampingRatio() < 0.15, "measured zeta " + v.dampingRatio());
      assertTrue(
          v.oscillationHz() > 0 && v.oscillationHz() < StepResponseAnalyzer.kNoiseFrequencyHz,
          "below 8 Hz this is the mechanism moving, so the advice must be about kP, not kD: "
              + v.oscillationHz());
      assertTrue(v.recommendation().contains("Cut kP"), v.recommendation());
      assertTrue(v.diagnosis().contains("kP that is too high"), v.diagnosis());
    }

    @Test
    @DisplayName("a critically damped but slow arrival is called SLUGGISH")
    void overDampedIsSluggish() {
      ResponseVerdict v = analyse(stepResponse(1.0, 1.2, 6.0, 1.0, 0.0), false);

      assertEquals(ResponseClass.SLUGGISH, v.classification(), v.describe());
      assertTrue(
          v.riseTimeSec() > 2.5 * 0.18,
          "the rule fires on a rise time more than 2.5x the expected one: " + v.riseTimeSec());
      assertTrue(v.overshootPct() < 3.0);
      assertTrue(v.recommendation().contains("1.6"), "the text must name the multiplier the "
          + "refinement step actually applies");
      assertEquals(
          org.rootstock.tuning.wizard.steps.StepResponseStep.kSluggishKpGain,
          1.6,
          0.0,
          "and that multiplier must still be 1.6");
    }

    @Test
    @DisplayName("a growing oscillation is called UNSTABLE and nothing softer")
    void divergenceIsUnstable() {
      List<SampleBuffer.Sample> window = new ArrayList<>();
      for (int i = 0; i * kDt <= 2.0; i++) {
        double t = i * kDt;
        double y = kStep * (1.0 - Math.exp(0.8 * t) * Math.cos(14.0 * t));
        window.add(new SampleBuffer.Sample(t, kStep, y, 0.0, 3.0, 0.0, 0.0));
      }

      ResponseVerdict v = analyse(window, false);
      assertEquals(ResponseClass.UNSTABLE, v.classification(), v.describe());
      assertTrue(v.classification().isDangerous());
      assertFalse(v.classification().shouldRefine(), "a diverging response must stop, not iterate");
      assertTrue(v.recommendation().contains("40%"), v.recommendation());
      assertEquals(
          org.rootstock.tuning.wizard.steps.StepResponseStep.kUnstableRetreat,
          0.4,
          0.0,
          "and the retreat the text promises must be the one the step applies");
    }

    @Test
    @DisplayName("a move smaller than four tolerances teaches nothing and says so")
    void tooSmallToJudge() {
      List<SampleBuffer.Sample> window = new ArrayList<>();
      double tiny = 3.0 * kTolerance; // below the four-tolerance floor
      for (int i = 0; i * kDt <= 2.0; i++) {
        double t = i * kDt;
        window.add(
            new SampleBuffer.Sample(
                t, tiny, tiny * (1.0 - Math.exp(-8.0 * t)), 0.0, 1.0, 0.0, 0.0));
      }

      ResponseVerdict v = analyse(window, false);
      assertEquals(ResponseClass.INSUFFICIENT_EXCITATION, v.classification());
      assertFalse(v.classification().shouldRefine(), "nothing is changed on no information");
      assertTrue(Double.isNaN(v.riseTimeSec()), "every metric is NaN, never a plausible zero");
      assertTrue(Double.isNaN(v.overshootPct()));

      assertEquals(
          4.0,
          StepResponseAnalyzer.kMinimumExcitationTolerances,
          0.0,
          "the floor the test is built on");
      assertTrue(tiny < StepResponseAnalyzer.kMinimumExcitationTolerances * kTolerance);
    }

    @Test
    @DisplayName("a window shorter than half a second is refused before it is classified")
    void tooFewSamples() {
      List<SampleBuffer.Sample> window =
          stepResponse(0.7, 12.0, 3.0, 1.0, 0.0)
              .subList(0, StepResponseAnalyzer.kMinimumSamples - 1);
      assertEquals(ResponseClass.INSUFFICIENT_EXCITATION, analyse(window, false).classification());
    }
  }

  @Nested
  @DisplayName("STEADY_STATE_ERROR: what it catches, and the shape it cannot")
  final class SteadyStateReachability {

    /**
     * The reachable case: the mechanism is still closing the last of its error when the
     * steady-state window opens, and is inside the settling band by the time the hold window does.
     */
    @Test
    @DisplayName("a late arrival with a residual offset is caught, and the advice names kG")
    void aLateArrivalWithResidualErrorIsCaught() {
      List<SampleBuffer.Sample> window = new ArrayList<>();
      double seconds = 3.0;
      double arriveAt = seconds - 0.30;
      double residual = 0.005 * kStep;
      for (int i = 0; i * kDt <= seconds; i++) {
        double t = i * kDt;
        double y;
        if (t >= arriveAt) {
          y = kStep - residual;
        } else {
          // Still travelling: 70% of the step at the moment the steady-state window opens.
          double fraction = Math.min(1.0, t / arriveAt);
          y = kStep * (0.70 + 0.295 * Math.pow(fraction, 8.0));
        }
        window.add(new SampleBuffer.Sample(t, kStep, y, 0.0, 3.0, 0.0, 1.8));
      }

      ResponseVerdict gravity = analyse(window, true);
      assertEquals(ResponseClass.STEADY_STATE_ERROR, gravity.classification(), gravity.describe());
      assertTrue(gravity.settled());
      assertTrue(gravity.diagnosis().contains("gravity"), gravity.diagnosis());
      assertTrue(gravity.recommendation().contains("kG"), gravity.recommendation());
      assertEquals(1.8, gravity.residualVolts(), 1e-9, "the residual is the missing feedforward");

      ResponseVerdict frictionOnly = analyse(window, false);
      assertEquals(ResponseClass.STEADY_STATE_ERROR, frictionOnly.classification());
      assertTrue(frictionOnly.diagnosis().contains("friction"), frictionOnly.diagnosis());
      assertTrue(frictionOnly.recommendation().contains("kS"), frictionOnly.recommendation());
      assertTrue(
          frictionOnly.recommendation().contains("Do NOT reach for kI"),
          "the one place a student would reach for an integrator, and the text talks them out of it");
    }

    /**
     * The gap. A mechanism that settles to a genuine constant offset cannot reach this
     * classification, because the settling band and the steady-state threshold are incompatible.
     */
    @Test
    @DisplayName("the settling band and the steady-state threshold are arithmetically incompatible")
    void aConstantOffsetCannotSatisfyBothRules() {
      double commanded = kStep;
      for (double tolerance : new double[] {0.0005, 0.002, 0.005, 0.02, 0.05}) {
        double settleBand =
            Math.max(StepResponseAnalyzer.kDefaultSettleBand, tolerance / commanded) * commanded;
        double steadyStateThreshold = Math.max(tolerance, 0.03 * commanded);

        assertTrue(
            settleBand <= steadyStateThreshold,
            () ->
                "with tolerance "
                    + tolerance
                    + " the largest error that still counts as settled is "
                    + settleBand
                    + " but the STEADY_STATE_ERROR rule needs more than "
                    + steadyStateThreshold
                    + ", so no constant offset can satisfy both. If this assertion ever fails, "
                    + "somebody widened kDefaultSettleBand past the 3% the rule uses and this "
                    + "test should become the ordinary steady-state test.");
      }
    }

    @Test
    @DisplayName("so a mechanism that settles 10% low is reported as SLUGGISH — the recorded gap")
    void aTenPercentOffsetIsReportedAsSluggish() {
      ResponseVerdict v = analyse(stepResponse(1.0, 6.0, 3.0, 0.90, 1.8), true);

      assertEquals(
          -10.0,
          v.overshootPct(),
          0.5,
          "it really does finish 10% low, which is the shape a missing kG produces");
      assertFalse(v.settled(), "10% low is outside the 2% band, so nothing counts as settled");
      assertEquals(
          ResponseClass.SLUGGISH,
          v.classification(),
          "recorded, not endorsed: the diagnosis a student needs here is 'raise kG', and what "
              + "they are told is 'turn kP up'. See the class javadoc.");
      assertTrue(v.recommendation().contains("kP"), v.recommendation());
    }
  }

  @Nested
  @DisplayName("the metrics themselves")
  final class Metrics {

    @Test
    @DisplayName("rise time is interpolated, not snapped to a 20 ms sample")
    void riseTimeIsInterpolated() {
      ResponseVerdict v = analyse(stepResponse(0.70, 12.0, 3.0, 1.0, 0.0), false);
      double quantised = Math.round(v.riseTimeSec() / kDt) * kDt;
      assertTrue(
          Math.abs(v.riseTimeSec() - quantised) > 1e-6,
          "a snapped rise time would land exactly on a multiple of 20 ms; this one is "
              + v.riseTimeSec());
    }

    @Test
    @DisplayName("the damping ratio comes out of the shape and goes negative when it diverges")
    void dampingRatioTracksTheShape() {
      ResponseVerdict light = analyse(stepResponse(0.05, 25.0, 2.0, 1.0, 0.0), false);
      ResponseVerdict heavier = analyse(stepResponse(0.25, 12.0, 3.0, 1.0, 0.0), false);

      assertTrue(light.dampingRatio() > 0.0);
      assertTrue(
          heavier.dampingRatio() > light.dampingRatio(),
          "more damping in the shape must read as more damping in the metric");

      // A diverging response: successive peaks grow, so the log decrement is negative.
      List<SampleBuffer.Sample> growing = new ArrayList<>();
      for (int i = 0; i * kDt <= 2.0; i++) {
        double t = i * kDt;
        growing.add(
            new SampleBuffer.Sample(
                t, kStep, kStep * (1.0 - Math.exp(0.5 * t) * Math.cos(10.0 * t)), 0, 3.0, 0, 0.0));
      }
      ResponseVerdict diverging = analyse(growing, false);
      assertEquals(ResponseClass.UNSTABLE, diverging.classification());
    }

    @Test
    @DisplayName("a command pinned at the ceiling for 100 ms adds the saturation note")
    void saturationIsNoticedAndExplained() {
      List<SampleBuffer.Sample> window = new ArrayList<>();
      double ceiling = 12.0;
      for (int i = 0; i * kDt <= 3.0; i++) {
        double t = i * kDt;
        double shape = 1.0 - Math.exp(-6.0 * t) * (1.0 + 6.0 * t);
        double volts = t < 0.4 ? ceiling : 1.0;
        window.add(new SampleBuffer.Sample(t, kStep, kStep * shape, 0.0, volts, 0.0, 0.0));
      }

      ResponseVerdict v =
          StepResponseAnalyzer.analyze(window, kTolerance, 0.18, 0.5, ceiling, false);
      assertTrue(v.saturated(), "0.4 s at the ceiling is well past the 0.10 s threshold");
      assertTrue(v.diagnosis().contains("ceiling"), v.diagnosis());
      assertTrue(
          v.diagnosis().contains("kP and kD do nothing"),
          "the note has to say why the reading is uninformative, not just that it happened");
      assertTrue(v.recommendation().contains("motion profile"), v.recommendation());
      assertEquals(ceiling, v.peakVolts(), 1e-9);
    }

    @Test
    @DisplayName("an unseparable feedback split reports NaN residual volts, never zero")
    void anUnknownResidualIsNaN() {
      ResponseVerdict v = analyse(stepResponse(0.70, 12.0, 3.0, 1.0, Double.NaN), false);
      assertTrue(
          Double.isNaN(v.residualVolts()),
          "zero would read as 'feedback is doing nothing', which is a different and wrong claim");
    }

    @Test
    @DisplayName("insufficient() is fully populated and never null")
    void theInsufficientFactoryIsComplete() {
      ResponseVerdict v = ResponseVerdict.insufficient(0.001, 0.005);
      assertEquals(ResponseClass.INSUFFICIENT_EXCITATION, v.classification());
      assertFalse(v.headline().isBlank());
      assertFalse(v.diagnosis().isBlank());
      assertFalse(v.recommendation().isBlank());
      assertFalse(v.describe().isBlank());
    }
  }

  @Nested
  @DisplayName("the enum's own contract")
  final class Vocabulary {

    @Test
    @DisplayName("exactly one classification is dangerous, and three do not refine")
    void theClassTable() {
      assertEquals(7, ResponseClass.values().length, "seven shapes, and deliberately no UNKNOWN");

      for (ResponseClass c : ResponseClass.values()) {
        assertEquals(c == ResponseClass.UNSTABLE, c.isDangerous(), c.name());
        boolean shouldRefine =
            c != ResponseClass.GOOD
                && c != ResponseClass.UNSTABLE
                && c != ResponseClass.INSUFFICIENT_EXCITATION;
        assertEquals(shouldRefine, c.shouldRefine(), c.name());
      }

      // Declaration order is the evaluation order, and that is load-bearing.
      assertEquals(0, ResponseClass.INSUFFICIENT_EXCITATION.ordinal());
      assertEquals(1, ResponseClass.UNSTABLE.ordinal());
      assertEquals(
          ResponseClass.values().length - 1,
          ResponseClass.GOOD.ordinal(),
          "GOOD is last because every other rule gets to fire first");
    }
  }
}

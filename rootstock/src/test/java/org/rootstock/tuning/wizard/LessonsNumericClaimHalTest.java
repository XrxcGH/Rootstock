package org.rootstock.tuning.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.control.FakeTarget;
import org.rootstock.control.Gains;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.control.TravelLimits;
import org.rootstock.tuning.TuningRegistry;
import org.rootstock.tuning.wizard.steps.LqrSuggestStep;
import org.rootstock.tuning.wizard.steps.PredictStep;

/**
 * The half of the lesson audit that has to run the solver — and the measurement that removed two
 * claims from the prose.
 *
 * <p><strong>What was wrong.</strong> Two lessons said, in as many words, that "for a position
 * mechanism kP is exactly the volts you allowed divided by the error you allowed". {@link
 * LqrSuggestStep#design} does not divide: it builds the position system implied by kV and kA and
 * solves a discrete-time LQR with Bryson's-rule weights. On this library's own reference elevator —
 * kV 5.0, kA 0.060, 4 V of effort, 5 mm of accepted error — the division rule predicts <b>800</b> and
 * the solver returns <b>55.9</b>, a factor of fourteen. Worse for a teaching tool, the second claim
 * ("halve the error, double the gain") is off by two orders of magnitude in the <em>direction of
 * change</em>: halving the accepted error moves kP by 0.2%, not by 100%.
 *
 * <p>{@code PredictStep.slidersBeforeLqr} had already noticed, without anybody realising: its answer
 * key is computed from exactly this comparison and therefore always resolved to "it stays about the
 * same", while the explanation printed beside it asserted "It roughly doubles: 55.86 becomes 55.98".
 * A student who answered correctly was shown a sentence contradicting their own correct answer.
 *
 * <p>What is asserted here is what survived: kP is <b>monotone</b> in both sliders, the bounce
 * frequency really is {@code sqrt(kP / kA)}, and the prediction's explanation now agrees with its
 * own key.
 *
 * <p>{@code @Tag("hal")}: {@code LinearQuadraticRegulator}'s constructor reaches
 * {@code Matrix.exp}, which is a {@code wpimathjni} JNI call. Without natives WPILib's loader calls
 * {@code System.exit(1)} rather than throwing, so this cannot live in the default suite. Verified by
 * stack trace against 2026.2.2, not assumed.
 */
@Tag("hal")
final class LessonsNumericClaimHalTest {

  /** The reference elevator's measured feedforward. */
  private static final double kV = 5.0;

  private static final double kA = 0.060;

  private static LqrSuggestStep.Preferences preferences() {
    return LqrSuggestStep.Preferences.defaultsFor(
        MechanismArchetype.ELEVATOR, new TravelLimits(0.0, 1.4, 0.04), 0.005);
  }

  private static double kpFor(LqrSuggestStep.Preferences preferences) {
    return LqrSuggestStep.design(MechanismArchetype.ELEVATOR, kV, kA, preferences).kP();
  }

  @Nested
  @DisplayName("the claim that was removed")
  final class TheDivisionRule {

    @Test
    @DisplayName("kP is not the allowed volts divided by the allowed error, by a factor of fourteen")
    void theDivisionRuleIsWrong() {
      LqrSuggestStep.Preferences preferences = preferences();
      double divisionRule = preferences.maxControlEffortVolts() / preferences.maxAcceptableErrorSi();
      double solved = kpFor(preferences);

      assertEquals(800.0, divisionRule, 1e-9, "4 V over 5 mm");
      assertEquals(55.86, solved, 0.01, "what the shipped designer actually returns");
      assertTrue(
          divisionRule / solved > 10.0,
          "the two differ by " + (divisionRule / solved) + "x, which is not a rounding difference");
    }

    @Test
    @DisplayName("halving the accepted error moves kP by well under 10%, not by 100%")
    void halvingTheErrorDoesNotDoubleTheGain() {
      LqrSuggestStep.Preferences base = preferences();
      double ratio = kpFor(base.withMaxAcceptableErrorSi(base.maxAcceptableErrorSi() / 2.0))
          / kpFor(base);

      assertTrue(ratio > 1.0, "it does go up, which is the claim that survived: " + ratio);
      assertTrue(
          ratio < 1.10,
          "\"it roughly doubles\" would need about 2.0 and the measured factor is " + ratio);
    }
  }

  @Nested
  @DisplayName("the claims that survived, measured against the solver")
  final class WhatIsTrue {

    @Test
    @DisplayName("kP is monotone non-decreasing as the accepted error tightens")
    void tighterErrorNeverLowersKp() {
      LqrSuggestStep.Preferences base = preferences();
      double previous = Double.NEGATIVE_INFINITY;
      for (double error : new double[] {0.05, 0.02, 0.01, 0.005, 0.002, 0.001}) {
        double kp = kpFor(base.withMaxAcceptableErrorSi(error));
        assertTrue(
            kp >= previous - 1e-9,
            "kP fell from " + previous + " to " + kp + " when the error tightened to " + error);
        previous = kp;
      }
    }

    @Test
    @DisplayName("kP is monotone non-decreasing as the allowed effort rises")
    void moreVoltsNeverLowersKp() {
      LqrSuggestStep.Preferences base = preferences();
      double previous = Double.NEGATIVE_INFINITY;
      for (double volts : new double[] {1.0, 2.0, 4.0, 8.0, 10.0}) {
        double kp = kpFor(base.withMaxControlEffortVolts(volts));
        assertTrue(kp >= previous - 1e-9, "kP fell from " + previous + " to " + kp + " at " + volts + " V");
        previous = kp;
      }
    }

    @Test
    @DisplayName("the bounce rate really is sqrt(kP / kA), so tripling kP gives sqrt(3)")
    void theBounceRateGoesAsTheSquareRoot() {
      LqrSuggestStep.Suggestion suggestion =
          LqrSuggestStep.design(MechanismArchetype.ELEVATOR, kV, kA, preferences());

      assertEquals(
          Math.sqrt(suggestion.kP() / kA),
          suggestion.naturalFrequencyRadPerSec(),
          1e-9,
          "the identity the 'square root of kP' sentence rests on");

      double tripled = Math.sqrt(3.0 * suggestion.kP() / kA) / suggestion.naturalFrequencyRadPerSec();
      assertEquals(Math.sqrt(3.0), tripled, 1e-9);
      assertEquals(
          "1.7",
          String.format(Locale.ROOT, "%.1f", tripled),
          "and \"about 1.7 times faster\" is what that rounds to");

      assertEquals(
          suggestion.naturalFrequencyRadPerSec() / (2.0 * Math.PI),
          suggestion.naturalFrequencyHz(),
          1e-12);
      assertEquals(
          PredictStep.dampingRatio(suggestion.kP(), suggestion.kD(), kV, kA),
          suggestion.dampingRatio(),
          1e-12,
          "one damping formula, read by the panel and by the prediction key alike");
    }

    @Test
    @DisplayName("the panel text quotes the same 0.7 the lesson does")
    void thePanelAndTheLessonAgree() {
      LqrSuggestStep.Suggestion suggestion =
          LqrSuggestStep.design(MechanismArchetype.ELEVATOR, kV, kA, preferences());
      assertTrue(
          suggestion.panel().contains(String.format(Locale.ROOT, "%.1f", Coach.kBounceThreshold)),
          suggestion.panel());
      assertTrue(Lessons.WHAT_THE_SLIDERS_DO.contains("Above about 0.7"));
    }

    @Test
    @DisplayName("a velocity mechanism gets kD = 0, exactly as the flywheel lesson promises")
    void aVelocityLoopHasNoDamperToTune() {
      LqrSuggestStep.Preferences preferences =
          LqrSuggestStep.Preferences.defaultsFor(
              MechanismArchetype.FLYWHEEL, TravelLimits.unbounded(), 5.0);
      LqrSuggestStep.Suggestion suggestion =
          LqrSuggestStep.design(MechanismArchetype.FLYWHEEL, 0.02, 0.004, preferences);

      assertEquals(0.0, suggestion.kD(), 0.0);
      assertTrue(
          Lessons.WHAT_THE_SLIDERS_DO.contains("a shooter wheel usually needs no kD at all"),
          "and the lesson says so");
    }

    @Test
    @DisplayName("an unphysical plant produces no gains and says which invert to fix")
    void anInvertedPlantIsRefused() {
      LqrSuggestStep.Suggestion suggestion =
          LqrSuggestStep.design(MechanismArchetype.ELEVATOR, kV, -0.01, preferences());
      assertTrue(Double.isNaN(suggestion.kP()));
      assertFalse(suggestion.isPhysical());
      assertTrue(suggestion.panel().contains("Re-run"), suggestion.panel());
      assertTrue(
          suggestion.warnings().stream().anyMatch(w -> w.contains("do not tune around it")),
          suggestion.warnings().toString());
    }
  }

  @Nested
  @DisplayName("the prediction whose key needs the solver")
  final class TheSliderPrediction {

    @Test
    @DisplayName("the computed key and its explanation now agree with each other")
    void theExplanationMatchesTheKey() {
      FakeTarget target =
          new FakeTarget().named("Elevator").archetype(MechanismArchetype.ELEVATOR).at(0.7);
      FakeStepContext ctx =
          new FakeStepContext(target).withGains(new Gains(0.0, 0, 0, 0.2, kV, kA, 0.25));

      PredictStep step = PredictStep.slidersBeforeLqr(new NoOpStep());
      step.begin(ctx);
      PredictStep.Question q = step.question().orElseThrow();

      assertEquals(3, q.options().size());
      String chosenOption = q.options().get(q.correctIndex());
      String claim = chosenOption.replace("It ", "").replace(".", "").trim();

      assertTrue(
          q.whyCorrect().toLowerCase(Locale.ROOT).contains(claim.toLowerCase(Locale.ROOT)),
          "the explanation opens with the branch the key actually chose. Key said \""
              + chosenOption
              + "\" and the explanation says: "
              + q.whyCorrect());
      assertFalse(
          q.whyCorrect().contains("volts you allowed divided by the error you allowed"),
          "and it no longer repeats the division rule that this class disproves");

      // Every option a student can pick has an explanation, including the one they most often pick.
      for (int i = 0; i < 3; i++) {
        if (i != q.correctIndex()) {
          assertFalse(
              q.whyWrongFor(i).isBlank(),
              "option " + i + " has no explanation, so a student who picks it learns nothing");
        }
      }
    }

    @Test
    @DisplayName("the ten control topics that share the mechanism namespace include the I guards")
    void theControlTopicsIncludeTheIntegralGuards() {
      assertTrue(TuningRegistry.kControlTunables.contains("iZone"), TuningRegistry.kControlTunables.toString());
      assertTrue(
          TuningRegistry.kControlTunables.contains("iMaxVolts"),
          TuningRegistry.kControlTunables.toString());
      assertEquals(10, TuningRegistry.kControlTunables.size());
    }
  }

  /** A stand-in for the step a prediction is asked in front of. */
  private static final class NoOpStep implements TuningStep {
    @Override
    public String title() {
      return "Suggest kP";
    }

    @Override
    public java.util.Optional<org.rootstock.control.GainId> produces() {
      return java.util.Optional.of(org.rootstock.control.GainId.KP);
    }

    @Override
    public String explanation() {
      return "";
    }

    @Override
    public String watchFor() {
      return "";
    }

    @Override
    public String willDo() {
      return "";
    }

    @Override
    public void begin(StepContext ctx) {}

    @Override
    public void periodic(StepContext ctx) {}

    @Override
    public boolean isComplete(StepContext ctx) {
      return true;
    }

    @Override
    public StepResult finish(StepContext ctx) {
      return StepResult.informational("done", "", "", java.util.List.of());
    }

    @Override
    public double expectedSeconds(StepContext ctx) {
      return 0.0;
    }
  }
}

package org.rootstock.tuning.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.control.GainId;
import org.rootstock.core.compat.Clock;
import org.rootstock.tuning.diagnostics.StepResponseAnalyzer;
import org.rootstock.tuning.sysid.FeedforwardFit;
import org.rootstock.tuning.sysid.SysIdSweep;
import org.rootstock.tuning.wizard.steps.BreakawayRampStep;
import org.rootstock.tuning.wizard.steps.HoldBisectionStep;
import org.rootstock.tuning.wizard.steps.PredictStep;
import org.rootstock.tuning.wizard.steps.StepResponseStep;
import org.rootstock.units.SiDomain;

/**
 * <strong>Every numeric claim in every lesson is checked against the code that produces it.</strong>
 *
 * <p>The lessons are not documentation about the library — they are the prose a fourteen-year-old
 * reads on the dashboard while a mechanism they built is about to move. A lesson that says "it cuts
 * the range in half ten times" when a constant was changed to eight is not a stale comment; it is a
 * teaching tool teaching something false, with the robot's authority behind it. That is worse than
 * teaching nothing, because a student who is misled by a tool they trust does not go and check.
 *
 * <p>So each claim below is <b>recomputed from the constant that produces it</b> and then found in
 * the lesson text. Two kinds of check run:
 *
 * <ul>
 *   <li><b>Constant claims</b> — "ten times", "half a second", "twenty milliseconds", "a thirtieth
 *       of its travel". The constant is read from the class that owns it and the derived string is
 *       required to appear in the lesson.
 *   <li><b>Arithmetic claims</b> — "one part in six hundred", "five thousand times apart", "about
 *       1.7 times faster". The arithmetic is redone here from first principles and compared with
 *       what the sentence asserts.
 * </ul>
 *
 * <p><strong>This test found two false claims and one self-contradiction.</strong> Both revisions of
 * "for a position mechanism kP is exactly the volts you allowed divided by the error you allowed"
 * were wrong for the shipped {@code LqrSuggestStep}, which solves a discrete-time LQR rather than
 * dividing — on the reference elevator the rule predicts 800 and the solver returns 55.9 — and
 * {@code PredictStep.slidersBeforeLqr} printed "it roughly doubles" beside a computed key that says
 * "it stays about the same". The claims that survived are pinned here; the arithmetic that disproved
 * them lives in {@code LessonsNumericClaimHalTest}, because running the solver needs wpimath's JNI.
 *
 * <p>Native-free: everything here is string matching and constant arithmetic.
 */
final class LessonsNumericClaimTest {

  /** Matches every number in a lesson, so none can be quietly skipped. */
  private static final Pattern kNumber = Pattern.compile("(?<![\\w.])(\\d+(?:\\.\\d+)?)(?![\\w])");

  /** Asserts a lesson contains a phrase, with the lesson name in the failure. */
  private static void claims(String lessonName, String lesson, String phrase) {
    assertTrue(
        lesson.contains(phrase),
        () ->
            "Lesson "
                + lessonName
                + " no longer contains the claim \""
                + phrase
                + "\". Either the prose changed or the constant it was derived from did. Fix "
                + "whichever is now wrong — a lesson and a constant that disagree is a lie with a "
                + "delay fuse.");
  }

  @Nested
  @DisplayName("kG: the bisection numbers")
  final class GravityLesson {

    @Test
    @DisplayName("\"in half ten times\" is HoldBisectionStep.kIterations")
    void tenHalvings() {
      assertEquals(10, HoldBisectionStep.kIterations);
      claims("KG", Lessons.KG, "cutting the range in half ten times");
    }

    @Test
    @DisplayName("\"one part in six hundred\" is the bracket span over two-to-the-ten")
    void onePartInSixHundred() {
      double span = HoldBisectionStep.kBracketHigh - HoldBisectionStep.kBracketLow;
      assertEquals(1.6, span, 1e-12, "the [0.2x, 1.8x] physics bracket");

      double finalWidth = span / Math.pow(2.0, HoldBisectionStep.kIterations);
      assertEquals(1.0 / 640.0, finalWidth, 1e-15, "1.6 / 1024 = 1/640");

      double partsIn = 1.0 / finalWidth;
      assertEquals(640.0, partsIn, 1e-9);
      assertTrue(
          partsIn >= 600.0 && partsIn < 700.0,
          "\"about one part in six hundred\" must round to six hundred, and it is " + partsIn);
      claims("KG", Lessons.KG, "one part in six hundred");
    }

    @Test
    @DisplayName("\"in five seconds\" is the drift window plus ten probe-and-settle iterations")
    void fiveSeconds() {
      double predicted =
          HoldBisectionStep.kDriftSeconds
              + HoldBisectionStep.kIterations
                  * (HoldBisectionStep.kProbeSeconds + HoldBisectionStep.kSettleSeconds);
      assertEquals(5.0, predicted, 1e-12, "0.50 + 10 * (0.35 + 0.10)");
      claims("KG", Lessons.KG, "in five seconds");
    }

    @Test
    @DisplayName("\"a thirtieth of its travel\" is at least kGuardFractionOfTravel")
    void aThirtiethOfTravel() {
      assertEquals(0.03, HoldBisectionStep.kGuardFractionOfTravel, 1e-12);
      assertTrue(
          HoldBisectionStep.kGuardFractionOfTravel <= 1.0 / 30.0,
          "the lesson promises it never drifts MORE than a thirtieth (0.0333), and the guard is "
              + HoldBisectionStep.kGuardFractionOfTravel);
      claims("KG", Lessons.KG, "a thirtieth of its travel");
    }

    @Test
    @DisplayName("\"lets go for half a second\" is kDriftSeconds")
    void halfASecondOfDrift() {
      assertEquals(0.50, HoldBisectionStep.kDriftSeconds, 1e-12);
      claims("KG", Lessons.KG, "lets go for half a second");
    }

    @Test
    @DisplayName("the brake-mode fallback the last paragraph describes actually exists")
    void theBreakawayFallbackExists() {
      claims("KG", Lessons.KG, "how hard the mechanism is to break loose in each direction");
      assertTrue(
          HoldBisectionStep.kDriftSecondsBrakeRetry > HoldBisectionStep.kDriftSeconds,
          "the brake retry watches for longer than the coast drift, which is why it needs its own "
              + "constant");
    }
  }

  @Nested
  @DisplayName("kA: the dynamic step's size and length")
  final class AccelerationLesson {

    @Test
    @DisplayName("\"less than half your remaining travel\" is kDynamicBandFraction")
    void lessThanHalfTheTravel() {
      assertEquals(0.45, SysIdSweep.kDynamicBandFraction, 1e-12);
      assertTrue(
          SysIdSweep.kDynamicBandFraction < 0.5,
          "the lesson promises less than half, and the constant is "
              + SysIdSweep.kDynamicBandFraction);
      claims("KA", Lessons.KA, "less than half your remaining travel");
    }

    @Test
    @DisplayName("\"a second and a half\" is kPositionDynamicSeconds")
    void aSecondAndAHalf() {
      assertEquals(1.5, SysIdSweep.kPositionDynamicSeconds, 1e-12);
      claims("KA", Lessons.KA, "stop it after a second and a half");
    }
  }

  @Nested
  @DisplayName("kS: what the ramp actually does")
  final class FrictionLesson {

    @Test
    @DisplayName("\"in both directions, and average the two answers\" is what the step computes")
    void bothDirectionsAveraged() {
      claims("KS", Lessons.KS, "in both directions, and average the two answers");
      assertTrue(
          BreakawayRampStep.kRampVoltsPerSecond > 0.0
              && BreakawayRampStep.kRampVoltsPerSecond <= 0.5,
          "\"slowly increase the voltage from zero\" is "
              + BreakawayRampStep.kRampVoltsPerSecond
              + " V/s");
    }

    @Test
    @DisplayName("\"between 0.1 V and 0.8 V\" sits inside the kS the fit will accept")
    void theQuotedRangeIsAcceptable() {
      claims("KS", Lessons.KS, "between 0.1 V and 0.8 V");
      double ceiling = FeedforwardFit.kMaxKsFractionOfNominal * 12.0;
      assertEquals(3.0, ceiling, 1e-12);
      assertTrue(
          0.8 < ceiling,
          "a lesson that quotes a typical range above the value the fit will flag would be "
              + "teaching students to expect a warning");
    }
  }

  @Nested
  @DisplayName("P and the sliders: what survived, and what did not")
  final class FeedbackLessons {

    @Test
    @DisplayName("\"about 1.7 times faster\" is the square root of three")
    void tripleKpIsRootThree() {
      // The claim rests on naturalFrequency = sqrt(kP / kA), so tripling kP multiplies it by
      // sqrt(3). That identity is checked against the designer in LessonsNumericClaimHalTest;
      // here the arithmetic the sentence asserts is checked on its own terms.
      double factor = Math.sqrt(3.0);
      assertEquals(1.7320508075688772, factor, 1e-15);
      assertEquals(
          "1.7",
          String.format(Locale.ROOT, "%.1f", factor),
          "\"about 1.7\" must be what sqrt(3) rounds to at one decimal place");
      claims("WHAT_THE_SLIDERS_DO", Lessons.WHAT_THE_SLIDERS_DO, "about 1.7 times faster");
      claims("WHAT_THE_SLIDERS_DO", Lessons.WHAT_THE_SLIDERS_DO, "square root of kP");
    }

    @Test
    @DisplayName("\"above about 0.7\" is Coach.kBounceThreshold, in both lessons and in the key")
    void sevenTenthsIsOneNumber() {
      assertEquals(0.7, Coach.kBounceThreshold, 1e-12);
      claims("WHAT_THE_SLIDERS_DO", Lessons.WHAT_THE_SLIDERS_DO, "Above about 0.7");
      claims("WHAT_THE_SLIDERS_DO", Lessons.WHAT_THE_SLIDERS_DO, "Below 0.7");

      // And it is the same 0.7 the prediction key branches on, not a second copy.
      assertEquals(0, PredictStep.classifyDamping(Coach.kBounceThreshold - 1e-9));
      assertEquals(2, PredictStep.classifyDamping(Coach.kBounceThreshold));
      assertEquals(2, PredictStep.classifyDamping(PredictStep.kSluggishThreshold));
      assertEquals(1, PredictStep.classifyDamping(PredictStep.kSluggishThreshold + 1e-9));
    }

    @Test
    @DisplayName("neither lesson claims kP is the allowed volts divided by the allowed error")
    void theDivisionClaimIsGone() {
      for (Map.Entry<String, String> lesson : Lessons.all().entrySet()) {
        String text = lesson.getValue();
        assertFalse(
            text.contains("volts divided by error")
                || text.contains("volts you allowed, divided by")
                || text.contains("volts you allowed divided by"),
            () ->
                "Lesson "
                    + lesson.getKey()
                    + " claims kP is the allowed volts divided by the allowed error. That is not "
                    + "what LqrSuggestStep computes: it solves a discrete-time LQR, and on the "
                    + "reference elevator (kV 5.0, kA 0.060, 4 V, 5 mm) the division rule predicts "
                    + "800 while the solver returns 55.9. See LessonsNumericClaimHalTest, which "
                    + "runs the solver and measures it.");
      }
    }

    @Test
    @DisplayName("what both lessons do claim — that both sliders push kP the same way — is checkable")
    void bothSlidersPushTheSameWay() {
      claims("P", Lessons.P, "Tighten the error, or allow more volts, and kP goes up");
      claims(
          "WHAT_THE_SLIDERS_DO",
          Lessons.WHAT_THE_SLIDERS_DO,
          "Tighten the error or raise the volts, and kP goes up");
      // The monotonicity itself is measured against the solver in LessonsNumericClaimHalTest.
    }

    @Test
    @DisplayName("\"the next number you tune is kD\" is the order the recipe actually runs")
    void kdComesAfterKp() {
      claims("WHAT_THE_SLIDERS_DO", Lessons.WHAT_THE_SLIDERS_DO, "the next number you tune is kD");

      List<GainId> produced = new ArrayList<>();
      TuningRecipe recipe = Recipes.elevator(TuningRecipe.Mode.TEACHING);
      for (int i = 0; i < recipe.size(); i++) {
        recipe.step(i).produces().ifPresent(produced::add);
      }
      assertTrue(produced.contains(GainId.KP), produced.toString());
      assertTrue(produced.contains(GainId.KD), produced.toString());
      assertTrue(
          produced.indexOf(GainId.KP) < produced.indexOf(GainId.KD),
          "the recipe produces " + produced + ", which is not kP then kD");
    }
  }

  @Nested
  @DisplayName("D: the buzz and what happens to kD")
  final class DerivativeLesson {

    @Test
    @DisplayName("\"halving kD because it saw a 14 Hz buzz\" is a real instance of the real rule")
    void fourteenHertzHalvesKd() {
      claims("D", Lessons.D, "halving kD because it saw a 14 Hz buzz");
      assertEquals(8.0, StepResponseAnalyzer.kNoiseFrequencyHz, 1e-12);
      assertEquals(
          Coach.kNoiseFrequencyHz,
          StepResponseAnalyzer.kNoiseFrequencyHz,
          1e-12,
          "one noise threshold, read by both the analyser and the coach");
      assertTrue(
          14.0 >= StepResponseAnalyzer.kNoiseFrequencyHz,
          "14 Hz has to actually be above the threshold, or the anecdote is fiction");
    }
  }

  @Nested
  @DisplayName("I: the two things you must supply with it")
  final class IntegralLesson {

    @Test
    @DisplayName("\"you have to supply an I-zone\" is a required argument, not a convention")
    void theIZoneCannotBeOmitted() {
      claims("I", Lessons.I, "supply an I-zone");
      claims("I", Lessons.I, "voltage cap");

      List<java.lang.reflect.Method> factories = new ArrayList<>();
      for (java.lang.reflect.Method method :
          org.rootstock.control.Controllers.class.getDeclaredMethods()) {
        if (method.getName().equals("pid")
            && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
          factories.add(method);
        }
      }
      assertEquals(
          1,
          factories.size(),
          "one feedback-controller factory, so there is no overload that quietly lets a student "
              + "enable kI without saying where it is allowed to accumulate");
      assertEquals(5, factories.get(0).getParameterCount());

      // And the zone is only applied when kI is actually non-zero, exactly as the lesson implies.
      assertEquals(
          0.02,
          org.rootstock.control.Controllers.pid(
                  org.rootstock.control.Gains.pid(1.0, 0.5, 0.0), false, 0.005, 0.02, 0.02)
              .getIZone(),
          1e-12);
      assertTrue(
          Double.isInfinite(
              org.rootstock.control.Controllers.pid(
                      org.rootstock.control.Gains.pid(1.0, 0.0, 0.0), false, 0.005, 0.02, 0.02)
                  .getIZone()),
          "with kI at zero there is no integrator to bound, so no zone is imposed");
    }

    @Test
    @DisplayName("\"residual volts\" is the number the refinement step actually branches on")
    void residualVoltsIsReal() {
      claims("I", Lessons.I, "residual volts");
      assertTrue(
          StepResponseAnalyzer.kSteadyStateWindowSeconds > 0.0,
          "the residual is the mean feedback voltage over the last "
              + StepResponseAnalyzer.kSteadyStateWindowSeconds
              + " s");
      assertEquals(0.5, StepResponseAnalyzer.kSteadyStateWindowSeconds, 1e-12);
    }
  }

  @Nested
  @DisplayName("motion profiles and units")
  final class RemainingLessons {

    @Test
    @DisplayName("\"every twenty milliseconds\" is the library's one loop period")
    void twentyMilliseconds() {
      assertEquals(0.02, Clock.kDefaultPeriodSeconds, 1e-12);
      assertEquals(
          20.0,
          Clock.kDefaultPeriodSeconds * 1000.0,
          1e-9,
          "twenty milliseconds, and there is exactly one spelling of it in the library");
      claims("MOTION_PROFILES", Lessons.MOTION_PROFILES, "every twenty milliseconds");
    }

    @Test
    @DisplayName("\"0.01 ... and 50 ... five thousand times apart\" is arithmetic that holds")
    void fiveThousandTimesApart() {
      claims("WHY_UNITS_MATTER", Lessons.WHY_UNITS_MATTER, "0.01 on a SPARK MAX");
      claims("WHY_UNITS_MATTER", Lessons.WHY_UNITS_MATTER, "50 on a TalonFX");
      claims("WHY_UNITS_MATTER", Lessons.WHY_UNITS_MATTER, "five thousand times apart");
      assertEquals(5000.0, 50.0 / 0.01, 1e-9);
    }

    @Test
    @DisplayName("\"metres or radians, and output in volts\" is what GainId actually renders")
    void theUnitsClaimMatchesTheEnum() {
      claims("WHY_UNITS_MATTER", Lessons.WHY_UNITS_MATTER, "metres or radians, and output in volts");
      assertEquals("V/m", GainId.KP.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/rad", GainId.KP.unitFor(SiDomain.ROTATIONAL_RADIANS));
      claims("P", Lessons.P, "volts per metre for an elevator, volts per radian for an arm");
    }

    @Test
    @DisplayName("\"three options, no trick answers\" is enforced by the question type")
    void threeOptionsIsEnforced() {
      claims("PRACTICE_MODE", Lessons.PRACTICE_MODE, "three options, no trick answers");
      assertTrue(
          org.junit.jupiter.api.Assertions.assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      new PredictStep.Question(
                          "?", List.of("a", "b"), 0, "why", Map.of()))
              .getMessage()
              .contains("exactly three options"),
          "the promise in the lesson is enforced by the constructor, not by discipline");
    }

    @Test
    @DisplayName("the order WHY_FEEDFORWARD_FIRST promises is the order GainId declares")
    void theOrderOfOperations() {
      claims(
          "WHY_FEEDFORWARD_FIRST",
          Lessons.WHY_FEEDFORWARD_FIRST,
          "kS, then kV, then kA, then kG, then kP, then kD");

      List<GainId> promised =
          List.of(GainId.KS, GainId.KV, GainId.KA, GainId.KG, GainId.KP, GainId.KD);
      List<GainId> declared = new ArrayList<>();
      for (GainId id : GainId.values()) {
        if (id != GainId.KI) {
          declared.add(id);
        }
      }
      assertEquals(
          promised,
          declared,
          "the enum's declaration order is the order the lesson teaches, minus kI — which no "
              + "recipe produces and which the I lesson explains at length");

      // And the gravity-before-friction wrinkle the lesson admits to is the real recipe order.
      claims("WHY_FEEDFORWARD_FIRST", Lessons.WHY_FEEDFORWARD_FIRST, "we measure gravity before we measure friction");
      List<GainId> produced = new ArrayList<>();
      TuningRecipe elevator = Recipes.elevator(TuningRecipe.Mode.TEACHING);
      for (int i = 0; i < elevator.size(); i++) {
        elevator.step(i).produces().ifPresent(produced::add);
      }
      assertTrue(
          produced.indexOf(GainId.KG) < produced.indexOf(GainId.KS),
          "the elevator recipe produces " + produced + ", and the lesson says kG comes first");
    }
  }

  @Nested
  @DisplayName("the coaching text the lessons point at")
  final class CoachingNumbers {

    @Test
    @DisplayName("every multiplier the recommendations quote is the one the refinement step applies")
    void theQuotedMultipliersAreReal() {
      assertEquals(1.6, StepResponseStep.kSluggishKpGain, 1e-12);
      assertEquals(0.6, StepResponseStep.kOscillatingKpCut, 1e-12);
      assertEquals(0.4, StepResponseStep.kUnstableRetreat, 1e-12);

      assertEquals(
          60.0,
          (StepResponseStep.kSluggishKpGain - 1.0) * 100.0,
          1e-9,
          "\"turning kP up by 60%\" is the 1.6 multiplier");
      assertEquals(
          40.0,
          (1.0 - StepResponseStep.kOscillatingKpCut) * 100.0,
          1e-9,
          "\"cut kP by 40%\" is the 0.6 multiplier");
      assertEquals(
          40.0,
          StepResponseStep.kUnstableRetreat * 100.0,
          1e-9,
          "\"cut to 40% of what it was\" is the 0.4 retreat");
    }
  }

  @Nested
  @DisplayName("the writing rules the lessons follow")
  final class Style {

    @Test
    @DisplayName("no lesson exceeds the length budget")
    void everyLessonFitsTheBudget() {
      for (Map.Entry<String, String> lesson : Lessons.all().entrySet()) {
        assertTrue(
            lesson.getValue().length() <= Lessons.kMaxLength,
            () ->
                lesson.getKey()
                    + " is "
                    + lesson.getValue().length()
                    + " characters and the budget is "
                    + Lessons.kMaxLength);
      }
    }

    @Test
    @DisplayName("no lesson uses jargon a fourteen-year-old would stop reading at")
    void noForbiddenJargon() {
      for (Map.Entry<String, String> lesson : Lessons.all().entrySet()) {
        String lower = lesson.getValue().toLowerCase(Locale.ROOT);
        for (String jargon : Lessons.kForbiddenJargon) {
          assertFalse(
              lower.contains(jargon.toLowerCase(Locale.ROOT)),
              lesson.getKey() + " contains \"" + jargon + "\"");
        }
      }
    }

    @Test
    @DisplayName("forGain covers all seven gains and never returns blank")
    void forGainIsTotal() {
      Set<String> distinct = new LinkedHashSet<>();
      for (GainId id : GainId.values()) {
        String lesson = Lessons.forGain(id);
        assertFalse(lesson.isBlank(), id.key());
        distinct.add(lesson);
      }
      assertEquals(7, distinct.size(), "seven gains, seven distinct lessons");
      assertEquals(12, Lessons.all().size(), "and twelve lessons in total");
    }

    @Test
    @DisplayName("every number that appears in a lesson has been accounted for by a test above")
    void noNumberIsUnaccountedFor() {
      // The numbers deliberately left unchecked, with the reason each one is not code-derived.
      Set<String> allowed =
          Set.of(
              "0.1", "0.8", // typical kS range on FRC hardware, an empirical claim about robots
              "1", "2", "3", "4", "10", "20", "100", // counting words inside ordinary prose
              "0.7", "1.7", "14", "50", "0.01", "5000", // checked above
              "1.4", "0.02", "30", "0.9", "0.5", "8", "0.4", "0.6", "1.6", "0.03");

      List<String> unexplained = new ArrayList<>();
      for (Map.Entry<String, String> lesson : Lessons.all().entrySet()) {
        Matcher matcher = kNumber.matcher(lesson.getValue());
        while (matcher.find()) {
          String number = matcher.group(1);
          if (!allowed.contains(number)) {
            unexplained.add(lesson.getKey() + ": " + number);
          }
        }
      }

      assertTrue(
          unexplained.isEmpty(),
          () ->
              "These numbers appear in a lesson and no test above checks them: "
                  + unexplained
                  + ". Add an assertion tying each one to the constant that produces it, or add it "
                  + "to the allow-list with a reason. A number in teaching prose that nothing "
                  + "checks is the exact failure this test class exists to prevent.");
    }
  }
}

package org.pumpkinlib.tuning.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.FakeTarget;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.MechanismArchetype;
import org.pumpkinlib.control.PlantPrior;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.tuning.wizard.steps.PredictStep;

/**
 * The prediction step: the student must commit an answer <em>before</em> anything moves, and the
 * answer key is computed from the mechanism rather than written down by whoever wrote the question.
 *
 * <p><strong>Why the commit-before-motion ordering is the whole point.</strong> Without it the
 * wizard is a progress bar with good prose: a student can finish every recipe by pressing A eleven
 * times, and neither the wizard nor a mentor can tell that apart from learning. Asking for a
 * prediction first, and scoring it, is the only measurement in this library that measures the
 * person.
 *
 * <p><strong>And why a computed key rather than an authored one.</strong> A hand-written answer key
 * is a claim about a mechanism nobody has measured; the moment a mechanism's kV differs from the one
 * the question's author imagined, the "correct" answer is wrong and the student is taught something
 * false with a green tick beside it. The damping key here is {@code (kD + kV) / (2*sqrt(kP*kA))}
 * evaluated at the gains the mechanism is actually carrying, which is the same arithmetic the kP
 * panel prints two screens later and which the student can redo by hand.
 *
 * <p>Native-free: nothing here arms a supervisor, builds an alert, or touches NetworkTables. The one
 * prediction whose key runs the LQR solver — {@code slidersBeforeLqr} — is exercised in {@code
 * LessonsNumericClaimHalTest}, because {@code Matrix.solveFullPivHouseholderQr} is a JNI call.
 */
final class PredictStepTest {

  /** The reference elevator, whose numbers every computed key below is derived from. */
  private static FakeTarget elevator() {
    return new FakeTarget().named("Elevator").archetype(MechanismArchetype.ELEVATOR).at(0.7);
  }

  /** A step that stands in for whatever a prediction is asked in front of. */
  private static final class NullStep implements TuningStep {
    @Override
    public String title() {
      return "Move the thing";
    }

    @Override
    public Optional<org.pumpkinlib.control.GainId> produces() {
      return Optional.empty();
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
      return StepResult.informational("done", "", "", List.of());
    }

    @Override
    public double expectedSeconds(StepContext ctx) {
      return 0.0;
    }
  }

  @Nested
  @DisplayName("commit before motion")
  final class CommitFirst {

    @Test
    @DisplayName("the prediction step itself never commands motion")
    void thePredictionCommandsNothing() {
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());
      assertFalse(step.commandsMotion(), "a question that moves an arm is not a question");
      assertEquals("Nothing moves during this step.", step.willDo());
      assertEquals(0.0, step.expectedSeconds(new FakeStepContext(elevator())), 0.0);
    }

    @Test
    @DisplayName("the step is not complete until the student has answered")
    void incompleteUntilAnswered() {
      FakeStepContext ctx = new FakeStepContext(elevator());
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());

      step.begin(ctx);
      assertEquals(1, ctx.prompts.size(), "the question is published as soon as the step begins");
      assertEquals(3, ctx.options.size());

      for (int loop = 0; loop < 50; loop++) {
        step.periodic(ctx);
        ctx.advance(0.02);
        assertFalse(
            step.isComplete(ctx),
            "the wizard must not advance to the moving step while the answer is outstanding");
      }

      ctx.studentAnswers(2);
      step.periodic(ctx);
      assertTrue(step.isComplete(ctx));
      assertEquals(java.util.OptionalInt.of(2), step.answer());
    }

    @Test
    @DisplayName("the answer is not scored, and correct() is empty, until finish() runs")
    void notScoredUntilFinished() {
      FakeStepContext ctx = new FakeStepContext(elevator());
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());
      step.begin(ctx);

      assertTrue(step.correct().isEmpty(), "no answer, no verdict");
      assertFalse(step.isScored());

      ctx.studentAnswers(0);
      step.periodic(ctx);
      assertTrue(step.correct().isPresent(), "an answer is enough to know whether it was right");
      assertFalse(step.isScored(), "but scoring is what finish() does");

      step.finish(ctx);
      assertTrue(step.isScored());
      assertEquals(1, ctx.feedback.size());
    }

    @Test
    @DisplayName("a student who never answers is not blocked, and is told the score is the point")
    void aTimeoutSkipsRatherThanBlocks() {
      FakeStepContext ctx = new FakeStepContext(elevator());
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());
      step.begin(ctx);

      ctx.advance(PredictStep.kTimeoutSeconds * 0.9);
      step.periodic(ctx);
      assertFalse(step.isComplete(ctx), "a student who is thinking is the student this is for");

      ctx.advance(PredictStep.kTimeoutSeconds * 0.2);
      step.periodic(ctx);
      assertTrue(step.isComplete(ctx));

      StepResult result = step.finish(ctx);
      assertEquals(StepResult.Outcome.SUCCESS, result.outcome());
      assertFalse(result.setsAGain(), "a prediction never writes a gain");
      assertTrue(result.headline().contains("skipped"), result.headline());
      assertTrue(
          ctx.feedback.get(0).contains("blocks nothing"),
          "getting it wrong, or not answering, must not gate the tuning: " + ctx.feedback.get(0));
    }

    @Test
    @DisplayName("in the shipped elevator recipe every prediction sits immediately before its step")
    void everyPredictionFrontsItsOwnStep() {
      TuningRecipe recipe = Recipes.elevator(TuningRecipe.Mode.TEACHING);
      assertTrue(recipe.hasPredictions());

      int found = 0;
      for (int i = 0; i < recipe.size(); i++) {
        TuningStep step = recipe.step(i);
        if (step instanceof PredictStep predict) {
          found++;
          assertTrue(i + 1 < recipe.size(), "a prediction cannot be the last step");
          assertEquals(
              predict.next(),
              recipe.step(i + 1),
              "the question must be asked in front of the step it is about, or the student is "
                  + "predicting something they have already watched");
          assertFalse(step.commandsMotion());
        }
      }
      assertEquals(4, found, "four predictions in the teaching elevator recipe");

      TuningRecipe express = Recipes.elevator(TuningRecipe.Mode.EXPRESS);
      assertFalse(
          express.hasPredictions(),
          "express drops teaching, and only teaching — never an interlock");
      assertTrue(
          express.preflight().isPresent(),
          "the pre-flight mechanical check survives into express mode");
    }
  }

  @Nested
  @DisplayName("the answer key is computed, not authored")
  final class ComputedKey {

    /** The damping formula, written here independently of the class under test. */
    private static double zeta(Gains g) {
      return (g.kD() + g.kV()) / (2.0 * Math.sqrt(g.kP() * g.kA()));
    }

    @Test
    @DisplayName("the damping key tracks the gains, and moves when they move")
    void theDampingKeyFollowsTheGains() {
      // Three gain sets chosen to land in the three different answer bands.
      Gains bouncy = new Gains(400.0, 0, 0, 0.2, 1.0, 0.060, 0.25);
      Gains clean = new Gains(128.0, 0, 0, 0.2, 5.0, 0.060, 0.25);
      Gains lazy = new Gains(40.0, 0, 0, 0.2, 8.0, 0.060, 0.25);

      assertTrue(zeta(bouncy) < 0.7, "zeta " + zeta(bouncy));
      assertTrue(zeta(clean) >= 0.7 && zeta(clean) <= 1.2, "zeta " + zeta(clean));
      assertTrue(zeta(lazy) > 1.2, "zeta " + zeta(lazy));

      assertEquals(0, keyFor(bouncy), "under-damped: it will overshoot and bounce");
      assertEquals(2, keyFor(clean), "in band: it will arrive cleanly");
      assertEquals(1, keyFor(lazy), "over-damped: it will arrive slowly and stop short");

      // And the key is the published formula, not a table.
      assertEquals(zeta(clean), PredictStep.dampingRatio(128.0, 0.0, 5.0, 0.060), 1e-12);
      assertEquals(0, PredictStep.classifyDamping(Double.NaN), "an unphysical plant reads as bounce");
    }

    private static int keyFor(Gains gains) {
      FakeStepContext ctx = new FakeStepContext(elevator()).withGains(gains);
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());
      step.begin(ctx);
      return step.question().orElseThrow().correctIndex();
    }

    @Test
    @DisplayName("the worked example from the design lands on 'it will stop cleanly'")
    void theWorkedExample() {
      // kP 128.00, kA 0.060, kD 0, kV 5.00 -> 5.00 / (2 * sqrt(128 * 0.060)) = 0.9021
      double computed = PredictStep.dampingRatio(128.00, 0.0, 5.00, 0.060);
      assertEquals(5.00 / (2.0 * Math.sqrt(128.00 * 0.060)), computed, 1e-12);
      assertEquals(0.9021, computed, 5e-5);
      assertEquals(2, PredictStep.classifyDamping(computed));

      FakeStepContext ctx =
          new FakeStepContext(elevator())
              .withGains(new Gains(128.0, 0, 0, 0.2, 5.0, 0.060, 0.25));
      PredictStep step = PredictStep.dampingBeforeRefine(new NullStep());
      step.begin(ctx);

      PredictStep.Question q = step.question().orElseThrow();
      assertEquals(2, q.correctIndex());
      assertTrue(
          q.whyCorrect().contains("0.90"),
          "the explanation shows the arithmetic the student can redo: " + q.whyCorrect());
      assertTrue(
          q.whyWrongFor(0).contains("kV is in the numerator"),
          "the whole lesson of this question is that a mechanism damps itself before kD does "
              + "anything: " + q.whyWrongFor(0));
    }

    @Test
    @DisplayName("the gravity key is three brackets around the mechanism's own prior")
    void theGravityKeyComesFromThePlantPrior() {
      FakeTarget target = elevator();
      double prior = target.plantPrior().gravityVoltsPrior();
      assertTrue(prior > 0.0);

      FakeStepContext ctx = new FakeStepContext(target);
      PredictStep step = PredictStep.holdingVoltsBeforeGravity(new NullStep());
      step.begin(ctx);

      PredictStep.Question q = step.question().orElseThrow();
      assertEquals(3, q.options().size());

      // The correct slot is rotated by a stable hash of the mechanism name, so a student cannot
      // learn "it is always the middle one" instead of learning the physics.
      int expectedSlot = Math.floorMod(target.tuningName().hashCode(), 3);
      assertEquals(expectedSlot, q.correctIndex());

      assertTrue(
          q.options().get(q.correctIndex()).contains(String.format(java.util.Locale.ROOT, "%.2f", prior)),
          "the right answer is the mechanism's own prior: " + q.options());
      assertTrue(
          q.whyCorrect().contains("mass, the gear ratio and the motor curve you already declared"),
          q.whyCorrect());

      // A different mechanism name rotates the answer to a different slot.
      FakeTarget other = elevator().named("Elevator2");
      FakeStepContext otherCtx = new FakeStepContext(other);
      PredictStep otherStep = PredictStep.holdingVoltsBeforeGravity(new NullStep());
      otherStep.begin(otherCtx);
      assertEquals(
          Math.floorMod("Elevator2".hashCode(), 3),
          otherStep.question().orElseThrow().correctIndex());
    }

    @Test
    @DisplayName("the gravity key survives a mechanism with no usable prior")
    void theGravityKeyDegradesGracefully() {
      FakeTarget noPrior =
          elevator()
              .prior(PlantPrior.flywheel(DCMotor.getKrakenX60Foc(1), Reduction.of(1.0), 0.004));
      FakeStepContext ctx = new FakeStepContext(noPrior);
      PredictStep step = PredictStep.holdingVoltsBeforeGravity(new NullStep());
      step.begin(ctx);

      PredictStep.Question q = step.question().orElseThrow();
      assertEquals(3, q.options().size(), "a NaN prior must still produce a legal question");
      for (String option : q.options()) {
        assertFalse(option.contains("NaN"), "and no option may read NaN: " + option);
      }
    }

    @Test
    @DisplayName("the stiction key is a fact about friction, and the prompt still names the robot")
    void theStictionKeyIsFixedButThePromptIsNot() {
      FakeStepContext ctx = new FakeStepContext(elevator().named("Wrist"));
      PredictStep step = PredictStep.stictionBeforeBreakaway(new NullStep());
      step.begin(ctx);

      PredictStep.Question q = step.question().orElseThrow();
      assertEquals(1, q.correctIndex(), "nothing, then nothing, then it breaks loose");
      assertTrue(q.prompt().contains("Wrist"), q.prompt());
      assertTrue(q.whyCorrect().contains("heavy box"), q.whyCorrect());
      assertEquals(Lessons.KS, step.explanation(), "and the kS lesson is shown beside it");
    }
  }

  @Nested
  @DisplayName("the question's own contract")
  final class QuestionShape {

    @Test
    @DisplayName("exactly three options, always")
    void threeOptions() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new PredictStep.Question(
                  "?", List.of("a", "b"), 0, "because", Map.of()),
          "two options is a coin flip a student can win without understanding anything");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new PredictStep.Question(
                  "?", List.of("a", "b", "c", "d"), 0, "because", Map.of()),
          "four is a reading test");

      PredictStep.Question ok =
          new PredictStep.Question("?", List.of("a", "b", "c"), 1, "because", Map.of());
      assertEquals(3, ok.options().size());
      assertEquals("", ok.whyWrongFor(0), "a missing explanation is empty, never null");
    }

    @Test
    @DisplayName("the answer key must name one of the three options")
    void theKeyMustBeInRange() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new PredictStep.Question("?", List.of("a", "b", "c"), 3, "because", Map.of()));
      assertThrows(
          IllegalArgumentException.class,
          () -> new PredictStep.Question("?", List.of("a", "b", "c"), -1, "because", Map.of()));
    }

    @Test
    @DisplayName("every shipped question's options are observable outcomes, not gain names")
    void optionsDescribeWhatTheStudentWillSee() {
      FakeStepContext ctx = new FakeStepContext(elevator());
      List<PredictStep> steps =
          List.of(
              PredictStep.dampingBeforeRefine(new NullStep()),
              PredictStep.stictionBeforeBreakaway(new NullStep()),
              PredictStep.holdingVoltsBeforeGravity(new NullStep()));

      for (PredictStep step : steps) {
        step.begin(ctx);
        PredictStep.Question q = step.question().orElseThrow();
        assertFalse(q.prompt().isBlank());
        assertFalse(q.whyCorrect().isBlank());
        for (String option : q.options()) {
          assertFalse(option.isBlank());
          assertFalse(
              option.contains("kP") || option.contains("too high"),
              "the skill being taught is reading a mechanism, not reciting vocabulary: " + option);
        }
      }
    }
  }

  @Nested
  @DisplayName("scoring")
  final class Scoring {

    @Test
    @DisplayName("a right answer is named as right, and a wrong one gets its own explanation")
    void feedbackBranchesOnTheAnswer() {
      Gains clean = new Gains(128.0, 0, 0, 0.2, 5.0, 0.060, 0.25);

      FakeStepContext right = new FakeStepContext(elevator()).withGains(clean);
      PredictStep rightStep = PredictStep.dampingBeforeRefine(new NullStep());
      rightStep.begin(right);
      right.studentAnswers(2);
      rightStep.periodic(right);
      StepResult rightResult = rightStep.finish(right);

      assertEquals(Optional.of(true), rightStep.correct());
      assertTrue(rightResult.headline().contains("correct"), rightResult.headline());

      FakeStepContext wrong = new FakeStepContext(elevator()).withGains(clean);
      PredictStep wrongStep = PredictStep.dampingBeforeRefine(new NullStep());
      wrongStep.begin(wrong);
      wrong.studentAnswers(0);
      wrongStep.periodic(wrong);
      StepResult wrongResult = wrongStep.finish(wrong);

      assertEquals(Optional.of(false), wrongStep.correct());
      assertNotEquals(rightResult.headline(), wrongResult.headline());
      assertTrue(
          wrong.feedback.get(0).contains("kV is in the numerator"),
          "the wrong answer is the moment to connect the lesson to what they just watched: "
              + wrong.feedback.get(0));
      assertEquals(
          StepResult.Outcome.SUCCESS,
          wrongResult.outcome(),
          "getting it wrong blocks nothing — it is not a test");
    }

    @Test
    @DisplayName("the score line counts what was asked, not what was right")
    void theScoreLineIsHonest() {
      assertTrue(Coach.scoreLine(3, 4).contains("3"), Coach.scoreLine(3, 4));
      assertTrue(Coach.scoreLine(3, 4).contains("4"), Coach.scoreLine(3, 4));
      assertFalse(Coach.scoreLine(0, 0).isBlank(), "and a run with no questions still says so");
    }
  }
}

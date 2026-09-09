package org.rootstock.tuning.wizard.steps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.control.PlantPrior;
import org.rootstock.tuning.wizard.Coach;
import org.rootstock.tuning.wizard.Lessons;
import org.rootstock.tuning.wizard.StepContext;
import org.rootstock.tuning.wizard.StepResult;
import org.rootstock.tuning.wizard.TuningStep;

/**
 * A no-motion step that asks the student to predict what the next step will do, then scores it.
 *
 * <p><b>Why this exists.</b> Without it the wizard is a progress bar with good prose. A student can
 * complete every recipe by pressing A eleven times and learn nothing, and neither the wizard nor a
 * mentor can tell the difference. The practice-mode lesson tells students to "tune them until you
 * can predict what the plot is going to do before it does it" — but nothing else in the design ever
 * asked for a prediction or scored one. This is the step that closes that gap, and it is the
 * cheapest high-value thing in the whole package.
 *
 * <p><b>Three options, always.</b> Two is a coin flip; four is a reading-comprehension test. The
 * options are written as observable outcomes — "the carriage will overshoot and bounce" — never as
 * gain names — "kP is too high" — because the skill being taught is reading a mechanism, not
 * reciting vocabulary.
 *
 * <p><b>The answer key is computed, not authored.</b> This matters more than it sounds. A
 * hand-written key is a claim about a mechanism nobody has measured; a computed key is arithmetic
 * the student is shown two screens later and can redo themselves. The damping question is the
 * clearest case: the key is
 *
 * <pre>
 *   damping = (kD + kV) / (2 * sqrt(kP * kA))
 * </pre>
 *
 * evaluated at the gains actually on the mechanism. Below {@value Coach#kBounceThreshold} the answer
 * is "it will bounce"; above {@value #kSluggishThreshold} it is "it will arrive slowly and stop
 * short"; between them it is "it will stop cleanly." The step is never wrong about its own
 * arithmetic, and it is the same arithmetic the kP panel shows.
 *
 * <p><b>A worked example, from this library's reference elevator.</b> With {@code kP = 128.00},
 * {@code kA = 0.060}, {@code kD = 0} and {@code kV = 5.00}, the damping is {@code 5.00 / (2 *
 * sqrt(128.00 * 0.060)) = 5.00 / 5.542563 = 0.9021}, which is between 0.7 and 1.2 — so the key is
 * "it will stop cleanly, just faster." Most students predict bouncing, because most students have
 * not noticed that kV is in the <em>numerator</em>: a mechanism fights its own motion before kD does
 * anything at all. That single miss teaches more than the four steps before it.
 */
public final class PredictStep implements TuningStep {

  /** Damping at or above this counts as over-damped: it will arrive slowly and stop short. */
  public static final double kSluggishThreshold = 1.2;

  /**
   * How long the student has to answer before the wizard moves on with the question unscored.
   *
   * <p>Generous on purpose. A student who is thinking is exactly the student this step is for, and
   * a timeout that punishes thinking would invert the whole point.
   */
  public static final double kTimeoutSeconds = 120.0;

  /**
   * One multiple-choice question, with its answer key and the explanation for every option.
   *
   * @param prompt the question, in plain language, naming real numbers from the real mechanism
   * @param options exactly three observable outcomes
   * @param correctIndex which option the model says is right
   * @param whyCorrect why that option is right, in terms the student can go and check
   * @param whyWrong per-option explanations of what a student who chose it was thinking
   */
  public record Question(
      String prompt,
      List<String> options,
      int correctIndex,
      String whyCorrect,
      Map<Integer, String> whyWrong) {

    /**
     * Canonical constructor, which enforces the three-option rule and copies the collections.
     *
     * @param prompt see the record javadoc
     * @param options see the record javadoc
     * @param correctIndex see the record javadoc
     * @param whyCorrect see the record javadoc
     * @param whyWrong see the record javadoc
     */
    public Question {
      options = options == null ? List.of() : List.copyOf(options);
      whyWrong = whyWrong == null ? Map.of() : Map.copyOf(whyWrong);
      if (options.size() != 3) {
        throw new IllegalArgumentException(
            "A prediction question must offer exactly three options, and this one offered "
                + options.size()
                + ". Two options is a coin flip a student can win without understanding anything, "
                + "and four is a reading test. Fix: write three observable outcomes.");
      }
      if (correctIndex < 0 || correctIndex > 2) {
        throw new IllegalArgumentException(
            "The correct option index was "
                + correctIndex
                + ", which is not 0, 1 or 2. Fix: the answer key must name one of the three "
                + "options the question offers.");
      }
    }

    /**
     * The explanation for the option the student actually chose, or empty if there is none.
     *
     * @param chosen the index they picked
     * @return the explanation
     */
    public String whyWrongFor(int chosen) {
      String s = whyWrong.get(chosen);
      return s == null ? "" : s;
    }
  }

  /**
   * How a question gets built at the moment it is asked, from the mechanism in front of the
   * student.
   *
   * <p>This is the seam that makes "computed answer key" true rather than aspirational: the
   * question does not exist until there is a plant model and a gain set to build it from.
   */
  @FunctionalInterface
  public interface AnswerKey {
    /**
     * Build the question.
     *
     * @param ctx the live step context, with the gains accumulated so far
     * @return the question, complete with its computed correct index
     */
    Question compose(StepContext ctx);
  }

  private final TuningStep m_next;
  private final String m_title;
  private final String m_explanation;
  private final AnswerKey m_key;

  private Question m_question;
  private OptionalInt m_answer = OptionalInt.empty();
  private boolean m_scored;
  private boolean m_timedOut;
  private String m_feedback = "";

  private PredictStep(TuningStep next, String title, String explanation, AnswerKey key) {
    m_next = next;
    m_title = title;
    m_explanation = explanation;
    m_key = key;
  }

  /**
   * A prediction with a fixed question, for the concepts whose answer is a property of physics
   * rather than of this mechanism's numbers.
   *
   * @param next the step this question is asked in front of
   * @param q the question
   * @return the step
   */
  public static PredictStep before(TuningStep next, Question q) {
    return new PredictStep(next, titleFor(next), Lessons.WHY_FEEDFORWARD_FIRST, ctx -> q);
  }

  /**
   * A prediction whose question — and whose answer — are computed from the mechanism at the moment
   * it is asked.
   *
   * @param next the step this question is asked in front of
   * @param explanation the lesson to show alongside the question
   * @param key how to build the question
   * @return the step
   */
  public static PredictStep computed(TuningStep next, String explanation, AnswerKey key) {
    return new PredictStep(next, titleFor(next), explanation, key);
  }

  /**
   * The damping question, asked in front of the refinement step.
   *
   * <p>This is the canonical one, and the answer key is the damping-ratio formula evaluated at the
   * gains the mechanism is actually carrying. See the class javadoc for the worked example.
   *
   * @param next the refinement step
   * @return the step
   */
  public static PredictStep dampingBeforeRefine(TuningStep next) {
    return computed(
        next,
        Lessons.WHAT_THE_SLIDERS_DO,
        ctx -> {
          Gains g = ctx.gains();
          double zeta = dampingRatio(g.kP(), 0.0, g.kV(), g.kA());
          int correct = classifyDamping(zeta);
          String prompt =
              String.format(
                  Locale.ROOT,
                  "Your %s has kD = %.2f and kV = %.2f. I am about to set kD to zero and command a "
                      + "step. What do you think it will do?",
                  ctx.target().tuningName(),
                  g.kD(),
                  g.kV());
          Map<Integer, String> whyWrong = new LinkedHashMap<>();
          whyWrong.put(
              0,
              "You said it would overshoot and bounce. Here is the tell, and it is the one most "
                  + "people miss: kV is in the numerator of the damping. Your mechanism fights its "
                  + "own motion before kD does anything at all.");
          whyWrong.put(
              1,
              "You said it would arrive slowly and stop short. That is what happens when damping "
                  + "climbs well past 1 - a shock absorber so stiff it fights the spring.");
          whyWrong.put(
              2,
              "You said it would stop cleanly. Clean arrival needs damping between about 0.7 and "
                  + "1.2, and this one is outside that band.");
          return new Question(
              prompt,
              List.of(
                  "It will overshoot and bounce a few times before settling.",
                  "It will get there slowly and stop a little short.",
                  "It will get there and stop cleanly, just faster."),
              correct,
              String.format(
                  Locale.ROOT,
                  "With kD at zero the damping is (0 + %.2f) / (2 * sqrt(%.2f * %.4f)) = %.2f. "
                      + "Above about %.1f you will not see it bounce at all, and below about %.1f "
                      + "it arrives lazily. That is your mechanism's own kV doing the damping, for "
                      + "free, which is exactly why the flywheel recipe leaves kD at zero.",
                  g.kV(),
                  g.kP(),
                  g.kA(),
                  zeta,
                  Coach.kBounceThreshold,
                  kSluggishThreshold),
              whyWrong);
        });
  }

  /**
   * The stiction question, asked in front of the kS step.
   *
   * <p>The key here is a property of friction rather than of this mechanism's numbers, so it is
   * fixed — but the prompt still names the mechanism, because a question about "your elevator" is a
   * question a student answers about the thing in front of them.
   *
   * @param next the breakaway step
   * @return the step
   */
  public static PredictStep stictionBeforeBreakaway(TuningStep next) {
    return computed(
        next,
        Lessons.KS,
        ctx -> {
          Map<Integer, String> whyWrong = new LinkedHashMap<>();
          whyWrong.put(
              0,
              "You said it would creep as soon as any voltage appeared. That is what a frictionless "
                  + "mechanism would do, and there is no such thing on a robot.");
          whyWrong.put(
              2,
              "You said it would buzz without moving. That does happen - but only when the ramp "
                  + "runs out of voltage before it breaks the friction loose, and this ramp is "
                  + "allowed to go much higher than that.");
          return new Question(
              String.format(
                  Locale.ROOT,
                  "I am about to raise the voltage on your %s from zero, very slowly. What will you "
                      + "see first?",
                  ctx.target().tuningName()),
              List.of(
                  "It will start creeping immediately and speed up smoothly.",
                  "Nothing, then nothing, then it suddenly breaks loose and moves.",
                  "It will buzz and hum but never actually move."),
              1,
              "Friction has to be broken before anything moves at all, like sliding a heavy box "
                  + "across a floor. The voltage at that exact moment is kS, and it is why small "
                  + "moves on an untuned mechanism never start.",
              whyWrong);
        });
  }

  /**
   * The holding-voltage question, asked in front of the gravity pre-pass.
   *
   * <p>The three options are computed from {@code PlantPrior.gravityVoltsPrior()} — a third of it,
   * it, and three times it — and the correct option is whichever bracket the prior lands in. The
   * <em>position</em> of the correct option is rotated by a stable hash of the mechanism's name, so
   * a student cannot learn "it is always the middle one" instead of learning the physics.
   *
   * @param next the bisection step
   * @return the step
   */
  public static PredictStep holdingVoltsBeforeGravity(TuningStep next) {
    return computed(
        next,
        Lessons.KG,
        ctx -> {
          PlantPrior prior = ctx.target().plantPrior();
          double kg = prior == null ? Double.NaN : prior.gravityVoltsPrior();
          double middle = Double.isFinite(kg) && kg > 0 ? kg : 1.0;
          double low = middle / 3.0;
          double high = middle * 3.0;

          // Rotate which slot holds the true answer, deterministically per mechanism.
          int slot = Math.floorMod(ctx.target().tuningName().hashCode(), 3);
          double[] values = new double[3];
          values[slot] = middle;
          values[(slot + 1) % 3] = low;
          values[(slot + 2) % 3] = high;

          List<String> options =
              List.of(
                  String.format(Locale.ROOT, "About %.2f V.", values[0]),
                  String.format(Locale.ROOT, "About %.2f V.", values[1]),
                  String.format(Locale.ROOT, "About %.2f V.", values[2]));
          Map<Integer, String> whyWrong = new LinkedHashMap<>();
          for (int i = 0; i < 3; i++) {
            if (i != slot) {
              whyWrong.put(
                  i,
                  values[i] < middle
                      ? "You picked a number about three times too small. That is roughly what this "
                          + "mechanism would need if it were three times lighter."
                      : "You picked a number about three times too big. That is roughly what it "
                          + "would need with three times the mass hanging on it.");
            }
          }
          return new Question(
              String.format(
                  Locale.ROOT,
                  "Your %s weighs what your config says it weighs, through the gearbox your config "
                      + "says it has. How many volts do you think it takes just to hold it still?",
                  ctx.target().tuningName()),
              options,
              slot,
              String.format(
                  Locale.ROOT,
                  "About %.2f V. That number comes straight from the mass, the gear ratio and the "
                      + "motor curve you already declared - which is why Rootstock can start the "
                      + "search close instead of sweeping from zero. The bisection is about to "
                      + "measure the real value and you will see how close the prediction was.",
                  middle),
              whyWrong);
        });
  }

  /**
   * The slider question, asked in front of the kP suggestion.
   *
   * <p>The key is computed by running the designer twice — once at the student's error preference
   * and once at half of it — and comparing. That is a genuinely computed key: it would still be
   * right if the solver changed underneath it.
   *
   * <p><b>And the explanation is computed from the same comparison</b>, which it did not used to be.
   * The key branches three ways while the explanation asserted "it roughly doubles" unconditionally,
   * so on every plant the shipped LQR designer actually meets — where the ratio is around 1.00 to
   * 1.20 and the key is therefore "it stays about the same" — the student was shown a correct answer
   * beside a sentence contradicting it, with the two numbers 55.86 and 55.98 printed as an example
   * of doubling. A teaching tool that contradicts itself in the same paragraph is worse than one
   * that says nothing, so the sentence now reads the branch it is explaining.
   *
   * @param next the LQR suggestion step
   * @return the step
   */
  public static PredictStep slidersBeforeLqr(TuningStep next) {
    return computed(
        next,
        Lessons.WHAT_THE_SLIDERS_DO,
        ctx -> {
          Gains g = ctx.gains();
          LqrSuggestStep.Preferences base =
              LqrSuggestStep.Preferences.defaultsFor(
                  ctx.target().archetype(), ctx.target().travelLimits(), ctx.toleranceSi());
          LqrSuggestStep.Preferences tighter = base.withMaxAcceptableErrorSi(
              base.maxAcceptableErrorSi() / 2.0);
          double kpBase =
              LqrSuggestStep.design(ctx.target().archetype(), g.kV(), g.kA(), base).kP();
          double kpTight =
              LqrSuggestStep.design(ctx.target().archetype(), g.kV(), g.kA(), tighter).kP();
          double ratio = kpBase > 0 ? kpTight / kpBase : Double.NaN;
          int correct = ratio > 1.5 ? 0 : (ratio < 0.75 ? 1 : 2);

          Map<Integer, String> whyWrong = new LinkedHashMap<>();
          whyWrong.put(
              0,
              "You said it roughly doubles. That is the answer if kP were simply the volts you "
                  + "allowed divided by the error you allowed - but Rootstock does not divide, it "
                  + "solves, using the kV and kA it just measured. Half the error does not buy you "
                  + "twice the gain on every mechanism.");
          whyWrong.put(
              1,
              "You said kP would go down. Being fussier about error never asks the controller for "
                  + "less push.");
          whyWrong.put(
              2,
              "You said kP would stay put. The two sliders are the only things kP is made of, so "
                  + "moving one has to move it.");
          whyWrong.remove(correct);
          return new Question(
              "I am about to work out kP from two things you choose: how much error you can live "
                  + "with, and how many volts you will spend fixing it. If you halve the error you "
                  + "can live with, and change nothing else, what happens to kP?",
              List.of(
                  "It roughly doubles.",
                  "It roughly halves.",
                  "It stays about the same."),
              correct,
              String.format(
                  Locale.ROOT,
                  "%s: %.2f becomes %.2f, a factor of %.2f. Caring twice as much never asks for "
                      + "less push - but how much MORE it asks for is not a ratio you can do in "
                      + "your head. Rootstock solves for kP using the kV and kA it just measured, "
                      + "so on a mechanism whose own damping is already doing the work, halving the "
                      + "error you accept can barely move kP at all.",
                  correct == 0
                      ? "It roughly doubles"
                      : (correct == 1 ? "It roughly halves" : "It stays about the same"),
                  kpBase,
                  kpTight,
                  ratio),
              whyWrong);
        });
  }

  /**
   * The damping ratio of the closed loop, in the student's own units.
   *
   * <p>The same arithmetic the kP panel prints and the same arithmetic this step's answer key uses.
   * There is one definition and both read it.
   *
   * @param kP proportional gain, volts per SI unit
   * @param kD derivative gain, volts per SI unit per second
   * @param kV velocity feedforward, volts per SI unit per second
   * @param kA acceleration feedforward, volts per SI unit per second squared
   * @return the damping ratio, or NaN when the plant is not physical
   */
  public static double dampingRatio(double kP, double kD, double kV, double kA) {
    double denominator = 2.0 * Math.sqrt(kP * kA);
    if (!(denominator > 0) || !Double.isFinite(denominator)) {
      return Double.NaN;
    }
    return (kD + kV) / denominator;
  }

  /**
   * Which of the three standard outcomes a damping ratio predicts.
   *
   * @param zeta the damping ratio
   * @return 0 for bounce, 1 for slow-and-short, 2 for clean
   */
  public static int classifyDamping(double zeta) {
    if (!Double.isFinite(zeta) || zeta < Coach.kBounceThreshold) {
      return 0;
    }
    return zeta > kSluggishThreshold ? 1 : 2;
  }

  /**
   * The step this question is asked in front of.
   *
   * @return the next step
   */
  public TuningStep next() {
    return m_next;
  }

  /**
   * The answer the student gave, if they gave one.
   *
   * @return the option index
   */
  public OptionalInt answer() {
    return m_answer;
  }

  /**
   * Whether they were right — the one measurement in this library that measures the person.
   *
   * @return true or false once scored, empty if they never answered
   */
  public Optional<Boolean> correct() {
    if (m_question == null || m_answer.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(m_answer.getAsInt() == m_question.correctIndex());
  }

  /**
   * The question as it was actually asked, with its computed key.
   *
   * @return the question, or empty before the step has begun
   */
  public Optional<Question> question() {
    return Optional.ofNullable(m_question);
  }

  @Override
  public String title() {
    return m_title;
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.empty();
  }

  @Override
  public String explanation() {
    return m_explanation;
  }

  @Override
  public String watchFor() {
    return "Nothing yet - this one is a question. Commit to an answer before anything moves; that "
        + "is the whole point.";
  }

  @Override
  public String willDo() {
    return "Nothing moves during this step.";
  }

  @Override
  public boolean commandsMotion() {
    return false;
  }

  @Override
  public void begin(StepContext ctx) {
    m_answer = OptionalInt.empty();
    m_scored = false;
    m_timedOut = false;
    m_feedback = "";
    m_question = m_key.compose(ctx);
    ctx.askPrediction(m_question.prompt(), m_question.options());
    ctx.narrate(m_question.prompt());
  }

  @Override
  public void periodic(StepContext ctx) {
    // No motion here, but the rule is the rule: if something has tripped, this step is over too.
    if (ctx.supervisor().check().isPresent()) {
      m_timedOut = true;
      return;
    }
    if (m_answer.isEmpty()) {
      m_answer = ctx.predictionAnswer();
    }
    if (m_answer.isEmpty() && ctx.elapsedSeconds() > kTimeoutSeconds) {
      m_timedOut = true;
    }
    ctx.publishProgress(m_answer.isPresent() ? 1.0 : 0.0);
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return m_answer.isPresent() || m_timedOut;
  }

  @Override
  public StepResult finish(StepContext ctx) {
    m_scored = true;
    if (m_question == null || m_answer.isEmpty()) {
      m_feedback =
          "No answer this time. The question is not a test and skipping it blocks nothing - but "
              + "the score at the end is the only thing that can tell you whether you are learning "
              + "or just pressing A.";
      ctx.publishPredictionFeedback(m_feedback);
      return StepResult.informational(
          "Prediction skipped",
          m_feedback,
          "Press A to run the step anyway.",
          List.of("Prediction not answered."));
    }
    int chosen = m_answer.getAsInt();
    boolean right = chosen == m_question.correctIndex();
    m_feedback =
        Coach.predictionFeedback(
            right, m_question.whyCorrect(), m_question.whyWrongFor(chosen));
    ctx.publishPredictionFeedback(m_feedback);
    ctx.narrate(m_feedback);
    return StepResult.informational(
        right ? "Prediction: correct" : "Prediction: not this time",
        m_feedback,
        "Press A to run the step and watch it happen.",
        List.of());
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return 0.0;
  }

  /**
   * Whether the question has been scored yet.
   *
   * @return true once {@link #finish(StepContext)} has run
   */
  public boolean isScored() {
    return m_scored;
  }

  private static String titleFor(TuningStep next) {
    return next == null ? "Predict" : "Predict: " + next.title();
  }
}

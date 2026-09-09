package org.rootstock.tuning.wizard;

import java.util.Optional;
import org.rootstock.control.GainId;

/**
 * One teachable move in a recipe.
 *
 * <p>A step is a small strategy object with a lesson attached. The lesson is not a comment on the
 * step — it is half of the step's job. A step that measures kS perfectly and explains nothing has
 * failed at the thing this package exists for.
 *
 * <p><b>Every step is a sub-state machine driven from {@link #periodic(StepContext)}, never a
 * blocking loop.</b> There is no {@code yieldOneLoop()} to write: the robot's main loop calls
 * {@code periodic()} every 20 ms, background threads are banned, and a replay must reproduce the
 * same call sequence it recorded. A step that blocks would also freeze the twelve abort conditions
 * for the duration of the most dangerous thing the library does.
 *
 * <p><b>Every implementation's first statement inside {@code periodic} is:</b>
 *
 * <pre>
 *   if (ctx.supervisor().check().isPresent()) { stopThisStep(); return; }
 * </pre>
 *
 * <p>The abort list is only live if something calls it, and this is the only place that does.
 *
 * <p><b>{@link #begin(StepContext)} must fully reset the step.</b> The student can press Retry, and
 * Retry re-enters the same instance. A step that carries state across {@code begin()} will report
 * the previous attempt's answer on the second attempt.
 */
public interface TuningStep {

  /**
   * Short title, for example {@code "Find kS"}. Rendered as {@code "Step 3 of 9 - Find kS"}.
   *
   * @return the title
   */
  String title();

  /**
   * Which gain this step produces, if any.
   *
   * <p>Empty for pre-flight, prediction and verification steps. The wizard uses this to know what
   * to revert when the student presses Back, so a step that returns a gain it does not actually
   * produce will silently corrupt the Back path.
   *
   * @return the gain this step determines, or empty
   */
  Optional<GainId> produces();

  /**
   * The lesson shown before the student pulls the trigger.
   *
   * <p>Normally one of the constants in {@link Lessons}. See that class for why the text lives in
   * the source rather than in a doc site.
   *
   * @return the lesson prose
   */
  String explanation();

  /**
   * What the student should physically watch for, in one sentence.
   *
   * <p>This is the sentence that turns a plot into an observation. "Watch for the exact moment the
   * wheel starts to turn" is a step's whole teaching payload compressed into nine words.
   *
   * @return the watch-for sentence
   */
  String watchFor();

  /**
   * What the robot is about to do, in one sentence, shown while armed and before any motion.
   *
   * <p>A student holding a trigger next to a mechanism is entitled to know what is about to happen
   * before it happens.
   *
   * @return the will-do sentence
   */
  String willDo();

  /**
   * Called once when the step is entered, and again on every Retry.
   *
   * @param ctx everything the step is allowed to touch
   */
  void begin(StepContext ctx);

  /**
   * Called every loop while the step is running. Must not block. See the class javadoc for the
   * mandatory first statement.
   *
   * @param ctx everything the step is allowed to touch
   */
  void periodic(StepContext ctx);

  /**
   * Whether the step has enough data to produce a result.
   *
   * @param ctx everything the step is allowed to touch
   * @return true when {@link #finish(StepContext)} may be called
   */
  boolean isComplete(StepContext ctx);

  /**
   * Produce the result. Called once, after {@link #isComplete(StepContext)} returns true.
   *
   * @param ctx everything the step is allowed to touch
   * @return the outcome, for the student to accept, retry or skip
   */
  StepResult finish(StepContext ctx);

  /**
   * Estimated duration, in seconds, for the progress bar.
   *
   * @param ctx everything the step is allowed to touch
   * @return the estimate; zero for a step that commands no motion
   */
  double expectedSeconds(StepContext ctx);

  /**
   * Whether this step can command voltage.
   *
   * <p>The wizard only arms the supervisor for steps that answer true, which is what keeps a
   * prediction question and an LQR panel from requiring a held trigger. It is a UI affordance and
   * <em>not</em> a safety mechanism: the safety mechanism is that the supervisor is not armed, so a
   * step that lied here and called {@code commandVolts} would get zero volts and silence.
   *
   * @return true if the step needs an armed supervisor, defaulting to true
   */
  default boolean commandsMotion() {
    return true;
  }
}

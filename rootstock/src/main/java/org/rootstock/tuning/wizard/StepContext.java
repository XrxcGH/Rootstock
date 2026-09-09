package org.rootstock.tuning.wizard;

import java.util.List;
import java.util.OptionalInt;
import org.rootstock.control.Gains;
import org.rootstock.control.TuningSupervisor;
import org.rootstock.control.TuningTarget;
import org.rootstock.tuning.sysid.SampleBuffer;

/**
 * Everything a step is allowed to touch — and, just as importantly, the boundary of everything it
 * is not.
 *
 * <p>A step gets the mechanism, the supervisor, the gains accumulated so far, a clock, a ring
 * buffer and a way to talk to the student. It does not get the {@code CommandScheduler}, the
 * {@code GainSink}, NetworkTables, or a raw motor. The one route to voltage is {@link
 * #supervisor()}, which is what makes ArchUnit rule 6 a property of the design rather than a code
 * review habit.
 */
public interface StepContext {

  /**
   * The mechanism being tuned.
   *
   * @return the target
   */
  TuningTarget target();

  /**
   * The supervisor — the only way to command volts.
   *
   * @return the supervisor
   */
  TuningSupervisor supervisor();

  /**
   * The gains as accumulated so far this session, including every gain the student has accepted.
   *
   * <p>This is what makes the order of operations real: the kS step reads the kG the gravity
   * pre-pass just produced and uses it to cancel gravity, because otherwise the "motion" it detects
   * is the carriage falling and kS comes out about ten times too large.
   *
   * @return the running gain set
   */
  Gains gains();

  /**
   * Hand a whole gain set back to the wizard.
   *
   * <p>Used by the refinement step, which legitimately moves more than one gain across its
   * iterations and cannot express that through {@link StepResult}'s single gain field. The wizard
   * stages these; nothing is written to the mechanism or to disk until the student accepts.
   *
   * @param gains the proposed gain set
   */
  void proposeGains(Gains gains);

  /**
   * The effective tolerance, in SI units, from {@link TuningSupervisor#effectiveToleranceSi()}.
   *
   * @return the tolerance in metres or radians
   */
  double toleranceSi();

  /**
   * Seconds since this step began, from {@code Clock}, never from a {@code Timer}.
   *
   * @return elapsed seconds
   */
  double elapsedSeconds();

  /**
   * The loop period, from {@code Clock.dt()}.
   *
   * @return the period in seconds
   */
  double dt();

  /**
   * The decimated ring buffer that feeds the plots and the response analyser.
   *
   * @return the buffer
   */
  SampleBuffer buffer();

  /**
   * Append a line to the narration log shown to the student and included in the report.
   *
   * <p>Narration is written in the second person, in plain language, and says what is happening and
   * why — never what a variable is. "Switching to coast and letting go for half a second, to see
   * which way this falls" is narration. "phase = SIGN_DRIFT" is not.
   *
   * @param line the line
   */
  void narrate(String line);

  /**
   * Publish this step's progress for the progress bar.
   *
   * @param fraction0to1 progress, clamped by the implementation
   */
  void publishProgress(double fraction0to1);

  /**
   * Which recipe mode is running.
   *
   * <p>A step may use this to shorten its narration in {@link TuningRecipe.Mode#EXPRESS}. It may
   * never use it to skip a check.
   *
   * @return the mode
   */
  TuningRecipe.Mode mode();

  /**
   * Ask the student a multiple-choice question and publish it.
   *
   * @param prompt the question, in plain language
   * @param options exactly three observable outcomes
   */
  void askPrediction(String prompt, List<String> options);

  /**
   * The student's answer to the current question, from the gamepad or from the dashboard.
   *
   * @return the chosen option index, or empty if they have not answered yet
   */
  OptionalInt predictionAnswer();

  /**
   * Publish the branched coaching text after a prediction has been scored.
   *
   * @param feedback the text, already branched on right-versus-wrong
   */
  void publishPredictionFeedback(String feedback);
}

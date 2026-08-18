package org.pumpkinlib.tuning.wizard;

import java.util.List;
import java.util.Optional;
import org.pumpkinlib.control.GainId;

/**
 * The outcome of a step, presented to the student for accept, retry or skip.
 *
 * <p>Four of these fields are numbers and five are sentences, and that ratio is deliberate. A
 * result that says {@code kS = 0.220} teaches nothing; a result that says {@code kS = 0.220 V (0.226
 * up / 0.214 down, 6% asymmetry - fine)} teaches a student what asymmetry is and that theirs is
 * fine.
 *
 * @param outcome whether the step succeeded, wants a retry, failed, or was skipped
 * @param gain which gain this result sets, empty for a step that produces none
 * @param value the suggested new value, in volts per SI unit
 * @param previousValue what the gain was before, so the student can see the change
 * @param headline one line, for example {@code "kS = 0.220 V"}
 * @param quality the measurement's own quality, for example {@code "Fit R2 = 0.981, RMSE 0.09 V"}
 * @param verdict a plain-language diagnosis of what the mechanism did
 * @param recommendation a plain-language next action
 * @param predictionCorrect whether the student's prediction for this step was right; see below
 * @param warnings anything the student should read before accepting
 */
public record StepResult(
    Outcome outcome,
    Optional<GainId> gain,
    double value,
    double previousValue,
    String headline,
    String quality,
    String verdict,
    String recommendation,

    /*
     * Whether the student's prediction for this step was right. Empty when the step had no
     * PredictStep in front of it, or when the student skipped the question.
     *
     * This is the only field in the whole design that measures the STUDENT rather than the
     * mechanism, and it is what makes the difference between a teaching tool and a progress bar
     * visible to a mentor who was not in the room.
     */
    Optional<Boolean> predictionCorrect,
    List<String> warnings) {

  /** How the step ended. */
  public enum Outcome {
    /** The step produced a value the student can accept. */
    SUCCESS,
    /** The step produced something, but it is not trustworthy; running it again is advised. */
    RETRY_SUGGESTED,
    /** The step could not produce a value at all. The gain is untouched. */
    FAILED,
    /** The student skipped the step. The gain is untouched. */
    SKIPPED
  }

  /**
   * Canonical constructor, with the two collection fields defensively copied and normalised.
   *
   * @param outcome see the record javadoc
   * @param gain see the record javadoc
   * @param value see the record javadoc
   * @param previousValue see the record javadoc
   * @param headline see the record javadoc
   * @param quality see the record javadoc
   * @param verdict see the record javadoc
   * @param recommendation see the record javadoc
   * @param predictionCorrect see the record javadoc
   * @param warnings see the record javadoc
   */
  public StepResult {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    gain = gain == null ? Optional.empty() : gain;
    predictionCorrect = predictionCorrect == null ? Optional.empty() : predictionCorrect;
    headline = headline == null ? "" : headline;
    quality = quality == null ? "" : quality;
    verdict = verdict == null ? "" : verdict;
    recommendation = recommendation == null ? "" : recommendation;
  }

  /**
   * A successful result that sets a gain.
   *
   * @param gain the gain produced
   * @param value the new value
   * @param previousValue the old value
   * @param headline one line naming the number and its unit
   * @param quality the measurement quality
   * @param verdict the plain-language diagnosis
   * @param recommendation the plain-language next action
   * @param warnings anything to read first
   * @return the result
   */
  public static StepResult success(
      GainId gain,
      double value,
      double previousValue,
      String headline,
      String quality,
      String verdict,
      String recommendation,
      List<String> warnings) {
    return new StepResult(
        Outcome.SUCCESS,
        Optional.of(gain),
        value,
        previousValue,
        headline,
        quality,
        verdict,
        recommendation,
        Optional.empty(),
        warnings);
  }

  /**
   * A successful result that produces no gain — a pre-flight, a prediction or a verification.
   *
   * @param headline one line
   * @param verdict the plain-language diagnosis
   * @param recommendation the plain-language next action
   * @param warnings anything to read first
   * @return the result
   */
  public static StepResult informational(
      String headline, String verdict, String recommendation, List<String> warnings) {
    return new StepResult(
        Outcome.SUCCESS,
        Optional.empty(),
        Double.NaN,
        Double.NaN,
        headline,
        "",
        verdict,
        recommendation,
        Optional.empty(),
        warnings);
  }

  /**
   * A result that could not be trusted. The gain is left alone and the student is told why.
   *
   * @param headline one line
   * @param verdict what went wrong, in plain language
   * @param recommendation what to do about it
   * @param warnings anything else
   * @return the result
   */
  public static StepResult retry(
      String headline, String verdict, String recommendation, List<String> warnings) {
    return new StepResult(
        Outcome.RETRY_SUGGESTED,
        Optional.empty(),
        Double.NaN,
        Double.NaN,
        headline,
        "",
        verdict,
        recommendation,
        Optional.empty(),
        warnings);
  }

  /**
   * A step that could not run at all.
   *
   * @param headline one line
   * @param verdict what went wrong, in plain language
   * @param recommendation what to do about it
   * @return the result
   */
  public static StepResult failed(String headline, String verdict, String recommendation) {
    return new StepResult(
        Outcome.FAILED,
        Optional.empty(),
        Double.NaN,
        Double.NaN,
        headline,
        "",
        verdict,
        recommendation,
        Optional.empty(),
        List.of());
  }

  /**
   * The same result with a prediction score attached.
   *
   * <p>The wizard calls this, not the step: a step cannot know whether a {@code PredictStep} ran in
   * front of it, and asking it to would push the scorekeeping into eight places instead of one.
   *
   * @param correct whether the preceding prediction was right, or empty for "not asked"
   * @return a copy carrying the score
   */
  public StepResult withPrediction(Optional<Boolean> correct) {
    return new StepResult(
        outcome,
        gain,
        value,
        previousValue,
        headline,
        quality,
        verdict,
        recommendation,
        correct == null ? Optional.empty() : correct,
        warnings);
  }

  /**
   * Whether accepting this result should change a gain.
   *
   * @return true if the outcome is {@link Outcome#SUCCESS} and a gain is present
   */
  public boolean setsAGain() {
    return outcome == Outcome.SUCCESS && gain.isPresent() && Double.isFinite(value);
  }
}

package org.rootstock.tuning.wizard;

import java.util.Locale;
import org.rootstock.tuning.diagnostics.ResponseClass;
import org.rootstock.tuning.diagnostics.ResponseVerdict;

/**
 * Turns a machine verdict into a sentence a student can act on — and branches on whether they
 * predicted it.
 *
 * <p>Two jobs live here, and the second one is the reason this class is not just a switch inside
 * the analyser.
 *
 * <p><b>Job one: say what happened, in the units the student is standing in front of.</b> {@code
 * ResponseClass.OVERSHOOT_RING} is a fact about a plot. "It overshoots by 14% and then rings four
 * times before settling - that is a stiff spring with no shock absorber" is a fact about a
 * mechanism, and only one of those is something a fourteen-year-old can check by looking.
 *
 * <p><b>Job two: connect it to what they said would happen.</b> A correct prediction is the moment
 * to name the concept, because the student has just earned the label. An incorrect one is the moment
 * to connect the lesson to what they have this second watched with their own eyes, which is the only
 * time the connection is cheap to make. Coaching text that ignores the prediction wastes both.
 */
public final class Coach {

  /** The damping ratio below which a student will visibly see the mechanism bounce. */
  public static final double kBounceThreshold = 0.7;

  /** Above this damping ratio the response is over-damped: no overshoot, but lazy arrival. */
  public static final double kOverDampedThreshold = 2.0;

  /** Oscillation at or above this frequency is sensor noise, not the mechanism moving. */
  public static final double kNoiseFrequencyHz = 8.0;

  private Coach() {}

  /**
   * The plain-language diagnosis for a response.
   *
   * <p>Every number in the returned sentence comes from the verdict, so a student can check it
   * against the plot they are looking at. That checkability is the point: a diagnosis they cannot
   * verify is one they have to take on faith, and faith does not transfer to the next mechanism.
   *
   * @param verdict the classified response
   * @param toleranceSi the mechanism's tolerance, for the "inside your tolerance" clause
   * @param unitLabel "m" or "rad", for the numbers
   * @return the diagnosis
   */
  public static String diagnosis(ResponseVerdict verdict, double toleranceSi, String unitLabel) {
    if (verdict == null) {
      return "No response was recorded, so there is nothing to diagnose yet.";
    }
    switch (verdict.classification()) {
      case GOOD:
        return String.format(
            Locale.ROOT,
            "Nice. It got there in %.2f s, overshot by %.1f%%, settled in %.2f s, and finished "
                + "%.4f %s off target - inside your tolerance of %.4f %s.",
            verdict.riseTimeSec(),
            verdict.overshootPct(),
            verdict.settleTimeSec(),
            Math.abs(verdict.steadyStateErrorSi()),
            unitLabel,
            toleranceSi,
            unitLabel);
      case SLUGGISH:
        return String.format(
            Locale.ROOT,
            "It is heading the right way, just lazily. It took %.2f s to cover 10%% to 90%% of the "
                + "move. There is no overshoot at all, which means kP is doing less than it could.",
            verdict.riseTimeSec());
      case OVERSHOOT_RING:
        return String.format(
            Locale.ROOT,
            "It overshoots by %.1f%% and then rings %d times before settling. Damping came out at "
                + "%.2f - anything under about %.1f will visibly bounce. That is a stiff spring "
                + "with no shock absorber.",
            verdict.overshootPct(),
            verdict.oscillationCrossings(),
            verdict.dampingRatio(),
            kBounceThreshold);
      case OSCILLATING:
        if (verdict.oscillationHz() >= kNoiseFrequencyHz) {
          return String.format(
              Locale.ROOT,
              "It is buzzing at %.1f Hz. That is far too fast to be the mechanism itself moving - "
                  + "it is kD amplifying noise in your encoder reading and feeding it back into "
                  + "the motor.",
              verdict.oscillationHz());
        }
        return String.format(
            Locale.ROOT,
            "It is swinging back and forth %.1f times a second and not settling. Damping %.2f. "
                + "This is a kP that is too high for this mechanism - the spring is so stiff it "
                + "throws the mechanism past the target every time.",
            verdict.oscillationHz(),
            verdict.dampingRatio());
      case STEADY_STATE_ERROR:
        return String.format(
            Locale.ROOT,
            "It settles %.4f %s short of the target and just sits there. The controller is holding "
                + "%.2f V trying to close that gap. That voltage is a feedforward term you are "
                + "missing - friction, or gravity - and not something kP should be asked to do.",
            Math.abs(verdict.steadyStateErrorSi()),
            unitLabel,
            verdict.residualVolts());
      case UNSTABLE:
        return "Stopped. The oscillations were getting bigger, not smaller. That is how mechanisms "
            + "break.";
      case INSUFFICIENT_EXCITATION:
      default:
        return String.format(
            Locale.ROOT,
            "That move was too small to learn anything from - your tolerance is %.4f %s and the "
                + "mechanism barely left it.",
            toleranceSi,
            unitLabel);
    }
  }

  /**
   * The plain-language next action for a response.
   *
   * <p>Note what is absent from every branch: an instruction to add kI. A constant offset means a
   * feedforward term is missing, and an integrator hides that instead of fixing it. That refusal is
   * a teaching decision, taken once, here.
   *
   * @param verdict the classified response
   * @return the recommendation
   */
  public static String recommendation(ResponseVerdict verdict) {
    if (verdict == null) {
      return "Run the step first.";
    }
    switch (verdict.classification()) {
      case GOOD:
        return "Nothing to change. Press A to keep these gains.";
      case SLUGGISH:
        return "Turn kP up. Rootstock will multiply it by 1.6 and try again - press A, or press B "
            + "to change it yourself.";
      case OVERSHOOT_RING:
        return "Add damping: cut kP by 30%, or add kD. Rootstock will try kD first, because that "
            + "keeps the mechanism fast. Press A.";
      case OSCILLATING:
        if (verdict.oscillationHz() >= kNoiseFrequencyHz) {
          return "Halve kD. Press A. If the buzz persists at kD = 0, your encoder is noisy or your "
              + "velocity signal is being filtered somewhere you do not know about.";
        }
        return "Cut kP by 40%. Press A. If it still oscillates after two tries, check for "
            + "backlash - no gain can fix slop.";
      case STEADY_STATE_ERROR:
        return "Raise kS if the mechanism is fighting friction, or kG if it is fighting gravity. "
            + "Do not reach for kI: a constant offset means a feedforward term is missing, and "
            + "adding an integrator hides the problem instead of fixing it.";
      case UNSTABLE:
        return "kP has been cut to 40% of what it was and the routine is disarmed. Pull the "
            + "trigger again when you are ready to retry. If this happens twice, your kV or kA "
            + "measurement is probably wrong - re-run the identification steps.";
      case INSUFFICIENT_EXCITATION:
      default:
        return "Nothing changed. Increase the step size, or widen your tolerance if it is "
            + "unrealistically tight.";
    }
  }

  /**
   * The saturation note, appended to any diagnosis when the motor spent time at its ceiling.
   *
   * <p>Almost always missing from hand tuning, and it invalidates the whole observation: while the
   * output is saturated, kP and kD are not in the loop at all. A student drawing conclusions about
   * gains from a saturated response is drawing conclusions about gains that were not running.
   *
   * @param ceilingVolts the voltage ceiling that was hit
   * @return the sentence to append
   */
  public static String saturationNote(double ceilingVolts) {
    return String.format(
        Locale.ROOT,
        " Also: the motor was commanded to its %.1f V ceiling during this move. While it is "
            + "saturated, kP and kD do nothing at all - the mechanism is just going as fast as it "
            + "can. Either use a motion profile so the setpoint stays reachable, or make the step "
            + "smaller.",
        ceilingVolts);
  }

  /**
   * The branched feedback after a prediction has been scored.
   *
   * <p>This method is the difference between a teaching tool and a quiz. A quiz says "correct."
   * This says <em>why</em> it was correct, in terms of a number the student can go and look at.
   *
   * @param correct whether the student called it
   * @param whyCorrect the explanation of the right answer, supplied by the question
   * @param whyTheirsWasWrong the explanation for the specific option they chose, or empty
   * @return the coaching text
   */
  public static String predictionFeedback(
      boolean correct, String whyCorrect, String whyTheirsWasWrong) {
    if (correct) {
      return "You called it. " + safe(whyCorrect);
    }
    String theirs = safe(whyTheirsWasWrong);
    return "Not this time - and this is the useful kind of wrong, because you just watched the "
        + "answer happen. "
        + (theirs.isEmpty() ? "" : theirs + " ")
        + safe(whyCorrect);
  }

  /**
   * The one-line score, for the state screen and for the report.
   *
   * <p>This single line is what lets a mentor who was not in the room tell whether a student ran
   * the wizard or learned from it.
   *
   * @param correct how many predictions the student got right
   * @param asked how many were asked
   * @return the score line, or a line saying none were asked
   */
  public static String scoreLine(int correct, int asked) {
    if (asked <= 0) {
      return "Predictions: none asked (express mode skips them).";
    }
    return String.format(Locale.ROOT, "Predictions: %d of %d correct.", correct, asked);
  }

  /**
   * Which response classes the refinement loop should keep working on.
   *
   * <p>Delegates to {@link ResponseClass#shouldRefine()} rather than re-listing the classes, so the
   * coaching text and the refinement loop cannot drift apart into two different opinions about
   * whether a ringing mechanism is finished.
   *
   * @param classification the classification
   * @return true if another refinement iteration is worth running
   */
  public static boolean shouldKeepRefining(ResponseClass classification) {
    return classification != null && classification.shouldRefine();
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }
}

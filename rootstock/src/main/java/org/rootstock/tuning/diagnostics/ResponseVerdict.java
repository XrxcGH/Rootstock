package org.rootstock.tuning.diagnostics;

import java.util.Locale;

/**
 * Everything a step response was, in numbers and in three sentences.
 *
 * <p><b>Why the numbers and the words travel together.</b> The numbers go to the plot, the log and
 * the tuning report; the words go to the student standing next to the mechanism. Splitting them into
 * two types guarantees that one day a UI shows a headline computed from one response beside metrics
 * computed from another. They are one measurement, so they are one record.
 *
 * <p>{@link Double#NaN} means <b>unknown</b> in every field here, never zero. A rise time of NaN is
 * "it never got to 90%", which is a completely different statement from "it got there instantly",
 * and a residual voltage of NaN is "this mechanism cannot report the feedforward/feedback split",
 * which is different again from "the feedback is contributing nothing". Every consumer, including
 * the plot topics, propagates the NaN rather than substituting zero — a graph that visibly stops is
 * honest, and a flat line at zero is not.
 *
 * @param classification the shape, from the ordered rules
 * @param riseTimeSec 10% to 90% of the commanded change, or NaN if it never reached 90%
 * @param overshootPct how far past the target the peak went, as a percentage; negative means it
 *     never arrived
 * @param settleTimeSec time from the start until it last left the settling band, or NaN if it never
 *     settled
 * @param settled whether it stayed inside the settling band for the final quarter second
 * @param steadyStateErrorSi mean signed error over the last half second, in SI units
 * @param steadyStatePct that error as a percentage of the commanded change
 * @param residualVolts mean feedback-only voltage over the last half second — the voltage the
 *     controller is spending to hold a steady error, which is the missing feedforward term; NaN when
 *     the mechanism cannot report the split
 * @param dampingRatio zeta, recovered by logarithmic decrement; negative means divergent, NaN means
 *     there were not two peaks to measure
 * @param oscillationHz oscillation frequency from zero crossings; NaN below three crossings
 * @param oscillationCrossings how many times the response crossed the target after the peak
 * @param peakVolts the largest magnitude commanded during the window
 * @param saturated whether the command sat at the voltage ceiling for more than 100 ms
 * @param headline one line, always populated
 * @param diagnosis one to three sentences of plain language
 * @param recommendation exactly one concrete action
 */
public record ResponseVerdict(
    ResponseClass classification,
    double riseTimeSec,
    double overshootPct,
    double settleTimeSec,
    boolean settled,
    double steadyStateErrorSi,
    double steadyStatePct,
    double residualVolts,
    double dampingRatio,
    double oscillationHz,
    int oscillationCrossings,
    double peakVolts,
    boolean saturated,
    String headline,
    String diagnosis,
    String recommendation) {

  /**
   * The verdict a mechanism gets when the move was too small to say anything.
   *
   * @param commandedChange how far it was actually asked to move, in SI
   * @param toleranceSi the mechanism's tolerance, in SI
   * @return a fully populated verdict with every metric NaN and a sentence explaining why
   */
  public static ResponseVerdict insufficient(double commandedChange, double toleranceSi) {
    String diagnosis =
        String.format(
            Locale.ROOT,
            "That move was too small to learn anything from - it only travelled %.4f, and your "
                + "tolerance is %.4f.",
            commandedChange,
            toleranceSi);
    return new ResponseVerdict(
        ResponseClass.INSUFFICIENT_EXCITATION,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        false,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        0,
        Double.NaN,
        false,
        "Not enough movement to judge",
        diagnosis,
        "Nothing changed. Increase the step size, or widen your tolerance if it is unrealistically "
            + "tight.");
  }

  /**
   * A copy with the saturation note appended to the diagnosis and the recommendation.
   *
   * <p>Saturation is almost always missing from hand tuning and it invalidates the entire reading: a
   * student watching a saturated response draws conclusions about gains that were not in the loop at
   * the time, because while the motor is pinned at its ceiling neither kP nor kD does anything at
   * all.
   *
   * @param ceilingVolts the voltage ceiling that was hit
   * @param durationSeconds how long it was held there
   * @return a copy carrying the note; this record is unchanged
   */
  public ResponseVerdict withSaturationNote(double ceilingVolts, double durationSeconds) {
    String extra =
        String.format(
            Locale.ROOT,
            " Also: the motor was commanded to its %.1f V ceiling for %.2f s during this move. "
                + "While it is saturated, kP and kD do nothing at all - the mechanism is just going "
                + "as fast as it can.",
            ceilingVolts,
            durationSeconds);
    String extraAction =
        " Either use a motion profile so the setpoint stays reachable, or make the step smaller.";
    return new ResponseVerdict(
        classification,
        riseTimeSec,
        overshootPct,
        settleTimeSec,
        settled,
        steadyStateErrorSi,
        steadyStatePct,
        residualVolts,
        dampingRatio,
        oscillationHz,
        oscillationCrossings,
        peakVolts,
        true,
        headline,
        diagnosis + extra,
        recommendation + extraAction);
  }

  /**
   * Whether this response is good enough to accept and stop iterating.
   *
   * @return true when the classification is {@link ResponseClass#GOOD}
   */
  public boolean isGood() {
    return classification == ResponseClass.GOOD;
  }

  /**
   * The metrics on one line, for the log and the markdown tuning report.
   *
   * @return e.g. {@code "GOOD: rise 0.31 s, overshoot 3.2%, settle 0.48 s, sse 0.0012, zeta 0.71"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s: rise %.3f s, overshoot %.1f%%, settle %.3f s, sse %.5f, zeta %.3f, osc %.2f Hz",
        classification,
        riseTimeSec,
        overshootPct,
        settleTimeSec,
        steadyStateErrorSi,
        dampingRatio,
        oscillationHz);
  }
}

package org.rootstock.tuning.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.tuning.sysid.SampleBuffer;

/**
 * Measures a recorded step response and says, in one sentence, which gain to move and which way.
 *
 * <p>Pure and HAL-free: a list of samples in, a {@link ResponseVerdict} out. That is what lets the
 * same code run live in the wizard, again at pit time as a "is this still tuned?" check, and a third
 * time in a unit test against a synthetic second-order response whose damping ratio is known
 * exactly.
 *
 * <h2>The metrics, and why each one is measured the way it is</h2>
 *
 * <ul>
 *   <li><b>Rise time</b> is 10% to 90% of the commanded change, interpolated between the bracketing
 *       samples rather than snapped to one. At 50 Hz a snapped rise time quantises to 20 ms steps,
 *       which on a fast mechanism is most of the measurement.
 *   <li><b>Damping ratio</b> comes from the logarithmic decrement of successive same-sign peaks,
 *       {@code zeta = delta / sqrt(4π² + delta²)}. This is the only metric here that detects
 *       <em>divergence</em> directly: if the second peak is larger than the first the decrement is
 *       negative, zeta is negative, and the response is unstable — which is a fact about the shape,
 *       not a threshold somebody chose.
 *   <li><b>Oscillation frequency</b> is zero crossings of the error after the peak, two per cycle.
 *       Reported only above three crossings, because two crossings and a bit of noise is not a
 *       frequency.
 *   <li><b>Residual voltage</b> is the mean feedback-only voltage over the last half second. It is
 *       the single most useful number in the whole analysis: a mechanism sitting on a steady error
 *       while its controller holds two volts is telling you exactly how many volts of feedforward
 *       are missing, and whether that is friction (kS) or gravity (kG).
 *   <li><b>Saturation</b> is tracked because a saturated response is uninformative about gains that
 *       were not in the loop at the time, and because hand tuning almost never notices it.
 * </ul>
 */
public final class StepResponseAnalyzer {

  /** Fewest samples that can be analysed: half a second at 50 Hz. */
  public static final int kMinimumSamples = 25;

  /** A step smaller than this many tolerances is too small to say anything about. */
  public static final double kMinimumExcitationTolerances = 4.0;

  /** The default settling band, as a fraction of the commanded change. */
  public static final double kDefaultSettleBand = 0.02;

  /** How long the response must stay inside the band at the end to count as settled, in seconds. */
  public static final double kSettleHoldSeconds = 0.25;

  /** The window used for steady-state error and residual voltage, in seconds. */
  public static final double kSteadyStateWindowSeconds = 0.5;

  /** Fraction of the ceiling above which a command counts as saturated. */
  public static final double kSaturationFraction = 0.98;

  /** How long saturation must persist before it is worth mentioning, in seconds. */
  public static final double kSaturationSeconds = 0.10;

  /** Oscillation at or above this frequency is electrical noise through kD, not mechanical. */
  public static final double kNoiseFrequencyHz = 8.0;

  private StepResponseAnalyzer() {}

  /**
   * Analyse a window from a mechanism with no gravity term.
   *
   * @param window the samples, oldest first
   * @param toleranceSi the mechanism's "at goal" tolerance, in SI
   * @param expectedRiseSec what a good rise time would be for this plant and this move
   * @param expectedSettleSec what a good settling time would be
   * @param voltageCeiling the supervisor's voltage ceiling, for the saturation check
   * @return the verdict; never null, and never classified "unknown"
   */
  public static ResponseVerdict analyze(
      List<SampleBuffer.Sample> window,
      double toleranceSi,
      double expectedRiseSec,
      double expectedSettleSec,
      double voltageCeiling) {
    return analyze(window, toleranceSi, expectedRiseSec, expectedSettleSec, voltageCeiling, false);
  }

  /**
   * Analyse a window, telling the coaching layer whether gravity loads this mechanism.
   *
   * <p>The gravity flag changes exactly one thing: which of the two steady-state stories is told. A
   * flywheel that settles short is fighting friction and needs kS; an arm that settles low, every
   * time, in the direction gravity pulls, is fighting gravity and needs kG. Both look identical in
   * the position trace and are told apart only by knowing what loads the mechanism.
   *
   * @param window the samples, oldest first
   * @param toleranceSi the mechanism's "at goal" tolerance, in SI
   * @param expectedRiseSec what a good rise time would be for this plant and this move
   * @param expectedSettleSec what a good settling time would be
   * @param voltageCeiling the supervisor's voltage ceiling, for the saturation check
   * @param gravityLoaded true for an elevator or an arm
   * @return the verdict
   */
  public static ResponseVerdict analyze(
      List<SampleBuffer.Sample> window,
      double toleranceSi,
      double expectedRiseSec,
      double expectedSettleSec,
      double voltageCeiling,
      boolean gravityLoaded) {

    double tolerance = Double.isFinite(toleranceSi) && toleranceSi > 0 ? toleranceSi : 0.0;
    if (window == null || window.size() < kMinimumSamples) {
      return ResponseVerdict.insufficient(0.0, tolerance);
    }

    int n = window.size();
    double y0 = window.get(0).measurement();
    double target = window.get(n - 1).setpoint();
    double commanded = target - y0;
    if (!Double.isFinite(commanded)
        || Math.abs(commanded) < kMinimumExcitationTolerances * tolerance
        || Math.abs(commanded) < 1e-9) {
      return ResponseVerdict.insufficient(commanded, tolerance);
    }

    double t0 = window.get(0).t();
    double tEnd = window.get(n - 1).t();

    // --- rise time, 10% to 90%, interpolated -----------------------------------------------------
    double t10 = crossingTime(window, y0, commanded, 0.10);
    double t90 = crossingTime(window, y0, commanded, 0.90);
    double riseTime =
        Double.isFinite(t10) && Double.isFinite(t90) ? Math.max(0.0, t90 - t10) : Double.NaN;

    // --- peak and overshoot ----------------------------------------------------------------------
    int peakIndex = 0;
    double ePeak = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < n; i++) {
      double e = (window.get(i).measurement() - y0) / commanded;
      if (e > ePeak) {
        ePeak = e;
        peakIndex = i;
      }
    }
    double overshootPct = 100.0 * (ePeak - 1.0);

    // --- settling --------------------------------------------------------------------------------
    double band = Math.max(kDefaultSettleBand, tolerance / Math.abs(commanded));
    double tLastOutside = Double.NaN;
    for (int i = 0; i < n; i++) {
      double e = (window.get(i).measurement() - y0) / commanded;
      if (Math.abs(e - 1.0) > band) {
        tLastOutside = window.get(i).t();
      }
    }
    double settleTime = Double.isFinite(tLastOutside) ? tLastOutside - t0 : Double.NaN;
    boolean settled =
        !Double.isFinite(tLastOutside) || tLastOutside < tEnd - kSettleHoldSeconds;
    if (!Double.isFinite(tLastOutside)) {
      settleTime = 0.0;
    }

    // --- steady state and the residual voltage that explains it ----------------------------------
    double errorSum = 0.0;
    double residualSum = 0.0;
    int steadyCount = 0;
    int residualCount = 0;
    boolean residualKnown = true;
    for (int i = 0; i < n; i++) {
      SampleBuffer.Sample s = window.get(i);
      if (s.t() < tEnd - kSteadyStateWindowSeconds) {
        continue;
      }
      errorSum += s.setpoint() - s.measurement();
      steadyCount++;
      if (Double.isNaN(s.feedbackVolts())) {
        residualKnown = false;
      } else {
        residualSum += s.feedbackVolts();
        residualCount++;
      }
    }
    double steadyStateError = steadyCount > 0 ? errorSum / steadyCount : Double.NaN;
    double steadyStatePct = 100.0 * steadyStateError / commanded;
    double residualVolts =
        residualKnown && residualCount > 0 ? residualSum / residualCount : Double.NaN;

    // --- oscillation: zero crossings of the error after the peak ---------------------------------
    int crossings = 0;
    for (int i = Math.max(1, peakIndex + 1); i < n; i++) {
      double previous = window.get(i - 1).measurement() - target;
      double current = window.get(i).measurement() - target;
      if (previous != 0.0 && current != 0.0 && Math.signum(previous) != Math.signum(current)) {
        crossings++;
      }
    }
    double oscillationSpan = tEnd - window.get(peakIndex).t();
    double oscillationHz =
        crossings >= 3 && oscillationSpan > 1e-6 ? crossings / (2.0 * oscillationSpan) : Double.NaN;

    // --- damping ratio by logarithmic decrement --------------------------------------------------
    double zeta = dampingRatio(window, y0, commanded, peakIndex, band);

    // --- saturation ------------------------------------------------------------------------------
    double ceiling = Math.abs(voltageCeiling);
    double peakVolts = 0.0;
    double saturatedSince = Double.NaN;
    double saturatedFor = 0.0;
    for (int i = 0; i < n; i++) {
      SampleBuffer.Sample s = window.get(i);
      double magnitude = Math.abs(s.commandedVolts());
      if (Double.isFinite(magnitude)) {
        peakVolts = Math.max(peakVolts, magnitude);
      }
      boolean pinned = ceiling > 0 && magnitude >= kSaturationFraction * ceiling;
      if (pinned) {
        if (Double.isNaN(saturatedSince)) {
          saturatedSince = s.t();
        }
        saturatedFor = Math.max(saturatedFor, s.t() - saturatedSince);
      } else {
        saturatedSince = Double.NaN;
      }
    }
    boolean saturated = saturatedFor >= kSaturationSeconds;

    // --- classification: ordered rules, first match wins ------------------------------------------
    double finalError = Math.abs((window.get(n - 1).measurement() - y0) / commanded - 1.0);
    ResponseClass classification =
        classify(
            zeta,
            ePeak,
            finalError,
            crossings,
            settled,
            overshootPct,
            steadyStateError,
            commanded,
            tolerance,
            riseTime,
            expectedRiseSec,
            settleTime,
            expectedSettleSec);

    ResponseVerdict verdict =
        new ResponseVerdict(
            classification,
            riseTime,
            overshootPct,
            settleTime,
            settled,
            steadyStateError,
            steadyStatePct,
            residualVolts,
            zeta,
            oscillationHz,
            crossings,
            peakVolts,
            saturated,
            headline(classification),
            diagnosis(
                classification,
                riseTime,
                expectedRiseSec,
                overshootPct,
                settleTime,
                steadyStateError,
                tolerance,
                residualVolts,
                zeta,
                oscillationHz,
                crossings,
                commanded,
                gravityLoaded),
            recommendation(classification, oscillationHz, gravityLoaded, residualVolts));

    return saturated ? verdict.withSaturationNote(ceiling, saturatedFor) : verdict;
  }

  // ===============================================================================================
  // the ordered rules
  // ===============================================================================================

  private static ResponseClass classify(
      double zeta,
      double ePeak,
      double finalError,
      int crossings,
      boolean settled,
      double overshootPct,
      double steadyStateError,
      double commanded,
      double tolerance,
      double riseTime,
      double expectedRise,
      double settleTime,
      double expectedSettle) {

    // 1 - instability, before anything else. A response that is growing must never be reported as
    // "a bit of overshoot", because the next iteration of a refinement loop would make it worse.
    if (zeta < 0.0 || ePeak > 3.0 || (settled && finalError > 0.5)) {
      return ResponseClass.UNSTABLE;
    }

    // 2 - swinging and not settling.
    if (crossings >= 4 && (Double.isNaN(zeta) || zeta < 0.15) && !settled) {
      return ResponseClass.OSCILLATING;
    }

    // 3 - overshoots and rings, but does come to rest.
    if (overshootPct > 15.0 && zeta >= 0.15 && zeta < 0.5) {
      return ResponseClass.OVERSHOOT_RING;
    }

    // 4 - arrives, stops short, and stays there.
    if (settled
        && Math.abs(steadyStateError) > Math.max(tolerance, 0.03 * Math.abs(commanded))
        && overshootPct < 8.0) {
      return ResponseClass.STEADY_STATE_ERROR;
    }

    // 5 - heading the right way, lazily. A NaN rise time means it never reached 90% at all.
    if (Double.isNaN(riseTime)
        || (Double.isFinite(expectedRise)
            && expectedRise > 0
            && riseTime > 2.5 * expectedRise
            && overshootPct < 3.0)) {
      return ResponseClass.SLUGGISH;
    }

    // 6 - good.
    if (overshootPct <= 8.0
        && Math.abs(steadyStateError) <= Math.max(tolerance, 0.02 * Math.abs(commanded))
        && settled
        && (!Double.isFinite(expectedSettle)
            || expectedSettle <= 0
            || settleTime <= 1.75 * expectedSettle)) {
      return ResponseClass.GOOD;
    }

    // 7 - never say "unknown" to a student.
    return overshootPct > 8.0 ? ResponseClass.OVERSHOOT_RING : ResponseClass.SLUGGISH;
  }

  // ===============================================================================================
  // metric helpers
  // ===============================================================================================

  /** The interpolated time at which the normalised response first reaches a fraction of the step. */
  private static double crossingTime(
      List<SampleBuffer.Sample> window, double y0, double commanded, double fraction) {
    for (int i = 1; i < window.size(); i++) {
      double previous = (window.get(i - 1).measurement() - y0) / commanded;
      double current = (window.get(i).measurement() - y0) / commanded;
      if (current >= fraction) {
        if (current == previous) {
          return window.get(i).t();
        }
        double span = (fraction - previous) / (current - previous);
        double t = window.get(i - 1).t();
        return t + span * (window.get(i).t() - t);
      }
    }
    return Double.NaN;
  }

  /**
   * Zeta from the logarithmic decrement of the first two same-sign peaks after the main peak.
   *
   * <p>{@code delta = ln(A1/A2)} and {@code zeta = delta / sqrt(4π² + delta²)}. When the second peak
   * is <em>larger</em> than the first the decrement is negative and so is zeta, which is exactly the
   * divergence detector the classification's first rule needs — and it is a property of the shape,
   * not a threshold anybody picked.
   *
   * @return zeta, or NaN when fewer than two peaks exist to measure
   */
  private static double dampingRatio(
      List<SampleBuffer.Sample> window, double y0, double commanded, int peakIndex, double band) {
    List<Double> amplitudes = new ArrayList<>();
    for (int i = Math.max(1, peakIndex); i < window.size() - 1; i++) {
      double previous = Math.abs((window.get(i - 1).measurement() - y0) / commanded - 1.0);
      double current = Math.abs((window.get(i).measurement() - y0) / commanded - 1.0);
      double next = Math.abs((window.get(i + 1).measurement() - y0) / commanded - 1.0);
      if (current > previous && current >= next && current > band) {
        amplitudes.add(current);
        if (amplitudes.size() >= 2) {
          break;
        }
      }
    }
    if (amplitudes.size() < 2) {
      return Double.NaN;
    }
    double a1 = amplitudes.get(0);
    double a2 = amplitudes.get(1);
    if (!(a1 > 0) || !(a2 > 0)) {
      return Double.NaN;
    }
    double delta = Math.log(a1 / a2);
    return delta / Math.sqrt(4.0 * Math.PI * Math.PI + delta * delta);
  }

  // ===============================================================================================
  // the plain-language layer
  // ===============================================================================================

  private static String headline(ResponseClass classification) {
    return switch (classification) {
      case GOOD -> "On target, in time, no bounce";
      case SLUGGISH -> "Right direction, too slow";
      case OVERSHOOT_RING -> "Overshoots and rings";
      case OSCILLATING -> "Swinging and not settling";
      case STEADY_STATE_ERROR -> "Settles short of the target";
      case UNSTABLE -> "Growing, not settling - stopped";
      case INSUFFICIENT_EXCITATION -> "Not enough movement to judge";
    };
  }

  private static String diagnosis(
      ResponseClass classification,
      double riseTime,
      double expectedRise,
      double overshootPct,
      double settleTime,
      double steadyStateError,
      double tolerance,
      double residualVolts,
      double zeta,
      double oscillationHz,
      int crossings,
      double commanded,
      boolean gravityLoaded) {
    return switch (classification) {
      case GOOD ->
          String.format(
              Locale.ROOT,
              "Nice. It got there in %.3f s, overshot by %.1f%%, settled in %.3f s, and finished "
                  + "%.4f off target - inside your tolerance of %.4f.",
              riseTime,
              overshootPct,
              settleTime,
              steadyStateError,
              tolerance);
      case SLUGGISH ->
          String.format(
              Locale.ROOT,
              "It is heading the right way, just lazily. It took %.3f s to cover 10%% to 90%% of "
                  + "the move; for this mechanism that should be closer to %.3f s. There is no "
                  + "overshoot at all, which means kP is doing less than it could.",
              riseTime,
              expectedRise);
      case OVERSHOOT_RING ->
          String.format(
              Locale.ROOT,
              "It overshoots by %.1f%% and then rings %d times before settling. Damping ratio came "
                  + "out at %.3f - anything under about 0.7 will visibly bounce. That is a stiff "
                  + "spring with no shock absorber.",
              overshootPct,
              crossings,
              zeta);
      case OSCILLATING ->
          oscillationHz >= kNoiseFrequencyHz
              ? String.format(
                  Locale.ROOT,
                  "It is buzzing at %.1f Hz. That is far too fast to be the mechanism itself "
                      + "moving - it is kD amplifying noise in your encoder reading and feeding it "
                      + "back into the motor.",
                  oscillationHz)
              : String.format(
                  Locale.ROOT,
                  "It is swinging back and forth %.2f times a second and not settling. Damping "
                      + "ratio %.3f. This is a kP that is too high for this mechanism - the spring "
                      + "is so stiff it throws the mechanism past the target every time.",
                  oscillationHz,
                  zeta);
      case STEADY_STATE_ERROR ->
          gravityLoaded
              ? String.format(
                  Locale.ROOT,
                  "It settles %.4f short, every time, in the direction gravity pulls. The "
                      + "controller is holding %.3f V just to stop it sinking further. That is "
                      + "gravity your kG is not paying for.",
                  steadyStateError,
                  residualVolts)
              : String.format(
                  Locale.ROOT,
                  "It settles %.4f short of the target and just sits there. The controller is "
                      + "holding %.3f V trying to close that gap. That voltage is friction - and "
                      + "friction is exactly what kS is for.",
                  steadyStateError,
                  residualVolts);
      case UNSTABLE ->
          "Stopped. The oscillations were getting bigger, not smaller. That is how mechanisms "
              + "break.";
      case INSUFFICIENT_EXCITATION ->
          String.format(
              Locale.ROOT,
              "That move was too small to learn anything from - it only travelled %.4f, and your "
                  + "tolerance is %.4f.",
              commanded,
              tolerance);
    };
  }

  private static String recommendation(
      ResponseClass classification,
      double oscillationHz,
      boolean gravityLoaded,
      double residualVolts) {
    return switch (classification) {
      case GOOD -> "Nothing to change. Keep these gains.";
      case SLUGGISH ->
          "Turn kP up. Rootstock will multiply it by 1.6 and try again - accept, or set it "
              + "yourself.";
      case OVERSHOOT_RING ->
          "Add damping: cut kP by 30% or add kD. Rootstock will try kD first, because that keeps "
              + "the mechanism fast.";
      case OSCILLATING ->
          oscillationHz >= kNoiseFrequencyHz
              ? "Halve kD. If the buzz persists at kD = 0, your encoder is noisy or your velocity "
                  + "signal is being filtered somewhere you do not know about."
              : "Cut kP by 40%. If it still oscillates after two tries, check for backlash - no "
                  + "gain can fix slop.";
      case STEADY_STATE_ERROR ->
          gravityLoaded
              ? String.format(
                  Locale.ROOT,
                  "Raise kG by about %.3f V. If raising kG makes it settle high on the way down "
                      + "but still low on the way up, your arm's zero angle is wrong - run the "
                      + "gravity pre-pass again.",
                  Math.abs(residualVolts))
              : String.format(
                  Locale.ROOT,
                  "Raise kS by about %.3f V. Do NOT reach for kI: a constant offset means a "
                      + "feedforward term is missing, and adding an integrator hides the problem "
                      + "instead of fixing it.",
                  Math.abs(residualVolts));
      case UNSTABLE ->
          "kP has been cut to 40% of what it was and the routine is disarmed. Retry when you are "
              + "ready. If this happens twice, your kV or kA measurement is probably wrong - "
              + "re-run the identification steps.";
      case INSUFFICIENT_EXCITATION ->
          "Nothing changed. Increase the step size, or widen your tolerance if it is unrealistically "
              + "tight.";
    };
  }
}

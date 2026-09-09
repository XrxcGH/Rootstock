package org.rootstock.tuning.sysid;

/**
 * Thrown when a feedforward fit cannot be produced from the data collected.
 *
 * <p><b>Why a throw here, when the rest of the library collects.</b> Configuration problems are
 * values, because the robot must still boot so the student can read the message. This is different:
 * it happens in the middle of an interactive tuning step that has already stopped the mechanism, the
 * caller is one wizard step, and there is no meaningful "degraded" fit to hand back. Returning a
 * {@link FeedforwardFit} full of NaN would put four fake numbers on a dashboard, and a fake number
 * is worse than no number.
 *
 * <p>The message always names what was missing and what the student should do differently on the
 * retry — "run the dynamic step, not just the ramp" is an instruction; "rank deficient" is not.
 */
public final class IdentificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final FitFailure m_failure;

  /**
   * @param failure which of the two failures this is
   * @param message what was missing and what to do about it, in a sentence a student can act on
   */
  public IdentificationException(FitFailure failure, String message) {
    super(message);
    m_failure = failure;
  }

  /**
   * Which failure this is, for a caller that wants to branch rather than print.
   *
   * @return the failure
   */
  public FitFailure failure() {
    return m_failure;
  }
}

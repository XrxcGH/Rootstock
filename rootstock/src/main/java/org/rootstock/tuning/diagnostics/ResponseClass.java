package org.rootstock.tuning.diagnostics;

/**
 * What a step response looked like, in the seven shapes worth telling a student apart.
 *
 * <p><b>The order of the constants is the order the rules are evaluated in</b>, and that order is
 * load-bearing rather than cosmetic:
 *
 * <ul>
 *   <li>{@link #INSUFFICIENT_EXCITATION} first, because a move too small to see says nothing about
 *       any gain, and classifying it anyway produces confident advice from noise.
 *   <li>{@link #UNSTABLE} next, because a diverging response is the one that breaks hardware and it
 *       must never be reported as "a bit of overshoot".
 *   <li>Then the shapes, in descending severity. A mechanism that never arrives is never classified
 *       on its overshoot, because it does not have one.
 * </ul>
 *
 * <p>There is deliberately no {@code UNKNOWN}. A student who runs a step and is told "unknown" has
 * learned nothing and will run it again; the classifier always falls back to the nearest of these,
 * and says how confident it is in the sentence rather than in the enum.
 */
public enum ResponseClass {

  /** The move was too small to learn anything from. Nothing is changed. */
  INSUFFICIENT_EXCITATION,

  /** The oscillations were growing, not shrinking. The routine stops and cuts kP. */
  UNSTABLE,

  /** Swinging back and forth and not settling: kP too high, or kD amplifying encoder noise. */
  OSCILLATING,

  /** Gets there fast, goes past, and rings on the way back: not enough damping. */
  OVERSHOOT_RING,

  /** Settles, but short of the target and stays there: a missing feedforward term, not a missing kI. */
  STEADY_STATE_ERROR,

  /** Heading the right way, lazily. kP is doing less than it could. */
  SLUGGISH,

  /** On target, in time, without bouncing. Nothing to change. */
  GOOD;

  /**
   * Whether this classification means the routine must stop rather than iterate.
   *
   * @return true only for {@link #UNSTABLE}
   */
  public boolean isDangerous() {
    return this == UNSTABLE;
  }

  /**
   * Whether a refinement loop should keep going after this classification.
   *
   * @return false for {@link #GOOD}, {@link #UNSTABLE} and {@link #INSUFFICIENT_EXCITATION}
   */
  public boolean shouldRefine() {
    return this != GOOD && this != UNSTABLE && this != INSUFFICIENT_EXCITATION;
  }
}

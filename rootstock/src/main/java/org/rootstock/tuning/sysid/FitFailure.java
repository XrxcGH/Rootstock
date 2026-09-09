package org.rootstock.tuning.sysid;

/**
 * Why a feedforward fit could not be produced.
 *
 * <p><b>Why this is not {@code AbortReason}.</b> A fit that cannot be solved did not stop any
 * motion, and putting these two outcomes into the twelve-value motion-abort enum is what made an
 * earlier "all twelve abort conditions" gate unreconcilable — it counted fourteen things and called
 * them twelve. Two enums, two meanings: {@code AbortReason} answers "why did the mechanism stop",
 * this answers "why is there no number at the end".
 */
public enum FitFailure {

  /**
   * Not enough samples to fit anything. Usually the mechanism never actually moved, or the sweep was
   * aborted a second after it started.
   */
  INSUFFICIENT_DATA,

  /**
   * A regressor column never varied, so the gain it multiplies is unidentifiable — most often kA
   * after a ramp-only sweep, because acceleration is only visible while speeding up.
   */
  RANK_DEFICIENT
}

package org.rootstock.superstructure;

/**
 * Where a planned transition duration came from, so an auto routine's timing budget is never a
 * silent guess.
 *
 * <p>{@code design/01} §8.8 is blunt about the cost of leaving this out: an {@code AutoStep} whose
 * budget is a made-up number attributes every overrun to the trajectory, and a team spends an
 * afternoon re-tuning a path that was never the problem. Naming the source turns "we ran two
 * seconds late" into either "the elevator is slower than we measured" or "the path is slower than
 * we thought", which are different fixes.
 */
public enum CostSource {

  /**
   * The number came from {@code characterizeTransitions()} — the robot actually drove this pair and
   * it was timed. Trust it, and attribute an overrun to the mechanism.
   */
  MEASURED,

  /**
   * The number is a conservative bound derived from the declared motion profile, not a measurement.
   * It is deliberately pessimistic; attribute an overrun to the trajectory, not the mechanism, and
   * run the characterization routine if the budget matters.
   */
  PROFILE_BOUND
}

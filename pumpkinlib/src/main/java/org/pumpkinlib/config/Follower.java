package org.pumpkinlib.config;

/**
 * Which way a follower motor turns relative to its leader.
 *
 * <p><b>This enum exists because {@code boolean opposeMasterDirection} is the single most commonly
 * mis-typed argument in FRC motor configuration.</b> A boolean at a call site reads as
 * {@code .follower(spec, true)} — true meaning <em>what</em>? On a two-motor elevator gearbox the
 * wrong answer does not produce an error: the two motors fight, the current draw doubles, the
 * mechanism barely moves, and the first symptom is a breaker or a stripped gear. Naming the two
 * states makes the call site say what the gearbox does.
 *
 * <p>The sense is relative to the <em>leader as configured</em>, i.e. it composes with the leader's
 * own {@code inverted} flag rather than replacing it. Flipping the leader's inversion flips both
 * motors and keeps the pair working together, which is what you want when the mechanism turns out
 * to run backwards.
 */
public enum Follower {

  /**
   * The follower turns the same way as the leader.
   *
   * <p>Correct when the two motors drive the same gear from the same side — most two-motor
   * gearboxes on a single plate.
   */
  ALIGNED,

  /**
   * The follower turns opposite to the leader.
   *
   * <p>Correct when the motors are mounted facing each other across a gearbox, so that opposite
   * rotor directions produce the <em>same</em> output-shaft direction. This is the common case on a
   * mirrored elevator gearbox, which is why the flagship elevator config uses it.
   */
  OPPOSED;

  /**
   * Whether this follower runs opposite to the leader.
   *
   * <p>This is the value a vendor's {@code opposeMasterDirection} / {@code follow(leader, invert)}
   * argument wants, computed in exactly one place.
   *
   * @return true for {@link #OPPOSED}
   */
  public boolean opposesLeader() {
    return this == OPPOSED;
  }

  /**
   * A phrase for {@code describe()} that says what the gearbox is doing, not what the flag is set
   * to.
   *
   * @return for example {@code "opposed to the leader (motors face each other)"}
   */
  public String describe() {
    return this == OPPOSED
        ? "opposed to the leader (motors face each other across the gearbox)"
        : "aligned with the leader (motors turn the same way)";
  }
}

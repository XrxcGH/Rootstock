package org.rootstock.superstructure;

import java.util.Map;
import java.util.function.BooleanSupplier;
import org.rootstock.mechanism.Mechanism;

/**
 * A team's state enum implements this. The enum is the team's; the machinery is ours.
 *
 * <p>The survey behind {@code design/01} §8.1 found three good ideas in three different code bases
 * and one failure mode shared by all of them. The good ideas are 4738's orthogonal sub-states (an
 * arm state plus a climb state plus a claw state, composed — which is what keeps fifty states
 * tractable), 9143-A's deferred planning from live measured state, and {@code 0000-XXXX}'s on-entry
 * latch. The shared failure mode is that every {@code case} has to remember to reset every actuator
 * it does not use, and forgetting is invisible until a roller is still spinning in the next state.
 *
 * <p>{@link #goals()} is what fixes that. <b>A state lists only what it asserts.</b> Every mechanism
 * the superstructure coordinates and this state does <i>not</i> name is driven to that mechanism's
 * declared default, every loop, unconditionally — the inversion in {@code design/01} §8.5. So
 * "forgot to stop the roller" stops being a thing a state can do.
 *
 * <h2>Implementing it</h2>
 *
 * <pre>{@code
 * public enum RobotState implements SuperState {
 *   IDLE(Map.of()),
 *   SCORE_L4(
 *       Map.of(
 *           RobotContainer.ELEVATOR, AxisGoal.of(RobotConfig.ELEVATOR_L4),
 *           RobotContainer.ARM,      AxisGoal.of(RobotConfig.ARM_SCORE),
 *           RobotContainer.ROLLER,   AxisGoal.percent(0.6)),
 *       // the roller may start as soon as the elevator is above 40 in, not when it ARRIVES
 *       Map.of(RobotContainer.ROLLER, () -> RobotContainer.ELEVATOR.measuredSi() >= 1.016));
 *
 *   private final Map<Mechanism, AxisGoal> m_goals;
 *   private final Map<Mechanism, BooleanSupplier> m_early;
 *   // ... constructors, then:
 *   @Override public Map<Mechanism, AxisGoal> goals() { return m_goals; }
 *   @Override public Map<Mechanism, BooleanSupplier> earlyRelease() { return m_early; }
 * }
 * }</pre>
 *
 * <p><b>Build the map once, in the enum's constructor, and return the same instance.</b> Both
 * methods are called every loop. Returning a freshly built {@code Map.of(...)} from the method body
 * allocates on the 50 Hz path for no benefit, and the superstructure has no way to tell the
 * difference.
 *
 * <p>The {@link Mechanism} keys are compared by identity, which is what you want: two mechanisms are
 * the same mechanism only when they are the same object.
 */
public interface SuperState {

  /**
   * The state's name, which is what the dashboard shows and what every alert and report line names
   * it by.
   *
   * <p>An enum gets this for free — {@code Enum.name()} already satisfies it — which is why the
   * interface declares it rather than inventing a second identity.
   *
   * @return the state's name; never null and never blank
   */
  String name();

  /**
   * The goals this state asserts.
   *
   * <p>Anything <b>not</b> listed falls back to the mechanism's declared default (see the inversion
   * in {@code design/01} §8.5) rather than being left at whatever the previous state set. That is
   * the entire point of the type: a state declares its intent, not the complete actuator vector.
   *
   * <p>Called every loop. Return a map built once; see the class javadoc.
   *
   * @return the mechanism-to-goal map; never null, and {@code Map.of()} is a perfectly good answer
   *     for a state that asserts nothing and therefore means "everything at its default"
   */
  Map<Mechanism, AxisGoal> goals();

  /**
   * Optional: let a downstream mechanism start early instead of waiting for full arrival.
   *
   * <p>4738's interrupt-supplier idea, generalized. A mechanism with an early-release predicate is
   * commanded as soon as the predicate is true, in parallel with the rest of the transition, instead
   * of when the transition completes. The waypoint gate still requires the <i>position</i> axes to
   * arrive — early release shortens the intake spin-up, it does not skip the elevator.
   *
   * <p>A predicate for a mechanism this state does not name in {@link #goals()} does nothing: there
   * is no goal to release early to.
   *
   * @return the mechanism-to-predicate map; empty by default
   */
  default Map<Mechanism, BooleanSupplier> earlyRelease() {
    return Map.of();
  }
}

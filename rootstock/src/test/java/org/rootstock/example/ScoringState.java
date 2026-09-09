package org.rootstock.example;

import java.util.Map;
import org.rootstock.config.Setpoint;
import org.rootstock.mechanism.Mechanism;
import org.rootstock.superstructure.AxisGoal;
import org.rootstock.superstructure.SuperState;

/**
 * {@code design/01-core-mechanisms.md} section 9.2 — the state machine, reproduced against the real
 * API.
 *
 * <h2>One departure from the snippet, and it is a naming one</h2>
 *
 * <p>Section 9.2 names this enum {@code SuperState} and has it {@code implements
 * org.rootstock.superstructure.SuperState} — the interface and the implementation share a simple
 * name, so every reference to the interface inside the file has to be fully qualified. That
 * compiles, and a team is welcome to do it, but it makes the file unreadable and it makes {@code
 * import} lines in every consumer ambiguous. This fixture calls the enum {@code ScoringState}.
 * Nothing else changes: the goal maps, the {@code score(...)} helper and the typed handles are the
 * snippet's.
 *
 * <p>The mechanism references come from {@link RobotContainer}, which is what makes the states
 * <i>typed</i>: a level that does not exist is a compile error rather than a mid-match no-op,
 * because {@code score(...)} takes a {@link Setpoint} and the only {@link Setpoint}s in scope are
 * the four the elevator declares.
 */
public enum ScoringState implements SuperState {

  /** Everything falls back to its declared default. */
  IDLE(Map.of()),

  /** Arm down, elevator stowed, roller pulling in. */
  INTAKE(
      Map.of(
          RobotContainer.elevator(), AxisGoal.of(RobotConfig.ELEVATOR_STOW),
          RobotContainer.arm(), AxisGoal.of(RobotConfig.ARM_INTAKE),
          RobotContainer.roller(), AxisGoal.percent(0.8))),

  /** Arm tucked with a light hold current on the roller. */
  HOLD(
      Map.of(
          RobotContainer.arm(), AxisGoal.of(RobotConfig.ARM_STOW),
          RobotContainer.roller(), AxisGoal.percent(0.05))),

  /** Elevator to the second level, arm out to score. */
  L2(score(RobotConfig.ELEVATOR_L2)),

  /** Elevator to the third level, arm out to score. */
  L3(score(RobotConfig.ELEVATOR_L3)),

  /** Elevator to the fourth level, arm out to score. */
  L4(score(RobotConfig.ELEVATOR_L4)),

  /** Roller reversed at full output. */
  EJECT(Map.of(RobotContainer.roller(), AxisGoal.percent(-1.0)));

  /** Typed, so a level that does not exist is a COMPILE error, not a mid-match no-op. */
  private static Map<Mechanism, AxisGoal> score(Setpoint level) {
    return Map.of(
        RobotContainer.elevator(),
        AxisGoal.of(level),
        RobotContainer.arm(),
        AxisGoal.of(RobotConfig.ARM_SCORE));
  }

  private final Map<Mechanism, AxisGoal> m_goals;

  ScoringState(Map<Mechanism, AxisGoal> goals) {
    m_goals = goals;
  }

  @Override
  public Map<Mechanism, AxisGoal> goals() {
    return m_goals;
  }
}

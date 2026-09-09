package org.rootstock.core.selftest;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.Command;
import java.util.List;

/**
 * One step of a {@link SelfTestRoutine}: a named action with a bounded runtime and a list of
 * expectations that must be met before it ends.
 *
 * <p><strong>The timeout is not optional and it is not decoration.</strong> It is the entire reason
 * this is safe to run in a pit with a student's hand near the mechanism: a step that never finishes
 * is stopped, marked FAIL, and the routine moves on. A self-test that can hang holding an arm
 * against a hard stop is a self-test that gets banned by the safety captain in week two.
 *
 * <p>Immutable. Built through {@link SelfTestRoutine#of(String)}'s DSL, never by hand.
 */
public final class SelfTestStep {

  private final String m_name;
  private final Command m_action;
  private final Time m_timeout;
  private final List<Expect> m_expectations;

  SelfTestStep(String name, Command action, Time timeout, List<Expect> expectations) {
    m_name = name;
    m_action = action;
    m_timeout = timeout;
    m_expectations = List.copyOf(expectations);
  }

  /**
   * The step name, used as the result row label.
   *
   * @return the name, e.g. {@code "extend"}
   */
  public String name() {
    return m_name;
  }

  /**
   * The command this step runs.
   *
   * <p>It is driven directly by the runner rather than scheduled, exactly as a WPILib command group
   * drives its members, so that the runner holds the subsystem requirements for the whole routine
   * and nothing can interrupt a step half way.
   *
   * @return the action
   */
  public Command action() {
    return m_action;
  }

  /**
   * How long the step may run before it is stopped and marked FAIL.
   *
   * @return the timeout
   */
  public Time timeout() {
    return m_timeout;
  }

  /**
   * The expectations evaluated while this step runs.
   *
   * @return an unmodifiable list, in declaration order
   */
  public List<Expect> expectations() {
    return m_expectations;
  }

  /**
   * A one-line description for the boot dump and {@code describe()} chains.
   *
   * @return the name, timeout and expectation count
   */
  public String describe() {
    return m_name
        + " (timeout "
        + m_timeout.toShortString()
        + ", "
        + m_expectations.size()
        + " expectation(s))";
  }
}

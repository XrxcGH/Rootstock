package org.pumpkinlib.core.util;

import edu.wpi.first.units.measure.Time;
import java.util.Objects;
import org.pumpkinlib.core.compat.Clock;

/**
 * A two-sided debouncer: independent rise and fall times, counted in robot loops.
 *
 * <p><b>Why not WPILib's {@code Debouncer}.</b> Two reasons, and both of them bite in this library
 * specifically. First, WPILib's is one-sided per instance — {@code kBoth} uses the <i>same</i> time
 * in each direction — and almost every signal PumpkinLib debounces is asymmetric: a fault should
 * latch fast and clear slow, a limit switch should confirm slowly and release instantly. Second, and
 * decisive, WPILib's {@code Debouncer} reads the wall clock, so it fires nondeterministically under
 * {@code SimHooks.stepTiming} and in hand-ticked JUnit tests, which is how every health monitor and
 * every alert in this library is asserted.
 *
 * <p><b>Cycles, not seconds.</b> The constructor takes durations because that is how a human thinks;
 * they are converted to whole loops via {@code Clock.cyclesFor(...)} the first time
 * {@link #calculate(boolean)} runs — deferred to first use, not to construction, because
 * {@code Clock.dt()} is not final until {@code PumpkinRobot}'s constructor has told it the real loop
 * period, and library types get constructed before that. The consequence a caller should know: a
 * 40 ms debounce is 2 loops at 50 Hz and 4 loops at 100 Hz, exactly, in real time, in replay, in sim
 * stepping and in JUnit.
 *
 * <p>Not thread-safe; every caller is on the robot loop.
 */
public final class Debouncer2 {

  private final Time m_riseTime;
  private final Time m_fallTime;

  private int m_riseCycles = -1;
  private int m_fallCycles = -1;

  private boolean m_state;
  private int m_agreeingCycles;

  /**
   * A debouncer with independent rise and fall times, starting in the false state.
   *
   * @param riseTime how long the input must be continuously true before the output becomes true
   * @param fallTime how long the input must be continuously false before the output becomes false
   */
  public Debouncer2(Time riseTime, Time fallTime) {
    this(riseTime, fallTime, false);
  }

  /**
   * A debouncer with independent rise and fall times and an explicit starting state.
   *
   * @param riseTime how long the input must be continuously true before the output becomes true
   * @param fallTime how long the input must be continuously false before the output becomes false
   * @param initialValue the output before any input has been seen
   */
  public Debouncer2(Time riseTime, Time fallTime, boolean initialValue) {
    m_riseTime = Objects.requireNonNull(riseTime, "Debouncer2: riseTime must not be null");
    m_fallTime = Objects.requireNonNull(fallTime, "Debouncer2: fallTime must not be null");
    m_state = initialValue;
  }

  /**
   * A debouncer with the same time in both directions.
   *
   * @param time the debounce time in both directions
   * @return the debouncer
   */
  public static Debouncer2 symmetric(Time time) {
    return new Debouncer2(time, time);
  }

  /**
   * A debouncer that latches true quickly and clears slowly — the shape a fault wants.
   *
   * @param riseTime how long the fault must persist before it is reported
   * @param fallTime how long it must stay clear before the report is withdrawn
   * @return the debouncer
   */
  public static Debouncer2 latching(Time riseTime, Time fallTime) {
    return new Debouncer2(riseTime, fallTime);
  }

  /**
   * Feeds the current input in and returns the debounced output. Call exactly once per loop.
   *
   * @param input the raw signal
   * @return the debounced signal
   */
  public boolean calculate(boolean input) {
    ensureCyclesResolved();
    if (input == m_state) {
      m_agreeingCycles = 0;
      return m_state;
    }
    m_agreeingCycles++;
    int required = input ? m_riseCycles : m_fallCycles;
    if (m_agreeingCycles >= required) {
      m_state = input;
      m_agreeingCycles = 0;
    }
    return m_state;
  }

  /**
   * The current debounced output, without feeding a new input.
   *
   * @return the debounced signal
   */
  public boolean get() {
    return m_state;
  }

  /**
   * Forces the output and discards any in-progress transition.
   *
   * @param value the state to force
   */
  public void reset(boolean value) {
    m_state = value;
    m_agreeingCycles = 0;
  }

  /**
   * How many consecutive loops of a true input are required to raise the output.
   *
   * <p>Printed by {@code describe()} so that "why did my 40 ms debounce take 60 ms" is answerable:
   * the answer is that 40 ms is 2 loops, and 2 loops is 40 ms only if the edge lands on a loop
   * boundary.
   *
   * @return the rise threshold in loops, at least 1
   */
  public int riseCycles() {
    ensureCyclesResolved();
    return m_riseCycles;
  }

  /**
   * How many consecutive loops of a false input are required to drop the output.
   *
   * @return the fall threshold in loops, at least 1
   */
  public int fallCycles() {
    ensureCyclesResolved();
    return m_fallCycles;
  }

  /**
   * A one-line human-readable summary, for {@code describe()} output.
   *
   * @return e.g. {@code "Debouncer2[rise=2 loops, fall=1 loop, state=false]"}
   */
  public String describe() {
    return "Debouncer2[rise="
        + riseCycles()
        + " loops, fall="
        + fallCycles()
        + " loops, state="
        + m_state
        + "]";
  }

  @Override
  public String toString() {
    return describe();
  }

  private void ensureCyclesResolved() {
    if (m_riseCycles < 0) {
      m_riseCycles = Clock.cyclesFor(m_riseTime);
      m_fallCycles = Clock.cyclesFor(m_fallTime);
    }
  }
}

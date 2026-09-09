package org.rootstock.core.util;

import edu.wpi.first.units.measure.Frequency;
import java.util.Objects;

/**
 * A {@link Runnable} wired to a {@link Rate} — "do this at 4 Hz" as one object instead of a gate, a
 * field and an {@code if}.
 *
 * <p>{@link #poll()} is called every loop and runs the action on the gate's loop. Every wall-clock
 * rate gate in the library is expressed this way, which is what makes rates exact under AdvantageKit
 * replay, under {@code SimHooks.stepTiming} and in hand-ticked JUnit tests.
 *
 * <p><b>The action must not throw.</b> A runner is called from inside a periodic loop, and a throw
 * from a 4 Hz housekeeping task should not take down a match. {@link #poll()} does not catch for you
 * — the callers that need a catch are {@code Mechanism.periodic()} and {@code RootstockLifecycle},
 * both of which already wrap in {@code try/catch(Throwable)} and raise an alert; adding a second,
 * silent catch here would hide a bug from both.
 */
public final class PeriodicRunner {

  private final String m_name;
  private final Rate m_rate;
  private final Runnable m_action;
  private long m_runCount;

  private PeriodicRunner(String name, Rate rate, Runnable action) {
    m_name = name;
    m_rate = rate;
    m_action = action;
  }

  /**
   * A runner at a given frequency.
   *
   * @param hz the desired rate; must be finite and strictly positive
   * @param name a stable identifier, used as the rate's phase key and in {@link #describe()}
   * @param action what to run
   * @return the runner
   */
  public static PeriodicRunner at(Frequency hz, String name, Runnable action) {
    return new PeriodicRunner(
        requireName(name, "PeriodicRunner.at"),
        Rate.of(hz, name),
        Objects.requireNonNull(action, "PeriodicRunner.at: action must not be null"));
  }

  /**
   * A runner at an explicit loop count.
   *
   * @param cycles the period in loops; must be at least 1
   * @param name a stable identifier, used as the rate's phase key and in {@link #describe()}
   * @param action what to run
   * @return the runner
   */
  public static PeriodicRunner everyCycles(int cycles, String name, Runnable action) {
    return new PeriodicRunner(
        requireName(name, "PeriodicRunner.everyCycles"),
        Rate.everyCycles(cycles, name),
        Objects.requireNonNull(action, "PeriodicRunner.everyCycles: action must not be null"));
  }

  /**
   * A runner that fires on every loop.
   *
   * <p>Worth having rather than just calling the action directly: it keeps the run count, the name
   * and the {@link #describe()} line, so a task that must run every loop says so explicitly and is
   * visible in the boot dump alongside the rate-gated ones.
   *
   * @param name a stable identifier
   * @param action what to run
   * @return the runner
   */
  public static PeriodicRunner everyLoop(String name, Runnable action) {
    return everyCycles(1, name, action);
  }

  /**
   * Runs the action if this loop is its loop. Call every loop.
   *
   * @return true if the action ran
   */
  public boolean poll() {
    if (!m_rate.poll()) {
      return false;
    }
    m_action.run();
    m_runCount++;
    return true;
  }

  /**
   * The runner's name.
   *
   * @return the name
   */
  public String name() {
    return m_name;
  }

  /**
   * The gate this runner fires on.
   *
   * @return the rate
   */
  public Rate rate() {
    return m_rate;
  }

  /**
   * How many times the action has run since construction.
   *
   * <p>A run count of zero halfway through a match is the fastest way to see that a task was wired up
   * and never polled.
   *
   * @return the run count
   */
  public long runCount() {
    return m_runCount;
  }

  /**
   * A one-line human-readable summary, for the boot dump.
   *
   * @return e.g. {@code "PeriodicRunner[LogBudget, every 13 loops (3.8 Hz), ran 421x]"}
   */
  public String describe() {
    return "PeriodicRunner["
        + m_name
        + ", every "
        + m_rate.cycles()
        + " loop"
        + (m_rate.cycles() == 1 ? "" : "s")
        + ", ran "
        + m_runCount
        + "x]";
  }

  @Override
  public String toString() {
    return describe();
  }

  private static String requireName(String name, String method) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          method
              + ": name was "
              + (name == null ? "null" : "blank")
              + "; it must be a stable non-blank identifier — it is both the rate's phase key and "
              + "how this task appears in the boot dump. Fix: pass something like \"LogBudget\".");
    }
    return name;
  }
}

package org.rootstock.sim;

import edu.wpi.first.units.measure.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.core.compat.Clock;

/**
 * Deterministic time, stepped by hand, faster than real time, with no HAL.
 *
 * <h2>Why this is not a second clock</h2>
 *
 * <p>It installs itself into {@link Clock#setSource(java.util.function.DoubleSupplier)} — the one
 * deliberate hole in an otherwise read-only facade — rather than becoming a time authority of its own.
 * That is the difference between a test harness and a bug: ArchUnit rule 3 forbids
 * {@code Timer.getFPGATimestamp()} and {@code new Timer()} anywhere in the library precisely so that
 * every duration Rootstock measures reads one clock, and a headless clock that did not go through
 * {@code Clock} would give the library two answers to "what time is it" during exactly the runs — unit
 * tests and {@code simulateJava} — where a disagreement is hardest to see.
 *
 * <p>Consequently everything downstream follows for free: every debounce built on
 * {@link Clock#cyclesFor(Time)}, every rate gate built on {@link Clock#everyNCycles(int, String)},
 * every settle time and every deadline in the library steps when this steps, and none of them needs to
 * know this class exists.
 *
 * <h2>Time and cycles advance separately, on purpose</h2>
 *
 * <p>{@link Clock} keeps a wall-ish timestamp and a loop counter as two independent quantities, because
 * they answer different questions: time is for <i>measuring</i> and cycles are for <i>scheduling</i>.
 * {@link #step()} advances both, which is what a robot loop does. {@link #advanceSeconds(double)}
 * advances only time, which is how a test simulates a loop overrun — the thing that breaks a
 * seconds-based gate and does not break a cycle-based one.
 *
 * <h2>Ordering</h2>
 *
 * <p>{@link #step()} runs, in this order: every {@link #driving(Runnable...)} runnable, then
 * {@link Clock#tick()}, then the timestamp advance. Time moves <b>last</b> so that everything running
 * inside a cycle sees a single, stable timestamp for that cycle — which is what a real 50 Hz loop with
 * a cached FPGA read gives you, and what makes two log entries from the same cycle share a timestamp.
 * It is the same order {@code RootstockTest.stepSeconds} documents.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (HeadlessClock clock = HeadlessClock.install()) {
 *   clock.driving(elevator::periodic, RootstockSim::tick);
 *   clock.stepSeconds(2.0);          // 100 loops, instantly
 *   assertEquals(1.0, elevator.heightMeters(), 0.02);
 * }                                   // the FPGA clock is restored here
 * }</pre>
 */
public final class HeadlessClock implements AutoCloseable {

  /** The timestamp a freshly installed clock starts at, in seconds. */
  public static final double kDefaultStartSeconds = 0.0;

  private final List<Runnable> m_perStep = new ArrayList<>();
  private final double m_startSeconds;
  private final double m_periodSeconds;

  private double m_seconds;
  private boolean m_installed;

  private HeadlessClock(double startSeconds, double periodSeconds) {
    m_startSeconds = startSeconds;
    m_periodSeconds = periodSeconds;
    m_seconds = startSeconds;
  }

  /**
   * Installs a headless clock at time zero with the current loop period.
   *
   * <p>The current period rather than a hard-coded 0.02 because a test that has already said "pretend
   * this robot runs at 100 Hz" through {@link Clock#setPeriodSeconds(double)} must not have that
   * silently undone by installing a clock.
   *
   * @return the installed clock
   */
  public static HeadlessClock install() {
    return installAt(kDefaultStartSeconds, Clock.dt());
  }

  /**
   * Installs a headless clock at time zero with an explicit loop period.
   *
   * @param periodSeconds the loop period; must be finite and strictly positive
   * @return the installed clock
   */
  public static HeadlessClock install(double periodSeconds) {
    return installAt(kDefaultStartSeconds, periodSeconds);
  }

  /**
   * Installs a headless clock at an explicit start time and loop period.
   *
   * <p>A non-zero start is occasionally the point: several WPILib and vendor APIs treat a timestamp of
   * exactly 0.0 as "never", so a test that wants to prove a staleness check works starts somewhere
   * else.
   *
   * @param startSeconds the initial timestamp
   * @param periodSeconds the loop period; must be finite and strictly positive
   * @return the installed clock
   */
  public static HeadlessClock installAt(double startSeconds, double periodSeconds) {
    if (!Double.isFinite(startSeconds)) {
      throw new IllegalArgumentException(
          "HeadlessClock.installAt: startSeconds was "
              + startSeconds
              + "; it must be finite. Fix: pass 0.0 unless a test needs a non-zero epoch.");
    }
    HeadlessClock clock = new HeadlessClock(startSeconds, periodSeconds);
    Clock.setPeriodSeconds(periodSeconds);
    Clock.setSource(clock::seconds);
    clock.m_installed = true;
    return clock;
  }

  /**
   * Registers work to run once per {@link #step()}, before time advances.
   *
   * <p>This is what turns a clock into a loop. Nothing in WPILib calls {@code periodic()},
   * {@code simulationPeriodic()} or {@code CommandScheduler.run()} for you in a headless run, and its
   * own official unit-test example never advances a control loop at all — so the runnables registered
   * here are the loop body, and {@link RootstockSim#tick()} is usually one of them.
   *
   * @param perStep the work, run in registration order
   * @return this
   */
  public HeadlessClock driving(Runnable... perStep) {
    for (Runnable runnable : perStep) {
      m_perStep.add(Objects.requireNonNull(runnable, "HeadlessClock.driving: a runnable was null"));
    }
    return this;
  }

  /**
   * Runs one loop: the registered work, the cycle counter, then the timestamp.
   *
   * @return this
   */
  public HeadlessClock step() {
    for (Runnable runnable : m_perStep) {
      runnable.run();
    }
    Clock.tick();
    m_seconds += m_periodSeconds;
    return this;
  }

  /**
   * Runs {@code cycles} loops.
   *
   * @param cycles how many; values below one do nothing
   * @return this
   */
  public HeadlessClock step(int cycles) {
    for (int i = 0; i < cycles; i++) {
      step();
    }
    return this;
  }

  /**
   * Runs whole loops until at least {@code seconds} of simulated time have passed.
   *
   * <p>Rounded <b>up</b> to a whole number of loops, the same way {@link Clock#cyclesFor(Time)} rounds,
   * because half a robot loop is not a thing and a test that asked for 2.0 s at 50 Hz should get 100
   * loops rather than 99.
   *
   * @param seconds how long
   * @return this
   */
  public HeadlessClock stepSeconds(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0.0) {
      return this;
    }
    return step((int) Math.ceil(seconds / m_periodSeconds));
  }

  /**
   * Runs loops until {@code condition} is true or {@code timeoutSeconds} of simulated time have
   * passed.
   *
   * <p>The condition is checked <i>after</i> each loop, so a mechanism that reaches its goal on the
   * loop it is commanded reports true immediately rather than one loop late.
   *
   * @param condition the thing being waited for
   * @param timeoutSeconds how long to wait
   * @return true if the condition became true before the timeout
   */
  public boolean runUntil(java.util.function.BooleanSupplier condition, double timeoutSeconds) {
    Objects.requireNonNull(condition, "HeadlessClock.runUntil: condition must not be null");
    int limit = (int) Math.ceil(Math.max(0.0, timeoutSeconds) / m_periodSeconds);
    for (int i = 0; i < limit; i++) {
      step();
      if (condition.getAsBoolean()) {
        return true;
      }
    }
    return condition.getAsBoolean();
  }

  /**
   * Advances the timestamp without running a loop or bumping the cycle counter.
   *
   * <p>How a test simulates a loop overrun, a blocking config apply, or a watchdog trip: time moved,
   * the loop did not. A seconds-based gate notices and a cycle-based one does not, which is exactly the
   * distinction {@link Clock} draws and exactly the one that is impossible to test with a wall clock.
   *
   * @param seconds how far to jump; negative values are ignored, because the {@link Clock} contract is
   *     monotonic and a test that needs time to go backwards has found a different bug
   * @return this
   */
  public HeadlessClock advanceSeconds(double seconds) {
    if (Double.isFinite(seconds) && seconds > 0.0) {
      m_seconds += seconds;
    }
    return this;
  }

  /**
   * The current simulated timestamp. This is the method {@link Clock} calls.
   *
   * @return seconds since the clock was installed, plus its start offset
   */
  public double seconds() {
    return m_seconds;
  }

  /**
   * How many loops have been run since installation.
   *
   * @return the loop count
   */
  public long cycles() {
    return Math.round((m_seconds - m_startSeconds) / m_periodSeconds);
  }

  /**
   * The loop period this clock advances by.
   *
   * @return seconds
   */
  public double periodSeconds() {
    return m_periodSeconds;
  }

  /**
   * Whether this clock is still installed in {@link Clock}.
   *
   * @return true until {@link #close()}
   */
  public boolean isInstalled() {
    return m_installed;
  }

  /**
   * Restores the FPGA-backed clock and forgets the registered work.
   *
   * <p>Idempotent, and safe to call on a clock that was never the installed one — it restores the
   * default source either way, which is the behaviour a {@code try}-with-resources in a failing test
   * needs.
   */
  @Override
  public void close() {
    m_perStep.clear();
    m_installed = false;
    Clock.useFpgaSource();
  }

  /**
   * A one-line description, for a test failure message.
   *
   * @return e.g. {@code "HeadlessClock: t=2.000 s, 100 cycles at 0.020 s, installed"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "HeadlessClock: t=%.3f s, %d cycles at %.3f s, %s, driving %d runnable(s)",
        m_seconds,
        cycles(),
        m_periodSeconds,
        m_installed ? "installed" : "closed",
        m_perStep.size());
  }
}

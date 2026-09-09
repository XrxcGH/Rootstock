package org.rootstock.core.util;

import edu.wpi.first.units.measure.Time;
import org.rootstock.core.compat.Clock;

/**
 * Replay-safe stopwatch.
 *
 * <p>Drop-in shape-compatible with {@code edu.wpi.first.wpilibj.Timer} for the methods Rootstock
 * uses, but every read is {@link Clock#seconds()}. <b>Rootstock code never constructs a
 * {@code wpilibj.Timer}</b> — ArchUnit rule 3 bans the constructor as well as
 * {@code getFPGATimestamp()}.
 *
 * <p><b>Why a {@code Timer} instance is not an exemption.</b> {@code Timer} reads the FPGA clock
 * internally, so under AdvantageKit replay it advances in wall-clock time while the log advances in
 * log time — and every {@code atTime}, {@code settleTime} and {@code deadline} built on it
 * desynchronizes. That is not a style objection: it is the difference between a trajectory that
 * replays where it ran and one that does not.
 *
 * <p>Not thread-safe; every caller is on the robot loop.
 */
public final class RootstockStopwatch {

  private double m_accumulatedSeconds;
  private double m_startedAtSeconds;
  private boolean m_running;

  /** A stopped stopwatch reading zero. */
  public RootstockStopwatch() {}

  /**
   * A stopwatch that is already running.
   *
   * @return the started stopwatch
   */
  public static RootstockStopwatch started() {
    RootstockStopwatch sw = new RootstockStopwatch();
    sw.start();
    return sw;
  }

  /** Zeroes the elapsed time and starts running. */
  public void restart() {
    m_accumulatedSeconds = 0.0;
    m_startedAtSeconds = Clock.seconds();
    m_running = true;
  }

  /** Starts running, keeping any already-accumulated time. No effect if already running. */
  public void start() {
    if (!m_running) {
      m_startedAtSeconds = Clock.seconds();
      m_running = true;
    }
  }

  /** Stops running, keeping the accumulated time. No effect if already stopped. */
  public void stop() {
    if (m_running) {
      m_accumulatedSeconds += Clock.seconds() - m_startedAtSeconds;
      m_running = false;
    }
  }

  /** Zeroes the elapsed time. Does not change whether the stopwatch is running. */
  public void reset() {
    m_accumulatedSeconds = 0.0;
    m_startedAtSeconds = Clock.seconds();
  }

  /**
   * Seconds accumulated while running.
   *
   * @return the elapsed time in seconds
   */
  public double get() {
    return m_running
        ? m_accumulatedSeconds + (Clock.seconds() - m_startedAtSeconds)
        : m_accumulatedSeconds;
  }

  /**
   * The same number as {@link #get()}, as a {@link Time}, for signatures that take units.
   *
   * @return the elapsed time
   */
  public Time elapsed() {
    return edu.wpi.first.units.Units.Seconds.of(get());
  }

  /**
   * Whether the stopwatch is currently running.
   *
   * @return true if running
   */
  public boolean isRunning() {
    return m_running;
  }

  /**
   * Whether at least this much time has accumulated.
   *
   * @param seconds the threshold in seconds
   * @return true if {@link #get()} is at least {@code seconds}
   */
  public boolean hasElapsed(double seconds) {
    return get() >= seconds;
  }

  /**
   * Whether at least this much time has accumulated.
   *
   * @param duration the threshold
   * @return true if {@link #get()} is at least {@code duration}
   */
  public boolean hasElapsed(Time duration) {
    return hasElapsed(duration.in(edu.wpi.first.units.Units.Seconds));
  }

  @Override
  public String toString() {
    return String.format("RootstockStopwatch[%.3f s, %s]", get(), m_running ? "running" : "stopped");
  }
}

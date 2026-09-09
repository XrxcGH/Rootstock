package org.rootstock.tuning.sysid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A fixed-capacity ring of loop samples: allocated once, never grown, never garbage.
 *
 * <p><b>Why a ring and not a list.</b> This is filled from the 20 ms robot loop while a mechanism is
 * moving under raw voltage. A growing collection there means an allocation on the hot path and, once
 * it is big enough, a garbage-collection pause in the middle of a sweep — which shows up in the data
 * as a gap the fit cannot explain and the student reads as "the mechanism did something weird". A
 * ring of 3000 samples is 60 s at 50 Hz, which covers any single step or sweep, and it costs the
 * same whether it is full or empty.
 *
 * <p>Time comes from {@code Clock.seconds()}, never from a {@code Timer}, so a buffer recorded in
 * simulation, in replay and on hardware carries the same timestamps for the same sequence of loops.
 *
 * <p>Deliberately not thread-safe. It is written from the robot loop and read between steps.
 */
public final class SampleBuffer {

  /** Samples in the default buffer: 60 s at 50 Hz, which covers any single step or sweep. */
  public static final int kDefaultCapacity = 3000;

  /**
   * One loop of a supervised move.
   *
   * <p>The three voltage columns are the whole reason the diagnostics can say <i>"raise kS"</i>
   * rather than <i>"raise kP"</i>. {@code feedbackVolts} is {@link Double#NaN} for the whole window
   * whenever the mechanism cannot report the feedforward/feedback split — and every consumer treats
   * that NaN as "unknown", never as zero, so a plot visibly stops instead of drawing a flat line a
   * student would read as "feedback is doing nothing".
   *
   * @param t seconds, from the library clock
   * @param setpoint the commanded goal at this instant, in SI
   * @param measurement the measured position or velocity, in SI
   * @param velocity the measured velocity, in SI per second
   * @param commandedVolts what the supervisor actually applied
   * @param feedforwardVolts the feedforward part of that, or NaN when not separable
   * @param feedbackVolts the feedback part of that, or NaN when not separable
   */
  public record Sample(
      double t,
      double setpoint,
      double measurement,
      double velocity,
      double commandedVolts,
      double feedforwardVolts,
      double feedbackVolts) {}

  private final Sample[] m_samples;
  private int m_size;
  private int m_next;

  /**
   * Allocate the whole buffer up front.
   *
   * @param capacity how many samples to keep; must be positive
   * @throws IllegalArgumentException if the capacity is not positive, because a zero-capacity buffer
   *     silently discards every sample and produces an "insufficient data" fit nobody can explain
   */
  public SampleBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException(
          "SampleBuffer capacity was "
              + capacity
              + " and must be at least 1. Use SampleBuffer.kDefaultCapacity ("
              + kDefaultCapacity
              + "), which is 60 s at 50 Hz.");
    }
    m_samples = new Sample[capacity];
  }

  /**
   * A buffer of {@link #kDefaultCapacity} samples.
   *
   * @return the buffer
   */
  public static SampleBuffer standard() {
    return new SampleBuffer(kDefaultCapacity);
  }

  /**
   * Record one loop. Constant time, and the only allocation is the {@link Sample} record itself.
   *
   * @param t seconds, from the library clock
   * @param setpoint the commanded goal, in SI
   * @param measurement the measured position or velocity, in SI
   * @param velocity the measured velocity, in SI per second
   * @param commandedVolts what the supervisor applied
   * @param ffVolts the feedforward part, or NaN when not separable
   * @param fbVolts the feedback part, or NaN when not separable
   */
  public void add(
      double t,
      double setpoint,
      double measurement,
      double velocity,
      double commandedVolts,
      double ffVolts,
      double fbVolts) {
    m_samples[m_next] =
        new Sample(t, setpoint, measurement, velocity, commandedVolts, ffVolts, fbVolts);
    m_next = (m_next + 1) % m_samples.length;
    if (m_size < m_samples.length) {
      m_size++;
    }
  }

  /**
   * How many samples are currently held.
   *
   * @return between 0 and {@link #capacity()}
   */
  public int size() {
    return m_size;
  }

  /**
   * How many samples this buffer can hold.
   *
   * @return the capacity it was constructed with
   */
  public int capacity() {
    return m_samples.length;
  }

  /**
   * Whether anything has been recorded since the last {@link #clear()}.
   *
   * @return true when {@link #size()} is zero
   */
  public boolean isEmpty() {
    return m_size == 0;
  }

  /**
   * One sample, oldest first.
   *
   * @param i 0 is the oldest sample still held, {@code size() - 1} the newest
   * @return the sample
   * @throws IndexOutOfBoundsException if {@code i} is outside {@code [0, size())}
   */
  public Sample at(int i) {
    if (i < 0 || i >= m_size) {
      throw new IndexOutOfBoundsException(
          "SampleBuffer index " + i + " is outside [0, " + m_size + ").");
    }
    int start = m_size == m_samples.length ? m_next : 0;
    return m_samples[(start + i) % m_samples.length];
  }

  /**
   * The newest sample, if there is one.
   *
   * @return the most recently added sample, or null when the buffer is empty
   */
  public Sample latest() {
    return m_size == 0 ? null : at(m_size - 1);
  }

  /** Forget everything. The backing array is kept, so this allocates nothing. */
  public void clear() {
    m_size = 0;
    m_next = 0;
    java.util.Arrays.fill(m_samples, null);
  }

  /**
   * A copy of the samples between two timestamps, for analysis.
   *
   * <p>This is the one method here that allocates, and it is meant to be called between steps rather
   * than inside the loop — the analysis reads the window several times and doing that against a ring
   * would make every consumer deal with the wrap.
   *
   * @param t0 inclusive start time, in seconds
   * @param t1 inclusive end time, in seconds
   * @return an immutable list, oldest first; empty when the window holds nothing
   */
  public List<Sample> window(double t0, double t1) {
    if (m_size == 0 || !(t1 >= t0)) {
      return List.of();
    }
    List<Sample> out = new ArrayList<>();
    for (int i = 0; i < m_size; i++) {
      Sample s = at(i);
      if (s.t() >= t0 && s.t() <= t1) {
        out.add(s);
      }
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * A copy of everything currently held, oldest first.
   *
   * @return an immutable list
   */
  public List<Sample> all() {
    List<Sample> out = new ArrayList<>(m_size);
    for (int i = 0; i < m_size; i++) {
      out.add(at(i));
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * The most recent {@code seconds} of samples, measured back from the newest one.
   *
   * @param seconds how far back to look
   * @return an immutable list, oldest first
   */
  public List<Sample> lastSeconds(double seconds) {
    Sample last = latest();
    if (last == null) {
      return List.of();
    }
    return window(last.t() - Math.abs(seconds), last.t());
  }

  /**
   * A one-line summary for the boot dump and the UI.
   *
   * @return e.g. {@code "SampleBuffer 812/3000 samples, 16.24 s"}
   */
  public String describe() {
    if (m_size == 0) {
      return "SampleBuffer 0/" + m_samples.length + " samples, empty";
    }
    double span = Objects.requireNonNull(latest()).t() - at(0).t();
    return String.format(
        java.util.Locale.ROOT,
        "SampleBuffer %d/%d samples, %.2f s",
        m_size,
        m_samples.length,
        span);
  }
}

package org.pumpkinlib.pure.math;

import java.util.Arrays;

/**
 * Rolling statistics over a fixed-size window of the most recent samples.
 *
 * <p>This is the sag window, the loop-time window, the vision accept-rate window and the byte-budget
 * p95 window — one class instead of four hand-rolled circular buffers, each with its own off-by-one.
 *
 * <p><b>It is deliberately sample-counted, not time-windowed.</b> Every consumer in PumpkinLib feeds
 * it exactly once per robot loop, so "the last 250 samples" and "the last 5 seconds" are the same
 * statement at 50 Hz — and the sample form is the one that is identical under AdvantageKit replay,
 * under {@code SimHooks.stepTiming}, and in a hand-ticked JUnit test. A wall-clock window is none of
 * those. Callers that think in seconds convert with {@code Clock.cyclesFor(...)} at construction.
 *
 * <p>Not thread-safe; every caller is on the robot loop.
 *
 * <p><b>Tier 0.</b> Zero {@code edu.wpi.first} imports (ArchUnit rule 8).
 */
public final class Rolling {

  private final double[] m_window;
  private int m_nextIndex;
  private int m_count;
  private double m_last;

  /**
   * A rolling window.
   *
   * @param windowSamples how many of the most recent samples to keep; must be at least one
   * @throws IllegalArgumentException if {@code windowSamples} is less than one
   */
  public Rolling(int windowSamples) {
    if (windowSamples < 1) {
      throw new IllegalArgumentException(
          "Rolling: windowSamples was "
              + windowSamples
              + "; it must be at least 1. Fix: if you are thinking in seconds, convert with "
              + "Clock.cyclesFor(Seconds.of(...)) — this window counts robot loops, not time.");
    }
    m_window = new double[windowSamples];
  }

  /**
   * A rolling window, named for readability at the call site.
   *
   * @param windowSamples how many of the most recent samples to keep; must be at least one
   * @return the window
   */
  public static Rolling ofSamples(int windowSamples) {
    return new Rolling(windowSamples);
  }

  /**
   * Adds one sample, evicting the oldest once the window is full.
   *
   * @param value the sample
   * @return this window, so a caller can write {@code stats.add(v).mean()}
   */
  public Rolling add(double value) {
    m_window[m_nextIndex] = value;
    m_nextIndex = (m_nextIndex + 1) % m_window.length;
    if (m_count < m_window.length) {
      m_count++;
    }
    m_last = value;
    return this;
  }

  /**
   * The most recently added sample.
   *
   * @return the last sample, or zero if none has been added
   */
  public double last() {
    return m_last;
  }

  /**
   * How many samples are currently held.
   *
   * @return the count, between zero and {@link #windowSamples()}
   */
  public int count() {
    return m_count;
  }

  /**
   * The configured window size.
   *
   * @return the window size in samples
   */
  public int windowSamples() {
    return m_window.length;
  }

  /**
   * Whether the window has seen at least {@link #windowSamples()} samples.
   *
   * <p>Statistics from a partially-filled window are computed over what is there, which is correct
   * but not yet representative — a monitor that alerts off a p95 should wait for this to be true.
   *
   * @return true once the window has wrapped at least once
   */
  public boolean isFull() {
    return m_count == m_window.length;
  }

  /**
   * Whether no samples have been added since construction or {@link #reset()}.
   *
   * @return true if empty
   */
  public boolean isEmpty() {
    return m_count == 0;
  }

  /**
   * The arithmetic mean of the held samples.
   *
   * @return the mean, or zero if empty
   */
  public double mean() {
    if (m_count == 0) {
      return 0.0;
    }
    double sum = 0.0;
    for (int i = 0; i < m_count; i++) {
      sum += m_window[i];
    }
    return sum / m_count;
  }

  /**
   * The smallest held sample.
   *
   * @return the minimum, or {@link Double#NaN} if empty
   */
  public double min() {
    if (m_count == 0) {
      return Double.NaN;
    }
    double result = Double.POSITIVE_INFINITY;
    for (int i = 0; i < m_count; i++) {
      result = Math.min(result, m_window[i]);
    }
    return result;
  }

  /**
   * The largest held sample.
   *
   * @return the maximum, or {@link Double#NaN} if empty
   */
  public double max() {
    if (m_count == 0) {
      return Double.NaN;
    }
    double result = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < m_count; i++) {
      result = Math.max(result, m_window[i]);
    }
    return result;
  }

  /**
   * The peak-to-peak spread of the held samples — {@code max - min}.
   *
   * <p>This is the sag number: the difference between resting battery voltage and the worst dip in
   * the window.
   *
   * @return the range, or zero if empty
   */
  public double range() {
    return m_count == 0 ? 0.0 : max() - min();
  }

  /**
   * The population standard deviation of the held samples.
   *
   * <p>Population rather than sample: the window <i>is</i> the population we are describing, and the
   * Bessel correction on a 250-sample window is 0.2 %, which is noise next to the thing being
   * measured.
   *
   * @return the standard deviation, or zero if fewer than two samples are held
   */
  public double stdDev() {
    if (m_count < 2) {
      return 0.0;
    }
    double mean = mean();
    double sumSquares = 0.0;
    for (int i = 0; i < m_count; i++) {
      double d = m_window[i] - mean;
      sumSquares += d * d;
    }
    return Math.sqrt(sumSquares / m_count);
  }

  /**
   * A percentile of the held samples, by nearest-rank on a sorted copy.
   *
   * <p>Nearest-rank rather than interpolated: the byte-budget governor and the loop-time monitor
   * both compare a percentile against a threshold, and a rank that is always an actual observed
   * sample is one a student can find in the log.
   *
   * @param percentile the percentile, in {@code [0, 100]}; 95.0 means p95
   * @return the percentile value, or {@link Double#NaN} if empty
   * @throws IllegalArgumentException if {@code percentile} is outside {@code [0, 100]}
   */
  public double percentile(double percentile) {
    if (!(percentile >= 0.0) || !(percentile <= 100.0)) {
      throw new IllegalArgumentException(
          "Rolling.percentile: percentile was "
              + percentile
              + "; it must be in [0, 100]. Fix: p95 is percentile(95.0), not percentile(0.95).");
    }
    if (m_count == 0) {
      return Double.NaN;
    }
    double[] sorted = Arrays.copyOf(m_window, m_count);
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(percentile / 100.0 * m_count) - 1;
    return sorted[PumpkinMath.clamp(rank, 0, m_count - 1)];
  }

  /** Discards every held sample. The window size is unchanged. */
  public void reset() {
    Arrays.fill(m_window, 0.0);
    m_nextIndex = 0;
    m_count = 0;
    m_last = 0.0;
  }

  /**
   * A one-line human-readable summary, for {@code describe()} output and alert text.
   *
   * @return e.g. {@code "n=250/250 mean=12.104 min=10.880 max=12.640 sd=0.312"}
   */
  public String describe() {
    if (m_count == 0) {
      return "n=0/" + m_window.length + " (no samples yet)";
    }
    return String.format(
        "n=%d/%d mean=%.3f min=%.3f max=%.3f sd=%.3f",
        m_count, m_window.length, mean(), min(), max(), stdDev());
  }

  @Override
  public String toString() {
    return "Rolling[" + describe() + "]";
  }
}

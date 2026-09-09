package org.rootstock.core.util;

/**
 * Rising- and falling-edge detection on a boolean signal.
 *
 * <p>The pattern this replaces is {@code if (limit && !m_lastLimit) { ... } m_lastLimit = limit;} —
 * three lines with a mutable field between them, written once per limit switch, and wrong the one
 * time somebody forgets the assignment or puts it before an early return. Homing, {@code hasReset}
 * re-arm, the FMS-attach latch and the snapshot trigger all need it.
 *
 * <p>Not thread-safe; every caller is on the robot loop.
 */
public final class EdgeDetector {

  private boolean m_value;
  private boolean m_previous;

  /** An edge detector whose first {@link #update(boolean)} establishes the baseline. */
  public EdgeDetector() {
    this(false);
  }

  /**
   * An edge detector with an explicit starting value.
   *
   * <p>Use this when the first observed value genuinely is an edge — a limit switch that is expected
   * to start released, say — so that the first loop reports it rather than swallowing it.
   *
   * @param initialValue the value to treat as already seen
   */
  public EdgeDetector(boolean initialValue) {
    m_value = initialValue;
    m_previous = initialValue;
  }

  /**
   * Feeds the current value in. Call exactly once per loop.
   *
   * @param value the current signal
   * @return {@code value}, so this can be used inline
   */
  public boolean update(boolean value) {
    m_previous = m_value;
    m_value = value;
    return value;
  }

  /**
   * Whether the last {@link #update(boolean)} saw a false-to-true transition.
   *
   * @return true on the rising edge only
   */
  public boolean rising() {
    return m_value && !m_previous;
  }

  /**
   * Whether the last {@link #update(boolean)} saw a true-to-false transition.
   *
   * @return true on the falling edge only
   */
  public boolean falling() {
    return !m_value && m_previous;
  }

  /**
   * Whether the last {@link #update(boolean)} saw any transition.
   *
   * @return true on either edge
   */
  public boolean changed() {
    return m_value != m_previous;
  }

  /**
   * The most recently fed value.
   *
   * @return the current signal
   */
  public boolean value() {
    return m_value;
  }

  /**
   * The value before the most recent {@link #update(boolean)}.
   *
   * @return the previous signal
   */
  public boolean previous() {
    return m_previous;
  }

  /**
   * Forces both the current and previous value, so the next update cannot report a spurious edge.
   *
   * @param value the value to seed with
   */
  public void reset(boolean value) {
    m_value = value;
    m_previous = value;
  }
}

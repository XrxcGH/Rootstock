package org.pumpkinlib.core.util;

import java.util.Objects;
import java.util.function.Supplier;
import org.pumpkinlib.core.compat.Clock;

/**
 * A value that is computed at most once per robot loop, no matter how many times it is read.
 *
 * <p>The problem this solves is real and specific: a swerve pose, a CAN status read, a
 * {@code describe()} string or a fused encoder position gets consumed by four different callers in
 * one loop, and each call is a CAN transaction or an allocation. Making every consumer remember to
 * cache is how you end up with four slightly different cached copies that disagree by one loop.
 *
 * <p><b>Loop-scoped, not time-scoped</b>, for the same reason everything else in this library is:
 * {@code Clock.cycle()} is exact under AdvantageKit replay, under {@code SimHooks.stepTiming} and in
 * a hand-ticked JUnit test, and a wall-clock TTL is none of those.
 *
 * <p>Not thread-safe; every caller is on the robot loop.
 *
 * @param <T> the cached value's type
 */
public final class Cached<T> implements Supplier<T> {

  private final Supplier<T> m_supplier;
  private final int m_validCycles;

  private T m_value;
  private long m_computedAtCycle = Long.MIN_VALUE;

  private Cached(Supplier<T> supplier, int validCycles) {
    m_supplier = supplier;
    m_validCycles = validCycles;
  }

  /**
   * A value recomputed at most once per loop.
   *
   * @param <T> the value's type
   * @param supplier computes the value; must not be null and must not return null
   * @return the cache
   * @throws IllegalArgumentException if {@code supplier} is null
   */
  public static <T> Cached<T> of(Supplier<T> supplier) {
    return ofCycles(1, supplier);
  }

  /**
   * A value recomputed at most once every {@code cycles} loops.
   *
   * <p>Use this for something genuinely expensive that nothing needs fresh — a directory listing, a
   * free-space check. For anything a control loop reads, use {@link #of(Supplier)}: a value that is
   * five loops stale inside a feedback path is a bug that looks like a tuning problem.
   *
   * @param <T> the value's type
   * @param cycles how many loops a computed value stays valid for; must be at least 1
   * @param supplier computes the value; must not be null
   * @return the cache
   * @throws IllegalArgumentException if {@code cycles} is less than 1 or {@code supplier} is null
   */
  public static <T> Cached<T> ofCycles(int cycles, Supplier<T> supplier) {
    if (cycles < 1) {
      throw new IllegalArgumentException(
          "Cached.ofCycles: cycles was "
              + cycles
              + "; it must be at least 1. Fix: use Cached.of(supplier) for the once-per-loop case, "
              + "or Clock.cyclesFor(Seconds.of(...)) if you are thinking in time.");
    }
    Objects.requireNonNull(supplier, "Cached: supplier must not be null");
    return new Cached<>(supplier, cycles);
  }

  /**
   * The cached value, recomputing it if this loop has not seen it yet.
   *
   * @return the value
   */
  @Override
  public T get() {
    long now = Clock.cycle();
    if (m_computedAtCycle == Long.MIN_VALUE || now - m_computedAtCycle >= m_validCycles) {
      m_value = m_supplier.get();
      m_computedAtCycle = now;
    }
    return m_value;
  }

  /**
   * Discards the cached value so the next {@link #get()} recomputes.
   *
   * <p>For the case where something outside the cache knows the underlying value just changed — a
   * config re-apply, a device reset.
   */
  public void invalidate() {
    m_computedAtCycle = Long.MIN_VALUE;
    m_value = null;
  }

  /**
   * Whether {@link #get()} would return without recomputing.
   *
   * @return true if the cached value is still valid this loop
   */
  public boolean isValid() {
    return m_computedAtCycle != Long.MIN_VALUE
        && Clock.cycle() - m_computedAtCycle < m_validCycles;
  }

  /**
   * How many loops a computed value stays valid for.
   *
   * @return the validity window in loops, at least 1
   */
  public int validCycles() {
    return m_validCycles;
  }
}

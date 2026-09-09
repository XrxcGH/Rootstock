package org.rootstock.core.util;

import edu.wpi.first.units.measure.Frequency;
import java.util.Objects;
import org.rootstock.core.compat.Clock;

/**
 * A rate gate: true on one loop out of every N, with a phase offset that stops every consumer landing
 * on the same loop.
 *
 * <p>This is the typed form of {@code Clock.everyNCycles(n, key)}, and it is <b>the</b> way a
 * periodic rate is expressed in Rootstock. A wall-clock gate — {@code if (now - m_last > 0.25)} —
 * is wrong in three environments this library is asserted in: it fires nondeterministically under
 * {@code SimHooks.stepTiming}, it either never fires or fires unpredictably in a hand-ticked JUnit
 * test, and it silently means something different on a 100 Hz robot. A cycle gate is exact in all
 * three, costs one modulo, and makes {@code RootstockReplayVerify} diffs meaningful instead of noisy.
 *
 * <p><b>The phase key is not decoration.</b> Ten 4 Hz consumers with no offset all fire on cycle 0
 * and rebuild the exact CPU spike the rate gate was added to remove. The offset is derived from the
 * key's hash, so it is stable across runs and identical under replay.
 *
 * <p>{@link #poll()} is a pure function of {@code Clock.cycle()}, so calling it twice in one loop
 * returns the same answer — it is safe to use as a plain condition rather than as something you must
 * call exactly once.
 */
public final class Rate {

  private final String m_phaseKey;
  private final Frequency m_hz;
  private int m_cycles;

  private Rate(String phaseKey, Frequency hz, int cycles) {
    m_phaseKey = phaseKey;
    m_hz = hz;
    m_cycles = cycles;
  }

  /**
   * A gate at a given frequency.
   *
   * <p>The loop count is derived from {@code Clock.dt()} the first time the gate is polled, not at
   * construction, because {@code Clock.dt()} is not final until {@code RootstockRobot}'s constructor has
   * told it the real loop period and library types get constructed before that.
   *
   * @param hz the desired rate; must be finite and strictly positive
   * @param phaseKey a stable identifier for this consumer, e.g. the mechanism name
   * @return the gate
   */
  public static Rate of(Frequency hz, String phaseKey) {
    Objects.requireNonNull(hz, "Rate.of: hz must not be null");
    return new Rate(requireKey(phaseKey, "Rate.of"), hz, -1);
  }

  /**
   * A gate at an explicit loop count.
   *
   * @param cycles the period in loops; must be at least 1
   * @param phaseKey a stable identifier for this consumer
   * @return the gate
   * @throws IllegalArgumentException if {@code cycles} is less than 1
   */
  public static Rate everyCycles(int cycles, String phaseKey) {
    if (cycles < 1) {
      throw new IllegalArgumentException(
          "Rate.everyCycles: cycles was "
              + cycles
              + "; it must be at least 1. Fix: use Rate.everyLoop(key) for the every-loop case.");
    }
    return new Rate(requireKey(phaseKey, "Rate.everyCycles"), null, cycles);
  }

  /**
   * A gate that is always open — for a consumer that is configurable but currently runs every loop.
   *
   * @param phaseKey a stable identifier for this consumer
   * @return the gate
   */
  public static Rate everyLoop(String phaseKey) {
    return everyCycles(1, phaseKey);
  }

  /**
   * Whether this loop is this consumer's loop.
   *
   * @return true on one loop out of every {@link #cycles()}
   */
  public boolean poll() {
    return Clock.everyNCycles(cycles(), m_phaseKey);
  }

  /**
   * The period in loops, resolved from the configured frequency on first use.
   *
   * @return the period in loops, at least 1
   */
  public int cycles() {
    if (m_cycles < 0) {
      m_cycles = Clock.periodCycles(m_hz);
    }
    return m_cycles;
  }

  /**
   * The phase key this gate was built with.
   *
   * @return the key
   */
  public String phaseKey() {
    return m_phaseKey;
  }

  /**
   * A one-line human-readable summary, for {@code describe()} output.
   *
   * @return e.g. {@code "Rate[Vision, every 13 loops (3.8 Hz)]"}
   */
  public String describe() {
    int n = cycles();
    return String.format("Rate[%s, every %d loop%s (%.1f Hz)]",
        m_phaseKey, n, n == 1 ? "" : "s", 1.0 / (n * Clock.dt()));
  }

  @Override
  public String toString() {
    return describe();
  }

  private static String requireKey(String phaseKey, String method) {
    if (phaseKey == null || phaseKey.isBlank()) {
      throw new IllegalArgumentException(
          method
              + ": phaseKey was "
              + (phaseKey == null ? "null" : "blank")
              + "; it must be a stable non-blank identifier. It is what spreads consumers across "
              + "loops instead of bunching them on cycle 0. Fix: pass the mechanism or subsystem "
              + "name.");
    }
    return phaseKey;
  }
}

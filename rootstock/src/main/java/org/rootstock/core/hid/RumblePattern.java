package org.rootstock.core.hid;

import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Time;
import java.util.Locale;

/**
 * A haptic waveform: intensity as a function of elapsed time, plus a finite duration.
 *
 * <p><strong>Why this exists.</strong> WPILib gives you exactly
 * {@code GenericHID.setRumble(RumbleType, double)} - no duration, no shape, no arbitration. Every
 * team writes the same ad-hoc {@code startEnd(...).withTimeout(...)}, and the moment two of them
 * exist they stomp each other. A pattern is the "what should it feel like" half of that problem;
 * {@link RumbleScheduler} is the "who wins" half.
 *
 * <p>Patterns are immutable value objects and can be shared freely. They hold no HID and no time
 * origin - the scheduler supplies elapsed time - so the same {@code doubleTap()} instance can be
 * playing on two controllers at once.
 *
 * <p>Intensity is always clamped to {@code [0, 1]}, and is {@code 0} for any elapsed time past
 * {@link #durationSeconds()}, so a pattern that outlives its command can never leave a controller
 * buzzing.
 */
public final class RumblePattern {

  private final String m_name;
  private final double m_durationSeconds;
  private final Shape m_shape;

  private RumblePattern(String name, double durationSeconds, Shape shape) {
    m_name = name;
    m_durationSeconds = durationSeconds;
    m_shape = shape;
  }

  /**
   * A constant-intensity buzz of a fixed length. The workhorse pattern.
   *
   * @param intensity motor intensity in {@code [0, 1]}.
   * @param duration  how long the buzz lasts; must be positive.
   * @return the pattern.
   * @throws IllegalArgumentException if intensity is outside {@code [0, 1]} or duration is not
   *     positive.
   * @throws NullPointerException if {@code duration} is {@code null}.
   */
  public static RumblePattern pulse(double intensity, Time duration) {
    double seconds = requirePositiveSeconds(duration, "pulse");
    double level = requireIntensity(intensity, "pulse");
    return new RumblePattern(
        String.format(Locale.ROOT, "pulse(%.2f, %.2fs)", level, seconds), seconds, t -> level);
  }

  /**
   * Two short taps - the "got it" signal. 0.08 s on, 0.07 s off, 0.08 s on, at 60% intensity.
   *
   * <p>Short and distinct on purpose: a driver has to be able to tell it from {@link #sos()} without
   * looking away from the field.
   *
   * @return the pattern, total length 0.23 s.
   */
  public static RumblePattern doubleTap() {
    return segments(
        "doubleTap", new double[] {0.08, 0.07, 0.08}, new double[] {0.60, 0.00, 0.60});
  }

  /**
   * A linear ramp between two intensities - useful for "you are getting closer".
   *
   * @param from     starting intensity in {@code [0, 1]}.
   * @param to       ending intensity in {@code [0, 1]}.
   * @param duration ramp length; must be positive.
   * @return the pattern.
   * @throws IllegalArgumentException if either intensity is outside {@code [0, 1]} or duration is
   *     not positive.
   * @throws NullPointerException if {@code duration} is {@code null}.
   */
  public static RumblePattern ramp(double from, double to, Time duration) {
    double seconds = requirePositiveSeconds(duration, "ramp");
    double start = requireIntensity(from, "ramp");
    double end = requireIntensity(to, "ramp");
    return new RumblePattern(
        String.format(Locale.ROOT, "ramp(%.2f -> %.2f, %.2fs)", start, end, seconds),
        seconds,
        t -> start + (end - start) * (t / seconds));
  }

  /**
   * Morse SOS - three short, three long, three short, at 80% intensity.
   *
   * <p>The "something is badly wrong, look at the dashboard" pattern. It is deliberately long (about
   * 2.1 s) and unmistakable; do not use it for routine feedback or drivers will learn to ignore it.
   *
   * @return the pattern.
   */
  public static RumblePattern sos() {
    final double dot = 0.09;
    final double dash = 0.27;
    final double gap = 0.07;
    final double letterGap = 0.18;
    final double on = 0.80;
    double[] durations = {
      dot, gap, dot, gap, dot, letterGap,
      dash, gap, dash, gap, dash, letterGap,
      dot, gap, dot, gap, dot
    };
    double[] levels = {
      on, 0, on, 0, on, 0,
      on, 0, on, 0, on, 0,
      on, 0, on, 0, on
    };
    return segments("sos", durations, levels);
  }

  /**
   * Intensity at a point in the pattern.
   *
   * @param elapsedSeconds seconds since the pattern started. Negative values and values past the
   *     duration both return {@code 0}, so a scheduler can call this without bounds-checking.
   * @return intensity in {@code [0, 1]}.
   */
  public double intensityAt(double elapsedSeconds) {
    if (elapsedSeconds < 0.0 || elapsedSeconds >= m_durationSeconds) {
      return 0.0;
    }
    double raw = m_shape.at(elapsedSeconds);
    return raw < 0.0 ? 0.0 : Math.min(raw, 1.0);
  }

  /**
   * Whether the pattern has run out.
   *
   * @param elapsedSeconds seconds since the pattern started.
   * @return {@code true} once elapsed time reaches the duration.
   */
  public boolean isFinished(double elapsedSeconds) {
    return elapsedSeconds >= m_durationSeconds;
  }

  /**
   * The total length of the pattern, as a unit-carrying measure.
   *
   * @return the duration.
   */
  public Time duration() {
    return Units.Seconds.of(m_durationSeconds);
  }

  /**
   * The total length of the pattern in seconds, for hot-path arithmetic.
   *
   * @return the duration in seconds.
   */
  public double durationSeconds() {
    return m_durationSeconds;
  }

  /**
   * The pattern's name, as it appears in command names and logs.
   *
   * @return e.g. {@code "doubleTap"} or {@code "pulse(0.50, 0.25s)"}.
   */
  public String name() {
    return m_name;
  }

  /**
   * Human-readable summary.
   *
   * @return e.g. {@code "RumblePattern[doubleTap, 0.230 s]"}.
   */
  public String describe() {
    return String.format(Locale.ROOT, "RumblePattern[%s, %.3f s]", m_name, m_durationSeconds);
  }

  @Override
  public String toString() {
    return describe();
  }

  /**
   * Builds a piecewise-constant pattern from alternating segment durations and levels. The design
   * names four factories and no builder, so this stays private: a team that needs a custom shape
   * composes {@code pulse} and {@code ramp} in a command sequence instead.
   */
  private static RumblePattern segments(String name, double[] durations, double[] levels) {
    double total = 0.0;
    for (double d : durations) {
      total += d;
    }
    final double[] edges = new double[durations.length];
    double running = 0.0;
    for (int i = 0; i < durations.length; i++) {
      running += durations[i];
      edges[i] = running;
    }
    final double[] values = levels.clone();
    return new RumblePattern(
        name,
        total,
        t -> {
          for (int i = 0; i < edges.length; i++) {
            if (t < edges[i]) {
              return values[i];
            }
          }
          return 0.0;
        });
  }

  private static double requireIntensity(double intensity, String factory) {
    if (!(intensity >= 0.0 && intensity <= 1.0)) {
      throw new IllegalArgumentException(
          "RumblePattern."
              + factory
              + " was given intensity "
              + intensity
              + ", which is outside the expected range [0, 1] (0 = off, 1 = full). "
              + "Fix: pass a fraction, not a percentage - 0.6, not 60.");
    }
    return intensity;
  }

  private static double requirePositiveSeconds(Time duration, String factory) {
    if (duration == null) {
      throw new NullPointerException(
          "RumblePattern."
              + factory
              + " was given a null duration. Fix: pass e.g. Seconds.of(0.25) or "
              + "Milliseconds.of(250).");
    }
    double seconds = duration.in(Units.Seconds);
    if (!(seconds > 0.0)) {
      throw new IllegalArgumentException(
          "RumblePattern."
              + factory
              + " was given duration "
              + seconds
              + " s, which must be greater than 0. A zero-length pattern is a rumble the driver "
              + "never feels. Fix: pass e.g. Seconds.of(0.25).");
    }
    return seconds;
  }

  /** The intensity shape. Private: patterns are built through the four named factories. */
  @FunctionalInterface
  private interface Shape {
    double at(double elapsedSeconds);
  }
}

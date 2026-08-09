package org.pumpkinlib.core.compat;

import static edu.wpi.first.units.Units.Hertz;
import static edu.wpi.first.units.Units.Microseconds;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Frequency;
import edu.wpi.first.units.measure.Time;
import java.util.function.DoubleSupplier;

/**
 * The single place PumpkinLib reads the robot clock or the loop period.
 *
 * <p><b>Never {@code Timer.getFPGATimestamp()}. Never {@code new Timer()}. Never
 * {@code System.nanoTime()}. Never a wall clock for RATE GATING</b> — see {@link
 * #periodCycles(Frequency)}: time is for measuring, cycles are for scheduling. ArchUnit rule 3
 * enforces the first two across every package in the library, and this class is the reason it can.
 *
 * <p><b>Why {@code Timer.getTimestamp()} and not {@code getFPGATimestamp()}.</b> Under AdvantageKit
 * replay {@code Timer.getTimestamp()} <i>is</i> the log timestamp — it reads the injected clock — so
 * every duration this library measures replays identically. {@code getFPGATimestamp()} reads the
 * hardware clock, which under replay advances in wall-clock time while the log advances in log time,
 * and every {@code atTime} / {@code settleTime} / {@code deadline} in the library desynchronizes.
 * That is one method name apart and it is the difference between a replay you can trust and one you
 * cannot.
 *
 * <p><b>Why cycles and not seconds for scheduling.</b> {@code SimHooks.stepTiming} and hand-ticked
 * JUnit tests are not AdvantageKit replays: a wall-clock gate in a unit test either never fires or
 * fires nondeterministically, and either way the test is worthless. And a 100 Hz robot, or any
 * non-20-ms period, breaks a hard-coded seconds gate silently, whereas
 * {@code periodCycles(Hertz.of(4))} re-derives from the actual period.
 *
 * <p><b>The test seam.</b> {@link #setSource(DoubleSupplier)} lets simulation, {@code HeadlessClock}
 * and unit tests drive time deterministically without a HAL. It is a deliberate hole in an otherwise
 * read-only facade, and {@link #useFpgaSource()} closes it again; nothing on the robot calls either.
 *
 * <p>This is the 2027 seam: when {@code edu.wpi.first.wpilibj.Timer} becomes
 * {@code org.wpilib.*.Timer}, exactly one import in this file changes.
 */
public final class Clock {

  private Clock() {}

  /**
   * The loop period assumed before {@code PumpkinRobot} tells us the real one. Matches WPILib's
   * default {@code IterativeRobotBase} period.
   */
  public static final double kDefaultPeriodSeconds = 0.02;

  /** Null means "read the FPGA-backed clock"; non-null is an installed deterministic source. */
  private static volatile DoubleSupplier s_source;

  private static volatile double s_periodSeconds = kDefaultPeriodSeconds;

  private static volatile long s_cycle;

  // -----------------------------------------------------------------------------------------
  // Reading time. For MEASURING durations.
  // -----------------------------------------------------------------------------------------

  /**
   * Replay-safe monotonic seconds. Maps to {@code Timer.getTimestamp()}, the AdvantageKit-injected
   * clock.
   *
   * @return seconds since the timebase started; monotonic, and identical on replay
   */
  public static double seconds() {
    DoubleSupplier source = s_source;
    return source == null ? edu.wpi.first.wpilibj.Timer.getTimestamp() : source.getAsDouble();
  }

  /**
   * The same instant as {@link #seconds()}, as a unit-typed {@link Time}.
   *
   * <p>This is the spelling {@code design/04} and {@code design/05} name at every call site; use it
   * wherever the receiving signature takes units, and {@link #seconds()} inside a hot loop where
   * everything is already a {@code double}.
   *
   * @return the current timestamp
   */
  public static Time now() {
    return Seconds.of(seconds());
  }

  /**
   * The current timestamp in microseconds.
   *
   * <p>On the real robot this reads {@code RobotController.getFPGATime()} directly, because that is
   * already integral microseconds and rounding {@link #seconds()} would throw away the low bits that
   * make latency arithmetic (vision timestamps, CAN signal latency) meaningful. When a deterministic
   * source is installed it is derived from that source instead, so a test that steps time sees both
   * spellings agree.
   *
   * @return microseconds since the timebase started
   */
  public static long nowMicros() {
    DoubleSupplier source = s_source;
    if (source == null) {
      return edu.wpi.first.wpilibj.RobotController.getFPGATime();
    }
    return Math.round(source.getAsDouble() * 1_000_000.0);
  }

  // -----------------------------------------------------------------------------------------
  // The loop period.
  // -----------------------------------------------------------------------------------------

  /**
   * THE loop timestep, in seconds.
   *
   * <p>Captured once from {@code IterativeRobotBase.getPeriod()} at {@code PumpkinRobot}
   * construction (via {@link #setPeriodSeconds(double)}) and never re-read. Until it is set, this is
   * {@link #kDefaultPeriodSeconds}, so a library type constructed before the robot exists still
   * behaves.
   *
   * <p>D12 makes this the one spelling in the library — it is what replaced the deleted
   * {@code Pumpkin.dt()} god-object accessor. There is deliberately no {@code periodSeconds()} twin:
   * two spellings of one number is how {@code 0.02} ends up hardcoded in five places again.
   *
   * @return the loop period in seconds
   */
  public static double dt() {
    return s_periodSeconds;
  }

  /**
   * The same number as {@link #dt()}, as a {@link Time}, for signatures that take units.
   *
   * @return the loop period
   */
  public static Time period() {
    return Seconds.of(s_periodSeconds);
  }

  /**
   * Installs the real loop period. Called once, from {@code PumpkinRobot}'s constructor, with
   * {@code getPeriod()}.
   *
   * <p>Public because {@code PumpkinRobot} is in a different package and because
   * {@code pumpkinlib-testkit} has to be able to say "pretend this robot runs at 100 Hz". It is not
   * something team code ever calls.
   *
   * @param seconds the loop period; must be finite and strictly positive
   * @throws IllegalArgumentException if {@code seconds} is not finite and strictly positive
   */
  public static void setPeriodSeconds(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0.0) {
      throw new IllegalArgumentException(
          "Clock.setPeriodSeconds: seconds was "
              + seconds
              + "; the loop period must be finite and strictly positive. "
              + "Fix: pass IterativeRobotBase.getPeriod(), which is 0.02 by default.");
    }
    s_periodSeconds = seconds;
  }

  /**
   * Installs the real loop period, unit-typed.
   *
   * @param period the loop period; must be finite and strictly positive
   * @throws IllegalArgumentException if {@code period} is not finite and strictly positive
   */
  public static void setPeriod(Time period) {
    setPeriodSeconds(period.in(Seconds));
  }

  // -----------------------------------------------------------------------------------------
  // The cycle counter. For SCHEDULING.
  // -----------------------------------------------------------------------------------------

  /**
   * Monotonic robot-loop counter. Increments exactly once per {@code periodic()}, from 0.
   *
   * @return the number of completed robot loops since boot
   */
  public static long cycle() {
    return s_cycle;
  }

  /**
   * Advances the loop counter by one. Called exactly once per loop, from
   * {@code PumpkinLifecycle.beforeUserPeriodic()}.
   *
   * <p>Public for the same two reasons {@link #setPeriodSeconds(double)} is: the caller is in
   * another package, and a hand-ticked JUnit test has to be able to drive it. Team code never calls
   * it; calling it twice in a loop makes every {@link #everyNCycles(int, String)} gate in the
   * library fire at the wrong rate.
   */
  public static void tick() {
    s_cycle++;
  }

  /**
   * How many robot loops make up one period of {@code hz}, minimum 1.
   *
   * <p>{@code periodCycles(Hertz.of(4))} is 13 at 50 Hz: {@code ceil(1 / (4 * 0.02)) = ceil(12.5)}.
   * THIS is how every periodic rate in PumpkinLib is expressed. It is exact under replay, under a
   * 100 Hz robot, and under sim stepping — a wall-clock gate is none of those.
   *
   * @param hz the desired rate; must be finite and strictly positive
   * @return the number of loops per period, at least 1
   * @throws IllegalArgumentException if {@code hz} is not finite and strictly positive
   */
  public static int periodCycles(Frequency hz) {
    double value = hz.in(Hertz);
    if (!Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(
          "Clock.periodCycles: rate was "
              + value
              + " Hz; it must be finite and strictly positive. "
              + "Fix: pass something like Hertz.of(4). For 'every loop', use 1 directly.");
    }
    return Math.max(1, (int) Math.ceil(1.0 / (value * dt())));
  }

  /**
   * Converts a duration to a whole number of loops, rounded up, minimum 1.
   *
   * <p>Used by every debounce in the library, which is why a 40 ms debounce is 2 loops at 50 Hz and
   * 4 loops at 100 Hz rather than a seconds comparison that silently means something different on
   * each.
   *
   * @param duration the duration; must be finite and non-negative
   * @return the number of loops, at least 1
   * @throws IllegalArgumentException if {@code duration} is not finite and non-negative
   */
  public static int cyclesFor(Time duration) {
    double seconds = duration.in(Seconds);
    if (!Double.isFinite(seconds) || seconds < 0.0) {
      throw new IllegalArgumentException(
          "Clock.cyclesFor: duration was "
              + seconds
              + " s; it must be finite and non-negative. Fix: pass something like "
              + "Milliseconds.of(40).");
    }
    return Math.max(1, (int) Math.ceil(seconds / dt()));
  }

  /**
   * True on exactly one loop out of every {@code n}, with a stable phase offset derived from the
   * key.
   *
   * <p>The phase offset is the point: without it, ten 4 Hz consumers all land on cycle 0 and rebuild
   * the very CPU spike the rate gate exists to remove. The offset is a pure function of the key, so
   * it is identical in real time, in replay, in sim stepping and in JUnit.
   *
   * <p>This is a pure function of {@link #cycle()}, so calling it twice in one loop returns the same
   * answer — it is safe to use as a plain condition rather than something you must poll once.
   *
   * @param n the period in loops; values below 2 mean "every loop"
   * @param phaseKey a stable identifier for the consumer, e.g. the mechanism name
   * @return true on this consumer's loop
   */
  public static boolean everyNCycles(int n, String phaseKey) {
    return n <= 1 || (cycle() + Math.floorMod(phaseKey.hashCode(), n)) % n == 0;
  }

  // -----------------------------------------------------------------------------------------
  // The deterministic-source seam. Sim and unit tests only.
  // -----------------------------------------------------------------------------------------

  /**
   * Installs a deterministic time source, replacing the FPGA-backed clock.
   *
   * <p>This exists so {@code org.pumpkinlib.sim.HeadlessClock} and {@code PumpkinTest} can step time
   * by hand, with no HAL and no natives, and have every duration in the library follow. It affects
   * {@link #seconds()}, {@link #now()} and {@link #nowMicros()}; it does not touch the loop period or
   * the cycle counter, which have their own explicit controls ({@link #setPeriodSeconds(double)} and
   * {@link #tick()}) precisely so a test can advance one without the other.
   *
   * @param secondsSource supplies monotonically non-decreasing seconds
   * @throws IllegalArgumentException if {@code secondsSource} is null
   */
  public static void setSource(DoubleSupplier secondsSource) {
    if (secondsSource == null) {
      throw new IllegalArgumentException(
          "Clock.setSource: secondsSource was null. "
              + "Fix: call Clock.useFpgaSource() to go back to the robot clock — passing null is "
              + "not the spelling for that, because a null here is far more often a bug.");
    }
    s_source = secondsSource;
  }

  /** Restores the FPGA-backed clock, undoing {@link #setSource(DoubleSupplier)}. */
  public static void useFpgaSource() {
    s_source = null;
  }

  /**
   * Whether a deterministic source is currently installed.
   *
   * <p>Printed in the boot dump, so "why is time not advancing" is answerable from a log rather than
   * from a debugger.
   *
   * @return true if {@link #setSource(DoubleSupplier)} is in effect
   */
  public static boolean isSourceOverridden() {
    return s_source != null;
  }

  /**
   * Restores every default: the FPGA clock, the default loop period and cycle zero.
   *
   * <p>For {@code @BeforeEach} in unit tests. Static state that survives between tests is how a test
   * suite becomes order-dependent.
   */
  public static void resetForTest() {
    s_source = null;
    s_periodSeconds = kDefaultPeriodSeconds;
    s_cycle = 0L;
  }

  /**
   * A one-line description of the clock's current state, for the boot dump.
   *
   * @return e.g. {@code "Clock: dt=0.020 s (50.0 Hz), cycle=1204, source=FPGA"}
   */
  public static String describe() {
    return String.format(
        "Clock: dt=%.3f s (%.1f Hz), cycle=%d, source=%s, now=%.3f s (%d us)",
        dt(),
        1.0 / dt(),
        cycle(),
        isSourceOverridden() ? "TEST" : "FPGA",
        seconds(),
        Math.round(now().in(Microseconds)));
  }
}

package org.pumpkinlib.core.compat;

import static edu.wpi.first.units.Units.Hertz;
import static edu.wpi.first.units.Units.Milliseconds;
import static edu.wpi.first.units.Units.Seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link Clock} — the one clock, and the deterministic time source that makes every other test in
 * this suite possible.
 *
 * <p><strong>No natives.</strong> Every test here installs a {@link Clock#setSource} before reading
 * time. That is not a workaround, it is the seam under test: without it {@link Clock#seconds()}
 * reads {@code Timer.getTimestamp()} and {@link Clock#nowMicros()} reads {@code
 * RobotController.getFPGATime()}, both of which need the WPILib JNI natives. The whole reason
 * {@code setSource} exists is that a unit test, {@code SimHooks}-stepped simulation and an
 * AdvantageKit replay all need to drive time without a roboRIO.
 *
 * <p><strong>The three counters are independent on purpose.</strong> {@code setSource} moves the
 * timestamp, {@link Clock#setPeriodSeconds(double)} sets the timestep and {@link Clock#tick()}
 * advances the cycle counter. A test can advance any one without the others, which is what lets it
 * pin behaviour that would otherwise only differ on a 100 Hz robot.
 */
final class ClockTest {

  private static final double kEps = 1e-9;

  /** The installed deterministic time source. Element 0 is "now", in seconds. */
  private final double[] m_now = {0.0};

  @BeforeEach
  void installDeterministicTime() {
    Clock.resetForTest();
    Clock.setSource(() -> m_now[0]);
  }

  @AfterEach
  void restoreDefaults() {
    Clock.resetForTest();
  }

  @Nested
  @DisplayName("the deterministic time source")
  final class TimeSource {

    @Test
    void secondsFollowsTheInstalledSource() {
      m_now[0] = 0.0;
      assertEquals(0.0, Clock.seconds(), kEps);
      m_now[0] = 12.34;
      assertEquals(12.34, Clock.seconds(), kEps);
    }

    /** {@code now()} and {@code seconds()} are two spellings of one number, never two clocks. */
    @Test
    void nowAgreesWithSeconds() {
      m_now[0] = 3.5;
      assertEquals(Clock.seconds(), Clock.now().in(Seconds), kEps);
    }

    /**
     * {@code nowMicros()} reads the FPGA directly on hardware, so it needs its own test that it
     * follows the installed source instead — otherwise a test that steps time would see the two
     * spellings disagree and every latency calculation would be wrong by the offset.
     */
    @Test
    void nowMicrosIsDerivedFromTheSourceWhenOneIsInstalled() {
      m_now[0] = 1.5;
      assertEquals(1_500_000L, Clock.nowMicros());
      m_now[0] = 0.000_002;
      assertEquals(2L, Clock.nowMicros());
    }

    @Test
    void isSourceOverriddenReportsTheSeamHonestly() {
      assertTrue(Clock.isSourceOverridden());
      Clock.useFpgaSource();
      assertFalse(Clock.isSourceOverridden());
    }

    /** Null is far more often a bug than an intent to restore the robot clock. */
    @Test
    void setSourceRejectsNullAndNamesTheRealSpelling() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Clock.setSource(null));
      assertTrue(
          e.getMessage().contains("Clock.useFpgaSource()"),
          "the message must name the method that actually restores the robot clock. Was:\n"
              + e.getMessage());
    }

    @Test
    void resetForTestRestoresEveryDefault() {
      m_now[0] = 99.0;
      Clock.setPeriodSeconds(0.01);
      Clock.tick();
      Clock.tick();

      Clock.resetForTest();

      assertFalse(Clock.isSourceOverridden());
      assertEquals(Clock.kDefaultPeriodSeconds, Clock.dt(), kEps);
      assertEquals(0L, Clock.cycle());
    }
  }

  @Nested
  @DisplayName("the loop period")
  final class Period {

    @Test
    void defaultsToTwentyMilliseconds() {
      assertEquals(0.02, Clock.kDefaultPeriodSeconds, kEps);
      assertEquals(0.02, Clock.dt(), kEps);
    }

    @Test
    void periodIsTheUnitTypedTwinOfDt() {
      Clock.setPeriodSeconds(0.01);
      assertEquals(Clock.dt(), Clock.period().in(Seconds), kEps);
    }

    @Test
    void setPeriodAcceptsAUnitTypedValue() {
      Clock.setPeriod(Milliseconds.of(10));
      assertEquals(0.01, Clock.dt(), kEps);
    }

    @Test
    void setPeriodSecondsRejectsNonsenseAndNamesTheFix() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Clock.setPeriodSeconds(0.0));
      assertTrue(
          e.getMessage().contains("IterativeRobotBase.getPeriod()"),
          "the message must name where the real value comes from. Was:\n" + e.getMessage());
      assertThrows(IllegalArgumentException.class, () -> Clock.setPeriodSeconds(-0.02));
      assertThrows(IllegalArgumentException.class, () -> Clock.setPeriodSeconds(Double.NaN));
    }
  }

  @Nested
  @DisplayName("the cycle counter — scheduling counts cycles, never seconds")
  final class Cycles {

    @Test
    void tickAdvancesFromZeroExactlyOnce() {
      assertEquals(0L, Clock.cycle());
      Clock.tick();
      assertEquals(1L, Clock.cycle());
      Clock.tick();
      Clock.tick();
      assertEquals(3L, Clock.cycle());
    }

    /** The cycle counter must not move just because time did — they are separate controls. */
    @Test
    void advancingTimeDoesNotAdvanceTheCycleCounter() {
      m_now[0] = 10.0;
      assertEquals(0L, Clock.cycle());
    }

    /**
     * {@code periodCycles(4 Hz)} is 13 at 50 Hz — {@code ceil(1 / (4 * 0.02)) = ceil(12.5)} — and 25
     * at 100 Hz. A hard-coded seconds gate silently means something different on each; this
     * re-derives from the period the robot actually runs at.
     */
    @Test
    void periodCyclesRederivesFromTheActualLoopPeriod() {
      Clock.setPeriodSeconds(0.02);
      assertEquals(13, Clock.periodCycles(Hertz.of(4)));
      Clock.setPeriodSeconds(0.01);
      assertEquals(25, Clock.periodCycles(Hertz.of(4)));
    }

    @Test
    void periodCyclesIsNeverLessThanOneLoop() {
      assertEquals(1, Clock.periodCycles(Hertz.of(1000)));
    }

    @Test
    void periodCyclesRejectsNonsense() {
      assertThrows(IllegalArgumentException.class, () -> Clock.periodCycles(Hertz.of(0)));
      assertThrows(IllegalArgumentException.class, () -> Clock.periodCycles(Hertz.of(-4)));
    }

    /** A 40 ms debounce is 2 loops at 50 Hz and 4 loops at 100 Hz. */
    @Test
    void cyclesForRoundsUpAndRederivesFromThePeriod() {
      Clock.setPeriodSeconds(0.02);
      assertEquals(2, Clock.cyclesFor(Milliseconds.of(40)));
      assertEquals(3, Clock.cyclesFor(Milliseconds.of(41)));
      Clock.setPeriodSeconds(0.01);
      assertEquals(4, Clock.cyclesFor(Milliseconds.of(40)));
    }

    @Test
    void cyclesForIsNeverLessThanOneLoop() {
      assertEquals(1, Clock.cyclesFor(Seconds.of(0)));
    }

    @Test
    void cyclesForRejectsNegativeDurations() {
      assertThrows(IllegalArgumentException.class, () -> Clock.cyclesFor(Seconds.of(-1)));
    }
  }

  @Nested
  @DisplayName("everyNCycles — one loop in N, phase-offset by key")
  final class RateGating {

    /** Exactly one loop in every N fires, for any key. Anything else is a rate that is wrong. */
    @Test
    void firesExactlyOnceInEveryWindowOfN() {
      int n = 13;
      for (String key : new String[] {"Arm", "Intake", "Vision/Front", ""}) {
        int fired = 0;
        Clock.resetForTest();
        Clock.setSource(() -> m_now[0]);
        for (int i = 0; i < n; i++) {
          if (Clock.everyNCycles(n, key)) {
            fired++;
          }
          Clock.tick();
        }
        assertEquals(1, fired, "key \"" + key + "\" must fire exactly once per window of " + n);
      }
    }

    /**
     * The phase offset is the whole point: without it ten 4 Hz consumers all land on cycle 0 and
     * rebuild the CPU spike the rate gate exists to remove.
     */
    @Test
    void differentKeysLandOnDifferentCycles() {
      int n = 13;
      int armCycle = firstFiringCycle(n, "Arm");
      int intakeCycle = firstFiringCycle(n, "Intake");
      int elevatorCycle = firstFiringCycle(n, "Elevator");
      assertTrue(
          armCycle != intakeCycle || intakeCycle != elevatorCycle,
          "three keys landing on the identical cycle would mean the phase offset is not applied");
    }

    /** A pure function of {@code cycle()}, so it is safe to use as a plain condition. */
    @Test
    void isIdempotentWithinOneLoop() {
      for (int i = 0; i < 13; i++) {
        assertEquals(
            Clock.everyNCycles(13, "Arm"),
            Clock.everyNCycles(13, "Arm"),
            "calling it twice in one loop must give the same answer");
        Clock.tick();
      }
    }

    @Test
    void nBelowTwoMeansEveryLoop() {
      for (int i = 0; i < 5; i++) {
        assertTrue(Clock.everyNCycles(1, "Arm"));
        assertTrue(Clock.everyNCycles(0, "Arm"));
        Clock.tick();
      }
    }

    private int firstFiringCycle(int n, String key) {
      Clock.resetForTest();
      Clock.setSource(() -> m_now[0]);
      for (int i = 0; i < n; i++) {
        if (Clock.everyNCycles(n, key)) {
          return i;
        }
        Clock.tick();
      }
      throw new AssertionError("key \"" + key + "\" never fired in " + n + " cycles");
    }
  }

  @Nested
  @DisplayName("describe() — 'why is time not advancing' answerable from the log")
  final class Describe {

    @Test
    void describeNamesTheSourceThePeriodAndTheCycle() {
      m_now[0] = 1.5;
      Clock.setPeriodSeconds(0.02);
      Clock.tick();

      String description = Clock.describe();

      assertTrue(description.contains("source=TEST"), description);
      assertTrue(description.contains("dt=0.020 s"), description);
      assertTrue(description.contains("50.0 Hz"), description);
      assertTrue(description.contains("cycle=1"), description);
      assertTrue(description.contains("1500000 us"), description);
    }
  }
}

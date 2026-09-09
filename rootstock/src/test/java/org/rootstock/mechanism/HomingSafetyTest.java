package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.config.HomingStrategy;
import org.rootstock.config.SensorSpec;

/**
 * <b>Every homing strategy terminates.</b> A routine that drives a mechanism into a hard stop and
 * waits for a signal that never comes must give up, say so, and leave {@code isHomed()} false — it
 * must never sit on the stop drawing stall current until somebody disables the robot.
 *
 * <p>There are four strategies and the termination argument is different for each:
 *
 * <ul>
 *   <li>{@code currentSpike} and {@code limitSwitch} <b>move</b>, so they carry a wall-clock timeout
 *       and the timeout is the termination proof. Both default to four seconds, and a non-positive
 *       one is a configuration error that {@code HomingRunner.begin()} refuses <i>before</i> it
 *       commands a volt.
 *   <li>{@code absoluteSeed} and {@code assumeAtBoot} <b>do not move</b>, so they complete inside the
 *       call that starts them. There is no loop to run away with.
 *   <li>{@code firstOf} is only as terminating as its members, and inherits every one of their
 *       problems with the index that produced it.
 * </ul>
 *
 * <p><strong>Native-free.</strong> The strategies are records; nothing here constructs a {@code
 * HomingRunner} or a {@code Mechanism}, both of which build {@code RootstockAlert}s and therefore need
 * WPILib's JNI natives. {@code HomingSafetyHalTest} drives a live {@code HomingRunner} past its
 * timeout with a signal that never trips and asserts the abort reason, the neutral output and the
 * alert. What is pinned here is the property that makes that possible at all: the declared timeout,
 * and the refusal of a strategy that has none.
 */
final class HomingSafetyTest {

  /** The design's elevator strategy, written exactly as section 9.1 writes it. */
  private static final HomingStrategy.CurrentSpike kDesignElevatorHoming =
      HomingStrategy.currentSpike()
          .direction(HomingStrategy.Direction.REVERSE)
          .voltage(Volts.of(-1.5))
          .currentThreshold(Amps.of(30))
          .debounce(Seconds.of(0.15))
          .timeout(Seconds.of(4.0))
          .seedTo(Inches.of(0.0));

  @Nested
  @DisplayName("the two strategies that move")
  final class Moving {

    /** A current-spike routine declares a finite, positive timeout out of the box. */
    @Test
    void currentSpikeDefaultsToAFiniteTimeout() {
      HomingStrategy.CurrentSpike spike = HomingStrategy.currentSpike();
      assertTrue(spike.needsMotion());
      assertTrue(
          Double.isFinite(spike.timeoutSeconds()) && spike.timeoutSeconds() > 0.0,
          "a moving routine with no timeout is a mechanism on its stop until the match ends: "
              + spike.timeoutSeconds());
      assertEquals(4.0, spike.timeoutSeconds(), 0.0);
      assertTrue(spike.problems().isEmpty(), "the default must be usable: " + spike.problems());
    }

    /** So does a limit-switch routine. */
    @Test
    void limitSwitchDefaultsToAFiniteTimeout() {
      HomingStrategy.LimitSwitch limit =
          HomingStrategy.limitSwitch(SensorSpec.dio(0, true, Seconds.of(0.02)));
      assertTrue(limit.needsMotion());
      assertEquals(4.0, limit.timeoutSeconds(), 0.0);
      assertTrue(limit.problems().isEmpty(), limit.problems().toString());
    }

    /**
     * A non-positive timeout is a configuration error whose message names the exact failure — the
     * mechanism pushing against its stop until the robot is disabled — and {@code
     * HomingRunner.begin()} refuses a strategy with any problem before it drives.
     *
     * <p>That refusal is what makes "every strategy terminates" true rather than aspirational: the
     * only way to reach the driving loop is with a timeout that will eventually fire.
     */
    @Test
    void aRoutineWithNoTimeoutIsRefusedRatherThanRun() {
      for (double bad : new double[] {0.0, -1.0, Double.NaN}) {
        List<String> problems = kDesignElevatorHoming.timeout(Seconds.of(bad)).problems();
        assertFalse(problems.isEmpty(), "timeout " + bad + " must be reported");
        assertTrue(
            String.join(" ", problems).contains("timeout"),
            "the message must name the field: " + problems);
        assertTrue(
            String.join(" ", problems).contains("push"),
            "and the consequence, in words a student can act on: " + problems);
      }
    }

    /**
     * A current threshold that can never be exceeded is the subtler version of the same bug: the
     * routine runs to its full timeout with the mechanism jammed against the stop the whole time.
     * The message says exactly that, which is why it is worth a test.
     */
    @Test
    void anUnreachableCurrentThresholdIsReportedWithItsConsequence() {
      List<String> problems = kDesignElevatorHoming.currentThreshold(Amps.of(0.0)).problems();
      String joined = String.join(" ", problems);
      assertFalse(problems.isEmpty());
      assertTrue(joined.contains("timed out"), joined);
      assertTrue(
          joined.contains("hard stop"),
          "the point is that the timeout is reached WITH the mechanism on the stop: " + joined);
    }

    /**
     * A zero drive voltage means the mechanism never moves and therefore always times out — a
     * terminating failure, but still a failure, and it is reported rather than run.
     */
    @Test
    void aZeroDriveVoltageIsReported() {
      List<String> problems = kDesignElevatorHoming.voltage(Volts.of(0.0)).problems();
      assertFalse(problems.isEmpty());
      assertTrue(String.join(" ", problems).contains("never move"), problems.toString());
    }

    /**
     * The drive magnitude is clamped to {@value HomingStrategy#kMaxHomingVolts} V and the sign comes
     * from the direction, not from the number.
     *
     * <p>This is why the design's {@code .voltage(Volts.of(-1.5))} paired with {@code
     * .direction(REVERSE)} is not a double negative that drives the wrong way: the record's compact
     * constructor takes the magnitude, and {@code signedVolts()} applies the direction once.
     */
    @Test
    void theMagnitudeIsClampedAndTheSignComesFromTheDirection() {
      assertEquals(1.5, kDesignElevatorHoming.volts(), 1e-12, "abs(-1.5), not -1.5");
      assertEquals(
          -1.5,
          kDesignElevatorHoming.signedVolts(),
          1e-12,
          "REVERSE applies the sign exactly once, so the elevator drives down");
      assertEquals(
          1.5,
          kDesignElevatorHoming.direction(HomingStrategy.Direction.FORWARD).signedVolts(),
          1e-12);
      assertEquals(
          HomingStrategy.kMaxHomingVolts,
          kDesignElevatorHoming.voltage(Volts.of(-40.0)).volts(),
          1e-12,
          "no number a team can write drives homing faster than 3 V into a hard stop");
    }

    /**
     * While the routine runs it installs its own stator limit, so a mechanism that reaches the stop
     * before the trigger fires is current-limited rather than relying on the timeout alone.
     *
     * <p>30 A threshold x 1.5 = 45 A, which is above the trip point (or the routine could never
     * trip) and below the mechanism's own 70 A limit (or the limit would do nothing).
     */
    @Test
    void theRoutineInstallsItsOwnStatorLimitAboveTheTripPoint() {
      assertEquals(1.5, HomingStrategy.kCurrentLimitFactor, 0.0);
      assertEquals(45.0, kDesignElevatorHoming.routineStatorLimitAmps(), 1e-12);
      assertTrue(
          kDesignElevatorHoming.routineStatorLimitAmps()
              > kDesignElevatorHoming.currentThresholdAmps(),
          "a limit at or below the trip point would stop the routine ever tripping");
    }

    /** A zero debounce lets startup inrush read as a hard stop and seed the position in mid-air. */
    @Test
    void aNegativeDebounceIsReportedAndTheDefaultIsNotZero() {
      assertEquals(0.15, HomingStrategy.currentSpike().debounceSeconds(), 1e-12);
      assertFalse(kDesignElevatorHoming.debounce(Seconds.of(-0.1)).problems().isEmpty());
    }
  }

  @Nested
  @DisplayName("the two strategies that do not move")
  final class Instant {

    /**
     * An absolute seed needs no motion, so it completes inside {@code begin()} and there is no
     * driving loop that could fail to terminate.
     */
    @Test
    void anAbsoluteSeedNeverDrives() {
      HomingStrategy.AbsoluteSeed seed = HomingStrategy.absoluteSeed();
      assertFalse(seed.needsMotion(), "nothing to time out");
      assertTrue(seed.seedsPosition());
      assertTrue(seed.isTrustworthy(), "a sensor measured it");
      assertTrue(seed.problems().isEmpty());
    }

    /** A boot-time assumption needs no motion either, and admits that nothing confirms it. */
    @Test
    void aBootAssumptionNeverDrivesAndIsNotTrustworthy() {
      HomingStrategy.AssumeAtBoot assumed = HomingStrategy.assumeAtBoot(Degrees.of(90.0));
      assertFalse(assumed.needsMotion());
      assertTrue(assumed.seedsPosition());
      assertFalse(
          assumed.isTrustworthy(),
          "nothing measured this, so a mechanism moved by hand while disabled is now lying");
      assertEquals(90.0, assumed.seedToUserUnits(), 1e-12);
      assertTrue(
          assumed.describe().toUpperCase(Locale.ROOT).contains("ASSUMED"),
          "the boot dump must shout it: " + assumed.describe());
    }

    /** A NaN assumption would make every subsequent goal NaN, so it is reported. */
    @Test
    void aNanAssumptionIsReported() {
      assertFalse(new HomingStrategy.AssumeAtBoot(Double.NaN).problems().isEmpty());
    }

    /** A NaN agreement tolerance would make the guarded reseed never pass, so it is reported. */
    @Test
    void aNanAgreementToleranceIsReported() {
      assertFalse(new HomingStrategy.AbsoluteSeed(Double.NaN).problems().isEmpty());
      assertTrue(HomingStrategy.absoluteSeed(Degrees.of(2.0)).problems().isEmpty());
      assertEquals(
          2.0, HomingStrategy.absoluteSeed(Degrees.of(2.0)).agreementToleranceUserUnits(), 1e-12);
    }
  }

  @Nested
  @DisplayName("firstOf — a composite is only as good as its worst member")
  final class Composite {

    /**
     * The useful shape: an absolute encoder if it is fitted, otherwise drive into the stop. The
     * composite needs motion because one member does, so the runner keeps the timeout machinery.
     */
    @Test
    void aCompositeNeedsMotionIfAnyMemberDoes() {
      HomingStrategy composite =
          HomingStrategy.firstOf(HomingStrategy.absoluteSeed(), kDesignElevatorHoming);
      assertTrue(composite.needsMotion());
      assertTrue(composite.seedsPosition());
      assertTrue(composite.problems().isEmpty(), composite.problems().toString());
    }

    /** And it is only as trustworthy as its least trustworthy member, because that is the fallback. */
    @Test
    void aCompositeIsOnlyAsTrustworthyAsItsWorstMember() {
      assertFalse(
          HomingStrategy.firstOf(
                  HomingStrategy.absoluteSeed(), HomingStrategy.assumeAtBoot(Degrees.of(90.0)))
              .isTrustworthy(),
          "the assumption is the one that runs when the encoder is missing — which is the case "
              + "being configured for");
    }

    /** An empty composite would never home at all, and says so rather than silently doing nothing. */
    @Test
    void anEmptyCompositeIsReported() {
      HomingStrategy empty = HomingStrategy.firstOf();
      assertFalse(empty.problems().isEmpty());
      assertTrue(String.join(" ", empty.problems()).contains("never home"), empty.problems().toString());
      assertFalse(empty.isTrustworthy());
    }

    /** A member's problem is reported with the index that produced it, so it can be found. */
    @Test
    void aMembersProblemIsReportedWithItsIndex() {
      HomingStrategy composite =
          HomingStrategy.firstOf(
              HomingStrategy.absoluteSeed(), kDesignElevatorHoming.timeout(Seconds.of(0.0)));
      String joined = String.join(" ", composite.problems());
      assertTrue(joined.contains("firstOf[1]"), joined);
      assertTrue(joined.contains("timeout"), joined);
    }
  }

  @Nested
  @DisplayName("the design's own elevator strategy")
  final class DesignExample {

    /** Section 9.1's homing declaration, read back field by field. */
    @Test
    void section91RoundTrips() {
      assertEquals(HomingStrategy.Direction.REVERSE, kDesignElevatorHoming.direction());
      assertEquals(30.0, kDesignElevatorHoming.currentThresholdAmps(), 1e-12);
      assertEquals(0.15, kDesignElevatorHoming.debounceSeconds(), 1e-12);
      assertEquals(4.0, kDesignElevatorHoming.timeoutSeconds(), 1e-12);
      assertEquals(0.0, kDesignElevatorHoming.seedToUserUnits(), 1e-12);
      assertTrue(kDesignElevatorHoming.problems().isEmpty());
      assertTrue(kDesignElevatorHoming.seedsPosition());
      assertTrue(kDesignElevatorHoming.isTrustworthy());
    }

    /** Section 9.1's arm declaration: an absolute seed, which needs no motion and no timeout. */
    @Test
    void theArmSeedsFromItsCancoder() {
      HomingStrategy arm = HomingStrategy.absoluteSeed();
      assertFalse(arm.needsMotion());
      assertTrue(arm.problems().isEmpty());
    }
  }
}

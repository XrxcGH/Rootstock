package org.pumpkinlib.mechanism;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.hardware.MotorCapabilities;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;
import org.pumpkinlib.units.RotaryAxis;
import org.pumpkinlib.units.SiDomain;

/**
 * The same {@code PositionConfig} must mean the same physical motion in all four {@link
 * ControlLocation}s — and the way that guarantee actually breaks is a <b>unit</b> error, not a
 * control-theory one.
 *
 * <h2>The 360x class of error, stated explicitly</h2>
 *
 * <p>A rotary mechanism has three different numbers for the same angle and they differ by large,
 * plausible-looking constants:
 *
 * <ul>
 *   <li><b>user degrees</b> — what the config, the setpoints and every published key speak;
 *   <li><b>output rotations</b> — what {@code MotorIO.setPositionGoal} wants, and therefore what
 *       {@code ON_MOTOR_PROFILED}, {@code ON_MOTOR_DIRECT} and {@code RIO_PROFILE_MOTOR_LOOP} send.
 *       <b>360x smaller</b> than the degree count;
 *   <li><b>SI radians</b> — what {@code RIO_FULL}'s PID, profile and feedforward run in, because the
 *       gains are volts-per-SI. <b>57.29578x smaller</b> than the degree count, and <b>2&pi;x
 *       larger</b> than the rotation count.
 * </ul>
 *
 * <p>Every one of those three is a number in the low tens for a normal arm, so substituting one for
 * another produces a mechanism that moves — just not where it was told. That is why this file spends
 * its assertions on the conversion identities rather than on trajectories: an arm that goes to 0.25
 * instead of 90 is the failure, and it is arithmetic.
 *
 * <p><strong>What is here and what is in the HAL twin.</strong> This class is native-free: it pins
 * the conversions each location relies on, the invariant that ties them together, and the capability
 * downgrade that decides which location a mechanism actually ends up in. {@code
 * ControlLocationParityHalTest} runs the same config through four live {@code PositionMechanism}s
 * and compares the motion. A {@code Mechanism} needs the HAL — its constructor builds a {@code
 * PumpkinAlert}, which reaches NetworkTables and exits the JVM when the natives are absent.
 */
final class ControlLocationParityTest {

  /** The design's arm: 58/10 x 58/18 x 42/12, which is 65.4111... to one. */
  private static final Reduction kArmReduction =
      Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12);

  /** The design's arm axis: cosine gravity, horizontal at zero. */
  private static final MechanismUnits kArm =
      MechanismUnits.of(kArmReduction, RotaryAxis.arm(Degrees.of(0.0)));

  /** The design's elevator: 12:1 into a 22-tooth, 0.25 in sprocket cascaded twice. */
  private static final MechanismUnits kElevator =
      MechanismUnits.of(Reduction.ofStages(3.0, 4.0), LinearAxis.sprocket(Inches.of(0.25), 22, 2));

  @Nested
  @DisplayName("the 360x guard")
  final class ThreeHundredAndSixty {

    /**
     * A degree count and an output-rotation count differ by exactly 360, and the library must never
     * hand one where the other is wanted.
     *
     * <p>90 degrees is 0.25 output rotations. If {@code setPositionGoal} were handed 90, the device
     * would drive 90 output rotations — 32,400 degrees — into whatever the arm is bolted to.
     */
    @Test
    void userDegreesAreThreeHundredAndSixtyTimesAnOutputRotationCount() {
      assertEquals(360.0, kArm.userPerOutputRotation(), 1e-12);
      assertEquals(0.25, kArm.toOutputRotations(90.0), 1e-12);
      assertEquals(90.0, kArm.toUser(0.25), 1e-12);
      assertNotEquals(
          90.0,
          kArm.toOutputRotations(90.0),
          "if these were ever equal the device would be commanded 32,400 degrees");
      assertEquals(
          360.0,
          90.0 / kArm.toOutputRotations(90.0),
          1e-9,
          "the ratio must be exactly one turn, in both directions");
    }

    /**
     * A degree count and an SI radian count differ by 57.29578, which is the constant that turns a
     * 90 degree arm setpoint into a 5157 on a dashboard that mislabels the unit.
     */
    @Test
    void userDegreesAreFiftySevenPointThreeTimesARadian() {
      assertEquals(Math.PI / 2.0, kArm.toSi(90.0), 1e-12);
      assertEquals(
          180.0 / Math.PI,
          90.0 / kArm.toSi(90.0),
          1e-9,
          "57.29577951... — the number that must never appear as a factor in a published stream");
      assertEquals(90.0, kArm.fromSi(kArm.toSi(90.0)), 1e-12);
    }

    /** Output rotations and radians differ by 2*pi. That is the RIO_FULL to on-motor bridge. */
    @Test
    void outputRotationsAndRadiansDifferByTwoPi() {
      assertEquals(2.0 * Math.PI, kArm.siPerOutputRotation(), 1e-12);
      assertEquals(
          2.0 * Math.PI,
          kArm.toSi(90.0) / kArm.toOutputRotations(90.0),
          1e-9);
    }

    /**
     * And the rotor is 65.411 turns per output turn on top of all of that, so the full stack from a
     * published degree to a motor shaft rotation is 65.411/360.
     */
    @Test
    void theRotorAddsTheGearboxOnTop() {
      double ratio = kArmReduction.rotorPerOutput();
      assertEquals(
          (58.0 / 10.0) * (58.0 / 18.0) * (42.0 / 12.0),
          ratio,
          1e-9,
          "58/10 x 58/18 x 42/12, recomputed rather than copied from the design's 65.411");
      assertEquals(65.41111111111111, ratio, 1e-9);
      assertEquals(0.25 * ratio, kArm.toRotorRotations(90.0), 1e-9);
      assertEquals(90.0, kArm.toUserFromRotorRotations(kArm.toRotorRotations(90.0)), 1e-9);
    }

    /**
     * The linear case has its own version of the same trap: metres are already SI, so {@code toSi}
     * is the identity and the only interesting factor is metres per output rotation.
     */
    @Test
    void aLinearAxisConvertsOnlyThroughTheSprocket() {
      assertEquals(SiDomain.LINEAR_METERS, kElevator.siDomain());
      assertEquals(0.27940, kElevator.siPerOutputRotation(), 1e-9, "22 x 0.25 in x 2 = 11 in");
      assertEquals(1.0, kElevator.toSi(1.0), 0.0, "metres are already SI: no conversion at all");
      assertEquals(1.0 / 0.27940, kElevator.toOutputRotations(1.0), 1e-9);
      assertEquals(
          52.5 * 0.0254 / 0.27940,
          kElevator.toOutputRotations(Inches.of(52.5).in(edu.wpi.first.units.Units.Meters)),
          1e-9,
          "the design's L4: 1.3335 m is 4.7727... output rotations");
    }
  }

  @Nested
  @DisplayName("the parity invariant the four locations share")
  final class Parity {

    /**
     * The identity that makes all four locations equivalent: the device-facing rotation count and
     * the RIO_FULL SI value are two renderings of one physical configuration, related only by
     * {@code siPerOutputRotation()}.
     *
     * <p>{@code ON_MOTOR_*} and {@code RIO_PROFILE_MOTOR_LOOP} send {@code toOutputRotations(goal)};
     * {@code RIO_FULL} closes on {@code toSi(goal)}. If this identity holds, the same config commands
     * the same physical place in all four. If it does not, one of the four is silently wrong by a
     * constant, which is exactly the failure mode this test exists for.
     */
    @Test
    void theDeviceRotationAndTheSiValueAreTheSameConfigurationInDifferentUnits() {
      for (double userDeg : new double[] {-15.0, 0.0, 35.0, 90.0, 105.0}) {
        assertEquals(
            kArm.toSi(userDeg) / kArm.siPerOutputRotation(),
            kArm.toOutputRotations(userDeg),
            1e-12,
            "arm at " + userDeg + " deg");
      }
      for (double userM : new double[] {0.0, 0.5207, 0.9525, 1.3335}) {
        assertEquals(
            kElevator.toSi(userM) / kElevator.siPerOutputRotation(),
            kElevator.toOutputRotations(userM),
            1e-12,
            "elevator at " + userM + " m");
      }
    }

    /** The same identity for velocity, which is the term the goal-velocity carrier transports. */
    @Test
    void velocityObeysTheSameIdentity() {
      for (double userDegPerSec : new double[] {-540.0, -1.0, 0.0, 180.0}) {
        assertEquals(
            kArm.toSiPerSec(userDegPerSec) / kArm.siPerOutputRotation(),
            kArm.toOutputRps(userDegPerSec),
            1e-12);
        assertEquals(userDegPerSec, kArm.toUserPerSec(kArm.toOutputRps(userDegPerSec)), 1e-9);
      }
    }

    /** And for acceleration, which is what a dynamic profile change sends to the device. */
    @Test
    void accelerationObeysTheSameIdentity() {
      for (double userDegPerSec2 : new double[] {0.0, 540.0, 6000.0}) {
        assertEquals(
            kArm.toSiPerSec2(userDegPerSec2) / kArm.siPerOutputRotation(),
            kArm.toOutputRps2(userDegPerSec2),
            1e-9);
      }
    }

    /** Every conversion round-trips, so no location loses precision the others keep. */
    @Test
    void everyConversionRoundTrips() {
      for (double userDeg : new double[] {-15.0, 0.0, 35.0, 95.0}) {
        assertEquals(userDeg, kArm.toUser(kArm.toOutputRotations(userDeg)), 1e-9);
        assertEquals(userDeg, kArm.fromSi(kArm.toSi(userDeg)), 1e-9);
        assertEquals(
            userDeg, kArm.toUserFromRotorRotations(kArm.toRotorRotations(userDeg)), 1e-9);
      }
    }
  }

  @Nested
  @DisplayName("which location a mechanism actually gets")
  final class Selection {

    /** The one question the enum exists to answer: is the feedback loop on the device? */
    @Test
    void onlyRioFullClosesTheLoopOnTheRio() {
      assertTrue(ControlLocation.ON_MOTOR_PROFILED.runsOnMotor());
      assertTrue(ControlLocation.ON_MOTOR_DIRECT.runsOnMotor());
      assertTrue(
          ControlLocation.RIO_PROFILE_MOTOR_LOOP.runsOnMotor(),
          "the profile is on the RIO but the feedback loop is still on the device");
      assertFalse(ControlLocation.RIO_FULL.runsOnMotor());
    }

    /** Which locations generate the intermediate setpoint on the RIO, and therefore publish one. */
    @Test
    void twoLocationsProfileOnTheRio() {
      assertFalse(ControlLocation.ON_MOTOR_PROFILED.profileOnRio());
      assertFalse(ControlLocation.ON_MOTOR_DIRECT.profileOnRio());
      assertTrue(ControlLocation.RIO_PROFILE_MOTOR_LOOP.profileOnRio());
      assertTrue(ControlLocation.RIO_FULL.profileOnRio());
    }

    /** Which locations latch a goal on the device and keep holding it with no further frames. */
    @Test
    void onlyTheOnMotorLocationsLatch() {
      assertTrue(ControlLocation.ON_MOTOR_PROFILED.setpointLatches());
      assertTrue(ControlLocation.ON_MOTOR_DIRECT.setpointLatches());
      assertFalse(ControlLocation.RIO_PROFILE_MOTOR_LOOP.setpointLatches());
      assertFalse(ControlLocation.RIO_FULL.setpointLatches());
    }

    /**
     * A backend that cannot serve the requested location is downgraded rather than refused, which is
     * what makes "the same config" mean anything across four backends.
     *
     * <p>A device with a position loop but no on-board profile falls back to {@code ON_MOTOR_DIRECT}
     * — the loop stays where the latency is lowest. A device with nothing falls all the way to
     * {@code RIO_FULL}, where PumpkinLib closes the loop itself in SI.
     */
    @Test
    void anUnsupportedLocationDowngradesRatherThanFailing() {
      MotorCapabilities rioOnly = MotorCapabilities.rioOnly();
      assertFalse(rioOnly.hasDeviceLoop());
      for (ControlLocation requested : ControlLocation.values()) {
        assertEquals(
            ControlLocation.RIO_FULL,
            rioOnly.downgrade(requested),
            "a backend with no device loop can only serve RIO_FULL, whatever was asked for");
      }
      assertEquals(
          ControlLocation.RIO_FULL,
          rioOnly.downgrade(null),
          "and a null request is RIO_FULL too, never a null location");
    }

    /** Downgrading is idempotent: whatever a backend settles on, it supports. */
    @Test
    void aDowngradedLocationIsAlwaysSupported() {
      MotorCapabilities rioOnly = MotorCapabilities.rioOnly();
      for (ControlLocation requested : ControlLocation.values()) {
        ControlLocation settled = rioOnly.downgrade(requested);
        assertTrue(rioOnly.supports(settled), settled + " must be supported after a downgrade");
        assertEquals(settled, rioOnly.downgrade(settled), "downgrade must be idempotent");
      }
    }
  }
}

package org.pumpkinlib.hardware.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorModel;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.spi.SimMotorHandle;
import org.pumpkinlib.hardware.MotorInputs;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;

/**
 * Simulation exercises the <b>real ratio path</b>, so a mis-entered gearing is visible on a laptop in
 * week two instead of at the first event.
 *
 * <p>That claim is only worth making if it is testable, and it is testable because the plant's
 * dynamics are analytically clean. With no declared {@code MechanismGeometry}, {@link SimMotorIO}
 * picks the inertia that makes stall torque reach free speed in {@link
 * SimMotorIO#kDefaultSpinUpSeconds}. Substituting that choice into the motor curve collapses the
 * whole plant to a first-order lag whose time constant is <em>independent of the reduction</em>:
 *
 * <pre>
 *   J          = stallTorque * G^2 * T / freeSpeed          (chosen by SimMotorIO)
 *   alpha_out  = Kt * G * (V - w_out * G / Kv) / (R * J)
 *              = (Kv * V / G  -  w_out) / T
 * </pre>
 *
 * <p>So two plants that differ <em>only</em> in the declared reduction spin up along the same curve,
 * scaled by their own free speeds. Their velocity ratio after any number of identical steps is
 * exactly the inverse ratio of their reductions — no tolerance-fudging, no "close enough". A backend
 * that applied the reduction in one place instead of two, or applied it to torque and forgot it on
 * speed, cannot produce that number.
 *
 * <p>The three properties pinned here are the ones the design claims out loud: the reduction is
 * really in the loop, the {@code simHandle()} conversion is rotor-to-output through the <em>same</em>
 * reduction a real device carries, and the declared geometry (not the bare drum radius) is what turns
 * output rotations into metres.
 */
final class SimMotorIOTest {

  /** Nominal bus, and therefore the free-speed voltage. */
  private static final double kBusVolts = SimMotorIO.kNominalBusVolts;

  /**
   * The 9143-A elevator geometry: a 22-tooth #25 sprocket on a two-stage cascade.
   *
   * <p>Derived by hand: {@code 22 teeth x 0.250 in = 5.500 in} of chain per drum rotation, doubled by
   * the cascade, so {@code 11.000 in = 11 x 0.0254 = 0.279400 m} of carriage travel per output
   * rotation.
   */
  private static final double kMetersPerOutputRotation = 11.0 * 0.0254;

  private static MechanismUnits unitsWithReduction(double rotorPerOutput) {
    return new MechanismUnits(
        Reduction.of(rotorPerOutput), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  private static SimMotorIO simWithReduction(double rotorPerOutput) {
    return new SimMotorIO(
        MotorSpec.sim("elevator", MotorModel.KRAKEN_X60),
        unitsWithReduction(rotorPerOutput),
        ControlConfig.builder()
            .gains(new Gains(80.0, 0.0, 2.0, 0.0, 12.0, 0.6, 0.0))
            .constraints(MotionConstraints.of(1.0, 2.0))
            .build(),
        MechanismKind.POSITION);
  }

  /** Free speed of one Kraken X60 rotor, non-FOC, in rotations per second — the RATED number. */
  private static double krakenRatedRotorFreeSpeedRps() {
    return MotorModel.KRAKEN_X60.freeSpeedRotorRps(false);
  }

  /**
   * The rotor speed at which the motor curve produces <b>zero torque</b> at 12 V, in rotations per
   * second — which is the asymptote a frictionless plant actually approaches.
   *
   * <p>Derived from the curve rather than copied. WPILib's {@code DCMotor} models torque as {@code
   * Kt * (V - w / Kv) / R}, and it back-solves {@code Kv} from the datasheet so that the rated free
   * speed is reached while still drawing the no-load current:
   *
   * <pre>
   *   Kv = freeSpeedRadPerSec / (Vnom - R * freeCurrentAmps)
   *   w(torque = 0, V = 12) = Kv * 12
   *            = freeSpeedRadPerSec * 12 / (12 - R * freeCurrentAmps)
   * </pre>
   *
   * <p>For a Kraken X60 the no-load current is 2 A into 12/366 ohm, so the zero-torque asymptote sits
   * about 0.55% above the rated 6000 rpm. That gap is real physics, not slop, and asserting against
   * the rated number instead would either need a fudged tolerance or would quietly hide a genuine
   * plant error of the same size.
   */
  private static double krakenZeroTorqueRotorRps() {
    var curve = MotorModel.KRAKEN_X60.dcMotor(1, false);
    double asymptoteRadPerSec =
        curve.freeSpeedRadPerSec * kBusVolts / (kBusVolts - curve.rOhms * curve.freeCurrentAmps);
    return asymptoteRadPerSec / (2.0 * Math.PI);
  }

  @BeforeEach
  void deterministicClock() {
    Clock.resetForTest();
    Clock.setSource(() -> 0.0);
    Clock.setPeriodSeconds(0.02);
  }

  @AfterEach
  void restoreClock() {
    Clock.resetForTest();
  }

  @Nested
  @DisplayName("the geometry the config declared is the geometry the sim runs")
  final class Geometry {

    /** The hand-derived travel per output rotation, so every number below is anchored. */
    @Test
    void travelPerOutputRotationIsTheHandDerivedNumber() {
      MechanismUnits units = unitsWithReduction(9.0);
      assertEquals(
          kMetersPerOutputRotation,
          units.siPerOutputRotation(),
          1e-12,
          "22 teeth x 0.250 in x 2 stages = 11.000 in = 0.279400 m per output rotation");
      assertEquals(0.2794, units.siPerOutputRotation(), 1e-12);
    }

    /**
     * The plant's steady-state speed is the declared free speed of the declared mechanism: motor free
     * speed divided by the reduction, times the travel per output rotation. Both halves of the chain
     * — gearbox and geometry — are exercised, and each is applied exactly once.
     */
    @Test
    void theSimulatedFreeSpeedMatchesTheDeclaredMechanismsFreeSpeed() {
      double rotorPerOutput = 9.0;
      MechanismUnits units = unitsWithReduction(rotorPerOutput);
      SimMotorIO io = simWithReduction(rotorPerOutput);
      MotorInputs inputs = new MotorInputs();

      io.setVoltage(kBusVolts);
      for (int i = 0; i < 400; i++) {
        io.updateInputs(inputs);
      }

      // Hand-derived: zero-torque rotor rot/s / 9 = output rot/s; x 0.2794 m per rotation = m/s.
      double expectedMetersPerSecond =
          krakenZeroTorqueRotorRps() / rotorPerOutput * kMetersPerOutputRotation;
      assertEquals(
          expectedMetersPerSecond,
          units.toUserPerSec(io.simulatedOutputRps()),
          expectedMetersPerSecond * 1e-3,
          "the free speed of the SIMULATED plant must equal the free speed the declared reduction "
              + "and geometry predict");

      // And it must agree with the library's own free-speed sanity check — the number the boot dump
      // prints — to within the no-load-current gap derived above, and no more. Two independent
      // routes through the same reduction and the same geometry.
      double ratedFreeSpeedMps = units.freeSpeedUserPerSec(krakenRatedRotorFreeSpeedRps());
      assertEquals(
          ratedFreeSpeedMps,
          units.toUserPerSec(io.simulatedOutputRps()),
          ratedFreeSpeedMps * 0.01,
          "the boot dump's free-speed check and the plant must agree to better than 1%");
      assertTrue(
          units.toUserPerSec(io.simulatedOutputRps()) > ratedFreeSpeedMps,
          "and the plant must sit slightly ABOVE the rated free speed, because the rated number is "
              + "measured while drawing the no-load current and the plant is unloaded");
    }
  }

  @Nested
  @DisplayName("a wrong gearing is visible in simulation")
  final class WrongGearing {

    /**
     * Two plants identical but for the reduction — 9:1 as built, 3:1 as mistyped (one stage of a
     * three-stage box omitted). Same voltage, same steps. The velocity ratio is <b>exactly</b> the
     * inverse ratio of the reductions, because the spin-up time constant does not depend on the
     * reduction (see the class javadoc).
     *
     * <p>This is the whole claim: the mistake shows up as a mechanism that moves three times too fast
     * in {@code simulateJava}, not as a mechanism that hits a hard stop at an event.
     */
    @Test
    void aMistypedReductionShowsUpAsTheWrongSpeed() {
      SimMotorIO asBuilt = simWithReduction(9.0);
      SimMotorIO asMistyped = simWithReduction(3.0);
      MotorInputs correct = new MotorInputs();
      MotorInputs wrong = new MotorInputs();

      asBuilt.setVoltage(kBusVolts);
      asMistyped.setVoltage(kBusVolts);
      for (int i = 0; i < 200; i++) {
        asBuilt.updateInputs(correct);
        asMistyped.updateInputs(wrong);
      }

      assertTrue(correct.velocityRps > 0.0, "guard: the correctly-geared plant must be moving");
      assertEquals(
          9.0 / 3.0,
          wrong.velocityRps / correct.velocityRps,
          1e-9,
          "a 3:1 typed where 9:1 was built must run exactly three times as fast. If this ratio is "
              + "1.0 the reduction is not in the plant at all; if it is 9.0 it is being applied "
              + "twice.");

      // Position integrates the same proportional velocities, so the travel ratio is identical.
      assertEquals(
          9.0 / 3.0,
          wrong.positionRot / correct.positionRot,
          1e-9,
          "and it must have travelled exactly three times as far");
    }

    /**
     * The error is visible in <b>user units</b> too — which is what a student actually reads on a
     * dashboard. 0.62 m/s versus 1.86 m/s is not a subtle discrepancy.
     */
    @Test
    void theWrongGearingIsVisibleInMetresPerSecond() {
      MechanismUnits correctUnits = unitsWithReduction(9.0);
      MechanismUnits wrongUnits = unitsWithReduction(3.0);
      SimMotorIO asBuilt = simWithReduction(9.0);
      SimMotorIO asMistyped = simWithReduction(3.0);
      MotorInputs a = new MotorInputs();
      MotorInputs b = new MotorInputs();

      asBuilt.setVoltage(kBusVolts);
      asMistyped.setVoltage(kBusVolts);
      for (int i = 0; i < 400; i++) {
        asBuilt.updateInputs(a);
        asMistyped.updateInputs(b);
      }

      double correctMps = correctUnits.toUserPerSec(a.velocityRps);
      double wrongMps = wrongUnits.toUserPerSec(b.velocityRps);
      assertEquals(
          krakenZeroTorqueRotorRps() / 9.0 * kMetersPerOutputRotation,
          correctMps,
          correctMps * 1e-3);
      assertEquals(
          krakenZeroTorqueRotorRps() / 3.0 * kMetersPerOutputRotation, wrongMps, wrongMps * 1e-3);
      assertTrue(
          wrongMps - correctMps > 1.0,
          "the mistake must be more than a metre per second wide, not a rounding difference. Was "
              + correctMps
              + " vs "
              + wrongMps);
    }
  }

  @Nested
  @DisplayName("simHandle(): rotor in, output out, through the same reduction")
  final class RotorConversion {

    /**
     * The handle takes <b>rotor</b> state, exactly as a vendor sim state does, and the IO divides by
     * the reduction on the way out. Writing output units through the handle would skip the one
     * conversion most likely to be misconfigured, which is the whole reason the handle sits at the
     * device rather than at the plant.
     */
    @Test
    void theHandleConvertsRotorToOutputExactly() {
      double rotorPerOutput = 9.0;
      SimMotorIO io = simWithReduction(rotorPerOutput);
      SimMotorHandle handle =
          io.simHandle().orElseThrow(() -> new AssertionError("SimMotorIO always has a handle"));

      handle.setRotorPosition(45.0, 18.0);

      assertEquals(45.0 / rotorPerOutput, io.simulatedOutputRotations(), 1e-12);
      assertEquals(18.0 / rotorPerOutput, io.simulatedOutputRps(), 1e-12);
      assertEquals(5.0, io.simulatedOutputRotations(), 1e-12, "45 rotor rotations at 9:1 is 5 output");
    }

    /**
     * The round trip through {@code MechanismUnits} closes: user units to rotor rotations, written
     * through the handle, read back as output rotations, converted back to user units.
     */
    @Test
    void userToRotorToOutputToUserRoundTripsExactly() {
      double rotorPerOutput = 9.0;
      MechanismUnits units = unitsWithReduction(rotorPerOutput);
      SimMotorIO io = simWithReduction(rotorPerOutput);
      SimMotorHandle handle = io.simHandle().orElseThrow();

      double carriageMeters = 0.75;
      double rotorRotations = units.toRotorRotations(carriageMeters);
      // Independent hand check of that conversion: 0.75 m / 0.2794 m per output rot x 9.
      assertEquals(carriageMeters / kMetersPerOutputRotation * rotorPerOutput, rotorRotations, 1e-9);

      handle.setRotorPosition(rotorRotations, 0.0);

      assertEquals(carriageMeters, units.toUser(io.simulatedOutputRotations()), 1e-12);
    }

    /**
     * Once an external simulator owns the plant, the IO stops integrating — the internal plant and an
     * external one must never both be moving the same mechanism.
     */
    @Test
    void handingOverThePlantStopsSelfIntegration() {
      SimMotorIO io = simWithReduction(9.0);
      MotorInputs inputs = new MotorInputs();
      SimMotorHandle handle = io.simHandle().orElseThrow();

      io.setVoltage(kBusVolts);
      handle.setRotorPosition(90.0, 0.0);
      for (int i = 0; i < 50; i++) {
        io.updateInputs(inputs);
      }

      assertEquals(
          10.0,
          inputs.positionRot,
          1e-12,
          "the externally-written state must not be overwritten by the internal integrator");
      assertNotEquals(
          0.0, inputs.appliedVolts, "but the applied voltage is still reported for the battery model");
    }
  }

  @Nested
  @DisplayName("closed loop through the declared units")
  final class ClosedLoop {

    /**
     * A goal expressed in metres, converted through {@link MechanismUnits}, is reached in metres.
     * This is the end-to-end statement that no ratio is applied twice anywhere along the chain.
     */
    @Test
    void aGoalInMetresIsReachedInMetres() {
      MechanismUnits units = unitsWithReduction(9.0);
      SimMotorIO io = simWithReduction(9.0);
      MotorInputs inputs = new MotorInputs();

      double goalMeters = 0.5;
      io.setPositionGoal(units.toOutputRotations(goalMeters), 0.0, 0.0);
      for (int i = 0; i < 300; i++) {
        io.updateInputs(inputs);
      }

      assertEquals(
          goalMeters,
          units.toUser(inputs.positionRot),
          goalMeters * 0.05,
          "a 0.5 m goal must arrive within 5% through the real reduction and the real geometry");
    }

    /**
     * Describe() prints the two numbers a student needs to check the chain by hand, and says out loud
     * that the default inertia was invented rather than measured.
     */
    @Test
    void describeNamesTheGearingAndAdmitsTheInventedInertia() {
      String text = simWithReduction(9.0).describe();
      assertTrue(text.contains("9.0000 rotor rotations per output rotation"), text);
      assertTrue(text.contains("0.279400"), "the travel per output rotation must be printed: " + text);
      assertTrue(text.contains("INVENTED"), "an undeclared plant must say so: " + text);
    }
  }
}

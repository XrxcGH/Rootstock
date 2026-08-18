package org.pumpkinlib.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.pure.units.Reduction;

/**
 * The gravity prior, and the factor-of-n bug it used to carry.
 *
 * <p><strong>What went wrong, and why it is not cosmetic.</strong> WPILib's {@code DCMotor}
 * constructors take a motor <em>count</em>, and an n-motor gearbox is not "one motor used n times":
 * stall torque and stall current both scale with n, so {@code KtNMPerAmp} is unchanged while {@code
 * rOhms} is divided by n. That means {@code Kt / R} — the term the gravity prior divides by —
 * <b>already carries the factor of n</b>. Dividing by the motor count a second time makes the prior
 * n times too low.
 *
 * <p>That is not a rounding error in a display. {@code HoldBisectionStep} brackets its search at
 * {@code [0.2x, 1.8x]} of this prior; an n-times-low prior puts the top of the bracket <em>below</em>
 * the true holding voltage on every multi-motor mechanism, so the bisection runs out of range and
 * the wizard reports a hardware fault on perfectly healthy hardware. A student then spends an
 * afternoon looking for a mechanical problem that does not exist.
 *
 * <p>Every number asserted here is recomputed from the motor curve and the declared geometry, and
 * the one hard-coded literal is present only so that a change to either would show up as a diff in
 * this file rather than silently as a different robot.
 *
 * <p>Native-free: {@code DCMotor} and {@link PlantPrior} are arithmetic over records.
 */
final class KgPriorTest {

  /** Standard gravity, exactly as {@link PlantPrior} uses it. */
  private static final double kG0 = 9.80665;

  /** The reference elevator: 9 kg on a 22.3 mm effective drum radius through a 12:1 gearbox. */
  private static final double kMassKg = 9.0;

  private static final double kRadiusMetres = 0.0223;

  private static final Reduction kReduction = Reduction.of(12.0);

  /** {@code tau * R / (G * Kt)} — the derivation, written out, with no library call in it. */
  private static double byHand(DCMotor motor, double massKg, double radiusMetres, double gearing) {
    double gravityTorque = massKg * kG0 * radiusMetres;
    return gravityTorque * motor.rOhms / (gearing * motor.KtNMPerAmp);
  }

  @Nested
  @DisplayName("the WPILib fact the whole argument rests on")
  final class MotorCurve {

    @Test
    @DisplayName("an n-motor DCMotor keeps Kt and divides R by n")
    void ktIsUnchangedAndResistanceScales() {
      DCMotor one = DCMotor.getKrakenX60Foc(1);
      DCMotor two = DCMotor.getKrakenX60Foc(2);
      DCMotor four = DCMotor.getKrakenX60Foc(4);

      assertEquals(one.KtNMPerAmp, two.KtNMPerAmp, 0.0, "torque constant is per-amp, so it is n-free");
      assertEquals(one.KtNMPerAmp, four.KtNMPerAmp, 0.0);
      assertEquals(one.rOhms / 2.0, two.rOhms, 1e-15, "two motors in parallel is half the resistance");
      assertEquals(one.rOhms / 4.0, four.rOhms, 1e-15);
      assertEquals(one.KvRadPerSecPerVolt, two.KvRadPerSecPerVolt, 0.0);

      // Therefore Kt/R — the term the prior divides by — is n times larger for n motors.
      assertEquals(2.0 * (one.KtNMPerAmp / one.rOhms), two.KtNMPerAmp / two.rOhms, 1e-9);
    }
  }

  @Nested
  @DisplayName("the corrected two-motor prior")
  final class TwoMotorElevator {

    private final DCMotor m_two = DCMotor.getKrakenX60Foc(2);

    private final PlantPrior m_prior =
        PlantPrior.elevator(m_two, kReduction, kMassKg, kRadiusMetres);

    @Test
    @DisplayName("the prior equals tau * R / (G * Kt) with no second division by the motor count")
    void theCorrectValue() {
      double expected = byHand(m_two, kMassKg, kRadiusMetres, kReduction.rotorPerOutput());

      assertEquals(expected, m_prior.gravityVoltsPrior(), 1e-12);

      // Pinned literal, so a change to the formula or to WPILib's Kraken curve shows up here.
      assertEquals(0.105026, m_prior.gravityVoltsPrior(), 5e-7);
    }

    @Test
    @DisplayName("the same mechanism on one motor needs exactly twice the voltage")
    void oneMotorIsExactlyDoubleTwoMotors() {
      PlantPrior single =
          PlantPrior.elevator(DCMotor.getKrakenX60Foc(1), kReduction, kMassKg, kRadiusMetres);

      assertEquals(
          2.0 * m_prior.gravityVoltsPrior(),
          single.gravityVoltsPrior(),
          1e-12,
          "half the motors, twice the volts — that relationship is the whole content of the "
              + "motor-count argument, and it only holds if the prior does NOT divide by n itself");
    }

    @Test
    @DisplayName("the reintroduced /motorCount bug puts the true value outside the bisection bracket")
    void theBuggyPriorExhaustsTheBracket() {
      double correct = m_prior.gravityVoltsPrior();
      double buggy = correct / 2.0; // what an extra division by the two-motor count would give

      assertNotEquals(buggy, correct, "the two must differ, or this test proves nothing");

      // HoldBisectionStep brackets the search at [0.2x, 1.8x] of the prior.
      double bracketLow = 0.2 * buggy;
      double bracketHigh = 1.8 * buggy;

      assertTrue(
          bracketHigh < correct,
          () ->
              "with the bug the bracket tops out at "
                  + bracketHigh
                  + " V but the mechanism actually needs "
                  + correct
                  + " V, so the search runs out of range and the wizard blames the hardware");
      assertTrue(bracketLow < correct);

      // With the shipped prior the true value is comfortably bracketed.
      assertTrue(0.2 * correct < correct && correct < 1.8 * correct);
    }

    @Test
    @DisplayName("the derived kV, kA and free speed are the motor curve, not a guess")
    void theOtherPriorsAgreeWithTheCurve() {
      double gearing = kReduction.rotorPerOutput();
      double radPerSecPerVolt = m_two.KvRadPerSecPerVolt / gearing;

      assertEquals(1.0 / (radPerSecPerVolt * kRadiusMetres), m_prior.kVprior(), 1e-12);
      assertEquals(
          m_two.rOhms * kMassKg * kRadiusMetres / (gearing * m_two.KtNMPerAmp),
          m_prior.kAprior(),
          1e-15);
      assertEquals(
          m_prior.nominalVolts() * radPerSecPerVolt * kRadiusMetres, m_prior.freeSpeedSi(), 1e-12);
    }
  }

  @Nested
  @DisplayName("the same rule on an arm, and the archetype that has no gravity at all")
  final class OtherShapes {

    @Test
    @DisplayName("an arm's prior uses the centre-of-mass length and scales the same way with n")
    void armPriorUsesComLength() {
      double moi = 0.35;
      double com = 0.42;
      double mass = 4.5;
      DCMotor two = DCMotor.getNEO(2);
      Reduction reduction = Reduction.of(80.0);

      PlantPrior arm = PlantPrior.arm(two, reduction, moi, com, mass);
      PlantPrior armSingle =
          PlantPrior.arm(DCMotor.getNEO(1), reduction, moi, com, mass);

      assertEquals(byHand(two, mass, com, 80.0), arm.gravityVoltsPrior(), 1e-12);
      assertEquals(2.0 * arm.gravityVoltsPrior(), armSingle.gravityVoltsPrior(), 1e-12);
      assertTrue(arm.gravityVoltsPrior() > 0.0, "the prior is a positive magnitude, never a sign");
    }

    @Test
    @DisplayName("a flywheel has no mass or radius, so it reports no gravity prior")
    void flywheelPriorHasNoGravity() {
      PlantPrior flywheel =
          PlantPrior.flywheel(DCMotor.getKrakenX60Foc(2), Reduction.of(1.0), 0.004);

      assertTrue(
          Double.isNaN(flywheel.gravityVoltsPrior()),
          "NaN, not zero: a flywheel has no gravity term, and zero is a measurement");
      assertTrue(Double.isFinite(flywheel.kVprior()));
      assertTrue(Double.isFinite(flywheel.kAprior()));
    }
  }
}

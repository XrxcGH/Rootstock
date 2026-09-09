package org.rootstock.pure.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link Reduction} — the gearbox type, and the class that makes one whole bug family
 * unrepresentable.
 *
 * <p>No HAL, no natives, no {@code edu.wpi.first} import anywhere in the tier under test (ArchUnit
 * rule 8). This whole class runs on a bare JVM, which is the point of the pure tier existing.
 */
final class ReductionTest {

  private static final double kEps = 1e-9;

  @Nested
  @DisplayName("the sign-cancellation bug class")
  final class NegativeRatiosAreRejected {

    /**
     * The headline property. {@code TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0} is unrepresentable, and
     * with it the "the gear ratio is negative so the signs cancel" reasoning — which is correct
     * exactly until somebody fixes the encoder phase, at which point two wrongs stop making a right
     * and the mechanism drives itself into a hard stop.
     */
    @Test
    void ofRejectsANegativeRatio() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Reduction.of(-9.0));
      assertTrue(e.getMessage().contains("-9.0"), "the message must quote the offending value");
      assertTrue(
          e.getMessage().contains("MAGNITUDE"),
          "the message must say why a ratio cannot be negative");
      assertTrue(
          e.getMessage().contains("MotorGroup.leaderInverted()"),
          "the message must name the fix — where direction actually belongs. Message was:\n"
              + e.getMessage());
    }

    @Test
    void ofRejectsZero() {
      assertThrows(IllegalArgumentException.class, () -> Reduction.of(0.0));
    }

    @Test
    void ofRejectsNonFiniteRatios() {
      assertThrows(IllegalArgumentException.class, () -> Reduction.of(Double.NaN));
      assertThrows(IllegalArgumentException.class, () -> Reduction.of(Double.POSITIVE_INFINITY));
      assertThrows(IllegalArgumentException.class, () -> Reduction.of(Double.NEGATIVE_INFINITY));
    }

    /** A negative stage buried in the middle of a chain is the version that actually ships. */
    @Test
    void ofStagesRejectsANegativeStageAnywhereInTheChain() {
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofStages(3.0, -4.0));
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofStages(-3.0, 4.0));
    }

    @Test
    void thenRejectsANegativeStage() {
      Reduction base = Reduction.of(9.0);
      assertThrows(IllegalArgumentException.class, () -> base.then(-2.0));
      assertEquals(9.0, base.rotorPerOutput(), kEps, "the receiver must be unchanged");
    }

    @Test
    void toothCountsMustBeStrictlyPositive() {
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofTeeth(-58, 10));
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofTeeth(58, -10));
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofTeeth(0, 10));
      assertThrows(IllegalArgumentException.class, () -> Reduction.ofTeeth(58, 0));
    }

    @Test
    void toothCountRejectionNamesTheDisambiguatingOverload() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Reduction.ofTeeth(58, 0));
      assertTrue(
          e.getMessage().contains("Reduction.ofGears"),
          "the message must point at the overload that names both sides. Message was:\n"
              + e.getMessage());
    }

    @Test
    void thenRejectsNonPositiveToothCounts() {
      Reduction base = Reduction.ofTeeth(58, 10);
      assertThrows(IllegalArgumentException.class, () -> base.then(0, 12));
      assertThrows(IllegalArgumentException.class, () -> base.then(42, -12));
    }

    @Test
    void ofStagesRejectsAnEmptyChainAndNamesIdentity() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, Reduction::ofStages);
      assertTrue(
          e.getMessage().contains("Reduction.IDENTITY"),
          "an empty chain means direct drive; the message must say so. Message was:\n"
              + e.getMessage());
    }
  }

  @Nested
  @DisplayName("parameter order — the spec bug that would invert every ratio in the library")
  final class ToothCountOrder {

    /**
     * {@code ofTeeth(drivenTeeth, drivingTeeth)} — big gear (output side) first. An earlier design
     * revision declared the parameters the other way round; a faithful implementation of that
     * signature computes 10/58 and turns the flagship arm into a 65x speed-up.
     */
    @Test
    void ofTeethIsDrivenThenDriving() {
      assertEquals(5.8, Reduction.ofTeeth(58, 10).rotorPerOutput(), kEps);
    }

    /** 9143-2025-A's {@code CORAL_PIVOT_GEAR_RATIO}, reproduced exactly. */
    @Test
    void theThreeStageArmRatioIsReproducedExactly() {
      Reduction arm = Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12);
      assertEquals(65.411, arm.rotorPerOutput(), 1e-3);
    }

    @Test
    void ofGearsNamesBothSidesAndAgreesWithOfTeeth() {
      assertEquals(
          Reduction.ofTeeth(58, 10).rotorPerOutput(),
          Reduction.ofGears(Teeth.of(58), Teeth.of(10)).rotorPerOutput(),
          kEps);
    }
  }

  @Nested
  @DisplayName("construction and chaining")
  final class Construction {

    @Test
    void identityIsDirectDrive() {
      assertEquals(1.0, Reduction.IDENTITY.rotorPerOutput(), kEps);
      assertTrue(Reduction.IDENTITY.stages().isEmpty());
      assertEquals("direct drive = 1.000:1 (rotor per output)", Reduction.IDENTITY.describe());
    }

    @Test
    void ofStagesMultipliesInOrder() {
      assertEquals(12.0, Reduction.ofStages(3.0, 4.0).rotorPerOutput(), kEps);
      assertEquals(3, Reduction.ofStages(3.0, 4.0, 5.0).stages().size());
    }

    @Test
    void thenReturnsANewInstanceAndLeavesTheReceiverAlone() {
      Reduction base = Reduction.of(9.0);
      Reduction extended = base.then(2.0);
      assertEquals(9.0, base.rotorPerOutput(), kEps);
      assertEquals(18.0, extended.rotorPerOutput(), kEps);
      assertNotEquals(base, extended);
    }

    @Test
    void thenAcceptsAWholeGearboxAndConcatenatesItsDerivation() {
      Reduction planetary = Reduction.ofStages(3.0, 3.0);
      Reduction belt = Reduction.ofTeeth(42, 12);
      Reduction combined = planetary.then(belt);
      assertEquals(9.0 * 42.0 / 12.0, combined.rotorPerOutput(), kEps);
      assertEquals(3, combined.stages().size(), "both derivations must survive the join");
    }

    @Test
    void stagesIsUnmodifiable() {
      assertThrows(
          UnsupportedOperationException.class, () -> Reduction.of(9.0).stages().add("nope"));
    }
  }

  @Nested
  @DisplayName("conversions")
  final class Conversions {

    @Test
    void reduceGoesRotorToOutputAndUnreduceComesBack() {
      Reduction gearbox = Reduction.of(9.0);
      assertEquals(1.0, gearbox.reduce(9.0), kEps);
      assertEquals(9.0, gearbox.unreduce(1.0), kEps);
      assertEquals(3.7, gearbox.reduce(gearbox.unreduce(3.7)), kEps);
    }

    @Test
    void outputPerRotorIsTheReciprocalAndRatioIsAnAlias() {
      Reduction gearbox = Reduction.of(4.0);
      assertEquals(0.25, gearbox.outputPerRotor(), kEps);
      assertEquals(gearbox.rotorPerOutput(), gearbox.ratio(), kEps);
    }
  }

  @Nested
  @DisplayName("cross-checking and equality")
  final class EqualityAndCrossCheck {

    @Test
    void approxEqualsComparesAgainstAnExternalSourceOfTruth() {
      Reduction arm = Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12);
      assertTrue(arm.approxEquals(65.411, 0.001), "should agree with TunerConstants to 0.1%");
      assertFalse(arm.approxEquals(6.5411, 0.001), "an order-of-magnitude error must be caught");
    }

    @Test
    void approxEqualsRejectsMeaninglessComparisons() {
      assertFalse(Reduction.of(9.0).approxEquals(0.0, 0.01));
      assertFalse(Reduction.of(9.0).approxEquals(Double.NaN, 0.01));
    }

    /**
     * Equality is on the ratio alone, deliberately: {@code ofStages(3, 4)} and {@code of(12)} are
     * the same gearbox, and a config diff that said otherwise would report a difference that is not
     * there.
     */
    @Test
    void equalityIgnoresTheDerivation() {
      assertEquals(Reduction.of(12.0), Reduction.ofStages(3.0, 4.0));
      assertEquals(Reduction.of(12.0).hashCode(), Reduction.ofStages(3.0, 4.0).hashCode());
    }

    @Test
    void differentRatiosAreNotEqual() {
      assertNotEquals(Reduction.of(12.0), Reduction.of(12.5));
      assertNotEquals(Reduction.of(12.0), "12.0");
    }
  }

  @Nested
  @DisplayName("describe() — the derivation survives so a mismatch is readable")
  final class Describe {

    @Test
    void describePrintsEveryStageAndTheProduct() {
      assertEquals(
          "(58:10) x (58:18) x (42:12) = 65.411:1 (rotor per output)",
          Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12).describe());
    }

    @Test
    void aSingleStageDescribesWithoutTheMultiplicationChain() {
      assertEquals("(58:10) = 5.800:1 (rotor per output)", Reduction.ofTeeth(58, 10).describe());
    }

    @Test
    void toStringWrapsDescribe() {
      Reduction gearbox = Reduction.of(9.0);
      assertEquals("Reduction[" + gearbox.describe() + "]", gearbox.toString());
    }
  }

  @Nested
  @DisplayName("Teeth")
  final class TeethRecord {

    @Test
    void teethCarriesItsCount() {
      assertEquals(58, Teeth.of(58).count());
    }
  }
}

package org.rootstock.units;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Radians;
import static edu.wpi.first.units.Units.RotationsPerSecond;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.pure.units.Reduction;

/**
 * The four-layer unit contract: rotor rotations, output rotations, SI, user units.
 *
 * <pre>
 *   USER UNITS         SI UNITS            OUTPUT ROTATIONS          ROTOR ROTATIONS
 *   m / deg     &lt;--&gt;   m / rad     &lt;--&gt;    double (the seam)  &lt;--&gt;   inside the vendor
 *        ^                  ^                     ^                          ^
 *      Axis           SiDomain.toSi()       MotorIO seam                 Reduction
 * </pre>
 *
 * <p><strong>Two defect classes are pinned here.</strong>
 *
 * <ol>
 *   <li><em>Round-trip loss.</em> Every conversion is a single multiply or divide by one constant,
 *       so {@code toUser(toOutputRotations(x))} must return {@code x} to within floating-point
 *       noise. A library that applied the axis twice (or applied the reduction on the wrong side of
 *       the seam) would still look plausible in a log and would put every setpoint in the wrong
 *       place.
 *   <li><em>The radians/degrees mislabel.</em> A rotary axis reports USER units in <strong>
 *       degrees</strong> and SI in <strong>radians</strong>, and {@code unitLabel()} must say
 *       {@code "deg"}. Getting that backwards is a 57.29578x error — large enough to destroy a
 *       mechanism, small enough that a single number in isolation still looks like an angle.
 * </ol>
 *
 * <p>The reference mechanisms are the two flagship configs of {@code design/01}: the 12:1
 * two-Kraken cascade elevator of §5.4 and the 65.4111:1 arm of §5.5.
 */
final class MechanismUnitsTest {

  private static final double kExact = 1e-12;

  /** §5.4 elevator: 12:1 gearbox, 22-tooth #25 sprocket, 2 cascade stages. */
  private static MechanismUnits elevator() {
    return MechanismUnits.of(
        Reduction.ofStages(3.0, 4.0), LinearAxis.sprocket(Inches.of(0.25), 22, 2));
  }

  /** §5.5 arm: (58:10) x (58:18) x (42:12) = 65.4111:1, level at 0 deg. */
  private static MechanismUnits arm() {
    return MechanismUnits.of(
        Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12), RotaryAxis.arm(Degrees.of(0.0)));
  }

  // ===============================================================================================
  // Layer boundaries round-trip
  // ===============================================================================================

  @Nested
  @DisplayName("the four layers round-trip")
  final class RoundTrips {

    @Test
    @DisplayName("user -> output rotations -> user is the identity on the elevator")
    void userToOutputRotationsRoundTripsLinear() {
      MechanismUnits units = elevator();
      for (double metres : new double[] {0.0, 0.0127, 0.5207, 1.3335, 1.397, -0.25}) {
        assertEquals(
            metres,
            units.toUser(units.toOutputRotations(metres)),
            kExact,
            "round trip lost precision at " + metres + " m");
      }
    }

    @Test
    @DisplayName("user -> output rotations -> user is the identity on the arm")
    void userToOutputRotationsRoundTripsRotary() {
      MechanismUnits units = arm();
      for (double degrees : new double[] {0.0, -15.0, 35.0, 95.0, 105.0, 360.0}) {
        assertEquals(
            degrees,
            units.toUser(units.toOutputRotations(degrees)),
            kExact,
            "round trip lost precision at " + degrees + " deg");
      }
    }

    @Test
    @DisplayName("user -> rotor rotations -> user survives the gearbox in both directions")
    void rotorRoundTripsThroughTheReduction() {
      MechanismUnits units = elevator();
      for (double metres : new double[] {0.0, 0.2032, 0.9525, 1.397}) {
        assertEquals(metres, units.toUserFromRotorRotations(units.toRotorRotations(metres)), kExact);
      }
    }

    @Test
    @DisplayName("SI -> user -> SI is the identity on both domains")
    void siRoundTrips() {
      MechanismUnits linear = elevator();
      assertEquals(1.397, linear.fromSi(linear.toSi(1.397)), kExact);

      MechanismUnits rotary = arm();
      assertEquals(95.0, rotary.fromSi(rotary.toSi(95.0)), 1e-12);
      assertEquals(Math.toRadians(95.0), rotary.toSi(95.0), 1e-15);
    }
  }

  // ===============================================================================================
  // The elevator, with every number recomputed
  // ===============================================================================================

  @Nested
  @DisplayName("the §5.4 elevator")
  final class Elevator {

    @Test
    @DisplayName("one output rotation is 0.279400 m of carriage travel")
    void oneOutputRotationIsTheChainAdvanceTimesStages() {
      assertEquals(0.279400, elevator().toUser(1.0), kExact);
      assertEquals(0.279400, elevator().userPerOutputRotation(), kExact);
    }

    @Test
    @DisplayName("for a linear axis the SI value and the user value are the same number")
    void linearSiIsTheIdentity() {
      MechanismUnits units = elevator();
      assertEquals(SiDomain.LINEAR_METERS, units.siDomain());
      assertEquals("m", units.unitLabel());
      assertEquals("m", units.siLabel());
      assertEquals(units.userPerOutputRotation(), units.siPerOutputRotation(), 0.0);
      assertEquals(1.397, units.toSi(1.397), 0.0);
    }

    @Test
    @DisplayName("55 in of travel is exactly 5.0000 output rotations")
    void softMaxIsFiveOutputRotations() {
      // 55 in = 1.397 m; 1.397 / 0.279400 = 5 exactly.
      assertEquals(5.0, elevator().toOutputRotations(Inches.of(55.0)), 1e-12);
      assertEquals(5.0, elevator().toOutputRotations(1.397), 1e-12);
    }

    @Test
    @DisplayName("one rotor rotation is 0.0232833 m — the axis divided by the 12:1 gearbox")
    void travelPerRotorRotationIsTravelOverTheReduction() {
      // 0.279400 / 12 = 0.02328333...
      assertEquals(0.279400 / 12.0, elevator().userPerRotorRotation(), kExact);
      assertEquals(0.023283333, elevator().userPerRotorRotation(), 1e-9);
      assertEquals(12.0, elevator().rotorPerOutput(), kExact);
    }

    @Test
    @DisplayName("free speed at the carriage is 2.2507 m/s on two FOC Krakens")
    void freeSpeedMatchesTheDesignDerivation() {
      // 5800 rpm (Kraken X60 with FOC) / 60 = 96.6667 rotor rps
      //   / 12:1                            =  8.05556 output rps
      //   x 0.279400 m per output rotation  =  2.25072 m/s
      MechanismUnits units = elevator();
      assertEquals(2.250722, units.freeSpeedUserPerSec(5800.0 / 60.0), 1e-6);
      assertEquals(
          2.250722, units.freeSpeedUserPerSec(RotationsPerSecond.of(5800.0 / 60.0)), 1e-6);
      // Sanity on the direction of the gearbox: the OUTPUT is slower than the rotor.
      assertTrue(units.freeSpeedUserPerSec(96.6667) < 96.6667 * units.userPerOutputRotation());
    }

    @Test
    @DisplayName("velocity, acceleration and jerk all use the same one constant")
    void derivativesUseTheSameConstant() {
      MechanismUnits units = elevator();
      double perRot = units.userPerOutputRotation();
      assertEquals(1.6 / perRot, units.toOutputRps(1.6), kExact);
      assertEquals(6.0 / perRot, units.toOutputRps2(6.0), kExact);
      assertEquals(40.0 / perRot, units.toOutputRps3(40.0), kExact);
      assertEquals(1.6, units.toUserPerSec(units.toOutputRps(1.6)), kExact);
      assertEquals(6.0, units.toUserPerSec2(units.toOutputRps2(6.0)), kExact);
      assertEquals(40.0, units.toUserPerSec3(units.toOutputRps3(40.0)), kExact);
    }

    @Test
    @DisplayName("a Distance converts; an Angle is refused with a message naming both fixes")
    void typedOverloadsGuardTheDomain() {
      MechanismUnits units = elevator();
      assertEquals(5.0, units.toOutputRotations(Inches.of(55.0)), 1e-12);

      Distance back = units.toDistance(5.0);
      assertEquals(1.397, back.in(Meters), kExact);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> units.toOutputRotations(Degrees.of(35.0)));
      assertTrue(thrown.getMessage().contains("LINEAR"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("Fix:"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("Inches.of(20.5)"), thrown.getMessage());

      assertThrows(IllegalArgumentException.class, () -> units.toAngle(1.0));
    }

    @Test
    @DisplayName("a linear axis has no horizontal reference, so kG is unconditional")
    void horizontalReferenceIsZeroOnALinearAxis() {
      assertEquals(0.0, elevator().horizontalReferenceSi(), 0.0);
    }
  }

  // ===============================================================================================
  // The arm — where the radians mislabel would live
  // ===============================================================================================

  @Nested
  @DisplayName("the §5.5 arm reports DEGREES, not radians")
  final class RotaryLabelling {

    @Test
    @DisplayName("one output rotation is 360 user units and 2*pi SI units")
    void oneOutputRotationIsThreeSixtyDegrees() {
      MechanismUnits units = arm();
      assertEquals(360.0, units.toUser(1.0), 0.0);
      assertEquals(360.0, units.userPerOutputRotation(), 0.0);
      assertEquals(2.0 * Math.PI, units.siPerOutputRotation(), 0.0);
      // The mislabel: if userPerOutputRotation() ever returned 2*pi, every gain, soft limit and
      // setpoint on every rotary mechanism would be off by 57.29578x.
      assertNotEquals(2.0 * Math.PI, units.userPerOutputRotation());
    }

    @Test
    @DisplayName("unitLabel() is \"deg\" and siLabel() is \"rad\" — never the same string")
    void labelsDistinguishUserFromSi() {
      MechanismUnits units = arm();
      assertEquals("deg", units.unitLabel());
      assertEquals("rad", units.siLabel());
      assertNotEquals(units.unitLabel(), units.siLabel());
      assertEquals(SiDomain.ROTATIONAL_RADIANS, units.siDomain());
    }

    @Test
    @DisplayName("half an output rotation is 180 deg = pi rad, and the two do not get swapped")
    void halfARotationIsOneEightyDegrees() {
      MechanismUnits units = arm();
      assertEquals(180.0, units.toUser(0.5), 1e-13);
      assertEquals(Math.PI, units.toSi(units.toUser(0.5)), 1e-15);
      // 180 deg is NOT pi user units.
      assertNotEquals(Math.PI, units.toUser(0.5), 1e-6);
    }

    @Test
    @DisplayName("the gearbox reduces DEGREES per rotor rotation, 360 / 65.4111 = 5.503652")
    void degreesPerRotorRotation() {
      // (58 x 58 x 42) / (10 x 18 x 12) = 141288 / 2160 = 65.4111111...
      // 360 deg / 65.4111111 = 777600 / 141288 = 5.5036521 deg per rotor rotation
      MechanismUnits units = arm();
      assertEquals(141288.0 / 2160.0, units.rotorPerOutput(), 1e-9);
      assertEquals(65.4111111, units.rotorPerOutput(), 1e-7);
      assertEquals(777600.0 / 141288.0, units.userPerRotorRotation(), 1e-12);
      assertEquals(5.5036521, units.userPerRotorRotation(), 1e-7);
    }

    @Test
    @DisplayName("free speed is 88.670 output rpm = 9.2856 rad/s, as design/01 §5.5 derives")
    void armFreeSpeedMatchesTheDesignDerivation() {
      // 5800 rotor rpm / 65.4111111 = 88.66995 output rpm
      //                             =  1.4778325 rot/s
      //                             = 532.0197 deg/s
      //                             =   9.2854955 rad/s
      //
      // NOTE: design/01 §5.5 prints 9.28558 rad/s. That is a rounding artefact of its own
      // intermediate — it rounds 1.4778325 rot/s to 1.47784 and then multiplies by 2*pi. The
      // exact value is 9.2854955, and the conclusion the design draws from it (kV ~ 1.29,
      // rounded down to 1.25 for kS headroom) is unaffected.
      MechanismUnits units = arm();
      double degPerSec = units.freeSpeedUserPerSec(5800.0 / 60.0);
      assertEquals(532.0197, degPerSec, 1e-4);
      assertEquals(9.2854955, Math.toRadians(degPerSec), 1e-6);
      assertEquals(88.66995, degPerSec / 360.0 * 60.0, 1e-5);
      // and therefore kV at a full 12 V bus is 12 / 9.2854955 = 1.29234 V/(rad/s).
      assertEquals(1.29234, 12.0 / Math.toRadians(degPerSec), 1e-5);
    }

    @Test
    @DisplayName("an Angle converts; a Distance is refused with a message naming both fixes")
    void typedOverloadsGuardTheDomain() {
      MechanismUnits units = arm();
      assertEquals(0.25, units.toOutputRotations(Degrees.of(90.0)), 1e-15);

      Angle back = units.toAngle(0.25);
      assertEquals(90.0, back.in(Degrees), 1e-12);
      assertEquals(Math.PI / 2.0, back.in(Radians), 1e-15);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> units.toOutputRotations(Inches.of(20.5)));
      assertTrue(thrown.getMessage().contains("ROTARY"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("deg"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("Degrees.of(35)"), thrown.getMessage());

      assertThrows(IllegalArgumentException.class, () -> units.toDistance(1.0));
    }

    @Test
    @DisplayName("horizontalReferenceSi() converts the user-unit reference into radians")
    void horizontalReferenceIsConvertedToSi() {
      MechanismUnits level = arm();
      assertEquals(0.0, level.horizontalReferenceSi(), 0.0);

      MechanismUnits offset =
          MechanismUnits.of(Reduction.of(65.4111), RotaryAxis.arm(Degrees.of(35.0)));
      assertEquals(Math.toRadians(35.0), offset.horizontalReferenceSi(), 1e-15);
      assertEquals(0.610865, offset.horizontalReferenceSi(), 1e-6);
      // 35 deg is not 35 rad. A missing conversion here misplaces kG by two full turns.
      assertNotEquals(35.0, offset.horizontalReferenceSi(), 1e-6);
    }
  }

  // ===============================================================================================
  // Construction is total: MechanismUnits NEVER throws
  // ===============================================================================================

  @Nested
  @DisplayName("construction never throws")
  final class TotalConstruction {

    @Test
    @DisplayName("a null reduction is reported, not thrown, and falls back to IDENTITY")
    void nullReductionIsCollected() {
      MechanismUnits units = new MechanismUnits(null, LinearAxis.drum(Inches.of(0.75), 1));
      assertEquals(Reduction.IDENTITY, units.reduction());
      List<String> problems = units.problems();
      assertEquals(1, problems.size(), problems.toString());
      assertTrue(problems.get(0).contains("no Reduction"), problems.get(0));
      assertTrue(problems.get(0).contains("Reduction.ofStages(3.0, 4.0)"), problems.get(0));
      assertTrue(problems.get(0).contains("Reduction.IDENTITY"), problems.get(0));
    }

    @Test
    @DisplayName("a null axis is reported, and the substitute axis is reported too")
    void nullAxisIsCollected() {
      MechanismUnits units = new MechanismUnits(Reduction.of(12.0), null);
      List<String> problems = units.problems();
      assertTrue(problems.size() >= 2, "the null axis and its zero travel: " + problems);
      assertTrue(problems.get(0).contains("no Axis"), problems.get(0));
      assertTrue(problems.get(0).contains("LinearAxis.sprocket"), problems.get(0));
      assertTrue(problems.get(0).contains("RotaryAxis.arm"), problems.get(0));
      assertTrue(
          problems.stream().anyMatch(p -> p.contains("Travel per output rotation")),
          problems.toString());
    }

    @Test
    @DisplayName("a zero-travel axis is flagged, because every conversion divides by it")
    void zeroTravelIsFlagged() {
      MechanismUnits units = MechanismUnits.of(Reduction.of(12.0), LinearAxis.drum(Meters.of(0), 1));
      assertTrue(
          units.problems().stream().anyMatch(p -> p.contains("infinity or NaN")),
          units.problems().toString());
    }

    @Test
    @DisplayName("a well-formed mechanism reports nothing")
    void goodMechanismsAreSilent() {
      assertEquals(List.of(), elevator().problems());
      assertEquals(List.of(), arm().problems());
    }
  }

  // ===============================================================================================
  // Value semantics and the derivation dump
  // ===============================================================================================

  @Nested
  @DisplayName("value semantics and describe()")
  final class ValueSemantics {

    @Test
    @DisplayName("two mechanisms with the same gearbox and geometry are equal")
    void equalityIsByGearboxAndGeometry() {
      assertEquals(elevator(), elevator());
      assertEquals(elevator().hashCode(), elevator().hashCode());
      assertNotEquals(elevator(), arm());
      assertNotEquals(
          elevator(),
          MechanismUnits.of(Reduction.of(9.0), LinearAxis.sprocket(Inches.of(0.25), 22, 2)));
    }

    @Test
    @DisplayName("describe() shows the gearing, both radii and the chordal-action caveat")
    void describePrintsTheDerivation() {
      String text = elevator().describe("Elevator");
      assertTrue(text.startsWith("Elevator geometry"), text);
      assertTrue(text.contains("(3.000) x (4.000) = 12.000:1"), text);
      assertTrue(text.contains("#25 chain, 0.250 in pitch x 22 teeth"), text);
      assertTrue(text.contains("22 x 0.250 in = 5.5000 in per drum rot"), text);
      assertTrue(text.contains("0.279400 m"), text);
      assertTrue(text.contains("0.0444679 m"), text);
      assertTrue(text.contains("NOT the bare drum radius"), text);
      assertTrue(text.contains("CHAIN ADVANCE"), text);
      assertTrue(text.contains("volts-per-meter"), text);
      assertTrue(text.contains("CONSTANT"), text);
    }

    @Test
    @DisplayName("a rotary describe() states the cosine reference and the Phoenix sign rule")
    void describePrintsTheRotaryFrame() {
      String text =
          MechanismUnits.of(Reduction.of(65.4111), RotaryAxis.arm(Degrees.of(0.0)))
              .describe("Arm");
      assertTrue(text.contains("1 output rotation = 360.000 deg"), text);
      assertTrue(text.contains("horizontal at"), text);
      assertTrue(text.contains("GravityArmPositionOffset is this value NEGATED"), text);
      assertTrue(text.contains("COSINE"), text);
      assertTrue(text.contains("volts-per-radian"), text);
    }

    @Test
    @DisplayName("toString() carries the gearing and the travel per rotation")
    void toStringIsSelfDescribing() {
      String text = elevator().toString();
      assertTrue(text.contains("12.000:1"), text);
      assertTrue(text.contains("0.279400 m per output rot"), text);
    }
  }
}

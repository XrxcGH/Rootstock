package org.pumpkinlib.units;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Millimeters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.GravityMode;

/**
 * The axis derivation — the arithmetic that turns a part number into metres of travel.
 *
 * <p><strong>Why this file exists.</strong> Revision 1 of the design derived a sprocket's kinematic
 * radius by dividing the chain advance by {@code pi} instead of {@code 2*pi}, which makes every
 * soft limit, every setpoint, every gain and the whole simulated plant wrong by a factor of two —
 * silently, because nothing in a robot program cross-checks its own geometry. The numbers asserted
 * below are recomputed from first principles in the comment above each one, so this test fails if
 * the derivation is ever "simplified" back to the broken form.
 *
 * <p><strong>The reference part.</strong> The flagship elevator of {@code design/01} §5.4 is a
 * 22-tooth #25 sprocket on a two-stage cascade. Every quantity for it, derived by hand:
 *
 * <pre>
 *   #25 chain pitch      0.250 in              = 0.250 x 0.0254        = 0.006350 m   (exact)
 *   chain advance / rot  22 teeth x 0.006350 m                         = 0.139700 m
 *   kinematic radius     0.139700 / (2 * pi)                           = 0.02223395 m
 *   travel / output rot  0.139700 x 2 stages                           = 0.279400 m
 *   effective radius     0.02223395 x 2 stages  = 0.279400 / (2 * pi)  = 0.04446789 m
 * </pre>
 *
 * <p>One link of chain engages one tooth, so the chain advances {@code teeth * pitch} per rotation.
 * That is the number PumpkinLib uses. It is deliberately <em>not</em> the pitch circumference
 * {@code pi * pitch / sin(pi/teeth)}, which is 0.34 % larger for this part because the chain rides
 * a polygon rather than a circle.
 */
final class AxisTest {

  /**
   * Tolerance for a quantity that should be exact but travels through a divide by {@code 2*pi} and
   * a multiply back by it. The observed round-trip error for the reference part is below 5e-13.
   */
  private static final double kExact = 1e-12;

  /** The reference part: 22-tooth #25 sprocket, two cascade stages. */
  private static LinearAxis referenceElevator() {
    return LinearAxis.sprocket(Inches.of(0.25), 22, 2);
  }

  // ===============================================================================================
  // The sprocket derivation — the numbers, asserted
  // ===============================================================================================

  @Nested
  @DisplayName("the 22-tooth #25 sprocket on a 2-stage cascade")
  final class ReferenceSprocket {

    @Test
    @DisplayName("advances 0.279400 m per output rotation, exactly")
    void travelPerOutputRotationIsExactlyPointTwoSevenNineFour() {
      // 22 x 0.250 in = 5.500 in = 0.139700 m of chain per drum rotation.
      // x 2 cascade stages = 0.279400 m of carriage travel per drum rotation.
      assertEquals(0.279400, referenceElevator().userPerOutputRotation(), kExact);
    }

    @Test
    @DisplayName("prints as 0.279400 m, so describe() and the design table agree")
    void travelFormatsToSixPlacesAsTheDesignStates() {
      assertEquals(
          "0.279400",
          String.format(Locale.ROOT, "%.6f", referenceElevator().userPerOutputRotation()));
    }

    @Test
    @DisplayName("has an effective radius of 0.0444679 m, which is travel / 2pi")
    void effectiveRadiusIsTravelOverTwoPi() {
      double effective = referenceElevator().effectiveRadius().in(Meters);
      // 0.279400 / (2 * pi) = 0.0444678911...
      assertEquals(0.279400 / (2.0 * Math.PI), effective, kExact);
      assertEquals(0.0444678911, effective, 1e-9);
      assertEquals("0.0444679", String.format(Locale.ROOT, "%.7f", effective));
    }

    @Test
    @DisplayName("has a bare drum radius of 0.0222339 m — half the effective radius")
    void bareDrumRadiusIsTheKinematicRadiusOfOneStage() {
      LinearAxis axis = referenceElevator();
      // 0.139700 / (2 * pi) = 0.0222339455...
      assertEquals(0.0222339455, axis.drumRadius().in(Meters), 1e-9);
      // "effective" means "including the cascade". That distinction is the whole point of the
      // record: ElevatorSim and the kG derivation take the effective radius, never the bare one.
      assertEquals(
          2.0 * axis.drumRadius().in(Meters), axis.effectiveRadius().in(Meters), kExact);
    }

    @Test
    @DisplayName("is NOT the revision-1 result of dividing by pi instead of 2pi")
    void rejectsTheRevisionOneDivideByPiBug() {
      double travel = referenceElevator().userPerOutputRotation();
      // Revision 1 computed radius = advance / pi, which doubles every derived quantity.
      double ifDividedByPiInstead = 2.0 * 0.139700 * 2.0; // = 0.558800 m
      assertNotEquals(ifDividedByPiInstead, travel, 1e-6);
      // ... and it is not the mirror-image mistake of dividing the travel by pi either.
      assertNotEquals(0.279400 / Math.PI, travel, 1e-6);
      assertNotEquals(0.279400 * Math.PI, travel, 1e-6);
    }

    @Test
    @DisplayName("uses the CHAIN ADVANCE, not the geometric pitch circumference")
    void usesChainAdvanceRatherThanPitchCircumference() {
      double pitchInches = 0.25;
      int teeth = 22;
      // The geometric pitch diameter of a sprocket is pitch / sin(pi/teeth) = 1.7566685 in, whose
      // circumference is 5.5187368 in — 0.341 % more than the 5.5000 in of chain that actually
      // passes. A chain rides a polygon; PumpkinLib measures the polygon.
      double pitchDiameterInches = pitchInches / Math.sin(Math.PI / teeth);
      double pitchCircumferenceInches = Math.PI * pitchDiameterInches;
      double advanceInches = teeth * pitchInches;
      assertEquals(5.5000, advanceInches, 1e-9);
      assertEquals(1.7566685, pitchDiameterInches, 1e-6);
      assertEquals(5.5187368, pitchCircumferenceInches, 1e-6);
      assertEquals(0.3407, (pitchCircumferenceInches / advanceInches - 1.0) * 100.0, 1e-3);
      assertTrue(pitchCircumferenceInches > advanceInches, "the polygon is the smaller number");

      double travelIfCircumferenceWereUsed = Inches.of(pitchCircumferenceInches * 2).in(Meters);
      assertNotEquals(
          travelIfCircumferenceWereUsed, referenceElevator().userPerOutputRotation(), 1e-6);
      assertEquals(
          Inches.of(advanceInches * 2).in(Meters),
          referenceElevator().userPerOutputRotation(),
          kExact);
    }

    @Test
    @DisplayName("scales linearly with the cascade stage count")
    void stagesMultiplyTravelLinearly() {
      double oneStage = LinearAxis.sprocket(Inches.of(0.25), 22, 1).userPerOutputRotation();
      double twoStage = LinearAxis.sprocket(Inches.of(0.25), 22, 2).userPerOutputRotation();
      double threeStage = LinearAxis.sprocket(Inches.of(0.25), 22, 3).userPerOutputRotation();
      assertEquals(0.139700, oneStage, kExact);
      assertEquals(2.0 * oneStage, twoStage, kExact);
      assertEquals(3.0 * oneStage, threeStage, kExact);
    }

    @Test
    @DisplayName("stages is a COUNT of stages, not a count of EXTRA stages")
    void oneStageMeansDirectPull() {
      // The off-by-one this guards: reading `stages` as "how many stages beyond the first".
      assertEquals(
          0.139700, LinearAxis.sprocket(Inches.of(0.25), 22, 1).userPerOutputRotation(), kExact);
    }
  }

  // ===============================================================================================
  // The other rigging forms
  // ===============================================================================================

  @Nested
  @DisplayName("rigging")
  final class RiggingForms {

    @Test
    @DisplayName("a toothed part advances teeth x pitch per rotation")
    void toothedAdvanceIsTeethTimesPitch() {
      Rigging sprocket = Rigging.sprocket(Inches.of(0.25), 22);
      assertTrue(sprocket.isToothed());
      assertEquals(0.139700, sprocket.advancePerRotation().in(Meters), kExact);

      // HTD 5 mm belt, 24 teeth: 24 x 0.005 m = 0.120 m per rotation.
      Rigging pulley = Rigging.pulley(Millimeters.of(5), 24);
      assertTrue(pulley.isToothed());
      assertEquals(0.120, pulley.advancePerRotation().in(Meters), kExact);
    }

    @Test
    @DisplayName("a cable drum has no teeth and no advance — 2*pi*r is exact for it")
    void cableDrumHasNoTeeth() {
      Rigging drum = Rigging.cableDrum();
      assertFalse(drum.isToothed());
      assertEquals(0, drum.teeth());
      assertEquals(0.0, drum.advancePerRotation().in(Meters), 0.0);
      assertTrue(drum.describe().contains("cable drum"));
    }

    @Test
    @DisplayName("the compact constructor erases teeth and pitch on a cable drum")
    void cableDrumNormalisesAwayToothFields() {
      // A drum with a stray tooth count would produce a nonsense advancePerRotation().
      Rigging confused = new Rigging(Rigging.Form.CABLE_DRUM, Inches.of(0.25), 22);
      assertEquals(0, confused.teeth());
      assertEquals(0.0, confused.toothPitch().in(Meters), 0.0);
      assertEquals(0.0, confused.advancePerRotation().in(Meters), 0.0);
    }

    @Test
    @DisplayName("names the chain and belt standards a student reads off the part")
    void describeNamesTheStandard() {
      assertTrue(Rigging.sprocket(Inches.of(0.25), 22).describe().contains("#25 chain"));
      assertTrue(Rigging.sprocket(Inches.of(0.375), 16).describe().contains("#35 chain"));
      assertTrue(Rigging.sprocket(Inches.of(0.5), 16).describe().contains("#40/#41 chain"));
      assertTrue(Rigging.pulley(Millimeters.of(3), 24).describe().contains("GT2 3 mm belt"));
      assertTrue(Rigging.pulley(Millimeters.of(5), 24).describe().contains("HTD 5 mm belt"));
      assertTrue(Rigging.pulley(Millimeters.of(9), 24).describe().contains("HTD 9 mm belt"));
    }

    @Test
    @DisplayName("a bare drum's travel is 2*pi*r*stages")
    void drumTravelIsTwoPiR() {
      LinearAxis drum = LinearAxis.drum(Inches.of(0.75), 1);
      double radiusMetres = 0.75 * 0.0254; // = 0.019050 m
      assertEquals(2.0 * Math.PI * radiusMetres, drum.userPerOutputRotation(), kExact);
      // 2 * pi * 0.019050 m = 0.11969468 m
      assertEquals(0.11969468, drum.userPerOutputRotation(), 1e-8);
    }

    @Test
    @DisplayName("the two-argument LinearAxis constructor means a cable drum")
    void twoArgConstructorDefaultsToCableDrum() {
      LinearAxis axis = new LinearAxis(Inches.of(0.75), 2);
      assertEquals(Rigging.Form.CABLE_DRUM, axis.rigging().form());
      assertEquals(2.0 * Math.PI * 0.75 * 0.0254 * 2, axis.userPerOutputRotation(), kExact);
    }
  }

  // ===============================================================================================
  // Gravity is DERIVED from the axis, never configured beside it
  // ===============================================================================================

  @Nested
  @DisplayName("gravity mode is derived from the geometry")
  final class DerivedGravity {

    @Test
    @DisplayName("a linear axis is always CONSTANT — a carriage fights the same weight everywhere")
    void linearIsAlwaysConstant() {
      assertEquals(GravityMode.CONSTANT, referenceElevator().gravity());
      assertEquals(GravityMode.CONSTANT, LinearAxis.drum(Inches.of(0.75), 1).gravity());
      assertEquals(GravityMode.CONSTANT, LinearAxis.pulley(Millimeters.of(5), 24, 3).gravity());
      // There is no setter. That is the point: you cannot declare an elevator and then give it
      // an arm's gravity model.
    }

    @Test
    @DisplayName("an arm, pivot, wrist or hood is COSINE — the load scales with cos(angle)")
    void jointsAreCosine() {
      assertEquals(GravityMode.COSINE, RotaryAxis.arm(Degrees.of(0)).gravity());
      assertEquals(GravityMode.COSINE, RotaryAxis.pivot(Degrees.of(0)).gravity());
      assertEquals(GravityMode.COSINE, RotaryAxis.wrist(Degrees.of(0)).gravity());
      assertEquals(GravityMode.COSINE, RotaryAxis.hood(Degrees.of(0)).gravity());
    }

    @Test
    @DisplayName("a turret or roller is NONE — nothing pulls on a horizontal axis")
    void turretsAndRollersHaveNoGravityTerm() {
      assertEquals(GravityMode.NONE, RotaryAxis.turret(true).gravity());
      assertEquals(GravityMode.NONE, RotaryAxis.turret(false).gravity());
      assertEquals(GravityMode.NONE, RotaryAxis.roller().gravity());
    }

    @Test
    @DisplayName("a rotary axis declared CONSTANT is reported as a problem, with the fix")
    void constantGravityOnARotaryAxisIsAProblem() {
      List<String> problems =
          new RotaryAxis(GravityMode.CONSTANT, Degrees.of(0), false).problems();
      assertEquals(1, problems.size(), "exactly the one complaint: " + problems);
      String message = problems.get(0);
      assertTrue(message.contains("CONSTANT"), message);
      assertTrue(message.contains("cosine"), message);
      assertTrue(message.contains("Fix:"), message);
      assertTrue(message.contains("RotaryAxis.arm"), message);
      assertTrue(message.contains("LinearAxis"), message);
    }
  }

  // ===============================================================================================
  // Continuity and the horizontal reference
  // ===============================================================================================

  @Nested
  @DisplayName("continuity and the horizontal reference")
  final class Frames {

    @Test
    @DisplayName("a linear axis is never continuous and has no horizontal reference")
    void linearIsNeverContinuous() {
      assertFalse(referenceElevator().isContinuous());
      assertEquals(0.0, referenceElevator().horizontalReference(), 0.0);
    }

    @Test
    @DisplayName("continuity is a per-turret decision, and only turrets and rollers get it")
    void continuityIsExplicitOnRotaryAxes() {
      assertTrue(RotaryAxis.turret(true).isContinuous());
      assertFalse(RotaryAxis.turret(false).isContinuous());
      assertTrue(RotaryAxis.roller().isContinuous());
      // An arm has finite travel; wrapping would send the short way through the end stop.
      assertFalse(RotaryAxis.arm(Degrees.of(0)).isContinuous());
    }

    @Test
    @DisplayName("horizontalReference() is reported in USER units, which are DEGREES")
    void horizontalReferenceIsInDegrees() {
      // A radians answer here would be a 57.3x error in the Phoenix gravity offset.
      assertEquals(35.0, RotaryAxis.arm(Degrees.of(35)).horizontalReference(), 1e-12);
      assertEquals(-15.0, RotaryAxis.arm(Degrees.of(-15)).horizontalReference(), 1e-12);
    }
  }

  // ===============================================================================================
  // The collected-error contract (§5.6): problems() NEVER throws
  // ===============================================================================================

  @Nested
  @DisplayName("problems() collects rather than throws")
  final class Problems {

    @Test
    @DisplayName("a well-formed axis reports nothing")
    void aGoodAxisIsSilent() {
      assertEquals(List.of(), referenceElevator().problems());
      assertEquals(List.of(), RotaryAxis.arm(Degrees.of(0)).problems());
      assertEquals(List.of(), LinearAxis.drum(Inches.of(0.75), 1).problems());
    }

    @Test
    @DisplayName("a zero or negative drum radius names the value and both fixes")
    void badRadiusNamesTheValueAndTheFix() {
      List<String> problems = LinearAxis.drum(Meters.of(0.0), 1).problems();
      assertEquals(1, problems.size(), problems.toString());
      String message = problems.get(0);
      assertTrue(message.contains("drumRadius"), message);
      assertTrue(message.contains("0.0"), message);
      assertTrue(message.contains("strictly positive"), message);
      assertTrue(message.contains("LinearAxis.sprocket(Inches.of(0.25), 22, 2)"), message);
      assertTrue(message.contains("LinearAxis.drum"), message);
    }

    @Test
    @DisplayName("a stage count below one names the value and explains the off-by-one")
    void badStageCountExplainsTheOffByOne() {
      List<String> problems = LinearAxis.drum(Inches.of(0.75), 0).problems();
      assertEquals(1, problems.size(), problems.toString());
      String message = problems.get(0);
      assertTrue(message.contains("stages was 0"), message);
      assertTrue(message.contains("at least 1"), message);
      assertTrue(message.contains("not a count of EXTRA stages"), message);
    }

    @Test
    @DisplayName("several defects at once are collected, not short-circuited")
    void multipleDefectsAreCollectedTogether() {
      // Zero radius AND zero stages AND a toothless sprocket: three independent complaints.
      LinearAxis broken = new LinearAxis(Meters.of(0.0), 0, Rigging.sprocket(Meters.of(0.0), 0));
      List<String> problems = broken.problems();
      assertEquals(4, problems.size(), "radius, stages, tooth count, pitch: " + problems);
      assertTrue(problems.stream().anyMatch(p -> p.contains("drumRadius")), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("stages")), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("tooth count")), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("pitch")), problems.toString());
    }

    @Test
    @DisplayName("a NaN horizontal reference is reported rather than propagated into kG")
    void nonFiniteHorizontalIsReported() {
      List<String> problems = RotaryAxis.arm(Degrees.of(Double.NaN)).problems();
      assertEquals(1, problems.size(), problems.toString());
      assertTrue(problems.get(0).contains("horizontalAt"), problems.get(0));
      assertTrue(problems.get(0).contains("finite"), problems.get(0));
    }

    @Test
    @DisplayName("the returned list is immutable, so a caller cannot edit the diagnosis")
    void problemsListIsImmutable() {
      List<String> problems = LinearAxis.drum(Meters.of(0.0), 1).problems();
      org.junit.jupiter.api.Assertions.assertThrows(
          UnsupportedOperationException.class, () -> problems.add("not a real problem"));
    }
  }

  // ===============================================================================================
  // The two implementations, and only two, forever
  // ===============================================================================================

  @Nested
  @DisplayName("the sealed hierarchy")
  final class Sealing {

    @Test
    @DisplayName("Axis permits exactly LinearAxis and RotaryAxis")
    void axisIsSealedToTwoImplementations() {
      assertTrue(Axis.class.isSealed());
      List<String> permitted =
          java.util.Arrays.stream(Axis.class.getPermittedSubclasses())
              .map(Class::getSimpleName)
              .sorted()
              .toList();
      assertEquals(List.of("LinearAxis", "RotaryAxis"), permitted);
    }

    @Test
    @DisplayName("SI domain and labels are fixed per implementation")
    void labelsAreFixedPerImplementation() {
      Axis linear = referenceElevator();
      assertEquals(SiDomain.LINEAR_METERS, linear.siDomain());
      assertEquals("m", linear.unitLabel());
      assertEquals("m", linear.siLabel());
      assertEquals(linear.userPerOutputRotation(), linear.siPerOutputRotation(), 0.0);

      Axis rotary = RotaryAxis.arm(Degrees.of(0));
      assertEquals(SiDomain.ROTATIONAL_RADIANS, rotary.siDomain());
      assertEquals("deg", rotary.unitLabel());
      assertEquals("rad", rotary.siLabel());
      assertEquals(360.0, rotary.userPerOutputRotation(), 0.0);
      assertEquals(2.0 * Math.PI, rotary.siPerOutputRotation(), 0.0);
    }

    @Test
    @DisplayName("SiDomain converts and labels consistently in both directions")
    void siDomainRoundTrips() {
      assertEquals(1.234, SiDomain.LINEAR_METERS.toSi(1.234), 0.0);
      assertEquals(1.234, SiDomain.LINEAR_METERS.fromSi(1.234), 0.0);
      assertEquals("m", SiDomain.LINEAR_METERS.label());
      assertEquals("m", SiDomain.LINEAR_METERS.userLabel());

      assertEquals(Math.PI, SiDomain.ROTATIONAL_RADIANS.toSi(180.0), 1e-15);
      assertEquals(180.0, SiDomain.ROTATIONAL_RADIANS.fromSi(Math.PI), 1e-13);
      assertEquals("rad", SiDomain.ROTATIONAL_RADIANS.label());
      // The user label is DEGREES while the SI label is radians. Conflating the two is the
      // 57.29578x error this whole package exists to make impossible.
      assertEquals("deg", SiDomain.ROTATIONAL_RADIANS.userLabel());
      assertNotEquals(
          SiDomain.ROTATIONAL_RADIANS.label(), SiDomain.ROTATIONAL_RADIANS.userLabel());
    }
  }
}

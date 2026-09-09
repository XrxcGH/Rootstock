package org.rootstock.mechanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two failures {@link ContinuousUnwrap} exists to prevent, asserted as behaviour rather than as
 * coverage: <b>a field-locked turret must take the short path</b>, and <b>it must never take a short
 * path that unwinds the cable harness past its stop</b>.
 *
 * <p><strong>Why this test is native-free and the rest of the mechanism suite is not.</strong> Every
 * conversion in {@code ContinuousUnwrap} is a pure static function of its arguments and the stateful
 * form holds exactly one {@code double}. Nothing here constructs a {@code Mechanism}, and that is
 * deliberate: a {@code Mechanism} constructor builds a {@code RootstockAlert}, a {@code RootstockAlert}
 * eagerly constructs an {@code edu.wpi.first.wpilibj.Alert}, and on a JVM without WPILib's JNI
 * natives that path calls {@code System.exit(1)} rather than throwing. So the turret maths is
 * asserted here, in the default {@code ./gradlew test}, and the mechanism that consumes it is
 * asserted in {@code @Tag("hal")} classes.
 *
 * <p><strong>Every number below is derived, not copied.</strong> The travel range used throughout is
 * a 540&deg; turret, {@code [-270, +270]}, because that is the smallest range on which the
 * full-turn alternative branch can fire at all ({@code appliesTo} needs strictly more than one
 * turn). Each expected value is written next to the arithmetic that produces it.
 */
final class ContinuousUnwrapTest {

  /** A 540 degree cable-wrap turret: the reverse stop, in degrees. */
  private static final double kTurretMinDeg = -270.0;

  /** A 540 degree cable-wrap turret: the forward stop, in degrees. */
  private static final double kTurretMaxDeg = 270.0;

  @Nested
  @DisplayName("shortestDeltaDeg — the +/-180 discontinuity")
  final class ShortestDelta {

    /**
     * The headline case from the class javadoc: 179 from -179 is a 2 degree move, not a 358 degree
     * one. inputModulus(179 - (-179), -180, 180) = inputModulus(358, -180, 180) = 358 - 360 = -2.
     */
    @Test
    void crossesTheWrapRatherThanGoingTheLongWayRound() {
      assertEquals(-2.0, ContinuousUnwrap.shortestDeltaDeg(-179.0, 179.0), 1e-9);
      assertEquals(2.0, ContinuousUnwrap.shortestDeltaDeg(179.0, -179.0), 1e-9);
    }

    /**
     * Exactly half a turn is the one ambiguous input, and it resolves <b>positive</b> in both
     * directions.
     *
     * <p>This is pinned rather than asserted from the javadoc, which claims the result is half-open
     * on {@code [-180, 180)}. It is not: {@code ContinuousUnwrap.shortestDeltaDeg} delegates to
     * {@code MathX.inputModulus} and thence to WPILib's {@code MathUtil.inputModulus}, whose
     * two-step integer reduction maps an input sitting exactly on either bound onto the
     * <i>maximum</i>. Both {@code +180} and {@code -180} therefore come back as {@code +180}.
     *
     * <p>It is harmless — a half-turn request is a coin flip by definition and both answers reach
     * the same physical heading — but the doc comment overstates the contract, and a test that
     * asserted the doc rather than the code would have been asserting a fiction.
     */
    @Test
    void exactlyHalfATurnResolvesToTheForwardBound() {
      assertEquals(180.0, ContinuousUnwrap.shortestDeltaDeg(0.0, 180.0), 1e-9);
      assertEquals(180.0, ContinuousUnwrap.shortestDeltaDeg(0.0, -180.0), 1e-9);
    }

    /** Multi-turn arguments reduce: 1000 - 10 = 990; 990 - 2*360 = 270; 270 - 360 = -90. */
    @Test
    void reducesArbitraryMagnitudes() {
      assertEquals(-90.0, ContinuousUnwrap.shortestDeltaDeg(10.0, 1000.0), 1e-9);
    }

    /** A NaN argument yields zero rather than propagating NaN into a position goal. */
    @Test
    void nanYieldsNoMotion() {
      assertEquals(0.0, ContinuousUnwrap.shortestDeltaDeg(Double.NaN, 90.0), 0.0);
      assertEquals(0.0, ContinuousUnwrap.shortestDeltaDeg(90.0, Double.NaN), 0.0);
    }
  }

  @Nested
  @DisplayName("unwrap — short path when it fits, the long way round when it does not")
  final class Unwrap {

    /**
     * Sitting at +170 and asked for -170, the turret must move +20 to reach +190 — the same physical
     * heading — and not -340.
     *
     * <p>delta = inputModulus(-170 - 170, -180, 180) = inputModulus(-340, ...) = +20. target = 190,
     * which is inside [-270, 270], so it stands.
     */
    @Test
    void takesTheShortPathThroughTheWrapWhenTravelAllowsIt() {
      double resolved = ContinuousUnwrap.unwrap(-170.0, 170.0, kTurretMinDeg, kTurretMaxDeg);
      assertEquals(190.0, resolved, 1e-9);
      assertEquals(
          20.0,
          Math.abs(resolved - 170.0),
          1e-9,
          "the whole point: a 20 degree move, not the 340 degree one a naive clamp to -170 gives");
    }

    /**
     * The cable-wrap violation. Sitting at +265 and asked for -80, the short path is +15 to +280 —
     * ten degrees past the forward stop. The full-turn alternative, 280 - 360 = -80, is inside the
     * range, so the turret goes the long way (-345 degrees) and reaches the same heading legally.
     */
    @Test
    void refusesAShortPathThatWouldLeaveTheCableWrapRange() {
      double resolved = ContinuousUnwrap.unwrap(-80.0, 265.0, kTurretMinDeg, kTurretMaxDeg);
      assertEquals(-80.0, resolved, 1e-9);
      assertTrue(
          resolved >= kTurretMinDeg && resolved <= kTurretMaxDeg,
          "the alternative must itself be inside the travel range");
      assertEquals(
          -345.0,
          resolved - 265.0,
          1e-9,
          "it deliberately takes the LONG way: the short way ends 10 degrees past the hard stop");
    }

    /** The mirror image at the reverse stop: -265 asked for +80 goes forward 345 degrees. */
    @Test
    void refusesAShortPathThatWouldLeaveTheReverseStop() {
      double resolved = ContinuousUnwrap.unwrap(80.0, -265.0, kTurretMinDeg, kTurretMaxDeg);
      assertEquals(80.0, resolved, 1e-9);
      assertEquals(345.0, resolved - (-265.0), 1e-9);
    }

    /**
     * When the short path is out of range AND the full-turn alternative is also out of range, the
     * short path is clamped. A +/-10 degree wrist asked to point backwards genuinely cannot: 180 is
     * past +10, and 180 - 360 = -180 is past -10, so it clamps at the stop.
     */
    @Test
    void clampsWhenNeitherPathFitsTheTravel() {
      assertEquals(10.0, ContinuousUnwrap.unwrap(180.0, 10.0, -10.0, 10.0), 1e-9);
    }

    /** The very first command, with nothing seeded, is the request clamped — never a turn away. */
    @Test
    void theFirstCommandUsesTheRequestAsItsOwnReference() {
      assertEquals(
          170.0, ContinuousUnwrap.unwrap(170.0, Double.NaN, kTurretMinDeg, kTurretMaxDeg), 1e-9);
      assertEquals(
          -170.0, ContinuousUnwrap.unwrap(-170.0, Double.NaN, kTurretMinDeg, kTurretMaxDeg), 1e-9);
    }

    /** A NaN request holds the last command rather than commanding NaN into a position loop. */
    @Test
    void aNanRequestHoldsTheLastCommand() {
      assertEquals(
          100.0, ContinuousUnwrap.unwrap(Double.NaN, 100.0, kTurretMinDeg, kTurretMaxDeg), 1e-9);
      assertEquals(
          kTurretMinDeg,
          ContinuousUnwrap.unwrap(Double.NaN, Double.NaN, kTurretMinDeg, kTurretMaxDeg),
          1e-9,
          "nothing commanded and nothing requested: the reverse stop is the only defined answer");
    }

    /** Reversed limits are normalised rather than producing an empty range that clamps to NaN. */
    @Test
    void reversedLimitsAreNormalised() {
      assertEquals(
          190.0,
          ContinuousUnwrap.unwrap(-170.0, 170.0, kTurretMaxDeg, kTurretMinDeg),
          1e-9,
          "min and max swapped must give the same answer as the right way round");
    }

    /**
     * The property that matters at the harness: whatever is asked for, the answer is commandable.
     * Swept across five full turns in both directions from three different last-commanded angles.
     */
    @Test
    void everyResolvedAngleIsInsideTheTravelRange() {
      for (double last : new double[] {-269.0, 0.0, 269.0}) {
        for (double request = -1800.0; request <= 1800.0; request += 7.0) {
          double resolved = ContinuousUnwrap.unwrap(request, last, kTurretMinDeg, kTurretMaxDeg);
          assertTrue(
              resolved >= kTurretMinDeg && resolved <= kTurretMaxDeg,
              "unwrap("
                  + request
                  + ", last="
                  + last
                  + ") returned "
                  + resolved
                  + ", which is outside the cable-wrap range ["
                  + kTurretMinDeg
                  + ", "
                  + kTurretMaxDeg
                  + "]");
        }
      }
    }
  }

  @Nested
  @DisplayName("appliesTo — the gate that keeps single-turn axes cheap")
  final class AppliesTo {

    /** A 540 degree turret needs unwrapping; the design's 120 degree arm does not. */
    @Test
    void onlyMultiTurnAxesNeedIt() {
      assertTrue(ContinuousUnwrap.appliesTo(kTurretMinDeg, kTurretMaxDeg), "540 deg of travel");
      assertFalse(
          ContinuousUnwrap.appliesTo(-15.0, 105.0),
          "the design's arm has 120 deg of travel: one commandable angle per heading, so a plain "
              + "clamp is correct and cheaper");
    }

    /** Exactly one turn is NOT multi-turn: the comparison is strict, as the javadoc states. */
    @Test
    void exactlyOneTurnIsNotMultiTurn() {
      assertFalse(ContinuousUnwrap.appliesTo(-180.0, 180.0));
      assertTrue(ContinuousUnwrap.appliesTo(-180.0, 180.001));
    }

    /** A NaN limit answers false rather than throwing out of a config field initialiser. */
    @Test
    void nanLimitsAnswerFalse() {
      assertFalse(ContinuousUnwrap.appliesTo(Double.NaN, 270.0));
      assertFalse(ContinuousUnwrap.appliesTo(-270.0, Double.NaN));
    }
  }

  @Nested
  @DisplayName("field lock — the chassis frame and the joint frame")
  final class FieldLock {

    /** The conversion is a subtraction: joint = field - chassis. */
    @Test
    void convertsAFieldHeadingIntoTheJointFrame() {
      assertEquals(15.0, ContinuousUnwrap.fieldLockedGoalDeg(45.0, 30.0), 1e-9);
      assertEquals(-90.0, ContinuousUnwrap.fieldLockedGoalDeg(0.0, 90.0), 1e-9);
    }

    /**
     * The composed case a field-locked turret actually runs: hold field heading 0 while the chassis
     * spins from 100 to -100 degrees.
     *
     * <p>Chassis at +100 gives a joint goal of -100; from a last command of 0 that is a -100 degree
     * move, so the joint lands at -100.
     *
     * <p>Chassis at -100 gives a joint goal of +100. From -100 the two ways round are +200 and
     * -160, so the short path is <b>-160</b> and the commanded angle is -260 — a number 540 degrees
     * of cable wrap can hold and a naive "clamp to +100" would have turned into a 200 degree swing
     * in the wrong direction. That -260 is the whole reason the joint frame is allowed to run past
     * +/-180 at all.
     */
    @Test
    void aFieldLockedTurretTracksTheChassisByTheShortPath() {
      ContinuousUnwrap turret = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      turret.seed(0.0);

      double first = turret.unwrap(ContinuousUnwrap.fieldLockedGoalDeg(0.0, 100.0));
      assertEquals(-100.0, first, 1e-9);

      double second = turret.unwrap(ContinuousUnwrap.fieldLockedGoalDeg(0.0, -100.0));
      assertEquals(-260.0, second, 1e-9);
      assertEquals(-160.0, second - first, 1e-9, "a -160 sweep, not the +200 one");
      assertTrue(second >= kTurretMinDeg, "and it is still inside the cable wrap");
    }

    /**
     * The counter-rotation feedforward negates and converts to degrees per second. pi rad/s
     * counter-clockwise is exactly -180 deg/s at the joint.
     */
    @Test
    void theCounterRotationTermNegatesAndConvertsToDegreesPerSecond() {
      assertEquals(-180.0, ContinuousUnwrap.fieldLockVelocityDegPerSec(Math.PI), 1e-9);
      assertEquals(
          -Math.toDegrees(0.5), ContinuousUnwrap.fieldLockVelocityDegPerSec(0.5), 1e-12);
      assertEquals(
          -28.64788975654116,
          ContinuousUnwrap.fieldLockVelocityDegPerSec(0.5),
          1e-12,
          "0.5 rad/s = 28.6478897565... deg/s, negated");
    }

    /** A dead gyro reports NaN; the counter-rotation term must be zero, not NaN. */
    @Test
    void aNanYawRateContributesNothing() {
      assertEquals(0.0, ContinuousUnwrap.fieldLockVelocityDegPerSec(Double.NaN), 0.0);
    }
  }

  @Nested
  @DisplayName("the stateful form — the one double that makes 'shortest' mean anything")
  final class Stateful {

    /** Each call becomes the reference for the next, which is what chains a sweep together. */
    @Test
    void eachCommandBecomesTheNextReference() {
      ContinuousUnwrap turret = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      turret.seed(0.0);
      assertEquals(170.0, turret.unwrap(170.0), 1e-9);
      assertEquals(170.0, turret.lastCommandedDeg(), 1e-9);
      // From 170, -170 is +20 away.
      assertEquals(190.0, turret.unwrap(-170.0), 1e-9);
      assertEquals(190.0, turret.lastCommandedDeg(), 1e-9);
    }

    /**
     * Seeding after homing is not optional. A tracker that still believes it is at -179 while the
     * mechanism sits at +179 computes "shortest path" from a lie: it commands -181 (a 2 degree move
     * from where it thinks it is) when the mechanism is already there.
     *
     * <p>Both branches are asserted so the failure is legible: with the correct seed the first
     * command is a no-op, with the stale seed it is a 2 degree command to a different number
     * entirely.
     */
    @Test
    void seedingAfterHomingIsWhatStopsTheFirstCommandBeingComputedFromALie() {
      ContinuousUnwrap lying = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      lying.seed(-179.0);
      assertEquals(
          -181.0,
          lying.unwrap(179.0),
          1e-9,
          "-179 + shortestDelta(-179, 179) = -179 + -2 = -181, which 540 degrees of travel allows");

      ContinuousUnwrap seeded = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      seeded.seed(179.0);
      assertEquals(179.0, seeded.unwrap(179.0), 1e-9, "already there: a zero-degree command");
    }

    /** seed() clamps, so a seed from a mis-scaled sensor cannot park the reference out of range. */
    @Test
    void seedIsClampedToTheTravelRange() {
      ContinuousUnwrap turret = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      turret.seed(5000.0);
      assertEquals(kTurretMaxDeg, turret.lastCommandedDeg(), 1e-9);
    }

    /** reset() forgets the reference, so the next request is treated as its own reference. */
    @Test
    void resetMakesTheNextRequestItsOwnReference() {
      ContinuousUnwrap turret = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      turret.seed(265.0);
      turret.reset();
      assertTrue(Double.isNaN(turret.lastCommandedDeg()));
      assertEquals(-80.0, turret.unwrap(-80.0), 1e-9, "no reference: the request, clamped");
    }

    /** The travel accessors are what {@code PositionMechanism} gates the whole feature on. */
    @Test
    void travelAccessorsDescribeTheAxis() {
      ContinuousUnwrap turret = new ContinuousUnwrap(kTurretMinDeg, kTurretMaxDeg);
      assertEquals(-270.0, turret.minDeg(), 0.0);
      assertEquals(270.0, turret.maxDeg(), 0.0);
      assertEquals(540.0, turret.travelDeg(), 1e-9);
      assertTrue(turret.isMultiTurn());
      assertTrue(turret.describe().contains("multi-turn"));

      ContinuousUnwrap wrist = new ContinuousUnwrap(-15.0, 105.0);
      assertEquals(120.0, wrist.travelDeg(), 1e-9);
      assertFalse(wrist.isMultiTurn());
      assertTrue(wrist.describe().contains("single-turn"));
    }
  }
}

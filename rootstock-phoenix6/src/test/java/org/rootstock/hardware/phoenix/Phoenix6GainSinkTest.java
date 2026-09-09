package org.rootstock.hardware.phoenix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.MotionMagicConfigs;
import com.ctre.phoenix6.configs.Slot0Configs;
import com.ctre.phoenix6.signals.GravityTypeValue;
import com.ctre.phoenix6.signals.StaticFeedforwardSignValue;
import edu.wpi.first.units.Units;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.RotaryAxis;

/**
 * The volts-per-SI to Phoenix-slot conversion, checked against numbers computed <b>by hand</b>.
 *
 * <h2>Why this is the highest-value test in the milestone</h2>
 *
 * <p>{@link Gains} are seven doubles in volts per SI unit. Phoenix slot gains are volts per
 * <em>output rotation</em>. Somewhere, exactly once, somebody multiplies by exactly one number. If
 * that number is wrong the loop is off by 6.28 or by 3.6 — both of which oscillate impressively and
 * neither of which looks wrong on the line it was written. Every teaching claim, the tuning wizard,
 * the persisted gain store and the NT schema all rest on this one multiplication being right.
 *
 * <h2>The two mechanisms, derived rather than copied</h2>
 *
 * <p><b>The elevator</b> is 9143-2025-A's: a 22-tooth #25 sprocket on a two-stage cascade.
 *
 * <pre>
 *   chain advance  = 22 teeth x 0.250 in       = 5.500 in per drum rotation
 *   cascade        = x 2 stages                = 11.000 in of carriage travel per output rotation
 *   U              = 11.000 x 0.0254           = 0.279400 m per output rotation
 * </pre>
 *
 * <p><b>The arm</b> is a rotary axis, where {@code U} is the same number for every rotary mechanism
 * ever built: {@code 2*pi} radians per rotation. Its cosine reference is 20 degrees, which is
 * {@code 20/360 = 0.0555556} output rotations — and Phoenix's {@code GravityArmPositionOffset} is
 * that value <b>negated</b>, because the device evaluates {@code kG * cos(position + offset)} while
 * the library means {@code kG * cos(position - horizontalReference)}.
 *
 * <p>Every expected value below is written as the arithmetic that produces it, next to the decimal
 * literal it equals, so the test itself is checkable with a calculator. Copying a number out of the
 * design document would test that two documents agree, not that the code is right.
 *
 * <h2>No hardware</h2>
 *
 * <p>{@code Slot0Configs} and {@code MotionMagicConfigs} are plain configuration objects — verified
 * to construct on a JVM with no Phoenix natives present — and this sink takes its device writes as
 * two function parameters. So the whole conversion is exercised with no CAN bus and no {@code @Tag}.
 */
final class Phoenix6GainSinkTest {

  /** Bit-exact where the arithmetic is exact; loose enough for one rounding of 0.0254. */
  private static final double kEps = 1e-12;

  // ---- the two mechanisms, derived by hand ------------------------------------------------------

  /** 22 teeth x 0.250 in x 2 stages = 11.000 in = 0.279400 m of travel per output rotation. */
  private static final double kElevatorU = 11.0 * 0.0254;

  /** Every rotary axis: one output rotation is 2*pi radians. */
  private static final double kArmU = 2.0 * Math.PI;

  /** 20 degrees of cosine reference, in output rotations: 20 / 360. */
  private static final double kArmHorizontalRot = 20.0 / 360.0;

  /**
   * One concrete elevator gain set, in volts per SI unit. Chosen so that no two products below
   * coincide — a transposed pair would otherwise round-trip by luck.
   */
  private static final Gains kElevatorGains =
      new Gains(
          /* kP */ 80.0, /* kI */ 0.5, /* kD */ 2.0, /* kS */ 0.22, /* kV */ 12.0, /* kA */ 0.6,
          /* kG */ 0.35);

  private static MechanismUnits elevatorUnits() {
    return new MechanismUnits(
        Reduction.of(9.0), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  private static MechanismUnits armUnits() {
    return new MechanismUnits(
        Reduction.ofStages(5.0, 5.0, 4.0),
        RotaryAxis.arm(Units.Degrees.of(20.0)));
  }

  @Nested
  @DisplayName("the one factor")
  final class TheFactor {

    /**
     * {@code U} is what the whole conversion is, so it is pinned first and independently. If this is
     * wrong every other assertion in the file is measuring the wrong mechanism.
     */
    @Test
    void theElevatorFactorIsTheHandDerivedTravelPerOutputRotation() {
      assertEquals(0.2794, kElevatorU, kEps, "11.000 in x 0.0254 m/in");
      assertEquals(
          kElevatorU,
          elevatorUnits().siPerOutputRotation(),
          kEps,
          "MechanismUnits must derive the same 0.279400 m per output rotation from 22 teeth, "
              + "0.250 in pitch and 2 cascade stages");
    }

    /** A rotary axis's factor is 2*pi and is not affected by the gearbox — the gearbox is inside. */
    @Test
    void theArmFactorIsTwoPiRegardlessOfGearing() {
      assertEquals(kArmU, armUnits().siPerOutputRotation(), kEps);
      assertEquals(
          6.283185307179586,
          armUnits().siPerOutputRotation(),
          kEps,
          "one output rotation is 2*pi radians for every rotary mechanism ever built");
      assertEquals(
          100.0,
          armUnits().rotorPerOutput(),
          kEps,
          "5 x 5 x 4 = 100:1 — and it must NOT appear in the gain conversion, because "
              + "SensorToMechanismRatio has already absorbed it");
    }
  }

  @Nested
  @DisplayName("the elevator: volts per metre into a Phoenix slot")
  final class Elevator {

    /**
     * The five position-like terms scale by {@code U}; {@code kS} and {@code kG} are volts on both
     * sides and must not be touched. Every expectation is the multiplication written out.
     */
    @Test
    void everyGainConvertsExactlyOnce() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);

      // kP: 80.0 V/m x 0.279400 m/rot = 22.3520 V/rot
      assertEquals(80.0 * 0.2794, slot.kP, kEps);
      assertEquals(22.352, slot.kP, 1e-9);

      // kI: 0.5 V/(m*s) x 0.279400 = 0.139700 V/(rot*s)
      assertEquals(0.5 * 0.2794, slot.kI, kEps);
      assertEquals(0.1397, slot.kI, 1e-9);

      // kD: 2.0 V/(m/s) x 0.279400 = 0.558800 V/(rot/s)
      assertEquals(2.0 * 0.2794, slot.kD, kEps);
      assertEquals(0.5588, slot.kD, 1e-9);

      // kV: 12.0 V/(m/s) x 0.279400 = 3.352800 V/(rot/s)
      assertEquals(12.0 * 0.2794, slot.kV, kEps);
      assertEquals(3.3528, slot.kV, 1e-9);

      // kA: 0.6 V/(m/s^2) x 0.279400 = 0.167640 V/(rot/s^2)
      assertEquals(0.6 * 0.2794, slot.kA, kEps);
      assertEquals(0.16764, slot.kA, 1e-9);

      // kS and kG are VOLTS on both sides. Multiplying them by U is the classic mistake and would
      // put a 0.22 V friction term in at 0.0615 V, which reads as "kS does nothing".
      assertEquals(0.22, slot.kS, 0.0, "kS is volts on both sides — unconverted");
      assertEquals(0.35, slot.kG, 0.0, "kG is volts on both sides — unconverted");
    }

    /**
     * The five scaled terms are each scaled by {@code U} and not by anything else. Written as a
     * ratio so that a stray {@code / 12}, {@code * 60} or {@code * 2*pi} is named by the failure.
     */
    @Test
    void theScaleFactorIsExactlyUAndNothingElse() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);

      assertEquals(kElevatorU, slot.kP / kElevatorGains.kP(), 1e-15);
      assertEquals(kElevatorU, slot.kI / kElevatorGains.kI(), 1e-15);
      assertEquals(kElevatorU, slot.kD / kElevatorGains.kD(), 1e-15);
      assertEquals(kElevatorU, slot.kV / kElevatorGains.kV(), 1e-15);
      assertEquals(kElevatorU, slot.kA / kElevatorGains.kA(), 1e-15);
    }

    /**
     * {@code kS} must oppose the <em>closed-loop</em> direction. Against measured velocity it flips
     * with sensor noise while the mechanism holds station, which chatters audibly and heats the
     * motor. This is not a gain, but it travels with them and is wrong by default.
     */
    @Test
    void staticFeedforwardOpposesTheClosedLoopDirection() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);
      assertEquals(StaticFeedforwardSignValue.UseClosedLoopSign, slot.StaticFeedforwardSign);
    }

    /** An elevator gets {@code Elevator_Static} and no arm offset. */
    @Test
    void constantGravityBecomesElevatorStaticWithNoOffset() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);
      assertEquals(GravityTypeValue.Elevator_Static, slot.GravityType);
      assertEquals(0.0, slot.GravityArmPositionOffset, 0.0);
    }

    /**
     * {@link GravityMode#NONE} also uses {@code Elevator_Static}: {@code kG} is zero under NONE, so
     * the behaviour is identical with one fewer branch running on the device.
     */
    @Test
    void noGravityAlsoUsesElevatorStaticBecauseKgIsZero() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, Gains.pid(1.0, 0.0, 0.0), GravityMode.NONE, kElevatorU, 0.0);
      assertEquals(GravityTypeValue.Elevator_Static, slot.GravityType);
      assertEquals(0.0, slot.GravityArmPositionOffset, 0.0);
      assertEquals(0.0, slot.kG, 0.0);
    }
  }

  @Nested
  @DisplayName("the arm: volts per radian, and the negated gravity offset")
  final class Arm {

    /** kP of 7 V/rad is 43.98 V per output rotation. The 2*pi is where an arm goes wrong. */
    @Test
    void radianGainsScaleByTwoPi() {
      Gains si = new Gains(7.0, 0.0, 0.4, 0.18, 1.1, 0.05, 0.62);
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, si, GravityMode.COSINE, kArmU, kArmHorizontalRot);

      // 7.0 V/rad x 2*pi rad/rot = 43.98229715025710 V/rot
      assertEquals(7.0 * 2.0 * Math.PI, slot.kP, kEps);
      assertEquals(43.98229715025710, slot.kP, 1e-11);
      // 0.4 V/(rad/s) x 2*pi = 2.513274122871834
      assertEquals(0.4 * 2.0 * Math.PI, slot.kD, kEps);
      assertEquals(2.513274122871834, slot.kD, 1e-11);
      // 1.1 V/(rad/s) x 2*pi = 6.911503837897546
      assertEquals(1.1 * 2.0 * Math.PI, slot.kV, kEps);
      assertEquals(6.911503837897546, slot.kV, 1e-11);
      // volts stay volts
      assertEquals(0.18, slot.kS, 0.0);
      assertEquals(0.62, slot.kG, 0.0);
    }

    /**
     * <b>The offset is NEGATED.</b> Phoenix computes {@code kG * cos(position +
     * GravityArmPositionOffset)}; the library means {@code kG * cos(position -
     * horizontalReference)}. Writing it un-negated pushes {@code kG} the wrong way by <em>twice</em>
     * the reference angle — an arm that sags on one side and slams on the other.
     */
    @Test
    void theCosineOffsetIsTheNegatedHorizontalReference() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(
          slot, kElevatorGains, GravityMode.COSINE, kArmU, kArmHorizontalRot);

      assertEquals(GravityTypeValue.Arm_Cosine, slot.GravityType);
      // 20 deg / 360 = 0.0555555... rotations, negated.
      assertEquals(-(20.0 / 360.0), slot.GravityArmPositionOffset, kEps);
      assertEquals(-0.05555555555555555, slot.GravityArmPositionOffset, 1e-15);
      assertNotEquals(
          kArmHorizontalRot,
          slot.GravityArmPositionOffset,
          "an un-negated offset is wrong by twice the reference angle");
    }

    /**
     * A reference beyond Phoenix's silent clamp is clamped <em>here</em>, deliberately, to the
     * documented {@value Phoenix6GainSink#kMaxGravityOffsetRot} rotations. Phoenix clamps a larger
     * offset with no error at all, so the value the device runs and the value the config claims would
     * otherwise disagree with nothing to notice it.
     */
    @Test
    void anOutOfRangeReferenceIsClampedRatherThanSilentlyTruncatedByTheDevice() {
      Slot0Configs slot = new Slot0Configs();
      // 0.40 rotations = 144 degrees, well past the +/-0.25 rot (90 deg) Phoenix accepts.
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.COSINE, kArmU, 0.40);
      assertEquals(-Phoenix6GainSink.kMaxGravityOffsetRot, slot.GravityArmPositionOffset, kEps);
      assertEquals(-0.25, slot.GravityArmPositionOffset, kEps);

      Slot0Configs negative = new Slot0Configs();
      Phoenix6GainSink.writeInto(negative, kElevatorGains, GravityMode.COSINE, kArmU, -0.40);
      assertEquals(Phoenix6GainSink.kMaxGravityOffsetRot, negative.GravityArmPositionOffset, kEps);
    }
  }

  @Nested
  @DisplayName("the device kV that carries the goal velocity")
  final class DeviceKv {

    /**
     * Motion Magic requests have <b>no velocity field</b> — verified against the 26.3.0 jar — so the
     * goal-velocity term rides as volts: {@code ff += kV_device * goalRps}. {@code kV_device} must be
     * <em>the same number</em> as {@code Slot0.kV}, taken from one place, so there is no second
     * opportunity for a unit error.
     */
    @Test
    void deviceKvIsExactlyTheSlotKv() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);

      double deviceKv = Phoenix6GainSink.deviceKv(kElevatorGains, kElevatorU);
      assertEquals(slot.kV, deviceKv, 0.0, "one number, one place");
      assertEquals(12.0 * 0.2794, deviceKv, kEps);
      assertEquals(3.3528, deviceKv, 1e-9);
    }

    /**
     * The volt term for a concrete goal velocity, computed end to end. A field-locked mechanism
     * counter-rotating at 0.25 output rot/s needs {@code 3.3528 x 0.25 = 0.8382} volts of
     * feedforward, and that is the number the Phoenix backend must add to the request.
     */
    @Test
    void aConcreteGoalVelocityBecomesAConcreteNumberOfVolts() {
      double goalRps = 0.25;
      double volts = Phoenix6GainSink.deviceKv(kElevatorGains, kElevatorU) * goalRps;
      assertEquals(12.0 * 0.2794 * 0.25, volts, kEps);
      assertEquals(0.8382, volts, 1e-9);
    }

    /** A rotary mechanism's device kV goes through 2*pi, not 360 and not 1. */
    @Test
    void theRotaryDeviceKvGoesThroughTwoPi() {
      Gains si = Gains.feedforward(0.0, 1.1, 0.0);
      assertEquals(1.1 * 2.0 * Math.PI, Phoenix6GainSink.deviceKv(si, kArmU), kEps);
      assertEquals(6.911503837897546, Phoenix6GainSink.deviceKv(si, kArmU), 1e-11);
    }
  }

  @Nested
  @DisplayName("Motion Magic Expo rides on the same factor")
  final class Expo {

    /**
     * Expo's two terms are volts, so they take the identical {@code x U} conversion. An earlier
     * revision selected Expo and never configured them, silently inheriting CTRE's factory 0.12 and
     * 0.1 — a profile shaped by CTRE's arbitrary numbers instead of the measured plant.
     */
    @Test
    void expoTermsAreTheSameConversionAsTheSlot() {
      assertEquals(12.0 * 0.2794, Phoenix6GainSink.expoKv(kElevatorGains, kElevatorU), kEps);
      assertEquals(3.3528, Phoenix6GainSink.expoKv(kElevatorGains, kElevatorU), 1e-9);
      assertEquals(0.6 * 0.2794, Phoenix6GainSink.expoKa(kElevatorGains, kElevatorU), kEps);
      assertEquals(0.16764, Phoenix6GainSink.expoKa(kElevatorGains, kElevatorU), 1e-9);

      assertNotEquals(
          0.12,
          Phoenix6GainSink.expoKv(kElevatorGains, kElevatorU),
          "CTRE's factory default must never survive into a configured mechanism");
    }

    /** Both terms are clamped into the ranges the device will actually accept. */
    @Test
    void expoTermsAreClampedIntoTheRangePhoenixAccepts() {
      Gains tiny = Gains.feedforward(0.0, 0.0, 0.0);
      assertEquals(Phoenix6GainSink.kExpoKvMin, Phoenix6GainSink.expoKv(tiny, kElevatorU), 0.0);
      assertEquals(Phoenix6GainSink.kExpoKaMin, Phoenix6GainSink.expoKa(tiny, kElevatorU), 0.0);

      Gains huge = Gains.feedforward(0.0, 1.0e9, 1.0e9);
      assertEquals(Phoenix6GainSink.kExpoKvMax, Phoenix6GainSink.expoKv(huge, kElevatorU), 0.0);
      assertEquals(Phoenix6GainSink.kExpoKaMax, Phoenix6GainSink.expoKa(huge, kElevatorU), 0.0);
    }

    /** Untuned gains (a NaN kP) must not propagate NaN into the device configuration. */
    @Test
    void untunedGainsBecomeZerosRatherThanNaNs() {
      Slot0Configs slot = new Slot0Configs();
      Phoenix6GainSink.writeInto(slot, Gains.UNTUNED, GravityMode.NONE, kElevatorU, 0.0);
      assertEquals(0.0, slot.kP, 0.0, "a NaN kP must not reach the device as NaN");
      assertTrue(Double.isFinite(slot.kV));
      assertTrue(Double.isFinite(Phoenix6GainSink.expoKv(Gains.UNTUNED, kElevatorU)));
      assertTrue(Double.isFinite(Phoenix6GainSink.expoKa(Gains.UNTUNED, kElevatorU)));
    }
  }

  @Nested
  @DisplayName("the sink as an object: idempotent, non-blocking, honest")
  final class SinkBehaviour {

    /** A recording pair of writers, standing in for the device with no CAN bus involved. */
    private final List<Slot0Configs> m_slotWrites = new ArrayList<>();
    private final List<MotionMagicConfigs> m_expoWrites = new ArrayList<>();

    private Phoenix6GainSink newSink(Slot0Configs slot, MotionMagicConfigs mm, boolean useExpo) {
      return new Phoenix6GainSink(
          "Elevator",
          slot,
          mm,
          GravityMode.CONSTANT,
          kElevatorU,
          0.0,
          useExpo,
          "m",
          s -> {
            m_slotWrites.add(s);
            return StatusCode.OK;
          },
          m -> {
            m_expoWrites.add(m);
            return StatusCode.OK;
          });
    }

    /**
     * Applying the same gains twice touches the bus once. This runs at 10 Hz while a student drags a
     * slider, so a second write per call is a second CAN frame per loop for the whole session.
     */
    @Test
    void reapplyingUnchangedGainsPerformsNoBusTraffic() {
      Slot0Configs slot = new Slot0Configs();
      MotionMagicConfigs mm = new MotionMagicConfigs();
      Phoenix6GainSink sink = newSink(slot, mm, false);

      assertTrue(sink.apply(kElevatorGains));
      assertEquals(1, m_slotWrites.size());

      assertTrue(sink.apply(kElevatorGains));
      assertEquals(1, m_slotWrites.size(), "an unchanged apply must not write");

      assertTrue(sink.apply(kElevatorGains.withKp(81.0)));
      assertEquals(2, m_slotWrites.size(), "a changed apply must write");
    }

    /** The live apply path produces the identical slot the static construction path does. */
    @Test
    void theLiveApplyPathAgreesWithTheConstructionPath() {
      Slot0Configs live = new Slot0Configs();
      newSink(live, new MotionMagicConfigs(), false).apply(kElevatorGains);

      Slot0Configs constructed = new Slot0Configs();
      Phoenix6GainSink.writeInto(
          constructed, kElevatorGains, GravityMode.CONSTANT, kElevatorU, 0.0);

      assertEquals(constructed.kP, live.kP, 0.0);
      assertEquals(constructed.kI, live.kI, 0.0);
      assertEquals(constructed.kD, live.kD, 0.0);
      assertEquals(constructed.kS, live.kS, 0.0);
      assertEquals(constructed.kV, live.kV, 0.0);
      assertEquals(constructed.kA, live.kA, 0.0);
      assertEquals(constructed.kG, live.kG, 0.0);
      assertEquals(constructed.GravityType, live.GravityType);
      assertEquals(constructed.StaticFeedforwardSign, live.StaticFeedforwardSign);
    }

    /**
     * Under Expo the two profile terms must FOLLOW the gains, not stay frozen at whatever the
     * constructor computed. A retune that moves kV and leaves the profile behind gives a mechanism
     * whose profile and whose loop disagree about the plant.
     */
    @Test
    void expoTermsFollowARetune() {
      MotionMagicConfigs mm = new MotionMagicConfigs();
      Phoenix6GainSink sink = newSink(new Slot0Configs(), mm, true);

      sink.apply(kElevatorGains);
      assertEquals(12.0 * 0.2794, mm.MotionMagicExpo_kV, kEps);

      sink.apply(kElevatorGains.withKv(6.0));
      assertEquals(6.0 * 0.2794, mm.MotionMagicExpo_kV, kEps);
      assertEquals(1.6764, mm.MotionMagicExpo_kV, 1e-9);
      assertEquals(2, m_expoWrites.size(), "each change writes the Motion Magic config once");
    }

    /** With Expo off, the Motion Magic config is never written at all. */
    @Test
    void withoutExpoTheMotionMagicConfigIsNeverTouched() {
      Phoenix6GainSink sink = newSink(new Slot0Configs(), new MotionMagicConfigs(), false);
      sink.apply(kElevatorGains);
      sink.apply(kElevatorGains.withKv(6.0));
      assertTrue(m_expoWrites.isEmpty(), "no Expo means no Motion Magic write");
    }

    /**
     * {@code describeConversion()} prints the factor and both sides of every gain, so a CSA at an
     * event can check the arithmetic with a calculator. A conversion nobody can reproduce is a magic
     * number again.
     */
    @Test
    void describeConversionShowsItsWorking() {
      Phoenix6GainSink sink = newSink(new Slot0Configs(), new MotionMagicConfigs(), false);
      sink.apply(kElevatorGains);
      String text = sink.describeConversion();

      assertTrue(text.contains("0.279400"), "the one factor must be printed: " + text);
      assertTrue(text.contains("22.35200"), "the converted kP must be printed: " + text);
      assertTrue(text.contains("UseClosedLoopSign"), text);
      assertEquals(kElevatorU, sink.siUnitsPerMechanismRotation(), 0.0);
      assertEquals(kElevatorGains, sink.appliedGains());
    }
  }
}

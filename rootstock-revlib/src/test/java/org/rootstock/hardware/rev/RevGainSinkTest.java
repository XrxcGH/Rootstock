package org.rootstock.hardware.rev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.revrobotics.spark.config.SparkMaxConfig;
import edu.wpi.first.units.Units;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.SparkModel;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.RotaryAxis;

/**
 * The volts-per-SI to REVLib conversion, checked against numbers computed <b>by hand</b> — including
 * the cruise-velocity unit chain, so the 60&times; bug cannot come back.
 *
 * <h2>Why REV is the harder half</h2>
 *
 * <p>Phoenix takes volts. REVLib does not, and it does not do so <em>inconsistently</em>:
 *
 * <ul>
 *   <li><b>Feedback</b> ({@code closedLoop.pid}) is a <b>duty cycle</b> bounded by {@code
 *       outputRange}, so it carries a {@code / 12} that Phoenix does not.
 *   <li><b>Feedforward</b> ({@code closedLoop.feedForward}, new in REVLib 2026) is documented in
 *       <b>volts</b> — <i>"the kV gain in Volts per velocity"</i> — so it carries no {@code / 12} at
 *       all, and a 2025-shaped mental model that reaches for {@code velocityFF}'s per-RPM units puts
 *       a {@code / 60} in as well.
 *   <li><b>MAXMotion constraints</b> are "affected by the velocity conversion factor", which means
 *       they must be expressed in the unit the <em>measurement</em> ends up in — output rot/s — and
 *       a {@code * 60} "to convert to RPM" on top of a conversion factor that has already left RPM
 *       behind asks for sixty times the intended cruise velocity.
 * </ul>
 *
 * <p>Three different treatments of the same physical quantity, on adjacent lines, in one config
 * object. Every one of them is pinned below.
 *
 * <h2>The mechanism, derived rather than copied</h2>
 *
 * <pre>
 *   9143-2025-A elevator, on a SPARK MAX with a NEO:
 *     chain advance  = 22 teeth x 0.250 in            = 5.500 in per drum rotation
 *     cascade        = x 2 stages                     = 11.000 in per output rotation
 *     U              = 11.000 x 0.0254                = 0.279400 m per output rotation
 *     gearbox G      = 9:1                            (rotor rotations per output rotation)
 * </pre>
 *
 * <h2>No hardware</h2>
 *
 * <p>{@link RevGainSink} takes a nullable device and does its arithmetic on plain doubles, and
 * {@code SparkMaxConfig} — verified — constructs and accepts writes on a bare JVM; only {@code
 * flatten()} and the device handles reach {@code REVLibDriver}. So even the real vendor config object
 * is exercised here, and {@link ConfigRouting} reads its parameter table back by reflection rather
 * than through {@code flatten()}. What is genuinely unreachable without natives — constructing a
 * {@link SparkMotorIO}, which needs a {@code SparkMax} handle — lives in {@link SparkMotorIOHalTest}
 * behind {@code @Tag("hal")}.
 */
final class RevGainSinkTest {

  private static final double kEps = 1e-12;

  /** 22 teeth x 0.250 in x 2 stages = 11.000 in = 0.279400 m of travel per output rotation. */
  private static final double kU = 11.0 * 0.0254;

  /** Rotor rotations per output rotation. */
  private static final double kG = 9.0;

  /** REV's PID output is a duty cycle, so the feedback path divides by the bus. */
  private static final double kBusVolts = RevGainSink.kNominalBusVolts;

  private static final Gains kGains =
      new Gains(
          /* kP */ 80.0, /* kI */ 0.5, /* kD */ 2.0, /* kS */ 0.22, /* kV */ 12.0, /* kA */ 0.6,
          /* kG */ 0.35);

  private static MechanismUnits elevatorUnits() {
    return new MechanismUnits(
        Reduction.of(kG), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  private static RevGainSink sink(double siPerOutputRotation, GravityMode gravity) {
    return new RevGainSink(
        null,
        MotorSpec.spark(9, SparkModel.MAX_NEO),
        siPerOutputRotation,
        gravity,
        0.0,
        "Elevator");
  }

  @Nested
  @DisplayName("the one factor")
  final class TheFactor {

    @Test
    void theFactorIsTheHandDerivedTravelPerOutputRotation() {
      assertEquals(0.2794, kU, kEps, "11.000 in x 0.0254 m/in");
      assertEquals(
          kU,
          elevatorUnits().siPerOutputRotation(),
          kEps,
          "MechanismUnits must derive 0.279400 m per output rotation from 22 teeth, 0.250 in "
              + "pitch and 2 cascade stages");
      assertEquals(kU, sink(kU, GravityMode.CONSTANT).siUnitsPerMechanismRotation(), 0.0);
    }
  }

  @Nested
  @DisplayName("feedback: duty cycle, so x U and / 12")
  final class Feedback {

    /**
     * {@code kP = 80 V/m} on this elevator is {@code 80 x 0.2794 = 22.352} volts per output rotation,
     * and REVLib wants that as a fraction of a 12 V bus: {@code 22.352 / 12 = 1.86266667}.
     */
    @Test
    void kpIsVoltsPerRotationDividedByTheBus() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      assertEquals(80.0 * 0.2794 / 12.0, s.p(80.0), kEps);
      assertEquals(1.8626666666666667, s.p(80.0), 1e-13);
      assertEquals(kBusVolts, 12.0, 0.0, "the divisor is the nominal bus, stated once");
    }

    @Test
    void kiAndKdFollowKpExactly() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      // 0.5 x 0.2794 / 12 = 0.011641666...
      assertEquals(0.5 * 0.2794 / 12.0, s.i(0.5), kEps);
      assertEquals(0.011641666666666667, s.i(0.5), 1e-15);
      // 2.0 x 0.2794 / 12 = 0.046566666...
      assertEquals(2.0 * 0.2794 / 12.0, s.d(2.0), kEps);
      assertEquals(0.04656666666666667, s.d(2.0), 1e-15);
    }

    /**
     * The two ways to get this wrong, named. Forgetting the {@code / 12} makes kP twelve times too
     * hot on a device whose output saturates at 1.0; adding a {@code / 60} on top of it is the RPM
     * ghost that the conversion factor already exorcised.
     */
    @Test
    void theFeedbackScaleIsExactlyUOverTwelve() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      assertEquals(kU / kBusVolts, s.p(1.0), 1e-15);
      assertNotEquals(kU, s.p(1.0), "a missing / 12 makes kP twelve times too hot");
      assertNotEquals(kU / kBusVolts / 60.0, s.p(1.0), "there is no / 60 on the feedback path");
    }

    /** A rotary mechanism's feedback goes through 2*pi, then the bus. */
    @Test
    void aRotaryMechanismGoesThroughTwoPiThenTheBus() {
      RevGainSink s = sink(2.0 * Math.PI, GravityMode.COSINE);
      // 7.0 V/rad x 2*pi rad/rot / 12 V = 3.665191429188092
      assertEquals(7.0 * 2.0 * Math.PI / 12.0, s.p(7.0), kEps);
      assertEquals(3.665191429188092, s.p(7.0), 1e-12);
    }
  }

  @Nested
  @DisplayName("feedforward: REVLib 2026 volts, so x U and NOTHING else")
  final class Feedforward {

    /**
     * {@code FeedForwardConfig.kV} is documented in volts per velocity, and the velocity the device
     * measures has already been converted to output rot/s. So {@code kV} takes {@code x U} and
     * neither {@code / 12} nor {@code / 60}.
     */
    @Test
    void kvIsVoltsPerOutputRotationPerSecondWithNoBusAndNoMinute() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      // 12.0 V/(m/s) x 0.279400 m/rot = 3.35280 V/(rot/s)
      assertEquals(12.0 * 0.2794, s.kv(12.0), kEps);
      assertEquals(3.3528, s.kv(12.0), 1e-12);

      assertNotEquals(12.0 * 0.2794 / 12.0, s.kv(12.0), "no / 12 on the feedforward path");
      assertNotEquals(12.0 * 0.2794 / 60.0, s.kv(12.0), "no / 60 — the RPM ghost");
      assertNotEquals(12.0 * 0.2794 * 60.0, s.kv(12.0), "and no * 60 either");
    }

    @Test
    void kaTakesTheIdenticalTreatment() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      assertEquals(0.6 * 0.2794, s.ka(0.6), kEps);
      assertEquals(0.16764, s.ka(0.6), 1e-12);
    }

    /**
     * <b>The cross-vendor parity number.</b> The volts-per-output-rot/s a REV position request needs
     * to carry a goal velocity is <em>the same formula and the same number</em> Phoenix's Motion
     * Magic path uses: {@code kV_si * U}. That is what makes a field-locked turret behave the same on
     * both vendors, and what makes a parity test a real test rather than a tautology.
     *
     * <p>Written out rather than compared against the Phoenix constant, because this artifact must
     * not name {@code com.ctre} — ArchUnit rule 1, enforced by {@code RevArchitectureTest}.
     */
    @Test
    void theGoalVelocityCarrierMatchesThePhoenixFormulaExactly() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      double deviceKv = s.deviceKvVoltsPerOutputRps(kGains);

      assertEquals(12.0 * 0.2794, deviceKv, kEps, "kV_si x U, identical to the Phoenix slot kV");
      assertEquals(3.3528, deviceKv, 1e-12);
      assertEquals(s.kv(kGains.kV()), deviceKv, 0.0, "one number, one place");

      // A field-locked mechanism counter-rotating at 0.25 output rot/s needs 0.8382 V.
      assertEquals(3.3528 * 0.25, deviceKv * 0.25, 1e-12);
      assertEquals(0.8382, deviceKv * 0.25, 1e-12);
    }

    /** Untuned gains carry no feedforward at all rather than a NaN. */
    @Test
    void untunedGainsCarryZeroVoltsRatherThanNaN() {
      RevGainSink s = sink(kU, GravityMode.CONSTANT);
      assertEquals(0.0, s.deviceKvVoltsPerOutputRps(Gains.UNTUNED), 0.0);
      assertEquals(0.0, s.deviceKvVoltsPerOutputRps(null), 0.0);
    }

    /** kS and kG are volts on both sides. Scaling them is the classic mistake. */
    @Test
    void voltDenominatedGainsAreNotScaledAtAll() {
      // There is no s.kS()/s.kG() to call because there is nothing to convert — which is the point.
      // Assert the intent through describeConversion(), which prints the whole table.
      String text = sink(kU, GravityMode.CONSTANT).describeConversion();
      assertTrue(
          text.contains("no /60") && text.contains("no /12"),
          "the printed derivation must state the two conversions that are NOT applied: " + text);
      assertTrue(text.contains("0.279400"), "the one factor must be printed: " + text);
    }
  }

  @Nested
  @DisplayName("MAXMotion: the 60x bug, pinned to the unit chain that forbids it")
  final class MaxMotionUnitChain {

    /** The declared cruise, in the units a driver reasons about out loud. */
    private static final double kCruiseMetersPerSecond = 1.6;

    /** The declared acceleration limit. */
    private static final double kAccelMetersPerSecondSquared = 3.0;

    /**
     * REVLib's own javadoc: {@code positionConversionFactor} is applied to a rotor-rotation count and
     * {@code velocityConversionFactor} to a rotor-RPM reading. With {@code G} rotor rotations per
     * output rotation the two factors are forced:
     *
     * <pre>
     *   f_p = 1/G            : rotor rot  x 1/G       = output rot
     *   f_v = 1/(G x 60)     : rotor RPM  x 1/(G*60)  = (rotor rot/s)/G = output rot/s
     * </pre>
     */
    @Test
    void theConversionFactorsAreForcedByTheUnitsTheyConvert() {
      double positionFactor = 1.0 / kG;
      double velocityFactor = 1.0 / (kG * 60.0);

      // 90 rotor rotations at 9:1 is 10 output rotations.
      assertEquals(10.0, 90.0 * positionFactor, kEps);
      // 5400 rotor RPM at 9:1 is 90 rotor rot/s is 10 output rot/s.
      assertEquals(10.0, 5400.0 * velocityFactor, kEps);
      assertEquals(1.0 / 540.0, velocityFactor, kEps);
    }

    /**
     * <b>The cruise velocity is in OUTPUT ROT/S, with no {@code * 60}.</b>
     *
     * <p>Derived rather than asserted: the number written into {@code maxMotion.cruiseVelocity} must
     * be the number REVLib's own velocity getter would report when the mechanism is physically
     * running at the requested speed, because the same {@code f_v} is applied to both. So compute
     * that reading independently, from the physical chain, and require the config value to equal it.
     *
     * <pre>
     *   1.6 m/s / 0.279400 m per output rot   = 5.726557 output rot/s
     *   x 9 rotor per output                  = 51.53901 rotor rot/s
     *   x 60                                  = 3092.341 rotor RPM   (what the device measures)
     *   x f_v = 1/540                         = 5.726557 output rot/s (what REVLib reports)
     * </pre>
     */
    @Test
    void theCruiseVelocityIsWhatTheDeviceWillReportAtThatSpeed() {
      MechanismUnits units = elevatorUnits();
      MotionConstraints constraints =
          MotionConstraints.of(kCruiseMetersPerSecond, kAccelMetersPerSecondSquared);

      double configured = constraints.maxVelocityRps(units);

      // Hand-derived, independently of MotionConstraints.
      double outputRps = kCruiseMetersPerSecond / kU;
      double rotorRpmAtThatSpeed = outputRps * kG * 60.0;
      double whatRevLibWillReport = rotorRpmAtThatSpeed * (1.0 / (kG * 60.0));

      assertEquals(5.726556907659270, outputRps, 1e-12, "1.6 / 0.2794");
      assertEquals(3092.3407301360057, rotorRpmAtThatSpeed, 1e-9, "5.726557 x 9 x 60");
      assertEquals(
          whatRevLibWillReport,
          configured,
          1e-12,
          "the cruise velocity written must be the value REVLib's own getter reports at that "
              + "speed, because the SAME velocityConversionFactor is applied to both");
      assertEquals(outputRps, configured, 1e-12);
    }

    /**
     * The bug, quantified. Writing {@code cruiseVelocity * 60} "to convert to RPM" — on top of a
     * conversion factor that has already left RPM behind — asks for sixty times the intended speed.
     * On this elevator that is 343.6 m/s: the profile never cruises, the carriage accelerates until
     * the current limit stops it, and a gravity-loaded elevator arrives at the top hard stop at full
     * speed.
     */
    @Test
    void aStrayTimesSixtyAsksForSixtyTimesTheSpeed() {
      MechanismUnits units = elevatorUnits();
      MotionConstraints constraints =
          MotionConstraints.of(kCruiseMetersPerSecond, kAccelMetersPerSecondSquared);

      double correct = constraints.maxVelocityRps(units);
      double withStrayTimesSixty = correct * 60.0;

      // Interpret both back through the same chain to see what each ASKS FOR, in m/s.
      assertEquals(kCruiseMetersPerSecond, units.toUserPerSec(correct), 1e-12);
      assertEquals(
          60.0 * kCruiseMetersPerSecond,
          units.toUserPerSec(withStrayTimesSixty),
          1e-9,
          "the stray x60 is not a small error");
      assertEquals(96.0, units.toUserPerSec(withStrayTimesSixty), 1e-9, "60 x 1.6 m/s");
      assertTrue(
          units.toUserPerSec(withStrayTimesSixty) > 50.0,
          "and it is physically absurd, which is why it presents as 'the profile never cruises' "
              + "rather than as a number anybody notices in a config file");
    }

    /** The acceleration limit takes the identical treatment, and it too has no {@code * 60}. */
    @Test
    void theAccelerationLimitIsOutputRotationsPerSecondSquared() {
      MechanismUnits units = elevatorUnits();
      MotionConstraints constraints =
          MotionConstraints.of(kCruiseMetersPerSecond, kAccelMetersPerSecondSquared);

      // 3.0 m/s^2 / 0.279400 m per output rot = 10.73729419 output rot/s^2
      assertEquals(3.0 / 0.2794, constraints.maxAccelerationRps2(units), 1e-12);
      assertEquals(10.737294201861131, constraints.maxAccelerationRps2(units), 1e-11);
      assertNotEquals(
          3.0 / 0.2794 * 60.0,
          constraints.maxAccelerationRps2(units),
          "maxAcceleration is documented as RPM-per-second natively, and is likewise 'affected by "
              + "the velocity conversion factor' — so it too is written in the converted unit");
    }

    /**
     * {@code allowedProfileError} is affected by the <em>position</em> conversion factor, so it is in
     * output rotations — a third unit, on the third line of the same builder chain.
     */
    @Test
    void theAllowedProfileErrorIsInOutputRotations() {
      MechanismUnits units = elevatorUnits();
      // A 5 mm tolerance on this elevator is 0.005 / 0.2794 = 0.017895 output rotations.
      assertEquals(0.005 / 0.2794, units.toOutputRotations(0.005), 1e-15);
      assertEquals(0.017895490336435218, units.toOutputRotations(0.005), 1e-15);
    }

    /**
     * A rotary mechanism's chain, so the elevator's metres cannot be the only case that works. A
     * turret asked for 180 deg/s on a 100:1 box: {@code 180/360 = 0.5} output rot/s, which is 50
     * rotor rot/s, which is 3000 rotor RPM.
     */
    @Test
    void theRotaryChainAgreesToo() {
      MechanismUnits turret =
          new MechanismUnits(Reduction.ofStages(5.0, 5.0, 4.0), RotaryAxis.turret(true));
      MotionConstraints constraints = MotionConstraints.of(180.0, 360.0);

      assertEquals(0.5, constraints.maxVelocityRps(turret), kEps, "180 deg/s is half a turret turn");
      assertEquals(1.0, constraints.maxAccelerationRps2(turret), kEps);
      assertEquals(3000.0, 0.5 * 100.0 * 60.0, kEps, "which the rotor sees as 3000 RPM");
      assertEquals(0.5, 3000.0 * (1.0 / (100.0 * 60.0)), kEps, "and REVLib converts back to 0.5");
    }
  }

  @Nested
  @DisplayName("the real vendor config object: which field each gain lands in")
  final class ConfigRouting {

    /**
     * The sink's output compared, parameter table for parameter table, against a config built by
     * hand with the vendor API and the hand-derived numbers.
     *
     * <p>This catches what a value assertion cannot: a gain converted correctly and written into the
     * <em>wrong field</em>. It also pins {@code outputRange}, which is what bounds the duty cycle the
     * {@code / 12} above assumes.
     */
    @Test
    void everyGainLandsInTheFieldItBelongsIn() {
      SparkMaxConfig actual = new SparkMaxConfig();
      sink(kU, GravityMode.CONSTANT).writeInto(actual, kGains);

      SparkMaxConfig expected = new SparkMaxConfig();
      expected
          .closedLoop
          .pid(80.0 * kU / 12.0, 0.5 * kU / 12.0, 2.0 * kU / 12.0, RevGainSink.kSlot)
          .outputRange(-1, 1);
      expected.closedLoop.feedForward
          .kS(0.22, RevGainSink.kSlot)
          .kV(12.0 * kU, RevGainSink.kSlot)
          .kA(0.6 * kU, RevGainSink.kSlot);
      expected.closedLoop.feedForward.kG(0.35, RevGainSink.kSlot);

      assertEquals(parameters(expected), parameters(actual));
    }

    /**
     * <b>Exactly one of {@code kG} and {@code kCos} is ever written.</b>
     *
     * <p>Verified in the REVLib 2026.0.5 sources: {@code kCos(v, slot)} <em>deletes</em> any {@code
     * kG} already in the config, and {@code kG(v, slot)} silently refuses when a {@code kCos} is
     * present. So the natural-looking defensive form — set the one you want, zero the other — ships an
     * elevator with <em>no</em> gravity feedforward at all. Comparing whole parameter tables is how
     * that becomes visible: a stray zeroing adds a key the hand-built config does not have.
     */
    @Test
    void anElevatorGetsKgAndNoKcos() {
      SparkMaxConfig actual = new SparkMaxConfig();
      sink(kU, GravityMode.CONSTANT).writeInto(actual, kGains);

      assertTrue(
          parameters(actual).keySet().containsAll(kgParameterKeys()),
          "an elevator's gravity gain must land in feedForward.kG");
      for (String kcosKey : kcosParameterKeys()) {
        assertFalse(
            parameters(actual).containsKey(kcosKey),
            "and kCos must not be touched at all — not even zeroed. kCos(v, slot) DELETES any kG "
                + "already present, so the belt-and-braces form ships a carriage with no gravity "
                + "feedforward. Offending key: "
                + kcosKey);
      }
    }

    /** An arm gets {@code kCos} plus a unit {@code kCosRatio}, and no {@code kG}. */
    @Test
    void anArmGetsKcosAndKcosRatioAndNoKg() {
      SparkMaxConfig actual = new SparkMaxConfig();
      sink(2.0 * Math.PI, GravityMode.COSINE).writeInto(actual, kGains);

      SparkMaxConfig expected = new SparkMaxConfig();
      double u = 2.0 * Math.PI;
      expected
          .closedLoop
          .pid(80.0 * u / 12.0, 0.5 * u / 12.0, 2.0 * u / 12.0, RevGainSink.kSlot)
          .outputRange(-1, 1);
      expected.closedLoop.feedForward
          .kS(0.22, RevGainSink.kSlot)
          .kV(12.0 * u, RevGainSink.kSlot)
          .kA(0.6 * u, RevGainSink.kSlot);
      expected.closedLoop.feedForward.kCos(0.35, RevGainSink.kSlot).kCosRatio(1.0, RevGainSink.kSlot);

      assertEquals(
          parameters(expected),
          parameters(actual),
          "kCosRatio is 1.0 because the conversion factor already produces mechanism rotations");
      for (String kgKey : kgParameterKeys()) {
        assertFalse(
            parameters(actual).containsKey(kgKey),
            "an arm must not also write kG: kG(v, slot) silently refuses when a kCos is present, "
                + "so the write would be a no-op that reads like a safety net. Offending key: "
                + kgKey);
      }
    }

    /** {@link GravityMode#NONE} writes neither gravity term rather than writing a zero. */
    @Test
    void gravityNoneWritesNeitherTerm() {
      SparkMaxConfig actual = new SparkMaxConfig();
      sink(kU, GravityMode.NONE).writeInto(actual, kGains);

      SparkMaxConfig expected = new SparkMaxConfig();
      writeFeedbackAndFeedforward(expected);

      assertEquals(parameters(expected), parameters(actual));
    }

    /** Untuned gains write nothing at all — the device keeps its factory zeros and does not move. */
    @Test
    void untunedGainsWriteNothing() {
      SparkMaxConfig actual = new SparkMaxConfig();
      sink(kU, GravityMode.CONSTANT).writeInto(actual, Gains.UNTUNED);
      assertEquals(
          parameters(new SparkMaxConfig()),
          parameters(actual),
          "a NaN kP must reach the device as no configuration at all, never as NaN");
    }

    /**
     * The parameter key {@code feedForward.kG} occupies, discovered by writing only that term to an
     * otherwise empty config.
     *
     * <p>Discovered rather than hard-coded because REVLib's parameter ids are an implementation
     * detail. Written into its <em>own</em> config: a fixture that set {@code kG} and then {@code
     * kCos} to compare them side by side does not merely lose the gain, it reaches REVLib's
     * driver-station warning path, which on a JVM with no natives terminates the process outright.
     * The two terms are genuinely mutually exclusive, and even a test may only hold one at a time.
     *
     * @return the key set a lone {@code kG} produces
     */
    private java.util.Set<String> kgParameterKeys() {
      SparkMaxConfig only = new SparkMaxConfig();
      only.closedLoop.feedForward.kG(0.35, RevGainSink.kSlot);
      return parameters(only).keySet();
    }

    /**
     * The parameter keys {@code feedForward.kCos} and {@code kCosRatio} occupy.
     *
     * @return the key set a lone {@code kCos} plus {@code kCosRatio} produces
     */
    private java.util.Set<String> kcosParameterKeys() {
      SparkMaxConfig only = new SparkMaxConfig();
      only.closedLoop.feedForward.kCos(0.35, RevGainSink.kSlot).kCosRatio(1.0, RevGainSink.kSlot);
      return parameters(only).keySet();
    }

    private void writeFeedbackAndFeedforward(SparkMaxConfig config) {
      config
          .closedLoop
          .pid(80.0 * kU / 12.0, 0.5 * kU / 12.0, 2.0 * kU / 12.0, RevGainSink.kSlot)
          .outputRange(-1, 1);
      config.closedLoop.feedForward
          .kS(0.22, RevGainSink.kSlot)
          .kV(12.0 * kU, RevGainSink.kSlot)
          .kA(0.6 * kU, RevGainSink.kSlot);
    }
  }

  // ---- reading a REVLib config back without touching flatten() ---------------------------------

  /**
   * The whole parameter table of a config and every nested config it owns, keyed by path and
   * parameter id.
   *
   * <p>REVLib exposes no getter — only {@code flatten()}, whose static initialiser loads {@code
   * REVLibDriver} and therefore cannot run on a bare JVM. The parameters themselves live in a private
   * {@code Map<Integer, Object>} on {@code com.revrobotics.config.BaseConfig}, and reading it by
   * reflection is what lets the routing assertions above run in the default build. The parameter ids
   * are opaque and are never interpreted here: every assertion compares this table against one
   * produced by the vendor API itself.
   *
   * @param config the config to read
   * @return an ordered map of {@code "<path>#<parameterId>"} to stored value
   */
  private static Map<String, Object> parameters(Object config) {
    Map<String, Object> out = new TreeMap<>();
    collect(config, "", out, new IdentityHashMap<>());
    return out;
  }

  private static void collect(
      Object node, String path, Map<String, Object> out, IdentityHashMap<Object, Boolean> seen) {
    if (node == null || seen.put(node, Boolean.TRUE) != null) {
      return;
    }
    try {
      for (Class<?> k = node.getClass(); k != null; k = k.getSuperclass()) {
        if ("com.revrobotics.config.BaseConfig".equals(k.getName())) {
          Field field = k.getDeclaredField("parameters");
          field.setAccessible(true);
          Map<?, ?> stored = (Map<?, ?>) field.get(node);
          for (Map.Entry<?, ?> entry : stored.entrySet()) {
            out.put(path + "#" + entry.getKey(), entry.getValue());
          }
        }
      }
      for (Field field : node.getClass().getFields()) {
        if (isBaseConfig(field.getType())) {
          collect(field.get(node), path + "." + field.getName(), out, seen);
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "could not read REVLib's private parameter table. REVLib changed shape; this helper — and "
              + "therefore the routing assertions that depend on it — needs re-checking against the "
              + "new sources rather than deleting.",
          e);
    }
  }

  private static boolean isBaseConfig(Class<?> type) {
    for (Class<?> k = type; k != null; k = k.getSuperclass()) {
      if ("com.revrobotics.config.BaseConfig".equals(k.getName())) {
        return true;
      }
    }
    return false;
  }
}

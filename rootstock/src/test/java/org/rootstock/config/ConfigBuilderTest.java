package org.rootstock.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Rotations;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.ControlLocationSource;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.control.NeutralMode;
import org.rootstock.control.PositionReference;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.RotaryAxis;

/**
 * The two flagship configs of {@code design/01} §5.4 and §5.5, built and checked number by number.
 *
 * <p><strong>Why the whole config and not a unit of it.</strong> The claim the config system makes
 * is that a team states the machine <em>once</em> and everything downstream follows: soft limits in
 * rotations, Motion Magic constraints, the simulated plant, the gain units, the free-speed sanity
 * check, the homing seed. That claim is only testable end to end. Each assertion below carries the
 * derivation it checks, so a reader can verify the number rather than trust it — the design's own
 * review found a 1.87x gear-ratio error, a 2.9x free-speed claim and a 60x velocity error in text
 * that nobody had recomputed.
 *
 * <p><strong>{@code Gains.realOrSim} is deliberately not used here.</strong> It reads
 * {@code Platform.isReal()}, which reaches WPILib's native loader; on a JVM without the JNI
 * natives that is a {@code NoClassDefFoundError}. The literal gains of the "real" branch are used
 * instead, which is what {@code realOrSim} would return on a robot. The selection itself is
 * covered by the {@code @Tag("hal")} case in {@code GainsTest}.
 */
final class ConfigBuilderTest {

  @BeforeEach
  @AfterEach
  void clearGlobalLookupMisses() {
    Validation.resetForTest();
  }

  // ===============================================================================================
  // design/01 §5.4 — the two-Kraken cascade elevator
  // ===============================================================================================

  @Nested
  @DisplayName("the §5.4 elevator")
  final class Elevator {

    /** Exactly the config printed in design/01 §5.4, minus the realOrSim wrapper. */
    private static PositionConfig build() {
      return PositionConfig.linear("Elevator")
          .motors(
              MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                  .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
          .reduction(Reduction.ofStages(3.0, 4.0))
          .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
          .feedback(new FeedbackSpec.RotorOnly())
          .softLimits(Inches.of(0.0), Inches.of(55.0))
          .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
          .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33))
          .constraints(MotionConstraints.of(1.6, 6.0))
          .tolerance(Inches.of(0.5), 0.05, 0.06)
          .manualControl(0.10, 0.30)
          .homing(
              HomingStrategy.currentSpike()
                  .direction(HomingStrategy.Direction.REVERSE)
                  .voltage(Volts.of(-1.5))
                  .currentThreshold(Amps.of(30))
                  .debounce(Seconds.of(0.15))
                  .timeout(Seconds.of(4.0))
                  .seedTo(Inches.of(0.0)))
          .setpoint("STOW", Inches.of(0.0))
          .setpoint("L1", Inches.of(8.0))
          .setpoint("L2", Inches.of(20.5))
          .setpoint("L3", Inches.of(37.5))
          .setpoint("L4", Inches.of(52.5))
          .sim(Pounds.of(24.0), Inches.of(0.0))
          .build();
    }

    @Test
    @DisplayName("builds with no fatal error — only the atGoal timing note")
    void buildsClean() {
      PositionConfig elevator = build();
      assertFalse(elevator.hasFatalError(), elevator.errors().toString());
      // Exactly one WARNING, and it is the deliberate one: the 0.5 in tolerance band is narrower
      // than the 0.032 m the carriage covers in one 20 ms loop at 1.6 m/s, so atGoal() latches
      // only after the profile decelerates. design/01 documents that as correct behaviour.
      assertEquals(1, elevator.errors().size(), elevator.errors().toString());
      ConfigError note = elevator.errors().get(0);
      assertEquals(ConfigError.Severity.WARNING, note.severity());
      assertEquals("control.tolerance", note.field());
      assertEquals("0.0127 m", note.value());
      assertTrue(note.expected().contains("one loop step (0.0320 m)"), note.expected());
    }

    @Test
    @DisplayName("the geometry resolves to 0.279400 m per output rotation")
    void geometry() {
      MechanismUnits units = build().units();
      assertEquals(0.279400, units.userPerOutputRotation(), 1e-12);
      assertEquals(12.0, units.rotorPerOutput(), 1e-12);
      assertEquals(0.279400 / 12.0, units.userPerRotorRotation(), 1e-12);
      assertEquals("m", units.unitLabel());
      assertTrue(build().isLinear());
      assertEquals(MechanismKind.POSITION, build().kind());
    }

    @Test
    @DisplayName("55 in of soft travel is exactly 5.0000 output rotations")
    void softLimitsInRotations() {
      PositionConfig elevator = build();
      assertEquals(0.0, elevator.travel().min(), 0.0);
      assertEquals(1.397, elevator.travel().max(), 1e-12);
      assertEquals("[0.000, 1.397] m", elevator.travel().describe());
      assertEquals(5.0, elevator.units().toOutputRotations(elevator.travel().max()), 1e-12);
    }

    @Test
    @DisplayName("free speed is 2.2507 m/s and the 1.6 m/s cruise is 71.1% of it")
    void freeSpeedAndCruise() {
      // 5800 rpm (Kraken X60 FOC) / 60 / 12 x 0.279400 = 2.250722 m/s
      PositionConfig elevator = build();
      assertEquals(2.250722, elevator.freeSpeedUserPerSec(), 1e-6);
      assertEquals(
          71.09, 100.0 * 1.6 / elevator.freeSpeedUserPerSec(), 0.01, "cruise as % of free speed");
      // ... and it is therefore under the 100%-of-free-speed threshold, so no warning fires.
      assertTrue(
          elevator.errors().stream()
              .noneMatch(e -> e.field().equals("control.constraints.maxVelocity")),
          elevator.errors().toString());
    }

    @Test
    @DisplayName("gains stay volts-per-SI, and the device-side numbers derive from them")
    void gainsAreVoltsPerSi() {
      PositionConfig elevator = build();
      Gains gains = elevator.control().gains();
      assertFalse(gains.isUntuned());
      assertEquals(80.0, gains.kP(), 0.0);
      assertEquals(2.0, gains.kD(), 0.0);
      assertEquals(0.33, gains.kG(), 0.0);

      // The whole point of volts-per-SI: the DEVICE gains are derived, not stored.
      // Slot0.kP = 80 V/m x 0.279400 m/rot = 22.352 V/rot
      // Slot0.kD = 2.0 V/(m/s) x 0.279400 = 0.5588
      double perRotation = elevator.units().siPerOutputRotation();
      assertEquals(22.352, gains.kP() * perRotation, 1e-9);
      assertEquals(0.5588, gains.kD() * perRotation, 1e-9);

      // Changing the gearbox does NOT change any of the seven, which is why the one-line ratio
      // edit in §5.4 is safe.
      PositionConfig regeared = elevator.withReduction(Reduction.ofStages(3.0, 5.0));
      assertEquals(gains, regeared.control().gains());
      assertEquals(15.0, regeared.units().rotorPerOutput(), 1e-12);
    }

    @Test
    @DisplayName("the control location is defaulted from the TalonFX leader, not left unset")
    void controlLocationIsDefaultedWithProvenance() {
      PositionConfig elevator = build();
      assertEquals(ControlLocation.ON_MOTOR_PROFILED, elevator.control().location());
      assertEquals(ControlLocationSource.DEFAULTED, elevator.control().locationSource());
      assertTrue(elevator.control().location().runsOnMotor());
      assertTrue(elevator.control().location().setpointLatches());
    }

    @Test
    @DisplayName("gravity is derived from the axis without the config naming it")
    void gravityIsDerived() {
      // §5.4 never writes .gravity(...). LinearAxis implies CONSTANT and the builder takes it.
      assertEquals(GravityMode.CONSTANT, build().control().gravity());
      assertEquals(GravityMode.CONSTANT, build().axis().gravity());
    }

    @Test
    @DisplayName("rotor-only feedback plus current-spike homing gives a homed-against-stop reference")
    void positionReference() {
      PositionReference reference = build().positionReference();
      assertTrue(reference instanceof PositionReference.HomedAgainstSwitch, reference.describe());
      assertFalse(reference.isAbsolute());
      assertTrue(reference.describe().contains("homed against a switch or hard stop"));
    }

    @Test
    @DisplayName("all five setpoints resolve to the inches they were declared in")
    void setpointsResolve() {
      PositionConfig elevator = build();
      assertEquals(5, elevator.setpoints().size());
      assertEquals(0.0, elevator.setpoint("STOW").valueUser(), 0.0);
      assertEquals(8.0 * 0.0254, elevator.setpoint("L1").valueUser(), 1e-12);
      assertEquals(0.52070, elevator.setpoint("L2").valueUser(), 1e-9);
      assertEquals(0.95250, elevator.setpoint("L3").valueUser(), 1e-9);
      assertEquals(1.33350, elevator.setpoint("L4").valueUser(), 1e-9);
      for (Setpoint setpoint : elevator.setpoints()) {
        assertTrue(setpoint.isResolved(), setpoint.describe());
        assertTrue(setpoint.asDistance().isPresent(), setpoint.describe());
        assertEquals(List.of(), setpoint.problems());
        assertTrue(elevator.travel().contains(setpoint.valueUser()), setpoint.describe());
      }
      // Every setpoint lookup succeeded, so nothing was recorded globally.
      assertEquals(List.of(), Validation.lookupMisses());
    }

    @Test
    @DisplayName("both CAN devices are declared, on the bus they were given")
    void canDevices() {
      assertEquals(2, build().canDevices().size());
      assertTrue(
          build().canDevices().stream().allMatch(d -> d.bus().equals("rio")),
          build().canDevices().toString());
      assertEquals(2, build().motors().count());
      assertTrue(build().motors().leader().foc());
      assertEquals(
          Follower.OPPOSED, build().motors().followers().get(0).sense(), "the followers oppose");
    }

    @Test
    @DisplayName("the simulated plant is the declared 24 lb carriage, starting at the bottom")
    void simulation() {
      // 24 lb -> kg. NOTE the constant: WPILib's Pounds unit carries 0.453592, not the exact
      // international-pound 0.45359237 that design/01 §5.4's kG derivation uses by hand. The two
      // differ by 3.4 ppm (10.886208 vs 10.886217 kg), which moves no gain at two decimal places
      // — but the literal below is WPILib's, because that is the mass the simulator actually runs.
      SimConfig sim = build().sim();
      assertEquals(24.0 * 0.453592, sim.massKg(), 1e-9);
      assertEquals(10.886208, sim.massKg(), 1e-6);
      assertEquals(0.0, sim.startingPositionSi(), 0.0);
      assertTrue(sim.simulateGravity());
      assertEquals(List.of(), sim.problems());
    }

    @Test
    @DisplayName("describe() prints the gearing derivation and the resolved control location")
    void describePrintsTheDerivation() {
      String text = build().describe();

      // --- the gearing, spelled out stage by stage ---
      assertTrue(text.contains("(3.000) x (4.000) = 12.000:1 (rotor per output)"), text);
      assertTrue(text.contains("#25 chain, 0.250 in pitch x 22 teeth"), text);
      assertTrue(text.contains("chain advance    = 22 x 0.250 in = 5.5000 in per drum rot"), text);
      assertTrue(text.contains("kinematic radius = 5.5000 / 2pi = 0.87535 in = 0.0222339 m"), text);
      assertTrue(text.contains("2 stages (cascade) -> the carriage moves 2x the drum surface"), text);
      assertTrue(text.contains("travel per drum rot  0.279400 m  (11.0000 in)"), text);
      assertTrue(text.contains("effective radius     0.0444679 m"), text);
      assertTrue(text.contains("NOT the bare drum radius"), text);

      // --- the resolved control location, with its provenance and the escape hatch ---
      assertTrue(
          text.contains(
              "ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX"),
          text);
      assertTrue(text.contains("To change it: .controlLocation(...)"), text);

      // --- and the derived numbers a reader would otherwise have to compute ---
      assertTrue(text.contains("Soft limits         [0.000, 1.397] m  =  0.0000 .. 5.0000 output rot"), text);
      assertTrue(text.contains("Free speed estimate 2.2507 m/s"), text);
      assertTrue(text.contains("Cruise velocity     1.6000 m/s = 71.1% of free speed"), text);
      assertTrue(text.contains("kP 80.0000 V/m"), text);
      assertTrue(text.contains("Elevator.L4 = 1.3335 m"), text);
    }

    @Test
    @DisplayName("the logged snapshot carries the same numbers describe() prints")
    void snapshotAgreesWithDescribe() {
      MechanismConfigSnapshot snapshot = build().snapshot();
      assertEquals("Elevator", snapshot.name());
      assertEquals("POSITION", snapshot.kind());
      assertEquals("ON_MOTOR_PROFILED", snapshot.controlLocation());
      assertEquals("DEFAULTED", snapshot.controlLocationSource());
      assertEquals("VOLTAGE", snapshot.outputMode());
      assertTrue(snapshot.focEnabled());
      assertEquals(20, snapshot.leaderCanId());
      assertEquals(1, snapshot.followerCount());
      assertEquals(12.0, snapshot.reductionRotorPerOutput(), 1e-12);
      assertEquals(0.279400, snapshot.userPerOutputRotation(), 1e-12);
      assertEquals("m", snapshot.unitLabel());
      assertEquals("m", snapshot.siLabel());
      assertEquals(0.0, snapshot.softMin(), 0.0);
      assertEquals(1.397, snapshot.softMax(), 1e-12);
      assertEquals(80.0, snapshot.kP(), 0.0);
      assertEquals(0.33, snapshot.kG(), 0.0);
      assertEquals("CONSTANT", snapshot.gravityMode());
      assertEquals(70.0, snapshot.statorAmps(), 0.0);
      assertEquals(40.0, snapshot.supplyAmps(), 0.0);
      assertEquals("RotorOnly", snapshot.feedbackKind());
      assertEquals("CurrentSpike", snapshot.homingKind());
      assertEquals(10.886208, snapshot.simMassOrMoi(), 1e-6);
      assertEquals(1, snapshot.configErrorCount());
      assertTrue(snapshot.logKey().startsWith(MechanismConfigSnapshot.kLogRoot), snapshot.logKey());
      assertTrue(snapshot.logKey().endsWith("Elevator"), snapshot.logKey());
    }

    @Test
    @DisplayName("changing the gearbox in one place moves every derived number with it")
    void oneLineRegearMovesEverything() {
      PositionConfig before = build();
      PositionConfig after = before.withReduction(Reduction.ofStages(3.0, 5.0));

      // 15:1 instead of 12:1 -> the carriage is slower and the rotor turns further per metre.
      assertEquals(15.0, after.units().rotorPerOutput(), 1e-12);
      assertEquals(2.250722 * 12.0 / 15.0, after.freeSpeedUserPerSec(), 1e-6);
      assertEquals(1.800578, after.freeSpeedUserPerSec(), 1e-6);
      assertEquals(0.279400 / 15.0, after.units().userPerRotorRotation(), 1e-12);

      // The geometry, the soft limits and the gains are untouched — they describe the mechanism.
      assertEquals(before.travel(), after.travel());
      assertEquals(0.279400, after.units().userPerOutputRotation(), 1e-12);
      assertEquals(before.control().gains(), after.control().gains());
      assertEquals(before.setpoints(), after.setpoints());

      // ... and the cruise sanity check re-runs: 1.6 m/s is now 88.9% of free speed, still legal.
      assertFalse(after.hasFatalError(), after.errors().toString());
    }
  }

  // ===============================================================================================
  // design/01 §5.5 — the fused-CANcoder arm
  // ===============================================================================================

  @Nested
  @DisplayName("the §5.5 arm")
  final class Arm {

    /** (58:10) x (58:18) x (42:12) = 141288/2160 = 65.4111111:1 */
    private static final double kReduction = 141288.0 / 2160.0;

    private static PositionConfig build() {
      return PositionConfig.rotary("Arm")
          .motors(MotorGroup.leader(MotorSpec.talonFX(22, "rio").inverted(true)))
          .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
          .axis(RotaryAxis.arm(Degrees.of(0.0)))
          .feedback(
              new FeedbackSpec.FusedCancoder(23, "rio", Rotations.of(-0.1387), kReduction, 1.0))
          .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
          .hardStop(HardStop.REVERSE, SensorSpec.motorLimit(SensorSpec.Limit.REVERSE))
          .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
          .gains(Gains.pid(5.0, 0.0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29))
          .constraints(MotionConstraints.of(180.0, 540.0))
          .tolerance(Degrees.of(1.5), 5.0, 0.06)
          .manualControl(0.10, 0.20)
          .homing(HomingStrategy.absoluteSeed())
          .setpoint("STOW", Degrees.of(95.0))
          .setpoint("INTAKE", Degrees.of(-10.0))
          .setpoint("SCORE", Degrees.of(35.0))
          .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0)))
          .build();
    }

    @Test
    @DisplayName("builds with no fatal error")
    void buildsClean() {
      PositionConfig arm = build();
      assertFalse(arm.hasFatalError(), arm.errors().toString());
      assertEquals(1, arm.errors().size(), arm.errors().toString());
      assertEquals("control.tolerance", arm.errors().get(0).field());
      assertEquals(ConfigError.Severity.WARNING, arm.errors().get(0).severity());
    }

    @Test
    @DisplayName("the tooth counts multiply to 65.4111:1, the revision-4 number")
    void reductionIsSixtyFivePointFour() {
      // (58 x 58 x 42) / (10 x 18 x 12) = 141288 / 2160 = 65.4111111
      // Revision 3 of the design carried 34.126:1, which is 1.917x wrong; the whole gain block
      // was rescaled for it, and this is the assertion that keeps the two in step.
      assertEquals(65.4111111, build().units().rotorPerOutput(), 1e-7);
      assertEquals(kReduction, build().units().rotorPerOutput(), 1e-12);
      assertTrue(build().reduction().describe().contains("(58:10) x (58:18) x (42:12)"));
    }

    @Test
    @DisplayName("the fused CANcoder's ratios reproduce the declared reduction exactly")
    void feedbackRatiosAgreeWithTheReduction() {
      // D2b: rotorPerSensor x sensorPerOutput must equal reduction.rotorPerOutput().
      // The encoder is ON the joint, so sensorPerOutput is 1.0 and rotorPerSensor is the lot.
      FeedbackSpec feedback = build().feedback();
      assertEquals(kReduction, feedback.rotorPerSensor(), 1e-12);
      assertEquals(1.0, feedback.sensorPerOutput(), 0.0);
      assertEquals(
          build().units().rotorPerOutput(),
          feedback.rotorPerSensor() * feedback.sensorPerOutput(),
          1e-12);
      assertTrue(feedback.isAbsolute());
      assertTrue(feedback.isFusedOnDevice());
      // ... so Validation raises nothing about them.
      assertTrue(
          build().errors().stream().noneMatch(e -> e.field().contains("feedback")),
          build().errors().toString());
    }

    @Test
    @DisplayName("user units are DEGREES and SI units are radians")
    void unitsAreDegreesOverRadians() {
      MechanismUnits units = build().units();
      assertEquals("deg", units.unitLabel());
      assertEquals("rad", units.siLabel());
      assertEquals(360.0, units.userPerOutputRotation(), 0.0);
      assertEquals(2.0 * Math.PI, units.siPerOutputRotation(), 0.0);
      assertFalse(build().isLinear());
    }

    @Test
    @DisplayName("the soft limits are -0.0417 .. 0.2917 output rotations")
    void softLimitsInRotations() {
      MechanismUnits units = build().units();
      assertEquals(-15.0 / 360.0, units.toOutputRotations(-15.0), 1e-15);
      assertEquals(105.0 / 360.0, units.toOutputRotations(105.0), 1e-15);
      assertEquals(-0.0416667, units.toOutputRotations(-15.0), 1e-7);
      assertEquals(0.2916667, units.toOutputRotations(105.0), 1e-7);
    }

    @Test
    @DisplayName("cosine gravity and its horizontal reference come from the axis")
    void cosineGravityWithAHorizontalReference() {
      PositionConfig arm = build();
      assertEquals(GravityMode.COSINE, arm.control().gravity());
      assertEquals(0.0, arm.axis().horizontalReference(), 0.0);
      assertEquals(0.0, arm.units().horizontalReferenceSi(), 0.0);
      // Phoenix's GravityArmPositionOffset is the reference NEGATED, in rotations.
      assertEquals(-0.0, arm.snapshot().gravityArmPositionOffsetRot(), 0.0);
    }

    @Test
    @DisplayName("the device-side kV is the SI kV times 2*pi — design/01 §5.5's 7.85")
    void deviceGainsDeriveFromTheSiGains() {
      // kV 1.25 V/(rad/s) x 2*pi rad per output rotation = 7.854 V/(rot/s)
      // kP 5.0 V/rad      x 2*pi                         = 31.416 V/rot
      MechanismConfigSnapshot snapshot = build().snapshot();
      assertEquals(7.853982, snapshot.expoKvVoltsPerRps(), 1e-6);
      assertEquals(0.062832, snapshot.expoKaVoltsPerRps2(), 1e-6);
      assertEquals(31.41593, 5.0 * build().units().siPerOutputRotation(), 1e-5);
    }

    @Test
    @DisplayName("the fused absolute encoder makes position trustworthy without homing motion")
    void positionReferenceIsFusedAbsolute() {
      PositionReference reference = build().positionReference();
      assertTrue(reference.isAbsolute(), reference.describe());
      assertTrue(reference.isTrustworthyNow(), reference.describe());
      assertFalse(build().homing().needsMotion());
      assertTrue(build().homing().isTrustworthy());
    }

    @Test
    @DisplayName("the simulated arm's inertia is derived from its length and mass")
    void simulationMomentOfInertia() {
      // A uniform rod about one end: (1/3) m L^2
      //   m = 9.5 lb  = 4.309124 kg   (WPILib's Pounds factor, 0.453592)
      //   L = 21 in   = 0.53340 m
      //   (1/3) x 4.309124 x 0.53340^2 = 0.4086709 kg m^2
      SimConfig sim = build().sim();
      assertEquals(0.4086709, sim.moiKgM2(), 1e-7);
      assertEquals(9.5 * 0.453592 * 0.5334 * 0.5334 / 3.0, sim.moiKgM2(), 1e-9);
      // armLength() is the CENTRE OF MASS distance, half the physical length — it is what the
      // gravity torque is computed from, and it is the 10.5 in of design/01 §5.5's derivation.
      assertEquals(0.26670, sim.armLengthMeters(), 1e-9);
      assertEquals(Math.toRadians(95.0), sim.startingPositionSi(), 1e-12);
      assertTrue(sim.simulateGravity());
    }

    @Test
    @DisplayName("the CANcoder joins the leader on the CAN device list")
    void canDevicesIncludeTheEncoder() {
      assertEquals(2, build().canDevices().size(), build().canDevices().toString());
      assertTrue(
          build().canDevices().stream().anyMatch(d -> d.deviceId() == 22),
          build().canDevices().toString());
      assertTrue(
          build().canDevices().stream().anyMatch(d -> d.deviceId() == 23),
          build().canDevices().toString());
    }

    @Test
    @DisplayName("describe() prints the tooth counts, the cosine reference and the Phoenix sign")
    void describePrintsTheDerivation() {
      String text = build().describe();
      assertTrue(text.contains("(58:10) x (58:18) x (42:12) = 65.411:1 (rotor per output)"), text);
      assertTrue(text.contains("rotary -- 1 output rotation = 360.000 deg = 6.283185 rad"), text);
      assertTrue(text.contains("travel per rotor rot 5.50365 deg"), text);
      assertTrue(text.contains("horizontal at        0.000 deg"), text);
      assertTrue(text.contains("GravityArmPositionOffset is this value NEGATED"), text);
      assertTrue(text.contains("volts-per-radian"), text);
      assertTrue(text.contains("Soft limits         [-15.000, 105.000] deg"), text);
      assertTrue(text.contains("-0.0417 .. 0.2917 output rot"), text);
      assertTrue(text.contains("kP 5.0000 V/rad"), text);
      assertTrue(text.contains("kV 1.2500 V/(rad/s)"), text);
      assertTrue(
          text.contains("ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX"),
          text);
      assertTrue(text.contains("stops the motor in FIRMWARE"), text);
      assertTrue(text.contains("Arm.STOW = 95.00 deg"), text);
    }
  }

  // ===============================================================================================
  // Builder defaults
  // ===============================================================================================

  @Nested
  @DisplayName("what the builder fills in when you do not")
  final class Defaults {

    @Test
    @DisplayName("build() never throws, even on an empty builder")
    void anEmptyBuilderStillBuilds() {
      PositionConfig empty = PositionConfig.linear("Nothing").build();
      assertTrue(empty.hasFatalError());
      assertEquals("Nothing", empty.name());
      assertEquals(Reduction.IDENTITY, empty.reduction());
      assertEquals(NeutralMode.BRAKE, empty.control().neutralMode());
      assertTrue(empty.control().gains().isUntuned());
      assertTrue(empty.setpoints().isEmpty());
    }

    @Test
    @DisplayName("current limits default from the leader motor when not stated")
    void currentLimitsDefaultFromTheMotor() {
      PositionConfig config =
          PositionConfig.linear("Elevator")
              .motor(MotorSpec.talonFX(20, "rio"))
              .softLimits(Inches.of(0.0), Inches.of(55.0))
              .build();
      // MotorModel.KRAKEN_X60 carries 80 A stator / 40 A supply.
      assertEquals(80.0, config.limits().current().statorAmps(), 0.0);
      assertEquals(40.0, config.limits().current().supplyAmps(), 0.0);
      assertEquals(CurrentLimits.defaultsFor(MotorModel.KRAKEN_X60), config.limits().current());
    }

    @Test
    @DisplayName("without soft limits the whole PositionLimits block falls back to the sentinel")
    void currentLimitsAreCarriedByTheSoftLimitBlock() {
      // Pinned deliberately, because it surprises: CurrentLimits live INSIDE PositionLimits, and
      // "soft limits were never declared" is detected by reference identity against a shared
      // sentinel. A builder with .currentLimits(...) but no .softLimits(...) therefore reports
      // the sentinel's BRUSHED_UNKNOWN defaults rather than what was typed.
      //
      // It is not worth trading away: such a config is already FATAL ("limits / not declared"),
      // so the robot is in safe mode and the current numbers never reach a device. Substituting
      // the real limits here would need a non-sentinel instance, which would silence that FATAL.
      PositionConfig noSoftLimits =
          PositionConfig.linear("Elevator")
              .motor(MotorSpec.talonFX(20, "rio"))
              .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
              .build();
      assertEquals(30.0, noSoftLimits.limits().current().statorAmps(), 0.0);
      assertTrue(
          noSoftLimits.errors().stream()
              .anyMatch(e -> e.field().equals("limits") && "not declared".equals(e.value())),
          noSoftLimits.errors().toString());
      assertTrue(noSoftLimits.hasFatalError());
    }

    @Test
    @DisplayName("the position tolerance defaults to the unit family of the axis")
    void toleranceDefaultsToTheRightDomain() {
      PositionConfig linear = PositionConfig.linear("Elevator").build();
      assertTrue(linear.control().toleranceIsLinear());
      assertEquals(ControlConfig.kDefaultLinearToleranceMeters, linear.control().toleranceUser(), 0.0);

      PositionConfig rotary = PositionConfig.rotary("Arm").build();
      assertFalse(rotary.control().toleranceIsLinear());
      assertEquals(ControlConfig.kDefaultRotaryToleranceDegrees, rotary.control().toleranceUser(), 0.0);
      // A degrees tolerance in radians is 0.01745 — the two must not be confused.
      assertEquals(Math.toRadians(1.0), rotary.control().toleranceSi(), 1e-15);
    }

    @Test
    @DisplayName("a velocity mechanism coasts by default; a position mechanism brakes")
    void neutralModeDefaultsDifferPerKind() {
      // A flywheel that brakes on disable throws its stored energy into the gearbox.
      assertEquals(
          NeutralMode.COAST,
          VelocityConfig.of("Shooter").motor(MotorSpec.talonFX(30, "rio")).build()
              .control().neutralMode());
      assertEquals(
          NeutralMode.BRAKE,
          PositionConfig.linear("Elevator").motor(MotorSpec.talonFX(20, "rio")).build()
              .control().neutralMode());
    }

    @Test
    @DisplayName("each mechanism kind names its own signal rate")
    void signalRatesPerKind() {
      assertEquals(100.0, MechanismKind.POSITION.defaultSignalRateHz(), 0.0);
      assertEquals(50.0, MechanismKind.VELOCITY.defaultSignalRateHz(), 0.0);
      assertEquals(20.0, MechanismKind.SIMPLE.defaultSignalRateHz(), 0.0);
      assertTrue(MechanismKind.POSITION.hasPositionGoals());
      assertFalse(MechanismKind.VELOCITY.hasPositionGoals());
      assertFalse(MechanismKind.SIMPLE.isClosedLoop());
    }

    @Test
    @DisplayName("a SimpleConfig needs a motor and an inertia and nothing else")
    void aSimpleMechanismBuilds() {
      SimpleConfig intake =
          SimpleConfig.of("Intake")
              .motor(MotorSpec.talonFX(31, "rio"))
              .reduction(Reduction.of(3.0))
              .currentLimits(CurrentLimits.statorOnly(Amps.of(40)))
              .heldSensor(SensorSpec.statorCurrent(Amps.of(25), Seconds.of(0.2)))
              .sim(edu.wpi.first.units.Units.KilogramSquareMeters.of(0.002))
              .build();
      assertEquals(List.of(), intake.errors(), intake.errors().toString());
      assertFalse(intake.hasFatalError());
      assertEquals(MechanismKind.SIMPLE, intake.kind());
      assertTrue(intake.hasHeldSensor());
      assertEquals(NeutralMode.BRAKE, intake.neutralMode());
    }

    @Test
    @DisplayName("a name is never blank, however it was given")
    void namesAreAlwaysUsable() {
      assertEquals("(unnamed mechanism)", PositionConfig.linear(null).build().name());
      assertEquals("(unnamed mechanism)", PositionConfig.linear("   ").build().name());
      assertEquals("Elevator", PositionConfig.linear("  Elevator  ").build().name());
    }

    @Test
    @DisplayName("a linear builder with no axis still produces metres, not a crash")
    void anUndeclaredAxisIsStillMeasurable() {
      PositionConfig config = PositionConfig.linear("Elevator").build();
      assertEquals("m", config.units().unitLabel());
      assertEquals(Meters.of(0.0).in(Meters), config.travel().min(), 0.0);
      assertTrue(
          config.errors().stream()
              .anyMatch(e -> e.field().equals("axis") && "not declared".equals(e.value())),
          config.errors().toString());
    }
  }
}

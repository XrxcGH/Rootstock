package org.rootstock.sim;

import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Kilograms;
import static edu.wpi.first.units.Units.Meters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.wpilibj.simulation.ElevatorSim;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.config.SimConfig;
import org.rootstock.core.spi.MechanismGeometry;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;

/**
 * A plant built from a {@link MechanismGeometry} and a {@link SimConfig} moves through the
 * <strong>real</strong> {@link MechanismUnits} ratio path — and a wrong gearing is visibly wrong.
 *
 * <h2>Why the ratio path is the thing worth testing</h2>
 *
 * <p>The simulation seam is at the <em>device</em>, not at the plant: the plant reads the volts the
 * motor controller is actually commanding and writes the simulated <b>ROTOR</b> state back, so the
 * controller's own {@code SensorToMechanismRatio} — the single most commonly misconfigured number on
 * a robot — is under test rather than bypassed. That writeback is one multiply by
 * {@code rotorPerOutput} and one divide by {@code siPerOutputRotation}, and getting either wrong
 * produces a simulation that agrees with itself perfectly while disagreeing with the robot. There is
 * no symptom; the mechanism is just the wrong speed, and a team looks at gains for a week.
 *
 * <p>So the assertions below never compare the plant against another call into the same converter.
 * The expected rotor-per-metre figure is computed from the declared tooth count and gear ratio
 * independently, and then the converter, the geometry and the plant all have to agree with it.
 *
 * <h2>The mechanism</h2>
 *
 * <p>9143-2025-A's elevator, because its numbers are already worked out in {@code LinearAxis}'s own
 * javadoc: a #25 chain (0.25 in pitch) over a 22-tooth sprocket, two cascade stages. One output
 * rotation advances the chain {@code 22 * 0.25 in = 5.5 in} and the cascade doubles it, so the
 * carriage travels {@code 2 * 5.5 in = 11 in = 0.2794 m} per output rotation.
 */
final class RootstockSimTest {

  /** #25 chain pitch. */
  private static final double kChainPitchMeters = 0.25 * 0.0254;

  private static final int kSprocketTeeth = 22;
  private static final int kCascadeStages = 2;

  /** 22 teeth x 0.25 in x 2 stages = 11 in = 0.2794 m of carriage travel per output rotation. */
  private static final double kTravelPerOutputRotation =
      kSprocketTeeth * kChainPitchMeters * kCascadeStages;

  private static final double kRightGearing = 9.0;

  /** A missing decade in the gearbox: the classic "I typed the stage ratios wrong" mistake. */
  private static final double kWrongGearing = 90.0;

  private static final double kCarriageMassKg = 6.0;
  private static final double kTravelTopMeters = 1.5;
  private static final double kStepSeconds = 0.02;

  private static final LinearAxis kAxis =
      LinearAxis.sprocket(Inches.of(0.25), kSprocketTeeth, kCascadeStages);

  @BeforeEach
  void freshRegistry() {
    RootstockSim.resetForTest();
  }

  @AfterEach
  void clearRegistry() {
    RootstockSim.resetForTest();
  }

  // ===============================================================================================
  // Fixtures
  // ===============================================================================================

  private static MechanismUnits units(double rotorPerOutput) {
    return MechanismUnits.of(Reduction.of(rotorPerOutput), kAxis);
  }

  private static SimConfig simConfig() {
    return SimConfig.linear(Kilograms.of(kCarriageMassKg), Meters.of(0.0));
  }

  /** The geometry core would hand the sink, built from the same units and SimConfig a mechanism has. */
  private static MechanismGeometry geometry(String name, double rotorPerOutput, MechanismUnits u) {
    SimConfig sim = simConfig();
    return new MechanismGeometry(
        name,
        MechanismGeometry.Kind.LINEAR,
        DCMotor.getKrakenX60Foc(2),
        rotorPerOutput,
        u.siPerOutputRotation(),
        u.siPerOutputRotation() / (2.0 * Math.PI),
        sim.massKg(),
        Double.NaN,
        Double.NaN,
        0.0,
        kTravelTopMeters,
        sim.startingPositionSi(),
        true);
  }

  /** Holds the commanded volts and records the rotor state the plant writes back. */
  private static final class RecordingDevice implements SimMotorHandle {
    private double m_volts;
    private double m_rotorRotations;
    private double m_rotorRps;
    private int m_writes;

    RecordingDevice(double volts) {
      m_volts = volts;
    }

    @Override
    public double appliedVolts(double busVoltage) {
      return m_volts;
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      m_rotorRotations = rotorRotations;
      m_rotorRps = rotorRps;
      m_writes++;
    }

    @Override
    public double statorAmps() {
      return 0.0;
    }
  }

  /** Steps one plant at a fixed bus voltage, isolating the ratio question from battery sag. */
  private static void run(MechanismSim sim, int steps) {
    for (int i = 0; i < steps; i++) {
      sim.setBusVolts(RootstockSim.kNominalBusVolts);
      sim.update(kStepSeconds);
    }
  }

  // ===============================================================================================

  @Nested
  @DisplayName("the geometry the declaration produces")
  final class Declaration {

    /** Every downstream number rests on this one, so it is derived from the tooth count here. */
    @Test
    void oneOutputRotationIsElevenInchesOfCarriageTravel() {
      assertEquals(0.2794, kTravelPerOutputRotation, 1.0e-12);
      assertEquals(kTravelPerOutputRotation, kAxis.siPerOutputRotation(), 1.0e-12);
      assertEquals(kTravelPerOutputRotation, kAxis.userPerOutputRotation(), 1.0e-12);
      assertEquals(
          kTravelPerOutputRotation, units(kRightGearing).siPerOutputRotation(), 1.0e-12);
    }

    /**
     * The effective radius and the travel per rotation are two spellings of {@code travel = 2*pi*r}.
     * {@code LinearAxis}'s javadoc gives 0.0444679 m for this elevator; that is the number the plant
     * derives.
     */
    @Test
    void theEffectiveRadiusIsTheTravelDividedByTwoPi() {
      assertEquals(0.0444679, kTravelPerOutputRotation / (2.0 * Math.PI), 1.0e-7);
    }

    @Test
    void aLinearAxisNeedsNoSiConversionBecauseMetresAreAlreadySi() {
      MechanismUnits u = units(kRightGearing);

      assertEquals(1.25, u.toSi(1.25), 0.0);
      assertEquals(1.25, u.fromSi(1.25), 0.0);
      assertEquals("m", u.unitLabel());
    }
  }

  @Nested
  @DisplayName("the plant moves through the real MechanismUnits ratio path")
  final class RatioPath {

    /**
     * Rotor rotations per metre of carriage travel is {@code rotorPerOutput / travelPerRotation} =
     * {@code 9 / 0.2794} = 32.2119. Computed here from the declaration, not read off the converter,
     * so the converter has to agree with the declaration and not merely with itself.
     */
    @Test
    void theConverterTurnsMetresIntoRotorRotationsAtTheDeclaredRatio() {
      MechanismUnits u = units(kRightGearing);
      double expectedPerMetre = kRightGearing / kTravelPerOutputRotation;

      assertEquals(32.211882, expectedPerMetre, 1.0e-6, "9 / 0.2794, restated");
      assertEquals(expectedPerMetre, u.toRotorRotations(u.fromSi(1.0)), 1.0e-9);
      assertEquals(expectedPerMetre * 0.5, u.toRotorRotations(u.fromSi(0.5)), 1.0e-9);
    }

    /**
     * And the plant writes that same ratio back to the device, every cycle, for position and for
     * velocity. Twenty steps of 20 ms: the carriage is moving freely and has not yet reached the top
     * soft limit, so both numbers are non-trivial.
     */
    @Test
    void thePlantWritesRotorStateAtTheDeclaredRatio() {
      MechanismUnits u = units(kRightGearing);
      RecordingDevice device = new RecordingDevice(12.0);
      MechanismSim sim = RootstockSim.declare(geometry("Elevator", kRightGearing, u), u, device);

      run(sim, 20);

      double expectedPerMetre = kRightGearing / kTravelPerOutputRotation;
      assertEquals(20, device.m_writes, "the rotor state is written once per update, every update");
      assertTrue(sim.positionSi() > 0.5, "the carriage should have moved: " + sim.positionSi());
      assertTrue(
          sim.positionSi() < kTravelTopMeters,
          "and must not have hit the top soft limit yet: " + sim.positionSi());

      assertEquals(
          sim.positionSi() * expectedPerMetre,
          device.m_rotorRotations,
          1.0e-9,
          "the device must see ROTOR rotations, so its own ratio conversion is exercised");
      assertEquals(sim.velocitySi() * expectedPerMetre, device.m_rotorRps, 1.0e-9);
    }

    /**
     * The plant reaches the free speed the declaration implies:
     * {@code freeSpeedRadPerSec / (2*pi*gearing) * travelPerRotation}. Within 2%, which is the
     * difference between an ideal free speed and the plant's steady state under gravity and the
     * motor's own no-load current.
     */
    @Test
    void theCarriageSettlesAtTheFreeSpeedTheGearingImplies() {
      MechanismUnits u = units(kRightGearing);
      MechanismSim sim =
          RootstockSim.declare(
              geometry("Elevator", kRightGearing, u), u, new RecordingDevice(12.0));

      run(sim, 15);

      double freeSpeed = freeSpeedMetersPerSec(kRightGearing);
      assertEquals(3.0009630, freeSpeed, 1.0e-6, "607.3746 rad/s / (2*pi*9) * 0.2794 m");
      assertEquals(
          freeSpeed, sim.velocitySi(), 0.02 * freeSpeed, "steady state within 2% of free speed");
    }
  }

  @Nested
  @DisplayName("a wrong gearing is visibly wrong")
  final class WrongGearing {

    /**
     * Ten times the gearing is ten times the rotor rotations per metre — 322.12 instead of 32.21. A
     * device configured with the right ratio and fed this would report the carriage ten times higher
     * than it is, which is a soft limit that fires at 0.15 m.
     */
    @Test
    void tenTimesTheGearingIsTenTimesTheReportedRotorPosition() {
      RecordingDevice right = new RecordingDevice(12.0);
      RecordingDevice wrong = new RecordingDevice(12.0);
      MechanismUnits rightUnits = units(kRightGearing);
      MechanismUnits wrongUnits = units(kWrongGearing);

      MechanismSim rightSim =
          RootstockSim.declare(geometry("Right", kRightGearing, rightUnits), rightUnits, right);
      MechanismSim wrongSim =
          RootstockSim.declare(geometry("Wrong", kWrongGearing, wrongUnits), wrongUnits, wrong);

      run(rightSim, 20);
      run(wrongSim, 20);

      assertEquals(
          32.211882, right.m_rotorRotations / rightSim.positionSi(), 1.0e-5, "9 / 0.2794");
      assertEquals(
          322.118825, wrong.m_rotorRotations / wrongSim.positionSi(), 1.0e-4, "90 / 0.2794");
      assertEquals(
          kWrongGearing / kRightGearing,
          (wrong.m_rotorRotations / wrongSim.positionSi())
              / (right.m_rotorRotations / rightSim.positionSi()),
          1.0e-6,
          "the error is exactly the gearing error — nothing else absorbs it");
    }

    /**
     * And the carriage physically behaves differently, which is what makes the mistake findable in
     * {@code simulateJava} rather than at an event. Free speed scales as {@code 1 / gearing}, so ten
     * times the gearing is roughly a tenth of the travel in the same window; the ratio is a little
     * under ten because the faster plant spends proportionally more of the window accelerating.
     */
    @Test
    void theCarriageTravelsAboutATenthAsFar() {
      MechanismUnits rightUnits = units(kRightGearing);
      MechanismUnits wrongUnits = units(kWrongGearing);
      MechanismSim rightSim =
          RootstockSim.declare(
              geometry("Right", kRightGearing, rightUnits), rightUnits, new RecordingDevice(12.0));
      MechanismSim wrongSim =
          RootstockSim.declare(
              geometry("Wrong", kWrongGearing, wrongUnits), wrongUnits, new RecordingDevice(12.0));

      run(rightSim, 20);
      run(wrongSim, 20);

      assertEquals(
          freeSpeedMetersPerSec(kWrongGearing),
          wrongSim.velocitySi(),
          0.02 * freeSpeedMetersPerSec(kWrongGearing));
      assertEquals(0.30009630, freeSpeedMetersPerSec(kWrongGearing), 1.0e-7);

      double ratio = rightSim.positionSi() / wrongSim.positionSi();
      assertTrue(
          ratio > 8.0 && ratio < 12.0,
          () ->
              "a 10x gearing error should show up as roughly a 10x travel difference, but the two"
                  + " plants travelled "
                  + rightSim.positionSi()
                  + " m and "
                  + wrongSim.positionSi()
                  + " m (ratio "
                  + ratio
                  + ")");
    }

    /**
     * A declared effective radius that contradicts the declared travel per rotation is reported by
     * name at construction. Halving the radius is exactly what a forgotten cascade stage looks like,
     * and the plant is built from the travel, so the disagreement is 50%.
     */
    @Test
    void aRadiusThatContradictsTheTravelIsReportedByName() {
      MechanismUnits u = units(kRightGearing);
      MechanismGeometry declared = geometry("Elevator", kRightGearing, u);
      MechanismGeometry halfRadius =
          new MechanismGeometry(
              declared.name(),
              declared.kind(),
              declared.gearbox(),
              declared.rotorPerOutput(),
              declared.siPerOutputRotation(),
              declared.effectiveRadiusMeters() / 2.0,
              declared.massKg(),
              declared.momentOfInertiaKgM2(),
              declared.armLengthMeters(),
              declared.siMin(),
              declared.siMax(),
              declared.siStart(),
              declared.simulateGravity());

      List<String> problems = problemsOf(RootstockSim.declare(halfRadius, u));

      assertEquals(1, problems.size(), problems.toString());
      assertTrue(problems.get(0).contains("a 50.0% disagreement"), problems.get(0));
      assertTrue(problems.get(0).contains("a missing stage is exactly a factor of two"), problems.get(0));
    }

    /**
     * A converter and a geometry that disagree about the gearbox means two different declarations
     * reached one mechanism — which otherwise shows up only as a simulation that is subtly the wrong
     * speed.
     */
    @Test
    void aConverterThatDisagreesWithTheGeometryIsReportedByName() {
      MechanismUnits converterSaysNine = units(kRightGearing);
      MechanismGeometry geometrySaysNinety =
          geometry("Elevator", kWrongGearing, converterSaysNine);

      List<String> problems =
          problemsOf(RootstockSim.declare(geometrySaysNinety, converterSaysNine));

      assertEquals(1, problems.size(), problems.toString());
      assertTrue(
          problems.get(0).contains("9.000000 rotor rotations per output rotation"),
          problems.get(0));
      assertTrue(problems.get(0).contains("90.000000"), problems.get(0));
    }

    /** A fully consistent declaration reports nothing at all. */
    @Test
    void aConsistentDeclarationHasNoProblems() {
      MechanismUnits u = units(kRightGearing);

      assertEquals(
          List.of(), problemsOf(RootstockSim.declare(geometry("Elevator", kRightGearing, u), u)));
    }
  }

  @Nested
  @DisplayName("the plant that is built")
  final class PlantChoice {

    @Test
    void aLinearGeometryBecomesAnElevatorSim() {
      MechanismUnits u = units(kRightGearing);
      MechanismSim sim = RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);

      assertInstanceOf(ElevatorSim.class, sim.raw());
      assertEquals("Elevator", sim.name());
    }

    /** Registration is by name and is idempotent, so a test that builds a mechanism twice is fine. */
    @Test
    void registrationIsByNameAndReplaces() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);

      assertEquals(1, RootstockSim.registered().size());
      assertTrue(RootstockSim.get("Elevator").isPresent());
    }

    /** The soft limits are the plant's limits: the carriage stops at the top, it does not fly off. */
    @Test
    void theCarriageStopsAtTheDeclaredTopSoftLimit() {
      MechanismUnits u = units(kRightGearing);
      MechanismSim sim =
          RootstockSim.declare(
              geometry("Elevator", kRightGearing, u), u, new RecordingDevice(12.0));

      run(sim, 100);

      assertEquals(kTravelTopMeters, sim.positionSi(), 1.0e-9);
      assertEquals(0.0, sim.velocitySi(), 1.0e-9);
    }
  }

  @Nested
  @DisplayName("the peak-stall-current boot line")
  final class StressTest {

    /**
     * <strong>The line that turns an event-day discovery into a week-two one.</strong> A team that
     * has specified two Kraken X60s on an elevator has specified {@code 2 * 483 = 966 A} of stall
     * current and nothing on the robot tells them: the motors are in CAD, the limits are in a config
     * file, and the first evidence is a brownout in a match. The arithmetic needs no sensors and no
     * timestep, so it runs at boot.
     */
    @Test
    void thePeakIsTheSumOfEveryGearboxesStallCurrent() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);

      StressReport report = RootstockSim.stressTest();

      double oneGearbox = DCMotor.getKrakenX60Foc(2).stallCurrentAmps;
      assertEquals(966.0, oneGearbox, 1.0e-9, "two Kraken X60 FOC at 483 A stall each");
      assertEquals(oneGearbox, report.peakStallAmps(), 1.0e-9);
      assertEquals(1, report.mechanisms().size());

      StressReport.Entry entry = report.mechanisms().get(0);
      assertEquals("Elevator", entry.name());
      assertEquals(MechanismGeometry.Kind.LINEAR, entry.kind());
      assertEquals(oneGearbox, entry.stallAmps(), 1.0e-9);
      assertEquals(
          DCMotor.getKrakenX60Foc(2).stallTorqueNewtonMeters,
          entry.stallTorqueNewtonMeters(),
          1.0e-9);
    }

    /** Two mechanisms sum, in registration order. */
    @Test
    void everyRegisteredMechanismContributes() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);
      RootstockSim.declare(geometry("Arm", kRightGearing, u), u);

      StressReport report = RootstockSim.stressTest();

      assertEquals(
          2 * DCMotor.getKrakenX60Foc(2).stallCurrentAmps, report.peakStallAmps(), 1.0e-9);
      assertEquals(
          List.of("Elevator", "Arm"),
          report.mechanisms().stream().map(StressReport.Entry::name).toList());
    }

    /**
     * The predicted sag is WPILib's default battery model: 12 V nominal, 0.02 ohm, clamped at zero.
     * At 966 A that is {@code 12 - 19.32 = -7.32}, i.e. zero — the bus does not sag, it collapses.
     */
    @Test
    void theSagIsPredictedAndTheBrownoutIsCalled() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);

      StressReport report = RootstockSim.stressTest();

      assertEquals(
          Math.max(0.0, 12.0 - 0.02 * report.peakStallAmps()),
          report.predictedBusVolts(),
          1.0e-9);
      assertEquals(0.0, report.predictedBusVolts(), 1.0e-9);
      assertTrue(report.predictsBrownout());
      assertTrue(report.predictedBusVolts() < RootstockSim.kBrownoutVolts);
    }

    /**
     * The boot line itself, exactly as {@code printBootReport()} prints it. That method also raises
     * the declaration and unattached-device alerts, which need NetworkTables and therefore live in
     * {@code RootstockSimHalTest}; the line is produced here.
     */
    @Test
    void theBootLineNamesThePeakAndTheSagAndTheThreshold() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(geometry("Elevator", kRightGearing, u), u);

      StressReport report = RootstockSim.stressTest();
      String line = report.describe();

      assertTrue(
          line.contains("RootstockSim stress test — every mechanism stalled simultaneously"), line);
      assertTrue(
          line.contains(
              String.format(
                  Locale.ROOT,
                  "PEAK SIMULATED STALL CURRENT %.0f A -> bus sags to %.2f V",
                  report.peakStallAmps(),
                  report.predictedBusVolts())),
          line);
      assertTrue(line.contains("PEAK SIMULATED STALL CURRENT 966 A -> bus sags to 0.00 V"), line);
      assertTrue(
          line.contains(
              String.format(Locale.ROOT, "BELOW the %.2f V brownout threshold", RootstockSim.kBrownoutVolts)),
          line);
      assertTrue(line.contains("Elevator"), line);
    }

    /** A robot with headroom gets the same line without the brownout clause. */
    @Test
    void aSmallRobotIsNotAccusedOfBrowningOut() {
      MechanismUnits u = units(kRightGearing);
      MechanismGeometry declared = geometry("Roller", kRightGearing, u);
      MechanismGeometry oneSmallMotor =
          new MechanismGeometry(
              declared.name(),
              declared.kind(),
              DCMotor.getNeo550(1),
              declared.rotorPerOutput(),
              declared.siPerOutputRotation(),
              declared.effectiveRadiusMeters(),
              declared.massKg(),
              declared.momentOfInertiaKgM2(),
              declared.armLengthMeters(),
              declared.siMin(),
              declared.siMax(),
              declared.siStart(),
              declared.simulateGravity());
      RootstockSim.declare(oneSmallMotor, u);

      StressReport report = RootstockSim.stressTest();

      assertEquals(DCMotor.getNeo550(1).stallCurrentAmps, report.peakStallAmps(), 1.0e-9);
      assertFalse(report.predictsBrownout());
      assertTrue(report.describe().endsWith(" V."), report.describe());
    }

    /** Nothing declared: the report says so rather than printing an empty table. */
    @Test
    void anEmptyRegistryReportsThatNothingWasDeclared() {
      StressReport report = RootstockSim.stressTest();

      assertEquals(0.0, report.peakStallAmps(), 0.0);
      assertTrue(report.describe().contains("no mechanisms registered"), report.describe());
    }
  }

  @Nested
  @DisplayName("the battery, stepped")
  final class Battery {

    /**
     * {@code stepPlants} is the HAL-free half of {@code tick()}, and this is why it exists: the
     * physics and the battery model run on a bare JVM. A freely running elevator draws single-digit
     * amps, so the bus barely moves.
     */
    @Test
    void aFreelyRunningElevatorBarelyLoadsTheBus() {
      MechanismUnits u = units(kRightGearing);
      RootstockSim.declare(
          geometry("Elevator", kRightGearing, u), u, new RecordingDevice(12.0));

      double volts = RootstockSim.stepPlants(kStepSeconds);

      assertEquals(
          Math.max(0.0, 12.0 - 0.02 * RootstockSim.totalCurrentAmps()), volts, 1.0e-9);
      assertEquals(volts, RootstockSim.busVolts(), 0.0);
      assertFalse(RootstockSim.isBrownedOut());
      assertTrue(RootstockSim.totalCurrentAmps() > 0.0);
    }

    /**
     * Driven into the top soft limit the plant stalls, the current goes to the gearbox's stall
     * figure, and the bus collapses — the simulated version of the failure the stress test predicts
     * analytically.
     */
    @Test
    void anElevatorStalledAgainstItsTopLimitBrownsTheBusOut() {
      MechanismUnits u = units(kRightGearing);
      MechanismSim sim =
          RootstockSim.declare(
              geometry("Elevator", kRightGearing, u), u, new RecordingDevice(12.0));

      for (int i = 0; i < 60; i++) {
        RootstockSim.stepPlants(kStepSeconds);
      }

      assertEquals(kTravelTopMeters, sim.positionSi(), 1.0e-9);
      assertTrue(RootstockSim.isBrownedOut(), "bus was " + RootstockSim.busVolts() + " V");
      assertTrue(RootstockSim.busVolts() < RootstockSim.kBrownoutVolts);
    }
  }

  // ===============================================================================================

  /**
   * Free speed at the carriage, in metres per second, from the motor's free speed and the
   * declaration alone: {@code rotorRadPerSec / (2*pi*gearing)} output rotations per second, times the
   * travel per output rotation.
   */
  private static double freeSpeedMetersPerSec(double gearing) {
    return DCMotor.getKrakenX60Foc(2).freeSpeedRadPerSec
        / (2.0 * Math.PI * gearing)
        * kTravelPerOutputRotation;
  }

  /** The declaration problems a plant reported at construction. */
  private static List<String> problemsOf(MechanismSim sim) {
    return assertInstanceOf(WpilibPlantSim.class, sim).problems();
  }
}

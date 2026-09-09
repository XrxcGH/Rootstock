package org.rootstock.example;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.KilogramSquareMeters;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Rotations;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.Follower;
import org.rootstock.config.HomingStrategy;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorModel;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionConfig;
import org.rootstock.config.SensorSpec;
import org.rootstock.config.Setpoint;
import org.rootstock.config.SimConfig;
import org.rootstock.config.SimpleConfig;
import org.rootstock.config.SparkModel;
import org.rootstock.control.Gains;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.RotaryAxis;

/**
 * {@code design/01-core-mechanisms.md} section 9.1, reproduced against the real API.
 *
 * <p>This is the milestone's proof object: three mechanism configs and the typed setpoint handles
 * that go with them, written the way a team writes them. Everything below is either identical to the
 * design snippet or annotated with the exact way the shipped API differs from it.
 *
 * <h2>Where this file departs from section 9.1, and why</h2>
 *
 * <ol>
 *   <li><b>Import packages.</b> The snippet imports {@code org.rootstock.hardware.ControlLocation}
 *       and {@code org.rootstock.mechanism.HomingStrategy}. Both moved: {@code ControlLocation} is
 *       in {@code org.rootstock.control} and {@code HomingStrategy} is in {@code
 *       org.rootstock.config}. {@code Reduction} is in {@code org.rootstock.pure.units}, not
 *       {@code org.rootstock.units}, which the snippet's wildcard hid.
 *   <li><b>{@code SAFETY} cannot live here.</b> The snippet declares {@code SafetyModel.over(ELEVATOR,
 *       ARM)} in this file, passing the two {@code PositionConfig}s. The shipped
 *       {@code SafetyModel.over} takes two <b>{@code Mechanism}</b>s, because it reads their live
 *       measured positions. So the safety model moves to {@link RobotContainer}, after the mechanisms
 *       exist. This is the one structural difference in the whole example.
 *   <li><b>Simulation motor specs.</b> {@code MotorIOFactory} routes a {@code talonFX} spec to the
 *       Phoenix&nbsp;6 adapter in <i>every</i> mode including simulation — there is no automatic
 *       substitution of a simulated backend for a vendor spec. That is correct for a robot project,
 *       which has the vendor artifact on its classpath, but this test source set does not, so the
 *       vendor specs would yield {@code NoOpMotorIO} and nothing would move. {@link #simulated()}
 *       returns the same three configs with {@code MotorSpec.sim(...)} leaders and every other field
 *       — gearing, limits, gains, setpoints, homing, plant — untouched.
 * </ol>
 */
public final class RobotConfig {

  // ===============================================================================================
  // Section 9.1, verbatim in intent
  // ===============================================================================================

  /** The design's elevator: two Krakens, 12:1, a 22-tooth 0.25 in sprocket cascaded twice. */
  public static final PositionConfig ELEVATOR =
      PositionConfig.linear("Elevator")
          // .foc(true) = FOC on the voltage requests. Gains stay VOLTS-per-SI.
          .motors(
              MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                  .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
          .reduction(Reduction.ofStages(3.0, 4.0)) // 12:1
          .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2)) // 22 x 0.25 in x 2 = 0.279400 m/rot
          .feedback(new FeedbackSpec.RotorOnly())
          .softLimits(Inches.of(0.0), Inches.of(55.0))
          .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
          // ControlLocation omitted -> defaults to ON_MOTOR_PROFILED (TalonFX leader).
          // Gains: kP V/m, kD V/(m/s), kS V, kV V/(m/s), kA V/(m/s^2), kG V.
          .gains(
              Gains.realOrSim(
                  Gains.pid(80.0, 0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33),
                  Gains.pid(150.0, 0, 0).withKv(5.00).withKa(0.06).withKg(0.33)))
          .constraints(MotionConstraints.of(1.6, 6.0)) // 71% of free speed -- no alert
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
          .setpoint("STOW", Inches.of(0))
          .setpoint("L2", Inches.of(20.5))
          .setpoint("L3", Inches.of(37.5))
          .setpoint("L4", Inches.of(52.5))
          .sim(Pounds.of(24.0), Inches.of(0.0))
          .build();

  /** The design's arm: one Kraken through 58/10 x 58/18 x 42/12, fused to a CANcoder. */
  public static final PositionConfig ARM =
      PositionConfig.rotary("Arm")
          .motors(MotorGroup.leader(MotorSpec.talonFX(22, "rio").inverted(true)))
          // ofTeeth(drivenTeeth, drivingTeeth): 58/10 x 58/18 x 42/12 = 65.411:1
          .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
          .axis(RotaryAxis.arm(Degrees.of(0.0))) // |horizontalAt| <= 90 deg
          // D2b identity: rotorPerSensor x sensorPerOutput == 65.411 x 1.0 == reduction
          .feedback(new FeedbackSpec.FusedCancoder(23, "rio", Rotations.of(-0.1387), 65.411, 1.0))
          .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
          .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
          // Gains: kP V/rad, kD V/(rad/s), kV V/(rad/s), kA V/(rad/s^2), kS and kG volts.
          .gains(
              Gains.realOrSim(
                  Gains.pid(5.0, 0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29),
                  Gains.pid(10.0, 0, 0).withKv(1.25).withKa(0.010).withKg(0.29)))
          .constraints(MotionConstraints.of(180.0, 540.0)) // deg/s, deg/s^2
          .tolerance(Degrees.of(1.5), 5.0, 0.06)
          .manualControl(0.10, 0.20)
          .homing(HomingStrategy.absoluteSeed())
          .setpoint("STOW", Degrees.of(95))
          .setpoint("INTAKE", Degrees.of(-10))
          .setpoint("SCORE", Degrees.of(35))
          .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95)))
          .build();

  /** The design's roller: one NEO 550 behind a CANrange that reports "a piece is in there". */
  public static final SimpleConfig ROLLER =
      SimpleConfig.of("Roller")
          .motors(MotorGroup.leader(MotorSpec.spark(24, SparkModel.MAX_NEO550)))
          .reduction(Reduction.of(4.0))
          .currentLimits(CurrentLimits.of(Amps.of(30), Amps.of(20)))
          .heldSensor(SensorSpec.canRange(25, "rio", Meters.of(0.06)))
          .sim(KilogramSquareMeters.of(0.001))
          .build();

  // ---- typed setpoint handles: compiler-checked, and the documented default ----

  /** Elevator down. */
  public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");

  /** Elevator at the second scoring level. */
  public static final Setpoint ELEVATOR_L2 = ELEVATOR.setpoint("L2");

  /** Elevator at the third scoring level. */
  public static final Setpoint ELEVATOR_L3 = ELEVATOR.setpoint("L3");

  /** Elevator at the fourth scoring level. */
  public static final Setpoint ELEVATOR_L4 = ELEVATOR.setpoint("L4");

  /** Arm tucked. */
  public static final Setpoint ARM_STOW = ARM.setpoint("STOW");

  /** Arm down at the intake. */
  public static final Setpoint ARM_INTAKE = ARM.setpoint("INTAKE");

  /** Arm out to score. */
  public static final Setpoint ARM_SCORE = ARM.setpoint("SCORE");

  // ===============================================================================================
  // The simulation variant — same numbers, a backend this source set can actually build
  // ===============================================================================================

  /**
   * The same three configs with simulated leaders.
   *
   * <p>Only {@code motors(...)} changes. Gearing, axis, soft limits, current limits, gains,
   * constraints, tolerances, setpoints, homing strategy and the declared plant are the ones above,
   * so a test that drives these is testing the design's mechanism and not a simplified stand-in.
   *
   * @return the elevator, arm and roller, in that order
   */
  public static Simulated simulated() {
    return new Simulated(
        ELEVATOR.withMotors(
            MotorGroup.leader(MotorSpec.sim("Elevator", MotorModel.KRAKEN_X60))
                .follower(MotorSpec.sim("ElevatorFollower", MotorModel.KRAKEN_X60),
                    Follower.OPPOSED)),
        // The arm's fused CANcoder is a device the sim backend does not have, so the feedback spec
        // falls back to the rotor. The homing strategy is switched with it: an absoluteSeed with no
        // absolute source would leave the arm permanently unhomed, which is a correct refusal but a
        // useless fixture. assumeAtBoot(95 deg) matches the declared sim starting position.
        ARM.withMotors(MotorGroup.leader(MotorSpec.sim("Arm", MotorModel.KRAKEN_X60)))
            .withFeedback(new FeedbackSpec.RotorOnly())
            .withHoming(HomingStrategy.assumeAtBoot(Degrees.of(95.0))),
        ROLLER.withMotors(MotorGroup.leader(MotorSpec.sim("Roller", MotorModel.NEO_550))));
  }

  /**
   * The three simulated configs, held together so a caller cannot pair an elevator with the wrong
   * arm.
   *
   * @param elevator the elevator config with a simulated leader and follower
   * @param arm the arm config with a simulated leader, a rotor reference and a boot assumption
   * @param roller the roller config with a simulated leader
   */
  public record Simulated(PositionConfig elevator, PositionConfig arm, SimpleConfig roller) {}

  private RobotConfig() {}
}

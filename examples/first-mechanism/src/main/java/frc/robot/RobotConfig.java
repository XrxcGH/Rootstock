package frc.robot;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.HomingStrategy;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionConfig;
import org.rootstock.config.Setpoint;
import org.rootstock.control.Gains;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;

/**
 * One elevator, declared as data. This is the whole mechanism: there is no second file that
 * multiplies by a gear ratio, and there is no {@code periodic()} to write.
 *
 * <p>Change the CAN IDs, the reduction, the axis and the soft limits to match your robot. Leave
 * the shape alone. Then read the block Rootstock prints to the console at boot and check the
 * travel-per-rotation line against CAD, which is the single highest-value thing you do in a first
 * session.
 *
 * <p>Two imports above are in places nobody guesses: {@code Gains} is in
 * {@code org.rootstock.control} and {@code Reduction} is in {@code org.rootstock.pure.units},
 * not next to {@code LinearAxis}. See {@code docs/concepts.md}.
 */
public final class RobotConfig {

  /**
   * A two-stage cascade elevator on one Kraken through a 12:1 gearbox and a 22-tooth number 25
   * sprocket. One output rotation is 11 inches of carriage travel, which the boot dump prints as
   * 0.279400 m so you can check it.
   */
  public static final PositionConfig ELEVATOR =
      PositionConfig.linear("Elevator")
          .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio")))
          .reduction(Reduction.ofStages(3.0, 4.0)) // 3:1 then 4:1 = 12:1
          .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2)) // #25 chain, 22 teeth, 2 stages
          .feedback(new FeedbackSpec.RotorOnly())
          .softLimits(Inches.of(0.0), Inches.of(55.0))
          .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
          // UNTUNED resolves to a physics-derived first guess in simulation, and refuses
          // closed-loop control on real hardware until the tuning wizard has run.
          .gains(Gains.UNTUNED)
          .constraints(MotionConstraints.of(1.6, 6.0)) // m/s, m/s^2
          .tolerance(Inches.of(0.5), 0.05, 0.06)
          .manualControl(0.10, 0.30)
          // Drive down at 1.5 V until the stator current holds above 30 A for 150 ms, then call
          // that position zero. The sign comes from .direction(...), not from the voltage.
          .homing(
              HomingStrategy.currentSpike()
                  .direction(HomingStrategy.Direction.REVERSE)
                  .voltage(Volts.of(-1.5))
                  .currentThreshold(Amps.of(30))
                  .debounce(Seconds.of(0.15))
                  .timeout(Seconds.of(4.0))
                  .seedTo(Inches.of(0.0)))
          .setpoint("STOW", Inches.of(0.0))
          .setpoint("L4", Inches.of(52.5))
          // Declaring the carriage mass is the only simulation code in this project.
          .sim(Pounds.of(24.0), Inches.of(0.0))
          .build();

  /** Elevator down. A handle, so a misspelling is a compile error rather than a refusal. */
  public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");

  /** Elevator at the top scoring level. */
  public static final Setpoint ELEVATOR_L4 = ELEVATOR.setpoint("L4");

  private RobotConfig() {}
}

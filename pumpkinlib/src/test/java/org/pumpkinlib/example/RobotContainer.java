package org.pumpkinlib.example;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.pumpkinlib.core.PumpkinRegistry;
import org.pumpkinlib.core.hid.Rumble;
import org.pumpkinlib.core.hid.RumblePattern;
import org.pumpkinlib.mechanism.PositionMechanism;
import org.pumpkinlib.mechanism.SimpleMechanism;
import org.pumpkinlib.superstructure.AxisGoal;
import org.pumpkinlib.superstructure.Interlock;
import org.pumpkinlib.superstructure.SafetyModel;
import org.pumpkinlib.superstructure.Superstructure;
import org.pumpkinlib.units.Range;

/**
 * {@code design/01-core-mechanisms.md} section 9.3 — construction and bindings, reproduced against
 * the real API.
 *
 * <h2>Where this departs from section 9.3, and why</h2>
 *
 * <ol>
 *   <li><b>{@code SafetyModel} is built here, not in {@code RobotConfig}.</b> {@code
 *       SafetyModel.over(...)} takes two {@code Mechanism}s, because it reads their live measured
 *       positions to answer {@code isSafe()}. Section 9.1 passes two {@code PositionConfig}s, which
 *       does not compile. The zones, the corridor and {@code synchronizedAxes(true)} are the
 *       snippet's, unchanged.
 *   <li><b>{@code Superstructure.Builder} has {@code add(...)}, not {@code mechanisms(...)}.</b> One
 *       mechanism per call, so that {@code add(mechanism, goalReceiver)} can override the adapter
 *       for a mechanism the superstructure does not drive the normal way.
 *   <li><b>{@code interlock(...)} takes an {@link Interlock} record, not five loose arguments.</b>
 *       The record is the thing {@code SuperstructureReport} lists and the dashboard names, so it
 *       has to exist as a value either way; the builder simply does not hide its construction.
 *   <li><b>No {@code TalonFXMotorIO} escape hatch.</b> Section 9.3 ends with {@code
 *       ELEVATOR.io().as(TalonFXMotorIO.class).ifPresent(io -> io.applyRaw(...))}. That class is real
 *       and is in exactly the package the snippet names — {@code
 *       org.pumpkinlib.hardware.phoenix.TalonFXMotorIO}, with a real {@code applyRaw(Consumer)} — but
 *       it ships in the separate {@code pumpkinlib-phoenix6} artifact, which this test source set
 *       does not and must not depend on (architecture rule 1). The line is quoted in a comment below
 *       rather than compiled.
 *   <li><b>Simulated backends.</b> See {@link RobotConfig#simulated()}: {@code MotorIOFactory} does
 *       not substitute a simulated backend for a vendor spec, so a fixture with no vendor artifact on
 *       its classpath would get three {@code NoOpMotorIO}s and never move.
 * </ol>
 *
 * <p><b>This class cannot be loaded without WPILib's JNI natives.</b> Constructing a {@code
 * Mechanism} builds a {@code PumpkinAlert}, which eagerly constructs an {@code
 * edu.wpi.first.wpilibj.Alert}, which reaches NetworkTables; with no natives WPILib's loader calls
 * {@code System.exit(1)}. Every test that touches it is therefore {@code @Tag("hal")}.
 */
public final class RobotContainer {

  // The three configs, with simulated leaders. On a robot this line reads
  //   new PositionMechanism(RobotConfig.ELEVATOR)
  // and the vendor artifact on the classpath supplies the backend; see RobotConfig.simulated().
  private static final RobotConfig.Simulated kConfigs = RobotConfig.simulated();

  private static final PositionMechanism kElevator = new PositionMechanism(kConfigs.elevator());
  private static final PositionMechanism kArm = new PositionMechanism(kConfigs.arm());
  private static final SimpleMechanism kRoller = new SimpleMechanism(kConfigs.roller());

  /**
   * The elevator.
   *
   * @return the one elevator instance the whole robot shares
   */
  public static PositionMechanism elevator() {
    return kElevator;
  }

  /**
   * The arm.
   *
   * @return the one arm instance the whole robot shares
   */
  public static PositionMechanism arm() {
    return kArm;
  }

  /**
   * The roller.
   *
   * @return the one roller instance the whole robot shares
   */
  public static SimpleMechanism roller() {
    return kRoller;
  }

  /**
   * Section 9.1's collision model, built where the API can accept it.
   *
   * <p>The arm sweeps through the chassis crossbar whenever the elevator is below 9 in and the arm
   * is between -15 and 40 degrees, so that rectangle of the (elevator, arm) configuration space is
   * forbidden. The corridor says the arm is safe at 95 degrees — tucked — for the whole 55 in of
   * elevator travel, which is what gives the router somewhere to go.
   *
   * @return the safety model over the elevator and the arm
   */
  public static SafetyModel safety() {
    return SafetyModel.over(kElevator, kArm)
        .forbid(
            "arm-through-chassis",
            Range.of(Inches.of(0), Inches.of(9)),
            Range.of(Degrees.of(-15), Degrees.of(40)),
            "the arm hits the chassis crossbar below 9 in")
        .corridor("travel-tucked", Range.of(Inches.of(0), Inches.of(55)), 95.0)
        .synchronizedAxes(true) // default; stated here so the choice is visible
        .build();
  }

  /**
   * Section 9.3's one interlock: nothing but IDLE is reachable until both axes have homed.
   *
   * @return the rule
   */
  public static Interlock<ScoringState> homedInterlock() {
    return new Interlock<>(
        "no-score-until-homed",
        from -> true,
        to -> to != ScoringState.IDLE,
        () -> kElevator.isHomed() && kArm.isHomed(),
        "the elevator has not homed yet; press Start to home");
  }

  private final Superstructure<ScoringState> m_super;
  private final CommandXboxController m_driver = new CommandXboxController(0);

  /** Builds the superstructure, registers everything, and binds the driver's buttons. */
  public RobotContainer() {
    m_super =
        new Superstructure.Builder<>(ScoringState.class, ScoringState.IDLE)
            .safety(safety())
            .add(kElevator)
            .add(kArm)
            .add(kRoller)
            .defaultFor(kElevator, AxisGoal.of(RobotConfig.ELEVATOR_STOW))
            .defaultFor(kArm, AxisGoal.of(RobotConfig.ARM_STOW))
            .defaultFor(kRoller, AxisGoal.percent(0.0))
            .interlock(homedInterlock())
            .build(); // validates every setpoint reference and runs report()

    // The ONE place validation, the CAN-ID scan, setpoint-name resolution and SAFE_MODE live.
    PumpkinRegistry.addAll(kElevator, kArm, kRoller, m_super);

    m_driver.start().onTrue(kElevator.homeCommand());
    m_driver
        .leftBumper()
        .whileTrue(m_super.request(ScoringState.INTAKE))
        .onFalse(m_super.request(ScoringState.HOLD));
    m_driver.a().onTrue(m_super.request(ScoringState.L2));
    m_driver.b().onTrue(m_super.request(ScoringState.L3));
    m_driver.y().onTrue(m_super.request(ScoringState.L4));
    m_driver
        .rightBumper()
        .whileTrue(m_super.request(ScoringState.EJECT))
        .onFalse(m_super.request(ScoringState.IDLE));
    m_driver.x().onTrue(m_super.request(ScoringState.IDLE));

    // Rumble when a piece is held. Level signal, so it works even if the piece was already there
    // when the command started.
    kRoller
        .holding()
        .onTrue(
            Rumble.on(m_driver.getHID())
                .side(GenericHID.RumbleType.kBothRumble)
                .play(RumblePattern.pulse(0.4, Seconds.of(0.25))));

    // Escape hatch, on page 1. Real, and in exactly the package section 9.3 names, but it lives in
    // the pumpkinlib-phoenix6 artifact which this source set may not depend on:
    //
    //   ELEVATOR.io().as(TalonFXMotorIO.class)
    //       .ifPresent(io -> io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false));
  }

  /**
   * The superstructure this container built.
   *
   * @return the superstructure
   */
  public Superstructure<ScoringState> superstructure() {
    return m_super;
  }

  /**
   * Section 9.3's autonomous routine, including the {@code Commands.waitUntil} that the snippet
   * calls out as a named counter-example.
   *
   * <p>{@code .andThen(trigger::getAsBoolean)} would compile — a {@code BooleanSupplier} method
   * reference is assignable to {@code Runnable}, so it binds to {@code andThen(Runnable,
   * Subsystem...)}, the trigger is evaluated once, the answer is discarded, and the step finishes in
   * the same loop. That ejects before the elevator has moved. {@code Commands.waitUntil} is the
   * correct call and the reason this routine is asserted by its <i>end state</i> and not merely by
   * the fact that it built.
   *
   * @return the routine
   */
  public Command getAutonomousCommand() {
    return kElevator
        .homeCommand()
        .andThen(m_super.request(ScoringState.L4))
        .andThen(
            Commands.waitUntil(m_super.at(ScoringState.L4).and(kElevator.atGoalTrigger())))
        .andThen(m_super.request(ScoringState.EJECT).withTimeout(0.5))
        .andThen(m_super.request(ScoringState.IDLE));
  }
}

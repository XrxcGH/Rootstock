package frc.robot;

import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.rootstock.core.RootstockRegistry;
import org.rootstock.mechanism.PositionMechanism;
import org.rootstock.tuning.wizard.TuningWizard;

/**
 * Construction and bindings. A {@link PositionMechanism} is a plain WPILib {@code Subsystem}, so
 * {@code setDefaultCommand}, requirements and any command you have already written keep working.
 */
public class RobotContainer {

  private final PositionMechanism m_elevator = new PositionMechanism(RobotConfig.ELEVATOR);
  private final CommandXboxController m_driver = new CommandXboxController(0);

  /**
   * The wizard gets a port nobody else uses. Its right trigger authorizes raw voltage, which is
   * the driver's "shoot" button on most robots. Sharing a port is legal, logged verbatim and
   * deliberately awkward.
   */
  private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));

  /** Registers everything and binds the driver's buttons. */
  public RobotContainer() {
    // ONE call. Telemetry, the health monitors, the pit self-test, live tuning, the command
    // scheduler and the CAN ID conflict scan all attach here, and this is also the single place
    // config validation runs and SAFE_MODE is decided.
    RootstockRegistry.addAll(m_elevator, m_tuner);

    // The derivation dump, and the highest-value thirty seconds of a first session: it prints the
    // gearbox as written and as one number, what one output rotation is worth in inches and in
    // meters, the soft limits in both, the gains with their units, where the loop runs and why,
    // and every named setpoint. Check the travel-per-rotation line against CAD before anything
    // moves. You have to ask for this: the library builds the text but nothing calls describe()
    // for you, and the resolved config otherwise reaches only the log at /Rootstock/Config/.
    System.out.println(RobotConfig.ELEVATOR.describe());

    m_driver.start().onTrue(m_elevator.homeCommand());
    m_driver.y().onTrue(m_elevator.goTo(RobotConfig.ELEVATOR_L4));
    m_driver.a().onTrue(m_elevator.goTo(RobotConfig.ELEVATOR_STOW));

    // Left stick drives the elevator by hand whenever nothing else has it, and the mechanism
    // captures its position on release rather than falling.
    m_elevator.setDefaultCommand(m_elevator.manual(() -> -m_driver.getLeftY()));
  }

  /**
   * The one elevator this robot shares.
   *
   * @return the elevator
   */
  public PositionMechanism elevator() {
    return m_elevator;
  }
}

package frc.robot;

import org.rootstock.core.RootstockRobot;
import org.rootstock.core.spi.LogConfig;

/**
 * The base class, and the only one Rootstock ships. Every line of {@code RootstockRobot} is a
 * delegation to the public {@code RootstockLifecycle}, so a team that would rather keep its own
 * base class can drive the lifecycle by hand instead. See the README's "Adopting one piece".
 *
 * <p>{@code LogConfig.defaults()} means Rootstock configures <b>and starts</b> AdvantageKit's
 * {@code Logger}. If your own code already calls {@code Logger.start()}, this is a crash at boot,
 * and {@code LogConfig.adoptExistingLogger()} is the factory you want. There is deliberately no
 * no-argument version that guesses which situation you are in.
 */
public class Robot extends RootstockRobot {

  private final RobotContainer m_container;

  /** Builds the robot. */
  public Robot() {
    super(LogConfig.defaults());
    m_container = new RobotContainer();

    // LAST, after everything has registered, so the boot dump reflects what you actually built.
    // If you forget it, the first robotPeriodic() runs it for you, which is later than you want
    // but is not a failure.
    lifecycle().init();
  }

  /**
   * The container this robot built.
   *
   * @return the container
   */
  public RobotContainer container() {
    return m_container;
  }
}

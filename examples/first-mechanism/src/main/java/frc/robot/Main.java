package frc.robot;

import edu.wpi.first.wpilibj.RobotBase;

/**
 * WPILib's entry point, unchanged. GradleRIO generates this file for you and Rootstock does not
 * touch it. It is here only so this directory is a complete set of files rather than a set with a
 * hole in it.
 */
public final class Main {

  /**
   * Starts the robot program.
   *
   * @param args ignored
   */
  public static void main(String... args) {
    RobotBase.startRobot(Robot::new);
  }

  private Main() {}
}

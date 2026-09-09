package org.rootstock.sim;

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Translation3d;
import java.util.Locale;

/**
 * A game piece leaving a shooter: where it starts and how fast it is going.
 *
 * <p>Deliberately kinematic rather than a shooter model. The exit velocity is what a team measures on a
 * practice field with a chronograph or a high-speed camera, and it is what a flywheel's own simulation
 * already produces; asking this record for a wheel radius and a compression coefficient would be asking
 * for numbers nobody has.
 *
 * <p><b>Gravity is the world's, not the projectile's.</b> maple-sim uses a tuned 11 m/s&sup2; rather
 * than 9.81 for projectiles, which is a real difference over a five-metre shot. The number is not a
 * field here because a projectile that carried its own gravity would let a team "fix" a sweep by
 * editing it, which produces a simulation that agrees with the spreadsheet and not with the field.
 *
 * @param type the game-piece type, matching the season's name for it
 * @param release where the piece leaves the robot, in field coordinates
 * @param velocityMetersPerSecond the exit velocity vector, field-relative, in metres per second
 */
public record ProjectileSpec(String type, Pose3d release, Translation3d velocityMetersPerSecond) {

  /**
   * A projectile from a release pose, a speed and an elevation angle.
   *
   * @param type the game-piece type
   * @param release where the piece leaves the robot; its rotation supplies the heading
   * @param speedMetersPerSecond the exit speed
   * @param elevationRadians the launch angle above horizontal
   * @return the spec
   */
  public static ProjectileSpec of(
      String type, Pose3d release, double speedMetersPerSecond, double elevationRadians) {
    double heading = release.getRotation().getZ();
    double horizontal = speedMetersPerSecond * Math.cos(elevationRadians);
    return new ProjectileSpec(
        type,
        release,
        new Translation3d(
            horizontal * Math.cos(heading),
            horizontal * Math.sin(heading),
            speedMetersPerSecond * Math.sin(elevationRadians)));
  }

  /**
   * The exit speed, as a scalar.
   *
   * @return metres per second
   */
  public double speedMetersPerSecond() {
    return velocityMetersPerSecond.getNorm();
  }

  /**
   * A one-line description, for a log entry.
   *
   * @return the description
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s from (%.2f, %.2f, %.2f) at %.2f m/s",
        type,
        release.getX(),
        release.getY(),
        release.getZ(),
        speedMetersPerSecond());
  }
}

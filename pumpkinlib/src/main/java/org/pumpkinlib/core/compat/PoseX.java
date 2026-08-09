package org.pumpkinlib.core.compat;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Twist2d;
import java.util.List;

/**
 * Thin wrappers over the {@code edu.wpi.first.math.geometry} calls whose spelling changes in 2027.
 *
 * <p>The known delta is the exponential map: {@code Pose2d.exp(Twist2d)} becomes
 * {@code pose.plus(twist.exp())}. That call is the heart of odometry integration and of every
 * trajectory-following discretization step in the library, so it is worth exactly one facade method
 * to keep the 2027 port from touching the drive domain at all.
 *
 * <p>This class deliberately does <b>not</b> wrap the rest of the geometry API. {@code Pose2d},
 * {@code Rotation2d} and {@code Translation2d} themselves are stable value types whose 2027 change is
 * a package rename — an import rewrite, which is the thing §10 already promises. Only the calls whose
 * <i>shape</i> changes need a seam.
 */
public final class PoseX {

  private PoseX() {}

  /**
   * Applies a twist to a pose — the exponential map, i.e. "drive this constant-curvature arc from
   * here and tell me where I end up".
   *
   * <p>2027: {@code pose.exp(twist)} becomes {@code pose.plus(twist.exp())}. One method body here,
   * instead of every odometry and discretization call site in {@code org.pumpkinlib.drive}.
   *
   * @param pose the starting pose
   * @param twist the twist to apply, in the pose's own frame
   * @return the resulting pose
   */
  public static Pose2d expTwist(Pose2d pose, Twist2d twist) {
    return pose.exp(twist);
  }

  /**
   * The inverse of {@link #expTwist}: the twist that carries {@code start} to {@code end}.
   *
   * @param start the starting pose
   * @param end the ending pose
   * @return the twist, in {@code start}'s frame
   */
  public static Twist2d logTwist(Pose2d start, Pose2d end) {
    return start.log(end);
  }

  /**
   * Linearly interpolates between two poses.
   *
   * @param start the pose at {@code t == 0}
   * @param end the pose at {@code t == 1}
   * @param t the interpolation parameter, clamped to {@code [0, 1]} by WPILib
   * @return the interpolated pose
   */
  public static Pose2d interpolate(Pose2d start, Pose2d end, double t) {
    return start.interpolate(end, t);
  }

  /**
   * The pose in a collection nearest to a reference pose, by translation distance.
   *
   * @param reference the pose to measure from
   * @param candidates the poses to choose among; must not be empty
   * @return the nearest candidate
   */
  public static Pose2d nearest(Pose2d reference, List<Pose2d> candidates) {
    return reference.nearest(candidates);
  }
}

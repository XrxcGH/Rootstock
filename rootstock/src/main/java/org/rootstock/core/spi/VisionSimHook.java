package org.rootstock.core.spi;

import edu.wpi.first.math.geometry.Pose2d;

/**
 * How simulated vision gets the robot's ground-truth pose without anything in core naming the vision
 * domain (D19/D26).
 *
 * <p>{@code org.rootstock.vision.sim.RootstockVisionSim} implements this; the drive domain's
 * simulation tick calls it with whatever ground truth it has — the maple-sim robot pose if maple-sim
 * is installed, otherwise the drivetrain's own sim pose. Neither side names the other.
 *
 * <p><b>This one stays {@code ServiceLoader}-discovered.</b> Decision 3 moved the in-jar hooks to an
 * explicit list, but the vision simulation path lives behind the photonvision adapter — genuinely
 * out-of-jar — which is exactly where {@code ServiceLoader} is load-bearing.
 *
 * <p>Implementations must not throw; a simulation-only failure must never take down a sim session
 * that a student is using to test auto.
 */
public interface VisionSimHook {

  /**
   * A short, stable name for the boot dump.
   *
   * @return the hook's name
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * Called once per simulation loop with the GROUND-TRUTH robot pose.
   *
   * <p>Ground truth, not the estimated pose: feeding an estimate back in would make the simulated
   * cameras confirm whatever the pose estimator already believed, and a vision simulation that cannot
   * disagree with the estimator tests nothing.
   *
   * @param groundTruthPose the true robot pose in the blue-origin field convention
   */
  void update(Pose2d groundTruthPose);
}

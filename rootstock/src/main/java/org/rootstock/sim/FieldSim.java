package org.rootstock.sim;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import java.util.List;

/**
 * A world with robots and game pieces in it — implemented by the optional maple-sim adapter, and by a
 * kinematic stand-in that is always present.
 *
 * <h2>Why this interface exists in the core jar when nothing in the core jar implements it</h2>
 *
 * <p>maple-sim is a rigid-body field simulator (dyn4j) with real collisions and real game pieces, and
 * it is genuinely valuable. It is also, as of 2026-08-07, a beta: the only 2026 release is v0.4.0-beta,
 * {@code releases/latest} still returns a 2025 build, and the community has flagged succession risk.
 * Rootstock will not make a team's build depend on that. So the adapter is a separate artifact
 * ({@code dev.rootstock:rootstock-maplesim}) discovered by {@code ServiceLoader}, this interface is
 * the contract it implements, and <b>no Rootstock build file names maple-sim</b>. A team can never
 * fail to build because of it. See {@link RootstockFieldSim}.
 *
 * <h2>The degraded path is a supported configuration, not a failure path</h2>
 *
 * <p>Roadmap risk R13. {@link RootstockFieldSim#field()} always returns something: the adapter when it is
 * installed, and {@link RootstockFieldSim#kinematic()} when it is not. The kinematic world integrates
 * commanded chassis speeds with no collisions and no game-piece physics — which is exactly what most
 * teams' hand-rolled sim does today, and it is enough to make an autonomous routine's <i>path</i>
 * testable headlessly. What it cannot do is tell you that you would have hit something, and it says so
 * rather than pretending.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>{@code design/04} §7.5 sketches {@code addRobot(SwerveSimConfig, Pose2d)}. {@code SwerveSimConfig}
 * belongs to the drive domain, which does not exist yet, and naming an unbuilt type here would make
 * this interface uncompilable rather than forward-looking. The drivetrain configures its own simulation
 * and tells this world where it is; the world's job is the field, not the robot.
 */
public interface FieldSim {

  /**
   * Introduces a robot to the world.
   *
   * @param name the robot's name; {@code "Robot"} for our own
   * @param start where it starts, in blue-origin field coordinates
   */
  void addRobot(String name, Pose2d start);

  /**
   * Commands a robot's chassis speeds for the next tick.
   *
   * @param name the robot's name
   * @param fieldRelative the commanded speeds, field-relative
   */
  void setRobotSpeeds(String name, ChassisSpeeds fieldRelative);

  /**
   * Teleports a robot, and — in the adapter — the odometry that follows it.
   *
   * <p>Odometry reset and arena pose reset happen <b>in one place</b>, which is what makes the classic
   * "the physics sim and the odometry did not agree on where the robot was" first-run failure
   * impossible rather than merely unlikely.
   *
   * @param name the robot's name
   * @param pose the new pose
   */
  void resetPose(String name, Pose2d pose);

  /**
   * Where a robot actually is, as opposed to where its odometry thinks it is.
   *
   * <p>The whole reason a physics world is worth having: the difference between this and the estimated
   * pose is the number a vision filter or an odometry fix is judged on.
   *
   * @param name the robot's name
   * @return the ground-truth pose
   */
  Pose2d groundTruthPose(String name);

  /**
   * Places a game piece on the field.
   *
   * @param type the game-piece type, matching the season's name for it
   * @param at where to put it
   */
  void addGamePiece(String type, Translation2d at);

  /**
   * Every game piece of one type, for publishing to a 3D field view.
   *
   * @param type the game-piece type
   * @return the poses; empty when the world does not model this type
   */
  Pose3d[] gamePieces(String type);

  /**
   * Every game-piece type this world knows about.
   *
   * @return the type names, in a stable order
   */
  List<String> gamePieceTypes();

  /**
   * Fires a projectile.
   *
   * <p><b>Documented caveat, because it changes what a shooter sweep means.</b> maple-sim's projectile
   * gravity is a tuned 11 m/s&sup2;, not 9.81. Treat a simulated shooter sweep as a starting point, not
   * as truth.
   *
   * @param spec the projectile
   */
  void launchProjectile(ProjectileSpec spec);

  /** Puts every game piece back where autonomous expects it. */
  void resetForAuto();

  /**
   * Advances the world one timestep.
   *
   * <p>Called <b>only</b> from {@link RootstockSim#tick()}, which returns immediately unless
   * {@link org.rootstock.core.compat.Platform#isSimulation()}. maple-sim's own documentation warns
   * that its arena consumes roboRIO resources, so "never on hardware" is a structural property here
   * rather than a note.
   *
   * @param dtSeconds the timestep
   */
  void tick(double dtSeconds);

  /**
   * Whether this world models rigid-body collisions.
   *
   * @return true for the maple-sim adapter, false for the kinematic stand-in
   */
  boolean hasCollisions();

  /**
   * A one-line description naming which world this is, for the boot dump.
   *
   * @return the description
   */
  String describe();
}

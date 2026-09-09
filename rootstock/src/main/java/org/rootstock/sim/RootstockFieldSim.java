package org.rootstock.sim;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.RootstockAlert;

/**
 * The maple-sim hook: one {@code ServiceLoader} lookup, and a kinematic world when it comes up empty.
 *
 * <h2>The one surviving ServiceLoader use in this domain</h2>
 *
 * <p>D28 put telemetry, tuning, simulation and vision in a single jar, which deleted the discovery
 * machinery everywhere else — a {@code ServiceLoader} lookup for a class that is unconditionally on the
 * classpath is indirection with no seam behind it. maple-sim is different: it is genuinely out-of-jar
 * and genuinely optional, so the mechanism is load-bearing here and stays.
 *
 * <p>The reason it is optional is on the record. As of 2026-08-07 the only 2026 maple-sim release is
 * v0.4.0-beta (2026-01-17), flagged prerelease; GitHub's {@code releases/latest} still returns v0.3.14
 * from 2025; and the primary developer has graduated. Its 2026 game support is real and documented, and
 * the library self-describes as beta with "potential bugs". Making a team's build depend on that is not
 * a trade Rootstock will make, so: separate artifact, discovered, never a dependency. <b>Nothing in
 * this file imports maple-sim and no build file names it.</b>
 *
 * <h2>The kinematic world is a supported configuration (risk R13)</h2>
 *
 * <p>{@link #get()} is empty when the adapter is absent — that is the design's signature and it is what
 * a caller asks when it wants to know whether collisions are available. {@link #field()} is never
 * empty: it returns the adapter, or {@link #kinematic()}. The kinematic world integrates commanded
 * chassis speeds with no collisions and no game-piece physics, which is enough for an autonomous
 * routine's path to be regression-tested headlessly and honest about what it is not.
 *
 * <p>Absence raises an <b>informational</b> alert, never an error. A team that never installs the
 * adapter should see one sentence explaining what they do not have, once, and never think about it
 * again.
 *
 * <h2>The two rules the adapter owns, restated here so they are findable</h2>
 *
 * <ol>
 *   <li>{@code SimulatedArena.simulationPeriodic()} is called only from {@link RootstockSim#tick()},
 *       which returns immediately unless {@code Platform.isSimulation()}. maple-sim's docs warn it
 *       consumes roboRIO resources on hardware.
 *   <li>Any attempt to call {@code SimulatedArena.overrideSimulationTimings(...)} throws. maple-sim's
 *       docs, verbatim: "DO NOT override the timing if you are using AdvantageKit, as it only supports
 *       50Hz robots." AdvantageKit is unconditional in Rootstock, so this check is unconditional too —
 *       one fewer branch and one fewer way to get it wrong.
 * </ol>
 */
public final class RootstockFieldSim {

  private RootstockFieldSim() {}

  /** The alert group field-simulation notes are filed under. */
  public static final String kAlertGroup = "Rootstock/Sim";

  private static Optional<FieldSim> s_adapter;
  private static KinematicFieldSim s_kinematic;
  private static RootstockAlert s_absentAlert;

  /**
   * The installed field-simulation adapter.
   *
   * <p><b>Empty when the adapter is not on the classpath</b>, which is the normal case and not an
   * error. Use {@link #field()} if what you want is "a world"; use this if what you want is "the world
   * with collisions, or nothing".
   *
   * @return the adapter, or empty
   */
  public static synchronized Optional<FieldSim> get() {
    if (s_adapter == null) {
      s_adapter = load();
    }
    return s_adapter;
  }

  /**
   * Whether a real field-simulation adapter is installed.
   *
   * @return true when {@link #get()} is non-empty
   */
  public static synchronized boolean available() {
    return get().isPresent();
  }

  /**
   * A world, always. The adapter when it is installed, the kinematic stand-in when it is not.
   *
   * @return the field simulation
   */
  public static synchronized FieldSim field() {
    return get().orElseGet(RootstockFieldSim::kinematic);
  }

  /**
   * The kinematic stand-in, explicitly — for a test that wants the degraded path even when an adapter
   * is installed.
   *
   * @return the singleton kinematic world
   */
  public static synchronized FieldSim kinematic() {
    if (s_kinematic == null) {
      s_kinematic = new KinematicFieldSim();
    }
    return s_kinematic;
  }

  /**
   * Advances whichever world is in use.
   *
   * <p>Called from {@link RootstockSim#tick()} and nowhere else, so the "never on hardware" property is
   * structural rather than a convention.
   *
   * @param dtSeconds the timestep
   */
  public static synchronized void tick(double dtSeconds) {
    field().tick(dtSeconds);
  }

  /**
   * A one-line description of which world is in use, for the boot dump.
   *
   * @return the description
   */
  public static synchronized String describe() {
    return "RootstockFieldSim: " + field().describe();
  }

  /**
   * Forgets the discovered adapter and the kinematic world.
   *
   * <p>For {@code @BeforeEach}, and for a test that wants to prove the degraded path works with the
   * adapter present.
   */
  public static synchronized void resetForTest() {
    s_adapter = null;
    s_kinematic = null;
  }

  /**
   * Whether {@link #kinematic()} is what {@link #field()} would return — the degraded, still-supported
   * configuration of roadmap risk R13.
   *
   * @return true when no adapter is installed
   */
  public static synchronized boolean isKinematicOnly() {
    return get().isEmpty();
  }

  /**
   * Raises the informational alert that says which world is in use.
   *
   * <p>Separate from {@link #get()} on purpose. {@code Alerts.info(...)} builds a WPILib
   * {@code Alert}, which needs the JNI natives; if the discovery lookup raised it, then
   * {@link #describe()}, {@link #field()} and {@link RootstockSim#describe()} would all become
   * HAL-dependent and none of them could be exercised in a headless test. Discovery is therefore pure
   * and this is called once, from {@link RootstockSim#printBootReport()}, which is already on the
   * HAL-bearing path.
   */
  public static synchronized void publishAvailability() {
    boolean absent = get().isEmpty();
    absentAlert()
        .text(
            absent
                ? "FIELD_SIM_KINEMATIC: no field-simulation adapter is installed, so the simulated "
                    + "world has no collisions and no game-piece physics. This is a supported "
                    + "configuration: autonomous paths, odometry and mechanism physics all still "
                    + "work. Install dev.rootstock:rootstock-maplesim if you want rigid-body "
                    + "collisions."
                : "FIELD_SIM_ADAPTER: " + field().describe())
        .set(absent);
  }

  /** The {@code ServiceLoader} lookup. Pure — see {@link #publishAvailability()}. */
  private static Optional<FieldSim> load() {
    List<FieldSim> found = new ArrayList<>();
    for (FieldSim candidate : ServiceLoader.load(FieldSim.class)) {
      found.add(candidate);
    }
    if (found.isEmpty()) {
      return Optional.empty();
    }
    // First wins, deterministically: ServiceLoader iterates classpath order, and taking the first
    // rather than the last means a team that adds a second adapter gets the one it declared first
    // rather than whichever the classloader happened to reach last.
    return Optional.of(found.get(0));
  }

  /** Built on first use, not at class initialisation, because a WPILib Alert needs the JNI natives. */
  private static RootstockAlert absentAlert() {
    if (s_absentAlert == null) {
      s_absentAlert = Alerts.info(kAlertGroup, "FIELD_SIM_KINEMATIC");
    }
    return s_absentAlert;
  }

  /**
   * The degraded world: commanded chassis speeds, integrated. No collisions, no game-piece physics.
   *
   * <p>Every method is honest about what it does not do. {@link #hasCollisions()} returns false,
   * {@link #launchProjectile} records the shot without flying it, and {@link #describe()} says which
   * world this is — so a team reading a log can tell whether "the robot drove through the reef" was a
   * pathing bug or an absent physics engine.
   */
  private static final class KinematicFieldSim implements FieldSim {

    private final Map<String, Pose2d> m_poses = new LinkedHashMap<>();
    private final Map<String, ChassisSpeeds> m_speeds = new LinkedHashMap<>();
    private final Map<String, List<Translation2d>> m_pieces = new LinkedHashMap<>();
    private final Map<String, List<Translation2d>> m_initialPieces = new LinkedHashMap<>();
    private int m_projectiles;

    @Override
    public void addRobot(String name, Pose2d start) {
      m_poses.put(name, start == null ? Pose2d.kZero : start);
      m_speeds.put(name, new ChassisSpeeds());
    }

    @Override
    public void setRobotSpeeds(String name, ChassisSpeeds fieldRelative) {
      m_speeds.put(name, fieldRelative == null ? new ChassisSpeeds() : fieldRelative);
    }

    @Override
    public void resetPose(String name, Pose2d pose) {
      m_poses.put(name, pose == null ? Pose2d.kZero : pose);
    }

    @Override
    public Pose2d groundTruthPose(String name) {
      return m_poses.getOrDefault(name, Pose2d.kZero);
    }

    @Override
    public void addGamePiece(String type, Translation2d at) {
      m_pieces.computeIfAbsent(type, key -> new ArrayList<>()).add(at);
      m_initialPieces.computeIfAbsent(type, key -> new ArrayList<>()).add(at);
    }

    @Override
    public Pose3d[] gamePieces(String type) {
      List<Translation2d> pieces = m_pieces.get(type);
      if (pieces == null) {
        return new Pose3d[0];
      }
      Pose3d[] out = new Pose3d[pieces.size()];
      for (int i = 0; i < out.length; i++) {
        Translation2d at = pieces.get(i);
        // On the floor, unrotated: a kinematic world does not track a game piece's attitude, and
        // inventing one would put a tilted, spinning gear in a 3D field view that nothing produced.
        out[i] = new Pose3d(at.getX(), at.getY(), 0.0, new Rotation3d());
      }
      return out;
    }

    @Override
    public List<String> gamePieceTypes() {
      return List.copyOf(m_pieces.keySet());
    }

    @Override
    public void launchProjectile(ProjectileSpec spec) {
      // Recorded, not flown. A kinematic world that pretended to model a trajectory would give a
      // shooter sweep an answer, and a wrong answer here is worse than no answer: a team would tune
      // against it.
      m_projectiles++;
    }

    @Override
    public void resetForAuto() {
      m_pieces.clear();
      for (Map.Entry<String, List<Translation2d>> e : m_initialPieces.entrySet()) {
        m_pieces.put(e.getKey(), new ArrayList<>(e.getValue()));
      }
      m_projectiles = 0;
    }

    @Override
    public void tick(double dtSeconds) {
      double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : 0.02;
      for (Map.Entry<String, ChassisSpeeds> e : m_speeds.entrySet()) {
        Pose2d pose = m_poses.getOrDefault(e.getKey(), Pose2d.kZero);
        ChassisSpeeds speeds = e.getValue();
        m_poses.put(
            e.getKey(),
            new Pose2d(
                pose.getX() + speeds.vxMetersPerSecond * dt,
                pose.getY() + speeds.vyMetersPerSecond * dt,
                pose.getRotation().plus(
                    Rotation2d.fromRadians(speeds.omegaRadiansPerSecond * dt))));
      }
    }

    @Override
    public boolean hasCollisions() {
      return false;
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "KINEMATIC (no maple-sim adapter installed): %d robot(s), %d game-piece type(s), "
              + "%d projectile(s) recorded but not flown. No collisions, no game-piece physics; "
              + "paths and odometry are still meaningful.",
          m_poses.size(),
          m_pieces.size(),
          m_projectiles);
    }
  }
}

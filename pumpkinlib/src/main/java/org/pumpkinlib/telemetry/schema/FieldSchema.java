package org.pumpkinlib.telemetry.schema;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.units.Units;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * The section 3.4 key block — {@code Pumpkin/Field/} — the ghost contract, as both a published
 * contract and the writer that fills it.
 *
 * <p>These key names are fixed so that the shipped AdvantageScope layouts, the replay-diff tool and
 * the triage manifest can all key off them without probing. Every failure mode in this area is
 * silent: a renamed key does not error, it renders as a robot that never moves on the field view.
 *
 * <p><b>{@link #goal(Pose2d)} publishes an array of length zero or one, not a nullable pose.</b>
 * That is the difference between "there is no goal right now" and "the goal is wherever it was the
 * last time there was one", and on a field view the second one is a ghost robot parked at a scoring
 * position the driver abandoned twenty seconds ago.
 *
 * <p><b>None of these keys is demotable</b>, and none of them is published by this class on its own
 * schedule — the caller decides when. Poses handed here are already in the blue-origin convention;
 * this domain publishes what it is given and flips nothing.
 *
 * <h2>On tiers</h2>
 *
 * <p>Section 3.4's table has no tier column, so every key in this block is {@code STANDARD} — the
 * documented default, and the tier that survives an FMS attach. Nothing here is CRITICAL because
 * every value in the block is a <i>view</i> of something already logged at CRITICAL elsewhere:
 * {@code Robot} mirrors {@code Pumpkin/Drive/Pose}, and the ghosts are the auto domain's own
 * setpoints. If a future revision of the design tiers this table, this comment is the thing to
 * delete along with the change.
 */
public final class FieldSchema {

  /** Everything in this block hangs off this prefix. */
  public static final String kPrefix = PumpkinLog.kRoot + "/Field";

  /** The robot's pose. AdvantageScope object: Robot. */
  public static final String kRobot = kPrefix + "/Robot";

  /** This cycle's trajectory setpoint. AdvantageScope object: Ghost. */
  public static final String kRobotGhost = kPrefix + "/RobotGhost";

  /** The active goal, as a length-0-or-1 array. AdvantageScope object: Ghost #2. */
  public static final String kGoal = kPrefix + "/Goal";

  /** The active trajectory. AdvantageScope object: Trajectory. */
  public static final String kTrajectory = kPrefix + "/Trajectory";

  /** Vision pose estimates, 2D. AdvantageScope object: Vision Target, child of Robot. */
  public static final String kVisionPoses = kPrefix + "/VisionPoses";

  /** Vision targets, 3D. AdvantageScope object: Vision Target (3D). */
  public static final String kVisionTargets = kPrefix + "/VisionTargets";

  /** Prefix of the per-type game piece arrays. AdvantageScope object: Game piece. */
  public static final String kGamePiecesPrefix = kPrefix + "/GamePieces/";

  /** Translational distance from the robot to its trajectory setpoint. */
  public static final String kErrorMeters = kPrefix + "/ErrorMeters";

  /** Rotational distance from the robot to its trajectory setpoint. */
  public static final String kErrorDegrees = kPrefix + "/ErrorDegrees";

  /** The placeholder the static table uses where a real key carries a game piece type. */
  public static final String kTypePlaceholder = "<Type>";

  /** Reused so clearing a 2D channel allocates nothing. */
  private static final Pose2d[] kNoPose2d = new Pose2d[0];

  /** Reused so clearing a 3D channel allocates nothing. */
  private static final Pose3d[] kNoPose3d = new Pose3d[0];

  /**
   * The one-element carrier {@link #goal(Pose2d)} writes into.
   *
   * <p>Reused rather than allocated because a {@code new Pose2d[1]} per cycle is exactly the
   * per-cycle garbage the tier design exists to avoid, and the array's contents are copied into the
   * log before this method returns. Safe because the robot loop is single-threaded; the alternative
   * — a nullable {@code Pose2d} key — is the stale-ghost bug this whole channel is shaped to avoid.
   */
  private static final Pose2d[] m_oneGoal = new Pose2d[1];

  /**
   * Absolute keys for each game piece type seen this session, so the per-cycle path never
   * concatenates. Insertion-ordered per guarantee G7.
   */
  private static final Map<String, String> m_gamePieceKeys = new LinkedHashMap<>();

  private FieldSchema() {}

  /**
   * Publishes the robot's pose.
   *
   * @param pose the pose, blue-origin
   */
  public static void robot(Pose2d pose) {
    PumpkinLog.log(kRobot, pose);
  }

  /**
   * Publishes this cycle's trajectory setpoint as the ghost robot.
   *
   * @param setpoint the setpoint pose, blue-origin
   */
  public static void robotGhost(Pose2d setpoint) {
    PumpkinLog.log(kRobotGhost, setpoint);
  }

  /**
   * Publishes the active goal, or clears it.
   *
   * @param goal the goal pose, blue-origin; null publishes an empty array, which is how "no goal"
   *     is spelled so the ghost disappears instead of going stale
   */
  public static void goal(Pose2d goal) {
    if (goal == null) {
      PumpkinLog.log(kGoal, kNoPose2d);
    } else {
      m_oneGoal[0] = goal;
      PumpkinLog.log(kGoal, m_oneGoal);
    }
  }

  /**
   * Publishes the active trajectory.
   *
   * @param poses the trajectory samples, blue-origin; null or empty clears it
   */
  public static void trajectory(Pose2d[] poses) {
    PumpkinLog.log(kTrajectory, poses == null ? kNoPose2d : poses);
  }

  /**
   * Publishes the 2D vision pose estimates.
   *
   * @param poses the estimates, blue-origin
   */
  public static void visionPoses(Pose2d[] poses) {
    PumpkinLog.log(kVisionPoses, poses == null ? kNoPose2d : poses);
  }

  /**
   * Publishes the 3D vision targets.
   *
   * @param targets the target poses
   */
  public static void visionTargets(Pose3d[] targets) {
    PumpkinLog.log(kVisionTargets, targets == null ? kNoPose3d :targets);
  }

  /**
   * Publishes one type's game pieces.
   *
   * @param type the game piece type; becomes the final path segment
   * @param poses the piece poses
   */
  public static void gamePieces(String type, Pose3d[] poses) {
    Objects.requireNonNull(type, "FieldSchema.gamePieces: type must not be null.");
    // computeIfAbsent so the concatenation happens once per type per session, never per cycle.
    String key = m_gamePieceKeys.computeIfAbsent(type, t -> kGamePiecesPrefix + t);
    PumpkinLog.log(key, poses == null ? kNoPose3d :poses);
  }

  /**
   * Publishes how far the robot is from its trajectory setpoint.
   *
   * <p>One call for the pair: a path that is 2 cm off translationally and 40 degrees off rotationally
   * is a different failure from one that is 2 m off and pointing correctly, and either number alone
   * reads as success.
   *
   * @param errorMeters translational distance
   * @param errorDegrees rotational distance
   */
  public static void error(double errorMeters, double errorDegrees) {
    PumpkinLog.log(kErrorMeters, errorMeters, Units.Meters);
    PumpkinLog.log(kErrorDegrees, errorDegrees, Units.Degrees);
  }

  /** Forgets the per-type key cache, so one test cannot see another test's game piece types. */
  public static void resetForTest() {
    m_gamePieceKeys.clear();
  }

  /**
   * Section 3.4's table, in the order it lists the keys.
   *
   * @return the rows
   */
  public static List<SchemaEntry> schema() {
    return List.of(
        SchemaEntry.of(kRobot, "Pose2d", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Robot"),
        SchemaEntry.of(kRobotGhost, "Pose2d", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Ghost (trajectory setpoint this cycle)"),
        SchemaEntry.of(kGoal, "Pose2d[] (length 0 or 1)", SchemaEntry.kNoUnit, Tier.STANDARD, "Ghost #2. Array so \"no goal\" logs empty, not stale."),
        SchemaEntry.of(kTrajectory, "Pose2d[]", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Trajectory"),
        SchemaEntry.of(kVisionPoses, "Pose2d[]", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Vision Target (child of Robot)"),
        SchemaEntry.of(kVisionTargets, "Pose3d[]", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Vision Target (3D)"),
        SchemaEntry.of(kGamePiecesPrefix + kTypePlaceholder, "Pose3d[]", SchemaEntry.kNoUnit, Tier.STANDARD, "AdvantageScope object: Game piece"),
        SchemaEntry.of(kErrorMeters, "double", "meters", Tier.STANDARD),
        SchemaEntry.of(kErrorDegrees, "double", "degrees", Tier.STANDARD));
  }
}

package org.pumpkinlib.telemetry.schema;

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.units.Units;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.Demotable;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * The section 3.3 key block — {@code Pumpkin/Vision/&lt;Camera&gt;/} — as both a published contract
 * and the writer that fills it.
 *
 * <p>One instance per camera, created once and reused; every absolute key is concatenated in the
 * constructor so the per-cycle path allocates nothing. The roll-up keys at {@code Pumpkin/Vision/}
 * are static, because there is one set of them however many cameras a robot has.
 *
 * <h2>Rejections are logged, and that is the point of the block</h2>
 *
 * <p><i>"Why did vision not correct my pose"</i> is the number one triage question, and a silently
 * dropped rejection makes it unanswerable. {@link #kAccepted} is published every cycle whether the
 * observation was taken or not, and {@link #kRejectReason} carries the reason — the empty string
 * when accepted, never null and never absent. A camera publishing {@code Accepted=false} with an
 * empty reason is a schema-audit failure, not a quirk.
 *
 * <h2>AllTagPoses is DEBUG and supplier-gated, deliberately</h2>
 *
 * <p>It was the single biggest 2026 loop-time offender: an array of {@code Pose3d}s rebuilt and
 * published every cycle by every camera. {@link #allTagPoses(Supplier)} takes a supplier, so on FMS
 * — where the tier gate drops DEBUG — the array is never even constructed. The cost of the key when
 * it is off is one enum comparison, not one allocation.
 */
public final class VisionSchema {

  /** The block every camera hangs under, and the home of the roll-ups. */
  public static final String kPrefix = PumpkinLog.kRoot + "/Vision";

  /** Whether the camera is producing frames. */
  public static final String kConnected = "Connected";

  /** Frames per second the camera is actually delivering. */
  public static final String kFps = "Fps";

  /** Pipeline plus transport latency, in seconds. */
  public static final String kLatencySec = "LatencySec";

  /** The camera's mounting transform. Logged on change. */
  public static final String kRobotToCamera = "RobotToCamera";

  /** How many tags this frame saw. */
  public static final String kTagCount = "TagCount";

  /** Which tags this frame saw. */
  public static final String kTagIds = "TagIds";

  /** The pose this frame estimated. */
  public static final String kEstimatedPose = "EstimatedPose";

  /** Whether the estimate was handed to the pose estimator. */
  public static final String kAccepted = "Accepted";

  /** Why it was not, or the empty string when it was. */
  public static final String kRejectReason = "RejectReason";

  /** Single-tag pose ambiguity. */
  public static final String kAmbiguity = "Ambiguity";

  /** Mean distance to the tags in this frame, in meters. */
  public static final String kAvgTagDistanceMeters = "AvgTagDistanceMeters";

  /** The three standard deviations handed to the pose estimator. Demotable. */
  public static final String kStdDevs = "StdDevs";

  /** Every tag pose the camera can currently see. DEBUG, supplier-gated. */
  public static final String kAllTagPoses = "AllTagPoses";

  /** Roll-up: how many observations across all cameras were accepted this cycle. */
  public static final String kAcceptedThisCycle = kPrefix + "/AcceptedThisCycle";

  /** Roll-up: the fraction of this session's cycles in which every camera was connected. */
  public static final String kUptimeFraction = kPrefix + "/UptimeFraction";

  /** Roll-up: whether any camera is currently offline. */
  public static final String kAnyCameraOffline = kPrefix + "/AnyCameraOffline";

  /** The placeholder the static table uses where a real block carries the camera's name. */
  public static final String kCameraPlaceholder = "<Camera>";

  private final String m_camera;
  private final String m_prefix;
  private final String m_keyConnected;
  private final String m_keyFps;
  private final String m_keyLatencySec;
  private final String m_keyRobotToCamera;
  private final String m_keyTagCount;
  private final String m_keyTagIds;
  private final String m_keyEstimatedPose;
  private final String m_keyAccepted;
  private final String m_keyRejectReason;
  private final String m_keyAmbiguity;
  private final String m_keyAvgTagDistanceMeters;
  private final String m_keyStdDevs;
  private final String m_keyAllTagPoses;

  private Transform3d m_lastRobotToCamera;

  private VisionSchema(String camera) {
    m_camera = camera;
    m_prefix = kPrefix + '/' + camera;
    m_keyConnected = m_prefix + '/' + kConnected;
    m_keyFps = m_prefix + '/' + kFps;
    m_keyLatencySec = m_prefix + '/' + kLatencySec;
    m_keyRobotToCamera = m_prefix + '/' + kRobotToCamera;
    m_keyTagCount = m_prefix + '/' + kTagCount;
    m_keyTagIds = m_prefix + '/' + kTagIds;
    m_keyEstimatedPose = m_prefix + '/' + kEstimatedPose;
    m_keyAccepted = m_prefix + '/' + kAccepted;
    m_keyRejectReason = m_prefix + '/' + kRejectReason;
    m_keyAmbiguity = m_prefix + '/' + kAmbiguity;
    m_keyAvgTagDistanceMeters = m_prefix + '/' + kAvgTagDistanceMeters;
    m_keyStdDevs = m_prefix + '/' + kStdDevs;
    m_keyAllTagPoses = m_prefix + '/' + kAllTagPoses;
  }

  /**
   * The writer for one camera's block.
   *
   * @param cameraName the camera's {@code telemetryName()}; becomes the path segment
   * @return the writer, to be held for the life of the camera
   */
  public static VisionSchema forCamera(String cameraName) {
    Objects.requireNonNull(cameraName, "VisionSchema.forCamera: cameraName must not be null.");
    if (cameraName.isBlank()) {
      throw new IllegalArgumentException(
          "VisionSchema.forCamera: cameraName was blank. It becomes a log key segment, so it must "
              + "be a stable, unique, path-safe string such as \"FrontLeft\".");
    }
    return new VisionSchema(cameraName);
  }

  /**
   * The camera name.
   *
   * @return the name
   */
  public String camera() {
    return m_camera;
  }

  /**
   * The absolute key prefix, with no trailing slash.
   *
   * @return e.g. {@code "Pumpkin/Vision/FrontLeft"}
   */
  public String prefix() {
    return m_prefix;
  }

  /**
   * Publishes whether the camera is producing frames.
   *
   * @param connected true when it is
   */
  public void connected(boolean connected) {
    PumpkinLog.critical(m_keyConnected, connected);
  }

  /**
   * Publishes the delivered frame rate and the frame's total latency.
   *
   * <p>One call for the pair because they are read together: 30 fps at 300 ms of latency and 3 fps
   * at 30 ms are both broken, and neither number alone says which.
   *
   * @param fps frames per second actually delivered
   * @param latencySeconds pipeline plus transport latency
   */
  public void timing(double fps, double latencySeconds) {
    PumpkinLog.critical(m_keyFps, fps);
    PumpkinLog.critical(m_keyLatencySec, latencySeconds, Units.Seconds);
  }

  /**
   * Publishes the camera's mounting transform, <b>on change only</b>.
   *
   * <p>A mount transform that changes mid-match means someone re-ran calibration on a live robot;
   * every other cycle it is the same 32 bytes, and publishing it 50 times a second buys nothing.
   *
   * @param robotToCamera the transform; null publishes nothing
   */
  public void robotToCamera(Transform3d robotToCamera) {
    if (robotToCamera == null || robotToCamera.equals(m_lastRobotToCamera)) {
      return;
    }
    m_lastRobotToCamera = robotToCamera;
    PumpkinLog.log(m_keyRobotToCamera, robotToCamera);
  }

  /**
   * Publishes which tags this frame saw.
   *
   * @param tagIds the tag ids; the count is derived so the two can never disagree
   */
  public void tags(long[] tagIds) {
    PumpkinLog.critical(m_keyTagCount, tagIds.length);
    PumpkinLog.critical(m_keyTagIds, tagIds);
  }

  /**
   * Publishes this frame's pose estimate.
   *
   * @param estimatedPose the estimate
   */
  public void estimatedPose(Pose3d estimatedPose) {
    PumpkinLog.critical(m_keyEstimatedPose, estimatedPose);
  }

  /**
   * Publishes the accept/reject verdict and its reason.
   *
   * <p>One call for the pair, and the reason is normalised to the empty string on accept, because
   * the failure this block exists to prevent is a rejection with no reason recorded.
   *
   * @param accepted whether the estimate was handed to the pose estimator
   * @param rejectReason why it was not; ignored when {@code accepted}, and null becomes {@code ""}
   */
  public void verdict(boolean accepted, String rejectReason) {
    PumpkinLog.critical(m_keyAccepted, accepted);
    String reason = accepted || rejectReason == null ? "" : rejectReason;
    PumpkinLog.critical(m_keyRejectReason, reason);
  }

  /**
   * Publishes the two quality scalars behind the verdict.
   *
   * @param ambiguity single-tag pose ambiguity
   * @param avgTagDistanceMeters mean distance to the tags in this frame
   */
  public void quality(double ambiguity, double avgTagDistanceMeters) {
    PumpkinLog.log(m_keyAmbiguity, ambiguity);
    PumpkinLog.log(m_keyAvgTagDistanceMeters, avgTagDistanceMeters, Units.Meters);
  }

  /**
   * Publishes the three standard deviations handed to the pose estimator.
   *
   * <p>The unit is {@code Units.Value} — dimensionless — and that is the honest answer rather than a
   * convenient one. The three entries are meters, meters and radians, so no single unit label is
   * correct for the array, and {@code PumpkinLog}'s array overloads require a unit precisely so that
   * the call site has to answer the question rather than leave a bare row of numbers on a graph.
   * Labelling the whole array {@code Meters} would mis-convert the third element in AdvantageScope.
   *
   * @param stdDevs the three values, x/y/theta
   */
  public void stdDevs(double[] stdDevs) {
    PumpkinLog.log(m_keyStdDevs, stdDevs, Units.Value, Demotable.YES);
  }

  /**
   * Publishes every tag pose the camera can see, at DEBUG, <b>without building the array unless the
   * tier is on</b>.
   *
   * @param poses a supplier of the array; never invoked when DEBUG is gated off
   */
  public void allTagPoses(Supplier<Pose3d[]> poses) {
    PumpkinLog.debugArray(m_keyAllTagPoses, Pose3d.struct, poses);
  }

  // ===============================================================================================
  // Roll-ups — one set per robot, not per camera
  // ===============================================================================================

  /**
   * Publishes how many observations across all cameras were accepted this cycle.
   *
   * @param count the count
   */
  public static void acceptedThisCycle(long count) {
    PumpkinLog.critical(kAcceptedThisCycle, count);
  }

  /**
   * Publishes the fraction of this session's cycles in which every camera was connected.
   *
   * @param fraction zero to one
   */
  public static void uptimeFraction(double fraction) {
    PumpkinLog.log(kUptimeFraction, fraction);
  }

  /**
   * Publishes whether any camera is currently offline.
   *
   * @param offline true when at least one camera is not reporting
   */
  public static void anyCameraOffline(boolean offline) {
    PumpkinLog.critical(kAnyCameraOffline, offline);
  }

  // ===============================================================================================
  // The published contract
  // ===============================================================================================

  /**
   * Section 3.3's per-camera table, in the order it lists the keys.
   *
   * @return the rows, with {@code <Camera>} left as a literal placeholder
   */
  public static List<SchemaEntry> schema() {
    String p = kPrefix + "/" + kCameraPlaceholder + "/";
    return List.of(
        SchemaEntry.of(p + kConnected, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kFps, "double", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kLatencySec, "double", "seconds", Tier.CRITICAL),
        SchemaEntry.of(p + kRobotToCamera, "Transform3d struct", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change"),
        SchemaEntry.of(p + kTagCount, "long", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kTagIds, "long[]", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kEstimatedPose, "Pose3d struct", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kAccepted, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kRejectReason, "String", SchemaEntry.kNoUnit, Tier.CRITICAL, "\"\" when accepted; never absent"),
        SchemaEntry.of(p + kAmbiguity, "double", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(p + kAvgTagDistanceMeters, "double", "meters", Tier.STANDARD),
        SchemaEntry.demotable(p + kStdDevs, "double[3]", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(p + kAllTagPoses, "Pose3d[]", SchemaEntry.kNoUnit, Tier.DEBUG, "Supplier-gated: the single biggest 2026 loop-time offender"));
  }

  /**
   * Section 3.3's roll-ups at {@code Pumpkin/Vision/}.
   *
   * @return the rows
   */
  public static List<SchemaEntry> rollupSchema() {
    return List.of(
        SchemaEntry.of(kAcceptedThisCycle, "long", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(kUptimeFraction, "double", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(kAnyCameraOffline, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL));
  }
}

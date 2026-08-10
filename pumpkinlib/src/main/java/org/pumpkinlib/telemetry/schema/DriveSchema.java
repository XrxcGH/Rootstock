package org.pumpkinlib.telemetry.schema;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.units.Units;
import java.util.List;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.Demotable;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * The section 3.2 key block — {@code Pumpkin/Drive/} — as both a published contract and the writer
 * that fills it.
 *
 * <p>Every key here is a fixed string, because there is one drivetrain. That is why this class is
 * static where {@link MechanismSchema} is an instance: no prefix has to be computed, so every
 * absolute key is a compile-time constant and the writer allocates nothing at all.
 *
 * <p><b>Three keys are {@link Demotable#YES} and the choice of which three is the whole design.</b>
 * {@code Pose3d}, {@code ModuleStates/SetpointOptimized} and {@code ModulePositions} are the fat,
 * derived keys: a 3D pose that {@code Pose} already carries in 2D, the post-optimisation copy of
 * setpoints whose pre-optimisation form is CRITICAL, and the raw odometry deltas that
 * {@code ModuleStates/Measured} summarises. Under sustained overload the governor may sample those
 * three at 10 Hz. It may never touch {@code Pose}, the two {@code ChassisSpeeds} or the gyro, because
 * those are what "where was the robot and what did we ask it to do" is read from.
 *
 * <h2>Two gyro yaws, on purpose</h2>
 *
 * <p>{@code Gyro/YawRad} is the offset-corrected, blue-origin field heading. {@code Gyro/RawYawRad}
 * is the raw, unreferenced IMU yaw. Logging both makes "these two curves are identical" a one-glance
 * diagnosis of an offset that was never seeded — a failure that otherwise presents as a robot that
 * drives field-relative in the wrong direction and nothing in the log saying why.
 */
public final class DriveSchema {

  /** Everything in this block hangs off this prefix. */
  public static final String kPrefix = PumpkinLog.kRoot + "/Drive";

  /** Fused pose estimate. */
  public static final String kPose = kPrefix + "/Pose";

  /** 3D pose. Demotable: {@link #kPose} already carries the part match triage reads. */
  public static final String kPose3d = kPrefix + "/Pose3d";

  /** Pose with no vision correction applied — the other half of the divergence metric. */
  public static final String kOdometryOnlyPose = kPrefix + "/OdometryOnlyPose";

  /** How far the fused estimate has moved away from odometry-only, in meters. */
  public static final String kVisionDivergenceMeters = kPrefix + "/VisionDivergenceMeters";

  /** Measured chassis speeds, robot relative. */
  public static final String kChassisSpeedsMeasured = kPrefix + "/ChassisSpeeds/Measured";

  /** Commanded chassis speeds, robot relative. */
  public static final String kChassisSpeedsSetpoint = kPrefix + "/ChassisSpeeds/Setpoint";

  /** Measured module states. */
  public static final String kModuleStatesMeasured = kPrefix + "/ModuleStates/Measured";

  /** Commanded module states, before optimisation. */
  public static final String kModuleStatesSetpoint = kPrefix + "/ModuleStates/Setpoint";

  /** Commanded module states after angle optimisation. Demotable. */
  public static final String kModuleStatesSetpointOptimized = kPrefix + "/ModuleStates/SetpointOptimized";

  /** Module drive distances and angles. Demotable. */
  public static final String kModulePositions = kPrefix + "/ModulePositions";

  /** Whether the IMU is reporting. */
  public static final String kGyroConnected = kPrefix + "/Gyro/Connected";

  /** Offset-corrected, blue-origin field heading, in radians. */
  public static final String kGyroYawRad = kPrefix + "/Gyro/YawRad";

  /** Raw, unreferenced IMU yaw, in radians. */
  public static final String kGyroRawYawRad = kPrefix + "/Gyro/RawYawRad";

  /** Yaw rate, in radians per second. */
  public static final String kGyroYawRateRadPerSec = kPrefix + "/Gyro/YawRateRadPerSec";

  /** Chassis ground speed magnitude. */
  public static final String kSpeedMetersPerSec = kPrefix + "/SpeedMetersPerSec";

  /** Ratio of fastest to slowest module ground speed; a skid detector. */
  public static final String kSkidRatio = kPrefix + "/SkidRatio";

  /** The path segment each per-module section 3.1 block hangs under. */
  public static final String kModulePrefix = "Module";

  private DriveSchema() {}

  // ===============================================================================================
  // Writers
  // ===============================================================================================

  /**
   * Publishes the fused pose estimate.
   *
   * @param pose the pose, blue-origin
   */
  public static void pose(Pose2d pose) {
    PumpkinLog.critical(kPose, pose);
  }

  /**
   * Publishes the 3D pose.
   *
   * @param pose the pose, blue-origin
   */
  public static void pose3d(Pose3d pose) {
    PumpkinLog.log(kPose3d, pose, Demotable.YES);
  }

  /**
   * Publishes the odometry-only pose — no vision correction.
   *
   * @param pose the pose, blue-origin
   */
  public static void odometryOnlyPose(Pose2d pose) {
    PumpkinLog.log(kOdometryOnlyPose, pose);
  }

  /**
   * Publishes how far vision has pulled the fused estimate away from odometry-only.
   *
   * <p>CRITICAL, and this is the key that answers the single most common vision question. A number
   * that grows without bound says vision is fighting odometry; a number pinned at zero says vision
   * is not being accepted at all, which looks identical from the driver's seat.
   *
   * @param meters the divergence in meters
   */
  public static void visionDivergenceMeters(double meters) {
    PumpkinLog.critical(kVisionDivergenceMeters, meters, Units.Meters);
  }

  /**
   * Publishes the measured chassis speeds, robot relative.
   *
   * @param speeds the speeds
   */
  public static void measuredSpeeds(ChassisSpeeds speeds) {
    PumpkinLog.critical(kChassisSpeedsMeasured, speeds);
  }

  /**
   * Publishes the commanded chassis speeds, robot relative.
   *
   * @param speeds the speeds
   */
  public static void setpointSpeeds(ChassisSpeeds speeds) {
    PumpkinLog.critical(kChassisSpeedsSetpoint, speeds);
  }

  /**
   * Publishes the measured module states.
   *
   * @param states the states, in module order
   */
  public static void measuredStates(SwerveModuleState[] states) {
    PumpkinLog.critical(kModuleStatesMeasured, states);
  }

  /**
   * Publishes the commanded module states before optimisation.
   *
   * @param states the states, in module order
   */
  public static void setpointStates(SwerveModuleState[] states) {
    PumpkinLog.critical(kModuleStatesSetpoint, states);
  }

  /**
   * Publishes the commanded module states after angle optimisation.
   *
   * @param states the states, in module order
   */
  public static void optimizedSetpointStates(SwerveModuleState[] states) {
    PumpkinLog.log(kModuleStatesSetpointOptimized, states, Demotable.YES);
  }

  /**
   * Publishes the module drive distances and angles.
   *
   * @param positions the positions, in module order
   */
  public static void modulePositions(SwerveModulePosition[] positions) {
    PumpkinLog.log(kModulePositions, positions, Demotable.YES);
  }

  /**
   * Publishes the whole gyro block in one call.
   *
   * <p>One call rather than four because the four values are only useful together: a heading with no
   * connection flag beside it is a heading you cannot trust, and a corrected yaw with no raw yaw
   * beside it hides the unseeded-offset failure this block exists to expose.
   *
   * @param connected whether the IMU is reporting
   * @param yawRad the offset-corrected, blue-origin field heading in radians
   * @param rawYawRad the raw, unreferenced IMU yaw in radians
   * @param yawRateRadPerSec the yaw rate in radians per second
   */
  public static void gyro(boolean connected, double yawRad, double rawYawRad, double yawRateRadPerSec) {
    PumpkinLog.critical(kGyroConnected, connected);
    PumpkinLog.critical(kGyroYawRad, yawRad, Units.Radians);
    PumpkinLog.critical(kGyroRawYawRad, rawYawRad, Units.Radians);
    PumpkinLog.critical(kGyroYawRateRadPerSec, yawRateRadPerSec, Units.RadiansPerSecond);
  }

  /**
   * Publishes the chassis ground speed magnitude.
   *
   * @param metersPerSecond the speed
   */
  public static void speedMetersPerSec(double metersPerSecond) {
    PumpkinLog.log(kSpeedMetersPerSec, metersPerSecond, Units.MetersPerSecond);
  }

  /**
   * Publishes the skid ratio: fastest module ground speed over slowest.
   *
   * @param ratio the ratio; 1.0 means no skid
   */
  public static void skidRatio(double ratio) {
    PumpkinLog.log(kSkidRatio, ratio);
  }

  /**
   * The absolute prefix of one module's section 3.1 block.
   *
   * <p>Pass the returned string to {@code MechanismSchema.declareNested(...)} only if the module
   * source's {@code telemetryName()} is not already {@code "Module<i>"} — normally it is, and
   * {@code declareNested(DriveSchema.kPrefix, module, Tier.STANDARD)} is the whole call.
   *
   * @param index the module index, zero-based
   * @return e.g. {@code "Pumpkin/Drive/Module0"}
   */
  public static String modulePrefix(int index) {
    return kPrefix + "/" + kModulePrefix + index;
  }

  // ===============================================================================================
  // The published contract
  // ===============================================================================================

  /**
   * Section 3.2's table, in the order it lists the keys.
   *
   * @return the rows
   */
  public static List<SchemaEntry> schema() {
    return List.of(
        SchemaEntry.of(kPose, "Pose2d struct", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.demotable(kPose3d, "Pose3d struct", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(kOdometryOnlyPose, "Pose2d struct", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(kVisionDivergenceMeters, "double", "meters", Tier.CRITICAL),
        SchemaEntry.of(kChassisSpeedsMeasured, "ChassisSpeeds struct", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(kChassisSpeedsSetpoint, "ChassisSpeeds struct", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(kModuleStatesMeasured, "SwerveModuleState[]", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(kModuleStatesSetpoint, "SwerveModuleState[]", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.demotable(kModuleStatesSetpointOptimized, "SwerveModuleState[]", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.demotable(kModulePositions, "SwerveModulePosition[]", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(kGyroConnected, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(kGyroYawRad, "double", "radians", Tier.CRITICAL, "Offset-corrected, blue-origin field heading"),
        SchemaEntry.of(kGyroRawYawRad, "double", "radians", Tier.CRITICAL, "Raw unreferenced IMU yaw; identical curves means the offset was never seeded"),
        SchemaEntry.of(kGyroYawRateRadPerSec, "double", "radians per second", Tier.CRITICAL),
        SchemaEntry.of(kSpeedMetersPerSec, "double", "meters per second", Tier.STANDARD),
        SchemaEntry.of(kSkidRatio, "double", SchemaEntry.kNoUnit, Tier.STANDARD),
        SchemaEntry.of(
            kPrefix + "/" + kModulePrefix + "<i>/...",
            "the full section 3.1 mechanism block",
            SchemaEntry.kNoUnit,
            Tier.STANDARD,
            "Declared with MechanismSchema.declareNested(DriveSchema.kPrefix, module, Tier.STANDARD)"));
  }
}

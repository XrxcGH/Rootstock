package org.rootstock.hardware.phoenix;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.MetersPerSecondPerSecond;
import static edu.wpi.first.units.Units.RadiansPerSecond;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.hardware.Pigeon2;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.LinearAcceleration;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.hardware.GyroIO;

/**
 * A CTRE Pigeon 2 on CAN.
 *
 * <p>CORE owns only the minimal gyro seam: yaw, tilt, yaw rate and acceleration, batched into one
 * bus transaction. Odometry, the high-frequency yaw thread and the timestamp arrays belong to the
 * drivetrain domain, which fills {@code GyroInputs.odometryTimestamps} and
 * {@code odometryYawPositions} itself; for a CORE consumer those stay empty, which is the honest
 * report rather than a single-sample array pretending to be a 250 Hz history.
 *
 * <h2>What CORE actually uses a gyro for</h2>
 *
 * <p>One thing: {@code RotaryAxis.fieldLocked(gyro)} supplies the chassis-omega counter-rotation
 * feedforward that a turret needs in order to stay pointed at a target while the chassis spins. That
 * term reaches the motor through {@code MotorIO.setPositionGoal(rot, rps, arbFf)}'s <b>velocity</b>
 * parameter, and on a Phoenix backend it is carried to the device as volts because the Motion Magic
 * requests have no velocity field. {@link GyroIO.GyroInputs#yawVelocityRadPerSec} is therefore load-bearing, not
 * decorative, and is subscribed at the same rate as yaw.
 *
 * <h2>Units, stated because two of them are easy to get wrong</h2>
 *
 * <ul>
 *   <li>Yaw, pitch and roll are returned as {@link Rotation2d}, built from the device's degrees.
 *   <li>Yaw velocity is radians per second — SI, like everything the control layer consumes.
 *   <li>Acceleration is in <b>g</b>, converted from the typed measure rather than from
 *       {@code getValueAsDouble()}, so it stays correct whichever base unit Phoenix reports in.
 * </ul>
 */
public final class Pigeon2GyroIO implements GyroIO {

  /** Yaw and yaw rate: the two the drivetrain and a field-locked turret both read every loop. */
  public static final double kYawRateHz = 100.0;

  /** Tilt: a tipping robot is a slow event by the standards of a control loop. */
  public static final double kTiltRateHz = 20.0;

  /** Acceleration: collision detection, not control. */
  public static final double kAccelerationRateHz = 20.0;

  /** Standard gravity, for the metres-per-second-squared to g conversion. */
  private static final double kGravityMetersPerSecondSquared = 9.80665;

  private final Pigeon2 m_pigeon;
  private final Pigeon2Configuration m_config = new Pigeon2Configuration();
  private final String m_name;

  private final StatusSignal<Angle> m_yaw;
  private final StatusSignal<Angle> m_pitch;
  private final StatusSignal<Angle> m_roll;
  private final StatusSignal<AngularVelocity> m_yawVelocity;
  private final StatusSignal<LinearAcceleration> m_accelX;
  private final StatusSignal<LinearAcceleration> m_accelY;
  private final StatusSignal<LinearAcceleration> m_accelZ;
  private final BaseStatusSignal[] m_batch;

  /**
   * Opens a Pigeon 2 and configures its mount pose.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name; blank means the roboRIO's own bus
   * @param mountYawDegrees how far the Pigeon is rotated about the robot's vertical axis relative to
   *     forward; zero when the arrow on the case points forward
   */
  public Pigeon2GyroIO(int deviceId, String canBus, double mountYawDegrees) {
    m_pigeon = new Pigeon2(deviceId, PhoenixUtil.bus(canBus));
    m_name = "Pigeon2 " + deviceId;
    m_config.MountPose.MountPoseYaw = Double.isFinite(mountYawDegrees) ? mountYawDegrees : 0.0;
    PhoenixUtil.applyVerified(m_pigeon, m_config, m_name);

    m_yaw = m_pigeon.getYaw();
    m_pitch = m_pigeon.getPitch();
    m_roll = m_pigeon.getRoll();
    m_yawVelocity = m_pigeon.getAngularVelocityZWorld();
    m_accelX = m_pigeon.getAccelerationX();
    m_accelY = m_pigeon.getAccelerationY();
    m_accelZ = m_pigeon.getAccelerationZ();
    m_batch =
        new BaseStatusSignal[] {
          m_yaw, m_pitch, m_roll, m_yawVelocity, m_accelX, m_accelY, m_accelZ
        };

    BaseStatusSignal.setUpdateFrequencyForAll(kYawRateHz, m_yaw, m_yawVelocity);
    BaseStatusSignal.setUpdateFrequencyForAll(kTiltRateHz, m_pitch, m_roll);
    BaseStatusSignal.setUpdateFrequencyForAll(kAccelerationRateHz, m_accelX, m_accelY, m_accelZ);
    // Last, after the frequencies. Optimising first would zero everything just requested.
    m_pigeon.optimizeBusUtilization();

    // Consume the power-on reset flag so the first periodic() does not report a reset that is only
    // "the device has been powered on", exactly as the motor backends do.
    m_pigeon.hasResetOccurred();
  }

  /**
   * Opens a Pigeon 2 on the default bus with no mount rotation.
   *
   * @param deviceId the CAN device id
   */
  public Pigeon2GyroIO(int deviceId) {
    this(deviceId, "", 0.0);
  }

  /**
   * Wraps a Pigeon 2 you already own — for a drivetrain that also runs a high-frequency yaw thread
   * on the same device.
   *
   * @param pigeon the device
   */
  public Pigeon2GyroIO(Pigeon2 pigeon) {
    m_pigeon = Objects.requireNonNull(pigeon, "Pigeon2GyroIO: device must not be null");
    m_name = "Pigeon2 " + pigeon.getDeviceID();
    m_yaw = m_pigeon.getYaw();
    m_pitch = m_pigeon.getPitch();
    m_roll = m_pigeon.getRoll();
    m_yawVelocity = m_pigeon.getAngularVelocityZWorld();
    m_accelX = m_pigeon.getAccelerationX();
    m_accelY = m_pigeon.getAccelerationY();
    m_accelZ = m_pigeon.getAccelerationZ();
    m_batch =
        new BaseStatusSignal[] {
          m_yaw, m_pitch, m_roll, m_yawVelocity, m_accelX, m_accelY, m_accelZ
        };
    m_pigeon.hasResetOccurred();
  }

  @Override
  public void updateInputs(GyroInputs inputs) {
    if (inputs == null) {
      return;
    }
    boolean ok = BaseStatusSignal.refreshAll(m_batch).isOK();
    inputs.connected = ok && m_pigeon.isConnected();
    inputs.yaw = Rotation2d.fromDegrees(m_yaw.getValue().in(Degrees));
    inputs.pitch = Rotation2d.fromDegrees(m_pitch.getValue().in(Degrees));
    inputs.roll = Rotation2d.fromDegrees(m_roll.getValue().in(Degrees));
    inputs.yawVelocityRadPerSec = m_yawVelocity.getValue().in(RadiansPerSecond);
    inputs.accelXG = toG(m_accelX);
    inputs.accelYG = toG(m_accelY);
    inputs.accelZG = toG(m_accelZ);
    // odometryTimestamps and odometryYawPositions are deliberately left alone: only the drivetrain's
    // high-frequency thread can fill them honestly, and a one-sample array here would look like a
    // history that does not exist.
  }

  @Override
  public void setYaw(Rotation2d yaw) {
    if (yaw == null) {
      return;
    }
    // The zero-timeout overload: setYaw is reachable from a driver's "reset field-oriented heading"
    // button, and the default overload blocks the main loop waiting for an ack.
    m_pigeon.setYaw(yaw.getDegrees(), 0.0);
  }

  /**
   * The live Pigeon 2, for the drivetrain's high-frequency yaw thread and for anything Rootstock
   * does not model.
   *
   * @return the device
   */
  public Pigeon2 pigeon() {
    return m_pigeon;
  }

  /**
   * The yaw signal, so a drivetrain odometry thread can subscribe it at its own rate rather than
   * opening a second handle to the same device.
   *
   * @return the yaw status signal
   */
  public StatusSignal<Angle> yawSignal() {
    return m_yaw;
  }

  /**
   * The gyro as the boot dump prints it.
   *
   * @return a multi-line block, no trailing newline
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s%n  device            %s%n  mount pose        yaw %.2f deg%n"
            + "  signals           YAW@%.0fHz YAW_RATE@%.0fHz PITCH/ROLL@%.0fHz ACCEL@%.0fHz%n"
            + "  odometry arrays   EMPTY -- only the drivetrain's high-frequency thread fills those",
        m_name,
        PhoenixUtil.describeDevice(m_pigeon),
        m_config.MountPose.MountPoseYaw,
        kYawRateHz,
        kYawRateHz,
        kTiltRateHz,
        kAccelerationRateHz);
  }

  private static double toG(StatusSignal<LinearAcceleration> signal) {
    // Converted from the TYPED measure, not from getValueAsDouble(), so this stays right whichever
    // base unit Phoenix reports the acceleration in.
    return signal.getValue().in(MetersPerSecondPerSecond) / kGravityMetersPerSecondSquared;
  }
}

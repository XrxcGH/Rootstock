package org.rootstock.hardware;

import edu.wpi.first.math.geometry.Rotation2d;
import java.util.Optional;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * The minimal gyro seam.
 *
 * <p>Core owns only this. Odometry, high-frequency sampling and the yaw thread belong to the
 * drivetrain domain, which fills {@link GyroInputs#odometryTimestamps} and {@link
 * GyroInputs#odometryYawPositions}; for a core consumer those two arrays are empty and that is
 * correct rather than broken.
 *
 * <p>Core's own use of a gyro is narrow and specific: a field-locked rotary axis — a turret that
 * holds a heading in the field frame while the chassis spins under it — needs the chassis angular
 * velocity as a counter-rotation feedforward. That number reaches the motor through the
 * <i>velocity</i> parameter of {@link MotorIO#setPositionGoal(double, double, double)}. An earlier
 * revision of this design omitted that parameter, which made the feature unimplementable; the
 * surveyed robot code computes the same term by hand in a subsystem and one template drops it
 * silently.
 */
public interface GyroIO {

  /**
   * Refresh the gyro's signals and fill {@code inputs}.
   *
   * @param inputs the inputs object to fill; the same instance every loop
   */
  void updateInputs(GyroInputs inputs);

  /**
   * Re-zero the yaw to a known heading.
   *
   * <p>Non-blocking where the device allows it. Called at the start of autonomous once the alliance
   * is known, and from a driver "reset heading" binding.
   *
   * @param yaw the heading the robot is actually facing
   */
  void setYaw(Rotation2d yaw);

  /**
   * The typed escape hatch — reach the real {@code Pigeon2} or navX object.
   *
   * @param <T> the concrete IO type being asked for
   * @param type the backend class
   * @return this IO as that type, or empty if it is a different backend
   */
  default <T extends GyroIO> Optional<T> as(Class<T> type) {
    return type != null && type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
  }

  /**
   * Everything read from a gyro.
   *
   * <p>Angles are {@link Rotation2d} rather than doubles because a bare {@code double} yaw is the
   * single most reliably unit-confused value on an FRC robot, and because {@code Rotation2d} logs as
   * a struct that AdvantageScope draws.
   *
   * <p>Note that these fields default to zero rather than NaN: unlike a motor status signal, a gyro
   * that is not connected has {@code connected == false}, which is the honest signal, and a
   * {@code Rotation2d} cannot hold NaN without poisoning every pose it touches.
   */
  class GyroInputs implements LoggableInputs {

    /** Whether the gyro is answering. */
    public boolean connected = false;

    /** Yaw, counter-clockwise positive, continuous (not wrapped to +/-180). */
    public Rotation2d yaw = Rotation2d.kZero;

    /** Yaw rate about the field Z axis, radians per second, counter-clockwise positive. */
    public double yawVelocityRadPerSec = 0.0;

    /** Pitch. Used by tip detection and by an elevator's "am I about to fall over" check. */
    public Rotation2d pitch = Rotation2d.kZero;

    /** Roll. */
    public Rotation2d roll = Rotation2d.kZero;

    /** Acceleration along the robot X axis, in g. */
    public double accelXG = 0.0;

    /** Acceleration along the robot Y axis, in g. */
    public double accelYG = 0.0;

    /** Acceleration along the robot Z axis, in g. */
    public double accelZG = 0.0;

    /**
     * Timestamps of the high-frequency yaw samples.
     *
     * <p>Filled ONLY by the drivetrain's odometry thread. Empty for core consumers, and empty is the
     * correct value rather than a missing one.
     */
    public double[] odometryTimestamps = new double[0];

    /** Yaw samples matching {@link #odometryTimestamps}, one for one. */
    public Rotation2d[] odometryYawPositions = new Rotation2d[0];

    /**
     * Writes every field under its frozen schema name.
     *
     * @param t the table for this gyro's inputs subtable
     */
    @Override
    public void toLog(LogTable t) {
      t.put("Connected", connected);
      t.put("Yaw", yaw);
      t.put("YawVelocityRadPerSec", yawVelocityRadPerSec);
      t.put("Pitch", pitch);
      t.put("Roll", roll);
      t.put("AccelXG", accelXG);
      t.put("AccelYG", accelYG);
      t.put("AccelZG", accelZG);
      t.put("OdometryTimestamps", odometryTimestamps);
      t.put("OdometryYawPositions", odometryYawPositions);
    }

    /**
     * Reads every field back during replay.
     *
     * @param t the table for this gyro's inputs subtable
     */
    @Override
    public void fromLog(LogTable t) {
      connected = t.get("Connected", connected);
      yaw = t.get("Yaw", yaw);
      yawVelocityRadPerSec = t.get("YawVelocityRadPerSec", yawVelocityRadPerSec);
      pitch = t.get("Pitch", pitch);
      roll = t.get("Roll", roll);
      accelXG = t.get("AccelXG", accelXG);
      accelYG = t.get("AccelYG", accelYG);
      accelZG = t.get("AccelZG", accelZG);
      odometryTimestamps = t.get("OdometryTimestamps", odometryTimestamps);
      odometryYawPositions = t.get("OdometryYawPositions", odometryYawPositions);
    }
  }
}

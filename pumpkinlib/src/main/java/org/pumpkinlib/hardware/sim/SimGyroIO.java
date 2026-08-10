package org.pumpkinlib.hardware.sim;

import edu.wpi.first.math.geometry.Rotation2d;
import java.util.function.DoubleSupplier;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.hardware.GyroIO;

/**
 * A gyro with no hardware behind it, driven by the simulated chassis.
 *
 * <p>Its job is narrow and specific: make the <b>field-locked axis</b> feature testable. A turret
 * that holds a field heading while the chassis spins under it needs a chassis angular velocity, and
 * that number reaches the motor through the velocity parameter of {@code
 * MotorIO.setPositionGoal(rot, rps, arbFf)}. Without a simulated gyro the whole path is untestable,
 * and an untestable path is where the surveyed template silently drops the term.
 *
 * <p>Two ways to drive it. Either hand it a yaw-rate supplier — the drivetrain simulation's omega —
 * and let it integrate, or call {@link #setSimulatedYaw(Rotation2d)} directly from a test. The
 * integrating form is the realistic one, because it reproduces the thing that actually bites: the
 * yaw a mechanism reads is one loop stale.
 */
public final class SimGyroIO implements GyroIO {

  private final DoubleSupplier m_yawRateRadPerSec;

  private Rotation2d m_yaw = Rotation2d.kZero;
  private Rotation2d m_pitch = Rotation2d.kZero;
  private Rotation2d m_roll = Rotation2d.kZero;
  private double m_lastRateRadPerSec;
  private boolean m_connected = true;

  /** A gyro that never moves — the right default for a mechanism test that does not spin. */
  public SimGyroIO() {
    this(() -> 0.0);
  }

  /**
   * A gyro that integrates a supplied yaw rate every {@code updateInputs}.
   *
   * @param yawRateRadPerSec the simulated chassis angular velocity, radians per second,
   *     counter-clockwise positive; null is read as a stationary chassis
   */
  public SimGyroIO(DoubleSupplier yawRateRadPerSec) {
    m_yawRateRadPerSec = yawRateRadPerSec == null ? () -> 0.0 : yawRateRadPerSec;
  }

  @Override
  public void updateInputs(GyroInputs inputs) {
    double rate = m_yawRateRadPerSec.getAsDouble();
    m_lastRateRadPerSec = Double.isFinite(rate) ? rate : 0.0;
    m_yaw = m_yaw.plus(Rotation2d.fromRadians(m_lastRateRadPerSec * Clock.dt()));
    if (inputs == null) {
      return;
    }
    inputs.connected = m_connected;
    inputs.yaw = m_yaw;
    inputs.yawVelocityRadPerSec = m_lastRateRadPerSec;
    inputs.pitch = m_pitch;
    inputs.roll = m_roll;
    inputs.accelXG = 0.0;
    inputs.accelYG = 0.0;
    inputs.accelZG = 1.0;
    // The odometry arrays belong to the drivetrain's high-frequency thread. Empty here is the
    // correct value, not a missing one, and filling them with the single 50 Hz sample would make a
    // simulated odometry test pass for the wrong reason.
    inputs.odometryTimestamps = new double[0];
    inputs.odometryYawPositions = new Rotation2d[0];
  }

  @Override
  public void setYaw(Rotation2d yaw) {
    if (yaw != null) {
      m_yaw = yaw;
    }
  }

  /**
   * Set the simulated yaw directly, for a test that wants to place the chassis rather than spin it.
   *
   * @param yaw the heading the simulated robot is facing
   */
  public void setSimulatedYaw(Rotation2d yaw) {
    setYaw(yaw);
  }

  /**
   * Set the simulated pitch and roll, for tip-detection and elevator-stability tests.
   *
   * @param pitch the simulated pitch
   * @param roll the simulated roll
   */
  public void setSimulatedTilt(Rotation2d pitch, Rotation2d roll) {
    if (pitch != null) {
      m_pitch = pitch;
    }
    if (roll != null) {
      m_roll = roll;
    }
  }

  /**
   * Simulate the gyro falling off the CAN bus, so the "what happens when the gyro dies" path is
   * exercised by a test rather than by a match.
   *
   * @param connected whether the simulated gyro answers
   */
  public void setSimulatedConnected(boolean connected) {
    m_connected = connected;
  }

  /**
   * The simulated heading, for assertions.
   *
   * @return the current yaw
   */
  public Rotation2d simulatedYaw() {
    return m_yaw;
  }
}

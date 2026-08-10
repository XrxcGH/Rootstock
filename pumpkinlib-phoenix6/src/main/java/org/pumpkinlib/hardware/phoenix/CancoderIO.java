package org.pumpkinlib.hardware.phoenix;

import static edu.wpi.first.units.Units.Rotations;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.signals.MagnetHealthValue;
import com.ctre.phoenix6.signals.SensorDirectionValue;
import edu.wpi.first.units.measure.Angle;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.config.FeedbackSpec;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.hardware.AbsoluteEncoderIO;

/**
 * A CTRE CANcoder, answering the one question the encoder seam abstracts: <b>what is the
 * mechanism's absolute position, and do I trust it?</b>
 *
 * <h2>What this reports, and in what units</h2>
 *
 * <p>{@code AbsoluteEncoderInputs.absolutePositionRot} is in <b>output rotations</b>, not sensor
 * rotations — the same unit as every other number crossing a PumpkinLib hardware seam. A CANcoder
 * mounted on the joint has {@code sensorPerOutput == 1.0} and the two coincide; a CANcoder behind a
 * belt reduction does not, and dividing by {@code sensorPerOutput} here is the difference between an
 * arm that lands on its setpoint and one that lands somewhere proportional to it.
 *
 * <p>{@code rawPositionRot} is the same reading with the magnet offset <b>removed</b> — which is
 * what a student needs to see while holding the mechanism against its hard stop, because that number
 * is the offset they are about to record.
 *
 * <h2>Fused, remote, and the difference that shows up in behaviour</h2>
 *
 * <p>Under {@link FeedbackSpec.FusedCancoder} the TalonFX fuses this sensor on-device and PumpkinLib
 * performs <b>no</b> guarded re-seed: Phoenix already keeps the rotor and the CANcoder in step, and
 * a second re-seed from the roboRIO would fight it. Under {@link FeedbackSpec.RemoteCancoder} the
 * mechanism layer re-seeds the motor's internal sensor from this one at boot and immediately before
 * a move — but <b>never during</b> one, because shifting the reference frame mid-motion makes the
 * mechanism land off target. Both facts are printed by {@link #describe()} so a student can see why
 * the code path differs.
 *
 * <h2>Magnet health is not decoration</h2>
 *
 * <p>A magnet that has drifted out of range reads plausibly and wrongly. This class raises a
 * pit-only alert naming the device the first time the device reports anything but a good magnet,
 * rather than leaving it to be discovered as "the arm is 8 degrees off on Saturday".
 */
public final class CancoderIO implements AbsoluteEncoderIO {

  /** How often the absolute position is polled, in hertz. */
  public static final double kPositionRateHz = 100.0;

  /** How often the velocity is polled, in hertz. */
  public static final double kVelocityRateHz = 50.0;

  /** How often magnet health is polled, in hertz — it changes when a magnet falls off, not sooner. */
  public static final double kHealthRateHz = 4.0;

  private final CANcoder m_cancoder;
  private final CANcoderConfiguration m_config = new CANcoderConfiguration();
  private final String m_name;
  private final double m_sensorPerOutput;
  private final double m_magnetOffsetRot;
  private final boolean m_fusedOnDevice;

  private final StatusSignal<Angle> m_absolutePosition;
  private final StatusSignal<edu.wpi.first.units.measure.AngularVelocity> m_velocity;
  private final StatusSignal<MagnetHealthValue> m_magnetHealth;
  private final BaseStatusSignal[] m_batch;

  private PumpkinAlert m_magnetAlert;

  /**
   * Opens the CANcoder a {@link FeedbackSpec} declares and configures its magnet offset.
   *
   * @param owner the mechanism name, for alerts and the boot dump
   * @param spec a {@link FeedbackSpec.FusedCancoder} or {@link FeedbackSpec.RemoteCancoder}; any
   *     other variant is a caller error and yields an encoder that reports NaN rather than throwing
   *     from a config field initialiser
   */
  public CancoderIO(String owner, FeedbackSpec spec) {
    Objects.requireNonNull(spec, "CancoderIO: FeedbackSpec must not be null");
    int id;
    String bus;
    if (spec instanceof FeedbackSpec.FusedCancoder fused) {
      id = fused.cancoderId();
      bus = fused.canBus();
      m_sensorPerOutput = fused.sensorPerOutput();
      m_magnetOffsetRot = fused.magnetOffset().in(Rotations);
      m_fusedOnDevice = true;
    } else if (spec instanceof FeedbackSpec.RemoteCancoder remote) {
      id = remote.cancoderId();
      bus = remote.canBus();
      m_sensorPerOutput = remote.sensorPerOutput();
      m_magnetOffsetRot = remote.magnetOffset().in(Rotations);
      m_fusedOnDevice = false;
    } else {
      id = 0;
      bus = "";
      m_sensorPerOutput = 1.0;
      m_magnetOffsetRot = 0.0;
      m_fusedOnDevice = false;
    }
    m_name = (owner == null || owner.isBlank() ? "CANcoder" : owner) + "/CANcoder " + id;
    m_cancoder = new CANcoder(id, PhoenixUtil.bus(bus));

    m_config.MagnetSensor.MagnetOffset = m_magnetOffsetRot;
    m_config.MagnetSensor.SensorDirection = SensorDirectionValue.CounterClockwise_Positive;
    // A CANcoder on a mechanism with finite travel wants a full-turn range so that a position just
    // below zero does not wrap to just below one, which is how an arm decides to take the long way
    // round through its hard stop.
    m_config.MagnetSensor.AbsoluteSensorDiscontinuityPoint = 1.0;
    PhoenixUtil.applyVerified(m_cancoder, m_config, m_name);

    m_absolutePosition = m_cancoder.getAbsolutePosition();
    m_velocity = m_cancoder.getVelocity();
    m_magnetHealth = m_cancoder.getMagnetHealth();
    m_batch = new BaseStatusSignal[] {m_absolutePosition, m_velocity, m_magnetHealth};

    m_absolutePosition.setUpdateFrequency(kPositionRateHz);
    m_velocity.setUpdateFrequency(kVelocityRateHz);
    m_magnetHealth.setUpdateFrequency(kHealthRateHz);
    // Last, after the frequencies: optimising first would zero the signals just requested.
    m_cancoder.optimizeBusUtilization();

    m_cancoder.hasResetOccurred();
  }

  /**
   * Wraps a CANcoder you already own.
   *
   * @param owner the mechanism name, for alerts
   * @param cancoder the device
   * @param sensorPerOutput gearing between the CANcoder and the output shaft; 1.0 when it is on the
   *     joint
   * @param magnetOffsetRot the magnet offset already written to the device, in sensor rotations
   * @param fusedOnDevice whether a TalonFX is fusing this sensor, which decides whether the
   *     mechanism layer re-seeds from it
   */
  public CancoderIO(
      String owner,
      CANcoder cancoder,
      double sensorPerOutput,
      double magnetOffsetRot,
      boolean fusedOnDevice) {
    m_cancoder = Objects.requireNonNull(cancoder, "CancoderIO: device must not be null");
    m_name = (owner == null || owner.isBlank() ? "CANcoder" : owner) + "/CANcoder "
        + cancoder.getDeviceID();
    m_sensorPerOutput = sensorPerOutput;
    m_magnetOffsetRot = magnetOffsetRot;
    m_fusedOnDevice = fusedOnDevice;
    m_absolutePosition = m_cancoder.getAbsolutePosition();
    m_velocity = m_cancoder.getVelocity();
    m_magnetHealth = m_cancoder.getMagnetHealth();
    m_batch = new BaseStatusSignal[] {m_absolutePosition, m_velocity, m_magnetHealth};
    m_cancoder.hasResetOccurred();
  }

  @Override
  public void updateInputs(AbsoluteEncoderInputs inputs) {
    if (inputs == null) {
      return;
    }
    boolean ok = BaseStatusSignal.refreshAll(m_batch).isOK();
    inputs.connected = ok && m_cancoder.isConnected();

    double perOutput = m_sensorPerOutput == 0.0 || !Double.isFinite(m_sensorPerOutput)
        ? 1.0
        : m_sensorPerOutput;
    double sensorRot = m_absolutePosition.getValueAsDouble();

    // Sensor rotations to OUTPUT rotations. Every other number crossing this seam is in output
    // rotations, and a CANcoder behind a reduction is exactly where that stops being a no-op.
    inputs.absolutePositionRot = sensorRot / perOutput;
    inputs.velocityRps = m_velocity.getValueAsDouble() / perOutput;
    // The device already added the magnet offset, so removing it again is what a student holding the
    // mechanism against its hard stop needs to read off: this is the number they are about to record
    // as the new offset.
    inputs.rawPositionRot = (sensorRot - m_magnetOffsetRot) / perOutput;

    if (ok && m_magnetHealth.getValue() != MagnetHealthValue.Magnet_Green) {
      magnetAlert().set(true);
    }
  }

  /**
   * The live CANcoder, for the one-in-twenty thing PumpkinLib does not model.
   *
   * @return the device
   */
  public CANcoder cancoder() {
    return m_cancoder;
  }

  /**
   * Whether a TalonFX is fusing this sensor on-device.
   *
   * @return true for {@link FeedbackSpec.FusedCancoder}
   */
  public boolean isFusedOnDevice() {
    return m_fusedOnDevice;
  }

  /**
   * The encoder as the boot dump prints it, including why the re-seed path differs.
   *
   * @return a multi-line block, no trailing newline
   */
  public String describe() {
    return String.format(
            Locale.ROOT,
            "%s%n  device            %s%n  magnet offset     %.6f sensor rot%n"
                + "  sensor -> output  /%.5f  (absolutePositionRot is in OUTPUT rotations)%n"
                + "  re-seed           %s%n  signals           ABSOLUTE_POSITION@%.0fHz"
                + " VELOCITY@%.0fHz MAGNET_HEALTH@%.0fHz",
            m_name,
            PhoenixUtil.describeDevice(m_cancoder),
            m_magnetOffsetRot,
            m_sensorPerOutput,
            m_fusedOnDevice
                ? "NONE -- the TalonFX fuses this sensor on-device, so a roboRIO-side re-seed would"
                    + " fight it"
                : "GUARDED -- the mechanism re-seeds the motor's internal sensor from this one at"
                    + " boot and immediately BEFORE a move, never during one",
            kPositionRateHz,
            kVelocityRateHz,
            kHealthRateHz);
  }

  private PumpkinAlert magnetAlert() {
    if (m_magnetAlert == null) {
      m_magnetAlert =
          Alerts.warning(
              m_name,
              m_name
                  + ": magnet health is not green ("
                  + PhoenixUtil.describeDevice(m_cancoder)
                  + "). The absolute position still reads a number, and that number is not"
                  + " trustworthy. Fix: check the magnet-to-sensor air gap and that the magnet is"
                  + " the diametrically-magnetised one, then re-capture the offset with"
                  + " `pumpkin zero`.",
              MatchImpact.PIT_ONLY);
    }
    return m_magnetAlert;
  }
}

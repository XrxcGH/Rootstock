package org.pumpkinlib.hardware.phoenix;

import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Seconds;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CANrangeConfiguration;
import com.ctre.phoenix6.hardware.CANrange;
import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.units.measure.Distance;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.config.SensorSpec;
import org.pumpkinlib.hardware.DigitalSensorIO;

/**
 * A CTRE CANrange time-of-flight sensor, answering the same logical question a limit switch and a
 * beam break answer — <b>is the thing there?</b> — plus the honest extra channel a switch cannot
 * fill: <b>how far away is it?</b>
 *
 * <h2>Detection happens on the device, not in a Java comparison</h2>
 *
 * <p>The declared threshold is written into {@code ProximityParams.ProximityThreshold} and the
 * device's own {@code getIsDetected()} is what {@link DigitalSensorInputs#detected} reports. That
 * matters for two reasons: the device applies proximity hysteresis, so an object sitting exactly at
 * the boundary does not flicker; and a comparison done in Java would be evaluated at the loop rate
 * against a distance sampled at the signal rate, which silently degrades the sensor to whichever is
 * slower.
 *
 * <h2>The library debounces the level, and it is a level</h2>
 *
 * <p>{@code detected} is a debounced <b>level</b>, never an edge. A surveyed team needed a five-line
 * workaround because an auto-stop fired only on the rising edge and therefore missed a game piece
 * that was already present when the command started. Building the level in here means no subsystem
 * ever has to notice.
 */
public final class CanRangeIO implements DigitalSensorIO {

  /** How often distance and detection are polled, in hertz. */
  public static final double kDistanceRateHz = 50.0;

  private final CANrange m_canRange;
  private final CANrangeConfiguration m_config = new CANrangeConfiguration();
  private final String m_name;
  private final double m_thresholdMeters;
  private final double m_debounceSeconds;
  private final Debouncer m_debouncer;

  private final StatusSignal<Distance> m_distance;
  private final StatusSignal<Boolean> m_detected;
  private final BaseStatusSignal[] m_batch;

  /**
   * Opens the CANrange a {@link SensorSpec} declares and writes its proximity threshold.
   *
   * @param spec the declared sensor
   */
  public CanRangeIO(SensorSpec.CanRange spec) {
    Objects.requireNonNull(spec, "CanRangeIO: spec must not be null");
    m_canRange = new CANrange(spec.deviceId(), PhoenixUtil.bus(spec.canBus()));
    m_name = "CANrange " + spec.deviceId();
    m_thresholdMeters = spec.threshold().in(Meters);
    m_debounceSeconds = spec.debounce() == null ? 0.0 : Math.max(0.0, spec.debounce().in(Seconds));
    m_debouncer = new Debouncer(m_debounceSeconds, Debouncer.DebounceType.kBoth);

    m_config.ProximityParams.ProximityThreshold = m_thresholdMeters;
    PhoenixUtil.applyVerified(m_canRange, m_config, m_name);

    m_distance = m_canRange.getDistance();
    m_detected = m_canRange.getIsDetected();
    m_batch = new BaseStatusSignal[] {m_distance, m_detected};
    BaseStatusSignal.setUpdateFrequencyForAll(kDistanceRateHz, m_batch);
    // Last, after the frequencies.
    m_canRange.optimizeBusUtilization();
    m_canRange.hasResetOccurred();
  }

  /**
   * Wraps a CANrange you already own.
   *
   * @param canRange the device
   * @param thresholdMeters the proximity threshold already written to the device
   * @param debounceSeconds how long the level must hold before it is believed; zero for none
   */
  public CanRangeIO(CANrange canRange, double thresholdMeters, double debounceSeconds) {
    m_canRange = Objects.requireNonNull(canRange, "CanRangeIO: device must not be null");
    m_name = "CANrange " + canRange.getDeviceID();
    m_thresholdMeters = thresholdMeters;
    m_debounceSeconds = Double.isFinite(debounceSeconds) ? Math.max(0.0, debounceSeconds) : 0.0;
    m_debouncer = new Debouncer(m_debounceSeconds, Debouncer.DebounceType.kBoth);
    m_distance = m_canRange.getDistance();
    m_detected = m_canRange.getIsDetected();
    m_batch = new BaseStatusSignal[] {m_distance, m_detected};
    m_canRange.hasResetOccurred();
  }

  @Override
  public void updateInputs(DigitalSensorInputs inputs) {
    if (inputs == null) {
      return;
    }
    boolean ok = BaseStatusSignal.refreshAll(m_batch).isOK();
    inputs.connected = ok && m_canRange.isConnected();
    // The device's own detection, with its own hysteresis, then the library's debounce on top. A
    // level, never an edge.
    inputs.detected = m_debouncer.calculate(Boolean.TRUE.equals(m_detected.getValue()));
    inputs.distanceMeters = m_distance.getValue().in(Meters);
  }

  /**
   * The live CANrange, for anything PumpkinLib does not model — field of view, signal strength,
   * measurement health.
   *
   * @return the device
   */
  public CANrange canRange() {
    return m_canRange;
  }

  /**
   * The sensor as the boot dump prints it.
   *
   * @return a multi-line block, no trailing newline
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s%n  device            %s%n  threshold         %.4f m, written to"
            + " ProximityParams.ProximityThreshold so DETECTION HAPPENS ON THE DEVICE%n"
            + "  debounce          %.3f s on the level (never an edge)%n"
            + "  signals           DISTANCE@%.0fHz IS_DETECTED@%.0fHz",
        m_name,
        PhoenixUtil.describeDevice(m_canRange),
        m_thresholdMeters,
        m_debounceSeconds,
        kDistanceRateHz,
        kDistanceRateHz);
  }
}

package org.rootstock.hardware.generic;

import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.wpilibj.DigitalInput;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.config.SensorSpec;
import org.rootstock.hardware.DigitalSensorIO;

/**
 * A limit switch or beam break wired to a roboRIO DIO channel.
 *
 * <p>DIO survives the 2027 API line, which is why this is the generic backend rather than an
 * also-ran.
 *
 * <h2>The two things this class owns so a subsystem never has to</h2>
 *
 * <p><b>Inversion.</b> A normally-closed switch and a beam break read opposite ways round, and a
 * team gets it wrong once per season. It is a declared field on the spec, applied here, and printed
 * in the boot dump — not a {@code !} somewhere in a command.
 *
 * <p><b>Debounce.</b> The library debounces the level, always. A raw DIO read chatters at the
 * boundary, and a chattering "piece detected" is a piece that gets ejected. {@link
 * DigitalSensorInputs#detected} is a debounced <i>level</i>, never an edge, because an auto-stop
 * built on a rising edge misses a game piece that was already present when the command started.
 *
 * <h2>What a DIO limit switch cannot do</h2>
 *
 * <p>It cannot stop the motor in firmware. A limit switch wired <i>into</i> a motor controller can,
 * and is always preferred; the mechanism layer zeroes the output in the same loop for this one and
 * warns at config time about the latency difference, because from the wiring the two look identical
 * and they are not.
 */
public final class DioSensorIO implements DigitalSensorIO {

  private final DigitalInput m_input;
  private final boolean m_inverted;
  private final Debouncer m_debouncer;
  private final double m_debounceSeconds;
  private final int m_channel;

  /**
   * Opens the DIO channel named by the spec.
   *
   * @param spec the declared DIO sensor
   */
  public DioSensorIO(SensorSpec.Dio spec) {
    Objects.requireNonNull(spec, "DioSensorIO: spec must not be null");
    m_channel = spec.channel();
    m_inverted = spec.inverted();
    m_debounceSeconds = spec.debounce() == null ? 0.0 : spec.debounce().baseUnitMagnitude();
    m_input = new DigitalInput(m_channel);
    m_debouncer = new Debouncer(Math.max(0.0, m_debounceSeconds), Debouncer.DebounceType.kBoth);
  }

  /**
   * Wraps a {@link DigitalInput} you already own — for a channel this library did not open, or for a
   * test.
   *
   * @param input the digital input to read
   * @param inverted true when a closed switch should read as "not detected"
   * @param debounceSeconds how long the level must hold before it is believed; zero for none
   */
  public DioSensorIO(DigitalInput input, boolean inverted, double debounceSeconds) {
    m_input = Objects.requireNonNull(input, "DioSensorIO: input must not be null");
    m_channel = input.getChannel();
    m_inverted = inverted;
    m_debounceSeconds = Double.isFinite(debounceSeconds) ? Math.max(0.0, debounceSeconds) : 0.0;
    m_debouncer = new Debouncer(m_debounceSeconds, Debouncer.DebounceType.kBoth);
  }

  @Override
  public void updateInputs(DigitalSensorInputs inputs) {
    if (inputs == null) {
      return;
    }
    // A DIO channel is a voltage on a pin; there is no device to be disconnected from, so this is
    // measured-true rather than assumed-true.
    inputs.connected = true;
    boolean raw = m_input.get() ^ m_inverted;
    inputs.detected = m_debouncer.calculate(raw);
    // A switch cannot measure distance. NaN, not zero: zero metres means "touching".
    inputs.distanceMeters = Double.NaN;
  }

  /**
   * The raw digital input, for anything this seam does not model.
   *
   * @return the live {@link DigitalInput}
   */
  public DigitalInput input() {
    return m_input;
  }

  /**
   * How this sensor reads, for the boot dump.
   *
   * @return a human-readable description
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "DIO %d, %s, debounced %.3f s (level, not edge)",
        m_channel,
        m_inverted ? "inverted (closed switch reads NOT detected)" : "not inverted",
        m_debounceSeconds);
  }
}

package org.rootstock.hardware;

import java.util.Optional;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * One interface for three physically different sensors that answer one logical question: <i>is the
 * game piece there?</i>
 *
 * <p>A limit switch on a DIO channel, a beam break, a limit switch wired into a motor controller and
 * a CANrange time-of-flight sensor all answer it. They differ in one honest way — a CANrange also
 * knows <i>how far</i> — so the interface carries that extra channel explicitly rather than
 * pretending the sensors are identical or splitting them into four interfaces that all get used the
 * same way.
 *
 * <h2>{@code detected} is a LEVEL, and it is debounced by the library</h2>
 *
 * <p>Never an edge. A surveyed robot needed a five-line workaround because an auto-stop fired only
 * on the rising edge, so a game piece already present when the command started was never seen. Level
 * plus library-owned debounce makes that class of bug unwritable.
 *
 * <h2>Hard limits versus soft limits</h2>
 *
 * <p>A hard limit wired <i>into the motor controller</i> stops the motor in firmware and is always
 * preferred; it is read from {@link MotorInputs#forwardLimitTripped} at zero extra bus cost. A hard
 * limit on a roboRIO DIO channel cannot stop the motor in firmware — the library zeroes the output
 * in the same loop and warns at config time about the latency difference, because the two are not
 * interchangeable and a student cannot see that from the wiring.
 */
public interface DigitalSensorIO {

  /**
   * Refresh the sensor and fill {@code inputs}.
   *
   * @param inputs the inputs object to fill; the same instance every loop
   */
  void updateInputs(DigitalSensorInputs inputs);

  /**
   * The typed escape hatch — reach the real {@code DigitalInput}, {@code CANrange} or {@code CANdi}.
   *
   * @param <T> the concrete IO type being asked for
   * @param type the backend class
   * @return this IO as that type, or empty if it is a different backend
   */
  default <T extends DigitalSensorIO> Optional<T> as(Class<T> type) {
    return type != null && type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
  }

  /** Everything read from a digital or proximity sensor. */
  class DigitalSensorInputs implements LoggableInputs {

    /** Whether the sensor is answering. A DIO channel is always considered connected. */
    public boolean connected = false;

    /**
     * THE level signal, already debounced by the library. Never an edge.
     *
     * <p>Read this every loop and act on it every loop; a command that starts with the piece already
     * present must see {@code true} on its first iteration.
     */
    public boolean detected = false;

    /**
     * Distance in metres, for a sensor that measures one.
     *
     * <p>{@link Double#NaN} when the sensor cannot measure distance — a switch or a beam break. NaN
     * rather than zero, because zero metres is a legal reading that means "touching".
     */
    public double distanceMeters = Double.NaN;

    /**
     * Writes every field under its frozen schema name.
     *
     * @param t the table for this sensor's inputs subtable
     */
    @Override
    public void toLog(LogTable t) {
      t.put("Connected", connected);
      t.put("Detected", detected);
      t.put("DistanceMeters", distanceMeters);
    }

    /**
     * Reads every field back during replay.
     *
     * @param t the table for this sensor's inputs subtable
     */
    @Override
    public void fromLog(LogTable t) {
      connected = t.get("Connected", connected);
      detected = t.get("Detected", detected);
      distanceMeters = t.get("DistanceMeters", distanceMeters);
    }
  }
}

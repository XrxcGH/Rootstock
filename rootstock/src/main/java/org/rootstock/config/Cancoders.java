package org.rootstock.config;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Rotations;

import edu.wpi.first.units.measure.Angle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.core.config.CanIdRegistry;

/**
 * Shared, package-private helpers for the two CANcoder {@link FeedbackSpec} variants.
 *
 * <p>Their id checks, their ratio checks and their {@code describe()} lines are identical; only the
 * sentence about on-device fusion differs. Writing the shared half once is what keeps the two
 * messages from drifting apart — and these are messages a student reads while the mechanism is doing
 * something inexplicable.
 */
final class Cancoders {

  /** The message for a missing encoder offset, which is a measurement, not a formality. */
  static final String kNullOffset =
      "FeedbackSpec: the encoder offset is required. Pass Rotations.of(0) if you have not measured "
          + "it yet — but measure it: an unmeasured offset means the mechanism believes it is "
          + "somewhere it is not, at boot, before anyone presses a button.";

  private Cancoders() {}

  /** The id and ratio checks both CANcoder variants make about themselves. */
  static List<String> problems(
      FeedbackSpec spec, int id, String bus, double rotorPerSensor, double sensorPerOutput) {
    List<String> out = new ArrayList<>();
    if (id < CanIdRegistry.kMinDeviceId || id > CanIdRegistry.kMaxDeviceId) {
      out.add(
          spec.sensorDescription()
              + ": CAN device id "
              + id
              + " is outside the addressable range "
              + CanIdRegistry.kMinDeviceId
              + ".."
              + CanIdRegistry.kMaxDeviceId
              + " (bus \""
              + bus
              + "\"). Fix: use the id shown for this CANcoder in Phoenix Tuner.");
    }
    checkRatio(out, spec, "rotorPerSensor", rotorPerSensor);
    checkRatio(out, spec, "sensorPerOutput", sensorPerOutput);
    return List.copyOf(out);
  }

  private static void checkRatio(List<String> out, FeedbackSpec spec, String field, double value) {
    if (!Double.isFinite(value) || value <= 0.0) {
      out.add(
          spec.sensorDescription()
              + ": "
              + field
              + " = "
              + value
              + ", which must be a finite number greater than zero. Gearing is always positive — "
              + "direction is set by the motor's inverted flag, never by a negative ratio. Fix: "
              + "count the teeth. If the sensor is on the joint, sensorPerOutput is 1.0 and "
              + "rotorPerSensor is the whole gearbox ratio.");
    }
  }

  /** The ratio-and-offset clause both CANcoder variants print. */
  static String describeRatios(double rotorPerSensor, double sensorPerOutput, Angle offset) {
    return String.format(
        Locale.ROOT,
        ", rotor:sensor %.4f, sensor:output %.4f, magnet offset %.4f rot (%.2f deg)",
        rotorPerSensor,
        sensorPerOutput,
        offset.in(Rotations),
        offset.in(Degrees));
  }
}

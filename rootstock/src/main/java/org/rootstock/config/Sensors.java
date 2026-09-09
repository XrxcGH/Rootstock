package org.rootstock.config;

import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Time;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.core.config.CanIdRegistry;

/**
 * Shared, package-private helpers for the {@link SensorSpec} variants.
 *
 * <p>Six records ask the same three questions about themselves. Answering them in one place is what
 * makes a bad debounce read the same whether it was typed on a beam break or on a CANrange.
 */
final class Sensors {

  private Sensors() {}

  /** Rejects a null debounce; every factory supplies one, so null is a direct-construction error. */
  static Time requireDebounce(Time debounce) {
    return Objects.requireNonNull(
        debounce,
        "SensorSpec: a debounce window is required. Pass SensorSpec.kDefaultDebounce if you have no "
            + "reason to choose. Every reading in this library is a debounced LEVEL, never an "
            + "edge, because an edge-triggered intake does nothing when the game piece was already "
            + "there.");
  }

  /** Collects a problem when a debounce is negative or long enough to be felt by a driver. */
  static void checkDebounce(List<String> out, String owner, Time debounce) {
    double seconds = debounce.in(Seconds);
    if (!Double.isFinite(seconds) || seconds < 0.0) {
      out.add(
          owner
              + ": debounce = "
              + String.format(Locale.ROOT, "%.3f s", seconds)
              + ", which must be a finite time of zero or more. Fix: use "
              + "SensorSpec.kDefaultDebounce (0.040 s) unless you have measured a reason not to.");
    } else if (seconds > 0.5) {
      out.add(
          owner
              + ": debounce = "
              + String.format(Locale.ROOT, "%.3f s", seconds)
              + " is longer than half a second, so the mechanism will keep running for that long "
              + "after the sensor is covered. Fix: 0.02..0.10 s is the useful range; anything "
              + "larger is a delay, not a debounce.");
    }
  }

  /** Collects a problem when a sensor's CAN id cannot be addressed at all. */
  static void checkCanId(List<String> out, String owner, int deviceId, String bus) {
    if (deviceId < CanIdRegistry.kMinDeviceId || deviceId > CanIdRegistry.kMaxDeviceId) {
      out.add(
          owner
              + ": CAN device id "
              + deviceId
              + " is outside the addressable range "
              + CanIdRegistry.kMinDeviceId
              + ".."
              + CanIdRegistry.kMaxDeviceId
              + " (bus \""
              + bus
              + "\"). Fix: use the id shown for this device in Phoenix Tuner.");
    }
  }

  /** The trailing debounce clause every variant's {@code describe()} ends with. */
  static String debounceClause(Time debounce) {
    return String.format(Locale.ROOT, ", debounced %.0f ms", debounce.in(Seconds) * 1000.0);
  }
}

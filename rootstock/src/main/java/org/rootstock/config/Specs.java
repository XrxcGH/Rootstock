package org.rootstock.config;

import java.util.List;
import java.util.Locale;
import org.rootstock.core.config.CanIdRegistry;

/**
 * Shared, package-private helpers for the {@link MotorSpec} variants.
 *
 * <p>Five records ask the same four questions about themselves. Written five times, three of them
 * end up subtly different — which is how a library ships an error message that names the wrong range
 * for one vendor. Written here, the message a student reads at 11pm is the same message whichever
 * motor they used.
 */
final class Specs {

  /** The message for a null output mode, which no factory can produce. */
  static final String kNullOutputMode =
      "MotorSpec: outputMode must not be null. Use OutputMode.VOLTAGE (the only supported mode).";

  /** The message for a null motor model, which no factory can produce. */
  static final String kNullModel =
      "MotorSpec: model must not be null. Name the motor that is actually bolted on: it is what "
          + "simulation, the free-speed check and the default current limits are derived from.";

  private Specs() {}

  /** Blank or missing bus names become the platform default rather than a validation error. */
  static String normaliseBus(String bus) {
    return bus == null || bus.isBlank() ? MotorSpec.kDefaultBus : bus.trim();
  }

  /** Collects a problem when the CAN id cannot be addressed at all. */
  static void checkCanId(List<String> out, MotorSpec spec) {
    if (spec.deviceId() < CanIdRegistry.kMinDeviceId
        || spec.deviceId() > CanIdRegistry.kMaxDeviceId) {
      out.add(
          spec.name()
              + ": CAN device id "
              + spec.deviceId()
              + " is outside the addressable range "
              + CanIdRegistry.kMinDeviceId
              + ".."
              + CanIdRegistry.kMaxDeviceId
              + ". Fix: use the id shown for this device in Phoenix Tuner or the REV Hardware "
              + "Client.");
    }
  }

  /** Collects a problem when an explicit signal rate is unusable. */
  static void checkSignalRate(List<String> out, MotorSpec spec) {
    double hz = spec.signalRateHz();
    if (Double.isNaN(hz)) {
      return;
    }
    if (hz <= 0.0 || hz > 250.0) {
      out.add(
          spec.name()
              + ": signalRateHz = "
              + String.format(Locale.ROOT, "%.1f", hz)
              + " is outside the usable range 1..250 Hz. Fix: omit it to take the library default "
              + "(100 Hz for a position mechanism, 50 Hz for a velocity mechanism), or name a rate "
              + "you have measured bus headroom for.");
    }
  }

  /** Collects the permanent v0.1 refusal of torque-current output. */
  static void checkOutputMode(List<String> out, MotorSpec spec) {
    if (!spec.outputMode().isSupported()) {
      out.add(
          spec.name()
              + ": outputMode = "
              + spec.outputMode()
              + ", expected VOLTAGE (the only supported output mode). Rootstock gains are "
              + "volts-per-SI; torque-current gains are amps-per-SI, which is a different number "
              + "for every one of kP kI kD kS kV kA kG, and the tuning wizard, the feedback "
              + "designer and the persisted gain file cannot convert between them. Fix: remove the "
              + ".outputMode(...) call. FOC is a separate switch and is unaffected: .foc(true) "
              + "stays correct with volt gains.");
    }
  }

  /** The name/model/inversion prefix every variant's {@code describe()} starts with. */
  static String describeCommon(MotorSpec spec) {
    StringBuilder sb = new StringBuilder(spec.name());
    sb.append(": ").append(spec.model().displayName());
    if (spec.inverted()) {
      sb.append(", inverted");
    }
    if (spec.hasSignalRateOverride()) {
      sb.append(String.format(Locale.ROOT, ", signals at %.1f Hz", spec.signalRateHz()));
    }
    return sb.toString();
  }
}

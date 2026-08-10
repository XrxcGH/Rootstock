package org.pumpkinlib.sim;

import java.util.List;
import java.util.Locale;
import org.pumpkinlib.core.spi.MechanismGeometry;

/**
 * What every registered mechanism would pull if they all stalled at once, and what the battery would
 * do about it.
 *
 * <p><b>Why this is printed at boot and not hidden behind a command.</b> A team that has specified a
 * four-NEO elevator has specified 724 A of stall current, and nothing on a robot tells them so: the
 * motors are in CAD, the current limits are in a config file, and the first evidence is a brownout in
 * a match. The arithmetic is a sum over {@code DCMotor.stallCurrentAmps} — it needs no sensors, no
 * hardware and no match — so it runs at boot in simulation and prints, which turns an event-day
 * discovery into a week-two one.
 *
 * <p><b>What the number is and is not.</b> It is the true worst case: every motor stalled, at once, at
 * full output, with no current limit applied. Real mechanisms are current-limited and do not all stall
 * simultaneously, so a robot whose peak here is 700 A is not necessarily broken — but a robot whose
 * peak here is 700 A <i>and</i> whose supply limits are 60 A per motor has a 60 A number that is doing
 * all the work, and that is worth knowing before the breaker finds out.
 *
 * @param mechanisms one entry per registered mechanism, in registration order
 * @param peakStallAmps the sum of every mechanism's stall current, in amps
 * @param predictedBusVolts what {@code BatterySim} says the bus would sag to under that load
 * @param predictsBrownout whether {@code predictedBusVolts} is below the roboRIO 2.0 stage-2 brownout
 *     threshold
 */
public record StressReport(
    List<Entry> mechanisms,
    double peakStallAmps,
    double predictedBusVolts,
    boolean predictsBrownout) {

  /** Copies the list so a report cannot be edited after it is handed out. */
  public StressReport {
    mechanisms = mechanisms == null ? List.of() : List.copyOf(mechanisms);
  }

  /**
   * One mechanism's contribution.
   *
   * @param name the mechanism's name
   * @param kind which plant it is
   * @param stallAmps what its gearbox pulls stalled, already multiplied by the motor count
   * @param stallTorqueNewtonMeters what its gearbox produces stalled, at the rotor
   * @param freeSpeedRadPerSec the gearbox's free speed, at the rotor
   */
  public record Entry(
      String name,
      MechanismGeometry.Kind kind,
      double stallAmps,
      double stallTorqueNewtonMeters,
      double freeSpeedRadPerSec) {}

  /**
   * The report as the boot dump prints it.
   *
   * @return a multi-line block, no trailing newline
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("PumpkinSim stress test — every mechanism stalled simultaneously\n");
    if (mechanisms.isEmpty()) {
      sb.append("  (no mechanisms registered — nothing declared a MechanismGeometry)");
      return sb.toString();
    }
    sb.append(String.format(Locale.ROOT, "  %-20s %-9s %10s %12s%n", "mechanism", "kind", "stall A",
        "stall N.m"));
    for (Entry e : mechanisms) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  %-20s %-9s %10.0f %12.2f%n",
              e.name(),
              e.kind(),
              e.stallAmps(),
              e.stallTorqueNewtonMeters()));
    }
    sb.append(
        String.format(
            Locale.ROOT,
            "  PEAK SIMULATED STALL CURRENT %.0f A -> bus sags to %.2f V%s",
            peakStallAmps,
            predictedBusVolts,
            predictsBrownout
                ? String.format(
                    Locale.ROOT,
                    " — BELOW the %.2f V brownout threshold. The roboRIO would reboot its outputs. "
                        + "Fix: lower the supply current limits, or use fewer/smaller motors. This is "
                        + "the worst case, not the expected case, but a design with no headroom here "
                        + "has none on the field either.",
                    PumpkinSim.kBrownoutVolts)
                : "."));
    return sb.toString();
  }
}

package org.rootstock.core.health.builtin;

import java.util.function.Supplier;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.HealthSource;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.util.EdgeDetector;

/**
 * CAN bus utilisation plus error-counter <strong>deltas since enable</strong>.
 *
 * <p>Deltas, because absolute counters are meaningless: a receive-error count of 4,812 tells you
 * nothing without knowing it was 4,810 when you rolled onto the field. What matters is whether the
 * bus is losing frames <em>during this match</em>, and a bus-off event during a match is the classic
 * "the robot stopped responding" — every device on that bus goes silent at once.
 *
 * <p>Multi-bus aware from day one, because SystemCore has several. {@link #rio()} reads the roboRIO
 * bus through {@code Platform.canStatus()}; {@link #named(String, Supplier)} takes a sampler so a
 * vendor adapter can feed a CANivore or SystemCore bus without core ever naming a vendor type.
 *
 * <p>Two of the eleven built-in conditions:
 *
 * <table border="1">
 *   <caption>CAN conditions</caption>
 *   <tr><th>Condition</th><th>Severity</th><th>Impact</th></tr>
 *   <tr><td>utilisation &gt; 90 %, or bus-off delta &gt; 0</td><td>ERROR</td>
 *       <td><strong>BLOCKS_MATCH</strong></td></tr>
 *   <tr><td>utilisation &gt; 70 %</td><td>WARNING</td><td>PIT_ONLY</td></tr>
 * </table>
 */
public final class CanBusMonitor implements HealthSource {

  /** Default utilisation fraction above which the bus is a pit-only warning. */
  public static final double kDefaultWarnFraction = 0.70;

  /** Default utilisation fraction above which the bus blocks the match. */
  public static final double kDefaultErrorFraction = 0.90;

  /** Health-name prefix, so every bus lands under {@code /Rootstock/Health/CAN/...}. */
  public static final String kNamePrefix = "CAN/";

  private final String m_bus;
  private final Supplier<CanSample> m_sampler;
  private final EdgeDetector m_enabled = new EdgeDetector(false);

  private double m_warnAbove = kDefaultWarnFraction;
  private double m_errorAbove = kDefaultErrorFraction;
  private boolean m_watchErrorDeltas = true;

  private CanSample m_baseline;
  private double m_peakUtilization;

  private RootstockAlert m_blocking;
  private RootstockAlert m_warning;

  private CanBusMonitor(String bus, Supplier<CanSample> sampler) {
    m_bus = bus;
    m_sampler = sampler;
  }

  /**
   * One sample of a CAN bus.
   *
   * <p>Field-for-field the shape both {@code RobotController.getCANStatus()} and Phoenix 6's {@code
   * CANBus.getStatus()} report, so an adapter is a one-line lambda.
   *
   * @param utilization fraction of bus bandwidth in use, 0..1
   * @param busOff cumulative bus-off event count
   * @param txFull cumulative transmit-buffer-full count
   * @param rec receive error counter
   * @param tec transmit error counter
   */
  public record CanSample(double utilization, int busOff, int txFull, int rec, int tec) {}

  /**
   * The roboRIO bus, read through {@code Platform.canStatus()} (which wraps {@code
   * RobotController.getCANStatus()}).
   *
   * @return a monitor named {@code "CAN/rio"}
   */
  public static CanBusMonitor rio() {
    return new CanBusMonitor(
        "rio",
        () -> {
          Platform.CanStatus s = Platform.canStatus();
          return new CanSample(s.utilization(), s.busOff(), s.txFull(), s.rec(), s.tec());
        });
  }

  /**
   * Any bus, via a supplier — the seam a vendor adapter uses so that core never imports a vendor
   * type (DESIGN.md §8 rule 1).
   *
   * @param busName the bus name as the electrical team says it, e.g. {@code "canivore"}
   * @param sampler reads one sample; called at most once per sweep
   * @return a monitor named {@code "CAN/" + busName}
   * @throws IllegalArgumentException if {@code busName} is blank or {@code sampler} is null
   */
  public static CanBusMonitor named(String busName, Supplier<CanSample> sampler) {
    if (busName == null || busName.isBlank()) {
      throw new IllegalArgumentException("CanBusMonitor.named: busName is blank.");
    }
    if (sampler == null) {
      throw new IllegalArgumentException(
          "CanBusMonitor.named(\"" + busName + "\"): sampler is null.");
    }
    return new CanBusMonitor(busName, sampler);
  }

  /**
   * Utilisation fraction above which this raises a pit-only warning.
   *
   * @param utilizationFraction 0..1; default {@value #kDefaultWarnFraction}
   * @return this, for chaining
   * @throws IllegalArgumentException if outside 0..1
   */
  public CanBusMonitor warnAbove(double utilizationFraction) {
    m_warnAbove = requireFraction(utilizationFraction, "warnAbove");
    return this;
  }

  /**
   * Utilisation fraction above which this blocks the match.
   *
   * @param utilizationFraction 0..1; default {@value #kDefaultErrorFraction}
   * @return this, for chaining
   * @throws IllegalArgumentException if outside 0..1
   */
  public CanBusMonitor errorAbove(double utilizationFraction) {
    m_errorAbove = requireFraction(utilizationFraction, "errorAbove");
    return this;
  }

  /**
   * Whether to report rising receive/transmit error counters and transmit-buffer-full events.
   *
   * @param b default true
   * @return this, for chaining
   */
  public CanBusMonitor watchErrorDeltas(boolean b) {
    m_watchErrorDeltas = b;
    return this;
  }

  /**
   * Register as one slice of the shared round-robin and create this monitor's two alerts.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_blocking =
        Alerts.error(
            healthName(),
            "CAN bus " + m_bus + " saturated or dropped off",
            MatchImpact.BLOCKS_MATCH);
    m_warning =
        Alerts.warning(healthName(), "CAN bus " + m_bus + " busy", MatchImpact.PIT_ONLY);
    return HealthMonitor.watch(this).ownAlerts();
  }

  /**
   * The highest utilisation seen since the robot was last enabled.
   *
   * @return a fraction, 0..1
   */
  public double peakUtilizationSinceEnable() {
    return m_peakUtilization;
  }

  @Override
  public String healthName() {
    return kNamePrefix + m_bus;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    CanSample s = m_sampler.get();

    m_enabled.update(MatchContext.isEnabled());
    if (m_enabled.rising() || m_baseline == null) {
      m_baseline = s;
      m_peakUtilization = 0.0;
    }
    m_peakUtilization = Math.max(m_peakUtilization, s.utilization());

    int busOffDelta = s.busOff() - m_baseline.busOff();
    int txFullDelta = s.txFull() - m_baseline.txFull();
    int recDelta = s.rec() - m_baseline.rec();
    int tecDelta = s.tec() - m_baseline.tec();

    String device = healthName();
    boolean blocking = false;
    boolean warning = false;
    String blockingText = "";

    if (s.utilization() > m_errorAbove) {
      blocking = true;
      blockingText =
          String.format(
              "bus %s at %.0f%% utilisation (limit %.0f%%). Reduce status-signal rates or move "
                  + "devices to a second bus - above 90%% frames start dropping.",
              m_bus, s.utilization() * 100.0, m_errorAbove * 100.0);
      out.error(device, blockingText);
    } else if (s.utilization() > m_warnAbove) {
      warning = true;
      out.warn(
          device,
          String.format(
              "bus %s at %.0f%% utilisation (warn above %.0f%%, error above %.0f%%). "
                  + "Lower the status-frame rates on devices you do not read.",
              m_bus, s.utilization() * 100.0, m_warnAbove * 100.0, m_errorAbove * 100.0));
    }

    if (busOffDelta > 0) {
      blocking = true;
      blockingText =
          String.format(
              "bus %s went bus-off %d time(s) since enable (expected 0). Every device on this bus "
                  + "went silent. Check terminating resistors and for a shorted or chafed CAN pair.",
              m_bus, busOffDelta);
      out.error(device, blockingText);
    }

    if (m_watchErrorDeltas) {
      if (txFullDelta > 0) {
        out.warn(
            device,
            String.format(
                "bus %s transmit buffer filled %d time(s) since enable (expected 0). "
                    + "Something is writing faster than the bus can carry.",
                m_bus, txFullDelta));
      }
      if (recDelta > 0 || tecDelta > 0) {
        out.warn(
            device,
            String.format(
                "bus %s error counters rose since enable (RX +%d, TX +%d; expected 0). "
                    + "Check connectors and terminators before this becomes a bus-off.",
                m_bus, recDelta, tecDelta));
      }
    }

    if (m_blocking != null) {
      if (blocking) {
        m_blocking.text(blockingText);
      }
      m_blocking.set(blocking);
    }
    if (m_warning != null) {
      m_warning.set(warning);
    }
  }

  private static double requireFraction(double value, String name) {
    if (!(value >= 0.0) || !(value <= 1.0)) {
      throw new IllegalArgumentException(
          "CanBusMonitor."
              + name
              + "("
              + value
              + "): must be a fraction between 0.0 and 1.0. 0.90 means 90% utilisation.");
    }
    return value;
  }
}

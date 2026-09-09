package org.rootstock.core.health.builtin;

import java.util.function.IntSupplier;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.alert.Severity;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.HealthSource;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.util.EdgeDetector;

/**
 * roboRIO rail fault counters, watched for <em>rises</em> since enable.
 *
 * <p>{@code Platform.faultCount5V() != 0} means the 5 V user rail browned out. When that happens
 * every sensor on it — every absolute encoder, every beam break, every limit switch — momentarily
 * returned garbage, and every closed loop on the robot acted on that garbage. The robot did not
 * "randomly jerk"; it was told to. Almost nobody checks this counter, and it is the reason for the
 * single bluntest error message in the library.
 *
 * <p><strong>This is the one type that registers three slices</strong> — 5 V, 3.3 V and 6 V — which
 * is the whole of the "seven types, eight slices" arithmetic.
 *
 * <p>Two of the eleven built-in conditions:
 *
 * <table border="1">
 *   <caption>Rail conditions</caption>
 *   <tr><th>Condition</th><th>Severity</th><th>Impact</th></tr>
 *   <tr><td>5 V fault count rising</td><td>ERROR</td><td><strong>BLOCKS_MATCH</strong></td></tr>
 *   <tr><td>3.3 V / 6 V fault count rising</td><td>WARNING</td><td>PIT_ONLY</td></tr>
 * </table>
 */
public final class RailMonitor implements HealthSource {

  /** Health-name prefix, so the three rails share a subtable. */
  public static final String kNamePrefix = "Rail/";

  private final String m_rail;
  private final IntSupplier m_faultCount;
  private final Severity m_severity;
  private final MatchImpact m_impact;
  private final String m_consequence;
  private final EdgeDetector m_enabled = new EdgeDetector(false);

  private int m_baseline = -1;
  private int m_peakDelta;
  private RootstockAlert m_alert;

  private RailMonitor(
      String rail,
      IntSupplier faultCount,
      Severity severity,
      MatchImpact impact,
      String consequence) {
    m_rail = rail;
    m_faultCount = faultCount;
    m_severity = severity;
    m_impact = impact;
    m_consequence = consequence;
  }

  /**
   * The 5 V user rail — the one every sensor hangs off.
   *
   * @return a monitor named {@code "Rail/5V"}, ERROR and BLOCKS_MATCH
   */
  public static RailMonitor watch5V() {
    return new RailMonitor(
        "5V",
        Platform::faultCount5V,
        Severity.ERROR,
        MatchImpact.BLOCKS_MATCH,
        "your encoders are lying right now. Find what is overloading the 5 V rail - usually a "
            + "shorted sensor cable or too many devices on one port - before you play");
  }

  /**
   * The 3.3 V rail.
   *
   * @return a monitor named {@code "Rail/3V3"}, WARNING and PIT_ONLY
   */
  public static RailMonitor watch3V3() {
    return new RailMonitor(
        "3V3",
        Platform::faultCount3V3,
        Severity.WARNING,
        MatchImpact.PIT_ONLY,
        "something on the 3.3 V rail is drawing too much. Check MXP-connected devices");
  }

  /**
   * The 6 V rail.
   *
   * @return a monitor named {@code "Rail/6V"}, WARNING and PIT_ONLY
   */
  public static RailMonitor watch6V() {
    return new RailMonitor(
        "6V",
        Platform::faultCount6V,
        Severity.WARNING,
        MatchImpact.PIT_ONLY,
        "something on the 6 V rail is drawing too much. Check servo-style loads");
  }

  /**
   * Register as one slice of the shared round-robin and create this rail's alert.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_alert =
        Alerts.of(
            healthName(),
            m_rail + " rail fault counter is rising",
            m_severity,
            m_impact);
    return HealthMonitor.watch(this).ownAlerts();
  }

  /**
   * How many rail faults have been counted since the robot was last enabled.
   *
   * @return the delta, 0 if none
   */
  public int faultsSinceEnable() {
    return m_peakDelta;
  }

  @Override
  public String healthName() {
    return kNamePrefix + m_rail;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    int count = m_faultCount.getAsInt();

    m_enabled.update(MatchContext.isEnabled());
    if (m_enabled.rising() || m_baseline < 0) {
      m_baseline = count;
      m_peakDelta = 0;
    }
    int delta = Math.max(0, count - m_baseline);
    m_peakDelta = Math.max(m_peakDelta, delta);

    boolean bad = m_peakDelta > 0;
    if (bad) {
      String text =
          String.format(
              "%s rail faulted %d time(s) since enable (expected 0) - %s.",
              m_rail, m_peakDelta, m_consequence);
      out.add(new org.rootstock.core.health.Fault(healthName(), text, m_severity, false));
      if (m_alert != null) {
        m_alert.text(text);
      }
    }
    if (m_alert != null) {
      m_alert.set(bad);
    }
  }
}

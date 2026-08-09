package org.pumpkinlib.core.health.builtin;

import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.health.FaultCollector;
import org.pumpkinlib.core.health.HealthMonitor;
import org.pumpkinlib.core.health.HealthSource;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.core.util.EdgeDetector;

/**
 * Latched brownout detection.
 *
 * <p>{@code Platform.isBrownedOut()} is true for as little as one 20 ms window, and the round-robin
 * only reaches this source once per sweep — so a check that merely reports the instantaneous value
 * would miss most brownouts entirely. This one <strong>latches</strong>, with a {@code
 * Clock.seconds()} timestamp, so a 40 ms brownout during autonomous is still on the pit screen when
 * the match ends. That is the whole point of the class.
 *
 * <p><strong>Every-loop sampling, slice-rate reporting.</strong> The latch itself is sampled every
 * loop through {@code SliceScheduler.registerEveryLoop} — about 200 ns of a boolean read — because a
 * latch you only look at every 220 ms is not a latch. The <em>reporting</em> stays on the rotation.
 *
 * <p>One of the eleven built-in conditions: latched brownout since enable, {@code ERROR}, and
 * <strong>{@code PIT_ONLY}</strong> — it already happened, and there is nothing the driver can do
 * about it in the next ten seconds. Waving a red banner at them mid-match for a past event is
 * exactly how a driver panel becomes wallpaper.
 */
public final class BrownoutMonitor implements HealthSource {

  /** The health name, and therefore the alert group and NT subtable. */
  public static final String kName = "Brownout";

  /** Every-loop slice name for the latch sampler. */
  public static final String kLatchSliceName = "Health/BrownoutLatch";

  private final EdgeDetector m_enabled = new EdgeDetector(false);

  private boolean m_latched;
  private boolean m_wasBrownedOut;
  private int m_count;
  private double m_firstSeconds = Double.NaN;
  private double m_lastSeconds = Double.NaN;
  private PumpkinAlert m_alert;

  private BrownoutMonitor() {}

  /**
   * Create the latching brownout monitor.
   *
   * @return a new monitor; call {@link #register()} to install it
   */
  public static BrownoutMonitor latching() {
    return new BrownoutMonitor();
  }

  /**
   * Register the every-loop latch sampler, the round-robin reporting slice, and the alert.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_alert =
        Alerts.error(kName, "the robot browned out since enable", MatchImpact.PIT_ONLY).latching();
    org.pumpkinlib.core.health.SliceScheduler.registerEveryLoop(kLatchSliceName, this::sample);
    return HealthMonitor.watch(this).ownAlerts();
  }

  /**
   * Whether a brownout has been latched since the robot was last enabled.
   *
   * @return true once one has been seen
   */
  public boolean isLatched() {
    return m_latched;
  }

  /**
   * How many separate brownout events have been latched since enable.
   *
   * @return the count
   */
  public int countSinceEnable() {
    return m_count;
  }

  /**
   * When the most recent brownout was seen.
   *
   * @return the {@code Clock.seconds()} timestamp, or NaN if none
   */
  public double lastSeconds() {
    return m_lastSeconds;
  }

  /** Clear the latch. The pit does this after writing the event down; nothing clears it by itself. */
  public void clearLatch() {
    m_latched = false;
    m_count = 0;
    m_firstSeconds = Double.NaN;
    m_lastSeconds = Double.NaN;
    if (m_alert != null) {
      m_alert.clearLatched();
      m_alert.set(false);
    }
  }

  @Override
  public String healthName() {
    return kName;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    sample();
    if (!m_latched) {
      return;
    }
    String text =
        String.format(
            "browned out %d time(s) since enable, most recently at t = %.2f s (first at %.2f s). "
                + "The roboRIO cut outputs. Fix the battery or lower your current limits - this "
                + "is why the robot stuttered.",
            m_count, m_lastSeconds, m_firstSeconds);
    out.error(kName, text);
    if (m_alert != null) {
      m_alert.text(text);
      m_alert.set(true);
    }
  }

  /** Every-loop: the latch. Cheap enough to justify running on every cycle. */
  private void sample() {
    boolean enabled = MatchContext.isEnabled();
    m_enabled.update(enabled);
    if (m_enabled.rising()) {
      // A brownout in the previous match is the previous match's problem; this counter is
      // "since enable" so the pit can tell the two apart.
      m_latched = false;
      m_count = 0;
      m_firstSeconds = Double.NaN;
      m_lastSeconds = Double.NaN;
    }
    if (!Platform.isBrownedOut()) {
      m_wasBrownedOut = false;
      return;
    }
    if (!m_wasBrownedOut) {
      m_wasBrownedOut = true;
      m_count++;
      m_lastSeconds = Clock.seconds();
      if (Double.isNaN(m_firstSeconds)) {
        m_firstSeconds = m_lastSeconds;
      }
      m_latched = true;
    }
  }
}

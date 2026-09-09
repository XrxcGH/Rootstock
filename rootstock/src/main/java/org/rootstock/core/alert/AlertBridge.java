package org.rootstock.core.alert;

import java.util.Optional;

/**
 * The seam between an alert going true and a driver-visible notification.
 *
 * <p>Rising edges only, never periodic: a dashboard notification API has no de-duplication, so
 * calling it from {@code periodic()} floods the driver with the same toast fifty times a second.
 * {@link RootstockAlert} does the edge detection and the per-alert rate limit; this class does the
 * delivery, and does nothing at all until a sink is installed.
 *
 * <p><strong>Why a sink and not a direct call.</strong> The notification transport lives in the
 * dashboard domain, and this package must not depend on it — an alert must be raisable from a plain
 * JUnit test with no NetworkTables, no HAL and no dashboard. So the dashboard layer pushes a sink
 * down at boot and the alert layer stays a leaf.
 *
 * <p>Undelivered rising edges are <em>counted</em>, not lost: {@link #describe()} answers "why did I
 * never get a notification?" with a number instead of a shrug.
 */
public final class AlertBridge {

  /**
   * Where a rising-edge notification goes. Implemented by the dashboard domain and installed with
   * {@link AlertBridge#setSink(NotificationSink)}.
   */
  @FunctionalInterface
  public interface NotificationSink {
    /**
     * Deliver one notification. Called on the robot loop thread, on a rising edge only, at most once
     * per {@link RootstockAlert#kMinNotifyCycles} cycles per alert.
     *
     * @param level the alert's severity
     * @param title the alert group, e.g. "Arm"
     * @param detail the alert message
     */
    void send(Severity level, String title, String detail);
  }

  private static NotificationSink m_sink;
  private static boolean m_enabled;
  private static long m_delivered;
  private static long m_dropped;
  private static String m_last;

  private AlertBridge() {}

  /**
   * Install the notification sink. Called once by the dashboard layer at boot.
   *
   * @param sink where notifications go; must not be null
   * @throws IllegalArgumentException if {@code sink} is null. Use {@link #clearSink()} to remove one
   */
  public static void setSink(NotificationSink sink) {
    if (sink == null) {
      throw new IllegalArgumentException(
          "AlertBridge.setSink(null) — pass a NotificationSink, or call AlertBridge.clearSink() if "
              + "you meant to remove the current one.");
    }
    m_sink = sink;
  }

  /** Remove the notification sink. Rising edges are counted as dropped after this. */
  public static void clearSink() {
    m_sink = null;
  }

  /**
   * Whether a sink is installed.
   *
   * @return true when notifications can be delivered
   */
  public static boolean hasSink() {
    return m_sink != null;
  }

  /**
   * Arm the bridge. Called by {@link AlertRegistry#bridgeToDashboard()}, which {@code RootstockRobot}
   * calls once at boot. Disarmed by default so a unit test that raises an alert cannot accidentally
   * push notifications.
   */
  public static void enable() {
    m_enabled = true;
  }

  /** Disarm the bridge. Rising edges are counted as dropped after this. */
  public static void disable() {
    m_enabled = false;
  }

  /**
   * Whether the bridge is armed.
   *
   * @return true when {@link #enable()} has been called and {@link #disable()} has not
   */
  public static boolean isEnabled() {
    return m_enabled;
  }

  /**
   * How many notifications have been delivered to the sink.
   *
   * @return the delivery count since boot or the last {@link #resetForTest()}
   */
  public static long delivered() {
    return m_delivered;
  }

  /**
   * How many rising edges wanted a notification and did not get one, because the bridge was
   * disarmed or no sink was installed.
   *
   * @return the drop count since boot or the last {@link #resetForTest()}
   */
  public static long dropped() {
    return m_dropped;
  }

  /**
   * The most recent notification that was delivered, for tests and for the pit printout.
   *
   * @return the last delivered notification's {@code "Group: message"}, or empty
   */
  public static Optional<String> lastDelivered() {
    return Optional.ofNullable(m_last);
  }

  /**
   * A one-line status: armed or not, sink present or not, and the delivered/dropped counts. Printed
   * in the boot dump so a missing sink is visible rather than mysterious.
   *
   * @return the status line
   */
  public static String describe() {
    return "AlertBridge: "
        + (m_enabled ? "armed" : "disarmed")
        + ", "
        + (m_sink == null ? "no sink installed" : "sink installed")
        + ", delivered="
        + m_delivered
        + ", dropped="
        + m_dropped
        + (m_dropped > 0 && m_sink == null
            ? " (nothing is listening — the dashboard layer calls AlertBridge.setSink(...) at boot)"
            : "");
  }

  /** Clears sink, arming state and counters. For tests only. */
  public static void resetForTest() {
    m_sink = null;
    m_enabled = false;
    m_delivered = 0;
    m_dropped = 0;
    m_last = null;
  }

  // --- package-private -------------------------------------------------------------------------

  /**
   * Deliver one rising edge. Called only by {@link RootstockAlert}, which has already checked that
   * notifications are enabled for that alert and that the per-alert rate limit allows it.
   *
   * <p>A sink that throws is not allowed to take the robot down: the notification is counted as
   * dropped and the loop continues. A dashboard toast is never worth a crash.
   */
  static void onRisingEdge(RootstockAlert alert) {
    NotificationSink sink = m_sink;
    if (!m_enabled || sink == null) {
      m_dropped++;
      return;
    }
    try {
      sink.send(alert.severity(), alert.group(), alert.text());
      m_delivered++;
      m_last = alert.driverLine();
    } catch (RuntimeException e) {
      m_dropped++;
    }
  }
}

package org.rootstock.core.alert;

import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj.Alert;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.rootstock.core.compat.Clock;

/**
 * A registered alert <em>handle</em>, returned by the {@link Alerts} facade.
 *
 * <p>Wraps a real WPILib {@link Alert} — so the stock dashboard Alerts widget shows it with no extra
 * wiring — and adds the five things a competition robot actually needs and WPILib does not provide:
 * registry membership ({@link AlertRegistry}), cycle-limited text updates, rising-edge driver
 * notification, stickiness, latching and debounce.
 *
 * <p><strong>One fault type, not two.</strong> The old {@code RootstockFaults} concept is folded in
 * here: a sticky device fault is {@code alert.sticky(true)}. There is exactly one alert type in this
 * library.
 *
 * <p><strong>Everything here is counted in loop cycles, never in wall-clock milliseconds.</strong>
 * Debounce and text coalescing both use {@link Clock#cycle()}, so a replayed log debounces on
 * exactly the cycles the real robot did and a replay diff means something.
 *
 * <p>Instances are created only by {@link Alerts}. That is not ceremony: every alert in the library
 * going through one facade is what makes the registry, the CI budget gate and the driver mirror
 * possible at all.
 *
 * <p>Not thread-safe for concurrent mutation of a single handle. Alerts are set from robot code on
 * the main loop; {@link AlertRegistry}'s bookkeeping is safe to read from other threads.
 */
public final class RootstockAlert implements AutoCloseable {

  /**
   * Live text updates are coalesced to at most one per this many loop cycles (25 cycles = 0.5 s at
   * 50 Hz). A temperature readout that changes every loop would otherwise be a NetworkTables string
   * write at 50 Hz for a value no human can read that fast.
   */
  public static final int kTextUpdateCycles = 25;

  /**
   * A single alert may raise at most one driver notification per this many cycles (100 cycles = 2 s
   * at 50 Hz), so a flapping condition cannot become a notification storm. The rising edge is the
   * first gate; this is the second.
   */
  public static final int kMinNotifyCycles = 100;

  private final String m_group;
  private final int m_registrationIndex;
  private final BooleanSupplier m_condition;

  private Alert m_wpi;
  private Severity m_severity;
  private MatchImpact m_impact;

  private String m_text;
  private String m_appliedText;
  private long m_lastTextCycle = -kTextUpdateCycles;

  private boolean m_rawCondition;
  private boolean m_active;
  private boolean m_sticky;
  private boolean m_latching;
  private boolean m_latched;
  private boolean m_notifyDriver;
  private boolean m_closed;

  private Time m_debounceDuration;
  private int m_debounceCycles = -1; // resolved lazily; -1 means "not yet resolved"
  private long m_conditionSinceCycle = -1;

  private long m_lastNotifyCycle = Long.MIN_VALUE / 4;
  private String m_expectedAbsentReason = "";

  /**
   * Package-private: alerts are created through {@link Alerts} so that registration, the CI alert
   * budget and the driver mirror can never be bypassed.
   *
   * @param group the dashboard group, e.g. a mechanism name
   * @param text the initial message
   * @param severity the severity
   * @param impact whether this blocks a match
   * @param condition a self-driving condition evaluated by the registry slice, or null for a
   *     manually {@link #set(boolean)} alert
   * @param registrationIndex creation order, used as the stable tiebreak when ranking
   */
  RootstockAlert(
      String group,
      String text,
      Severity severity,
      MatchImpact impact,
      BooleanSupplier condition,
      int registrationIndex) {
    m_group = group;
    m_text = text;
    m_appliedText = text;
    m_severity = severity;
    m_impact = impact;
    m_condition = condition;
    m_registrationIndex = registrationIndex;
    m_notifyDriver = severity == Severity.ERROR; // design default: notify on ERROR only
    m_wpi = new Alert(group, text, severity.toWpi());
  }

  /**
   * Raise or clear this alert.
   *
   * <p>Honours, in this order: {@link #debounce(Time)} (the condition must hold for the configured
   * number of cycles before the alert goes active), then {@link #latching()} and {@link
   * #sticky(boolean)} (once active, stays active until {@link #clearLatched()}). A rising edge may
   * fire one driver notification — see {@link #notifyDriver(boolean)}.
   *
   * <p>Calling this on a closed handle is a no-op rather than an error: a shutdown race must not
   * take the robot down.
   *
   * @param active whether the underlying condition is true right now
   * @return this, for chaining
   */
  public RootstockAlert set(boolean active) {
    if (m_closed) {
      return this;
    }
    m_rawCondition = active;

    boolean debounced;
    if (active) {
      if (m_conditionSinceCycle < 0) {
        m_conditionSinceCycle = Clock.cycle();
      }
      debounced = Clock.cycle() - m_conditionSinceCycle >= debounceCycles();
    } else {
      m_conditionSinceCycle = -1;
      debounced = false;
    }

    if (debounced) {
      m_latched = true;
    }
    boolean wanted = debounced || ((m_latching || m_sticky) && m_latched);

    if (wanted && !m_active) {
      m_active = true;
      m_wpi.set(true);
      maybeNotify();
    } else if (!wanted && m_active) {
      m_active = false;
      m_wpi.set(false);
    }
    return this;
  }

  /**
   * Whether this alert is currently raised.
   *
   * @return true when the alert is showing on the dashboard
   */
  public boolean isActive() {
    return m_active;
  }

  /**
   * Whether this alert claims the robot should not take the field.
   *
   * @return the match impact declared at the call site, possibly demoted by {@link
   *     #demoteExpectedAbsent(String)}
   */
  public MatchImpact impact() {
    return m_impact;
  }

  /**
   * This alert's severity.
   *
   * @return the severity declared at the call site, possibly demoted by {@link
   *     #demoteExpectedAbsent(String)}
   */
  public Severity severity() {
    return m_severity;
  }

  /**
   * The dashboard group this alert belongs to, typically a mechanism name.
   *
   * @return the group string given at the call site
   */
  public String group() {
    return m_group;
  }

  /**
   * The current message.
   *
   * @return the most recently requested text, which may not have been pushed to the dashboard yet
   *     (see {@link #text(String)})
   */
  public String text() {
    return m_text;
  }

  /**
   * Demote to {@code PIT_ONLY} and {@code INFO} because this hardware is knowingly not installed.
   *
   * <p>Set by {@code Health.expectAbsent(healthName, reason)} — not by hand at the alert site. That
   * distinction is the whole point: an alert nobody can demote at its own call site cannot be
   * quietly silenced by the person who wrote it.
   *
   * <p>Demotion <em>relocates</em>, it does not suppress: the alert is still raised, still named,
   * still attributable, and still printed in the boot dump. It just stops claiming the robot cannot
   * play a match.
   *
   * @param reason why the hardware is absent, e.g. "not built yet, week 2"; must not be blank
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code reason} is null or blank
   */
  public RootstockAlert demoteExpectedAbsent(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(
          "demoteExpectedAbsent(reason) needs a reason, got \""
              + reason
              + "\". The reason is printed in the boot dump so the next person knows why the "
              + "robot is missing hardware — pass something like \"not built yet, week 2\".");
    }
    m_expectedAbsentReason = reason;
    m_severity = Severity.INFO;
    m_impact = MatchImpact.PIT_ONLY;
    m_notifyDriver = false;
    retype();
    return this;
  }

  /**
   * Why this alert was demoted, if it was.
   *
   * @return the reason passed to {@link #demoteExpectedAbsent(String)}, or empty
   */
  public Optional<String> expectedAbsentReason() {
    return m_expectedAbsentReason.isEmpty() ? Optional.empty() : Optional.of(m_expectedAbsentReason);
  }

  /**
   * Update the live message, e.g. {@code "Arm leader at 84 C (limit 70 C)"}.
   *
   * <p>Coalesced to one dashboard write per {@value #kTextUpdateCycles} cycles. Calling this every
   * loop is the expected usage and costs one string comparison; the pending text is flushed by the
   * registry slice, so the last value you set always arrives.
   *
   * @param text the new message; ignored if null or blank, because losing the old message is worse
   *     than ignoring a bad update
   * @return this, for chaining
   */
  public RootstockAlert text(String text) {
    if (m_closed || text == null || text.isBlank()) {
      return this;
    }
    m_text = text;
    flushText();
    return this;
  }

  /**
   * Mark this as sticky in the device sense: the condition was latched by hardware, possibly across
   * boots, and must survive being cleared.
   *
   * <p>This is where design doc 04's {@code RootstockFaults} lives — a sticky fault is
   * {@code RootstockAlert.sticky(true)}, so there is one fault type in the library and not two. A
   * sticky alert behaves like a {@link #latching()} one: once raised it stays raised until {@link
   * #clearLatched()}, because a sticky fault from the previous match is exactly the thing you must
   * not lose on the next reboot.
   *
   * @param sticky whether this alert represents a hardware-latched fault
   * @return this, for chaining
   */
  public RootstockAlert sticky(boolean sticky) {
    m_sticky = sticky;
    return this;
  }

  /**
   * Whether this alert represents a hardware-latched (sticky) fault.
   *
   * @return true when {@link #sticky(boolean)} was set
   */
  public boolean isSticky() {
    return m_sticky;
  }

  /**
   * Once true, stays true until {@link #clearLatched()}. For transient events — a brownout lasts
   * 40 ms and matters for the rest of the match.
   *
   * @return this, for chaining
   */
  public RootstockAlert latching() {
    m_latching = true;
    return this;
  }

  /**
   * Clear a latched or sticky alert. If the underlying condition is still true the alert
   * immediately re-raises, which is the honest behaviour.
   *
   * @return this, for chaining
   */
  public RootstockAlert clearLatched() {
    m_latched = false;
    m_conditionSinceCycle = -1;
    set(m_rawCondition);
    return this;
  }

  /**
   * Whether this alert latches (explicitly, or implicitly because it is sticky).
   *
   * @return true when the alert stays raised after its condition clears
   */
  public boolean isLatching() {
    return m_latching || m_sticky;
  }

  /**
   * Suppress until the condition has held this long. Kills flapping alerts — a CAN utilization spike
   * that lasts one loop is not an alert, and one that lasts two seconds is.
   *
   * <p>Converted <em>once</em> to a whole number of loops via {@link Clock#cyclesFor(Time)} and
   * counted in cycles thereafter, so a replayed log debounces on exactly the cycles the real robot
   * did. The conversion is deliberately lazy — resolved on first {@link #set(boolean)} rather than
   * here — because the loop period is not final until {@code RootstockRobot}'s constructor has run,
   * and alerts get constructed in static initialisers before that.
   *
   * @param duration how long the condition must hold; must be positive
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code duration} is null or not positive
   */
  public RootstockAlert debounce(Time duration) {
    if (duration == null || duration.in(Seconds) <= 0.0) {
      throw new IllegalArgumentException(
          "debounce(duration) needs a positive duration, got "
              + duration
              + " for alert \""
              + m_group
              + ": "
              + m_text
              + "\". Expected something like Seconds.of(0.5). Omit the call entirely if you want "
              + "no debounce.");
    }
    m_debounceDuration = duration;
    m_debounceCycles = -1;
    return this;
  }

  /**
   * Send one driver notification on the <em>rising edge only</em>, never from periodic. Default true
   * for {@code ERROR}, false otherwise.
   *
   * <p>The notification goes through {@link AlertBridge}, which is inert until the dashboard layer
   * installs a sink — so this is safe to enable in a unit test.
   *
   * @param enabled whether rising edges notify the driver
   * @return this, for chaining
   */
  public RootstockAlert notifyDriver(boolean enabled) {
    m_notifyDriver = enabled;
    return this;
  }

  /**
   * Whether rising edges of this alert notify the driver.
   *
   * @return true when notifications are enabled
   */
  public boolean isNotifyDriver() {
    return m_notifyDriver;
  }

  /**
   * Escape hatch: the underlying WPILib alert.
   *
   * <p>The returned reference is replaced when {@link #demoteExpectedAbsent(String)} changes the
   * severity, because WPILib's alert type is fixed at construction — do not cache it.
   *
   * @return the live WPILib alert backing this handle
   */
  public Alert raw() {
    return m_wpi;
  }

  /**
   * Creation order within the process, used as the stable tiebreak when ranking blocking alerts.
   *
   * @return the zero-based registration index
   */
  public int registrationIndex() {
    return m_registrationIndex;
  }

  /**
   * Whether {@link #close()} has been called.
   *
   * @return true when this handle is no longer live
   */
  public boolean isClosed() {
    return m_closed;
  }

  /**
   * A one-line description naming the severity, the match impact, the group and the message — the
   * form used in the CI budget failure message and the pit printout.
   *
   * @return a human-readable description
   */
  public String describe() {
    return "[" + m_severity + "/" + m_impact + "] " + m_group + ": " + m_text;
  }

  /**
   * The driver-mirror row form: group and message only. Severity is carried by the widget, and the
   * driver has two seconds to read this.
   *
   * @return {@code "Group: message"}
   */
  public String driverLine() {
    return m_group + ": " + m_text;
  }

  @Override
  public String toString() {
    return describe();
  }

  /** Unregisters and closes the underlying WPILib alert. Idempotent. */
  @Override
  public void close() {
    if (m_closed) {
      return;
    }
    m_closed = true;
    m_active = false;
    m_wpi.close();
    AlertRegistry.unregister(this);
  }

  // --- package-private, driven by AlertRegistry's round-robin slice ---------------------------

  /** Evaluates the self-driving condition, if any, and flushes any pending text update. */
  void poll() {
    if (m_closed) {
      return;
    }
    if (m_condition != null) {
      set(m_condition.getAsBoolean());
    }
    flushText();
  }

  /** True when this alert drives itself from a BooleanSupplier. */
  boolean isSelfDriving() {
    return m_condition != null;
  }

  private void flushText() {
    if (m_text.equals(m_appliedText)) {
      return;
    }
    long cycle = Clock.cycle();
    if (cycle - m_lastTextCycle < kTextUpdateCycles) {
      return;
    }
    m_wpi.setText(m_text);
    m_appliedText = m_text;
    m_lastTextCycle = cycle;
  }

  private int debounceCycles() {
    if (m_debounceCycles < 0) {
      m_debounceCycles = m_debounceDuration == null ? 0 : Clock.cyclesFor(m_debounceDuration);
    }
    return m_debounceCycles;
  }

  private void maybeNotify() {
    if (!m_notifyDriver) {
      return;
    }
    long cycle = Clock.cycle();
    if (cycle - m_lastNotifyCycle < kMinNotifyCycles) {
      return;
    }
    m_lastNotifyCycle = cycle;
    AlertBridge.onRisingEdge(this);
  }

  /**
   * Rebuilds the WPILib alert after a severity change. WPILib's AlertType is fixed at construction,
   * so the only way to change it is to close and recreate — chosen because the alternative (holding
   * three alerts and swapping which one is set) leaves stale rows on the dashboard.
   */
  private void retype() {
    boolean wasActive = m_active;
    m_wpi.close();
    m_wpi = new Alert(m_group, m_text, m_severity.toWpi());
    m_appliedText = m_text;
    m_wpi.set(wasActive);
  }
}

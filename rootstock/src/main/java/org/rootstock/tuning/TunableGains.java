package org.rootstock.tuning;

import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.EnumMap;
import java.util.Map;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;

/**
 * The seven-gain set for one mechanism, published as editable doubles under
 * {@code /Tuning/<Mechanism>/}.
 *
 * <p>When any gain changes, the new {@link Gains} is pushed through the mechanism's
 * {@code TuningTarget.gainSink().apply(...)}, so a slider move actually reaches the device's gain
 * slot instead of sitting in an NT table next to it. That write-through is the difference between a
 * tuning table and a tuning <i>system</i>.
 *
 * <p><b>The seven topics are generated, not typed.</b> The list is built by iterating
 * {@code GainId.values()}, so a gain that can be published to a dashboard but cannot be applied back
 * to the record is structurally impossible.
 *
 * <p><b>Change-gated and rate-limited.</b> An apply happens only when at least one gain actually
 * differs from what the sink last accepted, and at most once per {@link #MIN_APPLY_PERIOD_SEC}
 * seconds. Dragging a slider therefore produces about ten config writes per second, not fifty — and
 * each is the sink's non-blocking fast path, never a blocking verified write, because this runs while
 * a student's thumb is on the slider.
 *
 * <p><b>The {@code /applied} echo</b> (D11a(c)). After a successful apply the value the sink accepted
 * is published to {@code /Tuning/<Mechanism>/<gain>/applied}. The round trip is therefore visible on
 * the dashboard, and a rejected config is diagnosable without a log pull: the slider says 128.0 and
 * the echo says 80.0, and a student can see it.
 */
public final class TunableGains {

  /**
   * The shortest interval between two {@code GainSink.apply} calls for one mechanism, in seconds.
   *
   * <p>100 ms rather than one loop because the thing being protected is the CAN bus, and because the
   * NT round trip from a dashboard slider is itself 100–150 ms — applying faster than the values
   * arrive buys nothing and costs bus bandwidth during the exact minutes a student is watching the
   * mechanism move.
   */
  public static final double MIN_APPLY_PERIOD_SEC = 0.100;

  /** The topic suffix that echoes back what the device actually accepted. */
  public static final String kAppliedSuffix = "/applied";

  private final TuningTarget m_target;
  private final String m_mechanism;
  private final Gains m_default;
  private final Map<GainId, TunableDouble> m_tunables = new EnumMap<>(GainId.class);
  private final Map<GainId, DoublePublisher> m_echoes = new EnumMap<>(GainId.class);

  private Gains m_current;
  private Gains m_applied;
  private double m_lastApplySeconds = Double.NEGATIVE_INFINITY;
  private int m_rejections;
  private RootstockAlert m_rejectAlert;

  /**
   * Package-private: always built by {@code TuningRegistry.gains(target)}, which owns the one
   * transport, the namespace allowlist and the persisted-value resolution.
   */
  TunableGains(TuningTarget target, String mechanism, Gains resolvedDefault, NetworkTableInstance nt) {
    m_target = target;
    m_mechanism = mechanism;
    m_default = resolvedDefault;
    m_current = resolvedDefault;
    m_applied = null;

    for (GainId id : GainId.values()) {
      double value = resolvedDefault.get(id);
      TunableDouble tunable =
          TuningRegistry.tunable(mechanism, id.key(), value, id.unitFor(target.siDomain()));
      tunable.onChange(v -> accept(id, v));
      m_tunables.put(id, tunable);
      m_echoes.put(id, nt.getDoubleTopic(tunable.fullKey() + kAppliedSuffix).publish());
    }
  }

  /**
   * The gains in force right now.
   *
   * <p>Cheap: returns a cached immutable record and allocates nothing. When tuning is disabled this
   * is the resolved boot-time value, which is the number the mechanism is genuinely running.
   *
   * @return the current gains, in volts per SI unit
   */
  public Gains get() {
    return TuningRegistry.isTuningEnabled() ? m_current : m_default;
  }

  /**
   * The gains the sink last accepted, or empty gains if nothing has been applied yet.
   *
   * <p>This is what the {@code /applied} echo carries. It differs from {@link #get()} exactly when a
   * device rejected a config, which is the case worth being able to see.
   *
   * @return the last accepted gains, or {@link #get()} when no apply has happened yet
   */
  public Gains appliedGains() {
    return m_applied == null ? get() : m_applied;
  }

  /**
   * The value the mechanism booted with, after the four-tier persistence merge.
   *
   * @return the resolved boot-time gains
   */
  public Gains defaults() {
    return m_default;
  }

  /**
   * The mechanism this gain set belongs to.
   *
   * @return the NT namespace, e.g. {@code "Elevator"}
   */
  public String mechanism() {
    return m_mechanism;
  }

  /**
   * The target these gains are written through to.
   *
   * @return the tuning target
   */
  public TuningTarget target() {
    return m_target;
  }

  /**
   * The individual tunable behind one gain, for a UI that wants its key or its unit.
   *
   * @param id which gain
   * @return the tunable; never null, because the set is generated from {@code GainId.values()}
   */
  public TunableDouble tunable(GainId id) {
    return m_tunables.get(id);
  }

  /**
   * Poll the cached fields, and if anything changed, call {@code gainSink().apply(...)} and publish
   * the {@code /applied} echo.
   *
   * <p>Called for you by the {@code "Tuning"} slice; you never write {@code checkAndApply} yourself.
   * Package-private for exactly that reason.
   */
  void checkAndApply() {
    if (!TuningRegistry.isTuningEnabled()) {
      return;
    }
    Gains now = m_current;
    if (now.equals(m_applied)) {
      return;
    }
    double seconds = Clock.seconds();
    if (seconds - m_lastApplySeconds < MIN_APPLY_PERIOD_SEC) {
      return;
    }
    m_lastApplySeconds = seconds;
    applyNow(now);
  }

  /**
   * Overwrite from code: publishes to NetworkTables and applies immediately.
   *
   * <p>The wizard does this on Accept, so the dashboard and the device agree the instant a fit is
   * accepted rather than up to 100 ms later.
   *
   * @param gains the new gains, in volts per SI unit
   */
  public void set(Gains gains) {
    if (gains == null) {
      return;
    }
    for (GainId id : GainId.values()) {
      m_tunables.get(id).set(gains.get(id));
    }
    m_current = gains;
    m_lastApplySeconds = Clock.seconds();
    applyNow(gains);
  }

  /**
   * Revert every gain to the value the mechanism booted with, and apply.
   *
   * <p>Bound to the wizard's global Abort. "Put it back the way it was" must be one action and must
   * not depend on a student remembering seven numbers.
   */
  public void resetToDefaults() {
    set(m_default);
  }

  /**
   * The exact conversion that will be performed on the way into the device, for display.
   *
   * <p>Delegates to the sink, because the conversion and the thing that performs it are one object
   * and therefore cannot disagree.
   *
   * @return e.g. {@code "1 mechanism rotation = 0.2794 m; kP 128.000 V/m -> Slot0.kP 35.7632"}
   */
  public String describeConversion() {
    try {
      return m_target.gainSink().describeConversion();
    } catch (RuntimeException e) {
      // A sink that throws while describing itself must not take out the UI slice.
      return "(the gain sink could not describe its conversion: " + e + ")";
    }
  }

  /**
   * How many times the device has rejected a config this session.
   *
   * @return the rejection count
   */
  public int rejectionCount() {
    return m_rejections;
  }

  /** Closes the echo publishers. Called from the lifecycle hook's {@code close()}. */
  void close() {
    for (DoublePublisher publisher : m_echoes.values()) {
      publisher.close();
    }
  }

  private void accept(GainId id, double value) {
    m_current = m_current.with(id, value);
  }

  private void applyNow(Gains gains) {
    boolean accepted;
    try {
      accepted = m_target.gainSink().apply(gains);
    } catch (RuntimeException e) {
      accepted = false;
    }
    if (accepted) {
      m_applied = gains;
      for (GainId id : GainId.values()) {
        m_echoes.get(id).set(gains.get(id));
      }
      return;
    }
    m_rejections++;
    if (m_rejectAlert == null) {
      m_rejectAlert =
          Alerts.warning(
              TuningRegistry.kAlertGroup,
              m_mechanism
                  + ": the motor controller REJECTED the gains written from the dashboard. The"
                  + " sliders under /Tuning/"
                  + m_mechanism
                  + "/ do not match what the device is running; compare each one against its"
                  + " /applied echo to see which value came back different. Fix: check the device is"
                  + " on the bus and that the value is inside the vendor's legal range for that"
                  + " gain.",
              MatchImpact.PIT_ONLY);
    }
    m_rejectAlert.set(true);
  }
}

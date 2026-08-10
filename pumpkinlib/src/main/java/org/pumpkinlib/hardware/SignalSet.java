package org.pumpkinlib.hardware;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A batched status-signal subscription in which <b>subscribing and reading are the same
 * statement</b>.
 *
 * <h2>The bug this class exists to make unrepresentable</h2>
 *
 * <p>A motor IO once subscribed supply current and the closed-loop reference only under a telemetry
 * tier check, never subscribed the two limit signals at all, then called the vendor's
 * "optimize bus utilization" call — which sets every unrequested signal to 0 Hz. Its {@code
 * updateInputs} then read all four unconditionally. Below the top telemetry tier those four fields
 * were frozen at their power-on values <b>forever, with no error</b>: a plot that looks like a
 * working sensor and is not. It also made the documented promise "declaring a motor limit costs zero
 * extra CAN traffic — the motor already reports it" permanently false.
 *
 * <p>A second {@code if} does not fix that. The fix is structural: <b>you cannot read a signal you
 * did not subscribe, because the only way to read is to iterate the set you declared.</b> {@link
 * Builder#add(Channel, Object, double, Sink)} takes the subscription and the read in one call, and
 * {@link Builder#build(MotorInputs)} stamps every channel you did <i>not</i> declare to {@link
 * Double#NaN} exactly once.
 *
 * <h2>Why this lives in core and is generic over the signal type</h2>
 *
 * <p>Phoenix's CAN efficiency — one bus transaction for N signals — has to survive the vendor
 * abstraction, or the abstraction costs bus utilisation and gets deleted by any team that measures
 * it. But the batching <i>policy</i> (declare, stamp, set rates, then optimise, in that order) is not
 * vendor-specific and must not be re-implemented per backend. So the policy is here, in the core
 * artifact with zero vendor imports, and the type parameter {@code S} is the vendor's signal type:
 * {@code StatusSignal<?>} for Phoenix, a supplier for a backend with no signal object at all.
 *
 * <p><b>Ordering in {@link Builder#build(MotorInputs)} is load-bearing:</b> stamp absent channels,
 * then apply per-rate update frequencies, then optimise the bus. Optimising first would zero the
 * frequencies of the signals just requested — which is precisely the original bug, one line earlier.
 *
 * @param <S> the backend's status-signal type; core never names a vendor type
 */
public final class SignalSet<S> {

  /**
   * The readable channels. One channel writes exactly one {@link MotorInputs} field, which is what
   * makes "was it subscribed?" and "is that number real?" the same question.
   */
  public enum Channel {
    /** Output-shaft position. */
    POSITION,
    /** Output-shaft velocity. */
    VELOCITY,
    /** Volts the device is applying. */
    APPLIED_VOLTS,
    /** Winding current — current-spike homing and stall detection depend on it. */
    STATOR,
    /** Battery-side current — the brownout and power monitors depend on it. */
    SUPPLY,
    /** Torque-producing current, on devices that measure one. */
    TORQUE_CURRENT,
    /** Device temperature. */
    TEMPERATURE,
    /** The device's own belief about its closed-loop target. */
    CLOSED_LOOP_REFERENCE,
    /** Forward hardware limit switch wired into the controller. */
    FORWARD_LIMIT,
    /** Reverse hardware limit switch wired into the controller. */
    REVERSE_LIMIT;

    /**
     * Stamps the {@link MotorInputs} field this channel backs with its "not measured" sentinel.
     *
     * <p>Called once per unsubscribed channel at build time and never again, so a value that was
     * never measured reads NaN (or {@code valid == false}) for the whole session instead of reading
     * as a plausible zero.
     *
     * @param in the inputs object to stamp
     */
    public void stampAbsent(MotorInputs in) {
      if (in == null) {
        return;
      }
      switch (this) {
        case POSITION -> in.positionRot = Double.NaN;
        case VELOCITY -> in.velocityRps = Double.NaN;
        case APPLIED_VOLTS -> in.appliedVolts = Double.NaN;
        case STATOR -> in.statorCurrentAmps = Double.NaN;
        case SUPPLY -> in.supplyCurrentAmps = Double.NaN;
        case TORQUE_CURRENT -> in.torqueCurrentAmps = Double.NaN;
        case TEMPERATURE -> in.temperatureCelsius = Double.NaN;
        case CLOSED_LOOP_REFERENCE -> in.closedLoopReferenceRot = Double.NaN;
        case FORWARD_LIMIT -> {
          in.forwardLimitTripped = false;
          in.forwardLimitValid = false;
        }
        case REVERSE_LIMIT -> {
          in.reverseLimitTripped = false;
          in.reverseLimitValid = false;
        }
      }
    }
  }

  /**
   * Writes exactly one {@link MotorInputs} field from exactly one signal.
   *
   * @param <S> the backend's signal type
   */
  @FunctionalInterface
  public interface Sink<S> {
    /**
     * Copies one freshly refreshed signal into one inputs field.
     *
     * @param in the inputs object being filled
     * @param signal the signal, already refreshed by the batch call
     */
    void accept(MotorInputs in, S signal);
  }

  /**
   * The backend's one-transaction refresh — Phoenix's {@code BaseStatusSignal.refreshAll(...)}.
   *
   * @param <S> the backend's signal type
   */
  @FunctionalInterface
  public interface BatchRefresh<S> {
    /**
     * Refreshes every signal in the list in a single transaction.
     *
     * @param signals the declared signals, in declaration order; never null, possibly empty
     * @return true when the transaction succeeded
     */
    boolean refreshAll(List<S> signals);
  }

  /**
   * Applies one update frequency to one group of signals — Phoenix's {@code
   * BaseStatusSignal.setUpdateFrequencyForAll(hz, signals)}.
   *
   * @param <S> the backend's signal type
   */
  @FunctionalInterface
  public interface RateApplier<S> {
    /**
     * Sets the update frequency for a group of signals that share a rate.
     *
     * @param hertz the requested rate
     * @param signals the signals at that rate
     */
    void apply(double hertz, List<S> signals);
  }

  private final String m_owner;
  private final List<S> m_signals;
  private final List<Sink<S>> m_sinks;
  private final EnumSet<Channel> m_present;
  private final BatchRefresh<S> m_refresh;

  private SignalSet(
      String owner,
      List<S> signals,
      List<Sink<S>> sinks,
      EnumSet<Channel> present,
      BatchRefresh<S> refresh) {
    m_owner = owner;
    m_signals = signals;
    m_sinks = sinks;
    m_present = present;
    m_refresh = refresh;
  }

  /**
   * Starts declaring a signal set.
   *
   * @param <S> the backend's signal type
   * @param owner the mechanism or device name, used in the description and in alerts
   * @param refresh the backend's batched refresh; a null refresher is read as "always succeeds",
   *     which is what a backend whose signals are plain suppliers wants
   * @return a builder
   */
  public static <S> Builder<S> builder(String owner, BatchRefresh<S> refresh) {
    return new Builder<>(owner, refresh);
  }

  /**
   * ONE batched transaction, then fan out into {@code in}. Allocation-free on the steady-state path.
   *
   * @param in the inputs object to fill
   * @return true when the batched refresh reported success; an empty set trivially succeeds
   */
  public boolean refreshInto(MotorInputs in) {
    boolean ok = m_signals.isEmpty() || m_refresh.refreshAll(m_signals);
    for (int i = 0; i < m_signals.size(); i++) {
      m_sinks.get(i).accept(in, m_signals.get(i));
    }
    return ok;
  }

  /**
   * Whether a channel was declared, and therefore whether its {@link MotorInputs} field carries a
   * real number.
   *
   * @param channel the channel to test
   * @return true when it was subscribed
   */
  public boolean has(Channel channel) {
    return m_present.contains(channel);
  }

  /**
   * The declared channels.
   *
   * @return a copy; mutating it does not change the set
   */
  public EnumSet<Channel> channels() {
    return EnumSet.copyOf(m_present);
  }

  /**
   * The declared signals, in declaration order.
   *
   * @return an unmodifiable view — the same list handed to {@link BatchRefresh}
   */
  public List<S> signals() {
    return m_signals;
  }

  /**
   * How many signals this set refreshes per loop.
   *
   * @return the signal count
   */
  public int size() {
    return m_signals.size();
  }

  /**
   * The owner name this set was declared under.
   *
   * @return the owner name
   */
  public String owner() {
    return m_owner;
  }

  /**
   * The subscription as the boot dump prints it: what is subscribed and, just as importantly, what
   * is therefore NaN.
   *
   * @return a human-readable summary
   */
  public String describe() {
    StringBuilder out = new StringBuilder(160);
    out.append(m_owner).append(": ").append(m_signals.size()).append(" signals subscribed [");
    boolean first = true;
    for (Channel c : Channel.values()) {
      if (m_present.contains(c)) {
        if (!first) {
          out.append(", ");
        }
        out.append(c);
        first = false;
      }
    }
    out.append("]; NOT subscribed (reads NaN) [");
    first = true;
    for (Channel c : Channel.values()) {
      if (!m_present.contains(c)) {
        if (!first) {
          out.append(", ");
        }
        out.append(c);
        first = false;
      }
    }
    return out.append(']').toString();
  }

  /**
   * Declares a signal set: one {@link #add(Channel, Object, double, Sink)} call per channel, then
   * one {@link #build(MotorInputs)}.
   *
   * @param <S> the backend's signal type
   */
  public static final class Builder<S> {
    private final String m_owner;
    private final BatchRefresh<S> m_refresh;
    private final List<S> m_signals = new ArrayList<>();
    private final List<Sink<S>> m_sinks = new ArrayList<>();
    private final EnumSet<Channel> m_present = EnumSet.noneOf(Channel.class);
    private final Map<Double, List<S>> m_rates = new LinkedHashMap<>();
    private RateApplier<S> m_rateApplier;
    private Runnable m_optimizer;

    private Builder(String owner, BatchRefresh<S> refresh) {
      m_owner = owner == null || owner.isBlank() ? "motor" : owner;
      m_refresh = refresh == null ? signals -> true : refresh;
    }

    /**
     * Subscribe AND declare the read, together — the whole point of this class.
     *
     * <p>A repeated channel replaces nothing and is ignored: two sinks writing one field is a bug
     * that would show up as a value that flickers between two sources.
     *
     * @param channel which inputs field this signal backs
     * @param signal the backend's signal object; a null signal is ignored, leaving the channel
     *     absent and its field NaN, which is the honest outcome when a device does not have it
     * @param hertz the requested update rate; signals sharing a rate are grouped into one call
     * @param sink the one-line copy from signal to inputs field
     * @return this builder
     */
    public Builder<S> add(Channel channel, S signal, double hertz, Sink<S> sink) {
      if (channel == null || signal == null || sink == null || m_present.contains(channel)) {
        return this;
      }
      m_present.add(channel);
      m_signals.add(signal);
      m_sinks.add(sink);
      m_rates.computeIfAbsent(hertz, k -> new ArrayList<>()).add(signal);
      return this;
    }

    /**
     * The backend's per-rate frequency call, applied at {@link #build(MotorInputs)} time.
     *
     * @param applier the applier, or null for a backend with no notion of update frequency
     * @return this builder
     */
    public Builder<S> rateApplier(RateApplier<S> applier) {
      m_rateApplier = applier;
      return this;
    }

    /**
     * The backend's "set every unrequested signal to 0 Hz" call.
     *
     * <p>Run <b>last</b>, after the rates are set. Running it first is the original bug.
     *
     * @param optimizer the optimiser, or null for a backend that has none
     * @return this builder
     */
    public Builder<S> optimizer(Runnable optimizer) {
      m_optimizer = optimizer;
      return this;
    }

    /**
     * Stamps every ABSENT channel's inputs field to its sentinel, applies the per-rate update
     * frequencies, then runs the optimiser — in that order.
     *
     * @param stampInto the inputs object this set will fill; every unsubscribed channel is stamped
     *     NaN here, once. Null skips stamping, which is only correct for a freshly constructed
     *     inputs object whose fields are already NaN.
     * @return the immutable signal set
     */
    public SignalSet<S> build(MotorInputs stampInto) {
      if (stampInto != null) {
        for (Channel c : Channel.values()) {
          if (!m_present.contains(c)) {
            c.stampAbsent(stampInto);
          }
        }
      }
      if (m_rateApplier != null) {
        for (Map.Entry<Double, List<S>> group : m_rates.entrySet()) {
          m_rateApplier.apply(group.getKey(), Collections.unmodifiableList(group.getValue()));
        }
      }
      if (m_optimizer != null) {
        m_optimizer.run();
      }
      return new SignalSet<>(
          m_owner,
          Collections.unmodifiableList(new ArrayList<>(m_signals)),
          Collections.unmodifiableList(new ArrayList<>(m_sinks)),
          EnumSet.copyOf(m_present),
          m_refresh);
    }
  }
}

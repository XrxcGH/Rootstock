package org.pumpkinlib.tuning;

import edu.wpi.first.networktables.BooleanPublisher;
import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableListenerPoller;
import edu.wpi.first.networktables.NetworkTableValue;
import edu.wpi.first.util.function.BooleanConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * The one shipped {@link TunableTransport}: <b>one poller, one {@code readQueue()}, one
 * {@code processInputs}</b>.
 *
 * <p><b>Why one poller and not one subscriber per tunable (binding decision D11a(b)).</b> The model
 * this replaces performed one {@code DoubleSubscriber.get()} per tunable per loop — roughly 250 JNI
 * calls per loop on a twelve-mechanism robot. A single {@link NetworkTableListenerPoller} subscribed
 * to the {@code /Tuning/} prefix costs <b>one</b> JNI call per loop regardless of how many tunables
 * exist, and loses no intermediate value, because the queue holds every change rather than the latest
 * one.
 *
 * <p><b>Why it is replay-safe.</b> Every value that crosses from the wire into robot code crosses
 * through {@link TuningInputs}, which is pushed through {@code PumpkinLog.processInputs("Tuning", …)}
 * exactly once per loop. In {@code REPLAY} mode {@code processInputs} overwrites the struct from the
 * log and <b>the poller is never read</b>, so a replayed run reproduces the exact dashboard value
 * that was live at the time instead of quietly falling back to the compile-time default.
 *
 * <p><b>What one enabled loop costs.</b> One {@code readQueue()} — which allocates one small
 * {@code NetworkTableEvent[]}, and that is the single named allocation on the enabled path — one
 * array-length check, one {@code processInputs}. Two map lookups per <i>event</i>, and there are
 * normally zero events. When tuning is disabled the poller is never touched at all.
 */
public final class AdvantageKitTunableTransport implements TunableTransport {

  /** The NT prefix this transport listens to. Nothing outside it is ever read. */
  public static final String kPrefix = TuningRegistry.kPrefix;

  /** The single {@code processInputs} key every tunable in the robot round-trips through. */
  public static final String kInputsKey = "Tuning";

  private final NetworkTableInstance m_instance;
  private final NetworkTableListenerPoller m_poller;
  private final TuningInputs m_inputs = new TuningInputs();

  // LinkedHashMap, never HashMap: replay rule 2 forbids iteration-order dependence in anything that
  // produces an output, and the sorted key arrays below are produced from these.
  private final Map<String, DoubleHandle> m_doubles = new LinkedHashMap<>();
  private final Map<String, FlagHandleImpl> m_flags = new LinkedHashMap<>();

  // Positional views of the two maps, frozen at the first drain so the wire layout is stable.
  private final List<DoubleHandle> m_doubleOrder = new ArrayList<>();
  private final List<FlagHandleImpl> m_flagOrder = new ArrayList<>();

  private boolean m_frozen;
  private boolean m_everLogged;

  /**
   * Builds the transport and registers its one listener.
   *
   * <p>Created lazily by {@link TuningRegistry} the first time a tunable or a target is registered,
   * so a robot that never tunes anything never touches NetworkTables from this package.
   */
  public AdvantageKitTunableTransport() {
    this(NetworkTableInstance.getDefault());
  }

  /**
   * Builds the transport against a specific NetworkTables instance.
   *
   * <p>The instance parameter exists for tests, which run against a private instance so one test
   * cannot see another test's topics.
   *
   * @param instance the instance to publish and listen on
   */
  public AdvantageKitTunableTransport(NetworkTableInstance instance) {
    m_instance = instance;
    m_poller = new NetworkTableListenerPoller(instance);
    m_poller.addListener(new String[] {kPrefix}, EnumSet.of(NetworkTableEvent.Kind.kValueAll));
  }

  @Override
  public Handle register(String fullKey, double defaultValue) {
    DoubleHandle existing = m_doubles.get(fullKey);
    if (existing != null) {
      return existing;
    }
    DoublePublisher publisher = m_instance.getDoubleTopic(fullKey).publish();
    publisher.setDefault(defaultValue);
    DoubleHandle handle = new DoubleHandle(fullKey, defaultValue, publisher);
    m_doubles.put(fullKey, handle);
    m_doubleOrder.add(handle);
    if (!m_frozen) {
      m_doubleOrder.sort((a, b) -> a.m_key.compareTo(b.m_key));
    }
    return handle;
  }

  @Override
  public FlagHandle registerFlag(String fullKey, boolean defaultValue) {
    FlagHandleImpl existing = m_flags.get(fullKey);
    if (existing != null) {
      return existing;
    }
    BooleanPublisher publisher = m_instance.getBooleanTopic(fullKey).publish();
    publisher.setDefault(defaultValue);
    FlagHandleImpl handle = new FlagHandleImpl(fullKey, defaultValue, publisher);
    m_flags.put(fullKey, handle);
    m_flagOrder.add(handle);
    if (!m_frozen) {
      m_flagOrder.sort((a, b) -> a.m_key.compareTo(b.m_key));
    }
    return handle;
  }

  @Override
  public void drain() {
    boolean replay = PumpkinLog.isReplay();

    if (!replay && TuningRegistry.isTuningEnabled()) {
      // THE one JNI call. Everything else in this method is local field work.
      NetworkTableEvent[] events = m_poller.readQueue();
      for (NetworkTableEvent event : events) {
        dispatch(event);
      }
    }

    freeze();

    if (!replay) {
      for (int i = 0; i < m_doubleOrder.size(); i++) {
        m_inputs.values[i] = m_doubleOrder.get(i).m_value;
      }
      for (int i = 0; i < m_flagOrder.size(); i++) {
        m_inputs.flagValues[i] = m_flagOrder.get(i).m_value;
      }
    }

    m_everLogged = true;
    PumpkinLog.processInputs(kInputsKey, m_inputs);

    if (replay) {
      replayBack();
    }
  }

  @Override
  public String describe() {
    return "AdvantageKit transport: one NetworkTableListenerPoller on \""
        + kPrefix
        + "\", one readQueue() per loop, one processInputs(\""
        + kInputsKey
        + "\"). "
        + m_doubles.size()
        + " tunable double(s), "
        + m_flags.size()
        + " flag(s). Replay reads the log, never the wire.";
  }

  /**
   * Releases the poller and every publisher.
   *
   * <p>Called from the lifecycle hook's {@code close()}. Must not throw.
   */
  public void close() {
    for (DoubleHandle handle : m_doubleOrder) {
      handle.m_publisher.close();
    }
    for (FlagHandleImpl handle : m_flagOrder) {
      handle.m_publisher.close();
    }
    m_poller.close();
  }

  /**
   * Whether {@code processInputs} has run at least once this session.
   *
   * <p>{@link TuningRegistry#drainPoller()} reads this to decide whether the constant-time disabled
   * early-return is still safe: once a key has been registered with the replay tripwire it must be
   * processed every cycle, unconditionally, or the tripwire reports a MISSING_PROCESS_INPUTS
   * violation for a key that simply stopped being interesting.
   *
   * @return true once the struct has been logged
   */
  boolean hasLoggedInputs() {
    return m_everLogged;
  }

  /** How many double topics are registered. Used by the boot dump and by tests. */
  int doubleCount() {
    return m_doubles.size();
  }

  /** How many boolean topics are registered. Used by the boot dump and by tests. */
  int flagCount() {
    return m_flags.size();
  }

  private void dispatch(NetworkTableEvent event) {
    if (event.valueData == null) {
      return;
    }
    String name = event.valueData.getTopic().getName();
    NetworkTableValue value = event.valueData.value;
    if (value.isDouble()) {
      DoubleHandle handle = m_doubles.get(name);
      if (handle != null) {
        handle.accept(value.getDouble());
      }
      return;
    }
    if (value.isBoolean()) {
      FlagHandleImpl handle = m_flags.get(name);
      if (handle != null) {
        handle.accept(value.getBoolean());
      }
    }
    // Anything else is ignored rather than logged: /Tuning/ is a public NT prefix and a dashboard
    // may write whatever it likes into it. An unknown topic is not a defect in the robot code.
  }

  /**
   * Sizes and names the logged arrays once, then never reorders them.
   *
   * <p>A tunable created after the first drain is appended at the end rather than re-sorted into
   * place: a replay of an older log against newer code must misalign visibly at the tail, not
   * silently in the middle.
   */
  private void freeze() {
    if (m_inputs.keys.length != m_doubleOrder.size()) {
      String[] keys = new String[m_doubleOrder.size()];
      for (int i = 0; i < keys.length; i++) {
        keys[i] = m_doubleOrder.get(i).m_key;
      }
      m_inputs.keys = keys;
      m_inputs.values = Arrays.copyOf(m_inputs.values, keys.length);
    }
    if (m_inputs.flagKeys.length != m_flagOrder.size()) {
      String[] keys = new String[m_flagOrder.size()];
      for (int i = 0; i < keys.length; i++) {
        keys[i] = m_flagOrder.get(i).m_key;
      }
      m_inputs.flagKeys = keys;
      m_inputs.flagValues = Arrays.copyOf(m_inputs.flagValues, keys.length);
    }
    m_frozen = true;
  }

  /**
   * Pushes the replayed struct back into the handles, by key rather than by index.
   *
   * <p>By key because a log written by an older build may carry a different key set; matching by
   * index there would assign an elevator's kP to a shooter's kV, which is the failure mode the
   * positional-with-stable-order rule exists to make impossible <em>and</em> detectable.
   */
  private void replayBack() {
    for (int i = 0; i < m_inputs.keys.length; i++) {
      DoubleHandle handle = m_doubles.get(m_inputs.keys[i]);
      if (handle != null) {
        handle.accept(m_inputs.values[i]);
      }
    }
    for (int i = 0; i < m_inputs.flagKeys.length; i++) {
      FlagHandleImpl handle = m_flags.get(m_inputs.flagKeys[i]);
      if (handle != null) {
        handle.accept(m_inputs.flagValues[i]);
      }
    }
  }

  /** One double topic: a cached primitive, a publisher, and at most one change listener. */
  private static final class DoubleHandle implements Handle {
    private final String m_key;
    private final double m_default;
    private final DoublePublisher m_publisher;
    private double m_value;
    private DoubleConsumer m_listener;

    DoubleHandle(String key, double defaultValue, DoublePublisher publisher) {
      m_key = key;
      m_default = defaultValue;
      m_publisher = publisher;
      m_value = defaultValue;
    }

    @Override
    public double get() {
      // No NT access. The whole point of the poller model is that this is a field read.
      return TuningRegistry.isTuningEnabled() ? m_value : m_default;
    }

    @Override
    public void set(double value) {
      m_publisher.set(value);
      accept(value);
    }

    @Override
    public void onChanged(DoubleConsumer listener) {
      m_listener = listener;
    }

    /**
     * Accepts a new value from the wire or from the log.
     *
     * <p>Compared with {@code !=} on the raw double, exactly as 6328 does. No epsilon: a dashboard
     * edit is always an exact new value, and an epsilon would silently swallow a deliberate
     * four-decimal kG adjustment.
     */
    void accept(double value) {
      if (value == m_value) {
        return;
      }
      m_value = value;
      if (m_listener != null) {
        m_listener.accept(value);
      }
    }
  }

  /** One boolean topic. Deliberately the smaller surface: no unit, no range, no fan-in. */
  private static final class FlagHandleImpl implements FlagHandle {
    private final String m_key;
    private final boolean m_default;
    private final BooleanPublisher m_publisher;
    private boolean m_value;
    private BooleanConsumer m_listener;

    FlagHandleImpl(String key, boolean defaultValue, BooleanPublisher publisher) {
      m_key = key;
      m_default = defaultValue;
      m_publisher = publisher;
      m_value = defaultValue;
    }

    @Override
    public boolean get() {
      return TuningRegistry.isTuningEnabled() ? m_value : m_default;
    }

    @Override
    public void set(boolean value) {
      m_publisher.set(value);
      accept(value);
    }

    @Override
    public void onChanged(BooleanConsumer listener) {
      m_listener = listener;
    }

    void accept(boolean value) {
      if (value == m_value) {
        return;
      }
      m_value = value;
      if (m_listener != null) {
        m_listener.accept(value);
      }
    }
  }
}

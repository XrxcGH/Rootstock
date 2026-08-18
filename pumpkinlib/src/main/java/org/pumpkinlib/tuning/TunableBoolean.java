package org.pumpkinlib.tuning;

import edu.wpi.first.util.function.BooleanConsumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * An NT4-backed boolean with a compile-time default — a pit switch, not a number.
 *
 * <p>Created by {@code TuningRegistry.tunableFlag(namespace, key, defaultValue)}. The motivating
 * caller is a per-camera kill switch: {@code
 * VisionFilters.enabledWhen(TuningRegistry.tunableFlag("Vision", "camera0Enabled", true))} lets a team
 * disable a dead camera in the pit without a redeploy.
 *
 * <p>{@code implements BooleanSupplier} is the load-bearing part of the contract, not a convenience:
 * it is what lets a handle be passed straight into a filter, a {@code Trigger} or a command factory
 * without the receiving domain importing anything from {@code org.pumpkinlib.tuning}.
 *
 * <p>Deliberately the smaller surface next to {@link TunableDouble}: there is no {@code unit()}, no
 * slider range and no {@code ifChanged} fan-in, because the callers are pit switches rather than
 * swept quantities.
 *
 * <p>Same lifecycle as {@link TunableDouble} in every respect that matters: one shared
 * {@code NetworkTableListenerPoller}, one {@code readQueue()} per loop (D11a), one publisher created
 * once at construction with {@code setDefault(defaultValue)}, and a cached primitive on the hot path.
 * When tuning is disabled — the default under FMS (D11) — {@link #get()} returns the compile-time
 * default forever with zero NT traffic, so <b>a kill switch left flipped in the pit cannot follow the
 * robot onto the field</b>.
 */
public final class TunableBoolean implements BooleanSupplier {

  private final String m_fullKey;
  private final String m_namespace;
  private final String m_key;
  private final boolean m_default;
  private final TunableTransport.FlagHandle m_handle;

  private final List<BooleanConsumer> m_listeners = new ArrayList<>();
  private final Map<Integer, Boolean> m_lastSeen = new LinkedHashMap<>();

  /** Package-private: always created through {@code TuningRegistry.tunableFlag(...)}. */
  TunableBoolean(
      String namespace,
      String key,
      String fullKey,
      boolean defaultValue,
      TunableTransport.FlagHandle handle) {
    m_namespace = namespace;
    m_key = key;
    m_fullKey = fullKey;
    m_default = defaultValue;
    m_handle = handle;
    handle.onChanged(this::fire);
  }

  /**
   * The current value.
   *
   * <p>When tuning is disabled this returns the cached compile-time default with no NT read.
   *
   * @return the live value, or the default when tuning is off
   */
  public boolean get() {
    return m_handle.get();
  }

  @Override
  public boolean getAsBoolean() {
    return get();
  }

  /**
   * The compile-time default, regardless of tuning mode.
   *
   * @return the default passed at construction
   */
  public boolean defaultValue() {
    return m_default;
  }

  /**
   * Per-caller change detection, identical in contract to {@link TunableDouble#hasChanged(int)}.
   *
   * @param id a stable per-caller id
   * @return true the first time it is called, and thereafter whenever the value differs from what
   *     this id last saw
   */
  public boolean hasChanged(int id) {
    Boolean previous = m_lastSeen.put(id, get());
    return previous == null || previous != get();
  }

  /**
   * Register a callback fired from the priority-30 hook whenever the value changes.
   *
   * @param action what to do with the new value; must not throw
   * @return this, for chaining
   */
  public TunableBoolean onChange(BooleanConsumer action) {
    if (action != null) {
      m_listeners.add(action);
    }
    return this;
  }

  /**
   * Overwrite the live value from code, publishing it to NetworkTables.
   *
   * @param value the new value
   */
  public void set(boolean value) {
    m_handle.set(value);
  }

  /**
   * The full NetworkTables key.
   *
   * @return e.g. {@code "/Tuning/Vision/camera0Enabled"}
   */
  public String fullKey() {
    return m_fullKey;
  }

  /**
   * The namespace half of the key.
   *
   * @return e.g. {@code "Vision"}
   */
  public String namespace() {
    return m_namespace;
  }

  /**
   * The leaf half of the key.
   *
   * @return e.g. {@code "camera0Enabled"}
   */
  public String key() {
    return m_key;
  }

  @Override
  public String toString() {
    return m_fullKey + " = " + get();
  }

  private void fire(boolean value) {
    for (int i = 0; i < m_listeners.size(); i++) {
      m_listeners.get(i).accept(value);
    }
  }
}

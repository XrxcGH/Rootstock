package org.pumpkinlib.tuning;

import edu.wpi.first.util.function.BooleanConsumer;
import java.util.function.DoubleConsumer;

/**
 * How a tunable's value crosses from the dashboard into robot code, replay-safely.
 *
 * <p><b>Why this seam exists.</b> A tunable read from NetworkTables is an <i>input</i> to robot code.
 * AdvantageKit replays inputs from the log; anything read outside a logged-input channel is not
 * replayed, so a replay run would silently use the deploy-time default while the real run used a
 * dashboard value. Outputs diverge, with no error. Confining every wire read to one implementation of
 * this interface is what makes that impossible rather than unlikely.
 *
 * <p>Exactly one implementation ships — {@link AdvantageKitTunableTransport}, referenced at compile
 * time with no reflection, no probe and no fallback. A second, {@code WpilibTunableTransport}, is
 * reserved for WPILib's first-party {@code Tunable} API if and when it merges; it is not built today
 * and must not be coded against.
 */
public interface TunableTransport {

  /**
   * Register a double topic and its compile-time default.
   *
   * <p>Called once per {@link TunableDouble}, at construction. The transport owns the storage; the
   * handle is the only way to read or write it.
   *
   * @param fullKey the full NT key, e.g. {@code "/Tuning/Elevator/kP"}
   * @param defaultValue the value compiled into the jar, published with {@code setDefault}
   * @return the handle
   */
  Handle register(String fullKey, double defaultValue);

  /**
   * Register a boolean topic and its compile-time default.
   *
   * <p>Flags share this transport's single queue drain — they do not get a second one. Present as a
   * separate method rather than an overload because the wire type and the handle type both differ; a
   * boolean stored as a {@code 0.0}/{@code 1.0} double is a boolean a replay can silently widen, and
   * AdvantageScope would render a pit kill switch as a number.
   *
   * @param fullKey the full NT key, e.g. {@code "/Tuning/Vision/camera0Enabled"}
   * @param defaultValue the value compiled into the jar
   * @return the handle
   */
  FlagHandle registerFlag(String fullKey, boolean defaultValue);

  /** One registered double topic, as seen by the {@link TunableDouble} that owns it. */
  interface Handle {

    /**
     * The current value.
     *
     * @return the live value when tuning is enabled, the compile-time default otherwise
     */
    double get();

    /**
     * Overwrite the value from code, publishing it to NetworkTables.
     *
     * @param value the new value
     */
    void set(double value);

    /**
     * Install the listener fired from {@link TunableTransport#drain()} when this value changes.
     *
     * <p>Push rather than poll so the per-loop cost is proportional to the number of values that
     * actually changed — normally zero — rather than to the number of tunables in the robot.
     *
     * @param listener called with the new value; at most one listener per handle
     */
    default void onChanged(DoubleConsumer listener) {
      // A transport that does not dispatch changes is still a legal transport; the tunable then
      // falls back to reading get() every loop, which is always correct and never stale.
    }
  }

  /** One registered boolean topic, as seen by the {@link TunableBoolean} that owns it. */
  interface FlagHandle {

    /**
     * The current value.
     *
     * @return the live value when tuning is enabled, the compile-time default otherwise
     */
    boolean get();

    /**
     * Overwrite the value from code, publishing it to NetworkTables.
     *
     * @param value the new value
     */
    void set(boolean value);

    /**
     * Install the listener fired from {@link TunableTransport#drain()} when this value changes.
     *
     * @param listener called with the new value; at most one listener per handle
     */
    default void onChanged(BooleanConsumer listener) {
      // See Handle.onChanged.
    }
  }

  /**
   * Drain the wire once.
   *
   * <p>Called from the priority-30 {@code LifecycleHook}, exactly once per loop. An implementation
   * must perform at most one queue read regardless of how many tunables are registered, and must not
   * read the wire at all in replay.
   */
  void drain();

  /**
   * Human-readable name shown in the boot log and the UI.
   *
   * @return e.g. {@code "AdvantageKit (one poller, one processInputs)"}
   */
  String describe();
}

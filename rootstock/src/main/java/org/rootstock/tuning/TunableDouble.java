package org.rootstock.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * An NT4-backed double with a compile-time default.
 *
 * <p>Wire format is a plain double topic at {@code /Tuning/<namespace>/<key>}, which is exactly what
 * AdvantageScope's tuning mode edits and what Elastic's Text Display and Number Slider bind to. There
 * are no structs, no protobuf and no logging-framework-specific types on the wire, so a team that
 * changes dashboards changes nothing here.
 *
 * <p>Three ways to consume a change, in increasing order of preference:
 *
 * <ol>
 *   <li>{@link #get()} every loop — simplest, always correct, costs a cached field read;
 *   <li>{@link #hasChanged(int)} with {@code hashCode()} as the id — the 6328 idiom, kept for source
 *       compatibility with existing team code;
 *   <li>{@link #onChange(DoubleConsumer)} — the DogLog idiom, and the one Rootstock uses internally,
 *       because it removes the change-detection {@code if} from user code entirely.
 * </ol>
 *
 * <p><b>What "disabled" means here.</b> Live tuning is off by default whenever the FMS is attached
 * (D11). When it is off, {@link #get()} returns the compile-time default forever, with zero NT reads
 * and zero allocation. A slider left in a strange place in the pit therefore cannot follow the robot
 * onto the field.
 *
 * <p><b>The honest latency number is 100–150 ms, not "the next loop"</b> (D11a(a)). AdvantageScope
 * and Elastic are NT <i>clients</i>; the robot is the server; a client publisher's default update
 * period is 100 ms unless it sets {@code PubSubOption.periodic} or explicitly flushes. Intermediate
 * slider positions are coalesced away by the client, not by us.
 */
public final class TunableDouble implements DoubleSupplier {

  private final String m_fullKey;
  private final String m_namespace;
  private final String m_key;
  private final String m_unit;
  private final double m_default;
  private final double m_sliderMin;
  private final double m_sliderMax;
  private final boolean m_hasRange;
  private final TunableTransport.Handle m_handle;

  private final List<DoubleConsumer> m_listeners = new ArrayList<>();
  private final Map<Integer, Double> m_lastSeen = new LinkedHashMap<>();

  /**
   * Package-private: a tunable is always created through {@code TuningRegistry.tunable(...)}, which
   * owns the one transport and the one namespace allowlist.
   */
  TunableDouble(
      String namespace,
      String key,
      String fullKey,
      double defaultValue,
      String unit,
      double sliderMin,
      double sliderMax,
      boolean hasRange,
      TunableTransport.Handle handle) {
    m_namespace = namespace;
    m_key = key;
    m_fullKey = fullKey;
    m_default = defaultValue;
    m_unit = unit;
    m_sliderMin = sliderMin;
    m_sliderMax = sliderMax;
    m_hasRange = hasRange;
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
  public double get() {
    return m_handle.get();
  }

  @Override
  public double getAsDouble() {
    return get();
  }

  /**
   * The compile-time default, regardless of tuning mode.
   *
   * <p>This is the number that is in the jar, which is the number a redeploy restores. It is what
   * {@code ValueExporter} diffs against when it decides whether a value is worth writing back.
   *
   * @return the default passed at construction
   */
  public double defaultValue() {
    return m_default;
  }

  /**
   * Per-caller change detection.
   *
   * <p>Pass a stable id; {@code hashCode()} of the calling object is the recommended value, which is
   * what lets two subsystems watch one tunable independently without either of them consuming the
   * other's edge.
   *
   * @param id a stable per-caller id
   * @return true the first time it is called, and thereafter whenever the value differs from what
   *     this id last saw
   */
  public boolean hasChanged(int id) {
    Double previous = m_lastSeen.put(id, get());
    return previous == null || previous != get();
  }

  /**
   * Register a callback fired from the priority-30 hook whenever the value changes.
   *
   * <p>The callback runs before any subsystem {@code periodic()}, so a mechanism that rebuilds a
   * profile from this value sees the new number on the same loop it arrived.
   *
   * @param action what to do with the new value; must not throw
   * @return this, for chaining
   */
  public TunableDouble onChange(DoubleConsumer action) {
    if (action != null) {
      m_listeners.add(action);
    }
    return this;
  }

  /**
   * Fires once when ANY of {@code others} changes, handing back all values in declaration order.
   *
   * <p>The fan-in exists because gains are tuned in groups: a profile rebuilt from {@code
   * maxVelocity} and {@code maxAcceleration} must be rebuilt once when either moves, not twice when
   * both do.
   *
   * @param id a stable per-caller id, as for {@link #hasChanged(int)}
   * @param action receives every value in the order the tunables were passed
   * @param others the tunables to watch; an empty array does nothing
   */
  public static void ifChanged(int id, Consumer<double[]> action, TunableDouble... others) {
    if (action == null || others == null || others.length == 0) {
      return;
    }
    boolean changed = false;
    for (TunableDouble other : others) {
      // Every tunable must be asked, not just the first that answers true: hasChanged() consumes
      // the edge, and a short-circuit would leave the others reporting stale changes forever.
      changed |= other.hasChanged(id);
    }
    if (!changed) {
      return;
    }
    double[] values = new double[others.length];
    for (int i = 0; i < others.length; i++) {
      values[i] = others[i].get();
    }
    action.accept(values);
  }

  /**
   * Overwrite the live value from code, publishing it to NetworkTables.
   *
   * <p>The wizard does this when a step is accepted, so the dashboard slider snaps to the fitted
   * number instead of disagreeing with the robot.
   *
   * @param value the new value
   */
  public void set(double value) {
    m_handle.set(value);
  }

  /**
   * The full NetworkTables key.
   *
   * @return e.g. {@code "/Tuning/Elevator/kP"}
   */
  public String fullKey() {
    return m_fullKey;
  }

  /**
   * The namespace half of the key.
   *
   * @return e.g. {@code "Elevator"} or {@code "Vision"}
   */
  public String namespace() {
    return m_namespace;
  }

  /**
   * The leaf half of the key.
   *
   * @return e.g. {@code "kP"} or {@code "maxTagDistance"}
   */
  public String key() {
    return m_key;
  }

  /**
   * The unit this value is expressed in.
   *
   * <p>Carried so AdvantageScope can render it and so the layout generator can label the widget. A
   * number without a unit is the bug this library exists to delete.
   *
   * @return e.g. {@code "V/m"} or {@code "m"}; blank when genuinely dimensionless
   */
  public String unit() {
    return m_unit;
  }

  /**
   * Whether a slider range was declared.
   *
   * @return true when {@link #sliderMin()} and {@link #sliderMax()} are meaningful
   */
  public boolean hasSliderRange() {
    return m_hasRange;
  }

  /**
   * The low end of the declared slider range.
   *
   * @return the minimum, or {@link Double#NaN} when no range was declared
   */
  public double sliderMin() {
    return m_hasRange ? m_sliderMin : Double.NaN;
  }

  /**
   * The high end of the declared slider range.
   *
   * @return the maximum, or {@link Double#NaN} when no range was declared
   */
  public double sliderMax() {
    return m_hasRange ? m_sliderMax : Double.NaN;
  }

  @Override
  public String toString() {
    return m_fullKey + " = " + get() + (m_unit.isBlank() ? "" : " " + m_unit);
  }

  /** Fired by the transport from the priority-30 hook. Never allocates when nobody is listening. */
  private void fire(double value) {
    for (int i = 0; i < m_listeners.size(); i++) {
      m_listeners.get(i).accept(value);
    }
  }
}

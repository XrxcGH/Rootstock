package org.rootstock.core.identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Per-robot constant overrides, expressed as functions the compiler checks.
 *
 * <p>Rootstock config objects are immutable records with {@code withX()} copy methods
 * ({@code design/01}'s convention), so a per-robot override is just a function from config to
 * config. That is the whole mechanism: <b>no JSON, no reflection, no string keys, no second source
 * of truth.</b> If you rename {@code withMaxHeight} the overlay stops compiling, which is the
 * property a JSON config file can never have and the reason this design does not have one.
 *
 * <pre>{@code
 * public static final ElevatorConfig kElevator =
 *     Overlay.of(ElevatorConfig.base()
 *                   .withGearing(12.0)
 *                   .withMaxHeight(Inches.of(58))
 *                   .withStatorLimit(Amps.of(60)))
 *         .when(RobotId.PRACTICE, c -> c.withMaxHeight(Inches.of(54)).withStatorLimit(Amps.of(40)))
 *         .when(RobotId.PROTO,    c -> c.withGearing(9.0))
 *         .resolve();
 * }</pre>
 *
 * <h2>Precedence order</h2>
 *
 * Layers are applied to the base value in this order; a later layer sees the output of the earlier
 * ones, so <b>later layers win on any field two layers both touch</b>:
 *
 * <ol>
 *   <li>the <b>base</b> value passed to {@link #of(Object)};
 *   <li>every {@link #whenAny(java.util.Set, UnaryOperator)} group whose set contains the resolved
 *       {@link RobotId}, in declaration order — "everything that is not the comp bot gets a 40 A
 *       limit";
 *   <li>every {@link #when(RobotId, UnaryOperator)} whose id equals the resolved id, in declaration
 *       order — the exact-robot delta, which therefore beats the group;
 *   <li>every {@link #always(UnaryOperator)}, in declaration order — a clamp or a safety
 *       normalisation that must survive every override.
 * </ol>
 *
 * <p>The order is <i>specific beats general, and unconditional beats both</i>. Group rules exist to
 * say "all the non-comp robots", exact rules exist to carve one robot out of that group, and
 * {@code always} exists for the rule you never want a per-robot edit to be able to defeat.
 *
 * <p><b>Overlays never fail closed.</b> A robot with no registered override for its identity gets
 * the base value. That is deliberate: the failure mode of throwing here is a robot that will not
 * boot because somebody added a {@code RobotId} constant and forgot one config, which is the exact
 * class of boot failure {@code design/01} §5.6 exists to abolish. If you want the strict behaviour —
 * "every robot must declare this value" — use {@link RobotIdentity#pick(java.util.Map)}, which does
 * name the missing robot and the caller.
 *
 * <p>Instances are immutable; every builder-style method returns a new {@code Overlay}, so a partly
 * built overlay can be shared and specialised.
 *
 * @param <T> the config type being overlaid, normally an immutable record with {@code withX()}
 *     copy methods
 */
public final class Overlay<T> {

  /** One registered layer. Kind decides which precedence band it lands in. */
  private record Layer<T>(Kind kind, Set<RobotId> ids, UnaryOperator<T> fn, String label) {}

  private enum Kind {
    GROUP,
    EXACT,
    ALWAYS
  }

  private final T m_base;
  private final List<Layer<T>> m_layers;

  private Overlay(T base, List<Layer<T>> layers) {
    m_base = base;
    m_layers = layers;
  }

  /**
   * Starts an overlay from the value every robot shares.
   *
   * @param base the compile-time default; must not be null, because a null config is the one thing
   *     no {@code withX()} chain can recover from
   * @param <T> the config type
   * @return an overlay with no layers, which resolves to {@code base} on every robot
   * @throws IllegalArgumentException if {@code base} is null
   */
  public static <T> Overlay<T> of(T base) {
    if (base == null) {
      throw new IllegalArgumentException(
          "Overlay.of(base): base was null. The base value is the config every robot shares; "
              + "pass the fully built default, for example Overlay.of(ElevatorConfig.base()"
              + ".withGearing(12.0)).");
    }
    return new Overlay<>(base, List.of());
  }

  /**
   * Registers an override applied only on one robot.
   *
   * <p>Precedence band 3: exact rules run after every group rule, so an exact rule always beats the
   * group it belongs to.
   *
   * @param id the robot this override applies to
   * @param override a function from the value so far to the value for this robot; must return a
   *     non-null value
   * @return a new overlay with this layer appended
   * @throws IllegalArgumentException if {@code id} or {@code override} is null
   */
  public Overlay<T> when(RobotId id, UnaryOperator<T> override) {
    requireArgs(id, override, "when");
    return added(new Layer<>(Kind.EXACT, EnumSet.of(id), override, "when(" + id + ")"));
  }

  /**
   * Registers an override applied on any of several robots.
   *
   * <p>Precedence band 2: group rules run before exact rules, so {@code whenAny(PRACTICE, PROTO,
   * c -> c.withStatorLimit(Amps.of(40)))} followed by {@code when(PROTO, c -> c.withStatorLimit(
   * Amps.of(25)))} gives the proto 25 A and the practice bot 40 A.
   *
   * @param ids the robots this override applies to; must be non-empty
   * @param override a function from the value so far to the value for these robots
   * @return a new overlay with this layer appended
   * @throws IllegalArgumentException if {@code ids} is null or empty, or {@code override} is null
   */
  public Overlay<T> whenAny(Set<RobotId> ids, UnaryOperator<T> override) {
    if (ids == null || ids.isEmpty()) {
      throw new IllegalArgumentException(
          "Overlay.whenAny(ids, override): ids was "
              + (ids == null ? "null" : "empty")
              + ". Name at least one RobotId, or use always(...) if the override is unconditional.");
    }
    requireArgs(RobotId.COMP, override, "whenAny");
    Set<RobotId> copy = new LinkedHashSet<>(ids);
    return added(new Layer<>(Kind.GROUP, copy, override, "whenAny" + copy));
  }

  /**
   * Registers an override applied on any of several robots.
   *
   * @param override a function from the value so far to the value for these robots
   * @param ids the robots this override applies to; must be non-empty
   * @return a new overlay with this layer appended
   * @throws IllegalArgumentException if {@code ids} is empty or {@code override} is null
   */
  public Overlay<T> whenAny(UnaryOperator<T> override, RobotId... ids) {
    return whenAny(new LinkedHashSet<>(Arrays.asList(ids)), override);
  }

  /**
   * Registers an override applied on every robot, after all per-robot layers.
   *
   * <p>Precedence band 4, the last one. This is where a clamp belongs: "whatever the per-robot
   * overrides did, the stator limit is never above 60 A" is a rule you do not want a future
   * per-robot edit to be able to defeat by accident.
   *
   * @param override a function applied last, on every robot
   * @return a new overlay with this layer appended
   * @throws IllegalArgumentException if {@code override} is null
   */
  public Overlay<T> always(UnaryOperator<T> override) {
    requireArgs(RobotId.COMP, override, "always");
    return added(new Layer<>(Kind.ALWAYS, EnumSet.allOf(RobotId.class), override, "always"));
  }

  /**
   * Resolves the value for the robot this code is running on.
   *
   * @return the overlaid value
   * @throws IllegalStateException if {@link RobotIdentity#configure()} was never completed — the
   *     one case where failing loudly is right, because silently returning the base value would
   *     hide a missing {@code RobotIds.register()} call behind constants that look plausible
   */
  public T resolve() {
    return resolveFor(RobotIdentity.current());
  }

  /**
   * Resolves the value for a specific robot, without consulting {@link RobotIdentity}.
   *
   * <p>This is the testable entry point: {@code kElevator.resolveFor(RobotId.PRACTICE)} asserts the
   * practice bot's max height in a plain JUnit test with no HAL and no identity configured.
   *
   * @param id the robot to resolve for
   * @return the overlaid value
   * @throws IllegalArgumentException if {@code id} is null
   * @throws IllegalStateException if any registered override returned null
   */
  public T resolveFor(RobotId id) {
    if (id == null) {
      throw new IllegalArgumentException("Overlay.resolveFor(id): id was null.");
    }
    T value = m_base;
    for (Kind band : new Kind[] {Kind.GROUP, Kind.EXACT, Kind.ALWAYS}) {
      for (Layer<T> layer : m_layers) {
        if (layer.kind() != band || !layer.ids().contains(id)) {
          continue;
        }
        T next = layer.fn().apply(value);
        if (next == null) {
          throw new IllegalStateException(
              "Overlay."
                  + layer.label()
                  + " returned null for "
                  + id
                  + ". An overlay must return a config, not null: it is a withX() copy chain on "
                  + "the value handed in, for example c -> c.withMaxHeight(Inches.of(54)).");
        }
        value = next;
      }
    }
    return value;
  }

  /**
   * The value before any override is applied.
   *
   * @return the base value passed to {@link #of(Object)}
   */
  public T base() {
    return m_base;
  }

  /**
   * How many override layers are registered.
   *
   * @return the layer count, across all precedence bands
   */
  public int layerCount() {
    return m_layers.size();
  }

  /**
   * Whether any layer would run for the given robot.
   *
   * @param id the robot to check
   * @return false if this robot gets the base value unchanged
   */
  public boolean hasOverrideFor(RobotId id) {
    return m_layers.stream().anyMatch(l -> l.ids().contains(Objects.requireNonNull(id)));
  }

  /**
   * A human summary of the layers and the order they run in, for the boot dump.
   *
   * @return a multi-line description naming each layer in application order
   */
  public String describe() {
    StringBuilder sb = new StringBuilder("Overlay of ").append(m_base.getClass().getSimpleName());
    sb.append(" with ").append(m_layers.size()).append(" layer(s), applied in this order:");
    int n = 0;
    for (Kind band : new Kind[] {Kind.GROUP, Kind.EXACT, Kind.ALWAYS}) {
      for (Layer<T> layer : m_layers) {
        if (layer.kind() == band) {
          sb.append("\n  ").append(++n).append(". ").append(layer.label());
        }
      }
    }
    if (n == 0) {
      sb.append("\n  (none; every robot gets the base value)");
    }
    return sb.toString();
  }

  private Overlay<T> added(Layer<T> layer) {
    List<Layer<T>> next = new ArrayList<>(m_layers);
    next.add(layer);
    return new Overlay<>(m_base, List.copyOf(next));
  }

  private static <T> void requireArgs(RobotId id, UnaryOperator<T> override, String method) {
    if (id == null) {
      throw new IllegalArgumentException("Overlay." + method + "(...): the RobotId was null.");
    }
    if (override == null) {
      throw new IllegalArgumentException(
          "Overlay."
              + method
              + "(...): the override function was null. Pass a withX() copy chain, for example "
              + "c -> c.withGearing(9.0).");
    }
  }
}

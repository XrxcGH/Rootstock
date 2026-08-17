package org.pumpkinlib.superstructure;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.pumpkinlib.config.Setpoint;

/**
 * What one axis is being told to do, as a value.
 *
 * <p>Seven shapes, sealed, so the superstructure's dispatch is exhaustive and a new goal kind cannot
 * be added without every site that consumes one being recompiled against it. A goal is inert data —
 * building one commands nothing — which is what lets a {@code SuperState} enum hold a whole robot
 * configuration in a {@code static final} field and lets {@link SuperstructureReport} analyse the
 * state machine at construction with no hardware present.
 *
 * <h2>Which one to reach for</h2>
 *
 * <ul>
 *   <li>{@link Of} — <b>the documented default.</b> A {@link Setpoint} handle held as a
 *       {@code public static final} field, so the compiler checks it. {@code design/01} §8.9 makes
 *       this the norm and everything else the escape hatch, because the string-keyed namespace is
 *       the single most-typed identifier in the API and a typo in it used to surface at the moment a
 *       driver pressed a button mid-match.
 *   <li>{@link Named} — the string escape hatch. Validated at construction ({@code design/01} §8.9
 *       layer 2), with a "did you mean" over the declared names, so it can never fail silently.
 *   <li>{@link Position} / {@link Velocity} — a raw number in the axis's user units, for a value
 *       computed at runtime rather than declared.
 *   <li>{@link Percent} — open-loop duty cycle. The roller verb.
 *   <li>{@link Neutral} — no output. The right default for a flywheel or an intake.
 *   <li>{@link Hold} — assert nothing, and opt this axis out of the §8.5 default inversion for as
 *       long as the goal is in force.
 * </ul>
 *
 * <p><b>Units are user units, always</b> — metres for a linear axis, degrees for a rotary one,
 * matching {@code Setpoint.valueUser()} and everything the mechanism publishes. Radians appear in
 * exactly one place in this library and it is not here.
 */
public sealed interface AxisGoal {

  /** The shared {@link Neutral}, so a default declaration does not allocate one per loop. */
  AxisGoal kNeutral = new Neutral();

  /** The shared {@link Hold}, for the same reason. */
  AxisGoal kHold = new Hold();

  /**
   * A raw position, in the axis's user units.
   *
   * @param userUnits metres for a linear axis, degrees for a rotary one
   */
  record Position(double userUnits) implements AxisGoal {}

  /**
   * A typed, compiler-checked goal. <b>THE documented default.</b>
   *
   * @param setpoint the handle, normally a {@code public static final} field on the team's config
   *     class
   */
  record Of(Setpoint setpoint) implements AxisGoal {}

  /**
   * The string escape hatch. Validated at construction ({@code design/01} §8.9); can never fail
   * silently.
   *
   * @param setpoint the setpoint name, compared exactly — no trimming, because {@code "L4 "} is not
   *     {@code "L4"} and silently trimming is what makes the typo invisible
   */
  record Named(String setpoint) implements AxisGoal {}

  /**
   * A velocity, in the axis's user units per second.
   *
   * @param userPerSecond metres per second or degrees per second
   */
  record Velocity(double userPerSecond) implements AxisGoal {}

  /**
   * An open-loop duty cycle.
   *
   * @param dutyCycle in {@code [-1, 1]}
   */
  record Percent(double dutyCycle) implements AxisGoal {}

  /** No output — coast or brake per the mechanism's configured neutral mode. */
  record Neutral() implements AxisGoal {}

  /** Assert nothing; leave the axis doing whatever it is doing. */
  record Hold() implements AxisGoal {}

  /**
   * A typed goal from a compiler-checked handle.
   *
   * @param s the setpoint
   * @return the goal
   */
  static AxisGoal of(Setpoint s) {
    return new Of(s);
  }

  /**
   * A goal by setpoint name. Prefer {@link #of(Setpoint)}; this one is the escape hatch.
   *
   * @param s the setpoint name
   * @return the goal
   */
  static AxisGoal named(String s) {
    return new Named(s);
  }

  /**
   * A raw position goal in user units.
   *
   * @param userUnits metres or degrees
   * @return the goal
   */
  static AxisGoal position(double userUnits) {
    return new Position(userUnits);
  }

  /**
   * A velocity goal in user units per second.
   *
   * @param userPerSecond metres per second or degrees per second
   * @return the goal
   */
  static AxisGoal velocity(double userPerSecond) {
    return new Velocity(userPerSecond);
  }

  /**
   * An open-loop duty-cycle goal.
   *
   * @param d the duty cycle, in {@code [-1, 1]}
   * @return the goal
   */
  static AxisGoal percent(double d) {
    return new Percent(d);
  }

  /**
   * The shared neutral goal.
   *
   * @return {@link #kNeutral}
   */
  static AxisGoal neutral() {
    return kNeutral;
  }

  /**
   * The shared hold goal.
   *
   * @return {@link #kHold}
   */
  static AxisGoal hold() {
    return kHold;
  }

  /**
   * Commands this goal onto an axis.
   *
   * <p>Called once per registered axis per loop for the declared default and again for the active
   * state's override, so it allocates nothing and never throws: an unresolvable name commands
   * nothing and the receiver raises the alert, which is {@code design/01} §8.9 layer 3 — the runtime
   * path is incapable of failing silently, and it is equally incapable of taking the scheduler down.
   *
   * @param receiver the axis
   */
  default void applyTo(GoalReceiver receiver) {
    if (receiver == null) {
      return;
    }
    if (this instanceof Position p) {
      receiver.applyPosition(p.userUnits());
      return;
    }
    if (this instanceof Of o) {
      Setpoint s = o.setpoint();
      if (s == null || !s.isResolved()) {
        // An unresolved handle is a config error that §8.9 layer 2 already collected into
        // SAFE_MODE at construction. Holding is the only honest thing left to do with it.
        receiver.applyHold();
        return;
      }
      receiver.applyPosition(s.valueUser());
      return;
    }
    if (this instanceof Named n) {
      List<Setpoint> declared = receiver.declaredSetpoints();
      for (int i = 0; i < declared.size(); i++) {
        Setpoint candidate = declared.get(i);
        if (candidate != null && candidate.isResolved() && candidate.name().equals(n.setpoint())) {
          receiver.applyPosition(candidate.valueUser());
          return;
        }
      }
      receiver.applyHold();
      return;
    }
    if (this instanceof Velocity v) {
      receiver.applyVelocity(v.userPerSecond());
      return;
    }
    if (this instanceof Percent p) {
      receiver.applyPercent(p.dutyCycle());
      return;
    }
    if (this instanceof Neutral) {
      receiver.applyNeutral();
      return;
    }
    receiver.applyHold();
  }

  /**
   * The configuration this goal asks the axis to be at, when it asks for one at all.
   *
   * <p>This is what the {@link SafetyModel} tests and what {@link TransitionPlanner} plans against.
   * A {@link Percent}, {@link Velocity}, {@link Neutral} or {@link Hold} goal names no position, so
   * a superstructure whose safety axes carry those goals falls back to the measured value — which is
   * correct, because those goals genuinely do not say where the axis will end up.
   *
   * @param receiver the axis, needed only to resolve a {@link Named} against its declared setpoints
   * @return the target position in user units, or empty
   */
  default OptionalDouble targetUser(GoalReceiver receiver) {
    if (this instanceof Position p) {
      return OptionalDouble.of(p.userUnits());
    }
    if (this instanceof Of o) {
      Setpoint s = o.setpoint();
      return s != null && s.isResolved() ? OptionalDouble.of(s.valueUser()) : OptionalDouble.empty();
    }
    if (this instanceof Named n && receiver != null) {
      List<Setpoint> declared = receiver.declaredSetpoints();
      for (int i = 0; i < declared.size(); i++) {
        Setpoint candidate = declared.get(i);
        if (candidate != null && candidate.isResolved() && candidate.name().equals(n.setpoint())) {
          return OptionalDouble.of(candidate.valueUser());
        }
      }
    }
    return OptionalDouble.empty();
  }

  /**
   * Whether this goal names a position at all.
   *
   * @return true for {@link Position}, {@link Of} and {@link Named}
   */
  default boolean isPositional() {
    return this instanceof Position || this instanceof Of || this instanceof Named;
  }

  /**
   * The setpoint name this goal references, for the {@code design/01} §8.9 construction-time check.
   *
   * <p>{@link Of} answers too: a handle built from a name that did not exist is an <i>unresolved</i>
   * {@code Setpoint} rather than a throw, so the typed form has exactly the same failure to report —
   * it just reports it against a name the compiler already saw.
   *
   * @return the referenced name, or an empty string when this goal references none
   */
  default String setpointName() {
    if (this instanceof Named n) {
      return n.setpoint() == null ? "" : n.setpoint();
    }
    if (this instanceof Of o) {
      return o.setpoint() == null ? "" : o.setpoint().name();
    }
    return "";
  }

  /**
   * Whether this goal references a setpoint name that is already known to be broken.
   *
   * @return true for a {@link Of} carrying an unresolved handle
   */
  default boolean referencesUnresolvedHandle() {
    return this instanceof Of o && (o.setpoint() == null || !o.setpoint().isResolved());
  }

  /**
   * One line, as {@link SuperstructureReport} and every alert print it.
   *
   * @return the description
   */
  default String describe() {
    if (this instanceof Position p) {
      return String.format(Locale.ROOT, "position %.3f", p.userUnits());
    }
    if (this instanceof Of o) {
      Setpoint s = o.setpoint();
      return s == null ? "setpoint (null)" : "setpoint \"" + s.name() + "\"";
    }
    if (this instanceof Named n) {
      return "setpoint \"" + n.setpoint() + "\" (by name)";
    }
    if (this instanceof Velocity v) {
      return String.format(Locale.ROOT, "velocity %.3f/s", v.userPerSecond());
    }
    if (this instanceof Percent p) {
      return String.format(Locale.ROOT, "percent %.0f%%", p.dutyCycle() * 100.0);
    }
    if (this instanceof Neutral) {
      return "neutral";
    }
    return "hold";
  }
}

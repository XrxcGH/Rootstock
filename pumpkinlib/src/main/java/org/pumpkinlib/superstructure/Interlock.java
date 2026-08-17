package org.pumpkinlib.superstructure;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * A hard rule about which transitions are permitted. Evaluated <b>before</b> planning.
 *
 * <p>An interlock is the declarative form of the {@code if (clawHoldingPiece) return;} that
 * otherwise appears at the top of a request handler, gets copied into the second request handler,
 * and then does not get copied into the third. Declaring it once as a value means the rule is
 * <i>inspectable</i>: {@link SuperstructureReport} can list every interlock, the dashboard can name
 * the one that is currently blocking, and a CSA can read the state machine without reading Java.
 *
 * <pre>{@code
 * List.of(
 *   new Interlock<>("no-climb-with-piece",
 *       from -> true, to -> to == CLIMB_DEPLOY || to == CLIMB_FINAL,
 *       () -> !coralClaw.holding().getAsBoolean(),
 *       "the coral claw is holding a game piece; eject before climbing"),
 *
 *   new Interlock<>("no-score-until-homed",
 *       from -> true, to -> to.name().startsWith("SCORE"),
 *       () -> elevator.isHomed() && arm.isHomed(),
 *       "the elevator or arm has not homed yet; run the home routine"))
 * }</pre>
 *
 * <p><b>A blocked request is visible, not silent.</b> The request is retained, so releasing the
 * interlock completes the move without the driver pressing anything again; an alert names the
 * interlock and its {@code explanation}; and {@code /Pumpkin/Superstructure/Blocked} carries the
 * string. Silently dropping the request is the failure mode this shape exists to prevent — a driver
 * who presses a button and sees nothing happen presses it four more times.
 *
 * <p><b>It never throws.</b> Interlocks are declared in {@code static final} lists next to the
 * configs they guard, and a throw from that context is an {@code ExceptionInInitializerError} that
 * kills the robot with no message. Null predicates are normalised to "matches everything", a null
 * {@code permitted} to "always permitted", and a supplier that throws when it is evaluated is
 * treated as <b>not permitted</b> — a rule you cannot evaluate is a rule you have to assume is
 * blocking, because the alternative is moving a superstructure on the strength of a broken check.
 *
 * <p>Interlocks are also the documented answer for constraints that {@link SafetyModel}'s two-axis
 * rectangles cannot express — anything that depends on a sensor rather than on geometry.
 *
 * @param <S> the team's state enum
 * @param name a short, stable identifier, used in the alert and in the report
 * @param fromMatches which source states this rule applies to
 * @param toMatches which destination states this rule applies to
 * @param permitted whether the transition is allowed <i>right now</i>
 * @param explanation why it is blocked and what to do about it, in English, aimed at somebody
 *     holding a controller
 */
public record Interlock<S extends Enum<S>>(
    String name,
    Predicate<S> fromMatches,
    Predicate<S> toMatches,
    BooleanSupplier permitted,
    String explanation) {

  /** What an unnamed interlock is called, so a report line is never blank. */
  public static final String kUnnamed = "(unnamed interlock)";

  /**
   * Normalises nulls instead of throwing; see the class javadoc for why a throw here would be the
   * diagnostic becoming the outage.
   */
  public Interlock {
    name = name == null || name.isBlank() ? kUnnamed : name.trim();
    fromMatches = fromMatches == null ? s -> true : fromMatches;
    toMatches = toMatches == null ? s -> true : toMatches;
    permitted = permitted == null ? () -> true : permitted;
    explanation = explanation == null ? "" : explanation.strip();
  }

  /**
   * Whether this rule has anything to say about a given transition.
   *
   * @param from the source state
   * @param to the destination state
   * @return true when both predicates match
   */
  public boolean applies(S from, S to) {
    return matches(fromMatches, from) && matches(toMatches, to);
  }

  /**
   * Whether this rule is currently satisfied.
   *
   * <p>A supplier that throws counts as <b>not</b> satisfied: see the class javadoc.
   *
   * @return true when the condition holds
   */
  public boolean isPermitted() {
    try {
      return permitted.getAsBoolean();
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Whether this rule blocks a given transition right now.
   *
   * @param from the source state
   * @param to the destination state
   * @return true when the rule applies and its condition does not hold
   */
  public boolean blocks(S from, S to) {
    return applies(from, to) && !isPermitted();
  }

  /**
   * The sentence the alert and {@code /Pumpkin/Superstructure/Blocked} carry.
   *
   * @param from the source state
   * @param to the destination state
   * @return for example {@code "climb-lockout: STOW -> CLIMB_FINAL blocked because 'the coral claw
   *     is holding a game piece; eject before climbing'"}
   */
  public String describe(S from, S to) {
    return name
        + ": "
        + (from == null ? "?" : from.name())
        + " -> "
        + (to == null ? "?" : to.name())
        + " blocked because '"
        + explanation
        + "'";
  }

  /**
   * The rule on its own, for the boot dump and {@link SuperstructureReport}.
   *
   * @return for example {@code "climb-lockout (currently PERMITTED): the coral claw is holding a
   *     game piece; eject before climbing"}
   */
  public String describe() {
    return name + " (currently " + (isPermitted() ? "PERMITTED" : "BLOCKING") + "): " + explanation;
  }

  @Override
  public String toString() {
    return "Interlock[" + name + "]";
  }

  private static <S extends Enum<S>> boolean matches(Predicate<S> predicate, S state) {
    try {
      return predicate.test(state);
    } catch (Throwable t) {
      // A predicate that throws cannot be said to match, and it must not take the loop with it.
      return false;
    }
  }
}

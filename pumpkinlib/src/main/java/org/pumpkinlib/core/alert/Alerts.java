package org.pumpkinlib.core.alert;

import java.util.function.BooleanSupplier;

/**
 * THE alert facade. Every alert in every PumpkinLib domain is created here.
 *
 * <p>One facade, not five: the registry, the {@code /Pumpkin/Driver} mirror, the CI alert-budget
 * gate and {@code Health.expectAbsent} all depend on every alert in the process being reachable from
 * one list, and the only way to guarantee that is to make this the only way to build one.
 *
 * <p><strong>There is no two-argument overload of {@link #error} or {@link #warning}, and there
 * never will be.</strong> The {@link MatchImpact} argument is the entire mechanism: the author of an
 * alert is the only person who knows whether it means "do not take the field", and the compiler is
 * what makes them say so. An overload that guessed a default would delete the feature.
 *
 * <p>{@link #info} takes two arguments, and that narrows the rule rather than weakening it:
 * {@code INFO} is {@code PIT_ONLY} by construction, so a {@code MatchImpact} parameter there would be
 * a parameter with exactly one legal value.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * private final PumpkinAlert m_disconnected =
 *     Alerts.error("Arm", "leader TalonFX 21 not responding on CAN", MatchImpact.BLOCKS_MATCH)
 *           .debounce(Seconds.of(0.5));
 * // ... in periodic:
 * m_disconnected.set(!m_leader.isConnected());
 * }</pre>
 */
public final class Alerts {

  private Alerts() {}

  /**
   * Create an {@code ERROR} alert.
   *
   * @param group the dashboard group; use the mechanism or subsystem name, e.g. "Arm"
   * @param text what is wrong. Name the thing, the value, the expected range and the fix — a student
   *     reads this at 11pm
   * @param impact whether this means the robot should not take the field. Required; see {@link
   *     MatchImpact}
   * @return the registered handle, for {@code set(...)} and further configuration
   * @throws IllegalArgumentException if {@code group} or {@code text} is blank, or {@code impact} is
   *     null
   */
  public static PumpkinAlert error(String group, String text, MatchImpact impact) {
    return of(group, text, Severity.ERROR, impact);
  }

  /**
   * Create a {@code WARNING} alert.
   *
   * @param group the dashboard group; use the mechanism or subsystem name
   * @param text what is wrong. Name the thing, the value, the expected range and the fix
   * @param impact whether this means the robot should not take the field. Required; see {@link
   *     MatchImpact}
   * @return the registered handle
   * @throws IllegalArgumentException if {@code group} or {@code text} is blank, or {@code impact} is
   *     null
   */
  public static PumpkinAlert warning(String group, String text, MatchImpact impact) {
    return of(group, text, Severity.WARNING, impact);
  }

  /**
   * Create an {@code INFO} alert. {@code INFO} is {@code PIT_ONLY} by definition — an informational
   * alert cannot stop a match — which is why there is no {@link MatchImpact} parameter here.
   *
   * @param group the dashboard group
   * @param text the message
   * @return the registered handle
   * @throws IllegalArgumentException if {@code group} or {@code text} is blank
   */
  public static PumpkinAlert info(String group, String text) {
    return of(group, text, Severity.INFO, MatchImpact.PIT_ONLY);
  }

  /**
   * Create an alert at an explicit severity. Use when the severity is itself computed (a health
   * source folding a {@code Fault} into an alert, say); prefer {@link #error}, {@link #warning} and
   * {@link #info} at hand-written call sites, because they read better.
   *
   * @param group the dashboard group
   * @param text the message
   * @param severity the severity
   * @param impact whether this means the robot should not take the field
   * @return the registered handle
   * @throws IllegalArgumentException if {@code group} or {@code text} is blank, either enum is null,
   *     or {@code severity} is {@code INFO} with {@code BLOCKS_MATCH}
   */
  public static PumpkinAlert of(String group, String text, Severity severity, MatchImpact impact) {
    return build(group, text, severity, impact, null);
  }

  /**
   * Create a <strong>self-driving</strong> alert: the registry evaluates {@code condition} on its
   * round-robin slice and calls {@code set(...)} for you. This is the preferred form — an alert
   * nobody has to remember to update in periodic is an alert that cannot silently stop working.
   *
   * <p>The condition is evaluated once per full health sweep (about every 220 ms on a stock robot),
   * not every loop, and it must be cheap and side-effect free.
   *
   * @param group the dashboard group
   * @param text the message
   * @param severity the severity
   * @param impact whether this means the robot should not take the field
   * @param condition evaluated by the registry; true means "raise this alert"
   * @return the registered handle. Calling {@code set(...)} on it is legal but pointless — the next
   *     evaluation overwrites it
   * @throws IllegalArgumentException if any argument is null, {@code group} or {@code text} is
   *     blank, or {@code severity} is {@code INFO} with {@code BLOCKS_MATCH}
   */
  public static PumpkinAlert when(
      String group, String text, Severity severity, MatchImpact impact, BooleanSupplier condition) {
    if (condition == null) {
      throw new IllegalArgumentException(
          "Alerts.when(\""
              + group
              + "\", ...) was given a null condition. Pass a BooleanSupplier that returns true when "
              + "the alert should be raised, or use Alerts.of(...) for an alert you set() yourself.");
    }
    return build(group, text, severity, impact, condition);
  }

  private static PumpkinAlert build(
      String group, String text, Severity severity, MatchImpact impact, BooleanSupplier condition) {
    if (group == null || group.isBlank()) {
      throw new IllegalArgumentException(
          "Alert group was \""
              + group
              + "\". Every alert needs a non-blank group — it is the dashboard heading and the "
              + "pit-tab path segment. Use the mechanism name, e.g. \"Arm\".");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(
          "Alert text for group \""
              + group
              + "\" was \""
              + text
              + "\". Every alert needs a non-blank message naming the thing, the value, the "
              + "expected range and the fix, e.g. \"leader TalonFX 21 at 84 C (limit 70 C) — check "
              + "the fan and the gearbox grease\".");
    }
    if (severity == null) {
      throw new IllegalArgumentException(
          "Alert severity for \""
              + group
              + ": "
              + text
              + "\" was null. Pass Severity.INFO, WARNING or ERROR, or call Alerts.error/warning/"
              + "info instead.");
    }
    if (impact == null) {
      throw new IllegalArgumentException(
          "MatchImpact for \""
              + group
              + ": "
              + text
              + "\" was null. It is required at every call site and has no default: pass "
              + "MatchImpact.BLOCKS_MATCH if this means the robot should not take the field, or "
              + "MatchImpact.PIT_ONLY if it is true, worth fixing and not match-stopping.");
    }
    if (severity == Severity.INFO && impact == MatchImpact.BLOCKS_MATCH) {
      throw new IllegalArgumentException(
          "\""
              + group
              + ": "
              + text
              + "\" is INFO but claims BLOCKS_MATCH. An informational alert cannot stop a match. "
              + "Raise it to Severity.WARNING or ERROR if it really blocks, otherwise pass "
              + "MatchImpact.PIT_ONLY.");
    }
    return AlertRegistry.create(group, text, severity, impact, condition);
  }
}

package org.rootstock.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The two current limits, which are not the same limit and are constantly confused for each other.
 *
 * <h2>Stator versus supply, in one paragraph</h2>
 *
 * <p><b>Stator current</b> is the current in the motor windings. It is what makes torque, and it is
 * what makes heat in the motor. A stator limit is a <em>torque</em> limit: it is what stops a
 * mechanism from tearing itself apart against a hard stop, and it is the number to reach for when a
 * gearbox is stripping.
 *
 * <p><b>Supply current</b> is the current drawn from the battery through the breaker. At low speeds
 * a motor controller is a step-down converter, so supply current is much <em>lower</em> than stator
 * current: a motor pulling 200 A of stator at a near-stall might draw only 40 A from the battery.
 * A supply limit is a <em>breaker and battery</em> limit: it is what stops a robot from browning out
 * when four mechanisms move at once.
 *
 * <p><b>The error this record exists to prevent</b> is setting one when you meant the other. Setting
 * a 40 A <em>stator</em> limit on an elevator because "the breaker is 40 A" cripples the mechanism:
 * it now has a fifth of the torque it should have, it stalls partway up, and no message anywhere
 * explains why. Setting a 120 A <em>supply</em> limit because "the motor can do 120 A" trips the
 * main breaker on the first hard acceleration. The two numbers are typed separately, in that order,
 * and validation flags a supply limit above the stator limit, which is physically backwards and is
 * a reliable sign the two were swapped.
 *
 * <h2>What each vendor does with these</h2>
 *
 * <ul>
 *   <li><b>Phoenix 6</b> implements both, plus a two-tier supply limit: it allows
 *       {@code supplyAmps} indefinitely, and if the draw exceeds it for {@code supplyLowerSeconds}
 *       it clamps down to {@code supplyLowerAmps}. That models a breaker's trip curve: a short
 *       surge is fine, a sustained overdraw is not.
 *   <li><b>REVLib</b> has a single {@code smartCurrentLimit}, which behaves as a stator limit. The
 *       supply fields are recorded and printed but the SPARK cannot enforce them, and
 *       {@link #describe()} says so rather than letting a team believe a limit is active when it is
 *       not.
 *   <li><b>PWM controllers</b> enforce nothing at all. The numbers are still logged, because "no
 *       current limit" is exactly the sort of thing worth being able to see in a post-match log.
 * </ul>
 *
 * @param statorAmps the winding-current (torque) limit, in amps
 * @param supplyAmps the sustained battery-draw limit, in amps
 * @param supplyLowerAmps the reduced battery-draw limit applied after a sustained overdraw, in amps;
 *     equal to {@code supplyAmps} when no second tier is wanted
 * @param supplyLowerSeconds how long the draw may exceed {@code supplyAmps} before the lower limit
 *     takes over, in seconds
 */
public record CurrentLimits(
    double statorAmps, double supplyAmps, double supplyLowerAmps, double supplyLowerSeconds) {

  /**
   * The default window before the lower supply tier engages.
   *
   * <p>One second is long enough for an acceleration surge and short enough that a genuinely stuck
   * mechanism stops pulling the battery down before anything else on the robot notices.
   */
  public static final double kDefaultSupplyLowerSeconds = 1.0;

  /**
   * A stator and supply pair, with no second supply tier.
   *
   * <p>This is the form every example config uses. Read it as "at most this much torque, at most
   * this much battery".
   *
   * @param stator the winding-current (torque) limit
   * @param supply the battery-draw limit
   * @return the limits
   */
  public static CurrentLimits of(Current stator, Current supply) {
    Objects.requireNonNull(stator, kNullStator);
    Objects.requireNonNull(supply, kNullSupply);
    double supplyAmps = supply.in(Amps);
    return new CurrentLimits(
        stator.in(Amps), supplyAmps, supplyAmps, kDefaultSupplyLowerSeconds);
  }

  /**
   * A stator and supply pair with an explicit second supply tier, for a team modeling its breaker's
   * trip curve.
   *
   * @param stator the winding-current (torque) limit
   * @param supply the sustained battery-draw limit
   * @param supplyLower the reduced battery-draw limit after a sustained overdraw
   * @param lowerAfter how long the draw may exceed {@code supply} first
   * @return the limits
   */
  public static CurrentLimits of(
      Current stator, Current supply, Current supplyLower, Time lowerAfter) {
    Objects.requireNonNull(stator, kNullStator);
    Objects.requireNonNull(supply, kNullSupply);
    Objects.requireNonNull(supplyLower, kNullSupply);
    Objects.requireNonNull(lowerAfter, "CurrentLimits: the lower-tier window must not be null.");
    return new CurrentLimits(
        stator.in(Amps), supply.in(Amps), supplyLower.in(Amps), lowerAfter.in(Seconds));
  }

  /**
   * A stator limit only, leaving the battery draw unlimited.
   *
   * <p>Correct for a mechanism whose supply draw is already bounded by its stator limit and its
   * gearing (a small roller, say). Not correct for anything that can stall hard.
   *
   * @param stator the winding-current (torque) limit
   * @return the limits
   */
  public static CurrentLimits statorOnly(Current stator) {
    Objects.requireNonNull(stator, kNullStator);
    double amps = stator.in(Amps);
    return new CurrentLimits(
        amps, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, kDefaultSupplyLowerSeconds);
  }

  /**
   * Sensible limits for a motor, so a first config can omit current limits entirely.
   *
   * <p>Chosen to be safe on a mechanism nobody has characterized, not to be fast. The point is that
   * forgetting this line never destroys hardware: a NEO 550 gets 20 A rather than the 40 A a NEO
   * gets, because it has almost no thermal mass.
   *
   * @param model the motor
   * @return the default limits for that motor
   */
  public static CurrentLimits defaultsFor(MotorModel model) {
    Objects.requireNonNull(
        model, "CurrentLimits.defaultsFor: name the motor whose defaults you want.");
    return of(model.defaultStatorLimit(), model.defaultSupplyLimit());
  }

  /**
   * The stator limit as a typed measure, for a caller that wants units back.
   *
   * @return the winding-current limit
   */
  public Current stator() {
    return Amps.of(statorAmps);
  }

  /**
   * The supply limit as a typed measure.
   *
   * @return the battery-draw limit
   */
  public Current supply() {
    return Amps.of(supplyAmps);
  }

  /**
   * A copy with a different stator limit.
   *
   * @param stator the new winding-current limit
   * @return a copy
   */
  public CurrentLimits withStator(Current stator) {
    Objects.requireNonNull(stator, kNullStator);
    return new CurrentLimits(stator.in(Amps), supplyAmps, supplyLowerAmps, supplyLowerSeconds);
  }

  /**
   * A copy with a different supply limit, keeping the second tier equal to it.
   *
   * @param supply the new battery-draw limit
   * @return a copy
   */
  public CurrentLimits withSupply(Current supply) {
    Objects.requireNonNull(supply, kNullSupply);
    double amps = supply.in(Amps);
    return new CurrentLimits(statorAmps, amps, amps, supplyLowerSeconds);
  }

  /**
   * A copy with an explicit second supply tier.
   *
   * @param supplyLower the reduced battery-draw limit
   * @param lowerAfter how long the draw may exceed the sustained limit first
   * @return a copy
   */
  public CurrentLimits withSupplyLowerTier(Current supplyLower, Time lowerAfter) {
    Objects.requireNonNull(supplyLower, kNullSupply);
    Objects.requireNonNull(lowerAfter, "CurrentLimits: the lower-tier window must not be null.");
    return new CurrentLimits(
        statorAmps, supplyAmps, supplyLower.in(Amps), lowerAfter.in(Seconds));
  }

  /**
   * Whether a finite battery-draw limit is set at all.
   *
   * @return true when {@link #supplyAmps()} is finite
   */
  public boolean hasSupplyLimit() {
    return Double.isFinite(supplyAmps);
  }

  /**
   * Whether a genuine second supply tier is configured, rather than the two tiers being equal.
   *
   * @return true when the lower tier is strictly below the sustained limit
   */
  public boolean hasSupplyLowerTier() {
    return Double.isFinite(supplyLowerAmps) && supplyLowerAmps < supplyAmps;
  }

  /**
   * The single number REVLib's {@code smartCurrentLimit} takes.
   *
   * <p>REVLib's limit is an integer and behaves as a stator limit, so this is the stator limit
   * rounded. A backend that used the supply limit here would be silently applying the wrong one.
   *
   * @return the stator limit in whole amps, at least 1
   */
  public int smartCurrentLimitAmps() {
    if (!Double.isFinite(statorAmps)) {
      return 1;
    }
    return Math.max(1, (int) Math.round(statorAmps));
  }

  /**
   * Every problem visible from these limits alone, with no owning mechanism to name.
   *
   * @return the problems, in declaration order; empty when the limits are fine
   */
  public List<String> problems() {
    return problems("");
  }

  /**
   * Every problem visible from these limits alone.
   *
   * <p><b>Never throws, never returns null.</b> The interesting case is the third one: a supply limit
   * above the stator limit is physically backwards (supply current is always the lower of the two
   * at the speeds where limiting matters) and is a reliable sign that the two arguments were
   * swapped at the call site.
   *
   * @param owner the mechanism these limits belong to, for the message
   * @return the problems, in declaration order; empty when the limits are fine
   */
  public List<String> problems(String owner) {
    String who = owner == null || owner.isBlank() ? "current limits" : owner + " current limits";
    List<String> out = new ArrayList<>();

    if (!Double.isFinite(statorAmps) || statorAmps <= 0.0) {
      out.add(
          who
              + ": statorAmps = "
              + fmt(statorAmps)
              + ", which must be a finite current greater than zero. The stator limit is the "
              + "TORQUE limit. With no value the mechanism has no protection against driving "
              + "itself into a hard stop. Fix: CurrentLimits.defaultsFor(motor), or a number you "
              + "have measured.");
    }
    if (supplyAmps <= 0.0 || Double.isNaN(supplyAmps)) {
      out.add(
          who
              + ": supplyAmps = "
              + fmt(supplyAmps)
              + ", which must be greater than zero (or infinite for no limit). Fix: use "
              + "CurrentLimits.statorOnly(...) if you deliberately want no supply limit.");
    }
    if (Double.isFinite(statorAmps)
        && Double.isFinite(supplyAmps)
        && supplyAmps > statorAmps
        && statorAmps > 0.0) {
      out.add(
          who
              + ": supplyAmps = "
              + fmt(supplyAmps)
              + " is ABOVE statorAmps = "
              + fmt(statorAmps)
              + ", which is backwards. A motor controller steps voltage down at low speed, so "
              + "battery (supply) current is always the smaller of the two where limiting matters. "
              + "This is what a swapped CurrentLimits.of(stator, supply) call looks like. Fix: the "
              + "first argument is the winding/torque limit (the bigger number, 40-80 A), the "
              + "second is the battery/breaker limit (the smaller one, 20-40 A).");
    }
    if (Double.isFinite(supplyLowerAmps) && supplyLowerAmps > supplyAmps) {
      out.add(
          who
              + ": supplyLowerAmps = "
              + fmt(supplyLowerAmps)
              + " is above supplyAmps = "
              + fmt(supplyAmps)
              + ". The lower tier is the REDUCED limit that engages after a sustained overdraw, so "
              + "it cannot be the larger of the two. Fix: make it smaller, or set the two equal to "
              + "disable the second tier.");
    }
    if (!Double.isFinite(supplyLowerSeconds) || supplyLowerSeconds < 0.0) {
      out.add(
          who
              + ": supplyLowerSeconds = "
              + supplyLowerSeconds
              + ", which must be a finite time of zero or more. Fix: "
              + kDefaultSupplyLowerSeconds
              + " s is the default and models a breaker's trip curve well.");
    }
    return List.copyOf(out);
  }

  /**
   * The limits as the boot dump prints them, naming which vendor enforces which.
   *
   * @return a human-readable description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "stator %s (torque limit)", fmt(statorAmps)));
    if (hasSupplyLimit()) {
      sb.append(String.format(Locale.ROOT, ", supply %s (battery/breaker limit)", fmt(supplyAmps)));
      if (hasSupplyLowerTier()) {
        sb.append(
            String.format(
                Locale.ROOT,
                ", dropping to %s after %.2f s of overdraw",
                fmt(supplyLowerAmps),
                supplyLowerSeconds));
      }
    } else {
      sb.append(", no supply limit");
    }
    sb.append(
        ". Phoenix enforces both; a SPARK has one smartCurrentLimit and enforces the stator "
            + "number only; a PWM controller enforces neither.");
    return sb.toString();
  }

  private static String fmt(double amps) {
    if (Double.isInfinite(amps)) {
      return "unlimited";
    }
    if (Double.isNaN(amps)) {
      return "NaN";
    }
    return String.format(Locale.ROOT, "%.1f A", amps);
  }

  private static final String kNullStator =
      "CurrentLimits: the stator limit must not be null. It is the TORQUE limit, the number that "
          + "stops a mechanism tearing itself apart against a hard stop.";

  private static final String kNullSupply =
      "CurrentLimits: the supply limit must not be null. It is the BATTERY limit, the number that "
          + "stops the robot browning out when several mechanisms move at once.";
}

package org.rootstock.control;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.units.SiDomain;

/**
 * The box every actuating tuning routine runs inside: a voltage ceiling, a position band, a velocity
 * ceiling, a current limit and a clock.
 *
 * <p>Every existing FRC live-tuning implementation ships with a documentation warning and no
 * interlocks — <i>"Live Tuning can be DANGEROUS please test in sim before the real robot"</i>, <i>"it
 * is up to you to set up hard or soft limits to prevent injury or damage"</i>. This record is where
 * Rootstock makes that structural instead of documentary. It is normally derived from the mechanism
 * by {@link #derive(TuningTarget)} rather than written by hand.
 *
 * <h2>Why the band is {@code softMargin + guard} and not {@code max(softMargin, guard)}</h2>
 *
 * <p>The guarantee is <i>"the supervisor always trips before the device's own soft limit clamps
 * silently"</i>, and two earlier rules broke it at opposite ends of the margin range:
 *
 * <ul>
 *   <li>Pulling the limits in by a <em>fraction of the margin</em> collapsed to nothing whenever the
 *       margin was zero, which was the default. The supervisor band equalled the device band and the
 *       failure was silent — the student saw the device clamp with no message.
 *   <li>Replacing it with {@code max(softMargin, 0.05 * range)} fixed the zero-margin end and broke
 *       the other one: as soon as a team configures a <em>generous</em> margin — 0.10 m on 0.8 m of
 *       travel, which is exactly what a careful team does — the max picks the margin, the supervisor
 *       band equals the device band again, and the guarantee is false for every margin at or above
 *       5% of travel.
 * </ul>
 *
 * <p>The additive rule makes the failure unrepresentable rather than merely untested. {@code
 * max(0.03 * range, floor)} is strictly positive for every legal limit set, so {@code guard >
 * softMargin} <b>always</b>, so the supervisor band is strictly inside the device band <b>always</b>.
 * That is a one-line proof rather than a case analysis, which is the whole point.
 *
 * @param maxVolts absolute voltage ceiling for the routine
 * @param positionMin lower abort bound, strictly inside the device's lower soft limit
 * @param positionMax upper abort bound, strictly inside the device's upper soft limit
 * @param maxAbsVelocity abort above this speed, m/s or rad/s
 * @param maxStatorAmps abort above this current, once sustained for {@code holdoffSeconds}
 * @param holdoffSeconds how long the current must exceed its limit before it counts as an abort
 * @param maxRoutineSeconds wall-clock timeout for the whole routine
 * @param stallVoltsThreshold "we commanded at least this many volts..."
 * @param stallVelocityThreshold "...but it is moving slower than this..."
 * @param stallSeconds "...for this long", which together mean a stall
 * @param requireHeldEnable whether a human must be holding an enable control for the routine to run
 */
public record SafetyEnvelope(
    double maxVolts,
    double positionMin,
    double positionMax,
    double maxAbsVelocity,
    double maxStatorAmps,
    double holdoffSeconds,
    double maxRoutineSeconds,
    double stallVoltsThreshold,
    double stallVelocityThreshold,
    double stallSeconds,
    boolean requireHeldEnable) {

  /** Extra guard beyond the team's own margin, as a fraction of travel. */
  public static final double GUARD_FRACTION_OF_TRAVEL = 0.03;

  /** Absolute floor on that extra guard for a linear axis, so a very short axis still gets a band. */
  public static final double MIN_GUARD_METRES = 0.005;

  /** Absolute floor on that extra guard for a rotary axis. */
  public static final double MIN_GUARD_RADIANS = 0.020;

  /** Fraction of nominal battery voltage a derived envelope will command. */
  public static final double DEFAULT_VOLTAGE_FRACTION = 0.85;

  /** Multiple of the predicted free speed above which a derived envelope aborts. */
  public static final double DEFAULT_VELOCITY_HEADROOM = 1.15;

  /** Stator-current ceiling used when the mechanism cannot report its configured limit, in amps. */
  public static final double DEFAULT_STATOR_AMPS = 60.0;

  /** How long an overcurrent must persist before it counts, in seconds. */
  public static final double DEFAULT_HOLDOFF_SECONDS = 0.15;

  /** Wall-clock ceiling on a single tuning routine, in seconds. */
  public static final double DEFAULT_MAX_ROUTINE_SECONDS = 20.0;

  /** How long a stall condition must persist before it counts, in seconds. */
  public static final double DEFAULT_STALL_SECONDS = 0.5;

  /**
   * Derive an envelope from the mechanism. This is what the wizard uses; a team rarely writes one by
   * hand.
   *
   * <ul>
   *   <li>{@code positionMin/Max} are pulled in from the hard limits by {@code softMargin +
   *       max(0.03 * range, floor)} — the team's margin <b>plus</b> a travel-derived guard, never
   *       the larger of the two.
   *   <li>{@code maxAbsVelocity} is {@value #DEFAULT_VELOCITY_HEADROOM} times the free speed the
   *       {@link PlantPrior} predicts. When the prior cannot predict one, the velocity abort is
   *       disabled rather than guessed at, and the routine narrates that.
   *   <li>{@code maxStatorAmps} falls back to {@value #DEFAULT_STATOR_AMPS} A, because a {@link
   *       TuningTarget} is not required to know its configured limit.
   * </ul>
   *
   * @param target the mechanism to derive from
   * @return an envelope; call {@link #validate(String, TravelLimits)} before arming anything with it
   * @throws NullPointerException if {@code target} is null
   */
  public static SafetyEnvelope derive(TuningTarget target) {
    Objects.requireNonNull(target, "target");
    TravelLimits limits = target.travelLimits();
    PlantPrior prior = target.plantPrior();

    double floor =
        target.siDomain() == SiDomain.LINEAR_METERS ? MIN_GUARD_METRES : MIN_GUARD_RADIANS;
    double guard =
        limits.softMargin() + Math.max(GUARD_FRACTION_OF_TRAVEL * limits.range(), floor);

    double freeSpeed = prior.freeSpeedSi();
    boolean speedKnown = Double.isFinite(freeSpeed) && freeSpeed > 0.0;
    double maxVolts = DEFAULT_VOLTAGE_FRACTION * prior.nominalVolts();

    return new SafetyEnvelope(
        maxVolts,
        limits.min() + guard,
        limits.max() - guard,
        speedKnown ? DEFAULT_VELOCITY_HEADROOM * freeSpeed : Double.POSITIVE_INFINITY,
        DEFAULT_STATOR_AMPS,
        DEFAULT_HOLDOFF_SECONDS,
        DEFAULT_MAX_ROUTINE_SECONDS,
        0.25 * maxVolts,
        speedKnown ? 0.02 * freeSpeed : 0.0,
        DEFAULT_STALL_SECONDS,
        true);
  }

  /**
   * A copy with a different voltage ceiling — the one field a cautious team most often lowers.
   *
   * @param volts the new ceiling
   * @return a new record; this one is unchanged
   */
  public SafetyEnvelope withMaxVolts(double volts) {
    return new SafetyEnvelope(
        volts,
        positionMin,
        positionMax,
        maxAbsVelocity,
        maxStatorAmps,
        holdoffSeconds,
        maxRoutineSeconds,
        stallVoltsThreshold,
        stallVelocityThreshold,
        stallSeconds,
        requireHeldEnable);
  }

  /**
   * A copy with a different stator-current ceiling, for a mechanism whose real limit is known.
   *
   * @param amps the new ceiling
   * @return a new record; this one is unchanged
   */
  public SafetyEnvelope withMaxStatorAmps(double amps) {
    return new SafetyEnvelope(
        maxVolts,
        positionMin,
        positionMax,
        maxAbsVelocity,
        amps,
        holdoffSeconds,
        maxRoutineSeconds,
        stallVoltsThreshold,
        stallVelocityThreshold,
        stallSeconds,
        requireHeldEnable);
  }

  /**
   * A copy with a different routine timeout.
   *
   * @param seconds the new wall-clock ceiling
   * @return a new record; this one is unchanged
   */
  public SafetyEnvelope withMaxRoutineSeconds(double seconds) {
    return new SafetyEnvelope(
        maxVolts,
        positionMin,
        positionMax,
        maxAbsVelocity,
        maxStatorAmps,
        holdoffSeconds,
        seconds,
        stallVoltsThreshold,
        stallVelocityThreshold,
        stallSeconds,
        requireHeldEnable);
  }

  /**
   * Whether a position is inside the supervisor's abort band.
   *
   * @param positionSi a position, in metres or radians
   * @return true if the routine may keep commanding voltage at this position
   */
  public boolean containsPosition(double positionSi) {
    return positionSi >= positionMin && positionSi <= positionMax;
  }

  /**
   * The width of the abort band.
   *
   * @return {@code positionMax - positionMin}, which is negative when the band has collapsed
   */
  public double bandWidth() {
    return positionMax - positionMin;
  }

  /**
   * Collect every problem with this envelope. Pure and non-throwing, like every other config-reachable
   * check in the library.
   *
   * <p>There is exactly one way to fail: the guard can eat the whole travel on a mechanism that is
   * too short for the fixed floor to fit inside twice. That is a real configuration — a 4 cm linear
   * stage, a 30-degree wrist — and the right answer is a named message, not an inverted band that
   * silently aborts on the first cycle.
   *
   * @param owner the mechanism name, so the message names the thing the student edits
   * @param limits the limits this envelope was derived from, so the message can show the arithmetic
   * @return an empty list when the band is usable, otherwise one message explaining why not
   */
  public List<String> validate(String owner, TravelLimits limits) {
    if (positionMax > positionMin) {
      return List.of();
    }
    String who = owner == null ? "this mechanism" : owner;
    double range = limits == null ? Double.NaN : limits.range();
    double margin = limits == null ? Double.NaN : limits.softMargin();
    return List.of(
        String.format(
            Locale.ROOT,
            "%s: there is not enough travel here for the tuning supervisor to have a band it can "
                + "stop inside. You declared %s of travel with a soft margin of %s, which leaves a "
                + "band of %s. It needs range > 2 * (softMargin + max(0.03 * range, floor)), where "
                + "the floor is %s m on a linear axis or %s rad on a rotary one. "
                + "Fix: either the travel limits are wrong or the soft margin is too large for "
                + "them. Measure the travel and set the margin to about 2%% of it.",
            who,
            range,
            margin,
            bandWidth(),
            MIN_GUARD_METRES,
            MIN_GUARD_RADIANS));
  }

  /**
   * The envelope on one line, for the boot dump and for the message shown before a routine arms.
   *
   * <p>A student about to let the library command voltage to their mechanism should be able to read
   * exactly what it has promised not to exceed.
   *
   * @param unitLabel the SI unit of the position band, {@code "m"} or {@code "rad"}
   * @return a single-line summary
   */
  public String describe(String unitLabel) {
    String unit = unitLabel == null ? "" : unitLabel;
    return String.format(
        Locale.ROOT,
        "at most %.2f V, inside [%.4f, %.4f] %s, below %.3f %s/s and %.0f A, "
            + "stopping after %.1f s",
        maxVolts,
        positionMin,
        positionMax,
        unit,
        maxAbsVelocity,
        unit,
        maxStatorAmps,
        maxRoutineSeconds);
  }
}

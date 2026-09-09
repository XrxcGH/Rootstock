package org.rootstock.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A mechanism's hard travel limits plus the margin a tuning routine must stay inside, all in SI —
 * metres for a linear axis, radians for a rotary one.
 *
 * <p><b>{@code softMargin} is not optional and not decorative.</b> The tuning supervisor's abort
 * band is derived from these limits and has to sit <em>strictly</em> inside them, so the supervisor
 * always trips first and the student sees "Rootstock stopped this" instead of a silent device
 * clamp. A zero margin makes that impossible; a margin larger than 40% of travel leaves the
 * supervisor no band at all. Both ends are checked.
 *
 * <h2>This record does not throw</h2>
 *
 * <p>Earlier revisions rejected an illegal margin from the compact constructor. Every config in this
 * library and in the documentation is declared as a {@code public static final} field, so that throw
 * surfaces as {@code ExceptionInInitializerError} out of a class initialiser: robot code never
 * starts, the driver station shows red "Robot Code", and the carefully written error message becomes
 * a nested cause under three frames of JVM class-init noise. That failure is declared structurally
 * unrepresentable across Rootstock. Problems are <b>collected</b> by {@link #validate(String)} and
 * printed all at once by the registration pipeline, which then enters safe mode. The robot boots;
 * the student reads the message.
 *
 * @param min the hard lower limit, in metres or radians
 * @param max the hard upper limit, in metres or radians
 * @param softMargin how far inside the hard limits a tuning routine must stay, in the same unit
 */
public record TravelLimits(double min, double max, double softMargin) {

  /**
   * The smallest legal margin, as a fraction of travel.
   *
   * <p>Below this the supervisor's band is indistinguishable from the device's own soft limits, and
   * the guarantee that Rootstock stops the mechanism first becomes false while continuing to be
   * documented — which is worse than having no supervisor.
   */
  public static final double MIN_MARGIN_FRACTION = 0.02;

  /**
   * The largest legal margin, as a fraction of travel. Above this there is no band left between the
   * two soft limits for a routine to move in.
   */
  public static final double MAX_MARGIN_FRACTION = 0.40;

  /**
   * Total travel between the hard limits.
   *
   * @return {@code max - min}, in metres or radians
   */
  public double range() {
    return max - min;
  }

  /**
   * The lower soft limit: the hard limit pulled in by the margin.
   *
   * @return {@code min + softMargin}
   */
  public double softMin() {
    return min + softMargin;
  }

  /**
   * The upper soft limit: the hard limit pulled in by the margin.
   *
   * @return {@code max - softMargin}
   */
  public double softMax() {
    return max - softMargin;
  }

  /**
   * The midpoint of travel — where a recentring move between identification probes returns to.
   *
   * @return {@code (min + max) / 2}
   */
  public double centre() {
    return (min + max) / 2.0;
  }

  /**
   * Whether a position is inside the soft band.
   *
   * @param x a position, in metres or radians
   * @return true if {@code x} lies between {@link #softMin()} and {@link #softMax()} inclusive
   */
  public boolean insideSoft(double x) {
    return x >= softMin() && x <= softMax();
  }

  /**
   * Collect every problem with these limits. Pure, non-throwing, and allocation-free when the limits
   * are legal.
   *
   * <p>Each message names the field, the value, the expected range and the fix, because it is read
   * by a student at 11pm who has never seen this library's source.
   *
   * @param owner the mechanism name, so the message names the thing the student actually edits
   * @return an empty list when the limits are legal, otherwise one message per problem
   */
  public List<String> validate(String owner) {
    if (max > min
        && softMargin >= MIN_MARGIN_FRACTION * range()
        && softMargin <= MAX_MARGIN_FRACTION * range()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(2);
    String who = owner == null ? "this mechanism" : owner;
    if (!(max > min)) {
      out.add(
          String.format(
              Locale.ROOT,
              "%s: travelLimits.max was %s, which must be greater than min (%s). "
                  + "Travel limits run from min to max and a mechanism with no travel cannot be "
                  + "tuned. Fix: check that you have not swapped the two arguments, and that both "
                  + "are in SI (metres or radians), not in inches or degrees.",
              who,
              max,
              min));
    } else if (softMargin < MIN_MARGIN_FRACTION * range()) {
      out.add(
          String.format(
              Locale.ROOT,
              "%s: travelLimits.softMargin was %s, which must be at least %s (2%% of the %s of "
                  + "travel you declared). The tuning supervisor's abort band has to fit strictly "
                  + "inside your soft limits so it stops the mechanism before the device clamps "
                  + "silently; a margin this small leaves it no room, and you would get a "
                  + "supervisor that only appears to protect you. "
                  + "Fix: raise the margin, or widen the travel limits if they are too tight.",
              who,
              softMargin,
              MIN_MARGIN_FRACTION * range(),
              range()));
    } else {
      out.add(
          String.format(
              Locale.ROOT,
              "%s: travelLimits.softMargin was %s, which must be at most %s (40%% of the %s of "
                  + "travel you declared). A margin this large leaves the supervisor no band "
                  + "between the two soft limits, so there is nowhere for a tuning routine to "
                  + "move. Fix: either your travel limits are wrong or your margin is. Check "
                  + "which of the two you measured and which you guessed.",
              who,
              softMargin,
              MAX_MARGIN_FRACTION * range(),
              range()));
    }
    return out;
  }

  /**
   * Limits meaning "unbounded", legal only for {@link MechanismArchetype#FLYWHEEL} and {@link
   * MechanismArchetype#DRIVE_VELOCITY}.
   *
   * <p>Position aborts are disabled for those archetypes, so the numbers are never compared against
   * anything real. The margin exists purely so the 2%-of-travel invariant holds and {@link
   * #validate(String)} stays quiet: {@code 1e8} is 5% of the {@code 2e9} range.
   *
   * @return a limit set no position will ever reach
   */
  public static TravelLimits unbounded() {
    return new TravelLimits(-1e9, 1e9, 1e8);
  }

  /**
   * These limits on one line, for the boot dump and for alert text.
   *
   * @param unitLabel the SI unit these numbers are in, {@code "m"} or {@code "rad"}
   * @return e.g. {@code "[0.000, 1.397] m, soft band [0.028, 1.369] m"}
   */
  public String describe(String unitLabel) {
    String unit = unitLabel == null ? "" : unitLabel;
    return String.format(
        Locale.ROOT,
        "[%.3f, %.3f] %s, soft band [%.3f, %.3f] %s",
        min,
        max,
        unit,
        softMin(),
        softMax(),
        unit);
  }
}

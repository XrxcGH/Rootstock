package org.rootstock.tuning.wizard;

import java.time.Instant;
import java.util.Locale;
import org.rootstock.control.Gains;

/**
 * The record that says a recipe has proved itself in simulation against a plant that was
 * deliberately not the one it was told about.
 *
 * <p><b>What was wrong with the obvious version of this gate.</b> The first form simply required the
 * recipe to complete in simulation. That gate cannot fail. In simulation the plant <em>is</em> the
 * declared model: the wizard identifies the exact system it was handed, every fit is near perfect,
 * every response classifies as good, and the gate passes unconditionally. It cannot catch a wrong
 * gear ratio, backlash, a slipping belt, an inverted encoder or a wrong arm zero — which are the
 * actual causes of tuning accidents. It was pure friction with an escape hatch students would find
 * in week one.
 *
 * <p><b>What the gate tests instead.</b> Nine runs. kV and kA are each independently scaled by
 * {@code {0.4, 1.0, 3.0}} relative to the declared prior, and every run injects a position-reference
 * error. Promotion is granted only if, in <em>all nine</em>, the supervisor kept the mechanism
 * inside its band and no run reached a hard stop.
 *
 * <p>That is a direct test of the thing that must not fail on hardware: <i>when the mechanism is not
 * what you told me it was, does the supervisor still stop it in time?</i> A wrong gear ratio is
 * exactly a wrong kV. A heavier-than-declared mechanism is exactly a larger kA. The grid brackets
 * the range of error the fit checker is willing to warn about rather than reject, so the gate covers
 * every plant the identification step would hand back with a shrug.
 *
 * <p><b>And it is shown to the student</b>, because a gate the student cannot see is a gate the
 * student routes around. {@link #describe(String)} is the sentence that appears on the dashboard.
 *
 * <p><b>Stated honestly: this is the third safety property of the tuning domain, not the first.</b>
 * The two that carry the case are the mechanical health check's block verdict and the supervisor's
 * position-reference preconditions. This one proves the envelope holds under plant error. That is a
 * real property and it should be advertised as exactly that much.
 *
 * @param mechanismName which mechanism was promoted
 * @param configHash a hash of the plant prior, travel limits, archetype and unit domain; change the
 *     gearing and the promotion is void
 * @param recipeVersion which recipe proved it
 * @param completedAt when
 * @param resultingGains what the runs produced, for the record
 * @param runsCompleted must equal {@link #kRequiredRuns} or the promotion is void
 * @param worstMarginToLimitSi the smallest distance from the band edge, over all runs
 * @param worstMarginToHardStopSi the smallest distance from a hard stop, over all runs
 * @param worstCaseDescription which perturbation produced that worst case
 */
public record SimPromotion(
    String mechanismName,
    String configHash,
    String recipeVersion,
    Instant completedAt,
    Gains resultingGains,
    int runsCompleted,
    double worstMarginToLimitSi,
    double worstMarginToHardStopSi,
    String worstCaseDescription) {

  /**
   * The perturbation grid. Public so a team can see exactly what was tested rather than trusting a
   * claim about it.
   */
  public static final double[] PLANT_SCALES = {0.4, 1.0, 3.0};

  /** How many perturbed runs a valid promotion contains. Three kV scales times three kA scales. */
  public static final int kRequiredRuns = 9;

  /**
   * Whether this record is a valid promotion for a given configuration and recipe.
   *
   * <p>Three ways to be invalid, and all three matter. Fewer than nine runs means the grid was not
   * covered. A different config hash means the gearing, the mass or the travel changed, and a
   * promotion earned by a different mechanism is not a promotion. A margin at or below zero means
   * the supervisor did not contain the run it was supposed to contain.
   *
   * @param hash the current configuration hash
   * @param recipe the recipe version about to run
   * @return true if this promotion authorises that recipe on that configuration
   */
  public boolean authorises(String hash, String recipe) {
    return runsCompleted == kRequiredRuns
        && configHash != null
        && configHash.equals(hash)
        && recipeVersion != null
        && recipeVersion.equals(recipe)
        && worstMarginToLimitSi > 0
        && worstMarginToHardStopSi > 0;
  }

  /**
   * The sentence the student reads.
   *
   * @param unitLabel "m" or "rad"
   * @return the description
   */
  public String describe(String unitLabel) {
    if (runsCompleted != kRequiredRuns) {
      return String.format(
          Locale.ROOT,
          "Only %d of the %d perturbed runs finished, so this promotion does not count.",
          runsCompleted,
          kRequiredRuns);
    }
    if (worstMarginToHardStopSi <= 0) {
      return String.format(
          Locale.ROOT,
          "In simulation, with %s, the mechanism reached a hard stop. The supervisor's look-ahead "
              + "is not enough margin at the speeds that plant produces. Not promoting - widen "
              + "your soft limits or reduce the step size before you run this on hardware.",
          worstCaseDescription);
    }
    return String.format(
        Locale.ROOT,
        "In simulation, with %s, the supervisor stopped the mechanism %.4f %s before the limit and "
            + "%.4f %s before a hard stop. All %d runs contained. Promoting.",
        worstCaseDescription,
        worstMarginToLimitSi,
        unitLabel,
        worstMarginToHardStopSi,
        unitLabel,
        runsCompleted);
  }
}

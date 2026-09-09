package org.rootstock.tuning.wizard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.rootstock.control.Gains;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.identity.RobotId;
import org.rootstock.core.identity.RobotIdentity;
import org.rootstock.tuning.TuningRegistry;

/**
 * Decides whether a recipe has earned the right to run on hardware.
 *
 * <p>The gate itself is two rules and they are both about the <em>envelope</em>, never about the
 * fit: every one of the nine perturbed runs must finish with the mechanism inside its band, and no
 * run may reach a hard stop. A run that produced a terrible kV and a badly damped kP still passes,
 * because the question this gate asks is not "did the wizard tune it well" but "when the mechanism
 * is not what you told me it was, does the supervisor still stop it in time."
 *
 * <p><b>The gate does not apply in simulation.</b> That would be circular. It applies the first time
 * a mechanism is asked to move on real hardware, and it stops applying the moment the mechanism's
 * configuration changes — a new gear ratio voids the promotion, because a new gear ratio is a new
 * mechanism.
 *
 * <p><b>There is an override and it is deliberately annoying.</b> {@link
 * TuningWizard#skipSimPromotion(String)} takes a free-text reason, logs it verbatim into the report,
 * and raises a warning alert that stays up for the rest of the session. Friction, on purpose:
 * a student who has read the safety notes can proceed, and a student who has not will be asked to
 * type a sentence saying they did.
 */
public final class SimPromotionGate {

  /** One perturbed run's outcome. The only two numbers that decide anything are the margins. */
  public record Run(
      double kvScale,
      double kaScale,
      double positionReferenceErrorSi,
      double marginToLimitSi,
      double marginToHardStopSi) {

    /**
     * Whether this run was contained.
     *
     * @return true if the supervisor kept it inside both bounds
     */
    public boolean contained() {
      return marginToLimitSi > 0 && marginToHardStopSi > 0;
    }

    /**
     * The one-line description that ends up in the promotion record's worst case.
     *
     * @return the description
     */
    public String describe() {
      return String.format(
          Locale.ROOT,
          "kV x%.1f, kA x%.1f, position reference off by %.4f",
          kvScale,
          kaScale,
          positionReferenceErrorSi);
    }
  }

  private static final Map<String, SimPromotion> s_promotions = new LinkedHashMap<>();

  private SimPromotionGate() {}

  /**
   * The nine perturbations the gate requires, in a stable order.
   *
   * <p>Published so a team can read the grid rather than trust a sentence about it — and so a
   * harness that runs the sweep does not have to reinvent it.
   *
   * @return nine {kvScale, kaScale} pairs
   */
  public static List<double[]> grid() {
    List<double[]> out = new ArrayList<>();
    for (double kv : SimPromotion.PLANT_SCALES) {
      for (double ka : SimPromotion.PLANT_SCALES) {
        out.add(new double[] {kv, ka});
      }
    }
    return out;
  }

  /**
   * Turn nine completed runs into a promotion, or say why they are not one.
   *
   * <p>This is the whole gate. Note what it does <em>not</em> look at: the gains, the fit quality,
   * the response classification. A gate that scored the tuning would pass a wildly optimistic plant
   * that happened to tune nicely, which is precisely the failure the first version of this gate had.
   *
   * @param mechanismName the mechanism
   * @param configHash the configuration hash the runs were performed against
   * @param recipeVersion the recipe that ran
   * @param resultingGains what the runs produced, recorded but not judged
   * @param runs the runs; there must be exactly nine
   * @return the promotion, or empty if the runs do not authorise one
   */
  public static SimPromotion evaluate(
      String mechanismName,
      String configHash,
      String recipeVersion,
      Gains resultingGains,
      List<Run> runs) {
    if (runs == null || runs.isEmpty()) {
      return new SimPromotion(
          mechanismName,
          configHash,
          recipeVersion,
          Instant.now(),
          resultingGains,
          0,
          Double.NaN,
          Double.NaN,
          "no runs were performed");
    }
    Run worst = runs.get(0);
    for (Run r : runs) {
      if (r.marginToHardStopSi() < worst.marginToHardStopSi()) {
        worst = r;
      }
    }
    double worstLimit = Double.POSITIVE_INFINITY;
    for (Run r : runs) {
      worstLimit = Math.min(worstLimit, r.marginToLimitSi());
    }
    // A refused promotion is still returned, and it still carries its worst case, so the caller can
    // show the student exactly which perturbation broke containment rather than a bare "no".
    return new SimPromotion(
        mechanismName,
        configHash,
        recipeVersion,
        Instant.now(),
        resultingGains,
        runs.size(),
        worstLimit,
        worst.marginToHardStopSi(),
        worst.describe());
  }

  /**
   * Evaluate the runs and, only if they authorise it, remember the promotion.
   *
   * @param mechanismName the mechanism
   * @param configHash the configuration hash the runs were performed against
   * @param recipeVersion the recipe that ran
   * @param resultingGains what the runs produced
   * @param runs the nine runs
   * @return the promotion if it was granted, or empty if the gate refused
   */
  public static Optional<SimPromotion> promote(
      String mechanismName,
      String configHash,
      String recipeVersion,
      Gains resultingGains,
      List<Run> runs) {
    SimPromotion promotion =
        evaluate(mechanismName, configHash, recipeVersion, resultingGains, runs);
    if (!promotion.authorises(configHash, recipeVersion)) {
      return Optional.empty();
    }
    remember(promotion);
    return Optional.of(promotion);
  }

  /**
   * Remember a promotion so the wizard will honour it.
   *
   * @param promotion the promotion
   * @return the promotion, for chaining
   */
  public static SimPromotion remember(SimPromotion promotion) {
    s_promotions.put(promotion.mechanismName(), promotion);
    return promotion;
  }

  /**
   * The recorded promotion for a mechanism, if there is one.
   *
   * @param mechanismName the mechanism
   * @return the promotion, or empty
   */
  public static Optional<SimPromotion> of(String mechanismName) {
    return Optional.ofNullable(s_promotions.get(mechanismName));
  }

  /**
   * The configuration hash a promotion is keyed on.
   *
   * <p>Delegates to the tuning registry so that "what counts as the same mechanism" has one
   * definition. Change the gearing, the mass, the travel or the unit domain, and this changes.
   *
   * @param target the mechanism
   * @return the hash
   */
  public static String configHash(TuningTarget target) {
    return TuningRegistry.configHash(target);
  }

  /**
   * Whether the wizard may arm this mechanism on hardware.
   *
   * @param target the mechanism
   * @param recipe the recipe about to run
   * @return empty if it may proceed, or the refusal to show the student
   */
  public static Optional<String> refusal(TuningTarget target, TuningRecipe recipe) {
    if (RobotIdentity.current() == RobotId.SIM) {
      return Optional.empty();
    }
    String hash = configHash(target);
    Optional<SimPromotion> promotion = of(target.tuningName());
    if (promotion.isEmpty()) {
      return Optional.of(
          target.tuningName()
              + " has never completed this recipe in simulation, so Rootstock will not command "
              + "voltage to it on real hardware yet. Run the same recipe in the simulator first: it "
              + "takes about forty seconds, runs unattended, and it is checking one specific thing "
              + "- that when your mechanism turns out to be three times heavier than your config "
              + "says, the safety supervisor still stops it before it hits anything. If you have "
              + "read the safety notes and want to proceed anyway, call "
              + "skipSimPromotion(\"why\") on the wizard.");
    }
    SimPromotion p = promotion.get();
    if (!p.authorises(hash, recipe.version())) {
      return Optional.of(
          target.tuningName()
              + " has a simulation promotion, but it does not apply: "
              + (p.configHash() == null || !p.configHash().equals(hash)
                  ? "the mechanism's configuration has changed since it was earned. A new gear "
                      + "ratio, mass or travel range is a new mechanism, and a promotion earned by "
                      + "a different mechanism is not a promotion."
                  : p.runsCompleted() != SimPromotion.kRequiredRuns
                      ? "only "
                          + p.runsCompleted()
                          + " of the "
                          + SimPromotion.kRequiredRuns
                          + " perturbed runs finished."
                      : "at least one perturbed run was not contained. "
                          + p.describe(unitLabel(target)))
              + " Run it in the simulator again.");
    }
    return Optional.empty();
  }

  /**
   * Forget every recorded promotion. For tests, and for a team that has changed a mechanism and
   * wants the gate to apply again immediately.
   */
  public static void resetForTest() {
    s_promotions.clear();
  }

  private static String unitLabel(TuningTarget target) {
    return target.siDomain() == null ? "" : target.siDomain().label();
  }
}

package org.rootstock.tuning.wizard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.tuning.wizard.steps.BreakawayRampStep;
import org.rootstock.tuning.wizard.steps.HoldBisectionStep;
import org.rootstock.tuning.wizard.steps.LqrSuggestStep;
import org.rootstock.tuning.wizard.steps.PredictStep;
import org.rootstock.tuning.wizard.steps.PreflightStep;
import org.rootstock.tuning.wizard.steps.StepResponseStep;
import org.rootstock.tuning.wizard.steps.SysIdSweepStep;

/**
 * The built-in recipes. Two of them in this milestone: elevator and flywheel.
 *
 * <p>The other four — arm, turret, drive and steer — are a later milestone and are <em>deliberately
 * absent</em> rather than half-present. A recipe that exists but has never been run against a real
 * mechanism is worse than one that does not exist, because a student cannot tell the difference
 * until it is moving their arm. {@link #forArchetype(MechanismArchetype, TuningRecipe.Mode)} says so
 * by name when asked for one that has not shipped.
 *
 * <p><b>The canonical order, in both recipes and both modes:</b>
 *
 * <pre>
 *   kS  -&gt;  kV  -&gt;  kA  -&gt;  kG  -&gt;  kP  -&gt;  kD          (feedforward before feedback, always)
 * </pre>
 *
 * <p><b>Gravity mechanisms need one adjustment to that, and here is why.</b> You cannot measure
 * friction on an elevator until gravity is cancelled: ramp the voltage from zero and the "motion"
 * you detect is the carriage falling, not friction breaking loose, and kS comes out about ten times
 * too large. So the elevator recipe runs a gravity <em>pre-pass</em> before the friction step, uses
 * it to cancel gravity during the friction and speed measurements, and then re-solves kG jointly
 * with kS, kV and kA in the full fit. The student still learns the gains in canonical order; the
 * pre-pass is presented as part of getting ready — "first we work out how hard gravity is pulling,
 * so the rest of the measurements are not fighting it."
 *
 * <p><b>No recipe ever produces an integral gain.</b> A constant offset means a feedforward term is
 * missing, and an integrator hides that instead of fixing it. kI is available in the review screen,
 * behind its lesson, and nowhere else.
 */
public final class Recipes {

  /** The elevator recipe's version, which a simulation promotion record is keyed on. */
  public static final String kElevatorVersion = "elevator/1";

  /** The flywheel recipe's version. */
  public static final String kFlywheelVersion = "flywheel/1";

  private Recipes() {}

  /**
   * The archetypes a recipe ships for today.
   *
   * @return an unmodifiable set
   */
  public static Set<MechanismArchetype> supported() {
    return Set.copyOf(EnumSet.of(MechanismArchetype.ELEVATOR, MechanismArchetype.FLYWHEEL));
  }

  /**
   * Whether a recipe ships for an archetype.
   *
   * @param archetype the mechanism family
   * @return true if {@link #forArchetype(MechanismArchetype, TuningRecipe.Mode)} will succeed
   */
  public static boolean supports(MechanismArchetype archetype) {
    return supported().contains(archetype);
  }

  /**
   * The recipe for an archetype, in a mode.
   *
   * @param archetype the mechanism family
   * @param mode teaching or express
   * @return the recipe
   * @throws IllegalArgumentException if no recipe ships for that archetype yet
   */
  public static TuningRecipe forArchetype(MechanismArchetype archetype, TuningRecipe.Mode mode) {
    if (archetype == MechanismArchetype.ELEVATOR) {
      return elevator(mode);
    }
    if (archetype == MechanismArchetype.FLYWHEEL) {
      return flywheel(mode);
    }
    throw new IllegalArgumentException(
        "Rootstock does not ship a tuning recipe for "
            + archetype
            + " yet. The two that exist are ELEVATOR and FLYWHEEL. A recipe that has never been "
            + "run against a real mechanism is worse than no recipe, because a student cannot tell "
            + "the difference until it is moving their arm. Fix: tune this mechanism with the "
            + "sliders for now, or build a recipe by hand with TuningRecipe.of(...) if you know "
            + "what its steps should be.");
  }

  /**
   * The elevator recipe: position control against constant gravity.
   *
   * <p>Eleven steps in teaching mode, about a hundred seconds of motion, and four prediction
   * questions interleaved in front of the gravity, friction, kP and refinement steps. The
   * predictions add no motion and about fifteen seconds of reading, and they are the only thing in
   * this list that measures the student rather than the mechanism.
   *
   * @param mode teaching or express
   * @return the recipe
   */
  public static TuningRecipe elevator(TuningRecipe.Mode mode) {
    boolean teaching = mode == TuningRecipe.Mode.TEACHING;
    SysIdSweepStep.Session session = new SysIdSweepStep.Session();
    List<TuningStep> steps = new ArrayList<>();

    // 1. Pre-flight. Identical in both modes: express may drop teaching, never an interlock.
    steps.add(new PreflightStep());

    // 2. Gravity pre-pass, BEFORE friction. See the class javadoc for why the order looks inverted.
    HoldBisectionStep gravity = new HoldBisectionStep();
    if (teaching) {
      steps.add(PredictStep.holdingVoltsBeforeGravity(gravity));
    }
    steps.add(gravity);

    // 3. Friction, with gravity cancelled by the value step 2 just produced.
    BreakawayRampStep friction = new BreakawayRampStep();
    if (teaching) {
      steps.add(PredictStep.stictionBeforeBreakaway(friction));
    }
    steps.add(friction);

    // 4 and 5. kV from the ramps, then kA from the steps plus a joint re-solve of everything.
    steps.add(SysIdSweepStep.quasistatic(session));
    steps.add(SysIdSweepStep.dynamic(session));

    // 6 and 7. Feedback, from the numbers just measured. No motion in either.
    LqrSuggestStep kp = LqrSuggestStep.forKp();
    if (teaching) {
      steps.add(PredictStep.slidersBeforeLqr(kp));
    }
    steps.add(kp);
    steps.add(LqrSuggestStep.forKd());

    if (teaching) {
      // 8. Check and refine. Express stops here: its kP and kD are solver defaults, never checked.
      StepResponseStep refine = StepResponseStep.refine();
      steps.add(PredictStep.dampingBeforeRefine(refine));
      steps.add(refine);

      // 9. The whole safe band, twice, the way a match would use it.
      steps.add(StepResponseStep.verify());
    }

    return TuningRecipe.of(
        "elevator", kElevatorVersion, mode, MechanismArchetype.ELEVATOR, steps);
  }

  /**
   * The flywheel recipe: velocity control, no gravity.
   *
   * <p>Shorter than the elevator by exactly the gravity work, and it is worth telling the student
   * that out loud rather than silently skipping a step: "flywheels do not fight gravity, so there is
   * no kG." This and the other gravity-free archetypes are the <em>only</em> families for which a
   * kG of zero is a legitimate output — on an elevator or an arm, a zero kG means the search failed
   * and the recipe says so instead.
   *
   * <p>kD is left at zero unless the check step asks for it. A flywheel holding a steady speed has
   * nothing for a damper to do, and the reason is visible in the damping arithmetic the kP panel
   * prints: a wheel's own kV is already doing the damping.
   *
   * @param mode teaching or express
   * @return the recipe
   */
  public static TuningRecipe flywheel(TuningRecipe.Mode mode) {
    boolean teaching = mode == TuningRecipe.Mode.TEACHING;
    SysIdSweepStep.Session session = new SysIdSweepStep.Session();
    List<TuningStep> steps = new ArrayList<>();

    steps.add(new PreflightStep());

    BreakawayRampStep friction = new BreakawayRampStep();
    if (teaching) {
      steps.add(PredictStep.stictionBeforeBreakaway(friction));
    }
    steps.add(friction);

    steps.add(SysIdSweepStep.quasistatic(session));
    steps.add(SysIdSweepStep.dynamic(session));

    LqrSuggestStep kp = LqrSuggestStep.forKp();
    if (teaching) {
      steps.add(PredictStep.slidersBeforeLqr(kp));
    }
    steps.add(kp);

    if (teaching) {
      StepResponseStep refine = StepResponseStep.refine();
      steps.add(refine);
      steps.add(StepResponseStep.verify());
    }

    return TuningRecipe.of(
        "flywheel", kFlywheelVersion, mode, MechanismArchetype.FLYWHEEL, steps);
  }
}

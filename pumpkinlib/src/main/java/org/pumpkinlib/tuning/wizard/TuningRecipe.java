package org.pumpkinlib.tuning.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.pumpkinlib.control.MechanismArchetype;
import org.pumpkinlib.tuning.wizard.steps.PredictStep;
import org.pumpkinlib.tuning.wizard.steps.PreflightStep;
import org.pumpkinlib.tuning.wizard.steps.StepResponseStep;

/**
 * An ordered list of steps, a name, and a mode. That is the whole of it — recipes are data.
 *
 * <p><b>Two modes ship, and the difference between them is words, never interlocks.</b>
 *
 * <p>{@link Mode#TEACHING} is the full walk-through: every lesson, every prediction, every review
 * screen. On an elevator that is about eight minutes with the reading.
 *
 * <p>{@link Mode#EXPRESS} is identification only — the gravity pre-pass, kS, kV and kA, end to end
 * behind a single narration screen and a single held trigger, about ninety seconds. It exists
 * because the honest arithmetic is brutal: eight minutes times four mechanisms, on one shared robot,
 * after every mechanical change, is not a teaching win. It is a queue. And a teaching artifact
 * nobody has time to run teaches nothing.
 *
 * <p><b>What express removes is words on a screen and the student pressing A.</b> The mechanical
 * health check still runs and still blocks. The supervisor's preconditions still throw. The
 * simulation gate still applies. Test mode is still required. The report says which mode ran, in its
 * first line, so a mentor reading a pull request can tell the difference without asking.
 *
 * <p><b>Which mode is the default depends on where you are running.</b> In simulation it is always
 * {@code TEACHING} and that is not overridable per mechanism — simulation is free, unqueued, and
 * cannot break anything, so it is where the learning is supposed to happen. On hardware the wizard
 * asks once, the first time a mechanism is tuned, and remembers the answer after that.
 */
public final class TuningRecipe {

  /** How much of the teaching a recipe carries. */
  public enum Mode {
    /** Every lesson, every prediction, every review screen. About eight minutes for an elevator. */
    TEACHING,
    /** Identification only: gravity pre-pass, kS, kV, kA. One narration screen. About 90 seconds. */
    EXPRESS
  }

  private final String m_name;
  private final String m_version;
  private final Mode m_mode;
  private final MechanismArchetype m_archetype;
  private final List<TuningStep> m_steps;

  private TuningRecipe(
      String name,
      String version,
      Mode mode,
      MechanismArchetype archetype,
      List<TuningStep> steps) {
    m_name = name;
    m_version = version;
    m_mode = mode;
    m_archetype = archetype;
    m_steps = List.copyOf(steps);
  }

  /**
   * Build a recipe by hand. Rarely needed; the two shipped recipes are in {@link Recipes}.
   *
   * @param name a short human name, for example "elevator"
   * @param version the recipe version, which the simulation promotion record is keyed on
   * @param mode which mode this list of steps represents
   * @param archetype the mechanism family it applies to
   * @param steps the steps, in order
   * @return the recipe
   */
  public static TuningRecipe of(
      String name, String version, Mode mode, MechanismArchetype archetype, List<TuningStep> steps) {
    return new TuningRecipe(name, version, mode, archetype, steps);
  }

  /**
   * The full teaching recipe for an archetype.
   *
   * @param archetype the mechanism family
   * @return the recipe
   * @throws IllegalArgumentException if no recipe ships for that archetype yet
   */
  public static TuningRecipe teaching(MechanismArchetype archetype) {
    return Recipes.forArchetype(archetype, Mode.TEACHING);
  }

  /**
   * The identification-only recipe for an archetype.
   *
   * <p>No prediction step, no per-step review, no refinement pass. kP and kD come straight from the
   * solver at the archetype defaults and are labelled as such — they were never checked against a
   * real step response on hardware, and the report says so.
   *
   * @param archetype the mechanism family
   * @return the recipe
   * @throws IllegalArgumentException if no recipe ships for that archetype yet
   */
  public static TuningRecipe express(MechanismArchetype archetype) {
    return Recipes.forArchetype(archetype, Mode.EXPRESS);
  }

  /**
   * The recipe's short name.
   *
   * @return the name
   */
  public String name() {
    return m_name;
  }

  /**
   * The recipe version, which a simulation promotion record is keyed on.
   *
   * @return the version string
   */
  public String version() {
    return m_version;
  }

  /**
   * Which mode this recipe carries.
   *
   * @return the mode
   */
  public Mode mode() {
    return m_mode;
  }

  /**
   * The mechanism family this recipe applies to.
   *
   * @return the archetype
   */
  public MechanismArchetype archetype() {
    return m_archetype;
  }

  /**
   * The steps, in order.
   *
   * @return an unmodifiable list
   */
  public List<TuningStep> steps() {
    return m_steps;
  }

  /**
   * How many steps there are, for the "step 3 of 9" line.
   *
   * @return the count
   */
  public int size() {
    return m_steps.size();
  }

  /**
   * The step at an index.
   *
   * @param index zero-based
   * @return the step
   */
  public TuningStep step(int index) {
    return m_steps.get(index);
  }

  /**
   * The pre-flight step, which every recipe in every mode carries.
   *
   * @return the step, or empty for a hand-built recipe that omitted it
   */
  public Optional<TuningStep> preflight() {
    for (TuningStep s : m_steps) {
      if (s instanceof PreflightStep) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether this recipe asks the student to predict anything.
   *
   * <p>The express contract in one method: express has no prediction steps and no refinement pass,
   * and it has exactly the same pre-flight. A test can check all three of those against this and
   * {@link #hasRefinementPass()} without reaching into the step list.
   *
   * @return true if any step asks a question
   */
  public boolean hasPredictions() {
    for (TuningStep s : m_steps) {
      if (s instanceof PredictStep) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether this recipe runs the bounded refinement loop on hardware.
   *
   * @return true if any step is a refinement pass
   */
  public boolean hasRefinementPass() {
    for (TuningStep s : m_steps) {
      if (s instanceof StepResponseStep
          && ((StepResponseStep) s).mode() == StepResponseStep.Mode.REFINE) {
        return true;
      }
    }
    return false;
  }

  /**
   * A copy with one extra step appended.
   *
   * @param step the step
   * @return a new recipe
   */
  public TuningRecipe plus(TuningStep step) {
    List<TuningStep> steps = new ArrayList<>(m_steps);
    steps.add(step);
    return new TuningRecipe(m_name, m_version, m_mode, m_archetype, steps);
  }

  /**
   * A one-line description for the report's first line.
   *
   * @return the description
   */
  public String describe() {
    if (m_mode == Mode.EXPRESS) {
      return "Mode: EXPRESS - identification only. kP and kD are solver defaults and were never "
          + "verified against a step response on hardware.";
    }
    return "Mode: TEACHING - the full walk-through, with predictions and a bounded refinement pass.";
  }
}

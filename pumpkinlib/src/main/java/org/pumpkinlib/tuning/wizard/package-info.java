/**
 * The guided tuning wizard — the part of PumpkinLib that exists to <em>teach</em>.
 *
 * <p>Everything else in {@code org.pumpkinlib.tuning} produces numbers. This package produces
 * students. It is a state machine ({@link org.pumpkinlib.tuning.wizard.TuningWizard}) over an
 * ordered list of small strategy objects ({@link org.pumpkinlib.tuning.wizard.TuningStep}) that is
 * itself just data ({@link org.pumpkinlib.tuning.wizard.TuningRecipe}). Nothing here is
 * mechanism-specific: the built-in recipes are lists.
 *
 * <p><b>Three properties are non-negotiable and are worth stating at the package level, because
 * every class here is written to preserve one of them.</b>
 *
 * <ol>
 *   <li><b>Feedforward before feedback, always.</b> The canonical order is kS, kV, kA, kG, kP, kD.
 *       A big enough kP will drag almost anything to almost anywhere, and it does it by being wrong
 *       first and then reacting — which is exactly what overshoot and ringing are. See {@link
 *       org.pumpkinlib.tuning.wizard.Lessons#WHY_FEEDFORWARD_FIRST}.
 *   <li><b>Every volt goes through {@link org.pumpkinlib.control.TuningSupervisor}.</b> No class in
 *       this package calls {@code TuningTarget.setVoltage}, and ArchUnit rule 6 proves it. A step
 *       that wants motion asks the supervisor, which owns the soft limits, the timeout, the
 *       held-enable interlock and the twelve abort conditions.
 *   <li><b>The student predicts before the robot moves.</b> {@link
 *       org.pumpkinlib.tuning.wizard.steps.PredictStep} is not decoration. Without it a student can
 *       finish every recipe by pressing A eleven times, and neither the wizard nor a mentor can tell
 *       the difference between that and learning. The {@code Predictions: n/m} line in the report is
 *       the only measurement in this whole library that measures the <em>person</em>.
 * </ol>
 *
 * <p><b>Two recipes ship in this milestone:</b> {@code ELEVATOR} and {@code FLYWHEEL}. Arm, turret,
 * drive and steer are a later milestone and are deliberately absent rather than half-present — see
 * {@link org.pumpkinlib.tuning.wizard.Recipes#supported()}.
 */
package org.pumpkinlib.tuning.wizard;

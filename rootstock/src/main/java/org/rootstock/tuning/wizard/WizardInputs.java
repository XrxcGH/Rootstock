package org.rootstock.tuning.wizard;

import java.util.OptionalInt;

/**
 * The six buttons and one trigger the wizard needs, with no gamepad type in the signature.
 *
 * <p>{@link TuningWizard#using(edu.wpi.first.wpilibj2.command.button.CommandXboxController)} builds
 * one of these from a controller and is what almost every team uses. This interface exists for the
 * other two cases: a team on a state-based robot with no {@code CommandScheduler}, and a test that
 * needs to drive the whole state machine deterministically without a HAL.
 *
 * <p><b>Everything here is a level, not an edge.</b> The wizard does its own rising-edge detection,
 * so an implementation just reports what the button is doing right now. That is the shape that a
 * test can drive from a field and a gamepad can drive from a poll, without either one having to
 * remember anything.
 */
public interface WizardInputs {

  /**
   * Whether the student is physically holding the enable control right now.
   *
   * <p>This is the one input that is read as a level on every single loop rather than as an edge,
   * because releasing it must neutral the mechanism on the very next {@code check()}. A latching
   * toggle would satisfy the wizard while nobody was holding anything, which is the failure this
   * whole interlock exists to prevent.
   *
   * @return true while the enable control is held
   */
  boolean enableHeld();

  /**
   * Accept: commit the current step's result and advance.
   *
   * @return true while the accept control is pressed
   */
  boolean accept();

  /**
   * Retry: re-run the current step from scratch, discarding its result.
   *
   * @return true while the retry control is pressed
   */
  boolean retry();

  /**
   * Back: return to the previous step. That step's gain reverts to what it was before.
   *
   * @return true while the back control is pressed
   */
  boolean back();

  /**
   * Skip: move on without changing this step's gain. Logged as a warning in the report, because a
   * skipped kV is the most likely explanation for a mechanism that will not tune later.
   *
   * @return true while the skip control is pressed
   */
  boolean skip();

  /**
   * Abort everything: neutral the mechanism and revert <em>all</em> gains to the values they had
   * when the session began — not when the step began.
   *
   * @return true while the abort-all control is pressed
   */
  boolean abortAll();

  /**
   * The student's answer to a {@link org.rootstock.tuning.wizard.steps.PredictStep}, as an option
   * index of 0, 1 or 2.
   *
   * <p>Empty means "no answer offered this loop", which is the normal case: the wizard also accepts
   * an answer written to {@code /RootstockTuner/predict/answer} by a dashboard combo box, so a
   * student on a laptop and a student on a gamepad can both answer.
   *
   * @return the selected option index, or empty
   */
  OptionalInt predictionSelection();

  /**
   * A manual nudge, from -1 to 1, honoured only in {@link WizardState#REVIEW} and only at low
   * output, so a student can reposition a mechanism between attempts without leaving the wizard.
   *
   * @return the nudge command, defaulting to zero for implementations that offer none
   */
  default double manualNudge() {
    return 0.0;
  }

  /**
   * The Driver Station port this input source occupies, or -1 if it is not a physical controller.
   *
   * <p>The wizard uses this for exactly one check: whether it is sharing a port with the driver.
   * See {@link TuningWizard#acknowledgeSharedController(String)}.
   *
   * @return the port, or -1
   */
  default int port() {
    return -1;
  }
}

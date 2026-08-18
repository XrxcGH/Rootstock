package org.pumpkinlib.tuning.wizard;

/**
 * Where the wizard is, and — the part that matters — whether it is allowed to move anything.
 *
 * <p>Motion is possible in exactly two of these states, and both of them additionally require
 * {@link org.pumpkinlib.control.TuningSupervisor#isArmed()}. Publishing the state as a first-class
 * enum rather than a string is what lets a dashboard, a test and a student all agree on that claim.
 */
public enum WizardState {

  /** No mechanism selected. Nothing moves. */
  IDLE(false),

  /**
   * Mechanism selected, recipe loaded, the current step's lesson on screen. Nothing moves.
   *
   * <p>An {@code arm()} that threw lands back here with its message published, which is why the
   * hard throw in {@link org.pumpkinlib.control.TuningSupervisor#arm()} is a usable safety feature
   * rather than a crash: eleven precise sentences reach the student instead of a stack trace.
   */
  READY(false),

  /**
   * {@link org.pumpkinlib.control.MechanicalHealthCheck} is running, once per session, before the
   * first real step. Low-power motion, supervised.
   */
  PREFLIGHT(true),

  /**
   * The step is explained, {@link TuningStep#willDo()} is on screen, the supervisor is armed and
   * the wizard is waiting for the student to hold the trigger. Nothing moves <em>yet</em>.
   */
  ARMED(false),

  /** The step is executing. This is where the mechanism moves. */
  RUNNING(true),

  /**
   * The step finished and its {@link StepResult} is on screen with the plots. The student presses
   * accept, retry, back or skip.
   */
  REVIEW(false),

  /**
   * Something tripped. The reason is on screen and the gains have been reverted to the values they
   * had when this step was armed.
   */
  ABORTED(false),

  /** The recipe is complete, the report is generated, and the gains are staged for saving. */
  DONE(false),

  /**
   * {@code PumpkinLog.isReplay()} is true. A replay re-runs recorded inputs; commanding a motor
   * from one would be commanding a motor from a log file.
   */
  DISABLED_REPLAY(false);

  private final boolean m_motionAllowed;

  WizardState(boolean motionAllowed) {
    m_motionAllowed = motionAllowed;
  }

  /**
   * Whether the mechanism may move in this state.
   *
   * <p>True for exactly {@link #PREFLIGHT} and {@link #RUNNING}. This is a necessary condition and
   * never a sufficient one — the supervisor's arm state and its held-enable check both still apply.
   *
   * @return true if this state permits commanded voltage
   */
  public boolean motionAllowed() {
    return m_motionAllowed;
  }
}

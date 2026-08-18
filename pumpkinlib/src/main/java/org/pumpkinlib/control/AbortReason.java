package org.pumpkinlib.control;

/**
 * Why a tuning routine stopped moving — exactly twelve values, one per abort condition.
 *
 * <p><b>Why an enum and not a string.</b> A routine that stops without naming <em>which</em> limit
 * stopped it teaches nothing. "It stopped" sends a student looking at their gains; "the elevator
 * reached 1.330 m and the safe band ends at 1.327 m" sends them to the one number that is wrong.
 * Every value here carries a sentence a fourteen-year-old can act on, and {@link TuningSupervisor}
 * fills the measured numbers into it.
 *
 * <p><b>Why exactly twelve.</b> Conditions 1 through 11 are checked every loop by {@link
 * TuningSupervisor#check()}, in the order they are declared here, and the first one that trips
 * wins. The twelfth, {@link #UNSTABLE_RESPONSE}, is not a loop check at all — it is raised by the
 * step-response refinement step when a response is growing instead of settling. Fit failures are
 * deliberately <em>not</em> in this enum: a regression that cannot be solved did not stop any
 * motion, and putting those two outcomes here is what made an earlier count of "all twelve abort
 * conditions" unreconcilable. They live in {@code org.pumpkinlib.tuning.sysid.FitFailure}.
 */
public enum AbortReason {

  /** 1. The human let go of the enable trigger. By far the most common abort, and not a fault. */
  ENABLE_RELEASED(
      1,
      true,
      "You let go of the trigger. Nothing is broken - hold it again and press Retry."),

  /** 2. The driver station disabled the robot mid-routine. */
  DISABLED(2, true, "Robot disabled. The routine stopped where it was."),

  /** 3. The mechanism left the supervisor's position band. */
  LIMIT_REACHED(
      3,
      true,
      "The mechanism reached the edge of the safe band the tuner is allowed to move inside."),

  /**
   * 4. At the current speed the mechanism would leave the band within the look-ahead window, so it
   * was stopped before it got there rather than after.
   */
  LIMIT_APPROACH(
      4,
      true,
      "Stopped early: at this speed the mechanism would hit the edge of the safe band before it "
          + "could be stopped."),

  /** 5. Faster than this mechanism should ever go, which is almost always a wrong gear ratio. */
  OVERSPEED(
      5,
      true,
      "Stopped: this is faster than the mechanism should ever go. Check your gear ratio."),

  /** 6. Stator current above the ceiling for the whole holdoff window — a jam or a hard stop. */
  OVERCURRENT(
      6,
      true,
      "Stopped: the motor drew more current than the limit for the whole holdoff window. "
          + "Something is jammed or the mechanism is against a hard stop."),

  /** 7. Real voltage applied and nothing moved. Breaker, CAN id, or a hard stop. */
  STALLED(
      7,
      true,
      "Stopped: voltage was applied and nothing moved. Check the breaker, the CAN ID, and whether "
          + "it is already against a hard stop."),

  /** 8. Positive voltage produced negative motion. Fix the invert; never tune around it. */
  WRONG_DIRECTION(
      8,
      true,
      "Stopped: positive voltage is making this move in the negative direction. Fix the invert "
          + "before tuning."),

  /** 9. The routine ran longer than its wall-clock budget. */
  TIMEOUT(
      9,
      true,
      "Stopped: this step took longer than expected. Usually the mechanism is not reaching the "
          + "speed we asked for."),

  /** 10. A measurement came back NaN or infinite. Every interlock downstream of it is blind. */
  SENSOR_FAULT(
      10,
      true,
      "Stopped: a sensor returned an invalid value. Check the sensor wiring."),

  /**
   * 11. Position and velocity disagree about whether the mechanism is moving — the signature of a
   * frozen signal, which is what {@code optimizeBusUtilization()} with no preceding {@code
   * setUpdateFrequency} produces. A tuner that fits a model to a frozen signal produces confident
   * garbage.
   */
  SENSOR_INCONSISTENT(
      11,
      true,
      "Stopped: position and velocity disagree about whether this is moving. One of them is "
          + "stale - check optimizeBusUtilization and your signal update rates."),

  /**
   * 12. Not a loop check. Raised by the step-response refinement step when a response is diverging.
   */
  UNSTABLE_RESPONSE(
      12,
      false,
      "Stopping. The response was growing instead of settling, which is how mechanisms break. "
          + "kP has been cut and the routine is disarmed. Press Retry when you are ready.");

  private final int m_order;
  private final boolean m_loopChecked;
  private final String m_summary;

  AbortReason(int order, boolean loopChecked, String summary) {
    m_order = order;
    m_loopChecked = loopChecked;
    m_summary = summary;
  }

  /**
   * The position of this condition in the every-loop check order, 1 through 12.
   *
   * <p>Published so a student reading two aborts in a log can see which one had priority, and so a
   * test can assert the order is the documented one rather than the declaration accident.
   *
   * @return 1 through 12
   */
  public int order() {
    return m_order;
  }

  /**
   * Whether {@link TuningSupervisor#check()} can raise this reason.
   *
   * @return true for conditions 1 through 11, false for {@link #UNSTABLE_RESPONSE}
   */
  public boolean loopChecked() {
    return m_loopChecked;
  }

  /**
   * The generic student-facing sentence for this reason, with no measured numbers in it.
   *
   * <p>{@link TuningSupervisor} produces a more specific sentence with the actual values whenever it
   * has them; this is the fallback and the text a test can pin.
   *
   * @return one sentence, never blank
   */
  public String summary() {
    return m_summary;
  }
}

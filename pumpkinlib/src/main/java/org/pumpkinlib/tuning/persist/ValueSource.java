package org.pumpkinlib.tuning.persist;

/**
 * Where a tuned value came from.
 *
 * <p>This enum is the answer to the question a pit crew actually asks: <i>"why is my gain not what I
 * typed?"</i> Every resolved value carries one of these, it appears in the NT metadata blob, in the
 * persisted {@code gains.json}, and in the UI — so "where did this number come from" is answerable
 * from a dashboard alone, without a log pull and without reading the library's source.
 *
 * <p>The first four are the four load-order tiers of {@link TunedValueStore}, lowest priority first.
 * The last four record which wizard step produced a value, which is what makes a fitted gain
 * distinguishable from a hand-typed one six weeks later.
 */
public enum ValueSource {

  /** Tier 1: the {@code Gains} passed to {@code ControlConfig} in the team's own config class. */
  CODE_DEFAULT,

  /** Tier 2: {@code src/main/deploy/pumpkin/gains.json} — the team's committed, reviewed answer. */
  DEPLOY_FILE,

  /** Tier 3: {@code Platform.persistentDir()/pumpkin/gains.json} — the "we tuned it Saturday" file. */
  ROBOT_FILE,

  /** Tier 4: a live NT edit under {@code /Tuning/}. Ignored entirely under FMS. */
  DASHBOARD,

  /** Fitted by the wizard's streaming ordinary-least-squares step (kS, kV, kA). */
  WIZARD_OLS,

  /** Found by the wizard's gravity bisection (kG). */
  WIZARD_BISECTION,

  /** Suggested by the wizard's LQR step (kP, kD). */
  WIZARD_LQR,

  /** Adjusted by the wizard's closed-loop refinement step. */
  WIZARD_REFINE;

  /**
   * Whether this source is one of the four load-order tiers rather than a wizard step.
   *
   * @return true for the first four constants
   */
  public boolean isLoadTier() {
    return ordinal() <= DASHBOARD.ordinal();
  }

  /**
   * A sentence a fourteen-year-old can act on.
   *
   * @return e.g. {@code "the committed deploy file (src/main/deploy/pumpkin/gains.json)"}
   */
  public String explain() {
    switch (this) {
      case CODE_DEFAULT:
        return "the value compiled into the jar (your own config class)";
      case DEPLOY_FILE:
        return "the committed deploy file (src/main/deploy/pumpkin/gains.json)";
      case ROBOT_FILE:
        return "the file saved on this robot (survives reboot and redeploy)";
      case DASHBOARD:
        return "a live dashboard edit under /Tuning/";
      case WIZARD_OLS:
        return "the wizard's least-squares fit";
      case WIZARD_BISECTION:
        return "the wizard's gravity bisection";
      case WIZARD_LQR:
        return "the wizard's LQR suggestion";
      case WIZARD_REFINE:
        return "the wizard's closed-loop refinement";
      default:
        return name();
    }
  }
}

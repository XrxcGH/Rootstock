package org.rootstock.control;

/**
 * What a motor does when nothing is commanding it.
 *
 * <p>Two values, hoisted out of the motor IO layer into {@code org.rootstock.control} so the tuning
 * seam can name it without depending on the hardware layer.
 *
 * <p>It is here rather than being an implementation detail because of a real measurement bug: the
 * gravity-identification probe releases a mechanism for half a second and watches which way it
 * falls. On a brake-mode arm or elevator — which is the normal, correct configuration — nothing
 * falls, and a probe that does not know the idle mode concludes "there is nothing for kG to hold"
 * and sets kG to zero. Everything downstream then measures gravity as friction. The probe has to be
 * able to <em>ask</em>, and to temporarily override.
 */
public enum NeutralMode {
  /**
   * The motor shorts its terminals when idle, resisting motion.
   *
   * <p>The correct setting for anything gravity loads. It is also what makes a gravity probe read
   * nothing unless the probe overrides it first.
   */
  BRAKE,

  /**
   * The motor freewheels when idle.
   *
   * <p>Correct for flywheels and for drivetrains a team wants to be pushable. On an arm or an
   * elevator, coasting means falling, which is why a tuning routine that cannot read the idle mode
   * back refuses to release the mechanism rather than guessing.
   */
  COAST
}

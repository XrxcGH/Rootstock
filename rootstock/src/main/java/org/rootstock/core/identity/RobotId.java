package org.rootstock.core.identity;

/**
 * Which physical robot this code is running on.
 *
 * <p>One codebase serves the competition bot, the practice bot, a proto chassis and simulation.
 * Without an identity, the only mechanisms teams have are copy-pasting a branch per robot (which
 * diverges within a week) or commenting constants in and out before every deploy (which is how a
 * practice-bot gear ratio ends up on the comp bot at 8:40 on Saturday). {@link RobotIdentity}
 * resolves this value once at boot from a robot-side fact, and
 * {@link Overlay} turns it into per-robot constants that the compiler checks.
 *
 * <p><b>Teams extend this set by adding constants to their own enum.</b> Nothing in Rootstock
 * hardcodes the members of this enum by name except {@link #SIM}, which the simulation strategy
 * and the {@code rootstockCheckDeploy} build gate both reason about explicitly. If your team has two
 * practice bots, the honest answer is a fork of this enum in your own package plus a
 * {@code Map<YourId, T>}; the four members here are the shapes every FRC team has.
 *
 * @see RobotIdentity
 * @see Overlay
 */
public enum RobotId {
  /** The robot that plays matches. The safe fallback when nothing else matched. */
  COMP,

  /** The second robot, usually lighter, usually with different gearing and lower current limits. */
  PRACTICE,

  /** A test chassis or a single-mechanism rig. Rarely complete. */
  PROTO,

  /**
   * Desktop simulation. Never a real robot.
   *
   * <p>{@code rootstockCheckDeploy} hard-fails a deploy whose declared fallback is this value, with
   * no override, because a real robot running simulation constants has the wrong gearing, the wrong
   * limits and the wrong offsets — see {@code design/06} §7.4.
   */
  SIM
}

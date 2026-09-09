package org.rootstock.config;

/**
 * Which end of travel a hard stop sits at.
 *
 * <p>"Forward" and "reverse" are defined in <em>output</em> terms, after every inversion: forward is
 * the direction in which the mechanism's user-unit position <em>increases</em> — up for an elevator,
 * towards the larger angle for an arm. That definition is the one the device's own hardware limit
 * switch and soft limit configuration use, so a config that reads correctly here also configures
 * correctly on the motor controller.
 *
 * <p>A hard stop is a <em>physical</em> end of travel, sensed by a switch. It is not a soft limit: a
 * soft limit is a number Rootstock clamps against in Java <em>and</em> writes to the device, and it
 * should always sit inside the hard stops.
 *
 * @see PositionLimits#usesMotorLimit(HardStop)
 */
public enum HardStop {

  /** The end of travel in the increasing-position direction: the top of an elevator. */
  FORWARD,

  /** The end of travel in the decreasing-position direction: the bottom of an elevator. */
  REVERSE;

  /**
   * Whether this is the increasing-position end.
   *
   * @return true for {@link #FORWARD}
   */
  public boolean isForward() {
    return this == FORWARD;
  }

  /**
   * The other end of travel.
   *
   * @return {@link #REVERSE} for {@link #FORWARD} and vice versa
   */
  public HardStop opposite() {
    return this == FORWARD ? REVERSE : FORWARD;
  }

  /**
   * A phrase naming this end in the terms a student sees on the robot.
   *
   * @return for example {@code "forward (increasing position)"}
   */
  public String describe() {
    return this == FORWARD ? "forward (increasing position)" : "reverse (decreasing position)";
  }
}

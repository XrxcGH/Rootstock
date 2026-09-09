package org.rootstock.control;

/**
 * How gravity loads a mechanism — and therefore what the {@code kG} gain in {@code Gains} means and
 * which feedforward implements it.
 *
 * <p><b>This is derived from the {@code Axis}, never configured beside it</b> (decision D2a). A
 * linear axis is loaded by a constant force whichever way it is pointing; a rotary axis is loaded by
 * a force proportional to the cosine of its angle from horizontal. Those are geometric facts, not
 * preferences, so {@code Axis.gravity()} produces this value and a config that let you set it
 * independently would only be offering you a way to disagree with your own geometry.
 *
 * <p>The reason it is a single enum shared by both vendors is that REVLib 2026 grew {@code kG} and
 * {@code kCos} on the controller — the exact split Phoenix has always expressed as {@code
 * Elevator_Static} and {@code Arm_Cosine}. One enum maps cleanly onto both, and onto the RIO-side
 * feedforward classes as well.
 *
 * <p><b>Package note.</b> {@code design/01} §2's package table moves this type out of {@code
 * org.rootstock.units} and into {@code org.rootstock.control} under D2a/D5, which is where it
 * lives. It is defined here because {@code Axis.gravity()} cannot compile without it; the rest of
 * {@code org.rootstock.control} — {@code Gains}, {@code ControlLocation}, {@code Controllers},
 * {@code TuningTarget} — is owned by {@code design/05} and is not defined here.
 */
public enum GravityMode {
  /**
   * No gravity term at all. Turrets, rollers, flywheels, and any axis whose load does not change
   * with position. {@code kG} must be zero, and a non-zero {@code kG} with this mode is a config
   * warning rather than a silent constant push.
   *
   * <p>Backed by {@code SimpleMotorFeedforward} on the RIO, and by leaving the vendor's gravity
   * feedforward off entirely.
   */
  NONE,

  /**
   * A constant {@code kG} volts, always in the same direction. Elevators, linear slides, cascades —
   * anything whose weight pulls the same way at every position.
   *
   * <p>Maps to Phoenix {@code GravityTypeValue.Elevator_Static}, to REVLib's {@code
   * FeedForwardConfig.kG}, and to {@code ElevatorFeedforward} on the RIO.
   */
  CONSTANT,

  /**
   * {@code kG * cos(position - horizontalReference)}. Arms, pivots, wrists, hoods — anything whose
   * effective moment arm shrinks as it swings toward vertical.
   *
   * <p>The {@code horizontalReference} is the whole game: this term is only correct when the
   * library knows the position at which the mechanism is horizontal, which is why {@code
   * RotaryAxis.arm(...)} demands it at construction rather than assuming zero. Maps to Phoenix
   * {@code GravityTypeValue.Arm_Cosine} plus a <b>negated</b> {@code
   * Slot0Configs.GravityArmPositionOffset}, to REVLib's {@code kCos}, and to {@code ArmFeedforward}
   * (which contractually wants <i>radians from horizontal</i>) on the RIO.
   */
  COSINE
}

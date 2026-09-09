package org.rootstock.control;

import java.util.function.BooleanSupplier;

/**
 * How a mechanism knows where it is — in the specific sense a safety supervisor cares about: <b>can
 * the reported position be trusted enough to arm a routine that commands raw voltage?</b>
 *
 * <p>This is deliberately <em>not</em> the config layer's {@code FeedbackSpec}, which answers a
 * different question — which sensor is wired where. "Which sensor" and "is the number trustworthy
 * right now" are two different questions, and giving them one name is the bug class this library
 * exists to delete. The config layer maps one onto the other in a single total function; the tuning
 * seam names only this one, and could not name the other anyway, because {@code
 * org.rootstock.config} sits above this package in the dependency graph.
 *
 * <p>Every position-based abort and every gravity-shaped voltage command is computed against the
 * reported position. A mechanism that was moved by hand while disabled, and whose "homing" was a
 * boot-time assumption, reports a position that is simply wrong — and then every interlock is inert
 * while a routine commands voltage against a fictional angle.
 */
public sealed interface PositionReference {

  /**
   * Only the motor's internal rotor sensor. Zero is wherever the robot happened to boot.
   *
   * <p>Perfectly good for a velocity mechanism and for relative moves. Not a position reference a
   * tuning routine will accept for a position archetype without a completed homing routine.
   */
  record RotorOnly() implements PositionReference {}

  /**
   * A homing routine ran and completed since the last power cycle — a limit switch, a hard-stop
   * current spike, or a mechanical index.
   *
   * @param completedThisPowerCycle a <b>live</b> supplier, not a flag captured at construction: the
   *     supervisor asks again at arm time, because a homing that completed twenty minutes and one
   *     brownout ago is not a homing that completed
   */
  record HomedAgainstSwitch(BooleanSupplier completedThisPowerCycle)
      implements PositionReference {}

  /**
   * A true absolute sensor: CANcoder, through-bore absolute encoder, duty-cycle encoder,
   * potentiometer.
   *
   * @param sensorDescription what it is and where, for the boot dump and for error text — a student
   *     reading "Absolute (CANcoder 31 on the wrist)" can go and look at it
   */
  record Absolute(String sensorDescription) implements PositionReference {}

  /**
   * An absolute sensor fused with the rotor for resolution, such as a Phoenix 6 fused CANcoder.
   *
   * @param sensorDescription what it is and where
   */
  record FusedAbsolute(String sensorDescription) implements PositionReference {}

  /**
   * "Assume we booted at this position."
   *
   * <p>Entirely legal for robot code — a wrist that always starts stowed is a real and reasonable
   * design. It is <b>not</b> a position reference a tuning routine will accept for a position
   * archetype, because the assumption is exactly what fails when someone bumps the mechanism in the
   * pit, and the failure is silent.
   *
   * @param assumedSi the position assumed at boot, in metres or radians
   */
  record AssumeAtBoot(double assumedSi) implements PositionReference {}

  /**
   * True when this reference comes from a sensor that reads the mechanism's real position on power
   * up, with no homing routine and no assumption.
   *
   * @return true for {@link Absolute} and {@link FusedAbsolute}
   */
  default boolean isAbsolute() {
    return this instanceof Absolute || this instanceof FusedAbsolute;
  }

  /**
   * True when the reported position can be trusted right now.
   *
   * <p>Absolute references are always trustworthy; a homed one is trustworthy exactly while its
   * supplier says the homing routine has completed this power cycle; rotor-only and boot-assumption
   * references never are, in this sense.
   *
   * @return whether a supervisor may arm a voltage-commanding routine against this reference
   */
  default boolean isTrustworthyNow() {
    if (isAbsolute()) {
      return true;
    }
    if (this instanceof HomedAgainstSwitch homed) {
      return homed.completedThisPowerCycle().getAsBoolean();
    }
    return false;
  }

  /**
   * A short human-readable name for the boot dump and for the message a student reads when a routine
   * refuses to arm.
   *
   * @return e.g. {@code "Absolute (CANcoder 31)"} or {@code "rotor only (zero is wherever the robot
   *     booted)"}
   */
  default String describe() {
    if (this instanceof Absolute absolute) {
      return "absolute (" + absolute.sensorDescription() + ")";
    }
    if (this instanceof FusedAbsolute fused) {
      return "absolute fused with the rotor (" + fused.sensorDescription() + ")";
    }
    if (this instanceof HomedAgainstSwitch homed) {
      return "homed against a switch or hard stop (currently "
          + (homed.completedThisPowerCycle().getAsBoolean() ? "homed" : "NOT homed")
          + ")";
    }
    if (this instanceof AssumeAtBoot assumed) {
      return "assumed at boot to be " + assumed.assumedSi() + " (no sensor confirms this)";
    }
    return "rotor only (zero is wherever the robot booted)";
  }
}

package org.pumpkinlib.mechanism;

/**
 * What a {@link Mechanism} is doing right now — one enum instead of the private three-boolean state
 * machines every team rewrites.
 *
 * <p><b>Why this exists.</b> Five verbatim copies of {@code private enum ControlMode {POSITION,
 * DUTY_CYCLE, NEUTRAL}} in one surveyed template repository, and three hand-rolled
 * three-boolean state machines in two others, all answer the same question and none of them answer
 * it the same way. Collapsing them into one enum is what lets {@code Pumpkin/&lt;Name&gt;/Mode} mean
 * the same thing on every mechanism of every robot, which is the precondition for a shipped
 * dashboard layout and for reading a teammate's graph without asking what the numbers mean.
 *
 * <p>This is the enum a mechanism declares to telemetry through {@code
 * TelemetryDescriptor.states(MechanismMode.class)}, so the log carries the readable name rather than
 * an ordinal that shifts the day someone inserts a constant.
 */
public enum MechanismMode {
  /**
   * No output commanded. The device is at its configured idle behaviour — brake or coast — which is
   * <i>not</i> the same as "stopped": a coast-mode arm in NEUTRAL is falling.
   */
  NEUTRAL,

  /**
   * A raw voltage or duty cycle is being commanded with no feedback loop closed around it.
   *
   * <p>Distinct from {@link #MANUAL} on purpose: MANUAL is a <i>driver</i> holding a stick and
   * carries the capture-and-hold-on-release contract of {@link ManualControl}; OPEN_LOOP is code
   * asking for volts.
   */
  OPEN_LOOP,

  /**
   * A closed loop is running against a goal — on the motor controller or on the RIO, as {@code
   * ControlLocation} decides.
   */
  CLOSED_LOOP,

  /**
   * A driver stick is directly commanding output through {@link ManualControl}.
   *
   * <p>The mode exists separately from {@link #OPEN_LOOP} because the transition <i>out</i> of it is
   * the interesting one: a position mechanism latches its current measurement as the new goal on the
   * cycle the stick returns to neutral, and a log that cannot distinguish "the driver let go" from
   * "code commanded zero volts" cannot explain the resulting motion.
   */
  MANUAL,

  /**
   * A homing routine owns the mechanism: soft limits may be suspended and the position reference is
   * not yet trustworthy.
   */
  HOMING,

  /**
   * A characterisation or self-test routine owns the mechanism.
   *
   * <p>Anything that would normally fight for control — a default command, a superstructure
   * transition — must stand down while this mode is active, and the log must say so, because a
   * trace recorded during characterisation is not a trace of normal operation.
   */
  CHARACTERIZING
}

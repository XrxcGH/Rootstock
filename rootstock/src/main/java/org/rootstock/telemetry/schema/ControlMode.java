package org.rootstock.telemetry.schema;

/**
 * The five legal values of the {@code Rootstock/<Name>/ControlMode} key.
 *
 * <p><b>Why an enum and why it lives in the telemetry schema package.</b> {@code design/04} section
 * 3.1 types this key as a {@code String} and then enumerates exactly five spellings —
 * {@code POSITION}/{@code VELOCITY}/{@code VOLTAGE}/{@code NEUTRAL}/{@code HOMING}. A {@code String}
 * parameter on the writer would let a sixth spelling reach the log, and the cost of that is not
 * cosmetic: an AdvantageScope layout and the triage workflow both filter on these literals, so
 * {@code "Homing"} and {@code "HOMING"} are two different states to every consumer while being the
 * same state to the mechanism that wrote them. The enum makes the wire value un-typoable and the
 * published type stays {@code String}, because {@link MechanismSchema#controlMode(ControlMode)}
 * publishes {@link #name()}.
 *
 * <p>It lives here rather than in the mechanism domain because the wire contract is what fixes the
 * spellings, and because the mechanism domain does not exist yet — this enum is part of what it will
 * compile against.
 *
 * <p>This is deliberately <b>not</b> {@code org.rootstock.config.OutputMode}. That enum answers
 * "what quantity does the motor controller receive" ({@code VOLTAGE} / {@code TORQUE_CURRENT}); this
 * one answers "what is the mechanism doing right now". They overlap on one constant name and mean
 * different things.
 */
public enum ControlMode {

  /** Closed-loop on position. The mechanism is running a profile or holding a setpoint. */
  POSITION,

  /** Closed-loop on velocity. A shooter or a roller at a commanded speed. */
  VELOCITY,

  /** Open loop. A voltage was commanded directly — including by a tuning session. */
  VOLTAGE,

  /** No output. Coasting or braking, per the mechanism's {@code NeutralMode}. */
  NEUTRAL,

  /**
   * Running a homing routine.
   *
   * <p>Its own mode rather than a flavour of {@code VOLTAGE} because a mechanism that is homing is
   * deliberately ignoring its soft limits, and "why did the arm drive into the hard stop" needs to
   * be answerable from the log without cross-referencing {@code Homed}.
   */
  HOMING
}

package org.rootstock.control;

/**
 * <b>Where</b> a mechanism's closed loop actually executes.
 *
 * <p>This is a first-class, logged, alert-checked decision rather than an implementation detail,
 * because it silently changes what every gain <em>means</em>. The same kP is volts per radian on the
 * roboRIO and output-per-rotation on a Kraken — a factor of 2*pi, and on a linear axis a factor of
 * the drum circumference, which for a typical elevator is more than 360x. A team that moves a loop
 * from the motor to the RIO without noticing has not made a subtle latency trade; they have made
 * their gains wrong by two orders of magnitude, and the mechanism will either not move or will
 * slam. So the location is declared, printed with provenance in the boot dump (see {@link
 * ControlLocationSource}), and carried into every conversion a {@link GainSink} performs.
 *
 * <p><b>Four values, not two.</b> Earlier revisions of the tuning design carried a separate,
 * tuning-only two-value {@code LoopLocation} alongside the config's four-value enum, and taught a
 * student both names in the same error message. Binding decision D5 deleted it. Tuning asks this
 * enum exactly one question — {@link #runsOnMotor()} — which decides the measurement-delay model
 * and whether the feedback/feedforward split can be read back from the device.
 *
 * <p><b>It is defaulted, never demanded.</b> Making {@code .controlLocation(...)} a required builder
 * call put an expert question on the fifth line of a rookie's first config, and every wrong answer
 * is silently plausible. The builder fills it in from the leader's motor spec; the field stays
 * required in the config record so it is always in the log, the snapshot and the diff.
 */
public enum ControlLocation {
  /**
   * Profile <b>and</b> feedback run on the motor controller — Phoenix Motion Magic, REV MAXMotion.
   *
   * <p>The setpoint <b>latches</b> on the device: Rootstock re-sends it on change and on a 10 Hz
   * heartbeat rather than every loop. Lowest latency, best disturbance rejection, and the loop keeps
   * running through a roboRIO loop overrun. This is the default for every smart controller.
   */
  ON_MOTOR_PROFILED,

  /**
   * Feedback runs on the motor controller with no on-device profile — Phoenix {@code
   * PositionVoltage}, REV {@code kPosition}.
   *
   * <p>The setpoint <b>latches</b>. Use this when you feed the device your own profile, or when you
   * want a pure hold with no trajectory at all.
   */
  ON_MOTOR_DIRECT,

  /**
   * The motion profile is stepped on the roboRIO at loop rate and each step is handed to the motor's
   * on-board position loop — the 254 / 4738 pattern.
   *
   * <p>Re-sent every loop, so there is no latch. Use it when the profile itself must react to game
   * state (slow down while carrying a game piece) <em>and</em> the backend has no dynamic-profile
   * request of its own.
   */
  RIO_PROFILE_MOTOR_LOOP,

  /**
   * Profile, feedback and feedforward all run on the roboRIO, entirely in SI, and only a voltage
   * reaches the motor.
   *
   * <p>Required for non-smart controllers (PWM speed controllers with a separate encoder), which can
   * do nothing else. On a Kraken or a SPARK this is a <b>downgrade</b> — it gives up the 1 kHz
   * on-device loop for a 50 Hz one — and Rootstock says so out loud rather than letting it pass.
   */
  RIO_FULL;

  /**
   * True when the <b>feedback</b> loop closes on the motor controller rather than on the roboRIO.
   *
   * <p>This is the single question the tuning system asks of this enum, and it is the one that
   * matters for gain units: when it is true, gains must be converted to vendor units by a {@link
   * GainSink} before they mean anything, the effective loop rate is the device's (1 kHz on Phoenix)
   * rather than the robot's, and the measurement delay is the device's filter delay rather than the
   * CAN round trip plus a robot loop.
   *
   * <p>{@link #RIO_PROFILE_MOTOR_LOOP} returns true: the <em>profile</em> is on the RIO but the
   * position loop — the thing kP belongs to — is on the device.
   *
   * @return true for the three on-motor-feedback locations, false only for {@link #RIO_FULL}
   */
  public boolean runsOnMotor() {
    return this != RIO_FULL;
  }

  /**
   * True when the motion profile is generated on the roboRIO and therefore can react to robot state
   * between one setpoint and the next.
   *
   * <p>Used to decide whether a mechanism can honour a mid-motion constraint change, and by the
   * step-response analyser to decide whether to compare against a profile or against a step.
   *
   * @return true for {@link #RIO_PROFILE_MOTOR_LOOP} and {@link #RIO_FULL}
   */
  public boolean profileOnRio() {
    return this == RIO_PROFILE_MOTOR_LOOP || this == RIO_FULL;
  }

  /**
   * True when the setpoint latches on the device and must therefore be re-sent on change and on a
   * heartbeat, rather than every loop.
   *
   * <p>A latched setpoint that is never refreshed is how a mechanism keeps driving toward a goal the
   * robot code has already abandoned; a re-sent-every-loop setpoint that is treated as latched is
   * how it stops moving when a loop is skipped. The two are not interchangeable and the mechanism
   * layer branches on this.
   *
   * @return true for {@link #ON_MOTOR_PROFILED} and {@link #ON_MOTOR_DIRECT}
   */
  public boolean setpointLatches() {
    return this == ON_MOTOR_PROFILED || this == ON_MOTOR_DIRECT;
  }

  /**
   * One sentence explaining what this location does, for the boot dump and for the alert text a
   * student reads when Rootstock downgrades or questions their choice.
   *
   * <p>The sentence names the consequence, not the mechanism: "runs Motion Magic on the device at 1
   * kHz" is something a student can act on; "ON_MOTOR_PROFILED" is not.
   *
   * @return a human-readable explanation with no trailing period
   */
  public String explanation() {
    return switch (this) {
      case ON_MOTOR_PROFILED ->
          "the motor controller runs both the profile and the position loop on-device, "
              + "typically at 1 kHz, and the setpoint latches";
      case ON_MOTOR_DIRECT ->
          "the motor controller runs the position loop on-device with no profile, "
              + "and the setpoint latches";
      case RIO_PROFILE_MOTOR_LOOP ->
          "the roboRIO steps the profile every loop and the motor controller closes the "
              + "position loop, so the profile can react to game state";
      case RIO_FULL ->
          "the roboRIO runs the profile, the feedback and the feedforward in SI and sends "
              + "only a voltage, at the robot loop rate";
    };
  }
}

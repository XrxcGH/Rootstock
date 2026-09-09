package org.rootstock.config;

/**
 * How the device turns a closed-loop output into a command. <b>This is not FOC.</b>
 *
 * <p>FOC and output mode are orthogonal and they get two separate fields, because conflating them
 * broke the library's own flagship example once already: an elevator declared
 * {@code MotorSpec.talonFX(20, "rio").foc(true)} together with volts-per-SI gains, and a design that
 * treated "FOC" as "torque current" would have silently reinterpreted {@code kV = 5.0 V/(m/s)} as
 * {@code 5.0 A/(m/s)} — a number with no physical meaning that produces a mechanism which does not
 * move and a tuning session that never converges.
 *
 * <ul>
 *   <li>{@code foc} sets only {@code ControlRequest.withEnableFOC(...)}. It changes commutation, not
 *       units. Keeping {@code .foc(true)} with volt gains is correct.
 *   <li>{@code outputMode} chooses which family of control requests is used, and therefore what
 *       every one of the seven gains <em>means</em>.
 * </ul>
 *
 * @see MotorSpec.TalonFXSpec#foc()
 */
public enum OutputMode {

  /**
   * Voltage output: {@code MotionMagicVoltage} / {@code PositionVoltage} / {@code VoltageOut} on
   * Phoenix, the duty-cycle closed loop plus {@code FeedForwardConfig} on REVLib.
   *
   * <p>Gains are <b>volts per SI unit</b>, which is what makes one gain set portable across Phoenix,
   * REVLib, generic hardware and the RIO-side loop. This is the shipped default and, in v0.1, the
   * only supported value.
   */
  VOLTAGE,

  /**
   * Torque-current output: {@code MotionMagicTorqueCurrentFOC} and friends.
   *
   * <p>Gains become <b>amps per SI unit</b> — a different number for every one of kS kV kA kG kP kI
   * kD. <b>Not supported in v0.1.</b> Landing it needs an amps-per-SI row in the gain-sink
   * conversion table, an amps-per-SI mode in the feedback designer, a unit tag in the persisted gain
   * file and a migration path for a team that already tuned in volts. That is a coherent chunk of
   * work, not a flag flip, so selecting it collects a config error rather than silently producing
   * gains that are wrong by the motor's torque constant.
   */
  TORQUE_CURRENT;

  /**
   * Whether this output mode is implemented in the shipping library.
   *
   * <p>Validation turns a {@code false} here into a config error whose message names the reason, so
   * the failure is a paragraph at boot rather than a mechanism that will not move.
   *
   * @return true for {@link #VOLTAGE}
   */
  public boolean isSupported() {
    return this == VOLTAGE;
  }

  /**
   * What the seven gains mean under this output mode.
   *
   * @return for example {@code "volts per SI unit (V/m, V/(m/s), V)"}
   */
  public String gainUnits() {
    return this == VOLTAGE
        ? "volts per SI unit (V/m or V/rad, V/(m/s) or V/(rad/s), V)"
        : "amps per SI unit (A/m or A/rad, ...) — a different number for every gain";
  }
}

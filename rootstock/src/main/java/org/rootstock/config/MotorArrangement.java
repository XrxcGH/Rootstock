package org.rootstock.config;

/**
 * What a TalonFXS is commutating.
 *
 * <p>A TalonFXS is not a TalonFX with a different name: it drives an <em>external</em> motor, so it
 * has a commutation configuration group where a TalonFX has none. <b>If the motor arrangement is not
 * set, the device does nothing at all</b> — no fault, no motion, no message. That is why this is a
 * required argument to {@link MotorSpec#talonFXS(int, String, MotorArrangement)} with no default:
 * guessing here produces a dead motor and a student who has no idea why.
 *
 * <p>Data only — naming an arrangement here does not import Phoenix.
 */
public enum MotorArrangement {

  /** A brushed motor on the M+/M- terminals. */
  BRUSHED_DC(MotorModel.BRUSHED_UNKNOWN),

  /** A CTRE Minion on the JST connector. */
  MINION_JST(MotorModel.MINION),

  /** A REV NEO on the JST connector. */
  NEO_JST(MotorModel.NEO),

  /** A REV NEO 550 on the JST connector. */
  NEO550_JST(MotorModel.NEO_550),

  /** A REV NEO Vortex on the JST connector. */
  VORTEX_JST(MotorModel.NEO_VORTEX);

  private final MotorModel m_motor;

  MotorArrangement(MotorModel motor) {
    m_motor = motor;
  }

  /**
   * The motor this arrangement drives, from which the simulation curve and default current limits
   * follow.
   *
   * @return the motor model
   */
  public MotorModel motor() {
    return m_motor;
  }

  /**
   * Whether the arrangement is brushless.
   *
   * <p>A brushed arrangement has no rotor sensor, so it cannot use {@link FeedbackSpec.RotorOnly}.
   *
   * @return true if brushless
   */
  public boolean isBrushless() {
    return m_motor.isBrushless();
  }

  /**
   * One phrase for {@code describe()}.
   *
   * @return for example {@code "Minion on the JST connector"}
   */
  public String describe() {
    return this == BRUSHED_DC
        ? "a brushed motor on the M+/M- terminals"
        : m_motor.displayName() + " on the JST connector";
  }
}

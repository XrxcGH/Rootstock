package org.rootstock.config;

/**
 * Which SPARK controller, driving which motor.
 *
 * <p>REVLib needs <em>two</em> facts that Phoenix does not: which controller class to construct
 * ({@code SparkMax} or {@code SparkFlex}) and which motor type to configure ({@code kBrushless} or
 * {@code kBrushed}). Getting the second one wrong on a real robot is spectacular — a brushless motor
 * driven as brushed makes a noise the whole shop hears and does not turn — and it is not something
 * the library can detect from a CAN ID. So both are one enum constant, chosen once, at the call
 * site, in a form a student can read back against the sticker on the controller.
 *
 * <p>This is data only: naming a SPARK here does not import REVLib.
 */
public enum SparkModel {

  /** SPARK MAX driving a NEO. */
  MAX_NEO("SPARK MAX", false, MotorModel.NEO),

  /** SPARK MAX driving a NEO 550. */
  MAX_NEO550("SPARK MAX", false, MotorModel.NEO_550),

  /** SPARK MAX driving a NEO Vortex. Legal, though a Vortex is usually paired with a FLEX. */
  MAX_VORTEX("SPARK MAX", false, MotorModel.NEO_VORTEX),

  /**
   * SPARK MAX driving a brushed motor (CIM, BAG, 775pro, window motor...).
   *
   * <p>There is no rotor sensor on a brushed motor, so a mechanism using this <b>must</b> carry an
   * external encoder — {@link FeedbackSpec.RotorOnly} cannot work.
   */
  MAX_BRUSHED("SPARK MAX", false, MotorModel.BRUSHED_UNKNOWN),

  /** SPARK FLEX driving a NEO Vortex. The pairing REV designed the FLEX for. */
  FLEX_VORTEX("SPARK FLEX", true, MotorModel.NEO_VORTEX),

  /** SPARK FLEX driving a NEO. */
  FLEX_NEO("SPARK FLEX", true, MotorModel.NEO),

  /** SPARK FLEX driving a brushed motor. See {@link #MAX_BRUSHED} for the encoder consequence. */
  FLEX_BRUSHED("SPARK FLEX", true, MotorModel.BRUSHED_UNKNOWN);

  private final String m_controllerName;
  private final boolean m_flex;
  private final MotorModel m_motor;

  SparkModel(String controllerName, boolean flex, MotorModel motor) {
    m_controllerName = controllerName;
    m_flex = flex;
    m_motor = motor;
  }

  /**
   * The controller's name as printed on its case.
   *
   * @return {@code "SPARK MAX"} or {@code "SPARK FLEX"}
   */
  public String controllerName() {
    return m_controllerName;
  }

  /**
   * Whether the controller is a SPARK FLEX.
   *
   * <p>The backend needs this to pick {@code SparkFlex}/{@code SparkFlexConfig} over
   * {@code SparkMax}/{@code SparkMaxConfig}; the two are not interchangeable at runtime.
   *
   * @return true for a FLEX
   */
  public boolean isFlex() {
    return m_flex;
  }

  /**
   * The motor this controller is driving.
   *
   * @return the motor model, {@link MotorModel#BRUSHED_UNKNOWN} for a brushed pairing
   */
  public MotorModel motor() {
    return m_motor;
  }

  /**
   * Whether the motor is brushless, which is REVLib's {@code MotorType}.
   *
   * @return true if brushless
   */
  public boolean isBrushless() {
    return m_motor.isBrushless();
  }

  /**
   * Controller and motor in one phrase, for {@code describe()} and error messages.
   *
   * @return for example {@code "SPARK MAX driving a NEO 550"}
   */
  public String describe() {
    return m_controllerName + " driving a " + m_motor.displayName();
  }
}

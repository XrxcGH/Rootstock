package org.rootstock.hardware;

import org.rootstock.control.ControlLocation;

/**
 * What a backend can <b>actually</b> do — asked, not discovered by failure.
 *
 * <p>Two things depend on this record. First, {@link ControlLocation} downgrades: a team that asks
 * for an on-motor profile on a PWM speed controller gets a named, printed downgrade at construction
 * rather than a mechanism that silently does not move. Second, signal subscription: there is no
 * point paying CAN bandwidth for a torque-current signal on a device that does not measure one.
 *
 * <p>The alternative — "call it and see what happens" — is how the surveyed robot code ended up with
 * a {@code TurretIONeo} that accepted a chassis-omega feedforward and dropped it on the floor. A
 * capability that is not representable is a capability that gets faked.
 *
 * @param onBoardPositionLoop the device closes a position loop itself
 * @param onBoardVelocityLoop the device closes a velocity loop itself
 * @param onBoardProfile the device generates its own motion profile — Motion Magic, MAXMotion
 * @param dynamicProfile cruise velocity and acceleration can change per REQUEST, with no config
 *     write (Phoenix Pro on a CANivore); false means a constraint change costs a blocking apply
 * @param onBoardGravityFeedforward the device applies a constant gravity term itself
 * @param onBoardCosineGravity the device scales the gravity term by the cosine of absolute
 *     mechanism position — Phoenix {@code Arm_Cosine}, REVLib 2026 {@code FeedForwardConfig.kCos}
 * @param arbitraryFeedforward an arbitrary volt term can ride along with a closed-loop goal
 * @param positionGoalVelocity HOW a goal velocity rides along with a position goal; see {@link
 *     VelocityCarrier}, and note that every value except {@link VelocityCarrier#UNSUPPORTED}
 *     delivers the term
 * @param torqueCurrentControl the device accepts a torque-current request rather than a voltage
 * @param fusedAbsoluteEncoder the device fuses an absolute encoder into its own feedback at rotor
 *     bandwidth, so no roboRIO-side re-seed is needed or wanted
 * @param readsTorqueCurrent {@code MotorInputs.torqueCurrentAmps} can be a real number
 * @param readsTemperature {@code MotorInputs.temperatureCelsius} can be a real number
 * @param reportsDeviceReset the device can tell us it power-cycled, so configuration can be
 *     re-applied automatically; when false, a mid-match brownout silently reverts the config
 * @param reportsConnectionHealth {@code MotorInputs.connected} is measured rather than assumed
 */
public record MotorCapabilities(
    boolean onBoardPositionLoop,
    boolean onBoardVelocityLoop,
    boolean onBoardProfile,
    boolean dynamicProfile,
    boolean onBoardGravityFeedforward,
    boolean onBoardCosineGravity,
    boolean arbitraryFeedforward,
    VelocityCarrier positionGoalVelocity,
    boolean torqueCurrentControl,
    boolean fusedAbsoluteEncoder,
    boolean readsTorqueCurrent,
    boolean readsTemperature,
    boolean reportsDeviceReset,
    boolean reportsConnectionHealth) {

  /**
   * Substitutes {@link VelocityCarrier#UNSUPPORTED} for a null carrier rather than throwing.
   *
   * <p>Null here would mean "the backend author forgot to say", and the safe reading of that is the
   * pessimistic one: {@code UNSUPPORTED} makes a field-locked axis a loud config error instead of a
   * turret that quietly does not counter-rotate.
   */
  public MotorCapabilities {
    positionGoalVelocity = positionGoalVelocity == null ? VelocityCarrier.UNSUPPORTED : positionGoalVelocity;
  }

  /**
   * Everything a roboRIO-side-only backend can do: nothing on the device.
   *
   * <p>This is what a PWM speed controller and the pure-software simulation backend report. The goal
   * velocity is still <i>delivered</i> — as a roboRIO voltage trim — because the seam's promise is
   * delivery, not a mechanism.
   *
   * @return the all-false capability set with {@link VelocityCarrier#RIO_VOLTAGE_TRIM}
   */
  public static MotorCapabilities rioOnly() {
    return builder().positionGoalVelocity(VelocityCarrier.RIO_VOLTAGE_TRIM).build();
  }

  /**
   * A fresh builder with every capability false and the carrier at {@link
   * VelocityCarrier#UNSUPPORTED}.
   *
   * <p>Fourteen positional components is a place where a backend author transposes two booleans and
   * nobody notices for a season, so the builder is the supported way to construct one. The canonical
   * constructor stays public because the record is also a logged value type.
   *
   * @return a builder whose defaults are the pessimistic ones
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Whether this backend can run the requested control location as asked.
   *
   * @param location the location the team declared
   * @return true when nothing has to be downgraded
   */
  public boolean supports(ControlLocation location) {
    if (location == null) {
      return false;
    }
    return switch (location) {
      case ON_MOTOR_PROFILED -> onBoardPositionLoop && onBoardProfile;
      case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP -> onBoardPositionLoop;
      case RIO_FULL -> true;
    };
  }

  /**
   * The best control location this backend can actually run, given the one that was asked for.
   *
   * <p>Returns the request unchanged when it is achievable. Otherwise it steps <i>down</i> one rung
   * at a time — profiled to direct to roboRIO-full — so the downgrade is the smallest honest one
   * rather than a jump to the worst case. The caller is expected to print the difference; a silent
   * downgrade is the failure this method exists to replace.
   *
   * @param requested the declared location
   * @return the achievable location, equal to {@code requested} when nothing was lost
   */
  public ControlLocation downgrade(ControlLocation requested) {
    if (requested == null) {
      return ControlLocation.RIO_FULL;
    }
    if (supports(requested)) {
      return requested;
    }
    if (requested == ControlLocation.ON_MOTOR_PROFILED && onBoardPositionLoop) {
      return ControlLocation.ON_MOTOR_DIRECT;
    }
    return ControlLocation.RIO_FULL;
  }

  /**
   * Whether a closed-loop <i>position</i> goal reaches a device loop at all on this backend.
   *
   * @return {@link #onBoardPositionLoop()}
   */
  public boolean hasDeviceLoop() {
    return onBoardPositionLoop;
  }

  /**
   * The capability set as the boot dump prints it — one line, only the interesting facts.
   *
   * @return a human-readable summary
   */
  public String describe() {
    StringBuilder out = new StringBuilder(200);
    out.append("on-device: ");
    out.append(onBoardPositionLoop ? "position loop" : "no position loop");
    out.append(onBoardVelocityLoop ? ", velocity loop" : ", no velocity loop");
    out.append(onBoardProfile ? ", motion profile" : ", no motion profile");
    if (dynamicProfile) {
      out.append(", per-request constraints");
    }
    if (onBoardCosineGravity) {
      out.append(", cosine gravity");
    } else if (onBoardGravityFeedforward) {
      out.append(", constant gravity");
    }
    if (torqueCurrentControl) {
      out.append(", torque-current control");
    }
    if (fusedAbsoluteEncoder) {
      out.append(", fused absolute encoder");
    }
    out.append("; goal velocity: ").append(positionGoalVelocity.explanation());
    out.append("; reads: ");
    out.append(readsTorqueCurrent ? "torque current" : "no torque current");
    out.append(readsTemperature ? ", temperature" : ", no temperature");
    out.append(reportsDeviceReset ? ", device resets" : ", NO device-reset signal");
    out.append(reportsConnectionHealth ? ", connection health" : ", NO connection health");
    return out.toString();
  }

  /**
   * A mutable builder for {@link MotorCapabilities}, defaulting to "this backend can do nothing".
   *
   * <p>Pessimistic defaults are deliberate: a forgotten {@code .onBoardProfile(true)} costs a team
   * some latency and prints a downgrade notice, whereas a forgotten {@code false} would have the
   * library route a goal into a loop that is not there.
   */
  public static final class Builder {
    private boolean m_onBoardPositionLoop;
    private boolean m_onBoardVelocityLoop;
    private boolean m_onBoardProfile;
    private boolean m_dynamicProfile;
    private boolean m_onBoardGravityFeedforward;
    private boolean m_onBoardCosineGravity;
    private boolean m_arbitraryFeedforward;
    private VelocityCarrier m_positionGoalVelocity = VelocityCarrier.UNSUPPORTED;
    private boolean m_torqueCurrentControl;
    private boolean m_fusedAbsoluteEncoder;
    private boolean m_readsTorqueCurrent;
    private boolean m_readsTemperature;
    private boolean m_reportsDeviceReset;
    private boolean m_reportsConnectionHealth;

    private Builder() {}

    /**
     * Sets whether the device closes a position loop itself.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder onBoardPositionLoop(boolean value) {
      m_onBoardPositionLoop = value;
      return this;
    }

    /**
     * Sets whether the device closes a velocity loop itself.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder onBoardVelocityLoop(boolean value) {
      m_onBoardVelocityLoop = value;
      return this;
    }

    /**
     * Sets whether the device generates its own motion profile.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder onBoardProfile(boolean value) {
      m_onBoardProfile = value;
      return this;
    }

    /**
     * Sets whether constraints can change per request with no config write.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder dynamicProfile(boolean value) {
      m_dynamicProfile = value;
      return this;
    }

    /**
     * Sets whether the device applies a constant gravity feedforward itself.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder onBoardGravityFeedforward(boolean value) {
      m_onBoardGravityFeedforward = value;
      return this;
    }

    /**
     * Sets whether the device scales its gravity term by cosine of position.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder onBoardCosineGravity(boolean value) {
      m_onBoardCosineGravity = value;
      return this;
    }

    /**
     * Sets whether an arbitrary volt term can ride along with a closed-loop goal.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder arbitraryFeedforward(boolean value) {
      m_arbitraryFeedforward = value;
      return this;
    }

    /**
     * Sets how a goal velocity reaches the motor alongside a position goal.
     *
     * @param value the carrier; null is read as {@link VelocityCarrier#UNSUPPORTED}
     * @return this builder
     */
    public Builder positionGoalVelocity(VelocityCarrier value) {
      m_positionGoalVelocity = value == null ? VelocityCarrier.UNSUPPORTED : value;
      return this;
    }

    /**
     * Sets whether the device accepts a torque-current request.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder torqueCurrentControl(boolean value) {
      m_torqueCurrentControl = value;
      return this;
    }

    /**
     * Sets whether the device fuses an absolute encoder into its own feedback.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder fusedAbsoluteEncoder(boolean value) {
      m_fusedAbsoluteEncoder = value;
      return this;
    }

    /**
     * Sets whether torque current can be read back.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder readsTorqueCurrent(boolean value) {
      m_readsTorqueCurrent = value;
      return this;
    }

    /**
     * Sets whether device temperature can be read back.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder readsTemperature(boolean value) {
      m_readsTemperature = value;
      return this;
    }

    /**
     * Sets whether the device reports that it power-cycled.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder reportsDeviceReset(boolean value) {
      m_reportsDeviceReset = value;
      return this;
    }

    /**
     * Sets whether connection health is measured rather than assumed.
     *
     * @param value the new value
     * @return this builder
     */
    public Builder reportsConnectionHealth(boolean value) {
      m_reportsConnectionHealth = value;
      return this;
    }

    /**
     * Builds the immutable capability record.
     *
     * @return the capabilities
     */
    public MotorCapabilities build() {
      return new MotorCapabilities(
          m_onBoardPositionLoop,
          m_onBoardVelocityLoop,
          m_onBoardProfile,
          m_dynamicProfile,
          m_onBoardGravityFeedforward,
          m_onBoardCosineGravity,
          m_arbitraryFeedforward,
          m_positionGoalVelocity,
          m_torqueCurrentControl,
          m_fusedAbsoluteEncoder,
          m_readsTorqueCurrent,
          m_readsTemperature,
          m_reportsDeviceReset,
          m_reportsConnectionHealth);
    }
  }
}

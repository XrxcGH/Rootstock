package org.rootstock.hardware;

import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * Everything read FROM a motor, once per loop, in OUTPUT-SHAFT units.
 *
 * <p>Implements AdvantageKit's {@link LoggableInputs} directly. AdvantageKit is a required
 * dependency of this library, so there is nothing to be gained from a backend-neutral inputs type
 * plus an adapter — that indirection, and its per-key instance cache, bought nothing and cost a
 * whole artifact.
 *
 * <p><b>{@code toLog}/{@code fromLog} are HAND-WRITTEN, and that is not negotiable.</b> The reason is
 * not a preference about annotations: a vendordep cannot install an annotation processor into a
 * consumer's build, so {@code @AutoLog} would work in this repository and fail in the repository of
 * every team that depends on it. The generated-subclass package scope was never resolved for a
 * public type read across package boundaries either.
 *
 * <h2>The field names below are the LOG SCHEMA</h2>
 *
 * <p>Changing one breaks replay of older logs. They are frozen at the API freeze and asserted by a
 * schema test. Add fields at the end; never rename one.
 *
 * <h2>NaN discipline</h2>
 *
 * <p>Every double backed by a status signal the IO did not subscribe is stamped {@link Double#NaN}
 * once and never written again. A frozen zero is a lie that survives a whole match and looks
 * plausible on a plot; NaN is honest and is visibly wrong. Booleans cannot carry NaN, so the two
 * limit-switch signals carry an explicit {@code *Valid} companion instead.
 *
 * <p>Public mutable fields are deliberate: this is AdvantageKit's inputs idiom, the object is
 * allocated once per mechanism and filled in place every loop, and an accessor pair per field would
 * be 36 methods that do nothing.
 */
public class MotorInputs implements LoggableInputs {

  /** Whether the device is answering. Measured where the backend can measure it, never assumed. */
  public boolean connected = false;

  /** Mechanism position, in OUTPUT-SHAFT ROTATIONS. */
  public double positionRot = Double.NaN;

  /** Mechanism velocity, in OUTPUT-SHAFT ROTATIONS PER SECOND. */
  public double velocityRps = Double.NaN;

  /** Volts the device is actually applying, including its own closed loop. */
  public double appliedVolts = Double.NaN;

  /** Current drawn from the battery. Feeds the brownout and power monitors. */
  public double supplyCurrentAmps = Double.NaN;

  /** Current through the motor windings. Current-spike homing and stall detection are built on it. */
  public double statorCurrentAmps = Double.NaN;

  /** Torque-producing current, where the device measures one. NaN on devices that do not. */
  public double torqueCurrentAmps = Double.NaN;

  /** Device temperature. NaN on devices with no temperature sensor. */
  public double temperatureCelsius = Double.NaN;

  /** Forward hardware limit switch, when one is wired into the motor controller. */
  public boolean forwardLimitTripped = false;

  /** False when the forward limit signal was not subscribed — booleans cannot carry NaN. */
  public boolean forwardLimitValid = false;

  /** Reverse hardware limit switch, when one is wired into the motor controller. */
  public boolean reverseLimitTripped = false;

  /** False when the reverse limit signal was not subscribed — booleans cannot carry NaN. */
  public boolean reverseLimitValid = false;

  /**
   * What the controller believes its closed-loop target is, in output-shaft rotations.
   *
   * <p>Diagnostic gold: if this disagrees with the mechanism's goal, the setpoint did not land, and
   * that is a completely different problem from gains being wrong.
   */
  public double closedLoopReferenceRot = Double.NaN;

  /**
   * Monotonically increasing count of device resets observed.
   *
   * <p>A non-zero value mid-match explains an elevator that fell: the device power-cycled, lost its
   * configuration, and came back with factory soft limits.
   */
  public long deviceResetCount = 0L;

  /** Follower positions, flattened. Length 0 when there are no followers. */
  public double[] followerPositionRot = new double[0];

  /** Follower stator currents, flattened. A disagreement here is a mechanically fighting pair. */
  public double[] followerStatorAmps = new double[0];

  /** Follower temperatures, flattened. */
  public double[] followerTemperatureC = new double[0];

  /** Follower connection health, flattened. */
  public boolean[] followerConnected = new boolean[0];

  /**
   * Writes every field into the log table under its frozen schema name.
   *
   * @param t the table for this mechanism's inputs subtable
   */
  @Override
  public void toLog(LogTable t) {
    t.put("Connected", connected);
    t.put("PositionRot", positionRot);
    t.put("VelocityRps", velocityRps);
    t.put("AppliedVolts", appliedVolts);
    t.put("SupplyCurrentAmps", supplyCurrentAmps);
    t.put("StatorCurrentAmps", statorCurrentAmps);
    t.put("TorqueCurrentAmps", torqueCurrentAmps);
    t.put("TemperatureCelsius", temperatureCelsius);
    t.put("ForwardLimitTripped", forwardLimitTripped);
    t.put("ForwardLimitValid", forwardLimitValid);
    t.put("ReverseLimitTripped", reverseLimitTripped);
    t.put("ReverseLimitValid", reverseLimitValid);
    t.put("ClosedLoopReferenceRot", closedLoopReferenceRot);
    t.put("DeviceResetCount", deviceResetCount);
    t.put("FollowerPositionRot", followerPositionRot);
    t.put("FollowerStatorAmps", followerStatorAmps);
    t.put("FollowerTemperatureC", followerTemperatureC);
    t.put("FollowerConnected", followerConnected);
  }

  /**
   * Reads every field back out of a log table during replay, defaulting to the value already held.
   *
   * @param t the table for this mechanism's inputs subtable
   */
  @Override
  public void fromLog(LogTable t) {
    connected = t.get("Connected", connected);
    positionRot = t.get("PositionRot", positionRot);
    velocityRps = t.get("VelocityRps", velocityRps);
    appliedVolts = t.get("AppliedVolts", appliedVolts);
    supplyCurrentAmps = t.get("SupplyCurrentAmps", supplyCurrentAmps);
    statorCurrentAmps = t.get("StatorCurrentAmps", statorCurrentAmps);
    torqueCurrentAmps = t.get("TorqueCurrentAmps", torqueCurrentAmps);
    temperatureCelsius = t.get("TemperatureCelsius", temperatureCelsius);
    forwardLimitTripped = t.get("ForwardLimitTripped", forwardLimitTripped);
    forwardLimitValid = t.get("ForwardLimitValid", forwardLimitValid);
    reverseLimitTripped = t.get("ReverseLimitTripped", reverseLimitTripped);
    reverseLimitValid = t.get("ReverseLimitValid", reverseLimitValid);
    closedLoopReferenceRot = t.get("ClosedLoopReferenceRot", closedLoopReferenceRot);
    deviceResetCount = t.get("DeviceResetCount", deviceResetCount);
    followerPositionRot = t.get("FollowerPositionRot", followerPositionRot);
    followerStatorAmps = t.get("FollowerStatorAmps", followerStatorAmps);
    followerTemperatureC = t.get("FollowerTemperatureC", followerTemperatureC);
    followerConnected = t.get("FollowerConnected", followerConnected);
  }
}

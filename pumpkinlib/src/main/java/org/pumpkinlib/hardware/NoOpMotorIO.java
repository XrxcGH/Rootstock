package org.pumpkinlib.hardware;

import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.core.spi.RobotMode;

/**
 * A motor IO that accepts every command and does nothing with it.
 *
 * <p>Two callers, both deliberate.
 *
 * <p><b>Replay.</b> In {@link RobotMode#REPLAY} every input comes from the log, so an IO that talks
 * to hardware would be reading a device that is not there and writing a device that must not move.
 * Replay is unconditional in this library — AdvantageKit is a required dependency, so {@code REPLAY}
 * always exists and always works, and there is no "can this backend replay?" runtime check to fail.
 *
 * <p><b>Degrade, never crash.</b> When a spec names a backend whose adapter artifact is not on the
 * classpath, the factory returns one of these rather than throwing. The robot boots, safe mode
 * already carries the error, the driver station shows it, and the student sees a mechanism that
 * refuses to move with a message saying why — instead of a robot that will not start at all fifteen
 * minutes before a match.
 *
 * <p>Every reading stays {@link Double#NaN} and {@code connected} stays false, which is the honest
 * report and is visibly wrong on a plot. An IO that reported plausible zeros here would be
 * indistinguishable from a working mechanism sitting at its lower limit.
 */
public final class NoOpMotorIO implements MotorIO {

  private static final MotorCapabilities kNothing =
      MotorCapabilities.builder().positionGoalVelocity(VelocityCarrier.UNSUPPORTED).build();

  private final String m_name;
  private final String m_reason;

  /**
   * A no-op IO under the given name, with the generic replay explanation.
   *
   * @param name the name this IO logs and alerts under
   */
  public NoOpMotorIO(String name) {
    this(name, "replay or an unavailable backend");
  }

  /**
   * A no-op IO with an explicit reason, printed by {@link #describe()}.
   *
   * @param name the name this IO logs and alerts under
   * @param reason why there is no real device behind this IO, in a phrase a student can act on
   */
  public NoOpMotorIO(String name, String reason) {
    m_name = name == null || name.isBlank() ? "motor" : name;
    m_reason = reason == null || reason.isBlank() ? "no backend" : reason;
  }

  @Override
  public void updateInputs(MotorInputs inputs) {
    if (inputs == null) {
      return;
    }
    inputs.connected = false;
    for (SignalSet.Channel channel : SignalSet.Channel.values()) {
      channel.stampAbsent(inputs);
    }
  }

  @Override
  public void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts) {}

  @Override
  public void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override) {}

  @Override
  public void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts) {}

  @Override
  public void setVoltage(double volts) {}

  @Override
  public void setDutyCycle(double fraction) {}

  @Override
  public void setNeutral() {}

  @Override
  public void applyGains(Gains siGains) {}

  @Override
  public void applyConstraints(MotionConstraints constraints) {}

  @Override
  public void setNeutralMode(NeutralMode mode) {}

  @Override
  public void seedPosition(double outputRotations) {}

  @Override
  public void reapplyFullConfigBlocking() {}

  @Override
  public MotorCapabilities capabilities() {
    return kNothing;
  }

  @Override
  public String name() {
    return m_name;
  }

  @Override
  public String describe() {
    return m_name
        + ": NO-OP motor IO (" + m_reason + "). Every command is discarded and every reading is "
        + "NaN. Nothing will move.";
  }
}

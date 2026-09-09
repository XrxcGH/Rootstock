package org.rootstock.hardware.phoenix;

import org.rootstock.config.ControlConfig;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorSpec;
import org.rootstock.core.spi.Tier;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorIOFactory;
import org.rootstock.units.MechanismUnits;

/**
 * How this artifact tells the core factory that CTRE devices are handled.
 *
 * <p>The core {@code rootstock} artifact contains <b>zero</b> vendor imports and must compile and
 * run on a machine with neither Phoenix nor REVLib installed, so it cannot say {@code new
 * TalonFXMotorIO(...)}. This class is published through {@code
 * META-INF/services/org.rootstock.hardware.MotorIOFactory$Backend}; the factory discovers it on
 * first use and the backend decision stays in one place without core pointing at a vendor.
 *
 * <p>{@code Class.forName("com.ctre...")} was the alternative and was rejected: a string literal is
 * not a seam, it is a compile error deferred to a competition.
 *
 * <p>The factory hands a backend the whole {@link MotorIOFactory.DeviceSetup} -- the motor group,
 * the declared current limits, the travel range and hard stops, and the absolute-encoder plumbing --
 * so this class configures all of it on the device. {@code describe()} says what was configured.
 */
public final class Phoenix6Backend implements MotorIOFactory.Backend {

  /** Discovered reflectively by {@link java.util.ServiceLoader}, which needs a public no-arg form. */
  public Phoenix6Backend() {}

  @Override
  public boolean supports(MotorSpec spec) {
    return spec instanceof MotorSpec.TalonFXSpec || spec instanceof MotorSpec.TalonFXSSpec;
  }

  /**
   * Build the live Phoenix backend, configuring everything the setup carries.
   *
   * @param setup the motors, current limits, travel range and feedback plumbing the team declared
   * @param units the mechanism's unit conversion object
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
   * @return the backend IO, or null when the leader is not a CTRE device so the factory keeps
   *     looking rather than crashing
   */
  @Override
  public MotorIO create(
      MotorIOFactory.DeviceSetup setup,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    MotorSpec spec = setup.leader();
    if (spec instanceof MotorSpec.TalonFXSpec) {
      return new TalonFXMotorIO(setup, units, control, kind, Tier.STANDARD);
    }
    if (spec instanceof MotorSpec.TalonFXSSpec) {
      return new TalonFXSMotorIO(setup, units, control, kind, Tier.STANDARD);
    }
    return null;
  }

  @Override
  public String vendor() {
    return "Phoenix 6";
  }
}

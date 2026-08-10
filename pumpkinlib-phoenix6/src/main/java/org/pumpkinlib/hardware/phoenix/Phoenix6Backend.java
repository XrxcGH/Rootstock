package org.pumpkinlib.hardware.phoenix;

import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.hardware.MotorIO;
import org.pumpkinlib.hardware.MotorIOFactory;
import org.pumpkinlib.units.MechanismUnits;

/**
 * How this artifact tells the core factory that CTRE devices are handled.
 *
 * <p>The core {@code pumpkinlib} artifact contains <b>zero</b> vendor imports and must compile and
 * run on a machine with neither Phoenix nor REVLib installed, so it cannot say {@code new
 * TalonFXMotorIO(...)}. This class is published through {@code
 * META-INF/services/org.pumpkinlib.hardware.MotorIOFactory$Backend}; the factory discovers it on
 * first use and the backend decision stays in one place without core pointing at a vendor.
 *
 * <p>{@code Class.forName("com.ctre...")} was the alternative and was rejected: a string literal is
 * not a seam, it is a compile error deferred to a competition.
 *
 * <p>What this constructor path can and cannot configure is stated on {@link TalonFXMotorIO}: the
 * factory hands a backend only the spec, the units, the control config and the mechanism kind, so
 * soft limits, hard stops, absolute-encoder plumbing and followers are configured only through the
 * mechanism builder's fuller constructor. {@code describe()} says which form built a given backend.
 */
public final class Phoenix6Backend implements MotorIOFactory.Backend {

  /** Discovered reflectively by {@link java.util.ServiceLoader}, which needs a public no-arg form. */
  public Phoenix6Backend() {}

  @Override
  public boolean supports(MotorSpec spec) {
    return spec instanceof MotorSpec.TalonFXSpec || spec instanceof MotorSpec.TalonFXSSpec;
  }

  @Override
  public MotorIO create(
      MotorSpec spec, MechanismUnits units, ControlConfig control, MechanismKind kind) {
    if (spec instanceof MotorSpec.TalonFXSpec talonFx) {
      return new TalonFXMotorIO(talonFx, units, control, kind);
    }
    if (spec instanceof MotorSpec.TalonFXSSpec talonFxs) {
      return new TalonFXSMotorIO(talonFxs, units, control, kind);
    }
    return null;
  }

  @Override
  public String vendor() {
    return "Phoenix 6";
  }
}

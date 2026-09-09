package org.rootstock.hardware.rev;

import org.rootstock.config.ControlConfig;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorSpec;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorIOFactory;
import org.rootstock.units.MechanismUnits;

/**
 * How {@code MotorIOFactory} finds this artifact.
 *
 * <p>Declared in {@code META-INF/services/org.rootstock.hardware.MotorIOFactory$Backend} and
 * discovered through {@link java.util.ServiceLoader}. Core never names a vendor class, so a team
 * that installs only this artifact gets REV support with no Phoenix on the classpath and no
 * {@code Class.forName} string literal standing in for a seam.
 *
 * <p>Public with a public no-argument constructor because that is what {@code ServiceLoader}
 * requires; nothing is expected to construct it by hand.
 */
public final class RevBackend implements MotorIOFactory.Backend {

  /** Required by {@link java.util.ServiceLoader}. */
  public RevBackend() {}

  /**
   * Whether a spec names a SPARK.
   *
   * @param spec the declared motor
   * @return true for {@link MotorSpec.SparkSpec}
   */
  @Override
  public boolean supports(MotorSpec spec) {
    return spec instanceof MotorSpec.SparkSpec;
  }

  /**
   * Build the live SPARK backend.
   *
   * @param spec the declared motor
   * @param units the mechanism's unit conversion object
   * @param control the declared location, gains, constraints, gravity model and tolerance
   * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
   * @return a {@link SparkMotorIO}, or null when the spec is not a SPARK so the factory keeps
   *     looking rather than crashing
   */
  @Override
  public MotorIO create(
      MotorSpec spec, MechanismUnits units, ControlConfig control, MechanismKind kind) {
    if (!(spec instanceof MotorSpec.SparkSpec sparkSpec)) {
      return null;
    }
    return new SparkMotorIO(sparkSpec, units, control, kind);
  }

  /**
   * The vendor name used in the boot dump and in the "adapter not installed" alert.
   *
   * @return {@code "REVLib"}
   */
  @Override
  public String vendor() {
    return "REVLib";
  }
}

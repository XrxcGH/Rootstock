package org.rootstock.hardware.rev;

import org.rootstock.config.ControlConfig;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
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
   * Build the live SPARK backend, configuring everything the setup carries.
   *
   * <p>The whole {@link MotorIOFactory.DeviceSetup} crosses the seam, so the followers and the
   * mechanism's declared current limits go straight into the constructor, and the travel range is
   * armed on the device afterwards through {@link SparkMotorIO#applySoftLimits}. Before this, a
   * declared second NEO was never constructed and a declared 40/20 A limit was silently replaced by
   * the motor model's default.
   *
   * @param setup the motors, current limits, travel range and feedback plumbing the team declared
   * @param units the mechanism's unit conversion object
   * @param control the declared location, gains, constraints, gravity model and tolerance
   * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
   * @return a {@link SparkMotorIO}, or null when the leader is not a SPARK so the factory keeps
   *     looking rather than crashing
   */
  @Override
  public MotorIO create(
      MotorIOFactory.DeviceSetup setup,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    if (!(setup.leader() instanceof MotorSpec.SparkSpec)) {
      return null;
    }
    SparkMotorIO io = new SparkMotorIO(setup.motors(), units, control, kind, setup.current());
    PositionLimits limits = setup.limits();
    if (limits != null) {
      double a = units.toOutputRotations(limits.range().min());
      double b = units.toOutputRotations(limits.range().max());
      double lo = Math.min(a, b);
      double hi = Math.max(a, b);
      // hi > lo, not merely finite: a PositionConfig whose .softLimits(...) was never called carries
      // a placeholder range of exactly zero to zero, which validation already reports as a fatal
      // config error. Arming the SPARK at [0, 0] on top of that would pin the mechanism at zero.
      boolean usable = Double.isFinite(lo) && Double.isFinite(hi) && hi > lo;
      io.applySoftLimits(usable ? lo : Double.NaN, usable ? hi : Double.NaN);
    }
    return io;
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

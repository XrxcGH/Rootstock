package org.rootstock.hardware.phoenix;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.TalonFXSConfiguration;
import com.ctre.phoenix6.hardware.TalonFXS;
import com.ctre.phoenix6.signals.ExternalFeedbackSensorSourceValue;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.MotorArrangementValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import com.ctre.phoenix6.sim.TalonFXSSimState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.HardStop;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorArrangement;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.core.spi.Tier;
import org.rootstock.units.MechanismUnits;

/**
 * The Phoenix 6 TalonFXS backend — a CTRE controller commutating an <b>external</b> motor.
 *
 * <h2>A TalonFXS is not a TalonFX with a different name</h2>
 *
 * <p>Verified against Phoenix 6 26.3.0, {@code TalonFXSConfiguration} carries {@code Commutation}
 * and {@code ExternalFeedback} groups where {@code TalonFXConfiguration} carries {@code Feedback}:
 *
 * <ul>
 *   <li><b>{@code Commutation.MotorArrangement} must be set or the device does nothing at all</b> —
 *       no fault, no motion, no message. It is a required field on {@code MotorSpec.talonFXS(...)}
 *       for exactly that reason, and it is mapped here.
 *   <li><b>Gearing goes in {@code ExternalFeedback.SensorToMechanismRatio}</b>, not
 *       {@code Feedback.*}. Writing the TalonFX field name compiles on neither device and, worse,
 *       leaving the FXS field at its default of 1.0 makes every position on the device read in rotor
 *       rotations while the rest of the library believes they are output rotations.
 *   <li>{@code Slot0}, {@code MotionMagic}, {@code SoftwareLimitSwitch}, {@code CurrentLimits},
 *       {@code MotorOutput}, {@code HardwareLimitSwitch} and {@code ClosedLoopGeneral} are
 *       identical, which is why everything except this class's {@link #buildConfig()} lives in
 *       {@link AbstractPhoenixMotorIO}.
 * </ul>
 *
 * <h2>No FOC, and that is a hardware fact rather than a policy</h2>
 *
 * <p>A TalonFXS drives a brushed motor or an external brushless one over a JST hall connector. It
 * implements Phoenix's external-motor trait rather than the FOC trait, so {@code .foc(true)} on a
 * TalonFXS spec is reported as unsupported in {@link #describe()} instead of being quietly passed to
 * a request the device ignores.
 */
public final class TalonFXSMotorIO extends AbstractPhoenixMotorIO {

  private final TalonFXS m_leaderFxs;
  private final TalonFXS[] m_followerFxs;
  private final TalonFXSConfiguration m_config = new TalonFXSConfiguration();
  private final TalonFXSConfiguration[] m_followerConfigs;
  private final MotorSpec.TalonFXSSpec m_talonSpec;
  private final boolean m_fullyConfigured;

  /**
   * The form {@link org.rootstock.hardware.MotorIOFactory} calls.
   *
   * @param spec the declared motor, carrying the required motor arrangement
   * @param units the mechanism's one converter
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public TalonFXSMotorIO(
      MotorSpec.TalonFXSSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    this(spec, units, control, kind, null, null, List.of(), Tier.STANDARD);
  }

  /**
   * The full form, used by a mechanism builder that knows the limits, the feedback plumbing and the
   * followers.
   *
   * @param spec the declared leader motor
   * @param units the mechanism's one converter
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param limits soft limits, current limits and hard stops; null configures no soft limits
   * @param feedback how absolute position is plumbed; null means the commutation sensor
   * @param followers the follower motors and their sense
   * @param tier the telemetry tier, which gates the diagnostic-only status signals
   */
  public TalonFXSMotorIO(
      MotorSpec.TalonFXSSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      PositionLimits limits,
      FeedbackSpec feedback,
      List<MotorGroup.FollowerSpec> followers,
      Tier tier) {
    this(
        new TalonFXS(spec.deviceId(), PhoenixUtil.bus(spec.canBus())),
        buildFollowers(followers),
        spec,
        units,
        control,
        kind,
        limits,
        feedback,
        tier);
  }

  private TalonFXSMotorIO(
      TalonFXS leader,
      List<FollowerDevice> followers,
      MotorSpec.TalonFXSSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      PositionLimits limits,
      FeedbackSpec feedback,
      Tier tier) {
    super(
        new Setup(
            spec.name(), units, control, kind, limits, feedback, spec, proLicensed(leader), tier),
        leader,
        leader,
        followers);
    m_leaderFxs = leader;
    m_talonSpec = spec;
    m_fullyConfigured = limits != null;

    m_followerFxs = new TalonFXS[followers.size()];
    m_followerConfigs = new TalonFXSConfiguration[followers.size()];
    for (int i = 0; i < followers.size(); i++) {
      m_followerFxs[i] = (TalonFXS) followers.get(i).device();
      m_followerConfigs[i] = new TalonFXSConfiguration();
    }

    buildConfig();
    PhoenixUtil.applyVerified(m_leaderFxs, m_config, motorName());
    for (int i = 0; i < m_followerFxs.length; i++) {
      buildFollowerConfig(i);
      PhoenixUtil.applyVerified(
          m_followerFxs[i], m_followerConfigs[i], motorName() + "/follower" + i);
    }

    completeConstruction(
        m_config.Slot0,
        m_config.MotionMagic,
        m_config.MotorOutput,
        slot -> PhoenixUtil.applyFast(m_leaderFxs, slot),
        motionMagic -> PhoenixUtil.applyFast(m_leaderFxs, motionMagic),
        output -> PhoenixUtil.applyFast(m_leaderFxs, output),
        tier);
  }

  // ============================================================================ config derivation

  private void buildConfig() {
    MechanismUnits u = m_units;
    double siPerRot = u.siPerOutputRotation();

    // 0. COMMUTATION. Without this the device is silent: no fault, no motion, no message. There is
    //    deliberately no default on MotorSpec.talonFXS(...) for the same reason.
    m_config.Commutation.MotorArrangement = arrangementOf(m_talonSpec.arrangement());

    // 1. GEARBOX -- ExternalFeedback on this device, NOT Feedback.
    m_config.ExternalFeedback.SensorToMechanismRatio = u.rotorPerOutput();
    m_config.ExternalFeedback.ExternalFeedbackSensorSource =
        ExternalFeedbackSensorSourceValue.Commutation;

    // 2. FEEDBACK SOURCE, when a CANcoder is in the loop.
    if (m_feedback instanceof FeedbackSpec.FusedCancoder fused) {
      m_config.ExternalFeedback.ExternalFeedbackSensorSource =
          ExternalFeedbackSensorSourceValue.FusedCANcoder;
      m_config.ExternalFeedback.FeedbackRemoteSensorID = fused.cancoderId();
      m_config.ExternalFeedback.RotorToSensorRatio = fused.rotorPerSensor();
      m_config.ExternalFeedback.SensorToMechanismRatio = fused.sensorPerOutput();
    } else if (m_feedback instanceof FeedbackSpec.RemoteCancoder remote) {
      m_config.ExternalFeedback.ExternalFeedbackSensorSource =
          ExternalFeedbackSensorSourceValue.RemoteCANcoder;
      m_config.ExternalFeedback.FeedbackRemoteSensorID = remote.cancoderId();
      m_config.ExternalFeedback.RotorToSensorRatio = remote.rotorPerSensor();
      m_config.ExternalFeedback.SensorToMechanismRatio = remote.sensorPerOutput();
    }

    // 3. MOTOR OUTPUT.
    m_config.MotorOutput.Inverted =
        m_talonSpec.inverted()
            ? InvertedValue.Clockwise_Positive
            : InvertedValue.CounterClockwise_Positive;
    m_config.MotorOutput.NeutralMode =
        m_control.neutralMode() == NeutralMode.BRAKE
            ? NeutralModeValue.Brake
            : NeutralModeValue.Coast;

    // 4. CURRENT LIMITS.
    CurrentLimits current =
        m_limits != null ? m_limits.current() : CurrentLimits.defaultsFor(m_talonSpec.model());
    m_config.CurrentLimits.StatorCurrentLimit = current.statorAmps();
    m_config.CurrentLimits.StatorCurrentLimitEnable = true;
    m_config.CurrentLimits.SupplyCurrentLimit = current.supplyAmps();
    m_config.CurrentLimits.SupplyCurrentLimitEnable = current.hasSupplyLimit();
    m_config.CurrentLimits.SupplyCurrentLowerLimit = current.supplyLowerAmps();
    m_config.CurrentLimits.SupplyCurrentLowerTime = current.supplyLowerSeconds();

    // 5. SOFT LIMITS, converted and ordered by the library.
    if (m_limits != null) {
      double a = u.toOutputRotations(m_limits.range().min());
      double b = u.toOutputRotations(m_limits.range().max());
      double lo = Math.min(a, b);
      double hi = Math.max(a, b);
      m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold = hi;
      m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable = Double.isFinite(hi);
      m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold = lo;
      m_config.SoftwareLimitSwitch.ReverseSoftLimitEnable = Double.isFinite(lo);
    }
    m_config.HardwareLimitSwitch.ForwardLimitEnable =
        m_limits != null && m_limits.usesMotorLimit(HardStop.FORWARD);
    m_config.HardwareLimitSwitch.ReverseLimitEnable =
        m_limits != null && m_limits.usesMotorLimit(HardStop.REVERSE);

    // 6. GAINS + GRAVITY, through the one converter.
    Phoenix6GainSink.writeInto(
        m_config.Slot0,
        m_control.gains(),
        m_control.gravity(),
        siPerRot,
        u.toOutputRotations(u.axis().horizontalReference()));

    // 7. PROFILE, all three derivatives converted.
    m_config.MotionMagic.MotionMagicCruiseVelocity =
        profileValue(u.toOutputRps(m_control.constraints().maxVelocity()));
    m_config.MotionMagic.MotionMagicAcceleration =
        profileValue(u.toOutputRps2(m_control.constraints().maxAcceleration()));
    m_config.MotionMagic.MotionMagicJerk =
        profileValue(u.toOutputRps3(m_control.constraints().jerk()));

    // 7b. MOTION MAGIC EXPO, derived from the mechanism's own kV and kA.
    if (m_useExpo) {
      m_config.MotionMagic.MotionMagicExpo_kV =
          Phoenix6GainSink.expoKv(m_control.gains(), siPerRot);
      m_config.MotionMagic.MotionMagicExpo_kA =
          Phoenix6GainSink.expoKa(m_control.gains(), siPerRot);
    }

    // 8. CONTINUOUS WRAP, derived from the axis.
    m_config.ClosedLoopGeneral.ContinuousWrap = u.axis().isContinuous();
  }

  private void buildFollowerConfig(int index) {
    TalonFXSConfiguration follower = m_followerConfigs[index];
    follower.Commutation.MotorArrangement = m_config.Commutation.MotorArrangement;
    follower.MotorOutput.NeutralMode = m_config.MotorOutput.NeutralMode;
    follower.MotorOutput.Inverted = m_config.MotorOutput.Inverted;
    follower.CurrentLimits = m_config.CurrentLimits;
  }

  // ============================================================================== subclass hooks

  @Override
  protected Object leaderConfig() {
    return m_config;
  }

  @Override
  protected Object followerConfig(int index) {
    return m_followerConfigs[index];
  }

  @Override
  protected StatusCode setDevicePosition(double outputRotations) {
    return m_leaderFxs.setPosition(outputRotations, 0.0);
  }

  @Override
  protected boolean supportsFoc() {
    // A TalonFXS commutates an external motor and implements Phoenix's external-motor trait, not the
    // FOC trait. Passing withEnableFOC(true) here would be a promise the hardware cannot keep.
    return false;
  }

  @Override
  protected boolean supportsTorqueCurrent() {
    return false;
  }

  @Override
  protected String deviceTypeName() {
    return "TalonFXS";
  }

  // ================================================================================== simulation

  /**
   * The simulation handle, driving the real ratio path through {@code ExternalFeedback}.
   *
   * @return the handle; always present
   */
  @Override
  public Optional<SimMotorHandle> simHandle() {
    return Optional.of(new Handle());
  }

  private final class Handle implements SimMotorHandle {

    @Override
    public double appliedVolts(double busVoltage) {
      TalonFXSSimState sim = m_leaderFxs.getSimState();
      sim.setSupplyVoltage(Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : 12.0);
      return sim.getMotorVoltage();
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      TalonFXSSimState sim = m_leaderFxs.getSimState();
      if (Double.isFinite(rotorRotations)) {
        sim.setRawRotorPosition(rotorRotations);
      }
      if (Double.isFinite(rotorRps)) {
        sim.setRotorVelocity(rotorRps);
      }
      for (TalonFXS follower : m_followerFxs) {
        TalonFXSSimState followerSim = follower.getSimState();
        if (Double.isFinite(rotorRotations)) {
          followerSim.setRawRotorPosition(rotorRotations);
        }
        if (Double.isFinite(rotorRps)) {
          followerSim.setRotorVelocity(rotorRps);
        }
      }
    }

    @Override
    public double statorAmps() {
      return m_leaderFxs.getSimState().getTorqueCurrent();
    }
  }

  // ================================================================================ escape hatch

  /**
   * The live TalonFXS, reached through {@code io.as(TalonFXSMotorIO.class)}.
   *
   * @return the leader device
   */
  public TalonFXS talonFXS() {
    return m_leaderFxs;
  }

  /**
   * Mutate the device configuration and re-apply it, verified. Blocking; not legal from an enabled
   * {@code periodic()}.
   *
   * @param mutation what to change
   * @return true when the changed configuration read back matching
   */
  public boolean applyRaw(Consumer<TalonFXSConfiguration> mutation) {
    if (mutation == null) {
      return true;
    }
    mutation.accept(m_config);
    return PhoenixUtil.applyVerified(m_leaderFxs, m_config, motorName());
  }

  // ==================================================================================== describe

  @Override
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(2048);
    sb.append(describeCommon()).append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "commutation"))
        .append(m_config.Commutation.MotorArrangement)
        .append("  (")
        .append(m_talonSpec.arrangement().describe())
        .append(") -- a TalonFXS with no arrangement does nothing at all")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "gearing"))
        .append(
            String.format(
                Locale.ROOT,
                "ExternalFeedback.SensorToMechanismRatio = %.5f rotor rot per output rot"
                    + "  (NOT Feedback.* -- that group does not exist on a TalonFXS)",
                m_config.ExternalFeedback.SensorToMechanismRatio))
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "feedback source"))
        .append(m_config.ExternalFeedback.ExternalFeedbackSensorSource)
        .append("  (")
        .append(m_feedback.sensorDescription())
        .append(')')
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "soft limits"))
        .append(
            m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable
                ? String.format(
                    Locale.ROOT,
                    "[%.4f, %.4f] output rotations, ENABLED on the device",
                    m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold,
                    m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold)
                : "NONE configured -- built without a PositionLimits")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "built by"))
        .append(
            m_fullyConfigured
                ? "the full constructor (limits, feedback and followers configured)"
                : "the MotorIOFactory constructor -- soft limits, hard stops and followers were not"
                    + " supplied, so they are not configured on the device")
        .append(nl);
    return sb.toString().stripTrailing();
  }

  // ===================================================================================== private

  private static MotorArrangementValue arrangementOf(MotorArrangement arrangement) {
    if (arrangement == null) {
      return MotorArrangementValue.Disabled;
    }
    return switch (arrangement) {
      case BRUSHED_DC -> MotorArrangementValue.Brushed_DC;
      case MINION_JST -> MotorArrangementValue.Minion_JST;
      case NEO_JST -> MotorArrangementValue.NEO_JST;
      case NEO550_JST -> MotorArrangementValue.NEO550_JST;
      case VORTEX_JST -> MotorArrangementValue.VORTEX_JST;
    };
  }

  private static List<FollowerDevice> buildFollowers(List<MotorGroup.FollowerSpec> specs) {
    List<FollowerDevice> out = new ArrayList<>();
    if (specs == null) {
      return out;
    }
    for (MotorGroup.FollowerSpec follower : specs) {
      if (follower == null || !(follower.spec() instanceof MotorSpec.TalonFXSSpec fxs)) {
        continue;
      }
      TalonFXS device = new TalonFXS(fxs.deviceId(), PhoenixUtil.bus(fxs.canBus()));
      out.add(new FollowerDevice(device, device, follower.sense()));
    }
    return out;
  }

  private static boolean proLicensed(TalonFXS leader) {
    return Boolean.TRUE.equals(leader.getIsProLicensed().refresh().getValue());
  }

  private static double profileValue(double converted) {
    return Double.isFinite(converted) && converted > 0.0 ? converted : 0.0;
  }
}

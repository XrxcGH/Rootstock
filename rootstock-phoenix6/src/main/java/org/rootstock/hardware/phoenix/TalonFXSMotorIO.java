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
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.hardware.MotorIOFactory;
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
  private final CurrentLimits m_currentLimits;
  private final boolean m_fullSetup;

  /**
   * The bare-spec form, for a motor used on its own rather than inside a mechanism.
   *
   * <p>A {@code MotorSpec} carries no travel range, no follower list, no absolute encoder and not
   * the mechanism's real current limits, so none of those are configured here. A mechanism uses the
   * {@link MotorIOFactory.DeviceSetup} form below, and {@link #describe()} states which form ran.
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
    this(
        new TalonFXS(spec.deviceId(), PhoenixUtil.bus(spec.canBus())),
        buildFollowers(List.of()),
        spec,
        units,
        control,
        kind,
        CurrentLimits.defaultsFor(spec.model()),
        null,
        null,
        Tier.STANDARD,
        false);
  }

  /**
   * The full form, which is what {@link MotorIOFactory} calls for every mechanism.
   *
   * @param setup the motors, current limits, travel range and feedback plumbing the team declared
   * @param units the mechanism's one converter
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param tier the telemetry tier, which gates the diagnostic-only status signals
   * @throws IllegalArgumentException if the leader is not a TalonFXS
   */
  public TalonFXSMotorIO(
      MotorIOFactory.DeviceSetup setup,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      Tier tier) {
    this(
        new TalonFXS(
            leaderSpecOf(setup).deviceId(), PhoenixUtil.bus(leaderSpecOf(setup).canBus())),
        buildFollowers(setup.motors().followers()),
        leaderSpecOf(setup),
        units,
        control,
        kind,
        setup.current(),
        setup.limits(),
        setup.feedback(),
        tier,
        true);
  }

  private TalonFXSMotorIO(
      TalonFXS leader,
      List<FollowerDevice> followers,
      MotorSpec.TalonFXSSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      CurrentLimits current,
      PositionLimits limits,
      FeedbackSpec feedback,
      Tier tier,
      boolean fullSetup) {
    super(
        new Setup(
            spec.name(), units, control, kind, limits, feedback, spec, proLicensed(leader), tier),
        leader,
        leader,
        followers);
    m_leaderFxs = leader;
    m_talonSpec = spec;
    // Never null: a controller with no current limit at all is a fire risk, so the bare-spec form
    // substitutes the model defaults rather than leaving the device unlimited.
    m_currentLimits = current == null ? CurrentLimits.defaultsFor(spec.model()) : current;
    m_fullSetup = fullSetup;

    m_followerFxs = new TalonFXS[followers.size()];
    m_followerConfigs = new TalonFXSConfiguration[followers.size()];
    for (int i = 0; i < followers.size(); i++) {
      m_followerFxs[i] = (TalonFXS) followers.get(i).device();
      m_followerConfigs[i] = new TalonFXSConfiguration();
    }

    buildConfig();
    warnIfDeviceHasNoSensor();
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

    // 4. CURRENT LIMITS, from the DeviceSetup and NOT from m_limits.current(): a velocity or an
    //    open-loop mechanism has no PositionLimits at all, and reading the limits off one meant the
    //    declared amps were silently replaced by the motor model's defaults.
    CurrentLimits current = m_currentLimits;
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
      // hi > lo, not merely finite: a PositionConfig whose .softLimits(...) was never called carries
      // a placeholder range of exactly zero to zero, and arming the firmware at [0, 0] would pin the
      // mechanism at zero on top of the fatal config error that placeholder already raises.
      boolean usable = Double.isFinite(lo) && Double.isFinite(hi) && hi > lo;
      m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold = hi;
      m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable = usable;
      m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold = lo;
      m_config.SoftwareLimitSwitch.ReverseSoftLimitEnable = usable;
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
        .append(
            deviceHasNoSensor()
                ? "NO SENSOR: a brushed arrangement has no commutation sensor, so the device"
                    + " reports no position and no velocity"
                : m_feedback.sensorDescription())
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
                : m_limits == null
                    ? "NONE -- this mechanism declared no travel range, which is right for a roller"
                        + " or a flywheel and wrong for anything with two ends"
                    : "NONE armed -- the declared range is empty, so the firmware backstop is NOT"
                        + " armed")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "current limits"))
        .append(
            String.format(
                Locale.ROOT,
                "%.0f A stator, %.0f A supply%s",
                m_config.CurrentLimits.StatorCurrentLimit,
                m_config.CurrentLimits.SupplyCurrentLimit,
                m_config.CurrentLimits.SupplyCurrentLimitEnable ? "" : " (supply limit DISABLED)"))
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "followers"))
        .append(
            m_followerFxs.length == 0
                ? "none declared"
                : m_followerFxs.length + " configured and following this leader")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "built by"))
        .append(
            m_fullSetup
                ? "the DeviceSetup constructor: current limits, soft limits, hard stops, feedback"
                    + " plumbing and followers all configured on the device"
                : "the bare-spec constructor -- soft limits, hard stops, absolute feedback and"
                    + " followers were not supplied, so they are not configured on the device")
        .append(nl);
    return sb.toString().stripTrailing();
  }

  // ===================================================================================== private

  private static MotorSpec.TalonFXSSpec leaderSpecOf(MotorIOFactory.DeviceSetup setup) {
    if (setup.leader() instanceof MotorSpec.TalonFXSSpec fxs) {
      return fxs;
    }
    throw new IllegalArgumentException(
        "TalonFXSMotorIO was given a "
            + setup.leader().deviceType()
            + " as its leader. This backend only drives a TalonFXS; use MotorSpec.talonFXS(...) or"
            + " let MotorIOFactory choose the backend.");
  }

  /**
   * Whether this device, as configured, can report a position at all.
   *
   * <p>A brushed arrangement has no sensor inside the motor -- {@link MotorArrangement} says so in
   * its own javadoc, and until now nothing acted on it. Phoenix's own default for {@code
   * ExternalFeedback.ExternalFeedbackSensorSource} is {@code Commutation}, so the combination is not
   * a wrong value written by this class; it is a value that names a sensor which is not there.
   *
   * <p>Verified against Phoenix 6 26.3.0: {@code ExternalFeedbackSensorSourceValue} also offers
   * {@code Quadrature} and {@code PulseWidth} for an encoder wired to the data port, and Rootstock's
   * sealed {@code FeedbackSpec} has no variant that maps to either. So a CANcoder is the only device
   * feedback this library can currently give a brushed TalonFXS.
   *
   * @return true when the device is commutating a brushed motor and no CANcoder was plumbed
   */
  private boolean deviceHasNoSensor() {
    return !m_talonSpec.arrangement().isBrushless()
        && m_config.ExternalFeedback.ExternalFeedbackSensorSource
            == ExternalFeedbackSensorSourceValue.Commutation;
  }

  /**
   * The alert for a device that cannot measure itself, raised once at construction.
   *
   * <p>Only for a mechanism that closes a loop. An open-loop roller commands duty cycle and reads
   * nothing back, so a brushed TalonFXS driving one is a perfectly ordinary intake.
   */
  private void warnIfDeviceHasNoSensor() {
    if (!deviceHasNoSensor() || m_kind == MechanismKind.SIMPLE) {
      return;
    }
    Alerts.error(
            m_name,
            m_name
                + ": this TalonFXS is commutating a brushed motor, which has no sensor inside it,"
                + " and no CANcoder was declared. The controller has nothing to measure: its"
                + " position and velocity stay frozen, the closed loop drives against a number that"
                + " never changes, and the soft limits are evaluated against that same frozen number"
                + " so they cannot stop it either. The output saturates until the current limit"
                + " holds the mechanism against whatever it has run into. Fix: declare a CANcoder"
                + " through .feedback(new FeedbackSpec.RemoteCancoder(...)), which needs no Phoenix"
                + " Pro license, or change the arrangement to MINION_JST, NEO_JST, NEO550_JST or"
                + " VORTEX_JST -- those are brushless and carry a sensor.",
            MatchImpact.BLOCKS_MATCH)
        .set(true);
  }

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

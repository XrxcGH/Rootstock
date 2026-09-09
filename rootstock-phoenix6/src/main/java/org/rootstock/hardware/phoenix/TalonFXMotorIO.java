package org.rootstock.hardware.phoenix;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.FeedbackSensorSourceValue;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import com.ctre.phoenix6.sim.TalonFXSimState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.HardStop;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorModel;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.hardware.MotorIOFactory;
import org.rootstock.core.spi.Tier;
import org.rootstock.units.MechanismUnits;

/**
 * The Phoenix 6 TalonFX backend — a Kraken X60, a Kraken X44 or a Falcon 500.
 *
 * <p>This is where roughly nine hundred lines of a typical team's motor-IO template collapse into
 * one class: the gearbox is expressed once, the geometry is expressed once, the gains are converted
 * once, the status signals are subscribed and read in one statement, and a device that reboots
 * mid-match is put back together without anybody noticing except the alert log.
 *
 * <h2>Everything below comes from the config. The team types none of it.</h2>
 *
 * <p>{@link #buildConfig()} is the whole device configuration, derived: the reduction becomes
 * {@code Feedback.SensorToMechanismRatio} — which is <b>why</b> the {@code MotorIO} seam speaks
 * output rotations, because after that line every position, velocity, soft limit and Motion Magic
 * number on the device is already in them. The soft limits are converted by {@code MechanismUnits}
 * and ordered by the library, so a negative-reduction sign hack cannot invert them. The gravity type
 * follows the axis. The gains go through {@link Phoenix6GainSink}, once.
 *
 * <h2>Two constructors, and why the second one exists</h2>
 *
 * <p>{@link org.rootstock.hardware.MotorIOFactory.Backend} hands a backend a {@link
 * MotorIOFactory.DeviceSetup}: the whole motor group, the declared {@link CurrentLimits}, the
 * {@link PositionLimits} travel range and hard stops, and the {@link FeedbackSpec} absolute
 * plumbing. That form configures everything the mechanism declared. The other form takes a bare
 * {@code MotorSpec}, for a motor used on its own outside a mechanism, and can configure only what a
 * spec carries: gearing, gains, profile, inversion, neutral mode and the motor model's default
 * current limits. {@link #describe()} says which form built a given backend.
 *
 * <p>This distinction is not academic. Until the {@code DeviceSetup} existed, every mechanism the
 * library built went through the bare-spec form, so a declared 70 A stator limit, a declared soft
 * limit, a declared CANcoder and a declared second Kraken all stopped at the seam and never reached
 * the device -- while the boot dump reported the mechanism as configured.
 */
public final class TalonFXMotorIO extends AbstractPhoenixMotorIO {

  private final TalonFX m_leaderFx;
  private final TalonFX[] m_followerFx;
  private final TalonFXConfiguration m_config = new TalonFXConfiguration();
  private final TalonFXConfiguration[] m_followerConfigs;
  private final MotorSpec.TalonFXSpec m_talonSpec;
  private final CurrentLimits m_currentLimits;
  private final MotorModel[] m_followerModels;
  private final boolean m_fullSetup;

  /**
   * The bare-spec form, for a motor used on its own rather than inside a mechanism.
   *
   * <p>A {@code MotorSpec} does not carry a travel range, a follower list, an absolute encoder or
   * the mechanism's real current limits, so none of those are configured here. That is honest for a
   * lone motor and wrong for a mechanism; a mechanism uses the {@link MotorIOFactory.DeviceSetup}
   * form below, and {@link #describe()} states which form ran.
   *
   * @param spec the declared motor
   * @param units the mechanism's one converter
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public TalonFXMotorIO(
      MotorSpec.TalonFXSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    this(
        new TalonFX(spec.deviceId(), PhoenixUtil.bus(spec.canBus())),
        buildFollowerSet(List.of()),
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
   * <p>Everything the team declared is configured on the device: the current limits, the soft limits
   * and hard stops when a travel range is present, the CANcoder plumbing, and every follower.
   *
   * @param setup the motors, current limits, travel range and feedback plumbing the team declared
   * @param units the mechanism's one converter
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param tier the telemetry tier, which gates the diagnostic-only status signals
   * @throws IllegalArgumentException if the leader is not a TalonFX; a constructor is one of the
   *     three places design section 5.6 allows a throw, and this backend handed a SPARK is a
   *     programming error rather than a field condition
   */
  public TalonFXMotorIO(
      MotorIOFactory.DeviceSetup setup,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      Tier tier) {
    this(
        new TalonFX(
            leaderSpecOf(setup).deviceId(), PhoenixUtil.bus(leaderSpecOf(setup).canBus())),
        buildFollowerSet(setup.motors().followers()),
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

  private TalonFXMotorIO(
      TalonFX leader,
      FollowerSet followers,
      MotorSpec.TalonFXSpec spec,
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
            spec.name(),
            units,
            control,
            kind,
            limits,
            feedback,
            spec,
            proLicensed(leader),
            tier),
        leader,
        leader,
        followers.devices());
    m_leaderFx = leader;
    m_talonSpec = spec;
    // Never null: a TalonFX with no current limit at all is a fire risk, so the bare-spec form
    // substitutes the model defaults rather than leaving the device unlimited.
    m_currentLimits = current == null ? CurrentLimits.defaultsFor(spec.model()) : current;
    m_followerModels = followers.models();
    m_fullSetup = fullSetup;

    List<FollowerDevice> devices = followers.devices();
    m_followerFx = new TalonFX[devices.size()];
    m_followerConfigs = new TalonFXConfiguration[devices.size()];
    for (int i = 0; i < devices.size(); i++) {
      m_followerFx[i] = (TalonFX) devices.get(i).device();
      m_followerConfigs[i] = new TalonFXConfiguration();
    }

    applySimMotorType();
    buildConfig();
    // CONSTRUCTION path: blocking, read back and verified. This is one of the few legal callers.
    PhoenixUtil.applyVerified(m_leaderFx, m_config, motorName());
    for (int i = 0; i < m_followerFx.length; i++) {
      buildFollowerConfig(i);
      PhoenixUtil.applyVerified(m_followerFx[i], m_followerConfigs[i], motorName() + "/follower" + i);
    }

    completeConstruction(
        m_config.Slot0,
        m_config.MotionMagic,
        m_config.MotorOutput,
        slot -> PhoenixUtil.applyFast(m_leaderFx, slot),
        motionMagic -> PhoenixUtil.applyFast(m_leaderFx, motionMagic),
        output -> PhoenixUtil.applyFast(m_leaderFx, output),
        tier);
  }

  // ============================================================================ config derivation

  /**
   * Every field of the device configuration, derived from the declared mechanism.
   *
   * <p>Read as a list of the things that would otherwise be typed by hand into a constants file and
   * be wrong in one of them.
   */
  private void buildConfig() {
    MechanismUnits u = m_units;
    double siPerRot = u.siPerOutputRotation();

    // 1. GEARBOX. The single place the reduction is expressed for Phoenix. After this line, every
    //    getPosition(), setPosition(), soft limit and Motion Magic value on this device is in
    //    OUTPUT ROTATIONS -- which is exactly why the MotorIO seam's unit is output rotations.
    m_config.Feedback.SensorToMechanismRatio = u.rotorPerOutput();

    // 2. FEEDBACK SOURCE. RotorToSensorRatio exists only when a remote sensor is in the loop, and
    //    Phoenix requires RotorToSensorRatio x SensorToMechanismRatio == rotor-per-output. That
    //    identity is a Tier-1 validation rule upstream; here it is simply obeyed.
    if (m_feedback instanceof FeedbackSpec.FusedCancoder fused) {
      m_config.Feedback.FeedbackSensorSource = FeedbackSensorSourceValue.FusedCANcoder;
      m_config.Feedback.FeedbackRemoteSensorID = fused.cancoderId();
      m_config.Feedback.RotorToSensorRatio = fused.rotorPerSensor();
      m_config.Feedback.SensorToMechanismRatio = fused.sensorPerOutput();
    } else if (m_feedback instanceof FeedbackSpec.RemoteCancoder remote) {
      m_config.Feedback.FeedbackSensorSource = FeedbackSensorSourceValue.RemoteCANcoder;
      m_config.Feedback.FeedbackRemoteSensorID = remote.cancoderId();
      m_config.Feedback.RotorToSensorRatio = remote.rotorPerSensor();
      m_config.Feedback.SensorToMechanismRatio = remote.sensorPerOutput();
    } else {
      m_config.Feedback.FeedbackSensorSource = FeedbackSensorSourceValue.RotorSensor;
    }

    // 3. MOTOR OUTPUT. Direction lives on the spec and nowhere else, so "the mechanism runs
    //    backwards" is fixed by one flag rather than by negating a gear ratio.
    m_config.MotorOutput.Inverted =
        m_talonSpec.inverted()
            ? InvertedValue.Clockwise_Positive
            : InvertedValue.CounterClockwise_Positive;
    m_config.MotorOutput.NeutralMode =
        m_control.neutralMode() == NeutralMode.BRAKE ? NeutralModeValue.Brake : NeutralModeValue.Coast;

    // 4. CURRENT LIMITS. Stator limits torque; supply limits what the battery is asked for. Setting
    //    one when you meant the other is a whole class of mystery brownout.
    // These come from the DeviceSetup, NOT from m_limits.current(): a shooter and an intake have
    // no travel range at all, so reading the limits off a PositionLimits meant their declared
    // 80/40 A was silently replaced by the motor model's defaults while PositionMechanism set its
    // stall threshold from the declared number. The software's protection was calibrated to a
    // figure the device did not have.
    CurrentLimits current = m_currentLimits;
    m_config.CurrentLimits.StatorCurrentLimit = current.statorAmps();
    m_config.CurrentLimits.StatorCurrentLimitEnable = true;
    m_config.CurrentLimits.SupplyCurrentLimit = current.supplyAmps();
    m_config.CurrentLimits.SupplyCurrentLimitEnable = current.hasSupplyLimit();
    m_config.CurrentLimits.SupplyCurrentLowerLimit = current.supplyLowerAmps();
    m_config.CurrentLimits.SupplyCurrentLowerTime = current.supplyLowerSeconds();

    // 5. SOFT LIMITS, converted by MechanismUnits and never by hand, and ORDERED by the library so
    //    that a reversed pair is impossible rather than merely unlikely.
    if (m_limits != null) {
      double a = u.toOutputRotations(m_limits.range().min());
      double b = u.toOutputRotations(m_limits.range().max());
      double lo = Math.min(a, b);
      double hi = Math.max(a, b);
      // hi > lo, not merely finite. A PositionConfig whose .softLimits(...) was never called still
      // carries a placeholder range of exactly zero to zero, which validation reports as a fatal
      // config error. Arming the firmware at [0, 0] on top of that would pin the mechanism at zero
      // and turn a readable config error into a mechanism that does not move for a reason the
      // driver station never mentions.
      boolean usable = Double.isFinite(lo) && Double.isFinite(hi) && hi > lo;
      m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold = hi;
      m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable = usable;
      m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold = lo;
      m_config.SoftwareLimitSwitch.ReverseSoftLimitEnable = usable;
    }

    // 5b. HARDWARE limit switches, enabled only when the team declared one. A hard limit wired into
    //     the controller stops the motor in FIRMWARE, which is the only kind that still works when
    //     robot code hangs.
    m_config.HardwareLimitSwitch.ForwardLimitEnable =
        m_limits != null && m_limits.usesMotorLimit(HardStop.FORWARD);
    m_config.HardwareLimitSwitch.ReverseLimitEnable =
        m_limits != null && m_limits.usesMotorLimit(HardStop.REVERSE);

    // 6. GAINS + GRAVITY, through the one converter. See Phoenix6GainSink for the arithmetic.
    Phoenix6GainSink.writeInto(
        m_config.Slot0,
        m_control.gains(),
        m_control.gravity(),
        siPerRot,
        u.toOutputRotations(u.axis().horizontalReference()));

    // 7. PROFILE, in OUTPUT rotations/s, /s^2 and /s^3. ALL THREE are converted: jerk arrives in
    //    USER units per second cubed and MotionMagicJerk is rot/s^3, so passing it raw is off by 360
    //    on a rotary axis. Zero is Phoenix's "no limit from this field".
    m_config.MotionMagic.MotionMagicCruiseVelocity =
        profileValue(u.toOutputRps(m_control.constraints().maxVelocity()));
    m_config.MotionMagic.MotionMagicAcceleration =
        profileValue(u.toOutputRps2(m_control.constraints().maxAcceleration()));
    m_config.MotionMagic.MotionMagicJerk =
        profileValue(u.toOutputRps3(m_control.constraints().jerk()));

    // 7b. MOTION MAGIC EXPO. Selecting an Expo request without setting these hands the device CTRE's
    //     factory defaults (0.12 V/rps, 0.1 V/rps^2) and produces a profile shaped by CTRE's
    //     arbitrary numbers instead of the measured plant. Expo's terms are always in VOLTS, so the
    //     conversion is the same volts-per-SI to volts-per-output-rotation factor as Slot0.
    if (m_useExpo) {
      m_config.MotionMagic.MotionMagicExpo_kV =
          Phoenix6GainSink.expoKv(m_control.gains(), siPerRot);
      m_config.MotionMagic.MotionMagicExpo_kA =
          Phoenix6GainSink.expoKa(m_control.gains(), siPerRot);
    }

    // 8. CONTINUOUS WRAP, derived from the axis. A turret may take the short way around 0/360; an
    //    arm with finite travel may not, and letting it would unwind the mechanism through a stop.
    m_config.ClosedLoopGeneral.ContinuousWrap = u.axis().isContinuous();
  }

  /**
   * A follower's configuration: the same current limits, neutral mode and inversion handling as the
   * leader, and nothing about the loop — a follower does not close one.
   */
  private void buildFollowerConfig(int index) {
    TalonFXConfiguration follower = m_followerConfigs[index];
    follower.MotorOutput.NeutralMode = m_config.MotorOutput.NeutralMode;
    // The Follower request carries the direction (Aligned / Opposed), so the follower's own Inverted
    // stays at the leader's value; setting BOTH is how a follower ends up fighting its leader.
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
    // The zero-timeout overload on purpose: the default one blocks the main loop for up to 100 ms,
    // and the guarded re-seed happens immediately before a move starts.
    return m_leaderFx.setPosition(outputRotations, 0.0);
  }

  @Override
  protected boolean supportsFoc() {
    return true;
  }

  @Override
  protected boolean supportsTorqueCurrent() {
    // The hardware can. Rootstock v0.1 does not use it: gains are volts-per-SI and a torque-current
    // slot wants amps-per-SI, a different number for every one of the seven.
    return true;
  }

  @Override
  protected String deviceTypeName() {
    return "TalonFX";
  }

  // ================================================================================== simulation

  /**
   * The simulation handle, which drives the <b>real</b> ratio path.
   *
   * <p>{@code setRawRotorPosition} is in <b>rotor</b> rotations, and the device divides by the
   * {@code SensorToMechanismRatio} this class configured to report output rotations. So a
   * mis-entered gear ratio produces a mechanism that moves at the wrong speed in {@code
   * simulateJava}, on a laptop, in week two — rather than at the first event. Simulating through a
   * plant that bypasses the device's own conversion would test the simulation, not the robot.
   *
   * @return the handle; always present, because a TalonFX always has a sim state
   */
  @Override
  public Optional<SimMotorHandle> simHandle() {
    return Optional.of(new Handle());
  }

  private final class Handle implements SimMotorHandle {

    @Override
    public double appliedVolts(double busVoltage) {
      TalonFXSimState sim = m_leaderFx.getSimState();
      sim.setSupplyVoltage(Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : 12.0);
      return sim.getMotorVoltage();
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      TalonFXSimState sim = m_leaderFx.getSimState();
      if (Double.isFinite(rotorRotations)) {
        sim.setRawRotorPosition(rotorRotations);
      }
      if (Double.isFinite(rotorRps)) {
        sim.setRotorVelocity(rotorRps);
      }
      for (TalonFX follower : m_followerFx) {
        TalonFXSimState followerSim = follower.getSimState();
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
      // TalonFXSimState exposes torque current and supply current, not stator. Torque current is the
      // torque-producing component and is the honest answer to "what is this motor pulling"; naming
      // it stator would be a small lie in a number a brownout model reads.
      return m_leaderFx.getSimState().getTorqueCurrent();
    }
  }

  // ================================================================================== escape hatch

  /**
   * The live TalonFX, for the one-in-twenty thing Rootstock does not model.
   *
   * <p>Reached through {@code io.as(TalonFXMotorIO.class)}, so core never names a vendor type.
   *
   * @return the leader device
   */
  public TalonFX talonFX() {
    return m_leaderFx;
  }

  /**
   * The followers, in declaration order.
   *
   * @return a copy of the follower array; mutating it changes nothing
   */
  public List<TalonFX> followers() {
    return List.of(m_followerFx);
  }

  /**
   * Mutate the device configuration and re-apply it, verified.
   *
   * <p>Blocking, and therefore <b>not</b> legal from an enabled {@code periodic()}. This is the
   * "Rootstock does not model beep-on-boot" hatch: {@code io.applyRaw(cfg -> cfg.Audio.BeepOnBoot =
   * false)}. The mutation lands on the same configuration object the reset-recovery path re-applies,
   * so it survives a mid-match device reboot.
   *
   * @param mutation what to change
   * @return true when the changed configuration read back matching
   */
  public boolean applyRaw(Consumer<TalonFXConfiguration> mutation) {
    if (mutation == null) {
      return true;
    }
    mutation.accept(m_config);
    return PhoenixUtil.applyVerified(m_leaderFx, m_config, motorName());
  }

  // ==================================================================================== describe

  @Override
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(2048);
    sb.append(describeCommon()).append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "gearing"))
        .append(
            String.format(
                java.util.Locale.ROOT,
                "Feedback.SensorToMechanismRatio = %.5f rotor rot per output rot",
                m_config.Feedback.SensorToMechanismRatio))
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "feedback source"))
        .append(m_config.Feedback.FeedbackSensorSource)
        .append(
            m_feedback.isFusedOnDevice()
                ? "  (fused on the device, so Rootstock performs NO guarded re-seed -- Phoenix"
                    + " already keeps the rotor and the CANcoder in step)"
                : "  (" + m_feedback.sensorDescription() + ")")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "soft limits"))
        .append(
            m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable
                ? String.format(
                    java.util.Locale.ROOT,
                    "[%.4f, %.4f] output rotations, ENABLED on the device AND re-clamped in Java",
                    m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold,
                    m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold)
                : m_limits == null
                    ? "NONE -- this mechanism declared no travel range, which is right for a roller"
                        + " or a flywheel and wrong for anything with two ends"
                    : "NONE armed -- the declared range is empty, so the firmware backstop that"
                        + " still works when robot code hangs is NOT armed")
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "current limits"))
        .append(
            String.format(
                java.util.Locale.ROOT,
                "%.0f A stator, %.0f A supply%s",
                m_config.CurrentLimits.StatorCurrentLimit,
                m_config.CurrentLimits.SupplyCurrentLimit,
                m_config.CurrentLimits.SupplyCurrentLimitEnable ? "" : " (supply limit DISABLED)"))
        .append(nl);
    sb.append("  ")
        .append(String.format("%-20s", "followers"))
        .append(
            m_followerFx.length == 0
                ? "none declared"
                : m_followerFx.length + " configured and following this leader")
        .append(nl);
    if (Platform.isSimulation()) {
      sb.append("  ")
          .append(String.format("%-20s", "sim motor curve"))
          .append(
              simMotorType(m_talonSpec.model()) == null
                  ? m_talonSpec.model().displayName()
                      + " -- Phoenix ships no simulation curve for it, so the device simulator uses"
                      + " its Kraken X60 default. Simulated current is NOT this motor's."
                  : m_talonSpec.model().displayName() + ", set on the device simulator")
          .append(nl);
    }
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

  /**
   * The follower devices and their motor models, built in ONE pass.
   *
   * <p>One pass because the two lists are index-aligned and the filter that skips a non-TalonFX
   * follower has to be applied identically to both. Two loops with two copies of that filter is one
   * edit away from a device that is configured as the wrong motor.
   *
   * @param devices the constructed followers, in declaration order
   * @param models each follower's motor model, at the same index
   */
  private record FollowerSet(List<FollowerDevice> devices, MotorModel[] models) {}

  private static FollowerSet buildFollowerSet(List<MotorGroup.FollowerSpec> specs) {
    List<FollowerDevice> devices = new ArrayList<>();
    List<MotorModel> models = new ArrayList<>();
    if (specs == null) {
      return new FollowerSet(devices, new MotorModel[0]);
    }
    for (MotorGroup.FollowerSpec follower : specs) {
      if (follower == null || !(follower.spec() instanceof MotorSpec.TalonFXSpec fx)) {
        continue;
      }
      TalonFX device = new TalonFX(fx.deviceId(), PhoenixUtil.bus(fx.canBus()));
      devices.add(new FollowerDevice(device, device, follower.sense()));
      models.add(fx.model());
    }
    return new FollowerSet(devices, models.toArray(new MotorModel[0]));
  }

  private static MotorSpec.TalonFXSpec leaderSpecOf(MotorIOFactory.DeviceSetup setup) {
    if (setup.leader() instanceof MotorSpec.TalonFXSpec fx) {
      return fx;
    }
    throw new IllegalArgumentException(
        "TalonFXMotorIO was given a "
            + setup.leader().deviceType()
            + " as its leader. This backend only drives a TalonFX; use MotorSpec.talonFX(...) or"
            + " let MotorIOFactory choose the backend.");
  }

  /**
   * Tell the device simulator which motor is inside, once.
   *
   * <p>Phoenix 6 26.3.0 ships two TalonFX motor curves and defaults to the Kraken X60. Running the
   * pinned wpimath 2026.2.2 jar, the two differ by more than a rounding error: X60 R = 0.03279 ohm
   * and kV = 52.65, X44 R = 0.04301 ohm and kV = 68.19, which at 6 V stalled is 183.0 A against
   * 139.5 A. A team that declares a Kraken X44 and sets a stall-detection threshold in simulation
   * is otherwise reading the wrong motor's current.
   *
   * <p>Set once, in the constructor, and not on the per-cycle sim path: {@code setMotorType} pushes
   * a physics input over JNI and the value never changes.
   *
   * <p>There is no Falcon 500 {@code MotorType}. A Falcon keeps simulating on the Kraken X60 curve
   * whatever this method does, and {@link #describe()} says so rather than implying otherwise.
   */
  private void applySimMotorType() {
    if (!Platform.isSimulation()) {
      return;
    }
    TalonFXSimState.MotorType leaderType = simMotorType(m_talonSpec.model());
    if (leaderType != null) {
      m_leaderFx.getSimState().setMotorType(leaderType);
    }
    for (int i = 0; i < m_followerFx.length && i < m_followerModels.length; i++) {
      TalonFXSimState.MotorType followerType = simMotorType(m_followerModels[i]);
      if (followerType != null) {
        m_followerFx[i].getSimState().setMotorType(followerType);
      }
    }
  }

  private static TalonFXSimState.MotorType simMotorType(MotorModel model) {
    return switch (model) {
      case KRAKEN_X60 -> TalonFXSimState.MotorType.KrakenX60;
      case KRAKEN_X44 -> TalonFXSimState.MotorType.KrakenX44;
      // Every other model a TalonFXSpec accepts is a Falcon 500, for which Phoenix ships no curve.
      default -> null;
    };
  }

  /**
   * Whether the device reports a Phoenix Pro licence, read once at construction.
   *
   * <p>Pro plus a CANivore is what {@code DynamicMotionMagicVoltage} requires. In simulation this is
   * false, and {@code describe()} says so rather than pretending a per-call profile is available.
   */
  private static boolean proLicensed(TalonFX leader) {
    return Boolean.TRUE.equals(leader.getIsProLicensed().refresh().getValue());
  }

  private static double profileValue(double converted) {
    // Phoenix reads 0 as "no limit imposed by this field", which is the right answer for an
    // unconstrained profile. An infinity written into a device config is not.
    return Double.isFinite(converted) && converted > 0.0 ? converted : 0.0;
  }
}

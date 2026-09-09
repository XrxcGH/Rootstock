package org.rootstock.hardware.phoenix;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.MotionMagicConfigs;
import com.ctre.phoenix6.configs.MotorOutputConfigs;
import com.ctre.phoenix6.configs.Slot0Configs;
import com.ctre.phoenix6.controls.DutyCycleOut;
import com.ctre.phoenix6.controls.DynamicMotionMagicVoltage;
import com.ctre.phoenix6.controls.Follower;
import com.ctre.phoenix6.controls.MotionMagicExpoVoltage;
import com.ctre.phoenix6.controls.MotionMagicVelocityVoltage;
import com.ctre.phoenix6.controls.MotionMagicVoltage;
import com.ctre.phoenix6.controls.NeutralOut;
import com.ctre.phoenix6.controls.PositionVoltage;
import com.ctre.phoenix6.controls.VelocityVoltage;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.ParentDevice;
import com.ctre.phoenix6.hardware.traits.CommonTalon;
import com.ctre.phoenix6.signals.ForwardLimitValue;
import com.ctre.phoenix6.signals.MotorAlignmentValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import com.ctre.phoenix6.signals.ReverseLimitValue;
import edu.wpi.first.math.MathUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.HardStop;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.Gains;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.Tier;
import org.rootstock.hardware.MotorCapabilities;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorInputs;
import org.rootstock.hardware.RioControlLoop;
import org.rootstock.hardware.SignalSet;
import org.rootstock.hardware.VelocityCarrier;
import org.rootstock.units.MechanismUnits;

/**
 * Everything a TalonFX and a TalonFXS share, which is roughly ninety per cent of both.
 *
 * <p>A TalonFXS is <em>not</em> a TalonFX with a different name — it has {@code Commutation} and
 * {@code ExternalFeedback} config groups where a TalonFX has {@code Feedback}, and getting the
 * commutation wrong produces a motor that does nothing at all with no fault and no message. But
 * {@code Slot0}, {@code MotionMagic}, {@code SoftwareLimitSwitch}, {@code CurrentLimits}, {@code
 * MotorOutput}, {@code HardwareLimitSwitch} and {@code ClosedLoopGeneral} are identical on both, and
 * both devices implement Phoenix's {@code CommonTalon} trait — so the signal set, the reset
 * detector, the heartbeat latch, the goal-velocity carrier and the gain conversion live here, once,
 * and only the feedback/commutation block differs.
 *
 * <h2>The three things this class exists to get right</h2>
 *
 * <ol>
 *   <li><b>You cannot read a signal you did not subscribe.</b> Subscription and read are one
 *       statement, through the core {@link SignalSet}. An unsubscribed channel reads {@code NaN},
 *       not a plausible zero frozen at its power-on value.
 *   <li><b>A device that resets mid-match comes back with its persisted configuration but no active
 *       control request, and outputs neutral.</b> An elevator held by Motion Magic silently falls.
 *       {@link #updateInputs(MotorInputs)} polls {@code hasResetOccurred()}, re-applies the full
 *       configuration, re-sends the follower requests and forces the next goal to be transmitted.
 *   <li><b>The Motion Magic requests have no velocity field.</b> Verified against Phoenix 6 26.3.0:
 *       {@code MotionMagicVoltage} and {@code MotionMagicExpoVoltage} expose {@code withPosition}
 *       and {@code withFeedForward} and nothing velocity-shaped at all. So a nonzero goal velocity —
 *       a field-locked turret's counter-rotation term — rides as <b>volts</b> on the
 *       arbitrary-feedforward field. See {@link #setPositionGoal(double, double, double,
 *       MotionConstraints)}.
 * </ol>
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>Every method reachable from a mechanism's {@code periodic()} degrades and names itself. An
 * internal invariant violation raises an alert that says which invariant and what to do; it does not
 * kill the robot with a stack trace fifteen minutes before a match.
 */
abstract class AbstractPhoenixMotorIO implements MotorIO {

  // ----------------------------------------------------------------------------- signal rates
  /**
   * 100 Hz, not 50, for a position mechanism: a 50 Hz signal sampled by a 50 Hz loop aliases and can
   * add a full 20 ms of latency to the measurement the whole control loop depends on. It is not free
   * — see {@link #describeSignalBudget()} — which is why only POSITION mechanisms get it.
   */
  static final double kPositionRateHz = 100.0;

  /** A flywheel's arrival gate is debounced over tens of milliseconds and gains nothing from 100 Hz. */
  static final double kVelocityMechanismRateHz = 50.0;

  /** Applied volts: enough to plot, not enough to matter. */
  static final double kAppliedVoltsRateHz = 50.0;

  /** Stator current is NOT optional: current-spike homing and stall detection are built on it. */
  static final double kStatorRateHz = 50.0;

  /** Supply current is NOT optional either: it feeds the brownout and power monitors. */
  static final double kSupplyRateHz = 20.0;

  /** Temperature changes on the timescale of a match, not a loop. */
  static final double kTemperatureRateHz = 4.0;

  /** Genuinely diagnostic-only channels, subscribed at DEBUG tier and above. */
  static final double kDiagnosticRateHz = 50.0;

  /** Followers are watched for divergence and heat, not controlled. */
  static final double kFollowerRateHz = 20.0;

  /** Follower temperature. */
  static final double kFollowerTemperatureRateHz = 4.0;

  /**
   * Bits on the wire per 8-byte CAN 2.0B extended frame, as the budget in {@link #describe()} counts
   * them.
   *
   * <p>131 bits nominal (SOF 1 + 29-bit identifier + SRR/IDE/RTR 3 + reserved 2 + DLC 4 + data 64 +
   * CRC 15 + delimiter 1 + ACK 2 + EOF 7 + inter-frame space 3) plus an expected 9 stuff bits. The
   * honest range is 131 to 160, so every percentage printed carries about seven per cent of its own
   * value in uncertainty. An earlier revision priced the payload as 8 <em>bits</em> and was wrong by
   * roughly nineteen times.
   */
  static final double kBitsPerFrame = 140.0;

  /** A 1 Mbps {@code rio} bus. CAN FD changes these numbers substantially in our favour. */
  static final double kBusBitsPerSecond = 1.0e6;

  /**
   * How long an unchanged goal may go un-re-sent.
   *
   * <p>Phoenix latches the last setpoint, so re-sending an identical goal every loop is pure CAN
   * waste. But never re-sending is how a device reset goes unnoticed, so the latch carries a 10 Hz
   * heartbeat.
   */
  static final double kHeartbeatSeconds = 0.100;

  // ------------------------------------------------------------------------------- immutable state
  protected final String m_name;
  protected final String m_bus;
  protected final MechanismUnits m_units;
  protected final ControlConfig m_control;
  protected final MechanismKind m_kind;
  protected final PositionLimits m_limits;
  protected final FeedbackSpec m_feedback;
  protected final MotorSpec m_spec;

  /**
   * FOC and output mode are <b>orthogonal</b> and this field is only the first of them.
   *
   * <p>FOC changes commutation: it reaches {@code ControlRequest.withEnableFOC(...)} and nothing
   * else. It does not change gain units, does not change which request objects are used, and is not
   * torque-current control. Conflating the two is how a config ends up with {@code kV = 0.62}
   * amps-per-metre-per-second, which is a meaningless number.
   */
  protected final boolean m_foc;

  protected final boolean m_useExpo;
  protected final boolean m_proLicensed;

  /** {@code DynamicMotionMagicVoltage} requires Phoenix Pro <b>and</b> a CANivore. */
  protected final boolean m_dynamicCapable;

  protected final ControlLocation m_requestedLocation;
  protected final ControlLocation m_location;
  protected final double m_signalRateHz;

  private final CommonTalon m_leader;
  private final ParentDevice m_leaderDevice;
  private final CommonTalon[] m_followers;
  private final ParentDevice[] m_followerDevices;
  private final Follower[] m_followerRequests;
  private final StatusSignal<?>[] m_followerPositionSignals;
  private final StatusSignal<?>[] m_followerStatorSignals;
  private final StatusSignal<?>[] m_followerTemperatureSignals;
  private final BaseStatusSignal[] m_followerBatch;

  // ------------------------------------------------------------- pre-allocated control requests
  // NEVER allocated in periodic(). Every one of these is mutated in place and re-sent.
  private final MotionMagicVoltage m_mmVoltage = new MotionMagicVoltage(0.0).withSlot(0);
  private final MotionMagicExpoVoltage m_mmExpo = new MotionMagicExpoVoltage(0.0).withSlot(0);

  /**
   * Three constructor arguments, not four: {@code DynamicMotionMagicVoltage(position, velocity,
   * acceleration)}. There is no jerk argument in Phoenix 6 26.3.0 — jerk is chained on afterwards.
   */
  private final DynamicMotionMagicVoltage m_mmDynamic =
      new DynamicMotionMagicVoltage(0.0, 0.0, 0.0).withSlot(0);

  private final PositionVoltage m_posVoltage = new PositionVoltage(0.0).withSlot(0);
  private final MotionMagicVelocityVoltage m_mmVelocity =
      new MotionMagicVelocityVoltage(0.0).withSlot(0);
  private final VelocityVoltage m_velVoltage = new VelocityVoltage(0.0).withSlot(0);
  private final VoltageOut m_voltageOut = new VoltageOut(0.0);
  private final DutyCycleOut m_dutyOut = new DutyCycleOut(0.0);
  private final NeutralOut m_neutralOut = new NeutralOut();

  // ------------------------------------------------------------------------------- mutable state
  private SignalSet<BaseStatusSignal> m_signals;
  private MotorCapabilities m_capabilities = MotorCapabilities.rioOnly();
  private Phoenix6GainSink m_gainSink;
  private RioControlLoop m_rioLoop;
  private Slot0Configs m_slot;
  private MotionMagicConfigs m_motionMagic;
  private MotorOutputConfigs m_motorOutput;
  private Function<MotionMagicConfigs, StatusCode> m_motionMagicWriter;
  private Function<MotorOutputConfigs, StatusCode> m_motorOutputWriter;

  private double m_kvDevice;
  private MotionConstraints m_lastConstraints;
  private double m_lastGoalRot = Double.NaN;
  private double m_lastGoalRps = Double.NaN;
  private double m_lastGoalRps2 = Double.NaN;
  private double m_lastArbFf = Double.NaN;
  private boolean m_lastRequestWasPosition;
  private boolean m_lastRequestWasVelocity;
  private double m_lastSendSeconds = Double.NEGATIVE_INFINITY;
  private long m_deviceResetCount;
  private double m_measuredRot;
  private double m_measuredRps;
  private boolean m_stampedCallerInputs;
  private boolean m_routingFaultReported;
  private boolean m_dynamicOverrideWarned;

  private RootstockAlert m_resetAlert;
  private RootstockAlert m_routingFaultAlert;
  private RootstockAlert m_dynamicUnavailableAlert;

  /**
   * Everything the base needs that is not a device handle.
   *
   * @param name the mechanism-scoped motor name used in log keys and alerts
   * @param units the one converter for this mechanism
   * @param control gains, constraints, gravity model, neutral mode and control location
   * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
   * @param limits soft limits and current limits; null means "not declared", and the device gets no
   *     soft limits rather than an invented pair
   * @param feedback how the absolute position is plumbed; null means rotor only
   * @param spec the declared motor
   * @param proLicensed whether the device reports a Phoenix Pro licence
   * @param tier the telemetry tier, which gates the genuinely diagnostic-only signals
   */
  record Setup(
      String name,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      PositionLimits limits,
      FeedbackSpec feedback,
      MotorSpec spec,
      boolean proLicensed,
      Tier tier) {}

  /**
   * One follower, as the base tracks it.
   *
   * @param talon the follower for control and signals
   * @param device the same object, for configuration
   * @param sense whether it turns with the leader or against it
   */
  record FollowerDevice(
      CommonTalon talon, ParentDevice device, org.rootstock.config.Follower sense) {}

  /**
   * Builds the shared half of a Phoenix motor backend.
   *
   * @param setup the declared configuration
   * @param leader the leader, for control requests and status signals
   * @param leaderDevice the same object, for configuration writes
   * @param followers the followers, possibly empty
   */
  protected AbstractPhoenixMotorIO(
      Setup setup, CommonTalon leader, ParentDevice leaderDevice, List<FollowerDevice> followers) {
    Objects.requireNonNull(setup, "AbstractPhoenixMotorIO: setup must not be null");
    m_leader = Objects.requireNonNull(leader, "AbstractPhoenixMotorIO: leader must not be null");
    m_leaderDevice =
        Objects.requireNonNull(leaderDevice, "AbstractPhoenixMotorIO: leader device required");
    m_units = Objects.requireNonNull(setup.units(), "AbstractPhoenixMotorIO: units must not be null");
    m_control =
        Objects.requireNonNull(setup.control(), "AbstractPhoenixMotorIO: control must not be null");
    m_spec = Objects.requireNonNull(setup.spec(), "AbstractPhoenixMotorIO: spec must not be null");
    m_name = setup.name() == null || setup.name().isBlank() ? m_spec.name() : setup.name();
    m_kind = setup.kind() == null ? MechanismKind.SIMPLE : setup.kind();
    m_limits = setup.limits();
    m_feedback = setup.feedback() == null ? new FeedbackSpec.RotorOnly() : setup.feedback();
    m_bus = PhoenixUtil.busName(leaderDevice);
    m_proLicensed = setup.proLicensed();
    m_foc = m_spec.foc() && supportsFoc();
    m_useExpo = m_control.useExpo();
    m_signalRateHz = setup.spec().hasSignalRateOverride() ? setup.spec().signalRateHz() : Double.NaN;

    // DynamicMotionMagicVoltage is documented as requiring Phoenix Pro AND a CANivore. A team on the
    // rio bus that asks for a per-call constraint override is routed elsewhere, loudly, rather than
    // silently receiving a request the device will reject.
    m_dynamicCapable = m_proLicensed && PhoenixUtil.isCanFd(leaderDevice);

    m_requestedLocation = m_control.location();

    List<FollowerDevice> list = followers == null ? List.of() : followers;
    int n = list.size();
    m_followers = new CommonTalon[n];
    m_followerDevices = new ParentDevice[n];
    m_followerRequests = new Follower[n];
    m_followerPositionSignals = new StatusSignal<?>[n];
    m_followerStatorSignals = new StatusSignal<?>[n];
    m_followerTemperatureSignals = new StatusSignal<?>[n];
    m_followerBatch = new BaseStatusSignal[n * 3];
    for (int i = 0; i < n; i++) {
      FollowerDevice f = list.get(i);
      m_followers[i] = f.talon();
      m_followerDevices[i] = f.device();
      m_followerRequests[i] =
          new Follower(
              leaderDevice.getDeviceID(),
              f.sense() != null && f.sense().opposesLeader()
                  ? MotorAlignmentValue.Opposed
                  : MotorAlignmentValue.Aligned);
      m_followerPositionSignals[i] = f.talon().getPosition();
      m_followerStatorSignals[i] = f.talon().getStatorCurrent();
      m_followerTemperatureSignals[i] = f.talon().getDeviceTemp();
      m_followerBatch[i * 3] = m_followerPositionSignals[i];
      m_followerBatch[i * 3 + 1] = m_followerStatorSignals[i];
      m_followerBatch[i * 3 + 2] = m_followerTemperatureSignals[i];
    }

    // Capabilities first, because the control location is a function of them: asking a device for a
    // loop it cannot close is answered here, once, at construction, not discovered mid-match.
    m_capabilities =
        MotorCapabilities.builder()
            .onBoardPositionLoop(true)
            .onBoardVelocityLoop(true)
            .onBoardProfile(true)
            .dynamicProfile(m_dynamicCapable)
            .onBoardGravityFeedforward(true)
            .onBoardCosineGravity(true)
            .arbitraryFeedforward(true)
            .positionGoalVelocity(carrierFor(m_requestedLocation))
            .torqueCurrentControl(supportsTorqueCurrent())
            .fusedAbsoluteEncoder(m_feedback.isFusedOnDevice())
            .readsTorqueCurrent(true)
            .readsTemperature(true)
            .reportsDeviceReset(true)
            .reportsConnectionHealth(true)
            .build();
    m_location = m_capabilities.downgrade(m_requestedLocation);
    // Re-state the carrier now that the location is settled, so describe() and the parity test agree.
    m_capabilities =
        MotorCapabilities.builder()
            .onBoardPositionLoop(true)
            .onBoardVelocityLoop(true)
            .onBoardProfile(true)
            .dynamicProfile(m_dynamicCapable)
            .onBoardGravityFeedforward(true)
            .onBoardCosineGravity(true)
            .arbitraryFeedforward(true)
            .positionGoalVelocity(carrierFor(m_location))
            .torqueCurrentControl(supportsTorqueCurrent())
            .fusedAbsoluteEncoder(m_feedback.isFusedOnDevice())
            .readsTorqueCurrent(true)
            .readsTemperature(true)
            .reportsDeviceReset(true)
            .reportsConnectionHealth(true)
            .build();
  }

  // =============================================================================== construction

  /**
   * Finishes construction once the subclass has built and applied its vendor configuration.
   *
   * <p>Called as the <b>last</b> statement of a subclass constructor. It builds the gain sink, wires
   * the batched signal set, sends the follower requests and consumes the power-on reset flag.
   *
   * @param slot the live slot inside the whole-device configuration
   * @param motionMagic the live Motion Magic config inside the same configuration
   * @param motorOutput the live motor-output config inside the same configuration
   * @param slotWriter the non-blocking slot write
   * @param motionMagicWriter the non-blocking Motion Magic write
   * @param motorOutputWriter the non-blocking motor-output write
   * @param tier the telemetry tier, gating the diagnostic-only channels
   */
  protected final void completeConstruction(
      Slot0Configs slot,
      MotionMagicConfigs motionMagic,
      MotorOutputConfigs motorOutput,
      Function<Slot0Configs, StatusCode> slotWriter,
      Function<MotionMagicConfigs, StatusCode> motionMagicWriter,
      Function<MotorOutputConfigs, StatusCode> motorOutputWriter,
      Tier tier) {
    m_slot = slot;
    m_motionMagic = motionMagic;
    m_motorOutput = motorOutput;
    m_motionMagicWriter = motionMagicWriter;
    m_motorOutputWriter = motorOutputWriter;

    m_gainSink =
        new Phoenix6GainSink(
            m_name,
            slot,
            motionMagic,
            m_control.gravity(),
            m_units.siPerOutputRotation(),
            m_units.toOutputRotations(m_units.axis().horizontalReference()),
            m_useExpo,
            m_units.siLabel(),
            slotWriter,
            motionMagicWriter);
    m_gainSink.seedApplied(m_control.gains());
    m_kvDevice = Phoenix6GainSink.deviceKv(m_control.gains(), m_units.siPerOutputRotation());
    // The whole-device configuration the subclass just applied already carries these, so the first
    // live applyConstraints() with unchanged values is correctly a no-op rather than a redundant
    // frame.
    m_lastConstraints = m_control.constraints();

    if (m_location == ControlLocation.RIO_FULL) {
      // The ONE roboRIO-side implementation, shared with every other backend. Writing a second
      // profile-plus-PID here is how two backends end up disagreeing about what "at goal" means.
      m_rioLoop = new RioControlLoop(m_units, m_control, Clock.dt());
    }

    m_signals = buildSignalSet(tier);

    for (int i = 0; i < m_followers.length; i++) {
      m_followers[i].setControl(m_followerRequests[i]);
    }
    if (m_followerBatch.length > 0) {
      BaseStatusSignal.setUpdateFrequencyForAll(kFollowerRateHz, m_followerBatch);
      for (StatusSignal<?> temperature : m_followerTemperatureSignals) {
        temperature.setUpdateFrequency(kFollowerTemperatureRateHz);
      }
      for (ParentDevice follower : m_followerDevices) {
        follower.optimizeBusUtilization();
      }
    }

    // LAST: consume and discard the power-on reset flag, once per device.
    //
    // hasResetOccurred() is documented as "true if the device has reset since the previous call of
    // this routine", and a device that has just powered on has, trivially, reset since a call that
    // never happened. If the first call were in updateInputs then EVERY boot would see a "reset" on
    // the first periodic(), run a second blocking full-config apply on the enabled hot path, raise
    // the "check the power and CAN wiring" alert and start deviceResetCount at 1 — which trains a
    // team to ignore the one alert that actually matters mid-match. If the flag is in fact not set
    // at boot, this costs one CAN-free method call per device and changes nothing.
    m_leaderDevice.hasResetOccurred();
    for (ParentDevice follower : m_followerDevices) {
      follower.hasResetOccurred();
    }
  }

  /**
   * Declares the batched subscription: one {@code add} per channel, then one {@code build}.
   *
   * <p>{@code build} stamps every channel that was <b>not</b> declared to NaN, then sets the
   * per-rate update frequencies, then optimises the bus — in that order. Optimising first would zero
   * the frequencies of the signals just requested, which is precisely the bug this whole seam was
   * written to prevent, one line earlier.
   */
  private SignalSet<BaseStatusSignal> buildSignalSet(Tier tier) {
    Tier level = tier == null ? Tier.STANDARD : tier;
    SignalSet.Builder<BaseStatusSignal> b =
        SignalSet.builder(m_name, signals -> BaseStatusSignal.refreshAll(signals).isOK());

    if (m_kind != MechanismKind.SIMPLE) {
      // Rollers have no position loop; they pay nothing for one.
      double positionRate =
          Double.isNaN(m_signalRateHz)
              ? (m_kind == MechanismKind.POSITION ? kPositionRateHz : kVelocityMechanismRateHz)
              : m_signalRateHz;
      if (m_kind == MechanismKind.POSITION) {
        b.add(
            SignalSet.Channel.POSITION,
            m_leader.getPosition(),
            positionRate,
            (in, s) -> in.positionRot = s.getValueAsDouble());
      }
      b.add(
          SignalSet.Channel.VELOCITY,
          m_leader.getVelocity(),
          positionRate,
          (in, s) -> in.velocityRps = s.getValueAsDouble());
      if (m_kind == MechanismKind.VELOCITY) {
        // A velocity mechanism still reports where it is; it just does not need it at 100 Hz. The
        // rate is the SAME positionRate the branch above uses, so describe()'s budget and the
        // subscription cannot disagree.
        b.add(
            SignalSet.Channel.POSITION,
            m_leader.getPosition(),
            positionRate,
            (in, s) -> in.positionRot = s.getValueAsDouble());
      }
    }
    b.add(
        SignalSet.Channel.APPLIED_VOLTS,
        m_leader.getMotorVoltage(),
        kAppliedVoltsRateHz,
        (in, s) -> in.appliedVolts = s.getValueAsDouble());
    b.add(
        SignalSet.Channel.STATOR,
        m_leader.getStatorCurrent(),
        kStatorRateHz,
        (in, s) -> in.statorCurrentAmps = s.getValueAsDouble());
    b.add(
        SignalSet.Channel.SUPPLY,
        m_leader.getSupplyCurrent(),
        kSupplyRateHz,
        (in, s) -> in.supplyCurrentAmps = s.getValueAsDouble());
    b.add(
        SignalSet.Channel.TEMPERATURE,
        m_leader.getDeviceTemp(),
        kTemperatureRateHz,
        (in, s) -> in.temperatureCelsius = s.getValueAsDouble());

    // Limit signals are subscribed WHENEVER a motor limit is declared, which is what makes
    // SensorSpec.motorLimit's advertised "zero extra CAN traffic -- the motor already reports it"
    // true instead of a frozen field.
    if (usesMotorLimit(HardStop.FORWARD)) {
      StatusSignal<ForwardLimitValue> forward = m_leader.getForwardLimit();
      b.add(
          SignalSet.Channel.FORWARD_LIMIT,
          forward,
          kDiagnosticRateHz,
          (in, s) -> {
            in.forwardLimitTripped = forward.getValue() == ForwardLimitValue.ClosedToGround;
            in.forwardLimitValid = true;
          });
    }
    if (usesMotorLimit(HardStop.REVERSE)) {
      StatusSignal<ReverseLimitValue> reverse = m_leader.getReverseLimit();
      b.add(
          SignalSet.Channel.REVERSE_LIMIT,
          reverse,
          kDiagnosticRateHz,
          (in, s) -> {
            in.reverseLimitTripped = reverse.getValue() == ReverseLimitValue.ClosedToGround;
            in.reverseLimitValid = true;
          });
    }

    // Genuinely diagnostic-only signals are the ONLY tier-gated ones. There is no separate
    // "telemetry level" type; the telemetry Tier the library already owns is the gate.
    if (level == Tier.DEBUG) {
      b.add(
          SignalSet.Channel.CLOSED_LOOP_REFERENCE,
          m_leader.getClosedLoopReference(),
          kDiagnosticRateHz,
          (in, s) -> in.closedLoopReferenceRot = s.getValueAsDouble());
      if (m_foc || supportsTorqueCurrent()) {
        b.add(
            SignalSet.Channel.TORQUE_CURRENT,
            m_leader.getTorqueCurrent(),
            kDiagnosticRateHz,
            (in, s) -> in.torqueCurrentAmps = s.getValueAsDouble());
      }
    }

    return b.rateApplier(BaseStatusSignal::setUpdateFrequencyForAll)
        // Passed as the optimiser rather than called before build(), because build() runs it LAST,
        // after the frequencies are set. Calling it here would zero everything just requested.
        .optimizer(m_leaderDevice::optimizeBusUtilization)
        .build(new MotorInputs());
  }

  // ================================================================================ MotorIO reads

  @Override
  public void updateInputs(MotorInputs inputs) {
    if (inputs == null) {
      return;
    }
    if (!m_stampedCallerInputs) {
      // Stamp the caller's object once, so a channel we never subscribed reads NaN in THEIR inputs
      // too — not just in the prototype build() stamped. A replayed or recycled inputs object would
      // otherwise carry a stale plausible number forever.
      m_stampedCallerInputs = true;
      for (SignalSet.Channel channel : SignalSet.Channel.values()) {
        if (!m_signals.has(channel)) {
          channel.stampAbsent(inputs);
        }
      }
    }

    boolean ok = m_signals.refreshInto(inputs);
    inputs.connected = ok && m_leaderDevice.isConnected();
    m_measuredRot = inputs.positionRot;
    m_measuredRps = inputs.velocityRps;

    // A Phoenix device that resets — brownout, CAN glitch, one motor power-cycling — comes back with
    // its PERSISTED configuration but NO ACTIVE CONTROL REQUEST, and outputs neutral. An elevator
    // held by Motion Magic silently falls. The boot flag was consumed in the constructor, so this
    // only ever fires post-boot.
    if (m_leaderDevice.hasResetOccurred()) {
      m_deviceResetCount++;
      m_leader.clearStickyFaults();
      PhoenixUtil.applyVerified(m_leaderDevice, leaderConfig(), m_name);
      // Force the next goal to be re-sent: the latch below would otherwise suppress it, because from
      // the library's point of view nothing changed.
      m_lastGoalRot = Double.NaN;
      m_lastGoalRps = Double.NaN;
      m_lastGoalRps2 = Double.NaN;
      m_lastArbFf = Double.NaN;
      m_lastSendSeconds = Double.NEGATIVE_INFINITY;
      resetAlert().set(true);
    }
    for (int i = 0; i < m_followerDevices.length; i++) {
      if (m_followerDevices[i].hasResetOccurred()) {
        m_deviceResetCount++;
        PhoenixUtil.applyVerified(m_followerDevices[i], followerConfig(i), m_name + "/follower" + i);
        m_followers[i].setControl(m_followerRequests[i]);
        resetAlert().set(true);
      }
    }
    inputs.deviceResetCount = m_deviceResetCount;

    if (m_followerBatch.length > 0) {
      BaseStatusSignal.refreshAll(m_followerBatch);
      if (inputs.followerPositionRot.length != m_followers.length) {
        inputs.followerPositionRot = new double[m_followers.length];
        inputs.followerStatorAmps = new double[m_followers.length];
        inputs.followerTemperatureC = new double[m_followers.length];
        inputs.followerConnected = new boolean[m_followers.length];
      }
      for (int i = 0; i < m_followers.length; i++) {
        inputs.followerPositionRot[i] = m_followerPositionSignals[i].getValueAsDouble();
        inputs.followerStatorAmps[i] = m_followerStatorSignals[i].getValueAsDouble();
        inputs.followerTemperatureC[i] = m_followerTemperatureSignals[i].getValueAsDouble();
        inputs.followerConnected[i] = m_followerDevices[i].isConnected();
      }
    }
  }

  // =============================================================================== MotorIO writes

  @Override
  public void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts) {
    setPositionGoal(outputRotations, outputRotationsPerSecond, arbFeedforwardVolts, null);
  }

  /**
   * Sends a position goal, latch-preserving, with a 10 Hz heartbeat.
   *
   * <h2>Where the goal velocity goes, and why</h2>
   *
   * <p>Verified against Phoenix 6 26.3.0: <b>{@code MotionMagicVoltage} has no {@code withVelocity}
   * method</b>, and neither does {@code MotionMagicExpoVoltage}. {@code DynamicMotionMagicVoltage}
   * has one, but its {@code Velocity} is the <em>cruise</em> velocity for profile generation, not a
   * goal velocity — so chaining {@code .withVelocity(goalRps).withVelocity(cruise)} makes the second
   * call silently eat the first, and a field-locked turret's counter-rotation term is dropped by the
   * library. Only {@code PositionVoltage} has a genuine goal-velocity field.
   *
   * <p>Motion Magic generates its own velocity reference internally and applies {@code Slot0.kV} to
   * it; when the profile converges that reference goes to zero. A field-locked turret needs the
   * opposite — a steady-state velocity held <em>at</em> the goal. Those requirements compose rather
   * than conflict, so the goal-velocity term becomes an explicit voltage riding on the request's
   * arbitrary-feedforward field:
   *
   * <pre>
   *   ffVolts = arbFfVolts + kVDevice * goalRps
   *   units:    [V]       = [V]       + [V / (output rot/s)] * [output rot/s]
   *   kVDevice is EXACTLY Slot0.kV -- the same number, from the same place, so this introduces no
   *   new conversion and no new place for a unit error.
   * </pre>
   *
   * <p><b>kS is deliberately not added.</b> {@code StaticFeedforwardSign = UseClosedLoopSign}
   * already makes the device apply kS against the closed-loop direction; a second kS term here would
   * double it and make a holding turret chatter.
   *
   * <p><b>What is lost, stated rather than hidden.</b> On {@code PositionVoltage} the velocity term
   * enters the device's error computation. On the three Motion Magic requests it enters only the
   * output sum, so residual velocity error is corrected by the position loop one step later rather
   * than by a velocity loop immediately. For chassis-omega counter-rotation, where omega changes on
   * the timescale of a driver's wrist and the goal is updated every loop anyway, that is not
   * observable. {@link #capabilities()} prints which carrier is in use.
   *
   * @param outputRotations the goal, in output-shaft rotations
   * @param outputRotationsPerSecond the goal velocity to hold at the goal, in output rot/s
   * @param arbFeedforwardVolts an arbitrary feedforward, in volts
   * @param override a per-call constraint override, or null for the configured profile
   */
  @Override
  public void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override) {
    double goalRot = finite(outputRotations, m_measuredRot);
    double goalRps = finite(outputRotationsPerSecond, 0.0);
    double arbFf = finite(arbFeedforwardVolts, 0.0);

    if (m_location == ControlLocation.RIO_FULL) {
      servicePositionOnRio(goalRot, goalRps, arbFf, override);
      return;
    }

    // LATCHING: Phoenix holds the last setpoint, so re-sending an identical goal every loop is pure
    // CAN waste. But NEVER re-sending is how a device reset goes unnoticed, so the latch carries a
    // 10 Hz heartbeat: an unchanged goal is refreshed at least every 100 ms.
    boolean unchanged =
        goalRot == m_lastGoalRot
            && goalRps == m_lastGoalRps
            && arbFf == m_lastArbFf
            && override == null
            && m_lastRequestWasPosition;
    double now = Clock.seconds();
    if (unchanged && now - m_lastSendSeconds < kHeartbeatSeconds) {
      return;
    }
    m_lastGoalRot = goalRot;
    m_lastGoalRps = goalRps;
    m_lastArbFf = arbFf;
    m_lastRequestWasPosition = true;
    m_lastRequestWasVelocity = false;
    m_lastSendSeconds = now;

    // The Motion Magic requests have no velocity field, so the goal-velocity term rides as volts.
    double motionMagicFfVolts = arbFf + m_kvDevice * goalRps;

    if (override != null) {
      if (m_dynamicCapable) {
        // withVelocity here is the CRUISE velocity -- the constraint override, never the goal
        // velocity. There is exactly ONE withVelocity call on this chain, on purpose: an earlier
        // revision chained two and the second silently ate the first, dropping the field-locked
        // turret's counter-rotation term inside the library that exists to fix exactly that bug.
        // The goal velocity is in withFeedForward, above.
        m_leader.setControl(
            m_mmDynamic
                .withPosition(goalRot)
                .withVelocity(m_units.toOutputRps(override.maxVelocity()))
                .withAcceleration(m_units.toOutputRps2(override.maxAcceleration()))
                .withJerk(m_units.toOutputRps3(override.jerk()))
                .withFeedForward(motionMagicFfVolts)
                .withEnableFOC(m_foc));
        return;
      }
      // No Pro licence, or not on a CANivore: DynamicMotionMagicVoltage would be rejected by the
      // device. Push the override into the device's own Motion Magic config on the non-blocking
      // path instead, and say once that the profile is now sticky rather than per-call.
      applyConstraints(override);
      dynamicUnavailableAlert().set(true);
    }

    switch (m_location) {
      // DEVICE_FEEDFORWARD_VOLTS: neither Motion Magic request has a Velocity field.
      case ON_MOTOR_PROFILED ->
          m_leader.setControl(
              m_useExpo
                  ? m_mmExpo
                      .withPosition(goalRot)
                      .withFeedForward(motionMagicFfVolts)
                      .withEnableFOC(m_foc)
                  : m_mmVoltage
                      .withPosition(goalRot)
                      .withFeedForward(motionMagicFfVolts)
                      .withEnableFOC(m_foc));
      // NATIVE_REQUEST_FIELD: PositionVoltage really does have Velocity. arbFf is passed RAW here --
      // adding kV * rps would double-count, because the device applies Slot0.kV to the Velocity
      // reference itself.
      case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP ->
          m_leader.setControl(
              m_posVoltage
                  .withPosition(goalRot)
                  .withVelocity(goalRps)
                  .withFeedForward(arbFf)
                  .withEnableFOC(m_foc));
      case RIO_FULL -> {
        // Unreachable: handled above. Present so the switch is exhaustive without a default that
        // could silently swallow a new location.
      }
    }
  }

  @Override
  public void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts) {
    double goalRps = finite(outputRps, 0.0);
    double goalRps2 = finite(outputRps2, 0.0);
    double arbFf = finite(arbFeedforwardVolts, 0.0);

    if (m_location == ControlLocation.RIO_FULL) {
      serviceVelocityOnRio(goalRps, goalRps2, arbFf);
      return;
    }

    boolean unchanged =
        goalRps == m_lastGoalRps
            && goalRps2 == m_lastGoalRps2
            && arbFf == m_lastArbFf
            && m_lastRequestWasVelocity;
    double now = Clock.seconds();
    if (unchanged && now - m_lastSendSeconds < kHeartbeatSeconds) {
      return;
    }
    m_lastGoalRps = goalRps;
    m_lastGoalRps2 = goalRps2;
    m_lastArbFf = arbFf;
    m_lastRequestWasVelocity = true;
    m_lastRequestWasPosition = false;
    m_lastSendSeconds = now;

    if (m_location == ControlLocation.ON_MOTOR_PROFILED && goalRps2 != 0.0) {
      // MotionMagicVelocityVoltage jerk-limits the approach to a new speed, which is what a shooter
      // that must not brown out the bus wants.
      m_leader.setControl(
          m_mmVelocity
              .withVelocity(goalRps)
              .withAcceleration(goalRps2)
              .withFeedForward(arbFf)
              .withEnableFOC(m_foc));
      return;
    }
    m_leader.setControl(
        m_velVoltage
            .withVelocity(goalRps)
            .withAcceleration(goalRps2)
            .withFeedForward(arbFf)
            .withEnableFOC(m_foc));
  }

  @Override
  public void setVoltage(double volts) {
    m_lastRequestWasPosition = false;
    m_lastRequestWasVelocity = false;
    m_lastGoalRot = Double.NaN;
    m_leader.setControl(m_voltageOut.withOutput(finite(volts, 0.0)).withEnableFOC(m_foc));
  }

  @Override
  public void setDutyCycle(double fraction) {
    m_lastRequestWasPosition = false;
    m_lastRequestWasVelocity = false;
    m_lastGoalRot = Double.NaN;
    m_leader.setControl(
        m_dutyOut.withOutput(MathUtil.clamp(finite(fraction, 0.0), -1.0, 1.0)).withEnableFOC(m_foc));
  }

  @Override
  public void setNeutral() {
    m_lastRequestWasPosition = false;
    m_lastRequestWasVelocity = false;
    m_lastGoalRot = Double.NaN;
    m_leader.setControl(m_neutralOut);
  }

  // ============================================================================ MotorIO config

  @Override
  public void applyGains(Gains siGains) {
    if (siGains == null) {
      return;
    }
    m_gainSink.apply(siGains);
    // Recomputed here and only here: the goal-velocity carrier is Slot0.kV, so it must follow a
    // live re-tune or a turret's counter-rotation term drifts away from the gain it is built on.
    m_kvDevice = Phoenix6GainSink.deviceKv(siGains, m_units.siPerOutputRotation());
    if (m_rioLoop != null) {
      m_rioLoop.applyGains(siGains);
    }
  }

  /**
   * Pushes profile constraints to the device, non-blocking, and does nothing at all when they have
   * not changed.
   *
   * <p>The no-change guard is load-bearing rather than tidy: a mechanism running a constraint
   * profile on a device without Phoenix Pro reaches this method <b>every loop</b> through the
   * dynamic-profile fallback in {@link #setPositionGoal(double, double, double, MotionConstraints)},
   * and an unguarded write would put a Motion Magic config frame on the bus at 50 Hz forever.
   *
   * @param constraints the constraints, in user units per second, second squared and second cubed
   */
  @Override
  public void applyConstraints(MotionConstraints constraints) {
    if (constraints == null || constraints.equals(m_lastConstraints)) {
      return;
    }
    m_lastConstraints = constraints;
    m_motionMagic.MotionMagicCruiseVelocity = m_units.toOutputRps(constraints.maxVelocity());
    m_motionMagic.MotionMagicAcceleration = m_units.toOutputRps2(constraints.maxAcceleration());
    // ALL THREE are converted. jerk arrives in USER units per second cubed and MotionMagicJerk is
    // rot/s^3, so passing it raw is off by 360 on a rotary axis and by 1/(travel per output
    // rotation) on a linear one.
    m_motionMagic.MotionMagicJerk = m_units.toOutputRps3(constraints.jerk());
    m_motionMagicWriter.apply(m_motionMagic);
    if (m_rioLoop != null) {
      m_rioLoop.applyConstraints(constraints);
    }
  }

  @Override
  public void setNeutralMode(NeutralMode mode) {
    if (mode == null) {
      return;
    }
    m_motorOutput.NeutralMode =
        mode == NeutralMode.BRAKE ? NeutralModeValue.Brake : NeutralModeValue.Coast;
    // Fast, not verified: this is reachable from a pit-crew "coast the arm" button, and a blocking
    // write from a button press is still a blocking write.
    m_motorOutputWriter.apply(m_motorOutput);
  }

  @Override
  public void seedPosition(double outputRotations) {
    if (!Double.isFinite(outputRotations)) {
      return;
    }
    // The zero-timeout overload. The default one blocks the main loop for up to 100 ms, and seeding
    // happens immediately before a move starts.
    setDevicePosition(outputRotations);
    if (m_rioLoop != null) {
      m_rioLoop.reset(outputRotations, m_measuredRps);
    }
  }

  @Override
  public void reapplyFullConfigBlocking() {
    PhoenixUtil.applyVerified(m_leaderDevice, leaderConfig(), m_name);
    for (int i = 0; i < m_followerDevices.length; i++) {
      PhoenixUtil.applyVerified(m_followerDevices[i], followerConfig(i), m_name + "/follower" + i);
      m_followers[i].setControl(m_followerRequests[i]);
    }
  }

  @Override
  public MotorCapabilities capabilities() {
    return m_capabilities;
  }

  @Override
  public String name() {
    return m_name;
  }

  // ================================================================================== inspection

  /**
   * The gain sink this backend converts through, so a self test can read back the arithmetic.
   *
   * @return the sink; never null after construction
   */
  public Phoenix6GainSink gainSink() {
    return m_gainSink;
  }

  /**
   * The declared status-signal subscription, so a test can assert that a channel it plots is real.
   *
   * @return the signal set
   */
  public SignalSet<BaseStatusSignal> signals() {
    return m_signals;
  }

  /**
   * Where this backend's loop actually runs, after any downgrade.
   *
   * @return the resolved control location
   */
  public ControlLocation controlLocation() {
    return m_location;
  }

  /**
   * How many post-boot device resets this backend has recovered from.
   *
   * @return the count; zero after a clean boot and one full periodic
   */
  public long deviceResetCount() {
    return m_deviceResetCount;
  }

  // ============================================================================== subclass hooks
  //
  // NOTE for anyone adding a hook: supportsFoc() and supportsTorqueCurrent() are called from THIS
  // class's constructor, before the subclass constructor body has run. They must therefore answer
  // from the device TYPE and must not read a subclass field, which would still be null or zero.
  // Every other hook is called only after construction has finished.

  /** @return the whole-device configuration object, for the verified re-apply paths */
  protected abstract Object leaderConfig();

  /**
   * @param index which follower
   * @return that follower's configuration object
   */
  protected abstract Object followerConfig(int index);

  /**
   * @param outputRotations where the device should believe it is, in output rotations
   * @return the status of the non-blocking seed
   */
  protected abstract StatusCode setDevicePosition(double outputRotations);

  /** @return whether this device type can commutate with field-oriented control */
  protected abstract boolean supportsFoc();

  /**
   * Whether the device can close a loop in torque-current units.
   *
   * <p>This is a hardware fact and is <b>not</b> the same question as FOC. Rootstock v0.1 only ever
   * uses voltage output, because gains are volts-per-SI and a torque-current slot wants amps-per-SI
   * — a different number for every one of the seven, which the tuning wizard, the feedback designer
   * and the persisted gains file cannot yet convert between.
   *
   * @return true when the device supports torque-current control requests
   */
  protected abstract boolean supportsTorqueCurrent();

  /** @return the human name of the device type, for {@code describe()} */
  protected abstract String deviceTypeName();

  // ================================================================================ description

  /**
   * The common half of {@code describe()}: control location, output mode, FOC, profile, the
   * goal-velocity carrier with its arithmetic, the signal budget, and the gain conversion.
   *
   * @return a multi-line block, no trailing newline
   */
  protected final String describeCommon() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(1600);
    sb.append(m_name).append(" motor backend").append(nl);
    row(sb, "leader", deviceTypeName() + " " + m_leaderDevice.getDeviceID()
        + " on bus \"" + m_bus + "\"   (" + m_spec.model().displayName()
        + ", Phoenix Pro: " + (m_proLicensed ? "yes" : "no") + ")");
    if (m_followers.length == 0) {
      row(sb, "followers", "none");
    } else {
      StringBuilder f = new StringBuilder();
      for (int i = 0; i < m_followers.length; i++) {
        if (i > 0) {
          f.append(", ");
        }
        f.append(deviceTypeName())
            .append(' ')
            .append(m_followerDevices[i].getDeviceID())
            .append(m_followerRequests[i].MotorAlignment == MotorAlignmentValue.Opposed
                ? " OPPOSED" : " ALIGNED");
      }
      row(sb, "followers", f.toString());
    }
    row(sb, "ControlLocation", m_location
        + (m_location == m_requestedLocation
            ? " (" + m_control.locationSource() + ")"
            : " -- DOWNGRADED from " + m_requestedLocation + " by this device's capabilities"));
    cont(sb, m_location.explanation());
    row(sb, "output mode", "VOLTAGE  (gains are volts-per-SI: kP V/" + m_units.siLabel()
        + ", kV V/(" + m_units.siLabel() + "/s), kG V)");
    row(sb, "FOC", !supportsFoc()
        ? "NOT SUPPORTED by this device type"
        : (m_foc
            ? "ENABLED  (per-request .withEnableFOC(true))"
            : "disabled (per-request .withEnableFOC(false))"));
    cont(sb, "FOC is commutation only. It is NOT torque-current control and it does");
    cont(sb, "not change gain units. Torque-current output: "
        + (supportsTorqueCurrent() ? "supported by the device, NOT used in v0.1"
            : "not supported by this device"));
    row(sb, "profile", m_useExpo
        ? "Motion Magic EXPO (shaped entirely by MotionMagicExpo_kV/_kA, which are"
        : "Motion Magic trapezoid (useExpo = false)");
    if (m_useExpo) {
      cont(sb, String.format(Locale.ROOT,
          "DERIVED from your kV/kA: %.5f V/(rot/s), %.5f V/(rot/s^2) -- not",
          m_motionMagic.MotionMagicExpo_kV, m_motionMagic.MotionMagicExpo_kA));
      cont(sb, "CTRE's factory 0.12 / 0.1. Under Expo the acceleration limit is");
      cont(sb, "unused and the cruise velocity is only a ceiling.)");
    }
    row(sb, "dynamic profile", m_dynamicCapable
        ? "available (Phoenix Pro + CAN FD bus): a per-call constraint override "
            + "uses DynamicMotionMagicVoltage"
        : "UNAVAILABLE (DynamicMotionMagicVoltage needs Phoenix Pro + a CANivore; "
            + "this device has Pro: " + m_proLicensed + ", CAN FD: "
            + PhoenixUtil.isCanFd(m_leaderDevice) + ").");
    if (!m_dynamicCapable) {
      cont(sb, "A per-call override is instead pushed into the device's own Motion");
      cont(sb, "Magic config on the non-blocking path, and is therefore STICKY until");
      cont(sb, "the next applyConstraints(). ControlLocation.RIO_PROFILE_MOTOR_LOOP");
      cont(sb, "is the alternative if you need a genuinely per-call profile.");
    }
    row(sb, "goal-velocity path", m_capabilities.positionGoalVelocity().name() + " -- "
        + m_capabilities.positionGoalVelocity().explanation());
    if (m_capabilities.positionGoalVelocity() == VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS) {
      cont(sb, "MotionMagicVoltage has no Velocity field (verified against Phoenix 6");
      cont(sb, "26.3.0), so a nonzero goal velocity rides as kV_device x rps volts on");
      cont(sb, String.format(Locale.ROOT,
          "withFeedForward: kV_device = %.4f V/(SI/s) x %.5f %s/rot = %.4f V/(rot/s).",
          m_control.gains().kV(), m_units.siPerOutputRotation(), m_units.siLabel(), m_kvDevice));
      cont(sb, "kS is deliberately NOT added -- StaticFeedforwardSign already applies");
      cont(sb, "it against the closed-loop direction, and doubling it makes a holding");
      cont(sb, "mechanism chatter. ON_MOTOR_DIRECT would give NATIVE_REQUEST_FIELD.");
    }
    sb.append(describeSignalBudget());
    row(sb, "gains -> device", "");
    sb.append(m_gainSink.describeConversion()).append(nl);
    row(sb, "capabilities", m_capabilities.describe());
    return sb.toString().stripTrailing();
  }

  /**
   * What is subscribed, at what rate, what that costs on the bus, and — just as importantly — which
   * {@link MotorInputs} fields are therefore NaN rather than zero.
   *
   * @return a multi-line block ending in a line separator
   */
  protected final String describeSignalBudget() {
    StringBuilder sb = new StringBuilder(512);
    List<String> subscribed = new ArrayList<>();
    double framesPerSecond = 0.0;
    for (SignalSet.Channel channel : SignalSet.Channel.values()) {
      if (m_signals.has(channel)) {
        double hz = rateFor(channel);
        subscribed.add(channel + "@" + (long) hz + "Hz");
        framesPerSecond += hz;
      }
    }
    framesPerSecond += m_followers.length * (2 * kFollowerRateHz + kFollowerTemperatureRateHz);
    double bitsPerSecond = framesPerSecond * kBitsPerFrame;
    row(sb, "signals subscribed", String.join(" ", subscribed));
    cont(sb, String.format(Locale.ROOT,
        "%.0f frames/s UPPER BOUND x %.0f bits/frame = %.1f kbit/s",
        framesPerSecond, kBitsPerFrame, bitsPerSecond / 1000.0));
    cont(sb, String.format(Locale.ROOT,
        "= %.1f%% of a 1 Mbps bus. It is an UPPER bound because Phoenix",
        100.0 * bitsPerSecond / kBusBitsPerSecond));
    cont(sb, "co-locates several signals in one status frame and which signals share");
    cont(sb, "which frame is UNVERIFIED, so one-frame-per-signal is the ceiling.");
    List<String> absent = new ArrayList<>();
    for (SignalSet.Channel channel : SignalSet.Channel.values()) {
      if (!m_signals.has(channel)) {
        absent.add(channel.name());
      }
    }
    row(sb, "signals NOT read", absent.isEmpty() ? "none" : String.join(", ", absent));
    if (!absent.isEmpty()) {
      cont(sb, "-> those MotorInputs fields are NaN, not 0.0");
    }
    return sb.toString();
  }

  // ===================================================================================== private

  /**
   * The RIO_FULL position path.
   *
   * <p><b>Deviation from the frozen design, stated so it is a decision and not an accident.</b> The
   * design specified a latched no-op plus {@code setNeutral()} here, on the grounds that a TalonFX
   * reaching this branch is a Rootstock routing bug. Going neutral on a gravity-loaded mechanism
   * is, however, the exact failure mode ("the elevator drops") this backend spends a hundred lines
   * preventing elsewhere. So the fault is still latched and still names itself — but the goal is
   * then serviced through the shared {@link RioControlLoop}, which is the one roboRIO-side
   * implementation in the library. The mechanism holds; the alert says why it should not have had
   * to.
   */
  private void servicePositionOnRio(
      double goalRot, double goalRps, double arbFf, MotionConstraints override) {
    if (!m_routingFaultReported) {
      m_routingFaultReported = true;
      routingFaultAlert().set(true);
    }
    if (m_rioLoop == null) {
      setNeutral();
      return;
    }
    if (override != null) {
      m_rioLoop.applyConstraints(override);
    }
    double volts =
        m_rioLoop.positionVolts(
            m_measuredRot, m_measuredRps, goalRot, goalRps, arbFf, Clock.dt());
    m_leader.setControl(m_voltageOut.withOutput(volts).withEnableFOC(m_foc));
  }

  private void serviceVelocityOnRio(double goalRps, double goalRps2, double arbFf) {
    if (!m_routingFaultReported) {
      m_routingFaultReported = true;
      routingFaultAlert().set(true);
    }
    if (m_rioLoop == null) {
      setNeutral();
      return;
    }
    double volts = m_rioLoop.velocityVolts(m_measuredRps, goalRps, goalRps2, arbFf, Clock.dt());
    m_leader.setControl(m_voltageOut.withOutput(volts).withEnableFOC(m_foc));
  }

  private VelocityCarrier carrierFor(ControlLocation location) {
    ControlLocation where = location == null ? ControlLocation.ON_MOTOR_PROFILED : location;
    return switch (where) {
      case ON_MOTOR_PROFILED -> VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS;
      case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP -> VelocityCarrier.NATIVE_REQUEST_FIELD;
      case RIO_FULL -> VelocityCarrier.RIO_VOLTAGE_TRIM;
    };
  }

  private boolean usesMotorLimit(HardStop side) {
    return m_limits != null && m_limits.usesMotorLimit(side);
  }

  private double rateFor(SignalSet.Channel channel) {
    double positionRate =
        Double.isNaN(m_signalRateHz)
            ? (m_kind == MechanismKind.POSITION ? kPositionRateHz : kVelocityMechanismRateHz)
            : m_signalRateHz;
    return switch (channel) {
      case POSITION, VELOCITY -> positionRate;
      case APPLIED_VOLTS -> kAppliedVoltsRateHz;
      case STATOR -> kStatorRateHz;
      case SUPPLY -> kSupplyRateHz;
      case TEMPERATURE -> kTemperatureRateHz;
      case TORQUE_CURRENT, CLOSED_LOOP_REFERENCE, FORWARD_LIMIT, REVERSE_LIMIT -> kDiagnosticRateHz;
    };
  }

  private RootstockAlert resetAlert() {
    if (m_resetAlert == null) {
      m_resetAlert =
          Alerts.warning(
              m_name,
              m_name
                  + ": device reset detected on "
                  + PhoenixUtil.describeDevice(m_leaderDevice)
                  + ". The configuration and the setpoint were re-sent. If this repeats, check the"
                  + " power and CAN wiring to that motor -- a mid-match reset drops the mechanism"
                  + " until we notice.",
              MatchImpact.BLOCKS_MATCH);
    }
    return m_resetAlert;
  }

  private RootstockAlert routingFaultAlert() {
    if (m_routingFaultAlert == null) {
      m_routingFaultAlert =
          Alerts.error(
              m_name,
              m_name
                  + ": internal routing error -- ControlLocation.RIO_FULL reached the on-motor goal"
                  + " path. This is a Rootstock bug, not your config. The mechanism is being held"
                  + " by the shared roboRIO control loop instead, so it will not drop, but the goal"
                  + " should have arrived through setVoltage(). Please file it with"
                  + " `rootstock doctor --bundle`.",
              MatchImpact.BLOCKS_MATCH);
    }
    return m_routingFaultAlert;
  }

  private RootstockAlert dynamicUnavailableAlert() {
    if (m_dynamicUnavailableAlert == null) {
      m_dynamicUnavailableAlert =
          Alerts.warning(
              m_name,
              m_name
                  + ": a per-call MotionConstraints override was requested, but"
                  + " DynamicMotionMagicVoltage needs Phoenix Pro AND a CANivore and this device has"
                  + " Pro: "
                  + m_proLicensed
                  + ", CAN FD: "
                  + PhoenixUtil.isCanFd(m_leaderDevice)
                  + ". The override was written into the device's own Motion Magic config instead,"
                  + " so it is STICKY until the next applyConstraints() rather than per call."
                  + " Fix: move the device to a CANivore, license Phoenix Pro, or use"
                  + " ControlLocation.RIO_PROFILE_MOTOR_LOOP where the roboRIO owns the profile.",
              MatchImpact.PIT_ONLY);
    }
    return m_dynamicUnavailableAlert;
  }

  private static double finite(double value, double fallback) {
    return Double.isFinite(value) ? value : fallback;
  }

  private static void row(StringBuilder sb, String label, String value) {
    sb.append("  ")
        .append(String.format(Locale.ROOT, "%-20s", label))
        .append(value)
        .append(System.lineSeparator());
  }

  private static void cont(StringBuilder sb, String value) {
    sb.append("  ").append(" ".repeat(20)).append(value).append(System.lineSeparator());
  }

  /** @return the leader, for a subclass that needs to send a device-specific request */
  protected final CommonTalon leader() {
    return m_leader;
  }

  /** @return the leader as a configurable device */
  protected final ParentDevice leaderDevice() {
    return m_leaderDevice;
  }

  /** @return the telemetry-visible motor name */
  protected final String motorName() {
    return m_name;
  }
}

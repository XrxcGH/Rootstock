package org.rootstock.hardware.rev;

import com.revrobotics.RelativeEncoder;
import com.revrobotics.spark.ClosedLoopSlot;
import com.revrobotics.spark.SparkBase;
import com.revrobotics.spark.SparkClosedLoopController;
import com.revrobotics.spark.SparkClosedLoopController.ArbFFUnits;
import com.revrobotics.spark.SparkSim;
import com.revrobotics.spark.config.SparkBaseConfig;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.system.plant.DCMotor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.CurrentLimits;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorSpec;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.Gains;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.hardware.MotorCapabilities;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorInputs;
import org.rootstock.hardware.RioControlLoop;
import org.rootstock.hardware.VelocityCarrier;
import org.rootstock.units.MechanismUnits;

/**
 * The REVLib backend: SPARK MAX and SPARK Flex, one class.
 *
 * <p>Everything above this class works in <b>output-shaft rotations</b> and <b>output rotations per
 * second</b>. This class is where those become REVLib's numbers, and every conversion it performs
 * is derived in {@link #buildConfig} rather than asserted, because the two most expensive bugs
 * found in the surveyed REV code were both unit errors that looked completely reasonable on the
 * line they were written.
 *
 * <h2>Configuration is declarative, not incremental</h2>
 *
 * <p>REVLib 2026 configures through a config object, applied whole. This backend builds one {@code
 * SparkMaxConfig} / {@code SparkFlexConfig} in the constructor and applies it with {@code
 * kResetSafeParameters}, which means "this config, and factory defaults for everything else". A
 * SPARK swapped in the pit therefore behaves identically to the one that came out, with no memory
 * of whatever was last poked into it through the REV Hardware Client. The cost is that every write
 * after construction has to be a deliberate partial config through {@link RevUtil#applyFast} — see
 * that class for when this library persists to flash and when it refuses to.
 *
 * <h2>What this class does NOT configure, and why</h2>
 *
 * <p>The {@code MotorIOFactory.Backend} seam hands a backend a {@link MotorSpec}, a {@link
 * MechanismUnits}, a {@link ControlConfig} and a {@link MechanismKind} — not the owning {@code
 * PositionConfig}. So soft limits and the mechanism's real current limits are not visible here.
 * Current limiting still happens, from {@link CurrentLimits#defaultsFor} for the declared motor,
 * because a SPARK with no current limit at all is a fire risk and a NEO 550 with a NEO's limit is a
 * dead NEO 550. Soft limits are applied only when a caller supplies them through {@link
 * #applySoftLimits}; otherwise {@code PositionMechanism} enforces them on the roboRIO and {@link
 * #describe()} says so out loud rather than letting a team assume the device is protecting them.
 *
 * <h2>Reset recovery</h2>
 *
 * <p>Phoenix has {@code hasResetOccurred()}, a self-clearing per-call flag. REVLib's analogue is
 * <b>verified to exist but is a different shape</b>: {@code getStickyWarnings().hasReset}, a sticky
 * bit cleared only by {@code clearFaults()}, which clears every other sticky bit with it. This
 * backend implements the same behaviour — count the reset, clear, re-apply the whole config
 * blocking, invalidate the setpoint latch so the next goal is re-sent, raise a sticky alert — and
 * accepts the one honest difference: clearing the reset bit also clears any sticky brownout or
 * overcurrent record the pit crew had not read yet, so those are captured into the alert text
 * first. <b>[UNVERIFIED-BY-EXECUTION]</b> whether {@code hasReset} is latched by a power-on as well
 * as by a brownout; the constructor consumes it once either way, exactly as the Phoenix backend
 * consumes {@code hasResetOccurred()}, so a clean boot never counts as a reset.
 *
 * <h2>Simulation, honestly</h2>
 *
 * <p>REVLib's simulation is weaker than Phoenix's and this class does not pretend otherwise. {@code
 * SparkSim.iterate(velocity, vbus, dt)} takes an <em>externally computed</em> velocity in converted
 * units, runs the device's internal closed loop against it, and integrates position itself, adding
 * its own filtering lag and noise. There is no equivalent of writing a raw rotor position, so
 * {@link SimMotorHandle#setRotorPosition} converts back through the same reduction the device is
 * configured with and calls {@code setPosition}/{@code setVelocity}. What that buys is real: the
 * <em>device's own</em> conversion factors, MAXMotion profile and gravity feedforward are under
 * test in {@code simulateJava}, which is the whole point of simulating through the vendor rather
 * than around it. What it does not buy is bit-exact device timing.
 */
public final class SparkMotorIO implements MotorIO {

  /**
   * How long a latched setpoint may go un-refreshed before it is re-sent, in seconds.
   *
   * <p>The SPARK holds the last setpoint it was given, so re-sending it every loop is pure bus
   * traffic — but never re-sending it means a controller that missed the one frame holds the wrong
   * goal forever. Ten hertz is the compromise: 20× less traffic than every loop, and a worst case
   * of 100 ms of stale goal after a dropped frame.
   */
  public static final double kHeartbeatSeconds = 0.100;

  /** Nominal bus voltage, used to turn an applied duty cycle into volts in simulation. */
  public static final double kNominalBusVolts = 12.0;

  /**
   * The time the stand-in simulation plant takes to reach free speed, in seconds.
   *
   * <p>Only used when nothing has taken {@link #simHandle()}. See {@link #stepDefaultSimPlant} for
   * what this number is and is not. Deliberately the same value {@code SimMotorIO} documents, so
   * the two backends feel alike to a student switching between them.
   */
  public static final double kDefaultSpinUpSeconds = 0.35;

  /**
   * The largest MAXMotion constraint this backend will send.
   *
   * <p>{@link MotionConstraints#unconstrained()} is infinite, and REVLib stores profile parameters
   * as 32-bit floats — an infinity there is a device that does something undefined rather than
   * something fast. This is "effectively unbounded" in output rotations per second: 1e6 rot/s is
   * 60 million RPM, so nothing physical is being clipped.
   */
  public static final double kEffectivelyUnbounded = RioControlLoop.kEffectivelyUnbounded;

  /** What the IO was last told to do. The SPARK latches it; so do we, so we can re-send it. */
  private enum Mode {
    NEUTRAL,
    VOLTAGE,
    DUTY_CYCLE,
    POSITION,
    VELOCITY
  }

  private final String m_name;
  private final MotorSpec.SparkSpec m_spec;
  private final MechanismUnits m_units;
  private final ControlConfig m_control;
  private final MechanismKind m_kind;
  private final CurrentLimits m_limits;

  private final SparkBase m_leader;
  private final SparkBase[] m_followers;
  private final SparkBaseConfig[] m_followerConfigs;
  private final SparkBaseConfig m_config;
  private final RelativeEncoder m_encoder;
  private final SparkClosedLoopController m_closedLoop;

  private final MotorCapabilities m_capabilities;
  private final ControlLocation m_location;
  private final RevGainSink m_gains;
  private final RioControlLoop m_rioLoop;
  private final SparkSim m_sim;
  private final RootstockAlert m_resetAlert;

  private final double m_rotorPerOutput;
  private double m_softMinRot = Double.NaN;
  private double m_softMaxRot = Double.NaN;

  private Mode m_mode = Mode.NEUTRAL;
  private Gains m_currentGains;
  private double m_kvDeviceVoltsPerRps;
  private double m_lastGoalRot = Double.NaN;
  private double m_lastGoalRps = Double.NaN;
  private double m_lastArbFf = Double.NaN;
  private double m_lastSendSeconds = Double.NEGATIVE_INFINITY;
  private long m_deviceResetCount;
  private double m_lastMeasuredRot;
  private double m_lastMeasuredRps;
  private boolean m_simExternallyDriven;
  private double m_defaultPlantRps;

  // ---- construction ----------------------------------------------------------------------------

  /**
   * The form the backend factory calls.
   *
   * @param spec the declared SPARK
   * @param units the mechanism's unit conversion object — the reduction the device is configured
   *     with
   * @param control the declared location, gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public SparkMotorIO(
      MotorSpec.SparkSpec spec, MechanismUnits units, ControlConfig control, MechanismKind kind) {
    this(
        MotorGroup.leader(spec),
        units,
        control,
        kind,
        CurrentLimits.defaultsFor(spec.sparkModel().motor()));
  }

  /**
   * The full form: a leader, its followers, and the mechanism's real current limits.
   *
   * <p>Followers are configured through REVLib's {@code follow(...)} so the SPARK does the
   * following on the bus rather than the roboRIO echoing setpoints, and each carries its own
   * inversion sense.
   *
   * @param group the leader and its followers; the leader must be a {@link MotorSpec.SparkSpec}
   * @param units the mechanism's unit conversion object
   * @param control the declared location, gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param limits the current limits to configure; null falls back to the motor's defaults
   * @throws IllegalArgumentException if the leader is not a SPARK — a constructor is one of the
   *     three places design §5.6 allows a throw, and a REV backend handed a Kraken is a
   *     programming error rather than a field condition
   */
  public SparkMotorIO(
      MotorGroup group,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      CurrentLimits limits) {
    Objects.requireNonNull(group, "SparkMotorIO: motor group must not be null");
    m_units = Objects.requireNonNull(units, "SparkMotorIO: units must not be null");
    m_control = Objects.requireNonNull(control, "SparkMotorIO: control config must not be null");
    m_kind = kind == null ? MechanismKind.SIMPLE : kind;
    if (!(group.leader() instanceof MotorSpec.SparkSpec leaderSpec)) {
      throw new IllegalArgumentException(
          "SparkMotorIO was given a "
              + group.leader().deviceType()
              + " as its leader. This backend only drives SPARK MAX and SPARK Flex controllers;"
              + " use MotorSpec.spark(...) or let MotorIOFactory choose the backend.");
    }
    m_spec = leaderSpec;
    m_name = leaderSpec.name();
    m_limits = limits == null ? CurrentLimits.defaultsFor(leaderSpec.sparkModel().motor()) : limits;
    m_rotorPerOutput = units.rotorPerOutput();

    m_leader = RevUtil.newSpark(leaderSpec);
    m_encoder = m_leader.getEncoder();
    m_closedLoop = m_leader.getClosedLoopController();

    m_capabilities = buildCapabilities();
    m_location = m_capabilities.downgrade(control.location());
    warnIfDowngraded(control.location());

    m_gains =
        new RevGainSink(
            m_leader,
            leaderSpec,
            units.siPerOutputRotation(),
            control.gravity(),
            units.horizontalReferenceSi(),
            m_name);
    m_currentGains = control.gains();
    m_kvDeviceVoltsPerRps = m_gains.deviceKvVoltsPerOutputRps(m_currentGains);
    m_rioLoop = new RioControlLoop(units, control, Clock.dt());

    m_config = buildConfig();
    RevUtil.applyVerified(m_leader, m_config, m_name);

    List<SparkBase> followers = new ArrayList<>();
    List<SparkBaseConfig> followerConfigs = new ArrayList<>();
    for (MotorGroup.FollowerSpec follower : group.followers()) {
      if (!(follower.spec() instanceof MotorSpec.SparkSpec followerSpec)) {
        Alerts.error(
                m_name,
                m_name
                    + ": follower "
                    + follower.spec().name()
                    + " is a "
                    + follower.spec().deviceType()
                    + ", which cannot follow a SPARK. Mixed-vendor follower groups are not a"
                    + " thing on the CAN bus. Fix: make every motor in this mechanism the same"
                    + " vendor.",
                MatchImpact.BLOCKS_MATCH)
            .set(true);
        continue;
      }
      SparkBase device = RevUtil.newSpark(followerSpec);
      SparkBaseConfig config = RevUtil.newConfig(followerSpec);
      config
          .idleMode(idleMode())
          .smartCurrentLimit(m_limits.smartCurrentLimitAmps())
          .follow(m_leader, follower.sense().opposesLeader());
      RevUtil.applyVerified(device, config, m_name + "/" + followerSpec.name());
      followers.add(device);
      followerConfigs.add(config);
    }
    m_followers = followers.toArray(new SparkBase[0]);
    m_followerConfigs = followerConfigs.toArray(new SparkBaseConfig[0]);

    m_sim = Platform.isSimulation() ? new SparkSim(m_leader, gearbox()) : null;
    if (m_sim != null) {
      m_sim.useDriverStationEnable();
    }

    m_resetAlert =
        Alerts.warning(
            m_name,
            m_name + ": SPARK controller reset detected. Config and setpoint were re-sent.",
            MatchImpact.BLOCKS_MATCH);

    // LAST STATEMENT of the constructor: consume the boot reset flag, exactly as the Phoenix
    // backend consumes hasResetOccurred(). A device that has just powered on has, trivially, reset
    // since the sticky bits were last cleared -- there was no "last cleared". Counting that as a
    // reset would fire the mid-match alert on every single boot, which trains a team to ignore the
    // one alert that actually matters.
    m_leader.clearFaults();
    for (SparkBase follower : m_followers) {
      follower.clearFaults();
    }
  }

  // ---- the unit chain --------------------------------------------------------------------------

  /**
   * Build the whole declarative configuration — and derive every unit rather than asserting it.
   *
   * <h2>Gearing</h2>
   *
   * <p>REVLib's conversion factors are its {@code SensorToMechanismRatio}. With {@code G =
   * rotorPerOutput}:
   *
   * <pre>
   *   positionConversionFactor  f_p = 1 / G
   *       rotor rotations * f_p = OUTPUT rotations                        (what we want)
   *
   *   velocityConversionFactor  f_v = 1 / (G * 60)
   *       rotor RPM * f_v = (rotor rot / min) / (G * 60)
   *                       = (rotor rot / s) / G
   *                       = OUTPUT rot / s                                (what we want)
   * </pre>
   *
   * <h2>MAXMotion — the 60× bug, and why it is not here</h2>
   *
   * <p>REVLib's own javadoc, quoted verbatim from the 2026.0.5 sources:
   *
   * <ul>
   *   <li>{@code cruiseVelocity}: <i>"Natively, the units are in RPM but will be affected by the
   *       velocity conversion factor."</i>
   *   <li>{@code maxAcceleration}: <i>"Natively, the units are in RPM per second but will be
   *       affected by the velocity conversion factor."</i>
   *   <li>{@code allowedProfileError}: <i>"Natively, the units are in rotations but will be
   *       affected by the position conversion factor."</i>
   * </ul>
   *
   * <p>"Affected by the velocity conversion factor" means REVLib applies {@code f_v} to the
   * constraint in the <b>same direction and with the same factor</b> as it applies it to the
   * measurement. So the constraint must be expressed in the same unit the measurement ends up in:
   *
   * <pre>
   *   cruiseVelocity      -&gt; OUTPUT rot/s      = constraints.maxVelocityRps(units)
   *   maxAcceleration     -&gt; OUTPUT rot/s^2    = constraints.maxAccelerationRps2(units)
   *   allowedProfileError -&gt; OUTPUT rotations  = units.toOutputRotations(tolerance)
   * </pre>
   *
   * <p>Multiplying the cruise velocity by 60 "to convert to RPM" — on top of a conversion factor
   * that has already left RPM behind — asks for <b>sixty times</b> the intended cruise velocity.
   * The profile then never cruises: the mechanism accelerates until the acceleration limit or the
   * current limit stops it, which on a gravity-loaded elevator carriage means it arrives at the top
   * hard stop at full speed. That defect shipped in a flagship REV path and is the reason this
   * derivation is written out in the source instead of living in someone's head.
   *
   * <p><b>[UNVERIFIED-BY-EXECUTION]</b>: the documentation above is verified against REVLib
   * 2026.0.5's shipped sources; the <em>behaviour</em> is pinned by the {@code MaxMotionUnitsTest}
   * described in design §3.7, which drives {@code SparkSim.iterate} and asserts the observed
   * steady-state profile velocity matches the configured cruise velocity within 5%.
   *
   * @return the configuration to apply; never null
   */
  private SparkBaseConfig buildConfig() {
    SparkBaseConfig config = RevUtil.newConfig(m_spec);

    config.encoder
        .positionConversionFactor(1.0 / m_rotorPerOutput)
        .velocityConversionFactor(1.0 / (m_rotorPerOutput * 60.0));

    config
        .inverted(m_spec.inverted())
        .idleMode(idleMode())
        .smartCurrentLimit(m_limits.smartCurrentLimitAmps());

    // Gains: the ONE place they are converted lives in RevGainSink, and this is a call to it, not
    // a copy of it. Untuned gains write nothing, so the device keeps its factory zeros and the
    // closed loop simply does not move -- which is what a NaN kP is for.
    m_gains.writeInto(config, m_control.gains());

    MotionConstraints constraints = m_control.constraints();
    config.closedLoop.maxMotion
        .cruiseVelocity(bounded(constraints.maxVelocityRps(m_units)), RevGainSink.kSlot)
        .maxAcceleration(bounded(constraints.maxAccelerationRps2(m_units)), RevGainSink.kSlot)
        .allowedProfileError(toleranceOutputRotations(), RevGainSink.kSlot);

    if (m_units.axis().isContinuous()) {
      // A continuous axis wraps at one OUTPUT rotation, because that is the unit the position
      // conversion factor above leaves the device measuring in.
      config.closedLoop.positionWrappingEnabled(true).positionWrappingInputRange(0.0, 1.0);
    }

    int periodMs = periodMillis();
    config.signals
        .primaryEncoderPositionPeriodMs(periodMs)
        .primaryEncoderVelocityPeriodMs(periodMs)
        .appliedOutputPeriodMs(periodMs)
        .outputCurrentPeriodMs(periodMs)
        .busVoltagePeriodMs(100)
        .motorTemperaturePeriodMs(250)
        .faultsPeriodMs(100)
        .warningsPeriodMs(100);

    return config;
  }

  // ---- MotorIO: reads --------------------------------------------------------------------------

  /**
   * Read every channel and fill {@code inputs}, then handle a device reset if one happened.
   *
   * <p>REVLib has no batched-signal transaction — there is no analogue of {@code
   * BaseStatusSignal.refreshAll}, so {@code SignalSet} has nothing to wrap here. What REVLib has
   * instead is periodic status frames pushed by the device at rates configured in {@link
   * #buildConfig}; each getter below reads the most recent frame out of the vendordep's cache and
   * costs no bus traffic at call time. The consequence for a caller is that a REV mechanism's
   * inputs are not a single coherent snapshot the way a Phoenix mechanism's are — position and
   * current can be up to one frame period apart.
   *
   * @param inputs the inputs object to fill; the same instance every loop
   */
  @Override
  public void updateInputs(MotorInputs inputs) {
    if (m_sim != null && !m_simExternallyDriven) {
      // The device's own closed loop and profile are stepped here so that a mechanism that only
      // ever calls updateInputs still moves in simulation -- and moves through the REAL config.
      // Skipped once a real plant has taken simHandle(): that plant calls iterate() itself, and
      // stepping the device twice per loop would integrate the mechanism at double speed, which is
      // exactly the class of silent 2x error this library exists to delete.
      stepDefaultSimPlant();
    }
    m_lastMeasuredRot = m_encoder.getPosition();
    m_lastMeasuredRps = m_encoder.getVelocity();
    if (inputs == null) {
      return;
    }

    inputs.connected = RevUtil.connected(m_leader);
    inputs.positionRot = m_lastMeasuredRot;
    inputs.velocityRps = m_lastMeasuredRps;
    inputs.appliedVolts = m_leader.getAppliedOutput() * m_leader.getBusVoltage();
    // getOutputCurrent() is the SPARK's measured phase current, which is a stator current.
    inputs.statorCurrentAmps = m_leader.getOutputCurrent();
    // REVLib publishes no supply-side current and no torque current. NaN, not a fabricated
    // estimate: a brownout model fed an invented supply current is worse than one that knows it
    // has no number.
    inputs.supplyCurrentAmps = Double.NaN;
    inputs.torqueCurrentAmps = Double.NaN;
    inputs.temperatureCelsius = m_leader.getMotorTemperature();
    // The hardware limit switches are not configured by this backend (the seam carries no
    // SensorSpec), so their state would be meaningless. "Not valid" is the honest report.
    inputs.forwardLimitTripped = false;
    inputs.forwardLimitValid = false;
    inputs.reverseLimitTripped = false;
    inputs.reverseLimitValid = false;
    inputs.closedLoopReferenceRot = referenceRot();
    inputs.deviceResetCount = m_deviceResetCount;

    readFollowers(inputs);
    handleDeviceReset(inputs);
  }

  // ---- MotorIO: writes -------------------------------------------------------------------------

  /**
   * Command a position goal, carrying the goal-velocity term the REV API has no field for.
   *
   * <p>{@code SparkClosedLoopController} has no analogue of Phoenix's {@code
   * PositionVoltage.withVelocity()}. What it does have — verified in the 2026.0.5 sources — is
   * {@code setSetpoint(setpoint, ControlType, ClosedLoopSlot, arbFeedforward, ArbFFUnits)}, whose
   * arbitrary feedforward is documented as <i>"voltage applied to the motor after the result of the
   * specified control mode. The units for the parameter is Volts."</i> So the goal-velocity term
   * rides there, as {@code kV_device * goalRps} volts — the identical formula, in identical units,
   * to the one the Phoenix Motion Magic path uses. It is reported as {@link
   * VelocityCarrier#DEVICE_FEEDFORWARD_VOLTS} and it is <b>never</b> silently dropped, which is
   * exactly what a surveyed {@code TurretIONeo} does today: a field-locked turret that must
   * counter-rotate with the chassis loses its entire feedforward term and lags every time the robot
   * spins.
   *
   * <p>The setpoint <b>latches</b> on the device, so it is re-sent only when it changes or when the
   * {@link #kHeartbeatSeconds} heartbeat expires — except under {@link ControlLocation#RIO_FULL},
   * where there is no device loop to latch anything and the voltage must be recomputed every call.
   *
   * @param outputRotations the goal position, in output-shaft rotations
   * @param outputRotationsPerSecond the goal velocity at that position, in output rot/s
   * @param arbFeedforwardVolts an additional feedforward, in volts
   */
  @Override
  public void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts) {
    if (m_location == ControlLocation.RIO_FULL) {
      if (m_mode != Mode.POSITION) {
        m_rioLoop.reset(m_lastMeasuredRot, m_lastMeasuredRps);
      }
      m_mode = Mode.POSITION;
      m_leader.setVoltage(
          m_rioLoop.positionVolts(
              m_lastMeasuredRot,
              m_lastMeasuredRps,
              outputRotations,
              outputRotationsPerSecond,
              arbFeedforwardVolts,
              Clock.dt()));
      return;
    }

    double now = Clock.seconds();
    boolean unchanged =
        m_mode == Mode.POSITION
            && outputRotations == m_lastGoalRot
            && outputRotationsPerSecond == m_lastGoalRps
            && arbFeedforwardVolts == m_lastArbFf;
    if (unchanged && now - m_lastSendSeconds < kHeartbeatSeconds) {
      return;
    }
    m_mode = Mode.POSITION;
    m_lastGoalRot = outputRotations;
    m_lastGoalRps = outputRotationsPerSecond;
    m_lastArbFf = arbFeedforwardVolts;
    m_lastSendSeconds = now;

    SparkBase.ControlType type =
        m_location == ControlLocation.ON_MOTOR_PROFILED
            ? SparkBase.ControlType.kMAXMotionPositionControl
            : SparkBase.ControlType.kPosition;
    double velocityVolts =
        Double.isFinite(outputRotationsPerSecond)
            ? m_kvDeviceVoltsPerRps * outputRotationsPerSecond
            : 0.0;
    m_closedLoop.setSetpoint(
        outputRotations,
        type,
        ClosedLoopSlot.kSlot0,
        finiteOrZero(arbFeedforwardVolts) + velocityVolts,
        ArbFFUnits.kVoltage);
  }

  /**
   * Command a position goal with a one-shot constraint override.
   *
   * <p>REVLib has no dynamic MAXMotion — there is no per-request profile, only the configured one.
   * {@link MotorCapabilities#dynamicProfile()} is therefore false, which makes {@code
   * PositionMechanism} choose {@link ControlLocation#RIO_PROFILE_MOTOR_LOOP} for any mechanism that
   * needs a mid-motion constraint change, so this overload is unreachable in normal use. It is
   * implemented rather than left to throw because "unreachable" is a claim about today's caller,
   * not a guarantee, and a backend that throws from a control path is a mechanism that dies.
   *
   * @param outputRotations the goal position, in output-shaft rotations
   * @param outputRotationsPerSecond the goal velocity at that position, in output rot/s
   * @param arbFeedforwardVolts an additional feedforward, in volts
   * @param override the requested constraints; applied to the device configuration, not to the
   *     request
   */
  @Override
  public void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override) {
    if (override != null) {
      applyConstraints(override);
    }
    setPositionGoal(outputRotations, outputRotationsPerSecond, arbFeedforwardVolts);
  }

  /**
   * Command a velocity goal.
   *
   * <p>{@code ControlType.kVelocity} takes the goal in the converted unit — output rot/s, per
   * {@link #buildConfig} — and REVLib applies the configured {@code feedForward.kV} itself in
   * velocity modes (its javadoc says kV is <i>"not applied in Position control mode"</i>, which is
   * why the position path above has to carry that term by hand). The acceleration term rides in the
   * arbitrary feedforward, since {@code kA} is documented as MAXMotion-only.
   *
   * @param outputRps the goal velocity, in output rot/s
   * @param outputRps2 the goal acceleration, in output rot/s^2
   * @param arbFeedforwardVolts an additional feedforward, in volts
   */
  @Override
  public void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts) {
    if (m_location == ControlLocation.RIO_FULL) {
      m_mode = Mode.VELOCITY;
      m_leader.setVoltage(
          m_rioLoop.velocityVolts(
              m_lastMeasuredRps, outputRps, outputRps2, arbFeedforwardVolts, Clock.dt()));
      return;
    }
    double accelerationVolts =
        Double.isFinite(outputRps2) && m_currentGains != null && !m_currentGains.isUntuned()
            ? m_gains.ka(m_currentGains.kA()) * outputRps2
            : 0.0;
    m_mode = Mode.VELOCITY;
    m_lastGoalRps = outputRps;
    m_lastSendSeconds = Clock.seconds();
    m_closedLoop.setSetpoint(
        outputRps,
        SparkBase.ControlType.kVelocity,
        ClosedLoopSlot.kSlot0,
        finiteOrZero(arbFeedforwardVolts) + accelerationVolts,
        ArbFFUnits.kVoltage);
  }

  /**
   * Drive the motor at a fixed voltage.
   *
   * @param volts the commanded voltage; non-finite becomes zero
   */
  @Override
  public void setVoltage(double volts) {
    m_mode = Mode.VOLTAGE;
    invalidateLatch();
    m_leader.setVoltage(finiteOrZero(volts));
  }

  /**
   * Drive the motor at a fixed duty cycle.
   *
   * @param fraction the commanded fraction of bus voltage, clamped to [-1, 1]
   */
  @Override
  public void setDutyCycle(double fraction) {
    m_mode = Mode.DUTY_CYCLE;
    invalidateLatch();
    m_leader.set(MathUtil.clamp(finiteOrZero(fraction), -1.0, 1.0));
  }

  /** Stop commanding output. What the motor then does is the configured {@link NeutralMode}. */
  @Override
  public void setNeutral() {
    m_mode = Mode.NEUTRAL;
    invalidateLatch();
    m_leader.stopMotor();
  }

  /**
   * Convert and push new gains, on the non-blocking path.
   *
   * <p>Delegates to {@link RevGainSink} — this method holds no arithmetic of its own, which is what
   * makes "one place converts gains" true. It also refreshes the device kV used to carry a position
   * request's goal-velocity term, because a tuning session that changes kV and leaves the
   * feedforward carrier on the old value is a mechanism that behaves differently from what the
   * slider says.
   *
   * @param siGains the gains, in volts per SI unit
   */
  @Override
  public void applyGains(Gains siGains) {
    if (siGains == null) {
      return;
    }
    m_rioLoop.applyGains(siGains);
    if (m_gains.apply(siGains)) {
      m_currentGains = siGains;
      m_kvDeviceVoltsPerRps = m_gains.deviceKvVoltsPerOutputRps(siGains);
    }
  }

  /**
   * Push new MAXMotion constraints, on the non-blocking path.
   *
   * <p>Same units as {@link #buildConfig} derives — output rot/s and output rot/s^2, with no
   * {@code * 60}.
   *
   * @param constraints the new constraints; null is ignored
   */
  @Override
  public void applyConstraints(MotionConstraints constraints) {
    if (constraints == null) {
      return;
    }
    m_rioLoop.applyConstraints(constraints);
    SparkBaseConfig partial = RevUtil.newConfig(m_spec);
    partial.closedLoop.maxMotion
        .cruiseVelocity(bounded(constraints.maxVelocityRps(m_units)), RevGainSink.kSlot)
        .maxAcceleration(bounded(constraints.maxAccelerationRps2(m_units)), RevGainSink.kSlot);
    RevUtil.applyFast(m_leader, partial, m_name);
  }

  /**
   * Change what the motor does when nothing is commanding it.
   *
   * <p>Idle mode is one of the parameters REVLib documents as <em>not</em> reset by {@code
   * kResetSafeParameters}, so pushing it as a partial config is safe and does not disturb anything
   * else.
   *
   * @param mode brake or coast; null is ignored
   */
  @Override
  public void setNeutralMode(NeutralMode mode) {
    if (mode == null) {
      return;
    }
    SparkBaseConfig partial = RevUtil.newConfig(m_spec);
    partial.idleMode(
        mode == NeutralMode.BRAKE
            ? SparkBaseConfig.IdleMode.kBrake
            : SparkBaseConfig.IdleMode.kCoast);
    RevUtil.applyFast(m_leader, partial, m_name);
    for (SparkBase follower : m_followers) {
      RevUtil.applyFast(follower, partial, m_name);
    }
  }

  /**
   * Redefine where the mechanism currently is.
   *
   * <p>The value is in output-shaft rotations and lands on the device through the same conversion
   * factor everything else uses, so a re-seed from an absolute encoder cannot disagree with the
   * reading it is correcting.
   *
   * @param outputRotations the position to declare; non-finite is ignored
   */
  @Override
  public void seedPosition(double outputRotations) {
    if (!Double.isFinite(outputRotations)) {
      return;
    }
    m_encoder.setPosition(outputRotations);
    m_lastMeasuredRot = outputRotations;
    m_rioLoop.reset(outputRotations, m_lastMeasuredRps);
    invalidateLatch();
  }

  /**
   * Re-push the whole configuration, blocking.
   *
   * <p>Legal from {@code onDisable()}, self-test and reset recovery only — see {@link RevUtil}.
   */
  @Override
  public void reapplyFullConfigBlocking() {
    RevUtil.applyVerified(m_leader, m_config, m_name);
    for (int i = 0; i < m_followers.length; i++) {
      RevUtil.applyVerified(m_followers[i], m_followerConfigs[i], m_name + "/follower" + i);
    }
    invalidateLatch();
  }

  /**
   * The vendor sim state, present only in simulation.
   *
   * @return the handle, or empty on real hardware
   */
  @Override
  public Optional<SimMotorHandle> simHandle() {
    if (m_sim == null) {
      return Optional.empty();
    }
    // Taking the handle means a real plant is about to own the physics, so the stand-in plant in
    // updateInputs stands down.
    m_simExternallyDriven = true;
    return Optional.of(new SparkSimHandle());
  }

  /**
   * What this device can and cannot do on its own.
   *
   * @return the capabilities, computed once at construction
   */
  @Override
  public MotorCapabilities capabilities() {
    return m_capabilities;
  }

  /**
   * The mechanism-facing name of this motor.
   *
   * @return for example {@code "SPARK MAX 9"}
   */
  @Override
  public String name() {
    return m_name;
  }

  /**
   * Everything a student or a CSA needs to understand what this backend is actually doing.
   *
   * <p>Includes the two honest gaps — no supply current, and reset detection built on a sticky
   * warning rather than on a per-call flag — because a documented gap is a gap a team can plan
   * around and an undocumented one is a surprise at an event.
   *
   * @return a multi-line description
   */
  @Override
  public String describe() {
    StringBuilder out = new StringBuilder(1024);
    out.append(
        String.format(
            Locale.ROOT,
            "%s (%s, %s) via REVLib%n"
                + "  gearing: %.4f rotor rotations per output rotation%n"
                + "    positionConversionFactor %.8f, velocityConversionFactor %.8f"
                + " (rotor RPM -> output rot/s)%n"
                + "  control: %s -- %s%n"
                + "  MAXMotion: cruise %.4f output rot/s, accel %.4f output rot/s^2,"
                + " allowedProfileError %.5f output rot (NO x60)%n"
                + "  current limit: %d A smart limit (%s)%n"
                + "  followers: %d%n"
                + "  soft limits: %s%n"
                + "  status frames: %d ms%n"
                + "  simulation: %s%n"
                + "  NOT reported by REVLib: supply current, torque current, hardware limit-switch"
                + " validity%n"
                + "  device reset: detected via sticky warning hasReset + clearFaults() re-arm."
                + " Phoenix's per-call hasResetOccurred() has no exact REVLib analogue, so"
                + " clearing the reset bit also clears any unread sticky brownout/overcurrent"
                + " record. deviceResetCount counts POST-BOOT resets only.%n"
                + "  capabilities: %s%n",
            m_name,
            m_spec.sparkModel().describe(),
            m_kind.describe(),
            m_rotorPerOutput,
            1.0 / m_rotorPerOutput,
            1.0 / (m_rotorPerOutput * 60.0),
            m_location,
            m_location.explanation(),
            bounded(m_control.constraints().maxVelocityRps(m_units)),
            bounded(m_control.constraints().maxAccelerationRps2(m_units)),
            toleranceOutputRotations(),
            m_limits.smartCurrentLimitAmps(),
            m_limits.describe(),
            m_followers.length,
            describeSoftLimits(),
            periodMillis(),
            m_sim == null
                ? "real hardware"
                : "SparkSim.iterate(velocity, vbus, dt) -- the DEVICE's conversion factors,"
                    + " MAXMotion profile and gravity feedforward are exercised, but REVLib"
                    + " integrates position itself and adds its own filter lag and noise, so this"
                    + " is not bit-exact device timing. Plant: "
                    + (m_simExternallyDriven
                        ? "a real plant, attached through simHandle()"
                        : "STAND-IN first-order spin-up over "
                            + kDefaultSpinUpSeconds
                            + " s -- INVENTED, with no mass, no gravity and no hard stops."
                            + " Do not tune against it"),
            m_capabilities.describe()));
    out.append("  ").append(m_gains.describeConversion().replace("\n", "\n  "));
    return out.toString();
  }

  // ---- REV-specific surface --------------------------------------------------------------------

  /**
   * Apply device-side soft limits, blocking.
   *
   * <p>Not part of {@link MotorIO} because the backend seam carries no {@code PositionLimits}. A
   * mechanism that wants the SPARK itself to enforce travel — rather than relying on the roboRIO
   * loop to stop asking — reaches this through {@code MotorIO.as(SparkMotorIO.class)}.
   *
   * <p>Limits are in <b>output-shaft rotations</b>, the same unit {@code
   * positionConversionFactor} leaves the device measuring in.
   *
   * @param minOutputRotations the reverse limit; non-finite disables the reverse soft limit
   * @param maxOutputRotations the forward limit; non-finite disables the forward soft limit
   * @return true if the device accepted the configuration
   */
  public boolean applySoftLimits(double minOutputRotations, double maxOutputRotations) {
    m_softMinRot = minOutputRotations;
    m_softMaxRot = maxOutputRotations;
    m_config.softLimit
        .forwardSoftLimitEnabled(Double.isFinite(maxOutputRotations))
        .reverseSoftLimitEnabled(Double.isFinite(minOutputRotations));
    if (Double.isFinite(maxOutputRotations)) {
      m_config.softLimit.forwardSoftLimit(maxOutputRotations);
    }
    if (Double.isFinite(minOutputRotations)) {
      m_config.softLimit.reverseSoftLimit(minOutputRotations);
    }
    return RevUtil.applyVerified(m_leader, m_config, m_name);
  }

  /**
   * The live leader controller, for a team that needs a REVLib knob this library does not model.
   *
   * @return the SPARK MAX or SPARK Flex
   */
  public SparkBase leader() {
    return m_leader;
  }

  /**
   * The gain sink, so the tuning layer and its tests can read the conversion back.
   *
   * @return the sink; never null
   */
  public RevGainSink gainSink() {
    return m_gains;
  }

  /**
   * The roboRIO-side loop, used when this backend has been downgraded to {@link
   * ControlLocation#RIO_FULL}.
   *
   * @return the loop; never null, even when unused
   */
  public RioControlLoop loop() {
    return m_rioLoop;
  }

  /**
   * The control location actually in force, after any capability downgrade.
   *
   * @return the effective location
   */
  public ControlLocation location() {
    return m_location;
  }

  // ---- internals -------------------------------------------------------------------------------

  private MotorCapabilities buildCapabilities() {
    return MotorCapabilities.builder()
        .onBoardPositionLoop(true)
        .onBoardVelocityLoop(true)
        .onBoardProfile(true)
        // No dynamic MAXMotion: constraints live in the config, not in the request.
        .dynamicProfile(false)
        .onBoardGravityFeedforward(true)
        .onBoardCosineGravity(true)
        .arbitraryFeedforward(true)
        // setSetpoint has no velocity field; the term rides in the volts-denominated arbFF.
        .positionGoalVelocity(VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS)
        .torqueCurrentControl(false)
        // A SPARK can close its loop on a data-port absolute encoder, but this backend is not
        // handed a FeedbackSpec, so it configures the primary encoder and claims nothing more.
        .fusedAbsoluteEncoder(false)
        .readsTorqueCurrent(false)
        .readsTemperature(true)
        .reportsDeviceReset(true)
        .reportsConnectionHealth(true)
        .build();
  }

  private void warnIfDowngraded(ControlLocation requested) {
    if (requested == null || requested == m_location) {
      return;
    }
    Alerts.warning(
            m_name,
            m_name
                + ": requested ControlLocation."
                + requested
                + " but a SPARK cannot do that, so Rootstock is running "
                + m_location
                + " instead ("
                + m_location.explanation()
                + "). The usual cause is asking for a dynamic profile: REVLib has no per-request"
                + " MAXMotion constraints.",
            MatchImpact.PIT_ONLY)
        .set(true);
  }

  private void handleDeviceReset(MotorInputs inputs) {
    if (m_sim != null || !m_leader.getStickyWarnings().hasReset) {
      return;
    }
    m_deviceResetCount++;
    inputs.deviceResetCount = m_deviceResetCount;
    String health = RevUtil.describeHealth(m_leader);
    // clearFaults() is REVLib's only way to re-arm hasReset, and it clears every other sticky bit
    // with it -- so the unread ones are captured into the alert text on the line above first.
    m_leader.clearFaults();
    RevUtil.applyVerified(m_leader, m_config, m_name);
    invalidateLatch();
    m_resetAlert
        .text(
            m_name
                + ": SPARK "
                + m_spec.deviceId()
                + " reset (reset #"
                + m_deviceResetCount
                + ", sticky state at the time: "
                + health
                + "). A SPARK that resets comes back with its persisted config but NO active"
                + " setpoint and outputs neutral, so a held elevator falls. Config and setpoint"
                + " have been re-sent. If this repeats, check the power and CAN wiring to that"
                + " controller.")
        .set(true);
  }

  private void readFollowers(MotorInputs inputs) {
    int count = m_followers.length;
    if (inputs.followerPositionRot.length != count) {
      inputs.followerPositionRot = new double[count];
      inputs.followerStatorAmps = new double[count];
      inputs.followerTemperatureC = new double[count];
      inputs.followerConnected = new boolean[count];
    }
    for (int i = 0; i < count; i++) {
      SparkBase follower = m_followers[i];
      // A follower has its own primary encoder; its position is in ROTOR rotations because no
      // conversion factor is configured on a follower, so it is converted here rather than logged
      // in a different unit from the leader's.
      inputs.followerPositionRot[i] = follower.getEncoder().getPosition() / m_rotorPerOutput;
      inputs.followerStatorAmps[i] = follower.getOutputCurrent();
      inputs.followerTemperatureC[i] = follower.getMotorTemperature();
      inputs.followerConnected[i] = RevUtil.connected(follower);
    }
  }

  /**
   * Step {@code SparkSim} against a stand-in plant, so a simulated REV mechanism moves at all.
   *
   * <p>{@code SparkSim.iterate(velocity, vbus, dt)} takes an <b>externally computed</b> velocity —
   * REVLib does not model the mechanism, only the controller. Feeding it back its own filtered
   * output would produce a mechanism that never accelerates, so when nothing has taken {@link
   * #simHandle()} this integrates a documented stand-in: a first-order approach to the motor's free
   * speed with a {@link #kDefaultSpinUpSeconds} time constant.
   *
   * <p><b>This is not physics.</b> It has no mass, no moment of inertia, no gravity and no hard
   * stops, because nothing at this seam declared any of them — {@link #describe()} says so rather
   * than letting a team tune against it. Its job is to make the <em>device's</em> conversion
   * factors, MAXMotion profile and gravity feedforward observable in {@code simulateJava}. The
   * moment a mechanism attaches a real plant through {@code simHandle()}, this stops running and
   * the real one takes over.
   */
  private void stepDefaultSimPlant() {
    double dt = Clock.dt();
    double freeSpeedOutputRps =
        m_spec.sparkModel().motor().freeSpeedRotorRps(false) / m_rotorPerOutput;
    double target = m_leader.getAppliedOutput() * freeSpeedOutputRps;
    double alpha = MathUtil.clamp(dt / kDefaultSpinUpSeconds, 0.0, 1.0);
    m_defaultPlantRps += (target - m_defaultPlantRps) * alpha;
    m_sim.iterate(m_defaultPlantRps, kNominalBusVolts, dt);
  }

  private double referenceRot() {
    return switch (m_location) {
      case ON_MOTOR_PROFILED -> m_closedLoop.getMAXMotionSetpointPosition();
      case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP ->
          m_mode == Mode.POSITION ? m_closedLoop.getSetpoint() : Double.NaN;
      case RIO_FULL -> m_mode == Mode.POSITION ? m_rioLoop.referenceRot() : Double.NaN;
    };
  }

  private SparkBaseConfig.IdleMode idleMode() {
    return m_control.neutralMode() == NeutralMode.BRAKE
        ? SparkBaseConfig.IdleMode.kBrake
        : SparkBaseConfig.IdleMode.kCoast;
  }

  private DCMotor gearbox() {
    return m_spec.sparkModel().motor().dcMotor(1);
  }

  private int periodMillis() {
    double hertz =
        Double.isFinite(m_spec.signalRateHz()) && m_spec.signalRateHz() > 0.0
            ? m_spec.signalRateHz()
            : m_kind.defaultSignalRateHz();
    return (int) MathUtil.clamp(Math.round(1000.0 / hertz), 5, 1000);
  }

  private double toleranceOutputRotations() {
    double user = m_control.toleranceUser();
    return Double.isFinite(user) ? Math.abs(m_units.toOutputRotations(user)) : 0.0;
  }

  private String describeSoftLimits() {
    if (!Double.isFinite(m_softMinRot) && !Double.isFinite(m_softMaxRot)) {
      return "NOT configured on the device -- the backend seam carries no PositionLimits, so travel"
          + " is enforced by PositionMechanism on the roboRIO. Call applySoftLimits(...) to push"
          + " them to the SPARK as well.";
    }
    return String.format(
        Locale.ROOT, "[%.4f, %.4f] output rotations, on the device", m_softMinRot, m_softMaxRot);
  }

  private void invalidateLatch() {
    m_lastGoalRot = Double.NaN;
    m_lastGoalRps = Double.NaN;
    m_lastArbFf = Double.NaN;
    m_lastSendSeconds = Double.NEGATIVE_INFINITY;
  }

  private static double bounded(double value) {
    if (!Double.isFinite(value) || value <= 0.0) {
      return kEffectivelyUnbounded;
    }
    return Math.min(value, kEffectivelyUnbounded);
  }

  private static double finiteOrZero(double value) {
    return Double.isFinite(value) ? value : 0.0;
  }

  /**
   * The vendor sim seam, kept inside this artifact so {@code com.revrobotics} never escapes it.
   *
   * <p>The gap this cannot close is stated rather than papered over: REVLib exposes no raw rotor
   * write, so the plant's rotor state is converted back through the same reduction the device is
   * configured with. That still exercises the device's conversion factor in the direction that
   * matters (the device converts what it is given, and the mechanism reads the result), but it is
   * one conversion less than the Phoenix path tests.
   */
  private final class SparkSimHandle implements SimMotorHandle {

    @Override
    public double appliedVolts(double busVoltage) {
      double bus = Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : kNominalBusVolts;
      m_sim.iterate(m_lastMeasuredRps, bus, Clock.dt());
      return m_sim.getAppliedOutput() * bus;
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      if (Double.isFinite(rotorRotations)) {
        m_sim.setPosition(rotorRotations / m_rotorPerOutput);
        m_lastMeasuredRot = rotorRotations / m_rotorPerOutput;
      }
      if (Double.isFinite(rotorRps)) {
        m_sim.setVelocity(rotorRps / m_rotorPerOutput);
        m_lastMeasuredRps = rotorRps / m_rotorPerOutput;
      }
    }

    @Override
    public double statorAmps() {
      return m_sim.getMotorCurrent();
    }
  }
}

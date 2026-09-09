package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.DegreesPerSecond;
import static edu.wpi.first.units.Units.MetersPerSecond;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.LinearVelocity;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.Setpoint;
import org.rootstock.config.VelocityConfig;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.Controllers;
import org.rootstock.control.GainSink;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.control.NeutralMode;
import org.rootstock.control.PlantPrior;
import org.rootstock.control.PositionReference;
import org.rootstock.control.TravelLimits;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.selftest.SelfTestRoutine;
import org.rootstock.core.spi.MechanismGeometry;
import org.rootstock.core.spi.Tier;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorIOFactory;
import org.rootstock.hardware.RioControlLoop;
import org.rootstock.pure.math.RootstockMath;
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.schema.ControlMode;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.SiDomain;

/**
 * Anything whose controlled quantity is a <b>speed</b>: a flywheel, a roller under closed-loop
 * velocity, an indexer with a speed target.
 *
 * <h2>{@code atGoal()} is two-sided, settled and debounced</h2>
 *
 * <p>A flywheel passing through its setpoint on the way up is not ready to shoot, and a single
 * {@code Math.abs(measured - target) < tolerance} says it is. This mechanism requires the speed to
 * be within tolerance, the acceleration to have settled, and both to hold for the configured
 * debounce — and it also tracks {@link #secondsAtGoal()}, which is what a "wait for spin-up after a
 * shot" gate actually wants to read.
 *
 * <h2>Position is not read, and says so</h2>
 *
 * <p>A velocity mechanism subscribes velocity, applied volts, current and temperature. It does not
 * pay CAN bandwidth for a position it never uses, so {@code MotorInputs.positionRot} is
 * {@link Double#NaN} — honest, and visibly wrong on a plot, rather than a frozen zero that survives
 * a whole match looking plausible.
 *
 * <h2>All four control locations</h2>
 *
 * <p>The two on-motor locations hand a velocity goal straight to the device's own loop.
 * {@link ControlLocation#RIO_PROFILE_MOTOR_LOOP} ramps the goal on the roboRIO against the declared
 * acceleration limit and hands each step to the device. {@link ControlLocation#RIO_FULL} runs the
 * same {@link RioControlLoop} every other roboRIO-side loop in this library runs — <b>in SI</b>,
 * because the gains are volts-per-SI and nothing else would mean anything.
 */
public final class VelocityMechanism extends Mechanism
    implements org.rootstock.config.Validation.ConfigCarrier {

  /** The voltage ceiling the roboRIO-side loop clamps its own output to. */
  public static final double kMaxOutputVolts = 12.0;

  /** The relative telemetry key carrying how long the mechanism has been continuously at goal. */
  public static final String kSecondsAtGoalKey = "SecondsAtGoal";

  /** The relative telemetry key carrying the measured acceleration the settle gate reads. */
  public static final String kAccelerationKey = "Acceleration";

  /** The tracer section the live-tuning gain push is budgeted under. */
  public static final String kApplyGainsSection = "Mechanism/ApplyGains";

  /**
   * The fraction of free speed per second below which the mechanism counts as settled, when the
   * config declares no acceleration limit of its own.
   *
   * <p>Expressed as a fraction rather than an absolute so it means the same thing on a 6000 rpm
   * flywheel and on a 60 deg/s roller.
   */
  public static final double kDefaultSettleFractionPerSecond = 0.5;

  private final VelocityConfig m_config;

  /**
   * The config this mechanism was built from, for the boot-time validation sweep.
   *
   * <p>Implementing {@link org.rootstock.config.Validation.ConfigCarrier} is what makes validation
   * reach a robot that registers its MECHANISMS rather than its configs, which is the flow every
   * worked example teaches. Without it {@code Validation.addAll} received mechanisms, {@code
   * errorsOf} recognised only configs, and the whole pipeline found nothing: no per-config faults
   * and no CAN id collision check, on a robot that had done exactly what the documents said.
   *
   * @return the config, never null
   */
  @Override
  public Object config() {
    return m_config;
  }
  private final ControlConfig m_control;
  private final ControlLocation m_location;
  private final RioControlLoop m_loop;
  private final GainSink m_gainSink;
  private final PlantPrior m_prior;
  private final MotionConstraints m_constraints;
  private final double m_toleranceUser;
  private final double m_settleThreshold;
  private final double m_freeSpeedUserPerSec;

  private final RootstockAlert m_untunedGains;
  private final RootstockAlert m_refusedInSafeMode;
  private final RootstockAlert m_derivedGains;

  private Gains m_gains;
  private double m_goal;
  private double m_setpointUser;
  private double m_measured;
  private double m_lastMeasured = Double.NaN;
  private double m_acceleration;
  private double m_outputVolts;
  private double m_openLoopVolts;
  private double m_secondsAtGoal;
  private boolean m_atGoal;
  private long m_lastResetCount;

  private Trigger m_atGoalTrigger;

  /**
   * Builds a velocity mechanism and the backend its motor spec names.
   *
   * @param config the declared mechanism
   * @throws NullPointerException if {@code config} is null
   */
  public VelocityMechanism(VelocityConfig config) {
    this(config, backendFor(config));
  }

  /**
   * Builds a velocity mechanism against a supplied IO — the seam a unit test injects through.
   *
   * @param config the declared mechanism
   * @param io the hardware seam to command
   * @throws NullPointerException if {@code config} or {@code io} is null
   */
  public VelocityMechanism(VelocityConfig config, MotorIO io) {
    super(
        config == null ? null : config.name(),
        config == null ? null : MechanismUnits.of(config.reduction(), config.axis()),
        io,
        config == null ? null : config.motors(),
        buildGeometry(config));
    m_config = Objects.requireNonNull(config, "VelocityMechanism: config must not be null");
    m_control = m_config.control();
    m_location = m_io.capabilities().downgrade(m_control.location());
    m_constraints = m_control.constraints();
    m_freeSpeedUserPerSec = m_config.freeSpeedUserPerSec();
    m_toleranceUser =
        Double.isFinite(m_control.velocityTolerance()) && m_control.velocityTolerance() > 0.0
            ? m_control.velocityTolerance()
            : Math.abs(m_freeSpeedUserPerSec) * 0.02;
    double accelLimit = m_constraints.maxAcceleration();
    m_settleThreshold =
        Double.isFinite(accelLimit) && accelLimit > 0.0
            ? accelLimit * 0.1
            : Math.abs(m_freeSpeedUserPerSec) * kDefaultSettleFractionPerSecond;

    m_prior = priorFor(m_config);

    m_untunedGains =
        Alerts.error(m_config.name(), m_config.name() + "/gains-untuned", MatchImpact.BLOCKS_MATCH)
            .sticky(true);
    m_refusedInSafeMode =
        Alerts.warning(
            m_config.name(), m_config.name() + "/refused-in-safe-mode", MatchImpact.PIT_ONLY);
    m_derivedGains =
        Alerts.warning(
                m_config.name(),
                m_config.name() + "/gains-derived-not-measured",
                MatchImpact.PIT_ONLY)
            .sticky(true);

    m_gains = resolveGains(m_control.gains(), m_prior, m_derivedGains, m_config.name());
    m_loop = new RioControlLoop(m_units, m_control.withGains(m_gains), Clock.dt());

    setStallThresholds(m_config.current().stator().in(Amps) * 0.9, kDefaultStallVelocityRps);
    setOverTemperatureCelsius(kDefaultOverTemperatureCelsius);

    m_io.applyGains(m_gains);
    m_io.applyConstraints(m_constraints);
    m_io.setNeutralMode(m_control.neutralMode());
    m_gainSink = new VelocityGainSink();
    m_lastResetCount = m_inputs.deviceResetCount;
  }

  // ===============================================================================================
  // Speed
  // ===============================================================================================

  /**
   * The commanded speed, in user units per second.
   *
   * @return degrees per second at the output, or metres per second
   */
  public double goal() {
    return m_goal;
  }

  /**
   * The speed the controller is being told to hold right now, in user units per second.
   *
   * <p>Equal to {@link #goal()} unless a roboRIO-side location is ramping toward it against the
   * declared acceleration limit.
   *
   * @return user units per second
   */
  public double setpoint() {
    return m_setpointUser;
  }

  /**
   * The measured speed, in user units per second.
   *
   * @return user units per second
   */
  public double measured() {
    return m_measured;
  }

  /**
   * The measured acceleration, differenced from velocity, in user units per second squared.
   *
   * <p>This is the term that makes {@link #atGoal()} mean <i>arrived</i> rather than <i>passing
   * through</i>.
   *
   * @return user units per second squared
   */
  public double measuredAcceleration() {
    return m_acceleration;
  }

  /**
   * The stator current the leader is drawing, for self-test bands and health checks.
   *
   * @return amps, or NaN when the signal is not subscribed
   */
  public double statorAmps() {
    return m_inputs.statorCurrentAmps;
  }

  // ===============================================================================================
  // Commanding
  // ===============================================================================================

  /**
   * Command a speed in user units per second.
   *
   * @param userPerSecond the speed
   */
  public void setGoal(double userPerSecond) {
    if (safeMode()) {
      refuseInSafeMode("setGoal");
      return;
    }
    if (Double.isNaN(userPerSecond)) {
      return;
    }
    m_goal = userPerSecond;
    setMode(MechanismMode.CLOSED_LOOP);
  }

  /**
   * Command a speed as a typed angular velocity. A no-op on a linear axis, with a named alert.
   *
   * @param v the speed
   */
  public void setGoal(AngularVelocity v) {
    if (v == null) {
      return;
    }
    setGoal(v.in(DegreesPerSecond));
  }

  /**
   * Command a speed as a typed linear velocity.
   *
   * @param v the speed
   */
  public void setGoal(LinearVelocity v) {
    if (v == null) {
      return;
    }
    setGoal(v.in(MetersPerSecond));
  }

  /**
   * Command a declared setpoint's value as a speed.
   *
   * @param named the setpoint, whose value is read in user units per second
   */
  public void setGoal(Setpoint named) {
    if (named != null) {
      setGoal(named.valueUser());
    }
  }

  /**
   * Stop applying output, honouring the configured brake or coast behaviour.
   *
   * <p>Zeroes the goal as well as the mode. A neutral flywheel holding a stale non-zero goal is a
   * mechanism one {@code setMode} away from spinning up on its own, and a published {@code Goal} of
   * 4000 next to a {@code Measured} of 0 is a plot nobody can read.
   */
  public void setNeutral() {
    m_openLoopVolts = 0.0;
    m_goal = 0.0;
    setMode(MechanismMode.NEUTRAL);
  }

  /**
   * Command a raw voltage with no loop closed around it.
   *
   * @param volts the voltage
   */
  @Override
  public void setVoltage(double volts) {
    if (safeMode()) {
      refuseInSafeMode("setVoltage");
      return;
    }
    m_openLoopVolts = Double.isFinite(volts) ? volts : 0.0;
    setMode(MechanismMode.OPEN_LOOP);
  }

  // ===============================================================================================
  // At-goal semantics
  // ===============================================================================================

  /**
   * Whether the mechanism is holding its commanded speed.
   *
   * <p>Within tolerance <b>and</b> settled <b>and</b> debounced, and false while disabled or in
   * SAFE_MODE. A flywheel on its way through the setpoint fails the settle gate, which is the whole
   * point.
   *
   * @return true when the speed has actually arrived
   */
  public boolean atGoal() {
    return m_atGoal;
  }

  /**
   * How long the mechanism has been continuously at goal.
   *
   * <p>Resets to zero the instant it dips out of tolerance, so "wait for spin-up after a shot" is a
   * threshold on this number rather than a hand-rolled timer.
   *
   * @return seconds, accumulated from {@link Clock#dt()} so it replays exactly
   */
  public double secondsAtGoal() {
    return m_secondsAtGoal;
  }

  /**
   * A trigger over {@link #atGoal()}, created once and cached.
   *
   * @return the trigger
   */
  public Trigger atGoalTrigger() {
    if (m_atGoalTrigger == null) {
      m_atGoalTrigger = new Trigger(this::atGoal);
    }
    return m_atGoalTrigger;
  }

  // ===============================================================================================
  // Command factories
  // ===============================================================================================

  /**
   * Hold a speed for as long as the command runs, and go neutral when it ends.
   *
   * <p>Stop-on-end is structural: a roller left running on an interrupt is the failure a surveyed
   * team hand-appended {@code .handleInterrupt(...)} to three separate commands to avoid.
   *
   * @param userPerSecond the speed
   * @return a fresh command
   */
  public Command runAt(double userPerSecond) {
    if (safeMode()) {
      return refusal("runAt");
    }
    return Commands.startEnd(() -> setGoal(userPerSecond), this::setNeutral, this)
        .withName(m_name + ".runAt(" + userPerSecond + ")");
  }

  /**
   * Hold a declared setpoint's speed for as long as the command runs.
   *
   * @param s the setpoint
   * @return a fresh command
   */
  public Command runAt(Setpoint s) {
    if (safeMode()) {
      return refusal("runAt");
    }
    String label = s == null ? "null" : s.name();
    return Commands.startEnd(() -> setGoal(s), this::setNeutral, this)
        .withName(m_name + ".runAt(" + label + ")");
  }

  /**
   * Spin up to a speed and end once {@link #atGoal()} latches. Does <b>not</b> stop on end.
   *
   * <p>Deliberately different from {@link #runAt(double)}: the whole point of waiting for spin-up is
   * that the next command shoots into a wheel that is still turning.
   *
   * @param userPerSecond the speed
   * @return a fresh command
   */
  public Command spinUpAndWait(double userPerSecond) {
    if (safeMode()) {
      return refusal("spinUpAndWait");
    }
    return Commands.startRun(() -> setGoal(userPerSecond), () -> {}, this)
        .until(this::atGoal)
        .withName(m_name + ".spinUpAndWait(" + userPerSecond + ")");
  }

  /**
   * Command neutral and stay there.
   *
   * @return a fresh command
   */
  public Command neutral() {
    return Commands.startRun(this::setNeutral, () -> {}, this).withName(m_name + ".neutral");
  }

  // ===============================================================================================
  // The loop
  // ===============================================================================================

  @Override
  protected void onPeriodic() {
    double dt = dtSeconds();
    m_measured = m_units.toUserPerSec(m_inputs.velocityRps);
    m_acceleration =
        Double.isFinite(m_measured) && Double.isFinite(m_lastMeasured)
            ? (m_measured - m_lastMeasured) / dt
            : 0.0;
    m_lastMeasured = m_measured;

    if (m_inputs.deviceResetCount > m_lastResetCount) {
      m_lastResetCount = m_inputs.deviceResetCount;
      m_io.applyGains(m_gains);
      m_io.applyConstraints(m_constraints);
    }

    if (MatchContext.isDisabled() || safeMode()) {
      setMode(MechanismMode.NEUTRAL);
      m_setpointUser = 0.0;
    }

    MechanismMode mode = mode();
    if (mode == MechanismMode.NEUTRAL) {
      m_outputVolts = 0.0;
      m_setpointUser = 0.0;
      m_io.setNeutral();
    } else if (mode == MechanismMode.CLOSED_LOOP) {
      applyClosedLoop(dt);
    } else {
      m_outputVolts = Double.isFinite(m_openLoopVolts) ? m_openLoopVolts : 0.0;
      m_setpointUser = 0.0;
      m_io.setVoltage(m_outputVolts);
    }

    boolean raw =
        mode() == MechanismMode.CLOSED_LOOP
            && Double.isFinite(m_measured)
            && Math.abs(m_goal - m_measured) <= m_toleranceUser
            && Math.abs(m_acceleration) <= m_settleThreshold;
    boolean gated = raw && !MatchContext.isDisabled() && !safeMode();
    if (gated) {
      m_secondsAtGoal += dt;
    } else {
      m_secondsAtGoal = 0.0;
    }
    m_atGoal = gated && m_secondsAtGoal >= m_control.goalDebounceSeconds();

    schema()
        .goal(m_goal)
        .setpoint(m_setpointUser)
        .setpointVelocity(m_setpointUser)
        .measured(m_measured)
        .output(m_outputVolts)
        .atGoal(m_atGoal)
        .atSetpoint(raw)
        .state(mode())
        .controlMode(controlModeOf(mode()))
        .homed(true)
        .softLimits(-Math.abs(m_freeSpeedUserPerSec), Math.abs(m_freeSpeedUserPerSec))
        .currentLimitAmps(m_config.current().stator().in(Amps))
        .simEnabled(Platform.isSimulation());
    schema().gains(m_gains);
    schema().extra(kSecondsAtGoalKey, m_secondsAtGoal);
    schema().extra(kAccelerationKey, m_acceleration);
  }

  private void applyClosedLoop(double dt) {
    if (m_gains.isUntuned()) {
      m_untunedGains
          .text(
              m_name
                  + "/gains-untuned: closed-loop velocity control is refused because kP is the "
                  + "UNTUNED placeholder. Expected measured gains. Fix: run the tuning wizard, or "
                  + "set .gains(Gains.feedforward(kS, kV, kA)). A flywheel runs well on "
                  + "feedforward alone.")
          .set(true);
      m_outputVolts = 0.0;
      m_setpointUser = 0.0;
      m_io.setNeutral();
      return;
    }
    m_untunedGains.set(false);

    double accelLimit = m_constraints.maxAcceleration();
    if (m_location.profileOnRio() && Double.isFinite(accelLimit) && accelLimit > 0.0) {
      // The roboRIO owns the ramp: step the setpoint toward the goal at the declared acceleration,
      // so the feedforward sees a velocity the mechanism can actually be at next cycle.
      double step = accelLimit * dt;
      m_setpointUser = m_setpointUser + RootstockMath.clamp(m_goal - m_setpointUser, -step, step);
    } else {
      m_setpointUser = m_goal;
    }
    double goalRps = m_units.toOutputRps(m_setpointUser);
    double goalRps2 = m_units.toOutputRps2(Double.isFinite(accelLimit) ? accelLimit : 0.0);

    if (m_location == ControlLocation.RIO_FULL) {
      m_outputVolts =
          RootstockMath.clamp(
              m_loop.velocityVolts(m_inputs.velocityRps, goalRps, goalRps2, 0.0, dt),
              -kMaxOutputVolts,
              kMaxOutputVolts);
      m_io.setVoltage(m_outputVolts);
      return;
    }
    m_io.setVelocityGoal(goalRps, goalRps2, 0.0);
    m_outputVolts = Double.isFinite(m_inputs.appliedVolts) ? m_inputs.appliedVolts : 0.0;
  }

  private static ControlMode controlModeOf(MechanismMode mode) {
    if (mode == MechanismMode.CLOSED_LOOP) {
      return ControlMode.VELOCITY;
    }
    if (mode == MechanismMode.NEUTRAL) {
      return ControlMode.NEUTRAL;
    }
    return ControlMode.VOLTAGE;
  }

  // ===============================================================================================
  // Telemetry, health and self test
  // ===============================================================================================

  @Override
  protected void describeExtras(TelemetryDescriptor d) {
    d.extra(kSecondsAtGoalKey, Seconds, Tier.STANDARD);
    d.extra(kAccelerationKey, Tier.STANDARD);
  }

  @Override
  protected void pollMechanismHealth(FaultCollector out) {
    if (m_gains.isUntuned()) {
      out.error(
          m_name,
          "gains are the UNTUNED placeholder (kP is NaN), so closed-loop velocity control is "
              + "refused and the mechanism will not spin up. Expected measured gains. Fix: run the "
              + "tuning wizard, or set .gains(...) on the config.");
    }
    if (Double.isFinite(m_goal)
        && Math.abs(m_goal) > Math.abs(m_freeSpeedUserPerSec)
        && Math.abs(m_freeSpeedUserPerSec) > 0.0) {
      out.warn(
          m_name,
          String.format(
              Locale.ROOT,
              "commanded %.2f %s/s but the motor's free speed through this gearbox is only %.2f "
                  + "%s/s, so atGoal() can never be true. Expected a goal below free speed. Fix: "
                  + "lower the goal, or check the reduction.",
              m_goal,
              m_units.unitLabel(),
              m_freeSpeedUserPerSec,
              m_units.unitLabel()));
    }
  }

  @Override
  public SelfTestRoutine selfTestRoutine() {
    double target = m_freeSpeedUserPerSec * 0.25;
    String unit = m_units.unitLabel() + "/s";
    return SelfTestRoutine.of(m_name)
        .current(this::statorAmps)
        .step("spin up", spinUpAndWait(target).withTimeout(Seconds.of(4.0)))
        .expect(this::measured, target, Math.max(m_toleranceUser * 3.0, 1e-6), unit)
        .expectMoved(this::measured, Math.max(Math.abs(target) * 0.5, 1e-6), unit)
        .withTimeout(Seconds.of(5.0))
        .step("coast down", neutral().withTimeout(Seconds.of(1.0)))
        .withTimeout(Seconds.of(2.0))
        .expectNoNewFaults()
        .build();
  }

  // ===============================================================================================
  // TuningTarget
  // ===============================================================================================

  @Override
  public MechanismArchetype archetype() {
    return MechanismArchetype.FLYWHEEL;
  }

  @Override
  public ControlLocation controlLocation() {
    return m_location;
  }

  @Override
  public PositionReference positionReference() {
    // A flywheel has no meaningful absolute position; the rotor is all there is and all there needs
    // to be, and every position abort is disabled for this archetype anyway.
    return new PositionReference.RotorOnly();
  }

  @Override
  public TravelLimits travelLimits() {
    // Stated rather than omitted: a mechanism that genuinely has no travel limits says so.
    return TravelLimits.unbounded();
  }

  @Override
  public PlantPrior plantPrior() {
    return m_prior;
  }

  @Override
  public Gains gains() {
    return m_gains;
  }

  @Override
  public GainSink gainSink() {
    return m_gainSink;
  }

  @Override
  public double measuredSi() {
    // Position is deliberately not subscribed on a velocity mechanism, so there is nothing honest to
    // report here. NaN propagates loudly; a zero would look like a real reading.
    return Double.NaN;
  }

  @Override
  public double velocitySi() {
    return m_units.toSiPerSec(m_measured);
  }

  @Override
  public double accelerationSi() {
    return m_units.toSiPerSec2(m_acceleration);
  }

  @Override
  public double toleranceSi() {
    return m_units.toSiPerSec(m_toleranceUser);
  }

  @Override
  public GravityMode gravityMode() {
    return GravityMode.NONE;
  }

  @Override
  public Optional<NeutralMode> neutralMode() {
    return Optional.of(m_control.neutralMode());
  }

  @Override
  public boolean supportsClosedLoop() {
    return true;
  }

  @Override
  public void setClosedLoopGoalSi(double goalSi) {
    setGoal(m_units.fromSiPerSec(goalSi));
  }

  @Override
  public boolean isProfiled() {
    return false;
  }

  @Override
  protected String describeDetail() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("  location: ")
        .append(m_location)
        .append(m_location == m_control.location() ? "" : " (downgraded from " + m_control.location() + ")");
    sb.append(System.lineSeparator()).append("  loop:     ").append(m_loop.describe());
    sb.append(System.lineSeparator())
        .append("  signals NOT read: position. A velocity mechanism does not pay CAN for a ")
        .append("position it never uses, so MotorInputs.positionRot is NaN by design.");
    sb.append(System.lineSeparator())
        .append(
            String.format(
                Locale.ROOT,
                "  atGoal:   within %.3f %s/s and settled below %.3f %s/s^2 for %.3f s",
                m_toleranceUser,
                m_units.unitLabel(),
                m_settleThreshold,
                m_units.unitLabel(),
                m_control.goalDebounceSeconds()));
    return sb.toString();
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  private void refuseInSafeMode(String verb) {
    m_refusedInSafeMode
        .text(
            m_name
                + "/refused-in-safe-mode: "
                + verb
                + " did nothing because SAFE_MODE is active. Fix the configuration errors printed "
                + "at boot and reboot.")
        .set(true);
  }

  private Command refusal(String verb) {
    return Commands.none()
        .beforeStarting(() -> refuseInSafeMode(verb))
        .withName(m_name + "." + verb + " (refused: SAFE_MODE)");
  }

  private static double dtSeconds() {
    double dt = Clock.dt();
    return Double.isFinite(dt) && dt > 0.0 ? dt : 0.02;
  }

  private static Gains resolveGains(
      Gains declared, PlantPrior prior, RootstockAlert derived, String name) {
    if (declared == null) {
      return Gains.UNTUNED;
    }
    if (!declared.isUntuned() || Platform.isReal()) {
      return declared;
    }
    double kV = prior.kVprior();
    double kA = prior.kAprior();
    if (!Double.isFinite(kV) || !Double.isFinite(kA)) {
      return declared;
    }
    // A velocity loop's plant is first order, so the placement is a single pole: kP = kA / tau with
    // tau one tenth of the open-loop time constant kA/kV. It spins up without the integrator wind-up
    // that a guessed kI would produce.
    double kP = kV > 0.0 ? 10.0 * kV : kA;
    Gains guess = new Gains(kP, 0.0, 0.0, 0.0, kV, kA, 0.0);
    derived
        .text(
            name
                + "/gains-derived-not-measured: this mechanism declared Gains.UNTUNED and is "
                + "running in simulation, so Rootstock derived "
                + guess.describe(SiDomain.ROTATIONAL_RADIANS)
                + " from your declared moment of inertia and gearing. They were DERIVED, not "
                + "measured. Run the tuning wizard before trusting them on hardware.")
        .set(true);
    return guess;
  }

  private static PlantPrior priorFor(VelocityConfig config) {
    MotorGroup motors = config.motors();
    DCMotor gearbox = motors.model().dcMotor(motors.count(), motors.leader().foc());
    return PlantPrior.flywheel(gearbox, config.reduction(), config.sim().moiKgM2());
  }

  private static MotorIO backendFor(VelocityConfig config) {
    Objects.requireNonNull(config, "VelocityMechanism: config must not be null");
    return MotorIOFactory.create(
        // limits is null on purpose: a flywheel has no travel range, and inventing one would arm a
        // firmware soft limit against a mechanism that is supposed to spin forever.
        new MotorIOFactory.DeviceSetup(
            config.motors(), config.current(), null, config.feedback()),
        config.units(),
        config.control(),
        MechanismKind.VELOCITY,
        RootstockLog.mode());
  }

  /**
   * Compute the plant declaration once. Static so it can run before {@code super()} completes.
   *
   * @param config the declared mechanism
   * @return the geometry a {@code FlywheelSim} is built from
   */
  private static MechanismGeometry buildGeometry(VelocityConfig config) {
    Objects.requireNonNull(config, "VelocityMechanism: config must not be null");
    MechanismUnits units = config.units();
    MotorGroup motors = config.motors();
    DCMotor gearbox = motors.model().dcMotor(motors.count(), motors.leader().foc());
    return new MechanismGeometry(
        config.name(),
        MechanismGeometry.Kind.FLYWHEEL,
        gearbox,
        config.reduction().rotorPerOutput(),
        units.siPerOutputRotation(),
        Double.NaN,
        Double.NaN,
        config.sim().moiKgM2(),
        Double.NaN,
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        0.0,
        false);
  }

  /** Writes tuned gains through to wherever this mechanism's loop actually runs. */
  private final class VelocityGainSink implements GainSink {

    @Override
    public boolean apply(Gains gains) {
      if (gains == null) {
        return false;
      }
      m_gains = gains;
      m_loop.applyGains(gains);
      RootstockTracer.enter(kApplyGainsSection);
      m_io.applyGains(gains);
      RootstockTracer.exit(kApplyGainsSection);
      return true;
    }

    @Override
    public String describeConversion() {
      return m_units.describe(m_name);
    }

    @Override
    public double siUnitsPerMechanismRotation() {
      return m_units.siPerOutputRotation();
    }
  }
}

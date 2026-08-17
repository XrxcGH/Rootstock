package org.pumpkinlib.mechanism;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.SensorSpec;
import org.pumpkinlib.config.SimpleConfig;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.GainSink;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.control.MechanismArchetype;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.control.PlantPrior;
import org.pumpkinlib.control.PositionReference;
import org.pumpkinlib.control.TravelLimits;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.health.FaultCollector;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.core.selftest.SelfTestRoutine;
import org.pumpkinlib.core.spi.MechanismGeometry;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.hardware.DigitalSensorIO;
import org.pumpkinlib.hardware.MotorIO;
import org.pumpkinlib.hardware.MotorIOFactory;
import org.pumpkinlib.pure.math.PumpkinMath;
import org.pumpkinlib.telemetry.PumpkinLog;
import org.pumpkinlib.telemetry.TelemetryDescriptor;
import org.pumpkinlib.telemetry.schema.ControlMode;
import org.pumpkinlib.units.MechanismUnits;
import org.pumpkinlib.units.RotaryAxis;

/**
 * Open-loop rollers, intakes, indexers and feeders — anything commanded as a percentage.
 *
 * <h2>Stop-on-end is structural, not remembered</h2>
 *
 * <p>Every command factory here is built from {@code startEnd} or {@code runEnd}, so the roller
 * stops when the command ends <b>and</b> when it is interrupted, without anybody appending
 * {@code .handleInterrupt(...)}. A surveyed robot needed that append on three separate commands with
 * the comment "never leave rollers running on interrupt"; making it the only available shape is the
 * difference between a guarantee and a comment.
 *
 * <h2>{@code holding()} is a LEVEL, never an edge</h2>
 *
 * <p>{@link #intakeUntilHeld(double)} works even when the game piece was already present when the
 * command started, which is exactly the five-line workaround an edge-triggered auto-stop forced on a
 * surveyed robot. The sensor is debounced by the library, in frames rather than against a wall
 * clock, so it replays.
 */
public final class SimpleMechanism extends Mechanism {

  /** The voltage a duty cycle of 1.0 is commanded as when the backend wants volts. */
  public static final double kMaxOutputVolts = 12.0;

  /** The relative telemetry key carrying the debounced "am I holding a game piece?" level. */
  public static final String kHoldingKey = "Holding";

  /** The relative telemetry key carrying the commanded duty cycle. */
  public static final String kDutyCycleKey = "DutyCycle";

  /** Stator current above which a roller with no declared stall sensor counts as stalled, in amps. */
  public static final double kDefaultStallFractionOfLimit = 0.9;

  private final SimpleConfig m_config;
  private final PlantPrior m_prior;
  private final GainSink m_gainSink;
  private final SensorSpec m_heldSpec;
  private final SensorSpec m_stallSpec;
  private final Debounce m_heldDebounce;
  private final Debounce m_stallDebounce;
  private final PumpkinAlert m_refusedInSafeMode;
  private final PumpkinAlert m_noHeldSensor;

  private final DigitalSensorIO.DigitalSensorInputs m_heldInputs =
      new DigitalSensorIO.DigitalSensorInputs();

  /** Precomputed, because concatenating it in {@code periodic()} allocates 50 strings a second. */
  private final String m_heldInputsKey;

  private DigitalSensorIO m_heldIo;
  private double m_dutyCycle;
  private double m_outputVolts;
  private boolean m_holding;
  private boolean m_stalled;

  private Trigger m_holdingTrigger;

  /**
   * Builds an open-loop mechanism and the backend its motor spec names.
   *
   * @param config the declared mechanism
   * @throws NullPointerException if {@code config} is null
   */
  public SimpleMechanism(SimpleConfig config) {
    this(config, backendFor(config));
  }

  /**
   * Builds an open-loop mechanism against a supplied IO — the seam a unit test injects through.
   *
   * @param config the declared mechanism
   * @param io the hardware seam to command
   * @throws NullPointerException if {@code config} or {@code io} is null
   */
  public SimpleMechanism(SimpleConfig config, MotorIO io) {
    super(
        config == null ? null : config.name(),
        config == null ? null : MechanismUnits.of(config.reduction(), RotaryAxis.roller()),
        io,
        config == null ? null : config.motors(),
        buildGeometry(config));
    m_config = Objects.requireNonNull(config, "SimpleMechanism: config must not be null");
    m_heldSpec = m_config.heldSensor().orElse(null);
    m_stallSpec = m_config.stallSensor().orElse(null);
    m_heldDebounce = new Debounce(debounceSecondsOf(m_heldSpec));
    m_stallDebounce = new Debounce(debounceSecondsOf(m_stallSpec));
    m_prior =
        PlantPrior.flywheel(
            m_config.motors().model().dcMotor(m_config.motors().count(), m_config.motors().leader().foc()),
            m_config.reduction(),
            m_config.sim().moiKgM2());

    m_refusedInSafeMode =
        Alerts.warning(
            m_config.name(), m_config.name() + "/refused-in-safe-mode", MatchImpact.PIT_ONLY);
    m_noHeldSensor =
        Alerts.warning(m_config.name(), m_config.name() + "/no-held-sensor", MatchImpact.PIT_ONLY);

    setStallThresholds(
        m_config.current().stator().in(Amps) * kDefaultStallFractionOfLimit,
        kDefaultStallVelocityRps);
    setOverTemperatureCelsius(kDefaultOverTemperatureCelsius);
    m_io.setNeutralMode(m_config.neutralMode());
    m_gainSink = new NoOpGainSink();
    m_heldInputsKey = kPrefix + "/HeldSensorInputs";
  }

  // ===============================================================================================
  // Commanding
  // ===============================================================================================

  /**
   * Command a duty cycle.
   *
   * @param dutyCycle the commanded output, -1..1; clamped rather than rejected
   */
  public void set(double dutyCycle) {
    if (safeMode()) {
      refuseInSafeMode("set");
      return;
    }
    m_dutyCycle = PumpkinMath.clamp(Double.isFinite(dutyCycle) ? dutyCycle : 0.0, -1.0, 1.0);
    setMode(m_dutyCycle == 0.0 ? MechanismMode.NEUTRAL : MechanismMode.OPEN_LOOP);
  }

  /**
   * Command a raw voltage.
   *
   * @param volts the voltage
   */
  @Override
  public void setVoltage(double volts) {
    set((Double.isFinite(volts) ? volts : 0.0) / kMaxOutputVolts);
  }

  /**
   * The duty cycle currently commanded.
   *
   * @return -1..1
   */
  public double dutyCycle() {
    return m_dutyCycle;
  }

  /**
   * The stator current the leader is drawing, for self-test bands and health checks.
   *
   * @return amps, or NaN when the signal is not subscribed
   */
  public double statorAmps() {
    return m_inputs.statorCurrentAmps;
  }

  /**
   * Attach the sensor that answers {@link #holding()}.
   *
   * <p>Core ships a DIO backend and a simulation backend; a CANrange or a CANdi lives in a vendor
   * adapter, so that one is handed in here. A declared held sensor with nothing attached publishes
   * {@code Holding = false} and raises a named alert rather than reporting a piece that is not there.
   *
   * @param sensor the sensor seam, or null to detach
   */
  public void setHeldSensor(DigitalSensorIO sensor) {
    m_heldIo = sensor;
  }

  // ===============================================================================================
  // Triggers
  // ===============================================================================================

  /**
   * The debounced "am I holding a game piece?" level.
   *
   * <p>A level, never an edge: a command that starts with the piece already present sees true on its
   * first iteration.
   *
   * @return the trigger; always false when no held sensor is declared
   */
  public Trigger holding() {
    if (m_holdingTrigger == null) {
      m_holdingTrigger = new Trigger(() -> m_holding);
    }
    return m_holdingTrigger;
  }

  /**
   * Whether the mechanism is holding a game piece right now.
   *
   * @return the debounced level
   */
  public boolean isHolding() {
    return m_holding;
  }

  /**
   * The stall heuristic, honouring a declared stator-current stall sensor when there is one.
   *
   * @return true when the mechanism appears stalled
   */
  @Override
  protected boolean isStalled() {
    return m_stalled;
  }

  // ===============================================================================================
  // Command factories
  // ===============================================================================================

  /**
   * Run at a duty cycle until interrupted, then stop.
   *
   * @param dutyCycle the commanded output, -1..1
   * @return a fresh command
   */
  public Command run(double dutyCycle) {
    if (safeMode()) {
      return refusal("run");
    }
    return Commands.startEnd(() -> set(dutyCycle), this::stopRollers, this)
        .withName(m_name + ".run(" + dutyCycle + ")");
  }

  /**
   * Run at a duty cycle for a fixed time, then stop.
   *
   * @param dutyCycle the commanded output, -1..1
   * @param duration how long to run
   * @return a fresh command
   */
  public Command runFor(double dutyCycle, Time duration) {
    Time bounded = duration == null ? Seconds.of(0.0) : duration;
    return run(dutyCycle).withTimeout(bounded).withName(m_name + ".runFor(" + dutyCycle + ")");
  }

  /**
   * Run at a duty cycle until a condition holds, then stop.
   *
   * @param dutyCycle the commanded output, -1..1
   * @param stop the condition that ends the command
   * @return a fresh command
   */
  public Command runUntil(double dutyCycle, BooleanSupplier stop) {
    BooleanSupplier condition = stop == null ? () -> true : stop;
    return run(dutyCycle).until(condition).withName(m_name + ".runUntil(" + dutyCycle + ")");
  }

  /**
   * Intake until the held sensor says the piece is in, then stop.
   *
   * <p>Level, not edge — a piece already present when the command starts ends it immediately, which
   * is the correct behaviour and the one that needed a workaround on a surveyed robot.
   *
   * @param dutyCycle the commanded output, -1..1
   * @return a fresh command
   */
  public Command intakeUntilHeld(double dutyCycle) {
    if (!m_config.hasHeldSensor()) {
      m_noHeldSensor
          .text(
              m_name
                  + "/no-held-sensor: intakeUntilHeld("
                  + dutyCycle
                  + ") was requested but this mechanism declares no held sensor, so nothing can "
                  + "ever end it except an interrupt. Expected .heldSensor(SensorSpec.dio(0, "
                  + "true)) or a stator-current threshold on the config.")
          .set(true);
    }
    return runUntil(dutyCycle, () -> m_holding).withName(m_name + ".intakeUntilHeld");
  }

  /**
   * Outtake at a duty cycle for a fixed time, then stop.
   *
   * <p>The sign is applied here so a team writes the same positive number for intake and outtake and
   * cannot get the direction wrong by forgetting a minus.
   *
   * @param dutyCycle the commanded output magnitude, 0..1
   * @param duration how long to run
   * @return a fresh command
   */
  public Command outtakeFor(double dutyCycle, Time duration) {
    return runFor(-Math.abs(dutyCycle), duration).withName(m_name + ".outtakeFor(" + dutyCycle + ")");
  }

  // ===============================================================================================
  // The loop
  // ===============================================================================================

  @Override
  protected void onPeriodic() {
    if (m_heldIo != null) {
      m_heldIo.updateInputs(m_heldInputs);
      PumpkinLog.processInputs(m_heldInputsKey, m_heldInputs);
    }
    m_holding = m_heldDebounce.calculate(rawHeld());
    m_stalled = m_stallDebounce.calculate(rawStalled());

    if (MatchContext.isDisabled() || safeMode()) {
      m_dutyCycle = 0.0;
      setMode(MechanismMode.NEUTRAL);
    }

    if (mode() == MechanismMode.NEUTRAL) {
      m_outputVolts = 0.0;
      m_io.setNeutral();
    } else {
      m_outputVolts = m_dutyCycle * kMaxOutputVolts;
      m_io.setDutyCycle(m_dutyCycle);
    }

    schema()
        .goal(m_dutyCycle)
        .setpoint(m_dutyCycle)
        .measured(m_units.toUserPerSec(m_inputs.velocityRps))
        .output(m_outputVolts)
        .atGoal(mode() != MechanismMode.NEUTRAL)
        .atSetpoint(mode() != MechanismMode.NEUTRAL)
        .state(mode())
        .controlMode(mode() == MechanismMode.NEUTRAL ? ControlMode.NEUTRAL : ControlMode.VOLTAGE)
        .homed(true)
        .currentLimitAmps(m_config.current().stator().in(Amps))
        .simEnabled(Platform.isSimulation());
    schema().extra(kHoldingKey, m_holding);
    schema().extra(kDutyCycleKey, m_dutyCycle);
  }

  private boolean rawHeld() {
    if (m_heldSpec == null) {
      return false;
    }
    if (m_heldSpec instanceof SensorSpec.StatorCurrent stator) {
      double amps = m_inputs.statorCurrentAmps;
      return !Double.isNaN(amps) && amps >= stator.threshold().in(Amps);
    }
    if (m_heldSpec instanceof SensorSpec.Sim sim) {
      return sim.detected().getAsBoolean();
    }
    if (m_heldSpec instanceof SensorSpec.MotorLimit motorLimit) {
      return motorLimit.side() == SensorSpec.Limit.FORWARD
          ? m_inputs.forwardLimitValid && m_inputs.forwardLimitTripped
          : m_inputs.reverseLimitValid && m_inputs.reverseLimitTripped;
    }
    if (m_heldIo != null) {
      return m_heldInputs.detected;
    }
    m_noHeldSensor
        .text(
            m_name
                + "/no-held-sensor: "
                + m_heldSpec.describe()
                + " was declared but no DigitalSensorIO is attached, so Holding can never be true. "
                + "Fix: call setHeldSensor(io) with the backend for that sensor.")
        .set(true);
    return false;
  }

  private boolean rawStalled() {
    if (m_stallSpec instanceof SensorSpec.StatorCurrent stator) {
      double amps = m_inputs.statorCurrentAmps;
      return !Double.isNaN(amps) && amps >= stator.threshold().in(Amps);
    }
    return super.isStalled();
  }

  // ===============================================================================================
  // Telemetry, health and self test
  // ===============================================================================================

  @Override
  protected void describeExtras(TelemetryDescriptor d) {
    d.extra(kHoldingKey, Tier.CRITICAL);
    d.extra(kDutyCycleKey, Tier.STANDARD);
  }

  @Override
  protected void pollMechanismHealth(FaultCollector out) {
    if (m_config.hasHeldSensor() && m_heldIo == null && needsSensorIo()) {
      out.warn(
          m_name,
          "declares a held sensor that no backend is reading, so Holding is permanently false and "
              + "intakeUntilHeld(...) can never end on its own. Fix: call setHeldSensor(io).");
    }
  }

  @Override
  public SelfTestRoutine selfTestRoutine() {
    return SelfTestRoutine.of(m_name)
        .current(this::statorAmps)
        .step("forward", runFor(0.2, Seconds.of(0.5)))
        .expectMoved(() -> m_units.toUserPerSec(m_inputs.velocityRps), 1e-3, m_units.unitLabel() + "/s")
        .withTimeout(Seconds.of(2.0))
        .step("reverse", runFor(-0.2, Seconds.of(0.5)))
        .withTimeout(Seconds.of(2.0))
        .expectNoNewFaults()
        .build();
  }

  // ===============================================================================================
  // TuningTarget — a roller is not tuned, and says so rather than pretending
  // ===============================================================================================

  @Override
  public MechanismArchetype archetype() {
    return MechanismArchetype.FLYWHEEL;
  }

  @Override
  public ControlLocation controlLocation() {
    // An open-loop mechanism closes no loop anywhere; RIO_FULL is the honest answer because the only
    // thing that ever reaches the device is a duty cycle this class computed.
    return ControlLocation.RIO_FULL;
  }

  @Override
  public PositionReference positionReference() {
    return new PositionReference.RotorOnly();
  }

  @Override
  public TravelLimits travelLimits() {
    return TravelLimits.unbounded();
  }

  @Override
  public PlantPrior plantPrior() {
    return m_prior;
  }

  @Override
  public Gains gains() {
    // There is no loop, so there are no gains. UNTUNED is the placeholder that says exactly that,
    // and the tuning wizard refuses to tune a target that reports it.
    return Gains.UNTUNED;
  }

  @Override
  public GainSink gainSink() {
    return m_gainSink;
  }

  @Override
  public double measuredSi() {
    return Double.NaN;
  }

  @Override
  public double velocitySi() {
    return m_units.toSiPerSec(m_units.toUserPerSec(m_inputs.velocityRps));
  }

  @Override
  public GravityMode gravityMode() {
    return GravityMode.NONE;
  }

  @Override
  public Optional<NeutralMode> neutralMode() {
    return Optional.of(m_config.neutralMode());
  }

  @Override
  public OptionalDouble absolutePositionSi() {
    return OptionalDouble.empty();
  }

  @Override
  protected String describeDetail() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("  open loop: duty cycle only; no gains and no closed loop exist on this mechanism.");
    sb.append(System.lineSeparator())
        .append("  held:      ")
        .append(m_heldSpec == null ? "none declared" : m_heldSpec.describe());
    sb.append(System.lineSeparator())
        .append("  stall:     ")
        .append(m_stallSpec == null ? "stator current heuristic" : m_stallSpec.describe());
    return sb.toString();
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  /**
   * Zero the duty cycle and go neutral.
   *
   * <p>Distinct from {@link Mechanism#stop()}, which cannot clear this class's commanded duty cycle
   * because it is {@code final} on the base. Every command factory ends here, so a roller cannot be
   * left running by an interrupt <i>and</i> cannot keep publishing a duty cycle it is not applying.
   * A no-op in SAFE_MODE would be wrong, so this deliberately does not go through {@link #set}.
   */
  private void stopRollers() {
    m_dutyCycle = 0.0;
    setMode(MechanismMode.NEUTRAL);
    m_io.setNeutral();
  }

  private boolean needsSensorIo() {
    return !(m_heldSpec instanceof SensorSpec.StatorCurrent)
        && !(m_heldSpec instanceof SensorSpec.Sim)
        && !(m_heldSpec instanceof SensorSpec.MotorLimit);
  }

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

  private static double debounceSecondsOf(SensorSpec spec) {
    if (spec == null) {
      return 0.0;
    }
    Time debounce = spec.debounce();
    return debounce == null ? 0.0 : debounce.in(Seconds);
  }

  private static MotorIO backendFor(SimpleConfig config) {
    Objects.requireNonNull(config, "SimpleMechanism: config must not be null");
    return MotorIOFactory.create(
        config.motors().leader(),
        config.units(),
        ControlConfig.defaults(),
        MechanismKind.SIMPLE,
        PumpkinLog.mode());
  }

  /**
   * Compute the plant declaration once. Static so it can run before {@code super()} completes.
   *
   * @param config the declared mechanism
   * @return the geometry a {@code DCMotorSim} is built from
   */
  private static MechanismGeometry buildGeometry(SimpleConfig config) {
    Objects.requireNonNull(config, "SimpleMechanism: config must not be null");
    MechanismUnits units = MechanismUnits.of(config.reduction(), RotaryAxis.roller());
    DCMotor gearbox =
        config.motors().model().dcMotor(config.motors().count(), config.motors().leader().foc());
    return new MechanismGeometry(
        config.name(),
        MechanismGeometry.Kind.SIMPLE,
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

  /** There is no loop here, so there is nothing to write gains into — and it says so. */
  private static final class NoOpGainSink implements GainSink {

    @Override
    public boolean apply(Gains gains) {
      // Returning false rather than true is the honest answer: a sink that accepts a gain and does
      // not write it is the exact failure GainSink exists to delete.
      return false;
    }

    @Override
    public String describeConversion() {
      return "open loop: no gains are applied to this mechanism, so nothing is converted.";
    }
  }

  /**
   * A frame-counted debounce, so a level that latches on the robot latches on the same frame under
   * replay.
   */
  private static final class Debounce {

    private final double m_seconds;
    private double m_held;
    private boolean m_state;

    Debounce(double seconds) {
      m_seconds = Double.isFinite(seconds) && seconds > 0.0 ? seconds : 0.0;
    }

    boolean calculate(boolean input) {
      if (!input) {
        m_held = 0.0;
        m_state = false;
        return false;
      }
      double dt = Clock.dt();
      m_held += Double.isFinite(dt) && dt > 0.0 ? dt : 0.02;
      if (m_held >= m_seconds) {
        m_state = true;
      }
      return m_state;
    }
  }
}

package org.pumpkinlib.hardware.sim;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.system.plant.DCMotor;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.spi.MechanismGeometry;
import org.pumpkinlib.core.spi.SimMotorHandle;
import org.pumpkinlib.hardware.MotorCapabilities;
import org.pumpkinlib.hardware.MotorIO;
import org.pumpkinlib.hardware.MotorInputs;
import org.pumpkinlib.hardware.RioControlLoop;
import org.pumpkinlib.hardware.VelocityCarrier;
import org.pumpkinlib.units.MechanismUnits;

/**
 * A complete {@link MotorIO} backed by a motor-curve physics plant and an internal closed loop, so
 * that <b>every mechanism has physics with no extra team code</b>.
 *
 * <h2>What this is for, and what it is NOT for</h2>
 *
 * <p>This is the backend for a machine with no vendor libraries installed at all, and for the
 * cross-backend parity tests. It is deliberately <b>not</b> how the Phoenix and REV backends
 * simulate: those drive their own vendor sim state, so that simulation exercises the <i>real</i>
 * config path including {@code SensorToMechanismRatio} and Motion Magic. A simulation that bypasses
 * the device's own conversion tests the simulation, not the robot.
 *
 * <h2>It runs the real ratio path on purpose</h2>
 *
 * <p>The plant is integrated in <b>rotor</b> space and read out in <b>output-shaft</b> space, with
 * the reduction applied at exactly the two places a real device applies it. So a mis-entered gear
 * ratio produces an elevator that moves at the wrong speed <i>in {@code simulateJava}</i>, on a
 * laptop, in week two — rather than at the first event. That is the whole reason this class does its
 * own integration instead of handing the numbers to a black box.
 *
 * <h2>The setpoint LATCHES</h2>
 *
 * <p>A goal set once keeps being driven every step until something else is commanded, exactly like a
 * real Phoenix or REV controller. A backend that dropped the goal when {@code setPositionGoal} was
 * not re-called every loop would let a mechanism fall the first time a loop overran — which is the
 * "original hold-position bug" a surveyed team keeps a dedicated regression test for.
 *
 * <h2>The plant is as good as what you declared</h2>
 *
 * <p>Given a {@link MechanismGeometry} it models the real mass or moment of inertia, real gravity,
 * and the real soft limits. Given only a {@link MotorSpec.SimSpec} it substitutes a documented
 * default inertia — see {@link #kDefaultSpinUpSeconds} — and says so in {@link #describe()}. It
 * never silently pretends to know a mass nobody typed.
 */
public final class SimMotorIO implements MotorIO {

  /** Standard gravity, m/s^2. */
  public static final double kGravityMetersPerSecondSquared = 9.80665;

  /** Nominal simulated bus voltage, volts. */
  public static final double kNominalBusVolts = 12.0;

  /**
   * The time the default plant takes to reach free speed under stall torque, in seconds.
   *
   * <p>With no declared mass or moment of inertia there is no honest number, so the inertia is
   * chosen to make the mechanism spin up in this long: fast enough to be usable, slow enough that a
   * student can see the profile do its job. {@link #describe()} prints that the value was invented
   * rather than measured.
   */
  public static final double kDefaultSpinUpSeconds = 0.35;

  /** What the IO was last told to do. Latching, exactly like a real smart controller. */
  private enum Mode {
    NEUTRAL,
    VOLTAGE,
    DUTY_CYCLE,
    POSITION,
    VELOCITY
  }

  private final String m_name;
  private final MotorSpec.SimSpec m_spec;
  private final MechanismKind m_kind;
  private final MechanismUnits m_units;
  private final MechanismGeometry m_geometry;
  private final DCMotor m_gearbox;
  private final RioControlLoop m_loop;
  private final MotorCapabilities m_capabilities;

  private final double m_rotorPerOutput;
  private final double m_siPerOutputRotation;
  private final double m_horizontalReferenceSi;
  private final double m_inertiaKgM2;
  private final double m_minRot;
  private final double m_maxRot;

  private Mode m_mode = Mode.NEUTRAL;
  private NeutralMode m_neutralMode;
  private double m_goalRot;
  private double m_goalRps;
  private double m_goalRps2;
  private double m_arbFeedforwardVolts;
  private double m_commandedVolts;
  private double m_appliedVolts;
  private double m_statorAmps;
  private double m_outputRot;
  private double m_outputRps;
  private boolean m_externallyDriven;

  /**
   * The form the backend factory calls: physics from the motor curve and the reduction, with a
   * default inertia and no gravity.
   *
   * @param spec the declared simulated motor
   * @param units the mechanism's unit conversion object — the reduction this plant actually runs
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public SimMotorIO(
      MotorSpec.SimSpec spec, MechanismUnits units, ControlConfig control, MechanismKind kind) {
    this(spec, units, control, kind, null);
  }

  /**
   * The full form: a real plant, from the geometry the mechanism already declared.
   *
   * @param spec the declared simulated motor
   * @param units the mechanism's unit conversion object
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param geometry the declared plant — mass or moment of inertia, travel limits, gravity; null
   *     falls back to the default inertia and no limits
   */
  public SimMotorIO(
      MotorSpec.SimSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      MechanismGeometry geometry) {
    m_spec = Objects.requireNonNull(spec, "SimMotorIO: spec must not be null");
    m_units = Objects.requireNonNull(units, "SimMotorIO: units must not be null");
    Objects.requireNonNull(control, "SimMotorIO: control must not be null");
    m_kind = kind == null ? MechanismKind.SIMPLE : kind;
    m_geometry = geometry;
    m_name = spec.name();
    m_neutralMode = control.neutralMode();
    // spec.inverted() is deliberately NOT applied here. A real device inverts the sensor and the
    // output together, so the mechanism sees no sign change at all; inverting only the output would
    // turn this plant's closed loop into positive feedback and simulate a mechanism that runs away.

    m_gearbox = geometry != null ? geometry.gearbox() : spec.model().dcMotor(1);
    m_rotorPerOutput = units.rotorPerOutput();
    m_siPerOutputRotation = units.siPerOutputRotation();
    m_horizontalReferenceSi = units.horizontalReferenceSi();
    m_inertiaKgM2 = inertiaFor(geometry, m_gearbox, m_rotorPerOutput);

    if (geometry != null && Double.isFinite(geometry.siMin()) && Double.isFinite(geometry.siMax())) {
      m_minRot = geometry.siMin() / m_siPerOutputRotation;
      m_maxRot = geometry.siMax() / m_siPerOutputRotation;
      m_outputRot =
          Double.isFinite(geometry.siStart()) ? geometry.siStart() / m_siPerOutputRotation : m_minRot;
    } else {
      m_minRot = Double.NEGATIVE_INFINITY;
      m_maxRot = Double.POSITIVE_INFINITY;
      m_outputRot = 0.0;
    }

    m_loop = new RioControlLoop(units, control, Clock.dt());
    m_capabilities =
        MotorCapabilities.builder()
            .onBoardPositionLoop(true)
            .onBoardVelocityLoop(true)
            .onBoardProfile(true)
            .dynamicProfile(true)
            .onBoardGravityFeedforward(true)
            .onBoardCosineGravity(true)
            .arbitraryFeedforward(true)
            .positionGoalVelocity(VelocityCarrier.NATIVE_REQUEST_FIELD)
            .readsTorqueCurrent(true)
            .reportsConnectionHealth(true)
            .build();
  }

  // ---- MotorIO ---------------------------------------------------------------------------------

  /**
   * Steps the plant by one {@link Clock#dt()} and then fills {@code inputs}.
   *
   * <p>Stepping here rather than in a separate {@code simulationPeriodic} is deliberate: a mechanism
   * calls {@code updateInputs} at the top of every loop whether or not it remembers to call anything
   * else, so a simulated mechanism cannot be built that silently never moves.
   *
   * @param inputs the inputs object to fill
   */
  @Override
  public void updateInputs(MotorInputs inputs) {
    step(Clock.dt());
    if (inputs == null) {
      return;
    }
    inputs.connected = true;
    inputs.positionRot = m_outputRot;
    inputs.velocityRps = m_outputRps;
    inputs.appliedVolts = m_appliedVolts;
    inputs.statorCurrentAmps = m_statorAmps;
    // Supply current is the stator current scaled by duty cycle, which is the same relationship a
    // real device reports and the reason the two are never interchangeable in a brownout model.
    inputs.supplyCurrentAmps = m_statorAmps * Math.abs(m_appliedVolts) / kNominalBusVolts;
    inputs.torqueCurrentAmps = m_statorAmps;
    // A simulated motor has no thermal model, and inventing one would let a team tune against a
    // temperature that does not exist.
    inputs.temperatureCelsius = Double.NaN;
    inputs.forwardLimitTripped = false;
    inputs.forwardLimitValid = false;
    inputs.reverseLimitTripped = false;
    inputs.reverseLimitValid = false;
    inputs.closedLoopReferenceRot =
        m_mode == Mode.POSITION ? m_loop.referenceRot() : Double.NaN;
  }

  @Override
  public void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts) {
    if (m_mode != Mode.POSITION) {
      m_loop.reset(m_outputRot, m_outputRps);
    }
    m_mode = Mode.POSITION;
    m_goalRot = outputRotations;
    m_goalRps = outputRotationsPerSecond;
    m_arbFeedforwardVolts = arbFeedforwardVolts;
  }

  @Override
  public void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override) {
    m_loop.applyConstraints(override);
    setPositionGoal(outputRotations, outputRotationsPerSecond, arbFeedforwardVolts);
  }

  @Override
  public void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts) {
    m_mode = Mode.VELOCITY;
    m_goalRps = outputRps;
    m_goalRps2 = outputRps2;
    m_arbFeedforwardVolts = arbFeedforwardVolts;
  }

  @Override
  public void setVoltage(double volts) {
    m_mode = Mode.VOLTAGE;
    m_commandedVolts = Double.isFinite(volts) ? volts : 0.0;
  }

  @Override
  public void setDutyCycle(double fraction) {
    m_mode = Mode.DUTY_CYCLE;
    double clamped = MathUtil.clamp(Double.isFinite(fraction) ? fraction : 0.0, -1.0, 1.0);
    m_commandedVolts = clamped * kNominalBusVolts;
  }

  @Override
  public void setNeutral() {
    m_mode = Mode.NEUTRAL;
    m_commandedVolts = 0.0;
  }

  @Override
  public void applyGains(Gains siGains) {
    m_loop.applyGains(siGains);
  }

  @Override
  public void applyConstraints(MotionConstraints constraints) {
    m_loop.applyConstraints(constraints);
  }

  @Override
  public void setNeutralMode(NeutralMode mode) {
    if (mode != null) {
      m_neutralMode = mode;
    }
  }

  @Override
  public void seedPosition(double outputRotations) {
    if (!Double.isFinite(outputRotations)) {
      return;
    }
    m_outputRot = outputRotations;
    m_loop.reset(m_outputRot, m_outputRps);
  }

  @Override
  public void reapplyFullConfigBlocking() {
    // A simulated device cannot lose its configuration, so there is nothing to re-apply. It is not
    // a no-op by accident: reportsDeviceReset() is false, which is how a caller knows.
    m_loop.reset(m_outputRot, m_outputRps);
  }

  @Override
  public Optional<SimMotorHandle> simHandle() {
    return Optional.of(new Handle());
  }

  @Override
  public MotorCapabilities capabilities() {
    return m_capabilities;
  }

  @Override
  public String name() {
    return m_name;
  }

  @Override
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s (%s, %s)%n  plant: %s%n  gearing: %.4f rotor rotations per output rotation, "
            + "%.6f %s per output rotation%n  travel: [%.4f, %.4f] output rotations%n  %s"
            + "%n  capabilities: %s",
        m_name,
        m_spec.describe(),
        m_kind.describe(),
        m_geometry != null
            ? m_geometry.describe()
            : String.format(
                Locale.ROOT,
                "DEFAULT inertia %.6f kg*m^2, INVENTED from a %.2f s spin-up (no MechanismGeometry "
                    + "was supplied, so no mass or moment of inertia was declared) -- gravity is "
                    + "NOT modelled",
                m_inertiaKgM2,
                kDefaultSpinUpSeconds),
        m_rotorPerOutput,
        m_siPerOutputRotation,
        m_units.siLabel(),
        m_minRot,
        m_maxRot,
        m_loop.describe(),
        m_capabilities.describe());
  }

  // ---- inspection ------------------------------------------------------------------------------

  /**
   * The simulated position, for a test that wants to assert on the plant rather than on the log.
   *
   * @return output-shaft rotations
   */
  public double simulatedOutputRotations() {
    return m_outputRot;
  }

  /**
   * The simulated velocity.
   *
   * @return output-shaft rotations per second
   */
  public double simulatedOutputRps() {
    return m_outputRps;
  }

  /**
   * The moment of inertia this plant is actually integrating, referred to the output shaft.
   *
   * @return kilogram metres squared
   */
  public double inertiaKgM2() {
    return m_inertiaKgM2;
  }

  /**
   * The roboRIO-side loop, so tuning and tests can read back gains and the profile reference.
   *
   * @return the live loop
   */
  public RioControlLoop loop() {
    return m_loop;
  }

  // ---- physics ---------------------------------------------------------------------------------

  private void step(double dtSeconds) {
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : 0.02;
    m_commandedVolts = commandedVoltsFor(dt);
    m_appliedVolts = MathUtil.clamp(m_commandedVolts, -kNominalBusVolts, kNominalBusVolts);

    if (m_externallyDriven) {
      // An external simulator owns the plant through simHandle(); we still report the volts and the
      // current so the battery model can charge for them, but we do not integrate twice.
      m_statorAmps = m_gearbox.getCurrent(rotorRadPerSec(), m_appliedVolts);
      return;
    }

    double current = m_gearbox.getCurrent(rotorRadPerSec(), m_appliedVolts);
    m_statorAmps = current;

    // Torque is produced at the ROTOR and multiplied by the reduction on its way to the output
    // shaft. That multiply is the same number a real device carries in SensorToMechanismRatio, so a
    // wrong ratio is wrong here in exactly the way it is wrong on the robot.
    double outputTorque = m_gearbox.getTorque(current) * m_rotorPerOutput;
    double alphaRadPerSec2 = (outputTorque - gravityTorque()) / m_inertiaKgM2;
    double accelRps2 = alphaRadPerSec2 / (2.0 * Math.PI);

    m_outputRps += accelRps2 * dt;
    m_outputRot += m_outputRps * dt;

    if (m_outputRot <= m_minRot) {
      m_outputRot = m_minRot;
      m_outputRps = Math.max(0.0, m_outputRps);
    } else if (m_outputRot >= m_maxRot) {
      m_outputRot = m_maxRot;
      m_outputRps = Math.min(0.0, m_outputRps);
    }
    if (m_mode == Mode.NEUTRAL && m_neutralMode == NeutralMode.BRAKE) {
      // Brake mode shorts the windings; the motor curve already opposes motion, and this makes the
      // remaining coast finite instead of asymptotic.
      m_outputRps *= 0.5;
    }
  }

  private double commandedVoltsFor(double dt) {
    return switch (m_mode) {
      case POSITION ->
          m_loop.positionVolts(
              m_outputRot, m_outputRps, m_goalRot, m_goalRps, m_arbFeedforwardVolts, dt);
      case VELOCITY ->
          m_loop.velocityVolts(m_outputRps, m_goalRps, m_goalRps2, m_arbFeedforwardVolts, dt);
      case NEUTRAL -> 0.0;
      case VOLTAGE, DUTY_CYCLE -> m_commandedVolts;
    };
  }

  private double rotorRadPerSec() {
    return m_outputRps * m_rotorPerOutput * 2.0 * Math.PI;
  }

  private double gravityTorque() {
    if (m_geometry == null || !m_geometry.simulateGravity()) {
      return 0.0;
    }
    double siPosition = m_outputRot * m_siPerOutputRotation;
    return switch (m_geometry.kind()) {
      case LINEAR ->
          finiteOrZero(
              m_geometry.massKg()
                  * kGravityMetersPerSecondSquared
                  * m_geometry.effectiveRadiusMeters());
      case ROTARY ->
          finiteOrZero(
              m_geometry.massKg()
                  * kGravityMetersPerSecondSquared
                  * m_geometry.armLengthMeters()
                  * Math.cos(siPosition - m_horizontalReferenceSi));
      case FLYWHEEL, SIMPLE -> 0.0;
    };
  }

  private static double finiteOrZero(double value) {
    return Double.isFinite(value) ? value : 0.0;
  }

  private static double inertiaFor(
      MechanismGeometry geometry, DCMotor gearbox, double rotorPerOutput) {
    if (geometry != null) {
      double declared =
          switch (geometry.kind()) {
            case LINEAR -> geometry.massKg() * square(geometry.effectiveRadiusMeters());
            case ROTARY ->
                Double.isFinite(geometry.momentOfInertiaKgM2())
                    ? geometry.momentOfInertiaKgM2()
                    : geometry.massKg() * square(geometry.armLengthMeters());
            case FLYWHEEL, SIMPLE -> geometry.momentOfInertiaKgM2();
          };
      if (Double.isFinite(declared) && declared > 0.0) {
        return declared;
      }
    }
    // No declared plant: pick the inertia that makes stall torque reach free speed in
    // kDefaultSpinUpSeconds. Both quantities are referred to the OUTPUT shaft, so the reduction
    // appears twice and cancels once -- which is exactly the arithmetic a hand-written sim gets
    // wrong.
    double stallTorqueAtOutput = gearbox.stallTorqueNewtonMeters * rotorPerOutput;
    double freeSpeedAtOutput = gearbox.freeSpeedRadPerSec / rotorPerOutput;
    double j = stallTorqueAtOutput * kDefaultSpinUpSeconds / freeSpeedAtOutput;
    return Double.isFinite(j) && j > 0.0 ? j : 0.001;
  }

  private static double square(double v) {
    return v * v;
  }

  /**
   * The {@link SimMotorHandle} an external simulator drives this backend through.
   *
   * <p>Handing over the plant is explicit: the first {@link #setRotorPosition(double, double)} call
   * switches this IO out of self-integration, so the internal plant and an external one can never
   * both be moving the same mechanism.
   */
  private final class Handle implements SimMotorHandle {

    @Override
    public double appliedVolts(double busVoltage) {
      double bus = Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : kNominalBusVolts;
      return MathUtil.clamp(m_appliedVolts, -bus, bus);
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      m_externallyDriven = true;
      // Rotor in, output out -- through the SAME reduction the device applies. Writing output units
      // here would skip the one conversion most likely to be misconfigured, which is the entire
      // point of the handle being at the device rather than at the plant.
      if (Double.isFinite(rotorRotations) && m_rotorPerOutput != 0.0) {
        m_outputRot = rotorRotations / m_rotorPerOutput;
      }
      if (Double.isFinite(rotorRps) && m_rotorPerOutput != 0.0) {
        m_outputRps = rotorRps / m_rotorPerOutput;
      }
    }

    @Override
    public double statorAmps() {
      return m_statorAmps;
    }
  }
}

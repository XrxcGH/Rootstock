package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.HomingStrategy;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.PositionConfig;
import org.rootstock.config.SensorSpec;
import org.rootstock.config.Setpoint;
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
import org.rootstock.hardware.AbsoluteEncoderIO;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorIOFactory;
import org.rootstock.hardware.VelocityCarrier;
import org.rootstock.pure.math.RootstockMath;
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.schema.ControlMode;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.Range;
import org.rootstock.units.SiDomain;

/**
 * Everything whose controlled quantity is a <b>position</b>: elevator, arm, pivot, wrist, turret,
 * hood, climber.
 *
 * <h2>Goal, setpoint and measured are three different things</h2>
 *
 * <p>{@link #goal()} is where the mechanism has been told to end up. {@link #setpoint()} is where
 * the controller is being told to be <i>right now</i> — equal to the goal for the two on-motor
 * locations, because the device owns the profile and the roboRIO cannot see inside it; equal to the
 * profile step for the two roboRIO locations. {@link #measured()} is where it actually is. Collapsing
 * any two of those into one is how a profiled move looks like a tracking catastrophe on the plot the
 * tuning wizard uses to judge tracking, which is why {@code Error} is derived from the setpoint and
 * {@code GoalError} from the goal, and why both are always published.
 *
 * <h2>All four control locations, and only one of them converts units</h2>
 *
 * <p>{@link ControlLocation#ON_MOTOR_PROFILED} and {@link ControlLocation#ON_MOTOR_DIRECT} hand the
 * goal to the device in output-shaft rotations and let it run its own 1 kHz loop.
 * {@link ControlLocation#RIO_PROFILE_MOTOR_LOOP} steps a profile here and hands each step to the
 * device's position loop. {@link ControlLocation#RIO_FULL} is <b>the one place in this class where SI
 * conversion happens</b>: the profile, the PID, the feedforward and the clamp are all in metres or
 * radians, and only the telemetry converts back. That is not a stylistic choice — gains are
 * volts-per-SI, and running a V/rad kP against an error expressed in degrees is wrong by 57.3&times;
 * in a way that looks entirely plausible until an arm hits something.
 *
 * <h2>Untuned gains refuse rather than guess — on hardware</h2>
 *
 * <p>{@link Gains#UNTUNED} carries a NaN kP. On a real robot this mechanism refuses closed-loop
 * control entirely, holds neutral and raises a named alert telling the student to run the tuning
 * wizard; manual control and homing still work, so the robot is not bricked. In simulation the same
 * placeholder resolves at construction to a first guess derived from the declared {@link PlantPrior}
 * — kV and kA from the motor curve, kG from the declared mass — so the demo moves on the first run,
 * and the boot dump says out loud that those numbers were derived and not measured.
 *
 * <h2>Two layers of limit, and both are reported</h2>
 *
 * <p>Every commanded goal is clamped in Java against the configured soft limits, and a clamp raises
 * {@code <name>/soft-limit-clamped-setpoint} naming the requested and the applied value — so "why
 * won't it go all the way up" answers itself. Open-loop and manual commands that push past a soft
 * limit are zeroed and publish {@code OpenLoopClamped}, so a student pushing the stick sees a key go
 * true instead of concluding the stick stopped working. A hard limit switch asserting <b>captures</b>
 * the current position as the goal and holds it, so the mechanism stops fighting the stop instead of
 * grinding against it.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>Architecture rule 11, and {@link Mechanism#periodic()}'s {@code catch (Throwable)} is the
 * backstop rather than the plan. Unknown setpoint names, refused commands and failed homing are all
 * named alerts and named refusals, never exceptions and never silent no-ops.
 */
public final class PositionMechanism extends Mechanism
    implements org.rootstock.config.Validation.ConfigCarrier {

  /** The voltage ceiling the roboRIO-side loop clamps its own output to. */
  public static final double kMaxOutputVolts = 12.0;

  /** The relative telemetry key carrying the feedback-only volts of a {@code RIO_FULL} loop. */
  public static final String kFeedbackVoltsKey = "FeedbackVolts";

  /** The relative telemetry key carrying the feedforward-only volts of a {@code RIO_FULL} loop. */
  public static final String kFeedforwardVoltsKey = "FeedforwardVolts";

  /** The relative telemetry key that is true when a manual command was zeroed by a soft limit. */
  public static final String kOpenLoopClampedKey = "OpenLoopClamped";

  /** The relative telemetry key naming which constraint profile is in force. */
  public static final String kActiveProfileKey = "ActiveProfile";

  /** The relative telemetry key prefix every homing signal is published under. */
  public static final String kHomingPrefix = "Homing/";

  /** The tracer section the live-tuning gain push is budgeted under. */
  public static final String kApplyGainsSection = "Mechanism/ApplyGains";

  /** The fraction of travel used as the tuning soft margin when none is declared. */
  public static final double kTuningMarginFraction = 0.05;

  /** The closed-loop bandwidth, in hertz, the simulation-only gain guess is designed for. */
  public static final double kSimGuessBandwidthHz = 1.5;

  /** One constraint profile: a name, the constraints it installs, and when it applies. */
  private record Profile(String name, MotionConstraints constraints, BooleanSupplier when) {}

  private final PositionConfig m_config;

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
  private final Range m_travel;
  private final Map<String, Setpoint> m_setpoints;
  private final MotionConstraints m_baseConstraints;
  private final List<Profile> m_profiles = new ArrayList<>();
  private final ManualControl m_manual;
  private final ContinuousUnwrap m_unwrap;
  private final HomingRunner m_homing;
  private final GainSink m_gainSink;
  private final PlantPrior m_prior;
  private final TravelLimits m_travelLimits;
  private final MechanismArchetype m_archetype;
  private final double m_toleranceUser;
  private final double m_velocityToleranceUser;
  private final double m_siHorizontalReference;
  private final Debounce m_goalDebounce;

  private final PIDController m_siPid;
  private final Controllers.Feedforward m_ff;

  private final RootstockAlert m_softLimitClamped;
  private final RootstockAlert m_unknownSetpoint;
  private final RootstockAlert m_untunedGains;
  private final RootstockAlert m_refusedInSafeMode;
  private final RootstockAlert m_derivedGains;
  private final RootstockAlert m_characterizationRefused;

  private final AbsoluteEncoderIO.AbsoluteEncoderInputs m_absoluteInputs =
      new AbsoluteEncoderIO.AbsoluteEncoderInputs();

  /** Precomputed, because concatenating it in {@code periodic()} allocates 50 strings a second. */
  private final String m_absoluteInputsKey;

  private Gains m_gains;
  private MotionConstraints m_activeConstraints;
  private String m_activeProfileName = "base";
  private TrapezoidProfile m_siProfile;
  private MotionConstraints m_siProfileBuiltFrom;
  private TrapezoidProfile.State m_siProfileState = new TrapezoidProfile.State();
  private double m_kVDevice;

  private double m_goal;
  private double m_setpointUser;
  private double m_setpointVelUser;
  private double m_measured;
  private double m_measuredVel;
  private double m_outputVolts;
  private double m_feedbackVolts;
  private double m_feedforwardVolts;
  private double m_openLoopVolts;
  private boolean m_openLoopClamped;
  private boolean m_profileFinished = true;
  private boolean m_atGoal;
  private boolean m_atSetpoint;
  private boolean m_goalIsFresh = true;
  private boolean m_bootHomingAttempted;
  private boolean m_latchGoalAfterHoming;
  private long m_lastResetCount;

  private AbsoluteEncoderIO m_absolute;
  private DoubleSupplier m_fieldLockHeadingDeg;
  private DoubleSupplier m_fieldLockYawRateRadPerSec;
  private boolean m_fieldLocked;
  private double m_fieldLockGoalDeg;

  private Trigger m_atGoalTrigger;
  private Trigger m_homedTrigger;

  /**
   * Builds a position mechanism and the backend its {@link org.rootstock.config.MotorSpec} names.
   *
   * @param config the declared mechanism, already validated into collected errors
   * @throws NullPointerException if {@code config} is null
   */
  public PositionMechanism(PositionConfig config) {
    this(config, backendFor(config));
  }

  /**
   * Builds a position mechanism against a supplied IO — the seam a unit test injects through.
   *
   * <p>The two-argument form is not a test-only back door: it is also how a team wires a backend
   * Rootstock does not ship, and how a mechanism is built against {@code NoOpMotorIO} deliberately.
   *
   * @param config the declared mechanism
   * @param io the hardware seam to command
   * @throws NullPointerException if {@code config} or {@code io} is null
   */
  public PositionMechanism(PositionConfig config, MotorIO io) {
    super(
        config == null ? null : config.name(),
        config == null ? null : MechanismUnits.of(config.reduction(), config.axis()),
        io,
        config == null ? null : config.motors(),
        buildGeometry(config));
    m_config = Objects.requireNonNull(config, "PositionMechanism: config must not be null");
    m_control = m_config.control();
    m_location = m_io.capabilities().downgrade(m_control.location());
    m_travel = m_config.travel();
    m_baseConstraints = m_control.constraints();
    m_activeConstraints = m_baseConstraints;
    m_toleranceUser = resolveTolerance(m_control, m_config.isLinear());
    m_velocityToleranceUser =
        Double.isFinite(m_control.velocityTolerance()) && m_control.velocityTolerance() > 0.0
            ? m_control.velocityTolerance()
            : Double.POSITIVE_INFINITY;
    m_goalDebounce = new Debounce(m_control.goalDebounceSeconds());
    m_siHorizontalReference = m_units.horizontalReferenceSi();

    Map<String, Setpoint> setpoints = new LinkedHashMap<>();
    for (Setpoint setpoint : m_config.setpoints()) {
      setpoints.put(setpoint.name(), setpoint);
    }
    m_setpoints = Map.copyOf(setpoints);

    m_manual = ManualControl.of(m_control);
    m_unwrap =
        !m_config.isLinear() && ContinuousUnwrap.appliesTo(m_travel.min(), m_travel.max())
            ? new ContinuousUnwrap(m_travel.min(), m_travel.max())
            : null;

    m_archetype = archetypeOf(m_config);
    m_prior = priorFor(m_config);
    m_travelLimits = travelLimitsFor(m_units, m_travel);

    m_softLimitClamped =
        Alerts.warning(
            m_config.name(), m_config.name() + "/soft-limit-clamped-setpoint", MatchImpact.PIT_ONLY);
    m_unknownSetpoint =
        Alerts.error(m_config.name(), m_config.name() + "/unknown-setpoint", MatchImpact.PIT_ONLY)
            .sticky(true);
    m_untunedGains =
        Alerts.error(m_config.name(), m_config.name() + "/gains-untuned", MatchImpact.BLOCKS_MATCH)
            .sticky(true);
    m_refusedInSafeMode =
        Alerts.warning(
            m_config.name(), m_config.name() + "/refused-in-safe-mode", MatchImpact.PIT_ONLY);
    m_derivedGains =
        Alerts.warning(
                m_config.name(), m_config.name() + "/gains-derived-not-measured", MatchImpact.PIT_ONLY)
            .sticky(true);
    m_characterizationRefused =
        Alerts.warning(
            m_config.name(), m_config.name() + "/characterization-refused", MatchImpact.PIT_ONLY);

    m_gains = resolveGains(m_control.gains(), m_prior, m_control.gravity(), m_derivedGains, m_name);
    m_kVDevice = m_gains.kV() * m_units.siPerOutputRotation();
    m_siPid =
        Controllers.pid(
            m_gains, m_config.axis().isContinuous(), m_control.toleranceSi(), 0.0, Clock.dt());
    m_ff = Controllers.feedforward(m_control.gravity(), m_gains, Clock.dt());
    m_siProfile = buildSiProfile(m_baseConstraints);
    m_siProfileBuiltFrom = m_baseConstraints;

    m_goal = m_travel.clamp(0.0);
    m_setpointUser = m_goal;
    m_absoluteInputsKey = kPrefix + "/AbsoluteInputs";

    m_homing =
        new HomingRunner(
            m_config.name(),
            m_units,
            m_io,
            m_inputs,
            m_config.homing(),
            new Environment(),
            m_config.limits().followerToleranceRot());

    // The 60 A default can never be reached by a mechanism limited to 40 A, so without this the
    // stall protection is silently inert. Same argument for the thermal threshold.
    setStallThresholds(
        m_config.limits().current().stator().in(Amps) * 0.9, kDefaultStallVelocityRps);
    setOverTemperatureCelsius(m_config.limits().overTempCelsius());

    m_io.applyGains(m_gains);
    m_io.applyConstraints(m_baseConstraints);
    m_io.setNeutralMode(m_control.neutralMode());
    m_gainSink = new PositionGainSink();
    m_lastResetCount = m_inputs.deviceResetCount;
  }

  // ===============================================================================================
  // Position — goal, setpoint, measured
  // ===============================================================================================

  /**
   * Where this mechanism has been told to end up, in user units.
   *
   * @return metres or degrees
   */
  public double goal() {
    return m_goal;
  }

  /**
   * Where the controller is being told to be right now, in user units.
   *
   * <p>Equal to {@link #goal()} for the on-motor locations, because the device owns the profile and
   * its internal reference is not visible from here. Equal to this cycle's profile step for the two
   * roboRIO locations.
   *
   * @return metres or degrees
   */
  public double setpoint() {
    return m_setpointUser;
  }

  /**
   * This cycle's profile velocity, in user units per second.
   *
   * @return metres or degrees per second
   */
  public double setpointVelocity() {
    return m_setpointVelUser;
  }

  /**
   * Where the mechanism actually is, in user units.
   *
   * @return metres or degrees, or NaN when position is not a subscribed signal
   */
  public double measured() {
    return m_measured;
  }

  /**
   * How fast the mechanism is actually moving, in user units per second.
   *
   * @return metres or degrees per second
   */
  public double measuredVelocity() {
    return m_measuredVel;
  }

  /**
   * The measured position as a typed {@link Distance}, for a linear mechanism only.
   *
   * @return the distance, or empty for a rotary axis — the unit is not a matter of opinion
   */
  public Optional<Distance> measuredDistance() {
    return m_units.siDomain() == SiDomain.LINEAR_METERS
        ? Optional.of(Meters.of(m_measured))
        : Optional.empty();
  }

  /**
   * The measured position as a typed {@link Angle}, for a rotary mechanism only.
   *
   * @return the angle, or empty for a linear axis
   */
  public Optional<Angle> measuredAngle() {
    return m_units.siDomain() == SiDomain.LINEAR_METERS
        ? Optional.empty()
        : Optional.of(Degrees.of(m_measured));
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
   * Command a goal in user units, clamped to the soft limits.
   *
   * <p>A clamp is never silent: it raises {@code <name>/soft-limit-clamped-setpoint} naming the
   * requested and the applied value. On a rotary axis with more than one turn of travel the request
   * is first resolved through {@link ContinuousUnwrap}, so a 350&deg; request from a 10&deg;
   * measurement takes the 20&deg; path rather than the 340&deg; one.
   *
   * @param userUnits the goal, in metres or degrees
   */
  public void setGoal(double userUnits) {
    if (safeMode()) {
      refuseInSafeMode("setGoal");
      return;
    }
    if (Double.isNaN(userUnits)) {
      return;
    }
    double requested = m_unwrap == null ? userUnits : m_unwrap.unwrap(userUnits);
    double applied = m_travel.clamp(requested);
    if (Math.abs(applied - requested) > 1e-9) {
      m_softLimitClamped
          .text(
              String.format(
                  Locale.ROOT,
                  "%s/soft-limit-clamped-setpoint: %.4f %s was requested but the soft limits are "
                      + "[%.4f, %.4f] %s, so %.4f %s was applied instead. Expected a goal inside "
                      + "travel. Fix: change the goal, or widen .softLimits(...) if the mechanism "
                      + "really does travel that far.",
                  m_name,
                  requested,
                  m_units.unitLabel(),
                  m_travel.min(),
                  m_travel.max(),
                  m_units.unitLabel(),
                  applied,
                  m_units.unitLabel()))
          .set(true);
    } else {
      m_softLimitClamped.set(false);
    }
    if (m_unwrap != null) {
      m_unwrap.seed(applied);
    }
    m_goal = applied;
    m_goalIsFresh = true;
    m_profileFinished = false;
    m_goalDebounce.reset();
    if (mode() == MechanismMode.HOMING) {
      m_homing.cancel(HomingRunner.AbortReason.INTERRUPTED);
    }
    if (mode() != MechanismMode.CLOSED_LOOP) {
      // Re-anchor the profile to where the mechanism actually is. A profile that resumes from a
      // stale internal setpoint commands a step, and a step on an elevator is a bang.
      m_siProfileState =
          new TrapezoidProfile.State(
              finiteOr(m_units.toSi(m_measured), 0.0),
              finiteOr(m_units.toSiPerSec(m_measuredVel), 0.0));
      m_siPid.reset();
    }
    m_manual.reset();
    setMode(MechanismMode.CLOSED_LOOP);
  }

  /**
   * Command a goal as a typed distance. A no-op on a rotary mechanism, with a named alert.
   *
   * @param goal the goal
   */
  public void setGoal(Distance goal) {
    if (goal == null) {
      return;
    }
    if (m_units.siDomain() != SiDomain.LINEAR_METERS) {
      wrongUnitKind("a Distance", "an Angle");
      return;
    }
    setGoal(goal.in(Meters));
  }

  /**
   * Command a goal as a typed angle. A no-op on a linear mechanism, with a named alert.
   *
   * @param goal the goal
   */
  public void setGoal(Angle goal) {
    if (goal == null) {
      return;
    }
    if (m_units.siDomain() == SiDomain.LINEAR_METERS) {
      wrongUnitKind("an Angle", "a Distance");
      return;
    }
    setGoal(goal.in(Degrees));
  }

  /**
   * Command a named goal. The typed path, and the documented default — the compiler checks it.
   *
   * @param named the setpoint, from {@code config.setpoint("L4")}
   */
  public void setGoal(Setpoint named) {
    if (named == null) {
      return;
    }
    setGoal(named.valueUser());
  }

  /**
   * Command a named goal by string — the escape hatch.
   *
   * <p>An unknown name never throws and never silently does nothing: it raises a sticky
   * {@code <name>/unknown-setpoint} alert naming the valid set, because a typo that produces silence
   * is a twenty-minute mystery and a typo that produces a sentence is a ten-second fix.
   *
   * @param setpointName the setpoint name, as declared in the config
   */
  public void setGoal(String setpointName) {
    Optional<Setpoint> found = setpoint(setpointName);
    if (found.isEmpty()) {
      m_unknownSetpoint
          .text(
              m_name
                  + "/unknown-setpoint: no setpoint named \""
                  + setpointName
                  + "\" is declared on this mechanism. Expected one of "
                  + m_setpoints.keySet()
                  + ". Fix: correct the spelling, or add "
                  + ".setpoint(\""
                  + setpointName
                  + "\", ...) to the config. Nothing was commanded.")
          .set(true);
      return;
    }
    setGoal(found.get());
  }

  /**
   * Look up a declared setpoint by name.
   *
   * @param setpointName the name
   * @return the setpoint, or empty when this mechanism declares none by that name
   */
  public Optional<Setpoint> setpoint(String setpointName) {
    return setpointName == null ? Optional.empty() : Optional.ofNullable(m_setpoints.get(setpointName));
  }

  /**
   * The names of every setpoint this mechanism declares, in declaration order.
   *
   * @return the names
   */
  public List<String> setpointNames() {
    return List.copyOf(m_setpoints.keySet());
  }

  /**
   * Latch the current measured position as the goal and hold it.
   *
   * <p>Deliberately a different verb from {@code goTo(STOW)}. A surveyed robot named its snap-to-zero
   * behaviour {@code maintainStateCommand()} and documented it as "re-apply current state"; it did
   * the opposite and slammed the turret to zero whenever a mode command ended. Rootstock keeps the
   * two verbs apart forever.
   */
  public void holdPosition() {
    if (safeMode()) {
      refuseInSafeMode("holdPosition");
      return;
    }
    setGoal(Double.isFinite(m_measured) ? m_measured : m_goal);
  }

  /**
   * Command a raw voltage with no loop closed around it, clamped against the soft limits.
   *
   * @param volts the voltage
   */
  @Override
  public void setVoltage(double volts) {
    if (safeMode()) {
      refuseInSafeMode("setVoltage");
      return;
    }
    if (mode() == MechanismMode.HOMING) {
      m_homing.cancel(HomingRunner.AbortReason.INTERRUPTED);
    }
    m_openLoopVolts = Double.isFinite(volts) ? volts : 0.0;
    setMode(MechanismMode.OPEN_LOOP);
  }

  /**
   * Command a duty cycle with no loop closed around it, clamped against the soft limits.
   *
   * @param fraction the commanded output, -1..1
   */
  public void setDutyCycle(double fraction) {
    setVoltage(RootstockMath.clamp(fraction, -1.0, 1.0) * kMaxOutputVolts);
  }

  /** Stop applying output, honouring the configured brake or coast behaviour. */
  public void setNeutral() {
    if (mode() == MechanismMode.HOMING) {
      m_homing.cancel(HomingRunner.AbortReason.INTERRUPTED);
    }
    m_openLoopVolts = 0.0;
    setMode(MechanismMode.NEUTRAL);
  }

  /**
   * Driver-stick control with deadband, scale, and capture-and-hold on release.
   *
   * <p>Call it exactly once per loop: {@link ManualControl#update(double)} consumes the release edge,
   * and the frame on which the stick returns to centre is the frame on which the current position
   * becomes the new goal. One implementation replaces the five hand-written copies and the hardcoded
   * 0.1/0.15 magic numbers found in the surveyed repositories.
   *
   * @param stickInput the raw axis value, -1..1
   */
  public void manualControl(double stickInput) {
    if (safeMode()) {
      refuseInSafeMode("manualControl");
      return;
    }
    if (mode() == MechanismMode.HOMING) {
      m_homing.cancel(HomingRunner.AbortReason.INTERRUPTED);
    }
    double shaped = m_manual.update(stickInput);
    if (m_manual.releasedThisCycle()) {
      // Capture and hold: the position the stick was released at becomes the goal, so the mechanism
      // does not sag back to wherever it was before the driver touched it.
      setGoal(Double.isFinite(m_measured) ? m_measured : m_goal);
      return;
    }
    if (m_manual.isActive()) {
      m_openLoopVolts = shaped * kMaxOutputVolts;
      setMode(MechanismMode.MANUAL);
    }
  }

  // ===============================================================================================
  // At-goal semantics
  // ===============================================================================================

  /**
   * Whether the mechanism has <b>arrived</b> at its goal — not merely passed through it.
   *
   * <p>Position within tolerance <b>and</b> velocity settled <b>and</b> the profile finished, held
   * for the configured debounce. Then four hard gates, each of which exists because a robot without
   * it lies: false while disabled, false in SAFE_MODE, false before homing completes when the
   * strategy needs motion, and false for at least one loop after {@link #setGoal(double)} so a
   * {@code waitUntil(atGoal)} cannot fall through instantly on a no-op move.
   *
   * @return true when the mechanism is at its goal
   */
  public boolean atGoal() {
    return m_atGoal;
  }

  /**
   * {@link #atGoal()} against a caller-supplied tolerance.
   *
   * <p>Undebounced and unlatched, because the debounce state belongs to the configured tolerance;
   * everything else — the velocity gate, the profile gate and the four hard gates — is identical.
   *
   * @param toleranceUserUnits the position tolerance, in metres or degrees
   * @return true when the mechanism is at its goal within that tolerance
   */
  public boolean atGoal(double toleranceUserUnits) {
    return atGoalGatesPass() && rawAtGoal(toleranceUserUnits);
  }

  /**
   * Whether the mechanism is tracking this cycle's profile step.
   *
   * <p>A separate, unlatched, undebounced predicate from {@link #atGoal()}: it measures tracking
   * quality, not arrival, and it is what an early-release supplier reads.
   *
   * @return true when within tolerance of the setpoint
   */
  public boolean atSetpoint() {
    return m_atSetpoint;
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

  /**
   * A trigger over {@link #atGoal(double)}.
   *
   * @param toleranceUserUnits the position tolerance, in metres or degrees
   * @return a new trigger; bind it once rather than building it in a loop
   */
  public Trigger atGoalTrigger(double toleranceUserUnits) {
    return new Trigger(() -> atGoal(toleranceUserUnits));
  }

  // ===============================================================================================
  // Homing
  // ===============================================================================================

  /**
   * Whether this mechanism's reported position corresponds to physical reality.
   *
   * @return true once a homing strategy has completed this power cycle
   */
  @Override
  public boolean isHomed() {
    return m_homing.isHomed();
  }

  /** Start the declared homing routine now, or refuse and say why. */
  public void beginHoming() {
    if (safeMode()) {
      refuseInSafeMode("home");
      return;
    }
    if (m_homing.begin(false)) {
      if (m_homing.isActive()) {
        setMode(MechanismMode.HOMING);
      }
    }
  }

  /**
   * A command that homes the mechanism and ends when the routine does.
   *
   * <p>Deliberately not {@code ignoringDisable}: homing moves a mechanism into a hard stop, and that
   * happens while enabled or not at all. Interrupting it commands neutral and leaves
   * {@link #isHomed()} false rather than half-true.
   *
   * @return a fresh command
   */
  public Command homeCommand() {
    if (safeMode()) {
      return refusal("home");
    }
    return Commands.runOnce(this::beginHoming, this)
        .andThen(Commands.idle(this).until(() -> !m_homing.isActive()))
        .finallyDo(
            interrupted -> {
              if (interrupted) {
                m_homing.cancel(HomingRunner.AbortReason.INTERRUPTED);
              }
              if (mode() == MechanismMode.HOMING) {
                setMode(MechanismMode.NEUTRAL);
              }
            })
        .withName(m_name + ".home");
  }

  /**
   * A trigger over {@link #isHomed()}, created once and cached.
   *
   * @return the trigger
   */
  public Trigger homed() {
    if (m_homedTrigger == null) {
      m_homedTrigger = new Trigger(this::isHomed);
    }
    return m_homedTrigger;
  }

  /**
   * The homing runtime, for a superstructure that needs the abort reason or the elapsed time.
   *
   * @return the runner; never null
   */
  public HomingRunner homing() {
    return m_homing;
  }

  /**
   * Attach an absolute encoder so {@link HomingStrategy.AbsoluteSeed} has something to seed from.
   *
   * <p>Core ships no factory that turns a {@code FeedbackSpec} into an {@link AbsoluteEncoderIO} —
   * every absolute source Rootstock models is a vendor device — so the vendor adapter or the team
   * hands one in here. Without it an {@code absoluteSeed} strategy fails with
   * {@link HomingRunner.AbortReason#NO_ABSOLUTE_SOURCE} and a composite falls through to its next
   * strategy, which is exactly the practice-bot/comp-bot case {@code firstOf(...)} exists for.
   *
   * @param encoder the absolute encoder seam, or null to detach
   */
  public void setAbsoluteEncoder(AbsoluteEncoderIO encoder) {
    m_absolute = encoder;
  }

  /**
   * Declare where the mechanism actually is, without running a routine.
   *
   * @param userUnits the true position, in metres or degrees
   */
  public void seedPosition(double userUnits) {
    m_homing.seedTo(userUnits);
    if (m_unwrap != null) {
      m_unwrap.seed(userUnits);
    }
    m_siProfileState =
        new TrapezoidProfile.State(m_units.toSi(userUnits), 0.0);
  }

  // ===============================================================================================
  // Field-locked azimuth (design/01 section 6.4)
  // ===============================================================================================

  /**
   * Turn this mechanism into a field-locked azimuth: it holds a field heading while the chassis
   * turns underneath it.
   *
   * <p>{@code design/01} §6.4 spells this as {@code RotaryAxis.turret(...).fieldLocked(gyro)}, but
   * {@code org.rootstock.units} as built carries no gyro reference — an axis is pure geometry and a
   * gyro is a device. So the wiring is done here instead, where the mechanism already owns a loop and
   * a goal. The counter-rotation term is what {@link MotorIO#setPositionGoal(double, double, double)}
   * has a velocity parameter for, and it is never silently dropped: a backend whose {@link
   * VelocityCarrier} is {@link VelocityCarrier#RIO_VOLTAGE_TRIM} receives it as volts instead.
   *
   * @param chassisHeadingDegrees the chassis yaw, degrees, same sign convention as the joint
   * @param chassisYawRateRadPerSec the chassis yaw rate, radians per second
   */
  public void enableFieldLock(
      DoubleSupplier chassisHeadingDegrees, DoubleSupplier chassisYawRateRadPerSec) {
    m_fieldLockHeadingDeg = chassisHeadingDegrees;
    m_fieldLockYawRateRadPerSec = chassisYawRateRadPerSec;
    m_fieldLocked = chassisHeadingDegrees != null && chassisYawRateRadPerSec != null;
  }

  /** Stop holding a field heading; the goal becomes an ordinary joint-frame goal again. */
  public void disableFieldLock() {
    m_fieldLocked = false;
  }

  /**
   * Command a goal expressed in the <b>field</b> frame.
   *
   * <p>Converted into the joint frame against the current chassis heading, then unwrapped and
   * clamped exactly like any other goal. A no-op with a named alert when field lock is not enabled,
   * because silently interpreting a field heading as a joint angle points the turret at the wrong
   * half of the field.
   *
   * @param fieldHeadingDegrees the heading to hold, in field-frame degrees
   */
  public void setFieldLockedGoal(double fieldHeadingDegrees) {
    if (!m_fieldLocked) {
      m_unknownSetpoint
          .text(
              m_name
                  + "/field-lock-not-enabled: setFieldLockedGoal("
                  + fieldHeadingDegrees
                  + ") was called but no gyro is attached. Expected enableFieldLock(headingDeg, "
                  + "yawRateRadPerSec) first. Nothing was commanded.")
          .set(true);
      return;
    }
    m_fieldLockGoalDeg = fieldHeadingDegrees;
    setGoal(
        ContinuousUnwrap.fieldLockedGoalDeg(
            fieldHeadingDegrees, m_fieldLockHeadingDeg.getAsDouble()));
  }

  // ===============================================================================================
  // Constraint profiles
  // ===============================================================================================

  /**
   * Add a named constraint profile that applies while {@code when} is true.
   *
   * <p>The fast/slow idea from a surveyed elevator, which moves gently while holding a game piece.
   * Selection is published as {@code ActiveProfile}, so "why is it slow right now" is a glance rather
   * than an investigation. Profiles are checked in declaration order and the first match wins.
   *
   * <p><b>Motion Magic cruise and acceleration are device configuration.</b> A backend that cannot
   * carry them on the request itself ({@link org.rootstock.hardware.MotorCapabilities#dynamicProfile()}
   * false) would need a blocking CAN config write from {@code periodic()} to honour a runtime change,
   * which this library will not do — so such a mechanism steps the profile on the roboRIO instead,
   * decided once here rather than silently at match time.
   *
   * @param name the profile's name, as it appears in the log
   * @param constraints the constraints to install while the condition holds
   * @param when the condition
   */
  public void addConstraintProfile(String name, MotionConstraints constraints, BooleanSupplier when) {
    if (name == null || constraints == null || when == null) {
      return;
    }
    m_profiles.add(new Profile(name, constraints, when));
  }

  /**
   * The constraints in force this cycle.
   *
   * @return the active constraints, in user units
   */
  public MotionConstraints activeConstraints() {
    return m_activeConstraints;
  }

  /**
   * The name of the constraint profile in force this cycle.
   *
   * @return the profile name, or {@code "base"}
   */
  public String activeProfileName() {
    return m_activeProfileName;
  }

  // ===============================================================================================
  // Command factories
  // ===============================================================================================

  /**
   * Go to a named setpoint and stay there.
   *
   * <p>Holds the requirement until interrupted, so a default {@code hold()} command cannot cut the
   * move short by latching the mechanism's mid-flight position. Compose with {@code .until(...)} for
   * anything else; {@link #goToAndWait(Setpoint)} is that composition pre-written.
   *
   * @param s the setpoint
   * @return a fresh command
   */
  public Command goTo(Setpoint s) {
    if (safeMode()) {
      return refusal("goTo");
    }
    String label = s == null ? "null" : s.name();
    return Commands.startRun(() -> setGoal(s), () -> {}, this).withName(m_name + ".goTo(" + label + ")");
  }

  /**
   * Go to a named setpoint by string and stay there. An unknown name refuses, loudly.
   *
   * @param name the setpoint name
   * @return a fresh command; a named refusal when the name is unknown
   */
  public Command goTo(String name) {
    if (safeMode()) {
      return refusal("goTo");
    }
    Optional<Setpoint> found = setpoint(name);
    if (found.isEmpty()) {
      return Commands.none()
          .beforeStarting(() -> setGoal(name))
          .withName(m_name + ".goTo(" + name + ") [unknown setpoint]");
    }
    return goTo(found.get());
  }

  /**
   * Go to a position in user units and stay there.
   *
   * @param userUnits the goal, in metres or degrees
   * @return a fresh command
   */
  public Command goTo(double userUnits) {
    if (safeMode()) {
      return refusal("goTo");
    }
    return Commands.startRun(() -> setGoal(userUnits), () -> {}, this)
        .withName(m_name + ".goTo(" + userUnits + ")");
  }

  /**
   * Go to a named setpoint and end once {@link #atGoal()} latches.
   *
   * @param s the setpoint
   * @return a fresh command
   */
  public Command goToAndWait(Setpoint s) {
    String label = s == null ? "null" : s.name();
    return goTo(s).until(this::atGoal).withName(m_name + ".goToAndWait(" + label + ")");
  }

  /**
   * Go to a named setpoint by string and end once {@link #atGoal()} latches.
   *
   * @param name the setpoint name
   * @return a fresh command
   */
  public Command goToAndWait(String name) {
    return goTo(name).until(this::atGoal).withName(m_name + ".goToAndWait(" + name + ")");
  }

  /**
   * Go to a position in user units and end once {@link #atGoal()} latches.
   *
   * @param userUnits the goal, in metres or degrees
   * @return a fresh command
   */
  public Command goToAndWait(double userUnits) {
    return goTo(userUnits).until(this::atGoal).withName(m_name + ".goToAndWait(" + userUnits + ")");
  }

  /**
   * Latch the current position on first execute and hold it forever.
   *
   * <p>The intended default command. It is a different verb from {@code goTo(STOW)} on purpose.
   *
   * @return a fresh command
   */
  public Command hold() {
    if (safeMode()) {
      return refusal("hold");
    }
    return Commands.startRun(this::holdPosition, () -> {}, this).withName(m_name + ".hold");
  }

  /**
   * Manual stick control for as long as the command runs, capturing the position on release.
   *
   * @param stick the axis supplier
   * @return a fresh command
   */
  public Command manual(DoubleSupplier stick) {
    if (safeMode()) {
      return refusal("manual");
    }
    DoubleSupplier source = stick == null ? () -> 0.0 : stick;
    return Commands.runEnd(() -> manualControl(source.getAsDouble()), this::holdPosition, this)
        .withName(m_name + ".manual");
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
  // Characterization callbacks (the routine itself is M7)
  // ===============================================================================================

  /**
   * Ask permission to run a characterization routine, and record the refusal if there is one.
   *
   * <p>SysId refuses to run in SAFE_MODE, while disabled, when the mechanism is unhomed, and when an
   * FMS is attached. A characterization routine that drives a geared arm into a hard stop at step
   * voltage is a broken gearbox, and an unhomed mechanism cannot know where its hard stops are.
   *
   * @return empty when the routine may start, or the sentence explaining why it may not
   */
  public Optional<String> characterizationRefusal() {
    String reason = null;
    if (safeMode()) {
      reason = "SAFE_MODE is active, so no mechanism actuates.";
    } else if (MatchContext.isDisabled()) {
      reason = "the robot is disabled. Enable it, then run the routine.";
    } else if (!isHomed()) {
      reason =
          "the mechanism is not homed, so every position abort would be computed against a "
              + "fictional position. Run homeCommand() first.";
    } else if (MatchContext.isFMSAttached()) {
      reason = "an FMS is attached. Characterization is a pit activity, never a match one.";
    }
    if (reason == null) {
      m_characterizationRefused.set(false);
      return Optional.empty();
    }
    m_characterizationRefused.text(m_name + "/characterization-refused: " + reason).set(true);
    return Optional.of(reason);
  }

  /**
   * Enter characterization, handing the output over to the caller's voltage ramp.
   *
   * @return true when the mechanism entered {@link MechanismMode#CHARACTERIZING}
   */
  public boolean beginCharacterization() {
    if (characterizationRefusal().isPresent()) {
      return false;
    }
    m_openLoopVolts = 0.0;
    setMode(MechanismMode.CHARACTERIZING);
    return true;
  }

  /**
   * The abort a running characterization routine must honour this cycle.
   *
   * <p>Checked every loop by the routine, because the conditions that make a ramp unsafe all appear
   * <i>during</i> the ramp: approaching a soft limit at step voltage, a stall, a device reset, or a
   * follower that has stopped agreeing with its leader.
   *
   * @return empty when the ramp may continue, or the sentence explaining why it must stop
   */
  public Optional<String> characterizationAbort() {
    if (mode() != MechanismMode.CHARACTERIZING) {
      return Optional.of("the mechanism is no longer in CHARACTERIZING mode.");
    }
    if (!m_travelLimits.insideSoft(m_units.toSi(m_measured))) {
      return Optional.of(
          String.format(
              Locale.ROOT,
              "the mechanism reached %.4f %s, inside the soft margin of its travel limits.",
              m_measured,
              m_units.unitLabel()));
    }
    if (isStalled()) {
      return Optional.of("the mechanism is stalled.");
    }
    if (m_inputs.deviceResetCount > m_lastResetCount) {
      return Optional.of("the device reset mid-routine and lost its configuration.");
    }
    if (followerDriftRot() > m_config.limits().followerToleranceRot()) {
      return Optional.of("a follower stopped agreeing with its leader.");
    }
    return Optional.empty();
  }

  /**
   * Command the characterization voltage for this cycle.
   *
   * <p>A no-op unless {@link #beginCharacterization()} succeeded, so a stray call cannot drive the
   * mechanism open-loop from anywhere else in the robot.
   *
   * @param volts the voltage the routine wants
   */
  public void setCharacterizationVolts(double volts) {
    if (mode() != MechanismMode.CHARACTERIZING) {
      return;
    }
    m_openLoopVolts = Double.isFinite(volts) ? volts : 0.0;
  }

  /** Leave characterization and command neutral. */
  public void endCharacterization() {
    if (mode() == MechanismMode.CHARACTERIZING) {
      setNeutral();
    }
  }

  // ===============================================================================================
  // The loop
  // ===============================================================================================

  @Override
  protected void onPeriodic() {
    m_measured = m_units.toUser(m_inputs.positionRot);
    m_measuredVel = m_units.toUserPerSec(m_inputs.velocityRps);

    if (m_absolute != null) {
      m_absolute.updateInputs(m_absoluteInputs);
      RootstockLog.processInputs(m_absoluteInputsKey, m_absoluteInputs);
    }

    if (m_inputs.deviceResetCount > m_lastResetCount) {
      // The device came back with factory configuration: no soft limits, no gains, no seeded
      // position. Whatever it reports is fiction until the routine runs again.
      m_lastResetCount = m_inputs.deviceResetCount;
      m_homing.invalidate();
      m_io.applyGains(m_gains);
      m_io.applyConstraints(m_activeConstraints);
    }

    if (!m_bootHomingAttempted
        && m_config.homing().seedsPosition()
        && !m_config.homing().needsMotion()) {
      // An absolute seed or a boot-time assumption costs nothing and needs no motion, so it happens
      // on the first loop rather than waiting for somebody to schedule a command. A mechanism that
      // declares no strategy at all is left alone: validation has already said so at boot, and a
      // per-mechanism refusal alert on top of that is noise, not information.
      m_bootHomingAttempted = true;
      m_homing.begin(safeMode());
    }

    if (m_latchGoalAfterHoming) {
      m_latchGoalAfterHoming = false;
      if (Double.isFinite(m_measured)) {
        m_goal = m_travel.clamp(m_measured);
        m_siProfileState = new TrapezoidProfile.State(m_units.toSi(m_goal), 0.0);
        if (m_unwrap != null) {
          m_unwrap.seed(m_goal);
        }
      }
    }

    m_homing.maybeReseed(mode() == MechanismMode.NEUTRAL);
    selectConstraintProfile();

    if (MatchContext.isDisabled() || safeMode()) {
      if (mode() == MechanismMode.HOMING) {
        m_homing.cancel(
            safeMode() ? HomingRunner.AbortReason.SAFE_MODE : HomingRunner.AbortReason.DISABLED);
      }
      setMode(MechanismMode.NEUTRAL);
      // Re-seed the profile from reality so a re-enable never commands a step.
      m_siProfileState =
          new TrapezoidProfile.State(
              finiteOr(m_units.toSi(m_measured), 0.0), finiteOr(m_units.toSiPerSec(m_measuredVel), 0.0));
      m_goalIsFresh = true;
      m_manual.reset();
    }

    applyHardLimitCapture();

    MechanismMode mode = mode();
    if (mode == MechanismMode.NEUTRAL) {
      m_outputVolts = 0.0;
      m_setpointUser = m_measured;
      m_setpointVelUser = 0.0;
      m_io.setNeutral();
    } else if (mode == MechanismMode.OPEN_LOOP || mode == MechanismMode.MANUAL) {
      m_outputVolts = clampOpenLoopAgainstSoftLimits(m_openLoopVolts);
      m_setpointUser = m_measured;
      m_setpointVelUser = 0.0;
      m_io.setVoltage(m_outputVolts);
    } else if (mode == MechanismMode.CLOSED_LOOP) {
      applyClosedLoop();
    } else if (mode == MechanismMode.HOMING) {
      m_homing.periodic(safeMode());
      m_outputVolts = m_homing.commandedVolts();
      m_setpointUser = m_measured;
      m_setpointVelUser = 0.0;
      if (!m_homing.isActive()) {
        setMode(MechanismMode.NEUTRAL);
        // The seed written this cycle does not reach m_measured until the NEXT updateInputs, so the
        // goal is latched one loop later. Latching it now would latch the pre-seed reading, which is
        // exactly the fiction homing just finished correcting.
        m_latchGoalAfterHoming = true;
      }
    } else {
      // CHARACTERIZING: the routine owns the voltage, and the soft-limit clamp still applies.
      m_outputVolts = clampOpenLoopAgainstSoftLimits(m_openLoopVolts);
      m_setpointUser = m_measured;
      m_setpointVelUser = 0.0;
      m_io.setVoltage(m_outputVolts);
    }

    m_atSetpoint =
        Double.isFinite(m_measured) && Math.abs(m_setpointUser - m_measured) <= m_toleranceUser;
    m_atGoal = atGoalGatesPass() && m_goalDebounce.calculate(rawAtGoal(m_toleranceUser));
    m_goalIsFresh = false;

    stage();
  }

  private void applyClosedLoop() {
    if (m_gains.isUntuned()) {
      // Refuse rather than command a NaN a motor controller reads as zero and a plot reads as a gap.
      m_untunedGains
          .text(
              m_name
                  + "/gains-untuned: closed-loop control is refused because kP is the UNTUNED "
                  + "placeholder. Expected measured gains. Fix: run the tuning wizard, or set "
                  + ".gains(Gains.pid(...)) on the config. Manual control and homing still work.")
          .set(true);
      m_outputVolts = 0.0;
      m_setpointUser = m_measured;
      m_setpointVelUser = 0.0;
      m_io.setNeutral();
      return;
    }
    m_untunedGains.set(false);

    MotionConstraints active = m_activeConstraints;
    if (m_location == ControlLocation.ON_MOTOR_PROFILED
        || m_location == ControlLocation.ON_MOTOR_DIRECT) {
      double goalRot = m_units.toOutputRotations(m_goal);
      double goalRps = m_units.toOutputRps(fieldLockVelocityUserPerSec());
      double trimVolts =
          m_io.capabilities().positionGoalVelocity() == VelocityCarrier.RIO_VOLTAGE_TRIM
              ? m_kVDevice * goalRps
              : 0.0;
      if (active == m_baseConstraints || !m_io.capabilities().dynamicProfile()) {
        m_io.setPositionGoal(goalRot, goalRps, trimVolts);
      } else {
        m_io.setPositionGoal(goalRot, goalRps, trimVolts, active);
      }
      m_profileFinished = true;
      m_setpointUser = m_goal;
      m_setpointVelUser = m_units.toUserPerSec(goalRps);
      m_outputVolts = finiteOr(m_inputs.appliedVolts, trimVolts);
      m_feedbackVolts = Double.NaN;
      m_feedforwardVolts = Double.NaN;
      return;
    }

    TrapezoidProfile profile = siProfile(active);
    TrapezoidProfile.State goalState = new TrapezoidProfile.State(m_units.toSi(m_goal), 0.0);
    double dt = dtSeconds();
    // `next` first, so isFinished(0.0) refers to the step actually applied.
    TrapezoidProfile.State next = profile.calculate(2.0 * dt, m_siProfileState, goalState);
    TrapezoidProfile.State current = profile.calculate(dt, m_siProfileState, goalState);
    m_siProfileState = current;
    m_profileFinished = profile.isFinished(0.0);
    m_setpointUser = m_units.fromSi(current.position);
    m_setpointVelUser = m_units.fromSiPerSec(current.velocity);

    if (m_location == ControlLocation.RIO_PROFILE_MOTOR_LOOP) {
      double stepRps = m_units.toOutputRps(m_setpointVelUser + fieldLockVelocityUserPerSec());
      m_io.setPositionGoal(m_units.toOutputRotations(m_setpointUser), stepRps, 0.0);
      m_outputVolts = finiteOr(m_inputs.appliedVolts, 0.0);
      m_feedbackVolts = Double.NaN;
      m_feedforwardVolts = Double.NaN;
      return;
    }

    // =============================================================================================
    // RIO_FULL. THIS IS THE ONE PLACE IN THIS CLASS WHERE SI CONVERSION HAPPENS.
    // Gains are volts-per-SI. Running them against an error expressed in degrees is wrong by 57.3x,
    // and against one expressed in inches by 39.37x, in a way that looks entirely plausible.
    // Profile, PID, feedforward and clamp are all SI below; only the telemetry converts back.
    // =============================================================================================
    double siMeasured = m_units.toSi(m_measured);
    double velocitySi =
        m_units.toSiPerSec(fieldLockVelocityUserPerSec()) + current.velocity;
    double feedback =
        Double.isFinite(siMeasured) ? m_siPid.calculate(siMeasured, current.position) : 0.0;
    // ArmFeedforward wants radians FROM HORIZONTAL, so the horizontal reference is subtracted here
    // and nowhere else. The elevator and simple feedforwards ignore the position argument.
    double feedforward =
        m_ff.calculate(current.position - m_siHorizontalReference, velocitySi, next.velocity);
    m_feedbackVolts = feedback;
    m_feedforwardVolts = feedforward;
    m_outputVolts =
        RootstockMath.clamp(finiteOr(feedback + feedforward, 0.0), -kMaxOutputVolts, kMaxOutputVolts);
    m_io.setVoltage(m_outputVolts);
  }

  /**
   * Zero an open-loop command that pushes past a soft limit.
   *
   * <p>The device's own soft limit would do this anyway; doing it in Java means the student sees
   * {@code OpenLoopClamped} go true instead of concluding the stick stopped working.
   */
  private double clampOpenLoopAgainstSoftLimits(double volts) {
    double requested = Double.isFinite(volts) ? volts : 0.0;
    m_openLoopClamped = false;
    if (!Double.isFinite(m_measured)) {
      return requested;
    }
    if (requested > 0.0 && (m_measured >= m_travel.max() || forwardHardLimitTripped())) {
      m_openLoopClamped = true;
      return 0.0;
    }
    if (requested < 0.0 && (m_measured <= m_travel.min() || reverseHardLimitTripped())) {
      m_openLoopClamped = true;
      return 0.0;
    }
    return requested;
  }

  /**
   * Capture-and-hold: a hard limit asserting turns the current position into the goal.
   *
   * <p>Without it a closed loop keeps commanding through an assert switch, which is a mechanism
   * grinding against a stop with the controller convinced it simply needs more voltage.
   */
  private void applyHardLimitCapture() {
    if (mode() != MechanismMode.CLOSED_LOOP || !Double.isFinite(m_measured)) {
      return;
    }
    if (forwardHardLimitTripped() && m_goal > m_measured) {
      m_goal = m_measured;
      m_siProfileState = new TrapezoidProfile.State(m_units.toSi(m_measured), 0.0);
    } else if (reverseHardLimitTripped() && m_goal < m_measured) {
      m_goal = m_measured;
      m_siProfileState = new TrapezoidProfile.State(m_units.toSi(m_measured), 0.0);
    }
  }

  private boolean forwardHardLimitTripped() {
    return m_inputs.forwardLimitValid && m_inputs.forwardLimitTripped;
  }

  private boolean reverseHardLimitTripped() {
    return m_inputs.reverseLimitValid && m_inputs.reverseLimitTripped;
  }

  private void selectConstraintProfile() {
    MotionConstraints chosen = m_baseConstraints;
    String name = "base";
    for (Profile profile : m_profiles) {
      boolean applies;
      try {
        applies = profile.when().getAsBoolean();
      } catch (Throwable ignored) {
        // A team's supplier is not this library's code. A broken one selects the base profile
        // rather than taking the mechanism down.
        applies = false;
      }
      if (applies) {
        chosen = profile.constraints();
        name = profile.name();
        break;
      }
    }
    if (chosen != m_activeConstraints) {
      m_activeConstraints = chosen;
      m_activeProfileName = name;
      if (m_location.runsOnMotor() && m_io.capabilities().dynamicProfile()) {
        // Non-blocking by contract; the blocking apply is what this library refuses to do here.
        m_io.applyConstraints(chosen);
      }
    } else {
      m_activeProfileName = name;
    }
  }

  private double fieldLockVelocityUserPerSec() {
    if (!m_fieldLocked || m_fieldLockYawRateRadPerSec == null) {
      return 0.0;
    }
    double omega = m_fieldLockYawRateRadPerSec.getAsDouble();
    return Double.isFinite(omega) ? ContinuousUnwrap.fieldLockVelocityDegPerSec(omega) : 0.0;
  }

  private TrapezoidProfile siProfile(MotionConstraints constraints) {
    if (m_siProfile == null || !constraints.equals(m_siProfileBuiltFrom)) {
      m_siProfile = buildSiProfile(constraints);
      m_siProfileBuiltFrom = constraints;
    }
    return m_siProfile;
  }

  private TrapezoidProfile buildSiProfile(MotionConstraints constraints) {
    double v = finiteConstraint(constraints.maxVelocitySi(m_units));
    double a = finiteConstraint(constraints.maxAccelerationSi(m_units));
    return Controllers.trapezoidProfile(v, a);
  }

  private boolean rawAtGoal(double tolerance) {
    if (!Double.isFinite(m_measured)) {
      return false;
    }
    boolean nearGoal = Math.abs(m_goal - m_measured) <= tolerance;
    boolean settled =
        !Double.isFinite(m_measuredVel) || Math.abs(m_measuredVel) <= m_velocityToleranceUser;
    boolean profileDone = m_location.profileOnRio() ? m_profileFinished : true;
    return nearGoal && settled && profileDone;
  }

  private boolean atGoalGatesPass() {
    if (MatchContext.isDisabled() || safeMode() || m_goalIsFresh) {
      return false;
    }
    // A mechanism that does not know where it is cannot be at a goal. A strategy that needs no
    // motion — an absolute seed, or a declared assumption — has already answered the question.
    return isHomed() || !m_config.homing().needsMotion();
  }

  private void stage() {
    schema()
        .goal(m_goal)
        .setpoint(m_setpointUser)
        .setpointVelocity(m_setpointVelUser)
        .measured(m_measured)
        .output(m_outputVolts)
        .atGoal(m_atGoal)
        .atSetpoint(m_atSetpoint)
        .state(mode())
        .controlMode(controlModeOf(mode()))
        .homed(isHomed())
        .softLimits(m_travel.min(), m_travel.max())
        .currentLimitAmps(m_config.limits().current().stator().in(Amps))
        .simEnabled(Platform.isSimulation());
    schema().gains(m_gains);
    schema().extra(kFeedbackVoltsKey, m_feedbackVolts);
    schema().extra(kFeedforwardVoltsKey, m_feedforwardVolts);
    schema().extra(kOpenLoopClampedKey, m_openLoopClamped);
    schema().extra(kActiveProfileKey, m_activeProfileName);
    schema().extra(kHomingPrefix + "Active", m_homing.isActive());
    schema().extra(kHomingPrefix + "Strategy", m_homing.strategyLabel());
    schema().extra(kHomingPrefix + "ElapsedSec", m_homing.elapsedSeconds());
    schema().extra(kHomingPrefix + "TriggerValue", m_homing.triggerValue());
    schema().extra(kHomingPrefix + "Succeeded", m_homing.succeeded());
    schema().extra(kHomingPrefix + "AbortReason", m_homing.abortReason().name());
    schema().extra(kHomingPrefix + "LimitsSuspended", m_homing.limitsSuspended());
    schema().extra(kHomingPrefix + "LimitsRestoreVerified", m_homing.limitsRestoreVerified());
  }

  private static ControlMode controlModeOf(MechanismMode mode) {
    if (mode == MechanismMode.CLOSED_LOOP) {
      return ControlMode.POSITION;
    }
    if (mode == MechanismMode.HOMING) {
      return ControlMode.HOMING;
    }
    if (mode == MechanismMode.NEUTRAL) {
      return ControlMode.NEUTRAL;
    }
    return ControlMode.VOLTAGE;
  }

  // ===============================================================================================
  // Telemetry declaration, health and self test
  // ===============================================================================================

  @Override
  protected void describeExtras(TelemetryDescriptor d) {
    // Declared UNCONDITIONALLY even though only RIO_FULL publishes a real number into them: a
    // conditionally declared key is a key whose absence the schema audit cannot distinguish from a
    // bug, and "the graph is empty" is exactly the failure that audit exists to catch.
    d.extra(kFeedbackVoltsKey, Volts, Tier.STANDARD);
    d.extra(kFeedforwardVoltsKey, Volts, Tier.STANDARD);
    d.extra(kOpenLoopClampedKey, Tier.STANDARD);
    d.extra(kActiveProfileKey, Tier.STANDARD);
    d.extra(kHomingPrefix + "Active", Tier.CRITICAL);
    d.extra(kHomingPrefix + "Strategy", Tier.STANDARD);
    d.extra(kHomingPrefix + "ElapsedSec", Seconds, Tier.STANDARD);
    d.extra(kHomingPrefix + "TriggerValue", Tier.STANDARD);
    d.extra(kHomingPrefix + "Succeeded", Tier.CRITICAL);
    d.extra(kHomingPrefix + "AbortReason", Tier.CRITICAL);
    // The last two exist so "were the soft limits actually put back?" is answerable from a log file
    // alone, which is the question a failed restore makes urgent.
    d.extra(kHomingPrefix + "LimitsSuspended", Tier.CRITICAL);
    d.extra(kHomingPrefix + "LimitsRestoreVerified", Tier.CRITICAL);
  }

  @Override
  protected void pollMechanismHealth(FaultCollector out) {
    if (!isHomed() && m_config.homing().needsMotion()) {
      out.warn(
          m_name,
          "not homed, so its reported position is a guess and atGoal() will never be true. "
              + "Expected: homing completed this power cycle. Fix: schedule homeCommand(), or "
              + "declare a strategy that needs no motion if the mechanism has an absolute encoder.");
    }
    if (m_gains.isUntuned()) {
      out.error(
          m_name,
          "gains are the UNTUNED placeholder (kP is NaN), so closed-loop control is refused and "
              + "the mechanism will not move to a goal. Expected measured gains. Fix: run the "
              + "tuning wizard, or set .gains(...) on the config.");
    }
    if (!m_config.homing().isTrustworthy()) {
      out.warn(
          m_name,
          "its homing strategy asserts a position rather than measuring one, so a mechanism moved "
              + "by hand while disabled reports the wrong place and gravity compensation pushes the "
              + "wrong way. Expected a sensor-confirmed reference. Fix: add an absolute encoder or "
              + "a hard stop plus HomingStrategy.currentSpike().");
    }
    double drift = followerDriftRot();
    if (drift > m_config.limits().followerToleranceRot()) {
      out.error(
          m_name,
          String.format(
              Locale.ROOT,
              "a follower has drifted %.3f output rotations from its leader, past the %.3f "
                  + "tolerance. Expected them to agree. That is a slipped belt, a stripped gear or "
                  + "an inverted follower, and it is doing mechanical damage right now.",
              drift,
              m_config.limits().followerToleranceRot()));
    }
    if (m_homing.abortReason() != HomingRunner.AbortReason.NONE && !m_homing.succeeded()) {
      out.warn(
          m_name,
          "the last homing attempt ended with "
              + m_homing.abortReason()
              + ". isHomed() is false and every interlock built on it will refuse to run.");
    }
  }

  @Override
  public SelfTestRoutine selfTestRoutine() {
    double span = Math.abs(m_travel.width());
    double delta = span > 0.0 ? Math.min(span * 0.1, Math.max(m_toleranceUser * 5.0, span * 0.05)) : 0.0;
    double start = Double.isFinite(m_measured) ? m_measured : m_travel.centre();
    double target = m_travel.clamp(start + delta);
    double back = m_travel.clamp(start);
    String unit = m_units.unitLabel();
    return SelfTestRoutine.of(m_name)
        .current(this::statorAmps)
        .step("hold", hold().withTimeout(Seconds.of(0.5)))
        .withTimeout(Seconds.of(1.0))
        .step("move away", goToAndWait(target))
        .expect(this::measured, target, Math.max(m_toleranceUser * 3.0, 1e-6), unit)
        .expectMoved(this::measured, Math.max(Math.abs(delta) * 0.5, 1e-6), unit)
        .withTimeout(Seconds.of(4.0))
        .step("return", goToAndWait(back))
        .expect(this::measured, back, Math.max(m_toleranceUser * 3.0, 1e-6), unit)
        .withTimeout(Seconds.of(4.0))
        .expectNoNewFaults()
        .build();
  }

  // ===============================================================================================
  // TuningTarget
  // ===============================================================================================

  @Override
  public MechanismArchetype archetype() {
    return m_archetype;
  }

  @Override
  public ControlLocation controlLocation() {
    return m_location;
  }

  @Override
  public PositionReference positionReference() {
    PositionReference declared = m_config.positionReference();
    if (declared instanceof PositionReference.HomedAgainstSwitch) {
      // The config supplies the shape; whether the routine actually completed this power cycle is
      // runtime state, and only the mechanism owns it.
      return new PositionReference.HomedAgainstSwitch(this::isHomed);
    }
    return declared;
  }

  @Override
  public TravelLimits travelLimits() {
    return m_travelLimits;
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
  public double toleranceSi() {
    return m_control.toleranceSi();
  }

  @Override
  public GravityMode gravityMode() {
    return m_control.gravity();
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
    setGoal(m_units.fromSi(goalSi));
  }

  @Override
  public boolean isProfiled() {
    return m_location != ControlLocation.ON_MOTOR_DIRECT;
  }

  @Override
  public Optional<Double> getFeedbackVolts() {
    return m_location == ControlLocation.RIO_FULL && Double.isFinite(m_feedbackVolts)
        ? Optional.of(m_feedbackVolts)
        : Optional.empty();
  }

  @Override
  public OptionalDouble absolutePositionSi() {
    OptionalDouble user = absolutePositionUser();
    return user.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(m_units.toSi(user.getAsDouble()));
  }

  // ===============================================================================================
  // Boot dump
  // ===============================================================================================

  @Override
  protected String describeDetail() {
    StringBuilder sb = new StringBuilder(512);
    sb.append("  limits:   ").append(m_config.limits().describe());
    sb.append(System.lineSeparator())
        .append("  location: ")
        .append(m_location)
        .append(m_location == m_control.location() ? "" : " (downgraded from " + m_control.location() + ")");
    sb.append(System.lineSeparator()).append("  carrier:  ")
        .append(m_io.capabilities().positionGoalVelocity())
        .append(" carries the goal-velocity term");
    sb.append(System.lineSeparator()).append("  ").append(m_homing.describe());
    if (m_unwrap != null) {
      sb.append(System.lineSeparator()).append("  unwrap:   ").append(m_unwrap.describe());
    }
    if (!m_profiles.isEmpty()) {
      sb.append(System.lineSeparator()).append("  profiles: ");
      for (Profile profile : m_profiles) {
        sb.append(profile.name()).append("=").append(profile.constraints().describe(m_units.unitLabel())).append("; ");
      }
    }
    if (!m_setpoints.isEmpty()) {
      sb.append(System.lineSeparator()).append("  setpoints:").append(m_setpoints.keySet());
    }
    return sb.toString();
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  private OptionalDouble absolutePositionUser() {
    if (m_absolute == null || !m_absoluteInputs.connected) {
      return OptionalDouble.empty();
    }
    double rot = m_absoluteInputs.absolutePositionRot;
    return Double.isNaN(rot) ? OptionalDouble.empty() : OptionalDouble.of(m_units.toUser(rot));
  }

  private double followerDriftRot() {
    double leader = m_inputs.positionRot;
    if (Double.isNaN(leader)) {
      return 0.0;
    }
    double worst = 0.0;
    for (double follower : m_inputs.followerPositionRot) {
      if (!Double.isNaN(follower)) {
        worst = Math.max(worst, Math.abs(follower - leader));
      }
    }
    return worst;
  }

  private void refuseInSafeMode(String verb) {
    m_refusedInSafeMode
        .text(
            m_name
                + "/refused-in-safe-mode: "
                + verb
                + " did nothing because SAFE_MODE is active. Expected a valid configuration. Fix: "
                + "read the config errors printed at boot, correct them, and reboot. The robot is "
                + "still drivable so it can be pushed off the field.")
        .set(true);
  }

  private Command refusal(String verb) {
    return Commands.none()
        .beforeStarting(() -> refuseInSafeMode(verb))
        .withName(m_name + "." + verb + " (refused: SAFE_MODE)");
  }

  private void wrongUnitKind(String given, String wanted) {
    m_unknownSetpoint
        .text(
            m_name
                + "/wrong-unit-kind: setGoal was called with "
                + given
                + " but this mechanism's axis measures "
                + wanted
                + ". Nothing was commanded. Fix: use the overload that matches the axis, or a "
                + "declared Setpoint, which carries its own unit.")
        .set(true);
  }

  private static double dtSeconds() {
    double dt = Clock.dt();
    return Double.isFinite(dt) && dt > 0.0 ? dt : 0.02;
  }

  private static double finiteOr(double value, double fallback) {
    return Double.isFinite(value) ? value : fallback;
  }

  private static double finiteConstraint(double value) {
    if (Double.isNaN(value) || value <= 0.0 || Double.isInfinite(value)) {
      // An infinite trapezoid constraint makes TrapezoidProfile return NaN rather than "instantly at
      // the goal", so the honest translation of "unconstrained" is one very large finite number.
      return org.rootstock.hardware.RioControlLoop.kEffectivelyUnbounded;
    }
    return value;
  }

  private static double resolveTolerance(ControlConfig control, boolean linear) {
    double declared = control.toleranceUser();
    if (Double.isFinite(declared) && declared > 0.0 && control.toleranceIsLinear() == linear) {
      return declared;
    }
    return linear
        ? ControlConfig.kDefaultLinearToleranceMeters
        : ControlConfig.kDefaultRotaryToleranceDegrees;
  }

  /**
   * Resolve the gains a mechanism actually runs, which is the one place {@link Gains#UNTUNED} is
   * interpreted.
   *
   * <p>Static so it can run before the constructor finishes and so architecture rule 11 permits it
   * to be the place a fatal misuse would surface.
   */
  private static Gains resolveGains(
      Gains declared, PlantPrior prior, GravityMode gravity, RootstockAlert derived, String name) {
    if (declared == null) {
      return Gains.UNTUNED;
    }
    if (!declared.isUntuned() || Platform.isReal()) {
      // On hardware the placeholder is kept exactly as declared: the mechanism refuses closed-loop
      // control and says to run the wizard, rather than pretending somebody else's numbers are ours.
      return declared;
    }
    double kV = prior.kVprior();
    double kA = prior.kAprior();
    double kG = gravity == GravityMode.NONE ? 0.0 : prior.gravityVoltsPrior();
    if (!Double.isFinite(kV) || !Double.isFinite(kA) || kA <= 0.0) {
      return declared;
    }
    // A first guess with a stated design: place the closed loop at a gentle bandwidth against the
    // plant the team declared, critically damped. kP = kA*wn^2 and kD = 2*zeta*wn*kA - kV are the
    // second-order placement for a plant whose transfer function is 1/(kA s^2 + kV s).
    double omega = 2.0 * Math.PI * kSimGuessBandwidthHz;
    double kP = kA * omega * omega;
    double kD = Math.max(0.0, 2.0 * omega * kA - kV);
    Gains guess = new Gains(kP, 0.0, kD, 0.0, kV, kA, Double.isFinite(kG) ? kG : 0.0);
    derived
        .text(
            name
                + "/gains-derived-not-measured: this mechanism declared Gains.UNTUNED and is running "
                + "in simulation, so Rootstock derived "
                + guess.describe(
                    prior.isLinear() ? SiDomain.LINEAR_METERS : SiDomain.ROTATIONAL_RADIANS)
                + " from your declared mass and gearing. They were DERIVED, not measured. Run the "
                + "tuning wizard before trusting them on hardware.")
        .set(true);
    return guess;
  }

  private static MechanismArchetype archetypeOf(PositionConfig config) {
    if (config.isLinear()) {
      return MechanismArchetype.ELEVATOR;
    }
    return config.axis().gravity() == GravityMode.COSINE
        ? MechanismArchetype.ARM
        : MechanismArchetype.TURRET;
  }

  private static PlantPrior priorFor(PositionConfig config) {
    MotorGroup motors = config.motors();
    DCMotor gearbox = motors.model().dcMotor(motors.count(), motors.leader().foc());
    if (config.isLinear()) {
      LinearAxis axis = (LinearAxis) config.axis();
      return PlantPrior.elevator(
          gearbox, config.reduction(), config.sim().massKg(), axis.effectiveRadius().in(Meters));
    }
    if (config.axis().gravity() == GravityMode.COSINE) {
      return PlantPrior.arm(
          gearbox,
          config.reduction(),
          config.sim().moiKgM2(),
          config.sim().armLengthMeters(),
          config.sim().massKg());
    }
    return PlantPrior.flywheel(gearbox, config.reduction(), config.sim().moiKgM2());
  }

  private static TravelLimits travelLimitsFor(MechanismUnits units, Range travel) {
    double min = units.toSi(travel.min());
    double max = units.toSi(travel.max());
    if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
      return TravelLimits.unbounded();
    }
    return new TravelLimits(min, max, (max - min) * kTuningMarginFraction);
  }

  private static MotorIO backendFor(PositionConfig config) {
    Objects.requireNonNull(config, "PositionMechanism: config must not be null");
    return MotorIOFactory.create(
        new MotorIOFactory.DeviceSetup(
            config.motors(), config.limits().current(), config.limits(), config.feedback()),
        config.units(),
        config.control(),
        MechanismKind.POSITION,
        RootstockLog.mode());
  }

  /**
   * Compute the plant declaration once, from the config and the axis.
   *
   * <p>Static so it can run before {@code super()} completes, and so architecture rule 11 permits it
   * to throw on a misuse the way every constructor in this package may.
   */
  private static MechanismGeometry buildGeometry(PositionConfig config) {
    Objects.requireNonNull(config, "PositionMechanism: config must not be null");
    MechanismUnits units = config.units();
    MotorGroup motors = config.motors();
    DCMotor gearbox = motors.model().dcMotor(motors.count(), motors.leader().foc());
    Range travel = config.travel();
    double siMin = units.toSi(travel.min());
    double siMax = units.toSi(travel.max());
    double siStart = config.sim().startingPositionSi();
    if (!Double.isFinite(siStart)) {
      siStart = Double.isFinite(siMin) ? siMin : 0.0;
    }
    boolean linear = config.isLinear();
    boolean cosine = !linear && config.axis().gravity() == GravityMode.COSINE;
    MechanismGeometry.Kind kind =
        linear
            ? MechanismGeometry.Kind.LINEAR
            : (cosine ? MechanismGeometry.Kind.ROTARY : MechanismGeometry.Kind.SIMPLE);
    double effectiveRadius =
        linear ? ((LinearAxis) config.axis()).effectiveRadius().in(Meters) : Double.NaN;
    return new MechanismGeometry(
        config.name(),
        kind,
        gearbox,
        config.reduction().rotorPerOutput(),
        units.siPerOutputRotation(),
        effectiveRadius,
        linear ? config.sim().massKg() : Double.NaN,
        linear ? Double.NaN : config.sim().moiKgM2(),
        cosine ? config.sim().armLengthMeters() : Double.NaN,
        siMin,
        siMax,
        siStart,
        config.sim().simulateGravity());
  }

  /** The mechanism's answers to the five questions {@link HomingRunner} is allowed to ask. */
  private final class Environment implements HomingRunner.Environment {

    @Override
    public double measuredUser() {
      return m_measured;
    }

    @Override
    public double statorAmps() {
      return m_inputs.statorCurrentAmps;
    }

    @Override
    public boolean limitAsserted(SensorSpec sensor) {
      if (sensor instanceof SensorSpec.MotorLimit motorLimit) {
        return motorLimit.side() == SensorSpec.Limit.FORWARD
            ? forwardHardLimitTripped()
            : reverseHardLimitTripped();
      }
      if (sensor instanceof SensorSpec.StatorCurrent stator) {
        double amps = m_inputs.statorCurrentAmps;
        return !Double.isNaN(amps) && amps >= stator.threshold().in(Amps);
      }
      if (sensor instanceof SensorSpec.Sim sim) {
        return sim.detected().getAsBoolean();
      }
      // A DIO, CANrange or CANdi switch needs a DigitalSensorIO this mechanism was never handed.
      // Reporting "not asserted" is the safe answer: the routine times out and says so, rather than
      // seeding a position from a switch nobody is reading.
      return false;
    }

    @Override
    public boolean oppositeLimitAsserted(HomingStrategy.Direction direction) {
      return direction == HomingStrategy.Direction.FORWARD
          ? reverseHardLimitTripped()
          : forwardHardLimitTripped();
    }

    @Override
    public OptionalDouble absolutePositionUser() {
      return PositionMechanism.this.absolutePositionUser();
    }

    @Override
    public boolean absoluteIsFusedOnDevice() {
      return m_config.feedback().isFusedOnDevice() || m_io.capabilities().fusedAbsoluteEncoder();
    }
  }

  /** Writes tuned gains through to wherever this mechanism's loop actually runs. */
  private final class PositionGainSink implements GainSink {

    @Override
    public boolean apply(Gains gains) {
      if (gains == null) {
        return false;
      }
      m_gains = gains;
      m_kVDevice = gains.kV() * m_units.siPerOutputRotation();
      m_siPid.setPID(gains.kP(), gains.kI(), gains.kD());
      m_ff.update(gains);
      // applyGains is the NON-BLOCKING path by contract: safe to call at the ten hertz a dragged
      // tuning slider produces, and traced so the cost is visible rather than assumed.
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

  /**
   * A frame-counted debounce.
   *
   * <p>Written here rather than reused from WPILib because WPILib's {@code Debouncer} reads a wall
   * clock, and a predicate that latches at a different frame under replay than it did on the robot
   * makes the log disagree with the match it recorded. This one integrates {@link Clock#dt()}, so it
   * replays exactly.
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
      m_held += dtSeconds();
      if (m_held >= m_seconds) {
        m_state = true;
      }
      return m_state;
    }

    void reset() {
      m_held = 0.0;
      m_state = false;
    }
  }
}

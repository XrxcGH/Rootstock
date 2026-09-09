package org.rootstock.tuning.sysid;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.MetersPerSecond;
import static edu.wpi.first.units.Units.Radians;
import static edu.wpi.first.units.Units.RadiansPerSecond;
import static edu.wpi.first.units.Units.Second;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import edu.wpi.first.wpilibj.DataLogManager;
import edu.wpi.first.wpilibj.sysid.SysIdRoutineLog;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.control.GravityMode;
import org.rootstock.control.PlantPrior;
import org.rootstock.control.SafetyEnvelope;
import org.rootstock.control.TuningSupervisor;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.units.SiDomain;

/**
 * WPILib's {@code SysIdRoutine}, wired to a mechanism the library already knows about, inside a
 * safety envelope, with the fit happening on the robot.
 *
 * <h2>What is borrowed and what is added</h2>
 *
 * <p>The quasistatic ramp, the dynamic step, and the WPILog entries a SysId GUI can read are all
 * WPILib's, unchanged. What this class adds is everything WPILib explicitly leaves to the team:
 *
 * <ul>
 *   <li><b>The two callbacks.</b> Nobody hand-writes a drive lambda and a log lambda, so nobody
 *       hand-writes the one that logs the <em>commanded</em> voltage instead of the measured one —
 *       which on a sagging battery biases kV high and is among the most common silent
 *       characterisation errors.
 *   <li><b>A derived config.</b> WPILib's defaults are 1 V/s, 7 V and 10 s. On a 1.4 m elevator
 *       those numbers plan a move far longer than the mechanism has room for. Here the ramp rate is
 *       solved so the ramp consumes at most 70% of the <em>supervisor's</em> band, and the step
 *       voltage so the dynamic test consumes at most 45% of it.
 *   <li><b>Safety.</b> WPILib states plainly that the routine only creates voltage commands and that
 *       limits are the team's problem. Every volt here goes through {@link TuningSupervisor}.
 *   <li><b>Log hygiene.</b> WPILib is equally plain that only a log containing a single routine is
 *       analysable. {@link #fullSweep()} closes the current log and opens a fresh, correctly named
 *       one before it moves, so a student cannot get that wrong by running two sweeps in a row.
 * </ul>
 *
 * <p><b>The band the config is derived from is the supervisor's, not the device's.</b> The device's
 * soft-limit band is strictly wider, so a ramp sized against it plans to travel past the point where
 * the supervisor will abort — the sweep would then reliably stop itself and blame the mechanism.
 *
 * <p><b>Why this takes a supervisor rather than a target and an envelope.</b> The published sketch
 * of this class took {@code (TuningTarget, SafetyEnvelope)}, but the drive callback has to reach
 * {@link TuningSupervisor#commandVolts(double)} — nothing else may command voltage — so the
 * supervisor is the argument that actually carries both. {@link #target()} and {@link #envelope()}
 * expose the two halves.
 */
public final class SysIdSweep {

  /** Fraction of the supervisor's band the quasistatic ramp is allowed to consume. */
  public static final double kQuasistaticBandFraction = 0.70;

  /** Fraction of the supervisor's band the dynamic step is allowed to consume. */
  public static final double kDynamicBandFraction = 0.45;

  /** Slowest legal derived ramp rate, in volts per second. */
  public static final double kMinRampVoltsPerSecond = 0.25;

  /** Fastest legal derived ramp rate, in volts per second. */
  public static final double kMaxRampVoltsPerSecond = 2.0;

  /** Smallest legal derived step voltage. Below this nothing breaks away. */
  public static final double kMinStepVolts = 1.5;

  /** Dynamic-step duration for a position archetype, in seconds. */
  public static final double kPositionDynamicSeconds = 1.5;

  /** Dynamic-step duration for a velocity archetype, in seconds. */
  public static final double kVelocityDynamicSeconds = 3.0;

  /** Shortest legal derived quasistatic timeout, in seconds. */
  public static final double kMinQuasistaticSeconds = 2.0;

  /** Longest legal derived quasistatic timeout, in seconds. */
  public static final double kMaxQuasistaticSeconds = 10.0;

  /** Neutral settle between the four tests, in seconds. */
  public static final double kSettleSeconds = 0.75;

  /** Ceiling on the return-to-start move between tests, in seconds. */
  public static final double kReturnSeconds = 6.0;

  /** The alert group every message from this class lands in. */
  public static final String kAlertGroup = "Tuning";

  /**
   * The friction prior used when sizing the step voltage, in volts.
   *
   * <p>Zero, and named rather than hidden: {@link PlantPrior} is a motor curve and a mass, and
   * neither of those predicts friction. kS is precisely what the sweep is there to measure, so
   * sizing the sweep against a guess at it would be circular. The consequence is that the derived
   * step voltage is very slightly conservative on a high-friction mechanism, which is the correct
   * direction to be wrong in.
   */
  public static final double kFrictionPriorVolts = 0.0;

  private final TuningSupervisor m_supervisor;
  private final TuningTarget m_target;
  private final SafetyEnvelope m_envelope;
  private final FeedforwardRegression m_regression;
  private final SampleBuffer m_buffer;

  private final SysIdRoutine.Config m_quasistaticConfig;
  private final SysIdRoutine.Config m_dynamicConfig;
  private SysIdRoutine m_quasistaticRoutine;
  private SysIdRoutine m_dynamicRoutine;

  private SysIdRoutineLog.State m_state = SysIdRoutineLog.State.kNone;
  private Optional<String> m_lastLogPath = Optional.empty();
  private double m_startPosition = Double.NaN;

  // Two-sample history, so acceleration can be a centred difference rather than a backward one.
  // A backward difference lags by half a sample and, at 50 Hz on a fast mechanism, that lag is
  // large enough to bias kA. The cost is that every sample is fed to the regression one loop late.
  private double m_t0 = Double.NaN;
  private double m_v0 = Double.NaN;
  private double m_t1 = Double.NaN;
  private double m_v1 = Double.NaN;
  private double m_pos1 = Double.NaN;
  private double m_angle1 = Double.NaN;
  private double m_volts1 = Double.NaN;

  private SysIdSweep(TuningSupervisor supervisor) {
    m_supervisor = Objects.requireNonNull(supervisor, "supervisor");
    m_target = supervisor.target();
    m_envelope = supervisor.envelope();
    m_regression = new FeedforwardRegression(m_target.archetype());
    m_buffer = SampleBuffer.standard();

    double[] derived = derive(m_target, m_envelope);
    double rampRate = derived[0];
    double stepVolts = derived[1];
    double quasiSeconds = derived[2];
    double dynamicSeconds = derived[3];

    m_quasistaticConfig =
        new SysIdRoutine.Config(
            Volts.per(Second).of(rampRate),
            Volts.of(stepVolts),
            Seconds.of(quasiSeconds),
            this::recordState);
    m_dynamicConfig =
        new SysIdRoutine.Config(
            Volts.per(Second).of(rampRate),
            Volts.of(stepVolts),
            Seconds.of(dynamicSeconds),
            this::recordState);

    SysIdRoutine.Mechanism mechanism =
        new SysIdRoutine.Mechanism(
            volts -> m_supervisor.commandVolts(volts.in(Volts)),
            this::recordMotor,
            m_target.requirement().orElseGet(SysIdSweep::placeholderSubsystem),
            m_target.tuningName());

    m_quasistaticRoutine = new SysIdRoutine(m_quasistaticConfig, mechanism);
    m_dynamicRoutine = new SysIdRoutine(m_dynamicConfig, mechanism);
  }

  /**
   * Build a sweep for the mechanism the supervisor guards.
   *
   * @param supervisor the supervisor; its envelope is what the ramp and step are sized against
   * @return the sweep
   * @throws NullPointerException if the supervisor is null
   */
  public static SysIdSweep of(TuningSupervisor supervisor) {
    return new SysIdSweep(supervisor);
  }

  /**
   * The generated quasistatic routine. Exposed so a team can bind the commands itself.
   *
   * @return the routine used by {@link #quasistatic(SysIdRoutine.Direction)}
   */
  public SysIdRoutine routine() {
    return m_quasistaticRoutine;
  }

  /**
   * The generated dynamic routine.
   *
   * <p>There are two routines because {@code SysIdRoutine.Config} carries a single timeout, and the
   * ramp and the step have genuinely different budgets — a ramp runs until it reaches the voltage
   * ceiling, a step runs for a fixed short burst. One shared timeout would either cut the ramp short
   * or let the step run four times longer than the band allows.
   *
   * @return the routine used by {@link #dynamic(SysIdRoutine.Direction)}
   */
  public SysIdRoutine dynamicRoutine() {
    return m_dynamicRoutine;
  }

  /**
   * The quasistatic voltage ramp, in one direction.
   *
   * @param direction forward or reverse
   * @return the command
   */
  public Command quasistatic(SysIdRoutine.Direction direction) {
    return m_quasistaticRoutine.quasistatic(direction);
  }

  /**
   * The dynamic voltage step, in one direction.
   *
   * @param direction forward or reverse
   * @return the command
   */
  public Command dynamic(SysIdRoutine.Direction direction) {
    return m_dynamicRoutine.dynamic(direction);
  }

  /**
   * All four tests, in order, into a single freshly opened log, with a settle and a return to the
   * starting position between them.
   *
   * <p>The fresh log is the point. WPILib is explicit that only a log containing a single routine is
   * usable for analysis, and running two sweeps without power-cycling silently produces a file the
   * GUI rejects. That is a rule a student cannot be expected to remember at 11pm, so this owns it.
   *
   * @return the command; schedule it in Test mode with the enable held
   */
  public Command fullSweep() {
    return Commands.sequence(
            Commands.runOnce(this::beginSweep),
            quasistatic(SysIdRoutine.Direction.kForward),
            settle(),
            returnToStart(),
            quasistatic(SysIdRoutine.Direction.kReverse),
            settle(),
            returnToStart(),
            dynamic(SysIdRoutine.Direction.kForward),
            settle(),
            returnToStart(),
            dynamic(SysIdRoutine.Direction.kReverse),
            settle(),
            returnToStart())
        .finallyDo(this::endSweep)
        .withName("SysIdSweep/" + m_target.tuningName());
  }

  /**
   * The regression accumulated during the most recent sweep.
   *
   * <p>It is live: it can be read while the sweep is running to show a sample count, and {@link
   * FeedforwardRegression#solve()} is called once at the end.
   *
   * @return the regression
   */
  public FeedforwardRegression regression() {
    return m_regression;
  }

  /**
   * Every sample recorded during the sweep, for plots and for the simulated-velocity check.
   *
   * @return the ring buffer
   */
  public SampleBuffer buffer() {
    return m_buffer;
  }

  /**
   * The quasistatic config actually used, after derivation and clamping. Published to the UI.
   *
   * @return the config
   */
  public SysIdRoutine.Config derivedConfig() {
    return m_quasistaticConfig;
  }

  /**
   * The dynamic config actually used. Differs from {@link #derivedConfig()} only in its timeout.
   *
   * @return the config
   */
  public SysIdRoutine.Config derivedDynamicConfig() {
    return m_dynamicConfig;
  }

  /**
   * The mechanism being swept.
   *
   * @return the target
   */
  public TuningTarget target() {
    return m_target;
  }

  /**
   * The box the sweep runs inside, and the band its config was sized against.
   *
   * @return the envelope
   */
  public SafetyEnvelope envelope() {
    return m_envelope;
  }

  /**
   * Where the plain WPILog for this sweep was written.
   *
   * <p>Always shown to the student, not only when AdvantageKit is detected: AdvantageKit logs are
   * not directly loadable by the SysId GUI, AdvantageKit is always present, and a student who wants
   * the official tool should not have to discover that on a forum.
   *
   * @return the absolute path, or empty until a sweep has run
   */
  public Optional<String> lastLogPath() {
    return m_lastLogPath;
  }

  /**
   * The derived numbers on one line, for the UI panel that shows what the sweep is about to do.
   *
   * @return e.g. {@code "Elevator sweep: ramp 0.83 V/s, step 3.21 V, quasi 12.2 s, dynamic 1.5 s"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s sweep: ramp %.2f V/s, step %.2f V, quasi %.1f s, dynamic %.1f s",
        m_target.tuningName(),
        m_quasistaticConfig.m_rampRate.baseUnitMagnitude(),
        m_quasistaticConfig.m_stepVoltage.in(Volts),
        m_quasistaticConfig.m_timeout.in(Seconds),
        m_dynamicConfig.m_timeout.in(Seconds));
  }

  // ===============================================================================================
  // config derivation
  // ===============================================================================================

  /**
   * Solve the ramp rate, step voltage and the two timeouts from the mechanism's own safe band.
   *
   * <p>For a position archetype, with {@code L} the supervisor band, {@code Vmax} the voltage
   * ceiling and {@code kV} the motor-curve prior: the distance covered while ramping to {@code Vmax}
   * at rate {@code r} is about {@code Vmax² / (2 r kV)}, so setting that equal to 70% of the band
   * and solving for {@code r} gives the ramp rate. The step voltage comes from the same idea in the
   * other direction — at steady speed {@code (V − kS)/kV} for {@code t} seconds, travel is about
   * {@code t (V − kS)/kV}, so the {@code V} that consumes 45% of the band in the step's duration is
   * the ceiling.
   *
   * <p>For a velocity archetype travel is irrelevant; the constraint is the velocity ceiling, so the
   * ramp is simply a sixth of the voltage ceiling per second and the step is what reaches 80% of the
   * allowed speed.
   *
   * @param target the mechanism
   * @param envelope the band to size against
   * @return {@code {rampVoltsPerSecond, stepVolts, quasistaticSeconds, dynamicSeconds}}
   */
  public static double[] derive(TuningTarget target, SafetyEnvelope envelope) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(envelope, "envelope");
    PlantPrior prior = target.plantPrior();
    double vmax = Math.abs(envelope.maxVolts());
    double kv = prior.kVprior();
    boolean kvUsable = Double.isFinite(kv) && kv > 0;

    if (target.archetype().isVelocity()) {
      double ramp = clamp(vmax / 6.0, kMinRampVoltsPerSecond, kMaxRampVoltsPerSecond);
      double allowedSpeed = envelope.maxAbsVelocity();
      if (!Double.isFinite(allowedSpeed)) {
        allowedSpeed = prior.freeSpeedSi();
      }
      double step =
          kvUsable && Double.isFinite(allowedSpeed)
              ? Math.min(vmax, kFrictionPriorVolts + kv * 0.80 * allowedSpeed)
              : Math.min(vmax, 0.5 * vmax);
      return new double[] {
        ramp, clamp(step, kMinStepVolts, vmax), vmax / ramp + 1.0, kVelocityDynamicSeconds
      };
    }

    double band = envelope.bandWidth();
    if (!kvUsable || !(band > 0)) {
      // No usable prior means no way to predict how far the ramp travels. Take the slowest legal
      // ramp and the smallest legal step, which is the conservative corner of the whole space, and
      // let the supervisor's position aborts be the real limit.
      return new double[] {
        kMinRampVoltsPerSecond,
        Math.min(kMinStepVolts, vmax),
        kMaxQuasistaticSeconds,
        kPositionDynamicSeconds
      };
    }

    double ramp =
        clamp(
            vmax * vmax / (2.0 * kQuasistaticBandFraction * band * kv),
            kMinRampVoltsPerSecond,
            kMaxRampVoltsPerSecond);
    double step =
        clamp(
            kDynamicBandFraction * band * kv / kPositionDynamicSeconds + kFrictionPriorVolts,
            kMinStepVolts,
            vmax);
    double quasistatic = clamp(vmax / ramp, kMinQuasistaticSeconds, kMaxQuasistaticSeconds);
    return new double[] {ramp, step, quasistatic, kPositionDynamicSeconds};
  }

  // ===============================================================================================
  // sweep plumbing
  // ===============================================================================================

  private void beginSweep() {
    m_regression.reset();
    m_buffer.clear();
    m_startPosition = m_target.measuredSi();
    m_t0 = Double.NaN;
    m_t1 = Double.NaN;
    startFreshLog();
  }

  private void endSweep(boolean interrupted) {
    m_supervisor.commandVolts(0.0);
    m_supervisor.disarm();
    m_lastLogPath.ifPresent(
        path ->
            Alerts.info(
                kAlertGroup,
                "AdvantageKit logs are not directly loadable by the SysId GUI. Rootstock wrote a "
                    + "separate plain WPILog for you at "
                    + path
                    + (interrupted ? " (the sweep was interrupted before it finished)." : ".")));
  }

  /**
   * Close the current log and open a fresh one named for this mechanism and this minute.
   *
   * <p>Falls back to the persistent directory when no USB stick is mounted, and warns rather than
   * failing. A robot that will not run because logging failed is a lost match.
   */
  private void startFreshLog() {
    Path usb = Path.of("/U/logs");
    Path directory;
    if (Files.isDirectory(usb)) {
      directory = usb;
    } else {
      directory = Platform.persistentDir().resolve("logs");
      Alerts.warning(
          kAlertGroup,
          "No USB stick is mounted at /U, so the SysId log for "
              + m_target.tuningName()
              + " is going to "
              + directory
              + " instead. Fix: plug a USB stick into the roboRIO if you want to pull the log "
              + "without an FTP client.",
          MatchImpact.PIT_ONLY);
    }
    String name =
        "sysid-"
            + m_target.tuningName()
            + "-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + ".wpilog";
    try {
      java.nio.file.Files.createDirectories(directory);
      DataLogManager.stop();
      DataLogManager.start(directory.toString(), name);
      m_lastLogPath = Optional.of(directory.resolve(name).toString());
    } catch (RuntimeException | java.io.IOException e) {
      m_lastLogPath = Optional.empty();
      Alerts.warning(
          kAlertGroup,
          "Could not open a fresh WPILog for the "
              + m_target.tuningName()
              + " sweep ("
              + e.getMessage()
              + "). The sweep will still run and Rootstock will still fit the gains on the robot; "
              + "only the file the SysId GUI would read is missing.",
          MatchImpact.PIT_ONLY);
    }
  }

  /** Neutral (or, on a gravity mechanism, hold) between tests. */
  private Command settle() {
    return Commands.run(() -> m_supervisor.commandVolts(holdVolts()))
        .withTimeout(kSettleSeconds)
        .withName("SysIdSweep/settle");
  }

  /**
   * Walk back to where the sweep started, so the next test has the same room the first one had.
   *
   * <p>Deliberately a slow proportional crawl rather than a closed-loop move: the whole point of the
   * sweep is that the closed loop is not trusted yet, and the supervisor's own limits are what stop
   * this if the crawl misbehaves.
   */
  private Command returnToStart() {
    if (!m_target.archetype().isPosition()) {
      return Commands.none();
    }
    double tolerance = Math.max(m_supervisor.effectiveToleranceSi(), 1e-6);
    return Commands.run(
            () -> {
              double error = m_startPosition - m_target.measuredSi();
              double crawl = Math.max(0.25, 0.15 * Math.abs(m_envelope.maxVolts()));
              double command = Math.signum(error) * Math.min(crawl, Math.abs(error) * crawl * 4.0);
              m_supervisor.commandVolts(holdVolts() + command);
            })
        .until(() -> Math.abs(m_startPosition - m_target.measuredSi()) <= 2.0 * tolerance)
        .withTimeout(kReturnSeconds)
        .andThen(Commands.runOnce(() -> m_supervisor.commandVolts(holdVolts())))
        .withName("SysIdSweep/return");
  }

  private double holdVolts() {
    if (!m_target.archetype().hasGravity()) {
      return 0.0;
    }
    double kg = m_target.plantPrior().gravityVoltsPrior();
    if (!Double.isFinite(kg)) {
      return 0.0;
    }
    return m_target.gravityMode() == GravityMode.COSINE
        ? kg * Math.cos(m_target.measuredSi() - m_target.horizontalReferenceSi())
        : kg;
  }

  /** Forward the phase to the WPILog so the file stays valid, and remember it for the UI. */
  private void recordState(SysIdRoutineLog.State state) {
    m_state = state;
    SysIdRoutine active =
        state == SysIdRoutineLog.State.kDynamicForward
                || state == SysIdRoutineLog.State.kDynamicReverse
            ? m_dynamicRoutine
            : m_quasistaticRoutine;
    if (active != null) {
      active.recordState(state);
    }
  }

  /**
   * The log callback: write the WPILog entries WPILib's analysis needs, and feed the on-robot fit.
   *
   * <p>The voltage written is {@link TuningTarget#appliedVolts()} when the device reports it, and
   * only falls back to the commanded value when it does not. That preference is the whole reason
   * this callback is generated rather than hand-written.
   */
  private void recordMotor(SysIdRoutineLog log) {
    double position = m_target.measuredSi();
    double velocity = m_target.velocitySi();
    OptionalDouble measured = m_target.appliedVolts();
    double applied = measured.orElse(m_supervisor.appliedVolts());

    var motor = log.motor(m_target.tuningName());
    motor.voltage(Volts.of(applied));
    if (m_target.siDomain() == SiDomain.LINEAR_METERS) {
      motor.linearPosition(Meters.of(position)).linearVelocity(MetersPerSecond.of(velocity));
    } else {
      motor.angularPosition(Radians.of(position)).angularVelocity(RadiansPerSecond.of(velocity));
    }
    OptionalDouble amps = m_target.statorCurrentAmps();
    if (amps.isPresent()) {
      motor.current(Amps.of(amps.getAsDouble()));
    }

    accumulate(position, velocity, applied);
  }

  /**
   * Push one sample into the regression, one loop late, so acceleration is a centred difference.
   *
   * <p>{@link TuningTarget#accelerationSi()} is used when the hardware reports one. Very little FRC
   * hardware does, and differencing velocity is what the fit would have to do anyway to trust the
   * number.
   */
  private void accumulate(double position, double velocity, double volts) {
    double now = Clock.seconds();
    double angle = position - m_target.horizontalReferenceSi();

    if (Double.isFinite(m_t0) && Double.isFinite(m_t1)) {
      double span = now - m_t0;
      double reported = m_target.accelerationSi();
      double accel =
          Double.isFinite(reported) ? reported : (span > 1e-6 ? (velocity - m_v0) / span : 0.0);
      m_regression.add(m_angle1, m_v1, accel, m_volts1);
      m_buffer.add(m_t1, Double.NaN, m_pos1, m_v1, m_volts1, m_volts1, Double.NaN);
    }

    m_t0 = m_t1;
    m_v0 = m_v1;
    m_t1 = now;
    m_v1 = velocity;
    m_pos1 = position;
    m_angle1 = angle;
    m_volts1 = volts;
  }

  /**
   * The phase the routine is in, for the UI.
   *
   * @return the most recent state WPILib reported
   */
  public SysIdRoutineLog.State state() {
    return m_state;
  }

  /**
   * A subsystem to hand {@code SysIdRoutine.Mechanism} when the team is not using command-based.
   *
   * <p>{@code Mechanism} requires one and will not accept null. This one owns nothing and does
   * nothing; the real exclusion is the supervisor, which is armed for exactly one mechanism.
   */
  private static Subsystem placeholderSubsystem() {
    return new Subsystem() {
      @Override
      public String getName() {
        return "RootstockTuningPlaceholder";
      }
    };
  }

  /**
   * Clamp, with the ceiling winning when the two bounds cross.
   *
   * <p>A team that lowers {@code maxVolts} below {@link #kMinStepVolts} produces an inverted range,
   * and the ordinary {@code max(low, min(high, x))} would then return the <em>floor</em> — handing
   * the sweep a step voltage above the ceiling the supervisor promised never to exceed. The ceiling
   * is the safety promise, so the ceiling wins.
   */
  private static double clamp(double value, double low, double high) {
    if (high < low) {
      return high;
    }
    if (!Double.isFinite(value)) {
      return low;
    }
    return Math.max(low, Math.min(high, value));
  }
}

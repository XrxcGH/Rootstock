package org.rootstock.control;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.compat.Clock;
import org.rootstock.units.SiDomain;

/**
 * The twelve-second mechanical pre-flight that runs <em>before</em> anybody touches a gain.
 *
 * <p><b>Why this exists.</b> PID control cannot compensate for slop. Every experienced mentor says
 * the same thing — tension the belts and shim the gears first — and nothing in the FRC ecosystem
 * checks it, so a student spends an afternoon chasing an oscillation that is 4 degrees of backlash.
 * Telling them <i>"your chain is loose, fix that before touching kP"</i> is worth more than any gain
 * this library could compute, and it is the one thing a piece of software can measure in twelve
 * seconds that a fourteen-year-old cannot measure by eye.
 *
 * <p><b>Why it lives beside {@link TuningSupervisor} and not with the passive health monitors.</b>
 * It commands raw voltage, so every volt it applies has to pass through the supervisor — the health
 * domain owns no voltage-commanding code by design. The passive half of this idea, "is this
 * mechanism still tuned?", is {@code org.rootstock.tuning.diagnostics.TuningHealth} and <i>is</i> a
 * {@code HealthSource}.
 *
 * <p><b>What it measures</b>, in order, because each test depends on the one before it:
 *
 * <ol>
 *   <li><b>Idle mode read-back</b> — first, because the coast precondition and the gravity probe
 *       both depend on the answer. Absent or COAST on a gravity mechanism is a {@link Verdict#BLOCK}.
 *   <li><b>Sensor liveness and direction</b> — a short push. Either signal frozen, or positive volts
 *       producing negative motion, is a {@link Verdict#BLOCK}: a tuner fitted to a frozen signal
 *       produces confident garbage, and a tuner fitted through an inverted sensor bakes the sign
 *       error into every gain.
 *   <li><b>Breakaway in both directions</b> — the voltage at which motion starts, up and down. Their
 *       ratio is friction asymmetry; the distance between the two start positions is the deadband,
 *       which is backlash.
 *   <li><b>Encoder slip</b> — if an absolute sensor exists, how far the two disagree after the sweep.
 *   <li><b>Gravity presence</b> — borrow COAST for a second and see whether it falls, so a mechanism
 *       declared a turret that is really an arm is caught before its recipe omits kG.
 * </ol>
 */
public final class MechanicalHealthCheck {

  /** Ramp rate used to find breakaway, in volts per second. Slow on purpose: it must not lurch. */
  public static final double kRampVoltsPerSecond = 0.5;

  /** Ceiling on the breakaway ramp, in volts above the gravity hold. */
  public static final double kRampCeilingVolts = 2.0;

  /** Voltage used for the liveness push, in volts above the gravity hold. */
  public static final double kLivenessVolts = 1.5;

  /** How long the liveness push lasts, in seconds. */
  public static final double kLivenessSeconds = 0.4;

  /** How long the mechanism is allowed to settle between the two ramps, in seconds. */
  public static final double kSettleSeconds = 0.5;

  /** How long the mechanism is released for the gravity probe, in seconds. */
  public static final double kGravityProbeSeconds = 1.0;

  /** Backlash above this is worth telling a student about, in radians. */
  public static final double kBacklashWarnRadians = Math.toRadians(2.0);

  /** Backlash above this is worth telling a student about, in metres. */
  public static final double kBacklashWarnMetres = 0.002;

  /** Breakaway-voltage ratio above this means something drags one way, on a gravity-free axis. */
  public static final double kFrictionAsymmetryWarn = 1.6;

  /** Encoder disagreement above this fraction of travel means a belt or chain is skipping. */
  public static final double kEncoderSlipWarnFraction = 0.01;

  /** Gravity drift above this fraction of travel on a "no gravity" archetype is a wrong archetype. */
  public static final double kGravityDriftWarnFraction = 0.02;

  /** The alert group every message from this class lands in. */
  public static final String kAlertGroup = "Tuning";

  /** How bad the news is. The wizard refuses to proceed on {@link #BLOCK}. */
  public enum Verdict {
    /** Nothing found. Tune away. */
    PASS,
    /** Something is worth fixing, but the tuner can still produce meaningful numbers. */
    WARN,
    /** Tuning this mechanism now would produce wrong gains or damage it. Refused. */
    BLOCK
  }

  /**
   * What the check found, in numbers and in sentences.
   *
   * @param verdict the headline; {@link Verdict#BLOCK} means the wizard refuses to continue
   * @param findings one plain-language sentence per problem, each naming the value and the fix
   * @param deadbandSi measured backlash, in metres or radians; NaN when it could not be measured
   * @param breakawayUpVolts volts above the gravity hold at which motion started in the positive
   *     direction; NaN when it never broke away
   * @param breakawayDownVolts the same, in the negative direction
   * @param frictionAsymmetry the larger breakaway divided by the smaller; NaN when unmeasured
   * @param encoderSlipSi how far the absolute and rotor-derived positions disagreed after the
   *     sweep; NaN when there is no absolute sensor
   * @param gravityDriftSi how far the mechanism fell when released; NaN when COAST could not be
   *     borrowed
   * @param idleMode the idle mode read back from the device, or empty if it could not be read
   */
  public record HealthReport(
      Verdict verdict,
      List<String> findings,
      double deadbandSi,
      double breakawayUpVolts,
      double breakawayDownVolts,
      double frictionAsymmetry,
      double encoderSlipSi,
      double gravityDriftSi,
      Optional<NeutralMode> idleMode) {

    /**
     * Defensive canonical constructor: the findings list is copied so a report cannot change after
     * it is handed to the UI or written into the tuning report.
     */
    public HealthReport {
      findings = List.copyOf(findings);
    }

    /**
     * The report as a short paragraph, for the UI and the markdown tuning report.
     *
     * @return the verdict followed by one line per finding
     */
    public String describe() {
      if (findings.isEmpty()) {
        return verdict + ": nothing found.";
      }
      return verdict + ":\n  - " + String.join("\n  - ", findings);
    }
  }

  private enum Phase {
    IDLE_MODE,
    LIVENESS,
    RAMP_UP,
    SETTLE,
    RAMP_DOWN,
    GRAVITY_PROBE,
    FINISH,
    DONE
  }

  private final TuningSupervisor m_supervisor;
  private final TuningTarget m_target;

  private Phase m_phase = Phase.DONE;
  private double m_phaseStart = Double.NaN;
  private double m_phaseStartPosition = Double.NaN;
  private double m_positionAtStart = Double.NaN;
  private double m_absoluteAtStart = Double.NaN;
  private boolean m_coastBorrowed;

  private double m_breakawayUpVolts = Double.NaN;
  private double m_breakawayDownVolts = Double.NaN;
  private double m_breakawayUpPosition = Double.NaN;
  private double m_breakawayDownPosition = Double.NaN;
  private double m_livenessPositionDelta = Double.NaN;
  private boolean m_velocityMovedDuringLiveness;
  private double m_gravityDrift = Double.NaN;

  private final List<String> m_findings = new ArrayList<>();
  private Verdict m_verdict = Verdict.PASS;
  private Optional<HealthReport> m_report = Optional.empty();

  private MechanicalHealthCheck(TuningSupervisor supervisor) {
    m_supervisor = Objects.requireNonNull(supervisor, "supervisor");
    m_target = supervisor.target();
  }

  /**
   * Build a check that will run through the given supervisor.
   *
   * <p>The supervisor is the only reason this is safe: every volt below goes through {@link
   * TuningSupervisor#commandVolts(double)}, and every loop asks {@link TuningSupervisor#check()}
   * first, so a mechanism that is jammed or mis-wired stops on the same eleven conditions a tuning
   * sweep does.
   *
   * @param supervisor an armed-or-armable supervisor for the mechanism to check
   * @return the check
   * @throws NullPointerException if the supervisor is null
   */
  public static MechanicalHealthCheck of(TuningSupervisor supervisor) {
    return new MechanicalHealthCheck(supervisor);
  }

  /**
   * The command that runs the whole check, about twelve seconds of motion.
   *
   * <p>It requires the mechanism's subsystem when the team uses command-based, so the scheduler
   * gives it exclusive control. It always ends by neutralling the mechanism and restoring the idle
   * mode, including on interruption — the supervisor's disarm path does that, and it is the path
   * that runs when a student walks away.
   *
   * <p><b>The supervisor must already be armed.</b> This class never arms it: {@link
   * TuningSupervisor#arm()} throws a specific message for whichever of its eleven preconditions
   * failed, and swallowing that inside a command would replace eleven precise sentences with one
   * vague one. If it is not armed the check records a {@link Verdict#BLOCK} saying so and moves
   * nothing.
   *
   * @return the command; schedule it in Test mode with the enable held
   */
  public Command command() {
    Subsystem[] requirements =
        m_target.requirement().map(s -> new Subsystem[] {s}).orElseGet(() -> new Subsystem[0]);
    return Commands.sequence(
            Commands.runOnce(this::begin, requirements),
            Commands.run(this::step, requirements).until(() -> m_phase == Phase.DONE))
        .finallyDo(this::finish)
        .withName("MechanicalHealthCheck/" + m_target.tuningName());
  }

  /**
   * The report from the most recent run.
   *
   * @return the report, or empty if the check has not finished a run yet
   */
  public Optional<HealthReport> lastReport() {
    return m_report;
  }

  // ===============================================================================================
  // the state machine
  // ===============================================================================================

  private void begin() {
    m_findings.clear();
    m_verdict = Verdict.PASS;
    m_report = Optional.empty();
    m_breakawayUpVolts = Double.NaN;
    m_breakawayDownVolts = Double.NaN;
    m_breakawayUpPosition = Double.NaN;
    m_breakawayDownPosition = Double.NaN;
    m_livenessPositionDelta = Double.NaN;
    m_velocityMovedDuringLiveness = false;
    m_gravityDrift = Double.NaN;
    m_coastBorrowed = false;
    m_positionAtStart = m_target.measuredSi();
    OptionalDouble absolute = m_target.absolutePositionSi();
    m_absoluteAtStart = absolute.isPresent() ? absolute.getAsDouble() : Double.NaN;
    enter(Phase.IDLE_MODE);
  }

  private void step() {
    if (!m_supervisor.isArmed()) {
      // Either the caller never armed, or an abort already fired. Either way there is no
      // authorisation to move, so record what we know and stop rather than looping silently.
      if (m_supervisor.lastAbort().isPresent()) {
        record(Verdict.BLOCK, m_supervisor.lastAbortMessage());
      } else {
        record(
            Verdict.BLOCK,
            m_target.tuningName()
                + ": the mechanical health check was scheduled without an armed supervisor, so it "
                + "was not allowed to move anything and measured nothing. Fix: call "
                + "supervisor.arm() first - it will tell you exactly which precondition is not "
                + "met.");
      }
      enter(Phase.DONE);
      return;
    }
    if (m_supervisor.check().isPresent()) {
      record(Verdict.BLOCK, m_supervisor.lastAbortMessage());
      enter(Phase.DONE);
      return;
    }

    double now = Clock.seconds();
    double elapsed = now - m_phaseStart;
    double position = m_target.measuredSi();
    double velocity = m_target.velocitySi();
    double hold = gravityHoldVolts(position);
    double breakawaySpeed = breakawayVelocityThreshold();

    switch (m_phase) {
      case IDLE_MODE -> {
        checkIdleMode();
        enter(Phase.LIVENESS);
      }
      case LIVENESS -> {
        m_supervisor.commandVolts(hold + kLivenessVolts);
        if (Math.abs(velocity) > breakawaySpeed) {
          m_velocityMovedDuringLiveness = true;
        }
        if (elapsed >= kLivenessSeconds) {
          m_livenessPositionDelta = position - m_phaseStartPosition;
          checkLivenessAndDirection();
          enter(Phase.RAMP_UP);
        }
      }
      case RAMP_UP -> {
        double excess = Math.min(kRampCeilingVolts, kRampVoltsPerSecond * elapsed);
        m_supervisor.commandVolts(hold + excess);
        if (velocity > breakawaySpeed && Double.isNaN(m_breakawayUpVolts)) {
          m_breakawayUpVolts = excess;
          m_breakawayUpPosition = position;
        }
        if (!Double.isNaN(m_breakawayUpVolts) || excess >= kRampCeilingVolts) {
          enter(Phase.SETTLE);
        }
      }
      case SETTLE -> {
        m_supervisor.commandVolts(hold);
        if (elapsed >= kSettleSeconds) {
          enter(Phase.RAMP_DOWN);
        }
      }
      case RAMP_DOWN -> {
        double excess = Math.min(kRampCeilingVolts, kRampVoltsPerSecond * elapsed);
        m_supervisor.commandVolts(hold - excess);
        if (velocity < -breakawaySpeed && Double.isNaN(m_breakawayDownVolts)) {
          m_breakawayDownVolts = excess;
          m_breakawayDownPosition = position;
        }
        if (!Double.isNaN(m_breakawayDownVolts) || excess >= kRampCeilingVolts) {
          checkBacklashAndFriction();
          enter(Phase.GRAVITY_PROBE);
        }
      }
      case GRAVITY_PROBE -> {
        if (!m_coastBorrowed) {
          m_coastBorrowed = m_target.overrideNeutralMode(NeutralMode.COAST);
          if (!m_coastBorrowed) {
            // Cannot release it safely. The breakaway asymmetry already measured above is the
            // documented fallback, so there is nothing further to do here.
            enter(Phase.FINISH);
            return;
          }
        }
        m_supervisor.commandVolts(0.0);
        if (elapsed >= kGravityProbeSeconds) {
          m_gravityDrift = position - m_phaseStartPosition;
          m_target.restoreNeutralMode();
          checkGravity();
          enter(Phase.FINISH);
        }
      }
      case FINISH -> {
        m_supervisor.commandVolts(hold);
        checkEncoderSlip();
        enter(Phase.DONE);
      }
      case DONE -> {
        // Nothing: until() sees this on the same loop and the command ends.
      }
      default -> enter(Phase.DONE);
    }
  }

  private void finish(boolean interrupted) {
    if (m_coastBorrowed) {
      m_target.restoreNeutralMode();
      m_coastBorrowed = false;
    }
    m_supervisor.disarm();
    if (interrupted) {
      record(
          Verdict.WARN,
          m_target.tuningName()
              + ": the mechanical health check was interrupted before it finished, so its results "
              + "are incomplete. Fix: run it again without cancelling it.");
    }
    m_report =
        Optional.of(
            new HealthReport(
                m_verdict,
                List.copyOf(m_findings),
                deadbandSi(),
                m_breakawayUpVolts,
                m_breakawayDownVolts,
                frictionAsymmetry(),
                encoderSlipSi(),
                m_gravityDrift,
                m_target.neutralMode()));
    for (String finding : m_findings) {
      if (m_verdict == Verdict.BLOCK) {
        Alerts.error(kAlertGroup, finding, MatchImpact.PIT_ONLY);
      } else {
        Alerts.warning(kAlertGroup, finding, MatchImpact.PIT_ONLY);
      }
    }
  }

  // ===============================================================================================
  // the individual tests
  // ===============================================================================================

  private void checkIdleMode() {
    if (!m_target.archetype().hasGravity()) {
      return;
    }
    Optional<NeutralMode> mode = m_target.neutralMode();
    if (mode.isEmpty()) {
      record(
          Verdict.BLOCK,
          "I cannot read "
              + m_target.tuningName()
              + "'s idle mode back, and a gravity mechanism that coasts falls when the tuner lets "
              + "go. Fix: implement TuningTarget.neutralMode(), or acknowledge the risk with "
              + "acknowledgeCoastRisk(\"why\").");
    } else if (mode.get() != NeutralMode.BRAKE) {
      record(
          Verdict.BLOCK,
          m_target.tuningName()
              + " is set to coast. The tuner neutrals the mechanism whenever you let go of the "
              + "trigger, and a coasting arm falls. Fix: set NeutralMode.BRAKE in your "
              + "ControlConfig.");
    }
  }

  private void checkLivenessAndDirection() {
    boolean positionMoved =
        Double.isFinite(m_livenessPositionDelta)
            && Math.abs(m_livenessPositionDelta) > 0.5 * m_supervisor.effectiveToleranceSi();
    if (!positionMoved || !m_velocityMovedDuringLiveness) {
      record(
          Verdict.BLOCK,
          String.format(
              Locale.ROOT,
              "%s was pushed with %.1f V for %.1f s and %s did not change. A stale signal reads "
                  + "the same number forever with no error, and a tuner fitted to a stale signal "
                  + "produces confident garbage. Fix: check optimizeBusUtilization and your "
                  + "signal update rates, then check the breaker and the CAN ID.",
              m_target.tuningName(),
              kLivenessVolts,
              kLivenessSeconds,
              positionMoved ? "the velocity" : "the position"));
      return;
    }
    // positionMoved is already true here, so the magnitude is known to be meaningful and only the
    // sign is still in question. Positive volts that produce negative motion is an inversion.
    if (m_livenessPositionDelta < 0.0) {
      record(
          Verdict.BLOCK,
          String.format(
              Locale.ROOT,
              "Positive voltage moves %s in the negative direction (%.4f %s in %.1f s). Fix: "
                  + "invert the motor or the sensor. Do not tune around it - every gain measured "
                  + "through an inverted sensor carries the sign error with it.",
              m_target.tuningName(),
              m_livenessPositionDelta,
              unitLabel(),
              kLivenessSeconds));
    }
  }

  private void checkBacklashAndFriction() {
    double deadband = deadbandSi();
    double warnAt =
        m_target.siDomain() == SiDomain.LINEAR_METERS ? kBacklashWarnMetres : kBacklashWarnRadians;
    if (Double.isFinite(deadband) && deadband > warnAt) {
      record(
          Verdict.WARN,
          String.format(
              Locale.ROOT,
              "About %s of slop on %s - it started moving at two positions %s apart depending on "
                  + "which way it was pushed. Fix: tighten the chain or shim the gears. No PID "
                  + "gain can fix backlash.",
              formatSi(deadband),
              m_target.tuningName(),
              formatSi(deadband)));
    }

    double ratio = frictionAsymmetry();
    if (!m_target.archetype().hasGravity()
        && Double.isFinite(ratio)
        && ratio > kFrictionAsymmetryWarn) {
      record(
          Verdict.WARN,
          String.format(
              Locale.ROOT,
              "It takes %.2f V to move %s one way and %.2f V the other. Something is dragging in "
                  + "one direction. Fix: look for a rubbing cable, a misaligned bearing or an "
                  + "overtight belt before you accept any friction number from the tuner.",
              Math.max(m_breakawayUpVolts, m_breakawayDownVolts),
              m_target.tuningName(),
              Math.min(m_breakawayUpVolts, m_breakawayDownVolts)));
    }

    if (Double.isNaN(m_breakawayUpVolts) || Double.isNaN(m_breakawayDownVolts)) {
      record(
          Verdict.WARN,
          String.format(
              Locale.ROOT,
              "%s never started moving within %.1f V of its holding voltage in at least one "
                  + "direction, so backlash and friction could not be measured. Fix: check for a "
                  + "hard stop right where the mechanism is sitting, then run this again from the "
                  + "middle of its travel.",
              m_target.tuningName(),
              kRampCeilingVolts));
    }
  }

  private void checkEncoderSlip() {
    double slip = encoderSlipSi();
    if (!Double.isFinite(slip)) {
      return;
    }
    double range = m_target.travelLimits().range();
    if (Double.isFinite(range) && range > 0 && slip > kEncoderSlipWarnFraction * range) {
      record(
          Verdict.WARN,
          String.format(
              Locale.ROOT,
              "The motor encoder and the absolute encoder on %s disagree by %s after one short "
                  + "sweep, which is more than %.0f%% of its travel. Fix: a belt or chain is "
                  + "skipping - tension it before you tune, because every gain you measure will "
                  + "drift as it slips further.",
              m_target.tuningName(),
              formatSi(slip),
              kEncoderSlipWarnFraction * 100.0));
    }
  }

  private void checkGravity() {
    if (!Double.isFinite(m_gravityDrift) || m_target.archetype().hasGravity()) {
      return;
    }
    double range = m_target.travelLimits().range();
    if (Double.isFinite(range)
        && range > 0
        && Math.abs(m_gravityDrift) > kGravityDriftWarnFraction * range) {
      record(
          Verdict.WARN,
          String.format(
              Locale.ROOT,
              "%s is configured as a %s, which has no gravity term, but it moved %s on its own "
                  + "when it was released. Fix: is this actually an arm or an elevator? The "
                  + "recipe for those measures kG, and this one does not.",
              m_target.tuningName(),
              m_target.archetype(),
              formatSi(m_gravityDrift)));
    }
  }

  // ===============================================================================================
  // derived quantities
  // ===============================================================================================

  private double deadbandSi() {
    if (Double.isNaN(m_breakawayUpPosition) || Double.isNaN(m_breakawayDownPosition)) {
      return Double.NaN;
    }
    return Math.abs(m_breakawayUpPosition - m_breakawayDownPosition);
  }

  private double frictionAsymmetry() {
    if (!Double.isFinite(m_breakawayUpVolts) || !Double.isFinite(m_breakawayDownVolts)) {
      return Double.NaN;
    }
    double high = Math.max(m_breakawayUpVolts, m_breakawayDownVolts);
    double low = Math.min(m_breakawayUpVolts, m_breakawayDownVolts);
    return low > 1e-6 ? high / low : Double.NaN;
  }

  private double encoderSlipSi() {
    OptionalDouble absolute = m_target.absolutePositionSi();
    if (absolute.isEmpty() || !Double.isFinite(m_absoluteAtStart)) {
      return Double.NaN;
    }
    double absoluteTravel = absolute.getAsDouble() - m_absoluteAtStart;
    double rotorTravel = m_target.measuredSi() - m_positionAtStart;
    return Math.abs(absoluteTravel - rotorTravel);
  }

  private double gravityHoldVolts(double position) {
    if (!m_target.archetype().hasGravity()) {
      return 0.0;
    }
    double kg = m_target.plantPrior().gravityVoltsPrior();
    if (!Double.isFinite(kg)) {
      return 0.0;
    }
    return m_target.gravityMode() == GravityMode.COSINE
        ? kg * Math.cos(position - m_target.horizontalReferenceSi())
        : kg;
  }

  /** "Moving" for the purposes of breakaway: twice the supervisor's own stall deadband. */
  private double breakawayVelocityThreshold() {
    double stall = m_supervisor.envelope().stallVelocityThreshold();
    return Math.max(2.0 * stall, 1e-4);
  }

  private void enter(Phase next) {
    m_phase = next;
    m_phaseStart = Clock.seconds();
    m_phaseStartPosition = m_target.measuredSi();
  }

  private void record(Verdict verdict, String finding) {
    if (finding != null && !finding.isBlank() && !m_findings.contains(finding)) {
      m_findings.add(finding);
    }
    if (verdict.ordinal() > m_verdict.ordinal()) {
      m_verdict = verdict;
    }
  }

  private String formatSi(double value) {
    return m_target.siDomain() == SiDomain.LINEAR_METERS
        ? String.format(Locale.ROOT, "%.1f mm", value * 1000.0)
        : String.format(Locale.ROOT, "%.1f degrees", Math.toDegrees(value));
  }

  private String unitLabel() {
    return m_target.siDomain() == SiDomain.LINEAR_METERS ? "m" : "rad";
  }
}

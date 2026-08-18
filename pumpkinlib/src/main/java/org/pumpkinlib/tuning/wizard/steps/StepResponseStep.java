package org.pumpkinlib.tuning.wizard.steps;

import edu.wpi.first.math.trajectory.TrapezoidProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.control.AbortReason;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.tuning.diagnostics.ResponseClass;
import org.pumpkinlib.tuning.diagnostics.ResponseVerdict;
import org.pumpkinlib.tuning.diagnostics.StepResponseAnalyzer;
import org.pumpkinlib.tuning.diagnostics.TuningHealth;
import org.pumpkinlib.tuning.sysid.SampleBuffer;
import org.pumpkinlib.tuning.wizard.Coach;
import org.pumpkinlib.tuning.wizard.Lessons;
import org.pumpkinlib.tuning.wizard.StepContext;
import org.pumpkinlib.tuning.wizard.StepResult;
import org.pumpkinlib.tuning.wizard.TuningStep;

/**
 * Commands a bounded closed-loop move, classifies what happened, and — in refine mode — improves the
 * gains and does it again.
 *
 * <p>This is where the wizard stops predicting and starts checking. Everything before it is a model:
 * kV and kA describe how the mechanism responds to voltage, and kP and kD are what a solver thinks
 * follows from that. This step closes the loop on the <em>actual</em> mechanism, including
 * everything the linear model does not know about — backlash, stiction, belt compliance, CAN
 * latency, and a battery that sags.
 *
 * <p><b>The update rules are deterministic and bounded, and every one of them has a sentence
 * attached.</b> "Turning kP up by 60%" is a change the student watches happen and can predict the
 * consequence of. A search that wandered would be faster and would teach nothing.
 *
 * <p><b>This is the only class that raises {@link AbortReason#UNSTABLE_RESPONSE}.</b> A response
 * whose oscillations are growing rather than shrinking is how mechanisms break, and the correct
 * reaction is to retreat the gains hard, disarm, and make a human decide to try again — not to keep
 * iterating on a mechanism that is destroying itself.
 */
public final class StepResponseStep implements TuningStep {

  /** How many refinement iterations before the step accepts the best it has seen. */
  public static final int kMaxIterations = 6;

  /** The refinement move, as a fraction of travel, for a position mechanism. */
  public static final double kStepFractionOfTravel = 0.25;

  /** The refinement move's low setpoint, as a fraction of safe speed, for a velocity mechanism. */
  public static final double kVelocityLowFraction = 0.40;

  /** The refinement move's high setpoint, as a fraction of safe speed, for a velocity mechanism. */
  public static final double kVelocityHighFraction = 0.70;

  /** How long to hold at the target before analysing, in seconds. */
  public static final double kSettleWindowSeconds = 1.5;

  /** kP is never raised beyond this multiple of the value the solver suggested. */
  public static final double kMaxKpMultiple = 8.0;

  /** kD is never raised beyond this multiple of the value the solver suggested. */
  public static final double kMaxKdMultiple = 4.0;

  /** What a sluggish response multiplies kP by. */
  public static final double kSluggishKpGain = 1.6;

  /** What a low-frequency oscillation multiplies kP by. */
  public static final double kOscillatingKpCut = 0.6;

  /** What an unstable response retreats kP and kD to. */
  public static final double kUnstableRetreat = 0.4;

  /** What the step does with what it learns. */
  public enum Mode {
    /** Run, classify, and improve the gains, up to {@link #kMaxIterations} times. */
    REFINE,
    /** Run once at each end of the safe band and report. Changes nothing. */
    VERIFY
  }

  private enum Phase {
    MOVE,
    HOLD,
    RETURN,
    DONE,
    FAILED
  }

  private final Mode m_mode;

  private Phase m_phase = Phase.DONE;
  private int m_iteration;
  private double m_phaseElapsed;
  private double m_windowStart;

  private double m_startPos;
  private double m_goalPos;
  private double m_stepMagnitude;
  private double m_velocityLow;
  private double m_velocityHigh;

  private Gains m_gains = Gains.UNTUNED;
  private Gains m_best = Gains.UNTUNED;
  private double m_bestScore = Double.POSITIVE_INFINITY;
  private double m_kpFromSolver;
  private double m_kdFromSolver;
  private int m_ringCount;

  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_state;
  private TrapezoidProfile.State m_goal;
  private double m_lastProfileVelocity;

  private ResponseVerdict m_verdict;
  private TuningHealth m_health;
  private final List<String> m_history = new ArrayList<>();
  private String m_failure = "";

  private StepResponseStep(Mode mode) {
    m_mode = mode;
  }

  /**
   * The refinement step: run, classify, improve, repeat.
   *
   * @return the step
   */
  public static StepResponseStep refine() {
    return new StepResponseStep(Mode.REFINE);
  }

  /**
   * The verification step: run once and report, changing nothing.
   *
   * @return the step
   */
  public static StepResponseStep verify() {
    return new StepResponseStep(Mode.VERIFY);
  }

  /**
   * Which mode this step is in.
   *
   * <p>Published because the express recipe's contract is "no refinement pass", and a test that
   * cannot see the mode cannot check that contract.
   *
   * @return the mode
   */
  public Mode mode() {
    return m_mode;
  }

  /**
   * The most recent classification.
   *
   * @return the verdict, or empty before a move has been analysed
   */
  public Optional<ResponseVerdict> verdict() {
    return Optional.ofNullable(m_verdict);
  }

  /**
   * The iteration-by-iteration history, for the report.
   *
   * @return one line per iteration
   */
  public List<String> history() {
    return List.copyOf(m_history);
  }

  @Override
  public String title() {
    return m_mode == Mode.REFINE ? "Check and refine" : "Full-travel verify";
  }

  @Override
  public Optional<GainId> produces() {
    return m_mode == Mode.REFINE ? Optional.of(GainId.KP) : Optional.empty();
  }

  @Override
  public String explanation() {
    return m_mode == Mode.REFINE
        ? Lessons.MOTION_PROFILES
            + "\n\nThis step commands a real move with the gains you just accepted, watches what "
            + "happens, and tells you in plain language what it saw. If it can be improved with a "
            + "bounded, predictable change, it will make that change and try again - and it will "
            + "always tell you which change and why."
        : Lessons.WHY_UNITS_MATTER
            + "\n\nThis last check changes nothing. It runs the whole safe band, the way a match "
            + "would use it, and reports rise time, overshoot, settle time and final error at both "
            + "ends. An asymmetry between the two directions is the clearest sign there is that a "
            + "gravity term is slightly wrong.";
  }

  @Override
  public String watchFor() {
    return "Two plots. The top one is where you asked it to be against where it actually is; the "
        + "bottom one is the volts. If the bottom plot is flat against its ceiling, none of your "
        + "gains were in the loop at all for that stretch.";
  }

  @Override
  public String willDo() {
    return m_mode == Mode.REFINE
        ? "I will command a profiled move over about a quarter of your travel, hold it, and come "
            + "back - up to six times, adjusting one gain between attempts."
        : "I will run the whole safe band and back again, once, and report the numbers.";
  }

  @Override
  public void begin(StepContext ctx) {
    m_gains = ctx.gains();
    m_best = m_gains;
    m_bestScore = Double.POSITIVE_INFINITY;
    m_kpFromSolver = m_gains.kP();
    m_kdFromSolver = m_gains.kD();
    m_iteration = 0;
    m_ringCount = 0;
    m_verdict = null;
    m_failure = "";
    m_history.clear();
    m_health = TuningHealth.watching(ctx.target());
    beginIteration(ctx);
  }

  @Override
  public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions.
    if (ctx.supervisor().check().isPresent()) {
      m_failure = ctx.supervisor().lastAbortMessage();
      m_phase = Phase.FAILED;
      return;
    }
    m_phaseElapsed += ctx.dt();
    switch (m_phase) {
      case MOVE:
        drive(ctx, m_goalPos, m_velocityHigh);
        if (profileFinished(ctx)) {
          m_phase = Phase.HOLD;
          m_phaseElapsed = 0;
        }
        break;
      case HOLD:
        drive(ctx, m_goalPos, m_velocityHigh);
        if (m_phaseElapsed >= kSettleWindowSeconds) {
          analyse(ctx);
        }
        break;
      case RETURN:
        drive(ctx, m_startPos, m_velocityLow);
        if (m_phaseElapsed >= kSettleWindowSeconds && profileFinished(ctx)) {
          if (m_iteration >= iterationBudget()
              || !Coach.shouldKeepRefining(m_verdict.classification())) {
            m_phase = Phase.DONE;
          } else {
            beginIteration(ctx);
          }
        }
        break;
      case DONE:
      case FAILED:
      default:
        break;
    }
    ctx.publishProgress(Math.min(1.0, (double) m_iteration / iterationBudget()));
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return m_phase == Phase.DONE || m_phase == Phase.FAILED;
  }

  @Override
  public StepResult finish(StepContext ctx) {
    ctx.supervisor().commandVolts(0.0);
    if (m_verdict == null) {
      return StepResult.retry(
          title() + " produced no data",
          m_failure.isEmpty()
              ? "The move did not record enough samples to classify."
              : m_failure,
          "Press B to try again with the trigger held for the whole move.",
          List.of());
    }
    String unit = StepSupport.unitLabel(ctx);
    String diagnosis = Coach.diagnosis(m_verdict, ctx.toleranceSi(), unit);
    if (m_verdict.saturated()) {
      diagnosis = diagnosis + Coach.saturationNote(ctx.supervisor().envelope().maxVolts());
    }
    List<String> warnings = new ArrayList<>(m_history);

    if (m_mode == Mode.VERIFY) {
      return StepResult.informational(
          m_verdict.headline(), diagnosis, Coach.recommendation(m_verdict), warnings);
    }
    if (m_phase == Phase.FAILED && m_verdict.classification() == ResponseClass.UNSTABLE) {
      ctx.proposeGains(m_best);
      return StepResult.retry(
          "Stopped - the response was growing",
          diagnosis,
          Coach.recommendation(m_verdict),
          warnings);
    }
    ctx.proposeGains(m_best);
    return StepResult.success(
        GainId.KP,
        m_best.kP(),
        m_kpFromSolver,
        String.format(
            Locale.ROOT, "kP = %.4f, kD = %.4f after %d attempts", m_best.kP(), m_best.kD(),
            m_iteration),
        String.format(
            Locale.ROOT,
            "rise %.2f s, overshoot %.1f%%, settle %.2f s, final error %.4f %s",
            m_verdict.riseTimeSec(),
            m_verdict.overshootPct(),
            m_verdict.settleTimeSec(),
            Math.abs(m_verdict.steadyStateErrorSi()),
            unit),
        diagnosis,
        Coach.recommendation(m_verdict),
        warnings);
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return iterationBudget() * (2.0 * kSettleWindowSeconds + 2.0);
  }

  // ---- the loop ----------------------------------------------------------------------------------

  private int iterationBudget() {
    return m_mode == Mode.REFINE ? kMaxIterations : 1;
  }

  private void beginIteration(StepContext ctx) {
    m_iteration++;
    m_startPos = ctx.target().measuredSi();
    double range = travelRange(ctx);
    double safeMin = ctx.supervisor().envelope().positionMin();
    double safeMax = ctx.supervisor().envelope().positionMax();

    if (ctx.target().archetype().isPosition()) {
      m_stepMagnitude =
          m_mode == Mode.REFINE
              ? kStepFractionOfTravel * range
              : Math.max(0.0, safeMax - safeMin);
      if (m_mode == Mode.VERIFY) {
        // Sweep the SUPERVISOR band, not the device soft limits. The band is strictly inside them,
        // so a verify move to the soft limit is a move to a position the supervisor aborts before
        // reaching - the verify would trip on every mechanism, every time.
        m_startPos = safeMin;
        m_goalPos = safeMax;
      } else {
        m_goalPos = clamp(m_startPos + m_stepMagnitude, safeMin, safeMax);
        if (Math.abs(m_goalPos - m_startPos) < 0.25 * m_stepMagnitude) {
          m_goalPos = clamp(m_startPos - m_stepMagnitude, safeMin, safeMax);
        }
      }
      double cruise = Math.max(0.25 * StepSupport.freeSpeed(ctx), 1e-3);
      double accel = Math.max(2.0 * cruise, 1e-3);
      m_profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise, accel));
      // The profile starts where the mechanism actually is, not where the move is nominally from.
      // On the verify sweep those differ - it runs the whole band, and starting the profile at the
      // band edge would hand the controller a step demand instead of a plan.
      m_state =
          new TrapezoidProfile.State(ctx.target().measuredSi(), ctx.target().velocitySi());
      m_goal = new TrapezoidProfile.State(m_goalPos, 0);
    } else {
      double vMax = safeSpeed(ctx);
      m_velocityLow = kVelocityLowFraction * vMax;
      m_velocityHigh = kVelocityHighFraction * vMax;
      m_startPos = m_velocityLow;
      m_goalPos = m_velocityHigh;
    }

    m_lastProfileVelocity = 0;
    m_windowStart = Clock.seconds();
    m_phase = Phase.MOVE;
    m_phaseElapsed = 0;
    ctx.buffer().clear();
    ctx.narrate(
        String.format(
            Locale.ROOT,
            "Attempt %d with kP %.4f and kD %.4f.",
            m_iteration,
            m_gains.kP(),
            m_gains.kD()));
  }

  /**
   * One loop of closed-loop control, always through the supervisor.
   *
   * <p>The feedback runs on the RIO even when the mechanism's own loop runs on the motor controller.
   * That is deliberate: the supervisor has to be in the voltage path for the twelve abort conditions
   * to mean anything, and handing a goal to a device would put the device's loop in the path
   * instead.
   */
  private void drive(StepContext ctx, double goal, double velocityGoal) {
    double measured = ctx.target().measuredSi();
    double velocity = ctx.target().velocitySi();
    double setpoint;
    double ff;
    double fb;

    if (ctx.target().archetype().isPosition()) {
      m_goal = new TrapezoidProfile.State(goal, 0);
      m_state = m_profile.calculate(ctx.dt(), m_state, m_goal);
      double accel = ctx.dt() > 0 ? (m_state.velocity - m_lastProfileVelocity) / ctx.dt() : 0.0;
      m_lastProfileVelocity = m_state.velocity;
      setpoint = m_state.position;
      ff =
          m_gains.kS() * Math.signum(m_state.velocity)
              + m_gains.kV() * m_state.velocity
              + m_gains.kA() * accel
              + m_gains.kG() * StepSupport.gravityShape(ctx);
      fb = m_gains.kP() * (setpoint - measured) + m_gains.kD() * (m_state.velocity - velocity);
      ctx.buffer()
          .add(Clock.seconds(), setpoint, measured, velocity, ff + fb, ff, fb);
    } else {
      setpoint = velocityGoal;
      ff = m_gains.kS() * Math.signum(setpoint) + m_gains.kV() * setpoint;
      fb = m_gains.kP() * (setpoint - velocity);
      ctx.buffer()
          .add(Clock.seconds(), setpoint, velocity, ctx.target().accelerationSi(), ff + fb, ff, fb);
    }
    ctx.supervisor().commandVolts(ff + fb);
  }

  private boolean profileFinished(StepContext ctx) {
    if (!ctx.target().archetype().isPosition()) {
      return m_phaseElapsed >= kSettleWindowSeconds;
    }
    return Math.abs(m_state.position - m_goal.position) < 1e-6
        && Math.abs(m_state.velocity) < 1e-6;
  }

  private void analyse(StepContext ctx) {
    List<SampleBuffer.Sample> window = ctx.buffer().window(m_windowStart, Clock.seconds());
    m_verdict =
        StepResponseAnalyzer.analyze(
            window,
            ctx.toleranceSi(),
            m_health.expectedRiseSeconds(),
            m_health.expectedSettleSeconds(),
            ctx.supervisor().envelope().maxVolts(),
            ctx.target().archetype().hasGravity());
    m_health.record(m_verdict);

    double score = cost(m_verdict, ctx);
    if (score < m_bestScore) {
      m_bestScore = score;
      m_best = m_gains;
    }
    m_history.add(
        String.format(
            Locale.ROOT,
            "iter %d: kP %.4f kD %.4f -> %s",
            m_iteration,
            m_gains.kP(),
            m_gains.kD(),
            m_verdict.classification().name()));
    ctx.narrate(m_verdict.headline());

    if (m_mode == Mode.VERIFY) {
      m_phase = Phase.RETURN;
      m_phaseElapsed = 0;
      return;
    }
    if (m_verdict.classification() == ResponseClass.UNSTABLE) {
      // Retreat hard, then hand the decision back to a human. This is the only place in the library
      // that raises this abort.
      m_gains =
          m_gains
              .withKp(kUnstableRetreat * m_gains.kP())
              .withKd(kUnstableRetreat * m_gains.kD());
      m_best = m_gains;
      ctx.supervisor().abort(AbortReason.UNSTABLE_RESPONSE);
      m_phase = Phase.FAILED;
      return;
    }
    if (!Coach.shouldKeepRefining(m_verdict.classification())
        || m_iteration >= kMaxIterations) {
      m_phase = Phase.RETURN;
      m_phaseElapsed = 0;
      return;
    }
    m_gains = update(m_gains, m_verdict, ctx);
    m_phase = Phase.RETURN;
    m_phaseElapsed = 0;
  }

  /**
   * The deterministic, bounded update rules.
   *
   * <p>Each branch is paired with the sentence the student sees. There is no search here and no
   * randomness: a student has to be able to predict what the next attempt will do, which is the
   * whole reason to prefer a small table of rules over an optimiser.
   */
  private Gains update(Gains g, ResponseVerdict v, StepContext ctx) {
    switch (v.classification()) {
      case SLUGGISH: {
        double kp = Math.min(kSluggishKpGain * g.kP(), kMaxKpMultiple * m_kpFromSolver);
        ctx.narrate("It is getting there, just slowly. Turning kP up by 60%.");
        return g.withKp(kp);
      }
      case OVERSHOOT_RING: {
        m_ringCount++;
        if (g.kD() <= 0) {
          double kd = 0.35 * Math.max(m_kdFromSolver, g.kV());
          ctx.narrate(
              "It overshoots and rings. That is a spring with no shock absorber - adding some kD.");
          return g.withKd(kd);
        }
        if (m_ringCount <= 2) {
          double kd = Math.min(1.5 * g.kD(), kMaxKdMultiple * Math.max(m_kdFromSolver, 1e-9));
          ctx.narrate("Still ringing. More damping.");
          return g.withKd(kd);
        }
        ctx.narrate("More damping is not helping, so the spring is just too stiff. Cutting kP by 30%.");
        return g.withKp(0.7 * g.kP());
      }
      case OSCILLATING: {
        if (v.oscillationHz() >= Coach.kNoiseFrequencyHz) {
          ctx.narrate(
              String.format(
                  Locale.ROOT,
                  "It is buzzing at %.0f Hz. That is too fast to be the mechanism - kD is "
                      + "amplifying sensor noise. Halving kD.",
                  v.oscillationHz()));
          return g.withKd(0.5 * g.kD());
        }
        ctx.narrate(
            String.format(
                Locale.ROOT,
                "It is swinging back and forth about %.1f times a second. That is a kP that is too "
                    + "high. Cutting it by 40%%.",
                v.oscillationHz()));
        return g.withKp(kOscillatingKpCut * g.kP()).withKd(0.8 * g.kD());
      }
      case STEADY_STATE_ERROR: {
        Optional<Double> residual = ctx.target().getFeedbackVolts();
        if (residual.isEmpty()) {
          ctx.narrate(
              "This mechanism's loop does not report how it split feedforward from feedback, so I "
                  + "cannot tell you whether the leftover error is friction or gravity. Run it with "
                  + "ControlLocation.RIO_FULL for one session if you want that diagnosis.");
          return g;
        }
        double r = Math.abs(v.residualVolts());
        if (ctx.target().archetype().hasGravity() && v.steadyStateErrorSi() > 0) {
          double delta = clamp(0.6 * r, 0, 0.30);
          ctx.narrate(
              String.format(
                  Locale.ROOT,
                  "It settles low every time and holds %.2f V doing it. That is gravity your kG is "
                      + "not paying for. Raising kG by %.3f V.",
                  r,
                  delta));
          return g.withKg(g.kG() + delta);
        }
        double delta = clamp(0.6 * r, 0, 0.20);
        ctx.narrate(
            String.format(
                Locale.ROOT,
                "It stops just short every time, and the controller is holding a steady %.2f V "
                    + "trying to close the gap. That voltage is friction your kS is not paying "
                    + "for. Raising kS by %.3f V.",
                r,
                delta));
        return g.withKs(g.kS() + delta);
      }
      case GOOD:
      case INSUFFICIENT_EXCITATION:
      case UNSTABLE:
      default:
        return g;
    }
  }

  /**
   * The scalar used to pick the best iteration when none of them reach a good response.
   *
   * <p>The weights punish steady-state error and ringing harder than being slow, on purpose: a small
   * team's mechanism that is twenty percent slow and rock solid is a better outcome than one that is
   * fast and rings.
   */
  private double cost(ResponseVerdict v, StepContext ctx) {
    double expectedSettle = Math.max(m_health.expectedSettleSeconds(), 1e-3);
    double tolerance = Math.max(ctx.toleranceSi(), 1e-9);
    return v.settleTimeSec() / expectedSettle
        + 2.0 * Math.max(0, v.overshootPct() - 5.0) / 100.0
        + 3.0 * Math.abs(v.steadyStateErrorSi()) / tolerance
        + 5.0 * (v.oscillationCrossings() > 4 ? 1.0 : 0.0);
  }

  private double safeSpeed(StepContext ctx) {
    double envelope = ctx.supervisor().envelope().maxAbsVelocity();
    double free = StepSupport.freeSpeed(ctx);
    double candidate = Double.isFinite(envelope) && envelope > 0 ? envelope / 1.15 : Double.NaN;
    if (Double.isFinite(free) && free > 0) {
      candidate = Double.isFinite(candidate) ? Math.min(candidate, free) : free;
    }
    return Double.isFinite(candidate) && candidate > 0 ? candidate : 1.0;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static double travelRange(StepContext ctx) {
    var limits = ctx.target().travelLimits();
    double range = limits == null ? Double.NaN : limits.range();
    return Double.isFinite(range) && range > 0 ? range : 1.0;
  }
}

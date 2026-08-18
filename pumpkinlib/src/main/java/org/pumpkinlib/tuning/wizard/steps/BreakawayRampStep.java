package org.pumpkinlib.tuning.wizard.steps;

import edu.wpi.first.math.trajectory.TrapezoidProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.tuning.wizard.Lessons;
import org.pumpkinlib.tuning.wizard.StepContext;
import org.pumpkinlib.tuning.wizard.StepResult;
import org.pumpkinlib.tuning.wizard.TuningStep;

/**
 * Finds kS — and, on a gravity mechanism, the sign of kG as a by-product.
 *
 * <p>Ramps voltage up from zero at a slow, fixed rate until motion is detected, in both directions,
 * and averages the two answers. Slow on purpose: at {@value #kRampVoltsPerSecond} V/s the mechanism
 * is never accelerating hard enough for inertia to contribute, so the voltage at the moment it moves
 * is friction and nothing else.
 *
 * <p><b>Gravity is cancelled first, and that is not optional.</b> The ramp commands {@code u +
 * gravityCompensation()}, where the compensation is the kG accumulated so far — constant for an
 * elevator, cosine-shaped for an arm, zero otherwise. Without it, the "motion" the step detects is
 * the carriage <em>falling</em>, and kS comes out about ten times too large. This is why an elevator
 * recipe runs the gravity pre-pass before this step rather than after it, even though the student
 * learns the gains in the canonical order.
 *
 * <p><b>The raw breakaway magnitudes are also the gravity-sign fallback.</b> On a gravity mechanism
 * with no compensation applied yet, the voltage needed to break loose <em>toward</em> gravity is
 * {@code |kS - |kG||} and the voltage needed to break loose <em>against</em> it is {@code kS +
 * |kG|}. The direction that needs more voltage is the direction gravity opposes, which is the
 * direction kG must push. That is an exact identity and not an approximation:
 *
 * <pre>
 *   (kS + |kG|) - |kS - |kG|| = 2 * min(kS, |kG|)
 * </pre>
 *
 * <p>It gives the sign reliably for any non-zero kG and a lower bound on the magnitude, which is
 * all {@link HoldBisectionStep} needs, because the bisection finds the value. It works with the
 * brake engaged, because a brake changes the <em>neutral</em> state and not a commanded voltage —
 * which is exactly why it is the fallback when the coast borrow is unavailable.
 */
public final class BreakawayRampStep implements TuningStep {

  /** Ramp rate, in volts per second. Slow enough that inertia contributes nothing. */
  public static final double kRampVoltsPerSecond = 0.30;

  /** How long to sit still measuring the velocity signal's own noise, in seconds. */
  public static final double kNoiseWindowSeconds = 0.5;

  /** How long to let the mechanism settle after each direction, in seconds. */
  public static final double kSettleSeconds = 0.5;

  /** Motion counts at this fraction of the predicted free speed, or three noise sigmas, whichever is larger. */
  public static final double kMoveFractionOfFreeSpeed = 0.02;

  /** Asymmetry above this ratio is worth telling the student about. */
  public static final double kAsymmetryWarning = 1.6;

  /** The ramp gives up at this fraction of the envelope's voltage ceiling. */
  public static final double kCeilingFraction = 0.9;

  private enum Phase {
    NOISE,
    RAMP,
    SETTLE,
    RETURN,
    DONE,
    FAILED
  }

  private Phase m_phase = Phase.NOISE;
  private int m_directionIndex;
  private double m_u;
  private double m_phaseElapsed;

  private double m_noiseSum;
  private double m_noiseSumSquares;
  private int m_noiseCount;
  private double m_noiseStdDev = Double.NaN;
  private double m_moveThreshold = Double.NaN;

  private final double[] m_uBreak = {Double.NaN, Double.NaN};
  private final double[] m_kS = {Double.NaN, Double.NaN};

  private double m_startPos = Double.NaN;
  private double m_ceiling = Double.NaN;
  private double m_kP;
  private double m_returnBudget;
  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_state;
  private TrapezoidProfile.State m_goal;
  private String m_failure = "";

  /** Creates the step. Recipes build one of these per mechanism. */
  public BreakawayRampStep() {}

  /**
   * The raw breakaway magnitude in the positive direction, before any gravity term is subtracted.
   *
   * @return the magnitude in volts, or NaN if that direction has not run
   */
  public double uBreakPositive() {
    return m_uBreak[0];
  }

  /**
   * The raw breakaway magnitude in the negative direction, before any gravity term is subtracted.
   *
   * @return the magnitude in volts, or NaN if that direction has not run
   */
  public double uBreakNegative() {
    return m_uBreak[1];
  }

  /**
   * The measured standard deviation of the velocity signal at rest.
   *
   * <p>Published because the gravity-sign fallback needs to know whether a difference between the
   * two breakaway magnitudes is real or is noise, and guessing at that threshold is how a wrong kG
   * sign gets baked into a mechanism.
   *
   * @return the standard deviation in SI units per second, or NaN before the noise window
   */
  public double breakawayNoiseStdDev() {
    return m_noiseStdDev;
  }

  /**
   * Whether the sub-machine has finished, successfully or not.
   *
   * @return true when there is nothing left to run
   */
  public boolean isDone() {
    return m_phase == Phase.DONE || m_phase == Phase.FAILED;
  }

  /**
   * Whether the sub-machine gave up.
   *
   * @return true if it failed
   */
  public boolean isFailed() {
    return m_phase == Phase.FAILED;
  }

  /**
   * The averaged kS, once both directions have run.
   *
   * @return kS in volts, or empty
   */
  public Optional<Double> kS() {
    if (!Double.isFinite(m_kS[0]) || !Double.isFinite(m_kS[1])) {
      return Optional.empty();
    }
    return Optional.of(0.5 * (m_kS[0] + m_kS[1]));
  }

  @Override
  public String title() {
    return "Find kS";
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.of(GainId.KS);
  }

  @Override
  public String explanation() {
    return Lessons.KS;
  }

  @Override
  public String watchFor() {
    return "Watch for the exact moment it starts to move. Everything before that moment is the "
        + "motor pushing against friction and losing.";
  }

  @Override
  public String willDo() {
    return String.format(
        Locale.ROOT,
        "I will raise the voltage from zero at %.2f volts every second until it moves, then do the "
            + "same in the other direction, and average the two.",
        kRampVoltsPerSecond);
  }

  @Override
  public void begin(StepContext ctx) {
    m_phase = Phase.NOISE;
    m_directionIndex = 0;
    m_u = 0;
    m_phaseElapsed = 0;
    m_noiseSum = 0;
    m_noiseSumSquares = 0;
    m_noiseCount = 0;
    m_noiseStdDev = Double.NaN;
    m_moveThreshold = Double.NaN;
    m_uBreak[0] = Double.NaN;
    m_uBreak[1] = Double.NaN;
    m_kS[0] = Double.NaN;
    m_kS[1] = Double.NaN;
    m_failure = "";
    m_startPos = ctx.target().measuredSi();
    m_ceiling = kCeilingFraction * ctx.supervisor().envelope().maxVolts();
    m_kP = StepSupport.provisionalKp(ctx);
    double free = StepSupport.freeSpeed(ctx);
    double cruise = Double.isFinite(free) && free > 0 ? 0.25 * free : 0.1;
    double accel = Math.max(cruise * 2.0, 1e-3);
    m_profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise, accel));
    m_returnBudget = 3.0 * StepSupport.profileDuration(0.1, cruise, accel) + 1.0;
    ctx.narrate(
        "Sitting still for half a second first, to find out how noisy this mechanism's speed "
            + "reading is. Anything smaller than that noise is not motion.");
  }

  @Override
  public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions. The twelve abort conditions are only live
    // because this line runs.
    if (ctx.supervisor().check().isPresent()) {
      m_failure = ctx.supervisor().lastAbortMessage();
      m_phase = Phase.FAILED;
      return;
    }
    m_phaseElapsed += ctx.dt();
    switch (m_phase) {
      case NOISE:
        noise(ctx);
        break;
      case RAMP:
        ramp(ctx);
        break;
      case SETTLE:
        settle(ctx);
        break;
      case RETURN:
        returnToStart(ctx);
        break;
      case DONE:
      case FAILED:
      default:
        break;
    }
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return isDone();
  }

  @Override
  public StepResult finish(StepContext ctx) {
    if (m_phase == Phase.FAILED) {
      return StepResult.retry(
          "kS not measured",
          m_failure.isEmpty()
              ? "The ramp stopped before the mechanism broke loose."
              : m_failure,
          "Check that nothing is jammed, then press B to try again.",
          List.of());
    }
    Optional<Double> ks = kS();
    if (ks.isEmpty()) {
      return StepResult.retry(
          "kS not measured",
          "Only one direction produced a breakaway voltage, so there is nothing to average.",
          "Press B to run it again.",
          List.of());
    }
    double value = Math.max(0.0, ks.get());
    double up = Math.abs(m_kS[0]);
    double down = Math.abs(m_kS[1]);
    double asymmetry = Math.max(up, down) / Math.max(Math.min(up, down), 1e-9);

    List<String> warnings = new ArrayList<>();
    if (asymmetry > kAsymmetryWarning) {
      warnings.add(
          String.format(
              Locale.ROOT,
              "It takes %.3f V to break loose one way and %.3f V the other - %.1f times as much. "
                  + "Something is dragging in one direction. Run the mechanical health check before "
                  + "you trust any gain from here on.",
              up,
              down,
              asymmetry));
    }
    if (value > 1.0) {
      warnings.add(
          String.format(
              Locale.ROOT,
              "kS came out at %.3f V, which is high for an FRC mechanism - most land between 0.1 V "
                  + "and 0.8 V. Worth checking belt tension before you accept it.",
              value));
    }

    return StepResult.success(
        GainId.KS,
        value,
        ctx.gains().kS(),
        String.format(Locale.ROOT, "kS = %.3f V", value),
        String.format(
            Locale.ROOT,
            "%.3f V one way, %.3f V the other, %.0f%% asymmetry",
            up,
            down,
            100.0 * (asymmetry - 1.0)),
        String.format(
            Locale.ROOT,
            "That is the voltage it takes to break friction loose. Below %.3f V this mechanism "
                + "will hum and not move, which is exactly the symptom kS exists to remove.",
            value),
        "Press A to keep it, or B to measure it again.",
        warnings);
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    // Two ramps, two settles, two returns, plus the noise window. A ramp to a typical 0.3 V kS at
    // 0.3 V/s is a second; budget four for a stiff one.
    return kNoiseWindowSeconds + 2.0 * (4.0 + kSettleSeconds + 1.5);
  }

  // ---- phases ---------------------------------------------------------------------------------

  private void noise(StepContext ctx) {
    double v = ctx.target().velocitySi();
    m_noiseSum += v;
    m_noiseSumSquares += v * v;
    m_noiseCount++;
    // Hold against gravity while we listen. Commanding zero on an elevator during the noise window
    // would measure the noise of a falling carriage.
    ctx.supervisor().commandVolts(StepSupport.gravityCompensation(ctx));
    ctx.publishProgress(Math.min(1.0, m_phaseElapsed / kNoiseWindowSeconds) * 0.1);
    if (m_phaseElapsed < kNoiseWindowSeconds) {
      return;
    }
    m_noiseStdDev = StepSupport.stdDev(m_noiseSum, m_noiseSumSquares, m_noiseCount);
    double free = StepSupport.freeSpeed(ctx);
    double fromFreeSpeed =
        Double.isFinite(free) && free > 0 ? kMoveFractionOfFreeSpeed * free : 0.0;
    m_moveThreshold =
        Math.max(fromFreeSpeed, StepSupport.driftDeadband(ctx, m_noiseStdDev));
    ctx.narrate(
        String.format(
            Locale.ROOT,
            "Speed noise is %.4f. I will call anything faster than %.4f real motion. Ramping up "
                + "now - watch for the moment it breaks loose.",
            Double.isFinite(m_noiseStdDev) ? m_noiseStdDev : 0.0,
            m_moveThreshold));
    beginRamp(ctx);
  }

  private void beginRamp(StepContext ctx) {
    m_u = 0;
    m_phase = Phase.RAMP;
    m_phaseElapsed = 0;
    m_startPos = ctx.target().measuredSi();
  }

  private void ramp(StepContext ctx) {
    double direction = m_directionIndex == 0 ? 1.0 : -1.0;
    m_u += kRampVoltsPerSecond * ctx.dt();
    double gravity = StepSupport.gravityCompensation(ctx);
    ctx.supervisor().commandVolts(direction * m_u + gravity);
    ctx.publishProgress(0.1 + 0.45 * m_directionIndex + 0.3 * Math.min(1.0, m_u / 2.0));

    if (Math.abs(ctx.target().velocitySi()) >= m_moveThreshold) {
      m_uBreak[m_directionIndex] = m_u;
      m_kS[m_directionIndex] = Math.max(0.0, m_u);
      ctx.narrate(
          String.format(
              Locale.ROOT,
              "Broke loose at %.3f V going %s.",
              m_u,
              m_directionIndex == 0 ? "positive" : "negative"));
      m_phase = Phase.SETTLE;
      m_phaseElapsed = 0;
      return;
    }
    if (m_u > m_ceiling) {
      m_failure =
          String.format(
              Locale.ROOT,
              "I ramped all the way to %.2f V and this mechanism never moved. That is not friction, "
                  + "that is something holding it: a jam, a hard stop it is already sitting on, or "
                  + "a mechanism that is not actually connected to this motor.",
              m_u);
      m_phase = Phase.FAILED;
    }
  }

  private void settle(StepContext ctx) {
    ctx.supervisor().commandVolts(StepSupport.gravityCompensation(ctx));
    if (m_phaseElapsed < kSettleSeconds) {
      return;
    }
    if (ctx.target().archetype().isPosition()) {
      m_state =
          new TrapezoidProfile.State(ctx.target().measuredSi(), ctx.target().velocitySi());
      m_goal = new TrapezoidProfile.State(m_startPos, 0);
      m_phase = Phase.RETURN;
      m_phaseElapsed = 0;
      return;
    }
    nextDirection(ctx);
  }

  private void returnToStart(StepContext ctx) {
    // Closed loop, always. Open-loop recentring on a gravity mechanism is the same hazard the ramp
    // itself is, and "command zero and hope" is a release.
    m_state = m_profile.calculate(ctx.dt(), m_state, m_goal);
    double feedforward = StepSupport.gravityCompensation(ctx);
    double feedback = m_kP * (m_state.position - ctx.target().measuredSi());
    ctx.supervisor().commandVolts(feedforward + feedback);

    double band = Math.max(ctx.toleranceSi(), 0.005 * travelRange(ctx));
    if (Math.abs(ctx.target().measuredSi() - m_startPos) <= band
        || m_phaseElapsed > m_returnBudget) {
      nextDirection(ctx);
    }
  }

  private void nextDirection(StepContext ctx) {
    ctx.supervisor().commandVolts(StepSupport.gravityCompensation(ctx));
    if (m_directionIndex == 0) {
      m_directionIndex = 1;
      beginRamp(ctx);
      return;
    }
    m_phase = Phase.DONE;
  }

  private static double travelRange(StepContext ctx) {
    var limits = ctx.target().travelLimits();
    double range = limits == null ? Double.NaN : limits.range();
    return Double.isFinite(range) && range > 0 ? range : 1.0;
  }
}

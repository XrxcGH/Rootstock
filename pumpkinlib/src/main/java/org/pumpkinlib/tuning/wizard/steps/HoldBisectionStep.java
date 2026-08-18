package org.pumpkinlib.tuning.wizard.steps;

import edu.wpi.first.math.trajectory.TrapezoidProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.control.PlantPrior;
import org.pumpkinlib.tuning.wizard.Lessons;
import org.pumpkinlib.tuning.wizard.StepContext;
import org.pumpkinlib.tuning.wizard.StepResult;
import org.pumpkinlib.tuning.wizard.TuningStep;

/**
 * Finds kG to three decimal places without slamming a hard stop.
 *
 * <p>This is the step that most clearly demonstrates why tuning on the robot beats redeploying and
 * guessing. WPILib's own vertical-arm guide says you must zero in on kG to about four decimal
 * places; twelve deploys will not get you there and five seconds of bisection will.
 *
 * <p>It is also, by a wide margin, the most dangerous step in the library, because it is
 * <b>open-loop voltage on a mechanism that gravity is actively pulling on</b>. The naive version of
 * this step would destroy a real arm on iteration one, and it is worth writing down exactly how,
 * because every safeguard below is shaped by that failure.
 *
 * <p><b>The failure, walked through on a real arm</b> with kV = 0.85 V/(rad/s), 120 degrees of
 * travel and a true kG near 1.2 V. Bracket from the supply voltage and iteration one commands {@code
 * 0.5 * 0.6 * 12 = 3.6 V}, which is 2.4 V of net excess over what gravity needs. Steady speed is
 * about {@code 2.4 / 0.85 = 2.8 rad/s}, or 160 degrees per second. A settle-then-measure window of
 * half a second at that speed travels seventy to eighty degrees — on a mechanism with a hundred and
 * twenty degrees of travel — and a position guard evaluated only <em>after</em> that window cannot
 * intervene. Worse, the supervisor's "taper to zero near the band edge" means, on a gravity
 * mechanism, <em>releasing it</em>.
 *
 * <p><b>Five changes, all present below.</b>
 *
 * <ol>
 *   <li><b>Bracket from physics, not from the supply.</b> {@code PlantPrior.gravityVoltsPrior()}
 *       already knows roughly what this load needs, so the bracket is {@code [0.2x, 1.8x]} of it
 *       rather than "anything this motor can produce." Iteration one then commands roughly the right
 *       voltage and the net excess is near zero rather than 2.4 V.
 *   <li><b>The probe is a bounded pulse with an in-window guard, and an abort is itself a
 *       measurement.</b> Position is checked every loop, not after. Hitting the {@value
 *       #kGuardFractionOfTravel} guard does not waste the iteration: <em>which way it moved is the
 *       answer the iteration was asking for.</em>
 *   <li><b>Recentring is a real closed-loop move</b> with a time budget, using a provisional gain
 *       from the same physics prior. Open-loop recentring on a gravity mechanism is the same hazard
 *       the probe is.
 *   <li><b>The sign probe reads back the idle mode, borrows coast under supervision, and never
 *       takes the kG = 0 shortcut on a gravity archetype.</b> Setting kG = 0 makes the friction step
 *       measure gravity as friction, and kS comes out about ten times too large.
 *   <li><b>The whole step is an explicit sub-state machine</b>, because it has to be: there is no
 *       yield to write, threads are banned, and a blocking loop would leave the twelve abort
 *       conditions inert for the duration of the most dangerous thing in the library.
 * </ol>
 *
 * <p><b>Resolution.</b> The bracket spans {@code 1.8 - 0.2 = 1.6} times the prior, and {@value
 * #kIterations} halvings land inside {@code 1.6 / 2^10 = 1/640} of it — about one part in six
 * hundred of the range the mechanism's own mass and gearing predict. More iterations would resolve a
 * number more precisely than the encoder can see it while giving the mechanism more chances to run
 * into something.
 */
public final class HoldBisectionStep implements TuningStep {

  /** How many halvings. Ten, and the lesson text says ten; see the class javadoc for why. */
  public static final int kIterations = 10;

  /** How long each probe holds a candidate voltage, in seconds. */
  public static final double kProbeSeconds = 0.35;

  /** How long to wait inside a probe before the drift measurement starts, in seconds. */
  public static final double kSettleSeconds = 0.10;

  /** The coast-borrow window used to see which way the mechanism falls, in seconds. */
  public static final double kDriftSeconds = 0.50;

  /** The longer window used when coast could not be borrowed, in seconds. */
  public static final double kDriftSecondsBrakeRetry = 1.20;

  /** The probe ends the moment the mechanism has moved this fraction of its travel. */
  public static final double kGuardFractionOfTravel = 0.03;

  /** Lower end of the physics bracket, as a multiple of the gravity prior. */
  public static final double kBracketLow = 0.2;

  /** Upper end of the physics bracket, as a multiple of the gravity prior. */
  public static final double kBracketHigh = 1.8;

  /** Backstop ceiling on the bracket, as a fraction of nominal battery voltage. */
  public static final double kCeilingFractionOfNominal = 0.6;

  private enum Phase {
    SIGN_DRIFT,
    SIGN_BREAKAWAY,
    PROBE,
    RECENTRE,
    DONE,
    FAILED
  }

  /**
   * The result of one bounded probe.
   *
   * <p>An aborted probe is not a wasted iteration: the direction the mechanism moved before the
   * guard tripped is exactly the comparison the bisection was about to make, so the bracket updates
   * either way. That is why tightening the guard costs nothing in convergence.
   */
  private sealed interface ProbeOutcome {
    /** The probe ran to completion and measured a mean drift. */
    record Measured(double drift) implements ProbeOutcome {}

    /** The guard tripped with the mechanism having moved positive. */
    record AbortedUp() implements ProbeOutcome {}

    /** The guard tripped with the mechanism having moved negative. */
    record AbortedDown() implements ProbeOutcome {}
  }

  private Phase m_phase = Phase.SIGN_DRIFT;
  private int m_iteration;
  private double m_lo;
  private double m_hi;
  private double m_mid;
  private double m_gSign;
  private double m_startPos;
  private double m_range;
  private double m_phaseElapsed;
  private double m_sumVel;
  private int m_velSamples;
  private boolean m_coastBorrowed;
  private int m_guardTrips;
  private String m_failure = "";
  private final List<String> m_warnings = new ArrayList<>();

  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_state;
  private TrapezoidProfile.State m_goal;
  private double m_kP;
  private double m_recentreBudget;
  private double m_settleBand;

  private BreakawayRampStep m_breakaway;

  /**
   * The best kG known right now.
   *
   * <p>Seeded from {@code gSign * kGprior} before the first probe — never zero — because recentring
   * and the supervisor's gravity-aware taper both use it to <em>hold</em> the mechanism, and a zero
   * here is a release.
   */
  private double m_kGbest;

  /** Creates the step. Recipes build one of these per gravity mechanism. */
  public HoldBisectionStep() {}

  /**
   * The holding voltage found, once the bisection has converged.
   *
   * @return the signed kG in volts, or empty
   */
  public Optional<Double> holdingVolts() {
    return Double.isFinite(m_kGbest) && m_phase == Phase.DONE
        ? Optional.of(m_kGbest)
        : Optional.empty();
  }

  /**
   * How many probes ended on the travel guard rather than on a measurement.
   *
   * <p>Reported to the student because it is a real diagnostic: a mechanism whose every probe trips
   * the guard is one whose prior is badly wrong, and saying so beats silently converging on the
   * bracket edge.
   *
   * @return the count
   */
  public int guardTrips() {
    return m_guardTrips;
  }

  @Override
  public String title() {
    return "Gravity pre-pass";
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.of(GainId.KG);
  }

  @Override
  public String explanation() {
    return Lessons.KG;
  }

  @Override
  public String watchFor() {
    return "Watch the mechanism hang almost perfectly still. Every twitch you see is one halving of "
        + "the search, and there are ten of them.";
  }

  @Override
  public String willDo() {
    return "I will let go for half a second to see which way it falls, then hold it at a series of "
        + "candidate voltages, narrowing in on the one that keeps it still. It never drifts more "
        + "than a thirtieth of its travel.";
  }

  @Override
  public void begin(StepContext ctx) {
    m_startPos = ctx.target().measuredSi();
    m_range = travelRange(ctx);
    m_settleBand = Math.max(ctx.toleranceSi(), 0.005 * m_range);
    m_kP = StepSupport.provisionalKp(ctx);
    m_iteration = 0;
    m_guardTrips = 0;
    m_coastBorrowed = false;
    m_failure = "";
    m_warnings.clear();
    m_gSign = 0;
    m_breakaway = null;

    PlantPrior prior = ctx.target().plantPrior();
    double cruise = 0.25 * StepSupport.freeSpeed(ctx);
    double accel = prior == null ? Double.NaN : 0.25 * prior.maxAccelSi();
    if (!(cruise > 0) || !Double.isFinite(cruise)) {
      cruise = 0.05 * m_range;
    }
    if (!(accel > 0) || !Double.isFinite(accel)) {
      accel = 2.0 * cruise;
    }
    m_profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise, accel));
    // Three times the gentle profile's own duration for the worst move the guard permits, plus half
    // a second. Bounded twice: this budget, and a settle band wider than the raw tolerance.
    m_recentreBudget =
        3.0 * StepSupport.profileDuration(kGuardFractionOfTravel * m_range, cruise, accel) + 0.5;

    // No sign yet; the sign phases set kGbest before anything is held.
    m_kGbest = 0.0;

    if (ctx.target().overrideNeutralMode(NeutralMode.COAST)) {
      beginCoastBorrow(ctx);
    } else {
      ctx.narrate(
          "This mechanism will not let me borrow coast mode, so I cannot watch it fall. I will "
              + "work out which way gravity pulls from how hard it is to break loose in each "
              + "direction instead.");
      beginBreakawayFallback(ctx);
    }
  }

  @Override
  public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions. The twelve abort conditions are only live
    // because this line runs.
    if (ctx.supervisor().check().isPresent()) {
      m_failure = ctx.supervisor().lastAbortMessage();
      failTo(ctx, Phase.FAILED);
      return;
    }
    m_phaseElapsed += ctx.dt();
    switch (m_phase) {
      case SIGN_DRIFT:
        signDrift(ctx);
        break;
      case SIGN_BREAKAWAY:
        signBreakaway(ctx);
        break;
      case PROBE:
        probe(ctx);
        break;
      case RECENTRE:
        recentre(ctx);
        break;
      case DONE:
      case FAILED:
      default:
        break;
    }
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return m_phase == Phase.DONE || m_phase == Phase.FAILED;
  }

  @Override
  public StepResult finish(StepContext ctx) {
    releaseCoastBorrow(ctx);
    if (m_phase == Phase.FAILED) {
      return StepResult.retry(
          "kG not found",
          m_failure.isEmpty() ? "The gravity search stopped early." : m_failure,
          "Fix whatever the message names, then press B to run it again. Do not skip this step on "
              + "an elevator or an arm - every measurement after it depends on gravity being "
              + "cancelled.",
          List.copyOf(m_warnings));
    }
    double resolution = Math.abs(m_hi - m_lo);
    List<String> warnings = new ArrayList<>(m_warnings);
    if (m_guardTrips > kIterations / 2) {
      warnings.add(
          String.format(
              Locale.ROOT,
              "%d of the %d probes ended on the travel guard rather than on a still mechanism. The "
                  + "mass or the gear ratio in your config is probably some way off, because the "
                  + "search bracket comes straight from them.",
              m_guardTrips,
              kIterations));
    }
    return StepResult.success(
        GainId.KG,
        m_kGbest,
        ctx.gains().kG(),
        String.format(Locale.ROOT, "kG = %.4f V", m_kGbest),
        String.format(
            Locale.ROOT,
            "%d halvings, converged to within %.5f V",
            m_iteration,
            Double.isFinite(resolution) ? resolution : 0.0),
        String.format(
            Locale.ROOT,
            "That is the voltage it takes to hold this still and do nothing else. Gravity pulls "
                + "%s here, so kG pushes the other way. Everything measured from now on gets this "
                + "added first, which is why the friction measurement will be friction and not "
                + "weight.",
            m_gSign > 0 ? "negative" : "positive"),
        "Press A to keep it. The full fit at the end will solve for kG again using all the data, "
            + "and the two answers agreeing is a good sign that your model is right.",
        warnings);
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return kDriftSeconds + kIterations * (kProbeSeconds + 0.15);
  }

  // ---- step 0a: which way does this thing fall? (coast borrowed) -------------------------------

  private void beginCoastBorrow(StepContext ctx) {
    m_coastBorrowed = true;
    ctx.narrate(
        "Switching to coast and letting go for half a second, to see which way this falls. Brake "
            + "mode goes back on the moment I have the answer.");
    ctx.target().stop();
    m_phase = Phase.SIGN_DRIFT;
    m_phaseElapsed = 0;
    m_sumVel = 0;
    m_velSamples = 0;
  }

  private void signDrift(StepContext ctx) {
    m_sumVel += ctx.target().velocitySi();
    m_velSamples++;
    double window = m_coastBorrowed ? kDriftSeconds : kDriftSecondsBrakeRetry;
    if (m_phaseElapsed < window) {
      return;
    }
    double meanVel = m_sumVel / Math.max(m_velSamples, 1);
    releaseCoastBorrow(ctx); // brake back on BEFORE anything else

    if (Math.abs(meanVel) >= StepSupport.driftDeadband(ctx, Double.NaN)) {
      // Falls negative means kG must push positive.
      m_gSign = Math.signum(-meanVel);
      ctx.narrate(
          String.format(
              Locale.ROOT,
              "It drifted %s when I let go, so gravity pulls that way and kG has to push the "
                  + "other.",
              meanVel < 0 ? "downward in your units" : "upward in your units"));
      beginBisection(ctx);
      return;
    }
    // High-reduction gearboxes hold themselves; stiction at the rotor can exceed back-driven
    // gravity torque. Fall through to the breakaway measurement.
    ctx.narrate(
        "It did not move when I let go - your gearbox is holding it against gravity. I will find "
            + "which way gravity pulls from how hard it is to break loose in each direction "
            + "instead.");
    beginBreakawayFallback(ctx);
  }

  private void releaseCoastBorrow(StepContext ctx) {
    if (m_coastBorrowed) {
      ctx.target().restoreNeutralMode();
      m_coastBorrowed = false;
    }
  }

  // ---- step 0b: the brake-mode / stiction fallback ---------------------------------------------

  private void beginBreakawayFallback(StepContext ctx) {
    m_breakaway = new BreakawayRampStep();
    m_breakaway.begin(ctx);
    m_phase = Phase.SIGN_BREAKAWAY;
    m_phaseElapsed = 0;
  }

  private void signBreakaway(StepContext ctx) {
    m_breakaway.periodic(ctx);
    if (!m_breakaway.isDone()) {
      return;
    }
    if (m_breakaway.isFailed()) {
      m_failure =
          "I could not break this mechanism loose in either direction, so I cannot tell which way "
              + "gravity pulls. Check that nothing is jammed and that the motor is actually "
              + "connected to this mechanism.";
      failTo(ctx, Phase.FAILED);
      return;
    }
    double positive = m_breakaway.uBreakPositive();
    double negative = m_breakaway.uBreakNegative();
    double diff = positive - negative;
    double noise = m_breakaway.breakawayNoiseStdDev();
    double threshold = 3.0 * (Double.isFinite(noise) && noise > 0 ? noise : 0.01);

    if (Math.abs(diff) < threshold) {
      if (!ctx.target().archetype().hasGravity()) {
        // TURRET, FLYWHEEL, STEER, DRIVE_VELOCITY. This is the ONLY archetype family for which
        // kG = 0 is a legitimate wizard output.
        ctx.narrate(
            "This mechanism does not move when released and breaks loose equally hard in both "
                + "directions, so there is nothing for kG to hold. Setting kG = 0.");
        m_kGbest = 0.0;
        m_gSign = 0.0;
        m_phase = Phase.DONE;
        return;
      }
      // ELEVATOR and ARM: kG = 0 is never the answer. It would make the friction step measure
      // gravity as friction, so kS would come out about ten times too large, and it would leave the
      // joint fit with a gravity-shaped residual that corrupts kV and kA as well.
      m_failure =
          "I could not tell which way gravity pulls: this mechanism does not drift when released "
              + "and breaks loose equally hard in both directions. That usually means a very high "
              + "reduction or a lot of stiction. I am NOT setting kG to zero - an elevator or an "
              + "arm always has a kG. Check that the archetype is right, or run the mechanical "
              + "health check.";
      failTo(ctx, Phase.FAILED);
      return;
    }
    m_gSign = Math.signum(diff);
    ctx.narrate(
        String.format(
            Locale.ROOT,
            "It takes %.3f V to break loose one way and %.3f V the other. The harder direction is "
                + "the one gravity is fighting, so that is the way kG has to push.",
            Math.abs(positive),
            Math.abs(negative)));
    beginBisection(ctx);
  }

  // ---- the bisection ---------------------------------------------------------------------------

  private void beginBisection(StepContext ctx) {
    PlantPrior prior = ctx.target().plantPrior();
    double kGprior = prior == null ? Double.NaN : prior.gravityVoltsPrior();
    double nominal = prior == null || !Double.isFinite(prior.nominalVolts()) ? 12.0 : prior.nominalVolts();
    if (!Double.isFinite(kGprior) || kGprior <= 0) {
      // No usable prior. Fall back to the old supply-voltage bracket and say so, rather than
      // silently pretending the physics bracket applied.
      kGprior = 0.25 * nominal;
      m_warnings.add(
          "Your mechanism's mass or gearing is missing from its PlantPrior, so I could not bracket "
              + "the search from physics and had to guess from the battery voltage instead. Fill in "
              + "the mass and the reduction and this step gets both faster and safer.");
    }
    m_lo = m_gSign * kBracketLow * kGprior;
    m_hi = m_gSign * Math.min(kCeilingFractionOfNominal * nominal, kBracketHigh * kGprior);
    // Hold at the prior from the very first loop, never at zero.
    m_kGbest = m_gSign * kGprior;
    ctx.supervisor().setGravityHoldVolts(m_kGbest);
    m_iteration = 0;
    ctx.narrate(
        String.format(
            Locale.ROOT,
            "Your mechanism's own mass and gearing say the answer is near %.4f V, so I am searching "
                + "between %.4f V and %.4f V. Ten halvings from here.",
            kGprior,
            Math.min(m_lo, m_hi),
            Math.max(m_lo, m_hi)));
    beginProbe(ctx);
  }

  private void beginProbe(StepContext ctx) {
    m_mid = 0.5 * (m_lo + m_hi);
    m_phase = Phase.PROBE;
    m_phaseElapsed = 0;
    m_sumVel = 0;
    m_velSamples = 0;
    m_startPos = ctx.target().measuredSi();
    ctx.publishProgress(Math.min(1.0, (double) m_iteration / kIterations));
  }

  private void probe(StepContext ctx) {
    double delta = ctx.target().measuredSi() - m_startPos;
    if (Math.abs(delta) > kGuardFractionOfTravel * m_range) {
      m_guardTrips++;
      holdAtBest(ctx); // hold at kGbest -- never release
      narrowBracket(
          delta > 0 ? new ProbeOutcome.AbortedUp() : new ProbeOutcome.AbortedDown(), 0.0, ctx);
      beginRecentre(ctx);
      return;
    }
    ctx.supervisor().commandVolts(m_mid * gravityShape(ctx));
    if (m_phaseElapsed > kSettleSeconds) {
      m_sumVel += ctx.target().velocitySi();
      m_velSamples++;
    }
    if (m_phaseElapsed < kProbeSeconds) {
      return;
    }
    double drift = m_sumVel / Math.max(m_velSamples, 1);
    if (Math.abs(drift) < StepSupport.driftDeadband(ctx, Double.NaN)) {
      m_kGbest = m_mid;
      ctx.supervisor().setGravityHoldVolts(m_kGbest);
      m_phase = Phase.DONE;
      return;
    }
    narrowBracket(new ProbeOutcome.Measured(drift), drift, ctx);
    beginRecentre(ctx);
  }

  /**
   * Narrow the bracket from one probe.
   *
   * <p>Java 17: {@code instanceof} patterns only. A pattern-matching switch is a preview feature in
   * 17 and PumpkinLib uses no preview features anywhere.
   *
   * <p>Guard tripped: the direction of the abort is the measurement. "Moved up" means we over-pushed
   * against gravity, so the midpoint is too many volts.
   */
  private void narrowBracket(ProbeOutcome out, double drift, StepContext ctx) {
    if (out instanceof ProbeOutcome.AbortedUp) {
      if (m_gSign > 0) {
        m_hi = m_mid;
      } else {
        m_lo = m_mid;
      }
    } else if (out instanceof ProbeOutcome.AbortedDown) {
      if (m_gSign > 0) {
        m_lo = m_mid;
      } else {
        m_hi = m_mid;
      }
    } else if (out instanceof ProbeOutcome.Measured) {
      if (m_gSign * drift < 0) {
        m_lo = m_mid;
      } else {
        m_hi = m_mid;
      }
    }
    // Hold at the new best, not the old one.
    m_kGbest = 0.5 * (m_lo + m_hi);
    ctx.supervisor().setGravityHoldVolts(m_kGbest);
  }

  private void beginRecentre(StepContext ctx) {
    m_state = new TrapezoidProfile.State(ctx.target().measuredSi(), ctx.target().velocitySi());
    m_goal = new TrapezoidProfile.State(m_startPos, 0);
    m_phase = Phase.RECENTRE;
    m_phaseElapsed = 0;
  }

  /**
   * Walk the mechanism back to where the probe started, under closed loop, one loop at a time.
   *
   * <p>Bounded twice: the settle band is {@code max(tolerance, 0.5% of travel)} rather than the raw
   * tolerance, and there is a wall-clock budget. A mechanism whose stiction holds it just outside
   * tolerance is entirely likely — the provisional gain comes from a prior that may be three times
   * wrong — and without both bounds it would apply voltage forever.
   */
  private void recentre(StepContext ctx) {
    m_state = m_profile.calculate(ctx.dt(), m_state, m_goal);
    double feedforward = m_kGbest * gravityShape(ctx); // hold, always
    double feedback = m_kP * (m_state.position - ctx.target().measuredSi());
    ctx.supervisor().commandVolts(feedforward + feedback);

    if (Math.abs(ctx.target().measuredSi() - m_startPos) <= m_settleBand) {
      m_iteration++;
      if (m_iteration >= kIterations) {
        m_kGbest = 0.5 * (m_lo + m_hi);
        ctx.supervisor().setGravityHoldVolts(m_kGbest);
        m_phase = Phase.DONE;
      } else {
        beginProbe(ctx);
      }
      return;
    }
    if (m_phaseElapsed > m_recentreBudget) {
      m_failure =
          String.format(
              Locale.ROOT,
              "I could not walk this mechanism back to where the probe started within %.1f s. "
                  + "Something is holding it - usually stiction larger than the provisional gain "
                  + "times your tolerance. Holding at kG = %.4f V and stopping here.",
              m_recentreBudget,
              m_kGbest);
      ctx.narrate(m_failure);
      failTo(ctx, Phase.FAILED);
    }
  }

  // ---- exits ------------------------------------------------------------------------------------

  private void holdAtBest(StepContext ctx) {
    // Hold at the best kG known, never release. A taper to zero on a gravity mechanism is a drop.
    ctx.supervisor().commandVolts(m_kGbest * gravityShape(ctx));
  }

  private void failTo(StepContext ctx, Phase p) {
    releaseCoastBorrow(ctx); // brake back on before ANY neutral
    m_phase = p;
  }

  private double gravityShape(StepContext ctx) {
    double shape = StepSupport.gravityShape(ctx);
    // A gravity archetype whose configured mode is NONE would otherwise multiply every probe by
    // zero and "converge" on a mechanism that never moved because nothing was ever commanded.
    return ctx.target().archetype().hasGravity() && shape == 0.0 ? 1.0 : shape;
  }

  private static double travelRange(StepContext ctx) {
    var limits = ctx.target().travelLimits();
    double range = limits == null ? Double.NaN : limits.range();
    return Double.isFinite(range) && range > 0 ? range : 1.0;
  }
}

package org.rootstock.tuning.wizard.steps;

import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.tuning.sysid.FeedforwardFit;
import org.rootstock.tuning.sysid.IdentificationException;
import org.rootstock.tuning.sysid.SampleBuffer;
import org.rootstock.tuning.sysid.SysIdSweep;
import org.rootstock.tuning.wizard.Lessons;
import org.rootstock.tuning.wizard.StepContext;
import org.rootstock.tuning.wizard.StepResult;
import org.rootstock.tuning.wizard.TuningStep;

/**
 * Finds kV and kA, presented to the student as two separate steps.
 *
 * <p>One sweep, two lessons. The quasistatic half ramps the voltage up very slowly and teaches the
 * price of speed; the dynamic half steps the voltage suddenly and teaches the price of getting
 * moving. Splitting them is a small pedagogical trick with real value: the student sees each gain
 * appear out of a motion they watched, rather than out of one indivisible sixty-second event.
 *
 * <p><b>But the arithmetic is not split.</b> The kV step reports a <em>provisional</em> kV from a
 * two-column fit over the ramp samples alone, because that is all the data that exists at that
 * point. The kA step then solves the full model over <em>all four</em> tests at once, which is the
 * statistically correct thing to do, and says so out loud: <i>"kV refined from 5.11 to 5.00 now that
 * we have the step data too."</i> A student who watches a number they were shown get corrected in
 * front of them learns something about measurement that no amount of prose delivers.
 *
 * <p>Both halves share one {@link Session}, which is what lets the second solve see the first
 * half's samples. Recipes build one session and hand it to both steps.
 */
public final class SysIdSweepStep implements TuningStep {

  /** How long to let a velocity mechanism spin down between directions, in seconds. */
  public static final double kSpinDownSeconds = 3.0;

  /** How long a return-to-start move is allowed to take before the step gives up, in seconds. */
  public static final double kReturnBudgetSeconds = 8.0;

  /** Which half of the sweep a step runs. */
  public enum Half {
    /** The slow ramps, both directions. Produces a provisional kV. */
    QUASISTATIC,
    /** The sudden steps, both directions. Produces kA and refines kV against everything. */
    DYNAMIC
  }

  /**
   * The state the two halves share.
   *
   * <p>Exists because the regression has to accumulate across all four tests. Building two
   * independent sweeps would give the dynamic half a fit over the step data alone, which is exactly
   * the badly conditioned fit this design goes out of its way to avoid.
   */
  public static final class Session {
    private SysIdSweep m_sweep;
    private FeedforwardFit m_fit;
    private double m_provisionalKv = Double.NaN;

    /** Creates an empty session. The first step to run fills it in. */
    public Session() {}

    /**
     * The underlying sweep, once a step has built it.
     *
     * @return the sweep, or empty
     */
    public Optional<SysIdSweep> sweep() {
      return Optional.ofNullable(m_sweep);
    }

    /**
     * The joint fit from the dynamic half, once it has run.
     *
     * @return the fit, or empty
     */
    public Optional<FeedforwardFit> fit() {
      return Optional.ofNullable(m_fit);
    }

    /**
     * The provisional kV the quasistatic half reported.
     *
     * @return the provisional value, or NaN
     */
    public double provisionalKv() {
      return m_provisionalKv;
    }
  }

  private enum Phase {
    FORWARD,
    RETURN_ONE,
    REVERSE,
    RETURN_TWO,
    DONE,
    FAILED
  }

  private final Half m_half;
  private final Session m_session;

  private Phase m_phase = Phase.DONE;
  private Command m_command;
  private double m_phaseElapsed;
  private double m_startPos = Double.NaN;
  private double m_kP;
  private double m_settleBand;
  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_state;
  private TrapezoidProfile.State m_goal;
  private String m_failure = "";

  private SysIdSweepStep(Half half, Session session) {
    m_half = half;
    m_session = session;
  }

  /**
   * The kV step: the two slow ramps.
   *
   * @param session shared with the kA step
   * @return the step
   */
  public static SysIdSweepStep quasistatic(Session session) {
    return new SysIdSweepStep(Half.QUASISTATIC, session);
  }

  /**
   * The kA step: the two sudden voltage steps, plus the joint solve.
   *
   * @param session shared with the kV step
   * @return the step
   */
  public static SysIdSweepStep dynamic(Session session) {
    return new SysIdSweepStep(Half.DYNAMIC, session);
  }

  @Override
  public String title() {
    return m_half == Half.QUASISTATIC ? "Find kV" : "Find kA";
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.of(m_half == Half.QUASISTATIC ? GainId.KV : GainId.KA);
  }

  @Override
  public String explanation() {
    return m_half == Half.QUASISTATIC ? Lessons.KV : Lessons.KA;
  }

  @Override
  public String watchFor() {
    return m_half == Half.QUASISTATIC
        ? "Watch it settle into a steady speed and then climb, slowly. Every point on that line is "
            + "one voltage and one speed, and the slope between them is kV."
        : "Watch the first quarter of a second. That is the only part of this that tells us "
            + "anything about mass - after that it is just cruising.";
  }

  @Override
  public String willDo() {
    return m_half == Half.QUASISTATIC
        ? "I will ramp the voltage up slowly, one way and then the other, returning to the start "
            + "in between."
        : "I will step the voltage suddenly, one way and then the other. This one looks and sounds "
            + "more violent than the others, and it is over in about a second and a half each way.";
  }

  @Override
  public void begin(StepContext ctx) {
    m_failure = "";
    m_phaseElapsed = 0;
    m_startPos = ctx.target().measuredSi();
    m_kP = StepSupport.provisionalKp(ctx);
    m_settleBand = Math.max(ctx.toleranceSi(), 0.005 * travelRange(ctx));

    double cruise = 0.25 * StepSupport.freeSpeed(ctx);
    if (!(cruise > 0) || !Double.isFinite(cruise)) {
      cruise = 0.05 * travelRange(ctx);
    }
    double accel = Math.max(2.0 * cruise, 1e-3);
    m_profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(cruise, accel));

    if (m_session.m_sweep == null) {
      m_session.m_sweep = SysIdSweep.of(ctx.supervisor());
    }
    if (m_half == Half.QUASISTATIC) {
      // Start the accumulation clean. The dynamic half deliberately does NOT reset, because its
      // solve is supposed to see the ramp data too.
      m_session.m_sweep.regression().reset();
      m_session.m_sweep.buffer().clear();
      m_session.m_fit = null;
      m_session.m_provisionalKv = Double.NaN;
    }

    m_command = commandFor(SysIdRoutine.Direction.kForward);
    m_command.initialize();
    m_phase = Phase.FORWARD;
    ctx.narrate(
        m_half == Half.QUASISTATIC
            ? "Ramping slowly. Slowly on purpose: if I ramped quickly, some of the voltage would be "
                + "going into speeding up rather than into holding speed, and we could not tell "
                + "the two apart."
            : "Stepping the voltage. Only the first moment of this matters - once it is cruising, "
                + "mass contributes nothing at all.");
  }

  @Override
  public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions.
    if (ctx.supervisor().check().isPresent()) {
      m_failure = ctx.supervisor().lastAbortMessage();
      endCommand(true);
      m_phase = Phase.FAILED;
      return;
    }
    m_phaseElapsed += ctx.dt();
    switch (m_phase) {
      case FORWARD:
        if (runCommand()) {
          beginReturn(ctx, Phase.RETURN_ONE);
        }
        ctx.publishProgress(0.25);
        break;
      case RETURN_ONE:
        if (recentre(ctx)) {
          m_command = commandFor(SysIdRoutine.Direction.kReverse);
          m_command.initialize();
          m_phase = Phase.REVERSE;
          m_phaseElapsed = 0;
        }
        ctx.publishProgress(0.5);
        break;
      case REVERSE:
        if (runCommand()) {
          beginReturn(ctx, Phase.RETURN_TWO);
        }
        ctx.publishProgress(0.75);
        break;
      case RETURN_TWO:
        if (recentre(ctx)) {
          m_phase = Phase.DONE;
        }
        ctx.publishProgress(1.0);
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
    if (m_phase == Phase.FAILED) {
      return StepResult.retry(
          title() + " did not finish",
          m_failure.isEmpty() ? "The sweep stopped early." : m_failure,
          "Fix whatever the message names and press B to run it again.",
          List.of());
    }
    return m_half == Half.QUASISTATIC ? finishQuasistatic(ctx) : finishDynamic(ctx);
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return m_half == Half.QUASISTATIC ? 26.0 : 14.0;
  }

  // ---- the two solves ---------------------------------------------------------------------------

  /**
   * The provisional kV, from a two-column fit over the ramp samples alone.
   *
   * <p>Written out here rather than delegated to the joint regression because the joint regression
   * has an acceleration column, and a pure ramp has essentially no acceleration in it — the column
   * is nearly constant, the solve is rank-deficient, and the correct response to that is to fit the
   * two columns the data actually supports rather than to report a failure the student cannot act
   * on. The joint fit happens one step later, when the step data exists.
   */
  private StepResult finishQuasistatic(StepContext ctx) {
    double[] fit = fitTwoColumn(ctx);
    if (fit == null) {
      return StepResult.retry(
          "kV not measured",
          "The ramp did not produce enough moving samples to fit a line through. Usually that means "
              + "the mechanism never broke loose, or it hit the edge of its safe band almost "
              + "immediately.",
          "Press B to run it again. If it fails the same way, check the kS you accepted.",
          List.of());
    }
    double kS = fit[0];
    double kV = fit[1];
    m_session.m_provisionalKv = kV;

    List<String> warnings = new ArrayList<>();
    if (!(kV > 0)) {
      warnings.add(
          "kV came out at or below zero, which is not physical: it would mean the mechanism goes "
              + "faster the less you push it. Your encoder or your motor direction is inverted. Fix "
              + "the invert before tuning anything else.");
    }
    return StepResult.success(
        GainId.KV,
        kV,
        ctx.gains().kV(),
        String.format(Locale.ROOT, "kV = %.4f %s", kV, GainId.KV.unitFor(ctx.target().siDomain())),
        "Provisional - ramp data only",
        String.format(
            Locale.ROOT,
            "That is the price of speed: %.4f volts for every unit of speed you ask for, forever, "
                + "just to hold it. The same fit says friction is about %.3f V, which should look "
                + "familiar from the kS step.",
            kV,
            Math.abs(kS)),
        "Press A. The next step measures mass, and then I will re-solve kV using everything at "
            + "once - do not be surprised when this number moves a little.",
        warnings);
  }

  private StepResult finishDynamic(StepContext ctx) {
    SysIdSweep sweep = m_session.m_sweep;
    FeedforwardFit fit;
    try {
      fit = sweep.regression().solve();
    } catch (IdentificationException e) {
      return StepResult.retry(
          "kA not measured",
          e.getMessage(),
          "Press B to run the whole sweep again, starting from the kV step.",
          List.of());
    }
    double gravitySign = Math.signum(ctx.gains().kG() == 0 ? 1.0 : ctx.gains().kG());
    fit = fit.sanityBounded(ctx.target().plantPrior(), ctx.target().archetype(), gravitySign);
    fit =
        fit.validatedAgainst(
            sweep.buffer().all(), ctx.target().archetype(), ctx.target().horizontalReferenceSi());
    m_session.m_fit = fit;

    // Stage the whole refined set. kA is what this step "produces" for the accept/revert path, but
    // the joint solve legitimately improves kV and kG at the same time and hiding that would mean
    // shipping the student a kV we know to be worse than the one we have.
    Gains staged = fit.appliedTo(ctx.gains());
    ctx.proposeGains(staged);

    List<String> warnings = new ArrayList<>(fit.warnings());
    if (Double.isFinite(m_session.m_provisionalKv) && m_session.m_provisionalKv > 0) {
      ctx.narrate(
          String.format(
              Locale.ROOT,
              "kV refined from %.4f to %.4f now that we have the step data too.",
              m_session.m_provisionalKv,
              fit.kV()));
    }
    if (ctx.target().archetype().hasGravity() && ctx.gains().kG() != 0) {
      double prepass = ctx.gains().kG();
      double joint = fit.kG();
      double disagreement =
          Math.abs(prepass) > 1e-9 ? Math.abs(joint - prepass) / Math.abs(prepass) : Double.NaN;
      if (Double.isFinite(disagreement) && disagreement > 0.15) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "The gravity pre-pass said kG = %.4f V but the full fit says %.4f V. They should "
                    + "agree. This usually means the mechanism is not purely constant-gravity - a "
                    + "cascading elevator with a constant-force spring, for example - or that "
                    + "something is binding at one end of travel.",
                prepass,
                joint));
      } else if (Double.isFinite(disagreement)) {
        ctx.narrate(
            String.format(
                Locale.ROOT,
                "Gravity pre-pass said kG = %.4f V; the full fit says %.4f V. Those agree to %.1f%%, "
                    + "which is a good sign that your model is right.",
                prepass,
                joint,
                100.0 * disagreement));
      }
    }

    return StepResult.success(
        GainId.KA,
        fit.kA(),
        ctx.gains().kA(),
        String.format(Locale.ROOT, "kA = %.4f %s", fit.kA(), GainId.KA.unitFor(ctx.target().siDomain())),
        String.format(
            Locale.ROOT,
            "Fit R2 %.3f, RMSE %.3f V, %d samples - %s",
            fit.voltageFitR2(),
            fit.rmseVolts(),
            fit.samples(),
            fit.quality().name().toLowerCase(Locale.ROOT)),
        String.format(
            Locale.ROOT,
            "That is the price of getting moving: %.4f extra volts for every unit of speed change "
                + "per second. %s",
            fit.kA(),
            fit.quality().explain()),
        fit.acceptable()
            ? "Press A. Every feedforward number is now measured, and the controller can predict "
                + "most of the voltage a move needs before it starts."
            : "Press B and run the sweep again - this fit is not good enough to build feedback on, "
                + "and a bad kA makes every gain after it wrong.",
        warnings);
  }

  /**
   * Ordinary least squares on {@code volts = kS * sign(v) + kV * v} over the moving samples.
   *
   * @return {kS, kV}, or null if there were not enough moving samples to fit anything
   */
  private double[] fitTwoColumn(StepContext ctx) {
    List<SampleBuffer.Sample> samples = m_session.m_sweep.buffer().all();
    double threshold = StepSupport.driftDeadband(ctx, Double.NaN);
    double kG = ctx.gains().kG();
    GravityMode mode = ctx.target().gravityMode();
    double href = ctx.target().horizontalReferenceSi();

    double s11 = 0;
    double s12 = 0;
    double s22 = 0;
    double b1 = 0;
    double b2 = 0;
    int n = 0;
    for (SampleBuffer.Sample s : samples) {
      double v = s.velocity();
      if (!Double.isFinite(v) || Math.abs(v) < threshold) {
        continue;
      }
      double gravity =
          mode == GravityMode.NONE
              ? 0.0
              : kG * (mode == GravityMode.COSINE ? Math.cos(s.measurement() - href) : 1.0);
      double u = s.commandedVolts() - gravity;
      double x1 = Math.signum(v);
      double x2 = v;
      s11 += x1 * x1;
      s12 += x1 * x2;
      s22 += x2 * x2;
      b1 += x1 * u;
      b2 += x2 * u;
      n++;
    }
    if (n < 50) {
      return null;
    }
    double det = s11 * s22 - s12 * s12;
    if (Math.abs(det) < 1e-12) {
      return null;
    }
    double kS = (b1 * s22 - b2 * s12) / det;
    double kV = (s11 * b2 - s12 * b1) / det;
    return new double[] {kS, kV};
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private Command commandFor(SysIdRoutine.Direction direction) {
    return m_half == Half.QUASISTATIC
        ? m_session.m_sweep.quasistatic(direction)
        : m_session.m_sweep.dynamic(direction);
  }

  private boolean runCommand() {
    if (m_command == null) {
      return true;
    }
    if (m_command.isFinished()) {
      endCommand(false);
      return true;
    }
    m_command.execute();
    return false;
  }

  private void endCommand(boolean interrupted) {
    if (m_command != null) {
      m_command.end(interrupted);
      m_command = null;
    }
  }

  private void beginReturn(StepContext ctx, Phase next) {
    m_phase = next;
    m_phaseElapsed = 0;
    m_state = new TrapezoidProfile.State(ctx.target().measuredSi(), ctx.target().velocitySi());
    m_goal = new TrapezoidProfile.State(m_startPos, 0);
  }

  /**
   * Walk back to where the sweep started, closed loop and time-budgeted. A velocity mechanism has
   * nowhere to walk back to, so it just spins down instead.
   *
   * @return true when it is time to move on
   */
  private boolean recentre(StepContext ctx) {
    if (!ctx.target().archetype().isPosition()) {
      ctx.supervisor().commandVolts(0.0);
      return m_phaseElapsed > kSpinDownSeconds
          || Math.abs(ctx.target().velocitySi())
              < StepSupport.driftDeadband(ctx, Double.NaN);
    }
    m_state = m_profile.calculate(ctx.dt(), m_state, m_goal);
    double feedforward = StepSupport.gravityCompensation(ctx);
    double feedback = m_kP * (m_state.position - ctx.target().measuredSi());
    ctx.supervisor().commandVolts(feedforward + feedback);
    return Math.abs(ctx.target().measuredSi() - m_startPos) <= m_settleBand
        || m_phaseElapsed > kReturnBudgetSeconds;
  }

  private static double travelRange(StepContext ctx) {
    var limits = ctx.target().travelLimits();
    double range = limits == null ? Double.NaN : limits.range();
    return Double.isFinite(range) && range > 0 ? range : 1.0;
  }
}

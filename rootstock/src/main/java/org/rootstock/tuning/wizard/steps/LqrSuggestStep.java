package org.rootstock.tuning.wizard.steps;

import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.LinearQuadraticRegulator;
import edu.wpi.first.math.system.plant.LinearSystemId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.control.TravelLimits;
import org.rootstock.tuning.TunableDouble;
import org.rootstock.tuning.TuningRegistry;
import org.rootstock.tuning.wizard.Lessons;
import org.rootstock.tuning.wizard.StepContext;
import org.rootstock.tuning.wizard.StepResult;
import org.rootstock.tuning.wizard.TuningStep;

/**
 * Proposes kP and kD from the measured kV and kA — and refuses to be an oracle about it.
 *
 * <p><b>The failure this class is written against.</b> The obvious version of this step takes two
 * sliders, runs a solver, and prints a number. That is a black box in the worst possible place: kP
 * is the gain students most need intuition about, and handing it over unexplained teaches a student
 * that gains come from software rather than from physics. So this step prints the second-order
 * interpretation <em>above</em> the gains, in the student's own units:
 *
 * <pre>
 *   bounce rate = sqrt(kP / kA) / (2 * PI)                   Hz
 *   damping     = (kD + kV) / (2 * sqrt(kP * kA))
 * </pre>
 *
 * <p>Two facts fall straight out of those two lines and neither is obvious from a number:
 *
 * <ol>
 *   <li><b>kV is in the numerator of the damping.</b> The mechanism's own back-EMF is damping, for
 *       free, before kD does anything. A student who watches the damping sit above 0.7 with kD at
 *       zero has just learned why the flywheel recipe leaves kD at zero, without being told.
 *   <li><b>kP is under a square root in the bounce rate and in the denominator of the damping.</b>
 *       Tripling kP makes the mechanism about 1.7 times faster and 1.7 times <em>less</em> damped,
 *       from the same slider, at the same time. That single sentence is the whole content of the
 *       "it overshoots and rings" diagnosis, available before anything moves.
 * </ol>
 *
 * <p><b>And there is a closed form worth knowing.</b> For a position mechanism the solve returns
 * {@code kP = maxVolts / maxError}, exactly. A student who says "four volts, one centimetre" has
 * already said "kP = 400 V/m" without knowing it, and that is a far better thing to teach than
 * "the solver produced 400." The panel still prints what the solver returned, never the closed
 * form, because the solver discretises the plant and the two diverge as the loop gets fast relative
 * to its own sample rate.
 *
 * <p>This step commands no motion at all.
 */
public final class LqrSuggestStep implements TuningStep {

  /** The namespace the two sliders are published under, so they cannot collide with a mechanism. */
  public static final String kSliderNamespace = "TunerSliders";

  /** Default acceptable control effort for a mechanism that gravity is pulling on, in volts. */
  public static final double kGravityEffortVolts = 4.0;

  /** Default acceptable control effort for a mechanism gravity is not pulling on, in volts. */
  public static final double kDefaultEffortVolts = 3.0;

  /** Below this damping, the response will visibly bounce. */
  public static final double kUnderDampedWarning = 0.4;

  /** Above this damping, nothing overshoots but arrival is lazy. */
  public static final double kOverDampedWarning = 2.0;

  /** kD above this multiple of kV will make a noisy encoder buzz. */
  public static final double kNoisyDerivativeFraction = 0.5;

  /**
   * The loop must sample the predicted bounce at least this many times per cycle.
   *
   * <p>Below it, the controller cannot see the oscillation it is creating and amplifies it instead
   * of damping it — which looks, from the driver station, exactly like a mechanical fault.
   */
  public static final double kMinSamplesPerCycle = 2.5;

  /**
   * What the student actually chooses. Two physical quantities, not two abstract gains.
   *
   * @param maxAcceptableErrorSi how much error they can live with, in metres or radians
   * @param maxAcceptableVelocityErrorSi position loops only; how much speed error is acceptable
   * @param maxControlEffortVolts how many volts they will spend correcting it
   * @param measurementDelaySeconds how stale the measurement is by the time the loop acts on it
   * @param dtSeconds the loop period the solve is discretised at
   * @param refinementStepSi the move the refinement step will command, for the saturation warning
   */
  public record Preferences(
      double maxAcceptableErrorSi,
      double maxAcceptableVelocityErrorSi,
      double maxControlEffortVolts,
      double measurementDelaySeconds,
      double dtSeconds,
      double refinementStepSi) {

    /**
     * Sensible starting preferences for an archetype.
     *
     * <p>The error preference defaults to the mechanism's own declared tolerance, because a team
     * that has said "half an inch is close enough" has already answered this question once and
     * should not be asked it twice in different words.
     *
     * @param archetype the mechanism family
     * @param limits its travel, used for the fallback error and the refinement step size
     * @param toleranceSi its declared tolerance, or NaN
     * @return the defaults
     */
    public static Preferences defaultsFor(
        MechanismArchetype archetype, TravelLimits limits, double toleranceSi) {
      double range = limits == null ? Double.NaN : limits.range();
      double fallback = Double.isFinite(range) && range > 0 ? 0.005 * range : 0.005;
      double err = Math.max(Double.isFinite(toleranceSi) ? toleranceSi : fallback, 0.002);
      double effort =
          archetype != null && archetype.hasGravity() ? kGravityEffortVolts : kDefaultEffortVolts;
      double step = Double.isFinite(range) && range > 0 ? 0.25 * range : Double.NaN;
      return new Preferences(err, 10.0 * err, effort, 0.0, 0.020, step);
    }

    /**
     * The same preferences with a different error tolerance.
     *
     * @param error the new acceptable error, in SI units
     * @return a copy
     */
    public Preferences withMaxAcceptableErrorSi(double error) {
      return new Preferences(
          error,
          10.0 * error,
          maxControlEffortVolts,
          measurementDelaySeconds,
          dtSeconds,
          refinementStepSi);
    }

    /**
     * The same preferences with a different control-effort budget.
     *
     * @param volts the new budget
     * @return a copy
     */
    public Preferences withMaxControlEffortVolts(double volts) {
      return new Preferences(
          maxAcceptableErrorSi,
          maxAcceptableVelocityErrorSi,
          volts,
          measurementDelaySeconds,
          dtSeconds,
          refinementStepSi);
    }

    /**
     * The same preferences with a different measurement delay.
     *
     * @param seconds the delay
     * @return a copy
     */
    public Preferences withMeasurementDelaySeconds(double seconds) {
      return new Preferences(
          maxAcceptableErrorSi,
          maxAcceptableVelocityErrorSi,
          maxControlEffortVolts,
          seconds,
          dtSeconds,
          refinementStepSi);
    }
  }

  /**
   * What the solve produced, together with everything needed to explain it.
   *
   * @param kP proportional gain, volts per SI unit
   * @param kD derivative gain, volts per SI unit per second; always zero for a velocity loop
   * @param naturalFrequencyRadPerSec how fast it would wobble, in radians per second; NaN for a
   *     velocity loop, which has no wobble to have a frequency
   * @param naturalFrequencyHz the same number in hertz, which is what the panel prints
   * @param dampingRatio how quickly that wobble dies; NaN for a velocity loop
   * @param timeConstantSeconds velocity loops only: how long it takes to cover 63% of a speed change
   * @param panel the text shown above the gains, already formatted
   * @param warnings sanity warnings, each one a sentence the student can act on
   */
  public record Suggestion(
      double kP,
      double kD,
      double naturalFrequencyRadPerSec,
      double naturalFrequencyHz,
      double dampingRatio,
      double timeConstantSeconds,
      String panel,
      List<String> warnings) {

    /**
     * Canonical constructor; copies the warning list.
     *
     * @param kP see the record javadoc
     * @param kD see the record javadoc
     * @param naturalFrequencyRadPerSec see the record javadoc
     * @param naturalFrequencyHz see the record javadoc
     * @param dampingRatio see the record javadoc
     * @param timeConstantSeconds see the record javadoc
     * @param panel see the record javadoc
     * @param warnings see the record javadoc
     */
    public Suggestion {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Whether the solve produced a gain that can be used at all.
     *
     * @return false when kP came out non-positive, which means kV or kA is non-physical
     */
    public boolean isPhysical() {
      return Double.isFinite(kP) && kP > 0;
    }
  }

  private final GainId m_produces;
  private final String m_explanation;

  private Suggestion m_suggestion;
  private Preferences m_preferences;
  private TunableDouble m_errorSlider;
  private TunableDouble m_effortSlider;
  private boolean m_done;

  private LqrSuggestStep(GainId produces, String explanation) {
    m_produces = produces;
    m_explanation = explanation;
  }

  /**
   * The step that proposes kP.
   *
   * @return the step
   */
  public static LqrSuggestStep forKp() {
    return new LqrSuggestStep(GainId.KP, Lessons.P);
  }

  /**
   * The step that proposes kD, from the same solve.
   *
   * <p>Separated from kP deliberately. They come out of one solve, but they are two different ideas
   * — a spring and a shock absorber — and a student who meets them in the same screen learns one
   * blurred idea instead of two sharp ones.
   *
   * @return the step
   */
  public static LqrSuggestStep forKd() {
    return new LqrSuggestStep(GainId.KD, Lessons.D);
  }

  /**
   * Run the solve. No motion, no state, no side effects — safe to call from a prediction question's
   * answer key, which is exactly what {@link PredictStep#slidersBeforeLqr(TuningStep)} does.
   *
   * @param archetype the mechanism family, which decides position versus velocity
   * @param kV the measured velocity feedforward
   * @param kA the measured acceleration feedforward
   * @param p the student's preferences
   * @return the suggestion, including the panel text and the warnings
   */
  public static Suggestion design(
      MechanismArchetype archetype, double kV, double kA, Preferences p) {
    boolean velocity = archetype != null && archetype.isVelocity();
    if (!(kA > 0) || !Double.isFinite(kA) || !Double.isFinite(kV)) {
      return new Suggestion(
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          "kP cannot be computed, because kA came out as "
              + kA
              + " and kV as "
              + kV
              + ". Both have to be real, positive numbers before feedback means anything. Re-run "
              + "the kV and kA steps, and check the motor and encoder directions first.",
          List.of(
              "kV or kA is not physical, so no feedback gain can be derived from them. This almost "
                  + "always means an inverted encoder or an inverted motor - fix the invert, do "
                  + "not tune around it."));
    }

    double kP;
    double kD;
    if (velocity) {
      var plant = LinearSystemId.identifyVelocitySystem(kV, kA);
      var lqr =
          new LinearQuadraticRegulator<>(
              plant,
              VecBuilder.fill(p.maxAcceptableErrorSi()),
              VecBuilder.fill(p.maxControlEffortVolts()),
              p.dtSeconds());
      if (p.measurementDelaySeconds() > 0) {
        lqr.latencyCompensate(plant, p.dtSeconds(), p.measurementDelaySeconds());
      }
      kP = lqr.getK().get(0, 0);
      kD = 0.0;
    } else {
      var plant = LinearSystemId.identifyPositionSystem(kV, kA);
      var lqr =
          new LinearQuadraticRegulator<>(
              plant,
              VecBuilder.fill(p.maxAcceptableErrorSi(), p.maxAcceptableVelocityErrorSi()),
              VecBuilder.fill(p.maxControlEffortVolts()),
              p.dtSeconds());
      if (p.measurementDelaySeconds() > 0) {
        lqr.latencyCompensate(plant, p.dtSeconds(), p.measurementDelaySeconds());
      }
      kP = lqr.getK().get(0, 0);
      kD = lqr.getK().get(0, 1);
    }

    double wn = velocity ? Double.NaN : Math.sqrt(kP / kA);
    double fn = velocity ? Double.NaN : wn / (2.0 * Math.PI);
    double zeta = velocity ? Double.NaN : PredictStep.dampingRatio(kP, kD, kV, kA);
    double tau = velocity ? kA / (kV + kP) : Double.NaN;

    return new Suggestion(
        kP, kD, wn, fn, zeta, tau, panelText(velocity, kP, kD, fn, zeta, tau), checks(kP, kD, kV, zeta, fn, p));
  }

  private static String panelText(
      boolean velocity, double kP, double kD, double fn, double zeta, double tau) {
    if (velocity) {
      return String.format(
          Locale.ROOT,
          "With this gain your wheel closes about two thirds of any speed error in %.3f s, and "
              + "keeps closing from there. There is no bounce to talk about: a velocity loop has "
              + "one thing to remember, not two, so it cannot overshoot its way past the target and "
              + "come back.%n%n    kP = %.4f  V per unit of speed error      kD = 0%n%n"
              + "Less acceptable speed error, or more volts, gives a bigger kP and a shorter time. "
              + "These are educated starting points, not final answers.",
          tau,
          kP);
    }
    return String.format(
        Locale.ROOT,
        "With these gains your mechanism behaves like a spring that would bounce at %.2f Hz, damped "
            + "to %.2f. Above about %.1f you will not see it bounce at all.%n%n"
            + "    kP = %.4f  V per unit          kD = %.4f  V per unit per second%n%n"
            + "Smaller error or more volts gives bigger gains, a faster bounce, and less damping - "
            + "at the same time, from the same slider. These are educated starting points, not "
            + "final answers.",
        fn,
        zeta,
        org.rootstock.tuning.wizard.Coach.kBounceThreshold,
        kP,
        kD);
  }

  private static List<String> checks(
      double kP, double kD, double kV, double zeta, double fn, Preferences p) {
    List<String> out = new ArrayList<>();
    if (!(kP > 0)) {
      out.add(
          "kP came out at "
              + kP
              + ", which is not a usable gain. That means kV or kA is non-physical. Check the "
              + "motor and encoder directions before anything else.");
      return out;
    }
    if (Double.isFinite(p.refinementStepSi())
        && kP * p.refinementStepSi() > p.maxControlEffortVolts() * 3.0) {
      out.add(
          String.format(
              Locale.ROOT,
              "These gains saturate for any error bigger than %.3f. That is fine with a motion "
                  + "profile, and the check step uses one - but without a profile the controller "
                  + "will just hold the throttle open and none of your gains will be in the loop.",
              p.maxControlEffortVolts() / kP));
    }
    if (kD > kNoisyDerivativeFraction * kV && kV > 0) {
      out.add(
          String.format(
              Locale.ROOT,
              "kD (%.2f) is large compared to kV (%.2f). On a noisy encoder that will make the "
                  + "mechanism buzz. Consider accepting a little more speed error.",
              kD,
              kV));
    }
    if (Double.isFinite(zeta) && zeta > kOverDampedWarning) {
      out.add(
          String.format(
              Locale.ROOT,
              "Damping is %.2f. Nothing will overshoot, but this will arrive lazily and the next "
                  + "step will probably call it sluggish. Loosen the speed-error slider, or accept "
                  + "a bigger position error, if you want it snappier.",
              zeta));
    }
    if (Double.isFinite(zeta) && zeta < kUnderDampedWarning) {
      out.add(
          String.format(
              Locale.ROOT,
              "Damping is %.2f. This will visibly bounce. That is a legal choice if you want speed, "
                  + "but the next step will classify it as ringing and offer to add kD.",
              zeta));
    }
    double nyquistish = 1.0 / (kMinSamplesPerCycle * p.dtSeconds());
    if (Double.isFinite(fn) && fn > nyquistish) {
      out.add(
          String.format(
              Locale.ROOT,
              "These gains want the mechanism to bounce at %.0f Hz, and your loop only runs at "
                  + "%.0f Hz. The controller cannot see an oscillation that fast, so it will "
                  + "amplify it instead of damping it. Accept more error, or move this loop onto "
                  + "the motor controller.",
              fn,
              1.0 / p.dtSeconds()));
    }
    return out;
  }

  /**
   * The suggestion this step produced, once it has run.
   *
   * @return the suggestion, or empty before {@link #begin(StepContext)}
   */
  public Optional<Suggestion> suggestion() {
    return Optional.ofNullable(m_suggestion);
  }

  /**
   * The preferences the solve actually used, including any slider the student moved.
   *
   * @return the preferences, or empty before the step has begun
   */
  public Optional<Preferences> preferences() {
    return Optional.ofNullable(m_preferences);
  }

  @Override
  public String title() {
    return m_produces == GainId.KP ? "Suggest kP" : "Suggest kD";
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.of(m_produces);
  }

  @Override
  public String explanation() {
    return m_explanation;
  }

  @Override
  public String watchFor() {
    return "Watch the bounce rate and the damping change as you drag the sliders. Those two "
        + "numbers are what kP means.";
  }

  @Override
  public String willDo() {
    return "Nothing moves. This step is arithmetic on the kV and kA you just measured.";
  }

  @Override
  public boolean commandsMotion() {
    return false;
  }

  @Override
  public void begin(StepContext ctx) {
    m_done = false;
    m_suggestion = null;
    String mech = ctx.target().tuningName();
    Preferences base =
        Preferences.defaultsFor(
            ctx.target().archetype(), ctx.target().travelLimits(), ctx.toleranceSi());
    m_errorSlider =
        TuningRegistry.tunable(
            kSliderNamespace,
            mech + ".maxError",
            base.maxAcceptableErrorSi(),
            StepSupport.unitLabel(ctx),
            base.maxAcceptableErrorSi() / 10.0,
            base.maxAcceptableErrorSi() * 10.0);
    m_effortSlider =
        TuningRegistry.tunable(
            kSliderNamespace,
            mech + ".maxVolts",
            base.maxControlEffortVolts(),
            "V",
            0.5,
            10.0);
    ctx.narrate(
        "No motion in this step. Drag the two sliders and watch what happens to the bounce rate "
            + "and the damping - that is kP explaining itself.");
  }

  @Override
  public void periodic(StepContext ctx) {
    if (ctx.supervisor().check().isPresent()) {
      m_done = true;
      return;
    }
    m_preferences = live(ctx);
    Gains g = ctx.gains();
    m_suggestion = design(ctx.target().archetype(), g.kV(), g.kA(), m_preferences);
    ctx.publishProgress(1.0);
    m_done = true;
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return m_done;
  }

  @Override
  public StepResult finish(StepContext ctx) {
    Gains g = ctx.gains();
    if (m_suggestion == null) {
      m_preferences = live(ctx);
      m_suggestion = design(ctx.target().archetype(), g.kV(), g.kA(), m_preferences);
    }
    if (!m_suggestion.isPhysical()) {
      return StepResult.retry(
          "No usable gain",
          m_suggestion.panel(),
          "Re-run the kV and kA steps. If they keep coming out like this, the encoder or the motor "
              + "is inverted.",
          m_suggestion.warnings());
    }
    double value = m_produces == GainId.KP ? m_suggestion.kP() : m_suggestion.kD();
    double previous = m_produces == GainId.KP ? g.kP() : g.kD();
    String unit = m_produces.unitFor(ctx.target().siDomain());
    return StepResult.success(
        m_produces,
        value,
        previous,
        String.format(Locale.ROOT, "%s = %.4f %s", m_produces.key(), value, unit),
        m_produces == GainId.KP
            ? String.format(
                Locale.ROOT,
                "From %.4f volts allowed and %.4f %s of error allowed",
                m_preferences.maxControlEffortVolts(),
                m_preferences.maxAcceptableErrorSi(),
                StepSupport.unitLabel(ctx))
            : "From the same solve that produced kP",
        m_suggestion.panel(),
        "Press A to take this starting point. The next step will command a real move and tell you "
            + "whether it was a good one - these are educated starting points, not final answers.",
        m_suggestion.warnings());
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return 0.0;
  }

  private Preferences live(StepContext ctx) {
    Preferences base =
        Preferences.defaultsFor(
            ctx.target().archetype(), ctx.target().travelLimits(), ctx.toleranceSi());
    double error = m_errorSlider == null ? base.maxAcceptableErrorSi() : m_errorSlider.get();
    double effort = m_effortSlider == null ? base.maxControlEffortVolts() : m_effortSlider.get();
    double declared = ctx.target().measurementDelaySeconds();
    double delay;
    if (Double.isFinite(declared)) {
      delay = declared;
    } else if (ctx.target().controlLocation() != null
        && ctx.target().controlLocation().runsOnMotor()) {
      // The feedback loop is running on the device at about a kilohertz. The RIO's transport delay
      // is not in that loop, so putting it into the solve would deliberately detune a loop that
      // does not have the problem.
      delay = 0.0;
    } else {
      delay = 0.5 * ctx.dt();
    }
    return new Preferences(
        Math.max(error, 1e-6),
        10.0 * Math.max(error, 1e-6),
        Math.max(effort, 0.1),
        delay,
        ctx.dt() > 0 ? ctx.dt() : base.dtSeconds(),
        base.refinementStepSi());
  }
}

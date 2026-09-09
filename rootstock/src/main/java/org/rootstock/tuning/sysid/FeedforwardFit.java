package org.rootstock.tuning.sysid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.control.PlantPrior;

/**
 * The four feedforward gains a sweep measured, with an honest account of how well they fit.
 *
 * <p><b>Why the quality is part of the record.</b> Four numbers on a dashboard look equally
 * authoritative whether they explain 97% of the applied voltage or 40% of it. A student cannot tell
 * the difference by looking, so the difference is carried in the type: a fit whose {@link Quality}
 * is {@link Quality#UNUSABLE} is one Rootstock refuses to accept, and it says why rather than
 * handing over a number that will make the mechanism behave strangely a week later.
 *
 * <p><b>{@code voltageFitR2} is our statistic, not SysId's.</b> It is the R² of predicted applied
 * <em>voltage</em>, computed from the same accumulators the streaming fit already keeps. SysId
 * reports a simulated-velocity r² and an acceleration r², and its published thresholds do not
 * transfer to this one — so this field is named for what it is, and {@link #simulatedVelocityR2()}
 * is computed separately by {@link #validatedAgainst} for a student who already knows SysId's
 * number.
 *
 * @param kS static-friction feedforward, volts
 * @param kV velocity feedforward, volts per (SI unit per second)
 * @param kA acceleration feedforward, volts per (SI unit per second squared)
 * @param kG gravity feedforward, volts; zero for an archetype with no gravity term
 * @param voltageFitR2 fraction of the applied-voltage variance the model explains
 * @param rmseVolts root-mean-square voltage prediction error
 * @param simulatedVelocityR2 SysId's familiar statistic, or NaN until {@link #validatedAgainst} runs
 * @param samples how many loops went into the fit
 * @param quality the headline verdict
 * @param warnings physics sanity findings, each a sentence naming the value and the likely cause
 */
public record FeedforwardFit(
    double kS,
    double kV,
    double kA,
    double kG,
    double voltageFitR2,
    double rmseVolts,
    double simulatedVelocityR2,
    int samples,
    FeedforwardFit.Quality quality,
    List<String> warnings) {

  /** How much to trust this fit. */
  public enum Quality {
    /** The model explains almost all of the applied voltage. Use it. */
    GOOD,
    /** Usable but noisy. Worth a second sweep with a slower ramp if the gains look odd. */
    ACCEPTABLE,
    /** Poor. Usually backlash, a slipping encoder, or something else fighting the motor. */
    SUSPECT,
    /** Not trustworthy. Rootstock will not accept it. */
    UNUSABLE;

    /**
     * One sentence a student can act on.
     *
     * @return the explanation
     */
    public String explain() {
      return switch (this) {
        case GOOD -> "Good fit. The model explains almost all of the voltage you applied.";
        case ACCEPTABLE ->
            "Usable fit, but noisy. Re-run with a slower ramp if the gains look odd.";
        case SUSPECT ->
            "Poor fit. Usually this means backlash, a slipping encoder, or something else "
                + "fighting the motor. Run the mechanical health check.";
        case UNUSABLE ->
            "This fit is not trustworthy and Rootstock will not accept it. Run the mechanical "
                + "health check before you sweep again.";
      };
    }
  }

  /** At or above this R², with a small enough RMSE, the fit is {@link Quality#GOOD}. */
  public static final double kGoodR2 = 0.95;

  /** RMSE ceiling, in volts, for {@link Quality#GOOD}. */
  public static final double kGoodRmseVolts = 0.25;

  /** At or above this R², with a small enough RMSE, the fit is {@link Quality#ACCEPTABLE}. */
  public static final double kAcceptableR2 = 0.85;

  /** RMSE ceiling, in volts, for {@link Quality#ACCEPTABLE}. */
  public static final double kAcceptableRmseVolts = 0.50;

  /** Below this R² nothing is trustworthy, whatever the RMSE says. */
  public static final double kSuspectR2 = 0.60;

  /** Measured kV must lie inside this multiple of the motor-curve prior. */
  public static final double kMinKvRatio = 0.4;

  /** Measured kV must lie inside this multiple of the motor-curve prior. */
  public static final double kMaxKvRatio = 3.0;

  /** Measured kA must lie inside this multiple of the motor-curve prior. */
  public static final double kMinKaRatio = 0.25;

  /** Measured kA must lie inside this multiple of the motor-curve prior. */
  public static final double kMaxKaRatio = 6.0;

  /** Measured kG must lie inside this multiple of the gravity prior. */
  public static final double kMinKgRatio = 0.3;

  /** Measured kG must lie inside this multiple of the gravity prior. */
  public static final double kMaxKgRatio = 3.0;

  /** kS above this fraction of nominal battery voltage is a lot of friction. */
  public static final double kMaxKsFractionOfNominal = 0.25;

  /** Defensive canonical constructor; the warning list is copied so a fit cannot change later. */
  public FeedforwardFit {
    warnings = List.copyOf(warnings);
  }

  /**
   * Build a fit from a solved parameter vector.
   *
   * <p>The order of {@code beta} is the regressor order of the archetype: {@code [kS, kV, kA]} for a
   * gravity-free mechanism and {@code [kG, kS, kV, kA]} for an elevator or an arm. That order is the
   * regression's, not a convention chosen here, which is why this factory exists rather than a
   * constructor call at the call site.
   *
   * @param archetype which model was fitted
   * @param beta the solved parameters, in regressor order
   * @param voltageFitR2 the voltage-prediction R²
   * @param rmseVolts the voltage-prediction RMSE
   * @param samples how many loops went into it
   * @return the fit, with its quality classified and no sanity warnings yet
   * @throws IllegalArgumentException if {@code beta} is not the right length for the archetype
   */
  public static FeedforwardFit from(
      MechanismArchetype archetype,
      double[] beta,
      double voltageFitR2,
      double rmseVolts,
      int samples) {
    int expected = archetype.hasGravity() ? 4 : 3;
    if (beta == null || beta.length != expected) {
      throw new IllegalArgumentException(
          "A "
              + archetype
              + " fit needs "
              + expected
              + " parameters and got "
              + (beta == null ? "null" : String.valueOf(beta.length))
              + ". This is a library bug, not a configuration mistake.");
    }
    double kG = archetype.hasGravity() ? beta[0] : 0.0;
    int base = archetype.hasGravity() ? 1 : 0;
    return new FeedforwardFit(
        beta[base],
        beta[base + 1],
        beta[base + 2],
        kG,
        voltageFitR2,
        rmseVolts,
        Double.NaN,
        samples,
        classify(voltageFitR2, rmseVolts),
        List.of());
  }

  /**
   * Classify a fit from its two quality numbers.
   *
   * @param voltageFitR2 the voltage-prediction R²
   * @param rmseVolts the voltage-prediction RMSE
   * @return the quality band
   */
  public static Quality classify(double voltageFitR2, double rmseVolts) {
    if (!Double.isFinite(voltageFitR2)) {
      return Quality.UNUSABLE;
    }
    if (voltageFitR2 >= kGoodR2 && rmseVolts <= kGoodRmseVolts) {
      return Quality.GOOD;
    }
    if (voltageFitR2 >= kAcceptableR2 && rmseVolts <= kAcceptableRmseVolts) {
      return Quality.ACCEPTABLE;
    }
    if (voltageFitR2 >= kSuspectR2) {
      return Quality.SUSPECT;
    }
    return Quality.UNUSABLE;
  }

  /**
   * Compare every gain against the physics the mechanism declared, and say where they disagree.
   *
   * <p><b>Why this exists.</b> A fit can be statistically excellent and physically absurd. A kV that
   * implies a free speed twice the motor's is not a discovery about the mechanism, it is a wrong
   * gear ratio somewhere — and handing that number to a student produces a mechanism that overshoots
   * every setpoint for a month while everyone blames kP.
   *
   * <p>Every check produces a <em>warning</em>, not a rejection, because a real robot legitimately
   * differs from a spherical-cow prior: a chain drive really does have more friction than the model,
   * and a carriage really does carry an unmodelled cable. The one exception is a negative kV, which
   * is not a disagreement about magnitude but a statement that the encoder and the motor point in
   * opposite directions; that is downgraded to {@link Quality#UNUSABLE}, because tuning around an
   * inversion bakes the sign error into every gain that follows.
   *
   * <p>The gravity prior used here is {@link PlantPrior#gravityVoltsPrior()}, which computes
   * {@code tau / (G * Kt/R)} and does <b>not</b> divide again by the motor count. WPILib's n-motor
   * {@code DCMotor} constructor scales stall torque and stall current together, so {@code Kt} is
   * unchanged and {@code R} is divided by n — meaning {@code Kt/R} already carries the factor. An
   * extra division there makes the prior n times low on every multi-motor mechanism, which is not a
   * cosmetic error: it puts the bisection bracket below the true value and reports a hardware fault
   * on perfectly healthy hardware.
   *
   * @param prior the declared physics
   * @param archetype which model was fitted
   * @param gravitySign the measured sign of the gravity term: +1 when the mechanism needs positive
   *     volts to hold itself up, -1 when it needs negative volts, 0 when it was not measured. Never
   *     assumed — a wrist whose positive direction points downward has a genuinely negative kG.
   * @return a copy carrying the warnings, and a possibly downgraded quality
   */
  public FeedforwardFit sanityBounded(
      PlantPrior prior, MechanismArchetype archetype, double gravitySign) {
    List<String> found = new ArrayList<>(warnings);
    Quality worst = quality;

    if (!(kV > 0.0)) {
      found.add(
          String.format(
              Locale.ROOT,
              "kV came out at %.4f, which is not positive. Your encoder or motor direction is "
                  + "inverted. Fix the invert before tuning; do not tune around it.",
              kV));
      worst = Quality.UNUSABLE;
    }

    if (prior != null) {
      double kvPrior = prior.kVprior();
      if (Double.isFinite(kvPrior) && kvPrior > 0 && kV > 0) {
        double ratio = kV / kvPrior;
        if (ratio < kMinKvRatio || ratio > kMaxKvRatio) {
          double impliedFreeSpeed = prior.nominalVolts() / kV;
          found.add(
              String.format(
                  Locale.ROOT,
                  "Measured kV is %.2fx what the motor curve predicts (%.4f measured, %.4f "
                      + "predicted), which implies a free speed of %.3f SI/s against the "
                      + "predicted %.3f. That usually means the gear ratio in your config is "
                      + "wrong, or something is dragging.",
                  ratio,
                  kV,
                  kvPrior,
                  impliedFreeSpeed,
                  prior.freeSpeedSi()));
          worst = downgrade(worst);
        }
      }

      double kaPrior = prior.kAprior();
      if (Double.isFinite(kaPrior) && kaPrior > 0 && Double.isFinite(kA)) {
        double ratio = kA / kaPrior;
        if (ratio < kMinKaRatio || ratio > kMaxKaRatio) {
          found.add(
              String.format(
                  Locale.ROOT,
                  "Measured kA is %.2fx the predicted value (%.5f measured, %.5f predicted). kA is "
                      + "the hardest gain to measure - check that the dynamic (step) test actually "
                      + "ran, because a ramp alone cannot see acceleration.",
                  ratio,
                  kA,
                  kaPrior));
          worst = downgrade(worst);
        }
      }

      double ksCeiling = kMaxKsFractionOfNominal * prior.nominalVolts();
      if (kS < 0.0 || kS > ksCeiling) {
        found.add(
            String.format(
                Locale.ROOT,
                "kS came out at %.3f V and it should be between 0 and %.2f V. That is a lot of "
                    + "friction - check for a binding bearing or an overtight belt before you "
                    + "accept this.",
                kS,
                ksCeiling));
        worst = downgrade(worst);
      }

      if (archetype != null && archetype.hasGravity()) {
        if (gravitySign != 0.0 && Math.signum(kG) != Math.signum(gravitySign) && kG != 0.0) {
          found.add(
              String.format(
                  Locale.ROOT,
                  "kG came out at %.4f V, which is the opposite sign to the direction this "
                      + "mechanism was measured to fall. Check which way your encoder counts.",
                  kG));
          worst = downgrade(worst);
        }
        double kgPrior = prior.gravityVoltsPrior();
        if (Double.isFinite(kgPrior) && kgPrior > 0 && kG != 0.0) {
          double ratio = Math.abs(kG) / kgPrior;
          if (ratio < kMinKgRatio || ratio > kMaxKgRatio) {
            found.add(
                String.format(
                    Locale.ROOT,
                    "Measured kG is %.2fx the value your declared mass and geometry predict "
                        + "(%.4f V measured, %.4f V predicted). Either the mass or the "
                        + "centre-of-mass length in your PlantPrior is wrong, or the mechanism is "
                        + "carrying something you did not declare.",
                    ratio,
                    Math.abs(kG),
                    kgPrior));
            worst = downgrade(worst);
          }
        }
      }
    }

    return new FeedforwardFit(
        kS, kV, kA, kG, voltageFitR2, rmseVolts, simulatedVelocityR2, samples, worst, found);
  }

  /**
   * Compute SysId's familiar simulated-velocity R² by replaying the model through the window.
   *
   * <p>The fit's own {@link #voltageFitR2()} answers "how well does this model predict the voltage
   * we applied". This answers the different question SysId's GUI reports — "if I had driven this
   * model with the voltages we applied, how closely would it have followed the velocity we
   * measured" — and it is worth showing because a student who has used SysId before will look for
   * it and, not finding it, will not trust the rest.
   *
   * @param window the recorded samples, oldest first
   * @param archetype which model to integrate
   * @param horizontalReferenceSi the position at which a cosine-gravity mechanism is horizontal
   * @return a copy carrying {@link #simulatedVelocityR2()}; NaN when the window is too short or kA
   *     is too small to integrate against
   */
  public FeedforwardFit validatedAgainst(
      List<SampleBuffer.Sample> window, MechanismArchetype archetype, double horizontalReferenceSi) {
    double r2 = simulatedVelocityR2(window, archetype, horizontalReferenceSi);
    return new FeedforwardFit(
        kS, kV, kA, kG, voltageFitR2, rmseVolts, r2, samples, quality, warnings);
  }

  private double simulatedVelocityR2(
      List<SampleBuffer.Sample> window, MechanismArchetype archetype, double horizontalReferenceSi) {
    if (window == null || window.size() < 10 || !(Math.abs(kA) > 1e-9)) {
      return Double.NaN;
    }
    GravityMode gravity =
        archetype == null || !archetype.hasGravity()
            ? GravityMode.NONE
            : (archetype == MechanismArchetype.ARM ? GravityMode.COSINE : GravityMode.CONSTANT);

    double simulated = window.get(0).velocity();
    double sumMeasured = 0.0;
    int counted = 0;
    for (SampleBuffer.Sample s : window) {
      if (Double.isFinite(s.velocity())) {
        sumMeasured += s.velocity();
        counted++;
      }
    }
    if (counted < 10) {
      return Double.NaN;
    }
    double mean = sumMeasured / counted;

    double sse = 0.0;
    double sst = 0.0;
    for (int i = 1; i < window.size(); i++) {
      SampleBuffer.Sample previous = window.get(i - 1);
      SampleBuffer.Sample s = window.get(i);
      double dt = s.t() - previous.t();
      if (!(dt > 0) || dt > 1.0 || !Double.isFinite(s.commandedVolts())) {
        continue;
      }
      double gravityVolts =
          switch (gravity) {
            case NONE -> 0.0;
            case CONSTANT -> kG;
            case COSINE -> kG * Math.cos(previous.measurement() - horizontalReferenceSi);
          };
      double accel =
          (s.commandedVolts() - gravityVolts - kS * Math.signum(simulated) - kV * simulated) / kA;
      simulated += accel * dt;
      if (!Double.isFinite(simulated)) {
        return Double.NaN;
      }
      if (Double.isFinite(s.velocity())) {
        sse += Math.pow(s.velocity() - simulated, 2);
        sst += Math.pow(s.velocity() - mean, 2);
      }
    }
    return sst > 1e-12 ? 1.0 - sse / sst : Double.NaN;
  }

  /**
   * These four gains folded into an existing gain set, leaving the feedback terms alone.
   *
   * @param base the gains the mechanism is running now
   * @return a copy with kS, kV, kA and kG replaced by the measured values
   */
  public Gains appliedTo(Gains base) {
    Gains starting = base == null ? Gains.UNTUNED : base;
    return starting.withKs(kS).withKv(kV).withKa(kA).withKg(kG);
  }

  /**
   * Whether Rootstock will let this fit be accepted.
   *
   * @return false when the quality is {@link Quality#UNUSABLE}
   */
  public boolean acceptable() {
    return quality != Quality.UNUSABLE;
  }

  /**
   * The fit on one line, for the report and the boot dump.
   *
   * @return e.g. {@code "kS 0.220, kV 5.110, kA 0.0600, kG 0.2528, R2 0.982, RMSE 0.18 V, 640
   *     samples, GOOD"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "kS %.4f, kV %.4f, kA %.5f, kG %.4f, R2 %.4f, RMSE %.3f V, %d samples, %s",
        kS,
        kV,
        kA,
        kG,
        voltageFitR2,
        rmseVolts,
        samples,
        quality);
  }

  private static Quality downgrade(Quality current) {
    return switch (current) {
      case GOOD -> Quality.ACCEPTABLE;
      case ACCEPTABLE -> Quality.SUSPECT;
      default -> current;
    };
  }
}

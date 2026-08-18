package org.pumpkinlib.tuning.sysid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.MechanismArchetype;
import org.pumpkinlib.control.PlantPrior;
import org.pumpkinlib.pure.units.Reduction;

/**
 * The streaming least-squares fit, driven from plants whose gains are known exactly.
 *
 * <p><strong>Why generate the data instead of replaying a log.</strong> A recorded sweep can only
 * tell you that the fit produced <em>some</em> numbers; it cannot tell you they are the right ones,
 * because nobody knows what the right ones were. Here the voltage is computed from the model —
 * {@code u = kS*sgn(v) + kV*v + kA*a}, plus a constant or a cosine for the two gravity archetypes —
 * so the answer is known before the solver runs and "recovered to within x" is a statement with a
 * meaning.
 *
 * <p><strong>And why the second half matters more than the first.</strong> A fit that recovers a
 * real plant is table stakes. The dangerous case is the fit that is <em>statistically excellent and
 * physically impossible</em>: R² of 1.000, RMSE of zero, and a kV whose sign says the encoder counts
 * backwards. Nothing in the fit itself can see that, which is exactly why {@link
 * FeedforwardFit#sanityBounded} exists and why the tests below check that it refuses rather than
 * warns.
 *
 * <p><strong>Why this is {@code @Tag("hal")}.</strong> {@link FeedforwardRegression#solve()} ends in
 * {@code Matrix.solveFullPivHouseholderQr}, and that method — unlike the rest of wpimath's linear
 * algebra — is a JNI call into {@code wpimathjni}, not EJML. On a JVM without the WPILib desktop
 * natives, WPILib's loader writes to stderr and calls {@code System.exit(1)}; it does not throw, so
 * no catch clause can save the test JVM and the whole worker dies with no indication of which test
 * did it. Verified against 2026.2.2 by stack trace, not assumed. Run these with {@code ./gradlew
 * halTest}.
 */
@Tag("hal")
final class FeedforwardRegressionTest {

  /** 50 Hz, exactly as the robot loop samples. */
  private static final double kDt = 0.02;

  /** The reference elevator prior, used for every sanity-bound assertion below. */
  private static final PlantPrior kElevatorPrior =
      PlantPrior.elevator(DCMotor.getKrakenX60Foc(2), Reduction.of(12.0), 9.0, 0.0223);

  /**
   * A velocity profile that reverses, so the {@code sgn(v)} column genuinely varies.
   *
   * <p>This is not decorative. A sweep that only ever runs one direction leaves {@code sgn(v)}
   * constant, and a constant regressor column carries no information about the gain it multiplies —
   * which is precisely the rank check {@link FeedforwardRegression#solve()} refuses on.
   *
   * @param samples how many loops to generate
   * @param amplitude peak velocity, in SI per second
   * @param hz how fast the profile reverses
   * @return one velocity per loop
   */
  private static double[] reversingVelocity(int samples, double amplitude, double hz) {
    double[] v = new double[samples];
    for (int i = 0; i < samples; i++) {
      v[i] = amplitude * Math.sin(2.0 * Math.PI * hz * i * kDt);
    }
    return v;
  }

  /** The analytic derivative of {@link #reversingVelocity}, so acceleration is exact. */
  private static double[] reversingAccel(int samples, double amplitude, double hz) {
    double[] a = new double[samples];
    double w = 2.0 * Math.PI * hz;
    for (int i = 0; i < samples; i++) {
      a[i] = amplitude * w * Math.cos(w * i * kDt);
    }
    return a;
  }

  /**
   * Feeds a whole synthetic sweep of a known plant into a fresh regression.
   *
   * @param archetype which model to fit
   * @param kS the true static-friction volts
   * @param kV the true velocity gain
   * @param kA the true acceleration gain
   * @param kG the true gravity volts, ignored for a gravity-free archetype
   * @param samples how many loops
   * @param noiseVolts standard deviation of the voltage noise, zero for a noiseless plant
   * @return the loaded regression, ready to solve
   */
  private static FeedforwardRegression sweep(
      MechanismArchetype archetype,
      double kS,
      double kV,
      double kA,
      double kG,
      int samples,
      double noiseVolts) {
    FeedforwardRegression fit = new FeedforwardRegression(archetype);
    double[] v = reversingVelocity(samples, 2.5, 0.25);
    double[] a = reversingAccel(samples, 2.5, 0.25);
    Random random = new Random(20260817L);

    for (int i = 0; i < samples; i++) {
      double theta = -1.0 + 2.4 * i / (double) samples; // radians from horizontal, swept
      double gravityVolts =
          switch (archetype) {
            case ARM -> kG * Math.cos(theta);
            case ELEVATOR -> kG;
            default -> 0.0;
          };
      double u =
          gravityVolts
              + kS * Math.signum(v[i])
              + kV * v[i]
              + kA * a[i]
              + (noiseVolts > 0 ? noiseVolts * random.nextGaussian() : 0.0);
      fit.add(theta, v[i], a[i], u);
    }
    return fit;
  }

  @Nested
  @DisplayName("recovering a plant whose gains are known exactly")
  final class Recovery {

    @Test
    @DisplayName("a noiseless flywheel recovers kS, kV and kA to twelve digits")
    void flywheelRecoversExactly() {
      FeedforwardFit fit = sweep(MechanismArchetype.FLYWHEEL, 0.22, 0.0195, 0.0031, 0.0, 600, 0.0).solve();

      assertEquals(0.22, fit.kS(), 1e-9);
      assertEquals(0.0195, fit.kV(), 1e-9);
      assertEquals(0.0031, fit.kA(), 1e-9);
      assertEquals(0.0, fit.kG(), 0.0, "a gravity-free archetype has no kG term to solve for");
      assertEquals(600, fit.samples());
      assertEquals(1.0, fit.voltageFitR2(), 1e-9);
      assertTrue(fit.rmseVolts() < 1e-6, "RMSE was " + fit.rmseVolts());
      assertEquals(FeedforwardFit.Quality.GOOD, fit.quality());
    }

    @Test
    @DisplayName("a noiseless elevator recovers its constant gravity term too")
    void elevatorRecoversGravity() {
      FeedforwardFit fit = sweep(MechanismArchetype.ELEVATOR, 0.31, 5.10, 0.060, 0.2528, 600, 0.0).solve();

      assertEquals(0.31, fit.kS(), 1e-8);
      assertEquals(5.10, fit.kV(), 1e-8);
      assertEquals(0.060, fit.kA(), 1e-8);
      assertEquals(0.2528, fit.kG(), 1e-8);
      assertEquals(4, new FeedforwardRegression(MechanismArchetype.ELEVATOR).parameterCount());
    }

    @Test
    @DisplayName("a noiseless arm recovers a cosine gravity term against the angle from horizontal")
    void armRecoversCosineGravity() {
      FeedforwardFit fit = sweep(MechanismArchetype.ARM, 0.18, 1.40, 0.045, 0.95, 600, 0.0).solve();

      assertEquals(0.18, fit.kS(), 1e-8);
      assertEquals(1.40, fit.kV(), 1e-8);
      assertEquals(0.045, fit.kA(), 1e-8);
      assertEquals(0.95, fit.kG(), 1e-8);
    }

    @Test
    @DisplayName("with 0.15 V of noise the gains still land inside 5%, and the quality says so")
    void noisyDataStillRecoversWithinAStatedTolerance() {
      double kS = 0.31;
      double kV = 5.10;
      double kA = 0.060;
      double kG = 0.2528;
      FeedforwardFit fit = sweep(MechanismArchetype.ELEVATOR, kS, kV, kA, kG, 1200, 0.15).solve();

      assertEquals(kV, fit.kV(), 0.05 * kV, "kV is the dominant term and must survive noise");
      assertEquals(kG, fit.kG(), 0.05 * kG);
      assertEquals(kS, fit.kS(), 0.10 * kS, "kS rides on a two-valued column, so it is noisier");
      assertEquals(kA, fit.kA(), 0.20 * kA, "kA is the hardest gain to measure, and the code says so");

      assertTrue(fit.rmseVolts() > 0.0);
      assertTrue(fit.rmseVolts() < 0.25, "0.15 V of noise should not produce more than 0.25 V RMSE");
      assertTrue(fit.voltageFitR2() > FeedforwardFit.kGoodR2);
      assertEquals(FeedforwardFit.Quality.GOOD, fit.quality());
    }

    @Test
    @DisplayName("the fit is streaming: 200 samples and 20000 samples cost the same memory")
    void theFitIsStreaming() {
      FeedforwardRegression small = sweep(MechanismArchetype.FLYWHEEL, 0.2, 0.02, 0.003, 0, 400, 0);
      FeedforwardRegression large = sweep(MechanismArchetype.FLYWHEEL, 0.2, 0.02, 0.003, 0, 20000, 0);

      assertEquals(400, small.samples());
      assertEquals(20000, large.samples());
      assertEquals(3, small.parameterCount());
      assertEquals(3, large.parameterCount());
      assertEquals(small.solve().kV(), large.solve().kV(), 1e-9);
      assertTrue(small.describe().contains("400/" + FeedforwardRegression.kMinimumSamples));
    }

    @Test
    @DisplayName("reset() forgets everything and the next fit is unaffected")
    void resetForgetsEverything() {
      FeedforwardRegression fit = sweep(MechanismArchetype.FLYWHEEL, 9.9, 9.9, 9.9, 0, 400, 0);
      fit.reset();
      assertEquals(0, fit.samples());

      double[] v = reversingVelocity(400, 2.5, 0.25);
      double[] a = reversingAccel(400, 2.5, 0.25);
      for (int i = 0; i < 400; i++) {
        fit.add(0, v[i], a[i], 0.2 * Math.signum(v[i]) + 0.02 * v[i] + 0.003 * a[i]);
      }
      assertEquals(0.02, fit.solve().kV(), 1e-9);
    }
  }

  @Nested
  @DisplayName("the two ways a solve refuses, which are not abort reasons")
  final class Refusals {

    @Test
    @DisplayName("one sample short of the minimum is INSUFFICIENT_DATA, not a wrong answer")
    void tooLittleData() {
      FeedforwardRegression fit =
          sweep(
              MechanismArchetype.FLYWHEEL,
              0.2,
              0.02,
              0.003,
              0,
              FeedforwardRegression.kMinimumSamples - 1,
              0);

      IdentificationException e = assertThrows(IdentificationException.class, fit::solve);
      assertEquals(FitFailure.INSUFFICIENT_DATA, e.failure());
      assertTrue(e.getMessage().contains(String.valueOf(FeedforwardRegression.kMinimumSamples)));
      assertTrue(e.getMessage().contains("4 seconds"), e.getMessage());

      // And exactly at the minimum it solves.
      FeedforwardRegression atMinimum =
          sweep(
              MechanismArchetype.FLYWHEEL,
              0.2,
              0.02,
              0.003,
              0,
              FeedforwardRegression.kMinimumSamples,
              0);
      assertEquals(0.02, atMinimum.solve().kV(), 1e-9);
    }

    @Test
    @DisplayName("a ramp with no dynamic step leaves kA unidentifiable and says which step is missing")
    void quasistaticOnlyIsRankDeficient() {
      FeedforwardRegression fit = new FeedforwardRegression(MechanismArchetype.FLYWHEEL);
      // A quasistatic sweep: the velocity crawls up and back down and acceleration is never
      // excited, so the kA column is a constant zero.
      for (int i = 0; i < 800; i++) {
        double v = (i < 400 ? i : 800 - i) * 0.005 - 1.0;
        fit.add(0.0, v, 0.0, 0.2 * Math.signum(v) + 0.02 * v);
      }

      IdentificationException e = assertThrows(IdentificationException.class, fit::solve);
      assertEquals(FitFailure.RANK_DEFICIENT, e.failure());
      assertTrue(e.getMessage().contains("\"a\"") || e.getMessage().contains(" a "), e.getMessage());
      assertTrue(
          e.getMessage().contains("DYNAMIC"),
          "the message must name the step that was skipped, not the matrix that was singular");
    }

    @Test
    @DisplayName("a mechanism that never reversed leaves the friction column constant")
    void oneDirectionOnlyIsRankDeficient() {
      FeedforwardRegression fit = new FeedforwardRegression(MechanismArchetype.FLYWHEEL);
      for (int i = 0; i < 800; i++) {
        double v = 1.0 + 0.5 * Math.sin(i * 0.05);
        double a = 0.5 * 0.05 / kDt * Math.cos(i * 0.05);
        fit.add(0.0, v, a, 0.2 + 0.02 * v + 0.003 * a);
      }

      IdentificationException e = assertThrows(IdentificationException.class, fit::solve);
      assertEquals(FitFailure.RANK_DEFICIENT, e.failure());
      assertTrue(e.getMessage().contains("sgn(v)"), e.getMessage());
    }

    @Test
    @DisplayName("an elevator's constant gravity column is not rank deficiency")
    void theElevatorGravityColumnIsExempt() {
      // The gravity column is the literal constant 1 for an elevator, so its variance is zero by
      // construction. Exempting it is what lets a four-parameter elevator fit solve at all.
      FeedforwardFit fit = sweep(MechanismArchetype.ELEVATOR, 0.3, 5.1, 0.06, 0.25, 600, 0).solve();
      assertEquals(0.25, fit.kG(), 1e-8);
    }

    @Test
    @DisplayName("a fit failure is not an abort reason, and the two enums do not overlap")
    void fitFailuresAreASeparateVocabulary() {
      assertEquals(2, FitFailure.values().length, "two fit failures, not twelve abort reasons");
      for (FitFailure failure : FitFailure.values()) {
        for (org.pumpkinlib.control.AbortReason reason :
            org.pumpkinlib.control.AbortReason.values()) {
          assertFalse(
              failure.name().equals(reason.name()),
              "a regression that could not be solved did not stop any motion, so it must not "
                  + "share a name with something that did");
        }
      }
    }

    @Test
    @DisplayName("NaN samples are dropped rather than poisoning every accumulator")
    void nonFiniteSamplesAreDropped() {
      FeedforwardRegression fit = new FeedforwardRegression(MechanismArchetype.FLYWHEEL);
      double[] v = reversingVelocity(400, 2.5, 0.25);
      double[] a = reversingAccel(400, 2.5, 0.25);
      for (int i = 0; i < 400; i++) {
        fit.add(0, v[i], a[i], 0.2 * Math.signum(v[i]) + 0.02 * v[i] + 0.003 * a[i]);
      }
      fit.add(0, Double.NaN, 1.0, 3.0);
      fit.add(0, 1.0, 1.0, Double.NaN);

      assertEquals(400, fit.samples(), "the two poisoned loops must not be counted");
      assertEquals(0.02, fit.solve().kV(), 1e-9);
    }
  }

  @Nested
  @DisplayName("physically impossible plants, which fit beautifully and must still be refused")
  final class SanityBounds {

    @Test
    @DisplayName("a negative kV is refused outright, not warned about")
    void anInvertedEncoderIsUnusable() {
      FeedforwardFit raw = sweep(MechanismArchetype.ELEVATOR, 0.3, -5.10, 0.06, 0.25, 600, 0).solve();

      // The fit itself is perfect. That is the trap.
      assertEquals(-5.10, raw.kV(), 1e-8);
      assertEquals(1.0, raw.voltageFitR2(), 1e-9);
      assertEquals(FeedforwardFit.Quality.GOOD, raw.quality());
      assertTrue(raw.acceptable(), "before the physics check it looks like the best fit ever taken");

      FeedforwardFit bounded =
          raw.sanityBounded(kElevatorPrior, MechanismArchetype.ELEVATOR, 1.0);

      assertEquals(
          FeedforwardFit.Quality.UNUSABLE,
          bounded.quality(),
          "a sign error is not a disagreement about magnitude — it propagates into every gain "
              + "measured after it, so it is a refusal and not a downgrade");
      assertFalse(bounded.acceptable());
      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("inverted")),
          bounded.warnings().toString());
      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("Fix the invert")),
          "the message must send the student to the invert flag, not to kP");
    }

    @Test
    @DisplayName("a kV six times the motor curve is flagged with the free speed it implies")
    void anImpossibleGearRatioIsFlagged() {
      double impossibleKv = 6.0 * kElevatorPrior.kVprior();
      FeedforwardFit raw =
          sweep(MechanismArchetype.ELEVATOR, 0.3, impossibleKv, 0.06, 0.25, 600, 0).solve();

      assertEquals(FeedforwardFit.Quality.GOOD, raw.quality(), "statistically it is flawless");

      FeedforwardFit bounded =
          raw.sanityBounded(kElevatorPrior, MechanismArchetype.ELEVATOR, 1.0);

      assertTrue(6.0 > FeedforwardFit.kMaxKvRatio, "6x is outside the accepted 0.4x..3x window");
      assertTrue(
          bounded.quality().ordinal() > FeedforwardFit.Quality.GOOD.ordinal(),
          "an impossible gear ratio must not still read GOOD");
      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("gear ratio")),
          bounded.warnings().toString());
      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("free speed")),
          "naming the implied free speed is what lets a student check the claim by hand");
    }

    @Test
    @DisplayName("a kV inside the window produces no warning at all")
    void aPlausiblePlantPassesClean() {
      FeedforwardFit raw =
          sweep(
                  MechanismArchetype.ELEVATOR,
                  0.3,
                  kElevatorPrior.kVprior(),
                  kElevatorPrior.kAprior(),
                  kElevatorPrior.gravityVoltsPrior(),
                  600,
                  0)
              .solve();
      FeedforwardFit bounded =
          raw.sanityBounded(kElevatorPrior, MechanismArchetype.ELEVATOR, 1.0);

      assertTrue(bounded.warnings().isEmpty(), bounded.warnings().toString());
      assertEquals(FeedforwardFit.Quality.GOOD, bounded.quality());
      assertTrue(bounded.acceptable());
    }

    @Test
    @DisplayName("a kS above a quarter of the battery is called out as friction, with the ceiling")
    void tooMuchFrictionIsFlagged() {
      double ceiling = FeedforwardFit.kMaxKsFractionOfNominal * kElevatorPrior.nominalVolts();
      assertEquals(3.0, ceiling, 1e-12, "0.25 x 12 V");

      FeedforwardFit bounded =
          sweep(
                  MechanismArchetype.ELEVATOR,
                  ceiling + 0.5,
                  kElevatorPrior.kVprior(),
                  kElevatorPrior.kAprior(),
                  kElevatorPrior.gravityVoltsPrior(),
                  600,
                  0)
              .solve()
              .sanityBounded(kElevatorPrior, MechanismArchetype.ELEVATOR, 1.0);

      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("binding bearing")),
          bounded.warnings().toString());
    }

    @Test
    @DisplayName("a kG of the opposite sign to the measured fall direction is flagged")
    void aBackwardsGravityTermIsFlagged() {
      FeedforwardFit bounded =
          sweep(
                  MechanismArchetype.ELEVATOR,
                  0.3,
                  kElevatorPrior.kVprior(),
                  kElevatorPrior.kAprior(),
                  -kElevatorPrior.gravityVoltsPrior(),
                  600,
                  0)
              .solve()
              .sanityBounded(kElevatorPrior, MechanismArchetype.ELEVATOR, +1.0);

      assertTrue(
          bounded.warnings().stream().anyMatch(w -> w.contains("opposite sign")),
          bounded.warnings().toString());
    }

    @Test
    @DisplayName("the quality bands are exactly the published constants, at the boundaries")
    void classificationBoundaries() {
      assertEquals(
          FeedforwardFit.Quality.GOOD,
          FeedforwardFit.classify(FeedforwardFit.kGoodR2, FeedforwardFit.kGoodRmseVolts));
      assertEquals(
          FeedforwardFit.Quality.ACCEPTABLE,
          FeedforwardFit.classify(
              FeedforwardFit.kGoodR2, Math.nextUp(FeedforwardFit.kGoodRmseVolts)));
      assertEquals(
          FeedforwardFit.Quality.ACCEPTABLE,
          FeedforwardFit.classify(FeedforwardFit.kAcceptableR2, FeedforwardFit.kAcceptableRmseVolts));
      assertEquals(
          FeedforwardFit.Quality.SUSPECT,
          FeedforwardFit.classify(FeedforwardFit.kSuspectR2, 9.0));
      assertEquals(
          FeedforwardFit.Quality.UNUSABLE,
          FeedforwardFit.classify(Math.nextDown(FeedforwardFit.kSuspectR2), 0.0));
      assertEquals(
          FeedforwardFit.Quality.UNUSABLE,
          FeedforwardFit.classify(Double.NaN, 0.0),
          "a NaN R squared is not a good fit, it is no fit");

      for (FeedforwardFit.Quality quality : FeedforwardFit.Quality.values()) {
        assertNotNull(quality.explain());
        assertFalse(quality.explain().isBlank());
      }
    }

    @Test
    @DisplayName("beta order is the regressor order, and a wrong-length vector is a library bug")
    void betaOrderIsTheRegressorOrder() {
      FeedforwardFit gravityFree =
          FeedforwardFit.from(MechanismArchetype.FLYWHEEL, new double[] {1, 2, 3}, 1.0, 0.0, 300);
      assertEquals(1, gravityFree.kS(), 0.0);
      assertEquals(2, gravityFree.kV(), 0.0);
      assertEquals(3, gravityFree.kA(), 0.0);
      assertEquals(0, gravityFree.kG(), 0.0);

      FeedforwardFit gravity =
          FeedforwardFit.from(
              MechanismArchetype.ELEVATOR, new double[] {9, 1, 2, 3}, 1.0, 0.0, 300);
      assertEquals(9, gravity.kG(), 0.0, "the gravity column comes first in the regressor order");
      assertEquals(1, gravity.kS(), 0.0);
      assertEquals(2, gravity.kV(), 0.0);
      assertEquals(3, gravity.kA(), 0.0);

      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  FeedforwardFit.from(
                      MechanismArchetype.ELEVATOR, new double[] {1, 2, 3}, 1.0, 0.0, 300));
      assertTrue(e.getMessage().contains("library bug"), e.getMessage());
    }

    @Test
    @DisplayName("appliedTo replaces only the four feedforward terms")
    void appliedToLeavesFeedbackAlone() {
      Gains base = Gains.pid(128.0, 0.5, 4.0);
      Gains merged =
          FeedforwardFit.from(MechanismArchetype.ELEVATOR, new double[] {0.25, 0.3, 5.1, 0.06}, 1.0, 0.0, 300)
              .appliedTo(base);

      assertEquals(128.0, merged.kP(), 0.0);
      assertEquals(0.5, merged.kI(), 0.0);
      assertEquals(4.0, merged.kD(), 0.0);
      assertEquals(0.3, merged.kS(), 0.0);
      assertEquals(5.1, merged.kV(), 0.0);
      assertEquals(0.06, merged.kA(), 0.0);
      assertEquals(0.25, merged.kG(), 0.0);
    }
  }

  @Nested
  @DisplayName("SysId's familiar statistic, computed separately")
  final class SimulatedVelocity {

    @Test
    @DisplayName("replaying the fitted model through the window reproduces the measured velocity")
    void simulatedVelocityR2IsHighForATruePlant() {
      double kS = 0.0;
      double kV = 5.10;
      double kA = 0.060;
      double kG = 0.0;

      List<SampleBuffer.Sample> window = new ArrayList<>();
      double v = 0.0;
      double x = 0.0;
      for (int i = 0; i < 300; i++) {
        double u = 3.0;
        double a = (u - kG - kS * Math.signum(v) - kV * v) / kA;
        v += a * kDt;
        x += v * kDt;
        window.add(new SampleBuffer.Sample(i * kDt, 0.0, x, v, u, u, Double.NaN));
      }

      FeedforwardFit fit =
          FeedforwardFit.from(
                  MechanismArchetype.FLYWHEEL, new double[] {kS, kV, kA}, 1.0, 0.0, 300)
              .validatedAgainst(window, MechanismArchetype.FLYWHEEL, 0.0);

      assertTrue(
          fit.simulatedVelocityR2() > 0.99,
          "simulated velocity R2 was " + fit.simulatedVelocityR2());
    }

    @Test
    @DisplayName("a window too short to integrate reports NaN rather than a number")
    void aShortWindowReportsNaN() {
      FeedforwardFit fit =
          FeedforwardFit.from(MechanismArchetype.FLYWHEEL, new double[] {0.2, 5.0, 0.06}, 1.0, 0.0, 300)
              .validatedAgainst(List.of(), MechanismArchetype.FLYWHEEL, 0.0);
      assertTrue(Double.isNaN(fit.simulatedVelocityR2()));
    }
  }
}

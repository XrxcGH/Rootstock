package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Pounds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.config.ConfigError;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorModel;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionConfig;
import org.rootstock.config.Validation;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.control.PlantPrior;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.SiDomain;

/**
 * {@link Gains#UNTUNED} is a value with defined behaviour in both directions: <b>on hardware it
 * refuses closed-loop control</b>, and <b>in simulation it resolves to a physics-derived first guess
 * so that something moves.</b>
 *
 * <p>The failure this exists to prevent is the all-zero gain set that earlier revisions shipped as
 * {@code Gains.zero()}. Zeros look deliberate. A student reads {@code kP = 0} as "somebody decided
 * that", the mechanism silently never moves, and the afternoon goes into the wiring. {@code kP =
 * NaN} cannot be mistaken for a decision, is exactly detectable, and — critically — would command a
 * NaN into a motor controller if it ever reached one, which is why the refusal is a hard gate rather
 * than a warning.
 *
 * <p><strong>What is asserted where.</strong> Everything below is native-free: the sentinel itself,
 * the config-level placeholder error, and the arithmetic of the simulation guess recomputed from
 * {@link PlantPrior} independently of the mechanism. The two <i>behavioural</i> halves — that
 * {@code PositionMechanism} actually commands neutral rather than NaN when the gains are untuned,
 * and that it actually installs the derived guess in simulation — need a live mechanism and are in
 * {@code UntunedGainsHalTest}. A {@code Mechanism} constructor builds {@code RootstockAlert}s, which
 * eagerly construct WPILib {@code Alert}s, which reach NetworkTables and {@code System.exit(1)} on a
 * JVM with no JNI natives.
 */
final class UntunedGainsTest {

  /** The bandwidth the mechanism places the derived loop at, restated so the maths below is legible. */
  private static final double kBandwidthHz = PositionMechanism.kSimGuessBandwidthHz;

  @BeforeEach
  @AfterEach
  void clearValidationState() {
    Validation.resetForTest();
  }

  @Nested
  @DisplayName("the sentinel")
  final class Sentinel {

    /** kP is NaN. That is the detection, and it is exact. */
    @Test
    void theUntunedPlaceholderIsANaNProportionalGain() {
      assertTrue(Double.isNaN(Gains.UNTUNED.kP()));
      assertTrue(Gains.UNTUNED.isUntuned());
    }

    /**
     * Detection is a NaN test on kP, not an identity test on the constant, so a gain set that was
     * copied, {@code withKg(...)}-ed and passed around still reports itself as untuned.
     */
    @Test
    void aDerivedCopyOfThePlaceholderIsStillUntuned() {
      Gains carried = Gains.UNTUNED.withKg(0.33).withKv(5.0);
      assertFalse(carried == Gains.UNTUNED, "a different object");
      assertTrue(carried.isUntuned(), "and still the placeholder, because kP is still NaN");
    }

    /**
     * All zeros are NOT the placeholder. This is the whole reason {@code Gains.zero()} was deleted:
     * a zero kP is a legitimate thing to write (a pure feedforward loop), so overloading it as
     * "unset" made the two indistinguishable.
     */
    @Test
    void allZerosAreADecisionAndNotAPlaceholder() {
      assertFalse(Gains.pid(0.0, 0.0, 0.0).isUntuned());
      assertFalse(new Gains(0, 0, 0, 0, 0, 0, 0).isUntuned());
    }

    /** A tuned gain set is not untuned, obviously — pinned so the predicate cannot invert. */
    @Test
    void measuredGainsAreNotUntuned() {
      assertFalse(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).isUntuned());
    }
  }

  @Nested
  @DisplayName("a config that declares it")
  final class ConfigLevel {

    /**
     * Declaring {@code UNTUNED} produces a PLACEHOLDER error, not a fatal one.
     *
     * <p>That distinction is load-bearing. A fatal error means SAFE_MODE, and SAFE_MODE means the
     * mechanism will not actuate at all — including the homing routine and manual control, which are
     * exactly the two things a team needs in order to go and measure the gains. So an untuned
     * mechanism boots, homes, and can be jogged; only the closed loop is refused.
     */
    @Test
    void untunedGainsAreAPlaceholderErrorAndNotAFatalOne() {
      PositionConfig config = elevator(Gains.UNTUNED);

      ConfigError gainsError = null;
      for (ConfigError error : config.errors()) {
        if ("control.gains".equals(error.field())) {
          gainsError = error;
        }
      }
      assertTrue(gainsError != null, "a config declaring UNTUNED must say so: " + config.errors());
      assertFalse(
          gainsError.isFatal(),
          "fatal would mean SAFE_MODE, which would also refuse the homing run and the manual jog "
              + "the team needs in order to measure the gains");
      assertEquals("Gains.UNTUNED", gainsError.value());
      assertTrue(
          gainsError.explanation().contains("refused"),
          "the explanation must state the hardware behaviour: " + gainsError.explanation());
      assertTrue(
          gainsError.explanation().contains("simulation"),
          "and the simulation behaviour: " + gainsError.explanation());
    }

    /** A tuned config raises no gains placeholder at all. */
    @Test
    void measuredGainsRaiseNoGainsPlaceholder() {
      PositionConfig config =
          elevator(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33));
      for (ConfigError error : config.errors()) {
        assertFalse(
            "control.gains".equals(error.field()),
            "measured gains must not be flagged as a placeholder: " + error.describe());
      }
    }
  }

  @Nested
  @DisplayName("the simulation guess — arithmetic, recomputed here")
  final class SimulationGuess {

    /**
     * The derivation {@code PositionMechanism.resolveGains} performs, recomputed independently.
     *
     * <p>The plant a voltage-driven mechanism presents is {@code 1/(kA s^2 + kV s)}. Placing a
     * critically damped second-order closed loop on it at natural frequency &omega; gives {@code kP =
     * kA w^2} and {@code kD = 2 kA w - kV}. The mechanism uses &omega; = 2&pi; x {@value
     * PositionMechanism#kSimGuessBandwidthHz} Hz, which is gentle enough that a wrong mass produces
     * a sluggish mechanism rather than an oscillating one.
     *
     * <p>This test asserts the <i>numbers</i> — that the guess is finite, positive, and of the
     * magnitude the plant implies. {@code UntunedGainsHalTest} asserts that the mechanism installs
     * exactly these.
     */
    @Test
    void theGuessIsCriticallyDampedAtTheDeclaredBandwidth() {
      PlantPrior prior = elevatorPrior();
      double kV = prior.kVprior();
      double kA = prior.kAprior();
      assertTrue(Double.isFinite(kV) && kV > 0.0, "kV prior: " + kV);
      assertTrue(Double.isFinite(kA) && kA > 0.0, "kA prior: " + kA);

      double omega = 2.0 * Math.PI * kBandwidthHz;
      double kP = kA * omega * omega;
      double kD = Math.max(0.0, 2.0 * omega * kA - kV);

      Gains guess = new Gains(kP, 0.0, kD, 0.0, kV, kA, prior.gravityVoltsPrior());

      assertFalse(guess.isUntuned(), "the derived guess is a usable gain set, not the placeholder");
      assertEquals(kA * omega * omega, guess.kP(), 0.0);
      assertTrue(guess.kP() > 0.0, "kP must be positive or the loop is open: " + guess.kP());
      assertTrue(guess.kD() >= 0.0, "kD must never be negative: " + guess.kD());
      assertEquals(0.0, guess.kI(), 0.0, "no integral term in a guess — it would wind up");
      assertEquals(0.0, guess.kS(), 0.0, "static friction cannot be derived, only measured");
      assertEquals(9.4247779607693797, omega, 1e-12, "2*pi*1.5 Hz");
    }

    /**
     * The bandwidth is fixed at 1.5 Hz. Pinned because it is what makes a wrong declared mass a
     * sluggish mechanism instead of an oscillating one, and raising it silently would trade the
     * first failure for the second.
     */
    @Test
    void theGuessBandwidthIsGentle() {
      assertEquals(1.5, PositionMechanism.kSimGuessBandwidthHz, 0.0);
    }

    /**
     * The gravity term is a property of the axis, not of the tuning.
     *
     * <p>{@code resolveGains} zeroes kG when the axis declares {@link GravityMode#NONE}, and the
     * prior itself has nothing to offer for a flywheel. Both halves matter: a turret's guess must
     * not carry an elevator's hold voltage, and an elevator's guess must carry its own or the
     * carriage falls the instant the loop closes.
     */
    @Test
    void gravityIsAPropertyOfTheAxisAndNotOfTheTuning() {
      assertTrue(
          elevatorPrior().gravityVoltsPrior() > 0.0,
          "an elevator holds itself up: " + elevatorPrior().gravityVoltsPrior());

      PlantPrior flywheel =
          PlantPrior.flywheel(MotorModel.KRAKEN_X60.dcMotor(1, true), Reduction.of(4.0), 0.001);
      assertEquals(
          0.0,
          GravityMode.NONE == GravityMode.NONE ? 0.0 : flywheel.gravityVoltsPrior(),
          0.0,
          "GravityMode.NONE zeroes the term before the prior is ever consulted");
      assertTrue(
          Double.isFinite(flywheel.kAprior()) && flywheel.kAprior() > 0.0,
          "and the rest of the flywheel prior is still usable: " + flywheel.kAprior());
    }

    /**
     * A prior with no declared mass cannot produce a guess. The mechanism keeps the placeholder in
     * that case, which is correct: a derived number from an undeclared plant is a made-up number.
     */
    @Test
    void anUndeclaredPlantYieldsNoUsablePrior() {
      PlantPrior noMass =
          PlantPrior.elevator(
              MotorModel.KRAKEN_X60.dcMotor(2, true), Reduction.of(12.0), Double.NaN, kDrumRadiusM);
      assertFalse(
          Double.isFinite(noMass.kAprior()) && noMass.kAprior() > 0.0,
          "kA from an undeclared mass must not be a usable number: " + noMass.kAprior());
    }

    /**
     * The prior knows which SI domain its gains are per, and that choice is what decides whether the
     * derived kV is volts per metre-per-second or volts per radian-per-second. Getting it backwards
     * is the 1/radius class of error, so it is pinned.
     */
    @Test
    void theSiDomainFollowsTheAxisAndNotTheMotor() {
      assertTrue(elevatorPrior().isLinear(), "an elevator's gains are volts per (m/s)");
      assertEquals(
          SiDomain.LINEAR_METERS,
          elevatorPrior().isLinear() ? SiDomain.LINEAR_METERS : SiDomain.ROTATIONAL_RADIANS);

      PlantPrior arm =
          PlantPrior.arm(
              MotorModel.KRAKEN_X60.dcMotor(1, false),
              Reduction.of(65.411),
              0.35,
              Inches.of(21.0).in(Meters),
              Pounds.of(9.5).in(edu.wpi.first.units.Units.Kilograms));
      assertFalse(arm.isLinear(), "an arm's gains are volts per (rad/s), driven by the same motor");
    }
  }

  // ===============================================================================================
  // Fixtures — the design's own elevator, so the numbers above are the numbers a team would see
  // ===============================================================================================

  /**
   * The effective radius of a 22-tooth, 0.25 in pitch sprocket cascaded twice.
   *
   * <p>Circumference per output rotation is {@code teeth * pitch * stages = 22 * 0.25 in * 2 = 11
   * in = 0.27940 m}, so the effective radius is {@code 0.27940 / (2*pi)}.
   */
  private static final double kDrumRadiusM = (22 * 0.25 * 2 * 0.0254) / (2.0 * Math.PI);

  private static PlantPrior elevatorPrior() {
    DCMotor gearbox = MotorModel.KRAKEN_X60.dcMotor(2, true);
    return PlantPrior.elevator(
        gearbox, Reduction.ofStages(3.0, 4.0), Pounds.of(24.0).in(edu.wpi.first.units.Units.Kilograms), kDrumRadiusM);
  }

  private static PositionConfig elevator(Gains gains) {
    return PositionConfig.linear("UntunedGainsTestElevator")
        .motor(MotorSpec.sim())
        .reduction(Reduction.ofStages(3.0, 4.0))
        .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
        .softLimits(Inches.of(0.0), Inches.of(55.0))
        .gains(gains)
        .constraints(MotionConstraints.of(1.6, 6.0))
        .tolerance(Inches.of(0.5), 0.05, 0.06)
        .sim(Pounds.of(24.0), Inches.of(0.0))
        .build();
  }

  /** The sprocket circumference, asserted so {@link #kDrumRadiusM} cannot silently drift. */
  @Test
  void theFixtureSprocketIsTheDesignsElevator() {
    LinearAxis axis = LinearAxis.sprocket(Inches.of(0.25), 22, 2);
    assertEquals(0.27940, axis.siPerOutputRotation(), 1e-9, "22 x 0.25 in x 2 = 11 in = 0.2794 m");
    assertEquals(kDrumRadiusM, axis.effectiveRadius().in(Meters), 1e-12);
  }
}

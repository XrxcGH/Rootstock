package org.pumpkinlib.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.SiDomain;

/**
 * {@link Gains}: seven doubles, canonicalised to volts-per-SI, with an explicit "not yet measured"
 * value.
 *
 * <p><strong>Why seven and not more.</strong> Every vendor's gain slot is some subset of the same
 * seven terms under different names — Phoenix {@code Slot0Configs}, REV {@code ClosedLoopConfig},
 * WPILib's three {@code *Feedforward} classes. Adding an eighth (a vendor-specific "kF", a
 * per-backend velocity feedforward, an integral zone stored as a gain) would mean a gain that only
 * some backends can honour, which is the thing this record exists to prevent. The count is asserted
 * reflectively so an added component fails this test rather than silently changing the tuning file
 * format.
 *
 * <p><strong>Why volts-per-SI and not volts-per-user-unit.</strong> Gains describe the
 * <em>mechanism</em>, not the gearbox and not the units a driver thinks in. If {@code kP} were
 * volts-per-degree, changing a gearbox ratio would change every gain; because it is volts-per-radian
 * (or volts-per-metre on a linear axis), it does not. {@link GainId#unitFor} is the single place
 * that renders that decision, and the strings it produces appear in the boot dump, the tuning UI and
 * the persisted gain file — so they must agree.
 */
final class GainsTest {

  // ===============================================================================================
  // Exactly seven doubles
  // ===============================================================================================

  @Nested
  @DisplayName("the shape of the record")
  final class Shape {

    @Test
    @DisplayName("Gains has exactly seven components, and every one of them is a double")
    void sevenDoublesAndNoMore() {
      RecordComponent[] components = Gains.class.getRecordComponents();
      assertEquals(
          7,
          components.length,
          "Gains grew or shrank: "
              + Arrays.stream(components).map(RecordComponent::getName).toList());
      for (RecordComponent component : components) {
        assertEquals(
            double.class,
            component.getType(),
            component.getName() + " must be a primitive double, not a boxed or unit type");
      }
      assertEquals(
          List.of("kP", "kI", "kD", "kS", "kV", "kA", "kG"),
          Arrays.stream(components).map(RecordComponent::getName).toList());
    }

    @Test
    @DisplayName("GainId names exactly those seven, and its keys match the component names")
    void gainIdCoversExactlyTheSevenComponents() {
      assertEquals(7, GainId.values().length);
      List<String> keys = Arrays.stream(GainId.values()).map(GainId::key).sorted().toList();
      List<String> components =
          Arrays.stream(Gains.class.getRecordComponents())
              .map(RecordComponent::getName)
              .sorted()
              .toList();
      assertEquals(components, keys);
    }

    @Test
    @DisplayName("get(id) and with(id, v) address every one of the seven independently")
    void byIdAccessIsTotalAndIndependent() {
      Gains base = new Gains(1, 2, 3, 4, 5, 6, 7);
      for (GainId id : GainId.values()) {
        Gains changed = base.with(id, 99.0);
        assertEquals(99.0, changed.get(id), 0.0, id + " did not take the new value");
        // Every OTHER gain must be untouched — a with(...) that wrote two slots would be
        // invisible until a tuning session produced a mechanism that behaved like neither.
        for (GainId other : GainId.values()) {
          if (other != id) {
            assertEquals(
                base.get(other), changed.get(other), 0.0, "with(" + id + ") also moved " + other);
          }
        }
      }
    }

    @Test
    @DisplayName("every with*() is a copy; the receiver is never mutated")
    void withMethodsCopy() {
      Gains base = new Gains(1, 2, 3, 4, 5, 6, 7);
      assertEquals(new Gains(9, 2, 3, 4, 5, 6, 7), base.withKp(9));
      assertEquals(new Gains(1, 9, 3, 4, 5, 6, 7), base.withKi(9));
      assertEquals(new Gains(1, 2, 9, 4, 5, 6, 7), base.withKd(9));
      assertEquals(new Gains(1, 2, 3, 9, 5, 6, 7), base.withKs(9));
      assertEquals(new Gains(1, 2, 3, 4, 9, 6, 7), base.withKv(9));
      assertEquals(new Gains(1, 2, 3, 4, 5, 9, 7), base.withKa(9));
      assertEquals(new Gains(1, 2, 3, 4, 5, 6, 9), base.withKg(9));
      assertEquals(new Gains(1, 2, 3, 4, 5, 6, 7), base, "the receiver was mutated");
    }

    @Test
    @DisplayName("the factories fill only the terms they name")
    void factoriesLeaveEverythingElseAtZero() {
      assertEquals(new Gains(80.0, 0, 2.0, 0, 0, 0, 0), Gains.pid(80.0, 0.0, 2.0));
      assertEquals(new Gains(0, 0, 0, 0.22, 5.0, 0.06, 0), Gains.feedforward(0.22, 5.0, 0.06));
    }
  }

  // ===============================================================================================
  // UNTUNED: the explicit absence of a measurement
  // ===============================================================================================

  @Nested
  @DisplayName("Gains.UNTUNED")
  final class Untuned {

    @Test
    @DisplayName("marks itself with a NaN kP, which is the flag closed loop checks")
    void untunedIsNaNKp() {
      assertTrue(Double.isNaN(Gains.UNTUNED.kP()));
      assertTrue(Gains.UNTUNED.isUntuned());
      // NaN in kP, not zero. A zero kP is a legitimate configuration (pure feedforward); a NaN
      // one cannot be produced by measurement, so it is unambiguously "nobody has measured this".
      assertNotEquals(0.0, Gains.UNTUNED.kP());
    }

    @Test
    @DisplayName("has zero for the other six, so nothing is silently applied while untuned")
    void everyOtherTermIsZero() {
      assertEquals(0.0, Gains.UNTUNED.kI(), 0.0);
      assertEquals(0.0, Gains.UNTUNED.kD(), 0.0);
      assertEquals(0.0, Gains.UNTUNED.kS(), 0.0);
      assertEquals(0.0, Gains.UNTUNED.kV(), 0.0);
      assertEquals(0.0, Gains.UNTUNED.kA(), 0.0);
      assertEquals(0.0, Gains.UNTUNED.kG(), 0.0);
    }

    @Test
    @DisplayName("any measured kP clears the flag; the other six do not")
    void onlyKpClearsTheFlag() {
      assertFalse(Gains.UNTUNED.withKp(1.0).isUntuned());
      assertFalse(Gains.pid(80.0, 0.0, 2.0).isUntuned());
      assertFalse(Gains.pid(0.0, 0.0, 0.0).isUntuned(), "a deliberate zero kP IS a tuning");
      // Filling in feedforward alone leaves the mechanism still untuned for closed loop, which is
      // exactly right: kS/kV/kA do not make a position loop safe.
      assertTrue(Gains.UNTUNED.withKv(5.0).withKa(0.06).withKg(0.33).isUntuned());
      assertTrue(Gains.UNTUNED.with(GainId.KS, 0.22).isUntuned());
      assertFalse(Gains.UNTUNED.with(GainId.KP, 80.0).isUntuned());
    }

    @Test
    @DisplayName("describe() renders NaN rather than pretending to a value")
    void describeShowsNaN() {
      String text = Gains.UNTUNED.describe(SiDomain.LINEAR_METERS);
      assertTrue(text.contains("NaN"), text);
      assertTrue(text.contains("kP"), text);
    }
  }

  // ===============================================================================================
  // Volts-per-SI canonicalisation
  // ===============================================================================================

  @Nested
  @DisplayName("gains are volts-per-SI")
  final class Canonicalisation {

    @Test
    @DisplayName("on a linear axis the SI unit is the METRE, in every term that has one")
    void linearGainsAreVoltsPerMetre() {
      assertEquals("V", GainId.KS.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V", GainId.KG.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/(m/s)", GainId.KV.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/(m/s^2)", GainId.KA.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/m", GainId.KP.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/(m*s)", GainId.KI.unitFor(SiDomain.LINEAR_METERS));
      assertEquals("V/(m/s)", GainId.KD.unitFor(SiDomain.LINEAR_METERS));
    }

    @Test
    @DisplayName("on a rotary axis the SI unit is the RADIAN — never the degree")
    void rotaryGainsAreVoltsPerRadian() {
      assertEquals("V", GainId.KS.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V", GainId.KG.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V/(rad/s)", GainId.KV.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V/(rad/s^2)", GainId.KA.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V/rad", GainId.KP.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V/(rad*s)", GainId.KI.unitFor(SiDomain.ROTATIONAL_RADIANS));
      assertEquals("V/(rad/s)", GainId.KD.unitFor(SiDomain.ROTATIONAL_RADIANS));

      // The user unit of a rotary axis is the DEGREE, but gains are never per-degree. If they
      // were, every gain would need rescaling by 57.29578 whenever a log or a UI changed units.
      for (GainId id : GainId.values()) {
        assertFalse(
            id.unitFor(SiDomain.ROTATIONAL_RADIANS).contains("deg"),
            id + " leaked a user unit into a gain unit");
      }
    }

    @Test
    @DisplayName("the template is domain-free, and the domain is applied exactly once")
    void templateCarriesThePlaceholder() {
      assertEquals("V/unit", GainId.KP.unitTemplate());
      assertEquals("V/(unit/s)", GainId.KV.unitTemplate());
      assertEquals("V/(unit/s^2)", GainId.KA.unitTemplate());
      // kS and kG are volts flat: they do not scale with the axis at all.
      assertEquals("V", GainId.KS.unitTemplate());
      assertEquals("V", GainId.KG.unitTemplate());
      for (GainId id : GainId.values()) {
        assertFalse(id.unitFor(SiDomain.LINEAR_METERS).contains("unit"), id.toString());
        assertFalse(id.unitFor(SiDomain.ROTATIONAL_RADIANS).contains("unit"), id.toString());
      }
    }

    @Test
    @DisplayName("describe() prints every gain with the unit for the axis it belongs to")
    void describeCarriesTheUnits() {
      Gains elevator = Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33);
      String linear = elevator.describe(SiDomain.LINEAR_METERS);
      assertTrue(linear.contains("kP 80.0000 V/m"), linear);
      assertTrue(linear.contains("kV 5.0000 V/(m/s)"), linear);
      assertTrue(linear.contains("kG 0.3300 V"), linear);
      assertFalse(linear.contains("rad"), linear);

      Gains arm = Gains.pid(5.0, 0.0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29);
      String rotary = arm.describe(SiDomain.ROTATIONAL_RADIANS);
      assertTrue(rotary.contains("kP 5.0000 V/rad"), rotary);
      assertTrue(rotary.contains("kV 1.2500 V/(rad/s)"), rotary);
      assertTrue(rotary.contains("kG 0.2900 V"), rotary);
      assertFalse(rotary.contains("V/m"), rotary);
      assertFalse(rotary.contains("deg"), rotary);
    }

    @Test
    @DisplayName("the same seven numbers mean different things on the two domains")
    void thesameNumbersRenderDifferently() {
      Gains g = Gains.pid(5.0, 0.0, 0.18);
      assertNotEquals(g.describe(SiDomain.LINEAR_METERS), g.describe(SiDomain.ROTATIONAL_RADIANS));
    }
  }

  // ===============================================================================================
  // The physics prior that stands in for UNTUNED gains in simulation
  // ===============================================================================================

  @Nested
  @DisplayName("the physics prior UNTUNED resolves to in simulation")
  final class PhysicsPrior {

    /**
     * The §5.4 elevator: 24 lb carriage, 12:1, effective radius 0.0444679 m (which already includes
     * the 2x cascade — that is what "effective" means).
     */
    private static final double kCarriageKg = 24.0 * 0.45359237; // = 10.886217 kg
    private static final double kEffectiveRadiusM = 0.279400 / (2.0 * Math.PI); // = 0.0444679 m

    private static PlantPrior elevatorPrior(boolean foc) {
      return PlantPrior.elevator(
          foc ? DCMotor.getKrakenX60Foc(2) : DCMotor.getKrakenX60(2),
          Reduction.ofStages(3.0, 4.0),
          kCarriageKg,
          kEffectiveRadiusM);
    }

    @Test
    @DisplayName("the carriage mass and effective radius are the design's numbers")
    void theInputsAreTheDesignsInputs() {
      assertEquals(10.886217, kCarriageKg, 1e-6);
      assertEquals(0.0444679, kEffectiveRadiusM, 1e-7);
      // weight = 10.8862169 x 9.80665            = 106.757319 N
      // torque at the drum = 106.757319 x 0.0444678911 = 4.747273 N.m
      //
      // NOTE: design/01 §5.4's table prints 106.756 N and 4.74731 N.m. Both are a hair low —
      // recomputed here from 24 lb and the exact effective radius. The kG it concludes with
      // (0.33 V) is unaffected at two decimal places.
      assertEquals(106.757319, kCarriageKg * 9.80665, 1e-6);
      assertEquals(4.747273, kCarriageKg * 9.80665 * kEffectiveRadiusM, 1e-6);
    }

    @Test
    @DisplayName("kG on the trapezoidal curve is 0.33 V — design/01 §5.4's stated upper bound")
    void trapezoidalGravityPriorIsThirtyThreeCentivolts() {
      // kG = m*g*r*R / (G*kT). Two Kraken X60s on the trapezoidal curve:
      //   R  = 12 V / 732 A  = 0.0163934 ohm   (WPILib halves R for the 2-motor DCMotor)
      //   kT = 14.18 / 732   = 0.0193716 N.m/A (unchanged by motor count)
      //   4.747273 x 0.0327869 / 2 / (12 x 0.0193716) = 0.334787 V
      double kg = elevatorPrior(false).gravityVoltsPrior();
      assertEquals(0.334787, kg, 1e-6);
      assertEquals("0.33", String.format(java.util.Locale.ROOT, "%.2f", kg));
    }

    @Test
    @DisplayName("kG on the FOC curve is 0.25 V — the design's caveat reproduces exactly")
    void focGravityPriorIsLower() {
      // Same mechanism, FOC commutation: R = 12/966 = 0.0124224 ohm, kT = 18.74/966 = 0.0194.
      // design/01 §5.4 says "with FOC the same hold needs about 0.25 V", and it does.
      double kg = elevatorPrior(true).gravityVoltsPrior();
      assertEquals(0.253323, kg, 1e-6);
      assertEquals("0.25", String.format(java.util.Locale.ROOT, "%.2f", kg));
      assertTrue(
          kg < elevatorPrior(false).gravityVoltsPrior(),
          "FOC gives more torque per amp, so it needs fewer volts to hold station");
    }

    @Test
    @DisplayName("kV agrees with 12 V / free speed, which is where the design's 5.00 came from")
    void kvPriorAgreesWithTheFreeSpeedEstimate() {
      // The prior derives kV from the motor's Kv curve; the design derived it as 12 / 2.251 m/s.
      // They are the same physics and must land within a few percent of each other.
      double kv = elevatorPrior(true).kVprior();
      double fromFreeSpeed = 12.0 / 2.250722;
      assertEquals(5.309545, kv, 1e-6);
      assertEquals(5.331622, fromFreeSpeed, 1e-6);
      assertTrue(
          Math.abs(kv - fromFreeSpeed) / fromFreeSpeed < 0.01,
          "kV prior " + kv + " and 12/freeSpeed " + fromFreeSpeed + " disagree by over 1%");
      // The design ships 5.00, deliberately below both, to leave headroom for kS.
      assertTrue(5.00 < kv, "the shipped kV should sit below the no-loss prior");
    }

    @Test
    @DisplayName("free speed from the prior is within 0.5% of design/01 §5.4's 2.2507 m/s")
    void freeSpeedPriorMatchesTheDesign() {
      // The prior evaluates 12 V x Kv / G x r. That is NOT identical to the motor curve's own
      // free speed, because Kv is defined as freeSpeed / (12 - R * I_free) — so multiplying it
      // back by a full 12 V adds the no-load current drop back in. The prior therefore reads
      // 2.260081 m/s against the curve's 2.250722 m/s: 0.42% high, and high in the safe
      // direction for a cruise-velocity sanity check.
      double prior = elevatorPrior(true).freeSpeedSi();
      assertEquals(2.260081, prior, 1e-6);
      assertTrue(Math.abs(prior - 2.250722) / 2.250722 < 0.005, "prior was " + prior);
    }

    @Test
    @DisplayName("the §5.5 arm's kG prior is 0.29 V, as its derivation block states")
    void armGravityPrior() {
      // 9.5 lb x 0.45359237 = 4.3091275 kg; centre of mass 10.5 in = 0.266700 m
      // torque = 4.3091275 x 9.80665 x 0.266700 = 11.2702367 N.m
      // one Kraken X60: R = 12/366 = 0.0327869, kT = 7.09/366 = 0.0193716
      // 11.2702367 x 0.0327869 / (65.4111111 x 0.0193716) = 0.291619 V
      double massKg = 9.5 * 0.45359237;
      double comMetres = 10.5 * 0.0254;
      assertEquals(4.3091275, massKg, 1e-7);
      assertEquals(0.266700, comMetres, 1e-9);
      assertEquals(11.2702367, massKg * 9.80665 * comMetres, 1e-6);

      PlantPrior prior =
          PlantPrior.arm(
              DCMotor.getKrakenX60(1),
              Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12),
              SimConfigMoi.thinRod(massKg, comMetres),
              comMetres,
              massKg);
      assertEquals(0.291619, prior.gravityVoltsPrior(), 1e-6);
      assertEquals("0.29", String.format(java.util.Locale.ROOT, "%.2f", prior.gravityVoltsPrior()));

      // The same prior independently reproduces the other two gains design/01 §5.5 ships:
      // kV = 1.242 against a shipped 1.25, and kA = 0.0106 against a shipped 0.010. Both were
      // rescaled in revision 4 from 34.126:1 to the true 65.411:1, and this is the check that
      // the rescale landed on the physics rather than on a typo.
      assertEquals(1.242434, prior.kVprior(), 1e-6);
      assertEquals(0.010574, prior.kAprior(), 1e-6);
      assertTrue(Math.abs(prior.kVprior() - 1.25) / 1.25 < 0.01, "kV prior " + prior.kVprior());
      assertTrue(Math.abs(prior.kAprior() - 0.010) / 0.010 < 0.10, "kA prior " + prior.kAprior());
    }

    @Test
    @DisplayName("an unusable prior returns NaN rather than a plausible wrong number")
    void unusablePriorIsNaN() {
      PlantPrior broken = new PlantPrior(null, Reduction.of(12.0), 10.0, Double.NaN, 0.04, 12.0);
      assertTrue(Double.isNaN(broken.kVprior()));
      assertTrue(Double.isNaN(broken.kAprior()));
      assertTrue(Double.isNaN(broken.gravityVoltsPrior()));
      assertTrue(Double.isNaN(broken.freeSpeedSi()));
      // ... and it says why, without throwing.
      List<String> problems = broken.validate("Elevator");
      assertEquals(1, problems.size(), problems.toString());
      assertTrue(problems.get(0).startsWith("Elevator:"), problems.get(0));
      assertTrue(problems.get(0).contains("Fix:"), problems.get(0));
    }

    /** Tiny helper so the arm's moment of inertia is derived rather than asserted as a literal. */
    private static final class SimConfigMoi {
      private SimConfigMoi() {}

      /** A uniform rod pivoting about one end: {@code (1/3) m L^2}, with {@code L = 2 * com}. */
      static double thinRod(double massKg, double comMetres) {
        double length = 2.0 * comMetres;
        return massKg * length * length / 3.0;
      }
    }
  }

  // ===============================================================================================
  // realOrSim
  // ===============================================================================================

  @Nested
  @DisplayName("realOrSim")
  final class RealOrSim {

    @Test
    @DisplayName("the two branches are distinguishable, so the choice is observable")
    void theTwoBranchesDiffer() {
      // The §5.4 elevator ships a lower kP for hardware than for simulation, because a simulated
      // plant has no backlash and no chain slop. If the two were equal the selector would be
      // untestable and pointless.
      Gains real = Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33);
      Gains sim = Gains.pid(150.0, 0.0, 0.0).withKv(5.0).withKa(0.06).withKg(0.33);
      assertNotEquals(real, sim);
      assertEquals(80.0, real.kP(), 0.0);
      assertEquals(150.0, sim.kP(), 0.0);
      // ... and the sim branch deliberately carries no kS: static friction is not simulated.
      assertEquals(0.0, sim.kS(), 0.0);
      assertEquals(0.22, real.kS(), 0.0);
    }

    @Test
    @Tag("hal")
    @DisplayName("selects by platform, returning one of the two instances unchanged")
    void selectsByPlatform() {
      // Tagged "hal": Gains.realOrSim reads Platform.isReal(), which reaches RobotBase and
      // therefore the WPILib native loader. On a JVM without natives that is a
      // NoClassDefFoundError, so this assertion can only run where `./gradlew halTest` runs.
      Gains real = Gains.pid(80.0, 0.0, 2.0);
      Gains sim = Gains.pid(150.0, 0.0, 0.0);
      Gains chosen = Gains.realOrSim(real, sim);
      assertTrue(chosen == real || chosen == sim, "realOrSim must SELECT, never blend");
      if (org.pumpkinlib.core.compat.Platform.isReal()) {
        assertSame(real, chosen);
      } else {
        assertSame(sim, chosen);
      }
    }
  }
}

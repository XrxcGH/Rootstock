package org.rootstock.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Celsius;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.KilogramSquareMeters;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.control.ControlLocation;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.control.NeutralMode;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.RotaryAxis;

/**
 * Every {@code with*()} returns a copy; nothing ever mutates its receiver.
 *
 * <p><strong>Why this matters more than the usual "records are immutable" shrug.</strong> The
 * per-robot overlay pattern of {@code design/06} §7.3 — the answer to two sibling robots that share
 * a codebase and differ in three CAN ids — is built entirely out of {@code with*()} calls applied to
 * a shared base config declared as a {@code public static final} field. If a single {@code with*()}
 * mutated in place, robot A's overlay would silently edit robot B's config, and the symptom would
 * be a mechanism that behaved correctly on the bench and wrongly on one of the two robots.
 *
 * <p>Records give field immutability for free. What they do not give for free, and what is checked
 * here, is that (a) the copy methods actually copy rather than returning {@code this}, (b) the
 * collections handed out are unmodifiable, and (c) the derived {@code errors()} list is
 * <em>recomputed</em> on the copy — a copy that carried the base's stale errors would report the
 * old config's problems against the new config's numbers.
 */
final class ConfigImmutabilityTest {

  @BeforeEach
  @AfterEach
  void clearGlobalLookupMisses() {
    Validation.resetForTest();
  }

  private static PositionConfig elevator() {
    return PositionConfig.linear("Elevator")
        .motors(
            MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
        .reduction(Reduction.ofStages(3.0, 4.0))
        .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
        .feedback(new FeedbackSpec.RotorOnly())
        .softLimits(Inches.of(0.0), Inches.of(55.0))
        .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
        .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33))
        .constraints(MotionConstraints.of(1.6, 6.0))
        .tolerance(Inches.of(0.5), 0.05, 0.06)
        .homing(HomingStrategy.currentSpike().seedTo(Inches.of(0.0)))
        .setpoint("L4", Inches.of(52.5))
        .sim(Pounds.of(24.0), Inches.of(0.0))
        .build();
  }

  private static VelocityConfig shooter() {
    return VelocityConfig.of("Shooter")
        .motor(MotorSpec.talonFX(30, "rio"))
        .reduction(Reduction.of(1.5))
        .axis(RotaryAxis.roller())
        .gains(Gains.pid(0.1, 0.0, 0.0).withKs(0.1).withKv(0.12))
        .constraints(MotionConstraints.of(6000.0, 12000.0))
        .sim(KilogramSquareMeters.of(0.004))
        .build();
  }

  private static SimpleConfig intake() {
    return SimpleConfig.of("Intake")
        .motor(MotorSpec.talonFX(31, "rio"))
        .reduction(Reduction.of(3.0))
        .currentLimits(CurrentLimits.statorOnly(Amps.of(40)))
        .sim(KilogramSquareMeters.of(0.002))
        .build();
  }

  /**
   * Calls every no-argument-free {@code with*} method reachable with the sample arguments given,
   * and asserts the receiver is byte-for-byte what it was before the call.
   */
  private static void assertReceiverUnchanged(Object receiver, Runnable mutationAttempt) {
    String before = receiver.toString();
    int hashBefore = receiver.hashCode();
    mutationAttempt.run();
    assertEquals(before, receiver.toString(), "toString() changed — the receiver was mutated");
    assertEquals(hashBefore, receiver.hashCode(), "hashCode() changed — the receiver was mutated");
  }

  // ===============================================================================================
  // PositionConfig
  // ===============================================================================================

  @Nested
  @DisplayName("PositionConfig")
  final class Position {

    @Test
    @DisplayName("every with*() returns a different instance and leaves the receiver alone")
    void everyWithCopies() {
      PositionConfig base = elevator();

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withMotors(MotorGroup.leader(MotorSpec.talonFX(40, "rio")));
            assertNotSame(base, copy);
            assertEquals(20, base.motors().leader().deviceId());
            assertEquals(40, copy.motors().leader().deviceId());
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withReduction(Reduction.ofStages(3.0, 5.0));
            assertEquals(12.0, base.reduction().rotorPerOutput(), 1e-12);
            assertEquals(15.0, copy.reduction().rotorPerOutput(), 1e-12);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withAxis(LinearAxis.drum(Inches.of(0.75), 1));
            assertEquals(0.279400, base.units().userPerOutputRotation(), 1e-12);
            assertEquals(0.11969468, copy.units().userPerOutputRotation(), 1e-8);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withFeedback(new FeedbackSpec.SparkAbsolute(Degrees.of(0), false));
            assertFalse(base.feedback().isAbsolute());
            assertTrue(copy.feedback().isAbsolute());
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy =
                base.withLimits(
                    PositionLimits.of(
                        Inches.of(0), Inches.of(30), CurrentLimits.of(Amps.of(50), Amps.of(30))));
            assertEquals(1.397, base.travel().max(), 1e-12);
            assertEquals(0.762, copy.travel().max(), 1e-12);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withGains(Gains.pid(1.0, 0.0, 0.0));
            assertEquals(80.0, base.control().gains().kP(), 0.0);
            assertEquals(1.0, copy.control().gains().kP(), 0.0);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withConstraints(MotionConstraints.of(0.5, 2.0));
            assertEquals(1.6, base.control().constraints().maxVelocity(), 0.0);
            assertEquals(0.5, copy.control().constraints().maxVelocity(), 0.0);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withHoming(HomingStrategy.absoluteSeed());
            assertTrue(base.homing() instanceof HomingStrategy.CurrentSpike);
            assertTrue(copy.homing() instanceof HomingStrategy.AbsoluteSeed);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withSim(SimConfig.linear(Pounds.of(50.0), Inches.of(0)));
            assertEquals(24.0 * 0.453592, base.sim().massKg(), 1e-9);
            assertEquals(50.0 * 0.453592, copy.sim().massKg(), 1e-9);
          });

      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy =
                base.withControl(base.control().withNeutralMode(NeutralMode.COAST));
            assertEquals(NeutralMode.BRAKE, base.control().neutralMode());
            assertEquals(NeutralMode.COAST, copy.control().neutralMode());
          });
    }

    @Test
    @DisplayName("withSetpoint() adds without touching the receiver's list")
    void withSetpointAdds() {
      PositionConfig base = elevator();
      assertReceiverUnchanged(
          base,
          () -> {
            PositionConfig copy = base.withSetpoint("L3", Inches.of(37.5));
            assertEquals(1, base.setpoints().size());
            assertEquals(2, copy.setpoints().size());
            assertTrue(base.findSetpoint("L3").isEmpty());
            assertTrue(copy.findSetpoint("L3").isPresent());
          });
    }

    @Test
    @DisplayName("withSetpoint() on an existing name REPLACES in place in the copy, keeping order")
    void withSetpointReplaces() {
      PositionConfig base = elevator().withSetpoint("L2", Inches.of(20.5));
      PositionConfig copy = base.withSetpoint("L4", Inches.of(53.0));
      assertEquals(2, copy.setpoints().size(), "replacement, not append");
      assertEquals(1.3335, base.setpoint("L4").valueUser(), 1e-9);
      assertEquals(53.0 * 0.0254, copy.setpoint("L4").valueUser(), 1e-12);
      // Order is preserved so describe() and the log keys do not shuffle between deploys.
      assertEquals(List.of("L4", "L2"), copy.setpoints().stream().map(Setpoint::name).toList());
    }

    @Test
    @DisplayName("the errors list is RECOMPUTED on the copy, never carried over")
    void errorsAreRecomputedNotCopied() {
      PositionConfig healthy = elevator();
      assertFalse(healthy.hasFatalError(), healthy.errors().toString());

      // Break it with a copy: cosine gravity on a linear axis is FATAL.
      PositionConfig broken = healthy.withControl(healthy.control().withGravity(GravityMode.COSINE));
      assertTrue(broken.hasFatalError(), "the copy's errors were stale");
      assertTrue(
          broken.errors().stream().anyMatch(e -> e.field().equals("control.gravity")),
          broken.errors().toString());

      // ... and repairing it by another copy clears the error again.
      PositionConfig repaired = broken.withControl(broken.control().withGravity(GravityMode.CONSTANT));
      assertFalse(repaired.hasFatalError(), repaired.errors().toString());
      assertEquals(healthy.errors(), repaired.errors());

      // The original is untouched throughout.
      assertFalse(healthy.hasFatalError());
    }

    @Test
    @DisplayName("the setpoint and error lists handed out are unmodifiable")
    void collectionsAreUnmodifiable() {
      PositionConfig base = elevator();
      assertThrows(UnsupportedOperationException.class, () -> base.setpoints().clear());
      assertThrows(UnsupportedOperationException.class, () -> base.errors().clear());
      assertThrows(UnsupportedOperationException.class, () -> base.canDevices().clear());
      assertThrows(UnsupportedOperationException.class, () -> base.motors().allSpecs().clear());
      assertThrows(UnsupportedOperationException.class, () -> base.motors().followers().clear());
    }

    @Test
    @DisplayName("a copy chain leaves every intermediate config intact — the overlay pattern")
    void anOverlayChainDoesNotDisturbItsBase() {
      // design/06 §7.3: one base config, two robots, three CAN ids different.
      PositionConfig shared = elevator();
      PositionConfig robotA = shared.withMotors(MotorGroup.leader(MotorSpec.talonFX(20, "rio")));
      PositionConfig robotB =
          shared
              .withMotors(MotorGroup.leader(MotorSpec.talonFX(40, "canivore")))
              .withReduction(Reduction.ofStages(3.0, 5.0));

      assertEquals(20, robotA.motors().leader().deviceId());
      assertEquals(40, robotB.motors().leader().deviceId());
      assertEquals("rio", robotA.motors().leader().canBus());
      assertEquals("canivore", robotB.motors().leader().canBus());
      assertEquals(12.0, robotA.reduction().rotorPerOutput(), 1e-12);
      assertEquals(15.0, robotB.reduction().rotorPerOutput(), 1e-12);

      // The shared base is exactly what it was.
      assertEquals(elevator().toString(), shared.toString());
      assertEquals(20, shared.motors().leader().deviceId());
      assertEquals(2, shared.motors().count());
      assertEquals(12.0, shared.reduction().rotorPerOutput(), 1e-12);
      // ... and nothing was registered globally by any of the copies.
      assertEquals(List.of(), Validation.lookupMisses());
    }
  }

  // ===============================================================================================
  // VelocityConfig and SimpleConfig
  // ===============================================================================================

  @Nested
  @DisplayName("VelocityConfig")
  final class Velocity {

    @Test
    @DisplayName("every with*() copies")
    void everyWithCopies() {
      VelocityConfig base = shooter();
      assertReceiverUnchanged(
          base,
          () -> {
            VelocityConfig moved =
                base.withMotors(MotorGroup.leader(MotorSpec.talonFX(41, "rio")));
            assertNotSame(base, moved);
            assertEquals(41, moved.motors().leader().deviceId());
            assertEquals(30, base.motors().leader().deviceId());
            assertNotSame(base, base.withReduction(Reduction.of(2.0)));
            assertNotSame(base, base.withAxis(RotaryAxis.turret(true)));
            assertNotSame(base, base.withFeedback(new FeedbackSpec.RotorOnly()));
            assertNotSame(base, base.withCurrentLimits(CurrentLimits.statorOnly(Amps.of(20))));
            assertNotSame(base, base.withGains(Gains.pid(9.0, 0.0, 0.0)));
            assertNotSame(base, base.withConstraints(MotionConstraints.of(10.0, 20.0)));
            assertNotSame(base, base.withSim(SimConfig.flywheel(KilogramSquareMeters.of(0.01))));
            assertNotSame(base, base.withControl(ControlConfig.defaults()));
          });
      assertEquals(1.5, base.reduction().rotorPerOutput(), 1e-12);
      assertEquals(0.1, base.control().gains().kP(), 0.0);
    }

    @Test
    @DisplayName("errors are recomputed on the copy")
    void errorsRecompute() {
      VelocityConfig healthy = shooter();
      assertEquals(List.of(), healthy.errors(), healthy.errors().toString());
      VelocityConfig broken =
          healthy.withControl(healthy.control().withGravity(GravityMode.CONSTANT));
      assertTrue(broken.hasFatalError());
      assertEquals(List.of(), healthy.errors(), "the base was mutated");
    }
  }

  @Nested
  @DisplayName("SimpleConfig")
  final class Simple {

    @Test
    @DisplayName("every with*() copies")
    void everyWithCopies() {
      SimpleConfig base = intake();
      assertReceiverUnchanged(
          base,
          () -> {
            assertNotSame(base, base.withMotors(MotorGroup.leader(MotorSpec.talonFX(42, "rio"))));
            assertNotSame(base, base.withReduction(Reduction.of(9.0)));
            assertNotSame(base, base.withCurrentLimits(CurrentLimits.statorOnly(Amps.of(20))));
            assertNotSame(base, base.withHeldSensor(SensorSpec.dio(0, false)));
            assertNotSame(base, base.withStallSensor(SensorSpec.statorCurrent(Amps.of(30), Seconds.of(0.2))));
            assertNotSame(base, base.withNeutralMode(NeutralMode.COAST));
            assertNotSame(base, base.withSim(SimConfig.flywheel(KilogramSquareMeters.of(0.01))));
          });
      assertFalse(base.hasHeldSensor(), "the base gained a sensor it was never given");
      assertEquals(NeutralMode.BRAKE, base.neutralMode());
      assertEquals(3.0, base.reduction().rotorPerOutput(), 1e-12);
    }
  }

  // ===============================================================================================
  // The leaf records
  // ===============================================================================================

  @Nested
  @DisplayName("the leaf records copy too")
  final class Leaves {

    @Test
    @DisplayName("ControlConfig")
    void controlConfig() {
      ControlConfig base = ControlConfig.defaults();
      assertReceiverUnchanged(
          base,
          () -> {
            assertNotSame(base, base.withLocation(ControlLocation.ON_MOTOR_PROFILED));
            assertNotSame(base, base.withGains(Gains.pid(1, 2, 3)));
            assertNotSame(base, base.withConstraints(MotionConstraints.of(1, 2)));
            assertNotSame(base, base.withUseExpo(true));
            assertNotSame(base, base.withGravity(GravityMode.COSINE));
            assertNotSame(base, base.withTolerance(Inches.of(1), 1, 1));
            assertNotSame(base, base.withNeutralMode(NeutralMode.COAST));
            assertNotSame(base, base.withManualControl(0.2, 0.4));
            assertNotSame(base, base.withDefaultedLocation(ControlLocation.RIO_PROFILE_MOTOR_LOOP));
          });
      assertEquals(ControlLocation.RIO_FULL, base.location());
      assertEquals(GravityMode.NONE, base.gravity());
      assertTrue(base.gains().isUntuned());
    }

    @Test
    @DisplayName("MotorGroup — follower() appends to a copy")
    void motorGroup() {
      MotorGroup base = MotorGroup.leader(MotorSpec.talonFX(20, "rio"));
      assertReceiverUnchanged(
          base,
          () -> {
            MotorGroup two = base.follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED);
            assertEquals(1, base.count());
            assertEquals(2, two.count());
            assertNotSame(base, base.withLeader(MotorSpec.talonFX(40, "rio")));
          });
      assertEquals(20, base.leader().deviceId());
      assertTrue(base.followers().isEmpty());
    }

    @Test
    @DisplayName("MotorSpec — each builder-style setter copies")
    void motorSpec() {
      MotorSpec.TalonFXSpec base = MotorSpec.talonFX(20, "rio");
      assertReceiverUnchanged(
          base,
          () -> {
            assertNotSame(base, base.inverted(true));
            assertNotSame(base, base.foc(true));
            assertNotSame(base, base.outputMode(OutputMode.TORQUE_CURRENT));
            assertNotSame(base, base.signalRateHz(200.0));
            assertNotSame(base, base.model(MotorModel.FALCON_500));
            assertNotSame(base, base.canBus("canivore"));
          });
      assertFalse(base.inverted());
      assertFalse(base.foc());
      assertEquals(OutputMode.VOLTAGE, base.outputMode());
      assertEquals(MotorModel.KRAKEN_X60, base.model());
      assertEquals("rio", base.canBus());
      assertFalse(base.hasSignalRateOverride());
    }

    @Test
    @DisplayName("PositionLimits, CurrentLimits, MotionConstraints, SimConfig, Gains")
    void theRest() {
      PositionLimits limits =
          PositionLimits.of(Inches.of(0), Inches.of(55), CurrentLimits.of(Amps.of(70), Amps.of(40)));
      assertReceiverUnchanged(
          limits,
          () -> {
            assertNotSame(
                limits, limits.withHardStop(HardStop.REVERSE, SensorSpec.dio(0, false)));
            assertNotSame(limits, limits.withCurrent(CurrentLimits.statorOnly(Amps.of(10))));
            assertNotSame(limits, limits.withOverTemperature(Celsius.of(70)));
            assertNotSame(limits, limits.withFollowerTolerance(0.25));
          });
      assertTrue(limits.hardStop(HardStop.REVERSE).isEmpty());
      assertEquals(PositionLimits.kDefaultOverTempCelsius, limits.overTempCelsius(), 0.0);

      CurrentLimits current = CurrentLimits.of(Amps.of(70), Amps.of(40));
      assertReceiverUnchanged(
          current,
          () -> {
            assertNotSame(current, current.withStator(Amps.of(10)));
            assertNotSame(current, current.withSupply(Amps.of(10)));
            assertNotSame(current, current.withSupplyLowerTier(Amps.of(20), Seconds.of(1)));
          });
      assertEquals(70.0, current.statorAmps(), 0.0);
      assertFalse(current.hasSupplyLowerTier());

      MotionConstraints constraints = MotionConstraints.of(1.6, 6.0);
      assertReceiverUnchanged(
          constraints,
          () -> {
            assertNotSame(constraints, constraints.withMaxVelocity(9.0));
            assertNotSame(constraints, constraints.withMaxAcceleration(9.0));
            assertNotSame(constraints, constraints.withJerk(9.0));
            assertNotSame(constraints, constraints.scaled(0.5));
          });
      assertEquals(1.6, constraints.maxVelocity(), 0.0);
      assertFalse(constraints.hasJerk());

      SimConfig sim = SimConfig.linear(Pounds.of(24), Inches.of(0));
      assertReceiverUnchanged(
          sim,
          () -> {
            assertNotSame(sim, sim.withStartingPosition(Meters.of(1.0)));
            assertNotSame(sim, sim.withGravity(false));
          });
      assertEquals(0.0, sim.startingPositionSi(), 0.0);
      assertTrue(sim.simulateGravity());

      Gains gains = Gains.pid(1, 2, 3);
      assertReceiverUnchanged(gains, () -> assertNotSame(gains, gains.withKp(9.0)));
      assertEquals(1.0, gains.kP(), 0.0);
    }
  }

  // ===============================================================================================
  // A reflective sweep, so a NEW with*() cannot be added without being covered
  // ===============================================================================================

  @Nested
  @DisplayName("reflective sweep over every public with* method")
  final class Sweep {

    private static final List<Class<?>> kConfigTypes =
        List.of(
            PositionConfig.class,
            VelocityConfig.class,
            SimpleConfig.class,
            ControlConfig.class,
            PositionLimits.class,
            CurrentLimits.class,
            MotionConstraints.class,
            SimConfig.class,
            MotorGroup.class);

    @Test
    @DisplayName("no with*() declares void, and every one returns its own type or a relative")
    void everyWithMethodIsACopyFactoryBySignature() {
      List<String> offenders = new ArrayList<>();
      int checked = 0;
      for (Class<?> type : kConfigTypes) {
        for (Method method : type.getDeclaredMethods()) {
          if (!method.getName().startsWith("with") || !Modifier.isPublic(method.getModifiers())) {
            continue;
          }
          checked++;
          // A void with*() cannot be anything BUT a mutator.
          if (method.getReturnType() == void.class) {
            offenders.add(type.getSimpleName() + "." + method.getName() + " returns void");
          }
          // A static with*() would be a factory, not a copy method — also a smell here.
          if (Modifier.isStatic(method.getModifiers())) {
            offenders.add(type.getSimpleName() + "." + method.getName() + " is static");
          }
        }
      }
      assertTrue(checked >= 30, "the sweep found only " + checked + " with* methods; did they move?");
      assertEquals(List.of(), offenders, "these look like mutators, not copy factories");
    }

    @Test
    @DisplayName("the config records are final, so no subclass can add mutable state")
    void configTypesAreFinal() {
      for (Class<?> type : kConfigTypes) {
        assertTrue(
            Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " is not final");
      }
    }

    @Test
    @DisplayName("no config type exposes a public non-final field")
    void noPublicMutableFields() {
      for (Class<?> type : kConfigTypes) {
        for (var field : type.getDeclaredFields()) {
          if (Modifier.isPublic(field.getModifiers())) {
            assertTrue(
                Modifier.isFinal(field.getModifiers()),
                type.getSimpleName() + "." + field.getName() + " is public and not final");
          }
        }
      }
    }

    @Test
    @DisplayName("two configs built from identical inputs are equal and hash alike")
    void valueSemantics() {
      assertEquals(elevator(), elevator());
      assertEquals(elevator().hashCode(), elevator().hashCode());
      assertEquals(shooter(), shooter());
      assertEquals(intake(), intake());
      assertFalse(elevator().equals(elevator().withReduction(Reduction.of(9.0))));
    }

    @Test
    @DisplayName("an UNSET sim field does not destroy value equality")
    void unsetSimFieldsCompareEqual() {
      // SimConfig marks inapplicable fields with kUnset == NaN, and NaN is not numerically equal
      // to itself. The record's generated equals therefore reported two identical linear sims as
      // different, and that inequality propagated into PositionConfig / VelocityConfig /
      // SimpleConfig, every one of which carries a SimConfig. A Map keyed on a config, a
      // List.contains, or an overlay diff was silently wrong on EVERY mechanism, because no axis
      // uses more than two of the four sim fields.
      assertTrue(Double.isNaN(SimConfig.kUnset));
      SimConfig a = SimConfig.linear(Pounds.of(24.0), Inches.of(0.0));
      SimConfig b = SimConfig.linear(Pounds.of(24.0), Inches.of(0.0));
      assertTrue(Double.isNaN(a.moiKgM2()), "the moment of inertia is the unset one here");
      assertTrue(Double.isNaN(a.armLengthMeters()));
      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());

      // Still discriminating where it should be.
      assertFalse(a.equals(SimConfig.linear(Pounds.of(25.0), Inches.of(0.0))));
      assertFalse(a.equals(SimConfig.linear(Pounds.of(24.0), Inches.of(1.0))));
      assertFalse(a.equals(a.withGravity(false)));
      assertFalse(a.equals(SimConfig.flywheel(KilogramSquareMeters.of(0.004))));

      // A rotary sim, whose unset field is the mass instead.
      SimConfig arm = SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0));
      assertTrue(Double.isNaN(arm.massKg()));
      assertEquals(arm, SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0)));

      // And the whole point: a config containing one is usable as a value.
      assertTrue(List.of(elevator()).contains(elevator()));
      assertEquals(1, new java.util.HashSet<>(List.of(elevator(), elevator())).size());
      assertEquals("value", new java.util.HashMap<>(java.util.Map.of(elevator(), "value"))
          .get(elevator()));
    }
  }
}

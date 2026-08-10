package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.KilogramSquareMeters;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Rotations;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.RotaryAxis;

/**
 * Validation: the contract that a wrong number is a <em>value</em>, never an exception.
 *
 * <p><strong>The failure this prevents.</strong> Revision 1 of the design threw from the record's
 * compact constructor while every example declared its configs as {@code public static final}
 * fields. On a real robot that is an {@code ExceptionInInitializerError} out of
 * {@code frc.robot.RobotConfig.<clinit>}: robot code never starts, the driver station shows red
 * "Robot Code", and the carefully written message is buried as a <em>cause</em> under JVM
 * class-initialisation frames. A student editing a soft limit between matches gets a dead robot and
 * no diagnosis. So:
 *
 * <ul>
 *   <li>validation NEVER throws — it returns {@code List<ConfigError>};
 *   <li>errors are COLLECTED, so one deploy shows every problem rather than the first one;
 *   <li>each error names the <em>mechanism</em>, the <em>field</em>, the <em>value</em>, the
 *       <em>expected</em> range and the <em>fix</em>.
 * </ul>
 *
 * <p><strong>These tests assert on message text, not on counts.</strong> The count only proves
 * something was noticed; the text is the product. A student reads it at 11pm on a phone in a pit,
 * and if it does not say what to type next it has failed even though the check "passed".
 */
final class ValidationTest {

  @BeforeEach
  @AfterEach
  void clearGlobalLookupMisses() {
    Validation.resetForTest();
  }

  // -----------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------

  /** A linear config that is correct in every respect, as the baseline to break one field of. */
  private static PositionConfig.Builder healthyElevator() {
    return PositionConfig.linear("Elevator")
        .motors(
            MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
        .reduction(Reduction.ofStages(3.0, 4.0))
        .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
        .feedback(new FeedbackSpec.RotorOnly())
        .softLimits(Inches.of(0.0), Inches.of(55.0))
        .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
        .gains(
            org.pumpkinlib.control.Gains.pid(80.0, 0.0, 2.0)
                .withKs(0.22)
                .withKv(5.00)
                .withKa(0.06)
                .withKg(0.33))
        .constraints(MotionConstraints.of(1.6, 6.0))
        .tolerance(Inches.of(0.5), 0.05, 0.06)
        .homing(
            HomingStrategy.currentSpike()
                .direction(HomingStrategy.Direction.REVERSE)
                .seedTo(Inches.of(0.0)))
        .sim(Pounds.of(24.0), Inches.of(0.0));
  }

  /**
   * The same elevator with no {@code .homing(...)} call at all.
   *
   * <p>Not the same thing as {@code .homing(HomingStrategy.firstOf())}: the "never declared"
   * sentinel is identified by reference, and an explicitly empty composite is a different mistake
   * with its own message.
   */
  private static PositionConfig.Builder elevatorWithNoHomingCall() {
    return PositionConfig.linear("Elevator")
        .motors(
            MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
        .reduction(Reduction.ofStages(3.0, 4.0))
        .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
        .feedback(new FeedbackSpec.RotorOnly())
        .softLimits(Inches.of(0.0), Inches.of(55.0))
        .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
        .constraints(MotionConstraints.of(1.6, 6.0))
        .tolerance(Inches.of(0.5), 0.05, 0.06)
        .sim(Pounds.of(24.0), Inches.of(0.0));
  }

  /** The arm with no {@code .homing(...)} call, to reach the absolute-sensor WARNING branch. */
  private static PositionConfig.Builder armWithNoHomingCall() {
    return PositionConfig.rotary("Arm")
        .motor(MotorSpec.talonFX(22, "rio").inverted(true))
        .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
        .axis(RotaryAxis.arm(Degrees.of(0.0)))
        .feedback(
            new FeedbackSpec.FusedCancoder(
                23, "rio", Rotations.of(-0.1387), 141288.0 / 2160.0, 1.0))
        .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
        .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
        .gains(
            org.pumpkinlib.control.Gains.pid(5.0, 0.0, 0.18)
                .withKs(0.20)
                .withKv(1.25)
                .withKa(0.010)
                .withKg(0.29))
        .constraints(MotionConstraints.of(180.0, 540.0))
        .tolerance(Degrees.of(1.5), 5.0, 0.06)
        .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0)));
  }

  /** A rotary config that is correct in every respect. */
  private static PositionConfig.Builder healthyArm() {
    return PositionConfig.rotary("Arm")
        .motor(MotorSpec.talonFX(22, "rio").inverted(true))
        .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
        .axis(RotaryAxis.arm(Degrees.of(0.0)))
        .feedback(
            new FeedbackSpec.FusedCancoder(
                23, "rio", Rotations.of(-0.1387), 141288.0 / 2160.0, 1.0))
        .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
        .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
        .gains(
            org.pumpkinlib.control.Gains.pid(5.0, 0.0, 0.18)
                .withKs(0.20)
                .withKv(1.25)
                .withKa(0.010)
                .withKg(0.29))
        .constraints(MotionConstraints.of(180.0, 540.0))
        .tolerance(Degrees.of(1.5), 5.0, 0.06)
        .homing(HomingStrategy.absoluteSeed())
        .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0)));
  }

  private static ConfigError only(List<ConfigError> errors, String field) {
    List<ConfigError> matches = errors.stream().filter(e -> e.field().equals(field)).toList();
    assertEquals(
        1,
        matches.size(),
        "expected exactly one error on field \""
            + field
            + "\", got "
            + matches.size()
            + " of "
            + errors.stream().map(ConfigError::field).toList());
    return matches.get(0);
  }

  private static boolean anyExplanationContains(List<ConfigError> errors, String needle) {
    return errors.stream().anyMatch(e -> e.explanation().contains(needle));
  }

  // ===============================================================================================
  // The contract itself
  // ===============================================================================================

  @Nested
  @DisplayName("validation never throws")
  final class NeverThrows {

    @Test
    @DisplayName("a config built entirely of nulls returns errors instead of an exception")
    void everyNullIsSurvivable() {
      List<ConfigError> errors =
          assertDoesNotThrow(
              () ->
                  Validation.localChecks(
                      null, null, null, null, null, null, null, null, null, null));
      assertFalse(errors.isEmpty(), "silence on an all-null config would be worse than a throw");
      assertTrue(errors.stream().anyMatch(ConfigError::isFatal));
      // Even the owner name is filled in rather than being a null in the middle of a sentence.
      assertTrue(errors.stream().noneMatch(e -> e.owner() == null || e.owner().isBlank()));
      assertTrue(errors.stream().noneMatch(e -> e.explanation().contains("null,")));
    }

    @Test
    @DisplayName("a record whose every component is broken still constructs")
    void aTotallyBrokenConfigStillConstructs() {
      PositionConfig broken =
          assertDoesNotThrow(
              () ->
                  new PositionConfig(
                      "   ", null, null, null, null, null, null, null, null, null, null));
      assertEquals("(unnamed mechanism)", broken.name());
      assertTrue(broken.hasFatalError());
      // ... and every accessor keeps working, because describe() is what a student runs next.
      assertDoesNotThrow(broken::describe);
      assertDoesNotThrow(broken::toString);
      assertDoesNotThrow(broken::snapshot);
      assertDoesNotThrow(broken::canDevices);
      assertDoesNotThrow(broken::units);
    }

    @Test
    @DisplayName("NaN and infinity in the numeric fields are reported, not propagated")
    void nonFiniteNumbersAreReported() {
      PositionConfig config =
          assertDoesNotThrow(
              () ->
                  healthyElevator()
                      .constraints(new MotionConstraints(Double.NaN, Double.NaN, Double.NaN))
                      .manualControl(Double.NaN, Double.NaN)
                      .build());
      List<ConfigError> errors = config.errors();
      assertTrue(anyExplanationContains(errors, "maxVelocity = NaN"), errors.toString());
      assertTrue(anyExplanationContains(errors, "maxAcceleration = NaN"), errors.toString());
      assertTrue(anyExplanationContains(errors, "manualDeadband = NaN"), errors.toString());
      assertTrue(anyExplanationContains(errors, "manualScale = NaN"), errors.toString());
    }

    @Test
    @DisplayName("the returned list is immutable, so a caller cannot suppress a fault")
    void theErrorListIsImmutable() {
      List<ConfigError> errors = healthyElevator().softLimits(Inches.of(9), Inches.of(1)).build()
          .errors();
      assertFalse(errors.isEmpty());
      assertThrows(UnsupportedOperationException.class, errors::clear);
      assertThrows(
          UnsupportedOperationException.class,
          () -> errors.add(ConfigError.fatal("x", "y", "z", "w", "v")));
    }

    @Test
    @DisplayName("crossChecks and validateAll tolerate nulls and unknown objects")
    void globalChecksAreTotalToo() {
      assertDoesNotThrow(() -> Validation.crossChecks((Object[]) null));
      assertDoesNotThrow(() -> Validation.validateAll((Object[]) null));
      assertEquals(List.of(), Validation.crossChecks("not a config", 42, null));
      assertEquals(List.of(), Validation.errorsOf("not a config"));
      assertEquals(List.of(), Validation.devicesOf(null));
      assertDoesNotThrow(() -> Validation.describe(null));
      assertDoesNotThrow(() -> Validation.checklist(null));
      assertDoesNotThrow(() -> Validation.toStrings(null));
    }
  }

  // ===============================================================================================
  // Errors are collected, not raised one at a time
  // ===============================================================================================

  @Nested
  @DisplayName("errors are collected and reported together")
  final class Collected {

    @Test
    @DisplayName("six independent defects produce six errors in one pass")
    void everyDefectIsFoundInASinglePass() {
      PositionConfig sixWaysWrong =
          PositionConfig.linear("Wrist")
              // 1. CAN id above the addressable range
              .motor(MotorSpec.talonFX(64, "rio"))
              .reduction(Reduction.ofStages(3.0, 4.0))
              .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
              // 2. min and max swapped
              .softLimits(Inches.of(55.0), Inches.of(0.0))
              // 3. cosine gravity on a linear axis
              .gravity(GravityMode.COSINE)
              // 4. an angular tolerance on a linear mechanism
              .tolerance(Degrees.of(1.0), 0.05, 0.06)
              // 5. a cruise velocity far above the free speed
              .constraints(MotionConstraints.of(50.0, 6.0))
              // 6. no homing strategy on a rotor-only mechanism
              .sim(Pounds.of(24.0), Inches.of(0.0))
              .build();

      List<ConfigError> errors = sixWaysWrong.errors();
      // A validator that stopped at the first fault would show one of these and hide five.
      assertTrue(
          anyExplanationContains(errors, "CAN device id 64 is outside the addressable range 0..62"),
          errors.toString());
      assertTrue(
          errors.stream().anyMatch(e -> e.field().equals("limits.min / limits.max")),
          errors.toString());
      assertTrue(
          errors.stream().anyMatch(e -> e.field().equals("control.gravity")), errors.toString());
      assertTrue(
          errors.stream().anyMatch(e -> e.field().equals("control.tolerance")), errors.toString());
      assertTrue(
          errors.stream()
              .anyMatch(e -> e.field().equals("control.constraints.maxVelocity")),
          errors.toString());
      assertTrue(errors.stream().anyMatch(e -> e.field().equals("homing")), errors.toString());
      assertTrue(sixWaysWrong.hasFatalError());
    }

    @Test
    @DisplayName("the three severities coexist in one list and are ordered by the printer")
    void severitiesCoexist() {
      List<ConfigError> errors =
          PositionConfig.linear("Wrist")
              .motor(MotorSpec.talonFX(64, "rio")) // FATAL
              .reduction(Reduction.ofStages(3.0, 4.0))
              .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
              .softLimits(Inches.of(0.0), Inches.of(55.0))
              .constraints(MotionConstraints.of(50.0, 6.0)) // WARNING
              .tolerance(Inches.of(0.5), 0.05, 0.06)
              .homing(HomingStrategy.currentSpike().seedTo(Inches.of(0.0)))
              .build() // Gains.UNTUNED and no sim => PLACEHOLDER
              .errors();
      assertTrue(errors.stream().anyMatch(e -> e.severity() == ConfigError.Severity.FATAL));
      assertTrue(errors.stream().anyMatch(e -> e.severity() == ConfigError.Severity.WARNING));
      assertTrue(errors.stream().anyMatch(e -> e.severity() == ConfigError.Severity.PLACEHOLDER));

      // The first-setup checklist shows only the placeholders, with the VERIFY verb.
      String checklist = Validation.checklist(errors);
      assertTrue(checklist.contains("First-setup checklist"), checklist);
      assertTrue(checklist.contains("VERIFY"), checklist);
      assertFalse(checklist.contains("[FATAL]"), checklist);
      assertEquals("FIX", ConfigError.Severity.FATAL.verb());
      assertEquals("CHECK", ConfigError.Severity.WARNING.verb());
      assertEquals("VERIFY", ConfigError.Severity.PLACEHOLDER.verb());
    }

    @Test
    @DisplayName("only FATAL blocks the robot; a warning-only config is not fatal")
    void onlyFatalBlocks() {
      PositionConfig warningsOnly = healthyElevator().build();
      assertFalse(warningsOnly.errors().isEmpty(), "the tolerance/loop-step note should be here");
      assertFalse(warningsOnly.hasFatalError());
      assertTrue(
          warningsOnly.errors().stream().noneMatch(ConfigError::isFatal),
          warningsOnly.errors().toString());
    }

    @Test
    @DisplayName("validateAll gathers per-config errors and the global scan in one list")
    void validateAllGathersEverything() {
      PositionConfig a = healthyElevator().build();
      PositionConfig b = healthyArm().build();
      List<ConfigError> all = Validation.validateAll(a, b);
      assertTrue(all.containsAll(a.errors()));
      assertTrue(all.containsAll(b.errors()));
      assertTrue(all.size() >= a.errors().size() + b.errors().size());
    }
  }

  // ===============================================================================================
  // Message quality, one defect class at a time
  // ===============================================================================================

  @Nested
  @DisplayName("each message names the mechanism, the field, the value, the range and the fix")
  final class MessageQuality {

    @Test
    @DisplayName("a non-positive reduction explains the 12:1 direction convention")
    void reductionMessage() {
      // Reduction itself refuses a non-positive magnitude, so the only route to this branch is a
      // null — which is exactly what an unfilled config field looks like.
      List<ConfigError> errors =
          Validation.localChecks(
              "Elevator",
              MotorGroup.leader(MotorSpec.talonFX(20, "rio")),
              null,
              LinearAxis.sprocket(Inches.of(0.25), 22, 2),
              new FeedbackSpec.RotorOnly(),
              PositionLimits.of(
                  Inches.of(0), Inches.of(55), CurrentLimits.of(Amps.of(70), Amps.of(40))),
              ControlConfig.defaults()
                  .withConstraints(MotionConstraints.of(1.6, 6.0))
                  .withTolerance(Inches.of(0.5), 0.05, 0.06),
              HomingStrategy.assumeAtBoot(Inches.of(0)),
              List.of(),
              SimConfig.linear(Pounds.of(24), Inches.of(0)));

      ConfigError error = only(errors, "reduction");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      assertEquals("null", error.value());
      assertEquals("> 0 (rotor rotations per output rotation)", error.expected());
      assertTrue(error.explanation().contains("how many times the MOTOR turns"), error.explanation());
      // The inversion a student actually makes, spelled out both ways round:
      assertTrue(
          error.explanation().contains("Reduction.of(12.0), not Reduction.of(1.0/12.0)"),
          error.explanation());
      assertTrue(error.explanation().contains("Reduction.ofTeeth(58, 10)"), error.explanation());
    }

    @Test
    @DisplayName("swapped soft limits print both values and refuse to reorder them")
    void swappedLimitsMessage() {
      ConfigError error =
          only(
              healthyElevator().softLimits(Inches.of(55.0), Inches.of(0.0)).build().errors(),
              "limits.min / limits.max");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      // 55 in = 1.3970 m, 0 in = 0.0000 m — both rendered, so a reader can see which is which.
      assertEquals("min = 1.3970 m, max = 0.0000 m", error.value());
      assertEquals("limits.min < limits.max", error.expected());
      assertTrue(error.explanation().contains("will not order them for you"), error.explanation());
      assertTrue(error.explanation().contains("inverted motor"), error.explanation());
      assertTrue(error.explanation().contains("Fix: check describe()"), error.explanation());
      assertTrue(error.explanation().contains(".softLimits(...)"), error.explanation());
    }

    @Test
    @DisplayName("cosine gravity on a linear axis explains the physical consequence")
    void cosineOnLinearMessage() {
      ConfigError error =
          only(healthyElevator().gravity(GravityMode.COSINE).build().errors(), "control.gravity");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("COSINE", error.value());
      assertEquals("CONSTANT on a linear axis", error.expected());
      // The consequence, not just the rule: the elevator falls at the top of its travel.
      assertTrue(error.explanation().contains("go to zero at the top"), error.explanation());
      assertTrue(error.explanation().contains("the elevator would fall"), error.explanation());
      assertTrue(
          error.explanation().contains("LinearAxis already implies CONSTANT"), error.explanation());
    }

    @Test
    @DisplayName("a horizontal reference beyond +/-90 deg names Phoenix's silent clamp")
    void horizontalOffsetMessage() {
      ConfigError error =
          only(
              healthyArm().axis(RotaryAxis.arm(Degrees.of(95.0))).build().errors(),
              "axis.horizontalAt");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      // 95 deg / 360 = 0.2639 rotations — both the angle a student typed and the number Phoenix
      // actually clamps, so the connection between the two is visible.
      assertEquals("95.0 deg  ->  0.2639 output rotations", error.value());
      assertTrue(error.expected().contains("+/-0.25 rotations"), error.expected());
      assertTrue(error.expected().contains("+/-90 deg"), error.expected());
      assertTrue(
          error.explanation().contains("clamped by the device with NO error"), error.explanation());
      assertTrue(error.explanation().contains("sags on one side"), error.explanation());
      assertTrue(error.explanation().contains("Fix: re-zero the encoder"), error.explanation());
      assertTrue(error.explanation().contains("ControlLocation.RIO_FULL"), error.explanation());
    }

    @Test
    @DisplayName("feedback ratios that contradict the reduction print the scale factor")
    void feedbackRatioMessage() {
      // 34.126 x 1.0 against a declared 65.4111 — the exact revision-3 arm mistake.
      ConfigError error =
          only(
              healthyArm()
                  .feedback(
                      new FeedbackSpec.FusedCancoder(
                          23, "rio", Rotations.of(-0.1387), 34.126, 1.0))
                  .build()
                  .errors(),
              "feedback ratios vs reduction");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("rotorPerSensor 34.1260 x sensorPerOutput 1.0000 = 34.1260", error.value());
      assertEquals("65.4111, the reduction you declared", error.expected());
      // 34.126 / 65.4111 = 0.5217: the factor every position would be off by.
      assertTrue(error.explanation().contains("scaled by 0.5217"), error.explanation());
      assertTrue(error.explanation().contains("Count the teeth once"), error.explanation());
    }

    @Test
    @DisplayName("a setpoint outside the soft limits says why atGoal() would hang forever")
    void setpointOutsideLimitsMessage() {
      ConfigError error =
          only(
              healthyElevator().setpoint("L4", Inches.of(80.0)).build().errors(),
              "setpoint(\"L4\")");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertEquals("Elevator", error.owner());
      // 80 in = 2.0320 m
      assertEquals("Elevator.L4 = 2.0320 m", error.value());
      assertEquals("inside the soft limits [0.000, 1.397] m", error.expected());
      assertTrue(error.explanation().contains("clamped to the nearest soft limit"),
          error.explanation());
      assertTrue(error.explanation().contains("waits forever"), error.explanation());
      assertTrue(error.explanation().contains("Fix: move the goal inside"), error.explanation());
    }

    @Test
    @DisplayName("a duplicate setpoint name explains the declaration-order hazard")
    void duplicateSetpointMessage() {
      ConfigError error =
          only(
              healthyElevator()
                  .setpoint("L4", Inches.of(52.5))
                  .setpoint("L4", Inches.of(53.0))
                  .build()
                  .errors(),
              "setpoint(\"L4\")");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("declared more than once", error.value());
      assertEquals("one goal per name", error.expected());
      assertTrue(error.explanation().contains("declaration order"), error.explanation());
      assertTrue(error.explanation().contains("Fix: delete the duplicate"), error.explanation());
    }

    @Test
    @DisplayName("a cruise velocity above free speed shows the whole derivation and a number to type")
    void cruiseVelocityMessage() {
      ConfigError error =
          only(
              healthyElevator().constraints(MotionConstraints.of(50.0, 6.0)).build().errors(),
              "control.constraints.maxVelocity");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertEquals("50.000 m/s", error.value());
      // free speed 2.2507 m/s x 0.80 = 1.8006 m/s
      assertEquals("<= 1.801 m/s (80% of the free-speed estimate)", error.expected());
      // The derivation, so the free-speed claim can be checked rather than believed:
      assertTrue(error.explanation().contains("free-speed estimate is 2.251 m/s"),
          error.explanation());
      assertTrue(error.explanation().contains("2 x Kraken X60"), error.explanation());
      assertTrue(error.explanation().contains("97 rotor rps"), error.explanation());
      assertTrue(error.explanation().contains("12.000:1"), error.explanation());
      assertTrue(error.explanation().contains("0.279400 m of travel per output rotation"),
          error.explanation());
      // And a literal call to paste, not just "reduce it":
      assertTrue(
          error.explanation().contains("Fix: .constraints(MotionConstraints.of(1.80, 6.00))"),
          error.explanation());
    }

    @Test
    @DisplayName("a missing homing strategy on a rotor-only mechanism offers all three answers")
    void missingHomingMessage() {
      ConfigError error = only(elevatorWithNoHomingCall().build().errors(), "homing");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      assertEquals("not declared", error.value());
      assertEquals("a HomingStrategy", error.expected());
      assertTrue(error.explanation().contains("reads zero at boot"), error.explanation());
      assertTrue(error.explanation().contains("HomingStrategy.currentSpike()"), error.explanation());
      assertTrue(error.explanation().contains("HomingStrategy.limitSwitch("), error.explanation());
      assertTrue(error.explanation().contains("HomingStrategy.assumeAtBoot("), error.explanation());
    }

    @Test
    @DisplayName("an EXPLICITLY empty firstOf() is a different mistake with its own message")
    void explicitlyEmptyCompositeIsItsOwnMessage() {
      // "I never said how this homes" and "I wrote firstOf() and forgot the arguments" are
      // different edits with different fixes, so they must not collapse into one message.
      List<ConfigError> errors =
          healthyElevator().homing(HomingStrategy.firstOf()).build().errors();
      assertTrue(
          anyExplanationContains(errors, "HomingStrategy.firstOf: no strategies were given"),
          errors.toString());
      assertTrue(anyExplanationContains(errors, "isHomed() would stay false forever"),
          errors.toString());
      assertTrue(errors.stream().noneMatch(e -> "not declared".equals(e.value())),
          errors.toString());
    }

    @Test
    @DisplayName("an absolute sensor without a declared strategy is only a WARNING")
    void absoluteSensorWithoutHomingIsAWarning() {
      // The mechanism does know where it is; what is missing is the config SAYING so. Making
      // this fatal would block a correct robot.
      ConfigError error = only(armWithNoHomingCall().build().errors(), "homing");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertEquals("not declared", error.value());
      assertEquals("HomingStrategy.absoluteSeed()", error.expected());
      assertTrue(error.explanation().contains("does know where it is"), error.explanation());
      assertTrue(
          error.explanation().contains("Fix: .homing(HomingStrategy.absoluteSeed())"),
          error.explanation());
    }

    @Test
    @DisplayName("an angular tolerance on a linear axis names the 57x consequence")
    void toleranceDomainMessage() {
      ConfigError error =
          only(
              healthyElevator().tolerance(Degrees.of(1.0), 0.05, 0.06).build().errors(),
              "control.tolerance");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("a Distance, because the axis is a LinearAxis", error.expected());
      assertTrue(error.explanation().contains("57 times"), error.explanation());
      assertTrue(error.explanation().contains("Fix: .tolerance(Inches.of(0.5)"), error.explanation());
    }

    @Test
    @DisplayName("a distance tolerance on a rotary axis is the mirror-image error")
    void toleranceDomainMessageRotary() {
      // Two errors land on control.tolerance here — the FATAL domain mismatch and the WARNING
      // that the band is narrower than one loop step. Only the fatal one is under test.
      ConfigError error =
          healthyArm().tolerance(Inches.of(1.0), 5.0, 0.06).build().errors().stream()
              .filter(e -> e.field().equals("control.tolerance") && e.isFatal())
              .findFirst()
              .orElseThrow();
      assertEquals("an Angle, because the axis is a RotaryAxis", error.expected());
      assertTrue(error.explanation().contains("Fix: .tolerance(Degrees.of(1.5)"), error.explanation());
    }

    @Test
    @DisplayName("a CAN id outside 0..62 names the range and where to read the real one")
    void canIdMessage() {
      List<ConfigError> errors = healthyElevator().motor(MotorSpec.talonFX(64, "rio")).build()
          .errors();
      ConfigError error =
          errors.stream()
              .filter(e -> e.explanation().contains("CAN device id"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no CAN id error in " + errors));
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      assertTrue(error.explanation().contains("TalonFX 64 (rio)"), error.explanation());
      assertTrue(
          error.explanation().contains("outside the addressable range 0..62"), error.explanation());
      assertTrue(error.explanation().contains("Phoenix Tuner"), error.explanation());
    }

    @Test
    @DisplayName("torque-current output is refused, and FOC is named as the thing you wanted")
    void torqueCurrentMessage() {
      List<ConfigError> errors =
          healthyElevator()
              .motor(MotorSpec.talonFX(20, "rio").outputMode(OutputMode.TORQUE_CURRENT))
              .build()
              .errors();
      ConfigError error =
          errors.stream()
              .filter(e -> e.explanation().contains("outputMode"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no outputMode error in " + errors));
      assertTrue(error.explanation().contains("expected VOLTAGE"), error.explanation());
      assertTrue(error.explanation().contains("volts-per-SI"), error.explanation());
      assertTrue(error.explanation().contains("amps-per-SI"), error.explanation());
      assertTrue(error.explanation().contains(".foc(true)"), error.explanation());
      assertFalse(OutputMode.TORQUE_CURRENT.isSupported());
      assertTrue(OutputMode.VOLTAGE.isSupported());
    }

    @Test
    @DisplayName("a gravity mode with kG at zero says exactly how to measure kG")
    void zeroGravityGainMessage() {
      ConfigError error =
          only(
              healthyElevator()
                  .gains(org.pumpkinlib.control.Gains.pid(80.0, 0.0, 2.0).withKv(5.0))
                  .build()
                  .errors(),
              "control.gains.kG");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertEquals("0.00 V", error.value());
      assertTrue(error.explanation().contains("nothing holds this mechanism up"),
          error.explanation());
      assertTrue(error.explanation().contains("falls"), error.explanation());
      assertTrue(error.explanation().contains("Procedure:"), error.explanation());
      assertTrue(error.explanation().contains("raise kG with kP = 0"), error.explanation());
      assertTrue(error.explanation().contains("/Tuning/Elevator/kG"), error.explanation());
    }

    @Test
    @DisplayName("Motion Magic Expo without measured kV and kA names the factory defaults")
    void expoWithoutFeedforwardMessage() {
      ConfigError error =
          only(
              healthyElevator()
                  .controlLocation(org.pumpkinlib.control.ControlLocation.ON_MOTOR_PROFILED)
                  .useExpo(true)
                  .gains(org.pumpkinlib.control.Gains.pid(80.0, 0.0, 2.0).withKg(0.33))
                  .build()
                  .errors(),
              "control.useExpo");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertTrue(error.value().contains("kV = 0.0000"), error.value());
      assertTrue(error.explanation().contains("0.12 V/rps"), error.explanation());
      assertTrue(error.explanation().contains("some other mechanism entirely"), error.explanation());
    }

    @Test
    @DisplayName("a DIO hard stop is flagged as slower than a firmware limit, but still allowed")
    void dioHardStopMessage() {
      ConfigError error =
          only(
              healthyElevator()
                  .hardStop(HardStop.REVERSE, SensorSpec.dio(0, false))
                  .build()
                  .errors(),
              "limits.hardStop(REVERSE)");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertTrue(error.explanation().contains("20 ms of travel"), error.explanation());
      assertTrue(
          error.explanation().contains("SensorSpec.motorLimit(SensorSpec.Limit.REVERSE)"),
          error.explanation());
      // Explicitly not a mistake — just a trade-off, and the message says so.
      assertTrue(error.explanation().contains("legitimate choice"), error.explanation());

      // A firmware limit switch draws no complaint at all.
      assertTrue(
          healthyElevator()
              .hardStop(HardStop.REVERSE, SensorSpec.motorLimit(SensorSpec.Limit.REVERSE))
              .build()
              .errors()
              .stream()
              .noneMatch(e -> e.field().startsWith("limits.hardStop")));
    }

    @Test
    @DisplayName("a continuous axis with sub-360 soft limits explains the shortest-path hazard")
    void continuousWithBoundedTravelMessage() {
      ConfigError error =
          only(
              PositionConfig.rotary("Turret")
                  .motor(MotorSpec.talonFX(25, "rio"))
                  .reduction(Reduction.of(50.0))
                  .axis(RotaryAxis.turret(true))
                  .softLimits(Degrees.of(-90.0), Degrees.of(90.0))
                  .constraints(MotionConstraints.of(180.0, 540.0))
                  .tolerance(Degrees.of(1.0), 5.0, 0.06)
                  .homing(HomingStrategy.assumeAtBoot(Degrees.of(0.0)))
                  .sim(KilogramSquareMeters.of(0.02), Degrees.of(0.0))
                  .build()
                  .errors(),
              "axis.continuous");
      assertEquals(ConfigError.Severity.WARNING, error.severity());
      assertTrue(error.value().contains("[-90.000, 90.000] deg"), error.value());
      assertTrue(
          error.explanation().contains("straight through the end stop"), error.explanation());
      assertTrue(error.explanation().contains("RotaryAxis.turret(false)"), error.explanation());
    }

    @Test
    @DisplayName("a velocity mechanism with a gravity term is refused with the creep explained")
    void gravityOnAVelocityMechanismMessage() {
      ConfigError error =
          only(
              VelocityConfig.of("Shooter")
                  .motor(MotorSpec.talonFX(30, "rio"))
                  .reduction(Reduction.of(1.5))
                  .axis(RotaryAxis.roller())
                  .gains(org.pumpkinlib.control.Gains.pid(0.1, 0, 0).withKv(0.12))
                  .sim(KilogramSquareMeters.of(0.004))
                  .build()
                  .withControl(
                      ControlConfig.defaults()
                          .withGravity(GravityMode.CONSTANT)
                          .withGains(org.pumpkinlib.control.Gains.pid(0.1, 0, 0).withKv(0.12)))
                  .errors(),
              "control.gravity");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("NONE", error.expected());
      assertTrue(error.explanation().contains("no angle to be applied at"), error.explanation());
      assertTrue(error.explanation().contains("flywheel would creep"), error.explanation());
      assertTrue(error.explanation().contains("PositionConfig"), error.explanation());
    }

    @Test
    @DisplayName("every message ends up somewhere a student can act on")
    void everyMessageOffersAFix() {
      PositionConfig broken =
          PositionConfig.linear("Wrist")
              .motor(MotorSpec.talonFX(64, "rio"))
              .reduction(Reduction.ofStages(3.0, 4.0))
              .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
              .softLimits(Inches.of(55.0), Inches.of(0.0))
              .gravity(GravityMode.COSINE)
              .tolerance(Degrees.of(1.0), 0.05, 0.06)
              .constraints(MotionConstraints.of(50.0, 6.0))
              .setpoint("L4", Inches.of(80.0))
              .build();
      for (ConfigError error : broken.errors()) {
        assertFalse(error.explanation().isBlank(), error.summary() + " has no explanation");
        assertTrue(
            error.explanation().contains("Fix")
                || error.explanation().contains("Procedure")
                || error.explanation().contains("Alternative"),
            error.summary() + " tells a student what is wrong but not what to type: "
                + error.explanation());
        assertFalse(error.owner().isBlank(), error.summary());
        assertFalse(error.field().isBlank(), error.summary());
      }
    }
  }

  // ===============================================================================================
  // Cross-config checks: run once, globally
  // ===============================================================================================

  @Nested
  @DisplayName("cross-config checks")
  final class CrossChecks {

    @Test
    @DisplayName("two mechanisms with the same name collide on every log key")
    void duplicateMechanismNames() {
      PositionConfig a = healthyElevator().build();
      PositionConfig b = healthyElevator().motor(MotorSpec.talonFX(40, "rio")).build();
      ConfigError error = only(Validation.crossChecks(a, b), "name");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      assertEquals("2 mechanisms share this name", error.value());
      assertEquals("one mechanism per name", error.expected());
      assertTrue(error.explanation().contains("publish on top of each other"), error.explanation());
      assertTrue(error.explanation().contains("\"ElevatorLeft\""), error.explanation());
    }

    @Test
    @DisplayName("two devices claiming one CAN id are named with their owners and the consequence")
    void duplicateCanIds() {
      PositionConfig elevator =
          healthyElevator().motor(MotorSpec.talonFX(22, "rio")).build();
      PositionConfig arm = healthyArm().motor(MotorSpec.talonFX(22, "rio")).build();
      List<ConfigError> conflicts =
          Validation.crossChecks(elevator, arm).stream()
              .filter(e -> e.field().startsWith("CAN id"))
              .toList();
      assertEquals(1, conflicts.size(), conflicts.toString());
      ConfigError error = conflicts.get(0);
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("CAN id 22 on bus \"rio\"", error.field());
      assertEquals("22", error.value());
      assertEquals("one device per id per bus", error.expected());
      assertTrue(error.explanation().contains("\"Elevator\""), error.explanation());
      assertTrue(error.explanation().contains("\"Arm\""), error.explanation());
      assertTrue(error.explanation().contains("will fight"), error.explanation());
      assertTrue(error.explanation().contains("Phoenix Tuner X"), error.explanation());
    }

    @Test
    @DisplayName("a correct pair of mechanisms produces no cross-config error at all")
    void correctPairIsSilent() {
      assertEquals(List.of(), Validation.crossChecks(healthyElevator().build(), healthyArm().build()));
    }

    @Test
    @DisplayName("the same config copied by with*() does NOT read as a duplicate device")
    void overlayCopiesDoNotSelfConflict() {
      // §5.6b: revision 1 registered CAN ids from the compact constructor, so the per-robot
      // overlay pattern (which rebuilds a config through with*()) reported a false conflict on a
      // CORRECT config. Building a copy must have no global side effect whatsoever.
      PositionConfig base = healthyElevator().build();
      PositionConfig overlaid = base.withReduction(Reduction.ofStages(3.0, 5.0));
      assertEquals(List.of(), Validation.crossChecks(overlaid));
      assertEquals(List.of(), Validation.lookupMisses());
    }

    @Test
    @DisplayName("a missed setpoint lookup is recorded globally instead of throwing")
    void setpointLookupMissIsRecorded() {
      PositionConfig elevator = healthyElevator().setpoint("L4", Inches.of(52.5)).build();
      Setpoint missing = assertDoesNotThrow(() -> elevator.setpoint("l4"));
      assertFalse(missing.isResolved());
      assertTrue(Double.isNaN(missing.valueUser()));

      ConfigError error = only(Validation.lookupMisses(), "setpoint(\"l4\")");
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("Elevator", error.owner());
      assertEquals("no such setpoint", error.value());
      // The expected field lists what IS declared, so the typo is visible side by side.
      assertEquals("\"L4\"", error.expected());
      assertTrue(error.explanation().contains("check the spelling and the capitalisation"),
          error.explanation());

      // ... and it reaches crossChecks(), which is where the robot actually reads it.
      assertTrue(Validation.crossChecks(elevator).contains(error));
    }

    @Test
    @DisplayName("a whitespace-padded setpoint name says so, because it is invisible on screen")
    void whitespacePaddedLookupSaysSo() {
      PositionConfig elevator = healthyElevator().setpoint("L4", Inches.of(52.5)).build();
      elevator.setpoint("L4 ");
      ConfigError error = only(Validation.lookupMisses(), "setpoint(\"L4 \")");
      assertTrue(
          error.explanation().contains("leading or trailing whitespace"), error.explanation());
    }

    @Test
    @DisplayName("findSetpoint() is the silent lookup and records nothing")
    void findSetpointDoesNotRecord() {
      PositionConfig elevator = healthyElevator().setpoint("L4", Inches.of(52.5)).build();
      assertEquals(Optional.empty(), elevator.findSetpoint("nope"));
      assertTrue(elevator.findSetpoint("L4").isPresent());
      assertEquals(List.of(), Validation.lookupMisses());
    }
  }

  // ===============================================================================================
  // Rendering
  // ===============================================================================================

  @Nested
  @DisplayName("how an error renders")
  final class Rendering {

    @Test
    @DisplayName("describe() lays out field, value, expected and explanation on separate lines")
    void describeIsAReadableBlock() {
      ConfigError error =
          only(
              healthyElevator().softLimits(Inches.of(55.0), Inches.of(0.0)).build().errors(),
              "limits.min / limits.max");
      String text = error.describe();
      assertTrue(text.contains("[FATAL]"), text);
      assertTrue(text.contains("config error in \"Elevator\""), text);
      assertTrue(text.contains("  field    limits.min / limits.max"), text);
      assertTrue(text.contains("  value    min = 1.3970 m, max = 0.0000 m"), text);
      assertTrue(text.contains("  expected limits.min < limits.max"), text);
    }

    @Test
    @DisplayName("summary() is one line and carries the owner, field, value and expectation")
    void summaryIsOneLine() {
      ConfigError error =
          only(
              healthyElevator().softLimits(Inches.of(55.0), Inches.of(0.0)).build().errors(),
              "limits.min / limits.max");
      String summary = error.summary();
      assertEquals(1, summary.lines().count(), summary);
      assertTrue(summary.startsWith("[FATAL] Elevator.limits.min / limits.max"), summary);
      assertTrue(summary.contains("= min = 1.3970 m, max = 0.0000 m"), summary);
      assertTrue(summary.contains("(expected limits.min < limits.max)"), summary);
      assertEquals(summary, error.toString());
    }

    @Test
    @DisplayName("toStrings() renders one entry per error, the same text the console prints")
    void toStringsMatchesTheConsole() {
      List<ConfigError> errors =
          healthyElevator().softLimits(Inches.of(55.0), Inches.of(0.0)).build().errors();
      List<String> rendered = Validation.toStrings(errors);
      assertEquals(errors.size(), rendered.size());
      for (int i = 0; i < errors.size(); i++) {
        assertEquals(errors.get(i).describe(), rendered.get(i));
      }
    }

    @Test
    @DisplayName("printAll never throws, on any list including an empty one")
    void printAllIsTotal() {
      assertDoesNotThrow(() -> Validation.printAll(null));
      assertDoesNotThrow(() -> Validation.printAll(List.of()));
      assertDoesNotThrow(
          () ->
              Validation.printAll(
                  healthyElevator().softLimits(Inches.of(55.0), Inches.of(0.0)).build().errors()));
    }

    @Test
    @DisplayName("a blank field is normalised rather than printed as an empty gap")
    void blankFieldsAreNormalised() {
      ConfigError error = new ConfigError(null, null, "  ", null, null, null, null);
      assertEquals(ConfigError.Severity.FATAL, error.severity());
      assertEquals("(unnamed mechanism)", error.owner());
      assertEquals("(unnamed field)", error.field());
      assertEquals("", error.value());
      assertEquals("", error.expected());
      assertEquals("", error.explanation());
      assertDoesNotThrow(error::describe);
      assertDoesNotThrow(error::summary);
    }
  }
}

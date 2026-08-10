package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Pounds;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.SafeMode;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.RotaryAxis;

/**
 * The 11pm-before-competition case: a wrong config boots the robot instead of killing it.
 *
 * <p><strong>The scenario, in full.</strong> A student edits a soft limit at an event. The value is
 * wrong. Under revision 1 of the design, validation threw from the record's compact constructor —
 * and every documented example declares its configs as {@code public static final} fields, so the
 * throw came out of {@code frc.robot.RobotConfig.<clinit>} as an {@code ExceptionInInitializerError}.
 * Robot code never starts. The driver station shows red "Robot Code". The carefully written message
 * is a <em>cause</em>, four frames down, under JVM class-initialisation machinery that means nothing
 * to a student. There is no safe mode, no dashboard, and no diagnosis.
 *
 * <p><strong>What must happen instead.</strong> The record constructor is pure and non-throwing; it
 * <em>stores</em> the errors. Static initialisation completes. The robot boots, connects, and
 * publishes the whole list. Every mechanism refuses to move. The difference is a five-minute fix in
 * the pits versus a lost match.
 *
 * <p><strong>Scope note.</strong> The class-initialisation half of that contract and the
 * error-to-fault translation are exercised here natively-free. Entering safe mode with a FATAL fault
 * raises a live {@code PumpkinAlert}, which eagerly constructs an {@code edu.wpi.first.wpilibj.Alert}
 * and therefore reaches NetworkTables; on a JVM without the WPILib JNI natives WPILib's loader calls
 * {@code System.exit(1)} rather than throwing, which would kill the test JVM with no diagnostic. So
 * that one assertion is {@code @Tag("hal")} and runs under {@code ./gradlew halTest}, following the
 * same split as {@code AlertBudgetTest} / {@code AlertBudgetHalTest}.
 */
final class SafeModeTest {

  // ===============================================================================================
  // THE POINT: these are static final fields, exactly as every documented example declares them,
  // and every one of them is wrong. If loading this class throws, the test class cannot even be
  // instantiated — which is precisely the failure being pinned.
  // ===============================================================================================

  /** A reduction that was never filled in, and a cruise velocity nobody checked. */
  static final PositionConfig kBrokenElevator =
      PositionConfig.linear("Elevator")
          .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio")))
          .reduction(Reduction.ofStages(3.0, 4.0))
          .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
          // swapped, which is the edit a student makes at 11pm
          .softLimits(Inches.of(55.0), Inches.of(0.0))
          .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
          .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33))
          .constraints(MotionConstraints.of(1.6, 6.0))
          .tolerance(Inches.of(0.5), 0.05, 0.06)
          .homing(HomingStrategy.currentSpike().seedTo(Inches.of(0.0)))
          .sim(Pounds.of(24.0), Inches.of(0.0))
          .build();

  /** A CAN id that a Phoenix constructor would throw on, and a tolerance in the wrong domain. */
  static final PositionConfig kBrokenArm =
      PositionConfig.rotary("Arm")
          .motors(MotorGroup.leader(MotorSpec.talonFX(64, "rio")))
          .reduction(Reduction.of(65.4111))
          .axis(RotaryAxis.arm(Degrees.of(0.0)))
          .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
          .constraints(MotionConstraints.of(180.0, 540.0))
          .tolerance(Inches.of(1.0), 5.0, 0.06)
          .homing(HomingStrategy.absoluteSeed())
          .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95.0)))
          .build();

  /** The typed setpoint handle pattern — a lookup MISS, also from a static final field. */
  static final Setpoint kMissingSetpoint = kBrokenElevator.setpoint("L4");

  @BeforeEach
  @AfterEach
  void resetGlobalState() {
    SafeMode.resetForTest();
    Validation.resetForTest();
  }

  // ===============================================================================================
  // Static initialisation completes
  // ===============================================================================================

  @Nested
  @DisplayName("nothing throws from a static initialiser")
  final class StaticInitialisation {

    @Test
    @DisplayName("the broken static final fields loaded — the class initialiser ran to completion")
    void theClassInitialiserCompleted() {
      // If any of the three declarations above had thrown, this method would never run: JUnit
      // would report an ExceptionInInitializerError against the whole class. Reaching here IS
      // the assertion; the rest confirms the fields hold real, inspectable values.
      assertNotNull(kBrokenElevator);
      assertNotNull(kBrokenArm);
      assertNotNull(kMissingSetpoint);
      assertEquals("Elevator", kBrokenElevator.name());
      assertEquals("Arm", kBrokenArm.name());
    }

    @Test
    @DisplayName("a setpoint lookup that misses returns an unresolved handle, not an exception")
    void aMissedSetpointIsAValue() {
      // ELEVATOR_L4 = ELEVATOR.setpoint("L4") is the documented pattern, and it is evaluated at
      // class-init time. A throw here would take the robot down for a typo in a goal name.
      assertFalse(kMissingSetpoint.isResolved());
      assertEquals("Elevator", kMissingSetpoint.mechanism());
      assertEquals("L4", kMissingSetpoint.name());
      assertTrue(Double.isNaN(kMissingSetpoint.valueUser()));
      assertTrue(kMissingSetpoint.describe().contains("UNRESOLVED"), kMissingSetpoint.describe());
      assertEquals(1, kMissingSetpoint.problems().size());
      assertTrue(
          kMissingSetpoint.problems().get(0).contains("there is no setpoint named \"L4\""),
          kMissingSetpoint.problems().get(0));
    }

    @Test
    @DisplayName("describe() still works on a fatally broken config — it is what you run next")
    void describeStillWorks() {
      String text = assertDoesNotThrow(kBrokenElevator::describe);
      assertTrue(text.contains("=== Elevator"), text);
      assertTrue(text.contains("Config errors"), text);
      assertTrue(text.contains("[FATAL] Elevator.limits.min / limits.max"), text);
      assertDoesNotThrow(kBrokenArm::describe);
      assertDoesNotThrow(() -> Validation.describeAll(kBrokenElevator, kBrokenArm));
    }
  }

  // ===============================================================================================
  // The errors are collected and are FATAL
  // ===============================================================================================

  @Nested
  @DisplayName("the errors are collected, and they are the ones that block a match")
  final class Collection {

    @Test
    @DisplayName("both configs report a fatal error rather than having refused to exist")
    void bothAreFatal() {
      assertTrue(kBrokenElevator.hasFatalError(), kBrokenElevator.errors().toString());
      assertTrue(kBrokenArm.hasFatalError(), kBrokenArm.errors().toString());
      assertTrue(
          kBrokenElevator.errors().stream()
              .anyMatch(e -> e.isFatal() && e.field().equals("limits.min / limits.max")),
          kBrokenElevator.errors().toString());
      assertTrue(
          kBrokenArm.errors().stream()
              .anyMatch(e -> e.explanation().contains("CAN device id 64")),
          kBrokenArm.errors().toString());
    }

    @Test
    @DisplayName("validateAll gathers every mechanism's list plus the global scan in one pass")
    void oneListForTheWholeRobot() {
      // The same lookup the static final ELEVATOR_L4 handle performs at class-init time. It is
      // repeated here rather than relied on from the field, because @BeforeEach clears the global
      // miss list so that these tests do not depend on each other's ordering.
      Setpoint missed = kBrokenElevator.setpoint("L4");
      assertFalse(missed.isResolved());

      List<ConfigError> all = Validation.validateAll(kBrokenElevator, kBrokenArm);
      assertTrue(all.size() >= kBrokenElevator.errors().size() + kBrokenArm.errors().size());
      assertTrue(all.containsAll(kBrokenElevator.errors()));
      assertTrue(all.containsAll(kBrokenArm.errors()));
      // The setpoint miss reaches the same list, so one report covers the whole robot.
      assertTrue(
          all.stream().anyMatch(e -> e.field().equals("setpoint(\"L4\")")),
          all.stream().map(ConfigError::field).toList().toString());
      // ... as does the out-of-range CAN id, found by the ONE global device scan.
      assertTrue(
          all.stream().anyMatch(e -> e.field().equals("CAN id")),
          all.stream().map(ConfigError::field).toList().toString());
      assertTrue(all.stream().anyMatch(ConfigError::isFatal));
    }
  }

  // ===============================================================================================
  // ConfigError -> SafeMode.Fault
  // ===============================================================================================

  @Nested
  @DisplayName("errors translate into the faults SafeMode publishes")
  final class Translation {

    @Test
    @DisplayName("every field survives the round trip, in both directions")
    void faultRoundTrip() {
      ConfigError error =
          kBrokenElevator.errors().stream()
              .filter(e -> e.field().equals("limits.min / limits.max"))
              .findFirst()
              .orElseThrow();

      SafeMode.Fault fault = error.toFault();
      assertEquals(SafeMode.Level.FATAL, fault.level());
      assertEquals("Elevator", fault.owner());
      assertEquals("limits.min / limits.max", fault.field());
      assertEquals("min = 1.3970 m, max = 0.0000 m", fault.value());
      assertEquals("limits.min < limits.max", fault.expected());
      assertEquals(error.explanation(), fault.explanation());
      assertTrue(fault.isFatal());

      assertEquals(error, ConfigError.fromFault(fault));
    }

    @Test
    @DisplayName("the three severities map one-for-one onto the three levels, both ways")
    void severitiesMap() {
      assertEquals(SafeMode.Level.FATAL, ConfigError.Severity.FATAL.toLevel());
      assertEquals(SafeMode.Level.WARNING, ConfigError.Severity.WARNING.toLevel());
      assertEquals(SafeMode.Level.PLACEHOLDER, ConfigError.Severity.PLACEHOLDER.toLevel());
      for (SafeMode.Level level : SafeMode.Level.values()) {
        assertEquals(level, Validation.severityOf(level).toLevel());
      }
      for (ConfigError.Severity severity : ConfigError.Severity.values()) {
        assertEquals(severity, Validation.severityOf(severity.toLevel()));
      }
    }

    @Test
    @DisplayName("a value-free error still renders something rather than an empty column")
    void emptyFieldsAreRenderedNotBlank() {
      // The CAN-id error is lifted from MotorSpec.problems(), which carries its detail entirely
      // in the explanation. SafeMode.Fault forbids nulls, so the blanks are filled with a marker.
      ConfigError lifted =
          kBrokenArm.errors().stream()
              .filter(e -> e.explanation().contains("CAN device id 64"))
              .findFirst()
              .orElseThrow();
      assertEquals("", lifted.value());
      SafeMode.Fault fault = lifted.toFault();
      assertEquals(ConfigError.kNoValueRendered, fault.value());
      assertEquals(ConfigError.kNoExpectationRendered, fault.expected());
      assertFalse(fault.summary().contains("null"), fault.summary());
      assertTrue(fault.describe().contains("CAN device id 64"), fault.describe());
    }

    @Test
    @DisplayName("toFaults() converts a whole list and tolerates null and empty")
    void listConversion() {
      List<SafeMode.Fault> faults = ConfigError.toFaults(kBrokenElevator.errors());
      assertEquals(kBrokenElevator.errors().size(), faults.size());
      assertEquals(List.of(), ConfigError.toFaults(null));
      assertEquals(List.of(), ConfigError.toFaults(List.of()));
      assertTrue(faults.stream().anyMatch(SafeMode.Fault::isFatal));
    }
  }

  // ===============================================================================================
  // Entering SafeMode
  // ===============================================================================================

  @Nested
  @DisplayName("entering safe mode")
  final class Entering {

    @Test
    @DisplayName("a clean robot never enters safe mode")
    void noFaultsMeansNoSafeMode() {
      assertFalse(SafeMode.isActive());
      SafeMode.enter(List.of());
      assertFalse(SafeMode.isActive());
      assertEquals(List.of(), SafeMode.faults());
      assertTrue(SafeMode.describe().contains("inactive, 0 faults"));
    }

    @Test
    @DisplayName("warnings and placeholders are collected WITHOUT blocking the robot")
    void nonFatalFaultsDoNotActivate() {
      // Everything below is deliberately non-fatal, so refreshAlert() short-circuits and no
      // NetworkTables-backed alert is constructed. That is what keeps this case native-free.
      List<ConfigError> nonFatal =
          Validation.validateAll(kBrokenElevator).stream()
              .filter(e -> !e.isFatal())
              .toList();
      assertFalse(nonFatal.isEmpty(), "the fixture should carry warnings and placeholders");

      SafeMode.enter(ConfigError.toFaults(nonFatal));
      assertFalse(SafeMode.isActive(), "a warning must not stop the robot from moving");
      assertEquals(nonFatal.size(), SafeMode.faults().size());
      assertEquals(List.of(), SafeMode.fatalFaults());
      assertTrue(SafeMode.firstFatal().isEmpty());

      // ... but they are all still readable, which is the whole reason to collect them.
      assertEquals(nonFatal.size(), SafeMode.toStrings().size());
      String text = SafeMode.describe();
      assertTrue(text.contains("inactive"), text);
      assertTrue(text.contains(nonFatal.size() + " fault(s) collected"), text);
    }

    @Test
    @DisplayName("the published keys are the ones the driver dashboard reads")
    void thePublishedKeysAreStable() {
      assertEquals("/Pumpkin/Driver/SafeMode", SafeMode.kSafeModeKey);
      assertEquals("/Pumpkin/Driver/SafeModeErrors", SafeMode.kSafeModeErrorsKey);
      assertEquals("Config", SafeMode.kAlertGroup);
    }

    @Test
    @DisplayName("printAll says the robot HAS booted and is refusing to move")
    void theConsoleReportSaysWhatHappened() {
      // The sentence a student reads in the riolog has to answer "is my robot alive?" first.
      assertDoesNotThrow(
          () -> Validation.printAll(Validation.validateAll(kBrokenElevator, kBrokenArm)));
      String checklist = Validation.checklist(Validation.validateAll(kBrokenElevator, kBrokenArm));
      assertTrue(checklist.isEmpty() || checklist.contains("First-setup checklist"), checklist);
    }

    @Test
    @Tag("hal")
    @DisplayName("a FATAL fault activates safe mode and raises one sticky driver alert")
    void fatalFaultsActivateSafeMode() {
      // Tagged "hal": SafeMode.enter() with a fatal fault constructs a PumpkinAlert, which
      // eagerly builds an edu.wpi.first.wpilibj.Alert and reaches NetworkTables. Run with
      // `./gradlew halTest` on a machine with the WPILib desktop natives.
      List<ConfigError> errors = Validation.validateAll(kBrokenElevator, kBrokenArm);
      assertTrue(errors.stream().anyMatch(ConfigError::isFatal));

      SafeMode.enter(ConfigError.toFaults(errors));

      assertTrue(SafeMode.isActive(), "a fatal config error must stop every mechanism");
      assertFalse(SafeMode.fatalFaults().isEmpty());
      assertTrue(SafeMode.firstFatal().isPresent());
      assertEquals(errors.size(), SafeMode.faults().size());

      String text = SafeMode.describe();
      assertTrue(text.contains("ACTIVE"), text);
      assertTrue(text.contains("The robot has BOOTED so you can read this"), text);
      assertTrue(text.contains("refuses every command"), text);
    }
  }
}

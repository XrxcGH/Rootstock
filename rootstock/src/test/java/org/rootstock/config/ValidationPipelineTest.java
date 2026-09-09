package org.rootstock.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.KilogramSquareMeters;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Rotations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.control.Gains;
import org.rootstock.core.RootstockLifecycle;
import org.rootstock.core.RootstockRegistry;
import org.rootstock.core.SafeMode;
import org.rootstock.core.config.CanIdRegistry;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.RotaryAxis;

/**
 * The validation <b>pipeline</b>: not what a check computes, but whether anything ever runs it.
 *
 * <p><strong>The failure this exists to prevent, which had already happened.</strong>
 * {@code Validation.install()} is the only thing that registers the config package's fault and
 * device extractors with {@code RootstockRegistry}, and it shipped with <em>zero call sites</em>.
 * Measured on the shipped code with a standalone program against the real classpath: a
 * {@code PositionConfig} with swapped soft limits reported two errors and one CAN device to
 * {@code Validation.errorsOf} / {@code devicesOf}, and {@code RootstockRegistry.addAll(config)}
 * then printed <em>"0 CAN device(s) scanned; 0 config fault(s)"</em> with
 * {@code SafeMode.isActive() == false}. Every FATAL message in {@link Validation}, the whole
 * first-setup checklist, and the single global duplicate-CAN-id scan were computed and read by
 * nobody. {@link ValidationTest} was green throughout, because it calls the checks directly.
 *
 * <p>So the tests here are deliberately about <em>reachability</em>, and two of them assert on the
 * compiled call graph rather than on a return value. A test that calls a method proves the method
 * works; it does not prove the product calls it.
 *
 * <p>{@link ThroughTheRegistry} is {@code @Tag("hal")} because
 * {@code RootstockRegistry.addAll(...)} reaches {@code Rootstock.isDisabled} to
 * {@code Filesystem.getDeployDirectory()} to the HAL, and WPILib's {@code JNIWrapper} calls
 * {@code System.exit(1)} when the natives are missing rather than throwing. That is why the seam
 * had no coverage: the one call in the library that runs it cannot run in the default test task.
 * Everything above it here is native-free and runs in {@code ./gradlew test}.
 */
final class ValidationPipelineTest {

  @BeforeEach
  @AfterEach
  void resetGlobalState() {
    SafeMode.resetForTest();
    Validation.resetForTest();
  }

  // ===============================================================================================
  // Fixtures
  // ===============================================================================================

  /**
   * An elevator that is correct in every respect, tolerance included.
   *
   * <p>Deliberately not {@link ValidationTest}'s {@code healthyElevator()}: that one carries a
   * tolerance narrower than one loop step and so reports a WARNING, which is fine for a test that
   * filters by field and useless for one that has to say "this config is silent".
   */
  private static PositionConfig.Builder cleanElevator(String name, int leader, int follower) {
    return PositionConfig.linear(name)
        .motors(
            MotorGroup.leader(MotorSpec.talonFX(leader, "rio").foc(true))
                .follower(MotorSpec.talonFX(follower, "rio"), Follower.OPPOSED))
        .reduction(Reduction.ofStages(3.0, 4.0))
        .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
        .feedback(new FeedbackSpec.RotorOnly())
        .softLimits(Inches.of(0.0), Inches.of(55.0))
        .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
        .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33))
        .constraints(MotionConstraints.of(1.6, 6.0))
        .tolerance(Inches.of(2.0), 0.05, 0.06)
        .homing(
            HomingStrategy.currentSpike()
                .direction(HomingStrategy.Direction.REVERSE)
                .seedTo(Inches.of(0.0)))
        .setpoint("L4", Inches.of(52.0))
        .sim(Pounds.of(24.0), Inches.of(0.0));
  }

  /** The same elevator with the one mistake a student makes most: the soft limits swapped. */
  private static PositionConfig.Builder swappedLimits(String name, int leader, int follower) {
    return cleanElevator(name, leader, follower)
        .softLimits(Inches.of(55.0), Inches.of(0.0));
  }

  /** A rotary arm that is correct in every respect. */
  private static PositionConfig.Builder cleanArm(String name, int leader, int cancoder) {
    return PositionConfig.rotary(name)
        .motor(MotorSpec.talonFX(leader, "rio").inverted(true))
        .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
        .axis(RotaryAxis.arm(Degrees.of(0.0)))
        .feedback(
            new FeedbackSpec.FusedCancoder(
                cancoder, "rio", Rotations.of(-0.1387), 141288.0 / 2160.0, 1.0))
        .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
        .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
        .gains(Gains.pid(5.0, 0.0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29))
        .constraints(MotionConstraints.of(180.0, 540.0))
        .tolerance(Degrees.of(4.0), 5.0, 0.06)
        .homing(HomingStrategy.absoluteSeed());
  }

  /**
   * What a mechanism looks like to the pipeline once it can hand over its config.
   *
   * <p>{@code PositionMechanism}, {@code VelocityMechanism} and {@code SimpleMechanism} each hold
   * their config in a private field with no accessor, so a registered mechanism matches none of
   * {@link Validation}'s {@code instanceof} branches. {@link Validation.ConfigCarrier} is the seam
   * that closes that, and this stands in for it here without needing the HAL that constructing a
   * real mechanism needs.
   */
  private record FakeMechanism(Object config) implements Validation.ConfigCarrier {}

  private static JavaClasses mainClasses() {
    return new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("org.rootstock");
  }

  private static boolean calls(JavaClass caller, Class<?> targetOwner, String method) {
    return caller.getCodeUnits().stream()
        .flatMap(unit -> unit.getMethodCallsFromSelf().stream())
        .anyMatch(
            call ->
                call.getTargetOwner().getName().equals(targetOwner.getName())
                    && call.getName().equals(method));
  }

  // ===============================================================================================
  // The wiring
  // ===============================================================================================

  @Nested
  @DisplayName("the wiring: who calls what")
  final class Wiring {

    @Test
    @DisplayName("building any config wires the config package into RootstockRegistry")
    void buildingAConfigInstallsTheRegistryIntegration() {
      PositionConfig config = cleanElevator("WiringElevator", 20, 21).build();
      assertEquals("WiringElevator", config.name());

      assertTrue(
          Validation.isInstalled(),
          "Validation.install() has to have run by the time a config exists, because a config is "
              + "the earliest thing that can be handed to RootstockRegistry.addAll(...). If this "
              + "fails, every ConfigError in the library is computed and read by nobody.");
    }

    @Test
    @DisplayName("Validation.install() is reachable from the config package, not just declared")
    void installHasACallSite() {
      JavaClasses classes = mainClasses();
      assertTrue(
          calls(classes.get(Validation.class), RootstockRegistry.class, "addFaultExtractor"),
          "Validation must register a fault extractor with RootstockRegistry");
      assertTrue(
          calls(classes.get(Validation.class), RootstockRegistry.class, "addDeviceExtractor"),
          "Validation must register a device extractor, or the global CAN id scan sees nothing");
      assertTrue(
          calls(classes.get(Validation.class), Validation.class, "install"),
          "install() must have a caller inside this package. It shipped with none, which is the "
              + "whole defect this class exists for: grep for the call site, do not assume it.");

      boolean everyLocalChecksInstalls =
          classes.get(Validation.class).getMethods().stream()
              .filter(method -> method.getName().equals("localChecks"))
              .allMatch(
                  method ->
                      method.getMethodCallsFromSelf().stream()
                          .anyMatch(
                              call ->
                                  call.getTargetOwner()
                                          .getName()
                                          .equals(Validation.class.getName())
                                      && call.getName().equals("installOnce")));
      assertTrue(
          everyLocalChecksInstalls,
          "every localChecks overload must install the pipeline, because each one is the compact "
              + "constructor of a different config record and any of the three can be the first "
              + "config a robot builds");
    }

    @Test
    @DisplayName("RootstockLifecycle.init() calls the cross-config report")
    void theLifecycleRunsTheCrossChecks() {
      assertTrue(
          calls(mainClasses().get(RootstockLifecycle.class), Validation.class, "reportCrossChecks"),
          "Duplicate mechanism names and setpoint-name typos are found nowhere else. "
              + "RootstockRegistry.addAll sees one component at a time and cannot make either "
              + "check, and D29 puts init() after addAll and before the robot can be enabled.");
    }
  }

  // ===============================================================================================
  // A known-bad value is raised; a good config is silent
  // ===============================================================================================

  @Nested
  @DisplayName("what the installed extractor produces")
  final class WhatTheExtractorProduces {

    /**
     * The exact expression {@code Validation.install()} registers with {@code RootstockRegistry},
     * so a change to either side of that lambda breaks this test.
     */
    private List<SafeMode.Fault> extract(Object component) {
      return ConfigError.toFaults(Validation.errorsOf(component));
    }

    @Test
    @DisplayName("a config with swapped soft limits yields a FATAL fault")
    void aKnownBadValueIsRaised() {
      PositionConfig broken = swappedLimits("BadElevator", 24, 25).build();

      List<SafeMode.Fault> faults = extract(broken);
      assertFalse(faults.isEmpty(), "the swapped soft limits must reach SafeMode as a fault");
      assertTrue(
          faults.stream().anyMatch(SafeMode.Fault::isFatal),
          "swapped soft limits are structurally impossible, so they are FATAL: " + faults);
      assertTrue(
          faults.stream()
              .anyMatch(f -> f.field().contains("limits.min") && f.owner().equals("BadElevator")),
          faults.toString());
    }

    @Test
    @DisplayName("a config that is correct in every respect yields nothing at all")
    void aGoodConfigPassesClean() {
      PositionConfig good = cleanElevator("GoodElevator", 26, 27).build();

      assertEquals(
          List.of(),
          good.errors(),
          "a clean config must be silent, or a team learns to scroll past the report");
      assertEquals(List.of(), extract(good));
      assertFalse(good.hasFatalError());
      assertEquals("", Validation.checklist(good.errors()));
    }

    @Test
    @DisplayName("the device extractor reports the config's CAN devices, for the global id scan")
    void devicesReachTheGlobalScan() {
      PositionConfig good = cleanElevator("ScannedElevator", 28, 29).build();

      List<CanIdRegistry.Device> devices = Validation.devicesOf(good);
      assertEquals(2, devices.size(), devices.toString());
      assertTrue(devices.stream().anyMatch(d -> d.deviceId() == 28), devices.toString());
      assertTrue(devices.stream().anyMatch(d -> d.deviceId() == 29), devices.toString());
    }
  }

  // ===============================================================================================
  // Mechanisms, which is what the documentation actually registers
  // ===============================================================================================

  @Nested
  @DisplayName("a component that carries a config is unwrapped first")
  final class Carriers {

    @Test
    @DisplayName("errorsOf sees through the carrier to the config's errors")
    void errorsAreFound() {
      PositionConfig broken = swappedLimits("CarriedElevator", 30, 31).build();
      Object mechanism = new FakeMechanism(broken);

      assertEquals(broken.errors(), Validation.errorsOf(mechanism));
      assertFalse(Validation.errorsOf(mechanism).isEmpty());
    }

    @Test
    @DisplayName("devicesOf sees through the carrier, so the CAN scan is not silently empty")
    void devicesAreFound() {
      PositionConfig good = cleanElevator("CarriedScan", 32, 33).build();

      assertEquals(good.canDevices(), Validation.devicesOf(new FakeMechanism(good)));
    }

    @Test
    @DisplayName("describe sees through the carrier rather than printing a toString")
    void describeIsTheConfigsBootDump() {
      PositionConfig good = cleanElevator("CarriedDump", 34, 35).build();

      String described = Validation.describe(new FakeMechanism(good));
      assertEquals(good.describe(), described);
      assertFalse(described.startsWith("FakeMechanism"), described);
    }

    @Test
    @DisplayName("two carriers of same-named configs still collide on the duplicate-name check")
    void duplicateNamesAreFoundThroughCarriers() {
      PositionConfig a = cleanElevator("Elevator", 36, 37).build();
      PositionConfig b = cleanElevator("Elevator", 38, 39).build();

      List<ConfigError> viaConfigs = Validation.crossChecks(a, b);
      List<ConfigError> viaCarriers =
          Validation.crossChecks(new FakeMechanism(a), new FakeMechanism(b));

      assertEquals(
          viaConfigs.size(),
          viaCarriers.size(),
          "registering the mechanism and registering the config it was built from must report the "
              + "same thing; the documented examples all register mechanisms");
      assertTrue(
          viaCarriers.stream().anyMatch(e -> e.field().equals("name") && e.isFatal()),
          viaCarriers.toString());
    }

    @Test
    @DisplayName("anything that is not a config and carries none is still ignored, not rejected")
    void unrelatedObjectsAreIgnored() {
      assertEquals(List.of(), Validation.errorsOf("not a config"));
      assertEquals(List.of(), Validation.devicesOf(new Object()));
      assertEquals(List.of(), Validation.errorsOf(new FakeMechanism(null)));
      assertEquals(List.of(), Validation.errorsOf(null));
    }
  }

  // ===============================================================================================
  // The two silent configs
  // ===============================================================================================

  @Nested
  @DisplayName("omitting .constraints(...)")
  final class MissingConstraints {

    private ConfigError constraintsError(List<ConfigError> errors) {
      List<ConfigError> matches =
          errors.stream().filter(e -> e.field().equals("control.constraints")).toList();
      assertEquals(1, matches.size(), errors.toString());
      return matches.get(0);
    }

    @Test
    @DisplayName("is a checklist line naming a real cruise velocity, not silence")
    void unconstrainedIsReported() {
      PositionConfig noConstraints =
          PositionConfig.linear("Unprofiled")
              .motors(
                  MotorGroup.leader(MotorSpec.talonFX(40, "rio").foc(true))
                      .follower(MotorSpec.talonFX(41, "rio"), Follower.OPPOSED))
              .reduction(Reduction.ofStages(3.0, 4.0))
              .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
              .feedback(new FeedbackSpec.RotorOnly())
              .softLimits(Inches.of(0.0), Inches.of(55.0))
              .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
              .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33))
              .tolerance(Inches.of(2.0), 0.05, 0.06)
              .homing(
                  HomingStrategy.currentSpike()
                      .direction(HomingStrategy.Direction.REVERSE)
                      .seedTo(Inches.of(0.0)))
              .sim(Pounds.of(24.0), Inches.of(0.0))
              .build();

      ConfigError error = constraintsError(noConstraints.errors());
      assertEquals(ConfigError.Severity.PLACEHOLDER, error.severity());
      assertEquals("MotionConstraints.unconstrained()", error.value());
      assertTrue(error.expected().contains("MotionConstraints.of("), error.expected());
      assertFalse(error.expected().contains("Infinity"), error.expected());
      assertFalse(error.expected().contains("NaN"), error.expected());
      assertTrue(error.explanation().contains("NO PROFILE"), error.explanation());
      assertTrue(
          error.explanation().contains("MotionMagicCruiseVelocity = 0"), error.explanation());

      // Non-blocking on purpose: an unconstrained axis is a documented, legal choice
      // (MotionConstraints.unconstrained exists for one), so this is a checklist line and not a
      // refusal. It has to appear on the checklist, which is the tier-3 contract.
      assertFalse(noConstraints.hasFatalError());
      assertTrue(
          Validation.checklist(noConstraints.errors()).contains("control.constraints"),
          Validation.checklist(noConstraints.errors()));
    }

    @Test
    @DisplayName("stating them makes the report quieter, which it did not before")
    void constrainedIsSilent() {
      PositionConfig constrained = cleanElevator("Profiled", 42, 43).build();

      assertTrue(
          constrained.errors().stream().noneMatch(e -> e.field().equals("control.constraints")),
          constrained.errors().toString());
    }
  }

  @Nested
  @DisplayName("the rotary sim convenience")
  final class RotarySim {

    @Test
    @DisplayName("takes the centre of mass, and produces a config with no fatal error")
    void rotarySimIsUsable() {
      PositionConfig arm =
          cleanArm("SimArm", 44, 45)
              .sim(KilogramSquareMeters.of(0.5), Meters.of(0.30), Degrees.of(-15.0))
              .build();

      assertFalse(
          arm.hasFatalError(),
          "the documented rotary sim convenience must be able to produce a valid config: "
              + arm.errors());
      assertTrue(
          arm.errors().stream().noneMatch(e -> e.field().equals("sim")),
          arm.errors().toString());
      assertEquals(0.30, arm.sim().armLengthMeters(), 1e-12);
      assertTrue(arm.sim().simulateGravity());
    }

    @Test
    @DisplayName("a zero centre of mass is still a fatal error, so the fix is not a silent default")
    void zeroCentreOfMassIsStillRefused() {
      PositionConfig arm =
          cleanArm("ZeroArm", 46, 47)
              .sim(KilogramSquareMeters.of(0.5), Meters.of(0.0), Degrees.of(-15.0))
              .build();

      assertTrue(
          arm.errors().stream().anyMatch(e -> e.field().equals("sim") && e.isFatal()),
          "gravity with no moment arm floats in sim and drops on the robot; the loud version of "
              + "that is strictly better than the quiet one: " + arm.errors());
    }
  }

  // ===============================================================================================
  // The documented call, end to end
  // ===============================================================================================

  @Nested
  @Tag("hal")
  @DisplayName("through RootstockRegistry.addAll, the one call the documentation names")
  final class ThroughTheRegistry {

    @Test
    @DisplayName("registering a broken config prints its faults and enters safe mode")
    void addAllReportsFaultsAndEntersSafeMode() {
      PositionConfig broken = swappedLimits("RegisteredBad", 48, 49).build();
      int devicesBefore = RootstockRegistry.devices().size();
      assertFalse(SafeMode.isActive(), "the fixture resets SafeMode before each test");

      RootstockRegistry.addAll(broken);

      assertTrue(
          SafeMode.isActive(),
          "a FATAL config error must stop every mechanism. If this fails the extractors are not "
              + "installed and addAll collected nothing: " + RootstockRegistry.bootSummary());
      assertFalse(SafeMode.fatalFaults().isEmpty());
      assertTrue(
          SafeMode.fatalFaults().stream().anyMatch(f -> f.owner().equals("RegisteredBad")),
          SafeMode.toStrings().toString());
      assertEquals(
          devicesBefore + 2,
          RootstockRegistry.devices().size(),
          "both declared motors must reach the single global CAN id scan");
      assertNotEquals(
          -1,
          RootstockRegistry.bootSummary().indexOf("config fault(s)"),
          RootstockRegistry.bootSummary());
      assertFalse(
          RootstockRegistry.bootSummary().contains("0 config fault(s)"),
          "the boot summary reported no faults on a config that has one: "
              + RootstockRegistry.bootSummary());
    }

    @Test
    @DisplayName("registering a clean config leaves the robot out of safe mode")
    void addAllOnACleanConfigIsSilent() {
      PositionConfig good = cleanElevator("RegisteredGood", 50, 51).build();

      RootstockRegistry.addAll(good);

      assertFalse(
          SafeMode.isActive(),
          "a correct config must not stop the robot: " + SafeMode.toStrings());
      assertTrue(
          RootstockRegistry.bootSummary().contains("0 config fault(s)"),
          RootstockRegistry.bootSummary());
    }

    @Test
    @DisplayName("a setpoint typo is reported by the lifecycle's cross-check pass, and drained")
    void setpointTyposAreReportedOnce() {
      PositionConfig elevator = cleanElevator("TypoElevator", 52, 53).build();
      Setpoint missed = elevator.setpoint("L4 ");
      assertFalse(missed.isResolved(), "the trailing space must not resolve");
      assertEquals(1, Validation.lookupMisses().size());

      Validation.reportCrossChecks();

      assertTrue(SafeMode.isActive(), "an unresolved setpoint refuses every command that uses it");
      assertTrue(
          SafeMode.toStrings().stream().anyMatch(s -> s.contains("L4 ")),
          SafeMode.toStrings().toString());
      assertEquals(
          List.of(),
          Validation.lookupMisses(),
          "the misses are drained, so a second addAll and init do not report them again");

      SafeMode.resetForTest();
      Validation.reportCrossChecks();
      assertFalse(SafeMode.isActive(), "nothing left to report on the second pass");
    }
  }
}

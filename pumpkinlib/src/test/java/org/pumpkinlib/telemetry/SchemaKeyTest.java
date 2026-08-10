package org.pumpkinlib.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.spi.LogConfig;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.schema.ControlMode;
import org.pumpkinlib.telemetry.schema.DriveSchema;
import org.pumpkinlib.telemetry.schema.FieldSchema;
import org.pumpkinlib.telemetry.schema.HealthSchema;
import org.pumpkinlib.telemetry.schema.MechanismSchema;
import org.pumpkinlib.telemetry.schema.ProvenanceSchema;
import org.pumpkinlib.telemetry.schema.SchemaEntry;
import org.pumpkinlib.telemetry.schema.SchemaGeneration;
import org.pumpkinlib.telemetry.schema.SchemaSection;
import org.pumpkinlib.telemetry.schema.VisionSchema;

/**
 * The emitted key layout, spelled out.
 *
 * <p><strong>Key names are a wire contract.</strong> AdvantageScope layouts, the replay-diff tool and
 * the five-minute "why did auto fail in match 42" triage all bind to these strings literally.
 * Renaming one is a breaking change for every team that upgrades and for every log already written,
 * and nothing in Java stops it — a key is a string constant, and a string constant is one careless
 * refactor away from being a different string constant. So the layout is written out here in full,
 * by hand, rather than derived from the constants it is meant to police.
 *
 * <h2>Error and GoalError</h2>
 *
 * <p>They are separate keys because they answer different questions. {@code Error} is
 * {@code Setpoint - Measured}: how far the mechanism is from <em>this cycle's profile sample</em>.
 * {@code GoalError} is {@code Goal - Measured}: how far it is from where it was told to end up. For
 * the entire duration of a motion profile those are different numbers, and collapsing them into one
 * key was a real bug in an earlier revision of the design. That they are distinct — different names,
 * different publish conditions, different tiers — is asserted directly rather than assumed.
 *
 * <h2>How "was this key published" is observed</h2>
 *
 * <p>{@code Logger.recordOutput} is a no-op until {@code Logger.start()} has run, so the published
 * key set is read off the byte governor's per-key attribution instead. The governor collects
 * attribution on cycles where {@code cycle % 50 == 0}, and a freshly reset governor is on cycle
 * zero — so publishing and then closing the cycle without opening a new one lands squarely on an
 * attribution cycle and names every key that passed the tier gate.
 */
final class SchemaKeyTest {

  private LogConfig m_config;

  @BeforeEach
  void freshFacade() {
    LogState.reset();
    m_config = LogState.quietDefaults();
    LogState.install(m_config);
    FmsGate.set(false);
  }

  @AfterEach
  void restore() {
    LogState.reset();
  }

  /** Every key {@code body} actually published, from the governor's per-key attribution. */
  private Set<String> publishedKeys(Runnable body) {
    PumpkinBudget.resetForTest();
    body.run();
    PumpkinBudget.endCycle(m_config, false);
    Set<String> keys = new LinkedHashSet<>();
    for (Map.Entry<String, Integer> entry : PumpkinBudget.topKeys(Integer.MAX_VALUE)) {
      keys.add(entry.getKey());
    }
    return keys;
  }

  private static MechanismSchema elevator() {
    return MechanismSchema.declare(new ElevatorSource());
  }

  /** Stages every value the writer knows about, so publish() emits the whole block. */
  private static void stageEverything(MechanismSchema schema) {
    schema
        .goal(10.0)
        .setpoint(4.0)
        .setpointVelocity(0.5)
        .measured(1.0)
        .output(6.0)
        .atSetpoint(false)
        .atGoal(false)
        .state(Phase.MOVING)
        .controlMode(ControlMode.POSITION)
        .homed(true)
        .softLimits(0.0, 1.5)
        .currentLimitAmps(60.0)
        .simEnabled(false)
        .publish();
    schema.gains(new Gains(1, 2, 3, 4, 5, 6, 7));
    schema.extra("DeviceResetCount", 0L);
    schema.extra("FeedbackVolts", 1.0);
  }

  @Nested
  @DisplayName("Pumpkin/<Name>/ — the section 3.1 block")
  final class MechanismBlock {

    /** The whole layout, by hand. A renamed constant fails here and nowhere else. */
    @Test
    void theDeclaredKeysAreExactlyTheseInThisOrder() {
      assertEquals(
          List.of(
              "Pumpkin/Elevator/Goal",
              "Pumpkin/Elevator/Setpoint",
              "Pumpkin/Elevator/SetpointVelocity",
              "Pumpkin/Elevator/Measured",
              "Pumpkin/Elevator/Error",
              "Pumpkin/Elevator/GoalError",
              "Pumpkin/Elevator/Output",
              "Pumpkin/Elevator/AtSetpoint",
              "Pumpkin/Elevator/AtGoal",
              "Pumpkin/Elevator/State",
              "Pumpkin/Elevator/ControlMode",
              "Pumpkin/Elevator/Homed",
              "Pumpkin/Elevator/SoftLimitMin",
              "Pumpkin/Elevator/SoftLimitMax",
              "Pumpkin/Elevator/CurrentLimitAmps",
              "Pumpkin/Elevator/Gains/kP",
              "Pumpkin/Elevator/Gains/kI",
              "Pumpkin/Elevator/Gains/kD",
              "Pumpkin/Elevator/Gains/kS",
              "Pumpkin/Elevator/Gains/kV",
              "Pumpkin/Elevator/Gains/kA",
              "Pumpkin/Elevator/Gains/kG",
              "Pumpkin/Elevator/Sim/Enabled",
              "Pumpkin/Elevator/DeviceResetCount",
              "Pumpkin/Elevator/FeedbackVolts"),
          elevator().declaredKeys());
    }

    /** Inputs land one level down, and no mechanism spells the segment itself. */
    @Test
    void inputsLandUnderTheInputsSegment() {
      assertEquals("Pumpkin/Elevator/Inputs", elevator().inputsKey());
      assertEquals("Pumpkin/Elevator", elevator().prefix());
      assertEquals("Elevator", elevator().name());
    }

    /**
     * The section 1.5 audit's premise: everything declared is published, and nothing else is. If
     * these two sets ever differ, a layout is bound to a key that no longer exists — or a key exists
     * that no diff can police.
     */
    @Test
    void whatIsPublishedIsExactlyWhatWasDeclared() {
      MechanismSchema schema = elevator();

      Set<String> published = publishedKeys(() -> stageEverything(schema));

      assertEquals(
          new TreeSet<>(schema.declaredKeys()),
          new TreeSet<>(published),
          "the declared set and the published set must be the same set");
    }

    /** A value not staged this cycle is not republished from last cycle's. */
    @Test
    void onlyStagedValuesArePublished() {
      MechanismSchema schema = elevator();

      Set<String> published = publishedKeys(() -> schema.measured(1.0).homed(true).publish());

      assertEquals(Set.of("Pumpkin/Elevator/Measured", "Pumpkin/Elevator/Homed"), published);
    }
  }

  @Nested
  @DisplayName("Error and GoalError are two keys, not one")
  final class TwoErrors {

    @Test
    void theKeyNamesAreDistinct() {
      assertNotEquals(MechanismSchema.kError, MechanismSchema.kGoalError);
      assertEquals("Error", MechanismSchema.kError);
      assertEquals("GoalError", MechanismSchema.kGoalError);

      List<String> declared = elevator().declaredKeys();
      assertTrue(declared.contains("Pumpkin/Elevator/Error"));
      assertTrue(declared.contains("Pumpkin/Elevator/GoalError"));
    }

    /** {@code Error} needs Setpoint and Measured. Without a Goal, {@code GoalError} is absent. */
    @Test
    void errorIsDerivedFromSetpointAndMeasuredAlone() {
      MechanismSchema schema = elevator();

      Set<String> published = publishedKeys(() -> schema.setpoint(4.0).measured(1.0).publish());

      assertEquals(
          Set.of(
              "Pumpkin/Elevator/Setpoint",
              "Pumpkin/Elevator/Measured",
              "Pumpkin/Elevator/Error"),
          published,
          "GoalError must NOT appear: no goal was staged, and a GoalError computed against a stale"
              + " goal is a trace that looks correct and is not.");
    }

    /** {@code GoalError} needs Goal and Measured. Without a Setpoint, {@code Error} is absent. */
    @Test
    void goalErrorIsDerivedFromGoalAndMeasuredAlone() {
      MechanismSchema schema = elevator();

      Set<String> published = publishedKeys(() -> schema.goal(10.0).measured(1.0).publish());

      assertEquals(
          Set.of(
              "Pumpkin/Elevator/Goal",
              "Pumpkin/Elevator/Measured",
              "Pumpkin/Elevator/GoalError"),
          published);
    }

    /** With all three staged, both derived keys appear — five keys, not four. */
    @Test
    void bothAppearWhenGoalSetpointAndMeasuredAreAllStaged() {
      MechanismSchema schema = elevator();

      Set<String> published =
          publishedKeys(() -> schema.goal(10.0).setpoint(4.0).measured(1.0).publish());

      assertEquals(5, published.size(), published.toString());
      assertTrue(published.contains("Pumpkin/Elevator/Error"));
      assertTrue(published.contains("Pumpkin/Elevator/GoalError"));
    }

    /**
     * And they are at different tiers, which is the practical consequence of being two keys:
     * {@code Error} is one of the four traces match triage reads frame by frame and stays CRITICAL;
     * {@code GoalError} is reconstructible from Goal and Measured and is STANDARD.
     */
    @Test
    void errorIsCriticalAndGoalErrorIsStandard() {
      Map<String, Tier> tiers = tiersOf(MechanismSchema.outputSchema());
      String p = "Pumpkin/" + MechanismSchema.kNamePlaceholder + "/";

      assertEquals(Tier.CRITICAL, tiers.get(p + "Error"));
      assertEquals(Tier.STANDARD, tiers.get(p + "GoalError"));
    }

    /** With a CRITICAL minimum, GoalError drops and Error does not. */
    @Test
    void onlyErrorSurvivesACriticalMinimum() {
      LogState.install(LogState.quietDefaults().withMinimumTier(Tier.CRITICAL));
      m_config = LogConfig.defaults().withMinimumTier(Tier.CRITICAL);
      MechanismSchema schema = elevator();

      Set<String> published =
          publishedKeys(() -> schema.goal(10.0).setpoint(4.0).measured(1.0).publish());

      assertTrue(published.contains("Pumpkin/Elevator/Error"), published.toString());
      assertTrue(!published.contains("Pumpkin/Elevator/GoalError"), published.toString());
    }
  }

  @Nested
  @DisplayName("nested blocks")
  final class Nested_ {

    @Test
    void aSwerveModuleBlockNestsUnderTheDrivePrefix() {
      MechanismSchema module =
          MechanismSchema.declareNested("Pumpkin/Drive", new ModuleSource(), Tier.STANDARD);

      assertEquals("Pumpkin/Drive/Module0", module.prefix());
      assertEquals("Pumpkin/Drive/Module0/Inputs", module.inputsKey());
      assertTrue(module.declaredKeys().contains("Pumpkin/Drive/Module0/Error"));
      assertTrue(module.declaredKeys().contains("Pumpkin/Drive/Module0/GoalError"));
    }

    /**
     * A DEBUG cap is refused. It would drop the whole mechanism block whenever the FMS gate fires, so
     * Setpoint, Measured, Error and AtGoal would be absent from exactly the logs that matter.
     */
    @Test
    void aDebugCapIsRefusedByName() {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> MechanismSchema.declareNested("Pumpkin/Drive", new ModuleSource(), Tier.DEBUG));

      assertTrue(thrown.getMessage().contains("Pass CRITICAL or STANDARD"), thrown.getMessage());
    }

    @Test
    void aBlankTelemetryNameIsRefusedBecauseItBecomesAKeySegment() {
      assertThrows(
          IllegalArgumentException.class,
          () -> MechanismSchema.declare(new BlankSource()));
    }
  }

  @Nested
  @DisplayName("the fixed key blocks: Drive, Vision, Field, Health")
  final class FixedBlocks {

    @Test
    void theDriveKeysAreTheseLiterals() {
      assertEquals("Pumpkin/Drive", DriveSchema.kPrefix);
      assertEquals("Pumpkin/Drive/Pose", DriveSchema.kPose);
      assertEquals("Pumpkin/Drive/Pose3d", DriveSchema.kPose3d);
      assertEquals("Pumpkin/Drive/OdometryOnlyPose", DriveSchema.kOdometryOnlyPose);
      assertEquals("Pumpkin/Drive/VisionDivergenceMeters", DriveSchema.kVisionDivergenceMeters);
      assertEquals("Pumpkin/Drive/ChassisSpeeds/Measured", DriveSchema.kChassisSpeedsMeasured);
      assertEquals("Pumpkin/Drive/ChassisSpeeds/Setpoint", DriveSchema.kChassisSpeedsSetpoint);
      assertEquals("Pumpkin/Drive/ModuleStates/Measured", DriveSchema.kModuleStatesMeasured);
      assertEquals("Pumpkin/Drive/ModuleStates/Setpoint", DriveSchema.kModuleStatesSetpoint);
      assertEquals(
          "Pumpkin/Drive/ModuleStates/SetpointOptimized", DriveSchema.kModuleStatesSetpointOptimized);
      assertEquals("Pumpkin/Drive/ModulePositions", DriveSchema.kModulePositions);
      assertEquals("Pumpkin/Drive/Gyro/Connected", DriveSchema.kGyroConnected);
      assertEquals("Pumpkin/Drive/Gyro/YawRad", DriveSchema.kGyroYawRad);
      assertEquals("Pumpkin/Drive/Gyro/RawYawRad", DriveSchema.kGyroRawYawRad);
      assertEquals("Pumpkin/Drive/Gyro/YawRateRadPerSec", DriveSchema.kGyroYawRateRadPerSec);
      assertEquals("Pumpkin/Drive/SpeedMetersPerSec", DriveSchema.kSpeedMetersPerSec);
      assertEquals("Pumpkin/Drive/SkidRatio", DriveSchema.kSkidRatio);
      assertEquals("Pumpkin/Drive/Module0", DriveSchema.modulePrefix(0));
      assertEquals("Pumpkin/Drive/Module3", DriveSchema.modulePrefix(3));
    }

    /**
     * {@code Pose} and {@code Gyro/YawRad} are the trace triage starts from and stay CRITICAL;
     * {@code Pose3d} is the redundant 3D copy and is the one the governor may slow down.
     */
    @Test
    void poseIsCriticalAndOnlyTheRedundantKeysAreDemotable() {
      Map<String, SchemaEntry> byKey = byKey(DriveSchema.schema());

      assertEquals(Tier.CRITICAL, byKey.get(DriveSchema.kPose).tier());
      assertEquals(Demotable.NO, byKey.get(DriveSchema.kPose).demotable());
      assertEquals(Demotable.YES, byKey.get(DriveSchema.kPose3d).demotable());
      assertEquals(
          Demotable.YES, byKey.get(DriveSchema.kModuleStatesSetpointOptimized).demotable());
    }

    @Test
    void theVisionKeysAreTheseLiterals() {
      assertEquals("Pumpkin/Vision", VisionSchema.kPrefix);
      assertEquals("Pumpkin/Vision/AcceptedThisCycle", VisionSchema.kAcceptedThisCycle);
      assertEquals("Pumpkin/Vision/UptimeFraction", VisionSchema.kUptimeFraction);
      assertEquals("Pumpkin/Vision/AnyCameraOffline", VisionSchema.kAnyCameraOffline);
      assertEquals("Pumpkin/Vision/FrontLeft", VisionSchema.forCamera("FrontLeft").prefix());
      assertEquals("FrontLeft", VisionSchema.forCamera("FrontLeft").camera());
    }

    @Test
    void theFieldKeysAreTheseLiterals() {
      assertEquals("Pumpkin/Field", FieldSchema.kPrefix);
      assertEquals("Pumpkin/Field/Robot", FieldSchema.kRobot);
      assertEquals("Pumpkin/Field/RobotGhost", FieldSchema.kRobotGhost);
      assertEquals("Pumpkin/Field/Goal", FieldSchema.kGoal);
      assertEquals("Pumpkin/Field/Trajectory", FieldSchema.kTrajectory);
      assertEquals("Pumpkin/Field/VisionPoses", FieldSchema.kVisionPoses);
      assertEquals("Pumpkin/Field/VisionTargets", FieldSchema.kVisionTargets);
      assertEquals("Pumpkin/Field/GamePieces/", FieldSchema.kGamePiecesPrefix);
      assertEquals("Pumpkin/Field/ErrorMeters", FieldSchema.kErrorMeters);
      assertEquals("Pumpkin/Field/ErrorDegrees", FieldSchema.kErrorDegrees);
    }

    @Test
    void theHealthKeysAreTheseLiterals() {
      assertEquals("Pumpkin/Health", HealthSchema.kPrefix);
      assertEquals("Pumpkin/Health/Active", HealthSchema.kActive);
      assertEquals("Pumpkin/Health/Seen", HealthSchema.kSeen);
      assertEquals("Pumpkin/Health/Counts/", HealthSchema.kCountsPrefix);
      assertEquals("Pumpkin/Health/Worst", HealthSchema.kWorst);
      assertEquals("NONE", HealthSchema.kWorstNone);
    }
  }

  @Nested
  @DisplayName("the emitted document")
  final class Emission {

    @Test
    void noKeyAppearsTwiceAcrossTheWholeSchema() {
      List<String> keys = SchemaGeneration.keys();
      Set<String> unique = new LinkedHashSet<>(keys);

      assertEquals(keys.size(), unique.size(), "duplicate keys: " + duplicates(keys));
    }

    /** Every logged key lives under {@code Pumpkin/}. Provenance rows are metadata, not log keys. */
    @Test
    void everyLoggedKeyLivesUnderTheRoot() {
      Set<String> provenance = Set.copyOf(ProvenanceSchema.keys());

      for (String key : SchemaGeneration.keys()) {
        if (provenance.contains(key)) {
          continue;
        }
        assertTrue(
            key.startsWith(PumpkinLog.kRoot + "/"),
            () -> "\"" + key + "\" is neither a Pumpkin/ log key nor a provenance metadata key");
      }
    }

    @Test
    void theDocumentHasItsHeaderColumnLegendAndOneMarkerPerSection() {
      String emitted = SchemaGeneration.emit();
      List<SchemaSection> sections = SchemaGeneration.sections();

      assertTrue(emitted.startsWith(SchemaGeneration.kHeader + "\n"), emitted.substring(0, 80));
      assertTrue(emitted.contains(SchemaGeneration.kColumns));
      assertEquals("1", SchemaGeneration.kFormatVersion);

      assertEquals(
          List.of(
              "3.1 Every mechanism - Pumpkin/<Name>/ (replayable inputs)",
              "3.1 Every mechanism - Pumpkin/<Name>/ (outputs)",
              "3.1 Every mechanism - declared extras",
              "3.2 Drivetrain - Pumpkin/Drive/",
              "3.3 Vision - Pumpkin/Vision/<Camera>/",
              "3.3 Vision - roll-ups at Pumpkin/Vision/",
              "3.4 Field / ghost contract - Pumpkin/Field/",
              "3.5 Alerts and health - Pumpkin/Health/",
              "3.6 Provenance metadata (write-once, before start())"),
          sections.stream().map(SchemaSection::title).toList(),
          "the nine sections of design section 3, in order");
      for (SchemaSection section : sections) {
        assertTrue(
            emitted.contains(SchemaGeneration.kSectionMarker + section.title()), section.title());
        assertTrue(!section.entries().isEmpty(), section.title());
      }
    }

    /** Five tab-separated machine columns, then the sentence behind a {@code #}. */
    @Test
    void aRowIsFiveTabSeparatedColumnsAndAnOptionalComment() {
      SchemaEntry plain =
          SchemaEntry.of("Pumpkin/X/Y", "double", "meters", Tier.CRITICAL);
      assertEquals("Pumpkin/X/Y\tdouble\tmeters\tCRITICAL\tNO", plain.toLine());
      assertEquals(5, plain.toLine().split("\t", -1).length);

      SchemaEntry commented =
          SchemaEntry.of("Pumpkin/X/Y", "double", "meters", Tier.CRITICAL, "why");
      assertEquals("Pumpkin/X/Y\tdouble\tmeters\tCRITICAL\tNO\t# why", commented.toLine());

      SchemaEntry demotable =
          SchemaEntry.demotable("Pumpkin/X/Z", "Pose3d struct", SchemaEntry.kNoUnit, Tier.STANDARD);
      assertEquals("Pumpkin/X/Z\tPose3d struct\t-\tSTANDARD\tYES", demotable.toLine());
      assertEquals('\t', SchemaEntry.kSeparator);
      assertEquals("-", SchemaEntry.kNoUnit);
    }

    /** The fingerprint is a pure function of the document, so it is stable within a run. */
    @Test
    void theFingerprintIsSixteenHexCharactersAndDeterministic() {
      String first = SchemaGeneration.fingerprint();

      assertEquals(16, first.length(), first);
      assertTrue(first.matches("[0-9a-f]{16}"), first);
      assertEquals(first, SchemaGeneration.fingerprint());
    }

    /** Every row of the document is one of the eight sections' rows, in order. */
    @Test
    void entriesIsTheConcatenationOfTheSections() {
      List<SchemaEntry> flattened = new java.util.ArrayList<>();
      SchemaGeneration.sections().forEach(s -> flattened.addAll(s.entries()));

      assertEquals(flattened, SchemaGeneration.entries());
      assertEquals(flattened.size(), SchemaGeneration.keys().size());
    }
  }

  // ===============================================================================================

  private static Map<String, Tier> tiersOf(List<SchemaEntry> entries) {
    Map<String, Tier> out = new java.util.LinkedHashMap<>();
    entries.forEach(e -> out.put(e.key(), e.tier()));
    return out;
  }

  private static Map<String, SchemaEntry> byKey(List<SchemaEntry> entries) {
    Map<String, SchemaEntry> out = new java.util.LinkedHashMap<>();
    entries.forEach(e -> out.put(e.key(), e));
    return out;
  }

  private static Set<String> duplicates(List<String> keys) {
    Set<String> seen = new LinkedHashSet<>();
    Set<String> dupes = new LinkedHashSet<>();
    for (String key : keys) {
      if (!seen.add(key)) {
        dupes.add(key);
      }
    }
    return dupes;
  }

  /** A mechanism state enum, so {@code State} has something to publish. */
  private enum Phase {
    IDLE,
    MOVING
  }

  private record ElevatorSource() implements TelemetrySource {
    @Override
    public String telemetryName() {
      return "Elevator";
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      d.positionUnit(Units.Meters)
          .velocityUnit(Units.MetersPerSecond)
          .motorCount(2)
          .states(Phase.class)
          .extra("DeviceResetCount", Tier.CRITICAL)
          .extra("FeedbackVolts", Units.Volts, Tier.STANDARD);
    }
  }

  private record ModuleSource() implements TelemetrySource {
    @Override
    public String telemetryName() {
      return "Module0";
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      d.positionUnit(Units.Meters).velocityUnit(Units.MetersPerSecond).motorCount(2);
    }
  }

  private record BlankSource() implements TelemetrySource {
    @Override
    public String telemetryName() {
      return "   ";
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      d.motorCount(1);
    }
  }
}

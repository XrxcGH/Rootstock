package org.rootstock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.core.config.CanIdRegistry.Conflict;
import org.rootstock.core.config.CanIdRegistry.Device;
import org.rootstock.core.config.CanIdRegistry.Kind;

/**
 * {@link CanIdRegistry} — "two devices are fighting over CAN ID 22", turned into a message that
 * names both of them.
 *
 * <p>The class is stateless by design, so these tests are three hand-built records and a scan. No
 * HAL, no natives, no mechanism domain on the classpath — which is exactly the property the class
 * javadoc claims the statelessness buys.
 */
final class CanIdRegistryTest {

  private static Device device(String owner, int id) {
    return Device.of(owner, "leader", "TalonFX", id);
  }

  @Nested
  @DisplayName("a sound CAN map")
  final class NoConflicts {

    @Test
    void distinctIdsProduceNothing() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 20), device("Intake", 21)));
      assertTrue(conflicts.isEmpty(), "distinct ids must not be reported");
    }

    /**
     * The same ID on two different buses is legal and common on a robot with a CANivore. Reporting
     * it would train students to ignore this check, which is worse than not having it.
     */
    @Test
    void theSameIdOnTwoDifferentBusesIsLegal() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(
              List.of(
                  Device.on("Arm", "leader", "TalonFX", 22, "rio"),
                  Device.on("Drive/FL", "drive", "TalonFX", 22, "canivore")));
      assertTrue(conflicts.isEmpty(), "one id per bus, not one id per robot");
    }

    @Test
    void theBoundaryIdsAreLegal() {
      assertTrue(device("Low", CanIdRegistry.kMinDeviceId).idIsLegal());
      assertTrue(device("High", CanIdRegistry.kMaxDeviceId).idIsLegal());
      assertTrue(
          CanIdRegistry.scanForConflicts(
                  List.of(
                      device("Low", CanIdRegistry.kMinDeviceId),
                      device("High", CanIdRegistry.kMaxDeviceId)))
              .isEmpty());
    }

    @Test
    void nullAndEmptyInputsScanAsEmptyRatherThanThrowing() {
      assertTrue(CanIdRegistry.scanForConflicts(null).isEmpty());
      assertTrue(CanIdRegistry.scanForConflicts(List.of()).isEmpty());
      assertTrue(CanIdRegistry.toStrings(null).isEmpty());
      assertEquals("CAN map: no devices declared.", CanIdRegistry.describe(List.of()));
    }

    @Test
    void aNullElementIsSkippedRatherThanCrashingTheScan() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(Arrays.asList(device("Arm", 20), null));
      assertTrue(conflicts.isEmpty());
    }
  }

  @Nested
  @DisplayName("duplicate ids — the message must name BOTH colliding devices")
  final class Duplicates {

    @Test
    void aDuplicateIsReportedOnceWithBothDevicesAttached() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 22), device("Intake", 22)));

      assertEquals(1, conflicts.size(), "one collision, one conflict — not one per device");
      Conflict conflict = conflicts.get(0);
      assertEquals(Kind.DUPLICATE_ID, conflict.kind());
      assertEquals(22, conflict.deviceId());
      assertEquals(CanIdRegistry.kDefaultBus, conflict.bus());
      assertEquals(2, conflict.devices().size());
    }

    /** The whole point of the class: the report names the team's own names for both devices. */
    @Test
    void describeNamesBothOwnersBothRolesAndBothDeclarationSites() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(
                  List.of(
                      Device.of("Arm", "leader", "TalonFX", 22).declaredAt("RobotConfig.java:76"),
                      Device.of("Intake", "roller", "SparkMax", 22)
                          .declaredAt("RobotConfig.java:104")))
              .get(0);

      String message = conflict.describe();

      assertTrue(message.contains("\"Arm\""), message);
      assertTrue(message.contains("\"Intake\""), message);
      assertTrue(message.contains("leader"), message);
      assertTrue(message.contains("roller"), message);
      assertTrue(message.contains("TalonFX"), message);
      assertTrue(message.contains("SparkMax"), message);
      assertTrue(message.contains("RobotConfig.java:76"), message);
      assertTrue(message.contains("RobotConfig.java:104"), message);
      assertTrue(message.contains("22"), message);
      assertTrue(message.contains("BOTH"), message);
    }

    /** Naming the physical consequence and the fix is what makes this readable at 11pm. */
    @Test
    void describeNamesTheConsequenceAndTheTwoHalvesOfTheFix() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 22), device("Intake", 22))).get(0);

      String message = conflict.describe();

      assertTrue(message.contains("destroy the gearbox"), message);
      assertTrue(
          message.contains("Phoenix Tuner X") && message.contains("AND here"),
          "renumbering in the vendor tool and in the code are two separate edits, and forgetting "
              + "the second is the classic follow-up bug. Message was:\n"
              + message);
    }

    @Test
    void summaryIsAOneLinerNamingBothOwners() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 22), device("Intake", 22))).get(0);
      assertEquals("CAN ID 22 on bus \"rio\" is claimed by \"Arm\" and \"Intake\"", conflict.summary());
    }

    @Test
    void threeWayCollisionsAreReportedAsOneConflictNamingAllThree() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(
                  List.of(device("Arm", 22), device("Intake", 22), device("Climber", 22)))
              .get(0);

      assertEquals(3, conflict.devices().size());
      assertTrue(conflict.describe().contains("ALL 3 OF"), conflict.describe());
      assertTrue(conflict.summary().contains("\"Climber\""), conflict.summary());
    }

    /** Report order follows declaration order — the order the student reads their own file in. */
    @Test
    void devicesAppearInDeclarationOrder() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 22), device("Intake", 22))).get(0);
      assertEquals("Arm", conflict.devices().get(0).owner());
      assertEquals("Intake", conflict.devices().get(1).owner());
    }

    @Test
    void theDeviceListIsDefensivelyCopied() {
      Conflict conflict =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 22), device("Intake", 22))).get(0);
      assertThrows(
          UnsupportedOperationException.class, () -> conflict.devices().add(device("X", 1)));
    }
  }

  @Nested
  @DisplayName("out-of-range ids — the boot crash, collected rather than thrown")
  final class OutOfRange {

    /**
     * 9143-2025-A documents a CAN ID of 64 that crashed robot code on boot, because Phoenix device
     * IDs stop at 62. The scan must turn that into a value, not a stack trace.
     */
    @Test
    void anIdAboveSixtyTwoIsReported() {
      List<Conflict> conflicts = CanIdRegistry.scanForConflicts(List.of(device("Turret", 64)));

      assertEquals(1, conflicts.size());
      assertEquals(Kind.ID_OUT_OF_RANGE, conflicts.get(0).kind());
      assertEquals(64, conflicts.get(0).deviceId());
      assertEquals(1, conflicts.get(0).devices().size());
    }

    @Test
    void aNegativeIdIsReported() {
      assertFalse(device("Turret", -1).idIsLegal());
      assertEquals(1, CanIdRegistry.scanForConflicts(List.of(device("Turret", -1))).size());
    }

    @Test
    void describeNamesTheLegalRangeAndTheBootCrash() {
      String message =
          CanIdRegistry.scanForConflicts(List.of(device("Turret", 64))).get(0).describe();

      assertTrue(message.contains("0..62"), message);
      assertTrue(message.contains("crashes robot code on boot"), message);
      assertTrue(message.contains("\"Turret\""), message);
    }

    /**
     * Two devices both wrongly set to 64 are two separate problems and the team should see both:
     * one duplicate plus two out-of-range reports.
     */
    @Test
    void anOutOfRangeIdStillParticipatesInTheDuplicateScan() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(List.of(device("Arm", 64), device("Intake", 64)));

      assertEquals(3, conflicts.size(), "one duplicate + two out-of-range");
      assertEquals(1, conflicts.stream().filter(c -> c.kind() == Kind.DUPLICATE_ID).count());
      assertEquals(2, conflicts.stream().filter(c -> c.kind() == Kind.ID_OUT_OF_RANGE).count());
    }

    /** Duplicates first, then out-of-range — a stable order the boot dump can rely on. */
    @Test
    void duplicatesAreReportedBeforeOutOfRangeIds() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(
              List.of(device("Turret", 64), device("Arm", 22), device("Intake", 22)));

      assertEquals(Kind.DUPLICATE_ID, conflicts.get(0).kind());
      assertEquals(Kind.ID_OUT_OF_RANGE, conflicts.get(conflicts.size() - 1).kind());
    }
  }

  @Nested
  @DisplayName("the Device record")
  final class DeviceNormalisation {

    @Test
    void blankComponentsAreNormalisedRatherThanLeftEmpty() {
      Device blank = new Device("  ", "", null, 5, "  ", null);
      assertEquals("(unnamed)", blank.owner());
      assertEquals("device", blank.role());
      assertEquals("unknown device", blank.deviceType());
      assertEquals(CanIdRegistry.kDefaultBus, blank.bus());
      assertEquals(CanIdRegistry.kUnknownLocation, blank.declaredAt());
    }

    @Test
    void describeOmitsTheLocationWhenThereIsNone() {
      assertEquals("\"Arm\" leader (TalonFX)", device("Arm", 20).describe());
      assertEquals(
          "\"Arm\" leader (TalonFX) declared at RobotConfig.java:76",
          device("Arm", 20).declaredAt("RobotConfig.java:76").describe());
    }

    @Test
    void declaredAtReturnsACopyAndLeavesTheOriginalAlone() {
      Device original = device("Arm", 20);
      Device located = original.declaredAt("RobotConfig.java:76");
      assertEquals(CanIdRegistry.kUnknownLocation, original.declaredAt());
      assertEquals("RobotConfig.java:76", located.declaredAt());
    }
  }

  @Nested
  @DisplayName("rendering")
  final class Rendering {

    @Test
    void toStringsRendersOneMessagePerConflictInOrder() {
      List<Conflict> conflicts =
          CanIdRegistry.scanForConflicts(
              List.of(device("Arm", 22), device("Intake", 22), device("Turret", 64)));
      List<String> rendered = CanIdRegistry.toStrings(conflicts);

      assertEquals(conflicts.size(), rendered.size());
      for (int i = 0; i < conflicts.size(); i++) {
        assertEquals(conflicts.get(i).describe(), rendered.get(i));
      }
    }

    @Test
    void describeSummarisesACleanMapAndFlagsADirtyOne() {
      String clean = CanIdRegistry.describe(List.of(device("Arm", 20), device("Intake", 21)));
      assertTrue(clean.contains("2 device(s) on 1 bus(es)"), clean);
      assertTrue(clean.contains("no duplicate or out-of-range IDs."), clean);

      String dirty = CanIdRegistry.describe(List.of(device("Arm", 22), device("Intake", 22)));
      assertTrue(dirty.contains("PROBLEM:"), dirty);
      assertTrue(dirty.contains("claimed by \"Arm\" and \"Intake\""), dirty);
    }

    @Test
    void describeGroupsByBusAndSortsByIdWithinEachBus() {
      String description =
          CanIdRegistry.describe(
              List.of(
                  Device.on("Arm", "leader", "TalonFX", 30, "rio"),
                  Device.on("Intake", "roller", "TalonFX", 10, "rio"),
                  Device.on("Drive/FL", "drive", "TalonFX", 1, "canivore")));

      assertTrue(description.contains("2 bus(es)"), description);
      assertTrue(
          description.indexOf("\"Intake\"") < description.indexOf("\"Arm\""),
          "within a bus, devices are listed by ascending id. Was:\n" + description);
    }
  }
}

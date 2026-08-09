package org.pumpkinlib.core.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The alert budget, exercised against live {@link PumpkinAlert}s.
 *
 * <p>Tagged {@code @Tag("hal")} and therefore excluded from the default {@code ./gradlew test}: a
 * {@code PumpkinAlert} constructs an {@code edu.wpi.first.wpilibj.Alert} in its constructor, which
 * reaches NetworkTables. On a machine without the WPILib JNI natives, WPILib's native loader writes
 * to stderr and calls {@code System.exit(1)} — it does not throw — so a JVM that runs this without
 * natives dies with "non-zero exit value 1" and no indication of which test did it. Run these with
 * {@code ./gradlew halTest} on a machine with the WPILib desktop natives installed.
 *
 * <p>The policy that these behaviours are measured against — the constants, the ranking and the
 * "PIT_ONLY does not block a match" rule — is pinned natively-free in {@link AlertBudgetTest}.
 */
@Tag("hal")
final class AlertBudgetHalTest {

  private static final String kGroup = "BudgetTest";

  @BeforeEach
  void freshRegistry() {
    AlertRegistry.resetForTest();
  }

  @AfterEach
  void clearRegistry() {
    AlertRegistry.resetForTest();
  }

  /** Raises {@code count} active {@code ERROR} / {@code BLOCKS_MATCH} alerts. */
  private static void raiseBlocking(int count) {
    for (int i = 0; i < count; i++) {
      Alerts.error(kGroup, "blocking fault " + i, MatchImpact.BLOCKS_MATCH).set(true);
    }
  }

  @Test
  @DisplayName("one blocking alert makes the robot not match-ready")
  void oneBlockingAlertBlocksTheMatch() {
    raiseBlocking(1);

    assertFalse(AlertRegistry.matchReady());
    assertEquals(1, AlertRegistry.blocking().size());
    assertFalse(AlertRegistry.isOverBudget());
  }

  @Test
  @DisplayName("PIT_ONLY alerts never block a match, however many there are")
  void pitOnlyAlertsDoNotBlockAMatch() {
    for (int i = 0; i < 9; i++) {
      Alerts.warning(kGroup, "cosmetic " + i, MatchImpact.PIT_ONLY).set(true);
    }

    assertTrue(AlertRegistry.matchReady(), "nine pit warnings is still a robot that can play");
    assertEquals(9, AlertRegistry.active().size());
    assertTrue(AlertRegistry.blocking().isEmpty());
    assertFalse(AlertRegistry.isOverBudget());
  }

  @Test
  @DisplayName("the budget is exceeded at six simultaneous blocking alerts, not five")
  void theBudgetBoundaryIsExclusive() {
    raiseBlocking(AlertRegistry.kBlockingBudget);
    assertFalse(AlertRegistry.isOverBudget(), "exactly at the budget is not over it");

    raiseBlocking(1);
    assertTrue(AlertRegistry.isOverBudget());
  }

  @Test
  @DisplayName("over budget, nothing is dropped — only the driver display is capped")
  void overBudgetNothingIsDropped() {
    raiseBlocking(6);

    assertEquals(6, AlertRegistry.blocking().size(), "every alert is still retrievable");
    assertEquals(
        AlertRegistry.kDriverDisplayCap,
        AlertRegistry.driverBlockingRows().size(),
        "the driver tab is capped");
    assertEquals("+3 more - see the pit tab", AlertRegistry.driverBlockingMore());
  }

  @Test
  @DisplayName("under the cap there is no rollup row")
  void underTheCapThereIsNoRollupRow() {
    raiseBlocking(2);

    assertEquals(2, AlertRegistry.driverBlockingRows().size());
    assertEquals("", AlertRegistry.driverBlockingMore());
  }

  @Test
  @DisplayName("the diagnosis names every offender and both ways out")
  void theDiagnosisIsInformative() {
    raiseBlocking(6);

    String diagnosis = AlertRegistry.budgetDiagnosis().orElseThrow();

    assertTrue(diagnosis.contains("6 BLOCKS_MATCH alerts"), diagnosis);
    assertTrue(diagnosis.contains("budget " + AlertRegistry.kBlockingBudget), diagnosis);
    for (int i = 0; i < 6; i++) {
      assertTrue(
          diagnosis.contains("blocking fault " + i),
          "every offender must be named, not just the first three. Missing " + i + ":\n"
              + diagnosis);
    }
    assertTrue(diagnosis.contains("Nothing was dropped"), diagnosis);
    assertTrue(diagnosis.contains("MatchImpact.PIT_ONLY"), diagnosis);
    assertTrue(diagnosis.contains("expectAbsent"), diagnosis);
  }

  @Test
  @DisplayName("blocking alerts rank by severity, then by registration order")
  void blockingAlertsRankBySeverityThenRegistration() {
    PumpkinAlert firstWarning =
        Alerts.warning(kGroup, "warning A", MatchImpact.BLOCKS_MATCH).set(true);
    PumpkinAlert secondWarning =
        Alerts.warning(kGroup, "warning B", MatchImpact.BLOCKS_MATCH).set(true);
    PumpkinAlert error = Alerts.error(kGroup, "error", MatchImpact.BLOCKS_MATCH).set(true);

    List<PumpkinAlert> blocking = AlertRegistry.blocking();

    assertEquals(error, blocking.get(0), "ERROR must outrank WARNING");
    assertEquals(firstWarning, blocking.get(1), "equal severity ties break on registration order");
    assertEquals(secondWarning, blocking.get(2));
  }

  /**
   * An alert about too many blocking alerts must never itself be a blocking alert, or the budget
   * check makes the thing it is measuring worse.
   */
  @Test
  @DisplayName("the registry's own overflow alert does not cascade")
  void theOverflowAlertIsNeverItselfBlocking() {
    raiseBlocking(6);

    AlertRegistry.poll();

    assertEquals(6, AlertRegistry.blocking().size(), "the overflow alert must not join the count");
    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(a -> a.isActive() && a.impact() == MatchImpact.PIT_ONLY),
        "the overflow alert should have been raised as PIT_ONLY");
  }

  @Test
  @DisplayName("clearing the offenders clears the overflow alert too")
  void theOverflowAlertClearsWhenTheRobotComesBackUnderBudget() {
    raiseBlocking(6);
    AlertRegistry.poll();
    assertTrue(AlertRegistry.isOverBudget());

    AlertRegistry.all().forEach(a -> a.set(false));
    AlertRegistry.poll();

    assertFalse(AlertRegistry.isOverBudget());
    assertTrue(AlertRegistry.budgetDiagnosis().isEmpty());
    assertTrue(AlertRegistry.matchReady());
  }

  @Test
  @DisplayName("an inactive alert counts for nothing")
  void inactiveAlertsAreNotCounted() {
    Alerts.error(kGroup, "not raised", MatchImpact.BLOCKS_MATCH);

    assertEquals(1, AlertRegistry.size(), "it is registered");
    assertTrue(AlertRegistry.active().isEmpty(), "but it is not active");
    assertTrue(AlertRegistry.matchReady());
  }

  @Test
  @DisplayName("the summary reflects the live state")
  void theSummaryReflectsTheLiveState() {
    raiseBlocking(2);
    Alerts.warning(kGroup, "cosmetic", MatchImpact.PIT_ONLY).set(true);

    AlertRegistry.Summary summary = AlertRegistry.summary();

    assertFalse(summary.matchReady());
    assertEquals(3, summary.activeCount());
    assertEquals(2, summary.blockingCount());
    assertEquals(Severity.ERROR, summary.worst().orElseThrow());
    assertTrue(summary.describe().contains("NOT READY"), summary.describe());
  }
}

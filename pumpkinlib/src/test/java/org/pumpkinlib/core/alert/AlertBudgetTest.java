package org.pumpkinlib.core.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The alert budget: {@link AlertRegistry#kBlockingBudget} simultaneous {@code BLOCKS_MATCH} alerts,
 * and what happens when a robot exceeds it.
 *
 * <p><strong>What a budget is for.</strong> Every {@code BLOCKS_MATCH} alert claims the robot cannot
 * play a match. If nine of them are active at once then none of them means anything, the driver
 * stops reading the tab, and the one that mattered is the one nobody saw. The budget is the point at
 * which the library stops trusting its own alerts and says so.
 *
 * <p><strong>Degrading informatively is the whole design.</strong> Over budget, the registry does
 * <em>not</em> drop alerts — {@link AlertRegistry#blocking()} still returns all of them. What it
 * does is cap the <em>driver</em> display at {@link AlertRegistry#kDriverDisplayCap} rows, add a
 * rollup row saying how many are hidden, and produce a diagnosis that names every offender and both
 * ways out. Silently truncating would be the failure mode this exists to prevent.
 *
 * <p><strong>This class is native-free by construction.</strong> Nothing here builds a {@code
 * PumpkinAlert} — a {@code PumpkinAlert} eagerly constructs an {@code edu.wpi.first.wpilibj.Alert},
 * which touches NetworkTables, and on a JVM without WPILib's JNI natives WPILib's loader calls
 * {@code System.exit(1)} rather than throwing, killing the test JVM with no diagnostic. The
 * behaviour that needs live alerts is in {@code AlertBudgetHalTest}, tagged {@code @Tag("hal")};
 * what is pinned here is the policy those alerts are measured against, which is the part that is
 * quoted in the driver tab, the pit printout and the CI failure message and therefore must not
 * drift between them.
 */
final class AlertBudgetTest {

  @BeforeEach
  void freshRegistry() {
    AlertRegistry.resetForTest();
  }

  @AfterEach
  void clearRegistry() {
    AlertRegistry.resetForTest();
  }

  @Nested
  @DisplayName("the budget constants")
  final class Constants {

    @Test
    void theBudgetIsFiveSimultaneousBlockingAlerts() {
      assertEquals(5, AlertRegistry.kBlockingBudget);
    }

    @Test
    void theDriverSeesAtMostThreeRows() {
      assertEquals(3, AlertRegistry.kDriverDisplayCap);
    }

    /**
     * The display cap must be strictly below the budget. If a robot could hit the budget without the
     * driver tab ever overflowing, the rollup row would never appear and the cap would be silent
     * truncation instead of a visible degradation.
     */
    @Test
    void theDisplayCapIsStrictlyBelowTheBudget() {
      assertTrue(
          AlertRegistry.kDriverDisplayCap < AlertRegistry.kBlockingBudget,
          "a cap at or above the budget would truncate silently: the robot would be over budget "
              + "before the driver ever saw a '+N more' row.");
    }
  }

  @Nested
  @DisplayName("a robot with nothing raised")
  final class Nominal {

    @Test
    void anEmptyRegistryIsMatchReadyAndUnderBudget() {
      assertTrue(AlertRegistry.matchReady());
      assertFalse(AlertRegistry.isOverBudget());
      assertEquals(Optional.empty(), AlertRegistry.worst());
      assertEquals(0, AlertRegistry.size());
      assertTrue(AlertRegistry.all().isEmpty());
      assertTrue(AlertRegistry.active().isEmpty());
      assertTrue(AlertRegistry.blocking().isEmpty());
    }

    @Test
    void theDriverTabIsEmptyAndThereIsNoRollupRow() {
      assertTrue(AlertRegistry.driverBlockingRows().isEmpty());
      assertEquals("", AlertRegistry.driverBlockingMore());
    }

    /** No diagnosis when there is nothing to diagnose — an empty budget report is noise. */
    @Test
    void thereIsNoBudgetDiagnosisWhenTheBudgetIsNotExceeded() {
      assertEquals(Optional.empty(), AlertRegistry.budgetDiagnosis());
    }

    @Test
    void pollingAnEmptyRegistryIsHarmless() {
      AlertRegistry.poll();
      AlertRegistry.poll();

      assertTrue(AlertRegistry.matchReady());
      assertEquals(0, AlertRegistry.size());
    }

    @Test
    void theSummaryReportsReady() {
      AlertRegistry.Summary summary = AlertRegistry.summary();

      assertTrue(summary.matchReady());
      assertEquals(0, summary.activeCount());
      assertEquals(0, summary.blockingCount());
      assertEquals(Optional.empty(), summary.worst());
      assertTrue(summary.describe().contains("READY"), summary.describe());
    }

    @Test
    void describeSaysNothingIsActiveRatherThanPrintingAnEmptyTable() {
      assertTrue(AlertRegistry.describe().contains("none active"), AlertRegistry.describe());
    }
  }

  @Nested
  @DisplayName("severity ranking — what 'worst' means")
  final class SeverityOrdering {

    /**
     * {@code blocking()} ranks by severity descending, so the natural order of {@link Severity} is
     * load bearing: if {@code ERROR} did not sort above {@code WARNING}, the three rows the driver
     * sees would be the three least important ones.
     */
    @Test
    void errorOutranksWarningWhichOutranksInfo() {
      assertTrue(Severity.ERROR.compareTo(Severity.WARNING) > 0);
      assertTrue(Severity.WARNING.compareTo(Severity.INFO) > 0);
    }

    @Test
    void atLeastIsInclusive() {
      assertTrue(Severity.ERROR.atLeast(Severity.ERROR));
      assertTrue(Severity.ERROR.atLeast(Severity.WARNING));
      assertFalse(Severity.INFO.atLeast(Severity.WARNING));
    }

    @Test
    void maxPicksTheMoreSevere() {
      assertEquals(Severity.ERROR, Severity.max(Severity.INFO, Severity.ERROR));
      assertEquals(Severity.ERROR, Severity.max(Severity.ERROR, Severity.INFO));
      assertEquals(Severity.WARNING, Severity.max(Severity.WARNING, Severity.WARNING));
    }

    /** The mapping onto WPILib's own alert type must round-trip, or the dashboard colour lies. */
    @Test
    void severityRoundTripsThroughTheWpiAlertType() {
      for (Severity severity : Severity.values()) {
        assertEquals(severity, Severity.from(severity.toWpi()));
      }
    }
  }

  @Nested
  @DisplayName("match impact — 'can this robot play' is not 'is everything perfect'")
  final class Impact {

    /**
     * A robot with nine {@code PIT_ONLY} warnings and no blocking alerts is ready and the LEDs go
     * green, because that is the honest answer and an honest answer is what makes the signal
     * trusted.
     */
    @Test
    void thereAreExactlyTwoImpactsAndOnlyOneOfThemBlocksAMatch() {
      assertEquals(2, MatchImpact.values().length);
      assertEquals(MatchImpact.BLOCKS_MATCH, MatchImpact.valueOf("BLOCKS_MATCH"));
      assertEquals(MatchImpact.PIT_ONLY, MatchImpact.valueOf("PIT_ONLY"));
    }
  }

  @Nested
  @DisplayName("the notification bridge is disarmed until something arms it")
  final class Bridge {

    @Test
    void theBridgeStartsWithNoSinkAndDropsNothingBecauseNothingWasSent() {
      AlertBridge.resetForTest();

      assertFalse(AlertBridge.hasSink());
      assertFalse(AlertBridge.isEnabled());
      assertEquals(0L, AlertBridge.delivered());
      assertEquals(0L, AlertBridge.dropped());
      assertEquals(Optional.empty(), AlertBridge.lastDelivered());
    }

    @Test
    void aSinkCanBeInstalledAndRemovedWithoutTouchingHardware() {
      AlertBridge.resetForTest();

      AlertBridge.setSink((level, title, detail) -> { });
      assertTrue(AlertBridge.hasSink());

      AlertBridge.clearSink();
      assertFalse(AlertBridge.hasSink());

      AlertBridge.resetForTest();
    }
  }
}

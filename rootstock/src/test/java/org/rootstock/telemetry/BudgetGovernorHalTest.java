package org.rootstock.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.spi.LogConfig;

/**
 * The two governor branches that raise alerts, and therefore need WPILib's JNI natives: the demotion
 * itself, and the refusal of an ineligible key.
 *
 * <p>Tagged {@code hal} for the reason the whole library splits its alert tests: constructing a
 * {@code RootstockAlert} builds a WPILib {@code Alert}, which touches NetworkTables, and on a JVM
 * without the natives WPILib's loader calls {@code System.exit(1)} instead of throwing. Run with
 * {@code ./gradlew halTest}.
 *
 * <p>{@code BudgetGovernorTest} pins everything either side of these branches — the byte arithmetic,
 * the p95 window, the suppression pattern, the whole input invariant, and the threshold from below
 * (forty-nine over-budget cycles change nothing).
 */
@Tag("hal")
final class BudgetGovernorHalTest {

  /** One demotable key costing 25 doubles: 200 B of payload plus one 11 B header = 211 B. */
  private static final String kFatKey = "Rootstock/Hal/Fat";

  private static final double[] kFatValue = new double[25];
  private static final int kFatBytes = 8 * 25 + RootstockBudget.kHeaderBytes;
  private static final double kBudget = 100.0;

  private LogConfig m_config;

  @BeforeEach
  void freshBudget() {
    LogState.reset();
    AlertRegistry.resetForTest();
    m_config = LogState.quietDefaults().withPerCycleByteBudget(kBudget);
    LogState.install(m_config);
    FmsGate.set(false);
  }

  @AfterEach
  void restore() {
    AlertRegistry.resetForTest();
    LogState.reset();
  }

  private void publishFatCycle() {
    RootstockBudget.beginCycle();
    RootstockLog.log(kFatKey, kFatValue, Units.Meters, Demotable.YES);
    RootstockBudget.endCycle(m_config, false);
  }

  @Test
  @DisplayName("the fiftieth consecutive over-budget cycle demotes exactly one key")
  void theFiftiethOverBudgetCycleDemotesOneKeyAndSaysWhy() {
    assertEquals(211, kFatBytes, "25 doubles at 8 B each, plus one 11 B record header");

    for (int i = 0; i < RootstockBudget.kOverBudgetCycles; i++) {
      publishFatCycle();
    }

    assertEquals(kFatBytes, RootstockBudget.p95CycleBytes());
    assertEquals(List.of(kFatKey), RootstockBudget.demotedKeys());
    assertEquals(1L, RootstockBudget.schemaGeneration(), "a demotion is a schema generation bump");
    assertEquals(
        "p95=211B > budget=100B for 50 cycles; demoted " + kFatKey,
        RootstockBudget.governorReason());
    assertTrue(RootstockBudget.lastChangeSeconds() >= 0.0);
  }

  @Test
  @DisplayName("a demoted key then publishes on one cycle in five, and is never deleted")
  void afterDemotionThePatternIsOneInFive() {
    for (int i = 0; i < RootstockBudget.kOverBudgetCycles; i++) {
      publishFatCycle();
    }
    assertEquals(List.of(kFatKey), RootstockBudget.demotedKeys());

    int published = 0;
    for (int i = 0; i < 10; i++) {
      publishFatCycle();
      if (RootstockBudget.lastCycleFacadeBytes() > 0) {
        published++;
      }
    }

    assertEquals(2, published, "10 cycles at one in five, not zero — demotion is never deletion");
  }

  @Test
  @DisplayName("with the demotable set exhausted the governor stops rather than cutting other keys")
  void anExhaustedGovernorEscalatesInsteadOfCuttingSomethingElse() {
    for (int i = 0; i < RootstockBudget.kOverBudgetCycles; i++) {
      publishFatCycle();
    }
    long generationAfterFirstDemotion = RootstockBudget.schemaGeneration();

    // The only demotable key is already demoted, and the log is still over budget. Fifty more
    // cycles must not find something else to cut.
    for (int i = 0; i < 2 * RootstockBudget.kOverBudgetCycles; i++) {
      RootstockBudget.beginCycle();
      RootstockLog.log(kFatKey, kFatValue, Units.Meters, Demotable.YES);
      RootstockLog.log("Rootstock/Hal/Unmarked", kFatValue, Units.Meters);
      RootstockBudget.endCycle(m_config, false);
    }

    assertEquals(List.of(kFatKey), RootstockBudget.demotedKeys(), "nothing else was touched");
    assertEquals(
        generationAfterFirstDemotion,
        RootstockBudget.schemaGeneration(),
        "escalating to an error is not a schema change");
    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(a -> a.text().contains("LOG_BUDGET_EXHAUSTED")),
        "the team is told to delete log calls, not given a quietly mutilated log");
  }

  @Test
  @DisplayName("an input key passed Demotable.YES is refused, loudly and by name")
  void markingAReplayedInputDemotableIsRefusedWithANamedAlert() {
    String inputKey = "Rootstock/Elevator/Inputs/TempCelsius";

    RootstockBudget.beginCycle();
    RootstockLog.log(inputKey, 42.0, Demotable.YES);
    RootstockBudget.endCycle(m_config, false);

    assertTrue(
        RootstockBudget.demotableKeys().isEmpty(),
        "a replayed input must never enter the demotable set");
    assertEquals(java.util.Set.of(inputKey), RootstockBudget.rejectedDemotions());
    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(
                a ->
                    a.text().contains("DEMOTABLE_REFUSED")
                        && a.text().contains(inputKey)
                        && a.text().contains("byte-identical replay guarantee")),
        "a silently-refused marking looks exactly like a marking that worked");
  }

  @Test
  @DisplayName("a driver-mirror key passed Demotable.YES is refused for its own reason")
  void markingADriverTopicDemotableIsRefusedToo() {
    String driverKey = "Rootstock/Driver/Ready";

    RootstockBudget.beginCycle();
    RootstockLog.log(driverKey, true, Demotable.YES);
    RootstockBudget.endCycle(m_config, false);

    assertTrue(RootstockBudget.demotableKeys().isEmpty());
    assertEquals(java.util.Set.of(driverKey), RootstockBudget.rejectedDemotions());
    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(
                a ->
                    a.text().contains("DEMOTABLE_REFUSED")
                        && a.text().contains("a human is reading it while the robot is moving")),
        "the driver mirror is exempt from the governor entirely, not merely unmarked");
  }
}

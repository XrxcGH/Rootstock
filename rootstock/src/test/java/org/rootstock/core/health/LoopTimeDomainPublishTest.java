package org.rootstock.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.builtin.LoopTimeMonitor;

/**
 * {@code /Rootstock/Loop/Domain/} has to exist.
 *
 * <p>The over-budget alert tells a student to open that subtable, and {@code LoopTimeMonitor}'s
 * class javadoc calls it the library's whole answer to "is Rootstock causing my overruns?". For a
 * long time nothing published it: the topic was named in three javadoc comments and one alert
 * string, and in no publisher. That is a bad failure to ship, because it costs a student the one
 * step they were told to take, at the moment they are already stuck.
 *
 * <p>{@code @Tag("hal")}, because a publisher reaches NetworkTables and the round-robin's recovery
 * path builds a {@code RootstockAlert}, which builds a WPILib {@code Alert}.
 */
@Tag("hal")
final class LoopTimeDomainPublishTest {

  private LoopTimeMonitor m_monitor;

  @BeforeEach
  void freshEverything() {
    Clock.resetForTest();
    RootstockTracer.resetForTest();
    SliceScheduler.resetForTest();
    HealthMonitor.resetForTest();
    AlertRegistry.resetForTest();
    m_monitor = LoopTimeMonitor.create();
    m_monitor.register();
  }

  @AfterEach
  void tearDown() {
    HealthMonitor.resetForTest();
    SliceScheduler.resetForTest();
    AlertRegistry.resetForTest();
    RootstockTracer.resetForTest();
    Clock.resetForTest();
  }

  @Test
  @DisplayName("every declared domain gets a key, from the first loop, before it is ever measured")
  void everyDeclaredDomainGetsAKey() {
    tick();

    List<String> topics = m_monitor.publishedDomainTopics();
    assertTrue(
        topics.contains(LoopTimeMonitor.kDomainTopicPrefix + "Health" + "Ms"),
        "declared-but-unmeasured budgets must publish a zero, not be absent: " + topics);
    assertTrue(
        topics.contains(LoopTimeMonitor.kDomainTopicPrefix + "Telemetry" + "Ms"),
        topics.toString());
    assertEquals(
        RootstockTracer.sections().size(),
        topics.size(),
        "one key per tracer section, no more and no fewer: " + topics);
  }

  @Test
  @DisplayName("the subtable the over-budget alert names is the subtable that is published")
  void theAlertTextNamesTheSubtableThatExists() {
    tick();

    // The alert says "Check /Rootstock/Loop/Domain/ to see which domain is spending the time."
    // Nothing enforced that the prefix in that sentence was the prefix anything published, and for
    // a while it was not: RootstockTracer's own javadoc spells the same topic "Rootstock/Perf/".
    for (String topic : m_monitor.publishedDomainTopics()) {
      assertTrue(
          topic.startsWith(LoopTimeMonitor.kDomainTopicPrefix),
          "published under an unexpected prefix: " + topic);
    }
    assertTrue(
        m_monitor.publishedDomainTopics().size() > 0,
        "the alert sends a student here, so it cannot be empty");
  }

  @Test
  @DisplayName("Mechanism is a domain with a declared budget, so an overrun there can be named")
  void mechanismIsABudgetedDomain() {
    tick();

    assertTrue(
        RootstockTracer.sections().contains(LoopTimeMonitor.kMechanismSection),
        "Mechanism.periodic() is the most expensive thing the library runs; without a section it "
            + "cannot be attributed. Sections were: "
            + RootstockTracer.sections());
    assertNotEquals(
        0.0,
        RootstockTracer.budgetOf(LoopTimeMonitor.kMechanismSection).magnitude(),
        "an unbudgeted section can never breach, so it never reaches the named-fault path");
    assertTrue(
        m_monitor.publishedDomainTopics().contains(mechanismTopic()),
        m_monitor.publishedDomainTopics().toString());
  }

  @Test
  @DisplayName("a measured section reaches NetworkTables, not just the tracer")
  void aMeasuredSectionReachesNetworkTables() {
    // Two loops: the value published on loop N is the value the tracer rolled at the start of it.
    try (var s = LoopTimeMonitor.section(LoopTimeMonitor.kMechanismSection)) {
      spin();
    }
    tick();
    tick();

    double published =
        NetworkTableInstance.getDefault().getDoubleTopic(mechanismTopic()).subscribe(-1.0).get();
    assertTrue(published >= 0.0, "the key must exist and carry a number, got " + published);
  }

  private static String mechanismTopic() {
    return LoopTimeMonitor.kDomainTopicPrefix
        + LoopTimeMonitor.kMechanismSection
        + LoopTimeMonitor.kDomainTopicSuffix;
  }

  private static void tick() {
    Clock.tick();
    RootstockTracer.reset();
    SliceScheduler.tick();
  }

  private static void spin() {
    long end = System.nanoTime() + 200_000L;
    while (System.nanoTime() < end) {
      // Burn a measurable span so the section has something to report.
    }
  }
}

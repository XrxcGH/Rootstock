package org.rootstock.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.health.SliceScheduler;
import org.rootstock.core.spi.Tier;

/**
 * A smoke test for {@link RootstockTest} itself — the JUnit 5 extension the library <em>ships</em> to
 * teams, as opposed to the tests the library runs on itself.
 *
 * <p>Tagged {@code @Tag("hal")} because that is what {@link RootstockTest#headless()} requires and
 * what its own javadoc instructs: it calls {@code HAL.initialize(500, 0)}, and on a machine with no
 * WPILib JNI natives that fails. The extension is the harness that makes every <em>team's</em>
 * mechanism test possible, so it needs at least one test proving the tick order it documents is the
 * tick order it performs.
 *
 * <p>Run with {@code ./gradlew halTest} on a machine with the WPILib desktop natives installed.
 */
@Tag("hal")
final class RootstockTestHarnessTest {

  @RegisterExtension final RootstockTest m_robot = RootstockTest.headless();

  @Test
  @DisplayName("the library's cycle counter advances exactly once per step, from zero")
  void stepAdvancesTheCycleCounterExactlyOncePerLoop() {
    assertEquals(0L, Clock.cycle(), "beforeEach must reset the counter to zero");

    m_robot.step(5);

    assertEquals(5L, Clock.cycle(), "double-ticking would make every rate gate fire at 2x");
  }

  @Test
  @DisplayName("time advances by exactly one period per step")
  void timeAdvancesByOnePeriodPerLoop() {
    double before = Clock.seconds();

    m_robot.step(10);

    assertEquals(10 * RootstockTest.kPeriodSeconds, Clock.seconds() - before, 1e-6);
  }

  @Test
  @DisplayName("stepSeconds rounds up to whole cycles")
  void stepSecondsRoundsUp() {
    m_robot.stepSeconds(0.05);

    assertEquals(3L, Clock.cycle(), "0.05 s is 2.5 loops; a test asking for 0.05 s must get 0.06 s");
  }

  @Test
  @DisplayName("registered periodics run at the top of every cycle, in registration order")
  void registeredPeriodicsRunInOrderEveryCycle() {
    List<String> log = new ArrayList<>();
    m_robot.ticking(() -> log.add("first"), () -> log.add("second"));

    m_robot.step(2);

    assertEquals(List.of("first", "second", "first", "second"), log);
  }

  @Test
  @DisplayName("the library's own per-loop work runs inside step()")
  void theLibrarysPerLoopWorkRunsInsideStep() {
    List<String> log = new ArrayList<>();
    SliceScheduler.register("Test/Slice", () -> log.add("sliced"));

    m_robot.step(3);

    assertEquals(3, log.size(), "step() must tick the slice scheduler, as robotPeriodic() does");
  }

  @Test
  @DisplayName("runUntil stops as soon as the condition holds")
  void runUntilStopsEarly() {
    boolean reached = m_robot.runUntil(() -> Clock.cycle() >= 3, 1.0);

    assertTrue(reached);
    assertEquals(3L, Clock.cycle(), "it must not keep stepping after the condition held");
  }

  @Test
  @DisplayName("runUntil reports failure rather than hanging")
  void runUntilTimesOutHonestly() {
    assertTrue(!m_robot.runUntil(() -> false, 0.1), "an impossible condition must return false");
  }

  @Test
  @DisplayName("nominal operation raises no alerts")
  void nominalOperationRaisesNothing() {
    m_robot.step(10);

    assertEquals(
        List.of(),
        m_robot.raisedAlerts(),
        "a harness that silently raises the same warning every run is a harness lying to you");
  }

  @Test
  @DisplayName("the tier gate is recorded so telemetry can read it when it lands")
  void theTierGateIsRecorded() {
    assertEquals(Tier.DEBUG, m_robot.minimumTier());

    m_robot.withMinimumTier(Tier.CRITICAL);

    assertEquals(Tier.CRITICAL, m_robot.minimumTier());
  }

  @Test
  @DisplayName("managed resources are returned unchanged so they read as assignments")
  void managingReturnsTheResource() {
    AutoCloseable resource = () -> { };

    assertEquals(resource, m_robot.managing(resource));
  }
}

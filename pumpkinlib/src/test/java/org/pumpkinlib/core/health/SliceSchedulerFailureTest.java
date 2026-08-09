package org.pumpkinlib.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.alert.AlertRegistry;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.diag.PumpkinTracer;

/**
 * A health slice that throws must never take the robot loop with it.
 *
 * <p>This is the central promise of the health package — <em>a diagnostic never becomes the
 * outage</em> — and it is the one part of {@link SliceScheduler} that cannot be tested without
 * WPILib's natives: the recovery path raises a {@code PumpkinAlert}, which constructs an {@code
 * edu.wpi.first.wpilibj.Alert}, which reaches NetworkTables. Hence {@code @Tag("hal")} and {@code
 * ./gradlew halTest}. Everything about the scheduler that does not throw is pinned natively-free in
 * {@link SliceSchedulerTest}.
 */
@Tag("hal")
final class SliceSchedulerFailureTest {

  @BeforeEach
  void freshScheduler() {
    Clock.resetForTest();
    PumpkinTracer.resetForTest();
    SliceScheduler.resetForTest();
    AlertRegistry.resetForTest();
  }

  @AfterEach
  void tearDown() {
    SliceScheduler.resetForTest();
    AlertRegistry.resetForTest();
    PumpkinTracer.resetForTest();
    Clock.resetForTest();
  }

  @Test
  @DisplayName("a throwing slice is caught, and the rotation carries on")
  void aThrowingSliceDoesNotStopTheRotation() {
    List<String> log = new ArrayList<>();
    SliceScheduler.register(
        "Health/Bad",
        () -> {
          throw new IllegalStateException("sensor unplugged");
        });
    SliceScheduler.register("Health/Good", () -> log.add("good"));

    for (int i = 0; i < 4; i++) {
      SliceScheduler.tick();
    }

    assertEquals(2, log.size(), "the healthy slice must still have run on its two turns");
  }

  @Test
  @DisplayName("failures are counted per slice")
  void failuresAreCountedPerSlice() {
    SliceScheduler.register(
        "Health/Bad",
        () -> {
          throw new IllegalStateException("sensor unplugged");
        });
    SliceScheduler.register("Health/Good", () -> { });

    for (int i = 0; i < 6; i++) {
      SliceScheduler.tick();
    }

    assertEquals(3, SliceScheduler.failureCount("Health/Bad"));
    assertEquals(0, SliceScheduler.failureCount("Health/Good"));
  }

  @Test
  @DisplayName("the failure is reported as a PIT_ONLY warning naming the slice and the exception")
  void theFailureIsReportedInformatively() {
    SliceScheduler.register(
        "Health/Bad",
        () -> {
          throw new IllegalStateException("sensor unplugged");
        });

    SliceScheduler.tick();

    assertTrue(
        AlertRegistry.active().stream()
            .anyMatch(
                a ->
                    a.impact() == MatchImpact.PIT_ONLY
                        && a.text().contains("Health/Bad")
                        && a.text().contains("IllegalStateException")
                        && a.text().contains("sensor unplugged")),
        "the alert must name the slice, the exception type and the message, and must not itself "
            + "block a match. Active alerts were: "
            + AlertRegistry.describe());
    assertTrue(
        AlertRegistry.matchReady(),
        "a broken health check is a pit problem, not a reason to forfeit a match");
  }

  @Test
  @DisplayName("describe() names the failing slice and its count")
  void describeNamesTheFailingSlice() {
    SliceScheduler.register(
        "Health/Bad",
        () -> {
          throw new IllegalStateException("boom");
        });

    SliceScheduler.tick();
    SliceScheduler.tick();

    String description = SliceScheduler.describe();
    assertTrue(description.contains("Health/Bad"), description);
    assertTrue(description.contains("2 failures"), description);
  }
}

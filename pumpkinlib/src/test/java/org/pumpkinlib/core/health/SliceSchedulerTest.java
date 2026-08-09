package org.pumpkinlib.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.diag.PumpkinTracer;
import org.pumpkinlib.testsupport.NtStubs;

/**
 * {@link SliceScheduler} — the one round-robin, and the two bugs it exists to kill.
 *
 * <p><strong>Exactly one registered slice runs per robot loop.</strong> That is the entire
 * scheduling policy and it is what these tests pin. The alternative — every health check on its own
 * 4 Hz wall-clock gate — does not make the work cheaper, it makes it lumpy: ~10 ms of fault checking
 * arriving in one loop out of twelve, on a 20 ms budget. Loop overruns that appear once every 250 ms
 * correlate with nothing visible and are the worst kind to debug.
 *
 * <p><strong>It counts cycles and never reads a clock.</strong> {@link #theRotationAdvancesWhileTimeStandsStill()}
 * is the test of that: the installed time source is frozen for the whole run and the rotation still
 * advances. A wall-clocked gate fires on different cycles when the same log is replayed at 50x, so
 * replayed health output would not match what was recorded and every replay diff would be an
 * artifact of the harness.
 *
 * <p><strong>Two seams are needed to run this without WPILib natives</strong>, and both are load
 * bearing rather than incidental:
 *
 * <ul>
 *   <li>a deterministic {@link Clock} source, because {@code tick()} times each slice through {@link
 *       PumpkinTracer}, which reads {@code Clock.nowMicros()};
 *   <li>{@link NtStubs}, because {@code tick()} publishes the sweep length to NetworkTables and
 *       WPILib's native loader calls {@code System.exit(1)} rather than throwing when the natives
 *       are absent — see that class for the full explanation.
 * </ul>
 *
 * <p>Deliberately <em>not</em> here: the "a slice that throws is caught and counted" path. It is
 * real behaviour and it is covered in {@code SliceSchedulerFailureTest}, tagged {@code @Tag("hal")},
 * because the catch block raises a {@code PumpkinAlert}, which constructs an {@code
 * edu.wpi.first.wpilibj.Alert}, which needs NetworkTables.
 */
final class SliceSchedulerTest {

  /** Frozen. Nothing in this class advances it; the scheduler must not care. */
  private static final double kFrozenSeconds = 7.5;

  @BeforeEach
  void headlessScheduler() {
    Clock.resetForTest();
    Clock.setSource(() -> kFrozenSeconds);
    PumpkinTracer.resetForTest();
    SliceScheduler.resetForTest();
    NtStubs.install(SliceScheduler.class, "m_sweepPublisher", "m_lastSlicePublisher");
  }

  @AfterEach
  void tearDown() {
    SliceScheduler.resetForTest();
    NtStubs.clear(SliceScheduler.class, "m_sweepPublisher", "m_lastSlicePublisher");
    PumpkinTracer.resetForTest();
    Clock.resetForTest();
  }

  /** Registers a slice that appends {@code label} to {@code log} each time it runs. */
  private static void recording(String name, String label, List<String> log) {
    SliceScheduler.register(name, () -> log.add(label));
  }

  @Nested
  @DisplayName("the scheduling policy — one slice per loop, in registration order")
  final class RoundRobin {

    @Test
    void exactlyOneSliceRunsPerTick() {
      List<String> log = new ArrayList<>();
      recording("A", "A", log);
      recording("B", "B", log);
      recording("C", "C", log);

      SliceScheduler.tick();

      assertEquals(1, log.size(), "one loop must cost exactly one slice, never three");
    }

    @Test
    void slicesRunInRegistrationOrderAndWrapAround() {
      List<String> log = new ArrayList<>();
      recording("A", "A", log);
      recording("B", "B", log);
      recording("C", "C", log);

      for (int i = 0; i < 7; i++) {
        SliceScheduler.tick();
      }

      assertEquals(List.of("A", "B", "C", "A", "B", "C", "A"), log);
    }

    /**
     * The property the replay contract rests on: the rotation is a function of the cycle counter
     * alone. Time is frozen for this entire test and the rotation still advances.
     */
    @Test
    void theRotationAdvancesWhileTimeStandsStill() {
      List<String> log = new ArrayList<>();
      recording("A", "A", log);
      recording("B", "B", log);

      double before = Clock.seconds();
      for (int i = 0; i < 4; i++) {
        SliceScheduler.tick();
      }

      assertEquals(before, Clock.seconds(), 0.0, "the time source was frozen for this test");
      assertEquals(List.of("A", "B", "A", "B"), log);
    }

    /** The scheduler owns its own counter; it must not be coupled to the robot loop counter. */
    @Test
    void theSchedulerCounterIsIndependentOfTheClockCycleCounter() {
      recording("A", "A", new ArrayList<>());

      SliceScheduler.tick();
      SliceScheduler.tick();

      assertEquals(2L, SliceScheduler.cycle());
      assertEquals(0L, Clock.cycle(), "SliceScheduler must not advance the robot loop counter");
    }

    @Test
    void lastSliceNamesWhatJustRan() {
      recording("A", "A", new ArrayList<>());
      recording("B", "B", new ArrayList<>());

      assertEquals("", SliceScheduler.lastSlice(), "nothing has run yet");
      SliceScheduler.tick();
      assertEquals("A", SliceScheduler.lastSlice());
      SliceScheduler.tick();
      assertEquals("B", SliceScheduler.lastSlice());
    }

    @Test
    void tickingWithNoSlicesRegisteredIsHarmless() {
      SliceScheduler.tick();
      SliceScheduler.tick();

      assertEquals("", SliceScheduler.lastSlice());
      assertEquals(2L, SliceScheduler.ticks());
      assertEquals(0, SliceScheduler.sweepCycles());
    }
  }

  @Nested
  @DisplayName("the budget — cost is bounded, latency is what grows")
  final class Budget {

    /**
     * Registering a twelfth slice lengthens the sweep; it does not raise the per-loop cost. The cost
     * of "one more check" is latency, not overruns, which is the right thing to trade.
     */
    @Test
    void addingSlicesLengthensTheSweepAndNeverThePerLoopCost() {
      List<String> log = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        recording("S" + i, "S" + i, log);
      }
      assertEquals(12, SliceScheduler.sweepCycles());

      for (int i = 0; i < 12; i++) {
        int before = log.size();
        SliceScheduler.tick();
        assertEquals(before + 1, log.size(), "loop " + i + " must run exactly one slice");
      }

      assertEquals(12, log.size(), "a full sweep visits each slice exactly once");
      assertEquals(12, log.stream().distinct().count());
    }

    @Test
    void sweepCyclesIsTheNumberOfLoopsBetweenTwoRunsOfTheSameSlice() {
      List<String> log = new ArrayList<>();
      recording("A", "A", log);
      recording("B", "B", log);
      recording("C", "C", log);

      int sweep = SliceScheduler.sweepCycles();
      for (int i = 0; i < sweep + 1; i++) {
        SliceScheduler.tick();
      }

      assertEquals(3, sweep);
      assertEquals("A", log.get(0));
      assertEquals("A", log.get(sweep), "the same slice must come round after exactly one sweep");
    }

    @Test
    void ticksCountsEveryCallIncludingOnesWithNothingToRun() {
      SliceScheduler.tick();
      recording("A", "A", new ArrayList<>());
      SliceScheduler.tick();

      assertEquals(2L, SliceScheduler.ticks());
      assertEquals(1L, SliceScheduler.cycle(), "only the loop that dispatched a slice counts here");
    }
  }

  @Nested
  @DisplayName("registration")
  final class Registration {

    /**
     * Idempotent by name: a subsystem constructed twice in a test must not silently double the sweep
     * length, which would halve the rate of every other check on the robot.
     */
    @Test
    void reRegisteringANameReplacesTheWorkAndDoesNotLengthenTheSweep() {
      List<String> log = new ArrayList<>();
      SliceScheduler.register("A", () -> log.add("first"));
      SliceScheduler.register("A", () -> log.add("second"));

      assertEquals(1, SliceScheduler.sweepCycles());
      SliceScheduler.tick();
      assertEquals(List.of("second"), log);
    }

    @Test
    void slicesReportsRegistrationOrderAndIsUnmodifiable() {
      recording("Health/Arm", "a", new ArrayList<>());
      recording("Alerts", "b", new ArrayList<>());
      recording("Match", "c", new ArrayList<>());

      assertEquals(List.of("Health/Arm", "Alerts", "Match"), SliceScheduler.slices());
      assertThrows(
          UnsupportedOperationException.class, () -> SliceScheduler.slices().add("sneaky"));
    }

    @Test
    void unregisterRemovesASliceAndShortensTheSweep() {
      recording("A", "A", new ArrayList<>());
      recording("B", "B", new ArrayList<>());

      assertTrue(SliceScheduler.unregister("A"));
      assertEquals(1, SliceScheduler.sweepCycles());
      assertEquals(List.of("B"), SliceScheduler.slices());
      assertFalse(SliceScheduler.unregister("A"), "removing it twice is not a removal");
    }

    @Test
    void registerRejectsABlankNameAndSaysWhyTheNameMatters() {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class, () -> SliceScheduler.register("  ", () -> {}));
      assertTrue(
          e.getMessage().contains("replay contract"),
          "the name is the replay contract and the tracer budget key. Was:\n" + e.getMessage());
      assertThrows(IllegalArgumentException.class, () -> SliceScheduler.register(null, () -> {}));
    }

    @Test
    void registerRejectsANullSliceAndNamesIt() {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class, () -> SliceScheduler.register("Health/Arm", null));
      assertTrue(e.getMessage().contains("Health/Arm"), e.getMessage());
    }

    @Test
    void resetForTestDropsEverything() {
      recording("A", "A", new ArrayList<>());
      SliceScheduler.tick();

      SliceScheduler.resetForTest();

      assertEquals(0, SliceScheduler.sweepCycles());
      assertEquals(0L, SliceScheduler.cycle());
      assertEquals(0L, SliceScheduler.ticks());
      assertEquals("", SliceScheduler.lastSlice());
      // resetForTest() closed the stubs; put them back for the @AfterEach tick-free teardown.
      NtStubs.install(SliceScheduler.class, "m_sweepPublisher", "m_lastSlicePublisher");
    }
  }

  @Nested
  @DisplayName("every-loop work — the small, justified exception to the rotation")
  final class EveryLoop {

    /**
     * Reserved for things whose value is edge detection. Every registration here is a permanent
     * per-loop tax, which is why it is a separate method rather than a flag on {@code register}.
     */
    @Test
    void everyLoopWorkRunsOnEveryTickWhileSlicesTakeTurns() {
      List<String> log = new ArrayList<>();
      SliceScheduler.registerEveryLoop("Rumble", () -> log.add("rumble"));
      recording("A", "A", log);
      recording("B", "B", log);

      for (int i = 0; i < 4; i++) {
        SliceScheduler.tick();
      }

      assertEquals(4, log.stream().filter("rumble"::equals).count());
      assertEquals(2, log.stream().filter("A"::equals).count());
      assertEquals(2, log.stream().filter("B"::equals).count());
    }

    @Test
    void everyLoopWorkRunsBeforeTheLoopsOneSlice() {
      List<String> log = new ArrayList<>();
      SliceScheduler.registerEveryLoop("Rumble", () -> log.add("rumble"));
      recording("A", "A", log);

      SliceScheduler.tick();

      assertEquals(List.of("rumble", "A"), log);
    }

    @Test
    void everyLoopWorkIsNotInTheRotation() {
      SliceScheduler.registerEveryLoop("Rumble", () -> {});
      recording("A", "A", new ArrayList<>());

      assertEquals(1, SliceScheduler.sweepCycles(), "every-loop work must not lengthen the sweep");
      assertEquals(List.of("Rumble"), SliceScheduler.everyLoopSlices());
      assertEquals(List.of("A"), SliceScheduler.slices());
    }

    @Test
    void everyLoopWorkRunsEvenWhenNoSlicesAreRegistered() {
      List<String> log = new ArrayList<>();
      SliceScheduler.registerEveryLoop("Rumble", () -> log.add("rumble"));

      SliceScheduler.tick();

      assertEquals(List.of("rumble"), log);
    }

    @Test
    void unregisterAlsoRemovesEveryLoopWork() {
      SliceScheduler.registerEveryLoop("Rumble", () -> {});
      assertTrue(SliceScheduler.unregister("Rumble"));
      assertTrue(SliceScheduler.everyLoopSlices().isEmpty());
    }

    @Test
    void registerEveryLoopRejectsBlankNamesAndNullWork() {
      assertThrows(
          IllegalArgumentException.class, () -> SliceScheduler.registerEveryLoop("", () -> {}));
      assertThrows(
          IllegalArgumentException.class, () -> SliceScheduler.registerEveryLoop("Rumble", null));
    }
  }

  @Nested
  @DisplayName("describe() — the boot dump")
  final class Describe {

    @Test
    void describeListsTheRotationInOrderWithItsSweepLength() {
      recording("Health/Arm", "a", new ArrayList<>());
      recording("Alerts", "b", new ArrayList<>());
      SliceScheduler.registerEveryLoop("Rumble", () -> {});

      String description = SliceScheduler.describe();

      assertTrue(description.contains("2 slices (sweep = 2 cycles)"), description);
      assertTrue(description.contains("[0] Health/Arm"), description);
      assertTrue(description.contains("[1] Alerts"), description);
      assertTrue(description.contains("[every loop] Rumble"), description);
    }
  }
}

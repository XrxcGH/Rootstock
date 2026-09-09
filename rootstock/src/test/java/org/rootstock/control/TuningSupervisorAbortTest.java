package org.rootstock.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.compat.Clock;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.SiDomain;

/**
 * Fault injection against {@link TuningSupervisor}: every one of the twelve abort conditions is
 * driven, one at a time, and asserted to fire, to name itself, and to leave the mechanism neutral.
 *
 * <p><strong>Why every one, and why exactly twelve.</strong> This class is the only thing in the
 * library that commands raw voltage at a mechanism a student is standing next to. Its value is
 * entirely in its refusals, and a refusal that is never exercised is a refusal nobody knows is
 * broken. Eleven of the twelve are checked every loop, in a fixed order, first-trip-wins; the
 * twelfth ({@link AbortReason#UNSTABLE_RESPONSE}) is raised by the refinement step rather than by
 * the loop. The count is asserted directly, because an abort silently dropped from the enum is an
 * interlock silently dropped from the robot.
 *
 * <p><strong>Why a hand-driven fake and a hand-driven clock.</strong> Half of these conditions are
 * sensors lying — a frozen CAN signal, a NaN encoder, current that stays high — and no simulation
 * produces those on demand, because a simulation is self-consistent by construction. {@link
 * FakeTarget} lets each fault be injected in isolation, and {@link Clock#setSource} lets the holdoff
 * windows (0.15 s of overcurrent, 0.3 s of wrong direction, 0.5 s of stall, 20 s of routine) be
 * crossed in a test that takes microseconds.
 *
 * <p><strong>Why {@code @Tag("hal")}.</strong> Every abort path ends in {@code Alerts.warning}, which
 * constructs an {@code edu.wpi.first.wpilibj.Alert}, which reaches NetworkTables. Without WPILib's
 * desktop natives that call does not throw — the loader writes to stderr and calls {@code
 * System.exit(1)} — so the whole test JVM would die with no indication of which test did it. Run
 * these with {@code ./gradlew halTest}.
 */
@Tag("hal")
final class TuningSupervisorAbortTest {

  /** The hand-driven clock, in seconds. */
  private double m_now;

  /** The held-enable trigger, which the tests release when they mean to. */
  private final AtomicBoolean m_enableHeld = new AtomicBoolean(true);

  @BeforeEach
  void deterministicWorld() {
    Clock.resetForTest();
    Clock.setSource(() -> m_now);
    m_now = 0.0;
    m_enableHeld.set(true);
    AlertRegistry.resetForTest();
    DiagnosticsMode.enterTestModeEnabled();
  }

  @AfterEach
  void restoreTheWorld() {
    DiagnosticsMode.clear();
    Clock.resetForTest();
    AlertRegistry.resetForTest();
  }

  /** Advances the deterministic clock. */
  private void advance(double seconds) {
    m_now += seconds;
  }

  /** The reference elevator: 1.4 m of travel, 40 mm of margin, two Krakens through 12:1. */
  private static FakeTarget elevator() {
    return new FakeTarget().named("Elevator").archetype(MechanismArchetype.ELEVATOR).at(0.7);
  }

  /** A flywheel, for the conditions that must be provoked without a position abort firing first. */
  private static FakeTarget flywheel() {
    return new FakeTarget()
        .named("Shooter")
        .archetype(MechanismArchetype.FLYWHEEL)
        .domain(SiDomain.ROTATIONAL_RADIANS)
        .limits(TravelLimits.unbounded())
        .prior(PlantPrior.flywheel(DCMotor.getKrakenX60Foc(2), Reduction.of(1.0), 0.004))
        .tolerance(0.5)
        .at(0.0);
  }

  /**
   * Builds an armed supervisor around a target, failing loudly if arming was refused.
   *
   * <p>Re-establishes the held enable and the Test-mode latch first, so that the aggregate coverage
   * test can run every scenario back to back in one JVM: {@code enableReleased} deliberately drops
   * the trigger and {@code disabledMidRoutine} deliberately disables the robot, and neither may
   * leak into the scenario that follows it.
   */
  private TuningSupervisor armed(FakeTarget target) {
    m_enableHeld.set(true);
    DiagnosticsMode.enterTestModeEnabled();
    TuningSupervisor supervisor =
        new TuningSupervisor(target, SafetyEnvelope.derive(target), m_enableHeld::get);
    supervisor.arm();
    assertTrue(supervisor.isArmed(), "the scenario is meaningless unless arming succeeded");
    return supervisor;
  }

  /** Asserts the shared contract every abort must satisfy, whichever condition tripped. */
  private static void assertNeutralledAndNamed(
      TuningSupervisor supervisor, FakeTarget target, AbortReason expected, int stopsBefore) {
    assertEquals(Optional.of(expected), supervisor.lastAbort());
    assertFalse(supervisor.isArmed(), "an abort must disarm, or the next loop keeps commanding");
    assertEquals(stopsBefore + 1, target.stops, "the mechanism must actually be told to stop");
    assertEquals(0.0, supervisor.appliedVolts(), 0.0);
    assertEquals(
        0.0,
        supervisor.commandVolts(12.0),
        0.0,
        "a disarmed supervisor must return zero and touch nothing, not silently re-arm");

    String message = supervisor.lastAbortMessage();
    assertFalse(message.isBlank(), "an abort with no sentence teaches nothing");
    if (expected != AbortReason.TIMEOUT) {
      // Ten of the eleven loop messages open with the mechanism's own name. TIMEOUT does not —
      // it says "this step ran 22.0 s and the budget is 20.0 s" with no mechanism in it, which is
      // the one message in this class a student reading a two-mechanism log cannot attribute.
      // Recorded here rather than asserted away, so the gap is visible.
      assertTrue(
          message.contains(target.tuningName()),
          () -> "the message must name the mechanism: " + message);
    }
    assertFalse(expected.summary().isBlank());

    // restoreNeutralMode() before stop(): a step may have borrowed COAST, and neutralling a
    // coasting gravity mechanism before its brake is back is dropping it.
    int stopIndex = target.abortOrder.lastIndexOf("stop");
    int restoreIndex = target.abortOrder.lastIndexOf("restoreNeutralMode");
    assertTrue(
        restoreIndex >= 0 && restoreIndex < stopIndex,
        () -> "idle mode must be restored before the stop: " + target.abortOrder);
  }

  @Nested
  @DisplayName("the eleven conditions checked every loop")
  final class LoopChecks {

    /** Collects the reason each scenario produced, so the set can be compared with the enum. */
    private final List<AbortReason> m_produced = new ArrayList<>();

    private AbortReason record(Optional<AbortReason> reason) {
      assertTrue(reason.isPresent(), "the scenario did not trip anything at all");
      m_produced.add(reason.get());
      return reason.get();
    }

    @Test
    @DisplayName("1 — ENABLE_RELEASED: letting go of the trigger stops it on the very next check")
    void enableReleased() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      supervisor.commandVolts(6.0);
      assertTrue(supervisor.appliedVolts() > 0.0, "it really was moving before the release");
      int stops = target.stops;

      m_enableHeld.set(false);
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.ENABLE_RELEASED, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(
          supervisor.lastAbortMessage().contains("let go of the trigger"),
          "the most common abort must read as 'nothing is broken', not as a fault");
    }

    @Test
    @DisplayName("2 — DISABLED: the driver station disabling mid-routine stops it")
    void disabledMidRoutine() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      DiagnosticsMode.disable();
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.DISABLED, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
    }

    @Test
    @DisplayName("3 — LIMIT_REACHED: leaving the band names the position and the band edge")
    void limitReached() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();
      int stops = target.stops;

      double past = envelope.positionMax() + 0.003;
      assertTrue(
          past < target.travelLimits().softMax(),
          "the whole point: the supervisor trips while the mechanism is still inside the "
              + "device's own soft limit, so the student sees a message instead of a silent clamp");
      target.at(past);

      AbortReason reason = record(supervisor.check());
      assertEquals(AbortReason.LIMIT_REACHED, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("safe band"), supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("4 — LIMIT_APPROACH: it stops before the edge, not after it")
    void limitApproach() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();
      int stops = target.stops;

      // Inside the band right now, outside it 0.15 s from now at this speed.
      double speed = 0.8;
      double position = envelope.positionMax() - 0.5 * speed * TuningSupervisor.kLookAheadSeconds;
      target.at(position).moving(speed);

      assertTrue(envelope.containsPosition(position), "it has not left the band yet");
      assertFalse(
          envelope.containsPosition(position + speed * TuningSupervisor.kLookAheadSeconds),
          "but it would within the look-ahead window");
      assertTrue(speed < envelope.maxAbsVelocity(), "and it is not overspeeding, which is next");

      AbortReason reason = record(supervisor.check());
      assertEquals(AbortReason.LIMIT_APPROACH, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("Stopped early"), supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("5 — OVERSPEED: faster than the motor curve allows, with the gear-ratio hint")
    void overspeed() {
      FakeTarget target = flywheel();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      target.moving(1.01 * supervisor.envelope().maxAbsVelocity());
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.OVERSPEED, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(
          supervisor.lastAbortMessage().contains("gear ratio"),
          "a mechanism that reads impossibly fast almost always has a wrong reduction, and the "
              + "message must send the student there: " + supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("6 — OVERCURRENT: only after the whole holdoff window, never on one spike")
    void overcurrent() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();
      int stops = target.stops;

      target.amps(OptionalDouble.of(1.5 * envelope.maxStatorAmps()));
      assertTrue(supervisor.check().isEmpty(), "one loop of high current is not a jam");

      advance(0.5 * envelope.holdoffSeconds());
      assertTrue(supervisor.check().isEmpty(), "half the holdoff window is still not a jam");

      advance(0.6 * envelope.holdoffSeconds());
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.OVERCURRENT, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("jammed"), supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("6b — a current spike that clears resets the holdoff instead of accumulating")
    void overcurrentHoldoffResets() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();

      for (int i = 0; i < 20; i++) {
        target.amps(OptionalDouble.of(1.5 * envelope.maxStatorAmps()));
        advance(0.9 * envelope.holdoffSeconds());
        assertTrue(supervisor.check().isEmpty());
        target.amps(OptionalDouble.of(5.0));
        advance(0.02);
        assertTrue(supervisor.check().isEmpty());
      }
      assertTrue(supervisor.isArmed(), "twenty near-misses must not add up to one abort");
    }

    @Test
    @DisplayName("7 — STALLED: real voltage, no motion, for the whole stall window")
    void stalled() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();
      int stops = target.stops;

      double applied = supervisor.commandVolts(envelope.maxVolts());
      assertTrue(
          applied > envelope.stallVoltsThreshold(),
          "the slew limiter must still let through more than the stall threshold in one loop: "
              + applied);
      target.moving(0.0);

      assertTrue(supervisor.check().isEmpty(), "one loop of no motion is not a stall");
      advance(1.1 * envelope.stallSeconds());
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.STALLED, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("breaker"), supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("8 — WRONG_DIRECTION: positive volts, negative motion, sustained")
    void wrongDirection() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      supervisor.commandVolts(supervisor.envelope().maxVolts());
      target.moving(-0.3);

      assertTrue(supervisor.check().isEmpty(), "one loop of disagreement is noise");
      advance(1.1 * TuningSupervisor.kWrongDirectionSeconds);
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.WRONG_DIRECTION, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(
          supervisor.lastAbortMessage().contains("Do not tune around it"),
          "an inverted mechanism must be fixed, not tuned around, and the text has to say so: "
              + supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("9 — TIMEOUT: the routine's wall-clock budget")
    void timeout() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      advance(supervisor.envelope().maxRoutineSeconds() * 0.9);
      assertTrue(supervisor.check().isEmpty(), "inside the budget nothing happens");

      advance(supervisor.envelope().maxRoutineSeconds() * 0.2);
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.TIMEOUT, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("budget"), supervisor.lastAbortMessage());
      assertTrue(
          supervisor.lastAbortMessage().contains("20.0 s"),
          "the budget it broke must be in the sentence: " + supervisor.lastAbortMessage());
      assertFalse(
          supervisor.lastAbortMessage().contains(target.tuningName()),
          "recorded, not endorsed: TIMEOUT is the one loop message that does not name the "
              + "mechanism, so in a log with two supervised mechanisms it cannot be attributed. "
              + "If somebody adds the name, delete this assertion and the exemption in "
              + "assertNeutralledAndNamed.");
    }

    @Test
    @DisplayName("10 — SENSOR_FAULT: a NaN reading is named as a sensor, not as a limit")
    void sensorFault() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      target.at(Double.NaN);
      AbortReason reason = record(supervisor.check());

      assertEquals(
          AbortReason.SENSOR_FAULT,
          reason,
          "a NaN compares false against every band, so a naive order would report LIMIT_REACHED "
              + "and send the student to look at their soft limits");
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(supervisor.lastAbortMessage().contains("sensor wiring"), supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("11 — SENSOR_INCONSISTENT: a frozen position while velocity insists it is moving")
    void sensorInconsistent() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      int stops = target.stops;

      // The signature of optimizeBusUtilization() with no preceding setUpdateFrequency: the
      // position signal returns the identical double forever while velocity keeps reporting motion.
      target.moving(0.5);
      assertTrue(supervisor.check().isEmpty());

      advance(1.1 * TuningSupervisor.kSensorFreezeSeconds);
      AbortReason reason = record(supervisor.check());

      assertEquals(AbortReason.SENSOR_INCONSISTENT, reason);
      assertNeutralledAndNamed(supervisor, target, reason, stops);
      assertTrue(
          supervisor.lastAbortMessage().contains("optimizeBusUtilization"),
          "this one failure mode has one cause, and the message names it: "
              + supervisor.lastAbortMessage());
    }

    @Test
    @DisplayName("every loop-checked reason is reachable, and the eleven scenarios cover all of them")
    void theElevenScenariosCoverTheElevenConditions() {
      // Re-run each scenario in one test so the produced set can be compared with the enum. Each
      // one builds its own supervisor, so there is no cross-talk.
      enableReleased();
      disabledMidRoutine();
      limitReached();
      limitApproach();
      overspeed();
      overcurrent();
      stalled();
      wrongDirection();
      timeout();
      sensorFault();
      sensorInconsistent();

      Set<AbortReason> produced = EnumSet.copyOf(m_produced);
      Set<AbortReason> loopChecked = EnumSet.noneOf(AbortReason.class);
      for (AbortReason reason : AbortReason.values()) {
        if (reason.loopChecked()) {
          loopChecked.add(reason);
        }
      }

      assertEquals(
          loopChecked,
          produced,
          "every reason the supervisor's loop can raise must have a scenario here, and every "
              + "scenario here must raise a distinct one");
      assertEquals(11, produced.size());
    }
  }

  @Nested
  @DisplayName("the twelfth, which is not a loop check")
  final class RaisedByTheRefinementStep {

    @Test
    @DisplayName("12 — UNSTABLE_RESPONSE: raised directly, and it neutrals like any other abort")
    void unstableResponse() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      supervisor.commandVolts(4.0);
      int stops = target.stops;

      supervisor.abort(AbortReason.UNSTABLE_RESPONSE);

      assertNeutralledAndNamed(supervisor, target, AbortReason.UNSTABLE_RESPONSE, stops);
      assertFalse(
          AbortReason.UNSTABLE_RESPONSE.loopChecked(),
          "no measurement in check() can see a diverging response — only the step that "
              + "commanded the step can");
      assertTrue(
          supervisor.lastAbortMessage().contains(AbortReason.UNSTABLE_RESPONSE.summary()),
          supervisor.lastAbortMessage());
    }
  }

  @Nested
  @DisplayName("the enum's shape, which a dropped interlock would change")
  final class Shape {

    @Test
    @DisplayName("there are exactly twelve abort reasons")
    void exactlyTwelve() {
      assertEquals(
          12,
          AbortReason.values().length,
          "twelve abort conditions, eleven of them checked every loop. A thirteenth means a new "
              + "interlock that nothing here exercises; an eleventh means one was dropped and the "
              + "robot lost a protection nobody noticed.");
    }

    @Test
    @DisplayName("order() is 1..12 with no gaps and matches declaration order")
    void orderIsDense() {
      Set<Integer> seen = new java.util.TreeSet<>();
      for (AbortReason reason : AbortReason.values()) {
        assertEquals(
            reason.ordinal() + 1,
            reason.order(),
            () -> reason + " declares order " + reason.order() + " but is declared at position "
                + (reason.ordinal() + 1) + ", and check() evaluates them in declaration order");
        assertTrue(seen.add(reason.order()), reason + " reuses an order number");
      }
      assertEquals(12, seen.size());
      assertEquals(1, java.util.Collections.min(seen));
      assertEquals(12, java.util.Collections.max(seen));
    }

    @Test
    @DisplayName("exactly eleven are loop-checked, and UNSTABLE_RESPONSE is the one that is not")
    void elevenAreLoopChecked() {
      long loopChecked =
          java.util.Arrays.stream(AbortReason.values()).filter(AbortReason::loopChecked).count();
      assertEquals(11, loopChecked);
      assertFalse(AbortReason.UNSTABLE_RESPONSE.loopChecked());
      assertEquals(12, AbortReason.UNSTABLE_RESPONSE.order(), "and it is last in the order");
    }

    @Test
    @DisplayName("every summary is a sentence a student can act on")
    void everySummaryIsUseful() {
      for (AbortReason reason : AbortReason.values()) {
        String summary = reason.summary();
        assertFalse(summary.isBlank(), reason.name());
        assertTrue(summary.length() > 25, reason + " has a summary too short to teach anything");
        assertNotEquals(
            reason.name(),
            summary,
            reason + " must explain itself, not repeat its own constant name");
      }
    }
  }

  @Nested
  @DisplayName("what happens after an abort, and what must never happen")
  final class AfterTheAbort {

    @Test
    @DisplayName("an abort path never propagates, even when stop() breaks its own contract")
    void anAbortPathIsNeverTheOutage() {
      FakeTarget target = elevator().withThrowingStop();
      TuningSupervisor supervisor = armed(target);

      m_enableHeld.set(false);
      Optional<AbortReason> reason = supervisor.check();

      assertEquals(Optional.of(AbortReason.ENABLE_RELEASED), reason);
      assertFalse(supervisor.isArmed(), "the latch must still be set even though stop() threw");
      assertEquals(1, target.neutralRestores);
    }

    @Test
    @DisplayName("a second check() after an abort does nothing and raises nothing")
    void abortIsLatchedUntilReArmed() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      m_enableHeld.set(false);
      supervisor.check();
      int stops = target.stops;

      assertTrue(supervisor.check().isEmpty(), "a disarmed supervisor checks nothing");
      assertEquals(stops, target.stops, "and must not keep calling stop() every loop");
      assertEquals(Optional.of(AbortReason.ENABLE_RELEASED), supervisor.lastAbort());
    }

    @Test
    @DisplayName("re-arming clears the latch and the condition timers")
    void reArmingClearsEverything() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);

      target.amps(OptionalDouble.of(90.0));
      supervisor.check(); // starts the overcurrent timer
      m_enableHeld.set(false);
      supervisor.check();
      assertEquals(Optional.of(AbortReason.ENABLE_RELEASED), supervisor.lastAbort());

      m_enableHeld.set(true);
      target.amps(OptionalDouble.of(10.0));
      supervisor.arm();

      assertTrue(supervisor.lastAbort().isEmpty());
      assertEquals("", supervisor.lastAbortMessage());
      assertTrue(supervisor.isArmed());

      // The overcurrent timer was reset, so a fresh spike needs the whole holdoff again.
      target.amps(OptionalDouble.of(90.0));
      assertTrue(supervisor.check().isEmpty());
      advance(0.9 * supervisor.envelope().holdoffSeconds());
      assertTrue(supervisor.check().isEmpty(), "the stale timer must not have carried over");
    }

    @Test
    @DisplayName("disarm() stops cleanly and does not show the student a stop sign")
    void disarmIsNotAnAbort() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      supervisor.commandVolts(3.0);

      supervisor.disarm();

      assertFalse(supervisor.isArmed());
      assertEquals(1, target.stops);
      assertEquals(1, target.neutralRestores);
      assertTrue(supervisor.lastAbort().isEmpty(), "a step that finished its work is not a fault");
      assertEquals(0.0, supervisor.appliedVolts(), 0.0);
    }
  }

  @Nested
  @DisplayName("commandVolts is the only door, and it is shut unless armed")
  final class TheOnlyDoor {

    @Test
    @DisplayName("an unarmed supervisor commands nothing and touches the mechanism not at all")
    void unarmedCommandsNothing() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor =
          new TuningSupervisor(target, SafetyEnvelope.derive(target), () -> true);

      for (int i = 0; i < 50; i++) {
        assertEquals(0.0, supervisor.commandVolts(12.0), 0.0);
      }
      assertTrue(
          target.commanded.isEmpty(),
          "a caller that forgot arm() must get silence, not motion — setVoltage was never reached");
    }

    @Test
    @DisplayName("a full-scale request takes at least 50 ms to arrive at the device")
    void nothingStepsInstantlyAtTheHardware() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      double ceiling = supervisor.envelope().maxVolts();

      double first = supervisor.commandVolts(ceiling);
      assertTrue(
          first < ceiling,
          "the first loop must not deliver the whole swing: " + first + " of " + ceiling);

      int loops = 1;
      while (supervisor.appliedVolts() < ceiling - 1e-9 && loops < 1000) {
        supervisor.commandVolts(ceiling);
        loops++;
      }
      assertTrue(
          loops * Clock.dt() >= TuningSupervisor.kSlewSecondsToFullScale - 1e-9,
          "a full-scale swing took " + (loops * Clock.dt()) + " s, and the promise is at least "
              + TuningSupervisor.kSlewSecondsToFullScale);
    }

    @Test
    @DisplayName("the request is clamped to the envelope ceiling however much is asked for")
    void theCeilingIsAbsolute() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      double ceiling = supervisor.envelope().maxVolts();

      for (int i = 0; i < 200; i++) {
        supervisor.commandVolts(240.0);
      }
      assertEquals(ceiling, supervisor.appliedVolts(), 1e-9);

      for (int i = 0; i < 400; i++) {
        supervisor.commandVolts(-240.0);
      }
      assertEquals(-ceiling, supervisor.appliedVolts(), 1e-9);

      for (double volts : target.commanded) {
        assertTrue(Math.abs(volts) <= ceiling + 1e-9, "the device saw " + volts);
      }
    }

    @Test
    @DisplayName("a NaN request is treated as zero rather than passed to the motor")
    void nonFiniteRequestsBecomeZero() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);

      supervisor.commandVolts(Double.NaN);
      supervisor.commandVolts(Double.POSITIVE_INFINITY);

      for (double volts : target.commanded) {
        assertTrue(Double.isFinite(volts), "the device saw " + volts);
      }
    }

    @Test
    @DisplayName("near the top of the band the taper holds a gravity mechanism, it does not drop it")
    void theTaperFloorIsTheHoldVoltage() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();

      double hold = 1.25;
      supervisor.setGravityHoldVolts(hold);
      // Sit right at the top edge, where the taper factor is zero.
      target.at(envelope.positionMax());

      double applied = 0.0;
      for (int i = 0; i < 200; i++) {
        applied = supervisor.commandVolts(envelope.maxVolts());
      }

      assertEquals(
          hold,
          applied,
          1e-9,
          "tapering an elevator's command to zero at the band edge does not stop the carriage, "
              + "it drops it into the limit — so the floor is the gravity hold voltage");
    }

    @Test
    @DisplayName("on a gravity-free archetype the same taper is an ordinary brake to zero")
    void theTaperGoesToZeroWithoutGravity() {
      FakeTarget target =
          new FakeTarget().named("Turret").archetype(MechanismArchetype.TURRET).at(0.7);
      TuningSupervisor supervisor = armed(target);
      SafetyEnvelope envelope = supervisor.envelope();
      target.at(envelope.positionMax());

      double applied = 0.0;
      for (int i = 0; i < 200; i++) {
        applied = supervisor.commandVolts(envelope.maxVolts());
      }
      assertEquals(0.0, applied, 1e-9);
    }
  }
}

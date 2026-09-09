package org.rootstock.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.compat.Clock;

/**
 * The dead-man's switch, and the three driver-station facts that gate it.
 *
 * <p><strong>What is being defended.</strong> A tuning routine commands raw voltage at a mechanism
 * with a person standing beside it. The only interlock that person controls directly is the trigger
 * they are holding, so it has to be the <em>first</em> condition evaluated on every loop — before
 * limits, before current, before anything — and letting go has to stop the mechanism on the very
 * next cycle, not at the end of the step. Everything else in the supervisor protects the robot; this
 * protects the person.
 *
 * <p><strong>And why Test mode is a hard throw rather than a warning.</strong> {@code arm()} is the
 * one place in the library where voltage becomes authorised. Making the preconditions collectable
 * would mean a mis-wired caller could receive an unarmed supervisor and conclude nothing was wrong.
 * It throws instead, and the wizard — its only caller — catches, publishes the sentence verbatim and
 * refuses, so nothing propagates into {@code robotPeriodic()}.
 *
 * <p>{@code @Tag("hal")}: {@code arm()} and every abort path build {@code RootstockAlert}s, which
 * reach NetworkTables. See {@link TuningSupervisorAbortTest} for why that is fatal without natives.
 */
@Tag("hal")
final class HeldEnableTest {

  private double m_now;

  private final AtomicBoolean m_held = new AtomicBoolean(true);

  @BeforeEach
  void deterministicWorld() {
    Clock.resetForTest();
    Clock.setSource(() -> m_now);
    m_now = 0.0;
    m_held.set(true);
    AlertRegistry.resetForTest();
    DiagnosticsMode.enterTestModeEnabled();
  }

  @AfterEach
  void restoreTheWorld() {
    DiagnosticsMode.clear();
    Clock.resetForTest();
    AlertRegistry.resetForTest();
  }

  private static FakeTarget elevator() {
    return new FakeTarget().named("Elevator").archetype(MechanismArchetype.ELEVATOR).at(0.7);
  }

  private TuningSupervisor supervisorFor(FakeTarget target) {
    return new TuningSupervisor(target, SafetyEnvelope.derive(target), m_held::get);
  }

  @Nested
  @DisplayName("the trigger")
  final class TheTrigger {

    @Test
    @DisplayName("a derived envelope always requires a held enable — it is not opt-in")
    void derivedEnvelopesRequireIt() {
      assertTrue(
          SafetyEnvelope.derive(elevator()).requireHeldEnable(),
          "if this were ever derived as false, every routine in the library would run with no "
              + "dead-man's switch and nothing would say so");
    }

    @Test
    @DisplayName("releasing the trigger stops the mechanism on the very next check")
    void releasingStopsItImmediately() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();

      supervisor.commandVolts(6.0);
      assertTrue(supervisor.appliedVolts() > 0.0);

      m_held.set(false);
      assertEquals(Optional.of(AbortReason.ENABLE_RELEASED), supervisor.check());

      assertFalse(supervisor.isArmed());
      assertEquals(1, target.stops);
      assertEquals(0.0, supervisor.appliedVolts(), 0.0);
    }

    @Test
    @DisplayName("a sweep cannot continue while nobody is holding it")
    void aSweepCannotContinueUnheld() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();

      int releaseLoop = 20;
      int loops = 200;
      int lastLoopWithVoltage = -1;

      for (int i = 0; i < loops; i++) {
        if (i == releaseLoop) {
          m_held.set(false);
        }
        if (supervisor.check().isPresent()) {
          // Aborted. A real sweep keeps running its command loop for the rest of the step; the
          // point is that the command loop now cannot produce voltage.
        }
        double applied = supervisor.commandVolts(4.0);
        if (Math.abs(applied) > 0.0) {
          lastLoopWithVoltage = i;
        }
        m_now += Clock.dt();
      }

      assertTrue(
          lastLoopWithVoltage < releaseLoop,
          "voltage was still reaching the device at loop "
              + lastLoopWithVoltage
              + ", after the trigger was released at loop "
              + releaseLoop);
      assertEquals(
          1,
          target.stops,
          "and the mechanism was stopped exactly once, not once per loop for 180 loops");

      for (int i = 0; i < target.commanded.size(); i++) {
        assertTrue(
            Double.isFinite(target.commanded.get(i)),
            "the device saw " + target.commanded.get(i));
      }
    }

    @Test
    @DisplayName("the trigger is condition 1, so it wins over every other fault in the same loop")
    void theTriggerIsEvaluatedFirst() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();

      // Release the trigger AND break everything else at the same instant.
      m_held.set(false);
      target.at(Double.NaN);
      target.amps(OptionalDouble.of(500.0));
      DiagnosticsMode.disable();

      assertEquals(
          Optional.of(AbortReason.ENABLE_RELEASED),
          supervisor.check(),
          "with four conditions true at once the student must be told the one that is not a "
              + "fault, because letting go is not a fault");
      assertEquals(1, AbortReason.ENABLE_RELEASED.order());
    }

    @Test
    @DisplayName("holding the trigger through a whole routine changes nothing")
    void holdingItIsUneventful() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();

      for (int i = 0; i < 200; i++) {
        assertTrue(supervisor.check().isEmpty(), "nothing should trip at loop " + i);
        supervisor.commandVolts(2.0);
        m_now += Clock.dt();
      }
      assertTrue(supervisor.isArmed());
      assertEquals(0, target.stops);
    }
  }

  @Nested
  @DisplayName("the three driver-station facts arm() refuses on")
  final class TestModeOnly {

    @Test
    @DisplayName("in Test mode, enabled, with no FMS, it arms")
    void theOneLegalState() {
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();
      assertTrue(supervisor.isArmed());
      assertTrue(supervisor.describe().contains("enable held"));
    }

    @Test
    @DisplayName("a disabled robot cannot arm, and the message says how to enable it")
    void disabledRefuses() {
      DiagnosticsMode.disable();
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);

      IllegalStateException e = assertThrows(IllegalStateException.class, supervisor::arm);
      assertTrue(e.getMessage().contains("disabled"), e.getMessage());
      assertTrue(e.getMessage().contains("Test mode"), e.getMessage());
      assertFalse(supervisor.isArmed());
      assertTrue(target.commanded.isEmpty(), "a refused arm must not have touched the motor");
    }

    @Test
    @DisplayName("an attached FMS cannot arm, whatever else is true")
    void fmsRefuses() {
      DiagnosticsMode.setFmsAttached(true);
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);

      IllegalStateException e = assertThrows(IllegalStateException.class, supervisor::arm);
      assertTrue(e.getMessage().contains("FMS"), e.getMessage());
      assertTrue(e.getMessage().contains("pit"), e.getMessage());
      assertFalse(supervisor.isArmed());
    }

    @Test
    @DisplayName("teleop cannot arm, so a driver holding a trigger can never be authorising volts")
    void outsideTestModeRefuses() {
      DiagnosticsMode.leaveTestMode();
      FakeTarget target = elevator();
      TuningSupervisor supervisor = supervisorFor(target);

      IllegalStateException e = assertThrows(IllegalStateException.class, supervisor::arm);
      assertTrue(e.getMessage().contains("Test"), e.getMessage());
      assertTrue(
          e.getMessage().contains("teleop"),
          "the message has to name the scenario it is preventing: " + e.getMessage());
      assertFalse(supervisor.isArmed());
      assertEquals(0.0, supervisor.commandVolts(12.0), 0.0);
      assertTrue(target.commanded.isEmpty());
    }
  }

  @Nested
  @DisplayName("the other preconditions that stand between a caller and a volt")
  final class TheRestOfTheGate {

    @Test
    @DisplayName("a mechanism that has not been homed cannot arm")
    void unhomedRefuses() {
      FakeTarget target = elevator().homed(false);
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("homed"), e.getMessage());
    }

    @Test
    @DisplayName("a boot-assumed position is not a position reference the tuner will trust")
    void assumedBootPositionRefuses() {
      FakeTarget target = elevator().reference(new PositionReference.AssumeAtBoot(0.0));
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("assumed boot position"), e.getMessage());
      assertTrue(e.getMessage().contains("by hand"), e.getMessage());
    }

    @Test
    @DisplayName("a rotor-only encoder is fine for a flywheel and refused for an elevator")
    void rotorOnlyRefusesOnAPositionMechanism() {
      FakeTarget target = elevator().reference(new PositionReference.RotorOnly());
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("internal encoder"), e.getMessage());
    }

    @Test
    @DisplayName("homing that has not run this power cycle refuses; homing that has, arms")
    void switchHomingIsCheckedPerPowerCycle() {
      AtomicBoolean homedThisCycle = new AtomicBoolean(false);
      FakeTarget target =
          elevator().reference(new PositionReference.HomedAgainstSwitch(homedThisCycle::get));

      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("has not completed since this power cycle"), e.getMessage());

      homedThisCycle.set(true);
      TuningSupervisor supervisor = supervisorFor(target);
      supervisor.arm();
      assertTrue(supervisor.isArmed());
    }

    @Test
    @DisplayName("an absolute sensor that disagrees with the motor encoder refuses")
    void disagreeingSensorsRefuse() {
      FakeTarget target = elevator().at(0.700).absolute(OptionalDouble.of(0.760)).tolerance(0.005);
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("disagree"), e.getMessage());
      assertTrue(
          e.getMessage().contains("a wrong position is a wrong safety limit"),
          e.getMessage());

      // Inside twice the tolerance it arms.
      FakeTarget agreeing =
          elevator().at(0.700).absolute(OptionalDouble.of(0.7005)).tolerance(0.005);
      TuningSupervisor ok = supervisorFor(agreeing);
      ok.arm();
      assertTrue(ok.isArmed());
    }

    @Test
    @DisplayName("no current sensing refuses unless the loss of the overcurrent abort is accepted")
    void noCurrentSensingRefusesUnlessAccepted() {
      FakeTarget target = elevator().amps(OptionalDouble.empty());

      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("stator current"), e.getMessage());
      assertTrue(e.getMessage().contains("allowNoCurrentSensing"), e.getMessage());

      TuningSupervisor accepted = supervisorFor(target).allowNoCurrentSensing();
      accepted.arm();
      assertTrue(accepted.isArmed(), "the escape hatch exists, and it is loud rather than silent");
    }

    @Test
    @DisplayName("a coasting gravity mechanism refuses unless the risk is acknowledged in words")
    void coastingGravityRefusesUnlessAcknowledged() {
      FakeTarget target = elevator().neutral(Optional.of(NeutralMode.COAST));

      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("coast"), e.getMessage());
      assertTrue(e.getMessage().contains("acknowledgeCoastRisk"), e.getMessage());

      TuningSupervisor accepted =
          supervisorFor(target).acknowledgeCoastRisk("counterbalanced, it does not fall");
      accepted.arm();
      assertTrue(accepted.isArmed());

      assertThrows(
          IllegalArgumentException.class,
          () -> supervisorFor(target).acknowledgeCoastRisk("  "),
          "an override with no stated reason is an override nobody reviewed");
    }

    @Test
    @DisplayName("a mechanism whose idle mode cannot be read refuses too")
    void unreadableIdleModeRefuses() {
      FakeTarget target = elevator().neutral(Optional.empty());
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(e.getMessage().contains("idle mode"), e.getMessage());
    }

    @Test
    @DisplayName("a mechanism already outside the band refuses, and says how to get back in")
    void outsideTheBandRefuses() {
      FakeTarget target = elevator();
      SafetyEnvelope envelope = SafetyEnvelope.derive(target);
      target.at(envelope.positionMax() + 0.01);

      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              new TuningSupervisor(target, envelope, m_held::get)::arm);
      assertTrue(e.getMessage().contains("outside the band"), e.getMessage());
      assertTrue(e.getMessage().contains("by hand"), e.getMessage());
    }

    @Test
    @DisplayName("travel limits that leave no band refuse before anything moves")
    void anImpossibleBandRefuses() {
      FakeTarget target =
          elevator().limits(new TravelLimits(0.0, 0.009, 0.00018)).at(0.0045);
      IllegalStateException e =
          assertThrows(IllegalStateException.class, supervisorFor(target)::arm);
      assertTrue(
          e.getMessage().contains("band") || e.getMessage().contains("softMargin"),
          e.getMessage());
    }

    @Test
    @DisplayName("a velocity archetype needs no travel limits, no homing and no position reference")
    void aFlywheelArmsWithoutAnyOfThat() {
      FakeTarget flywheel =
          new FakeTarget()
              .named("Shooter")
              .archetype(MechanismArchetype.FLYWHEEL)
              .limits(TravelLimits.unbounded())
              .reference(new PositionReference.RotorOnly())
              .homed(false)
              .at(0.0);

      TuningSupervisor supervisor = supervisorFor(flywheel);
      supervisor.arm();
      assertTrue(
          supervisor.isArmed(),
          "a flywheel has no meaningful absolute position, so requiring one would be theatre");
    }
  }
}

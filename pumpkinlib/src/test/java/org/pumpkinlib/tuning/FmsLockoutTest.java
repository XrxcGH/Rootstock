package org.pumpkinlib.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.alert.AlertRegistry;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.config.ConfigRegistry;
import org.pumpkinlib.core.match.FmsPolicy;
import org.pumpkinlib.telemetry.FmsGate;

/**
 * Live tuning is <b>default-deny</b> the moment an FMS appears, and it is deny by re-evaluation
 * rather than by anybody remembering to call anything.
 *
 * <p><strong>The failure this deletes.</strong> A slider left in a strange place in the pit is the
 * normal end of a tuning session — somebody was mid-experiment when the queue call came. Every FRC
 * live-tuning implementation that reads the dashboard unconditionally carries that value onto the
 * field, and the mechanism behaves differently in the match than it did in the pit, for a reason
 * nobody will find until they diff two logs. Here the gate is consulted on <em>every read</em>, so a
 * mid-session FMS connection disables tuning on the next {@code get()} with no lifecycle call
 * involved.
 *
 * <p><strong>And why "returns the compile-time default", not "returns the last value".</strong> The
 * value in the jar is the value a redeploy would restore and the value the code review saw. Freezing
 * the last dashboard value instead would mean the robot plays the match with a number that exists
 * nowhere in git.
 *
 * <p>{@code @Tag("hal")}: {@code TuningRegistry} reaches NetworkTables in its static initialiser, and
 * {@code allowUnderFms()} builds a {@code PumpkinAlert}. See {@code TuningRegistryBudgetTest} for why
 * that is fatal rather than catchable without natives.
 */
@Tag("hal")
final class FmsLockoutTest {

  private static final double kCompiledKp = 128.0;

  private static final double kDashboardKp = 999.0;

  private NetworkTableInstance m_instance;

  private final List<DoublePublisher> m_publishers = new ArrayList<>();

  @BeforeEach
  void freshRegistry() {
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    AlertRegistry.resetForTest();
    FmsPolicy.resetForTest();
    FmsGate.clear();
    FmsGate.set(false);
    m_instance = NetworkTableInstance.create();
    m_instance.startLocal();
    TuningRegistry.setNetworkTableInstance(m_instance);
    TuningRegistry.setTuningEnabled(true);
  }

  @AfterEach
  void restore() {
    for (DoublePublisher publisher : m_publishers) {
      publisher.close();
    }
    m_publishers.clear();
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    AlertRegistry.resetForTest();
    FmsPolicy.resetForTest();
    FmsGate.clear();
    m_instance.close();
  }

  /** Writes a value the way a dashboard would, and waits for ntcore to deliver it. */
  private void dashboardWrites(String fullKey, double value) {
    DoublePublisher publisher = m_instance.getDoubleTopic(fullKey).publish();
    m_publishers.add(publisher);
    publisher.set(value);
    m_instance.flushLocal();
    m_instance.waitForListenerQueue(2.0);
    TuningRegistry.drainPoller();
  }

  @Nested
  @DisplayName("the gate itself")
  final class TheGate {

    @Test
    @DisplayName("a pit edit does not follow the robot onto the field")
    void aPitEditDoesNotSurviveAnFmsConnection() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);
      assertEquals(kDashboardKp, kp.get(), 0.0, "in the pit the dashboard wins");

      FmsGate.set(true);

      assertFalse(TuningRegistry.isTuningEnabled());
      assertEquals(
          kCompiledKp,
          kp.get(),
          0.0,
          "on the field the value in the jar wins — the number a redeploy restores and the "
              + "number the code review saw, not one that exists nowhere in git");

      FmsGate.set(false);
      assertEquals(kDashboardKp, kp.get(), 0.0, "and back in the pit the edit is live again");
    }

    @Test
    @DisplayName("no lifecycle call is involved: the gate is re-read on every get()")
    void theGateIsReEvaluatedPerRead() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);

      for (int i = 0; i < 10; i++) {
        FmsGate.set(i % 2 == 0);
        assertEquals(
            i % 2 == 0 ? kCompiledKp : kDashboardKp,
            kp.get(),
            0.0,
            "flip " + i + ": nothing was called between these reads except the FMS latch");
      }
    }

    @Test
    @DisplayName("flags are gated by exactly the same rule as doubles")
    void flagsAreGatedToo() {
      TunableBoolean flag = TuningRegistry.tunableFlag("VisionPit", "useBackCamera", false);
      flag.set(true);
      assertTrue(flag.get());

      FmsGate.set(true);
      assertFalse(
          flag.get(),
          "a pit kill switch is exactly the thing that must not follow the robot onto the field");

      FmsGate.set(false);
      assertTrue(flag.get());
    }

    @Test
    @DisplayName("the whole predicate is the override AND the FMS lock, and nothing else")
    void thePredicateIsTwoBooleans() {
      for (boolean override : new boolean[] {true, false}) {
        for (boolean fms : new boolean[] {true, false}) {
          TuningRegistry.setTuningEnabled(override);
          FmsGate.set(fms);
          assertEquals(
              override && !FmsPolicy.tunablesLocked(),
              TuningRegistry.isTuningEnabled(),
              "override=" + override + " fms=" + fms);
        }
      }
    }

    @Test
    @DisplayName("setTuningEnabled(true) does not defeat the FMS lockout")
    void theManualSwitchIsNotAnOverride() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);

      FmsGate.set(true);
      TuningRegistry.setTuningEnabled(true);

      assertFalse(TuningRegistry.isTuningEnabled());
      assertEquals(kCompiledKp, kp.get(), 0.0);
    }

    @Test
    @DisplayName("setTuningEnabled(false) turns tuning off everywhere, FMS or not")
    void theManualSwitchStillTurnsItOff() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);

      TuningRegistry.setTuningEnabled(false);
      assertFalse(TuningRegistry.isTuningEnabled());
      assertEquals(kCompiledKp, kp.get(), 0.0);
      assertTrue(TuningRegistry.describe().contains("turned off in code"), TuningRegistry.describe());
    }

    @Test
    @DisplayName("defaultValue() always reports the jar value, whatever the gate says")
    void theCompiledValueIsAlwaysReadable() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);

      assertEquals(kCompiledKp, kp.defaultValue(), 0.0);
      FmsGate.set(true);
      assertEquals(kCompiledKp, kp.defaultValue(), 0.0);
    }
  }

  @Nested
  @DisplayName("the escape hatch, which is loud on purpose")
  final class Override {

    @Test
    @DisplayName("allowUnderFms unlocks tuning and raises a BLOCKS_MATCH alert while it is on")
    void allowUnderFmsIsVisibleInTheLog() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);
      FmsGate.set(true);
      assertEquals(kCompiledKp, kp.get(), 0.0);

      TuningRegistry.allowUnderFms();

      assertTrue(TuningRegistry.isTuningEnabled(), "the hatch exists because 'the library will not "
          + "let me fix my robot in the queue line' is a worse failure than the one prevented");
      assertEquals(kDashboardKp, kp.get(), 0.0);
      assertTrue(FmsPolicy.tunablesAllowedAtEvent());

      List<PumpkinAlert> blocking = AlertRegistry.blocking();
      assertFalse(
          blocking.isEmpty(),
          "a dashboard edit that can change robot behaviour mid-match must be impossible to do "
              + "quietly");
      assertTrue(
          blocking.stream().anyMatch(a -> a.impact() == MatchImpact.BLOCKS_MATCH),
          blocking.toString());
      assertTrue(
          blocking.stream().anyMatch(a -> a.text().contains("UNLOCKED")),
          blocking.toString());
      assertTrue(
          blocking.stream().anyMatch(a -> a.text().contains("allowTunablesAtEvent(false)")),
          "the message has to say how to put it back");
      assertTrue(TuningRegistry.describe().contains("Tuning"), TuningRegistry.describe());
    }

    @Test
    @DisplayName("re-locking restores default-deny")
    void reLockingRestoresDefaultDeny() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", kCompiledKp, "V/m");
      dashboardWrites(kp.fullKey(), kDashboardKp);
      FmsGate.set(true);
      TuningRegistry.allowUnderFms();
      assertEquals(kDashboardKp, kp.get(), 0.0);

      FmsPolicy.allowTunablesAtEvent(false);

      assertFalse(TuningRegistry.isTuningEnabled());
      assertEquals(kCompiledKp, kp.get(), 0.0);
      assertTrue(TuningRegistry.describe().contains("FMS lockout in force"), TuningRegistry.describe());
    }
  }
}

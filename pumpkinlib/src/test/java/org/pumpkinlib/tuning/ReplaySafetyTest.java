package org.pumpkinlib.tuning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogReplaySource;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.pumpkinlib.core.config.ConfigRegistry;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * A tunable read during replay must produce the value the log recorded — never the value on the
 * wire, and never the compile-time default.
 *
 * <p><strong>Why this is the hardest thing about tunables and replay.</strong> Deterministic replay
 * means "the same inputs produce the same outputs". A dashboard number is an input: a mechanism
 * whose kP was 128 at 14:32 and 96 at 14:33 produced two different sets of outputs, and a replay
 * that reads the compile-time default reproduces neither. Worse, it reproduces them <em>silently</em>
 * — the log looks real, the plots look plausible, and the one number that explains the behaviour is
 * the one number the replay invented.
 *
 * <p>So every value that crosses from the wire into robot code crosses through one logged struct,
 * pushed through {@code PumpkinLog.processInputs} exactly once per loop. In replay the poller is
 * never read at all, and the struct — overwritten from the log — is pushed back into the handles
 * <b>by key</b> rather than by index, so a log written by an older build misaligns visibly at the
 * tail instead of quietly assigning an elevator's kP to a shooter's kV.
 *
 * <p><strong>What this test can and cannot see.</strong> {@code Logger.processInputs} is a no-op
 * until {@code Logger.start()} has run, so the struct is not actually reloaded from a WPILOG here.
 * That is fine and is in fact the sharper test: the struct is written directly, standing in for what
 * the log would have supplied, and the assertions are that the wire is ignored, that the push is by
 * key, and that repeated drains against a changing wire produce an unchanging value. Whether
 * AdvantageKit reloads a struct correctly is AdvantageKit's test, not this one.
 *
 * <p>{@code @Tag("hal")}: {@code TuningRegistry} reaches NetworkTables in its static initialiser.
 */
@Tag("hal")
final class ReplaySafetyTest {

  /** A replay source that supplies nothing; installing it is what makes {@code isReplay()} true. */
  private static final class SilentReplaySource implements LogReplaySource {
    @Override
    public boolean updateTable(LogTable table) {
      return false;
    }
  }

  private NetworkTableInstance m_instance;

  private final List<DoublePublisher> m_publishers = new ArrayList<>();

  @BeforeEach
  void freshRegistry() {
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    m_instance = NetworkTableInstance.create();
    m_instance.startLocal();
    TuningRegistry.setNetworkTableInstance(m_instance);
    TuningRegistry.setTuningEnabled(true);
    Logger.setReplaySource(null);
  }

  @AfterEach
  void restore() {
    Logger.setReplaySource(null);
    for (DoublePublisher publisher : m_publishers) {
      publisher.close();
    }
    m_publishers.clear();
    TuningRegistry.resetForTest();
    ConfigRegistry.clearForTest();
    PumpkinLog.resetForTest();
    m_instance.close();
  }

  private void dashboardWrites(String fullKey, double value) {
    DoublePublisher publisher = m_instance.getDoubleTopic(fullKey).publish();
    m_publishers.add(publisher);
    publisher.set(value);
    m_instance.flushLocal();
    m_instance.waitForListenerQueue(2.0);
  }

  private static void enterReplay() {
    Logger.setReplaySource(new SilentReplaySource());
    assertTrue(PumpkinLog.isReplay(), "the whole test rests on this being the replay path");
  }

  /** The single logged struct every tunable in the robot round-trips through. */
  private static TuningInputs inputs() {
    try {
      Field field = AdvantageKitTunableTransport.class.getDeclaredField("m_inputs");
      field.setAccessible(true);
      return (TuningInputs) field.get(TuningRegistry.transport());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "AdvantageKitTunableTransport has no field \"m_inputs\" any more. It was renamed, so "
              + "this replay test no longer stands in for the log. Fix: update the field name.",
          e);
    }
  }

  private static boolean hasLoggedInputs() {
    try {
      Field field = AdvantageKitTunableTransport.class.getDeclaredField("m_everLogged");
      field.setAccessible(true);
      return field.getBoolean(TuningRegistry.transport());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("m_everLogged was renamed", e);
    }
  }

  @Nested
  @DisplayName("in replay the wire does not exist")
  final class TheWireIsIgnored {

    @Test
    @DisplayName("a live dashboard edit during replay changes nothing")
    void theWireIsNeverRead() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TuningRegistry.drainPoller();

      // What the log says.
      inputs().values[Arrays.asList(inputs().keys).indexOf(kp.fullKey())] = 96.0;
      enterReplay();
      TuningRegistry.drainPoller();
      assertEquals(96.0, kp.get(), 0.0, "the replayed value, not the default and not the wire");

      // Somebody has AdvantageScope open while the replay runs. It must not matter.
      dashboardWrites(kp.fullKey(), 5000.0);
      TuningRegistry.drainPoller();
      assertEquals(96.0, kp.get(), 0.0, "the live wire is invisible in replay");
    }

    @Test
    @DisplayName("replaying the same log twice against a changing wire gives the same answer twice")
    void replayIsDeterministic() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TunableDouble kg = TuningRegistry.tunable("Elevator", "kG", 0.25, "V");
      TuningRegistry.drainPoller();

      List<String> keys = Arrays.asList(inputs().keys);
      inputs().values[keys.indexOf(kp.fullKey())] = 96.0;
      inputs().values[keys.indexOf(kg.fullKey())] = 0.31;
      enterReplay();

      List<double[]> runs = new ArrayList<>();
      for (int loop = 0; loop < 25; loop++) {
        dashboardWrites(kp.fullKey(), 1000.0 + loop);
        dashboardWrites(kg.fullKey(), 2000.0 + loop);
        TuningRegistry.drainPoller();
        runs.add(new double[] {kp.get(), kg.get()});
      }

      for (double[] run : runs) {
        assertArrayEquals(
            new double[] {96.0, 0.31},
            run,
            0.0,
            "twenty-five loops with the wire changing under it produced " + Arrays.toString(run)
                + "; replay must be a function of the log alone");
      }
    }

    @Test
    @DisplayName("the FMS gate does not silently substitute a default during replay either")
    void replayIsNotSubjectToTheFmsDefault() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 128.0, "V/m");
      TuningRegistry.drainPoller();
      inputs().values[Arrays.asList(inputs().keys).indexOf(kp.fullKey())] = 96.0;
      enterReplay();
      TuningRegistry.drainPoller();

      assertEquals(96.0, kp.get(), 0.0);
      TuningRegistry.setTuningEnabled(false);
      assertEquals(
          128.0,
          kp.get(),
          0.0,
          "recorded, not endorsed: turning tuning off in code during a replay substitutes the "
              + "compile-time default, so a replay must be run with tuning left enabled. The "
              + "FMS lock cannot cause this, because a replay is not FMS-attached.");
    }
  }

  @Nested
  @DisplayName("the push back into the handles is by key, never by index")
  final class ByKeyNotByIndex {

    @Test
    @DisplayName("a permuted key order still lands every value on the right tunable")
    void permutedKeysStillLandCorrectly() {
      TunableDouble elevatorKp = TuningRegistry.tunable("Elevator", "kP", 1.0, "V/m");
      TunableDouble shooterKv = TuningRegistry.tunable("Shooter", "kV", 2.0, "V/(rad/s)");
      TunableDouble armKg = TuningRegistry.tunable("Arm", "kG", 3.0, "V");
      TuningRegistry.drainPoller();

      // A log written by an older build, with the keys in a different order.
      TuningInputs inputs = inputs();
      inputs.keys = new String[] {shooterKv.fullKey(), armKg.fullKey(), elevatorKp.fullKey()};
      inputs.values = new double[] {22.0, 33.0, 11.0};

      enterReplay();
      TuningRegistry.drainPoller();

      assertEquals(11.0, elevatorKp.get(), 0.0, "an index-matched push would have given 22.0");
      assertEquals(22.0, shooterKv.get(), 0.0);
      assertEquals(33.0, armKg.get(), 0.0);
    }

    @Test
    @DisplayName("a key in the log that code no longer has is skipped; one code lacks keeps its value")
    void unknownAndMissingKeysAreBothSafe() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 1.0, "V/m");
      TunableDouble kg = TuningRegistry.tunable("Elevator", "kG", 9.0, "V");
      TuningRegistry.drainPoller();

      // A log from a build that had a tunable this code deleted, and that never had kG. The key
      // count matches, so the struct is not resized: this is exactly the shape a same-length but
      // differently-named key set has.
      TuningInputs inputs = inputs();
      inputs.keys = new String[] {"/Tuning/Deleted/kP", kp.fullKey()};
      inputs.values = new double[] {77.0, 55.0};

      enterReplay();
      TuningRegistry.drainPoller();

      assertEquals(55.0, kp.get(), 0.0, "matched by key; an index-matched push would give 77.0");
      assertEquals(
          9.0,
          kg.get(),
          0.0,
          "and the tunable the log never mentioned keeps the value it already had, rather than "
              + "being handed the deleted mechanism's number");
    }

    @Test
    @DisplayName("a log with a different key COUNT is resized from the code, not read past")
    void aDifferentKeyCountIsResized() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 1.0, "V/m");
      TunableDouble kg = TuningRegistry.tunable("Elevator", "kG", 9.0, "V");
      TuningRegistry.drainPoller();
      assertEquals(2, inputs().keys.length);

      // A log written by a build with only one tunable.
      TuningInputs inputs = inputs();
      inputs.keys = new String[] {kp.fullKey()};
      inputs.values = new double[] {55.0};

      enterReplay();
      TuningRegistry.drainPoller();

      assertEquals(
          2,
          inputs().keys.length,
          "the struct is re-sized to the code's key set before the push, so nothing indexes past "
              + "the end of an older log's arrays");
      assertArrayEquals(
          new String[] {kg.fullKey(), kp.fullKey()},
          inputs().keys,
          "and the regenerated key set is the sorted code one");
    }

    @Test
    @DisplayName("flags round-trip in their own arrays, so a boolean is never widened to a double")
    void flagsHaveTheirOwnArrays() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 1.0, "V/m");
      TunableBoolean flag = TuningRegistry.tunableFlag("VisionPit", "useBackCamera", false);
      TuningRegistry.drainPoller();

      TuningInputs inputs = inputs();
      assertEquals(1, inputs.keys.length, "one double topic");
      assertEquals(1, inputs.flagKeys.length, "and one boolean topic, in a separate array");
      assertEquals(kp.fullKey(), inputs.keys[0]);
      assertEquals(flag.fullKey(), inputs.flagKeys[0]);

      inputs.values = new double[] {42.0};
      inputs.flagValues = new boolean[] {true};

      enterReplay();
      TuningRegistry.drainPoller();

      assertEquals(42.0, kp.get(), 0.0);
      assertTrue(flag.get());
    }
  }

  @Nested
  @DisplayName("the key order the struct is written in")
  final class KeyOrder {

    @Test
    @DisplayName("keys are sorted and frozen at the first drain, and later ones append at the tail")
    void keysAreSortedThenFrozen() {
      TuningRegistry.tunable("Zebra", "k", 1.0, "");
      TuningRegistry.tunable("Alpha", "k", 2.0, "");
      TuningRegistry.tunable("Mike", "k", 3.0, "");
      TuningRegistry.drainPoller();

      assertArrayEquals(
          new String[] {"/Tuning/Alpha/k", "/Tuning/Mike/k", "/Tuning/Zebra/k"},
          inputs().keys,
          "sorted, so the wire layout does not depend on class-loading order");

      TuningRegistry.tunable("Bravo", "k", 4.0, "");
      TuningRegistry.drainPoller();

      assertEquals(
          "/Tuning/Bravo/k",
          inputs().keys[3],
          "a tunable created after the freeze appends at the tail rather than re-sorting into "
              + "place — a replay of an older log then misaligns visibly at the end instead of "
              + "silently in the middle");
    }

    @Test
    @DisplayName("the struct keeps being logged once it has been logged once, even when disabled")
    void theReplayTripwireIsNotOptimisedAway() {
      TunableDouble kp = TuningRegistry.tunable("Elevator", "kP", 1.0, "V/m");

      TuningRegistry.setTuningEnabled(false);
      TuningRegistry.drainPoller();
      assertFalse(
          hasLoggedInputs(),
          "before anything is logged, the disabled path is a boolean check and an early return");

      TuningRegistry.setTuningEnabled(true);
      TuningRegistry.drainPoller();
      assertTrue(hasLoggedInputs());

      // Now that the key is registered with the replay tripwire it must be processed every cycle,
      // unconditionally — a registered input key that stops being processed is a violation, not an
      // optimisation. Observable here because the replay push still runs.
      TuningInputs inputs = inputs();
      inputs.values[0] = 555.0;
      enterReplay();
      TuningRegistry.setTuningEnabled(false);
      TuningRegistry.drainPoller();

      TuningRegistry.setTuningEnabled(true);
      assertEquals(
          555.0,
          kp.get(),
          0.0,
          "the drain ran even with tuning disabled, because the key had already been logged");
    }
  }
}

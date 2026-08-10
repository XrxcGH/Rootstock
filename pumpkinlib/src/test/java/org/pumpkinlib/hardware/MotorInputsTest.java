package org.pumpkinlib.hardware;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;

/**
 * {@link MotorInputs#toLog} / {@link MotorInputs#fromLog} round-trip losslessly through a real
 * {@link LogTable}.
 *
 * <p><b>Why this is the test that matters.</b> AdvantageKit replay re-runs the robot's own code
 * against logged inputs. If a field is written under one key and read under another, or is written
 * and never read, or is added to the class and forgotten in one of the two methods, replay does not
 * fail — it silently substitutes whatever the freshly-constructed inputs object happened to hold and
 * produces a plausible, wrong answer. The whole determinism guarantee rests on these two
 * hand-written methods agreeing, and D24 requires them to be hand-written (a vendordep cannot
 * install {@code @AutoLog}'s annotation processor into a consumer's build), so nothing but a test
 * keeps them in step.
 *
 * <p>The completeness check is reflective on purpose: adding a field to {@link MotorInputs} and
 * forgetting {@code toLog} makes this class red without anybody remembering to extend it.
 */
final class MotorInputsTest {

  /** The frozen log schema. Changing one of these names breaks replay of every older log. */
  private static final Set<String> kFrozenSchema =
      new LinkedHashSet<>(
          List.of(
              "Connected",
              "PositionRot",
              "VelocityRps",
              "AppliedVolts",
              "SupplyCurrentAmps",
              "StatorCurrentAmps",
              "TorqueCurrentAmps",
              "TemperatureCelsius",
              "ForwardLimitTripped",
              "ForwardLimitValid",
              "ReverseLimitTripped",
              "ReverseLimitValid",
              "ClosedLoopReferenceRot",
              "DeviceResetCount",
              "FollowerPositionRot",
              "FollowerStatorAmps",
              "FollowerTemperatureC",
              "FollowerConnected"));

  /**
   * Every field set to a value that is distinct from the constructor default AND distinct from every
   * other field, so a key collision or a transposed pair cannot round-trip by luck.
   */
  private static MotorInputs distinctlyFilled() {
    MotorInputs in = new MotorInputs();
    in.connected = true;
    in.positionRot = 12.5;
    in.velocityRps = -3.25;
    in.appliedVolts = 7.125;
    in.supplyCurrentAmps = 18.5;
    in.statorCurrentAmps = 42.75;
    in.torqueCurrentAmps = -9.5;
    in.temperatureCelsius = 63.25;
    in.forwardLimitTripped = true;
    in.forwardLimitValid = true;
    in.reverseLimitTripped = true;
    in.reverseLimitValid = true;
    in.closedLoopReferenceRot = 11.75;
    in.deviceResetCount = 7L;
    in.followerPositionRot = new double[] {1.5, -2.5};
    in.followerStatorAmps = new double[] {30.5, 31.25};
    in.followerTemperatureC = new double[] {50.5, 51.25};
    in.followerConnected = new boolean[] {true, false};
    return in;
  }

  @Nested
  @DisplayName("the schema")
  final class Schema {

    /**
     * The key set is exactly the eighteen frozen names. A rename is a silent replay break for every
     * log recorded before it, so it has to be a deliberate act that turns this test red first.
     */
    @Test
    void theKeySetIsExactlyTheFrozenSchema() {
      LogTable table = new LogTable(0L);
      distinctlyFilled().toLog(table);

      Set<String> actual = new LinkedHashSet<>();
      for (String key : table.getAll(false).keySet()) {
        // getAll() returns fully-qualified keys, which for a root table means a leading "/".
        actual.add(key.startsWith("/") ? key.substring(1) : key);
      }
      assertEquals(
          kFrozenSchema,
          actual,
          "MotorInputs.toLog writes the frozen log schema. Fields may be ADDED at the end; a "
              + "rename or removal invalidates replay of every log recorded before it.");
    }

    /**
     * Every public instance field of {@link MotorInputs} is covered by the round trip. This is the
     * check that fires when somebody adds a field and updates only one of the two methods.
     */
    @Test
    void everyPublicFieldSurvivesTheRoundTrip() throws Exception {
      MotorInputs filled = distinctlyFilled();
      MotorInputs read = new MotorInputs();

      LogTable table = new LogTable(0L);
      filled.toLog(table);
      read.fromLog(table);

      List<String> lost = new ArrayList<>();
      for (Field f : publicInstanceFields()) {
        Object expected = f.get(filled);
        Object actual = f.get(read);
        Object fresh = f.get(new MotorInputs());
        // The filled value must genuinely differ from the default, or "survived" would be vacuous.
        assertTrue(
            !equalsField(expected, fresh),
            "distinctlyFilled() must set "
                + f.getName()
                + " to something other than its constructor default, or this test proves nothing");
        if (!equalsField(expected, actual)) {
          lost.add(f.getName() + ": wrote " + show(expected) + " but read back " + show(actual));
        }
      }
      assertTrue(
          lost.isEmpty(),
          "toLog/fromLog must round-trip every field. A field written under one key and read under "
              + "another makes replay silently wrong rather than loudly broken. Lost: "
              + lost);
    }

    /** Field count is pinned so a new field cannot land without a look at the frozen schema. */
    @Test
    void theFieldCountMatchesTheSchemaSize() {
      assertEquals(
          kFrozenSchema.size(),
          publicInstanceFields().size(),
          "every public field must have exactly one schema key, and vice versa");
    }
  }

  @Nested
  @DisplayName("the values")
  final class Values {

    /** Scalars round-trip bit-for-bit — no float narrowing, no unit rescaling. */
    @Test
    void scalarsRoundTripExactly() {
      MotorInputs filled = distinctlyFilled();
      MotorInputs read = new MotorInputs();
      LogTable table = new LogTable(0L);
      filled.toLog(table);
      read.fromLog(table);

      assertEquals(filled.connected, read.connected);
      assertEquals(filled.positionRot, read.positionRot, 0.0);
      assertEquals(filled.velocityRps, read.velocityRps, 0.0);
      assertEquals(filled.appliedVolts, read.appliedVolts, 0.0);
      assertEquals(filled.supplyCurrentAmps, read.supplyCurrentAmps, 0.0);
      assertEquals(filled.statorCurrentAmps, read.statorCurrentAmps, 0.0);
      assertEquals(filled.torqueCurrentAmps, read.torqueCurrentAmps, 0.0);
      assertEquals(filled.temperatureCelsius, read.temperatureCelsius, 0.0);
      assertEquals(filled.closedLoopReferenceRot, read.closedLoopReferenceRot, 0.0);
      assertEquals(filled.deviceResetCount, read.deviceResetCount);
    }

    /**
     * <b>NaN survives.</b> This is the NaN discipline: a signal the IO did not subscribe is stamped
     * {@link Double#NaN} once and must still be NaN in replay. A round trip that turned it into 0.0
     * would convert "we never measured this" into "we measured exactly zero" — a lie that looks
     * plausible on an AdvantageScope plot for a whole match.
     */
    @Test
    void nanSurvivesTheRoundTripRatherThanBecomingZero() {
      MotorInputs unsubscribed = new MotorInputs();
      unsubscribed.torqueCurrentAmps = Double.NaN;
      unsubscribed.temperatureCelsius = Double.NaN;
      unsubscribed.closedLoopReferenceRot = Double.NaN;

      MotorInputs read = new MotorInputs();
      // Seed the reader with real numbers, so a missing key would show up as those numbers rather
      // than coincidentally reproducing NaN from its own defaults.
      read.torqueCurrentAmps = 1.0;
      read.temperatureCelsius = 2.0;
      read.closedLoopReferenceRot = 3.0;

      LogTable table = new LogTable(0L);
      unsubscribed.toLog(table);
      read.fromLog(table);

      assertTrue(Double.isNaN(read.torqueCurrentAmps), "torqueCurrentAmps must stay NaN");
      assertTrue(Double.isNaN(read.temperatureCelsius), "temperatureCelsius must stay NaN");
      assertTrue(Double.isNaN(read.closedLoopReferenceRot), "closedLoopReferenceRot must stay NaN");
    }

    /** The four follower arrays keep their length, order and element values. */
    @Test
    void followerArraysRoundTripByValueAndOrder() {
      MotorInputs filled = distinctlyFilled();
      MotorInputs read = new MotorInputs();
      LogTable table = new LogTable(0L);
      filled.toLog(table);
      read.fromLog(table);

      assertArrayEquals(filled.followerPositionRot, read.followerPositionRot, 0.0);
      assertArrayEquals(filled.followerStatorAmps, read.followerStatorAmps, 0.0);
      assertArrayEquals(filled.followerTemperatureC, read.followerTemperatureC, 0.0);
      assertArrayEquals(filled.followerConnected, read.followerConnected);

      // Order matters: a follower array read back reversed would attribute one motor's stall to
      // the other. Assert the arrays are not accidentally symmetric, so the check has teeth.
      assertNotEquals(
          filled.followerPositionRot[0], filled.followerPositionRot[1], "fixture must be asymmetric");
    }

    /** An empty follower array — the no-followers case — round-trips as empty, not as null. */
    @Test
    void emptyFollowerArraysRoundTripAsEmpty() {
      MotorInputs none = new MotorInputs();
      MotorInputs read = new MotorInputs();
      read.followerPositionRot = new double[] {9.0};
      read.followerConnected = new boolean[] {true};

      LogTable table = new LogTable(0L);
      none.toLog(table);
      read.fromLog(table);

      assertEquals(0, read.followerPositionRot.length);
      assertEquals(0, read.followerStatorAmps.length);
      assertEquals(0, read.followerTemperatureC.length);
      assertEquals(0, read.followerConnected.length);
    }

    /**
     * Two round trips are idempotent: log, replay, log again — the second table equals the first.
     * That is the property replay actually depends on, since a replayed run re-logs its own inputs.
     */
    @Test
    void aSecondRoundTripReproducesTheSameTable() {
      MotorInputs filled = distinctlyFilled();
      LogTable first = new LogTable(0L);
      filled.toLog(first);

      MotorInputs replayed = new MotorInputs();
      replayed.fromLog(first);
      LogTable second = new LogTable(0L);
      replayed.toLog(second);

      assertEquals(
          new LinkedHashSet<>(first.getAll(false).keySet()),
          new LinkedHashSet<>(second.getAll(false).keySet()),
          "replay must re-log the identical schema");
      for (String key : kFrozenSchema) {
        assertEquals(
            first.get(key), second.get(key), "replay must re-log the identical value for " + key);
      }
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static List<Field> publicInstanceFields() {
    List<Field> out = new ArrayList<>();
    for (Field f : MotorInputs.class.getFields()) {
      if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
        out.add(f);
      }
    }
    return out;
  }

  /** Array-aware equality, with NaN counted as equal to itself the way a log round trip must. */
  private static boolean equalsField(Object a, Object b) {
    if (a instanceof double[] x && b instanceof double[] y) {
      return java.util.Arrays.equals(x, y);
    }
    if (a instanceof boolean[] x && b instanceof boolean[] y) {
      return java.util.Arrays.equals(x, y);
    }
    return java.util.Objects.equals(a, b);
  }

  private static String show(Object v) {
    if (v instanceof double[] d) {
      return java.util.Arrays.toString(d);
    }
    if (v instanceof boolean[] b) {
      return java.util.Arrays.toString(b);
    }
    return String.valueOf(v);
  }
}

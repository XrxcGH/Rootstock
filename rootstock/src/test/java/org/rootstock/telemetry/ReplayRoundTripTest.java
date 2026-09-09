package org.rootstock.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.rootstock.core.spi.Tier;
import org.rootstock.hardware.MotorInputs;
import org.rootstock.telemetry.schema.MechanismSchema;

/**
 * <strong>The most important test in this milestone.</strong> Inputs written through the schema and
 * read back must come out bit-for-bit identical, and the written table must be a fixpoint.
 *
 * <p><strong>What is at stake.</strong> Deterministic replay is the property maintainer decision 3
 * was spent to buy — one required logging backend, no SPI, no escape to a cheaper logger. The whole
 * of that purchase rests on one contract: {@code toLog} writes what the robot saw and {@code fromLog}
 * hands it back unchanged. If that round trip is lossy, replay does not fail — it <em>lies</em>. It
 * produces a run that looks like the match, plots like the match, and disagrees with the match by a
 * field nobody noticed was dropped, and every downstream conclusion drawn from it is wrong. A failed
 * replay is recoverable; a diverged one is not, which is the same argument
 * {@code RootstockReplay} exists to make at runtime.
 *
 * <h2>What this test can and cannot reach on a machine with no WPILib natives</h2>
 *
 * <p>{@code Logger.processInputs} is a no-op until {@code Logger.start()} has run, and
 * {@code WPILOGWriter} needs {@code wpiutiljni} — verified: {@code DataLogWriter} dies with
 * {@code "wpiutiljni could not be loaded from path"} on this build's classpath. So the file encoder
 * is out of reach here, and that is the correct boundary anyway: the WPILOG byte encoding belongs to
 * WPILib and AdvantageKit and is exercised by their own suites. <b>The half Rootstock owns, and the
 * only half that can be lossy by a Rootstock mistake, is the {@code toLog}/{@code fromLog} pair</b>
 * — and that is exactly what {@code Logger.processInputs} calls in each direction:
 *
 * <pre>{@code
 * if (replaySource == null) inputs.toLog(entry.getSubtable(key));
 * else                      inputs.fromLog(entry.getSubtable(key));
 * }</pre>
 *
 * <p>This test drives those two calls against a real {@link LogTable} at the real key the schema
 * hands out, which is the identical code path with the file layer removed.
 *
 * <h2>Three independent checks, because each catches a different mistake</h2>
 *
 * <ol>
 *   <li><b>Fidelity from a dirty target.</b> The read-back object is pre-filled with sentinels that
 *       no logged value holds. A field {@code toLog} writes but {@code fromLog} never reads keeps its
 *       sentinel; a field {@code fromLog} reads under a name {@code toLog} never wrote also keeps its
 *       sentinel, because {@code LogTable.get} returns the supplied default. One assertion, both
 *       failure directions.
 *   <li><b>Table fixpoint.</b> Re-serialising the read-back object must produce a table equal to the
 *       first, key for key and {@code LogValue} for {@code LogValue}. {@code LogValue.equals}
 *       compares the logged type and then the boxed value, so {@code NaN} equals {@code NaN} and
 *       {@code -0.0} does <b>not</b> equal {@code 0.0} — which is precisely the resolution replay
 *       needs and precisely what a lazily-written test using {@code assertEquals(double, double)}
 *       would miss.
 *   <li><b>Raw bits.</b> Every double is compared with {@link Double#doubleToRawLongBits}, so a
 *       value that survived as "numerically close" fails.
 * </ol>
 */
final class ReplayRoundTripTest {

  /** The frozen {@code MotorInputs} log schema, in {@code toLog} order. Renaming one breaks replay. */
  private static final List<String> kMotorInputKeys =
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
          "FollowerConnected");

  /** The key the schema hands a mechanism named {@code Elevator}. */
  private static String inputsKey() {
    return MechanismSchema.declare(new ElevatorSource()).inputsKey();
  }

  /** Writes {@code inputs} into a fresh root table under the schema's inputs key. */
  private static LogTable write(LoggableInputs inputs) {
    LogTable root = new LogTable(1234567L);
    inputs.toLog(root.getSubtable(inputsKey()));
    return root;
  }

  /** The subtable view the writer and the reader both see. */
  private static Map<String, LogTable.LogValue> fields(LogTable root) {
    return root.getSubtable(inputsKey()).getAll(true);
  }

  // ===============================================================================================
  // Fixtures
  // ===============================================================================================

  /**
   * Every field set to a value no other field holds, all finite, so a dirty-target read-back proves
   * each field independently.
   */
  private static MotorInputs distinctive() {
    MotorInputs in = new MotorInputs();
    in.connected = true;
    in.positionRot = 1.5;
    in.velocityRps = 2.5;
    in.appliedVolts = 3.5;
    in.supplyCurrentAmps = 4.5;
    in.statorCurrentAmps = 5.5;
    in.torqueCurrentAmps = 6.5;
    in.temperatureCelsius = 7.5;
    in.forwardLimitTripped = true;
    in.forwardLimitValid = true;
    in.reverseLimitTripped = false;
    in.reverseLimitValid = true;
    in.closedLoopReferenceRot = 8.5;
    in.deviceResetCount = 3L;
    in.followerPositionRot = new double[] {9.5, 10.5};
    in.followerStatorAmps = new double[] {11.5};
    in.followerTemperatureC = new double[] {12.5, 13.5, 14.5};
    in.followerConnected = new boolean[] {true, false};
    return in;
  }

  /**
   * The values a naive round trip loses: the NaN a signal the IO never subscribed is stamped with,
   * negative zero, both infinities, the subnormal at the bottom of the double range, and the two
   * ends of the long range.
   */
  private static MotorInputs adversarial() {
    MotorInputs in = new MotorInputs();
    in.connected = false;
    in.positionRot = Double.NaN;
    in.velocityRps = -0.0;
    in.appliedVolts = Double.POSITIVE_INFINITY;
    in.supplyCurrentAmps = Double.NEGATIVE_INFINITY;
    in.statorCurrentAmps = Double.MIN_VALUE;
    in.torqueCurrentAmps = -Double.MAX_VALUE;
    in.temperatureCelsius = 0.1 + 0.2;
    in.forwardLimitTripped = true;
    in.forwardLimitValid = false;
    in.reverseLimitTripped = true;
    in.reverseLimitValid = false;
    in.closedLoopReferenceRot = Math.nextUp(1.0);
    in.deviceResetCount = Long.MIN_VALUE;
    in.followerPositionRot = new double[0];
    in.followerStatorAmps = new double[] {Double.NaN, -0.0, Double.MAX_VALUE};
    in.followerTemperatureC = new double[] {Double.NEGATIVE_INFINITY};
    in.followerConnected = new boolean[] {false, false, true};
    return in;
  }

  /** A target whose every field differs from both fixtures, so nothing can pass by accident. */
  private static MotorInputs sentinels() {
    MotorInputs in = new MotorInputs();
    in.connected = true;
    in.positionRot = -8_675_309.0;
    in.velocityRps = -8_675_309.0;
    in.appliedVolts = -8_675_309.0;
    in.supplyCurrentAmps = -8_675_309.0;
    in.statorCurrentAmps = -8_675_309.0;
    in.torqueCurrentAmps = -8_675_309.0;
    in.temperatureCelsius = -8_675_309.0;
    in.forwardLimitTripped = true;
    in.forwardLimitValid = true;
    in.reverseLimitTripped = true;
    in.reverseLimitValid = true;
    in.closedLoopReferenceRot = -8_675_309.0;
    in.deviceResetCount = -8_675_309L;
    in.followerPositionRot = new double[] {-8_675_309.0, -8_675_309.0, -8_675_309.0, -8_675_309.0};
    in.followerStatorAmps = new double[] {-8_675_309.0, -8_675_309.0, -8_675_309.0, -8_675_309.0};
    in.followerTemperatureC = new double[] {-8_675_309.0, -8_675_309.0, -8_675_309.0, -8_675_309.0};
    in.followerConnected = new boolean[] {true, true, true, true};
    return in;
  }

  /** Bit-exact field-by-field comparison. Doubles by raw bits, arrays element by element. */
  private static void assertSameInputs(MotorInputs expected, MotorInputs actual, String what) {
    assertEquals(expected.connected, actual.connected, what + ": connected");
    assertBits(expected.positionRot, actual.positionRot, what + ": positionRot");
    assertBits(expected.velocityRps, actual.velocityRps, what + ": velocityRps");
    assertBits(expected.appliedVolts, actual.appliedVolts, what + ": appliedVolts");
    assertBits(expected.supplyCurrentAmps, actual.supplyCurrentAmps, what + ": supplyCurrentAmps");
    assertBits(expected.statorCurrentAmps, actual.statorCurrentAmps, what + ": statorCurrentAmps");
    assertBits(expected.torqueCurrentAmps, actual.torqueCurrentAmps, what + ": torqueCurrentAmps");
    assertBits(expected.temperatureCelsius, actual.temperatureCelsius, what + ": temperatureCelsius");
    assertEquals(expected.forwardLimitTripped, actual.forwardLimitTripped, what + ": forwardLimitTripped");
    assertEquals(expected.forwardLimitValid, actual.forwardLimitValid, what + ": forwardLimitValid");
    assertEquals(expected.reverseLimitTripped, actual.reverseLimitTripped, what + ": reverseLimitTripped");
    assertEquals(expected.reverseLimitValid, actual.reverseLimitValid, what + ": reverseLimitValid");
    assertBits(
        expected.closedLoopReferenceRot, actual.closedLoopReferenceRot, what + ": closedLoopReferenceRot");
    assertEquals(expected.deviceResetCount, actual.deviceResetCount, what + ": deviceResetCount");
    assertBitArray(expected.followerPositionRot, actual.followerPositionRot, what + ": followerPositionRot");
    assertBitArray(expected.followerStatorAmps, actual.followerStatorAmps, what + ": followerStatorAmps");
    assertBitArray(
        expected.followerTemperatureC, actual.followerTemperatureC, what + ": followerTemperatureC");
    assertArrayEquals(expected.followerConnected, actual.followerConnected, what + ": followerConnected");
  }

  private static void assertBits(double expected, double actual, String what) {
    assertEquals(
        Double.doubleToRawLongBits(expected),
        Double.doubleToRawLongBits(actual),
        () ->
            what
                + ": expected "
                + expected
                + " but the round trip produced "
                + actual
                + ". Compared as raw bits on purpose — NaN, -0.0 and a one-ulp drift are all values a"
                + " replay must reproduce exactly, and none of the three is caught by ==.");
  }

  private static void assertBitArray(double[] expected, double[] actual, String what) {
    assertEquals(expected.length, actual.length, what + ": length");
    for (int i = 0; i < expected.length; i++) {
      assertBits(expected[i], actual[i], what + "[" + i + "]");
    }
  }

  // ===============================================================================================

  @Nested
  @DisplayName("the key block the inputs land in")
  final class Keys {

    /**
     * The prefix is the schema's, not the mechanism's guess. A mechanism that passes its bare prefix
     * publishes {@code Rootstock/Elevator/Connected}, and every shipped AdvantageScope layout and every
     * replay tool looks for it one level down.
     */
    @Test
    void inputsLandUnderTheSchemasInputsKey() {
      assertEquals("Rootstock/Elevator/Inputs", inputsKey());
    }

    /**
     * The 18 field names in {@code MotorInputs.toLog} are the log schema. Renaming one breaks replay
     * of every older log, so the list is pinned here rather than left to be discovered by a student
     * whose 2026 log will not open in 2027.
     */
    @Test
    void theWrittenFieldNamesAreExactlyTheFrozenSchema() {
      Map<String, LogTable.LogValue> written = fields(write(distinctive()));

      assertEquals(
          18,
          written.size(),
          () ->
              "MotorInputs wrote " + written.size() + " fields; the frozen schema has 18. Fields may"
                  + " be ADDED at the end (update this list), but never renamed or removed.");
      for (String key : kMotorInputKeys) {
        assertTrue(
            written.containsKey(key),
            () -> "MotorInputs.toLog did not write \"" + key + "\". Written: " + written.keySet());
      }
    }

    /** Absolute keys, so the whole path a layout binds to is pinned and not just the leaf. */
    @Test
    void theAbsolutePathsAreTheOnesALayoutBindsTo() {
      Map<String, LogTable.LogValue> all = write(distinctive()).getAll(false);

      assertTrue(all.containsKey("/Rootstock/Elevator/Inputs/PositionRot"), all.keySet().toString());
      assertTrue(
          all.containsKey("/Rootstock/Elevator/Inputs/DeviceResetCount"), all.keySet().toString());
    }
  }

  @Nested
  @DisplayName("the round trip itself")
  final class RoundTrip {

    /**
     * Read back into a <em>dirty</em> object. A field written but never read keeps its sentinel; a
     * field read under a name that was never written keeps its sentinel too, because
     * {@code LogTable.get(key, default)} hands the default straight back. Both are the same failure —
     * a value that was in the log and is not in the replay — and this one assertion catches both.
     */
    @Test
    void everyFieldSurvivesAReadBackIntoADirtyTarget() {
      MotorInputs original = distinctive();
      LogTable table = write(original);

      MotorInputs replayed = sentinels();
      replayed.fromLog(table.getSubtable(inputsKey()));

      assertSameInputs(original, replayed, "dirty-target read-back");
    }

    /**
     * The values a naive round trip quietly repairs. {@code NaN} is the library's honest marker for
     * "this signal was never subscribed" — a frozen zero in its place is a lie that survives a whole
     * match and looks plausible on a plot — so it has to survive replay as {@code NaN} and not as
     * anything else.
     */
    @Test
    void nanNegativeZeroInfinitiesAndTheLongExtremesAllSurvive() {
      MotorInputs original = adversarial();
      LogTable table = write(original);

      MotorInputs replayed = sentinels();
      replayed.fromLog(table.getSubtable(inputsKey()));

      assertSameInputs(original, replayed, "adversarial read-back");

      // Stated separately, because these are the three the assertion above exists for.
      assertTrue(Double.isNaN(replayed.positionRot), "NaN must not become 0.0");
      assertEquals(
          Double.doubleToRawLongBits(-0.0),
          Double.doubleToRawLongBits(replayed.velocityRps),
          "-0.0 must not become +0.0");
      assertEquals(Long.MIN_VALUE, replayed.deviceResetCount);
    }

    /**
     * Re-serialising the read-back object must reproduce the table exactly. This is the byte-identity
     * claim in the strongest form reachable without WPILib's natives: same key set, same logged type
     * per key, same value per key, where {@code LogValue.equals} treats {@code NaN} as equal to
     * {@code NaN} and {@code -0.0} as different from {@code 0.0}.
     */
    @Test
    void reserialisingTheReplayedInputsReproducesTheTableExactly() {
      for (MotorInputs original : List.of(distinctive(), adversarial())) {
        LogTable first = write(original);

        MotorInputs replayed = sentinels();
        replayed.fromLog(first.getSubtable(inputsKey()));
        LogTable second = write(replayed);

        Map<String, LogTable.LogValue> a = fields(first);
        Map<String, LogTable.LogValue> b = fields(second);

        assertEquals(a.keySet(), b.keySet(), "the second pass wrote a different key set");
        for (String key : a.keySet()) {
          assertEquals(
              a.get(key).type,
              b.get(key).type,
              () -> "\"" + key + "\" changed logged type across the round trip");
          assertEquals(
              a.get(key),
              b.get(key),
              () ->
                  "\""
                      + key
                      + "\" did not survive: wrote "
                      + a.get(key)
                      + ", replay reproduced "
                      + b.get(key)
                      + ". A lossy field here means replay recomputes outputs from an input the match"
                      + " never had, and nothing downstream can tell.");
        }
        assertEquals(a, b, "the whole inputs table must be a fixpoint of write-read-write");
      }
    }

    /**
     * Three passes, to catch a round trip that is stable only because both directions share a
     * mistake that cancels once and not twice.
     */
    @Test
    void theRoundTripIsStableUnderRepetition() {
      MotorInputs current = adversarial();
      Map<String, LogTable.LogValue> reference = fields(write(current));

      for (int pass = 0; pass < 3; pass++) {
        LogTable table = write(current);
        MotorInputs next = sentinels();
        next.fromLog(table.getSubtable(inputsKey()));
        current = next;
        assertEquals(reference, fields(write(current)), "drifted on pass " + pass);
      }
    }

    /** The check the others are measured against: a genuinely different value must fail. */
    @Test
    void aChangedFieldIsDetectedRatherThanCompared() {
      LogTable good = write(distinctive());

      MotorInputs tampered = distinctive();
      tampered.positionRot = Math.nextUp(tampered.positionRot);
      LogTable bad = write(tampered);

      assertNotEquals(
          fields(good),
          fields(bad),
          "a one-ulp difference must be visible, or the fixpoint assertions above prove nothing.");
    }
  }

  @Nested
  @DisplayName("strings and string arrays, which MotorInputs has none of")
  final class Strings {

    /**
     * The other two shapes an IO layer writes — {@code StickyFaults} in the section 3.1 input schema
     * is a {@code String[]}. UTF-8 above the BMP, the empty string and an empty array are the three
     * that a hand-rolled encoder gets wrong.
     */
    @Test
    void unicodeEmptyStringsAndEmptyArraysSurvive() {
      StringyInputs original = new StringyInputs();
      original.label = "élan µ 🎃";
      original.blank = "";
      original.faults = new String[] {"", "StickyFault_BootDuringEnable", "µm"};

      LogTable root = new LogTable(7L);
      original.toLog(root.getSubtable("Rootstock/Camera/Inputs"));

      StringyInputs replayed = new StringyInputs();
      replayed.label = "SENTINEL";
      replayed.blank = "SENTINEL";
      replayed.faults = new String[] {"SENTINEL"};
      replayed.fromLog(root.getSubtable("Rootstock/Camera/Inputs"));

      assertEquals(original.label, replayed.label);
      assertEquals("", replayed.blank);
      assertArrayEquals(original.faults, replayed.faults);

      StringyInputs empty = new StringyInputs();
      LogTable emptyRoot = new LogTable(7L);
      empty.toLog(emptyRoot.getSubtable("Rootstock/Camera/Inputs"));
      StringyInputs emptyBack = new StringyInputs();
      emptyBack.faults = new String[] {"SENTINEL"};
      emptyBack.fromLog(emptyRoot.getSubtable("Rootstock/Camera/Inputs"));
      assertEquals(0, emptyBack.faults.length, "an empty String[] must replay as empty, not as null");
    }
  }

  @Nested
  @DisplayName("what the schema promises about the inputs half")
  final class SchemaContract {

    /**
     * <b>No input key is demotable, at any tier, under any load.</b> The governor sampling a replayed
     * input at one cycle in five hands {@code fromLog} a stale value on the other four while the
     * robot read a fresh one — which is exactly the divergence this whole test exists to prevent. The
     * published schema's Demotable column has to say so, or a future reader will assume the omission
     * was an oversight and "fix" it.
     */
    @Test
    void noRowOfTheInputSchemaIsMarkedDemotable() {
      for (var entry : MechanismSchema.inputSchema()) {
        assertEquals(
            Demotable.NO,
            entry.demotable(),
            () ->
                entry.key()
                    + " is marked demotable in the input schema. A demoted input makes fromLog return"
                    + " a stale value on four cycles in five during replay, so the byte-identical"
                    + " guarantee becomes false.");
      }
    }

    /** Position, velocity and the two limit switches are the ones replay cannot do without. */
    @Test
    void theInputSchemaKeepsItsCriticalRowsCritical() {
      var byKey = MechanismSchema.inputSchema().stream()
          .collect(java.util.stream.Collectors.toMap(e -> e.key(), e -> e.tier()));
      String p = "Rootstock/" + MechanismSchema.kNamePlaceholder + "/" + MechanismSchema.kInputs + "/";

      assertEquals(Tier.CRITICAL, byKey.get(p + "Position"));
      assertEquals(Tier.CRITICAL, byKey.get(p + "Velocity"));
      assertEquals(Tier.CRITICAL, byKey.get(p + "LimitForward"));
      assertEquals(Tier.CRITICAL, byKey.get(p + "LimitReverse"));
    }
  }

  // ===============================================================================================
  // Fixtures that are types
  // ===============================================================================================

  /** A minimal source, so the test writes to the key a real mechanism would. */
  private static final class ElevatorSource implements TelemetrySource {
    @Override
    public String telemetryName() {
      return "Elevator";
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      d.positionUnit(edu.wpi.first.units.Units.Meters)
          .velocityUnit(edu.wpi.first.units.Units.MetersPerSecond)
          .motorCount(2);
    }
  }

  /** The two shapes {@code MotorInputs} does not carry, written the same hand-written way. */
  private static final class StringyInputs implements LoggableInputs {
    String label = "";
    String blank = "";
    String[] faults = new String[0];

    @Override
    public void toLog(LogTable t) {
      t.put("Label", label);
      t.put("Blank", blank);
      t.put("Faults", faults);
    }

    @Override
    public void fromLog(LogTable t) {
      label = t.get("Label", label);
      blank = t.get("Blank", blank);
      faults = t.get("Faults", faults);
    }

    @Override
    public String toString() {
      return "StringyInputs[" + label + ", " + blank + ", " + Arrays.toString(faults) + "]";
    }
  }
}

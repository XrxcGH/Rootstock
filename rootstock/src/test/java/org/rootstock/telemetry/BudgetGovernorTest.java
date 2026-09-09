package org.rootstock.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.units.Units;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.core.spi.LogConfig;
import org.rootstock.hardware.MotorInputs;
import org.rootstock.telemetry.schema.ControlMode;

/**
 * The per-cycle byte budget, and the one invariant inside it that is not a tuning decision:
 * <strong>a replayed INPUT key is never demoted.</strong>
 *
 * <h2>Why the input invariant is the headline and not a footnote</h2>
 *
 * <p>Demotion means publishing a key on one cycle in {@value RootstockBudget#kDemotedPublishEveryN}.
 * On an output that is a coarser trace and nothing more. On an <em>input</em> it is a correctness
 * failure: during REPLAY, {@code fromLog} would hand a subsystem a stale struct on four cycles in
 * five while the real robot read a fresh one, so every output derived from it diverges — silently,
 * because the replay still runs and still produces plots. That makes the byte-identical replay
 * guarantee false, which is the guarantee maintainer decision 3 was spent to buy. This was a
 * blocking review finding, so it is asserted directly and from four angles rather than implied.
 *
 * <h2>What is checked here and what needs natives</h2>
 *
 * <p>Everything except the two alert-raising branches. Demoting a key builds a {@code RootstockAlert},
 * which constructs a WPILib {@code Alert}, which touches NetworkTables — and on a JVM with no WPILib
 * JNI, WPILib's loader calls {@code System.exit(1)} instead of throwing, so no {@code catch} and no
 * assertion can survive it. That is the same split the library already makes between
 * {@code AlertBudgetTest} and {@code AlertBudgetHalTest}. Here the threshold is pinned from
 * <em>below</em> — 49 consecutive over-budget cycles must change nothing — and
 * {@code BudgetGovernorHalTest} takes the 50th.
 *
 * <p>Every byte figure is recomputed from {@code RootstockLog}'s payload table plus
 * {@link RootstockBudget#kHeaderBytes}, never copied from a run.
 */
final class BudgetGovernorTest {

  private LogConfig m_config;

  @BeforeEach
  void freshBudget() {
    LogState.reset();
    m_config = LogState.quietDefaults();
    LogState.install(m_config);
    FmsGate.set(false);
  }

  @AfterEach
  void restore() {
    LogState.reset();
  }

  private void withBudget(double bytes) {
    m_config = LogState.quietDefaults().withPerCycleByteBudget(bytes);
    LogState.install(m_config);
  }

  /** One complete governor cycle around {@code body}. */
  private void cycle(Runnable body) {
    RootstockBudget.beginCycle();
    body.run();
    RootstockBudget.endCycle(m_config, false);
  }

  // ===============================================================================================
  // Reaching into the governor's privates, and exactly why each reach is needed
  // ===============================================================================================

  /**
   * Marks a key demoted without going through {@code demoteOne}.
   *
   * <p>{@code demoteOne} always raises an alert, and an alert kills a native-free JVM. The behaviour
   * under test is not how a key is chosen — that is the hal test's job — but what suppression
   * <em>does</em> once a key is demoted, and that is a pure function of {@code m_demoted} and the
   * cycle counter.
   */
  @SuppressWarnings("unchecked")
  private static void forceDemoted(String key) {
    ((List<String>) readStatic("m_demoted")).add(key);
  }

  /**
   * Pre-registers a key as already-refused.
   *
   * <p>{@code markDemotable} refuses an ineligible key and then raises a named error alert — but only
   * on the <em>first</em> refusal, because it dedupes on {@code m_rejectedDemotions.add(key)} before
   * building the alert. Seeding the set therefore exercises the whole refusal path (the key does not
   * enter the demotable set, and it is recorded as rejected) with only the alert construction
   * skipped. The alert itself is asserted in {@code BudgetGovernorHalTest}.
   */
  @SuppressWarnings("unchecked")
  private static void preRegisterRejection(String key) {
    ((Collection<String>) readStatic("m_rejectedDemotions")).add(key);
  }

  private static Object readStatic(String fieldName) {
    try {
      Field field = RootstockBudget.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "BudgetGovernorTest: RootstockBudget has no field \""
              + fieldName
              + "\"; it was renamed and this test no longer exercises what it claims to.",
          e);
    }
  }

  // ===============================================================================================

  @Nested
  @DisplayName("the constants the governor and ./gradlew logBudget must agree on")
  final class Constants {

    @Test
    void theyAreTheDocumentedValues() {
      assertEquals(11, RootstockBudget.kHeaderBytes);
      assertEquals(5, RootstockBudget.kDemotedPublishEveryN);
      assertEquals(50, RootstockBudget.kOverBudgetCycles);
      assertEquals(500, RootstockBudget.kRestoreCycles);
      assertEquals(0.70, RootstockBudget.kRestoreFraction, 0.0);
      assertEquals(250, RootstockBudget.kP95WindowCycles);
      assertEquals(50, RootstockBudget.kAttributionPeriodCycles);
    }

    /** The accessor exists so {@code logBudget} prints the same number the runtime uses. */
    @Test
    void headerBytesIsReadableAtRuntime() {
      assertEquals(RootstockBudget.kHeaderBytes, RootstockBudget.headerBytes());
    }

    /**
     * Restoration must be far slower than demotion. A governor whose two thresholds were close would
     * flap, and a log with dozens of schema generations is worse than one that never restores at all.
     */
    @Test
    void restorationIsAnOrderOfMagnitudeSlowerThanDemotion() {
      assertTrue(RootstockBudget.kRestoreCycles >= 10 * RootstockBudget.kOverBudgetCycles);
      assertTrue(RootstockBudget.kRestoreFraction < 1.0);
    }
  }

  @Nested
  @DisplayName("bucket A: what a call actually costs")
  final class ByteAccounting {

    /**
     * Every shape, sized from {@code RootstockLog}'s own payload table. The header is charged once per
     * record, so each call costs {@code payload + 11}.
     */
    @Test
    void everyValueShapeIsChargedItsPayloadPlusOneHeader() {
      cycle(
          () -> {
            RootstockLog.log("Rootstock/B/Bool", true); //                     1 +11 = 12
            RootstockLog.log("Rootstock/B/Long", 7L); //                       8 +11 = 19
            RootstockLog.log("Rootstock/B/Double", 1.5); //                    8 +11 = 19
            RootstockLog.log("Rootstock/B/Text", "héllo"); //             6 +11 = 17
            RootstockLog.log("Rootstock/B/Bools", new boolean[] {true, false, true}); // 3 +11 = 14
            RootstockLog.log("Rootstock/B/Longs", new long[] {1L, 2L}); //    16 +11 = 27
            RootstockLog.log("Rootstock/B/Doubles", new double[] {1, 2, 3}, Units.Meters); // 24+11 = 35
            RootstockLog.log("Rootstock/B/Texts", new String[] {"ab", ""}); // 14 +11 = 25
            RootstockLog.log("Rootstock/B/Enum", ControlMode.POSITION); //     8 +11 = 19
            RootstockLog.log("Rootstock/B/Pose", new Pose2d()); //            24 +11 = 35
          });

      // "héllo" is 6 UTF-8 bytes: h, e-acute (2 bytes), l, l, o.
      // A String[] carries a 4-byte count and a 4-byte length per element: 4 + (4+2) + (4+0) = 14.
      // ControlMode.POSITION logs as its 8-character name.
      // Pose2d.struct.getSize() is 24: two doubles of translation and one of rotation.
      int expected =
          (1 + 11)
              + (8 + 11)
              + (8 + 11)
              + (6 + 11)
              + (3 + 11)
              + (16 + 11)
              + (24 + 11)
              + (14 + 11)
              + (8 + 11)
              + (24 + 11);
      assertEquals(222, expected, "the arithmetic in this test, restated");
      assertEquals(expected, RootstockBudget.lastCycleFacadeBytes());
      assertEquals(expected, RootstockBudget.lastCycleBytes());
    }

    /**
     * Bucket B is sized so it is visible and is added to the same total the governor triggers on —
     * it is simply never demotable.
     */
    @Test
    void inputBytesJoinTheGovernableTotalWithoutJoiningTheDemotableSet() {
      cycle(
          () -> {
            RootstockLog.log("Rootstock/Elevator/Measured", 1.0); // bucket A: 19
            RootstockBudget.recordInputBytes("Rootstock/Elevator/Inputs/PositionRot", 8); // bucket B: 19
          });

      assertEquals(19, RootstockBudget.lastCycleFacadeBytes());
      assertEquals(19, RootstockBudget.lastCycleInputBytes());
      assertEquals(38, RootstockBudget.lastCycleBytes());
      assertTrue(RootstockBudget.demotableKeys().isEmpty());
    }

    /** Bucket C is measured, never estimated: no log file, no number. */
    @Test
    void theFrameworkBucketIsEmptyRatherThanGuessedWhenItCannotBeMeasured() {
      for (int i = 0; i < RootstockBudget.kAttributionPeriodCycles; i++) {
        cycle(() -> RootstockLog.log("Rootstock/B/Double", 1.0));
      }

      assertTrue(
          RootstockBudget.lastCycleFrameworkBytes().isEmpty(),
          "folding an estimate into A+B would make the two numbers a team CAN act on untrustworthy");
    }

    /** Per-key attribution refreshes on the once-per-second cycle and sorts fattest-first. */
    @Test
    void attributionNamesTheFattestKeysOnceASecond() {
      for (int i = 0; i < RootstockBudget.kAttributionPeriodCycles; i++) {
        cycle(
            () -> {
              RootstockLog.log("Rootstock/B/Fat", new double[] {1, 2, 3, 4, 5, 6}, Units.Meters);
              RootstockLog.log("Rootstock/B/Thin", true);
            });
      }

      List<Map.Entry<String, Integer>> top = RootstockBudget.topKeys(2);
      assertEquals(2, top.size());
      assertEquals("Rootstock/B/Fat", top.get(0).getKey());
      assertEquals(6 * 8 + 11, top.get(0).getValue());
      assertEquals("Rootstock/B/Thin", top.get(1).getKey());
      assertEquals(1 + 11, top.get(1).getValue());
    }
  }

  @Nested
  @DisplayName("the p95 window the governor triggers on")
  final class Percentile {

    /**
     * <strong>The warm-up regression.</strong> The window is written at a dedicated index starting at
     * zero, and the percentile reads the prefix {@code [0, filled)}. Written at {@code cycle % 250}
     * instead — with the cycle counter starting at one — slot 0 stays empty until cycle 250 and
     * {@code p95CycleBytes()} reports 0 for the governor's entire first five seconds, which is
     * exactly when a team's log is fattest. One cycle in, p95 must already be that cycle's bytes.
     */
    @Test
    void theWindowIsUsableFromTheVeryFirstCycle() {
      cycle(() -> RootstockLog.log("Rootstock/P/Double", 1.0));

      assertEquals(19, RootstockBudget.lastCycleBytes());
      assertEquals(
          19,
          RootstockBudget.p95CycleBytes(),
          "a p95 of 0 after a real cycle means the window's warm-up is broken and the governor is"
              + " blind exactly when it matters most.");
    }

    /**
     * Twenty cycles costing 19, 38, ... 380 bytes. {@code percentile95} takes
     * {@code ceil(0.95 * 20) - 1 = 18}, the nineteenth of twenty sorted ascending, which is
     * {@code 19 * 19 = 361}.
     */
    @Test
    void thePercentileIsTheNineteenthOfTwentySortedSamples() {
      for (int calls = 1; calls <= 20; calls++) {
        int n = calls;
        cycle(
            () -> {
              for (int i = 0; i < n; i++) {
                RootstockLog.log("Rootstock/P/Double" + i, 1.0);
              }
            });
      }

      assertEquals(20 * 19, RootstockBudget.lastCycleBytes());
      assertEquals((int) Math.ceil(0.95 * 20), 19, "the index arithmetic, restated");
      assertEquals(19 * 19, RootstockBudget.p95CycleBytes());
      assertEquals(361, RootstockBudget.p95CycleBytes());
    }
  }

  @Nested
  @DisplayName("the governor's trigger")
  final class Trigger {

    /**
     * Pinned from below. Forty-nine consecutive over-budget cycles must change nothing at all: no
     * demotion, no generation bump, no reason string. The fiftieth demotes and raises an alert, which
     * needs NetworkTables, so it lives in {@code BudgetGovernorHalTest}.
     */
    @Test
    void fortyNineConsecutiveOverBudgetCyclesDemoteNothing() {
      withBudget(100.0);

      for (int i = 0; i < RootstockBudget.kOverBudgetCycles - 1; i++) {
        cycle(
            () -> {
              for (int k = 0; k < 6; k++) {
                RootstockLog.log("Rootstock/T/Double" + k, 1.0, Demotable.YES);
              }
            });
      }

      assertEquals(6 * 19, RootstockBudget.lastCycleBytes(), "114 B against a 100 B budget");
      assertTrue(RootstockBudget.p95CycleBytes() > 100);
      assertEquals(0L, RootstockBudget.schemaGeneration());
      assertTrue(RootstockBudget.demotedKeys().isEmpty());
      assertEquals("", RootstockBudget.governorReason());
      assertEquals(6, RootstockBudget.demotableKeys().size(), "all six were offered to the governor");
    }

    /** A robot comfortably under budget is never touched, however long it runs. */
    @Test
    void anUnderBudgetRobotIsNeverDemoted() {
      withBudget(10_000.0);

      for (int i = 0; i < 2 * RootstockBudget.kOverBudgetCycles; i++) {
        cycle(() -> RootstockLog.log("Rootstock/T/Double", 1.0, Demotable.YES));
      }

      assertEquals(0L, RootstockBudget.schemaGeneration());
      assertTrue(RootstockBudget.demotedKeys().isEmpty());
    }

    /**
     * In REPLAY the governor demotes nothing, whatever the byte rate. It triggers on measured
     * wall-clock rates, which are meaningless at 50x, and a demotion during replay would invent a
     * schema generation the original log never had.
     */
    @Test
    void theGovernorIsANoOpInReplay() {
      withBudget(1.0);

      for (int i = 0; i < 5 * RootstockBudget.kOverBudgetCycles; i++) {
        RootstockBudget.beginCycle();
        RootstockLog.log("Rootstock/T/Double", 1.0, Demotable.YES);
        RootstockBudget.endCycle(m_config, true);
      }

      assertTrue(RootstockBudget.p95CycleBytes() > 1);
      assertEquals(0L, RootstockBudget.schemaGeneration());
      assertTrue(RootstockBudget.demotedKeys().isEmpty());
    }
  }

  @Nested
  @DisplayName("what demotion does once a key is demoted")
  final class Suppression {

    /**
     * One cycle in five, aligned to the cycle counter, and never deletion. Over ten cycles the
     * pattern is {@code ----P----P}: the counter is incremented at the top of the cycle, so
     * suppression lifts when {@code cycle % 5 == 0}, i.e. on cycles 5 and 10.
     */
    @Test
    void aDemotedKeyPublishesOnOneCycleInFiveAndIsNeverDeleted() {
      forceDemoted("Rootstock/S/Fat");

      StringBuilder pattern = new StringBuilder();
      int published = 0;
      for (int i = 0; i < 10; i++) {
        cycle(() -> RootstockLog.log("Rootstock/S/Fat", 1.0, Demotable.YES));
        boolean wrote = RootstockBudget.lastCycleFacadeBytes() > 0;
        pattern.append(wrote ? 'P' : '-');
        published += wrote ? 1 : 0;
      }

      assertEquals("----P----P", pattern.toString());
      assertEquals(2, published, "10 cycles / one in five = 2 publications, not 0 and not 10");
    }

    /** An undemoted key beside a demoted one is untouched — the governor cuts one key, not a tier. */
    @Test
    void suppressionAppliesOnlyToTheDemotedKey() {
      forceDemoted("Rootstock/S/Fat");

      cycle(
          () -> {
            RootstockLog.log("Rootstock/S/Fat", 1.0, Demotable.YES);
            RootstockLog.log("Rootstock/S/Thin", 2.0);
          });

      assertEquals(19, RootstockBudget.lastCycleFacadeBytes(), "only the undemoted key was charged");
    }

    /** With nothing demoted the suppression check is a single empty-list test on the hot path. */
    @Test
    void nothingIsSuppressedOnARobotWithNoDemotions() {
      RootstockBudget.beginCycle();

      assertFalse(RootstockBudget.isSuppressedThisCycle("Rootstock/S/Anything"));
    }
  }

  @Nested
  @DisplayName("THE INVARIANT: a replayed input key is never demoted")
  final class InputsAreNeverDemoted {

    /** The runtime statement of the rule, in every direction it has to hold. */
    @Test
    void isDemotionEligibleRefusesInputsAndTheDriverMirror() {
      assertFalse(RootstockBudget.isDemotionEligible("Rootstock/Elevator/Inputs/TempCelsius"));
      assertFalse(RootstockBudget.isDemotionEligible("Rootstock/Drive/Module0/Inputs/PositionRot"));
      assertFalse(RootstockBudget.isDemotionEligible("Rootstock/Driver/Ready"));
      assertFalse(RootstockBudget.isDemotionEligible("/Rootstock/Driver/Blocking"));
      assertFalse(RootstockBudget.isDemotionEligible(null));
      assertFalse(RootstockBudget.isDemotionEligible(""));

      assertTrue(RootstockBudget.isDemotionEligible("Rootstock/Drive/Pose3d"));
      assertTrue(RootstockBudget.isDemotionEligible("Rootstock/Vision/Front/AllTagPoses"));
    }

    /**
     * The rule is on the path segment {@code /Inputs/}, not on the substring {@code Inputs}. Pinned
     * so a later "tidy-up" to {@code contains("Inputs")} — which would quietly make a mechanism
     * called {@code InputsHandler} ungovernable — shows up as a failure and not as a mystery.
     */
    @Test
    void theRuleIsThePathSegmentAndNotTheWord() {
      assertTrue(RootstockBudget.isDemotionEligible("Rootstock/InputsHandler/Pose"));
      assertFalse(RootstockBudget.isDemotionEligible("Rootstock/InputsHandler/Inputs/Pose"));
    }

    /**
     * <strong>The strongest form the invariant can take: there is nothing to pass.</strong>
     * {@code processInputs} has no {@link Demotable} overload, so no call site can even ask.
     */
    @Test
    void processInputsHasNoDemotableOverload() {
      for (Method method : RootstockLog.class.getDeclaredMethods()) {
        if (!method.getName().equals("processInputs")) {
          continue;
        }
        for (Class<?> parameter : method.getParameterTypes()) {
          assertFalse(
              parameter == Demotable.class,
              () ->
                  "RootstockLog."
                      + method
                      + " takes a Demotable. Replayed inputs are structurally outside the governor's"
                      + " reach, and the absence of this parameter is what makes that unarguable.");
        }
      }
    }

    /**
     * And on every public overload where {@code Demotable} does appear, it is the trailing
     * parameter — which is what makes omitting it mean {@code Demotable.NO}. (The private
     * {@code gate} helper takes it mid-signature and is deliberately not in scope: no call site
     * outside this class can reach it.)
     */
    @Test
    void theDemotableParameterIsAlwaysLastOnEveryPublicOverload() {
      List<String> offenders = new ArrayList<>();
      for (Method method : RootstockLog.class.getMethods()) {
        if (method.getDeclaringClass() != RootstockLog.class) {
          continue;
        }
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
          if (parameters[i] == Demotable.class && i != parameters.length - 1) {
            offenders.add(method.toString());
          }
        }
      }
      assertTrue(
          offenders.isEmpty(),
          () ->
              "Demotable must be the last parameter so that omitting it means Demotable.NO and no"
                  + " key can become demotable by accident: " + offenders);
    }

    /**
     * End to end. A mechanism reads hardware through {@code processInputs}, charges input bytes, and
     * publishes an output it explicitly marked demotable. Only the output may be offered to the
     * governor.
     */
    @Test
    void aRealisticCycleOffersTheOutputAndNeverTheInputs() {
      MotorInputs inputs = new MotorInputs();

      cycle(
          () -> {
            RootstockLog.processInputs("Rootstock/Elevator/Inputs", inputs);
            RootstockBudget.recordInputBytes("Rootstock/Elevator/Inputs/PositionRot", 8);
            RootstockBudget.recordInputBytes("Rootstock/Elevator/Inputs/TempCelsius", 8);
            RootstockLog.log("Rootstock/Elevator/Pose3d", 1.0, Demotable.YES);
            RootstockLog.critical("Rootstock/Elevator/Measured", 2.0);
          });

      assertEquals(List.of("Rootstock/Elevator/Inputs"), RootstockLog.inputKeys());
      assertEquals(
          java.util.Set.of("Rootstock/Elevator/Pose3d"),
          RootstockBudget.demotableKeys(),
          "only the explicitly marked OUTPUT may be demotable");
      assertTrue(RootstockBudget.rejectedDemotions().isEmpty(), "nothing tried to break the rule");
      for (String key : RootstockBudget.demotableKeys()) {
        assertFalse(key.contains("/Inputs/"), key);
      }
    }

    /**
     * And if something does try, the marking is refused: the key never enters the demotable set and
     * is recorded as rejected instead.
     *
     * <p>The refusal also raises a named error alert on first sight. That construction needs
     * NetworkTables, so it is pre-deduplicated here (see {@link #preRegisterRejection}) and asserted
     * for real in {@code BudgetGovernorHalTest}. What is asserted here is the part that matters for
     * replay: the key does not become demotable.
     */
    @Test
    void anInputKeyPassedDemotableYesIsRefusedRatherThanAccepted() {
      String inputKey = "Rootstock/Elevator/Inputs/TempCelsius";
      String driverKey = "Rootstock/Driver/Ready";
      preRegisterRejection(inputKey);
      preRegisterRejection(driverKey);

      cycle(
          () -> {
            RootstockLog.log(inputKey, 42.0, Demotable.YES);
            RootstockLog.log(driverKey, true, Demotable.YES);
          });

      assertTrue(
          RootstockBudget.demotableKeys().isEmpty(),
          () -> "an input or driver key must never enter the demotable set: "
              + RootstockBudget.demotableKeys());
      assertEquals(java.util.Set.of(inputKey, driverKey), RootstockBudget.rejectedDemotions());
    }

    /**
     * A refused key is still <em>published</em>. The refusal is about the governor's reach, not about
     * dropping the value — 8 bytes and a header for the double, 1 and a header for the boolean.
     */
    @Test
    void aRefusedKeyStillPublishesNormally() {
      preRegisterRejection("Rootstock/Elevator/Inputs/TempCelsius");
      preRegisterRejection("Rootstock/Driver/Ready");

      cycle(
          () -> {
            RootstockLog.log("Rootstock/Elevator/Inputs/TempCelsius", 42.0, Demotable.YES);
            RootstockLog.log("Rootstock/Driver/Ready", true, Demotable.YES);
          });

      assertEquals((8 + 11) + (1 + 11), RootstockBudget.lastCycleFacadeBytes());
    }
  }
}

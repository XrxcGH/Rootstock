package org.pumpkinlib.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.spi.LogConfig.NtPolicy;

/**
 * {@link LogConfig} — immutability, and the one accessor that deliberately has no wither.
 *
 * <p><strong>Why immutability is worth a test rather than a code review.</strong> {@code LogConfig}
 * is read by {@code PumpkinLifecycle} to decide whether to configure and start AdvantageKit's
 * {@code Logger}. A wither that mutated the receiver instead of returning a copy would mean a config
 * built once and shared — the normal shape for a per-robot overlay — silently changing under a
 * caller that never asked for it, and the symptom would be "the log went to the wrong folder on the
 * practice bot", three weeks later, on a robot nobody can reproduce on.
 *
 * <p>This class also lives in {@code org.pumpkinlib.core.spi} for a rule-9 reason (D31/D33): it is a
 * value type the outer domains must name, so it points <em>into</em> core rather than out of it.
 * That is why a test of it needs nothing but a JVM.
 */
final class LogConfigTest {

  @Nested
  @DisplayName("the two factories (D29)")
  final class Factories {

    @Test
    void defaultsAreTheOnesPumpkinTemplateWants() {
      LogConfig config = LogConfig.defaults();

      assertFalse(config.adoptsExistingLogger());
      assertEquals(RobotMode.REAL, config.mode());
      assertEquals("/U/logs", config.wpilogFolder());
      assertEquals("/home/lvuser/logs", config.fallbackFolder());
      assertFalse(config.compress());
      assertEquals(NtPolicy.OFF_ON_FMS, config.ntPublish());
      assertEquals(Tier.DEBUG, config.minimumTier());
      assertEquals(6_000.0, config.perCycleByteBudget(), 1e-9);
      assertTrue(config.captureConsole());
      assertTrue(config.captureDriverStation());
      assertFalse(config.ctreSignalLogger());
      assertFalse(config.urcl());
      assertEquals(200, config.minFreeMegabytes());
      assertTrue(config.driverMirror());
    }

    @Test
    void adoptExistingLoggerDiffersFromDefaultsOnlyInTheAdoptFlag() {
      LogConfig adopt = LogConfig.adoptExistingLogger();
      LogConfig defaults = LogConfig.defaults();

      assertTrue(adopt.adoptsExistingLogger());
      assertFalse(defaults.adoptsExistingLogger());

      assertEquals(defaults.mode(), adopt.mode());
      assertEquals(defaults.wpilogFolder(), adopt.wpilogFolder());
      assertEquals(defaults.fallbackFolder(), adopt.fallbackFolder());
      assertEquals(defaults.compress(), adopt.compress());
      assertEquals(defaults.ntPublish(), adopt.ntPublish());
      assertEquals(defaults.minimumTier(), adopt.minimumTier());
      assertEquals(defaults.perCycleByteBudget(), adopt.perCycleByteBudget(), 1e-9);
      assertEquals(defaults.captureConsole(), adopt.captureConsole());
      assertEquals(defaults.captureDriverStation(), adopt.captureDriverStation());
      assertEquals(defaults.ctreSignalLogger(), adopt.ctreSignalLogger());
      assertEquals(defaults.urcl(), adopt.urcl());
      assertEquals(defaults.minFreeMegabytes(), adopt.minFreeMegabytes());
      assertEquals(defaults.driverMirror(), adopt.driverMirror());
    }

    @Test
    void eachCallReturnsAFreshValue() {
      assertNotSame(LogConfig.defaults(), LogConfig.defaults());
      assertEquals(LogConfig.defaults(), LogConfig.defaults());
    }
  }

  @Nested
  @DisplayName("immutability — every wither returns a copy and never mutates the receiver")
  final class Immutability {

    @Test
    void withModeDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withMode(RobotMode.REPLAY);

      assertNotSame(original, changed);
      assertEquals(RobotMode.REAL, original.mode(), "the receiver must be untouched");
      assertEquals(RobotMode.REPLAY, changed.mode());
    }

    @Test
    void withWpilogFolderDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withWpilogFolder("/media/sda1");

      assertEquals("/U/logs", original.wpilogFolder());
      assertEquals("/media/sda1", changed.wpilogFolder());
    }

    @Test
    void withFallbackFolderDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withFallbackFolder("/tmp/logs");

      assertEquals("/home/lvuser/logs", original.fallbackFolder());
      assertEquals("/tmp/logs", changed.fallbackFolder());
    }

    @Test
    void withCompressDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withCompress(true);

      assertFalse(original.compress());
      assertTrue(changed.compress());
    }

    @Test
    void withNtPublishDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withNtPublish(NtPolicy.NEVER);

      assertEquals(NtPolicy.OFF_ON_FMS, original.ntPublish());
      assertEquals(NtPolicy.NEVER, changed.ntPublish());
    }

    @Test
    void withMinimumTierDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withMinimumTier(Tier.CRITICAL);

      assertEquals(Tier.DEBUG, original.minimumTier());
      assertEquals(Tier.CRITICAL, changed.minimumTier());
    }

    @Test
    void withPerCycleByteBudgetDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withPerCycleByteBudget(1_000.0);

      assertEquals(6_000.0, original.perCycleByteBudget(), 1e-9);
      assertEquals(1_000.0, changed.perCycleByteBudget(), 1e-9);
    }

    @Test
    void withCaptureConsoleDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withCaptureConsole(false);

      assertTrue(original.captureConsole());
      assertFalse(changed.captureConsole());
    }

    @Test
    void withCaptureDriverStationDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withCaptureDriverStation(false);

      assertTrue(original.captureDriverStation());
      assertFalse(changed.captureDriverStation());
    }

    @Test
    void withCtreSignalLoggerDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withCtreSignalLogger(true);

      assertFalse(original.ctreSignalLogger());
      assertTrue(changed.ctreSignalLogger());
    }

    @Test
    void withUrclDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withUrcl(true);

      assertFalse(original.urcl());
      assertTrue(changed.urcl());
    }

    @Test
    void withMinFreeMegabytesDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withMinFreeMegabytes(50);

      assertEquals(200, original.minFreeMegabytes());
      assertEquals(50, changed.minFreeMegabytes());
    }

    @Test
    void withDriverMirrorDoesNotMutateTheReceiver() {
      LogConfig original = LogConfig.defaults();
      LogConfig changed = original.withDriverMirror(false);

      assertTrue(original.driverMirror());
      assertFalse(changed.driverMirror());
    }

    /**
     * The whole-object version of the thirteen tests above: a long fluent chain must leave the
     * value it started from completely untouched, not merely untouched in the last field written.
     */
    @Test
    void aLongChainLeavesTheOriginalCompletelyUnchanged() {
      LogConfig original = LogConfig.defaults();
      LogConfig pristine = LogConfig.defaults();

      LogConfig changed =
          original
              .withMode(RobotMode.SIM)
              .withWpilogFolder("/media/sda1")
              .withFallbackFolder("/tmp/logs")
              .withCompress(true)
              .withNtPublish(NtPolicy.NEVER)
              .withMinimumTier(Tier.CRITICAL)
              .withPerCycleByteBudget(1_000.0)
              .withCaptureConsole(false)
              .withCaptureDriverStation(false)
              .withCtreSignalLogger(true)
              .withUrcl(true)
              .withMinFreeMegabytes(50)
              .withDriverMirror(false);

      assertEquals(pristine, original, "thirteen withers must not have touched the receiver");
      assertNotEquals(pristine, changed);
    }

    /**
     * The adopt flag is decided once, by which factory built the value, and a wither must carry it
     * through untouched — otherwise a chain off {@code adoptExistingLogger()} would quietly become a
     * config that restarts a {@code Logger} the team already started.
     */
    @Test
    void withersPreserveTheAdoptFlagInBothDirections() {
      assertTrue(
          LogConfig.adoptExistingLogger()
              .withMode(RobotMode.SIM)
              .withCompress(true)
              .adoptsExistingLogger());
      assertFalse(
          LogConfig.defaults().withMode(RobotMode.SIM).withCompress(true).adoptsExistingLogger());
    }
  }

  @Nested
  @DisplayName("adoptsExistingLogger() has no wither, on purpose")
  final class NoAdoptWither {

    /**
     * The thirteen {@code with*()} methods are exactly the thirteen configurable fields. Which
     * factory built the value is the fourteenth accessor and deliberately has no twin: a
     * {@code withAdoptExistingLogger(boolean)} would recreate exactly the ambiguity D29 removed by
     * splitting construction into two named factories.
     */
    @Test
    void thereAreExactlyThirteenWithersAndNoneOfThemTouchesTheAdoptFlag() {
      List<String> withers =
          List.of(LogConfig.class.getDeclaredMethods()).stream()
              .filter(m -> Modifier.isPublic(m.getModifiers()))
              .map(Method::getName)
              .filter(n -> n.startsWith("with"))
              .sorted()
              .collect(Collectors.toList());

      assertEquals(
          13,
          withers.size(),
          "the with*() methods must be exactly the thirteen configurable fields. Found: " + withers);
      assertTrue(
          withers.stream().noneMatch(n -> n.toLowerCase(java.util.Locale.ROOT).contains("adopt")),
          "a withAdoptExistingLogger() would recreate the ambiguity D29 removed. Found: " + withers);
    }

    @Test
    void thereIsNoPublicConstructor() {
      assertEquals(
          0,
          LogConfig.class.getConstructors().length,
          "construction goes through defaults() or adoptExistingLogger(), never a constructor");
    }
  }

  @Nested
  @DisplayName("validation")
  final class Validation {

    @Test
    void aBlankFolderIsRejectedAndTheMessageSuggestsARealPath() {
      LogConfig config = LogConfig.defaults();

      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> config.withWpilogFolder("  "));
      assertTrue(e.getMessage().contains("/U/logs"), e.getMessage());

      assertThrows(IllegalArgumentException.class, () -> config.withWpilogFolder(null));
      assertThrows(IllegalArgumentException.class, () -> config.withFallbackFolder(""));
      assertThrows(IllegalArgumentException.class, () -> config.withFallbackFolder(null));
    }
  }

  @Nested
  @DisplayName("value semantics")
  final class ValueSemantics {

    @Test
    void equalConfigsAreEqualAndHashAlike() {
      LogConfig a = LogConfig.defaults().withMode(RobotMode.SIM).withMinFreeMegabytes(50);
      LogConfig b = LogConfig.defaults().withMinFreeMegabytes(50).withMode(RobotMode.SIM);

      assertEquals(a, b, "wither order must not matter");
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void theAdoptFlagParticipatesInEquality() {
      assertNotEquals(LogConfig.defaults(), LogConfig.adoptExistingLogger());
    }

    @Test
    void differentFieldsMakeDifferentValues() {
      assertNotEquals(LogConfig.defaults(), LogConfig.defaults().withUrcl(true));
      assertNotEquals(LogConfig.defaults(), "not a config");
    }

    @Test
    void aValueEqualsItself() {
      LogConfig config = LogConfig.defaults();
      assertSame(config, config);
      assertEquals(config, config);
    }
  }

  @Nested
  @DisplayName("describe() — the config the robot actually booted with")
  final class Describe {

    @Test
    void describeNamesWhichFactoryBuiltTheValue() {
      assertTrue(LogConfig.defaults().describe().contains("defaults"));
      assertTrue(LogConfig.adoptExistingLogger().describe().contains("adoptExistingLogger"));
    }

    @Test
    void describeListsEveryField() {
      String description = LogConfig.defaults().describe();

      for (String field :
          List.of(
              "mode",
              "wpilogFolder",
              "fallbackFolder",
              "compress",
              "ntPublish",
              "minimumTier",
              "perCycleByteBudget",
              "captureConsole",
              "captureDriverStation",
              "ctreSignalLogger",
              "urcl",
              "minFreeMegabytes",
              "driverMirror")) {
        assertTrue(description.contains(field), "describe() omits " + field + ":\n" + description);
      }
    }

    @Test
    void toStringIsDescribe() {
      LogConfig config = LogConfig.defaults();
      assertEquals(config.describe(), config.toString());
    }
  }
}

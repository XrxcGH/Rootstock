package org.pumpkinlib.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.spi.LogConfig;
import org.pumpkinlib.core.spi.Tier;

/**
 * The FMS tier gate raises {@code minimumTier} to {@code STANDARD} — <strong>to STANDARD and no
 * higher</strong>.
 *
 * <p><strong>Both directions matter, and only one of them is usually tested.</strong> "DEBUG is
 * dropped on FMS" is the easy half and everybody writes it. The half that has actually caused a
 * misunderstanding is the other one: a STANDARD key <em>survives</em> an FMS attach. Loose wording
 * about the gate "tightening logging at an event" once left a reader believing STANDARD disappeared
 * too, which would mean {@code SetpointVelocity}, {@code GoalError}, both soft limits, the current
 * limit and all seven gain traces vanish from exactly the logs a team reviews after a match went
 * wrong. The gate removes one tier, not two, and this class is the executable version of that
 * sentence.
 *
 * <p>The third direction is the one nobody thinks of: the gate must never <em>loosen</em> a stricter
 * setting either. A team that deliberately set {@code minimumTier = CRITICAL} at 11 pm under a byte
 * squeeze keeps CRITICAL when the FMS attaches; the gate raises, it does not assign.
 *
 * <h2>How publication is observed</h2>
 *
 * <p>{@code Logger.recordOutput} is a no-op until {@code Logger.start()} has run, so "did this key
 * get published" cannot be read off AdvantageKit here. It is read off the byte governor instead:
 * {@code PumpkinLog.gate(...)} calls {@code PumpkinBudget.recordFacadeBytes} on exactly the calls it
 * lets through and on no others, so bucket A is a faithful record of which keys passed the gate.
 * Every byte figure below is derived from the payload table in {@code PumpkinLog} plus
 * {@code PumpkinBudget.kHeaderBytes}, never copied from a previous run.
 */
final class TierGateTest {

  /** A double payload is 8 bytes; every record also carries {@code kHeaderBytes} of framing. */
  private static final int kDoubleCallBytes = 8 + PumpkinBudget.kHeaderBytes;

  private LogConfig m_config;

  @BeforeEach
  void freshFacade() {
    LogState.reset();
    m_config = LogState.quietDefaults();
    LogState.install(m_config);
    FmsGate.set(false);
  }

  @AfterEach
  void restore() {
    LogState.reset();
  }

  /** Installs a config on both the facade and the governor, so the two cannot disagree. */
  private void withMinimumTier(Tier tier) {
    m_config = LogState.quietDefaults().withMinimumTier(tier);
    LogState.install(m_config);
  }

  /**
   * Runs one complete logging cycle and returns the governable bytes bucket A recorded — i.e. the
   * bytes of the calls that passed the gate.
   */
  private int cycleBytes(Runnable body) {
    PumpkinBudget.beginCycle();
    body.run();
    PumpkinBudget.endCycle(m_config, false);
    return PumpkinBudget.lastCycleFacadeBytes();
  }

  @Nested
  @DisplayName("effectiveMinimumTier(): the gate as one expression")
  final class TheGate {

    @Test
    void offTheFieldTheConfiguredTierIsUntouched() {
      for (Tier tier : Tier.values()) {
        withMinimumTier(tier);
        assertEquals(tier, PumpkinLog.effectiveMinimumTier(), "no FMS: " + tier);
      }
    }

    /** The famous half. */
    @Test
    void anFmsAttachRaisesDebugToStandard() {
      withMinimumTier(Tier.DEBUG);
      FmsGate.set(true);

      assertEquals(Tier.STANDARD, PumpkinLog.effectiveMinimumTier());
    }

    /** The half that gets misremembered. */
    @Test
    void anFmsAttachLeavesStandardExactlyWhereItWas() {
      withMinimumTier(Tier.STANDARD);
      FmsGate.set(true);

      assertEquals(
          Tier.STANDARD,
          PumpkinLog.effectiveMinimumTier(),
          "the gate raises minimumTier TO STANDARD, so a STANDARD setting is already at the gate's"
              + " ceiling and cannot be raised past it.");
    }

    /** The half nobody writes: the gate never loosens a stricter setting. */
    @Test
    void anFmsAttachNeverRelaxesADeliberateCriticalSetting() {
      withMinimumTier(Tier.CRITICAL);
      FmsGate.set(true);

      assertEquals(
          Tier.CRITICAL,
          PumpkinLog.effectiveMinimumTier(),
          "a team that set CRITICAL under a byte squeeze must keep CRITICAL; the gate raises, it"
              + " does not assign.");
    }

    /** The gate is level-triggered, so a DS dropout between matches restores DEBUG. */
    @Test
    void theGateFollowsTheFmsStateInBothDirections() {
      withMinimumTier(Tier.DEBUG);

      assertEquals(Tier.DEBUG, PumpkinLog.effectiveMinimumTier());
      FmsGate.set(true);
      assertEquals(Tier.STANDARD, PumpkinLog.effectiveMinimumTier());
      FmsGate.set(false);
      assertEquals(Tier.DEBUG, PumpkinLog.effectiveMinimumTier());
    }

    @Test
    void debugEnabledIsExactlyTheGateBeingOpen() {
      withMinimumTier(Tier.DEBUG);
      assertTrue(PumpkinLog.debugEnabled());

      FmsGate.set(true);
      assertFalse(PumpkinLog.debugEnabled());
    }
  }

  @Nested
  @DisplayName("what actually reaches the log")
  final class WhatSurvives {

    /** Off the field, all three tiers publish: 3 doubles at 19 B each. */
    @Test
    void offTheFieldAllThreeTiersPublish() {
      withMinimumTier(Tier.DEBUG);

      int bytes = cycleBytes(TierGateTest::publishOneOfEachTier);

      assertEquals(3 * kDoubleCallBytes, bytes);
      assertEquals(57, bytes, "3 calls x (8 B payload + 11 B header)");
    }

    /**
     * On the field, CRITICAL and STANDARD both still publish and only DEBUG stops — two doubles at
     * 19 B, not one.
     */
    @Test
    void onTheFieldStandardSurvivesAndOnlyDebugDrops() {
      withMinimumTier(Tier.DEBUG);
      FmsGate.set(true);

      int bytes = cycleBytes(TierGateTest::publishOneOfEachTier);

      assertEquals(
          2 * kDoubleCallBytes,
          bytes,
          "38 B is CRITICAL + STANDARD. 19 B would mean STANDARD was dropped too, which is the"
              + " misreading this test exists to prevent.");
      assertEquals(38, bytes);
    }

    /** And with a deliberate CRITICAL setting, exactly one key survives. */
    @Test
    void aDeliberateCriticalSettingLeavesOnlyCriticalKeys() {
      withMinimumTier(Tier.CRITICAL);
      FmsGate.set(true);

      assertEquals(kDoubleCallBytes, cycleBytes(TierGateTest::publishOneOfEachTier));
    }

    /**
     * The gated call must not merely be discarded — the value must never be built. This is the trap
     * the supplier overloads exist to close: the classic AdvantageKit CPU problem is a debug line
     * allocating a {@code Translation2d} (or concatenating a string) every cycle whether or not
     * anybody is listening.
     */
    @Test
    void aClosedGateNeverInvokesADebugSupplier() {
      withMinimumTier(Tier.DEBUG);
      FmsGate.set(true);

      AtomicInteger calls = new AtomicInteger();
      Supplier<String> text = () -> {
        calls.incrementAndGet();
        return "expensive";
      };
      BooleanSupplier flag = () -> {
        calls.incrementAndGet();
        return true;
      };
      LongSupplier count = () -> {
        calls.incrementAndGet();
        return 1L;
      };

      PumpkinLog.debug("Pumpkin/Gate/Text", text);
      PumpkinLog.debug("Pumpkin/Gate/Flag", flag);
      PumpkinLog.debug("Pumpkin/Gate/Count", count);

      assertEquals(0, calls.get(), "a DEBUG supplier must not run while the FMS gate is up");

      FmsGate.set(false);
      PumpkinLog.debug("Pumpkin/Gate/Text", text);
      PumpkinLog.debug("Pumpkin/Gate/Flag", flag);
      PumpkinLog.debug("Pumpkin/Gate/Count", count);

      assertEquals(3, calls.get(), "and it must run once each when the gate is open");
    }

    /** The tier gate is checked before the demotion marking, so a gated-out key is never marked. */
    @Test
    void aGatedOutKeyIsNotEvenConsideredForDemotion() {
      withMinimumTier(Tier.CRITICAL);
      FmsGate.set(true);

      PumpkinLog.log("Pumpkin/Gate/StandardDemotable", 1.0, Demotable.YES);

      assertTrue(
          PumpkinBudget.demotableKeys().isEmpty(),
          "a key the tier gate already dropped costs nothing, so there is nothing for the governor"
              + " to demote: " + PumpkinBudget.demotableKeys());
    }
  }

  /** One call at each tier, all doubles so the byte arithmetic is the same for each. */
  private static void publishOneOfEachTier() {
    PumpkinLog.critical("Pumpkin/Gate/Critical", 1.0);
    PumpkinLog.log("Pumpkin/Gate/Standard", 2.0);
    PumpkinLog.debug("Pumpkin/Gate/Debug", 3.0);
  }
}

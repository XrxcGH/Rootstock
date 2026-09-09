package org.rootstock.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.core.spi.LogConfig;
import org.rootstock.core.spi.Tier;
import org.rootstock.telemetry.schema.MechanismSchema;
import org.rootstock.telemetry.schema.SchemaEntry;

/**
 * {@code DeviceResetCount} is CRITICAL. The other four declared extras are STANDARD.
 *
 * <p><strong>Why one key is treated differently from its four neighbours.</strong>
 * {@code DeviceResetCount} is the key that says a motor controller rebooted mid-match — power-cycled,
 * lost its configuration, and came back with factory soft limits, which is the entire explanation for
 * an elevator that fell. It can only ever be read <em>after</em> the match that went wrong, and there
 * is no second copy of the fact anywhere in the log.
 *
 * <p>STANDARD would survive today's FMS gate, which raises {@code minimumTier} to STANDARD and no
 * higher. That is not the hazard. The hazard is that {@code minimumTier} is a {@code LogConfig} field
 * a team under a byte squeeze can set to {@code CRITICAL} at 11 pm at an event — and if they do, a
 * STANDARD {@code DeviceResetCount} disappears from precisely the logs it exists for. CRITICAL is the
 * only tier whose presence does not depend on a decision someone might make in a hurry, so this test
 * asserts survival against <em>that</em> setting, not merely against an FMS attach.
 *
 * <p><strong>Scope, stated honestly.</strong> The tier travels from the mechanism's own
 * {@code d.extra("DeviceResetCount", Tier.CRITICAL)} call site, which lives in
 * {@code org.rootstock.mechanism} and is not built yet. What is pinned here is the frozen contract
 * that call site has to match — {@link MechanismSchema#extraSchema()} — plus proof that a source
 * declaring it that way does survive both squeezes. The declaration and the publish must land
 * together; the section 1.5 schema audit is what catches it if only one does.
 */
final class DeviceResetTierTest {

  private static final String kDeviceResetCount = "DeviceResetCount";
  private static final String kFeedbackVolts = "FeedbackVolts";
  private static final String kFeedforwardVolts = "FeedforwardVolts";
  private static final String kBlocked = "Blocked";
  private static final String kPlan = "Plan";

  /** The five extras, keyed by their leaf name, from the published schema. */
  private static Map<String, SchemaEntry> extras() {
    String prefix = RootstockLog.kRoot + "/" + MechanismSchema.kNamePlaceholder + "/";
    Map<String, SchemaEntry> byLeaf = new LinkedHashMap<>();
    for (SchemaEntry entry : MechanismSchema.extraSchema()) {
      assertTrue(entry.key().startsWith(prefix), entry.key());
      byLeaf.put(entry.key().substring(prefix.length()), entry);
    }
    return byLeaf;
  }

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

  private void withMinimumTier(Tier tier) {
    m_config = LogState.quietDefaults().withMinimumTier(tier);
    LogState.install(m_config);
  }

  /** Publishes all five extras in one governor cycle and returns the bytes that got through. */
  private int publishAllFive(MechanismSchema schema) {
    RootstockBudget.beginCycle();
    schema.extra(kDeviceResetCount, 3L);
    schema.extra(kFeedbackVolts, 1.0);
    schema.extra(kFeedforwardVolts, 2.0);
    schema.extra(kBlocked, "Waiting");
    schema.extra(kPlan, new String[] {"A", "BB"});
    RootstockBudget.endCycle(m_config, false);
    return RootstockBudget.lastCycleFacadeBytes();
  }

  // Payload sizes, from RootstockLog's table, each plus the 11-byte record header:
  //   DeviceResetCount  long                     8 + 11 = 19
  //   FeedbackVolts     double                   8 + 11 = 19
  //   FeedforwardVolts  double                   8 + 11 = 19
  //   Blocked           "Waiting", 7 UTF-8 bytes 7 + 11 = 18
  //   Plan              String[]{"A","BB"}: 4 count + (4+1) + (4+2) = 15   15 + 11 = 26
  private static final int kDeviceResetBytes = 8 + RootstockBudget.kHeaderBytes;
  private static final int kFeedbackBytes = 8 + RootstockBudget.kHeaderBytes;
  private static final int kFeedforwardBytes = 8 + RootstockBudget.kHeaderBytes;
  private static final int kBlockedBytes = 7 + RootstockBudget.kHeaderBytes;
  private static final int kPlanBytes = (4 + (4 + 1) + (4 + 2)) + RootstockBudget.kHeaderBytes;
  private static final int kAllFiveBytes =
      kDeviceResetBytes + kFeedbackBytes + kFeedforwardBytes + kBlockedBytes + kPlanBytes;

  @Nested
  @DisplayName("the frozen contract in MechanismSchema.extraSchema()")
  final class TheContract {

    @Test
    void thereAreExactlyFiveDeclaredExtras() {
      assertEquals(5, MechanismSchema.extraSchema().size());
      assertEquals(
          List.of(kDeviceResetCount, kFeedbackVolts, kFeedforwardVolts, kBlocked, kPlan),
          List.copyOf(extras().keySet()),
          "the five, in the order section 3.1 rules on them");
    }

    @Test
    void deviceResetCountIsCritical() {
      SchemaEntry entry = extras().get(kDeviceResetCount);

      assertEquals(
          Tier.CRITICAL,
          entry.tier(),
          "STANDARD would vanish the moment a team set minimumTier = CRITICAL at an event, which is"
              + " exactly when a controller reboot is the thing they need to see.");
      assertEquals("long", entry.type());
      assertEquals(SchemaEntry.kNoUnit, entry.unit(), "a count is unit-less, not dimensionless");
      assertEquals(Demotable.NO, entry.demotable());
      assertTrue(entry.meaning().contains("no second copy"), entry.meaning());
    }

    @Test
    void theOtherFourAreStandard() {
      Map<String, SchemaEntry> byLeaf = extras();

      for (String key : List.of(kFeedbackVolts, kFeedforwardVolts, kBlocked, kPlan)) {
        assertEquals(Tier.STANDARD, byLeaf.get(key).tier(), key);
        assertNotEquals(Tier.CRITICAL, byLeaf.get(key).tier(), key);
      }

      assertEquals("volts", byLeaf.get(kFeedbackVolts).unit());
      assertEquals("volts", byLeaf.get(kFeedforwardVolts).unit());
      assertEquals(SchemaEntry.kNoUnit, byLeaf.get(kBlocked).unit());
      assertEquals(SchemaEntry.kNoUnit, byLeaf.get(kPlan).unit());
      assertEquals("String[]", byLeaf.get(kPlan).type());
    }

    /** No extra is demotable: they are all either diagnostics or the one key with no second copy. */
    @Test
    void noExtraIsDemotable() {
      for (SchemaEntry entry : MechanismSchema.extraSchema()) {
        assertEquals(Demotable.NO, entry.demotable(), entry.key());
      }
    }
  }

  @Nested
  @DisplayName("what survives each squeeze")
  final class Survival {

    /** Off the field, nothing is gated: all five publish. */
    @Test
    void offTheFieldAllFivePublish() {
      assertEquals(kAllFiveBytes, publishAllFive(declaredAsSchemaSays()));
      assertEquals(101, kAllFiveBytes, "19 + 19 + 19 + 18 + 26, restated");
    }

    /**
     * An FMS attach raises {@code minimumTier} to STANDARD and no higher, so all five still publish.
     * This is the case that is <em>not</em> the hazard, asserted so the next test's failure cannot be
     * mistaken for the gate being broken in general.
     */
    @Test
    void anFmsAttachDropsNoneOfThem() {
      FmsGate.set(true);

      assertEquals(kAllFiveBytes, publishAllFive(declaredAsSchemaSays()));
    }

    /**
     * <strong>The hazard.</strong> A team sets {@code minimumTier = CRITICAL} under a byte squeeze.
     * Four extras go; {@code DeviceResetCount} stays, because it is the only one whose presence does
     * not depend on that decision.
     */
    @Test
    void aDeliberateCriticalMinimumLeavesOnlyDeviceResetCount() {
      withMinimumTier(Tier.CRITICAL);
      FmsGate.set(true);

      assertEquals(
          kDeviceResetBytes,
          publishAllFive(declaredAsSchemaSays()),
          "19 B is DeviceResetCount alone. Anything larger means a STANDARD extra survived a"
              + " CRITICAL minimum; anything smaller means the one key with no second copy was lost.");
    }

    /**
     * A nested block's cap raises tiers, it never lowers them: inside a swerve module capped at
     * STANDARD, even {@code DeviceResetCount} publishes at STANDARD. Four modules times a full
     * CRITICAL block would put sixty-eight always-on keys in an FMS log for information the
     * chassis-level keys already summarise.
     */
    @Test
    void aStandardCappedNestedBlockQuietensEvenTheCriticalExtra() {
      withMinimumTier(Tier.CRITICAL);

      MechanismSchema module =
          MechanismSchema.declareNested(
              "Rootstock/Drive", new ExtrasSource("Module0"), Tier.STANDARD);
      assertEquals("Rootstock/Drive/Module0", module.prefix());
      assertEquals(Tier.STANDARD, module.tierCap());

      assertEquals(
          0,
          publishAllFive(module),
          "a STANDARD-capped block publishes nothing at all under a CRITICAL minimum — the cap makes"
              + " a block quieter, never louder.");
    }
  }

  /** A source that declares the five extras exactly as {@code extraSchema()} rules on them. */
  private static MechanismSchema declaredAsSchemaSays() {
    return MechanismSchema.declare(new ExtrasSource("Elevator"));
  }

  /**
   * The declaration the {@code org.rootstock.mechanism} call site has to make.
   *
   * <p>{@code DeviceResetCount} uses the <b>unit-free</b> {@code extra(String, Tier)} overload, which
   * is the half of this that telemetry owns: a count has no unit, and the alternative was passing
   * {@code Units.Value} and claiming it was dimensionless.
   */
  private record ExtrasSource(String name) implements TelemetrySource {
    @Override
    public String telemetryName() {
      return name;
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      d.positionUnit(Units.Meters)
          .velocityUnit(Units.MetersPerSecond)
          .motorCount(1)
          .extra(kDeviceResetCount, Tier.CRITICAL)
          .extra(kFeedbackVolts, Units.Volts, Tier.STANDARD)
          .extra(kFeedforwardVolts, Units.Volts, Tier.STANDARD)
          .extra(kBlocked, Tier.STANDARD)
          .extra(kPlan, Tier.STANDARD);
    }
  }
}

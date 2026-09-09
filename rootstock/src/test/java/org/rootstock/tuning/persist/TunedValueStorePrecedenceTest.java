package org.rootstock.tuning.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.config.PersistentStore;

/**
 * The four-tier precedence, exercised one tier at a time, with the winning tier reported per gain.
 *
 * <p><strong>The failure this closes.</strong> Every common FRC tunable class reads the dashboard and
 * falls back to a compile-time default, and nothing writes back. The result is the loop every team
 * knows: <i>we tuned it, then power-cycled, and lost everything</i> — or the worse version, <i>we
 * tuned it, it worked all day, and then somebody redeployed</i>. Four tiers fix that only if the
 * order is right and the merge is <b>per value</b>: a student who bisected only kG must not
 * accidentally revert kV, which a whole-mechanism merge would do silently.
 *
 * <p><strong>And why the {@code configHash} is the most valuable line in the file.</strong> Gains
 * that survive a gearbox change are how a mechanism gets destroyed after a rebuild. A file recorded
 * against a different physical configuration is made <em>inert</em> — every value in it is ignored,
 * the mechanism falls back to the tier below, and a {@code BLOCKS_MATCH} error says so. The
 * fall-back may well be {@code Gains.UNTUNED}, in which case the mechanism refuses closed-loop
 * control entirely, and that is the intended outcome.
 *
 * <p>{@code @Tag("hal")}: {@link TunedValueStore#resolve} raises {@code RootstockAlert}s, and
 * {@code PersistentStore.persistent()} resolves through {@code Filesystem.getOperatingDirectory()},
 * which reads the HAL runtime type. Both are fatal rather than catchable without natives.
 */
@Tag("hal")
final class TunedValueStorePrecedenceTest {

  private static final String kMechanism = "Elevator";

  private static final String kHash = "0123456789abcdef";

  /** The gains in the team's own config class: the tier-1 fallback for every test below. */
  private static final Gains kCodeDefault =
      new Gains(10.0, 0.0, 1.0, 0.10, 5.00, 0.050, 0.20);

  @TempDir Path m_deployRoot;

  @TempDir Path m_robotRoot;

  @BeforeEach
  void pointTheStoresAtTempDirectories() {
    AlertRegistry.resetForTest();
    TunedValueStore.resetForTest();
    installStore("s_deploy", PersistentStore.at(m_deployRoot));
    installStore("s_persistent", PersistentStore.at(m_robotRoot));
  }

  @AfterEach
  void restore() {
    PersistentStore.resetForTest();
    TunedValueStore.resetForTest();
    AlertRegistry.resetForTest();
  }

  /**
   * Points one of {@code PersistentStore}'s two shared singletons at a temp directory.
   *
   * <p>Reflection because the real accessors resolve through {@code Platform.persistentDir()} and
   * {@code Platform.deployDir()}, which on a desktop are the launch directory — so a test that used
   * them would read and write the developer's own working tree, and two tests running in the same
   * JVM would see each other's files.
   */
  private static void installStore(String fieldName, PersistentStore store) {
    try {
      Field field = PersistentStore.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(null, store);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "PersistentStore has no field \""
              + fieldName
              + "\". It was renamed, so this test would silently read and write the developer's "
              + "own deploy directory. Fix: update the field name here.",
          e);
    }
  }

  /** Writes a gains.json containing exactly the named gains for one mechanism. */
  private static void writeFile(Path root, String hash, Map<GainId, Double> gains) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("configHash", hash);
    Map<String, Object> gainsBlock = new LinkedHashMap<>();
    gains.forEach((id, value) -> gainsBlock.put(id.key(), value));
    block.put("gains", gainsBlock);

    Map<String, Object> mechanisms = new LinkedHashMap<>();
    mechanisms.put(kMechanism, block);
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("mechanisms", mechanisms);
    TunedValueStore.stamp(file);

    assertTrue(
        PersistentStore.at(root).writeJson(TunedValueStore.kFileName, file),
        "the fixture file must actually have been written");
  }

  private static TunedValueStore.Resolved resolve() {
    return TunedValueStore.resolve(kMechanism, kHash, kCodeDefault);
  }

  @Nested
  @DisplayName("the four tiers, one at a time")
  final class Precedence {

    @Test
    @DisplayName("tier 1 alone: with no files, every gain is the code default and says so")
    void codeDefaultOnly() {
      TunedValueStore.Resolved resolved = resolve();

      assertEquals(kCodeDefault, resolved.gains());
      for (GainId id : GainId.values()) {
        assertEquals(ValueSource.CODE_DEFAULT, resolved.sourceOf(id), id.key());
      }
      assertTrue(resolved.warnings().isEmpty());
      assertTrue(
          resolved.describe().contains("compiled into the jar"),
          "the report must answer 'why is my gain not what I typed' without a log pull");
    }

    @Test
    @DisplayName("tier 2 beats tier 1, per value")
    void deployFileBeatsCodeDefault() {
      writeFile(m_deployRoot, kHash, Map.of(GainId.KP, 42.0));

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(42.0, resolved.gains().kP(), 0.0);
      assertEquals(ValueSource.DEPLOY_FILE, resolved.sourceOf(GainId.KP));
      assertEquals(
          kCodeDefault.kV(),
          resolved.gains().kV(),
          0.0,
          "the file mentioned only kP, so every other gain is untouched");
      assertEquals(ValueSource.CODE_DEFAULT, resolved.sourceOf(GainId.KV));
    }

    @Test
    @DisplayName("tier 3 beats tier 2, per value")
    void robotFileBeatsDeployFile() {
      writeFile(m_deployRoot, kHash, Map.of(GainId.KP, 42.0, GainId.KV, 7.0));
      writeFile(m_robotRoot, kHash, Map.of(GainId.KP, 96.0));

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(96.0, resolved.gains().kP(), 0.0, "the robot file is the newest answer");
      assertEquals(ValueSource.ROBOT_FILE, resolved.sourceOf(GainId.KP));
      assertEquals(
          7.0,
          resolved.gains().kV(),
          0.0,
          "a student who bisected only kG must not accidentally revert kV — the merge is per "
              + "value, not per mechanism");
      assertEquals(ValueSource.DEPLOY_FILE, resolved.sourceOf(GainId.KV));
      assertEquals(ValueSource.CODE_DEFAULT, resolved.sourceOf(GainId.KA));
    }

    @Test
    @DisplayName("all three tiers at once resolve to one gain from each, correctly attributed")
    void allThreeTiersAtOnce() {
      writeFile(m_deployRoot, kHash, Map.of(GainId.KP, 42.0, GainId.KV, 7.0, GainId.KA, 0.9));
      writeFile(m_robotRoot, kHash, Map.of(GainId.KV, 5.5));

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(ValueSource.DEPLOY_FILE, resolved.sourceOf(GainId.KP));
      assertEquals(ValueSource.ROBOT_FILE, resolved.sourceOf(GainId.KV));
      assertEquals(ValueSource.DEPLOY_FILE, resolved.sourceOf(GainId.KA));
      assertEquals(ValueSource.CODE_DEFAULT, resolved.sourceOf(GainId.KG));

      assertEquals(42.0, resolved.gains().kP(), 0.0);
      assertEquals(5.5, resolved.gains().kV(), 0.0);
      assertEquals(0.9, resolved.gains().kA(), 0.0);
      assertEquals(kCodeDefault.kG(), resolved.gains().kG(), 0.0);
    }

    @Test
    @DisplayName("tier 4 is the dashboard, and resolve() deliberately stops before it")
    void theDashboardTierIsNotResolvedHere() {
      writeFile(m_robotRoot, kHash, Map.of(GainId.KP, 96.0));
      TunedValueStore.Resolved resolved = resolve();

      for (GainId id : GainId.values()) {
        assertFalse(
            resolved.sourceOf(id) == ValueSource.DASHBOARD,
            "a live NT edit is applied by TunableDouble on the read path, not baked into the "
                + "resolved set at registration: " + id.key());
      }
      assertTrue(ValueSource.DASHBOARD.isLoadTier());
      assertEquals(
          "a live dashboard edit under /Tuning/", ValueSource.DASHBOARD.explain());
    }

    @Test
    @DisplayName("the enum's declaration order is the precedence order, lowest first")
    void theEnumOrderIsThePrecedenceOrder() {
      assertEquals(0, ValueSource.CODE_DEFAULT.ordinal());
      assertEquals(1, ValueSource.DEPLOY_FILE.ordinal());
      assertEquals(2, ValueSource.ROBOT_FILE.ordinal());
      assertEquals(3, ValueSource.DASHBOARD.ordinal());

      for (ValueSource source : ValueSource.values()) {
        assertEquals(
            source.ordinal() <= ValueSource.DASHBOARD.ordinal(),
            source.isLoadTier(),
            source.name());
        assertFalse(source.explain().isBlank(), source.name());
      }
      assertEquals(8, ValueSource.values().length, "four load tiers plus four wizard steps");
    }
  }

  @Nested
  @DisplayName("the two ways a file is refused, neither of which stops the robot booting")
  final class Refusals {

    @Test
    @DisplayName("a configHash mismatch makes the whole entry inert and raises a BLOCKS_MATCH error")
    void aMismatchedHashIsInert() {
      writeFile(m_robotRoot, "deadbeefdeadbeef", Map.of(GainId.KP, 96.0, GainId.KG, 3.0));

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(
          kCodeDefault,
          resolved.gains(),
          "every value in the file is ignored, not just the ones that look odd");
      for (GainId id : GainId.values()) {
        assertEquals(ValueSource.CODE_DEFAULT, resolved.sourceOf(id), id.key());
      }

      assertEquals(1, resolved.warnings().size());
      String warning = resolved.warnings().get(0);
      assertTrue(warning.contains("deadbeefdeadbeef"), warning);
      assertTrue(warning.contains(kHash), warning);
      assertTrue(warning.contains("destroyed after a rebuild"), warning);
      assertTrue(warning.contains("Fix:"), warning);

      assertTrue(
          AlertRegistry.blocking().stream()
              .anyMatch(a -> a.impact() == MatchImpact.BLOCKS_MATCH),
          "the fall-back may be Gains.UNTUNED, which refuses closed-loop control — that must "
              + "block the match rather than surprise a driver");
    }

    @Test
    @DisplayName("a malformed file is a pit warning, is skipped, and the robot still boots")
    void aMalformedFileIsSkipped() throws IOException {
      Files.createDirectories(m_robotRoot);
      Files.writeString(
          m_robotRoot.resolve(TunedValueStore.kFileName), "{ \"mechanisms\": { , }");

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(kCodeDefault, resolved.gains(), "the compile-time defaults are in force");
      assertEquals(1, resolved.warnings().size());
      String warning = resolved.warnings().get(0);
      assertTrue(warning.contains("could not be read as JSON"), warning);
      assertTrue(warning.contains("trailing comma"), warning);

      for (RootstockAlert alert : AlertRegistry.active()) {
        assertEquals(
            MatchImpact.PIT_ONLY,
            alert.impact(),
            "a robot that refuses to boot because a JSON file has a stray comma is unacceptable, "
                + "so this is a warning: " + alert.text());
      }
    }

    @Test
    @DisplayName("a file with no entry for this mechanism is not an error at all")
    void anAbsentMechanismIsSilent() {
      Map<String, Object> file = new LinkedHashMap<>();
      Map<String, Object> mechanisms = new LinkedHashMap<>();
      mechanisms.put("SomeOtherThing", new LinkedHashMap<String, Object>());
      file.put("mechanisms", mechanisms);
      TunedValueStore.stamp(file);
      PersistentStore.at(m_robotRoot).writeJson(TunedValueStore.kFileName, file);

      TunedValueStore.Resolved resolved = resolve();
      assertEquals(kCodeDefault, resolved.gains());
      assertTrue(resolved.warnings().isEmpty());
    }

    @Test
    @DisplayName("an entry with no configHash at all is honoured, not refused")
    void aFileWithoutAHashIsStillUsable() {
      Map<String, Object> block = new LinkedHashMap<>();
      Map<String, Object> gainsBlock = new LinkedHashMap<>();
      gainsBlock.put(GainId.KP.key(), 96.0);
      block.put("gains", gainsBlock);
      Map<String, Object> mechanisms = new LinkedHashMap<>();
      mechanisms.put(kMechanism, block);
      Map<String, Object> file = new LinkedHashMap<>();
      file.put("mechanisms", mechanisms);
      TunedValueStore.stamp(file);
      PersistentStore.at(m_robotRoot).writeJson(TunedValueStore.kFileName, file);

      TunedValueStore.Resolved resolved = resolve();
      assertEquals(96.0, resolved.gains().kP(), 0.0, "a hand-written file is a legitimate file");
      assertEquals(ValueSource.ROBOT_FILE, resolved.sourceOf(GainId.KP));
    }
  }

  @Nested
  @DisplayName("the round trip through saveToRobot")
  final class RoundTrip {

    @Test
    @DisplayName("what is saved is what the next boot resolves, with its provenance intact")
    void savingThenResolvingReproducesTheGains() {
      Gains tuned = new Gains(96.0, 0.0, 4.0, 0.31, 5.10, 0.060, 0.2528);
      Map<GainId, String> provenance = new java.util.EnumMap<>(GainId.class);
      provenance.put(GainId.KV, ValueSource.WIZARD_OLS.name());
      provenance.put(GainId.KG, ValueSource.WIZARD_BISECTION.name());

      assertTrue(
          TunedValueStore.saveToRobot(
              kMechanism, kHash, tuned, Map.of("tolerance", 0.005), provenance, java.util.Optional.empty()));

      TunedValueStore.Resolved resolved = resolve();

      assertEquals(tuned, resolved.gains());
      for (GainId id : GainId.values()) {
        assertEquals(
            ValueSource.ROBOT_FILE,
            resolved.sourceOf(id),
            "the tier that supplied the value on this boot is the robot file, whatever produced "
                + "it originally: " + id.key());
      }
      assertEquals(0.005, resolved.controlValues().get("tolerance"), 0.0);
    }

    @Test
    @DisplayName("forget() reverts one mechanism to the tier below and leaves the others alone")
    void forgetRevertsOneMechanism() {
      writeFile(m_deployRoot, kHash, Map.of(GainId.KP, 42.0));
      TunedValueStore.saveToRobot(
          kMechanism,
          kHash,
          kCodeDefault.withKp(96.0),
          Map.of(),
          Map.of(),
          java.util.Optional.empty());
      TunedValueStore.saveToRobot(
          "Shooter", kHash, kCodeDefault.withKv(2.0), Map.of(), Map.of(), java.util.Optional.empty());

      assertEquals(96.0, resolve().gains().kP(), 0.0);

      assertTrue(TunedValueStore.forget(kMechanism));

      assertEquals(42.0, resolve().gains().kP(), 0.0, "back to the committed deploy file");
      assertEquals(
          2.0,
          TunedValueStore.resolve("Shooter", kHash, kCodeDefault).gains().kV(),
          0.0,
          "and the other mechanism's saved values are untouched");
      assertFalse(TunedValueStore.forget(kMechanism), "forgetting twice is not an error");
    }

    @Test
    @DisplayName("the Preferences mirror is off by default")
    void theMirrorIsOffByDefault() {
      assertFalse(TunedValueStore.isMirroringToPreferences());
    }
  }
}

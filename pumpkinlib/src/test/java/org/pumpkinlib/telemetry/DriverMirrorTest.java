package org.pumpkinlib.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.alert.AlertRegistry;
import org.pumpkinlib.core.spi.LogConfig;

/**
 * The {@code /Pumpkin/Driver} mirror: at most {@value AlertRegistry#kDriverDisplayCap} rows, plus one
 * rollup line pointing at the pit tab.
 *
 * <p><strong>Why an uncapped mirror is the same as no mirror.</strong> A half-built robot in week two
 * has thirty active alerts. Thirty rows of red on a driver's dashboard carries exactly as much
 * information as zero rows, because the driver learns to ignore the panel — and an ignored alert is
 * strictly worse than no alert, since it also hides the one that mattered. The cap is not cosmetic
 * tidying; it is what keeps the panel worth looking at.
 *
 * <p>The cap is also not silent truncation, which would be the same failure wearing a different hat:
 * {@code AlertRegistry.blocking()} still returns every alert, and the mirror publishes a
 * {@code "+N more - see the pit tab"} row so the hidden ones are visibly hidden.
 *
 * <h2>The split with {@code DriverMirrorHalTest}</h2>
 *
 * <p>Building a {@code PumpkinAlert} constructs a WPILib {@code Alert}, which touches NetworkTables,
 * which on a JVM without WPILib's JNI calls {@code System.exit(1)} — no exception, no stack trace,
 * the whole test JVM gone. So the case with more than three live alerts is in
 * {@code DriverMirrorHalTest}. What is here is everything reachable without one: the constants that
 * define the cap, the policy on an empty registry, and — the part that is genuinely this domain's —
 * the <em>transport</em>, exercised through recording stand-ins for the two NetworkTables publishers
 * so the real {@code publishDriverAlerts()} body runs end to end.
 */
final class DriverMirrorTest {

  private final List<String[]> m_rows = new ArrayList<>();
  private final List<String> m_more = new ArrayList<>();

  @BeforeEach
  void installRecordingPublishers() {
    LogState.reset();
    AlertRegistry.resetForTest();
    LogState.install(LogState.quietDefaults());
    m_rows.clear();
    m_more.clear();
    setPublisher("m_driverBlocking", args -> m_rows.add((String[]) args[0]));
    setPublisher("m_driverBlockingMore", args -> m_more.add((String) args[0]));
  }

  @AfterEach
  void removeRecordingPublishers() {
    clearPublisher("m_driverBlocking");
    clearPublisher("m_driverBlockingMore");
    AlertRegistry.resetForTest();
    LogState.reset();
  }

  /**
   * Puts a recording stand-in into one of {@code PumpkinLog}'s private publisher fields.
   *
   * <p>Pre-seeding the field is what keeps the test alive: {@code publishDriverAlerts()} calls
   * {@code ntRoot()} only when the field is still null, so a non-null stand-in short-circuits the
   * one branch that would reach {@code NetworkTableInstance.getDefault()} and kill the JVM. The
   * publisher types are interfaces, and loading an interface runs no native initialiser, so a
   * {@link Proxy} over the field's own declared type is safe — the same technique, and the same
   * reasoning, as {@code org.pumpkinlib.testsupport.NtStubs}, which records nothing.
   */
  private static void setPublisher(String fieldName, java.util.function.Consumer<Object[]> onSet) {
    Field field = publisherField(fieldName);
    Class<?> type = field.getType();
    Object proxy =
        Proxy.newProxyInstance(
            DriverMirrorTest.class.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> {
              if (method.getName().equals("set") && args != null && args.length > 0) {
                onSet.accept(args);
              }
              return defaultValue(method.getReturnType());
            });
    write(field, proxy);
  }

  private static void clearPublisher(String fieldName) {
    write(publisherField(fieldName), null);
  }

  private static Field publisherField(String fieldName) {
    try {
      Field field = PumpkinLog.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      if (!field.getType().isInterface()) {
        throw new IllegalStateException(
            "DriverMirrorTest: PumpkinLog."
                + fieldName
                + " is a "
                + field.getType().getName()
                + ", which is not an interface and cannot be proxied. NetworkTables publishers are"
                + " interfaces; if this changed type the test would reach the real NT stack and the"
                + " JVM would exit with no diagnostic.");
      }
      return field;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "DriverMirrorTest: PumpkinLog has no field \"" + fieldName + "\"; it was renamed.", e);
    }
  }

  private static void write(Field field, Object value) {
    try {
      field.set(null, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("DriverMirrorTest: could not write " + field, e);
    }
  }

  private static Object defaultValue(Class<?> returnType) {
    if (!returnType.isPrimitive() || returnType == void.class) {
      return null;
    }
    if (returnType == boolean.class) {
      return false;
    }
    if (returnType == long.class) {
      return 0L;
    }
    if (returnType == double.class) {
      return 0.0d;
    }
    if (returnType == float.class) {
      return 0.0f;
    }
    if (returnType == char.class) {
      return (char) 0;
    }
    return 0;
  }

  @Nested
  @DisplayName("the cap is one number, in one place")
  final class TheCap {

    @Test
    void theMirrorShowsThreeRows() {
      assertEquals(3, PumpkinLog.kDriverAlertCap);
    }

    /**
     * The transport must not carry its own copy of the policy's number. Two constants that mean the
     * same thing drift, and the drift is invisible until a driver sees four rows and a "+0 more".
     */
    @Test
    void theTransportsCapIsTheRegistrysCapAndNotACopy() {
      assertEquals(AlertRegistry.kDriverDisplayCap, PumpkinLog.kDriverAlertCap);
    }

    /**
     * The cap must be strictly below the blocking budget, or a robot could reach the budget without
     * the driver tab ever overflowing — and the rollup row that makes the truncation visible would
     * never appear.
     */
    @Test
    void theCapIsStrictlyBelowTheAlertBudget() {
      assertTrue(PumpkinLog.kDriverAlertCap < AlertRegistry.kBlockingBudget);
    }

    /**
     * The mirror is exempt from the byte governor <em>entirely</em>, rather than merely left
     * unmarked. It is ~30 low-rate topics a human is reading while the robot is moving, so a governor
     * that thinned them under load would blank the panel at exactly the wrong moment.
     */
    @Test
    void everyDriverTopicIsPermanentlyExemptFromTheGovernor() {
      assertFalse(PumpkinBudget.isDemotionEligible("Pumpkin/" + PumpkinLog.kDriverBlockingTopic));
      assertFalse(
          PumpkinBudget.isDemotionEligible("Pumpkin/" + PumpkinLog.kDriverBlockingMoreTopic));
      assertFalse(PumpkinBudget.isDemotionEligible("Pumpkin/Driver/Anything/At/All"));
    }
  }

  @Nested
  @DisplayName("the topics the dashboard binds to")
  final class Topics {

    @Test
    void theyAreTheseLiteralsUnderThePumpkinRoot() {
      assertEquals("Pumpkin", PumpkinLog.kNtRoot);
      assertEquals("Driver/Blocking", PumpkinLog.kDriverBlockingTopic);
      assertEquals("Driver/BlockingMore", PumpkinLog.kDriverBlockingMoreTopic);
    }
  }

  @Nested
  @DisplayName("the transport")
  final class Transport {

    /** Nothing raised: an empty row array and an empty rollup, both written. */
    @Test
    void anEmptyRegistryPublishesNoRowsAndNoRollup() {
      PumpkinLog.publishDriverAlerts();

      assertEquals(1, m_rows.size());
      assertArrayEquals(new String[0], m_rows.get(0));
      assertEquals(List.of(""), m_more);
    }

    /**
     * Both topics are written on <em>every</em> call. Writing the rollup only when it is non-empty
     * would leave a stale {@code "+7 more"} on a driver's screen after the seven were fixed, which is
     * the mirror lying rather than the mirror being quiet.
     */
    @Test
    void bothTopicsAreWrittenEveryCycleAndNotOnlyOnChange() {
      PumpkinLog.publishDriverAlerts();
      PumpkinLog.publishDriverAlerts();
      PumpkinLog.publishDriverAlerts();

      assertEquals(3, m_rows.size());
      assertEquals(3, m_more.size());
      assertEquals(List.of("", "", ""), m_more);
    }

    /** The transport publishes the registry's rows verbatim; the policy is not re-implemented here. */
    @Test
    void theRowsPublishedAreTheRegistrysOwn() {
      PumpkinLog.publishDriverAlerts();

      assertArrayEquals(
          AlertRegistry.driverBlockingRows().toArray(new String[0]), m_rows.get(0));
      assertEquals(AlertRegistry.driverBlockingMore(), m_more.get(0));
    }

    /** With the mirror configured off, nothing is written at all — not even an empty array. */
    @Test
    void aDisabledMirrorPublishesNothing() {
      LogState.install(LogState.quietDefaults().withDriverMirror(false));

      PumpkinLog.publishDriverAlerts();

      assertTrue(m_rows.isEmpty());
      assertTrue(m_more.isEmpty());
    }

    /** And it is on by default, because a driver who has to enable the alert panel will not. */
    @Test
    void theMirrorIsOnByDefault() {
      assertTrue(LogConfig.defaults().driverMirror());
    }
  }

  @Nested
  @DisplayName("the policy on a robot with nothing raised")
  final class EmptyRegistryPolicy {

    @Test
    void thereAreNoRowsAndNoRollup() {
      assertTrue(AlertRegistry.driverBlockingRows().isEmpty());
      assertEquals("", AlertRegistry.driverBlockingMore());
    }

    /** True at every size, and the only size reachable without natives is zero. */
    @Test
    void theRowCountNeverExceedsTheCap() {
      assertTrue(AlertRegistry.driverBlockingRows().size() <= PumpkinLog.kDriverAlertCap);
    }

    /**
     * A PIT_ONLY alert must never reach the driver panel, whatever its severity. "Can this robot
     * play a match" is not "is everything perfect", and a driver panel that answers the second
     * question stops being read. Reachable without natives only in the negative direction — there is
     * nothing registered, so there is nothing to leak — with the populated case in
     * {@code DriverMirrorHalTest}.
     */
    @Test
    void nothingReachesTheDriverPanelFromAnEmptyRegistry() {
      assertTrue(AlertRegistry.blocking().isEmpty());
      assertTrue(AlertRegistry.driverBlockingRows().isEmpty());
      assertTrue(AlertRegistry.matchReady());
    }
  }
}

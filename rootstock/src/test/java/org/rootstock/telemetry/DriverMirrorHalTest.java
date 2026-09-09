package org.rootstock.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;

/**
 * The half of the {@code /Rootstock/Driver} mirror that needs live alerts: what happens when more than
 * {@value AlertRegistry#kDriverDisplayCap} alerts are blocking at once.
 *
 * <p>Tagged {@code hal} because building a {@code RootstockAlert} constructs a WPILib {@code Alert},
 * which touches NetworkTables. On a JVM without WPILib's JNI, WPILib's loader calls
 * {@code System.exit(1)} rather than throwing — no exception, no stack trace, the whole test JVM
 * gone — so this cannot live in the default {@code test} task. Run it with
 * {@code ./gradlew halTest} on a machine with the natives installed.
 *
 * <p>{@code DriverMirrorTest} carries everything reachable without them: the constants, the empty
 * registry, and the transport.
 */
@Tag("hal")
final class DriverMirrorHalTest {

  private final List<String[]> m_rows = new ArrayList<>();
  private final List<String> m_more = new ArrayList<>();
  private final List<RootstockAlert> m_alerts = new ArrayList<>();

  @BeforeEach
  void freshRegistry() {
    LogState.reset();
    AlertRegistry.resetForTest();
    LogState.install(LogState.quietDefaults());
    m_rows.clear();
    m_more.clear();
    m_alerts.clear();
    recordInto("m_driverBlocking", args -> m_rows.add((String[]) args[0]));
    recordInto("m_driverBlockingMore", args -> m_more.add((String) args[0]));
  }

  @AfterEach
  void clearRegistry() {
    m_alerts.forEach(RootstockAlert::close);
    write(publisherField("m_driverBlocking"), null);
    write(publisherField("m_driverBlockingMore"), null);
    AlertRegistry.resetForTest();
    LogState.reset();
  }

  /** Raises one blocking ERROR and keeps the handle so the test can close it. */
  private RootstockAlert blocking(String group, String text) {
    RootstockAlert alert = Alerts.error(group, text, MatchImpact.BLOCKS_MATCH);
    m_alerts.add(alert);
    alert.set(true);
    return alert;
  }

  @Test
  @DisplayName("five blocking alerts become three rows and one rollup")
  void theMirrorCapsAtThreeRowsAndSaysHowManyAreHidden() {
    blocking("Arm", "leader TalonFX 21 not responding on CAN");
    blocking("Elevator", "leader TalonFX 31 not responding on CAN");
    blocking("Intake", "absolute encoder reads 0.000 rot on every cycle");
    blocking("Drive", "module 2 azimuth not homed");
    blocking("Vision", "front camera offline");

    assertEquals(5, AlertRegistry.blocking().size(), "nothing is dropped from the registry");
    assertEquals(3, AlertRegistry.driverBlockingRows().size(), "the driver sees three");
    assertEquals("+2 more - see the pit tab", AlertRegistry.driverBlockingMore());

    RootstockLog.publishDriverAlerts();

    assertEquals(1, m_rows.size());
    assertEquals(3, m_rows.get(0).length, "the transport must carry exactly the capped rows");
    assertArrayEquals(
        AlertRegistry.driverBlockingRows().toArray(new String[0]), m_rows.get(0));
    assertEquals(List.of("+2 more - see the pit tab"), m_more);
    assertTrue(m_rows.get(0)[0].contains(": "), "rows are \"Group: message\"");
  }

  @Test
  @DisplayName("exactly three blocking alerts fit, so there is no rollup row")
  void atTheCapThereIsNoRollup() {
    blocking("Arm", "leader TalonFX 21 not responding on CAN");
    blocking("Elevator", "leader TalonFX 31 not responding on CAN");
    blocking("Intake", "absolute encoder reads 0.000 rot on every cycle");

    assertEquals(3, AlertRegistry.driverBlockingRows().size());
    assertEquals("", AlertRegistry.driverBlockingMore());

    RootstockLog.publishDriverAlerts();

    assertEquals(3, m_rows.get(0).length);
    assertEquals(List.of(""), m_more);
  }

  @Test
  @DisplayName("a PIT_ONLY alert never reaches the driver panel, however severe")
  void pitOnlyAlertsStayOutOfTheDriverPanel() {
    RootstockAlert pitOnly = Alerts.error("Log", "byte budget exceeded", MatchImpact.PIT_ONLY);
    m_alerts.add(pitOnly);
    pitOnly.set(true);

    assertTrue(AlertRegistry.driverBlockingRows().isEmpty());
    assertTrue(AlertRegistry.matchReady(), "PIT_ONLY does not stop a match");

    RootstockLog.publishDriverAlerts();

    assertArrayEquals(new String[0], m_rows.get(0));
    assertEquals(List.of(""), m_more);
  }

  // --- the same recording stand-ins DriverMirrorTest uses ----------------------------------------

  private static void recordInto(String fieldName, java.util.function.Consumer<Object[]> onSet) {
    Field field = publisherField(fieldName);
    Object proxy =
        Proxy.newProxyInstance(
            DriverMirrorHalTest.class.getClassLoader(),
            new Class<?>[] {field.getType()},
            (self, method, args) -> {
              if (method.getName().equals("set") && args != null && args.length > 0) {
                onSet.accept(args);
              }
              return defaultValue(method.getReturnType());
            });
    write(field, proxy);
  }

  private static Field publisherField(String fieldName) {
    try {
      Field field = RootstockLog.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "DriverMirrorHalTest: RootstockLog has no field \"" + fieldName + "\".", e);
    }
  }

  private static void write(Field field, Object value) {
    try {
      field.set(null, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("DriverMirrorHalTest: could not write " + field, e);
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
}

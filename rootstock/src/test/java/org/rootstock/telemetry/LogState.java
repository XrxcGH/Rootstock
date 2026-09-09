package org.rootstock.telemetry;

import java.lang.reflect.Field;
import org.rootstock.core.spi.LogConfig;

/**
 * Installs a {@link LogConfig} into {@link RootstockLog} without touching WPILib's natives, and puts
 * every telemetry static back to its boot state afterwards.
 *
 * <p><strong>Why {@code RootstockLog.configure(...)} cannot be called from a unit test.</strong>
 * {@code configure} resolves the run mode eagerly, and resolving it reaches
 * {@code Platform.isSimulation()} → {@code RobotBase.isSimulation()}, whose class initialiser needs
 * {@code edu.wpi.first.cameraserver.CameraServerShared}. That class is not on a library build's
 * classpath, so the call fails with {@code NoClassDefFoundError} before the config is ever stored.
 * (Verified, and it is a different failure from the HAL one: this throws and is catchable, whereas
 * anything reaching {@code DriverStation} or NetworkTables calls {@code System.exit(1)} and kills
 * the test JVM outright.)
 *
 * <p>On a robot none of that matters — the HAL is present and {@code configure} is the front door.
 * A unit test simply cannot use it, so the immutable config value is written straight into the
 * field {@code configure} would have written. Everything downstream — {@link RootstockLog#log},
 * {@link RootstockLog#effectiveMinimumTier()}, the byte governor — then runs completely unmodified,
 * which is the point: the gate under test is the real one.
 */
public final class LogState {

  private LogState() {}

  /**
   * Makes {@code RootstockLog} run under {@code config}, exactly as {@code configure} would.
   *
   * @param config the configuration to install
   */
  public static void install(LogConfig config) {
    try {
      Field field = RootstockLog.class.getDeclaredField("m_config");
      field.setAccessible(true);
      field.set(null, config);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "LogState: RootstockLog has no field \"m_config\". It was renamed, so this helper no longer"
              + " installs a configuration and every tier and budget test would silently run on"
              + " LogConfig.defaults(). Fix: update the field name here.",
          e);
    }
  }

  /**
   * A configuration whose log folders cannot exist, so the byte governor's framework-bucket sampler
   * finds no {@code .wpilog} file and honestly reports {@code unmeasured}.
   *
   * <p>Not fussiness: if the sampler ever found a real growing file, a measured growth smaller than
   * buckets A+B raises {@code BYTE_ACCOUNTING_OVERCOUNT}, which builds a {@code RootstockAlert}, which
   * needs NetworkTables, which kills the JVM here. Pointing the folders at nothing removes the whole
   * branch from the test's reach.
   *
   * @return a defaults-based config with unusable log folders
   */
  public static LogConfig quietDefaults() {
    return LogConfig.defaults()
        .withWpilogFolder("build/test-no-such-log-folder")
        .withFallbackFolder("build/test-no-such-fallback-folder");
  }

  /** Returns every telemetry and match static to its boot state. Call from {@code @AfterEach}. */
  public static void reset() {
    RootstockLog.resetForTest();
    FmsGate.clear();
  }
}

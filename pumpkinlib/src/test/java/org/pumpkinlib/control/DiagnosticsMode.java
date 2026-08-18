package org.pumpkinlib.control;

import java.lang.reflect.Field;
import org.pumpkinlib.core.match.MatchContext;

/**
 * Puts {@link MatchContext} into the exact driver-station state {@code TuningSupervisor.arm()}
 * demands, with no driver station in the room.
 *
 * <p><strong>Why reflection and not {@code MatchContext.periodic()}.</strong> Every accessor falls
 * through to a live {@code DriverStation} read until {@code periodic()} has run once —
 * {@code isEnabled()} is literally {@code s_periodicRan ? s_enabled : DriverStation.isEnabled()} —
 * and {@code periodic()} itself reads the driver station eleven times on its first line. A test
 * harness has no driver station attached, so calling it would latch "disabled, not in Test mode",
 * which is precisely the state {@code arm()} refuses. Setting {@code s_periodicRan} makes every
 * accessor read its cached field, and then the three booleans below are the whole of the state the
 * supervisor's preconditions consult.
 *
 * <p>This is the same technique, and the same justification, as {@code
 * org.pumpkinlib.telemetry.FmsGate}; it lives here rather than being reused because the supervisor
 * needs three more fields than the telemetry tier gate does, and a helper that reaches into a
 * different package's privates should say exactly which ones it touches.
 *
 * <p>Always {@link #clear()} in an {@code @AfterEach}: this is process-wide static state, and a test
 * that leaves the robot "enabled in Test mode" changes what the next test's {@code arm()} does.
 */
public final class DiagnosticsMode {

  private DiagnosticsMode() {}

  /**
   * Enabled, in Test mode, with no FMS — the only state in which raw-voltage tuning is legal.
   */
  public static void enterTestModeEnabled() {
    write("s_periodicRan", true);
    write("s_enabled", true);
    write("s_disabled", false);
    write("s_diagnostics", true);
    write("s_fmsAttached", false);
  }

  /**
   * Flips the driver station to disabled without touching anything else, so an already-armed
   * supervisor sees exactly the transition a student pressing the space bar produces.
   */
  public static void disable() {
    write("s_enabled", false);
    write("s_disabled", true);
  }

  /**
   * Reports an FMS, so {@code arm()}'s "never at an event" precondition can be exercised.
   *
   * @param attached whether an FMS is attached
   */
  public static void setFmsAttached(boolean attached) {
    write("s_fmsAttached", attached);
  }

  /**
   * Leaves Test mode while staying enabled, which is the teleop case {@code arm()} must refuse.
   */
  public static void leaveTestMode() {
    write("s_diagnostics", false);
  }

  /** Returns {@code MatchContext} to its boot state. Call from {@code @AfterEach}. */
  public static void clear() {
    MatchContext.resetForTest();
  }

  private static void write(String fieldName, boolean value) {
    try {
      Field field = MatchContext.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.setBoolean(null, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "DiagnosticsMode: MatchContext has no boolean field \""
              + fieldName
              + "\". It was renamed or removed, so this helper no longer latches the driver-station "
              + "state and every supervisor test would instead read a DriverStation that is not "
              + "there. Fix: update the field name here.",
          e);
    }
  }
}

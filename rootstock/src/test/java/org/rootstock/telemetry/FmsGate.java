package org.rootstock.telemetry;

import java.lang.reflect.Field;
import org.rootstock.core.match.MatchContext;

/**
 * Puts {@link MatchContext} into a chosen FMS-attached state without a driver station and without
 * WPILib's JNI natives.
 *
 * <p><strong>Why reflection and not {@code MatchContext.periodic()}.</strong> Every
 * {@code MatchContext} accessor falls through to a live {@code DriverStation} read until
 * {@code periodic()} has run once — {@code isFMSAttached()} is literally
 * {@code s_periodicRan ? s_fmsAttached : DriverStation.isFMSAttached()}. On a JVM with no WPILib
 * natives that fall-through does not throw: WPILib's loader prints {@code "wpiHaljni could not be
 * loaded from path"} and calls {@code System.exit(1)}, so the whole test JVM dies with no stack
 * trace naming the test that did it. Calling {@code periodic()} to set the latch is no better — it
 * reads the DriverStation eleven times on its first line.
 *
 * <p>So the latch is set directly. Setting {@code s_periodicRan} true is what makes every accessor
 * read its cached field instead of the HAL, and setting {@code s_fmsAttached} is then the whole of
 * the state the FMS tier gate reads. This is the smallest possible reach into another package's
 * privates, and it exercises the <em>real</em> {@code RootstockLog.effectiveMinimumTier()} —
 * including its real {@code MatchContext.isFMSAttached()} call — rather than a parallel
 * reimplementation of the gate.
 *
 * <p>Always restore with {@link #clear()} in an {@code @AfterEach}: the latch is process-wide static
 * state and a test that leaves the FMS attached silently changes what the next test's tier gate
 * does.
 */
public final class FmsGate {

  private FmsGate() {}

  /**
   * Latches {@code MatchContext} as FMS-attached, with no DriverStation involved.
   *
   * @param attached true to report an FMS, false to report none
   */
  public static void set(boolean attached) {
    write("s_fmsAttached", attached);
    write("s_periodicRan", true);
  }

  /**
   * Returns {@code MatchContext} to its boot state, so the next test starts with no latch.
   *
   * <p>{@code MatchContext.resetForTest()} is the library's own reset and does exactly this; it is
   * called here rather than duplicated so this helper cannot drift from it.
   */
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
          "FmsGate: MatchContext has no boolean field \""
              + fieldName
              + "\". It was renamed or removed, so this helper no longer latches the FMS state and "
              + "every tier-gate test would instead reach DriverStation and kill the JVM. Fix: "
              + "update the field name here.",
          e);
    }
  }
}

package org.rootstock.core.match;

import edu.wpi.first.wpilibj.DriverStation;

/**
 * The single place Rootstock decides what is unsafe at an event.
 *
 * <p>Everything in the library that has an "off at a real match" behaviour asks here rather than
 * inventing its own FMS check, so there is exactly one answer to "why did my dashboard slider stop
 * doing anything?" and it is documented.
 *
 * <p><b>The disabled path is constant time and must stay that way.</b> {@link #tunablesLocked()} is
 * consulted on <b>every</b> tunable read in the library — thousands per second on a robot with a
 * hundred tunables. It costs two {@code volatile} boolean reads and a branch. A lockout that costs a
 * map lookup per read is a lockout somebody eventually removes for performance, and then loses a
 * match to a stray dashboard edit. That failure is the whole reason this class exists.
 *
 * <p><b>Default-deny, with a loud escape hatch.</b> The overrides
 * ({@link #allowTunablesAtEvent(boolean)}, {@link #allowVerboseTelemetryAtEvent(boolean)}) exist
 * because "the library will not let me fix my robot in the queue line" is a worse failure than the
 * one being prevented. They report a warning through the driver station on every call, so the
 * decision is in the log next to whatever happened afterwards.
 */
public final class FmsPolicy {

  private FmsPolicy() {}

  private static volatile boolean s_tunablesAllowedAtEvent;

  private static volatile boolean s_verboseTelemetryAllowedAtEvent;

  /**
   * Whether live tuning is locked out right now.
   *
   * <p>True when the FMS is attached, unless {@link #allowTunablesAtEvent(boolean)} has been called
   * with {@code true}. A tunable that is locked returns its last committed value and ignores
   * dashboard writes entirely — it does not merely stop publishing.
   *
   * @return true when tunable reads must ignore live edits
   */
  public static boolean tunablesLocked() {
    return MatchContext.isFMSAttached() && !s_tunablesAllowedAtEvent;
  }

  /**
   * Whether {@code SelfTest.runAll()} may schedule.
   *
   * <p>False only when the FMS is attached <i>and</i> the robot is enabled — i.e. during a match. A
   * self-test while disabled on the field is legal, because that is exactly when a team wants to
   * check a mechanism in the queue line.
   *
   * @return false only while FMS-attached and enabled
   */
  public static boolean selfTestAllowed() {
    return !(MatchContext.isFMSAttached() && MatchContext.isEnabled());
  }

  /**
   * Whether per-signal verbose (DEBUG-tier) telemetry may be emitted.
   *
   * <p>False when FMS-attached, unless {@link #allowVerboseTelemetryAtEvent(boolean)} has been
   * called with {@code true}. Verbose telemetry at an event is a bandwidth and loop-time cost paid
   * for data nobody looks at until the drive home.
   *
   * @return true when verbose telemetry is permitted
   */
  public static boolean verboseTelemetryAllowed() {
    return !MatchContext.isFMSAttached() || s_verboseTelemetryAllowedAtEvent;
  }

  /**
   * Unlock live tuning while FMS-attached. Loud, logged, and off by default.
   *
   * <p>Call this and a dashboard edit can change robot behaviour mid-match. That is occasionally the
   * right call — a kG that is 10% off and a match starting in four minutes — and it is never the
   * default. Every call reports a driver-station warning naming the state it just entered, so the
   * decision is visible in the log beside whatever happened next.
   *
   * @param yesReally true to permit tuning at an event; false to restore the default lockout
   */
  public static void allowTunablesAtEvent(boolean yesReally) {
    s_tunablesAllowedAtEvent = yesReally;
    if (yesReally) {
      DriverStation.reportWarning(
          "FmsPolicy: live tuning has been UNLOCKED while FMS-attached. Dashboard edits can now"
              + " change robot behaviour mid-match. This is off by default for a reason."
              + " Fix: call FmsPolicy.allowTunablesAtEvent(false) as soon as you are done.",
          false);
    } else {
      DriverStation.reportWarning(
          "FmsPolicy: live tuning re-locked for FMS-attached operation (the default).", false);
    }
  }

  /**
   * Permit verbose telemetry while FMS-attached. Loud, logged, and off by default.
   *
   * <p>The design specifies verbose telemetry as "false when FMS-attached, unless overridden"; this
   * is the override. It is a separate switch from {@link #allowTunablesAtEvent(boolean)} because the
   * two risks are unrelated — extra logging cannot move a mechanism.
   *
   * @param yesReally true to permit DEBUG-tier logging at an event; false to restore the default
   */
  public static void allowVerboseTelemetryAtEvent(boolean yesReally) {
    s_verboseTelemetryAllowedAtEvent = yesReally;
    DriverStation.reportWarning(
        "FmsPolicy: verbose telemetry at an event is now "
            + (yesReally ? "ALLOWED (costs bandwidth and loop time)" : "DENIED (the default)")
            + ".",
        false);
  }

  /**
   * Whether the tunable lockout has been explicitly overridden.
   *
   * <p>Exposed so the alert domain can raise a standing "tuning is unlocked at an event" alert
   * without having to remember the override itself.
   *
   * @return true when {@link #allowTunablesAtEvent(boolean)} was last called with true
   */
  public static boolean tunablesAllowedAtEvent() {
    return s_tunablesAllowedAtEvent;
  }

  /**
   * Whether the verbose-telemetry lockout has been explicitly overridden.
   *
   * @return true when {@link #allowVerboseTelemetryAtEvent(boolean)} was last called with true
   */
  public static boolean verboseTelemetryAllowedAtEvent() {
    return s_verboseTelemetryAllowedAtEvent;
  }

  /** Restores both overrides to their default-deny state. For tests only. */
  public static void resetForTest() {
    s_tunablesAllowedAtEvent = false;
    s_verboseTelemetryAllowedAtEvent = false;
  }

  /**
   * One line explaining every gate this class controls and why it is where it is.
   *
   * @return a human-readable summary of the current policy
   */
  public static String describe() {
    return "FmsPolicy: FMS="
        + MatchContext.isFMSAttached()
        + " -> tunablesLocked="
        + tunablesLocked()
        + (s_tunablesAllowedAtEvent ? " (OVERRIDDEN)" : "")
        + ", selfTestAllowed="
        + selfTestAllowed()
        + ", verboseTelemetryAllowed="
        + verboseTelemetryAllowed()
        + (s_verboseTelemetryAllowedAtEvent ? " (OVERRIDDEN)" : "");
  }
}

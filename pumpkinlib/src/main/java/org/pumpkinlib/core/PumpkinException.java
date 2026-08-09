package org.pumpkinlib.core;

import java.util.Objects;

/**
 * The one exception type PumpkinLib throws, and it is thrown from very few places.
 *
 * <p><b>Read this before adding a {@code throw}.</b> PumpkinLib's failure policy (design/01 §5.6,
 * principle 5 — <i>degrade, never crash</i>) is that a wrong <i>value</i> is a collected
 * {@link SafeMode.Fault}, not an exception: the robot boots, connects, publishes telemetry, refuses
 * to move, and prints a sentence. An {@code ExceptionInInitializerError} out of
 * {@code frc.robot.RobotConfig.<clinit>} at 11 p.m. before a competition is the exact failure mode
 * the whole config system exists to prevent. ArchUnit rule 11 enforces the boundary mechanically:
 * no explicit {@code throw} in {@code org.pumpkinlib.mechanism..} or {@code org.pumpkinlib.hardware..}
 * outside constructors, static factories and {@code Validation}.
 *
 * <p>So this type is for the cases that are <b>not</b> a wrong value:
 *
 * <ul>
 *   <li>A <b>programming error in the caller</b> that cannot be expressed as a robot that runs
 *       badly — a null where the API documents "no nulls in public APIs", a builder finished twice,
 *       a name that must be unique and is not.
 *   <li>A <b>library invariant</b> that has already been violated by the time it is noticed, where
 *       continuing would silently produce wrong numbers rather than visibly wrong behaviour.
 * </ul>
 *
 * <p>It is a {@link RuntimeException} deliberately. A checked exception on a config builder would
 * force {@code try}/{@code catch} into every {@code RobotConfig} field initialiser, and the thing a
 * student would then write is {@code catch (Exception e) {}}.
 *
 * <p><b>Message shape.</b> Every message names the thing, the value, the expectation and the fix, in
 * that order — see {@link #of(String, String, String, String)}. A message that says
 * {@code "invalid argument"} is a bug in this library, not a bug report about the robot.
 */
public class PumpkinException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message written for a student reading it on a riolog at 11 p.m.
   *
   * @param message what went wrong, what the value was, what was expected, and how to fix it
   */
  public PumpkinException(String message) {
    super(message);
  }

  /**
   * Creates an exception wrapping an underlying failure.
   *
   * @param message what went wrong, what the value was, what was expected, and how to fix it
   * @param cause the underlying failure, which is usually a vendor or JDK exception
   */
  public PumpkinException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Builds the library's standard four-part message: thing, value, expectation, fix.
   *
   * <p>The rendering is deliberately multi-line and deliberately boring:
   *
   * <pre>
   * PumpkinLib: ControlMap role "driver"
   *   value    port 0
   *   expected a port not already claimed by another ControlMap
   *   fix      give the second map its own port, or reuse ControlMap.of("driver", hid)
   * </pre>
   *
   * @param thing what the value belongs to, named the way the caller named it
   * @param value the offending value, rendered the way the caller wrote it
   * @param expected the expectation, stated as a range or a rule rather than as "valid"
   * @param fix the concrete edit that makes the problem go away
   * @return the exception, ready to throw
   */
  public static PumpkinException of(String thing, String value, String expected, String fix) {
    Objects.requireNonNull(thing, "PumpkinException.of: thing must not be null");
    Objects.requireNonNull(value, "PumpkinException.of: value must not be null");
    Objects.requireNonNull(expected, "PumpkinException.of: expected must not be null");
    Objects.requireNonNull(fix, "PumpkinException.of: fix must not be null");
    return new PumpkinException(
        String.format(
            "PumpkinLib: %s%n  value    %s%n  expected %s%n  fix      %s", thing, value, expected,
            fix));
  }
}

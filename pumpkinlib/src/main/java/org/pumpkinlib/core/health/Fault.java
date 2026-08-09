package org.pumpkinlib.core.health;

import org.pumpkinlib.core.alert.Severity;

/**
 * One observed problem, as reported by a {@link HealthSource}. Immutable, and produced fresh on
 * every poll — a fault is a statement about <em>now</em>, not a latched object you keep and mutate.
 *
 * <p><strong>A fault is not an alert.</strong> Faults are the diagnostic channel: they are what
 * {@code /Pumpkin/Health/&lt;Source&gt;/Faults} publishes, what {@code expectNoNewFaults()} in a
 * self-test compares against, and what {@link RobotHealth#active()} lists. Alerts are the
 * <em>driver</em> channel and carry a {@link org.pumpkinlib.core.alert.MatchImpact}, which is a
 * different question ("does this stop us taking the field?") that only the author of the check can
 * answer. {@link HealthMonitor} mirrors faults into alerts for sources that do not raise their own.
 *
 * @param device what is broken, named the way it is named on the robot and in the code, e.g.
 *     {@code "Arm/leader (TalonFX 21)"}. Prefix with the source's {@link HealthSource#healthName()}
 *     and a {@code /} so {@link HealthMonitor#expectAbsent(String, String)} can match by prefix
 * @param description what is wrong, in the form the library requires everywhere: name the thing,
 *     the value, the expected range, and the fix. A student reads this at 11pm, e.g. {@code "device
 *     temperature 84.2 C exceeds warn limit 70.0 C - check the gearbox for a jam"}
 * @param severity how bad it is; see {@link Severity}
 * @param sticky {@code true} when the condition was latched by the <em>device</em> across boots (a
 *     Phoenix sticky fault, say) rather than being observed live. Sticky faults from a previous
 *     match are surfaced immediately at startup and are never auto-cleared, because that is how you
 *     catch a fault that happened in the match you just lost
 */
public record Fault(String device, String description, Severity severity, boolean sticky) {

  /**
   * Validating canonical constructor.
   *
   * @throws IllegalArgumentException if {@code device} or {@code description} is null or blank, or
   *     {@code severity} is null
   */
  public Fault {
    if (device == null || device.isBlank()) {
      throw new IllegalArgumentException(
          "Fault.device is blank. Name the physical thing, e.g. \"Arm/leader (TalonFX 21)\" - "
              + "a fault nobody can locate is a fault nobody fixes.");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException(
          "Fault.description is blank for device \""
              + device
              + "\". Say the thing, the value, the expected range and the fix.");
    }
    if (severity == null) {
      throw new IllegalArgumentException(
          "Fault.severity is null for device \"" + device + "\". Use Severity.INFO/WARNING/ERROR.");
    }
  }

  /**
   * A stable identity for this fault, ignoring severity and stickiness.
   *
   * <p>Used as the map key that keeps one mirrored alert per distinct problem, and as the element
   * of {@link HealthMonitor#faultFingerprints()} that {@code expectNoNewFaults()} diffs. Two polls
   * that observe the same problem produce equal fingerprints even though they produce different
   * {@code Fault} instances.
   *
   * @return {@code device + " | " + description}
   */
  public String fingerprint() {
    return device + " | " + description;
  }

  /**
   * A copy of this fault at {@link Severity#INFO}, used by {@link HealthMonitor#expectAbsent(String,
   * String)} to relocate an expected-absent device's faults out of the alert panel without deleting
   * them.
   *
   * @param reason why the hardware is knowingly not installed; appended to the description so the
   *     boot dump and the pit tab both carry it
   * @return a demoted copy; stickiness is preserved
   */
  public Fault demotedTo(String reason) {
    return new Fault(device, description + " [expected absent: " + reason + "]", Severity.INFO, sticky);
  }

  /**
   * One human-readable line for the console, the pit printout and {@code
   * /Pumpkin/Health/&lt;Source&gt;/Faults}.
   *
   * @return e.g. {@code "ERROR  Arm/leader (TalonFX 21): not responding on CAN (sticky)"}
   */
  public String describe() {
    return severity + "  " + device + ": " + description + (sticky ? " (sticky)" : "");
  }
}

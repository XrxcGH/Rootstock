package org.rootstock.core.health;

import org.rootstock.core.alert.Severity;

/**
 * The sink handed to {@link HealthSource#pollHealth(FaultCollector)}.
 *
 * <p>It exists so a source can report a fault <em>without allocating a list per poll</em>: the
 * collector is owned and reused by {@link HealthMonitor}, so a healthy robot — the normal case, one
 * poll per loop at 50 Hz for the life of the match — allocates nothing at all. Do not retain the
 * collector past the end of your {@code pollHealth} call; it is refilled for the next source.
 *
 * <p>The three convenience methods are the three things a check actually says. Use them rather than
 * building a {@link Fault} by hand:
 *
 * <pre>{@code
 * public void pollHealth(FaultCollector out) {
 *   if (!m_leader.isConnected()) {
 *     out.error("Arm/leader (TalonFX 21)",
 *               "not responding on CAN - check power, the CAN chain, and the ID in ArmConfig");
 *   }
 * }
 * }</pre>
 */
public interface FaultCollector {

  /**
   * Report a fault.
   *
   * @param f the fault; never null
   */
  void add(Fault f);

  /**
   * Report an {@link Severity#ERROR} fault: something is broken now.
   *
   * @param device what is broken, e.g. {@code "Arm/leader (TalonFX 21)"}
   * @param description the thing, the value, the expected range and the fix
   */
  default void error(String device, String description) {
    add(new Fault(device, description, Severity.ERROR, false));
  }

  /**
   * Report a {@link Severity#WARNING} fault: degraded, still playable.
   *
   * @param device what is degraded
   * @param description the thing, the value, the expected range and the fix
   */
  default void warn(String device, String description) {
    add(new Fault(device, description, Severity.WARNING, false));
  }

  /**
   * Report a fault the <em>device</em> latched across boots — a vendor sticky fault.
   *
   * <p>Reported at {@link Severity#WARNING} because the condition may no longer be true, and marked
   * {@link Fault#sticky()} so nothing auto-clears it. A sticky fault from the previous match is the
   * single highest-value thing on the pit screen.
   *
   * @param device the device that latched it
   * @param description which sticky fault, and how to clear it once it is understood
   */
  default void sticky(String device, String description) {
    add(new Fault(device, description, Severity.WARNING, true));
  }

  /**
   * Report an {@link Severity#INFO} fault: worth writing down, not worth worrying about.
   *
   * <p>Not in the original design sketch; added because expected-absent hardware and provenance
   * notes need a channel that is visible in the log and the pit tab but can never be mistaken for a
   * problem.
   *
   * @param device what the note is about
   * @param description the note
   */
  default void info(String device, String description) {
    add(new Fault(device, description, Severity.INFO, false));
  }
}

package org.pumpkinlib.config;

import org.pumpkinlib.core.PumpkinRegistry;

/**
 * The four automatic registration facets a mechanism config can opt <em>out</em> of.
 *
 * <p><b>Why an opt-out and not an opt-in.</b> {@code PumpkinRegistry.addAll(m_drive, m_elevator,
 * m_arm)} is the one registration call in the library (D27). It routes each argument by
 * {@code instanceof}: a {@code TelemetrySource} goes to telemetry, a {@code HealthSource} to the
 * health monitor, a {@code SelfTestable} to {@code SelfTest}, a {@code TuningTarget} to
 * {@code TuningRegistry}. Revision 1 had four parallel public registration lists over the same
 * objects with different membership for reasons nobody could reconstruct — inside the very example
 * that advertised "ONE list". Collapsing them means the default is "everything", and the only way
 * to say "not this one" is to say it <em>in the config</em>, where {@code describe()} prints it.
 *
 * <p>That is the whole point: an opt-in is invisible when it is missing (a mechanism that is simply
 * absent from the health sweep looks identical to one nobody thought about), whereas an opt-out is a
 * line in the boot dump that a CSA can read in the queue line.
 *
 * <p>Excluding a facet is a real decision with a real cost, so each constant documents what stops
 * happening.
 */
public enum Registry {

  /**
   * Logging under {@code /Pumpkin/&lt;Mechanism&gt;/}.
   *
   * <p>Excluding this means the mechanism does not appear in AdvantageScope, does not appear in a
   * replay, and cannot be diagnosed after a match from the log alone. Exclude only for a mechanism
   * whose telemetry you publish yourself.
   */
  TELEMETRY(PumpkinRegistry.kRouteTelemetry, "logging under /Pumpkin/<name>/"),

  /**
   * The round-robin health sweep and the alerts it raises.
   *
   * <p>Excluding this means a disconnected motor, a stale signal or an over-temperature device on
   * this mechanism will not reach the driver mirror. Exclude only for a mechanism you have decided
   * cannot affect a match.
   */
  HEALTH(PumpkinRegistry.kRouteHealth, "the round-robin health sweep"),

  /**
   * The one-button pit self test.
   *
   * <p>Excluding this means the mechanism is not exercised by the pre-match check, so a wiring or
   * gearing fault on it is discovered on the field instead of in the pit.
   */
  SELFTEST(PumpkinRegistry.kRouteSelfTest, "the one-button pit self test"),

  /**
   * Live gain and constraint tuning over NetworkTables.
   *
   * <p>Excluding this means sliders for this mechanism never appear, which is occasionally what you
   * want for a mechanism whose gains are settled and whose CAN budget is tight. Note that tunables
   * are already inert when the FMS is attached, so this is not the way to make a robot
   * competition-safe — that is automatic.
   */
  TUNING(PumpkinRegistry.kRouteTuning, "live gain and constraint tuning");

  private final String m_routeLabel;
  private final String m_whatItDoes;

  Registry(String routeLabel, String whatItDoes) {
    m_routeLabel = routeLabel;
    m_whatItDoes = whatItDoes;
  }

  /**
   * The routing label {@code PumpkinRegistry} uses for this facet.
   *
   * <p>Tied to the {@code PumpkinRegistry.kRoute*} constants at compile time so the enum and the
   * router cannot drift apart silently.
   *
   * @return the route label, for example {@code "health"}
   */
  public String routeLabel() {
    return m_routeLabel;
  }

  /**
   * One clause naming what a team gives up by excluding this facet, for {@code describe()}.
   *
   * @return a human-readable sentence fragment
   */
  public String describe() {
    return name() + " (" + m_whatItDoes + ")";
  }
}

package org.pumpkinlib.control;

import org.pumpkinlib.units.SiDomain;

/**
 * Exactly one identifier per component of {@link Gains} — seven, no more.
 *
 * <p><b>Why this enum exists at all:</b> the NetworkTables tuning schema, the persisted tuned-value
 * file and the wizard's slider list are all <em>generated</em> from {@code GainId.values()} rather
 * than typed out. A gain that can be published to a dashboard but not applied back to the record is
 * the failure mode this deletes: {@link Gains#with(GainId, double)} and {@link Gains#get(GainId)}
 * are total functions over this enum, so the schema physically cannot drift ahead of the record.
 *
 * <p>Revision 3 of the tuning design carried twelve ids, because {@code Gains} carried twelve
 * components. Binding decision <b>D1a</b> moved gravity mode, motion constraints, tolerance and the
 * integral-windup guard onto {@code ControlConfig} — where a mechanism's <em>policy</em> belongs —
 * and this enum went back to seven. A tuner writes gains; it does not write policy.
 */
public enum GainId {
  /** Static friction, in volts. The voltage needed to just barely start the mechanism moving. */
  KS("kS", "V"),

  /** Velocity gain, in volts per (unit/second). The dominant feedforward term. */
  KV("kV", "V/(unit/s)"),

  /** Acceleration gain, in volts per (unit/second^2). */
  KA("kA", "V/(unit/s^2)"),

  /**
   * Gravity gain, in volts. For {@link GravityMode#CONSTANT} it is applied unconditionally; for
   * {@link GravityMode#COSINE} it is multiplied by {@code cos(position - horizontalReference)}.
   */
  KG("kG", "V"),

  /** Proportional feedback gain, in volts per unit of error. */
  KP("kP", "V/unit"),

  /** Integral feedback gain, in volts per (unit * second) of accumulated error. */
  KI("kI", "V/(unit*s)"),

  /** Derivative feedback gain, in volts per (unit/second) of error rate. */
  KD("kD", "V/(unit/s)");

  private final String m_key;
  private final String m_unit;

  GainId(String key, String unit) {
    m_key = key;
    m_unit = unit;
  }

  /**
   * The NetworkTables topic name and the persisted-file key for this gain.
   *
   * <p>It is deliberately the same string in both places, and the same string a student sees in the
   * WPILib documentation, so that copying a number out of a log and into source involves no
   * translation step.
   *
   * @return {@code "kP"}, {@code "kS"} and so on
   */
  public String key() {
    return m_key;
  }

  /**
   * The unit of this gain with the SI unit left as the literal word {@code "unit"}.
   *
   * <p>Useful for documentation and for a domain-agnostic schema dump. Prefer {@link
   * #unitFor(SiDomain)} anywhere a human will read it, because "V/unit" teaches nothing and "V/m"
   * teaches the whole idea.
   *
   * @return e.g. {@code "V/(unit/s)"}
   */
  public String unitTemplate() {
    return m_unit;
  }

  /**
   * The unit of this gain for a concrete mechanism domain, ready to print next to the number.
   *
   * <p>This is the one place the volts-per-SI convention becomes visible to a student: the same kP
   * that reads {@code 128.0 V/m} on an elevator reads {@code 7.0 V/rad} on an arm, and neither
   * number is a vendor's opinion.
   *
   * @param domain the mechanism's SI domain
   * @return e.g. {@code "V/m"} for {@link SiDomain#LINEAR_METERS}, {@code "V/rad"} for {@link
   *     SiDomain#ROTATIONAL_RADIANS}
   */
  public String unitFor(SiDomain domain) {
    String si = domain == SiDomain.LINEAR_METERS ? "m" : "rad";
    return m_unit.replace("unit", si);
  }
}

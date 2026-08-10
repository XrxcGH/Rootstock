package org.pumpkinlib.control;

import java.util.Objects;

/**
 * Whether a mechanism's {@link ControlLocation} was chosen by the team or filled in by PumpkinLib.
 *
 * <p>"Required" and "visible" are different properties, and this enum is what buys the second one
 * without paying for the first. Making {@code .controlLocation(...)} a required builder call put an
 * unanswerable expert question on line five of a rookie's first config. Removing it entirely would
 * hide a decision that changes what every gain means by up to 360x. So the builder defaults it, and
 * the boot dump prints it <b>unconditionally, with provenance</b>:
 *
 * <pre>
 *   ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs
 *                       Motion Magic on the device at 1 kHz).  To change it: .controlLocation(...)
 *
 *   ControlLocation     RIO_FULL (explicit -- you called .controlLocation(RIO_FULL)).
 *                       On a TalonFX this disables Motion Magic and the 1 kHz on-motor loop.
 * </pre>
 *
 * <p>A silent default here is a gain error waiting to happen, so there is no silent default: there
 * is a printed one.
 */
public enum ControlLocationSource {
  /** The team called {@code .controlLocation(...)} and meant it. */
  EXPLICIT,

  /** PumpkinLib filled it in from the leader motor's capabilities. */
  DEFAULTED;

  /**
   * True when PumpkinLib chose this location rather than the team.
   *
   * <p>Used to decide whether a "this is a downgrade" alert is worth raising: telling a team their
   * explicit choice has a cost is useful, telling them our own default has a cost is noise.
   *
   * @return true for {@link #DEFAULTED}
   */
  public boolean isDefaulted() {
    return this == DEFAULTED;
  }

  /**
   * The provenance line for the boot dump: the location, this source, and the reason.
   *
   * <p>The reason is supplied by the caller because only the config layer knows what the leader
   * motor is. For {@link #EXPLICIT} the reason is usually just the call the team made; for {@link
   * #DEFAULTED} it must say <em>why</em> — a default without a why is the thing this enum exists to
   * prevent.
   *
   * @param location the location that was chosen
   * @param reason the why, e.g. {@code "your leader is a TalonFX"} or {@code "you called
   *     .controlLocation(RIO_FULL)"}
   * @return e.g. {@code "ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs Motion
   *     Magic on the device at 1 kHz)"}
   */
  public String describe(ControlLocation location, String reason) {
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(reason, "reason");
    String tag = this == EXPLICIT ? "explicit" : "defaulted";
    return location.name()
        + " ("
        + tag
        + " -- "
        + reason
        + ", so "
        + location.explanation()
        + ")"
        + (this == DEFAULTED ? ".  To change it: .controlLocation(...)" : ".");
  }
}

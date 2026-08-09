package org.pumpkinlib.core.match;

/**
 * What the robot is doing right now, as one value instead of five booleans.
 *
 * <p><b>Why an enum and not the five {@code DriverStation} booleans.</b> {@code isAutonomous()} is
 * true whenever the DS <i>mode selector</i> says autonomous — including while disabled — and
 * {@code isTest()} is true in the DS's Test tab whether or not anything is enabled. Code that
 * switches on the raw booleans therefore gets the precedence wrong, silently, in the one situation
 * (disabled-in-auto, on the field, waiting for the match to start) where getting it wrong is most
 * expensive. {@link MatchContext#phase()} applies the precedence exactly once, here, and every
 * caller in the library reads the answer.
 *
 * <p><b>The precedence, stated so you never have to guess:</b> {@link #ESTOPPED} beats everything,
 * then {@link #DIAGNOSTICS}, then {@link #DISABLED}, then {@link #AUTONOMOUS}, then {@link #TELEOP}.
 * E-stop first because an e-stopped robot is not "disabled in teleop", it is e-stopped, and an LED
 * table or a log filter that says otherwise is lying to the person holding the button.
 * {@code DIAGNOSTICS} before {@code DISABLED} because the whole point of the Test tab is that you
 * run pit checks there while the robot is not enabled.
 *
 * @see MatchContext#phase()
 */
public enum MatchPhase {
  /** Not enabled, not in the Test tab, not e-stopped. The state a robot spends most of its life in. */
  DISABLED,

  /** Enabled with the DS mode selector on Autonomous. */
  AUTONOMOUS,

  /** Enabled with the DS mode selector on Teleoperated. */
  TELEOP,

  /**
   * The DS's "Test" tab in 2026, renamed "Utility" in 2027. PumpkinLib calls it
   * <i>diagnostics</i> in every spelling it owns so that the 2027 rename touches
   * {@link MatchContext} and nothing else.
   */
  DIAGNOSTICS,

  /** Emergency-stopped. Nothing moves until the robot is rebooted or the DS clears it. */
  ESTOPPED;

  /**
   * Whether the robot is actually able to command actuators in this phase.
   *
   * <p>{@link #DIAGNOSTICS} answers {@code false} here even though a self-test may well be driving
   * a motor: diagnostics is not a match phase and treating it as "enabled" is how a pit routine
   * ends up in a match log labelled teleop.
   *
   * @return true for {@link #AUTONOMOUS} and {@link #TELEOP} only
   */
  public boolean isEnabledPhase() {
    return this == AUTONOMOUS || this == TELEOP;
  }

  /**
   * A short human label for dashboards and log keys.
   *
   * @return the enum name in Title Case, e.g. {@code "Autonomous"}
   */
  public String label() {
    String n = name();
    return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT);
  }
}

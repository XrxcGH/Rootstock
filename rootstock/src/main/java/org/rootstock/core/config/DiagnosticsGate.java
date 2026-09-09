package org.rootstock.core.config;

import java.util.function.BooleanSupplier;

/**
 * The "is it safe to do a pit-only thing right now?" gate.
 *
 * <p>Two Rootstock buttons mutate persistent robot state: {@code RobotIdentity.assignCommand(id)}
 * writes the robot's identity file, and {@link ConfigSnapshot#takeCommand()} writes a tuned-value
 * snapshot. The design gates both on {@code MatchContext.isDiagnostics()} so they are safe to leave
 * bound to a controller button all season.
 *
 * <p><b>This class exists because {@code core.config} may not read {@code DriverStation}.</b>
 * ArchUnit rule 10 gives {@code edu.wpi.first.wpilibj.DriverStation} exactly one reader in the whole
 * library — {@code org.rootstock.core.match.MatchContext} — and that rule is absolute, because it
 * is the single file the 2027 driver-station surface change has to touch. So the gate is
 * <i>injected</i>: {@code MatchContext} calls {@code DiagnosticsGate.install(MatchContext::isDiagnostics)}
 * during lifecycle init, exactly as {@code Clock.setSource(DoubleSupplier)} lets the test kit
 * replace the clock without {@code Clock} knowing what a test is.
 *
 * <p><b>The default is permissive, and that is deliberate.</b> With no gate installed
 * {@link #allowed()} returns true. The alternative — refusing until {@code MatchContext} has wired
 * itself — means a team on the vendordep-only path presses the pit button, nothing happens, and no
 * message explains why. A robot that has not initialised its match context is, by construction, not
 * in a match.
 */
public final class DiagnosticsGate {

  private static volatile BooleanSupplier s_gate;

  private DiagnosticsGate() {}

  /**
   * Installs the real gate. Called once by {@code MatchContext} during lifecycle init.
   *
   * @param gate returns true when the robot is in a state where pit-only operations are allowed —
   *     in practice {@code DriverStation.isTest()}
   * @throws IllegalArgumentException if {@code gate} is null; use {@link #reset()} to uninstall
   */
  public static void install(BooleanSupplier gate) {
    if (gate == null) {
      throw new IllegalArgumentException(
          "DiagnosticsGate.install(gate): gate was null. Pass MatchContext::isDiagnostics, or call"
              + " DiagnosticsGate.reset() to go back to the permissive default.");
    }
    s_gate = gate;
  }

  /**
   * Whether pit-only operations are currently allowed.
   *
   * <p>Never throws: a gate supplier that itself throws is treated as "not allowed", because a
   * broken gate must not take out the command that consulted it.
   *
   * @return true if the installed gate says yes, or if no gate is installed
   */
  public static boolean allowed() {
    BooleanSupplier gate = s_gate;
    if (gate == null) {
      return true;
    }
    try {
      return gate.getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Whether a real gate has been installed.
   *
   * @return true once {@link #install(BooleanSupplier)} has run
   */
  public static boolean isInstalled() {
    return s_gate != null;
  }

  /** Uninstalls the gate, restoring the permissive default. Test and teardown entry point. */
  public static void reset() {
    s_gate = null;
  }

  /**
   * One sentence explaining the current gate state, for the message a blocked button prints.
   *
   * @return a human description naming who installed the gate, if anyone
   */
  public static String describe() {
    return s_gate == null
        ? "No diagnostics gate is installed, so pit-only operations are allowed."
        : "The diagnostics gate (installed by MatchContext) currently says "
            + allowed()
            + "; switch the driver station to Test mode.";
  }
}

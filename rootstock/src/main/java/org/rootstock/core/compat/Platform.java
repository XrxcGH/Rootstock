package org.rootstock.core.compat;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Platform facts whose source changes with roboRIO → SystemCore.
 *
 * <p>Nothing else in Rootstock may call {@code RobotBase.isSimulation()},
 * {@code RobotController.*} or {@code Filesystem.*} — ArchUnit rule 2 confines all three to this
 * package and to {@code org.rootstock.field}. That is what makes the 2027 rename an import rewrite
 * in one file rather than a search across forty.
 *
 * <p><b>There is deliberately no {@code Platform.diagnosticsMode()}.</b> Test/Utility mode is a
 * {@code DriverStation} read, and {@code DriverStation} has exactly one reader in this library:
 * {@code org.rootstock.core.match.MatchContext}. Callers say {@code MatchContext.isDiagnostics()}.
 * Putting it here would have made {@code compat} a second {@code DriverStation} reader and quietly
 * broken the one rule (rule 10) that keeps the 2027 DS surface to a single file. If a second package
 * ever needs {@code DriverStation}, the answer is a new {@code MatchContext} method, not a new
 * allowlist entry.
 *
 * <p><b>{@code RootstockField} is NOT in this package.</b> D14 gives the one {@code RootstockField} to
 * {@code org.rootstock.field}, which joins the same ArchUnit-fenced tier because it is the file
 * that absorbs the 2027 field-origin move.
 */
public final class Platform {

  private Platform() {}

  /**
   * Whether this code is running in simulation.
   *
   * @return true in {@code simulateJava} and in unit tests, false on a robot
   */
  public static boolean isSimulation() {
    return edu.wpi.first.wpilibj.RobotBase.isSimulation();
  }

  /**
   * Whether this code is running on real hardware.
   *
   * @return true on a robot, false in simulation
   */
  public static boolean isReal() {
    return edu.wpi.first.wpilibj.RobotBase.isReal();
  }

  /**
   * The team number the roboRIO is configured for.
   *
   * @return the team number, or 0 if it cannot be determined
   */
  public static int teamNumber() {
    return edu.wpi.first.wpilibj.RobotController.getTeamNumber();
  }

  /**
   * The controller's serial number, used by {@code RobotIdentity} to tell the comp bot from the
   * practice bot with zero setup once it has been recorded.
   *
   * <p>Availability on SystemCore is UNVERIFIED, which is exactly why this returns an
   * {@link Optional} rather than a possibly-empty {@code String}: when the call disappears, this
   * method becomes {@code Optional.empty()} in one file and the identity chain degrades to the next
   * strategy with a named alert. It does not fail to compile.
   *
   * @return the trimmed serial number, or empty if unavailable
   */
  public static Optional<String> serialNumber() {
    String s = edu.wpi.first.wpilibj.RobotController.getSerialNumber();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /**
   * The roboRIO web-dashboard "comments" field — student-editable through a web page, with no code
   * and no SSH, which is why {@code RobotIdentity} matches against it.
   *
   * <p>UNVERIFIED on SystemCore; see {@link #serialNumber()} for why that means {@link Optional}.
   *
   * @return the trimmed comments field, or empty if unavailable
   */
  public static Optional<String> comments() {
    String s = edu.wpi.first.wpilibj.RobotController.getComments();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /**
   * A writable directory that survives a redeploy — {@code /home/lvuser} on a roboRIO.
   *
   * <p>This is where {@code rootstock/robot-id}, {@code rootstock/target-state.json} and the persistent
   * tuned-value store live. It is the primary identity strategy specifically because it is the only
   * mechanism guaranteed to exist on SystemCore.
   *
   * @return the operating directory
   */
  public static Path persistentDir() {
    return edu.wpi.first.wpilibj.Filesystem.getOperatingDirectory().toPath();
  }

  /**
   * The same directory as {@link #persistentDir()}, under WPILib's own name for it.
   *
   * <p>Provided because "operating directory" is what the WPILib docs call it and what a reader
   * grepping for the migrated call will look for. {@link #persistentDir()} is the name the rest of
   * this library uses, because <i>persistent</i> is the property callers actually depend on.
   *
   * @return the operating directory
   */
  public static Path operatingDirectory() {
    return persistentDir();
  }

  /**
   * The deploy directory — {@code src/main/deploy} as it lands on the robot.
   *
   * <p>Holds {@code rootstock/disabled.txt} (the runtime kill switch), {@code rootstock/gains.json}, the
   * generated Elastic layout and the field layout JSON, and it is what the port-5800 layout server
   * serves.
   *
   * @return the deploy directory
   */
  public static Path deployDir() {
    return edu.wpi.first.wpilibj.Filesystem.getDeployDirectory().toPath();
  }

  // -------------------------------------------------------------------------------------------
  // The RobotController surface used by the built-in health monitors.
  //
  // These exist so CanBusMonitor / RailMonitor / BrownoutMonitor / BatteryMonitor can be written in
  // org.rootstock.core.health.builtin without naming RobotController, which rule 2 forbids outside
  // this package.
  // -------------------------------------------------------------------------------------------

  /**
   * The roboRIO CAN bus status, flattened.
   *
   * <p>Flattened rather than returned as {@code edu.wpi.first.hal.can.CANStatus} on purpose: that is
   * a HAL type on the 2027 move list ({@code edu.wpi.first.hal.} → {@code org.wpilib.hardware.hal.})
   * and it is the <i>only</i> HAL type Rootstock would otherwise touch. Flattening it here means the
   * core has zero HAL imports outside {@code compat}.
   *
   * @return the current bus status
   */
  public static CanStatus canStatus() {
    var s = edu.wpi.first.wpilibj.RobotController.getCANStatus();
    return new CanStatus(
        s.percentBusUtilization,
        s.busOffCount,
        s.txFullCount,
        s.receiveErrorCount,
        s.transmitErrorCount);
  }

  /**
   * Flattened CAN status. Same shape as Phoenix 6's {@code CANBusStatus}, so one monitor reads both.
   *
   * @param utilization bus utilization as a fraction, 0.0 to 1.0
   * @param busOff count of bus-off events since boot
   * @param txFull count of transmit-buffer-full events since boot
   * @param rec receive error counter
   * @param tec transmit error counter
   */
  public record CanStatus(double utilization, int busOff, int txFull, int rec, int tec) {}

  /**
   * Battery voltage at the roboRIO's input terminals.
   *
   * @return volts
   */
  public static double batteryVolts() {
    return edu.wpi.first.wpilibj.RobotController.getBatteryVoltage();
  }

  /**
   * Whether the controller is currently in brownout.
   *
   * @return true during a brownout
   */
  public static boolean isBrownedOut() {
    return edu.wpi.first.wpilibj.RobotController.isBrownedOut();
  }

  /**
   * How many times the 5 V rail has faulted since boot.
   *
   * <p>A non-zero value means sensors browned out and your encoders lied — which is why this is a
   * monitored health signal rather than a curiosity.
   *
   * @return the fault count
   */
  public static int faultCount5V() {
    return edu.wpi.first.wpilibj.RobotController.getFaultCount5V();
  }

  /**
   * How many times the 3.3 V rail has faulted since boot.
   *
   * @return the fault count
   */
  public static int faultCount3V3() {
    return edu.wpi.first.wpilibj.RobotController.getFaultCount3V3();
  }

  /**
   * How many times the 6 V rail has faulted since boot.
   *
   * @return the fault count
   */
  public static int faultCount6V() {
    return edu.wpi.first.wpilibj.RobotController.getFaultCount6V();
  }
}

package org.rootstock.core.health.builtin;

import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.config.DeployInfo;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.HealthSource;

/**
 * "Is the code on this robot what is in git?"
 *
 * <p>The question is asked at 11pm on Friday when the robot behaves differently from the copy on
 * someone's laptop, and the honest answer is usually "no, and nobody knows what the difference is".
 * A dirty deploy is not an emergency — it is week-one normal — but it must be <em>on the screen</em>
 * so that "it worked in the pit" is a checkable claim rather than a memory.
 *
 * <p>Two of the eleven built-in conditions, both {@code PIT_ONLY}, because a driver cannot act on
 * either one in the ninety seconds before a match:
 *
 * <table border="1">
 *   <caption>Deploy conditions</caption>
 *   <tr><th>Condition</th><th>Severity</th><th>Impact</th></tr>
 *   <tr><td>{@code DIRTY == 1} — uncommitted changes at deploy time</td><td>WARNING</td>
 *       <td>PIT_ONLY</td></tr>
 *   <tr><td>provenance UNKNOWN — no {@code BuildConstants} on the classpath</td><td>INFO</td>
 *       <td>PIT_ONLY</td></tr>
 * </table>
 *
 * <p>This is a static fact: it cannot change while the robot is running. It is still a slice rather
 * than a one-shot boot check so that the alert re-asserts itself after anything clears the panel,
 * and it costs a field read.
 */
public final class DeployMonitor implements HealthSource {

  /** The health name, and therefore the alert group and NT subtable. */
  public static final String kName = "Deploy";

  private RootstockAlert m_dirtyAlert;
  private RootstockAlert m_unknownAlert;

  private DeployMonitor() {}

  /**
   * Create the monitor.
   *
   * @return a new monitor; call {@link #register()} to install it
   */
  public static DeployMonitor create() {
    return new DeployMonitor();
  }

  /**
   * Register as one slice of the shared round-robin and create the two alerts.
   *
   * <p>Polled every 32 sweeps rather than every sweep: the answer cannot change while the robot is
   * running, so anything more often is pure waste. That is exactly what {@code everyNSweeps} is for.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_dirtyAlert =
        Alerts.warning(kName, "deployed code is not in git", MatchImpact.PIT_ONLY);
    m_unknownAlert =
        Alerts.info(kName, "build provenance is unknown - BuildConstants was not generated");
    return HealthMonitor.watch(this).ownAlerts().everyNSweeps(32);
  }

  @Override
  public String healthName() {
    return kName;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    DeployInfo.Dirty dirty = DeployInfo.dirty();

    boolean isDirty = dirty == DeployInfo.Dirty.DIRTY;
    boolean isUnknown = dirty == DeployInfo.Dirty.UNKNOWN || !DeployInfo.isAvailable();

    if (isDirty) {
      String text =
          "deployed code has uncommitted changes on top of "
              + DeployInfo.gitShaShort()
              + " ("
              + DeployInfo.gitBranch()
              + "). Expected a clean tree. Commit and redeploy, or write down what is different - "
              + "nobody can reproduce this build from git.";
      out.warn(kName, text);
      if (m_dirtyAlert != null) {
        m_dirtyAlert.text(text);
      }
    }
    if (isUnknown) {
      String text =
          "build provenance is unknown ("
              + DeployInfo.resolutionNote()
              + "). Expected a generated BuildConstants class on the classpath. Add the "
              + "gversion/BuildConstants task to build.gradle so logs say which commit they came "
              + "from.";
      out.info(kName, text);
      if (m_unknownAlert != null) {
        m_unknownAlert.text(text);
      }
    }

    if (m_dirtyAlert != null) {
      m_dirtyAlert.set(isDirty);
    }
    if (m_unknownAlert != null) {
      m_unknownAlert.set(isUnknown);
    }
  }
}

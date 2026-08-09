package org.pumpkinlib.core.health.builtin;

import java.util.ArrayList;
import java.util.List;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.health.FaultCollector;
import org.pumpkinlib.core.health.HealthMonitor;
import org.pumpkinlib.core.health.HealthSource;
import org.pumpkinlib.core.hid.ControlMap;
import org.pumpkinlib.core.match.MatchContext;

/**
 * Joystick presence and slot verification against the declared {@link ControlMap}.
 *
 * <p>A controller in the wrong Driver Station slot means half the bindings are dead — the driver
 * pushes the stick and nothing happens, and the diagnosis takes three matches because everything
 * <em>looks</em> plugged in. The library already knows which ports the team declared, so it can say
 * so before the match instead of after.
 *
 * <p><strong>This monitor does not read {@code DriverStation}.</strong> DESIGN.md §8 rule 10 says
 * {@code org.pumpkinlib.core.match} is the single reader in the whole library, and revision 1 of the
 * design broke that here, which made the "single reader" claim false. Everything below goes through
 * {@link MatchContext}. If a check needs something {@code MatchContext} does not expose, the answer
 * is a new {@code MatchContext} method — not an import.
 *
 * <p>One of the eleven built-in conditions: a declared HID port that is empty or holds the wrong
 * kind of controller. {@code ERROR}, and <strong>{@code BLOCKS_MATCH}</strong>, because it is
 * genuinely fixable in the ninety seconds before a match by plugging the controller into the right
 * slot.
 */
public final class DsMonitor implements HealthSource {

  /** The health name, and therefore the alert group and NT subtable. */
  public static final String kName = "DriverStation";

  private PumpkinAlert m_alert;

  private DsMonitor() {}

  /**
   * Create the monitor.
   *
   * @return a new monitor; call {@link #register()} to install it
   */
  public static DsMonitor create() {
    return new DsMonitor();
  }

  /**
   * Register as one slice of the shared round-robin and create the alert.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_alert =
        Alerts.error(
            kName, "a declared controller port is empty or wrong", MatchImpact.BLOCKS_MATCH);
    return HealthMonitor.watch(this).ownAlerts();
  }

  @Override
  public String healthName() {
    return kName;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    List<String> problems = new ArrayList<>();

    for (ControlMap<?> map : ControlMap.all()) {
      int port = map.port();
      String device = kName + "/port" + port;

      if (!MatchContext.joystickConnected(port)) {
        String text =
            "port "
                + port
                + " is declared by the \""
                + map.role()
                + "\" control map but the Driver Station reports nothing plugged in. "
                + "Every binding on that map is dead. Plug the controller into slot "
                + port
                + " in the Driver Station USB tab.";
        out.error(device, text);
        problems.add(text);
        continue;
      }

      // The declared HID type is compared by simple class name rather than by importing the
      // concrete controller classes: core.hid owns the wrapping, and a name comparison keeps this
      // monitor working for PS4/PS5/Stadia without a switch that has to be edited every season.
      String declared = map.raw().getClass().getSimpleName();
      boolean expectsXbox = declared.toLowerCase(java.util.Locale.ROOT).contains("xbox");
      boolean isXbox = MatchContext.joystickIsXbox(port);
      if (expectsXbox && !isXbox) {
        String text =
            "port "
                + port
                + " (\""
                + map.role()
                + "\") is declared as "
                + declared
                + " but the Driver Station reports \""
                + MatchContext.joystickName(port)
                + "\", which is not an Xbox-layout controller. The button numbers will not line up. "
                + "Either move the controller to the right slot or set the DS to Xbox mode.";
        out.error(device, text);
        problems.add(text);
      }
    }

    for (String defect : ControlMap.defects()) {
      out.warn(kName + "/controls", defect);
    }

    if (m_alert != null) {
      if (!problems.isEmpty()) {
        m_alert.text(problems.get(0) + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""));
      }
      m_alert.set(!problems.isEmpty());
    }
  }
}

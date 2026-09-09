package org.rootstock.core.health.builtin;

import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Voltage;
import java.util.function.DoubleSupplier;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.HealthSource;
import org.rootstock.core.health.RobotHealth;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.util.EdgeDetector;
import org.rootstock.pure.math.Rolling;

/**
 * Resting voltage, sag under load, and joules consumed.
 *
 * <p><strong>Resting voltage below 12.3 V at enable is the classic lost match</strong>, and it is
 * fixable in ninety seconds by a student with a fresh battery and a wrench. That is why it is the
 * only {@code WARNING} in the library that blocks the match: severity says "not on fire", impact
 * says "do not take the field like this". The two questions are genuinely different and this row is
 * the proof.
 *
 * <p>Two of the eleven built-in conditions:
 *
 * <table border="1">
 *   <caption>Battery conditions</caption>
 *   <tr><th>Condition</th><th>Severity</th><th>Impact</th></tr>
 *   <tr><td>resting &lt; 12.3 V at enable</td><td>WARNING</td>
 *       <td><strong>BLOCKS_MATCH</strong></td></tr>
 *   <tr><td>sag &lt; 9.0 V sustained</td><td>WARNING</td><td>PIT_ONLY</td></tr>
 * </table>
 *
 * <p>Sag correlates against total current draw when a current source is supplied with {@link
 * #totalCurrent(DoubleSupplier)} — typically {@code pdh::getTotalCurrent}. Core does not construct a
 * {@code PowerDistribution} itself, because whether one exists and on which CAN ID is a robot fact,
 * not a library fact.
 */
public final class BatteryMonitor implements HealthSource {

  /** The health name, and therefore the alert group and NT subtable. */
  public static final String kName = "Battery";

  /** Default resting-voltage floor: below this, rotate the battery out. */
  public static final double kDefaultRestingMinVolts = 12.3;

  /** Default sag floor. Below this the roboRIO is close to browning out. */
  public static final double kDefaultSagLimitVolts = 9.0;

  /** Default sustained-sag window. */
  public static final double kDefaultSagWindowSeconds = 2.0;

  /** Samples averaged to estimate resting voltage, at 50 Hz sweeps. */
  private static final int kRestingWindowSamples = 25;

  private final Rolling m_restingWindow = new Rolling(kRestingWindowSamples);
  private final EdgeDetector m_enabled = new EdgeDetector(false);

  private double m_restingMinVolts = kDefaultRestingMinVolts;
  private double m_sagLimitVolts = kDefaultSagLimitVolts;
  private Time m_sagWindow = Units.Seconds.of(kDefaultSagWindowSeconds);
  private DoubleSupplier m_totalCurrent = () -> Double.NaN;

  private double m_minSeenVolts = Double.POSITIVE_INFINITY;
  private double m_restingAtEnable = Double.NaN;
  private double m_joules;
  private double m_lastSampleSeconds = Double.NaN;
  private boolean m_sagging;

  private RootstockAlert m_restingAlert;
  private RootstockAlert m_sagAlert;

  private BatteryMonitor() {}

  /**
   * Create the monitor with the default thresholds.
   *
   * @return a new monitor; call {@link #register()} to install it
   */
  public static BatteryMonitor create() {
    return new BatteryMonitor();
  }

  /**
   * The resting-voltage floor checked at the enable edge.
   *
   * @param v default 12.3 V
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code v} is null or not positive
   */
  public BatteryMonitor restingMin(Voltage v) {
    m_restingMinVolts = requirePositiveVolts(v, "restingMin");
    return this;
  }

  /**
   * The sag floor: sustained voltage below this is a pit-only warning.
   *
   * @param v default 9.0 V
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code v} is null or not positive
   */
  public BatteryMonitor sagLimit(Voltage v) {
    m_sagLimitVolts = requirePositiveVolts(v, "sagLimit");
    return this;
  }

  /**
   * How long the sag must persist before it is reported. Converted once to whole cycles by {@link
   * RootstockAlert#debounce(Time)}, so a replayed log debounces on exactly the cycles the real robot
   * did.
   *
   * @param t default 2 s
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code t} is null or negative
   */
  public BatteryMonitor sagWindow(Time t) {
    if (t == null || t.in(Units.Seconds) < 0.0) {
      throw new IllegalArgumentException(
          "BatteryMonitor.sagWindow: must be a non-negative Time, e.g. Seconds.of(2.0).");
    }
    m_sagWindow = t;
    return this;
  }

  /**
   * Supply total robot current so sag can be correlated against draw, and so joules consumed can be
   * integrated.
   *
   * <p>Typically {@code pdh::getTotalCurrent}. Without it the monitor still works — it simply cannot
   * say <em>why</em> the battery sagged.
   *
   * @param amps total current draw in amps
   * @return this, for chaining
   */
  public BatteryMonitor totalCurrent(DoubleSupplier amps) {
    m_totalCurrent = amps == null ? () -> Double.NaN : amps;
    return this;
  }

  /**
   * Register as one slice of the shared round-robin, create the two alerts, and install {@link
   * RobotHealth#batterySagging()}.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_restingAlert =
        Alerts.warning(
            kName,
            String.format(
                "battery resting voltage is below %.1f V - swap it before this match",
                m_restingMinVolts),
            MatchImpact.BLOCKS_MATCH);
    m_sagAlert =
        Alerts.warning(
                kName,
                String.format("battery sagging below %.1f V under load", m_sagLimitVolts),
                MatchImpact.PIT_ONLY)
            .debounce(m_sagWindow);
    RobotHealth.setBatterySaggingSource(() -> m_sagging);
    return HealthMonitor.watch(this).ownAlerts();
  }

  /**
   * The lowest voltage seen since the robot was last enabled.
   *
   * @return volts, or positive infinity before the first sample
   */
  public Voltage minSeenSinceEnable() {
    return Units.Volts.of(Double.isInfinite(m_minSeenVolts) ? 0.0 : m_minSeenVolts);
  }

  /**
   * The current resting-voltage estimate: the mean of the last half-second of samples taken while
   * <em>disabled</em>, which is the only time the battery is genuinely at rest.
   *
   * @return volts; 0 V before enough samples have been taken
   */
  public Voltage restingEstimate() {
    return Units.Volts.of(m_restingWindow.isEmpty() ? 0.0 : m_restingWindow.mean());
  }

  /**
   * The resting estimate captured at the most recent enable edge — the number the blocking alert is
   * based on.
   *
   * @return volts, or 0 V if the robot has not been enabled yet
   */
  public Voltage restingAtEnable() {
    return Units.Volts.of(Double.isNaN(m_restingAtEnable) ? 0.0 : m_restingAtEnable);
  }

  /**
   * Energy drawn since the robot was last enabled, integrated from bus voltage and the supplied
   * total current. A per-match battery metric that is essentially never used and is one of the best
   * predictors of "this battery is tired".
   *
   * @return joules, or 0 when no current source was supplied
   */
  public double joulesSinceEnable() {
    return m_joules;
  }

  /**
   * Whether the sag condition currently holds.
   *
   * @return true while bus voltage is below the sag limit
   */
  public boolean isSagging() {
    return m_sagging;
  }

  @Override
  public String healthName() {
    return kName;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    double volts = Platform.batteryVolts();
    double now = Clock.seconds();

    boolean enabled = MatchContext.isEnabled();
    m_enabled.update(enabled);
    if (m_enabled.rising()) {
      m_restingAtEnable = m_restingWindow.isEmpty() ? volts : m_restingWindow.mean();
      m_minSeenVolts = volts;
      m_joules = 0.0;
    }

    if (!enabled) {
      // Only sample resting voltage while disabled: under load every battery reads low, and a
      // "resting" number measured under load is the number that makes people distrust the check.
      m_restingWindow.add(volts);
    } else {
      m_minSeenVolts = Math.min(m_minSeenVolts, volts);
      double amps = m_totalCurrent.getAsDouble();
      if (!Double.isNaN(amps) && !Double.isNaN(m_lastSampleSeconds)) {
        m_joules += volts * amps * Math.max(0.0, now - m_lastSampleSeconds);
      }
    }
    m_lastSampleSeconds = now;

    m_sagging = volts < m_sagLimitVolts;

    boolean restingLow =
        !Double.isNaN(m_restingAtEnable) && m_restingAtEnable < m_restingMinVolts;

    if (restingLow) {
      out.warn(
          kName,
          String.format(
              "resting voltage was %.2f V at enable, below the %.2f V floor. Put a charged "
                  + "battery in before this match - this is the single most common lost match.",
              m_restingAtEnable, m_restingMinVolts));
    }
    if (m_sagging) {
      String amps = describeCurrent();
      out.warn(
          kName,
          String.format(
              "bus voltage %.2f V is below the %.2f V sag limit%s. Check the battery, the main "
                  + "breaker and the battery leads, and lower your current limits.",
              volts, m_sagLimitVolts, amps));
    }

    if (m_restingAlert != null) {
      if (restingLow) {
        m_restingAlert.text(
            String.format(
                "battery was %.2f V at rest (floor %.2f V) - swap it before this match",
                m_restingAtEnable, m_restingMinVolts));
      }
      m_restingAlert.set(restingLow);
    }
    if (m_sagAlert != null) {
      if (m_sagging) {
        m_sagAlert.text(
            String.format(
                "battery at %.2f V (sag limit %.2f V, min seen %.2f V)%s",
                volts, m_sagLimitVolts, m_minSeenVolts, describeCurrent()));
      }
      m_sagAlert.set(m_sagging);
    }
  }

  private String describeCurrent() {
    double amps = m_totalCurrent.getAsDouble();
    return Double.isNaN(amps) ? "" : String.format(" at %.1f A total draw", amps);
  }

  private static double requirePositiveVolts(Voltage v, String name) {
    if (v == null || !(v.in(Units.Volts) > 0.0)) {
      throw new IllegalArgumentException(
          "BatteryMonitor." + name + ": must be a positive Voltage, e.g. Volts.of(12.3).");
    }
    return v.in(Units.Volts);
  }
}

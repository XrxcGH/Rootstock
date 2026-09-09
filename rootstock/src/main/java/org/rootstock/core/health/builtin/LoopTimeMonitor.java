package org.rootstock.core.health.builtin;

import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Time;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthMonitor;
import org.rootstock.core.health.HealthSource;

/**
 * Per-domain loop timing against the budgets declared with {@link RootstockTracer}.
 *
 * <p>This is a direct response to the strongest recurring criticism of every FRC framework: <em>"For
 * us, YAMS caused more problems than it solved. The structure of it created a crazy amount of loop
 * overruns... We ended up not using any logging or telemetry at all."</em> A library whose
 * observability is the first thing dropped under pressure has failed at its main job. So Rootstock
 * publishes {@code /Rootstock/Loop/Domain/<name>Ms} for every one of its domains, on by default, and
 * answers "is Rootstock causing my overruns?" in one glance <strong>with a name attached</strong>
 * — rather than by bisecting a robot project at 1am.
 *
 * <p><strong>Registers zero slices and runs every loop</strong>, because it is measuring loops. That
 * is the {@code 0} in the seven-types / eight-slices arithmetic. Its own cost is about 30 µs and it
 * is accounted in its own budget.
 *
 * <p>One of the eleven built-in conditions: over budget for five consecutive loops, {@code WARNING},
 * {@code PIT_ONLY}. Five consecutive, because a single 21 ms loop after a garbage collection is
 * noise and alerting on it is how a warning gets ignored.
 */
public final class LoopTimeMonitor implements HealthSource {

  /** The health name, and therefore the alert group and NT subtable. */
  public static final String kName = "LoopTime";

  /** Default fraction of the budget above which a loop counts as late. */
  public static final double kDefaultWarnFraction = 0.80;

  /** Consecutive late loops before this is a fault. Matches {@link RootstockTracer}'s breach rule. */
  public static final int kConsecutiveLoopsToBreach = RootstockTracer.kConsecutiveLoopsToBreach;

  private final Map<String, RootstockTracer.Breach> m_breaches = new LinkedHashMap<>();

  private Time m_budget;
  private double m_warnFraction = kDefaultWarnFraction;
  private int m_lateStreak;
  private int m_worstStreak;
  private RootstockAlert m_alert;

  private LoopTimeMonitor() {}

  /**
   * Create the monitor with the default budget ({@code Clock.period()}) and warn threshold.
   *
   * @return a new monitor; call {@link #register()} to install it
   */
  public static LoopTimeMonitor create() {
    return new LoopTimeMonitor();
  }

  /**
   * Override the loop budget.
   *
   * @param period the budget; defaults to {@code Clock.period()}, i.e. the robot's real loop period
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code period} is null or not positive
   */
  public LoopTimeMonitor budget(Time period) {
    if (period == null || !(period.in(Units.Seconds) > 0.0)) {
      throw new IllegalArgumentException(
          "LoopTimeMonitor.budget: must be a positive Time, e.g. Milliseconds.of(20).");
    }
    m_budget = period;
    return this;
  }

  /**
   * The fraction of the budget above which a loop counts as late.
   *
   * @param fractionOfBudget 0..1; default {@value #kDefaultWarnFraction}
   * @return this, for chaining
   * @throws IllegalArgumentException if outside 0..1
   */
  public LoopTimeMonitor warnAbove(double fractionOfBudget) {
    if (!(fractionOfBudget > 0.0) || !(fractionOfBudget <= 1.0)) {
      throw new IllegalArgumentException(
          "LoopTimeMonitor.warnAbove("
              + fractionOfBudget
              + "): must be a fraction in (0, 1]. 0.80 means 80% of the loop period.");
    }
    m_warnFraction = fractionOfBudget;
    return this;
  }

  /**
   * A try-with-resources timing block. Delegates to {@link RootstockTracer#section(String)}; it is
   * duplicated here because this is where people look for it.
   *
   * <p>Returns {@code RootstockTracer.Scope} rather than the bare {@code AutoCloseable} the design
   * sketch named, so that {@code close()} declares no checked exception and try-with-resources needs
   * no catch block.
   *
   * @param name the section name; becomes {@code /Rootstock/Loop/Domain/<name>Ms}
   * @return the scope; close it to record the elapsed time
   */
  public static RootstockTracer.Scope section(String name) {
    return RootstockTracer.section(name);
  }

  /**
   * Declare the loop-time budgets Rootstock holds itself to, so "is Rootstock causing my
   * overruns?" is answerable without bisecting.
   *
   * <p>Called by {@link #register()}. The numbers are the design's, and they are deliberately
   * publishable: if a domain exceeds its own stated budget, that is a bug in the library and it says
   * so by name.
   */
  public static void declareLibraryBudgets() {
    RootstockTracer.budget("Health", Units.Milliseconds.of(1.5)); // one source per loop, round-robin
    RootstockTracer.budget("Alerts", Units.Milliseconds.of(0.5));
    RootstockTracer.budget("Match", Units.Milliseconds.of(0.2));
    RootstockTracer.budget("Power", Units.Milliseconds.of(0.5));
    RootstockTracer.budget("Leds", Units.Milliseconds.of(1.0)); // every loop by design
    RootstockTracer.budget("Telemetry", Units.Milliseconds.of(2.0));
  }

  /**
   * Register the every-loop sampler, declare the library budgets, install the {@link RootstockTracer}
   * breach listener, and create the alert.
   *
   * <p>Registers <strong>zero</strong> round-robin slices, on purpose: {@code everyLoop()} moves it
   * off the rotation, which is what keeps {@code builtinSliceCount() == 8} honest. It is still a
   * {@link HealthMonitor} registration, so its faults appear in {@code RobotHealth.active()} and in
   * {@code expectNoNewFaults()} like everything else.
   *
   * @return the registration handle
   */
  public HealthMonitor register() {
    m_alert = Alerts.warning(kName, "loop time is over budget", MatchImpact.PIT_ONLY);
    declareLibraryBudgets();
    RootstockTracer.setBreachListener(b -> m_breaches.put(b.section(), b));
    return HealthMonitor.watch(this).ownAlerts().everyLoop();
  }

  /**
   * The budget currently in force.
   *
   * @return the configured budget, or {@code Clock.period()} when none was set
   */
  public Time budget() {
    return m_budget == null ? Clock.period() : m_budget;
  }

  /**
   * The longest run of consecutive late loops seen so far.
   *
   * @return the streak length
   */
  public int worstLateStreak() {
    return m_worstStreak;
  }

  @Override
  public String healthName() {
    return kName;
  }

  @Override
  public void pollHealth(FaultCollector out) {
    double budgetMs = budget().in(Units.Milliseconds);
    double limitMs = budgetMs * m_warnFraction;
    double lastMs = RootstockTracer.loopMillis();

    // Runs every loop, so the streak is counted here rather than in a separate sampler.
    if (lastMs > limitMs) {
      m_lateStreak++;
      m_worstStreak = Math.max(m_worstStreak, m_lateStreak);
    } else {
      m_lateStreak = 0;
    }
    boolean late = m_lateStreak >= kConsecutiveLoopsToBreach;

    if (late) {
      String text =
          String.format(
              "loop took %.2f ms for %d consecutive loops, over the %.2f ms warn threshold "
                  + "(%.0f%% of the %.2f ms budget); worst loop %.2f ms, %d overruns so far. "
                  + "Check /Rootstock/Loop/Domain/ to see which domain is spending the time.",
              lastMs,
              m_lateStreak,
              limitMs,
              m_warnFraction * 100.0,
              budgetMs,
              RootstockTracer.worstLoopMillis(),
              RootstockTracer.overrunCount());
      out.warn(kName, text);
      if (m_alert != null) {
        m_alert.text(text);
      }
    }

    for (RootstockTracer.Breach b : new ArrayList<>(m_breaches.values())) {
      out.warn(kName + "/" + b.section(), b.message());
    }

    if (m_alert != null) {
      m_alert.set(late || !m_breaches.isEmpty());
    }
  }

  /**
   * The per-section budget breaches {@link RootstockTracer} has reported.
   *
   * @return an unmodifiable snapshot, most recent value per section
   */
  public List<RootstockTracer.Breach> breaches() {
    return List.copyOf(m_breaches.values());
  }
}

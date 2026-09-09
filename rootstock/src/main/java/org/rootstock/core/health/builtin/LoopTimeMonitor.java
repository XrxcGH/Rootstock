package org.rootstock.core.health.builtin;

import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
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
import org.rootstock.core.match.MatchContext;

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
 * <p>The publisher is {@code publishDomains()}, called from {@link #pollHealth(FaultCollector)}. It
 * writes one key per {@link RootstockTracer} section, including sections that only have a declared
 * budget and have never been measured, so every domain shows a number from boot rather than
 * appearing the first time it happens to run.
 *
 * <p><strong>Registers zero slices and runs every loop</strong>, because it is measuring loops. That
 * is the {@code 0} in the seven-types / eight-slices arithmetic. Its own cost is about 30 µs and it
 * is accounted in its own budget: with seven domains published, the whole {@code Health} section
 * measured 5 to 6 µs in a simulated loop.
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

  /**
   * The NetworkTables subtable this monitor fills, one {@code <name>Ms} key per tracer section.
   *
   * <p>This is the topic the class javadoc promises and the topic the over-budget alert tells a
   * student to open. Until the publisher below existed, both sent them to an empty subtable.
   */
  public static final String kDomainTopicPrefix = "/Rootstock/Loop/Domain/";

  /** Suffix on every domain key, so the unit is on the key and not only in a document. */
  public static final String kDomainTopicSuffix = "Ms";

  /**
   * The tracer section every {@code Mechanism.periodic()} accumulates into.
   *
   * <p>One constant name for all of them, not {@code "Mechanism/" + name}: a name per mechanism
   * would concatenate a string on the periodic path, which {@code Mechanism} precomputes its keys
   * specifically to avoid, and it would grow a log topic per mechanism, which is what breaks a
   * saved AdvantageScope layout when a team adds a subsystem. The tracer sums repeated spans of the
   * same section within one loop, so one name gives the whole domain.
   */
  public static final String kMechanismSection = "Mechanism";

  // Loops between two checks for a newly created tracer section, and only while disabled. See
  // refreshTableIfNeeded().
  private static final int kRefreshEveryNCycles = 50;

  private final Map<String, RootstockTracer.Breach> m_breaches = new LinkedHashMap<>();

  private Time m_budget;
  private double m_warnFraction = kDefaultWarnFraction;
  private int m_lateStreak;
  private int m_worstStreak;
  private RootstockAlert m_alert;

  // Parallel arrays rather than a map, because this is walked on every loop and iterating a Map
  // allocates an iterator. Names are kept so a rebuild can tell "the same sections" from "the same
  // number of different sections" without publishing a number under a stale key.
  private String[] m_domains = new String[0];
  private DoublePublisher[] m_domainPublishers = new DoublePublisher[0];
  private long m_lastRefreshCycle = Long.MIN_VALUE;

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
    // Mechanism is the sum of every Mechanism.periodic() on the robot, and unlike the six above it
    // its cost is dominated by the vendor's updateInputs, not by library code -- so read a breach
    // as "look at your device reads", not as "the library has a bug". 5 ms is the arithmetic that
    // is left: the six budgets above total 5.7 ms, and 10.7 ms of a 20 ms loop still leaves the
    // team's own commands the larger half. It is not a measurement of a real bus. What was
    // measured, in simulation with one roller, is that the section reads out at all and costs
    // roughly 1 us per warm periodic() -- so on this side of the CAN bus the budget is not the
    // binding constraint, which is the point.
    RootstockTracer.budget(kMechanismSection, Units.Milliseconds.of(5.0));
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
    publishDomains();

    // budgetMillis(), not budget().in(...): budget() falls back to Clock.period(), which is
    // Seconds.of(...) -- a fresh Measure every loop. JFR sampled it as edu.wpi.first.units.TimeUnit
    // .of(double) inside a 50,000-loop steady-state window. in(...) on an existing Measure is a
    // multiply and allocates nothing, so the configured branch was always free.
    double budgetMs = budgetMillis();
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

    // Guarded, because the copy and its iterator were allocated on every loop of a robot with no
    // breaches at all -- which is every healthy robot. JFR sampled both (LinkedValues.toArray and
    // ArrayList.iterator) in a steady-state window. The copy itself stays: the breach listener
    // writes m_breaches from RootstockTracer.reset(), and iterating the live map is how that
    // becomes a ConcurrentModificationException on the one loop that is already going wrong.
    if (!m_breaches.isEmpty()) {
      for (RootstockTracer.Breach b : new ArrayList<>(m_breaches.values())) {
        out.warn(kName + "/" + b.section(), b.message());
      }
    }

    if (m_alert != null) {
      m_alert.set(late || !m_breaches.isEmpty());
    }
  }

  // The loop budget in milliseconds, without the Measure that budget() allocates on its fallback
  // branch. Not merged into budget(): that one is public API and returns a typed Time.
  private double budgetMillis() {
    return m_budget == null ? Clock.dt() * 1000.0 : m_budget.in(Units.Milliseconds);
  }

  /**
   * The per-section budget breaches {@link RootstockTracer} has reported.
   *
   * @return an unmodifiable snapshot, most recent value per section
   */
  public List<RootstockTracer.Breach> breaches() {
    return List.copyOf(m_breaches.values());
  }

  /**
   * The domain keys currently being published, in tracer order.
   *
   * @return the full NetworkTables topic names; empty before the first poll
   */
  public List<String> publishedDomainTopics() {
    List<String> out = new ArrayList<>(m_domains.length);
    for (String name : m_domains) {
      out.add(kDomainTopicPrefix + name + kDomainTopicSuffix);
    }
    return List.copyOf(out);
  }

  // -----------------------------------------------------------------------------------------
  // Publishing
  // -----------------------------------------------------------------------------------------

  // Runs every loop, from pollHealth, so it is already accounted against the "Health" budget:
  // SliceScheduler routes every-loop work through runGuarded("Health/LoopTime", ...) and takes the
  // section name from the part before the slash.
  //
  // The write path walks two arrays and calls one NT set per domain. It allocates nothing: the key
  // strings are built when the table is rebuilt, and RootstockTracer.lastMillis reads one double
  // field. Building the table calls RootstockTracer.sections(), which does allocate a list, so it
  // is done only while disabled and at most every kRefreshEveryNCycles loops. An enabled loop
  // therefore allocates zero bytes here, which is the constraint the whole periodic path is held to.
  private void publishDomains() {
    try {
      refreshTableIfNeeded();
      DoublePublisher[] publishers = m_domainPublishers;
      String[] domains = m_domains;
      for (int i = 0; i < publishers.length; i++) {
        publishers[i].set(RootstockTracer.lastMillis(domains[i]));
      }
    } catch (RuntimeException e) {
      // Publishing is the courtesy; measuring the loop is the job. Drop the table and try again
      // next loop, the way HealthMonitor.publish() handles the same failure.
      closePublishers();
    }
  }

  private void refreshTableIfNeeded() {
    // A section created while the robot is enabled is not published until the next disable. That is
    // deliberate: the alternative is calling sections() on the match loop, and a domain that first
    // appears mid-match has no sample to show until the loop after it appears anyway. The first
    // build is exempt, because on a real field the robot boots disabled but a bench test may not.
    if (m_domainPublishers.length > 0 && !MatchContext.isDisabled()) {
      return;
    }
    long cycle = Clock.cycle();
    if (m_lastRefreshCycle != Long.MIN_VALUE && cycle - m_lastRefreshCycle < kRefreshEveryNCycles) {
      return;
    }
    m_lastRefreshCycle = cycle;

    List<String> names = RootstockTracer.sections();
    if (matchesCurrentTable(names)) {
      return;
    }

    closePublishers();
    NetworkTableInstance nt = NetworkTableInstance.getDefault();
    String[] domains = new String[names.size()];
    DoublePublisher[] publishers = new DoublePublisher[names.size()];
    for (int i = 0; i < domains.length; i++) {
      domains[i] = names.get(i);
      publishers[i] =
          nt.getDoubleTopic(kDomainTopicPrefix + domains[i] + kDomainTopicSuffix).publish();
    }
    m_domains = domains;
    m_domainPublishers = publishers;
  }

  private boolean matchesCurrentTable(List<String> names) {
    if (names.size() != m_domains.length) {
      return false;
    }
    for (int i = 0; i < m_domains.length; i++) {
      if (!m_domains[i].equals(names.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void closePublishers() {
    for (DoublePublisher p : m_domainPublishers) {
      p.close();
    }
    m_domains = new String[0];
    m_domainPublishers = new DoublePublisher[0];
  }
}

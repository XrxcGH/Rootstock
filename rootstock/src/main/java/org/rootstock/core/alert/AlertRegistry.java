package org.rootstock.core.alert;

import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Answers the one question nobody can currently answer: <strong>is the robot OK right now?</strong>
 *
 * <p>Holds every {@link RootstockAlert} created through {@link Alerts}, evaluates the self-driving
 * ones, ranks the blocking ones, and enforces the alert budget. It is evaluated on the round-robin
 * health slice — one {@link #poll()} per sweep, roughly every 220 ms on a stock robot — never at
 * 50 Hz and never on a wall clock.
 *
 * <p><strong>Two numbers live here and they answer two different questions.</strong> Conflating them
 * is what produced two contradictory CI gates in the design, so they are named separately:
 *
 * <ul>
 *   <li>{@link #kBlockingBudget} = <strong>5</strong> — the <em>CI budget</em>: how many
 *       {@code BLOCKS_MATCH} alerts may be simultaneously active on a bare robot with no hardware
 *       present. A hardware-free robot honestly cannot play a match, so zero would force the library
 *       to lie about the week-2 case; a sixth means something has been mis-classified.
 *   <li>{@link #kDriverDisplayCap} = <strong>3</strong> — the <em>display cap</em>: how many
 *       blocking alerts the driver tab shows, with a {@code +N more} rollup for the rest. Three rows
 *       is what a human reads in the two seconds between "robot's on the field" and "hands on the
 *       sticks"; seventeen is a wall of red that trains people to ignore it.
 * </ul>
 *
 * <p><strong>Exceeding the budget never drops an alert.</strong> Silently discarding the sixth
 * blocking alert would be the worst possible failure for a library whose headline property is
 * zero-mystery debugging. Instead {@link #poll()} raises one extra {@code WARNING} /
 * {@code PIT_ONLY} alert — itself not blocking, so it cannot cascade — whose text names the count,
 * every offender, and the two ways to fix it. {@link #budgetDiagnosis()} returns the same text for
 * a CI assertion or a pit printout.
 *
 * <p>All state is static. There is one robot.
 */
public final class AlertRegistry {

  /**
   * The CI alert budget: at most this many {@code BLOCKS_MATCH} alerts may be simultaneously active
   * on the bare example robot with no hardware present. Five is the number of distinct root causes a
   * student can hold in their head.
   */
  public static final int kBlockingBudget = 5;

  /**
   * The driver-mirror display cap: {@code /Rootstock/Driver/Blocking} shows at most this many rows,
   * with {@code /Rootstock/Driver/BlockingMore} carrying the rollup for the rest.
   */
  public static final int kDriverDisplayCap = 3;

  /** Group used by the registry's own alerts, so they are attributable to the library. */
  private static final String kSelfGroup = "Rootstock";

  private static final List<RootstockAlert> m_alerts = new CopyOnWriteArrayList<>();
  private static final AtomicInteger m_nextIndex = new AtomicInteger();
  private static RootstockAlert m_budgetAlert;
  private static boolean m_budgetReported;
  private static boolean m_bridged;

  private AlertRegistry() {}

  /**
   * Register an alert. Called automatically for every alert created through {@link Alerts}; public
   * because a re-registration after {@link #resetForTest()} must be possible, and idempotent so that
   * calling it twice is harmless.
   *
   * @param alert the alert to register; null and closed alerts are ignored rather than throwing,
   *     because a registration race must not take the robot down
   */
  public static void register(RootstockAlert alert) {
    if (alert == null || alert.isClosed() || m_alerts.contains(alert)) {
      return;
    }
    m_alerts.add(alert);
  }

  /**
   * Every registered alert, in creation order, active or not.
   *
   * @return an immutable snapshot
   */
  public static List<RootstockAlert> all() {
    return List.copyOf(m_alerts);
  }

  /**
   * Every currently raised alert, in creation order.
   *
   * @return an immutable snapshot
   */
  public static List<RootstockAlert> active() {
    return m_alerts.stream().filter(RootstockAlert::isActive).collect(Collectors.toUnmodifiableList());
  }

  /**
   * The most severe active alert's severity.
   *
   * @return the worst active severity, or empty when nothing is raised
   */
  public static Optional<Severity> worst() {
    return m_alerts.stream()
        .filter(RootstockAlert::isActive)
        .map(RootstockAlert::severity)
        .max(Comparator.naturalOrder());
  }

  /**
   * Active alerts with {@code impact == BLOCKS_MATCH}, ranked by severity descending and then by
   * registration order. The ranking is stable across boots and across replay because registration
   * order is construction order, which is deterministic.
   *
   * @return an immutable snapshot, most important first
   */
  public static List<RootstockAlert> blocking() {
    return m_alerts.stream()
        .filter(RootstockAlert::isActive)
        .filter(a -> a.impact() == MatchImpact.BLOCKS_MATCH)
        .sorted(
            Comparator.comparing(RootstockAlert::severity)
                .reversed()
                .thenComparingInt(RootstockAlert::registrationIndex))
        .collect(Collectors.toUnmodifiableList());
  }

  /**
   * NO active {@code BLOCKS_MATCH} alert.
   *
   * <p>This is "this robot can play a match", <strong>not</strong> "nothing anywhere is imperfect". A
   * robot with nine {@code PIT_ONLY} warnings and no blocking alerts is ready and the LEDs go green,
   * because that is the honest answer and an honest answer is what makes the signal trusted. Drives
   * the LEDs, the pit display and the self-test gate.
   *
   * @return true when nothing is claiming the robot should stay off the field
   */
  public static boolean matchReady() {
    return m_alerts.stream()
        .noneMatch(a -> a.isActive() && a.impact() == MatchImpact.BLOCKS_MATCH);
  }

  /**
   * The at-most-{@value #kDriverDisplayCap} rows for {@code /Rootstock/Driver/Blocking}.
   *
   * <p>This method owns the <em>policy</em> (only blocking, capped, ranked); the telemetry domain
   * owns the transport that publishes it.
   *
   * @return an immutable list of {@code "Group: message"} rows, most important first
   */
  public static List<String> driverBlockingRows() {
    return blocking().stream()
        .limit(kDriverDisplayCap)
        .map(RootstockAlert::driverLine)
        .collect(Collectors.toUnmodifiableList());
  }

  /**
   * The rollup row for {@code /Rootstock/Driver/BlockingMore}.
   *
   * @return {@code "+N more - see the pit tab"}, or an empty string when everything fits
   */
  public static String driverBlockingMore() {
    int hidden = blocking().size() - kDriverDisplayCap;
    return hidden > 0 ? "+" + hidden + " more - see the pit tab" : "";
  }

  /**
   * Whether more than {@link #kBlockingBudget} blocking alerts are simultaneously active.
   *
   * @return true when the alert budget is exceeded right now
   */
  public static boolean isOverBudget() {
    return blocking().size() > kBlockingBudget;
  }

  /**
   * The full budget diagnosis: the count, every offender, and both fixes. Used by the registry's own
   * overflow alert, by the CI {@code AlertBudgetTest} failure message and by the pit printout, so
   * all three say the same thing.
   *
   * @return the diagnosis, or empty when the budget is not exceeded
   */
  public static Optional<String> budgetDiagnosis() {
    List<RootstockAlert> blocking = blocking();
    if (blocking.size() <= kBlockingBudget) {
      return Optional.empty();
    }
    String offenders =
        blocking.stream().map(RootstockAlert::describe).collect(Collectors.joining("\n  "));
    return Optional.of(
        "Alert budget exceeded: "
            + blocking.size()
            + " BLOCKS_MATCH alerts are active at once (budget "
            + kBlockingBudget
            + "). Each one claims the robot cannot play a match, and the driver tab only shows "
            + kDriverDisplayCap
            + " of them:\n  "
            + offenders
            + "\nNothing was dropped — all "
            + blocking.size()
            + " are still in AlertRegistry.blocking(). If one of these is really a 'a programmer "
            + "should look at this' alert, mark it MatchImpact.PIT_ONLY. If the hardware is "
            + "knowingly not installed, call Health.expectAbsent(healthName, reason).");
  }

  /**
   * A trigger that is true while any active alert is an {@code ERROR}.
   *
   * @return a new trigger on the default command scheduler button loop
   */
  public static Trigger anyError() {
    return new Trigger(() -> hasActiveAtLeast(Severity.ERROR));
  }

  /**
   * A trigger that is true while any active alert is a {@code WARNING} or worse.
   *
   * @return a new trigger on the default command scheduler button loop
   */
  public static Trigger anyWarning() {
    return new Trigger(() -> hasActiveAtLeast(Severity.WARNING));
  }

  /**
   * A trigger that is true while any active alert is {@code BLOCKS_MATCH} — i.e. the inverse of
   * {@link #matchReady()}. Bind LEDs to this.
   *
   * @return a new trigger on the default command scheduler button loop
   */
  public static Trigger anyBlocking() {
    return new Trigger(() -> !matchReady());
  }

  /**
   * Bridge every rising-edge activation to a dashboard notification. Called once by
   * {@code RootstockRobot}.
   *
   * <p>This only arms the bridge; delivery still requires the dashboard layer to have installed a
   * sink via {@link AlertBridge#setSink}. Until then rising edges are counted, not lost, so
   * "why did I never get a notification?" has an answer in {@link AlertBridge#describe()}.
   */
  public static void bridgeToDashboard() {
    m_bridged = true;
    AlertBridge.enable();
  }

  /**
   * Whether {@link #bridgeToDashboard()} has been called.
   *
   * @return true when the dashboard bridge is armed
   */
  public static boolean isBridged() {
    return m_bridged;
  }

  /**
   * The registry's round-robin slice: evaluates every self-driving alert, flushes pending text
   * updates, and re-checks the alert budget.
   *
   * <p>Registered as one slice of the shared health rotation by {@code RootstockRobot} — it must not
   * be called every loop, and it must not install its own rate gate.
   */
  public static void poll() {
    for (RootstockAlert alert : m_alerts) {
      alert.poll();
    }
    updateBudgetAlert();
  }

  /**
   * The rollup published to {@code /Rootstock/Health/Summary}. Returned as a value rather than
   * published here, because publishing is the telemetry domain's job and this package may not depend
   * on it.
   *
   * @return a snapshot of the whole-robot alert state
   */
  public static Summary summary() {
    List<RootstockAlert> blocking = blocking();
    return new Summary(worst(), active().size(), blocking.size(), blocking.isEmpty());
  }

  /**
   * The whole-robot alert rollup, published by the telemetry domain to
   * {@code /Rootstock/Health/Summary/{Worst, ActiveCount, BlockingCount, MatchReady}}.
   *
   * @param worst the most severe active severity, or empty when nothing is raised
   * @param activeCount how many alerts are raised
   * @param blockingCount how many of those are {@code BLOCKS_MATCH}
   * @param matchReady whether the robot can play a match
   */
  public record Summary(
      Optional<Severity> worst, int activeCount, int blockingCount, boolean matchReady) {

    /**
     * A one-line human-readable form for the pit text widget and the console boot dump.
     *
     * @return e.g. {@code "NOT READY - 2 blocking, 7 active, worst ERROR"}
     */
    public String describe() {
      return (matchReady ? "READY" : "NOT READY")
          + " - "
          + blockingCount
          + " blocking, "
          + activeCount
          + " active, worst "
          + worst.map(Enum::name).orElse("none");
    }
  }

  /**
   * A multi-line human-readable dump of every active alert, blocking ones first. For the pit
   * printout and the boot dump.
   *
   * @return the dump; a single line when nothing is raised
   */
  public static String describe() {
    List<RootstockAlert> active = active();
    if (active.isEmpty()) {
      return "Rootstock alerts: none active (" + m_alerts.size() + " registered)";
    }
    List<RootstockAlert> ordered = new ArrayList<>(blocking());
    active.stream().filter(a -> a.impact() != MatchImpact.BLOCKS_MATCH).forEach(ordered::add);
    return "Rootstock alerts: "
        + summary().describe()
        + "\n  "
        + ordered.stream().map(RootstockAlert::describe).collect(Collectors.joining("\n  "));
  }

  /**
   * How many alerts are registered, active or not.
   *
   * @return the registration count
   */
  public static int size() {
    return m_alerts.size();
  }

  /**
   * Close every registered alert and clear the registry. For tests only — a robot has one alert
   * registry for the life of the process.
   */
  public static void resetForTest() {
    for (RootstockAlert alert : List.copyOf(m_alerts)) {
      alert.close();
    }
    m_alerts.clear();
    m_nextIndex.set(0);
    m_budgetAlert = null;
    m_budgetReported = false;
    m_bridged = false;
    AlertBridge.resetForTest();
  }

  // --- package-private -------------------------------------------------------------------------

  /** Builds and registers an alert. The only construction path; {@link Alerts} validates first. */
  static RootstockAlert create(
      String group, String text, Severity severity, MatchImpact impact, BooleanSupplier condition) {
    RootstockAlert alert =
        new RootstockAlert(group, text, severity, impact, condition, m_nextIndex.getAndIncrement());
    m_alerts.add(alert);
    return alert;
  }

  /** Removes a closed alert. Called by {@link RootstockAlert#close()}. */
  static void unregister(RootstockAlert alert) {
    m_alerts.remove(alert);
  }

  private static boolean hasActiveAtLeast(Severity floor) {
    return m_alerts.stream().anyMatch(a -> a.isActive() && a.severity().atLeast(floor));
  }

  /**
   * Raises or clears the registry's own overflow alert. It is WARNING / PIT_ONLY on purpose: an
   * alert about too many blocking alerts must never itself be a blocking alert, or the budget check
   * makes the thing it is measuring worse.
   */
  private static void updateBudgetAlert() {
    Optional<String> diagnosis = budgetDiagnosis();
    if (diagnosis.isEmpty()) {
      if (m_budgetAlert != null) {
        m_budgetAlert.set(false);
      }
      m_budgetReported = false;
      return;
    }
    String headline =
        blocking().size()
            + " BLOCKS_MATCH alerts active at once (budget "
            + kBlockingBudget
            + ", driver tab shows "
            + kDriverDisplayCap
            + "). Nothing was dropped — see the console, or AlertRegistry.describe().";
    if (m_budgetAlert == null) {
      // Never BLOCKS_MATCH: an alert about too many blocking alerts must not cascade.
      m_budgetAlert = Alerts.warning(kSelfGroup, headline, MatchImpact.PIT_ONLY);
      m_budgetAlert.notifyDriver(false);
    } else {
      m_budgetAlert.text(headline);
    }
    m_budgetAlert.set(true);

    // The alert row is one line because that is all a dashboard row can carry; the full offender
    // list goes to the console once per crossing, because degrading silently is the one thing a
    // budget must never do.
    if (!m_budgetReported) {
      m_budgetReported = true;
      System.out.println("Rootstock: " + diagnosis.get());
    }
  }
}

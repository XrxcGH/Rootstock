package org.rootstock.core.health;

import edu.wpi.first.networktables.BooleanPublisher;
import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringArrayPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.alert.Severity;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.health.builtin.BatteryMonitor;
import org.rootstock.core.health.builtin.BrownoutMonitor;
import org.rootstock.core.health.builtin.CanBusMonitor;
import org.rootstock.core.health.builtin.DeployMonitor;
import org.rootstock.core.health.builtin.DsMonitor;
import org.rootstock.core.health.builtin.LoopTimeMonitor;
import org.rootstock.core.health.builtin.RailMonitor;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.util.EdgeDetector;

/**
 * The health registry: every {@link HealthSource} in the process, each registered as one slice of
 * {@link SliceScheduler}'s shared round-robin.
 *
 * <p><strong>Registration is not the advertised entry point (D27).</strong> Revision 1 of the design
 * had four parallel public registration lists over the same objects — {@code RootstockRegistry.addAll},
 * {@code SelfTest.registerAll}, {@code TuningRegistry.registerAll}, {@code HealthMonitor.watch} —
 * with different membership for non-obvious reasons, inside the very example that advertises "ONE
 * list". D27 collapsed them:
 *
 * <pre>{@code
 * RootstockRegistry.addAll(m_drive, m_elevator, m_arm);
 * }</pre>
 *
 * inspects each argument once and routes anything {@code instanceof HealthSource} to {@link
 * #watch(HealthSource)}. {@code watch} is {@code public} only because that router lives in a
 * different package; treat it as internal.
 *
 * <p><strong>Faults become alerts, unless the source says otherwise.</strong> A {@link Fault} has a
 * {@link Severity} but no {@link MatchImpact}, because "how bad is it" and "does it stop us taking
 * the field" are different questions. So this class mirrors each fault into a {@link RootstockAlert}
 * grouped by {@link HealthSource#healthName()}, mapping {@code ERROR -> BLOCKS_MATCH} and everything
 * else to {@code PIT_ONLY}. A source that knows better — every one of the seven built-in monitors,
 * because the built-in impact table is per <em>condition</em> and not per severity — calls {@link
 * #ownAlerts()} and raises its own.
 *
 * <p>All state is static. There is one robot.
 */
public final class HealthMonitor {

  /** Slice-name prefix, so every health source is timed against the one {@code Health} budget. */
  public static final String kSlicePrefix = "Health/";

  /** NetworkTables topic listing hardware declared absent via {@link #expectAbsent}. */
  public static final String kExpectedAbsentTopic = "/Rootstock/Health/ExpectedAbsent";

  /** NetworkTables topic carrying the cost of the last {@link #pollAll()}. */
  public static final String kPollAllTopic = "/Rootstock/SelfTest/PollAllMs";

  /**
   * Mirrored alerts per source, past which we stop creating handles and say so. A source producing
   * seventeen distinct devices is a source with a description that embeds a live value in the device
   * name; that is a bug in the check, and it would otherwise leak alert handles forever.
   */
  public static final int kMaxMirroredAlertsPerSource = 16;

  private static final Map<String, HealthMonitor> m_registrations = new LinkedHashMap<>();
  private static final Map<String, String> m_expectedAbsent = new TreeMap<>();
  private static final Set<String> m_fingerprints = new LinkedHashSet<>();
  private static final EdgeDetector m_enabled = new EdgeDetector(false);

  private static double m_lastPollAllMillis;
  private static StringArrayPublisher m_absentPublisher;
  private static DoublePublisher m_pollAllPublisher;

  private final HealthSource m_source;
  private final String m_name;
  private final List<Fault> m_current = new ArrayList<>();
  private final Map<String, RootstockAlert> m_mirrored = new LinkedHashMap<>();
  private final Collector m_collector = new Collector();

  private int m_everyNSweeps = 1;
  private boolean m_onlyInDiagnostics;
  private boolean m_ownAlerts;
  private boolean m_everyLoop;
  private long m_sweep;
  private long m_polls;
  private double m_lastFaultTime = -1.0;
  private boolean m_mirrorCapWarned;

  private BooleanPublisher m_okPublisher;
  private StringArrayPublisher m_faultsPublisher;
  private DoublePublisher m_lastFaultPublisher;

  private HealthMonitor(HealthSource source) {
    m_source = source;
    m_name = source.healthName();
  }

  // ---------------------------------------------------------------- registration

  /**
   * Register a source as one slice of the shared round-robin. Idempotent by {@link
   * HealthSource#healthName()}: registering the same name twice returns the existing handle and does
   * not lengthen the sweep.
   *
   * <p>Not the advertised entry point — see the class javadoc. Use {@code RootstockRegistry.addAll}.
   *
   * @param source the source to poll
   * @return the per-source handle, for {@link #everyNSweeps}, {@link #onlyInDiagnostics} and {@link
   *     #ownAlerts}
   * @throws IllegalArgumentException if {@code source} is null or its {@code healthName()} is blank
   */
  public static HealthMonitor watch(HealthSource source) {
    if (source == null) {
      throw new IllegalArgumentException("HealthMonitor.watch: source is null.");
    }
    String name = source.healthName();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "HealthMonitor.watch: "
              + source.getClass().getName()
              + ".healthName() is blank. The name is the NT subtable, the alert group, the slice "
              + "name and the expectAbsent() key - it cannot be empty.");
    }
    HealthMonitor existing = m_registrations.get(name);
    if (existing != null) {
      return existing;
    }
    HealthMonitor reg = new HealthMonitor(source);
    m_registrations.put(name, reg);
    SliceScheduler.register(kSlicePrefix + name, reg::poll);
    return reg;
  }

  /**
   * Poll this source only every <i>n</i>th time its slot comes up, for genuinely expensive checks —
   * a device configuration read-back, say.
   *
   * <p>There is deliberately no {@code rate(Frequency)} method: a Hz value implies a wall clock, and
   * wall clocks are what the round-robin removed. If you are thinking in Hz, convert once with
   * {@code Clock.periodCycles(Hertz.of(...))} and divide by {@link #sweepCycles()}.
   *
   * @param n sweeps between polls; 1 (the default) means every sweep
   * @return this, for chaining
   * @throws IllegalArgumentException if {@code n < 1}
   */
  public HealthMonitor everyNSweeps(int n) {
    if (n < 1) {
      throw new IllegalArgumentException(
          "HealthMonitor.everyNSweeps("
              + n
              + ") for \""
              + m_name
              + "\": must be >= 1. 1 means every sweep, which is the default.");
    }
    m_everyNSweeps = n;
    return this;
  }

  /**
   * Only poll this source while {@code MatchContext.isDiagnostics()}.
   *
   * <p>Off by default, and that default is deliberate: cheap checks must run <em>in a match</em>, or
   * they cannot tell you why you lost it.
   *
   * @param b true to restrict this source to Test mode
   * @return this, for chaining
   */
  public HealthMonitor onlyInDiagnostics(boolean b) {
    m_onlyInDiagnostics = b;
    return this;
  }

  /**
   * Move this source off the rotation and onto every loop.
   *
   * <p>Reserved for sources whose subject <em>is</em> the loop — {@link LoopTimeMonitor} is the only
   * one in the library. It costs a permanent per-loop tax, so it must be justified in the source's
   * javadoc and budgeted with {@link org.rootstock.core.diag.RootstockTracer}.
   *
   * <p>An every-loop source does not count towards {@link #sweepCycles()}, which is why {@link
   * #builtinSliceCount()} is eight rather than nine.
   *
   * @return this, for chaining
   */
  public HealthMonitor everyLoop() {
    SliceScheduler.unregister(kSlicePrefix + m_name);
    SliceScheduler.registerEveryLoop(kSlicePrefix + m_name, this::poll);
    m_everyLoop = true;
    return this;
  }

  /**
   * Whether this source runs every loop rather than taking a turn in the rotation.
   *
   * @return true if {@link #everyLoop()} was called
   */
  public boolean isEveryLoop() {
    return m_everyLoop;
  }

  /**
   * Declare that this source raises its own {@link RootstockAlert}s, so the registry must not mirror
   * its faults into alerts.
   *
   * <p>Used by the seven built-in monitors, whose {@link MatchImpact} table is per condition rather
   * than per severity — a battery resting below 12.3 V is only a {@code WARNING} but it
   * {@code BLOCKS_MATCH}, and a latched brownout is an {@code ERROR} that is {@code PIT_ONLY}
   * because the driver cannot act on it. No severity-to-impact mapping expresses both.
   *
   * <p>Not in the frozen design sketch; added because without it the built-in impact table cannot be
   * implemented and the four-of-eleven blocking-alert budget cannot hold.
   *
   * @return this, for chaining
   */
  public HealthMonitor ownAlerts() {
    m_ownAlerts = true;
    return this;
  }

  // ---------------------------------------------------------------- expected-absent

  /**
   * Declare that a device is knowingly not installed on this robot right now — mid-build, swapped
   * out, next year's mechanism.
   *
   * <p>Its faults are demoted to {@link Severity#INFO} + {@link MatchImpact#PIT_ONLY} and printed in
   * the boot dump instead of shouting from the alert panel. <strong>This is never guessed by the
   * library</strong>; a human says it, in code, with a reason:
   *
   * <pre>{@code
   * HealthMonitor.expectAbsent("Climber/leader", "not built yet, week 2");
   * }</pre>
   *
   * <p>This does not <em>silence</em> anything, it <em>relocates</em> it. The fact stays on screen,
   * stays named and stays attributable at {@value #kExpectedAbsentTopic} and in {@link
   * #expectedAbsentDump()} — it just stops claiming the robot cannot play a match. That is the
   * difference between suppression and triage.
   *
   * @param healthName the source name, or a device-name prefix such as {@code "Climber/leader"}.
   *     Matching is exact-or-prefix against both the source name and each fault's device
   * @param reason why it is absent, in words a mentor will recognise in three weeks. Required
   * @throws IllegalArgumentException if either argument is blank
   */
  public static void expectAbsent(String healthName, String reason) {
    if (healthName == null || healthName.isBlank()) {
      throw new IllegalArgumentException("HealthMonitor.expectAbsent: healthName is blank.");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(
          "HealthMonitor.expectAbsent(\""
              + healthName
              + "\"): reason is blank. The reason is the whole point - it is what tells the next "
              + "person whether to remove this line.");
    }
    m_expectedAbsent.put(healthName, reason);

    // Demote anything already raised under exactly this group. Prefix matching is applied to
    // FAULTS (where the device name carries the detail); alert groups are matched exactly, because
    // a fuzzy group match would quietly demote a neighbour's alert.
    for (RootstockAlert a : org.rootstock.core.alert.AlertRegistry.all()) {
      if (a.group().equals(healthName)) {
        a.demoteExpectedAbsent(reason);
      }
    }
    publishExpectedAbsent();
  }

  /**
   * Undo an {@link #expectAbsent} declaration, because the hardware landed.
   *
   * @param healthName the key that was passed to {@link #expectAbsent}
   * @return true if a declaration was removed
   */
  public static boolean clearExpectAbsent(String healthName) {
    boolean removed = m_expectedAbsent.remove(healthName) != null;
    if (removed) {
      publishExpectedAbsent();
    }
    return removed;
  }

  /**
   * The current expected-absent declarations.
   *
   * @return an unmodifiable name-to-reason map, sorted by name
   */
  public static Map<String, String> expectedAbsent() {
    return Collections.unmodifiableMap(new TreeMap<>(m_expectedAbsent));
  }

  /**
   * The boot dump block, printed once by {@code RootstockRobot} and published to {@value
   * #kExpectedAbsentTopic}.
   *
   * @return the empty string when nothing is declared absent, otherwise a multi-line block naming
   *     every declaration and the call to delete when the hardware lands
   */
  public static String expectedAbsentDump() {
    if (m_expectedAbsent.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Rootstock: ")
        .append(m_expectedAbsent.size())
        .append(" devices declared absent (alerts demoted to INFO)\n");
    for (Map.Entry<String, String> e : m_expectedAbsent.entrySet()) {
      sb.append("  ").append(e.getKey()).append("  - \"").append(e.getValue()).append("\"\n");
    }
    sb.append("Remove the HealthMonitor.expectAbsent(...) calls when the hardware lands.\n");
    return sb.toString();
  }

  // ---------------------------------------------------------------- polling

  /**
   * Poll every registered source once, right now, ignoring the round-robin.
   *
   * <p>This is the one place the ~10 ms burst is acceptable, because {@code SelfTest} runs it
   * disabled in the pit with no control loop to starve. Its duration is published to {@value
   * #kPollAllTopic} so the cost stays visible rather than becoming folklore.
   */
  public static void pollAll() {
    double start = Clock.seconds();
    for (HealthMonitor reg : new ArrayList<>(m_registrations.values())) {
      reg.pollNow();
    }
    m_lastPollAllMillis = (Clock.seconds() - start) * 1000.0;
    try {
      if (m_pollAllPublisher == null) {
        m_pollAllPublisher = NetworkTableInstance.getDefault().getDoubleTopic(kPollAllTopic).publish();
      }
      m_pollAllPublisher.set(m_lastPollAllMillis);
    } catch (RuntimeException e) {
      m_pollAllPublisher = null;
    }
  }

  /**
   * How long the last {@link #pollAll()} took.
   *
   * @return milliseconds, or 0 if {@link #pollAll()} has never run
   */
  public static double lastPollAllMillis() {
    return m_lastPollAllMillis;
  }

  /**
   * How many loops a full sweep takes right now. Published to {@code /Rootstock/Health/SweepCycles}.
   *
   * @return {@link SliceScheduler#sweepCycles()}, which counts non-health slices too — the rotation
   *     is shared, so the honest answer to "when will my check next run" includes them
   */
  public static int sweepCycles() {
    return SliceScheduler.sweepCycles();
  }

  /**
   * Every fault fingerprint seen since the robot was last enabled.
   *
   * <p>This is what {@code SelfTestRoutine.expectNoNewFaults()} diffs: snapshot before the routine,
   * snapshot after, and any addition is a fault the routine caused.
   *
   * @return an unmodifiable snapshot, in first-seen order
   */
  public static Set<String> faultFingerprints() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(m_fingerprints));
  }

  /**
   * The registered sources, in slice order.
   *
   * @return an unmodifiable list of health names
   */
  public static List<String> sources() {
    return Collections.unmodifiableList(new ArrayList<>(m_registrations.keySet()));
  }

  /**
   * Look up a registration by name.
   *
   * @param healthName the source name
   * @return the handle, or empty if nothing is registered under that name
   */
  public static Optional<HealthMonitor> registration(String healthName) {
    return Optional.ofNullable(m_registrations.get(healthName));
  }

  /**
   * The name of the source this handle polls.
   *
   * @return the health name
   */
  public String healthName() {
    return m_name;
  }

  /**
   * The faults reported by this source on its most recent poll, after expected-absent demotion.
   *
   * @return an unmodifiable snapshot
   */
  public List<Fault> faults() {
    return Collections.unmodifiableList(new ArrayList<>(m_current));
  }

  /**
   * Whether this source reported nothing on its last poll.
   *
   * @return true when the most recent poll produced no faults
   */
  public boolean isOk() {
    return m_current.isEmpty();
  }

  /**
   * How many times this source has actually been polled.
   *
   * @return the poll count since registration or {@link #resetForTest()}
   */
  public long pollCount() {
    return m_polls;
  }

  // ---------------------------------------------------------------- built-in counts

  /**
   * The number of built-in monitor <em>types</em> in {@code org.rootstock.core.health.builtin}.
   *
   * <p>Seven, and seven is load-bearing: it is asserted by {@code BuiltinMonitorCountTest} and
   * quoted in four documents. This document set has already had to withdraw a "ten competition-day
   * health checks" claim once, so the number is derived from the class list rather than typed.
   *
   * @return 7
   */
  public static int builtinTypeCount() {
    return builtinTypes().size();
  }

  /**
   * The number of health <em>slices</em> the built-ins register by default.
   *
   * <p>Eight, and the difference from seven is {@link RailMonitor}: six types register one slice
   * each, {@code RailMonitor} registers three (5 V / 3.3 V / 6 V), and {@link LoopTimeMonitor}
   * registers none because it is measuring loops and therefore runs every loop. 1+1+3+1+1+1+0 = 8.
   *
   * @return 8
   */
  public static int builtinSliceCount() {
    return 1 // CanBusMonitor.rio()
        + 1 // BatteryMonitor
        + 3 // RailMonitor: 5 V, 3.3 V, 6 V
        + 1 // BrownoutMonitor
        + 1 // DeployMonitor
        + 1 // DsMonitor
        + 0; // LoopTimeMonitor runs every loop, not as a slice
  }

  /**
   * The number of built-in alert <em>conditions</em> — the rows of the impact table in {@code
   * design/06} §8.5.
   *
   * <p>Eleven, because several types declare two conditions at different severities: CAN &gt;90 %
   * and &gt;70 %; battery resting and sag; rail 5 V and 3.3/6 V; deploy dirty and unknown
   * provenance. Exactly <strong>four</strong> of the eleven can reach the driver, which is why the
   * blocking-alert budget of five is a real constraint rather than a formality.
   *
   * @return 11
   */
  public static int builtinConditionCount() {
    return 2 // CanBusMonitor: >90% or bus-off delta (ERROR/BLOCKS), >70% (WARNING/PIT)
        + 2 // BatteryMonitor: resting <12.3 V (WARNING/BLOCKS), sag <9.0 V (WARNING/PIT)
        + 2 // RailMonitor: 5 V rising (ERROR/BLOCKS), 3.3 V or 6 V rising (WARNING/PIT)
        + 1 // BrownoutMonitor: latched brownout (ERROR/PIT)
        + 2 // DeployMonitor: DIRTY (WARNING/PIT), provenance UNKNOWN (INFO/PIT)
        + 1 // LoopTimeMonitor: over budget five sweeps running (WARNING/PIT)
        + 1; // DsMonitor: declared HID port empty or wrong controller (ERROR/BLOCKS)
  }

  /**
   * The seven built-in monitor types, as classes, so the count above is derived rather than typed.
   *
   * @return an unmodifiable list of exactly seven classes
   */
  public static List<Class<?>> builtinTypes() {
    return List.of(
        CanBusMonitor.class,
        BatteryMonitor.class,
        RailMonitor.class,
        BrownoutMonitor.class,
        DeployMonitor.class,
        LoopTimeMonitor.class,
        DsMonitor.class);
  }

  /**
   * Register the default set of built-in monitors: the eight slices plus {@link LoopTimeMonitor}'s
   * every-loop registration.
   *
   * <p>{@code RootstockRobot} calls this once, at construction. Idempotent — each monitor registers by
   * a fixed health name, and {@link #watch(HealthSource)} is idempotent by name.
   *
   * <p>Every one of these is on by default and each can be reconfigured or disabled by name
   * afterwards through {@link #registration(String)} and {@link SliceScheduler#unregister(String)}.
   */
  public static void installBuiltins() {
    CanBusMonitor.rio().register();
    BatteryMonitor.create().register();
    RailMonitor.watch5V().register();
    RailMonitor.watch3V3().register();
    RailMonitor.watch6V().register();
    BrownoutMonitor.latching().register();
    DeployMonitor.create().register();
    DsMonitor.create().register();
    LoopTimeMonitor.create().register();
  }

  // ---------------------------------------------------------------- machinery

  /** The slice body. One source, on the cycles whose index selects it. */
  private void poll() {
    if (m_everyNSweeps > 1 && (m_sweep++ % m_everyNSweeps) != 0) {
      return;
    }
    if (m_onlyInDiagnostics && !MatchContext.isDiagnostics()) {
      return;
    }
    pollNow();
  }

  private void pollNow() {
    // The enable edge clears the fingerprint set, so faultFingerprints() means "since enable".
    // Checked here rather than in an every-loop runnable because a health poll is the only consumer
    // and the rotation reaches it within one sweep.
    m_enabled.update(MatchContext.isEnabled());
    if (m_enabled.rising()) {
      m_fingerprints.clear();
    }

    m_polls++;
    m_collector.reset();
    m_source.pollHealth(m_collector);

    m_current.clear();
    for (Fault raw : m_collector.m_faults) {
      Optional<String> absent = absentReasonFor(raw.device());
      Fault f = absent.map(raw::demotedTo).orElse(raw);
      m_current.add(f);
      m_fingerprints.add(f.fingerprint());
    }
    if (!m_current.isEmpty()) {
      m_lastFaultTime = Clock.seconds();
    }

    if (!m_ownAlerts) {
      mirrorToAlerts();
    }
    publish();
  }

  private Optional<String> absentReasonFor(String device) {
    for (Map.Entry<String, String> e : m_expectedAbsent.entrySet()) {
      String key = e.getKey();
      if (device.equals(key) || device.startsWith(key) || m_name.equals(key)) {
        return Optional.of(e.getValue());
      }
    }
    return Optional.empty();
  }

  // One alert per (device, severity), not per fingerprint: the description is allowed to carry a
  // live value ("84.2 C"), and keying on it would mint a fresh alert handle every poll.
  private void mirrorToAlerts() {
    Set<String> live = new LinkedHashSet<>();
    for (Fault f : m_current) {
      String key = f.device() + " | " + f.severity();
      live.add(key);
      RootstockAlert alert = m_mirrored.get(key);
      if (alert == null) {
        if (m_mirrored.size() >= kMaxMirroredAlertsPerSource) {
          if (!m_mirrorCapWarned) {
            m_mirrorCapWarned = true;
            Alerts.warning(
                    m_name,
                    "more than "
                        + kMaxMirroredAlertsPerSource
                        + " distinct fault devices - Rootstock stopped mirroring them to alerts. "
                        + "Put the changing value in the description, not the device name.",
                    MatchImpact.PIT_ONLY)
                .set(true);
          }
          continue;
        }
        MatchImpact impact =
            f.severity() == Severity.ERROR ? MatchImpact.BLOCKS_MATCH : MatchImpact.PIT_ONLY;
        alert = Alerts.of(m_name, f.device() + ": " + f.description(), f.severity(), impact);
        absentReasonFor(f.device()).ifPresent(alert::demoteExpectedAbsent);
        m_mirrored.put(key, alert);
      }
      alert.sticky(f.sticky());
      alert.text(f.device() + ": " + f.description());
      alert.set(true);
    }
    for (Map.Entry<String, RootstockAlert> e : m_mirrored.entrySet()) {
      if (!live.contains(e.getKey())) {
        e.getValue().set(false);
      }
    }
  }

  private void publish() {
    try {
      if (m_okPublisher == null) {
        NetworkTableInstance nt = NetworkTableInstance.getDefault();
        String root = "/Rootstock/Health/" + m_name + "/";
        m_okPublisher = nt.getBooleanTopic(root + "Ok").publish();
        m_faultsPublisher = nt.getStringArrayTopic(root + "Faults").publish();
        m_lastFaultPublisher = nt.getDoubleTopic(root + "LastFaultTime").publish();
      }
      m_okPublisher.set(m_current.isEmpty());
      String[] rows = new String[m_current.size()];
      for (int i = 0; i < rows.length; i++) {
        rows[i] = m_current.get(i).describe();
      }
      m_faultsPublisher.set(rows);
      m_lastFaultPublisher.set(m_lastFaultTime);
    } catch (RuntimeException e) {
      // Publishing is the courtesy; checking is the job.
      m_okPublisher = null;
    }
  }

  private static void publishExpectedAbsent() {
    try {
      if (m_absentPublisher == null) {
        m_absentPublisher =
            NetworkTableInstance.getDefault().getStringArrayTopic(kExpectedAbsentTopic).publish();
      }
      List<String> rows = new ArrayList<>();
      for (Map.Entry<String, String> e : m_expectedAbsent.entrySet()) {
        rows.add(e.getKey() + " - \"" + e.getValue() + "\"");
      }
      m_absentPublisher.set(rows.toArray(new String[0]));
    } catch (RuntimeException e) {
      m_absentPublisher = null;
    }
  }

  /**
   * A multi-line dump of the registry, for the boot log and the pit printout.
   *
   * @return one line per registered source with its current fault count, plus the expected-absent
   *     block
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("HealthMonitor: ")
        .append(m_registrations.size())
        .append(" sources, sweep = ")
        .append(sweepCycles())
        .append(" cycles\n");
    for (HealthMonitor reg : m_registrations.values()) {
      sb.append("  ")
          .append(reg.m_name)
          .append(reg.m_ownAlerts ? " [own alerts]" : "")
          .append(reg.m_onlyInDiagnostics ? " [diagnostics only]" : "")
          .append(reg.m_everyNSweeps > 1 ? " [every " + reg.m_everyNSweeps + " sweeps]" : "")
          .append(" -> ")
          .append(reg.m_current.isEmpty() ? "OK" : reg.m_current.size() + " fault(s)")
          .append('\n');
      for (Fault f : reg.m_current) {
        sb.append("      ").append(f.describe()).append('\n');
      }
    }
    sb.append(expectedAbsentDump());
    return sb.toString();
  }

  /** Drop every registration and every declaration. Tests only. */
  public static void resetForTest() {
    m_registrations.clear();
    m_expectedAbsent.clear();
    m_fingerprints.clear();
    m_enabled.reset(false);
    m_lastPollAllMillis = 0.0;
    if (m_absentPublisher != null) {
      m_absentPublisher.close();
      m_absentPublisher = null;
    }
    if (m_pollAllPublisher != null) {
      m_pollAllPublisher.close();
      m_pollAllPublisher = null;
    }
  }

  /** Package-private view for {@link RobotHealth}. */
  static List<HealthMonitor> all() {
    return new ArrayList<>(m_registrations.values());
  }

  /** The reused, non-allocating collector handed to one source per poll. */
  private static final class Collector implements FaultCollector {
    private final List<Fault> m_faults = new ArrayList<>();

    void reset() {
      m_faults.clear();
    }

    @Override
    public void add(Fault f) {
      if (f != null) {
        m_faults.add(f);
      }
    }
  }
}

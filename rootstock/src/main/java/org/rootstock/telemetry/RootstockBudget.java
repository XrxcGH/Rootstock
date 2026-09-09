package org.rootstock.telemetry;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.LogConfig;
import org.rootstock.telemetry.replay.ReplayExempt;

/**
 * The byte-budget governor: how many bytes a cycle of logging costs, which keys cost them, and — under
 * sustained overload — which explicitly-marked keys get sampled slower until it fits.
 *
 * <p>Every documented AdvantageKit loop-time disaster traces to logging <i>volume</i>, not logging
 * <i>design</i>. This class is what lets a small team log aggressively at home without bricking itself
 * at an event. It is also the only lever left: with one logging backend there is no "switch to a
 * cheaper logger" escape any more, so the tiers, this governor and {@code ./gradlew logBudget} are the
 * whole of the CPU story and all three are volume levers.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>AdvantageKit exposes no per-key byte accounting — {@code Logger} has no size or statistics method
 * and {@code LogValue} has no length accessor — so Rootstock computes every byte number itself, in
 * three buckets, and only two of them are governable.
 *
 * <table border="1">
 *   <caption>The three buckets</caption>
 *   <tr><th>Bucket</th><th>What is in it</th><th>Measured how</th><th>Governable</th></tr>
 *   <tr><td>A — facade</td><td>every {@code RootstockLog.critical/log/debug} call</td>
 *       <td>sized at the call site from the static type, before delegating</td><td><b>yes</b></td></tr>
 *   <tr><td>B — inputs</td><td>every field of every struct passed to {@code processInputs}</td>
 *       <td>by enumerating the {@code LogTable} the sizing wrapper just wrote</td>
 *       <td><b>no</b> — sized so it is <i>visible</i>, never demoted</td></tr>
 *   <tr><td>C — framework</td><td>console capture, DS capture, AdvantageKit's own topics, WPILOG
 *       control records, direct {@code Logger} calls</td>
 *       <td>measured: the active log file's growth, minus A+B</td><td>no</td></tr>
 * </table>
 *
 * <p><b>{@code perCycleByteBudget} is a budget on governable bytes, A+B only.</b> The governor never
 * demotes a key to compensate for framework bytes it cannot control, because that would mutilate the
 * log in response to a number the team cannot act on.
 *
 * <p><b>A and B are upper bounds.</b> AdvantageKit's {@code WPILOGWriter} writes only values that
 * <i>changed</i> since the previous cycle, so a key whose value is unchanged costs nothing that cycle.
 * The governor deliberately triggers on the upper bound: the alternative is per-key change-rate
 * tracking, which is more state, more CPU and a trigger that moves when the robot's behaviour changes.
 * The direction of the error is the safe one — the governor fires <b>earlier</b> than strictly
 * necessary, never later.
 *
 * <h2>What demotion is, and what it is not</h2>
 *
 * <p>Demotion is publication every {@value #kDemotedPublishEveryN}th cycle. It is never deletion, never
 * a silent retype, and it applies only to keys an author explicitly marked {@link Demotable#YES}. When
 * the demotable set is exhausted and the log is still over budget the governor <b>stops and escalates
 * the alert to an error</b>. It does not go looking for other keys to cut: a team that logged itself
 * into a hole gets told so, it does not get a quietly mutilated log.
 *
 * <p><b>Replayed inputs are never demotable.</b> {@link #isDemotionEligible(String)} is the runtime
 * statement of that invariant and {@link Demotable} is the full argument. It is the property decision 3
 * was spent to buy, and it is the one thing in this class that is not a tuning decision.
 *
 * <p><b>The governor is a no-op in {@code REPLAY}.</b> It reads measured wall-clock byte rates, which
 * are meaningless at 50x. In replay it demotes nothing, so the recorded generation sequence stands
 * unchanged and the replay-diff tool's timeline partitioning still works.
 */
public final class RootstockBudget {

  /**
   * The fixed per-record WPILOG header cost, in bytes.
   *
   * <p>A record is a 1-byte length bitfield + a 1-4 byte entry ID + a 1-4 byte payload size + a 1-8
   * byte timestamp. Accounted as 1 + 2 (the reference robot has ~500 entries, which needs 2 bytes) + 2
   * (the largest key is under 64 kB) + 6 (microsecond timestamps exceed 2^32 us only across sessions)
   * = 11. One named constant, printed by {@code logBudget}, and re-measured against a real WPILOG at
   * M5 rather than trusted to the byte today.
   */
  public static final int kHeaderBytes = 11;

  /** A demoted key publishes on one cycle in this many. */
  public static final int kDemotedPublishEveryN = 5;

  /** Consecutive cycles over budget before the governor demotes one key. */
  public static final int kOverBudgetCycles = 50;

  /** Consecutive cycles comfortably under budget before the governor restores one key. */
  public static final int kRestoreCycles = 500;

  /**
   * Restoration threshold as a fraction of the budget.
   *
   * <p>The hysteresis is deliberate: a governor that flaps produces a log with dozens of schema
   * generations and is worse than one that never restores.
   */
  public static final double kRestoreFraction = 0.70;

  /** How many cycles the p95 window covers. */
  public static final int kP95WindowCycles = 250;

  /** Per-key attribution runs on one cycle in this many — once a second at 50 Hz. */
  public static final int kAttributionPeriodCycles = 50;

  /** The alert group every governor finding is filed under. */
  public static final String kAlertGroup = "Rootstock/Log";

  /** The synthetic {@link #topKeys(int)} entry that carries bucket C, so the list sums to the truth. */
  public static final String kFrameworkKey = "(Framework)";

  private static final int[] m_p95Window = new int[kP95WindowCycles];
  private static final int[] m_p95Scratch = new int[kP95WindowCycles];
  private static final Map<String, Integer> m_attribution = new LinkedHashMap<>();
  private static final Set<String> m_demotable = new LinkedHashSet<>();
  private static final List<String> m_demoted = new ArrayList<>();
  private static final Set<String> m_rejectedDemotions = new LinkedHashSet<>();

  private static List<Map.Entry<String, Integer>> m_topKeys = List.of();

  private static long m_cycle;
  private static int m_p95Index;
  private static int m_p95Filled;
  private static int m_facadeBytes;
  private static int m_inputBytes;
  private static int m_lastFacadeBytes;
  private static int m_lastInputBytes;
  private static int m_lastCycleBytes;
  private static int m_p95CycleBytes;

  private static boolean m_frameworkKnown;
  private static int m_frameworkBytes;
  private static long m_lastFileSize = -1L;
  private static int m_cyclesSinceSample;
  private static long m_governableSinceSample;

  private static int m_overBudgetCycles;
  private static int m_underBudgetCycles;
  private static long m_schemaGeneration;
  private static double m_lastChangeSeconds;
  private static String m_governorReason = "";

  private static RootstockAlert m_budgetAlert;
  private static RootstockAlert m_exhaustedAlert;
  private static RootstockAlert m_invariantAlert;
  private static RootstockAlert m_overcountAlert;

  private RootstockBudget() {}

  // ===============================================================================================
  // The numbers
  // ===============================================================================================

  /**
   * Governable bytes written last cycle: bucket A + bucket B.
   *
   * @return the byte count
   */
  public static int lastCycleBytes() {
    return m_lastCycleBytes;
  }

  /**
   * Rolling p95 of {@link #lastCycleBytes()} over the last {@value #kP95WindowCycles} cycles. This is
   * the governor's trigger.
   *
   * @return the 95th-percentile governable byte count
   */
  public static int p95CycleBytes() {
    return m_p95CycleBytes;
  }

  /**
   * Bucket A only — what the {@link RootstockLog} facade wrote last cycle.
   *
   * @return the byte count
   */
  public static int lastCycleFacadeBytes() {
    return m_lastFacadeBytes;
  }

  /**
   * Bucket B only — what {@code processInputs} wrote last cycle.
   *
   * <p>Never demotable. It is reported so a team can see that its <i>input schema</i>, not its log
   * calls, is the problem — which is a design-time fix (subscribe fewer signals) and not a runtime one.
   *
   * @return the byte count
   */
  public static int lastCycleInputBytes() {
    return m_lastInputBytes;
  }

  /**
   * Bucket C: measured log-file growth per cycle, minus (A + B).
   *
   * <p>Empty until the first once-per-second sample lands, always empty in {@code REPLAY}, and empty
   * when the active log file cannot be found — in which case {@code logBudget} prints
   * {@code "Framework: unmeasured"}. Folding an estimate into A+B instead would make the two numbers a
   * team <i>can</i> act on untrustworthy, which is the wrong trade.
   *
   * @return the framework byte count per cycle, or empty when unmeasured
   */
  public static OptionalInt lastCycleFrameworkBytes() {
    return m_frameworkKnown ? OptionalInt.of(m_frameworkBytes) : OptionalInt.empty();
  }

  /**
   * Per-key attribution over buckets A and B, sorted descending, refreshed on the once-per-second
   * attribution cycle.
   *
   * <p>Bucket C appears as a single synthetic entry named {@value #kFrameworkKey} when it is known, so
   * the list always sums to the measured truth rather than to the part Rootstock can see.
   *
   * @param n how many entries to return
   * @return an immutable list, largest first
   */
  public static List<Map.Entry<String, Integer>> topKeys(int n) {
    if (n <= 0 || m_topKeys.isEmpty()) {
      return List.of();
    }
    return List.copyOf(m_topKeys.subList(0, Math.min(n, m_topKeys.size())));
  }

  /**
   * The record-header constant the runtime uses, exposed so {@code ./gradlew logBudget} and the M5 gate
   * print the same numbers.
   *
   * @return {@value #kHeaderBytes}
   */
  public static int headerBytes() {
    return kHeaderBytes;
  }

  // ===============================================================================================
  // The demotable set and the governor's state
  // ===============================================================================================

  /**
   * The complete, closed set of keys the governor is permitted to touch.
   *
   * <p>Contains no key whose path contains {@code "/Inputs/"} and none under {@code Rootstock/Driver/} —
   * asserted here at runtime, by {@code rootstock-lint} at build time, and by the test suite.
   *
   * @return an immutable set, in the order keys were marked (G7)
   */
  public static Set<String> demotableKeys() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(m_demotable));
  }

  /**
   * Every key currently demoted to every-{@value #kDemotedPublishEveryN}th-cycle publication, in
   * demotion order.
   *
   * @return an immutable list
   */
  public static List<String> demotedKeys() {
    return List.copyOf(m_demoted);
  }

  /**
   * Monotonic, starting at 0, bumped on every demotion <b>and</b> every restoration.
   *
   * <p>Mirrors {@code Rootstock/Log/SchemaGeneration}. A generation bump is the only legal way for the
   * effective schema to change mid-session; any other schema change is a bug. Consumers must honour it:
   * the replay-diff tool treats a bump as an expected discontinuity rather than a diff, and the triage
   * workflow prints "the log's sample rate changed at t=..." instead of leaving a student staring at a
   * trace with holes in it.
   *
   * @return the generation
   */
  public static long schemaGeneration() {
    return m_schemaGeneration;
  }

  /**
   * Why the last generation bump happened, in the form
   * {@code "p95=8214B > budget=6000B for 50 cycles"}.
   *
   * <p>The quoted bytes are <b>governable</b> bytes (A+B), which is what {@code perCycleByteBudget}
   * bounds.
   *
   * @return the reason, or an empty string before the first bump
   */
  public static String governorReason() {
    return m_governorReason;
  }

  /**
   * The timestamp of the last generation bump, in seconds on the injected clock.
   *
   * @return the timestamp, or 0 before the first bump
   */
  public static double lastChangeSeconds() {
    return m_lastChangeSeconds;
  }

  /**
   * Whether a key may ever be marked {@link Demotable#YES}. <b>The runtime statement of the input
   * invariant.</b>
   *
   * <p>Two families are permanently ineligible and neither is a judgement call:
   *
   * <ul>
   *   <li>Anything containing {@code "/Inputs/"}. A replayed input demoted to one cycle in five hands
   *       {@code fromLog} a stale value on the other four while the real robot read a fresh one, and
   *       every output derived from it diverges. That makes the byte-identical replay guarantee false.
   *   <li>Anything under {@code Rootstock/Driver/}. The driver mirror is ~30 low-rate topics that a human
   *       is looking at while the robot is moving. It is exempt from the governor entirely, rather than
   *       merely unmarked.
   * </ul>
   *
   * @param key the log key
   * @return true if the governor is permitted to slow this key down
   */
  public static boolean isDemotionEligible(String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    if (key.contains("/Inputs/")) {
      return false;
    }
    return !key.startsWith("Rootstock/Driver/") && !key.startsWith("/Rootstock/Driver/");
  }

  /**
   * Keys that were passed {@link Demotable#YES} and refused by {@link #isDemotionEligible(String)}.
   *
   * <p>Non-empty is always a defect, and always the same defect: something tried to make a replayed
   * input or a driver topic demotable. Exposed rather than only alerted so a test can assert emptiness.
   *
   * @return an immutable set, in the order the attempts happened
   */
  public static Set<String> rejectedDemotions() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(m_rejectedDemotions));
  }

  /**
   * A multi-line dump for the boot log and for {@code ./gradlew logBudget}.
   *
   * @return the human-readable state
   */
  public static String describe() {
    StringBuilder out = new StringBuilder("RootstockBudget (HEADER_BYTES=" + kHeaderBytes + ")");
    out.append(String.format("%n  lastCycleBytes  = %d B (facade %d + inputs %d)",
        m_lastCycleBytes, m_lastFacadeBytes, m_lastInputBytes));
    out.append(String.format("%n  p95CycleBytes   = %d B", m_p95CycleBytes));
    out.append(String.format("%n  framework       = %s",
        m_frameworkKnown ? m_frameworkBytes + " B/cycle (measured)" : "unmeasured"));
    out.append(String.format("%n  schemaGeneration= %d", m_schemaGeneration));
    out.append(String.format("%n  demotable       = %s", m_demotable));
    out.append(String.format("%n  demoted         = %s", m_demoted));
    if (!m_governorReason.isEmpty()) {
      out.append(String.format("%n  lastChange      = %s (t=%.2f s)", m_governorReason,
          m_lastChangeSeconds));
    }
    return out.toString();
  }

  /** Clears every static, so one test cannot see another test's bytes. */
  public static void resetForTest() {
    Arrays.fill(m_p95Window, 0);
    m_attribution.clear();
    m_demotable.clear();
    m_demoted.clear();
    m_rejectedDemotions.clear();
    m_topKeys = List.of();
    m_cycle = 0;
    m_p95Index = 0;
    m_p95Filled = 0;
    m_facadeBytes = 0;
    m_inputBytes = 0;
    m_lastFacadeBytes = 0;
    m_lastInputBytes = 0;
    m_lastCycleBytes = 0;
    m_p95CycleBytes = 0;
    m_frameworkKnown = false;
    m_frameworkBytes = 0;
    m_lastFileSize = -1L;
    m_cyclesSinceSample = 0;
    m_governableSinceSample = 0L;
    m_overBudgetCycles = 0;
    m_underBudgetCycles = 0;
    m_schemaGeneration = 0L;
    m_lastChangeSeconds = 0.0;
    m_governorReason = "";
    m_budgetAlert = null;
    m_exhaustedAlert = null;
    m_invariantAlert = null;
    m_overcountAlert = null;
  }

  // ===============================================================================================
  // The facade's side. Package-private: RootstockLog is the only caller, by construction.
  // ===============================================================================================

  /**
   * Marks a key demotable, or refuses and says why.
   *
   * <p>Called from {@code RootstockLog}'s trailing-{@code Demotable} overloads. Refusal is loud and
   * permanent rather than silent, because a silently-refused marking looks exactly like a marking that
   * worked.
   */
  static void markDemotable(String key) {
    if (isDemotionEligible(key)) {
      m_demotable.add(key);
      return;
    }
    if (!m_rejectedDemotions.add(key)) {
      return;
    }
    String message =
        "DEMOTABLE_REFUSED: "
            + key
            + " was passed Demotable.YES and cannot be demotable. "
            + (key.contains("/Inputs/")
                ? "It is a replayed INPUT: demoting it makes fromLog return a stale value on four "
                    + "cycles in five during replay while the robot read a fresh one, which breaks "
                    + "the byte-identical replay guarantee. "
                : "It is under Rootstock/Driver/, the driver mirror, which is exempt from the governor "
                    + "entirely because a human is reading it while the robot is moving. ")
            + "Fix: pass Demotable.NO (or omit the parameter), and reduce input bytes at design time "
            + "by subscribing fewer signals — ./gradlew logBudget prints which.";
    if (m_invariantAlert == null) {
      m_invariantAlert = Alerts.error(kAlertGroup, message, MatchImpact.PIT_ONLY);
    } else {
      m_invariantAlert.text(message);
    }
    m_invariantAlert.sticky(true).set(true);
  }

  /**
   * Whether a demoted key should skip publication this cycle.
   *
   * <p>Cheap by design: a list membership test over a set that is empty on every robot that is not over
   * budget, which is nearly all of them.
   */
  static boolean isSuppressedThisCycle(String key) {
    if (m_demoted.isEmpty()) {
      return false;
    }
    return m_demoted.contains(key) && m_cycle % kDemotedPublishEveryN != 0;
  }

  /** Bucket A: one facade call's payload, plus the record header. */
  static void recordFacadeBytes(String key, int payloadBytes) {
    int total = payloadBytes + kHeaderBytes;
    m_facadeBytes += total;
    if (isAttributionCycle()) {
      m_attribution.merge(key, total, Integer::sum);
    }
  }

  /** Bucket B: one input field's payload, plus the record header. */
  static void recordInputBytes(String key, int payloadBytes) {
    int total = payloadBytes + kHeaderBytes;
    m_inputBytes += total;
    if (isAttributionCycle()) {
      m_attribution.merge(key, total, Integer::sum);
    }
  }

  /**
   * Bucket B on a non-attribution cycle: the total this key measured last time, headers already
   * included.
   *
   * <p>Per-field attribution costs a {@code LogTable} walk, so it runs once a second. The <i>total</i>
   * is needed every cycle, because that is what the governor triggers on. Re-using the last measured
   * total is the compromise: a per-cycle number at once-per-second measurement cost. It is exact
   * whenever the input schema is stable, which it is by construction — an inputs struct writes the same
   * field set every cycle, and a field set that changes is a schema change with its own generation bump.
   */
  static void addInputBytes(int totalBytes) {
    m_inputBytes += totalBytes;
  }

  /** Whether this is the once-per-second cycle on which per-key attribution is collected. */
  static boolean isAttributionCycle() {
    return m_cycle % kAttributionPeriodCycles == 0;
  }

  /** Opens a cycle. Called from {@code RootstockLog.beforeUserPeriodic()}. */
  static void beginCycle() {
    m_cycle++;
    m_facadeBytes = 0;
    m_inputBytes = 0;
    if (isAttributionCycle()) {
      m_attribution.clear();
    }
  }

  /**
   * Closes a cycle: totals the buckets, refreshes p95, samples bucket C, and runs the governor.
   *
   * <p>Called from {@code RootstockLog.afterUserPeriodic()}.
   */
  static void endCycle(LogConfig config, boolean replay) {
    m_lastFacadeBytes = m_facadeBytes;
    m_lastInputBytes = m_inputBytes;
    m_lastCycleBytes = m_facadeBytes + m_inputBytes;

    // Write at a dedicated index rather than at (cycle % N): the percentile reads the prefix
    // [0, m_p95Filled), so the first sample must land at slot 0 or the window's warm-up reports a
    // p95 of zero — which would make the governor blind for its first 250 cycles.
    m_p95Window[m_p95Index] = m_lastCycleBytes;
    m_p95Index = (m_p95Index + 1) % kP95WindowCycles;
    if (m_p95Filled < kP95WindowCycles) {
      m_p95Filled++;
    }
    m_p95CycleBytes = percentile95();

    m_cyclesSinceSample++;
    m_governableSinceSample += m_lastCycleBytes;

    if (isAttributionCycle()) {
      refreshTopKeys();
      if (!replay) {
        sampleFrameworkBucket(config);
      }
    }

    if (!replay) {
      runGovernor(config);
    }
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  private static int percentile95() {
    if (m_p95Filled == 0) {
      return 0;
    }
    System.arraycopy(m_p95Window, 0, m_p95Scratch, 0, m_p95Filled);
    Arrays.sort(m_p95Scratch, 0, m_p95Filled);
    int index = (int) Math.ceil(0.95 * m_p95Filled) - 1;
    return m_p95Scratch[Math.max(0, Math.min(index, m_p95Filled - 1))];
  }

  private static void refreshTopKeys() {
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(m_attribution.size() + 1);
    for (Map.Entry<String, Integer> entry : m_attribution.entrySet()) {
      entries.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
    }
    if (m_frameworkKnown) {
      entries.add(new AbstractMap.SimpleImmutableEntry<>(kFrameworkKey, m_frameworkBytes));
    }
    entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    m_topKeys = List.copyOf(entries);
  }

  /**
   * Bucket C, by measurement rather than estimation.
   *
   * <p>A+B cannot see console capture, DS capture, AdvantageKit's internal topics or the WPILOG control
   * records that open every entry, so they systematically <b>undercount</b> file growth, always in the
   * same direction. This closes the gap by statting the active log file once a second and dividing the
   * growth by the cycles elapsed.
   *
   * <p>That is a filesystem read on a periodic path, which the replay rules otherwise forbid, so it is
   * an explicit named exemption rather than an oversight: it never affects control, it is skipped
   * entirely in {@code REPLAY}, and its topics are in the replay-diff tool's default ignore set. Any
   * failure to find or stat the file degrades to {@code unmeasured} — never to a folded-in estimate.
   */
  @ReplayExempt("byte-budget framework bucket stats the active log file; never affects control")
  private static void sampleFrameworkBucket(LogConfig config) {
    if (m_cyclesSinceSample <= 0) {
      return;
    }
    long size = activeLogSize(config);
    int cycles = m_cyclesSinceSample;
    long governable = m_governableSinceSample;
    m_cyclesSinceSample = 0;
    m_governableSinceSample = 0L;

    if (size < 0) {
      m_frameworkKnown = false;
      m_lastFileSize = -1L;
      return;
    }
    if (m_lastFileSize < 0 || size < m_lastFileSize) {
      // First sample, or the writer rolled over to a new file. Re-baseline rather than report a
      // negative or an absurd number.
      m_lastFileSize = size;
      m_frameworkKnown = false;
      return;
    }

    long growth = size - m_lastFileSize;
    m_lastFileSize = size;
    int framework = (int) ((growth - governable) / cycles);
    m_frameworkKnown = true;
    m_frameworkBytes = framework;

    if (framework < 0 && m_overcountAlert == null) {
      m_overcountAlert =
          Alerts.warning(
              kAlertGroup,
              "BYTE_ACCOUNTING_OVERCOUNT: measured file growth was "
                  + growth
                  + " B over "
                  + cycles
                  + " cycles but buckets A+B claimed "
                  + governable
                  + " B. The per-call size function is over-counting, which means the governor may "
                  + "fire earlier than it should. Fix: re-measure HEADER_BYTES and the payload table "
                  + "against a real WPILOG.",
              MatchImpact.PIT_ONLY);
      m_overcountAlert.sticky(true).set(true);
    }
  }

  /** The size of the newest log file under the configured folders, or -1 when it cannot be found. */
  private static long activeLogSize(LogConfig config) {
    long newest = -1L;
    long newestTime = Long.MIN_VALUE;
    for (String folder : List.of(config.wpilogFolder(), config.fallbackFolder())) {
      Path dir;
      try {
        dir = Path.of(folder);
      } catch (RuntimeException e) {
        continue;
      }
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.wpilog*")) {
        for (Path file : stream) {
          long modified = Files.getLastModifiedTime(file).toMillis();
          if (modified > newestTime) {
            newestTime = modified;
            newest = Files.size(file);
          }
        }
      } catch (IOException | RuntimeException e) {
        // Unmeasured is the honest degradation. Never fold an estimate into A+B.
        return -1L;
      }
    }
    return newest;
  }

  private static void runGovernor(LogConfig config) {
    double budget = config.perCycleByteBudget();
    if (m_p95CycleBytes > budget) {
      m_underBudgetCycles = 0;
      m_overBudgetCycles++;
      if (m_overBudgetCycles >= kOverBudgetCycles) {
        m_overBudgetCycles = 0;
        demoteOne(budget);
      }
      return;
    }

    m_overBudgetCycles = 0;
    if (m_p95CycleBytes < budget * kRestoreFraction) {
      m_underBudgetCycles++;
      if (m_underBudgetCycles >= kRestoreCycles) {
        m_underBudgetCycles = 0;
        restoreOne(budget);
      }
    } else {
      m_underBudgetCycles = 0;
    }
  }

  private static void demoteOne(double budget) {
    String reason =
        String.format(
            "p95=%dB > budget=%.0fB for %d cycles", m_p95CycleBytes, budget, kOverBudgetCycles);

    String candidate = mostExpensiveUndemoted();
    if (candidate == null) {
      // The demotable set is exhausted and we are still over. Stop demoting, escalate, and say the
      // honest thing: delete log calls or move them to DEBUG. Do not go looking for other keys to cut.
      String message =
          "LOG_BUDGET_EXHAUSTED: "
              + reason
              + ", and every Demotable.YES key is already demoted. The governor will not touch any "
              + "other key. Fix: delete log calls, or move them to RootstockLog.debug(...) so they are "
              + "dropped on FMS. ./gradlew logBudget names the fattest keys. Top now: "
              + topKeysSummary();
      if (m_exhaustedAlert == null) {
        m_exhaustedAlert = Alerts.error(kAlertGroup, message, MatchImpact.PIT_ONLY);
      } else {
        m_exhaustedAlert.text(message);
      }
      m_exhaustedAlert.sticky(true).set(true);
      return;
    }

    m_demoted.add(candidate);
    bumpGeneration(reason + "; demoted " + candidate);

    String message =
        "LOG_BUDGET_EXCEEDED: "
            + reason
            + ". Demoted "
            + candidate
            + " to one cycle in "
            + kDemotedPublishEveryN
            + ". Top keys: "
            + topKeysSummary();
    if (m_budgetAlert == null) {
      m_budgetAlert = Alerts.warning(kAlertGroup, message, MatchImpact.PIT_ONLY);
    } else {
      m_budgetAlert.text(message);
    }
    m_budgetAlert.sticky(true).set(true);
  }

  private static void restoreOne(double budget) {
    if (m_demoted.isEmpty()) {
      return;
    }
    String restored = m_demoted.remove(m_demoted.size() - 1);
    bumpGeneration(
        String.format(
            "p95=%dB < %.0f%% of budget=%.0fB for %d cycles; restored %s",
            m_p95CycleBytes, kRestoreFraction * 100.0, budget, kRestoreCycles, restored));
    if (m_demoted.isEmpty() && m_budgetAlert != null) {
      m_budgetAlert.set(false);
    }
  }

  private static void bumpGeneration(String reason) {
    m_schemaGeneration++;
    m_governorReason = reason;
    m_lastChangeSeconds = Clock.seconds();
  }

  /** The fattest demotable-but-not-yet-demoted key, by last attribution. Insertion order breaks ties. */
  private static String mostExpensiveUndemoted() {
    String best = null;
    int bestBytes = -1;
    for (String key : m_demotable) {
      if (m_demoted.contains(key)) {
        continue;
      }
      int bytes = m_attribution.getOrDefault(key, 0);
      if (bytes > bestBytes) {
        bestBytes = bytes;
        best = key;
      }
    }
    return best;
  }

  private static String topKeysSummary() {
    List<Map.Entry<String, Integer>> top = topKeys(3);
    if (top.isEmpty()) {
      return "(no attribution sample yet)";
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Integer> entry : top) {
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(entry.getKey()).append('=').append(entry.getValue()).append('B');
    }
    return out.toString();
  }
}

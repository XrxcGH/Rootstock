package org.rootstock.telemetry.replay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.littletonrobotics.junction.Logger;
import org.rootstock.core.RootstockException;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;

/**
 * The runtime tripwire: in {@code REPLAY} mode, it detects code doing something replay cannot
 * reproduce and <b>says so loudly</b> rather than letting the run produce a log that silently lies.
 *
 * <p>This is the runtime half of replay safety. The build-time half — {@code rootstock-lint} — only
 * reaches teams that forked the template, because a vendordep cannot add an {@code annotationProcessor}
 * line to a consumer's {@code build.gradle}. Every team gets this one, because it ships in the jar.
 *
 * <p><b>Why a tripwire and not a fix.</b> A diverged replay is worse than a failed one. The failure
 * mode this class exists to prevent is the one 6328's published comparison names about non-deterministic
 * replay engines: there is no way to distinguish accurate outputs from the inaccurate, diverged ones.
 * A run that stops and names the class is recoverable; a run that quietly drifts is not.
 *
 * <h2>What it checks</h2>
 *
 * <ol>
 *   <li><b>Thread growth.</b> {@link #install()} records {@code Thread.activeCount()}. A thread
 *       appearing after that is nondeterministic interleaving, and the odometry thread — the one
 *       sanctioned exception — is started inside drive IO before replay begins.
 *   <li><b>Monotonic time.</b> {@code Logger.getTimestamp()} must not go backwards between cycles. It
 *       going backwards means something is reading a clock the log did not inject.
 *   <li><b>Exactly one {@code processInputs} per registered IO key per cycle.</b> A missing key means
 *       an IO layer was skipped and its consumer is reading a stale struct; a duplicate means a
 *       subsystem is reading hardware twice in one cycle, and only one of those reads is in the log.
 * </ol>
 *
 * <p>There is deliberately no {@code SecurityManager} and no {@code -javaagent}. The poisoned-{@code
 * Random} half of the design is a <i>refusal</i>, not an interceptor: Rootstock simply does not hand
 * out a {@code Random}, and direct construction is the lint's job.
 *
 * <p>Every method here is safe to call in any mode. Outside {@code REPLAY} the tripwire is not
 * installed and every hook is a predictable no-op, so {@code RootstockLog} calls them unconditionally
 * rather than branching on the mode at every call site.
 */
public final class RootstockReplay {

  /** The alert group every tripwire finding is filed under. */
  public static final String kAlertGroup = "Rootstock/Replay";

  /**
   * How many distinct violations are retained.
   *
   * <p>A capped list, because the failure this catches is usually systemic: one skipped IO layer
   * produces one violation per cycle for the length of the log, and a student needs the first ten, not
   * the thirty thousandth.
   */
  public static final int kMaxViolations = 50;

  private static final List<String> m_violations = new ArrayList<>();
  private static final Set<String> m_seen = new LinkedHashSet<>();
  private static final Map<String, Integer> m_thisCycle = new LinkedHashMap<>();

  private static boolean m_installed;
  private static int m_baselineThreads;
  private static long m_lastTimestampMicros = Long.MIN_VALUE;
  private static long m_cycle;
  private static RootstockAlert m_alert;

  private RootstockReplay() {}

  /**
   * Arms the tripwire. Called by {@code RootstockLog.start()} when, and only when, a replay source is
   * installed.
   *
   * <p>Idempotent, and safe to call on a real robot — it simply records a thread baseline that nothing
   * will ever be compared against, because the per-cycle hooks return early when not installed.
   */
  public static void install() {
    if (m_installed) {
      return;
    }
    m_installed = true;
    m_baselineThreads = Thread.activeCount();
    m_lastTimestampMicros = Long.MIN_VALUE;
    m_cycle = 0;
  }

  /**
   * Whether the tripwire is armed.
   *
   * @return true after {@link #install()} in {@code REPLAY} mode
   */
  public static boolean isInstalled() {
    return m_installed;
  }

  /**
   * Opens a cycle: clears the per-key {@code processInputs} tally and checks that the injected clock
   * has not gone backwards.
   *
   * <p>Called by {@code RootstockLog.beforeUserPeriodic()}.
   */
  public static void beginCycle() {
    if (!m_installed) {
      return;
    }
    m_cycle++;
    m_thisCycle.clear();

    long now = Logger.getTimestamp();
    if (m_lastTimestampMicros != Long.MIN_VALUE && now < m_lastTimestampMicros) {
      violation(
          "TIME_WENT_BACKWARDS: Logger.getTimestamp() was "
              + now
              + " us this cycle and "
              + m_lastTimestampMicros
              + " us last cycle. The replay clock is monotonic, so something read a clock the log did "
              + "not inject. Fix: every duration in control code comes from Clock.seconds() or "
              + "RootstockLog.timestamp(); getFPGATimestamp(), RobotController.getFPGATime(), "
              + "System.nanoTime() and Instant.now() are not replayable.");
    }
    m_lastTimestampMicros = now;
  }

  /**
   * Records that {@code processInputs} ran for {@code key} this cycle.
   *
   * <p>Called by {@code RootstockLog.processInputs}. Counting here rather than at {@code Logger} is
   * exactly why the facade's {@code processInputs} survives as a thin alias: counting calls that went
   * straight to {@code Logger} would need bytecode inspection.
   *
   * @param key the IO key, e.g. {@code "Rootstock/Elevator"}
   */
  public static void noteProcessInputs(String key) {
    if (!m_installed) {
      return;
    }
    int count = m_thisCycle.merge(key, 1, Integer::sum);
    if (count == 2) {
      violation(
          "DUPLICATE_PROCESS_INPUTS: "
              + key
              + " was processed twice in one cycle. Only one of those two reads is in the log, so "
              + "replay sees one where the robot saw two. Fix: call io.updateInputs(inputs) and "
              + "RootstockLog.processInputs(key, inputs) exactly once, as the first two statements of "
              + "the subsystem's periodic(), and read only from `inputs` afterwards.");
    }
  }

  /**
   * Closes a cycle: every registered IO key must have been processed exactly once, and no thread may
   * have appeared.
   *
   * <p>Called by {@code RootstockLog.afterUserPeriodic()} with {@code RootstockLog.inputKeys()}.
   *
   * @param registeredKeys every key registered through {@code processInputs} this session, in
   *     insertion order
   */
  public static void endCycle(Collection<String> registeredKeys) {
    if (!m_installed) {
      return;
    }

    for (String key : registeredKeys) {
      if (!m_thisCycle.containsKey(key)) {
        violation(
            "MISSING_PROCESS_INPUTS: "
                + key
                + " was registered but not processed on cycle "
                + m_cycle
                + ". Its consumer is reading last cycle's struct while replay believes it is fresh. "
                + "Fix: call RootstockLog.processInputs(\""
                + key
                + "\", inputs) every cycle, unconditionally — never inside an if.");
      }
    }

    int threads = Thread.activeCount();
    if (threads > m_baselineThreads) {
      violation(
          "THREAD_STARTED_DURING_REPLAY: Thread.activeCount() went from "
              + m_baselineThreads
              + " at replay start to "
              + threads
              + ". A thread that mutates control state interleaves differently on every run, so the "
              + "replay cannot be byte-identical. Fix: move the work onto the loop, or into an IO "
              + "layer annotated @IoImplementation whose samples enter control only through "
              + "processInputs.");
      m_baselineThreads = threads;
    }
  }

  /**
   * Throws if anything replay cannot reproduce has been observed.
   *
   * <p>Public so a test can assert against it directly: {@code RootstockReplay.assertDeterministic()} at
   * the end of a replayed run is the whole of the standing gate's runtime half. It is <b>not</b> called
   * automatically from the loop — a throw out of {@code periodic()} would take the robot down for a
   * diagnostic, which is the failure this library refuses everywhere else. The loop raises an alert;
   * a test asserts.
   *
   * @throws RootstockException if {@link #violations()} is non-empty
   */
  public static void assertDeterministic() {
    if (m_violations.isEmpty()) {
      return;
    }
    throw new RootstockException(
        "RootstockReplay: this run did "
            + m_violations.size()
            + " thing(s) deterministic replay cannot reproduce, so its outputs are not the match's "
            + "outputs:"
            + System.lineSeparator()
            + "  - "
            + String.join(System.lineSeparator() + "  - ", m_violations));
  }

  /**
   * Everything the tripwire has caught, in the order it was first seen, deduplicated.
   *
   * @return an immutable list; empty is the only passing result
   */
  public static List<String> violations() {
    return List.copyOf(m_violations);
  }

  /**
   * A one-line summary for the boot dump and for {@code describe()} chains.
   *
   * @return a human-readable state
   */
  public static String describe() {
    if (!m_installed) {
      return "RootstockReplay: not installed (no replay source).";
    }
    return "RootstockReplay: armed, cycle "
        + m_cycle
        + ", "
        + m_violations.size()
        + " violation(s), thread baseline "
        + m_baselineThreads
        + ".";
  }

  /** Clears every static, so one test cannot see another test's violations. */
  public static void resetForTest() {
    m_violations.clear();
    m_seen.clear();
    m_thisCycle.clear();
    m_installed = false;
    m_baselineThreads = 0;
    m_lastTimestampMicros = Long.MIN_VALUE;
    m_cycle = 0;
    m_alert = null;
  }

  /**
   * Records a violation once, and raises one sticky alert naming the count.
   *
   * <p>Deduplicated on the full message so a skipped IO layer produces one row, not one row per cycle
   * for the length of the log.
   */
  private static void violation(String message) {
    if (!m_seen.add(message)) {
      return;
    }
    if (m_violations.size() < kMaxViolations) {
      m_violations.add(message);
    }
    if (m_alert == null) {
      m_alert = Alerts.error(kAlertGroup, message, MatchImpact.PIT_ONLY);
    } else {
      m_alert.text(
          "REPLAY_NOT_DETERMINISTIC: "
              + m_violations.size()
              + " findings; first: "
              + m_violations.get(0));
    }
    m_alert.sticky(true).set(true);
  }
}

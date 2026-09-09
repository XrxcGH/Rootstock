package org.rootstock.core.health;

import edu.wpi.first.networktables.IntegerPublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.diag.RootstockTracer;

/**
 * THE single slice scheduler: one shared round-robin for everything in Rootstock that is periodic
 * but not every-loop.
 *
 * <p><strong>Exactly one registered slice runs per robot loop</strong>, chosen as {@code
 * slices.get(cycle % slices.size())}. That single line is the entire scheduling policy, and it is
 * the answer to two separate bugs:
 *
 * <ul>
 *   <li><strong>Bursty cost.</strong> Polling every health check at 4 Hz does not make the work
 *       cheaper, it makes it lumpy: ~10 ms of fault checking arriving in one loop out of twelve, on
 *       a 20 ms budget that already carries library and user code. Loop overruns that appear once
 *       every 250 ms are the worst kind to debug because they correlate with nothing visible. One
 *       slice per loop is ~1 ms worst case and a flat tail.
 *   <li><strong>Nondeterminism under replay.</strong> A wall-clocked 4 Hz gate fires on different
 *       cycles when the same log is replayed at 50x, so replayed health output does not match what
 *       was recorded and every replay diff is an artifact of the harness. A replay tool that cries
 *       wolf is a replay tool nobody runs. <strong>This scheduler counts cycles. It never reads a
 *       clock.</strong>
 * </ul>
 *
 * <p><strong>Budget.</strong> The default registration is eleven slices — eight built-in health
 * slices plus {@code Alerts}, {@code Match} and {@code Tuning} — so a full sweep is 11 cycles = 220
 * ms at 50 Hz, within a rounding error of what the 4 Hz gate gave, at roughly a twelfth of the peak
 * cost. Registering a twelfth slice lengthens the sweep to 240 ms; it does <em>not</em> raise the
 * per-loop cost. The cost of "one more check" is latency, not overruns, which is the right thing to
 * trade. A swerve robot with two cameras and four mechanisms lands around eighteen slices — a 360 ms
 * sweep, and still a flat 1 ms tail.
 *
 * <p><strong>Nothing else may own a rate gate.</strong> {@code MatchContext.poll()}, {@code
 * TuningRegistry.slice()}, {@code AlertRegistry.poll()} and every {@link HealthSource} are slices.
 * <em>n</em> independent rotations reconstruct exactly the spike this class removed.
 *
 * <p>Three things are deliberately <em>not</em> slices and run every loop, because their whole value
 * is edge detection: {@code MatchContext}'s FMS/DS/alliance latching (the derived <em>publishing</em>
 * is the slice), {@code RumbleScheduler}, and {@code LedController}. They register through {@link
 * #registerEveryLoop(String, Runnable)} so that {@link #tick()} stays the one call site.
 *
 * <p>All state is static. There is one robot.
 */
public final class SliceScheduler {

  /** NetworkTables topic carrying the current sweep length, for the pit tab. */
  public static final String kSweepCyclesTopic = "/Rootstock/Health/SweepCycles";

  /** NetworkTables topic carrying the name of the slice that ran on the most recent loop. */
  public static final String kLastSliceTopic = "/Rootstock/Health/LastSlice";

  /** Alert group for the scheduler's own problems. */
  private static final String kGroup = "Rootstock";

  // LinkedHashMap: registration order is part of the replay contract, so iteration order must be
  // insertion order and must never depend on a hash. Idempotent by name.
  private static final Map<String, Runnable> m_slices = new LinkedHashMap<>();
  private static final Map<String, Runnable> m_everyLoop = new LinkedHashMap<>();
  private static final List<String> m_order = new ArrayList<>();

  private static long m_cycle;
  private static String m_lastSlice = "";
  private static long m_tickCount;

  private static final Map<String, Integer> m_failures = new LinkedHashMap<>();
  private static RootstockAlert m_failureAlert;

  private static IntegerPublisher m_sweepPublisher;
  private static StringPublisher m_lastSlicePublisher;

  private SliceScheduler() {}

  /**
   * Register one slice of the shared round-robin.
   *
   * <p>Registration order is part of the replay contract, so it is stable and it is insertion order.
   * Registration is idempotent by {@code name}: registering the same name twice replaces the
   * runnable and does not add a second slot, so a subsystem constructed twice in a test does not
   * silently double the sweep length.
   *
   * @param name a stable slice name. Use {@code "Domain"} or {@code "Domain/Detail"} — the segment
   *     before the first {@code /} selects the {@link RootstockTracer} budget the slice is timed
   *     against, so every health source is timed against the one {@code Health} budget
   * @param slice the work. Must not block and must complete in well under 1 ms
   * @throws IllegalArgumentException if {@code name} is blank or {@code slice} is null
   */
  public static void register(String name, Runnable slice) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "SliceScheduler.register: name is blank. The name is the replay contract and the tracer "
              + "budget key - pass something like \"Health/Arm\".");
    }
    if (slice == null) {
      throw new IllegalArgumentException(
          "SliceScheduler.register(\"" + name + "\"): slice is null.");
    }
    if (m_slices.put(name, slice) == null) {
      m_order.add(name);
    }
  }

  /**
   * Register work that must run on <em>every</em> loop rather than taking a turn in the rotation.
   *
   * <p>Reserved for the small set of things whose value is edge detection or whose subject is the
   * loop itself: {@code RumbleScheduler}, {@code LedController}, {@code LoopTimeMonitor}. Every
   * every-loop registration is a permanent per-loop tax, so it must be justified in its javadoc and
   * budgeted with {@link RootstockTracer}. If you are not sure, you want {@link #register}.
   *
   * <p>This method is not in the frozen design sketch. It is here so that {@link #tick()} remains
   * the single call site the robot loop has to know about; the alternative was a second list owned
   * by {@code RootstockRobot}, which is how you end up with two schedulers.
   *
   * @param name a stable name, same convention as {@link #register}
   * @param work the work; runs before the loop's one slice
   * @throws IllegalArgumentException if {@code name} is blank or {@code work} is null
   */
  public static void registerEveryLoop(String name, Runnable work) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("SliceScheduler.registerEveryLoop: name is blank.");
    }
    if (work == null) {
      throw new IllegalArgumentException(
          "SliceScheduler.registerEveryLoop(\"" + name + "\"): work is null.");
    }
    m_everyLoop.put(name, work);
  }

  /**
   * Remove a slice. Used by tests and by a subsystem being closed; not part of the normal robot
   * lifecycle.
   *
   * @param name the slice name
   * @return true if a slice or every-loop entry with that name was removed
   */
  public static boolean unregister(String name) {
    boolean removed = m_slices.remove(name) != null;
    if (removed) {
      m_order.remove(name);
    }
    return m_everyLoop.remove(name) != null || removed;
  }

  /**
   * Run every-loop work, then exactly ONE slice.
   *
   * <p>{@code RootstockRobot} calls this exactly once per {@code robotPeriodic()}. Nothing else calls
   * it.
   *
   * <p>Declared {@code public} rather than package-private as sketched in the design: the one caller
   * lives in {@code org.rootstock.core}, a different package, so package-private would not compile.
   * It is documented as internal instead.
   */
  public static void tick() {
    m_tickCount++;

    for (Map.Entry<String, Runnable> e : m_everyLoop.entrySet()) {
      runGuarded(e.getKey(), e.getValue());
    }

    if (m_order.isEmpty()) {
      m_lastSlice = "";
      return;
    }

    // THE entire scheduling policy. Cycle-counted, never wall-clocked.
    String name = m_order.get(Math.floorMod(m_cycle++, m_order.size()));
    m_lastSlice = name;
    runGuarded(name, m_slices.get(name));

    publish();
  }

  /**
   * How many loops a full sweep takes right now — i.e. how many cycles pass between two runs of the
   * same slice. Published to {@value #kSweepCyclesTopic}.
   *
   * @return the number of registered slices, or 0 when none are registered
   */
  public static int sweepCycles() {
    return m_order.size();
  }

  /**
   * The registered slice names, in the order they run. Order is the replay contract; this is how a
   * test asserts it did not change.
   *
   * @return an unmodifiable list in registration order
   */
  public static List<String> slices() {
    return Collections.unmodifiableList(new ArrayList<>(m_order));
  }

  /**
   * The names registered to run every loop.
   *
   * @return an unmodifiable list in registration order
   */
  public static List<String> everyLoopSlices() {
    return Collections.unmodifiableList(new ArrayList<>(m_everyLoop.keySet()));
  }

  /**
   * The cycle counter that selects the slice. Increments once per {@link #tick()}, from 0.
   *
   * @return the number of slices dispatched since boot or {@link #resetForTest()}
   */
  public static long cycle() {
    return m_cycle;
  }

  /**
   * The name of the slice that ran on the most recent {@link #tick()}.
   *
   * @return the slice name, or the empty string if nothing has run yet
   */
  public static String lastSlice() {
    return m_lastSlice;
  }

  /**
   * How many times {@link #tick()} has been called.
   *
   * @return the tick count since boot or {@link #resetForTest()}
   */
  public static long ticks() {
    return m_tickCount;
  }

  /**
   * How many times a given slice has thrown. A slice that throws is caught, counted and named in a
   * {@code PIT_ONLY} warning rather than being allowed to kill the robot loop.
   *
   * @param name the slice name
   * @return the failure count, 0 if the slice has never thrown
   */
  public static int failureCount(String name) {
    return m_failures.getOrDefault(name, 0);
  }

  /**
   * A multi-line dump of the rotation, for the boot log and {@code describe()} chains.
   *
   * @return the sweep length, the ordered slice list and the every-loop list
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("SliceScheduler: ")
        .append(m_order.size())
        .append(" slices (sweep = ")
        .append(m_order.size())
        .append(" cycles), ")
        .append(m_everyLoop.size())
        .append(" every-loop\n");
    for (int i = 0; i < m_order.size(); i++) {
      String n = m_order.get(i);
      sb.append("  [").append(i).append("] ").append(n);
      int f = failureCount(n);
      if (f > 0) {
        sb.append("  (").append(f).append(" failures)");
      }
      sb.append('\n');
    }
    for (String n : m_everyLoop.keySet()) {
      sb.append("  [every loop] ").append(n).append('\n');
    }
    return sb.toString();
  }

  /** Drop every registration and reset the cycle counter. Tests only. */
  public static void resetForTest() {
    m_slices.clear();
    m_everyLoop.clear();
    m_order.clear();
    m_failures.clear();
    m_cycle = 0;
    m_tickCount = 0;
    m_lastSlice = "";
    m_failureAlert = null;
    if (m_sweepPublisher != null) {
      m_sweepPublisher.close();
      m_sweepPublisher = null;
    }
    if (m_lastSlicePublisher != null) {
      m_lastSlicePublisher.close();
      m_lastSlicePublisher = null;
    }
  }

  // A slice that throws must not take the robot loop with it: the whole point of this package is
  // that a diagnostic never becomes the outage. Caught, counted, named.
  private static void runGuarded(String name, Runnable work) {
    if (work == null) {
      return;
    }
    int cut = name.indexOf('/');
    String section = cut < 0 ? name : name.substring(0, cut);
    RootstockTracer.Scope scope = RootstockTracer.section(section);
    try {
      work.run();
      scope.close();
    } catch (RuntimeException e) {
      scope.close();
      int n = m_failures.merge(name, 1, Integer::sum);
      if (m_failureAlert == null) {
        m_failureAlert =
            Alerts.warning(
                kGroup,
                "a health slice threw - see /Rootstock/Health/LastSlice",
                MatchImpact.PIT_ONLY);
      }
      m_failureAlert.set(true);
      m_failureAlert.text(
          "slice \""
              + name
              + "\" threw "
              + e.getClass().getSimpleName()
              + ": "
              + String.valueOf(e.getMessage())
              + " ("
              + n
              + " times). The loop continued; fix the check, it is not reporting.");
    }
  }

  private static void publish() {
    try {
      if (m_sweepPublisher == null) {
        NetworkTableInstance nt = NetworkTableInstance.getDefault();
        m_sweepPublisher = nt.getIntegerTopic(kSweepCyclesTopic).publish();
        m_lastSlicePublisher = nt.getStringTopic(kLastSliceTopic).publish();
      }
      m_sweepPublisher.set(m_order.size());
      m_lastSlicePublisher.set(m_lastSlice);
    } catch (RuntimeException e) {
      // NetworkTables is unavailable (a pure-JVM unit test, say). Scheduling is the job; publishing
      // is the courtesy. Never let the courtesy break the job.
      m_sweepPublisher = null;
      m_lastSlicePublisher = null;
    }
  }
}

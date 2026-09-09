package org.rootstock.core.hid;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj.GenericHID.RumbleType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.LifecycleHook;

/**
 * The one place that writes rumble to a controller, so two subsystems cannot fight over it.
 *
 * <p><strong>The failure this removes.</strong> WPILib exposes
 * {@code setRumble(RumbleType, double)} and nothing else: no getter, no duration, no arbitration and
 * no auto-clear on disable. So an intake writes {@code setRumble(kBothRumble, 0.5)}, an alignment
 * routine writes {@code setRumble(kBothRumble, 0.0)} a millisecond later, and the driver feels
 * whichever ran last. Worse, when a command is interrupted at the end of auto the rumble is simply
 * left on and buzzes through the whole of teleop.
 *
 * <p><strong>The arbitration rule, stated exactly.</strong> Every playing pattern is a
 * <em>request</em> with a priority. Each loop, for the left and right motors of each controller
 * <em>independently</em>, the highest-priority active request wins and its intensity is written.
 * Ties go to the most recently started request, because the newer fact is almost always the one the
 * driver needs. Left and right being independent is the point: a team can encode two facts at once —
 * "game piece acquired" on the left, "aligned to target" on the right.
 *
 * <p><strong>Disable behaviour.</strong> {@link #disabledInit()} clears every request and zeroes
 * every registered controller unconditionally. Requests arrive only from scheduled commands, which
 * the WPILib scheduler cancels on disable, so nothing can re-arm behind that. Note that this class
 * deliberately does <em>not</em> read {@code DriverStation} — only
 * {@code org.rootstock.core.match} is allowed to, and everything else goes through
 * {@code MatchContext}. If you need to gate rumble on some other condition, call
 * {@link #setSuppressed(boolean)}.
 *
 * <p><strong>Loop cost.</strong> This runs every loop, on purpose — its whole value is edge
 * behaviour, so it is not a scheduler slice. The work is O(active requests) doubles plus at most two
 * HID writes per controller, and the writes are skipped when the value has not changed.
 *
 * <p>It is a {@link LifecycleHook} at priority {@value #kPriority}, so {@code RootstockRobot} drives
 * it automatically. A team on its own base class calls {@link #tick()} once per loop and
 * {@link #stopAll()} from {@code disabledInit}.
 */
public final class RumbleScheduler implements LifecycleHook {

  /**
   * The lifecycle priority of this hook. Sits after telemetry (10), tuning (30) and sim (50) and
   * before viz (70): rumble output depends on nothing and nothing depends on it.
   */
  public static final int kPriority = 60;

  // Below this, a change is imperceptible and not worth an HID write.
  private static final double kWriteEpsilon = 1e-4;

  private static final RumbleScheduler s_instance = new RumbleScheduler();

  // Keyed by DS port, not by object identity: a team that constructs two CommandXboxController(0)
  // objects still has exactly one physical controller, and "zero every registered HID" has to mean
  // the physical one.
  private final Map<Integer, GenericHID> m_hids = new LinkedHashMap<>();
  private final Map<Integer, double[]> m_lastWritten = new LinkedHashMap<>();
  private final List<Request> m_requests = new ArrayList<>();

  private long m_nextId = 1L;
  private boolean m_suppressed;

  private RumbleScheduler() {}

  /**
   * The singleton.
   *
   * @return the one scheduler; there is exactly one set of controllers.
   */
  public static RumbleScheduler getInstance() {
    return s_instance;
  }

  // ---------------------------------------------------------------------------------------------
  // LifecycleHook
  // ---------------------------------------------------------------------------------------------

  @Override
  public String name() {
    return "RumbleScheduler";
  }

  @Override
  public int priority() {
    return kPriority;
  }

  /**
   * Arbitrates and writes, once per loop.
   *
   * <p>Runs <em>after</em> user periodic because that is when the command scheduler has started and
   * ended the commands that own the requests; arbitrating before it would act on last loop's set.
   */
  @Override
  public void afterUserPeriodic() {
    update();
  }

  /** Clears every request and zeroes every registered controller, unconditionally. */
  @Override
  public void disabledInit() {
    stopAll();
  }

  /** Zeroes every controller, so a JVM shutdown cannot leave a gamepad buzzing on the cart. */
  @Override
  public void close() {
    stopAll();
  }

  // ---------------------------------------------------------------------------------------------
  // Static entry points
  // ---------------------------------------------------------------------------------------------

  /** Arbitrates and writes rumble for every registered controller. Call once per loop. */
  public static void tick() {
    s_instance.update();
  }

  /**
   * Cancels every request and writes zero to every registered controller.
   *
   * <p>Safe to call at any time and idempotent.
   */
  public static void stopAll() {
    s_instance.m_requests.clear();
    for (Map.Entry<Integer, GenericHID> entry : s_instance.m_hids.entrySet()) {
      write(entry.getValue(), entry.getKey(), 0.0, 0.0);
    }
  }

  /**
   * Registers a controller so it is zeroed on disable even if it has never rumbled.
   *
   * <p>Requests register their controller automatically; this is for a team that wants the guarantee
   * up front.
   *
   * @param hid the controller.
   * @throws NullPointerException if {@code hid} is {@code null}.
   */
  public static void register(GenericHID hid) {
    if (hid == null) {
      throw new NullPointerException(
          "RumbleScheduler.register(null). Fix: pass the HID, e.g. m_driver.getHID().");
    }
    s_instance.m_hids.putIfAbsent(hid.getPort(), hid);
    s_instance.m_lastWritten.putIfAbsent(hid.getPort(), new double[] {Double.NaN, Double.NaN});
  }

  /**
   * Globally suppresses rumble output without cancelling requests.
   *
   * <p>While suppressed every controller is written zero and patterns keep running underneath, so
   * un-suppressing mid-pattern resumes where the pattern actually is rather than restarting it.
   * Provided because this class may not read {@code DriverStation} itself; wire it from
   * {@code MatchContext} if you want a match-state rule.
   *
   * @param suppressed {@code true} to force every motor to zero.
   */
  public static void setSuppressed(boolean suppressed) {
    s_instance.m_suppressed = suppressed;
    if (suppressed) {
      for (Map.Entry<Integer, GenericHID> entry : s_instance.m_hids.entrySet()) {
        write(entry.getValue(), entry.getKey(), 0.0, 0.0);
      }
    }
  }

  /**
   * Whether rumble output is currently suppressed.
   *
   * @return {@code true} if every motor is being forced to zero.
   */
  public static boolean isSuppressed() {
    return s_instance.m_suppressed;
  }

  /**
   * The intensity currently being written to one motor of one controller.
   *
   * <p>WPILib has no rumble getter; this is it. Useful in telemetry and in tests that assert the
   * arbitration outcome instead of guessing at it.
   *
   * @param hid  the controller.
   * @param side {@code kLeftRumble} or {@code kRightRumble}. {@code kBothRumble} returns the larger
   *     of the two, since "both" is not a motor.
   * @return the last written intensity in {@code [0, 1]}, or {@code 0} if the controller has never
   *     been written.
   */
  public static double intensity(GenericHID hid, RumbleType side) {
    double[] last = s_instance.m_lastWritten.get(hid.getPort());
    if (last == null) {
      return 0.0;
    }
    double left = Double.isNaN(last[0]) ? 0.0 : last[0];
    double right = Double.isNaN(last[1]) ? 0.0 : last[1];
    return switch (side) {
      case kLeftRumble -> left;
      case kRightRumble -> right;
      case kBothRumble -> Math.max(left, right);
    };
  }

  /**
   * The number of requests currently competing for a motor.
   *
   * @return the active request count across every controller.
   */
  public static int activeRequestCount() {
    return s_instance.m_requests.size();
  }

  /**
   * Every registered controller's DS port, in registration order.
   *
   * @return an unmodifiable view.
   */
  public static List<Integer> registeredPorts() {
    return Collections.unmodifiableList(new ArrayList<>(s_instance.m_hids.keySet()));
  }

  /**
   * A human-readable dump of the current arbitration state.
   *
   * @return one line per controller plus one line per active request.
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("RumbleScheduler[")
        .append(s_instance.m_hids.size())
        .append(" controllers, ")
        .append(s_instance.m_requests.size())
        .append(" active requests")
        .append(s_instance.m_suppressed ? ", SUPPRESSED]" : "]");
    for (Map.Entry<Integer, double[]> entry : s_instance.m_lastWritten.entrySet()) {
      double[] v = entry.getValue();
      sb.append(System.lineSeparator())
          .append(
              String.format(
                  Locale.ROOT,
                  "  port %d: left %.2f right %.2f",
                  entry.getKey(),
                  Double.isNaN(v[0]) ? 0.0 : v[0],
                  Double.isNaN(v[1]) ? 0.0 : v[1]));
    }
    for (Request r : s_instance.m_requests) {
      sb.append(System.lineSeparator())
          .append(
              String.format(
                  Locale.ROOT,
                  "  request #%d port %d %s priority %d %s (%.2f s elapsed)",
                  r.id,
                  r.hid.getPort(),
                  r.side,
                  r.priority,
                  r.pattern.name(),
                  Clock.seconds() - r.startSeconds));
    }
    return sb.toString();
  }

  /**
   * Forgets every controller, request and cached output.
   *
   * <p>For tests only. The scheduler is a singleton because there is one set of physical
   * controllers, which means one test's requests would otherwise leak into the next.
   */
  public static void resetForTest() {
    s_instance.m_requests.clear();
    s_instance.m_hids.clear();
    s_instance.m_lastWritten.clear();
    s_instance.m_suppressed = false;
    s_instance.m_nextId = 1L;
  }

  // ---------------------------------------------------------------------------------------------
  // Package-private request lifecycle, driven by Rumble.Builder.play()
  // ---------------------------------------------------------------------------------------------

  static long begin(GenericHID hid, RumbleType side, int priority, RumblePattern pattern) {
    register(hid);
    long id = s_instance.m_nextId++;
    s_instance.m_requests.add(new Request(id, hid, side, priority, pattern, Clock.seconds()));
    return id;
  }

  static void end(long id) {
    s_instance.m_requests.removeIf(r -> r.id == id);
    // Write the new winner immediately rather than waiting for the next tick, so a rumble that ends
    // in the last loop before disable does not linger for one extra cycle.
    s_instance.update();
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private void update() {
    double now = Clock.seconds();

    // Retire patterns that have run out even if their command has not ended yet (for example, a
    // command wrapped in something that outlives its timeout).
    m_requests.removeIf(r -> r.pattern.isFinished(now - r.startSeconds));

    for (Map.Entry<Integer, GenericHID> entry : m_hids.entrySet()) {
      int port = entry.getKey();
      GenericHID hid = entry.getValue();

      double leftValue = 0.0;
      double rightValue = 0.0;
      int leftPriority = Integer.MIN_VALUE;
      int rightPriority = Integer.MIN_VALUE;
      double leftStart = Double.NEGATIVE_INFINITY;
      double rightStart = Double.NEGATIVE_INFINITY;

      if (!m_suppressed) {
        for (Request r : m_requests) {
          if (r.hid.getPort() != port) {
            continue;
          }
          double value = r.pattern.intensityAt(now - r.startSeconds);
          boolean affectsLeft = r.side == RumbleType.kLeftRumble || r.side == RumbleType.kBothRumble;
          boolean affectsRight =
              r.side == RumbleType.kRightRumble || r.side == RumbleType.kBothRumble;

          if (affectsLeft && wins(r.priority, r.startSeconds, leftPriority, leftStart)) {
            leftPriority = r.priority;
            leftStart = r.startSeconds;
            leftValue = value;
          }
          if (affectsRight && wins(r.priority, r.startSeconds, rightPriority, rightStart)) {
            rightPriority = r.priority;
            rightStart = r.startSeconds;
            rightValue = value;
          }
        }
      }

      write(hid, port, leftValue, rightValue);
    }
  }

  /** Highest priority wins; a tie goes to the request that started most recently. */
  private static boolean wins(
      int priority, double startSeconds, int bestPriority, double bestStart) {
    return priority > bestPriority || (priority == bestPriority && startSeconds >= bestStart);
  }

  private static void write(GenericHID hid, int port, double left, double right) {
    double[] last =
        s_instance.m_lastWritten.computeIfAbsent(
            port, p -> new double[] {Double.NaN, Double.NaN});
    if (Double.isNaN(last[0]) || Math.abs(last[0] - left) > kWriteEpsilon) {
      hid.setRumble(RumbleType.kLeftRumble, left);
      last[0] = left;
    }
    if (Double.isNaN(last[1]) || Math.abs(last[1] - right) > kWriteEpsilon) {
      hid.setRumble(RumbleType.kRightRumble, right);
      last[1] = right;
    }
  }

  /** One playing pattern on one controller. Mutable-free apart from being removed from the list. */
  private static final class Request {
    private final long id;
    private final GenericHID hid;
    private final RumbleType side;
    private final int priority;
    private final RumblePattern pattern;
    private final double startSeconds;

    private Request(
        long id,
        GenericHID hid,
        RumbleType side,
        int priority,
        RumblePattern pattern,
        double startSeconds) {
      this.id = id;
      this.hid = hid;
      this.side = side;
      this.priority = priority;
      this.pattern = pattern;
      this.startSeconds = startSeconds;
    }
  }
}

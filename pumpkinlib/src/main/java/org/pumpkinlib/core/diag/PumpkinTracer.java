package org.pumpkinlib.core.diag;

import static edu.wpi.first.units.Units.Milliseconds;

import edu.wpi.first.units.measure.Time;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.pumpkinlib.core.compat.Clock;

/**
 * Per-section loop-time accounting with declared budgets — the answer to "is PumpkinLib causing my
 * overruns?" without bisecting the robot.
 *
 * <p>A 20 ms loop is the whole budget the robot has. When it is blown, the WPILib message says
 * <i>"Loop time of 0.02s overrun"</i> and names nothing, so the next four hours go into commenting
 * out subsystems. This class carves the loop into named sections, measures each one every loop, and
 * turns a declared budget into a named breach: <i>"PERF_Vision/Consume: 4.1 ms against a 2.0 ms
 * budget for 5 consecutive loops"</i>. That is the entire value proposition, and it only works if
 * the tracer is cheap enough to leave on in a match.
 *
 * <h2>Cost</h2>
 *
 * <p><b>Zero allocation per sample on the hot path, by construction.</b> {@link #enter(String)} and
 * {@link #exit(String)} are a {@link ConcurrentHashMap#get} (which allocates nothing) plus two
 * {@code long} reads. {@link #section(String)} returns a <i>cached</i> {@link Scope} owned by the
 * section, so try-with-resources allocates nothing either. {@link #reset()} walks a pre-built array
 * with an indexed {@code for}, not an iterator. The only allocation in the whole class happens the
 * first time a section name is seen and inside the reporting methods, which are not hot.
 *
 * <h2>The clock, and why it is the "wrong" one on purpose</h2>
 *
 * <p>The tracer reads {@link Clock#nowMicros()}, which is the <b>un-injected hardware clock</b>
 * ({@code RobotController.getFPGATime()}) on a robot. Every other duration in PumpkinLib comes from
 * the AdvantageKit-injected {@code Timer.getTimestamp()} so that it replays identically — but a
 * profiler that used the injected clock would report the loop times of the <i>recording</i> robot
 * during a 50× replay, which is worse than reporting nothing. This is the single sanctioned
 * exception, its output topics belong in the replay-verify ignore set, and it still goes through
 * {@code Clock} so that ArchUnit rule 3 has nothing to catch and a headless test can install a
 * deterministic source.
 *
 * <h2>Vocabulary</h2>
 *
 * <p>Three spellings for one idea exist across the design documents and all three are kept, because
 * a call site that reads better with one of them is not a call site worth rewriting:
 * {@link #section(String)} and {@link #scope(String)} are the same method,
 * {@link #enter(String)}/{@link #exit(String)} are the explicit form for code that cannot use
 * try-with-resources, and {@link #record(String)} attributes everything since the last mark.
 */
public final class PumpkinTracer {

  private PumpkinTracer() {}

  /** How many consecutive loops a section must exceed its budget before the breach is reported. */
  public static final int kConsecutiveLoopsToBreach = 5;

  /** Samples retained per section for {@link #p95(String)}: 256 loops is about 5 s at 50 Hz. */
  private static final int kSampleWindow = 256;

  private static final Map<String, Section> s_byName = new ConcurrentHashMap<>();

  /**
   * A snapshot array of every registered section, rebuilt on registration only. Iterated with an
   * indexed for-loop every {@link #reset()}; a {@code Map.values()} iterator would allocate 50 times
   * a second forever.
   */
  private static volatile Section[] s_ordered = new Section[0];

  private static volatile long s_loopStartMicros = -1L;

  private static volatile long s_markMicros = -1L;

  private static volatile double s_lastLoopMillis;

  private static volatile double s_worstLoopMillis;

  private static volatile long s_overrunCount;

  private static volatile long s_loopCount;

  private static volatile BreachListener s_breachListener = PumpkinTracer::reportBreachToConsole;

  // -------------------------------------------------------------------------------------------
  // Declaring budgets.
  // -------------------------------------------------------------------------------------------

  /**
   * Declare a per-loop budget for a section.
   *
   * <p>Exceeding it for {@value #kConsecutiveLoopsToBreach} consecutive loops raises one breach —
   * consecutive rather than cumulative, because a single 4 ms spike when a mechanism reconfigures is
   * not a problem and a sustained 4 ms is. The breach re-arms once the section drops back under
   * budget, so a section that oscillates reports each episode rather than once forever or once per
   * loop.
   *
   * <p>Calling this twice for the same section replaces the budget; declaring a budget for a section
   * that has never been measured is legal and normal, since budgets are declared at boot.
   *
   * @param section the section name, e.g. {@code "Vision/Consume"}; must not be null or blank
   * @param perLoop the per-loop ceiling; must be positive
   * @throws IllegalArgumentException if the name is blank or the budget is not positive
   */
  public static void budget(String section, Time perLoop) {
    if (section == null || section.isBlank()) {
      throw new IllegalArgumentException(
          "PumpkinTracer.budget: section name was null or blank. "
              + "Fix: pass a stable name like \"Vision/Consume\" — it becomes a log key and an "
              + "alert message, so it has to mean something to a person.");
    }
    if (perLoop == null || perLoop.in(Milliseconds) <= 0.0) {
      throw new IllegalArgumentException(
          "PumpkinTracer.budget(\""
              + section
              + "\"): budget was "
              + (perLoop == null ? "null" : perLoop.in(Milliseconds) + " ms")
              + ", expected a positive duration. "
              + "Fix: pass e.g. Milliseconds.of(2.0). A zero or negative budget would breach on "
              + "every loop and the alert would be useless.");
    }
    of(section).budgetMillis = perLoop.in(Milliseconds);
  }

  /**
   * The declared budget for a section.
   *
   * @param section the section name
   * @return the budget, or zero milliseconds when none was declared
   */
  public static Time budgetOf(String section) {
    Section s = s_byName.get(section);
    return Milliseconds.of(s == null ? 0.0 : s.budgetMillis);
  }

  // -------------------------------------------------------------------------------------------
  // Measuring.
  // -------------------------------------------------------------------------------------------

  /**
   * Start timing a section, try-with-resources style.
   *
   * <pre>{@code
   * try (var s = PumpkinTracer.section("Vision/Consume")) {
   *   consumeFrames();
   * }
   * }</pre>
   *
   * <p>The returned {@link Scope} is <b>cached per section and is not reentrant</b>: nesting the
   * same section name inside itself attributes the inner span twice and the outer not at all. That
   * is the price of allocating nothing, and it is the right trade for a name that identifies a
   * phase of the loop. Nesting <i>different</i> sections is fine and is the normal case.
   *
   * @param section the section name
   * @return a closeable scope; closing it records the elapsed time
   */
  public static Scope section(String section) {
    Section s = of(section);
    s.startMicros = Clock.nowMicros();
    return s.scope;
  }

  /**
   * Alias for {@link #section(String)}, the spelling used by the vision and telemetry documents.
   *
   * @param epoch the section name
   * @return a closeable scope; closing it records the elapsed time
   */
  public static Scope scope(String epoch) {
    return section(epoch);
  }

  /**
   * Start timing a section explicitly. Pair with {@link #exit(String)}.
   *
   * <p>For call sites where the measured span does not fit a block — a start in one method and a
   * finish in another. Prefer {@link #section(String)} when you can: an unpaired {@code enter} is
   * silently ignored rather than reported, because a tracer that throws during a match is worse than
   * a tracer that under-reports.
   *
   * @param section the section name
   */
  public static void enter(String section) {
    of(section).startMicros = Clock.nowMicros();
  }

  /**
   * Finish timing a section opened by {@link #enter(String)} or {@link #section(String)}.
   *
   * <p>A call with no matching {@code enter} is ignored.
   *
   * @param section the section name
   */
  public static void exit(String section) {
    Section s = s_byName.get(section);
    if (s != null) {
      s.close();
    }
  }

  /**
   * Attribute everything since the last mark to {@code epoch}, and start a new mark.
   *
   * <p>The linear form: {@code reset(); … record("Inputs"); … record("Control"); … record("Log");}
   * carves the loop into consecutive spans with no nesting and no blocks. The first mark is set by
   * {@link #reset()}.
   *
   * <p><b>Do not mix the linear form with {@link #section(String)} in the same loop.</b> The mark
   * does not know about blocks, so a {@code record(...)} following a {@code section(...)} counts the
   * block's time a second time under a different name and both numbers look plausible. Pick one
   * style per loop; picking the block form is usually right.
   *
   * @param epoch the section name for the span that just ended
   */
  public static void record(String epoch) {
    long now = Clock.nowMicros();
    long mark = s_markMicros;
    s_markMicros = now;
    if (mark >= 0) {
      of(epoch).accumulateMicros(now - mark);
    }
  }

  /**
   * Close the previous loop's accounting and open a new one. Call once at the top of the loop.
   *
   * <p>This is where budgets are checked and where the per-loop accumulators roll into the sample
   * window, so a section that is never {@code reset()} never breaches. {@code PumpkinRobot} calls
   * it; a team driving the tracer by hand must.
   */
  public static void reset() {
    long now = Clock.nowMicros();
    long start = s_loopStartMicros;

    if (start >= 0) {
      double loopMillis = (now - start) / 1000.0;
      s_lastLoopMillis = loopMillis;
      if (loopMillis > s_worstLoopMillis) {
        s_worstLoopMillis = loopMillis;
      }
      if (loopMillis > Clock.dt() * 1000.0) {
        s_overrunCount++;
      }
      s_loopCount++;

      Section[] sections = s_ordered;
      for (int i = 0; i < sections.length; i++) {
        sections[i].endLoop();
      }
    }

    s_loopStartMicros = now;
    s_markMicros = now;
  }

  // -------------------------------------------------------------------------------------------
  // Reporting. None of this is on the hot path; allocation here is fine.
  // -------------------------------------------------------------------------------------------

  /**
   * The 95th percentile of a section's per-loop cost over the retained window.
   *
   * <p>p95 rather than a mean, because a mean hides exactly the tail that causes overruns. Nearest
   * rank, matching {@code Rolling}'s convention so two percentiles in the same library mean the same
   * thing.
   *
   * @param section the section name
   * @return the p95 cost, or zero when the section has no samples
   */
  public static Time p95(String section) {
    return Milliseconds.of(p95Millis(section));
  }

  /**
   * The worst per-loop cost ever seen for a section, since boot or since {@link #clear()}.
   *
   * @param section the section name
   * @return the worst cost, or zero when the section has no samples
   */
  public static Time worst(String section) {
    return Milliseconds.of(worstMillis(section));
  }

  /**
   * The p95 cost in milliseconds, without a {@link Time} allocation.
   *
   * @param section the section name
   * @return milliseconds; zero when the section has no samples
   */
  public static double p95Millis(String section) {
    Section s = s_byName.get(section);
    return s == null ? 0.0 : s.p95Millis();
  }

  /**
   * The worst-ever cost in milliseconds, without a {@link Time} allocation.
   *
   * @param section the section name
   * @return milliseconds; zero when the section has no samples
   */
  public static double worstMillis(String section) {
    Section s = s_byName.get(section);
    return s == null ? 0.0 : s.worstMillis;
  }

  /**
   * The most recent completed loop's cost for a section, in milliseconds.
   *
   * <p>This is what a publisher emits as {@code Pumpkin/Perf/<section>Ms} every loop. It reads one
   * {@code double} field and allocates nothing, which is why it exists alongside {@link #p95(String)}.
   *
   * @param section the section name
   * @return milliseconds; zero when the section has no samples
   */
  public static double lastMillis(String section) {
    Section s = s_byName.get(section);
    return s == null ? 0.0 : s.lastMillis;
  }

  /**
   * Every section name that has been declared or measured, in first-seen order.
   *
   * <p>Stable order, because it is what a publisher iterates and a log schema that reorders itself
   * between boots is a log schema that breaks every saved AdvantageScope layout.
   *
   * @return an immutable list of section names
   */
  public static List<String> sections() {
    Section[] sections = s_ordered;
    List<String> names = new ArrayList<>(sections.length);
    for (Section s : sections) {
      names.add(s.name);
    }
    return List.copyOf(names);
  }

  /**
   * The wall time of the most recent completed loop, in milliseconds.
   *
   * @return milliseconds; zero before the second {@link #reset()}
   */
  public static double loopMillis() {
    return s_lastLoopMillis;
  }

  /**
   * The worst loop wall time seen since boot, in milliseconds.
   *
   * @return milliseconds; zero before the second {@link #reset()}
   */
  public static double worstLoopMillis() {
    return s_worstLoopMillis;
  }

  /**
   * How many loops have exceeded the configured loop period.
   *
   * <p>Compared against {@link Clock#dt()} rather than a hard-coded 20 ms, so a 100 Hz robot gets
   * the right answer instead of a counter stuck at zero.
   *
   * @return the cumulative overrun count
   */
  public static long overrunCount() {
    return s_overrunCount;
  }

  /**
   * How many loops the tracer has accounted for.
   *
   * @return the cumulative loop count
   */
  public static long loopCount() {
    return s_loopCount;
  }

  /**
   * A multi-line report of every section against its budget, worst-first.
   *
   * <p>Written for a person reading it in the pit: name, last, p95, worst, budget, and whether it is
   * currently breaching.
   *
   * @return the report; a single line when nothing has been measured
   */
  public static String describe() {
    Section[] sections = s_ordered;
    if (sections.length == 0) {
      return "PumpkinTracer: no sections measured yet.";
    }
    List<Section> sorted = new ArrayList<>(Arrays.asList(sections));
    sorted.sort((a, b) -> Double.compare(b.p95Millis(), a.p95Millis()));
    StringBuilder out = new StringBuilder();
    out.append(
        String.format(
            Locale.ROOT,
            "PumpkinTracer: loop %.2f ms (worst %.2f, %d overruns in %d loops, period %.1f ms)%n",
            s_lastLoopMillis,
            s_worstLoopMillis,
            s_overrunCount,
            s_loopCount,
            Clock.dt() * 1000.0));
    for (Section s : sorted) {
      out.append(
          String.format(
              Locale.ROOT,
              "  %-28s last %6.3f  p95 %6.3f  worst %6.3f  budget %s%s%n",
              s.name,
              s.lastMillis,
              s.p95Millis(),
              s.worstMillis,
              s.budgetMillis > 0 ? String.format(Locale.ROOT, "%6.3f", s.budgetMillis) : "  none",
              s.breaching ? "  <-- BREACHING" : ""));
    }
    return out.toString();
  }

  // -------------------------------------------------------------------------------------------
  // Breach reporting.
  // -------------------------------------------------------------------------------------------

  /**
   * Install the handler that turns a sustained budget overrun into an alert.
   *
   * <p>Push registration: {@code core.diag} must not import the alert domain, so the alert domain
   * installs itself here at boot and raises a {@code WARNING} / {@code PIT_ONLY} alert. Until it
   * does, the default handler prints one line to standard error per breach edge, which the driver
   * station captures — silence would defeat the entire purpose of the class.
   *
   * @param listener the handler; null restores the default console handler
   */
  public static void setBreachListener(BreachListener listener) {
    s_breachListener = listener == null ? PumpkinTracer::reportBreachToConsole : listener;
  }

  /** Receives a sustained budget overrun. Implemented by the alert domain. */
  @FunctionalInterface
  public interface BreachListener {
    /**
     * Called once on the loop a section's overrun becomes sustained, and again only after the
     * section has recovered and breached anew.
     *
     * @param breach what breached, by how much, and for how long
     */
    void onBreach(Breach breach);
  }

  /**
   * One sustained budget overrun.
   *
   * @param section the section name
   * @param observedMillis the most recent per-loop cost that breached
   * @param budgetMillis the declared per-loop budget
   * @param consecutiveLoops how many consecutive loops were over budget when this fired
   */
  public record Breach(
      String section, double observedMillis, double budgetMillis, int consecutiveLoops) {

    /**
     * The alert text, naming the thing, the value, the expected range and the fix.
     *
     * @return a message a student can act on at 11pm
     */
    public String message() {
      return String.format(
          Locale.ROOT,
          "PERF_%s: %.2f ms against a %.2f ms budget for %d consecutive loops. "
              + "Fix: profile that section, or raise its budget with PumpkinTracer.budget(\"%s\", "
              + "Milliseconds.of(...)) if the new cost is intended.",
          section,
          observedMillis,
          budgetMillis,
          consecutiveLoops,
          section);
    }
  }

  private static void reportBreachToConsole(Breach breach) {
    // Default handler. Deliberately not silent: an unwired tracer that says nothing is a tracer
    // whose budgets are decoration. The alert domain replaces this via setBreachListener.
    System.err.println("[PumpkinTracer] " + breach.message());
  }

  /**
   * Forgets every sample, budget and section. For tests, and for a pit workflow that wants a clean
   * "worst since I pressed the button".
   */
  public static void clear() {
    s_byName.clear();
    s_ordered = new Section[0];
    s_loopStartMicros = -1L;
    s_markMicros = -1L;
    s_lastLoopMillis = 0.0;
    s_worstLoopMillis = 0.0;
    s_overrunCount = 0L;
    s_loopCount = 0L;
  }

  /** {@link #clear()} plus restoring the default breach handler. For tests only. */
  public static void resetForTest() {
    clear();
    s_breachListener = PumpkinTracer::reportBreachToConsole;
  }

  // -------------------------------------------------------------------------------------------
  // Internals.
  // -------------------------------------------------------------------------------------------

  private static Section of(String name) {
    Section existing = s_byName.get(name);
    if (existing != null) {
      return existing;
    }
    return registerSection(name);
  }

  private static synchronized Section registerSection(String name) {
    Section existing = s_byName.get(name);
    if (existing != null) {
      return existing;
    }
    Section created = new Section(name);
    s_byName.put(name, created);
    Section[] previous = s_ordered;
    Section[] next = Arrays.copyOf(previous, previous.length + 1);
    next[previous.length] = created;
    s_ordered = next;
    return created;
  }

  /**
   * A named span of the loop. One instance per section name, allocated once, mutated in place — that
   * is the whole reason the hot path allocates nothing.
   */
  private static final class Section {
    private final String name;
    private final Scope scope;
    private final double[] samples = new double[kSampleWindow];

    private int sampleCount;
    private int sampleIndex;

    private long startMicros = -1L;
    private double accumulatedMicrosThisLoop;
    private double lastMillis;
    private double worstMillis;
    private double budgetMillis;
    private int consecutiveOverruns;
    private boolean breaching;

    Section(String name) {
      this.name = name;
      this.scope = new Scope(this);
    }

    void accumulateMicros(long micros) {
      if (micros > 0) {
        accumulatedMicrosThisLoop += micros;
      }
    }

    void close() {
      long start = startMicros;
      if (start < 0) {
        return;
      }
      startMicros = -1L;
      accumulateMicros(Clock.nowMicros() - start);
    }

    void endLoop() {
      double millis = accumulatedMicrosThisLoop / 1000.0;
      accumulatedMicrosThisLoop = 0.0;
      startMicros = -1L;
      lastMillis = millis;
      if (millis > worstMillis) {
        worstMillis = millis;
      }
      samples[sampleIndex] = millis;
      sampleIndex = (sampleIndex + 1) % kSampleWindow;
      if (sampleCount < kSampleWindow) {
        sampleCount++;
      }

      if (budgetMillis <= 0.0) {
        return;
      }
      if (millis > budgetMillis) {
        consecutiveOverruns++;
        if (consecutiveOverruns >= kConsecutiveLoopsToBreach && !breaching) {
          breaching = true;
          s_breachListener.onBreach(new Breach(name, millis, budgetMillis, consecutiveOverruns));
        }
      } else {
        consecutiveOverruns = 0;
        breaching = false;
      }
    }

    double p95Millis() {
      int n = sampleCount;
      if (n == 0) {
        return 0.0;
      }
      double[] copy = Arrays.copyOf(samples, n);
      Arrays.sort(copy);
      // Nearest rank, matching Rolling's convention.
      int rank = (int) Math.ceil(0.95 * n);
      return copy[Math.min(n - 1, Math.max(0, rank - 1))];
    }
  }

  /**
   * The try-with-resources handle returned by {@link PumpkinTracer#section(String)}.
   *
   * <p>Declared as a concrete type rather than bare {@link AutoCloseable} on purpose: an
   * {@code AutoCloseable} return type forces every {@code try (var s = ...)} call site to catch a
   * checked {@code Exception} that can never be thrown. {@link #close()} here declares nothing, so
   * the call sites in the design documents compile as written.
   */
  public static final class Scope implements AutoCloseable {
    private final Section m_section;

    private Scope(Section section) {
      m_section = section;
    }

    /** Records the elapsed time since the matching {@code section(...)} call. */
    @Override
    public void close() {
      m_section.close();
    }
  }
}

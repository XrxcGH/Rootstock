package org.rootstock.telemetry.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.rootstock.core.spi.Tier;
import org.rootstock.telemetry.RootstockLog;

/**
 * The section 3.5 key block — {@code Rootstock/Health/} — the log-side transport for the alert
 * registry.
 *
 * <p><b>This domain does not own alerts.</b> {@code org.rootstock.core.alert} does: every alert in
 * the library is raised through {@code Alerts.error/warning/info} and a sticky fault is
 * {@code RootstockAlert.sticky(true)}. What lives here is the <i>transport and schema</i> — the four
 * keys the registry's contents are published under, so that "what was wrong with the robot at
 * t=42.3" is answerable from a log file and not only from a dashboard nobody screenshotted.
 *
 * <p>{@code Active} and {@code Seen} answer two different questions and both are needed.
 * {@code Active} is what is wrong right now; {@code Seen} is everything that has been wrong this
 * session, which is how an intermittent fault that cleared before anyone looked still leaves a
 * trace. {@code Counts/&lt;name&gt;} is the rising-edge count, which is how "it happened once" is
 * told apart from "it happened four hundred times".
 */
public final class HealthSchema {

  /** Everything in this block hangs off this prefix. */
  public static final String kPrefix = RootstockLog.kRoot + "/Health";

  /** Active alert texts, worst first. */
  public static final String kActive = kPrefix + "/Active";

  /** Every alert raised this session. */
  public static final String kSeen = kPrefix + "/Seen";

  /** Prefix of the per-alert rising-edge counters. */
  public static final String kCountsPrefix = kPrefix + "/Counts/";

  /** One of {@code "ERROR"}, {@code "WARNING"}, {@code "INFO"} or {@code "NONE"}. */
  public static final String kWorst = kPrefix + "/Worst";

  /** The {@link #kWorst} value when nothing is active. */
  public static final String kWorstNone = "NONE";

  /** The placeholder the static table uses where a real key carries an alert name. */
  public static final String kNamePlaceholder = "<name>";

  /**
   * Absolute keys for each counted alert name, so the per-cycle path never concatenates.
   * Insertion-ordered per guarantee G7.
   */
  private static final Map<String, String> m_countKeys = new LinkedHashMap<>();

  private HealthSchema() {}

  /**
   * Publishes the three summary keys.
   *
   * <p>One call because they are one snapshot: an {@code Active} list that disagrees with
   * {@code Worst} is a log that argues with itself, and the two can only disagree if they were
   * written from two different reads of the registry.
   *
   * @param active active alert texts, worst first
   * @param seen every alert raised this session
   * @param worst {@code "ERROR"}, {@code "WARNING"}, {@code "INFO"} or {@link #kWorstNone}
   */
  public static void summary(String[] active, String[] seen, String worst) {
    Objects.requireNonNull(active, "HealthSchema.summary: active must not be null; pass an empty array.");
    Objects.requireNonNull(seen, "HealthSchema.summary: seen must not be null; pass an empty array.");
    RootstockLog.critical(kActive, active);
    RootstockLog.critical(kSeen, seen);
    RootstockLog.critical(kWorst, worst == null ? kWorstNone : worst);
  }

  /**
   * Publishes one alert's rising-edge count.
   *
   * @param name the alert name; becomes the final path segment
   * @param count how many times it has gone from clear to set this session
   */
  public static void count(String name, long count) {
    Objects.requireNonNull(name, "HealthSchema.count: name must not be null.");
    String key = m_countKeys.computeIfAbsent(name, n -> kCountsPrefix + n);
    RootstockLog.log(key, count);
  }

  /** Forgets the per-name key cache, so one test cannot see another test's alert names. */
  public static void resetForTest() {
    m_countKeys.clear();
  }

  /**
   * Section 3.5's table, in the order it lists the keys.
   *
   * @return the rows
   */
  public static List<SchemaEntry> schema() {
    return List.of(
        SchemaEntry.of(kActive, "String[]", SchemaEntry.kNoUnit, Tier.CRITICAL, "active alert texts, worst-first"),
        SchemaEntry.of(kSeen, "String[]", SchemaEntry.kNoUnit, Tier.CRITICAL, "every alert raised this session"),
        SchemaEntry.of(kCountsPrefix + kNamePlaceholder, "long", SchemaEntry.kNoUnit, Tier.STANDARD, "rising-edge count per alert"),
        SchemaEntry.of(kWorst, "String", SchemaEntry.kNoUnit, Tier.CRITICAL, "\"ERROR\" | \"WARNING\" | \"INFO\" | \"NONE\""));
  }
}

package org.rootstock.telemetry.schema;

import java.util.Objects;
import org.rootstock.core.spi.Tier;
import org.rootstock.telemetry.Demotable;

/**
 * One row of the published telemetry schema: a key, its type, its unit, its tier and whether the
 * byte-budget governor may slow it down.
 *
 * <p><b>Why this exists as a value type rather than as prose.</b> The key names in {@code design/04}
 * section 3 are a <i>wire contract</i>. AdvantageScope layouts bind to them by literal string, the
 * replay-diff tool partitions on them, and the five-minute "why did auto fail in match 42" triage
 * reads four of them frame by frame. A key that changes spelling between two releases is a silently
 * broken dashboard for every team that upgraded — and the failure is silent because an absent key
 * renders as an empty graph, not as an error. Making every row a value that {@link SchemaGeneration}
 * can emit turns that into a one-line diff.
 *
 * <p>{@link #type()} is a {@code String} and not an enum on purpose. The design's tables spell types
 * the way a reader needs to see them — {@code "Pose2d[] (length 0 or 1)"}, {@code "double[3]"},
 * {@code "boolean[] (per motor)"} — and those qualifications carry real contract information that an
 * enum constant would erase. Nothing in the library dispatches on this field; it exists to be read
 * and diffed.
 *
 * @param key the full log key, with {@code <Name>} / {@code <Camera>} / {@code <Type>} left as
 *     literal placeholders in the static block tables
 * @param type the value type, spelled as the design table spells it
 * @param unit the unit metadata attached to the entry, or {@link #kNoUnit} when the value has no
 *     unit at all — a {@code String} key is not dimensionless, it is unit-less, and the schema says
 *     so rather than writing {@code Value}
 * @param tier which tiers of the FMS gate this key survives
 * @param demotable whether the governor may publish this key at one cycle in
 *     {@code RootstockBudget.kDemotedPublishEveryN} under sustained overload
 * @param meaning a short human sentence, or the empty string; emitted as a trailing comment so a
 *     diff of the machine columns is not swamped by prose
 */
public record SchemaEntry(
    String key, String type, String unit, Tier tier, Demotable demotable, String meaning) {

  /** The {@link #unit()} value for a key that has no unit at all. */
  public static final String kNoUnit = "-";

  /** Column separator in {@link #toLine()}. A tab so the emitted document is diff- and cut-friendly. */
  public static final char kSeparator = '\t';

  /**
   * Validates every component. A null anywhere in a schema row would emit as the four characters
   * {@code null} and diff clean against a later release that fixed it.
   */
  public SchemaEntry {
    Objects.requireNonNull(key, "SchemaEntry.key must not be null.");
    Objects.requireNonNull(type, "SchemaEntry.type must not be null.");
    Objects.requireNonNull(unit, "SchemaEntry.unit must not be null; use SchemaEntry.kNoUnit.");
    Objects.requireNonNull(tier, "SchemaEntry.tier must not be null.");
    Objects.requireNonNull(demotable, "SchemaEntry.demotable must not be null.");
    Objects.requireNonNull(meaning, "SchemaEntry.meaning must not be null; use \"\".");
    if (key.isEmpty()) {
      throw new IllegalArgumentException("SchemaEntry.key must not be empty.");
    }
  }

  /**
   * A non-demotable row with no explanatory sentence — the common case.
   *
   * @param key the full log key
   * @param type the value type
   * @param unit the unit, or {@link #kNoUnit}
   * @param tier the tier
   * @return the row
   */
  public static SchemaEntry of(String key, String type, String unit, Tier tier) {
    return new SchemaEntry(key, type, unit, tier, Demotable.NO, "");
  }

  /**
   * A non-demotable row that carries a sentence explaining why the key exists.
   *
   * @param key the full log key
   * @param type the value type
   * @param unit the unit, or {@link #kNoUnit}
   * @param tier the tier
   * @param meaning the sentence
   * @return the row
   */
  public static SchemaEntry of(String key, String type, String unit, Tier tier, String meaning) {
    return new SchemaEntry(key, type, unit, tier, Demotable.NO, meaning);
  }

  /**
   * A row the governor is permitted to slow down.
   *
   * <p>Legal on outputs only. A key containing {@code "/Inputs/"} is refused at runtime by
   * {@code RootstockBudget.isDemotionEligible}, so a schema row that claims otherwise is a
   * documentation bug this factory cannot catch — but {@link SchemaGeneration#emit()} makes it
   * visible, and the section 3.1 inputs table is blank in this column on purpose.
   *
   * @param key the full log key
   * @param type the value type
   * @param unit the unit, or {@link #kNoUnit}
   * @param tier the tier
   * @return the row
   */
  public static SchemaEntry demotable(String key, String type, String unit, Tier tier) {
    return new SchemaEntry(key, type, unit, tier, Demotable.YES, "");
  }

  /**
   * The row as one tab-separated line, without a trailing newline.
   *
   * <p>Stable by construction: five machine columns in a fixed order, then the sentence behind a
   * {@code #} so a reader can strip it with {@code cut -f1-5} and diff only the contract.
   *
   * @return the line
   */
  public String toLine() {
    StringBuilder out = new StringBuilder(96);
    out.append(key)
        .append(kSeparator)
        .append(type)
        .append(kSeparator)
        .append(unit)
        .append(kSeparator)
        .append(tier)
        .append(kSeparator)
        .append(demotable);
    if (!meaning.isEmpty()) {
      out.append(kSeparator).append('#').append(' ').append(meaning);
    }
    return out.toString();
  }
}

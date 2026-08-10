package org.pumpkinlib.telemetry.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits PumpkinLib's automatic telemetry schema as one deterministic, diffable document.
 *
 * <h2>What this is for</h2>
 *
 * <p>The key names in section 3 are a <b>wire contract</b>. Shipped AdvantageScope layouts bind to
 * them by literal string, the replay-diff tool partitions on them, and the five-minute "why did auto
 * fail in match 42" triage reads four of them frame by frame. A key that changes spelling between
 * two releases is a broken dashboard for every team that upgraded — and it breaks <i>silently</i>,
 * because an absent key renders as an empty graph and not as an error. Nobody notices until a
 * student is standing in the pit staring at a blank plot.
 *
 * <p>So the schema is emitted rather than merely documented. {@code ./gradlew run --args=schema} —
 * or a test that pins {@link #fingerprint()} — turns "somebody renamed a key" from a support ticket
 * in March into a failing build in November.
 *
 * <h2>Not to be confused with {@code Pumpkin/Log/SchemaGeneration}</h2>
 *
 * <p>That is a {@code long} counter published every cycle: it increments when the byte-budget
 * governor demotes or restores a key, so replay and the triage tooling can see the boundary at which
 * a trace's sample rate changed. <b>This class is unrelated to it.</b> It is the <i>generation</i>
 * of the schema document, in the sense of "generating a file", and it is a build-time and test-time
 * artifact that never runs on a robot. The name collision is unfortunate and is called out here
 * rather than left for a reader to trip over.
 *
 * <h2>Format</h2>
 *
 * <p>Tab-separated, five machine columns, one section heading per section-3 table:
 *
 * <pre>{@code
 * # PumpkinLib telemetry schema, format 1
 * # key	type	unit	tier	demotable	[# meaning]
 * ## 3.1 Every mechanism - Pumpkin/<Name>/ (inputs)
 * Pumpkin/<Name>/Inputs/Connected	boolean[] (per motor)	-	CRITICAL	NO
 * }</pre>
 *
 * <p>{@code cut -f1-5} strips the prose so a diff reads only the contract. Placeholders are left
 * literal — {@code <Name>}, {@code <Camera>}, {@code <Type>}, {@code <i>}, {@code <name>} — because
 * the contract is the <i>shape</i> of the key, and a document listing one team's actual mechanism
 * names would diff dirty on every robot.
 */
public final class SchemaGeneration {

  /**
   * The document format version.
   *
   * <p>Bumped when the <i>columns</i> change, never when a key changes — a diff consumer needs to
   * tell "the schema moved" from "the file's shape moved", and those two want opposite reactions.
   */
  public static final String kFormatVersion = "1";

  /** First line of {@link #emit()}. */
  public static final String kHeader = "# PumpkinLib telemetry schema, format " + kFormatVersion;

  /** Second line of {@link #emit()}: the column legend. */
  public static final String kColumns = "# key\ttype\tunit\ttier\tdemotable\t[# meaning]";

  /** Prefix marking a section heading line. */
  public static final String kSectionMarker = "## ";

  /** FNV-1a 64-bit offset basis. */
  private static final long kFnvOffsetBasis = 0xcbf29ce484222325L;

  /** FNV-1a 64-bit prime. */
  private static final long kFnvPrime = 0x100000001b3L;

  private SchemaGeneration() {}

  /**
   * Every section of the schema, in document order.
   *
   * @return an immutable list of immutable sections
   */
  public static List<SchemaSection> sections() {
    return List.of(
        new SchemaSection(
            "3.1 Every mechanism - Pumpkin/<Name>/ (replayable inputs)", MechanismSchema.inputSchema()),
        new SchemaSection("3.1 Every mechanism - Pumpkin/<Name>/ (outputs)", MechanismSchema.outputSchema()),
        new SchemaSection("3.1 Every mechanism - declared extras", MechanismSchema.extraSchema()),
        new SchemaSection("3.2 Drivetrain - Pumpkin/Drive/", DriveSchema.schema()),
        new SchemaSection("3.3 Vision - Pumpkin/Vision/<Camera>/", VisionSchema.schema()),
        new SchemaSection("3.3 Vision - roll-ups at Pumpkin/Vision/", VisionSchema.rollupSchema()),
        new SchemaSection("3.4 Field / ghost contract - Pumpkin/Field/", FieldSchema.schema()),
        new SchemaSection("3.5 Alerts and health - Pumpkin/Health/", HealthSchema.schema()),
        new SchemaSection("3.6 Provenance metadata (write-once, before start())", ProvenanceSchema.schema()));
  }

  /**
   * Every row of every section, flattened, in document order.
   *
   * @return an immutable list
   */
  public static List<SchemaEntry> entries() {
    List<SchemaEntry> all = new ArrayList<>();
    for (SchemaSection section : sections()) {
      all.addAll(section.entries());
    }
    return List.copyOf(all);
  }

  /**
   * Every key in the schema, in document order.
   *
   * <p>The list a test asserts against when it wants to say "these keys exist and no others".
   *
   * @return an immutable list
   */
  public static List<String> keys() {
    return entries().stream().map(SchemaEntry::key).toList();
  }

  /**
   * The whole schema as one document, newline-terminated per line.
   *
   * <p>Deterministic: the section order, the row order within a section and the column order are all
   * fixed, so two runs on two machines produce byte-identical output and a diff shows only real
   * changes.
   *
   * @return the document
   */
  public static String emit() {
    StringBuilder out = new StringBuilder(8192);
    out.append(kHeader).append('\n');
    out.append(kColumns).append('\n');
    for (SchemaSection section : sections()) {
      out.append('\n').append(kSectionMarker).append(section.title()).append('\n');
      for (SchemaEntry entry : section.entries()) {
        out.append(entry.toLine()).append('\n');
      }
    }
    return out.toString();
  }

  /**
   * A stable 16-character hex digest of {@link #emit()}.
   *
   * <p>Exists so a regression test is one string comparison instead of a golden file: pin this, and
   * any change to any key, type, unit, tier or demotability fails the build with the old and new
   * digests side by side. The reviewer then reads {@code emit()} to see <i>what</i> moved.
   *
   * <p>FNV-1a rather than {@code String.hashCode} or {@code MessageDigest}. {@code hashCode} is a
   * 32-bit value with documented collisions and no cross-version guarantee stronger than the spec;
   * {@code MessageDigest} needs an algorithm lookup that can throw. FNV-1a over UTF-16 code units is
   * ten lines, allocation-free, and identical on every JVM forever, which is the only property a
   * schema fingerprint needs.
   *
   * @return the digest, lower-case hex, zero-padded to 16 characters
   */
  public static String fingerprint() {
    String document = emit();
    long hash = kFnvOffsetBasis;
    for (int i = 0; i < document.length(); i++) {
      char c = document.charAt(i);
      hash ^= (c & 0xff);
      hash *= kFnvPrime;
      hash ^= ((c >> 8) & 0xff);
      hash *= kFnvPrime;
    }
    return String.format("%016x", hash);
  }
}

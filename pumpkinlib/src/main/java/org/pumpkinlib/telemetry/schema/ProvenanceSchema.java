package org.pumpkinlib.telemetry.schema;

import java.util.List;
import java.util.Objects;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.PumpkinLog;

/**
 * The section 3.6 provenance metadata — the twelve write-once fields that make any log traceable to
 * an exact commit on an exact robot.
 *
 * <p>Straight from 6328's practice, and the reason is the sentence a team says at 11 pm at an event:
 * <i>"this log is from before we changed the gains, isn't it?"</i> Without {@code GitSHA} and
 * {@code GitDirty} that question has no answer, and a log you cannot place in time is a log you
 * cannot reason from.
 *
 * <p>Written <b>before</b> {@code PumpkinLog.start()}; the facade closes metadata after that, so a
 * late write is a named warning rather than a silently missing field.
 *
 * <p><b>{@code Backend} and {@code ReplayCapable} are deliberately absent.</b> {@code Backend} had
 * one possible value once AdvantageKit became a hard dependency, and {@code ReplayCapable} was
 * structurally true. {@code AdvantageKitVersion} replaces them and is the more useful field anyway:
 * when a replay misbehaves after an upgrade, the first question is which AdvantageKit wrote the log.
 */
public final class ProvenanceSchema {

  /** The Gradle project name. */
  public static final String kProjectName = "ProjectName";

  /** When the jar was built. */
  public static final String kBuildDate = "BuildDate";

  /** The commit the code was built from. */
  public static final String kGitSha = "GitSHA";

  /** The branch it was built from. */
  public static final String kGitBranch = "GitBranch";

  /** {@code "All changes committed"} or {@code "Uncommitted changes"}. */
  public static final String kGitDirty = "GitDirty";

  /** The machine that built it. */
  public static final String kHostname = "Hostname";

  /** The platform the code is running on. */
  public static final String kPlatform = "Platform";

  /** Which robot this is — comp bot, practice bot, sim. */
  public static final String kRobotIdentity = "RobotIdentity";

  /** The PumpkinLib version. */
  public static final String kPumpkinLibVersion = "PumpkinLibVersion";

  /** The WPILib version. */
  public static final String kWpilibVersion = "WpilibVersion";

  /** The AdvantageKit version, which is the first thing to check when a replay misbehaves. */
  public static final String kAdvantageKitVersion = "AdvantageKitVersion";

  /** {@code REAL}, {@code SIM} or {@code REPLAY}. */
  public static final String kMode = "Mode";

  /** The value {@link #kGitDirty} takes on a clean tree. */
  public static final String kGitClean = "All changes committed";

  /** The value {@link #kGitDirty} takes on a dirty tree. */
  public static final String kGitDirtyValue = "Uncommitted changes";

  private static final List<String> kKeys =
      List.of(
          kProjectName,
          kBuildDate,
          kGitSha,
          kGitBranch,
          kGitDirty,
          kHostname,
          kPlatform,
          kRobotIdentity,
          kPumpkinLibVersion,
          kWpilibVersion,
          kAdvantageKitVersion,
          kMode);

  private ProvenanceSchema() {}

  /**
   * The twelve field names, in the order section 3.6 lists them.
   *
   * @return an immutable list
   */
  public static List<String> keys() {
    return kKeys;
  }

  /**
   * Whether a name is one of the twelve.
   *
   * @param key the name
   * @return true if it is part of the provenance contract
   */
  public static boolean isProvenanceKey(String key) {
    return kKeys.contains(key);
  }

  /**
   * Writes one provenance field.
   *
   * <p>Refuses a name that is not one of the twelve, because provenance is a <i>fixed</i> set: the
   * triage manifest and the log-naming scheme read these by name, and a thirteenth field that only
   * one team writes is a field no tool will ever look at. Team-specific metadata goes through
   * {@code PumpkinLog.metadata} directly.
   *
   * @param key one of the twelve names
   * @param value the value; null is written as the empty string so the field exists and is visibly
   *     blank rather than absent, which reads on a graph as "this build had no git SHA"
   */
  public static void write(String key, String value) {
    Objects.requireNonNull(key, "ProvenanceSchema.write: key must not be null.");
    if (!isProvenanceKey(key)) {
      throw new IllegalArgumentException(
          "ProvenanceSchema.write was given \""
              + key
              + "\", which is not one of the twelve section 3.6 provenance fields "
              + kKeys
              + ". Provenance is a fixed set that the triage manifest and the log-naming scheme read"
              + " by name; for anything else call PumpkinLog.metadata(key, value) directly.");
    }
    PumpkinLog.metadata(key, value == null ? "" : value);
  }

  /**
   * The section 3.6 field list, rendered as schema rows so it appears in the emitted document
   * alongside the key blocks.
   *
   * <p>Metadata is not tiered — it is written once, before {@code start()}, and is present in every
   * log including an FMS one — so these rows are emitted with a dedicated {@code "metadata"} type
   * and are not gated. They are here because a release that drops {@code GitSHA} breaks the same
   * class of consumer that a renamed key breaks, and a diff should say so.
   *
   * @return the rows
   */
  public static List<SchemaEntry> schema() {
    return kKeys.stream()
        .map(
            k ->
                SchemaEntry.of(
                    k,
                    "metadata (String)",
                    SchemaEntry.kNoUnit,
                    Tier.CRITICAL,
                    "write-once, before start()"))
        .toList();
  }
}

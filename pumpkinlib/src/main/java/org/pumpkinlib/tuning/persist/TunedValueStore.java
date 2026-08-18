package org.pumpkinlib.tuning.persist;

import edu.wpi.first.wpilibj.Preferences;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.config.PersistentStore;
import org.pumpkinlib.core.identity.RobotId;
import org.pumpkinlib.core.identity.RobotIdentity;

/**
 * The persistence layer for tuned values: four tiers, per-value merge, atomic write, setpoints keyed
 * by {@code RobotId}.
 *
 * <p><b>The problem this solves.</b> Nothing in the FRC ecosystem closes this loop. The common
 * tunable-number classes read the dashboard and fall back to a compile-time default; nothing writes
 * back. The result is the failure every team knows: <i>we tuned it, then power-cycled, and lost
 * everything</i> — or the slightly worse version, <i>we tuned it, it worked all day, and then
 * somebody redeployed</i>.
 *
 * <p><b>Four sources, lowest to highest priority.</b> Every value records which one it came from, and
 * that source string is what appears in the NT metadata and in the UI.
 *
 * <ol>
 *   <li>{@link ValueSource#CODE_DEFAULT} — the {@code Gains} in the team's own config class. Always
 *       present, always the fallback. {@code Gains.UNTUNED} is a legal code default.
 *   <li>{@link ValueSource#DEPLOY_FILE} — {@code src/main/deploy/pumpkin/gains.json}, checked into
 *       git. This is the team's committed, reviewed answer.
 *   <li>{@link ValueSource#ROBOT_FILE} — {@code Platform.persistentDir()/pumpkin/gains.json}, written
 *       by the Save button. Survives deploy and reboot. The "we tuned it at the field on Saturday"
 *       file.
 *   <li>{@link ValueSource#DASHBOARD} — the live NT value, highest priority while tuning is enabled
 *       and ignored entirely under FMS. Applied by {@code TunableDouble}, not by this class, which is
 *       why {@link #resolve} stops at tier 3.
 * </ol>
 *
 * <p><b>Merging is per value, not per mechanism.</b> If the robot file contains only {@code kP},
 * every other gain still comes from the deploy file or the code default. A student who bisected only
 * kG must not accidentally revert kV.
 *
 * <p><b>A mismatched {@code configHash} makes a file inert.</b> A mechanism whose hash differs from
 * the one recorded in a file ignores that file's values and raises a {@code BLOCKS_MATCH} error —
 * because the code default it falls back to may be {@code Gains.UNTUNED}, and a mechanism holding
 * {@code UNTUNED} refuses closed-loop control on hardware, so the robot will not move that mechanism
 * at all. This is the single most valuable line in the whole persistence layer: gains silently
 * surviving a mechanical change is how a robot gets destroyed after a rebuild.
 *
 * <p><b>Nothing here throws.</b> A malformed or unreadable file raises a {@code PIT_ONLY} warning and
 * is skipped. A robot that will not boot because a JSON file has a stray comma is unacceptable.
 */
public final class TunedValueStore {

  /** The schema tag written into every file, so a future format change is detectable. */
  public static final String kSchema = "pumpkinlib.gains/1";

  /** The file name, identical under both roots so one schema serves both. */
  public static final String kFileName = "gains.json";

  /** The file {@code ValueExporter} writes on a robot for a human to copy into git. */
  public static final String kCommitFileName = "gains-for-commit.json";

  /** The alert group every message from the persistence layer is filed under. */
  public static final String kAlertGroup = "Tuning";

  private static boolean s_mirrorToPreferences;

  private TunedValueStore() {}

  /**
   * The runtime file: {@code Platform.persistentDir()/pumpkin/gains.json} on the roboRIO, and
   * {@code ./pumpkin/gains.json} in desktop simulation.
   *
   * <p>Written at runtime, survives a redeploy, cleared only by re-imaging. Runtime state never goes
   * in the deploy directory: that is the deploy task's territory, and writing there invites a silent
   * revert on the next deploy.
   *
   * @return the absolute path; the file may not exist
   */
  public static Path robotFile() {
    return PersistentStore.persistent().resolve(kFileName);
  }

  /**
   * The committed file: {@code Platform.deployDir()/pumpkin/gains.json}, which is
   * {@code src/main/deploy/pumpkin/gains.json} in desktop simulation.
   *
   * <p>Shipped with the code and therefore in git. This is the artifact that survives a student
   * graduating, which the small-team research names as the single biggest institutional risk.
   *
   * @return the absolute path; the file may not exist
   */
  public static Path deployFile() {
    return PersistentStore.deploy().resolve(kFileName);
  }

  /**
   * Resolve the effective values for a mechanism using the four-tier precedence.
   *
   * <p>Never throws: an unreadable or malformed file raises a {@code PIT_ONLY} warning, contributes a
   * line to {@link Resolved#warnings()}, and is skipped.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @param configHash the hash of the physical configuration these gains must match
   * @param codeDefault the gains compiled into the jar; never null, and legally {@code Gains.UNTUNED}
   * @return the merged result, with one {@link ValueSource} recorded per gain
   */
  public static Resolved resolve(String mechanism, String configHash, Gains codeDefault) {
    Gains gains = codeDefault == null ? Gains.UNTUNED : codeDefault;
    Map<GainId, String> provenance = new EnumMap<>(GainId.class);
    for (GainId id : GainId.values()) {
      provenance.put(id, ValueSource.CODE_DEFAULT.name());
    }
    Map<String, Double> controlValues = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();

    // Tier 2, then tier 3: each overwrites, per value, only the keys it actually contains.
    Merge deployMerge =
        mergeFrom(PersistentStore.deploy(), mechanism, configHash, ValueSource.DEPLOY_FILE, warnings);
    Merge robotMerge =
        mergeFrom(PersistentStore.persistent(), mechanism, configHash, ValueSource.ROBOT_FILE, warnings);

    for (Merge merge : List.of(deployMerge, robotMerge)) {
      for (Map.Entry<GainId, Double> entry : merge.gains().entrySet()) {
        gains = gains.with(entry.getKey(), entry.getValue());
        provenance.put(entry.getKey(), merge.source().name());
      }
      controlValues.putAll(merge.control());
    }

    for (String warning : warnings) {
      Alerts.warning(kAlertGroup, warning, MatchImpact.PIT_ONLY).set(true);
    }
    return new Resolved(gains, Map.copyOf(controlValues), Map.copyOf(provenance), List.copyOf(warnings));
  }

  /**
   * The effective values for one mechanism, and where each of them came from.
   *
   * @param gains the merged seven gains, in volts per SI unit
   * @param controlValues the merged {@code ControlConfig} values, in user units, keyed by topic name
   * @param provenance which {@link ValueSource} won, per gain — the answer to "why is my gain not
   *     what I typed"
   * @param warnings every file problem encountered, already raised as {@code PIT_ONLY} alerts
   */
  public record Resolved(
      Gains gains,
      Map<String, Double> controlValues,
      Map<GainId, String> provenance,
      List<String> warnings) {

    /**
     * The tier that won for one gain.
     *
     * @param id which gain
     * @return the source, or {@link ValueSource#CODE_DEFAULT} when nothing overrode it
     */
    public ValueSource sourceOf(GainId id) {
      String name = provenance.get(id);
      if (name == null) {
        return ValueSource.CODE_DEFAULT;
      }
      try {
        return ValueSource.valueOf(name);
      } catch (IllegalArgumentException e) {
        // A hand-edited file may carry a source string we do not know. That is not a reason to lose
        // the value; it is a reason to say we do not recognise where it came from.
        return ValueSource.CODE_DEFAULT;
      }
    }

    /**
     * One line per gain saying what it is and where it came from.
     *
     * @return a multi-line report for the pit display
     */
    public String describe() {
      StringBuilder sb = new StringBuilder(240);
      for (GainId id : GainId.values()) {
        sb.append(String.format(Locale.ROOT, "  %-3s %12.5f   from %s", id.key(), gains.get(id), sourceOf(id).explain()))
            .append(System.lineSeparator());
      }
      return sb.toString();
    }
  }

  /**
   * How well a fit went, persisted beside the numbers it produced.
   *
   * <p>Persisted because a gain without its fit quality is a number nobody can argue with six weeks
   * later. An R² of 0.62 and an R² of 0.98 produce equally confident-looking kV values.
   *
   * @param voltageFitR2 the coefficient of determination of the voltage fit, 0..1
   * @param rmseVolts the root-mean-square residual, in volts
   * @param samples how many samples the fit consumed
   * @param finalResponse a one-word verdict such as {@code "GOOD"}
   * @param riseTimeSec measured rise time of the final step, in seconds
   * @param overshootPct measured overshoot of the final step, as a percentage
   * @param settleTimeSec measured settling time of the final step, in seconds
   * @param steadyStateErrorSi residual steady-state error, in SI units
   */
  public record QualityRecord(
      double voltageFitR2,
      double rmseVolts,
      int samples,
      String finalResponse,
      double riseTimeSec,
      double overshootPct,
      double settleTimeSec,
      double steadyStateErrorSi) {}

  /**
   * Atomically write the runtime file, preserving every other mechanism's entry.
   *
   * <p>Atomic because a brownout mid-write must not leave a truncated file that bricks the next boot:
   * {@code PersistentStore} writes a sibling {@code .tmp} and moves it into place.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @param configHash the hash of the physical configuration these gains were tuned against
   * @param gains the seven gains, in volts per SI unit
   * @param controlValues the {@code ControlConfig} values in user units, keyed by topic name
   * @param provenance which {@link ValueSource} produced each gain
   * @param quality the fit record, when there is one
   * @return true when the file was written
   */
  public static boolean saveToRobot(
      String mechanism,
      String configHash,
      Gains gains,
      Map<String, Double> controlValues,
      Map<GainId, String> provenance,
      Optional<QualityRecord> quality) {
    PersistentStore store = PersistentStore.persistent();
    Map<String, Object> file = readOrCreate(store);
    Map<String, Object> mechanisms = childObject(file, "mechanisms");
    Map<String, Object> entry = childObject(mechanisms, mechanism);

    entry.put("configHash", configHash);
    entry.put("gains", gainsBlock(gains));
    if (controlValues != null && !controlValues.isEmpty()) {
      entry.put("control", new LinkedHashMap<String, Object>(controlValues));
    }
    if (provenance != null && !provenance.isEmpty()) {
      Map<String, Object> block = new LinkedHashMap<>();
      for (GainId id : GainId.values()) {
        String source = provenance.get(id);
        block.put(id.key(), source == null ? ValueSource.CODE_DEFAULT.name() : source);
      }
      entry.put("provenance", block);
    }
    quality.ifPresent(q -> entry.put("quality", qualityBlock(q)));

    stamp(file);
    boolean ok = store.writeJson(kFileName, file);
    if (!ok) {
      Alerts.warning(
              kAlertGroup,
              "Could not write "
                  + robotFile()
                  + ": "
                  + store.lastError().orElse("(no reason reported)")
                  + ". Your tuned values are still live on the robot but will be lost at the next"
                  + " reboot. Fix: check the roboRIO is not out of disk space (Driver Station"
                  + " reports it), then press Save again.",
              MatchImpact.PIT_ONLY)
          .set(true);
      return false;
    }
    if (s_mirrorToPreferences) {
      for (GainId id : GainId.values()) {
        Preferences.setDouble(mechanism + "/" + id.key(), gains.get(id));
      }
    }
    return true;
  }

  /**
   * Persist a named setpoint, keyed by {@code RobotId} so a practice-bot value can be promoted.
   *
   * <p>Setpoints live at {@code /Tuning/<Mechanism>/Setpoints/<NAME>} on the wire and under the
   * mechanism's {@code "setpoints"} block in the file. The robot's identity is stamped at the top of
   * the file, so a file copied from the practice bot to the competition bot is visibly a
   * practice-bot file rather than a silent one.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @param setpointName the setpoint's name, e.g. {@code "L4"}
   * @param userUnits the value, in the mechanism's user units
   * @return true when the file was written
   */
  public static boolean saveSetpoint(String mechanism, String setpointName, double userUnits) {
    PersistentStore store = PersistentStore.persistent();
    Map<String, Object> file = readOrCreate(store);
    Map<String, Object> mechanisms = childObject(file, "mechanisms");
    Map<String, Object> entry = childObject(mechanisms, mechanism);
    childObject(entry, "setpoints").put(setpointName, userUnits);
    stamp(file);
    return store.writeJson(kFileName, file);
  }

  /**
   * Every persisted setpoint for one mechanism, merged deploy-then-robot.
   *
   * <p>A deploy-file block written on a different robot is skipped, because a practice bot's L4 is
   * not a competition bot's L4 and the difference is measured in bent metal.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @return the setpoints in file order; empty when none are persisted
   */
  public static Map<String, Double> setpoints(String mechanism) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (PersistentStore store : List.of(PersistentStore.deploy(), PersistentStore.persistent())) {
      Optional<Map<String, Object>> file = store.readJson(kFileName);
      if (file.isEmpty() || !robotMatches(file.get())) {
        continue;
      }
      Object mechanisms = file.get().get("mechanisms");
      if (!(mechanisms instanceof Map)) {
        continue;
      }
      Object entry = ((Map<?, ?>) mechanisms).get(mechanism);
      if (!(entry instanceof Map)) {
        continue;
      }
      Object block = ((Map<?, ?>) entry).get("setpoints");
      if (!(block instanceof Map)) {
        continue;
      }
      for (Map.Entry<?, ?> e : ((Map<?, ?>) block).entrySet()) {
        if (e.getValue() instanceof Number) {
          out.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
        }
      }
    }
    return out;
  }

  /**
   * Delete the runtime entry for one mechanism, reverting it to the deploy file or the code default.
   *
   * <p>"Put it back the way it was" must be one action. A student who tuned an arm into a shape they
   * do not like must not have to remember seven numbers or find a text editor on a roboRIO.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @return true when an entry existed and the file was rewritten without it
   */
  public static boolean forget(String mechanism) {
    PersistentStore store = PersistentStore.persistent();
    Optional<Map<String, Object>> read = store.readJson(kFileName);
    if (read.isEmpty()) {
      return false;
    }
    Map<String, Object> file = new LinkedHashMap<>(read.get());
    Object mechanisms = file.get("mechanisms");
    if (!(mechanisms instanceof Map)) {
      return false;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : ((Map<?, ?>) mechanisms).entrySet()) {
      copy.put(String.valueOf(e.getKey()), e.getValue());
    }
    if (copy.remove(mechanism) == null) {
      return false;
    }
    file.put("mechanisms", copy);
    stamp(file);
    return store.writeJson(kFileName, file);
  }

  /**
   * Additionally mirror saved gains into WPILib {@code Preferences}.
   *
   * <p>Off by default and deliberately nothing more than a mirror. {@code Preferences} persists
   * correctly and it is the documented WPILib answer, but it is a flat key-value namespace shared
   * with everything else on the robot, it carries no provenance, no quality record and no
   * {@code configHash}, and it is not diffable or reviewable. This switch exists for teams that
   * already built tooling around it.
   *
   * @param enabled true to mirror every {@link #saveToRobot} into {@code Preferences}
   */
  public static void mirrorToPreferences(boolean enabled) {
    s_mirrorToPreferences = enabled;
  }

  /**
   * Whether the {@code Preferences} mirror is on.
   *
   * @return true when mirroring
   */
  public static boolean isMirroringToPreferences() {
    return s_mirrorToPreferences;
  }

  /**
   * The raw parsed contents of one of the two files, for the exporter and the UI.
   *
   * @param deploy true for the committed file, false for the runtime file
   * @return the parsed object, or empty when absent or malformed
   */
  public static Optional<Map<String, Object>> read(boolean deploy) {
    return (deploy ? PersistentStore.deploy() : PersistentStore.persistent()).readJson(kFileName);
  }

  /**
   * Builds the file-shaped object for one mechanism without writing it.
   *
   * <p>{@code ValueExporter.writeDeployBaseline()} uses this to assemble a whole-robot baseline, so
   * the committed file and the runtime file cannot drift into two shapes.
   *
   * @param configHash the configuration hash
   * @param gains the seven gains
   * @param controlValues the {@code ControlConfig} values, in user units
   * @param provenance which source produced each gain
   * @return a fresh, mutable, deterministically ordered object
   */
  public static Map<String, Object> mechanismBlock(
      String configHash,
      Gains gains,
      Map<String, Double> controlValues,
      Map<GainId, String> provenance) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("configHash", configHash);
    entry.put("gains", gainsBlock(gains));
    if (controlValues != null && !controlValues.isEmpty()) {
      entry.put("control", new LinkedHashMap<String, Object>(controlValues));
    }
    if (provenance != null && !provenance.isEmpty()) {
      Map<String, Object> block = new LinkedHashMap<>();
      for (GainId id : GainId.values()) {
        String source = provenance.get(id);
        block.put(id.key(), source == null ? ValueSource.CODE_DEFAULT.name() : source);
      }
      entry.put("provenance", block);
    }
    return entry;
  }

  /**
   * Stamps the schema, the timestamp, the versions and the robot identity onto a file object.
   *
   * @param file the file object to stamp, mutated in place
   */
  public static void stamp(Map<String, Object> file) {
    file.put("schema", kSchema);
    file.put("writtenBy", "PumpkinLib " + org.pumpkinlib.core.PumpkinLib.VERSION);
    file.put("wpilib", edu.wpi.first.wpilibj.util.WPILibVersion.Version);
    RobotIdentity.currentIfResolved().map(RobotId::name).ifPresent(id -> file.put("robotId", id));
    file.put("simulation", Platform.isSimulation());
  }

  /** Clears the {@code Preferences} mirror switch. Test-only. */
  public static void resetForTest() {
    s_mirrorToPreferences = false;
  }

  // ---- internals ---------------------------------------------------------------------------

  private record Merge(ValueSource source, Map<GainId, Double> gains, Map<String, Double> control) {}

  private static Merge mergeFrom(
      PersistentStore store,
      String mechanism,
      String configHash,
      ValueSource source,
      List<String> warnings) {
    Map<GainId, Double> gains = new EnumMap<>(GainId.class);
    Map<String, Double> control = new LinkedHashMap<>();

    if (!store.exists(kFileName)) {
      return new Merge(source, gains, control);
    }
    Optional<Map<String, Object>> read = store.readJson(kFileName);
    if (read.isEmpty()) {
      warnings.add(
          store.resolve(kFileName)
              + " could not be read as JSON ("
              + store.lastError().orElse("no reason reported")
              + "), so every value in it is being ignored and the compile-time defaults are in"
              + " force. A robot that refuses to boot because a JSON file has a stray comma is"
              + " unacceptable, so this is a warning and not a crash. Fix: open the file and look"
              + " for a trailing comma, or delete it and re-tune.");
      return new Merge(source, gains, control);
    }

    Object mechanisms = read.get().get("mechanisms");
    if (!(mechanisms instanceof Map)) {
      return new Merge(source, gains, control);
    }
    Object rawEntry = ((Map<?, ?>) mechanisms).get(mechanism);
    if (!(rawEntry instanceof Map)) {
      return new Merge(source, gains, control);
    }
    Map<?, ?> entry = (Map<?, ?>) rawEntry;

    Object storedHash = entry.get("configHash");
    if (storedHash != null && configHash != null && !configHash.equals(String.valueOf(storedHash))) {
      String message =
          mechanism
              + " gains in "
              + store.resolve(kFileName)
              + " were tuned for a different physical configuration (recorded "
              + storedHash
              + ", this robot is "
              + configHash
              + "). Ignoring them and using the values below this tier. Gains that silently survive"
              + " a gear-ratio or rigging change are how a mechanism gets destroyed after a"
              + " rebuild. Fix: delete that entry, or re-tune the mechanism.";
      warnings.add(message);
      Alerts.error(kAlertGroup, message, MatchImpact.BLOCKS_MATCH).set(true);
      return new Merge(source, gains, control);
    }

    Object gainsBlock = entry.get("gains");
    if (gainsBlock instanceof Map) {
      for (GainId id : GainId.values()) {
        Object value = ((Map<?, ?>) gainsBlock).get(id.key());
        if (value instanceof Number) {
          gains.put(id, ((Number) value).doubleValue());
        }
      }
    }
    Object controlBlock = entry.get("control");
    if (controlBlock instanceof Map) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) controlBlock).entrySet()) {
        if (e.getValue() instanceof Number) {
          control.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
        }
      }
    }
    return new Merge(source, gains, control);
  }

  private static Map<String, Object> gainsBlock(Gains gains) {
    Map<String, Object> block = new LinkedHashMap<>();
    for (GainId id : GainId.values()) {
      block.put(id.key(), gains.get(id));
    }
    return block;
  }

  private static Map<String, Object> qualityBlock(QualityRecord q) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("voltageFitR2", q.voltageFitR2());
    block.put("rmseVolts", q.rmseVolts());
    block.put("samples", q.samples());
    block.put("finalResponse", q.finalResponse());
    block.put("riseTimeSec", q.riseTimeSec());
    block.put("overshootPct", q.overshootPct());
    block.put("settleTimeSec", q.settleTimeSec());
    block.put("steadyStateErrorSi", q.steadyStateErrorSi());
    return block;
  }

  private static Map<String, Object> readOrCreate(PersistentStore store) {
    return new LinkedHashMap<>(store.readJson(kFileName).orElseGet(LinkedHashMap::new));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> childObject(Map<String, Object> parent, String key) {
    Object existing = parent.get(key);
    Map<String, Object> child;
    if (existing instanceof Map) {
      child = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) existing).entrySet()) {
        child.put(String.valueOf(e.getKey()), e.getValue());
      }
    } else {
      child = new LinkedHashMap<>();
    }
    parent.put(key, child);
    return child;
  }

  private static boolean robotMatches(Map<String, Object> file) {
    Object stamped = file.get("robotId");
    if (stamped == null) {
      return true;
    }
    Optional<RobotId> current = RobotIdentity.currentIfResolved();
    return current.isEmpty() || current.get().name().equals(String.valueOf(stamped));
  }
}

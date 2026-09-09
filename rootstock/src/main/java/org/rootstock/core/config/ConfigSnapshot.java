package org.rootstock.core.config;

import edu.wpi.first.wpilibj.Preferences;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ObjDoubleConsumer;
import org.rootstock.core.identity.RobotIdentity;

/**
 * Backup and restore of everything that was tuned on the robot rather than committed in the code.
 *
 * <p>{@code Preferences} persists on the roboRIO filesystem, <b>not in git</b>. A reimage, a robot
 * swap, or a fresh image silently reverts every tuned value with no warning at all. For a small team
 * with one programmer, discovering that at an event is unrecoverable. This class makes the tuned
 * state a file you can commit, diff, and put back.
 *
 * <h2>The three operations</h2>
 *
 * <ul>
 *   <li>{@link #take()} — writes every registered tunable and every WPILib preference to
 *       {@code <persistentDir>/rootstock/snapshots/<timestamp>_<robotId>_<gitSha>.json}. The filename
 *       carries the three facts you need to know whether a snapshot is the one you want.
 *   <li>{@link #restore(Path)} — puts a snapshot back into {@code Preferences} and the tuned-value
 *       store.
 *   <li>{@link #drift()} — compares the live values to
 *       {@code deploy/rootstock/config-snapshot.json}, the baseline that <i>is</i> in git. A
 *       non-empty result at boot raises a {@code WARNING} / {@code PIT_ONLY} alert naming the first
 *       three drifted keys, and that single alert is what turns "the robot behaves differently from
 *       what the code says" from a mystery into a fact.
 * </ul>
 *
 * <p>{@code rootstock pull-config} downloads the newest snapshot over the same port-5800 web server
 * Elastic uses, drops it into {@code src/main/deploy/rootstock/config-snapshot.json}, and prints
 * {@code git diff}. Tuning at the field becomes a committable artifact.
 *
 * <h2>File format</h2>
 *
 * <pre>{@code
 * {
 *   "meta":        { "robotId": "COMP", "gitSha": "a1b2c3d", "takenAt": "2026-03-14T09:41:02" },
 *   "tunables":    { "Elevator/kP": 52.5 },
 *   "preferences": { "Shooter/rpm": 3000.0 }
 * }
 * }</pre>
 *
 * <p>Flat, numeric, and pretty-printed on purpose: it lands in git and a diff a mentor can read at a
 * glance is the entire value of committing it.
 *
 * <h2>Never throws</h2>
 *
 * <p>Every operation returns a value or does nothing, and records why in {@link #lastError()}.
 * A robot that refuses to boot because a snapshot file has a stray comma is worse than one that
 * boots and says the snapshot is unreadable.
 */
public final class ConfigSnapshot {

  /** Directory, under the persistent store, that snapshots are written to. */
  public static final String kSnapshotDirectory = "snapshots";

  /** The committed baseline file name, read from the deploy store by {@link #drift()}. */
  public static final String kCommittedBaselineFile = "config-snapshot.json";

  /** JSON key holding the tuned values registered with {@link ConfigRegistry}. */
  public static final String kTunablesSection = "tunables";

  /** JSON key holding the WPILib {@code Preferences} values. */
  public static final String kPreferencesSection = "preferences";

  /** JSON key holding provenance: robot id, git sha, and when the snapshot was taken. */
  public static final String kMetaSection = "meta";

  /** Values closer than this are treated as equal by {@link #drift()}. */
  public static final double kDriftEpsilon = 1e-9;

  private static final DateTimeFormatter kStamp =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

  private static volatile ObjDoubleConsumer<String> s_tunableRestoreSink;
  private static volatile String s_lastError;

  private ConfigSnapshot() {}

  /**
   * One tuned value that differs between the committed baseline and the running robot.
   *
   * @param key the tunable or preference key
   * @param committed the value in {@code deploy/rootstock/config-snapshot.json}
   * @param live the value the robot is running right now
   */
  public record Drift(String key, double committed, double live) {

    /**
     * The short form used in the drift alert.
     *
     * @return for example {@code Elevator/kP 45.0 -> 52.5}
     */
    public String describe() {
      return key + " " + committed + " -> " + live;
    }
  }

  /**
   * Writes a snapshot of every tuned value on this robot.
   *
   * <p>Includes both halves of "tuned": the tunables registered with {@link ConfigRegistry} (which
   * is everything {@code TuningRegistry} created) and every WPILib preference, because a team that
   * has used {@code Preferences} directly for three seasons should not lose those values just
   * because they predate Rootstock.
   *
   * @return the file that was written, or empty if it could not be written; see
   *     {@link #lastError()}
   */
  public static Optional<Path> take() {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put(kMetaSection, meta());
    document.put(kTunablesSection, ConfigRegistry.liveTunableValues());
    document.put(kPreferencesSection, preferenceValues());

    String name = snapshotFileName();
    PersistentStore store = PersistentStore.persistent();
    String relative = kSnapshotDirectory + "/" + name;
    if (!store.writeJson(relative, document)) {
      s_lastError = store.lastError().orElse("could not write " + relative);
      return Optional.empty();
    }
    s_lastError = null;
    return Optional.of(store.resolve(relative));
  }

  /**
   * A pit button that takes a snapshot.
   *
   * <p>Runs while disabled, because the pit is where you press it, and is gated on
   * {@link DiagnosticsGate} so it is safe to leave bound to a controller button all season.
   *
   * @return a command that writes one snapshot and prints where it went
   */
  public static Command takeCommand() {
    return Commands.runOnce(
            () -> {
              if (!DiagnosticsGate.allowed()) {
                System.out.println(
                    "[Rootstock] ConfigSnapshot.take() ignored: the robot is not in diagnostics"
                        + " (Test) mode. "
                        + DiagnosticsGate.describe());
                return;
              }
              Optional<Path> written = take();
              if (written.isPresent()) {
                System.out.println(
                    "[Rootstock] Config snapshot written to "
                        + written.get()
                        + ". Run `rootstock pull-config` to bring it into git.");
              } else {
                System.out.println(
                    "[Rootstock] Could NOT write a config snapshot: "
                        + lastError().orElse("unknown error"));
              }
            })
        .ignoringDisable(true)
        .withName("ConfigSnapshot.take");
  }

  /**
   * Reloads a snapshot into {@code Preferences} and the tuned-value store.
   *
   * <p>Preferences are written directly. Tunables are handed to the restore sink that
   * {@code TuningRegistry} installs via {@link #setTunableRestoreSink(ObjDoubleConsumer)} — this
   * class cannot write into the tuning package itself, because ArchUnit rule 9 forbids an arrow from
   * {@code core} to {@code tuning}. With no sink installed the tunable half is skipped and
   * {@link #lastError()} says so, which is the honest outcome for a robot with no tuning layer.
   *
   * <p>Values restored here take effect immediately in {@code Preferences} and on the next read for
   * tunables. Never throws.
   *
   * @param json the snapshot file to restore, as returned by {@link #take()}
   */
  public static void restore(Path json) {
    if (json == null) {
      s_lastError = "ConfigSnapshot.restore(json): json was null.";
      return;
    }
    PersistentStore store = PersistentStore.at(json.getParent() == null ? Path.of(".") : json.getParent());
    Optional<Map<String, Object>> parsed = store.readJson(json.getFileName().toString());
    if (parsed.isEmpty()) {
      s_lastError = store.lastError().orElse("could not read " + json);
      return;
    }

    Map<String, Double> preferences = numericSection(parsed.get(), kPreferencesSection);
    for (Map.Entry<String, Double> entry : preferences.entrySet()) {
      try {
        Preferences.setDouble(entry.getKey(), entry.getValue());
      } catch (RuntimeException | LinkageError e) {
        s_lastError = "could not restore preference " + entry.getKey() + ": " + e;
      }
    }

    Map<String, Double> tunables = numericSection(parsed.get(), kTunablesSection);
    ObjDoubleConsumer<String> sink = s_tunableRestoreSink;
    if (sink == null) {
      if (!tunables.isEmpty()) {
        s_lastError =
            "Restored "
                + preferences.size()
                + " preference(s) from "
                + json
                + ", but skipped "
                + tunables.size()
                + " tuned value(s): no tunable restore sink is installed. TuningRegistry installs"
                + " one during init, so this robot has no tuning layer running.";
      }
      return;
    }
    for (Map.Entry<String, Double> entry : tunables.entrySet()) {
      try {
        sink.accept(entry.getKey(), entry.getValue());
      } catch (RuntimeException e) {
        s_lastError = "could not restore tuned value " + entry.getKey() + ": " + e;
      }
    }
  }

  /**
   * Compares the live tuned values to the committed baseline.
   *
   * <p>The baseline is {@code deploy/rootstock/config-snapshot.json} — the file that ships with the
   * code and is therefore in git. Only keys present in <i>both</i> are compared: a key that exists
   * only live is a tunable added since the baseline was committed (not drift, just newer code), and
   * a key that exists only in the baseline is one that was deleted.
   *
   * @return every value that differs by more than {@value #kDriftEpsilon}, in baseline order; empty
   *     when the robot is running exactly what the committed snapshot says, or when there is no
   *     committed snapshot to compare against
   */
  public static List<Drift> drift() {
    Optional<Map<String, Object>> baseline =
        PersistentStore.deploy().readJson(kCommittedBaselineFile);
    if (baseline.isEmpty()) {
      return List.of();
    }

    Map<String, Double> committed = new LinkedHashMap<>();
    committed.putAll(numericSection(baseline.get(), kTunablesSection));
    committed.putAll(numericSection(baseline.get(), kPreferencesSection));

    Map<String, Double> live = new LinkedHashMap<>(ConfigRegistry.liveTunableValues());
    live.putAll(preferenceValues());

    List<Drift> out = new ArrayList<>();
    for (Map.Entry<String, Double> entry : committed.entrySet()) {
      Double liveValue = live.get(entry.getKey());
      if (liveValue == null) {
        continue;
      }
      if (Math.abs(liveValue - entry.getValue()) > kDriftEpsilon) {
        out.add(new Drift(entry.getKey(), entry.getValue(), liveValue));
      }
    }
    return List.copyOf(out);
  }

  /**
   * The one-sentence drift alert text.
   *
   * <p>Names the first three drifted keys with both values, because "3 values differ" without the
   * names is exactly the alert students learn to scroll past.
   *
   * @return the alert sentence, or empty if nothing has drifted
   */
  public static Optional<String> driftSummary() {
    List<Drift> drifts = drift();
    if (drifts.isEmpty()) {
      return Optional.empty();
    }
    List<String> first = new ArrayList<>();
    for (int i = 0; i < Math.min(3, drifts.size()); i++) {
      first.add(drifts.get(i).describe());
    }
    return Optional.of(
        drifts.size()
            + " tuned value"
            + (drifts.size() == 1 ? "" : "s")
            + " differ from the committed snapshot: "
            + String.join(", ", first)
            + (drifts.size() > 3 ? ", ..." : "")
            + ". Run `rootstock pull-config` to commit them, or ConfigSnapshot.restore(...) to put"
            + " the committed values back.");
  }

  /**
   * The newest snapshot on this robot, by file name (which begins with a sortable timestamp).
   *
   * @return the newest snapshot file, or empty if none has been taken
   */
  public static Optional<Path> newestSnapshot() {
    List<Path> files = PersistentStore.persistent().list(kSnapshotDirectory);
    return files.isEmpty() ? Optional.empty() : Optional.of(files.get(files.size() - 1));
  }

  /**
   * Every snapshot on this robot, oldest first.
   *
   * @return the snapshot files in timestamp order
   */
  public static List<Path> snapshots() {
    return PersistentStore.persistent().list(kSnapshotDirectory);
  }

  /**
   * Installs the sink {@link #restore(Path)} pushes tuned values into.
   *
   * <p>Called by {@code TuningRegistry} during init. Push, not pull, for the same reason
   * {@link ConfigRegistry#registerTunable} is push: an arrow from {@code core} to {@code tuning}
   * would break ArchUnit rule 9.
   *
   * @param sink accepts a tunable key and the value to restore
   * @throws IllegalArgumentException if {@code sink} is null; use {@link #resetForTest()} to remove
   */
  public static void setTunableRestoreSink(ObjDoubleConsumer<String> sink) {
    if (sink == null) {
      throw new IllegalArgumentException(
          "ConfigSnapshot.setTunableRestoreSink(sink): sink was null.");
    }
    s_tunableRestoreSink = sink;
  }

  /**
   * The reason the most recent operation failed or was incomplete.
   *
   * @return the message, or empty if the last operation fully succeeded
   */
  public static Optional<String> lastError() {
    return Optional.ofNullable(s_lastError);
  }

  /** Clears the restore sink and the last error. Test-only. */
  public static void resetForTest() {
    s_tunableRestoreSink = null;
    s_lastError = null;
  }

  /**
   * A human summary for the boot dump and {@code rootstock doctor}.
   *
   * @return the snapshot directory, how many snapshots exist, and the current drift
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder("ConfigSnapshot: ");
    List<Path> snapshots = snapshots();
    sb.append(snapshots.size())
        .append(" snapshot(s) in ")
        .append(PersistentStore.persistent().resolve(kSnapshotDirectory));
    newestSnapshot().ifPresent(p -> sb.append("\n  newest: ").append(p.getFileName()));
    sb.append("\n  ").append(driftSummary().orElse("No drift from the committed snapshot."));
    lastError().ifPresent(err -> sb.append("\n  last error: ").append(err));
    return sb.toString();
  }

  /**
   * The file name a snapshot taken right now would get.
   *
   * @return {@code <timestamp>_<robotId>_<gitSha>.json}
   */
  static String snapshotFileName() {
    String robot = RobotIdentity.currentIfResolved().map(Enum::name).orElse("UNKNOWN");
    String sha = DeployInfo.gitShaShort();
    // LocalDateTime, not Clock: Clock is the loop's monotonic timebase and cannot produce a
    // wall-clock stamp. A roboRIO with no DS connection has a wrong date; the robot id and git sha
    // in the same name are what actually identify the file, so a wrong date costs only sort order.
    return kStamp.format(LocalDateTime.now()) + "_" + robot + "_" + sha + ".json";
  }

  private static Map<String, String> meta() {
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("robotId", RobotIdentity.currentIfResolved().map(Enum::name).orElse("UNKNOWN"));
    meta.put("robotIdSource", RobotIdentity.source());
    meta.put("gitSha", DeployInfo.gitSha());
    meta.put("gitBranch", DeployInfo.gitBranch());
    meta.put("gitDirty", DeployInfo.dirty().name());
    meta.put("rootstock", DeployInfo.rootstockVersion());
    meta.put("takenAt", LocalDateTime.now().toString());
    return meta;
  }

  private static Map<String, Double> preferenceValues() {
    Map<String, Double> out = new LinkedHashMap<>();
    try {
      for (String key : Preferences.getKeys()) {
        // Preferences is untyped at the API level; a non-numeric entry reads back as NaN with this
        // default, which is the simplest way to keep the snapshot flat and numeric.
        double value = Preferences.getDouble(key, Double.NaN);
        if (!Double.isNaN(value)) {
          out.put(key, value);
        }
      }
    } catch (RuntimeException | LinkageError e) {
      // LinkageError as well: Preferences is NetworkTables-backed, so on a machine with no WPILib
      // natives the whole class fails to initialise. A snapshot without the preference half is
      // still worth writing; a thrown error would cost the tunable half too.
      s_lastError = "could not read WPILib Preferences: " + e;
    }
    return out;
  }

  private static Map<String, Double> numericSection(Map<String, Object> document, String section) {
    Map<String, Double> out = new LinkedHashMap<>();
    Object raw = document.get(section);
    if (!(raw instanceof Map<?, ?> map)) {
      return out;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
        out.put(key, number.doubleValue());
      }
    }
    return out;
  }
}

package org.rootstock.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.rootstock.core.compat.Platform;

/**
 * Small, never-throwing JSON and text storage under a Rootstock-owned directory.
 *
 * <p>Rootstock keeps exactly two kinds of file on a robot, and they live in two different places
 * for a reason:
 *
 * <ul>
 *   <li>{@link #persistent()} — {@code Platform.persistentDir()/rootstock/}. Written <b>by the
 *       robot</b>, survives a redeploy, wiped by a reimage. The robot's identity file, the
 *       {@code everFmsAttached} target state, and tuned-value snapshots taken at an event live
 *       here. A file here is a fact about <i>this robot</i>.
 *   <li>{@link #deploy()} — {@code Platform.deployDir()/rootstock/}. Written <b>by the laptop</b> and
 *       shipped with the code, so it is in git and identical on every robot. The committed
 *       config-snapshot baseline and {@code schedule.json} live here. A file here is a fact about
 *       <i>this build</i>.
 * </ul>
 *
 * <p>Putting a robot-specific fact in the deploy directory is the mistake {@code design/06} §7.1
 * calls out by name: the deploy directory is deployed from the laptop, so every robot receives the
 * same file.
 *
 * <p><b>Nothing here throws.</b> Every read returns an {@link Optional} and every write returns a
 * boolean; the reason for the most recent failure is available from {@link #lastError()}. A robot
 * that will not boot because a JSON file has a stray comma is strictly worse than a robot that boots
 * and says so — {@code design/01} §5.6, "nothing throws from a static initializer, ever".
 *
 * <p><b>Filesystem access goes through {@code Platform}, never {@code Filesystem}</b> (ArchUnit rule
 * 2): {@code Filesystem.getOperatingDirectory()} moves in the 2027 SystemCore port, and confining
 * the call to {@code core.compat} makes that an edit in one file.
 *
 * <p>Writes are atomic where the filesystem allows it: content goes to a {@code .tmp} sibling and is
 * then moved over the target, so a brownout mid-write cannot leave a half-written snapshot that
 * parses as valid JSON with three gains missing.
 *
 * <p>Instances are cheap; {@link #persistent()} and {@link #deploy()} return shared singletons.
 */
public final class PersistentStore {

  /** The subdirectory Rootstock owns under both roots. Never write outside it. */
  public static final String kRootstockSubdirectory = "rootstock";

  private static final ObjectMapper kMapper = new ObjectMapper();

  private static volatile PersistentStore s_persistent;
  private static volatile PersistentStore s_deploy;

  private final Path m_root;
  private volatile String m_lastError;

  private PersistentStore(Path root) {
    m_root = root;
  }

  /**
   * The store the robot writes: {@code Platform.persistentDir()/rootstock/}.
   *
   * <p>Survives a redeploy. This is where a fact about <i>this robot</i> belongs — the identity
   * file, the FMS-attach flag, snapshots taken at an event.
   *
   * @return the shared persistent store
   */
  public static PersistentStore persistent() {
    PersistentStore local = s_persistent;
    if (local == null) {
      synchronized (PersistentStore.class) {
        local = s_persistent;
        if (local == null) {
          local = new PersistentStore(Platform.persistentDir().resolve(kRootstockSubdirectory));
          s_persistent = local;
        }
      }
    }
    return local;
  }

  /**
   * The store the laptop writes: {@code Platform.deployDir()/rootstock/}.
   *
   * <p>Shipped with the code and therefore in git. This is where a fact about <i>this build</i>
   * belongs — the committed config-snapshot baseline, the pre-event match schedule. It is read-only
   * in practice on a roboRIO; writes are still permitted so that simulation can produce these files.
   *
   * @return the shared deploy store
   */
  public static PersistentStore deploy() {
    PersistentStore local = s_deploy;
    if (local == null) {
      synchronized (PersistentStore.class) {
        local = s_deploy;
        if (local == null) {
          local = new PersistentStore(Platform.deployDir().resolve(kRootstockSubdirectory));
          s_deploy = local;
        }
      }
    }
    return local;
  }

  /**
   * A store rooted at an arbitrary directory. Test and tooling entry point.
   *
   * @param root the directory to read and write under; created lazily on first write
   * @return a new store
   * @throws IllegalArgumentException if {@code root} is null
   */
  public static PersistentStore at(Path root) {
    if (root == null) {
      throw new IllegalArgumentException("PersistentStore.at(root): root was null.");
    }
    return new PersistentStore(root);
  }

  /** Forgets the cached {@link #persistent()} and {@link #deploy()} stores. Test-only. */
  public static void resetForTest() {
    s_persistent = null;
    s_deploy = null;
  }

  /**
   * The directory this store reads and writes under.
   *
   * @return the root path; it may not exist yet
   */
  public Path root() {
    return m_root;
  }

  /**
   * Resolves a path inside this store.
   *
   * @param relative a relative path such as {@code "robot-id"} or {@code "snapshots/x.json"}
   * @return the absolute path
   * @throws IllegalArgumentException if {@code relative} is null, blank, or absolute — an absolute
   *     path would escape the Rootstock-owned directory, and nothing in this library has a reason
   *     to write elsewhere on a robot
   */
  public Path resolve(String relative) {
    if (relative == null || relative.isBlank()) {
      throw new IllegalArgumentException(
          "PersistentStore.resolve(relative): relative was "
              + (relative == null ? "null" : "blank")
              + ". Pass a file name such as \"robot-id\".");
    }
    Path candidate = Path.of(relative);
    if (candidate.isAbsolute()) {
      throw new IllegalArgumentException(
          "PersistentStore.resolve(\""
              + relative
              + "\"): absolute paths are not allowed. Rootstock only reads and writes under "
              + m_root
              + "; use PersistentStore.at(path) if you genuinely need another directory.");
    }
    return m_root.resolve(candidate).normalize();
  }

  /**
   * Whether a file exists in this store.
   *
   * @param relative the relative file name
   * @return true if the file exists and is readable
   */
  public boolean exists(String relative) {
    return Files.isReadable(resolve(relative));
  }

  /**
   * Reads a whole file as UTF-8 text.
   *
   * @param relative the relative file name
   * @return the contents, or empty if the file is absent or unreadable
   */
  public Optional<String> readText(String relative) {
    Path path = resolve(relative);
    try {
      if (!Files.isReadable(path)) {
        return Optional.empty();
      }
      return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not read " + path + ": " + e;
      return Optional.empty();
    }
  }

  /**
   * Writes a whole file as UTF-8 text, atomically where the filesystem allows it.
   *
   * <p>Creates parent directories as needed.
   *
   * @param relative the relative file name
   * @param contents the text to write
   * @return true on success; on failure see {@link #lastError()}
   */
  public boolean writeText(String relative, String contents) {
    Path path = resolve(relative);
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(tmp, contents == null ? "" : contents, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        // Some filesystems (and some simulated environments) cannot do an atomic replace.
        // A non-atomic move is still better than writing in place, so fall back rather than fail.
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not write " + path + ": " + e;
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // Leaving a stray .tmp is not worth a second failure path.
      }
      return false;
    }
  }

  /**
   * Reads a JSON object as a string-keyed map.
   *
   * <p>Values keep their JSON types: numbers arrive as {@link Number}, nested objects as
   * {@code Map}, arrays as {@code List}. Use {@link #readJsonDoubles(String)} when you want the flat
   * numeric view a tuned-value file has.
   *
   * @param relative the relative file name
   * @return the parsed object, or empty if the file is absent, unreadable, or not a JSON object
   */
  public Optional<Map<String, Object>> readJson(String relative) {
    Optional<String> text = readText(relative);
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      Map<String, Object> parsed =
          kMapper.readValue(text.get(), new TypeReference<LinkedHashMap<String, Object>>() {});
      return Optional.ofNullable(parsed);
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not parse " + resolve(relative) + " as a JSON object: " + e;
      return Optional.empty();
    }
  }

  /**
   * Reads a JSON object as a flat map of numbers, skipping anything that is not a number.
   *
   * <p>The tuned-value shape: {@code {"Elevator/kP": 52.5, "Shooter/kV": 0.118}}. Non-numeric
   * entries are skipped rather than failing the whole read, because one hand-edited string in a
   * snapshot must not cost a team every other value in the file.
   *
   * @param relative the relative file name
   * @return the numeric entries, in file order; empty if the file is absent or unparseable
   */
  public Optional<Map<String, Double>> readJsonDoubles(String relative) {
    Optional<Map<String, Object>> raw = readJson(relative);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Double> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.get().entrySet()) {
      if (entry.getValue() instanceof Number n) {
        out.put(entry.getKey(), n.doubleValue());
      }
    }
    return Optional.of(out);
  }

  /**
   * Writes a map as pretty-printed JSON, atomically where the filesystem allows it.
   *
   * <p>Pretty-printed on purpose: these files land in git via {@code rootstock pull-config}, and a
   * one-line JSON blob produces a diff nobody can read.
   *
   * @param relative the relative file name
   * @param values the object to write; nested maps and lists are written as nested JSON
   * @return true on success; on failure see {@link #lastError()}
   */
  public boolean writeJson(String relative, Map<String, ?> values) {
    try {
      String json =
          kMapper.writerWithDefaultPrettyPrinter().writeValueAsString(values == null ? Map.of() : values);
      return writeText(relative, json + System.lineSeparator());
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not serialise " + resolve(relative) + " as JSON: " + e;
      return false;
    }
  }

  /**
   * Deletes a file.
   *
   * @param relative the relative file name
   * @return true if the file existed and was deleted
   */
  public boolean delete(String relative) {
    Path path = resolve(relative);
    try {
      return Files.deleteIfExists(path);
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not delete " + path + ": " + e;
      return false;
    }
  }

  /**
   * Lists the regular files in a subdirectory of this store, newest last.
   *
   * <p>Sorted by file name rather than modification time, because snapshot names begin with a
   * sortable timestamp and a roboRIO without a synchronised clock reports modification times that
   * are not ordered the way a human expects.
   *
   * @param relativeDir the relative directory, for example {@code "snapshots"}
   * @return the files in name order; empty if the directory does not exist
   */
  public List<Path> list(String relativeDir) {
    Path dir = resolve(relativeDir);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      List<Path> out = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
      out.sort(Comparator.comparing(p -> p.getFileName().toString()));
      return List.copyOf(out);
    } catch (IOException | RuntimeException e) {
      m_lastError = "could not list " + dir + ": " + e;
      return List.of();
    }
  }

  /**
   * The reason the most recent operation failed.
   *
   * <p>This is the whole point of a never-throwing store: the failure is still fully described, it
   * just does not take the robot down. Alerts and {@code rootstock doctor} read this.
   *
   * @return the last error message, or empty if nothing has failed
   */
  public Optional<String> lastError() {
    return Optional.ofNullable(m_lastError);
  }

  /** Clears {@link #lastError()}. */
  public void clearLastError() {
    m_lastError = null;
  }

  /**
   * A human summary of this store, for the boot dump and {@code rootstock doctor}.
   *
   * @return the root, whether it exists, and how many files it holds
   */
  public String describe() {
    boolean exists = Files.isDirectory(m_root);
    StringBuilder sb = new StringBuilder("PersistentStore ").append(m_root);
    sb.append(exists ? " (exists" : " (does not exist yet");
    if (exists) {
      try (Stream<Path> stream = Files.list(m_root)) {
        sb.append(", ").append(stream.filter(Files::isRegularFile).count()).append(" file(s)");
      } catch (IOException | RuntimeException e) {
        sb.append(", unreadable: ").append(e);
      }
    }
    sb.append(')');
    lastError().ifPresent(err -> sb.append("\n  last error: ").append(err));
    return sb.toString();
  }
}

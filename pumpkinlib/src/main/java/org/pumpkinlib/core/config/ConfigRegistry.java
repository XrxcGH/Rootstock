package org.pumpkinlib.core.config;

import edu.wpi.first.util.struct.StructSerializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * One answer to "what did this robot actually run with?"
 *
 * <p>Every resolved PumpkinLib config record registers here at construction, and every tunable
 * push-registers its live value. At boot {@link #publishAll()} writes the whole set into the log, so
 * the question is answerable from the log alone, months later, for a match nobody recorded.
 *
 * <p>That is the introspectability win a JSON config file is usually reached for — <b>without</b>
 * giving up the compiler. Configs stay immutable records with {@code withX()} copy methods, per-robot
 * differences stay {@code Overlay} functions the compiler checks, and the log gets the resolved
 * result. There is no second source of truth to drift.
 *
 * <h2>Why publishing is injected rather than called directly</h2>
 *
 * <p>This class does not import AdvantageKit's {@code Logger} and cannot: ArchUnit rule 1c confines
 * the AdvantageKit driver types to {@code telemetry}, the {@code core} root, {@code tuning} and
 * {@code viz}, and rule 9 forbids any arrow from {@code core} to {@code telemetry}. So the publisher
 * is handed in — {@code PumpkinLifecycle} installs one during init, exactly as
 * {@code Clock.setSource(DoubleSupplier)} lets the test kit replace the clock. With no publisher
 * installed {@link #publishAll()} is a no-op that records why, which is the correct behaviour for a
 * team on the vendordep-only path who never started a logger.
 *
 * <h2>Tunables push, they are never pulled</h2>
 *
 * <p>{@code TuningRegistry} calls {@link #registerTunable(String, DoubleSupplier, double)} for every
 * tunable it creates. The reverse — a query API this class calls into the tuning package — would
 * point an arrow out of {@code core} and break rule 9. Push registration is decision D11 and it is
 * why {@link #tunables()} can enumerate live values without {@code core} knowing what a
 * {@code Tunable} is.
 *
 * <p>All state is static, because there is one robot. {@link #clearForTest()} resets it.
 */
public final class ConfigRegistry {

  /**
   * Where a registered config is published. Installed by {@code PumpkinLifecycle}.
   *
   * <p>Deliberately narrow: one method, no lifecycle, no registration of its own. It is not a
   * "sink SPI" — decision 3 deleted every {@code TelemetrySink} in the library, because there is one
   * logging backend and nothing to plug. This is the single call {@code core} needs to make in the
   * other direction, expressed as a lambda.
   */
  @FunctionalInterface
  public interface ConfigPublisher {
    /**
     * Publishes one resolved config.
     *
     * @param path the log path, for example {@code /Pumpkin/Config/Elevator}
     * @param config the resolved config value
     */
    void publish(String path, StructSerializable config);
  }

  /**
   * One registered tunable, as {@code core} sees it.
   *
   * @param key the tunable's key, for example {@code Elevator/kP}
   * @param live reads the current value, including any dashboard edit
   * @param compiled the value compiled into the jar, which is what a snapshot diff compares against
   */
  public record TunableEntry(String key, DoubleSupplier live, double compiled) {

    /** Canonical constructor. */
    public TunableEntry {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException(
            "ConfigRegistry.registerTunable(key, live, compiled): key was "
                + (key == null ? "null" : "blank")
                + ". Use the tunable's full path, for example \"Elevator/kP\".");
      }
      if (live == null) {
        throw new IllegalArgumentException(
            "ConfigRegistry.registerTunable(\"" + key + "\", live, compiled): live was null.");
      }
    }

    /**
     * The tunable's current value.
     *
     * @return the live value, or the compiled value if the supplier throws — a broken tunable must
     *     not take out the snapshot of every other one
     */
    public double liveValue() {
      try {
        return live.getAsDouble();
      } catch (RuntimeException e) {
        return compiled;
      }
    }

    /**
     * Whether the live value has moved away from what was compiled in.
     *
     * @return true if the live value differs from {@link #compiled()} by more than 1e-9
     */
    public boolean differsFromCompiled() {
      return Math.abs(liveValue() - compiled) > 1e-9;
    }
  }

  private static final Map<String, StructSerializable> s_configs = new LinkedHashMap<>();
  private static final Map<String, TunableEntry> s_tunables = new LinkedHashMap<>();
  private static final List<String> s_warnings = new ArrayList<>();
  private static volatile ConfigPublisher s_publisher;
  private static volatile String s_lastPublishNote = "publishAll() has not run";

  private ConfigRegistry() {}

  /**
   * Registers a resolved config so it lands in the log at boot.
   *
   * <p>Registering the same path twice keeps the newest value and records a warning rather than
   * throwing: a duplicate path is a naming mistake, not a reason for a robot not to boot
   * ({@code design/01} §5.6). {@link #warnings()} surfaces it and {@code PumpkinRegistry} turns it
   * into a collected error.
   *
   * @param path the log path, for example {@code /Pumpkin/Config/Elevator}
   * @param config the fully resolved config, after every overlay has been applied
   * @param <T> the config type; must be struct-serialisable, because a fixed-size struct is what
   *     makes a config queryable in AdvantageScope rather than a wall of text
   * @throws IllegalArgumentException if {@code path} is blank or {@code config} is null
   */
  public static <T extends StructSerializable> void register(String path, T config) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException(
          "ConfigRegistry.register(path, config): path was "
              + (path == null ? "null" : "blank")
              + ". Use a log path such as \"/Pumpkin/Config/Elevator\".");
    }
    if (config == null) {
      throw new IllegalArgumentException(
          "ConfigRegistry.register(\"" + path + "\", config): config was null.");
    }
    synchronized (ConfigRegistry.class) {
      StructSerializable previous = s_configs.put(path.trim(), config);
      if (previous != null && previous != config) {
        s_warnings.add(
            "Two different configs registered at \""
                + path.trim()
                + "\" ("
                + previous.getClass().getSimpleName()
                + " then "
                + config.getClass().getSimpleName()
                + "). The log will show only the second. Give them distinct paths — the path is how"
                + " you find the config in AdvantageScope six weeks later.");
      }
    }
  }

  /**
   * Push-registers a tunable's live value.
   *
   * <p>Called by {@code TuningRegistry} for every tunable it creates. Registering the same key twice
   * keeps the newest supplier and records a warning.
   *
   * @param key the tunable's key, for example {@code Elevator/kP}
   * @param live reads the current value
   * @param compiled the value compiled into the jar
   */
  public static void registerTunable(String key, DoubleSupplier live, double compiled) {
    TunableEntry entry = new TunableEntry(key.trim(), live, compiled);
    synchronized (ConfigRegistry.class) {
      TunableEntry previous = s_tunables.put(entry.key(), entry);
      if (previous != null) {
        s_warnings.add(
            "Tunable \""
                + entry.key()
                + "\" was registered twice. The snapshot and the drift check will use the second"
                + " registration; if two mechanisms share a key, one of them is editing the other's"
                + " gain from the dashboard.");
      }
    }
  }

  /**
   * Installs the publisher {@link #publishAll()} writes through.
   *
   * <p>Called once by {@code PumpkinLifecycle} during init, after the logger has started.
   *
   * @param publisher where to write registered configs
   * @throws IllegalArgumentException if {@code publisher} is null; use {@link #clearForTest()} to
   *     uninstall
   */
  public static void setPublisher(ConfigPublisher publisher) {
    if (publisher == null) {
      throw new IllegalArgumentException(
          "ConfigRegistry.setPublisher(publisher): publisher was null.");
    }
    s_publisher = publisher;
  }

  /**
   * Logs every registered config as a struct.
   *
   * <p>Called once at boot by {@code PumpkinLifecycle}. Never throws: a publisher that fails on one
   * config is recorded in {@link #lastPublishNote()} and the remaining configs still publish,
   * because a telemetry problem must not cost you the record of what the other four mechanisms ran
   * with.
   */
  public static void publishAll() {
    ConfigPublisher publisher = s_publisher;
    if (publisher == null) {
      s_lastPublishNote =
          "No ConfigPublisher installed, so "
              + s_configs.size()
              + " registered config(s) were not logged. PumpkinLifecycle installs one during init;"
              + " a robot that never starts PumpkinLifecycle will not have config in its log.";
      return;
    }
    int published = 0;
    List<String> failures = new ArrayList<>();
    Map<String, StructSerializable> snapshot;
    synchronized (ConfigRegistry.class) {
      snapshot = new LinkedHashMap<>(s_configs);
    }
    for (Map.Entry<String, StructSerializable> entry : snapshot.entrySet()) {
      try {
        publisher.publish(entry.getKey(), entry.getValue());
        published++;
      } catch (RuntimeException e) {
        failures.add(entry.getKey() + ": " + e);
      }
    }
    s_lastPublishNote =
        "published "
            + published
            + " of "
            + snapshot.size()
            + " config(s)"
            + (failures.isEmpty() ? "" : "; failed: " + failures);
  }

  /**
   * The paths of every registered config.
   *
   * @return the paths, in registration order
   */
  public static List<String> paths() {
    synchronized (ConfigRegistry.class) {
      return List.copyOf(s_configs.keySet());
    }
  }

  /**
   * The config registered at a path.
   *
   * @param path the log path
   * @return the config, or empty if nothing is registered there
   */
  public static Optional<StructSerializable> config(String path) {
    synchronized (ConfigRegistry.class) {
      return Optional.ofNullable(s_configs.get(path == null ? "" : path.trim()));
    }
  }

  /**
   * Every registered tunable.
   *
   * @return the entries, in registration order
   */
  public static List<TunableEntry> tunables() {
    synchronized (ConfigRegistry.class) {
      return List.copyOf(s_tunables.values());
    }
  }

  /**
   * The current value of every registered tunable.
   *
   * <p>This is what {@link ConfigSnapshot#take()} writes and what {@link ConfigSnapshot#drift()}
   * compares against the committed baseline.
   *
   * @return an ordered map of key to live value
   */
  public static Map<String, Double> liveTunableValues() {
    Map<String, Double> out = new LinkedHashMap<>();
    for (TunableEntry entry : tunables()) {
      out.put(entry.key(), entry.liveValue());
    }
    return out;
  }

  /**
   * Problems noticed during registration — duplicate paths, duplicate tunable keys.
   *
   * <p>Collected rather than thrown, so {@code PumpkinRegistry} can print all of them at once and
   * the robot still boots.
   *
   * @return the warnings, in the order they were noticed
   */
  public static List<String> warnings() {
    synchronized (ConfigRegistry.class) {
      return List.copyOf(s_warnings);
    }
  }

  /**
   * What the most recent {@link #publishAll()} did.
   *
   * @return a human sentence naming how many configs published and what failed
   */
  public static String lastPublishNote() {
    return s_lastPublishNote;
  }

  /** Clears every registration and the installed publisher. Test-only. */
  public static void clearForTest() {
    synchronized (ConfigRegistry.class) {
      s_configs.clear();
      s_tunables.clear();
      s_warnings.clear();
    }
    s_publisher = null;
    s_lastPublishNote = "publishAll() has not run";
  }

  /**
   * A human summary for the boot dump and {@code pumpkin doctor}.
   *
   * @return the registered config paths, the tunable count, and any warnings
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder("ConfigRegistry: ");
    List<String> paths = paths();
    List<TunableEntry> tunables = tunables();
    sb.append(paths.size()).append(" config(s), ").append(tunables.size()).append(" tunable(s)");
    for (String path : paths) {
      sb.append("\n  config   ").append(path);
    }
    for (TunableEntry entry : tunables) {
      sb.append("\n  tunable  ")
          .append(entry.key())
          .append(" = ")
          .append(entry.liveValue())
          .append(entry.differsFromCompiled() ? " (compiled " + entry.compiled() + ")" : "");
    }
    for (String warning : warnings()) {
      sb.append("\n  WARNING  ").append(warning);
    }
    sb.append("\n  ").append(lastPublishNote());
    return sb.toString();
  }
}

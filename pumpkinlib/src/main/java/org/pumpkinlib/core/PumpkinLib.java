package org.pumpkinlib.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.config.PersistentStore;

/**
 * Library version, and the runtime kill switch.
 *
 * <p><b>The kill switch is the part that matters</b> (DESIGN.md R15, README "A runtime kill
 * switch"). It is week 4 of build season or Saturday morning at a regional, one mechanism is
 * misbehaving in a way nobody can debug in the next ten minutes, and the maintainer is at a
 * different event. Putting {@code Elevator} on its own line in
 * {@code src/main/deploy/pumpkin/disabled.txt} drops that one component to neutral, unregisters it
 * from every registry, and raises one {@code INFO} alert saying so. <b>No code change and no Java
 * redeploy</b> — the file is deployed content, so it ships with a {@code deploy} of the deploy
 * directory alone, and the team keeps driving.
 *
 * <p>{@link #disable(String)} is the same switch reachable from code, for a team that would rather
 * write one line in {@code RobotContainer} than edit a file, and for tests.
 *
 * <p><b>The file is shipped empty by {@code PumpkinTemplate}</b>, present and already in git, so a
 * team that needs it does not have to discover that it exists at the worst possible moment. Lines
 * are trimmed, blank lines are ignored, and {@code #} starts a comment. Matching is
 * case-insensitive, because {@code elevator} and {@code Elevator} being different components is not
 * a distinction anyone wants to debug at an event.
 *
 * <p><b>Nothing here throws.</b> A missing file means "nothing is disabled"; an unreadable one means
 * the same thing plus a warning alert. A kill switch that can itself stop the robot from booting is
 * not a kill switch.
 */
public final class PumpkinLib {

  private PumpkinLib() {}

  /** The library version, year-anchored: {@code <frcYear>.<major>.<patch>}. */
  public static final String VERSION = "2026.0.0-SNAPSHOT";

  /**
   * The kill-switch file, relative to {@code PersistentStore.deploy()} — i.e.
   * {@code src/main/deploy/pumpkin/disabled.txt} on the laptop.
   */
  public static final String kDisabledFileName = "disabled.txt";

  /** The alert group every kill-switch alert is filed under. */
  public static final String kAlertGroup = "Kill switch";

  private static final Set<String> s_disabled = new LinkedHashSet<>();
  private static final Map<String, PumpkinAlert> s_alerts = new LinkedHashMap<>();
  private static boolean s_fileLoaded;
  private static String s_fileNote = "not read yet";

  /**
   * Drops a named component out of the robot for this run.
   *
   * <p>{@link PumpkinRegistry#addAll(Object...)} skips a disabled component entirely — it is not
   * routed to telemetry, health, self-test, tuning or the scheduler, and it appears in the boot
   * summary's skipped list with the reason. Mechanisms additionally check
   * {@link #isDisabled(String)} and hold neutral.
   *
   * <p>Idempotent. Calling it for an already-disabled component does nothing and raises no second
   * alert.
   *
   * @param component the component name, matched case-insensitively against the name the component
   *     reports (a {@code Subsystem}'s {@code getName()}, otherwise its simple class name)
   */
  public static synchronized void disable(String component) {
    String key = normalize(component);
    if (key.isEmpty() || !s_disabled.add(key)) {
      return;
    }
    s_alerts.put(
        key,
        Alerts.info(
                kAlertGroup,
                "\"" + component.trim()
                    + "\" is DISABLED by the runtime kill switch: it is unregistered from every"
                    + " registry and held neutral. Remove it from deploy/pumpkin/"
                    + kDisabledFileName + " (or stop calling PumpkinLib.disable) to bring it back.")
            .sticky(true)
            .set(true));
  }

  /**
   * Re-enables a component disabled by {@link #disable(String)} or by the file.
   *
   * <p>This does <b>not</b> re-register anything: registration happened (or did not) at boot. It is
   * here so a test can undo a {@code disable} and so a team can clear a stale entry before calling
   * {@link PumpkinRegistry#addAll(Object...)}.
   *
   * @param component the component name, matched case-insensitively
   */
  public static synchronized void enable(String component) {
    String key = normalize(component);
    if (!s_disabled.remove(key)) {
      return;
    }
    PumpkinAlert alert = s_alerts.remove(key);
    if (alert != null) {
      alert.set(false);
      alert.close();
    }
  }

  /**
   * Whether a component is switched off.
   *
   * <p>Reads {@code deploy/pumpkin/disabled.txt} on first call, so ordering between
   * {@link #loadDisabledFile()} and {@link PumpkinRegistry#addAll(Object...)} cannot be got wrong.
   *
   * @param component the component name, matched case-insensitively
   * @return true when the component should be dropped to neutral and unregistered
   */
  public static synchronized boolean isDisabled(String component) {
    ensureFileLoaded();
    return s_disabled.contains(normalize(component));
  }

  /**
   * Every currently disabled component name, lower-cased and in the order they were disabled.
   *
   * @return an unmodifiable snapshot
   */
  public static synchronized Set<String> disabledComponents() {
    ensureFileLoaded();
    return Set.copyOf(s_disabled);
  }

  /**
   * Re-reads {@code deploy/pumpkin/disabled.txt}, adding every name in it.
   *
   * <p>Called once from {@link PumpkinLifecycle#init()} and again lazily by the first
   * {@link #isDisabled(String)}, whichever happens first. Explicit re-reads are for a team that
   * pushes a new {@code disabled.txt} and restarts robot code without a full deploy.
   *
   * <p>Never removes a name — a component switched off in code stays off — and never throws.
   */
  public static synchronized void loadDisabledFile() {
    s_fileLoaded = true;
    PersistentStore store = PersistentStore.deploy();
    Optional<String> contents = store.readText(kDisabledFileName);
    if (contents.isEmpty()) {
      Optional<String> error = store.lastError();
      if (error.isPresent()) {
        s_fileNote = "unreadable (" + error.get() + ")";
        Alerts.warning(
                kAlertGroup,
                "Could not read " + store.resolve(kDisabledFileName)
                    + ". Nothing is disabled by file. Reason: " + error.get(),
                org.pumpkinlib.core.alert.MatchImpact.PIT_ONLY)
            .set(true);
      } else {
        s_fileNote = "absent (nothing disabled by file)";
      }
      return;
    }

    List<String> names = parse(contents.get());
    s_fileNote = names.isEmpty()
        ? "present and empty (nothing disabled by file)"
        : "present, " + names.size() + " name(s): " + String.join(", ", names);
    for (String name : names) {
      disable(name);
    }
  }

  /**
   * The kill switch, as a paragraph for the boot dump.
   *
   * @return a multi-line report naming the file, its state, and every disabled component
   */
  public static synchronized String describe() {
    ensureFileLoaded();
    StringBuilder sb = new StringBuilder(256);
    sb.append("PumpkinLib ").append(VERSION).append(System.lineSeparator());
    sb.append("  kill-switch file  ").append(PersistentStore.deploy().resolve(kDisabledFileName))
        .append(System.lineSeparator());
    sb.append("  file state        ").append(s_fileNote).append(System.lineSeparator());
    sb.append("  disabled          ")
        .append(s_disabled.isEmpty() ? "nothing" : String.join(", ", s_disabled))
        .append(System.lineSeparator());
    return sb.toString();
  }

  /** Clears every disabled name and forgets that the file was read. Tests only. */
  public static synchronized void resetForTest() {
    for (PumpkinAlert alert : s_alerts.values()) {
      alert.set(false);
      alert.close();
    }
    s_alerts.clear();
    s_disabled.clear();
    s_fileLoaded = false;
    s_fileNote = "not read yet";
  }

  private static void ensureFileLoaded() {
    if (!s_fileLoaded) {
      loadDisabledFile();
    }
  }

  private static String normalize(String component) {
    Objects.requireNonNull(component, "PumpkinLib: component name must not be null");
    return component.trim().toLowerCase(Locale.ROOT);
  }

  /** Splits the file: one name per line, {@code #} comments, blank lines ignored. */
  private static List<String> parse(String contents) {
    List<String> names = new ArrayList<>();
    for (String raw : contents.split("\\R")) {
      int hash = raw.indexOf('#');
      String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
      if (!line.isEmpty()) {
        names.add(line);
      }
    }
    return names;
  }
}

package org.pumpkinlib.core.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.pumpkinlib.core.PumpkinLib;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.identity.RobotIdentity;

/**
 * Git provenance of the running code — "is the code on this robot what is in git?"
 *
 * <p>The single most expensive unanswerable question in an FRC pit is "which version is on the
 * robot right now?". The {@code gversion} Gradle plugin already answers it: it generates a
 * {@code BuildConstants} class with {@code GIT_SHA}, {@code GIT_BRANCH}, {@code BUILD_DATE},
 * {@code VERSION} and {@code DIRTY} (0 clean, 1 uncommitted, −1 error). Teams generate it and then
 * nothing surfaces it. This class surfaces it.
 *
 * <p><b>Why reflection.</b> {@code BuildConstants} is generated into the <i>team's</i> package
 * ({@code frc.robot}), which a library cannot import, and a vendordep cannot add a Gradle plugin to
 * generate it in the first place. So the class is located reflectively, once, at boot, and the
 * absence of it degrades to {@link Dirty#UNKNOWN} plus an {@code INFO} / {@code PIT_ONLY} alert
 * naming the template as the fix — it never fails to compile and never throws.
 *
 * <p><b>What the rest of the library does with this.</b> {@code PumpkinLifecycle} resolves this
 * before {@code Logger.start()} and pushes {@link #metadata()} through the metadata hook.
 * AdvantageKit's {@code Logger.recordMetadata} is write-once <i>before</i> {@code start()}, so that
 * ordering is a hard constraint, not a convention. The values also publish to
 * {@code /Pumpkin/Meta/}.
 *
 * <p><b>The dirty alert is unconditional.</b> {@code design/06} §7.4 deliberately lets a dirty
 * working tree deploy — a hard gate on "tree is dirty" bricks the robot at 8:40 on Saturday, because
 * the VS Code deploy button cannot pass a {@code -P} property and the student who needs to change
 * one soft limit cannot deploy the fix at all. A {@code WARNING} / {@code PIT_ONLY} alert plus
 * {@code /Pumpkin/Meta/GitDirty} is the entire consideration we get in exchange, so it is raised in
 * the shop as well as at an event. Gating it on FMS attach would mean the shop deploy that actually
 * created the untraceable jar is the one that says nothing.
 *
 * <p>Every accessor is lazy: the first call resolves {@code frc.robot.BuildConstants} if
 * {@link #resolve(String)} has not already run, so nothing depends on boot ordering.
 */
public final class DeployInfo {

  /** The value every accessor reports when {@code BuildConstants} could not be located. */
  public static final String kUnknown = "UNKNOWN";

  /** The class {@code gversion} generates by default in a GradleRIO project. */
  public static final String kDefaultBuildConstantsClass = "frc.robot.BuildConstants";

  /** Whether the deployed working tree was committed. */
  public enum Dirty {
    /** Every change was committed; this build is reproducible from a commit. */
    CLEAN,

    /**
     * The working tree had uncommitted changes. The build still deploys (§7.4), but it cannot be
     * traced to a commit, which is what the persistent {@code WARNING} / {@code PIT_ONLY} alert
     * says.
     */
    DIRTY,

    /**
     * {@code BuildConstants} was not found, or its {@code DIRTY} field was −1. Provenance is
     * unavailable; the fix is the gversion plugin, which the PumpkinLib template ships wired.
     */
    UNKNOWN
  }

  private static final IntSupplier s_halTeamNumber = Platform::teamNumber;

  private static volatile IntSupplier s_teamNumberSource = s_halTeamNumber;
  private static volatile boolean s_resolved;
  private static volatile String s_className = kDefaultBuildConstantsClass;
  private static volatile String s_gitSha = kUnknown;
  private static volatile String s_gitBranch = kUnknown;
  private static volatile String s_gitDate = kUnknown;
  private static volatile String s_buildDate = kUnknown;
  private static volatile String s_projectVersion = kUnknown;
  private static volatile String s_projectName = kUnknown;
  private static volatile Dirty s_dirty = Dirty.UNKNOWN;
  private static volatile String s_resolutionNote = "not resolved yet";

  private DeployInfo() {}

  /**
   * Locates and reads the generated build-constants class.
   *
   * <p>Called by {@code PumpkinRobot} before the logger starts, so that the values land in log
   * metadata. Safe to call more than once; the second call re-reads, which is what a test that
   * points it at a stub class needs.
   *
   * <p><b>Never throws.</b> A missing class, a missing field, a field of an unexpected type and a
   * security manager all resolve to {@link #kUnknown} with the reason recorded in
   * {@link #resolutionNote()}.
   *
   * <p>Declared public rather than package-private (the design sketch showed no modifier) because
   * the caller — {@code PumpkinRobot} / {@code PumpkinLifecycle} — lives in
   * {@code org.pumpkinlib.core}, a different package.
   *
   * @param buildConstantsClassName fully qualified class name, normally
   *     {@value #kDefaultBuildConstantsClass}
   */
  public static void resolve(String buildConstantsClassName) {
    String name =
        (buildConstantsClassName == null || buildConstantsClassName.isBlank())
            ? kDefaultBuildConstantsClass
            : buildConstantsClassName.trim();
    s_className = name;

    // Reset first, so a second resolve() against a missing class does not leave stale values.
    s_gitSha = kUnknown;
    s_gitBranch = kUnknown;
    s_gitDate = kUnknown;
    s_buildDate = kUnknown;
    s_projectVersion = kUnknown;
    s_projectName = kUnknown;
    s_dirty = Dirty.UNKNOWN;

    Class<?> clazz;
    try {
      clazz = Class.forName(name, false, DeployInfo.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
      s_resolutionNote =
          name
              + " was not found on the classpath, so build provenance is unavailable. "
              + "Add the gversion Gradle plugin (the PumpkinLib template ships it wired) — "
              + "see pumpkinlib.dev/install.";
      s_resolved = true;
      return;
    }

    s_gitSha = readString(clazz, "GIT_SHA");
    s_gitBranch = readString(clazz, "GIT_BRANCH");
    s_gitDate = readString(clazz, "GIT_DATE");
    s_buildDate = readString(clazz, "BUILD_DATE");
    s_projectVersion = readString(clazz, "VERSION");
    s_projectName = readString(clazz, "MAVEN_NAME");

    Optional<Integer> dirtyFlag = readInt(clazz, "DIRTY");
    s_dirty =
        dirtyFlag
            .map(flag -> flag == 0 ? Dirty.CLEAN : flag == 1 ? Dirty.DIRTY : Dirty.UNKNOWN)
            .orElse(Dirty.UNKNOWN);

    s_resolutionNote =
        "resolved from "
            + name
            + (s_dirty == Dirty.UNKNOWN
                ? " (its DIRTY field was missing or -1, which gversion writes when git itself"
                    + " failed)"
                : "");
    s_resolved = true;
  }

  /** Locates {@value #kDefaultBuildConstantsClass}. Equivalent to {@code resolve(null)}. */
  public static void resolve() {
    resolve(kDefaultBuildConstantsClass);
  }

  /**
   * Forgets the resolution so the next accessor re-reads. Test-only.
   *
   * @see #resolve(String)
   */
  public static void resetForTest() {
    s_resolved = false;
    s_className = kDefaultBuildConstantsClass;
    s_gitSha = kUnknown;
    s_gitBranch = kUnknown;
    s_gitDate = kUnknown;
    s_buildDate = kUnknown;
    s_projectVersion = kUnknown;
    s_projectName = kUnknown;
    s_dirty = Dirty.UNKNOWN;
    s_resolutionNote = "not resolved yet";
    s_teamNumberSource = s_halTeamNumber;
  }

  /**
   * The commit this code was built from.
   *
   * @return the git SHA exactly as {@code gversion} recorded it, or {@value #kUnknown}
   */
  public static String gitSha() {
    ensureResolved();
    return s_gitSha;
  }

  /**
   * The commit, abbreviated to the seven characters humans actually compare.
   *
   * @return the first seven characters of {@link #gitSha()}, or {@value #kUnknown}
   */
  public static String gitShaShort() {
    String sha = gitSha();
    return sha.length() > 7 && !kUnknown.equals(sha) ? sha.substring(0, 7) : sha;
  }

  /**
   * The branch this code was built from.
   *
   * @return the branch name, or {@value #kUnknown}
   */
  public static String gitBranch() {
    ensureResolved();
    return s_gitBranch;
  }

  /**
   * When the commit was made.
   *
   * @return {@code gversion}'s {@code GIT_DATE}, or {@value #kUnknown}
   */
  public static String gitDate() {
    ensureResolved();
    return s_gitDate;
  }

  /**
   * When the jar on this robot was built.
   *
   * <p>Distinct from {@link #gitDate()} and more useful in a pit: it answers "did my deploy actually
   * land?" even when the commit is old.
   *
   * @return {@code gversion}'s {@code BUILD_DATE}, or {@value #kUnknown}
   */
  public static String buildDate() {
    ensureResolved();
    return s_buildDate;
  }

  /**
   * Whether the deployed working tree was committed.
   *
   * @return {@link Dirty#CLEAN}, {@link Dirty#DIRTY} or {@link Dirty#UNKNOWN}
   */
  public static Dirty dirty() {
    ensureResolved();
    return s_dirty;
  }

  /**
   * The team's own project version, from {@code gversion}.
   *
   * @return the robot project's version string, or {@value #kUnknown}
   */
  public static String projectVersion() {
    ensureResolved();
    return s_projectVersion;
  }

  /**
   * The team's project name, from {@code gversion}'s {@code MAVEN_NAME}.
   *
   * @return the robot project's name, or {@value #kUnknown}
   */
  public static String projectName() {
    ensureResolved();
    return s_projectName;
  }

  /**
   * The PumpkinLib version this robot is running.
   *
   * @return the library version, year-anchored
   */
  public static String pumpkinVersion() {
    return PumpkinLib.VERSION;
  }

  /**
   * The team number this controller is configured for.
   *
   * @return the team number, or empty if the platform could not tell us (0 is not a team)
   */
  public static Optional<Integer> teamNumber() {
    try {
      int team = s_teamNumberSource.getAsInt();
      return team > 0 ? Optional.of(team) : Optional.empty();
    } catch (RuntimeException | LinkageError e) {
      return Optional.empty();
    }
  }

  /**
   * Replaces the team-number read. Test and tooling entry point.
   *
   * <p><b>Why this exists.</b> {@code Platform.teamNumber()} bottoms out in
   * {@code RobotController.getTeamNumber()}, a JNI call. On a machine with no WPILib natives — which
   * is every plain JUnit run — the HAL's static initialiser does not throw something catchable, it
   * calls {@code System.exit(1)} and takes the JVM with it. No {@code try}/{@code catch} can defend
   * against that, so a unit test of {@link #summary()} needs a way not to make the call at all.
   * This is the same shape as {@code Clock.setSource(DoubleSupplier)} in the compat layer, and for
   * the same reason.
   *
   * @param source returns the team number, or a value {@code <= 0} for "unknown"
   * @throws IllegalArgumentException if {@code source} is null; use {@link #useHalTeamNumber()} to
   *     go back to reading the platform
   */
  public static void setTeamNumberSource(IntSupplier source) {
    if (source == null) {
      throw new IllegalArgumentException(
          "DeployInfo.setTeamNumberSource(source): source was null. Call"
              + " DeployInfo.useHalTeamNumber() to restore the platform read.");
    }
    s_teamNumberSource = source;
  }

  /** Restores the default team-number read, {@code Platform.teamNumber()}. */
  public static void useHalTeamNumber() {
    s_teamNumberSource = Platform::teamNumber;
  }

  /**
   * Whether the team-number read has been replaced by {@link #setTeamNumberSource(IntSupplier)}.
   *
   * @return true if a non-default source is installed
   */
  public static boolean isTeamNumberSourceOverridden() {
    return s_teamNumberSource != s_halTeamNumber;
  }

  /**
   * Whether {@code BuildConstants} was located.
   *
   * @return false when provenance is unavailable, which is the condition behind the
   *     {@code INFO} / {@code PIT_ONLY} "add the gversion plugin" alert
   */
  public static boolean isAvailable() {
    ensureResolved();
    return !kUnknown.equals(s_gitSha) || !kUnknown.equals(s_buildDate);
  }

  /**
   * Why the resolution produced what it did.
   *
   * <p>Reads either "resolved from frc.robot.BuildConstants" or the full sentence explaining what
   * was missing and how to fix it. This is the text the {@code UNKNOWN} alert carries.
   *
   * @return a human explanation of the resolution outcome
   */
  public static String resolutionNote() {
    ensureResolved();
    return s_resolutionNote;
  }

  /**
   * One line for the pit display and the log header.
   *
   * <p>For example {@code 8793 comp | main@a1b2c3d (dirty) | pumpkinlib 2026.1.0}. Present on
   * screen without being in the way — the fact that a build is untraceable should be visible the
   * moment somebody looks, not something you have to go and check.
   *
   * @return the one-line summary; never throws, and degrades to naming what is unknown
   */
  public static String summary() {
    ensureResolved();
    StringBuilder sb = new StringBuilder();

    sb.append(teamNumber().map(String::valueOf).orElse("team ?"));
    RobotIdentity.currentIfResolved()
        .ifPresent(id -> sb.append(' ').append(id.name().toLowerCase(Locale.ROOT)));

    sb.append(" | ");
    if (isAvailable()) {
      sb.append(gitBranch()).append('@').append(gitShaShort());
      switch (dirty()) {
        case DIRTY -> sb.append(" (dirty)");
        case UNKNOWN -> sb.append(" (dirty state unknown)");
        case CLEAN -> {
          // Clean is the expected case and gets no annotation; the absence of "(dirty)" is the
          // signal, and a pit display line should carry only what is worth reading.
        }
        default -> { }
      }
    } else {
      sb.append("build provenance UNKNOWN (no gversion)");
    }

    sb.append(" | pumpkinlib ").append(pumpkinVersion());
    return sb.toString();
  }

  /**
   * The key/value pairs {@code PumpkinLifecycle} pushes into AdvantageKit log metadata before
   * {@code Logger.start()}, and publishes under {@code /Pumpkin/Meta/}.
   *
   * <p>Insertion-ordered so the log header reads top to bottom the way a human would write it.
   * Deliberately excludes event and match number: FMS data only arrives when the driver station
   * connects, and {@code recordMetadata} is write-once before {@code start()}, so those are logged
   * as normal signals under {@code /Pumpkin/Match/} instead.
   *
   * @return an ordered, mutable copy of the metadata pairs
   */
  public static Map<String, String> metadata() {
    ensureResolved();
    Map<String, String> out = new LinkedHashMap<>();
    out.put("PumpkinLibVersion", pumpkinVersion());
    out.put("ProjectName", projectName());
    out.put("ProjectVersion", projectVersion());
    out.put("GitSha", gitSha());
    out.put("GitBranch", gitBranch());
    out.put("GitDate", gitDate());
    out.put("GitDirty", dirty().name());
    out.put("BuildDate", buildDate());
    out.put("TeamNumber", teamNumber().map(String::valueOf).orElse(kUnknown));
    out.put("RobotId", RobotIdentity.currentIfResolved().map(Enum::name).orElse(kUnknown));
    out.put("RobotIdSource", RobotIdentity.source());
    return out;
  }

  /**
   * A multi-line description for the boot dump and {@code pumpkin doctor}.
   *
   * @return every resolved value plus the resolution note
   */
  public static String describe() {
    ensureResolved();
    StringBuilder sb = new StringBuilder("DeployInfo: ").append(summary());
    sb.append("\n  source: ").append(resolutionNote());
    for (Map.Entry<String, String> entry : metadata().entrySet()) {
      sb.append("\n  ").append(entry.getKey()).append(" = ").append(entry.getValue());
    }
    if (dirty() == Dirty.DIRTY) {
      sb.append(
          "\n  This code is not in git. It cannot be reproduced from a commit. Commit when you are"
              + " off the field.");
    }
    return sb.toString();
  }

  private static void ensureResolved() {
    if (!s_resolved) {
      synchronized (DeployInfo.class) {
        if (!s_resolved) {
          resolve(s_className);
        }
      }
    }
  }

  private static String readString(Class<?> clazz, String fieldName) {
    Object value = readStatic(clazz, fieldName);
    if (value == null) {
      return kUnknown;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? kUnknown : text;
  }

  private static Optional<Integer> readInt(Class<?> clazz, String fieldName) {
    Object value = readStatic(clazz, fieldName);
    if (value instanceof Number n) {
      return Optional.of(n.intValue());
    }
    if (value instanceof String s) {
      try {
        return Optional.of(Integer.parseInt(s.trim()));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Object readStatic(Class<?> clazz, String fieldName) {
    try {
      Field field = clazz.getField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        return null;
      }
      return field.get(null);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      // A missing field is normal: gversion's field set has changed across WPILib years, and a
      // hand-written stand-in may only carry two of them. Report UNKNOWN for that field, not a
      // failure for the whole class.
      return null;
    }
  }

  /**
   * The names of the {@code BuildConstants} fields this class reads, for diagnostics and tests.
   *
   * @return the field names, in the order they are read
   */
  public static List<String> readFieldNames() {
    List<String> names = new ArrayList<>();
    names.add("GIT_SHA");
    names.add("GIT_BRANCH");
    names.add("GIT_DATE");
    names.add("BUILD_DATE");
    names.add("VERSION");
    names.add("MAVEN_NAME");
    names.add("DIRTY");
    return List.copyOf(names);
  }
}

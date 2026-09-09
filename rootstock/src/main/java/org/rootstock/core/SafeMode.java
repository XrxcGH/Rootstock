package org.rootstock.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.littletonrobotics.junction.Logger;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;

/**
 * The state a misconfigured robot boots into instead of dying in a static initializer.
 *
 * <p><b>The failure this exists to prevent</b> (design/01 §5.6, and a real one — {@code
 * 9143-2025-A-Updated/src/main/java/frc/robot/Constants.java} documents a CAN ID of 64 that stopped
 * robot code from starting, because Phoenix device IDs stop at 62): a value is wrong, a record's
 * compact constructor throws, the field initialiser that built it is a {@code public static final}
 * in {@code RobotConfig}, and what the driver station shows is red "Robot Code" with an
 * {@code ExceptionInInitializerError} whose top frames are JVM class-init machinery. The carefully
 * written error message is a <i>cause</i> nested five frames down, and nobody reads it.
 *
 * <p><b>What happens instead.</b> Validation produces {@link Fault} <i>values</i>. Nothing throws.
 * {@link RootstockRegistry#addAll(Object...)} collects every fault from every registered component,
 * runs the cross-component checks that can only be run once globally, prints all of them together,
 * and — if any is {@link Level#FATAL} — calls {@link #enter(List)}. The robot then:
 *
 * <ul>
 *   <li>completes {@link RootstockLifecycle#init()} and <b>boots</b>; the driver station connects and
 *       the dashboard populates;
 *   <li>runs the {@code CommandScheduler}, telemetry, alerts and {@code describe()} normally;
 *   <li>forces every mechanism to neutral and refuses every command — {@code goTo}, {@code manual},
 *       {@code home} and {@code setVoltage} become named no-ops. <b>Nothing moves.</b>
 *   <li>publishes {@code /Rootstock/Driver/SafeMode = true} and {@code /Rootstock/Driver/SafeModeErrors}
 *       with the full list;
 *   <li>raises one persistent {@link org.rootstock.core.alert.Severity#ERROR} alert naming the
 *       count and the first fault verbatim.
 * </ul>
 *
 * <p>The difference is a five-minute fix in the pits instead of a lost match.
 *
 * <p><b>Why the fault type lives here rather than in {@code org.rootstock.config}.</b> {@code
 * org.rootstock.config.ConfigError} is the config domain's declaration of the same six fields and
 * it is the type a mechanism config produces. Core cannot name it — core is built first and the
 * config package is a later milestone — so {@link Fault} is core's own value shape and
 * {@link RootstockRegistry#addFaultExtractor} is the seam the config domain plugs its {@code
 * ConfigError} list into. One adapter function, no dependency arrow out of core.
 *
 * <p><b>Entering is one-way for the life of the process.</b> There is no {@code exit()}: a config
 * error is a fact about the deployed code, and a robot that could silently leave safe mode is a
 * robot whose safe mode nobody trusts. {@link #resetForTest()} exists for tests and says so in its
 * name.
 */
public final class SafeMode {

  private SafeMode() {}

  /** How bad a fault is, and therefore what it does to the robot. */
  public enum Level {
    /**
     * The robot cannot do the thing the config asks for. Puts the robot in safe mode: it boots and
     * refuses to move.
     */
    FATAL,

    /**
     * Physically implausible but runnable — a cruise velocity above the free-speed estimate, a
     * {@code kG} of zero on a gravity-compensated arm. Raises a persistent alert; the team can still
     * drive.
     */
    WARNING,

    /**
     * A value that is a placeholder rather than a measurement — an untuned gain, a mass nobody has
     * weighed. Reported so it is visible in the boot dump and never silently trusted.
     */
    PLACEHOLDER
  }

  /**
   * One validation failure, as a value.
   *
   * <p>The field set mirrors {@code org.rootstock.config.ConfigError} exactly (design/01 §5.6) so
   * the config domain's adapter is a field-for-field copy with no information lost.
   *
   * @param level how bad it is
   * @param owner the component the value belongs to, named the way the team named it ("Elevator")
   * @param field the field path inside that component ({@code "limits.min"})
   * @param value the offending value, rendered with its unit
   * @param expected the expectation, stated as a range or a rule
   * @param explanation why it is wrong and what to do about it, in English, several sentences if
   *     that is what it takes
   * @param declaredAt where the value was written, ideally {@code File.java:41}; empty when unknown
   */
  public record Fault(
      Level level,
      String owner,
      String field,
      String value,
      String expected,
      String explanation,
      String declaredAt) {

    /** Rendered in place of an unknown declaration site. */
    public static final String kUnknownSite = "";

    /**
     * Canonical constructor; rejects nulls because a null in a printed error message reads as a
     * library bug.
     */
    public Fault {
      Objects.requireNonNull(level, "SafeMode.Fault: level must not be null");
      Objects.requireNonNull(owner, "SafeMode.Fault: owner must not be null");
      Objects.requireNonNull(field, "SafeMode.Fault: field must not be null");
      Objects.requireNonNull(value, "SafeMode.Fault: value must not be null");
      Objects.requireNonNull(expected, "SafeMode.Fault: expected must not be null");
      Objects.requireNonNull(explanation, "SafeMode.Fault: explanation must not be null");
      Objects.requireNonNull(declaredAt, "SafeMode.Fault: declaredAt must not be null");
    }

    /**
     * A fatal fault with no known declaration site.
     *
     * @param owner the component the value belongs to
     * @param field the field path inside that component
     * @param value the offending value, rendered with its unit
     * @param expected the expectation, stated as a range or a rule
     * @param explanation why it is wrong and what to do about it
     * @return the fault
     */
    public static Fault fatal(
        String owner, String field, String value, String expected, String explanation) {
      return new Fault(Level.FATAL, owner, field, value, expected, explanation, kUnknownSite);
    }

    /**
     * A warning fault with no known declaration site.
     *
     * @param owner the component the value belongs to
     * @param field the field path inside that component
     * @param value the offending value, rendered with its unit
     * @param expected the expectation, stated as a range or a rule
     * @param explanation why it is implausible and what to do about it
     * @return the fault
     */
    public static Fault warning(
        String owner, String field, String value, String expected, String explanation) {
      return new Fault(Level.WARNING, owner, field, value, expected, explanation, kUnknownSite);
    }

    /**
     * A copy of this fault carrying a declaration site.
     *
     * @param site where the value was written, ideally {@code File.java:41}
     * @return a new fault; this one is unchanged
     */
    public Fault declaredAt(String site) {
      return new Fault(level, owner, field, value, expected, explanation, site);
    }

    /** @return true when this fault is what puts the robot in safe mode */
    public boolean isFatal() {
      return level == Level.FATAL;
    }

    /**
     * One line, for the alert panel and for the {@code /Rootstock/Driver/SafeModeErrors} array.
     *
     * @return {@code [FATAL] Elevator.reduction = 0.0 (expected > 0)}
     */
    public String summary() {
      return "[" + level + "] " + owner + "." + field + " = " + value + " (expected " + expected
          + ")";
    }

    /**
     * The full multi-line message, in the shape design/01 §5.6 prints.
     *
     * @return the rendered fault, ending without a trailing newline
     */
    public String describe() {
      StringBuilder sb = new StringBuilder(256);
      sb.append("Rootstock config error [").append(level).append("] in \"").append(owner)
          .append("\"").append(System.lineSeparator());
      sb.append(String.format("%n  field    %s%n  value    %s%n  expected %s%n", field, value,
          expected));
      if (!explanation.isEmpty()) {
        sb.append(System.lineSeparator());
        for (String line : explanation.split("\\R")) {
          sb.append("  ").append(line).append(System.lineSeparator());
        }
      }
      if (!declaredAt.isEmpty()) {
        sb.append(System.lineSeparator()).append("  declared at ").append(declaredAt)
            .append(System.lineSeparator());
      }
      return sb.toString();
    }
  }

  /** The NetworkTables/log key the driver dashboard reads to grey itself out. */
  public static final String kSafeModeKey = "/Rootstock/Driver/SafeMode";

  /** The NetworkTables/log key carrying the full rendered fault list. */
  public static final String kSafeModeErrorsKey = "/Rootstock/Driver/SafeModeErrors";

  /** The alert group every safe-mode alert is filed under. */
  public static final String kAlertGroup = "Config";

  private static final List<Fault> s_faults = new ArrayList<>();
  private static volatile boolean s_active;
  private static RootstockAlert s_alert;

  /**
   * Records faults and, if any is {@link Level#FATAL}, enters safe mode.
   *
   * <p>Idempotent and additive: calling it twice with two batches keeps both, and once safe mode is
   * active a later non-fatal batch does not leave it. Called by
   * {@link RootstockRegistry#addAll(Object...)}; a team wiring validation by hand may call it too.
   *
   * @param faults the collected faults; an empty list is legal and does nothing
   */
  public static synchronized void enter(List<Fault> faults) {
    Objects.requireNonNull(faults, "SafeMode.enter: faults must not be null");
    if (faults.isEmpty()) {
      return;
    }
    for (Fault f : faults) {
      s_faults.add(Objects.requireNonNull(f, "SafeMode.enter: no null fault in the list"));
      if (f.isFatal()) {
        s_active = true;
      }
    }
    refreshAlert();
  }

  /**
   * Whether the robot is in safe mode.
   *
   * <p>Every mechanism command factory checks this and returns a named no-op when it is true.
   *
   * @return true when a fatal config fault was collected
   */
  public static boolean isActive() {
    return s_active;
  }

  /**
   * Every fault collected so far, fatal or not, in the order they were collected.
   *
   * @return an unmodifiable snapshot
   */
  public static synchronized List<Fault> faults() {
    return List.copyOf(s_faults);
  }

  /**
   * Only the faults that put the robot in safe mode.
   *
   * @return an unmodifiable snapshot, empty when {@link #isActive()} is false
   */
  public static synchronized List<Fault> fatalFaults() {
    List<Fault> out = new ArrayList<>();
    for (Fault f : s_faults) {
      if (f.isFatal()) {
        out.add(f);
      }
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * The first fatal fault — the one the driver-station alert quotes verbatim.
   *
   * @return the first fatal fault, or empty when the robot is not in safe mode
   */
  public static synchronized Optional<Fault> firstFatal() {
    for (Fault f : s_faults) {
      if (f.isFatal()) {
        return Optional.of(f);
      }
    }
    return Optional.empty();
  }

  /**
   * Every fault rendered as one line, for the log array and the driver dashboard.
   *
   * @return one {@link Fault#summary()} per collected fault
   */
  public static synchronized List<String> toStrings() {
    List<String> out = new ArrayList<>(s_faults.size());
    for (Fault f : s_faults) {
      out.add(f.summary());
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * Publishes {@link #kSafeModeKey} and {@link #kSafeModeErrorsKey}.
   *
   * <p><b>Call only after AdvantageKit's {@code Logger} has started</b> —
   * {@link RootstockLifecycle#init()} and {@link RootstockLifecycle#disabledInit()} do, which is every
   * moment the value can have changed. It is a plain log output rather than a {@code RootstockLog}
   * call because the telemetry package is a later milestone and this key has to exist from the first
   * boot the library ever performs; telemetry takes it over without changing the key.
   */
  public static synchronized void publish() {
    Logger.recordOutput(kSafeModeKey, s_active);
    Logger.recordOutput(kSafeModeErrorsKey, toStrings().toArray(new String[0]));
  }

  /**
   * The whole state, printed at boot and by {@code rootstock doctor}.
   *
   * @return a multi-line report; one line when there is nothing wrong
   */
  public static synchronized String describe() {
    if (s_faults.isEmpty()) {
      return "SafeMode: inactive, 0 faults collected.";
    }
    StringBuilder sb = new StringBuilder(512);
    sb.append("SafeMode: ").append(s_active ? "ACTIVE" : "inactive").append(", ")
        .append(s_faults.size()).append(" fault(s) collected.").append(System.lineSeparator());
    for (Fault f : s_faults) {
      sb.append(System.lineSeparator()).append(f.describe());
    }
    if (s_active) {
      sb.append(System.lineSeparator())
          .append("  The robot has BOOTED so you can read this. Every mechanism is neutral and")
          .append(System.lineSeparator())
          .append("  refuses every command until the fault above is fixed and the code redeployed.")
          .append(System.lineSeparator());
    }
    return sb.toString();
  }

  /** Clears all state. Tests only — a real robot never leaves safe mode. */
  public static synchronized void resetForTest() {
    s_faults.clear();
    s_active = false;
    if (s_alert != null) {
      s_alert.close();
      s_alert = null;
    }
  }

  private static void refreshAlert() {
    if (!s_active) {
      return;
    }
    String first = firstFatal().map(Fault::summary).orElse("(none)");
    String text = fatalFaults().size() + " fatal config error(s). First: " + first;
    if (s_alert == null) {
      s_alert = Alerts.error(kAlertGroup, text, MatchImpact.BLOCKS_MATCH).sticky(true);
    } else {
      s_alert.text(text);
    }
    s_alert.set(true);
  }
}

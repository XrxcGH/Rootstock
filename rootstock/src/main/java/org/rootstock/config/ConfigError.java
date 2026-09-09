package org.rootstock.config;

import java.util.ArrayList;
import java.util.List;
import org.rootstock.core.SafeMode;

/**
 * One wrong configuration value, as a <b>value</b> — never as a thrown exception.
 *
 * <h2>Why this is not a {@code Throwable}</h2>
 *
 * <p>Every example in this library declares its configs as {@code public static final} fields in a
 * {@code RobotConfig} class. A throw from a record's compact constructor therefore surfaces as
 * {@code ExceptionInInitializerError} out of {@code frc.robot.RobotConfig.<clinit>}: robot code
 * never starts, the driver station shows red "Robot Code" and nothing else, and the carefully
 * written message below becomes a <i>cause</i> buried under three frames of JVM class-initialisation
 * machinery that nobody reads at 11pm in a pit. That happened for real —
 * {@code 9143-2025-A-Updated}'s {@code Constants.java} documents a Phoenix CAN ID of 64 that stopped
 * robot code from starting, because Phoenix device IDs stop at 62.
 *
 * <p>So validation <b>collects</b> these instead. {@link Validation#localChecks} returns them,
 * every config stores its own list, {@code RootstockRegistry.addAll(...)} gathers all of them plus the
 * cross-config checks, prints them together once, and — if any is {@link Severity#FATAL} — puts the
 * robot into {@link SafeMode}. The robot boots, connects, populates the dashboard, and refuses to
 * move while telling you exactly why.
 *
 * <h2>What a good message contains</h2>
 *
 * <p>Four things, in this order, because that is the order a student reads them in: <b>what</b>
 * field, <b>what value</b> you gave it, <b>what was expected</b>, and <b>what to type instead</b>.
 * A message that stops at "invalid reduction" has failed. The {@code explanation} is allowed to be
 * several sentences and is allowed to name a competing possibility ("these look swapped"), because
 * the reader is fourteen and has six minutes.
 *
 * @param severity how bad it is, and therefore what it does to the robot
 * @param owner the mechanism the value belongs to, named the way the team named it
 *     ({@code "Elevator"})
 * @param field the field path inside that config ({@code "limits.min"},
 *     {@code "motors.leader.canId"})
 * @param value the offending value rendered with its unit, or blank when the problem is not a
 *     single value
 * @param expected the expectation stated as a range or a rule, or blank when the explanation
 *     carries it
 * @param explanation why it is wrong and what to type instead, in English
 * @param declaredAt where the value was written, ideally {@code RobotConfig.java:41}; blank when
 *     unknown
 */
public record ConfigError(
    Severity severity,
    String owner,
    String field,
    String value,
    String expected,
    String explanation,
    String declaredAt) {

  /** How bad a configuration problem is, and therefore what the library does about it. */
  public enum Severity {
    /**
     * Tier 1 — structurally impossible. A zero reduction, swapped soft limits, a CAN ID of 64, a
     * feedback ratio that contradicts the gearbox. The robot boots into {@link SafeMode} and
     * refuses every command.
     */
    FATAL,

    /**
     * Tier 2 — physically implausible but runnable. A cruise velocity above the free-speed
     * estimate, {@code kG = 0} on a gravity-compensated arm, a tolerance narrower than one loop
     * step. Raises a persistent alert; the team can still drive.
     */
    WARNING,

    /**
     * Tier 3 — a placeholder rather than a measurement. {@code Gains.UNTUNED}, a 1:1 reduction that
     * is probably an unfilled blank, a sim mass nobody weighed. Becomes a line in the boot-time
     * first-setup checklist so it is visible instead of silently trusted.
     */
    PLACEHOLDER;

    /**
     * The checklist verb {@code design/01} §5.6 tier 3 prints, mirroring the {@code ADD} /
     * {@code VERIFY} / {@code TUNE} convention that {@code 0000-XXXX-Robot-Template}'s
     * {@code Constants.java} carries as comments.
     *
     * @return {@code "FIX"}, {@code "CHECK"} or {@code "VERIFY"}
     */
    public String verb() {
      return switch (this) {
        case FATAL -> "FIX";
        case WARNING -> "CHECK";
        case PLACEHOLDER -> "VERIFY";
      };
    }

    /**
     * The {@link SafeMode.Level} this severity maps onto, field for field.
     *
     * @return the matching level
     */
    public SafeMode.Level toLevel() {
      return switch (this) {
        case FATAL -> SafeMode.Level.FATAL;
        case WARNING -> SafeMode.Level.WARNING;
        case PLACEHOLDER -> SafeMode.Level.PLACEHOLDER;
      };
    }
  }

  /** Rendered in place of an unknown declaration site. */
  public static final String kUnknownSite = "";

  /** The {@code value} and {@code expected} placeholder for a problem that is not a single value. */
  public static final String kNotASingleValue = "";

  /** What {@link #toFault()} substitutes for a blank {@code value}, so the fault never prints "". */
  public static final String kNoValueRendered = "(see the explanation)";

  /** What {@link #toFault()} substitutes for a blank {@code expected}. */
  public static final String kNoExpectationRendered = "(see the explanation)";

  /**
   * Canonical constructor. Normalises nulls to blanks instead of throwing.
   *
   * <p>An error object is the thing that reports the problem; if constructing it can itself throw,
   * a library bug turns into the dead-robot failure this whole type exists to prevent.
   */
  public ConfigError {
    severity = severity == null ? Severity.FATAL : severity;
    owner = blank(owner) ? "(unnamed mechanism)" : owner.trim();
    field = blank(field) ? "(unnamed field)" : field.trim();
    value = value == null ? kNotASingleValue : value.trim();
    expected = expected == null ? kNotASingleValue : expected.trim();
    explanation = explanation == null ? "" : explanation.strip();
    declaredAt = declaredAt == null ? kUnknownSite : declaredAt.trim();
  }

  /**
   * A tier-1 error: the robot will boot into safe mode and refuse to move.
   *
   * @param owner the mechanism
   * @param field the field path
   * @param value the offending value with its unit
   * @param expected the rule that was broken
   * @param explanation why, and what to type instead
   * @return the error
   */
  public static ConfigError fatal(
      String owner, String field, String value, String expected, String explanation) {
    return new ConfigError(Severity.FATAL, owner, field, value, expected, explanation, kUnknownSite);
  }

  /**
   * A tier-2 error: an alert and a log line; the team can still drive.
   *
   * @param owner the mechanism
   * @param field the field path
   * @param value the offending value with its unit
   * @param expected the plausible range
   * @param explanation why it is implausible, and what to do
   * @return the error
   */
  public static ConfigError warning(
      String owner, String field, String value, String expected, String explanation) {
    return new ConfigError(
        Severity.WARNING, owner, field, value, expected, explanation, kUnknownSite);
  }

  /**
   * A tier-3 entry: a value that is a placeholder rather than a measurement.
   *
   * @param owner the mechanism
   * @param field the field path
   * @param value what the placeholder currently reads
   * @param expected what a real value would look like
   * @param explanation how to obtain the real value
   * @return the error
   */
  public static ConfigError placeholder(
      String owner, String field, String value, String expected, String explanation) {
    return new ConfigError(
        Severity.PLACEHOLDER, owner, field, value, expected, explanation, kUnknownSite);
  }

  /**
   * An error whose whole content is one already-complete sentence.
   *
   * <p>This is the lift for the {@code problems()} lists the leaf spec records return: those
   * strings already name the value, the rule and the fix, so re-splitting them into three columns
   * would only lose information.
   *
   * @param severity how bad it is
   * @param owner the mechanism
   * @param field the field path
   * @param explanation the complete message
   * @return the error
   */
  public static ConfigError of(Severity severity, String owner, String field, String explanation) {
    return new ConfigError(
        severity, owner, field, kNotASingleValue, kNotASingleValue, explanation, kUnknownSite);
  }

  /**
   * A copy of this error carrying a source location.
   *
   * @param site for example {@code "RobotConfig.java:41"}
   * @return a new error; this one is unchanged
   */
  public ConfigError declaredAt(String site) {
    return new ConfigError(severity, owner, field, value, expected, explanation, site);
  }

  /**
   * A copy of this error attributed to a different owner.
   *
   * <p>Used when a nested spec reports a problem in its own voice and the enclosing config knows
   * the mechanism name the team actually wrote.
   *
   * @param newOwner the mechanism name to attribute it to
   * @return a new error; this one is unchanged
   */
  public ConfigError ownedBy(String newOwner) {
    return new ConfigError(severity, newOwner, field, value, expected, explanation, declaredAt);
  }

  /**
   * Whether this is what puts the robot into safe mode.
   *
   * @return true when the severity is {@link Severity#FATAL}
   */
  public boolean isFatal() {
    return severity == Severity.FATAL;
  }

  /**
   * One line, for an alert title, a checklist row or the {@code /Rootstock/Config/Errors} array.
   *
   * @return for example {@code [FATAL] Elevator.reduction = 0.0 (expected > 0)}
   */
  public String summary() {
    StringBuilder sb = new StringBuilder(96);
    sb.append('[').append(severity).append("] ").append(owner).append('.').append(field);
    if (!value.isEmpty()) {
      sb.append(" = ").append(value);
    }
    if (!expected.isEmpty()) {
      sb.append(" (expected ").append(expected).append(')');
    }
    if (value.isEmpty() && expected.isEmpty() && !explanation.isEmpty()) {
      sb.append(": ").append(firstSentence(explanation));
    }
    return sb.toString();
  }

  /**
   * The full multi-line message, in the shape {@code design/01} §5.6 prints to the riolog.
   *
   * @return the rendered error, newline-terminated
   */
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(384);
    sb.append("org.rootstock.config.ConfigError [")
        .append(severity)
        .append("]: Rootstock config error in \"")
        .append(owner)
        .append('"')
        .append(nl)
        .append(nl);
    sb.append("  field    ").append(field).append(nl);
    if (!value.isEmpty()) {
      sb.append("  value    ").append(value).append(nl);
    }
    if (!expected.isEmpty()) {
      sb.append("  expected ").append(expected).append(nl);
    }
    if (!explanation.isEmpty()) {
      sb.append(nl);
      for (String line : explanation.split("\\R")) {
        sb.append("  ").append(line).append(nl);
      }
    }
    if (!declaredAt.isEmpty()) {
      sb.append(nl).append("  declared at ").append(declaredAt).append(nl);
    }
    return sb.toString();
  }

  /**
   * This error as the core-tier {@link SafeMode.Fault} that {@code RootstockRegistry} understands.
   *
   * <p>Field for field, with no information lost: {@code SafeMode.Fault} was given exactly this
   * shape so that {@code org.rootstock.core} never has to name a config type, and so this adapter
   * is a copy rather than a translation.
   *
   * @return the equivalent fault
   */
  public SafeMode.Fault toFault() {
    return new SafeMode.Fault(
        severity.toLevel(),
        owner,
        field,
        value.isEmpty() ? kNoValueRendered : value,
        expected.isEmpty() ? kNoExpectationRendered : expected,
        explanation,
        declaredAt);
  }

  /**
   * The reverse adapter, so a fault raised by a non-config component can be reported alongside
   * config errors in one list.
   *
   * @param fault the core-tier fault
   * @return the equivalent config error
   */
  public static ConfigError fromFault(SafeMode.Fault fault) {
    Severity severity =
        switch (fault.level()) {
          case FATAL -> Severity.FATAL;
          case WARNING -> Severity.WARNING;
          case PLACEHOLDER -> Severity.PLACEHOLDER;
        };
    return new ConfigError(
        severity,
        fault.owner(),
        fault.field(),
        fault.value(),
        fault.expected(),
        fault.explanation(),
        fault.declaredAt());
  }

  /**
   * Bulk conversion for {@code RootstockRegistry}'s fault extractor.
   *
   * @param errors the errors, never null
   * @return the equivalent faults, in the same order
   */
  public static List<SafeMode.Fault> toFaults(List<ConfigError> errors) {
    if (errors == null || errors.isEmpty()) {
      return List.of();
    }
    List<SafeMode.Fault> out = new ArrayList<>(errors.size());
    for (ConfigError error : errors) {
      out.add(error.toFault());
    }
    return List.copyOf(out);
  }

  @Override
  public String toString() {
    return summary();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String firstSentence(String text) {
    int stop = text.indexOf(". ");
    return stop < 0 ? text : text.substring(0, stop + 1);
  }
}

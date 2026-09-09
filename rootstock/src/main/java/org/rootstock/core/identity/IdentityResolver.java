package org.rootstock.core.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The pure, HAL-free resolution engine behind {@link RobotIdentity}.
 *
 * <p>This class exists as a separate type for exactly one reason: <b>identity resolution is the
 * single piece of boot-time logic most likely to be wrong on a robot you cannot reach</b>, and a
 * static facade that reads {@code Platform} directly cannot be unit tested. Everything the
 * resolution depends on arrives as an {@link Inputs} record, so a test can assert "a robot whose
 * comments field says {@code Practice Bot 2} and whose serial matches the comp table resolves to
 * PRACTICE, because comments outrank serial" without an HAL, a roboRIO, or a filesystem.
 *
 * <p><b>Why the obvious answer is wrong.</b> The tempting mechanism is a {@code robot-id.txt} in the
 * deploy directory. It does not work: the deploy directory is deployed <i>from the laptop</i>, so
 * every robot receives the same file and the identity is a property of the laptop, not the robot.
 * Identity has to live on the robot. See {@code design/06} §7.1.
 *
 * <p><b>Resolution order — first match wins.</b> Each step exists to cover a failure of the one
 * before it:
 *
 * <ol>
 *   <li><b>Persistent file</b> — {@code Platform.persistentDir()/rootstock/robot-id}, on the robot's
 *       own filesystem. Survives redeploy, wiped by a reimage. Rootstock writes it for you
 *       ({@link RobotIdentity#assignCommand}) so nobody ever SSHs into a robot. Primary,
 *       specifically because it is the only mechanism guaranteed to exist on SystemCore.
 *   <li><b>Comments</b> — the roboRIO web-dashboard comments field, matched case-insensitively
 *       against registered substrings. Student-editable through a web page: no code, no SSH.
 *   <li><b>Serial number</b> — exact match against a registered table. Zero setup once recorded;
 *       the string is in the log the first time you boot.
 *   <li><b>Simulation</b> — {@code Platform.isSimulation()} maps to the declared sim identity.
 *   <li><b>Declared fallback</b> — plus an alert naming every strategy that was tried and what it
 *       saw. A robot running an unknown identity's constants is running the wrong gearing and the
 *       wrong limits, and that genuinely means do not take the field.
 * </ol>
 *
 * <p>There is deliberately no DIO-jumper strategy: it consumes a channel, it can fall out, and
 * digital-IO is on the 2027 removal list.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class IdentityResolver {

  /** Name of the strategy that reads the on-robot persistent file. */
  public static final String kPersistentFileStrategy = "persistent file";

  /** Name of the strategy that reads the roboRIO web-dashboard comments field. */
  public static final String kCommentStrategy = "comments";

  /** Name of the strategy that matches the controller serial number. */
  public static final String kSerialStrategy = "serial number";

  /** Name of the strategy that maps simulation onto a declared identity. */
  public static final String kSimulationStrategy = "simulation";

  /** Name of the last-resort strategy. */
  public static final String kFallbackStrategy = "declared fallback";

  /**
   * A registered "the comments field contains this substring" rule.
   *
   * @param substring the substring to look for, matched case-insensitively; never empty
   * @param id the identity to resolve to when it matches
   */
  public record CommentRule(String substring, RobotId id) {

    /** Canonical constructor. */
    public CommentRule {
      if (substring == null || substring.isBlank()) {
        throw new IllegalArgumentException(
            "RobotIdentity.byComment(substring, id): substring was "
                + (substring == null ? "null" : "blank")
                + ". It must be a non-blank piece of the roboRIO comments field, for example "
                + "byComment(\"practice\", RobotId.PRACTICE).");
      }
      if (id == null) {
        throw new IllegalArgumentException(
            "RobotIdentity.byComment(\"" + substring + "\", id): id was null.");
      }
    }

    /**
     * Whether this rule matches the given comments text.
     *
     * @param comments the raw comments field
     * @return true if the comments contain this rule's substring, case-insensitively
     */
    public boolean matches(String comments) {
      return comments != null
          && comments.toLowerCase(Locale.ROOT).contains(substring.toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Everything the resolution reads, gathered by the caller.
   *
   * <p>All four come from {@code org.rootstock.core.compat.Platform} on a real robot. They are
   * parameters rather than direct reads so that this class contains no {@code edu.wpi.first}
   * import at all: when {@code getComments()} disappears on SystemCore, strategy 2 becomes
   * {@link Optional#empty()} in one file and the chain degrades to strategy 1 with a named alert —
   * it does not fail to compile.
   *
   * @param persistentFileContents contents of {@code persistentDir()/rootstock/robot-id}, if the file
   *     exists and could be read; not trimmed by the caller — the resolver trims
   * @param comments the roboRIO web-dashboard comments field, if readable on this platform
   * @param serialNumber the controller serial number, if readable on this platform
   * @param simulation whether this code is running in simulation
   */
  public record Inputs(
      Optional<String> persistentFileContents,
      Optional<String> comments,
      Optional<String> serialNumber,
      boolean simulation) {

    /** Canonical constructor. */
    public Inputs {
      if (persistentFileContents == null || comments == null || serialNumber == null) {
        throw new IllegalArgumentException(
            "IdentityResolver.Inputs: no component may be null; use Optional.empty() for "
                + "\"this platform cannot tell me\".");
      }
    }

    /**
     * Inputs describing a machine where nothing at all is readable.
     *
     * @param simulation whether this is simulation
     * @return inputs with all three optional reads empty
     */
    public static Inputs blank(boolean simulation) {
      return new Inputs(Optional.empty(), Optional.empty(), Optional.empty(), simulation);
    }
  }

  /**
   * One strategy that was tried, and what it saw.
   *
   * <p>The point of recording every attempt — not just the winner — is the fallback alert. "Nothing
   * matched, so I am pretending to be the comp bot" is useless; "the persistent file was absent,
   * the comments field said {@code Kitbot} which matches no registered substring, the serial
   * {@code 03264cf1} is not in the table of 2, and this is not simulation" tells a student exactly
   * which of the four things to go fix.
   *
   * @param strategy which strategy ran, one of the {@code k*Strategy} constants
   * @param saw a human sentence describing what the strategy observed
   * @param matched the identity it produced, or empty if it did not match
   */
  public record Attempt(String strategy, String saw, Optional<RobotId> matched) {

    /**
     * Renders this attempt as one line of the fallback alert.
     *
     * @return for example {@code comments: "Kitbot" matches none of ["practice", "proto"]}
     */
    public String describe() {
      return strategy + ": " + saw + matched.map(id -> " -> " + id).orElse("");
    }
  }

  /**
   * The outcome of a resolution.
   *
   * @param id the resolved identity; never null, because the fallback always produces one
   * @param source a one-line human description of how it was resolved, published to
   *     {@code /Rootstock/Meta/RobotIdSource} and shown in the pit
   * @param usedFallback true when nothing matched and the declared fallback was used — the
   *     condition that raises the {@code ERROR} / {@code BLOCKS_MATCH} alert
   * @param attempts every strategy that ran, in order, including the one that won
   */
  public record Resolution(
      RobotId id, String source, boolean usedFallback, List<Attempt> attempts) {

    /** Canonical constructor; defensively copies {@code attempts}. */
    public Resolution {
      attempts = List.copyOf(attempts);
    }

    /**
     * The full multi-line explanation, suitable for the fallback alert and for {@code describe()}.
     *
     * @return every strategy that was tried and what it saw, one per line
     */
    public String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("RobotIdentity resolved to ").append(id).append(" via ").append(source);
      if (usedFallback) {
        sb.append("\n  NOTHING MATCHED. This robot is running ")
            .append(id)
            .append("'s constants because that is the declared fallback, which means it may be")
            .append("\n  running the wrong gearing, the wrong soft limits and the wrong offsets.");
      }
      sb.append("\n  strategies tried:");
      for (Attempt a : attempts) {
        sb.append("\n    - ").append(a.describe());
      }
      if (usedFallback) {
        sb.append(
            "\n  Fix: bind RobotIdentity.assignCommand(<id>) to a pit button and press it once on"
                + " this robot,\n       or set the roboRIO comments field, or add this serial to"
                + " RobotIds.register().");
      }
      return sb.toString();
    }
  }

  private final boolean m_persistentFileEnabled;
  private final Map<String, RobotId> m_bySerial;
  private final List<CommentRule> m_byComment;
  private final RobotId m_simId;
  private final RobotId m_fallback;

  /**
   * Creates a resolver from a declared strategy table.
   *
   * <p>Normally built by {@link RobotIdentity.Builder}; the constructor is public so tests and
   * tooling can build one directly.
   *
   * @param persistentFileEnabled whether strategy 1 runs at all
   * @param bySerial exact serial-number matches; iteration order is preserved in messages
   * @param byComment substring rules, tried in declaration order
   * @param simId the identity used when {@link Inputs#simulation()} is true
   * @param fallback the identity used when nothing matched
   * @throws IllegalArgumentException if {@code simId} or {@code fallback} is null
   */
  public IdentityResolver(
      boolean persistentFileEnabled,
      Map<String, RobotId> bySerial,
      List<CommentRule> byComment,
      RobotId simId,
      RobotId fallback) {
    if (simId == null) {
      throw new IllegalArgumentException(
          "IdentityResolver: simId was null. Use RobotIdentity.configure().simIs(RobotId.SIM).");
    }
    if (fallback == null) {
      throw new IllegalArgumentException(
          "IdentityResolver: fallback was null. Use RobotIdentity.configure()"
              + ".fallback(RobotId.COMP). A mystery robot should behave like the comp bot.");
    }
    m_persistentFileEnabled = persistentFileEnabled;
    m_bySerial = new LinkedHashMap<>(bySerial == null ? Map.of() : bySerial);
    m_byComment = byComment == null ? List.of() : List.copyOf(byComment);
    m_simId = simId;
    m_fallback = fallback;
  }

  /**
   * Runs the five strategies in order and returns the first match.
   *
   * <p>Never throws and never returns null: an unresolvable robot resolves to the declared
   * fallback with {@link Resolution#usedFallback()} true. A boot-time exception here would mean a
   * robot that will not start over a constant-selection question, which is strictly worse than a
   * robot that starts and says loudly that it does not know what it is.
   *
   * @param inputs what this platform can see
   * @return the resolution, including every strategy that was tried
   */
  public Resolution resolve(Inputs inputs) {
    List<Attempt> attempts = new ArrayList<>();

    // ---- 1) persistent file: the only mechanism guaranteed to exist on SystemCore ----------
    if (m_persistentFileEnabled) {
      Optional<String> raw = inputs.persistentFileContents().map(String::trim);
      if (raw.isEmpty()) {
        attempts.add(new Attempt(kPersistentFileStrategy, "absent", Optional.empty()));
      } else {
        String text = raw.get();
        Optional<RobotId> parsed = parseId(text);
        if (parsed.isPresent()) {
          Attempt a =
              new Attempt(kPersistentFileStrategy, "contains \"" + text + "\"", parsed);
          attempts.add(a);
          return new Resolution(parsed.get(), a.describe(), false, attempts);
        }
        attempts.add(
            new Attempt(
                kPersistentFileStrategy,
                "contains \"" + text + "\", which is not a RobotId constant",
                Optional.empty()));
      }
    } else {
      attempts.add(new Attempt(kPersistentFileStrategy, "disabled by byPersistentFile(false)",
          Optional.empty()));
    }

    // ---- 2) comments: student-editable through a web page, no code, no SSH -----------------
    Optional<String> comments = inputs.comments().map(String::trim).filter(s -> !s.isEmpty());
    if (comments.isEmpty()) {
      attempts.add(new Attempt(kCommentStrategy, "empty or unreadable on this platform",
          Optional.empty()));
    } else if (m_byComment.isEmpty()) {
      attempts.add(
          new Attempt(
              kCommentStrategy,
              "\"" + comments.get() + "\", but no byComment(...) rules were registered",
              Optional.empty()));
    } else {
      for (CommentRule rule : m_byComment) {
        if (rule.matches(comments.get())) {
          Attempt a =
              new Attempt(
                  kCommentStrategy,
                  "\"" + comments.get() + "\" contains \"" + rule.substring() + "\"",
                  Optional.of(rule.id()));
          attempts.add(a);
          return new Resolution(rule.id(), a.describe(), false, attempts);
        }
      }
      attempts.add(
          new Attempt(
              kCommentStrategy,
              "\"" + comments.get() + "\" matches none of " + registeredSubstrings(),
              Optional.empty()));
    }

    // ---- 3) serial number: zero setup once recorded ----------------------------------------
    Optional<String> serial = inputs.serialNumber().map(String::trim).filter(s -> !s.isEmpty());
    if (serial.isEmpty()) {
      attempts.add(new Attempt(kSerialStrategy, "unreadable on this platform", Optional.empty()));
    } else {
      RobotId hit = m_bySerial.get(serial.get());
      if (hit != null) {
        Attempt a =
            new Attempt(kSerialStrategy, "\"" + serial.get() + "\"", Optional.of(hit));
        attempts.add(a);
        return new Resolution(hit, a.describe(), false, attempts);
      }
      attempts.add(
          new Attempt(
              kSerialStrategy,
              "\""
                  + serial.get()
                  + "\" is not in the table of "
                  + m_bySerial.size()
                  + " "
                  + m_bySerial.keySet(),
              Optional.empty()));
    }

    // ---- 4) simulation ----------------------------------------------------------------------
    if (inputs.simulation()) {
      Attempt a = new Attempt(kSimulationStrategy, "running in simulation", Optional.of(m_simId));
      attempts.add(a);
      return new Resolution(m_simId, a.describe(), false, attempts);
    }
    attempts.add(new Attempt(kSimulationStrategy, "running on hardware", Optional.empty()));

    // ---- 5) fallback, plus the alert --------------------------------------------------------
    Attempt a =
        new Attempt(
            kFallbackStrategy, "nothing above matched", Optional.of(m_fallback));
    attempts.add(a);
    return new Resolution(m_fallback, a.describe(), true, attempts);
  }

  /**
   * The identity used when running in simulation.
   *
   * @return the declared sim identity
   */
  public RobotId simId() {
    return m_simId;
  }

  /**
   * The identity used when no strategy matched.
   *
   * @return the declared fallback identity
   */
  public RobotId fallback() {
    return m_fallback;
  }

  /**
   * Whether strategy 1 (the on-robot persistent file) is enabled.
   *
   * @return true unless {@code byPersistentFile(false)} was declared
   */
  public boolean persistentFileEnabled() {
    return m_persistentFileEnabled;
  }

  /**
   * The registered serial-number table.
   *
   * @return an unmodifiable view, in declaration order
   */
  public Map<String, RobotId> bySerial() {
    return Map.copyOf(m_bySerial);
  }

  /**
   * The registered comments-substring rules.
   *
   * @return an unmodifiable view, in declaration order
   */
  public List<CommentRule> byComment() {
    return m_byComment;
  }

  /**
   * A human summary of the declared strategy table, for {@code rootstock doctor} and the boot dump.
   *
   * @return a multi-line description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder("RobotIdentity strategies, in order:");
    sb.append("\n  1. persistent file  ")
        .append(m_persistentFileEnabled ? "enabled (rootstock/robot-id)" : "DISABLED");
    sb.append("\n  2. comments         ").append(registeredSubstrings());
    sb.append("\n  3. serial number    ").append(m_bySerial);
    sb.append("\n  4. simulation       -> ").append(m_simId);
    sb.append("\n  5. fallback         -> ").append(m_fallback);
    return sb.toString();
  }

  /**
   * Parses a {@link RobotId} constant name, case- and whitespace-insensitively.
   *
   * @param text the text to parse
   * @return the matching constant, or empty if the text names no constant
   */
  public static Optional<RobotId> parseId(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String cleaned = text.trim();
    for (RobotId candidate : RobotId.values()) {
      if (candidate.name().equalsIgnoreCase(cleaned)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private List<String> registeredSubstrings() {
    List<String> out = new ArrayList<>(m_byComment.size());
    for (CommentRule rule : m_byComment) {
      out.add(rule.substring());
    }
    return out;
  }
}

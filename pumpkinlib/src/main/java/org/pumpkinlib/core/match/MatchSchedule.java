package org.pumpkinlib.core.match;

import edu.wpi.first.wpilibj.DriverStation.MatchType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.pumpkinlib.core.compat.Platform;

/**
 * The offline half of "what match am I in next?".
 *
 * <p><b>NO network, NO HTTP dependency, ever.</b> During a match the robot is on an isolated FMS
 * field network and cannot reach the internet; an HTTP client in robot code is dead weight at best
 * and a blocking call in a 20 ms periodic at worst. The schedule is produced <i>before</i> the event
 * by a desktop tool and deployed as a file. This class reads that file and does nothing else.
 *
 * <p>Reads {@code deploy/pumpkin/schedule.json} if present, and silently no-ops if absent — a team
 * that never runs the desktop tool notices nothing.
 *
 * <p><b>The file format</b>, defined here because no other document defines it:
 *
 * <pre>{@code
 * {
 *   "event": "CURIE",
 *   "matches": [
 *     { "type": "Qualification", "number": 12, "replay": 1 },
 *     { "type": "Qualification", "number": 34, "replay": 1 }
 *   ]
 * }
 * }</pre>
 *
 * {@code type} accepts any {@link MatchType} name case-insensitively, plus the shorthands
 * {@code "q"}, {@code "p"} and {@code "e"}. {@code replay} defaults to 1. Anything unparseable is
 * skipped rather than thrown — a malformed schedule must never take a robot down.
 */
public final class MatchSchedule {

  private MatchSchedule() {}

  /** Relative to {@link Platform#deployDir()}. */
  private static final String kScheduleRelative = "pumpkin/schedule.json";

  private static volatile List<MatchInfo> s_matches = List.of();

  private static volatile boolean s_loaded;

  /**
   * Loads the deployed schedule. Called by {@code PumpkinRobot}; silently no-ops if absent.
   *
   * <p>Idempotent and never throws. Calling it twice reloads, which is what a pit workflow that
   * pushes a corrected schedule over FTP wants.
   */
  public static void loadFromDeploy() {
    List<MatchInfo> parsed = List.of();
    try {
      Path file = Platform.deployDir().resolve(kScheduleRelative);
      if (Files.isReadable(file)) {
        parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (RuntimeException | java.io.IOException e) {
      // "No information", never a failure. A schedule is a convenience; the robot drives without it.
      parsed = List.of();
    }
    s_matches = parsed;
    s_loaded = true;
  }

  /**
   * The next match on the deployed schedule.
   *
   * <p>"Next" means the first entry strictly after the match the FMS currently reports. Off the
   * field, where {@link MatchContext#match()} is empty, that is simply the first entry — which is
   * the useful answer in the pit the morning of the event.
   *
   * @return the next scheduled match, or empty when no schedule is deployed or the list is finished
   */
  public static Optional<MatchInfo> nextScheduled() {
    if (!s_loaded) {
      loadFromDeploy();
    }
    List<MatchInfo> matches = s_matches;
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    Optional<MatchInfo> current = MatchContext.match();
    if (current.isEmpty() || !current.get().isKnown()) {
      return Optional.of(matches.get(0));
    }
    MatchInfo now = current.get();
    for (int i = 0; i < matches.size(); i++) {
      MatchInfo m = matches.get(i);
      if (m.matchType() == now.matchType() && m.matchNumber() == now.matchNumber()) {
        return i + 1 < matches.size() ? Optional.of(matches.get(i + 1)) : Optional.empty();
      }
    }
    // Current match is not on the schedule (a replay, or a stale file). The first entry that is
    // numerically later is the best available answer.
    for (int i = 0; i < matches.size(); i++) {
      MatchInfo m = matches.get(i);
      if (m.matchType() == now.matchType() && m.matchNumber() > now.matchNumber()) {
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }

  /**
   * Every entry in the deployed schedule, in file order.
   *
   * @return an immutable list; empty when no schedule is deployed
   */
  public static List<MatchInfo> all() {
    if (!s_loaded) {
      loadFromDeploy();
    }
    return s_matches;
  }

  /** Clears the loaded schedule so a test can install a different one. For tests only. */
  public static void resetForTest() {
    s_matches = List.of();
    s_loaded = false;
  }

  // -------------------------------------------------------------------------------------------
  // Parsing.
  //
  // Hand-rolled rather than Jackson, deliberately: the schema is three scalars per entry, this runs
  // once at boot, and a hand-rolled reader degrades to "no schedule" on a truncated file where a
  // strict parser throws. Robustness beats generality for a file a student edits in a pit.
  // -------------------------------------------------------------------------------------------

  private static List<MatchInfo> parse(String json) {
    String event = extractString(json, "event");
    List<MatchInfo> out = new ArrayList<>();
    int arrayStart = json.indexOf("\"matches\"");
    if (arrayStart < 0) {
      return List.of();
    }
    int cursor = json.indexOf('[', arrayStart);
    if (cursor < 0) {
      return List.of();
    }
    int end = json.indexOf(']', cursor);
    if (end < 0) {
      end = json.length();
    }
    String body = json.substring(cursor + 1, end);

    int from = 0;
    while (true) {
      int open = body.indexOf('{', from);
      if (open < 0) {
        break;
      }
      int close = body.indexOf('}', open);
      if (close < 0) {
        break;
      }
      String entry = body.substring(open + 1, close);
      MatchType type = parseType(extractString(entry, "type"));
      int number = extractInt(entry, "number", 0);
      int replay = extractInt(entry, "replay", 1);
      if (number >= 1) {
        out.add(new MatchInfo(event, type, number, replay));
      }
      from = close + 1;
    }
    return List.copyOf(out);
  }

  private static MatchType parseType(String raw) {
    String t = raw.trim().toLowerCase(Locale.ROOT);
    return switch (t) {
      case "q", "qual", "quals", "qualification" -> MatchType.Qualification;
      case "p", "practice" -> MatchType.Practice;
      case "e", "elim", "elims", "elimination", "playoff", "playoffs" -> MatchType.Elimination;
      default -> MatchType.None;
    };
  }

  private static String extractString(String json, String key) {
    int k = json.indexOf('"' + key + '"');
    if (k < 0) {
      return "";
    }
    int colon = json.indexOf(':', k);
    if (colon < 0) {
      return "";
    }
    int open = json.indexOf('"', colon);
    if (open < 0) {
      return "";
    }
    int close = json.indexOf('"', open + 1);
    if (close < 0) {
      return "";
    }
    return json.substring(open + 1, close);
  }

  private static int extractInt(String json, String key, int fallback) {
    int k = json.indexOf('"' + key + '"');
    if (k < 0) {
      return fallback;
    }
    int colon = json.indexOf(':', k);
    if (colon < 0) {
      return fallback;
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    int start = i;
    while (i < json.length() && Character.isDigit(json.charAt(i))) {
      i++;
    }
    if (start == i) {
      return fallback;
    }
    try {
      return Integer.parseInt(json.substring(start, i));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}

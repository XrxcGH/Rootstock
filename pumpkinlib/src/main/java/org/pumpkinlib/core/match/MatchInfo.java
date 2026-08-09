package org.pumpkinlib.core.match;

import edu.wpi.first.wpilibj.DriverStation.MatchType;
import java.util.Locale;

/**
 * Event and match identity, exactly as the FMS reported it.
 *
 * <p>Present only when an FMS is supplying it. In the shop, in simulation and on a practice field
 * with no FMS, {@link MatchContext#match()} is empty rather than carrying a record full of
 * placeholder zeroes — an empty {@code Optional} is a fact, {@code MatchInfo("", None, 0, 0)} is a
 * fact wearing a disguise.
 *
 * <p><b>Why this is a signal and not AdvantageKit metadata.</b> {@code Logger.recordMetadata()} is
 * write-once <i>before</i> {@code Logger.start()}, and FMS data does not arrive until the DS
 * connects — which is always after logging starts. Event and match therefore cannot be metadata and
 * are published as ordinary signals under {@code /Pumpkin/Match/}. Only build and identity data,
 * which is known at boot, goes in metadata.
 *
 * @param eventName the FMS event short name, e.g. {@code "CURIE"}; never null, empty when unknown
 * @param matchType practice / qualification / elimination, straight from WPILib; never null
 * @param matchNumber the match number within its type, 1-based; 0 when unknown
 * @param replayNumber the replay index, 1 for a match played once
 */
public record MatchInfo(String eventName, MatchType matchType, int matchNumber, int replayNumber) {

  /**
   * Normalises rather than rejects.
   *
   * <p>This record is built from FMS data on a competition field. A validation exception thrown
   * here would take the robot down at the exact moment nothing about the situation is under a
   * student's control, so a null event name becomes {@code ""} and a null match type becomes
   * {@link MatchType#None}. The "no nulls in public APIs" rule is satisfied by fixing the value, not
   * by refusing it.
   */
  public MatchInfo {
    eventName = eventName == null ? "" : eventName.trim();
    matchType = matchType == null ? MatchType.None : matchType;
    matchNumber = Math.max(0, matchNumber);
    replayNumber = Math.max(0, replayNumber);
  }

  /**
   * The compact label a human uses out loud: {@code "Q34"}, {@code "P3"}, {@code "E7"}.
   *
   * <p>A replay is suffixed — {@code "Q34r2"} — because "the log from Qual 34" is ambiguous the one
   * time it matters. Eliminations from the FMS carry only a match number, so an elimination renders
   * as {@code "E<n>"} and, when the replay index is above one, {@code "E<n>m<r>"}.
   *
   * @return a short, filename-safe label; {@code "M0"} when nothing is known
   */
  public String shortLabel() {
    String prefix =
        switch (matchType) {
          case Practice -> "P";
          case Qualification -> "Q";
          case Elimination -> "E";
          case None -> "M";
        };
    if (matchType == MatchType.Elimination) {
      return replayNumber > 1 ? prefix + matchNumber + "m" + replayNumber : prefix + matchNumber;
    }
    return replayNumber > 1 ? prefix + matchNumber + "r" + replayNumber : prefix + matchNumber;
  }

  /**
   * Whether this record carries enough to identify a real match.
   *
   * @return true when the match number is at least 1 and the type is not {@link MatchType#None}
   */
  public boolean isKnown() {
    return matchNumber >= 1 && matchType != MatchType.None;
  }

  /**
   * One line for a log, an alert or a pit display.
   *
   * @return e.g. {@code "CURIE Q34"}, or {@code "(no event) Q34"} when the event name is missing
   */
  public String describe() {
    String event = eventName.isEmpty() ? "(no event)" : eventName.toUpperCase(Locale.ROOT);
    return event + " " + shortLabel();
  }
}

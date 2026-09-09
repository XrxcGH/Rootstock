package org.rootstock.superstructure;

import java.util.List;

/**
 * A static analysis of the declared state machine. Computed at construction from the states, the
 * interlocks, the {@link SafetyModel} and the mechanisms' declared setpoints. <b>No hardware, no
 * motion, no HAL</b> — it runs in a unit test on a laptop, and it runs again in the boot dump on the
 * robot.
 *
 * <p>{@code design/01} §8.8 ranks measured transition costs and static analysis as the <b>#1 elite
 * differentiator</b> in the survey: 254 auto-generates its transition edges and persists measured
 * costs, 6328 runs BFS over an explicit directed graph. A greedy heuristic with no analysis behind it
 * means {@code AutoStep.budget()} is a guess and "the robot cannot stow from CLIMB_FINAL" is
 * something you find out during a climb.
 *
 * <h2>What is fatal and what is a warning</h2>
 *
 * <p>Any non-empty {@link #statesWithNoPathToIdle()}, {@link #unroutableTransitions()} or
 * {@link #unresolvedSetpointReferences()} is a <b>FATAL</b> {@code ConfigError} and puts the robot
 * into SAFE_MODE. The reasoning is not symmetric and is worth stating: a state you cannot leave is a
 * robot that cannot stow, which is the single most expensive superstructure bug there is; a
 * transition the router cannot solve will refuse at runtime, and you want to know that in the shop
 * rather than in a match; and an unresolved setpoint name is {@code design/01} §8.9's whole subject —
 * a typo that used to surface at the moment a driver pressed a button.
 *
 * <p>The rest are Tier-2 alerts. An unreachable state is usually a typo or a permanently-false
 * interlock and is worth a look, but it cannot hurt you mid-match; a mechanism with no declared
 * default is outside the §8.5 inversion's protection, which is a real gap but a knowable one.
 *
 * @param unreachableStates states no sequence of legal transitions can reach from idle. Usually a
 *     typo or a permanently-false interlock
 * @param statesWithNoPathToIdle states from which idle is <b>not</b> reachable. A robot that can
 *     enter one of these is a robot that cannot stow
 * @param transitionsCrossingAZone declared transitions whose bounding box (or diagonal, when
 *     {@code synchronizedAxes}) crosses a forbidden zone, with the zone named. These are the moves a
 *     router must detour — and, until one is installed, the moves that will be refused
 * @param unroutableTransitions transitions the installed router cannot solve within the four-waypoint
 *     cap. Empty when no router is installed, because "nobody tried" is not the same finding as "it
 *     cannot be done" and conflating them would fail every robot with zones and no router into
 *     SAFE_MODE
 * @param axesWithNoDeclaredDefault mechanisms with no declared default — the §8.5 inversion cannot
 *     protect them, so a state that forgets to name one leaves it wherever the last state put it
 * @param unresolvedSetpointReferences setpoint names referenced by a state that its mechanism does
 *     not declare (§8.9), each rendered as a complete message with a "did you mean"
 */
public record SuperstructureReport(
    List<String> unreachableStates,
    List<String> statesWithNoPathToIdle,
    List<String> transitionsCrossingAZone,
    List<String> unroutableTransitions,
    List<String> axesWithNoDeclaredDefault,
    List<String> unresolvedSetpointReferences) {

  /** Normalises nulls to empty lists and takes defensive copies; never throws. */
  public SuperstructureReport {
    unreachableStates = copy(unreachableStates);
    statesWithNoPathToIdle = copy(statesWithNoPathToIdle);
    transitionsCrossingAZone = copy(transitionsCrossingAZone);
    unroutableTransitions = copy(unroutableTransitions);
    axesWithNoDeclaredDefault = copy(axesWithNoDeclaredDefault);
    unresolvedSetpointReferences = copy(unresolvedSetpointReferences);
  }

  /**
   * The empty report — every check passed.
   *
   * @return a clean report
   */
  public static SuperstructureReport empty() {
    return new SuperstructureReport(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /**
   * Whether every check passed.
   *
   * @return true when all six lists are empty
   */
  public boolean clean() {
    return unreachableStates.isEmpty()
        && statesWithNoPathToIdle.isEmpty()
        && transitionsCrossingAZone.isEmpty()
        && unroutableTransitions.isEmpty()
        && axesWithNoDeclaredDefault.isEmpty()
        && unresolvedSetpointReferences.isEmpty();
  }

  /**
   * Whether anything here should stop the robot moving.
   *
   * @return true when a fatal category is non-empty
   */
  public boolean hasFatalFindings() {
    return !statesWithNoPathToIdle.isEmpty()
        || !unroutableTransitions.isEmpty()
        || !unresolvedSetpointReferences.isEmpty();
  }

  /**
   * How many findings there are in total.
   *
   * @return the count across all six categories
   */
  public int findingCount() {
    return unreachableStates.size()
        + statesWithNoPathToIdle.size()
        + transitionsCrossingAZone.size()
        + unroutableTransitions.size()
        + axesWithNoDeclaredDefault.size()
        + unresolvedSetpointReferences.size();
  }

  /**
   * The report, printed verbatim in the boot dump.
   *
   * <p>Grouped by category with the fatal ones first, and each category names what it means rather
   * than only what it found — the reader is fourteen, has six minutes, and has never seen this
   * output before.
   *
   * @return the rendered report, newline-terminated
   */
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(512);
    sb.append("Rootstock superstructure report").append(nl);
    if (clean()) {
      sb.append("  clean — every state reachable, every state can stow, every setpoint resolves.")
          .append(nl);
      return sb.toString();
    }
    section(
        sb,
        nl,
        "FATAL",
        "cannot reach idle (the robot could not stow from here)",
        statesWithNoPathToIdle);
    section(sb, nl, "FATAL", "unroutable transitions (these REFUSE at runtime)", unroutableTransitions);
    section(sb, nl, "FATAL", "unresolved setpoint references", unresolvedSetpointReferences);
    section(sb, nl, "WARN ", "unreachable states (a typo, or an interlock that is never true)", unreachableStates);
    section(
        sb,
        nl,
        "WARN ",
        "transitions crossing a forbidden zone (a router must detour these)",
        transitionsCrossingAZone);
    section(
        sb,
        nl,
        "WARN ",
        "axes with no declared default (outside the default-output inversion)",
        axesWithNoDeclaredDefault);
    return sb.toString();
  }

  @Override
  public String toString() {
    return "SuperstructureReport[" + findingCount() + " finding(s), clean=" + clean() + "]";
  }

  private static void section(
      StringBuilder sb, String nl, String level, String title, List<String> lines) {
    if (lines.isEmpty()) {
      return;
    }
    sb.append("  [").append(level).append("] ").append(title).append(nl);
    for (String line : lines) {
      sb.append("      - ").append(line).append(nl);
    }
  }

  private static List<String> copy(List<String> in) {
    return in == null ? List.of() : List.copyOf(in);
  }
}

package org.rootstock.core.selftest;

import java.util.List;

/**
 * The outcome of one {@link SelfTestRoutine}: what ran, whether it passed, how long it took, and —
 * for every expectation — what was expected next to what was actually observed.
 *
 * <p>The {@code expectation} / {@code observed} pair is the whole design. "FAIL" tells a student to
 * find a mentor; "expected angle settles at 90.000 deg (+/- 2.000 deg), observed never got closer
 * than 61.400 deg" tells them the arm is hitting something at 61 degrees.
 *
 * @param name the routine name, matching the NT subtable {@code /Rootstock/SelfTest/&lt;name&gt;/}
 * @param passed true only if every step and every routine-wide expectation passed
 * @param durationSec wall time the routine took
 * @param steps one entry per step, in order, plus one per routine-wide expectation
 * @param detail why it failed or was refused, in one line; empty when it passed cleanly
 */
public record SelfTestResult(
    String name, boolean passed, double durationSec, List<StepResult> steps, String detail) {

  /**
   * Compact constructor: defensively copies the step list so a published result cannot change under
   * the dashboard.
   */
  public SelfTestResult {
    steps = steps == null ? List.of() : List.copyOf(steps);
    detail = detail == null ? "" : detail;
  }

  /**
   * The outcome of one step, or of one routine-wide expectation.
   *
   * @param name the step name, e.g. {@code "extend"}
   * @param passed whether every expectation attached to it was met
   * @param expectation what was asserted, in words
   * @param observed what actually happened, with numbers
   */
  public record StepResult(String name, boolean passed, String expectation, String observed) {

    /**
     * One line for the pit printout.
     *
     * @return e.g. {@code "  [FAIL] extend: expected angle settles at 90.000 deg (+/- 2.000 deg),
     *     observed never got closer than 61.400 deg"}
     */
    public String describe() {
      return "  ["
          + (passed ? "PASS" : "FAIL")
          + "] "
          + name
          + ": expected "
          + expectation
          + ", observed "
          + observed;
    }
  }

  /**
   * How many steps passed.
   *
   * @return the count
   */
  public int passedCount() {
    return (int) steps.stream().filter(StepResult::passed).count();
  }

  /**
   * The status word the dashboard shows.
   *
   * @return {@code "PASS"} or {@code "FAIL"}
   */
  public String status() {
    return passed ? "PASS" : "FAIL";
  }

  /**
   * A multi-line block for the Elastic text widget and the pit printout.
   *
   * @return the header line plus one line per step
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(status())
        .append("  ")
        .append(name)
        .append(String.format("  (%d/%d, %.2f s)", passedCount(), steps.size(), durationSec));
    if (!detail.isEmpty()) {
      sb.append("  - ").append(detail);
    }
    sb.append('\n');
    for (StepResult s : steps) {
      sb.append(s.describe()).append('\n');
    }
    return sb.toString();
  }
}

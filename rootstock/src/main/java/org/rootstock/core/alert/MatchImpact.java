package org.rootstock.core.alert;

/**
 * The second, orthogonal axis every alert must declare: <strong>does this mean the robot should not
 * take the field?</strong>
 *
 * <p>Required at every {@link Alerts#error} and {@link Alerts#warning} call site. There is no
 * default and no two-argument overload of those two methods, and there never will be: the author of
 * an alert is the only person who knows the answer, and forcing them to type it is the entire
 * mechanism. ({@code Alerts.info(group, text)} takes two arguments precisely because {@code INFO} is
 * {@link #PIT_ONLY} by construction — an informational alert cannot stop a match.)
 *
 * <p><strong>Why this exists.</strong> A realistic robot has on the order of a hundred alert sites,
 * and a normal half-built week-2 robot raises seventeen of them simultaneously — four
 * motor-disconnected, two absolute-encoder-disconnected, three kG-is-zero, four homing-not-done,
 * git-dirty, CAN-utilization, two camera-not-seen. Every one is honest. Students learn within a week
 * that the alert panel is noise, and at that moment the one real alert on Saturday morning is
 * indistinguishable from the fifteen they have been scrolling past since January. Severity does not
 * fix this. This does: the driver tab shows only {@link #BLOCKS_MATCH} alerts, capped at three.
 *
 * <p>Deliberately an enum and not a {@code boolean blocksMatch}. {@code Alerts.error("Arm", "leader
 * disconnected", true)} is unreadable at exactly the call site that matters most and gets
 * copy-pasted with the wrong literal by precisely the audience this library is for.
 * {@code MatchImpact.BLOCKS_MATCH} cannot be copy-pasted wrong and cannot be silently inverted. Same
 * requirement, same enforcement, better call sites.
 */
public enum MatchImpact {
  /**
   * The robot should not take the field like this. Eligible for the driver mirror, counted by
   * {@link AlertRegistry#blocking()}, and the reason {@link AlertRegistry#matchReady()} is false.
   */
  BLOCKS_MATCH,

  /**
   * True, worth fixing, not match-stopping. Pit tab only — it never occupies a driver slot and never
   * reaches the driver mirror.
   */
  PIT_ONLY
}

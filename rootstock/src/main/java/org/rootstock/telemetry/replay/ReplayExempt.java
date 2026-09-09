package org.rootstock.telemetry.replay;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, with a reason, that a class or method deliberately does something deterministic replay
 * cannot reproduce.
 *
 * <p>Teams will hit false positives from the replay-safety rules, and a rule with no escape hatch gets
 * turned off wholesale. This is the escape hatch, and it is deliberately expensive to use: the reason
 * string is <b>mandatory and must be non-empty</b> — an empty reason is itself an error — and every
 * reason in a build is collected into {@code build/rootstock/replay-exemptions.txt} as a CI artifact so
 * a mentor can read the whole list in one place.
 *
 * <p>Rootstock's own sanctioned exemptions are the two the design names, and there are no others:
 *
 * <ul>
 *   <li>{@code RootstockTracer} reads the un-injected hardware clock, because it must report real wall
 *       time or a 50x replay reports absurd loop times.
 *   <li>The byte-budget governor's framework bucket stats the active log file on a periodic path, to
 *       measure what the facade cannot see. It never affects control and is skipped entirely in
 *       {@code REPLAY}.
 * </ul>
 *
 * <p>Both have their output topics in the replay-diff tool's default ignore set, which is the other
 * half of the exemption: a value that cannot be reproduced must also not be compared.
 *
 * <p>{@link RetentionPolicy#CLASS} retention, so the annotation processor sees it in source and
 * bytecode tooling sees it in the jar, without carrying a reflection cost onto the robot.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ReplayExempt {

  /**
   * Why this code cannot be replayed, in a sentence a mentor reviewing the exemption list can judge.
   *
   * <p>"Profiling only" and "measures real wall time by design" are reasons. "TODO" and "needed" are
   * not, and the point of forcing the string is that the difference is visible in review.
   *
   * @return the reason; must not be empty
   */
  String value();
}

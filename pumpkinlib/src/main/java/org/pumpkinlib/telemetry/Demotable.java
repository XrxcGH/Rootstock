package org.pumpkinlib.telemetry;

// Demotable STAYS in org.pumpkinlib.telemetry and must not follow LogConfig/RobotMode/Tier into
// core.spi (D33). It is never named in an org.pumpkinlib.core or org.pumpkinlib.core.spi signature —
// PumpkinLog.processInputs has no Demotable overload, LogConfig has no demotable field, and the
// parameter appears only on PumpkinLog.critical(...)/log(...) inside this package. ArchUnit rule 9 is
// indifferent to it, and moving a type that does not cross the boundary would dilute what core.spi
// means.
//
// Deliberately NO import of Tier: the javadoc references it, the declaration does not, and an unused
// import is a lie about the dependency graph.

/**
 * Whether the byte-budget governor is permitted to <b>slow down</b> a key — never to remove it.
 *
 * <p><b>Orthogonal to {@code org.pumpkinlib.core.spi.Tier}.</b> Tier answers <i>"may this key
 * disappear when the FMS gate raises {@code minimumTier}?"</i>; {@code Demotable} answers <i>"may this
 * key be sampled slower under sustained load?"</i> The two axes are kept separate because collapsing
 * them would mean the FMS tier gate and the governor could no longer be reasoned about independently.
 *
 * <p><b>The default is {@link #NO} for every key in the library and every key a team logs.</b> The
 * governor may not touch a key that is not explicitly marked {@link #YES}. Omitting the parameter
 * means {@link #NO}, so there is no way to make a key demotable by accident.
 *
 * <p>A demoted key is published every {@value PumpkinBudget#kDemotedPublishEveryN}th cycle instead of
 * every cycle. It is never deleted, never silently retyped, and every demotion bumps
 * {@code Pumpkin/Log/SchemaGeneration} so replay and the triage tooling can see the boundary.
 *
 * <h2>THE INPUT INVARIANT</h2>
 *
 * <p><b>A key that is read back from the log in {@code REPLAY} mode is never demotable, at any tier,
 * under any load, by any mechanism. {@code Demotable.YES} applies to outputs only.</b>
 *
 * <p>This is the sentence that keeps the byte-identical replay guarantee true, and it is enforced
 * structurally rather than by convention:
 *
 * <ul>
 *   <li><b>Type level.</b> {@link PumpkinLog#processInputs(String,
 *       org.littletonrobotics.junction.inputs.LoggableInputs)} has no {@code Demotable} overload, and
 *       {@code Demotable} is not a field, parameter or annotation anywhere on {@code LoggableInputs},
 *       {@code LogTable} or any {@code *Inputs} class. There is nothing to pass.
 *   <li><b>Runtime.</b> {@link PumpkinBudget#isDemotionEligible(String)} refuses any key whose path
 *       contains {@code "/Inputs/"} or lies under {@code Pumpkin/Driver/}, and marking one raises a
 *       named alert instead of quietly succeeding.
 *   <li><b>Build level.</b> {@code pumpkinlib-lint} fails the build on the same two patterns.
 * </ul>
 *
 * <p>Demoting an input would break replay in two independent ways. First the values diverge: on the
 * real robot {@code updateInputs} reads a fresh value every cycle regardless of what the governor is
 * doing to the <i>log</i>, so in {@code REPLAY} the four cycles out of five where nothing was written
 * hand {@code fromLog} a stale value, and every output derived from it drifts — including the power,
 * brownout and thermal monitors that are required to reach the same verdict at 1x and 50x. Second
 * there is no mechanism to do it with: a hand-written {@code toLog} writes every field unconditionally
 * into one {@code LogTable}, and nothing in AdvantageKit lets a governor gate an individual field
 * inside a {@code LoggableInputs}.
 */
public enum Demotable {

  /**
   * The governor may never touch this key. The default, and the value you get by omitting the
   * parameter.
   */
  NO,

  /**
   * The governor may publish this key every {@value PumpkinBudget#kDemotedPublishEveryN}th cycle when
   * it has been over budget for {@value PumpkinBudget#kOverBudgetCycles} consecutive cycles.
   *
   * <p>Legal only on <b>outputs</b>. Passing it for a key containing {@code "/Inputs/"} or under
   * {@code Pumpkin/Driver/} is refused at runtime and is a build error under {@code pumpkinlib-lint}.
   */
  YES
}

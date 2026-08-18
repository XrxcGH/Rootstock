/**
 * The reusable step primitives every recipe is built from.
 *
 * <p>Seven small strategy objects cover both shipped recipes and, when the remaining four land, all
 * six. A recipe is then literally a list, which is what makes "add the arm recipe" a data change
 * rather than a code change.
 *
 * <p><b>Every class here obeys the same three rules.</b> It is a sub-state machine driven from
 * {@code periodic()}, never a blocking loop. Its {@code periodic()} calls {@code
 * supervisor().check()} as its first statement, because the twelve abort conditions are only live
 * while something is calling them. And it reaches a motor through {@code
 * TuningSupervisor.commandVolts} or not at all.
 *
 * <p>The most dangerous class in the package by a wide margin is {@link
 * org.pumpkinlib.tuning.wizard.steps.HoldBisectionStep}: open-loop voltage on a mechanism that
 * gravity is actively pulling on. Its javadoc walks through the version of it that would have
 * destroyed a real arm on iteration one, because the five changes that fix it only make sense
 * against the failure that motivated them.
 */
package org.pumpkinlib.tuning.wizard.steps;

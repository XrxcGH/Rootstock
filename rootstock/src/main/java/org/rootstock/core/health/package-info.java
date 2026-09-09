/**
 * Robot health: the round-robin slice scheduler, the fault model, and the registry that answers
 * "can this robot play a match right now?".
 *
 * <p><strong>Why this package exists.</strong> Elite teams lose almost no matches to "the robot
 * didn't move"; small teams lose several per event. Almost every one of those losses is a condition
 * that some piece of software already knew about — a CAN device that never answered, a battery at
 * 11.8 V, a 5 V rail fault that made an encoder lie — and that nobody was told about in the ninety
 * seconds when it was still fixable. This package is the telling.
 *
 * <p><strong>The scheduling rule, stated once.</strong> Health checks are expensive. Running them
 * all every loop costs ~10 ms/loop (Team 135's measurement); running them all at 4 Hz converts that
 * sustained tax into a 10 ms spike every 250 ms, which overruns every twelfth loop and correlates
 * with nothing a student can see. So {@link org.rootstock.core.health.SliceScheduler} runs
 * <em>exactly one</em> registered slice per robot loop, round-robin, <strong>counted in cycles and
 * never against a wall clock</strong> — a wall clock would make the same log replay differently and
 * turn every replay diff into an artifact of the harness.
 *
 * <p>Read {@code design/06-platform-compday.md} §8.3 for the full argument.
 */
package org.rootstock.core.health;

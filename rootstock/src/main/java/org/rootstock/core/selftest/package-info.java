/**
 * Self-test: one button in the queue line that proves the robot works.
 *
 * <p>Every mechanism moves, every sensor reports, every CAN device answers, every controller is in
 * the right slot — PASS or FAIL, per subsystem, on a pit screen. Ninety seconds, repeatable, no
 * tribal knowledge. {@code SelfTest.runAll()} is the entry point and it is meant to be bound to one
 * button.
 *
 * <p>The workflow is Team 135's Consul/Squire, which proved it: run in Test mode, sequential,
 * per-step pass/fail. What is dropped is the two things that stopped anyone else adopting it —
 * mandatory inheritance ({@code extends SubsystemChecker}) and a separate desktop application. Here
 * a mechanism opts in by implementing {@link org.rootstock.core.selftest.SelfTestable}, or by
 * registering a bare routine with no owning object at all, and the results land on the dashboard the
 * team already has open.
 *
 * <p><strong>It is safe to run in the pit.</strong> Motion is bounded by a per-step timeout, the run
 * aborts if a blocking alert activates mid-test, and {@code runAll()} refuses outright while the
 * robot is FMS-attached and enabled — with an alert that says exactly why it refused, because a
 * button that silently does nothing is worse than no button.
 */
package org.rootstock.core.selftest;

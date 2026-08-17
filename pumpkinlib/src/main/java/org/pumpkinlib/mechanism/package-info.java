/**
 * Mechanism templates — the layer a team actually writes robot code against.
 *
 * <h2>What is here</h2>
 *
 * <ul>
 *   <li>{@link org.pumpkinlib.mechanism.Mechanism} — the abstract base. It implements {@code
 *       Subsystem}, {@code TelemetrySource}, {@code HealthSource}, {@code SelfTestable} and {@code
 *       TuningTarget}, which is what makes {@code PumpkinRegistry.addAll(...)} the only registration
 *       call a team writes.
 *   <li>{@link org.pumpkinlib.mechanism.MechanismMode} — the one state enum, replacing the private
 *       three-boolean state machines every team rewrites.
 *   <li>{@link org.pumpkinlib.mechanism.ManualControl} — deadband, scale, and the
 *       capture-and-hold-on-release edge.
 *   <li>{@link org.pumpkinlib.mechanism.ContinuousUnwrap} — over-360&deg; azimuths and the
 *       field-locked turret.
 *   <li>{@link org.pumpkinlib.mechanism.GoalBus} — the contract that lets auto request mechanism
 *       goals without cancelling itself, plus the map-backed adapter that ships regardless.
 * </ul>
 *
 * <h2>Two rules that hold across the whole package</h2>
 *
 * <ol>
 *   <li><b>Nothing throws outside constructors and static factories.</b> Construction is boot, and a
 *       fatal configuration error there is supposed to stop the robot before it moves. Everything
 *       reachable from {@code periodic()} reports instead — an alert, a fault, a neutral output. An
 *       architecture rule enforces it over bytecode, because a diagnostic must never become the
 *       outage.
 *   <li><b>Commands come from factories, never from subclasses.</b> {@code Mechanism} is one of only
 *       two abstract classes the public API permits, and it earns that by being the thing a team
 *       genuinely extends. Every command this package hands out is a fresh, named instance built by a
 *       factory method, which is what makes the 2027 Commands v3 port an internal swap rather than a
 *       breaking change for every team.
 * </ol>
 */
package org.pumpkinlib.mechanism;

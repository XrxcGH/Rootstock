/**
 * Backends with no hardware behind them, so that every mechanism has physics with no extra team
 * code.
 *
 * <p>{@link org.pumpkinlib.hardware.sim.SimMotorIO} integrates a motor-curve plant in <b>rotor</b>
 * space and reports it in <b>output-shaft</b> space, applying the reduction at exactly the two
 * places a real device applies it. A mis-entered gear ratio therefore produces a mechanism that
 * moves at the wrong speed in {@code simulateJava}, on a laptop, in week two — rather than at the
 * first event. Its setpoint latches, exactly like a real smart controller, so the class of bug where
 * a mechanism falls the first time a loop overruns is reproducible here.
 *
 * <p>These backends are for a machine with no vendor libraries installed at all, and for
 * cross-backend parity tests. They are deliberately <b>not</b> how the Phoenix and REV adapters
 * simulate: those drive their own vendor sim state so that simulation exercises the real config
 * path, including {@code SensorToMechanismRatio} and Motion Magic. A simulation that bypasses the
 * device's own conversion tests the simulation, not the robot.
 *
 * <p>{@link org.pumpkinlib.hardware.sim.SimGyroIO} exists mainly so the field-locked-axis feature —
 * a turret holding a field heading while the chassis spins under it — is testable at all. That
 * feature reaches the motor through the goal-velocity parameter of the seam, and an untestable path
 * is where a surveyed template silently drops the term.
 */
package org.pumpkinlib.hardware.sim;

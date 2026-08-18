/**
 * The teaching layer: turn a recorded step response into a classification and one concrete action.
 *
 * <p>A plot is not a lesson. A student who sees a mechanism overshoot and ring knows something is
 * wrong and has no idea which of seven numbers to change; the usual outcome is that they change kP,
 * because kP is the one they have heard of. What this package does is measure the response the way a
 * controls engineer would — rise time, overshoot, settling time, damping ratio by logarithmic
 * decrement, oscillation frequency by zero crossings — and then say, in one sentence, which gain is
 * responsible and which direction to move it.
 *
 * <p>The classification order matters and is not arbitrary. Instability is caught before anything
 * else, because a diverging response is the one that breaks hardware; and a mechanism that never
 * arrives is never classified on its (nonexistent) overshoot.
 *
 * <p>Everything here is pure: it takes a list of samples and returns a record. That is what lets the
 * same analysis run live in the wizard, again in a pit-time health check, and a third time in a unit
 * test against a synthetic second-order response with a known damping ratio.
 */
package org.pumpkinlib.tuning.diagnostics;

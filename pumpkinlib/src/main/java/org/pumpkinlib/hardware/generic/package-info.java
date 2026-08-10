/**
 * Backends for hardware that has no vendor SDK: a PWM speed controller, a quadrature or duty-cycle
 * encoder on the roboRIO, and a DIO switch.
 *
 * <p>{@link org.pumpkinlib.hardware.generic.GenericMotorIO} forces {@code
 * ControlLocation.RIO_FULL} and runs its profile, feedback and feedforward through the shared {@link
 * org.pumpkinlib.hardware.RioControlLoop} — the same object every other backend uses when it is
 * downgraded, never a second implementation of the same control law. It reports every on-board
 * capability as false, which is what makes the downgrade notice fire exactly once, at boot, naming
 * what was lost.
 *
 * <p>{@link org.pumpkinlib.hardware.generic.DioSensorIO} owns the two things a subsystem otherwise
 * re-invents: inversion (declared, not a stray {@code !}) and debounce (always applied, on a level
 * rather than an edge).
 *
 * <p><i>2027 note:</i> {@code MotorController.set()} becomes {@code setThrottle()}, and {@code
 * DutyCycleEncoder}'s survival past the {@code Counter} removal is unverified. Both live in this one
 * package on purpose.
 */
package org.pumpkinlib.hardware.generic;

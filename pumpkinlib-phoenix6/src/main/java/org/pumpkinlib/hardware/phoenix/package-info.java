/**
 * The Phoenix 6 adapter — the <b>only</b> package in PumpkinLib allowed to import {@code com.ctre}.
 *
 * <p>Everything this package exposes to the rest of the library is a PumpkinLib interface:
 * {@link org.pumpkinlib.hardware.phoenix.TalonFXMotorIO} is a
 * {@link org.pumpkinlib.hardware.MotorIO},
 * {@link org.pumpkinlib.hardware.phoenix.Phoenix6GainSink} is a
 * {@link org.pumpkinlib.control.GainSink},
 * {@link org.pumpkinlib.hardware.phoenix.Pigeon2GyroIO} is a
 * {@link org.pumpkinlib.hardware.GyroIO}. Core never sees a {@code TalonFX} in a signature, and the
 * escape hatches that do hand one back ({@code TalonFXMotorIO.talonFX()},
 * {@code TalonFXMotorIO.applyRaw(...)}) are reached through
 * {@code MotorIO.as(TalonFXMotorIO.class)}, which is a cast the caller asked for rather than a
 * vendor type leaking upward.
 *
 * <h2>Verified against Phoenix 6 26.3.0</h2>
 *
 * <p>Every vendor API used here was checked against the shipped jar rather than against
 * documentation. Three findings shaped the code:
 *
 * <ul>
 *   <li>{@code MotionMagicVoltage} and {@code MotionMagicExpoVoltage} have <b>no</b>
 *       {@code withVelocity}. A goal velocity rides as volts on {@code withFeedForward}.
 *   <li>{@code DynamicMotionMagicVoltage} takes <b>three</b> constructor arguments (position,
 *       velocity, acceleration) — there is no jerk argument — and its {@code Velocity} is the
 *       <em>cruise</em> velocity, so a chained {@code withVelocity} overwrites the profile limit
 *       rather than setting a goal velocity.
 *   <li>{@code Follower} takes a {@code MotorAlignmentValue}, not a boolean.
 * </ul>
 */
package org.pumpkinlib.hardware.phoenix;

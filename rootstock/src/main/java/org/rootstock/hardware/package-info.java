/**
 * The hardware abstraction layer: one seam between mechanism logic and a motor controller, placed at
 * a <b>goal</b> rather than at a voltage.
 *
 * <h2>The load-bearing decision</h2>
 *
 * <p>Every prior attempt at vendor neutrality in FRC put the seam at {@code setVoltage(double)} and
 * a roboRIO PID loop. Doing that throws away Motion Magic's 1 kHz on-motor profile, FOC, {@code
 * SensorToMechanismRatio}, batched status-signal refresh, setpoint latching, fused CANcoders,
 * dynamic per-request profiles and REVLib's on-controller closed loop — that is, everything a Kraken
 * is worth.
 *
 * <p>So {@link org.rootstock.hardware.MotorIO} takes {@code setPositionGoal(outputRotations,
 * outputRotationsPerSecond, arbFeedforwardVolts)} and the velocity equivalent, in mechanism units,
 * and lets the backend decide what vendor request that becomes. Nothing above the seam knows or
 * cares.
 *
 * <h2>Reading the package</h2>
 *
 * <ul>
 *   <li>{@link org.rootstock.hardware.MotorIO} — the seam. Read its class javadoc first; the unit
 *       contract in it is the whole design.
 *   <li>{@link org.rootstock.hardware.MotorInputs} — everything read back, hand-written
 *       {@code LoggableInputs}, with NaN meaning "not measured" and never a frozen zero.
 *   <li>{@link org.rootstock.hardware.MotorCapabilities} — what a backend can actually do, so a
 *       caller asks instead of discovering by failure, and so a {@code ControlLocation} downgrade is
 *       loud and printed.
 *   <li>{@link org.rootstock.hardware.SignalSet} — subscription and read as one declaration, so
 *       Phoenix's one-transaction CAN efficiency survives the abstraction and an unsubscribed field
 *       cannot be silently frozen.
 *   <li>{@link org.rootstock.hardware.RioControlLoop} — the single implementation of
 *       {@code RIO_FULL}, shared by every backend that needs it.
 *   <li>{@link org.rootstock.hardware.MotorIOFactory} — backend selection in one place, with
 *       vendors registering themselves rather than being named by core.
 *   <li>{@link org.rootstock.hardware.AbsoluteEncoderIO}, {@link org.rootstock.hardware.GyroIO},
 *       {@link org.rootstock.hardware.DigitalSensorIO} — the three other sensor seams core owns.
 * </ul>
 *
 * <h2>Two rules this package lives under</h2>
 *
 * <p><b>Zero vendor imports.</b> This artifact compiles and runs with neither Phoenix nor REVLib on
 * the classpath. Adapters live in their own artifacts and are discovered, not imported.
 *
 * <p><b>Nothing throws outside construction.</b> A validation mistake is a value collected into safe
 * mode at boot, not an exception forty seconds into a match. Every method here degrades and reports;
 * only constructors and static factories reject.
 */
package org.rootstock.hardware;

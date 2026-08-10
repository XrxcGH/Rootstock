package org.pumpkinlib.hardware;

import java.util.Optional;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.core.spi.SimMotorHandle;

/**
 * The single boundary between mechanism logic and a motor controller.
 *
 * <h2>UNIT CONTRACT (memorize this; it is the whole point)</h2>
 *
 * <ul>
 *   <li>every position is in <b>OUTPUT-SHAFT ROTATIONS</b>,
 *   <li>every velocity is in <b>OUTPUT-SHAFT ROTATIONS PER SECOND</b>,
 *   <li>every acceleration is in <b>OUTPUT-SHAFT ROTATIONS PER SECOND SQUARED</b>,
 *   <li>every feedforward is in <b>VOLTS</b>.
 * </ul>
 *
 * <p>"Output shaft" means the last shaft before the mechanism's own geometry — the drum, the joint,
 * the turret ring. The <b>gearbox lives INSIDE the backend</b> (Phoenix {@code
 * SensorToMechanismRatio}, REV {@code positionConversionFactor}). The <b>geometry lives ABOVE the
 * seam</b>, in {@code MechanismUnits} (drum radius, cascade stages, degrees per rotation). Neither
 * is ever applied twice, and the reason 41 inline {@code / 360.0} conversions and one
 * double-applied-ratio bug exist in the surveyed robot code is that neither of those two sentences
 * was ever written down.
 *
 * <h2>Why the seam is a GOAL and not a VOLTAGE</h2>
 *
 * <p>The naive vendor-neutral motor interface is {@code getPosition()} plus {@code
 * setVoltage(volts)}, with a roboRIO {@code PIDController} in between. That interface throws away
 * everything a Kraken X60 is worth: Motion Magic's 1 kHz on-motor profile, FOC (a per-<i>request</i>
 * flag, not a config), {@code SensorToMechanismRatio}, batched status-signal refresh, <b>setpoint
 * latching</b>, fused CANcoders, dynamic per-request motion profiles, and REVLib 2026's
 * on-controller gravity feedforward. Every one of those is unreachable through a voltage.
 *
 * <p>So the seam is {@link #setPositionGoal(double, double, double)} — <i>where to end up</i>, in
 * mechanism units. Whether a backend turns that into {@code MotionMagicVoltage}, {@code
 * DynamicMotionMagicVoltage}, {@code SparkClosedLoopController.setReference(...,
 * kMAXMotionPositionControl)} or a roboRIO {@code TrapezoidProfile} plus {@code PIDController} is
 * the <b>backend's business</b>. Nothing above the seam knows or cares. What a backend can actually
 * do is readable, ahead of time, from {@link #capabilities()} — you ask, rather than discovering by
 * failure.
 *
 * <h2>The escape hatch is on page one</h2>
 *
 * <p>{@link #as(Class)} is not an appendix. Hiding the vendor object is explicitly against this
 * library's principles: PumpkinLib models what is worth modelling and hands you the real device for
 * everything else.
 *
 * <pre>{@code
 * // Anything PumpkinLib does not model, you do yourself, on the real device.
 * elevator.io().as(TalonFXMotorIO.class).ifPresent(io -> {
 *     io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false);
 *     io.talonFX().setControl(new MusicTone(440));
 * });
 * }</pre>
 *
 * <h2>Implementation notes for backend authors</h2>
 *
 * <p>Nothing on this interface may throw from a periodic path (ArchUnit rule 11). A backend that
 * cannot honour a call reports it through {@link #capabilities()}, raises a named alert, and
 * degrades — it never takes the robot loop with it.
 */
public interface MotorIO {

  // ---- read ------------------------------------------------------------------------------------

  /**
   * Refresh all SUBSCRIBED signals in ONE batched transaction and fill {@code inputs}.
   *
   * <p>Fields backed by a signal this IO did not subscribe are left at {@link Double#NaN} — never a
   * frozen zero. A frozen zero is a lie that survives a whole match; NaN is honest and is visibly
   * wrong on an AdvantageScope plot. See {@link SignalSet}, which makes "subscribe" and "read" the
   * same statement so the two cannot drift apart.
   *
   * @param inputs the inputs object to fill; the same instance every loop, so it must not be
   *     reallocated by the caller
   */
  void updateInputs(MotorInputs inputs);

  // ---- write: goals ----------------------------------------------------------------------------

  /**
   * Closed-loop position goal.
   *
   * @param outputRotations where to end up, in output-shaft rotations
   * @param outputRotationsPerSecond the velocity the controller should ALSO be tracking at that
   *     instant. Zero in the common case. Non-zero for a roboRIO-stepped profile (the profile step's
   *     velocity) and for a field-locked turret's chassis-omega counter-rotation — which is the
   *     whole reason this parameter exists.
   *     <p><b>THE SEAM PROMISES DELIVERY, NOT A MECHANISM.</b> Not every vendor request has a
   *     velocity field — Phoenix's Motion Magic requests do <i>not</i>, verified against the 26.3.0
   *     jar — so a backend declares HOW it carries the term via {@link
   *     MotorCapabilities#positionGoalVelocity()}, and every value of {@link VelocityCarrier} except
   *     {@link VelocityCarrier#UNSUPPORTED} delivers it. A backend may <b>never silently drop
   *     it</b>; that is the defect this parameter exists to make impossible.
   * @param arbFeedforwardVolts volts ADDED on top of whatever gravity term the controller computes
   *     from {@link Gains#kG()}. Pass 0 when gravity is handled on the motor.
   */
  void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts);

  /**
   * Closed-loop position goal with a one-request constraint override.
   *
   * <p>This is the runtime constraint-profile path: change cruise velocity and acceleration for
   * <i>this request only</i>, with no blocking config write. Backends that cannot do it per-request
   * report {@link MotorCapabilities#dynamicProfile()} {@code == false}, and the mechanism layer
   * routes them to a roboRIO-stepped profile instead — loudly, at construction, not silently at
   * match time.
   *
   * @param outputRotations where to end up, in output-shaft rotations
   * @param outputRotationsPerSecond the velocity to also be tracking at that instant; see {@link
   *     #setPositionGoal(double, double, double)}
   * @param arbFeedforwardVolts volts added on top of the controller's own gravity term
   * @param override the constraints to use for this request, in USER units per second and per second
   *     squared; converted at the seam
   */
  void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override);

  /**
   * Closed-loop velocity goal.
   *
   * @param outputRps the velocity to hold, in output-shaft rotations per second
   * @param outputRps2 the profile's acceleration at that instant, in output rotations per second
   *     squared; zero in the common case
   * @param arbFeedforwardVolts volts added on top of the controller's own feedforward
   */
  void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts);

  /**
   * Open loop, absolute volts, battery-compensated by the backend where the device supports it.
   *
   * @param volts the commanded voltage
   */
  void setVoltage(double volts);

  /**
   * Open loop, duty cycle.
   *
   * @param fraction the commanded output, -1..1; implementations clamp rather than reject
   */
  void setDutyCycle(double fraction);

  /**
   * Stop applying output, honouring the configured {@link NeutralMode}.
   *
   * <p>Not the same as {@code setVoltage(0)} on a latching backend: a latched setpoint keeps driving
   * toward a goal the robot code has already abandoned, and this is the call that releases it.
   */
  void setNeutral();

  // ---- write: configuration --------------------------------------------------------------------

  /**
   * Push gains to the controller. <b>NON-BLOCKING</b> — zero-timeout apply, no read-back, no retry.
   *
   * <p>Safe to call every loop; implementations no-op when the values are unchanged. This is what
   * makes live tuning free. A verified apply here would block the main loop for up to 500 ms, ten
   * times a second, on the exact workflow this library is built around — a student dragging a
   * slider.
   *
   * @param siGains the gains in the canonical volts-per-SI form; the backend converts to vendor
   *     units exactly once, here
   */
  void applyGains(Gains siGains);

  /**
   * Push profile constraints, in USER units per second and per second squared. Converted at the
   * seam. Same non-blocking contract as {@link #applyGains(Gains)}.
   *
   * @param constraints the cruise velocity, acceleration and jerk limits
   */
  void applyConstraints(MotionConstraints constraints);

  /**
   * Set what the motor does when nothing is commanding it.
   *
   * @param mode brake or coast
   */
  void setNeutralMode(NeutralMode mode);

  /**
   * Tell the controller that it is currently at {@code outputRotations}.
   *
   * <p><b>Non-blocking.</b> Implementations MUST use a zero timeout: {@code
   * TalonFX.setPosition(double)} blocks the main loop for up to 100 ms waiting for the device ack,
   * and this call happens during a move on a guarded re-seed.
   *
   * @param outputRotations the position the mechanism is actually at, in output-shaft rotations
   */
  void seedPosition(double outputRotations);

  /**
   * Full, verified, retried re-apply of the ENTIRE device configuration. <b>Blocking.</b>
   *
   * <p>Legal callers, exhaustively: a constructor, {@code disabledInit()}, self-test, the raw-config
   * escape hatch, the device-reset recovery path, and the two homing config transactions. It is
   * ILLEGAL from an enabled {@code periodic()} that is not one of those.
   */
  void reapplyFullConfigBlocking();

  // ---- simulation ------------------------------------------------------------------------------

  /**
   * The simulation seam: core DECLARES the plant, simulation OWNS and steps it.
   *
   * <p>A backend that can be driven in simulation returns a handle here; the simulator writes the
   * simulated <i>rotor</i> state through it and reads the applied voltage back. Rotor, not output —
   * the conversion between the two is the device's own {@code SensorToMechanismRatio}, which is
   * exactly the code most likely to be misconfigured and therefore exactly the code simulation must
   * exercise.
   *
   * <p>{@link Optional#empty()} means "this backend has no simulation", and the simulator says so at
   * boot rather than silently doing nothing. That is the NEO-has-no-sim gap made visible.
   *
   * @return the sim handle, or empty when this backend cannot be simulated
   */
  default Optional<SimMotorHandle> simHandle() {
    return Optional.empty();
  }

  // ---- introspection ---------------------------------------------------------------------------

  /**
   * What this backend can actually do.
   *
   * <p>Callers ask this instead of discovering a missing capability by failure. It is what lets the
   * mechanism layer downgrade a {@code ControlLocation} loudly at construction, and what decides
   * which status signals are worth paying CAN for.
   *
   * @return the capability set; never null, and constant for the life of this IO
   */
  MotorCapabilities capabilities();

  /**
   * The name this IO logs and alerts under — normally the mechanism name plus the device id.
   *
   * @return a stable, human-readable identifier
   */
  String name();

  /**
   * The derived model this IO actually installed, in prose, for the boot dump and for on-demand
   * printing.
   *
   * <p>It names the numbers a student would otherwise have to infer: the ratio pushed into the
   * device, the control location in force, the gain conversion applied, and which {@link
   * VelocityCarrier} is carrying the goal-velocity term.
   *
   * @return a multi-line human-readable description
   */
  String describe();

  /**
   * The typed escape hatch: reach the real vendor object.
   *
   * <p>Documented here, on page one, rather than in an appendix. PumpkinLib does not model every
   * vendor knob and does not pretend to; when you need one it does not model, you unwrap and use the
   * device directly.
   *
   * <p>The default implementation is the correct one for every backend — an {@code isInstance} test
   * and a cast — so a backend author never writes it. Override only to expose a <i>delegate</i> (a
   * wrapper that forwards to an inner IO), which is the one case the default gets wrong.
   *
   * @param <T> the concrete IO type being asked for
   * @param type the backend class, for example {@code TalonFXMotorIO.class}
   * @return this IO as that type, or empty if it is a different backend
   */
  default <T extends MotorIO> Optional<T> as(Class<T> type) {
    return type != null && type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
  }
}

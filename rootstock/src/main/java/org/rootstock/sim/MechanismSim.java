package org.rootstock.sim;

/**
 * One simulated mechanism: a physics plant, the device it drives, and the current it pulls.
 *
 * <p>Instances are created by {@link RootstockSim} from a {@link org.rootstock.core.spi.MechanismGeometry}
 * and ticked by {@link RootstockSim#tick()}. A team never implements this interface and never calls
 * {@link #update(double)} — that is the whole of D18: {@code Mechanism.simulationPeriodic()} does not
 * exist, because simulation owns the tick.
 *
 * <h2>SI, not "meters"</h2>
 *
 * <p>{@link #positionSi()} is <b>metres for a linear plant and radians for a rotary one</b>, matching
 * {@code MechanismGeometry.siMin()} / {@code siMax()} / {@code siPerOutputRotation()} and WPILib's own
 * plants, which take metres and radians. {@code design/04} §7.1 spells this pair
 * {@code positionMeters()} / {@code velocity()} with the inline comment <i>"or radians for angular
 * sims"</i>; a method called {@code positionMeters} that returns radians on every arm in the library is
 * exactly the class of unit lie the rest of Rootstock is built to prevent, so the shape of the design
 * is kept and the two names are made honest. Nothing else about the interface moved.
 *
 * <h2>Why {@link #raw()} returns {@code Object}</h2>
 *
 * <p>The four plants have no common WPILib supertype that exposes anything useful —
 * {@code LinearSystemSim} is generic over its state, input and output dimensions, so a declared return
 * of {@code LinearSystemSim<?, ?, ?>} would buy a cast at every call site and no type safety. The
 * escape hatch is therefore {@code Object} plus a documented set of four possible runtime types, which
 * is what {@code design/04} §7.1 specifies and what an {@code instanceof} at the call site handles in
 * one line.
 */
public interface MechanismSim extends AutoCloseable {

  /**
   * The mechanism's name, matching its telemetry namespace.
   *
   * @return the name this sim is registered under
   */
  String name();

  /**
   * Advances the model one timestep and writes the new state back into the vendor sim.
   *
   * <p>Called by {@link RootstockSim#tick()}. In order: read the volts the device is commanding at the
   * present bus voltage, clamp them to that bus, step the plant, then write the resulting
   * <b>rotor</b> position and velocity back through the {@link org.rootstock.core.spi.SimMotorHandle}
   * so the device's own conversion factors are what the simulation exercises.
   *
   * @param dtSeconds the timestep; non-finite or non-positive values are replaced by
   *     {@link org.rootstock.core.compat.Clock#dt()}
   */
  void update(double dtSeconds);

  /**
   * The plant's position, in SI: metres for a linear plant, radians for a rotary one.
   *
   * @return the position, or {@code NaN} for a plant with no position state (a flywheel before its
   *     first {@link #update(double)})
   */
  double positionSi();

  /**
   * The plant's velocity, in SI per second: m/s for a linear plant, rad/s for a rotary one.
   *
   * @return the velocity
   */
  double velocitySi();

  /**
   * Current the plant is drawing, which is what the battery model is charged for.
   *
   * @return stator amps
   */
  double currentDrawAmps();

  /**
   * The volts the device commanded on the last {@link #update(double)}, after the bus clamp.
   *
   * @return applied volts, signed
   */
  double appliedVolts();

  /**
   * The raw WPILib model, for the one-in-twenty thing Rootstock does not model.
   *
   * <p>Exactly one of {@code ElevatorSim}, {@code SingleJointedArmSim}, {@code FlywheelSim} or
   * {@code DCMotorSim} for a plant built by {@link RootstockSim}; whatever a hand-written
   * implementation chooses otherwise.
   *
   * @return the underlying model, never null
   */
  Object raw();

  /**
   * Tells the plant what the bus voltage is this cycle, so the device's applied volts are clamped to a
   * sagging battery rather than to a fictional 12 V.
   *
   * <p>Called by {@link RootstockSim#tick()} before {@link #update(double)}, with the voltage computed
   * from <i>last</i> cycle's currents — the same one-cycle lag every real closed loop already has, and
   * the alternative is an algebraic loop. A hand-written implementation that does not model sag may
   * ignore this, which is why it is a default rather than an abstract method.
   *
   * @param volts the simulated bus voltage
   */
  default void setBusVolts(double volts) {}

  /**
   * The current this mechanism would pull with its motors stalled at full output.
   *
   * <p>This is the number {@link RootstockSim#stressTest()} sums and prints at boot. It comes from the
   * motor curve ({@code DCMotor.stallCurrentAmps}, which is already multiplied by the motor count),
   * so a four-NEO elevator reports ~724 A and a team finds that out on a laptop rather than at an
   * event.
   *
   * @return stall amps, or {@code NaN} when this implementation cannot say
   */
  default double stallCurrentAmps() {
    return Double.NaN;
  }

  /**
   * A multi-line human-readable summary of the plant, for the boot dump.
   *
   * @return the description, no trailing newline
   */
  default String describe() {
    return name();
  }

  /**
   * Releases anything the plant holds. WPILib's plants hold nothing, so the default does nothing.
   *
   * <p>Narrowed from {@code AutoCloseable}'s {@code throws Exception} on purpose: a simulation plant
   * that throws from {@code close()} would take a test's {@code @AfterEach} with it, and the leaked
   * HAL port that follows poisons the <i>next</i> test rather than failing this one.
   */
  @Override
  default void close() {}
}

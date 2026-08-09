package org.pumpkinlib.core.spi;

/**
 * The vendor's simulated device state, wrapped so that nothing outside the adapter names the vendor
 * type.
 *
 * <p>{@code MotorIO.simHandle()} returns one of these; {@code TalonFXMotorIO} implements it by
 * constructing a Phoenix sim handle <i>inside</i> {@code pumpkinlib-phoenix6}, so the {@code com.ctre}
 * import never leaves the adapter (ArchUnit rule 1).
 *
 * <p><b>Why the seam is at the device and not at the plant.</b> Reading the volts the device is
 * actually commanding — including its on-device closed loop and its own gravity feedforward — and
 * writing the simulated ROTOR state back is what makes vendor simulation exercise the <i>real</i>
 * config path. A simulation that bypasses the device's own {@code SensorToMechanismRatio} tests the
 * simulation, not the robot.
 */
public interface SimMotorHandle {

  /**
   * Volts the device is currently commanding, INCLUDING the on-device closed loop and its gravity
   * feedforward.
   *
   * @param busVoltage the simulated bus voltage, so the device can scale its duty cycle
   * @return the applied voltage
   */
  double appliedVolts(double busVoltage);

  /**
   * Writes the simulated ROTOR state back to the device's sim state.
   *
   * <p>Rotor, not output — the conversion between them is the device's own
   * {@code SensorToMechanismRatio} / conversion factor, which is exactly the code we want under test.
   * Writing output units here would skip the one conversion most likely to be misconfigured.
   *
   * @param rotorRotations the simulated rotor position, in rotor rotations
   * @param rotorRps the simulated rotor velocity, in rotor rotations per second
   */
  void setRotorPosition(double rotorRotations, double rotorRps);

  /**
   * Current draw the plant should charge the battery simulation for.
   *
   * @return stator amps
   */
  double statorAmps();
}

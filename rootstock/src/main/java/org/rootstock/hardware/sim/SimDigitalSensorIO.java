package org.rootstock.hardware.sim;

import edu.wpi.first.math.filter.Debouncer;
import java.util.function.BooleanSupplier;
import org.rootstock.config.SensorSpec;
import org.rootstock.hardware.DigitalSensorIO;

/**
 * A limit switch, beam break or proximity sensor with no hardware behind it.
 *
 * <p>By default it is driven by the mechanism's own simulated position — a beam break at the top of
 * an elevator trips when the simulated carriage gets there — which is what makes a game-piece
 * handling sequence testable end to end without a robot.
 *
 * <p><b>It debounces exactly like the real backend.</b> That is not a detail: a sequence that works
 * in simulation because the simulated sensor is instantaneous, and fails on the robot because the
 * real one is debounced by 40 ms, is worse than no simulation at all. The same {@link Debouncer}
 * with the same declared time runs on both sides.
 */
public final class SimDigitalSensorIO implements DigitalSensorIO {

  private final BooleanSupplier m_detected;
  private final Debouncer m_debouncer;
  private final double m_debounceSeconds;

  private double m_distanceMeters = Double.NaN;
  private boolean m_connected = true;

  /**
   * Builds the backend from a declared simulated sensor.
   *
   * @param spec the declared sensor, carrying the condition and the debounce time
   */
  public SimDigitalSensorIO(SensorSpec.Sim spec) {
    this(
        spec == null ? null : spec.detected(),
        spec == null || spec.debounce() == null ? 0.0 : spec.debounce().baseUnitMagnitude());
  }

  /**
   * Builds the backend from a condition and a debounce time.
   *
   * @param detected the condition that means "the sensor sees something"; null reads as never
   * @param debounceSeconds how long the level must hold before it is believed; zero for none
   */
  public SimDigitalSensorIO(BooleanSupplier detected, double debounceSeconds) {
    m_detected = detected == null ? () -> false : detected;
    m_debounceSeconds =
        Double.isFinite(debounceSeconds) ? Math.max(0.0, debounceSeconds) : 0.0;
    m_debouncer = new Debouncer(m_debounceSeconds, Debouncer.DebounceType.kBoth);
  }

  @Override
  public void updateInputs(DigitalSensorInputs inputs) {
    if (inputs == null) {
      return;
    }
    inputs.connected = m_connected;
    inputs.detected = m_debouncer.calculate(m_detected.getAsBoolean());
    inputs.distanceMeters = m_distanceMeters;
  }

  /**
   * Set the distance a simulated proximity sensor reports.
   *
   * <p>Left at {@link Double#NaN} this simulates a switch or beam break, which cannot measure
   * distance — and NaN rather than zero, because zero metres means "touching".
   *
   * @param meters the simulated distance, or NaN for a sensor that does not measure one
   */
  public void setSimulatedDistanceMeters(double meters) {
    m_distanceMeters = meters;
  }

  /**
   * Simulate the sensor being unplugged, so the degraded path is exercised by a test.
   *
   * @param connected whether the simulated sensor answers
   */
  public void setSimulatedConnected(boolean connected) {
    m_connected = connected;
  }

  /**
   * The debounce time this backend is applying, so a test can assert that it matches the declared
   * one rather than assuming it does.
   *
   * @return seconds
   */
  public double debounceSeconds() {
    return m_debounceSeconds;
  }
}

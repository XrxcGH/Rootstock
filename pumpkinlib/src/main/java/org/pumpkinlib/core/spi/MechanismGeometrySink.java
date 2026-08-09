package org.pumpkinlib.core.spi;

/**
 * Where a mechanism declares its physical geometry so that something else can simulate it.
 *
 * <p>The receiving end is {@code org.pumpkinlib.sim.PumpkinSim}, which this package deliberately does
 * not name: {@code PumpkinRegistry} hands each registered mechanism's {@link MechanismGeometry} to
 * the installed sink, and every arrow still points into core (ArchUnit rule 9).
 *
 * <p>Declaration is fan-in, not fan-out. Core does not tick simulation and has no
 * {@code simulationPeriodic()} to fan out (D18); it says what exists, once, at registration.
 */
public interface MechanismGeometrySink {

  /**
   * Declares one mechanism's plant.
   *
   * <p>Called once per mechanism, from {@code PumpkinRegistry.addAll(...)}. An implementation that
   * cannot simulate the declared geometry must say so with a named boot alert — <i>"Roller: this
   * backend has no simulation; the mechanism will not move in simulateJava"</i> — rather than
   * silently doing nothing, which is how a team spends an evening wondering why their intake is
   * frozen in the sim GUI.
   *
   * @param geometry the declared plant
   */
  void declare(MechanismGeometry geometry);
}

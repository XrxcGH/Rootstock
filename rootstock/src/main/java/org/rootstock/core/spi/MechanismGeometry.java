package org.rootstock.core.spi;

import edu.wpi.first.math.system.plant.DCMotor;

/**
 * What a mechanism physically <i>is</i>, as pure data — the D18 simulation seam.
 *
 * <p><b>CORE declares; Sim owns; the vendor adapter bridges.</b> This record is the declaration.
 * It is computed once at construction from a {@code PositionConfig} and an {@code Axis}, handed to a
 * {@link MechanismGeometrySink} at registration, and that is the whole of core's involvement with
 * simulation. No plant is constructed here and no vendor type is named. {@code RootstockSim} — which
 * this package does not name and does not depend on — builds and steps an {@code ElevatorSim},
 * {@code SingleJointedArmSim}, {@code FlywheelSim} or {@code DCMotorSim} from these numbers, and
 * drives the device through a {@link SimMotorHandle}.
 *
 * <p>D18 deleted {@code Mechanism.simulationPeriodic()} and the {@code m_plant} field along with it.
 * Deciding <i>what plant the mechanism is</i> is the only part a team's declaration can determine and
 * the only part that belongs above the seam.
 *
 * <p><b>NaN is a documented sentinel here, not a bug.</b> A linear axis has no moment of inertia and a
 * rotary one has no carriage mass; the fields that do not apply to a {@link Kind} are NaN, so a
 * consumer that reads the wrong one gets a NaN that propagates loudly rather than a plausible zero
 * that produces a plant which quietly does not move.
 *
 * @param name the mechanism's name, matching its telemetry namespace
 * @param kind which plant this is
 * @param gearbox the motor model times the motor count
 * @param rotorPerOutput the gearbox ratio, from {@code Reduction.rotorPerOutput()}
 * @param siPerOutputRotation metres or radians of travel per output rotation, from the {@code Axis}
 * @param effectiveRadiusMeters drum or sprocket pitch radius times stages; NaN for a rotary axis
 * @param massKg the carriage mass for a linear axis; NaN for a rotary one
 * @param momentOfInertiaKgM2 the moment of inertia for a rotary axis or flywheel; NaN for a linear one
 * @param armLengthMeters the arm length for a rotary axis; NaN otherwise
 * @param siMin the lower soft limit in SI units
 * @param siMax the upper soft limit in SI units
 * @param siStart the starting position in SI units
 * @param simulateGravity whether the plant should model gravity
 */
public record MechanismGeometry(
    String name,
    Kind kind,
    DCMotor gearbox,
    double rotorPerOutput,
    double siPerOutputRotation,
    double effectiveRadiusMeters,
    double massKg,
    double momentOfInertiaKgM2,
    double armLengthMeters,
    double siMin,
    double siMax,
    double siStart,
    boolean simulateGravity) {

  /**
   * Which plant a declared mechanism is.
   *
   * <p>This is the only decision the {@code Axis} and {@code SimConfig} determine that simulation
   * cannot work out for itself, which is why it travels with the geometry rather than being inferred
   * from which fields happen to be NaN.
   */
  public enum Kind {
    /** A carriage on a rail or a cascade — an {@code ElevatorSim}. */
    LINEAR,
    /** An arm or a pivot — a {@code SingleJointedArmSim}. */
    ROTARY,
    /** A free-spinning wheel with inertia and no position limits — a {@code FlywheelSim}. */
    FLYWHEEL,
    /** Anything else driven open-loop — a {@code DCMotorSim}. */
    SIMPLE
  }

  /**
   * The travel range in SI units.
   *
   * @return {@code siMax - siMin}
   */
  public double siRange() {
    return siMax - siMin;
  }

  /**
   * A one-line human-readable summary for the boot dump.
   *
   * @return the mechanism's name, kind, gearing and travel range
   */
  public String describe() {
    return String.format(
        "%s: %s, %.3f:1 rotor-per-output, %.5f SI/rot, travel [%.3f, %.3f], gravity=%s",
        name, kind, rotorPerOutput, siPerOutputRotation, siMin, siMax, simulateGravity);
  }
}

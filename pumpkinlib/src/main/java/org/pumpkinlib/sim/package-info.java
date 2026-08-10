/**
 * Physics simulation: one plant per declared mechanism, one tick, one battery, no second code path.
 *
 * <p><b>The rule this package exists to enforce (design/04 §7.1).</b> If a mechanism declared its
 * geometry, it has a physics sim. Declaring a mass or a moment of inertia in {@code SimConfig} is the
 * <i>only</i> thing a team writes to get physics. There is no {@code simulationPeriodic()} to
 * implement, no {@code m_plant} field to remember, and no forty-line glue block per subsystem — D18
 * deleted all three and moved the plant here.
 *
 * <p><b>The D18 split, restated because it is the thing most easily broken.</b>
 * {@code org.pumpkinlib.core.spi} <i>declares</i>: {@link org.pumpkinlib.core.spi.MechanismGeometry}
 * is pure data and names no simulation type. This package <i>constructs</i>: it turns that record
 * into a WPILib {@code ElevatorSim}, {@code SingleJointedArmSim}, {@code FlywheelSim} or
 * {@code DCMotorSim} and owns the tick. The vendor adapter <i>bridges</i>: it implements
 * {@link org.pumpkinlib.core.spi.SimMotorHandle} so the plant's state is written back as <b>rotor</b>
 * state and the device's own conversion factors are what the simulation exercises. Every arrow points
 * into core, which is what makes ArchUnit rule 9 hold.
 *
 * <p><b>Simulation runs the real ratio path on purpose.</b> The gearing handed to every plant is
 * {@code MechanismGeometry.rotorPerOutput()} and the metres-or-radians per output rotation is
 * {@code siPerOutputRotation()} — the same two numbers {@link org.pumpkinlib.units.MechanismUnits}
 * hands the {@code MotorIO} seam. {@link org.pumpkinlib.sim.PumpkinSim} additionally cross-checks the
 * declared effective radius against {@code siPerOutputRotation / 2&pi;} and says so out loud when they
 * disagree. A unit mistake therefore shows up in {@code simulateJava}, on a laptop, in week two —
 * rather than at the first event.
 *
 * <p><b>The four types a caller touches:</b>
 *
 * <ul>
 *   <li>{@link org.pumpkinlib.sim.PumpkinSim} — the registry, the tick, the battery, the boot stall
 *       report and the {@code MechanismGeometrySink} the mechanism domain installs.
 *   <li>{@link org.pumpkinlib.sim.MechanismSim} — one plant. {@code raw()} is the escape hatch to the
 *       WPILib model for the one-in-twenty thing PumpkinLib does not model.
 *   <li>{@link org.pumpkinlib.sim.SimMotors} — turns a vendor motor controller into a
 *       {@code SimMotorHandle} without this package naming a vendor type (ArchUnit rule 1).
 *   <li>{@link org.pumpkinlib.sim.HeadlessClock} — deterministic time, faster than real time, with no
 *       HAL. It installs itself into {@link org.pumpkinlib.core.compat.Clock}'s one time seam rather
 *       than becoming a second time authority (ArchUnit rule 3).
 * </ul>
 *
 * <p><b>maple-sim is discovered, never depended on (design/04 §7.5).</b>
 * {@link org.pumpkinlib.sim.PumpkinFieldSim} is a {@code ServiceLoader} lookup for an out-of-jar
 * adapter that does not exist yet, plus a kinematic fallback that roadmap risk R13 makes a documented,
 * supported configuration rather than a failure path. Nothing in this package imports maple-sim and no
 * build file names it.
 *
 * <p><b>What this package may not do.</b> ArchUnit rule 2 confines {@code RobotBase} to
 * {@code core.compat}, so the simulation gate is {@link org.pumpkinlib.core.compat.Platform#isSimulation()}
 * and never {@code RobotBase.isSimulation()}. Rule 3 confines the clock to
 * {@link org.pumpkinlib.core.compat.Clock}. Rule 1c keeps AdvantageKit's {@code Logger} out, so
 * everything published here goes through {@link org.pumpkinlib.telemetry.PumpkinLog}.
 */
package org.pumpkinlib.sim;

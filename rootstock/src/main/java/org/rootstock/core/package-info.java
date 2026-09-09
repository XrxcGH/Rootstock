/**
 * The front door: the lifecycle, the base class, the one registration call, and the two ways a
 * misconfigured robot still boots.
 *
 * <p>Four types and two failure valves:
 *
 * <ul>
 *   <li>{@link org.rootstock.core.RootstockLifecycle} — <b>public, and that is the point</b> (D29).
 *       Everything Rootstock does per loop, as an object a team drives by hand from their own
 *       {@code Robot}. This is what makes partial adoption real rather than asserted.
 *   <li>{@link org.rootstock.core.RootstockRobot} — the convenience base class, {@code extends
 *       LoggedRobot}, about twenty lines, every one of them a delegation to the lifecycle.
 *   <li>{@link org.rootstock.core.RootstockRegistry} — the one registration call. {@code addAll(m_drive,
 *       m_elevator, m_arm)} replaces four parallel lists, runs the global CAN-ID scan, prints the boot
 *       summary, and is the only place validation happens.
 *   <li>{@link org.rootstock.core.Rootstock} — the version, and the runtime kill switch that drops
 *       one named component to neutral with no code change and no Java redeploy.
 *   <li>{@link org.rootstock.core.SafeMode} — what a fatal config error does instead of an
 *       {@code ExceptionInInitializerError}: the robot boots, connects, publishes, and refuses to move.
 *   <li>{@link org.rootstock.core.RootstockException} — the rare genuine programming error, documented
 *       so that reaching for it is a decision rather than a habit.
 * </ul>
 *
 * <p><b>This package and three others may import AdvantageKit driver types</b> ({@code Logger},
 * {@code LoggedRobot}, {@code WPILOGWriter}, {@code NT4Publisher}, {@code LogFileUtil}) — ArchUnit
 * rule 1c names {@code org.rootstock.telemetry}, this root, {@code org.rootstock.tuning} and
 * {@code org.rootstock.viz}. Everywhere else in the library goes through {@code RootstockLog}.
 *
 * <p><b>What this package may not name</b> (ArchUnit rule 9, measured over signatures): nothing in
 * {@code telemetry}, {@code tuning}, {@code sim}, {@code vision}, {@code drive}, {@code auto},
 * {@code mechanism} or {@code superstructure}. Every dependency arrow points into core, and
 * {@code org.rootstock.core.spi} is the one downward edge. That is why
 * {@link org.rootstock.core.RootstockRegistry#addAll(java.lang.Object...)} takes {@code Object...} and
 * why the routing table is installed by the domains rather than declared here.
 */
package org.rootstock.core;

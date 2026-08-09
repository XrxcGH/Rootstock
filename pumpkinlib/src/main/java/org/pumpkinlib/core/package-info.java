/**
 * The front door: the lifecycle, the base class, the one registration call, and the two ways a
 * misconfigured robot still boots.
 *
 * <p>Four types and two failure valves:
 *
 * <ul>
 *   <li>{@link org.pumpkinlib.core.PumpkinLifecycle} — <b>public, and that is the point</b> (D29).
 *       Everything PumpkinLib does per loop, as an object a team drives by hand from their own
 *       {@code Robot}. This is what makes partial adoption real rather than asserted.
 *   <li>{@link org.pumpkinlib.core.PumpkinRobot} — the convenience base class, {@code extends
 *       LoggedRobot}, about twenty lines, every one of them a delegation to the lifecycle.
 *   <li>{@link org.pumpkinlib.core.PumpkinRegistry} — the one registration call. {@code addAll(m_drive,
 *       m_elevator, m_arm)} replaces four parallel lists, runs the global CAN-ID scan, prints the boot
 *       summary, and is the only place validation happens.
 *   <li>{@link org.pumpkinlib.core.PumpkinLib} — the version, and the runtime kill switch that drops
 *       one named component to neutral with no code change and no Java redeploy.
 *   <li>{@link org.pumpkinlib.core.SafeMode} — what a fatal config error does instead of an
 *       {@code ExceptionInInitializerError}: the robot boots, connects, publishes, and refuses to move.
 *   <li>{@link org.pumpkinlib.core.PumpkinException} — the rare genuine programming error, documented
 *       so that reaching for it is a decision rather than a habit.
 * </ul>
 *
 * <p><b>This package and three others may import AdvantageKit driver types</b> ({@code Logger},
 * {@code LoggedRobot}, {@code WPILOGWriter}, {@code NT4Publisher}, {@code LogFileUtil}) — ArchUnit
 * rule 1c names {@code org.pumpkinlib.telemetry}, this root, {@code org.pumpkinlib.tuning} and
 * {@code org.pumpkinlib.viz}. Everywhere else in the library goes through {@code PumpkinLog}.
 *
 * <p><b>What this package may not name</b> (ArchUnit rule 9, measured over signatures): nothing in
 * {@code telemetry}, {@code tuning}, {@code sim}, {@code vision}, {@code drive}, {@code auto},
 * {@code mechanism} or {@code superstructure}. Every dependency arrow points into core, and
 * {@code org.pumpkinlib.core.spi} is the one downward edge. That is why
 * {@link org.pumpkinlib.core.PumpkinRegistry#addAll(java.lang.Object...)} takes {@code Object...} and
 * why the routing table is installed by the domains rather than declared here.
 */
package org.pumpkinlib.core;

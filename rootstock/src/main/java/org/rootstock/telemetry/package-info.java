/**
 * The logging facade, its tiering, and the byte-budget governor.
 *
 * <p><b>This package writes to AdvantageKit's {@code Logger} directly. There is no backend SPI.</b>
 * {@code LogBackend}, the {@code Backend} enum and the NT4/Epilogue/DogLog/AdvantageKit/no-op backends
 * were deleted by maintainer decision 3, along with {@code RootstockInputs}, {@code RootstockLogTable},
 * {@code TelemetrySink} and the {@code @AutoInputs} annotation processor. What that bought is a
 * property rather than an option: deterministic replay is guaranteed, not configured. What it cost is
 * stated in {@link org.rootstock.telemetry.RootstockLog}'s own javadoc rather than in a footnote.
 *
 * <p><b>ArchUnit rule 1c names this package.</b> AdvantageKit's driver types — {@code Logger},
 * {@code LoggedRobot}, {@code WPILOGWriter}, {@code NT4Publisher}, {@code LogFileUtil} — are importable
 * from here, from {@code org.rootstock.core}, from {@code org.rootstock.tuning} and from
 * {@code org.rootstock.viz}, and nowhere else. Every other package in the library publishes through
 * {@link org.rootstock.telemetry.RootstockLog}. The point is blast radius: if AdvantageKit does not ship
 * for a future WPILib line, the fork contingency has a four-package surface instead of a twenty-package
 * one.
 *
 * <p><b>The arrow points into core, never out of it.</b> This package depends on
 * {@code org.rootstock.core.spi} for {@code LogConfig}, {@code RobotMode} and {@code Tier} — all three
 * live there so that a public core signature naming one is not a rule-9 violation — and telemetry still
 * owns what all three <i>mean</i>. {@link org.rootstock.telemetry.Demotable} stays here, because it is
 * never named in a core signature and moving a type that does not cross the boundary would dilute what
 * {@code core.spi} is for.
 *
 * <p>The three types a caller from another domain touches:
 *
 * <ul>
 *   <li>{@link org.rootstock.telemetry.RootstockLog} — the static facade. {@code critical}, {@code log},
 *       {@code debug}, {@code processInputs}, {@code timestamp}, {@code isReplay}. No {@code put}.
 *   <li>{@link org.rootstock.telemetry.TelemetrySource} — implemented by every mechanism, drivetrain
 *       and camera. Registration and declaration only; values are pushed, never pulled.
 *   <li>{@link org.rootstock.telemetry.Demotable} — the opt-in marking that lets the governor sample a
 *       key slower. Outputs only, forever.
 * </ul>
 */
package org.rootstock.telemetry;

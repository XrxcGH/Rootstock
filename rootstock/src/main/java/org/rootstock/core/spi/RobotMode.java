package org.rootstock.core.spi;

// D26's downward-crossing-value package (ArchUnit rule 9). Telemetry (design/04 section 2.2) is
// still the SOLE definition site for what these constants MEAN; only the package moved, by D33.
// A change to what a RobotMode means is a change to design/04, not to this file.

/**
 * Rootstock's view of the run mode.
 *
 * <p>{@link #REAL} and {@link #SIM} come from {@code Platform.isReal()}
 * ({@code org.rootstock.core.compat}) — <b>not</b> {@code RobotBase} directly, because ArchUnit
 * rule 2 confines the year-volatile WPILib call to {@code compat}. {@link #REPLAY} is true when
 * AdvantageKit has a replay source installed.
 *
 * <p>There is no "this mode is unavailable" path: all three always work. That is what maintainer
 * decision 3 bought by making AdvantageKit a required dependency rather than one backend among four.
 *
 * <p><b>Why this enum is in {@code core.spi} rather than {@code org.rootstock.telemetry}.</b>
 * {@link LogConfig} lives here, and its public signatures name this type ({@code mode()},
 * {@code withMode()}). Leaving it in telemetry would have left a {@code core.spi → telemetry} arrow —
 * the same rule-9 violation the {@code LogConfig} move was made to fix, one level down. It is a
 * behaviourless, dependency-free value enum, which is precisely the shape D26 created this package to
 * hold.
 */
public enum RobotMode {
  /** Running on real hardware. */
  REAL,
  /** Running under {@code simulateJava} or a unit test. */
  SIM,
  /** Replaying a log through AdvantageKit, with a replay source installed. */
  REPLAY
}

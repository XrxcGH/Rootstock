/**
 * Live tunable values: one poller, one queue drain, one logged input.
 *
 * <p>{@link org.rootstock.tuning.TuningRegistry} is the single entry point. Everything editable is
 * published as a plain NT4 double or boolean at {@code /Tuning/<namespace>/<key>} and nowhere else,
 * which is the one path that works in both AdvantageScope's tuning mode and Elastic's editable
 * widgets with zero setup.
 *
 * <p>Three properties this package exists to guarantee:
 *
 * <ul>
 *   <li><b>One JNI call per loop, regardless of tunable count</b> — a single
 *       {@code NetworkTableListenerPoller} drained once per loop, not one subscriber per tunable.
 *   <li><b>Default-deny under FMS, in constant time</b> — {@code FmsPolicy.tunablesLocked()} is the
 *       gate, and a disabled tunable returns its compile-time default with zero NT traffic.
 *   <li><b>Replay safety</b> — every wire value crosses into robot code through one
 *       {@code LoggableInputs} struct and one {@code processInputs} call, so a replayed run
 *       reproduces the dashboard values that were live at the time instead of silently falling back
 *       to the jar's defaults.
 * </ul>
 *
 * <p>This package is one of the four ArchUnit rule 1c allows to name AdvantageKit's driver types
 * directly; every other package publishes through {@code RootstockLog}.
 */
package org.rootstock.tuning;

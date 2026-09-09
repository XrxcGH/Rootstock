/**
 * Replay safety: the runtime tripwire and the two annotations the build-time rules key off.
 *
 * <p>Replay safety has two halves and only one of them ships to every team.
 *
 * <p><b>Build time</b> is {@code rootstock-lint}, a javac annotation processor that turns the ten
 * replay rules into compiler diagnostics. It cannot be delivered by a vendordep — a dependency cannot
 * add an {@code annotationProcessor} line to a consumer's {@code build.gradle} — so a team that forked
 * {@code RootstockTemplate} gets it and a team that added Rootstock as a bare dependency adds one line
 * itself. That is a real hole in the enforcement story and it is not closable from the library side.
 * {@link org.rootstock.telemetry.replay.ReplayExempt} and
 * {@link org.rootstock.telemetry.replay.IoImplementation} are that processor's vocabulary, and they
 * live in the shipped jar so annotating code costs nothing when the processor is absent.
 *
 * <p><b>Runtime</b> is {@link org.rootstock.telemetry.replay.RootstockReplay}, which every team gets
 * because it is in the jar. It arms itself only in {@code REPLAY} mode and it exists to make a
 * non-reproducible run <i>fail loudly</i> rather than produce a log that silently lies — the failure
 * mode that makes non-deterministic replay engines useless, where there is no way to tell the accurate
 * outputs from the diverged ones.
 *
 * <p>Neither half throws from a periodic path. The tripwire raises a sticky alert naming the class and
 * the fix; {@link org.rootstock.telemetry.replay.RootstockReplay#assertDeterministic()} is what a test
 * calls when a throw is the point.
 */
package org.rootstock.telemetry.replay;

/**
 * The automatic telemetry schema: the key layout every Rootstock mechanism, drivetrain and vision
 * source publishes, the writers that fill it, and the emitter that makes it diffable between
 * releases.
 *
 * <h2>The promise</h2>
 *
 * <p>Every Rootstock mechanism, drivetrain and vision source logs a consistent schema with
 * <b>zero user code</b> — setpoint, goal, measured, error, output, current, temperature, limits,
 * state. That consistency is not tidiness. It is the precondition for three things that are
 * otherwise impossible:
 *
 * <ol>
 *   <li><b>Five-minute triage.</b> "Why did auto fail in match 42" is answerable in one sitting only
 *       because the four traces the answer lives in — {@code Setpoint}, {@code Measured},
 *       {@code Error}, {@code AtGoal} — are in the same place with the same names on every robot.
 *   <li><b>Shipped dashboard layouts.</b> An AdvantageScope layout can only be shipped if the author
 *       knows the key names on a robot they have never seen.
 *   <li><b>Reading a teammate's mechanism.</b> A student who has learned one mechanism's graph has
 *       learned all of them.
 * </ol>
 *
 * <h2>What is in here</h2>
 *
 * <ul>
 *   <li>{@link org.rootstock.telemetry.schema.MechanismSchema} — section 3.1,
 *       {@code Rootstock/<Name>/}. The flagship. Also the writer for a swerve module's nested block.
 *   <li>{@link org.rootstock.telemetry.schema.DriveSchema} — section 3.2, {@code Rootstock/Drive/}.
 *   <li>{@link org.rootstock.telemetry.schema.VisionSchema} — section 3.3,
 *       {@code Rootstock/Vision/<Camera>/} and its roll-ups.
 *   <li>{@link org.rootstock.telemetry.schema.FieldSchema} — section 3.4, {@code Rootstock/Field/},
 *       the ghost contract.
 *   <li>{@link org.rootstock.telemetry.schema.HealthSchema} — section 3.5, {@code Rootstock/Health/}.
 *   <li>{@link org.rootstock.telemetry.schema.ProvenanceSchema} — section 3.6, the twelve
 *       write-once metadata fields.
 *   <li>{@link org.rootstock.telemetry.schema.SchemaGeneration} — the emitter and its fingerprint.
 * </ul>
 *
 * <h2>Why writers and not just constants</h2>
 *
 * <p>A package of key-name constants makes the right thing possible; a package of writers makes it
 * the default. Three properties fall out of the writer shape and could not be had from constants
 * alone:
 *
 * <ul>
 *   <li><b>{@code Error} and {@code GoalError} cannot be collapsed.</b> They are derived inside
 *       {@link org.rootstock.telemetry.schema.MechanismSchema#publish()} from goal, setpoint and
 *       measured, so no mechanism author writes either formula. An earlier revision of the design
 *       did collapse them, and the pair is now pinned by a test on both sides.
 *   <li><b>Tiers and units cannot drift from the published table.</b> The writer chooses
 *       {@code critical} versus {@code log} and attaches the declared unit; a call site cannot pick
 *       a different tier for {@code Setpoint} than the schema says it has.
 *   <li><b>No per-cycle string concatenation.</b> Every absolute key is built once, at registration,
 *       and cached. Seventeen keys per mechanism at 50 Hz is exactly the kind of garbage the tier
 *       design exists to avoid.
 * </ul>
 *
 * <h2>This package is written before the domains that call it</h2>
 *
 * <p>{@code org.rootstock.mechanism}, {@code org.rootstock.drive} and {@code org.rootstock.vision}
 * do not exist yet. That is deliberate and it is the right order: the schema is the contract those
 * domains implement, so it is written, published and diffable first. Nothing here references them.
 */
package org.rootstock.telemetry.schema;

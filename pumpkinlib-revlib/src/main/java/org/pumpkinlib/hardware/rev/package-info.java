/**
 * The REVLib adapter: SPARK MAX and SPARK Flex, and nothing else.
 *
 * <p>This is the only package in PumpkinLib allowed to import {@code com.revrobotics} (ArchUnit
 * rule 1). A REV-only team installs {@code dev.pumpkinlib:pumpkinlib-revlib} alongside REV's own
 * vendordep and never sees a CTRE class; the design criticises vendordeps that force Phoenix onto
 * REV-only teams and this artifact is the answer to that criticism.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link org.pumpkinlib.hardware.rev.SparkMotorIO} — the backend. Owns the device config
 *       object and derives every unit conversion in its {@code buildConfig} javadoc.
 *   <li>{@link org.pumpkinlib.hardware.rev.RevGainSink} — the <b>only</b> code that converts
 *       canonical volts-per-SI gains into REV units.
 *   <li>{@link org.pumpkinlib.hardware.rev.RevUtil} — the two, and only two, ways this library
 *       writes configuration to a SPARK: blocking-and-verified, and fire-and-forget.
 *   <li>{@link org.pumpkinlib.hardware.rev.SparkAbsoluteEncoderIO} — a data-port absolute encoder,
 *       read in output-shaft rotations.
 *   <li>{@link org.pumpkinlib.hardware.rev.RevBackend} — the {@code ServiceLoader} entry point.
 * </ul>
 *
 * <h2>The three places this adapter is honest about a gap rather than papering over it</h2>
 *
 * <ol>
 *   <li><b>No supply current, no torque current.</b> REVLib publishes neither, so {@code
 *       MotorInputs} carries {@code NaN} for both rather than a plausible-looking estimate.
 *   <li><b>Reset detection is a sticky warning, not a per-call flag.</b> {@code
 *       getStickyWarnings().hasReset} exists and is used, but re-arming it costs every other sticky
 *       bit — so the unread ones are captured into the alert text before the clear.
 *   <li><b>Simulation is not at Phoenix parity.</b> {@code SparkSim.iterate} integrates position
 *       itself and adds filter lag and noise, and there is no raw-rotor write. The device's own
 *       conversion factors, profile and gravity feedforward <em>are</em> exercised, which is the
 *       part that matters; bit-exact timing is not.
 * </ol>
 *
 * <p>Every vendor API named in this package was verified against the REVLib 2026.0.5 jar and its
 * shipped sources with {@code javap} before it was written. Where a behaviour is documented by REV
 * but has not been observed on hardware by this library, the javadoc says
 * {@code [UNVERIFIED-BY-EXECUTION]} and means it.
 */
package org.pumpkinlib.hardware.rev;

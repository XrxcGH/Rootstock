package org.pumpkinlib.hardware.rev;

import static edu.wpi.first.units.Units.Rotations;

import com.revrobotics.spark.SparkAbsoluteEncoder;
import com.revrobotics.spark.SparkBase;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.config.FeedbackSpec;
import org.pumpkinlib.hardware.AbsoluteEncoderIO;

/**
 * A REV absolute encoder on a SPARK's data port, read in output-shaft rotations.
 *
 * <p>Everything above this class asks one question — "where is the mechanism, absolutely, and do I
 * trust the number?" — and gets it in output-shaft rotations with the offset already applied. This
 * is the SPARK data-port answer to it.
 *
 * <h2>The offset is applied on the roboRIO, deliberately</h2>
 *
 * <p>REVLib has a perfectly good {@code AbsoluteEncoderConfig.zeroOffset(...)} and this class does
 * not use it, for a reason worth stating: a SPARK's configuration is <b>declarative and total</b>.
 * {@link SparkMotorIO} owns the config object for the device and applies it with {@code
 * kResetSafeParameters}, which resets every parameter not present in the config being applied. A
 * second object pushing a second declarative config to the same controller would silently reset the
 * conversion factors, the current limit and the gains that the motor backend had just established.
 * Two owners of one device config is a defect that shows up as "the elevator's gear ratio randomly
 * reverts", and the cheapest way to not have it is to have one owner.
 *
 * <p>So the offset, the inversion and the sensor-to-output gearing are arithmetic here, on numbers
 * REVLib hands over raw. {@code rawPositionRot} is logged alongside the corrected value because it
 * is the number a student reads off AdvantageScope while holding the mechanism at its hard stop,
 * and having it in the log turns re-capturing a magnet offset from a twenty-minute job into a
 * two-minute one.
 *
 * <h2>Units, verified</h2>
 *
 * <p>From the REVLib 2026.0.5 sources: {@code AbsoluteEncoderConfig.positionConversionFactor} —
 * <i>"Position is returned in native units of rotations"</i>; {@code velocityConversionFactor} —
 * <i>"Velocity is returned in native units of rotations per minute"</i>. This class configures
 * neither factor, so both natives are what {@code getPosition()} and {@code getVelocity()} return
 * and the conversion below is exactly {@code rotations} and {@code RPM / 60}.
 *
 * <h2>Connection health</h2>
 *
 * <p>REVLib exposes no per-sensor "is the encoder plugged in" bit. What it does expose is the
 * SPARK's {@code sensor} fault, which is set when the controller cannot read its configured
 * feedback device — so that, plus the controller answering at all, is what {@code connected}
 * reports. <b>[UNVERIFIED-BY-EXECUTION]</b> that the {@code sensor} fault bit is raised for an
 * unplugged data-port absolute encoder specifically, as opposed to only for the primary encoder;
 * the honest consequence is that a disconnected absolute encoder may report connected, so a
 * mechanism must still cross-check against the motor's own sensor rather than trusting this flag
 * alone.
 */
public final class SparkAbsoluteEncoderIO implements AbsoluteEncoderIO {

  private final SparkBase m_spark;
  private final SparkAbsoluteEncoder m_encoder;
  private final double m_zeroOffsetRot;
  private final boolean m_inverted;
  private final double m_sensorPerOutput;
  private final String m_owner;

  /**
   * The usual case: a REV Through Bore or MagEncoder plugged into the data port, on the mechanism
   * shaft.
   *
   * @param spark the controller the encoder is plugged into — pass {@link SparkMotorIO#leader()},
   *     so the encoder and the motor are the same device rather than two handles racing to
   *     configure one controller
   * @param spec the declared offset and inversion
   */
  public SparkAbsoluteEncoderIO(SparkBase spark, FeedbackSpec.SparkAbsolute spec) {
    this(spark, spec, 1.0, null);
  }

  /**
   * The full form, for an absolute encoder that is geared to the output shaft rather than sitting
   * on it.
   *
   * @param spark the controller the encoder is plugged into
   * @param spec the declared offset and inversion
   * @param sensorRotationsPerOutputRotation how many turns the sensor makes per turn of the output
   *     shaft — {@code 1.0} when it is on the joint, which is the only arrangement that gives a
   *     genuinely absolute reading over more than one output rotation
   * @param owner the mechanism name, for {@link #describe()}; null takes the device's name
   */
  public SparkAbsoluteEncoderIO(
      SparkBase spark,
      FeedbackSpec.SparkAbsolute spec,
      double sensorRotationsPerOutputRotation,
      String owner) {
    m_spark = Objects.requireNonNull(spark, "SparkAbsoluteEncoderIO: spark must not be null");
    Objects.requireNonNull(spec, "SparkAbsoluteEncoderIO: feedback spec must not be null");
    m_encoder = spark.getAbsoluteEncoder();
    m_zeroOffsetRot = spec.zeroOffset().in(Rotations);
    m_inverted = spec.inverted();
    m_sensorPerOutput =
        Double.isFinite(sensorRotationsPerOutputRotation)
                && sensorRotationsPerOutputRotation != 0.0
            ? sensorRotationsPerOutputRotation
            : 1.0;
    m_owner = owner == null ? "SPARK " + spark.getDeviceId() + " absolute encoder" : owner;
  }

  /**
   * Read the encoder and fill {@code inputs}, in output-shaft rotations.
   *
   * <p>The corrected position is wrapped into {@code [0, 1)} <em>before</em> the sensor-to-output
   * division, which is the only order that is correct: wrapping is a property of the sensor's
   * single turn, not of the mechanism's travel, and wrapping afterwards would fold a geared
   * mechanism's range on top of itself.
   *
   * @param inputs the inputs object to fill; the same instance every loop
   */
  @Override
  public void updateInputs(AbsoluteEncoderInputs inputs) {
    if (inputs == null) {
      return;
    }
    double raw = m_encoder.getPosition();
    inputs.rawPositionRot = raw;
    double signed = m_inverted ? -raw : raw;
    inputs.absolutePositionRot = wrapUnitInterval(signed - m_zeroOffsetRot) / m_sensorPerOutput;
    // Native velocity is RPM (verified above); the sign follows the same inversion as position.
    double rpm = m_inverted ? -m_encoder.getVelocity() : m_encoder.getVelocity();
    inputs.velocityRps = rpm / 60.0 / m_sensorPerOutput;
    inputs.connected = RevUtil.connected(m_spark) && !m_spark.getFaults().sensor;
  }

  /**
   * What this encoder is and what arithmetic is being done to it.
   *
   * @return a one-line description for the boot dump
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s: SPARK data-port absolute encoder on CAN %d, zero offset %.6f rot, %s, %.4f sensor"
            + " rotations per output rotation. Offset and inversion are applied on the roboRIO,"
            + " not written to the device, because SparkMotorIO owns that controller's config.",
        m_owner,
        m_spark.getDeviceId(),
        m_zeroOffsetRot,
        m_inverted ? "inverted" : "not inverted",
        m_sensorPerOutput);
  }

  private static double wrapUnitInterval(double rotations) {
    if (!Double.isFinite(rotations)) {
      return Double.NaN;
    }
    double wrapped = rotations % 1.0;
    return wrapped < 0.0 ? wrapped + 1.0 : wrapped;
  }
}

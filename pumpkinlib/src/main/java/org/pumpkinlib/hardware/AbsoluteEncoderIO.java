package org.pumpkinlib.hardware;

import java.util.Optional;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * The absolute-position seam.
 *
 * <p><b>What is abstracted:</b> the <i>question</i> "what is the mechanism's absolute position, and
 * do I trust it?" <b>What is passed through:</b> everything else, via {@link #as(Class)}.
 *
 * <p>Everything above this interface reads {@link AbsoluteEncoderInputs#absolutePositionRot} in
 * output-shaft rotations with the magnet offset already applied. Whether that came from a CANcoder
 * fused into a TalonFX at rotor bandwidth, a CANcoder read remotely, a REV absolute encoder on a
 * SPARK's data port, or a Through Bore encoder read as a raw duty cycle on a DIO channel is the
 * backend's problem — but it is <b>not</b> hidden, because it changes what the mechanism has to do.
 * A fused encoder needs no roboRIO-side re-seed; the other three need the guarded re-seed (seed at
 * boot and immediately before a move, <b>never during one</b>, because shifting the reference frame
 * mid-motion makes the mechanism land off target).
 *
 * <p>{@link AbsoluteEncoderInputs#rawPositionRot} is deliberately logged alongside the offset value:
 * it is the number a student reads at the hard stop when capturing a magnet offset, and having it in
 * the log is the difference between "capture the offset again" being a two-minute job and a
 * twenty-minute one.
 */
public interface AbsoluteEncoderIO {

  /**
   * Refresh the encoder's signals and fill {@code inputs}.
   *
   * @param inputs the inputs object to fill; the same instance every loop
   */
  void updateInputs(AbsoluteEncoderInputs inputs);

  /**
   * The typed escape hatch — reach the real {@code CANcoder}, {@code DutyCycleEncoder} or {@code
   * AbsoluteEncoder}.
   *
   * @param <T> the concrete IO type being asked for
   * @param type the backend class
   * @return this IO as that type, or empty if it is a different backend
   */
  default <T extends AbsoluteEncoderIO> Optional<T> as(Class<T> type) {
    return type != null && type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
  }

  /**
   * Everything read from an absolute encoder, in OUTPUT-SHAFT ROTATIONS.
   *
   * <p>Hand-written {@code toLog}/{@code fromLog} for the same reason {@link MotorInputs} has them:
   * an annotation processor cannot be installed into a consuming team's build by a vendordep. The
   * key names are the log schema and are frozen.
   */
  class AbsoluteEncoderInputs implements LoggableInputs {

    /** Whether the encoder is answering. */
    public boolean connected = false;

    /** Absolute position in output-shaft rotations, with the magnet offset ALREADY applied. */
    public double absolutePositionRot = Double.NaN;

    /** Absolute velocity in output-shaft rotations per second. */
    public double velocityRps = Double.NaN;

    /**
     * Position before the offset — what you read at the hard stop.
     *
     * <p>This is the number you copy into the config when re-capturing a magnet offset, so it is
     * logged rather than left to be rediscovered with a print statement at 11pm.
     */
    public double rawPositionRot = Double.NaN;

    /**
     * Writes every field under its frozen schema name.
     *
     * @param t the table for this encoder's inputs subtable
     */
    @Override
    public void toLog(LogTable t) {
      t.put("Connected", connected);
      t.put("AbsolutePositionRot", absolutePositionRot);
      t.put("VelocityRps", velocityRps);
      t.put("RawPositionRot", rawPositionRot);
    }

    /**
     * Reads every field back during replay.
     *
     * @param t the table for this encoder's inputs subtable
     */
    @Override
    public void fromLog(LogTable t) {
      connected = t.get("Connected", connected);
      absolutePositionRot = t.get("AbsolutePositionRot", absolutePositionRot);
      velocityRps = t.get("VelocityRps", velocityRps);
      rawPositionRot = t.get("RawPositionRot", rawPositionRot);
    }
  }
}

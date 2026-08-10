package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Rotations;

import edu.wpi.first.units.measure.Angle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.pumpkinlib.control.PositionReference;
import org.pumpkinlib.core.config.CanIdRegistry;

/**
 * What actually measures the mechanism's position, and therefore whether that position can be
 * trusted at boot.
 *
 * <p>The variants map to how the hardware is <em>plumbed</em>, not to an abstract idea of "encoder",
 * because the plumbing is what decides the code path. A CANcoder fused into a TalonFX needs no
 * software re-seed at all; the same CANcoder read remotely needs the guarded re-seed below; a REV
 * Through Bore on a DIO port needs the same re-seed plus its own device. One abstraction over all
 * three would have to do the most conservative thing for every one of them, which is how a fused
 * arm ends up with a Java re-seed fighting the device's own fusion mid-motion.
 *
 * <h2>The guarded re-seed</h2>
 *
 * <p>Seed the motor's internal sensor from the absolute encoder at boot, and again immediately
 * before starting a move — but <b>never during a move</b>, because shifting the reference frame
 * mid-motion makes the mechanism land off target. This rule was hard-won on a real robot and the
 * library owns it so nobody has to rediscover it. {@link #needsGuardedReseed()} is what selects it,
 * and it is deliberately false for {@link FusedCancoder}: Phoenix already fuses on-device, and
 * {@code describe()} says so, so a student can see why the code path differs.
 *
 * <h2>The ratio identity</h2>
 *
 * <p>For the two CANcoder variants, {@code rotorPerSensor × sensorPerOutput} <b>must</b> equal the
 * gearbox's rotor-per-output ratio. Phoenix requires it; getting it wrong scales every angle the
 * mechanism reads and commands, and applies cosine gravity at the wrong angle over the whole range.
 * The identity is checked as a fatal config error rather than left as a comment, because a correct
 * literal is one review away from drifting.
 */
public sealed interface FeedbackSpec
    permits
        FeedbackSpec.RotorOnly,
        FeedbackSpec.FusedCancoder,
        FeedbackSpec.RemoteCancoder,
        FeedbackSpec.SparkAbsolute,
        FeedbackSpec.DioAbsolute {

  /**
   * Whether an absolute position source is present, so the mechanism knows where it is at boot
   * without moving.
   *
   * @return false only for {@link RotorOnly}
   */
  boolean isAbsolute();

  /**
   * Whether the device itself fuses the absolute sensor with the rotor.
   *
   * <p>True only for {@link FusedCancoder}. When true, PumpkinLib must do nothing in Java — the
   * seeding, the wrap handling and the ratio are all the device's job.
   *
   * @return true if fusion happens on the device
   */
  boolean isFusedOnDevice();

  /**
   * Whether this source needs the guarded re-seed described on this interface.
   *
   * @return true for the remote CANcoder, the SPARK absolute encoder and the DIO absolute encoder
   */
  boolean needsGuardedReseed();

  /**
   * Whether the mechanism must home before its position means anything.
   *
   * @return true only for {@link RotorOnly}
   */
  default boolean requiresHoming() {
    return !isAbsolute();
  }

  /**
   * A short name for the sensor, for alerts and the boot dump.
   *
   * @return for example {@code "CANcoder 23 (rio), fused"}
   */
  String sensorDescription();

  /**
   * This spec expressed in the vocabulary the tuning supervisor uses to decide whether a measured
   * position can be trusted right now.
   *
   * <p>The mapping is the whole point of having two types: config describes the wiring, control
   * describes the trust. A rotor-only mechanism becomes trustworthy only once its homing routine has
   * run, which is why this returns the un-homed form and the mechanism substitutes the homed one.
   *
   * @return the control-side position reference
   */
  PositionReference positionReference();

  /**
   * Every problem visible from this spec alone.
   *
   * <p><b>Never throws, never returns null.</b> The cross-check against the gearbox ratio needs the
   * reduction as well, so it lives in validation rather than here.
   *
   * @return the problems, in declaration order; empty when the spec is fine
   */
  List<String> problems();

  /**
   * One line naming the sensor, its wiring and which code path it selects.
   *
   * @return a human-readable description
   */
  String describe();

  /**
   * This spec as a CAN device declaration, for the one global duplicate-ID scan.
   *
   * @param owner the mechanism that declares this sensor
   * @return the device, or empty when the sensor is not on a CAN bus
   */
  default Optional<CanIdRegistry.Device> canDevice(String owner) {
    return Optional.empty();
  }

  /**
   * The rotor-to-sensor gearing, or {@code NaN} when this variant has none.
   *
   * <p>Only the two CANcoder variants carry gearing; every other source either sits on the joint or
   * is the rotor. {@code NaN} rather than {@code 1.0} so that a check written against this can tell
   * "not applicable" from "one to one".
   *
   * @return rotor rotations per sensor rotation, or {@code NaN}
   */
  default double rotorPerSensor() {
    return Double.NaN;
  }

  /**
   * The sensor-to-output gearing, or {@code NaN} when this variant has none.
   *
   * @return sensor rotations per output rotation, or {@code NaN}
   */
  default double sensorPerOutput() {
    return Double.NaN;
  }

  // ===============================================================================================
  // Variants
  // ===============================================================================================

  /**
   * The motor's internal rotor, and nothing else.
   *
   * <p>Position is <em>relative</em>: at boot the mechanism believes it is at zero wherever it
   * happens to be. That is fine — it is the right answer for an elevator with a hard stop — but it
   * makes a homing strategy mandatory, and validation says so with a message that names the two
   * ways out (add an absolute encoder, or add a homing routine).
   */
  record RotorOnly() implements FeedbackSpec {

    @Override
    public boolean isAbsolute() {
      return false;
    }

    @Override
    public boolean isFusedOnDevice() {
      return false;
    }

    @Override
    public boolean needsGuardedReseed() {
      return false;
    }

    @Override
    public String sensorDescription() {
      return "motor rotor";
    }

    @Override
    public PositionReference positionReference() {
      return new PositionReference.RotorOnly();
    }

    @Override
    public List<String> problems() {
      return List.of();
    }

    @Override
    public String describe() {
      return "rotor only — position is RELATIVE, so it is whatever the mechanism was sitting at "
          + "when the robot booted. A homing strategy is required.";
    }
  }

  /**
   * A CANcoder fused into the TalonFX on the device. The right answer for a Phoenix arm or turret.
   *
   * <p>The device combines the CANcoder's absolute reading with the rotor's resolution, so the
   * mechanism knows where it is at boot <em>and</em> has a high-resolution velocity signal, with no
   * Java code in the path at all.
   *
   * @param cancoderId the CANcoder's CAN device id
   * @param canBus the CAN bus name; blank becomes {@value MotorSpec#kDefaultBus}
   * @param magnetOffset the offset that makes the sensor read the mechanism's zero at its zero
   * @param rotorPerSensor gearing between the rotor and the CANcoder
   * @param sensorPerOutput gearing between the CANcoder and the output shaft; 1.0 when the CANcoder
   *     is on the joint, which is the usual and the recommended arrangement
   */
  record FusedCancoder(
      int cancoderId,
      String canBus,
      Angle magnetOffset,
      double rotorPerSensor,
      double sensorPerOutput)
      implements FeedbackSpec {

    /** Normalises the bus name and rejects a null offset. */
    public FusedCancoder {
      canBus = Specs.normaliseBus(canBus);
      magnetOffset = Objects.requireNonNull(magnetOffset, Cancoders.kNullOffset);
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public boolean isFusedOnDevice() {
      return true;
    }

    @Override
    public boolean needsGuardedReseed() {
      return false;
    }

    @Override
    public String sensorDescription() {
      return "CANcoder " + cancoderId + " (" + canBus + "), fused into the TalonFX";
    }

    @Override
    public PositionReference positionReference() {
      return new PositionReference.FusedAbsolute(sensorDescription());
    }

    @Override
    public Optional<CanIdRegistry.Device> canDevice(String owner) {
      return Optional.of(
          CanIdRegistry.Device.on(owner, "feedback", "CANcoder", cancoderId, canBus));
    }

    @Override
    public List<String> problems() {
      return Cancoders.problems(this, cancoderId, canBus, rotorPerSensor, sensorPerOutput);
    }

    @Override
    public String describe() {
      return sensorDescription()
          + Cancoders.describeRatios(rotorPerSensor, sensorPerOutput, magnetOffset)
          + ". Phoenix fuses on-device, so PumpkinLib does NOT re-seed in Java.";
    }
  }

  /**
   * A CANcoder read remotely, without on-device fusion — the arrangement a team without Phoenix Pro
   * has.
   *
   * <p>Everything the fused variant gets for free is now software's job, which is why this variant
   * selects the guarded re-seed.
   *
   * @param cancoderId the CANcoder's CAN device id
   * @param canBus the CAN bus name; blank becomes {@value MotorSpec#kDefaultBus}
   * @param magnetOffset the offset that makes the sensor read the mechanism's zero at its zero
   * @param rotorPerSensor gearing between the rotor and the CANcoder
   * @param sensorPerOutput gearing between the CANcoder and the output shaft
   */
  record RemoteCancoder(
      int cancoderId,
      String canBus,
      Angle magnetOffset,
      double rotorPerSensor,
      double sensorPerOutput)
      implements FeedbackSpec {

    /** Normalises the bus name and rejects a null offset. */
    public RemoteCancoder {
      canBus = Specs.normaliseBus(canBus);
      magnetOffset = Objects.requireNonNull(magnetOffset, Cancoders.kNullOffset);
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public boolean isFusedOnDevice() {
      return false;
    }

    @Override
    public boolean needsGuardedReseed() {
      return true;
    }

    @Override
    public String sensorDescription() {
      return "CANcoder " + cancoderId + " (" + canBus + "), read remotely";
    }

    @Override
    public PositionReference positionReference() {
      return new PositionReference.Absolute(sensorDescription());
    }

    @Override
    public Optional<CanIdRegistry.Device> canDevice(String owner) {
      return Optional.of(
          CanIdRegistry.Device.on(owner, "feedback", "CANcoder", cancoderId, canBus));
    }

    @Override
    public List<String> problems() {
      return Cancoders.problems(this, cancoderId, canBus, rotorPerSensor, sensorPerOutput);
    }

    @Override
    public String describe() {
      return sensorDescription()
          + Cancoders.describeRatios(rotorPerSensor, sensorPerOutput, magnetOffset)
          + ". No on-device fusion, so PumpkinLib re-seeds the rotor from it at boot and before "
          + "each move — never during one.";
    }
  }

  /**
   * A REV absolute encoder on a SPARK's data port.
   *
   * <p>The SPARK reports the absolute angle directly, but it does not fuse it, so this selects the
   * guarded re-seed.
   *
   * @param zeroOffset the reading the sensor gives when the mechanism is at its zero
   * @param inverted whether the sensor counts the opposite way from the mechanism
   */
  record SparkAbsolute(Angle zeroOffset, boolean inverted) implements FeedbackSpec {

    /** Rejects a null offset. */
    public SparkAbsolute {
      zeroOffset = Objects.requireNonNull(zeroOffset, Cancoders.kNullOffset);
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public boolean isFusedOnDevice() {
      return false;
    }

    @Override
    public boolean needsGuardedReseed() {
      return true;
    }

    @Override
    public String sensorDescription() {
      return "SPARK data-port absolute encoder";
    }

    @Override
    public PositionReference positionReference() {
      return new PositionReference.Absolute(sensorDescription());
    }

    @Override
    public List<String> problems() {
      return List.of();
    }

    @Override
    public String describe() {
      return sensorDescription()
          + String.format(Locale.ROOT, ", zero offset %.4f rot", zeroOffset.in(Rotations))
          + (inverted ? ", inverted" : "")
          + ". PumpkinLib re-seeds the rotor from it at boot and before each move.";
    }
  }

  /**
   * A REV Through Bore (or any duty-cycle encoder) read straight off a roboRIO DIO channel.
   *
   * <p>This is a <b>separate device from the motor</b>: nothing on the CAN bus knows it exists, so
   * every conversion, the wrap handling and the re-seed are all in Java, and a disconnected sensor
   * looks like a plausible number rather than a fault. It works, it is cheap, and the boot dump says
   * exactly what it costs.
   *
   * @param dioChannel the roboRIO DIO channel
   * @param zeroOffset the reading the sensor gives when the mechanism is at its zero
   * @param inverted whether the sensor counts the opposite way from the mechanism
   */
  record DioAbsolute(int dioChannel, Angle zeroOffset, boolean inverted) implements FeedbackSpec {

    /** Rejects a null offset. */
    public DioAbsolute {
      zeroOffset = Objects.requireNonNull(zeroOffset, Cancoders.kNullOffset);
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public boolean isFusedOnDevice() {
      return false;
    }

    @Override
    public boolean needsGuardedReseed() {
      return true;
    }

    @Override
    public String sensorDescription() {
      return "duty-cycle absolute encoder on DIO " + dioChannel;
    }

    @Override
    public PositionReference positionReference() {
      return new PositionReference.Absolute(sensorDescription());
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (dioChannel < 0 || dioChannel > 25) {
        out.add(
            sensorDescription()
                + ": DIO channel "
                + dioChannel
                + " is outside the addressable range 0..25. Fix: use the channel number printed "
                + "next to the header the encoder is plugged into (0..9 on the roboRIO itself).");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return sensorDescription()
          + String.format(Locale.ROOT, ", zero offset %.4f rot", zeroOffset.in(Rotations))
          + (inverted ? ", inverted" : "")
          + ". Separate device from the motor, so PumpkinLib re-seeds the rotor from it at boot "
          + "and before each move — never during one.";
    }
  }
}

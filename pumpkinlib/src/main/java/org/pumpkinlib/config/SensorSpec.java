package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Milliseconds;
import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.pumpkinlib.core.config.CanIdRegistry;

/**
 * A sensor that answers one boolean question: <em>is the thing there?</em>
 *
 * <p>A limit switch, a beam break, a time-of-flight range sensor and "the motor is pulling more
 * current than it would if the intake were empty" are physically nothing alike, but a mechanism asks
 * all four the same question, so they get one type. The honest extra channel — a distance, when the
 * sensor can actually measure one — is carried on the reading rather than by splitting the type.
 *
 * <h2>The one that matters: {@link #motorLimit(Limit)}</h2>
 *
 * <p>A limit switch wired <b>into the motor controller</b> stops the motor in firmware, with no
 * roboRIO in the loop and therefore no latency and no dependency on robot code still running. It is
 * always the preferred wiring, and declaring one here is also what causes the backend to subscribe
 * the corresponding status signal — so "zero extra CAN traffic, the motor already reports it" is a
 * true statement rather than a frozen field that reads {@code false} forever.
 *
 * <p>A switch on a roboRIO DIO channel cannot stop the motor in firmware. PumpkinLib zeroes the
 * output in the same loop and raises a config-time warning naming the latency difference, so the
 * choice is visible rather than assumed.
 *
 * <h2>Level, never edge</h2>
 *
 * <p>Every reading from every variant is a <b>debounced level</b>. That is not a detail: a real
 * intake command that fires on the rising edge does nothing at all when the game piece was already
 * present at the moment the command started, which is a five-line workaround in somebody's robot
 * code right now.
 */
public sealed interface SensorSpec
    permits
        SensorSpec.Dio,
        SensorSpec.MotorLimit,
        SensorSpec.CanRange,
        SensorSpec.Candi,
        SensorSpec.StatorCurrent,
        SensorSpec.Sim {

  /**
   * The debounce applied when a variant does not carry its own.
   *
   * <p>One control loop is 20 ms; two of them is enough to reject a contact bounce or a single
   * noisy frame without being felt by a driver.
   */
  Time kDefaultDebounce = Milliseconds.of(40);

  /** Which end of travel a controller-wired limit switch is on. */
  enum Limit {
    /** The switch at the increasing-position end. */
    FORWARD,
    /** The switch at the decreasing-position end. */
    REVERSE;

    /**
     * The same end expressed as a {@link HardStop}, which is how position limits name it.
     *
     * @return the matching hard stop
     */
    public HardStop toHardStop() {
      return this == FORWARD ? HardStop.FORWARD : HardStop.REVERSE;
    }
  }

  /** Which digital input on a CANdi. */
  enum CandiChannel {
    /** The S1 input. */
    S1,
    /** The S2 input. */
    S2
  }

  /**
   * How long the raw reading must hold before the level changes.
   *
   * @return the debounce window
   */
  Time debounce();

  /**
   * Whether this sensor is the motor controller's own limit-switch input.
   *
   * <p>True here means the stop happens in firmware, and it means the backend must subscribe the
   * limit status signal.
   *
   * @return true for {@link MotorLimit}
   */
  boolean isMotorLimit();

  /**
   * Whether this sensor reports a distance as well as a level.
   *
   * @return true for {@link CanRange}
   */
  boolean measuresDistance();

  /**
   * Every problem visible from this spec alone.
   *
   * <p><b>Never throws, never returns null.</b>
   *
   * @return the problems, in declaration order; empty when the spec is fine
   */
  List<String> problems();

  /**
   * One line naming the sensor, its wiring and what it costs.
   *
   * @return a human-readable description
   */
  String describe();

  /**
   * This spec as a CAN device declaration, for the one global duplicate-ID scan.
   *
   * @param owner the mechanism that declares this sensor
   * @param role its role within that mechanism, for example {@code "held sensor"}
   * @return the device, or empty when the sensor is not on a CAN bus
   */
  default Optional<CanIdRegistry.Device> canDevice(String owner, String role) {
    return Optional.empty();
  }

  // ===============================================================================================
  // Factories
  // ===============================================================================================

  /**
   * A switch or beam break on a roboRIO DIO channel.
   *
   * <p>Cannot stop a motor in firmware. PumpkinLib zeroes the output in the same loop it sees the
   * level change, which is one control period — around 20 ms of extra travel — later than a
   * controller-wired switch would have stopped it.
   *
   * @param channel the DIO channel
   * @param inverted true when the sensor reads low for "present", which is the common wiring for a
   *     normally-closed switch
   * @param debounce how long the raw reading must hold before the level changes
   * @return the spec
   */
  static Dio dio(int channel, boolean inverted, Time debounce) {
    return new Dio(channel, inverted, debounce);
  }

  /**
   * A switch or beam break on a roboRIO DIO channel, with the default debounce.
   *
   * @param channel the DIO channel
   * @param inverted true when the sensor reads low for "present"
   * @return the spec
   */
  static Dio dio(int channel, boolean inverted) {
    return new Dio(channel, inverted, kDefaultDebounce);
  }

  /**
   * The motor controller's own limit-switch input.
   *
   * <p>The stop happens in firmware, so it works even if robot code has hung. Declaring one is also
   * what subscribes the limit status signal, so the reading is real.
   *
   * @param side which end of travel the switch is on
   * @return the spec
   */
  static MotorLimit motorLimit(Limit side) {
    return new MotorLimit(side, kDefaultDebounce);
  }

  /**
   * A CANrange time-of-flight sensor, reporting "closer than the threshold" as its level.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name; blank becomes {@value MotorSpec#kDefaultBus}
   * @param threshold the distance below which the sensor reads "present"
   * @return the spec
   */
  static CanRange canRange(int deviceId, String canBus, Distance threshold) {
    return new CanRange(deviceId, canBus, threshold, kDefaultDebounce);
  }

  /**
   * A digital input on a CANdi.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name; blank becomes {@value MotorSpec#kDefaultBus}
   * @param channel which input
   * @return the spec
   */
  static Candi candi(int deviceId, String canBus, CandiChannel channel) {
    return new Candi(deviceId, canBus, channel, kDefaultDebounce);
  }

  /**
   * "The motor is working harder than it would be if the mechanism were empty."
   *
   * <p>No sensor, no wiring, no extra device — and no way to tell a game piece from a jam. It is a
   * genuinely useful last resort for a roller, and the boot dump says what it is.
   *
   * @param threshold the stator current above which the level reads true
   * @param debounce how long the current must stay high; a spin-up spike is not a game piece
   * @return the spec
   */
  static StatorCurrent statorCurrent(Current threshold, Time debounce) {
    return new StatorCurrent(threshold, debounce);
  }

  /**
   * A sensor driven by a supplier, for simulation and for tests.
   *
   * @param detected what the sensor reads
   * @return the spec
   */
  static Sim sim(BooleanSupplier detected) {
    return new Sim(detected, kDefaultDebounce);
  }

  // ===============================================================================================
  // Variants
  // ===============================================================================================

  /**
   * A switch or beam break on a roboRIO DIO channel.
   *
   * @param channel the DIO channel
   * @param inverted true when the sensor reads low for "present"
   * @param debounce how long the raw reading must hold before the level changes
   */
  record Dio(int channel, boolean inverted, Time debounce) implements SensorSpec {

    /** Rejects a null debounce, which no factory can produce. */
    public Dio {
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return false;
    }

    @Override
    public boolean measuresDistance() {
      return false;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (channel < 0 || channel > 25) {
        out.add(
            "DIO sensor: channel "
                + channel
                + " is outside the addressable range 0..25. Fix: use the channel number printed "
                + "next to the header the sensor is plugged into (0..9 on the roboRIO itself).");
      }
      Sensors.checkDebounce(out, "DIO sensor on channel " + channel, debounce);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return "DIO "
          + channel
          + (inverted ? " (inverted)" : "")
          + Sensors.debounceClause(debounce)
          + " — on the roboRIO, so it CANNOT stop the motor in firmware; PumpkinLib zeroes the "
          + "output one control loop (about 20 ms) after the level changes.";
    }
  }

  /**
   * The motor controller's own limit-switch input.
   *
   * @param side which end of travel the switch is on
   * @param debounce how long the raw reading must hold before the level changes
   */
  record MotorLimit(Limit side, Time debounce) implements SensorSpec {

    /** Rejects the null components, neither of which any factory can produce. */
    public MotorLimit {
      side =
          Objects.requireNonNull(
              side,
              "SensorSpec.motorLimit: which end? Pass SensorSpec.Limit.FORWARD (increasing "
                  + "position) or SensorSpec.Limit.REVERSE (decreasing position).");
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return true;
    }

    @Override
    public boolean measuresDistance() {
      return false;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Sensors.checkDebounce(out, "motor limit switch (" + side + ")", debounce);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return "motor controller "
          + side.toString().toLowerCase(Locale.ROOT)
          + " limit input"
          + Sensors.debounceClause(debounce)
          + " — stops the motor in FIRMWARE, so it works even if robot code hangs. Declaring it is "
          + "also what subscribes the limit status signal, so the reading is real rather than "
          + "frozen. Zero extra CAN traffic: the motor already reports it.";
    }
  }

  /**
   * A CANrange time-of-flight sensor.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name
   * @param threshold the distance below which the sensor reads "present"
   * @param debounce how long the raw reading must hold before the level changes
   */
  record CanRange(int deviceId, String canBus, Distance threshold, Time debounce)
      implements SensorSpec {

    /** Normalises the bus name and rejects the null components. */
    public CanRange {
      canBus = Specs.normaliseBus(canBus);
      threshold =
          Objects.requireNonNull(
              threshold,
              "SensorSpec.canRange: a proximity threshold is required — it is the distance below "
                  + "which the sensor reads \"present\". Measure it with the game piece in place.");
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return false;
    }

    @Override
    public boolean measuresDistance() {
      return true;
    }

    @Override
    public Optional<CanIdRegistry.Device> canDevice(String owner, String role) {
      return Optional.of(CanIdRegistry.Device.on(owner, role, "CANrange", deviceId, canBus));
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Sensors.checkCanId(out, "CANrange " + deviceId, deviceId, canBus);
      Sensors.checkDebounce(out, "CANrange " + deviceId, debounce);
      double metres = threshold.in(Meters);
      if (!Double.isFinite(metres) || metres <= 0.0) {
        out.add(
            "CANrange "
                + deviceId
                + ": proximity threshold = "
                + String.format(Locale.ROOT, "%.4f m", metres)
                + ", which must be a finite distance greater than zero. Fix: hold the game piece "
                + "where it should read \"present\", read the distance, and use a little more.");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "CANrange %d (%s), present below %.3f m%s — also reports the raw distance.",
          deviceId,
          canBus,
          threshold.in(Meters),
          Sensors.debounceClause(debounce));
    }
  }

  /**
   * A digital input on a CANdi.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name
   * @param channel which input
   * @param debounce how long the raw reading must hold before the level changes
   */
  record Candi(int deviceId, String canBus, CandiChannel channel, Time debounce)
      implements SensorSpec {

    /** Normalises the bus name and rejects the null components. */
    public Candi {
      canBus = Specs.normaliseBus(canBus);
      channel =
          Objects.requireNonNull(
              channel,
              "SensorSpec.candi: which input? Pass SensorSpec.CandiChannel.S1 or "
                  + "SensorSpec.CandiChannel.S2.");
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return false;
    }

    @Override
    public boolean measuresDistance() {
      return false;
    }

    @Override
    public Optional<CanIdRegistry.Device> canDevice(String owner, String role) {
      return Optional.of(CanIdRegistry.Device.on(owner, role, "CANdi", deviceId, canBus));
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Sensors.checkCanId(out, "CANdi " + deviceId, deviceId, canBus);
      Sensors.checkDebounce(out, "CANdi " + deviceId, debounce);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return "CANdi " + deviceId + " (" + canBus + ") " + channel + Sensors.debounceClause(debounce);
    }
  }

  /**
   * "The motor is pulling more current than it would if the mechanism were empty."
   *
   * @param threshold the stator current above which the level reads true
   * @param debounce how long the current must stay high before the level changes
   */
  record StatorCurrent(Current threshold, Time debounce) implements SensorSpec {

    /** Rejects the null components. */
    public StatorCurrent {
      threshold =
          Objects.requireNonNull(
              threshold,
              "SensorSpec.statorCurrent: a current threshold is required. Watch the stator current "
                  + "in AdvantageScope with and without a game piece, and pick a number between "
                  + "the two.");
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return false;
    }

    @Override
    public boolean measuresDistance() {
      return false;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      double amps = threshold.in(Amps);
      if (!Double.isFinite(amps) || amps <= 0.0) {
        out.add(
            "stator-current sensor: threshold = "
                + String.format(Locale.ROOT, "%.1f A", amps)
                + ", which must be a finite current greater than zero. Fix: read the stator current "
                + "with and without a game piece and pick a number between the two.");
      }
      Sensors.checkDebounce(out, "stator-current sensor", debounce);
      if (debounce.in(Seconds) < 0.04) {
        out.add(
            "stator-current sensor: debounce = "
                + String.format(Locale.ROOT, "%.3f s", debounce.in(Seconds))
                + " is short enough that the roller's own spin-up current spike will read as a game "
                + "piece. Fix: use at least 0.05 s, or add a real sensor.");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "stator current above %.1f A%s — no sensor and no wiring, but it cannot tell a game piece "
              + "from a jam.",
          threshold.in(Amps),
          Sensors.debounceClause(debounce));
    }
  }

  /**
   * A sensor driven by a supplier, for simulation and for tests.
   *
   * @param detected what the sensor reads
   * @param debounce how long the raw reading must hold before the level changes
   */
  record Sim(BooleanSupplier detected, Time debounce) implements SensorSpec {

    /** Rejects the null components. */
    public Sim {
      detected =
          Objects.requireNonNull(
              detected, "SensorSpec.sim: a BooleanSupplier is required — it is the whole sensor.");
      debounce = Sensors.requireDebounce(debounce);
    }

    @Override
    public boolean isMotorLimit() {
      return false;
    }

    @Override
    public boolean measuresDistance() {
      return false;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Sensors.checkDebounce(out, "simulated sensor", debounce);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return "simulated sensor" + Sensors.debounceClause(debounce);
    }
  }
}

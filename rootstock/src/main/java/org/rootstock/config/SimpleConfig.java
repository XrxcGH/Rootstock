package org.rootstock.config;

import edu.wpi.first.units.measure.MomentOfInertia;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.rootstock.control.NeutralMode;
import org.rootstock.core.config.CanIdRegistry;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.RotaryAxis;

/**
 * A mechanism that is told a percentage and left alone: intake rollers, feeders, indexers.
 *
 * <p>There is no closed loop, no gains and no goal — which is exactly why the interesting content
 * of this config is the two sensors. {@code heldSensor} becomes the debounced {@code holding()}
 * trigger, and because it is a <b>level</b> rather than an edge, "intake until held" works even
 * when the game piece was already present before the command started. That five-line workaround
 * appears by hand in {@code 9143-2025-A-Updated}; here it is the only available shape.
 *
 * <p>Stop-on-end is structural for the same reason: {@code 9143-A} had to hand-append
 * {@code .handleInterrupt(coral::stopIntake)} to three separate commands under a comment reading
 * "Never leave rollers running on interrupt". A roller left spinning after an interrupted command
 * is a jammed game piece and a burnt 550, so {@code SimpleMechanism}'s factories are
 * {@code startEnd}-shaped and there is no way to write the other one.
 *
 * <h2>Example — the flagship {@code ROLLER} from {@code design/01} §9.1</h2>
 *
 * <pre>{@code
 * public static final SimpleConfig ROLLER = SimpleConfig.of("Roller")
 *     .motors(MotorGroup.leader(MotorSpec.spark(24, SparkModel.MAX_NEO550)))
 *     .reduction(Reduction.of(4.0))
 *     .currentLimits(CurrentLimits.of(Amps.of(30), Amps.of(20)))
 *     .heldSensor(SensorSpec.canRange(25, "rio", Meters.of(0.06)))
 *     .sim(KilogramSquareMeters.of(0.001))
 *     .build();
 * }</pre>
 *
 * @param name the mechanism's name, as the team wrote it
 * @param motors the leader and its followers
 * @param reduction the gearbox, rotor rotations per output rotation
 * @param current the stator and supply limits
 * @param heldSensor the level sensor that becomes {@code holding()}, if there is one
 * @param stallSensor the sensor that becomes {@code stalled()}; usually a stator-current threshold
 * @param neutralMode what the motor does when nothing is commanding it
 * @param sim the moment of inertia the simulated plant is built from
 * @param errors every problem validation found, collected and never thrown (§5.6)
 */
public record SimpleConfig(
    String name,
    MotorGroup motors,
    Reduction reduction,
    CurrentLimits current,
    Optional<SensorSpec> heldSensor,
    Optional<SensorSpec> stallSensor,
    NeutralMode neutralMode,
    SimConfig sim,
    List<ConfigError> errors) {

  /**
   * Compact constructor: pure, local and non-throwing, for the reasons {@link PositionConfig} gives
   * at length. It substitutes documented defaults for nulls and stores the collected errors.
   */
  public SimpleConfig {
    name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    motors = motors == null ? Validation.kNoMotors : motors;
    reduction = reduction == null ? Reduction.IDENTITY : reduction;
    current = current == null ? CurrentLimits.defaultsFor(motors.model()) : current;
    heldSensor = heldSensor == null ? Optional.empty() : heldSensor;
    stallSensor = stallSensor == null ? Optional.empty() : stallSensor;
    neutralMode = neutralMode == null ? NeutralMode.BRAKE : neutralMode;
    sim = sim == null ? Validation.kUnsetSim : sim;
    errors =
        Validation.localChecks(name, motors, reduction, current, heldSensor, stallSensor, sim);
  }

  /**
   * A builder for an open-loop mechanism.
   *
   * @param name the mechanism's name, as it should appear in logs and messages
   * @return the builder
   */
  public static Builder of(String name) {
    return new Builder(name);
  }

  // -------------------------------------------------------------------------------------------
  // with*() copies
  // -------------------------------------------------------------------------------------------

  /**
   * A copy driven by different motors.
   *
   * @param value the motor group
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withMotors(MotorGroup value) {
    return new SimpleConfig(
        name, value, reduction, current, heldSensor, stallSensor, neutralMode, sim, errors);
  }

  /**
   * A copy with a different gearbox.
   *
   * @param value the reduction
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withReduction(Reduction value) {
    return new SimpleConfig(
        name, motors, value, current, heldSensor, stallSensor, neutralMode, sim, errors);
  }

  /**
   * A copy with different current limits.
   *
   * @param value the current limits
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withCurrentLimits(CurrentLimits value) {
    return new SimpleConfig(
        name, motors, reduction, value, heldSensor, stallSensor, neutralMode, sim, errors);
  }

  /**
   * A copy with a different "am I holding a game piece?" sensor.
   *
   * @param value the sensor, or null for none
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withHeldSensor(SensorSpec value) {
    return new SimpleConfig(
        name,
        motors,
        reduction,
        current,
        Optional.ofNullable(value),
        stallSensor,
        neutralMode,
        sim,
        errors);
  }

  /**
   * A copy with a different stall detector.
   *
   * @param value the sensor, or null for none
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withStallSensor(SensorSpec value) {
    return new SimpleConfig(
        name,
        motors,
        reduction,
        current,
        heldSensor,
        Optional.ofNullable(value),
        neutralMode,
        sim,
        errors);
  }

  /**
   * A copy with a different neutral behaviour.
   *
   * @param value brake or coast
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withNeutralMode(NeutralMode value) {
    return new SimpleConfig(
        name, motors, reduction, current, heldSensor, stallSensor, value, sim, errors);
  }

  /**
   * A copy with a different simulated plant.
   *
   * @param value the sim config
   * @return a new config; this one is unchanged
   */
  public SimpleConfig withSim(SimConfig value) {
    return new SimpleConfig(
        name, motors, reduction, current, heldSensor, stallSensor, neutralMode, value, errors);
  }

  // -------------------------------------------------------------------------------------------
  // Derived values
  // -------------------------------------------------------------------------------------------

  /**
   * The converter used for the simulated plant's gearing and for the boot dump's free-speed line.
   *
   * <p>A simple mechanism has no declared geometry, so this is the gearbox against a plain roller
   * axis: one output rotation is 360 degrees and nothing more is claimed.
   *
   * @return the mechanism's units
   */
  public MechanismUnits units() {
    return MechanismUnits.of(reduction, RotaryAxis.roller());
  }

  /**
   * Which of the three mechanism shapes this is.
   *
   * @return always {@link MechanismKind#SIMPLE}
   */
  public MechanismKind kind() {
    return MechanismKind.SIMPLE;
  }

  /**
   * Whether this mechanism can answer "am I holding something?".
   *
   * @return true when a held sensor was declared
   */
  public boolean hasHeldSensor() {
    return heldSensor.isPresent();
  }

  /**
   * The estimated free speed of the output, in degrees per second.
   *
   * @return degrees per second
   */
  public double freeSpeedUserPerSec() {
    return units().freeSpeedUserPerSec(motors.model().freeSpeedRotorRps(motors.leader().foc()));
  }

  /**
   * Every CAN device this mechanism claims, for the single global uniqueness scan (§5.6b).
   *
   * @return the motors and any CAN sensors
   */
  public List<CanIdRegistry.Device> canDevices() {
    List<CanIdRegistry.Device> out = new ArrayList<>(motors.canDevices(name));
    heldSensor.flatMap(s -> s.canDevice(name, "held sensor")).ifPresent(out::add);
    stallSensor.flatMap(s -> s.canDevice(name, "stall sensor")).ifPresent(out::add);
    return List.copyOf(out);
  }

  /**
   * Whether registering this config puts the robot into safe mode.
   *
   * @return true when at least one collected error is fatal
   */
  public boolean hasFatalError() {
    return errors.stream().anyMatch(ConfigError::isFatal);
  }

  /**
   * The loggable, flattened form of this config.
   *
   * @return the snapshot
   */
  public MechanismConfigSnapshot snapshot() {
    return MechanismConfigSnapshot.of(this);
  }

  /**
   * The boot dump for an open-loop mechanism.
   *
   * @return a multi-line block, newline-terminated
   */
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(768);
    sb.append("=== ").append(name).append("  (SimpleConfig, ").append(kind().describe())
        .append(") ===").append(nl);
    sb.append("  Motors              ").append(motors.describe()).append(nl);
    sb.append("  Reduction           ").append(reduction.describe()).append(nl);
    sb.append("  Current limits      ").append(current.describe()).append(nl);
    sb.append("  Held sensor         ")
        .append(heldSensor.map(SensorSpec::describe).orElse("none — holding() is always false"))
        .append(nl);
    sb.append("  Stall sensor        ")
        .append(stallSensor.map(SensorSpec::describe).orElse("none — stalled() is always false"))
        .append(nl);
    sb.append("  Neutral             ").append(neutralMode).append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Free speed estimate %.1f deg/s  (%d x %s through %s)",
                freeSpeedUserPerSec(),
                motors.count(),
                motors.model().displayName(),
                reduction.describe()))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Signal rate         %.0f Hz default for a SIMPLE mechanism",
                kind().defaultSignalRateHz()))
        .append(nl);
    sb.append("  Command shape       stop-on-end is structural: every factory is startEnd-shaped, ")
        .append("so an interrupted command cannot leave the roller running")
        .append(nl);
    sb.append("  Simulation          ").append(sim.describe()).append(nl);
    sb.append("  Config errors       ")
        .append(errors.isEmpty() ? "none" : errors.size() + " — see the list above")
        .append(nl);
    for (ConfigError error : errors) {
      sb.append("    ").append(error.summary()).append(nl);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "SimpleConfig[" + name + ", " + motors.count() + " motor(s), " + errors.size()
        + " error(s)]";
  }

  // -------------------------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------------------------

  /** Builds a {@link SimpleConfig}. No setter throws and neither does {@link #build()}. */
  public static final class Builder {
    private final String m_name;

    private MotorGroup m_motors = Validation.kNoMotors;
    private Reduction m_reduction = Reduction.IDENTITY;
    private CurrentLimits m_current;
    private SensorSpec m_heldSensor;
    private SensorSpec m_stallSensor;
    private NeutralMode m_neutralMode = NeutralMode.BRAKE;
    private SimConfig m_sim = Validation.kUnsetSim;

    private Builder(String name) {
      m_name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    }

    /**
     * What drives it.
     *
     * @param value the leader and its followers
     * @return this builder
     */
    public Builder motors(MotorGroup value) {
      m_motors = value;
      return this;
    }

    /**
     * A single-motor group, for the common case.
     *
     * @param leader the only motor
     * @return this builder
     */
    public Builder motor(MotorSpec leader) {
      return motors(MotorGroup.leader(leader));
    }

    /**
     * The gearbox.
     *
     * @param value rotor rotations per output rotation
     * @return this builder
     */
    public Builder reduction(Reduction value) {
      m_reduction = value;
      return this;
    }

    /**
     * Stator and supply limits. Omit and the leader motor's defaults are used — which matters most
     * here, because an intake roller is the mechanism most likely to be stalled against a game
     * piece for seconds at a time.
     *
     * @param value the current limits
     * @return this builder
     */
    public Builder currentLimits(CurrentLimits value) {
      m_current = value;
      return this;
    }

    /**
     * The level sensor that answers "am I holding a game piece?".
     *
     * @param value a beam break, a CANrange, or a stator-current threshold
     * @return this builder
     */
    public Builder heldSensor(SensorSpec value) {
      m_heldSensor = value;
      return this;
    }

    /**
     * The sensor that answers "am I jammed?".
     *
     * @param value usually {@code SensorSpec.statorCurrent(Amps.of(40), Seconds.of(0.25))}
     * @return this builder
     */
    public Builder stallSensor(SensorSpec value) {
      m_stallSensor = value;
      return this;
    }

    /**
     * What the motor does when nothing is commanding it.
     *
     * @param value brake or coast
     * @return this builder
     */
    public Builder neutralMode(NeutralMode value) {
      m_neutralMode = value;
      return this;
    }

    /**
     * The simulated plant, stated in full.
     *
     * @param value the sim config
     * @return this builder
     */
    public Builder sim(SimConfig value) {
      m_sim = value;
      return this;
    }

    /**
     * The simulated plant — the only sim code a team writes.
     *
     * @param momentOfInertia the moment of inertia of everything that spins
     * @return this builder
     */
    public Builder sim(MomentOfInertia momentOfInertia) {
      return sim(SimConfig.flywheel(momentOfInertia));
    }

    /**
     * Assembles the config. <b>Never throws.</b>
     *
     * @return the config, carrying every problem validation found
     */
    public SimpleConfig build() {
      CurrentLimits current =
          m_current != null ? m_current : CurrentLimits.defaultsFor(m_motors.model());
      return new SimpleConfig(
          m_name,
          m_motors,
          m_reduction,
          current,
          Optional.ofNullable(m_heldSensor),
          Optional.ofNullable(m_stallSensor),
          m_neutralMode,
          m_sim,
          List.of());
    }
  }
}

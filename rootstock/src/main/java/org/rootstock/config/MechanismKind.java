package org.rootstock.config;

/**
 * Which of the three mechanism shapes a config describes.
 *
 * <p>It exists so the boot dump, the log snapshot and the validation messages can say
 * <i>"Elevator (POSITION)"</i> without a chain of {@code instanceof} tests, and so a downstream
 * package can switch on a single value rather than on the config's Java type. The three shapes are
 * the ones {@code design/01} §6 defines: a mechanism that goes to a place, a mechanism that holds a
 * speed, and a mechanism that is told a percentage and left alone.
 */
public enum MechanismKind {

  /** Goes to a place. Elevator, arm, wrist, turret, hood — anything with soft limits and a goal. */
  POSITION,

  /** Holds a speed. Flywheel, indexer, a roller under closed-loop velocity. */
  VELOCITY,

  /** Told a percentage. Intake rollers, feeders — open loop, stop-on-end, no encoder required. */
  SIMPLE;

  /**
   * Whether a config of this kind carries soft position limits and named setpoints.
   *
   * @return true only for {@link #POSITION}
   */
  public boolean hasPositionGoals() {
    return this == POSITION;
  }

  /**
   * Whether a config of this kind runs a closed loop at all.
   *
   * <p>{@link #SIMPLE} does not, which is why it carries no {@code Gains} and why validation never
   * asks it about {@code kG}.
   *
   * @return true for {@link #POSITION} and {@link #VELOCITY}
   */
  public boolean isClosedLoop() {
    return this != SIMPLE;
  }

  /**
   * The default signal subscription rate for this kind, in hertz.
   *
   * <p>design/01 §6.5: a position mechanism gets 100 Hz so a fast RIO-side loop is not aliasing
   * against the 50 Hz robot loop; a velocity mechanism gets 50 Hz because its {@code atGoal} is a
   * debounced predicate over tens of milliseconds and gains nothing from the extra frames. The
   * difference is 4.5 % versus 2.4 % of a 1 Mbps CAN bus per mechanism, which is real money on a
   * robot with six of them. {@code MotorSpec.signalRateHz(...)} overrides it per device.
   *
   * @return 100.0, 50.0 or 20.0 hertz
   */
  public double defaultSignalRateHz() {
    return switch (this) {
      case POSITION -> 100.0;
      case VELOCITY -> 50.0;
      case SIMPLE -> 20.0;
    };
  }

  /**
   * The kind as the boot dump prints it.
   *
   * @return for example {@code "POSITION (goes to a place)"}
   */
  public String describe() {
    return switch (this) {
      case POSITION -> "POSITION (goes to a place)";
      case VELOCITY -> "VELOCITY (holds a speed)";
      case SIMPLE -> "SIMPLE (open loop, stop on end)";
    };
  }
}

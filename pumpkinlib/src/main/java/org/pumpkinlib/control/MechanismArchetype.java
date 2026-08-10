package org.pumpkinlib.control;

/**
 * Which tuning recipe a mechanism needs.
 *
 * <p>An archetype is not a description of hardware — it is a decision about <em>procedure</em>. It
 * selects which identification steps run, in which order, with which safety envelope, and which
 * regressor terms the feedforward fit includes. Two mechanisms with identical motors and gearboxes
 * can be different archetypes (a flywheel and a turret) and two very different-looking mechanisms
 * can be the same one (an elevator and a telescoping climber).
 *
 * <p>Deliberately six values and no "OTHER". A mechanism that does not fit one of these is a
 * mechanism the tuning wizard cannot safely move, and saying so is more useful than accepting it and
 * running the wrong recipe.
 */
public enum MechanismArchetype {
  /**
   * Velocity control, no gravity, high inertia. Shooter wheels, intake rollers under load.
   *
   * <p>Has no meaningful absolute position, so position aborts are disabled and homing is not
   * required before the wizard will arm.
   */
  FLYWHEEL,

  /**
   * Velocity control of a swerve or tank drive motor. The same math as {@link #FLYWHEEL}, but a
   * different safety envelope: a drive motor accelerates the whole robot, so the routine has to run
   * against a chassis that is either on blocks or free to move, and the velocity ceiling is the
   * robot's, not the motor's.
   */
  DRIVE_VELOCITY,

  /**
   * Position control with a constant gravity term. Elevators, telescopes, linear slides.
   *
   * <p>kG is a fixed number of volts in a fixed direction, so the identification step measures it
   * once anywhere in travel.
   */
  ELEVATOR,

  /**
   * Position control with a cosine gravity term. Arms, pivots, wrists, hoods.
   *
   * <p>kG is measured against the angle from <em>horizontal</em>, which is why every arm must
   * declare where horizontal is. An arm whose zero is not horizontal and whose recipe assumes it is
   * gets a cosine that is wrong at every angle, not just at one.
   */
  ARM,

  /**
   * Position control with no gravity. Turrets, counterbalanced hoods, azimuth stages.
   *
   * <p>Position aborts still apply — a turret has hard stops and cable wrap — but there is no
   * gravity term to identify, so the recipe is shorter.
   */
  TURRET,

  /**
   * Position control of a swerve steer motor: {@link #TURRET} math plus continuous wrap.
   *
   * <p>Kept separate from {@code TURRET} because continuous wrap changes the error computation, and
   * because per-module gains are the entire point of tuning a swerve — averaging four modules hides
   * the one that is wrong.
   */
  STEER;

  /**
   * True when this archetype controls a position and therefore has travel limits worth aborting on.
   *
   * @return true for {@link #ELEVATOR}, {@link #ARM}, {@link #TURRET} and {@link #STEER}
   */
  public boolean isPosition() {
    return this == ELEVATOR || this == ARM || this == TURRET || this == STEER;
  }

  /**
   * True when this archetype controls a velocity, so position aborts do not apply and the tuning
   * target is not required to know where it is.
   *
   * @return true for {@link #FLYWHEEL} and {@link #DRIVE_VELOCITY}
   */
  public boolean isVelocity() {
    return this == FLYWHEEL || this == DRIVE_VELOCITY;
  }

  /**
   * True when gravity loads this archetype, so a kG term must be identified and the taper on an
   * abort must hold the mechanism rather than release it.
   *
   * <p>This is the single most safety-relevant question in the enum: tapering an arm's voltage to
   * zero near a limit does not stop the arm, it drops it into the limit.
   *
   * @return true for {@link #ELEVATOR} and {@link #ARM}
   */
  public boolean hasGravity() {
    return this == ELEVATOR || this == ARM;
  }

  /**
   * True when the axis wraps, so error must be computed modulo a full rotation.
   *
   * @return true for {@link #STEER}
   */
  public boolean isContinuous() {
    return this == STEER;
  }
}

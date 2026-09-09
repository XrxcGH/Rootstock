package org.rootstock.control;

import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.units.SiDomain;

/**
 * The seam between a mechanism and the tuning system: <b>raw voltage in, SI state out</b>, plus
 * enough physical description to be safe and to sanity-check the answer.
 *
 * <p>Every quantity here is canonical SI — metres and metres per second for a linear mechanism,
 * radians and radians per second for a rotational one, as declared by {@link #siDomain()}. Volts are
 * always volts.
 *
 * <h2>Why this lives in {@code org.rootstock.control} and not in the tuning package</h2>
 *
 * <p>This is the single most important incremental-adoption seam in the library. A team with
 * hand-rolled subsystems implements it in about thirty lines and gets the whole tuning domain,
 * without adopting the mechanism layer, the config layer or the wizard. Equally, the mechanism layer
 * does not have to depend on the tuning package in order to be tunable. Putting the interface in the
 * tuning package would have forced both halves of that trade to be all-or-nothing.
 *
 * <h2>The one rule about {@link #setVoltage(double)}</h2>
 *
 * <p>{@code TuningSupervisor} is the <b>only</b> legal caller. Nothing in the tuning package calls it
 * directly, and an ArchUnit rule fails the build if anything does. That is what makes "every
 * actuating routine runs inside a {@link SafetyEnvelope}" a structural property rather than a
 * convention somebody remembers.
 */
public interface TuningTarget {

  /**
   * A unique, human-meaningful name for this mechanism. Becomes the NetworkTables namespace, so it
   * must not contain a slash.
   *
   * @return e.g. {@code "Elevator"} or {@code "FrontLeftSteer"}
   */
  String tuningName();

  /**
   * Which tuning recipe applies — which identification steps run, in which order, with which
   * aborts.
   *
   * @return the archetype
   */
  MechanismArchetype archetype();

  /**
   * Whether this mechanism's SI unit is metres or radians.
   *
   * <p>Derived, never typed by a team: Rootstock's own mechanisms return their geometry's domain.
   *
   * @return the SI domain
   */
  SiDomain siDomain();

  /**
   * Where the closed loop executes.
   *
   * <p>The tuning system asks exactly one question of this — {@link ControlLocation#runsOnMotor()} —
   * which decides the measurement-delay model and whether {@link #getFeedbackVolts()} can be
   * expected to be present. Everything else about the location is the config layer's business.
   *
   * @return the location
   */
  ControlLocation controlLocation();

  /**
   * Command a raw voltage, bypassing any closed loop, while still respecting device soft limits.
   *
   * <p><b>{@code TuningSupervisor} is the only legal caller</b>, enforced by an ArchUnit rule rather
   * than by a comment. An implementation must bypass the closed loop — a "voltage" that is really a
   * setpoint into a controller measures the controller, not the plant, and every gain fitted from it
   * is wrong.
   *
   * @param volts the voltage to command
   */
  void setVoltage(double volts);

  /**
   * Immediately neutral the mechanism.
   *
   * <p>Called on every abort path, so it <b>must never throw</b>. An exception here turns a
   * controlled stop into an uncontrolled one.
   */
  void stop();

  /**
   * The mechanism's position.
   *
   * <p>For a cosine-gravity axis, note that this is the raw position, not the angle from horizontal;
   * see {@link #horizontalReferenceSi()}.
   *
   * @return position in metres or radians
   */
  double measuredSi();

  /**
   * The mechanism's velocity.
   *
   * @return velocity in m/s or rad/s
   */
  double velocitySi();

  /**
   * The mechanism's acceleration, if a signal for it exists.
   *
   * <p>Returning {@link Double#NaN} is the normal answer and not a defect — very little FRC hardware
   * reports acceleration. The tuner then differences velocity itself with a central-difference
   * filter, which is what it would have to do anyway to trust the number.
   *
   * @return acceleration in m/s^2 or rad/s^2, or NaN when unavailable
   */
  default double accelerationSi() {
    return Double.NaN;
  }

  /**
   * The motor voltage as actually measured at the device, when the device reports it.
   *
   * <p>Strongly preferred over the commanded value. On a sagging battery the two differ by half a
   * volt, and fitting kV against the commanded value biases it high — one of the most common silent
   * characterisation errors.
   *
   * @return the measured applied voltage, or empty if the device does not report it
   */
  default OptionalDouble appliedVolts() {
    return OptionalDouble.empty();
  }

  /**
   * Stator current, for the overcurrent abort.
   *
   * <p>Empty disables that abort, with a warning — a routine that cannot see current cannot tell a
   * jam from a hard stop, and the student should know which protections are actually running.
   *
   * @return stator current in amps, or empty if unavailable
   */
  default OptionalDouble statorCurrentAmps() {
    return OptionalDouble.empty();
  }

  /**
   * The feedback-only contribution of the closed loop this cycle, in volts — the total closed-loop
   * output minus whatever feedforward the loop applied.
   *
   * <p>This is what lets the wizard say <i>"raise kS"</i> or <i>"raise kG"</i> instead of <i>"raise
   * kP"</i>. When it is empty the entire steady-state diagnosis branch is disabled and the student is
   * told why; we do not guess.
   *
   * <p>Empty is a legitimate answer. A loop whose {@link ControlLocation#runsOnMotor()} is true
   * computes its feedforward inside the device, and not every vendor reports the split back.
   *
   * @return the feedback-only volts, or empty when the split is not observable
   */
  default Optional<Double> getFeedbackVolts() {
    return Optional.empty();
  }

  /**
   * How this mechanism knows where it is, in the sense a supervisor cares about.
   *
   * @return the position reference
   */
  PositionReference positionReference();

  /**
   * True when the reported position corresponds to physical reality <b>right now</b>.
   *
   * <p>Every position abort and every gravity-shaped voltage command is computed against {@link
   * #measuredSi()}. A mechanism that was moved by hand while disabled, whose homing was a boot-time
   * assumption, reports a position that is simply wrong — and then every interlock is inert while a
   * routine commands voltage against a fictional angle.
   *
   * <p>The default is true only for the two velocity archetypes, which have no meaningful absolute
   * position. Every position mechanism must override this, and must return false when its only
   * position reference is a boot-time assumption. That is a deliberate, compile-time-visible burden:
   * a mechanism that cannot answer "do I know where I am" is a mechanism the tuner will not move.
   *
   * @return whether the position may be trusted
   */
  default boolean isHomed() {
    return archetype() == MechanismArchetype.FLYWHEEL
        || archetype() == MechanismArchetype.DRIVE_VELOCITY;
  }

  /**
   * The absolute sensor's reading, when one exists, for the pre-arm agreement check against {@link
   * #measuredSi()}.
   *
   * @return the absolute position in metres or radians, or empty when there is no absolute source
   */
  default OptionalDouble absolutePositionSi() {
    return OptionalDouble.empty();
  }

  /**
   * Hard travel limits plus the tuning margin, in SI.
   *
   * <p>Required, with no default: the supervisor refuses to arm without them, and a mechanism that
   * genuinely has no limits says so with {@link TravelLimits#unbounded()} rather than by omission.
   *
   * @return the limits
   */
  TravelLimits travelLimits();

  /**
   * The physics prior used to sanity-bound a fit and to build the simulation plant.
   *
   * @return the prior
   */
  PlantPrior plantPrior();

  /**
   * The SI position at which a cosine-gravity mechanism is <b>horizontal</b> — the angle from which
   * the gravity term is {@code kG * cos(measuredSi() - horizontalReferenceSi())}.
   *
   * <p>Zero for a linear axis and for {@link GravityMode#NONE}. Earlier revisions asked a boolean,
   * {@code armZeroIsHorizontal()}; a boolean cannot carry the offset a mechanism actually has, and
   * passing a raw position to a cosine makes the term wrong at every angle rather than at one.
   *
   * @return the horizontal reference, in radians for a rotary axis
   */
  default double horizontalReferenceSi() {
    return 0.0;
  }

  /**
   * Which gravity model applies.
   *
   * <p>Derived from the geometry for Rootstock's own mechanisms; a hand-rolled target states it.
   * The default reads it off the archetype, which is right for every built-in recipe.
   *
   * @return the gravity mode
   */
  default GravityMode gravityMode() {
    if (!archetype().hasGravity()) {
      return GravityMode.NONE;
    }
    return archetype() == MechanismArchetype.ARM ? GravityMode.COSINE : GravityMode.CONSTANT;
  }

  /**
   * The "at goal" tolerance, in SI.
   *
   * <p>{@link Double#NaN} means "I do not have one", and the supervisor then substitutes 0.5% of
   * travel for a position archetype or 1% of free speed for a velocity one — and <b>narrates the
   * substitution</b>, because a tolerance the library invented is not a tolerance the team agreed
   * to.
   *
   * @return the tolerance in metres or radians, or NaN
   */
  default double toleranceSi() {
    return Double.NaN;
  }

  /**
   * The idle behaviour the device is configured for right now.
   *
   * <p>This exists because of a real measurement failure: the gravity probe releases the mechanism
   * for half a second and watches which way it falls. On a brake-mode arm — the normal, correct
   * configuration — nothing falls, and a probe that cannot read the idle mode concludes there is no
   * gravity to hold and sets kG to zero. Everything downstream then measures gravity as friction.
   *
   * @return the configured idle mode, or empty when the adapter cannot read it back
   */
  default Optional<NeutralMode> neutralMode() {
    return Optional.empty();
  }

  /**
   * Temporarily override the idle mode for a supervised measurement.
   *
   * <p>Returning false is a legitimate answer; the routine then takes a different, slower branch
   * rather than proceeding with a measurement it knows is invalid.
   *
   * @param mode the mode to apply for the duration of the measurement
   * @return true if the override took effect
   */
  default boolean overrideNeutralMode(NeutralMode mode) {
    return false;
  }

  /**
   * Restore the configured idle mode.
   *
   * <p>Called on <b>every</b> exit path including every abort, and <i>before</i> the supervisor
   * commands neutral — a coast-mode arm that is neutralled before its brake mode is restored falls.
   * Must be idempotent and must never throw.
   */
  default void restoreNeutralMode() {}

  /**
   * The gains this mechanism is running right now, in volts per SI unit.
   *
   * @return the current gains
   */
  Gains gains();

  /**
   * Where tuned gains are written.
   *
   * <p>Must write through to wherever the loop <em>actually</em> runs. Returning a sink rather than
   * exposing an {@code applyGains(Gains)} method is what lets the same object also answer {@link
   * GainSink#describeConversion()} for the boot dump and the UI — the conversion and the thing that
   * performs it are one object, so they cannot disagree.
   *
   * @return the sink
   */
  GainSink gainSink();

  /**
   * Whether this target can run a closed loop at all, which the feedback-tuning and verification
   * steps require.
   *
   * @return true if {@link #setClosedLoopGoalSi(double)} does something
   */
  default boolean supportsClosedLoop() {
    return false;
  }

  /**
   * Command a closed-loop goal: a position for a position archetype, a velocity for a velocity one.
   *
   * <p>Unlike {@link #setVoltage(double)} this is not supervisor-only, because it goes through the
   * mechanism's own limits and controller.
   *
   * @param goalSi the goal, in metres/radians or m/s per rad/s
   */
  default void setClosedLoopGoalSi(double goalSi) {
    // no-op: supportsClosedLoop() is false by default
  }

  /**
   * Whether a motion profile is in use, so the step-response analysis compares against the profile
   * rather than against an instantaneous step.
   *
   * @return true when profiled
   */
  default boolean isProfiled() {
    return false;
  }

  /**
   * An override for the measurement delay used when designing feedback gains.
   *
   * <p>{@link Double#NaN} means "use the delay implied by {@link #controlLocation()}", which is the
   * right answer for every supported backend. Override it only when you have measured the delay.
   *
   * @return the delay in seconds, or NaN
   */
  default double measurementDelaySeconds() {
    return Double.NaN;
  }

  /**
   * The subsystem a tuning command must require, if the team uses command-based.
   *
   * <p>Empty for a state-based robot. This is how a tuning routine gets exclusive control of the
   * mechanism without the tuning system knowing anything about the scheduler.
   *
   * @return the subsystem to require, or empty
   */
  default Optional<Subsystem> requirement() {
    return Optional.empty();
  }
}

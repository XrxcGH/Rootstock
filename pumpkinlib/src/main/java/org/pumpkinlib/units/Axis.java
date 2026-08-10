package org.pumpkinlib.units;

import java.util.List;
import org.pumpkinlib.control.GravityMode;

/**
 * The geometry of one mechanism: what one rotation of its OUTPUT shaft does in the world.
 *
 * <p><b>Where this sits in the four-layer unit contract</b> (§4.1):
 *
 * <pre>
 *   USER UNITS         SI UNITS            OUTPUT ROTATIONS          ROTOR ROTATIONS
 *   m / deg     &lt;--&gt;   m / rad     &lt;--&gt;    double (the seam)  &lt;--&gt;   inside the vendor
 *        ^                  ^                     ^                          ^
 *        |                  |                     |                          |
 *      Axis          MechanismUnits.toSi()   MotorIO seam                Reduction
 *    (geometry)      (identity for LINEAR;                              (gearbox)
 *                     deg-&gt;rad for ROTARY)
 * </pre>
 *
 * <p>{@code Axis} owns exactly one of those arrows: output rotations to user units. It is applied
 * <b>once</b>, inside {@link MechanismUnits}, on the way in and out of the {@code MotorIO} seam.
 * Team code never writes {@code * 360.0} (41 occurrences in one of the maintainer's repositories),
 * never writes {@code inchesToRotations}, and never divides by {@code 2 * Math.PI} by hand. The
 * gearbox owns a different arrow and is applied once too, inside the backend config, by
 * {@code Reduction}. Two conversions, each in one place, is the whole idea.
 *
 * <p><b>Axis is also where gravity comes from.</b> A linear axis is pulled by a constant force
 * whichever way it points; a rotary axis is pulled by a force proportional to the cosine of its
 * angle from horizontal. Those are consequences of the geometry, so {@link #gravity()} is derived
 * from the axis rather than configured next to it — you cannot declare an elevator and then
 * accidentally give it an arm's gravity model.
 *
 * <p><b>Sealed, with two implementations, forever.</b> Everything a mechanism can be is either
 * something that travels in a line ({@link LinearAxis}) or something that swings around a joint
 * ({@link RotaryAxis}). Sealing it means every {@code instanceof} chain in the library is
 * exhaustive by construction and a third case cannot appear without the compiler pointing at every
 * site that has to handle it.
 *
 * <p><b>Nothing here throws.</b> Axes are declared in {@code public static final} fields of a
 * team's {@code RobotConfig}, so a throw from a constructor or a factory is an {@code
 * ExceptionInInitializerError} out of {@code <clinit>} — red "Robot Code", no message, dead robot.
 * Bad geometry is instead <em>representable</em> and reported through {@link #problems()}, which
 * the config layer's validation pass turns into collected {@code ConfigError}s that let the robot
 * boot into safe mode and tell you what is wrong (§5.6).
 */
public sealed interface Axis permits LinearAxis, RotaryAxis {

  /**
   * How far the mechanism moves, in the units a human types and reads, for one rotation of the
   * output shaft.
   *
   * <p><b>Metres for a linear axis; DEGREES for a rotary one — 360.0.</b> That is binding (decision
   * P3). CORE publishes degrees for a rotary axis and labels them {@code "deg"}; radians are the SI
   * layer's business and are reached through {@link #siPerOutputRotation()}. Publishing a degree
   * stream under a radian label is a 57.3&times; mislabel that reads as a plausible number, which is
   * exactly the kind of bug that survives a whole season.
   *
   * @return user units per output rotation; strictly positive for a well-formed axis
   */
  double userPerOutputRotation();

  /**
   * How far the mechanism moves, in SI, for one rotation of the output shaft.
   *
   * <p>Metres for a linear axis (identical to {@link #userPerOutputRotation()}); {@code 2 * Math.PI}
   * radians for a rotary one. <b>This is the number gains use.</b> It is the single factor that
   * turns a volts-per-SI {@code kP} into the vendor's volts-per-output-rotation slot, and it is why
   * the same {@code Gains} record survives a change of drum diameter.
   *
   * @return SI units per output rotation; strictly positive for a well-formed axis
   */
  double siPerOutputRotation();

  /**
   * Which SI quantity this axis lives in, and therefore what {@code toSi}/{@code fromSi} do.
   *
   * @return {@link SiDomain#LINEAR_METERS} or {@link SiDomain#ROTATIONAL_RADIANS}
   */
  SiDomain siDomain();

  /**
   * The user unit's short label, for telemetry keys, {@code describe()} output and error messages.
   *
   * @return {@code "m"} for a linear axis, {@code "deg"} for a rotary one
   */
  String unitLabel();

  /**
   * The SI unit's short label — the label that belongs on anything derived from {@link
   * #siPerOutputRotation()}, including every gain.
   *
   * @return {@code "m"} for a linear axis, {@code "rad"} for a rotary one
   */
  String siLabel();

  /**
   * The gravity model this geometry implies.
   *
   * <p>Derived, never configured: {@link GravityMode#CONSTANT} for a linear axis, and whatever the
   * rotary axis was built as — {@link GravityMode#COSINE} for an arm, pivot or wrist, {@link
   * GravityMode#NONE} for a turret.
   *
   * @return the gravity model
   */
  GravityMode gravity();

  /**
   * Whether the closed loop should wrap around, taking the short way to a goal across the
   * {@code 0}/{@code 360} boundary.
   *
   * <p>Always {@code false} for a linear axis. For a rotary axis this is {@code false} unless the
   * joint genuinely rotates without limit: a turret with 540&deg; of cable-limited travel is
   * <b>not</b> continuous, because wrapping it is how you wrap a cable around a bearing. Read by
   * the Phoenix backend to set {@code ClosedLoopGeneral.ContinuousWrap} and by the REV backend to
   * set {@code positionWrapping}.
   *
   * @return true if the loop may take the short way around
   */
  boolean isContinuous();

  /**
   * For {@link GravityMode#COSINE}: the position, <b>in user units</b>, at which the mechanism is
   * horizontal.
   *
   * <p>This is the one number that makes cosine gravity correct, and the reason {@link
   * RotaryAxis#arm} demands it instead of assuming zero. It maps to Phoenix's {@code
   * Slot0Configs.GravityArmPositionOffset} (<b>negated</b> — the Phoenix field offsets the device
   * position, not the horizontal) and to the angle offset {@code ArmFeedforward} requires.
   *
   * <p>Zero for a linear axis, where it has no meaning.
   *
   * @return the horizontal reference in user units — degrees for a rotary axis
   */
  double horizontalReference();

  /**
   * Everything structurally wrong with this axis, in plain sentences, or an empty list if it is
   * well formed.
   *
   * <p>This is the non-throwing half of validation. A constructor cannot throw here (see the class
   * note above), so it stores the bad value and describes the problem, and the config layer's
   * validation pass wraps each entry in a collected {@code ConfigError}. The robot boots, refuses
   * to move, and prints the sentence.
   *
   * <p>Each entry names the thing, the value, the expected range and the fix, because a student
   * reads it at 11pm.
   *
   * @return an unmodifiable list of problem descriptions; empty when the axis is usable
   */
  List<String> problems();
}

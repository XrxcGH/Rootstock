package org.rootstock.units;

import static edu.wpi.first.units.Units.Degrees;

import edu.wpi.first.units.measure.Angle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.control.GravityMode;

/**
 * A mechanism that swings around a joint: an arm, a pivot, a wrist, a turret, a hood, a flywheel.
 *
 * <p>The geometry is trivial — one output rotation is 360 degrees, always — so this class is really
 * about the two things that are <em>not</em> trivial and that a flat constants file always gets
 * wrong.
 *
 * <h2>1. Degrees out here, radians in the gains</h2>
 *
 * <p>{@link #userPerOutputRotation()} is <b>360.0</b> and {@link #unitLabel()} is <b>{@code "deg"}
 * </b>. That is binding (decision P3). CORE publishes degrees, because degrees are what a student
 * types into a setpoint, reads on the dashboard and shouts across the pit. Meanwhile {@link
 * #siPerOutputRotation()} is {@code 2 * pi} and every gain, every RIO-side profile and WPILib's
 * {@code ArmFeedforward} are in radians, because that is what those APIs contractually require.
 *
 * <p>Both facts are true at once and the conversion between them happens in exactly one place —
 * {@link MechanismUnits#toSi(double)}. What must never happen is declaring one and publishing the
 * other: a degree stream labelled {@code "rad"} is a 57.3&times; mislabel, and 57.3&times; is a
 * number that still <em>looks</em> like a plausible gain.
 *
 * <h2>2. Where horizontal is</h2>
 *
 * <p>Cosine gravity is {@code kG * cos(position - horizontalReference)}. It is correct only when the
 * library knows where horizontal is, and the failure mode when it does not is a comment: <em>"Arm
 * cosine is only correct when 0 deg = arm horizontal"</em>, left in a constants file for somebody
 * else to trip over. So {@link #arm}, {@link #pivot}, {@link #wrist} and {@link #hood} take the
 * horizontal reference as a required argument. There is no overload that assumes zero.
 *
 * <p>That number reaches Phoenix as a <b>negated</b> {@code Slot0Configs.GravityArmPositionOffset}
 * (the field offsets the device position, not the horizontal) and reaches {@code ArmFeedforward} as
 * radians from horizontal.
 *
 * <h2>Continuous is not the same as "turns more than once"</h2>
 *
 * <p>{@link #isContinuous()} asks whether the closed loop may take the short way around. A turret
 * with 540&deg; of cable-limited travel turns more than once and is <b>not</b> continuous — wrapping
 * it is how a wiring harness gets wound around a bearing. Only a genuinely unlimited azimuth is
 * continuous.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>A {@code RotaryAxis} is declared in a {@code public static final} field, so a throw from a
 * constructor is an {@code ExceptionInInitializerError} — red "Robot Code", no message, dead robot.
 * Problems are stored and reported through {@link #problems()} for the config layer to collect
 * (§5.6).
 *
 * @param gravity the gravity model — {@link GravityMode#COSINE} for anything that swings against
 *     its own weight, {@link GravityMode#NONE} for a turret, roller or flywheel
 * @param horizontalAt the position at which the mechanism is horizontal; meaningful only for {@link
 *     GravityMode#COSINE}, and {@code Degrees.of(0)} otherwise
 * @param continuous whether the closed loop may wrap around the 0/360 boundary
 */
public record RotaryAxis(GravityMode gravity, Angle horizontalAt, boolean continuous)
    implements Axis {

  /**
   * Substitutes safe placeholders for absent values so nothing downstream can throw a {@code
   * NullPointerException} out of a static initialiser. A missing gravity model becomes {@link
   * GravityMode#NONE} and is reported by {@link #problems()}.
   */
  public RotaryAxis {
    if (gravity == null) {
      gravity = GravityMode.NONE;
    }
    if (horizontalAt == null) {
      horizontalAt = Degrees.of(0.0);
    }
  }

  /**
   * An arm — a rotary joint carrying its own weight on a moment arm, so cosine gravity.
   *
   * @param horizontalAt the position at which the arm is horizontal, the one number that makes
   *     cosine gravity correct
   * @return the axis
   */
  public static RotaryAxis arm(Angle horizontalAt) {
    return new RotaryAxis(GravityMode.COSINE, horizontalAt, false);
  }

  /**
   * A pivot. Mechanically an arm by another name, and gravity does not care what you call it.
   *
   * @param horizontalAt the position at which the pivot is horizontal
   * @return the axis
   */
  public static RotaryAxis pivot(Angle horizontalAt) {
    return new RotaryAxis(GravityMode.COSINE, horizontalAt, false);
  }

  /**
   * A wrist — the joint on the end of an arm. Cosine gravity in its own frame.
   *
   * @param horizontalAt the position at which the wrist is horizontal
   * @return the axis
   */
  public static RotaryAxis wrist(Angle horizontalAt) {
    return new RotaryAxis(GravityMode.COSINE, horizontalAt, false);
  }

  /**
   * A shooter hood. Cosine gravity, and usually a very small {@code kG}.
   *
   * @param horizontalAt the position at which the hood is horizontal
   * @return the axis
   */
  public static RotaryAxis hood(Angle horizontalAt) {
    return new RotaryAxis(GravityMode.COSINE, horizontalAt, false);
  }

  /**
   * A turret, which is (usually) gravity-neutral because it rotates about a vertical axis.
   *
   * @param continuous {@code true} only for a genuinely unlimited azimuth. Pass {@code false} when
   *     travel is limited to a finite range <em>even if that range exceeds 360 degrees</em> — a
   *     540&deg; cable-limited turret is not continuous, and wrapping it winds the harness around
   *     the bearing.
   * @return the axis
   */
  public static RotaryAxis turret(boolean continuous) {
    return new RotaryAxis(GravityMode.NONE, Degrees.of(0.0), continuous);
  }

  /**
   * A roller, flywheel or intake — a rotary axis whose position nobody controls and whose load does
   * not change with angle.
   *
   * <p>This is the axis a velocity mechanism wants. It is continuous because a flywheel genuinely
   * does spin without limit, and gravity-neutral because a symmetric spinning mass is.
   *
   * @return the axis
   */
  public static RotaryAxis roller() {
    return new RotaryAxis(GravityMode.NONE, Degrees.of(0.0), true);
  }

  /**
   * Always {@code 360.0} — degrees per output rotation.
   *
   * <p>Degrees, not radians. See the class javadoc: this is decision P3 and it is what makes {@code
   * unitLabel()} {@code "deg"}.
   *
   * @return {@code 360.0}
   */
  @Override
  public double userPerOutputRotation() {
    return 360.0;
  }

  /**
   * Always {@code 2 * Math.PI} — radians per output rotation.
   *
   * <p>This is the single factor that converts a volts-per-radian gain into the vendor's
   * volts-per-output-rotation slot.
   *
   * @return {@code 2 * Math.PI}
   */
  @Override
  public double siPerOutputRotation() {
    return 2.0 * Math.PI;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@link SiDomain#ROTATIONAL_RADIANS}
   */
  @Override
  public SiDomain siDomain() {
    return SiDomain.ROTATIONAL_RADIANS;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "deg"}
   */
  @Override
  public String unitLabel() {
    return "deg";
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "rad"}
   */
  @Override
  public String siLabel() {
    return "rad";
  }

  /**
   * The horizontal reference in user units — degrees.
   *
   * @return {@code horizontalAt} in degrees
   */
  @Override
  public double horizontalReference() {
    return horizontalAt.in(Degrees);
  }

  /**
   * Whether the closed loop may wrap around.
   *
   * <p>This override is not optional and is easy to miss: the record component {@code continuous}
   * generates an accessor spelled {@code continuous()}, which does <b>not</b> satisfy {@link
   * Axis#isContinuous()}. Without this method the record does not compile, and call sites depend on
   * the interface spelling — the Phoenix backend reads it to set {@code
   * ClosedLoopGeneral.ContinuousWrap} and the REV backend to set {@code positionWrapping}.
   *
   * @return {@code continuous}
   */
  @Override
  public boolean isContinuous() {
    return continuous;
  }

  /**
   * {@inheritDoc}
   *
   * @return the problems with this geometry, empty if there are none
   */
  @Override
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    double horizontalDegrees = horizontalAt.in(Degrees);
    if (!Double.isFinite(horizontalDegrees)) {
      out.add(
          String.format(
              Locale.ROOT,
              "RotaryAxis horizontalAt was %s deg; it must be a finite angle. "
                  + "Fix: it is the position at which the mechanism is HORIZONTAL, measured in the "
                  + "same frame as its setpoints — e.g. RotaryAxis.arm(Degrees.of(0.0)) for an arm "
                  + "whose zero is level.",
              horizontalDegrees));
    }
    if (gravity == GravityMode.CONSTANT) {
      out.add(
          "RotaryAxis gravity was CONSTANT. A rotary joint's gravity load scales with the cosine of "
              + "its angle from horizontal, so CONSTANT is only correct for a linear axis. "
              + "Fix: use RotaryAxis.arm(horizontalAt) for COSINE, RotaryAxis.turret(continuous) "
              + "for NONE, or LinearAxis if this mechanism actually travels in a line.");
    }
    return List.copyOf(out);
  }
}

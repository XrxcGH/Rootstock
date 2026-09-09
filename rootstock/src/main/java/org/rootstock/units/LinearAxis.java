package org.rootstock.units;

import static edu.wpi.first.units.Units.Meters;

import edu.wpi.first.units.measure.Distance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rootstock.control.GravityMode;

/**
 * A mechanism that travels in a straight line: an elevator, a slide, a climber, a cascade.
 *
 * <p>The whole axis is one number — metres of carriage travel per rotation of the output shaft:
 *
 * <pre>{@code metersPerOutputRotation = 2 * pi * drumRadius * stages}</pre>
 *
 * <p>and the entire job of the factories below is to get {@code drumRadius} right from the numbers
 * a student can actually read off the robot.
 *
 * <h2>The sprocket derivation, worked out in full, because revision 1 got it wrong</h2>
 *
 * <p><b>Chain advance per drum revolution is exactly {@code teeth * pitch}.</b> One link engages one
 * tooth; N teeth pull N links; the chain — and the carriage bolted to it — advances {@code N * p}.
 * There is no &pi; in that sentence and there must be none in the code. Revision 1 divided by &pi;,
 * and a later revision used the geometric <em>pitch-circle</em> circumference instead, which is a
 * different and also wrong model.
 *
 * <p>For 9143-2025-A's elevator — a 22-tooth #25 sprocket on a two-stage cascade:
 *
 * <pre>
 *   chain advance per drum rotation   = 22 x 0.250 in           =  5.500000 in
 *   cascade multiplication            = 5.500000 in x 2 stages  = 11.000000 in
 *   travel per output rotation        = 11.000000 in            =  0.279400 m   (exact: 11 x 0.0254)
 *   effective radius                  = 0.279400 / (2 * pi)     =  0.0444679 m
 *   single-stage kinematic radius     = 5.500000 / (2 * pi) in  =  0.875352 in  = 0.0222339 m
 * </pre>
 *
 * <p>Compare the two rejected models on the same part:
 *
 * <pre>
 *   22 x 0.250                        = 5.500000 in   chain advance      -- CORRECT, and what this class uses
 *   pi x 0.250 / sin(pi / 22)         = 5.518930 in   pitch circumference -- 0.344% high
 *   22 x 0.250 / pi                   = 1.750704 in   revision 1          -- 3.14x low, catastrophic
 * </pre>
 *
 * <p>The 0.344% version is the dangerous one, because it looks right. Over 55 in of elevator travel
 * it is 0.19 in of accumulated position error baked into a constant nobody re-derives. The
 * difference is <b>chordal action</b>: the chain rides a polygon, not a circle. Six-figure precision
 * on the wrong model is worse than three figures on the right one, so {@code describe()} prints both
 * numbers, labelled, and says which one Rootstock uses.
 *
 * <p>{@link #pulley} needs no such argument and never did: for a timing belt, {@code pitchDiameter =
 * pitch * teeth / pi}, so the circumference is {@code pi * d = teeth * pitch} — the tooth-count
 * advance again. The corrected {@code sprocket} now <em>agrees</em> with {@code pulley} instead of
 * contradicting it, which is itself the check that the model is right.
 *
 * <h2>Gravity</h2>
 *
 * <p>Always {@link GravityMode#CONSTANT}. A carriage weighs the same at every height and the force
 * pulls the same way, so {@code kG} is a constant number of volts. That is derived here rather than
 * configured, so an elevator cannot accidentally be given an arm's cosine model.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>Neither the constructors nor the factories throw, because a {@code LinearAxis} is declared in a
 * {@code public static final} field and a throw from that context is an {@code
 * ExceptionInInitializerError} — red "Robot Code", no message, dead robot, at an event. Bad geometry
 * is stored and reported through {@link #problems()}, which the config layer collects into {@code
 * ConfigError}s so the robot boots, refuses to move, and prints the sentence (§5.6).
 *
 * @param drumRadius the KINEMATIC radius — the radius that reproduces the real advance per
 *     revolution, which for a toothed form is {@code teeth * pitch / (2 * pi)} and is not the radius
 *     you measure with calipers
 * @param stages the number of cascade stages; {@code 1} for a direct pull, {@code 2} for a
 *     continuous-rigged two-stage cascade where the carriage moves twice the drum surface
 * @param rigging how the drum couples to what it moves, retained purely so {@code describe()} can
 *     show its work
 */
public record LinearAxis(Distance drumRadius, int stages, Rigging rigging) implements Axis {

  /**
   * Substitutes safe placeholders for absent values so nothing downstream can throw a {@code
   * NullPointerException} out of a static initialiser. The substituted values are themselves
   * invalid and are reported by {@link #problems()}.
   */
  public LinearAxis {
    if (drumRadius == null) {
      drumRadius = Meters.of(0.0);
    }
    if (rigging == null) {
      rigging = Rigging.cableDrum();
    }
  }

  /**
   * A linear axis from a kinematic radius and a cascade stage count, with no rigging provenance.
   *
   * <p>This is the two-argument shape the design specifies. Prefer {@link #sprocket}, {@link
   * #pulley} or {@link #drum}: they state the part instead of a derived number, and they let {@code
   * describe()} show the derivation rather than a bare radius nobody can check.
   *
   * @param drumRadius the kinematic radius
   * @param stages the number of cascade stages
   */
  public LinearAxis(Distance drumRadius, int stages) {
    this(drumRadius, stages, Rigging.cableDrum());
  }

  /**
   * A chain sprocket, stated by chain pitch and tooth count — the way the part is actually
   * specified.
   *
   * <p>The stored radius is the <b>kinematic</b> radius that reproduces the real chain advance:
   *
   * <pre>{@code drumRadius = teeth * chainPitch / (2 * pi)}</pre>
   *
   * <p>See the class javadoc for why that is {@code teeth * pitch} and not the pitch-circle
   * circumference, and for the worked numbers.
   *
   * <p>{@code LinearAxis.sprocket(Inches.of(0.25), 22, 2)} is 9143-2025-A's elevator geometry:
   * 0.279400 m of travel per drum rotation, an effective radius of 0.0444679 m.
   *
   * @param chainPitch the chain pitch — {@code Inches.of(0.25)} for #25, {@code Inches.of(0.375)}
   *     for #35
   * @param teeth the sprocket's tooth count
   * @param stages the number of cascade stages; {@code 1} for a direct pull
   * @return the axis
   */
  public static LinearAxis sprocket(Distance chainPitch, int teeth, int stages) {
    return toothed(Rigging.sprocket(chainPitch, teeth), stages);
  }

  /**
   * A timing-belt pulley, stated by belt pitch and tooth count.
   *
   * <p>Identical physics to {@link #sprocket}: {@code pitchDiameter = pitch * teeth / pi}, so the
   * circumference is {@code teeth * pitch}. The two factories agree by construction.
   *
   * @param beltPitch the belt pitch — {@code Millimeters.of(5)} for HTD 5mm
   * @param teeth the pulley's tooth count
   * @param stages the number of cascade stages; {@code 1} for a direct pull
   * @return the axis
   */
  public static LinearAxis pulley(Distance beltPitch, int teeth, int stages) {
    return toothed(Rigging.pulley(beltPitch, teeth), stages);
  }

  /**
   * A bare drum wrapped with cable or rope.
   *
   * <p>Here {@code 2 * pi * r} <b>is</b> the right model — a cable has no teeth and therefore no
   * chordal action, so the geometric radius and the kinematic radius are the same number. Kept as a
   * separate factory precisely so that this distinction is visible in a config instead of being
   * something a reader has to infer.
   *
   * @param drumRadius the drum's radius, measured to the centre of the wrapped cable
   * @param stages the number of cascade stages; {@code 1} for a direct pull
   * @return the axis
   */
  public static LinearAxis drum(Distance drumRadius, int stages) {
    return new LinearAxis(drumRadius, stages, Rigging.cableDrum());
  }

  /**
   * Builds a toothed axis by converting its tooth-count advance into a kinematic radius.
   *
   * @param rigging the sprocket or pulley description
   * @param stages the number of cascade stages
   * @return the axis
   */
  private static LinearAxis toothed(Rigging rigging, int stages) {
    double advanceMeters = rigging.advancePerRotation().in(Meters);
    return new LinearAxis(Meters.of(advanceMeters / (2.0 * Math.PI)), stages, rigging);
  }

  /**
   * Metres of carriage travel per output rotation — {@code 2 * pi * drumRadius * stages}.
   *
   * @return metres per output rotation
   */
  @Override
  public double userPerOutputRotation() {
    return 2.0 * Math.PI * drumRadius.in(Meters) * stages;
  }

  /**
   * Metres per output rotation. Identical to {@link #userPerOutputRotation()}, because a linear
   * axis's user unit already <em>is</em> its SI unit — which is exactly why {@code toSi()} is the
   * identity here and the whole SI layer is invisible on an elevator.
   *
   * @return metres per output rotation
   */
  @Override
  public double siPerOutputRotation() {
    return userPerOutputRotation();
  }

  /**
   * {@inheritDoc}
   *
   * @return {@link SiDomain#LINEAR_METERS}
   */
  @Override
  public SiDomain siDomain() {
    return SiDomain.LINEAR_METERS;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "m"}
   */
  @Override
  public String unitLabel() {
    return "m";
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "m"}
   */
  @Override
  public String siLabel() {
    return "m";
  }

  /**
   * Always {@link GravityMode#CONSTANT} — a carriage weighs the same at every height and pulls the
   * same way.
   *
   * @return {@link GravityMode#CONSTANT}
   */
  @Override
  public GravityMode gravity() {
    return GravityMode.CONSTANT;
  }

  /**
   * Always {@code false}. There is no short way around on a rail; wrapping a linear closed loop
   * would drive the carriage into a hard stop.
   *
   * @return {@code false}
   */
  @Override
  public boolean isContinuous() {
    return false;
  }

  /**
   * Always {@code 0.0}. "Horizontal" is a cosine-gravity concept and a linear axis has constant
   * gravity, so there is nothing to reference.
   *
   * @return {@code 0.0}
   */
  @Override
  public double horizontalReference() {
    return 0.0;
  }

  /**
   * The effective radius the physics sim and the {@code kG} derivation both use: the radius that,
   * turned once, produces the full cascade-multiplied travel.
   *
   * <p>{@code drumRadius * stages}. For the 9143-A elevator that is {@code 0.0222339 m x 2 =
   * 0.0444679 m}. It is <b>not</b> {@link #drumRadius()} — feeding {@code ElevatorSim} the
   * un-multiplied drum radius is how a two-stage cascade ends up simulating at half speed.
   *
   * @return the effective radius
   */
  public Distance effectiveRadius() {
    return Meters.of(drumRadius.in(Meters) * stages);
  }

  /**
   * {@inheritDoc}
   *
   * @return the problems with this geometry, empty if there are none
   */
  @Override
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    double radius = drumRadius.in(Meters);
    if (!Double.isFinite(radius) || radius <= 0.0) {
      out.add(
          String.format(
              Locale.ROOT,
              "LinearAxis drumRadius was %s m; it must be finite and strictly positive. "
                  + "Fix: state the part instead of the radius, e.g. "
                  + "LinearAxis.sprocket(Inches.of(0.25), 22, 2) for a 22-tooth #25 sprocket on a "
                  + "2-stage cascade, or LinearAxis.drum(Inches.of(0.75), 1) for a cable drum.",
              radius));
    }
    if (stages < 1) {
      out.add(
          "LinearAxis stages was "
              + stages
              + "; it must be at least 1. Fix: a direct pull is 1 stage, a continuous-rigged "
              + "two-stage cascade is 2. It is a count of stages, not a count of EXTRA stages.");
    }
    if (rigging.isToothed() && rigging.teeth() < 1) {
      out.add(
          "LinearAxis rigging tooth count was "
              + rigging.teeth()
              + "; a sprocket or pulley has at least one tooth. Fix: pass the tooth count printed "
              + "on the part, e.g. LinearAxis.sprocket(Inches.of(0.25), 22, 2).");
    }
    if (rigging.isToothed() && !(rigging.toothPitch().in(Meters) > 0.0)) {
      out.add(
          String.format(
              Locale.ROOT,
              "LinearAxis rigging pitch was %s m; it must be strictly positive. "
                  + "Fix: #25 chain is Inches.of(0.25), #35 chain is Inches.of(0.375), "
                  + "HTD 5mm belt is Millimeters.of(5).",
              rigging.toothPitch().in(Meters)));
    }
    return List.copyOf(out);
  }
}

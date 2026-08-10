package org.pumpkinlib.units;

import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Millimeters;

import edu.wpi.first.units.measure.Distance;
import java.util.Locale;

/**
 * How a {@link LinearAxis}'s effective radius was arrived at — retained so {@code describe()} can
 * show its work.
 *
 * <p><b>Why the provenance is kept instead of multiplied away.</b> A {@code LinearAxis} only needs
 * one number to do its job: metres of travel per output rotation. But the number a student can
 * check against the robot is not that one — it is "22 teeth, #25 chain". When the elevator ends up
 * scaled by 0.34%, or by &pi;, the only way to see it at boot is for the axis to print the
 * derivation that produced the radius, in the same terms the part was ordered in. That is what this
 * record is for. It carries no behaviour that affects a conversion; delete it and every number in
 * the library is unchanged and every mis-scaled mechanism becomes invisible.
 *
 * @param form how the drum couples to whatever it is pulling
 * @param toothPitch the chain or belt pitch; {@code Meters.of(0)} for a smooth cable drum
 * @param teeth the tooth count on the driving sprocket or pulley; {@code 0} for a smooth cable drum
 */
public record Rigging(Form form, Distance toothPitch, int teeth) {

  /** How the drum couples to the thing it moves. */
  public enum Form {
    /**
     * A chain sprocket. Chain advance per revolution is exactly {@code teeth * pitch}: one link
     * engages one tooth, so N teeth pull N links. Not the pitch-circle circumference — see {@link
     * LinearAxis#sprocket}.
     */
    SPROCKET,

    /**
     * A timing-belt pulley. Same physics as a sprocket: {@code pitchDiameter = pitch * teeth / pi},
     * so the circumference is {@code teeth * pitch} — the tooth-count advance again.
     */
    PULLEY,

    /**
     * A bare drum wrapped with cable or rope. Here {@code 2 * pi * r} genuinely <em>is</em> the
     * model, because a cable has no teeth and therefore no chordal action. Kept as its own form so
     * the distinction is visible in a config rather than implied.
     */
    CABLE_DRUM
  }

  /**
   * Normalises a cable drum's unused fields so two cable drums compare equal.
   *
   * <p>Does not throw — a {@code LinearAxis} is declared in a {@code static final} field and a
   * throw from here is an {@code ExceptionInInitializerError}. Bad tooth counts and pitches are
   * reported by {@link LinearAxis#problems()} instead.
   */
  public Rigging {
    if (form == Form.CABLE_DRUM) {
      toothPitch = Meters.of(0.0);
      teeth = 0;
    }
  }

  /**
   * A chain sprocket, stated the way the part is specified in a catalogue.
   *
   * @param chainPitch the chain pitch — {@code Inches.of(0.25)} for #25 chain, {@code
   *     Inches.of(0.375)} for #35
   * @param teeth the sprocket's tooth count
   * @return the rigging description
   */
  public static Rigging sprocket(Distance chainPitch, int teeth) {
    return new Rigging(Form.SPROCKET, chainPitch, teeth);
  }

  /**
   * A timing-belt pulley, stated the way the part is specified in a catalogue.
   *
   * @param beltPitch the belt pitch — {@code Millimeters.of(5)} for HTD 5mm, {@code
   *     Millimeters.of(3)} for GT2 3mm
   * @param teeth the pulley's tooth count
   * @return the rigging description
   */
  public static Rigging pulley(Distance beltPitch, int teeth) {
    return new Rigging(Form.PULLEY, beltPitch, teeth);
  }

  /**
   * A smooth drum wrapped with cable or rope — no teeth, no chordal action, {@code 2 * pi * r} is
   * exact.
   *
   * @return the rigging description
   */
  public static Rigging cableDrum() {
    return new Rigging(Form.CABLE_DRUM, Meters.of(0.0), 0);
  }

  /**
   * Whether this rigging engages discrete teeth, and therefore whether {@code teeth * pitch} is the
   * right model for its advance per revolution.
   *
   * @return true for a sprocket or a pulley, false for a cable drum
   */
  public boolean isToothed() {
    return form != Form.CABLE_DRUM;
  }

  /**
   * The chain or belt advance for one rotation of the drum, before any cascade multiplication.
   *
   * <p>Exactly {@code teeth * pitch} for a toothed form. Zero for a cable drum, where the concept
   * does not apply and {@link LinearAxis#drumRadius()} is the primary datum instead.
   *
   * @return the advance per drum rotation
   */
  public Distance advancePerRotation() {
    return isToothed() ? Meters.of(teeth * toothPitch.in(Meters)) : Meters.of(0.0);
  }

  /**
   * The rigging in one line, the way the part is written on a bill of materials.
   *
   * <p>{@code "#25 chain, 0.250 in pitch x 22 teeth"}, {@code "HTD 5 mm belt, 5.000 mm pitch x 24
   * teeth"}, {@code "cable drum (no teeth)"}.
   *
   * @return the human-readable description
   */
  public String describe() {
    switch (form) {
      case SPROCKET:
        return chainDesignation() + ", " + inches(toothPitch) + " in pitch x " + teeth + " teeth";
      case PULLEY:
        return beltDesignation()
            + ", "
            + String.format(Locale.ROOT, "%.3f", toothPitch.in(Millimeters))
            + " mm pitch x "
            + teeth
            + " teeth";
      case CABLE_DRUM:
      default:
        return "cable drum (no teeth; 2*pi*r is exact)";
    }
  }

  /**
   * The label a mechanical student would use for this chain size, from its pitch.
   *
   * @return {@code "#25 chain"}, {@code "#35 chain"}, {@code "#40 chain"}, or a generic label
   */
  private String chainDesignation() {
    double p = toothPitch.in(Inches);
    if (near(p, 0.250)) {
      return "#25 chain";
    }
    if (near(p, 0.375)) {
      return "#35 chain";
    }
    if (near(p, 0.500)) {
      return "#40/#41 chain";
    }
    return "chain";
  }

  /**
   * The label a mechanical student would use for this belt size, from its pitch.
   *
   * @return {@code "GT2 3 mm belt"}, {@code "HTD 5 mm belt"}, {@code "HTD 9 mm belt"}, or a generic
   *     label
   */
  private String beltDesignation() {
    double mm = toothPitch.in(Millimeters);
    if (near(mm, 3.0)) {
      return "GT2 3 mm belt";
    }
    if (near(mm, 5.0)) {
      return "HTD 5 mm belt";
    }
    if (near(mm, 9.0)) {
      return "HTD 9 mm belt";
    }
    return "belt";
  }

  /**
   * Formats a distance in inches to three decimals, the precision a catalogue quotes.
   *
   * @param d the distance
   * @return the formatted value, with no unit suffix
   */
  private static String inches(Distance d) {
    return String.format(Locale.ROOT, "%.3f", d.in(Inches));
  }

  /**
   * Whether two catalogue numbers are the same size, allowing for floating point.
   *
   * @param a one value
   * @param b the other value
   * @return true if they agree to within a thousandth
   */
  private static boolean near(double a, double b) {
    return Math.abs(a - b) < 1e-3;
  }
}

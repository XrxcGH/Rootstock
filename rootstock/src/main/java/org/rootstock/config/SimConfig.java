package org.rootstock.config;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.KilogramSquareMeters;
import static edu.wpi.first.units.Units.Kilograms;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Radians;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Mass;
import edu.wpi.first.units.measure.MomentOfInertia;
import edu.wpi.first.wpilibj.simulation.SingleJointedArmSim;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The only thing a team writes to get simulation.
 *
 * <p>Everything else a simulated mechanism needs (the motor curve, the gearing, the travel per
 * output rotation, the soft limits) is already in the config, derived from the parts a team
 * declared for the real robot. What is left is the handful of facts that exist only in the physical
 * world and cannot be inferred from a CAN id: how much mass is moving, how it is distributed, and
 * where the mechanism starts when the simulator opens.
 *
 * <h2>Which fields matter depends on the axis</h2>
 *
 * <p>A linear mechanism needs {@link #carriageMass()}; a rotary one needs
 * {@link #momentOfInertia()} and, for gravity, {@link #armLength()}. The fields that do not apply
 * carry {@code NaN} rather than zero, and {@link #hasCarriageMass()} and its siblings are how a
 * plant asks. That distinction is load-bearing: zero mass and "no mass declared" are different
 * situations, and a simulation built on an accidental zero is a mechanism that accelerates
 * instantly and teaches a student nothing.
 *
 * @param carriageMass the moving mass for a linear mechanism; {@code NaN} when not applicable
 * @param momentOfInertia the moment of inertia about the joint for a rotary mechanism, or of the
 *     wheel for a flywheel; {@code NaN} when not applicable
 * @param armLength the distance from the joint to the center of mass, used for gravity torque on a
 *     rotary mechanism; {@code NaN} when not applicable
 * @param startingPosition where the mechanism sits when the simulation starts, as a {@link Distance}
 *     or an {@link Angle}
 * @param simulateGravity whether the plant applies gravity; false for a turret or a flywheel, whose
 *     axis is vertical or whose mass is balanced
 */
public record SimConfig(
    Mass carriageMass,
    MomentOfInertia momentOfInertia,
    Distance armLength,
    Measure<?> startingPosition,
    boolean simulateGravity) {

  /** The value stored in a field that does not apply to this mechanism's axis. */
  public static final double kUnset = Double.NaN;

  /** Rejects the components that no factory can produce as null. */
  public SimConfig {
    carriageMass = carriageMass == null ? Kilograms.of(kUnset) : carriageMass;
    momentOfInertia = momentOfInertia == null ? KilogramSquareMeters.of(kUnset) : momentOfInertia;
    armLength = armLength == null ? Meters.of(kUnset) : armLength;
    startingPosition =
        Objects.requireNonNull(
            startingPosition,
            "SimConfig: a starting position is required. It is where the mechanism sits when the "
                + "simulator opens. Pass Inches.of(0) for an elevator at the bottom, or "
                + "Degrees.of(95) for an arm stowed upright.");
  }

  /**
   * A linear mechanism: a carriage of some mass, starting somewhere, with gravity on.
   *
   * @param carriageMass everything that moves: the carriage, the arm bolted to it, the game piece
   * @param startingPosition where it sits when the simulation starts
   * @return the config
   */
  public static SimConfig linear(Mass carriageMass, Distance startingPosition) {
    return new SimConfig(
        carriageMass,
        KilogramSquareMeters.of(kUnset),
        Meters.of(kUnset),
        startingPosition,
        true);
  }

  /**
   * An arm: a length, a mass and a starting angle, with gravity on.
   *
   * <p>The moment of inertia is estimated from the length and the mass with
   * {@link #estimateArmMoi(Distance, Mass)}, and the center of mass is taken as half the length,
   * the uniform-bar approximation. It is an approximation, and a good one for a first simulation;
   * a team that has a CAD model should pass the real number through
   * {@link #rotary(MomentOfInertia, Distance, Angle)}.
   *
   * @param length the distance from the joint to the far end of the arm
   * @param mass the arm's mass
   * @param startingPosition the angle the arm sits at when the simulation starts
   * @return the config
   */
  public static SimConfig arm(Distance length, Mass mass, Angle startingPosition) {
    Objects.requireNonNull(length, kNullLength);
    Objects.requireNonNull(mass, kNullMass);
    return new SimConfig(
        Kilograms.of(kUnset),
        estimateArmMoi(length, mass),
        length.div(2.0),
        startingPosition,
        true);
  }

  /**
   * A rotary mechanism whose moment of inertia and center of mass are known, with gravity on.
   *
   * @param momentOfInertia the moment of inertia about the joint
   * @param centreOfMass the distance from the joint to the center of mass
   * @param startingPosition the angle the mechanism sits at when the simulation starts
   * @return the config
   */
  public static SimConfig rotary(
      MomentOfInertia momentOfInertia, Distance centreOfMass, Angle startingPosition) {
    return new SimConfig(
        Kilograms.of(kUnset), momentOfInertia, centreOfMass, startingPosition, true);
  }

  /**
   * A flywheel or a roller: a moment of inertia and nothing else, with gravity off.
   *
   * <p>A flywheel's mass is balanced about its axis, so gravity contributes no net torque and
   * simulating it would be modeling a force that is not there.
   *
   * @param momentOfInertia the moment of inertia of the wheel and everything geared to it
   * @return the config
   */
  public static SimConfig flywheel(MomentOfInertia momentOfInertia) {
    return new SimConfig(
        Kilograms.of(kUnset), momentOfInertia, Meters.of(kUnset), Degrees.of(0.0), false);
  }

  /**
   * The moment of inertia of a uniform bar rotating about one end.
   *
   * <p>Wraps WPILib's own estimate, {@code m*L^2/3}, so that the number a Rootstock simulation uses
   * is the number WPILib's arm simulation was written against.
   *
   * @param length the length of the arm
   * @param mass the mass of the arm
   * @return the estimated moment of inertia about the joint
   */
  public static MomentOfInertia estimateArmMoi(Distance length, Mass mass) {
    Objects.requireNonNull(length, kNullLength);
    Objects.requireNonNull(mass, kNullMass);
    return KilogramSquareMeters.of(
        SingleJointedArmSim.estimateMOI(length.in(Meters), mass.in(Kilograms)));
  }

  /**
   * A copy starting at a different linear position.
   *
   * @param value the new starting position
   * @return a copy
   */
  public SimConfig withStartingPosition(Distance value) {
    return new SimConfig(carriageMass, momentOfInertia, armLength, value, simulateGravity);
  }

  /**
   * A copy starting at a different angle.
   *
   * @param value the new starting position
   * @return a copy
   */
  public SimConfig withStartingPosition(Angle value) {
    return new SimConfig(carriageMass, momentOfInertia, armLength, value, simulateGravity);
  }

  /**
   * A copy with gravity simulation turned on or off.
   *
   * @param value whether the plant applies gravity
   * @return a copy
   */
  public SimConfig withGravity(boolean value) {
    return new SimConfig(carriageMass, momentOfInertia, armLength, startingPosition, value);
  }

  /**
   * Whether a moving mass was declared.
   *
   * @return true when {@link #massKg()} is a real number
   */
  public boolean hasCarriageMass() {
    return Double.isFinite(massKg());
  }

  /**
   * Whether a moment of inertia was declared.
   *
   * @return true when {@link #moiKgM2()} is a real number
   */
  public boolean hasMomentOfInertia() {
    return Double.isFinite(moiKgM2());
  }

  /**
   * Whether an arm length was declared.
   *
   * @return true when {@link #armLengthMeters()} is a real number
   */
  public boolean hasArmLength() {
    return Double.isFinite(armLengthMeters());
  }

  /**
   * The moving mass in kilograms, for a plant that wants a bare double.
   *
   * @return kilograms, or {@code NaN} when not declared
   */
  public double massKg() {
    return carriageMass.in(Kilograms);
  }

  /**
   * The moment of inertia in kilogram square meters.
   *
   * @return kg*m^2, or {@code NaN} when not declared
   */
  public double moiKgM2() {
    return momentOfInertia.in(KilogramSquareMeters);
  }

  /**
   * The distance from the joint to the center of mass, in meters.
   *
   * @return meters, or {@code NaN} when not declared
   */
  public double armLengthMeters() {
    return armLength.in(Meters);
  }

  /**
   * The starting position in SI: meters for a linear mechanism, radians for a rotary one.
   *
   * <p>SI rather than user units because the simulation plant is the one place in the library that
   * is unambiguously SI: WPILib's own plants take meters and radians.
   *
   * @return meters or radians, or {@code NaN} when the starting position is neither a distance nor
   *     an angle
   */
  public double startingPositionSi() {
    if (startingPosition instanceof Distance d) {
      return d.in(Meters);
    }
    if (startingPosition instanceof Angle a) {
      return a.in(Radians);
    }
    return Double.NaN;
  }

  /**
   * Every problem visible from this config alone.
   *
   * <p><b>Never throws, never returns null.</b> Whether the <em>right</em> field is populated for
   * this mechanism's axis needs the axis, so it belongs to validation; what is checked here is that
   * whatever was declared is physically possible.
   *
   * @return the problems, in declaration order; empty when the config is fine
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    if (hasCarriageMass() && massKg() <= 0.0) {
      out.add(
          "SimConfig: carriage mass = "
              + fmt(massKg(), "kg")
              + ", which must be greater than zero. A massless carriage accelerates instantly, so "
              + "the simulation will look nothing like the robot. Fix: weigh the carriage, or "
              + "estimate from CAD. Being within 30% is plenty.");
    }
    if (hasMomentOfInertia() && moiKgM2() <= 0.0) {
      out.add(
          "SimConfig: moment of inertia = "
              + fmt(moiKgM2(), "kg m^2")
              + ", which must be greater than zero. Fix: SimConfig.estimateArmMoi(length, mass) "
              + "for an arm, or the number from CAD for a flywheel.");
    }
    if (hasArmLength() && armLengthMeters() <= 0.0) {
      out.add(
          "SimConfig: arm length = "
              + fmt(armLengthMeters(), "m")
              + ", which must be greater than zero. It is the distance from the joint to the center "
              + "of mass, and it is what gravity torque is computed from.");
    }
    if (Double.isNaN(startingPositionSi())) {
      out.add(
          "SimConfig: the starting position is a "
              + startingPosition.getClass().getSimpleName()
              + ", which is neither a Distance nor an Angle. Fix: Inches.of(0) for a linear "
              + "mechanism, Degrees.of(95) for a rotary one.");
    }
    if (simulateGravity && !hasCarriageMass() && !hasArmLength()) {
      out.add(
          "SimConfig: gravity is on, but neither a carriage mass nor an arm length was declared, so "
              + "there is nothing for gravity to act on and the simulated mechanism will float. "
              + "Fix: SimConfig.linear(mass, start) for an elevator, SimConfig.arm(length, mass, "
              + "start) for an arm, or SimConfig.flywheel(moi) if gravity genuinely does not apply.");
    }
    return List.copyOf(out);
  }

  /**
   * The config as the boot dump prints it, listing only the fields that apply.
   *
   * @return a human-readable description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    if (hasCarriageMass()) {
      sb.append("moving mass ").append(fmt(massKg(), "kg"));
    }
    if (hasMomentOfInertia()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append("MOI ").append(fmt(moiKgM2(), "kg m^2"));
    }
    if (hasArmLength()) {
      sb.append(", center of mass ").append(fmt(armLengthMeters(), "m")).append(" from the joint");
    }
    if (sb.length() == 0) {
      sb.append("nothing declared");
    }
    sb.append(", starts at ").append(describeStart());
    sb.append(simulateGravity ? ", gravity ON" : ", gravity off");
    return sb.toString();
  }

  /**
   * Value equality that treats the {@link #kUnset} sentinel as equal to itself.
   *
   * <p>The record's generated {@code equals} delegates to {@code Measure.equals}, which compares
   * magnitudes numerically, and {@link #kUnset} is {@code NaN}, which is not numerically equal to
   * anything including itself. So two {@code SimConfig.linear(...)} values built from identical
   * inputs compared <em>unequal</em>, and that inequality propagated up through {@link
   * PositionConfig}, {@link VelocityConfig} and {@link SimpleConfig}, all of which carry a {@code
   * SimConfig} component. Any {@code List.contains}, {@code Map} key or overlay diff over a config
   * was therefore silently wrong on every mechanism that did not declare all four sim fields,
   * which is every mechanism, since no axis uses more than two of them.
   *
   * <p>The comparison below uses {@link Double#compare}, whose NaN ordering is total, so an unset
   * field equals an unset field and a declared one still compares by magnitude.
   *
   * @param obj the object to compare against
   * @return true when both describe the same simulated plant
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof SimConfig other)) {
      return false;
    }
    return simulateGravity == other.simulateGravity
        && sameMeasure(carriageMass, other.carriageMass)
        && sameMeasure(momentOfInertia, other.momentOfInertia)
        && sameMeasure(armLength, other.armLength)
        && sameMeasure(startingPosition, other.startingPosition);
  }

  /**
   * A hash consistent with {@link #equals(Object)}.
   *
   * @return the hash
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        Boolean.valueOf(simulateGravity),
        hashMeasure(carriageMass),
        hashMeasure(momentOfInertia),
        hashMeasure(armLength),
        hashMeasure(startingPosition));
  }

  /** True when two measures name the same unit and the same magnitude, NaN included. */
  private static boolean sameMeasure(Measure<?> a, Measure<?> b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.unit().equals(b.unit())
        && Double.compare(a.baseUnitMagnitude(), b.baseUnitMagnitude()) == 0;
  }

  /** The hash half of {@link #sameMeasure}; {@code Double.hashCode(NaN)} is stable. */
  private static int hashMeasure(Measure<?> measure) {
    return measure == null
        ? 0
        : 31 * measure.unit().hashCode() + Double.hashCode(measure.baseUnitMagnitude());
  }

  private String describeStart() {
    if (startingPosition instanceof Distance d) {
      return String.format(Locale.ROOT, "%.4f m", d.in(Meters));
    }
    if (startingPosition instanceof Angle a) {
      return String.format(Locale.ROOT, "%.2f deg", a.in(Degrees));
    }
    return "an unrecognized measure";
  }

  private static String fmt(double value, String unit) {
    return String.format(Locale.ROOT, "%.5f %s", value, unit);
  }

  private static final String kNullLength =
      "SimConfig: the arm length must not be null. It is the distance from the joint to the far end "
          + "of the arm. Measure it on the robot.";

  private static final String kNullMass =
      "SimConfig: the mass must not be null. Weigh it, or estimate from CAD; within 30% is plenty "
          + "for a simulation that teaches the right lessons.";
}

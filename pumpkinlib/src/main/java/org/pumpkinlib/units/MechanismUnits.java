package org.pumpkinlib.units;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.RotationsPerSecond;

import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.Distance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.pumpkinlib.pure.units.Reduction;

/**
 * The one converter. Built once from a {@link Reduction} and an {@link Axis}, and handed to the
 * motor IO, the physics sim, the soft-limit derivation, the telemetry namespace and the tuning UI.
 *
 * <p><b>There is no other converter in PumpkinLib, and mechanism code never performs a unit
 * conversion by hand.</b> That sentence is the whole point of this class, and it is worth being
 * concrete about what it deletes. Across the maintainer's four robot repositories there are 41
 * inline {@code * 360.0} conversions, a family of {@code angle / 360.0 / GEAR_RATIO} expressions,
 * one place where the ratio is applied twice while seeding a position, and a constant written
 * {@code TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0} whose negative sign exists to cancel another sign
 * elsewhere. Every one of those is an arithmetic expression a human typed, in a file where nothing
 * checks it, and every one of them is a mechanism that drives into a hard stop the day somebody
 * fixes an encoder phase.
 *
 * <p>The fix is not "be careful". It is structural: the gearbox is applied <b>exactly once</b>,
 * inside the backend's device configuration, and the geometry is applied <b>exactly once</b>,
 * here, on the way in and out of the {@code MotorIO} seam. Nothing above the seam multiplies by a
 * ratio, because nothing above the seam is ever handed a ratio.
 *
 * <h2>The four layers</h2>
 *
 * <pre>
 *   USER UNITS         SI UNITS            OUTPUT ROTATIONS          ROTOR ROTATIONS
 *   m / deg     &lt;--&gt;   m / rad     &lt;--&gt;    double (the seam)  &lt;--&gt;   inside the vendor
 *   what a human       what GAINS and      drum rot, joint rot       Phoenix SensorToMechanismRatio
 *   types and reads    every RIO-side                                REV positionConversionFactor
 *                      controller use
 *        ^                  ^                     ^                          ^
 *        |                  |                     |                          |
 *      Axis          MechanismUnits.toSi()   MotorIO seam                Reduction
 *    (geometry)      (identity for LINEAR;                              (gearbox)
 *                     deg-&gt;rad for ROTARY)
 * </pre>
 *
 * <p>Which layer a number is in, for every type in the library:
 *
 * <table border="1">
 *   <caption>The units table, also printed by {@code describe()}</caption>
 *   <tr><th>Quantity</th><th>Units</th></tr>
 *   <tr><td>{@code Gains.kP}</td><td><b>V/m</b> (linear) or <b>V/rad</b> (rotary)</td></tr>
 *   <tr><td>{@code Gains.kV}</td><td><b>V/(m/s)</b> or <b>V/(rad/s)</b></td></tr>
 *   <tr><td>{@code Gains.kS}, {@code Gains.kG}</td><td><b>V</b></td></tr>
 *   <tr><td>{@code MotionConstraints.maxVelocity}</td><td><b>user</b>/s — m/s or <b>deg</b>/s</td></tr>
 *   <tr><td>{@code MotionConstraints.maxAcceleration}</td><td><b>user</b>/s&sup2;</td></tr>
 *   <tr><td>{@code MotionConstraints.jerk}</td><td><b>user</b>/s&sup3;</td></tr>
 *   <tr><td>{@code Setpoint.value}, {@code PositionLimits}, tolerances</td><td>typed {@code Measure}</td></tr>
 *   <tr><td>{@code PositionMechanism.goal()}/{@code measured()}</td><td><b>user</b> — m or deg</td></tr>
 *   <tr><td>{@code MotorIO.setPositionGoal(...)}</td><td><b>output rotations</b>, output rot/s, V</td></tr>
 * </table>
 *
 * <p><b>Gains are SI and constraints are user units on purpose.</b> Gains are machine numbers that
 * the tuning wizard measures and {@code gains.json} persists, and they must stay portable across a
 * change of drum diameter. Constraints are numbers a driver reasons about out loud — "make the
 * elevator go 1.6 m/s". The split is stated once, here, and printed by {@link #describe()}.
 *
 * <p><b>{@code Measure} at the boundary, {@code double} inside.</b> The typed overloads exist for
 * config authoring and public mechanism signatures. Inside {@code periodic()} everything is a
 * {@code double}, because allocating a {@code Measure} every loop on a roboRIO is a real cost and
 * because the conversion has already happened, once, here.
 *
 * <p>Immutable and thread-safe. Never throws from construction — see {@link #problems()}.
 */
public final class MechanismUnits {

  /** Column width for the label in {@link #describe()}; "travel per rotor rot" is 20 characters. */
  private static final int kLabelWidth = 21;

  /** Indent for a continuation line under a {@link #describe()} label. */
  private static final String kContinuation = " ".repeat(2 + kLabelWidth);

  private final Reduction m_reduction;
  private final Axis m_axis;
  private final List<String> m_constructionProblems;

  /**
   * Builds the converter for one mechanism.
   *
   * <p>Build it once, at config time, and pass it around. Building a second one is not wrong, it is
   * just a second place for the two numbers to disagree.
   *
   * <p>Does not throw. A missing argument is replaced by a placeholder that is itself invalid and is
   * reported by {@link #problems()}, because configs are declared in {@code static final} fields and
   * a throw from there is an {@code ExceptionInInitializerError} with no readable message (§5.6).
   *
   * @param reduction the gearbox, rotor rotations per output rotation
   * @param axis the geometry, output rotations to user units
   */
  public MechanismUnits(Reduction reduction, Axis axis) {
    List<String> problems = new ArrayList<>();
    if (reduction == null) {
      problems.add(
          "MechanismUnits was given no Reduction. Fix: declare the gearbox, e.g. "
              + ".reduction(Reduction.ofStages(3.0, 4.0)) for a 12:1, or "
              + ".reduction(Reduction.ofTeeth(58, 10).then(58, 18)) if you know the tooth counts. "
              + "Use Reduction.IDENTITY for a genuine direct drive.");
      reduction = Reduction.IDENTITY;
    }
    if (axis == null) {
      problems.add(
          "MechanismUnits was given no Axis, so there is no way to know what one output rotation "
              + "means. Fix: declare the geometry, e.g. "
              + ".axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2)) for an elevator, or "
              + ".axis(RotaryAxis.arm(Degrees.of(0.0))) for an arm that is level at zero.");
      axis = new LinearAxis(Meters.of(0.0), 0);
    }
    m_reduction = reduction;
    m_axis = axis;
    m_constructionProblems = List.copyOf(problems);
  }

  /**
   * Builds the converter for one mechanism.
   *
   * @param reduction the gearbox, rotor rotations per output rotation
   * @param axis the geometry, output rotations to user units
   * @return the converter
   */
  public static MechanismUnits of(Reduction reduction, Axis axis) {
    return new MechanismUnits(reduction, axis);
  }

  // ===============================================================================================
  // Seam <-> user. The MotorIO seam speaks OUTPUT ROTATIONS; humans speak metres and degrees.
  // ===============================================================================================

  /**
   * Output rotations to user units — metres, or degrees.
   *
   * <p>This is the read direction of the seam: what the mechanism reports to a dashboard, a
   * setpoint comparison, or a student.
   *
   * @param outputRotations a position at the output shaft, in rotations
   * @return the position in user units
   */
  public double toUser(double outputRotations) {
    return outputRotations * m_axis.userPerOutputRotation();
  }

  /**
   * User units to output rotations — the write direction of the seam.
   *
   * @param userUnits a position in metres or degrees
   * @return the position at the output shaft, in rotations
   */
  public double toOutputRotations(double userUnits) {
    return userUnits / m_axis.userPerOutputRotation();
  }

  /**
   * Output rot/s to user units per second.
   *
   * @param outputRps a velocity at the output shaft, in rotations per second
   * @return the velocity in user units per second
   */
  public double toUserPerSec(double outputRps) {
    return outputRps * m_axis.userPerOutputRotation();
  }

  /**
   * User units per second to output rot/s. The conversion {@code MotionConstraints.maxVelocity}
   * goes through on its way to the device's cruise-velocity field.
   *
   * @param userPerSec a velocity in m/s or deg/s
   * @return the velocity at the output shaft, in rotations per second
   */
  public double toOutputRps(double userPerSec) {
    return userPerSec / m_axis.userPerOutputRotation();
  }

  /**
   * Output rot/s&sup2; to user units per second squared.
   *
   * @param outputRps2 an acceleration at the output shaft, in rot/s&sup2;
   * @return the acceleration in user units per second squared
   */
  public double toUserPerSec2(double outputRps2) {
    return outputRps2 * m_axis.userPerOutputRotation();
  }

  /**
   * User units per second squared to output rot/s&sup2;. The conversion {@code
   * MotionConstraints.maxAcceleration} goes through on its way to the device.
   *
   * @param userPerSecSquared an acceleration in m/s&sup2; or deg/s&sup2;
   * @return the acceleration at the output shaft, in rot/s&sup2;
   */
  public double toOutputRps2(double userPerSecSquared) {
    return userPerSecSquared / m_axis.userPerOutputRotation();
  }

  /**
   * User units per second cubed to output rot/s&sup3; — the third derivative, and its absence was a
   * live bug.
   *
   * <p>Both the device-configuration path and the dynamic-profile path passed {@code
   * MotionConstraints.jerk()} <b>raw</b> into a field documented as rot/s&sup3;, so a rotary axis
   * was off by a factor of 360 and a linear one by {@code 1 / travelPerOutputRotation}. The shape is
   * identical to {@link #toOutputRps} and {@link #toOutputRps2} — {@code j / userPerOutputRotation()}
   * — which is exactly why leaving it out, and inlining nothing in its place, was so easy to miss.
   *
   * @param userPerSecCubed a jerk in m/s&sup3; or deg/s&sup3;
   * @return the jerk at the output shaft, in rot/s&sup3;
   */
  public double toOutputRps3(double userPerSecCubed) {
    return userPerSecCubed / m_axis.userPerOutputRotation();
  }

  /**
   * Output rot/s&sup3; to user units per second cubed.
   *
   * @param outputRps3 a jerk at the output shaft, in rot/s&sup3;
   * @return the jerk in user units per second cubed
   */
  public double toUserPerSec3(double outputRps3) {
    return outputRps3 * m_axis.userPerOutputRotation();
  }

  /**
   * User units to ROTOR rotations — what the motor's own encoder counts, before the gearbox.
   *
   * <p>Needed only for seeding a rotor-side position or reading a raw rotor signal. It applies the
   * gearbox <b>and</b> the geometry, once each: this is the one method whose job is to do both, and
   * having it means nobody writes {@code position / 360.0 / GEAR_RATIO} at a call site and nobody
   * applies the ratio a second time on top of a value that already had it.
   *
   * @param userUnits a position in metres or degrees
   * @return the position at the rotor, in rotations
   */
  public double toRotorRotations(double userUnits) {
    return m_reduction.unreduce(toOutputRotations(userUnits));
  }

  /**
   * ROTOR rotations to user units. The inverse of {@link #toRotorRotations(double)}.
   *
   * @param rotorRotations a position at the rotor, in rotations
   * @return the position in user units
   */
  public double toUserFromRotorRotations(double rotorRotations) {
    return toUser(m_reduction.reduce(rotorRotations));
  }

  // ===============================================================================================
  // User <-> SI. The layer an earlier revision leaked without naming.
  // ===============================================================================================

  /**
   * User units to SI — the identity on a linear axis, {@code Math.toRadians} on a rotary one.
   *
   * <p>Applied in exactly one place in the running library: the {@code RIO_FULL} branch of
   * {@code applyClosedLoop()}, plus the gain conversions in the backend adapters. If you find
   * yourself calling this in a subsystem, something above you has already got the layer wrong.
   *
   * @param userUnits a value in metres or degrees
   * @return the value in metres or radians
   */
  public double toSi(double userUnits) {
    return m_axis.siDomain().toSi(userUnits);
  }

  /**
   * SI to user units. The exact inverse of {@link #toSi(double)}.
   *
   * @param siUnits a value in metres or radians
   * @return the value in metres or degrees
   */
  public double fromSi(double siUnits) {
    return m_axis.siDomain().fromSi(siUnits);
  }

  /**
   * User units per second to SI per second.
   *
   * @param userPerSec a velocity in m/s or deg/s
   * @return the velocity in m/s or rad/s
   */
  public double toSiPerSec(double userPerSec) {
    return m_axis.siDomain().toSi(userPerSec);
  }

  /**
   * SI per second to user units per second.
   *
   * @param siPerSec a velocity in m/s or rad/s
   * @return the velocity in m/s or deg/s
   */
  public double fromSiPerSec(double siPerSec) {
    return m_axis.siDomain().fromSi(siPerSec);
  }

  /**
   * User units per second squared to SI per second squared.
   *
   * @param userPerSecSquared an acceleration in m/s&sup2; or deg/s&sup2;
   * @return the acceleration in m/s&sup2; or rad/s&sup2;
   */
  public double toSiPerSec2(double userPerSecSquared) {
    return m_axis.siDomain().toSi(userPerSecSquared);
  }

  /**
   * SI per second squared to user units per second squared.
   *
   * @param siPerSec2 an acceleration in m/s&sup2; or rad/s&sup2;
   * @return the acceleration in m/s&sup2; or deg/s&sup2;
   */
  public double fromSiPerSec2(double siPerSec2) {
    return m_axis.siDomain().fromSi(siPerSec2);
  }

  /**
   * Radians per output rotation ({@code 2 * pi}) or metres per output rotation.
   *
   * <p><b>The one factor that converts volts-per-SI gains into the vendor's
   * volts-per-output-rotation slot.</b> A {@code kP} of 12 V/rad becomes {@code 12 * 2 * pi} volts
   * per output rotation on the device; a {@code kP} of 40 V/m on the 9143-A elevator becomes
   * {@code 40 * 0.279400}. Get this factor wrong and the loop is off by 6.28 or by 3.6, both of
   * which oscillate impressively.
   *
   * @return SI units per output rotation
   */
  public double siPerOutputRotation() {
    return m_axis.siPerOutputRotation();
  }

  /**
   * User units per output rotation — metres, or 360 degrees.
   *
   * @return user units per output rotation
   */
  public double userPerOutputRotation() {
    return m_axis.userPerOutputRotation();
  }

  /**
   * Which SI quantity this mechanism's gains live in.
   *
   * @return the SI domain
   */
  public SiDomain siDomain() {
    return m_axis.siDomain();
  }

  /**
   * The SI unit's label, for anything derived from {@link #siPerOutputRotation()}.
   *
   * @return {@code "m"} or {@code "rad"}
   */
  public String siLabel() {
    return m_axis.siLabel();
  }

  /**
   * The cosine-gravity horizontal reference, converted into SI.
   *
   * <p>Radians from the axis's zero for a rotary axis; zero for a linear one. This is the form
   * {@code ArmFeedforward} wants, and the form the Phoenix adapter negates on its way into {@code
   * Slot0Configs.GravityArmPositionOffset}.
   *
   * @return the horizontal reference in SI units
   */
  public double horizontalReferenceSi() {
    return toSi(m_axis.horizontalReference());
  }

  // ===============================================================================================
  // Convenience for Measure-typed callers. Config boundaries and public mechanism signatures only.
  // ===============================================================================================

  /**
   * A distance to output rotations, for a linear axis.
   *
   * @param d the distance
   * @return the position at the output shaft, in rotations
   * @throws IllegalArgumentException if this mechanism is a rotary axis, where a distance is not a
   *     position
   */
  public double toOutputRotations(Distance d) {
    requireDomain(SiDomain.LINEAR_METERS, "a Distance");
    return toOutputRotations(d.in(Meters));
  }

  /**
   * An angle to output rotations, for a rotary axis.
   *
   * <p>Converted through <b>degrees</b>, the rotary user unit, so the number that reaches the seam
   * is the same one {@link #toOutputRotations(double)} would produce.
   *
   * @param a the angle
   * @return the position at the output shaft, in rotations
   * @throws IllegalArgumentException if this mechanism is a linear axis, where an angle is not a
   *     position
   */
  public double toOutputRotations(Angle a) {
    requireDomain(SiDomain.ROTATIONAL_RADIANS, "an Angle");
    return toOutputRotations(a.in(Degrees));
  }

  /**
   * Output rotations to a typed distance, for a linear axis.
   *
   * @param outputRotations a position at the output shaft, in rotations
   * @return the distance travelled
   * @throws IllegalArgumentException if this mechanism is a rotary axis
   */
  public Distance toDistance(double outputRotations) {
    requireDomain(SiDomain.LINEAR_METERS, "a Distance");
    return Meters.of(toUser(outputRotations));
  }

  /**
   * Output rotations to a typed angle, for a rotary axis.
   *
   * @param outputRotations a position at the output shaft, in rotations
   * @return the angle swept
   * @throws IllegalArgumentException if this mechanism is a linear axis
   */
  public Angle toAngle(double outputRotations) {
    requireDomain(SiDomain.ROTATIONAL_RADIANS, "an Angle");
    return Degrees.of(toUser(outputRotations));
  }

  // ===============================================================================================
  // Sim, and the free-speed sanity check.
  // ===============================================================================================

  /**
   * Rotor rotations per output rotation — the gearbox, restated for callers who have the units
   * object but not the reduction.
   *
   * @return the gear ratio
   */
  public double rotorPerOutput() {
    return m_reduction.rotorPerOutput();
  }

  /**
   * User units of travel per ROTOR rotation — the finest increment the motor's own encoder can
   * resolve, in the units a human reads.
   *
   * <p>Useful as a sanity check: 0.0232833 m per rotor rotation on the 9143-A elevator means one
   * rotor rotation is 0.92 in of carriage travel, which tells you immediately whether a 0.5 in
   * tolerance is achievable.
   *
   * @return user units per rotor rotation
   */
  public double userPerRotorRotation() {
    return m_axis.userPerOutputRotation() * m_reduction.outputPerRotor();
  }

  /**
   * Free speed of the mechanism at the carriage or joint, from the motor's free speed.
   *
   * <p><b>The check this exists for.</b> A config that asks for 1.6 m/s from a mechanism whose free
   * speed is 0.62 m/s will simply never reach its cruise velocity, and the symptom — "the profile
   * says it should be there and it isn't" — sends a team looking at gains for a week. Comparing the
   * requested cruise against this number at boot catches it in one line of {@code describe()}
   * output. A useful ceiling is about 80% of free speed under load.
   *
   * <p>Motor <em>count</em> does not appear here on purpose: adding a second motor doubles the
   * available torque, not the free speed. Torque headroom is a separate calculation and belongs
   * with the plant model, not with a unit conversion.
   *
   * @param motorFreeSpeedRotorRps the motor's free speed in ROTOR rotations per second — 96.667 for
   *     a Kraken X60 at its 5800 rpm FOC free speed
   * @return the free speed at the carriage or joint, in user units per second
   */
  public double freeSpeedUserPerSec(double motorFreeSpeedRotorRps) {
    return m_reduction.reduce(motorFreeSpeedRotorRps) * m_axis.userPerOutputRotation();
  }

  /**
   * Free speed of the mechanism at the carriage or joint, from a typed motor free speed.
   *
   * @param motorFreeSpeed the motor's free speed at the rotor, e.g. {@code RPM.of(5800)}
   * @return the free speed at the carriage or joint, in user units per second
   */
  public double freeSpeedUserPerSec(AngularVelocity motorFreeSpeed) {
    return freeSpeedUserPerSec(motorFreeSpeed.in(RotationsPerSecond));
  }

  // ===============================================================================================
  // Accessors, validation, description.
  // ===============================================================================================

  /**
   * The gearbox this converter was built from.
   *
   * @return the reduction
   */
  public Reduction reduction() {
    return m_reduction;
  }

  /**
   * The geometry this converter was built from.
   *
   * @return the axis
   */
  public Axis axis() {
    return m_axis;
  }

  /**
   * The user unit's label — {@code "m"} or {@code "deg"}. The label that belongs on every telemetry
   * key and every setpoint this mechanism publishes.
   *
   * @return the user unit label
   */
  public String unitLabel() {
    return m_axis.unitLabel();
  }

  /**
   * Everything structurally wrong with this mechanism's units, in plain sentences.
   *
   * <p>Collected, never thrown, so the config layer can print all of a robot's errors at once and
   * boot into safe mode instead of dying in a static initialiser (§5.6).
   *
   * @return an unmodifiable list of problem descriptions; empty when the units are usable
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>(m_constructionProblems);
    out.addAll(m_axis.problems());
    double perRotation = m_axis.userPerOutputRotation();
    if (!Double.isFinite(perRotation) || perRotation <= 0.0) {
      out.add(
          String.format(
              Locale.ROOT,
              "Travel per output rotation came out as %s %s, which makes every conversion in this "
                  + "mechanism meaningless (division by it produces infinity or NaN). "
                  + "Fix: check the axis geometry above — the drum radius, the tooth count and the "
                  + "cascade stage count all have to be strictly positive.",
              perRotation,
              m_axis.unitLabel()));
    }
    return List.copyOf(out);
  }

  /**
   * The full derivation chain, printed.
   *
   * <p>Printed at boot, included in the config snapshot, and shown by {@code pumpkin doctor}. It
   * exists so that a mis-scaled mechanism is <b>readable at boot</b> rather than discovered on the
   * field: every number here is computed from the declared reduction and axis, and a student
   * comparing the printed chain against the hardware can find a wrong tooth count in ten seconds.
   *
   * <p>It is also the direct answer to the complaint that a flat constants file "doesn't reveal
   * anything about the attached real-world system". A config that can describe itself is a config a
   * control-system adviser can check at an event.
   *
   * @return a multi-line block, no trailing newline
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    row(sb, "gearbox", m_reduction.describe());
    if (m_axis instanceof LinearAxis linear) {
      describeLinear(sb, linear);
    } else if (m_axis instanceof RotaryAxis rotary) {
      describeRotary(sb, rotary);
    }
    row(
        sb,
        "SI domain",
        m_axis.siDomain()
            + " -- gains are volts-per-"
            + (m_axis.siDomain() == SiDomain.LINEAR_METERS ? "meter" : "radian")
            + "; toSi() "
            + (m_axis.siDomain() == SiDomain.LINEAR_METERS
                ? "is the identity"
                : "= Math.toRadians"));
    row(sb, "gravity", describeGravity());
    return sb.toString().stripTrailing();
  }

  /**
   * The full derivation chain with the mechanism's name on the first line.
   *
   * @param mechanismName the mechanism's name, e.g. {@code "Elevator"}
   * @return a multi-line block beginning {@code "<name> geometry"}, no trailing newline
   */
  public String describe(String mechanismName) {
    return mechanismName + " geometry\n" + describe();
  }

  @Override
  public String toString() {
    return "MechanismUnits["
        + m_reduction.describe()
        + "; "
        + String.format(Locale.ROOT, "%.6f", m_axis.userPerOutputRotation())
        + " "
        + m_axis.unitLabel()
        + " per output rot]";
  }

  /**
   * Value equality on the two things the converter was built from.
   *
   * @param obj the object to compare against
   * @return true if {@code obj} is a MechanismUnits with an equal reduction and axis
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MechanismUnits other)) {
      return false;
    }
    return m_reduction.equals(other.m_reduction) && m_axis.equals(other.m_axis);
  }

  @Override
  public int hashCode() {
    return 31 * m_reduction.hashCode() + m_axis.hashCode();
  }

  // ===============================================================================================
  // describe() internals
  // ===============================================================================================

  /**
   * Appends the geometry rows for a linear axis, showing the tooth-count derivation in full.
   *
   * @param sb the buffer to append to
   * @param linear the axis
   */
  private void describeLinear(StringBuilder sb, LinearAxis linear) {
    Rigging rigging = linear.rigging();
    double pitchInches = rigging.toothPitch().in(Inches);
    int teeth = rigging.teeth();

    if (rigging.isToothed() && teeth > 0 && pitchInches > 0.0) {
      double advanceInches = teeth * pitchInches;
      double kinematicRadiusInches = advanceInches / (2.0 * Math.PI);
      double pitchDiameterInches = pitchInches / Math.sin(Math.PI / teeth);
      double pitchCircumferenceInches = Math.PI * pitchDiameterInches;
      double percentHigh = (pitchCircumferenceInches / advanceInches - 1.0) * 100.0;
      String noun = rigging.form() == Rigging.Form.SPROCKET ? "sprocket" : "pulley";
      String medium = rigging.form() == Rigging.Form.SPROCKET ? "chain" : "belt";

      row(sb, noun, rigging.describe());
      cont(
          sb,
          String.format(
              Locale.ROOT,
              "%-16s = %d x %.3f in = %.4f in per drum rot",
              medium + " advance",
              teeth,
              pitchInches,
              advanceInches));
      cont(
          sb,
          String.format(
              Locale.ROOT,
              "%-16s = %.4f / 2pi = %.5f in = %.7f m",
              "kinematic radius",
              advanceInches,
              kinematicRadiusInches,
              Inches.of(kinematicRadiusInches).in(Meters)));
      cont(
          sb,
          String.format(
              Locale.ROOT,
              "(geometric pitch dia = %.3f / sin(pi/%d) = %.5f in, so the pitch",
              pitchInches,
              teeth,
              pitchDiameterInches));
      cont(
          sb,
          String.format(
              Locale.ROOT,
              " circumference is %.5f in -- %.2f%% larger. Chordal action: the %s",
              pitchCircumferenceInches,
              percentHigh,
              medium));
      cont(sb, " rides a polygon. PumpkinLib uses the " + medium.toUpperCase(Locale.ROOT)
          + " ADVANCE.)");
    } else {
      row(
          sb,
          "drum",
          rigging.describe()
              + String.format(
                  Locale.ROOT,
                  ", radius %.7f m (%.5f in)",
                  linear.drumRadius().in(Meters),
                  linear.drumRadius().in(Inches)));
    }

    int stages = linear.stages();
    row(
        sb,
        "rigging",
        stages == 1
            ? "1 stage (direct pull) -> the carriage moves with the drum surface"
            : stages
                + " stages (cascade) -> the carriage moves "
                + stages
                + "x the drum surface");

    double travelMeters = linear.userPerOutputRotation();
    row(
        sb,
        "travel per drum rot",
        String.format(
            Locale.ROOT, "%.6f m  (%.4f in)", travelMeters, Meters.of(travelMeters).in(Inches)));
    row(
        sb,
        "effective radius",
        String.format(
            Locale.ROOT,
            "%.7f m  (kinematic radius x %d stage%s) -- ElevatorSim and",
            linear.effectiveRadius().in(Meters),
            stages,
            stages == 1 ? "" : "s"));
    cont(sb, "the kG derivation both use this, NOT the bare drum radius");
    double perRotor = userPerRotorRotation();
    row(
        sb,
        "travel per rotor rot",
        String.format(
            Locale.ROOT, "%.7f m  (%.5f in)", perRotor, Meters.of(perRotor).in(Inches)));
  }

  /**
   * Appends the geometry rows for a rotary axis.
   *
   * @param sb the buffer to append to
   * @param rotary the axis
   */
  private void describeRotary(StringBuilder sb, RotaryAxis rotary) {
    row(
        sb,
        "joint",
        String.format(
            Locale.ROOT,
            "rotary -- 1 output rotation = %.3f deg = %.6f rad",
            rotary.userPerOutputRotation(),
            rotary.siPerOutputRotation()));
    double perRotor = userPerRotorRotation();
    row(
        sb,
        "travel per rotor rot",
        String.format(
            Locale.ROOT, "%.5f deg  (%.7f rad)", perRotor, Math.toRadians(perRotor)));
    if (rotary.gravity() == org.pumpkinlib.control.GravityMode.COSINE) {
      row(
          sb,
          "horizontal at",
          String.format(
              Locale.ROOT,
              "%.3f deg (%.6f rad) -- the cosine reference. Phoenix",
              rotary.horizontalReference(),
              horizontalReferenceSi()));
      cont(sb, "GravityArmPositionOffset is this value NEGATED");
    }
    row(
        sb,
        "continuous wrap",
        rotary.isContinuous()
            ? "YES -- the loop may take the short way around 0/360"
            : "no -- finite travel; the loop may NOT take the short way around");
  }

  /**
   * The gravity row's text, naming the vendor knob each mode maps onto.
   *
   * @return the description
   */
  private String describeGravity() {
    switch (m_axis.gravity()) {
      case CONSTANT:
        return "CONSTANT (kG applied always; Phoenix Elevator_Static, REV kG, "
            + "RIO ElevatorFeedforward)";
      case COSINE:
        return "COSINE (kG * cos(position - horizontal); Phoenix Arm_Cosine, REV kCos, "
            + "RIO ArmFeedforward)";
      case NONE:
      default:
        return "NONE (no gravity term; kG must be 0; RIO SimpleMotorFeedforward)";
    }
  }

  /**
   * Appends one label/value row in the aligned two-column layout.
   *
   * @param sb the buffer to append to
   * @param label the left column
   * @param value the right column
   */
  private static void row(StringBuilder sb, String label, String value) {
    sb.append("  ")
        .append(String.format(Locale.ROOT, "%-" + kLabelWidth + "s", label))
        .append(value)
        .append('\n');
  }

  /**
   * Appends a continuation line under the previous row's value column.
   *
   * @param sb the buffer to append to
   * @param value the text
   */
  private static void cont(StringBuilder sb, String value) {
    sb.append(kContinuation).append(value).append('\n');
  }

  /**
   * Guards a typed conversion against being used on the wrong kind of axis.
   *
   * @param required the domain the caller's {@code Measure} type implies
   * @param what the measure type named in the message, e.g. {@code "a Distance"}
   * @throws IllegalArgumentException if this mechanism is not in that domain
   */
  private void requireDomain(SiDomain required, String what) {
    if (m_axis.siDomain() != required) {
      throw new IllegalArgumentException(
          "This mechanism is a "
              + (m_axis instanceof RotaryAxis ? "ROTARY" : "LINEAR")
              + " axis, so its positions are measured in "
              + m_axis.unitLabel()
              + " and "
              + what
              + " is not a position on it. "
              + "Fix: use "
              + (required == SiDomain.LINEAR_METERS
                  ? "the Angle overload (e.g. Degrees.of(35))"
                  : "the Distance overload (e.g. Inches.of(20.5))")
              + ", or declare the axis you actually meant — "
              + "LinearAxis for something that travels in a line, RotaryAxis for a joint.");
    }
  }
}

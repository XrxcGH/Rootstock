package org.rootstock.sim;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.system.plant.LinearSystemId;
import edu.wpi.first.wpilibj.simulation.DCMotorSim;
import edu.wpi.first.wpilibj.simulation.ElevatorSim;
import edu.wpi.first.wpilibj.simulation.FlywheelSim;
import edu.wpi.first.wpilibj.simulation.SingleJointedArmSim;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.MechanismGeometry;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.units.MechanismUnits;

/**
 * The one place a {@link MechanismGeometry} becomes a WPILib plant.
 *
 * <p>Package-private on purpose. {@link MechanismSim} is the type callers hold and {@link #raw()} is
 * the escape hatch to the WPILib model; a public class here would be a second, subclassable way to
 * express the same thing and ArchUnit rule 7 exists to stop that happening by accident.
 *
 * <h2>There is no second conversion</h2>
 *
 * <p>Every number handed to a WPILib constructor comes from the geometry the mechanism already
 * declared, which {@code MechanismUnits} already computed:
 *
 * <ul>
 *   <li><b>gearing</b> is {@code rotorPerOutput()} — rotor rotations per output rotation, the same
 *       number the device carries in {@code SensorToMechanismRatio}.
 *   <li><b>drum radius</b> is {@code siPerOutputRotation() / 2&pi;}, <i>derived</i> rather than copied
 *       from {@code effectiveRadiusMeters()}. WPILib's {@code ElevatorSim} converts drum rotations to
 *       metres by multiplying by {@code 2&pi;r}, so deriving {@code r} from the travel-per-rotation the
 *       {@code MotorIO} seam uses makes the plant travel exactly as far per rotation as the real
 *       mechanism does. The declared radius is then compared against it and a disagreement is
 *       reported by name — that comparison is how a wrong tooth count or a forgotten cascade stage
 *       becomes visible in {@code simulateJava} instead of at an event.
 *   <li><b>travel limits</b> are {@code siMin()} / {@code siMax()}, the mechanism's own soft limits.
 *   <li><b>rotor writeback</b> goes through {@link MechanismUnits#toRotorRotations(double)} when the
 *       units object is available, and through the identical geometry-derived expression when it is
 *       not. Both spellings are the same two multiplies; the units object is preferred because it is
 *       the object the rest of the library agrees with by construction.
 * </ul>
 */
final class WpilibPlantSim implements MechanismSim {

  /** Two pi, spelled once. */
  private static final double kTwoPi = 2.0 * Math.PI;

  /**
   * How far the declared effective radius may differ from the derived one before it is reported, as a
   * fraction.
   *
   * <p>One percent: tight enough to catch a missing cascade stage (a factor of two) or a chordal-action
   * mix-up (about five percent on a 22-tooth sprocket), loose enough not to fire on the last bit of a
   * double.
   */
  private static final double kRadiusTolerance = 0.01;

  /**
   * The time the fallback plant takes to reach free speed under stall torque, in seconds.
   *
   * <p>Deliberately the same number and the same derivation as
   * {@code org.rootstock.hardware.sim.SimMotorIO.kDefaultSpinUpSeconds}, so a mechanism with no
   * declared inertia behaves the same whichever backend is under it.
   */
  static final double kDefaultSpinUpSeconds = 0.35;

  private final MechanismGeometry m_geometry;
  private final MechanismUnits m_units;
  private final DCMotor m_gearbox;
  private final double m_rotorPerOutput;
  private final double m_siPerOutputRotation;
  private final double m_inertiaKgM2;
  private final double m_derivedRadiusMeters;
  private final List<String> m_problems;

  private final ElevatorSim m_elevator;
  private final SingleJointedArmSim m_arm;
  private final FlywheelSim m_flywheel;
  private final DCMotorSim m_motor;

  private SimMotorHandle m_handle;
  private double m_busVolts = RootstockSim.kNominalBusVolts;
  private double m_appliedVolts;
  private double m_flywheelPositionRad;

  /**
   * Builds the plant the declared geometry describes.
   *
   * @param geometry the declared plant; must not be null
   * @param units the mechanism's converter, or null when only the geometry is available
   * @param handle the device to drive, or null to attach one later with {@link #attach}
   * @param measurementStdDev the noise to add to the plant's outputs, in SI; zero for none
   */
  WpilibPlantSim(
      MechanismGeometry geometry,
      MechanismUnits units,
      SimMotorHandle handle,
      double measurementStdDev) {
    m_geometry = Objects.requireNonNull(geometry, "WpilibPlantSim: geometry must not be null");
    m_units = units;
    m_handle = handle;

    List<String> problems = new ArrayList<>();
    m_gearbox = geometry.gearbox() != null ? geometry.gearbox() : DCMotor.getNEO(1);
    if (geometry.gearbox() == null) {
      problems.add(
          "no gearbox was declared, so the plant is standing in a single NEO. Fix: give "
              + "MechanismGeometry a DCMotor built from the motor and the motor count, e.g. "
              + "DCMotor.getKrakenX60Foc(2).");
    }

    m_rotorPerOutput = positiveOr(geometry.rotorPerOutput(), 1.0, "rotorPerOutput", problems);
    m_siPerOutputRotation =
        positiveOr(geometry.siPerOutputRotation(), kTwoPi, "siPerOutputRotation", problems);
    m_derivedRadiusMeters = m_siPerOutputRotation / kTwoPi;
    m_inertiaKgM2 = inertiaFor(geometry, m_gearbox, m_rotorPerOutput);

    checkUnitsAgree(units, problems);
    if (geometry.kind() == MechanismGeometry.Kind.LINEAR) {
      checkRadiusAgrees(geometry, problems);
    }

    double lower = Double.isFinite(geometry.siMin()) ? geometry.siMin() : -1.0e6;
    double upper = Double.isFinite(geometry.siMax()) ? geometry.siMax() : 1.0e6;
    if (upper <= lower) {
      problems.add(
          String.format(
              Locale.ROOT,
              "travel limits are [%.4f, %.4f] %s, which is empty. Fix: declare PositionLimits whose "
                  + "maximum is above its minimum; a plant clamped to a single point cannot move.",
              lower,
              upper,
              siLabel()));
      upper = lower + 1.0;
    }
    double start = Double.isFinite(geometry.siStart()) ? geometry.siStart() : lower;
    start = MathUtil.clamp(start, lower, upper);

    double std = Double.isFinite(measurementStdDev) && measurementStdDev > 0.0 ? measurementStdDev : 0.0;
    double[] twoOutputs = std > 0.0 ? new double[] {std, std} : new double[0];
    double[] oneOutput = std > 0.0 ? new double[] {std} : new double[0];

    ElevatorSim elevator = null;
    SingleJointedArmSim arm = null;
    FlywheelSim flywheel = null;
    DCMotorSim motor = null;
    switch (geometry.kind()) {
      case LINEAR ->
          elevator =
              new ElevatorSim(
                  m_gearbox,
                  m_rotorPerOutput,
                  massOr(geometry, problems),
                  m_derivedRadiusMeters,
                  lower,
                  upper,
                  geometry.simulateGravity(),
                  start,
                  twoOutputs);
      case ROTARY ->
          arm =
              new SingleJointedArmSim(
                  m_gearbox,
                  m_rotorPerOutput,
                  m_inertiaKgM2,
                  armLengthOr(geometry, problems),
                  lower,
                  upper,
                  geometry.simulateGravity(),
                  start,
                  twoOutputs);
      case FLYWHEEL ->
          flywheel =
              new FlywheelSim(
                  LinearSystemId.createFlywheelSystem(m_gearbox, m_inertiaKgM2, m_rotorPerOutput),
                  m_gearbox,
                  oneOutput);
      case SIMPLE ->
          motor =
              new DCMotorSim(
                  LinearSystemId.createDCMotorSystem(m_gearbox, m_inertiaKgM2, m_rotorPerOutput),
                  m_gearbox,
                  twoOutputs);
      default -> throw new IllegalStateException("unreachable: " + geometry.kind());
    }
    m_elevator = elevator;
    m_arm = arm;
    m_flywheel = flywheel;
    m_motor = motor;

    if (motor != null && Double.isFinite(start)) {
      motor.setState(start, 0.0);
    }
    m_problems = List.copyOf(problems);
  }

  // ---- MechanismSim ------------------------------------------------------------------------------

  @Override
  public String name() {
    return m_geometry.name();
  }

  @Override
  public void update(double dtSeconds) {
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : Clock.dt();
    double bus = Double.isFinite(m_busVolts) && m_busVolts > 0.0 ? m_busVolts : RootstockSim.kNominalBusVolts;

    // Reading the device FIRST is what makes this simulation worth running: the volts come out of the
    // controller's own closed loop, its own motion profile and its own gravity feedforward, so those
    // three are under test rather than reimplemented here.
    double commanded = m_handle == null ? 0.0 : m_handle.appliedVolts(bus);
    m_appliedVolts = MathUtil.clamp(Double.isFinite(commanded) ? commanded : 0.0, -bus, bus);

    // setInput, NOT setInputVoltage. Every WPILib plant's setInputVoltage is
    //     setInput(volts); clampInput(RobotController.getBatteryVoltage());
    // which has two consequences Rootstock does not want. First, it reads the HAL, so a plant
    // stepped through it cannot run on a machine with no WPILib natives — which is CI, and which is
    // the property RootstockSim.stepPlants() exists to provide. Second, it clamps to whatever was last
    // pushed into RoboRioSim rather than to the bus voltage RootstockSim just computed, so a team
    // driving the plants without RoboRioSim would silently get a fictional 12 V. The clamp above
    // already applied the correct bus, so setInput is both HAL-free and the more accurate of the two.
    if (m_elevator != null) {
      m_elevator.setInput(m_appliedVolts);
      m_elevator.update(dt);
    } else if (m_arm != null) {
      m_arm.setInput(m_appliedVolts);
      m_arm.update(dt);
    } else if (m_flywheel != null) {
      m_flywheel.setInput(m_appliedVolts);
      m_flywheel.update(dt);
      // FlywheelSim has one state and it is velocity, so position has to be integrated here. It is
      // still published, because a roller's revolution count is how an indexer counts game pieces.
      m_flywheelPositionRad += m_flywheel.getAngularVelocityRadPerSec() * dt;
    } else if (m_motor != null) {
      m_motor.setInput(m_appliedVolts);
      m_motor.update(dt);
    }

    writeRotorState();
  }

  @Override
  public double positionSi() {
    if (m_elevator != null) {
      return m_elevator.getPositionMeters();
    }
    if (m_arm != null) {
      return m_arm.getAngleRads();
    }
    if (m_flywheel != null) {
      return m_flywheelPositionRad;
    }
    return m_motor.getAngularPositionRad();
  }

  @Override
  public double velocitySi() {
    if (m_elevator != null) {
      return m_elevator.getVelocityMetersPerSecond();
    }
    if (m_arm != null) {
      return m_arm.getVelocityRadPerSec();
    }
    if (m_flywheel != null) {
      return m_flywheel.getAngularVelocityRadPerSec();
    }
    return m_motor.getAngularVelocityRadPerSec();
  }

  @Override
  public double currentDrawAmps() {
    if (m_elevator != null) {
      return m_elevator.getCurrentDrawAmps();
    }
    if (m_arm != null) {
      return m_arm.getCurrentDrawAmps();
    }
    if (m_flywheel != null) {
      return m_flywheel.getCurrentDrawAmps();
    }
    return m_motor.getCurrentDrawAmps();
  }

  @Override
  public double appliedVolts() {
    return m_appliedVolts;
  }

  @Override
  public Object raw() {
    if (m_elevator != null) {
      return m_elevator;
    }
    if (m_arm != null) {
      return m_arm;
    }
    if (m_flywheel != null) {
      return m_flywheel;
    }
    return m_motor;
  }

  @Override
  public void setBusVolts(double volts) {
    m_busVolts = volts;
  }

  @Override
  public double stallCurrentAmps() {
    // DCMotor's constructor multiplies stallCurrentAmps by the motor count, so this is already the
    // whole gearbox and not one motor. That is exactly the number a team needs to see at boot.
    return m_gearbox.stallCurrentAmps;
  }

  @Override
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(m_geometry.describe()).append('\n');
    sb.append(
        String.format(
            Locale.ROOT,
            "  plant %s, inertia %.6f kg m^2, %.6f %s per output rotation "
                + "(radius %.7f m derived from it), stall %.0f A",
            raw().getClass().getSimpleName(),
            m_inertiaKgM2,
            m_siPerOutputRotation,
            siLabel(),
            m_derivedRadiusMeters,
            stallCurrentAmps()));
    sb.append('\n').append("  device ").append(m_handle == null ? "NOT ATTACHED" : "attached");
    for (String problem : m_problems) {
      sb.append('\n').append("  PROBLEM ").append(problem);
    }
    return sb.toString();
  }

  // ---- package API -------------------------------------------------------------------------------

  /**
   * Wires the device this plant drives.
   *
   * @param handle the vendor sim state, or null to detach
   */
  void attach(SimMotorHandle handle) {
    m_handle = handle;
  }

  /**
   * Whether a device is wired.
   *
   * @return true when a handle is attached
   */
  boolean isAttached() {
    return m_handle != null;
  }

  /**
   * The declared geometry, so the registry can report on it without a second copy.
   *
   * @return the geometry
   */
  MechanismGeometry geometry() {
    return m_geometry;
  }

  /**
   * Everything structurally wrong with the declaration, in plain sentences.
   *
   * @return an unmodifiable list; empty when the plant is fully specified
   */
  List<String> problems() {
    return m_problems;
  }

  // ---- internals ---------------------------------------------------------------------------------

  /**
   * Writes the plant's state back to the device as ROTOR state.
   *
   * <p>Rotor, not output: the device divides by its own configured ratio to report output units, and
   * that division is the single most commonly misconfigured number on a robot. Writing output units
   * here would skip it, and the simulation would agree with itself while disagreeing with the robot.
   */
  private void writeRotorState() {
    if (m_handle == null) {
      return;
    }
    double si = positionSi();
    double siPerSec = velocitySi();
    double rotorRot;
    double rotorRps;
    if (m_units != null) {
      // The M2 converter, not a parallel expression. fromSi() is the identity on a linear axis and
      // Math.toDegrees on a rotary one; toRotorRotations() then applies the geometry and the gearbox
      // once each. Both are linear, so the same call converts a rate.
      rotorRot = m_units.toRotorRotations(m_units.fromSi(si));
      rotorRps = m_units.toRotorRotations(m_units.fromSiPerSec(siPerSec));
    } else {
      rotorRot = si / m_siPerOutputRotation * m_rotorPerOutput;
      rotorRps = siPerSec / m_siPerOutputRotation * m_rotorPerOutput;
    }
    m_handle.setRotorPosition(rotorRot, rotorRps);
  }

  /** {@code "m"} for a linear plant, {@code "rad"} for anything rotating. */
  private String siLabel() {
    return m_geometry.kind() == MechanismGeometry.Kind.LINEAR ? "m" : "rad";
  }

  /**
   * Reports a units object that disagrees with the geometry it was supposed to have produced.
   *
   * <p>They are computed from the same {@code Reduction} and {@code Axis}, so a disagreement means two
   * different declarations reached the same mechanism — which is a bug that otherwise shows up as a
   * simulation that is subtly the wrong speed and nothing else.
   */
  private void checkUnitsAgree(MechanismUnits units, List<String> problems) {
    if (units == null) {
      return;
    }
    if (!approxEquals(units.rotorPerOutput(), m_rotorPerOutput)) {
      problems.add(
          String.format(
              Locale.ROOT,
              "the MechanismUnits gear ratio is %.6f rotor rotations per output rotation but the "
                  + "declared geometry says %.6f. They are derived from the same Reduction, so two "
                  + "different declarations have reached this mechanism. Fix: build the geometry from "
                  + "the same MechanismUnits the MotorIO was built from.",
              units.rotorPerOutput(),
              m_rotorPerOutput));
    }
    if (!approxEquals(units.siPerOutputRotation(), m_siPerOutputRotation)) {
      problems.add(
          String.format(
              Locale.ROOT,
              "the MechanismUnits travel is %.7f %s per output rotation but the declared geometry "
                  + "says %.7f. Fix: build the geometry from the same Axis the MechanismUnits was "
                  + "built from.",
              units.siPerOutputRotation(),
              siLabel(),
              m_siPerOutputRotation));
    }
  }

  /**
   * Reports a declared effective radius that does not match the declared travel per rotation.
   *
   * <p>{@code effectiveRadiusMeters} and {@code siPerOutputRotation} are two spellings of the same
   * fact — {@code travel = 2&pi;r} — and the kG derivation reads one while the plant reads the other.
   * If they disagree, one of them is wrong and a simulated elevator holds at a height a real one does
   * not.
   */
  private void checkRadiusAgrees(MechanismGeometry geometry, List<String> problems) {
    double declared = geometry.effectiveRadiusMeters();
    if (!Double.isFinite(declared) || declared <= 0.0) {
      return;
    }
    double error = Math.abs(declared - m_derivedRadiusMeters) / m_derivedRadiusMeters;
    if (error > kRadiusTolerance) {
      problems.add(
          String.format(
              Locale.ROOT,
              "the declared effective radius is %.7f m but %.6f m of travel per output rotation "
                  + "implies %.7f m (travel = 2*pi*r), a %.1f%% disagreement. One of the two is wrong "
                  + "and the plant is built from the travel. Fix: check the cascade stage count and "
                  + "the tooth count on the Axis. A missing stage is exactly a factor of two here.",
              declared,
              m_siPerOutputRotation,
              m_derivedRadiusMeters,
              error * 100.0));
    }
  }

  /** The carriage mass, or a reported stand-in. */
  private static double massOr(MechanismGeometry geometry, List<String> problems) {
    double mass = geometry.massKg();
    if (Double.isFinite(mass) && mass > 0.0) {
      return mass;
    }
    problems.add(
        "no carriage mass was declared, so the plant is standing in 1 kg and will accelerate far "
            + "faster than the real mechanism. Fix: SimConfig.linear(Kilograms.of(...), start). "
            + "Weighing the carriage to within 30% is plenty.");
    return 1.0;
  }

  /** The arm length, or a reported stand-in. */
  private static double armLengthOr(MechanismGeometry geometry, List<String> problems) {
    double length = geometry.armLengthMeters();
    if (Double.isFinite(length) && length > 0.0) {
      return length;
    }
    problems.add(
        "no arm length was declared, so gravity torque is computed against a 0.5 m stand-in. Fix: "
            + "SimConfig.arm(length, mass, start), or SimConfig.rotary(moi, centreOfMass, start) if "
            + "the centre of mass is known.");
    return 0.5;
  }

  /**
   * The moment of inertia the plant integrates, referred to the OUTPUT shaft.
   *
   * <p>Deliberately the same derivation, in the same order, as
   * {@code org.rootstock.hardware.sim.SimMotorIO.inertiaFor}: a mechanism must not change its
   * simulated dynamics depending on which backend happens to be under it.
   */
  private static double inertiaFor(
      MechanismGeometry geometry, DCMotor gearbox, double rotorPerOutput) {
    double declared =
        switch (geometry.kind()) {
          case LINEAR -> geometry.massKg() * square(geometry.effectiveRadiusMeters());
          case ROTARY ->
              Double.isFinite(geometry.momentOfInertiaKgM2())
                  ? geometry.momentOfInertiaKgM2()
                  : geometry.massKg() * square(geometry.armLengthMeters());
          case FLYWHEEL, SIMPLE -> geometry.momentOfInertiaKgM2();
        };
    if (Double.isFinite(declared) && declared > 0.0) {
      return declared;
    }
    // Nothing declared: pick the inertia that makes stall torque reach free speed in
    // kDefaultSpinUpSeconds. Both quantities are referred to the OUTPUT shaft, so the reduction
    // appears twice and cancels once -- which is exactly the arithmetic a hand-written sim gets wrong.
    double stallTorqueAtOutput = gearbox.stallTorqueNewtonMeters * rotorPerOutput;
    double freeSpeedAtOutput = gearbox.freeSpeedRadPerSec / rotorPerOutput;
    double j = stallTorqueAtOutput * kDefaultSpinUpSeconds / freeSpeedAtOutput;
    return Double.isFinite(j) && j > 0.0 ? j : 0.001;
  }

  /** Replaces a non-finite or non-positive scale factor with a usable one, and says so. */
  private static double positiveOr(
      double value, double fallback, String what, List<String> problems) {
    if (Double.isFinite(value) && value > 0.0) {
      return value;
    }
    problems.add(
        what
            + " was "
            + value
            + ", which makes every conversion in this plant meaningless. Standing in "
            + fallback
            + ". Fix: check the Reduction and the Axis. Both must be strictly positive.");
    return fallback;
  }

  private static boolean approxEquals(double a, double b) {
    return Math.abs(a - b) <= 1.0e-9 * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
  }

  private static double square(double v) {
    return v * v;
  }
}

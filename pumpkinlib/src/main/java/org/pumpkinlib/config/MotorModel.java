package org.pumpkinlib.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.RadiansPerSecond;

import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.Current;

/**
 * Which physical motor is bolted to the gearbox.
 *
 * <p>This is the one place PumpkinLib knows a motor's physics, and it is <b>data, not a handle</b> —
 * naming a Kraken here does not import a vendor library, does not open a CAN device and does not
 * decide which backend runs the loop. That separation is what lets a config compile on a laptop with
 * no vendordeps installed.
 *
 * <p>Three things are derived from this one field, and a team writes none of them:
 *
 * <ol>
 *   <li>the {@link DCMotor} that every simulation plant is built from,
 *   <li>the free speed the "you asked for a cruise velocity above what this mechanism can reach"
 *       check compares against,
 *   <li>the default {@link CurrentLimits}, so a first config can omit current limits entirely and
 *       still not cook a NEO 550.
 * </ol>
 *
 * <p><b>FOC is a property of the request, not of the motor</b> (see {@link OutputMode}), so it is a
 * parameter of {@link #dcMotor(int, boolean)} rather than a separate enum constant. The FOC curve
 * has a lower free speed and a higher stall torque than the trapezoidal one; using the wrong one
 * shifts a derived kV by about 3% on a Kraken, which is inside the noise of a real kV measurement
 * but shows up in a printed derivation, so it is worth getting right.
 */
public enum MotorModel {

  /** CTRE Kraken X60. The default assumption for a Phoenix 6 mechanism. */
  KRAKEN_X60("Kraken X60", true, true, 80.0, 40.0),

  /** CTRE Kraken X44 — the short Kraken. Faster, less torque, same electronics. */
  KRAKEN_X44("Kraken X44", true, true, 60.0, 40.0),

  /** CTRE Minion, the brushless motor a TalonFXS commutates over JST. */
  MINION("Minion", true, false, 60.0, 40.0),

  /** CTRE/VEX Falcon 500. Still on a great many robots. */
  FALCON_500("Falcon 500", true, true, 60.0, 40.0),

  /** REV NEO (V1.1). Brushless, on a SPARK MAX. */
  NEO("NEO", true, false, 40.0, 40.0),

  /**
   * REV NEO 550. Small, fast and <b>easy to destroy</b>: it has very little thermal mass, so the
   * default stator limit here is deliberately 20 A rather than the 40 A a NEO gets.
   */
  NEO_550("NEO 550", true, false, 20.0, 20.0),

  /** REV NEO Vortex, on a SPARK FLEX. */
  NEO_VORTEX("NEO Vortex", true, false, 60.0, 60.0),

  /** CIM. Brushed, and still the right answer for a low-duty-cycle roller. */
  CIM("CIM", false, false, 40.0, 40.0),

  /** MiniCIM. Brushed. */
  MINI_CIM("MiniCIM", false, false, 30.0, 30.0),

  /** BAG motor. Brushed. */
  BAG("BAG", false, false, 25.0, 25.0),

  /** VEX 775pro. Brushed, fast, and thermally fragile under stall. */
  VEX_775PRO("775pro", false, false, 25.0, 25.0),

  /**
   * A brushed motor whose exact model PumpkinLib cannot know.
   *
   * <p>Used when a SPARK or a TalonFXS is configured for brushed commutation without naming the
   * motor. The simulation plant falls back to a CIM curve, and {@link #describe()} says so out loud
   * rather than letting a student believe the sim is accurate.
   */
  BRUSHED_UNKNOWN("unspecified brushed motor", false, false, 30.0, 30.0);

  private final String m_displayName;
  private final boolean m_brushless;
  private final boolean m_hasFocCurve;
  private final double m_defaultStatorAmps;
  private final double m_defaultSupplyAmps;

  MotorModel(
      String displayName,
      boolean brushless,
      boolean hasFocCurve,
      double defaultStatorAmps,
      double defaultSupplyAmps) {
    m_displayName = displayName;
    m_brushless = brushless;
    m_hasFocCurve = hasFocCurve;
    m_defaultStatorAmps = defaultStatorAmps;
    m_defaultSupplyAmps = defaultSupplyAmps;
  }

  /**
   * The motor's name as a human writes it, for error messages and {@code describe()}.
   *
   * @return for example {@code "Kraken X60"}
   */
  public String displayName() {
    return m_displayName;
  }

  /**
   * Whether the motor is brushless.
   *
   * <p>Brushed motors have no rotor position sensor at all, which is why a brushed mechanism cannot
   * use {@link FeedbackSpec.RotorOnly} and must carry an external encoder.
   *
   * @return true if brushless
   */
  public boolean isBrushless() {
    return m_brushless;
  }

  /**
   * Whether WPILib ships a distinct field-oriented-control curve for this motor.
   *
   * <p>Only the CTRE brushless motors have one; asking for FOC on anything else quietly uses the
   * same curve, which is correct, because FOC is a Phoenix Pro feature on Phoenix devices.
   *
   * @return true if {@link #dcMotor(int, boolean)} differs between {@code foc} true and false
   */
  public boolean hasFocCurve() {
    return m_hasFocCurve;
  }

  /**
   * The WPILib motor curve for {@code count} of these motors on one gearbox, without FOC.
   *
   * @param count how many motors share the output shaft; values below 1 are treated as 1
   * @return the combined {@link DCMotor}
   */
  public DCMotor dcMotor(int count) {
    return dcMotor(count, false);
  }

  /**
   * The WPILib motor curve for {@code count} of these motors on one gearbox.
   *
   * <p>This is the single object every simulation plant in the library is built from, so a wrong
   * answer here is a simulation that disagrees with the robot in a way nobody can localise. It is
   * derived from the motor model and the motor count and nothing else — never typed by a team.
   *
   * @param count how many motors share the output shaft; values below 1 are treated as 1
   * @param foc whether the device is commutating with field-oriented control
   * @return the combined {@link DCMotor}
   */
  public DCMotor dcMotor(int count, boolean foc) {
    int n = Math.max(1, count);
    switch (this) {
      case KRAKEN_X60:
        return foc ? DCMotor.getKrakenX60Foc(n) : DCMotor.getKrakenX60(n);
      case KRAKEN_X44:
        return foc ? DCMotor.getKrakenX44Foc(n) : DCMotor.getKrakenX44(n);
      case MINION:
        return DCMotor.getMinion(n);
      case FALCON_500:
        return foc ? DCMotor.getFalcon500Foc(n) : DCMotor.getFalcon500(n);
      case NEO:
        return DCMotor.getNEO(n);
      case NEO_550:
        return DCMotor.getNeo550(n);
      case NEO_VORTEX:
        return DCMotor.getNeoVortex(n);
      case MINI_CIM:
        return DCMotor.getMiniCIM(n);
      case BAG:
        return DCMotor.getBag(n);
      case VEX_775PRO:
        return DCMotor.getVex775Pro(n);
      case CIM:
      case BRUSHED_UNKNOWN:
      default:
        return DCMotor.getCIM(n);
    }
  }

  /**
   * Free speed of one motor's rotor, unloaded, at nominal bus voltage.
   *
   * <p>This is the numerator of the free-speed sanity check: divide by the reduction, multiply by
   * the travel per output rotation, and you have the fastest the mechanism can physically go. A
   * cruise velocity above that is a config that will never reach its own setpoint.
   *
   * @param foc whether the device is commutating with field-oriented control
   * @return the free speed
   */
  public AngularVelocity freeSpeed(boolean foc) {
    return RadiansPerSecond.of(dcMotor(1, foc).freeSpeedRadPerSec);
  }

  /**
   * Free speed of one rotor in rotations per second, the unit every reduction in this library works
   * in.
   *
   * @param foc whether the device is commutating with field-oriented control
   * @return rotor rotations per second, unloaded
   */
  public double freeSpeedRotorRps(boolean foc) {
    return dcMotor(1, foc).freeSpeedRadPerSec / (2.0 * Math.PI);
  }

  /**
   * The default <b>stator</b> current limit for this motor.
   *
   * <p>Chosen to be safe on a mechanism nobody has characterised yet, not to be fast. A team that
   * has measured its mechanism should raise it; the point of the default is that omitting current
   * limits entirely never destroys hardware.
   *
   * @return the default stator limit
   */
  public Current defaultStatorLimit() {
    return Amps.of(m_defaultStatorAmps);
  }

  /**
   * The default <b>supply</b> current limit for this motor.
   *
   * <p>For REV hardware, which has a single {@code smartCurrentLimit} rather than the stator/supply
   * pair, this equals the stator default. See {@link CurrentLimits} for why the two are not the same
   * number on Phoenix hardware.
   *
   * @return the default supply limit
   */
  public Current defaultSupplyLimit() {
    return Amps.of(m_defaultSupplyAmps);
  }

  /**
   * One line naming the motor and its derived free speed, for the boot dump.
   *
   * @return a human-readable description
   */
  public String describe() {
    String note = this == BRUSHED_UNKNOWN ? " (simulated with a CIM curve — model unknown)" : "";
    return String.format(
        "%s, %s, free speed %.0f rpm%s",
        m_displayName,
        m_brushless ? "brushless" : "brushed",
        freeSpeedRotorRps(false) * 60.0,
        note);
  }
}

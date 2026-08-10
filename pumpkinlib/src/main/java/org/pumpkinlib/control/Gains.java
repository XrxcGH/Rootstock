package org.pumpkinlib.control;

import java.util.Locale;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.units.SiDomain;

/**
 * The canonical PumpkinLib gain set: <b>seven doubles, every one of them volts per SI unit</b>.
 *
 * <pre>
 *   kS  volts                       static friction
 *   kV  volts / (unit/second)       velocity
 *   kA  volts / (unit/second^2)     acceleration
 *   kG  volts                       gravity (times cos(theta - horizontalRef) for COSINE)
 *   kP  volts / unit                proportional
 *   kI  volts / (unit * second)     integral
 *   kD  volts / (unit/second)       derivative
 * </pre>
 *
 * <p>where "unit" is <b>metres</b> for {@link SiDomain#LINEAR_METERS} and <b>radians</b> for {@link
 * SiDomain#ROTATIONAL_RADIANS}. This matches wpimath exactly, which is the point.
 *
 * <h2>Why volts-per-SI, and why that is load-bearing</h2>
 *
 * <p>One physical mechanism, one physical behaviour, three numbers that differ by four orders of
 * magnitude:
 *
 * <table border="1">
 *   <caption>The same swerve steer motor's starting kP</caption>
 *   <tr><th>Where the loop runs</th><th>kP unit</th><th>Real value</th></tr>
 *   <tr><td>wpimath on the roboRIO</td><td>volts per radian</td><td>~7</td></tr>
 *   <tr><td>Phoenix 6 {@code Slot0Configs} (voltage request)</td><td>output per rotation</td>
 *       <td>~100</td></tr>
 *   <tr><td>REVLib {@code ClosedLoopConfig}</td><td>duty cycle per rotation</td><td>~0.01</td></tr>
 * </table>
 *
 * <p>A 10,000x spread means gains are untransferable between teams, between mechanisms, and even
 * between two motors on the same robot — and it means the WPILib arm tutorial teaches a number a
 * student cannot use. PumpkinLib canonicalises to volts-per-SI and converts <b>exactly once</b>, in
 * a {@link GainSink}. Team code never sees a vendor unit. That single decision is the precondition
 * for the tuning wizard, the persisted tuned-value store, the NetworkTables schema and every
 * teaching claim this library makes; without it none of them are possible.
 *
 * <h2>Named fields only</h2>
 *
 * <p>There is deliberately no public multi-double constructor. {@code
 * reefscape2025/util/custom/GainConstants.java} carries a live positional-overload bug in which
 * {@code (P, I, D, FF, minOut, maxOut)} silently binds to {@code (P, I, D, S, V, G)}, quietly
 * dropping the feedforward and installing an output clamp as a gravity term. Construction here goes
 * {@code Gains.pid(kP, kI, kD).withKv(..).withKs(..)} so that class of bug is unrepresentable.
 *
 * <h2>What is not here, and where it went (D1a, D1b, D2, D2a)</h2>
 *
 * <p>{@link GravityMode} is derived from the {@code Axis} and lives on {@code ControlConfig},
 * because no team should type a gravity mode for an elevator. Motion constraints are {@code
 * ControlConfig.MotionConstraints}, authored in user units and stored in SI. Tolerance is {@code
 * ControlConfig.tolerance}. Integral windup is {@code ControlConfig.integral(kI, iZone,
 * iMaxVolts)} — deliberately awkward, and still the only way to enable kI, because integral windup
 * with no clamp is how arms slam. Keeping this record a flat tuple of seven <em>measurable physical
 * quantities</em> is what lets the tuned-value store, the exporter, the NT schema and the wizard all
 * treat it as one object.
 *
 * @param kP proportional gain, volts per unit of error
 * @param kI integral gain, volts per (unit * second) of accumulated error
 * @param kD derivative gain, volts per (unit/second) of error rate
 * @param kS static-friction feedforward, volts
 * @param kV velocity feedforward, volts per (unit/second)
 * @param kA acceleration feedforward, volts per (unit/second^2)
 * @param kG gravity feedforward, volts
 */
public record Gains(
    double kP, double kI, double kD, double kS, double kV, double kA, double kG) {

  /**
   * Feedback-only gains. The normal starting point: build with the three feedback terms, then add
   * feedforward terms by name.
   *
   * @param kP proportional gain, volts per unit
   * @param kI integral gain, volts per (unit * second)
   * @param kD derivative gain, volts per (unit/second)
   * @return a gain set with all four feedforward terms zero
   */
  public static Gains pid(double kP, double kI, double kD) {
    return new Gains(kP, kI, kD, 0, 0, 0, 0);
  }

  /**
   * Feedforward-only gains, for a velocity mechanism that runs open-loop until it is characterised.
   *
   * @param kS static-friction feedforward, volts
   * @param kV velocity feedforward, volts per (unit/second)
   * @param kA acceleration feedforward, volts per (unit/second^2)
   * @return a gain set with all three feedback terms zero
   */
  public static Gains feedforward(double kS, double kV, double kA) {
    return new Gains(0, 0, 0, kS, kV, kA, 0);
  }

  /**
   * A copy with a new proportional gain.
   *
   * @param v the new kP, volts per unit
   * @return a new record; this one is unchanged
   */
  public Gains withKp(double v) {
    return new Gains(v, kI, kD, kS, kV, kA, kG);
  }

  /**
   * A copy with a new integral gain.
   *
   * <p>Note that a non-zero kI does nothing on its own: {@code ControlConfig.integral(kI, iZone,
   * iMaxVolts)} is the only supported way to <em>enable</em> integral action, because the clamp has
   * to be supplied alongside the gain.
   *
   * @param v the new kI, volts per (unit * second)
   * @return a new record; this one is unchanged
   */
  public Gains withKi(double v) {
    return new Gains(kP, v, kD, kS, kV, kA, kG);
  }

  /**
   * A copy with a new derivative gain.
   *
   * @param v the new kD, volts per (unit/second)
   * @return a new record; this one is unchanged
   */
  public Gains withKd(double v) {
    return new Gains(kP, kI, v, kS, kV, kA, kG);
  }

  /**
   * A copy with a new static-friction feedforward.
   *
   * @param v the new kS, volts
   * @return a new record; this one is unchanged
   */
  public Gains withKs(double v) {
    return new Gains(kP, kI, kD, v, kV, kA, kG);
  }

  /**
   * A copy with a new velocity feedforward.
   *
   * @param v the new kV, volts per (unit/second)
   * @return a new record; this one is unchanged
   */
  public Gains withKv(double v) {
    return new Gains(kP, kI, kD, kS, v, kA, kG);
  }

  /**
   * A copy with a new acceleration feedforward.
   *
   * @param v the new kA, volts per (unit/second^2)
   * @return a new record; this one is unchanged
   */
  public Gains withKa(double v) {
    return new Gains(kP, kI, kD, kS, kV, v, kG);
  }

  /**
   * A copy with a new gravity feedforward.
   *
   * @param v the new kG, volts
   * @return a new record; this one is unchanged
   */
  public Gains withKg(double v) {
    return new Gains(kP, kI, kD, kS, kV, kA, v);
  }

  /**
   * Pick the real-robot gains or the simulation gains, as two readable literals instead of seven
   * ternaries.
   *
   * <p>Uses {@link Platform#isReal()} rather than {@code RobotBase.isReal()} so the choice is
   * observable and overridable in a unit test, and so the year-volatile WPILib call stays inside
   * {@code core.compat}.
   *
   * @param real the gains to use on hardware
   * @param sim the gains to use in simulation
   * @return {@code real} on a roboRIO, {@code sim} otherwise
   */
  public static Gains realOrSim(Gains real, Gains sim) {
    return Platform.isReal() ? real : sim;
  }

  /**
   * The one placeholder value PumpkinLib ships — a real, named value with defined behaviour, not a
   * blank a team is expected to notice and replace.
   *
   * <p>{@code kP} is {@link Double#NaN}. That is a genuine sentinel: {@link #isUntuned()} is exact,
   * placeholder detection fires on it, and it cannot be confused with a deliberate choice. The
   * all-zero {@code Gains.zero()} of earlier revisions is <b>deleted</b> precisely because it looked
   * deliberate and was silently a mechanism that never moved.
   *
   * <p><b>In simulation</b> {@code UNTUNED} resolves at mechanism construction to a physics-derived
   * first guess from {@link PlantPrior} — kV and kA from the motor curve, kG from {@link
   * PlantPrior#gravityVoltsPrior()}, kP from the feedback designer — so the demo moves on the first
   * run, and the boot dump says <i>"these gains were derived from your declared mass, not measured
   * — run the tuning wizard."</i>
   *
   * <p><b>On real hardware</b> a mechanism holding {@code UNTUNED} <b>refuses closed-loop
   * control</b> and holds neutral, with a named alert telling the student to run the wizard. Manual
   * control and homing still work, so the robot is not bricked — it just will not pretend to be
   * tuned. We never ship another team's converged gains as pasteable literals, because a converged
   * gain is a property of <em>their</em> mass, <em>their</em> gearing and <em>their</em> friction.
   */
  public static final Gains UNTUNED = new Gains(Double.NaN, 0, 0, 0, 0, 0, 0);

  /**
   * True when these gains are the {@link #UNTUNED} placeholder and closed-loop control must be
   * refused on hardware.
   *
   * <p>Implemented as a NaN test on kP rather than an identity test on {@link #UNTUNED}, so that a
   * gain set that round-tripped through a file, a NetworkTables topic or {@link #with(GainId,
   * double)} is still recognised.
   *
   * @return true if kP is NaN
   */
  public boolean isUntuned() {
    return Double.isNaN(kP);
  }

  /**
   * Set one gain by identifier. This is the wizard's and the dashboard's write path.
   *
   * <p>Every id published under {@code /Tuning/<Mechanism>/} round-trips through this method and
   * {@link #get(GainId)}. That bijection is what lets the NT schema be generated from {@link
   * GainId#values()} instead of typed, and it is why this method is <b>total</b>: no case throws and
   * no case substitutes a different value. An earlier revision's {@code KI} case had to invent an
   * integrator clamp because {@code iMaxVolts} lived on this record; D1a moved the clamp to {@code
   * ControlConfig}, where the policy it guards lives.
   *
   * @param id which gain to replace
   * @param value the new value, in this gain's volts-per-SI unit
   * @return a new record; this one is unchanged
   */
  public Gains with(GainId id, double value) {
    return switch (id) {
      case KP -> withKp(value);
      case KI -> withKi(value);
      case KD -> withKd(value);
      case KS -> withKs(value);
      case KV -> withKv(value);
      case KA -> withKa(value);
      case KG -> withKg(value);
    };
  }

  /**
   * Read one gain by identifier — the inverse of {@link #with(GainId, double)}.
   *
   * @param id which gain to read
   * @return that gain's value, in its volts-per-SI unit
   */
  public double get(GainId id) {
    return switch (id) {
      case KP -> kP;
      case KI -> kI;
      case KD -> kD;
      case KS -> kS;
      case KV -> kV;
      case KA -> kA;
      case KG -> kG;
    };
  }

  /**
   * All seven gains on one line with their real units, for the boot dump and for alert text.
   *
   * <p>The units are spelled out rather than implied, because the whole reason this record exists is
   * that "kP = 128" is meaningless and "kP = 128.000 V/m" is a physical statement a student can
   * check.
   *
   * @param domain the mechanism's SI domain, which decides whether "unit" reads metres or radians
   * @return e.g. {@code "kP 128.000 V/m, kI 0.000 V/(m*s), ..."}
   */
  public String describe(SiDomain domain) {
    StringBuilder out = new StringBuilder(160);
    GainId[] ids = GainId.values();
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append(
          String.format(
              Locale.ROOT, "%s %.4f %s", ids[i].key(), get(ids[i]), ids[i].unitFor(domain)));
    }
    return out.toString();
  }
}

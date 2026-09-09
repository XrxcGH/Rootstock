package org.rootstock.pure.units;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A gearbox — <b>the</b> gearbox type in Rootstock. Always POSITIVE, always self-describing, and
 * always built from the stages a student can count on the real hardware.
 *
 * <p><b>Why positive-only.</b> Direction is expressed by {@code MotorGroup.leaderInverted()}, never
 * by a negative ratio. That makes {@code TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0}
 * ({@code 8793-2026-Robot/.../Constants.java:45}) unrepresentable, along with the "the gear ratio is
 * negative, so the signs cancel" reasoning it forced — reasoning that is correct exactly until
 * somebody fixes the encoder phase, at which point two wrongs stop making a right and the mechanism
 * drives itself into a hard stop. A ratio is a magnitude. Sign is a wiring fact and lives with the
 * wiring.
 *
 * <p><b>The one number.</b> {@link #rotorPerOutput()} is rotor rotations per one output rotation.
 * A 9:1 gearbox has {@code rotorPerOutput() == 9.0}. Every other accessor is derived from it, and
 * {@link #describe()} prints the stages that produced it so a mismatch against TunerConstants or
 * PathPlanner's {@code settings.json} is a readable line rather than an archaeology exercise.
 *
 * <p>Immutable; every {@code then(...)} returns a new instance.
 *
 * <p><b>Tier 0.</b> Zero {@code edu.wpi.first} imports (ArchUnit rule 8), so this class ports to the
 * {@code org.wpilib.*} namespace by having nothing to port.
 */
public final class Reduction {

  /** A direct drive — one rotor rotation per output rotation. */
  public static final Reduction IDENTITY = new Reduction(1.0, List.of());

  private final double m_rotorPerOutput;
  private final List<String> m_stages;

  private Reduction(double rotorPerOutput, List<String> stages) {
    m_rotorPerOutput = rotorPerOutput;
    m_stages = List.copyOf(stages);
  }

  // ---------------------------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------------------------

  /**
   * A gearbox stated as a single ratio. {@code Reduction.of(9.0)} is a 9:1 gearbox.
   *
   * @param rotorPerOutput rotor rotations per one output rotation; must be finite and strictly
   *     positive
   * @return the reduction
   * @throws IllegalArgumentException if {@code rotorPerOutput} is not finite and strictly positive
   */
  public static Reduction of(double rotorPerOutput) {
    requirePositive(rotorPerOutput, "Reduction.of", "rotorPerOutput");
    return new Reduction(rotorPerOutput, List.of(format(rotorPerOutput)));
  }

  /**
   * A gearbox stated as a chain of numeric stages, multiplied in order.
   *
   * <p>{@code Reduction.ofStages(3.0, 4.0)} is 12.0:1. This matches the habit of writing
   * {@code ELEVATOR_GEAR_RATIO = 3.0 * 4.0} — but machine-checkable, and self-describing in
   * {@link #describe()} rather than in a comment that goes stale.
   *
   * @param stages the per-stage reductions, each finite and strictly positive
   * @return the reduction
   * @throws IllegalArgumentException if no stages are given, or any stage is not finite and
   *     strictly positive
   */
  public static Reduction ofStages(double... stages) {
    if (stages.length == 0) {
      throw new IllegalArgumentException(
          "Reduction.ofStages: no stages given. "
              + "Fix: pass at least one stage, or use Reduction.IDENTITY for a direct drive.");
    }
    double product = 1.0;
    List<String> descriptions = new ArrayList<>(stages.length);
    for (double stage : stages) {
      requirePositive(stage, "Reduction.ofStages", "stage");
      product *= stage;
      descriptions.add(format(stage));
    }
    return new Reduction(product, descriptions);
  }

  /**
   * One gear stage, BY TOOTH COUNT, in the order a student reads them off the gearbox: the big gear
   * (on the slow / OUTPUT side) first, the small gear (on the fast / MOTOR side) second.
   *
   * <pre>{@code rotorPerOutput contribution = drivenTeeth / drivingTeeth}</pre>
   *
   * <p>so {@code ofTeeth(58, 10)} is a 5.8:1 <b>reduction</b>, which is what every call in this
   * library and in {@code DESIGN.md} §10A.1 means by it.
   *
   * <p><b>Parameter names are load-bearing.</b> An earlier design revision declared
   * {@code ofTeeth(int driving, int driven)}; a faithful implementation of that signature computes
   * 10/58 and turns the flagship arm into a 65× speed-up. For a library whose central promise is
   * making gear-ratio errors unrepresentable, a signature whose faithful implementation inverts
   * every ratio in its own documentation is the worst possible spec bug. It is pinned by a test:
   *
   * <pre>{@code
   * assertEquals(5.8,    Reduction.ofTeeth(58, 10).rotorPerOutput(),           1e-9);
   * assertEquals(65.411, Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12)
   *                               .rotorPerOutput(),                          1e-3);
   * }</pre>
   *
   * <p>If you do not trust the order — and the fact that you might not is why it exists — use
   * {@link #ofGears(Teeth, Teeth)}, which names both sides at the call site.
   *
   * @param drivenTeeth teeth on the DRIVEN gear — the one on the OUTPUT side of this stage
   * @param drivingTeeth teeth on the DRIVING gear — the one on the MOTOR side of this stage
   * @return the reduction
   * @throws IllegalArgumentException if either tooth count is not strictly positive
   */
  public static Reduction ofTeeth(int drivenTeeth, int drivingTeeth) {
    requireTeeth(drivenTeeth, drivingTeeth);
    return new Reduction(
        (double) drivenTeeth / drivingTeeth, List.of(drivenTeeth + ":" + drivingTeeth));
  }

  /**
   * The disambiguating overload of {@link #ofTeeth(int, int)}: the same stage, with both sides
   * named at the call site.
   *
   * <pre>{@code Reduction.ofGears(Teeth.of(58), Teeth.of(10))   // output-side, motor-side}</pre>
   *
   * @param outputSide the gear on the OUTPUT (slow) side of this stage — the driven gear
   * @param motorSide the gear on the MOTOR (fast) side of this stage — the driving gear
   * @return the reduction
   */
  public static Reduction ofGears(Teeth outputSide, Teeth motorSide) {
    return ofTeeth(outputSide.count(), motorSide.count());
  }

  // ---------------------------------------------------------------------------------------------
  // Chaining
  // ---------------------------------------------------------------------------------------------

  /**
   * This gearbox with one more tooth-count stage on the end.
   *
   * <p>{@code Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12)} reproduces 9143-2025-A's
   * {@code CORAL_PIVOT_GEAR_RATIO} exactly (65.411:1) and prints its own derivation.
   *
   * @param drivenTeeth teeth on the DRIVEN gear — the one on the OUTPUT side of this stage
   * @param drivingTeeth teeth on the DRIVING gear — the one on the MOTOR side of this stage
   * @return a new reduction; this one is unchanged
   * @throws IllegalArgumentException if either tooth count is not strictly positive
   */
  public Reduction then(int drivenTeeth, int drivingTeeth) {
    requireTeeth(drivenTeeth, drivingTeeth);
    List<String> stages = new ArrayList<>(m_stages);
    stages.add(drivenTeeth + ":" + drivingTeeth);
    return new Reduction(m_rotorPerOutput * drivenTeeth / drivingTeeth, stages);
  }

  /**
   * This gearbox with one more numeric stage on the end.
   *
   * @param stage the additional per-stage reduction; must be finite and strictly positive
   * @return a new reduction; this one is unchanged
   * @throws IllegalArgumentException if {@code stage} is not finite and strictly positive
   */
  public Reduction then(double stage) {
    requirePositive(stage, "Reduction.then", "stage");
    List<String> stages = new ArrayList<>(m_stages);
    stages.add(format(stage));
    return new Reduction(m_rotorPerOutput * stage, stages);
  }

  /**
   * This gearbox with another whole gearbox on the end — a belt reduction after a planetary, say.
   *
   * @param other the reduction to append; its stages are appended to this one's derivation
   * @return a new reduction; neither input is changed
   */
  public Reduction then(Reduction other) {
    List<String> stages = new ArrayList<>(m_stages);
    stages.addAll(other.m_stages);
    return new Reduction(m_rotorPerOutput * other.m_rotorPerOutput, stages);
  }

  // ---------------------------------------------------------------------------------------------
  // Accessors and conversions
  // ---------------------------------------------------------------------------------------------

  /**
   * Rotor rotations per one output rotation. A 9:1 gearbox returns 9.0.
   *
   * @return the ratio, always finite and strictly positive
   */
  public double rotorPerOutput() {
    return m_rotorPerOutput;
  }

  /**
   * Output rotations per one rotor rotation — the reciprocal of {@link #rotorPerOutput()}.
   *
   * @return the ratio, always finite and strictly positive
   */
  public double outputPerRotor() {
    return 1.0 / m_rotorPerOutput;
  }

  /**
   * The same number as {@link #rotorPerOutput()}, under the name people say out loud ("what's the
   * ratio?").
   *
   * @return rotor rotations per output rotation
   */
  public double ratio() {
    return m_rotorPerOutput;
  }

  /**
   * Applies the reduction: converts a MOTOR-side quantity to the OUTPUT side.
   *
   * <p>Works for position, velocity and acceleration alike, because a gearbox is linear in all
   * three. It does <b>not</b> work for torque — that multiplies instead of divides — which is why
   * there is no {@code reduceTorque} here and why torque conversions live with the plant model.
   *
   * @param rotorQuantity a rotor-side rotations, rot/s or rot/s² value
   * @return the same quantity on the output side
   */
  public double reduce(double rotorQuantity) {
    return rotorQuantity / m_rotorPerOutput;
  }

  /**
   * Removes the reduction: converts an OUTPUT-side quantity to the MOTOR side. The inverse of
   * {@link #reduce(double)}.
   *
   * @param outputQuantity an output-side rotations, rot/s or rot/s² value
   * @return the same quantity on the rotor side
   */
  public double unreduce(double outputQuantity) {
    return outputQuantity * m_rotorPerOutput;
  }

  /**
   * The stage descriptions this reduction was built from, in order.
   *
   * <p>Empty for {@link #IDENTITY}. Entries are either {@code "58:10"} (tooth counts) or a formatted
   * number (numeric stages).
   *
   * @return an unmodifiable list of stage descriptions
   */
  public List<String> stages() {
    return Collections.unmodifiableList(m_stages);
  }

  /**
   * Cross-check against an external source of truth — PathPlanner's {@code settings.json}, CTRE's
   * {@code TunerConstants}, a CAD number on a whiteboard.
   *
   * @param other the ratio to compare against, in rotor-per-output
   * @param relativeTolerance the permitted relative difference, e.g. {@code 0.01} for one percent
   * @return true if the two agree to within {@code relativeTolerance}
   */
  public boolean approxEquals(double other, double relativeTolerance) {
    if (!Double.isFinite(other) || other == 0.0) {
      return false;
    }
    return Math.abs(m_rotorPerOutput - other) / Math.abs(other) <= Math.abs(relativeTolerance);
  }

  /**
   * The derivation, printed.
   *
   * <p>{@code "(58:10) x (58:18) x (42:12) = 65.411:1 (rotor per output)"}. This string goes into
   * {@code describe()} output and into the disagreement alert that fires when a declared reduction
   * and a vendor config disagree — which is the whole reason the stages are retained rather than
   * multiplied away at construction.
   *
   * @return the human-readable derivation
   */
  public String describe() {
    String tail = format(m_rotorPerOutput) + ":1 (rotor per output)";
    if (m_stages.isEmpty()) {
      return "direct drive = " + tail;
    }
    if (m_stages.size() == 1) {
      return "(" + m_stages.get(0) + ") = " + tail;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < m_stages.size(); i++) {
      if (i > 0) {
        sb.append(" x ");
      }
      sb.append('(').append(m_stages.get(i)).append(')');
    }
    return sb.append(" = ").append(tail).toString();
  }

  @Override
  public String toString() {
    return "Reduction[" + describe() + "]";
  }

  /**
   * Value equality on the ratio alone.
   *
   * <p>The derivation is deliberately excluded: {@code ofStages(3.0, 4.0)} and {@code of(12.0)} are
   * the same gearbox, and a config comparison that says otherwise would report a false difference in
   * {@code MechanismConfigSnapshot} diffs.
   *
   * @param obj the object to compare against
   * @return true if {@code obj} is a Reduction with the same ratio
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Reduction other)) {
      return false;
    }
    return Double.compare(m_rotorPerOutput, other.m_rotorPerOutput) == 0;
  }

  @Override
  public int hashCode() {
    return Double.hashCode(m_rotorPerOutput);
  }

  // ---------------------------------------------------------------------------------------------
  // Validation. Static factories and constructors are the one place rule 11 permits a throw.
  // ---------------------------------------------------------------------------------------------

  private static void requirePositive(double value, String where, String what) {
    if (!Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(
          where
              + ": "
              + what
              + " was "
              + value
              + "; it must be finite and strictly positive. A Reduction is a MAGNITUDE. "
              + "Fix: if you wrote a negative ratio to flip a mechanism's direction, delete the "
              + "sign and set MotorGroup.leaderInverted() instead — that is the only place "
              + "direction belongs, and it is why the sign-cancellation bug class cannot happen "
              + "here.");
    }
  }

  private static void requireTeeth(int drivenTeeth, int drivingTeeth) {
    if (drivenTeeth <= 0 || drivingTeeth <= 0) {
      throw new IllegalArgumentException(
          "Reduction.ofTeeth("
              + drivenTeeth
              + ", "
              + drivingTeeth
              + "): both tooth counts must be strictly positive. "
              + "The order is (drivenTeeth on the OUTPUT side, drivingTeeth on the MOTOR side), "
              + "so ofTeeth(58, 10) is a 5.8:1 reduction. "
              + "Fix: use Reduction.ofGears(Teeth.of(58), Teeth.of(10)) if you want the sides "
              + "named at the call site.");
    }
  }

  private static String format(double value) {
    // %s on a double would print 12.0 for a 12:1 stage and 65.41111111111111 for the arm; three
    // decimals is what design/01 section 4.2's worked derivation prints, so it is what we print.
    return String.format("%.3f", value);
  }
}

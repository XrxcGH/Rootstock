package org.pumpkinlib.mechanism;

import org.pumpkinlib.core.compat.MathX;

/**
 * The over-360&deg; azimuth problem, owned once: take the shortest path across the &plusmn;180&deg;
 * discontinuity, and if that path would leave the physical travel range, go the other way round
 * instead.
 *
 * <p><b>Why this is library code.</b> A surveyed shooter subsystem contains a correct, subtle,
 * twenty-line version of exactly this — track the last commanded angle, take the shortest delta
 * across the wrap, and if the unwrapped target exceeds a limit, add or subtract a full turn to reach
 * the same physical heading from the other side of a greater-than-one-turn travel range, then clamp.
 * It is universal for any azimuth with more than one turn of cable-wrap, it is what a student writes
 * at 2 am, and it is never revisited afterwards. Every team that needs it writes it once and every
 * team that gets it wrong finds out by unwinding a wire harness 359&deg; the wrong way in a match.
 *
 * <h2>The two failures this prevents</h2>
 *
 * <ol>
 *   <li><b>The long way round.</b> Commanding 179&deg; while sitting at -179&deg; must move
 *       2&deg;, not 358&deg;. Naive clamping of a raw angle does the second.
 *   <li><b>The cable-wrap violation.</b> A turret with 540&deg; of travel commanded to a heading
 *       whose shortest path lands past the hard stop must reach the <i>same physical heading</i>
 *       from the other side — a full turn away — rather than jam against the stop and hold current
 *       there. Only a routine that knows the travel limits can make that choice.
 * </ol>
 *
 * <h2>Field-locked turrets</h2>
 *
 * <p>A turret told to hold a <i>field</i> heading is this problem plus one more term: while the
 * chassis rotates, the joint must counter-rotate just to stand still. {@link
 * #fieldLockedGoalDeg(double, double)} converts the field heading into the joint's own frame and
 * {@link #fieldLockVelocityDegPerSec(double)} is the counter-rotation rate — the velocity term that
 * is the entire reason {@code MotorIO.setPositionGoal} carries a velocity parameter at all. Both are
 * stateless conversions; what is stateful is only the last commanded angle.
 *
 * <h2>Stateless maths, one piece of state, no HAL</h2>
 *
 * <p>Every conversion here is a static function of its arguments, so the whole class is unit-testable
 * on a bare JVM with no HAL, no scheduler and no robot. The stateful form exists because a mechanism
 * genuinely needs to remember where it last commanded — that is what makes "shortest path" mean
 * anything — and one object holding one double is a better home for it than a field on every
 * mechanism that ever rotates.
 *
 * <p>Nothing here throws. Degenerate limits are normalised, {@code NaN} requests are ignored in
 * favour of the last good command, and an impossible range yields a clamp rather than an exception:
 * this code runs inside {@code periodic()}.
 */
public final class ContinuousUnwrap {

  /** One full turn, in the degrees this class speaks throughout. */
  public static final double kFullTurnDeg = 360.0;

  /** Half a turn — the half-open bound of the shortest-path modulus. */
  public static final double kHalfTurnDeg = 180.0;

  private final double m_minDeg;
  private final double m_maxDeg;

  private double m_lastCommandedDeg = Double.NaN;

  /**
   * Creates a tracker for one axis's travel range.
   *
   * <p>Limits are normalised rather than validated: a reversed pair is swapped and a {@code NaN}
   * bound becomes an unbounded one. This constructor is on the path from a {@code public static
   * final} config field, where a throw is an unreadable class-initialisation failure.
   *
   * @param minDeg the reverse travel limit, in the axis's user degrees
   * @param maxDeg the forward travel limit, in the axis's user degrees
   */
  public ContinuousUnwrap(double minDeg, double maxDeg) {
    double lo = Double.isNaN(minDeg) ? Double.NEGATIVE_INFINITY : minDeg;
    double hi = Double.isNaN(maxDeg) ? Double.POSITIVE_INFINITY : maxDeg;
    m_minDeg = Math.min(lo, hi);
    m_maxDeg = Math.max(lo, hi);
  }

  // ===============================================================================================
  // The maths — static, pure, HAL-free
  // ===============================================================================================

  /**
   * The signed shortest angular delta from one heading to another, in degrees.
   *
   * @param fromDeg where the mechanism is, or was last told to be
   * @param toDeg where it is being asked to go
   * @return the delta in {@code [-180, 180)}; zero if either argument is {@code NaN}
   */
  public static double shortestDeltaDeg(double fromDeg, double toDeg) {
    if (Double.isNaN(fromDeg) || Double.isNaN(toDeg)) {
      return 0.0;
    }
    return MathX.inputModulus(toDeg - fromDeg, -kHalfTurnDeg, kHalfTurnDeg);
  }

  /**
   * Resolve a requested heading into a commandable angle: shortest path from the last command,
   * wrapped a full turn to the other side of the travel range if the shortest path leaves it, then
   * clamped.
   *
   * <p>This is the whole algorithm, and it is deliberately a pure function of its four arguments so
   * that a test can assert every branch without a mechanism, a scheduler or a HAL.
   *
   * <p>Behaviour at the edges, stated because these are the cases that decide whether it is right:
   *
   * <ul>
   *   <li>A {@code NaN} request returns {@code lastCommandedDeg} clamped — a bad request must not
   *       move the mechanism.
   *   <li>A {@code NaN} {@code lastCommandedDeg} (nothing commanded yet) treats the request itself
   *       as the reference, so the first command is the request clamped and never a full turn away.
   *   <li>When the shortest path is out of range and the full-turn alternative is <i>also</i> out of
   *       range, the shortest path is clamped. That is a mechanism whose travel genuinely cannot
   *       reach the heading, and clamping at the stop is the honest answer.
   * </ul>
   *
   * @param requestedDeg the heading asked for, in the axis's user degrees; any magnitude
   * @param lastCommandedDeg the angle last commanded, or {@code NaN} if none has been
   * @param minDeg the reverse travel limit
   * @param maxDeg the forward travel limit
   * @return an angle inside {@code [minDeg, maxDeg]} representing the requested heading
   */
  public static double unwrap(
      double requestedDeg, double lastCommandedDeg, double minDeg, double maxDeg) {
    double lo = Double.isNaN(minDeg) ? Double.NEGATIVE_INFINITY : minDeg;
    double hi = Double.isNaN(maxDeg) ? Double.POSITIVE_INFINITY : maxDeg;
    double low = Math.min(lo, hi);
    double high = Math.max(lo, hi);

    if (Double.isNaN(requestedDeg)) {
      return Double.isNaN(lastCommandedDeg) ? low : MathX.clamp(lastCommandedDeg, low, high);
    }
    double reference = Double.isNaN(lastCommandedDeg) ? requestedDeg : lastCommandedDeg;

    double target = reference + shortestDeltaDeg(reference, requestedDeg);

    if (target > high) {
      double alternative = target - kFullTurnDeg;
      if (alternative >= low) {
        target = alternative;
      }
    } else if (target < low) {
      double alternative = target + kFullTurnDeg;
      if (alternative <= high) {
        target = alternative;
      }
    }
    return MathX.clamp(target, low, high);
  }

  /**
   * Whether an axis with this travel range needs unwrapping at all.
   *
   * <p>True only for a genuinely multi-turn axis — more than 360&deg; of travel — which is the
   * condition the design attaches the unwrap to inside {@code setGoal()}. An axis with less than one
   * turn of travel has exactly one commandable angle per physical heading, so there is nothing to
   * choose between and a plain clamp is correct and cheaper.
   *
   * @param minDeg the reverse travel limit
   * @param maxDeg the forward travel limit
   * @return true when {@code maxDeg - minDeg} exceeds one full turn
   */
  public static boolean appliesTo(double minDeg, double maxDeg) {
    if (Double.isNaN(minDeg) || Double.isNaN(maxDeg)) {
      return false;
    }
    return Math.abs(maxDeg - minDeg) > kFullTurnDeg;
  }

  /**
   * Converts a desired <b>field</b> heading into the joint's own frame, which is what a turret's
   * position loop actually closes on.
   *
   * <p>The result is deliberately not wrapped or clamped here: hand it to {@link #unwrap(double)} or
   * {@link #unwrap(double, double, double, double)}, which is the step that knows the travel range.
   *
   * @param desiredFieldHeadingDeg where the turret should point in field coordinates
   * @param chassisHeadingDeg the chassis yaw in the same field coordinates
   * @return the joint angle that points the turret at the field heading
   */
  public static double fieldLockedGoalDeg(double desiredFieldHeadingDeg, double chassisHeadingDeg) {
    if (Double.isNaN(desiredFieldHeadingDeg) || Double.isNaN(chassisHeadingDeg)) {
      return Double.NaN;
    }
    return desiredFieldHeadingDeg - chassisHeadingDeg;
  }

  /**
   * The output-shaft velocity a field-locked joint must run at just to stand still while the chassis
   * rotates underneath it.
   *
   * <p>This is the counter-rotation feedforward, and it is the reason {@code
   * MotorIO.setPositionGoal(outputRot, outputRps, arbFfVolts)} has a velocity parameter. Without it,
   * a turret holding a field heading lags the chassis by however long the position loop takes to
   * notice the error it was never told about.
   *
   * <p>Sign: a chassis yawing counter-clockwise (positive &omega;) requires the joint to run
   * clockwise at the same rate, hence the negation.
   *
   * @param chassisYawVelocityRadPerSec the chassis yaw rate as a gyro reports it, in rad/s
   * @return the joint velocity in degrees per second, zero for a {@code NaN} input
   */
  public static double fieldLockVelocityDegPerSec(double chassisYawVelocityRadPerSec) {
    if (Double.isNaN(chassisYawVelocityRadPerSec)) {
      return 0.0;
    }
    return -Math.toDegrees(chassisYawVelocityRadPerSec);
  }

  // ===============================================================================================
  // The stateful form — one double, remembered
  // ===============================================================================================

  /**
   * Resolve a requested heading against the angle this tracker last commanded, and remember the
   * result as the new reference.
   *
   * @param requestedDeg the heading asked for, in the axis's user degrees
   * @return the commandable angle inside this tracker's travel range
   */
  public double unwrap(double requestedDeg) {
    double resolved = unwrap(requestedDeg, m_lastCommandedDeg, m_minDeg, m_maxDeg);
    m_lastCommandedDeg = resolved;
    return resolved;
  }

  /**
   * Seed the reference angle without commanding anything — after homing, after a seed of the
   * position sensor, or at the first enable.
   *
   * <p>Getting this wrong is how a turret takes the long way round on its very first command: the
   * tracker thinks it is at zero, the mechanism is physically at 170&deg;, and "shortest path" is
   * computed from a lie.
   *
   * @param commandedDeg the angle the mechanism is actually at, in the axis's user degrees
   */
  public void seed(double commandedDeg) {
    m_lastCommandedDeg =
        Double.isNaN(commandedDeg) ? Double.NaN : MathX.clamp(commandedDeg, m_minDeg, m_maxDeg);
  }

  /**
   * Forget the reference angle, so the next {@link #unwrap(double)} treats its request as the
   * reference rather than computing a shortest path from stale state.
   */
  public void reset() {
    m_lastCommandedDeg = Double.NaN;
  }

  /**
   * The angle last returned by {@link #unwrap(double)}, or seeded by {@link #seed(double)}.
   *
   * @return the reference angle, or {@code NaN} if nothing has been commanded or seeded
   */
  public double lastCommandedDeg() {
    return m_lastCommandedDeg;
  }

  /**
   * The reverse travel limit this tracker clamps to.
   *
   * @return the minimum, in degrees
   */
  public double minDeg() {
    return m_minDeg;
  }

  /**
   * The forward travel limit this tracker clamps to.
   *
   * @return the maximum, in degrees
   */
  public double maxDeg() {
    return m_maxDeg;
  }

  /**
   * The total travel range.
   *
   * @return {@code maxDeg - minDeg}, in degrees
   */
  public double travelDeg() {
    return m_maxDeg - m_minDeg;
  }

  /**
   * Whether this axis has more than one turn of travel, and therefore whether the full-turn
   * alternative branch can ever fire.
   *
   * @return true for a multi-turn axis
   */
  public boolean isMultiTurn() {
    return appliesTo(m_minDeg, m_maxDeg);
  }

  /**
   * A one-line account for the boot dump.
   *
   * @return e.g. {@code "unwrap: travel [-270.0, 270.0] deg (540.0 deg, multi-turn)"}
   */
  public String describe() {
    return String.format(
        "unwrap: travel [%.1f, %.1f] deg (%.1f deg, %s)",
        m_minDeg, m_maxDeg, travelDeg(), isMultiTurn() ? "multi-turn" : "single-turn");
  }

  @Override
  public String toString() {
    return describe();
  }
}

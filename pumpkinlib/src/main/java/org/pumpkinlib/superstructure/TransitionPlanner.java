package org.pumpkinlib.superstructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns "I am here, take me there" into an ordered list of waypoints, recomputed from <b>live
 * measured state</b> every time the request changes.
 *
 * <h2>Why measured-state gates, never timeouts</h2>
 *
 * <p>{@code 9143-2025-A-Updated}'s superstructure is built out of
 * {@code Commands.waitUntil(() -> elevator.getCurrentPosition() >= handoff)}, and its ascending
 * escape re-evaluates its ceiling every loop so the elevator target ratchets up as the arm swings —
 * which is what stops the transition stuttering. A time-based version of the same sequence is both
 * slower and unsafe when the mechanism is loaded or cold: the timer does not know that the elevator
 * is 300 ms behind today because the battery is at 11.2 V.
 *
 * <p>So PumpkinLib has <b>no</b> time-based waypoint gate. A waypoint may carry a timeout, and a
 * timeout only raises {@code <name>/transition-timed-out} and holds; it never advances. Advancing on
 * a timer is how an arm arrives somewhere the elevator has not left yet.
 *
 * <h2>Plan from live state, unconditionally</h2>
 *
 * <p>{@link Superstructure#periodic()} replans whenever the request changes, whenever the plan is
 * empty, and whenever {@link Plan#isInvalidatedBy} says the leg in flight is no longer safe from
 * where the robot actually is. That is 9143-A's {@code Commands.defer(() -> planMove(...))} insight
 * made unconditional: a plan computed from where we <i>think</i> we are is a plan that is wrong
 * exactly when it matters, which is after something was moved by hand while disabled.
 *
 * @param <S> the team's state enum
 */
public final class TransitionPlanner<S extends Enum<S> & SuperState> {

  private final SafetyModel m_safety;

  /**
   * A planner with no collision model: every move is a direct hop.
   *
   * <p>The right choice for a superstructure whose axes cannot collide — a shooter with a hood and a
   * feeder, for example. It is not a degraded mode and it raises nothing.
   */
  public TransitionPlanner() {
    this(null);
  }

  /**
   * A planner that respects a collision model.
   *
   * @param safety the model, or null for no collision checking
   */
  public TransitionPlanner(SafetyModel safety) {
    m_safety = safety;
  }

  /**
   * The collision model this planner routes around, if any.
   *
   * @return the model, or empty
   */
  public Optional<SafetyModel> safetyModel() {
    return Optional.ofNullable(m_safety);
  }

  /**
   * Plans a move, from the live measured configuration.
   *
   * <p>With no {@link SafetyModel}, or with a target configuration the requested state does not
   * name, the answer is the one-waypoint direct plan — there is nothing to route around and nothing
   * to route with. With a model, the move is tested with the model's own rule (bounding box, or the
   * diagonal for a synchronized pair) and either passes through, is detoured by an installed
   * {@link SafetyModel.Router}, or is <b>refused</b> with the blocking zone named.
   *
   * @param to the requested state
   * @param a0 the measured value of axis A, in user units; {@code NaN} when there is no axis A
   * @param b0 the measured value of axis B, in user units
   * @param a1 the target value of axis A, in user units; {@code NaN} when the state does not name
   *     one, which is the honest answer for a percent or velocity goal
   * @param b1 the target value of axis B, in user units
   * @return the plan; never null. {@link Plan#isRefused()} is how a refusal is reported, rather than
   *     an exception or a null
   */
  public Plan<S> plan(S to, double a0, double b0, double a1, double b1) {
    if (m_safety == null || anyNaN(a0, b0, a1, b1)) {
      return Plan.direct(to);
    }
    List<double[]> waypoints = m_safety.route(a0, b0, a1, b1);
    if (waypoints.isEmpty()) {
      return Plan.refused(to, m_safety.describeRefusal(a0, b0, a1, b1));
    }
    return new Plan<>(to, waypoints, m_safety, "");
  }

  /**
   * An unconditional direct plan, for a superstructure with no collision model.
   *
   * @param to the requested state
   * @return the plan
   */
  public Plan<S> direct(S to) {
    return Plan.direct(to);
  }

  private static boolean anyNaN(double a, double b, double c, double d) {
    return Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d);
  }

  /**
   * An ordered list of configuration waypoints and a cursor into it.
   *
   * <p>Mutable in exactly one way — {@link #advance()} moves the cursor — because the alternative is
   * allocating a new plan every loop on the 50 Hz path. Everything else about it is fixed at
   * construction.
   *
   * <p>A plan always ends at the requested state. Intermediate waypoints are <i>configurations</i>,
   * not states: a router invents them, and there is no {@code SuperState} constant for "the arm at
   * 95 degrees on the way past the funnel". {@link Superstructure#active()} therefore reports the
   * requested state for the whole of a routed move and {@code Plan[]} in the log carries the
   * waypoint list, which is the pairing that turns "why is the arm moving there first?" from a
   * code-reading exercise into a dashboard glance.
   *
   * @param <S> the team's state enum
   */
  public static final class Plan<S extends Enum<S> & SuperState> {

    private final S m_goalState;
    private final List<double[]> m_waypoints;
    private final String[] m_names;
    private final SafetyModel m_safety;
    private final String m_refusalReason;

    private int m_index;

    private Plan(
        S goalState, List<double[]> waypoints, SafetyModel safety, String refusalReason) {
      m_goalState = goalState;
      m_waypoints = waypoints;
      m_safety = safety;
      m_refusalReason = refusalReason;
      m_names = new String[waypoints.size()];
      String goalName = goalState == null ? "?" : goalState.name();
      for (int i = 0; i < waypoints.size(); i++) {
        double[] waypoint = waypoints.get(i);
        m_names[i] =
            i == waypoints.size() - 1 || waypoint == null
                ? goalName
                : String.format(Locale.ROOT, "via(%.3f, %.3f)", waypoint[0], waypoint[1]);
      }
    }

    /**
     * The one-waypoint plan: straight to the goal, with no configuration constraint.
     *
     * @param <S> the state enum
     * @param to the goal state
     * @return the plan
     */
    static <S extends Enum<S> & SuperState> Plan<S> direct(S to) {
      List<double[]> single = new ArrayList<>(1);
      single.add(null);
      return new Plan<>(to, single, null, "");
    }

    /**
     * The refused plan: no waypoints, one sentence saying why.
     *
     * @param <S> the state enum
     * @param to the goal state that was refused
     * @param reason the refusal, ready to print
     * @return the plan
     */
    static <S extends Enum<S> & SuperState> Plan<S> refused(S to, String reason) {
      return new Plan<>(to, List.of(), null, reason);
    }

    /**
     * The state this plan ends at.
     *
     * @return the goal state
     */
    public S goalState() {
      return m_goalState;
    }

    /**
     * Whether this plan has no waypoints at all — which for a refusal is the point, and for anything
     * else means "not yet planned".
     *
     * @return true when there is nothing to execute
     */
    public boolean isEmpty() {
      return m_waypoints.isEmpty();
    }

    /**
     * Whether the planner refused this move.
     *
     * @return true when no safe route exists
     */
    public boolean isRefused() {
      return !m_refusalReason.isEmpty();
    }

    /**
     * Why the move was refused, ready to print into an alert and into
     * {@code /Pumpkin/Superstructure/Blocked}.
     *
     * @return the reason, or an empty string when the plan was not refused
     */
    public String refusalReason() {
      return m_refusalReason;
    }

    /**
     * How many waypoints this plan has.
     *
     * @return the count
     */
    public int size() {
      return m_waypoints.size();
    }

    /**
     * How far through the plan execution is.
     *
     * @return the zero-based cursor
     */
    public int index() {
      return m_index;
    }

    /**
     * Whether the cursor is on the final waypoint, which is the goal.
     *
     * @return true on the last leg
     */
    public boolean isLast() {
      return m_index >= m_waypoints.size() - 1;
    }

    /**
     * The configuration the current leg is heading to.
     *
     * @return the {@code {axisA, axisB}} pair in user units, or null for a leg with no configuration
     *     constraint
     */
    public double[] currentWaypoint() {
      if (m_waypoints.isEmpty()) {
        return null;
      }
      double[] waypoint = m_waypoints.get(Math.min(m_index, m_waypoints.size() - 1));
      return waypoint == null ? null : waypoint.clone();
    }

    /**
     * Moves the cursor to the next leg.
     *
     * @return true when the cursor moved; false when it was already on the last leg
     */
    public boolean advance() {
      if (isLast()) {
        return false;
      }
      m_index++;
      return true;
    }

    /**
     * The waypoint names, as {@code /Pumpkin/Superstructure/Plan} publishes them.
     *
     * <p>The final entry is the goal state's name; intermediate entries are the configurations a
     * router invented, rendered so a student reading the dashboard can see where the arm is being
     * sent first and why.
     *
     * @return the names; a fresh array, because the caller hands it to a logger that keeps it
     */
    public String[] waypointNames() {
      return m_names.clone();
    }

    /**
     * Whether the leg in flight is no longer safe from where the robot actually is.
     *
     * <p>The exact question, not a proxy for it: re-run the model's own crossing test from the
     * <i>measured</i> configuration to the current leg's target. A plan that was safe when it was
     * computed and is not safe now is a plan computed for a robot that has since been pushed by
     * hand, re-homed onto a wrong value, or knocked by a defender — and continuing to execute it is
     * how the arm ends up inside the funnel.
     *
     * <p>Always false for a plan with no collision model or no configuration constraint: there is no
     * geometry to be wrong about.
     *
     * @param a the measured value of axis A, in user units
     * @param b the measured value of axis B, in user units
     * @return true when the plan should be recomputed
     */
    public boolean isInvalidatedBy(double a, double b) {
      if (m_safety == null || m_waypoints.isEmpty() || Double.isNaN(a) || Double.isNaN(b)) {
        return false;
      }
      double[] target = m_waypoints.get(Math.min(m_index, m_waypoints.size() - 1));
      if (target == null) {
        return false;
      }
      return m_safety.crossing(a, b, target[0], target[1]).isPresent();
    }

    /**
     * Whether the current leg's configuration has been reached, within the given tolerances.
     *
     * <p>A leg with no configuration constraint is satisfied by the axes' own {@code atGoal()},
     * which {@link Superstructure} evaluates; this method answers only the geometric half and
     * returns true for such a leg so the two conditions compose with {@code &&}.
     *
     * @param a the measured value of axis A, in user units
     * @param b the measured value of axis B, in user units
     * @param toleranceA the arrival tolerance on axis A, in user units
     * @param toleranceB the arrival tolerance on axis B
     * @return true when the current leg's configuration has been reached
     */
    public boolean currentWaypointReached(
        double a, double b, double toleranceA, double toleranceB) {
      if (m_waypoints.isEmpty()) {
        return false;
      }
      double[] target = m_waypoints.get(Math.min(m_index, m_waypoints.size() - 1));
      if (target == null) {
        return true;
      }
      return Math.abs(a - target[0]) <= toleranceA && Math.abs(b - target[1]) <= toleranceB;
    }

    /**
     * The plan on one line, for the boot dump and for an alert.
     *
     * @return the description
     */
    public String describe() {
      if (isRefused()) {
        return "REFUSED: " + m_refusalReason;
      }
      if (isEmpty()) {
        return "(no plan)";
      }
      return Arrays.toString(m_names) + " at leg " + (m_index + 1) + " of " + m_waypoints.size();
    }

    @Override
    public String toString() {
      return "Plan" + describe();
    }
  }
}

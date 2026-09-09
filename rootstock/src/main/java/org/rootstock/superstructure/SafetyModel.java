package org.rootstock.superstructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.mechanism.Mechanism;
import org.rootstock.units.Range;

/**
 * Collision avoidance over a 2-axis configuration space (typically elevator height &times; arm
 * angle). Zones are axis-aligned rectangles in <b>user units</b>. The planner routes around them.
 *
 * <p>Replaces the hand-written branch tree — {@code planMove} / {@code escapeCurrentPose} /
 * {@code travelAndFinish} / {@code avoidClimb} / {@code transitionWrist}, roughly 250 lines — that
 * {@code 9143-2025-A-Updated}'s {@code Superstructure.java} carries. Robot-specific geometry,
 * generic machinery: the team declares <i>forbidden regions of the configuration space</i>, not a
 * branch tree a student gets wrong at 1 a.m.
 *
 * <pre>{@code
 * static final SafetyModel SAFETY = SafetyModel.over(ELEVATOR, ARM)
 *     .forbid("arm-through-chassis",
 *             Range.of(Inches.of(0), Inches.of(9)),        // elevator low
 *             Range.of(Degrees.of(-15), Degrees.of(40)),   // arm swung out/down
 *             "the arm hits the chassis crossbar below 9 in")
 *     .forbid("arm-through-funnel",
 *             Range.of(Inches.of(22), Inches.of(34)),
 *             Range.of(Degrees.of(60), Degrees.of(105)),
 *             "the arm hits the coral funnel between 22 and 34 in")
 *     .corridor("travel-tucked", Range.of(Inches.of(0), Inches.of(55)), 95.0)
 *     .build();
 * }</pre>
 *
 * <h2>Why the bounding box, not the segment</h2>
 *
 * <p>Asking whether <i>the straight line from start to goal</i> enters a forbidden zone validates a
 * path the robot never takes. The elevator and the arm run <b>independent</b> motion profiles with
 * different velocities, accelerations and loads; the actual traversal through configuration space is
 * whichever axis finishes first followed by the other — an <b>L-shaped</b> path that can pass
 * straight through a rectangle the diagonal misses. That is precisely the geometry this feature
 * exists to prevent, and the failure mode is a destroyed arm on a robot whose logs say the transition
 * was legal. So the default test is the axis-aligned bounding box of the segment, which is exactly
 * the set of configurations two unsynchronized profiles can reach.
 *
 * <p>{@link Builder#synchronizedAxes(boolean)} — <b>true</b> by default for any pair covered by a
 * model — time-scales the faster axis so both arrive together, which makes the real path the
 * diagonal and lets {@link #crossing} test the segment instead. Setting it false disables the
 * collision guarantee; it is legal, it is logged, and it raises a persistent alert for as long as it
 * is set.
 *
 * <h2>What is here and what is deferred, stated plainly</h2>
 *
 * <p><b>Here:</b> the zones, the conditional zones, the corridors, the exact
 * box-and-segment intersection tests, {@link #isSafe}, {@link #violated}, and the direct-hop case of
 * {@link #route} — everything that answers "is this configuration legal" and "is this move legal",
 * with no HAL and no hardware, unit-testable on a bare JVM.
 *
 * <p><b>Deferred:</b> the corner-escape <i>router</i> — the part that invents intermediate waypoints
 * when the direct hop is blocked. It is held back pending validation against 9143-A's real CorAl
 * geometry, because a router that produces a plausible-looking but wrong waypoint list is worse than
 * no router at all: it converts a refusal a team would have noticed in the shop into a move that
 * looks fine until it does not. {@link Router} is the seam it will land behind, and
 * {@link Builder#router(Router)} installs one today. Until one is installed, a move whose bounding
 * box crosses a zone is <b>refused</b>, loudly, with the zone named — which is the safe answer and
 * is what {@link SuperstructureReport#transitionsCrossingAZone()} lists at construction so the
 * refusal is discovered in the shop rather than in a match.
 */
public final class SafetyModel {

  /** The alert group every {@code SafetyModel} diagnostic is filed under. */
  public static final String kAlertGroup = "Superstructure";

  /** The {@code design/01} §8.4 cap on how many waypoints a route may contain. */
  public static final int kMaxWaypoints = 4;

  /** The default margin, in user units, a router expands a zone by before picking escape corners. */
  public static final double kDefaultMargin = 0.0;

  private final Mechanism m_axisA;
  private final Mechanism m_axisB;
  private final List<Zone> m_zones;
  private final List<Corridor> m_corridors;
  private final boolean m_synchronized;
  private final double m_marginA;
  private final double m_marginB;
  private final Router m_router;
  private final List<String> m_problems;

  private SafetyModel(
      Mechanism axisA,
      Mechanism axisB,
      List<Zone> zones,
      List<Corridor> corridors,
      boolean synchronizedAxes,
      double marginA,
      double marginB,
      Router router) {
    m_axisA = axisA;
    m_axisB = axisB;
    m_zones = List.copyOf(zones);
    m_corridors = List.copyOf(corridors);
    m_synchronized = synchronizedAxes;
    m_marginA = marginA;
    m_marginB = marginB;
    m_router = router;
    m_problems = collectProblems();
    if (!synchronizedAxes) {
      Alerts.warning(
              kAlertGroup,
              "Superstructure: synchronizedAxes(false) is set on the ("
                  + axisA.name()
                  + ", "
                  + axisB.name()
                  + ") pair. Collision avoidance is now tested against the straight line between "
                  + "configurations, but the two axes run independent profiles and the real path is "
                  + "L-shaped. A forbidden zone that the diagonal misses can still be entered. This "
                  + "alert stays up until synchronizedAxes(true).",
              MatchImpact.PIT_ONLY)
          .sticky(true)
          .set(true);
    }
  }

  /**
   * One forbidden rectangle of the configuration space.
   *
   * @param name a short, stable identifier; it is what the alert names
   * @param axisA the forbidden span of the first axis, in its user units
   * @param axisB the forbidden span of the second axis, in its user units
   * @param permitted when this returns true the zone is <b>open</b> — the
   *     {@link Builder#forbidUnless} form. An unconditional zone carries {@code () -> false}
   * @param why what hits what, in English; used verbatim in the alert text
   */
  public record Zone(
      String name, Range axisA, Range axisB, BooleanSupplier permitted, String why) {

    /** Normalises nulls; a zone is declared in a static field and must never throw from one. */
    public Zone {
      name = name == null || name.isBlank() ? "(unnamed zone)" : name.trim();
      axisA = axisA == null ? new Range(Double.NaN, Double.NaN, "?") : axisA;
      axisB = axisB == null ? new Range(Double.NaN, Double.NaN, "?") : axisB;
      permitted = permitted == null ? () -> false : permitted;
      why = why == null ? "" : why.strip();
    }

    /**
     * Whether an exact configuration is inside this rectangle.
     *
     * @param a the first axis, in user units
     * @param b the second axis, in user units
     * @return true when both bounds contain their value
     */
    public boolean contains(double a, double b) {
      return axisA.contains(a) && axisB.contains(b);
    }

    /**
     * Whether this zone can be opened by a condition.
     *
     * @return true for a {@link Builder#forbidUnless} zone
     */
    public boolean isConditional() {
      return !(permitted instanceof AlwaysForbidden);
    }

    /**
     * Whether this zone is forbidden <i>right now</i>.
     *
     * <p>A condition that throws counts as forbidden: a rule you cannot evaluate is a rule you have
     * to assume is blocking.
     *
     * @return true when the zone is currently closed
     */
    public boolean isActive() {
      try {
        return !permitted.getAsBoolean();
      } catch (Throwable t) {
        return true;
      }
    }

    /**
     * The zone as the alert text and the boot dump print it.
     *
     * @return for example {@code "arm-through-chassis ([0.000, 0.229] m x [-15.00, 40.00] deg): the
     *     arm hits the chassis crossbar below 9 in"}
     */
    public String describe() {
      return name
          + " ("
          + axisA.describe()
          + " x "
          + axisB.describe()
          + ")"
          + (isConditional() ? " [conditional]" : "")
          + ": "
          + why;
    }

    @Override
    public String toString() {
      return "Zone[" + name + "]";
    }
  }

  /**
   * A preferred travel corridor: a span of axis A over which axis B has one safe value.
   *
   * <p>This is 9143-A's "carry it tucked" behaviour, declared instead of coded. A router uses it in
   * preference to inventing an escape corner, because "carry the arm at 95 degrees while the
   * elevator moves" is a rule the team already knows and a corner is a number nobody chose.
   *
   * @param name a short identifier
   * @param axisA the span of the first axis this corridor covers, in user units
   * @param axisBValue the value the second axis holds while traversing it, in user units
   */
  public record Corridor(String name, Range axisA, double axisBValue) {

    /** Normalises nulls. */
    public Corridor {
      name = name == null || name.isBlank() ? "(unnamed corridor)" : name.trim();
      axisA = axisA == null ? new Range(Double.NaN, Double.NaN, "?") : axisA;
    }

    /**
     * Whether this corridor covers a required span of axis A.
     *
     * @param from one end of the required span, in user units
     * @param to the other end
     * @return true when the corridor contains both ends
     */
    public boolean covers(double from, double to) {
      return axisA.contains(from) && axisA.contains(to);
    }

    /**
     * The corridor as the boot dump prints it.
     *
     * @return the description
     */
    public String describe() {
      return String.format(
          Locale.ROOT, "%s: axisA %s carried at axisB = %.3f", name, axisA.describe(), axisBValue);
    }

    @Override
    public String toString() {
      return "Corridor[" + name + "]";
    }
  }

  /**
   * The seam the corner-escape router lands behind.
   *
   * <p>Deliberately an interface with one method and no state, so the router can be developed,
   * tested and swapped without touching a single line of the state machine. An implementation is
   * handed the model, the start configuration and the goal configuration, and returns the ordered
   * waypoint list — <b>excluding</b> the start, <b>including</b> the goal — or empty to mean "I
   * cannot solve this; refuse the move".
   *
   * <p>The contract a router must keep, restated from {@code design/01} §8.4 so it lives next to the
   * signature rather than only in a document: every hop between consecutive waypoints is re-tested
   * with the same rule the direct hop uses, so the guarantee is inductive over the whole plan and
   * not just the first leg; the list never exceeds {@link #kMaxWaypoints}; and a route is preferred
   * that moves the gravity-loaded axis least before it minimises total normalised travel, which for
   * an elevator&times;arm pair means "swing the arm out of the way at the current height" beats
   * "raise the elevator with the arm out".
   */
  @FunctionalInterface
  public interface Router {

    /**
     * Plans a zone-respecting path.
     *
     * @param model the model whose zones must not be entered
     * @param a0 the start value of axis A, in user units
     * @param b0 the start value of axis B, in user units
     * @param a1 the goal value of axis A, in user units
     * @param b1 the goal value of axis B, in user units
     * @return the waypoints, goal last; empty means "refuse the move"
     */
    List<double[]> route(SafetyModel model, double a0, double b0, double a1, double b1);
  }

  /**
   * Starts a model over two position axes.
   *
   * <p>{@code design/01} §8.4 writes this as {@code over(PositionMechanism, PositionMechanism)}.
   * {@code PositionMechanism} does not exist at this milestone, and the model needs exactly two
   * things from each axis — its name and its measured value in user units — both of which
   * {@link Mechanism} already provides. Taking the base type is therefore the same guarantee with a
   * wider door, and it costs nothing: a model built over a roller is a model whose zones are
   * nonsense, and no type could have stopped that anyway.
   *
   * @param axisA the first axis, conventionally the gravity-loaded one (the elevator)
   * @param axisB the second axis (the arm)
   * @return a builder
   * @throws NullPointerException if either axis is null
   */
  public static Builder over(Mechanism axisA, Mechanism axisB) {
    return new Builder(
        Objects.requireNonNull(
            axisA,
            "SafetyModel.over: axisA must not be null. The model reads its name and its measured "
                + "position every loop, so there is no degraded mode without it."),
        Objects.requireNonNull(
            axisB, "SafetyModel.over: axisB must not be null. See axisA."));
  }

  /** Assembles a {@link SafetyModel}. Reachable only through {@link SafetyModel#over}. */
  public static final class Builder {

    private final Mechanism m_a;
    private final Mechanism m_b;
    private final List<Zone> m_zones = new ArrayList<>();
    private final List<Corridor> m_corridors = new ArrayList<>();
    private boolean m_sync = true;
    private double m_marginA = kDefaultMargin;
    private double m_marginB = kDefaultMargin;
    private Router m_router;

    private Builder(Mechanism a, Mechanism b) {
      m_a = a;
      m_b = b;
    }

    /**
     * A region of the configuration space the mechanism may never occupy.
     *
     * @param name a short, stable identifier; it is what the alert names
     * @param axisA the forbidden span of the first axis
     * @param axisB the forbidden span of the second axis
     * @param why what hits what, in English
     * @return this builder
     */
    public Builder forbid(String name, Range axisA, Range axisB, String why) {
      m_zones.add(new Zone(name, axisA, axisB, new AlwaysForbidden(), why));
      return this;
    }

    /**
     * A region that may only be entered while a condition holds.
     *
     * @param name a short, stable identifier
     * @param a the span of the first axis
     * @param b the span of the second axis
     * @param ok when this is true the region is open
     * @param why what hits what when the condition does not hold
     * @return this builder
     */
    public Builder forbidUnless(String name, Range a, Range b, BooleanSupplier ok, String why) {
      m_zones.add(new Zone(name, a, b, ok == null ? new AlwaysForbidden() : ok, why));
      return this;
    }

    /**
     * A preferred travel corridor, used to pick waypoints (for example "carry the arm at 95
     * degrees").
     *
     * @param name a short identifier
     * @param axisA the span of the first axis the corridor covers
     * @param axisBValue the value the second axis holds while traversing it
     * @return this builder
     */
    public Builder corridor(String name, Range axisA, double axisBValue) {
      m_corridors.add(new Corridor(name, axisA, axisBValue));
      return this;
    }

    /**
     * Time-scale the faster axis so both arrive together; see "Why the bounding box" in the class
     * javadoc.
     *
     * <p>Defaults to <b>true</b> for any axis pair covered by a model. Setting it false disables the
     * collision guarantee and raises a persistent alert for as long as it is set.
     *
     * @param on whether the axes are synchronized
     * @return this builder
     */
    public Builder synchronizedAxes(boolean on) {
      m_sync = on;
      return this;
    }

    /**
     * How far outside each zone a router must place an escape waypoint, in each axis's user units.
     *
     * <p>Zero by default, which means "exactly on the boundary". A real robot wants a margin
     * slightly larger than its position tolerance, or an axis that settles one tolerance short of
     * the waypoint settles inside the zone.
     *
     * @param axisAmargin the margin on the first axis
     * @param axisBmargin the margin on the second axis
     * @return this builder
     */
    public Builder margin(double axisAmargin, double axisBmargin) {
      m_marginA = axisAmargin;
      m_marginB = axisBmargin;
      return this;
    }

    /**
     * Installs a {@link Router}, which is what turns a refused move into a detoured one.
     *
     * <p>No router ships at this milestone; see "What is here and what is deferred" in the class
     * javadoc. Without one, a move whose bounding box crosses an active zone is refused.
     *
     * @param router the router, or null to remove one
     * @return this builder
     */
    public Builder router(Router router) {
      m_router = router;
      return this;
    }

    /**
     * Builds the model.
     *
     * @return the model
     */
    public SafetyModel build() {
      return new SafetyModel(
          m_a, m_b, m_zones, m_corridors, m_sync, m_marginA, m_marginB, m_router);
    }
  }

  /**
   * Is this exact {@code (a, b)} pair legal right now?
   *
   * @param a the first axis, in user units
   * @param b the second axis, in user units
   * @return true when no active zone contains it
   */
  public boolean isSafe(double a, double b) {
    for (int i = 0; i < m_zones.size(); i++) {
      Zone zone = m_zones.get(i);
      if (zone.contains(a, b) && zone.isActive()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Which zone does this configuration violate, and why? Used verbatim in the alert text.
   *
   * @param a the first axis, in user units
   * @param b the second axis, in user units
   * @return the first active zone containing it, or empty
   */
  public Optional<Zone> violated(double a, double b) {
    for (int i = 0; i < m_zones.size(); i++) {
      Zone zone = m_zones.get(i);
      if (zone.contains(a, b) && zone.isActive()) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }

  /**
   * Which zone would a direct hop from one configuration to another enter?
   *
   * <p>The bounding box of the segment when {@link #synchronizedAxes()} is false — exactly the set
   * of configurations two unsynchronized profiles can reach — and the segment itself when it is
   * true, because a synchronized pair really does travel the diagonal.
   *
   * @param a0 the start value of axis A, in user units
   * @param b0 the start value of axis B
   * @param a1 the goal value of axis A
   * @param b1 the goal value of axis B
   * @return the first active zone the move would enter, or empty when the move is clear
   */
  public Optional<Zone> crossing(double a0, double b0, double a1, double b1) {
    for (int i = 0; i < m_zones.size(); i++) {
      Zone zone = m_zones.get(i);
      if (!zone.isActive()) {
        continue;
      }
      boolean hit =
          m_synchronized
              ? segmentIntersects(a0, b0, a1, b1, zone)
              : boxIntersects(a0, b0, a1, b1, zone);
      if (hit) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }

  /**
   * The core routine: a waypoint list from {@code (a0,b0)} to {@code (a1,b1)} that never enters a
   * zone.
   *
   * <p>Waypoints exclude the start and include the goal, so a clear move is the one-element list
   * {@code [{a1, b1}]}. An <b>empty</b> list means "no safe route" and the caller must refuse the
   * move — {@link Superstructure} does exactly that, holding the current state and raising an alert
   * that names the blocking zone and its explanation.
   *
   * <p>Today this answers the direct-hop case exactly and delegates everything else to the installed
   * {@link Router}, of which there is none by default. See "What is here and what is deferred".
   *
   * @param a0 the start value of axis A, in user units
   * @param b0 the start value of axis B
   * @param a1 the goal value of axis A
   * @param b1 the goal value of axis B
   * @return the waypoints, goal last; empty means refuse
   */
  public List<double[]> route(double a0, double b0, double a1, double b1) {
    if (crossing(a0, b0, a1, b1).isEmpty()) {
      return List.of(new double[] {a1, b1});
    }
    if (m_router == null) {
      return List.of();
    }
    List<double[]> routed;
    try {
      routed = m_router.route(this, a0, b0, a1, b1);
    } catch (Throwable t) {
      // A router that throws refuses. It must not take the loop down, and it must not be trusted
      // with a partial answer either.
      return List.of();
    }
    if (routed == null || routed.isEmpty() || routed.size() > kMaxWaypoints) {
      return List.of();
    }
    // The inductive re-test: a router is not trusted, it is checked. Every hop, including the last.
    double a = a0;
    double b = b0;
    for (int i = 0; i < routed.size(); i++) {
      double[] waypoint = routed.get(i);
      if (waypoint == null || waypoint.length < 2 || crossing(a, b, waypoint[0], waypoint[1]).isPresent()) {
        return List.of();
      }
      a = waypoint[0];
      b = waypoint[1];
    }
    return List.copyOf(routed);
  }

  /**
   * The message {@code design/01} §8.4 prints when a move is refused, with the real numbers in it.
   *
   * @param a0 the start value of axis A, in user units
   * @param b0 the start value of axis B
   * @param a1 the goal value of axis A
   * @param b1 the goal value of axis B
   * @return the multi-sentence refusal, ready for an alert or a log line
   */
  public String describeRefusal(double a0, double b0, double a1, double b1) {
    Optional<Zone> blocking = crossing(a0, b0, a1, b1);
    StringBuilder sb = new StringBuilder(320);
    sb.append(
        String.format(
            Locale.ROOT,
            "Superstructure: no safe route from (%s %.3f %s, %s %.3f %s) to (%s %.3f %s, %s %.3f %s).",
            m_axisA.name(),
            a0,
            m_axisA.units().unitLabel(),
            m_axisB.name(),
            b0,
            m_axisB.units().unitLabel(),
            m_axisA.name(),
            a1,
            m_axisA.units().unitLabel(),
            m_axisB.name(),
            b1,
            m_axisB.units().unitLabel()));
    blocking.ifPresent(
        zone -> sb.append(" Blocking zone: ").append(zone.name()).append(" (\"").append(zone.why()).append("\")."));
    if (!isSafe(a0, b0)) {
      sb.append(
          " The CURRENT position is already inside a forbidden zone — most likely the mechanism was "
              + "moved by hand while disabled, or homing seeded a wrong value. Recovery: run "
              + "Superstructure.escapeCommand(), or home both axes.");
    } else if (m_router == null) {
      sb.append(
          " No router is installed, so a move whose "
              + (m_synchronized ? "path" : "bounding box")
              + " crosses a zone is refused rather than detoured. Fix: choose a goal that does not "
              + "cross the zone, declare a corridor and an intermediate SuperState that uses it, or "
              + "install a SafetyModel.Router.");
    }
    return sb.toString();
  }

  /**
   * The measured value of the first axis, in its user units.
   *
   * @return the measurement
   */
  public double measuredA() {
    return m_axisA.units().fromSi(m_axisA.measuredSi());
  }

  /**
   * The measured value of the second axis, in its user units.
   *
   * @return the measurement
   */
  public double measuredB() {
    return m_axisB.units().fromSi(m_axisB.measuredSi());
  }

  /**
   * The first axis.
   *
   * @return the mechanism
   */
  public Mechanism axisA() {
    return m_axisA;
  }

  /**
   * The second axis.
   *
   * @return the mechanism
   */
  public Mechanism axisB() {
    return m_axisB;
  }

  /**
   * Every declared zone, in declaration order.
   *
   * @return the zones; never null
   */
  public List<Zone> zones() {
    return m_zones;
  }

  /**
   * Every declared corridor, in declaration order.
   *
   * @return the corridors; never null
   */
  public List<Corridor> corridors() {
    return m_corridors;
  }

  /**
   * Whether the two axes are time-scaled to arrive together.
   *
   * @return true when the diagonal is the tested path
   */
  public boolean synchronizedAxes() {
    return m_synchronized;
  }

  /**
   * Whether a {@link Router} is installed, and therefore whether a blocked move can be detoured
   * rather than refused.
   *
   * @return true when a router is present
   */
  public boolean routerInstalled() {
    return m_router != null;
  }

  /**
   * The margin a router expands the first axis's zone bounds by, in user units.
   *
   * @return the margin
   */
  public double marginA() {
    return m_marginA;
  }

  /**
   * The margin a router expands the second axis's zone bounds by, in user units.
   *
   * @return the margin
   */
  public double marginB() {
    return m_marginB;
  }

  /**
   * Everything structurally wrong with this model — a NaN bound, a zone whose unit label does not
   * match the axis it guards, a duplicate zone name.
   *
   * <p>Returned rather than thrown, because a model is declared in a {@code static final} field.
   * {@link Superstructure} lifts these into {@code ConfigError}s at construction.
   *
   * @return the problems; empty when the model is well formed
   */
  public List<String> problems() {
    return m_problems;
  }

  /**
   * The whole model, as the boot dump prints it.
   *
   * @return a multi-line description
   */
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(256);
    sb.append("SafetyModel over (")
        .append(m_axisA.name())
        .append(", ")
        .append(m_axisB.name())
        .append("), synchronizedAxes=")
        .append(m_synchronized)
        .append(", router=")
        .append(m_router == null ? "NONE (blocked moves are refused)" : m_router.getClass().getSimpleName())
        .append(nl);
    for (Zone zone : m_zones) {
      sb.append("  forbid   ").append(zone.describe()).append(nl);
    }
    for (Corridor corridor : m_corridors) {
      sb.append("  corridor ").append(corridor.describe()).append(nl);
    }
    for (String problem : m_problems) {
      sb.append("  PROBLEM  ").append(problem).append(nl);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "SafetyModel[" + m_axisA.name() + " x " + m_axisB.name() + ", " + m_zones.size() + " zone(s)]";
  }

  // ===============================================================================================
  // Geometry. Pure arithmetic, no HAL, no allocation.
  // ===============================================================================================

  private static boolean boxIntersects(double a0, double b0, double a1, double b1, Zone zone) {
    double aMin = Math.min(a0, a1);
    double aMax = Math.max(a0, a1);
    double bMin = Math.min(b0, b1);
    double bMax = Math.max(b0, b1);
    return aMin <= zone.axisA().max()
        && zone.axisA().min() <= aMax
        && bMin <= zone.axisB().max()
        && zone.axisB().min() <= bMax;
  }

  /**
   * Liang-Barsky segment-versus-rectangle clipping. True when any point of the closed segment lies
   * in the closed rectangle, including the degenerate case of a segment entirely inside it.
   */
  private static boolean segmentIntersects(double a0, double b0, double a1, double b1, Zone zone) {
    double da = a1 - a0;
    double db = b1 - b0;
    double t0 = 0.0;
    double t1 = 1.0;
    double[] p = {-da, da, -db, db};
    double[] q = {
      a0 - zone.axisA().min(),
      zone.axisA().max() - a0,
      b0 - zone.axisB().min(),
      zone.axisB().max() - b0
    };
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0.0) {
        if (q[i] < 0.0) {
          return false;
        }
        continue;
      }
      double r = q[i] / p[i];
      if (p[i] < 0.0) {
        if (r > t1) {
          return false;
        }
        if (r > t0) {
          t0 = r;
        }
      } else {
        if (r < t0) {
          return false;
        }
        if (r < t1) {
          t1 = r;
        }
      }
    }
    return t0 <= t1;
  }

  private List<String> collectProblems() {
    List<String> out = new ArrayList<>();
    String labelA = m_axisA.units().unitLabel();
    String labelB = m_axisB.units().unitLabel();
    List<String> seen = new ArrayList<>();
    for (Zone zone : m_zones) {
      for (String problem : zone.axisA().problems()) {
        out.add("zone \"" + zone.name() + "\" axisA: " + problem);
      }
      for (String problem : zone.axisB().problems()) {
        out.add("zone \"" + zone.name() + "\" axisB: " + problem);
      }
      if (seen.contains(zone.name())) {
        out.add(
            "two zones are both named \""
                + zone.name()
                + "\". Expected: one name per zone. A duplicate name makes the alert that fires "
                + "ambiguous, which is the one moment you need it not to be. Fix: rename one of "
                + "them after what it protects, e.g. \""
                + zone.name()
                + "-low\".");
      }
      seen.add(zone.name());
      out.addAll(labelProblem(zone.name(), "axisA", zone.axisA().unitLabel(), labelA, m_axisA.name()));
      out.addAll(labelProblem(zone.name(), "axisB", zone.axisB().unitLabel(), labelB, m_axisB.name()));
    }
    for (Corridor corridor : m_corridors) {
      for (String problem : corridor.axisA().problems()) {
        out.add("corridor \"" + corridor.name() + "\" axisA: " + problem);
      }
      out.addAll(
          labelProblem(corridor.name(), "axisA", corridor.axisA().unitLabel(), labelA, m_axisA.name()));
    }
    return List.copyOf(out);
  }

  private static List<String> labelProblem(
      String owner, String which, String declared, String expected, String axisName) {
    if (declared == null || "?".equals(declared) || declared.equals(expected)) {
      return List.of();
    }
    return List.of(
        "\""
            + owner
            + "\" declares its "
            + which
            + " span in "
            + declared
            + ", but "
            + axisName
            + " publishes "
            + expected
            + ". Expected: "
            + expected
            + ". A degree span compared against a metre measurement is never violated and never "
            + "protects anything. Fix: build the range with the matching Measure type — "
            + ("m".equals(expected) ? "Range.of(Inches.of(0), Inches.of(9))" : "Range.of(Degrees.of(-15), Degrees.of(40))")
            + ".");
  }

  /** Marks an unconditional zone, so {@link Zone#isConditional()} can tell the two forms apart. */
  private static final class AlwaysForbidden implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
      return false;
    }
  }
}

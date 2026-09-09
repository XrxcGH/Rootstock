/**
 * The robot's state machine: orthogonal states, declarative interlocks, declarative forbidden zones,
 * and one union requirement.
 *
 * <h2>What this package is for</h2>
 *
 * <p>Three things go wrong with every hand-written superstructure in the survey, and this package
 * exists to make all three structurally impossible rather than merely discouraged.
 *
 * <p><b>1. "My auto stops halfway."</b> PathPlanner's best-documented footgun is that a
 * {@code NamedCommand} sharing subsystem requirements with the enclosing auto group cancels the auto
 * group. {@link org.rootstock.superstructure.Superstructure} is <i>itself</i> the one
 * {@code Subsystem} the scheduler knows about: it declares the union requirement exactly once, the
 * coordinated mechanisms do not self-register, and {@code request(state)} requires only the
 * superstructure. There is one claim on the elevator in the whole robot and this object holds it.
 *
 * <p><b>2. "The rollers were still running from the previous state."</b>
 * {@code 0000-XXXX-Robot-Template}'s superstructure requires every {@code case} to reset every
 * actuator, and its own comments record the cost of forgetting. The
 * {@linkplain org.rootstock.superstructure.Superstructure default-output inversion} applies every
 * mechanism's declared default every loop, unconditionally, and lets the active state override only
 * what it names.
 *
 * <p><b>3. "The arm went through the funnel."</b>
 * {@link org.rootstock.superstructure.SafetyModel} replaces a ~250-line hand-written branch tree
 * with a list of forbidden rectangles in configuration space, and tests moves against the
 * <i>bounding box</i> of the transition rather than its diagonal — because two independent motion
 * profiles trace an L, not a line, and the L can pass straight through a rectangle the diagonal
 * misses.
 *
 * <p>And one thing that goes wrong at the worst possible moment:
 * {@link org.rootstock.superstructure.AxisGoal.Named} setpoint strings are resolved at
 * <b>construction</b>, with a Levenshtein "did you mean" and a search of the sibling mechanisms, so
 * a typo is a boot-time message rather than a button that does nothing in a match.
 *
 * <h2>The types</h2>
 *
 * <ul>
 *   <li>{@link org.rootstock.superstructure.SuperState} — the interface a team's state enum
 *       implements. The enum is the team's; the machinery is ours.
 *   <li>{@link org.rootstock.superstructure.AxisGoal} — what one axis is being told to do, as a
 *       sealed value type.
 *   <li>{@link org.rootstock.superstructure.GoalReceiver} — the seam between a goal and the
 *       mechanism that honours it.
 *   <li>{@link org.rootstock.superstructure.Interlock} — a declarative, inspectable rule about
 *       which transitions are permitted.
 *   <li>{@link org.rootstock.superstructure.SafetyModel} — declarative forbidden zones and
 *       corridors over a two-axis configuration space.
 *   <li>{@link org.rootstock.superstructure.TransitionPlanner} — plans from live measured state,
 *       and gates every waypoint on measurement rather than on a timer.
 *   <li>{@link org.rootstock.superstructure.SuperstructureReport} — the static analysis computed at
 *       construction, printed in the boot dump.
 *   <li>{@link org.rootstock.superstructure.Superstructure} — the state machine itself, which is
 *       also a {@code GoalBus}, a {@code TelemetrySource} and a {@code LifecycleHook}.
 * </ul>
 *
 * <h2>Deferred, and named rather than hidden</h2>
 *
 * <p>The collision-avoidance <b>router</b> — the part that invents intermediate waypoints when a
 * direct hop is blocked — is not built at this milestone. It is held back pending validation against
 * a real robot's geometry, because a router that produces a plausible but wrong waypoint list
 * converts a refusal a team would have caught in the shop into a move that looks fine until it is
 * not. {@link org.rootstock.superstructure.SafetyModel.Router} is the seam it lands behind. Until
 * one is installed, a move whose bounding box crosses a zone is refused, loudly, with the zone
 * named — and {@link org.rootstock.superstructure.SuperstructureReport#transitionsCrossingAZone()}
 * lists every such move at construction so the refusal is discovered before an event.
 *
 * <p>{@code characterizeTransitions()} is deferred with it, and for the same reason: its own safety
 * gates require that every measured transition be the routed plan a match would actually run.
 */
package org.rootstock.superstructure;

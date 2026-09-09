package org.rootstock.superstructure;

import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import org.rootstock.config.ConfigError;
import org.rootstock.config.Setpoint;
import org.rootstock.core.SafeMode;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.LifecycleHook;
import org.rootstock.core.spi.Tier;
import org.rootstock.mechanism.GoalBus;
import org.rootstock.mechanism.Mechanism;
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.TelemetrySource;
import org.rootstock.units.SiDomain;

/**
 * The robot's state machine: one requested state, one active state, and a plan between them that is
 * recomputed from live measured state.
 *
 * <h2>The union requirement — the whole point of the type</h2>
 *
 * <p>PathPlanner's best-documented footgun is that a {@code NamedCommand} sharing subsystem
 * requirements with the enclosing auto group <b>cancels the auto group</b>. A trajectory trigger that
 * fires "raise the elevator" therefore silently kills the path it was fired from, and the symptom —
 * "my auto stops halfway" — looks nothing like the cause. It is the single most common auto bug in
 * the survey.
 *
 * <p>A {@code Superstructure} makes it structurally unreachable. It is <b>itself</b> the one
 * {@code Subsystem} the scheduler knows about: it declares the union requirement exactly once, for
 * the whole match, and {@link #request(Enum)} returns a command that requires <b>only the
 * superstructure</b> and never the mechanisms it coordinates. The coordinated mechanisms do not
 * self-register — {@link #init()} unregisters any that were registered before it ran, and the
 * superstructure calls their {@code periodic()} itself, in order, every loop. There is exactly one
 * claim on the elevator in the whole robot and it is held by this object, so two commands can never
 * be holding half of it each.
 *
 * <p><b>What that means for you:</b> do not call {@code registerWithScheduler()} on a coordinated
 * mechanism, and do not give one a default command. Pass the superstructure to
 * {@code RootstockRegistry.addAll(...)} and everything below is handled.
 *
 * <h2>The default-output inversion (§8.5) — the bug class this kills</h2>
 *
 * <p>{@code 0000-XXXX-Robot-Template}'s superstructure requires every {@code case} to remember to
 * reset every actuator. Its own comments prove the cost: <i>"Don't leave rollers running at whatever
 * the previous state set"</i> on one branch, <i>"entering from AIM/SHOOT must spin the flywheels
 * down"</i> on another, and an {@code AIM} case that silently forgets to retract the intake. Every
 * state has to know about every mechanism, which is O(states &times; mechanisms) chances to forget.
 *
 * <p>This class inverts it. <b>Every registered mechanism gets its declared default, every loop,
 * unconditionally</b>, and the active state overrides only what it names. Forgetting to stop a roller
 * becomes structurally impossible and each state's declaration shrinks to its actual intent. The
 * defaults are declared once, at construction:
 *
 * <pre>{@code
 * new Superstructure.Builder<>(RobotState.class, RobotState.IDLE)
 *     .defaultFor(elevator, elevatorReceiver, AxisGoal.of(RobotConfig.ELEVATOR_STOW))
 *     .defaultFor(arm,      armReceiver,      AxisGoal.of(RobotConfig.ARM_STOW))
 *     .defaultFor(intake,   intakeReceiver,   AxisGoal.percent(0.0))
 *     .defaultFor(flywheel, flywheelReceiver, AxisGoal.neutral())
 *     .safety(SAFETY)
 *     .interlocks(INTERLOCKS)
 *     .build();
 * }</pre>
 *
 * <h2>Setpoint names cannot fail at button-press time (§8.9)</h2>
 *
 * <p>{@link #Builder#build()} resolves every {@link AxisGoal.Named} and every unresolved
 * {@link Setpoint} handle in every state against the declaring mechanism's setpoint list, and
 * collects one <b>FATAL</b> {@code ConfigError} per miss — all of them at once, with a Levenshtein
 * "did you mean" and a search of the sibling mechanisms, into SAFE_MODE. A typo in a setpoint name
 * is a boot-time message in plain English, never a failure at the moment a driver presses a button
 * in a match.
 *
 * <h2>Telemetry</h2>
 *
 * <p>Publishes under its own name, {@code Rootstock/Superstructure/}, because it is a
 * {@link TelemetrySource} in its own right rather than a rider on a mechanism's block. The name is
 * the literal {@code "Superstructure"} and not a constructor argument, because {@code design/04}
 * hard-codes that segment in its own key literals. <b>Limitation, stated:</b> that means exactly one
 * superstructure per robot.
 *
 * @param <S> the team's state enum, which implements {@link SuperState}
 */
public final class Superstructure<S extends Enum<S> & SuperState>
    implements Subsystem, TelemetrySource, GoalBus<S>, LifecycleHook {

  /** The telemetry name, the health name and the alert group. A literal; see the class javadoc. */
  public static final String kName = "Superstructure";

  /** Where the superstructure sits in the lifecycle order: after telemetry, before tuning. */
  public static final int kLifecyclePriority = 20;

  /** The conservative planned transition duration used when nothing has been measured. */
  public static final double kDefaultProfileBoundSeconds = 2.0;

  /** The largest edit distance at which a "did you mean" is offered for a setpoint name. */
  public static final int kMaxSuggestionDistance = 2;

  /** Default waypoint arrival tolerance for a linear axis, in metres. */
  public static final double kDefaultLinearToleranceMeters = 0.01;

  /** Default waypoint arrival tolerance for a rotary axis, in degrees. */
  public static final double kDefaultRotaryToleranceDegrees = 2.0;

  private static final String kPrefix = RootstockLog.kRoot + "/" + kName;
  private static final String kStateKey = kPrefix + "/State";
  private static final String kRequestedKey = kPrefix + "/Requested";
  private static final String kActiveKey = kPrefix + "/Active";
  private static final String kPreviousKey = kPrefix + "/Previous";
  private static final String kTransitioningKey = kPrefix + "/Transitioning";
  private static final String kAtStateKey = kPrefix + "/AtState";
  private static final String kPlanKey = kPrefix + "/Plan";
  private static final String kBlockedKey = kPrefix + "/Blocked";
  private static final String kBlockedReasonKey = kPrefix + "/BlockedReason";
  private static final String kSafeZoneViolationKey = kPrefix + "/SafeZoneViolation";
  private static final String kTimeInStateKey = kPrefix + "/TimeInStateSec";
  private static final String kPlannedSecondsKey = kPrefix + "/PlannedSeconds";
  private static final String kPlannedSourceKey = kPrefix + "/PlannedSource";
  private static final String kSynchronizedKey = kPrefix + "/Synchronized";

  private static final String[] kNoPlan = new String[0];

  private final Class<S> m_stateType;
  private final S m_idle;
  private final S[] m_allStates;
  private final Mechanism[] m_mechanisms;
  private final GoalReceiver[] m_receivers;
  private final AxisGoal[] m_defaults;
  private final Map<Mechanism, Integer> m_indexOf;
  private final List<Interlock<S>> m_interlocks;
  private final SafetyModel m_safety;
  private final TransitionPlanner<S> m_planner;
  private final Map<S, Runnable> m_onEntry;
  private final Map<S, Map<S, Double>> m_measuredCosts;
  private final double m_profileBoundSeconds;
  private final double m_toleranceA;
  private final double m_toleranceB;
  private final boolean m_drivesMechanismPeriodic;
  private final SuperstructureReport m_report;
  private final List<ConfigError> m_errors;

  private final RootstockAlert m_blockedAlert;
  private final RootstockAlert m_noRouteAlert;
  private final RootstockAlert m_periodicThrew;
  private final RootstockAlert m_misuse;
  private final RootstockAlert m_refused;
  private final Map<S, Trigger> m_atTriggers;
  private final Trigger m_transitioningTrigger;

  private S m_requested;
  private S m_lastRequested;
  private S m_active;
  private S m_previous;
  private S m_previousActive;
  private TransitionPlanner.Plan<S> m_plan;
  private String[] m_planNames = kNoPlan;
  private boolean m_blocked;
  private String m_blockedText = "";
  private String m_blockedReason = "";
  private String m_zoneViolation = "";
  private SafetyModel.Zone m_lastViolation;
  private double m_stateEntrySeconds;
  private boolean m_primed;
  private boolean m_periodicFailed;

  /**
   * The direct constructor from {@code design/01} §8.1, for a superstructure whose mechanisms
   * implement {@link GoalReceiver} themselves and whose defaults are declared as {@link AxisGoal#hold()}.
   *
   * <p>Prefer {@link Builder}: it is the only form that can declare the per-mechanism defaults the
   * §8.5 inversion needs, and a superstructure with no declared defaults is a superstructure whose
   * headline feature is switched off. This constructor exists because the design names it and
   * because a two-mechanism robot with no rollers genuinely does not need one.
   *
   * @param stateType the team's state enum class
   * @param idleState the state the robot boots into and must always be able to return to
   * @param safety the collision model, or null
   * @param interlocks the transition rules, or null for none
   * @param mechanisms the coordinated mechanisms; each must implement {@link GoalReceiver} or it is
   *     wrapped in the degraded {@link GoalReceiver#forMechanism(Mechanism)} adapter
   * @throws NullPointerException if {@code stateType} or {@code idleState} is null
   */
  public Superstructure(
      Class<S> stateType,
      S idleState,
      SafetyModel safety,
      List<Interlock<S>> interlocks,
      Mechanism... mechanisms) {
    this(toBuilder(stateType, idleState, safety, interlocks, mechanisms));
  }

  private static <S extends Enum<S> & SuperState> Builder<S> toBuilder(
      Class<S> stateType,
      S idleState,
      SafetyModel safety,
      List<Interlock<S>> interlocks,
      Mechanism... mechanisms) {
    Builder<S> builder = new Builder<>(stateType, idleState).safety(safety);
    if (interlocks != null) {
      builder.interlocks(interlocks);
    }
    if (mechanisms != null) {
      for (Mechanism mechanism : mechanisms) {
        builder.add(mechanism);
      }
    }
    return builder;
  }

  private Superstructure(Builder<S> builder) {
    m_stateType =
        Objects.requireNonNull(
            builder.m_stateType,
            "Superstructure: the state enum class must not be null. It is what describe() declares "
                + "to the dashboard and what report() walks, so there is no degraded mode without "
                + "it. Pass RobotState.class.");
    m_idle =
        Objects.requireNonNull(
            builder.m_idle,
            "Superstructure: the idle state must not be null. Every reachability answer in "
                + "SuperstructureReport is relative to it, and \"can this robot stow?\" is the "
                + "question the report exists to answer.");
    S[] constants = m_stateType.getEnumConstants();
    m_allStates = constants == null ? newStateArray(0) : constants;

    int count = builder.m_mechanisms.size();
    m_mechanisms = builder.m_mechanisms.toArray(new Mechanism[0]);
    m_receivers = new GoalReceiver[count];
    m_defaults = new AxisGoal[count];
    m_indexOf = new IdentityHashMap<>(Math.max(1, count));
    for (int i = 0; i < count; i++) {
      Mechanism mechanism = m_mechanisms[i];
      GoalReceiver receiver = builder.m_receivers.get(mechanism);
      m_receivers[i] = receiver == null ? GoalReceiver.forMechanism(mechanism) : receiver;
      m_defaults[i] = builder.m_defaults.get(mechanism);
      m_indexOf.put(mechanism, i);
    }

    m_interlocks = List.copyOf(builder.m_interlocks);
    m_safety = builder.m_safety;
    m_planner = new TransitionPlanner<>(m_safety);
    m_onEntry = new EnumMap<>(m_stateType);
    m_onEntry.putAll(builder.m_onEntry);
    m_measuredCosts = new EnumMap<>(m_stateType);
    for (Map.Entry<S, Map<S, Double>> entry : builder.m_costs.entrySet()) {
      m_measuredCosts.put(entry.getKey(), new EnumMap<>(entry.getValue()));
    }
    m_profileBoundSeconds =
        builder.m_profileBoundSeconds > 0.0
            ? builder.m_profileBoundSeconds
            : kDefaultProfileBoundSeconds;
    m_toleranceA =
        builder.m_toleranceA > 0.0 ? builder.m_toleranceA : defaultTolerance(m_safety, true);
    m_toleranceB =
        builder.m_toleranceB > 0.0 ? builder.m_toleranceB : defaultTolerance(m_safety, false);
    m_drivesMechanismPeriodic = builder.m_drivesMechanismPeriodic;

    m_requested = m_idle;
    m_lastRequested = m_idle;
    m_active = m_idle;
    m_previous = m_idle;
    m_previousActive = null;
    m_plan = m_planner.direct(m_idle);
    m_planNames = m_plan.waypointNames();
    m_stateEntrySeconds = Clock.seconds();

    m_blockedAlert = Alerts.warning(kName, kName + "/blocked", MatchImpact.BLOCKS_MATCH);
    m_noRouteAlert = Alerts.error(kName, kName + "/no-safe-route", MatchImpact.BLOCKS_MATCH);
    m_periodicThrew =
        Alerts.error(kName, kName + "/periodic-threw", MatchImpact.BLOCKS_MATCH).sticky(true);
    m_misuse = Alerts.warning(kName, kName + "/misuse", MatchImpact.PIT_ONLY).sticky(true);
    m_refused = Alerts.warning(kName, kName + "/refused", MatchImpact.PIT_ONLY).sticky(true);
    m_atTriggers = new EnumMap<>(m_stateType);
    m_transitioningTrigger = new Trigger(this::isTransitioning);

    List<ConfigError> errors = new ArrayList<>();
    m_report = analyse(errors);
    m_errors = List.copyOf(errors);
    raiseFindings();
    SafeMode.enter(ConfigError.toFaults(m_errors));
  }

  @SuppressWarnings("unchecked")
  private static <S extends Enum<S> & SuperState> S[] newStateArray(int size) {
    return (S[]) new Enum<?>[size];
  }

  private static double defaultTolerance(SafetyModel safety, boolean first) {
    if (safety == null) {
      return kDefaultLinearToleranceMeters;
    }
    Mechanism axis = first ? safety.axisA() : safety.axisB();
    return axis.units().siDomain() == SiDomain.LINEAR_METERS
        ? kDefaultLinearToleranceMeters
        : kDefaultRotaryToleranceDegrees;
  }

  // ===============================================================================================
  // Builder
  // ===============================================================================================

  /**
   * Declares a superstructure: its mechanisms, their <b>defaults</b>, its interlocks and its
   * collision model.
   *
   * <p>The declared defaults are what make the §8.5 inversion work, and they are the reason this is
   * the preferred construction path. A mechanism registered with {@link #add(Mechanism)} and no
   * default is left alone by the inversion — it is listed in
   * {@link SuperstructureReport#axesWithNoDeclaredDefault()} so the gap is visible rather than
   * assumed.
   *
   * @param <S> the team's state enum
   */
  public static final class Builder<S extends Enum<S> & SuperState> {

    private final Class<S> m_stateType;
    private final S m_idle;
    private final List<Mechanism> m_mechanisms = new ArrayList<>();
    private final Map<Mechanism, GoalReceiver> m_receivers = new IdentityHashMap<>();
    private final Map<Mechanism, AxisGoal> m_defaults = new IdentityHashMap<>();
    private final List<Interlock<S>> m_interlocks = new ArrayList<>();
    private final Map<S, Runnable> m_onEntry = new LinkedHashMap<>();
    private final Map<S, Map<S, Double>> m_costs = new LinkedHashMap<>();
    private SafetyModel m_safety;
    private double m_profileBoundSeconds = kDefaultProfileBoundSeconds;
    private double m_toleranceA;
    private double m_toleranceB;
    private boolean m_drivesMechanismPeriodic = true;

    /**
     * Starts a declaration.
     *
     * @param stateType the team's state enum class
     * @param idleState the state the robot boots into and must always be able to return to
     */
    public Builder(Class<S> stateType, S idleState) {
      m_stateType = stateType;
      m_idle = idleState;
    }

    /**
     * Coordinates a mechanism with <b>no declared default</b>.
     *
     * <p>Legal, listed in the report, and usually not what you want: an axis with no default is an
     * axis the inversion cannot protect, so a state that forgets to name it leaves it wherever the
     * previous state put it — which is the exact bug the inversion exists to remove.
     *
     * @param mechanism the mechanism; null is ignored
     * @return this builder
     */
    public Builder<S> add(Mechanism mechanism) {
      if (mechanism != null && !m_mechanisms.contains(mechanism)) {
        m_mechanisms.add(mechanism);
      }
      return this;
    }

    /**
     * Coordinates a mechanism with an explicit {@link GoalReceiver} and no declared default.
     *
     * @param mechanism the mechanism
     * @param receiver how to command it
     * @return this builder
     */
    public Builder<S> add(Mechanism mechanism, GoalReceiver receiver) {
      add(mechanism);
      if (mechanism != null && receiver != null) {
        m_receivers.put(mechanism, receiver);
      }
      return this;
    }

    /**
     * Coordinates a mechanism and declares what it does when no state says otherwise.
     *
     * @param mechanism the mechanism
     * @param defaultGoal what it does by default, every loop, unless the active state names it
     * @return this builder
     */
    public Builder<S> defaultFor(Mechanism mechanism, AxisGoal defaultGoal) {
      add(mechanism);
      if (mechanism != null && defaultGoal != null) {
        m_defaults.put(mechanism, defaultGoal);
      }
      return this;
    }

    /**
     * Coordinates a mechanism with an explicit receiver and declares its default.
     *
     * @param mechanism the mechanism
     * @param receiver how to command it
     * @param defaultGoal what it does by default
     * @return this builder
     */
    public Builder<S> defaultFor(
        Mechanism mechanism, GoalReceiver receiver, AxisGoal defaultGoal) {
      add(mechanism, receiver);
      return defaultFor(mechanism, defaultGoal);
    }

    /**
     * Adds one transition rule.
     *
     * @param interlock the rule; null is ignored
     * @return this builder
     */
    public Builder<S> interlock(Interlock<S> interlock) {
      if (interlock != null) {
        m_interlocks.add(interlock);
      }
      return this;
    }

    /**
     * Adds several transition rules.
     *
     * @param interlocks the rules; null is ignored, as is a null entry
     * @return this builder
     */
    public Builder<S> interlocks(List<Interlock<S>> interlocks) {
      if (interlocks != null) {
        for (Interlock<S> interlock : interlocks) {
          interlock(interlock);
        }
      }
      return this;
    }

    /**
     * Installs the collision model.
     *
     * @param safety the model, or null for none
     * @return this builder
     */
    public Builder<S> safety(SafetyModel safety) {
      m_safety = safety;
      return this;
    }

    /**
     * An action that runs <b>once</b> each time a state is entered — the on-entry latch
     * {@code 0000-XXXX-Robot-Template} needs for its climber hold-position capture.
     *
     * <p>Re-armed by {@link Superstructure#resetStateEntry()}, which the registry calls from
     * {@code disabledInit} for you, because it is easy to forget and the symptom is a climber that
     * captures its hold position once per power cycle.
     *
     * @param state the state
     * @param action what to do on entry; it must not throw, and a throw is caught and reported
     *     rather than allowed to take the loop down
     * @return this builder
     */
    public Builder<S> onEntry(S state, Runnable action) {
      if (state != null && action != null) {
        m_onEntry.put(state, action);
      }
      return this;
    }

    /**
     * Records a measured transition cost, as {@code characterizeTransitions()} will write and
     * {@code plannedTransitionSeconds} will read.
     *
     * <p>Present now so a team that has timed its own transitions with a stopwatch can hand the
     * numbers over and get {@link CostSource#MEASURED} out of {@code plannedTransitionSource},
     * instead of an auto budget that is a silent guess.
     *
     * @param from the source state
     * @param to the destination state
     * @param seconds the measured duration
     * @return this builder
     */
    public Builder<S> transitionSeconds(S from, S to, double seconds) {
      if (from != null && to != null && seconds > 0.0 && Double.isFinite(seconds)) {
        m_costs.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(to, seconds);
      }
      return this;
    }

    /**
     * The conservative duration reported for any pair that has not been measured.
     *
     * @param seconds the bound; non-positive values are ignored
     * @return this builder
     */
    public Builder<S> profileBoundSeconds(double seconds) {
      m_profileBoundSeconds = seconds;
      return this;
    }

    /**
     * The waypoint arrival tolerances, in each safety axis's user units.
     *
     * <p>Defaults to 1 cm on a linear axis and 2 degrees on a rotary one. A tolerance smaller than
     * the mechanism's own settling error is a transition that never advances.
     *
     * @param axisA the tolerance on the first safety axis
     * @param axisB the tolerance on the second
     * @return this builder
     */
    public Builder<S> waypointTolerance(double axisA, double axisB) {
      m_toleranceA = axisA;
      m_toleranceB = axisB;
      return this;
    }

    /**
     * Whether the superstructure calls each coordinated mechanism's {@code periodic()} itself.
     *
     * <p>True by default, and it is half of the union requirement: the mechanisms are not registered
     * with the scheduler, so somebody has to drive them, and it is this. Set it false only if you
     * are driving them yourself from your own robot loop — in which case you have taken on the other
     * half too.
     *
     * @param on whether to drive them
     * @return this builder
     */
    public Builder<S> drivesMechanismPeriodic(boolean on) {
      m_drivesMechanismPeriodic = on;
      return this;
    }

    /**
     * Validates every declaration and builds the superstructure.
     *
     * <p>Every {@link AxisGoal.Named} and every unresolved {@link Setpoint} handle in every state is
     * resolved against its mechanism's declared setpoints here, and every miss becomes a FATAL
     * {@code ConfigError} — all of them at once, into SAFE_MODE, with a "did you mean". That is
     * {@code design/01} §8.9 layer 2, and it is why a typo cannot fail at button-press time.
     *
     * @return the superstructure
     */
    public Superstructure<S> build() {
      return new Superstructure<>(this);
    }
  }

  // ===============================================================================================
  // Requests
  // ===============================================================================================

  /**
   * Requests a state. Returns a <b>fresh</b> command every call, so it is safe as a PathPlanner
   * {@code NamedCommand} and safe to bind to two buttons.
   *
   * <p>The command requires <b>only this superstructure</b> — never the mechanisms it coordinates.
   * That is the union requirement, and it is what stops a trajectory trigger cancelling the auto
   * group it was fired from.
   *
   * <p>It finishes when {@link #atState()} is true. It is safe to interrupt at any moment: the
   * request is a field, the state machine runs from {@code periodic()}, and interrupting the command
   * stops the waiting, not the moving. Interrupting therefore leaves the superstructure travelling to
   * the state that was last requested, which is the behaviour a driver expects when they let go of a
   * button.
   *
   * @param state the state to request
   * @return the command
   */
  @Override
  public Command request(S state) {
    if (state == null) {
      return refusal(
          "request(null)",
          "Superstructure.request(null): there is no state to go to, so the command does nothing "
              + "and the superstructure is holding. Fix: pass a constant from "
              + m_stateType.getSimpleName()
              + ".");
    }
    if (SafeMode.isActive()) {
      return refusal(
          "request(" + state.name() + ")",
          "Superstructure.request("
              + state.name()
              + ") refused: the robot is in SAFE_MODE because a fatal configuration error was "
              + "collected at boot. The command did nothing and every mechanism is holding. Fix: "
              + "read the boot dump — every fatal error is printed there with the field, the value "
              + "and what to type instead.");
    }
    return Commands.runOnce(() -> setRequested(state), this)
        .andThen(Commands.waitUntil(this::atState))
        .withName(kName + ".request(" + state.name() + ")");
  }

  /**
   * Fire-and-forget for state-based teams: sets the requested state; {@link #periodic()} does the
   * rest.
   *
   * <p>Legal from a {@code Trigger} inside a running auto, which is the entire reason it exists in
   * preference to scheduling a command. Never blocks and never throws.
   *
   * @param state the state to request; null is ignored with a named alert rather than a throw
   */
  public void setRequested(S state) {
    if (state == null) {
      m_misuse
          .text(
              "Superstructure.setRequested(null): the request was ignored and the superstructure "
                  + "is still travelling to "
                  + m_requested.name()
                  + ". Fix: pass a constant from "
                  + m_stateType.getSimpleName()
                  + ".")
          .set(true);
      return;
    }
    m_requested = state;
  }

  /**
   * Fire-and-forget, under the {@link GoalBus} name.
   *
   * @param goal the state to request
   */
  @Override
  public void requestAsync(S goal) {
    setRequested(goal);
  }

  /**
   * What the driver asked for.
   *
   * @return the requested state; never null
   */
  public S requested() {
    return m_requested;
  }

  /**
   * The requested state, under the {@link GoalBus} name.
   *
   * @return the requested state; never null
   */
  @Override
  public S currentGoal() {
    return m_requested;
  }

  /**
   * What is actually being executed, which may lag the request while an interlock or a forbidden
   * zone is holding it.
   *
   * @return the active state; never null
   */
  public S active() {
    return m_active;
  }

  /**
   * The state active before this one.
   *
   * @return the previous state; never null
   */
  public S previous() {
    return m_previous;
  }

  /**
   * Whether a transition is in flight.
   *
   * @return true when the superstructure has not settled at the requested state
   */
  public boolean isTransitioning() {
    return !atState();
  }

  /**
   * Whether the superstructure has settled: active equals requested, the plan is on its last leg,
   * nothing is blocked, and every goal the active state asserts reports arrival.
   *
   * @return true when settled
   */
  public boolean atState() {
    if (m_blocked || m_periodicFailed || m_active != m_requested || !m_plan.isLast()) {
      return false;
    }
    return assertedGoalsReached(m_active);
  }

  /**
   * Whether no transition is in flight, under the {@link GoalBus} name.
   *
   * @return true when settled
   */
  @Override
  public boolean isStable() {
    return atState();
  }

  /**
   * A trigger that is true while the superstructure has settled at a state.
   *
   * @param state the state to watch
   * @return the trigger; cached, so binding the same state twice does not build two
   */
  public Trigger at(S state) {
    if (state == null) {
      return new Trigger(() -> false);
    }
    return m_atTriggers.computeIfAbsent(state, s -> new Trigger(() -> m_active == s && atState()));
  }

  /**
   * A trigger that is true while the superstructure has settled at a state, under the
   * {@link GoalBus} name.
   *
   * @param goal the state to watch
   * @return the trigger
   */
  @Override
  public Trigger atGoal(S goal) {
    return at(goal);
  }

  /**
   * A trigger that is true while a transition is in flight.
   *
   * @return the trigger
   */
  public Trigger transitioning() {
    return m_transitioningTrigger;
  }

  /**
   * Whether an interlock or a forbidden zone is currently holding the request.
   *
   * @return true when blocked
   */
  public boolean isBlocked() {
    return m_blocked;
  }

  /**
   * Why the superstructure is blocked, in the words the alert and the dashboard carry.
   *
   * @return the explanation, or an empty string when nothing is blocked
   */
  public String blockedReason() {
    return m_blockedText;
  }

  /**
   * How long the active state has been active.
   *
   * @return the duration in seconds
   */
  public double timeInStateSeconds() {
    return Clock.seconds() - m_stateEntrySeconds;
  }

  // ===============================================================================================
  // Planning cost, for Auto
  // ===============================================================================================

  /**
   * How long a transition is expected to take.
   *
   * <p>Measured when a cost has been recorded for the pair, and a conservative profile bound
   * otherwise. {@link #plannedTransitionSource} reports which, so an {@code AutoStep} budget is
   * never a silent guess.
   *
   * @param from the source state
   * @param to the destination state
   * @return the duration in seconds
   */
  public double plannedTransitionSeconds(S from, S to) {
    if (from == null || to == null || from == to) {
      return 0.0;
    }
    Map<S, Double> row = m_measuredCosts.get(from);
    Double measured = row == null ? null : row.get(to);
    return measured == null ? m_profileBoundSeconds : measured;
  }

  /**
   * Where {@link #plannedTransitionSeconds(Enum, Enum)}'s answer came from.
   *
   * @param from the source state
   * @param to the destination state
   * @return {@link CostSource#MEASURED} or {@link CostSource#PROFILE_BOUND}
   */
  public CostSource plannedTransitionSource(S from, S to) {
    Map<S, Double> row = from == null ? null : m_measuredCosts.get(from);
    return row != null && to != null && row.containsKey(to)
        ? CostSource.MEASURED
        : CostSource.PROFILE_BOUND;
  }

  /**
   * The planned duration of the transition currently in flight, for auto budget reporting.
   *
   * @return the duration in seconds, or zero when nothing is in flight
   */
  @Override
  public double plannedTransitionSeconds() {
    return isTransitioning() ? plannedTransitionSeconds(m_previous, m_requested) : 0.0;
  }

  // ===============================================================================================
  // Static analysis and characterization
  // ===============================================================================================

  /**
   * The static analysis computed at construction: unreachable states, states that cannot stow,
   * transitions that cross a forbidden zone, axes with no declared default and unresolved setpoint
   * names.
   *
   * @return the report; never null
   */
  public SuperstructureReport report() {
    return m_report;
  }

  /**
   * Every configuration error this superstructure collected at construction, in the order they were
   * found.
   *
   * <p>Already handed to {@code SafeMode} by {@code build()}; exposed so a test can assert on them
   * without reading global state, and so a boot dump can print them next to the mechanisms'.
   *
   * @return the errors; empty when the declaration is clean
   */
  public List<ConfigError> configErrors() {
    return m_errors;
  }

  /**
   * Drives every declared state pair once, times it, and persists the result.
   *
   * <p><b>Not built at this milestone.</b> {@code design/01} §8.8(b) schedules it for M14, together
   * with the {@link SafetyModel.Router} it depends on — the routine's fourth non-negotiable safety
   * gate is that <i>the router is not bypassed</i>, so a characterization pass that ran without one
   * would measure a set of transitions the match would never execute and write the numbers down as
   * if they were the real ones. That is worse than having no numbers: {@link CostSource#MEASURED}
   * would be a lie, and an auto budget built on it would be wrong in the direction that looks right.
   *
   * <p>So this returns a <b>named refusal</b> — visible in the scheduler and in AdvantageScope as
   * {@code "Superstructure.characterizeTransitions[REFUSED]"} — and raises a pit-only alert.
   * {@link Builder#transitionSeconds(Enum, Enum, double)} is the seam for numbers you measured by
   * hand in the meantime.
   *
   * @return the refusal command
   */
  public Command characterizeTransitions() {
    return refusal(
        "characterizeTransitions",
        "Superstructure.characterizeTransitions() is not built at this milestone. It is scheduled "
            + "for M14 alongside the SafetyModel router, because its fourth safety gate requires "
            + "that every measured transition be the routed, zone-respecting plan a match would "
            + "actually run. The command did nothing and every mechanism is holding. In the "
            + "meantime, hand-measured numbers can be declared with "
            + "Superstructure.Builder.transitionSeconds(from, to, seconds), which makes "
            + "plannedTransitionSource() report MEASURED honestly.");
  }

  /**
   * Recovers from "we booted inside a forbidden zone" by moving <b>only</b> the axis that most
   * quickly exits it.
   *
   * <p>The case this exists for is a mechanism that was moved by hand while disabled, or a homing
   * routine that seeded a wrong value: the current configuration is illegal, so every route out of
   * it is illegal too, and the ordinary planner correctly refuses everything. Moving one axis along
   * its shortest exit is the one move that is safe by construction, because it leaves the zone by
   * the nearest face.
   *
   * <p><b>Deviation from {@code design/01} §8.4(7), stated:</b> the design also asks for reduced
   * speed and an operator confirmation trigger. Speed scaling needs a motion-constraint override
   * that {@link GoalReceiver} does not expose at this milestone; bind this command to a held button
   * with {@code whileTrue} to get the confirmation half today.
   *
   * @return the command; it requires only this superstructure
   */
  public Command escapeCommand() {
    if (m_safety == null) {
      return refusal(
          "escape",
          "Superstructure.escapeCommand(): there is no SafetyModel on this superstructure, so there "
              + "are no forbidden zones and nothing to escape from. The command did nothing.");
    }
    return Commands.run(this::stepEscape, this)
        .until(() -> m_safety.isSafe(m_safety.measuredA(), m_safety.measuredB()))
        .withName(kName + ".escape");
  }

  // ===============================================================================================
  // Lifecycle
  // ===============================================================================================

  /**
   * The lifecycle hook's name, which is the telemetry name.
   *
   * @return {@link #kName}
   */
  @Override
  public String name() {
    return kName;
  }

  /**
   * Where this hook runs in the lifecycle order.
   *
   * @return {@link #kLifecyclePriority}
   */
  @Override
  public int priority() {
    return kLifecyclePriority;
  }

  /**
   * Claims the union requirement, once, after every component has been registered.
   *
   * <p>Registers <b>this</b> with the {@code CommandScheduler} and <b>unregisters every coordinated
   * mechanism</b>. That second half is the load-bearing one: a mechanism that is still registered
   * holds its own requirement, so a command that requires it can cancel an auto group that also
   * requires it — the exact failure this class exists to make unreachable. Run from
   * {@code RootstockLifecycle.init()}, which is after {@code RootstockRegistry.addAll(...)} has routed
   * every {@code Subsystem} to the scheduler, so it undoes the registration rather than racing it.
   */
  @Override
  public void init() {
    registerWithScheduler();
  }

  /**
   * Claims the union requirement immediately, for a team that does not use {@code RootstockRegistry}.
   *
   * <p>Idempotent. Call it once from {@code RobotContainer} after every mechanism is built.
   */
  public void registerWithScheduler() {
    CommandScheduler scheduler = CommandScheduler.getInstance();
    if (m_mechanisms.length > 0) {
      scheduler.unregisterSubsystem(m_mechanisms);
    }
    scheduler.registerSubsystem(this);
  }

  /**
   * Re-arms the on-entry latch so entry actions fire again on the next enable.
   *
   * <p>Called from {@code disabledInit}. {@code 0000-XXXX-Robot-Template} needs this for the climber
   * hold-position capture and it is easy to forget, so the registry calls it for you.
   */
  public void resetStateEntry() {
    m_previousActive = null;
  }

  /**
   * Re-arms the on-entry latch when the robot is disabled.
   *
   * <p>The registry route that makes {@link #resetStateEntry()} automatic.
   */
  @Override
  public void disabledInit() {
    resetStateEntry();
  }

  /**
   * One cycle of the state machine: plan from live measured state, advance on measured arrival,
   * apply the default to every axis and the active state's overrides on top, then drive every
   * coordinated mechanism.
   *
   * <p><b>Wrapped in {@code catch (Throwable)} on purpose.</b> This method is the one thing between
   * the scheduler and every mechanism on the robot; a bug in a team's {@code goals()} map or an
   * on-entry action must degrade to "everything neutral, one sticky alert, the drivetrain still
   * drives", not to a dead robot forty seconds into a match.
   */
  @Override
  public void periodic() {
    try {
      if (!m_primed) {
        // Prime the mechanisms' inputs before the first plan, or the first plan is computed from a
        // configuration of all zeroes and can refuse a move that was always legal.
        driveMechanisms();
        m_primed = true;
      }
      runCycle();
    } catch (Throwable t) {
      m_periodicFailed = true;
      m_periodicThrew
          .text(
              "Superstructure: periodic() threw "
                  + summarise(t)
                  + ". Every coordinated mechanism has been commanded neutral and the state machine "
                  + "is not running. Expected: periodic() never throws. Fix: the stack trace is in "
                  + "the riolog; the usual causes are a goals() map that returns null and an "
                  + "onEntry action that touches a field that is still null at boot.")
          .set(true);
      neutralAll();
    }
    try {
      driveMechanisms();
      publish();
    } catch (Throwable t) {
      // Publishing must not be able to take the loop down either.
      m_periodicFailed = true;
    }
  }

  private void runCycle() {
    boolean requestChanged = m_requested != m_lastRequested;
    m_lastRequested = m_requested;

    double a = measuredA();
    double b = measuredB();
    updateZoneViolation(a, b);

    // Re-plan when the request changed, when there is no plan, when the plan in flight is no longer
    // safe from where the robot actually is, when the plan does not end at what is being asked for,
    // and — the one that is easy to miss — EVERY loop while something is blocking. A blocked request
    // is retained precisely so that the move completes by itself the moment the interlock releases;
    // if the block short-circuited the re-plan, releasing it would never be noticed and the driver
    // would have to press the button again, which is the behaviour interlocks exist to avoid.
    if (requestChanged
        || m_blocked
        || m_plan.isEmpty()
        || m_plan.goalState() != m_requested
        || m_plan.isInvalidatedBy(a, b)) {
      Optional<Interlock<S>> blocking = firstBlockingInterlock(m_active, m_requested);
      if (blocking.isPresent()) {
        Interlock<S> lock = blocking.get();
        m_blocked = true;
        m_blockedText = lock.describe(m_active, m_requested);
        m_blockedReason = lock.explanation();
        m_blockedAlert
            .text(
                "Superstructure: "
                    + m_blockedText
                    + ". The request is RETAINED, so the move completes by itself the moment the "
                    + "interlock releases — nobody has to press the button again.")
            .set(true);
        // Hold the current state; do NOT half-execute.
        applyGoals(m_active, false);
        return;
      }
      m_blockedAlert.set(false);

      TransitionPlanner.Plan<S> planned =
          m_planner.plan(m_requested, a, b, targetA(m_requested), targetB(m_requested));
      if (planned.isRefused()) {
        m_blocked = true;
        m_blockedText = planned.refusalReason();
        m_blockedReason = planned.refusalReason();
        m_noRouteAlert.text(m_blockedText).set(true);
        applyGoals(m_active, false);
        return;
      }
      m_noRouteAlert.set(false);
      m_blocked = false;
      m_blockedText = "";
      m_blockedReason = "";
      m_plan = planned;
      m_planNames = m_plan.waypointNames();
    }

    if (currentWaypointSatisfied(a, b)) {
      if (m_plan.advance()) {
        m_planNames = m_plan.waypointNames();
      }
    }
    m_active = m_plan.goalState();

    boolean onEntry = m_active != m_previousActive;
    if (onEntry) {
      if (m_previousActive != null) {
        m_previous = m_previousActive;
      }
      m_stateEntrySeconds = Clock.seconds();
    }
    m_previousActive = m_active;

    applyGoals(m_active, onEntry);
  }

  /**
   * The §8.5 inversion, in three steps and in this order.
   *
   * <p>1. Every registered mechanism gets its declared default, every loop, unconditionally.<br>
   * 2. The active state overrides <b>only</b> what it names.<br>
   * 3. On-entry actions fire once per entry.
   *
   * <p>Step 1 is the one that matters. Without it, every state has to remember every actuator, and
   * "the rollers were still running because the previous state set them" is a bug that is invisible
   * in code review and obvious only on the field.
   */
  private void applyGoals(S state, boolean onEntry) {
    for (int i = 0; i < m_mechanisms.length; i++) {
      AxisGoal declaredDefault = m_defaults[i];
      if (declaredDefault != null) {
        declaredDefault.applyTo(m_receivers[i]);
      }
    }

    applyStateGoals(state, false);

    // Early release: while a transition is in flight, a mechanism whose predicate is already true is
    // commanded to the REQUESTED state's goal in parallel with the rest of the move.
    if (!m_blocked && m_requested != state) {
      applyStateGoals(m_requested, true);
    }

    if (onEntry) {
      Runnable action = m_onEntry.get(state);
      if (action != null) {
        try {
          action.run();
        } catch (Throwable t) {
          m_periodicThrew
              .text(
                  "Superstructure: the onEntry action for "
                      + state.name()
                      + " threw "
                      + summarise(t)
                      + ". The state was still entered and every other axis is being commanded "
                      + "normally. Fix: the action runs once per entry from periodic(), so it must "
                      + "not touch a field that is null at boot.")
              .set(true);
        }
      }
    }
  }

  private void applyStateGoals(S state, boolean earlyReleaseOnly) {
    Map<Mechanism, AxisGoal> goals = state.goals();
    if (goals == null || goals.isEmpty()) {
      return;
    }
    Map<Mechanism, BooleanSupplier> early = earlyReleaseOnly ? state.earlyRelease() : null;
    for (Map.Entry<Mechanism, AxisGoal> entry : goals.entrySet()) {
      Mechanism mechanism = entry.getKey();
      AxisGoal goal = entry.getValue();
      if (mechanism == null || goal == null) {
        continue;
      }
      if (earlyReleaseOnly) {
        BooleanSupplier predicate = early == null ? null : early.get(mechanism);
        if (predicate == null || !safeTest(predicate)) {
          continue;
        }
      }
      GoalReceiver receiver = receiverFor(mechanism);
      if (receiver == null) {
        continue;
      }
      goal.applyTo(receiver);
    }
  }

  private void driveMechanisms() {
    if (!m_drivesMechanismPeriodic) {
      return;
    }
    for (int i = 0; i < m_mechanisms.length; i++) {
      // Mechanism.periodic() is final and already wraps everything it does in catch (Throwable),
      // so one broken mechanism cannot stop the next one from running.
      m_mechanisms[i].periodic();
    }
  }

  private void neutralAll() {
    for (int i = 0; i < m_receivers.length; i++) {
      try {
        m_receivers[i].applyNeutral();
      } catch (Throwable ignored) {
        // Degrade, never crash: the next axis still gets its chance to go neutral.
      }
    }
  }

  private void stepEscape() {
    double a = m_safety.measuredA();
    double b = m_safety.measuredB();
    Optional<SafetyModel.Zone> violated = m_safety.violated(a, b);
    if (violated.isEmpty()) {
      return;
    }
    SafetyModel.Zone zone = violated.get();
    double marginA = Math.max(m_safety.marginA(), m_toleranceA);
    double marginB = Math.max(m_safety.marginB(), m_toleranceB);
    double exitALow = zone.axisA().min() - marginA;
    double exitAHigh = zone.axisA().max() + marginA;
    double exitBLow = zone.axisB().min() - marginB;
    double exitBHigh = zone.axisB().max() + marginB;
    double costA = Math.min(Math.abs(a - exitALow), Math.abs(exitAHigh - a));
    double costB = Math.min(Math.abs(b - exitBLow), Math.abs(exitBHigh - b));
    // Normalise by the zone's own width so "2 cm of elevator" and "2 degrees of arm" compare.
    double normalisedA = zone.axisA().width() > 0.0 ? costA / zone.axisA().width() : costA;
    double normalisedB = zone.axisB().width() > 0.0 ? costB / zone.axisB().width() : costB;
    if (normalisedB <= normalisedA) {
      GoalReceiver receiver = receiverFor(m_safety.axisB());
      if (receiver != null) {
        receiver.applyPosition(
            Math.abs(b - exitBLow) <= Math.abs(exitBHigh - b) ? exitBLow : exitBHigh);
      }
      return;
    }
    GoalReceiver receiver = receiverFor(m_safety.axisA());
    if (receiver != null) {
      receiver.applyPosition(Math.abs(a - exitALow) <= Math.abs(exitAHigh - a) ? exitALow : exitAHigh);
    }
  }

  // ===============================================================================================
  // TelemetrySource
  // ===============================================================================================

  /**
   * The literal {@code "Superstructure"}.
   *
   * <p>Not a constructor argument, and that is a deliberate, bounded choice: {@code design/04}
   * hard-codes this segment in its own key literals, so a superstructure that named itself anything
   * else would publish into a namespace nothing reads. <b>Limitation, stated:</b> exactly one
   * superstructure per robot. Two collide on the name, which registration reports as a fatal
   * configuration error — a loud failure rather than a silent overwrite, which is the right failure
   * mode, but it is still a ceiling.
   *
   * @return {@link #kName}
   */
  @Override
  public String telemetryName() {
    return kName;
  }

  /**
   * Declares the state enum and every extra key this superstructure publishes, exactly once, at
   * registration.
   *
   * <p>No {@code positionUnit} or {@code velocityUnit}: a superstructure has no single axis, and
   * both are optional on the descriptor.
   *
   * @param d the descriptor, valid only for the duration of this call
   */
  @Override
  public void describe(TelemetryDescriptor d) {
    if (d == null) {
      return;
    }
    d.states(m_stateType);
    // Unit-free: a String is not dimensionless, it is dimensionless-LESS, and the two-argument
    // overload is what says so.
    d.extra("Requested", Tier.STANDARD);
    d.extra("Active", Tier.STANDARD);
    d.extra("Previous", Tier.STANDARD);
    d.extra("Transitioning", Tier.STANDARD);
    d.extra("AtState", Tier.STANDARD);
    d.extra("Plan", Tier.STANDARD);
    d.extra("Blocked", Tier.STANDARD);
    d.extra("BlockedReason", Tier.STANDARD);
    d.extra("SafeZoneViolation", Tier.STANDARD);
    d.extra("PlannedSource", Tier.STANDARD);
    d.extra("Synchronized", Tier.STANDARD);
    // These two are measurements, so they take the three-argument form and get the unit metadata
    // AdvantageScope reads for axis labelling.
    d.extra("TimeInStateSec", Seconds, Tier.STANDARD);
    d.extra("PlannedSeconds", Seconds, Tier.STANDARD);
  }

  private void publish() {
    RootstockLog.critical(kStateKey, m_active);
    RootstockLog.log(kRequestedKey, m_requested.name());
    RootstockLog.log(kActiveKey, m_active.name());
    RootstockLog.log(kPreviousKey, m_previous.name());
    RootstockLog.log(kTransitioningKey, isTransitioning());
    RootstockLog.log(kAtStateKey, atState());
    RootstockLog.log(kPlanKey, m_planNames);
    RootstockLog.log(kBlockedKey, m_blockedText);
    RootstockLog.log(kBlockedReasonKey, m_blockedReason);
    RootstockLog.log(kSafeZoneViolationKey, m_zoneViolation);
    RootstockLog.log(kTimeInStateKey, timeInStateSeconds(), Seconds);
    RootstockLog.log(kPlannedSecondsKey, plannedTransitionSeconds(), Seconds);
    RootstockLog.log(kPlannedSourceKey, plannedTransitionSource(m_previous, m_requested).name());
    RootstockLog.log(kSynchronizedKey, m_safety == null || m_safety.synchronizedAxes());
  }

  // ===============================================================================================
  // Introspection
  // ===============================================================================================

  /**
   * The mechanisms this superstructure coordinates, in registration order.
   *
   * @return the mechanisms; a fresh array, so the caller cannot reorder ours
   */
  public Mechanism[] mechanisms() {
    return m_mechanisms.clone();
  }

  /**
   * The declared transition rules, in declaration order.
   *
   * @return the interlocks; never null
   */
  public List<Interlock<S>> interlocks() {
    return m_interlocks;
  }

  /**
   * The collision model, if there is one.
   *
   * @return the model, or empty
   */
  public Optional<SafetyModel> safetyModel() {
    return Optional.ofNullable(m_safety);
  }

  /**
   * The whole state machine, printed so a team can read it without reading Java.
   *
   * @return a multi-line description: the axes and their defaults, the interlocks, the zones and the
   *     report
   */
  public String describe() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(768);
    sb.append("Superstructure over ")
        .append(m_stateType.getSimpleName())
        .append(" (idle = ")
        .append(m_idle.name())
        .append(", ")
        .append(m_allStates.length)
        .append(" state(s), ")
        .append(m_mechanisms.length)
        .append(" axis/axes)")
        .append(nl);
    for (int i = 0; i < m_mechanisms.length; i++) {
      sb.append("  axis ")
          .append(m_receivers[i].describe())
          .append(" default = ")
          .append(m_defaults[i] == null ? "NONE (outside the §8.5 inversion)" : m_defaults[i].describe())
          .append(nl);
    }
    for (Interlock<S> interlock : m_interlocks) {
      sb.append("  interlock ").append(interlock.describe()).append(nl);
    }
    if (m_safety != null) {
      sb.append(m_safety.describe());
    }
    sb.append(m_report.describe());
    return sb.toString();
  }

  @Override
  public String toString() {
    return kName
        + "["
        + m_active.name()
        + (m_active == m_requested ? "" : " -> " + m_requested.name())
        + (m_blocked ? " BLOCKED" : "")
        + "]";
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  /**
   * Recomputes {@code SafeZoneViolation} without allocating on the 50 Hz path.
   *
   * <p>Walks {@link SafetyModel#zones()} by index rather than calling
   * {@link SafetyModel#violated(double, double)}, because that returns an {@code Optional} and its
   * {@code describe()} builds a string — one of each, every loop, for a value that changes perhaps
   * twice a match. The rendered text is rebuilt only when the violating zone actually changes.
   */
  private void updateZoneViolation(double a, double b) {
    SafetyModel.Zone found = null;
    if (m_safety != null && !Double.isNaN(a) && !Double.isNaN(b)) {
      List<SafetyModel.Zone> zones = m_safety.zones();
      for (int i = 0; i < zones.size(); i++) {
        SafetyModel.Zone zone = zones.get(i);
        if (zone.contains(a, b) && zone.isActive()) {
          found = zone;
          break;
        }
      }
    }
    if (found != m_lastViolation) {
      m_lastViolation = found;
      m_zoneViolation = found == null ? "" : found.describe();
    }
  }

  private GoalReceiver receiverFor(Mechanism mechanism) {
    Integer index = m_indexOf.get(mechanism);
    if (index == null) {
      m_misuse
          .text(
              "Superstructure: a state asserts a goal for "
                  + mechanism.name()
                  + ", which this superstructure does not coordinate, so the goal was IGNORED. "
                  + "Expected: every mechanism named by a state is passed to the builder. Fix: add "
                  + "Superstructure.Builder.defaultFor("
                  + mechanism.name()
                  + ", receiver, AxisGoal.neutral()) — the default is what the §8.5 inversion needs "
                  + "and registering it is what makes the goal take effect.")
          .set(true);
      return null;
    }
    return m_receivers[index];
  }

  private Optional<Interlock<S>> firstBlockingInterlock(S from, S to) {
    if (from == to) {
      return Optional.empty();
    }
    for (int i = 0; i < m_interlocks.size(); i++) {
      Interlock<S> interlock = m_interlocks.get(i);
      if (interlock.blocks(from, to)) {
        return Optional.of(interlock);
      }
    }
    return Optional.empty();
  }

  private boolean currentWaypointSatisfied(double a, double b) {
    if (m_plan.isEmpty()) {
      return false;
    }
    if (m_plan.currentWaypoint() == null) {
      return assertedGoalsReached(m_plan.goalState());
    }
    return m_plan.currentWaypointReached(a, b, m_toleranceA, m_toleranceB);
  }

  private boolean assertedGoalsReached(S state) {
    Map<Mechanism, AxisGoal> goals = state.goals();
    if (goals == null || goals.isEmpty()) {
      return true;
    }
    for (Map.Entry<Mechanism, AxisGoal> entry : goals.entrySet()) {
      Integer index = m_indexOf.get(entry.getKey());
      if (index == null) {
        continue;
      }
      if (!m_receivers[index].atGoal()) {
        return false;
      }
    }
    return true;
  }

  private double measuredA() {
    return m_safety == null ? Double.NaN : m_safety.measuredA();
  }

  private double measuredB() {
    return m_safety == null ? Double.NaN : m_safety.measuredB();
  }

  private double targetA(S state) {
    return m_safety == null ? Double.NaN : targetFor(state, m_safety.axisA(), measuredA());
  }

  private double targetB(S state) {
    return m_safety == null ? Double.NaN : targetFor(state, m_safety.axisB(), measuredB());
  }

  private double targetFor(S state, Mechanism axis, double fallback) {
    Integer index = m_indexOf.get(axis);
    if (index == null) {
      return fallback;
    }
    GoalReceiver receiver = m_receivers[index];
    Map<Mechanism, AxisGoal> goals;
    try {
      goals = state.goals();
    } catch (Throwable t) {
      // A goals() that throws is already reported as a FATAL ConfigError by validateSetpointNames.
      return fallback;
    }
    AxisGoal goal = goals == null ? null : goals.get(axis);
    if (goal == null) {
      goal = m_defaults[index];
    }
    if (goal == null) {
      return fallback;
    }
    OptionalDouble target = goal.targetUser(receiver);
    // A percent, velocity, neutral or hold goal names no position, and pretending it names the
    // current one is the honest answer: that axis is not being sent anywhere by this state.
    return target.isPresent() ? target.getAsDouble() : fallback;
  }

  private static boolean safeTest(BooleanSupplier predicate) {
    try {
      return predicate.getAsBoolean();
    } catch (Throwable t) {
      return false;
    }
  }

  private Command refusal(String verb, String explanation) {
    // One alert instance, reused. Minting a RootstockAlert per call would register a new alert every
    // time a button was pressed, and the driver display has a cap that a leak like that would eat.
    return Commands.none()
        .beforeStarting(() -> m_refused.text(explanation).set(true))
        .withName(kName + "." + verb + "[REFUSED]");
  }

  private static String summarise(Throwable t) {
    String message = t.getMessage();
    return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }

  // ===============================================================================================
  // Static analysis (§8.8) and setpoint validation (§8.9)
  // ===============================================================================================

  private SuperstructureReport analyse(List<ConfigError> errors) {
    List<String> unresolved = validateSetpointNames(errors);
    List<String> noDefault = new ArrayList<>();
    for (int i = 0; i < m_mechanisms.length; i++) {
      if (m_defaults[i] == null) {
        noDefault.add(
            m_mechanisms[i].name()
                + " has no declared default, so a state that does not name it leaves it wherever "
                + "the previous state put it. Fix: Superstructure.Builder.defaultFor("
                + m_mechanisms[i].name()
                + ", AxisGoal.neutral()).");
      }
    }

    int n = m_allStates.length;
    boolean[][] edge = new boolean[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        edge[i][j] = i == j || !staticallyBlocked(m_allStates[i], m_allStates[j]);
      }
    }
    int idleIndex = indexOfState(m_idle);
    boolean[] reachable = reach(edge, idleIndex, false);
    boolean[] canStow = reach(edge, idleIndex, true);

    List<String> unreachable = new ArrayList<>();
    List<String> stranded = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (i == idleIndex) {
        continue;
      }
      if (!reachable[i]) {
        unreachable.add(
            m_allStates[i].name()
                + " cannot be reached from "
                + m_idle.name()
                + " by any sequence of permitted transitions. Expected: every declared state to be "
                + "reachable. This is usually an interlock whose condition is never true, or a "
                + "state constant nothing ever requests.");
      }
      if (!canStow[i]) {
        stranded.add(
            m_idle.name()
                + " is NOT reachable from "
                + m_allStates[i].name()
                + ". A robot that can enter this state is a robot that cannot stow. Expected: every "
                + "state to have a path back to idle. Fix: find the interlock that blocks the way "
                + "out and give it an escape — an interlock on 'to' that never permits idle is the "
                + "usual cause.");
      }
    }

    List<String> crossing = new ArrayList<>();
    List<String> unroutable = new ArrayList<>();
    if (m_safety != null) {
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          if (i == j || !edge[i][j]) {
            continue;
          }
          S from = m_allStates[i];
          S to = m_allStates[j];
          double a0 = targetFor(from, m_safety.axisA(), Double.NaN);
          double b0 = targetFor(from, m_safety.axisB(), Double.NaN);
          double a1 = targetFor(to, m_safety.axisA(), Double.NaN);
          double b1 = targetFor(to, m_safety.axisB(), Double.NaN);
          if (Double.isNaN(a0) || Double.isNaN(b0) || Double.isNaN(a1) || Double.isNaN(b1)) {
            continue;
          }
          Optional<SafetyModel.Zone> zone = m_safety.crossing(a0, b0, a1, b1);
          if (zone.isEmpty()) {
            continue;
          }
          crossing.add(
              from.name()
                  + " -> "
                  + to.name()
                  + " crosses "
                  + zone.get().name()
                  + " (\""
                  + zone.get().why()
                  + "\")"
                  + (m_safety.routerInstalled()
                      ? ""
                      : ". No router is installed, so this move will be REFUSED at runtime."));
          if (m_safety.routerInstalled() && m_safety.route(a0, b0, a1, b1).isEmpty()) {
            unroutable.add(
                from.name()
                    + " -> "
                    + to.name()
                    + " cannot be routed around "
                    + zone.get().name()
                    + " within the "
                    + SafetyModel.kMaxWaypoints
                    + "-waypoint cap. It will REFUSE at runtime. Fix: declare a corridor that "
                    + "covers the span, or add an intermediate state that steps around the zone.");
          }
        }
      }
    }

    for (String line : stranded) {
      errors.add(
          ConfigError.of(ConfigError.Severity.FATAL, kName, "states.pathToIdle", line));
    }
    for (String line : unroutable) {
      errors.add(ConfigError.of(ConfigError.Severity.FATAL, kName, "transitions.route", line));
    }
    if (m_safety != null) {
      for (String problem : m_safety.problems()) {
        errors.add(ConfigError.of(ConfigError.Severity.WARNING, kName, "safety", problem));
      }
    }

    return new SuperstructureReport(unreachable, stranded, crossing, unroutable, noDefault, unresolved);
  }

  /**
   * {@code design/01} §8.9 layer 2: every string, validated at construction, with a suggestion.
   *
   * <p>Both forms are checked, and for the same reason. {@link AxisGoal.Named} is the obvious one.
   * {@link AxisGoal.Of} is checked too because {@code PositionConfig.setpoint("L4 ")} does not throw
   * — it returns an <i>unresolved</i> {@code Setpoint}, precisely so that a typo in a
   * {@code public static final} field does not become an {@code ExceptionInInitializerError} — so
   * the typed form carries exactly the same failure, and it must be reported in exactly the same
   * place.
   */
  private List<String> validateSetpointNames(List<ConfigError> errors) {
    List<String> messages = new ArrayList<>();
    for (S state : m_allStates) {
      Map<Mechanism, AxisGoal> goals;
      try {
        goals = state.goals();
      } catch (Throwable t) {
        errors.add(
            ConfigError.of(
                ConfigError.Severity.FATAL,
                kName,
                "state." + state.name() + ".goals",
                state.name()
                    + ".goals() threw "
                    + summarise(t)
                    + " while the superstructure was being validated. Expected: goals() returns a "
                    + "map built once in the enum's constructor. Fix: build the map in the "
                    + "constructor and return the field — a goals() that touches a RobotContainer "
                    + "field is a goals() that runs before that field is assigned."));
        continue;
      }
      if (goals == null) {
        continue;
      }
      for (Map.Entry<Mechanism, AxisGoal> entry : goals.entrySet()) {
        Mechanism mechanism = entry.getKey();
        AxisGoal goal = entry.getValue();
        if (mechanism == null || goal == null) {
          continue;
        }
        Integer index = m_indexOf.get(mechanism);
        if (index == null) {
          errors.add(
              ConfigError.of(
                  ConfigError.Severity.FATAL,
                  kName,
                  "state." + state.name(),
                  state.name()
                      + " asserts a goal for "
                      + mechanism.name()
                      + ", which this superstructure does not coordinate. The goal would be "
                      + "silently ignored at runtime, which is the one thing this library will not "
                      + "do. Fix: pass "
                      + mechanism.name()
                      + " to the builder — Superstructure.Builder.defaultFor("
                      + mechanism.name()
                      + ", receiver, AxisGoal.neutral())."));
          continue;
        }
        String referenced = goal.setpointName();
        if (referenced.isEmpty() && !goal.referencesUnresolvedHandle()) {
          continue;
        }
        GoalReceiver receiver = m_receivers[index];
        List<Setpoint> declared = receiver.declaredSetpoints();
        if (declared.isEmpty() && !goal.referencesUnresolvedHandle()) {
          // The axis does not publish its setpoint list, so there is nothing to check against.
          // Reporting every name as a typo here would fail a working robot into SAFE_MODE.
          continue;
        }
        if (resolves(declared, referenced) && !goal.referencesUnresolvedHandle()) {
          continue;
        }
        String message = unresolvedMessage(state, mechanism, referenced, declared, index);
        messages.add(message);
        errors.add(
            ConfigError.of(
                ConfigError.Severity.FATAL,
                kName,
                "state." + state.name() + "." + mechanism.name(),
                message));
      }
    }
    return messages;
  }

  private static boolean resolves(List<Setpoint> declared, String name) {
    for (int i = 0; i < declared.size(); i++) {
      Setpoint candidate = declared.get(i);
      if (candidate != null && candidate.isResolved() && candidate.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  private String unresolvedMessage(
      S state, Mechanism mechanism, String referenced, List<Setpoint> declared, int index) {
    StringBuilder sb = new StringBuilder(480);
    sb.append(m_stateType.getSimpleName())
        .append('.')
        .append(state.name())
        .append(" references ")
        .append(mechanism.name())
        .append(" setpoint \"")
        .append(referenced)
        .append("\" which does not exist. ")
        .append(mechanism.name())
        .append(" declares: ")
        .append(nameList(declared))
        .append('.');

    String suggestion = closest(referenced, declared);
    if (!suggestion.isEmpty()) {
      int distance = editDistance(referenced, suggestion);
      sb.append(" Did you mean \"").append(suggestion).append("\"? (edit distance ").append(distance);
      if (!referenced.equals(referenced.strip())) {
        sb.append(" — leading or trailing whitespace");
      }
      sb.append(") ");
    }

    // The sibling search: "I put the arm's setpoint name on the elevator" is the most common form
    // of this mistake, and naming the sibling turns a hunt into a one-line fix.
    for (int i = 0; i < m_mechanisms.length; i++) {
      if (i == index) {
        continue;
      }
      if (resolves(m_receivers[i].declaredSetpoints(), referenced)) {
        sb.append(" (Did you mean a setpoint on ")
            .append(m_mechanisms[i].name())
            .append("? ")
            .append(m_mechanisms[i].name())
            .append(" declares: ")
            .append(nameList(m_receivers[i].declaredSetpoints()))
            .append(".)");
        break;
      }
    }

    sb.append(
        " Prefer the typed form, which the compiler checks: public static final Setpoint "
            + "MECHANISM_GOAL = RobotConfig.MECHANISM.setpoint(\"NAME\"); then "
            + "AxisGoal.of(RobotConfig.MECHANISM_GOAL).");
    return sb.toString();
  }

  private static String nameList(List<Setpoint> declared) {
    if (declared.isEmpty()) {
      return "(none)";
    }
    StringBuilder sb = new StringBuilder(64);
    for (int i = 0; i < declared.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(declared.get(i) == null ? "?" : declared.get(i).name());
    }
    return sb.toString();
  }

  private static String closest(String wanted, List<Setpoint> declared) {
    String best = "";
    int bestDistance = Integer.MAX_VALUE;
    for (int i = 0; i < declared.size(); i++) {
      Setpoint candidate = declared.get(i);
      if (candidate == null) {
        continue;
      }
      int distance = editDistance(wanted, candidate.name());
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate.name();
      }
    }
    return bestDistance <= kMaxSuggestionDistance ? best : "";
  }

  /** Levenshtein distance, two rows, no allocation beyond them. */
  private static int editDistance(String a, String b) {
    if (a == null || b == null) {
      return Integer.MAX_VALUE;
    }
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  /**
   * Whether an interlock <b>permanently</b> blocks an edge, for reachability analysis only.
   *
   * <p>A condition that throws while the analysis runs counts as <b>permitted</b>, which is the
   * opposite of the runtime rule and is deliberate. At construction a team's supplier routinely
   * touches a {@code RobotContainer} field that is still being assigned, and treating that as
   * "blocked" would report half the state machine as unreachable and fail a working robot into
   * SAFE_MODE. At runtime the same throw means "I cannot verify this is safe", and there the safe
   * answer is to block. The diagnostic must never become the outage.
   */
  private boolean staticallyBlocked(S from, S to) {
    for (int i = 0; i < m_interlocks.size(); i++) {
      Interlock<S> interlock = m_interlocks.get(i);
      if (!interlock.applies(from, to)) {
        continue;
      }
      boolean permitted;
      try {
        permitted = interlock.permitted().getAsBoolean();
      } catch (Throwable t) {
        permitted = true;
      }
      if (!permitted) {
        return true;
      }
    }
    return false;
  }

  private boolean[] reach(boolean[][] edge, int start, boolean reverse) {
    int n = edge.length;
    boolean[] seen = new boolean[n];
    if (start < 0 || n == 0) {
      return seen;
    }
    int[] queue = new int[n];
    int head = 0;
    int tail = 0;
    seen[start] = true;
    queue[tail++] = start;
    while (head < tail) {
      int at = queue[head++];
      for (int next = 0; next < n; next++) {
        boolean connected = reverse ? edge[next][at] : edge[at][next];
        if (connected && !seen[next]) {
          seen[next] = true;
          queue[tail++] = next;
        }
      }
    }
    return seen;
  }

  private int indexOfState(S state) {
    for (int i = 0; i < m_allStates.length; i++) {
      if (m_allStates[i] == state) {
        return i;
      }
    }
    return -1;
  }

  private void raiseFindings() {
    if (m_report.clean()) {
      return;
    }
    if (!m_report.unreachableStates().isEmpty()
        || !m_report.transitionsCrossingAZone().isEmpty()
        || !m_report.axesWithNoDeclaredDefault().isEmpty()) {
      Alerts.warning(kName, kName + "/report", MatchImpact.PIT_ONLY)
          .sticky(true)
          .text("Superstructure report: " + m_report.findingCount() + " finding(s). " + m_report.describe())
          .set(true);
    }
  }
}

package org.rootstock.superstructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import org.rootstock.config.Setpoint;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.mechanism.Mechanism;
import org.rootstock.mechanism.PositionMechanism;
import org.rootstock.mechanism.SimpleMechanism;
import org.rootstock.mechanism.VelocityMechanism;

/**
 * The one seam between an {@link AxisGoal} and the mechanism that has to honour it.
 *
 * <h2>Why this interface exists — read this before adding a method to it</h2>
 *
 * <p>{@code design/01} §8.1 writes {@code AxisGoal.applyTo(mech)} against a mechanism that can be
 * told "go to 52.5 inches", "hold", "run at 60 %" and "what setpoints do you declare?". The
 * {@link Mechanism} base class deliberately declares none of those: it owns identity, telemetry,
 * health, self-test and the periodic wrapper, and it ships <b>no</b> command surface, because the
 * command surface belongs to the concrete position / velocity / simple subclasses. So the
 * superstructure cannot name a type that has the four operations it needs without either depending
 * on one particular subclass or inventing this seam.
 *
 * <p>It invents this seam. A {@code GoalReceiver} is one axis's answer to the five verbs a state
 * machine can utter, plus the two questions it has to ask (has it arrived, and where is it). A
 * concrete mechanism may implement it directly; anything else is bound with
 * {@link #adapting(Mechanism)} in one expression at the call site where the mechanism is built.
 *
 * <p><b>Every method must be safe to call every loop, and none of them may throw.</b> They are
 * invoked from the superstructure's {@code periodic()} once per registered axis per cycle — twice,
 * in fact, because the §8.5 inversion applies the default first and the state's override second.
 * Setting a goal field is the whole of a correct implementation; running a profile is the
 * mechanism's own {@code periodic()}, not this.
 *
 * @see AxisGoal
 * @see Superstructure.Builder#defaultFor(Mechanism, GoalReceiver, AxisGoal)
 */
public interface GoalReceiver {

  /** The alert group every {@code GoalReceiver} diagnostic is filed under. */
  String kAlertGroup = "Superstructure";

  /**
   * The nominal battery voltage the degraded {@link #forMechanism(Mechanism)} adapter multiplies a
   * duty cycle by, because {@link Mechanism} exposes volts and not duty cycle.
   */
  double kNominalVolts = 12.0;

  /**
   * This axis's name, which is the mechanism's name and therefore the name every alert, report line
   * and error message uses for it.
   *
   * @return the name; never null and never blank
   */
  String axisName();

  /**
   * Command a position, in this axis's <b>user</b> units — metres for a linear axis, degrees for a
   * rotary one, exactly as {@code Setpoint.valueUser()} reports them.
   *
   * @param userUnits the goal position in user units
   */
  void applyPosition(double userUnits);

  /**
   * Command a velocity, in this axis's user units per second.
   *
   * @param userPerSecond the goal velocity
   */
  void applyVelocity(double userPerSecond);

  /**
   * Command an open-loop duty cycle in {@code [-1, 1]} — the roller/intake verb.
   *
   * @param dutyCycle the duty cycle
   */
  void applyPercent(double dutyCycle);

  /**
   * Command neutral: coast or brake per the mechanism's configured neutral mode, no output.
   *
   * <p>This is the default a flywheel or an intake wants, and it is the state the superstructure
   * drives everything to when it refuses a move.
   */
  void applyNeutral();

  /**
   * Hold whatever the mechanism is currently doing — assert nothing.
   *
   * <p>The one goal that is <i>not</i> an assertion, and therefore the one that opts an axis out of
   * the §8.5 inversion for as long as it is in force. Used for an axis a driver is controlling by
   * hand while the rest of the superstructure runs a state machine.
   */
  void applyHold();

  /**
   * Whether this axis has arrived at the goal it was last given.
   *
   * <p>The waypoint gate. {@code design/01} §8.2 is emphatic that this is a <b>measured</b> question
   * and never a timer: a time-based version of the same sequence is both slower and unsafe when the
   * mechanism is loaded or cold.
   *
   * @return true when the axis is at its goal within the mechanism's own tolerance
   */
  boolean atGoal();

  /**
   * The axis's measured position in user units — what the {@link SafetyModel} tests and what the
   * planner plans from.
   *
   * @return the measurement in metres or degrees, or {@code NaN} when the axis cannot report one
   */
  double measuredUser();

  /**
   * Every setpoint this axis declares, which is what {@code design/01} §8.9 validates
   * {@link AxisGoal.Named} against at construction.
   *
   * <p>An <b>empty</b> list means "this axis does not publish its setpoint list", not "this axis has
   * no setpoints". The superstructure therefore treats empty as <i>cannot validate</i> and says so
   * in {@link SuperstructureReport}, rather than declaring every name a typo — failing a robot into
   * SAFE_MODE because an adapter did not fill in an optional list would be the diagnostic becoming
   * the outage.
   *
   * @return the declared setpoints, newest declaration order; never null
   */
  List<Setpoint> declaredSetpoints();

  /**
   * A declared setpoint by name, or empty.
   *
   * <p>Returns {@code Optional} rather than a nullable {@code Setpoint} for the reason
   * {@code design/01} §8.9 layer 3 gives: the caller cannot ignore the miss by accident.
   *
   * @param name the setpoint name, compared exactly — no trimming, because {@code "L4 "} is not
   *     {@code "L4"} and pretending otherwise is what makes the typo invisible
   * @return the setpoint, or empty when this axis declares no such name
   */
  default Optional<Setpoint> setpoint(String name) {
    List<Setpoint> declared = declaredSetpoints();
    for (int i = 0; i < declared.size(); i++) {
      Setpoint candidate = declared.get(i);
      if (candidate != null && candidate.name().equals(name)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * A one-line description of what this axis can be told, for {@link SuperstructureReport}.
   *
   * @return the description
   */
  default String describe() {
    return axisName() + " (" + declaredSetpoints().size() + " declared setpoint(s))";
  }

  /**
   * The receiver for a mechanism, chosen by what that mechanism actually is.
   *
   * <p>This is what makes {@code Superstructure.Builder.defaultFor(elevator, AxisGoal.of(STOW))}
   * work with no receiver wiring at all, which is the shape {@code design/01} §8.1 writes. The three
   * concrete mechanism types are bound to their own command surfaces:
   *
   * <ul>
   *   <li>{@code PositionMechanism} — position goals go to {@code setGoal(double)}, percent to
   *       {@code setDutyCycle}, neutral to {@code setNeutral}, arrival to {@code atGoal()}, and its
   *       <b>declared setpoints are published</b>, which is what lets {@code design/01} §8.9's
   *       construction-time name check actually check something.
   *   <li>{@code VelocityMechanism} — velocity goals go to {@code setGoal(double)}, neutral to
   *       {@code setNeutral}, arrival to {@code atGoal()}.
   *   <li>{@code SimpleMechanism} — percent goes to {@code set(double)}, neutral to {@code set(0)},
   *       and it always reports arrival, because a roller has nowhere to arrive.
   * </ul>
   *
   * <p>{@link #applyHold()} is a deliberate no-op for every one of them. It means "assert nothing",
   * and calling {@code holdPosition()} every loop would latch the measurement as the new goal each
   * cycle and let a gravity-loaded axis ratchet downwards one tolerance at a time.
   *
   * <p>Anything else falls back to the <b>degraded</b> adapter: {@code applyNeutral()} calls
   * {@code stop()}, {@code applyPercent} maps the duty cycle onto {@code setVoltage(duty * 12)},
   * {@code measuredUser()} converts {@code measuredSi()} through the mechanism's own units — and a
   * position or velocity goal raises a {@link MatchImpact#PIT_ONLY} alert naming the axis and the
   * goal and commands nothing. <b>Never</b> a silent no-op, and never an exception out of the loop.
   *
   * @param mechanism the mechanism to wrap
   * @return the receiver
   * @throws NullPointerException if {@code mechanism} is null
   */
  static GoalReceiver forMechanism(Mechanism mechanism) {
    Objects.requireNonNull(
        mechanism,
        "GoalReceiver.forMechanism: the mechanism must not be null. A superstructure axis with no "
            + "mechanism behind it can never arrive, so there is no useful degraded behaviour.");
    if (mechanism instanceof PositionMechanism position) {
      return adapting(position)
          .position(position::setGoal)
          .percent(position::setDutyCycle)
          .neutral(position::setNeutral)
          .atGoal(position::atGoal)
          .measured(position::measured)
          .setpoints(declaredSetpointsOf(position))
          .build();
    }
    if (mechanism instanceof VelocityMechanism velocity) {
      return adapting(velocity)
          .velocity(velocity::setGoal)
          .neutral(velocity::setNeutral)
          .atGoal(velocity::atGoal)
          .measured(velocity::measured)
          .build();
    }
    if (mechanism instanceof SimpleMechanism simple) {
      return adapting(simple)
          .percent(simple::set)
          .neutral(() -> simple.set(0.0))
          .atGoal(() -> true)
          .build();
    }
    return adapting(mechanism).build();
  }

  /**
   * A position mechanism's declared setpoints, as objects rather than as names, so
   * {@code design/01} §8.9's check can compare values as well as spelling.
   *
   * @param mechanism the mechanism
   * @return its setpoints, in declaration order
   */
  private static List<Setpoint> declaredSetpointsOf(PositionMechanism mechanism) {
    List<String> names = mechanism.setpointNames();
    List<Setpoint> out = new ArrayList<>(names.size());
    for (int i = 0; i < names.size(); i++) {
      mechanism.setpoint(names.get(i)).ifPresent(out::add);
    }
    return List.copyOf(out);
  }

  /**
   * Binds a mechanism's own command surface to the five superstructure verbs, in one expression.
   *
   * <pre>{@code
   * GoalReceiver.adapting(elevator)
   *     .position(elevator::setGoalInches)
   *     .percent(elevator::setOpenLoop)
   *     .neutral(elevator::stop)
   *     .atGoal(elevator::atGoal)
   *     .measured(() -> elevator.heightInches())
   *     .setpoints(RobotConfig.ELEVATOR.setpoints())
   *     .build();
   * }</pre>
   *
   * <p>Anything left unbound is a refusal, not a crash: the goal that needs it raises a
   * {@link MatchImpact#PIT_ONLY} alert naming the axis and the verb, and the axis holds.
   *
   * @param mechanism the mechanism this axis is
   * @return a builder
   * @throws NullPointerException if {@code mechanism} is null
   */
  static Builder adapting(Mechanism mechanism) {
    return new Builder(
        Objects.requireNonNull(
            mechanism,
            "GoalReceiver.adapting: the mechanism must not be null. It supplies the axis name, the "
                + "unit conversion and the fallback behaviour, so there is nothing to adapt "
                + "without it."));
  }

  /**
   * Assembles an {@link Adapter}.
   *
   * <p>A class rather than an interface, and its constructor is package-private, so there is exactly
   * one way to reach it — {@link GoalReceiver#adapting(Mechanism)} — and the mechanism argument
   * cannot be skipped. (A member type of an interface is implicitly {@code public static} and cannot
   * be declared private, which is why the <i>constructor</i> carries the restriction rather than the
   * type; the same shape {@code GoalBus.CommandMapBus} uses.)
   */
  final class Builder {

    private final Mechanism m_mechanism;
    private DoubleConsumer m_position;
    private DoubleConsumer m_velocity;
    private DoubleConsumer m_percent;
    private Runnable m_neutral;
    private Runnable m_hold;
    private BooleanSupplier m_atGoal;
    private DoubleSupplier m_measured;
    private List<Setpoint> m_setpoints = List.of();

    Builder(Mechanism mechanism) {
      m_mechanism = mechanism;
    }

    /**
     * How this axis is told to go to a position, in user units.
     *
     * @param applyUserUnits the sink
     * @return this builder
     */
    public Builder position(DoubleConsumer applyUserUnits) {
      m_position = applyUserUnits;
      return this;
    }

    /**
     * How this axis is told to run at a velocity, in user units per second.
     *
     * @param applyUserPerSecond the sink
     * @return this builder
     */
    public Builder velocity(DoubleConsumer applyUserPerSecond) {
      m_velocity = applyUserPerSecond;
      return this;
    }

    /**
     * How this axis is told to run open loop at a duty cycle in {@code [-1, 1]}.
     *
     * @param applyDutyCycle the sink
     * @return this builder
     */
    public Builder percent(DoubleConsumer applyDutyCycle) {
      m_percent = applyDutyCycle;
      return this;
    }

    /**
     * How this axis is told to go neutral. Defaults to {@code Mechanism.stop()}.
     *
     * @param goNeutral the action
     * @return this builder
     */
    public Builder neutral(Runnable goNeutral) {
      m_neutral = goNeutral;
      return this;
    }

    /**
     * How this axis is told to hold. Defaults to doing nothing, which is what "hold" means for a
     * mechanism that is already holding its own goal.
     *
     * @param hold the action
     * @return this builder
     */
    public Builder hold(Runnable hold) {
      m_hold = hold;
      return this;
    }

    /**
     * How this axis reports arrival. Defaults to "always arrived", which makes an unbound axis
     * transparent to the waypoint gate rather than deadlocking every transition on it.
     *
     * @param atGoal the predicate
     * @return this builder
     */
    public Builder atGoal(BooleanSupplier atGoal) {
      m_atGoal = atGoal;
      return this;
    }

    /**
     * How this axis reports its measured position in user units. Defaults to converting
     * {@code Mechanism.measuredSi()} through the mechanism's own units, which is right for every
     * mechanism that implements {@code measuredSi()} honestly.
     *
     * @param measuredUser the supplier
     * @return this builder
     */
    public Builder measured(DoubleSupplier measuredUser) {
      m_measured = measuredUser;
      return this;
    }

    /**
     * The setpoints this axis declares, so {@link AxisGoal.Named} can be validated at construction
     * instead of at button-press time ({@code design/01} §8.9).
     *
     * @param setpoints the declared setpoints; null becomes empty
     * @return this builder
     */
    public Builder setpoints(List<Setpoint> setpoints) {
      m_setpoints = setpoints == null ? List.of() : List.copyOf(setpoints);
      return this;
    }

    /**
     * Builds the adapter.
     *
     * @return the receiver
     */
    public GoalReceiver build() {
      return new Adapter(
          m_mechanism,
          m_position,
          m_velocity,
          m_percent,
          m_neutral,
          m_hold,
          m_atGoal,
          m_measured,
          m_setpoints);
    }
  }

  /**
   * The {@link Builder} product: a mechanism plus up to seven bound verbs, with a named refusal for
   * every verb that was left unbound.
   *
   * <p>Its constructor is package-private; build one with {@link GoalReceiver#adapting(Mechanism)}.
   */
  final class Adapter implements GoalReceiver {

    private final Mechanism m_mechanism;
    private final DoubleConsumer m_position;
    private final DoubleConsumer m_velocity;
    private final DoubleConsumer m_percent;
    private final Runnable m_neutral;
    private final Runnable m_hold;
    private final BooleanSupplier m_atGoal;
    private final DoubleSupplier m_measured;
    private final List<Setpoint> m_setpoints;
    private final RootstockAlert m_unsupported;

    private boolean m_refusing;

    Adapter(
        Mechanism mechanism,
        DoubleConsumer position,
        DoubleConsumer velocity,
        DoubleConsumer percent,
        Runnable neutral,
        Runnable hold,
        BooleanSupplier atGoal,
        DoubleSupplier measured,
        List<Setpoint> setpoints) {
      m_mechanism = mechanism;
      m_position = position;
      m_velocity = velocity;
      m_percent = percent;
      m_neutral = neutral;
      m_hold = hold;
      m_atGoal = atGoal;
      m_measured = measured;
      m_setpoints = setpoints;
      m_unsupported =
          Alerts.warning(
                  kAlertGroup,
                  kAlertGroup + "/" + mechanism.name() + "/goal-not-bound",
                  MatchImpact.PIT_ONLY)
              .sticky(true);
    }

    @Override
    public String axisName() {
      return m_mechanism.name();
    }

    @Override
    public void applyPosition(double userUnits) {
      if (m_position == null) {
        refuse("a position goal of " + format(userUnits), "position(DoubleConsumer)");
        return;
      }
      m_position.accept(userUnits);
    }

    @Override
    public void applyVelocity(double userPerSecond) {
      if (m_velocity == null) {
        refuse("a velocity goal of " + format(userPerSecond) + "/s", "velocity(DoubleConsumer)");
        return;
      }
      m_velocity.accept(userPerSecond);
    }

    @Override
    public void applyPercent(double dutyCycle) {
      if (m_percent == null) {
        // The one verb the bare base class CAN honour: volts are a Mechanism method.
        m_mechanism.setVoltage(dutyCycle * kNominalVolts);
        return;
      }
      m_percent.accept(dutyCycle);
    }

    @Override
    public void applyNeutral() {
      if (m_neutral == null) {
        m_mechanism.stop();
        return;
      }
      m_neutral.run();
    }

    @Override
    public void applyHold() {
      if (m_hold != null) {
        m_hold.run();
      }
    }

    @Override
    public boolean atGoal() {
      if (m_refusing) {
        // A goal that was refused has not been reached, and reporting otherwise would let a
        // transition "complete" on an axis that never moved.
        return false;
      }
      return m_atGoal == null || m_atGoal.getAsBoolean();
    }

    @Override
    public double measuredUser() {
      if (m_measured != null) {
        return m_measured.getAsDouble();
      }
      return m_mechanism.units().fromSi(m_mechanism.measuredSi());
    }

    @Override
    public List<Setpoint> declaredSetpoints() {
      return m_setpoints;
    }

    @Override
    public String describe() {
      return axisName()
          + " ["
          + (m_position == null ? "-" : "position")
          + " "
          + (m_velocity == null ? "-" : "velocity")
          + " "
          + (m_percent == null ? "volts" : "percent")
          + "] "
          + m_setpoints.size()
          + " declared setpoint(s)";
    }

    private void refuse(String what, String binder) {
      m_refusing = true;
      m_unsupported
          .text(
              "Superstructure: "
                  + axisName()
                  + " was given "
                  + what
                  + ", but nothing is bound to honour it, so the axis is holding and did NOT move. "
                  + "Expected: a GoalReceiver with that verb bound. Fix: build this axis with "
                  + "GoalReceiver.adapting("
                  + axisName()
                  + ")."
                  + binder
                  + ".build() and pass it to Superstructure.Builder.defaultFor(...), or stop "
                  + "asserting that goal for this axis.")
          .set(true);
    }

    private static String format(double v) {
      return String.format(Locale.ROOT, "%.3f", v);
    }
  }
}

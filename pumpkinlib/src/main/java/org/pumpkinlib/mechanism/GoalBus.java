package org.pumpkinlib.mechanism;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;

/**
 * A coordinated set of mechanisms exposed as a single asynchronous request target.
 *
 * <p><b>This is the contract that makes auto mechanism coordination possible at all.</b> PathPlanner's
 * best-documented footgun is that a {@code NamedCommand} sharing subsystem requirements with the
 * enclosing auto group <i>cancels the auto group</i>. A trajectory trigger that fires "raise the
 * elevator" therefore silently kills the path it was fired from, and the symptom — the robot stops
 * mid-auto — looks nothing like the cause.
 *
 * <h2>The contract</h2>
 *
 * <p>The implementation owns the requirements of every mechanism subsystem it coordinates,
 * <b>exactly once, for the whole match</b>. Commands returned from this interface <b>must not</b>
 * declare those subsystems as requirements. That is what lets a goal be requested from a
 * {@code Trigger} inside a running auto without the scheduler treating it as a competing claim.
 *
 * <h2>Why the interface ships even if the state machine does not</h2>
 *
 * <p>A superstructure with a transition graph is the intended implementation, but the auto domain
 * cannot wait for it: without <i>some</i> implementation, auto coordination has no seam and every
 * team writes the requirement collision back in by hand. {@link #ofCommands(Map)} is that floor. It
 * adapts a plain goal&rarr;{@link Command} map, warns once — in the pit tab, not the driver mirror —
 * that requirement-collision protection is <b>not</b> available, and works. A team gets goal dispatch
 * without adopting a superstructure, and the auto domain gets a type it can depend on today.
 *
 * @param <G> the team's goal enum
 */
public interface GoalBus<G extends Enum<G>> {

  /** The {@code Alerts} group every {@code GoalBus} diagnostic is filed under. */
  String kAlertGroup = "GoalBus";

  /**
   * Fire and forget: ask for a goal and return immediately.
   *
   * <p>Legal to call from a {@code Trigger} inside a running auto, which is the entire reason this
   * method exists in preference to scheduling a command. <b>Never blocks</b> and never throws — a
   * goal that cannot be served raises an alert and the robot keeps running.
   *
   * @param goal the goal to request
   */
  void requestAsync(G goal);

  /**
   * A command that requests {@code goal} and finishes when the implementation reports it reached.
   *
   * <p>A fresh instance every call — WPILib forbids reusing a composed command, and a cached one
   * would be shared between the auto routine and a button binding.
   *
   * @param goal the goal to request
   * @return the command; it declares no mechanism requirements, per the contract above
   */
  Command request(G goal);

  /**
   * A trigger that is true while the implementation has settled at {@code goal}.
   *
   * @param goal the goal to watch
   * @return the trigger
   */
  Trigger atGoal(G goal);

  /**
   * Whether no transition is in flight.
   *
   * @return true when the implementation is settled
   */
  boolean isStable();

  /**
   * Best-effort planned duration of the transition currently in flight, for auto budget reporting.
   *
   * <p>Zero is a legitimate answer and means "not measured". An implementation that has measured its
   * transition costs returns the real number and the auto report attributes overrun to the
   * mechanism; one that has not returns zero and the report attributes overrun to the trajectory.
   *
   * @return the planned duration in seconds, or zero when unknown
   */
  double plannedTransitionSeconds();

  /**
   * The goal most recently requested.
   *
   * @return the current goal; never null
   */
  G currentGoal();

  /**
   * Adapts a plain goal&rarr;command map into a {@code GoalBus}, so goal dispatch is available
   * without a superstructure.
   *
   * <p><b>What you give up, stated once, loudly, and in the pit tab.</b> This adapter cannot own
   * mechanism requirements, because it does not know what the mapped commands require. If one of
   * them requires a subsystem the enclosing auto group also requires, the auto group is cancelled —
   * the exact failure {@code GoalBus} exists to prevent. A {@link MatchImpact#PIT_ONLY} warning is
   * raised the moment this adapter is created so that the trade is visible before an event rather
   * than diagnosed at one.
   *
   * <p>Semantics: {@link #requestAsync} cancels the command in flight and schedules the mapped one;
   * {@link #isStable()} is "nothing scheduled"; {@link #atGoal(Enum)} is "this is the current goal
   * and nothing is scheduled"; {@link #plannedTransitionSeconds()} is zero, because a map has no
   * measured costs.
   *
   * @param <G> the goal enum
   * @param commands the goal&rarr;command map; copied, so later mutation of the argument is not
   *     observed. Must be non-null and non-empty, and no value may be null
   * @return the adapter, whose {@link #currentGoal()} starts at the map's first key
   * @throws IllegalArgumentException if the map is empty or contains a null key or value
   * @throws NullPointerException if {@code commands} is null
   */
  static <G extends Enum<G>> GoalBus<G> ofCommands(Map<G, Command> commands) {
    Objects.requireNonNull(commands, "GoalBus.ofCommands: the goal->command map must not be null.");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException(
          "GoalBus.ofCommands: the goal->command map was empty. A bus with no goals can never "
              + "answer currentGoal(), so this is a configuration mistake rather than a degraded "
              + "mode. Pass at least one entry, e.g. Map.of(Goal.STOW, superstructure.stow()).");
    }
    for (Map.Entry<G, Command> entry : commands.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "GoalBus.ofCommands: the goal->command map contained a null "
                + (entry.getKey() == null ? "key" : "value for goal " + entry.getKey())
                + ". Every goal must map to a real command; use Commands.none().withName(...) if a "
                + "goal is deliberately a no-op, so the log still names it.");
      }
    }
    return new CommandMapBus<>(commands);
  }

  /**
   * The {@link #ofCommands(Map)} adapter.
   *
   * <p>Its constructor is private, so it is reachable only through {@link #ofCommands(Map)}: there
   * is exactly one way to build one and the warning cannot be skipped. (A member type of an
   * interface is implicitly {@code public static} and cannot be declared private, which is why the
   * <i>constructor</i> carries the restriction rather than the type.)
   *
   * @param <G> the goal enum
   */
  final class CommandMapBus<G extends Enum<G>> implements GoalBus<G> {

    private final Map<G, Command> m_commands;
    private final Map<G, Trigger> m_triggers;
    private final PumpkinAlert m_unknownGoal;

    private G m_current;
    private Command m_inFlight;

    private CommandMapBus(Map<G, Command> commands) {
      m_commands = new LinkedHashMap<>(commands);
      m_current = m_commands.keySet().iterator().next();
      m_triggers = new EnumMap<>(m_current.getDeclaringClass());
      m_unknownGoal =
          Alerts.warning(kAlertGroup, kAlertGroup + "/unknown-goal", MatchImpact.PIT_ONLY);
      Alerts.warning(
              kAlertGroup,
              "GoalBus.ofCommands is in use for goals "
                  + m_commands.keySet()
                  + ". Requirement-collision protection is NOT active: this adapter does not own "
                  + "the mechanisms' requirements, so if a mapped command requires a subsystem the "
                  + "auto group also requires, the auto group will be cancelled. Fix: implement "
                  + "GoalBus on a superstructure that declares the union of its mechanisms' "
                  + "requirements once.",
              MatchImpact.PIT_ONLY)
          .set(true);
    }

    @Override
    public void requestAsync(G goal) {
      Command next = goal == null ? null : m_commands.get(goal);
      if (next == null) {
        m_unknownGoal
            .text(
                "GoalBus.ofCommands: no command is mapped for goal "
                    + goal
                    + ". Known goals are "
                    + m_commands.keySet()
                    + ". The request was ignored. Fix: add the goal to the map passed to "
                    + "GoalBus.ofCommands, or stop requesting it.")
            .set(true);
        return;
      }
      if (m_inFlight != null && m_inFlight != next && m_inFlight.isScheduled()) {
        m_inFlight.cancel();
      }
      m_current = goal;
      m_inFlight = next;
      if (!next.isScheduled()) {
        // CommandScheduler.schedule(...) rather than Command.schedule(), which is deprecated for
        // removal on the 2026 line and gone on the 2027 one.
        CommandScheduler.getInstance().schedule(next);
      }
    }

    @Override
    public Command request(G goal) {
      return Commands.runOnce(() -> requestAsync(goal))
          .andThen(Commands.waitUntil(this::isStable))
          .withName("GoalBus.request(" + goal + ")");
    }

    @Override
    public Trigger atGoal(G goal) {
      if (goal == null) {
        // A trigger that is never true, rather than a NullPointerException out of a binding site.
        return new Trigger(() -> false);
      }
      return m_triggers.computeIfAbsent(goal, g -> new Trigger(() -> m_current == g && isStable()));
    }

    @Override
    public boolean isStable() {
      return m_inFlight == null || !m_inFlight.isScheduled();
    }

    @Override
    public double plannedTransitionSeconds() {
      return 0.0;
    }

    @Override
    public G currentGoal() {
      return m_current;
    }
  }
}

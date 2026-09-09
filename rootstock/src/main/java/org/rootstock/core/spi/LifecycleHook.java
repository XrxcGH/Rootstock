package org.rootstock.core.spi;

/**
 * The one downward edge: how telemetry, tuning, sim and viz get called each loop without
 * {@code org.rootstock.core} ever naming them.
 *
 * <p><b>Why this interface exists at all.</b> ArchUnit rule 9 says every dependency arrow points into
 * core. {@code RootstockLifecycle} must call {@code RootstockLog.beforeUserPeriodic()} and
 * {@code TuningRegistry.drainPoller()} every loop, and a direct call from core into telemetry is the
 * arrow the rule forbids. So core declares the shape and the downstream packages implement it. D28's
 * one-jar packaging means the code would compile either way — the rule is retained anyway, precisely
 * so the artifact split stays a zero-cost option.
 *
 * <p><b>How hooks are discovered (D26 as amended by decision 3).</b> The rule, in one line:
 * <b>out-of-jar means {@code ServiceLoader}; in-jar means the explicit list.</b> In-jar hooks —
 * telemetry, tuning, sim, viz — are registered explicitly, in code, inside
 * {@code RootstockLifecycle.create()}, in a hand-written priority order. Out-of-jar hooks (the vendor
 * adapters, and {@link VisionSimHook}) keep {@code ServiceLoader}, because that is where it is
 * load-bearing. {@code RootstockLifecycle.hooks()} returns the merged, priority-ordered view of both,
 * and the boot dump prints each hook's origin so "which mechanism found this hook" is never a guess.
 *
 * <p>Every method except {@link #name()} and {@link #priority()} has a no-op default, so a hook
 * implements only the phases it cares about. <b>A hook must never throw</b> — a throw here takes the
 * robot down for a telemetry problem, and {@code RootstockLifecycle} wraps each call in a
 * {@code try/catch(Throwable)} that raises an alert instead.
 */
public interface LifecycleHook {

  /**
   * A short, stable name for the boot dump and for the tracer's per-section budgets.
   *
   * @return the hook's name, e.g. {@code "Telemetry"} or {@code "Tuning"}
   */
  String name();

  /**
   * Ordering within a phase: <b>lower runs first</b>.
   *
   * <p>The reserved values in the library today are 10 (telemetry input capture), 30 (the tuning
   * poller drain), 50 (simulation) and 70 (visualization). Leaving gaps is deliberate — a team or an
   * adapter that needs to land between two of ours should not have to renumber ours.
   *
   * @return the priority
   */
  int priority();

  /**
   * One-time initialization, run from {@code RootstockLifecycle.init()} after every registrant has been
   * added, so a hook can inspect what was registered.
   *
   * <p>Idempotent: {@code init()} is itself idempotent, and a hook may see it at most once.
   */
  default void init() {}

  /**
   * Run before the team's {@code periodic()} and before the command scheduler: read inputs, poll
   * health monitors, drain the tunable queue, open the tracer epoch.
   */
  default void beforeUserPeriodic() {}

  /**
   * Run after the team's {@code periodic()} and after the command scheduler: flush telemetry, run the
   * byte-budget governor, close the tracer epoch.
   */
  default void afterUserPeriodic() {}

  /**
   * Run once per simulation tick, with the loop timestep.
   *
   * <p>{@code RootstockSim} is the implementor: it steps every declared {@link MechanismGeometry}'s
   * plant and writes the result back through each {@link SimMotorHandle}. It is called only when
   * {@code Platform.isSimulation()}.
   *
   * @param dtSeconds the timestep, from {@code Clock.dt()} — never a literal {@code 0.02}
   */
  default void simulationTick(double dtSeconds) {}

  /**
   * Run on the transition into disabled, where the verified full-config re-apply lives.
   *
   * <p>Disabled is the only time it is safe to spend 200 ms of blocking CAN transactions, which is
   * why the re-apply is here and not in {@link #init()}.
   */
  default void disabledInit() {}

  /**
   * Release anything that must be released — publishers, files, threads.
   *
   * <p>Called from {@code RootstockLifecycle.close()}. Must not throw.
   */
  default void close() {}
}

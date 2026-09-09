package org.rootstock.test;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj.simulation.SimHooks;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.SliceScheduler;
import org.rootstock.core.spi.Tier;

/**
 * The JUnit 5 extension that owns the undocumented parts of testing an FRC robot.
 *
 * <p><strong>Why this exists.</strong> Teams do not skip tests out of unwillingness. The preamble is
 * tribal knowledge scattered across three doc sites and its failure modes are inscrutable: forget
 * {@code HAL.initialize(500, 0)} and nothing works; forget to close a device and the <em>next</em>
 * test fails with a port-allocation error; forget {@link DriverStationSim#setEnabled(boolean)} plus
 * {@link DriverStationSim#notifyNewData()} and every actuator reads 0.0 with no diagnostic. And
 * nothing calls {@code simulationPeriodic()} or {@code CommandScheduler.run()} for you — WPILib's
 * own unit-test example never advances a control loop at all. This class is that preamble, written
 * once.
 *
 * <p><strong>The tick order is the load-bearing part.</strong> {@link #step(int)} runs, per cycle,
 * in exactly this order:
 *
 * <ol>
 *   <li>every {@link #ticking(Runnable...)} periodic, in registration order — for the
 *       non-{@code Subsystem} mechanisms that nothing else ticks;
 *   <li>{@code CommandScheduler.getInstance().run()}, which is what calls {@code
 *       Subsystem.periodic()} and, in simulation, {@code Subsystem.simulationPeriodic()};
 *   <li>the library's own per-loop work — {@link SliceScheduler#tick()} and {@link
 *       AlertRegistry#poll()} — the same two calls {@code RootstockRobot.robotPeriodic()} makes;
 *   <li>{@link Clock#tick()}, so the library's cycle counter advances exactly once per loop, from
 *       0, the way {@code RootstockLifecycle.beforeUserPeriodic()} advances it on the robot;
 *   <li>{@link SimHooks#stepTiming(double)} <strong>last</strong>, so timestamps advance
 *       <em>after</em> the cycle rather than in the middle of it.
 * </ol>
 *
 * <p>Step 5 being last is the unobvious one. Stepping time first means a command that checks
 * {@code hasElapsed()} at the top of its {@code execute()} sees a timestamp from a cycle that has
 * not run yet, and every timeout in the test is off by one loop.
 *
 * <p><strong>Natives.</strong> {@link #headless()} initialises the real HAL, which needs the WPILib
 * JNI libraries on {@code java.library.path}. Any test using this extension must therefore be
 * tagged {@code @Tag("hal")}, so that the default {@code ./gradlew test} stays green on a machine
 * with no natives installed; {@code ./gradlew halTest} runs them when the natives are present.
 * Tests of pure logic should not use this extension at all — that is the point of ArchUnit rule 8
 * and of {@link Clock#setSource(java.util.function.DoubleSupplier)}.
 *
 * <p><strong>Deliberately not here in M1.</strong> {@code assertTelemetry}, {@code
 * schemaGenerations()} and the byte-budget-governor assertion are M5: they read AdvantageKit's
 * in-memory {@code LogTable} through the {@code RootstockLog} facade, and neither the facade nor the
 * governor exists yet. {@code org.rootstock.test} is <em>not</em> on ArchUnit rule 1c's allowlist,
 * so when they land they must go through {@code RootstockLog} rather than naming {@code Logger} here.
 * {@code afterConstruction()} and {@code applyAndSettle(Runnable)} are present but wait zero time,
 * because the vendor settle delays they exist to absorb are Phoenix's and Phoenix is an adapter
 * artifact that does not exist in M1.
 *
 * <p>The harness never registers a {@code SimDeviceSim} value-changed callback: {@link
 * SimHooks#stepTiming(double)} can hang (allwpilib #6641) when called from inside one.
 */
public final class RootstockTest implements BeforeEachCallback, AfterEachCallback {

  /** The tick period, in seconds. 50 Hz, matching {@code IterativeRobotBase}'s default. */
  public static final double kPeriodSeconds = 0.02;

  /** HAL initialisation timeout, in milliseconds. WPILib's own examples use 500. */
  private static final int kHalTimeoutMillis = 500;

  private final List<AutoCloseable> m_managed = new ArrayList<>();
  private final List<Runnable> m_periodics = new ArrayList<>();

  private Tier m_minimumTier = Tier.DEBUG;
  private boolean m_started;

  private RootstockTest() {}

  /**
   * A headless robot harness: HAL up, driver station enabled and attached, timing paused so the
   * only thing that advances the clock is {@link #step(int)}, and every static registry in the
   * library reset so one test cannot see another test's alerts.
   *
   * <p>No data receivers and no replay source are configured, so nothing touches the disk or
   * NetworkTables beyond what the code under test does itself.
   *
   * @return the extension. Register it with {@code @RegisterExtension final RootstockTest t =
   *     RootstockTest.headless();}
   */
  public static RootstockTest headless() {
    return new RootstockTest();
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------------------------

  @Override
  public void beforeEach(ExtensionContext context) {
    boolean halUp = HAL.initialize(kHalTimeoutMillis, 0);
    if (!halUp) {
      throw new IllegalStateException(
          "RootstockTest.headless(): HAL.initialize("
              + kHalTimeoutMillis
              + ", 0) returned false. The WPILib JNI natives are not on java.library.path. "
              + "Fix: tag this test @Tag(\"hal\") and run it with ./gradlew halTest on a machine "
              + "with the WPILib desktop natives installed, or rewrite it against the pure tier, "
              + "which needs no natives at all.");
    }

    // Deterministic time. Paused first, so nothing advances between here and the first step().
    SimHooks.pauseTiming();
    SimHooks.restartTiming();

    // The three DS calls that every "my actuator reads 0.0" question turns out to be missing.
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setEnabled(true);
    DriverStationSim.setAutonomous(false);
    DriverStationSim.setTest(false);
    DriverStationSim.notifyNewData();

    // A fresh scheduler: a command left scheduled by the previous test is the classic
    // "this test only fails when run with the others" bug.
    CommandScheduler.getInstance().cancelAll();
    CommandScheduler.getInstance().unregisterAllSubsystems();

    resetLibraryState();
    m_started = true;
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // Reverse order: a mechanism must be closed before the motors it holds.
    for (int i = m_managed.size() - 1; i >= 0; i--) {
      AutoCloseable resource = m_managed.get(i);
      try {
        resource.close();
      } catch (Exception e) {
        throw new IllegalStateException(
            "RootstockTest: closing "
                + resource.getClass().getName()
                + " threw. A leaked HAL port poisons every later test in the JVM with a "
                + "port-allocation error that names the wrong test, so this is a failure here "
                + "rather than a warning.",
            e);
      }
    }
    m_managed.clear();
    m_periodics.clear();

    CommandScheduler.getInstance().cancelAll();
    CommandScheduler.getInstance().unregisterAllSubsystems();
    resetLibraryState();

    SimHooks.resumeTiming();
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    m_started = false;
  }

  /**
   * Register an {@link AutoCloseable} to be closed in {@code @AfterEach}, in reverse registration
   * order.
   *
   * <p>This is what prevents a leaked HAL port from poisoning the next test with an error that
   * names the wrong test.
   *
   * @param resource the resource, returned unchanged so this reads as {@code var e =
   *     t.managing(new Elevator(io));}
   * @param <T> the resource type
   * @return {@code resource}
   */
  public <T extends AutoCloseable> T managing(T resource) {
    m_managed.add(resource);
    return resource;
  }

  /**
   * Register periodics that nothing else ticks.
   *
   * <p>{@code Subsystem}s are ticked by the {@code CommandScheduler}; state-based mechanisms that
   * are not {@code Subsystem}s are not, and a test that forgets this sees a mechanism that never
   * moves and no diagnostic explaining why.
   *
   * @param periodics the methods to call at the top of every cycle, in this order
   * @return {@code this}, for chaining
   */
  public RootstockTest ticking(Runnable... periodics) {
    for (Runnable r : periodics) {
      m_periodics.add(r);
    }
    return this;
  }

  // ---------------------------------------------------------------------------------------------
  // Time
  // ---------------------------------------------------------------------------------------------

  /**
   * Advance the robot by {@code cycles} loops, in the documented order.
   *
   * @param cycles the number of 20 ms loops. Zero is legal and does nothing.
   */
  public void step(int cycles) {
    requireStarted();
    for (int i = 0; i < cycles; i++) {
      tickOnce();
    }
  }

  /**
   * Advance the robot by a duration, rounded up to whole cycles.
   *
   * <p>Rounded <em>up</em> deliberately: a test that asks for 2.0 s and gets 1.98 s fails for a
   * reason that has nothing to do with the code under test.
   *
   * @param seconds the duration
   */
  public void stepSeconds(double seconds) {
    step((int) Math.ceil(seconds / kPeriodSeconds - 1e-9));
  }

  /**
   * Step until a condition holds, or the timeout expires.
   *
   * @param condition checked after each cycle
   * @param timeoutSeconds how long to wait before giving up
   * @return true if the condition became true before the timeout
   */
  public boolean runUntil(BooleanSupplier condition, double timeoutSeconds) {
    requireStarted();
    int limit = (int) Math.ceil(timeoutSeconds / kPeriodSeconds - 1e-9);
    for (int i = 0; i < limit; i++) {
      tickOnce();
      if (condition.getAsBoolean()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Apply a control request and wait the documented vendor settle time.
   *
   * <p>Zero cycles in M1: the settle delay this exists to absorb is Phoenix 6's ~20 ms
   * post-request delay, and Phoenix is an adapter artifact that does not exist yet. The call site
   * is correct now so it does not have to be found and edited later.
   *
   * @param request the request to apply
   */
  public void applyAndSettle(Runnable request) {
    request.run();
    step(0);
  }

  /**
   * Wait the documented post-construction vendor settle time. Call once, after every device exists.
   *
   * <p>Zero cycles in M1, for the same reason as {@link #applyAndSettle(Runnable)}.
   *
   * @return {@code this}, for chaining
   */
  public RootstockTest afterConstruction() {
    step(0);
    return this;
  }

  // ---------------------------------------------------------------------------------------------
  // Gates and assertions
  // ---------------------------------------------------------------------------------------------

  /**
   * Raise the telemetry tier gate mid-test, the way an FMS attach does — except that a test may
   * raise it further than the FMS gate ever does.
   *
   * <p>This is how a test proves a key survives the gate rather than asserting it from the tier
   * table by eye. There is no FMS simulator here and there does not need to be: {@code
   * minimumTier} is the only thing the gate changes.
   *
   * <p>In M1 this records the requested tier and nothing reads it, because the tier gate lives in
   * the telemetry domain (M5). {@link #minimumTier()} exposes it so the wiring is testable the day
   * the gate exists.
   *
   * @param tier the new minimum tier
   * @return {@code this}, for chaining
   */
  public RootstockTest withMinimumTier(Tier tier) {
    m_minimumTier = tier;
    return this;
  }

  /**
   * The tier gate currently requested by {@link #withMinimumTier(Tier)}.
   *
   * @return the minimum tier, {@link Tier#DEBUG} unless set
   */
  public Tier minimumTier() {
    return m_minimumTier;
  }

  /**
   * Every alert currently raised through {@code org.rootstock.core.alert}, in registration order.
   *
   * <p>Named for the alert facade, not the deleted {@code RootstockFaults}. A test asserting {@code
   * assertEquals(List.of(), t.raisedAlerts())} is pinning "nominal operation raises nothing" — a
   * test that silently raises the same warning every run is a test lying to you.
   *
   * @return the {@code describe()} line of every active alert
   */
  public List<String> raisedAlerts() {
    List<String> out = new ArrayList<>();
    for (RootstockAlert alert : AlertRegistry.active()) {
      out.add(alert.describe());
    }
    return out;
  }

  /**
   * The wall time the most recent loop took, in milliseconds, as measured by {@link RootstockTracer}.
   *
   * @return the last loop's duration in milliseconds, 0 if no loop has run
   */
  public double lastLoopMillis() {
    return RootstockTracer.loopMillis();
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private void tickOnce() {
    for (Runnable periodic : m_periodics) {
      periodic.run();
    }
    CommandScheduler.getInstance().run();
    SliceScheduler.tick();
    AlertRegistry.poll();
    Clock.tick();
    SimHooks.stepTiming(kPeriodSeconds);
  }

  private void requireStarted() {
    if (!m_started) {
      throw new IllegalStateException(
          "RootstockTest was used outside a test. Register it with "
              + "@RegisterExtension final RootstockTest t = RootstockTest.headless(); - a plain field "
              + "assignment never runs beforeEach, so the HAL is down and time never advances.");
    }
  }

  private static void resetLibraryState() {
    AlertRegistry.resetForTest();
    SliceScheduler.resetForTest();
    RootstockTracer.resetForTest();
    Clock.resetForTest();
    Clock.setPeriodSeconds(kPeriodSeconds);
  }
}

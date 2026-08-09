package org.pumpkinlib.core;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.pumpkinlib.core.config.CanIdRegistry;
import org.pumpkinlib.core.spi.LifecycleHook;

/**
 * <b>The one registration call.</b> {@code PumpkinRegistry.addAll(m_drive, m_elevator, m_arm);}
 *
 * <p>Revision 1 had four parallel lists over the same objects — {@code PumpkinRegistry.addAll},
 * {@code SelfTest.registerAll}, {@code TuningRegistry.registerAll} and {@code HealthMonitor.watch} —
 * with different membership for non-obvious reasons, in the example that advertised "ONE list".
 * DESIGN.md <b>D27</b> collapses them: this class inspects each argument <i>once</i> and routes it by
 * {@code instanceof}. Opting out is a filter on the mechanism's config
 * ({@code .excludeFrom(Registry.TUNING)}), which is visible in {@code describe()} rather than absent
 * from a call site. {@code SelfTest.registerAll}, {@code TuningRegistry.registerAll} and
 * {@code HealthMonitor.watch} are not public API.
 *
 * <p><b>Why the parameter is {@code Object...}.</b> ArchUnit rule 9 forbids any {@code
 * org.pumpkinlib.core} type from naming {@code telemetry}, {@code tuning}, {@code sim}, {@code
 * vision}, {@code drive}, {@code auto}, {@code mechanism} or {@code superstructure} in a
 * <i>signature</i> — every dependency arrow points into core. {@code TelemetrySource},
 * {@code TuningTarget} and the rest therefore cannot appear here, and the routing table is built the
 * other way round: each domain installs its own {@link Route} when it initialises. That is the
 * documented seam, and it is what keeps the D28 artifact split available at zero cost.
 *
 * <h2>What is routed at M1</h2>
 *
 * <p>Core installs the routes it can name and that exist today: {@link LifecycleHook} (core.spi),
 * WPILib's {@link Subsystem}, {@link AutoCloseable}, and {@link CanIdRegistry.Device}. The telemetry,
 * health, self-test and tuning routes are installed by their own packages in later milestones; until
 * then the boot summary says <i>"telemetry: no route installed"</i> rather than silently reporting
 * zero, because "nothing registered" and "nowhere to register it" are different bugs.
 *
 * <h2>The boot summary</h2>
 *
 * <pre>
 * [PumpkinLib] Registered 3 components: 3 lifecycle-hook, 3 subsystem, 0 telemetry (no route
 *              installed), 12 CAN devices scanned, 0 config faults.
 * </pre>
 *
 * <p>A missing registration is then a <i>diff in the boot dump</i> rather than a mystery in week 4.
 *
 * <h2>Validation happens here and only here</h2>
 *
 * <p>Per-component faults are collected through {@link #addFaultExtractor(Function)}, the global CAN
 * ID uniqueness scan runs once over the <i>final resolved</i> device set (never as a side effect of
 * a record constructor — every {@code with*()} copy re-runs that constructor, so a per-robot overlay
 * would report a false conflict on a correct config), everything is printed together, and a fatal
 * fault enters {@link SafeMode}. Nothing throws.
 */
public final class PumpkinRegistry {

  private PumpkinRegistry() {}

  /** Route label for AdvantageKit-backed telemetry. Installed by {@code org.pumpkinlib.telemetry}. */
  public static final String kRouteTelemetry = "telemetry";

  /** Route label for the fault monitors. Installed by {@code org.pumpkinlib.core.health}. */
  public static final String kRouteHealth = "health";

  /** Route label for the self-test routines. Installed by {@code org.pumpkinlib.core.selftest}. */
  public static final String kRouteSelfTest = "selftest";

  /** Route label for live tuning. Installed by {@code org.pumpkinlib.tuning}. */
  public static final String kRouteTuning = "tuning";

  /** Route label for {@code CommandScheduler} registration. Installed by core. */
  public static final String kRouteScheduler = "subsystem";

  /** Route label for {@link LifecycleHook} components. Installed by core. */
  public static final String kRouteLifecycleHook = "lifecycle-hook";

  /** Route label for {@link AutoCloseable} components closed by {@link PumpkinLifecycle#close()}. */
  public static final String kRouteCloseable = "closeable";

  /** Route label for directly supplied {@link CanIdRegistry.Device} declarations. */
  public static final String kRouteCanDevice = "can-device";

  /** The routes core expects other packages to install, named so their absence is reportable. */
  private static final List<String> kExpectedRoutes =
      List.of(kRouteTelemetry, kRouteHealth, kRouteSelfTest, kRouteTuning);

  /**
   * One destination for registered components.
   *
   * <p>A domain that cannot be named by core installs one of these at its own initialisation:
   *
   * <pre>{@code
   * PumpkinRegistry.addRoute(new PumpkinRegistry.Route(
   *     PumpkinRegistry.kRouteTelemetry,
   *     o -> o instanceof TelemetrySource,
   *     o -> registerSource((TelemetrySource) o)));
   * }</pre>
   *
   * <p>Routes are evaluated in installation order and a component may match several — a
   * {@code Mechanism} matches telemetry, health, self-test, tuning <i>and</i> subsystem, which is the
   * entire point of D27.
   *
   * @param label the name that appears in the boot summary; one route per label
   * @param accepts whether this route wants the component
   * @param sink what to do with an accepted component; must not throw
   */
  public record Route(String label, Predicate<Object> accepts, Consumer<Object> sink) {

    /** Canonical constructor; rejects nulls. */
    public Route {
      Objects.requireNonNull(label, "PumpkinRegistry.Route: label must not be null");
      Objects.requireNonNull(accepts, "PumpkinRegistry.Route: accepts must not be null");
      Objects.requireNonNull(sink, "PumpkinRegistry.Route: sink must not be null");
    }
  }

  private static final Map<String, Route> s_routes = new LinkedHashMap<>();
  private static final Map<String, Integer> s_counts = new LinkedHashMap<>();
  private static final List<Object> s_registered = new ArrayList<>();
  private static final List<AutoCloseable> s_closeables = new ArrayList<>();
  private static final List<LifecycleHook> s_hooks = new ArrayList<>();
  private static final List<CanIdRegistry.Device> s_devices = new ArrayList<>();
  private static final List<Function<Object, List<SafeMode.Fault>>> s_faultExtractors =
      new ArrayList<>();
  private static final List<Function<Object, List<CanIdRegistry.Device>>> s_deviceExtractors =
      new ArrayList<>();
  private static final Map<String, Double> s_busAllowances = new LinkedHashMap<>();
  private static final List<String> s_skipped = new ArrayList<>();
  private static String s_lastSummary = "PumpkinRegistry.addAll has not been called.";

  static {
    installCoreRoutes();
  }

  /**
   * Registers every component, once, and validates the whole robot.
   *
   * <p>Call it from the end of {@code RobotContainer}'s constructor, before
   * {@link PumpkinLifecycle#init()}, so the boot dump reflects what was registered. May be called
   * more than once (a team that builds its drivetrain in a second phase); the counts and the fault
   * list accumulate and the summary is reprinted.
   *
   * <p>A component named in the runtime kill switch ({@link PumpkinLib#isDisabled(String)}) is
   * skipped entirely and reported in {@link #skipped()} with the reason.
   *
   * @param components the mechanisms, superstructure, drivetrain and anything else that wants to be
   *     seen by the platform; nulls are skipped with a named reason rather than throwing
   */
  public static synchronized void addAll(Object... components) {
    Objects.requireNonNull(components, "PumpkinRegistry.addAll: the array must not be null");

    List<Object> accepted = new ArrayList<>(components.length);
    for (Object component : components) {
      if (component == null) {
        s_skipped.add("(null) — skipped; a null in the addAll list is almost always a field that "
            + "is assigned after the addAll call rather than before it");
        continue;
      }
      String name = nameOf(component);
      if (PumpkinLib.isDisabled(name)) {
        s_skipped.add(name + " — skipped; disabled by the runtime kill switch (PumpkinLib.disable "
            + "or deploy/pumpkin/" + PumpkinLib.kDisabledFileName + ")");
        continue;
      }
      accepted.add(component);
    }

    for (Object component : accepted) {
      s_registered.add(component);
      for (Route route : s_routes.values()) {
        if (route.accepts().test(component)) {
          route.sink().accept(component);
          s_counts.merge(route.label(), 1, Integer::sum);
        }
      }
    }

    List<SafeMode.Fault> faults = new ArrayList<>();
    for (Object component : accepted) {
      for (Function<Object, List<SafeMode.Fault>> extractor : s_faultExtractors) {
        List<SafeMode.Fault> found = extractor.apply(component);
        if (found != null) {
          faults.addAll(found);
        }
      }
      for (Function<Object, List<CanIdRegistry.Device>> extractor : s_deviceExtractors) {
        List<CanIdRegistry.Device> found = extractor.apply(component);
        if (found != null) {
          s_devices.addAll(found);
        }
      }
    }

    // ONE global scan, over the FINAL resolved device set (design/01 section 5.6b). Doing this in a
    // record's compact constructor would re-register on every with*() copy and report a false
    // conflict on the per-robot overlay pattern, which is the design's own answer to sibling robots.
    for (CanIdRegistry.Conflict conflict : CanIdRegistry.scanForConflicts(s_devices)) {
      faults.add(toFault(conflict));
    }

    printAll(faults);
    SafeMode.enter(faults);

    s_lastSummary = buildSummary(faults.size());
    System.out.println(s_lastSummary);
  }

  /**
   * Installs a destination for components core cannot name.
   *
   * <p>Installing a second route with an existing label replaces the first — a domain that
   * re-initialises does not accumulate duplicate sinks.
   *
   * @param route the route
   */
  public static synchronized void addRoute(Route route) {
    Objects.requireNonNull(route, "PumpkinRegistry.addRoute: route must not be null");
    s_routes.put(route.label(), route);
    s_counts.putIfAbsent(route.label(), 0);

    // A route installed after components were already registered still sees them: registration
    // order between RobotContainer and a lazily-initialised domain is not something a team should
    // have to reason about.
    for (Object component : s_registered) {
      if (route.accepts().test(component)) {
        route.sink().accept(component);
        s_counts.merge(route.label(), 1, Integer::sum);
      }
    }
  }

  /**
   * Installs an adapter that turns a registered component into validation faults.
   *
   * <p>This is how {@code org.pumpkinlib.config} contributes its {@code ConfigError} list without
   * core naming the type: the adapter is a field-for-field copy into {@link SafeMode.Fault}.
   * Several adapters may be installed; all of them see every component.
   *
   * @param extractor returns the component's faults, or an empty list (never null) when it has none
   *     and when it is not a type this extractor understands
   */
  public static synchronized void addFaultExtractor(
      Function<Object, List<SafeMode.Fault>> extractor) {
    s_faultExtractors.add(
        Objects.requireNonNull(extractor, "PumpkinRegistry.addFaultExtractor: null extractor"));
  }

  /**
   * Installs an adapter that turns a registered component into its declared CAN devices.
   *
   * <p>Mechanism and drive configs know their motors and encoders; core does not. Everything these
   * adapters return goes into the single global uniqueness scan.
   *
   * @param extractor returns the component's devices, or an empty list (never null)
   */
  public static synchronized void addDeviceExtractor(
      Function<Object, List<CanIdRegistry.Device>> extractor) {
    s_deviceExtractors.add(
        Objects.requireNonNull(extractor, "PumpkinRegistry.addDeviceExtractor: null extractor"));
  }

  /**
   * Declares how much of a CAN bus a component that is not a registered mechanism is expected to
   * consume — in practice, the drivetrain.
   *
   * <p>The default when nothing declares one is zero, and {@link #describe()} says so rather than
   * assuming a number that would make the aggregate frame budget quietly wrong.
   *
   * @param bus the CAN bus name, {@code "rio"} for the roboRIO bus
   * @param fraction the expected utilisation as a fraction of one, e.g. {@code 0.65}
   */
  public static synchronized void declareBusAllowance(String bus, double fraction) {
    Objects.requireNonNull(bus, "PumpkinRegistry.declareBusAllowance: bus must not be null");
    s_busAllowances.put(bus, fraction);
  }

  /**
   * Everything registered so far, in registration order.
   *
   * @return an unmodifiable snapshot
   */
  public static synchronized List<Object> registered() {
    return List.copyOf(s_registered);
  }

  /**
   * How many components each route accepted.
   *
   * @return an unmodifiable snapshot keyed by route label
   */
  public static synchronized Map<String, Integer> counts() {
    return Map.copyOf(s_counts);
  }

  /**
   * What was <i>not</i> registered, and why.
   *
   * <p>Kill-switched components and nulls. Printed in the boot summary, because a component that
   * silently does not exist is the worst thing this class could do.
   *
   * @return an unmodifiable snapshot, one sentence per skipped component
   */
  public static synchronized List<String> skipped() {
    return List.copyOf(s_skipped);
  }

  /**
   * The {@link LifecycleHook}s registered through {@link #addAll(Object...)}.
   *
   * <p>Read by {@link PumpkinLifecycle} when it builds its priority-ordered hook list.
   *
   * @return an unmodifiable snapshot
   */
  public static synchronized List<LifecycleHook> hooks() {
    return List.copyOf(s_hooks);
  }

  /**
   * Every CAN device the extractors reported, after the global scan.
   *
   * @return an unmodifiable snapshot
   */
  public static synchronized List<CanIdRegistry.Device> devices() {
    return List.copyOf(s_devices);
  }

  /**
   * The one-line boot summary from the most recent {@link #addAll(Object...)}.
   *
   * @return the summary, or a sentence saying {@code addAll} has not run
   */
  public static synchronized String bootSummary() {
    return s_lastSummary;
  }

  /**
   * Called from {@link PumpkinLifecycle#disabledInit()} on every disable edge.
   *
   * <p>Registered components that are {@link LifecycleHook}s get {@code disabledInit()}; this is the
   * hook the mechanism domain uses for its verified full-config re-apply, which is safe here because
   * the robot is disabled and the loop budget is irrelevant.
   */
  public static synchronized void onDisable() {
    for (LifecycleHook hook : s_hooks) {
      hook.disabledInit();
    }
  }

  /**
   * The registry, as a section of the boot dump.
   *
   * @return a multi-line report: routes, counts, skips, bus allowances and the device scan
   */
  public static synchronized String describe() {
    StringBuilder sb = new StringBuilder(512);
    sb.append("PumpkinRegistry").append(System.lineSeparator());
    sb.append("  components   ").append(s_registered.size()).append(System.lineSeparator());
    sb.append("  routes       ")
        .append(s_routes.isEmpty() ? "none" : String.join(", ", s_routes.keySet()))
        .append(System.lineSeparator());
    for (Map.Entry<String, Integer> entry : s_counts.entrySet()) {
      sb.append(String.format("    %-16s %d%n", entry.getKey(), entry.getValue()));
    }
    for (String expected : kExpectedRoutes) {
      if (!s_routes.containsKey(expected)) {
        sb.append(String.format("    %-16s no route installed (that package is a later milestone)%n",
            expected));
      }
    }
    if (!s_skipped.isEmpty()) {
      sb.append("  skipped").append(System.lineSeparator());
      for (String reason : s_skipped) {
        sb.append("    ").append(reason).append(System.lineSeparator());
      }
    }
    sb.append("  bus allowance ")
        .append(s_busAllowances.isEmpty() ? "none declared (assumed 0.0)" : s_busAllowances)
        .append(System.lineSeparator());
    sb.append(System.lineSeparator()).append(CanIdRegistry.describe(s_devices));
    return sb.toString();
  }

  /** Clears every route, count and component. Tests only. */
  public static synchronized void resetForTest() {
    s_routes.clear();
    s_counts.clear();
    s_registered.clear();
    s_closeables.clear();
    s_hooks.clear();
    s_devices.clear();
    s_faultExtractors.clear();
    s_deviceExtractors.clear();
    s_busAllowances.clear();
    s_skipped.clear();
    s_lastSummary = "PumpkinRegistry.addAll has not been called.";
    installCoreRoutes();
  }

  /** Closes every {@link AutoCloseable} component, newest first. Called by the lifecycle. */
  static synchronized void closeAll() {
    for (int i = s_closeables.size() - 1; i >= 0; i--) {
      AutoCloseable closeable = s_closeables.get(i);
      try {
        closeable.close();
      } catch (Exception e) {
        // Degrade, never crash: a component that fails to close must not stop the rest closing,
        // and close() runs on the way out of a process that is ending anyway.
        System.err.println(
            "[PumpkinLib] " + nameOf(closeable) + " threw from close(): " + e.getMessage());
      }
    }
    s_closeables.clear();
  }

  private static void installCoreRoutes() {
    // The routes core can name. Everything else installs its own — rule 9.
    addRouteInternal(
        new Route(
            kRouteLifecycleHook,
            o -> o instanceof LifecycleHook,
            o -> s_hooks.add((LifecycleHook) o)));
    addRouteInternal(
        new Route(
            kRouteScheduler,
            o -> o instanceof Subsystem,
            o -> CommandScheduler.getInstance().registerSubsystem((Subsystem) o)));
    addRouteInternal(
        new Route(
            kRouteCloseable,
            o -> o instanceof AutoCloseable,
            o -> s_closeables.add((AutoCloseable) o)));
    addRouteInternal(
        new Route(
            kRouteCanDevice,
            o -> o instanceof CanIdRegistry.Device,
            o -> s_devices.add((CanIdRegistry.Device) o)));
  }

  /** Installs a route without replaying already-registered components (used from the initialiser). */
  private static void addRouteInternal(Route route) {
    s_routes.put(route.label(), route);
    s_counts.putIfAbsent(route.label(), 0);
  }

  private static String buildSummary(int faultCount) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("[PumpkinLib] Registered ").append(s_registered.size()).append(" component(s): ");
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : s_counts.entrySet()) {
      parts.add(entry.getValue() + " " + entry.getKey());
    }
    for (String expected : kExpectedRoutes) {
      if (!s_routes.containsKey(expected)) {
        parts.add("0 " + expected + " (no route installed)");
      }
    }
    sb.append(String.join(", ", parts));
    sb.append("; ").append(s_devices.size()).append(" CAN device(s) scanned; ")
        .append(faultCount).append(" config fault(s)");
    if (!s_skipped.isEmpty()) {
      sb.append("; ").append(s_skipped.size()).append(" skipped");
    }
    sb.append('.');
    if (!s_skipped.isEmpty()) {
      for (String reason : s_skipped) {
        sb.append(System.lineSeparator()).append("             skipped: ").append(reason);
      }
    }
    return sb.toString();
  }

  /** Prints every fault together, once — not one per deploy cycle. */
  private static void printAll(List<SafeMode.Fault> faults) {
    if (faults.isEmpty()) {
      return;
    }
    StringBuilder sb = new StringBuilder(1024);
    sb.append(System.lineSeparator())
        .append("================ PumpkinLib config validation: ").append(faults.size())
        .append(" problem(s) ================").append(System.lineSeparator());
    for (SafeMode.Fault fault : faults) {
      sb.append(System.lineSeparator()).append(fault.describe());
    }
    sb.append("========================================================================")
        .append(System.lineSeparator());
    System.out.println(sb);
  }

  /**
   * Both conflict kinds are FATAL: an out-of-range id makes the vendor constructor throw at boot,
   * and two devices sharing an id on one bus will fight. Neither is something the robot can run
   * through, and neither is worth a warning the team can scroll past.
   */
  private static SafeMode.Fault toFault(CanIdRegistry.Conflict conflict) {
    return new SafeMode.Fault(
        SafeMode.Level.FATAL,
        "CAN bus \"" + conflict.bus() + "\"",
        "deviceId",
        Integer.toString(conflict.deviceId()),
        conflict.kind() == CanIdRegistry.Kind.ID_OUT_OF_RANGE
            ? CanIdRegistry.kMinDeviceId + ".." + CanIdRegistry.kMaxDeviceId
            : "one device per id per bus",
        conflict.describe(),
        SafeMode.Fault.kUnknownSite);
  }

  /** The name a component reports: a subsystem's own name, otherwise its simple class name. */
  private static String nameOf(Object component) {
    if (component instanceof Subsystem subsystem) {
      return subsystem.getName();
    }
    String simple = component.getClass().getSimpleName();
    return simple.isEmpty() ? component.getClass().getName() : simple;
  }
}

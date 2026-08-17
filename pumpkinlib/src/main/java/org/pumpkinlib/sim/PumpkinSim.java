package org.pumpkinlib.sim;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Volts;

import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Mass;
import edu.wpi.first.units.measure.MomentOfInertia;
import edu.wpi.first.wpilibj.simulation.BatterySim;
import edu.wpi.first.wpilibj.simulation.RoboRioSim;
import edu.wpi.first.wpilibj.simulation.SimDeviceSim;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.pumpkinlib.core.PumpkinException;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.spi.LifecycleHook;
import org.pumpkinlib.core.spi.MechanismGeometry;
import org.pumpkinlib.core.spi.MechanismGeometrySink;
import org.pumpkinlib.core.spi.SimMotorHandle;
import org.pumpkinlib.telemetry.PumpkinLog;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The simulation registry, the tick, and the battery. <b>Sim owns the plants; core only declares
 * them.</b>
 *
 * <h2>Sim-by-default, with no second code path (design/04 §7.1)</h2>
 *
 * <p>The single largest source of duplicated code in an FRC repository is the forty-line
 * {@code simulationPeriodic()} block per mechanism, and it contains a correctness trap CTRE documents
 * verbatim: rotor position and velocity are <b>pre</b>-gear-ratio while the physics model is
 * <b>post</b>-gear-ratio. Get it backwards and the vendor's onboard PID behaves nothing like reality,
 * which makes tuning in simulation worthless — and nothing tells you.
 *
 * <p>So PumpkinLib deletes the block. {@code Mechanism.simulationPeriodic()} does not exist (D18); a
 * mechanism declares a {@link MechanismGeometry} once, at registration, through
 * {@link MechanismGeometrySink}, and this class builds the plant, owns the tick, models the battery
 * and writes rotor state back through {@link SimMotorHandle}. Declaring a mass or a moment of inertia
 * in {@code SimConfig} is the only thing a team writes to get physics.
 *
 * <h2>How it gets ticked</h2>
 *
 * <p>{@link #hook()} is a {@link LifecycleHook} at priority {@value #kHookPriority} whose
 * {@code simulationTick} calls {@link #tick()}; {@code PumpkinLifecycle} runs that only when
 * {@link Platform#isSimulation()}, so it is structurally impossible for physics to run on a robot.
 * {@link #tick()} guards on {@code Platform.isSimulation()} a second time for a team driving it by
 * hand. Register the hook with {@code PumpkinRegistry.addAll(PumpkinSim.hook())} — or call
 * {@link #tick()} yourself from {@code simulationPeriodic()}; both are supported and both end in the
 * same method.
 *
 * <h2>Battery sag and brownout, on by default (design/04 §7.3)</h2>
 *
 * <p>Nothing in FRC models battery sag unless a team wires it themselves, so simulation never
 * reproduces the failure mode that actually loses matches. {@link #tick()} does it for free: it sums
 * every plant's current draw, runs it through WPILib's {@code BatterySim}, pushes the result into
 * {@code RoboRioSim} so {@code RobotController.getBatteryVoltage()} sags, clamps every device's applied
 * volts to that sagging bus, and raises a sticky warning below {@value #kBrownoutVolts} V.
 *
 * <p><b>{@code s_sims} is a {@code LinkedHashMap}, not a {@code HashMap} — guarantee G7.</b> The
 * battery aggregation sums a {@code double[]} whose order comes from that map, and floating-point
 * addition is not associative, so an unordered map would make simulation results differ run to run on
 * the last bits. That is the exact class of bug the lint's R4 warns team code about, and the library
 * has to hold itself to it first.
 *
 * <h2>Peak stall current is printed at boot</h2>
 *
 * <p>{@link #stressTest()} sums what every registered gearbox pulls stalled and asks {@code BatterySim}
 * what the bus would do. It runs once, at the first {@link #tick()} or at {@link #hook()}'s
 * {@code init()}, and prints. A team that has specified a four-NEO elevator drawing 724 A finds out on
 * a laptop in week two rather than at an event.
 */
public final class PumpkinSim {

  private PumpkinSim() {}

  /** The alert group simulation problems are filed under. */
  public static final String kAlertGroup = "Pumpkin/Sim";

  /** The telemetry namespace everything here publishes under. */
  public static final String kKeyPrefix = "Pumpkin/Sim/";

  /** Nominal simulated bus voltage, volts. */
  public static final double kNominalBusVolts = 12.0;

  /**
   * The roboRIO 2.0 stage-2 brownout threshold, volts.
   *
   * <p>Below this the controller sheds its actuator outputs. It is the number worth alerting on
   * because it is the one where the robot stops moving, not the one where the log looks untidy.
   */
  public static final double kBrownoutVolts = 6.75;

  /**
   * This hook's priority in {@code PumpkinLifecycle}'s ordered list.
   *
   * <p>Fifty: after telemetry (10) and tuning (30) so a tunable applied this cycle is already in the
   * device when the plant reads its volts, and before viz (70) so the visualiser draws the state the
   * physics just produced rather than last cycle's.
   */
  public static final int kHookPriority = 50;

  /** One registered plant, with its telemetry keys built once instead of once per cycle. */
  private record Registration(MechanismSim sim, String positionKey, String velocityKey,
      String currentKey, String voltsKey) {}

  /** G7: insertion-ordered, because the battery sum reads it and float addition is not associative. */
  private static final Map<String, Registration> s_sims = new LinkedHashMap<>();

  /** The one sink instance, so {@code PumpkinRegistry} can install it and compare it by identity. */
  private static final MechanismGeometrySink s_sink = PumpkinSim::declareGeometry;

  private static final LifecycleHook s_hook = new Hook();

  /** Reused across cycles; {@link #tick()} is on the 50 Hz path and must not allocate. */
  private static double[] s_currents = new double[0];

  private static double s_busVolts = kNominalBusVolts;
  private static double s_totalAmps;
  private static boolean s_printedBootReport;

  private static PumpkinAlert s_brownoutAlert;
  private static PumpkinAlert s_unattachedAlert;
  private static PumpkinAlert s_declarationAlert;

  // ================================================================================ registration

  /**
   * The D18 seam: hand this to whatever declares mechanisms and every declared geometry becomes a
   * plant.
   *
   * <p>{@code org.pumpkinlib.core.spi} names {@link MechanismGeometrySink} and does not name this
   * class, which is what keeps every arrow pointing into core (ArchUnit rule 9).
   *
   * @return the singleton sink; the same instance every call
   */
  public static MechanismGeometrySink sink() {
    return s_sink;
  }

  /**
   * The lifecycle hook that ticks physics.
   *
   * <p>Hand it to {@code PumpkinRegistry.addAll(...)} before creating the {@code PumpkinLifecycle}.
   * Its {@code init()} prints the boot report and its {@code simulationTick} is the tick;
   * {@code PumpkinLifecycle} only calls the latter in simulation.
   *
   * @return the singleton hook; the same instance every call
   */
  public static LifecycleHook hook() {
    return s_hook;
  }

  /**
   * Builds a plant from a declared geometry and registers it.
   *
   * @param geometry the declared plant
   * @return the registered sim, so a caller can attach a device or read state
   */
  public static synchronized MechanismSim declare(MechanismGeometry geometry) {
    return declare(geometry, null, null, 0.0);
  }

  /**
   * Builds a plant from a declared geometry and the mechanism's own converter.
   *
   * <p><b>Prefer this form.</b> Passing the {@link MechanismUnits} the {@code MotorIO} was built from
   * makes the rotor writeback go through the same object as every other conversion in the library, and
   * lets the plant report a geometry and a converter that disagree instead of silently simulating the
   * wrong mechanism.
   *
   * @param geometry the declared plant
   * @param units the mechanism's converter, or null
   * @return the registered sim
   */
  public static synchronized MechanismSim declare(
      MechanismGeometry geometry, MechanismUnits units) {
    return declare(geometry, units, null, 0.0);
  }

  /**
   * Builds a plant, wires it to a device, and registers it.
   *
   * @param geometry the declared plant
   * @param units the mechanism's converter, or null
   * @param handle the device's simulated state, or null to attach one later
   * @return the registered sim
   */
  public static synchronized MechanismSim declare(
      MechanismGeometry geometry, MechanismUnits units, SimMotorHandle handle) {
    return declare(geometry, units, handle, 0.0);
  }

  /**
   * Builds a plant, wires it to a device, adds sensor noise, and registers it.
   *
   * @param geometry the declared plant
   * @param units the mechanism's converter, or null
   * @param handle the device's simulated state, or null to attach one later
   * @param measurementStdDev the standard deviation of the noise added to the plant's outputs, in SI;
   *     zero for a noiseless plant. Non-zero is how a filter or a debounce is tested against a sensor
   *     that is not perfect.
   * @return the registered sim
   */
  public static synchronized MechanismSim declare(
      MechanismGeometry geometry,
      MechanismUnits units,
      SimMotorHandle handle,
      double measurementStdDev) {
    Objects.requireNonNull(geometry, "PumpkinSim.declare: geometry must not be null");
    WpilibPlantSim plant = new WpilibPlantSim(geometry, units, handle, measurementStdDev);
    register(geometry.name(), plant);
    return plant;
  }

  /**
   * Registers an already-built sim for automatic ticking and battery aggregation. Idempotent by name:
   * re-registering a name replaces what was there.
   *
   * <p>Replacement rather than rejection because the common cause is a test that constructs a
   * mechanism twice, and a duplicate-name exception there fails the <i>second</i> test with a message
   * about the first.
   *
   * @param name the mechanism's name, matching its telemetry namespace
   * @param sim the plant
   */
  public static synchronized void register(String name, MechanismSim sim) {
    Objects.requireNonNull(sim, "PumpkinSim.register: sim must not be null");
    String key =
        name == null || name.isBlank() ? "Mechanism" + (s_sims.size() + 1) : name.trim();
    String root = kKeyPrefix + key + "/";
    s_sims.put(
        key,
        new Registration(
            sim, root + "PositionSi", root + "VelocitySi", root + "CurrentAmps", root + "AppliedVolts"));
    s_currents = new double[s_sims.size()];
    s_printedBootReport = false;
  }

  /**
   * Wires a device to an already-registered plant.
   *
   * @param name the mechanism's name
   * @param handle the device's simulated state
   * @throws PumpkinException if no plant is registered under {@code name}, or if the registered plant
   *     is a hand-written {@link MechanismSim} that owns its own device
   */
  public static synchronized void attach(String name, SimMotorHandle handle) {
    Registration registration = s_sims.get(name);
    if (registration == null) {
      throw PumpkinException.of(
          "PumpkinSim.attach: mechanism \"" + name + "\"",
          "not registered",
          "a name previously passed to declare(...) or register(...): " + s_sims.keySet(),
          "declare the mechanism's geometry before attaching its device, or check the spelling — "
              + "the name must match the mechanism's telemetry namespace exactly.");
    }
    if (!(registration.sim() instanceof WpilibPlantSim plant)) {
      throw PumpkinException.of(
          "PumpkinSim.attach: mechanism \"" + name + "\"",
          registration.sim().getClass().getSimpleName(),
          "a plant built by PumpkinSim.declare(...)",
          "a hand-written MechanismSim owns whatever device it drives; wire the handle inside it "
              + "rather than through the registry.");
    }
    plant.attach(handle);
  }

  /**
   * Wires a vendor motor controller to an already-registered plant, detecting its type.
   *
   * <p>The gear ratio {@link SimMotors} needs is taken from the registered geometry, so the ratio the
   * simulation uses cannot drift from the ratio the mechanism declared.
   *
   * @param name the mechanism's name
   * @param motorController a {@code MotorIO}, a {@code SimMotorHandle}, or a vendor controller
   * @throws PumpkinException if the name is unknown or the controller type is not supported
   */
  public static synchronized void attach(String name, Object motorController) {
    Registration registration = s_sims.get(name);
    double ratio =
        registration != null && registration.sim() instanceof WpilibPlantSim plant
            ? plant.geometry().rotorPerOutput()
            : 1.0;
    attach(name, SimMotors.of(motorController, ratio));
  }

  /**
   * Unregisters a mechanism and closes its plant.
   *
   * @param name the mechanism's name
   * @return true if something was removed
   */
  public static synchronized boolean remove(String name) {
    Registration removed = s_sims.remove(name);
    if (removed == null) {
      return false;
    }
    removed.sim().close();
    s_currents = new double[s_sims.size()];
    return true;
  }

  /**
   * One registered plant.
   *
   * @param name the mechanism's name
   * @return the sim, or empty if nothing is registered under that name
   */
  public static synchronized Optional<MechanismSim> get(String name) {
    Registration registration = s_sims.get(name);
    return registration == null ? Optional.empty() : Optional.of(registration.sim());
  }

  /**
   * Every registered plant, in registration order.
   *
   * @return an unmodifiable insertion-ordered view; G7 again — a caller iterating this must get the
   *     same order every run
   */
  public static synchronized Map<String, MechanismSim> registered() {
    Map<String, MechanismSim> out = new LinkedHashMap<>();
    for (Map.Entry<String, Registration> e : s_sims.entrySet()) {
      out.put(e.getKey(), e.getValue().sim());
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * The raw WPILib model behind one mechanism — {@code design/04} §0's "the escape hatch is always
   * visible".
   *
   * @param name the mechanism's name
   * @return the {@code ElevatorSim} / {@code SingleJointedArmSim} / {@code FlywheelSim} /
   *     {@code DCMotorSim}, or empty if the name is unknown
   */
  public static synchronized Optional<Object> raw(String name) {
    return get(name).map(MechanismSim::raw);
  }

  // ======================================================================================== tick

  /**
   * Advances every plant, models the battery, and publishes. <b>Never runs on real hardware.</b>
   *
   * <p>{@code Platform.isSimulation()}, not {@code RobotBase.isSimulation()}: ArchUnit rule 2 confines
   * the year-volatile WPILib types to {@code org.pumpkinlib.core.compat}, and this class is in
   * {@code org.pumpkinlib.sim}. Same call, one hop through the facade.
   *
   * <p>Order, and why: every plant is stepped first, so the currents summed are this cycle's; the bus
   * voltage that clamped this cycle's applied volts is therefore <i>last</i> cycle's. That one-cycle
   * lag is deliberate — computing them simultaneously is an algebraic loop, and every real closed loop
   * already has exactly this lag.
   */
  public static synchronized void tick() {
    if (!Platform.isSimulation()) {
      return;
    }
    if (!s_printedBootReport) {
      printBootReport();
    }

    double dt = Clock.dt();
    double volts = stepPlants(dt);
    RoboRioSim.setVInVoltage(volts);

    // The ONLY call site of the field world's tick, adapter or not. maple-sim's own documentation
    // warns that its arena consumes roboRIO resources, so keeping the call here — inside the
    // Platform.isSimulation() guard — makes "never on hardware" structural rather than a convention.
    PumpkinFieldSim.tick(dt);

    PumpkinLog.log(kKeyPrefix + "BatteryVolts", volts, Volts);
    PumpkinLog.log(kKeyPrefix + "TotalCurrentAmps", s_totalAmps, Amps);
    for (Registration registration : s_sims.values()) {
      MechanismSim sim = registration.sim();
      PumpkinLog.log(registration.positionKey(), sim.positionSi());
      PumpkinLog.log(registration.velocityKey(), sim.velocitySi());
      PumpkinLog.log(registration.currentKey(), sim.currentDrawAmps(), Amps);
      PumpkinLog.log(registration.voltsKey(), sim.appliedVolts(), Volts);
    }

    brownoutAlert().set(volts < kBrownoutVolts);
  }

  /**
   * The physics half of {@link #tick()}: steps every plant, sums the currents, and returns the loaded
   * bus voltage. Touches no HAL and no telemetry.
   *
   * <p><b>Why this is public and separate.</b> {@link #tick()} calls {@code RoboRioSim} and
   * {@code Platform.isSimulation()}, both of which need WPILib's JNI natives; this method is pure Java
   * arithmetic over WPILib's plant models. Splitting them is what lets the physics be unit-tested on a
   * bare JVM in CI — the same property ArchUnit rule 8 buys for {@code org.pumpkinlib.pure}, and the
   * reason the library's own test suite can run on a machine with no natives installed.
   *
   * @param dtSeconds the timestep
   * @return the simulated bus voltage under this cycle's load
   */
  public static synchronized double stepPlants(double dtSeconds) {
    double dt = Double.isFinite(dtSeconds) && dtSeconds > 0.0 ? dtSeconds : Clock.dt();
    if (s_currents.length != s_sims.size()) {
      s_currents = new double[s_sims.size()];
    }

    int i = 0;
    double total = 0.0;
    for (Registration registration : s_sims.values()) {
      MechanismSim sim = registration.sim();
      sim.setBusVolts(s_busVolts);
      sim.update(dt);
      double amps = sim.currentDrawAmps();
      s_currents[i] = Double.isFinite(amps) ? amps : 0.0;
      total += s_currents[i];
      i++;
    }
    s_totalAmps = total;
    s_busVolts = BatterySim.calculateDefaultBatteryLoadedVoltage(s_currents);
    return s_busVolts;
  }

  /**
   * The simulated bus voltage after the last step.
   *
   * @return volts
   */
  public static synchronized double busVolts() {
    return s_busVolts;
  }

  /**
   * The total current every plant drew on the last step.
   *
   * @return amps
   */
  public static synchronized double totalCurrentAmps() {
    return s_totalAmps;
  }

  /**
   * Whether the simulated bus is below the brownout threshold.
   *
   * @return true when the roboRIO would be shedding outputs
   */
  public static synchronized boolean isBrownedOut() {
    return s_busVolts < kBrownoutVolts;
  }

  // ================================================================================ stress test

  /**
   * Runs every registered mechanism at full output simultaneously and reports the predicted battery
   * sag. Find power problems before the field does.
   *
   * <p>Analytic, not stepped: stall current is the motor curve at zero speed, so this needs no
   * timestep, no hardware and no natives.
   *
   * @return the report
   */
  public static synchronized StressReport stressTest() {
    List<StressReport.Entry> entries = new ArrayList<>();
    double[] currents = new double[s_sims.size()];
    double peak = 0.0;
    int i = 0;
    for (Map.Entry<String, Registration> e : s_sims.entrySet()) {
      MechanismSim sim = e.getValue().sim();
      double stall = sim.stallCurrentAmps();
      if (!Double.isFinite(stall)) {
        stall = 0.0;
      }
      DCMotor gearbox =
          sim instanceof WpilibPlantSim plant ? plant.geometry().gearbox() : null;
      MechanismGeometry.Kind kind =
          sim instanceof WpilibPlantSim plant
              ? plant.geometry().kind()
              : MechanismGeometry.Kind.SIMPLE;
      entries.add(
          new StressReport.Entry(
              e.getKey(),
              kind,
              stall,
              gearbox == null ? Double.NaN : gearbox.stallTorqueNewtonMeters,
              gearbox == null ? Double.NaN : gearbox.freeSpeedRadPerSec));
      currents[i++] = stall;
      peak += stall;
    }
    double sagged = BatterySim.calculateDefaultBatteryLoadedVoltage(currents);
    return new StressReport(entries, peak, sagged, sagged < kBrownoutVolts);
  }

  /**
   * Prints the boot report — every plant's derivation, every declaration problem, and the peak
   * simulated stall current — and raises the alerts that go with it.
   *
   * <p>Called once, from {@link #hook()}'s {@code init()} or from the first {@link #tick()}, and again
   * whenever something new registers. Printing to {@code System.out} rather than only to the log is
   * deliberate: a student running {@code simulateJava} reads the console, and a boot warning nobody
   * reads is not a warning.
   */
  public static synchronized void printBootReport() {
    s_printedBootReport = true;
    StringBuilder sb = new StringBuilder();
    sb.append("PumpkinSim: ").append(s_sims.size()).append(" simulated mechanism(s)\n");
    List<String> problems = new ArrayList<>();
    List<String> unattached = new ArrayList<>();
    for (Map.Entry<String, Registration> e : s_sims.entrySet()) {
      sb.append(e.getValue().sim().describe()).append('\n');
      if (e.getValue().sim() instanceof WpilibPlantSim plant) {
        for (String problem : plant.problems()) {
          problems.add(e.getKey() + ": " + problem);
        }
        if (!plant.isAttached()) {
          unattached.add(e.getKey());
        }
      }
    }
    StressReport report = stressTest();
    sb.append(report.describe());
    System.out.println(sb);

    PumpkinLog.log(kKeyPrefix + "PeakStallAmps", report.peakStallAmps(), Amps);
    PumpkinLog.log(kKeyPrefix + "StressTest", report.describe());

    PumpkinFieldSim.publishAvailability();
    declarationAlert()
        .text(
            problems.isEmpty()
                ? "no simulation declaration problems"
                : "SIM_DECLARATION: " + String.join(" | ", problems))
        .set(!problems.isEmpty());
    unattachedAlert()
        .text(
            unattached.isEmpty()
                ? "every simulated mechanism has a device"
                : "SIM_NO_DEVICE: "
                    + String.join(", ", unattached)
                    + " declared geometry but no motor controller is wired to it, so "
                    + (unattached.size() == 1 ? "it" : "they")
                    + " will not move in simulateJava. Fix: PumpkinSim.attach(name, motor), or let "
                    + "the mechanism pass its MotorIO's simHandle() at declaration.")
        .set(!unattached.isEmpty());
  }

  // ============================================================================ device inspection

  /**
   * Prints every registered {@code SimDevice} key and every field on it.
   *
   * <p>The escape hatch for a vendor with no dedicated simulation class. {@code SimDeviceSim} keys are
   * stringly-typed and the required prefix is <b>hidden in the SimGUI by default</b> (it is behind
   * "Show prefix"), so guessing them wastes evenings. This prints the exact strings
   * {@link SimMotors#ofSimDevice} wants.
   */
  public static void dumpDevices() {
    StringBuilder sb = new StringBuilder("PumpkinSim.dumpDevices — every registered SimDevice\n");
    var devices = SimDeviceSim.enumerateDevices("");
    if (devices.length == 0) {
      sb.append("  (none — nothing has constructed a SimDevice yet. Construct your devices first, "
          + "then call this.)");
      System.out.println(sb);
      return;
    }
    for (var device : devices) {
      sb.append("  \"").append(device.name).append("\"\n");
      SimDeviceSim sim = new SimDeviceSim(device.name);
      for (var value : sim.enumerateValues()) {
        sb.append("      ").append(value.name).append('\n');
      }
    }
    System.out.println(sb);
  }

  // ================================================================================== inspection

  /**
   * A one-block summary of the simulation's state, for the boot dump and {@code pumpkin doctor}.
   *
   * @return a multi-line block, no trailing newline
   */
  public static synchronized String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "PumpkinSim: %d mechanism(s), bus %.2f V, load %.1f A%s%n",
            s_sims.size(),
            s_busVolts,
            s_totalAmps,
            isBrownedOut() ? " — BROWNED OUT" : ""));
    for (Map.Entry<String, Registration> e : s_sims.entrySet()) {
      sb.append(e.getValue().sim().describe()).append('\n');
    }
    sb.append(PumpkinFieldSim.describe());
    return sb.toString().stripTrailing();
  }

  /**
   * Drops every registered plant and restores the defaults.
   *
   * <p>For {@code @BeforeEach}. Static state that survives between tests is how a suite becomes
   * order-dependent, and a plant left registered by test A makes test B's battery sag for no visible
   * reason.
   */
  public static synchronized void resetForTest() {
    for (Registration registration : s_sims.values()) {
      registration.sim().close();
    }
    s_sims.clear();
    s_currents = new double[0];
    s_busVolts = kNominalBusVolts;
    s_totalAmps = 0.0;
    s_printedBootReport = false;

    // Drop the lazily-built alerts too.
    //
    // These are cached in statics, and a test that calls AlertRegistry.resetForTest() alongside
    // this method leaves them pointing at a registry that no longer exists. The next boot report
    // then raises a perfectly correct alert into nowhere: AlertRegistry.all() cannot see it, so a
    // test asserting "the declaration problem is reported" fails while the library is behaving
    // correctly — and, worse, a test asserting the opposite would PASS. Nulling them here means the
    // next call to declarationAlert() re-registers against whichever registry is current.
    s_brownoutAlert = null;
    s_unattachedAlert = null;
    s_declarationAlert = null;
  }

  // ==================================================================================== builders

  /**
   * A builder for a carriage on a rail or a cascade — an {@code ElevatorSim}.
   *
   * @return the builder
   */
  public static ElevatorBuilder elevator() {
    return new ElevatorBuilder();
  }

  /**
   * A builder for an arm or a pivot — a {@code SingleJointedArmSim}.
   *
   * @return the builder
   */
  public static ArmBuilder arm() {
    return new ArmBuilder();
  }

  /**
   * A builder for a free-spinning wheel with inertia and no position limits — a {@code FlywheelSim}.
   *
   * @return the builder
   */
  public static FlywheelBuilder flywheel() {
    return new FlywheelBuilder();
  }

  /**
   * A builder for anything else driven open-loop — a {@code DCMotorSim}, no gravity, no limits.
   *
   * @return the builder
   */
  public static RollerBuilder roller() {
    return new RollerBuilder();
  }

  /**
   * Builds and registers an {@code ElevatorSim} from typed measures.
   *
   * <p><b>Typed, not bare doubles.</b> {@code .drumRadius(Inches.of(1.0))} cannot be confused with
   * metres and {@code .carriageMass(Kilograms.of(6.0))} cannot be confused with pounds, so a wrong
   * unit is a compile error rather than a 39.37&times; runtime surprise.
   *
   * <p>This builder is a convenience for a team simulating something that is not a declared
   * {@code Mechanism} — a prototype, a test fixture. A real mechanism does not use it: it declares a
   * {@code SimConfig} and its geometry arrives through {@link #sink()}. Both paths end in the same
   * {@link MechanismGeometry} and the same plant, which is what "no second code path" means.
   */
  public static final class ElevatorBuilder {
    private String m_name = "Elevator";
    private DCMotor m_motor = DCMotor.getKrakenX60Foc(1);
    private Object m_attachedTo;
    private double m_gearing = 1.0;
    private Distance m_drumRadius;
    private Mass m_carriageMass;
    private Distance m_min;
    private Distance m_max;
    private Distance m_start;
    private boolean m_gravity = true;
    private double m_stdDev;

    private ElevatorBuilder() {}

    /**
     * Names the mechanism, which is also its telemetry namespace.
     *
     * @param name the name
     * @return this
     */
    public ElevatorBuilder named(String name) {
      m_name = name;
      return this;
    }

    /**
     * The gearbox: the motor model times the motor count.
     *
     * @param motor e.g. {@code DCMotor.getKrakenX60Foc(2)}
     * @return this
     */
    public ElevatorBuilder motor(DCMotor motor) {
      m_motor = motor;
      return this;
    }

    /**
     * The device this plant drives.
     *
     * @param motorController a {@code MotorIO}, a {@code SimMotorHandle}, or a vendor controller
     * @return this
     */
    public ElevatorBuilder attachedTo(Object motorController) {
      m_attachedTo = motorController;
      return this;
    }

    /**
     * Rotor rotations per drum rotation. Numbers greater than one are reductions.
     *
     * @param rotorPerOutput the ratio
     * @return this
     */
    public ElevatorBuilder gearing(double rotorPerOutput) {
      m_gearing = rotorPerOutput;
      return this;
    }

    /**
     * The effective drum radius — the pitch radius times the number of cascade stages.
     *
     * @param radius the radius
     * @return this
     */
    public ElevatorBuilder drumRadius(Distance radius) {
      m_drumRadius = radius;
      return this;
    }

    /**
     * Everything that moves: the carriage, whatever is bolted to it, the game piece.
     *
     * @param mass the mass
     * @return this
     */
    public ElevatorBuilder carriageMass(Mass mass) {
      m_carriageMass = mass;
      return this;
    }

    /**
     * The travel range. Simulation has no hard stops, so these are what stop the plant.
     *
     * @param min the bottom
     * @param max the top
     * @return this
     */
    public ElevatorBuilder travel(Distance min, Distance max) {
      m_min = min;
      m_max = max;
      return this;
    }

    /**
     * Where the carriage sits when the simulator opens.
     *
     * @param height the starting height
     * @return this
     */
    public ElevatorBuilder startingHeight(Distance height) {
      m_start = height;
      return this;
    }

    /**
     * Whether the plant applies gravity. Leave it on unless the rail is horizontal.
     *
     * @param value true to model gravity
     * @return this
     */
    public ElevatorBuilder simulateGravity(boolean value) {
      m_gravity = value;
      return this;
    }

    /**
     * Noise added to the plant's outputs, in metres — how a filter is tested against an imperfect
     * sensor.
     *
     * @param metres the standard deviation
     * @return this
     */
    public ElevatorBuilder measurementStdDev(double metres) {
      m_stdDev = metres;
      return this;
    }

    /**
     * Builds the plant and registers it.
     *
     * @return the registered sim
     */
    public MechanismSim build() {
      double radius = Builders.metersOr(m_drumRadius, Double.NaN);
      double siPerRotation = Double.isFinite(radius) ? 2.0 * Math.PI * radius : Double.NaN;
      MechanismGeometry geometry =
          new MechanismGeometry(
              m_name,
              MechanismGeometry.Kind.LINEAR,
              m_motor,
              m_gearing,
              siPerRotation,
              radius,
              Builders.kilogramsOr(m_carriageMass, Double.NaN),
              Double.NaN,
              Double.NaN,
              Builders.metersOr(m_min, 0.0),
              Builders.metersOr(m_max, Double.NaN),
              Builders.metersOr(m_start, 0.0),
              m_gravity);
      return Builders.finish(geometry, m_attachedTo, m_gearing, m_stdDev);
    }
  }

  /** Builds and registers a {@code SingleJointedArmSim} from typed measures. */
  public static final class ArmBuilder {
    private String m_name = "Arm";
    private DCMotor m_motor = DCMotor.getKrakenX60Foc(1);
    private Object m_attachedTo;
    private double m_gearing = 1.0;
    private Distance m_armLength;
    private Mass m_mass;
    private MomentOfInertia m_moi;
    private Angle m_min;
    private Angle m_max;
    private Angle m_start;
    private boolean m_gravity = true;
    private double m_stdDev;

    private ArmBuilder() {}

    /**
     * Names the mechanism, which is also its telemetry namespace.
     *
     * @param name the name
     * @return this
     */
    public ArmBuilder named(String name) {
      m_name = name;
      return this;
    }

    /**
     * The gearbox: the motor model times the motor count.
     *
     * @param motor e.g. {@code DCMotor.getNEO(2)}
     * @return this
     */
    public ArmBuilder motor(DCMotor motor) {
      m_motor = motor;
      return this;
    }

    /**
     * The device this plant drives.
     *
     * @param motorController a {@code MotorIO}, a {@code SimMotorHandle}, or a vendor controller
     * @return this
     */
    public ArmBuilder attachedTo(Object motorController) {
      m_attachedTo = motorController;
      return this;
    }

    /**
     * Rotor rotations per joint rotation.
     *
     * @param rotorPerOutput the ratio
     * @return this
     */
    public ArmBuilder gearing(double rotorPerOutput) {
      m_gearing = rotorPerOutput;
      return this;
    }

    /**
     * The distance from the joint to the centre of mass — what gravity torque is computed from.
     *
     * @param length the length
     * @return this
     */
    public ArmBuilder armLength(Distance length) {
      m_armLength = length;
      return this;
    }

    /**
     * The arm's mass. Used only to estimate the moment of inertia when none is given.
     *
     * @param mass the mass
     * @return this
     */
    public ArmBuilder mass(Mass mass) {
      m_mass = mass;
      return this;
    }

    /**
     * The moment of inertia about the joint. Preferred over {@link #mass(Mass)} when CAD knows it.
     *
     * @param moi the moment of inertia
     * @return this
     */
    public ArmBuilder momentOfInertia(MomentOfInertia moi) {
      m_moi = moi;
      return this;
    }

    /**
     * The travel range.
     *
     * @param min the lower limit
     * @param max the upper limit
     * @return this
     */
    public ArmBuilder travel(Angle min, Angle max) {
      m_min = min;
      m_max = max;
      return this;
    }

    /**
     * The angle the arm sits at when the simulator opens.
     *
     * @param angle the starting angle
     * @return this
     */
    public ArmBuilder startingAngle(Angle angle) {
      m_start = angle;
      return this;
    }

    /**
     * Whether the plant applies gravity. False for a turret, whose axis is vertical.
     *
     * @param value true to model gravity
     * @return this
     */
    public ArmBuilder simulateGravity(boolean value) {
      m_gravity = value;
      return this;
    }

    /**
     * Noise added to the plant's outputs, in radians.
     *
     * @param radians the standard deviation
     * @return this
     */
    public ArmBuilder measurementStdDev(double radians) {
      m_stdDev = radians;
      return this;
    }

    /**
     * Builds the plant and registers it.
     *
     * @return the registered sim
     */
    public MechanismSim build() {
      double length = Builders.metersOr(m_armLength, Double.NaN);
      double moi = Builders.moiOr(m_moi, Double.NaN);
      if (!Double.isFinite(moi) && m_mass != null && Double.isFinite(length)) {
        // The uniform-bar approximation, m*L^2/3, taken from WPILib's own estimator so the number
        // PumpkinLib simulates with is the number SingleJointedArmSim was written against.
        moi = edu.wpi.first.wpilibj.simulation.SingleJointedArmSim.estimateMOI(
            length, Builders.kilogramsOr(m_mass, 0.0));
      }
      MechanismGeometry geometry =
          new MechanismGeometry(
              m_name,
              MechanismGeometry.Kind.ROTARY,
              m_motor,
              m_gearing,
              2.0 * Math.PI,
              Double.NaN,
              Builders.kilogramsOr(m_mass, Double.NaN),
              moi,
              length,
              Builders.radiansOr(m_min, -Math.PI),
              Builders.radiansOr(m_max, Math.PI),
              Builders.radiansOr(m_start, 0.0),
              m_gravity);
      return Builders.finish(geometry, m_attachedTo, m_gearing, m_stdDev);
    }
  }

  /** Builds and registers a {@code FlywheelSim} from typed measures. */
  public static final class FlywheelBuilder {
    private String m_name = "Flywheel";
    private DCMotor m_motor = DCMotor.getKrakenX60Foc(1);
    private Object m_attachedTo;
    private double m_gearing = 1.0;
    private MomentOfInertia m_moi;
    private double m_stdDev;

    private FlywheelBuilder() {}

    /**
     * Names the mechanism, which is also its telemetry namespace.
     *
     * @param name the name
     * @return this
     */
    public FlywheelBuilder named(String name) {
      m_name = name;
      return this;
    }

    /**
     * The gearbox: the motor model times the motor count.
     *
     * @param motor e.g. {@code DCMotor.getKrakenX60(2)}
     * @return this
     */
    public FlywheelBuilder motor(DCMotor motor) {
      m_motor = motor;
      return this;
    }

    /**
     * The device this plant drives.
     *
     * @param motorController a {@code MotorIO}, a {@code SimMotorHandle}, or a vendor controller
     * @return this
     */
    public FlywheelBuilder attachedTo(Object motorController) {
      m_attachedTo = motorController;
      return this;
    }

    /**
     * Rotor rotations per wheel rotation.
     *
     * @param rotorPerOutput the ratio
     * @return this
     */
    public FlywheelBuilder gearing(double rotorPerOutput) {
      m_gearing = rotorPerOutput;
      return this;
    }

    /**
     * The moment of inertia of the wheel and everything geared to it.
     *
     * @param moi the moment of inertia
     * @return this
     */
    public FlywheelBuilder momentOfInertia(MomentOfInertia moi) {
      m_moi = moi;
      return this;
    }

    /**
     * Noise added to the plant's output, in radians per second.
     *
     * @param radiansPerSecond the standard deviation
     * @return this
     */
    public FlywheelBuilder measurementStdDev(double radiansPerSecond) {
      m_stdDev = radiansPerSecond;
      return this;
    }

    /**
     * Builds the plant and registers it.
     *
     * @return the registered sim
     */
    public MechanismSim build() {
      MechanismGeometry geometry =
          new MechanismGeometry(
              m_name,
              MechanismGeometry.Kind.FLYWHEEL,
              m_motor,
              m_gearing,
              2.0 * Math.PI,
              Double.NaN,
              Double.NaN,
              Builders.moiOr(m_moi, Double.NaN),
              Double.NaN,
              Double.NEGATIVE_INFINITY,
              Double.POSITIVE_INFINITY,
              0.0,
              false);
      return Builders.finish(geometry, m_attachedTo, m_gearing, m_stdDev);
    }
  }

  /** Builds and registers a {@code DCMotorSim} — no gravity, no limits. */
  public static final class RollerBuilder {
    private String m_name = "Roller";
    private DCMotor m_motor = DCMotor.getNEO(1);
    private Object m_attachedTo;
    private double m_gearing = 1.0;
    private MomentOfInertia m_moi;
    private double m_stdDev;

    private RollerBuilder() {}

    /**
     * Names the mechanism, which is also its telemetry namespace.
     *
     * @param name the name
     * @return this
     */
    public RollerBuilder named(String name) {
      m_name = name;
      return this;
    }

    /**
     * The gearbox: the motor model times the motor count.
     *
     * @param motor e.g. {@code DCMotor.getNeo550(1)}
     * @return this
     */
    public RollerBuilder motor(DCMotor motor) {
      m_motor = motor;
      return this;
    }

    /**
     * The device this plant drives.
     *
     * @param motorController a {@code MotorIO}, a {@code SimMotorHandle}, or a vendor controller
     * @return this
     */
    public RollerBuilder attachedTo(Object motorController) {
      m_attachedTo = motorController;
      return this;
    }

    /**
     * Rotor rotations per roller rotation.
     *
     * @param rotorPerOutput the ratio
     * @return this
     */
    public RollerBuilder gearing(double rotorPerOutput) {
      m_gearing = rotorPerOutput;
      return this;
    }

    /**
     * The moment of inertia of the roller. Omitted, a documented stand-in is used and
     * {@code describe()} says so.
     *
     * @param moi the moment of inertia
     * @return this
     */
    public RollerBuilder momentOfInertia(MomentOfInertia moi) {
      m_moi = moi;
      return this;
    }

    /**
     * Noise added to the plant's outputs, in radians.
     *
     * @param radians the standard deviation
     * @return this
     */
    public RollerBuilder measurementStdDev(double radians) {
      m_stdDev = radians;
      return this;
    }

    /**
     * Builds the plant and registers it.
     *
     * @return the registered sim
     */
    public MechanismSim build() {
      MechanismGeometry geometry =
          new MechanismGeometry(
              m_name,
              MechanismGeometry.Kind.SIMPLE,
              m_motor,
              m_gearing,
              2.0 * Math.PI,
              Double.NaN,
              Double.NaN,
              Builders.moiOr(m_moi, Double.NaN),
              Double.NaN,
              Double.NEGATIVE_INFINITY,
              Double.POSITIVE_INFINITY,
              0.0,
              false);
      return Builders.finish(geometry, m_attachedTo, m_gearing, m_stdDev);
    }
  }

  /** The four builders' shared tail, so "declare then register" exists once. */
  private static final class Builders {
    private Builders() {}

    static MechanismSim finish(
        MechanismGeometry geometry, Object attachedTo, double ratio, double stdDev) {
      SimMotorHandle handle = attachedTo == null ? null : SimMotors.of(attachedTo, ratio);
      return declare(geometry, null, handle, stdDev);
    }

    static double metersOr(Distance d, double fallback) {
      return d == null ? fallback : d.in(edu.wpi.first.units.Units.Meters);
    }

    static double radiansOr(Angle a, double fallback) {
      return a == null ? fallback : a.in(edu.wpi.first.units.Units.Radians);
    }

    static double kilogramsOr(Mass m, double fallback) {
      return m == null ? fallback : m.in(edu.wpi.first.units.Units.Kilograms);
    }

    static double moiOr(MomentOfInertia moi, double fallback) {
      return moi == null ? fallback : moi.in(edu.wpi.first.units.Units.KilogramSquareMeters);
    }
  }

  // ==================================================================================== internals

  /** The {@link MechanismGeometrySink} body, as a method so {@link #s_sink} can be a method ref. */
  private static void declareGeometry(MechanismGeometry geometry) {
    declare(geometry);
  }

  /**
   * The brownout alert, built on first use rather than at class initialisation.
   *
   * <p>{@code design/04} §7.3 says "constructed once, at class init, NOT per cycle", and the reason it
   * gives — {@code Alerts.warning(...)} allocates a handle, so allocating one per cycle is a real cost
   * on a roboRIO — is satisfied by any once-only construction. Class initialisation specifically is
   * not, because a WPILib {@code Alert} needs the JNI natives: constructing one in a static
   * initialiser would make this whole class unloadable on a machine with no natives, and that machine
   * is CI. Lazily once is once.
   *
   * @return the singleton alert
   */
  private static PumpkinAlert brownoutAlert() {
    if (s_brownoutAlert == null) {
      s_brownoutAlert =
          Alerts.warning(
                  kAlertGroup,
                  "SIM_BROWNOUT: simulated bus voltage fell below "
                      + kBrownoutVolts
                      + " V, which is where a roboRIO 2.0 sheds its actuator outputs. Fix: lower the "
                      + "supply current limits, or stop commanding every mechanism at once.",
                  // Simulation-only, so it can never block a real match — but it is exactly the
                  // finding that should stop a team leaving the pit.
                  MatchImpact.PIT_ONLY)
              .sticky(true);
    }
    return s_brownoutAlert;
  }

  /** The "declared but nothing wired to it" alert, built on first use. See {@link #brownoutAlert()}. */
  private static PumpkinAlert unattachedAlert() {
    if (s_unattachedAlert == null) {
      s_unattachedAlert =
          Alerts.warning(kAlertGroup, "SIM_NO_DEVICE", MatchImpact.PIT_ONLY);
    }
    return s_unattachedAlert;
  }

  /** The "the declaration does not add up" alert, built on first use. See {@link #brownoutAlert()}. */
  private static PumpkinAlert declarationAlert() {
    if (s_declarationAlert == null) {
      s_declarationAlert =
          Alerts.error(kAlertGroup, "SIM_DECLARATION", MatchImpact.PIT_ONLY);
    }
    return s_declarationAlert;
  }

  /** The lifecycle hook, as a named class so {@code describe()} output identifies it. */
  private static final class Hook implements LifecycleHook {

    @Override
    public String name() {
      return "PumpkinSim";
    }

    @Override
    public int priority() {
      return kHookPriority;
    }

    @Override
    public void init() {
      if (Platform.isSimulation()) {
        printBootReport();
      }
    }

    @Override
    public void simulationTick(double dtSeconds) {
      tick();
    }

    @Override
    public void close() {
      resetForTest();
    }
  }
}

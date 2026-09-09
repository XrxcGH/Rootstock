package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.DegreesPerSecond;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.MetersPerSecond;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.config.MotorGroup;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.SafeMode;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.diag.RootstockTracer;
import org.rootstock.core.health.FaultCollector;
import org.rootstock.core.health.HealthSource;
import org.rootstock.core.health.builtin.LoopTimeMonitor;
import org.rootstock.core.selftest.SelfTestRoutine;
import org.rootstock.core.selftest.SelfTestable;
import org.rootstock.core.spi.MechanismGeometry;
import org.rootstock.core.spi.Tier;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorInputs;
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.TelemetrySource;
import org.rootstock.telemetry.schema.MechanismSchema;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.SiDomain;

/**
 * The base every Rootstock mechanism extends — and one of exactly two abstract classes the
 * architecture rules permit in a public API, because a team subclasses a mechanism but never
 * subclasses a command.
 *
 * <h2>It implements {@code Subsystem}, the INTERFACE, not {@code SubsystemBase}, the class</h2>
 *
 * <p>That is deliberate, and it is what lets one library serve both house styles found in the
 * surveyed repositories:
 *
 * <ul>
 *   <li><b>Command-based.</b> Call {@link #registerWithScheduler()}. The {@code CommandScheduler}
 *       then drives {@link #periodic()}, command factories and requirements work normally, and
 *       {@code setDefaultCommand} works.
 *   <li><b>State-based.</b> Do <i>not</i> register. Call {@link #periodic()} yourself from your
 *       superstructure or {@code Robot}. Nothing in the {@code CommandScheduler} is involved:
 *       WPILib's {@code Subsystem} interface does not self-register — only {@code SubsystemBase}'s
 *       constructor does — so implementing it costs zero bytes and zero behaviour when unregistered.
 * </ul>
 *
 * <p>It is also the seam for the 2027 Commands v3 line, whose core noun is literally "Mechanism".
 * Inheriting {@code SubsystemBase} would spend the team's single superclass slot and block that swap.
 *
 * <h2>Five interfaces, one object, one registration call</h2>
 *
 * <p>A mechanism is simultaneously a {@link Subsystem}, a {@link TelemetrySource}, a {@link
 * HealthSource}, a {@link SelfTestable} and a {@link TuningTarget}. That is not interface soup: it is
 * what makes {@code RootstockRegistry.addAll(m_elevator, m_arm, m_intake)} the <i>only</i> registration
 * call a team writes, because routing is by {@code instanceof} and never by base class. Every one of
 * those five is an interface precisely so that a team with hand-rolled subsystems gets the same
 * routing without inheriting anything.
 *
 * <h2>{@link #periodic()} is final and wrapped in {@code try/catch(Throwable)}</h2>
 *
 * <p>This is the single most important property of this class, and it is why {@code periodic()} is
 * {@code final} here while subclasses implement {@link #onPeriodic()} instead. <b>One mechanism
 * failing must degrade that mechanism, not the robot.</b> A mechanism that throws mid-match raises
 * {@code <name>/periodic-threw}, is commanded neutral, is reported as an {@code ERROR} fault by
 * {@link #pollHealth(FaultCollector)}, and the scheduler keeps running so the drivetrain still
 * drives. Making the wrapper the base class's job rather than a rule every subclass must remember is
 * the difference between a guarantee and a convention; a bytecode test asserts the handler is here.
 *
 * <h2>{@link #simulationPeriodic()} is a no-op, on purpose</h2>
 *
 * <p>CORE <i>declares</i> a {@link MechanismGeometry}; {@code RootstockSim} owns plant construction and
 * the simulation tick. See {@link #simulationPeriodic()} for why an earlier design that put the plant
 * here could not have worked.
 *
 * <h2>Nothing in this package throws outside construction</h2>
 *
 * <p>Constructors and static factories may throw — that is boot, and a fatal configuration error is
 * <i>supposed</i> to stop the robot before it moves. Everything reachable from {@code periodic()}
 * reports instead: an alert, a fault, a neutral output. An architecture rule enforces it over
 * bytecode.
 */
public abstract class Mechanism
    implements Subsystem, TelemetrySource, HealthSource, SelfTestable, TuningTarget {

  /** The relative key CORE's one always-declared extra publishes under. */
  public static final String kDeviceResetCountKey = "DeviceResetCount";

  /** Stator current above which a motor that is not moving is presumed stalled, in amps. */
  public static final double kDefaultStallCurrentAmps = 60.0;

  /** Output-shaft speed below which a motor drawing stall current is presumed not moving, in rps. */
  public static final double kDefaultStallVelocityRps = 0.05;

  /** Device temperature above which a warning is raised, in degrees Celsius. */
  public static final double kDefaultOverTemperatureCelsius = 80.0;

  /** This mechanism's name — the log key segment, the health name and the tuning namespace. */
  protected final String m_name;

  /** The gearing and axis this mechanism's user units are derived from. */
  protected final MechanismUnits m_units;

  /** The hardware seam. Public escape hatch via {@link #io()}; used directly by subclasses. */
  protected final MotorIO m_io;

  /** Filled in place once per loop by {@link #periodic()} and replayed byte-for-byte. */
  protected final MotorInputs m_inputs = new MotorInputs();

  /**
   * The absolute log prefix, {@code "Rootstock/<name>"}, precomputed once.
   *
   * <p>Built in the constructor rather than concatenated in {@code periodic()}: four mechanisms
   * building six keys each at 50 Hz is 1,200 {@code String} allocations per second, which is the
   * opposite of the "zero bytes allocated in periodic() after warmup" constraint. Every other key in
   * the standard block is precomputed by {@link MechanismSchema}, which is why only the prefix lives
   * here.
   */
  protected final String kPrefix;

  private final MotorGroup m_motors;
  private final MechanismGeometry m_geometry;
  private final RootstockAlert m_periodicThrew;

  private MechanismSchema m_schema;
  private MechanismMode m_mode = MechanismMode.NEUTRAL;
  private boolean m_periodicFailed;
  private String m_periodicFailure = "";

  private double m_stallCurrentAmps = kDefaultStallCurrentAmps;
  private double m_stallVelocityRps = kDefaultStallVelocityRps;
  private double m_overTemperatureCelsius = kDefaultOverTemperatureCelsius;

  private Trigger m_connectedTrigger;
  private Trigger m_stalledTrigger;
  private Trigger m_overTemperatureTrigger;

  /**
   * The one constructor.
   *
   * <p>Everything here is state the base class needs in order to keep its five interface promises
   * without asking the subclass again: the name is the telemetry, health and tuning identity; the
   * units convert every published number; the IO is the only thing that touches hardware; the motor
   * group sizes the per-motor log arrays; and the geometry is the plant declaration {@code
   * RootstockSim} builds from.
   *
   * <p><b>This constructor may throw, and that is the design.</b> It runs at boot, from a
   * {@code RobotContainer} field initialiser, and a null unit conversion or a missing IO is a library
   * misuse rather than a team configuration error — configuration errors are collected as values into
   * SAFE_MODE by {@code Validation} long before they reach here. Nothing else in this package throws.
   *
   * @param name the mechanism's name, e.g. {@code "Elevator"}; blank or null becomes
   *     {@code "(unnamed mechanism)"} so a log key is never empty
   * @param units the gearing and axis, from the config
   * @param io the hardware seam, from {@code MotorIOFactory}
   * @param motors the leader plus followers this mechanism was configured with
   * @param geometry the plant declaration, computed once from the config and the axis
   * @throws NullPointerException if {@code units}, {@code io}, {@code motors} or {@code geometry} is
   *     null
   */
  protected Mechanism(
      String name,
      MechanismUnits units,
      MotorIO io,
      MotorGroup motors,
      MechanismGeometry geometry) {
    m_name = name == null || name.isBlank() ? "(unnamed mechanism)" : name.trim();
    m_units =
        Objects.requireNonNull(
            units,
            "Mechanism \""
                + m_name
                + "\": units must not be null. Every published number is converted through it, so "
                + "there is no meaningful degraded behaviour. Pass MechanismUnits.of(reduction, "
                + "axis) from the config.");
    m_io =
        Objects.requireNonNull(
            io,
            "Mechanism \""
                + m_name
                + "\": io must not be null. Use MotorIOFactory.create(...), which returns a "
                + "NoOpMotorIO rather than null when no backend can serve the spec.");
    m_motors =
        Objects.requireNonNull(
            motors,
            "Mechanism \""
                + m_name
                + "\": the motor group must not be null. It sizes the per-motor log arrays, so a "
                + "null one produces a log whose array widths do not match the robot.");
    m_geometry =
        Objects.requireNonNull(
            geometry,
            "Mechanism \""
                + m_name
                + "\": geometry must not be null. It is the plant declaration RootstockSim builds "
                + "from; a mechanism that declares none can never be simulated. Compute it once "
                + "from the config's SimConfig and Axis.");
    kPrefix = RootstockLog.kRoot + "/" + m_name;
    m_periodicThrew =
        Alerts.error(m_name, m_name + "/periodic-threw", MatchImpact.BLOCKS_MATCH).sticky(true);
  }

  // ===============================================================================================
  // Identity and accessors
  // ===============================================================================================

  /**
   * This mechanism's name.
   *
   * @return the name; never null and never blank
   */
  public final String name() {
    return m_name;
  }

  /**
   * The hardware seam — the page-one escape hatch.
   *
   * <p>Every abstraction leaks eventually, and a library that has no exit is a library a team forks.
   * {@code io().as(TalonFXMotorIO.class)} reaches the vendor object when a team genuinely needs a
   * feature Rootstock does not model.
   *
   * @return the IO this mechanism commands
   */
  public final MotorIO io() {
    return m_io;
  }

  /**
   * The gearing and axis every published number is converted through.
   *
   * @return the units
   */
  public final MechanismUnits units() {
    return m_units;
  }

  /**
   * The leader, followers and inversions this mechanism was configured with.
   *
   * <p><b>Protected, not public.</b> Its only consumer is the {@code motorCount(...)} line in
   * {@link #describe(TelemetryDescriptor)}, and a public accessor would invite a team to reach past
   * {@link MotorIO} into the configuration rather than through the seam that models the hardware.
   *
   * @return the motor group
   */
  protected final MotorGroup motorGroup() {
    return m_motors;
  }

  /**
   * The inputs this mechanism read at the top of the current loop.
   *
   * <p>Handed out so a superstructure can read a mechanism's state without commanding it. Treat it
   * as read-only: it is the same object {@code periodic()} fills in place, and it is what replay
   * feeds, so writing to it forges the log.
   *
   * @return the live inputs object
   */
  public final MotorInputs inputs() {
    return m_inputs;
  }

  /**
   * The plant declaration simulation builds from.
   *
   * <p>Pure data, computed once at construction from the config and the axis. CORE declares it;
   * {@code RootstockSim} owns and steps the plant. See {@link #simulationPeriodic()}.
   *
   * @return the geometry; never null
   */
  public final MechanismGeometry geometry() {
    return m_geometry;
  }

  /**
   * What this mechanism is doing right now.
   *
   * @return the mode
   */
  public final MechanismMode mode() {
    return m_mode;
  }

  /**
   * Records the mode, for subclasses that change what the mechanism is doing.
   *
   * @param mode the new mode; null is ignored rather than stored, because a null mode would publish
   *     an empty {@code State} key and break the enum declaration made at registration
   */
  protected final void setMode(MechanismMode mode) {
    if (mode != null) {
      m_mode = mode;
    }
  }

  /**
   * True when a fatal configuration error put the robot in SAFE_MODE.
   *
   * <p>Every command factory returns a named refusal and every setter is a no-op while this is true.
   * A robot with a mis-declared mechanism must be drivable — a team can still push it off the field
   * — but it must not actuate a mechanism whose limits or gearing the library knows are wrong.
   *
   * @return true when SAFE_MODE is active
   */
  public final boolean safeMode() {
    return SafeMode.isActive();
  }

  /**
   * Registers this mechanism with the {@code CommandScheduler}, so the scheduler drives {@link
   * #periodic()} and command requirements work.
   *
   * <p>Opt-in, and the whole reason this class implements the {@code Subsystem} interface rather than
   * extending {@code SubsystemBase}: a state-based team never calls it and pays nothing.
   */
  public final void registerWithScheduler() {
    CommandScheduler.getInstance().registerSubsystem(this);
  }

  // ===============================================================================================
  // The loop
  // ===============================================================================================

  /**
   * Reads inputs, runs the subclass's cycle, publishes the standard key block — and survives anything
   * that goes wrong inside all three.
   *
   * <p>Called by the {@code CommandScheduler} if {@link #registerWithScheduler()} was called, or by
   * your own superstructure if not. Idempotent within a loop in the sense that matters: calling it
   * twice re-reads inputs and republishes, it does not double-integrate anything.
   *
   * <p><b>The {@code catch (Throwable)} is the point.</b> It is {@code final} here, and subclasses
   * implement {@link #onPeriodic()}, so that no mechanism can be written without the handler. On a
   * throw the mechanism is commanded neutral, {@code <name>/periodic-threw} goes active and sticky
   * with {@link MatchImpact#BLOCKS_MATCH}, the failure is reported as an {@code ERROR} fault, and the
   * scheduler keeps running. Degrade, never crash: a broken elevator must not stop the drivetrain
   * forty seconds into a match.
   *
   * <p><b>Timed.</b> The whole body is one {@link RootstockTracer} span named {@link
   * LoopTimeMonitor#kMechanismSection}, so {@code /Rootstock/Loop/Domain/MechanismMs} carries the
   * summed cost of every mechanism on the robot. Before this existed, the two most expensive things
   * Rootstock runs were the two it could not name, and the over-budget alert sent a student to an
   * empty subtable. Every mechanism shares one section name deliberately: see that constant.
   */
  @Override
  public final void periodic() {
    // The scope is cached per section name and the name is a constant, so this allocates nothing.
    // close() in a finally, not at the end of the try: a section left open would leak its span into
    // whatever ran next, and this method exists to survive a throw.
    RootstockTracer.Scope scope = RootstockTracer.section(LoopTimeMonitor.kMechanismSection);
    try {
      m_io.updateInputs(m_inputs);
      RootstockLog.processInputs(schema().inputsKey(), m_inputs);
      onPeriodic();
      schema().extra(kDeviceResetCountKey, m_inputs.deviceResetCount);
      schema().publish();
    } catch (Throwable t) {
      recordPeriodicFailure(t);
    } finally {
      scope.close();
    }
  }

  /**
   * One cycle of this mechanism's own behaviour: run the controller, command the IO, and stage the
   * standard key block on {@link #schema()}.
   *
   * <p>Called from inside {@link #periodic()}'s {@code try}, <i>after</i> inputs have been read and
   * logged and <i>before</i> the staged block is published. Do not read inputs or publish here; both
   * are done for you, in the order the replay contract requires.
   *
   * <p>Do not throw. Report instead — an alert, a fault, a neutral output. The wrapper exists as a
   * last line of defence against a bug, not as a control-flow mechanism.
   */
  protected abstract void onPeriodic();

  /**
   * Deliberately a no-op, and {@code final} so it stays one.
   *
   * <p><b>CORE declares; simulation owns.</b> An earlier revision put the plant here and called two
   * methods that exist on no interface in the library, so it could not have compiled. The binding
   * decision deleted the method and both call sites: a mechanism declares a {@link
   * MechanismGeometry}, the backend exposes {@code MotorIO.simHandle()}, and {@code RootstockSim} owns
   * the plant and steps it. That split is what makes the same mechanism code run identically in real,
   * sim and replay, and what keeps {@code org.rootstock.core} free of any dependency on the
   * simulation domain.
   *
   * <p>Overriding it to build a plant would put a second, unlogged integrator next to the one {@code
   * RootstockSim} steps, and the two would disagree.
   */
  @Override
  public final void simulationPeriodic() {
    // Intentionally empty. See the javadoc: RootstockSim owns plant construction and the tick.
  }

  /**
   * The staged writer for this mechanism's {@code Rootstock/<Name>/} block.
   *
   * <p>Built lazily on first use rather than in the constructor, because constructing it calls {@link
   * #describe(TelemetryDescriptor)}, which calls the overridable {@link
   * #describeExtras(TelemetryDescriptor)} — and a subclass's fields are not initialised while the
   * base constructor is running. First use is inside the first {@code periodic()}, which is warmup;
   * <b>this method</b> allocates nothing afterwards, because it is one null check and a field read.
   *
   * <p>That is a claim about this method only. It used to read "nothing allocates on this path
   * afterwards", which was measured and is false: with 200,000 warmup loops and 200,000 measured
   * loops, one {@code SimpleMechanism.periodic()} on the design's section 9 roller allocates 256
   * bytes. A JFR allocation profile puts 85% of the samples in {@code
   * RootstockBudget.recordFacadeBytes}, which boxes an {@code Integer} per key on its attribution
   * cycle. Fixing that lives in the telemetry facade, not here; until it is fixed, do not repeat
   * the old sentence.
   *
   * @return the writer for this mechanism's key block
   */
  protected final MechanismSchema schema() {
    if (m_schema == null) {
      m_schema = MechanismSchema.declare(this);
    }
    return m_schema;
  }

  private void recordPeriodicFailure(Throwable t) {
    m_periodicFailed = true;
    m_periodicFailure = summarise(t);
    try {
      m_mode = MechanismMode.NEUTRAL;
      m_io.setNeutral();
    } catch (Throwable ignored) {
      // The neutral command itself failed, so the hardware seam is gone too. There is nothing
      // further this mechanism can do, and rethrowing would take the scheduler — and every other
      // mechanism on the robot — down with it.
    }
    m_periodicThrew
        .text(
            m_name
                + "/periodic-threw: "
                + m_periodicFailure
                + ". The mechanism was commanded neutral and is no longer controlled; the rest of "
                + "the robot is still running. Fix: read the stack trace in the console log, then "
                + "correct the mechanism's code or config.")
        .set(true);
  }

  private static String summarise(Throwable t) {
    if (t == null) {
      return "unknown failure (null Throwable)";
    }
    String message = t.getMessage() == null ? "no message" : t.getMessage();
    StackTraceElement[] frames = t.getStackTrace();
    String where = frames.length == 0 ? "no stack frames" : frames[0].toString();
    return t.getClass().getSimpleName() + ": " + message + " at " + where;
  }

  /**
   * Whether {@link #periodic()} has caught a throwable since the last {@link
   * #clearPeriodicFailure()}.
   *
   * @return true when this mechanism is in the degraded state the wrapper puts it in
   */
  public final boolean periodicFailed() {
    return m_periodicFailed;
  }

  /**
   * Clears the latched {@link #periodicFailed()} state and the alert that goes with it.
   *
   * <p>Latched rather than self-clearing because an intermittent throw that fixes itself is the
   * failure most worth knowing about, and a fault that disappears before anyone looks at the pit
   * screen may as well not have happened. Clearing it is therefore an explicit act.
   */
  protected final void clearPeriodicFailure() {
    m_periodicFailed = false;
    m_periodicFailure = "";
    m_periodicThrew.clearLatched();
    m_periodicThrew.set(false);
  }

  // ===============================================================================================
  // Commanding
  // ===============================================================================================

  /**
   * Neutral output, honouring the configured brake or coast behaviour.
   *
   * <p>Also {@code TuningTarget.stop()}, which is called on every abort path and <b>must never
   * throw</b> — so the IO call is wrapped. An exception on the abort path turns a controlled stop
   * into an uncontrolled one, which is the worst possible time for one.
   */
  @Override
  public final void stop() {
    m_mode = MechanismMode.NEUTRAL;
    try {
      m_io.setNeutral();
    } catch (Throwable ignored) {
      // Never propagate out of an abort path. The health poll will report the device shortly.
    }
  }

  /**
   * Command a raw voltage with no feedback loop closed around it, still respecting device soft
   * limits.
   *
   * <p>A no-op in SAFE_MODE: a robot whose configuration the library knows is wrong does not actuate.
   *
   * @param volts the voltage to command
   */
  @Override
  public void setVoltage(double volts) {
    if (safeMode()) {
      return;
    }
    m_mode = MechanismMode.OPEN_LOOP;
    m_io.setVoltage(volts);
  }

  // ===============================================================================================
  // Triggers
  // ===============================================================================================

  /**
   * True while the device is answering.
   *
   * <p>Created lazily and cached: constructing a {@code Trigger} touches the {@code
   * CommandScheduler}, and a mechanism constructed in a unit test with no HAL must not pay for a
   * trigger nobody binds.
   *
   * @return the trigger
   */
  public final Trigger connected() {
    if (m_connectedTrigger == null) {
      m_connectedTrigger = new Trigger(() -> m_inputs.connected);
    }
    return m_connectedTrigger;
  }

  /**
   * True while this mechanism looks stalled — drawing stall current and not moving.
   *
   * <p>The predicate is {@link #isStalled()}, which a subclass may override once it knows its own
   * current limits.
   *
   * @return the trigger
   */
  public final Trigger stalled() {
    if (m_stalledTrigger == null) {
      m_stalledTrigger = new Trigger(this::isStalled);
    }
    return m_stalledTrigger;
  }

  /**
   * True while any motor in this mechanism is above its temperature threshold.
   *
   * @return the trigger
   */
  public final Trigger overTemperature() {
    if (m_overTemperatureTrigger == null) {
      m_overTemperatureTrigger = new Trigger(this::isOverTemperature);
    }
    return m_overTemperatureTrigger;
  }

  /**
   * The stall heuristic: stator current above the threshold while the output shaft is essentially
   * stationary.
   *
   * <p>Overridable because a mechanism that knows its {@code CurrentLimits} can do better — a
   * mechanism limited to 40 A can never reach a 60 A default threshold, so the default would never
   * fire and the protection would be silently absent.
   *
   * @return true when the mechanism appears stalled
   */
  protected boolean isStalled() {
    double amps = m_inputs.statorCurrentAmps;
    double rps = m_inputs.velocityRps;
    if (Double.isNaN(amps) || Double.isNaN(rps)) {
      return false;
    }
    return amps >= m_stallCurrentAmps && Math.abs(rps) <= m_stallVelocityRps;
  }

  /**
   * Whether the leader or any follower is above the temperature threshold.
   *
   * @return true when any reported temperature exceeds the threshold
   */
  protected boolean isOverTemperature() {
    if (!Double.isNaN(m_inputs.temperatureCelsius)
        && m_inputs.temperatureCelsius > m_overTemperatureCelsius) {
      return true;
    }
    for (double c : m_inputs.followerTemperatureC) {
      if (!Double.isNaN(c) && c > m_overTemperatureCelsius) {
        return true;
      }
    }
    return false;
  }

  /**
   * Sets the thresholds {@link #isStalled()} uses.
   *
   * <p>Called from a subclass constructor once the configured current limits are known, so the
   * heuristic is expressed in numbers this mechanism can actually reach.
   *
   * @param stallCurrentAmps stator current at or above which a stationary motor is stalled; ignored
   *     if not a positive finite number
   * @param stallVelocityRps output-shaft speed at or below which the motor counts as stationary;
   *     ignored if negative or not finite
   */
  protected final void setStallThresholds(double stallCurrentAmps, double stallVelocityRps) {
    if (Double.isFinite(stallCurrentAmps) && stallCurrentAmps > 0.0) {
      m_stallCurrentAmps = stallCurrentAmps;
    }
    if (Double.isFinite(stallVelocityRps) && stallVelocityRps >= 0.0) {
      m_stallVelocityRps = stallVelocityRps;
    }
  }

  /**
   * Sets the threshold {@link #isOverTemperature()} uses.
   *
   * @param celsius the temperature above which a warning is raised; ignored if not finite
   */
  protected final void setOverTemperatureCelsius(double celsius) {
    if (Double.isFinite(celsius)) {
      m_overTemperatureCelsius = celsius;
    }
  }

  // ===============================================================================================
  // TelemetrySource — the DECLARATION half. The push half is periodic().
  // ===============================================================================================

  /**
   * The log key segment, which is the constructor's {@code name} argument unchanged.
   *
   * <p>"The log key segment" and "the telemetry name" are therefore the same string by construction
   * and cannot drift. Registering two sources under one name is a fatal configuration error, but it
   * is not enforced here: {@code RootstockRegistry.addAll} catches it in the same collected-not-thrown
   * pass as every other config error, so a duplicate name boots into SAFE_MODE with a sentence
   * instead of throwing out of a static initialiser.
   *
   * @return the name
   */
  @Override
  public final String telemetryName() {
    return m_name;
  }

  /**
   * Declares this mechanism's schema — units, motor count, state enum and extras — exactly once.
   *
   * <p>Everything below is read off state the mechanism already has: the axis, the motor group, the
   * mode enum. So the declaration cannot disagree with what {@code periodic()} publishes unless the
   * configuration itself is wrong, which validation already catches.
   *
   * <p><b>Degrees, not radians, for a rotary axis.</b> The stream a rotary mechanism actually
   * publishes into {@code Goal}, {@code Setpoint} and {@code Measured} is in the axis's <i>user</i>
   * units, and a rotary axis's user unit is the degree — {@code Axis.unitLabel()} returns
   * {@code "deg"}. Radians appear in exactly one place in this library, the SI control path, and that
   * path's values are never published. Declaring radians over a degree stream is a 57.3&times;
   * mislabel that a dashboard's own unit conversion would then compound rather than reveal: a 90
   * degree arm setpoint plotted as 5157.
   *
   * @param d the descriptor, built by telemetry and valid only for the duration of this call
   */
  @Override
  public final void describe(TelemetryDescriptor d) {
    if (d == null) {
      return;
    }
    if (m_units.siDomain() == SiDomain.LINEAR_METERS) {
      d.positionUnit(Meters).velocityUnit(MetersPerSecond);
    } else {
      d.positionUnit(Degrees).velocityUnit(DegreesPerSecond);
    }
    d.motorCount(m_motors.count());
    d.states(MechanismMode.class);

    // CRITICAL and unit-free. "A motor controller rebooted mid-match" is the textbook case of the
    // CRITICAL definition — a signal you can only ever read AFTER the match, off a robot that has
    // since been power-cycled, with no second copy anywhere. It would survive today's FMS gate at
    // STANDARD, but minimumTier is a config field a team can raise to CRITICAL at 11pm under a
    // byte-budget squeeze, and CRITICAL keys are never removed from the schema by any mechanism.
    // The paired publish in periodic() is schema().extra(kDeviceResetCountKey, long), which writes
    // at the tier declared here — so declaration and publish cannot drift.
    d.extra(kDeviceResetCountKey, Tier.CRITICAL);

    describeExtras(d);
  }

  /**
   * Hook for the keys only <i>some</i> mechanisms publish.
   *
   * <p>Empty by default. The base class's own extras are declared before this is called, so a
   * subclass cannot forget them by forgetting to call {@code super}.
   *
   * <p>Declare unconditionally, even for a key published only on one control location. A
   * conditionally declared key is a key whose absence the per-cycle schema audit cannot distinguish
   * from a bug, and the failure that audit exists to catch is precisely "a mechanism silently stopped
   * publishing {@code Setpoint} and nobody noticed until a student was staring at an empty graph at
   * an event".
   *
   * @param d the descriptor, valid only for the duration of the enclosing {@code describe} call
   */
  protected void describeExtras(TelemetryDescriptor d) {
    // Empty by default.
  }

  // ===============================================================================================
  // HealthSource
  // ===============================================================================================

  /**
   * The health name, which is the mechanism name.
   *
   * @return the name
   */
  @Override
  public final String healthName() {
    return m_name;
  }

  /**
   * The checks every mechanism gets for free, then {@link #pollMechanismHealth(FaultCollector)}.
   *
   * <p>Called at most once per robot loop, and only on the loop whose cycle index selects this
   * source. {@code final} so that a subclass cannot drop the free checks by overriding without
   * calling {@code super} — the standard checks are the ones a team never remembers to write and
   * always wants at an event.
   *
   * @param out where to report; valid only for the duration of this call
   */
  @Override
  public final void pollHealth(FaultCollector out) {
    if (out == null) {
      return;
    }
    if (m_periodicFailed) {
      out.error(
          m_name,
          "periodic() threw and the mechanism was commanded neutral: "
              + m_periodicFailure
              + ". It is not being controlled. Fix the code or config, then reboot.");
    }
    if (!m_inputs.connected) {
      out.error(
          m_name + " leader (" + m_io.name() + ")",
          "not responding. Expected: answering status signals every loop. Check CAN wiring, the "
              + "device ID against the config, and that the device has power.");
    }
    for (int i = 0; i < m_inputs.followerConnected.length; i++) {
      if (!m_inputs.followerConnected[i]) {
        out.error(
            m_name + " follower " + i,
            "not responding while the leader is. Expected: every follower answering. A silent "
                + "follower means the leader is doing the whole job alone. Check its CAN wiring "
                + "and device ID.");
      }
    }
    if (isOverTemperature()) {
      out.warn(
          m_name,
          "motor temperature "
              + format(hottestCelsius())
              + " C exceeds the "
              + format(m_overTemperatureCelsius)
              + " C threshold. Expected below threshold. Let it cool, then check for a mechanical "
              + "bind or an over-aggressive current limit.");
    }
    if (isStalled()) {
      out.warn(
          m_name,
          "stalled: "
              + format(m_inputs.statorCurrentAmps)
              + " A of stator current at "
              + format(m_inputs.velocityRps)
              + " rot/s. Expected either motion or low current. Check for a mechanical jam, a "
              + "hard stop, or a soft limit set past the physical travel.");
    }
    if (m_inputs.deviceResetCount > 0L) {
      out.sticky(
          m_name + " (" + m_io.name() + ")",
          "the device has reset "
              + m_inputs.deviceResetCount
              + " time(s) since boot, so it came back with factory configuration: no soft limits, "
              + "no current limits, no gains. Expected zero. Check power wiring and the CAN bus "
              + "before the next match; clear this once it is understood.");
    }
    pollMechanismHealth(out);
  }

  /**
   * Hook for checks only this mechanism can make — an absolute encoder disagreeing with the rotor, a
   * follower fighting its leader, an unhomed axis.
   *
   * <p>Same contract as {@link #pollHealth(FaultCollector)}: must not block, must not throw, must
   * complete in well under a millisecond.
   *
   * @param out where to report; valid only for the duration of this call
   */
  protected void pollMechanismHealth(FaultCollector out) {
    // Empty by default.
  }

  private double hottestCelsius() {
    double hottest = Double.isNaN(m_inputs.temperatureCelsius) ? 0.0 : m_inputs.temperatureCelsius;
    for (double c : m_inputs.followerTemperatureC) {
      if (!Double.isNaN(c) && c > hottest) {
        hottest = c;
      }
    }
    return hottest;
  }

  private static String format(double v) {
    return String.format("%.1f", v);
  }

  // ===============================================================================================
  // SelfTestable
  // ===============================================================================================

  /**
   * The self-test name, which is the mechanism name.
   *
   * @return the name
   */
  @Override
  public final String selfTestName() {
    return m_name;
  }

  /**
   * Build the routine that proves this mechanism works.
   *
   * <p>Abstract rather than defaulted: a self test that passes without checking anything is worse
   * than no self test, because a green light nobody earned is a green light everybody trusts. Built
   * fresh on every run, never cached, because a routine carries the mutable state of one execution.
   *
   * @return the routine; never null
   */
  @Override
  public abstract SelfTestRoutine selfTestRoutine();

  // ===============================================================================================
  // TuningTarget
  // ===============================================================================================

  /**
   * The tuning namespace, which is the mechanism name.
   *
   * @return the name
   */
  @Override
  public final String tuningName() {
    return m_name;
  }

  /**
   * Whether this mechanism's SI unit is metres or radians, read off its own geometry.
   *
   * @return the SI domain
   */
  @Override
  public final SiDomain siDomain() {
    return m_units.siDomain();
  }

  /**
   * The subsystem a tuning command must require — this mechanism.
   *
   * <p>Always present, and always this object, which is how a tuning routine gets exclusive control
   * without the tuning system knowing anything about the scheduler. A state-based team that never
   * registered simply has no scheduler to enforce it, and loses nothing.
   *
   * @return this mechanism, as a requirement
   */
  @Override
  public final Optional<Subsystem> requirement() {
    return Optional.of(this);
  }

  /**
   * The mechanism's position in canonical SI, derived from the logged inputs.
   *
   * <p>Overridable: a mechanism with a fused absolute encoder has a better answer than the rotor
   * does.
   *
   * @return metres or radians
   */
  @Override
  public double measuredSi() {
    return m_units.toSi(m_units.toUser(m_inputs.positionRot));
  }

  /**
   * The mechanism's velocity in canonical SI, derived from the logged inputs.
   *
   * @return m/s or rad/s
   */
  @Override
  public double velocitySi() {
    return m_units.toSiPerSec(m_units.toUserPerSec(m_inputs.velocityRps));
  }

  /**
   * The voltage the device reports it is actually applying.
   *
   * <p>Strongly preferred over the commanded value: on a sagging battery the two differ by half a
   * volt, and fitting kV against the commanded value biases it high.
   *
   * @return the measured applied voltage, or empty when the device does not report one
   */
  @Override
  public OptionalDouble appliedVolts() {
    return Double.isNaN(m_inputs.appliedVolts)
        ? OptionalDouble.empty()
        : OptionalDouble.of(m_inputs.appliedVolts);
  }

  /**
   * Stator current, for the overcurrent abort.
   *
   * @return the stator current in amps, or empty when the device does not report it
   */
  @Override
  public OptionalDouble statorCurrentAmps() {
    return Double.isNaN(m_inputs.statorCurrentAmps)
        ? OptionalDouble.empty()
        : OptionalDouble.of(m_inputs.statorCurrentAmps);
  }

  /**
   * The SI position at which a cosine-gravity mechanism is horizontal, read off the axis.
   *
   * @return the horizontal reference in radians for a rotary axis, zero for a linear one
   */
  @Override
  public double horizontalReferenceSi() {
    return m_units.horizontalReferenceSi();
  }

  // ===============================================================================================
  // Boot dump
  // ===============================================================================================

  /**
   * Everything about this mechanism a student or a competition-day CSA might need, as text.
   *
   * <p>Dumped to the log at boot. This is a teaching surface, not a debug string: it prints the
   * gearing derivation, the hardware seam's own account of itself, the declared plant and the motor
   * group, so that "why is it going the wrong distance" is answerable from the log alone.
   *
   * @return a multi-line description with no trailing newline
   */
  public final String describe() {
    StringBuilder sb = new StringBuilder(512);
    sb.append(m_name).append(" [").append(getClass().getSimpleName()).append(']');
    sb.append(System.lineSeparator()).append("  mode:     ").append(m_mode);
    sb.append(System.lineSeparator()).append("  motors:   ").append(m_motors.describe());
    sb.append(System.lineSeparator()).append("  io:       ").append(m_io.describe());
    sb.append(System.lineSeparator()).append("  geometry: ").append(m_geometry.describe());
    sb.append(System.lineSeparator()).append("  control:  ").append(controlLocation().explanation());
    sb.append(System.lineSeparator()).append("  gains:    ").append(gains());
    sb.append(System.lineSeparator()).append(m_units.describe(m_name));
    String detail = describeDetail();
    if (detail != null && !detail.isBlank()) {
      sb.append(System.lineSeparator()).append(detail);
    }
    return sb.toString();
  }

  /**
   * Hook for the lines only this mechanism can write — setpoint tables, homing strategy, unwrap
   * range.
   *
   * @return extra lines for {@link #describe()}, or an empty string
   */
  protected String describeDetail() {
    return "";
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + m_name + ", " + m_mode + ")";
  }
}

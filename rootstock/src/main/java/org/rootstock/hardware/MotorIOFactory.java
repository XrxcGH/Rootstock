package org.rootstock.hardware;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.spi.RobotMode;
import org.rootstock.hardware.generic.GenericMotorIO;
import org.rootstock.hardware.sim.SimMotorIO;
import org.rootstock.units.MechanismUnits;

/**
 * Backend selection: <b>one call, not a nested ternary</b>.
 *
 * <p>A surveyed robot template has nine copies of the same three-way nested ternary choosing between
 * a real IO, a sim IO and a replay IO — nine places for the fourth one to be wrong. This class owns
 * that decision once, driven off the sealed {@link MotorSpec} hierarchy.
 *
 * <h2>Why vendors register rather than being named here</h2>
 *
 * <p>The core artifact contains <b>zero vendor imports</b> and must compile and run on a machine
 * with neither Phoenix nor REVLib installed. So this class cannot say {@code new
 * TalonFXMotorIO(...)}. Instead each vendor artifact publishes a {@link Backend} through the
 * standard {@code META-INF/services} mechanism, this class discovers them on first use, and the
 * decision stays in one place without core pointing at a vendor.
 *
 * <p>The {@code Class.forName("com.ctre...")} alternative was rejected: a string literal is not a
 * seam, it is a compile error deferred to a competition.
 *
 * <h2>Why the whole {@link DeviceSetup} crosses the seam, not just the leader spec</h2>
 *
 * <p>This seam used to take a bare {@link MotorSpec}. Everything else the team had declared -- the
 * soft limits, the current limits, the hard stops, the CANcoder and every follower motor -- stopped
 * here and never reached the device, because a backend was never handed it. A two-motor elevator ran
 * on one motor, with the motor model's default current limit instead of the declared one and with no
 * firmware travel stop, and nothing in the boot dump said so. One carrier record crosses the seam
 * instead, so the next field added to a config does not need a new SPI overload and cannot be
 * quietly dropped again.
 *
 * <h2>Replay is unconditional</h2>
 *
 * <p>AdvantageKit is a required dependency, so {@link RobotMode#REPLAY} always exists and always
 * works. There is no runtime "can the installed backend replay?" refusal path, because there is
 * nothing that could fail.
 *
 * <h2>Degrade, never crash</h2>
 *
 * <p>A spec whose adapter is missing yields a {@link NoOpMotorIO} plus a blocking alert naming the
 * exact artifact to add. The robot boots and says what is wrong; it does not refuse to start fifteen
 * minutes before a match.
 */
public final class MotorIOFactory {

  /**
   * The Rootstock adapter artifacts this class knows how to recover after a robot jar has merged the
   * {@code META-INF/services} files. These are Rootstock artifact ids, not vendor package names, so
   * naming them creates no bytecode dependency on a vendor -- which is the thing architecture rule 1
   * forbids. Extend the list when an adapter ships.
   */
  private static final List<String> kAdapterIds = List.of("phoenix6", "revlib");

  /** Where an adapter publishes its own collision-proof marker. See {@link #ensureDiscovered()}. */
  private static final String kMarkerPrefix = "META-INF/rootstock/backends/";

  private static final List<Backend> kBackends = new CopyOnWriteArrayList<>();
  private static volatile boolean s_serviceLoaderScanned;

  private MotorIOFactory() {}

  /**
   * Everything the config declared about the motors on one output shaft.
   *
   * <p>This is the whole reason the seam has this shape. A {@link MotorSpec} says which device; it
   * does not say how far the mechanism may travel, how much current it may draw, where its absolute
   * reference is, or how many other motors are bolted to the same gearbox. While only the spec
   * crossed the seam, a backend physically could not configure any of those, and none of them
   * reached a device.
   *
   * @param motors the leader and its followers; required
   * @param current the stator and supply limits to configure; null means the leader model's
   *     defaults, because a motor with no current limit at all is a fire risk
   * @param limits the travel range and hard stops, or <b>null</b> for a mechanism that has no travel
   *     range at all -- a flywheel and an intake roller genuinely have none, and inventing one is
   *     worse than leaving the device unlimited
   * @param feedback how absolute position is plumbed; null means rotor only
   */
  public record DeviceSetup(
      MotorGroup motors, CurrentLimits current, PositionLimits limits, FeedbackSpec feedback) {

    /** Substitutes the two defaults that have a right answer, and rejects the one that does not. */
    public DeviceSetup {
      motors =
          Objects.requireNonNull(
              motors,
              "MotorIOFactory.DeviceSetup: a MotorGroup is required. Use"
                  + " MotorIOFactory.DeviceSetup.of(spec) if all you have is one motor.");
      current = current == null ? CurrentLimits.defaultsFor(motors.model()) : current;
      feedback = feedback == null ? new FeedbackSpec.RotorOnly() : feedback;
    }

    /**
     * One motor, no followers, no travel range, no absolute encoder.
     *
     * <p>For a bare {@link MotorSpec} used on its own, outside a mechanism. A mechanism must not use
     * this form: it knows its limits and its followers, and this form would discard them.
     *
     * @param leader the only motor
     * @return the setup
     */
    public static DeviceSetup of(MotorSpec leader) {
      return new DeviceSetup(MotorGroup.leader(leader), null, null, null);
    }

    /**
     * The motor whose sensor and closed loop are the mechanism's.
     *
     * @return the leader spec; never null
     */
    public MotorSpec leader() {
      return motors.leader();
    }

    /**
     * Whether this mechanism declared a travel range at all.
     *
     * @return true when {@link #limits()} is present
     */
    public boolean hasTravelRange() {
      return limits != null;
    }
  }

  /**
   * A vendor adapter's entry point into backend selection.
   *
   * <p>Implemented in {@code rootstock-phoenix6} and {@code rootstock-revlib}, declared in each
   * artifact's {@code META-INF/services/org.rootstock.hardware.MotorIOFactory$Backend}, and never
   * named by core.
   */
  public interface Backend {

    /**
     * Whether this adapter builds an IO for the given spec.
     *
     * @param spec the declared motor
     * @return true when {@link #create(DeviceSetup, MechanismUnits, ControlConfig, MechanismKind)}
     *     will return a real IO for it
     */
    boolean supports(MotorSpec spec);

    /**
     * Build the IO for a leader spec this adapter {@link #supports(MotorSpec)}.
     *
     * <p>An implementation is expected to configure <b>everything</b> the setup carries: the current
     * limits, the soft limits and hard stops when {@code setup.limits()} is present, the absolute
     * feedback plumbing, and every follower. Ignoring a field here is the exact failure this
     * parameter exists to prevent, so {@code describe()} should state what was configured.
     *
     * @param setup the motors, current limits, travel range and feedback plumbing the team declared
     * @param units the mechanism's unit conversion object
     * @param control the declared gains, constraints, gravity model and tolerance
     * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
     * @return the backend IO; never null
     */
    MotorIO create(
        DeviceSetup setup, MechanismUnits units, ControlConfig control, MechanismKind kind);

    /**
     * The vendor this adapter speaks for, for the boot dump and for the "adapter not installed"
     * alert.
     *
     * @return for example {@code "Phoenix 6"} or {@code "REVLib"}
     */
    String vendor();
  }

  /**
   * Register a backend explicitly.
   *
   * <p>Normally unnecessary — adapters are discovered through {@link ServiceLoader}. This exists for
   * a test that installs a fake backend, and for a team that writes an adapter and does not want to
   * ship a services file.
   *
   * @param backend the adapter; null and duplicates are ignored
   */
  public static void register(Backend backend) {
    if (backend != null && !kBackends.contains(backend)) {
      kBackends.add(backend);
    }
  }

  /**
   * Forget every registered backend and re-run discovery on the next {@link #create}.
   *
   * <p>For tests only. Backend registration is process-global, and a test that installs a fake
   * adapter must be able to remove it again or the next test inherits it.
   */
  public static void resetForTest() {
    kBackends.clear();
    s_serviceLoaderScanned = false;
  }

  /**
   * The vendors currently able to build an IO, for the boot dump.
   *
   * @return the registered vendor names, in discovery order
   */
  public static List<String> backends() {
    ensureDiscovered();
    List<String> out = new ArrayList<>(kBackends.size());
    for (Backend b : kBackends) {
      out.add(b.vendor());
    }
    return List.copyOf(out);
  }

  /**
   * Real, simulated or replayed, times the backend the spec names — decided in ONE place.
   *
   * @param setup everything the config declared about the motors on this output shaft
   * @param units the mechanism's unit conversion object
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param mode which robot mode is running
   * @return the backend IO; a {@link NoOpMotorIO} when replaying or when no adapter is installed,
   *     never null and never an exception
   */
  public static MotorIO create(
      DeviceSetup setup,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      RobotMode mode) {
    if (setup == null) {
      return new NoOpMotorIO("unnamed motor", "no MotorSpec was declared");
    }
    MotorSpec spec = setup.leader();
    if (mode == RobotMode.REPLAY) {
      return new NoOpMotorIO(spec.name(), "RobotMode.REPLAY -- every input comes from the log");
    }
    // The two backends core owns outright. They are checked first because they cannot be provided
    // by an adapter and because a GenericSpec must never fall through to a vendor.
    if (spec instanceof MotorSpec.GenericSpec generic) {
      return new GenericMotorIO(generic, units, control, kind);
    }
    if (spec instanceof MotorSpec.SimSpec sim) {
      return new SimMotorIO(sim, units, control, kind);
    }
    ensureDiscovered();
    for (Backend backend : kBackends) {
      if (backend.supports(spec)) {
        MotorIO io = backend.create(setup, units, control, kind);
        if (io != null) {
          return io;
        }
      }
    }
    raise(
        spec.name(),
        spec.name()
            + ": no Rootstock adapter is installed for a "
            + spec.deviceType()
            + " (vendor \""
            + spec.vendor()
            + "\"), so this mechanism will not move. Fix: add the matching artifact --"
            + " dev.rootstock:rootstock-phoenix6 for CTRE devices,"
            + " dev.rootstock:rootstock-revlib for REV devices -- to your build, alongside"
            + " that vendor's own vendordep.",
        MatchImpact.BLOCKS_MATCH);
    return new NoOpMotorIO(spec.name(), "no adapter installed for vendor \"" + spec.vendor() + "\"");
  }

  /**
   * Discovery, in two passes, because one of them does not survive a robot jar.
   *
   * <p>Pass one is {@link ServiceLoader}, which is correct whenever the adapters are separate jars
   * on a classpath: {@code ./gradlew test} and {@code simulateJava} both see every backend.
   *
   * <p>Pass two exists because the deployed artifact is not a classpath. The stock WPILib {@code
   * jar} task merges every dependency jar with {@code DuplicatesStrategy.INCLUDE}, and both adapters
   * ship a provider file at the byte-identical path {@code
   * META-INF/services/org.rootstock.hardware.MotorIOFactory$Backend}. The merged jar then holds that
   * entry twice, and {@code ClassLoader.getResources} returns exactly one of them -- last written
   * wins, which vendordep declaration order decides. A dual-vendor team loses one vendor's motors on
   * the robot only, after every desktop check has passed, because separate jars on a test or
   * simulation classpath resolve both. So each adapter also ships a marker under a path of its own,
   * which nothing can merge away, and this pass reads the backend class name out of it. The class
   * name comes from the adapter's own resource, so core still names no vendor type.
   */
  private static void ensureDiscovered() {
    if (s_serviceLoaderScanned) {
      return;
    }
    synchronized (MotorIOFactory.class) {
      if (s_serviceLoaderScanned) {
        return;
      }
      s_serviceLoaderScanned = true;
      for (Backend backend : ServiceLoader.load(Backend.class)) {
        register(backend);
      }
      recoverMergedAdapters();
    }
  }

  private static void recoverMergedAdapters() {
    ClassLoader loader = MotorIOFactory.class.getClassLoader();
    if (loader == null) {
      return;
    }
    for (String id : kAdapterIds) {
      String className = markedBackendClass(loader, id);
      if (className == null || isRegistered(className)) {
        continue;
      }
      Backend recovered = instantiate(className);
      if (recovered == null) {
        continue;
      }
      // REGISTER FIRST, announce second. The alert is the diagnostic; the registration is the
      // repair, and the repair must not depend on the diagnostic succeeding.
      register(recovered);
      raise(
          "MotorIOFactory",
          "Rootstock had to recover the "
              + recovered.vendor()
              + " backend by hand: your robot jar merged the two adapters' META-INF/services files"
              + " into one entry, so Java's own ServiceLoader could see only one of them. Both"
              + " vendors work now. Any OTHER library on your classpath that uses ServiceLoader has"
              + " the same problem and cannot fix itself. Fix: add a services-file merge to the jar"
              + " task in your build.gradle.",
          MatchImpact.PIT_ONLY);
    }
  }

  /**
   * Raise an alert, and never let raising one become the outage.
   *
   * <p>{@link #recoverMergedAdapters()} calls this from inside {@link #ensureDiscovered()}, which is
   * the registration path itself. Anything that escapes here leaves the robot with no backends at
   * all, which is a diagnostic costing every motor on the machine.
   *
   * <p>The machinery behind one alert is larger than it looks, and it was worth measuring: {@code
   * Alerts.warning} reaches {@code Alert}'s constructor, which reaches {@code SmartDashboard} and
   * {@code NetworkTableInstance.getDefault()}. On a JVM without the NetworkTables native on
   * {@code java.library.path}, WPILib prints {@code ntcorejni could not be loaded from path} and
   * calls {@code System.exit} -- which no catch block anywhere can stop, so that particular case is
   * not what this guard is for. What it is for is every case that does throw: a registry that
   * refuses a blank group, an alert table in a state it did not expect. Registration has already
   * happened by the time this runs, so losing the message costs a message.
   */
  private static void raise(String group, String text, MatchImpact impact) {
    try {
      if (impact == MatchImpact.BLOCKS_MATCH) {
        Alerts.error(group, text, impact).set(true);
      } else {
        Alerts.warning(group, text, impact).set(true);
      }
    } catch (Exception | LinkageError e) {
      // Nothing to do and nowhere to say it. A robot that boots without its alert table is worse
      // off than one with it, and far better off than one that does not boot.
    }
  }

  /**
   * The backend class an adapter's marker names, or null when that adapter is not installed.
   *
   * <p>Every failure here is a no-op on purpose: a missing adapter is the normal single-vendor case,
   * and an unreadable resource must not stop a robot from booting.
   */
  private static String markedBackendClass(ClassLoader loader, String id) {
    try {
      Enumeration<URL> found = loader.getResources(kMarkerPrefix + id + ".properties");
      while (found.hasMoreElements()) {
        try (InputStream in = found.nextElement().openStream()) {
          Properties props = new Properties();
          props.load(in);
          String name = props.getProperty("backend");
          if (name != null && !name.isBlank()) {
            return name.trim();
          }
        }
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }

  private static boolean isRegistered(String className) {
    for (Backend backend : kBackends) {
      if (backend.getClass().getName().equals(className)) {
        return true;
      }
    }
    return false;
  }

  private static Backend instantiate(String className) {
    try {
      Class<?> type = Class.forName(className, true, MotorIOFactory.class.getClassLoader());
      Object made = type.getDeclaredConstructor().newInstance();
      return made instanceof Backend backend ? backend : null;
    } catch (Exception | LinkageError e) {
      // The adapter jar is present but its vendor SDK is not, or the class was renamed. Either way
      // the "no adapter installed" alert is the honest report, and a crash at boot is not.
      return null;
    }
  }
}

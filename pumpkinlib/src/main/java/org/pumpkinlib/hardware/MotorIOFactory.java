package org.pumpkinlib.hardware;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.spi.RobotMode;
import org.pumpkinlib.hardware.generic.GenericMotorIO;
import org.pumpkinlib.hardware.sim.SimMotorIO;
import org.pumpkinlib.units.MechanismUnits;

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

  private static final List<Backend> kBackends = new CopyOnWriteArrayList<>();
  private static volatile boolean s_serviceLoaderScanned;

  private MotorIOFactory() {}

  /**
   * A vendor adapter's entry point into backend selection.
   *
   * <p>Implemented in {@code pumpkinlib-phoenix6} and {@code pumpkinlib-revlib}, declared in each
   * artifact's {@code META-INF/services/org.pumpkinlib.hardware.MotorIOFactory$Backend}, and never
   * named by core.
   */
  public interface Backend {

    /**
     * Whether this adapter builds an IO for the given spec.
     *
     * @param spec the declared motor
     * @return true when {@link #create(MotorSpec, MechanismUnits, ControlConfig, MechanismKind)}
     *     will return a real IO for it
     */
    boolean supports(MotorSpec spec);

    /**
     * Build the IO for a spec this adapter {@link #supports(MotorSpec)}.
     *
     * @param spec the declared motor
     * @param units the mechanism's unit conversion object
     * @param control the declared gains, constraints, gravity model and tolerance
     * @param kind whether the mechanism goes to a place, holds a speed, or is open loop
     * @return the backend IO; never null
     */
    MotorIO create(
        MotorSpec spec, MechanismUnits units, ControlConfig control, MechanismKind kind);

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
   * @param spec the declared motor
   * @param units the mechanism's unit conversion object
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   * @param mode which robot mode is running
   * @return the backend IO; a {@link NoOpMotorIO} when replaying or when no adapter is installed,
   *     never null and never an exception
   */
  public static MotorIO create(
      MotorSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind,
      RobotMode mode) {
    if (spec == null) {
      return new NoOpMotorIO("unnamed motor", "no MotorSpec was declared");
    }
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
        MotorIO io = backend.create(spec, units, control, kind);
        if (io != null) {
          return io;
        }
      }
    }
    Alerts.error(
            spec.name(),
            spec.name()
                + ": no PumpkinLib adapter is installed for a "
                + spec.deviceType()
                + " (vendor \""
                + spec.vendor()
                + "\"), so this mechanism will not move. Fix: add the matching artifact --"
                + " dev.pumpkinlib:pumpkinlib-phoenix6 for CTRE devices,"
                + " dev.pumpkinlib:pumpkinlib-revlib for REV devices -- to your build, alongside"
                + " that vendor's own vendordep.",
            MatchImpact.BLOCKS_MATCH)
        .set(true);
    return new NoOpMotorIO(spec.name(), "no adapter installed for vendor \"" + spec.vendor() + "\"");
  }

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
    }
  }
}

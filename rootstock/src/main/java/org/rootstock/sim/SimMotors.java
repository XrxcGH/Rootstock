package org.rootstock.sim;

import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.wpilibj.simulation.SimDeviceSim;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import org.rootstock.core.RootstockException;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.SimMotorHandle;
import org.rootstock.hardware.MotorIO;

/**
 * Turns whatever a team hands us into a {@link SimMotorHandle}, without naming a vendor type.
 *
 * <h2>Why this is reflection and not four imports</h2>
 *
 * <p>ArchUnit rule 1: the core artifact contains zero {@code com.ctre} and zero {@code com.revrobotics}
 * references, so that a REV-only team installing {@code dev.rootstock:rootstock} never receives
 * Phoenix code. The real, first-class path is therefore not this class at all — it is
 * {@link MotorIO#simHandle()}, implemented inside {@code rootstock-phoenix6} and
 * {@code rootstock-revlib} with ordinary imports, where the vendor's method names are checked by the
 * compiler. {@link #of(Object, double)} tries that first.
 *
 * <p>What reflection buys is the case the seam does not cover: a team that has a bare {@code TalonFX}
 * or a {@code SparkMaxSim} it constructed itself — a prototype, a swerve module built before the
 * mechanism layer, an existing subsystem being migrated — and wants physics on it today. For that,
 * "auto-detect and say precisely what you found if it is not supported" beats "you cannot".
 *
 * <p><b>This class is deliberately loud on failure.</b> Every unsupported input throws a
 * {@link RootstockException} naming the runtime type, because the alternative — returning a handle that
 * silently does nothing — is a mechanism that does not move in {@code simulateJava} with no explanation,
 * which is the single most common way an evening is lost.
 */
public final class SimMotors {

  private SimMotors() {}

  /** Nominal simulated bus voltage, used when a caller passes something non-physical. */
  public static final double kNominalBusVolts = 12.0;

  /**
   * Auto-detects the controller type and returns the handle that drives it.
   *
   * <p>In order:
   *
   * <ol>
   *   <li>a {@link SimMotorHandle} — returned unchanged;
   *   <li>a {@link MotorIO} — its {@link MotorIO#simHandle()}, which is the compiled, vendor-checked
   *       path and the one a declared mechanism always takes;
   *   <li>an object with a no-argument {@code getSimState()} — Phoenix 6's {@code TalonFX},
   *       {@code TalonFXS} and friends;
   *   <li>an object with {@code iterate(double, double, double)} — REVLib's {@code SparkMaxSim} /
   *       {@code SparkFlexSim};
   *   <li>anything else — a thrown {@link RootstockException} naming the type.
   * </ol>
   *
   * @param motorController the controller, its sim state, its {@code MotorIO}, or its handle
   * @param rotorPerOutput rotor rotations per mechanism (output-shaft) rotation — the same number the
   *     device carries in {@code SensorToMechanismRatio} or {@code positionConversionFactor}
   * @return the handle
   * @throws RootstockException if the type is not supported, or if a supported type is missing a method
   *     this version of the vendor library was expected to have
   */
  public static SimMotorHandle of(Object motorController, double rotorPerOutput) {
    Objects.requireNonNull(
        motorController,
        "SimMotors.of: motorController must not be null. Pass the mechanism's MotorIO, its vendor "
            + "controller, or SimMotors.none() if this mechanism genuinely has no device.");
    return find(motorController, rotorPerOutput)
        .orElseThrow(
            () ->
                RootstockException.of(
                    "SimMotors.of: motor controller",
                    motorController.getClass().getName(),
                    "a SimMotorHandle, a Rootstock MotorIO, a Phoenix 6 device (anything with "
                        + "getSimState()), or a REVLib SparkSim (anything with "
                        + "iterate(double, double, double))",
                    "let the mechanism build its MotorIO through MotorIOFactory and pass that — "
                        + "MotorIO.simHandle() is the supported seam and it is checked by the "
                        + "compiler. For a device with no vendor simulation class at all, call "
                        + "RootstockSim.dumpDevices() to find its SimDevice key and use "
                        + "SimMotors.ofSimDevice(...)."));
  }

  /**
   * The non-throwing form of {@link #of(Object, double)}, for a caller that has a fallback.
   *
   * @param motorController the controller, its sim state, its {@code MotorIO}, or its handle
   * @param rotorPerOutput rotor rotations per output rotation
   * @return the handle, or empty when the type is not recognised
   */
  public static Optional<SimMotorHandle> find(Object motorController, double rotorPerOutput) {
    if (motorController == null) {
      return Optional.empty();
    }
    double ratio =
        Double.isFinite(rotorPerOutput) && rotorPerOutput != 0.0 ? rotorPerOutput : 1.0;

    if (motorController instanceof SimMotorHandle handle) {
      return Optional.of(handle);
    }
    if (motorController instanceof MotorIO io) {
      return Optional.of(
          io.simHandle()
              .orElseThrow(
                  () ->
                      RootstockException.of(
                          "SimMotors.of: MotorIO \"" + io.name() + "\"",
                          "simHandle() returned empty",
                          "a backend that can be simulated",
                          "this backend has no simulated device state — NoOpMotorIO is the usual "
                              + "cause, and it is what MotorIOFactory returns in REPLAY mode. There "
                              + "is nothing to simulate in replay, which is correct; if this is a "
                              + "live simulation, check that the mechanism was built with a "
                              + "MotorSpec that has a backend installed.")));
    }

    Method getSimState = noArg(motorController, "getSimState");
    if (getSimState != null) {
      Object simState = invoke(getSimState, motorController, "getSimState");
      // No ratio here on purpose: the plant has already converted to ROTOR units, and Phoenix's
      // setRawRotorPosition is in rotor rotations. Applying it a second time is the exact
      // pre/post-gear-ratio trap this whole seam exists to prevent.
      return Optional.of(new PhoenixStyleHandle(motorController, getSimState, simState));
    }
    if (method(motorController, "iterate", double.class, double.class, double.class) != null) {
      return Optional.of(new RevStyleHandle(motorController, ratio));
    }
    return Optional.empty();
  }

  /**
   * A handle for a device whose vendor ships no simulation class at all, driven through WPILib's
   * {@code SimDeviceSim}.
   *
   * <p>{@code SimDeviceSim} keys are stringly-typed and the required prefix is <b>hidden in the SimGUI
   * by default</b> — it is behind "Show prefix" — so guessing them wastes evenings.
   * {@link RootstockSim#dumpDevices()} prints the exact strings this method wants.
   *
   * @param deviceKey the full device key, e.g. {@code "AnalogInput[0]"}
   * @param appliedOutputField the field carrying the commanded duty cycle or volts
   * @param positionField the field this handle writes rotor position into
   * @param velocityField the field this handle writes rotor velocity into
   * @param currentField the field carrying stator current, or null when the device does not report it
   * @param appliedIsDutyCycle true when {@code appliedOutputField} is a -1..1 duty cycle rather than
   *     volts
   * @return the handle
   * @throws RootstockException if no such device is registered
   */
  public static SimMotorHandle ofSimDevice(
      String deviceKey,
      String appliedOutputField,
      String positionField,
      String velocityField,
      String currentField,
      boolean appliedIsDutyCycle) {
    Objects.requireNonNull(deviceKey, "SimMotors.ofSimDevice: deviceKey must not be null");
    SimDeviceSim device = new SimDeviceSim(deviceKey);
    if (device.getNativeHandle() <= 0) {
      throw RootstockException.of(
          "SimMotors.ofSimDevice: device key",
          '"' + deviceKey + '"',
          "a key printed by RootstockSim.dumpDevices()",
          "construct the device before calling this, then run RootstockSim.dumpDevices() and copy the "
              + "key verbatim — the prefix is hidden in the SimGUI unless \"Show prefix\" is on, so "
              + "the key you can see is usually not the whole key.");
    }
    return new SimDeviceHandle(
        device,
        appliedOutputField,
        positionField,
        velocityField,
        currentField,
        appliedIsDutyCycle);
  }

  /**
   * A handle that commands nothing and reports nothing.
   *
   * <p>For a plant that is deliberately open-loop in simulation — a game-piece model, a fixture — and
   * for a test that wants a registered mechanism with no device. It is a real object rather than a
   * null so that {@code describe()} can say "attached: nothing" instead of "not attached", which are
   * different situations.
   *
   * @return the no-op handle
   */
  public static SimMotorHandle none() {
    return new SimMotorHandle() {
      @Override
      public double appliedVolts(double busVoltage) {
        return 0.0;
      }

      @Override
      public void setRotorPosition(double rotorRotations, double rotorRps) {}

      @Override
      public double statorAmps() {
        return 0.0;
      }
    };
  }

  /**
   * What {@link #of(Object, double)} would make of this object, as a sentence, without throwing.
   *
   * @param motorController the object to inspect
   * @return a human-readable description
   */
  public static String describe(Object motorController) {
    if (motorController == null) {
      return "null";
    }
    String type = motorController.getClass().getName();
    if (motorController instanceof SimMotorHandle) {
      return type + " (already a SimMotorHandle)";
    }
    if (motorController instanceof MotorIO) {
      return type + " (Rootstock MotorIO — the compiled, vendor-checked seam)";
    }
    if (noArg(motorController, "getSimState") != null) {
      return type + " (Phoenix-style: getSimState())";
    }
    if (method(motorController, "iterate", double.class, double.class, double.class) != null) {
      return type + " (REV-style: iterate(velocity, vbus, dt))";
    }
    return type + " (UNSUPPORTED)";
  }

  // ================================================================================ Phoenix style

  /**
   * Phoenix 6's {@code TalonFXSimState} and its siblings, reached reflectively.
   *
   * <p>The method names are Phoenix's documented contract and are the same ones
   * {@code rootstock-phoenix6} calls with real imports: {@code setSupplyVoltage(double)},
   * {@code getMotorVoltage()}, {@code setRawRotorPosition(double)}, {@code setRotorVelocity(double)},
   * {@code getTorqueCurrent()}. The sim state object is re-fetched every call because CTRE's own
   * examples do — it is a lightweight view, not a resource.
   *
   * <p><b>The trap this exists to avoid, stated once.</b> {@code setRawRotorPosition} takes
   * <b>rotor</b> rotations and the device divides by its own configured ratio to report output units.
   * The plant is post-gear-ratio. So the multiply belongs here, on the way in, and getting it backwards
   * produces a device whose onboard PID behaves nothing like reality with nothing to say so.
   */
  private static final class PhoenixStyleHandle implements SimMotorHandle {
    private final Object m_device;
    private final Method m_getSimState;

    private final Method m_setSupplyVoltage;
    private final Method m_getMotorVoltage;
    private final Method m_setRawRotorPosition;
    private final Method m_setRotorVelocity;
    private final Method m_current;

    PhoenixStyleHandle(Object device, Method getSimState, Object simState) {
      m_device = device;
      m_getSimState = getSimState;
      Class<?> type = simState.getClass();
      m_setSupplyVoltage = require(type, "setSupplyVoltage", double.class);
      m_getMotorVoltage = require(type, "getMotorVoltage");
      m_setRawRotorPosition = require(type, "setRawRotorPosition", double.class);
      m_setRotorVelocity = require(type, "setRotorVelocity", double.class);
      Method current = optional(type, "getTorqueCurrent");
      m_current = current != null ? current : optional(type, "getSupplyCurrent");
    }

    @Override
    public double appliedVolts(double busVoltage) {
      Object simState = invoke(m_getSimState, m_device, "getSimState");
      double bus =
          Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : kNominalBusVolts;
      invoke(m_setSupplyVoltage, simState, "setSupplyVoltage", bus);
      return toDouble(invoke(m_getMotorVoltage, simState, "getMotorVoltage"));
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      Object simState = invoke(m_getSimState, m_device, "getSimState");
      if (Double.isFinite(rotorRotations)) {
        invoke(m_setRawRotorPosition, simState, "setRawRotorPosition", rotorRotations);
      }
      if (Double.isFinite(rotorRps)) {
        invoke(m_setRotorVelocity, simState, "setRotorVelocity", rotorRps);
      }
    }

    @Override
    public double statorAmps() {
      if (m_current == null) {
        return 0.0;
      }
      Object simState = invoke(m_getSimState, m_device, "getSimState");
      return Math.abs(toDouble(invoke(m_current, simState, "current")));
    }
  }

  // ==================================================================================== REV style

  /**
   * REVLib's {@code SparkMaxSim} / {@code SparkFlexSim}, reached reflectively.
   *
   * <p><b>{@code iterate}'s velocity argument is in the units the configured encoder reports</b> — that
   * is, after {@code SparkBaseConfig}'s conversion factors, which for a Rootstock-configured SPARK
   * means <i>output</i> rotations per minute, not rotor. So this handle divides the rotor numbers it is
   * given by the ratio on the way in, exactly as {@code SparkMotorIO} does with real imports. That
   * inference is roadmap risk R3 and it is pinned by {@code rootstock-revlib}'s own test; this class
   * mirrors that adapter rather than making a second guess.
   */
  private static final class RevStyleHandle implements SimMotorHandle {
    private final Object m_sim;
    private final double m_ratio;

    private final Method m_iterate;
    private final Method m_getAppliedOutput;
    private final Method m_setPosition;
    private final Method m_setVelocity;
    private final Method m_getMotorCurrent;

    private double m_lastOutputRps;

    RevStyleHandle(Object sim, double ratio) {
      m_sim = sim;
      m_ratio = ratio;
      Class<?> type = sim.getClass();
      m_iterate = require(type, "iterate", double.class, double.class, double.class);
      m_getAppliedOutput = require(type, "getAppliedOutput");
      m_setPosition = require(type, "setPosition", double.class);
      m_setVelocity = require(type, "setVelocity", double.class);
      m_getMotorCurrent = optional(type, "getMotorCurrent");
    }

    @Override
    public double appliedVolts(double busVoltage) {
      double bus =
          Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : kNominalBusVolts;
      invoke(m_iterate, m_sim, "iterate", m_lastOutputRps, bus, Clock.dt());
      return toDouble(invoke(m_getAppliedOutput, m_sim, "getAppliedOutput")) * bus;
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      if (Double.isFinite(rotorRotations)) {
        invoke(m_setPosition, m_sim, "setPosition", rotorRotations / m_ratio);
      }
      if (Double.isFinite(rotorRps)) {
        m_lastOutputRps = rotorRps / m_ratio;
        invoke(m_setVelocity, m_sim, "setVelocity", m_lastOutputRps);
      }
    }

    @Override
    public double statorAmps() {
      return m_getMotorCurrent == null
          ? 0.0
          : Math.abs(toDouble(invoke(m_getMotorCurrent, m_sim, "getMotorCurrent")));
    }
  }

  // ================================================================================== SimDevice

  /** A handle over raw {@code SimDeviceSim} fields, for a vendor with no simulation class. */
  private static final class SimDeviceHandle implements SimMotorHandle {
    private final SimDouble m_applied;
    private final SimDouble m_position;
    private final SimDouble m_velocity;
    private final SimDouble m_current;
    private final boolean m_appliedIsDutyCycle;

    SimDeviceHandle(
        SimDeviceSim device,
        String appliedField,
        String positionField,
        String velocityField,
        String currentField,
        boolean appliedIsDutyCycle) {
      m_applied = appliedField == null ? null : device.getDouble(appliedField);
      m_position = positionField == null ? null : device.getDouble(positionField);
      m_velocity = velocityField == null ? null : device.getDouble(velocityField);
      m_current = currentField == null ? null : device.getDouble(currentField);
      m_appliedIsDutyCycle = appliedIsDutyCycle;
    }

    @Override
    public double appliedVolts(double busVoltage) {
      if (m_applied == null) {
        return 0.0;
      }
      double bus =
          Double.isFinite(busVoltage) && busVoltage > 0.0 ? busVoltage : kNominalBusVolts;
      double value = m_applied.get();
      return m_appliedIsDutyCycle ? value * bus : value;
    }

    @Override
    public void setRotorPosition(double rotorRotations, double rotorRps) {
      if (m_position != null && Double.isFinite(rotorRotations)) {
        m_position.set(rotorRotations);
      }
      if (m_velocity != null && Double.isFinite(rotorRps)) {
        m_velocity.set(rotorRps);
      }
    }

    @Override
    public double statorAmps() {
      return m_current == null ? 0.0 : Math.abs(m_current.get());
    }
  }

  // ================================================================================== reflection

  private static Method noArg(Object target, String name) {
    return method(target, name);
  }

  private static Method method(Object target, String name, Class<?>... parameters) {
    try {
      return target.getClass().getMethod(name, parameters);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static Method optional(Class<?> type, String name, Class<?>... parameters) {
    try {
      return type.getMethod(name, parameters);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static Method require(Class<?> type, String name, Class<?>... parameters) {
    Method found = optional(type, name, parameters);
    if (found != null) {
      return found;
    }
    throw RootstockException.of(
        "SimMotors: " + type.getName() + "." + name,
        "no such method",
        "the method this vendor's simulation API is documented to have",
        "the vendor library on the classpath is a version Rootstock has not been checked against. "
            + "Use the mechanism's MotorIO instead — MotorIO.simHandle() is implemented inside the "
            + "vendor adapter with real imports, so a renamed method is a compile error there rather "
            + "than a runtime surprise here.");
  }

  private static Object invoke(Method method, Object target, String what, Object... arguments) {
    try {
      return method.invoke(target, arguments);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RootstockException(
          "Rootstock: SimMotors could not call "
              + what
              + " on "
              + target.getClass().getName()
              + ". Fix: use the mechanism's MotorIO, whose simHandle() calls the vendor with real "
              + "imports and no reflection.",
          e);
    }
  }

  /**
   * A vendor return value as a double, whether it is a boxed number or a unit-typed measure.
   *
   * <p>Phoenix 6 returns plain doubles from the sim-state getters this class uses today. It has also
   * moved several getters to {@code Measure} across seasons, and a reflective call cannot be
   * recompiled against that change — so the measure case is handled here rather than becoming a
   * {@code ClassCastException} in week one of a new season.
   */
  private static double toDouble(Object value) {
    if (value == null) {
      return 0.0;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    Method baseUnit = optional(value.getClass(), "baseUnitMagnitude");
    if (baseUnit != null) {
      return toDouble(invoke(baseUnit, value, "baseUnitMagnitude"));
    }
    Method magnitude = optional(value.getClass(), "magnitude");
    if (magnitude != null) {
      return toDouble(invoke(magnitude, value, "magnitude"));
    }
    throw RootstockException.of(
        "SimMotors: vendor return value",
        value.getClass().getName(),
        "a number, or a unit-typed Measure",
        "use the mechanism's MotorIO instead — the vendor adapter reads this value with real "
            + "imports and the compiler checks the type.");
  }
}

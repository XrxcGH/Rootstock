package org.rootstock.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.rootstock.control.ControlLocation;
import org.rootstock.core.config.CanIdRegistry;

/**
 * <b>A description of a motor, not a handle to one.</b>
 *
 * <p>{@code MotorSpec.talonFX(20, "rio")} allocates nothing, opens no CAN device and imports no
 * vendor library. It is a value: it can be compared, copied, logged as a struct, varied per robot
 * with a {@code with*()} copy, and — critically — <em>declared in a {@code public static final}
 * field</em> without any risk that the declaration itself does something. The adapters that turn a
 * spec into a live {@code MotorIO} live in the hardware packages, behind the vendor's own import
 * fence.
 *
 * <p>The hierarchy is <b>sealed</b>, so the factory that maps a spec to a backend is a closed set of
 * {@code instanceof} tests with no reachable default that could silently swallow a new vendor. (On
 * the 2027 Java 25 line this becomes an exhaustive pattern-matching switch and adding a vendor
 * becomes a compile-error-driven checklist; the Java 17 form ships first.)
 *
 * <h2>What each variant carries, and why nothing more</h2>
 *
 * <p>Rootstock deliberately does not re-model every vendor knob. A spec carries only what is
 * (a) physical, (b) shared across vendors, or (c) needed to derive a vendor setting. Everything else
 * is reached through the backend's raw-config escape hatch. That is why a {@code TalonFXSpec} has
 * seven components rather than forty.
 */
public sealed interface MotorSpec
    permits
        MotorSpec.TalonFXSpec,
        MotorSpec.TalonFXSSpec,
        MotorSpec.SparkSpec,
        MotorSpec.GenericSpec,
        MotorSpec.SimSpec {

  /** The CAN bus a spec lands on when none is named. Matches the platform's own default. */
  String kDefaultBus = CanIdRegistry.kDefaultBus;

  /**
   * The device id reported by a spec that is not a CAN device (a PWM controller, a simulated motor).
   *
   * <p>Deliberately negative so that a CAN-ID conflict scan can filter it out by a range test rather
   * than by knowing which variants are CAN devices.
   */
  int kNoDeviceId = -1;

  /**
   * The value of {@link #signalRateHz()} that means "use the library default for this mechanism
   * kind" — 100 Hz for a position mechanism, 50 Hz for a velocity mechanism.
   *
   * <p>{@code NaN} rather than {@code 0}, because {@code 0} is a legal (if useless) rate and a
   * sentinel that is also a legal value is how frozen signals happen.
   */
  double kDefaultSignalRate = Double.NaN;

  /**
   * A short human name for this motor, used in log keys, alerts and the no-op backend under replay.
   *
   * @return for example {@code "TalonFX 20 (rio)"}
   */
  String name();

  /**
   * The CAN device id, or {@link #kNoDeviceId} for a spec that is not on a CAN bus.
   *
   * @return the device id
   */
  int deviceId();

  /**
   * The CAN bus name, or {@code ""} for a spec that is not on a CAN bus.
   *
   * <p>SystemCore has several buses, which is why this is a first-class field on every spec today
   * rather than a 2027 retrofit.
   *
   * @return the bus name
   */
  String canBus();

  /**
   * Whether this spec addresses a device on a CAN bus, and therefore participates in the global
   * duplicate-ID scan.
   *
   * @return true for the TalonFX, TalonFXS and SPARK variants
   */
  boolean isCanDevice();

  /**
   * Whether this motor's output is inverted relative to the mechanism's positive direction.
   *
   * <p>Direction lives here and nowhere else: a {@code Reduction} is always positive, so "the
   * mechanism runs backwards" is fixed by flipping this one flag rather than by negating a gear
   * ratio and breaking every derived number.
   *
   * @return true if inverted
   */
  boolean inverted();

  /**
   * Whether field-oriented commutation is requested.
   *
   * <p>FOC changes commutation only. It does <b>not</b> change gain units — see {@link OutputMode}.
   *
   * @return true if FOC is requested; always false on hardware that has no such concept
   */
  boolean foc();

  /**
   * How the device turns a closed-loop output into a command.
   *
   * @return the output mode; always {@link OutputMode#VOLTAGE} except where explicitly overridden
   */
  OutputMode outputMode();

  /**
   * The status-signal rate this device should be polled at, or {@link #kDefaultSignalRate} to take
   * the library default for the mechanism kind.
   *
   * <p>Exists for a team that has <em>measured</em> a bus-utilisation problem, or that wants a
   * tighter velocity loop than the 50 Hz a velocity mechanism gets by default. It is not a knob to
   * turn speculatively: every signal costs bus bandwidth that the drivetrain also needs.
   *
   * @return the rate in hertz, or {@code NaN} for the default
   */
  double signalRateHz();

  /**
   * The physical motor, from which the simulation curve, the free-speed check and the default
   * current limits all follow.
   *
   * @return the motor model
   */
  MotorModel model();

  /**
   * The vendor library this spec will be realised through.
   *
   * @return for example {@code "Phoenix 6"}
   */
  String vendor();

  /**
   * The device type, as {@code CanIdRegistry} and the boot dump name it.
   *
   * @return for example {@code "TalonFX"}
   */
  String deviceType();

  /**
   * Whether this device can close a control loop on itself.
   *
   * <p>False only for {@link GenericSpec}: a PWM controller receives a duty cycle and has no idea
   * what a setpoint is. Asking for an on-motor control location on one is downgraded with a message
   * naming the reason, not silently ignored.
   *
   * @return true if the device can run the loop
   */
  boolean supportsOnMotorControl();

  /**
   * Every problem with this spec that can be seen from the spec alone, as text.
   *
   * <p><b>Never throws, never returns null.</b> A config is a {@code public static final} field; a
   * throw from one surfaces as {@code ExceptionInInitializerError} out of a class initialiser, robot
   * code never starts, and the carefully written message becomes a nested cause under three frames
   * of JVM noise. Problems are values that the registration pipeline prints all at once.
   *
   * @return the problems, in declaration order; empty when the spec is fine
   */
  List<String> problems();

  /**
   * One line naming the device, its bus, its motor and any non-default settings.
   *
   * @return a human-readable description
   */
  String describe();

  /**
   * Where the control loop runs when the team does not say.
   *
   * <p>Revision 1 made {@code .controlLocation(...)} a required builder call, which put an expert
   * question — <em>should the profile and the feedback loop run on the motor controller or on the
   * roboRIO?</em> — on the fifth line of a rookie's first config, where every wrong answer is
   * silently plausible. The field stays required in the control config, so it is always in the log
   * and the snapshot; the default comes from the leader's hardware, and {@code describe()} prints it
   * with its provenance.
   *
   * @return {@link ControlLocation#ON_MOTOR_PROFILED} for a smart controller,
   *     {@link ControlLocation#RIO_FULL} for a PWM one
   */
  default ControlLocation defaultControlLocation() {
    return supportsOnMotorControl() ? ControlLocation.ON_MOTOR_PROFILED : ControlLocation.RIO_FULL;
  }

  /**
   * Whether a signal rate was explicitly asked for.
   *
   * @return true if {@link #signalRateHz()} is a real number
   */
  default boolean hasSignalRateOverride() {
    return !Double.isNaN(signalRateHz());
  }

  /**
   * This spec as a CAN device declaration, for the one global duplicate-ID scan.
   *
   * <p>The scan runs once, in the registration call, over the final resolved config set — never as a
   * side effect of constructing a record, because a record constructor with global side effects
   * turns an innocent {@code with*()} copy into a false "CAN ID conflict".
   *
   * @param owner the mechanism that declares this motor
   * @param role its role within that mechanism, for example {@code "leader"}
   * @return the device, or empty when this spec is not on a CAN bus
   */
  default Optional<CanIdRegistry.Device> canDevice(String owner, String role) {
    if (!isCanDevice()) {
      return Optional.empty();
    }
    return Optional.of(
        CanIdRegistry.Device.on(owner, role, deviceType(), deviceId(), canBus()));
  }

  // ===============================================================================================
  // Factories
  // ===============================================================================================

  /**
   * A TalonFX (Kraken X60 / Falcon 500) on the named bus.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name; blank becomes {@value #kDefaultBus}
   * @return the spec, with FOC off, voltage output and the default signal rate
   */
  static TalonFXSpec talonFX(int deviceId, String canBus) {
    return new TalonFXSpec(
        deviceId,
        canBus,
        false,
        false,
        OutputMode.VOLTAGE,
        kDefaultSignalRate,
        MotorModel.KRAKEN_X60);
  }

  /**
   * A TalonFX on the default {@value #kDefaultBus} bus.
   *
   * @param deviceId the CAN device id
   * @return the spec
   */
  static TalonFXSpec talonFX(int deviceId) {
    return talonFX(deviceId, kDefaultBus);
  }

  /**
   * A TalonFXS on the named bus.
   *
   * <p>The motor arrangement has no default on purpose: a TalonFXS with no commutation configured
   * does nothing at all, with no fault and no message.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name; blank becomes {@value #kDefaultBus}
   * @param arrangement what the device is commutating
   * @return the spec
   */
  static TalonFXSSpec talonFXS(int deviceId, String canBus, MotorArrangement arrangement) {
    return new TalonFXSSpec(
        deviceId, canBus, arrangement, false, false, OutputMode.VOLTAGE, kDefaultSignalRate);
  }

  /**
   * A TalonFXS on the default {@value #kDefaultBus} bus.
   *
   * @param deviceId the CAN device id
   * @param arrangement what the device is commutating
   * @return the spec
   */
  static TalonFXSSpec talonFXS(int deviceId, MotorArrangement arrangement) {
    return talonFXS(deviceId, kDefaultBus, arrangement);
  }

  /**
   * A SPARK MAX or SPARK FLEX. SPARKs live on the roboRIO's own CAN bus.
   *
   * @param deviceId the CAN device id
   * @param sparkModel which controller, driving which motor
   * @return the spec
   */
  static SparkSpec spark(int deviceId, SparkModel sparkModel) {
    return new SparkSpec(deviceId, sparkModel, false, kDefaultSignalRate);
  }

  /**
   * A PWM motor controller — a Talon SRX in PWM mode, a Spark (the old grey one), a Victor SPX.
   *
   * <p>This is the honest floor of the library: no encoder on the controller, no on-board loop, no
   * current limit, no temperature. Everything runs on the roboRIO, and {@code describe()} says so.
   *
   * @param pwmChannel the roboRIO PWM channel
   * @param model what motor is attached, so simulation and the free-speed check still work
   * @return the spec
   */
  static GenericSpec generic(int pwmChannel, MotorModel model) {
    return new GenericSpec(pwmChannel, model, false);
  }

  /**
   * A purely simulated motor, for a team with no vendor libraries installed at all.
   *
   * <p>This is <b>not</b> how the Phoenix and REV backends simulate. Those drive their own vendor sim
   * state so that simulation exercises the real config path, including the device's gearing and its
   * on-board profile. This variant exists so that a laptop with nothing installed can still run a
   * mechanism end to end.
   *
   * @param label a name for the simulated device
   * @param model the motor curve to simulate
   * @return the spec
   */
  static SimSpec sim(String label, MotorModel model) {
    return new SimSpec(label, model, false);
  }

  /**
   * A simulated Kraken X60, for the common case.
   *
   * @return the spec
   */
  static SimSpec sim() {
    return sim("sim", MotorModel.KRAKEN_X60);
  }

  // ===============================================================================================
  // Variants
  // ===============================================================================================

  /**
   * A CTRE TalonFX — a Kraken X60, a Kraken X44 or a Falcon 500.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name
   * @param inverted whether the output is inverted relative to the mechanism's positive direction
   * @param foc whether field-oriented commutation is requested (commutation only — never gain units)
   * @param outputMode voltage or torque current; only voltage is supported in v0.1
   * @param signalRateHz the status-signal rate, or {@link #kDefaultSignalRate}
   * @param model which Kraken or Falcon, for simulation and the free-speed check
   */
  record TalonFXSpec(
      int deviceId,
      String canBus,
      boolean inverted,
      boolean foc,
      OutputMode outputMode,
      double signalRateHz,
      MotorModel model)
      implements MotorSpec {

    /** Normalises the bus name and rejects the two null components that cannot be recovered from. */
    public TalonFXSpec {
      canBus = Specs.normaliseBus(canBus);
      outputMode = Objects.requireNonNull(outputMode, Specs.kNullOutputMode);
      model = Objects.requireNonNull(model, Specs.kNullModel);
    }

    @Override
    public String name() {
      return "TalonFX " + deviceId + " (" + canBus + ")";
    }

    @Override
    public boolean isCanDevice() {
      return true;
    }

    @Override
    public String vendor() {
      return "Phoenix 6";
    }

    @Override
    public String deviceType() {
      return "TalonFX";
    }

    @Override
    public boolean supportsOnMotorControl() {
      return true;
    }

    /**
     * A copy with the inversion flag set.
     *
     * @param value true if this motor's output is inverted
     * @return a copy
     */
    public TalonFXSpec inverted(boolean value) {
      return new TalonFXSpec(deviceId, canBus, value, foc, outputMode, signalRateHz, model);
    }

    /**
     * A copy with field-oriented commutation enabled or disabled.
     *
     * <p>This sets only the FOC flag on the control requests. Gains stay volts-per-SI.
     *
     * @param value true to request FOC
     * @return a copy
     */
    public TalonFXSpec foc(boolean value) {
      return new TalonFXSpec(deviceId, canBus, inverted, value, outputMode, signalRateHz, model);
    }

    /**
     * A copy with a different output mode.
     *
     * @param value the output mode
     * @return a copy
     */
    public TalonFXSpec outputMode(OutputMode value) {
      return new TalonFXSpec(deviceId, canBus, inverted, foc, value, signalRateHz, model);
    }

    /**
     * A copy polled at an explicit status-signal rate.
     *
     * @param hertz the rate in hertz
     * @return a copy
     */
    public TalonFXSpec signalRateHz(double hertz) {
      return new TalonFXSpec(deviceId, canBus, inverted, foc, outputMode, hertz, model);
    }

    /**
     * A copy naming a different motor.
     *
     * @param value the motor model
     * @return a copy
     */
    public TalonFXSpec model(MotorModel value) {
      return new TalonFXSpec(deviceId, canBus, inverted, foc, outputMode, signalRateHz, value);
    }

    /**
     * A copy on a different CAN bus.
     *
     * @param value the bus name
     * @return a copy
     */
    public TalonFXSpec canBus(String value) {
      return new TalonFXSpec(deviceId, value, inverted, foc, outputMode, signalRateHz, model);
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Specs.checkCanId(out, this);
      Specs.checkSignalRate(out, this);
      Specs.checkOutputMode(out, this);
      if (!model.isBrushless()) {
        out.add(
            name()
                + ": model is "
                + model.displayName()
                + ", but a TalonFX only drives its own integrated brushless motor. "
                + "Set the model to KRAKEN_X60, KRAKEN_X44 or FALCON_500, or use talonFXS(...) "
                + "if this is really an external motor.");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return Specs.describeCommon(this) + (foc ? ", FOC on the voltage requests" : "");
    }
  }

  /**
   * A CTRE TalonFXS, which commutates an <em>external</em> motor.
   *
   * @param deviceId the CAN device id
   * @param canBus the CAN bus name
   * @param arrangement what the device is commutating; without this the device does nothing
   * @param inverted whether the output is inverted relative to the mechanism's positive direction
   * @param foc whether field-oriented commutation is requested
   * @param outputMode voltage or torque current; only voltage is supported in v0.1
   * @param signalRateHz the status-signal rate, or {@link #kDefaultSignalRate}
   */
  record TalonFXSSpec(
      int deviceId,
      String canBus,
      MotorArrangement arrangement,
      boolean inverted,
      boolean foc,
      OutputMode outputMode,
      double signalRateHz)
      implements MotorSpec {

    /** Normalises the bus name and rejects the null components that cannot be recovered from. */
    public TalonFXSSpec {
      canBus = Specs.normaliseBus(canBus);
      arrangement =
          Objects.requireNonNull(
              arrangement,
              "MotorSpec.talonFXS: the motor arrangement is required. A TalonFXS with no "
                  + "commutation configured does nothing at all: no fault, no motion, no message. "
                  + "Pass MotorArrangement.MINION_JST, NEO_JST, NEO550_JST, VORTEX_JST or "
                  + "BRUSHED_DC.");
      outputMode = Objects.requireNonNull(outputMode, Specs.kNullOutputMode);
    }

    @Override
    public String name() {
      return "TalonFXS " + deviceId + " (" + canBus + ")";
    }

    @Override
    public boolean isCanDevice() {
      return true;
    }

    @Override
    public MotorModel model() {
      return arrangement.motor();
    }

    @Override
    public String vendor() {
      return "Phoenix 6";
    }

    @Override
    public String deviceType() {
      return "TalonFXS";
    }

    @Override
    public boolean supportsOnMotorControl() {
      return true;
    }

    /**
     * A copy with the inversion flag set.
     *
     * @param value true if this motor's output is inverted
     * @return a copy
     */
    public TalonFXSSpec inverted(boolean value) {
      return new TalonFXSSpec(
          deviceId, canBus, arrangement, value, foc, outputMode, signalRateHz);
    }

    /**
     * A copy with field-oriented commutation enabled or disabled.
     *
     * @param value true to request FOC
     * @return a copy
     */
    public TalonFXSSpec foc(boolean value) {
      return new TalonFXSSpec(
          deviceId, canBus, arrangement, inverted, value, outputMode, signalRateHz);
    }

    /**
     * A copy with a different output mode.
     *
     * @param value the output mode
     * @return a copy
     */
    public TalonFXSSpec outputMode(OutputMode value) {
      return new TalonFXSSpec(deviceId, canBus, arrangement, inverted, foc, value, signalRateHz);
    }

    /**
     * A copy polled at an explicit status-signal rate.
     *
     * @param hertz the rate in hertz
     * @return a copy
     */
    public TalonFXSSpec signalRateHz(double hertz) {
      return new TalonFXSSpec(deviceId, canBus, arrangement, inverted, foc, outputMode, hertz);
    }

    /**
     * A copy on a different CAN bus.
     *
     * @param value the bus name
     * @return a copy
     */
    public TalonFXSSpec canBus(String value) {
      return new TalonFXSSpec(
          deviceId, value, arrangement, inverted, foc, outputMode, signalRateHz);
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Specs.checkCanId(out, this);
      Specs.checkSignalRate(out, this);
      Specs.checkOutputMode(out, this);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return Specs.describeCommon(this)
          + ", commutating "
          + arrangement.describe()
          + (foc ? ", FOC on the voltage requests" : "");
    }
  }

  /**
   * A REV SPARK MAX or SPARK FLEX.
   *
   * <p>SPARKs are always on the roboRIO's own CAN bus, so there is no bus component: REVLib has no
   * concept of a second bus, and offering the field would be a promise the hardware cannot keep.
   *
   * @param deviceId the CAN device id
   * @param sparkModel which controller, driving which motor
   * @param inverted whether the output is inverted relative to the mechanism's positive direction
   * @param signalRateHz the status-frame period expressed as a rate, or {@link #kDefaultSignalRate}
   */
  record SparkSpec(int deviceId, SparkModel sparkModel, boolean inverted, double signalRateHz)
      implements MotorSpec {

    /** Rejects the null component that cannot be recovered from. */
    public SparkSpec {
      sparkModel =
          Objects.requireNonNull(
              sparkModel,
              "MotorSpec.spark: the SPARK model is required. It decides both which controller "
                  + "class is constructed (MAX or FLEX) and whether the motor is driven brushless "
                  + "or brushed. Pass SparkModel.MAX_NEO, MAX_NEO550, FLEX_VORTEX, ...");
    }

    @Override
    public String name() {
      return sparkModel.controllerName() + " " + deviceId;
    }

    @Override
    public String canBus() {
      return kDefaultBus;
    }

    @Override
    public boolean isCanDevice() {
      return true;
    }

    @Override
    public boolean foc() {
      return false;
    }

    @Override
    public OutputMode outputMode() {
      return OutputMode.VOLTAGE;
    }

    @Override
    public MotorModel model() {
      return sparkModel.motor();
    }

    @Override
    public String vendor() {
      return "REVLib";
    }

    @Override
    public String deviceType() {
      return sparkModel.isFlex() ? "SparkFlex" : "SparkMax";
    }

    @Override
    public boolean supportsOnMotorControl() {
      return true;
    }

    /**
     * A copy with the inversion flag set.
     *
     * @param value true if this motor's output is inverted
     * @return a copy
     */
    public SparkSpec inverted(boolean value) {
      return new SparkSpec(deviceId, sparkModel, value, signalRateHz);
    }

    /**
     * A copy polled at an explicit status-frame rate.
     *
     * @param hertz the rate in hertz
     * @return a copy
     */
    public SparkSpec signalRateHz(double hertz) {
      return new SparkSpec(deviceId, sparkModel, inverted, hertz);
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      Specs.checkCanId(out, this);
      Specs.checkSignalRate(out, this);
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return Specs.describeCommon(this) + " (" + sparkModel.describe() + ")";
    }
  }

  /**
   * A PWM motor controller, or anything else with no intelligence on the far end of the wire.
   *
   * <p>This variant is the reason the library's capability reporting is honest: it answers "no" to
   * every on-board question, so the one downgrade message fires once at boot instead of a mechanism
   * quietly behaving differently from its config.
   *
   * @param pwmChannel the roboRIO PWM channel
   * @param model what motor is attached, so simulation and the free-speed check still work
   * @param inverted whether the output is inverted relative to the mechanism's positive direction
   */
  record GenericSpec(int pwmChannel, MotorModel model, boolean inverted) implements MotorSpec {

    /** Rejects the null component that cannot be recovered from. */
    public GenericSpec {
      model = Objects.requireNonNull(model, Specs.kNullModel);
    }

    @Override
    public String name() {
      return "PWM motor " + pwmChannel;
    }

    @Override
    public int deviceId() {
      return kNoDeviceId;
    }

    @Override
    public String canBus() {
      return "";
    }

    @Override
    public boolean isCanDevice() {
      return false;
    }

    @Override
    public boolean foc() {
      return false;
    }

    @Override
    public OutputMode outputMode() {
      return OutputMode.VOLTAGE;
    }

    @Override
    public double signalRateHz() {
      return kDefaultSignalRate;
    }

    @Override
    public String vendor() {
      return "generic";
    }

    @Override
    public String deviceType() {
      return "PWM motor controller";
    }

    @Override
    public boolean supportsOnMotorControl() {
      return false;
    }

    /**
     * A copy with the inversion flag set.
     *
     * @param value true if this motor's output is inverted
     * @return a copy
     */
    public GenericSpec inverted(boolean value) {
      return new GenericSpec(pwmChannel, model, value);
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (pwmChannel < 0 || pwmChannel > 19) {
        out.add(
            name()
                + ": PWM channel "
                + pwmChannel
                + " is outside the addressable range 0..19. Fix: use the channel number printed "
                + "next to the header you plugged the signal wire into (0..9 on a roboRIO).");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return name()
          + ": "
          + model.displayName()
          + ", no encoder, no on-board loop, no current limit, no temperature. "
          + "Everything runs on the roboRIO.";
    }
  }

  /**
   * A purely simulated motor, for a machine with no vendor libraries installed.
   *
   * @param label a name for the simulated device, used in log keys and alerts
   * @param model the motor curve to simulate
   * @param inverted whether the output is inverted relative to the mechanism's positive direction
   */
  record SimSpec(String label, MotorModel model, boolean inverted) implements MotorSpec {

    /** Normalises the label and rejects the null model. */
    public SimSpec {
      label = label == null || label.isBlank() ? "sim" : label.trim();
      model = Objects.requireNonNull(model, Specs.kNullModel);
    }

    @Override
    public String name() {
      return "simulated motor \"" + label + "\"";
    }

    @Override
    public int deviceId() {
      return kNoDeviceId;
    }

    @Override
    public String canBus() {
      return "";
    }

    @Override
    public boolean isCanDevice() {
      return false;
    }

    @Override
    public boolean foc() {
      return false;
    }

    @Override
    public OutputMode outputMode() {
      return OutputMode.VOLTAGE;
    }

    @Override
    public double signalRateHz() {
      return kDefaultSignalRate;
    }

    @Override
    public String vendor() {
      return "simulated";
    }

    @Override
    public String deviceType() {
      return "simulated motor";
    }

    @Override
    public boolean supportsOnMotorControl() {
      return true;
    }

    /**
     * A copy with the inversion flag set.
     *
     * @param value true if this motor's output is inverted
     * @return a copy
     */
    public SimSpec inverted(boolean value) {
      return new SimSpec(label, model, value);
    }

    @Override
    public List<String> problems() {
      return List.of();
    }

    @Override
    public String describe() {
      return name() + ": " + model.describe();
    }
  }

}

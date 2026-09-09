package org.rootstock.telemetry.schema;

import edu.wpi.first.units.Unit;
import edu.wpi.first.units.Units;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.rootstock.control.Gains;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;
import org.rootstock.core.spi.Tier;
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.TelemetrySource;

/**
 * The section 3.1 key block — {@code Rootstock/<Name>/} — as both a published contract and the writer
 * that fills it.
 *
 * <p><b>This class is the "zero user code" promise made mechanical.</b> Every Rootstock mechanism
 * logs setpoint, goal, measured, error, output, current, temperature, limits and state under the
 * same names, in the same units, at the same tiers, because every mechanism calls this one writer
 * rather than seventeen {@code RootstockLog} calls of its own. That consistency is not a tidiness
 * preference: it is the precondition for the five-minute "why did auto fail in match 42" triage, for
 * a shipped AdvantageScope layout that works on a robot the layout's author never saw, and for a
 * student being able to read a teammate's mechanism graph without asking what the keys mean.
 *
 * <h2>Error and GoalError are computed here, not by the caller</h2>
 *
 * <p>{@code Error} is {@code Setpoint - Measured} — how far the mechanism is from <i>this cycle's
 * profile sample</i>. {@code GoalError} is {@code Goal - Measured} — how far it is from where it was
 * told to end up. They are different questions with different answers for the entire duration of a
 * motion profile, and collapsing them into one key was a real bug in an earlier revision of the
 * design. This writer takes {@code goal}, {@code setpoint} and {@code measured} and derives both, so
 * the formula is not something a mechanism author can get wrong.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // once, in the mechanism's constructor:
 * m_schema = MechanismSchema.declare(this);          // calls describe(...) exactly once
 *
 * // every cycle, at the top of periodic():
 * m_io.updateInputs(m_inputs);
 * RootstockLog.processInputs(m_schema.inputsKey(), m_inputs);
 *
 * // every cycle, at the end of periodic():
 * m_schema
 *     .goal(goalMeters)
 *     .setpoint(profileSample)
 *     .setpointVelocity(profileVelocity)
 *     .measured(positionMeters)
 *     .output(volts)
 *     .atSetpoint(atSetpoint)
 *     .atGoal(atGoal)
 *     .state(m_state)
 *     .controlMode(ControlMode.POSITION)
 *     .homed(m_homed)
 *     .softLimits(minMeters, maxMeters)
 *     .currentLimitAmps(limits.supplyLimitAmps())
 *     .simEnabled(simRunning)
 *     .publish();
 * }</pre>
 *
 * <h2>Allocation</h2>
 *
 * <p>One instance per mechanism, created at registration and reused forever. Every absolute key
 * string is concatenated once in the constructor and cached, because {@code "Rootstock/" + name +
 * "/Goal"} inside {@code periodic()} is a string allocation per key per cycle — seventeen of them
 * per mechanism at 50 Hz, which is precisely the kind of garbage the tier design exists to avoid.
 * The staged values live in primitive fields and a single {@code int} bitmask; nothing on the
 * publish path allocates.
 *
 * <h2>A key that was not set this cycle is not published</h2>
 *
 * <p>{@link #publish()} writes only the values staged since the last publish. That is deliberate:
 * republishing last cycle's setpoint because this cycle forgot to set one produces a trace that
 * looks correct and is not, and the section 1.5 schema audit — which compares what
 * {@code describe()} declared against what was published this cycle — is the thing that turns the
 * omission into a named warning. Filling the gap here would hide it from the one check built to
 * catch it.
 */
public final class MechanismSchema {

  // ===============================================================================================
  // The key names. These are a wire contract: AdvantageScope layouts and the replay-diff tool bind
  // to them by literal string. Renaming one is a breaking change for every team that upgrades.
  // ===============================================================================================

  /** The {@code processInputs} key segment. Inputs land at {@code Rootstock/<Name>/Inputs/...}. */
  public static final String kInputs = "Inputs";

  /** Where the mechanism has been told to end up. */
  public static final String kGoal = "Goal";

  /** This cycle's profile sample. */
  public static final String kSetpoint = "Setpoint";

  /** This cycle's profile velocity sample. */
  public static final String kSetpointVelocity = "SetpointVelocity";

  /** Mirror of {@code Inputs/Position}, present so the four ghost traces graph together. */
  public static final String kMeasured = "Measured";

  /** {@code Setpoint - Measured}. */
  public static final String kError = "Error";

  /** {@code Goal - Measured}. */
  public static final String kGoalError = "GoalError";

  /** Commanded voltage, or the equivalent for a non-voltage output mode. */
  public static final String kOutput = "Output";

  /** Whether the mechanism is within tolerance of this cycle's profile sample. */
  public static final String kAtSetpoint = "AtSetpoint";

  /** Whether the mechanism is within tolerance of its goal. */
  public static final String kAtGoal = "AtGoal";

  /** The mechanism's own state enum name. */
  public static final String kState = "State";

  /** One of {@link ControlMode}'s five names. */
  public static final String kControlMode = "ControlMode";

  /** Whether the mechanism has a valid zero. */
  public static final String kHomed = "Homed";

  /** Lower soft limit, logged so "why won't it move" is one glance. */
  public static final String kSoftLimitMin = "SoftLimitMin";

  /** Upper soft limit, logged so "why won't it move" is one glance. */
  public static final String kSoftLimitMax = "SoftLimitMax";

  /** The configured supply current limit. */
  public static final String kCurrentLimitAmps = "CurrentLimitAmps";

  /** Prefix of the seven gain keys. Makes "which gains were on the robot in match 42" answerable. */
  public static final String kGainsPrefix = "Gains/";

  /**
   * Whether the physics sim is driving this mechanism.
   *
   * <p><b>Non-negotiable and CRITICAL.</b> The 2026 "our robot lagged for five seconds" root cause
   * was sim code running on the real robot, and nothing else in the log says so.
   */
  public static final String kSimEnabled = "Sim/Enabled";

  /** The five extras {@code org.rootstock.mechanism} declares, in the order section 3.1 rules on. */
  private static final String kDeviceResetCount = "DeviceResetCount";

  private static final String kFeedbackVolts = "FeedbackVolts";
  private static final String kFeedforwardVolts = "FeedforwardVolts";
  private static final String kBlocked = "Blocked";
  private static final String kPlan = "Plan";

  /** The alert group schema problems are filed under. */
  public static final String kAlertGroup = "Rootstock/Log";

  /** The placeholder the static tables use where a real block carries the mechanism's name. */
  public static final String kNamePlaceholder = "<Name>";

  // Bits of m_set, one per staged value, so publish() can tell "false" from "never set".
  private static final int kBitGoal = 1;
  private static final int kBitSetpoint = 1 << 1;
  private static final int kBitSetpointVelocity = 1 << 2;
  private static final int kBitMeasured = 1 << 3;
  private static final int kBitOutput = 1 << 4;
  private static final int kBitAtSetpoint = 1 << 5;
  private static final int kBitAtGoal = 1 << 6;
  private static final int kBitState = 1 << 7;
  private static final int kBitControlMode = 1 << 8;
  private static final int kBitHomed = 1 << 9;
  private static final int kBitSoftLimits = 1 << 10;
  private static final int kBitCurrentLimit = 1 << 11;
  private static final int kBitSimEnabled = 1 << 12;

  private final String m_name;
  private final String m_prefix;
  private final Tier m_cap;
  private final Unit m_positionUnit;
  private final Unit m_velocityUnit;
  private final int m_motorCount;
  private final Class<? extends Enum<?>> m_stateEnum;
  private final Map<String, ExtraSpec> m_extras;
  private final List<String> m_declaredKeys;

  private final String m_keyInputs;
  private final String m_keyGoal;
  private final String m_keySetpoint;
  private final String m_keySetpointVelocity;
  private final String m_keyMeasured;
  private final String m_keyError;
  private final String m_keyGoalError;
  private final String m_keyOutput;
  private final String m_keyAtSetpoint;
  private final String m_keyAtGoal;
  private final String m_keyState;
  private final String m_keyControlMode;
  private final String m_keyHomed;
  private final String m_keySoftLimitMin;
  private final String m_keySoftLimitMax;
  private final String m_keyCurrentLimitAmps;
  private final String m_keySimEnabled;
  private final String[] m_keyGains;

  private int m_set;
  private double m_goal;
  private double m_setpoint;
  private double m_setpointVelocity;
  private double m_measured;
  private double m_output;
  private boolean m_atSetpoint;
  private boolean m_atGoal;
  private String m_state;
  private ControlMode m_controlMode;
  private boolean m_homed;
  private double m_softLimitMin;
  private double m_softLimitMax;
  private double m_currentLimitAmps;
  private boolean m_simEnabled;

  private Gains m_lastGains;
  private RootstockAlert m_undeclaredAlert;

  /** An extra declared through {@link TelemetryDescriptor}, with its absolute key precomputed. */
  private record ExtraSpec(String absoluteKey, Unit unit, Tier tier) {}

  private MechanismSchema(String prefix, String name, Tier cap, Declarator d) {
    m_name = name;
    m_prefix = prefix;
    m_cap = cap;
    m_positionUnit = d.m_positionUnit;
    m_velocityUnit = d.m_velocityUnit;
    m_motorCount = d.m_motorCount;
    m_stateEnum = d.m_stateEnum;

    m_keyInputs = prefix + '/' + kInputs;
    m_keyGoal = prefix + '/' + kGoal;
    m_keySetpoint = prefix + '/' + kSetpoint;
    m_keySetpointVelocity = prefix + '/' + kSetpointVelocity;
    m_keyMeasured = prefix + '/' + kMeasured;
    m_keyError = prefix + '/' + kError;
    m_keyGoalError = prefix + '/' + kGoalError;
    m_keyOutput = prefix + '/' + kOutput;
    m_keyAtSetpoint = prefix + '/' + kAtSetpoint;
    m_keyAtGoal = prefix + '/' + kAtGoal;
    m_keyState = prefix + '/' + kState;
    m_keyControlMode = prefix + '/' + kControlMode;
    m_keyHomed = prefix + '/' + kHomed;
    m_keySoftLimitMin = prefix + '/' + kSoftLimitMin;
    m_keySoftLimitMax = prefix + '/' + kSoftLimitMax;
    m_keyCurrentLimitAmps = prefix + '/' + kCurrentLimitAmps;
    m_keySimEnabled = prefix + '/' + kSimEnabled;

    m_keyGains = new String[kGainKeyNames.size()];
    for (int i = 0; i < m_keyGains.length; i++) {
      m_keyGains[i] = prefix + '/' + kGainKeyNames.get(i);
    }

    // LinkedHashMap wrapped unmodifiable, not Map.copyOf: Map.copyOf returns a hash-ordered map, and
    // a future reader who iterates this for a layout or an audit would silently lose guarantee G7's
    // "two runs of the same robot produce identical ordering". Nothing iterates it today; the point
    // is that it stays safe when something does.
    Map<String, ExtraSpec> extras = new LinkedHashMap<>();
    for (Map.Entry<String, Declarator.RawExtra> e : d.m_extras.entrySet()) {
      Declarator.RawExtra raw = e.getValue();
      extras.put(e.getKey(), new ExtraSpec(prefix + '/' + e.getKey(), raw.unit(), raw.tier()));
    }
    m_extras = Collections.unmodifiableMap(extras);

    List<String> declared = new ArrayList<>();
    declared.add(m_keyGoal);
    declared.add(m_keySetpoint);
    declared.add(m_keySetpointVelocity);
    declared.add(m_keyMeasured);
    declared.add(m_keyError);
    declared.add(m_keyGoalError);
    declared.add(m_keyOutput);
    declared.add(m_keyAtSetpoint);
    declared.add(m_keyAtGoal);
    declared.add(m_keyState);
    declared.add(m_keyControlMode);
    declared.add(m_keyHomed);
    declared.add(m_keySoftLimitMin);
    declared.add(m_keySoftLimitMax);
    declared.add(m_keyCurrentLimitAmps);
    for (String gainKey : m_keyGains) {
      declared.add(gainKey);
    }
    declared.add(m_keySimEnabled);
    // Insertion order, not extras.keySet() hash order: the audit and the layout generator both walk
    // this list and two runs of the same robot must produce the same ordering (guarantee G7).
    for (Declarator.RawExtra raw : d.m_extras.values()) {
      declared.add(prefix + '/' + raw.key());
    }
    m_declaredKeys = List.copyOf(declared);

    reset();
  }

  // ===============================================================================================
  // Declaration
  // ===============================================================================================

  /**
   * Consumes a source's {@code describe(...)} once and returns the writer for its block at
   * {@code Rootstock/<telemetryName()>/}.
   *
   * <p>The descriptor handed to {@code describe} is sealed the instant this method returns, so a
   * source that stores it and declares later gets a named exception instead of a declaration that
   * silently does nothing. {@code TelemetrySource.describe}'s javadoc says storing it is a bug; this
   * makes that structurally true rather than conventionally true.
   *
   * @param source the mechanism, drivetrain or camera
   * @return the writer, to be held for the life of the source
   */
  public static MechanismSchema declare(TelemetrySource source) {
    Objects.requireNonNull(source, "MechanismSchema.declare: source must not be null.");
    String name = requireName(source);
    return build(RootstockLog.kRoot + '/' + name, name, Tier.CRITICAL, source);
  }

  /**
   * Declares a block nested inside another source's block, with every tier capped.
   *
   * <p>This is what section 3.2's last row asks for: each swerve module publishes <i>the full
   * section 3.1 mechanism block</i> at {@code Rootstock/Drive/Module&lt;i&gt;/}, at STANDARD. Four
   * modules times the full block at CRITICAL would put sixty-eight always-on keys in an FMS log for
   * information that the chassis-level {@code ModuleStates} keys already summarise.
   *
   * @param parentPrefix the enclosing block's absolute prefix, e.g. {@code "Rootstock/Drive"}
   * @param source the nested source; its {@code telemetryName()} is the final path segment
   * @param cap the strongest tier any key in this block may use; {@code CRITICAL} means "no cap"
   * @return the writer
   */
  public static MechanismSchema declareNested(String parentPrefix, TelemetrySource source, Tier cap) {
    Objects.requireNonNull(parentPrefix, "MechanismSchema.declareNested: parentPrefix must not be null.");
    Objects.requireNonNull(source, "MechanismSchema.declareNested: source must not be null.");
    Objects.requireNonNull(cap, "MechanismSchema.declareNested: cap must not be null.");
    if (cap == Tier.DEBUG) {
      throw new IllegalArgumentException(
          "MechanismSchema.declareNested: cap was DEBUG for parentPrefix \""
              + parentPrefix
              + "\". A DEBUG cap would drop the whole mechanism block whenever the FMS gate fires, "
              + "so Setpoint, Measured, Error and AtGoal — the four traces match triage reads — "
              + "would be absent from exactly the logs that matter. Pass CRITICAL or STANDARD.");
    }
    String name = requireName(source);
    return build(parentPrefix + '/' + name, name, cap, source);
  }

  private static MechanismSchema build(String prefix, String name, Tier cap, TelemetrySource source) {
    Declarator declarator = new Declarator();
    source.describe(declarator);
    declarator.seal();
    return new MechanismSchema(prefix, name, cap, declarator);
  }

  private static String requireName(TelemetrySource source) {
    String name = source.telemetryName();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "TelemetrySource.telemetryName() returned "
              + (name == null ? "null" : "a blank string")
              + " for "
              + source.getClass().getName()
              + ". The name becomes a log key segment, so it must be a stable, unique, path-safe "
              + "string such as \"Elevator\" or \"Module0\".");
    }
    return name;
  }

  // ===============================================================================================
  // What was declared
  // ===============================================================================================

  /**
   * The source's {@code telemetryName()}.
   *
   * @return the name, e.g. {@code "Elevator"}
   */
  public String name() {
    return m_name;
  }

  /**
   * The absolute key prefix, with no trailing slash.
   *
   * @return e.g. {@code "Rootstock/Elevator"} or {@code "Rootstock/Drive/Module0"}
   */
  public String prefix() {
    return m_prefix;
  }

  /**
   * The key to hand {@code RootstockLog.processInputs}, so inputs land at
   * {@code Rootstock/<Name>/Inputs/...} exactly as section 3.1's table says.
   *
   * <p>Exists so no mechanism spells the {@code "Inputs"} segment itself. A mechanism that passes
   * its bare prefix instead publishes {@code Rootstock/Elevator/Connected}, which every shipped layout
   * and every replay tool will look for in the wrong place.
   *
   * @return the inputs key
   */
  public String inputsKey() {
    return m_keyInputs;
  }

  /**
   * The declared position unit — Meters for a linear axis, Degrees for a rotary one.
   *
   * @return the unit, or null if {@code describe} declared none
   */
  public Unit positionUnit() {
    return m_positionUnit;
  }

  /**
   * The declared velocity unit.
   *
   * @return the unit, or null if {@code describe} declared none
   */
  public Unit velocityUnit() {
    return m_velocityUnit;
  }

  /**
   * The declared motor count, so {@code ./gradlew logBudget} can size the per-motor arrays before an
   * event instead of measuring them at one.
   *
   * @return the count; 1 when {@code describe} declared none
   */
  public int motorCount() {
    return m_motorCount;
  }

  /**
   * The declared state enum, for layout generation.
   *
   * @return the enum class, or null if {@code describe} declared none
   */
  public Class<? extends Enum<?>> stateEnum() {
    return m_stateEnum;
  }

  /**
   * The strongest tier any key in this block may use.
   *
   * @return {@code CRITICAL} for a top-level mechanism, {@code STANDARD} for a swerve module block
   */
  public Tier tierCap() {
    return m_cap;
  }

  /**
   * Every absolute key this block is contracted to publish each cycle, insertion-ordered.
   *
   * <p>This is the expected set the section 1.5 schema audit compares against what was actually
   * published. {@code Error} and {@code GoalError} are in it even though no caller sets them
   * directly — they are derived, and a derived key is still a key a layout binds to.
   *
   * @return an immutable list
   */
  public List<String> declaredKeys() {
    return m_declaredKeys;
  }

  /**
   * The absolute key of a declared extra.
   *
   * @param key the relative key passed to {@code TelemetryDescriptor.extra}
   * @return the absolute key, or null if it was never declared
   */
  public String extraKey(String key) {
    ExtraSpec spec = m_extras.get(key);
    return spec == null ? null : spec.absoluteKey();
  }

  // ===============================================================================================
  // Staging — one fluent chain per cycle, no allocation
  // ===============================================================================================

  /**
   * Stages where the mechanism has been told to end up, in the declared position unit.
   *
   * @param v the goal
   * @return this writer
   */
  public MechanismSchema goal(double v) {
    m_goal = v;
    m_set |= kBitGoal;
    return this;
  }

  /**
   * Stages this cycle's profile sample, in the declared position unit.
   *
   * @param v the setpoint
   * @return this writer
   */
  public MechanismSchema setpoint(double v) {
    m_setpoint = v;
    m_set |= kBitSetpoint;
    return this;
  }

  /**
   * Stages this cycle's profile velocity sample, in the declared velocity unit.
   *
   * @param v the setpoint velocity
   * @return this writer
   */
  public MechanismSchema setpointVelocity(double v) {
    m_setpointVelocity = v;
    m_set |= kBitSetpointVelocity;
    return this;
  }

  /**
   * Stages the measured position, in the declared position unit.
   *
   * <p>This is the mirror of {@code Inputs/Position} that section 3.1 requires as an output. It is a
   * duplicate on purpose: {@code Setpoint}, {@code Measured}, {@code Error} and {@code AtGoal} are
   * the four traces the triage workflow reads frame by frame, and they have to graph together
   * without a student having to know that one of them lives in a different part of the log.
   *
   * @param v the measured position
   * @return this writer
   */
  public MechanismSchema measured(double v) {
    m_measured = v;
    m_set |= kBitMeasured;
    return this;
  }

  /**
   * Stages the commanded output in volts.
   *
   * @param volts the commanded voltage, or its equivalent for a non-voltage output mode
   * @return this writer
   */
  public MechanismSchema output(double volts) {
    m_output = volts;
    m_set |= kBitOutput;
    return this;
  }

  /**
   * Stages whether the mechanism is within tolerance of this cycle's profile sample.
   *
   * @param v true when within tolerance
   * @return this writer
   */
  public MechanismSchema atSetpoint(boolean v) {
    m_atSetpoint = v;
    m_set |= kBitAtSetpoint;
    return this;
  }

  /**
   * Stages whether the mechanism is within tolerance of its goal.
   *
   * @param v true when within tolerance
   * @return this writer
   */
  public MechanismSchema atGoal(boolean v) {
    m_atGoal = v;
    m_set |= kBitAtGoal;
    return this;
  }

  /**
   * Stages the mechanism's own state, published as {@code name()}.
   *
   * <p>Takes {@code Enum<?>} rather than a generic parameter so a mechanism can pass whatever state
   * enum it declared without this class knowing the type. The published type stays {@code String},
   * as section 3.1's table says.
   *
   * @param v the state constant; null stages nothing
   * @return this writer
   */
  public MechanismSchema state(Enum<?> v) {
    if (v != null) {
      m_state = v.name();
      m_set |= kBitState;
    }
    return this;
  }

  /**
   * Stages the mechanism's control mode.
   *
   * @param v the mode; null stages nothing
   * @return this writer
   */
  public MechanismSchema controlMode(ControlMode v) {
    if (v != null) {
      m_controlMode = v;
      m_set |= kBitControlMode;
    }
    return this;
  }

  /**
   * Stages whether the mechanism has a valid zero.
   *
   * @param v true when homed
   * @return this writer
   */
  public MechanismSchema homed(boolean v) {
    m_homed = v;
    m_set |= kBitHomed;
    return this;
  }

  /**
   * Stages both soft limits, in the declared position unit.
   *
   * <p>One call for the pair because a log carrying one limit and not the other answers "why won't
   * it move" only half the time, and half an answer at an event is worse than none.
   *
   * @param min the lower soft limit
   * @param max the upper soft limit
   * @return this writer
   */
  public MechanismSchema softLimits(double min, double max) {
    m_softLimitMin = min;
    m_softLimitMax = max;
    m_set |= kBitSoftLimits;
    return this;
  }

  /**
   * Stages the configured supply current limit.
   *
   * @param amps the limit in amps
   * @return this writer
   */
  public MechanismSchema currentLimitAmps(double amps) {
    m_currentLimitAmps = amps;
    m_set |= kBitCurrentLimit;
    return this;
  }

  /**
   * Stages whether the physics sim is driving this mechanism.
   *
   * @param v true when sim is driving it
   * @return this writer
   */
  public MechanismSchema simEnabled(boolean v) {
    m_simEnabled = v;
    m_set |= kBitSimEnabled;
    return this;
  }

  // ===============================================================================================
  // Publishing
  // ===============================================================================================

  /**
   * Writes every value staged since the last call, derives {@code Error} and {@code GoalError}, and
   * clears the staging area.
   *
   * <p>Call it once per cycle, at the end of the mechanism's {@code periodic()}.
   */
  public void publish() {
    if ((m_set & kBitGoal) != 0) {
      publishDouble(m_keyGoal, m_goal, m_positionUnit, Tier.CRITICAL);
    }
    if ((m_set & kBitSetpoint) != 0) {
      publishDouble(m_keySetpoint, m_setpoint, m_positionUnit, Tier.CRITICAL);
    }
    if ((m_set & kBitSetpointVelocity) != 0) {
      publishDouble(m_keySetpointVelocity, m_setpointVelocity, m_velocityUnit, Tier.STANDARD);
    }
    if ((m_set & kBitMeasured) != 0) {
      publishDouble(m_keyMeasured, m_measured, m_positionUnit, Tier.CRITICAL);
    }
    // Error is measured-vs-SETPOINT and GoalError is measured-vs-GOAL. Two keys, two formulas,
    // derived here so no mechanism can collapse them into one.
    if ((m_set & (kBitSetpoint | kBitMeasured)) == (kBitSetpoint | kBitMeasured)) {
      publishDouble(m_keyError, m_setpoint - m_measured, m_positionUnit, Tier.CRITICAL);
    }
    if ((m_set & (kBitGoal | kBitMeasured)) == (kBitGoal | kBitMeasured)) {
      publishDouble(m_keyGoalError, m_goal - m_measured, m_positionUnit, Tier.STANDARD);
    }
    if ((m_set & kBitOutput) != 0) {
      publishDouble(m_keyOutput, m_output, Units.Volts, Tier.CRITICAL);
    }
    if ((m_set & kBitAtSetpoint) != 0) {
      publishBoolean(m_keyAtSetpoint, m_atSetpoint, Tier.CRITICAL);
    }
    if ((m_set & kBitAtGoal) != 0) {
      publishBoolean(m_keyAtGoal, m_atGoal, Tier.CRITICAL);
    }
    if ((m_set & kBitState) != 0) {
      publishString(m_keyState, m_state, Tier.CRITICAL);
    }
    if ((m_set & kBitControlMode) != 0) {
      publishString(m_keyControlMode, m_controlMode.name(), Tier.STANDARD);
    }
    if ((m_set & kBitHomed) != 0) {
      publishBoolean(m_keyHomed, m_homed, Tier.CRITICAL);
    }
    if ((m_set & kBitSoftLimits) != 0) {
      publishDouble(m_keySoftLimitMin, m_softLimitMin, m_positionUnit, Tier.STANDARD);
      publishDouble(m_keySoftLimitMax, m_softLimitMax, m_positionUnit, Tier.STANDARD);
    }
    if ((m_set & kBitCurrentLimit) != 0) {
      publishDouble(m_keyCurrentLimitAmps, m_currentLimitAmps, Units.Amps, Tier.STANDARD);
    }
    if ((m_set & kBitSimEnabled) != 0) {
      publishBoolean(m_keySimEnabled, m_simEnabled, Tier.CRITICAL);
    }
    reset();
  }

  /**
   * Publishes the seven gains, <b>on change only</b>.
   *
   * <p>Publishes nothing when the value equals the last one published, which is every cycle but the
   * first and the ones on either side of a tuning edit. Seven doubles at 50 Hz forever to record a
   * number that changes twice a season is the definition of a key that should be event-driven, and
   * "which gains were on the robot in match 42" is answerable from a step trace exactly as well as
   * from a continuous one.
   *
   * <p>Not staged and not cleared by {@link #publish()}: call it whenever the gains are known, in
   * any order relative to the staging chain.
   *
   * @param gains the currently active gains; null publishes nothing
   */
  public void gains(Gains gains) {
    if (gains == null || gains.equals(m_lastGains)) {
      return;
    }
    m_lastGains = gains;
    publishDouble(m_keyGains[0], gains.kP(), null, Tier.STANDARD);
    publishDouble(m_keyGains[1], gains.kI(), null, Tier.STANDARD);
    publishDouble(m_keyGains[2], gains.kD(), null, Tier.STANDARD);
    publishDouble(m_keyGains[3], gains.kS(), null, Tier.STANDARD);
    publishDouble(m_keyGains[4], gains.kV(), null, Tier.STANDARD);
    publishDouble(m_keyGains[5], gains.kA(), null, Tier.STANDARD);
    publishDouble(m_keyGains[6], gains.kG(), null, Tier.STANDARD);
  }

  /**
   * Publishes a declared numeric extra at the tier and unit {@code describe(...)} gave it.
   *
   * @param key the relative key, exactly as passed to {@code TelemetryDescriptor.extra}
   * @param v the value
   */
  public void extra(String key, double v) {
    ExtraSpec spec = spec(key);
    if (spec != null) {
      publishDouble(spec.absoluteKey(), v, spec.unit(), spec.tier());
    }
  }

  /**
   * Publishes a declared count extra at the tier {@code describe(...)} gave it.
   *
   * <p>This is the shape {@code DeviceResetCount} uses, and the reason it is CRITICAL: it is the key
   * that says a motor controller rebooted mid-match, it can only ever be read <i>after</i> the match
   * that went wrong, and there is no second copy.
   *
   * @param key the relative key
   * @param v the value
   */
  public void extra(String key, long v) {
    ExtraSpec spec = spec(key);
    if (spec != null) {
      publishLong(spec.absoluteKey(), v, spec.tier());
    }
  }

  /**
   * Publishes a declared boolean extra at the tier {@code describe(...)} gave it.
   *
   * @param key the relative key
   * @param v the value
   */
  public void extra(String key, boolean v) {
    ExtraSpec spec = spec(key);
    if (spec != null) {
      publishBoolean(spec.absoluteKey(), v, spec.tier());
    }
  }

  /**
   * Publishes a declared String extra at the tier {@code describe(...)} gave it.
   *
   * @param key the relative key
   * @param v the value
   */
  public void extra(String key, String v) {
    ExtraSpec spec = spec(key);
    if (spec != null) {
      publishString(spec.absoluteKey(), v, spec.tier());
    }
  }

  /**
   * Publishes a declared String array extra at the tier {@code describe(...)} gave it.
   *
   * @param key the relative key
   * @param v the value
   */
  public void extra(String key, String[] v) {
    ExtraSpec spec = spec(key);
    if (spec == null) {
      return;
    }
    if (effective(spec.tier()) == Tier.CRITICAL) {
      RootstockLog.critical(spec.absoluteKey(), v);
    } else {
      RootstockLog.log(spec.absoluteKey(), v);
    }
  }

  // ===============================================================================================
  // Internals
  // ===============================================================================================

  /**
   * Looks up a declared extra, refusing — loudly, once — to publish one that was never declared.
   *
   * <p>Refusing rather than publishing anyway is the section 1.5 contract: no key under
   * {@code Rootstock/<Name>/} may exist that {@code describe()} did not declare, because a schema that
   * can grow silently is a schema no diff can police. The alert names the mechanism, the key and the
   * fix, and it is a warning rather than a throw because a telemetry mistake must never be the
   * outage.
   */
  private ExtraSpec spec(String key) {
    ExtraSpec spec = m_extras.get(key);
    if (spec == null) {
      undeclared(key);
    }
    return spec;
  }

  private void undeclared(String key) {
    String message =
        "SCHEMA_UNDECLARED: "
            + m_name
            + " published \""
            + key
            + "\" without declaring it. Add d.extra(\""
            + key
            + "\", <unit>, Tier.STANDARD) — or the unit-free d.extra(\""
            + key
            + "\", Tier.STANDARD) for a count or a String — to that mechanism's describe(...). Until"
            + " then the value is dropped, because an undeclared key makes the published schema"
            + " undiffable and breaks every AdvantageScope layout bound to this block.";
    if (m_undeclaredAlert == null) {
      m_undeclaredAlert = Alerts.warning(kAlertGroup, message, MatchImpact.PIT_ONLY);
    } else {
      m_undeclaredAlert.text(message);
    }
    m_undeclaredAlert.sticky(true).set(true);
  }

  /** Raises a tier to the block's cap. Never lowers one: a cap makes a block quieter, never louder. */
  private Tier effective(Tier tier) {
    return tier.ordinal() < m_cap.ordinal() ? m_cap : tier;
  }

  private void publishDouble(String key, double v, Unit unit, Tier tier) {
    Tier t = effective(tier);
    if (unit == null) {
      if (t == Tier.CRITICAL) {
        RootstockLog.critical(key, v);
      } else {
        RootstockLog.log(key, v);
      }
    } else if (t == Tier.CRITICAL) {
      RootstockLog.critical(key, v, unit);
    } else {
      RootstockLog.log(key, v, unit);
    }
  }

  private void publishLong(String key, long v, Tier tier) {
    if (effective(tier) == Tier.CRITICAL) {
      RootstockLog.critical(key, v);
    } else {
      RootstockLog.log(key, v);
    }
  }

  private void publishBoolean(String key, boolean v, Tier tier) {
    if (effective(tier) == Tier.CRITICAL) {
      RootstockLog.critical(key, v);
    } else {
      RootstockLog.log(key, v);
    }
  }

  private void publishString(String key, String v, Tier tier) {
    if (effective(tier) == Tier.CRITICAL) {
      RootstockLog.critical(key, v);
    } else {
      RootstockLog.log(key, v);
    }
  }

  private void reset() {
    m_set = 0;
    m_goal = Double.NaN;
    m_setpoint = Double.NaN;
    m_setpointVelocity = Double.NaN;
    m_measured = Double.NaN;
    m_output = Double.NaN;
    m_atSetpoint = false;
    m_atGoal = false;
    m_state = null;
    m_controlMode = null;
    m_homed = false;
    m_softLimitMin = Double.NaN;
    m_softLimitMax = Double.NaN;
    m_currentLimitAmps = Double.NaN;
    m_simEnabled = false;
  }

  /**
   * The seven gain key names, in the order {@link #gains(Gains)} publishes them.
   *
   * <p>Order is P, I, D, S, V, A, G — {@code Gains}'s own record component order. Matching it means
   * the publish loop and the record cannot drift apart when a gain is added.
   */
  private static final List<String> kGainKeyNames =
      List.of(
          kGainsPrefix + "kP",
          kGainsPrefix + "kI",
          kGainsPrefix + "kD",
          kGainsPrefix + "kS",
          kGainsPrefix + "kV",
          kGainsPrefix + "kA",
          kGainsPrefix + "kG");

  // ===============================================================================================
  // The published contract
  // ===============================================================================================

  /**
   * The replayable inputs half of section 3.1, at {@code Rootstock/<Name>/Inputs/}.
   *
   * <p><b>The Demotable column is empty and that is structural, not an oversight.</b> No key that
   * reaches the log through {@code processInputs} is ever demotable, at any tier, under any load:
   * demoting one would make {@code fromLog} return a stale value on four cycles in five during
   * REPLAY while the real robot read a fresh one, and every output derived from it would diverge.
   *
   * @return the rows, in the order section 3.1 lists them
   */
  public static List<SchemaEntry> inputSchema() {
    String p = RootstockLog.kRoot + "/" + kNamePlaceholder + "/" + kInputs + "/";
    return List.of(
        SchemaEntry.of(p + "Connected", "boolean[] (per motor)", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + "Position", "double", "position", Tier.CRITICAL),
        SchemaEntry.of(p + "Velocity", "double", "velocity", Tier.CRITICAL),
        SchemaEntry.of(p + "AbsolutePosition", "double", "position", Tier.STANDARD),
        SchemaEntry.of(p + "AppliedVolts", "double[]", "volts", Tier.CRITICAL),
        SchemaEntry.of(p + "SupplyCurrentAmps", "double[]", "amps", Tier.CRITICAL),
        SchemaEntry.of(p + "StatorCurrentAmps", "double[]", "amps", Tier.STANDARD),
        SchemaEntry.of(p + "TempCelsius", "double[]", "celsius", Tier.STANDARD),
        SchemaEntry.of(p + "LimitForward", "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + "LimitReverse", "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + "StickyFaults", "String[]", SchemaEntry.kNoUnit, Tier.STANDARD));
  }

  /**
   * The outputs half of section 3.1, at {@code Rootstock/<Name>/}.
   *
   * <p><b>None of these is demotable.</b> {@code Setpoint}, {@code Measured}, {@code Error} and
   * {@code AtGoal} are exactly the four traces the triage workflow reads frame by frame and the ones
   * a student watches while learning to tune. A gap in them under load is a gap at precisely the
   * moment the answer is in them.
   *
   * @return the rows, in the order section 3.1 lists them
   */
  public static List<SchemaEntry> outputSchema() {
    String p = RootstockLog.kRoot + "/" + kNamePlaceholder + "/";
    return List.of(
        SchemaEntry.of(p + kGoal, "double", "position", Tier.CRITICAL, "Where the mechanism has been told to end up"),
        SchemaEntry.of(p + kSetpoint, "double", "position", Tier.CRITICAL, "This cycle's profile sample"),
        SchemaEntry.of(p + kSetpointVelocity, "double", "velocity", Tier.STANDARD),
        SchemaEntry.of(p + kMeasured, "double", "position", Tier.CRITICAL, "Mirror of Inputs/Position; present so the four ghost traces graph together"),
        SchemaEntry.of(p + kError, "double", "position", Tier.CRITICAL, "Setpoint - Measured"),
        SchemaEntry.of(p + kGoalError, "double", "position", Tier.STANDARD, "Goal - Measured"),
        SchemaEntry.of(p + kOutput, "double", "volts", Tier.CRITICAL, "Commanded voltage (or equivalent)"),
        SchemaEntry.of(p + kAtSetpoint, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kAtGoal, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kState, "String", SchemaEntry.kNoUnit, Tier.CRITICAL, "Mechanism's own enum name"),
        SchemaEntry.of(p + kControlMode, "String", SchemaEntry.kNoUnit, Tier.STANDARD, "POSITION/VELOCITY/VOLTAGE/NEUTRAL/HOMING"),
        SchemaEntry.of(p + kHomed, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL),
        SchemaEntry.of(p + kSoftLimitMin, "double", "position", Tier.STANDARD, "Logged so a \"why won't it move\" is one glance"),
        SchemaEntry.of(p + kSoftLimitMax, "double", "position", Tier.STANDARD, "Logged so a \"why won't it move\" is one glance"),
        SchemaEntry.of(p + kCurrentLimitAmps, "double", "amps", Tier.STANDARD),
        SchemaEntry.of(p + kGainsPrefix + "kP", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kI", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kD", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kS", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kV", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kA", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kGainsPrefix + "kG", "double", SchemaEntry.kNoUnit, Tier.STANDARD, "Logged on change only"),
        SchemaEntry.of(p + kSimEnabled, "boolean", SchemaEntry.kNoUnit, Tier.CRITICAL, "Non-negotiable: the 2026 \"our robot lagged for 5 seconds\" root cause was sim code running on the real robot"));
  }

  /**
   * The five extras the mechanism domain declares through {@code TelemetryDescriptor.extra(...)},
   * with section 3.1's tier ruling on each.
   *
   * <p>{@code DeviceResetCount} is <b>CRITICAL</b>, overruling the STANDARD its call site originally
   * used. STANDARD does survive today's FMS gate, but {@code minimumTier} is a {@code LogConfig}
   * field a team under a byte squeeze can set to CRITICAL at 11 pm at an event, and CRITICAL is the
   * only tier whose presence does not depend on a decision someone might make in a hurry. The
   * declaration and the publish must move together; the schema audit is what catches it if only one
   * does.
   *
   * @return the rows
   */
  public static List<SchemaEntry> extraSchema() {
    String p = RootstockLog.kRoot + "/" + kNamePlaceholder + "/";
    return List.of(
        SchemaEntry.of(p + kDeviceResetCount, "long", SchemaEntry.kNoUnit, Tier.CRITICAL, "A controller rebooted mid-match. There is no second copy of that fact."),
        SchemaEntry.of(p + kFeedbackVolts, "double", "volts", Tier.STANDARD, "A gain-tuning trace; RIO_FULL / RIO_PROFILE_MOTOR_LOOP axes only"),
        SchemaEntry.of(p + kFeedforwardVolts, "double", "volts", Tier.STANDARD, "Companion to FeedbackVolts; the pair is meaningless split across tiers"),
        SchemaEntry.of(p + kBlocked, "String", SchemaEntry.kNoUnit, Tier.STANDARD, "Superstructure diagnostics; the fact is already in State and Rootstock/Health/**"),
        SchemaEntry.of(p + kPlan, "String[]", SchemaEntry.kNoUnit, Tier.STANDARD, "Planned transition sequence; reconstructible from the CRITICAL State trace"));
  }

  // ===============================================================================================
  // The descriptor handed to describe()
  // ===============================================================================================

  /**
   * The {@link TelemetryDescriptor} implementation, alive for exactly the duration of one
   * {@code describe(...)} call.
   *
   * <p>Sealed on return, so a source that stashes the reference and declares from its
   * {@code periodic()} gets a named exception rather than a declaration that silently arrives after
   * the writer was built from an empty one.
   */
  private static final class Declarator implements TelemetryDescriptor {

    private record RawExtra(String key, Unit unit, Tier tier) {}

    private final Map<String, RawExtra> m_extras = new LinkedHashMap<>();
    private Unit m_positionUnit;
    private Unit m_velocityUnit;
    private int m_motorCount = 1;
    private Class<? extends Enum<?>> m_stateEnum;
    private boolean m_open = true;

    void seal() {
      m_open = false;
    }

    private void requireOpen(String method) {
      if (!m_open) {
        throw new IllegalStateException(
            "TelemetryDescriptor."
                + method
                + " was called after describe(...) returned. The descriptor is valid only for the"
                + " duration of that one call and storing it is a bug — declare everything inside"
                + " describe(...), which telemetry calls exactly once at registration.");
      }
    }

    @Override
    public TelemetryDescriptor positionUnit(Unit unit) {
      requireOpen("positionUnit");
      m_positionUnit = Objects.requireNonNull(unit, "TelemetryDescriptor.positionUnit: unit must not be null.");
      return this;
    }

    @Override
    public TelemetryDescriptor velocityUnit(Unit unit) {
      requireOpen("velocityUnit");
      m_velocityUnit = Objects.requireNonNull(unit, "TelemetryDescriptor.velocityUnit: unit must not be null.");
      return this;
    }

    @Override
    public TelemetryDescriptor motorCount(int n) {
      requireOpen("motorCount");
      if (n < 1) {
        throw new IllegalArgumentException(
            "TelemetryDescriptor.motorCount was "
                + n
                + "; it must be at least 1. The count sizes the per-motor arrays for ./gradlew"
                + " logBudget, and a zero would report a mechanism as free.");
      }
      m_motorCount = n;
      return this;
    }

    @Override
    public TelemetryDescriptor states(Class<? extends Enum<?>> stateEnum) {
      requireOpen("states");
      m_stateEnum = Objects.requireNonNull(stateEnum, "TelemetryDescriptor.states: stateEnum must not be null.");
      return this;
    }

    @Override
    public TelemetryDescriptor extra(String key, Unit unit, Tier tier) {
      requireOpen("extra");
      Objects.requireNonNull(unit, "TelemetryDescriptor.extra: unit must not be null; use the unit-free overload.");
      return record(key, unit, tier);
    }

    @Override
    public TelemetryDescriptor extra(String key, Tier tier) {
      requireOpen("extra");
      return record(key, null, tier);
    }

    private TelemetryDescriptor record(String key, Unit unit, Tier tier) {
      Objects.requireNonNull(key, "TelemetryDescriptor.extra: key must not be null.");
      Objects.requireNonNull(tier, "TelemetryDescriptor.extra: tier must not be null.");
      if (key.isBlank()) {
        throw new IllegalArgumentException("TelemetryDescriptor.extra: key must not be blank.");
      }
      RawExtra previous = m_extras.put(key, new RawExtra(key, unit, tier));
      if (previous != null && (previous.unit() != unit || previous.tier() != tier)) {
        throw new IllegalArgumentException(
            "TelemetryDescriptor.extra declared \""
                + key
                + "\" twice with different metadata ("
                + previous.tier()
                + "/"
                + (previous.unit() == null ? "no unit" : previous.unit().name())
                + " then "
                + tier
                + "/"
                + (unit == null ? "no unit" : unit.name())
                + "). One key means one type, one unit and one tier for the whole session, or the"
                + " log is a stream whose meaning changes partway through.");
      }
      return this;
    }
  }
}

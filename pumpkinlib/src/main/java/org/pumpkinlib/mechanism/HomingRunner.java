package org.pumpkinlib.mechanism;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import org.pumpkinlib.config.HomingStrategy;
import org.pumpkinlib.config.SensorSpec;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.hardware.MotorIO;
import org.pumpkinlib.hardware.MotorInputs;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The runtime for every {@link HomingStrategy} — the code that actually drives a mechanism into a
 * hard stop and lives to tell about it.
 *
 * <p>{@code HomingStrategy} is a value: it says <i>what</i> should happen. This class is the state
 * machine that makes it happen, and it is the highest-risk code in the mechanism layer, because the
 * failure mode is not a wrong number on a plot — it is a motor stalled against a hard stop until
 * something burns. Every guarantee below exists because of that.
 *
 * <h2>The guarantees, restated as code</h2>
 *
 * <ul>
 *   <li><b>Every motion strategy has a timeout.</b> {@link #periodic()} accumulates {@link
 *       Clock#dt()} and gives up at {@code timeoutSeconds}, commands neutral, leaves {@link
 *       #isHomed()} false and raises {@code <name>/homing-timed-out}. A routine that cannot end is
 *       not a routine.
 *   <li><b>The drive voltage is already clamped</b> to {@link HomingStrategy#kMaxHomingVolts} by the
 *       strategy record itself, and this class clamps it again on the way to the IO, because a
 *       strategy reconstructed by hand from components can carry anything.
 *   <li><b>The current spike is debounced.</b> A motor's startup inrush looks exactly like a hard
 *       stop for the first few loops; without the debounce the mechanism seeds its position in
 *       mid-air and every goal afterwards is wrong by however far it had left to travel.
 *   <li><b>Contact is followed by a backoff</b>, so the mechanism is not left resting on the stop
 *       with kG fighting it, and the seeded value is corrected by exactly the distance backed off.
 *   <li><b>Homing refuses to start</b> in SAFE_MODE, while disabled, when the strategy itself is
 *       misconfigured, or when the mechanism's own {@code periodic()} has already failed. Refusing
 *       to start is free; starting with an unknown current limit is not.
 *   <li><b>Every abort names its cause</b> through {@link AbortReason}, which is published as
 *       {@code Homing/AbortReason} so the question is answerable from a log file alone.
 * </ul>
 *
 * <h2>Reported deviation — device soft-limit suspension</h2>
 *
 * <p>{@code design/01} §6.3 specifies that homing start suspends the <i>device</i> soft limits and
 * lowers the device stator limit through an {@code applyVerified} transaction, and that homing
 * completion restores them. {@link MotorIO} as built exposes no verified partial-config apply — its
 * only blocking config call is {@link MotorIO#reapplyFullConfigBlocking()}, which restores the
 * declared configuration rather than replacing part of it. So this runtime does <b>not</b> disable
 * device soft limits, and it says so out loud: {@code Homing/LimitsSuspended} publishes {@code
 * false} every cycle and {@link #describe()} prints the deviation. What is <i>not</i> lost is the
 * protection that mattered most in that section: the drive voltage is still clamped to 3 V, the
 * routine is still bounded by a timeout, and the Java-side goal clamp — the layer §6.3 says is never
 * suspended anyway — is untouched. When the seam grows a verified partial apply, this class gains
 * the transaction and the two keys start telling the truth about a real suspension.
 *
 * <h2>Composites fall through, they do not fail fast</h2>
 *
 * <p>{@link HomingStrategy.Composite} exists for "absolute encoder if it is there, otherwise drive
 * into the stop", so a strategy that cannot run — no absolute source attached, a timeout, a switch
 * that never asserted — advances to the next one and records why. Only when the last one is
 * exhausted does the whole routine fail.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>Architecture rule 11. Everything reports: an alert, an {@link AbortReason}, a neutral output.
 */
public final class HomingRunner {

  /** The smallest sane grace period before "the current signal is NaN" is treated as fatal, in s. */
  public static final double kSignalGraceSeconds = 0.25;

  /** The give-up time for the backoff leg, as a multiple of the strategy's own timeout. */
  public static final double kBackoffTimeoutFraction = 0.5;

  /** Where the routine is right now. */
  public enum Phase {
    /** Not running. Either never started, or finished. */
    IDLE,

    /** Driving toward the reference — a stop, or a switch. */
    DRIVING,

    /** Contact was made; retreating {@code backoffUserUnits} before seeding. */
    BACKING_OFF,

    /** Finished successfully; the position has been seeded. */
    COMPLETE,

    /** Finished unsuccessfully. {@link #isHomed()} is false and stays false. */
    FAILED
  }

  /**
   * Why a homing attempt stopped early.
   *
   * <p>Published as {@code Homing/AbortReason}. Every value names a physically distinct cause,
   * because "homing failed" is not an answer anybody can act on at an event.
   */
  public enum AbortReason {
    /** No abort — either not started, or finished successfully. */
    NONE,

    /** The routine ran out of time. The mechanism never found its reference. */
    TIMEOUT,

    /** The device stopped answering mid-routine. */
    DISCONNECTED,

    /** The device rebooted mid-routine, so it came back with factory configuration. */
    DEVICE_RESET,

    /** A follower's position drifted from its leader's by more than the configured tolerance. */
    FOLLOWER_DISAGREEMENT,

    /** The limit switch at the <i>other</i> end asserted, so the drive direction is mis-signed. */
    OPPOSITE_LIMIT,

    /** The robot was disabled. Homing while enabled only. */
    DISABLED,

    /** SAFE_MODE is active. A mechanism the library knows is mis-declared does not actuate. */
    SAFE_MODE,

    /** The declared strategy has problems of its own; it was never safe to run. */
    MISCONFIGURED,

    /** No strategy was declared at all. */
    NO_STRATEGY,

    /** The trigger signal this strategy needs is not being read — a NaN stator current. */
    NO_TRIGGER_SIGNAL,

    /** {@link HomingStrategy.AbsoluteSeed} with no absolute source attached to this mechanism. */
    NO_ABSOLUTE_SOURCE,

    /** The absolute sensor and the rotor estimate disagree by more than the declared tolerance. */
    ABSOLUTE_DISAGREEMENT,

    /** A device configuration apply the routine depends on did not land. */
    CONFIG_APPLY_FAILED,

    /** Something cancelled the routine — a command interrupt, or a new goal. */
    INTERRUPTED
  }

  /**
   * Everything the runner needs to observe that only the mechanism can answer.
   *
   * <p>An interface rather than five constructor lambdas so that a reader can see, in one place,
   * exactly how much of the mechanism this class is allowed to see: a position, a current, two
   * switch questions and an absolute reading. It cannot command anything except through the {@link
   * MotorIO} it was given.
   */
  public interface Environment {

    /**
     * The mechanism's position in user units, as the mechanism itself computes it.
     *
     * @return metres or degrees
     */
    double measuredUser();

    /**
     * The stator current the spike detector watches.
     *
     * @return amps, or {@link Double#NaN} when the signal is not subscribed
     */
    double statorAmps();

    /**
     * Whether the switch a {@link HomingStrategy.LimitSwitch} names is asserting right now.
     *
     * @param sensor the sensor the strategy declared; never null when this is called
     * @return true when the switch says "here"
     */
    boolean limitAsserted(SensorSpec sensor);

    /**
     * Whether the hard limit at the far end of travel is asserting.
     *
     * <p>This is the mis-signed-direction detector: driving REVERSE and tripping the FORWARD limit
     * means the sign is wrong, and continuing would drive into the stop the routine was trying to
     * move away from.
     *
     * @param direction the direction the routine is driving
     * @return true when the opposite end's switch is asserting
     */
    boolean oppositeLimitAsserted(HomingStrategy.Direction direction);

    /**
     * The absolute sensor's reading in user units, when this mechanism has one.
     *
     * @return the absolute position, or empty when there is no absolute source
     */
    OptionalDouble absolutePositionUser();

    /**
     * Whether the absolute source is fused into the rotor on the device.
     *
     * <p>A fused CANcoder makes {@link HomingStrategy.AbsoluteSeed} a deliberate no-op: Phoenix
     * fuses on the device, so re-seeding in Java would fight it.
     *
     * @return true when the device owns the fusion
     */
    boolean absoluteIsFusedOnDevice();
  }

  private final String m_name;
  private final MechanismUnits m_units;
  private final MotorIO m_io;
  private final MotorInputs m_inputs;
  private final Environment m_env;
  private final HomingStrategy m_declared;
  private final List<HomingStrategy> m_plan;
  private final double m_followerToleranceRot;

  private final PumpkinAlert m_timedOut;
  private final PumpkinAlert m_failed;
  private final PumpkinAlert m_refused;
  private final PumpkinAlert m_limitsUnrestored;
  private final PumpkinAlert m_assumed;

  private Phase m_phase = Phase.IDLE;
  private AbortReason m_reason = AbortReason.NONE;
  private int m_index;
  private boolean m_homed;
  private boolean m_succeeded;
  private double m_elapsed;
  private double m_debounceHeld;
  private double m_triggerValue = Double.NaN;
  private double m_contactUser = Double.NaN;
  private double m_commandedVolts;
  private long m_resetCountAtStart;
  private String m_lastFailureDetail = "";

  /**
   * Builds the runtime for one mechanism's declared strategy.
   *
   * <p>May throw, like every constructor in this package: it runs at boot from a mechanism's own
   * constructor, and a null IO or a null unit conversion is a library misuse rather than a team
   * configuration error.
   *
   * @param mechanismName the owning mechanism's name, for every alert and log key
   * @param units the mechanism's unit conversion, used to turn a seed position into output rotations
   * @param io the hardware seam this routine commands
   * @param inputs the mechanism's live inputs, read for the abort checks
   * @param strategy the declared strategy; null becomes an empty composite, which refuses to run
   * @param environment what only the mechanism can answer
   * @param followerToleranceRot how far a follower may drift before the routine aborts
   * @throws NullPointerException if {@code units}, {@code io}, {@code inputs} or {@code environment}
   *     is null
   */
  public HomingRunner(
      String mechanismName,
      MechanismUnits units,
      MotorIO io,
      MotorInputs inputs,
      HomingStrategy strategy,
      Environment environment,
      double followerToleranceRot) {
    m_name = mechanismName == null || mechanismName.isBlank() ? "(unnamed)" : mechanismName.trim();
    m_units = Objects.requireNonNull(units, "HomingRunner: units must not be null");
    m_io = Objects.requireNonNull(io, "HomingRunner: io must not be null");
    m_inputs = Objects.requireNonNull(inputs, "HomingRunner: inputs must not be null");
    m_env = Objects.requireNonNull(environment, "HomingRunner: environment must not be null");
    m_declared = strategy == null ? HomingStrategy.firstOf() : strategy;
    m_plan = flatten(m_declared);
    m_followerToleranceRot =
        Double.isFinite(followerToleranceRot) && followerToleranceRot > 0.0
            ? followerToleranceRot
            : Double.POSITIVE_INFINITY;

    m_timedOut =
        Alerts.error(m_name, m_name + "/homing-timed-out", MatchImpact.BLOCKS_MATCH).sticky(true);
    m_failed =
        Alerts.error(m_name, m_name + "/homing-failed", MatchImpact.BLOCKS_MATCH).sticky(true);
    m_refused = Alerts.warning(m_name, m_name + "/homing-refused", MatchImpact.PIT_ONLY);
    m_limitsUnrestored =
        Alerts.error(m_name, m_name + "/homing-limits-unrestored", MatchImpact.BLOCKS_MATCH)
            .sticky(true);
    m_assumed =
        Alerts.warning(m_name, m_name + "/position-assumed-at-boot", MatchImpact.PIT_ONLY)
            .sticky(true);
  }

  // ===============================================================================================
  // Control
  // ===============================================================================================

  /**
   * Start the routine, or refuse and say why.
   *
   * <p>A strategy that needs no motion — an absolute seed, or a boot-time assumption — completes
   * inside this call, so a mechanism whose homing is instantaneous never enters {@link
   * MechanismMode#HOMING} for a single loop and never publishes a one-frame flicker of {@code
   * Homing/Active}.
   *
   * @param safeMode whether SAFE_MODE is active, passed in so this class never reads global state
   *     the mechanism has already read this loop
   * @return true when the routine started or completed; false when it refused
   */
  public boolean begin(boolean safeMode) {
    if (m_plan.isEmpty()) {
      return refuse(
          AbortReason.NO_STRATEGY,
          "no homing strategy was declared, so this mechanism can never know where it is. "
              + "Fix: .homing(HomingStrategy.currentSpike().direction(REVERSE).seedTo(Inches.of(0)))"
              + " on the config, or .homing(HomingStrategy.absoluteSeed()) if it has an encoder.");
    }
    if (safeMode) {
      return refuse(
          AbortReason.SAFE_MODE,
          "SAFE_MODE is active, so no mechanism actuates. Fix the configuration errors printed at "
              + "boot and reboot; homing drives deliberately into a hard stop and is the last thing "
              + "that should run against limits the library knows are wrong.");
    }
    if (needsMotionAnywhere() && MatchContext.isDisabled()) {
      return refuse(
          AbortReason.DISABLED,
          "the robot is disabled and this strategy has to move the mechanism. Expected: enabled. "
              + "Enable the robot, then run homing; homeCommand() is deliberately not "
              + "ignoringDisable().");
    }
    List<String> problems = m_declared.problems();
    if (!problems.isEmpty()) {
      return refuse(AbortReason.MISCONFIGURED, String.join(" ", problems));
    }

    m_index = 0;
    m_elapsed = 0.0;
    m_debounceHeld = 0.0;
    m_succeeded = false;
    m_reason = AbortReason.NONE;
    m_lastFailureDetail = "";
    m_resetCountAtStart = m_inputs.deviceResetCount;
    m_timedOut.set(false);
    m_failed.set(false);
    m_refused.set(false);
    startCurrent();
    return m_phase != Phase.FAILED;
  }

  /**
   * One loop of the routine. Does nothing unless the routine is running.
   *
   * <p>Called from the owning mechanism's {@code onPeriodic()} while its mode is {@link
   * MechanismMode#HOMING}. Every abort check runs before any strategy-specific logic, so no
   * strategy can forget one.
   *
   * @param safeMode whether SAFE_MODE is active
   */
  public void periodic(boolean safeMode) {
    if (m_phase != Phase.DRIVING && m_phase != Phase.BACKING_OFF) {
      return;
    }
    m_elapsed += dt();

    if (safeMode) {
      abortCurrent(AbortReason.SAFE_MODE, "SAFE_MODE went active mid-routine.");
      return;
    }
    if (MatchContext.isDisabled()) {
      abortCurrent(AbortReason.DISABLED, "the robot was disabled mid-routine.");
      return;
    }
    if (!m_inputs.connected) {
      abortCurrent(
          AbortReason.DISCONNECTED,
          "the leader ("
              + m_io.name()
              + ") stopped answering mid-routine, so the trigger signal cannot be trusted.");
      return;
    }
    if (m_inputs.deviceResetCount > m_resetCountAtStart) {
      abortCurrent(
          AbortReason.DEVICE_RESET,
          "the device rebooted mid-routine and came back with factory configuration — no current "
              + "limits and no soft limits. Check power wiring and the CAN bus.");
      return;
    }
    double drift = followerDriftRot();
    if (drift > m_followerToleranceRot) {
      abortCurrent(
          AbortReason.FOLLOWER_DISAGREEMENT,
          String.format(
              Locale.ROOT,
              "a follower drifted %.3f output rotations from its leader, past the %.3f tolerance, "
                  + "which is a slipped belt or a stripped gear rather than a hard stop.",
              drift,
              m_followerToleranceRot));
      return;
    }

    HomingStrategy active = m_plan.get(m_index);
    if (active instanceof HomingStrategy.CurrentSpike spike) {
      stepCurrentSpike(spike);
    } else if (active instanceof HomingStrategy.LimitSwitch limit) {
      stepLimitSwitch(limit);
    } else {
      // A non-motion strategy can never be in DRIVING; if it somehow is, end it rather than spin.
      abortCurrent(AbortReason.MISCONFIGURED, "a strategy that needs no motion was left driving.");
    }
  }

  /**
   * Stop the routine now, command neutral, and record why.
   *
   * <p>Called when a homing command is interrupted, when a new goal arrives, and on the mechanism's
   * own failure paths. Idempotent, and safe to call when nothing is running.
   *
   * @param reason why the routine is being stopped
   */
  public void cancel(AbortReason reason) {
    if (m_phase != Phase.DRIVING && m_phase != Phase.BACKING_OFF) {
      return;
    }
    m_commandedVolts = 0.0;
    neutral();
    m_phase = Phase.FAILED;
    m_reason = reason == null ? AbortReason.INTERRUPTED : reason;
    m_succeeded = false;
  }

  /**
   * Re-seed from the absolute sensor while the mechanism is idle, if that is safe.
   *
   * <p>The guarded re-seed of {@code design/01} §3.10: read the absolute sensor, compare it against
   * the rotor-derived position, and refuse to seed when they disagree by more than the declared
   * tolerance — because a disagreement means one of the two ratios is wrong and seeding would bake
   * the error in. Never runs during a move: shifting the reference frame mid-motion makes the
   * mechanism land off target.
   *
   * @param idle whether the mechanism is currently commanding nothing
   * @return true when a seed was written this call
   */
  public boolean maybeReseed(boolean idle) {
    if (!idle || m_phase == Phase.DRIVING || m_phase == Phase.BACKING_OFF) {
      return false;
    }
    HomingStrategy.AbsoluteSeed seed = firstAbsoluteSeed();
    if (seed == null || m_env.absoluteIsFusedOnDevice()) {
      return false;
    }
    OptionalDouble absolute = m_env.absolutePositionUser();
    if (absolute.isEmpty() || Double.isNaN(absolute.getAsDouble())) {
      return false;
    }
    double tolerance = seed.agreementToleranceUserUnits();
    double rotor = m_env.measuredUser();
    if (tolerance > 0.0 && Double.isFinite(rotor) && Math.abs(absolute.getAsDouble() - rotor) > tolerance) {
      return false;
    }
    m_io.seedPosition(m_units.toOutputRotations(absolute.getAsDouble()));
    m_triggerValue = absolute.getAsDouble();
    m_homed = true;
    return true;
  }

  /**
   * Declare the mechanism homed at a position, without running a routine.
   *
   * <p>The escape hatch for a team that zeroes a mechanism from a dashboard button or from a
   * superstructure that knows something the library does not.
   *
   * @param userUnits where the mechanism actually is, in metres or degrees
   */
  public void seedTo(double userUnits) {
    if (!Double.isFinite(userUnits)) {
      return;
    }
    m_io.seedPosition(m_units.toOutputRotations(userUnits));
    m_homed = true;
    m_succeeded = true;
    m_reason = AbortReason.NONE;
    m_phase = Phase.COMPLETE;
  }

  /**
   * Forget that this mechanism was ever homed.
   *
   * <p>Used by the device-reset recovery path: a controller that rebooted came back with factory
   * configuration, so whatever position it reports is fiction until the routine runs again.
   */
  public void invalidate() {
    m_homed = false;
    m_succeeded = false;
  }

  // ===============================================================================================
  // State
  // ===============================================================================================

  /**
   * Whether a routine is driving the mechanism right now.
   *
   * @return true while the mode should be {@link MechanismMode#HOMING}
   */
  public boolean isActive() {
    return m_phase == Phase.DRIVING || m_phase == Phase.BACKING_OFF;
  }

  /**
   * Whether the mechanism's reported position corresponds to physical reality.
   *
   * <p>False until a strategy completes, and false again after a device reset. This is what {@code
   * atGoal()} and every superstructure interlock read: a mechanism that does not know where it is
   * cannot be at a goal.
   *
   * @return true when the position has been established this power cycle
   */
  public boolean isHomed() {
    return m_homed;
  }

  /**
   * Whether the most recent attempt finished successfully.
   *
   * @return true after a completed routine, false after a refusal or an abort
   */
  public boolean succeeded() {
    return m_succeeded;
  }

  /**
   * Where the state machine is.
   *
   * @return the phase
   */
  public Phase phase() {
    return m_phase;
  }

  /**
   * Why the last attempt stopped early.
   *
   * @return the reason; {@link AbortReason#NONE} when nothing has gone wrong
   */
  public AbortReason abortReason() {
    return m_reason;
  }

  /**
   * How long the current or most recent attempt has been running.
   *
   * @return seconds, accumulated from {@link Clock#dt()} so it replays exactly
   */
  public double elapsedSeconds() {
    return m_elapsed;
  }

  /**
   * The value the active strategy is watching — stator amps, a switch as 1/0, or an absolute
   * reading.
   *
   * @return the trigger value, or NaN before the routine starts
   */
  public double triggerValue() {
    return m_triggerValue;
  }

  /**
   * Whether device soft limits are currently suspended.
   *
   * <p>Always false in this build; see the class javadoc's reported deviation. It is published every
   * cycle anyway so that "were the soft limits actually put back?" is answerable from a log alone
   * the day the seam grows a verified partial apply.
   *
   * @return false
   */
  public boolean limitsSuspended() {
    return false;
  }

  /**
   * Whether the limit restore at homing completion was verified.
   *
   * @return true, because nothing was suspended and therefore nothing needed restoring
   */
  public boolean limitsRestoreVerified() {
    return !m_limitsUnrestored.isActive();
  }

  /**
   * The strategy currently being attempted, as a short label for {@code Homing/Strategy}.
   *
   * @return the simple name of the active strategy, or {@code "none"}
   */
  public String strategyLabel() {
    if (m_plan.isEmpty()) {
      return "none";
    }
    int i = Math.min(m_index, m_plan.size() - 1);
    return m_plan.get(i).getClass().getSimpleName();
  }

  /**
   * The voltage this routine last commanded, for the mechanism's {@code Output} key.
   *
   * @return volts; zero when the routine is not driving
   */
  public double commandedVolts() {
    return m_commandedVolts;
  }

  /**
   * The declared strategy, unchanged.
   *
   * @return the strategy this runner was built from
   */
  public HomingStrategy strategy() {
    return m_declared;
  }

  /**
   * The routine as the boot dump prints it, including the clamps the library applies and the one
   * deviation from {@code design/01} §6.3 this build carries.
   *
   * @return a human-readable multi-clause description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("homing:  ").append(m_declared.describe());
    sb.append(System.lineSeparator())
        .append("           drive voltage clamped to ")
        .append(HomingStrategy.kMaxHomingVolts)
        .append(" V; timeout enforced from Clock.dt(); aborts on disconnect, device reset, ")
        .append("follower disagreement, opposite limit, disable and SAFE_MODE.");
    sb.append(System.lineSeparator())
        .append("           device soft limits are NOT suspended by this build: MotorIO exposes no ")
        .append("verified partial config apply, so Homing/LimitsSuspended publishes false. The ")
        .append("Java-side goal clamp is unaffected and was never suspended.");
    return sb.toString();
  }

  // ===============================================================================================
  // Strategy steps
  // ===============================================================================================

  private void startCurrent() {
    while (m_index < m_plan.size()) {
      HomingStrategy active = m_plan.get(m_index);
      m_elapsed = 0.0;
      m_debounceHeld = 0.0;
      m_contactUser = Double.NaN;
      if (active instanceof HomingStrategy.AbsoluteSeed seed) {
        if (runAbsoluteSeed(seed)) {
          return;
        }
      } else if (active instanceof HomingStrategy.AssumeAtBoot assume) {
        runAssumeAtBoot(assume);
        return;
      } else if (active instanceof HomingStrategy.CurrentSpike spike) {
        m_phase = Phase.DRIVING;
        m_triggerValue = m_env.statorAmps();
        drive(spike.signedVolts());
        return;
      } else if (active instanceof HomingStrategy.LimitSwitch limit) {
        m_phase = Phase.DRIVING;
        m_triggerValue = m_env.limitAsserted(limit.sensor()) ? 1.0 : 0.0;
        if (m_triggerValue > 0.5) {
          // Already on the switch. Level, never an edge: seed and stop rather than driving off it.
          neutral();
          complete(limit.seedToUserUnits());
          return;
        }
        drive(limit.signedVolts());
        return;
      }
      m_index++;
    }
    exhausted();
  }

  private boolean runAbsoluteSeed(HomingStrategy.AbsoluteSeed seed) {
    if (m_env.absoluteIsFusedOnDevice()) {
      // Deliberate no-op: Phoenix fuses the CANcoder into the rotor on the device, so a Java-side
      // seed would fight it. The mechanism is nevertheless homed — the device already knows.
      m_triggerValue = m_env.measuredUser();
      complete(Double.NaN);
      return true;
    }
    OptionalDouble absolute = m_env.absolutePositionUser();
    if (absolute.isEmpty() || Double.isNaN(absolute.getAsDouble())) {
      noteFailure(
          AbortReason.NO_ABSOLUTE_SOURCE,
          "absoluteSeed was declared but no absolute encoder is attached to this mechanism, so "
              + "there is nothing to seed from. Fix: attach one with "
              + "PositionMechanism.setAbsoluteEncoder(io), or declare a fallback with "
              + "HomingStrategy.firstOf(absoluteSeed(), currentSpike()).");
      return false;
    }
    double value = absolute.getAsDouble();
    double tolerance = seed.agreementToleranceUserUnits();
    double rotor = m_env.measuredUser();
    // The guard compares two estimates of the same quantity, which is only meaningful once the
    // rotor estimate means something. At boot the rotor reads zero by definition, so guarding the
    // FIRST seed would refuse every correct configuration; the guard applies to a re-seed.
    if (m_homed && tolerance > 0.0 && Double.isFinite(rotor) && Math.abs(value - rotor) > tolerance) {
      noteFailure(
          AbortReason.ABSOLUTE_DISAGREEMENT,
          String.format(
              Locale.ROOT,
              "the absolute sensor reads %.4f and the rotor estimate reads %.4f, a disagreement of "
                  + "%.4f past the %.4f tolerance. Expected them to agree. One of the two ratios is "
                  + "wrong, and seeding now would bake the error in: check the reduction and the "
                  + "sensor-to-mechanism ratio before re-running.",
              value,
              rotor,
              Math.abs(value - rotor),
              tolerance));
      return false;
    }
    m_triggerValue = value;
    complete(value);
    return true;
  }

  private void runAssumeAtBoot(HomingStrategy.AssumeAtBoot assume) {
    m_triggerValue = assume.seedToUserUnits();
    m_assumed
        .text(
            m_name
                + "/position-assumed-at-boot: the position was ASSUMED to be "
                + String.format(Locale.ROOT, "%.4f", assume.seedToUserUnits())
                + " user units. No sensor confirms it. If the mechanism was moved by hand while "
                + "disabled, every goal and every gravity feedforward is now wrong. Fix: add an "
                + "absolute encoder, or a hard stop plus HomingStrategy.currentSpike().")
        .set(true);
    complete(assume.seedToUserUnits());
  }

  private void stepCurrentSpike(HomingStrategy.CurrentSpike spike) {
    if (m_phase == Phase.BACKING_OFF) {
      stepBackoff(spike);
      return;
    }
    if (m_env.oppositeLimitAsserted(spike.direction())) {
      abortCurrent(
          AbortReason.OPPOSITE_LIMIT,
          "the limit switch at the opposite end asserted while driving "
              + spike.direction()
              + ", which means the homing direction is mis-signed. Fix: "
              + ".direction(HomingStrategy.Direction."
              + spike.direction().opposite()
              + ").");
      return;
    }
    double amps = m_env.statorAmps();
    m_triggerValue = amps;
    if (Double.isNaN(amps)) {
      if (m_elapsed > kSignalGraceSeconds) {
        abortCurrent(
            AbortReason.NO_TRIGGER_SIGNAL,
            "the stator current signal is NaN, so a current spike can never be detected and this "
                + "routine would drive into the stop until it timed out. Expected a subscribed "
                + "StatorCurrent signal. Fix: use a backend that reads stator current, or home "
                + "against a switch with HomingStrategy.limitSwitch(...).");
        return;
      }
      drive(spike.signedVolts());
      return;
    }
    if (amps >= spike.currentThresholdAmps()) {
      m_debounceHeld += dt();
    } else {
      m_debounceHeld = 0.0;
    }
    if (m_debounceHeld >= spike.debounceSeconds()) {
      // Contact. Stop pushing before doing anything else — every line after this one runs with the
      // motor already commanded neutral.
      neutral();
      m_contactUser = m_env.measuredUser();
      if (spike.backoffUserUnits() <= 0.0 || !Double.isFinite(m_contactUser)) {
        complete(spike.seedToUserUnits());
        return;
      }
      m_phase = Phase.BACKING_OFF;
      m_elapsed = 0.0;
      drive(-spike.signedVolts());
      return;
    }
    if (m_elapsed >= spike.timeoutSeconds()) {
      timeout(spike.timeoutSeconds());
      return;
    }
    drive(spike.signedVolts());
  }

  private void stepBackoff(HomingStrategy.CurrentSpike spike) {
    double moved = Math.abs(m_env.measuredUser() - m_contactUser);
    m_triggerValue = moved;
    if (moved >= spike.backoffUserUnits()) {
      neutral();
      // At contact the mechanism was AT seedTo; it has since retreated `backoff` in the direction
      // opposite to the drive, so the position to declare is offset by exactly that much.
      complete(spike.seedToUserUnits() - spike.direction().sign() * spike.backoffUserUnits());
      return;
    }
    if (m_elapsed >= spike.timeoutSeconds() * kBackoffTimeoutFraction) {
      // The stop was found, which is the hard part; the retreat did not finish. Seeding at the stop
      // is still correct and is better than throwing away a successful contact.
      neutral();
      complete(spike.seedToUserUnits());
      return;
    }
    drive(-spike.signedVolts());
  }

  private void stepLimitSwitch(HomingStrategy.LimitSwitch limit) {
    if (m_env.oppositeLimitAsserted(limit.direction())) {
      abortCurrent(
          AbortReason.OPPOSITE_LIMIT,
          "the limit switch at the opposite end asserted while driving "
              + limit.direction()
              + ", so the homing direction is mis-signed. Fix: .direction("
              + limit.direction().opposite()
              + ").");
      return;
    }
    boolean asserted = m_env.limitAsserted(limit.sensor());
    m_triggerValue = asserted ? 1.0 : 0.0;
    if (asserted) {
      neutral();
      complete(limit.seedToUserUnits());
      return;
    }
    if (m_elapsed >= limit.timeoutSeconds()) {
      timeout(limit.timeoutSeconds());
      return;
    }
    drive(limit.signedVolts());
  }

  // ===============================================================================================
  // Outcomes
  // ===============================================================================================

  private void complete(double seedUserUnits) {
    m_commandedVolts = 0.0;
    neutral();
    if (Double.isFinite(seedUserUnits)) {
      // Non-blocking by contract: MotorIO.seedPosition must use the zero-timeout form, because this
      // can run immediately after a move.
      m_io.seedPosition(m_units.toOutputRotations(seedUserUnits));
    }
    m_homed = true;
    m_succeeded = true;
    m_reason = AbortReason.NONE;
    m_phase = Phase.COMPLETE;
    m_timedOut.set(false);
    m_failed.set(false);
  }

  private void timeout(double limitSeconds) {
    neutral();
    recordFailure(
        AbortReason.TIMEOUT,
        String.format(
            Locale.ROOT,
            "the routine ran for %.2f s without finding its reference and gave up at the %.2f s "
                + "timeout. The mechanism was commanded neutral and isHomed() stays false, so "
                + "atGoal() stays false and any interlock built on it will refuse to run. Check "
                + "the drive direction, the trip threshold, and that the mechanism is free to move.",
            m_elapsed,
            limitSeconds));
  }

  private void abortCurrent(AbortReason reason, String detail) {
    neutral();
    recordFailure(reason, detail);
  }

  private void noteFailure(AbortReason reason, String detail) {
    m_reason = reason;
    m_lastFailureDetail = detail == null ? "" : detail;
    m_succeeded = false;
  }

  private void recordFailure(AbortReason reason, String detail) {
    noteFailure(reason, detail);
    m_index++;
    if (m_index < m_plan.size()) {
      // A composite falls through to its next strategy — that is the whole point of firstOf(...).
      startCurrent();
      return;
    }
    exhausted();
  }

  private void exhausted() {
    m_phase = Phase.FAILED;
    m_succeeded = false;
    if (m_reason == AbortReason.NONE) {
      m_reason = AbortReason.NO_STRATEGY;
    }
    if (m_reason == AbortReason.TIMEOUT) {
      m_timedOut.text(m_name + "/homing-timed-out: " + m_lastFailureDetail).set(true);
    } else {
      m_failed
          .text(
              m_name
                  + "/homing-failed ("
                  + m_reason
                  + "): "
                  + m_lastFailureDetail
                  + " isHomed() stays false, so atGoal() stays false too.")
          .set(true);
    }
  }

  private boolean refuse(AbortReason reason, String detail) {
    m_phase = Phase.IDLE;
    m_reason = reason;
    m_succeeded = false;
    m_lastFailureDetail = detail == null ? "" : detail;
    m_refused
        .text(m_name + "/homing-refused (" + reason + "): " + m_lastFailureDetail)
        .set(true);
    return false;
  }

  // ===============================================================================================
  // Helpers
  // ===============================================================================================

  private void drive(double volts) {
    double clamped =
        Math.max(-HomingStrategy.kMaxHomingVolts, Math.min(HomingStrategy.kMaxHomingVolts, volts));
    m_commandedVolts = Double.isFinite(clamped) ? clamped : 0.0;
    m_io.setVoltage(m_commandedVolts);
  }

  private void neutral() {
    m_commandedVolts = 0.0;
    m_io.setNeutral();
  }

  private double followerDriftRot() {
    double leader = m_inputs.positionRot;
    if (Double.isNaN(leader)) {
      return 0.0;
    }
    double worst = 0.0;
    for (double follower : m_inputs.followerPositionRot) {
      if (!Double.isNaN(follower)) {
        worst = Math.max(worst, Math.abs(follower - leader));
      }
    }
    return worst;
  }

  private boolean needsMotionAnywhere() {
    for (HomingStrategy strategy : m_plan) {
      if (strategy.needsMotion()) {
        return true;
      }
    }
    return false;
  }

  private HomingStrategy.AbsoluteSeed firstAbsoluteSeed() {
    for (HomingStrategy strategy : m_plan) {
      if (strategy instanceof HomingStrategy.AbsoluteSeed seed) {
        return seed;
      }
    }
    return null;
  }

  private static double dt() {
    double dt = Clock.dt();
    return Double.isFinite(dt) && dt > 0.0 ? dt : 0.02;
  }

  private static List<HomingStrategy> flatten(HomingStrategy strategy) {
    List<HomingStrategy> out = new ArrayList<>();
    collect(strategy, out, 0);
    return List.copyOf(out);
  }

  private static void collect(HomingStrategy strategy, List<HomingStrategy> out, int depth) {
    if (strategy == null || depth > 8) {
      return;
    }
    if (strategy instanceof HomingStrategy.Composite composite) {
      for (HomingStrategy child : composite.strategies()) {
        collect(child, out, depth + 1);
      }
      return;
    }
    out.add(strategy);
  }
}

package org.pumpkinlib.mechanism;

import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.core.compat.MathX;

/**
 * Deadband, scale, and capture-and-hold-on-release for a driver stick pointed at one mechanism.
 *
 * <p><b>Why the library owns this.</b> Every surveyed repository hand-writes it, and every copy is
 * slightly different: {@code Wrist.java} carries a hardcoded {@code 0.1} deadband and {@code 0.15}
 * scale as bare literals with no name and no log key, and the "let go of the stick and the arm
 * stays put" behaviour is either missing or implemented differently in each subsystem. One
 * implementation replaces five, reads its two numbers from {@link ControlConfig} so they appear in
 * the config snapshot, and makes the release edge an explicit, testable event instead of an
 * accident of ordering.
 *
 * <h2>The release edge is the whole point</h2>
 *
 * <p>Shaping a stick is arithmetic. The part that goes wrong is the <i>transition</i>: when the
 * driver lets go, a position mechanism must latch wherever it currently is as its new goal, exactly
 * once, on that cycle. Latch it every cycle and the goal chases the measurement and the mechanism
 * sags under gravity; latch it never and the mechanism snaps back to whatever goal was set before
 * the driver took over — which is the failure that made {@code maintainStateCommand()} slam a turret
 * to zero. {@link #releasedThisCycle()} is that edge, and it is true for exactly one call to {@link
 * #update(double)}.
 *
 * <h2>Nothing here throws and nothing here allocates</h2>
 *
 * <p>It is called from {@code periodic()}. A bad deadband or scale is clamped into range at
 * construction rather than rejected, because the value already passed {@code ControlConfig}'s
 * validation and being wrong twice is not more informative than being wrong once.
 */
public final class ManualControl {

  /** The largest deadband that still leaves usable stick travel. Anything above is clamped here. */
  public static final double kMaxDeadband = 0.95;

  private final double m_deadband;
  private final double m_scale;

  private double m_output;
  private boolean m_active;
  private boolean m_releasedThisCycle;

  /**
   * Creates a shaper with an explicit feel.
   *
   * <p>Both arguments are clamped rather than rejected: {@code deadband} into {@code [0,
   * kMaxDeadband]} and {@code scale} into {@code [0, 1]}, with {@code NaN} treated as zero. This
   * constructor is reached from a mechanism constructor, which is reached from a {@code public
   * static final} config field, and a throw there is an {@code ExceptionInInitializerError} with the
   * real message buried under class-init frames.
   *
   * @param deadband the stick deadband, 0..1 — motion below this magnitude is exactly zero
   * @param scale the fraction of full output a fully deflected stick asks for, 0..1
   */
  public ManualControl(double deadband, double scale) {
    m_deadband = Double.isNaN(deadband) ? 0.0 : MathX.clamp(deadband, 0.0, kMaxDeadband);
    m_scale = Double.isNaN(scale) ? 0.0 : MathX.clamp(scale, 0.0, 1.0);
  }

  /**
   * Reads the feel out of a mechanism's control config, so the numbers in the log's config snapshot
   * and the numbers actually applied are the same two numbers.
   *
   * @param control the mechanism's control config; a null config yields {@code ControlConfig}'s
   *     documented defaults rather than a failure
   * @return a shaper configured from {@code control.manualDeadband()} and {@code
   *     control.manualScale()}
   */
  public static ManualControl of(ControlConfig control) {
    if (control == null) {
      return new ManualControl(
          ControlConfig.kDefaultManualDeadband, ControlConfig.kDefaultManualScale);
    }
    return new ManualControl(control.manualDeadband(), control.manualScale());
  }

  /**
   * The configured deadband.
   *
   * @return the deadband, 0..{@value #kMaxDeadband}
   */
  public double deadband() {
    return m_deadband;
  }

  /**
   * The configured scale.
   *
   * @return the scale, 0..1
   */
  public double scale() {
    return m_scale;
  }

  /**
   * The pure shaping function: deadband, rescale the remaining travel back to full range, then apply
   * the output scale.
   *
   * <p>Rescaling matters. Subtracting the deadband without rescaling makes a fully deflected stick
   * ask for {@code (1 - deadband)} of the output, so a 0.30 scale with a 0.10 deadband silently
   * becomes 0.27 and the number in the config is not the number on the motor.
   *
   * @param stickInput the raw axis value, nominally -1..1
   * @param deadband the deadband to apply
   * @param scale the output scale to apply
   * @return the shaped output fraction, -{@code scale}..{@code scale}, or zero inside the deadband
   *     and for a {@code NaN} input
   */
  public static double shape(double stickInput, double deadband, double scale) {
    if (Double.isNaN(stickInput)) {
      return 0.0;
    }
    return MathX.applyDeadband(MathX.clamp(stickInput, -1.0, 1.0), deadband) * scale;
  }

  /**
   * The pure shaping function with this instance's deadband and scale, leaving the release edge
   * untouched.
   *
   * <p>Use it to preview a value; use {@link #update(double)} for the cycle's real command, because
   * only {@code update} advances the edge detector.
   *
   * @param stickInput the raw axis value, nominally -1..1
   * @return the shaped output fraction
   */
  public double shape(double stickInput) {
    return shape(stickInput, m_deadband, m_scale);
  }

  /**
   * Advances one cycle: shapes the stick, records whether the driver is holding it, and raises the
   * release edge on the cycle the stick returns to neutral.
   *
   * <p>Call this exactly once per loop per stick. Calling it twice consumes the release edge in the
   * first call and the caller that needed it sees nothing.
   *
   * @param stickInput the raw axis value, nominally -1..1
   * @return the shaped output fraction to command this cycle
   */
  public double update(double stickInput) {
    double shaped = shape(stickInput);
    boolean nowActive = shaped != 0.0;
    m_releasedThisCycle = m_active && !nowActive;
    m_active = nowActive;
    m_output = shaped;
    return shaped;
  }

  /**
   * The shaped output from the most recent {@link #update(double)}.
   *
   * @return the last shaped output fraction; zero before the first update
   */
  public double output() {
    return m_output;
  }

  /**
   * Whether the driver is currently outside the deadband.
   *
   * <p>This is the condition a mechanism uses to decide it is in {@link MechanismMode#MANUAL} rather
   * than running its closed loop.
   *
   * @return true when the last update produced a non-zero output
   */
  public boolean isActive() {
    return m_active;
  }

  /**
   * The capture-and-hold edge: true for exactly the one {@link #update(double)} on which the stick
   * returned to neutral.
   *
   * <p>A position mechanism latches its current measured position as the new goal here. That is the
   * difference between "let go and it stays" and "let go and it snaps back to a goal set two minutes
   * ago".
   *
   * @return true on the falling edge of stick activity, false otherwise
   */
  public boolean releasedThisCycle() {
    return m_releasedThisCycle;
  }

  /**
   * Forgets the edge state, as if the driver had never touched the stick.
   *
   * <p>Called when something else takes the mechanism — a homing routine, a superstructure
   * transition, SAFE_MODE — so that the next real release produces an edge and a stale one does not.
   */
  public void reset() {
    m_output = 0.0;
    m_active = false;
    m_releasedThisCycle = false;
  }

  /**
   * A one-line account for the boot dump, so the two numbers that decide how the robot feels are
   * visible without reading the config source.
   *
   * @return e.g. {@code "manual: deadband 0.100, scale 0.300 of full output"}
   */
  public String describe() {
    return String.format("manual: deadband %.3f, scale %.3f of full output", m_deadband, m_scale);
  }

  @Override
  public String toString() {
    return describe();
  }
}

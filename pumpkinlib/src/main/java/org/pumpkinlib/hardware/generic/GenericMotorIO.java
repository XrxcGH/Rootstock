package org.pumpkinlib.hardware.generic;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.wpilibj.DutyCycleEncoder;
import edu.wpi.first.wpilibj.Encoder;
import edu.wpi.first.wpilibj.motorcontrol.MotorController;
import edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.control.ControlLocation;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.NeutralMode;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.hardware.MotorCapabilities;
import org.pumpkinlib.hardware.MotorIO;
import org.pumpkinlib.hardware.MotorInputs;
import org.pumpkinlib.hardware.RioControlLoop;
import org.pumpkinlib.hardware.VelocityCarrier;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The backend for a PWM speed controller and a separate encoder: everything runs on the robot
 * controller.
 *
 * <p>It wraps any WPILib {@link MotorController} plus any position source, forces {@link
 * ControlLocation#RIO_FULL}, and runs its profile, feedback and feedforward through {@link
 * RioControlLoop} — <b>the same object</b> the mechanism layer uses for {@code RIO_FULL} on any
 * other backend. It is deliberately not a second implementation; two implementations of one control
 * law is how the same declared constraints produce two different motions on one robot.
 *
 * <p>Every on-board capability is reported {@code false}, which is what makes the downgrade message
 * fire exactly once, at boot, naming the loss:
 *
 * <pre>
 * [PumpkinLib][ERROR] Wrist: ControlLocation.ON_MOTOR_PROFILED requested, but this is a PWM motor
 *   controller with no on-board closed loop. Downgraded to RIO_FULL. Gains you tuned for the motor
 *   controller will NOT transfer; retune with the roboRIO loop.
 * </pre>
 *
 * <h2>The goal velocity is still delivered</h2>
 *
 * <p>{@link VelocityCarrier#RIO_VOLTAGE_TRIM}: there is no device request to put it in, so the loop
 * carries it as the profile's own velocity term and it reaches the motor as volts. The seam's
 * promise is delivery, not a mechanism, and a PWM backend that dropped it would make a field-locked
 * turret silently not work — the exact defect the parameter exists to prevent.
 *
 * <p><i>2027 note:</i> {@code MotorController.set()} becomes {@code setThrottle()}, and {@link
 * DutyCycleEncoder}'s survival past the 2027 {@code Counter} removal is unverified. Both are
 * confined to this one class.
 */
public final class GenericMotorIO implements MotorIO {

  /**
   * The position source, in OUTPUT-SHAFT units — the seam's units, not the encoder's.
   *
   * <p>A PWM controller has no encoder of its own, so this is the one thing a team must supply and
   * the one place a conversion can go wrong. Doing it here, once, in a named object, is better than
   * an inline {@code / 2048.0} in a subsystem.
   */
  public interface Feedback {

    /**
     * The measured position.
     *
     * @return output-shaft rotations, or NaN when there is no position source
     */
    double outputRotations();

    /**
     * The measured velocity.
     *
     * @return output-shaft rotations per second, or NaN when there is no velocity source
     */
    double outputRotationsPerSecond();

    /**
     * Whether the source is answering.
     *
     * @return true when the reading can be trusted
     */
    default boolean connected() {
      return true;
    }

    /**
     * Declare that the mechanism is currently at {@code outputRotations}.
     *
     * <p>The default does nothing, which is correct for a source that is already absolute.
     *
     * @param outputRotations the true position, output-shaft rotations
     */
    default void seed(double outputRotations) {}

    /**
     * One line naming this source, for the boot dump.
     *
     * @return a human-readable description
     */
    default String describe() {
      return "custom feedback source";
    }
  }

  private final String m_name;
  private final MotorSpec.GenericSpec m_spec;
  private final MechanismKind m_kind;
  private final MotorController m_controller;
  private final Feedback m_feedback;
  private final RioControlLoop m_loop;
  private final MotorCapabilities m_capabilities;

  private double m_lastVolts;
  private NeutralMode m_neutralMode;
  private boolean m_closedLoopActive;

  /**
   * The full constructor: your motor controller, your encoder.
   *
   * @param spec the declared PWM motor spec
   * @param controller the WPILib motor controller to drive
   * @param feedback the position source, already in output-shaft rotations
   * @param units the mechanism's unit conversion object
   * @param control the declared gains, constraints, gravity model and tolerance
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public GenericMotorIO(
      MotorSpec.GenericSpec spec,
      MotorController controller,
      Feedback feedback,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    m_spec = Objects.requireNonNull(spec, "GenericMotorIO: spec must not be null");
    m_controller = Objects.requireNonNull(controller, "GenericMotorIO: controller must not be null");
    m_feedback = feedback == null ? noFeedback() : feedback;
    Objects.requireNonNull(units, "GenericMotorIO: units must not be null");
    Objects.requireNonNull(control, "GenericMotorIO: control must not be null");
    m_kind = kind == null ? MechanismKind.SIMPLE : kind;
    m_name = spec.name();
    m_neutralMode = control.neutralMode();
    m_capabilities =
        MotorCapabilities.builder()
            .positionGoalVelocity(VelocityCarrier.RIO_VOLTAGE_TRIM)
            .build();
    m_loop = new RioControlLoop(units, control, Clock.dt());
    m_controller.setInverted(spec.inverted());

    if (control.location() != ControlLocation.RIO_FULL) {
      Alerts.error(
              m_name,
              m_name
                  + ": ControlLocation."
                  + control.location()
                  + " requested, but "
                  + spec.describe()
                  + " has no on-board closed loop. Downgraded to RIO_FULL. Gains you tuned for a "
                  + "motor controller will NOT transfer; retune with the roboRIO loop.",
              MatchImpact.PIT_ONLY)
          .set(true);
    }
    if (m_kind.isClosedLoop() && !m_feedback.connected()) {
      Alerts.error(
              m_name,
              m_name
                  + ": this is a "
                  + m_kind.describe()
                  + " mechanism on a PWM motor controller with NO position source, so closed-loop "
                  + "control cannot work. Fix: construct GenericMotorIO with a Feedback -- "
                  + "GenericMotorIO.ofQuadrature(encoder, outputRotationsPerPulse) or "
                  + "GenericMotorIO.of(positionSupplier, velocitySupplier).",
              MatchImpact.BLOCKS_MATCH)
          .set(true);
    }
  }

  /**
   * The four-argument form the backend factory calls: a {@link PWMSparkMax} on the declared channel
   * and no encoder.
   *
   * <p>A {@code GenericSpec} carries a PWM channel and nothing else, so this is genuinely all the
   * information available — the encoder cannot be named by a channel number. A closed-loop mechanism
   * built this way raises a blocking alert from the constructor above rather than silently holding
   * zero volts; use the six-argument constructor to supply a real one.
   *
   * @param spec the declared PWM motor spec
   * @param units the mechanism's unit conversion object
   * @param control the declared control config
   * @param kind whether this mechanism goes to a place, holds a speed, or is open loop
   */
  public GenericMotorIO(
      MotorSpec.GenericSpec spec,
      MechanismUnits units,
      ControlConfig control,
      MechanismKind kind) {
    this(spec, new PWMSparkMax(spec.pwmChannel()), noFeedback(), units, control, kind);
  }

  // ---- feedback sources ------------------------------------------------------------------------

  /**
   * No position source at all: every reading is NaN and {@code connected} is false.
   *
   * <p>NaN rather than zero, on purpose. A zero would make an open-loop roller look like a
   * perfectly-held elevator at the bottom of its travel.
   *
   * @return the empty feedback source
   */
  public static Feedback noFeedback() {
    return new Feedback() {
      @Override
      public double outputRotations() {
        return Double.NaN;
      }

      @Override
      public double outputRotationsPerSecond() {
        return Double.NaN;
      }

      @Override
      public boolean connected() {
        return false;
      }

      @Override
      public String describe() {
        return "no position source";
      }
    };
  }

  /**
   * Feedback from two suppliers you already have — a simulated plant, a CAN sensor this library does
   * not model, a hand-rolled decoder.
   *
   * @param positionRot supplies the position in OUTPUT-SHAFT ROTATIONS
   * @param velocityRps supplies the velocity in OUTPUT-SHAFT ROTATIONS PER SECOND
   * @return the feedback source
   */
  public static Feedback of(DoubleSupplier positionRot, DoubleSupplier velocityRps) {
    DoubleSupplier p = positionRot == null ? () -> Double.NaN : positionRot;
    DoubleSupplier v = velocityRps == null ? () -> Double.NaN : velocityRps;
    return new Feedback() {
      @Override
      public double outputRotations() {
        return p.getAsDouble();
      }

      @Override
      public double outputRotationsPerSecond() {
        return v.getAsDouble();
      }

      @Override
      public String describe() {
        return "supplier-backed feedback";
      }
    };
  }

  /**
   * Feedback from a quadrature encoder on the roboRIO's DIO ports.
   *
   * <p>The conversion is stated once, here, as output rotations per encoder pulse — rather than
   * being spread over a {@code setDistancePerPulse} call somewhere else and a division at every read
   * site. Relative, so it needs a homing strategy or a boot seed.
   *
   * @param encoder the WPILib encoder
   * @param outputRotationsPerPulse how far the output shaft turns per encoder pulse
   * @return the feedback source
   */
  public static Feedback ofQuadrature(Encoder encoder, double outputRotationsPerPulse) {
    Objects.requireNonNull(encoder, "GenericMotorIO.ofQuadrature: encoder must not be null");
    return new QuadratureFeedback(encoder, outputRotationsPerPulse);
  }

  /**
   * Feedback from an absolute duty-cycle encoder (a REV Through Bore read on a DIO channel).
   *
   * <p>Absolute, so {@link Feedback#seed(double)} does nothing: there is nothing to seed. Velocity
   * is differentiated from position against {@link Clock}, because a duty-cycle encoder reports no
   * rate of its own.
   *
   * @param encoder the WPILib duty-cycle encoder
   * @param outputRotationsPerTurn how far the output shaft turns per full encoder revolution — 1.0
   *     when the encoder is on the joint
   * @param zeroOffsetRot the raw reading, in encoder turns, at the mechanism's zero position
   * @return the feedback source
   */
  public static Feedback ofDutyCycle(
      DutyCycleEncoder encoder, double outputRotationsPerTurn, double zeroOffsetRot) {
    Objects.requireNonNull(encoder, "GenericMotorIO.ofDutyCycle: encoder must not be null");
    return new DutyCycleFeedback(encoder, outputRotationsPerTurn, zeroOffsetRot);
  }

  // ---- MotorIO ---------------------------------------------------------------------------------

  @Override
  public void updateInputs(MotorInputs inputs) {
    if (inputs == null) {
      return;
    }
    inputs.connected = m_feedback.connected();
    inputs.positionRot = m_feedback.outputRotations();
    inputs.velocityRps = m_feedback.outputRotationsPerSecond();
    inputs.appliedVolts = m_lastVolts;
    // A PWM controller measures nothing. NaN, not zero: a frozen zero current would make the
    // current-spike homing strategy silently never trigger.
    inputs.supplyCurrentAmps = Double.NaN;
    inputs.statorCurrentAmps = Double.NaN;
    inputs.torqueCurrentAmps = Double.NaN;
    inputs.temperatureCelsius = Double.NaN;
    inputs.forwardLimitTripped = false;
    inputs.forwardLimitValid = false;
    inputs.reverseLimitTripped = false;
    inputs.reverseLimitValid = false;
    inputs.closedLoopReferenceRot = m_closedLoopActive ? m_loop.referenceRot() : Double.NaN;
  }

  @Override
  public void setPositionGoal(
      double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts) {
    if (!m_closedLoopActive) {
      m_loop.reset(m_feedback.outputRotations(), m_feedback.outputRotationsPerSecond());
      m_closedLoopActive = true;
    }
    double volts =
        m_loop.positionVolts(
            m_feedback.outputRotations(),
            m_feedback.outputRotationsPerSecond(),
            outputRotations,
            outputRotationsPerSecond,
            arbFeedforwardVolts,
            Clock.dt());
    drive(volts);
  }

  @Override
  public void setPositionGoal(
      double outputRotations,
      double outputRotationsPerSecond,
      double arbFeedforwardVolts,
      MotionConstraints override) {
    // No device request to override, so the override is simply installed on the roboRIO profile.
    // applyConstraints() no-ops when the value is unchanged, so a per-loop call costs nothing.
    m_loop.applyConstraints(override);
    setPositionGoal(outputRotations, outputRotationsPerSecond, arbFeedforwardVolts);
  }

  @Override
  public void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts) {
    m_closedLoopActive = true;
    drive(
        m_loop.velocityVolts(
            m_feedback.outputRotationsPerSecond(),
            outputRps,
            outputRps2,
            arbFeedforwardVolts,
            Clock.dt()));
  }

  @Override
  public void setVoltage(double volts) {
    m_closedLoopActive = false;
    drive(volts);
  }

  @Override
  public void setDutyCycle(double fraction) {
    m_closedLoopActive = false;
    double clamped = MathUtil.clamp(Double.isFinite(fraction) ? fraction : 0.0, -1.0, 1.0);
    m_controller.set(clamped);
    // A PWM controller reports nothing back, so the applied voltage is an estimate at nominal bus
    // voltage and is labelled as such in the boot dump rather than pretended to be a measurement.
    m_lastVolts = clamped * kNominalBusVolts;
  }

  @Override
  public void setNeutral() {
    m_closedLoopActive = false;
    m_lastVolts = 0.0;
    m_controller.stopMotor();
  }

  @Override
  public void applyGains(Gains siGains) {
    m_loop.applyGains(siGains);
  }

  @Override
  public void applyConstraints(MotionConstraints constraints) {
    m_loop.applyConstraints(constraints);
  }

  @Override
  public void setNeutralMode(NeutralMode mode) {
    if (mode == null || mode == m_neutralMode) {
      return;
    }
    m_neutralMode = mode;
    if (mode == NeutralMode.BRAKE) {
      Alerts.warning(
              m_name,
              m_name
                  + ": NeutralMode.BRAKE was requested, but a PWM speed controller's idle mode is "
                  + "set by a jumper or by the controller's own configuration tool and cannot be "
                  + "changed from robot code. The mechanism will COAST when neutral. Fix: set brake "
                  + "mode on the controller itself, or hold position with a closed-loop goal.",
              MatchImpact.PIT_ONLY)
          .set(true);
    }
  }

  @Override
  public void seedPosition(double outputRotations) {
    m_feedback.seed(outputRotations);
    m_loop.reset(outputRotations, m_feedback.outputRotationsPerSecond());
  }

  @Override
  public void reapplyFullConfigBlocking() {
    // A PWM controller holds exactly one piece of state that robot code owns. Re-asserting it is
    // the whole of "re-apply the configuration" here, and it costs nothing, so it is not guarded.
    m_controller.setInverted(m_spec.inverted());
    m_closedLoopActive = false;
  }

  @Override
  public MotorCapabilities capabilities() {
    return m_capabilities;
  }

  @Override
  public String name() {
    return m_name;
  }

  @Override
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s (%s, %s)%n  control: RIO_FULL -- %s%n  feedback: %s%n  %s%n  capabilities: %s",
        m_name,
        m_spec.describe(),
        m_kind.describe(),
        ControlLocation.RIO_FULL.explanation(),
        m_feedback.describe(),
        m_loop.describe(),
        m_capabilities.describe());
  }

  /**
   * The motor controller this IO drives — the escape hatch for anything a PWM controller can do that
   * this seam does not model.
   *
   * @return the live controller object
   */
  public MotorController controller() {
    return m_controller;
  }

  /**
   * The roboRIO-side loop, exposed so the tuning system can read back the live gains and the profile
   * reference without a second copy of either.
   *
   * @return the live loop
   */
  public RioControlLoop loop() {
    return m_loop;
  }

  /** Nominal bus voltage, used only to turn a commanded duty cycle into a reported voltage. */
  private static final double kNominalBusVolts = 12.0;

  private void drive(double volts) {
    double safe = Double.isFinite(volts) ? MathUtil.clamp(volts, -12.0, 12.0) : 0.0;
    m_lastVolts = safe;
    m_controller.setVoltage(safe);
  }

  /** A quadrature encoder read in output-shaft rotations, with a software seed offset. */
  private static final class QuadratureFeedback implements Feedback {
    private final Encoder m_encoder;
    private final double m_rotationsPerPulse;
    private double m_offsetRot;

    QuadratureFeedback(Encoder encoder, double rotationsPerPulse) {
      m_encoder = encoder;
      m_rotationsPerPulse =
          Double.isFinite(rotationsPerPulse) && rotationsPerPulse != 0.0 ? rotationsPerPulse : 1.0;
    }

    @Override
    public double outputRotations() {
      return m_encoder.get() * m_rotationsPerPulse + m_offsetRot;
    }

    @Override
    public double outputRotationsPerSecond() {
      // getRate() is pulses per second when the distance-per-pulse is left at its default of 1.
      return m_encoder.getRate() * m_rotationsPerPulse;
    }

    @Override
    public void seed(double outputRotations) {
      m_offsetRot = outputRotations - m_encoder.get() * m_rotationsPerPulse;
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "quadrature encoder, %.8f output rotations per pulse (RELATIVE -- needs homing or a seed)",
          m_rotationsPerPulse);
    }
  }

  /** An absolute duty-cycle encoder, differentiated against {@link Clock} for velocity. */
  private static final class DutyCycleFeedback implements Feedback {
    private final DutyCycleEncoder m_encoder;
    private final double m_rotationsPerTurn;
    private final double m_zeroOffsetTurns;
    private double m_cachedRot = Double.NaN;
    private double m_lastRot = Double.NaN;
    private double m_lastSeconds = Double.NaN;
    private double m_velocityRps = Double.NaN;
    private long m_lastCycle = -1L;

    DutyCycleFeedback(DutyCycleEncoder encoder, double rotationsPerTurn, double zeroOffsetTurns) {
      m_encoder = encoder;
      m_rotationsPerTurn =
          Double.isFinite(rotationsPerTurn) && rotationsPerTurn != 0.0 ? rotationsPerTurn : 1.0;
      m_zeroOffsetTurns = Double.isFinite(zeroOffsetTurns) ? zeroOffsetTurns : 0.0;
    }

    @Override
    public double outputRotations() {
      // Sampled ONCE per robot loop, keyed on Clock.cycle(). Re-differentiating on every call
      // within one loop would divide a zero position change by a near-zero time and hand the
      // control loop an enormous fictional velocity.
      long cycle = Clock.cycle();
      if (cycle != m_lastCycle) {
        m_lastCycle = cycle;
        double rot = (m_encoder.get() - m_zeroOffsetTurns) * m_rotationsPerTurn;
        double now = Clock.seconds();
        if (Double.isFinite(m_lastRot) && now > m_lastSeconds) {
          m_velocityRps = (rot - m_lastRot) / (now - m_lastSeconds);
        }
        m_lastRot = rot;
        m_lastSeconds = now;
        m_cachedRot = rot;
      }
      return m_cachedRot;
    }

    @Override
    public double outputRotationsPerSecond() {
      outputRotations();
      return m_velocityRps;
    }

    @Override
    public boolean connected() {
      return m_encoder.isConnected();
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "duty-cycle absolute encoder, %.6f output rotations per turn, zero at %.6f turns "
              + "(ABSOLUTE -- no homing needed; velocity is differentiated, not measured)",
          m_rotationsPerTurn,
          m_zeroOffsetTurns);
    }
  }
}

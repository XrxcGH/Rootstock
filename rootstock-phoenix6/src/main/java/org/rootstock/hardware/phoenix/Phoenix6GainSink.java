package org.rootstock.hardware.phoenix;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.MotionMagicConfigs;
import com.ctre.phoenix6.configs.Slot0Configs;
import com.ctre.phoenix6.signals.GravityTypeValue;
import com.ctre.phoenix6.signals.StaticFeedforwardSignValue;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.rootstock.control.GainSink;
import org.rootstock.control.Gains;
import org.rootstock.control.GravityMode;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.alert.RootstockAlert;

/**
 * The <b>one and only</b> place in Rootstock that touches a Phoenix {@code Slot0Configs}.
 *
 * <p>Canonical {@link Gains} are seven doubles in <b>volts per SI unit</b>. Phoenix slot gains are
 * volts per <b>output rotation</b>. Somebody has to multiply, exactly once, by exactly one number —
 * and every season a team writes {@code config.Slot0.kV = DRIVE_kV * 2.0 * Math.PI;} next to one
 * motor, so the next mechanism repeats it, or doesn't. This class is that multiplication, hoisted
 * next to the concept instead of next to a device.
 *
 * <h2>The arithmetic, derived rather than copied</h2>
 *
 * <p>{@code FeedbackConfigs.SensorToMechanismRatio} is configured to rotor-rotations-per-output-
 * rotation before any gain is written, so every position, velocity and profile number the device
 * sees is already in <b>output rotations</b>. That leaves exactly one factor between the two unit
 * systems:
 *
 * <pre>
 *   U = MechanismUnits.siPerOutputRotation()          [SI per output rotation]
 *     = 2*PI rad/rot                                   for a rotary axis
 *     = metres of carriage travel per drum rotation    for a linear one
 *
 *   kP  [V / SI]         x U [SI / rot]     = Slot0.kP  [V / rot]
 *   kI  [V / (SI*s)]     x U [SI / rot]     = Slot0.kI  [V / (rot*s)]
 *   kD  [V / (SI/s)]     x U [SI / rot]     = Slot0.kD  [V / (rot/s)]
 *   kV  [V / (SI/s)]     x U [SI / rot]     = Slot0.kV  [V / (rot/s)]
 *   kA  [V / (SI/s^2)]   x U [SI / rot]     = Slot0.kA  [V / (rot/s^2)]
 *   kS  [V]                                 = Slot0.kS   -- volts on both sides, NO conversion
 *   kG  [V]                                 = Slot0.kG   -- volts on both sides, NO conversion
 * </pre>
 *
 * <p>Worked, on the 9143-A elevator: a 22-tooth #25 sprocket advances {@code 22 x 0.250 in =
 * 5.500 in} of chain per drum rotation, doubled by a two-stage cascade, so {@code U = 0.279400 m}
 * per output rotation. {@code kP = 80.0 V/m} becomes {@code 80.0 x 0.279400 = 22.352 V/rot};
 * {@code kD = 2.0 V/(m/s)} becomes {@code 0.5588}; {@code kS = 0.22 V} stays {@code 0.22}.
 *
 * <h2>The two settings that are not gains but travel with them</h2>
 *
 * <ul>
 *   <li><b>{@code StaticFeedforwardSign = UseClosedLoopSign}.</b> {@code kS} must oppose the
 *       <em>closed-loop</em> direction, not the measured-velocity direction. A mechanism holding
 *       station at zero velocity has a velocity sign that flips with sensor noise, and a {@code kS}
 *       that flips with it chatters audibly and heats the motor.
 *   <li><b>{@code GravityType} follows {@link GravityMode}, which is derived from the axis</b> — the
 *       team never types it. {@link GravityMode#COSINE} additionally needs
 *       {@code GravityArmPositionOffset}, and Phoenix documents that offset as applied <em>to the
 *       position, before</em> {@code kG} is evaluated: the device computes
 *       {@code kG * cos(position + offset)} while we want {@code kG * cos(position -
 *       horizontalReference)}. <b>The offset is therefore the negated horizontal reference.</b>
 *       Writing it un-negated pushes {@code kG} the wrong way by twice the reference angle.
 * </ul>
 *
 * <h2>Motion Magic Expo rides on the same factor</h2>
 *
 * <p>An earlier revision selected {@code MotionMagicExpoVoltage} and never configured
 * {@code MotionMagicExpo_kV} / {@code _kA}, so a team enabling Expo silently inherited CTRE's
 * factory defaults (0.12 V/rps and 0.1 V/rps&sup2;) and got a profile shaped by CTRE's arbitrary
 * numbers instead of their measured plant. Expo's two terms are always in <b>volts</b>, so they take
 * the identical {@code x U} conversion — see {@link #expoKv(Gains, double)} and
 * {@link #expoKa(Gains, double)}, which also clamp into the ranges the device will accept.
 *
 * <h2>Behaviour required of a sink, and honoured here</h2>
 *
 * <ul>
 *   <li><b>Idempotent.</b> {@link #apply(Gains)} with unchanged gains performs no bus traffic at
 *       all. This runs while a slider is being dragged.
 *   <li><b>No allocation on the no-change path.</b> Same reason.
 *   <li><b>Non-blocking.</b> Writes go through {@link PhoenixUtil#applyFast}, never the verified
 *       path.
 *   <li><b>Returns false rather than throwing</b> when the device rejects a configuration, and
 *       raises a pit-only warning. A tuning session that takes the robot down is worse than a tuning
 *       session that says "the device would not take that".
 * </ul>
 */
public final class Phoenix6GainSink implements GainSink {

  /** Phoenix's documented lower bound for {@code MotionMagicExpo_kV}, in V per rot/s. */
  public static final double kExpoKvMin = 0.001;

  /** Phoenix's documented upper bound for {@code MotionMagicExpo_kV}, in V per rot/s. */
  public static final double kExpoKvMax = 100.0;

  /** Phoenix's documented lower bound for {@code MotionMagicExpo_kA}, in V per rot/s^2. */
  public static final double kExpoKaMin = 1.0e-05;

  /** Phoenix's documented upper bound for {@code MotionMagicExpo_kA}, in V per rot/s^2. */
  public static final double kExpoKaMax = 100.0;

  /**
   * The widest {@code GravityArmPositionOffset} Phoenix accepts, in rotations.
   *
   * <p>Phoenix clamps a larger offset with <b>no error</b>, so {@code kG} would be applied at the
   * wrong angle over the whole range — an arm that sags on one side and slams on the other.
   */
  public static final double kMaxGravityOffsetRot = 0.25;

  private final String m_owner;
  private final Slot0Configs m_slot;
  private final MotionMagicConfigs m_motionMagic;
  private final GravityMode m_gravity;
  private final double m_siPerOutputRotation;
  private final double m_horizontalReferenceRot;
  private final boolean m_useExpo;
  private final String m_siLabel;
  private final Function<Slot0Configs, StatusCode> m_slotWriter;
  private final Function<MotionMagicConfigs, StatusCode> m_motionMagicWriter;

  private Gains m_applied;
  private RootstockAlert m_rejectedAlert;

  /**
   * Builds the sink for one mechanism's leader motor.
   *
   * @param owner the mechanism name, for alerts and for {@link #describeConversion()}
   * @param slot the live {@code Slot0Configs} inside the device's whole-device configuration — the
   *     same object, so a later verified full re-apply carries the tuned gains with it rather than
   *     reverting to whatever was compiled in
   * @param motionMagic the live {@code MotionMagicConfigs} from the same configuration, so that
   *     Expo's {@code kV}/{@code kA} track the gains they are derived from
   * @param gravity which gravity model the axis implies; never typed by the team
   * @param siPerOutputRotation {@code MechanismUnits.siPerOutputRotation()} — the one factor
   * @param horizontalReferenceRot the cosine reference in <b>output rotations</b>, already converted
   *     by {@code MechanismUnits}; this class negates it on the way into the device
   * @param useExpo whether the profile is Motion Magic Expo, and therefore whether Expo's two terms
   *     must be re-derived whenever {@code kV} or {@code kA} changes
   * @param siLabel {@code "m"} or {@code "rad"}, for the printed derivation
   * @param slotWriter the non-blocking slot write, normally {@code slot -> PhoenixUtil.applyFast(fx,
   *     slot)}
   * @param motionMagicWriter the non-blocking Motion Magic write
   */
  public Phoenix6GainSink(
      String owner,
      Slot0Configs slot,
      MotionMagicConfigs motionMagic,
      GravityMode gravity,
      double siPerOutputRotation,
      double horizontalReferenceRot,
      boolean useExpo,
      String siLabel,
      Function<Slot0Configs, StatusCode> slotWriter,
      Function<MotionMagicConfigs, StatusCode> motionMagicWriter) {
    m_owner = owner == null || owner.isBlank() ? "motor" : owner;
    m_slot = Objects.requireNonNull(slot, "Phoenix6GainSink: Slot0Configs must not be null");
    m_motionMagic =
        Objects.requireNonNull(motionMagic, "Phoenix6GainSink: MotionMagicConfigs must not be null");
    m_gravity = gravity == null ? GravityMode.NONE : gravity;
    m_siPerOutputRotation = siPerOutputRotation;
    m_horizontalReferenceRot = horizontalReferenceRot;
    m_useExpo = useExpo;
    m_siLabel = siLabel == null || siLabel.isBlank() ? "SI" : siLabel;
    m_slotWriter = Objects.requireNonNull(slotWriter, "Phoenix6GainSink: slot writer required");
    m_motionMagicWriter =
        Objects.requireNonNull(motionMagicWriter, "Phoenix6GainSink: Motion Magic writer required");
  }

  // ================================================================== the conversion, exactly once

  /**
   * Converts volts-per-SI gains into a Phoenix slot, including the two settings that are not gains.
   *
   * <p>Static because the device configuration built at construction and the live re-tune performed
   * at 10 Hz must go through the <b>same</b> arithmetic. Two copies of this method is how a robot
   * ends up tuned to one conversion and configured with another.
   *
   * @param slot the slot to write into
   * @param si the canonical gains, volts per SI unit
   * @param gravity the gravity model derived from the axis
   * @param siPerOutputRotation {@code MechanismUnits.siPerOutputRotation()}
   * @param horizontalReferenceRot the cosine reference in output rotations, un-negated
   */
  public static void writeInto(
      Slot0Configs slot,
      Gains si,
      GravityMode gravity,
      double siPerOutputRotation,
      double horizontalReferenceRot) {
    if (slot == null || si == null) {
      return;
    }
    double u = Double.isFinite(siPerOutputRotation) ? siPerOutputRotation : 1.0;

    // Position-like terms scale by U. kS and kG are volts on both sides and do not.
    slot.kP = finite(si.kP() * u);
    slot.kI = finite(si.kI() * u);
    slot.kD = finite(si.kD() * u);
    slot.kV = finite(si.kV() * u);
    slot.kA = finite(si.kA() * u);
    slot.kS = finite(si.kS());
    slot.kG = finite(si.kG());

    // kS opposes the CLOSED-LOOP direction, not the measured-velocity direction. A mechanism holding
    // station at zero velocity has a velocity sign that flips with sensor noise, and a kS that flips
    // with it chatters.
    slot.StaticFeedforwardSign = StaticFeedforwardSignValue.UseClosedLoopSign;

    GravityMode mode = gravity == null ? GravityMode.NONE : gravity;
    switch (mode) {
      case COSINE -> {
        slot.GravityType = GravityTypeValue.Arm_Cosine;
        // Phoenix applies the offset to the POSITION, before evaluating kG:
        //     device computes  kG * cos(position + GravityArmPositionOffset)
        //     we want          kG * cos(position - horizontalReference)
        // so the offset is the NEGATED reference. Writing it un-negated pushes kG the wrong way by
        // twice the reference angle.
        slot.GravityArmPositionOffset =
            clamp(-finite(horizontalReferenceRot), -kMaxGravityOffsetRot, kMaxGravityOffsetRot);
      }
      case CONSTANT, NONE -> {
        // Elevator_Static applies kG unconditionally. Under NONE, kG is zero anyway, so this is the
        // same behaviour with one fewer branch on the device.
        slot.GravityType = GravityTypeValue.Elevator_Static;
        slot.GravityArmPositionOffset = 0.0;
      }
    }
  }

  /**
   * The device-side {@code kV}, in volts per output rotation per second.
   *
   * <p>This is exactly {@code Slot0.kV}. It is exposed because the Motion Magic requests have no
   * velocity field, so a nonzero goal velocity has to ride as <b>volts</b> on the request's
   * arbitrary-feedforward field: {@code ffVolts = arbFf + kVDevice * goalRps}. Taking the number
   * from here rather than recomputing it means there is no second place for a unit error.
   *
   * @param si the canonical gains
   * @param siPerOutputRotation {@code MechanismUnits.siPerOutputRotation()}
   * @return volts per (output rotation per second)
   */
  public static double deviceKv(Gains si, double siPerOutputRotation) {
    if (si == null) {
      return 0.0;
    }
    return finite(si.kV() * (Double.isFinite(siPerOutputRotation) ? siPerOutputRotation : 1.0));
  }

  /**
   * {@code MotionMagicExpo_kV}, derived from the mechanism's own {@code kV} and clamped into the
   * range Phoenix accepts.
   *
   * <p>Under Expo, {@code MotionMagicAcceleration} is unused and {@code MotionMagicCruiseVelocity}
   * is only a ceiling — the profile's whole shape comes from these two numbers. Leaving them at
   * CTRE's factory defaults, which is what happens if nobody sets them, produces a profile with no
   * relationship to the mechanism.
   *
   * @param si the canonical gains
   * @param siPerOutputRotation {@code MechanismUnits.siPerOutputRotation()}
   * @return volts per output rot/s, clamped to [{@value #kExpoKvMin}, {@value #kExpoKvMax}]
   */
  public static double expoKv(Gains si, double siPerOutputRotation) {
    return clamp(deviceKv(si, siPerOutputRotation), kExpoKvMin, kExpoKvMax);
  }

  /**
   * {@code MotionMagicExpo_kA}, derived from the mechanism's own {@code kA} and clamped.
   *
   * @param si the canonical gains
   * @param siPerOutputRotation {@code MechanismUnits.siPerOutputRotation()}
   * @return volts per output rot/s^2, clamped to [{@value #kExpoKaMin}, {@value #kExpoKaMax}]
   */
  public static double expoKa(Gains si, double siPerOutputRotation) {
    if (si == null) {
      return kExpoKaMin;
    }
    double u = Double.isFinite(siPerOutputRotation) ? siPerOutputRotation : 1.0;
    return clamp(finite(si.kA() * u), kExpoKaMin, kExpoKaMax);
  }

  // =========================================================================== GainSink

  /**
   * Converts and pushes the gains, non-blocking, skipping the write entirely when nothing changed.
   *
   * @param gains the gains to apply, volts per SI unit
   * @return true if the device accepted them or nothing changed; false if the device rejected them,
   *     in which case a pit-only alert has already been raised
   */
  @Override
  public boolean apply(Gains gains) {
    if (gains == null || gains.equals(m_applied)) {
      // The no-change path allocates nothing and touches no bus. This runs at 10 Hz while a value is
      // being dragged in the tuning UI.
      return true;
    }

    writeInto(m_slot, gains, m_gravity, m_siPerOutputRotation, m_horizontalReferenceRot);
    StatusCode slotStatus = m_slotWriter.apply(m_slot);

    StatusCode expoStatus = StatusCode.OK;
    if (m_useExpo) {
      // Expo's profile is shaped ENTIRELY by these two, so they must follow kV and kA rather than
      // being frozen at whatever the constructor computed from the compiled-in gains.
      m_motionMagic.MotionMagicExpo_kV = expoKv(gains, m_siPerOutputRotation);
      m_motionMagic.MotionMagicExpo_kA = expoKa(gains, m_siPerOutputRotation);
      expoStatus = m_motionMagicWriter.apply(m_motionMagic);
    }

    if (!slotStatus.isOK() || !expoStatus.isOK()) {
      rejectedAlert()
          .text(
              m_owner
                  + ": the device would not take these gains ("
                  + (slotStatus.isOK() ? expoStatus : slotStatus).getName()
                  + "). The previously accepted gains are still running. Tuning is degraded, the "
                  + "mechanism is not — but do not trust the sliders until this clears.")
          .set(true);
      return false;
    }

    m_applied = gains;
    return true;
  }

  /**
   * SI units of mechanism travel per output rotation — the one factor every gain above is
   * multiplied by.
   *
   * @return metres per output rotation for a linear axis, {@code 2*PI} for a rotary one
   */
  @Override
  public double siUnitsPerMechanismRotation() {
    return m_siPerOutputRotation;
  }

  /**
   * The conversion, printed with its arithmetic so a CSA at an event — or a student at 11pm — can
   * check every number by hand.
   *
   * @return a multi-line block, no trailing newline
   */
  @Override
  public String describeConversion() {
    Gains g = m_applied == null ? Gains.UNTUNED : m_applied;
    StringBuilder sb = new StringBuilder(640);
    sb.append(
        String.format(
            Locale.ROOT,
            "  1 output rotation   = %.6f %s  (this is the ONLY factor below)%n",
            m_siPerOutputRotation,
            m_siLabel));
    line(sb, "kP", g.kP(), m_slot.kP, "V/" + m_siLabel, "V/rot", true);
    line(sb, "kI", g.kI(), m_slot.kI, "V/(" + m_siLabel + "*s)", "V/(rot*s)", true);
    line(sb, "kD", g.kD(), m_slot.kD, "V/(" + m_siLabel + "/s)", "V/(rot/s)", true);
    line(sb, "kV", g.kV(), m_slot.kV, "V/(" + m_siLabel + "/s)", "V/(rot/s)", true);
    line(sb, "kA", g.kA(), m_slot.kA, "V/(" + m_siLabel + "/s^2)", "V/(rot/s^2)", true);
    line(sb, "kS", g.kS(), m_slot.kS, "V", "V", false);
    line(sb, "kG", g.kG(), m_slot.kG, "V", "V", false);
    sb.append(
        String.format(
            Locale.ROOT,
            "  GravityType         %s%s%n",
            m_slot.GravityType,
            m_gravity == GravityMode.COSINE
                ? String.format(
                    Locale.ROOT,
                    "  (GravityArmPositionOffset %.5f rot = NEGATED horizontal reference)",
                    m_slot.GravityArmPositionOffset)
                : ""));
    sb.append("  StaticFeedforward   UseClosedLoopSign (kS opposes the closed-loop direction, not")
        .append(System.lineSeparator())
        .append("                      the measured velocity, so holding station does not chatter)")
        .append(System.lineSeparator());
    if (m_useExpo) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  MotionMagicExpo     kV %.5f V/(rot/s), kA %.5f V/(rot/s^2) -- DERIVED from the%n"
                  + "                      gains above, not CTRE's factory 0.12 / 0.1. Under Expo,%n"
                  + "                      MotionMagicAcceleration is unused and the cruise velocity%n"
                  + "                      is only a ceiling.%n",
              m_motionMagic.MotionMagicExpo_kV,
              m_motionMagic.MotionMagicExpo_kA));
    }
    return sb.toString().stripTrailing();
  }

  // ================================================================================== inspection

  /**
   * The live slot this sink writes, so a self test can read back what was actually converted.
   *
   * @return the slot config object, shared with the device's whole-device configuration
   */
  public Slot0Configs slot() {
    return m_slot;
  }

  /**
   * The gains most recently accepted by the device.
   *
   * @return the applied gains, or {@link Gains#UNTUNED} if nothing has been applied yet
   */
  public Gains appliedGains() {
    return m_applied == null ? Gains.UNTUNED : m_applied;
  }

  /**
   * Records the gains the constructor already wrote through the whole-device configuration, so the
   * first live {@link #apply(Gains)} with the same values is correctly a no-op.
   *
   * @param gains the gains the full configuration carried
   */
  void seedApplied(Gains gains) {
    m_applied = gains;
  }

  // ===================================================================================== private

  private RootstockAlert rejectedAlert() {
    if (m_rejectedAlert == null) {
      m_rejectedAlert =
          Alerts.warning(
              m_owner, m_owner + ": gain write rejected by the device.", MatchImpact.PIT_ONLY);
    }
    return m_rejectedAlert;
  }

  private void line(
      StringBuilder sb,
      String name,
      double si,
      double phoenix,
      String siUnit,
      String phoenixUnit,
      boolean scaled) {
    sb.append(
        String.format(
            Locale.ROOT,
            "  %-4s %10.5f %-14s -> Slot0.%-3s %10.5f %-12s %s%n",
            name,
            si,
            siUnit,
            name,
            phoenix,
            phoenixUnit,
            scaled
                ? String.format(Locale.ROOT, "(x %.6f)", m_siPerOutputRotation)
                : "(volts both sides, unconverted)"));
  }

  private static double finite(double v) {
    return Double.isFinite(v) ? v : 0.0;
  }

  private static double clamp(double v, double lo, double hi) {
    if (!Double.isFinite(v)) {
      return lo;
    }
    return Math.max(lo, Math.min(hi, v));
  }
}

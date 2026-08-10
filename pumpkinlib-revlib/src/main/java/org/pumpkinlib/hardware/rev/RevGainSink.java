package org.pumpkinlib.hardware.rev;

import com.revrobotics.spark.ClosedLoopSlot;
import com.revrobotics.spark.SparkBase;
import com.revrobotics.spark.config.SparkBaseConfig;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.control.GainSink;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.GravityMode;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;

/**
 * The <b>only</b> code in this artifact that touches a SPARK's closed-loop gain configuration.
 *
 * <p>Canonical {@link Gains} are volts per SI unit — volts per metre for an elevator, volts per
 * radian for an arm. A tuned kP for one mechanism is roughly {@code 0.01} in REV units, roughly
 * {@code 100} in Phoenix units and roughly {@code 7} on the roboRIO. That four-order-of-magnitude
 * spread between three controllers driving the same steel is the entire reason a canonical gain
 * type exists, and this class is where the REV end of it is absorbed — once, in one file, with the
 * arithmetic printed at boot so a student can check it by hand.
 *
 * <h2>The conversion, derived rather than asserted</h2>
 *
 * <p>Let {@code U = }{@link #siUnitsPerMechanismRotation()} — metres of travel per output-shaft
 * rotation for a linear axis, {@code 2*PI} radians per rotation for a rotary one. {@link
 * SparkMotorIO} configures {@code encoder.positionConversionFactor(1/G)} and {@code
 * encoder.velocityConversionFactor(1/(60*G))}, so the SPARK's closed loop sees <b>output
 * rotations</b> and <b>output rotations per second</b>. Then:
 *
 * <pre>
 *   error is measured in OUTPUT ROTATIONS
 *   canonical kP is VOLTS per SI unit
 *   kP * U                      = volts per output rotation
 *   kP * U / 12                 = duty cycle per output rotation      -&gt; REV closedLoop.p
 * </pre>
 *
 * <p>The division by twelve is REV's, not ours: the SPARK's PID output is a <b>duty cycle</b>
 * bounded by {@code outputRange}, which defaults to {@code [-1, 1]}. Phoenix's voltage requests
 * take volts directly, which is precisely the 12× slice of that 10,000× spread. {@code kI} and
 * {@code kD} follow kP exactly; the time base is the device's, which is why {@code kD} is an
 * approximation and says so in {@link #describeConversion()}.
 *
 * <p>The feedforward terms do <b>not</b> get the same treatment, and this is where a 2025-shaped
 * mental model produces wrong numbers. REVLib 2026's {@code FeedForwardConfig} is documented — and
 * verified against the 2026.0.5 sources — in <b>volts</b>: <i>"@param kS The kS gain in Volts"</i>,
 * <i>"@param kV The kV gain in Volts per velocity"</i>, <i>"@param kA The kA gain in Volts per
 * velocity per second"</i>, <i>"@param kG The kG gain in Volts"</i>, <i>"@param kCos The kCos gain
 * in Volts"</i>. So:
 *
 * <pre>
 *   kS  [V]            -&gt; kS                     unchanged, volts are volts
 *   kV  [V/(SI/s)]     -&gt; kV * U                 volts per (output rot/s)
 *   kA  [V/(SI/s^2)]   -&gt; kA * U                 volts per (output rot/s^2)
 *   kG  [V]            -&gt; kG   or kCos           unchanged, volts are volts
 * </pre>
 *
 * <p>There is <b>no {@code / 12}</b> and <b>no {@code / 60}</b> on the feedforward path. The
 * {@code / 60} would be right if the velocity the SPARK measured were RPM; the {@code
 * velocityConversionFactor} this backend sets has already left RPM behind, and applying the factor
 * twice is the same class of error as the 60× MAXMotion bug documented on {@link
 * SparkMotorIO#buildConfig}. <b>This is a deliberate, documented departure from the conversion
 * table in {@link GainSink}'s javadoc</b>, which was written against REVLib's 2025 {@code
 * velocityFF} (a unitless-per-RPM term) rather than 2026's volts-denominated {@code
 * FeedForwardConfig}.
 *
 * <h2>Gravity</h2>
 *
 * <p>REVLib 2026 does gravity feedforward on the controller: {@code kG} applied statically for an
 * elevator, {@code kCos} multiplied by the cosine of the mechanism's absolute position for an arm —
 * the same split Phoenix spells {@code Elevator_Static} / {@code Arm_Cosine}. That is what lets one
 * {@link GravityMode} enum reach both vendors with no roboRIO-side term.
 *
 * <p><b>Exactly one of the two is ever written.</b> Verified in the 2026.0.5 sources: {@code
 * kCos(v, slot)} <em>deletes</em> any {@code kG} already present in the config and reports a
 * driver-station warning, and {@code kG(v, slot)} silently refuses when a {@code kCos} is present.
 * So the natural-looking defensive form — set the one you want, zero the other — sets an elevator's
 * gravity gain and then removes it again, shipping a carriage with no gravity feedforward. See
 * {@link #writeInto}.
 *
 * <p>{@code kCosRatio} is set to {@code 1.0}: REVLib documents it as "applied after the conversion
 * factor and should convert from those units to absolute rotations of your mechanism", and this
 * backend's conversion factor already produces mechanism rotations. REVLib also requires that the
 * encoder read zero at horizontal, and exposes <b>no offset field</b> — where Phoenix takes a
 * negated arm-position offset. So a mechanism whose horizontal reference is not zero gets a
 * {@link MatchImpact#BLOCKS_MATCH} alert naming the offset. The gain is still applied, because an
 * arm with mistimed gravity compensation is recoverable and an arm with none falls; what is not
 * acceptable is being wrong silently.
 */
public final class RevGainSink implements GainSink {

  /** The bus voltage the duty-cycle conversion assumes. */
  public static final double kNominalBusVolts = 12.0;

  /** The slot every PumpkinLib mechanism uses. Multi-slot gain scheduling is not a CORE feature. */
  public static final ClosedLoopSlot kSlot = ClosedLoopSlot.kSlot0;

  private final SparkBase m_device;
  private final MotorSpec.SparkSpec m_spec;
  private final double m_siPerOutputRotation;
  private final GravityMode m_gravity;
  private final double m_horizontalReferenceSi;
  private final String m_owner;

  private Gains m_applied = null;

  /**
   * Build the sink for one SPARK.
   *
   * @param device the live controller; may be null in a test that only wants the arithmetic
   * @param spec the declared SPARK, so the partial config allocated on a change is of the matching
   *     concrete type ({@code SparkMaxConfig} versus {@code SparkFlexConfig})
   * @param siPerOutputRotation SI units of mechanism travel per output-shaft rotation — pass
   *     {@code MechanismUnits.siPerOutputRotation()}, never a locally recomputed value
   * @param gravity which gravity model the mechanism declared
   * @param horizontalReferenceSi the mechanism position, in SI, at which an arm is horizontal
   * @param owner the mechanism name, for alerts and for the boot dump
   */
  public RevGainSink(
      SparkBase device,
      MotorSpec.SparkSpec spec,
      double siPerOutputRotation,
      GravityMode gravity,
      double horizontalReferenceSi,
      String owner) {
    m_device = device;
    m_spec = Objects.requireNonNull(spec, "RevGainSink: spec must not be null");
    m_siPerOutputRotation = siPerOutputRotation;
    m_gravity = gravity == null ? GravityMode.NONE : gravity;
    m_horizontalReferenceSi = horizontalReferenceSi;
    m_owner = owner == null ? spec.name() : owner;
  }

  // ---- GainSink --------------------------------------------------------------------------------

  /**
   * Convert and push gains to the device on the <b>non-blocking</b> path.
   *
   * <p>Idempotent and allocation-free when nothing changed, because this is called while a student
   * is dragging a slider. On a change it allocates exactly one partial config, writes only the
   * closed-loop group into it, and hands it to {@link RevUtil#applyFast} — never the blocking
   * verified path, and never with {@code kPersistParameters}, which would write the SPARK's flash
   * thousands of times per tuning session.
   *
   * @param gains the gains to apply, in volts per SI unit
   * @return true if the device accepted them or nothing changed; false if the device refused or the
   *     gains are untuned, in which case an alert has already been raised
   */
  @Override
  public boolean apply(Gains gains) {
    if (gains == null || gains.equals(m_applied)) {
      return true;
    }
    if (gains.isUntuned()) {
      Alerts.warning(
              m_owner,
              m_owner
                  + ": refusing to send untuned gains to SPARK "
                  + m_spec.deviceId()
                  + " (kP is NaN, the placeholder Gains.UNTUNED carries). The closed loop would"
                  + " command NaN volts. Fix: run the tuning wizard, or set gains(...) in the"
                  + " mechanism config.",
              MatchImpact.BLOCKS_MATCH)
          .set(true);
      return false;
    }
    SparkBaseConfig partial = RevUtil.newConfig(m_spec);
    writeInto(partial, gains);
    boolean ok = RevUtil.applyFast(m_device, partial, m_owner) == com.revrobotics.REVLibError.kOk;
    if (ok) {
      m_applied = gains;
    }
    return ok;
  }

  /**
   * The arithmetic, shown rather than asserted.
   *
   * <p>Printed at boot and in the tuning UI. Anyone can check these numbers with a calculator,
   * which is the point: a gain that arrives on a device by a route nobody can reproduce is a magic
   * number again.
   *
   * @return a multi-line description; never null
   */
  @Override
  public String describeConversion() {
    Gains g = m_applied;
    StringBuilder out = new StringBuilder(512);
    out.append(
        String.format(
            Locale.ROOT,
            "REV gain conversion for %s (%s)%n"
                + "  1 output rotation = %.6f SI units of travel%n"
                + "  feedback:    REV duty-cycle units, x %.6f SI/rot / %.1f V bus%n"
                + "  feedforward: REV VOLTS (2026 FeedForwardConfig), x %.6f SI/rot, no /60,"
                + " no /12%n",
            m_owner,
            m_spec.sparkModel().describe(),
            m_siPerOutputRotation,
            m_siPerOutputRotation,
            kNominalBusVolts,
            m_siPerOutputRotation));
    if (g == null) {
      out.append("  (no gains have been applied yet)");
      return out.toString();
    }
    line(out, "kP", g.kP(), "V/SI", "closedLoop.p", p(g.kP()), "x U / 12");
    line(out, "kI", g.kI(), "V/(SI*s)", "closedLoop.i", i(g.kI()), "x U / 12");
    line(out, "kD", g.kD(), "V/(SI/s)", "closedLoop.d", d(g.kD()), "x U / 12, REV time base");
    line(out, "kS", g.kS(), "V", "feedForward.kS", g.kS(), "unchanged");
    line(out, "kV", g.kV(), "V/(SI/s)", "feedForward.kV", kv(g.kV()), "x U");
    line(out, "kA", g.kA(), "V/(SI/s^2)", "feedForward.kA", ka(g.kA()), "x U");
    switch (m_gravity) {
      case NONE -> out.append("  kG   not applied (GravityMode.NONE)\n");
      case CONSTANT -> line(out, "kG", g.kG(), "V", "feedForward.kG", g.kG(), "unchanged");
      case COSINE ->
          line(
              out,
              "kG",
              g.kG(),
              "V",
              "feedForward.kCos",
              g.kG(),
              "unchanged, kCosRatio 1.0, zero must be horizontal");
      default -> out.append("  kG   not applied (unrecognised gravity mode)\n");
    }
    return out.toString();
  }

  /**
   * SI units of mechanism travel per output-shaft rotation.
   *
   * @return the value handed in at construction
   */
  @Override
  public double siUnitsPerMechanismRotation() {
    return m_siPerOutputRotation;
  }

  // ---- the conversion, exposed one term at a time so a test can pin each ------------------------

  /**
   * Canonical kP to REV's proportional gain.
   *
   * @param kP volts per SI unit of error
   * @return duty cycle per output rotation of error
   */
  public double p(double kP) {
    return kP * m_siPerOutputRotation / kNominalBusVolts;
  }

  /**
   * Canonical kI to REV's integral gain.
   *
   * @param kI volts per (SI unit * second)
   * @return duty cycle per (output rotation * second)
   */
  public double i(double kI) {
    return kI * m_siPerOutputRotation / kNominalBusVolts;
  }

  /**
   * Canonical kD to REV's derivative gain.
   *
   * @param kD volts per (SI unit / second)
   * @return duty cycle per (output rotation / second), on REV's own derivative time base
   */
  public double d(double kD) {
    return kD * m_siPerOutputRotation / kNominalBusVolts;
  }

  /**
   * Canonical kV to REV's {@code FeedForwardConfig.kV}, which is denominated in volts.
   *
   * @param kV volts per (SI unit / second)
   * @return volts per (output rotation / second) — no {@code /60}, no {@code /12}
   */
  public double kv(double kV) {
    return kV * m_siPerOutputRotation;
  }

  /**
   * Canonical kA to REV's {@code FeedForwardConfig.kA}, which is denominated in volts.
   *
   * @param kA volts per (SI unit / second^2)
   * @return volts per (output rotation / second^2)
   */
  public double ka(double kA) {
    return kA * m_siPerOutputRotation;
  }

  /**
   * The volts-per-output-rotation-per-second the {@code setSetpoint} arbitrary feedforward uses to
   * carry the goal-velocity term a REV position request has no field for.
   *
   * <p>Identical formula and identical units to the Phoenix Motion Magic path, which is what makes
   * the field-locked-turret parity test a real test rather than a tautology.
   *
   * @param gains the current gains
   * @return volts per output rotation per second; zero when the gains are untuned
   */
  public double deviceKvVoltsPerOutputRps(Gains gains) {
    if (gains == null || gains.isUntuned() || !Double.isFinite(gains.kV())) {
      return 0.0;
    }
    return kv(gains.kV());
  }

  // ---- construction path -----------------------------------------------------------------------

  /**
   * Write the converted gains into a config being built, without talking to the device.
   *
   * <p>Used by {@link SparkMotorIO}'s constructor, which pushes one whole declarative config rather
   * than a gains-only partial. Keeping the conversion here — instead of inlining it into
   * {@code buildConfig} — is what makes the "only one place converts gains" claim true rather than
   * aspirational.
   *
   * @param config the config being built; mutated in place
   * @param gains the gains to convert, in volts per SI unit; untuned gains write nothing
   */
  public void writeInto(SparkBaseConfig config, Gains gains) {
    if (config == null || gains == null || gains.isUntuned()) {
      return;
    }
    config.closedLoop.pid(p(gains.kP()), i(gains.kI()), d(gains.kD()), kSlot).outputRange(-1, 1);
    config.closedLoop.feedForward.kS(gains.kS(), kSlot).kV(kv(gains.kV()), kSlot)
        .kA(ka(gains.kA()), kSlot);

    // EXACTLY ONE of kG and kCos may be touched, and writing both is a defect rather than a
    // belt-and-braces zeroing. Verified in the REVLib 2026.0.5 sources: kCos(v, slot) *deletes* any
    // kG parameter already in the config and reports a driver-station warning, and kG(v, slot)
    // silently refuses to set anything when a kCos parameter is present. So the "obvious" defensive
    // form -- .kG(value).kCos(0.0) for an elevator -- sets the gravity gain and then removes it
    // again on the very next call, and ships an elevator with no gravity feedforward at all. The
    // zeroing is unnecessary anyway: the construction path applies with kResetSafeParameters, which
    // has already returned both terms to their factory zero.
    switch (m_gravity) {
      case NONE -> {
        // Neither term is written. See above.
      }
      case CONSTANT -> config.closedLoop.feedForward.kG(gains.kG(), kSlot);
      case COSINE -> {
        config.closedLoop.feedForward.kCos(gains.kG(), kSlot).kCosRatio(1.0, kSlot);
        warnIfHorizontalReferenceIsNotZero();
      }
      default -> {
        // An unrecognised gravity mode writes nothing rather than guessing.
      }
    }
    m_applied = gains;
  }

  /**
   * The gains this sink last successfully sent.
   *
   * @return the applied gains, or null if none have been
   */
  public Gains applied() {
    return m_applied;
  }

  // ---- internals -------------------------------------------------------------------------------

  private void warnIfHorizontalReferenceIsNotZero() {
    if (Math.abs(m_horizontalReferenceSi) <= 1e-9) {
      return;
    }
    Alerts.error(
            m_owner,
            String.format(
                Locale.ROOT,
                "%s: cosine gravity on a SPARK requires the encoder to read zero at horizontal, and"
                    + " this mechanism declares horizontal at %.4f SI units. REVLib's kCos has no"
                    + " offset field (Phoenix's Arm_Cosine does), so kG will be applied at an angle"
                    + " that is wrong by that offset across the whole range — strongest error at"
                    + " the ends of travel. Fix: re-zero the absolute encoder so horizontal reads"
                    + " zero, or move this mechanism to a Phoenix controller, or set"
                    + " GravityMode.NONE and accept the sag.",
                m_owner,
                m_horizontalReferenceSi),
            MatchImpact.BLOCKS_MATCH)
        .set(true);
  }

  private static void line(
      StringBuilder out,
      String name,
      double canonical,
      String canonicalUnit,
      String vendorField,
      double vendor,
      String how) {
    out.append(
        String.format(
            Locale.ROOT,
            "  %-4s %12.6f %-12s -> %-18s %12.6f  (%s)%n",
            name,
            canonical,
            canonicalUnit,
            vendorField,
            vendor,
            how));
  }
}

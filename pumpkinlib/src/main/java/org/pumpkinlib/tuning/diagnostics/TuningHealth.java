package org.pumpkinlib.tuning.diagnostics;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.TuningTarget;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.health.FaultCollector;
import org.pumpkinlib.core.health.HealthSource;
import org.pumpkinlib.tuning.sysid.SampleBuffer;

/**
 * The passive half of the tuning domain: <i>is this mechanism still tuned?</i>
 *
 * <p><b>Why this is a separate class from the mechanical pre-flight.</b> Belts stretch, batteries
 * age, a bearing picks up grit, and a mechanism that was beautifully tuned in week one is often not
 * tuned by championships — but nobody re-runs the wizard, because nothing tells them to. This
 * watches a recorded move and says so. It commands nothing: it takes a window somebody else already
 * recorded, classifies it, and reports a fault when the classification has fallen away from where
 * the mechanism was left. That is precisely why it can be a {@link HealthSource} and the
 * voltage-commanding mechanical check cannot — the health domain owns no code that moves a robot.
 *
 * <p>Register it the same way as everything else: hand it to {@code PumpkinRegistry.addAll(...)}
 * along with the mechanisms, and it appears on the pit tab beside the rest.
 */
public final class TuningHealth implements HealthSource {

  /** The alert group every message from this class lands in. */
  public static final String kAlertGroup = "Tuning";

  /** The 10-90% rise time of a first-order step is {@code ln(9) * tau}. */
  public static final double kFirstOrderRiseFactor = 2.1972245773362196;

  private final TuningTarget m_target;
  private ResponseClass m_baseline = ResponseClass.GOOD;
  private Optional<ResponseVerdict> m_lastVerdict = Optional.empty();
  private double m_expectedRiseSeconds = Double.NaN;
  private double m_expectedSettleSeconds = Double.NaN;

  private TuningHealth(TuningTarget target) {
    m_target = Objects.requireNonNull(target, "target");
  }

  /**
   * Watch one mechanism.
   *
   * @param target the mechanism whose responses will be classified
   * @return the health source
   * @throws NullPointerException if the target is null
   */
  public static TuningHealth watching(TuningTarget target) {
    return new TuningHealth(target);
  }

  /**
   * The name this source appears under on the pit tab and in the log.
   *
   * @return {@code "Tuning/<mechanism>"}
   */
  @Override
  public String healthName() {
    return "Tuning/" + m_target.tuningName();
  }

  /**
   * Report whether the last classified move was worse than the mechanism was left at.
   *
   * <p>Never having been checked is reported at info rather than as a warning. It is genuinely worth
   * writing down — a mechanism nobody has verified since week one is exactly the one that will
   * surprise you — but it is not a problem, and a pit tab where everything is yellow is a pit tab
   * nobody reads.
   *
   * @param out where to report; valid only for the duration of this call
   */
  @Override
  public void pollHealth(FaultCollector out) {
    if (m_lastVerdict.isEmpty()) {
      out.info(
          healthName(),
          m_target.tuningName()
              + " has not had a step response checked since this robot booted, so nothing here "
              + "knows whether it is still tuned. Fix: run a small verification move in the pit.");
      return;
    }
    ResponseVerdict verdict = m_lastVerdict.get();
    if (verdict.classification().ordinal() < m_baseline.ordinal()) {
      out.warn(
          healthName(),
          String.format(
              Locale.ROOT,
              "%s was tuned to %s and its last move classified %s (%s). %s Fix: %s",
              m_target.tuningName(),
              m_baseline,
              verdict.classification(),
              verdict.describe(),
              verdict.diagnosis(),
              verdict.recommendation()));
    }
  }

  /**
   * Classify a recorded window and remember the result.
   *
   * <p>Raises a pit-only warning when the classification has degraded from the baseline, because a
   * fault on the pit tab is seen at the end of the day and an alert is seen now.
   *
   * @param window the samples of a verification move, oldest first
   * @return the verdict, also stored as {@link #lastVerdict()}
   */
  public ResponseVerdict check(List<SampleBuffer.Sample> window) {
    ResponseVerdict verdict =
        StepResponseAnalyzer.analyze(
            window,
            m_target.toleranceSi(),
            expectedRiseSeconds(),
            expectedSettleSeconds(),
            m_target.plantPrior().nominalVolts(),
            m_target.archetype().hasGravity());
    record(verdict);
    return verdict;
  }

  /**
   * Classify everything currently held in a buffer.
   *
   * @param buffer the recorder the verification move was written into
   * @return the verdict, also stored as {@link #lastVerdict()}
   */
  public ResponseVerdict check(SampleBuffer buffer) {
    return check(buffer == null ? List.of() : buffer.all());
  }

  /**
   * Store a verdict somebody else computed, and alert if it is a step down from the baseline.
   *
   * @param verdict the classification of the most recent move
   */
  public void record(ResponseVerdict verdict) {
    if (verdict == null) {
      return;
    }
    m_lastVerdict = Optional.of(verdict);
    if (verdict.classification().ordinal() < m_baseline.ordinal()) {
      Alerts.warning(
          kAlertGroup,
          m_target.tuningName()
              + " is not behaving the way it did when it was tuned: it was left at "
              + m_baseline
              + " and now classifies "
              + verdict.classification()
              + ". "
              + verdict.diagnosis()
              + " Fix: "
              + verdict.recommendation(),
          MatchImpact.PIT_ONLY);
    }
  }

  /**
   * The classification this mechanism was left at when it was last tuned.
   *
   * @return the baseline, {@link ResponseClass#GOOD} unless something set it otherwise
   */
  public ResponseClass baseline() {
    return m_baseline;
  }

  /**
   * Record where the wizard left this mechanism, so a later check has something to compare against.
   *
   * @param baseline the classification the tuning session finished at
   * @return this source, for chaining
   */
  public TuningHealth withBaseline(ResponseClass baseline) {
    m_baseline = baseline == null ? ResponseClass.GOOD : baseline;
    return this;
  }

  /**
   * The most recent classification.
   *
   * @return the verdict, or empty if nothing has been checked since boot
   */
  public Optional<ResponseVerdict> lastVerdict() {
    return m_lastVerdict;
  }

  /**
   * Override the rise time a good response is compared against.
   *
   * @param seconds the expected 10-90% rise time; NaN restores the derived value
   * @return this source, for chaining
   */
  public TuningHealth withExpectedRiseSeconds(double seconds) {
    m_expectedRiseSeconds = seconds;
    return this;
  }

  /**
   * Override the settling time a good response is compared against.
   *
   * @param seconds the expected settling time; NaN restores the derived value
   * @return this source, for chaining
   */
  public TuningHealth withExpectedSettleSeconds(double seconds) {
    m_expectedSettleSeconds = seconds;
    return this;
  }

  /**
   * What a good rise time would be for this plant.
   *
   * <p>For a velocity loop the plant is first order with time constant {@code tau = kA / kV}, and
   * the 10-90% rise time of a first-order step is {@code ln(9) * tau}. For a position mechanism the
   * reference is the motion profile's own duration, which this class cannot see — so it returns NaN
   * and the analyser simply does not apply the sluggishness comparison, rather than inventing a
   * number and calling a healthy mechanism slow.
   *
   * @return the expected rise time in seconds, or NaN when it cannot be derived
   */
  public double expectedRiseSeconds() {
    if (Double.isFinite(m_expectedRiseSeconds)) {
      return m_expectedRiseSeconds;
    }
    if (!m_target.archetype().isVelocity()) {
      return Double.NaN;
    }
    Gains gains = m_target.gains();
    if (gains == null || !(gains.kV() > 0) || !Double.isFinite(gains.kA())) {
      return Double.NaN;
    }
    return kFirstOrderRiseFactor * (gains.kA() / gains.kV());
  }

  /**
   * What a good settling time would be for this plant.
   *
   * @return the expected settling time in seconds, or NaN when it cannot be derived
   */
  public double expectedSettleSeconds() {
    if (Double.isFinite(m_expectedSettleSeconds)) {
      return m_expectedSettleSeconds;
    }
    double rise = expectedRiseSeconds();
    return Double.isFinite(rise) ? rise + 0.25 : Double.NaN;
  }

  /**
   * One line for the boot dump and the pit tab.
   *
   * @return e.g. {@code "Tuning/Elevator: baseline GOOD, last GOOD"}
   */
  public String describe() {
    return healthName()
        + ": baseline "
        + m_baseline
        + ", last "
        + m_lastVerdict.map(v -> v.classification().name()).orElse("never checked");
  }
}

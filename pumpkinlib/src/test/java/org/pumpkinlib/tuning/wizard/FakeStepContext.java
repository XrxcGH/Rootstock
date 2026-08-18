package org.pumpkinlib.tuning.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.pumpkinlib.control.FakeTarget;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.SafetyEnvelope;
import org.pumpkinlib.control.TuningSupervisor;
import org.pumpkinlib.control.TuningTarget;
import org.pumpkinlib.tuning.sysid.SampleBuffer;

/**
 * A {@link StepContext} a test drives by hand: a real target, a real (unarmed) supervisor, and every
 * output captured in a list.
 *
 * <p><strong>Why the supervisor is real and unarmed rather than mocked.</strong> {@code
 * TuningSupervisor.check()} returns empty immediately when it is not armed and {@code commandVolts}
 * returns zero — so an unarmed real supervisor is exactly the "no motion is possible" world a
 * no-motion step should be tested in, and it is the real class rather than a stand-in that could
 * disagree with it. It also means this fixture needs no HAL: nothing here builds an alert or touches
 * NetworkTables.
 */
public final class FakeStepContext implements StepContext {

  /** Every line the step narrated, oldest first. */
  public final List<String> narration = new ArrayList<>();

  /** Every prediction prompt the step published, oldest first. */
  public final List<String> prompts = new ArrayList<>();

  /** The options published with the most recent prompt. */
  public List<String> options = List.of();

  /** Every progress value the step published. */
  public final List<Double> progress = new ArrayList<>();

  /** Every feedback string the step published after scoring. */
  public final List<String> feedback = new ArrayList<>();

  /** Gain sets the step proposed. */
  public final List<Gains> proposals = new ArrayList<>();

  private final FakeTarget m_target;
  private final TuningSupervisor m_supervisor;
  private final SampleBuffer m_buffer = SampleBuffer.standard();

  private Gains m_gains = new Gains(128.0, 0.0, 0.0, 0.20, 5.00, 0.060, 0.25);
  private double m_toleranceSi = 0.005;
  private double m_elapsed;
  private TuningRecipe.Mode m_mode = TuningRecipe.Mode.TEACHING;
  private OptionalInt m_answer = OptionalInt.empty();

  /**
   * Builds a context around a target.
   *
   * @param target the mechanism the step will read
   */
  public FakeStepContext(FakeTarget target) {
    m_target = target;
    m_supervisor = new TuningSupervisor(target, SafetyEnvelope.derive(target), () -> true);
  }

  /**
   * Sets the running gain set the step reads.
   *
   * @param gains the gains
   * @return this
   */
  public FakeStepContext withGains(Gains gains) {
    m_gains = gains;
    return this;
  }

  /**
   * Sets the effective tolerance.
   *
   * @param toleranceSi the tolerance in SI
   * @return this
   */
  public FakeStepContext withTolerance(double toleranceSi) {
    m_toleranceSi = toleranceSi;
    return this;
  }

  /**
   * Sets the recipe mode.
   *
   * @param mode teaching or express
   * @return this
   */
  public FakeStepContext withMode(TuningRecipe.Mode mode) {
    m_mode = mode;
    return this;
  }

  /**
   * Simulates the student pressing one of the three option buttons.
   *
   * @param index 0, 1 or 2
   */
  public void studentAnswers(int index) {
    m_answer = OptionalInt.of(index);
  }

  /** Simulates the student not answering at all. */
  public void studentSaysNothing() {
    m_answer = OptionalInt.empty();
  }

  /**
   * Advances the step clock.
   *
   * @param seconds how far
   */
  public void advance(double seconds) {
    m_elapsed += seconds;
  }

  @Override
  public TuningTarget target() {
    return m_target;
  }

  @Override
  public TuningSupervisor supervisor() {
    return m_supervisor;
  }

  @Override
  public Gains gains() {
    return m_gains;
  }

  @Override
  public void proposeGains(Gains gains) {
    proposals.add(gains);
    m_gains = gains;
  }

  @Override
  public double toleranceSi() {
    return m_toleranceSi;
  }

  @Override
  public double elapsedSeconds() {
    return m_elapsed;
  }

  @Override
  public double dt() {
    return 0.02;
  }

  @Override
  public SampleBuffer buffer() {
    return m_buffer;
  }

  @Override
  public void narrate(String line) {
    narration.add(line);
  }

  @Override
  public void publishProgress(double fraction0to1) {
    progress.add(fraction0to1);
  }

  @Override
  public TuningRecipe.Mode mode() {
    return m_mode;
  }

  @Override
  public void askPrediction(String prompt, List<String> choices) {
    prompts.add(prompt);
    options = List.copyOf(choices);
  }

  @Override
  public OptionalInt predictionAnswer() {
    return m_answer;
  }

  @Override
  public void publishPredictionFeedback(String text) {
    feedback.add(text);
  }
}

package org.pumpkinlib.tuning.wizard;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.pumpkinlib.control.AbortReason;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.SafetyEnvelope;
import org.pumpkinlib.control.TuningSupervisor;
import org.pumpkinlib.control.TuningTarget;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.hid.ControlMap;
import org.pumpkinlib.core.identity.RobotId;
import org.pumpkinlib.core.identity.RobotIdentity;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.telemetry.PumpkinLog;
import org.pumpkinlib.tuning.TuningRegistry;
import org.pumpkinlib.tuning.persist.TunedValueStore;
import org.pumpkinlib.tuning.persist.ValueExporter;
import org.pumpkinlib.tuning.persist.ValueSource;
import org.pumpkinlib.tuning.sysid.SampleBuffer;
import org.pumpkinlib.tuning.ui.TunerPublisher;
import org.pumpkinlib.tuning.wizard.steps.PredictStep;
import org.pumpkinlib.tuning.wizard.steps.PreflightStep;

/**
 * The guided tuning wizard: a state machine over a recipe, and the sole caller of {@link
 * TuningSupervisor#arm()}.
 *
 * <p><b>Motion is impossible outside Test mode, by construction and not by convention.</b> {@code
 * arm()} throws unless the robot is in diagnostics mode, and this class is the only thing that calls
 * it. That is what makes {@link #periodic()} safe to call from {@code robotPeriodic()}: during
 * teleop and autonomous the wizard advances its own state machine and publishes narration, but
 * cannot command a volt, no matter what any button on any controller is doing. Without that
 * property, a driver holding a trigger to shoot on a practice field would simultaneously be
 * satisfying the wizard's held-enable, which for a library aimed at unsupervised fourteen-year-olds
 * is the one place it must not fail.
 *
 * <p><b>This method is also the containment boundary for that hard throw.</b> {@code arm()}'s eleven
 * preconditions each produce a precise, actionable sentence, and swallowing them inside a command
 * would replace eleven good messages with one vague one. So the throw is caught here, published, and
 * the wizard stays in {@link WizardState#READY}. Nothing propagates out into {@code robotPeriodic}.
 *
 * <p><b>Give the wizard its own controller port.</b> The bindings collide, button for button, with a
 * typical driver map — right trigger means "shoot" to a driver and "authorize raw voltage to an arm"
 * to the wizard. Sharing is legal, logged verbatim, and deliberately awkward; see {@link
 * #acknowledgeSharedController(String)}.
 *
 * <pre>
 *   // The default, and every example in the documentation:
 *   private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));
 * </pre>
 *
 * <p><b>The bindings.</b> Right trigger held past half travel is enable, and releasing it neutrals
 * on the very next loop. A accepts, B retries, X goes back, Y skips, Start aborts everything and
 * reverts every gain to what it was when the session started. The D-pad answers a prediction.
 */
public final class TuningWizard {

  /** The alert group everything in the tuning domain publishes under. */
  public static final String kAlertGroup = "Tuning";

  /** How far the trigger must be pulled to count as held. */
  public static final double kTriggerThreshold = 0.5;

  private final WizardInputs m_inputs;
  private final Map<String, TuningRecipe> m_recipeOverrides = new LinkedHashMap<>();
  private final List<StepResult> m_results = new ArrayList<>();
  private final List<String> m_narration = new ArrayList<>();
  private final List<Gains> m_gainsAtStepEntry = new ArrayList<>();
  private final List<String> m_missedPredictions = new ArrayList<>();
  private final SampleBuffer m_buffer = SampleBuffer.standard();

  private Optional<String> m_sharedControllerAck = Optional.empty();
  private Optional<String> m_coastRiskAck = Optional.empty();
  private Optional<String> m_skipSimAck = Optional.empty();
  private Optional<TuningRecipe.Mode> m_forcedMode = Optional.empty();
  private Optional<String> m_blocked = Optional.empty();

  private WizardState m_state = WizardState.IDLE;
  private TuningTarget m_target;
  private TuningSupervisor m_supervisor;
  private TuningRecipe m_recipe;
  private int m_stepIndex;
  private boolean m_stepBegun;
  private StepResult m_result;
  private Optional<Boolean> m_pendingPrediction = Optional.empty();
  private int m_predictionsAsked;
  private int m_predictionsRight;
  private double m_stepStartSeconds = Double.NaN;
  private int m_predictionBaseline = -1;
  private String m_lastMessage = "";

  private Gains m_sessionGains = Gains.UNTUNED;
  private Gains m_stepGains = Gains.UNTUNED;
  private Gains m_gains = Gains.UNTUNED;
  private Optional<Gains> m_proposed = Optional.empty();
  private OptionalInt m_predictionOverride = OptionalInt.empty();

  private boolean m_prevAccept;
  private boolean m_prevRetry;
  private boolean m_prevBack;
  private boolean m_prevSkip;
  private boolean m_prevAbort;
  private boolean m_prevEnable;

  private TuningWizard(WizardInputs inputs) {
    m_inputs = inputs;
  }

  /**
   * Build the wizard over every registered tuning target, driven from a gamepad.
   *
   * <p>At construction this checks whether the controller's port is also a driver or operator port.
   * If it is, and {@link #acknowledgeSharedController(String)} has not been called, the wizard
   * raises an alert naming the conflict and refuses to leave {@link WizardState#IDLE}.
   *
   * @param controller the wizard's controller, ideally on a port nobody else uses
   * @return the wizard
   */
  public static TuningWizard using(CommandXboxController controller) {
    return new TuningWizard(new XboxInputs(controller)).checkControllerPort();
  }

  /**
   * Build the wizard from raw inputs, for a state-based robot with no command scheduler — or for a
   * test that needs to drive the whole state machine without a HAL.
   *
   * @param inputs the input source
   * @return the wizard
   */
  public static TuningWizard using(WizardInputs inputs) {
    return new TuningWizard(inputs);
  }

  /**
   * Permit the wizard to share a controller with the driver.
   *
   * <p>The argument is a free-text reason, logged verbatim into the tuning report and shown as a
   * persistent warning for the rest of the session. Deliberately awkward.
   *
   * <p>Sharing is survivable at all only because motion is impossible outside Test mode. It is still
   * a bad idea.
   *
   * @param reason why, in the student's own words
   * @return this wizard
   */
  public TuningWizard acknowledgeSharedController(String reason) {
    m_sharedControllerAck = Optional.ofNullable(reason);
    m_blocked = Optional.empty();
    Alerts.warning(
            kAlertGroup,
            "The tuning wizard is sharing a controller with the driver. Reason given: \""
                + reason
                + "\". Motion is still impossible outside Test mode, but every wizard button also "
                + "means something to your driver.",
            MatchImpact.PIT_ONLY)
        .set(true);
    TunerPublisher.setSharedControllerAck(String.valueOf(reason));
    return this;
  }

  /**
   * Permit a gravity mechanism whose idle mode is coast, or unreadable, to be armed anyway.
   *
   * <p>Same friction, same logging, same persistent warning. An elevator that coasts falls the
   * moment the student lets go of the trigger, which is why this is a sentence someone has to type.
   *
   * @param reason why, in the student's own words
   * @return this wizard
   */
  public TuningWizard acknowledgeCoastRisk(String reason) {
    m_coastRiskAck = Optional.ofNullable(reason);
    TunerPublisher.setCoastRiskAck(String.valueOf(reason));
    return this;
  }

  /**
   * Override the recipe for one mechanism. Rarely needed.
   *
   * @param mechanismName the mechanism's tuning name
   * @param recipe the recipe to use instead of the archetype default
   * @return this wizard
   */
  public TuningWizard recipe(String mechanismName, TuningRecipe recipe) {
    m_recipeOverrides.put(mechanismName, recipe);
    return this;
  }

  /**
   * Force one recipe mode for every mechanism, instead of the per-mechanism default.
   *
   * <p>The default is teaching in simulation — always, and not overridable per mechanism, because
   * simulation is free and unqueued and is where the learning is supposed to happen — and the
   * student's remembered choice on hardware.
   *
   * @param mode the mode
   * @return this wizard
   */
  public TuningWizard mode(TuningRecipe.Mode mode) {
    m_forcedMode = Optional.ofNullable(mode);
    return this;
  }

  /**
   * Bypass the simulation-first gate. The argument is logged verbatim into the report.
   *
   * @param acknowledgement why, in the student's own words
   * @return this wizard
   */
  public TuningWizard skipSimPromotion(String acknowledgement) {
    m_skipSimAck = Optional.ofNullable(acknowledgement);
    Alerts.warning(
            kAlertGroup,
            "The simulation-first gate has been bypassed for this session. Reason given: \""
                + acknowledgement
                + "\". Nobody has checked that the safety supervisor can contain this mechanism "
                + "when the plant is not what the config says it is.",
            MatchImpact.PIT_ONLY)
        .set(true);
    return this;
  }

  /**
   * Choose the mechanism to tune.
   *
   * @param mechanismName the tuning name of a registered target
   * @return this wizard
   */
  public TuningWizard select(String mechanismName) {
    Optional<TuningTarget> target = TuningRegistry.target(mechanismName);
    if (target.isEmpty()) {
      m_lastMessage =
          "There is no mechanism registered under the name \""
              + mechanismName
              + "\". Registered names are: "
              + TuningRegistry.targets().stream().map(TuningTarget::tuningName).toList()
              + ". Fix: call TuningRegistry.register(yourMechanism) before building the wizard.";
      return this;
    }
    beginSession(target.get());
    return this;
  }

  /**
   * Call every loop. Costs one branch when the wizard is idle.
   *
   * <p>See the class javadoc for why this is safe to call from {@code robotPeriodic()} and why
   * nothing thrown by {@code arm()} escapes it.
   */
  public void periodic() {
    if (PumpkinLog.isReplay()) {
      m_state = WizardState.DISABLED_REPLAY;
      TunerPublisher.setState(m_state.name());
      return;
    }
    if (MatchContext.isFMSAttached()) {
      return;
    }
    if (m_blocked.isPresent()) {
      m_state = WizardState.IDLE;
      TunerPublisher.setState(m_state.name());
      return;
    }

    boolean enable = m_inputs.enableHeld();
    boolean accept = rising(m_inputs.accept(), m_prevAccept);
    boolean retry = rising(m_inputs.retry(), m_prevRetry);
    boolean back = rising(m_inputs.back(), m_prevBack);
    boolean skip = rising(m_inputs.skip(), m_prevSkip);
    boolean abort = rising(m_inputs.abortAll(), m_prevAbort);
    m_prevAccept = m_inputs.accept();
    m_prevRetry = m_inputs.retry();
    m_prevBack = m_inputs.back();
    m_prevSkip = m_inputs.skip();
    m_prevAbort = m_inputs.abortAll();
    boolean enableRising = enable && !m_prevEnable;
    m_prevEnable = enable;

    m_predictionOverride = m_inputs.predictionSelection();
    TunerPublisher.setEnableHeld(enable);

    if (abort && m_state != WizardState.IDLE) {
      abortAll();
      return;
    }

    switch (m_state) {
      case IDLE:
        idle(accept);
        break;
      case READY:
        ready(enableRising || accept);
        break;
      case ARMED:
        armed(enable);
        break;
      case PREFLIGHT:
      case RUNNING:
        running(enable);
        break;
      case REVIEW:
        review(accept, retry, back, skip);
        break;
      case ABORTED:
        if (accept || retry) {
          m_state = WizardState.READY;
        }
        break;
      case DONE:
        done(accept, skip, retry);
        break;
      case DISABLED_REPLAY:
      default:
        break;
    }
    publish();
  }

  /**
   * Command-based convenience: run the wizard for the whole time this command is scheduled.
   *
   * @return the command
   */
  public Command command() {
    return Commands.run(this::periodic).ignoringDisable(true).withName("TuningWizard");
  }

  /**
   * Where the wizard is.
   *
   * @return the state
   */
  public WizardState state() {
    return m_state;
  }

  /**
   * The most recent message the wizard published — including the exact sentence an {@code arm()}
   * precondition produced, if one refused.
   *
   * @return the message
   */
  public String lastMessage() {
    return m_lastMessage;
  }

  /**
   * How many predictions were asked, and how many were right.
   *
   * @return the score line
   */
  public String predictionScore() {
    return Coach.scoreLine(m_predictionsRight, m_predictionsAsked);
  }

  /**
   * The gains accumulated so far this session. Nothing here is on the mechanism until a step is
   * accepted, and nothing is on disk until the student saves.
   *
   * @return the running gain set
   */
  public Gains gains() {
    return m_gains;
  }

  /**
   * A markdown report of everything done this session.
   *
   * <p>This is the artifact a student attaches to a pull request, and it is the closest thing this
   * library has to an answer for "the person who understood this graduated." The prediction score is
   * its most important line: it is what lets a mentor who was not in the room tell whether a student
   * ran the wizard or learned from it.
   *
   * @return the report
   */
  public String report() {
    StringBuilder sb = new StringBuilder();
    String name = m_target == null ? "(no mechanism selected)" : m_target.tuningName();
    sb.append("# ").append(name).append(" tuning session\n");
    if (m_recipe != null) {
      sb.append("Recipe: ")
          .append(m_recipe.version())
          .append("   |   ")
          .append(m_recipe.describe())
          .append('\n');
    }
    sb.append("Controller: port ")
        .append(m_inputs.port())
        .append(m_sharedControllerAck.isPresent() ? " (SHARED with the driver)" : " (dedicated)")
        .append("   |   Test mode required: yes\n");
    m_sharedControllerAck.ifPresent(r -> sb.append("Shared controller acknowledged: ").append(r).append('\n'));
    m_coastRiskAck.ifPresent(r -> sb.append("Coast risk acknowledged: ").append(r).append('\n'));
    m_skipSimAck.ifPresent(
        r -> sb.append("Simulation gate bypassed: ").append(r).append('\n'));
    if (m_target != null) {
      SimPromotionGate.of(m_target.tuningName())
          .ifPresent(
              p ->
                  sb.append("Sim promotion: ")
                      .append(p.describe(m_target.siDomain().label()))
                      .append('\n'));
    }
    sb.append('\n').append(predictionScore()).append('\n');
    for (StepResult r : m_results) {
      if (r.predictionCorrect().isPresent() && !r.predictionCorrect().get()) {
        sb.append("  MISSED - ").append(r.headline()).append('\n');
      }
    }
    sb.append('\n');
    int i = 1;
    for (StepResult r : m_results) {
      sb.append("## ").append(i++).append(". ").append(r.headline()).append("   ")
          .append(r.outcome())
          .append('\n');
      if (!r.quality().isEmpty()) {
        sb.append("   ").append(r.quality()).append('\n');
      }
      if (!r.verdict().isEmpty()) {
        sb.append("   ").append(r.verdict()).append('\n');
      }
      for (String w : r.warnings()) {
        sb.append("   WARNING: ").append(w).append('\n');
      }
    }
    sb.append("\n## Final gains\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "kS %.4f | kV %.4f | kA %.4f | kG %.4f | kP %.4f | kI %.4f | kD %.4f%n",
            m_gains.kS(),
            m_gains.kV(),
            m_gains.kA(),
            m_gains.kG(),
            m_gains.kP(),
            m_gains.kI(),
            m_gains.kD()));
    if (!m_narration.isEmpty()) {
      sb.append("\n## Narration\n");
      for (String line : m_narration) {
        sb.append("- ").append(line).append('\n');
      }
    }
    return sb.toString();
  }

  /** Clear every wizard-owned static. For tests. */
  public static void resetForTest() {
    SimPromotionGate.resetForTest();
  }

  // ===============================================================================================
  // the state machine
  // ===============================================================================================

  private void idle(boolean accept) {
    TunerPublisher.publishMechanismList();
    if (!accept) {
      return;
    }
    List<TuningTarget> targets = TuningRegistry.targets();
    if (targets.isEmpty()) {
      m_lastMessage =
          "No mechanism has been registered with TuningRegistry, so there is nothing to tune. Fix: "
              + "call TuningRegistry.register(yourMechanism) at robot construction.";
      return;
    }
    beginSession(targets.get(0));
  }

  private void ready(boolean go) {
    if (!go || m_recipe == null) {
      return;
    }
    if (m_stepIndex >= m_recipe.size()) {
      m_state = WizardState.DONE;
      return;
    }
    TuningStep step = m_recipe.step(m_stepIndex);
    snapshotStepEntry();

    if (!step.commandsMotion()) {
      // A question or an arithmetic panel. No supervisor, no held trigger, no volts.
      if (!m_stepBegun) {
        beginStep(step);
      }
      m_state = WizardState.RUNNING;
      return;
    }
    Optional<String> refusal = simGateRefusal();
    if (refusal.isPresent()) {
      m_lastMessage = refusal.get();
      TunerPublisher.narrate(m_lastMessage);
      return;
    }
    try {
      m_supervisor.arm();
    } catch (IllegalStateException e) {
      // The containment boundary. Eleven precise preconditions each produce their own sentence, and
      // the student needs to read the one that fired - not a stack trace in the console.
      m_lastMessage = e.getMessage();
      TunerPublisher.narrate(m_lastMessage);
      m_state = WizardState.READY;
      return;
    }
    m_state = WizardState.ARMED;
  }

  private void armed(boolean enable) {
    if (!enable) {
      return;
    }
    TuningStep step = m_recipe.step(m_stepIndex);
    if (!m_stepBegun) {
      beginStep(step);
    }
    m_state = step instanceof PreflightStep ? WizardState.PREFLIGHT : WizardState.RUNNING;
  }

  private void running(boolean enable) {
    TuningStep step = m_recipe.step(m_stepIndex);
    if (step.commandsMotion() && !enable) {
      // Releasing the trigger neutrals on the very next check. This is the most common abort in the
      // whole library, and the supervisor's abort path restores the idle mode before it neutrals so
      // that a still-coasting arm is not dropped.
      m_supervisor.abort(AbortReason.ENABLE_RELEASED);
      enterAborted(m_supervisor.lastAbortMessage());
      return;
    }
    StepContextImpl ctx = context();
    step.periodic(ctx);
    plot();

    if (step.commandsMotion() && !m_supervisor.isArmed() && m_supervisor.lastAbort().isPresent()) {
      enterAborted(m_supervisor.lastAbortMessage());
      return;
    }
    if (!step.isComplete(ctx)) {
      return;
    }
    StepResult result = step.finish(ctx);
    if (step.commandsMotion()) {
      m_supervisor.disarm();
    }
    if (step instanceof PredictStep) {
      PredictStep predict = (PredictStep) step;
      m_pendingPrediction = predict.correct();
      m_predictionsAsked++;
      if (m_pendingPrediction.orElse(false)) {
        m_predictionsRight++;
      } else {
        predict
            .question()
            .ifPresent(q -> m_missedPredictions.add(q.prompt() + "  -> " + q.whyCorrect()));
      }
      TunerPublisher.setPredictionResult(predictionScore(), result.verdict());
    } else {
      result = result.withPrediction(m_pendingPrediction);
      m_pendingPrediction = Optional.empty();
    }
    m_result = result;
    m_state = WizardState.REVIEW;

    // Express drops the review screen, never an interlock: a successful step advances on its own so
    // the whole recipe runs behind one held trigger.
    if (m_recipe.mode() == TuningRecipe.Mode.EXPRESS
        && result.outcome() == StepResult.Outcome.SUCCESS) {
      acceptResult();
    }
  }

  private void review(boolean accept, boolean retry, boolean back, boolean skip) {
    if (accept) {
      acceptResult();
      return;
    }
    if (retry) {
      m_gains = m_stepGains;
      m_proposed = Optional.empty();
      m_stepBegun = false;
      m_state = WizardState.READY;
      return;
    }
    if (skip) {
      m_results.add(
          new StepResult(
              StepResult.Outcome.SKIPPED,
              Optional.empty(),
              Double.NaN,
              Double.NaN,
              m_recipe.step(m_stepIndex).title() + " skipped",
              "",
              "The student skipped this step, so this gain is whatever it was before.",
              "If something behaves oddly later, this is the first line of the report to re-read.",
              m_pendingPrediction,
              List.of("Step skipped.")));
      m_pendingPrediction = Optional.empty();
      advance();
      return;
    }
    if (back && m_stepIndex > 0) {
      m_stepIndex--;
      if (m_stepIndex < m_gainsAtStepEntry.size()) {
        m_gains = m_gainsAtStepEntry.get(m_stepIndex);
        pushGains();
      }
      m_proposed = Optional.empty();
      m_stepBegun = false;
      m_state = WizardState.READY;
    }
  }

  private void done(boolean save, boolean saveAndExport, boolean discard) {
    if (discard) {
      m_gains = m_sessionGains;
      pushGains();
      m_lastMessage = "Discarded. Every gain is back to what it was when this session started.";
      m_state = WizardState.IDLE;
      return;
    }
    if (!save && !saveAndExport && !TunerPublisher.consumeCommand("save")) {
      return;
    }
    persist(saveAndExport);
  }

  private void abortAll() {
    if (m_supervisor != null && m_supervisor.isArmed()) {
      m_supervisor.abort(AbortReason.DISABLED);
    }
    m_gains = m_sessionGains;
    pushGains();
    m_proposed = Optional.empty();
    m_stepBegun = false;
    m_lastMessage =
        "Everything stopped and every gain is back to what it was when this session started.";
    m_state = WizardState.ABORTED;
    publish();
  }

  private void enterAborted(String reason) {
    // Entering ABORTED restores the gains to the snapshot taken when this step was armed - not the
    // session snapshot, which is what "abort all" does.
    m_gains = m_stepGains;
    pushGains();
    m_proposed = Optional.empty();
    m_stepBegun = false;
    m_lastMessage = reason;
    TunerPublisher.narrate(reason);
    TunerPublisher.setSafety(true, m_supervisor.lastAbort().map(Enum::name).orElse(""), reason, "");
    m_state = WizardState.ABORTED;
  }

  private void acceptResult() {
    if (m_result == null) {
      return;
    }
    m_proposed.ifPresent(g -> m_gains = g);
    m_proposed = Optional.empty();
    if (m_result.setsAGain()) {
      m_gains = m_gains.with(m_result.gain().get(), m_result.value());
    }
    pushGains();
    if (!(m_recipe.step(m_stepIndex) instanceof PredictStep)) {
      m_results.add(m_result);
    }
    advance();
  }

  private void advance() {
    m_result = null;
    m_stepBegun = false;
    m_stepIndex++;
    if (m_stepIndex >= m_recipe.size()) {
      m_state = WizardState.DONE;
      m_lastMessage = m_target.tuningName() + " is tuned. " + predictionScore();
      return;
    }
    m_state = WizardState.READY;
  }

  // ===============================================================================================
  // session plumbing
  // ===============================================================================================

  private void beginSession(TuningTarget target) {
    m_target = target;
    m_supervisor =
        new TuningSupervisor(target, SafetyEnvelope.derive(target), m_inputs::enableHeld);
    m_coastRiskAck.ifPresent(m_supervisor::acknowledgeCoastRisk);
    try {
      m_recipe = resolveRecipe(target);
    } catch (IllegalArgumentException e) {
      // A diagnostic must never become the outage: an unsupported archetype is a message, not a
      // crash out of robotPeriodic().
      m_recipe = null;
      m_lastMessage = e.getMessage();
      m_state = WizardState.IDLE;
      TunerPublisher.narrate(m_lastMessage);
      return;
    }
    m_stepIndex = 0;
    m_stepBegun = false;
    m_results.clear();
    m_narration.clear();
    m_missedPredictions.clear();
    m_gainsAtStepEntry.clear();
    m_buffer.clear();
    m_predictionsAsked = 0;
    m_predictionsRight = 0;
    m_pendingPrediction = Optional.empty();
    m_proposed = Optional.empty();
    m_sessionGains = target.gains();
    m_stepGains = m_sessionGains;
    m_gains = m_sessionGains;
    m_result = null;
    m_state = WizardState.READY;
    TunerPublisher.setSelected(target.tuningName());
    TunerPublisher.setMode(m_recipe.mode().name());
    TunerPublisher.narrate(m_recipe.describe());
  }

  private TuningRecipe resolveRecipe(TuningTarget target) {
    TuningRecipe override = m_recipeOverrides.get(target.tuningName());
    if (override != null) {
      return override;
    }
    return Recipes.forArchetype(target.archetype(), defaultMode(target));
  }

  /**
   * Which mode to use when the team has not forced one.
   *
   * <p>Teaching in simulation, always, and not overridable per mechanism. On hardware, whatever the
   * student chose last time, remembered per mechanism. The fourth mechanism of the day is express by
   * default, and a student who has never tuned this mechanism is offered the lesson.
   */
  private TuningRecipe.Mode defaultMode(TuningTarget target) {
    if (RobotIdentity.current() == RobotId.SIM) {
      return TuningRecipe.Mode.TEACHING;
    }
    if (m_forcedMode.isPresent()) {
      return m_forcedMode.get();
    }
    Double remembered = TunedValueStore.setpoints(target.tuningName()).get("preferredMode");
    if (remembered != null && remembered.intValue() == TuningRecipe.Mode.EXPRESS.ordinal()) {
      return TuningRecipe.Mode.EXPRESS;
    }
    return TuningRecipe.Mode.TEACHING;
  }

  private Optional<String> simGateRefusal() {
    if (m_skipSimAck.isPresent()) {
      return Optional.empty();
    }
    return SimPromotionGate.refusal(m_target, m_recipe);
  }

  private void snapshotStepEntry() {
    m_stepGains = m_gains;
    while (m_gainsAtStepEntry.size() <= m_stepIndex) {
      m_gainsAtStepEntry.add(m_gains);
    }
    m_gainsAtStepEntry.set(m_stepIndex, m_gains);
  }

  private void beginStep(TuningStep step) {
    m_stepStartSeconds = Clock.seconds();
    // The dashboard's answer topic is sticky, so remember what it said before the question was
    // asked. Without this, the second prediction of a session answers itself with the first one's
    // reply and the score becomes meaningless.
    m_predictionBaseline = TunerPublisher.predictionAnswer();
    m_buffer.clear();
    m_proposed = Optional.empty();
    step.begin(context());
    m_stepBegun = true;
  }

  private void pushGains() {
    if (m_target != null && m_target.gainSink() != null) {
      m_target.gainSink().apply(m_gains);
    }
  }

  private void persist(boolean alsoExport) {
    String mechanism = m_target.tuningName();
    Map<GainId, String> provenance = new LinkedHashMap<>();
    for (GainId id : GainId.values()) {
      provenance.put(id, ValueSource.WIZARD_LQR.name());
    }
    provenance.put(GainId.KS, ValueSource.WIZARD_BISECTION.name());
    provenance.put(GainId.KG, ValueSource.WIZARD_BISECTION.name());
    provenance.put(GainId.KV, ValueSource.WIZARD_OLS.name());
    provenance.put(GainId.KA, ValueSource.WIZARD_OLS.name());
    if (m_recipe.hasRefinementPass()) {
      provenance.put(GainId.KP, ValueSource.WIZARD_REFINE.name());
      provenance.put(GainId.KD, ValueSource.WIZARD_REFINE.name());
    }
    boolean ok =
        TunedValueStore.saveToRobot(
            mechanism,
            SimPromotionGate.configHash(m_target),
            m_gains,
            Map.of(),
            provenance,
            Optional.empty());
    TunedValueStore.saveSetpoint(mechanism, "preferredMode", m_recipe.mode().ordinal());
    if (alsoExport) {
      ValueExporter.writeJava(mechanism);
      ValueExporter.writeDeployBaseline();
      ValueExporter.writeMarkdownReport();
      TunerPublisher.setExports(
          ValueExporter.toJava(mechanism), TunedValueStore.robotFile().toString(), report());
    }
    m_lastMessage =
        ok
            ? mechanism
                + " saved. These gains now survive a reboot and a redeploy. "
                + predictionScore()
            : mechanism
                + " could not be saved to the roboRIO. The gains are still live on the robot but "
                + "will be lost at the next reboot - check the Driver Station for a disk-space "
                + "warning and press save again.";
    m_state = WizardState.IDLE;
  }

  private void publish() {
    TunerPublisher.setState(m_state.name());
    if (m_recipe == null) {
      return;
    }
    if (m_stepIndex < m_recipe.size()) {
      TuningStep step = m_recipe.step(m_stepIndex);
      double expected = Math.max(step.expectedSeconds(context()), 1e-9);
      double progress =
          m_state.motionAllowed()
              ? Math.min(1.0, (Clock.seconds() - m_stepStartSeconds) / expected)
              : 0.0;
      TunerPublisher.setStep(
          m_stepIndex + 1,
          m_recipe.size(),
          step.title(),
          step.explanation(),
          step.willDo(),
          step.watchFor(),
          progress);
    }
    if (m_result != null) {
      TunerPublisher.setResult(
          m_result.gain().map(GainId::key).orElse(""),
          m_result.value(),
          m_result.previousValue(),
          m_result.headline(),
          m_result.quality(),
          m_result.verdict(),
          m_result.recommendation(),
          m_result.warnings().toArray(new String[0]));
    }
  }

  private void plot() {
    if (m_buffer.isEmpty()) {
      return;
    }
    SampleBuffer.Sample s = m_buffer.latest();
    TunerPublisher.plot(
        s.setpoint(),
        s.measurement(),
        s.setpoint(),
        s.commandedVolts(),
        s.feedforwardVolts(),
        s.feedbackVolts(),
        s.velocity(),
        m_target.statorCurrentAmps().orElse(Double.NaN));
  }

  private StepContextImpl context() {
    return new StepContextImpl();
  }

  private static boolean rising(boolean now, boolean previous) {
    return now && !previous;
  }

  /**
   * Whether this wizard refused to start because it shares a port with the driver.
   *
   * @return the refusal message, or empty
   */
  public Optional<String> blockedReason() {
    return m_blocked;
  }

  /**
   * Check the controller-port conflict. Called from the gamepad factory, and public so a team using
   * {@link #using(WizardInputs)} with a real port can opt into the same protection.
   *
   * @return this wizard
   */
  public TuningWizard checkControllerPort() {
    int port = m_inputs.port();
    if (port < 0 || m_sharedControllerAck.isPresent() || !ControlMap.isPortRegistered(port)) {
      return this;
    }
    String message =
        "The tuning wizard and the driver controls are both on controller port "
            + port
            + ". Right trigger means 'shoot' to your driver and 'authorize motion' to the wizard. "
            + "Move the wizard to its own port with TuningWizard.using(new "
            + "CommandXboxController(2)), or call acknowledgeSharedController(\"why\") if you "
            + "really only have one controller.";
    m_blocked = Optional.of(message);
    m_lastMessage = message;
    Alerts.error(kAlertGroup, message, MatchImpact.PIT_ONLY).set(true);
    return this;
  }

  // ===============================================================================================
  // the context handed to steps
  // ===============================================================================================

  private final class StepContextImpl implements StepContext {

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
      m_proposed = Optional.ofNullable(gains);
    }

    @Override
    public double toleranceSi() {
      return m_supervisor.effectiveToleranceSi();
    }

    @Override
    public double elapsedSeconds() {
      return Double.isNaN(m_stepStartSeconds) ? 0.0 : Clock.seconds() - m_stepStartSeconds;
    }

    @Override
    public double dt() {
      return Clock.dt();
    }

    @Override
    public SampleBuffer buffer() {
      return m_buffer;
    }

    @Override
    public void narrate(String line) {
      if (line == null || line.isBlank()) {
        return;
      }
      m_narration.add(line);
      TunerPublisher.narrate(line);
    }

    @Override
    public void publishProgress(double fraction0to1) {
      // The publisher owns the step topic; progress rides along with the next setStep call.
      m_lastProgress = Math.max(0.0, Math.min(1.0, fraction0to1));
    }

    @Override
    public TuningRecipe.Mode mode() {
      return m_recipe == null ? TuningRecipe.Mode.TEACHING : m_recipe.mode();
    }

    @Override
    public void askPrediction(String prompt, List<String> options) {
      TunerPublisher.setPrediction(prompt, options.toArray(new String[0]));
    }

    @Override
    public OptionalInt predictionAnswer() {
      if (m_predictionOverride.isPresent()) {
        return m_predictionOverride;
      }
      int fromDashboard = TunerPublisher.predictionAnswer();
      return fromDashboard >= 0 && fromDashboard <= 2 && fromDashboard != m_predictionBaseline
          ? OptionalInt.of(fromDashboard)
          : OptionalInt.empty();
    }

    @Override
    public void publishPredictionFeedback(String feedback) {
      TunerPublisher.setPredictionResult(predictionScore(), feedback);
    }
  }

  private double m_lastProgress;

  /**
   * The most recent progress fraction a step published.
   *
   * @return progress from zero to one
   */
  public double progress() {
    return m_lastProgress;
  }

  // ===============================================================================================
  // the gamepad adapter
  // ===============================================================================================

  /** The standard bindings, on a dedicated port. */
  private static final class XboxInputs implements WizardInputs {
    private final CommandXboxController m_controller;

    XboxInputs(CommandXboxController controller) {
      m_controller = controller;
    }

    @Override
    public boolean enableHeld() {
      return m_controller.getRightTriggerAxis() > kTriggerThreshold;
    }

    @Override
    public boolean accept() {
      return m_controller.getHID().getAButton();
    }

    @Override
    public boolean retry() {
      return m_controller.getHID().getBButton();
    }

    @Override
    public boolean back() {
      return m_controller.getHID().getXButton();
    }

    @Override
    public boolean skip() {
      return m_controller.getHID().getYButton();
    }

    @Override
    public boolean abortAll() {
      return m_controller.getHID().getStartButton();
    }

    @Override
    public OptionalInt predictionSelection() {
      int pov = m_controller.getHID().getPOV();
      if (pov == 0) {
        return OptionalInt.of(0);
      }
      if (pov == 270) {
        return OptionalInt.of(1);
      }
      if (pov == 90) {
        return OptionalInt.of(2);
      }
      return OptionalInt.empty();
    }

    @Override
    public double manualNudge() {
      return -m_controller.getLeftY();
    }

    @Override
    public int port() {
      return m_controller.getHID().getPort();
    }
  }
}

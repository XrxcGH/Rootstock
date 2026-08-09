package org.pumpkinlib.core.selftest;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.pumpkinlib.core.alert.AlertRegistry;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.core.selftest.SelfTestResult.StepResult;

/**
 * Runs one {@link SelfTestRoutine} as a single command.
 *
 * <p>Package-private and never public, because DESIGN.md §8 rule 7 forbids user-extendable abstract
 * classes in the public API and this is the one place the library has to extend {@code Command}. It
 * drives each step's command directly — {@code initialize} / {@code execute} / {@code end} — exactly
 * as a WPILib command group drives its members, and declares the union of every step's requirements
 * so that nothing can interrupt a step half way through and leave a mechanism somewhere unexpected.
 *
 * <p>Everything time-based here is <strong>cycle-counted</strong>: step timeouts are converted once
 * with {@code Clock.cyclesFor(...)}, so a replayed log times out on exactly the cycles the real
 * robot did.
 */
final class SelfTestRunner extends Command {

  private final SelfTestRoutine m_routine;
  private final List<StepResult> m_results = new ArrayList<>();

  private final Set<PumpkinAlert> m_blockingAtStart =
      Collections.newSetFromMap(new IdentityHashMap<>());

  private int m_index;
  private Command m_active;
  private int m_stepCycles;
  private int m_elapsedCycles;
  private double m_startSeconds;
  private boolean m_aborted;
  private String m_detail = "";

  SelfTestRunner(SelfTestRoutine routine) {
    m_routine = routine;
    for (SelfTestStep step : routine.steps()) {
      Set<Subsystem> required = step.action().getRequirements();
      addRequirements(required.toArray(new Subsystem[0]));
    }
    setName("SelfTest/" + routine.name());
  }

  /**
   * The routine this runner executes.
   *
   * @return the routine
   */
  SelfTestRoutine routine() {
    return m_routine;
  }

  @Override
  public void initialize() {
    m_startSeconds = Clock.seconds();
    m_results.clear();
    m_index = 0;
    m_active = null;
    m_aborted = false;
    m_detail = "";

    if (!SelfTest.gateOpen()) {
      SelfTest.raiseRefusal(m_routine.name());
      m_detail = "REFUSED - " + SelfTest.gateRefusalReason();
      m_aborted = true;
      m_index = m_routine.steps().size();
      return;
    }
    SelfTest.clearRefusal();

    if (MatchContext.isDisabled()) {
      // The gate allows this (disabled and off the field) but the HAL will not let a motor move,
      // so every motion step is about to fail for a reason that has nothing to do with the robot.
      // Say so up front rather than letting somebody chase a phantom.
      m_detail =
          "ran while DISABLED - the HAL inhibits motor output, so motion steps will fail. "
              + "Enable Test mode for a result you can act on.";
    }

    m_blockingAtStart.clear();
    m_blockingAtStart.addAll(AlertRegistry.blocking());

    for (Expect e : m_routine.routineExpectations()) {
      e.begin();
    }
    startStep();
  }

  @Override
  public void execute() {
    for (Expect e : m_routine.routineExpectations()) {
      e.sample();
    }

    PumpkinAlert culprit = m_routine.abortOnError() ? newBlockingAlert() : null;
    if (culprit != null) {
      m_detail =
          "aborted: blocking alert activated mid-run - \""
              + culprit.group()
              + ": "
              + culprit.text()
              + "\". Fix that first; the rest of this routine would only add noise.";
      finishStep(true, "aborted");
      m_aborted = true;
      return;
    }

    if (m_active == null) {
      return;
    }

    m_active.execute();
    for (Expect e : currentStep().expectations()) {
      e.sample();
    }
    m_elapsedCycles++;

    boolean expectationsMet = allMet(currentStep().expectations());
    boolean commandDone = m_active.isFinished();
    boolean timedOut = m_elapsedCycles >= m_stepCycles;

    if (commandDone || timedOut || (expectationsMet && !currentStep().expectations().isEmpty())) {
      finishStep(timedOut && !commandDone, timedOut && !commandDone ? "timed out" : "");
      m_index++;
      startStep();
    }
  }

  @Override
  public boolean isFinished() {
    return m_aborted || m_index >= m_routine.steps().size();
  }

  @Override
  public void end(boolean interrupted) {
    if (m_active != null) {
      m_active.end(true);
      m_active = null;
    }
    if (interrupted && m_detail.isEmpty()) {
      m_detail = "interrupted before it finished - another command took a required subsystem";
    }

    boolean passed = !m_aborted && !interrupted;
    for (Expect e : m_routine.routineExpectations()) {
      boolean ok = e.satisfied();
      passed &= ok;
      m_results.add(new StepResult("(routine)", ok, e.description(), e.observed()));
    }
    for (StepResult r : m_results) {
      passed &= r.passed();
    }

    SelfTest.publish(
        new SelfTestResult(
            m_routine.name(),
            passed,
            Clock.seconds() - m_startSeconds,
            new ArrayList<>(m_results),
            m_detail));
  }

  @Override
  public boolean runsWhenDisabled() {
    // True on purpose, and it is the only way the safety gate can ever be heard. If this returned
    // false, pressing the pit button while disabled would be silently swallowed by the scheduler:
    // no run, no refusal, no message. A button that does nothing and says nothing is worse than no
    // button. So the command always starts, and it either refuses out loud or runs and says up
    // front that the robot is disabled.
    return true;
  }

  private void startStep() {
    if (m_index >= m_routine.steps().size()) {
      m_active = null;
      return;
    }
    SelfTestStep step = currentStep();
    m_stepCycles = Math.max(1, Clock.cyclesFor(step.timeout()));
    m_elapsedCycles = 0;
    for (Expect e : step.expectations()) {
      e.begin();
    }
    m_active = step.action();
    m_active.initialize();
  }

  private void finishStep(boolean interrupted, String note) {
    if (m_index >= m_routine.steps().size()) {
      return;
    }
    SelfTestStep step = currentStep();
    if (m_active != null) {
      m_active.end(interrupted);
      m_active = null;
    }
    if (step.expectations().isEmpty()) {
      m_results.add(
          new StepResult(
              step.name(),
              !interrupted,
              "runs to completion within " + step.timeout().toShortString(),
              note.isEmpty() ? "completed" : note));
      return;
    }
    for (Expect e : step.expectations()) {
      boolean ok = e.satisfied();
      String observed = e.observed();
      if (!note.isEmpty()) {
        observed = observed + " (" + note + ")";
      }
      m_results.add(new StepResult(step.name(), ok, e.description(), observed));
    }
  }

  private SelfTestStep currentStep() {
    return m_routine.steps().get(Math.min(m_index, m_routine.steps().size() - 1));
  }

  private PumpkinAlert newBlockingAlert() {
    for (PumpkinAlert a : AlertRegistry.blocking()) {
      if (!m_blockingAtStart.contains(a)) {
        return a;
      }
    }
    return null;
  }

  private static boolean allMet(List<Expect> expectations) {
    for (Expect e : expectations) {
      if (!e.satisfied()) {
        return false;
      }
    }
    return true;
  }
}

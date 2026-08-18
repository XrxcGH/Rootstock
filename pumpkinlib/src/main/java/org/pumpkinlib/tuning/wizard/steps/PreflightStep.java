package org.pumpkinlib.tuning.wizard.steps;

import edu.wpi.first.wpilibj2.command.Command;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.pumpkinlib.control.GainId;
import org.pumpkinlib.control.MechanicalHealthCheck;
import org.pumpkinlib.tuning.wizard.Lessons;
import org.pumpkinlib.tuning.wizard.StepContext;
import org.pumpkinlib.tuning.wizard.StepResult;
import org.pumpkinlib.tuning.wizard.TuningStep;

/**
 * Runs the mechanical health check before a single gain is touched, and refuses to continue if it
 * blocks.
 *
 * <p><b>This is the step that carries the safety case.</b> Not the simulation gate, and not the
 * abort list — those matter, but the single most destructive class of tuning accident is tuning a
 * mechanism that was already broken: an inverted encoder, a slipping belt, a frozen sensor, or a
 * gravity mechanism sitting in coast mode. Every one of those is detectable in about twelve seconds
 * of low-power motion, and nothing else in the FRC ecosystem checks for any of them.
 *
 * <p>Telling a student "your belt is loose, fix that before touching kP" is worth more than any gain
 * this library could compute, because no gain can fix slop. That sentence is what this step exists
 * to produce.
 *
 * <p>On a {@code BLOCK} verdict the recipe cannot proceed. That is deliberate and there is no
 * acknowledgement string for it — the three escape hatches this library ships (shared controller,
 * coast risk, simulation gate) are all things a knowledgeable adult might reasonably override, and
 * "tune it anyway, the encoder is backwards" is not.
 */
public final class PreflightStep implements TuningStep {

  private MechanicalHealthCheck m_check;
  private Command m_command;
  private boolean m_started;
  private boolean m_finished;

  /** Creates the step. Both recipe modes use the same one; express may drop teaching, never this. */
  public PreflightStep() {}

  /**
   * The health check this step ran, once it has run one.
   *
   * @return the check, or empty before {@link #begin(StepContext)}
   */
  public Optional<MechanicalHealthCheck> check() {
    return Optional.ofNullable(m_check);
  }

  @Override
  public String title() {
    return "Pre-flight";
  }

  @Override
  public Optional<GainId> produces() {
    return Optional.empty();
  }

  @Override
  public String explanation() {
    return "Before we tune, let us check the machine.\n\n"
        + "PID gains cannot compensate for slop, and no gain in the world fixes a belt that skips "
        + "or an encoder that is counting backwards. So this step spends about twelve seconds at "
        + "low power finding out whether this mechanism is in a fit state to be tuned at all: is "
        + "the idle mode right, does the sensor move when the motor does, does positive voltage "
        + "actually move it in the positive direction, how much backlash is there, and does the "
        + "motor encoder still agree with the absolute encoder after a full sweep.\n\n"
        + "If it stops you here, fix the hardware. That is not the wizard being fussy - it is the "
        + "wizard refusing to give you numbers that describe a broken machine.\n\n"
        + Lessons.WHY_FEEDFORWARD_FIRST;
  }

  @Override
  public String watchFor() {
    return "Watch and listen. Grinding, clicking or a mechanism that moves the wrong way are all "
        + "things this check will name, and all things you want named now rather than at speed.";
  }

  @Override
  public String willDo() {
    return "I will nudge this mechanism gently in both directions at low power, about twelve "
        + "seconds in total, and tell you what I find.";
  }

  @Override
  public void begin(StepContext ctx) {
    m_check = MechanicalHealthCheck.of(ctx.supervisor());
    m_command = m_check.command();
    m_started = false;
    m_finished = false;
    ctx.narrate("Checking the machine before we touch a gain.");
  }

  @Override
  public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions.
    if (ctx.supervisor().check().isPresent()) {
      endCommand(true);
      m_finished = true;
      return;
    }
    if (m_command == null) {
      m_finished = true;
      return;
    }
    if (!m_started) {
      m_command.initialize();
      m_started = true;
    }
    if (m_command.isFinished()) {
      endCommand(false);
      m_finished = true;
      return;
    }
    m_command.execute();
    ctx.publishProgress(Math.min(1.0, ctx.elapsedSeconds() / 12.0));
  }

  @Override
  public boolean isComplete(StepContext ctx) {
    return m_finished;
  }

  @Override
  public StepResult finish(StepContext ctx) {
    Optional<MechanicalHealthCheck.HealthReport> report =
        m_check == null ? Optional.empty() : m_check.lastReport();
    if (report.isEmpty()) {
      return StepResult.retry(
          "Pre-flight did not complete",
          "The health check stopped before it produced a verdict, so nothing about this mechanism "
              + "has actually been checked.",
          "Press B to run it again with the trigger held for the whole twelve seconds.",
          List.of());
    }
    MechanicalHealthCheck.HealthReport r = report.get();
    List<String> warnings = new ArrayList<>(r.findings());
    for (String finding : r.findings()) {
      ctx.narrate(finding);
    }
    switch (r.verdict()) {
      case BLOCK:
        return StepResult.failed(
            "Pre-flight BLOCKED",
            r.describe(),
            "Fix the hardware fault named above, then start the recipe again. Tuning around a "
                + "mechanical fault produces gains that describe the fault.");
      case WARN:
        return StepResult.informational(
            "Pre-flight PASS with warnings",
            r.describe(),
            "You can carry on, but read the warnings first - they are the most likely explanation "
                + "for anything strange later in the recipe.",
            warnings);
      case PASS:
      default:
        return StepResult.informational(
            "Pre-flight PASS",
            r.describe(),
            "The machine is in a fit state to tune. Press A to start with gravity.",
            warnings);
    }
  }

  @Override
  public double expectedSeconds(StepContext ctx) {
    return 12.0;
  }

  private void endCommand(boolean interrupted) {
    if (m_command != null && m_started) {
      m_command.end(interrupted);
    }
    m_command = null;
  }
}

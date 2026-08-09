package org.pumpkinlib.core.selftest;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Unit;
import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.Command;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A named, ordered sequence of steps with expectations — the whole content of one subsystem's
 * self-test.
 *
 * <p>A complete example, and this really is the whole thing a team writes:
 *
 * <pre>{@code
 * SelfTest.register("Arm", () -> SelfTestRoutine.of("Arm")
 *     .current(arm::statorAmps)
 *     .step("extend", arm.toAngle(Degrees.of(90)))
 *         .expect(arm::angle, Degrees.of(90), Degrees.of(2)).withTimeout(Seconds.of(2))
 *     .step("retract", arm.toAngle(Degrees.of(0)))
 *         .expect(arm::angle, Degrees.of(0), Degrees.of(2)).withTimeout(Seconds.of(2))
 *     .expectCurrentBetween(Amps.of(2), Amps.of(35))
 *     .expectNoNewFaults()
 *     .build());
 * }</pre>
 *
 * <p>Routines are <strong>built fresh for every run</strong> from a {@code Supplier}, because an
 * {@link Expect} carries the mutable state of one execution. That is also why {@link #of(String)}
 * returns a builder rather than a cached object.
 */
public final class SelfTestRoutine {

  /**
   * Default per-step timeout when none is given. Three seconds: long enough for any single
   * mechanism move, short enough that a hung step does not hold a mechanism against a hard stop
   * while somebody works out which button cancels it. The design does not specify a default; this is
   * the value chosen here.
   */
  public static final double kDefaultStepTimeoutSeconds = 3.0;

  private final String m_name;
  private final List<SelfTestStep> m_steps;
  private final List<Expect> m_routineExpectations;
  private final boolean m_abortOnError;

  private SelfTestRoutine(
      String name, List<SelfTestStep> steps, List<Expect> routineExpectations, boolean abortOnError) {
    m_name = name;
    m_steps = List.copyOf(steps);
    m_routineExpectations = List.copyOf(routineExpectations);
    m_abortOnError = abortOnError;
  }

  /**
   * Start building a routine.
   *
   * @param mechanismName the routine name; becomes the result key and the NT subtable
   * @return the builder
   * @throws IllegalArgumentException if {@code mechanismName} is blank
   */
  public static Builder of(String mechanismName) {
    if (mechanismName == null || mechanismName.isBlank()) {
      throw new IllegalArgumentException(
          "SelfTestRoutine.of: mechanismName is blank. It is the result key and the NT subtable.");
    }
    return new Builder(mechanismName);
  }

  /**
   * The routine name.
   *
   * @return the name
   */
  public String name() {
    return m_name;
  }

  /**
   * The steps, in order.
   *
   * @return an unmodifiable list
   */
  public List<SelfTestStep> steps() {
    return m_steps;
  }

  /**
   * The expectations evaluated across the whole routine rather than within one step.
   *
   * @return an unmodifiable list
   */
  public List<Expect> routineExpectations() {
    return m_routineExpectations;
  }

  /**
   * Whether the routine aborts when a blocking alert activates mid-run.
   *
   * @return true if it aborts
   */
  public boolean abortOnError() {
    return m_abortOnError;
  }

  /**
   * A multi-line description of what this routine will do, for the pit printout.
   *
   * @return the name, the steps and the routine-wide expectations
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("SelfTestRoutine \"").append(m_name).append("\"");
    sb.append(m_abortOnError ? " (aborts on a blocking alert)" : " (runs to completion)").append('\n');
    for (SelfTestStep s : m_steps) {
      sb.append("  ").append(s.describe()).append('\n');
      for (Expect e : s.expectations()) {
        sb.append("      expect ").append(e.description()).append('\n');
      }
    }
    for (Expect e : m_routineExpectations) {
      sb.append("  expect (whole routine) ").append(e.description()).append('\n');
    }
    return sb.toString();
  }

  /** Routine-level builder. Obtained from {@link SelfTestRoutine#of(String)}. */
  public static final class Builder {

    private final String m_name;
    private final List<SelfTestStep> m_steps = new ArrayList<>();
    private final List<Expect> m_routineExpectations = new ArrayList<>();
    private final List<Current[]> m_currentBands = new ArrayList<>();
    private DoubleSupplier m_current;
    private boolean m_abortOnError = true;

    private Builder(String name) {
      m_name = name;
    }

    /**
     * Supply the current measurement that {@link #expectCurrentBetween(Current, Current)} uses.
     *
     * <p>Not in the frozen design sketch, and it has to exist: {@code expectCurrentBetween} takes
     * only a band, so something must say <em>whose</em> current. Rather than invent a hidden
     * convention, the routine is told once, here.
     *
     * @param amps stator current for this mechanism, e.g. {@code arm::statorAmps}
     * @return this, for chaining
     */
    public Builder current(DoubleSupplier amps) {
      m_current = amps;
      return this;
    }

    /**
     * Begin a step.
     *
     * @param name the step name, e.g. {@code "extend"}
     * @param action the command to run. Must terminate on its own or be bounded by the step timeout
     * @return the step builder, for expectations and a timeout
     * @throws IllegalArgumentException if {@code name} is blank or {@code action} is null
     */
    public StepBuilder step(String name, Command action) {
      return new StepBuilder(this, name, action);
    }

    /**
     * Assert current stays inside this band for the whole routine.
     *
     * <p>Both ends matter: above means binding, below means the motor is not actually driving.
     *
     * @param min the lowest acceptable draw
     * @param max the highest acceptable draw
     * @return this, for chaining
     * @throws IllegalArgumentException if the band is inverted
     */
    public Builder expectCurrentBetween(Current min, Current max) {
      if (min == null || max == null) {
        throw new IllegalArgumentException(
            "SelfTestRoutine \""
                + m_name
                + "\".expectCurrentBetween: min and max are both required, e.g. Amps.of(2) and "
                + "Amps.of(35).");
      }
      // Held until build(), where it can be paired with the routine's current source.
      m_currentBands.add(new Current[] {min, max});
      return this;
    }

    /**
     * Assert the routine caused no new health faults. Diffs {@code
     * HealthMonitor.faultFingerprints()} across the run.
     *
     * @return this, for chaining
     */
    public Builder expectNoNewFaults() {
      m_routineExpectations.add(Expect.noNewFaults());
      return this;
    }

    /**
     * Whether to abort the routine when a <em>blocking</em> alert activates mid-run.
     *
     * <p>Blocking, not any alert at all: otherwise a {@code PIT_ONLY} gravity-gain warning aborts
     * the self-test that would have found the real problem. Default true.
     *
     * @param b true to abort
     * @return this, for chaining
     */
    public Builder abortOnError(boolean b) {
      m_abortOnError = b;
      return this;
    }

    /**
     * Finish the routine.
     *
     * @return the immutable routine
     * @throws IllegalStateException if no steps were declared, or if {@link
     *     #expectCurrentBetween(Current, Current)} was used without {@link #current(DoubleSupplier)}
     */
    public SelfTestRoutine build() {
      if (m_steps.isEmpty()) {
        throw new IllegalStateException(
            "SelfTestRoutine \""
                + m_name
                + "\" has no steps. Add at least one .step(name, command) - a routine with no steps "
                + "reports PASS and proves nothing, which is worse than no routine at all.");
      }
      List<Expect> resolved = new ArrayList<>(m_routineExpectations);
      for (Current[] band : m_currentBands) {
        if (m_current == null) {
          throw new IllegalStateException(
              "SelfTestRoutine \""
                  + m_name
                  + "\" calls .expectCurrentBetween("
                  + band[0].in(Units.Amps)
                  + " A, "
                  + band[1].in(Units.Amps)
                  + " A) but never says whose current to read. Add "
                  + ".current(mechanism::statorAmps) before .build().");
        }
        resolved.add(Expect.currentBetween(m_current, band[0], band[1]));
      }
      return new SelfTestRoutine(m_name, m_steps, resolved, m_abortOnError);
    }

    private void addStep(SelfTestStep step) {
      m_steps.add(step);
    }
  }

  /**
   * Per-step builder. Obtained from {@link Builder#step(String, Command)}.
   *
   * <p>It also carries the routine-level terminators ({@link #expectCurrentBetween}, {@link
   * #expectNoNewFaults}, {@link #abortOnError}, {@link #build}) so the fluent chain in the class
   * javadoc reads the way it is written there — the last step's expectations flow straight into the
   * routine's.
   */
  public static final class StepBuilder {

    private final Builder m_parent;
    private final String m_name;
    private final Command m_action;
    private final List<Expect> m_expectations = new ArrayList<>();
    private Time m_timeout = Units.Seconds.of(kDefaultStepTimeoutSeconds);
    private boolean m_committed;

    private StepBuilder(Builder parent, String name, Command action) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException(
            "SelfTestRoutine \"" + parent.m_name + "\": a step name is blank.");
      }
      if (action == null) {
        throw new IllegalArgumentException(
            "SelfTestRoutine \""
                + parent.m_name
                + "\" step \""
                + name
                + "\": action is null. Pass the command that performs the step, e.g. "
                + "arm.toAngle(Degrees.of(90)).");
      }
      m_parent = parent;
      m_name = name;
      m_action = action;
    }

    /**
     * Assert a measured quantity settles within tolerance of a target before the step times out.
     *
     * @param <U> the unit type
     * @param measured the live measurement, e.g. {@code arm::angle}
     * @param target the value it should reach
     * @param tolerance how close counts as reached
     * @return this, for chaining
     */
    public <U extends Unit> StepBuilder expect(
        Supplier<Measure<U>> measured, Measure<U> target, Measure<U> tolerance) {
      m_expectations.add(Expect.settles(m_name, measured, target, tolerance));
      return this;
    }

    /**
     * Unitless form of {@link #expect(Supplier, Measure, Measure)}, for a quantity WPILib has no
     * unit for.
     *
     * @param measured the live measurement
     * @param target the value it should reach
     * @param tolerance how close counts as reached
     * @param unitLabel the unit, printed in the message
     * @return this, for chaining
     */
    public StepBuilder expect(
        DoubleSupplier measured, double target, double tolerance, String unitLabel) {
      m_expectations.add(Expect.settles(m_name, measured, target, tolerance, unitLabel));
      return this;
    }

    /**
     * Assert something changed at all — the disconnected-encoder detector.
     *
     * @param measured the live measurement
     * @param minDelta the smallest change that counts as movement
     * @param unitLabel the unit, printed in the message
     * @return this, for chaining
     */
    public StepBuilder expectMoved(DoubleSupplier measured, double minDelta, String unitLabel) {
      m_expectations.add(Expect.moved(m_name, measured, minDelta, unitLabel));
      return this;
    }

    /**
     * Bound this step's runtime. Defaults to {@value #kDefaultStepTimeoutSeconds} seconds.
     *
     * @param t the timeout; must be positive
     * @return this, for chaining
     * @throws IllegalArgumentException if {@code t} is null or not positive
     */
    public StepBuilder withTimeout(Time t) {
      if (t == null || !(t.in(Units.Seconds) > 0.0)) {
        throw new IllegalArgumentException(
            "SelfTestRoutine step \""
                + m_name
                + "\": withTimeout must be a positive Time, e.g. Seconds.of(2). The timeout is what "
                + "makes this safe to run in the pit.");
      }
      m_timeout = t;
      return this;
    }

    /**
     * Commit this step and begin the next one.
     *
     * @param name the next step's name
     * @param action the next step's command
     * @return the next step builder
     */
    public StepBuilder step(String name, Command action) {
      commit();
      return m_parent.step(name, action);
    }

    /**
     * Commit this step and assert current stays inside this band for the whole routine.
     *
     * @param min the lowest acceptable draw
     * @param max the highest acceptable draw
     * @return the routine builder
     */
    public Builder expectCurrentBetween(Current min, Current max) {
      commit();
      return m_parent.expectCurrentBetween(min, max);
    }

    /**
     * Commit this step and assert the routine caused no new health faults.
     *
     * @return the routine builder
     */
    public Builder expectNoNewFaults() {
      commit();
      return m_parent.expectNoNewFaults();
    }

    /**
     * Commit this step and set the abort policy.
     *
     * @param b true to abort on a blocking alert
     * @return the routine builder
     */
    public Builder abortOnError(boolean b) {
      commit();
      return m_parent.abortOnError(b);
    }

    /**
     * Commit this step and finish the routine.
     *
     * @return the immutable routine
     */
    public SelfTestRoutine build() {
      commit();
      return m_parent.build();
    }

    private void commit() {
      if (!m_committed) {
        m_committed = true;
        m_parent.addStep(new SelfTestStep(m_name, m_action, m_timeout, m_expectations));
      }
    }
  }
}

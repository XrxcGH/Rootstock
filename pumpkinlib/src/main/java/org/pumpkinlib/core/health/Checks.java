package org.pumpkinlib.core.health;

import edu.wpi.first.units.Units;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Time;
import java.util.function.DoubleSupplier;
import org.pumpkinlib.core.compat.Clock;

/**
 * Ready-made {@link HealthSource}s for the three failures that cost the most matches and are the
 * least often checked.
 *
 * <p>Encoder sanity and stall detection conceptually belong to the mechanism domain — they need a
 * mechanism's two sensors — but they emit through this registry, so they are built here from plain
 * {@link DoubleSupplier}s and work identically for a hand-rolled subsystem.
 *
 * <p>Every check here is <strong>cycle-counted, never wall-clocked</strong>: a duration passed to
 * one of these factories is converted once, at first use, with {@code Clock.cyclesFor(...)}, so a
 * replayed log trips the check on exactly the cycles the real robot did.
 */
public final class Checks {

  private Checks() {}

  /**
   * Two sensors that must agree — the canonical "the CANcoder is on the wrong shaft" detector.
   *
   * <p>This is the check that turns a mystifying week of "the arm is 14 degrees off but only
   * sometimes" into one line on the pit screen. Both suppliers must read the same physical quantity
   * in the same units.
   *
   * @param label the health name and the device prefix, e.g. {@code "Arm/encoders"}
   * @param a the first measurement, in the units of {@code maxDisagreement}
   * @param b the second measurement, in the same units
   * @param maxDisagreement how far apart they may legitimately be — backlash plus sensor noise, not
   *     zero
   * @return a source that reports an ERROR fault while the two disagree by more than the tolerance
   * @throws IllegalArgumentException if any argument is null or {@code label} is blank
   */
  public static HealthSource encoderAgreement(
      String label, DoubleSupplier a, DoubleSupplier b, Angle maxDisagreement) {
    require(label, "label");
    require(a, "a");
    require(b, "b");
    require(maxDisagreement, "maxDisagreement");
    double tol = Math.abs(maxDisagreement.in(Units.Rotations));
    return new HealthSource() {
      @Override
      public String healthName() {
        return label;
      }

      @Override
      public void pollHealth(FaultCollector out) {
        double va = a.getAsDouble();
        double vb = b.getAsDouble();
        double delta = Math.abs(va - vb);
        if (delta > tol) {
          out.error(
              label,
              String.format(
                  "sensors disagree by %.4f rot (a = %.4f, b = %.4f); limit is %.4f rot. "
                      + "Check the encoder is on the right shaft, then the gear ratio in config.",
                  delta, va, vb, tol));
        }
      }
    };
  }

  /**
   * High current plus near-zero velocity, sustained, means something is jammed.
   *
   * <p>Sustained is the operative word: every mechanism draws stall current for a few loops at the
   * start of a move. {@code forTime} is converted to whole cycles once, so this fires on the same
   * cycle in replay as it did on the field.
   *
   * @param label the health name, e.g. {@code "Intake"}
   * @param amps stator current
   * @param velocity mechanism velocity, in any consistent unit
   * @param currentAbove the current at which we start counting
   * @param velocityBelow the velocity magnitude below which the mechanism counts as not moving, in
   *     the units of {@code velocity}
   * @param forTime how long both must hold before this is a fault
   * @return a source that reports an ERROR fault while the stall condition has held for {@code
   *     forTime}
   * @throws IllegalArgumentException if any argument is null or {@code label} is blank
   */
  public static HealthSource stall(
      String label,
      DoubleSupplier amps,
      DoubleSupplier velocity,
      Current currentAbove,
      double velocityBelow,
      Time forTime) {
    require(label, "label");
    require(amps, "amps");
    require(velocity, "velocity");
    require(currentAbove, "currentAbove");
    require(forTime, "forTime");
    double ampsLimit = currentAbove.in(Units.Amps);
    double velLimit = Math.abs(velocityBelow);
    return new SustainedCheck(label, forTime) {
      @Override
      boolean condition() {
        return amps.getAsDouble() > ampsLimit && Math.abs(velocity.getAsDouble()) < velLimit;
      }

      @Override
      void report(FaultCollector out, double heldSeconds) {
        out.error(
            label,
            String.format(
                "stalled: %.1f A (> %.1f A) with velocity %.3f (< %.3f) for %.2f s. "
                    + "Something is jammed - clear it before re-enabling, or the breaker will.",
                amps.getAsDouble(),
                ampsLimit,
                velocity.getAsDouble(),
                velLimit,
                heldSeconds));
      }
    };
  }

  /**
   * Commanded to move but the measurement did not change — the unplugged-encoder detector.
   *
   * <p>A disconnected encoder reads a perfect constant, which every closed loop happily integrates
   * into a full-power command. This is the check that catches it in the pit instead of on the field.
   *
   * @param label the health name, e.g. {@code "Elevator"}
   * @param command the commanded output (duty cycle, volts, whatever); a magnitude above 0.05 counts
   *     as "commanded to move"
   * @param measured the position or velocity that ought to respond
   * @param forTime how long the command must persist with no measured change
   * @return a source that reports an ERROR fault when the condition has held for {@code forTime}
   * @throws IllegalArgumentException if any argument is null or {@code label} is blank
   */
  public static HealthSource notMoving(
      String label, DoubleSupplier command, DoubleSupplier measured, Time forTime) {
    require(label, "label");
    require(command, "command");
    require(measured, "measured");
    require(forTime, "forTime");
    // 0.05 of full output and 1e-4 of measured change: chosen here because the design does not
    // specify them. Below 5% output most mechanisms genuinely do not move; 1e-4 is smaller than any
    // real encoder's resolution, so "no change at all" means exactly that.
    final double kCommandFloor = 0.05;
    final double kMoveEpsilon = 1e-4;
    double[] reference = {Double.NaN};
    return new SustainedCheck(label, forTime) {
      @Override
      boolean condition() {
        boolean commanded = Math.abs(command.getAsDouble()) > kCommandFloor;
        double now = measured.getAsDouble();
        if (!commanded) {
          reference[0] = now;
          return false;
        }
        if (Double.isNaN(reference[0])) {
          reference[0] = now;
        }
        if (Math.abs(now - reference[0]) > kMoveEpsilon) {
          reference[0] = now;
          return false;
        }
        return true;
      }

      @Override
      void report(FaultCollector out, double heldSeconds) {
        out.error(
            label,
            String.format(
                "commanded %.2f for %.2f s but the measurement has not moved off %.5f. "
                    + "Check the encoder cable and the sensor-to-mechanism ratio - a disconnected "
                    + "encoder reads a constant and the closed loop will wind up.",
                command.getAsDouble(), heldSeconds, reference[0]));
      }
    };
  }

  /**
   * Shared base for the two "has held for a while" checks. Package-private and abstract so it cannot
   * leak into the public API (DESIGN.md §8 rule 7).
   */
  private abstract static class SustainedCheck implements HealthSource {
    private final String m_label;
    private final Time m_forTime;

    // Resolved lazily at first poll, for two reasons: Clock.dt() is not final until PumpkinRobot's
    // constructor runs, and the sweep length is not known until every source has registered. A
    // health source is polled once per SWEEP, not once per cycle, so the duration converts to a
    // number of POLLS - which is still cycle-derived and therefore still replay-deterministic.
    private int m_pollsRequired = -1;
    private int m_cyclesPerPoll = 1;
    private int m_held;

    SustainedCheck(String label, Time forTime) {
      m_label = label;
      m_forTime = forTime;
    }

    abstract boolean condition();

    abstract void report(FaultCollector out, double heldSeconds);

    @Override
    public final String healthName() {
      return m_label;
    }

    @Override
    public final void pollHealth(FaultCollector out) {
      if (m_pollsRequired < 0) {
        m_cyclesPerPoll = Math.max(1, SliceScheduler.sweepCycles());
        int cycles = Math.max(1, Clock.cyclesFor(m_forTime));
        m_pollsRequired = Math.max(1, (cycles + m_cyclesPerPoll - 1) / m_cyclesPerPoll);
      }
      if (!condition()) {
        m_held = 0;
        return;
      }
      m_held++;
      if (m_held >= m_pollsRequired) {
        report(out, (double) m_held * m_cyclesPerPoll * Clock.dt());
      }
    }
  }

  private static void require(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("Checks: " + name + " is null.");
    }
    if (value instanceof String s && s.isBlank()) {
      throw new IllegalArgumentException("Checks: " + name + " is blank.");
    }
  }
}

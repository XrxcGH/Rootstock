package org.rootstock.mechanism;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.config.MotionConstraints;
import org.rootstock.control.Gains;
import org.rootstock.control.NeutralMode;
import org.rootstock.example.RobotConfig;
import org.rootstock.hardware.MotorCapabilities;
import org.rootstock.hardware.MotorIO;
import org.rootstock.hardware.MotorInputs;

/**
 * One mechanism failing must degrade <b>that mechanism</b>, never the robot.
 *
 * <p>This is ArchUnit rule 11's runtime half. The rule itself only asserts that {@code
 * Mechanism.periodic()} contains a {@code try/catch(Throwable)} wrapper — a bytecode shape. A
 * wrapper that catches and then rethrows, or that catches and leaves the motor commanded, satisfies
 * the bytecode test and still loses the match. What actually matters is the behaviour asserted here:
 *
 * <ol>
 *   <li>nothing propagates to the scheduler, so the other five subsystems keep running;
 *   <li>the mechanism is commanded <b>neutral</b>, so a half-configured elevator is not left holding
 *       a stale closed-loop goal against gravity;
 *   <li>the failure is <b>named</b> — {@code periodicFailed()} is true and an alert carries the
 *       cause — because a mechanism that silently stops responding is the worst of the three
 *       outcomes for a student trying to diagnose it in a queue line.
 * </ol>
 *
 * <p>The failure mode this prevents is specific and it is the reason the wrapper exists: a single
 * unhandled exception inside one subsystem's {@code periodic()} propagates out of {@code
 * CommandScheduler.run()}, and every subsystem scheduled after it stops being serviced for the rest
 * of the match. The robot does not crash visibly; it goes half-dead, which is much harder to
 * diagnose from the driver station.
 *
 * <p><b>Tagged {@code @Tag("hal")}.</b> {@link Mechanism} constructs {@code RootstockAlert}s, which
 * reach NetworkTables through the WPILib JNI. Without natives WPILib's loader calls {@code
 * System.exit(1)} rather than throwing, killing the worker with no indication of the cause. Run with
 * {@code ./gradlew halTest}.
 */
@Tag("hal")
final class MechanismPeriodicTest {

  /** The exception text asserted below, so a rename cannot make the assertions vacuous. */
  private static final String kBoom = "simulated CAN read failure";

  /**
   * A {@link MotorIO} that throws on {@code updateInputs} and records whether it was told to go
   * neutral afterwards.
   *
   * <p>{@code updateInputs} is the right place to fail: it is the first thing {@code periodic()}
   * does, it is where a real CAN or JNI fault actually surfaces, and failing there means the
   * mechanism never reaches its own {@code onPeriodic()}.
   */
  private static final class ThrowingMotorIO implements MotorIO {
    private final AtomicBoolean m_throwOnUpdate = new AtomicBoolean(true);
    private final AtomicInteger m_neutralCalls = new AtomicInteger();
    private final AtomicBoolean m_neutralAlsoThrows = new AtomicBoolean(false);

    @Override
    public void updateInputs(MotorInputs inputs) {
      if (m_throwOnUpdate.get()) {
        throw new IllegalStateException(kBoom);
      }
    }

    @Override
    public void setNeutral() {
      m_neutralCalls.incrementAndGet();
      if (m_neutralAlsoThrows.get()) {
        throw new IllegalStateException("the seam is gone too");
      }
    }

    @Override
    public void setPositionGoal(double rot, double rps, double arbFf) {}

    @Override
    public void setPositionGoal(double rot, double rps, double arbFf, MotionConstraints override) {}

    @Override
    public void setVelocityGoal(double rps, double rps2, double arbFf) {}

    @Override
    public void setVoltage(double volts) {}

    @Override
    public void setDutyCycle(double fraction) {}

    @Override
    public void applyGains(Gains siGains) {}

    @Override
    public void applyConstraints(MotionConstraints constraints) {}

    @Override
    public void setNeutralMode(NeutralMode mode) {}

    @Override
    public void seedPosition(double outputRotations) {}

    @Override
    public void reapplyFullConfigBlocking() {}

    @Override
    public MotorCapabilities capabilities() {
      return MotorCapabilities.rioOnly();
    }

    @Override
    public String name() {
      return "ThrowingMotorIO";
    }

    @Override
    public String describe() {
      return "a MotorIO that throws on updateInputs, for MechanismPeriodicTest";
    }
  }

  private static SimpleMechanism rollerWith(ThrowingMotorIO io) {
    return new SimpleMechanism(RobotConfig.ROLLER, io);
  }

  @Nested
  @DisplayName("a throwing IO does not escape periodic()")
  final class Containment {

    @Test
    @DisplayName("periodic() swallows the throwable instead of handing it to the scheduler")
    void periodicDoesNotPropagate() {
      SimpleMechanism roller = rollerWith(new ThrowingMotorIO());

      // If this throws, CommandScheduler.run() would have thrown, and every subsystem scheduled
      // after this one stops being serviced for the remainder of the match.
      assertDoesNotThrow(roller::periodic);
    }

    @Test
    @DisplayName("repeated failures stay contained — the wrapper is not a one-shot")
    void repeatedFailuresStayContained() {
      SimpleMechanism roller = rollerWith(new ThrowingMotorIO());

      for (int cycle = 0; cycle < 50; cycle++) {
        assertDoesNotThrow(roller::periodic, "cycle " + cycle + " escaped the wrapper");
      }
    }

    @Test
    @DisplayName("a failure while going neutral is contained too — the last line of defence")
    void neutralFailureIsAlsoContained() {
      ThrowingMotorIO io = new ThrowingMotorIO();
      io.m_neutralAlsoThrows.set(true);
      SimpleMechanism roller = rollerWith(io);

      // Both the read AND the recovery fail. There is nothing more this mechanism can do, and
      // rethrowing here would take the scheduler down — which is exactly the outcome the whole
      // wrapper exists to prevent.
      assertDoesNotThrow(roller::periodic);
    }
  }

  @Nested
  @DisplayName("the mechanism degrades rather than continuing to command")
  final class Degradation {

    @Test
    @DisplayName("the motor is commanded neutral, not left holding a stale goal")
    void motorIsCommandedNeutral() {
      ThrowingMotorIO io = new ThrowingMotorIO();
      SimpleMechanism roller = rollerWith(io);

      roller.periodic();

      assertTrue(
          io.m_neutralCalls.get() >= 1,
          "a mechanism whose periodic() threw was left commanded; an elevator in that state holds "
              + "a stale closed-loop goal against gravity");
    }

    @Test
    @DisplayName("mode drops to NEUTRAL so nothing downstream believes it is still controlled")
    void modeDropsToNeutral() {
      SimpleMechanism roller = rollerWith(new ThrowingMotorIO());

      roller.periodic();

      assertEquals(MechanismMode.NEUTRAL, roller.mode());
    }
  }

  @Nested
  @DisplayName("the failure is named, not silent")
  final class Naming {

    @Test
    @DisplayName("periodicFailed() flips, so health and self-test can see it")
    void failureIsObservable() {
      SimpleMechanism roller = rollerWith(new ThrowingMotorIO());

      assertFalse(roller.periodicFailed(), "a fresh mechanism must not report a failure");
      roller.periodic();
      assertTrue(
          roller.periodicFailed(),
          "the failure was swallowed AND hidden, which is worse than crashing: the mechanism "
              + "stops responding and nothing on the dashboard says why");
    }

    @Test
    @DisplayName("a mechanism that recovers can be cleared, so a transient fault is not permanent")
    void recoveryIsRepresentable() {
      ThrowingMotorIO io = new ThrowingMotorIO();
      SimpleMechanism roller = rollerWith(io);

      roller.periodic();
      assertTrue(roller.periodicFailed());

      // A CAN device that rebooted and came back should not leave the mechanism dead for the rest
      // of the match once the underlying fault is gone.
      io.m_throwOnUpdate.set(false);
      assertDoesNotThrow(roller::periodic);
    }
  }
}

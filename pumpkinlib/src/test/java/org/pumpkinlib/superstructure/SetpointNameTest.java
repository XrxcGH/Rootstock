package org.pumpkinlib.superstructure;

import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Pounds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.ConfigError;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.config.PositionConfig;
import org.pumpkinlib.config.Setpoint;
import org.pumpkinlib.config.Validation;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;

/**
 * A typo'd setpoint name is caught at <b>construction</b> — the moment the {@code public static
 * final Setpoint} handle is initialised — and not at button-press time in the middle of a match.
 *
 * <h2>What "at construction" actually means here, and why it is not a throw</h2>
 *
 * <p>The obvious implementation of "fails at construction" is to throw from {@code
 * config.setpoint("L4 ")}. PumpkinLib deliberately does not, and the reason is worth stating because
 * it is the difference between a diagnostic and an outage: every setpoint handle in the design is a
 * {@code public static final} field on a {@code RobotConfig} class, so a throw there surfaces as
 * {@code ExceptionInInitializerError} out of {@code <clinit>}. The robot never starts, the driver
 * station shows red, and the carefully written message ends up nested three frames deep under JVM
 * class-initialisation noise.
 *
 * <p>So the failure is <i>recorded as a value</i> instead: the lookup returns an <b>unresolved</b>
 * {@link Setpoint} and pushes a fatal {@link ConfigError} into {@link Validation#lookupMisses()} at
 * the instant the field initialiser runs. The robot boots, {@code PumpkinRegistry} prints the error
 * and enters SAFE_MODE, and nothing actuates. That is still construction time — it happens before
 * {@code RobotContainer}'s constructor has finished, let alone before anyone presses a button — and
 * this test pins exactly that: <b>the error exists after construction and before any command was
 * ever built.</b>
 *
 * <p>Native-free by construction. Nothing here builds a {@code Mechanism} or a {@code
 * Superstructure}: both construct {@code PumpkinAlert}s, which eagerly construct WPILib {@code
 * Alert}s, which reach NetworkTables and call {@code System.exit(1)} on a JVM with no JNI natives.
 * The superstructure-level half of the same check lives in the {@code @Tag("hal")} nested class at
 * the bottom.
 */
final class SetpointNameTest {

  /** Unique to this class so a lookup miss recorded elsewhere in the JVM cannot be mistaken for ours. */
  private static final String kName = "SetpointNameTestElevator";

  /** 52.5 in x 0.0254 m/in = 1.33350 m. Derived, not copied. */
  private static final double kL4Meters = 52.5 * 0.0254;

  private PositionConfig m_config;

  @BeforeEach
  void freshValidationState() {
    Validation.resetForTest();
    m_config =
        PositionConfig.linear(kName)
            .motor(MotorSpec.sim())
            .reduction(Reduction.of(12.0))
            // 22 teeth x 0.25 in pitch x 2 cascade stages = 11 in = 0.27940 m per output rotation.
            .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))
            .softLimits(Inches.of(0.0), Inches.of(55.0))
            .gains(Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.0).withKa(0.06).withKg(0.33))
            .constraints(MotionConstraints.of(1.6, 6.0))
            .setpoint("STOW", Inches.of(0.0))
            .setpoint("L2", Inches.of(20.5))
            .setpoint("L3", Inches.of(37.5))
            .setpoint("L4", Inches.of(52.5))
            .sim(Pounds.of(24.0), Inches.of(0.0))
            .build();
  }

  @AfterEach
  void clearValidationState() {
    Validation.resetForTest();
  }

  /** Only the misses this test caused, so a neighbouring test class cannot make it pass or fail. */
  private List<ConfigError> ourMisses() {
    List<ConfigError> mine = new ArrayList<>();
    for (ConfigError error : Validation.lookupMisses()) {
      if (kName.equals(error.owner())) {
        mine.add(error);
      }
    }
    return mine;
  }

  @Nested
  @DisplayName("a name that exists")
  final class Resolves {

    @Test
    void returnsTheDeclaredValueAndRecordsNothing() {
      Setpoint l4 = m_config.setpoint("L4");
      assertTrue(l4.isResolved());
      assertEquals(kL4Meters, l4.valueUser(), 1e-12, "user units for a linear axis are metres");
      assertEquals(1.33350, l4.valueUser(), 1e-9, "52.5 in = 1.3335 m");
      assertEquals(kName, l4.mechanism());
      assertTrue(l4.problems().isEmpty());
      assertTrue(ourMisses().isEmpty(), "a good name must not pollute the lookup-miss list");
    }

    /** The handle is the same object the config holds, so identity comparisons in a state map work. */
    @Test
    void theHandleIsTheDeclaredSetpointItself() {
      assertSame(m_config.setpoint("L2"), m_config.setpoint("L2"));
      assertEquals(List.of("STOW", "L2", "L3", "L4"), namesOf(m_config));
    }

    private List<String> namesOf(PositionConfig config) {
      List<String> out = new ArrayList<>();
      for (Setpoint s : config.setpoints()) {
        out.add(s.name());
      }
      return out;
    }
  }

  @Nested
  @DisplayName("a typo — the 11pm trailing space")
  final class TypoAtConstruction {

    /**
     * The whole point of the milestone item: constructing the handle is enough to surface the error.
     * No command is built, no button is pressed, no loop runs.
     */
    @Test
    void constructingTheHandleIsEnoughToRecordAFatalConfigError() {
      assertTrue(ourMisses().isEmpty(), "precondition: nothing recorded before the lookup");

      Setpoint typo = m_config.setpoint("L4 ");

      assertNotNull(typo, "never null: a null here is an NPE at 11pm instead of a sentence");
      assertFalse(typo.isResolved());
      List<ConfigError> misses = ourMisses();
      assertEquals(1, misses.size(), "exactly one error, recorded by the lookup itself");
      assertTrue(misses.get(0).isFatal(), "a goal nothing can reach is fatal, not a warning");
    }

    /** The error names the mechanism, the call, and every name that DOES exist. */
    @Test
    void theErrorNamesTheThingTheValueAndTheExpectedRange() {
      m_config.setpoint("L4 ");
      ConfigError error = ourMisses().get(0);

      assertEquals(kName, error.owner());
      assertTrue(
          error.field().contains("L4 "),
          "the field must echo the exact string that was asked for: " + error.field());
      String expected = error.expected();
      assertTrue(expected.contains("\"L4\""), "expected must list the real names: " + expected);
      assertTrue(expected.contains("\"STOW\""), expected);
      assertTrue(expected.contains("\"L2\""), expected);
      assertTrue(expected.contains("\"L3\""), expected);
    }

    /**
     * The message calls out whitespace explicitly, because "L4" and "L4 " render identically in a
     * console and that is what turns a two-second fix into twenty minutes.
     */
    @Test
    void whitespaceIsCalledOutByName() {
      Setpoint typo = m_config.setpoint("L4 ");
      String problem = String.join(" ", typo.problems());
      assertTrue(
          problem.toLowerCase(java.util.Locale.ROOT).contains("whitespace"),
          "the trailing-space case must say so out loud: " + problem);
    }

    /** Capitalisation is not normalised either: "l4" is a different name, and it is reported. */
    @Test
    void capitalisationIsNotForgiven() {
      Setpoint typo = m_config.setpoint("l4");
      assertFalse(typo.isResolved());
      assertEquals(1, ourMisses().size());
      assertFalse(
          String.join(" ", typo.problems()).toLowerCase(java.util.Locale.ROOT).contains("whitespace"),
          "no whitespace note when whitespace is not the problem");
    }

    /** An unresolved setpoint carries NaN, so a value that leaked through would be loud, not subtle. */
    @Test
    void anUnresolvedValueIsNaNRatherThanZero() {
      Setpoint typo = m_config.setpoint("L4 ");
      assertTrue(Double.isNaN(typo.valueUser()), "zero would command the elevator to the floor");
      assertTrue(typo.describe().contains("UNRESOLVED"));
    }

    /** Every miss is recorded, so a config with three typos prints three lines and not just the first. */
    @Test
    void everyMissIsRecorded() {
      m_config.setpoint("L4 ");
      m_config.setpoint("l3");
      m_config.setpoint("STOWED");
      assertEquals(3, ourMisses().size());
    }
  }

  @Nested
  @DisplayName("the goal that references it")
  final class GoalReferences {

    /**
     * {@code AxisGoal.of(handle)} is the typed form the design's state machine uses. A handle built
     * from a typo answers {@link AxisGoal#referencesUnresolvedHandle()} true, which is what the
     * superstructure's construction-time report reads.
     */
    @Test
    void aGoalBuiltFromABrokenHandleAdmitsIt() {
      AxisGoal good = AxisGoal.of(m_config.setpoint("L4"));
      AxisGoal broken = AxisGoal.of(m_config.setpoint("L4 "));

      assertFalse(good.referencesUnresolvedHandle());
      assertTrue(broken.referencesUnresolvedHandle());
      assertEquals("L4", good.setpointName());
      assertEquals("L4 ", broken.setpointName(), "the report must quote what was written");
    }

    /**
     * And at runtime it holds rather than moving. This is the last line of defence, not the first:
     * by the time a goal is applied the config error has already been printed and SAFE_MODE is on.
     */
    @Test
    void applyingABrokenGoalHoldsInsteadOfMovingToNaN() {
      RecordingReceiver receiver = new RecordingReceiver(m_config.setpoints());

      AxisGoal.of(m_config.setpoint("L4")).applyTo(receiver);
      assertEquals(List.of("position " + kL4Meters), receiver.m_calls);

      receiver.m_calls.clear();
      AxisGoal.of(m_config.setpoint("L4 ")).applyTo(receiver);
      assertEquals(List.of("hold"), receiver.m_calls, "hold, never position NaN");
    }

    /**
     * The by-name form has no compiler check at all, so it must fail the same way. A name that is
     * not in the receiver's declared list holds, and names no target for the planner.
     */
    @Test
    void aByNameGoalWithNoMatchHoldsAndNamesNoTarget() {
      RecordingReceiver receiver = new RecordingReceiver(m_config.setpoints());

      AxisGoal.named("L4").applyTo(receiver);
      assertEquals(List.of("position " + kL4Meters), receiver.m_calls);
      assertEquals(kL4Meters, AxisGoal.named("L4").targetUser(receiver).getAsDouble(), 1e-12);

      receiver.m_calls.clear();
      AxisGoal.named("L5").applyTo(receiver);
      assertEquals(List.of("hold"), receiver.m_calls);
      assertTrue(AxisGoal.named("L5").targetUser(receiver).isEmpty());
    }
  }

  /**
   * A {@link GoalReceiver} that records what it was asked to do.
   *
   * <p>Hand-written rather than adapted from a {@code Mechanism} because {@code
   * GoalReceiver.forMechanism} needs a {@code Mechanism}, and a {@code Mechanism} needs the HAL.
   * That the interface can be implemented in fifteen lines is itself the property being relied on.
   */
  private static final class RecordingReceiver implements GoalReceiver {

    private final List<String> m_calls = new ArrayList<>();
    private final List<Setpoint> m_setpoints;

    RecordingReceiver(List<Setpoint> setpoints) {
      m_setpoints = setpoints;
    }

    @Override
    public String axisName() {
      return kName;
    }

    @Override
    public void applyPosition(double userUnits) {
      m_calls.add("position " + userUnits);
    }

    @Override
    public void applyVelocity(double userPerSecond) {
      m_calls.add("velocity " + userPerSecond);
    }

    @Override
    public void applyPercent(double dutyCycle) {
      m_calls.add("percent " + dutyCycle);
    }

    @Override
    public void applyNeutral() {
      m_calls.add("neutral");
    }

    @Override
    public void applyHold() {
      m_calls.add("hold");
    }

    @Override
    public boolean atGoal() {
      return true;
    }

    @Override
    public double measuredUser() {
      return 0.0;
    }

    @Override
    public List<Setpoint> declaredSetpoints() {
      return m_setpoints;
    }
  }
}

package org.pumpkinlib.hardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.hardware.sim.SimMotorIO;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The seam is a <b>goal</b>, not a voltage — asserted structurally and behaviourally.
 *
 * <p>The naive vendor-neutral motor interface is {@code getPosition()} plus {@code
 * setVoltage(volts)}, with a roboRIO {@code PIDController} in between. {@link MotorIO}'s whole claim
 * is that it is <em>not</em> that interface. A claim made only in javadoc survives exactly until the
 * first backend author adds a convenience getter, at which point mechanism code starts reading a
 * position off the seam and computing volts, and every on-motor profile, setpoint latch and fused
 * encoder in the library becomes unreachable.
 *
 * <p>So this class pins four properties:
 *
 * <ol>
 *   <li><b>Structural.</b> The seam exposes no measurement getter at all — no method returns a
 *       {@code double}. "Read a position, run a PID, write a voltage" is not <em>expressible</em>
 *       against {@code MotorIO}, which is a stronger statement than "is discouraged".
 *   <li><b>Structural.</b> The closed-loop surface is goal-shaped, and every position goal carries an
 *       arbitrary-feedforward term as its last parameter.
 *   <li><b>Behavioural.</b> That feedforward term is really added to the commanded voltage, exactly,
 *       volt for volt — the parameter is not decorative.
 *   <li><b>Behavioural.</b> A goal <em>latches</em>. A voltage-shaped seam cannot hold a position
 *       when nobody re-commands it; a goal-shaped one must.
 * </ol>
 */
final class MotorIOSeamTest {

  /**
   * A 9:1 gearbox onto a 22-tooth #25 sprocket, two-stage cascade — the 9143-A elevator. Travel per
   * output rotation is {@code 22 x 0.25 in x 2 = 11.0 in = 0.2794 m}, derived by hand rather than
   * copied.
   */
  private static MechanismUnits elevatorUnits() {
    return new MechanismUnits(
        Reduction.of(9.0), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  /** Gains that are tuned enough for the roboRIO loop to actually command a voltage. */
  private static ControlConfig tunedControl() {
    return ControlConfig.builder()
        .gains(new Gains(80.0, 0.0, 2.0, 0.0, 12.0, 0.6, 0.0))
        .constraints(MotionConstraints.of(1.0, 2.0))
        .build();
  }

  private static SimMotorIO newSim() {
    return new SimMotorIO(
        MotorSpec.sim(), elevatorUnits(), tunedControl(), MechanismKind.POSITION);
  }

  @BeforeEach
  void deterministicClock() {
    Clock.resetForTest();
    Clock.setSource(() -> 0.0);
  }

  @AfterEach
  void restoreClock() {
    Clock.resetForTest();
  }

  @Nested
  @DisplayName("structure: the seam is not getPosition() + setVoltage()")
  final class Structure {

    /**
     * No method on {@link MotorIO} returns a {@code double} or a {@code float}. Measurements arrive
     * exclusively through {@link MotorIO#updateInputs(MotorInputs)}, so a caller physically cannot
     * write the roboRIO-PID-over-a-voltage loop the design rejects.
     */
    @Test
    void theSeamExposesNoMeasurementGetter() {
      List<String> offenders = new ArrayList<>();
      for (Method m : MotorIO.class.getDeclaredMethods()) {
        if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
          continue;
        }
        Class<?> r = m.getReturnType();
        if (r == double.class || r == float.class || r == double[].class) {
          offenders.add(m.getName() + " : " + r.getSimpleName());
        }
      }
      assertTrue(
          offenders.isEmpty(),
          "MotorIO must expose no numeric getter — measurements arrive only through "
              + "updateInputs(MotorInputs). A getter here re-enables the getPosition()+setVoltage() "
              + "shape the seam exists to prevent. Found: "
              + offenders);
    }

    /** The closed-loop surface a mechanism drives is entirely goal-shaped. */
    @Test
    void theClosedLoopSurfaceIsGoalShaped() {
      assertDoesNotThrow(
          () ->
              MotorIO.class.getMethod(
                  "setPositionGoal", double.class, double.class, double.class),
          "setPositionGoal(outputRotations, outputRps, arbFeedforwardVolts) is the seam");
      assertDoesNotThrow(
          () ->
              MotorIO.class.getMethod(
                  "setPositionGoal",
                  double.class,
                  double.class,
                  double.class,
                  MotionConstraints.class),
          "the per-request constraint override is part of the seam, not an extension");
      assertDoesNotThrow(
          () ->
              MotorIO.class.getMethod(
                  "setVelocityGoal", double.class, double.class, double.class),
          "setVelocityGoal(outputRps, outputRps2, arbFeedforwardVolts) is the seam");
    }

    /**
     * {@code setVoltage} exists — it is the deliberate open-loop escape hatch — but it is not part of
     * the closed-loop path, and there is no {@code setVoltage}-shaped method that takes a position.
     */
    @Test
    void setVoltageIsAnOpenLoopEscapeHatchAndNothingMore() {
      assertDoesNotThrow(() -> MotorIO.class.getMethod("setVoltage", double.class));
      for (Method m : MotorIO.class.getDeclaredMethods()) {
        String n = m.getName();
        assertFalse(
            n.toLowerCase(java.util.Locale.ROOT).contains("volt") && m.getParameterCount() > 1,
            "a multi-argument volt-shaped method on the seam would be a position-plus-voltage "
                + "control path, which is the shape MotorIO exists to replace. Found: "
                + m);
      }
    }

    /**
     * The third parameter of the position goal is the arbitrary feedforward, in VOLTS — the term the
     * gravity-compensated and field-locked paths ride on. Pinned by arity and type so a re-ordering
     * of the signature is a compile-time break here rather than a silent behaviour change.
     */
    @Test
    void thePositionGoalCarriesAnArbitraryFeedforwardTerm() throws Exception {
      Method three =
          MotorIO.class.getMethod("setPositionGoal", double.class, double.class, double.class);
      Method four =
          MotorIO.class.getMethod(
              "setPositionGoal",
              double.class,
              double.class,
              double.class,
              MotionConstraints.class);

      assertEquals(3, three.getParameterCount());
      assertEquals(void.class, three.getReturnType());

      // The constraint-override overload must be the SAME goal plus a constraint, not a
      // differently-ordered signature: a caller that swapped the two would silently pass a
      // feedforward as a velocity.
      for (int i = 0; i < 3; i++) {
        assertEquals(
            three.getParameterTypes()[i],
            four.getParameterTypes()[i],
            "parameter " + i + " must have the same meaning on both position-goal overloads");
      }
      assertEquals(
          MotionConstraints.class,
          four.getParameterTypes()[3],
          "the override is appended after the volt term, never inserted before it");
    }

    /**
     * Capabilities are <em>asked</em>, not discovered by failure — and they are constant for the life
     * of the IO, so a caller may cache the answer at construction.
     */
    @Test
    void capabilitiesAreAskableAndConstant() {
      MotorIO io = newSim();
      MotorCapabilities first = io.capabilities();
      assertSame(first, io.capabilities(), "capabilities() must be constant for the life of the IO");
      assertTrue(first.supports(org.pumpkinlib.control.ControlLocation.RIO_FULL));
    }
  }

  @Nested
  @DisplayName("behaviour: the goal really carries what it promises")
  final class Behaviour {

    /**
     * The arbitrary feedforward is added to the commanded voltage <b>volt for volt</b>.
     *
     * <p>Two identical plants, identical goal, identical step — the only difference is the third
     * argument. The applied-voltage delta must be exactly that argument. A backend that accepted the
     * term and dropped it (the {@code TurretIONeo} defect the design names) fails here.
     */
    @Test
    void theArbitraryFeedforwardReachesTheMotorExactly() {
      double arbVolts = 1.25;

      SimMotorIO withoutFf = newSim();
      SimMotorIO withFf = newSim();
      MotorInputs a = new MotorInputs();
      MotorInputs b = new MotorInputs();

      withoutFf.setPositionGoal(0.05, 0.0, 0.0);
      withFf.setPositionGoal(0.05, 0.0, arbVolts);
      withoutFf.updateInputs(a);
      withFf.updateInputs(b);

      // Both are well inside the +/-12 V clamp on the first step, so the difference is the term.
      assertTrue(Math.abs(a.appliedVolts) < 10.0, "guard: the baseline must not be clamped");
      assertTrue(Math.abs(b.appliedVolts) < 12.0, "guard: the trimmed command must not be clamped");
      assertEquals(
          arbVolts,
          b.appliedVolts - a.appliedVolts,
          1e-9,
          "the arbitrary feedforward must be added to the command, not dropped on the floor");
    }

    /**
     * The goal-velocity term is delivered too — a non-zero value changes the command. This is the
     * field-locked turret's counter-rotation, and {@link VelocityCarrier} promises every carrier
     * except {@link VelocityCarrier#UNSUPPORTED} delivers it.
     */
    @Test
    void theGoalVelocityTermIsDeliveredRatherThanDropped() {
      double goalRot = 0.5;
      double goalRps = 0.30;
      double kV = 12.0;
      double kP = 80.0;

      SimMotorIO still = newSim();
      SimMotorIO moving = newSim();
      MotorInputs a = new MotorInputs();
      MotorInputs b = new MotorInputs();

      still.setPositionGoal(goalRot, 0.0, 0.0);
      moving.setPositionGoal(goalRot, goalRps, 0.0);
      for (int i = 0; i < 600; i++) {
        still.updateInputs(a);
        moving.updateInputs(b);
      }

      assertNotEquals(
          a.positionRot,
          b.positionRot,
          "a non-zero goal velocity must change the command. The carrier this backend declares is "
              + still.capabilities().positionGoalVelocity()
              + ", and every carrier except UNSUPPORTED delivers the term.");
      assertTrue(
          still.capabilities().positionGoalVelocity().delivers(),
          "no shipped backend may report UNSUPPORTED");

      // Hand-derived. At rest against a frictionless plant the command is zero, so the profile's
      // steady kV feedforward has to be cancelled by proportional feedback:
      //     kV_si * v_si  ==  kP_si * error_si
      // and since v_si = goalRps * U and error_si = errorRot * U, the U cancels outright:
      //     errorRot = kV * goalRps / kP = 12.0 * 0.30 / 80.0 = 0.045 output rotations.
      double expectedOffsetRot = kV * goalRps / kP;
      assertEquals(
          0.045, expectedOffsetRot, 1e-12, "the hand derivation, restated as a literal");
      assertEquals(
          expectedOffsetRot,
          b.positionRot - a.positionRot,
          expectedOffsetRot * 0.10,
          "the goal-velocity term must arrive as kV * goalRps volts, which parks the mechanism "
              + "kV*goalRps/kP rotations past the goal. A backend that dropped the term would show "
              + "no offset at all.");
    }

    /**
     * <b>The setpoint latches.</b> Command the goal once, then step 150 loops calling nothing but
     * {@code updateInputs}. The mechanism must arrive and stay.
     *
     * <p>This is the property a voltage-shaped seam cannot have: a voltage commanded once and never
     * refreshed is a mechanism that coasts. It is also the "original hold-position bug" a surveyed
     * team keeps a dedicated regression test for.
     */
    @Test
    void aGoalLatchesWithoutBeingRecommanded() {
      SimMotorIO io = newSim();
      MotorInputs inputs = new MotorInputs();
      double goalRot = 1.5;

      io.setPositionGoal(goalRot, 0.0, 0.0);
      for (int i = 0; i < 400; i++) {
        io.updateInputs(inputs);
      }
      assertEquals(
          goalRot,
          inputs.positionRot,
          0.02,
          "the goal must be reached with no further setPositionGoal call");

      for (int i = 0; i < 200; i++) {
        io.updateInputs(inputs);
      }
      assertEquals(
          goalRot,
          inputs.positionRot,
          0.02,
          "and HELD. A dropped setpoint is a mechanism that falls the first time a loop overruns.");
    }

    /**
     * {@link MotorIO#setNeutral()} is not {@code setVoltage(0)}: it releases the latch. After it, the
     * mechanism must stop being driven toward the abandoned goal.
     */
    @Test
    void setNeutralReleasesTheLatchedGoal() {
      SimMotorIO io = newSim();
      MotorInputs inputs = new MotorInputs();

      io.setPositionGoal(1.5, 0.0, 0.0);
      io.updateInputs(inputs);
      assertNotEquals(0.0, inputs.appliedVolts, "guard: the goal is being driven");

      io.setNeutral();
      io.updateInputs(inputs);
      assertEquals(
          0.0,
          inputs.appliedVolts,
          1e-12,
          "setNeutral must release the latch, not merely command zero volts on top of it");
    }
  }
}

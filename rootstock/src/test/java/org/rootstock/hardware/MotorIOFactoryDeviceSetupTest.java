package org.rootstock.hardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rootstock.config.ControlConfig;
import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.Follower;
import org.rootstock.config.MechanismKind;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorModel;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionLimits;
import org.rootstock.core.spi.RobotMode;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;
import org.rootstock.units.MechanismUnits;

/**
 * <b>Everything the config declares reaches the backend.</b>
 *
 * <p>This exists because for three milestones it did not. {@code Backend.create} took a bare {@code
 * MotorSpec}, so a declared soft limit, a declared hard stop, a declared CANcoder, a declared
 * current limit and every declared follower stopped at the factory. The flagship two-Kraken elevator
 * in the README ran on one motor, with the motor model's default current limit rather than the 70/40
 * A it asked for, and with no firmware travel stop -- and the boot dump reported it as configured.
 *
 * <p>The regression is silent by construction: nothing throws, nothing logs, and the mechanism moves
 * in the direction you ask. So the guard has to be a test that reads what a backend was actually
 * handed. That is what the fake below is for.
 *
 * <p>No HAL, no DriverStation and no alerts: a backend is registered, so the "no adapter installed"
 * path that raises one is never reached.
 */
final class MotorIOFactoryDeviceSetupTest {

  /** A 9:1 gearbox onto a 22-tooth #25 sprocket, two-stage cascade. 0.2794 m per output rotation. */
  private static MechanismUnits elevatorUnits() {
    return new MechanismUnits(Reduction.of(9.0), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  /** Records what the seam handed it, and nothing else. */
  private static final class RecordingBackend implements MotorIOFactory.Backend {

    private MotorIOFactory.DeviceSetup m_seen;

    @Override
    public boolean supports(MotorSpec spec) {
      return spec instanceof MotorSpec.TalonFXSpec;
    }

    @Override
    public MotorIO create(
        MotorIOFactory.DeviceSetup setup,
        MechanismUnits units,
        ControlConfig control,
        MechanismKind kind) {
      m_seen = setup;
      return new NoOpMotorIO(setup.leader().name(), "recording backend");
    }

    @Override
    public String vendor() {
      return "recording";
    }
  }

  private RecordingBackend m_backend;

  @BeforeEach
  void install() {
    MotorIOFactory.resetForTest();
    m_backend = new RecordingBackend();
    MotorIOFactory.register(m_backend);
  }

  @AfterEach
  void remove() {
    MotorIOFactory.resetForTest();
  }

  @Test
  @DisplayName("the follower, the current limits, the travel range and the CANcoder all cross")
  void everythingDeclaredCrossesTheSeam() {
    MotorGroup motors =
        MotorGroup.leader(MotorSpec.talonFX(20, "rio"))
            .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED);
    CurrentLimits current = CurrentLimits.of(Units.Amps.of(70.0), Units.Amps.of(40.0));
    PositionLimits limits =
        PositionLimits.of(Units.Inches.of(0.0), Units.Inches.of(55.0), current);
    FeedbackSpec feedback =
        new FeedbackSpec.FusedCancoder(30, "rio", Units.Degrees.of(0.0), 9.0, 1.0);

    MotorIOFactory.create(
        new MotorIOFactory.DeviceSetup(motors, current, limits, feedback),
        elevatorUnits(),
        ControlConfig.defaults(),
        MechanismKind.POSITION,
        RobotMode.REAL);

    MotorIOFactory.DeviceSetup seen = m_backend.m_seen;
    assertNotNull(seen, "the backend was never called");
    assertEquals(
        1,
        seen.motors().followers().size(),
        "the declared follower must reach the backend, or the second motor is never commanded");
    assertEquals(
        70.0,
        seen.current().statorAmps(),
        0.0,
        "the declared stator limit must reach the backend, not the motor model's default");
    assertSame(limits, seen.limits(), "the travel range and hard stops must reach the backend");
    assertSame(feedback, seen.feedback(), "the CANcoder plumbing must reach the backend");
  }

  @Test
  @DisplayName("a mechanism with no travel range says so, rather than inventing one")
  void noTravelRangeIsRepresentable() {
    MotorGroup motors = MotorGroup.leader(MotorSpec.talonFX(22, "rio"));
    CurrentLimits current = CurrentLimits.of(Units.Amps.of(80.0), Units.Amps.of(40.0));

    MotorIOFactory.create(
        new MotorIOFactory.DeviceSetup(motors, current, null, null),
        elevatorUnits(),
        ControlConfig.defaults(),
        MechanismKind.VELOCITY,
        RobotMode.REAL);

    MotorIOFactory.DeviceSetup seen = m_backend.m_seen;
    assertNotNull(seen, "the backend was never called");
    assertNull(seen.limits(), "a flywheel has no travel range and must not be given a fake one");
    assertEquals(
        80.0,
        seen.current().statorAmps(),
        0.0,
        "a velocity mechanism's current limits are declared outside PositionLimits and must still"
            + " reach the device");
  }

  @Test
  @DisplayName("the two defaults that have a right answer are substituted, and only those")
  void nullCurrentAndNullFeedbackGetDocumentedDefaults() {
    MotorIOFactory.DeviceSetup setup =
        new MotorIOFactory.DeviceSetup(
            MotorGroup.leader(MotorSpec.talonFX(23, "rio")), null, null, null);

    assertEquals(
        CurrentLimits.defaultsFor(MotorModel.KRAKEN_X60).statorAmps(),
        setup.current().statorAmps(),
        0.0,
        "a motor with no current limit at all is a fire risk, so the model defaults stand in");
    assertInstanceOf(
        FeedbackSpec.RotorOnly.class,
        setup.feedback(),
        "no declared absolute encoder means rotor only, which is a real answer");
    assertNull(setup.limits(), "no travel range is NOT defaulted: there is no honest default");
  }

  @Test
  @DisplayName("the bare-spec form is explicit about carrying nothing else")
  void ofASpecCarriesOneMotorAndNothingElse() {
    MotorIOFactory.DeviceSetup setup =
        MotorIOFactory.DeviceSetup.of(MotorSpec.talonFX(24, "rio"));

    assertTrue(setup.motors().followers().isEmpty(), "one motor means no followers");
    assertNull(setup.limits(), "a bare spec carries no travel range");
    assertEquals(false, setup.hasTravelRange(), "and says so");
  }
}

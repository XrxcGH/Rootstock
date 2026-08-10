package org.pumpkinlib.hardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.control.ControlLocation;

/**
 * A caller <b>asks</b> what a backend supports, rather than discovering it by failure.
 *
 * <p>Two properties are load-bearing here and both are easy to break silently.
 *
 * <p><b>The transposition.</b> {@link MotorCapabilities} is a record of fourteen positional
 * components, thirteen of them {@code boolean}. Two swapped arguments in a backend's constructor call
 * compiles, runs, and is wrong for a season — a mechanism routed to an on-board profile that is not
 * there, or paying CAN bandwidth for a torque-current signal the device does not measure. {@link
 * MotorCapabilities#builder()} exists to make that unrepresentable, and {@link
 * BuilderIsNotTransposed} proves the builder actually wires each named setter to the component of the
 * same name — reflectively, so a component added later is covered without anybody remembering.
 *
 * <p><b>The downgrade.</b> {@link MotorCapabilities#downgrade} is what turns "this team asked for an
 * on-motor profile on a PWM speed controller" into a printed notice at construction instead of a
 * mechanism that does not move. It must step down <em>one rung at a time</em>: a jump straight to
 * {@code RIO_FULL} from a device that does have a position loop throws away the loop for no reason.
 */
final class MotorCapabilitiesTest {

  @Nested
  @DisplayName("pessimistic defaults")
  final class Defaults {

    /**
     * A fresh builder claims nothing. A forgotten {@code .onBoardProfile(true)} costs latency and
     * prints a downgrade; a forgotten {@code false} would route a goal into a loop that is not there.
     */
    @Test
    void aFreshBuilderClaimsNothing() {
      MotorCapabilities none = MotorCapabilities.builder().build();
      for (RecordComponent c : MotorCapabilities.class.getRecordComponents()) {
        if (c.getType() == boolean.class) {
          assertFalse(readBoolean(none, c), c.getName() + " must default to false");
        }
      }
      assertEquals(VelocityCarrier.UNSUPPORTED, none.positionGoalVelocity());
      assertFalse(none.hasDeviceLoop());
    }

    /**
     * A null carrier is read as {@link VelocityCarrier#UNSUPPORTED} rather than throwing: null means
     * "the backend author forgot to say", and the safe reading of that is the pessimistic one.
     */
    @Test
    void aNullCarrierBecomesUnsupportedRatherThanNull() {
      MotorCapabilities viaBuilder =
          MotorCapabilities.builder().positionGoalVelocity(null).build();
      assertEquals(VelocityCarrier.UNSUPPORTED, viaBuilder.positionGoalVelocity());

      MotorCapabilities viaCanonical =
          new MotorCapabilities(
              false, false, false, false, false, false, false, null, false, false, false, false,
              false, false);
      assertEquals(VelocityCarrier.UNSUPPORTED, viaCanonical.positionGoalVelocity());
    }

    /**
     * {@code rioOnly()} is "nothing on the device" — but the goal velocity is still <em>delivered</em>,
     * as a roboRIO voltage trim, because the seam's promise is delivery and not a mechanism.
     */
    @Test
    void rioOnlyDeliversTheGoalVelocityAsAVoltageTrim() {
      MotorCapabilities rio = MotorCapabilities.rioOnly();
      assertEquals(VelocityCarrier.RIO_VOLTAGE_TRIM, rio.positionGoalVelocity());
      assertTrue(rio.positionGoalVelocity().delivers(), "RIO_VOLTAGE_TRIM still delivers the term");
      assertFalse(
          rio.positionGoalVelocity().carriedByBackend(),
          "but the mechanism folds it in, not the backend");
      for (RecordComponent c : MotorCapabilities.class.getRecordComponents()) {
        if (c.getType() == boolean.class) {
          assertFalse(readBoolean(rio, c), "rioOnly claims nothing on the device: " + c.getName());
        }
      }
    }
  }

  @Nested
  @DisplayName("the builder is not transposed")
  final class BuilderIsNotTransposed {

    /**
     * For every boolean component, setting <em>only</em> the same-named builder method to true makes
     * <em>only</em> that component true. Any two components wired to each other's slot fails here.
     */
    @Test
    void eachSetterWritesTheComponentOfTheSameName() throws Exception {
      List<RecordComponent> booleans = new ArrayList<>();
      for (RecordComponent c : MotorCapabilities.class.getRecordComponents()) {
        if (c.getType() == boolean.class) {
          booleans.add(c);
        }
      }
      assertEquals(
          13,
          booleans.size(),
          "MotorCapabilities has thirteen boolean components and one carrier; if that changed, "
              + "this test and every backend's builder call need re-reading");

      for (RecordComponent target : booleans) {
        Method setter = MotorCapabilities.Builder.class.getMethod(target.getName(), boolean.class);
        MotorCapabilities.Builder builder = MotorCapabilities.builder();
        setter.invoke(builder, true);
        MotorCapabilities built = builder.build();

        for (RecordComponent other : booleans) {
          boolean expected = other.getName().equals(target.getName());
          assertEquals(
              expected,
              readBoolean(built, other),
              "builder."
                  + target.getName()
                  + "(true) must set exactly that component. It also changed "
                  + other.getName()
                  + " — that is a transposition, and it survives a season.");
        }
      }
    }

    /** The carrier setter is wired too, and to the carrier component rather than a boolean. */
    @Test
    void theCarrierSetterWritesTheCarrier() {
      for (VelocityCarrier carrier : VelocityCarrier.values()) {
        assertEquals(
            carrier,
            MotorCapabilities.builder().positionGoalVelocity(carrier).build().positionGoalVelocity());
      }
    }
  }

  @Nested
  @DisplayName("supports(): asked, not discovered by failure")
  final class Supports {

    /** {@code RIO_FULL} is always achievable — it needs nothing from the device. */
    @Test
    void rioFullIsAlwaysSupported() {
      assertTrue(MotorCapabilities.builder().build().supports(ControlLocation.RIO_FULL));
      assertTrue(MotorCapabilities.rioOnly().supports(ControlLocation.RIO_FULL));
    }

    /** A device loop alone is enough for the two locations that only need a position loop. */
    @Test
    void aPositionLoopAloneSupportsTheDirectAndRioProfiledLocations() {
      MotorCapabilities loopOnly = MotorCapabilities.builder().onBoardPositionLoop(true).build();
      assertTrue(loopOnly.supports(ControlLocation.ON_MOTOR_DIRECT));
      assertTrue(loopOnly.supports(ControlLocation.RIO_PROFILE_MOTOR_LOOP));
      assertFalse(
          loopOnly.supports(ControlLocation.ON_MOTOR_PROFILED),
          "a device loop with no device profile cannot run ON_MOTOR_PROFILED");
      assertTrue(loopOnly.hasDeviceLoop());
    }

    /** An on-board profile with no position loop is not enough — both halves are required. */
    @Test
    void aProfileWithoutAPositionLoopIsNotEnough() {
      MotorCapabilities profileOnly = MotorCapabilities.builder().onBoardProfile(true).build();
      assertFalse(profileOnly.supports(ControlLocation.ON_MOTOR_PROFILED));
      assertFalse(profileOnly.supports(ControlLocation.ON_MOTOR_DIRECT));
      assertFalse(profileOnly.hasDeviceLoop());
    }

    @Test
    void bothHalvesTogetherSupportEverything() {
      MotorCapabilities full =
          MotorCapabilities.builder().onBoardPositionLoop(true).onBoardProfile(true).build();
      for (ControlLocation location : ControlLocation.values()) {
        assertTrue(full.supports(location), location + " must be supported by a full device");
      }
    }

    /** A null request is not a crash and is not silently "yes". */
    @Test
    void aNullLocationIsNotSupported() {
      assertFalse(MotorCapabilities.rioOnly().supports(null));
    }
  }

  @Nested
  @DisplayName("downgrade(): one rung at a time, never silent")
  final class Downgrade {

    /** Nothing is lost when the request is achievable. */
    @Test
    void anAchievableRequestIsReturnedUnchanged() {
      MotorCapabilities full =
          MotorCapabilities.builder().onBoardPositionLoop(true).onBoardProfile(true).build();
      for (ControlLocation location : ControlLocation.values()) {
        assertEquals(location, full.downgrade(location));
      }
    }

    /**
     * A device with a loop but no profile steps <b>one rung</b>: profiled becomes direct, not
     * roboRIO-full. Jumping to the worst case would throw away a perfectly good device loop.
     */
    @Test
    void aProfilelessDeviceLoopStepsDownExactlyOneRung() {
      MotorCapabilities loopOnly = MotorCapabilities.builder().onBoardPositionLoop(true).build();
      assertEquals(
          ControlLocation.ON_MOTOR_DIRECT, loopOnly.downgrade(ControlLocation.ON_MOTOR_PROFILED));
      assertEquals(
          ControlLocation.ON_MOTOR_DIRECT, loopOnly.downgrade(ControlLocation.ON_MOTOR_DIRECT));
      assertEquals(
          ControlLocation.RIO_PROFILE_MOTOR_LOOP,
          loopOnly.downgrade(ControlLocation.RIO_PROFILE_MOTOR_LOOP));
    }

    /** A device with nothing on it lands on RIO_FULL from every request. */
    @Test
    void aDeviceWithNoLoopAlwaysLandsOnRioFull() {
      MotorCapabilities nothing = MotorCapabilities.rioOnly();
      for (ControlLocation location : ControlLocation.values()) {
        ControlLocation result = nothing.downgrade(location);
        assertEquals(
            ControlLocation.RIO_FULL,
            result,
            location + " on a device with no loop must degrade to RIO_FULL");
      }
    }

    /** A null request degrades to the always-achievable location rather than throwing. */
    @Test
    void aNullRequestDegradesToRioFull() {
      assertEquals(ControlLocation.RIO_FULL, MotorCapabilities.rioOnly().downgrade(null));
    }

    /** The result of a downgrade is always itself supported — the fixed point property. */
    @Test
    void aDowngradeIsAlwaysSupportedAndIsAFixedPoint() {
      List<MotorCapabilities> devices =
          List.of(
              MotorCapabilities.rioOnly(),
              MotorCapabilities.builder().onBoardPositionLoop(true).build(),
              MotorCapabilities.builder().onBoardProfile(true).build(),
              MotorCapabilities.builder().onBoardPositionLoop(true).onBoardProfile(true).build());
      for (MotorCapabilities device : devices) {
        for (ControlLocation requested : ControlLocation.values()) {
          ControlLocation got = device.downgrade(requested);
          assertTrue(
              device.supports(got),
              "downgrade must return something the device can actually run: " + requested);
          assertEquals(
              got, device.downgrade(got), "downgrading an already-downgraded location is a no-op");
        }
      }
    }
  }

  @Nested
  @DisplayName("describe(): the boot dump names the carrier")
  final class Describe {

    /** {@code describe()} must name the goal-velocity path, since that is what varies by backend. */
    @Test
    void describeNamesTheGoalVelocityCarrier() {
      for (VelocityCarrier carrier : VelocityCarrier.values()) {
        String text =
            MotorCapabilities.builder().positionGoalVelocity(carrier).build().describe();
        assertNotNull(text);
        assertTrue(
            text.contains(carrier.explanation()),
            "describe() must print the carrier's own explanation so the boot dump names the path "
                + "in use. Missing for "
                + carrier);
      }
    }

    /** The absences are stated positively — "NO device-reset signal", not silence. */
    @Test
    void describeStatesTheAbsencesOutLoud() {
      String text = MotorCapabilities.rioOnly().describe();
      assertTrue(text.contains("no position loop"), text);
      assertTrue(text.contains("NO device-reset signal"), text);
      assertTrue(text.contains("NO connection health"), text);
    }
  }

  @Nested
  @DisplayName("the shipped core backends report honestly")
  final class ShippedBackends {

    /** The simulation backend really does close its own loops, and never reports UNSUPPORTED. */
    @Test
    void theSimulationBackendReportsADeviceLoopAndDeliversTheGoalVelocity() {
      MotorCapabilities sim =
          new org.pumpkinlib.hardware.sim.SimMotorIO(
                  org.pumpkinlib.config.MotorSpec.sim(),
                  new org.pumpkinlib.units.MechanismUnits(
                      org.pumpkinlib.pure.units.Reduction.of(9.0),
                      org.pumpkinlib.units.LinearAxis.sprocket(
                          edu.wpi.first.units.Units.Inches.of(0.25), 22, 2)),
                  org.pumpkinlib.config.ControlConfig.defaults(),
                  org.pumpkinlib.config.MechanismKind.POSITION)
              .capabilities();
      assertTrue(sim.hasDeviceLoop());
      assertTrue(sim.supports(ControlLocation.ON_MOTOR_PROFILED));
      assertTrue(sim.positionGoalVelocity().delivers());
    }

    /**
     * {@link NoOpMotorIO} is the one place {@link VelocityCarrier#UNSUPPORTED} is correct: it is a
     * stand-in for a device that is not there, so a field-locked axis on it must be a loud config
     * error rather than a turret that quietly does not counter-rotate.
     */
    @Test
    void theNoOpBackendAdmitsItCanDoNothing() {
      MotorCapabilities none = new NoOpMotorIO("missing").capabilities();
      assertFalse(none.hasDeviceLoop());
      assertEquals(VelocityCarrier.UNSUPPORTED, none.positionGoalVelocity());
      assertFalse(none.positionGoalVelocity().delivers());
      assertEquals(ControlLocation.RIO_FULL, none.downgrade(ControlLocation.ON_MOTOR_PROFILED));
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static boolean readBoolean(MotorCapabilities caps, RecordComponent component) {
    try {
      return (boolean) component.getAccessor().invoke(caps);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("could not read " + component.getName(), e);
    }
  }
}

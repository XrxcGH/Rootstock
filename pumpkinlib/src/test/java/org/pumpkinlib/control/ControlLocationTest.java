package org.pumpkinlib.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MotorArrangement;
import org.pumpkinlib.config.MotorModel;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.config.SparkModel;

/**
 * {@link ControlLocation}: where the loop actually runs, and how a student finds out.
 *
 * <p><strong>Four values, and the differences between them are not stylistic.</strong> Whether the
 * profile is stepped on the roboRIO or latched on the device decides whether a goal can react to
 * game state mid-move; whether the feedback loop runs on the device decides whether it runs at 1 kHz
 * or at 50 Hz; whether the setpoint latches decides what happens when the CAN bus drops a frame. A
 * team that does not know which one it is running cannot reason about any of those.
 *
 * <p><strong>The default is chosen, and the choice is printed.</strong> Most configs never name a
 * control location — the library picks one from the leader motor. A silent default is the failure
 * mode here: a student who never wrote {@code .controlLocation(...)} has no way to know one was
 * chosen, so {@link ControlLocationSource} carries the provenance and {@code describe()} prints it
 * along with the escape hatch. These tests pin that the provenance survives into the text.
 */
final class ControlLocationTest {

  // ===============================================================================================
  // The four values
  // ===============================================================================================

  @Nested
  @DisplayName("the enum")
  final class Values {

    @Test
    @DisplayName("has exactly four values, in order from most on-device to least")
    void exactlyFour() {
      assertEquals(
          List.of("ON_MOTOR_PROFILED", "ON_MOTOR_DIRECT", "RIO_PROFILE_MOTOR_LOOP", "RIO_FULL"),
          Arrays.stream(ControlLocation.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("runsOnMotor() is true for all but RIO_FULL")
    void runsOnMotorIsTrueExceptForRioFull() {
      assertTrue(ControlLocation.ON_MOTOR_PROFILED.runsOnMotor());
      assertTrue(ControlLocation.ON_MOTOR_DIRECT.runsOnMotor());
      // RIO_PROFILE_MOTOR_LOOP still closes the FEEDBACK loop on the device — only the profile
      // moved to the roboRIO. Reading this as "false" would send a bare voltage to a device that
      // was configured for position control.
      assertTrue(ControlLocation.RIO_PROFILE_MOTOR_LOOP.runsOnMotor());
      assertFalse(ControlLocation.RIO_FULL.runsOnMotor());
    }

    @Test
    @DisplayName("profileOnRio() is true exactly where the roboRIO steps the profile")
    void profileOnRio() {
      assertFalse(ControlLocation.ON_MOTOR_PROFILED.profileOnRio());
      // ON_MOTOR_DIRECT has NO profile at all, on either side, so it is not "profile on rio".
      assertFalse(ControlLocation.ON_MOTOR_DIRECT.profileOnRio());
      assertTrue(ControlLocation.RIO_PROFILE_MOTOR_LOOP.profileOnRio());
      assertTrue(ControlLocation.RIO_FULL.profileOnRio());
    }

    @Test
    @DisplayName("setpointLatches() is true exactly where the device holds the last goal")
    void setpointLatches() {
      // A latched setpoint keeps commanding after robot code stops sending — which is the
      // behaviour that makes a disabled-then-enabled mechanism jump if nothing clears it.
      assertTrue(ControlLocation.ON_MOTOR_PROFILED.setpointLatches());
      assertTrue(ControlLocation.ON_MOTOR_DIRECT.setpointLatches());
      assertFalse(ControlLocation.RIO_PROFILE_MOTOR_LOOP.setpointLatches());
      assertFalse(ControlLocation.RIO_FULL.setpointLatches());
    }

    @Test
    @DisplayName("the three predicates partition the four into three classes, not four")
    void thePredicatesDoNotFormACompleteKey() {
      // This is pinned rather than asserted-away because it is a real property of the API that a
      // backend author has to know: ON_MOTOR_PROFILED and ON_MOTOR_DIRECT agree on all three
      // predicates (runsOnMotor, !profileOnRio, setpointLatches). What separates them — whether
      // the DEVICE shapes a profile — has no predicate of its own. A backend that dispatches on
      // the predicates alone will treat a Motion Magic mechanism as an unprofiled one; it must
      // switch on the enum value itself, or ask ControlLocation for a new predicate.
      List<String> keys =
          Arrays.stream(ControlLocation.values())
              .map(v -> v.runsOnMotor() + "/" + v.profileOnRio() + "/" + v.setpointLatches())
              .toList();
      assertEquals(
          List.of("true/false/true", "true/false/true", "true/true/false", "false/true/false"),
          keys);
      assertEquals(3, keys.stream().distinct().count());
      assertEquals(keys.get(0), keys.get(1), "the collision is between the two ON_MOTOR values");
    }

    @Test
    @DisplayName("every value explains itself in a sentence a student can act on")
    void everyValueHasADistinctExplanation() {
      for (ControlLocation location : ControlLocation.values()) {
        String explanation = location.explanation();
        assertFalse(explanation.isBlank(), location + " has no explanation");
        assertFalse(
            explanation.contains(location.name()),
            location + " explains itself by repeating its own name");
      }
      assertEquals(
          4,
          Arrays.stream(ControlLocation.values())
              .map(ControlLocation::explanation)
              .distinct()
              .count());
      assertTrue(ControlLocation.ON_MOTOR_PROFILED.explanation().contains("1 kHz"));
      assertTrue(ControlLocation.RIO_PROFILE_MOTOR_LOOP.explanation().contains("game state"));
      assertTrue(ControlLocation.RIO_FULL.explanation().contains("voltage"));
    }
  }

  // ===============================================================================================
  // Provenance
  // ===============================================================================================

  @Nested
  @DisplayName("the default is chosen WITH printed provenance")
  final class Provenance {

    @Test
    @DisplayName("a defaulted location says so, names the reason, and offers the override")
    void defaultedPrintsTheReasonAndTheEscapeHatch() {
      String text =
          ControlLocationSource.DEFAULTED.describe(
              ControlLocation.ON_MOTOR_PROFILED, "your leader is a TalonFX");
      assertTrue(text.startsWith("ON_MOTOR_PROFILED"), text);
      assertTrue(text.contains("defaulted"), text);
      assertTrue(text.contains("your leader is a TalonFX"), text);
      assertTrue(text.contains(ControlLocation.ON_MOTOR_PROFILED.explanation()), text);
      // Without this clause a student cannot tell a default from a decision, and cannot find the
      // knob even once they suspect one exists.
      assertTrue(text.contains(".controlLocation(...)"), text);
      assertTrue(ControlLocationSource.DEFAULTED.isDefaulted());
    }

    @Test
    @DisplayName("an explicit location says so and does NOT suggest changing it")
    void explicitPrintsTheCallSiteAndNoNag() {
      String text =
          ControlLocationSource.EXPLICIT.describe(
              ControlLocation.RIO_FULL, "you called .controlLocation(RIO_FULL)");
      assertTrue(text.startsWith("RIO_FULL"), text);
      assertTrue(text.contains("explicit"), text);
      assertFalse(text.contains("To change it"), text);
      assertFalse(ControlLocationSource.EXPLICIT.isDefaulted());
    }

    @Test
    @DisplayName("the two sources never render identically for the same location")
    void theTwoSourcesAreDistinguishableInText() {
      for (ControlLocation location : ControlLocation.values()) {
        assertNotEquals(
            ControlLocationSource.EXPLICIT.describe(location, "a reason"),
            ControlLocationSource.DEFAULTED.describe(location, "a reason"),
            location + " renders identically whether it was chosen or defaulted");
      }
    }
  }

  // ===============================================================================================
  // Which default each leader motor produces
  // ===============================================================================================

  @Nested
  @DisplayName("the default per leader motor")
  final class Defaults {

    @Test
    @DisplayName("a device that closes its own loop defaults to ON_MOTOR_PROFILED")
    void closedLoopCapableDevicesDefaultOnMotor() {
      assertEquals(
          ControlLocation.ON_MOTOR_PROFILED,
          ControlConfig.defaultLocationFor(MotorSpec.talonFX(20, "rio")));
      assertEquals(
          ControlLocation.ON_MOTOR_PROFILED,
          ControlConfig.defaultLocationFor(
              MotorSpec.talonFXS(21, "rio", MotorArrangement.MINION_JST)));
      assertEquals(
          ControlLocation.ON_MOTOR_PROFILED,
          ControlConfig.defaultLocationFor(MotorSpec.spark(24, SparkModel.MAX_NEO)));
      assertTrue(MotorSpec.talonFX(20, "rio").supportsOnMotorControl());
      assertTrue(MotorSpec.spark(24, SparkModel.MAX_NEO).supportsOnMotorControl());
    }

    @Test
    @DisplayName("a PWM controller has no loop of its own, so it defaults to RIO_FULL")
    void pwmDefaultsToRioFull() {
      MotorSpec pwm = MotorSpec.generic(0, MotorModel.CIM);
      assertFalse(pwm.supportsOnMotorControl());
      assertEquals(ControlLocation.RIO_FULL, ControlConfig.defaultLocationFor(pwm));
      assertEquals(ControlLocation.RIO_FULL, pwm.defaultControlLocation());
    }

    @Test
    @DisplayName("a missing leader falls back to RIO_FULL rather than throwing")
    void nullLeaderIsTotal() {
      assertEquals(ControlLocation.RIO_FULL, ControlConfig.defaultLocationFor(null));
    }
  }

  // ===============================================================================================
  // How the choice reaches ControlConfig
  // ===============================================================================================

  @Nested
  @DisplayName("ControlConfig carries the choice and its provenance together")
  final class InControlConfig {

    @Test
    @DisplayName("a fresh ControlConfig is RIO_FULL and DEFAULTED — the safest pair")
    void defaultsAreRioFullAndDefaulted() {
      ControlConfig config = ControlConfig.defaults();
      assertEquals(ControlLocation.RIO_FULL, config.location());
      assertEquals(ControlLocationSource.DEFAULTED, config.locationSource());
    }

    @Test
    @DisplayName("withLocation() marks the result EXPLICIT; withDefaultedLocation() does not")
    void theTwoSettersRecordDifferentProvenance() {
      ControlConfig explicit =
          ControlConfig.defaults().withLocation(ControlLocation.ON_MOTOR_DIRECT);
      assertEquals(ControlLocation.ON_MOTOR_DIRECT, explicit.location());
      assertEquals(ControlLocationSource.EXPLICIT, explicit.locationSource());

      ControlConfig defaulted =
          ControlConfig.defaults().withDefaultedLocation(ControlLocation.ON_MOTOR_DIRECT);
      assertEquals(ControlLocation.ON_MOTOR_DIRECT, defaulted.location());
      assertEquals(ControlLocationSource.DEFAULTED, defaulted.locationSource());
    }

    @Test
    @DisplayName("the builder's explicit choice survives a later defaulting pass")
    void anExplicitChoiceIsNotOverwrittenByTheDefault() {
      // PositionConfig.Builder.build() applies the motor-derived default AFTER the user's calls.
      // If that pass did not respect an explicit choice, .controlLocation(...) would be ignored
      // on every TalonFX mechanism — the exact case a team overrides it for.
      ControlConfig config =
          ControlConfig.builder().location(ControlLocation.RIO_FULL).build();
      assertEquals(ControlLocation.RIO_FULL, config.location());
      assertEquals(ControlLocationSource.EXPLICIT, config.locationSource());
    }

    @Test
    @DisplayName("describe() prints the location, the provenance and the reason, in one line")
    void describeCarriesTheWholeStory() {
      String defaulted =
          ControlConfig.defaults()
              .withDefaultedLocation(ControlLocation.ON_MOTOR_PROFILED)
              .describe("m", org.pumpkinlib.units.SiDomain.LINEAR_METERS, "TalonFX")
              .lines()
              .filter(line -> line.contains("ControlLocation"))
              .collect(Collectors.joining());
      assertTrue(defaulted.contains("ON_MOTOR_PROFILED"), defaulted);
      assertTrue(defaulted.contains("defaulted"), defaulted);
      assertTrue(defaulted.contains("your leader is a TalonFX"), defaulted);
      assertTrue(defaulted.contains(".controlLocation(...)"), defaulted);

      String explicit =
          ControlConfig.defaults()
              .withLocation(ControlLocation.RIO_FULL)
              .describe("m", org.pumpkinlib.units.SiDomain.LINEAR_METERS, "TalonFX")
              .lines()
              .filter(line -> line.contains("ControlLocation"))
              .collect(Collectors.joining());
      assertTrue(explicit.contains("explicit"), explicit);
      assertTrue(explicit.contains("you called .controlLocation(RIO_FULL)"), explicit);
    }

    @Test
    @DisplayName("an unnamed leader still produces a sentence, not a null in the middle of one")
    void aBlankDeviceTypeStillReadsAsEnglish() {
      String text =
          ControlConfig.defaults()
              .describe("m", org.pumpkinlib.units.SiDomain.LINEAR_METERS, "")
              .lines()
              .filter(line -> line.contains("ControlLocation"))
              .collect(Collectors.joining());
      assertFalse(text.contains("null"), text);
      assertTrue(text.contains("this is the library default"), text);
    }

    @Test
    @DisplayName("RIO_FULL with useExpo is refused, because Expo is a device feature")
    void expoOnTheRioIsAContradiction() {
      List<String> problems =
          ControlConfig.defaults()
              .withLocation(ControlLocation.RIO_FULL)
              .withUseExpo(true)
              .problems();
      assertTrue(
          problems.stream().anyMatch(p -> p.contains("useExpo is true")), problems.toString());
      String message =
          problems.stream().filter(p -> p.contains("useExpo is true")).findFirst().orElseThrow();
      assertTrue(message.contains("Motion Magic Expo"), message);
      assertTrue(message.contains("ON_MOTOR_PROFILED"), message);
      assertTrue(message.contains(".useExpo(false)"), message);

      // The same combination on a device that CAN run Expo is not a problem.
      assertFalse(
          ControlConfig.defaults()
              .withLocation(ControlLocation.ON_MOTOR_PROFILED)
              .withUseExpo(true)
              .problems()
              .stream()
              .anyMatch(p -> p.contains("useExpo is true")));
    }
  }
}

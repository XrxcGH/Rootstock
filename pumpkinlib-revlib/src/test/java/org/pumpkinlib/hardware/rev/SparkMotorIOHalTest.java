package org.pumpkinlib.hardware.rev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.ControlConfig;
import org.pumpkinlib.config.MechanismKind;
import org.pumpkinlib.config.MotionConstraints;
import org.pumpkinlib.config.MotorSpec;
import org.pumpkinlib.config.SparkModel;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;

/**
 * The half of the REV unit chain that cannot be tested without REVLib's natives.
 *
 * <p><b>Why this is tagged and the rest of {@link RevGainSinkTest} is not.</b> {@code SparkMaxConfig}
 * — and therefore {@code SparkBaseConfig}, {@code RevUtil.newConfig} and every path that builds a
 * real configuration object — has a static initialiser that loads {@code REVLibDriver}. On a JVM
 * without the natives that is an {@code ExceptionInInitializerError}, not a catchable failure. Every
 * arithmetic assertion that can be made on plain doubles is made in {@link RevGainSinkTest}, which
 * runs in the default build; what is left here is the part that requires a real config object and a
 * real device handle:
 *
 * <ul>
 *   <li>the conversion factors {@code SparkMotorIO} actually installs, {@code 1/G} and {@code
 *       1/(60G)};
 *   <li>the MAXMotion cruise velocity it actually writes, in output rot/s with <b>no</b> {@code
 *       x 60};
 *   <li>{@code writeInto} putting the converted gains into a real {@code SparkBaseConfig}.
 * </ul>
 *
 * <p>Run with {@code ./gradlew :pumpkinlib-revlib:halTest} on a machine with the REVLib natives
 * present. The default {@code test} task excludes the {@code hal} tag so {@code ./gradlew build}
 * stays green on a bare desktop.
 */
@Tag("hal")
final class SparkMotorIOHalTest {

  /** 22 teeth x 0.250 in x 2 stages = 11.000 in = 0.279400 m per output rotation. */
  private static final double kU = 11.0 * 0.0254;

  private static final double kG = 9.0;

  private static MechanismUnits elevatorUnits() {
    return new MechanismUnits(
        Reduction.of(kG), LinearAxis.sprocket(Units.Inches.of(0.25), 22, 2));
  }

  private static ControlConfig control() {
    return ControlConfig.builder()
        .gains(new Gains(80.0, 0.5, 2.0, 0.22, 12.0, 0.6, 0.35))
        .constraints(MotionConstraints.of(1.6, 3.0))
        .tolerance(Units.Meters.of(0.005), 0.05, 0.06)
        .build();
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

  /**
   * The backend's own boot dump must state the two conversion factors and the cruise velocity in the
   * converted unit. These are the numbers a stray {@code x 60} would change, and {@code describe()}
   * is where a student reads them at an event.
   */
  @Test
  void describeReportsTheConvertedUnitsWithNoStrayTimesSixty() {
    SparkMotorIO io =
        new SparkMotorIO(
            MotorSpec.spark(9, SparkModel.MAX_NEO),
            elevatorUnits(),
            control(),
            MechanismKind.POSITION);
    String text = io.describe();

    // f_p = 1/9 = 0.11111111, f_v = 1/540 = 0.00185185
    assertTrue(text.contains("0.11111111"), "positionConversionFactor 1/G must be printed: " + text);
    assertTrue(
        text.contains("0.00185185"), "velocityConversionFactor 1/(60G) must be printed: " + text);

    // 1.6 m/s / 0.2794 m per output rot = 5.7266 output rot/s, printed to 4 decimal places.
    assertTrue(
        text.contains("cruise 5.7266 output rot/s"),
        "the cruise velocity must be written in OUTPUT ROT/S with no x60: " + text);
    assertTrue(text.contains("NO x60"), text);
  }

  /**
   * {@code flatten()} is REVLib's own rendering of a configuration, and it is the call that needs the
   * driver. Asserting the converted kP survives into it is the end-to-end confirmation that the
   * reflective parameter-table read in {@link RevGainSinkTest} is looking at the right map.
   */
  @Test
  void flattenShowsTheConvertedGainsOnARealDevice() {
    RevGainSink sink =
        new RevGainSink(
            null,
            MotorSpec.spark(9, SparkModel.MAX_NEO),
            kU,
            org.pumpkinlib.control.GravityMode.CONSTANT,
            0.0,
            "Elevator");
    Gains gains = new Gains(80.0, 0.5, 2.0, 0.22, 12.0, 0.6, 0.35);
    var config = RevUtil.newConfig(MotorSpec.spark(9, SparkModel.MAX_NEO));

    sink.writeInto(config, gains);

    assertEquals(gains, sink.applied());
    // 80.0 x 0.279400 / 12 = 1.8626667
    assertEquals(80.0 * kU / 12.0, sink.p(80.0), 1e-12);
    assertTrue(
        config.flatten().contains("1.8626667"),
        "REVLib's own rendering must show the converted kP: " + config.flatten());
  }
}

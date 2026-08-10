package org.pumpkinlib.sim;

import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Kilograms;
import static edu.wpi.first.units.Units.Meters;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.system.plant.DCMotor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.config.SimConfig;
import org.pumpkinlib.core.alert.AlertRegistry;
import org.pumpkinlib.core.spi.MechanismGeometry;
import org.pumpkinlib.pure.units.Reduction;
import org.pumpkinlib.units.LinearAxis;
import org.pumpkinlib.units.MechanismUnits;

/**
 * {@link PumpkinSim#printBootReport()} end to end: the peak-stall-current line really is printed to
 * the console a student is looking at, and the declaration alerts really are raised.
 *
 * <p>Tagged {@code hal} because {@code printBootReport} raises {@code PumpkinAlert}s, which construct
 * WPILib {@code Alert}s, which touch NetworkTables — and without WPILib's JNI that ends the test JVM
 * with {@code System.exit(1)} rather than an exception. Run with {@code ./gradlew halTest}.
 *
 * <p>{@code PumpkinSimTest} pins the content of the report — the peak, the predicted sag, the
 * brownout call and the exact wording — through {@link PumpkinSim#stressTest()}, which is pure
 * arithmetic and needs nothing.
 */
@Tag("hal")
final class PumpkinSimHalTest {

  private static final LinearAxis kAxis = LinearAxis.sprocket(Inches.of(0.25), 22, 2);

  @BeforeEach
  void freshRegistry() {
    PumpkinSim.resetForTest();
    AlertRegistry.resetForTest();
  }

  @AfterEach
  void clearRegistry() {
    PumpkinSim.resetForTest();
    AlertRegistry.resetForTest();
  }

  private static MechanismGeometry elevator(double rotorPerOutput, MechanismUnits units) {
    SimConfig sim = SimConfig.linear(Kilograms.of(6.0), Meters.of(0.0));
    return new MechanismGeometry(
        "Elevator",
        MechanismGeometry.Kind.LINEAR,
        DCMotor.getKrakenX60Foc(2),
        rotorPerOutput,
        units.siPerOutputRotation(),
        units.siPerOutputRotation() / (2.0 * Math.PI),
        sim.massKg(),
        Double.NaN,
        Double.NaN,
        0.0,
        1.5,
        sim.startingPositionSi(),
        true);
  }

  /** Captures everything {@code body} writes to {@code System.out}. */
  private static String captureStdout(Runnable body) {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      body.run();
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the boot report prints the peak stall current to the console")
  void thePeakStallLineIsPrintedAtBoot() {
    MechanismUnits units = MechanismUnits.of(Reduction.of(9.0), kAxis);
    PumpkinSim.declare(elevator(9.0, units), units);

    String printed = captureStdout(PumpkinSim::printBootReport);

    assertTrue(printed.contains("PumpkinSim: 1 simulated mechanism(s)"), printed);
    assertTrue(
        printed.contains("PEAK SIMULATED STALL CURRENT 966 A -> bus sags to 0.00 V"), printed);
    assertTrue(printed.contains("BELOW the 6.75 V brownout threshold"), printed);
    assertTrue(printed.contains("Elevator"), printed);
    assertTrue(printed.contains("ElevatorSim"), printed);
  }

  @Test
  @DisplayName("a mechanism with no device wired is named in an alert, not left silent")
  void anUnattachedMechanismIsNamed() {
    MechanismUnits units = MechanismUnits.of(Reduction.of(9.0), kAxis);
    PumpkinSim.declare(elevator(9.0, units), units);

    captureStdout(PumpkinSim::printBootReport);

    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(a -> a.isActive() && a.text().contains("SIM_NO_DEVICE")),
        "a plant with no motor controller will not move in simulateJava, and silence about that"
            + " costs an evening");
  }

  @Test
  @DisplayName("a geometry that contradicts itself is named in an alert")
  void aContradictoryDeclarationIsNamed() {
    MechanismUnits units = MechanismUnits.of(Reduction.of(9.0), kAxis);
    MechanismGeometry declared = elevator(9.0, units);
    MechanismGeometry halfRadius =
        new MechanismGeometry(
            declared.name(),
            declared.kind(),
            declared.gearbox(),
            declared.rotorPerOutput(),
            declared.siPerOutputRotation(),
            declared.effectiveRadiusMeters() / 2.0,
            declared.massKg(),
            declared.momentOfInertiaKgM2(),
            declared.armLengthMeters(),
            declared.siMin(),
            declared.siMax(),
            declared.siStart(),
            declared.simulateGravity());
    PumpkinSim.declare(halfRadius, units);

    captureStdout(PumpkinSim::printBootReport);

    assertTrue(
        AlertRegistry.all().stream()
            .anyMatch(
                a ->
                    a.isActive()
                        && a.text().contains("SIM_DECLARATION")
                        && a.text().contains("50.0% disagreement")),
        "a missing cascade stage must be visible in simulateJava, not at an event");
  }
}

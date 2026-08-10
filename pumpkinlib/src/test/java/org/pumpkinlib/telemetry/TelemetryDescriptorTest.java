package org.pumpkinlib.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.units.Unit;
import edu.wpi.first.units.Units;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.spi.Tier;
import org.pumpkinlib.telemetry.schema.ControlMode;
import org.pumpkinlib.telemetry.schema.MechanismSchema;

/**
 * {@link TelemetryDescriptor} — and in particular the two {@code extra} overloads, which are
 * <strong>not</strong> interchangeable.
 *
 * <p><strong>Why the unit-free overload had to exist.</strong> Three of the five extras section 3.1
 * declares have no unit at all: {@code DeviceResetCount} is a count, {@code Blocked} is a
 * {@code String}, {@code Plan} is a {@code String[]}. With only a
 * {@code extra(String, Unit, Tier)} available, all three were forced to pass
 * {@code edu.wpi.first.units.Units.Value} — a {@code DimensionlessUnit} — purely to satisfy the
 * parameter, which writes entry metadata claiming the key is a dimensionless measurement.
 * AdvantageScope reads that metadata for axis labelling and conversion. A {@code String} is not
 * dimensionless; it is unit-less, and the schema has to be able to say so.
 *
 * <p>So "records no unit metadata" is the property, and it is observed through the descriptor's own
 * public duplicate-declaration guard rather than by reading a private field: declaring one key twice
 * with different metadata throws, and the exception prints the metadata that was actually stored.
 * If the unit-free overload quietly stored {@code Units.Value}, that message would say so.
 */
final class TelemetryDescriptorTest {

  /** Declares a block from an inline {@code describe(...)} body. */
  private static MechanismSchema declare(Consumer<TelemetryDescriptor> body) {
    return MechanismSchema.declare(new InlineSource("Elevator", body));
  }

  @Nested
  @DisplayName("both extra overloads exist, with the signatures the design froze")
  final class Overloads {

    @Test
    void theUnitCarryingOverloadIsPresent() throws Exception {
      assertNotNull(TelemetryDescriptor.class.getMethod("extra", String.class, Unit.class, Tier.class));
    }

    @Test
    void theUnitFreeOverloadIsPresent() throws Exception {
      assertNotNull(TelemetryDescriptor.class.getMethod("extra", String.class, Tier.class));
    }

    /** Exactly two, so a third shape cannot appear without this test noticing. */
    @Test
    void thereAreExactlyTwo() {
      long count =
          java.util.Arrays.stream(TelemetryDescriptor.class.getMethods())
              .filter(m -> m.getName().equals("extra"))
              .count();
      assertEquals(2, count);
    }

    /** Every declaration method returns the descriptor, so a {@code describe} body is one chain. */
    @Test
    void everyMethodReturnsTheDescriptorForChaining() {
      MechanismSchema schema =
          declare(
              d -> {
                TelemetryDescriptor chained =
                    d.positionUnit(Units.Meters)
                        .velocityUnit(Units.MetersPerSecond)
                        .motorCount(2)
                        .states(ControlMode.class)
                        .extra("FeedbackVolts", Units.Volts, Tier.STANDARD)
                        .extra("DeviceResetCount", Tier.CRITICAL);
                assertSame(d, chained);
              });

      assertEquals("Pumpkin/Elevator/FeedbackVolts", schema.extraKey("FeedbackVolts"));
      assertEquals("Pumpkin/Elevator/DeviceResetCount", schema.extraKey("DeviceResetCount"));
    }
  }

  @Nested
  @DisplayName("the unit-free overload records no unit")
  final class NoUnitMetadata {

    /**
     * Declared unit-free, then re-declared with a unit: the guard prints what was stored the first
     * time. {@code "no unit"} there is the whole assertion — a stored {@code Units.Value} would print
     * as {@code "Value"} instead, which is exactly the mislabel this overload exists to avoid.
     */
    @Test
    void aUnitFreeDeclarationStoresNoUnitAndSaysSoWhenItIsContradicted() {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  declare(
                      d -> {
                        d.extra("DeviceResetCount", Tier.CRITICAL);
                        d.extra("DeviceResetCount", Units.Volts, Tier.CRITICAL);
                      }));

      assertTrue(
          thrown.getMessage().contains("CRITICAL/no unit then"),
          () ->
              "the unit-free overload must store no unit at all, not Units.Value. The guard said: "
                  + thrown.getMessage());
    }

    /** And the same in the other direction, so the message is not just a formatting accident. */
    @Test
    void aUnitCarryingDeclarationContradictedByAUnitFreeOneSaysTheSame() {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  declare(
                      d -> {
                        d.extra("Blocked", Units.Volts, Tier.STANDARD);
                        d.extra("Blocked", Tier.STANDARD);
                      }));

      assertTrue(
          thrown.getMessage().contains("then STANDARD/no unit"), thrown.getMessage());
    }

    /** Re-declaring a unit-free key identically is not a contradiction. */
    @Test
    void declaringTheSameUnitFreeKeyTwiceIsHarmless() {
      MechanismSchema schema =
          assertDoesNotThrow(
              () ->
                  declare(
                      d -> {
                        d.extra("Plan", Tier.STANDARD);
                        d.extra("Plan", Tier.STANDARD);
                      }));

      assertEquals("Pumpkin/Elevator/Plan", schema.extraKey("Plan"));
    }

    /** A key declared at two different tiers is the same contradiction as two different units. */
    @Test
    void theSameKeyAtTwoTiersIsRefused() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              declare(
                  d -> {
                    d.extra("DeviceResetCount", Tier.CRITICAL);
                    d.extra("DeviceResetCount", Tier.STANDARD);
                  }));
    }

    /**
     * The unit-carrying overload refuses a null unit and points at the other one, rather than
     * silently becoming the unit-free overload.
     */
    @Test
    void theUnitCarryingOverloadRefusesANullUnit() {
      NullPointerException thrown =
          assertThrows(
              NullPointerException.class,
              () -> declare(d -> d.extra("FeedbackVolts", null, Tier.STANDARD)));

      assertTrue(thrown.getMessage().contains("unit-free overload"), thrown.getMessage());
    }
  }

  @Nested
  @DisplayName("the rest of the declaration surface")
  final class Declaration {

    /**
     * Meters for a linear axis, Degrees for a rotary one — the descriptor stores what it was given
     * and adds no conversion of its own, which is what makes the declaration the fix rather than a
     * conversion somewhere being the fix.
     */
    @Test
    void theDeclaredUnitsAreHandedBackUnchanged() {
      MechanismSchema linear =
          declare(d -> d.positionUnit(Units.Meters).velocityUnit(Units.MetersPerSecond));
      assertSame(Units.Meters, linear.positionUnit());
      assertSame(Units.MetersPerSecond, linear.velocityUnit());

      MechanismSchema rotary =
          declare(d -> d.positionUnit(Units.Degrees).velocityUnit(Units.DegreesPerSecond));
      assertSame(Units.Degrees, rotary.positionUnit());
      assertSame(Units.DegreesPerSecond, rotary.velocityUnit());
    }

    @Test
    void undeclaredUnitsAreNullRatherThanAGuess() {
      MechanismSchema schema = declare(d -> d.motorCount(1));

      assertEquals(null, schema.positionUnit());
      assertEquals(null, schema.velocityUnit());
      assertEquals(null, schema.stateEnum());
    }

    @Test
    void motorCountDefaultsToOneAndRefusesZero() {
      assertEquals(1, declare(d -> { }).motorCount());
      assertEquals(4, declare(d -> d.motorCount(4)).motorCount());

      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> declare(d -> d.motorCount(0)));
      assertTrue(
          thrown.getMessage().contains("report a mechanism as free"), thrown.getMessage());
    }

    @Test
    void theStateEnumIsStoredForLayoutGeneration() {
      assertSame(ControlMode.class, declare(d -> d.states(ControlMode.class)).stateEnum());
    }

    /**
     * The descriptor is sealed the instant {@code describe} returns. A source that stashes the
     * reference and declares from its {@code periodic()} gets a named exception instead of a
     * declaration that silently arrived after the writer was already built.
     */
    @Test
    void theDescriptorIsSealedWhenDescribeReturns() {
      CapturingSource source = new CapturingSource();
      MechanismSchema.declare(source);

      IllegalStateException thrown =
          assertThrows(IllegalStateException.class, () -> source.m_captured.motorCount(3));
      assertTrue(thrown.getMessage().contains("after describe(...) returned"), thrown.getMessage());

      assertThrows(
          IllegalStateException.class,
          () -> source.m_captured.extra("Late", Tier.STANDARD));
    }

    /**
     * Extras keep declaration order in {@code declaredKeys()} — guarantee G7. The schema audit and
     * the layout generator both walk that list, and two runs of the same robot must produce the same
     * ordering.
     */
    @Test
    void extrasKeepDeclarationOrderAndNotHashOrder() {
      MechanismSchema schema =
          declare(
              d -> {
                d.extra("Zulu", Tier.STANDARD);
                d.extra("Alpha", Tier.STANDARD);
                d.extra("Mike", Tier.STANDARD);
              });

      java.util.List<String> keys = schema.declaredKeys();
      int zulu = keys.indexOf("Pumpkin/Elevator/Zulu");
      int alpha = keys.indexOf("Pumpkin/Elevator/Alpha");
      int mike = keys.indexOf("Pumpkin/Elevator/Mike");

      assertTrue(zulu >= 0 && alpha > zulu && mike > alpha, keys.toString());
    }

    @Test
    void everyDeclarationMethodRefusesNull() {
      assertThrows(NullPointerException.class, () -> declare(d -> d.positionUnit(null)));
      assertThrows(NullPointerException.class, () -> declare(d -> d.velocityUnit(null)));
      assertThrows(NullPointerException.class, () -> declare(d -> d.states(null)));
      assertThrows(NullPointerException.class, () -> declare(d -> d.extra(null, Tier.STANDARD)));
      assertThrows(NullPointerException.class, () -> declare(d -> d.extra("K", (Tier) null)));
      assertThrows(IllegalArgumentException.class, () -> declare(d -> d.extra("  ", Tier.STANDARD)));
    }
  }

  // ===============================================================================================

  /** A source whose {@code describe} body is supplied per test. */
  private record InlineSource(String name, Consumer<TelemetryDescriptor> body)
      implements TelemetrySource {
    @Override
    public String telemetryName() {
      return name;
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      body.accept(d);
    }
  }

  /** A source that keeps the descriptor past the end of {@code describe}, which is a bug. */
  private static final class CapturingSource implements TelemetrySource {
    private TelemetryDescriptor m_captured;

    @Override
    public String telemetryName() {
      return "Elevator";
    }

    @Override
    public void describe(TelemetryDescriptor d) {
      m_captured = d;
      d.motorCount(1);
    }
  }
}

package org.pumpkinlib.telemetry;

import edu.wpi.first.units.Unit;
import org.pumpkinlib.core.spi.Tier;

/**
 * What a {@link TelemetrySource} declares about its own key block, once, at registration.
 *
 * <p>Every method returns {@code this} so a {@code describe(...)} body reads as one chain. The
 * instance is built by telemetry and is valid only inside {@link TelemetrySource#describe}; storing it
 * is a bug.
 *
 * <p>A declaration here is what makes a publish <i>legal</i>. The per-cycle schema audit rejects both
 * halves of the mismatch: a key declared and not published, and a key published under
 * {@code Pumpkin/<Name>/} that was never declared. Without that, a mechanism that silently stops
 * publishing {@code Setpoint} is invisible until a student is staring at an empty graph at an event.
 */
public interface TelemetryDescriptor {

  /**
   * Declares the natural unit of this mechanism's position axis.
   *
   * <p><b>Meters for a linear axis, Degrees for a rotary one — not Radians.</b> This is not a style
   * preference. {@code org.pumpkinlib.units.Axis} defines {@code userPerOutputRotation()} as
   * degrees-per-output-rotation and {@code Axis.unitLabel()} returns {@code "deg"}, so the stream a
   * rotary mechanism actually publishes into {@code Position}, {@code Goal}, {@code Setpoint},
   * {@code Measured}, {@code Error} and the two soft limits is in degrees. Declaring
   * {@code Units.Radians} over that stream is a 57.3x mislabel, and AdvantageScope's unit conversion
   * would then compound the error rather than reveal it — a 90 degree arm setpoint plotted as 5157.
   *
   * @param unit {@code Units.Meters} for a linear axis, {@code Units.Degrees} for a rotary one
   * @return this descriptor
   */
  TelemetryDescriptor positionUnit(Unit unit);

  /**
   * Declares the natural unit of this mechanism's velocity axis.
   *
   * <p>{@code Units.MetersPerSecond} for a linear axis and {@code Units.DegreesPerSecond} for a rotary
   * one, for exactly the reason in {@link #positionUnit(Unit)}.
   *
   * @param unit the velocity unit
   * @return this descriptor
   */
  TelemetryDescriptor velocityUnit(Unit unit);

  /**
   * The number of motors, so the per-motor array fields ({@code AppliedVolts},
   * {@code SupplyCurrentAmps}, {@code TempCelsius}, {@code Connected}) can be sized statically by
   * {@code ./gradlew logBudget} before an event rather than measured at one.
   *
   * @param n the motor count; one for a single-motor mechanism, never zero
   * @return this descriptor
   */
  TelemetryDescriptor motorCount(int n);

  /**
   * Optional: the mechanism's state enum, so the {@code State} key has a readable string and the
   * generated layouts can enumerate the legal values.
   *
   * @param stateEnum the enum class
   * @return this descriptor
   */
  TelemetryDescriptor states(Class<? extends Enum<?>> stateEnum);

  /**
   * An extra scalar this mechanism publishes in its standard block, <b>with</b> a unit.
   *
   * <p>Asserts <i>"this value is a measurement in this unit"</i> and writes the entry metadata
   * AdvantageScope reads for axis labelling and conversion. Numeric keys that genuinely have a unit
   * must use this form — {@code FeedbackVolts} and {@code FeedforwardVolts} stay {@code Units.Volts}.
   *
   * @param key the key, relative to {@code Pumpkin/<Name>/}
   * @param unit the unit the value is measured in
   * @param tier the tier; see {@code Tier} for what survives the FMS gate
   * @return this descriptor
   */
  TelemetryDescriptor extra(String key, Unit unit, Tier tier);

  /**
   * An extra this mechanism publishes in its standard block that has <b>no unit at all</b>: counts,
   * {@code String}s, {@code String[]}s, booleans.
   *
   * <p>Equivalent to {@link #extra(String, Unit, Tier)} except that no unit metadata entry is
   * attached — which is the entire point, and the two are <b>not</b> interchangeable. This overload
   * exists because three of core's five extras ({@code DeviceResetCount}, a count; {@code Blocked}, a
   * {@code String}; {@code Plan}, a {@code String[]}) were otherwise forced to pass
   * {@code edu.wpi.first.units.Units.Value} — a {@code DimensionlessUnit} — purely to satisfy the
   * parameter. A {@code String} key is not dimensionless; it is unit-less, and the schema should be
   * able to say so.
   *
   * <p>A key declared unit-free and later given a unit is a schema change and bumps
   * {@code Pumpkin/Log/SchemaGeneration} like any other.
   *
   * @param key the key, relative to {@code Pumpkin/<Name>/}
   * @param tier the tier; {@code DeviceResetCount} is {@code CRITICAL} because a controller rebooting
   *     mid-match must appear in an FMS-attached log and there is no second copy of that fact
   * @return this descriptor
   */
  TelemetryDescriptor extra(String key, Tier tier);
}

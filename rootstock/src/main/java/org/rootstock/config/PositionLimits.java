package org.rootstock.config;

import static edu.wpi.first.units.Units.Celsius;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Temperature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.rootstock.units.Range;

/**
 * Where a mechanism is allowed to be, how hard it is allowed to push to get there, and what tells it
 * that it has run out of travel.
 *
 * <h2>Soft limits and hard stops are different things</h2>
 *
 * <p>The {@code min}/{@code max} pair is a <b>soft limit</b>: a number. Rootstock writes it to the
 * motor controller <em>and</em> re-clamps every goal against it in Java, so a soft limit is enforced
 * even when the device configuration failed to apply. Belt and braces, deliberately, because the
 * failure mode of a missing soft limit on an elevator is a broken elevator.
 *
 * <p>A <b>hard stop</b> is a physical end of travel with a switch on it. When the switch is wired
 * into the motor controller ({@link SensorSpec#motorLimit}) the stop happens in firmware and works
 * even if robot code has hung; on a roboRIO DIO channel it cannot, and the config-time description
 * says so. Hard stops should sit <em>outside</em> the soft limits: the soft limit is where the
 * mechanism chooses to stop, the hard stop is where the mechanism has no choice.
 *
 * <h2>Why the bounds are {@code Measure<?>}</h2>
 *
 * <p>A linear mechanism's limits are distances and a rotary one's are angles, and the whole point of
 * this library's unit contract is that a team types the unit it thinks in. Storing the typed measure
 * — rather than a bare double plus a convention — is what lets {@link #range()} hand back a
 * correctly-labelled range and lets a mismatched pair (a distance and an angle on the same
 * mechanism) be caught as a config error rather than silently reinterpreted.
 *
 * @param min the lower soft limit, as a {@link Distance} or an {@link Angle}
 * @param max the upper soft limit, in the same kind of unit as {@code min}
 * @param current the stator and supply limits for this mechanism
 * @param overTempCelsius the device temperature above which a warning is raised
 * @param followerToleranceRot how far a follower's position may drift from its leader's, in output
 *     rotations, before the mismatch is reported — a slipped belt or a stripped gear shows up here
 *     long before anyone notices it by eye
 * @param forwardHardStop the switch at the increasing-position end, if there is one
 * @param reverseHardStop the switch at the decreasing-position end, if there is one
 */
public record PositionLimits(
    Measure<?> min,
    Measure<?> max,
    CurrentLimits current,
    double overTempCelsius,
    double followerToleranceRot,
    Optional<SensorSpec> forwardHardStop,
    Optional<SensorSpec> reverseHardStop) {

  /**
   * The default over-temperature warning threshold, in Celsius.
   *
   * <p>Below the point at which a Kraken or a NEO starts derating itself, so the warning arrives
   * while there is still something a team can do about it — which is the only kind of warning worth
   * raising during a match.
   */
  public static final double kDefaultOverTempCelsius = 90.0;

  /**
   * The default follower drift tolerance, in output rotations.
   *
   * <p>Half an output rotation is far more than any real gearbox flexes and far less than a slipped
   * belt, which makes it a threshold that fires on faults and not on physics.
   */
  public static final double kDefaultFollowerToleranceRot = 0.5;

  /** Copies the optionals and rejects the components that cannot be recovered from. */
  public PositionLimits {
    min = Objects.requireNonNull(min, kNullBound);
    max = Objects.requireNonNull(max, kNullBound);
    current =
        Objects.requireNonNull(
            current,
            "PositionLimits: current limits are required. Use CurrentLimits.defaultsFor(motor) if "
                + "you have not measured your mechanism yet.");
    forwardHardStop = Objects.requireNonNull(forwardHardStop, kNullStop);
    reverseHardStop = Objects.requireNonNull(reverseHardStop, kNullStop);
  }

  /**
   * Soft limits for a linear mechanism, with the library's default temperature and follower
   * thresholds and no hard stops.
   *
   * @param min the lower soft limit
   * @param max the upper soft limit
   * @param current the stator and supply limits
   * @return the limits
   */
  public static PositionLimits of(Distance min, Distance max, CurrentLimits current) {
    return new PositionLimits(
        min,
        max,
        current,
        kDefaultOverTempCelsius,
        kDefaultFollowerToleranceRot,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Soft limits for a rotary mechanism, with the library's default thresholds and no hard stops.
   *
   * @param min the lower soft limit
   * @param max the upper soft limit
   * @param current the stator and supply limits
   * @return the limits
   */
  public static PositionLimits of(Angle min, Angle max, CurrentLimits current) {
    return new PositionLimits(
        min,
        max,
        current,
        kDefaultOverTempCelsius,
        kDefaultFollowerToleranceRot,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * A copy with a hard stop declared at one end.
   *
   * @param side which end of travel
   * @param sensor what senses it
   * @return a copy
   */
  public PositionLimits withHardStop(HardStop side, SensorSpec sensor) {
    Objects.requireNonNull(side, "PositionLimits.withHardStop: which end? FORWARD or REVERSE.");
    Objects.requireNonNull(sensor, "PositionLimits.withHardStop: the sensor must not be null.");
    Optional<SensorSpec> fwd = side.isForward() ? Optional.of(sensor) : forwardHardStop;
    Optional<SensorSpec> rev = side.isForward() ? reverseHardStop : Optional.of(sensor);
    return new PositionLimits(
        min, max, current, overTempCelsius, followerToleranceRot, fwd, rev);
  }

  /**
   * A copy with different current limits.
   *
   * @param value the new limits
   * @return a copy
   */
  public PositionLimits withCurrent(CurrentLimits value) {
    return new PositionLimits(
        min, max, value, overTempCelsius, followerToleranceRot, forwardHardStop, reverseHardStop);
  }

  /**
   * A copy with a different over-temperature warning threshold.
   *
   * @param value the new threshold
   * @return a copy
   */
  public PositionLimits withOverTemperature(Temperature value) {
    Objects.requireNonNull(value, "PositionLimits: the temperature threshold must not be null.");
    return new PositionLimits(
        min,
        max,
        current,
        value.in(Celsius),
        followerToleranceRot,
        forwardHardStop,
        reverseHardStop);
  }

  /**
   * A copy with a different follower drift tolerance.
   *
   * @param outputRotations the new tolerance, in output rotations
   * @return a copy
   */
  public PositionLimits withFollowerTolerance(double outputRotations) {
    return new PositionLimits(
        min, max, current, overTempCelsius, outputRotations, forwardHardStop, reverseHardStop);
  }

  /**
   * The hard stop at one end, if there is one.
   *
   * @param side which end of travel
   * @return the sensor, or empty
   */
  public Optional<SensorSpec> hardStop(HardStop side) {
    return side.isForward() ? forwardHardStop : reverseHardStop;
  }

  /**
   * Whether the hard stop at this end is wired into the <em>motor controller</em>.
   *
   * <p>This is not a cosmetic question. It drives the backend's status-signal subscription: the
   * limit signal is subscribed exactly when a motor limit is declared, which is what makes the
   * "zero extra CAN traffic — the motor already reports it" claim true instead of leaving the
   * corresponding input frozen at its power-on value forever.
   *
   * @param side which end of travel
   * @return true when that end has a controller-wired limit switch
   */
  public boolean usesMotorLimit(HardStop side) {
    return hardStop(side).map(SensorSpec::isMotorLimit).orElse(false);
  }

  /**
   * The soft limits as a labelled, ordered range in user units.
   *
   * <p>Metres for a linear mechanism, degrees for a rotary one — the units layer's own convention,
   * so the number in a range's description is the number a team typed.
   *
   * @return the range, or a NaN range labelled {@code "?"} when the two bounds are not the same kind
   *     of measure (which {@link #problems()} reports as an error)
   */
  public Range range() {
    if (min instanceof Distance a && max instanceof Distance b) {
      return Range.of(a, b);
    }
    if (min instanceof Angle a && max instanceof Angle b) {
      return Range.of(a, b);
    }
    return Range.of(Double.NaN, Double.NaN, "?");
  }

  /**
   * Whether the bounds are distances, and the mechanism is therefore linear.
   *
   * @return true when both bounds are {@link Distance}
   */
  public boolean isLinear() {
    return min instanceof Distance && max instanceof Distance;
  }

  /**
   * Whether the bounds are angles, and the mechanism is therefore rotary.
   *
   * @return true when both bounds are {@link Angle}
   */
  public boolean isRotary() {
    return min instanceof Angle && max instanceof Angle;
  }

  /**
   * Every problem visible from these limits alone.
   *
   * <p><b>Never throws, never returns null.</b> The cross-check that matters most — do these bounds
   * match the axis the mechanism actually has? — needs the axis, so it belongs to validation, not
   * here.
   *
   * @return the problems, in declaration order; empty when the limits are fine
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>();

    if (!isLinear() && !isRotary()) {
      out.add(
          "PositionLimits: the two soft limits are not the same kind of measure (min is a "
              + min.getClass().getSimpleName()
              + ", max is a "
              + max.getClass().getSimpleName()
              + "). A mechanism travels along a line or around a joint, not both. Fix: use two "
              + "Distances (Inches.of(0), Inches.of(55)) for a linear axis, or two Angles "
              + "(Degrees.of(-15), Degrees.of(105)) for a rotary one.");
    } else {
      Range r = range();
      out.addAll(r.problems());
      if (r.width() <= 0.0) {
        out.add(
            "PositionLimits: the soft limits are "
                + r.describe()
                + ", which leaves no travel at all. The mechanism can never move off its one legal "
                + "position. Fix: check that min and max are not equal and are not swapped.");
      }
    }

    out.addAll(current.problems("PositionLimits"));

    if (!Double.isFinite(overTempCelsius) || overTempCelsius <= 0.0) {
      out.add(
          "PositionLimits: overTempCelsius = "
              + overTempCelsius
              + ", which must be a finite temperature above zero. Fix: "
              + kDefaultOverTempCelsius
              + " is the default and sits below the point at which a Kraken or a NEO derates "
              + "itself.");
    }
    if (!Double.isFinite(followerToleranceRot) || followerToleranceRot <= 0.0) {
      out.add(
          "PositionLimits: followerToleranceRot = "
              + followerToleranceRot
              + ", which must be a finite number of output rotations above zero. Fix: "
              + kDefaultFollowerToleranceRot
              + " is the default: more than any gearbox flexes, less than a slipped belt.");
    }

    forwardHardStop.ifPresent(s -> out.addAll(s.problems()));
    reverseHardStop.ifPresent(s -> out.addAll(s.problems()));

    checkStopSide(out, HardStop.FORWARD, forwardHardStop);
    checkStopSide(out, HardStop.REVERSE, reverseHardStop);

    return List.copyOf(out);
  }

  private static void checkStopSide(
      List<String> out, HardStop side, Optional<SensorSpec> sensor) {
    if (sensor.isEmpty()) {
      return;
    }
    SensorSpec s = sensor.get();
    if (s instanceof SensorSpec.MotorLimit m && m.side().toHardStop() != side) {
      out.add(
          "PositionLimits: the "
              + side
              + " hard stop is a motor limit switch declared as "
              + m.side()
              + ". The two must agree, or the device will stop the motor at the wrong end of "
              + "travel, which on an elevator means it refuses to come down and drives into the "
              + "top. Fix: SensorSpec.motorLimit(SensorSpec.Limit."
              + side
              + ").");
    }
  }

  /**
   * The limits as the boot dump prints them, including which end has what kind of stop.
   *
   * @return a multi-line, human-readable description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("soft limits       ").append(range().describe());
    sb.append(System.lineSeparator()).append("current           ").append(current.describe());
    sb.append(
        String.format(
            Locale.ROOT,
            "%sover temperature  warn above %.0f C",
            System.lineSeparator(),
            overTempCelsius));
    sb.append(
        String.format(
            Locale.ROOT,
            "%sfollower drift    warn beyond %.3f output rot",
            System.lineSeparator(),
            followerToleranceRot));
    sb.append(System.lineSeparator())
        .append("forward hard stop ")
        .append(forwardHardStop.map(SensorSpec::describe).orElse("none"));
    sb.append(System.lineSeparator())
        .append("reverse hard stop ")
        .append(reverseHardStop.map(SensorSpec::describe).orElse("none"));
    return sb.toString();
  }

  private static final String kNullBound =
      "PositionLimits: a soft limit must not be null. Type the unit you think in: Inches.of(55) "
          + "for a linear axis, Degrees.of(105) for a rotary one.";

  private static final String kNullStop =
      "PositionLimits: a hard-stop Optional must not be null. Use Optional.empty() for an end with "
          + "no switch on it.";

  /**
   * Reads the lower bound in metres, for a caller that has already established the axis is linear.
   *
   * @return metres, or {@code NaN} when the bound is not a distance
   */
  public double minMeters() {
    return min instanceof Distance d ? d.in(Meters) : Double.NaN;
  }

  /**
   * Reads the upper bound in metres.
   *
   * @return metres, or {@code NaN} when the bound is not a distance
   */
  public double maxMeters() {
    return max instanceof Distance d ? d.in(Meters) : Double.NaN;
  }

  /**
   * Reads the lower bound in degrees, the user unit for a rotary axis.
   *
   * @return degrees, or {@code NaN} when the bound is not an angle
   */
  public double minDegrees() {
    return min instanceof Angle a ? a.in(Degrees) : Double.NaN;
  }

  /**
   * Reads the upper bound in degrees.
   *
   * @return degrees, or {@code NaN} when the bound is not an angle
   */
  public double maxDegrees() {
    return max instanceof Angle a ? a.in(Degrees) : Double.NaN;
  }
}

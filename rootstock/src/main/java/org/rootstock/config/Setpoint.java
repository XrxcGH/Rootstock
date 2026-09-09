package org.rootstock.config;

import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Radians;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Distance;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A named, unit-typed goal — and whether it actually resolved.
 *
 * <p>This replaces the enum-with-a-double-payload that every team writes five times with five
 * different accessor names ({@code getHeight()}, {@code getAngle()}, {@code inches()}). A setpoint
 * carries the mechanism it belongs to, so a compiler-checked handle
 * ({@code RobotConfig.ELEVATOR_L4}) can be passed straight to a superstructure and cannot be
 * confused with the arm's.
 *
 * <h2>The resolution flag, and why it is not an exception</h2>
 *
 * <p>{@code config.setpoint("L4 ")} — with a trailing space — is a typo, and typos happen at 11pm.
 * A lookup that throws would surface as {@code ExceptionInInitializerError} out of
 * {@code RobotConfig.<clinit>}, because every setpoint handle in every example is a
 * {@code public static final} field: robot code would never start, the driver station would show
 * red, and the carefully written message would end up as a nested cause under three frames of JVM
 * class-initialisation noise.
 *
 * <p>So an unknown name yields an <b>unresolved</b> setpoint instead. It is a real object, it can be
 * assigned to a field, the robot boots, and the registration pipeline prints the problem in plain
 * English before anyone presses a button. A command factory handed an unresolved setpoint refuses
 * with a named no-op and a sticky error — never a silent nothing, and never a crashed scheduler.
 *
 * @param mechanism the name of the mechanism this goal belongs to
 * @param name the goal's name, as the team wrote it
 * @param value the goal, as a {@link Distance} or an {@link Angle}
 * @param resolved whether the name was found in the mechanism's setpoint list
 */
public record Setpoint(String mechanism, String name, Measure<?> value, boolean resolved) {

  /**
   * Normalises the mechanism name and rejects a null value.
   *
   * <p>The goal's own name is deliberately <b>not</b> trimmed. Trimming it would make
   * {@code setpoint("L4 ")} resolve, or — worse — fail with a message that echoes back {@code "L4"}
   * and looks exactly like the name that does exist, which turns a two-second fix into twenty
   * minutes of staring at two identical strings.
   */
  public Setpoint {
    mechanism = mechanism == null || mechanism.isBlank() ? "(unnamed mechanism)" : mechanism.trim();
    name = name == null ? "" : name;
    value =
        Objects.requireNonNull(
            value,
            "Setpoint: the value must not be null. Type the unit you think in: Inches.of(52.5) "
                + "for a height, Degrees.of(35) for an angle.");
  }

  /**
   * A resolved goal on a linear mechanism.
   *
   * @param mechanism the mechanism's name
   * @param name the goal's name
   * @param value the height
   * @return the setpoint
   */
  public static Setpoint of(String mechanism, String name, Distance value) {
    return new Setpoint(mechanism, name, value, true);
  }

  /**
   * A resolved goal on a rotary mechanism.
   *
   * @param mechanism the mechanism's name
   * @param name the goal's name
   * @param value the angle
   * @return the setpoint
   */
  public static Setpoint of(String mechanism, String name, Angle value) {
    return new Setpoint(mechanism, name, value, true);
  }

  /**
   * The answer to a lookup that failed: a real object carrying the failure, never a throw and never
   * a null.
   *
   * <p>Package-private on purpose. Only a mechanism config can honestly say that a name did not
   * resolve, because only it knows what names exist; letting anything else mint one would make the
   * flag mean "somebody decided this was wrong" instead of "the lookup failed".
   *
   * @param mechanism the mechanism the lookup was made against
   * @param name the name that was not found
   * @return an unresolved setpoint whose value is NaN metres
   */
  static Setpoint unresolved(String mechanism, String name) {
    return new Setpoint(mechanism, name, Meters.of(Double.NaN), false);
  }

  /**
   * Whether this setpoint names a goal that exists.
   *
   * @return the resolution flag
   */
  public boolean isResolved() {
    return resolved;
  }

  /**
   * The goal in user units — metres for a linear mechanism, <b>degrees</b> for a rotary one.
   *
   * @return the value in user units, or {@code NaN} when unresolved
   */
  public double valueUser() {
    if (value instanceof Distance d) {
      return d.in(Meters);
    }
    if (value instanceof Angle a) {
      return a.in(Degrees);
    }
    return Double.NaN;
  }

  /**
   * The goal in SI — metres or radians.
   *
   * @return the value in SI units, or {@code NaN} when unresolved
   */
  public double valueSi() {
    if (value instanceof Distance d) {
      return d.in(Meters);
    }
    if (value instanceof Angle a) {
      return a.in(Radians);
    }
    return Double.NaN;
  }

  /**
   * The goal as a distance, when it is one.
   *
   * @return the distance, or empty
   */
  public Optional<Distance> asDistance() {
    return value instanceof Distance d ? Optional.of(d) : Optional.empty();
  }

  /**
   * The goal as an angle, when it is one.
   *
   * @return the angle, or empty
   */
  public Optional<Angle> asAngle() {
    return value instanceof Angle a ? Optional.of(a) : Optional.empty();
  }

  /**
   * Every problem visible from this setpoint alone.
   *
   * <p><b>Never throws, never returns null.</b> Whether the value is inside the mechanism's soft
   * limits needs the limits, so it belongs to validation.
   *
   * @return the problems; empty when the setpoint is fine
   */
  public List<String> problems() {
    if (!resolved) {
      String whitespaceNote =
          name.equals(name.strip())
              ? ""
              : " NOTE: the name you asked for has leading or trailing whitespace. \""
                  + name
                  + "\" is not the same string as \""
                  + name.strip()
                  + "\", and that is almost certainly the whole problem.";
      return List.of(
          mechanism
              + ": there is no setpoint named \""
              + name
              + "\". Nothing that asks for it will move, and every command that targets it will "
              + "refuse with this message rather than doing nothing quietly. Fix: check the "
              + "spelling and the capitalisation against the .setpoint(...) calls in this "
              + "mechanism's config."
              + whitespaceNote);
    }
    if (name.isEmpty()) {
      return List.of(
          mechanism
              + ": a setpoint has an empty name, so nothing can look it up. Fix: give it a name a "
              + "driver would recognise, like \"L4\" or \"STOW\".");
    }
    if (Double.isNaN(valueUser())) {
      return List.of(
          mechanism
              + " setpoint \""
              + name
              + "\": the value is a "
              + value.getClass().getSimpleName()
              + ", which is neither a Distance nor an Angle. Fix: Inches.of(52.5) for a height, "
              + "Degrees.of(35) for an angle.");
    }
    return List.of();
  }

  /**
   * The setpoint as the boot dump and the tuning table print it.
   *
   * @return a human-readable description
   */
  public String describe() {
    if (!resolved) {
      return mechanism + "." + name + " = UNRESOLVED (no such setpoint)";
    }
    if (value instanceof Distance d) {
      return String.format(Locale.ROOT, "%s.%s = %.4f m", mechanism, name, d.in(Meters));
    }
    if (value instanceof Angle a) {
      return String.format(Locale.ROOT, "%s.%s = %.2f deg", mechanism, name, a.in(Degrees));
    }
    return mechanism + "." + name + " = " + value;
  }

  @Override
  public String toString() {
    return describe();
  }
}

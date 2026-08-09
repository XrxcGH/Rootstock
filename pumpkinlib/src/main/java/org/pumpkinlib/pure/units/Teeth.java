package org.pumpkinlib.pure.units;

/**
 * A gear's tooth count, as a type.
 *
 * <p>It exists for exactly one reason, and it is the reason {@link Reduction#ofGears} exists: two
 * bare ints in a call have no order a reader can verify, and getting that order backwards inverts
 * every ratio in the robot. {@code Reduction.ofGears(Teeth.of(58), Teeth.of(10))} reads the same way
 * round as the gearbox does — output side first, motor side second — and if you are not sure which
 * way {@link Reduction#ofTeeth} goes, this is the overload that says so at the call site.
 *
 * <p><b>Tier 0.</b> Zero {@code edu.wpi.first} imports (ArchUnit rule 8).
 *
 * @param count the number of teeth; strictly positive
 */
public record Teeth(int count) {

  /**
   * Validates the tooth count.
   *
   * @throws IllegalArgumentException if {@code count} is not strictly positive
   */
  public Teeth {
    if (count <= 0) {
      throw new IllegalArgumentException(
          "Teeth: count was "
              + count
              + "; a gear has a strictly positive number of teeth. "
              + "Fix: direction of travel is NEVER expressed as a negative gear — it belongs on "
              + "MotorGroup.leaderInverted().");
    }
  }

  /**
   * A tooth count.
   *
   * @param count the number of teeth; strictly positive
   * @return the value
   */
  public static Teeth of(int count) {
    return new Teeth(count);
  }
}

package org.rootstock.units;

/**
 * Which SI quantity an axis lives in — and therefore what one unit of every gain means.
 *
 * <p><b>Why this type exists at all.</b> Layer three of the four-layer unit contract (§4.1) is SI,
 * and it is the layer an earlier revision of the design leaked without naming: it fed a V/rad
 * {@code kP} into a controller whose error was in degrees (a 57.3&times; error) and handed {@code
 * ArmFeedforward} degrees where its contract requires radians from horizontal. Naming the domain
 * makes the conversion a method call with one implementation instead of a fact somebody has to
 * remember.
 *
 * <p>The constant names carry their unit on purpose. {@code LINEAR} and {@code ANGULAR} would be a
 * different spelling of the same two ideas that tells you nothing about whether the number in your
 * hand is degrees or radians; {@code ROTATIONAL_RADIANS} tells you.
 *
 * <p>The direction of travel is always <b>user &rarr; SI</b> for {@link #toSi(double)} and
 * <b>SI &rarr; user</b> for {@link #fromSi(double)}. User units are metres for a linear axis and
 * <b>degrees</b> for a rotary one; SI units are metres and <b>radians</b>. So {@code toSi} is the
 * identity on a linear axis and {@code Math.toRadians} on a rotary one, which is the entire content
 * of this enum and the reason it is only ever applied in one place.
 */
public enum SiDomain {

  /**
   * Metres, both in user units and in SI. {@code toSi}/{@code fromSi} are the identity, and gains
   * are volts-per-metre.
   */
  LINEAR_METERS {
    @Override
    public double toSi(double user) {
      return user;
    }

    @Override
    public double fromSi(double si) {
      return si;
    }

    @Override
    public String label() {
      return "m";
    }

    @Override
    public String userLabel() {
      return "m";
    }
  },

  /**
   * Degrees in user units, radians in SI. {@code toSi} is {@code Math.toRadians}, and gains are
   * volts-per-radian.
   *
   * <p>The asymmetry is deliberate and is decision P3: <b>CORE publishes degrees</b>, because a
   * degree is what a student types, reads on a dashboard and argues about in the pits, while
   * <b>every gain and every RIO-side controller is radians</b>, because that is what WPILib's
   * {@code ArmFeedforward}, {@code TrapezoidProfile} and system identification all assume.
   * Declaring radians over a degree stream is a 57.3&times; mislabel, so the two are never confused
   * here: the label for the user layer is {@code "deg"} and the label for this domain is {@code
   * "rad"}.
   */
  ROTATIONAL_RADIANS {
    @Override
    public double toSi(double user) {
      return Math.toRadians(user);
    }

    @Override
    public double fromSi(double si) {
      return Math.toDegrees(si);
    }

    @Override
    public String label() {
      return "rad";
    }

    @Override
    public String userLabel() {
      return "deg";
    }
  };

  /**
   * Converts a user-unit quantity into SI.
   *
   * <p>Works for position, velocity, acceleration and jerk alike, because the factor is a constant
   * scale in all four.
   *
   * @param user the value in user units — metres, or degrees
   * @return the value in SI units — metres, or radians
   */
  public abstract double toSi(double user);

  /**
   * Converts an SI quantity back into user units. The exact inverse of {@link #toSi(double)}.
   *
   * @param si the value in SI units — metres, or radians
   * @return the value in user units — metres, or degrees
   */
  public abstract double fromSi(double si);

  /**
   * The SI unit's short label, for {@code describe()} output, telemetry keys and error messages.
   *
   * @return {@code "m"} or {@code "rad"}
   */
  public abstract String label();

  /**
   * The user unit's short label — the other half of the pair, so an error message can print both
   * sides of a conversion without the caller having to know which domain it is in.
   *
   * @return {@code "m"} or {@code "deg"}
   */
  public abstract String userLabel();
}

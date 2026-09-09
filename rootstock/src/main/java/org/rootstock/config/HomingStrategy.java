package org.rootstock.config;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Degrees;
import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import edu.wpi.first.units.Measure;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Voltage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.rootstock.control.PositionReference;

/**
 * How a mechanism's position becomes <i>true</i> after a power cycle.
 *
 * <p>A rotor encoder reads zero at boot no matter where the mechanism physically is. Every strategy
 * below is a different answer to "so where is it, actually?", and naming the answer is the point:
 * six IO classes in {@code 0000-XXXX-Robot-Template} ({@code ArmIOTalonFX:76},
 * {@code WristIOTalonFX:66}, {@code IntakeIOTalonFX:48}, {@code TurretIOTalonFX:62},
 * {@code ClimberIOTalonFX:60}, {@code ElevatorIOTalonFX:83}) seed a constant at boot and document
 * the assumption in a comment. {@link AssumeAtBoot} is that same choice made <b>audible</b> — it
 * shows up in a diff, in {@code describe()}, and as a tier-2 warning on the driver station every
 * single boot.
 *
 * <h2>Package note — a deliberate, reported deviation</h2>
 *
 * <p>{@code design/01} §6.3 places this type in {@code org.rootstock.mechanism}. It lives here
 * instead because {@link PositionConfig} has a {@code homing} component, so {@code config} would
 * have to depend on {@code mechanism} while {@code mechanism} already depends on {@code config} —
 * a package cycle. {@code HomingStrategy} is a pure value type with no runtime behaviour, so
 * {@code config} is where it can live without one. The mechanism package should import it from
 * here rather than declare a second copy.
 *
 * <h2>Guarantees the library makes for every strategy</h2>
 *
 * <p>These are hard because this is a mechanism-damage surface (design/01 §6.3): homing runs only
 * while enabled and never in safe mode; device soft limits are suspended for the routine but the
 * Java-side goal clamp is not; {@link CurrentSpike#volts()} is magnitude-clamped to
 * {@value #kMaxHomingVolts} V and the stator limit is temporarily reduced to
 * {@code currentThresholdAmps × }{@value #kCurrentLimitFactor} so a mis-signed direction pushes
 * gently rather than destructively; a timeout leaves {@code isHomed()} false so {@code atGoal()}
 * stays false; and every abort names its cause.
 */
public sealed interface HomingStrategy
    permits
        HomingStrategy.AbsoluteSeed,
        HomingStrategy.CurrentSpike,
        HomingStrategy.LimitSwitch,
        HomingStrategy.AssumeAtBoot,
        HomingStrategy.Composite {

  /**
   * The hard ceiling on homing drive voltage, in volts.
   *
   * <p>Homing drives deliberately into a hard stop. A student who writes the wrong sign should hear
   * a gentle bump, not a bent shaft, so the magnitude is clamped here rather than trusted.
   */
  double kMaxHomingVolts = 3.0;

  /**
   * How much headroom the temporary stator limit gets over the trip threshold, as a multiplier.
   *
   * <p>Below 1.0 the limit would clip before the spike could be detected and homing would never
   * finish; far above it the clamp stops protecting anything.
   */
  double kCurrentLimitFactor = 1.5;

  /** Which way to drive to find the reference. */
  enum Direction {
    /** Toward increasing position — the soft-limit maximum end. */
    FORWARD,

    /** Toward decreasing position — the soft-limit minimum end. Usual for an elevator. */
    REVERSE;

    /**
     * The sign this direction applies to the drive voltage.
     *
     * @return {@code +1.0} for {@link #FORWARD}, {@code -1.0} for {@link #REVERSE}
     */
    public double sign() {
      return this == FORWARD ? 1.0 : -1.0;
    }

    /**
     * The other end of the travel.
     *
     * @return the opposite direction
     */
    public Direction opposite() {
      return this == FORWARD ? REVERSE : FORWARD;
    }

    /**
     * The hard-stop side this direction drives into.
     *
     * @return {@link HardStop#FORWARD} or {@link HardStop#REVERSE}
     */
    public HardStop hardStop() {
      return this == FORWARD ? HardStop.FORWARD : HardStop.REVERSE;
    }
  }

  // -----------------------------------------------------------------------------------------
  // The five strategies
  // -----------------------------------------------------------------------------------------

  /**
   * The rotor is seeded from an absolute sensor.
   *
   * <p>For {@link FeedbackSpec.FusedCancoder} this is a deliberate <b>no-op</b> — Phoenix fuses the
   * CANcoder into the rotor on the device, so re-seeding in Java would fight it — and
   * {@link #describe()} says so out loud rather than leaving a student wondering why nothing
   * happened. For {@code SparkAbsolute}, {@code DioAbsolute} and {@code RemoteCancoder} it performs
   * the guarded re-seed: read the absolute sensor, compare it against the rotor-derived position,
   * and refuse to seed (with a named alert) when they disagree by more than
   * {@code agreementToleranceUserUnits}, because a disagreement means one of the two ratios is
   * wrong and seeding would bake the error in.
   *
   * @param agreementToleranceUserUnits how far the absolute sensor and the rotor estimate may
   *     disagree before the seed is refused, in user units (metres or degrees); zero or negative
   *     means "seed unconditionally"
   */
  record AbsoluteSeed(double agreementToleranceUserUnits) implements HomingStrategy {

    /**
     * A copy with a different agreement tolerance.
     *
     * @param tolerance the tolerance as a distance or an angle
     * @return a new strategy; this one is unchanged
     */
    public AbsoluteSeed agreementTolerance(Measure<?> tolerance) {
      return new AbsoluteSeed(userValueOf(tolerance));
    }

    @Override
    public boolean seedsPosition() {
      return true;
    }

    @Override
    public boolean needsMotion() {
      return false;
    }

    @Override
    public boolean isTrustworthy() {
      return true;
    }

    @Override
    public List<String> problems() {
      if (Double.isNaN(agreementToleranceUserUnits)) {
        return List.of(
            "HomingStrategy.absoluteSeed: the agreement tolerance is NaN, so the guard that "
                + "compares the absolute sensor against the rotor estimate can never pass and the "
                + "mechanism would never seed. Fix: HomingStrategy.absoluteSeed() for no guard, or "
                + "absoluteSeed(Degrees.of(2.0)) to refuse a seed that disagrees by more than 2 "
                + "degrees.");
      }
      return List.of();
    }

    @Override
    public String describe() {
      if (agreementToleranceUserUnits <= 0.0) {
        return "absoluteSeed (seed from the absolute sensor, no agreement guard)";
      }
      return String.format(
          Locale.ROOT,
          "absoluteSeed (seed from the absolute sensor; refuse if it disagrees with the rotor "
              + "estimate by more than %.4f user units)",
          agreementToleranceUserUnits);
    }
  }

  /**
   * Drive gently into a hard stop until the stator current spikes, then stop, back off and seed.
   *
   * <p>This is the canonical answer for an elevator with no absolute encoder, and it is testable
   * off-robot: {@code ElevatorSim} and {@code SingleJointedArmSim} both report a current spike at
   * their travel limits, which is the entire reason homing belongs in the library rather than in
   * robot code.
   *
   * @param direction which way to drive
   * @param volts the drive magnitude in volts; the sign comes from {@code direction} and the
   *     magnitude is clamped to {@value #kMaxHomingVolts}
   * @param currentThresholdAmps stator current above which the mechanism is considered to be on the
   *     stop
   * @param debounceSeconds how long the current must stay above the threshold; stops a startup
   *     inrush from counting as a hard stop
   * @param timeoutSeconds the routine gives up after this long, leaves {@code isHomed()} false and
   *     raises {@code <name>/homing-timed-out}
   * @param seedToUserUnits the position to declare, in user units (metres or degrees)
   * @param backoffUserUnits how far to retreat off the stop before seeding, so the mechanism is not
   *     left resting on the stop with kG fighting it
   */
  record CurrentSpike(
      Direction direction,
      double volts,
      double currentThresholdAmps,
      double debounceSeconds,
      double timeoutSeconds,
      double seedToUserUnits,
      double backoffUserUnits)
      implements HomingStrategy {

    /** Normalises the direction and applies the {@value #kMaxHomingVolts} V magnitude clamp. */
    public CurrentSpike {
      direction = direction == null ? Direction.REVERSE : direction;
      volts =
          Double.isNaN(volts) ? Double.NaN : Math.min(Math.abs(volts), kMaxHomingVolts);
    }

    /**
     * A copy driving the other way.
     *
     * @param value the direction to drive
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike direction(Direction value) {
      return new CurrentSpike(
          value,
          volts,
          currentThresholdAmps,
          debounceSeconds,
          timeoutSeconds,
          seedToUserUnits,
          backoffUserUnits);
    }

    /**
     * A copy driving at a different voltage. Only the magnitude is kept; the direction supplies the
     * sign, so {@code Volts.of(-1.5)} and {@code Volts.of(1.5)} mean the same thing here.
     *
     * @param value the drive voltage
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike voltage(Voltage value) {
      return new CurrentSpike(
          direction,
          value == null ? Double.NaN : value.in(Volts),
          currentThresholdAmps,
          debounceSeconds,
          timeoutSeconds,
          seedToUserUnits,
          backoffUserUnits);
    }

    /**
     * A copy with a different trip current.
     *
     * @param value the stator current that means "we are on the stop"
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike currentThreshold(Current value) {
      return new CurrentSpike(
          direction,
          volts,
          value == null ? Double.NaN : value.in(Amps),
          debounceSeconds,
          timeoutSeconds,
          seedToUserUnits,
          backoffUserUnits);
    }

    /**
     * A copy with a different debounce window.
     *
     * @param value how long the current must stay high
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike debounce(Time value) {
      return new CurrentSpike(
          direction,
          volts,
          currentThresholdAmps,
          value == null ? Double.NaN : value.in(Seconds),
          timeoutSeconds,
          seedToUserUnits,
          backoffUserUnits);
    }

    /**
     * A copy with a different give-up time.
     *
     * @param value the timeout
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike timeout(Time value) {
      return new CurrentSpike(
          direction,
          volts,
          currentThresholdAmps,
          debounceSeconds,
          value == null ? Double.NaN : value.in(Seconds),
          seedToUserUnits,
          backoffUserUnits);
    }

    /**
     * A copy that declares a different position once the stop is found.
     *
     * @param value the position at the stop, as a distance or an angle
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike seedTo(Measure<?> value) {
      return new CurrentSpike(
          direction,
          volts,
          currentThresholdAmps,
          debounceSeconds,
          timeoutSeconds,
          userValueOf(value),
          backoffUserUnits);
    }

    /**
     * A copy that retreats a different distance off the stop before seeding.
     *
     * @param value how far to back off, as a distance or an angle
     * @return a new strategy; this one is unchanged
     */
    public CurrentSpike backoff(Measure<?> value) {
      return new CurrentSpike(
          direction,
          volts,
          currentThresholdAmps,
          debounceSeconds,
          timeoutSeconds,
          seedToUserUnits,
          Math.abs(userValueOf(value)));
    }

    /**
     * The stator limit the routine installs for its own duration.
     *
     * @return {@code currentThresholdAmps × }{@value #kCurrentLimitFactor}
     */
    public double routineStatorLimitAmps() {
      return currentThresholdAmps * kCurrentLimitFactor;
    }

    /**
     * The signed voltage actually commanded.
     *
     * @return the clamped magnitude with the direction's sign applied
     */
    public double signedVolts() {
      return volts * direction.sign();
    }

    @Override
    public boolean seedsPosition() {
      return true;
    }

    @Override
    public boolean needsMotion() {
      return true;
    }

    @Override
    public boolean isTrustworthy() {
      return true;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (Double.isNaN(volts) || volts <= 0.0) {
        out.add(
            "HomingStrategy.currentSpike: the drive voltage is "
                + volts
                + " V, so the mechanism would never move and homing would always time out. "
                + "Fix: .voltage(Volts.of(1.5)) — the sign comes from .direction(...), and the "
                + "magnitude is clamped to "
                + kMaxHomingVolts
                + " V no matter what you write.");
      }
      if (Double.isNaN(currentThresholdAmps) || currentThresholdAmps <= 0.0) {
        out.add(
            "HomingStrategy.currentSpike: the current threshold is "
                + currentThresholdAmps
                + " A, which can never be exceeded, so the routine would run until it timed out "
                + "and the mechanism would sit on its hard stop for the whole timeout. "
                + "Fix: .currentThreshold(Amps.of(30)) — pick roughly twice the free-running "
                + "current you see at this voltage; the riolog prints it while homing.");
      }
      if (Double.isNaN(debounceSeconds) || debounceSeconds < 0.0) {
        out.add(
            "HomingStrategy.currentSpike: the debounce is "
                + debounceSeconds
                + " s, which must be zero or more. A debounce of zero lets the motor's own startup "
                + "inrush read as a hard stop and seed the position in mid-air. "
                + "Fix: .debounce(Seconds.of(0.15)).");
      }
      if (Double.isNaN(timeoutSeconds) || timeoutSeconds <= 0.0) {
        out.add(
            "HomingStrategy.currentSpike: the timeout is "
                + timeoutSeconds
                + " s, so the routine could never give up and a mechanism that misses its stop "
                + "would push against it until the robot was disabled. "
                + "Fix: .timeout(Seconds.of(4.0)) — a little longer than a full-travel move.");
      }
      if (Double.isNaN(seedToUserUnits)) {
        out.add(
            "HomingStrategy.currentSpike: the seed position is NaN, so the mechanism would declare "
                + "itself to be at NaN and every goal after that would be NaN too. "
                + "Fix: .seedTo(Inches.of(0.0)) for a height, .seedTo(Degrees.of(-15)) for an "
                + "angle — it is the position the mechanism is at when it is against the stop.");
      }
      if (Double.isNaN(backoffUserUnits) || backoffUserUnits < 0.0) {
        out.add(
            "HomingStrategy.currentSpike: the backoff is "
                + backoffUserUnits
                + ", which must be zero or a positive distance. "
                + "Fix: .backoff(Inches.of(0.25)), or leave it out to seed on the stop.");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "currentSpike (%s at %.2f V, trip %.1f A for %.3f s, timeout %.1f s, seed to %.4f, "
              + "back off %.4f; stator limit temporarily %.1f A, voltage magnitude clamped to "
              + "%.1f V)",
          direction,
          volts,
          currentThresholdAmps,
          debounceSeconds,
          timeoutSeconds,
          seedToUserUnits,
          backoffUserUnits,
          routineStatorLimitAmps(),
          kMaxHomingVolts);
    }
  }

  /**
   * Drive gently until a switch asserts, then stop and seed.
   *
   * <p>Cheaper on the mechanism than {@link CurrentSpike} because nothing has to hit anything, and
   * more trustworthy because the trigger is a sensor rather than an inference.
   *
   * @param direction which way to drive
   * @param volts the drive magnitude in volts, clamped to {@value #kMaxHomingVolts}
   * @param sensor the switch that says "here"
   * @param timeoutSeconds the routine gives up after this long
   * @param seedToUserUnits the position to declare, in user units
   */
  record LimitSwitch(
      Direction direction,
      double volts,
      SensorSpec sensor,
      double timeoutSeconds,
      double seedToUserUnits)
      implements HomingStrategy {

    /** Normalises the direction and applies the {@value #kMaxHomingVolts} V magnitude clamp. */
    public LimitSwitch {
      direction = direction == null ? Direction.REVERSE : direction;
      volts = Double.isNaN(volts) ? Double.NaN : Math.min(Math.abs(volts), kMaxHomingVolts);
    }

    /**
     * A copy driving the other way.
     *
     * @param value the direction to drive
     * @return a new strategy; this one is unchanged
     */
    public LimitSwitch direction(Direction value) {
      return new LimitSwitch(value, volts, sensor, timeoutSeconds, seedToUserUnits);
    }

    /**
     * A copy driving at a different voltage; only the magnitude is kept.
     *
     * @param value the drive voltage
     * @return a new strategy; this one is unchanged
     */
    public LimitSwitch voltage(Voltage value) {
      return new LimitSwitch(
          direction,
          value == null ? Double.NaN : value.in(Volts),
          sensor,
          timeoutSeconds,
          seedToUserUnits);
    }

    /**
     * A copy with a different give-up time.
     *
     * @param value the timeout
     * @return a new strategy; this one is unchanged
     */
    public LimitSwitch timeout(Time value) {
      return new LimitSwitch(
          direction,
          volts,
          sensor,
          value == null ? Double.NaN : value.in(Seconds),
          seedToUserUnits);
    }

    /**
     * A copy that declares a different position once the switch asserts.
     *
     * @param value the position at the switch, as a distance or an angle
     * @return a new strategy; this one is unchanged
     */
    public LimitSwitch seedTo(Measure<?> value) {
      return new LimitSwitch(direction, volts, sensor, timeoutSeconds, userValueOf(value));
    }

    /**
     * The signed voltage actually commanded.
     *
     * @return the clamped magnitude with the direction's sign applied
     */
    public double signedVolts() {
      return volts * direction.sign();
    }

    @Override
    public boolean seedsPosition() {
      return true;
    }

    @Override
    public boolean needsMotion() {
      return true;
    }

    @Override
    public boolean isTrustworthy() {
      return true;
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (sensor == null) {
        out.add(
            "HomingStrategy.limitSwitch: no sensor was given, so nothing can tell the routine when "
                + "to stop. Fix: HomingStrategy.limitSwitch(SensorSpec.motorLimit("
                + "SensorSpec.Limit.REVERSE)) for a switch wired to the motor controller, or "
                + "SensorSpec.dio(0, true) for one wired to the roboRIO.");
      } else {
        for (String problem : sensor.problems()) {
          out.add("HomingStrategy.limitSwitch sensor: " + problem);
        }
      }
      if (Double.isNaN(volts) || volts <= 0.0) {
        out.add(
            "HomingStrategy.limitSwitch: the drive voltage is "
                + volts
                + " V, so the mechanism would never reach the switch. "
                + "Fix: .voltage(Volts.of(1.0)); the sign comes from .direction(...).");
      }
      if (Double.isNaN(timeoutSeconds) || timeoutSeconds <= 0.0) {
        out.add(
            "HomingStrategy.limitSwitch: the timeout is "
                + timeoutSeconds
                + " s, so a routine that never sees the switch would drive forever. "
                + "Fix: .timeout(Seconds.of(4.0)).");
      }
      if (Double.isNaN(seedToUserUnits)) {
        out.add(
            "HomingStrategy.limitSwitch: the seed position is NaN. "
                + "Fix: .seedTo(Inches.of(0.0)) — the position the mechanism is at when the switch "
                + "asserts.");
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "limitSwitch (%s at %.2f V until %s, timeout %.1f s, seed to %.4f)",
          direction,
          volts,
          sensor == null ? "(no sensor)" : sensor.describe(),
          timeoutSeconds,
          seedToUserUnits);
    }
  }

  /**
   * Declare a position at boot and hope the mechanism is really there.
   *
   * <p>Named so it is impossible to make this choice by accident: it is what happens implicitly in
   * six IO classes of {@code 0000-XXXX-Robot-Template}, and it is wrong the moment somebody moves
   * the mechanism by hand while the robot is disabled — after which cosine gravity compensation
   * pushes the wrong way. It always raises the tier-2 warning of {@code design/01} §5.6.
   *
   * @param seedToUserUnits the position to assume at boot, in user units (metres or degrees)
   */
  record AssumeAtBoot(double seedToUserUnits) implements HomingStrategy {

    @Override
    public boolean seedsPosition() {
      return true;
    }

    @Override
    public boolean needsMotion() {
      return false;
    }

    @Override
    public boolean isTrustworthy() {
      return false;
    }

    @Override
    public List<String> problems() {
      if (Double.isNaN(seedToUserUnits)) {
        return List.of(
            "HomingStrategy.assumeAtBoot: the assumed position is NaN, so the mechanism would boot "
                + "believing it is at NaN and every goal would be NaN. "
                + "Fix: HomingStrategy.assumeAtBoot(Degrees.of(90)).");
      }
      return List.of();
    }

    @Override
    public String describe() {
      return String.format(
          Locale.ROOT,
          "assumeAtBoot (%.4f user units, ASSUMED — no sensor confirms it)",
          seedToUserUnits);
    }
  }

  /**
   * Try each strategy in order; the first that succeeds wins.
   *
   * <p>The useful shape is "absolute encoder if it is there, otherwise drive into the stop", which
   * lets one config serve a practice bot without a CANcoder and a comp bot with one.
   *
   * @param strategies the strategies to try, in order
   */
  record Composite(List<HomingStrategy> strategies) implements HomingStrategy {

    /** Defensively copies the list and normalises a null to empty. */
    public Composite {
      strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }

    @Override
    public boolean seedsPosition() {
      return strategies.stream().anyMatch(HomingStrategy::seedsPosition);
    }

    @Override
    public boolean needsMotion() {
      return strategies.stream().anyMatch(HomingStrategy::needsMotion);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A composite is only as trustworthy as its <b>least</b> trustworthy member, because that is
     * the one that runs when the others fail — which is exactly the case you are configuring for.
     */
    @Override
    public boolean isTrustworthy() {
      return !strategies.isEmpty() && strategies.stream().allMatch(HomingStrategy::isTrustworthy);
    }

    @Override
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (strategies.isEmpty()) {
        out.add(
            "HomingStrategy.firstOf: no strategies were given, so the mechanism would never home "
                + "and isHomed() would stay false forever. Fix: pass at least one, for example "
                + "HomingStrategy.firstOf(HomingStrategy.absoluteSeed(), "
                + "HomingStrategy.currentSpike()).");
      }
      for (int i = 0; i < strategies.size(); i++) {
        for (String problem : strategies.get(i).problems()) {
          out.add("HomingStrategy.firstOf[" + i + "]: " + problem);
        }
      }
      return List.copyOf(out);
    }

    @Override
    public String describe() {
      List<String> parts = new ArrayList<>(strategies.size());
      for (HomingStrategy strategy : strategies) {
        parts.add(strategy.describe());
      }
      return "firstOf [" + String.join(" then ", parts) + "]";
    }
  }

  // -----------------------------------------------------------------------------------------
  // Factories
  // -----------------------------------------------------------------------------------------

  /**
   * Seed from an absolute sensor with no agreement guard.
   *
   * @return the strategy
   */
  static AbsoluteSeed absoluteSeed() {
    return new AbsoluteSeed(0.0);
  }

  /**
   * Seed from an absolute sensor, refusing the seed when it disagrees with the rotor estimate.
   *
   * @param agreementTolerance how far they may disagree, as a distance or an angle
   * @return the strategy
   */
  static AbsoluteSeed absoluteSeed(Measure<?> agreementTolerance) {
    return new AbsoluteSeed(userValueOf(agreementTolerance));
  }

  /**
   * A current-spike home with the library's defaults, ready to be refined by chaining.
   *
   * <p>Every refinement method returns a complete {@link CurrentSpike}, so the chain can stop at any
   * point and the intermediate value is always a usable strategy. Defaults: drive
   * {@link Direction#REVERSE} at 1.5 V, trip at 30 A held for 0.15 s, give up after 4 s, seed to
   * zero, no backoff.
   *
   * @return the strategy
   */
  static CurrentSpike currentSpike() {
    return new CurrentSpike(Direction.REVERSE, 1.5, 30.0, 0.15, 4.0, 0.0, 0.0);
  }

  /**
   * A limit-switch home with the library's defaults, ready to be refined by chaining.
   *
   * <p>Defaults: drive {@link Direction#REVERSE} at 1.0 V, give up after 4 s, seed to zero.
   *
   * @param sensor the switch that says "here"
   * @return the strategy
   */
  static LimitSwitch limitSwitch(SensorSpec sensor) {
    return new LimitSwitch(Direction.REVERSE, 1.0, sensor, 4.0, 0.0);
  }

  /**
   * Assume a height at boot. Always warns.
   *
   * @param assumed the height the mechanism is assumed to be resting at
   * @return the strategy
   */
  static AssumeAtBoot assumeAtBoot(Distance assumed) {
    return new AssumeAtBoot(assumed == null ? Double.NaN : assumed.in(Meters));
  }

  /**
   * Assume an angle at boot. Always warns.
   *
   * @param assumed the angle the mechanism is assumed to be resting at
   * @return the strategy
   */
  static AssumeAtBoot assumeAtBoot(Angle assumed) {
    return new AssumeAtBoot(assumed == null ? Double.NaN : assumed.in(Degrees));
  }

  /**
   * Try each strategy in order; the first that succeeds wins.
   *
   * @param strategies the strategies to try, in order
   * @return the composite
   */
  static Composite firstOf(HomingStrategy... strategies) {
    return new Composite(strategies == null ? List.of() : Arrays.asList(strategies));
  }

  // -----------------------------------------------------------------------------------------
  // The interface itself
  // -----------------------------------------------------------------------------------------

  /**
   * Whether this strategy ever writes a position onto the mechanism.
   *
   * @return true for every strategy that has a reference to seed from
   */
  boolean seedsPosition();

  /**
   * Whether running this strategy moves the mechanism.
   *
   * <p>A strategy that moves cannot run while disabled and cannot run in safe mode, and the
   * superstructure has to know that before it schedules anything else.
   *
   * @return true for {@link CurrentSpike} and {@link LimitSwitch}
   */
  boolean needsMotion();

  /**
   * Whether the position this strategy produces can be believed.
   *
   * <p>False only for {@link AssumeAtBoot}, which is an assertion rather than a measurement. The
   * tuning wizard refuses to tune a mechanism whose reference is not trustworthy, and validation
   * raises a tier-2 warning for it on every boot.
   *
   * @return true when a sensor confirms the position
   */
  boolean isTrustworthy();

  /**
   * The {@link PositionReference} this strategy produces once it has run.
   *
   * <p>This is the bridge to {@code org.rootstock.control.TuningTarget}, which asks the same
   * question in the tuning wizard's vocabulary.
   *
   * @param feedback the mechanism's feedback spec, because an absolute seed against a fused
   *     CANcoder is a different reference from one against a DIO encoder
   * @return the equivalent position reference
   */
  default PositionReference positionReference(FeedbackSpec feedback) {
    if (this instanceof AssumeAtBoot assume) {
      return new PositionReference.AssumeAtBoot(assume.seedToUserUnits());
    }
    if (feedback != null && feedback.isAbsolute()) {
      return feedback.positionReference();
    }
    if (this instanceof AbsoluteSeed) {
      return new PositionReference.RotorOnly();
    }
    // A current-spike or limit-switch home is a homing event against a physical stop; whether it
    // has actually completed this power cycle is runtime state the mechanism owns, so the config
    // hands back the shape and the mechanism supplies the predicate.
    return new PositionReference.HomedAgainstSwitch(() -> false);
  }

  /**
   * Everything wrong with this strategy, visible from the strategy alone.
   *
   * <p><b>Never throws, never returns null</b> — the collected-error contract of {@code design/01}
   * §5.6. Whether the seed position is inside the mechanism's soft limits needs the limits, so that
   * check belongs to {@link Validation}.
   *
   * @return the problems; empty when the strategy is fine
   */
  List<String> problems();

  /**
   * The strategy as the boot dump prints it.
   *
   * @return a one-line human-readable description
   */
  String describe();

  /**
   * A {@link Distance} or {@link Angle} in user units — metres or <b>degrees</b>.
   *
   * <p>Shared by every {@code seedTo}/{@code backoff}/{@code agreementTolerance} setter so that
   * "user units" means one thing across the whole file. Degrees rather than radians because that is
   * what {@link org.rootstock.units.RotaryAxis} declares users think in.
   *
   * @param measure the measure, possibly null
   * @return metres, degrees, or NaN when the measure is null or is neither
   */
  private static double userValueOf(Measure<?> measure) {
    if (measure instanceof Distance distance) {
      return distance.in(Meters);
    }
    if (measure instanceof Angle angle) {
      return angle.in(Degrees);
    }
    return Double.NaN;
  }
}

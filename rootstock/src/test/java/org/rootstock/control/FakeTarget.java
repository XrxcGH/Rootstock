package org.rootstock.control;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.SiDomain;

/**
 * A hand-driven {@link TuningTarget} whose every reading is a field a test writes.
 *
 * <p><strong>Why a fake and not a simulated mechanism.</strong> The properties under test in this
 * milestone are refusals: the supervisor must abort when a sensor reads NaN, when position and
 * velocity disagree, when current stays high, when nothing moves. A physical simulation cannot
 * produce any of those on demand — it is, by construction, self-consistent — so a test built on one
 * would exercise the happy path and call it coverage. Every field here is directly settable, so each
 * of the twelve abort conditions can be provoked in isolation and nothing else can trip first.
 *
 * <p>{@link #setVoltage(double)} only records. That is deliberate: this class must never move a
 * value the supervisor then reads back, because a fake that closes the loop would let a broken
 * supervisor look correct.
 *
 * <p>Public and in {@code org.rootstock.control} because the tuning tests need it too, and a second
 * copy of a fake is a second thing to keep in step with the interface.
 */
public final class FakeTarget implements TuningTarget {

  /** Every voltage the supervisor commanded, oldest first. */
  public final List<Double> commanded = new ArrayList<>();

  /** The order in which {@code restoreNeutralMode} and {@code stop} were called. */
  public final List<String> abortOrder = new ArrayList<>();

  /** How many times {@link #stop()} has been called. */
  public int stops;

  /** How many times {@link #restoreNeutralMode()} has been called. */
  public int neutralRestores;

  private String m_name = "Fake";
  private MechanismArchetype m_archetype = MechanismArchetype.ELEVATOR;
  private SiDomain m_domain = SiDomain.LINEAR_METERS;
  private ControlLocation m_location = ControlLocation.RIO_FULL;
  private PositionReference m_reference = new PositionReference.Absolute("test encoder");
  private TravelLimits m_limits = new TravelLimits(0.0, 1.4, 0.04);
  private PlantPrior m_prior =
      PlantPrior.elevator(DCMotor.getKrakenX60Foc(2), Reduction.of(12.0), 9.0, 0.0223);
  private Gains m_gains = Gains.pid(0, 0, 0);
  private double m_position = 0.7;
  private double m_velocity = 0.0;
  private double m_tolerance = 0.005;
  private boolean m_homed = true;
  private OptionalDouble m_amps = OptionalDouble.of(10.0);
  private OptionalDouble m_absolute = OptionalDouble.empty();
  private Optional<NeutralMode> m_neutral = Optional.of(NeutralMode.BRAKE);
  private boolean m_stopThrows;

  /**
   * Names this mechanism.
   *
   * @param name the tuning name
   * @return this
   */
  public FakeTarget named(String name) {
    m_name = name;
    return this;
  }

  /**
   * Sets the archetype, which decides whether position aborts apply at all.
   *
   * @param archetype the archetype
   * @return this
   */
  public FakeTarget archetype(MechanismArchetype archetype) {
    m_archetype = archetype;
    return this;
  }

  /**
   * Sets the SI domain, which changes the unit label and the envelope guard floor.
   *
   * @param domain metres or radians
   * @return this
   */
  public FakeTarget domain(SiDomain domain) {
    m_domain = domain;
    return this;
  }

  /**
   * Sets where the closed loop runs.
   *
   * @param location the location
   * @return this
   */
  public FakeTarget location(ControlLocation location) {
    m_location = location;
    return this;
  }

  /**
   * Sets the travel limits the envelope is derived from.
   *
   * @param limits the limits
   * @return this
   */
  public FakeTarget limits(TravelLimits limits) {
    m_limits = limits;
    return this;
  }

  /**
   * Sets the plant prior.
   *
   * @param prior the prior
   * @return this
   */
  public FakeTarget prior(PlantPrior prior) {
    m_prior = prior;
    return this;
  }

  /**
   * Sets the position reference the supervisor's precondition 9 inspects.
   *
   * @param reference the reference
   * @return this
   */
  public FakeTarget reference(PositionReference reference) {
    m_reference = reference;
    return this;
  }

  /**
   * Sets the reported position.
   *
   * @param positionSi metres or radians
   * @return this
   */
  public FakeTarget at(double positionSi) {
    m_position = positionSi;
    return this;
  }

  /**
   * Sets the reported velocity.
   *
   * @param velocitySi metres or radians per second
   * @return this
   */
  public FakeTarget moving(double velocitySi) {
    m_velocity = velocitySi;
    return this;
  }

  /**
   * Sets the reported stator current.
   *
   * @param amps the current, or empty to report none
   * @return this
   */
  public FakeTarget amps(OptionalDouble amps) {
    m_amps = amps;
    return this;
  }

  /**
   * Sets the declared tolerance.
   *
   * @param toleranceSi the tolerance, or NaN to force the supervisor's substitution
   * @return this
   */
  public FakeTarget tolerance(double toleranceSi) {
    m_tolerance = toleranceSi;
    return this;
  }

  /**
   * Sets whether this mechanism claims to know where it is.
   *
   * @param homed true when homed
   * @return this
   */
  public FakeTarget homed(boolean homed) {
    m_homed = homed;
    return this;
  }

  /**
   * Sets the absolute sensor reading used by the pre-arm agreement check.
   *
   * @param absolute the reading, or empty for no absolute source
   * @return this
   */
  public FakeTarget absolute(OptionalDouble absolute) {
    m_absolute = absolute;
    return this;
  }

  /**
   * Sets the idle mode this mechanism reports.
   *
   * @param mode the mode, or empty when unreadable
   * @return this
   */
  public FakeTarget neutral(Optional<NeutralMode> mode) {
    m_neutral = mode;
    return this;
  }

  /**
   * Sets the gains this mechanism claims to be running.
   *
   * @param gains the gains
   * @return this
   */
  public FakeTarget gains(Gains gains) {
    m_gains = gains;
    return this;
  }

  /**
   * Makes {@link #stop()} throw, so the abort path's promise never to propagate can be tested.
   *
   * @return this
   */
  public FakeTarget withThrowingStop() {
    m_stopThrows = true;
    return this;
  }

  /**
   * The last voltage the supervisor applied, or zero if it never applied one.
   *
   * @return the voltage
   */
  public double lastVolts() {
    return commanded.isEmpty() ? 0.0 : commanded.get(commanded.size() - 1);
  }

  @Override
  public String tuningName() {
    return m_name;
  }

  @Override
  public MechanismArchetype archetype() {
    return m_archetype;
  }

  @Override
  public SiDomain siDomain() {
    return m_domain;
  }

  @Override
  public ControlLocation controlLocation() {
    return m_location;
  }

  @Override
  public void setVoltage(double volts) {
    commanded.add(volts);
  }

  @Override
  public void stop() {
    stops++;
    abortOrder.add("stop");
    if (m_stopThrows) {
      throw new IllegalStateException("stop() threw, which the contract forbids");
    }
  }

  @Override
  public double measuredSi() {
    return m_position;
  }

  @Override
  public double velocitySi() {
    return m_velocity;
  }

  @Override
  public OptionalDouble statorCurrentAmps() {
    return m_amps;
  }

  @Override
  public OptionalDouble absolutePositionSi() {
    return m_absolute;
  }

  @Override
  public PositionReference positionReference() {
    return m_reference;
  }

  @Override
  public boolean isHomed() {
    return m_homed;
  }

  @Override
  public TravelLimits travelLimits() {
    return m_limits;
  }

  @Override
  public PlantPrior plantPrior() {
    return m_prior;
  }

  @Override
  public double toleranceSi() {
    return m_tolerance;
  }

  @Override
  public Optional<NeutralMode> neutralMode() {
    return m_neutral;
  }

  @Override
  public void restoreNeutralMode() {
    neutralRestores++;
    abortOrder.add("restoreNeutralMode");
  }

  @Override
  public Gains gains() {
    return m_gains;
  }

  @Override
  public GainSink gainSink() {
    return g -> {
      m_gains = g;
      return true;
    };
  }
}

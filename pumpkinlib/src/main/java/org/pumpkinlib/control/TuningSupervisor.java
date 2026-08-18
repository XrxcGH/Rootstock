package org.pumpkinlib.control;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.telemetry.PumpkinLog;
import org.pumpkinlib.units.SiDomain;

/**
 * The single choke point for every volt a tuning routine commands.
 *
 * <p><b>This is the most safety-critical class in the library.</b> It exists because every other
 * FRC live-tuning implementation ships a documentation warning and no interlocks — <i>"Live Tuning
 * can be DANGEROUS"</i>, <i>"it is up to you to set up hard or soft limits"</i> — and a
 * fourteen-year-old standing next to an arm does not read documentation warnings. Nothing anywhere
 * in PumpkinLib calls {@link TuningTarget#setVoltage(double)} except this class, and an ArchUnit
 * rule fails the build if anything does. That is what makes "every actuating routine runs inside a
 * {@link SafetyEnvelope}" a structural property instead of a convention somebody remembers.
 *
 * <h2>The three things this class does</h2>
 *
 * <ol>
 *   <li>{@link #arm()} refuses to authorise any voltage at all unless eleven preconditions hold. It
 *       <b>throws</b> rather than collecting, because a routine that runs without these is the one
 *       that breaks a robot — see the containment note on that method.
 *   <li>{@link #commandVolts(double)} clamps, slews and tapers every request before it reaches the
 *       device, so no step is instantaneous at the hardware and no approach to a band edge is a
 *       full-speed one.
 *   <li>{@link #check()} runs eleven abort conditions every loop, in a fixed order, and neutrals the
 *       mechanism the first time one trips — naming <em>which</em> one, with the measured number in
 *       the sentence.
 * </ol>
 *
 * <h2>Why the student always sees "PumpkinLib stopped this", never a silent device clamp</h2>
 *
 * <p>The band this class aborts on comes from {@link SafetyEnvelope#derive(TuningTarget)}, which
 * pulls the hard limits in by {@code guard = softMargin + max(0.03 * range, floor)}. That form is
 * <b>additive</b>, and the containment proof is one line rather than a case analysis:
 *
 * <pre>
 *   range &gt; 0 and floor &gt; 0 unconditionally
 *     =&gt;  max(0.03 * range, floor) &gt; 0
 *     =&gt;  guard &gt; softMargin                                 for every legal TravelLimits
 *     =&gt;  positionMin = min + guard  &gt;  min + softMargin = softMin
 *     and positionMax = max - guard  &lt;  max - softMargin = softMax
 * </pre>
 *
 * <p>So conditions 3 and 4 below trip strictly <em>before</em> the device's own soft limits engage,
 * for every margin a team can legally configure. This matters because the two obvious alternatives
 * both fail: pulling in by a <em>fraction of the margin</em> degenerates to zero at the default
 * margin of zero, and {@code max(softMargin, guard)} degenerates to exactly {@code softMargin} the
 * moment a careful team configures a generous margin — in both cases the supervisor band silently
 * equals the device band, the device clamps, and the student is told nothing at all. The
 * non-emptiness of the band is not assumed: {@link SafetyEnvelope#validate} reports the one case
 * where a very short axis cannot fit the guard twice, and {@link #arm()} refuses on it.
 *
 * <h2>Why the abort path restores the idle mode first</h2>
 *
 * <p>{@link #abort(AbortReason)} calls {@link TuningTarget#restoreNeutralMode()} <em>before</em>
 * {@link TuningTarget#stop()}. A measurement step is allowed to borrow COAST for half a second to
 * watch which way a mechanism falls; neutralling a gravity mechanism while it is still coasting is
 * exactly the "letting go of an arm" hazard the rest of this class exists to prevent, and {@link
 * AbortReason#ENABLE_RELEASED} — the most common abort by a wide margin — runs this path every
 * time somebody lets go of the trigger.
 */
public final class TuningSupervisor {

  /** How far ahead the approach check extrapolates position, in seconds. */
  public static final double kLookAheadSeconds = 0.15;

  /** How long a wrong-direction reading must persist before it counts, in seconds. */
  public static final double kWrongDirectionSeconds = 0.3;

  /** Seconds a full-scale voltage swing is allowed to take. Nothing steps instantly at the device. */
  public static final double kSlewSecondsToFullScale = 0.05;

  /** Fraction of the band, at each end, over which the excess above the hold voltage is tapered. */
  public static final double kTaperFractionOfBand = 0.10;

  /** How long a frozen position or velocity signal must persist before it counts, in seconds. */
  public static final double kSensorFreezeSeconds = 0.5;

  /** How far an arm's absolute reading may drift from its horizontal reference, in radians. */
  public static final double kArmZeroToleranceRadians = Math.toRadians(5.0);

  /** Fallback tolerance for a position mechanism that declares none: 0.5% of travel. */
  public static final double kToleranceFractionOfTravel = 0.005;

  /** Fallback tolerance for a velocity mechanism that declares none: 1% of free speed. */
  public static final double kToleranceFractionOfFreeSpeed = 0.01;

  /** The alert group every message from this class lands in. */
  public static final String kAlertGroup = "Tuning";

  private final TuningTarget m_target;
  private final SafetyEnvelope m_envelope;
  private final BooleanSupplier m_enableHeld;

  private boolean m_allowNoCurrentSensing;
  private Optional<String> m_coastRiskAcknowledgement = Optional.empty();

  private boolean m_armed;
  private double m_armedAtSeconds = Double.NaN;
  private Optional<AbortReason> m_lastAbort = Optional.empty();
  private String m_lastAbortMessage = "";

  private double m_lastCommandedVolts;
  private double m_gravityHoldVolts = Double.NaN;
  private boolean m_toleranceNarrated;

  private double m_overcurrentSince = Double.NaN;
  private double m_stallSince = Double.NaN;
  private double m_wrongDirectionSince = Double.NaN;

  private double m_frozenPositionValue = Double.NaN;
  private double m_frozenPositionSince = Double.NaN;
  private double m_frozenVelocityValue = Double.NaN;
  private double m_frozenVelocitySince = Double.NaN;
  private double m_frozenVelocityRefPosition = Double.NaN;

  /**
   * Build a supervisor for one mechanism.
   *
   * <p>Construction is cheap and commands nothing. No voltage is possible until {@link #arm()}
   * succeeds, and {@link #arm()} is only legal in Test mode on an enabled robot with no FMS.
   *
   * @param target the mechanism, which must be the same one the envelope was derived from
   * @param envelope the box this supervisor will not let the mechanism leave
   * @param enableHeld the physical held-enable, normally a gamepad trigger past 0.5; releasing it
   *     aborts with {@link AbortReason#ENABLE_RELEASED} on the very next {@link #check()}
   * @throws NullPointerException if any argument is null
   */
  public TuningSupervisor(
      TuningTarget target, SafetyEnvelope envelope, BooleanSupplier enableHeld) {
    m_target = Objects.requireNonNull(target, "target");
    m_envelope = Objects.requireNonNull(envelope, "envelope");
    m_enableHeld = Objects.requireNonNull(enableHeld, "enableHeld");
  }

  /**
   * State that this mechanism genuinely cannot report stator current, disabling the overcurrent
   * abort for it.
   *
   * <p>Deliberate friction rather than a silent default: a routine that cannot see current cannot
   * tell a jam from a hard stop, and the student should know which protections are actually running
   * before it moves. Calling this raises a standing pit warning naming the protection that is off.
   *
   * @return this supervisor, for chaining
   */
  public TuningSupervisor allowNoCurrentSensing() {
    m_allowNoCurrentSensing = true;
    Alerts.warning(
        kAlertGroup,
        m_target.tuningName()
            + ": tuning without current sensing. The overcurrent abort is OFF for this mechanism, "
            + "so a jam or a hard stop will be caught by the stall check half a second later "
            + "instead of by current in 0.15 s. Fix: implement TuningTarget.statorCurrentAmps().",
        MatchImpact.PIT_ONLY);
    return this;
  }

  /**
   * Accept, out loud and in writing, that a gravity mechanism will be tuned in coast mode.
   *
   * <p>The reason is logged verbatim and shown as a standing warning for the rest of the session,
   * the same deliberate friction the rest of the tuning domain uses for its other override paths. A
   * coast-mode arm falls whenever the tuner lets go of it, which is every abort.
   *
   * @param reason why, in the team's own words; blank is rejected
   * @return this supervisor, for chaining
   * @throws IllegalArgumentException if the reason is null or blank, because an override with no
   *     stated reason is an override nobody reviewed
   */
  public TuningSupervisor acknowledgeCoastRisk(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(
          "acknowledgeCoastRisk needs a reason in your own words, e.g. "
              + "acknowledgeCoastRisk(\"counterbalanced, it does not fall\"). "
              + "It is written into the tuning report so the next person knows why.");
    }
    m_coastRiskAcknowledgement = Optional.of(reason);
    Alerts.warning(
        kAlertGroup,
        m_target.tuningName()
            + ": coast-mode gravity tuning was acknowledged - \""
            + reason
            + "\". Every abort neutrals this mechanism, and a coasting gravity mechanism falls "
            + "when it is neutralled. Fix: set NeutralMode.BRAKE in your ControlConfig.",
        MatchImpact.PIT_ONLY);
    return this;
  }

  /**
   * Set the best kG known this session, signed in the mechanism's own direction convention.
   *
   * <p>Used only by the taper in {@link #commandVolts(double)}. Tapering an arm's voltage to zero
   * as it approaches a limit does not stop the arm, it drops it into the limit — so on a gravity
   * archetype the taper floor is this hold voltage and only the excess above it is tapered away.
   * Until a measurement replaces it the floor is {@link PlantPrior#gravityVoltsPrior()}, which is a
   * positive magnitude and therefore assumes positive volts move the mechanism up.
   *
   * @param signedVolts the gravity hold voltage, signed; NaN restores the prior
   */
  public void setGravityHoldVolts(double signedVolts) {
    m_gravityHoldVolts = signedVolts;
  }

  /**
   * Arm the supervisor: authorise voltage, start the routine clock, clear the latched abort.
   *
   * <p><b>This method throws.</b> Eleven preconditions are checked and the first failure raises an
   * {@link IllegalStateException} whose message names the mechanism, what was wrong, and the one
   * change that fixes it. A hard throw is right for the contract — a mis-wired caller must not
   * silently receive an unarmed supervisor and conclude that nothing is wrong — but it is only safe
   * because the wizard is the sole caller and wraps it: the exception is caught, the message is
   * published verbatim, the arm is refused, and nothing propagates into {@code robotPeriodic()}.
   * A library that kills the robot loop with its own diagnostic has turned the diagnostic into the
   * outage.
   *
   * <p>The eleven, in order:
   *
   * <ol>
   *   <li>travel limits exist and are real (position archetypes);
   *   <li>{@link SafetyEnvelope#validate} is clean, so there is a band to stop inside;
   *   <li>stator current is readable, or {@link #allowNoCurrentSensing()} was called;
   *   <li>the mechanism is inside the band right now;
   *   <li>the robot is enabled;
   *   <li>this is not a replay;
   *   <li>this is Test mode and no FMS is attached;
   *   <li>{@link TuningTarget#isHomed()};
   *   <li>the position reference is one a supervisor can trust;
   *   <li>absolute and rotor-derived positions agree;
   *   <li>a gravity mechanism is in BRAKE, or the coast risk was acknowledged.
   * </ol>
   *
   * @throws IllegalStateException if any precondition fails
   */
  public void arm() {
    boolean position = m_target.archetype().isPosition();
    String who = m_target.tuningName();
    TravelLimits limits = m_target.travelLimits();

    // 1 and 2 - there must be a band, and it must be inside the device's own soft limits.
    if (position) {
      if (limits == null || !Double.isFinite(limits.range()) || limits.range() <= 0.0) {
        throw new IllegalStateException(
            who
                + " did not declare usable travel limits, and every position abort in the tuner is "
                + "computed from them. Fix: give this mechanism a TravelLimits(min, max, "
                + "softMargin) measured from the real hardware. TravelLimits.unbounded() is only "
                + "legal for a flywheel or a drive motor.");
      }
      var envelopeProblems = m_envelope.validate(who, limits);
      if (!envelopeProblems.isEmpty()) {
        throw new IllegalStateException(envelopeProblems.get(0));
      }
    }

    // 3 - current sensing, or an explicit acceptance that the overcurrent abort is off.
    if (m_target.statorCurrentAmps().isEmpty() && !m_allowNoCurrentSensing) {
      throw new IllegalStateException(
          who
              + " cannot report stator current, so the tuner cannot tell a jam from a hard stop. "
              + "Fix: implement TuningTarget.statorCurrentAmps(), or call "
              + "supervisor.allowNoCurrentSensing() to accept that the overcurrent abort is off.");
    }

    // 4 - already inside the band.
    double startPosition = m_target.measuredSi();
    if (position) {
      if (!Double.isFinite(startPosition)) {
        throw new IllegalStateException(
            who
                + " reports a position of "
                + startPosition
                + ", which is not a number. Every safety limit is computed from that value. "
                + "Fix: check the encoder wiring and the signal update rate before tuning.");
      }
      if (!m_envelope.containsPosition(startPosition)) {
        throw new IllegalStateException(
            String.format(
                Locale.ROOT,
                "%s is at %.4f %s, which is outside the band the tuner is allowed to move inside "
                    + "(%.4f to %.4f %s). Fix: move the mechanism back into the middle of its "
                    + "travel by hand, or with your own controls, and arm again.",
                who,
                startPosition,
                unitLabel(),
                m_envelope.positionMin(),
                m_envelope.positionMax(),
                unitLabel()));
      }
    }

    // 5 and 7 - enabled, in Test mode, off the field. One expression, because they are one idea:
    // motion is impossible outside diagnostics mode, by construction rather than by convention.
    if (!MatchContext.isEnabled()) {
      throw new IllegalStateException(
          who
              + ": the robot is disabled, so the tuner cannot command anything. "
              + "Fix: enable the robot in Test mode on the driver station, then arm again.");
    }
    if (MatchContext.isFMSAttached()) {
      throw new IllegalStateException(
          who
              + ": an FMS is attached. The tuner never commands voltage at an event field. "
              + "Fix: tune in the pit, on a practice field with no FMS.");
    }
    if (!MatchContext.isDiagnostics()) {
      throw new IllegalStateException(
          who
              + ": the robot is not in Test mode. Raw-voltage tuning is only possible in Test "
              + "mode, so that a driver holding a trigger during teleop can never be authorising "
              + "motion at the same time. Fix: select Test on the driver station and enable.");
    }

    // 6 - replay must never actuate.
    if (PumpkinLog.isReplay()) {
      throw new IllegalStateException(
          who
              + ": this is a log replay, not a robot. The tuner will not command voltage into a "
              + "replay, because the outputs would be fiction and the log would look real.");
    }

    // 8, 9, 10 - do we actually know where this mechanism is?
    if (position) {
      if (!m_target.isHomed()) {
        throw new IllegalStateException(
            who
                + " has not been homed since power-on. The tuner needs to know where this "
                + "mechanism actually is before it commands any voltage - every safety limit is "
                + "computed from the position you are reporting. Fix: run your homing routine, "
                + "then try again.");
      }
      requireTrustworthyPositionReference(who);
      requireAbsoluteAgreement(who);
    }

    // 11 - a gravity mechanism that coasts falls the moment the tuner lets go of it, and letting go
    // is what every single abort does.
    if (m_target.archetype().hasGravity() && m_coastRiskAcknowledgement.isEmpty()) {
      Optional<NeutralMode> mode = m_target.neutralMode();
      if (mode.isEmpty()) {
        throw new IllegalStateException(
            "I cannot read "
                + who
                + "'s idle mode back, and a gravity mechanism that coasts falls when the tuner "
                + "lets go. Fix: implement TuningTarget.neutralMode(), or acknowledge the risk "
                + "with supervisor.acknowledgeCoastRisk(\"why\").");
      }
      if (mode.get() != NeutralMode.BRAKE) {
        throw new IllegalStateException(
            who
                + " is set to coast. The tuner neutrals the mechanism whenever you let go of the "
                + "trigger, and a coasting arm falls. Fix: set NeutralMode.BRAKE in your "
                + "ControlConfig, or call supervisor.acknowledgeCoastRisk(\"why\") if you know "
                + "what you are doing.");
      }
    }

    m_armed = true;
    m_armedAtSeconds = Clock.seconds();
    m_lastAbort = Optional.empty();
    m_lastAbortMessage = "";
    m_lastCommandedVolts = 0.0;
    resetConditionTimers();
    if (Double.isNaN(m_gravityHoldVolts) && m_target.archetype().hasGravity()) {
      m_gravityHoldVolts = m_target.plantPrior().gravityVoltsPrior();
    }
    PumpkinLog.log("Tuning/Supervisor/" + who + "/Armed", true);
  }

  /**
   * Clamp, slew, taper, apply, and record. The only path to {@link TuningTarget#setVoltage(double)}
   * in the whole library.
   *
   * <p>In order: clamp to the envelope's ceiling; limit the rate of change so a full-scale swing
   * takes at least {@value #kSlewSecondsToFullScale} s at the device rather than arriving as a step;
   * then, over the last {@value #kTaperFractionOfBand} of the band on the side being approached,
   * taper the <em>excess above the hold voltage</em> to nothing. On a flywheel or a turret the hold
   * voltage is zero and the taper is a brake; on an arm or an elevator it is the gravity term, and
   * tapering to zero instead would release the mechanism into the very limit the taper exists to
   * avoid.
   *
   * @param requestedVolts what the routine would like to apply
   * @return the volts actually applied, which is zero whenever the supervisor is not armed
   */
  public double commandVolts(double requestedVolts) {
    if (!m_armed) {
      return 0.0;
    }
    double request = Double.isFinite(requestedVolts) ? requestedVolts : 0.0;
    double ceiling = Math.abs(m_envelope.maxVolts());
    double clamped = Math.max(-ceiling, Math.min(ceiling, request));

    double dt = Clock.dt();
    double maxDelta = ceiling / kSlewSecondsToFullScale * (Double.isFinite(dt) && dt > 0 ? dt : 0.02);
    double delta = clamped - m_lastCommandedVolts;
    double slewed = m_lastCommandedVolts + Math.max(-maxDelta, Math.min(maxDelta, delta));

    double applied = taper(slewed, m_target.measuredSi());
    m_lastCommandedVolts = applied;
    m_target.setVoltage(applied);
    return applied;
  }

  /**
   * Run the eleven every-loop abort conditions, in the documented order, and stop the mechanism the
   * first time one trips.
   *
   * <p>Conditions 3, 4, 5 and 6 are skipped when the value they compare is not finite, so that a
   * NaN encoder reports {@link AbortReason#SENSOR_FAULT} — which names the actual problem — rather
   * than {@link AbortReason#LIMIT_REACHED}, which a NaN comparison would otherwise produce first
   * and which would send the student to look at their soft limits. For every finite reading the
   * order is exactly the documented one.
   *
   * @return the reason, if one tripped on this cycle; empty when nothing tripped or when the
   *     supervisor was already disarmed
   */
  public Optional<AbortReason> check() {
    if (!m_armed) {
      return Optional.empty();
    }
    double now = Clock.seconds();
    String who = m_target.tuningName();
    boolean position = m_target.archetype().isPosition();
    double pos = m_target.measuredSi();
    double vel = m_target.velocitySi();
    OptionalDouble amps = m_target.statorCurrentAmps();
    boolean posFinite = Double.isFinite(pos);
    boolean velFinite = Double.isFinite(vel);

    // 1 - the human let go.
    if (m_envelope.requireHeldEnable() && !m_enableHeld.getAsBoolean()) {
      return trip(AbortReason.ENABLE_RELEASED, who + ": " + AbortReason.ENABLE_RELEASED.summary());
    }

    // 2 - the driver station disabled us.
    if (MatchContext.isDisabled()) {
      return trip(AbortReason.DISABLED, who + ": " + AbortReason.DISABLED.summary());
    }

    // 3 - outside the band.
    if (position && posFinite && !m_envelope.containsPosition(pos)) {
      return trip(
          AbortReason.LIMIT_REACHED,
          String.format(
              Locale.ROOT,
              "Stopped: %s reached %.4f %s and the safe band ends at %.4f %s. "
                  + "Fix: nothing, if that is where the mechanism really is - the tuner stopped "
                  + "before your soft limits had to. If it is not where it really is, your "
                  + "position reference is wrong.",
              who,
              pos,
              unitLabel(),
              pos > m_envelope.positionMax() ? m_envelope.positionMax() : m_envelope.positionMin(),
              unitLabel()));
    }

    // 4 - would leave the band inside the look-ahead window.
    if (position && posFinite && velFinite) {
      double predicted = pos + vel * kLookAheadSeconds;
      if (!m_envelope.containsPosition(predicted)) {
        return trip(
            AbortReason.LIMIT_APPROACH,
            String.format(
                Locale.ROOT,
                "Stopped early: %s is at %.4f %s moving %.3f %s/s, so in %.2f s it would be at "
                    + "%.4f %s - past the edge of the safe band. "
                    + "Fix: lower the step voltage, or widen the travel limits if the mechanism "
                    + "really does have more room than you declared.",
                who,
                pos,
                unitLabel(),
                vel,
                unitLabel(),
                kLookAheadSeconds,
                predicted,
                unitLabel()));
      }
    }

    // 5 - faster than this mechanism should ever go.
    if (velFinite && Math.abs(vel) > m_envelope.maxAbsVelocity()) {
      String fix =
          m_target.archetype() == MechanismArchetype.DRIVE_VELOCITY
              ? "Fix: the wheels reached free speed almost instantly. A drivetrain cannot be "
                  + "characterized on blocks; put it on the floor with at least 3 m of clear space."
              : "Fix: check your gear ratio - a reduction that is wrong by 3x shows up here first.";
      return trip(
          AbortReason.OVERSPEED,
          String.format(
              Locale.ROOT,
              "Stopped: %s reached %.3f %s/s and the ceiling is %.3f %s/s. %s",
              who,
              vel,
              unitLabel(),
              m_envelope.maxAbsVelocity(),
              unitLabel(),
              fix));
    }

    // 6 - sustained overcurrent.
    if (amps.isPresent() && Double.isFinite(amps.getAsDouble())) {
      if (amps.getAsDouble() > m_envelope.maxStatorAmps()) {
        if (Double.isNaN(m_overcurrentSince)) {
          m_overcurrentSince = now;
        }
        if (now - m_overcurrentSince >= m_envelope.holdoffSeconds()) {
          return trip(
              AbortReason.OVERCURRENT,
              String.format(
                  Locale.ROOT,
                  "Stopped: %s drew %.0f A for %.2f s and the limit is %.0f A. "
                      + "Fix: something is jammed or the mechanism is against a hard stop - "
                      + "move it off the stop by hand and look for what is binding.",
                  who,
                  amps.getAsDouble(),
                  m_envelope.holdoffSeconds(),
                  m_envelope.maxStatorAmps()));
        }
      } else {
        m_overcurrentSince = Double.NaN;
      }
    }

    // 7 - real voltage, no motion.
    if (velFinite
        && Math.abs(m_lastCommandedVolts) > m_envelope.stallVoltsThreshold()
        && Math.abs(vel) < m_envelope.stallVelocityThreshold()) {
      if (Double.isNaN(m_stallSince)) {
        m_stallSince = now;
      }
      if (now - m_stallSince >= m_envelope.stallSeconds()) {
        return trip(
            AbortReason.STALLED,
            String.format(
                Locale.ROOT,
                "Stopped: %.2f V applied to %s for %.2f s and it moved slower than %.4f %s/s. "
                    + "Fix: check the breaker, check the CAN ID, and check whether it is already "
                    + "against a hard stop.",
                m_lastCommandedVolts,
                who,
                m_envelope.stallSeconds(),
                m_envelope.stallVelocityThreshold(),
                unitLabel()));
      }
    } else {
      m_stallSince = Double.NaN;
    }

    // 8 - positive voltage, negative motion.
    double voltsDeadband = Math.max(0.5, 0.05 * Math.abs(m_envelope.maxVolts()));
    double velocityDeadband = Math.max(m_envelope.stallVelocityThreshold(), 1e-6);
    if (velFinite
        && Math.abs(m_lastCommandedVolts) > voltsDeadband
        && Math.abs(vel) > velocityDeadband
        && Math.signum(vel) != Math.signum(m_lastCommandedVolts)) {
      if (Double.isNaN(m_wrongDirectionSince)) {
        m_wrongDirectionSince = now;
      }
      if (now - m_wrongDirectionSince >= kWrongDirectionSeconds) {
        return trip(
            AbortReason.WRONG_DIRECTION,
            String.format(
                Locale.ROOT,
                "Stopped: %.2f V is making %s move at %.3f %s/s, which is the opposite direction. "
                    + "Fix: invert the motor or the sensor so positive voltage produces positive "
                    + "motion. Do not tune around it - every gain you measure would carry the "
                    + "sign error with it.",
                m_lastCommandedVolts,
                who,
                vel,
                unitLabel()));
      }
    } else {
      m_wrongDirectionSince = Double.NaN;
    }

    // 9 - out of time.
    double elapsed = now - m_armedAtSeconds;
    if (Double.isFinite(elapsed) && elapsed > m_envelope.maxRoutineSeconds()) {
      return trip(
          AbortReason.TIMEOUT,
          String.format(
              Locale.ROOT,
              "Stopped: this step ran %.1f s and the budget is %.1f s. "
                  + "Fix: usually the mechanism is not reaching the speed we asked for - check "
                  + "for a mechanical drag, or raise maxRoutineSeconds if the move really is "
                  + "this slow.",
              elapsed,
              m_envelope.maxRoutineSeconds()));
    }

    // 10 - a sensor is lying.
    if (!posFinite || !velFinite || (amps.isPresent() && !Double.isFinite(amps.getAsDouble()))) {
      return trip(
          AbortReason.SENSOR_FAULT,
          String.format(
              Locale.ROOT,
              "Stopped: %s reported position %s, velocity %s. One of those is not a number, and "
                  + "every safety limit is computed from them. "
                  + "Fix: check the sensor wiring and that the device is on the CAN bus.",
              who,
              Double.toString(pos),
              Double.toString(vel)));
    }

    // 11 - position and velocity disagree about whether this is moving.
    Optional<AbortReason> inconsistent = checkSensorConsistency(now, pos, vel, who);
    if (inconsistent.isPresent()) {
      return inconsistent;
    }

    return Optional.empty();
  }

  /**
   * Neutral the mechanism, publish the reason, and latch until re-armed.
   *
   * <p>Idempotent and never throws, because this runs on every failure path including the ones
   * where something is already broken. The idle mode is restored <b>before</b> the mechanism is
   * neutralled: a step is allowed to borrow COAST for a measurement, and neutralling a gravity
   * mechanism while it is still coasting is releasing it.
   *
   * @param reason why; used verbatim in the published message when no more specific one was set
   */
  public void abort(AbortReason reason) {
    AbortReason actual = reason == null ? AbortReason.SENSOR_FAULT : reason;
    try {
      m_target.restoreNeutralMode();
    } catch (RuntimeException e) {
      // An abort path must not become the outage. There is nothing more useful to do here than
      // continue to the stop() below, which is the part that actually removes energy.
    }
    try {
      m_target.stop();
    } catch (RuntimeException e) {
      // Same: stop() is contractually not allowed to throw, and if it does anyway, latching the
      // abort and disarming is still strictly better than propagating.
    }
    m_lastCommandedVolts = 0.0;
    m_armed = false;
    m_lastAbort = Optional.of(actual);
    if (m_lastAbortMessage.isBlank()) {
      m_lastAbortMessage = m_target.tuningName() + ": " + actual.summary();
    }
    resetConditionTimers();
    PumpkinLog.log("Tuning/Supervisor/" + m_target.tuningName() + "/Abort", actual.name());
    PumpkinLog.log("Tuning/Supervisor/" + m_target.tuningName() + "/AbortMessage", m_lastAbortMessage);
    PumpkinLog.log("Tuning/Supervisor/" + m_target.tuningName() + "/Armed", false);
  }

  /**
   * Stop cleanly without recording a fault — the normal end of a step that finished its work.
   *
   * <p>Restores the idle mode and neutrals, exactly as {@link #abort(AbortReason)} does, but leaves
   * {@link #lastAbort()} alone so the UI does not show a stop sign for a successful step.
   */
  public void disarm() {
    try {
      m_target.restoreNeutralMode();
    } catch (RuntimeException e) {
      // See abort(): a cleanup path must never propagate.
    }
    try {
      m_target.stop();
    } catch (RuntimeException e) {
      // See abort().
    }
    m_lastCommandedVolts = 0.0;
    m_armed = false;
    resetConditionTimers();
    PumpkinLog.log("Tuning/Supervisor/" + m_target.tuningName() + "/Armed", false);
  }

  /**
   * Whether voltage is currently authorised.
   *
   * @return true between a successful {@link #arm()} and the next abort or disarm
   */
  public boolean isArmed() {
    return m_armed;
  }

  /**
   * The reason the last routine stopped, if it stopped for a reason.
   *
   * @return the latched reason, cleared by the next successful {@link #arm()}
   */
  public Optional<AbortReason> lastAbort() {
    return m_lastAbort;
  }

  /**
   * The full sentence for the last abort, with the measured numbers in it.
   *
   * <p>This is what the UI shows and what the tuning report records. It names the mechanism, the
   * value, the limit and the fix, because "LIMIT_REACHED" on its own teaches nothing.
   *
   * @return the message, or an empty string if nothing has aborted since the last arm
   */
  public String lastAbortMessage() {
    return m_lastAbortMessage;
  }

  /**
   * How long the current routine has been armed.
   *
   * @return seconds since {@link #arm()}, or NaN if it has never been armed
   */
  public double elapsedSeconds() {
    return Double.isNaN(m_armedAtSeconds) ? Double.NaN : Clock.seconds() - m_armedAtSeconds;
  }

  /**
   * The mechanism this supervisor guards.
   *
   * @return the target
   */
  public TuningTarget target() {
    return m_target;
  }

  /**
   * The box this supervisor enforces.
   *
   * @return the envelope
   */
  public SafetyEnvelope envelope() {
    return m_envelope;
  }

  /**
   * The volts applied on the most recent {@link #commandVolts(double)}.
   *
   * @return the applied voltage, zero when disarmed
   */
  public double appliedVolts() {
    return m_lastCommandedVolts;
  }

  /**
   * The tolerance actually in use, substituting a derived one when the mechanism declares none.
   *
   * <p>The substitution is narrated exactly once per session, because a tolerance the library
   * invented is not a tolerance the team agreed to and a student comparing "settled within
   * tolerance" against a number nobody chose deserves to know where it came from.
   *
   * @return the tolerance, in metres or radians for a position mechanism and m/s or rad/s for a
   *     velocity one
   */
  public double effectiveToleranceSi() {
    double declared = m_target.toleranceSi();
    if (Double.isFinite(declared) && declared > 0.0) {
      return declared;
    }
    double substituted;
    String basis;
    if (m_target.archetype().isPosition()) {
      substituted = kToleranceFractionOfTravel * m_target.travelLimits().range();
      basis = "0.5% of travel";
    } else {
      double freeSpeed = m_target.plantPrior().freeSpeedSi();
      substituted =
          Double.isFinite(freeSpeed) ? kToleranceFractionOfFreeSpeed * Math.abs(freeSpeed) : 0.0;
      basis = "1% of the predicted free speed";
    }
    if (!m_toleranceNarrated) {
      m_toleranceNarrated = true;
      Alerts.info(
          kAlertGroup,
          String.format(
              Locale.ROOT,
              "%s did not declare a tolerance, so the tuner is using %.5f %s (%s). "
                  + "Fix: set TuningTarget.toleranceSi() to the error your team actually accepts.",
              m_target.tuningName(),
              substituted,
              unitLabel(),
              basis));
    }
    return substituted;
  }

  /**
   * One line naming everything this supervisor has promised not to exceed.
   *
   * <p>Shown before a routine arms. A student about to let a library command voltage at their
   * mechanism should be able to read the whole promise in a sentence.
   *
   * @return the summary
   */
  public String describe() {
    return m_target.tuningName()
        + " supervised: "
        + m_envelope.describe(unitLabel())
        + (m_envelope.requireHeldEnable() ? ", enable held" : ", NO held enable")
        + (m_armed ? ", armed" : ", not armed");
  }

  // ===============================================================================================
  // internals
  // ===============================================================================================

  /**
   * Taper the excess above the hold voltage over the last slice of the band on the approach side.
   *
   * <p>The hold voltage is zero for a gravity-free archetype, so the taper is an ordinary brake. On
   * a gravity archetype it is {@code kG * gravityShape(position)}, so what is tapered away is only
   * the part of the command that is <em>moving</em> the mechanism, never the part that is holding
   * it up.
   */
  private double taper(double volts, double position) {
    if (!m_target.archetype().isPosition() || !Double.isFinite(position)) {
      return volts;
    }
    double band = m_envelope.bandWidth();
    if (!(band > 0.0)) {
      return volts;
    }
    double zone = kTaperFractionOfBand * band;
    double factor = 1.0;
    if (volts > 0.0) {
      double remaining = m_envelope.positionMax() - position;
      factor = Math.max(0.0, Math.min(1.0, remaining / zone));
    } else if (volts < 0.0) {
      double remaining = position - m_envelope.positionMin();
      factor = Math.max(0.0, Math.min(1.0, remaining / zone));
    }
    if (factor >= 1.0) {
      return volts;
    }
    double hold = holdVolts(position);
    return hold + (volts - hold) * factor;
  }

  /** The voltage that keeps a gravity mechanism where it is, at this position. Zero otherwise. */
  private double holdVolts(double position) {
    if (!m_target.archetype().hasGravity()) {
      return 0.0;
    }
    double kg = m_gravityHoldVolts;
    if (!Double.isFinite(kg)) {
      return 0.0;
    }
    if (m_target.gravityMode() == GravityMode.COSINE) {
      return kg * Math.cos(position - m_target.horizontalReferenceSi());
    }
    return kg;
  }

  /** Condition 11, split out because it needs four pieces of latched state of its own. */
  private Optional<AbortReason> checkSensorConsistency(
      double now, double pos, double vel, String who) {
    double velocityDeadband = Math.max(m_envelope.stallVelocityThreshold(), 1e-6);
    double tolerance = effectiveToleranceSi();

    // Frozen position while velocity insists we are moving. A frozen CAN signal returns the
    // identical double forever, which is why this compares exactly rather than with an epsilon.
    if (pos == m_frozenPositionValue) {
      if (Double.isNaN(m_frozenPositionSince)) {
        m_frozenPositionSince = now;
      } else if (now - m_frozenPositionSince >= kSensorFreezeSeconds
          && Math.abs(vel) > velocityDeadband) {
        return trip(
            AbortReason.SENSOR_INCONSISTENT,
            String.format(
                Locale.ROOT,
                "Stopped: %s reported the identical position %.6f %s for %.1f s while its velocity "
                    + "read %.3f %s/s. One of those signals is stale. "
                    + "Fix: check optimizeBusUtilization - calling it without first calling "
                    + "setUpdateFrequency on the position signal freezes that signal forever, "
                    + "with no error.",
                who,
                pos,
                unitLabel(),
                now - m_frozenPositionSince,
                vel,
                unitLabel()));
      }
    } else {
      m_frozenPositionValue = pos;
      m_frozenPositionSince = now;
    }

    // Frozen velocity while position is visibly moving.
    if (vel == m_frozenVelocityValue) {
      if (Double.isNaN(m_frozenVelocitySince)) {
        m_frozenVelocitySince = now;
        m_frozenVelocityRefPosition = pos;
      } else if (now - m_frozenVelocitySince >= kSensorFreezeSeconds
          && Math.abs(pos - m_frozenVelocityRefPosition) > 2.0 * tolerance
          && Math.abs(vel) <= velocityDeadband) {
        return trip(
            AbortReason.SENSOR_INCONSISTENT,
            String.format(
                Locale.ROOT,
                "Stopped: %s moved %.4f %s in %.1f s while its velocity signal read a constant "
                    + "%.3f %s/s. One of those signals is stale. "
                    + "Fix: check optimizeBusUtilization and your velocity signal update rate.",
                who,
                pos - m_frozenVelocityRefPosition,
                unitLabel(),
                now - m_frozenVelocitySince,
                vel,
                unitLabel()));
      }
    } else {
      m_frozenVelocityValue = vel;
      m_frozenVelocitySince = now;
      m_frozenVelocityRefPosition = pos;
    }
    return Optional.empty();
  }

  /** Precondition 9: a position the robot only assumes is not a position reference. */
  private void requireTrustworthyPositionReference(String who) {
    PositionReference reference = m_target.positionReference();
    if (reference instanceof PositionReference.Absolute
        || reference instanceof PositionReference.FusedAbsolute) {
      return;
    }
    if (reference instanceof PositionReference.HomedAgainstSwitch homed) {
      if (homed.completedThisPowerCycle().getAsBoolean()) {
        return;
      }
      throw new IllegalStateException(
          who
              + " homes against a limit switch, but that homing has not completed since this power "
              + "cycle. Fix: run the homing routine, then arm again.");
    }
    if (reference instanceof PositionReference.AssumeAtBoot) {
      throw new IllegalStateException(
          who
              + " uses an assumed boot position, which is not a position reference the tuner can "
              + "trust - somebody moving the mechanism by hand while the robot sat disabled makes "
              + "every safety limit here point at the wrong place. Fix: home against a limit "
              + "switch or add an absolute encoder before tuning.");
    }
    throw new IllegalStateException(
        who
            + " only has the motor's internal encoder, which reads zero wherever the robot booted. "
            + "That is fine for a flywheel and unsafe for an arm. Fix: home against a limit switch "
            + "or add an absolute encoder before tuning.");
  }

  /** Precondition 10, plus the arm zero-convention re-check that runs before every single step. */
  private void requireAbsoluteAgreement(String who) {
    OptionalDouble absolute = m_target.absolutePositionSi();
    if (absolute.isEmpty() || !Double.isFinite(absolute.getAsDouble())) {
      return;
    }
    double measured = m_target.measuredSi();
    double disagreement = Math.abs(absolute.getAsDouble() - measured);
    double allowed = 2.0 * effectiveToleranceSi();
    if (disagreement > allowed) {
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "%s's absolute sensor says %.4f %s and its motor encoder says %.4f %s. They "
                  + "disagree by %.4f %s, which is more than twice your tolerance of %.4f %s, so "
                  + "at least one of them is wrong. Fix that before tuning - a wrong position is "
                  + "a wrong safety limit.",
              who,
              absolute.getAsDouble(),
              unitLabel(),
              measured,
              unitLabel(),
              disagreement,
              unitLabel(),
              effectiveToleranceSi(),
              unitLabel()));
    }
    // The arm zero-convention check re-runs here and not only at pre-flight: pre-flight happened
    // once, possibly forty minutes and one hand-move ago, and arming is the last moment before
    // voltage.
    if (m_target.archetype() == MechanismArchetype.ARM && disagreement > kArmZeroToleranceRadians) {
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "%s's absolute sensor and motor encoder are %.1f degrees apart, and the cosine "
                  + "gravity term is computed from the angle to horizontal declared at %.1f "
                  + "degrees. Fix: re-run the arm zero check - a cosine referenced to the wrong "
                  + "zero is wrong at every angle, not just at one.",
              who,
              Math.toDegrees(disagreement),
              Math.toDegrees(m_target.horizontalReferenceSi())));
    }
  }

  /** Record the message, run the abort path, and hand the reason back to the caller. */
  private Optional<AbortReason> trip(AbortReason reason, String message) {
    m_lastAbortMessage = message;
    abort(reason);
    Alerts.warning(kAlertGroup, message, MatchImpact.PIT_ONLY);
    return Optional.of(reason);
  }

  private void resetConditionTimers() {
    m_overcurrentSince = Double.NaN;
    m_stallSince = Double.NaN;
    m_wrongDirectionSince = Double.NaN;
    m_frozenPositionValue = Double.NaN;
    m_frozenPositionSince = Double.NaN;
    m_frozenVelocityValue = Double.NaN;
    m_frozenVelocitySince = Double.NaN;
    m_frozenVelocityRefPosition = Double.NaN;
  }

  private String unitLabel() {
    return m_target.siDomain() == SiDomain.LINEAR_METERS ? "m" : "rad";
  }
}

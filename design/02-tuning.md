# PumpkinLib Domain 02 — The Tuning System

**Status:** Design complete, ready to implement. Revision 2 — post adversarial review, 2026-08-07.
**Target:** WPILib 2026 (`edu.wpi.first.*`, Java 17) with a mechanical 2027 port path (`org.wpilib.*`, Java 25)
**Owner package roots:** `org.pumpkinlib.tuning`, `org.pumpkinlib.tuning.sysid`, `org.pumpkinlib.tuning.wizard`, `org.pumpkinlib.tuning.diagnostics`, `org.pumpkinlib.tuning.persist`, `org.pumpkinlib.tuning.ui`, `org.pumpkinlib.control`

### Revision 2 — what changed and why

Five of these were findings that would have broken a real mechanism or shipped a safety claim that was not true. They are listed first because the failure walk-throughs are the most useful part of this document for anyone implementing it.

| # | Change | Section | Was |
|---|---|---|---|
| 1 | **Motion is impossible outside Test mode**, by a hard throw in `arm()`; the wizard gets its own controller port | §7.3, §7.4 | The wizard's held-enable was the driver's right trigger, live in teleop on any practice field with no FMS |
| 2 | **kG bisection rebuilt**: physics-derived bracket, in-window position guard, aborts as signed measurements, real closed-loop `recentre()`, 18 → 10 iterations | §8.4 | Open-loop 3.6 V on a 1.2 V arm, position guard evaluated only *after* a 0.5 s window, `recentre()` referenced and never defined. It would have slammed a hard stop on iteration 1 |
| 3 | **`arm()` refuses without a trustworthy position reference** — `isHomed()`, `FeedbackSpec`, absolute-vs-rotor agreement, ARM zero re-checked at arm time | §3.1, §7.3.1 | Six preconditions, none of which was "the mechanism knows where it is" |
| 4 | **`getFeedbackVolts()` added to the SPI**; the steady-state rules gate on it and say so when it is absent | §3.1, §9.3.1 | `residualVolts` was not computable on `ON_MOTOR` — the recommended default — so the whole kS/kG diagnosis was dead code falling through to `kP *= 1.4` |
| 5 | **Supervisor band derived from travel, not from the margin**; `TravelLimits` rejects a margin under 2% of travel | §3.1, §7.1 | With the default `softMargin = 0` the supervisor band equalled the device soft limits and the documented guarantee was false |
| 6 | **Sim gate tests the envelope, not the fit**: 9 Monte-Carlo perturbed runs, promotion on containment; demoted from headline safety property | §7.6 | In sim the plant *is* `PlantPrior`, so the gate could not fail — pure friction with an escape hatch |
| 7 | **`PredictStep`** — the wizard asks, scores, and reports `Predictions: n/m`; `LqrSuggestStep` shows `wn`/`zeta` instead of an oracle number; `Lessons.WHAT_THE_SLIDERS_DO` | §8.4, §9.2.2, §13.5a | Eleven steps of read-then-watch-then-press-A, with no way for the wizard or a mentor to tell learning from button-mashing |
| 8 | **`TuningRecipe.express()`** — identification only, ~90 s, teaching mode default in sim only | §8.12 | Eight minutes x four mechanisms x one shared robot, raised as OQ#10 and not answered |
| 9 | **kG sign is measured, not assumed** — a 0.5 s zero-voltage drift probe opens the bisection | §6.5, §8.4 | `[0, 0.6*V]` bracket and `kG >= 0` hard-coded "positive position = up"; a downward-positive wrist got kG = 0 |
| 10 | `GainId.IZONE`/`IMAX_VOLTS` added; `setpoint` and the LQR sliders moved out of `/Tuning`; `with(KI, v)` substitutes a clamp instead of throwing | §3.2, §5.5 | The published schema had three topics `Gains` could not round-trip, and the review panel's first kI edit threw mid-session |

Everything above is pinned by a named test in §16. Where a fix depends on a claim we could not verify from a vendor javadoc, it is marked `[UNVERIFIED]` inline and repeated in Appendix B.

---

## 1. Scope & Responsibilities

This domain owns **everything between "the mechanism moves" and "the mechanism moves correctly."**

### 1.1 In scope — we own these

| # | Responsibility | Deliverable |
|---|---|---|
| 1 | Canonical gain type and unit system | `org.pumpkinlib.control.Gains` — volts-per-SI, one definition for every vendor |
| 2 | Live-tunable values over NetworkTables | `PumpkinTunable`, `TunableDouble`, `TunableGains`, `TuningRegistry` |
| 3 | Vendor write-through of gains | `GainSink` SPI + change-gated, rate-limited apply |
| 4 | On-robot system identification | `SysIdSweep` (wraps WPILib `SysIdRoutine`), `FeedforwardRegression` (streaming OLS) |
| 5 | Assisted feedback-gain derivation | `FeedbackDesigner` (LQR from measured kV/kA) + bounded step-response refinement |
| 6 | Guided, teaching, on-robot tuning wizard | `TuningWizard`, `TuningRecipe`, `TuningStep`, six built-in recipes |
| 7 | Response classification and plain-language coaching | `StepResponseAnalyzer`, `ResponseVerdict`, `Coach` |
| 8 | Safety supervision of every actuating routine | `TuningSupervisor`, `SafetyEnvelope`, `AbortReason` |
| 9 | Gain persistence and source write-back | `GainStore`, `gains.json`, `GainsExporter` (paste-ready Java) |
| 10 | The tuning UI surface and its NT schema | `TunerPublisher`, shipped `elastic-tuning-layout.json` |
| 11 | Teaching content | `Lessons` — real, written explanations shipped as data, published to NT |
| 12 | Mechanical pre-flight that must pass before tuning | `MechanicalHealthCheck` (backlash, asymmetric friction, encoder slip) |
| 13 | **Formative assessment** — the wizard asks the student to predict, then scores it | `PredictStep`, `/PumpkinTuner/predict/*`, a `Predictions: 7/9` line in the report |
| 14 | A fast path for the fourth mechanism of the day | `TuningRecipe.express()` — identification only, one narration screen, ~90 s |

### 1.2 Explicitly out of scope for this domain

- **Building the mechanism.** We consume a `TuningTarget` (§3.1); the mechanism domain constructs it.
- **Swerve module bring-up** (invert/offset discovery). That is the bring-up domain. We *consume* a correctly-brought-up module and tune its gains.
- **Logging and replay infrastructure.** AdvantageKit / Epilogue / `DataLogManager`. We publish; we do not own the logger.
- **Plotting applications.** AdvantageScope. We publish plot topics; we do not draw them.
- **Dashboards.** Elastic. We ship a layout JSON; we do not write a dashboard.
- **The control loops themselves.** `PIDController`, `ProfiledPIDController`, `ArmFeedforward`, `ElevatorFeedforward`, `SimpleMotorFeedforward`, `TrapezoidProfile`, `ExponentialProfile`, Phoenix 6 `Slot0Configs`, REVLib `ClosedLoopConfig`. We compute the *numbers that go in them*.

### 1.3 The one-sentence pitch

> Every other FRC tuning system shows a student **where the knobs are**. PumpkinLib tells them **which knob to turn next, why, and by how much** — and it does the arithmetic that a redeploy loop cannot do.

This is the headline feature and it is a genuinely unoccupied niche. The only FRC repo advertising an auto-tuner (`Prosper-FRC/utility-main-autoPIDTuner`) has a README and **no `src` directory** (web-tuning dossier, painPoints). YAMS ships Live Tuning but it is a manual slider panel. FrcCatalyst ships `TunableGains.checkAndApply()` but no recipe. SysId characterizes but does not teach and requires a laptop round trip.

---

## 2. Integration Points

### 2.1 What I need FROM other PumpkinLib domains

| From domain | What I need | Why |
|---|---|---|
| **Mechanisms (03)** | Every mechanism implements `org.pumpkinlib.tuning.TuningTarget` (§3.1) | The tuner is generic; it needs voltage-in / position-velocity-out / limits / plant prior |
| **Mechanisms (03)** | Mechanisms **consume** `org.pumpkinlib.control.Gains` as their gain type, and expose `applyGains(Gains)` that writes through to the vendor | Otherwise tuned values sit on a dashboard next to a controller that ignores them (this is exactly the failure in `C:/Users/ericj/GitHub/0000-XXXX-Robot-Template` — see §2.4) |
| **Mechanisms (03)** | `PlantPrior` (DCMotor, gearing, mass or MOI, drum radius or arm length) | Sanity-bounding the fit and generating the sim plant |
| **Mechanisms (03)** | `TravelLimits` (min, max, soft-limit margin) with soft limits already configured on the device | `TuningSupervisor` refuses to arm without them |
| **Hardware/vendor adapters (04)** | `GainSink` implementations for Phoenix 6, REVLib, and RIO-side wpimath | Canonical-volts → vendor-native conversion (§4) |
| **Hardware/vendor adapters (04)** | A `LoopLocation` declaration per mechanism: `ON_MOTOR` or `ON_CONTROLLER` | kP means different things and the refinement loop's dt differs |
| **Simulation (05)** | A sim-backed `TuningTarget` that is *bit-identical in API* to the real one | Sim-first promotion gate (§7.6) — the wizard runs the full recipe in sim before it will arm hardware |
| **Logging (06)** | An optional `TunableTransport` implementation backed by AdvantageKit `LoggedNetworkNumber` | Replay-safe tunables without a hard AdvantageKit dependency (§5.6) |
| **Health/alerts (07)** | A `PumpkinAlerts.register(group, text, type)` facade over `edu.wpi.first.wpilibj.Alert` | The Alert API is documented as unstable; we must not depend on it directly |
| **Project scaffold (08)** | `src/main/deploy/pumpkin/` created by the template, and `elastic-tuning-layout.json` served on port 5800 | Persistence baseline + zero-click UI |
| **Project scaffold (08)** | `RobotIdentity.current()` returning a `RobotId` (`SIM`, `COMP`, `PRACTICE`, ...) | The full *teaching* recipe is the default only in `SIM`; on hardware the wizard offers both and remembers the choice (§8.12) |
| **Project scaffold (08)** | `ControlMap.isPortRegistered(int port)` — the driver/operator controller port registry | `TuningWizard` refuses to share a controller with the driver without an explicit, logged acknowledgement (§7.4.2) |
| **Bring-up (09)** | Guarantee that encoder direction, gear ratio and zero offset are already correct before a `TuningTarget` is handed to us | Tuning a wrong-signed mechanism destroys hardware; we detect it (§8.2) but we must not be the primary defense |

### 2.2 What I provide TO other domains

- `org.pumpkinlib.control.Gains` — the canonical gain record every mechanism stores.
- `TuningRegistry.tunable(...)` — the general-purpose tunable-number primitive, usable by *any* domain (vision std-dev models, auto-align tolerances, drive speed scalars).
- `StepResponseAnalyzer` — a pure, HAL-free classifier reusable by the health domain for "is this mechanism still tuned?" checks between matches.
- `GainStore` — the persistence layer; other domains may register non-gain configuration values.
- `TuningSupervisor` — reusable safety envelope for *any* routine that commands raw voltage (the bring-up domain should use it too).

### 2.3 Hard external dependencies

Required (all already in the user's stack):
- `wpimath` — `ArmFeedforward`, `ElevatorFeedforward`, `SimpleMotorFeedforward`, `PIDController`, `ProfiledPIDController`, `TrapezoidProfile`, `ExponentialProfile`, `LinearSystemId`, `LinearQuadraticRegulator`, `Matrix`, `MatBuilder`, `VecBuilder`, `Nat`.
- `wpilibj` — `Timer`, `DriverStation`, `RobotBase`, `Alert`, `Filesystem`, `Preferences`.
- `wpilibNewCommands` — `SysIdRoutine`, `SysIdRoutineLog`, `Command`, `Subsystem`.
- `ntcore` — `NetworkTableInstance`, `DoubleEntry`, `StringPublisher`, `BooleanEntry`.
- `wpinet` — `edu.wpi.first.net.WebServer` (verified: `start(int port, String path)`, `stop(int port)`).

Optional (reflectively detected, never required):
- AdvantageKit (`org.littletonrobotics.junction.networktables.LoggedNetworkNumber`) — replay-safe tunables.
- Phoenix 6 / REVLib — only through `GainSink` implementations owned by domain 04.

**Zero third-party math dependencies.** No JGraphT, no Apache Commons, no EJML calls outside what wpimath already exposes.

### 2.4 Evidence this is the right scope

From the user's own repositories:

- `C:/Users/ericj/GitHub/0000-XXXX-Robot-Template/src/main/java/frc/robot/util/LoggedTunableNumber.java` exists, is well-documented, and **has zero call sites in the entire repo.** No IO class exposes `setGains`. Every gain is a `static final` in `Constants.java` behind a `// TUNE` marker. The template author *wanted* live tuning, wrote the primitive, and it never got wired — because wiring it per-mechanism is more work than redeploying once. **A tunable primitive without a mechanism contract that consumes it is dead code.** This is why §3.1 and the `applyGains` requirement are non-negotiable.
- `C:/Users/ericj/GitHub/8793-2026-Robot/src/main/java/frc/robot/subsystems/ShooterSubsystem.java:91-168` holds three `InterpolatingDoubleTreeMap`s of ~20 hand-measured points each, populated in a `static {}` block. Every re-measurement is an edit plus `./gradlew deploy`. Grep across `src/main/java` returns **14 `SmartDashboard.put*` calls and zero `getNumber`/`Preferences`** — the dashboard is write-only. This is a real, competition-season team with a genuinely sophisticated shoot-on-the-move solver that still cannot change a number at the field.
- `C:/Users/ericj/GitHub/8793-2026-Robot/src/main/java/frc/robot/constants/Constants.java:45` — `TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0;` with conversions written as `angle / 360.0 / GEAR_RATIO` in seven places and a comment admitting "gear ratio is negative, so signs cancel." **Any tuning system that hands a student a number in a unit they cannot reason about is making this worse.** §4 exists because of this line.

---

## 3. Core types

### 3.1 `TuningTarget` — the SPI the mechanism domain implements

This is the only thing the tuner knows about a mechanism. It is deliberately narrow: raw voltage in, SI state out, plus enough physical description to be safe and to sanity-check the answer.

```java
package org.pumpkinlib.tuning;

import edu.wpi.first.units.measure.Voltage;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.Optional;
import java.util.OptionalDouble;
import org.pumpkinlib.control.Gains;

/**
 * The seam between a mechanism and the tuning system.
 *
 * <p>Every quantity is in canonical SI: metres and metres/second for linear mechanisms,
 * radians and radians/second for rotational ones. {@link #units()} declares which.
 * Volts are always volts.
 *
 * <p>Implementations live in the mechanism domain. PumpkinLib ships adapters for its own
 * mechanism types; teams with hand-rolled subsystems implement this directly in ~30 lines.
 */
public interface TuningTarget {

  /** Unique, human-meaningful name. Becomes the NetworkTables namespace. Must not contain '/'. */
  String name();

  /** Which tuning recipe applies. */
  MechanismArchetype archetype();

  /** Linear (metres) or rotational (radians). */
  MechanismUnits units();

  // ---- open-loop actuation (used by every identification step) -----------------------

  /** Command a raw voltage. Must bypass any closed loop. Must respect device soft limits. */
  void setVoltage(double volts);

  /** Immediately neutral the mechanism. Called on every abort path. Must never throw. */
  void stop();

  // ---- measurement ------------------------------------------------------------------

  /** Position in metres or radians. For ARM, zero MUST be horizontal (see {@link #armZeroIsHorizontal()}). */
  double getPosition();

  /** Velocity in m/s or rad/s. */
  double getVelocity();

  /**
   * Acceleration in m/s^2 or rad/s^2. Implementations that have no acceleration signal should
   * return {@link Double#NaN}; the tuner will then difference velocity itself with a
   * {@link org.pumpkinlib.tuning.sysid.CentralDifferenceAccel} filter.
   */
  double getAcceleration();

  /** Applied motor voltage as actually measured, if the device reports it. Preferred over the commanded value. */
  OptionalDouble getAppliedVolts();

  /** Stator current, for the overcurrent abort. Empty disables that abort with a warning. */
  OptionalDouble getStatorCurrentAmps();

  /**
   * The feedback-only contribution of the closed loop this cycle, in volts — i.e. the total closed-loop
   * output minus whatever feedforward the loop applied. Empty when the split is not observable.
   *
   * <p>This is the quantity {@code residualVolts} (section 9.3) is built from, and it is what lets the
   * wizard say "raise kS" or "raise kG" instead of "raise kP". When it is empty, the entire
   * steady-state diagnosis branch is disabled and the student is told why — we do not guess.
   *
   * <p>Empty is a legitimate answer, not a bug. A {@link LoopLocation#ON_MOTOR} loop computes its
   * feedforward inside the device, and not every vendor reports the split back.
   */
  default OptionalDouble getFeedbackVolts() { return OptionalDouble.empty(); }

  // ---- position reference ------------------------------------------------------------

  /**
   * True when this mechanism's reported position corresponds to physical reality right now.
   *
   * <p>Every position-based abort in section 7.2 and every gravity-shaped voltage command in
   * section 8.4 is computed against {@link #getPosition()}. A mechanism that was moved by hand while
   * disabled, whose "homing" was a boot-time assumption, reports a position that is simply wrong —
   * and then every interlock is inert while the wizard commands voltage against a fictional angle.
   *
   * <p>The default is {@code false} for position archetypes and {@code true} for
   * {@link MechanismArchetype#FLYWHEEL} and {@link MechanismArchetype#DRIVE_VELOCITY}, which have no
   * meaningful absolute position. Implementations MUST override this for position mechanisms and
   * MUST return {@code false} when the only position reference is a boot-time assumption.
   */
  default boolean isHomed() {
    return archetype() == MechanismArchetype.FLYWHEEL
        || archetype() == MechanismArchetype.DRIVE_VELOCITY;
  }

  /** How this mechanism knows where it is. Checked by {@link TuningSupervisor#arm()}. */
  FeedbackSpec feedbackSpec();

  /**
   * The absolute sensor's reading in SI, when one exists, for the agreement check in
   * {@code arm()}. Empty when there is no absolute source.
   */
  default OptionalDouble getAbsolutePosition() { return OptionalDouble.empty(); }

  // ---- physical description ---------------------------------------------------------

  /** Hard travel limits in SI. Required; the supervisor refuses to arm without them. */
  TravelLimits limits();

  /** Physics prior used to sanity-bound the fit and to build the sim plant. */
  PlantPrior plantPrior();

  /** True if the ARM archetype's position zero is horizontal (gravity term is cos(theta)). */
  default boolean armZeroIsHorizontal() { return true; }

  // ---- gains -------------------------------------------------------------------------

  Gains getGains();

  /**
   * Apply gains. MUST write through to wherever the loop actually runs:
   * on-motor slot configs for {@link LoopLocation#ON_MOTOR}, or the wpimath controller
   * objects for {@link LoopLocation#ON_CONTROLLER}. Implementations must be idempotent
   * and must not perform CAN traffic when the gains are unchanged.
   */
  void applyGains(Gains gains);

  LoopLocation loopLocation();

  // ---- closed loop (used only by the kP/kD steps and the verify step) -----------------

  boolean supportsClosedLoop();

  /** Position setpoint (m or rad) for POSITION archetypes; velocity setpoint (m/s or rad/s) for VELOCITY ones. */
  void setClosedLoopGoal(double goal);

  /** True when a motion profile is in use, so the analyzer compares against the profile, not a step. */
  default boolean isProfiled() { return false; }

  // ---- scheduler integration ---------------------------------------------------------

  /** Subsystem to require, if the team uses command-based. Empty for state-based robots. */
  default Optional<Subsystem> requirement() { return Optional.empty(); }
}
```

Supporting value types:

```java
package org.pumpkinlib.tuning;

public enum MechanismArchetype {
  /** Velocity control, no gravity, high inertia. Shooter wheels, intake rollers under load. */
  FLYWHEEL,
  /** Velocity control of a swerve/tank drive motor. Same math as FLYWHEEL, different safety envelope. */
  DRIVE_VELOCITY,
  /** Position control, constant gravity term. Elevators, telescopes, linear slides. */
  ELEVATOR,
  /** Position control, cosine gravity term. Arms, pivots, wrists, hoods. */
  ARM,
  /** Position control, no gravity. Turrets, hoods with a counterbalance, azimuth stages. */
  TURRET,
  /** Position control of a swerve steer motor. TURRET math plus continuous wrap. */
  STEER;

  public boolean isPosition() { return this == ELEVATOR || this == ARM || this == TURRET || this == STEER; }
  public boolean isVelocity() { return this == FLYWHEEL || this == DRIVE_VELOCITY; }
  public boolean hasGravity() { return this == ELEVATOR || this == ARM; }
  public boolean isContinuous() { return this == STEER; }
}

public enum MechanismUnits { LINEAR_METRES, ROTATIONAL_RADIANS }

public enum LoopLocation {
  /** Phoenix 6 Slot0/MotionMagic, REVLib ClosedLoopController. Runs at ~1 kHz on the device. */
  ON_MOTOR,
  /** wpimath PIDController/ProfiledPIDController on the roboRIO. Runs at 50 Hz. */
  ON_CONTROLLER
}

/**
 * Where this mechanism's position reference comes from. The supervisor uses this to decide whether
 * {@link TuningTarget#getPosition()} can be trusted enough to arm a routine that commands voltage.
 */
public sealed interface FeedbackSpec {

  /** Only the motor's internal rotor sensor. Zero is wherever the robot booted. */
  record RotorOnly() implements FeedbackSpec {}

  /**
   * A homing routine ran and completed since the last power cycle (limit switch, hard-stop current
   * spike, or a mechanical index). {@code completedThisPowerCycle} is a live supplier, not a flag
   * captured at construction.
   */
  record HomedAgainstSwitch(java.util.function.BooleanSupplier completedThisPowerCycle)
      implements FeedbackSpec {}

  /** A true absolute sensor (CANcoder, through-bore absolute, duty-cycle encoder, potentiometer). */
  record Absolute(String sensorDescription) implements FeedbackSpec {}

  /** Absolute sensor fused with the rotor for resolution (Phoenix 6 FusedCANcoder, etc.). */
  record FusedAbsolute(String sensorDescription) implements FeedbackSpec {}

  /**
   * "Assume we booted at this position." Legal for robot code. NOT a position reference the tuner
   * will accept for a position archetype — see {@link TuningSupervisor#arm()} precondition 9.
   */
  record AssumeAtBoot(double assumedSi) implements FeedbackSpec {}

  default boolean isAbsolute() { return this instanceof Absolute || this instanceof FusedAbsolute; }
}

/**
 * All values in SI (m or rad).
 *
 * <p><b>{@code softMargin} is not optional.</b> The tuning supervisor's abort band is derived from
 * these limits and it must be strictly inside them, so that the supervisor always trips before the
 * device's own soft limit clamps silently. A zero margin makes that impossible, so it is rejected
 * at the type boundary rather than producing a supervisor that only appears to protect you.
 */
public record TravelLimits(double min, double max, double softMargin) {
  public TravelLimits {
    if (!(max > min)) throw new IllegalArgumentException("TravelLimits: max must exceed min");
    if (softMargin < 0) throw new IllegalArgumentException("TravelLimits: softMargin must be >= 0");
    if (softMargin < 0.02 * (max - min)) {
      throw new IllegalArgumentException(
          "TravelLimits.softMargin must be at least 2% of travel (" + 0.02 * (max - min) + "); "
        + "the tuning supervisor needs room to stop before the device soft limit does.");
    }
  }
  public double range() { return max - min; }
  public double softMin() { return min + softMargin; }
  public double softMax() { return max - softMargin; }
  public double centre() { return (min + max) / 2.0; }
  public boolean insideSoft(double x) { return x >= softMin() && x <= softMax(); }

  /**
   * A limit set meaning "unbounded", legal only for FLYWHEEL and DRIVE_VELOCITY.
   * Position aborts are disabled for these archetypes, so the 2%-of-travel rule does not apply;
   * the margin here exists only to satisfy the invariant.
   */
  public static TravelLimits unbounded() {
    return new TravelLimits(-1e9, 1e9, 1e8);
  }
}

/**
 * Physics prior. Used for three things and three things only:
 * (1) sanity-bounding a fitted gain, (2) building the sim plant, (3) deriving a first-guess
 * kV/kA when the student wants to skip identification. It is never used in place of measurement.
 */
public record PlantPrior(
    edu.wpi.first.math.system.plant.DCMotor motor,
    double gearingReduction,      // motor rotations per mechanism rotation (or per drum rotation), always POSITIVE
    double massKg,                // ELEVATOR / DRIVE_VELOCITY: moving mass. NaN if not applicable.
    double moiKgM2,               // ARM / TURRET / FLYWHEEL / STEER: moment of inertia. NaN if not applicable.
    double radiusMetres,          // ELEVATOR drum radius, DRIVE_VELOCITY wheel radius, ARM centre-of-mass length. NaN otherwise.
    double nominalVolts) {        // usually 12.0

  public PlantPrior {
    if (gearingReduction <= 0) {
      throw new IllegalArgumentException(
          "PlantPrior.gearingReduction must be positive. Encode direction with an invert flag on the "
        + "device, never with a negative ratio. (See 8793 Constants.java:45 for why.)");
    }
  }

  // ---- derived priors ---------------------------------------------------------------
  //
  // These five are the ONLY things the tuner is allowed to read out of a prior before a
  // measurement exists. They are used to bracket the kG bisection (section 8.4), to build the
  // provisional feedback controller that recentres between probes, to derive the SysId envelope
  // (section 6.2), and to sanity-bound the fit (section 6.5). Every one of them is a pure
  // function of the record's own components; none of them touches hardware.

  /** kV implied by the motor curve, volts per (m/s) or per (rad/s). See section 6.5. */
  public double kVprior() { /* -A(1,1)/B(1,0) of the LinearSystemId plant */ return 0; }

  /** kA implied by the motor curve, volts per (m/s^2) or per (rad/s^2). See section 6.5. */
  public double kAprior() { /* 1.0/B(1,0) */ return 0; }

  /**
   * Volts required to hold the gravity load at the worst-case pose, as a POSITIVE MAGNITUDE.
   * The sign is never assumed — it is measured by the drift probe in section 8.4.
   * {@code NaN} for archetypes without gravity.
   */
  public double gravityVoltsPrior() { /* section 6.5 kGprior */ return Double.NaN; }

  /** Free speed at the mechanism, m/s or rad/s. Used for velocity aborts and gentle profiles. */
  public double freeSpeedSi() { /* motor.freeSpeedRadPerSec / gearing, scaled by radius for LINEAR */ return 0; }

  /** Stall-torque-limited acceleration at the mechanism, m/s^2 or rad/s^2. */
  public double maxAccelSi() { /* nominalVolts / kAprior() */ return 0; }
}
```

> **Design note — the positive-gearing invariant.** `PlantPrior` throws on a negative reduction. This is a direct response to `C:/Users/ericj/GitHub/8793-2026-Robot/src/main/java/frc/robot/constants/Constants.java:45` (`TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0;`) and the seven downstream `angle / 360.0 / GEAR_RATIO` sites that rely on double sign cancellation. A tuner that fits a *negative* kV because the ratio was negative will hand a student a physically meaningless number and then a kP that drives the mechanism away from its setpoint. Making it unrepresentable at the type boundary is cheaper than diagnosing it.

### 3.2 `Gains` — the canonical gain record

```java
package org.pumpkinlib.control;

/**
 * Canonical PumpkinLib gains. ALL gains are expressed in <b>volts per SI unit</b>, matching
 * wpimath exactly:
 *
 * <pre>
 *   kS  volts                       (static friction)
 *   kV  volts / (unit/second)       (velocity)
 *   kA  volts / (unit/second^2)     (acceleration)
 *   kG  volts                       (gravity; multiplied by cos(theta) for ARM)
 *   kP  volts / unit                (proportional)
 *   kI  volts / (unit * second)     (integral)
 *   kD  volts / (unit/second)       (derivative)
 * </pre>
 *
 * where "unit" is metres for {@link org.pumpkinlib.tuning.MechanismUnits#LINEAR_METRES}
 * and radians for {@link org.pumpkinlib.tuning.MechanismUnits#ROTATIONAL_RADIANS}.
 *
 * <p>Conversion to Phoenix 6 output-per-rotation and REVLib duty-cycle-per-rotation happens
 * exactly once, in a {@link org.pumpkinlib.control.GainSink}. Team code never sees vendor units.
 *
 * <p>Integral gain is deliberately awkward: there is no {@code withI(double)}. Use
 * {@link #withIntegral(double, double, double)} so windup protection cannot be skipped.
 */
public record Gains(
    double kP, double kI, double kD,
    double kS, double kV, double kA, double kG,
    double iZone,          // |error| above which the integrator is held at zero. 0 = disabled band, integrator always active.
    double iMaxVolts,      // hard clamp on the integrator's voltage contribution.
    GravityType gravityType,
    ProfileConstraints profile,
    double toleranceSi) {  // "at goal" tolerance, metres or radians.

  public enum GravityType { NONE, ELEVATOR_STATIC, ARM_COSINE }

  /** Trapezoid or exponential profile constraints, in SI. */
  public record ProfileConstraints(Kind kind, double maxVelocity, double maxAcceleration) {
    public enum Kind { NONE, TRAPEZOID, EXPONENTIAL }
    public static ProfileConstraints none() { return new ProfileConstraints(Kind.NONE, 0, 0); }
  }

  public static Gains zero() {
    return new Gains(0, 0, 0, 0, 0, 0, 0, 0, 0,
        GravityType.NONE, ProfileConstraints.none(), 0.01);
  }

  // ---- fluent builders (all pure, all return new instances) --------------------------

  public Gains withPD(double kP, double kD) {
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, gravityType, profile, toleranceSi);
  }

  public Gains withFeedforward(double kS, double kV, double kA) {
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, gravityType, profile, toleranceSi);
  }

  public Gains withGravity(double kG, GravityType type) {
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, type, profile, toleranceSi);
  }

  /**
   * The ONLY way to enable integral gain. There is no {@code withI(double)} overload, on purpose.
   *
   * @param kI        volts per (unit * second)
   * @param iZone     |error| threshold above which the integrator is frozen at zero, in SI units.
   *                  Pass {@link Double#POSITIVE_INFINITY} to disable the zone (not recommended).
   * @param iMaxVolts absolute clamp on the integral term's voltage contribution. Must be > 0.
   */
  public Gains withIntegral(double kI, double iZone, double iMaxVolts) {
    if (kI != 0.0 && !(iMaxVolts > 0.0)) {
      throw new IllegalArgumentException(
          "Non-zero kI requires iMaxVolts > 0. Integral windup with no clamp is how arms slam. "
        + "If you are reaching for kI to fix steady-state error, you almost certainly need kS or kG instead.");
    }
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, gravityType, profile, toleranceSi);
  }

  public Gains withProfile(ProfileConstraints c) {
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, gravityType, c, toleranceSi);
  }

  public Gains withTolerance(double toleranceSi) {
    return new Gains(kP, kI, kD, kS, kV, kA, kG, iZone, iMaxVolts, gravityType, profile, toleranceSi);
  }

  /**
   * Single-gain setter used by the wizard, keyed by {@link GainId}. Every id published to
   * {@code /Tuning/<Mechanism>/} round-trips through this method and {@link #get(GainId)}; that
   * bijection is pinned by {@code GainsTest} so the NT schema can never drift ahead of the record.
   *
   * <p>{@code KI} is the one case that substitutes rather than assigning verbatim. A student
   * enabling kI from the review panel of a {@link #zero()} gain set would otherwise trip
   * {@link #withIntegral}'s {@code iMaxVolts > 0} guard and take the wizard down mid-session.
   * Instead we supply a conservative clamp and say so out loud — see
   * {@link #integralSubstitutionNote(Gains, Gains)}, which the wizard calls on every mutation.
   */
  public Gains with(GainId id, double value) {
    return switch (id) {
      case KP -> withPD(value, kD);
      case KD -> withPD(kP, value);
      case KS -> withFeedforward(value, kV, kA);
      case KV -> withFeedforward(kS, value, kA);
      case KA -> withFeedforward(kS, kV, value);
      case KG -> withGravity(value, gravityType);
      case KI -> withIntegral(
          value,
          iZone == 0 ? Double.POSITIVE_INFINITY : iZone,
          iMaxVolts > 0 ? iMaxVolts : Math.min(1.0, 0.1 * NOMINAL_VOLTS));
      case IZONE -> withIntegral(kI, value, iMaxVolts);
      case IMAX_VOLTS -> withIntegral(kI, iZone, value);
      case TOLERANCE -> withTolerance(value);
      case PROFILE_MAX_VELOCITY -> withProfile(new ProfileConstraints(profile.kind(), value, profile.maxAcceleration()));
      case PROFILE_MAX_ACCELERATION -> withProfile(new ProfileConstraints(profile.kind(), profile.maxVelocity(), value));
    };
  }

  /** 12.0 V. Used only to size the default integrator clamp in {@link #with(GainId, double)}. */
  private static final double NOMINAL_VOLTS = 12.0;

  /**
   * Non-empty when a {@code with(KI, ...)} call had to substitute a clamp or a zone. Deliberately
   * <b>static and comparative</b> rather than an instance method: {@code Gains} is a value record
   * with no hidden state, so "did the last builder call substitute something" is a property of the
   * <i>pair</i> of values, not of either one. The wizard calls this immediately after every
   * {@code with(...)} and publishes the result verbatim to {@code /PumpkinTuner/result/warnings},
   * so the substitution is never silent:
   *
   * <p>{@code "Enabling kI also set an integrator clamp of 1.0 V, because integral windup with no
   * clamp is how arms slam. Change it in the panel if you need more."}
   */
  public static java.util.Optional<String> integralSubstitutionNote(Gains before, Gains after) {
    if (after.kI() == 0.0 || before.kI() != 0.0) return java.util.Optional.empty();
    StringBuilder sb = new StringBuilder();
    if (before.iMaxVolts() <= 0 && after.iMaxVolts() > 0) {
      sb.append("Enabling kI also set an integrator clamp of ")
        .append(String.format("%.1f", after.iMaxVolts()))
        .append(" V, because integral windup with no clamp is how arms slam. ")
        .append("Change it in the panel if you need more.");
    }
    if (before.iZone() == 0 && Double.isInfinite(after.iZone())) {
      sb.append(sb.length() > 0 ? " " : "")
        .append("The I-zone is disabled, so the integrator is always active. ")
        .append("Set an I-zone if this mechanism ever sits far from its target.");
    }
    return sb.length() == 0 ? java.util.Optional.empty() : java.util.Optional.of(sb.toString());
  }

  public double get(GainId id) {
    return switch (id) {
      case KP -> kP; case KI -> kI; case KD -> kD;
      case KS -> kS; case KV -> kV; case KA -> kA; case KG -> kG;
      case IZONE -> iZone;
      case IMAX_VOLTS -> iMaxVolts;
      case TOLERANCE -> toleranceSi;
      case PROFILE_MAX_VELOCITY -> profile.maxVelocity();
      case PROFILE_MAX_ACCELERATION -> profile.maxAcceleration();
    };
  }

  public enum GainId {
    KS("kS", "V"), KV("kV", "V/(unit/s)"), KA("kA", "V/(unit/s^2)"), KG("kG", "V"),
    KP("kP", "V/unit"), KI("kI", "V/(unit*s)"), KD("kD", "V/(unit/s)"),
    IZONE("iZone", "unit"),
    IMAX_VOLTS("iMaxVolts", "V"),
    TOLERANCE("tolerance", "unit"),
    PROFILE_MAX_VELOCITY("profile/maxVelocity", "unit/s"),
    PROFILE_MAX_ACCELERATION("profile/maxAcceleration", "unit/s^2");

    private final String key; private final String unit;
    GainId(String key, String unit) { this.key = key; this.unit = unit; }
    public String key() { return key; }
    public String unitTemplate() { return unit; }
    public String unitFor(org.pumpkinlib.tuning.MechanismUnits u) {
      return unit.replace("unit", u == org.pumpkinlib.tuning.MechanismUnits.LINEAR_METRES ? "m" : "rad");
    }
  }
}
```

### 3.3 Building the wpimath objects from `Gains`

Team code never does this by hand; `Controllers` does it and is the only place `calculateWithVelocities` is called.

```java
package org.pumpkinlib.control;

import edu.wpi.first.math.controller.*;
import edu.wpi.first.math.trajectory.ExponentialProfile;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import org.pumpkinlib.tuning.MechanismArchetype;

public final class Controllers {
  private Controllers() {}

  /** Live-mutable feedforward. Retuning calls setKs/setKv/setKa/setKg — never reallocates. */
  public sealed interface Feedforward permits SimpleFf, ElevatorFf, ArmFf {
    /**
     * Discrete plant-inversion feedforward for the CURRENT loop iteration.
     *
     * @param positionRad arm angle in radians (ignored by SimpleFf and ElevatorFf)
     * @param currentVelocity this cycle's profile velocity setpoint
     * @param nextVelocity next cycle's profile velocity setpoint
     */
    double calculate(double positionRad, double currentVelocity, double nextVelocity);
    void update(Gains g);
  }

  static final class SimpleFf implements Feedforward {
    private final SimpleMotorFeedforward ff;
    SimpleFf(Gains g, double dt) { ff = new SimpleMotorFeedforward(g.kS(), g.kV(), g.kA(), dt); }
    @Override public double calculate(double pos, double cur, double next) {
      return ff.calculateWithVelocities(cur, next);   // NOT the deprecated calculate(vel, accel)
    }
    @Override public void update(Gains g) { ff.setKs(g.kS()); ff.setKv(g.kV()); ff.setKa(g.kA()); }
  }

  static final class ElevatorFf implements Feedforward {
    private final ElevatorFeedforward ff;
    ElevatorFf(Gains g, double dt) { ff = new ElevatorFeedforward(g.kS(), g.kG(), g.kV(), g.kA(), dt); }
    @Override public double calculate(double pos, double cur, double next) {
      return ff.calculateWithVelocities(cur, next);
    }
    @Override public void update(Gains g) { ff.setKs(g.kS()); ff.setKg(g.kG()); ff.setKv(g.kV()); ff.setKa(g.kA()); }
  }

  static final class ArmFf implements Feedforward {
    private final ArmFeedforward ff;
    ArmFf(Gains g, double dt) { ff = new ArmFeedforward(g.kS(), g.kG(), g.kV(), g.kA(), dt); }
    @Override public double calculate(double posRad, double cur, double next) {
      return ff.calculateWithVelocities(posRad, cur, next);
    }
    @Override public void update(Gains g) { ff.setKs(g.kS()); ff.setKg(g.kG()); ff.setKv(g.kV()); ff.setKa(g.kA()); }
  }

  public static Feedforward feedforward(MechanismArchetype a, Gains g, double dtSeconds) {
    return switch (a) {
      case ARM -> new ArmFf(g, dtSeconds);
      case ELEVATOR -> new ElevatorFf(g, dtSeconds);
      case FLYWHEEL, DRIVE_VELOCITY, TURRET, STEER -> new SimpleFf(g, dtSeconds);
    };
  }

  /**
   * Build a motion profile straight from measured kV/kA. This is the single reason a student
   * never has to guess a max velocity or max acceleration again.
   */
  public static ExponentialProfile exponentialProfile(Gains g, double maxInputVolts) {
    return new ExponentialProfile(
        ExponentialProfile.Constraints.fromCharacteristics(maxInputVolts, g.kV(), g.kA()));
  }

  public static TrapezoidProfile trapezoidProfile(Gains g) {
    return new TrapezoidProfile(
        new TrapezoidProfile.Constraints(g.profile().maxVelocity(), g.profile().maxAcceleration()));
  }

  /** Feedback controller. Continuous input is enabled automatically for STEER. */
  public static PIDController pid(MechanismArchetype a, Gains g, double dtSeconds) {
    var c = new PIDController(g.kP(), g.kI(), g.kD(), dtSeconds);
    c.setTolerance(g.toleranceSi());
    if (g.kI() != 0.0) c.setIZone(g.iZone());
    if (a.isContinuous()) c.enableContinuousInput(-Math.PI, Math.PI);
    return c;
  }
}
```

> **Verified:** `ArmFeedforward.calculateWithVelocities(double currentAngle, double currentVelocity, double nextVelocity)`, `ElevatorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)`, `SimpleMotorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)` all exist in WPILib 2026, and both `calculate(pos, vel, accel)` and `calculate(angle, curVel, nextVel, dt)` are `@Deprecated(forRemoval = true, since = "2025")`. All three classes expose `setKs/setKv/setKa` (`setKg` on Arm/Elevator), so live retuning never reallocates. `ExponentialProfile.Constraints.fromCharacteristics(double maxInput, double kV, double kA)` is verified.

---

## 4. Canonical units and the vendor boundary

### 4.1 The problem this solves

The same physical mechanism, the same physical behaviour, three different numbers:

| Where the loop runs | kP unit | Same steer motor's real starting kP |
|---|---|---|
| wpimath on the RIO | volts per radian | ~7 (derived) |
| Phoenix 6 `Slot0Configs` (VoltageOut) | output per *rotation* | 100 (CTRE Tuner X generated) |
| REVLib `ClosedLoopConfig` | duty cycle per rotation | 0.01 (YAGSL SparkMax default) |

That is a 10,000× spread for one mechanism. `C:/Users/ericj/GitHub/0000-XXXX-Robot-Template/src/main/java/frc/robot/subsystems/swerve/ModuleIOTalonFX.java:79-98` already fights this by hand (`config.Slot0.kV = SwerveConstants.DRIVE_kV * 2.0 * Math.PI;`) with a unit test pinning the invariant. Good instinct, wrong layer.

**Decision:** PumpkinLib gains are *always* volts-per-SI. Conversion happens exactly once, in a `GainSink`. Every number the tuner shows, stores, or writes back to source is in these units. A number a student learns from the WPILib arm tutorial transfers unchanged to their Kraken or their NEO.

### 4.2 `GainSink`

```java
package org.pumpkinlib.control;

import org.pumpkinlib.tuning.MechanismUnits;

/**
 * Converts canonical volts-per-SI gains into whatever the actual control loop wants,
 * and applies them. Implemented once per vendor by domain 04.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>be idempotent — {@code apply(g)} twice with the same {@code g} performs no bus traffic;</li>
 *   <li>never allocate on the no-change path;</li>
 *   <li>return false and raise an alert rather than throwing when the device rejects a config.</li>
 * </ul>
 */
public interface GainSink {

  /** @return true if the gains were accepted (or unchanged); false if the device rejected them. */
  boolean apply(Gains gains);

  /** Human-readable description of the conversion, dumped to the log at boot and shown in the UI. */
  String describeConversion();

  /**
   * Metres-per-mechanism-rotation (LINEAR) or radians-per-mechanism-rotation (ROTATIONAL = 2*pi).
   * This is the ONLY number a Phoenix/REV sink needs to convert every gain.
   */
  double siUnitsPerMechanismRotation();
}
```

Conversion rules, stated once so domain 04 has no room to guess. Let `U = siUnitsPerMechanismRotation()` (metres per drum-or-wheel rotation for LINEAR, `2*PI` for ROTATIONAL), and assume the device's feedback is configured so **one device "rotation" equals one mechanism rotation** (Phoenix `FeedbackConfigs.SensorToMechanismRatio`, REV `ClosedLoopConfig` with a position conversion factor):

| Canonical | Phoenix 6 `Slot0Configs` with a *Voltage* request | REVLib `ClosedLoopConfig`, voltage-compensated at `Vnom` |
|---|---|---|
| `kP` [V/SI] | `kP * U` (V per mechanism rotation) | `kP * U / Vnom` (duty cycle per rotation) |
| `kI` [V/(SI·s)] | `kI * U` | `kI * U / Vnom` |
| `kD` [V/(SI/s)] | `kD * U` (V per rps) | `kD * U / Vnom` **[UNVERIFIED]** — REV's derivative time base is per-second while Phoenix's is per-rps; the scalar conversion is exact for Phoenix and approximate for REV. Report this in `describeConversion()`. |
| `kS` [V] | `kS` (unchanged) | `kS / Vnom` |
| `kV` [V/(SI/s)] | `kV * U` (V per rps) | `kV * U / (60 * Vnom)` — REV velocity is **RPM** |
| `kA` [V/(SI/s²)] | `kA * U` | `kA * U / (60 * Vnom)` |
| `kG` [V], `ELEVATOR_STATIC` | `kG`, `GravityType = Elevator_Static` | `kG / Vnom` into REV's `kG` |
| `kG` [V], `ARM_COSINE` | `kG`, `GravityType = Arm_Cosine` | `kG / Vnom` into REV's `kCos` (plus `kCosRatio` if the encoder is not 1:1 with the arm) |

Two hard requirements on the Phoenix sink:

1. When the archetype is `ARM_COSINE`, Phoenix's cosine reference is the *device's* position zero. Domain 04 must configure `FeedbackConfigs.FeedbackRotorOffset` (or a fused CANcoder magnet offset) so that device-zero is arm-horizontal, and `TuningTarget.armZeroIsHorizontal()` must report the truth. The wizard has a dedicated check for this (§8.4, the three-angle kG test).
2. `StaticFeedforwardSignValue.UseClosedLoopSign` should be set for position loops so kS is applied in the direction of *error*, not of measured velocity — otherwise kS dithers when the mechanism is stopped at its setpoint.

`describeConversion()` output is dumped to the log at boot and rendered in the UI, e.g.:

```
Elevator gain conversion (Phoenix 6, VoltageOut, on-motor loop)
  1 mechanism rotation = 0.0879 m of travel  (drum 5.5 in circumference x 2 stages)
  kP 12.000 V/m       -> Slot0.kP 1.0546  (V per mechanism rotation)
  kV  3.070 V/(m/s)   -> Slot0.kV 0.2699  (V per rps)
  kG  2.280 V         -> Slot0.kG 2.2800, GravityType = Elevator_Static
  kD  0.400 V/(m/s)   -> Slot0.kD 0.0352
```

That block alone answers guineawheek's "which knob is misconfigured?" question for the whole class of unit bugs.

---

## 5. Tunable values infrastructure

### 5.1 Requirements, and where each comes from

| Requirement | Source |
|---|---|
| Publish plain NT4 doubles under `/Tuning/<Mechanism>/<gain>` | AdvantageScope tuning mode reads the `/Tuning` table; Elastic Text Display and Number Slider are editable. This is the only path that works in **both** with zero setup. |
| No AdvantageKit dependency | 6328's `LoggedTunableNumber` requires `LoggedNetworkNumber`; `TunableControls` requires AdvantageKit outright. Small teams frequently do not run it (8793 does not). |
| Never touch SmartDashboard or Shuffleboard | Both deleted in WPILib 2027. YAMS's `NT:/SmartDashboard/.../Live Tuning` path dies in January. |
| Default-deny under FMS, with a constant-time disabled path | DogLog gates by default; 6328's does not. FrcCatalyst returns cached defaults in constant time. |
| Mechanism name required at construction | `TunableControls`' stated motivation is collisions between controller instances sharing a name. |
| Change-gated vendor writes | FrcCatalyst calls `checkAndApply(motor)` every periodic; unguarded that is a CAN flood. |
| Unit + range metadata | DogLog carries units so AdvantageScope renders them. 6328's does not. |
| A path from a good dashboard value back into source | Nothing in the ecosystem does this (see §10). |

### 5.2 `TuningRegistry` — the single entry point

```java
package org.pumpkinlib.tuning;

/**
 * Process-wide owner of every tunable value and every {@link TuningTarget}.
 *
 * <p>Tuning mode is OFF by default under FMS and ON otherwise. When off, every
 * {@code get()} returns a cached primitive with zero NetworkTables traffic and zero allocation.
 */
public final class TuningRegistry {

  private static final String TABLE = "Tuning";   // -> NT topic prefix "/Tuning"

  private TuningRegistry() {}

  // ---- global mode -------------------------------------------------------------------

  /**
   * Enable or disable live tuning. Call once from robot construction.
   * The default is {@code !DriverStation.isFMSAttached()}, re-evaluated at every
   * disabled-to-enabled edge so a mid-session FMS connection disables tuning.
   */
  public static void setTuningEnabled(boolean enabled) { /* ... */ }

  public static boolean isTuningEnabled() { /* ... */ return false; }

  /**
   * Opt in to live tuning while connected to an FMS. Raises a persistent warning alert
   * for as long as it is active. There is no way to do this silently.
   */
  public static void allowUnderFms() { /* ... */ }

  // ---- tunable creation --------------------------------------------------------------

  /** A standalone tunable double, e.g. {@code TuningRegistry.tunable("Vision", "maxTagDistance", 6.0, "m")}. */
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit) { /* ... */ return null; }

  /** With a slider range, which the layout generator uses to emit a Number Slider instead of a Text Display. */
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit,
                                      double min, double max) { /* ... */ return null; }

  /** The whole gain set for one mechanism. This is what mechanisms use. */
  public static TunableGains gains(TuningTarget target) { /* ... */ return null; }

  // ---- target registration -----------------------------------------------------------

  /** Register a mechanism with the wizard. Duplicate names raise an error alert and are rejected. */
  public static void register(TuningTarget target) { /* ... */ }

  public static java.util.List<TuningTarget> targets() { /* ... */ return java.util.List.of(); }

  // ---- lifecycle ---------------------------------------------------------------------

  /**
   * Call once per robot loop, BEFORE subsystem periodic. Polls NT for changed tunables,
   * fires callbacks, and publishes metadata. Costs one boolean check when tuning is disabled.
   */
  public static void periodic() { /* ... */ }
}
```

### 5.3 `TunableDouble`

```java
package org.pumpkinlib.tuning;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * An NT4-backed double with a compile-time default.
 *
 * <p>Wire format is a plain double topic at {@code /Tuning/<namespace>/<key>}, which is exactly
 * what AdvantageScope's tuning mode edits and what Elastic's Text Display and Number Slider bind to.
 *
 * <p>Three ways to consume a change, in increasing order of preference:
 * <ol>
 *   <li>{@link #get()} every loop - simplest, always correct, costs a cached field read;</li>
 *   <li>{@link #hasChanged(int)} with {@code hashCode()} as the id - the 6328 idiom, kept for
 *       source compatibility with existing team code;</li>
 *   <li>{@link #onChange(DoubleConsumer)} - the DogLog idiom, and the one PumpkinLib uses
 *       internally, because it removes the change-detection {@code if} from user code entirely.</li>
 * </ol>
 */
public final class TunableDouble implements DoubleSupplier {

  /** Current value. When tuning is disabled this returns the cached default with no NT read. */
  public double get() { /* ... */ return 0; }

  @Override public double getAsDouble() { return get(); }

  /** The compile-time default, regardless of tuning mode. */
  public double defaultValue() { /* ... */ return 0; }

  /**
   * Per-caller change detection. Pass a stable id; {@code hashCode()} of the calling object is
   * the recommended value, which is what lets two subsystems watch one tunable independently.
   */
  public boolean hasChanged(int id) { /* ... */ return false; }

  /** Register a callback fired from {@link TuningRegistry#periodic()} whenever the value changes. */
  public TunableDouble onChange(DoubleConsumer action) { /* ... */ return this; }

  /** Fires once when ANY of {@code others} changes, handing back all values in declaration order. */
  public static void ifChanged(int id, java.util.function.Consumer<double[]> action, TunableDouble... others) { /* ... */ }

  /** Overwrite the live value from code (the wizard does this when a step is accepted). */
  public void set(double value) { /* ... */ }

  public String fullKey() { /* ... */ return ""; }   // e.g. "/Tuning/Elevator/kP"
  public String unit() { /* ... */ return ""; }
}
```

**Implementation contract.**

- Backed by a single `edu.wpi.first.networktables.DoubleEntry` obtained from
  `NetworkTableInstance.getDefault().getTable("Tuning").getSubTable(namespace).getDoubleTopic(key).getEntry(defaultValue)`,
  with `setDefault(defaultValue)` called once at construction.
- `TuningRegistry.periodic()` performs exactly one `entry.get()` per tunable per loop and caches it in a `double` field. `get()` reads the field. There is no NT access on the hot path.
- When tuning is disabled, `periodic()` returns after one `if` and the cached field holds the compile-time default forever. Zero NT reads, zero allocation, zero CAN traffic. This matches FrcCatalyst's constant-time disabled path.
- Change detection compares with `!=` on the raw double, exactly as 6328 does. No epsilon: a dashboard edit is always an exact new value, and an epsilon would silently swallow a deliberate 4-decimal kG adjustment, which is precisely the change WPILib's arm tutorial says you must be able to make.
- Publishing is one-shot at construction. There is no periodic re-publish, so a stopped robot's tunables do not churn the NT server.

### 5.4 `TunableGains` - write-through, change-gated, rate-limited

```java
package org.pumpkinlib.tuning;

import org.pumpkinlib.control.Gains;

/**
 * The full gain set for one mechanism, published as editable doubles under
 * {@code /Tuning/<Mechanism>/}. When any gain changes, the new {@link Gains} is pushed
 * through the mechanism's {@link TuningTarget#applyGains(Gains)}, so a slider move actually
 * reaches the Slot0Configs instead of sitting in an NT table next to them.
 */
public final class TunableGains {

  /** Current gains. Cheap; returns a cached immutable record. */
  public Gains get() { /* ... */ return Gains.zero(); }

  /**
   * Poll NT, and if anything changed, call {@link TuningTarget#applyGains}. Called for you by
   * {@link TuningRegistry#periodic()}; you never write {@code checkAndApply} yourself.
   *
   * <p>Rate limiting: at most one apply per {@link #MIN_APPLY_PERIOD_SEC} seconds per mechanism,
   * and only when at least one gain actually differs. Dragging a slider therefore produces
   * about 10 CAN config writes per second, not 50.
   */
  void checkAndApply() { /* package-private; called by the registry */ }

  public static final double MIN_APPLY_PERIOD_SEC = 0.100;

  /** Overwrite from code (the wizard does this on Accept). Publishes to NT and applies immediately. */
  public void set(Gains gains) { /* ... */ }

  /** Revert every gain to its compile-time default and apply. Bound to the wizard's global Abort. */
  public void resetToDefaults() { /* ... */ }

  /** The exact conversion that will be performed, for display. */
  public String describeConversion() { /* ... */ return ""; }
}
```

### 5.5 NetworkTables schema for tunables

`/Tuning/<Mechanism>/` contains **only editable gain doubles**, so AdvantageScope's tuning tab stays clean. There is **exactly one topic per `Gains.GainId`** — twelve of them — and the mapping is the identity `topic == GainId.key()`:

```
/Tuning/<Mechanism>/kP                        double   GainId.KP
/Tuning/<Mechanism>/kI                        double   GainId.KI
/Tuning/<Mechanism>/kD                        double   GainId.KD
/Tuning/<Mechanism>/kS                        double   GainId.KS
/Tuning/<Mechanism>/kV                        double   GainId.KV
/Tuning/<Mechanism>/kA                        double   GainId.KA
/Tuning/<Mechanism>/kG                        double   GainId.KG
/Tuning/<Mechanism>/iZone                     double   GainId.IZONE
/Tuning/<Mechanism>/iMaxVolts                 double   GainId.IMAX_VOLTS
/Tuning/<Mechanism>/tolerance                 double   GainId.TOLERANCE
/Tuning/<Mechanism>/profile/maxVelocity       double   GainId.PROFILE_MAX_VELOCITY
/Tuning/<Mechanism>/profile/maxAcceleration   double   GainId.PROFILE_MAX_ACCELERATION
```

**`setpoint` is not here, and neither are the LQR sliders.** `setpoint` is a poke target, not a gain — it commands motion, it is not something `Gains` can hold, and `TunableGains.checkAndApply()` has nothing to do with it. The two `LqrSuggestStep` sliders are *student preferences*, not gains: they are inputs to a solver, they are never written to a motor controller, and they are not persisted in the `gains` block of `gains.json`. All three live outside `/Tuning`:

```
/PumpkinTuner/<Mechanism>/setpoint            double   (manual poke target; the wizard also drives it)
/PumpkinTuner/<Mechanism>/lqr/maxError        double   (section 9.2 slider, SI)
/PumpkinTuner/<Mechanism>/lqr/maxVolts        double   (section 9.2 slider, V)
```

`TunableGains` builds its topic list by iterating `GainId.values()`, so the schema above is generated, not typed. `NtSchemaTest` (§16.2) asserts the two sets are equal in both directions: every `GainId` has a topic and every topic under `/Tuning/<Mechanism>/` maps to a `GainId` that `Gains.with(...)` and `Gains.get(...)` both handle. A gain that can be published but not applied is the exact failure this test exists to make impossible.

Metadata lives **outside** `/Tuning`, as one JSON string per mechanism, so it never clutters the tuning tab:

```
/PumpkinTuner/Mechanisms/<Mechanism>/meta     string (JSON)
```

```json
{
  "name": "Elevator",
  "archetype": "ELEVATOR",
  "units": "LINEAR_METRES",
  "loopLocation": "ON_MOTOR",
  "limits": { "min": 0.0, "max": 1.60, "softMargin": 0.05 },
  "gains": {
    "kP": { "unit": "V/m",       "min": 0.0, "max": 60.0, "step": 0.1,    "source": "WIZARD_LQR" },
    "kG": { "unit": "V",         "min": 0.0, "max": 6.0,  "step": 0.0005, "source": "WIZARD_BISECTION" },
    "kV": { "unit": "V/(m/s)",   "min": 0.0, "max": 20.0, "step": 0.01,   "source": "WIZARD_OLS" },
    "kA": { "unit": "V/(m/s^2)", "min": 0.0, "max": 5.0,  "step": 0.01,   "source": "WIZARD_OLS" }
  },
  "supervisorBand": { "positionMin": 0.08, "positionMax": 1.52 },
  "conversion": "1 mechanism rotation = 0.0879 m; kP 12.000 V/m -> Slot0.kP 1.0546",
  "libraryVersion": "0.1.0",
  "wpilibVersion": "2026.2.1"
}
```

`source` is one of `CODE_DEFAULT`, `DEPLOY_FILE`, `ROBOT_FILE`, `DASHBOARD`, `WIZARD_OLS`, `WIZARD_BISECTION`, `WIZARD_LQR`, `WIZARD_REFINE`. It is what makes "where did this number come from?" answerable from the dashboard alone, which is the debuggability bar the small-team research sets.

### 5.6 Interaction with AdvantageKit replay

This is the subtle part and it must be right, because the user's own template
(`C:/Users/ericj/GitHub/0000-XXXX-Robot-Template`) makes deterministic replay a
non-negotiable architectural constraint.

**The hazard.** A tunable read from NetworkTables is an *input* to robot code. AdvantageKit replays inputs from the log; anything read outside an IO layer is not replayed, so a replay run silently uses the deploy-time default while the real run used a dashboard value. Outputs diverge, with no error.

**The fix: a transport SPI, resolved reflectively.**

```java
package org.pumpkinlib.tuning;

/** How a tunable's value crosses from the dashboard into robot code. */
public interface TunableTransport {

  /** Register a topic and its default. */
  Handle register(String fullKey, double defaultValue);

  interface Handle {
    double get();
    void set(double value);
  }

  /** Human-readable name shown in the boot log and the UI. */
  String describe();
}
```

Two implementations:

| Implementation | When selected | Replay behaviour |
|---|---|---|
| `Nt4TunableTransport` | Default. AdvantageKit not on the classpath. | Values are **also** mirrored into the log under `/RealOutputs/Tuning/<Mechanism>/<gain>` every loop so a post-hoc reader can see what was used, but a replay run uses compile-time defaults. |
| `AdvantageKitTunableTransport` | Selected automatically when `org.littletonrobotics.junction.networktables.LoggedNetworkNumber` resolves. Constructed reflectively; PumpkinLib has no compile-time reference to AdvantageKit. | `LoggedNetworkNumber` records the value as a **logged input**, so replay reproduces the exact dashboard value that was live at the time. Fully replay-safe. |

Rules the implementation must obey:

1. **Never call `Timer.getFPGATimestamp()`.** All timestamps come from `Timer.getTimestamp()`, or from an injected `DoubleSupplier clock` so unit tests and replay can drive time. This is stated explicitly in the template's `Superstructure.java:85-87` and `Vision.java:73-75`.
2. **No `HashMap` iteration-order dependence** in anything that produces an output. `TuningRegistry` stores tunables in a `LinkedHashMap` keyed by full NT key and iterates in insertion order.
3. **No `Math.random()`, no background threads.** Sweeps are driven from the main loop; the OLS accumulation happens inline.
4. **The wizard refuses to arm in REPLAY mode** and publishes `state = "DISABLED_REPLAY"`. Actuating a mechanism during a replay is nonsensical; re-running the *fit* against replayed data is a feature we expose separately (§8.8, offline refit).
5. If AdvantageKit is on the classpath but the plain NT transport was forced, raise a warning alert: `"AdvantageKit detected but PumpkinLib tunables are using plain NT4 - replay will not reproduce dashboard values."`

### 5.7 The WPILib 2027 `Tunable` API

**Verified 2026-08-06:** `wpilibsuite/allwpilib` PR **#7773, "[telemetry] Add Telemetry and Tunable APIs"** by PeterJohnson is **open, not merged**, targeting the **2027 Alpha 7** milestone. It introduces `Tunable<T>`, a `TunableRegistry`, and backends including `NetworkTablesTunableBackend` and `DataLogTunableBackend`, publishing under a configurable prefix (default `/Tunables`), with `onTune` callbacks. Reviews as of the fetch date flag unresolved concerns about mutex handling during callback execution. Exact final class and method names are therefore **[UNVERIFIED]** and must not be coded against.

**Decision: we plan for it, we do not depend on it, and we do not race it.**

1. `TunableTransport` (§5.6) is the seam. When WPILib 2027 ships a merged Tunable API, we add a third implementation, `WpilibTunableTransport`, and select it by default on the 2027 branch. `TunableDouble`'s public surface does not change.
2. We keep publishing at `/Tuning/<Mechanism>/<gain>` for 2026, because AdvantageScope's tuning mode reads `/Tuning` today. On the 2027 branch we publish to **both** paths for one season (WPILib's path as source of truth, `/Tuning` as a read-only mirror) and drop the mirror in 2028.
3. We do **not** attempt source compatibility with an unmerged PR.
4. If the PR merges before PumpkinLib 0.1 ships, this section is the only thing that changes.

### 5.8 Performance budget

Loop overruns were attributed to competing libraries repeatedly in 2026, and at least one team's response was to delete their telemetry entirely. The tuning layer must be measurably free:

| Path | Budget | How |
|---|---|---|
| Tuning disabled (FMS) | **< 5 us/loop total**, zero allocation | One boolean check in `TuningRegistry.periodic()`; early return. |
| Tuning enabled, nothing changed | **< 60 us/loop** for 12 mechanisms x 12 gains | One `DoubleEntry.get()` per topic (144 calls — `GainId.values().length` per mechanism, no more), no allocation, no CAN. |
| Tuning enabled, one gain changed | **< 400 us** on the changing loop | One `Gains` record allocation, one `GainSink.apply()`, rate-limited to 10 Hz per mechanism. |
| Wizard running a sweep | **< 150 us/loop** | 4 multiply-adds per regression column (at most 20 fused ops), one ring-buffer write, one batched NT publish. |

Enforced by `TuningAllocationTest` (1000 loops with tuning disabled, assert zero allocations on the tunable path) and `LoopTimingTest` (steady-state budget on the CI container).

---

## 6. On-robot system identification

### 6.1 Relationship to WPILib SysId — stated plainly

**We reuse `edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine` for the motion. We replace only the analysis, and we do it on the robot.**

SysId's 2026 workflow is a nine-step, laptop-bound ritual: write two callbacks, build the routine, bind four commands, run quasistatic forward/reverse and dynamic forward/reverse, pull the WPILog with DataLogTool, open the Log Loader, drag the string entry containing `state` into the Data Selector's Test State slot, read the gains, retype them into `Constants.java`, redeploy. AdvantageKit users hit an extra step because AK logs are not directly SysId-loadable. A small team does this once, badly, and never repeats it.

The motion itself is correct and well-tested, and re-implementing it would be exactly the kind of duplication this project refuses to do. So:

| Piece | Who does it |
|---|---|
| Quasistatic voltage ramp | **WPILib `SysIdRoutine.quasistatic(Direction)`** |
| Dynamic voltage step | **WPILib `SysIdRoutine.dynamic(Direction)`** |
| WPILog `sysid` state/data entries | **WPILib `SysIdRoutineLog`**, via the routine's log callback |
| Generating the two callbacks from a mechanism declaration | **PumpkinLib** (`SysIdSweep.routineFor(target)`) |
| Deriving safe ramp rate, step voltage and timeout from soft limits | **PumpkinLib** (§6.2) — WPILib's 1 V/s, 7 V, 10 s defaults are unsafe on a 1.6 m elevator |
| Safety aborts during the sweep | **PumpkinLib** (`TuningSupervisor`, §7) — WPILib states explicitly that the routine only creates voltage commands and limits are your problem |
| Fitting kS/kV/kA/kG | **PumpkinLib** (streaming OLS, §6.3), *in addition to* writing the WPILog |
| Log hygiene (one routine per file, auto-named) | **PumpkinLib** (§6.6) |
| Off-robot analysis in the SysId GUI | **WPILib SysId**, still fully supported as an escape hatch |

Nothing is taken away. A team that wants the official tool gets a clean, correctly-named, single-routine WPILog with no extra effort. A team that does not want a laptop gets the gains on the dashboard 20 seconds after the sweep ends.

### 6.2 `SysIdSweep` — generated routine with a derived envelope

```java
package org.pumpkinlib.tuning.sysid;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Voltage;
import edu.wpi.first.units.measure.Velocity;
import edu.wpi.first.units.VoltageUnit;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import org.pumpkinlib.tuning.TuningTarget;
import org.pumpkinlib.tuning.SafetyEnvelope;

/**
 * Wraps {@link SysIdRoutine} so a team never hand-writes the drive and log callbacks, and so the
 * ramp rate, step voltage and timeout come from the mechanism's measured travel instead of
 * WPILib's fixed 1 V/s, 7 V, 10 s defaults.
 */
public final class SysIdSweep {

  public static SysIdSweep of(TuningTarget target, SafetyEnvelope envelope) { /* ... */ return null; }

  /** The generated routine. Exposed so a team can bind the four commands themselves if they want. */
  public SysIdRoutine routine() { /* ... */ return null; }

  public Command quasistatic(SysIdRoutine.Direction direction) { /* ... */ return null; }
  public Command dynamic(SysIdRoutine.Direction direction) { /* ... */ return null; }

  /** All four tests, in order, with a settle and a return-to-start between each. */
  public Command fullSweep() { /* ... */ return null; }

  /** Regression accumulated during the most recent sweep. */
  public FeedforwardRegression regression() { /* ... */ return null; }

  /** Config actually used, after derivation and clamping. Published to the UI. */
  public SysIdRoutine.Config derivedConfig() { /* ... */ return null; }
}
```

**Derivation of the config** (this is the whole point — the defaults are dangerous on short-travel mechanisms):

Let `L = limits().softMax() - limits().softMin()` be the usable travel (metres or radians), `Vmax = 0.85 * nominalVolts` the voltage ceiling, and `kVprior`, `kAprior` the values implied by `PlantPrior` (from `LinearSystemId.createElevatorSystem` / `createSingleJointedArmSystem` / `createFlywheelSystem`, or from `DCMotor` free speed and stall torque directly).

```
// Predicted steady-state speed at the step voltage:
vStep      = (Vstep - kSprior) / kVprior

// POSITION archetypes (ELEVATOR, ARM, TURRET, STEER):
//   the quasistatic ramp must not consume more than 70% of travel before hitting Vmax.
//   Time to reach Vmax at rate r is Vmax/r; distance covered is roughly integral of v(t):
//       d(r) = (Vmax^2) / (2 * r * kVprior)
//   Solve d(r) = 0.70 * L for r:
rampRate   = clamp( Vmax^2 / (2 * 0.70 * L * kVprior),  0.25,  2.0 )    // V/s

//   the dynamic step must not consume more than 45% of travel before the timeout.
//   Distance in time t at steady speed vStep is roughly vStep * t (ignoring the accel transient):
Vstep      = clamp( 0.45 * L * kVprior / dynamicTimeout + kSprior,  1.5,  Vmax )   // V
dynamicTimeout = 1.5                                                    // s, fixed for position mechanisms
quasiTimeout   = clamp( Vmax / rampRate,  2.0,  10.0 )                  // s

// VELOCITY archetypes (FLYWHEEL, DRIVE_VELOCITY):
//   travel is irrelevant; the constraint is the velocity ceiling.
rampRate   = clamp( Vmax / 6.0,  0.25,  2.0 )
Vstep      = min( Vmax, kSprior + kVprior * 0.80 * vMaxAllowed )
quasiTimeout   = Vmax / rampRate + 1.0
dynamicTimeout = 3.0
```

`SysIdRoutine.Config` is then constructed with the verified signature
`Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout, Consumer<SysIdRoutineLog.State> recordState)`,
where the fourth argument is PumpkinLib's own state consumer that (a) forwards to `SysIdRoutineLog` so the WPILog stays valid, and (b) drives the regression's phase tracking.

The `Mechanism` is constructed with the verified signature
`Mechanism(Consumer<Voltage> drive, Consumer<SysIdRoutineLog> log, Subsystem subsystem, String name)`:

```java
new SysIdRoutine.Mechanism(
    v -> { supervisor.commandVolts(v.in(Volts)); },       // never target.setVoltage() directly
    log -> {
      var motor = log.motor(target.name());
      motor.voltage(Volts.of(appliedVolts()));
      if (target.units() == MechanismUnits.LINEAR_METRES) {
        motor.linearPosition(Meters.of(target.getPosition()))
             .linearVelocity(MetersPerSecond.of(target.getVelocity()));
      } else {
        motor.angularPosition(Radians.of(target.getPosition()))
             .angularVelocity(RadiansPerSecond.of(target.getVelocity()));
      }
      regression.add(target.getPosition(), target.getVelocity(), accel(), appliedVolts());
    },
    target.requirement().orElse(dummySubsystem),
    target.name());
```

Note `appliedVolts()` prefers `TuningTarget.getAppliedVolts()` (the device's measured output) over the commanded value. On a sagging battery those differ by half a volt, and fitting against the commanded value biases kV high — one of the most common silent characterization errors.

### 6.3 The least-squares fit — matrix formulation

The mechanism models, exactly as SysId uses them (`u` = applied volts, `x` = position, `v` = velocity, `a` = acceleration, `theta` = arm angle from horizontal):

| Archetype | Model | Regressor row `phi` | Parameter vector `beta` | n |
|---|---|---|---|---|
| `FLYWHEEL`, `DRIVE_VELOCITY`, `TURRET`, `STEER` | `u = kS*sgn(v) + kV*v + kA*a` | `[sgn(v), v, a]` | `[kS, kV, kA]` | 3 |
| `ELEVATOR` | `u = kG + kS*sgn(v) + kV*v + kA*a` | `[1, sgn(v), v, a]` | `[kG, kS, kV, kA]` | 4 |
| `ARM` | `u = kG*cos(theta) + kS*sgn(v) + kV*v + kA*a` | `[cos(theta), sgn(v), v, a]` | `[kG, kS, kV, kA]` | 4 |

Stacking `N` samples gives an overdetermined system `X beta = y` with `X` an `N x n` matrix and `y` the `N`-vector of applied volts. The ordinary least-squares solution satisfies the normal equations:

```
(X^T X) beta = X^T y
```

`X^T X` is `n x n` (3x3 or 4x4) and `X^T y` is `n x 1`, **regardless of N**. So we never store samples:

```
For each sample i:
    X^T X  +=  phi_i * phi_i^T          (n*n multiply-adds)
    X^T y  +=  phi_i * u_i              (n multiply-adds)
    y^T y  +=  u_i * u_i                (1)
    sum_y  +=  u_i                      (1)
    N      +=  1
```

At most 20 multiply-adds per 20 ms loop. This is the entire reason on-robot identification is feasible.

**Quality metrics, also computed in O(1) memory.** With `beta` known at the end:

```
SSE  =  y^T y  -  2 * beta^T (X^T y)  +  beta^T (X^T X) beta
SST  =  y^T y  -  (sum_y)^2 / N
R2   =  1 - SSE / SST
RMSE =  sqrt(SSE / N)                    // volts
```

That identity is exact, needs only the accumulators we already have, and gives a voltage-prediction R^2 and an RMSE in volts.

> **[UNVERIFIED]** This voltage-prediction R^2 is **not** the same statistic as SysId's reported "simulated velocity r^2" or "acceleration r^2", and the two are not directly comparable. SysId's thresholds (simulated-velocity r^2 > 0.9 good; acceleration r^2 rarely above 0.5) do not transfer. PumpkinLib therefore reports its own metric with its own thresholds and labels it clearly as `voltageFitR2`, and additionally computes a *simulated-velocity* R^2 from the decimated replay buffer (§6.5) so a student who knows SysId sees a familiar number too.

### 6.4 `FeedforwardRegression` — the implementation

```java
package org.pumpkinlib.tuning.sysid;

import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.VecBuilder;
import org.pumpkinlib.tuning.MechanismArchetype;

/**
 * Streaming ordinary-least-squares fit of kS/kV/kA (+ kG) using normal equations.
 * O(1) memory in the number of samples. Safe to call from a 20 ms loop.
 *
 * <p>Deliberately NOT thread-safe and deliberately not backed by a sample list: the whole point
 * is that a roboRIO can do this inline without a heap allocation per loop.
 */
public final class FeedforwardRegression {

  private final MechanismArchetype archetype;
  private final int n;                       // 3 or 4
  private final double[][] xtx;              // n x n
  private final double[] xty;                // n
  private final double[] phi;                // n, reused every sample - no allocation
  private double yty, sumY;
  private int samples;

  // Column-variance accumulators, used to detect a rank-deficient fit (see solve()).
  private final double[] colSum, colSumSq;

  public FeedforwardRegression(MechanismArchetype archetype) {
    this.archetype = archetype;
    this.n = archetype.hasGravity() ? 4 : 3;
    this.xtx = new double[n][n];
    this.xty = new double[n];
    this.phi = new double[n];
    this.colSum = new double[n];
    this.colSumSq = new double[n];
  }

  /**
   * Add one sample.
   *
   * @param position     metres or radians. For ARM, radians from horizontal.
   * @param velocity     m/s or rad/s
   * @param acceleration m/s^2 or rad/s^2. NaN samples are skipped for the kA column only.
   * @param volts        APPLIED volts, preferably as measured by the device
   */
  public void add(double position, double velocity, double acceleration, double volts) {
    if (!Double.isFinite(volts) || !Double.isFinite(velocity)) return;
    double a = Double.isFinite(acceleration) ? acceleration : 0.0;

    int k = 0;
    if (archetype == MechanismArchetype.ELEVATOR)  phi[k++] = 1.0;
    if (archetype == MechanismArchetype.ARM)       phi[k++] = Math.cos(position);
    phi[k++] = Math.signum(velocity);
    phi[k++] = velocity;
    phi[k]   = a;

    for (int i = 0; i < n; i++) {
      double pi = phi[i];
      xty[i] += pi * volts;
      colSum[i] += pi;
      colSumSq[i] += pi * pi;
      for (int j = i; j < n; j++) {          // symmetric: fill upper, mirror on solve
        xtx[i][j] += pi * phi[j];
      }
    }
    yty += volts * volts;
    sumY += volts;
    samples++;
  }

  public int samples() { return samples; }

  /** @throws IdentificationException if the system is rank-deficient or the fit is unusable. */
  public FeedforwardFit solve() { /* see below */ return null; }

  public void reset() { /* zero everything */ }
}
```

`solve()` body, in full:

```java
public FeedforwardFit solve() {
  if (samples < 200) {                              // 4 s at 50 Hz
    throw new IdentificationException(AbortReason.INSUFFICIENT_DATA,
        "Only " + samples + " samples. Need at least 200. Did the mechanism actually move?");
  }
  // Mirror the symmetric upper triangle.
  double[][] A = new double[n][n];
  for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
      A[i][j] = (j >= i) ? xtx[i][j] : xtx[j][i];

  // Rank check: a column with near-zero variance carries no information.
  // The classic failure is a pure ramp with no acceleration content -> kA is unidentifiable.
  for (int i = 0; i < n; i++) {
    double var = colSumSq[i] / samples - Math.pow(colSum[i] / samples, 2);
    if (var < 1e-9 && i != gravityColumnIndex()) {
      throw new IdentificationException(AbortReason.RANK_DEFICIENT,
          "Column " + columnName(i) + " never varied. "
        + (columnName(i).equals("a")
            ? "Run the DYNAMIC step, not just the ramp - kA is only visible while speeding up."
            : "The mechanism did not move enough."));
    }
  }

  double[] beta = (n == 3) ? solve3(A, xty) : solve4(A, xty);

  // Quality, from the accumulators only.
  double bXtY = 0;
  for (int i = 0; i < n; i++) bXtY += beta[i] * xty[i];
  double bXtXb = 0;
  for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
      bXtXb += beta[i] * A[i][j] * beta[j];

  double sse = yty - 2 * bXtY + bXtXb;
  double sst = yty - sumY * sumY / samples;
  double r2  = (sst > 1e-12) ? 1.0 - sse / sst : Double.NaN;
  double rmse = Math.sqrt(Math.max(sse, 0.0) / samples);

  return FeedforwardFit.from(archetype, beta, r2, rmse, samples);
}

private static double[] solve3(double[][] a, double[] b) {
  var A = MatBuilder.fill(Nat.N3(), Nat.N3(),
      a[0][0], a[0][1], a[0][2],
      a[1][0], a[1][1], a[1][2],
      a[2][0], a[2][1], a[2][2]);
  var B = VecBuilder.fill(b[0], b[1], b[2]);
  var x = A.solveFullPivHouseholderQr(B);
  return new double[] { x.get(0, 0), x.get(1, 0), x.get(2, 0) };
}

private static double[] solve4(double[][] a, double[] b) {
  var A = MatBuilder.fill(Nat.N4(), Nat.N4(),
      a[0][0], a[0][1], a[0][2], a[0][3],
      a[1][0], a[1][1], a[1][2], a[1][3],
      a[2][0], a[2][1], a[2][2], a[2][3],
      a[3][0], a[3][1], a[3][2], a[3][3]);
  var B = VecBuilder.fill(b[0], b[1], b[2], b[3]);
  var x = A.solveFullPivHouseholderQr(B);
  return new double[] { x.get(0, 0), x.get(1, 0), x.get(2, 0), x.get(3, 0) };
}
```

> **Verified WPILib signatures used above:**
> `MatBuilder.fill(Nat<R> rows, Nat<C> cols, double... data)` (row-major),
> `VecBuilder.fill(double...)` up to `N10`,
> `Matrix.solveFullPivHouseholderQr(Matrix<R2,C2> other)` returning `Matrix<C,C2>`,
> `Matrix.get(int row, int col)`.
> Note that `Matrix` has **no `plusEqu`** method, which is why accumulation is done in plain `double[][]` rather than in `Matrix` objects — that is also allocation-free, which `Matrix.plus` would not be.

`FeedforwardFit`:

```java
package org.pumpkinlib.tuning.sysid;

public record FeedforwardFit(
    double kS, double kV, double kA, double kG,
    double voltageFitR2,          // see the UNVERIFIED note in 6.3
    double rmseVolts,
    double simulatedVelocityR2,   // NaN until validate() is called with the replay buffer
    int samples,
    Quality quality,
    java.util.List<String> warnings) {

  public enum Quality { GOOD, ACCEPTABLE, SUSPECT, UNUSABLE }
}
```

**Quality thresholds** (PumpkinLib's own, chosen to be conservative; not from a WPILib source, so labelled as ours in the UI):

| `voltageFitR2` | `rmseVolts` | Quality | UI text |
|---|---|---|---|
| >= 0.95 | <= 0.25 | `GOOD` | "Good fit. The model explains 97% of the voltage you applied." |
| >= 0.85 | <= 0.50 | `ACCEPTABLE` | "Usable fit, but noisy. Re-run with a slower ramp if the gains look odd." |
| >= 0.60 | any | `SUSPECT` | "Poor fit. Usually this means backlash, a slipping encoder, or something else fighting the motor." |
| < 0.60 | any | `UNUSABLE` | "This fit is not trustworthy and PumpkinLib will not accept it. Run the mechanical health check." |

### 6.5 Sanity-bounding the fit against physics

Numbers can be statistically excellent and physically absurd. Before a fit is offered to the student, each gain is compared with the prior implied by `PlantPrior`:

```
// Prior from the motor curve. For a mechanism with gear reduction G and either
// mass m (linear, drum radius r) or inertia J (rotational):
//
//   kV_prior = nominalVolts / omega_free_at_mechanism
//   kA_prior = kV_prior * tau_mech / 1.0     where tau_mech = J_eff / b_eff
//
// In practice we get both directly from wpimath rather than deriving them by hand:
LinearSystem<N2,N1,N2> plant = switch (archetype) {
  case ELEVATOR ->
      LinearSystemId.createElevatorSystem(motor, massKg, radiusMetres, gearingReduction);
  case ARM, TURRET, STEER ->
      LinearSystemId.createSingleJointedArmSystem(motor, moiKgM2, gearingReduction);
  case FLYWHEEL, DRIVE_VELOCITY ->
      null;   // use createFlywheelSystem, which is LinearSystem<N1,N1,N1>
};
// For a position system in the form  xdot = A x + B u  with states [pos, vel]:
//    kV_prior = -A(1,1) / B(1,0)
//    kA_prior =  1.0    / B(1,0)
```

Rules (each produces a warning string, never a hard failure, because a real robot legitimately differs from a spherical-cow prior):

| Check | Threshold | Message |
|---|---|---|
| `kV` sign | must be > 0 | **hard failure** — `"kV came out negative. Your encoder or motor direction is inverted. Fix the invert before tuning; do not tune around it."` |
| `kV` magnitude | within `[0.4x, 3.0x]` of prior | `"Measured kV is 2.6x what the motor curve predicts. That usually means the gear ratio in your config is wrong, or something is dragging."` |
| `kA` magnitude | within `[0.25x, 6.0x]` of prior | `"Measured kA is far from the predicted value. kA is the hardest gain to measure - check that the dynamic (step) test actually ran."` |
| `kS` magnitude | `0 <= kS <= 0.25 * nominalVolts` | `"kS of 3.8 V is very high. That is a lot of friction - check for a binding bearing or an overtight belt before you accept this."` |
| `kG` sign (ELEVATOR) | `signum(kG)` must equal `gSign` from the drift probe (§8.4 step 0) | `"kG came out with the opposite sign to the direction this mechanism falls. Check which way your encoder counts."` |
| `kG` sign (ARM) | `signum(kG)` must equal `gSign` from the drift probe (§8.4 step 0) | `"kG came out with the opposite sign to the direction this mechanism falls. Check which way your encoder counts."` |
| `kG` magnitude (ARM) | within `[0.3x, 3.0x]` of `kGprior = m*g*L/(G*Kt)` | warning |
| `kG` magnitude (ELEVATOR) | within `[0.3x, 3.0x]` of `kGprior = m*g*r/(G*Kt)` | warning |

> **On the sign of kG.** The old form of this check hard-coded `kG >= 0`, which silently assumed "positive position is up." It is not: a hood or a wrist whose positive direction points *downward* has a genuinely negative kG, passes the `MechanicalHealthCheck` direction test (positive voltage really does produce positive position change), and would have been handed `kG = 0` for a mechanism that visibly sags. The sign is now **measured**, once, by the zero-voltage drift probe that opens `HoldBisectionStep`, and every downstream check compares against that measurement instead of an assumption. `kGprior` is always reported as a positive magnitude; the sign comes from the probe.

**`kGprior`, stated explicitly**, because §8.4 brackets the bisection from it and §7.6 perturbs it:

```
// Volts required to hold the gravity load at the worst-case pose, from the motor curve.
//   tau_gravity = m * g * L        (ARM: L = centre-of-mass length)
//   tau_gravity = m * g * r        (ELEVATOR: r = drum radius)
//   tau_motor_per_volt = motor.KtNMPerAmp / motor.rOhms      (stall torque per volt, one motor)
kGprior = tau_gravity / (gearingReduction * motor.KtNMPerAmp / motor.rOhms * motorCount)
```

All three factors come from `PlantPrior`; `DCMotor` exposes `KtNMPerAmp` and `rOhms` as public fields and `DCMotor.getKrakenX60Foc(n)` already folds `motorCount` into them, so the expression is a single line in practice.

### 6.6 Log hygiene

WPILib is explicit: *"Only log files with a single routine in them are usable for analysis."* Running sequential routines without extracting or power-cycling causes analysis failure. PumpkinLib owns this so a student cannot get it wrong:

1. `SysIdSweep.fullSweep()` calls `DataLogManager.start()` if not already started, then closes the current log and starts a **new** one named `sysid-<Mechanism>-<yyyyMMdd-HHmmss>.wpilog` before the first test.
2. Between the four tests it inserts a `settle` command (mechanism neutral, 0.75 s) and, for position archetypes, a return-to-start move. No second routine is written to the same file.
3. At the end of the sweep the file is closed and its path is published to `/PumpkinTuner/lastSysIdLog` (string) so a student can find it with FTP/scp without guessing.
4. When AdvantageKit is detected, the UI shows: `"AdvantageKit logs are not directly loadable by SysId. PumpkinLib wrote a separate plain WPILog for you at /U/logs/sysid-Elevator-20260806-141233.wpilog."`
5. If `/U` is not mounted (no USB stick), we log to `/home/lvuser/logs` and raise a warning alert rather than failing. A robot that will not run because logging failed is a lost match.

---

## 7. Safety — `TuningSupervisor`

Every existing FRC live-tuning implementation ships with a documentation warning and no interlocks. YAMS: *"Live Tuning can be DANGEROUS please test in sim before the real robot."* WPILib SysId: *"it is up to you to set up hard or soft limits to prevent injury or damage."* FrcCatalyst ships tuning enabled by default. **PumpkinLib makes safety structural instead of documentary.** This is the single strongest differentiator for a library aimed at teams where no mentor is watching.

### 7.1 `SafetyEnvelope`

```java
package org.pumpkinlib.tuning;

/** Every actuating tuning routine runs inside one of these. Derived from the target; overridable. */
public record SafetyEnvelope(
    double maxVolts,              // absolute voltage ceiling. Default 0.85 * nominal.
    double positionMin,           // hard abort band, tighter than the soft limits
    double positionMax,
    double maxAbsVelocity,        // abort above this
    double maxStatorAmps,         // abort above this, sustained for holdoffSeconds
    double holdoffSeconds,        // current must exceed the limit for this long (default 0.15)
    double maxRoutineSeconds,     // wall-clock timeout for the whole routine
    double stallVoltsThreshold,   // "commanded this many volts"
    double stallVelocityThreshold,// "...but moving slower than this"
    double stallSeconds,          // "...for this long" -> STALLED
    boolean requireHeldEnable) {

  /**
   * Derive from the mechanism. This is what the wizard uses; teams rarely construct one by hand.
   *
   * <ul>
   *   <li>{@code positionMin/Max} are pulled in from the <b>hard</b> limits by
   *       {@code max(softMargin, 0.05 * range)} — derived from TRAVEL, not from the margin — so the
   *       supervisor band is strictly inside the device's soft-limit band no matter what margin the
   *       team configured. The student always sees "PumpkinLib stopped this" instead of a silent
   *       device clamp.</li>
   *   <li>{@code maxAbsVelocity} = 1.15 x the free speed predicted by {@link PlantPrior}.</li>
   *   <li>{@code maxStatorAmps} = 0.85 x the configured stator limit, or 60 A if unknown.</li>
   * </ul>
   */
  public static SafetyEnvelope derive(TuningTarget target) {
    TravelLimits t = target.limits();
    double guard = Math.max(t.softMargin(), 0.05 * t.range());
    double positionMin = t.min() + guard;
    double positionMax = t.max() - guard;
    // ... remaining fields as documented above
    return new SafetyEnvelope(/* maxVolts */ 0.85 * target.plantPrior().nominalVolts(),
        positionMin, positionMax, /* ... */);
  }
}
```

**Why the band is derived from travel and not from the margin.** The previous rule — "soft limits pulled in by a further 25% of the soft margin" — was self-defeating for exactly the teams this library is for. With the default `softMargin = 0.0` that every `new TravelLimits(min, max, 0.0)` call produced, `0.25 * 0` is zero, the supervisor band was *identical* to the device soft limits, and the documented guarantee ("the supervisor always trips before the device's own soft limit does") was false in the default case. The failure was silent: the student saw the device clamp with no message, which is precisely the outcome §7 exists to prevent.

Two independent changes close it. `TravelLimits` (§3.1) now **rejects** a margin below 2% of travel at construction, with a message that names the reason. And `derive` takes `max(softMargin, 0.05 * range)` from the **hard** limits, so even a team that configures the bare minimum 2% margin gets a supervisor band 5% inside the hard stops and therefore 3% inside their own soft limits.

`SafetyEnvelopeTest` asserts the strict inequality, per archetype, over the full built-in archetype set plus a randomized sweep of travel ranges:

```java
@ParameterizedTest
@EnumSource(MechanismArchetype.class)
void supervisorBandIsStrictlyInsideDeviceSoftLimits(MechanismArchetype archetype) {
  assumeTrue(archetype.isPosition());
  for (double range : new double[] {0.05, 0.20, 1.60, 6.28}) {
    var target = SimTargets.of(archetype, new TravelLimits(0.0, range, 0.02 * range));
    var envelope = SafetyEnvelope.derive(target);
    assertTrue(envelope.positionMax() < target.limits().softMax(),
        archetype + " @ range " + range + ": supervisor band must trip before the device does");
    assertTrue(envelope.positionMin() > target.limits().softMin());
  }
}
```

### 7.2 Abort conditions — the complete list

Checked **every loop**, in this order, by `TuningSupervisor.check()`. The first one that trips wins.

| # | Condition | `AbortReason` | Student-facing message |
|---|---|---|---|
| 1 | Enable trigger released (when `requireHeldEnable`) | `ENABLE_RELEASED` | "You let go of the trigger. Nothing is broken - hold it again and press Retry." |
| 2 | `DriverStation.isDisabled()` | `DISABLED` | "Robot disabled. The routine stopped where it was." |
| 3 | Position outside `[positionMin, positionMax]` | `LIMIT_REACHED` | "Stopped: the elevator reached 1.54 m and the safe band ends at 1.55 m." |
| 4 | Predicted position at current velocity + 150 ms outside the band | `LIMIT_APPROACH` | "Stopped early: at this speed you would hit the top in 0.15 s." |
| 5 | `abs(velocity) > maxAbsVelocity` | `OVERSPEED` | "Stopped: 6.2 rad/s is faster than this mechanism should ever go. Check your gear ratio." |
| 6 | Stator current > `maxStatorAmps` for `holdoffSeconds` | `OVERCURRENT` | "Stopped: 72 A for 0.15 s. Something is jammed or the mechanism is at a hard stop." |
| 7 | Commanded > `stallVoltsThreshold` but `abs(velocity) < stallVelocityThreshold` for `stallSeconds` | `STALLED` | "Stopped: 4 V applied for 0.5 s and nothing moved. Check the breaker, the CAN ID, and whether it is at a hard stop." |
| 8 | Velocity sign opposite to commanded voltage sign for > 0.3 s, above a deadband | `WRONG_DIRECTION` | "Stopped: positive voltage is making this move in the negative direction. Fix the invert before tuning." |
| 9 | Elapsed > `maxRoutineSeconds` | `TIMEOUT` | "Stopped: this step took longer than expected. Usually the mechanism is not reaching the speed we asked for." |
| 10 | Any measurement NaN or non-finite | `SENSOR_FAULT` | "Stopped: the encoder returned an invalid value. Check the sensor wiring." |
| 11 | Position unchanged for 0.5 s while velocity reads non-zero (or vice versa) | `SENSOR_INCONSISTENT` | "Stopped: position and velocity disagree. One of them is stale - check `optimizeBusUtilization` and your signal update rates." |

> Condition 11 is a direct response to `C:/Users/ericj/GitHub/8793-2026-Robot/src/main/java/frc/robot/subsystems/ShooterSubsystem.java:180,185,189`, where `optimizeBusUtilization()` is called on three motors with **no preceding `setUpdateFrequency`**. Any `getPosition()` on those devices returns a frozen value forever, with no error. A tuner that fits a model to a frozen signal produces confident garbage; this check catches it in half a second and names it.

Velocity-runaway (condition 5) deserves a note: for a `FLYWHEEL` the free-speed prior is the right ceiling, but for `DRIVE_VELOCITY` on blocks the wheels spin to free speed instantly and the check fires immediately. That is *correct behaviour* — a drivetrain cannot be characterized on blocks — and the message says so: `"Stopped: the wheels reached free speed almost instantly. A drivetrain cannot be characterized on blocks; put it on the floor with at least 3 m of clear space."`

### 7.3 `TuningSupervisor`

```java
package org.pumpkinlib.tuning;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Single choke point for every volt a tuning routine commands. Nothing in
 * org.pumpkinlib.tuning calls {@link TuningTarget#setVoltage} directly.
 */
public final class TuningSupervisor {

  public TuningSupervisor(TuningTarget target, SafetyEnvelope envelope, BooleanSupplier enableHeld) { /* ... */ }

  /**
   * Arm the supervisor. Throws {@link IllegalStateException} - not an alert, a hard throw - if any
   * precondition fails, because a routine that runs without these is the one that breaks a robot:
   * <ol>
   *   <li>soft limits are configured on the device (position archetypes only);</li>
   *   <li>{@code limits().range()} is finite and positive (position archetypes only);</li>
   *   <li>{@link TuningTarget#getStatorCurrentAmps()} is present, OR the caller passed
   *       {@code allowNoCurrentSensing()} explicitly;</li>
   *   <li>the mechanism is currently inside the safe band;</li>
   *   <li>the robot is enabled and not connected to an FMS;</li>
   *   <li>{@code Constants.mode != REPLAY};</li>
   *   <li>{@link edu.wpi.first.wpilibj.DriverStation#isTest()} is true — see section 7.4. In code
   *       preconditions 5 and 7 collapse to the single expression
   *       {@code DriverStation.isTestEnabled() && !DriverStation.isFMSAttached()};</li>
   *   <li>{@link TuningTarget#isHomed()} is true;</li>
   *   <li>for position archetypes, {@link TuningTarget#feedbackSpec()} is an
   *       {@code Absolute}/{@code FusedAbsolute} source, or a {@code HomedAgainstSwitch} whose
   *       homing completed since the last power cycle. {@code RotorOnly} and {@code AssumeAtBoot}
   *       are rejected;</li>
   *   <li>when both an absolute and a rotor-derived position exist, they agree within
   *       {@code 2 * gains().toleranceSi()}.</li>
   * </ol>
   */
  public void arm() { /* ... */ }

  /** Clamp, apply, and record. Returns the volts actually applied. */
  public double commandVolts(double requestedVolts) { /* ... */ return 0; }

  /** Run every loop. Returns the abort reason if one tripped this cycle. */
  public Optional<AbortReason> check() { /* ... */ return Optional.empty(); }

  /** Neutral the mechanism, publish the reason, latch until re-armed. Idempotent, never throws. */
  public void abort(AbortReason reason) { /* ... */ }

  public boolean isArmed() { /* ... */ return false; }
  public Optional<AbortReason> lastAbort() { /* ... */ return Optional.empty(); }
}
```

`commandVolts` performs, in order: clamp to `[-maxVolts, +maxVolts]`; apply a slew limit of `maxVolts / 0.05` V/s so no step can be instantaneous at the hardware; taper toward the **hold voltage** over the last 10% of the safe band on the approach side; then `target.setVoltage(...)`.

> **The taper target is the hold voltage, not zero.** On a `FLYWHEEL` or a `TURRET`, tapering to zero as you approach the band edge is a brake. On an `ARM` or an `ELEVATOR`, tapering to zero is *releasing the mechanism*, which accelerates it into the very limit the taper was trying to avoid. So for `archetype.hasGravity()` the taper floor is `kG * gravityShape(position)` using the best kG known this session (the `PlantPrior` estimate `kGprior` before the bisection has run, the bisected value after), and only the *excess* above that floor is tapered away. `SafetyAbortTest` includes a gravity case asserting that a `LIMIT_APPROACH` on an arm leaves the arm holding, not falling.

#### 7.3.1 The position-reference preconditions, and why they are hard throws

Preconditions 8, 9 and 10 are new and they close the widest hole in the original design. The failure they prevent, concretely:

A `PositionMechanism` declared with `FeedbackSpec.AssumeAtBoot(Degrees.of(95))` that was pushed by hand while the robot sat disabled reports `getPosition() == 95°` while the arm is physically resting on its bottom stop at −10°. Then:

- `limits().insideSoft(95)` passes, so the old `arm()` succeeded.
- Abort conditions 3 and 4 (§7.2) are computed against 95°, a position the arm is nowhere near. Every position interlock is inert.
- `HoldBisectionStep` commands `mid * cos(95°) ≈ −0.087 * mid` — a small *negative* voltage — while the arm sits on its bottom stop. The bisection then reads the resulting non-motion as "needs more volts" and walks the bracket in the wrong direction.
- The ARM pre-flight's zero-convention check ("move the arm to horizontal and tell me what the encoder says") passed forty minutes ago, before somebody moved it.

The supervisor was armed with no valid position reference. Nothing downstream could detect that, because everything downstream *is* the position reference.

The messages are specific, because "precondition failed" teaches nothing:

| Precondition | Message |
|---|---|
| 8, not homed | `"Wrist has not been homed since power-on. The tuner needs to know where this mechanism actually is before it commands any voltage — every safety limit is computed from the position you are reporting. Run your homing routine, then try again."` |
| 9, `AssumeAtBoot` | `"Wrist uses HomingStrategy.assumeAtBoot, which is not a position reference the tuner can trust. Home against a limit switch or add an absolute encoder before tuning."` |
| 9, `RotorOnly` | `"Wrist only has the motor's internal encoder, which reads zero wherever the robot booted. That is fine for a flywheel and unsafe for an arm. Home against a limit switch or add an absolute encoder before tuning."` |
| 10, disagreement | `"Wrist's absolute encoder says 12.4 degrees and the motor encoder says 31.9 degrees. They disagree by more than twice your tolerance, so at least one of them is wrong. Fix that before tuning — a wrong position is a wrong safety limit."` |

**The ARM zero-convention check re-runs at `arm()`, not only at pre-flight.** For `ARM` targets with an absolute source, `arm()` re-reads `getAbsolutePosition()` and re-verifies that the recorded horizontal reference is still consistent with it, using the same 5-degree threshold as the pre-flight step (§8.8 row 1). Pre-flight is a one-time check; arming happens before every single step, and it is the last moment before voltage.

**`isHomed()` defaults.** `TuningTarget.isHomed()` returns `true` by default only for `FLYWHEEL` and `DRIVE_VELOCITY`, which have no meaningful absolute position and no position aborts. Every position archetype must override it. This is a deliberate compile-time-visible burden on the mechanism domain: a mechanism that cannot answer "do I know where I am" is a mechanism the tuner will not move.

### 7.4 Held-enable is a physical trigger, on a dedicated controller, in Test mode only

**Decision: the wizard is driven from a gamepad; the dashboard is the display; motion is impossible outside Test mode; and the wizard never shares a controller with the driver.**

The student running a tuning routine is standing next to the robot with a controller in their hands. A dashboard "Run" button means walking to a laptop, and a latching toggle means nothing stops the mechanism when they let go. So the wizard is gamepad-driven — but the first version of this design shipped two mistakes that together made it the most dangerous component in the library, and both are fixed here.

#### 7.4.1 Motion is impossible outside Test mode — by construction

`TuningSupervisor.arm()` throws unless `DriverStation.isTest()` (precondition 7, §7.3). Not a convention, not a doc note, not "we suggest enabling in Test mode": a hard throw at the only place voltage can be authorized.

The failure this closes: `TuningWizard.periodic()` is called from `robotPeriodic()` (as `DESIGN.md` §10.3 line 889 does), and the original design hard-returned only under FMS. On a practice field with no FMS attached, in teleop, a driver holding right trigger to shoot was simultaneously satisfying the wizard's held-enable. For a library whose stated safety case is "unsupervised 14-year-olds commanding raw voltage to arms," that was the one place it failed. `DriverStation.isTest()` is false during teleop and autonomous, so the wizard now cannot command a volt during a match, a practice match, or a demo, regardless of what any button is doing.

#### 7.4.2 The wizard gets its own controller port

The bindings below collide, button for button, with a typical driver map. `DESIGN.md` §10.3 binds the driver's right trigger to `SuperState.SHOOT`, A/B/Y to L2/L3/L4, X to Stow, Start to Home, and left stick to drive — every single wizard control. Sharing a controller between "drive the robot" and "authorize raw voltage to an arm" is not a binding conflict, it is a safety architecture error.

So the default is a **dedicated port**, and sharing must be stated out loud:

```java
// The default, and every example in this document:
private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));

// Sharing a controller with the driver — legal, logged verbatim, and deliberately awkward:
private final TuningWizard m_tuner =
    TuningWizard.using(m_driver).acknowledgeSharedController(
        "Only one controller at this event; wizard runs in Test mode only");
```

`TuningWizard.using(CommandXboxController)` checks at construction whether the controller's port is also registered with `ControlMap`. If it is, and `acknowledgeSharedController(String)` was not called, the wizard raises a `kError` alert naming the conflict and refuses to leave `IDLE`:

> `"The tuning wizard and the driver controls are both on controller port 0. Right trigger means 'shoot' to your driver and 'authorize motion' to the wizard. Move the wizard to its own port with TuningWizard.using(new CommandXboxController(2)), or call acknowledgeSharedController(\"why\") if you really only have one controller."`

The acknowledgement string is logged verbatim into the tuning report and shown as a persistent warning alert for the rest of the session, matching the `skipSimPromotion` pattern (§7.6) — the same deliberate friction, for the same reason.

#### 7.4.3 The bindings

| Control | Binding | Behaviour |
|---|---|---|
| **Enable** | Right trigger held past 0.5 | Required for any motion. Release = immediate neutral, `ENABLE_RELEASED`. |
| **Accept** | A | Commit the step's result and advance. |
| **Retry** | B | Re-run the current step from scratch. |
| **Back** | X | Return to the previous step; its gain reverts. |
| **Next / Skip** | Y | Skip the step, keeping the existing gain. Logs a warning in the report. |
| **Abort all** | Start | Neutral everything, revert **all** gains to the session's starting values, state = `ABORTED`. |
| **Predict** | D-pad up / left / right | Select one of the three outcomes in a `PredictStep` (§8.4). No motion. |
| **Manual nudge** | Left stick Y | Only in `REVIEW` state; moves the mechanism at up to 15% output so a student can reposition it by hand-ish. |

A secondary control path exists for laptop-only workflows: momentary boolean topics under `/PumpkinTuner/cmd/` that the robot consumes and resets to `false` in the same loop. When a routine is driven this way, `requireHeldEnable` cannot be satisfied, so `maxVolts` is halved and `maxRoutineSeconds` is capped at 3 s. This is stated in the UI: `"No gamepad enable held - running in reduced-power mode."` Test mode is still required; the dashboard path relaxes the *held* enable, never the *mode*.

### 7.5 `MechanicalHealthCheck` — run before you touch a gain

Both ArchdukeTim and SamCarlberg make the same point in the community PID-tuning thread: PIDF control cannot compensate for slop and backlash, and proper tuning requires tensioned belts and shimmed gears *first*. Nothing in the FRC ecosystem checks this. Telling a student "your belt is loose, fix that before touching kP" is worth more than any gain the library could compute.

`MechanicalHealthCheck.command(target)` runs in about 12 seconds and produces a `HealthReport`:

| Test | Method | Failure signature |
|---|---|---|
| **Backlash / deadband width** | Ramp voltage from 0 to +2 V at 0.5 V/s, record position at breakaway; return to rest; ramp to -2 V, record. The position difference is the deadband. | `> 2 degrees` (rotational) or `> 2 mm` (linear) → `"About 4.1 degrees of slop. Tighten the chain or shim the gears - no PID gain can fix backlash."` |
| **Asymmetric friction** | Compare breakaway voltage in both directions. | ratio > 1.6 → `"It takes 0.9 V to move one way and 0.35 V the other. Something is dragging in one direction."` |
| **Encoder slip** | If an absolute encoder is present, compare integrated relative position against absolute after a full sweep. | `> 1%` of travel → `"The motor encoder and the absolute encoder disagree by 12 mm after one sweep. Belt or chain is skipping."` |
| **Sensor liveness** | Command 1.5 V for 0.4 s and require both position and velocity to change. | either frozen → `SENSOR_INCONSISTENT` |
| **Direction** | Positive voltage must produce positive position change. | inverted → **hard stop**, `"Positive voltage moves this mechanism in the negative direction. Fix the invert; do not tune around it."` |
| **Gravity present?** | Neutral the mechanism at mid-travel for 1 s; measure drift. | drift > 5% of travel on a `TURRET`/`FLYWHEEL` → `"This is configured as a turret (no gravity) but it fell 8 cm when released. Is it actually an arm or an elevator?"` |

`HealthReport.verdict()` is `PASS`, `WARN`, or `BLOCK`. The wizard refuses to proceed on `BLOCK`, and shows the warnings inline on `WARN`.

### 7.6 The sim-first promotion gate — it tests the ENVELOPE, not the fit

Before the wizard will arm hardware for a mechanism, the recipe must complete in simulation against a **Monte-Carlo perturbed plant**, and the promotion is granted on a **safety** criterion, not a quality one.

**What was wrong with the obvious version.** The first form of this gate simply required the recipe to complete in simulation, keyed on `configHash = hash(PlantPrior + TravelLimits + archetype + units)`. That gate cannot fail. In simulation the plant *is* `PlantPrior` — the wizard identifies the exact model it was handed, every fit is near-perfect, every step response classifies `GOOD`, and the gate passes unconditionally. It cannot catch a wrong gear ratio, backlash, a slipping belt, an inverted encoder, or a wrong arm zero, which are the actual causes of tuning accidents. It was pure friction with an escape hatch (`skipSimPromotion`) that students would find in week one, and `DESIGN.md` §14 row 26 sold it as the primary safety property. That claim was not true.

**What the gate tests now.** Nine runs. `kV` and `kA` are each independently scaled by `{0.4, 1.0, 3.0}` relative to `PlantPrior`, and every run injects a ±15° arm-zero error (for gravity archetypes; a ±5%-of-travel position-reference error for `ELEVATOR`). Promotion is granted only if, in **all nine runs**, the `TuningSupervisor` kept position inside `[positionMin, positionMax]` and no run reached a hard stop.

That is a direct test of the thing that must not fail on hardware: *when the mechanism is not what you told me it was, does the supervisor still stop it in time?* A wrong gear ratio is exactly a wrong kV. A heavier-than-declared mechanism is exactly a larger kA. The perturbation grid is chosen to bracket the sanity bounds in §6.5 (`kV` within `[0.4x, 3.0x]`, `kA` within `[0.25x, 6.0x]`) so the gate covers the whole range of error the fit checker is willing to warn about rather than reject.

```java
/**
 * The gate. Persisted in gains.json so it survives a restart, but not a mechanism config change.
 * Records the WORST excursion across all nine perturbed runs, which is what gets shown to the student.
 */
public record SimPromotion(
    String mechanismName,
    String configHash,
    String recipeVersion,
    java.time.Instant completedAt,
    Gains resultingGains,
    int runsCompleted,                  // must be 9
    double worstMarginToLimitSi,        // smallest distance from the band edge, over all runs
    double worstMarginToHardStopSi,     // smallest distance from a hard stop, over all runs
    String worstCaseDescription) {}     // "kV x1.0, kA x3.0, arm zero +15 deg"

/** The perturbation grid. Public so a team can see exactly what was tested. */
public static final double[] PLANT_SCALES = { 0.4, 1.0, 3.0 };
```

And it is *shown*, because a gate the student cannot see is a gate the student routes around:

> "In simulation, with your mechanism 3x heavier than you declared, the supervisor stopped the arm 6 degrees before the limit. Promoting."

> "In simulation, with your mechanism 3x heavier than you declared, the arm reached the hard stop. The supervisor's 150 ms look-ahead is not enough margin at the speeds this kA produces. Not promoting — widen your soft limits or reduce the step size before you run this on hardware."

Rules:

- `configHash` is a stable hash of `PlantPrior` + `TravelLimits` + `archetype` + `units`. Change the gearing, and the promotion is void.
- Override: `TuningWizard.skipSimPromotion("I have read the safety notes")` — a literal string argument, logged verbatim into the tuning report and shown as a persistent warning alert for the rest of the session. Deliberately annoying.
- The nine runs execute headless and unattended in about 40 s of sim time; the student is not asked to watch them. The full *teaching* recipe in simulation (§13.11) is a separate, interactive thing.
- `SimPromotionGateTest` (§16.2) injects a plant that the supervisor genuinely cannot contain — a 6x kA with a 20-degree safe band — and asserts the gate **refuses**. A gate with no failing test case is a gate nobody has checked.

**Demotion, stated plainly.** This gate is no longer the headline safety property of the tuning domain, and `DESIGN.md` §14 row 26 must be updated to say so. The two properties that actually carry the safety case are:

1. **The `MechanicalHealthCheck` `BLOCK` verdict** (§7.5) — it refuses to tune an inverted, slipping, or frozen-sensor mechanism, which is the single most destructive class of tuning accident.
2. **The homing / position-reference preconditions on `arm()`** (§7.3.1) — they refuse to command voltage against a position the robot only *assumes*.

The sim gate is the third, and its value is real but bounded: it proves the envelope holds under plant error. That is a genuine property and it should be advertised as exactly that much.

---

## 8. The guided tuning wizard

### 8.1 Architecture

The wizard is a state machine over a **recipe**, which is an ordered list of **steps**. Recipes are data; steps are small strategy objects. Nothing about the wizard is mechanism-specific — the six built-in recipes are just six lists.

```java
package org.pumpkinlib.tuning.wizard;

import org.pumpkinlib.control.Gains;
import org.pumpkinlib.control.Gains.GainId;

/** One teachable move in a recipe. */
public interface TuningStep {

  /** Short title, e.g. "Find kS". Shown as "Step 3 of 9 - Find kS". */
  String title();

  /** Which gain this step produces. Empty for pre-flight and verification steps. */
  java.util.Optional<GainId> produces();

  /** The lesson shown before the student pulls the trigger. See Lessons in section 13. */
  String explanation();

  /** What the student should physically watch for, in one sentence. */
  String watchFor();

  /** What the robot is about to do, in one sentence. Shown while ARMED, before motion. */
  String willDo();

  /** Called once when the step is entered. */
  void begin(StepContext ctx);

  /** Called every loop while RUNNING. */
  void periodic(StepContext ctx);

  /** True when the step has enough data. */
  boolean isComplete(StepContext ctx);

  /** Produce the result. Called once after isComplete() returns true. */
  StepResult finish(StepContext ctx);

  /** Estimated duration for the progress bar. */
  double expectedSeconds(StepContext ctx);
}
```

```java
package org.pumpkinlib.tuning.wizard;

/** Everything a step is allowed to touch. */
public interface StepContext {
  org.pumpkinlib.tuning.TuningTarget target();
  org.pumpkinlib.tuning.TuningSupervisor supervisor();   // the ONLY way to command volts
  org.pumpkinlib.control.Gains gains();                  // gains as accumulated so far this session
  double elapsedSeconds();
  double dt();
  org.pumpkinlib.tuning.sysid.SampleBuffer buffer();     // decimated ring buffer for plots + analysis
  void narrate(String line);                             // appends to /PumpkinTuner/log
  void publishProgress(double fraction0to1);
}
```

```java
package org.pumpkinlib.tuning.wizard;

import org.pumpkinlib.control.Gains;

/** The outcome of a step, presented to the student for accept / retry / skip. */
public record StepResult(
    Outcome outcome,
    java.util.Optional<Gains.GainId> gain,
    double value,                 // the suggested new value
    double previousValue,
    String headline,              // "kS = 0.284 V"
    String quality,               // "Fit R2 = 0.981, RMSE 0.09 V - good"
    String verdict,               // plain-language diagnosis
    String recommendation,        // plain-language next action

    /**
     * Whether the student's prediction for this step was right. Empty when the step had no
     * {@link org.pumpkinlib.tuning.wizard.steps.PredictStep} in front of it, or when the student
     * skipped the question. This is the only field in the whole design that measures the STUDENT
     * rather than the mechanism, and it is what makes the difference between a teaching tool and a
     * progress bar visible to a mentor who was not in the room. See section 8.4.
     */
    java.util.Optional<Boolean> predictionCorrect,

    java.util.List<String> warnings) {

  public enum Outcome { SUCCESS, RETRY_SUGGESTED, FAILED, SKIPPED }
}
```

### 8.2 The wizard state machine

```
                    +-------------------------------------------------+
                    v                                                 |
 IDLE --select--> READY --preflight--> ARMED --trigger held--> RUNNING
   ^                 |                   |                        |
   |                 |                   | (release / abort)      | (complete)
   |                 |                   v                        v
   |                 |               ABORTED <---(any abort)--- REVIEW
   |                 |                   |                    /   |   \
   |                 |                   |            accept /    |    \ retry
   |                 |                   |                  /  skip     \
   |                 +<------------------+                 v     v       v
   |                                                    (next step, or DONE)
   +<------------------- DONE / ABORTED --exit-----------------------------+
```

| State | Meaning | Motion allowed |
|---|---|---|
| `IDLE` | No mechanism selected. | no |
| `READY` | Mechanism selected; recipe loaded; step 1 shown with its explanation. | no |
| `PREFLIGHT` | `MechanicalHealthCheck` running (once per session, before step 1). | yes, low power |
| `ARMED` | Step explained, `willDo()` shown, supervisor armed, waiting for the trigger. | no |
| `RUNNING` | Step executing. | yes |
| `REVIEW` | Step complete; `StepResult` shown with plots. Student presses A/B/Y. | manual nudge only |
| `ABORTED` | Something tripped. Reason shown. Gains reverted to the step's starting values. | no |
| `DONE` | Recipe complete; report generated; gains staged for persistence. | no |
| `DISABLED_REPLAY` | Constants mode is REPLAY. | no |

Invariants enforced in code:
- Motion is possible in exactly two states, and both require `supervisor.isArmed()`.
- Every transition out of `RUNNING` calls `supervisor.abort(...)` or `target.stop()` before anything else.
- Entering `ABORTED` restores `gains` to the snapshot taken on entry to `ARMED`, and calls `applyGains`.
- `Start` (abort all) restores the snapshot taken when the *session* began, not the step.

### 8.3 `TuningWizard` — the public surface

```java
package org.pumpkinlib.tuning.wizard;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;

public final class TuningWizard {

  /**
   * Build the wizard over every registered {@link org.pumpkinlib.tuning.TuningTarget}.
   * The controller supplies enable/accept/retry/back/skip/abort/predict per section 7.4.
   *
   * <p><b>Give the wizard its own port.</b> At construction this checks
   * {@code ControlMap.isPortRegistered(controller.getHID().getPort())}; if the port is also a
   * driver or operator port and {@link #acknowledgeSharedController(String)} has not been called,
   * the wizard raises a {@code kError} alert naming the conflict and refuses to leave
   * {@link WizardState#IDLE}. See section 7.4.2 for the reasoning and the exact message.
   */
  public static TuningWizard using(CommandXboxController controller) { /* ... */ return null; }

  /** For state-based robots (no CommandScheduler): supply the inputs directly. */
  public static TuningWizard using(WizardInputs inputs) { /* ... */ return null; }

  /**
   * Permit the wizard to share a controller with the driver. The argument is a free-text reason,
   * logged verbatim into the tuning report and shown as a persistent warning alert for the rest of
   * the session. Deliberately awkward, exactly like {@link #skipSimPromotion(String)}.
   *
   * <p>Sharing is only survivable at all because motion is impossible outside Test mode
   * (§7.3 precondition 7). It is still a bad idea.
   */
  public TuningWizard acknowledgeSharedController(String reason) { /* ... */ return this; }

  /** Override the recipe for one mechanism. Rarely needed. */
  public TuningWizard recipe(String mechanismName, TuningRecipe recipe) { /* ... */ return this; }

  /**
   * Force one recipe mode for every mechanism, instead of the per-mechanism default in section
   * 8.12 ({@link TuningRecipe.Mode#TEACHING} in simulation, student's remembered choice on
   * hardware, defaulting to a prompt the first time).
   */
  public TuningWizard mode(TuningRecipe.Mode mode) { /* ... */ return this; }

  /** Bypass the sim-first gate. The argument is logged verbatim into the report. */
  public TuningWizard skipSimPromotion(String acknowledgement) { /* ... */ return this; }

  /**
   * Call every loop from {@code robotPeriodic()} (or {@code testPeriodic()}). Costs one branch
   * when the wizard is {@code IDLE}.
   *
   * <p><b>This is safe to call from {@code robotPeriodic()} because of
   * {@link org.pumpkinlib.tuning.TuningSupervisor#arm()} precondition 7, not because of anything
   * this method does.</b> {@code arm()} throws unless {@code DriverStation.isTest()}, so during
   * teleop and autonomous the wizard can advance its own state machine and publish narration but
   * <i>cannot command a volt</i> — no button on any controller, held or not, can cause motion
   * outside Test mode. That is the property that makes the driver-controller collision described
   * in section 7.4.1 a UI annoyance instead of a safety failure.
   *
   * <p>It additionally hard-returns when {@code DriverStation.isFMSAttached()}, and publishes
   * {@code state = "DISABLED_REPLAY"} and returns when {@code Constants.mode == REPLAY}.
   */
  public void periodic() { /* ... */ }

  /** Command-based convenience: run the wizard for the whole time this command is scheduled. */
  public Command command() { /* ... */ return null; }

  public WizardState state() { /* ... */ return WizardState.IDLE; }

  /** Markdown report of everything done this session. See section 11.4. */
  public String report() { /* ... */ return ""; }
}
```

### 8.4 Step primitives

Seven reusable step implementations cover every recipe.

#### `PredictStep` — the step that makes this teaching instead of narration

Without this step the wizard is a progress bar with good prose. A student can complete every recipe by pressing A eleven times and learn nothing, and **neither the wizard nor the mentor can tell the difference.** §13.11 tells the student to "tune them until you can predict what the plot is going to do before it does it" — but nothing in `TuningStep`, `StepContext`, `StepResult` or the NT schema ever asked for a prediction or scored one. That was the largest gap in the design as a teaching artifact.

`PredictStep` is interleaved before the `RUNNING` phase of the kS, kG, kP and verify steps. It commands no motion. It asks one multiple-choice question in plain language, records the answer, and — after the real step runs — tells the student whether they were right and *why*.

```java
package org.pumpkinlib.tuning.wizard.steps;

/**
 * A no-motion step that asks the student to predict what the NEXT step will do, then scores it.
 *
 * <p>Three options, always. Two options is a coin flip; four is a reading-comprehension test.
 * The options are written as observable outcomes ("the carriage will overshoot and bounce"),
 * never as gain names ("kP is too high"), because the skill being taught is reading a mechanism.
 */
public final class PredictStep implements TuningStep {

  public record Question(String prompt, List<String> options, int correctIndex, String whyCorrect,
                         Map<Integer, String> whyWrong) {}

  /** Built by the recipe from the step it precedes; the correct answer comes from the plant model. */
  public static PredictStep before(TuningStep next, Question q) { /* ... */ return null; }

  @Override public Optional<GainId> produces() { return Optional.empty(); }
  @Override public double expectedSeconds(StepContext ctx) { return 0; }
}
```

**Wire format.** Three topics, bound to an Elastic ComboBox — no new widget types:

```
/PumpkinTuner/predict/question   string     "Your elevator's kP is about to triple. What happens?"
/PumpkinTuner/predict/options    string[]   three plain-language outcomes
/PumpkinTuner/predict/answer     double     RW - index 0/1/2, written by the ComboBox or the D-pad
/PumpkinTuner/predict/score      string     "Predictions: 7 of 9"
```

**A real question, from the elevator recipe's kP refinement step:**

> "I'm about to triple your kP, from 18.4 to 55. What do you think the carriage will do?"
>
> 1. It will overshoot and bounce a few times before settling.
> 2. It will get there slowly and stop a little short.
> 3. It will get there and stop cleanly, just faster.

The correct answer is computed from the model — this is not a hand-authored answer key. `zeta = (kD + kV) / (2 * sqrt(kP * kA))` at the proposed gains; below 0.7 the answer is (1), above 1.2 it is (2), between them it is (3). The step is *never* wrong about its own arithmetic, and the arithmetic is the same arithmetic §9.2.2 now shows the student.

**`StepResult` carries the score.** The field is `java.util.Optional<Boolean> predictionCorrect`, declared in the canonical record in §8.1 — there is one definition of `StepResult` in this document and that is it. It is empty when the step had no `PredictStep` in front of it and when the student let the question time out.

**The `Coach` text branches on right-vs-wrong**, which is the whole point — a correct prediction is the moment to name the concept, and an incorrect one is the moment to connect the lesson to what they just watched:

> *(predicted overshoot, and it overshot)* "You said it would overshoot, and it did — 18%, ringing three times. That bouncing is exactly the too-stiff spring from the P lesson. You are reading this mechanism correctly."

> *(predicted clean, and it overshot)* "You said it would stop cleanly; it overshot by 18% and rang three times. Here is the tell: we tripled kP but left kD alone, so the spring got stiffer and the shock absorber did not. Watch the volts plot — see it slam positive, then negative, then positive again?"

**The markdown report** (§11.5c) gains a `Predictions: 7/9` line and lists the missed ones with their questions. That single line is what makes a mentor able to tell, without watching, whether a student ran the wizard or *learned* from it.

**Cost: about 0.3 person-weeks.** It is one step primitive, four questions per recipe, one `Optional<Boolean>`, three NT topics and a report line. It is the difference between teaching and a progress bar, and it is the cheapest high-value item in this document.

#### `PreflightStep`
Runs `MechanicalHealthCheck` (§7.5). Produces no gain. On `BLOCK`, the recipe cannot proceed.

#### `BreakawayRampStep` — finds kS
Ramps voltage from zero at a slow, fixed rate until motion is detected, in **both** directions, and averages.

```
rate      = 0.30 V/s                             (slow enough that inertia contributes nothing)
vMoveThreshold = max(0.02 * vFreePrior, 3 * velocityNoiseStdDev)
                                                  // noise std dev measured over 0.5 s at rest

for direction in {+1, -1}:
    u = 0
    while |velocity| < vMoveThreshold:
        u += direction * rate * dt
        supervisor.commandVolts(u + gravityCompensation())   // see note
    kS[direction] = |u| - |gravityCompensation()|
    stop(); settle(0.5 s); return to start position

kS = (kS[+1] + kS[-1]) / 2
asymmetry = max(kS[+]) / min(kS[-])
```

`gravityCompensation()` is `kG` for `ELEVATOR`, `kG * cos(theta)` for `ARM`, and zero otherwise — which is why gravity mechanisms run a kG pre-pass first (§8.5). Without it, you are measuring "volts to lift the elevator", not "volts to overcome friction", and kS comes out ten times too large.

Reported: `kS = 0.284 V (0.291 up / 0.277 down, 5% asymmetry - fine)`. Asymmetry above 1.6 produces a warning and points at the health check.

#### `HoldBisectionStep` — finds kG to three decimal places, without slamming a hard stop

This is the step that most clearly demonstrates why on-robot beats redeploy-and-guess. WPILib's own vertical-arm tutorial says you must zero in on kG *"fairly precisely, at least four decimal places"*. Twelve deploys will not get you there; five seconds of bisection will.

It is also, by a wide margin, the most dangerous step in the library, because it is **open-loop voltage on a gravity mechanism**. The first version of this step would have destroyed a real arm on iteration 1, and it is worth writing down exactly how, because the fix is shaped by the failure.

> **The failure, walked through on this document's own arm** (kV = 0.85 V/(rad/s), 120° travel, true kG ≈ 1.2 V). Iteration 1 sets `mid = 0.5 * (0 + 0.6 * 12) = 3.6 V` and commands `3.6 * cos(theta)` open-loop. That is 2.4 V of net excess over what gravity needs. Steady velocity ≈ 2.4 / 0.85 ≈ 2.8 rad/s ≈ 160 deg/s. The step then runs `settle(0.15 s)` followed by `mean(velocity) over the next 0.35 s` — half a second at 160 deg/s, so the arm travels **70–80 degrees inside a single measurement window**, on a mechanism with 120 degrees of travel. The position guard (`|position - startPosition| > 0.08 * travelRange`, i.e. 9.6°) was evaluated only *after* that window had already elapsed, so it could not intervene. `LIMIT_APPROACH`'s 150 ms horizon buys 24° of lookahead at that speed. And `commandVolts`'s "taper linearly to zero over the last 10% of the safe band" means, on a gravity arm, *releasing it*. `recentre()` was referenced and never defined, and there was no closed loop available to execute it.

Three changes, all required, all present below.

**(1) Bracket from physics, not from the supply voltage.** `0.6 * nominalVolts` is a bracket over "any voltage this motor can produce." The bracket we actually want is "any voltage plausibly needed to hold *this* load," which `PlantPrior` already tells us (§6.5, `kGprior`):

```
kGprior = m*g*L / (G * Kt/R)          // ARM; m*g*r for ELEVATOR. Always a positive magnitude.
lo = gSign * 0.2 * kGprior
hi = gSign * Math.min(0.6 * nominalVolts, 1.8 * kGprior)
```

On the same arm, `kGprior ≈ 1.2 V`, so iteration 1 commands `mid ≈ 1.2 V` instead of 3.6 V — net excess near zero rather than 2.4 V. The `min` against `0.6 * nominalVolts` keeps the old ceiling as a backstop for a wildly wrong prior. A prior that is wrong by more than 1.8x is caught by the bracket-exhaustion branch below and reported, not silently ignored.

**(2) The probe is a bounded pulse with an IN-window guard, and an abort is itself a measurement.** The guard moved inside the loop, the threshold tightened from 8% to 3% of travel, and — the important part — hitting the guard does not waste the iteration. *Which way it moved is the answer the iteration was asking for.*

**(3) `recentre()` is a real closed-loop move.** The wizard holds a provisional feedback controller for the whole bisection, built from the same `PlantPrior` that produced `kGprior`.

```java
final class HoldBisectionStep implements TuningStep {

  /**
   * The result of one bounded probe. An aborted probe is NOT a wasted iteration: the direction the
   * mechanism moved before the guard tripped is exactly the comparison the bisection was about to
   * make, so the bracket updates either way. This is why tightening the guard from 8% to 3% of
   * travel costs nothing in convergence.
   */
  sealed interface ProbeOutcome {
    record Measured(double drift) implements ProbeOutcome {}
    record AbortedUp() implements ProbeOutcome {}
    record AbortedDown() implements ProbeOutcome {}

    static ProbeOutcome measured(double meanVelocity) { return new Measured(meanVelocity); }
    static ProbeOutcome abortedUp() { return new AbortedUp(); }
    static ProbeOutcome abortedDown() { return new AbortedDown(); }
  }

  /**
   * The best kG known right now. Seeded from {@code gSign * kGprior} BEFORE the first probe — not
   * zero — because {@link #recentre} and the supervisor's gravity-aware taper (§7.3) both use it to
   * HOLD the mechanism, and a zero here is a release. Updated to the bracket midpoint after every
   * iteration.
   */
  private double kGbest;

  // ---- step 0: which way does this thing fall? -----------------------------------------
  //
  // Determines the SIGN of kG by measurement instead of assuming "positive position = up."
  // A hood or wrist whose positive direction is downward has a genuinely negative kG; it passes
  // the MechanicalHealthCheck direction test and the old [0, 0.6*V] bracket could not represent it.
  private double measureGravitySign(StepContext ctx) {
    ctx.narrate("Letting go for half a second to see which way this falls.");
    ctx.target().stop();
    double meanVel = meanVelocityOver(ctx, 0.5);            // zero volts, supervisor live
    if (Math.abs(meanVel) < driftDeadband(ctx)) {
      ctx.narrate("This mechanism does not move when released, so there is nothing for kG to hold. "
                + "Setting kG = 0.");
      return 0.0;                                           // step reports SKIPPED with that text
    }
    return Math.signum(-meanVel);   // falls negative -> kG must push positive -> gSign = +1
  }

  // ---- the bisection --------------------------------------------------------------------
  private double bisect(StepContext ctx, double gSign) {
    double kGprior = ctx.target().plantPrior().gravityVoltsPrior();     // section 6.5
    double lo = gSign * 0.2 * kGprior;
    double hi = gSign * Math.min(0.6 * nominalVolts(ctx), 1.8 * kGprior);
    double startPos = ctx.target().getPosition();
    double range = ctx.target().limits().range();
    kGbest = gSign * kGprior;        // hold at the prior from the very first loop, never at zero

    for (int i = 0; i < ITERATIONS; i++) {                  // ITERATIONS = 10
      double mid = 0.5 * (lo + hi);
      ProbeOutcome out = probe(ctx, mid, startPos, range);

      // Java 17: instanceof patterns only. No pattern-matching switch (preview in 17), no
      // preview features anywhere in PumpkinLib - see section 17.
      //
      // Guard tripped: the DIRECTION of the abort IS the measurement.
      // "Moved up" means we over-pushed against gravity, so mid is too many volts.
      if (out instanceof ProbeOutcome.AbortedUp) {
        if (gSign > 0) hi = mid; else lo = mid;
      } else if (out instanceof ProbeOutcome.AbortedDown) {
        if (gSign > 0) lo = mid; else hi = mid;
      } else if (out instanceof ProbeOutcome.Measured m) {
        if (Math.abs(m.drift()) < driftDeadband(ctx)) { kGbest = mid; return mid; }  // converged
        if (gSign * m.drift() < 0) lo = mid; else hi = mid;
      }
      kGbest = 0.5 * (lo + hi);                             // hold at the new best, not the old one
      recentre(ctx, startPos);                              // closed-loop, always, every iteration
    }
    return kGbest = 0.5 * (lo + hi);
  }

  /**
   * One bounded probe. Commands mid * gravityShape() and watches position CONTINUOUSLY.
   * Returns as soon as the mechanism has moved 3% of travel, which is well inside the
   * supervisor's own band and far inside any hard stop.
   */
  private ProbeOutcome probe(StepContext ctx, double mid, double startPos, double range) {
    double t = 0, sumVel = 0; int n = 0;
    while (t < PROBE_SECONDS) {                             // PROBE_SECONDS = 0.35
      double pos = ctx.target().getPosition();
      double delta = pos - startPos;
      if (Math.abs(delta) > 0.03 * range) {
        abortProbe(ctx);                                    // hold at kG_best, do not release
        return delta > 0 ? ProbeOutcome.abortedUp() : ProbeOutcome.abortedDown();
      }
      ctx.supervisor().commandVolts(mid * gravityShape(ctx));   // 1.0 for ELEVATOR, cos(theta) for ARM
      if (t > SETTLE_SECONDS) { sumVel += ctx.target().getVelocity(); n++; }   // SETTLE = 0.10
      t += ctx.dt();
      yieldOneLoop();
    }
    return ProbeOutcome.measured(sumVel / Math.max(n, 1));
  }

  /**
   * Walk the mechanism back to where the bisection started, under CLOSED LOOP.
   *
   * <p>This is why the wizard holds a provisional feedback controller for the whole bisection:
   * open-loop recentring on a gravity mechanism is the same hazard the bisection itself is, and
   * "taper to zero" is a release. kP comes from the same physics prior that produced kGprior.
   */
  private void recentre(StepContext ctx, double startPos) {
    var prior = ctx.target().plantPrior();
    var pref  = FeedbackDesigner.Preferences.defaultsFor(
        ctx.target().archetype(), ctx.target().limits(), ctx.gains().toleranceSi());
    double kP = FeedbackDesigner.forPosition(prior.kVprior(), prior.kAprior(), pref).kP();

    var profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(
        0.25 * prior.freeSpeedSi(), 0.25 * prior.maxAccelSi()));    // deliberately gentle
    var goal = new TrapezoidProfile.State(startPos, 0);
    var state = new TrapezoidProfile.State(ctx.target().getPosition(), ctx.target().getVelocity());

    while (Math.abs(ctx.target().getPosition() - startPos) > ctx.gains().toleranceSi()) {
      state = profile.calculate(ctx.dt(), state, goal);
      double ff = kGbest * gravityShape(ctx);                        // hold, always
      double fb = kP * (state.position - ctx.target().getPosition());
      ctx.supervisor().commandVolts(ff + fb);
      yieldOneLoop();
    }
  }

  private static final int    ITERATIONS      = 10;
  private static final double PROBE_SECONDS   = 0.35;
  private static final double SETTLE_SECONDS  = 0.10;
}
```

- `driftDeadband = max(1e-3 m/s or 5e-3 rad/s, 3 * velocityNoiseStdDev)`.
- **Resolution: 10 iterations.** The bracket width is `1.6 * kGprior` (from `0.2x` to `1.8x`), so on a 1.2 V prior that is 1.92 V, and `1.92 / 2^10 = 1.9 mV` — three decimal places, which is what WPILib actually asks for when it says "at least four decimal places" about a number of order 1 V. The old 18 iterations bought five decimal places of a quantity whose *measurement* noise floor is two orders of magnitude larger; it was resolution theatre paid for in hard-stop risk and wall-clock time.
- **Total time: 10 iterations x (0.35 s probe + up to ~0.15 s recentre) ≈ 5 s.**
- The mechanism never leaves a **3%** band around its starting position, the guard is evaluated every loop *during* the probe, and the supervisor is live throughout with the gravity-aware taper from §7.3.
- If the bracket is exhausted — ten iterations and `|drift|` still above the deadband at both ends — the step reports `RETRY_SUGGESTED` with `"I could not find a holding voltage between 0.24 V and 2.16 V, which is the range your mechanism's mass and gearing predict. Either the mass or the gear ratio in your PlantPrior is wrong, or something is binding."` The bracket never silently widens itself.

**The three-angle check (ARM only).** After the 10-iteration bisection at angle `theta_1`, repeat a short **6-iteration** bisection at two more reachable angles `theta_2`, `theta_3` — 6 is enough, because these two probes are only ever used to fit a *phase*, and a phase error of 5 degrees is three orders of magnitude coarser than the voltage resolution. The three bisections plus the two closed-loop moves between angles total about 11 s. For a correct zero convention, the holding voltages must satisfy `V_hold(theta) = kG * cos(theta)`. Fit `kG` and a phase offset `phi` to `V_hold(theta_i) = kG * cos(theta_i + phi)` by two-parameter least squares over the three points. If `|phi| > 5 degrees`:

> "Your arm's zero is off by about 11 degrees. kG is biggest when the arm points straight out sideways, and your measurements say the sideways point is 11 degrees away from where your code thinks zero is. Fix the encoder offset — otherwise your arm will droop on one side and creep up on the other, no matter what kP you use."

Nothing in the FRC ecosystem does this, and a wrong arm zero is one of the most common and most confusing mechanism faults.

#### `SysIdSweepStep` — finds kV and kA
Wraps `SysIdSweep` (§6.2). Runs quasistatic forward, quasistatic reverse, dynamic forward, dynamic reverse, with settle and return-to-start between each. Accumulates into `FeedforwardRegression` throughout. The step is presented to the student as **two** logical steps so the teaching is separable:

- **"Find kV"** presents the quasistatic pair, narrates the speed-price lesson, and after the pair shows a provisional kV from a 2-column fit `[sgn(v), v]` over the ramp data only.
- **"Find kA"** presents the dynamic pair, narrates the get-moving-price lesson, and afterwards solves the **full** model over *all four* tests, replacing the provisional kV. The UI says so explicitly: `"kV refined from 3.11 to 3.07 now that we have the step data too."`

This is a small pedagogical trick with real value: the student sees each gain appear from a motion they watched, but the arithmetic is the statistically correct joint fit.

#### `LqrSuggestStep` — proposes kP and kD
No motion. Computes gains from the measured kV/kA and two physical sliders (§9.2), shows them, and lets the student adjust the sliders and watch the numbers move before committing.

#### `StepResponseStep` — verifies, and optionally refines
Commands a bounded closed-loop step (or a profiled move), records the response into the ring buffer, runs `StepResponseAnalyzer` (§10), and shows the classification plus a recommendation. In `REFINE` mode it applies the deterministic gain update (§9.3) and repeats, up to a bounded iteration count.

---

### 8.5 Order of operations

The canonical order taught by the wizard, and the order in which gains are finalized:

```
kS  ->  kV  ->  kA  ->  kG  ->  kP  ->  kD          (feedforward before feedback, always)
```

**Gravity mechanisms need one adjustment, and here is why.** You cannot measure kS on an elevator until gravity is cancelled: ramp the voltage from zero and the "motion" you detect is the carriage falling, not friction breaking loose. So the wizard runs a **kG pre-pass** (`HoldBisectionStep`) before the kS step, uses that provisional kG to cancel gravity during the kS/kV/kA sweeps, and then **re-solves kG jointly with kS/kV/kA in the OLS** at the kG step. The student still learns the gains in canonical order; the pre-pass is presented as part of the pre-flight ("first we work out how hard gravity is pulling, so the rest of the measurements aren't fighting it").

The UI shows the pre-pass value and the final value side by side, and disagreement is itself a diagnostic:

> "Gravity pre-pass said kG = 2.281 V; the full fit says kG = 2.276 V. Those agree, which is a good sign that your model is right."

> "Gravity pre-pass said kG = 2.28 V but the full fit says 1.61 V. They should agree. This usually means the mechanism is not purely constant-gravity — a cascading elevator with a constant-force spring, for example — or that something is binding at one end of travel."

Integral gain is never produced by any recipe. It appears only in the `REVIEW` state as an option, gated behind the explanation in §13.6 and `Gains.withIntegral(kI, iZone, iMaxVolts)`.

---

### 8.6 Recipe: FLYWHEEL

**Applies to:** shooter wheels, high-inertia rollers. Velocity control, no gravity.
**Total time:** about 75 seconds. **Space needed:** none.
**WPILib reference answer for the docs' simulated flywheel:** kV = 0.0075, kP = 0.1, kI = 0, kD = 0.

| # | Step | What the student sees | What the robot does | Math |
|---|---|---|---|---|
| 1 | **Pre-flight** | "Before we tune, let's check the machine. Is the wheel free to spin? Is idle mode set to coast?" | Health check (§7.5), reduced set: sensor liveness, direction, no-gravity confirmation. Reads back the idle mode. | — |
| 2 | **Find kS** | "Every mechanism has friction... watch for the exact moment the wheel starts to turn." | `BreakawayRampStep` at 0.30 V/s, both directions if reversible, else forward only. About 8 s. | `kS = mean(u at breakaway)` |
| 3 | **Find kV** | "A spinning motor pushes back... kV is the price of speed." Live plot of velocity vs applied volts. | Quasistatic ramp forward + reverse, `rampRate = Vmax/6`. About 24 s. | 2-column OLS `[sgn(v), v]` over ramp samples |
| 4 | **Find kA** | "Things with mass don't change speed instantly." Live plot showing the spin-up curve. | Dynamic step forward + reverse at `Vstep`, 3 s timeout each. About 14 s. | Full 3-column OLS over **all** samples: `phi = [sgn(v), v, a]` |
| 5 | *(kG skipped)* | "Flywheels don't fight gravity, so there's no kG. Skipping." | nothing | — |
| 6 | **Suggest kP** | Two sliders: "How much speed error can you live with?" (default 2% of target) and "How many volts may I spend correcting it?" (default 3 V). kP updates live as they drag. | nothing | LQR on `LinearSystemId.identifyVelocitySystem(kV, kA)`; see §9.2 |
| 7 | **Check kP** | Two stacked plots: measured-vs-setpoint on top, commanded volts below — deliberately the same layout as WPILib's browser tutorials. | Closed-loop step from 40% to 70% of max safe velocity, hold 2 s, return. Repeat up to 4 times in refine mode. | `StepResponseAnalyzer`, velocity mode |
| 8 | **kD (usually zero)** | "For a flywheel holding a steady speed, D usually has nothing to do. We'll leave it at zero unless step 7 says otherwise." | nothing, unless the analyzer classified `OVERSHOOT_RING` | §9.3 update rule |
| 9 | **Recovery test** | "This is the one that matters in a match: how fast does it get back to speed after you shoot?" Reports recovery time. | Run to setpoint; command a 1.5 V negative pulse for 120 ms to simulate a game piece; measure time to return within tolerance. | `recoveryTime = t(|error| < tol, sustained 0.2 s) - t(pulse end)` |

**Bang-bang branch.** If the device reports coast idle mode and the archetype is `FLYWHEEL`, step 8 offers an alternative:

> "Your wheel is in coast mode, which means you can use a bang-bang controller instead. Bang-bang has no gains at all: it's full voltage when you're below the target and zero when you're above. On a heavy wheel under a varying load it often recovers faster than a P controller. PumpkinLib will set it up as `BangBangController` output x 12 V plus 0.9 x your feedforward — the 0.9 is deliberate, so the feedforward slightly undershoots and bang-bang only ever has to push, never brake."

The library **refuses** to enable bang-bang unless idle mode is coast, and says why: braking fights the controller and causes destructive oscillation.

---

### 8.7 Recipe: ELEVATOR

**Applies to:** elevators, telescopes, linear slides. Position control, constant gravity.
**Total time:** about 106 seconds of motion (the gravity pre-pass is 5 s, not 9). **Space needed:** full travel, clear.
**WPILib reference answer for the docs' simulated elevator:** kG = 2.28, kV = 3.07, kA = 0.41, kP = 2.0.

| # | Step | What the student sees | What the robot does | Math |
|---|---|---|---|---|
| 1 | **Pre-flight** | "Soft limits set? Homed? Anything in the way over the full travel?" | Health check, full set. Verifies device soft limits exist, verifies travel range, direction, backlash, encoder slip. **BLOCKS** on missing soft limits or inverted direction. | — |
| 2 | **Gravity pre-pass** | "First we find out how hard gravity is pulling, so nothing else we measure is fighting it. Watch: the carriage should hang almost perfectly still." | `HoldBisectionStep` at mid-travel: a 0.5 s release to find which way it falls, then 10 bracketed probes, about 5 s. Never leaves a 3% band. | Physics-bracketed bisection on drift sign, §8.4 |
| 3 | **Find kS** | "Now, on top of holding it still, how much extra push does it take to break friction loose?" | `BreakawayRampStep` with `kG` applied as an offset. Both directions. About 10 s. | `kS = mean(extra volts at breakaway)` |
| 4 | **Find kV** | "Watch the carriage move at a steady speed. The faster it goes, the more volts it takes." | Quasistatic ramp up and down, `rampRate` derived so the ramp uses at most 70% of travel (§6.2). About 26 s including returns. | Provisional 3-column OLS over ramp data |
| 5 | **Find kA** | "This one is quick and it looks violent. Watch the first quarter-second — that's the only part that tells us about mass." | Dynamic step up and down, `Vstep` derived so the step uses at most 45% of travel in 1.5 s. About 12 s. | — |
| 6 | **Confirm kG** | "Now we solve for all four numbers at once, using everything we just recorded." Shows pre-pass kG vs fitted kG. | nothing | Full 4-column OLS: `phi = [1, sgn(v), v, a]`, `beta = [kG, kS, kV, kA]` |
| 7 | **Build the profile** | "You will never guess a max speed again. These come straight from kV and kA." Shows the derived limits in m/s and m/s^2. | nothing | `ExponentialProfile.Constraints.fromCharacteristics(0.85 * 12.0, kV, kA)`. Cross-checked against `ElevatorFeedforward.maxAchievableVelocity(11.0, 0)` and `maxAchievableAcceleration(11.0, 0.8 * vMax)`. |
| 8 | **Suggest kP** | Sliders: "How close is close enough?" (default = `tolerance`, 1 cm) and "How many volts may I spend?" (default 4 V). | nothing | LQR on `identifyPositionSystem(kV, kA)`, §9.2 |
| 9 | **Suggest kD** | Same panel; kD comes out of the same LQR solve. Narration explains the damper. | nothing | `K(0,1)` from the same `getK()` |
| 10 | **Check and refine** | Two stacked plots. Classification and a plain-language verdict after every attempt. | Profiled move over 25% of travel (`softMin + 0.35*range` to `softMin + 0.60*range`), hold 1.5 s, return. Up to 6 refine iterations. | §9.3 + §10 |
| 11 | **Full-travel verify** | "Last check: the whole range, twice, the way a match would use it." Reports rise time, overshoot, settle time, steady-state error at both ends. | Profiled move `softMin -> softMax -> softMin`. | §10 metrics, reported not acted on |

**Where the predictions go.** Four `PredictStep`s are interleaved into this recipe, before the `RUNNING` phase of steps **2 (kG)**, **3 (kS)**, **8 (kP)** and **10 (refine)**. They do not renumber the recipe — a `PredictStep` is a sub-phase of the step it precedes, not a twelfth step — and they add no motion and about 15 s of reading. The refine step asks its question again on each iteration where the gains change by more than 20%, so a student who guesses wrong at iteration 1 gets a second, better-informed go at iteration 2. `Predictions: n/m` lands in the report (§11.5c).

**Elevator-specific safety notes carried in the recipe:**
- Steps 4, 5, 10 and 11 run inside the standard `SafetyEnvelope.derive` band, which is `max(softMargin, 0.05 * range)` inside the **hard** limits (§7.1) and therefore strictly inside the device's own soft limits regardless of what margin the team configured. The supervisor trips first and the student sees a named reason instead of a silent clamp. This is enforced by `SafetyEnvelopeTest`'s strict inequality, not by a recipe-local adjustment.
- Step 5 (`kA`) uses a 1.5 s dynamic timeout regardless of the derived value. A dynamic step on a vertical elevator is the single most dangerous motion in the whole recipe.
- If `getStatorCurrentAmps()` is empty, steps 4, 5, 10, 11 halve `maxVolts` and say so.

---

### 8.8 Recipe: ARM / PIVOT

**Applies to:** arms, pivots, wrists, hoods, anything with a cosine gravity term.
**Total time:** about 118 seconds of motion (three-angle gravity pre-pass: one 10-iteration bisection at `theta_1` plus two 6-iteration bisections, ~11 s total). **Space needed:** full swing, clear.
**WPILib reference answer for the docs' simulated vertical arm:** kG = 1.75, kV = 1.95, kP = 5, kD = 1.

Identical in shape to the elevator recipe, with these differences:

| # | Difference | Detail |
|---|---|---|
| 1 | **Pre-flight adds the zero-convention question** | "PumpkinLib assumes your arm reads 0 when it points straight out sideways (horizontal). Move the arm to horizontal now and tell me what the encoder says." The measured value is compared with the configured zero; a mismatch > 5 degrees blocks the recipe with an explanation. |
| 2 | **Gravity pre-pass runs at three angles** | The `HoldBisectionStep` three-angle check (§8.4). Chooses the angle closest to horizontal that is inside the safe band as `theta_1`, then `theta_1 +/- 25 degrees` clamped to the band. The move *between* angles is the same closed-loop `recentre()` the bisection uses, not an open-loop command. Fits `V_hold(theta) = kG * cos(theta + phi)`. Reports both `kG` and the phase error `phi`. |
| 3 | **kS is measured near horizontal** | Friction on an arm is roughly angle-independent, but the *gravity cancellation* is only exact if `kG` is right, so the kS ramp is run at `theta_1` where `cos(theta)` is largest and the cancellation is best conditioned. |
| 4 | **The sweep avoids the vertical singularity** | `cos(theta)` goes to zero at +/-90 degrees, so `kG` is unidentifiable from samples taken only near vertical. The quasistatic ramp is constrained to spend at least 60% of its samples within +/-60 degrees of horizontal. If the arm's travel makes that impossible, the recipe warns: `"Your arm never gets within 60 degrees of horizontal, so kG is hard to measure accurately here. The number we give you will be a best effort."` |
| 5 | **Regressor uses `cos(theta)`** | `phi = [cos(theta), sgn(v), v, a]`, `beta = [kG, kS, kV, kA]` |
| 6 | **Profile constraints get an angle-aware cross-check** | `ArmFeedforward.maxAchievableVelocity(11.0, worstCaseAngle, 0)` where `worstCaseAngle` is whichever reachable angle maximizes `|cos(theta)|`. The exponential constraints from `fromCharacteristics` ignore gravity, so if the achievable velocity at the worst angle is less than 80% of the profile's max velocity, the profile is clamped and the student is told why. |
| 7 | **kP/kD sliders default tighter** | Default max acceptable error 2 degrees, max control effort 5 V. |
| 8 | **Verify sweeps the full arc, both directions** | Reports rise/overshoot/settle/SSE separately for the *up* move and the *down* move, because an arm with a wrong kG behaves asymmetrically and this is the clearest way to show it: `"Going up it settles in 0.4 s with 3% overshoot. Coming down it takes 1.1 s and stops 1.8 degrees short. That asymmetry is gravity, not kP - your kG is a little low."` |

---

### 8.9 Recipe: TURRET

**Applies to:** turrets, azimuth stages, counterbalanced hoods. Position control, no gravity.
**Total time:** about 85 seconds.
**WPILib reference answer for the docs' simulated turret:** kV = 0.15, kP = 0.3, kD = 0.05 (feedforward-only answer kV = 0.2; feedback-only answer kP = 0.3, kD = 0.05).

Same shape as the elevator recipe with the gravity steps removed:

1. Pre-flight (health check; **gravity-present test is a hard check here** — if the mechanism drifts when released, the archetype is wrong and the recipe stops).
2. Find kS.
3. Find kV.
4. Find kA.
5. *(kG skipped, with the narration in §8.6 step 5.)*
6. Build the profile.
7. Suggest kP / kD.
8. Check and refine.
9. Verify: two moves, +90 degrees and -90 degrees (clamped to the band).

**Turret-specific teaching moment, shown at step 7.** WPILib's turret tutorial makes a point worth repeating verbatim in the UI, because it is the most common conceptual error at this archetype:

> "Feedforward alone can't hold a turret in place. kV and kA describe how much voltage it takes to *move* at a given speed — and when you're sitting still at your target, the answer is zero. That's why a feedforward-only turret gives a little 'kick' when the setpoint changes and then drifts. A position mechanism with no gravity needs feedback to hold. This is the one archetype where kP does most of the work."

**Continuous rotation.** If the turret has more than 360 degrees of travel, `TuningTarget` should expose it as a bounded axis (min/max in radians, unwrapped) and **not** as continuous. Continuous input is only enabled for `STEER`. The user's 8793 turret (`ShooterSubsystem.java:349-369`) is exactly this case: a >360-degree bounded axis with unwrapping, not a continuous one. The recipe verifies the distinction at pre-flight:

> "This mechanism reports 740 degrees of travel, so it is a bounded axis, not a continuous one. PumpkinLib will not enable continuous wrapping. If it can actually spin forever, change the archetype to STEER."

---

### 8.10 Recipe: DRIVE_VELOCITY

**Applies to:** the drive motor of a swerve module, or a differential drivetrain side.
**Delegates to:** the FLYWHEEL recipe, with a different safety envelope and different pre-flight.
**Total time:** about 90 seconds. **Space needed: at least 3 m, ideally 6 m, of clear floor.**

This is deliberately implemented as `TuningRecipe.flywheel().withPreflight(drivePreflight()).withEnvelope(driveEnvelope())` — one line — because *the drive motor is a flywheel with a wheel bolted to it*, and saying that out loud is a good teaching story. YAGSL's own documentation makes the same point.

Drive-specific pre-flight, all of which block:

| Check | Why |
|---|---|
| Robot is **not** on blocks | WPILib: *"the robot drive can not be accurately characterized while on blocks."* Detected by commanding 1.0 V for 300 ms and checking that measured acceleration is below `0.4 * (predicted free-spin acceleration)`. On blocks, the wheel accelerates ~20x faster than the loaded robot does, so this is an easy, reliable test. Message: `"These wheels accelerated far faster than a robot of this mass could. Are you on blocks? Put it on the floor."` |
| At least 3 m of travel available | Asked, not measured: a `Toggle Switch` on the dashboard the student must set. |
| All modules pointed forward and held | The recipe commands the steer axes to zero and holds them for the whole sweep. |
| Only one module (or one side) under test at a time, unless `allModules()` was selected | Per-module gains are the point; averaging four modules hides a bad one. |

**Multi-module mode.** `TuningRecipe.driveVelocity().allModules(4)` runs the sweep once with all four modules driven together and fits **four independent regressions** from the same motion. It then reports the spread:

> "Module kV: FL 0.128, FR 0.126, BL 0.131, BR 0.098. BR is 24% off the others. That module has a different gear ratio, a different wheel diameter, or something dragging. Fix the hardware — do not give it a different kP."

That single output would have caught the CD-reported case of *"one rotation motor seems to need an entirely different P value"* in minutes. It also directly attacks the swerve bring-up failure mode the small-team research names as the single largest consumer of small-team software time.

Published starting points are offered as a fallback if the student skips identification, keyed by vendor, because a single default kP is actively harmful across a 5000x spread:

| Vendor / family | drive kP | steer kP | steer kD |
|---|---|---|---|
| SparkMax / NEO (YAGSL defaults) | 0.0020645 | 0.01 | 0 |
| TalonFX / Kraken / Falcon (YAGSL defaults) | 1.0 | 50.0 | 0.32 |
| CTRE Tuner X generated (`Slot0Configs`) | 0.1 (kS 0, kV 0.124) | 100 (kS 0.1, kV 1.91, kD 0.5) | — |

These are shown **as vendor-native numbers with a warning that PumpkinLib's own gains are in volts-per-SI and are not comparable**, and are offered only as "make it move so you can start" values.

---

### 8.11 Recipe: STEER

**Applies to:** swerve steer/azimuth motors.
**Delegates to:** the TURRET recipe, plus continuous input.
**Total time:** about 70 seconds. **Space needed:** none (steer may be tuned on blocks — and *should* be).

Differences from TURRET:

1. `PIDController.enableContinuousInput(-Math.PI, Math.PI)` is enabled by `Controllers.pid` automatically because `archetype.isContinuous()`.
2. `TravelLimits.unbounded()` is legal and expected; the position aborts (conditions 3 and 4 in §7.2) are disabled and replaced by a *rotation-count* abort: more than 6 full revolutions in one step trips `OVERSPEED` with the message `"The steer motor has spun 6 times without settling. Your encoder offset or your gear ratio is probably wrong."`
3. The verify step commands **+/-90 degree** and **+/-170 degree** moves, the second specifically to exercise the wrap. YAGSL's guidance is repeated in the UI:

   > "Because steer wrapping is enabled, test with left/right translation rather than rotating the robot. A rotation command asks all four modules to go to different angles at once and makes it very hard to see which one is misbehaving."

4. A **cross-contamination check** at pre-flight: if the steer motor's current `Gains` are within 1% of the drive motor's `Gains` on the same module, block with:

   > "Your steer and drive motors have identical gains. They are completely different mechanisms — a steer axis is a position controller and a drive motor is a velocity controller. Applying one set of configs to both is the single most common cause of a swerve that oscillates forever."

   This is the literal root cause reported in the Chief Delphi "Tuning CTRE Swerve" thread, and it is trivially detectable.

---

### 8.12 `TuningRecipe.express()` — the fourth mechanism of the day

**The problem, stated honestly.** The elevator recipe is eleven steps and about 106 s of motion, but with the reading and the predictions it is closer to **eight minutes**. Four mechanisms is half an hour. On one shared robot, with eight students who all want time on it, after every mechanical change, that is not a teaching win — it is a queue. The teaching *is* the product, and a teaching artifact nobody has time to run teaches nothing.

So we ship two modes, and we are explicit about which is which:

```java
package org.pumpkinlib.tuning.wizard;

public final class TuningRecipe {

  public enum Mode {
    /** Every lesson, every prediction, every review screen. ~8 min for an elevator. */
    TEACHING,
    /** Identification only: kG pre-pass, kS, kV, kA. One narration screen. ~90 s. */
    EXPRESS
  }

  /**
   * Identification only. Runs the gravity pre-pass, kS, kV and kA end-to-end behind a single
   * narration screen and a single held trigger, then hands over the numbers and the profile
   * constraints. No PredictStep, no per-step review, no LQR panel, no refinement — kP and kD come
   * straight from {@link org.pumpkinlib.tuning.FeedbackDesigner} at the archetype defaults and are
   * labelled {@code WIZARD_LQR} with no {@code WIZARD_REFINE} pass.
   *
   * <p>Every safety property is unchanged. Express skips *teaching*, never interlocks: the
   * mechanical health check still blocks, the supervisor's preconditions still throw, the sim
   * promotion gate still applies, and Test mode is still required. The only things removed are
   * words on a screen and the student pressing A.
   *
   * <p>The report says so, in the first line, so a mentor reading a pull request can tell:
   * {@code "Mode: EXPRESS - identification only. kP/kD are LQR defaults and were never verified
   * against a step response on hardware."}
   */
  public static TuningRecipe express(MechanismArchetype archetype) { /* ... */ return null; }

  public static TuningRecipe teaching(MechanismArchetype archetype) { /* ... */ return null; }
}
```

**Which one is the default, and why it depends on where you are running.**

| Where | Default | Reason |
|---|---|---|
| `RobotIdentity.current() == RobotId.SIM` | `TEACHING`, always, not overridable per-mechanism | Simulation is free, unqueued, and cannot break anything. This is where the learning is supposed to happen, and §13.11 already tells students to practise here. |
| Real hardware, first time this mechanism has ever been tuned | The wizard **asks**, once, on one screen: *"Full walk-through (about eight minutes, teaches you what each number means) or express (about ninety seconds, just measures them)?"* | A student who has never tuned this mechanism should be offered the lesson, not silently given the shortcut. |
| Real hardware, afterwards | The remembered choice, stored per mechanism in `gains.json` under `"preferredMode"` | The fourth mechanism of the day, and every re-tune after a bearing change, is express by default — which is the case OQ#10 raised. |

The wizard never *hides* the other mode: the state screen always shows `Mode: EXPRESS (Y for the full walk-through)`.

**Why this does not undermine the pedagogy.** A student who ran the teaching recipe on the elevator in simulation on Tuesday has already learned what kV is. Making them re-read the kV lesson on the arm on Saturday at an event is not teaching, it is tax, and tax is what makes teams turn a feature off. The `Predictions: n/m` line (§8.4) is what actually measures whether learning happened, and it is measured in `TEACHING` mode where it belongs.

---

## 9. Assisted kP/kD tuning

### 9.1 The algorithm choice, and why

**We ship: LQR-derived initial gains from measured kV/kA, followed by bounded step-response iterative refinement. We do not ship relay (Astrom-Hagglund) autotune, and we do not ship Ziegler-Nichols.**

The rejection is on two grounds, and both go in the docs so the objection is pre-empted:

1. **Safety.** Both relay autotune and Ziegler-Nichols work by *deliberately driving the loop into sustained oscillation* and measuring the resulting limit cycle. On a geared FRC arm or elevator, sustained oscillation means repeatedly slamming a hard stop with the full inertia of the mechanism, at a frequency chosen by the algorithm rather than by anyone watching. There is no version of this that a library aimed at unsupervised 14-year-olds should ship. Every abort condition in §7.2 exists to *prevent* the exact behaviour relay autotune requires.
2. **It is worse, and the community already knows it.** The FRC-specific discussion of relay autotune concludes that hand tuning is *"usually rated as superior to the autotune relay method"* and that auto-tune *"is not for the uninitiated"*; the thread was redirected to SysId as the proper answer. Shipping a known-inferior, known-dangerous method as the headline feature would be indefensible.

The chosen method has neither problem. LQR is a closed-form solve on a model we already measured — **zero motion required** to produce the initial gains. The refinement loop only ever runs bounded, profiled moves inside the supervisor's envelope, and its update rules are monotone and bounded. The community's stated objection to autotuning is *pedagogical* — that it hides understanding — and this method answers that directly: every number is derived from a physical quantity the student chose, and every iteration is explained.

### 9.2 `FeedbackDesigner` — LQR from measured kV/kA

This is the highest-leverage feature in the domain and it needs no new math. SysId's own Feedback Analysis view derives kP/kD via LQR from kV/kA plus max-acceptable-error, max-acceptable-control-effort, and measurement delay. Every piece is in wpimath. The only thing missing was somebody doing it on the robot.

```java
package org.pumpkinlib.tuning;

import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.LinearQuadraticRegulator;
import edu.wpi.first.math.system.plant.LinearSystemId;

/**
 * Derives kP and kD from measured kV/kA using LQR - the same relationship SysId's
 * "Feedback Analysis" view uses, computed on the robot with no laptop round trip.
 *
 * <p>The student's two knobs are physical quantities, not abstract gains:
 * "how much error can you live with" and "how many volts may I spend correcting it".
 * Smaller acceptable error or larger acceptable effort both produce larger gains.
 */
public final class FeedbackDesigner {

  /** What the student actually chooses. */
  public record Preferences(
      double maxAcceptableErrorSi,      // metres or radians (position) / m/s or rad/s (velocity)
      double maxAcceptableVelocityErrorSi,  // position loops only; defaults to 10x the position error
      double maxControlEffortVolts,     // must be < nominal; the UI clamps at 12
      double measurementDelaySeconds,   // see 9.2.1
      double dtSeconds) {

    public static Preferences defaultsFor(MechanismArchetype a, TravelLimits limits, double toleranceSi) {
      double err = Math.max(toleranceSi, 0.002);
      return new Preferences(err, 10.0 * err, a.hasGravity() ? 4.0 : 3.0, 0.0, 0.020);
    }
  }

  public record Suggestion(double kP, double kD, String explanation, java.util.List<String> warnings) {}

  /** POSITION archetypes: states are [position, velocity], one input (volts). Returns kP and kD. */
  public static Suggestion forPosition(double kV, double kA, Preferences p) {
    var plant = LinearSystemId.identifyPositionSystem(kV, kA);
    var lqr = new LinearQuadraticRegulator<>(
        plant,
        VecBuilder.fill(p.maxAcceptableErrorSi(), p.maxAcceptableVelocityErrorSi()),
        VecBuilder.fill(p.maxControlEffortVolts()),
        p.dtSeconds());
    if (p.measurementDelaySeconds() > 0) {
      lqr.latencyCompensate(plant, p.dtSeconds(), p.measurementDelaySeconds());
    }
    var K = lqr.getK();                     // Matrix<N1, N2>
    return new Suggestion(K.get(0, 0), K.get(0, 1), explain(p), checks(K, p));
  }

  /** VELOCITY archetypes: one state (velocity), one input. Returns kP; kD is 0. */
  public static Suggestion forVelocity(double kV, double kA, Preferences p) {
    var plant = LinearSystemId.identifyVelocitySystem(kV, kA);
    var lqr = new LinearQuadraticRegulator<>(
        plant,
        VecBuilder.fill(p.maxAcceptableErrorSi()),
        VecBuilder.fill(p.maxControlEffortVolts()),
        p.dtSeconds());
    if (p.measurementDelaySeconds() > 0) {
      lqr.latencyCompensate(plant, p.dtSeconds(), p.measurementDelaySeconds());
    }
    return new Suggestion(lqr.getK().get(0, 0), 0.0, explain(p), java.util.List.of());
  }
}
```

> **Verified WPILib signatures:**
> `LinearQuadraticRegulator(LinearSystem<States,Inputs,Outputs> plant, Vector<States> qelms, Vector<Inputs> relms, double dtSeconds)`;
> `void latencyCompensate(LinearSystem<States,Inputs,Outputs> plant, double dtSeconds, double inputDelaySeconds)`;
> `Matrix<Inputs,States> getK()`;
> `LinearSystemId.identifyPositionSystem(double kV, double kA)` returning `LinearSystem<N2,N1,N2>`;
> `LinearSystemId.identifyVelocitySystem(double kV, double kA)` returning `LinearSystem<N1,N1,N1>`.
>
> **[UNVERIFIED]** The exact Q and R matrices SysId's own Feedback Analysis constructs internally could not be read from `sysid`'s C++ source; the construction above is inferred from WPILib's prose (*"via LQR"*, *"Max Acceptable Error"*, *"Max Acceptable Control Effort"*) plus the public wpimath API. The relationship is Bryson's rule — `Q = diag(1/qelms^2)`, `R = diag(1/relms^2)` — which is what the `Vector` overload of the constructor documents ("maximum desired error tolerance for each state" / "maximum desired control effort for each input"). PumpkinLib's numbers may therefore differ slightly from SysId's for the same inputs. This is stated in the UI: *"These are educated starting points, not final answers"* — WPILib's own framing.

#### 9.2.1 Measurement delay

Getting this wrong is the main way LQR-derived gains oscillate on real hardware.

| `LoopLocation` | Delay used | Rationale |
|---|---|---|
| `ON_MOTOR` (Phoenix 6 / REVLib closed loop) | `0.0` | The device's own filters are already accounted for by the vendor; WPILib says smart-motor-controller filters are auto-handled. |
| `ON_CONTROLLER`, no user filter | `0.5 * dtSeconds` = 10 ms | One-sample transport plus zero-order hold. |
| `ON_CONTROLLER`, user declared an N-sample moving average at period T | `T * (N - 1) / 2` | The formula WPILib documents for a windowed filter. |

`TuningTarget` gets an optional `default double measurementDelaySeconds() { return Double.NaN; }`; when NaN, the table above applies.

#### 9.2.2 The UI panel — and why it does not just print a number

This is the kP step. It is the gain students most need intuition about, and in the first version of this design it was the one delivered as an oracle: two sliders in, a number out of a solver whose Q/R construction is `[UNVERIFIED]`, and `Lessons.P` sitting next to it explaining in prose what a P gain *is* with no bridge to the arithmetic. That is the weakest point in the whole wizard as a teaching artifact, and the fix is cheap: **print the second-order interpretation, derived from the same numbers, above the gains.**

```
  Max error I can live with     [====|--------]   0.010 m
  Max volts I'll spend on it    [========|----]   4.0 V
  Measurement delay                                0 ms  (loop runs on the motor controller)

  With these gains your elevator behaves like a spring that would bounce
  at 2.3 Hz, damped to 0.62.
  Under 0.7 you will see overshoot - that is exactly what the next step checks.

      kP = 18.42  V/m          kD = 1.07  V/(m/s)

  Smaller error or more volts -> bigger gains, a faster bounce, and less damping.
  These are educated starting points, not final answers.
```

**The arithmetic, stated so it can be tested.** Model the closed loop as the measured plant `kA * a + kV * v = u` under PD control. Substituting `u = kP*e + kD*edot` gives the standard second-order form, so:

```
wn   = sqrt(kP / kA)                        rad/s      -> natural frequency
fn   = wn / (2 * PI)                        Hz         -> "it would bounce at 2.3 Hz"
zeta = (kD + kV) / (2 * sqrt(kP * kA))                 -> damping ratio
```

Two things about that `zeta` expression are worth saying out loud to the reader of this document, because they are the reason it teaches something a slider cannot:

1. **`kV` is in the numerator.** The mechanism's own back-EMF is damping, for free, before kD does anything. On a high-kV mechanism a student will watch `zeta` sit above 0.7 with `kD = 0` and learn *why* the flywheel recipe leaves kD at zero (§8.6 step 8) without being told.
2. **`kP` is under a square root in `wn` and in the denominator of `zeta`.** Tripling kP makes it 1.7x faster and 1.7x *less* damped, simultaneously. That single sentence is the entire content of the `OVERSHOOT_RING` diagnosis, available before any motion happens.

**This is the same arithmetic `PredictStep` uses** to compute the correct answer to "I'm about to triple your kP — what happens?" (§8.4). The student is shown the formula's output, then asked to predict from it, then shown the real response. That loop — see it, predict it, watch it — is the difference between the wizard teaching and the wizard narrating.

`LessonsTest` asserts both formulas against `LqrSuggestStep`'s implementation, per §13.12: an analytic second-order plant with known `wn` and `zeta` is fed through `FeedbackDesigner` and the panel's reported values must match to 1e-9. If someone changes the panel text without changing the maths, or the maths without the text, the build fails.

Sanity warnings emitted alongside:
- `kP * maxAcceptableError > maxControlEffort * 1.5` → `"These gains will saturate at 12 V for any error bigger than 0.6 m. That's fine with a motion profile, but without one your elevator will slam."`
- `kD > 0.5 * kV` → `"kD is large compared to kV. On a noisy encoder that will make the mechanism buzz. Consider accepting a little more error."`
- `kP <= 0` → hard failure; means kV or kA came out non-physical.
- `zeta < 0.4` → `"Damping is 0.31. This will visibly bounce. That is a legal choice if you want speed, but the next step will classify it OVERSHOOT_RING and offer to add kD."`
- `fn > 0.4 / dtSeconds` (i.e. the loop samples the bounce fewer than about 2.5 times per cycle) → `"These gains want the mechanism to bounce at 22 Hz, and your loop only runs at 50 Hz. The controller cannot see an oscillation that fast, so it will amplify it instead of damping it. Accept more error, or move this loop onto the motor controller."`

### 9.3 Step-response iterative refinement

The LQR suggestion is a starting point. Refinement closes the loop on the *actual* mechanism, including everything the linear model does not know about: backlash, stiction, belt compliance, CAN latency, and a battery that sags.

**The algorithm.**

```
INPUT:  gains g0 (from LQR), target, supervisor, envelope
CONST:  MAX_ITER = 6
        stepMagnitude = 0.25 * travelRange  (position)  or  0.30 * vMaxSafe (velocity)
        settleWindow  = 1.5 s after the profile completes
        bestScore     = +infinity
        best          = g0

FOR iter = 1..MAX_ITER:
    apply(g)
    run one bounded step:
        position: profiled move  p0 -> p0 + stepMagnitude,  hold settleWindow,  return to p0
        velocity: setpoint 0.40*vMax -> 0.70*vMax,          hold settleWindow,  return
      (supervisor live throughout; any abort ends refinement immediately)
    record into ring buffer
    verdict = StepResponseAnalyzer.analyze(buffer, setpointTrace, tolerance, expectedRiseTime)
    score   = cost(verdict)                                  # see below
    IF score < bestScore:  bestScore = score;  best = g
    IF verdict.classification == GOOD:  BREAK
    IF verdict.classification == UNSTABLE:
        g = g0.withPD(0.4 * g.kP, 0.4 * g.kD)                # hard retreat
        supervisor.abort(UNSTABLE_RESPONSE)                  # requires re-arm by the student
        BREAK
    g = update(g, verdict)                                   # table below
OUTPUT: best, plus the full history for the report
```

**The scalar cost** used to pick the best iteration when none reach `GOOD`:

```
cost = (settleTimeSec / expectedSettleSec)
     + 2.0 * max(0, overshootPct - 5) / 100.0
     + 3.0 * abs(steadyStateErrorSi) / toleranceSi
     + 5.0 * (oscillationCrossings > 4 ? 1 : 0)
```

Weights chosen so that steady-state error and ringing are punished harder than being slow — a small team's mechanism that is 20% slow and rock solid is a better outcome than one that is fast and rings.

**The update rules.** Deterministic, bounded, and each one paired with the sentence the student sees.

| Classification | Disambiguator | Update | Bound | Shown to the student |
|---|---|---|---|---|
| `SLUGGISH` | — | `kP *= 1.6` | `kP <= 8 * kP_lqr` | "It's getting there, just slowly. Turning kP up by 60%." |
| `OVERSHOOT_RING` | `kD == 0` | `kD = 0.35 * kD_lqr_or_kV` | — | "It overshoots and rings. That's a spring with no shock absorber - adding some kD." |
| `OVERSHOOT_RING` | `kD > 0`, first two occurrences | `kD *= 1.5` | `kD <= 4 * kD_lqr` | "Still ringing. More damping." |
| `OVERSHOOT_RING` | `kD > 0`, third occurrence | `kP *= 0.7` | — | "More damping isn't helping, so the spring is just too stiff. Cutting kP by 30%." |
| `OSCILLATING` | `oscHz < 8 Hz` | `kP *= 0.6`, `kD *= 0.8` | — | "It's swinging back and forth about twice a second. That's a kP that's too high. Cutting it by 40%." |
| `OSCILLATING` | `oscHz >= 8 Hz` | `kD *= 0.5` | — | "It's buzzing at 14 Hz. That's too fast to be the mechanism - kD is amplifying sensor noise. Halving kD." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, position mechanism, error in the direction of the last motion | `kS += clamp(0.6 * abs(residualVolts), 0, 0.20)` | `kS <= 0.25 * nominal` | "It stops just short every time, and the controller is holding a steady 0.31 V trying to close the gap. That 0.31 V is friction your kS isn't paying for. Raising kS." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, gravity mechanism, error consistently *downward* | `kG += clamp(0.6 * abs(residualVolts), 0, 0.30)` | `kG <= 0.6 * nominal` | "It settles 8 mm low every time and holds 0.22 V doing it. That's gravity your kG isn't paying for. Raising kG." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, neither signature | `kP *= 1.4` | `kP <= 8 * kP_lqr` | "There's a small offset that doesn't look like friction or gravity. Nudging kP up. If this doesn't clear it, read the note about kI." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` empty** | **no gain change**; step ends `RETRY_SUGGESTED` | — | "This mechanism's loop runs on the motor controller and does not report how it split feedforward from feedback, so I cannot tell you whether the leftover error is friction or gravity. Run this mechanism with the loop on the RIO for one session — `LoopLocation.ON_CONTROLLER`, which the mechanism builder calls `ControlLocation.RIO_FULL` — if you want that diagnosis." |
| `GOOD` | — | stop | — | "That's a good response. Rise 0.34 s, 3% overshoot, settled in 0.51 s, final error 2 mm." |
| `UNSTABLE` | — | retreat and abort | — | "Stopping. The response was growing instead of settling, which is how mechanisms break. kP has been cut to 40% of what it was. Press Retry when you're ready." |

#### 9.3.1 `residualVolts` — the quantity, and when it does not exist

`residualVolts` is the key quantity in the steady-state rules and deserves the explicit definition:

```
residualVolts = mean over the last 0.5 s of target.getFeedbackVolts()
```

i.e. exactly the voltage the *feedback* term is holding to keep the mechanism where it is. In steady state that voltage is, by definition, the feedforward term that is missing. Adding 60% of it to kS or kG (rather than 100%) keeps the loop from over-correcting and oscillating between iterations. This is the single most useful piece of arithmetic in the whole refinement loop, and it is why PumpkinLib can tell a student *"add kS"* instead of *"add kI"*.

> **A naming note the mechanism domain must reconcile.** This document's enum is `LoopLocation` with values `ON_MOTOR` / `ON_CONTROLLER` (§3.1). `DESIGN.md` §10.1's mechanism builder exposes the same choice as `ControlLocation` with values `ON_MOTOR_PROFILED` / `RIO_FULL`. These are the same axis with two names, which is exactly the kind of drift that produces a doc example that does not compile. **Decision: `LoopLocation` is the tuning-domain SPI type and `ControlLocation` is the builder-facing type, and domain 03 owns a one-line total mapping between them.** Every student-facing message in this section names both, because the student sees the builder name in their own code.

**It is not always computable, and the design now says so instead of pretending.** The original definition was `mean(commandedVolts - feedforwardVolts)`, which quietly assumed the robot could see both halves. When `loopLocation() == ON_MOTOR` — the library's recommended default, `ControlLocation.ON_MOTOR_PROFILED`, and the setting used by every mechanism in `DESIGN.md` §10.1 — the feedforward is computed *inside* the Talon or the Spark and `TuningTarget` exposed only `getAppliedVolts()`, which is the sum. `feedforwardVolts` was not computable, so all three `STEADY_STATE_ERROR` rules were dead on the default configuration and the wizard fell through to the `kP *= 1.4` catch-all — which is precisely the "reach for kP/kI instead of kS/kG" mistake §13.7 says this library exists to prevent.

The fix is `TuningTarget.getFeedbackVolts()` (§3.1), an `OptionalDouble` the vendor adapter fills in when it genuinely can:

**Phoenix 6.** The split is directly reported. `TalonFX` exposes both halves as `StatusSignal<Double>`:

```java
@Override public OptionalDouble getFeedbackVolts() {
  if (!m_closedLoopSignalsSubscribed) return OptionalDouble.empty();
  return OptionalDouble.of(
      m_leader.getClosedLoopOutput().getValueAsDouble()
    - m_leader.getClosedLoopFeedForward().getValueAsDouble());
}
```

**Signature verified 2026-08-07** against the Phoenix 6 Java API docs ([CoreTalonFX](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/hardware/core/CoreTalonFX.html)): `StatusSignal<Double> getClosedLoopOutput()` and `StatusSignal<Double> getClosedLoopFeedForward()` both exist, with `(boolean refresh)` overloads, documented as *"Closed loop total output"* and *"Feedforward passed by the user"* respectively.

Two notes the adapter must honour:

1. **[UNVERIFIED — inferred, needs a bench check]** The javadoc does **not** state the units of `getClosedLoopOutput()`. We infer that it carries the units of the active control request's output type, which makes it volts only for voltage-output requests (`PositionVoltage`, `VelocityVoltage`, `MotionMagicVoltage`, `MotionMagicExpoVoltage`). The adapter therefore returns `OptionalDouble.empty()` for duty-cycle and torque-current requests rather than guessing — the conservative direction, since a wrong scale factor here would feed a fabricated `residualVolts` straight into the kS/kG update rules. Someone with a Kraken on a bench must confirm the voltage-request case reads back in volts before 0.1 ships; this is now open question 12.
2. Neither signal is in the default subscribed set, so domain 04 adds them via `BaseStatusSignal.setUpdateFrequencyForAll(50, ...)` **only when `TuningRegistry` has registered this target**. A team that never tunes pays no bus bandwidth.

**REVLib.** REV exposes no equivalent signal. The adapter computes the feedforward half in Java from the canonical `Gains` and the current profile state — which it already has, because it is the code that fed the arbitrary feedforward to the controller in the first place — and subtracts:

```java
@Override public OptionalDouble getFeedbackVolts() {
  double ff = m_feedforward.calculate(getPosition(), m_profileState.velocity, m_nextProfileState.velocity)
            + (m_gains.gravityType() == GravityType.NONE ? 0 : m_gains.kG() * gravityShape());
  return OptionalDouble.of(m_appliedVolts - ff);
}
```

This is a *reconstruction*, not a measurement, and `describeConversion()` says so verbatim so the student is never misled about which one they are looking at:

```
Wrist gain conversion (REVLib, voltage-compensated 12.0 V, on-motor loop)
  ...
  NOTE: REVLib does not report how the controller split feedforward from feedback.
        PumpkinLib reconstructs the feedforward half in Java from your kS/kV/kA/kG and the
        profile state, so "residual volts" here is computed, not measured. On a Phoenix 6
        device the same number is read directly off the motor controller.
```

**When it is empty.** Generic/WPILib `MotorController` targets on an `ON_MOTOR` loop, torque-current and duty-cycle Phoenix requests, and any hand-rolled adapter that does not override the default all return `empty()`. In that case the wizard **skips the kS/kG rules entirely** — it does not guess, and it does not silently fall through to kP. It shows the message in the table above, ends the step `RETRY_SUGGESTED`, and points at the one-line change that would restore the diagnosis. `StepResponseAnalyzer` still reports `steadyStateErrorSi` (which needs only position), and `ResponseVerdict.residualVolts` is `NaN` rather than a fabricated zero. `RefinementRuleTest` (§16.1) covers both branches: present and empty.

**Safety properties of the refinement loop, stated explicitly because reviewers will ask:**

1. It never issues an unprofiled step to a position mechanism. Every move is a `TrapezoidProfile` or `ExponentialProfile` bounded by constraints derived from measured kV/kA.
2. Step magnitude is 25% of travel and starts from `softMin + 0.35 * range`, so the mechanism is never near either hard stop.
3. Every iteration runs inside `TuningSupervisor` with the full abort list live. A single `LIMIT_APPROACH` ends refinement and reverts to `best`.
4. Gains only move by bounded multiplicative factors within hard caps relative to the LQR baseline. There is no path by which the loop can produce a kP an order of magnitude above the physics-derived value.
5. Maximum six iterations, each at most `2 * (profileTime) + 1.5 s`, so the worst case is bounded at about 45 s.
6. The `UNSTABLE` classification is a *terminal* state that requires the student to physically re-pull the trigger. There is no automatic retry after instability.
7. The trigger must be held for the entire loop. Letting go stops everything, and the best-so-far gains are kept.

**An arm must never slam a hard stop.** The properties above are what make that true, and the arm recipe adds one more: for `ARM`, `stepMagnitude` is additionally clamped so the move stays within +/-45 degrees of the starting angle, and the refinement start position is chosen as the point in the safe band furthest from both limits. An `ARM` whose safe band is smaller than 20 degrees is refused with `"There isn't enough safe travel here to test a step response. Widen your soft limits or tune this one by hand."`

---

## 10. Response diagnostics — the teaching layer

This is what turns a plot into a lesson. Given a recorded step (or profiled move), classify it and say something a 14-year-old can act on.

### 10.1 The sample buffer

```java
package org.pumpkinlib.tuning.sysid;

/**
 * Fixed-capacity ring buffer of loop samples. Allocated once at construction; never grows.
 * Capacity 3000 at 50 Hz = 60 s, which covers any single step or sweep.
 */
public final class SampleBuffer {
  public record Sample(double t, double setpoint, double measurement, double velocity,
                       double commandedVolts, double feedforwardVolts, double feedbackVolts) {}

  public SampleBuffer(int capacity) { /* ... */ }
  public void add(double t, double setpoint, double measurement, double velocity,
                  double commandedVolts, double ffVolts, double fbVolts) { /* ... */ }
  public int size() { /* ... */ return 0; }
  public Sample at(int i) { /* ... */ return null; }     // 0 = oldest
  public void clear() { /* ... */ }

  /** Copy of the window from t0 to t1, for analysis. Allocates once, off the hot path. */
  public java.util.List<Sample> window(double t0, double t1) { /* ... */ return java.util.List.of(); }
}
```

### 10.2 The metrics — actual math

Let the analysed window be samples `i = 0..N-1` with time `t_i`, measurement `y_i`, setpoint `r_i`. Let `y0 = y_0`, `r = r_{N-1}` (final setpoint), and `D = r - y0` the commanded change. Define the normalized response `e_i = (y_i - y0) / D`. If `|D|` is below `4 * tolerance`, the step is too small to analyse and the analyzer returns `INSUFFICIENT_EXCITATION`.

**Rise time** — 10% to 90% of the commanded change, by linear interpolation between bracketing samples:

```
t10 = first t where e >= 0.10       (interpolated)
t90 = first t where e >= 0.90       (interpolated)
riseTime = t90 - t10
```

If `e` never reaches 0.90, `riseTime = NaN` and the response is at least `SLUGGISH`.

**Peak and overshoot:**

```
ePeak       = max over i of e_i
overshootPct = 100 * (ePeak - 1.0)          // negative means it never got there
tPeak        = argmax
```

**Settling time** — 2% band by default, configurable, but never tighter than the mechanism's own tolerance:

```
band  = max(0.02, tolerance / |D|)
tLast = last t where |e - 1| > band
settleTime = tLast - t_0                    // NaN if it never settles
settled    = (tLast < t_{N-1} - 0.25)       // must stay in band for the final 0.25 s
```

**Steady-state error** — mean over the final 0.5 s:

```
sse = mean over the last 0.5 s of (r_i - y_i)          // signed, in SI units
ssePct = 100 * sse / D
```

**Residual voltage** — the missing feedforward (§9.3.1):

```
residualVolts = mean over the last 0.5 s of feedbackVolts_i        // from TuningTarget.getFeedbackVolts()
              = NaN                                                 // when the split is not observable
```

`SampleBuffer.Sample.feedbackVolts` is `NaN` for the whole window when `getFeedbackVolts()` is empty, and every consumer treats `NaN` as "unknown," never as zero. The `/PumpkinTuner/plot/ffVolts` and `/plot/fbVolts` topics publish `NaN` too, so the two-line graph visibly *stops* rather than drawing a flat zero that a student would read as "feedback is doing nothing."

**Oscillation frequency** — zero crossings of the error signal after the first peak:

```
crossings = count of i > iPeak where sign(y_i - r) != sign(y_{i-1} - r)
tSpan     = t_{N-1} - t_{iPeak}
oscHz     = crossings / (2 * tSpan)         // two crossings per cycle
```

`oscHz` is only meaningful when `crossings >= 3`; below that it is reported as `NaN`.

**Damping ratio via logarithmic decrement.** Find the successive *same-sign* peaks of the error signal `|e_i - 1|` after `tPeak`. Let `A1` and `A2` be the first two such peak amplitudes separated by one full oscillation period:

```
delta = ln(A1 / A2)                          // logarithmic decrement
zeta  = delta / sqrt(4 * PI^2 + delta^2)
```

If fewer than two peaks exist, `zeta = NaN` and the response is treated as non-oscillatory. If `A2 > A1`, `delta < 0`, `zeta < 0`, and the response is **divergent** — which is the primary `UNSTABLE` detector.

**Expected rise time**, the reference the `SLUGGISH` test compares against. For a velocity loop the plant is first-order with time constant `tau = kA / kV`, and the 10-90% rise time of a first-order step is `ln(9) * tau = 2.197 * tau`. For a profiled position move the reference is the profile's own duration:

```
velocity: expectedRise  = 2.197 * (kA / kV)
position: expectedRise  = 0.80 * profileDurationSeconds
          expectedSettle = profileDurationSeconds + 0.25
```

### 10.3 Classification — ordered rules

Evaluated top to bottom; first match wins. This ordering matters: instability must be caught before anything else, and a mechanism that never arrives must not be classified on its (nonexistent) overshoot.

```java
package org.pumpkinlib.tuning.diagnostics;

public enum ResponseClass {
  INSUFFICIENT_EXCITATION,   // the step was too small to say anything
  UNSTABLE,
  OSCILLATING,
  OVERSHOOT_RING,
  STEADY_STATE_ERROR,
  SLUGGISH,
  GOOD
}
```

| Order | Class | Condition |
|---|---|---|
| 0 | `INSUFFICIENT_EXCITATION` | `abs(D) < 4 * tolerance` OR `N < 25` |
| 1 | `UNSTABLE` | `zeta < 0` (divergent peaks, `A2 > 1.05 * A1`) **OR** `ePeak > 3.0` **OR** the supervisor tripped **OR** `abs(e_{N-1} - 1) > 0.5` after a full settle window |
| 2 | `OSCILLATING` | `crossings >= 4` **AND** (`zeta < 0.15` **OR** `zeta` is NaN) **AND** not settled |
| 3 | `OVERSHOOT_RING` | `overshootPct > 15` **AND** `0.15 <= zeta < 0.5` |
| 4 | `STEADY_STATE_ERROR` | settled **AND** `abs(sse) > max(tolerance, 0.03 * abs(D))` **AND** `overshootPct < 8` |
| 5 | `SLUGGISH` | `riseTime` is NaN **OR** `riseTime > 2.5 * expectedRise` **AND** `overshootPct < 3` |
| 6 | `GOOD` | `overshootPct <= 8` **AND** `abs(sse) <= max(tolerance, 0.02 * abs(D))` **AND** settled **AND** `settleTime <= 1.75 * expectedSettle` |
| 7 | fallback | If none match, report `OVERSHOOT_RING` if `overshootPct > 8`, else `SLUGGISH`. Never return "unknown" to a student. |

### 10.4 `ResponseVerdict` and the plain-language layer

```java
package org.pumpkinlib.tuning.diagnostics;

public record ResponseVerdict(
    ResponseClass classification,
    double riseTimeSec,
    double overshootPct,
    double settleTimeSec,
    boolean settled,
    double steadyStateErrorSi,
    double steadyStatePct,
    double residualVolts,
    double dampingRatio,
    double oscillationHz,
    int oscillationCrossings,
    double peakVolts,
    boolean saturated,             // commanded volts hit the ceiling for > 100 ms
    String headline,               // one line, always populated
    String diagnosis,              // 1-3 sentences of plain language
    String recommendation) {}      // one concrete action
```

```java
package org.pumpkinlib.tuning.diagnostics;

/** Pure, HAL-free. Reusable by the health domain to answer "is this mechanism still tuned?" */
public final class StepResponseAnalyzer {

  public static ResponseVerdict analyze(
      java.util.List<org.pumpkinlib.tuning.sysid.SampleBuffer.Sample> window,
      double toleranceSi,
      double expectedRiseSec,
      double expectedSettleSec,
      double voltageCeiling) { /* ... */ return null; }
}
```

**The actual recommendation text**, one entry per class. These strings ship in `Coach` and are published to `/PumpkinTuner/result/verdict` and `/recommendation`. Numbers in braces are substituted.

| Class | `diagnosis` | `recommendation` |
|---|---|---|
| `GOOD` | "Nice. It got there in {riseTime} s, overshot by {overshootPct}%, settled in {settleTime} s, and finished {sse} off target — inside your tolerance of {tolerance}." | "Nothing to change. Press A to keep these gains." |
| `SLUGGISH` | "It's heading the right way, just lazily. It took {riseTime} s to cover 10% to 90% of the move; for this mechanism that should be closer to {expectedRise} s. There's no overshoot at all, which means kP is doing less than it could." | "Turn kP up. PumpkinLib will multiply it by 1.6 and try again — press A, or press B to change it yourself." |
| `OVERSHOOT_RING` | "It overshoots by {overshootPct}% and then rings {crossings} times before settling. Damping ratio came out at {zeta} — anything under about 0.7 will visibly bounce. That's a stiff spring with no shock absorber." | "Add damping: cut kP by 30% **or** add kD. PumpkinLib will try kD first, because that keeps the mechanism fast. Press A." |
| `OSCILLATING` (low freq) | "It's swinging back and forth {oscHz} times a second and not settling. Damping ratio {zeta}. This is a kP that's too high for this mechanism — the spring is so stiff it throws the mechanism past the target every time." | "Cut kP by 40%. Press A. If it still oscillates after two tries, check for backlash — no gain can fix slop." |
| `OSCILLATING` (>= 8 Hz) | "It's buzzing at {oscHz} Hz. That's far too fast to be the mechanism itself moving — it's kD amplifying noise in your encoder reading and feeding it back into the motor." | "Halve kD. Press A. If the buzz persists at kD = 0, your encoder is noisy or your velocity signal is being filtered somewhere you don't know about." |
| `STEADY_STATE_ERROR` (friction signature) | "It settles {sse} short of the target and just sits there. The controller is holding {residualVolts} V trying to close that gap. That voltage is friction — and friction is exactly what kS is for." | "Raise kS by {delta} V. Do **not** reach for kI: a constant offset means a feedforward term is missing, and adding an integrator hides the problem instead of fixing it." |
| `STEADY_STATE_ERROR` (gravity signature) | "It settles {sse} low, every time, in the direction gravity pulls. The controller is holding {residualVolts} V just to stop it sinking further. That's gravity your kG isn't paying for." | "Raise kG by {delta} V. If raising kG makes it settle *high* on the way down but still low on the way up, your arm's zero angle is wrong — run the gravity pre-pass again." |
| `UNSTABLE` | "Stopped. The oscillations were getting bigger, not smaller ({A1} then {A2}). That's how mechanisms break." | "kP has been cut to 40% of what it was and the routine is disarmed. Pull the trigger again when you're ready to retry. If this happens twice, your kV or kA measurement is probably wrong — re-run the identification steps." |
| `INSUFFICIENT_EXCITATION` | "That move was too small to learn anything from — it only travelled {D}, and your tolerance is {tolerance}." | "Nothing changed. Increase the step size, or widen your tolerance if {tolerance} is unrealistically tight." |
| any, with `saturated == true` | (appended) "Also: the motor was commanded to its {ceiling} V ceiling for {duration} s during this move. While it's saturated, kP and kD do nothing at all — the mechanism is just going as fast as it can." | (appended) "Either use a motion profile so the setpoint stays reachable, or make the step smaller." |

The saturation append is important and almost always missing from hand tuning: a student watching a saturated response draws conclusions about gains that were not in the loop at the time.

### 10.5 Where the verdict shows up

- Live in the wizard's `REVIEW` state.
- In the markdown tuning report (§11.4), with the numbers.
- As a `HealthMonitor` check between matches: `TuningHealth.check(target)` runs a single small profiled move during a pit test and raises a warning alert if the classification has degraded from `GOOD` since the last tuning session. Belts stretch, batteries age, and a mechanism that was tuned in week 1 is often not tuned by champs.

---

## 11. Gain persistence

### 11.1 The problem

Nothing in the FRC ecosystem closes this loop. 6328's `LoggedTunableNumber` reads the dashboard and falls back to a compile-time default; nothing writes back. WPILib `Preferences` persists to flash but is a separate, flat, manually-wired system with its own keys. DogLog mirrors tunables into the DataLog, which is post-hoc analysis, not persistence. The result is the failure every team knows: *we tuned it, then power-cycled, and lost everything* — or the slightly worse version, *we tuned it, it worked all day, and then someone redeployed*.

### 11.2 Filesystem realities on the roboRIO

| Path | Persistence | Notes |
|---|---|---|
| `Filesystem.getDeployDirectory()` = `/home/lvuser/deploy` | Rewritten by `./gradlew deploy` | This is where `src/main/deploy/**` lands. **[UNVERIFIED]** whether GradleRIO deletes files not present in the source tree — behaviour has varied by year and by artifact config — so PumpkinLib treats anything here as *replaceable at any deploy* and never writes runtime state to it. |
| `Filesystem.getOperatingDirectory()` = `/home/lvuser` | Survives deploys and reboots | Where runtime state belongs. Cleared only by re-imaging. |
| `/U` (USB stick) | Survives everything, removable | Used for logs, not for gains — a gains file that vanishes when someone borrows the stick is worse than no gains file. |
| `Preferences` (NT-backed roboRIO flash) | Survives deploys and reboots | Flat `String -> double` keys, no structure, no metadata, no diffing, and the same key namespace as every other subsystem. Rejected as the primary store; see §11.7. |

In desktop simulation, `getOperatingDirectory()` is the launch directory and `getDeployDirectory()` is `pwd/src/main/deploy` — both verified. So the same code paths work in sim with no branching, and a student tuning in sim writes a file they can actually see in their project.

### 11.3 Load-order precedence

Four sources, lowest to highest priority. Every gain records which source it came from, and that source string is what appears in the NT metadata (§5.5) and the UI.

```
1. CODE_DEFAULT   the Gains passed to the mechanism constructor in Java
                  -> always present, always the fallback

2. DEPLOY_FILE    src/main/deploy/pumpkin/gains.json  ->  /home/lvuser/deploy/pumpkin/gains.json
                  -> CHECKED INTO GIT. This is the team's committed, reviewed answer.

3. ROBOT_FILE     /home/lvuser/pumpkin/gains.json
                  -> written by the wizard / the Save button. Survives deploy and reboot.
                  -> the "we tuned it at the field on Saturday" file.

4. DASHBOARD      live NT value under /Tuning/<Mechanism>/<gain>
                  -> highest priority while tuning is enabled; ignored entirely under FMS.
```

Rules:

- Merging is **per gain**, not per mechanism. If `gains.json` on the robot only contains `kP`, every other gain still comes from the deploy file or the code default. This matters because a student who bisects only kG should not accidentally revert kV.
- Loading happens once, in `TuningRegistry.register(target)`, before the first `applyGains`.
- A mechanism whose `configHash` (§7.6) differs from the one recorded in a file **ignores that file's gains** and raises a warning alert: `"Elevator gains in /home/lvuser/pumpkin/gains.json were tuned for a different gear ratio (45.0, now 60.0). Ignoring them and using code defaults. Delete the file or re-tune."` This is the single most valuable line in the whole persistence layer: gains silently surviving a mechanical change is how a robot gets destroyed after a rebuild.
- Under FMS, the `DASHBOARD` tier is skipped entirely and the effective gains are frozen at boot. The UI states which tier won, per gain, so a pit crew can answer "what is the robot actually running?" in one glance.

### 11.4 File format

Plain JSON, hand-editable, diffable, with metadata that makes it self-explaining. Written with a deterministic key order so a git diff shows only what changed.

`src/main/deploy/pumpkin/gains.json` (checked in) and `/home/lvuser/pumpkin/gains.json` (runtime) share one schema:

```json
{
  "schema": "pumpkinlib.gains/1",
  "writtenAt": "2026-08-06T14:12:33Z",
  "writtenBy": "TuningWizard 0.1.0",
  "wpilib": "2026.2.1",
  "mechanisms": {
    "Elevator": {
      "configHash": "e3b0c44298fc1c14",
      "archetype": "ELEVATOR",
      "units": "LINEAR_METRES",
      "gains": {
        "kS": 0.284,
        "kV": 3.071,
        "kA": 0.412,
        "kG": 2.2763,
        "kP": 18.42,
        "kI": 0.0,
        "kD": 1.07,
        "iZone": 0.0,
        "iMaxVolts": 0.0,
        "tolerance": 0.010,
        "profile": { "kind": "EXPONENTIAL", "maxVelocity": 2.94, "maxAcceleration": 8.11 }
      },
      "provenance": {
        "kS": "WIZARD_OLS", "kV": "WIZARD_OLS", "kA": "WIZARD_OLS",
        "kG": "WIZARD_BISECTION", "kP": "WIZARD_REFINE", "kD": "WIZARD_LQR"
      },
      "quality": {
        "voltageFitR2": 0.981,
        "rmseVolts": 0.094,
        "samples": 4820,
        "finalResponse": "GOOD",
        "riseTimeSec": 0.34, "overshootPct": 3.1, "settleTimeSec": 0.51,
        "steadyStateErrorSi": 0.002
      },
      "preferredMode": "EXPRESS",
      "simPromotion": {
        "completedAt": "2026-08-06T13:41:02Z",
        "recipeVersion": "elevator/1",
        "runsCompleted": 9,
        "worstMarginToLimitSi": 0.061,
        "worstMarginToHardStopSi": 0.111,
        "worstCaseDescription": "kV x1.0, kA x3.0, position reference +5% of travel"
      },
      "session": { "mode": "TEACHING", "predictionsCorrect": 3, "predictionsAsked": 4 }
    }
  }
}
```

`GainStore`:

```java
package org.pumpkinlib.tuning.persist;

import java.nio.file.Path;
import java.util.Optional;
import org.pumpkinlib.control.Gains;

public final class GainStore {

  /** /home/lvuser/pumpkin/gains.json on the roboRIO; ./pumpkin/gains.json in sim. */
  public static Path robotFile() { /* Filesystem.getOperatingDirectory() */ return null; }

  /** /home/lvuser/deploy/pumpkin/gains.json; src/main/deploy/pumpkin/gains.json in sim. */
  public static Path deployFile() { /* Filesystem.getDeployDirectory() */ return null; }

  /**
   * Resolve the effective gains for a mechanism using the precedence in section 11.3.
   * Never throws: an unreadable or malformed file raises a warning alert and is skipped,
   * because a robot that will not boot because a JSON file has a stray comma is unacceptable.
   */
  public static Resolved resolve(String mechanism, String configHash, Gains codeDefault) { /* ... */ return null; }

  public record Resolved(Gains gains, java.util.Map<Gains.GainId, String> provenance,
                         java.util.List<String> warnings) {}

  /** Atomically write the runtime file (temp file + rename), preserving other mechanisms' entries. */
  public static void saveToRobot(String mechanism, String configHash, Gains gains,
                                 java.util.Map<Gains.GainId, String> provenance,
                                 Optional<QualityRecord> quality) { /* ... */ }

  /** Delete the runtime entry for one mechanism, reverting to the deploy file / code default. */
  public static void forget(String mechanism) { /* ... */ }
}
```

Atomic write is mandatory: write `gains.json.tmp`, `fsync`, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`. A brownout mid-write must not leave a truncated file that bricks the next boot.

### 11.5 Getting gains back into source

The runtime file is the safety net. The *committed* file is the goal, because it is the artifact that survives a student graduating — which the small-team research names as the single biggest institutional risk.

Three write-back paths, all one action:

**(a) Update the deploy file.** `GainsExporter.writeDeployBaseline()` writes `src/main/deploy/pumpkin/gains.json` **when running in simulation** (where that path is inside the project) and, on the robot, writes `/home/lvuser/pumpkin/gains-for-commit.json` plus a console line telling the student to copy it. This is the primary path and it produces a git diff a mentor can review:

```
   "Elevator": {
     "gains": {
-      "kG": 2.28,
+      "kG": 2.2763,
-      "kP": 2.0,
+      "kP": 18.42,
```

**(b) Paste-ready Java.** `GainsExporter.toJava(mechanism)` returns a block matching the user's stated conventions (`kConstantName` / `UPPER_SNAKE_CASE`, unit in a trailing comment, 4-space indent, no `m_` prefix on constants) so it drops straight into the one-file `Constants.java` the template mandates:

```java
// ---- Elevator ---- generated by PumpkinLib TuningWizard 0.1.0 on 2026-08-06T14:12:33Z
// Fit: R2 0.981, RMSE 0.094 V, 4820 samples. Final response: GOOD (rise 0.34 s, 3.1% overshoot).
public static final double kS = 0.284;    // V
public static final double kV = 3.071;    // V/(m/s)
public static final double kA = 0.412;    // V/(m/s^2)
public static final double kG = 2.2763;   // V   (constant gravity, elevator)
public static final double kP = 18.42;    // V/m
public static final double kI = 0.0;      // V/(m*s)   -- intentionally zero, see kS/kG
public static final double kD = 1.07;     // V/(m/s)
public static final double MAX_VELOCITY_MPS = 2.94;        // derived from kV/kA
public static final double MAX_ACCELERATION_MPS2 = 8.11;   // derived from kV/kA
public static final double TOLERANCE_METERS = 0.010;
```

The block is printed to the console, published to `/PumpkinTuner/export/java` (string), and written to `/home/lvuser/pumpkin/Elevator-gains.java.txt`. A student with only a Driver Station can select it out of the console.

**(c) The tuning report.** `GainsExporter.markdownReport()` produces a full session record — every step, every measurement, every accepted and rejected value, every warning, with the final numbers. This is the artifact a student attaches to a pull request, and it is the closest thing to an answer for "the person who understood this graduated":

```markdown
# Elevator tuning session - 2026-08-06 14:12
Recipe: elevator/1   |   Mode: TEACHING   |   PumpkinLib 0.1.0   |   WPILib 2026.2.1
Controller: port 2 (dedicated)   |   Test mode: yes
Sim promotion: PASSED 2026-08-06 13:41
  9/9 perturbed runs contained. Worst case kV x1.0, kA x3.0, zero +15 deg:
  stopped 0.061 m inside the band, 0.111 m from the hard stop.

Predictions: 3 of 4 correct.
  MISSED - step 10 (refine): "I'm about to raise kP from 18.4 to 29.5. What happens?"
           You said "it will get there and stop cleanly, just faster."
           It overshot 18% and rang three times. zeta was 0.31 at those gains.

## 1. Pre-flight            PASS (2 warnings)
- Backlash 0.8 mm (fine, under 2 mm)
- Friction asymmetry 1.9x (WARN: 0.41 V up vs 0.22 V down. Check the carriage rollers.)
- Encoder slip after full sweep: 1.2 mm (fine)

## 2. Gravity pre-pass      kG = 2.2810 V
                            drift probe: falls negative -> gSign +1
                            bracket [0.456, 4.104] V from kGprior 2.28 V
                            10 bisections, converged to +/- 0.0018 V, 4.9 s
                            0 probes aborted on the 3%-of-travel guard

## 3. Find kS               kS = 0.284 V    (0.291 up / 0.277 down, 5% asymmetry)
## 4. Find kV               kV = 3.11 V/(m/s)  provisional, ramp data only
## 5. Find kA               kA = 0.412 V/(m/s^2)
## 6. Confirm kG            kG = 2.2763 V   joint fit; pre-pass agreed to 0.2%
                            kV refined 3.11 -> 3.071
                            Fit quality: R2 0.981, RMSE 0.094 V, 4820 samples -> GOOD
## 7. Profile               EXPONENTIAL from kV/kA: 2.94 m/s, 8.11 m/s^2
## 8/9. LQR suggestion      kP 18.42, kD 1.07  (max error 0.010 m, max effort 4.0 V, delay 0 ms)
## 10. Refinement
   iter 1: kP 18.42 kD 1.07  -> SLUGGISH   (rise 0.81 s vs expected 0.30 s)      -> kP x1.6
   iter 2: kP 29.47 kD 1.07  -> OVERSHOOT_RING (18% overshoot, zeta 0.31)        -> kD x1.5
   iter 3: kP 29.47 kD 1.61  -> GOOD       (rise 0.34 s, 3.1% overshoot, settle 0.51 s)
   ACCEPTED at iteration 3.
## 11. Full-travel verify   up: rise 0.71 s, 2.4% overshoot, settle 1.02 s, error 2 mm
                            down: rise 0.69 s, 3.0% overshoot, settle 0.98 s, error 3 mm
                            Symmetric -> kG is correct.

## Final gains
kS 0.284 | kV 3.071 | kA 0.412 | kG 2.2763 | kP 29.47 | kI 0.0 | kD 1.61
```

### 11.6 The Save flow

Nothing is persisted implicitly. When a recipe reaches `DONE` the wizard shows:

```
  Elevator is tuned.   kS 0.284  kV 3.071  kA 0.412  kG 2.2763  kP 29.47  kD 1.61

  A  Save to the robot        (survives reboot and redeploy)
  Y  Save + write commit file (also writes gains-for-commit.json and the Java block)
  B  Discard                  (revert to the gains this session started with)
```

Saving is also available at any time from the dashboard (`/PumpkinTuner/cmd/save`) so a student who hand-tunes with the sliders and gets it right can keep the result without running a recipe at all. That path alone would have solved the whole problem for 8793.

### 11.7 Rejected alternatives

- **`Preferences` as the primary store.** It persists correctly and it is the documented WPILib answer, but it is a flat key-value namespace shared with everything else on the robot, it carries no provenance, no quality record and no `configHash`, and it is not diffable or reviewable. Its keys also appear in NetworkTables under a path we do not control. We provide `GainStore.mirrorToPreferences(true)` as an opt-in for teams that already build tooling around `Preferences`, and nothing more.
- **Writing gains into `src/main/deploy` at runtime on the robot.** The deploy directory is the deploy task's territory; writing there invites a silent revert on the next deploy and, depending on GradleRIO's file-artifact configuration, possible deletion. Runtime state goes in `/home/lvuser/pumpkin/`.
- **Regenerating `Constants.java` automatically.** Tempting, and wrong: it puts a robot program in the business of rewriting its own source, it fights the team's formatter, and it breaks the reviewability that makes the committed file valuable. We generate a *block to paste* and a *JSON file to commit*, and a human decides.

---

## 12. The UI

### 12.1 The decision

**Primary surface: Elastic, driven by plain NT4 topics, with a shipped layout JSON served from the robot on port 5800.**
**Analysis companion: AdvantageScope, which gets the `/Tuning` table and the plot topics for free.**
**Control surface: a dedicated gamepad on its own port, in Test mode only (§7.4) — not the dashboard, and not the driver's controller.**

Justification, against what a small team can actually run in a pit at 8:40 on a Saturday morning:

| Option | Verdict |
|---|---|
| **Elastic** | Ships with WPILib since 2025 and was used by 1235 teams in matches in 2026. It is already open on the driver-station laptop. Its editable widgets (Text Display, Number Slider, Toggle Button, ComboBox chooser) cover everything the tuner needs to *display and adjust*, and its Graph widget covers the two stacked plots. It survives 2027 (Shuffleboard and SmartDashboard do not). **Chosen.** |
| **AdvantageScope tuning mode** | Excellent for editing `/Tuning` values and for deep plot analysis, and we get it with zero extra work by using the `/Tuning` path. But it is an analysis tool, not a pit tool: it has no concept of a wizard, no buttons, and a team will not have it open on the DS laptop during a match day. **Companion, not primary.** |
| **A custom NT-driven web UI served from the roboRIO** | Genuinely attractive: `edu.wpi.first.net.WebServer.start(int port, String path)` already serves static files, ports in the 5800-5810 range are conventionally open for team use ([UNVERIFIED] against the 2027 game manual — verify at kickoff), and it would let us reproduce WPILib's exact two-stacked-plot tutorial layout. But it needs an NT4 WebSocket client in JavaScript, it is a second UI to maintain, and if it breaks at an event the team has no fallback. **Deferred to v0.2 as an optional richer surface, layered on the same NT schema so the primary path is unaffected if it never ships.** |
| **A desktop app** | Another install, another version to keep in sync, another thing that is the wrong version on the one laptop that matters. **Rejected.** |
| **SmartDashboard / Shuffleboard** | Deleted in 2027. **Rejected outright.** |

The decisive argument is the failure mode. If the shipped Elastic layout is missing, a student can drag four widgets onto a tab and be running in ninety seconds, because everything is a plain NT4 double or string. Every other option has a failure mode that ends with "we can't tune today."

### 12.2 Complete NT topic schema

Everything below is plain NT4. No structs, no protobuf, no AdvantageKit-specific types.

**Editable gains** (§5.5): `/Tuning/<Mechanism>/...`

**Wizard state and narration:**

| Topic | Type | R/W | Meaning |
|---|---|---|---|
| `/PumpkinTuner/version` | string | R | `"PumpkinLib 0.1.0 / WPILib 2026.2.1"` |
| `/PumpkinTuner/mechanisms` | string[] | R | Every registered mechanism name |
| `/PumpkinTuner/selected` | string | RW | Currently selected mechanism |
| `/PumpkinTuner/MechanismChooser` | (chooser) | RW | `SendableChooser<String>`-shaped topics so Elastic's ComboBox binds directly |
| `/PumpkinTuner/state` | string | R | `IDLE`/`READY`/`PREFLIGHT`/`ARMED`/`RUNNING`/`REVIEW`/`ABORTED`/`DONE`/`DISABLED_REPLAY` |
| `/PumpkinTuner/enableHeld` | boolean | R | Mirror of the trigger, so the student can see the robot agrees |
| `/PumpkinTuner/step/index` | double | R | 1-based |
| `/PumpkinTuner/step/count` | double | R | |
| `/PumpkinTuner/step/title` | string | R | `"Step 3 of 11 - Find kS"` |
| `/PumpkinTuner/step/explanation` | string | R | The lesson (§13) |
| `/PumpkinTuner/step/willDo` | string | R | `"I will slowly increase voltage until the carriage starts to move."` |
| `/PumpkinTuner/step/watchFor` | string | R | `"Watch for the exact moment it breaks loose."` |
| `/PumpkinTuner/step/progress` | double | R | 0..1 |
| `/PumpkinTuner/log` | string[] | R | Rolling narration, last 40 lines |
| `/PumpkinTuner/mode` | string | R | `TEACHING` or `EXPRESS` (§8.12) |
| `/PumpkinTuner/cmd/setMode` | string | RW | Student writes `TEACHING`/`EXPRESS`; consumed and cleared in the same loop |
| `/PumpkinTuner/sharedControllerAck` | string | R | The verbatim `acknowledgeSharedController` reason, empty when the wizard has its own port |

**Formative assessment** (§8.4) — four topics, bound to an Elastic ComboBox and a text display. No new widget types:

| Topic | Type | R/W | Meaning |
|---|---|---|---|
| `/PumpkinTuner/predict/question` | string | R | `"I'm about to triple your kP, from 18.4 to 55. What do you think the carriage will do?"` |
| `/PumpkinTuner/predict/options` | string[] | R | Exactly three plain-language outcomes |
| `/PumpkinTuner/predict/answer` | double | RW | Index 0/1/2, written by the ComboBox or by the D-pad |
| `/PumpkinTuner/predict/score` | string | R | `"Predictions: 7 of 9"` |
| `/PumpkinTuner/predict/feedback` | string | R | The branched `Coach` text, populated after the step runs |

**Live plot topics** (the two stacked plots, matching WPILib's tutorial layout):

| Topic | Type | Meaning |
|---|---|---|
| `/PumpkinTuner/plot/setpoint` | double | Profile setpoint, SI |
| `/PumpkinTuner/plot/measurement` | double | Measured position or velocity, SI |
| `/PumpkinTuner/plot/goal` | double | Final goal (flat line), SI |
| `/PumpkinTuner/plot/error` | double | setpoint - measurement |
| `/PumpkinTuner/plot/volts` | double | Total commanded volts |
| `/PumpkinTuner/plot/ffVolts` | double | Feedforward contribution |
| `/PumpkinTuner/plot/fbVolts` | double | Feedback contribution |
| `/PumpkinTuner/plot/velocity` | double | Measured velocity, SI |
| `/PumpkinTuner/plot/amps` | double | Stator current, when available |

Publishing the feedforward and feedback contributions **separately** is deliberate and is one of the highest-value teaching artefacts in the whole design: a student who can see that the feedforward line carries 95% of the voltage and the feedback line only wobbles around zero has *understood* feedforward-before-feedback in a way no paragraph achieves.

**Result and diagnostics:**

| Topic | Type | Meaning |
|---|---|---|
| `/PumpkinTuner/result/gain` | string | `"kS"` |
| `/PumpkinTuner/result/value` | double | Suggested value |
| `/PumpkinTuner/result/previous` | double | What it was |
| `/PumpkinTuner/result/headline` | string | `"kS = 0.284 V"` |
| `/PumpkinTuner/result/quality` | string | `"Fit R2 0.981, RMSE 0.094 V - good"` |
| `/PumpkinTuner/result/verdict` | string | Plain-language diagnosis (§10.4) |
| `/PumpkinTuner/result/recommendation` | string | Plain-language action |
| `/PumpkinTuner/result/warnings` | string[] | |
| `/PumpkinTuner/diagnostics/riseTimeSec` | double | |
| `/PumpkinTuner/diagnostics/overshootPct` | double | |
| `/PumpkinTuner/diagnostics/settleTimeSec` | double | |
| `/PumpkinTuner/diagnostics/steadyStateErrorSi` | double | |
| `/PumpkinTuner/diagnostics/residualVolts` | double | |
| `/PumpkinTuner/diagnostics/dampingRatio` | double | |
| `/PumpkinTuner/diagnostics/oscillationHz` | double | |
| `/PumpkinTuner/diagnostics/classification` | string | `ResponseClass` name |
| `/PumpkinTuner/diagnostics/saturated` | boolean | |

**Safety and identification:**

| Topic | Type | Meaning |
|---|---|---|
| `/PumpkinTuner/safety/tripped` | boolean | |
| `/PumpkinTuner/safety/reason` | string | `AbortReason` name |
| `/PumpkinTuner/safety/message` | string | The student-facing sentence from §7.2 |
| `/PumpkinTuner/safety/envelope` | string | JSON dump of the active `SafetyEnvelope` |
| `/PumpkinTuner/sysid/r2` | double | `voltageFitR2` |
| `/PumpkinTuner/sysid/rmseVolts` | double | |
| `/PumpkinTuner/sysid/samples` | double | |
| `/PumpkinTuner/lastSysIdLog` | string | Path to the plain WPILog written for SysId |

**Secondary (laptop-only) control** — momentary booleans consumed and reset in the same loop:

`/PumpkinTuner/cmd/{start, accept, retry, back, skip, abort, save, saveAndExport, preflight}`

**Export:**

`/PumpkinTuner/export/java` (string), `/PumpkinTuner/export/json` (string), `/PumpkinTuner/export/report` (string).

### 12.3 The shipped Elastic layout

`src/main/deploy/elastic-tuning-layout.json`, served by `WebServer.start(5800, Filesystem.getDeployDirectory().getPath())` exactly as the user's template already does for its driver layout. One tab, `PumpkinTuner`, laid out in a 2-column grid:

| Widget | Type | Bound to |
|---|---|---|
| Mechanism | ComboBox Chooser | `/PumpkinTuner/MechanismChooser` |
| State | Large Text Display | `/PumpkinTuner/state` |
| Step | Large Text Display | `/PumpkinTuner/step/title` |
| Progress | Number Bar (0-1) | `/PumpkinTuner/step/progress` |
| **What this step teaches** | Large Text Display (tall) | `/PumpkinTuner/step/explanation` |
| **What the robot will do** | Large Text Display | `/PumpkinTuner/step/willDo` |
| Enable held | Boolean Box | `/PumpkinTuner/enableHeld` |
| Safety | Boolean Box + Large Text Display | `/PumpkinTuner/safety/tripped`, `/safety/message` |
| **Setpoint vs measured** | Graph (2 series) | `/PumpkinTuner/plot/setpoint`, `/plot/measurement` |
| **Commanded volts (FF vs FB)** | Graph (3 series) | `/plot/volts`, `/plot/ffVolts`, `/plot/fbVolts` |
| Result | Large Text Display | `/PumpkinTuner/result/headline` |
| Diagnosis | Large Text Display (tall) | `/PumpkinTuner/result/verdict` |
| Recommendation | Large Text Display (tall) | `/PumpkinTuner/result/recommendation` |
| **Predict: the question** | Large Text Display (tall) | `/PumpkinTuner/predict/question` |
| **Predict: your answer** | ComboBox | `/PumpkinTuner/predict/options` → `/PumpkinTuner/predict/answer` |
| **Predict: how you did** | Large Text Display (tall) | `/PumpkinTuner/predict/feedback` |
| Prediction score | Text Display | `/PumpkinTuner/predict/score` |
| Gains | 7x Text Display (editable) | `/Tuning/<Mechanism>/{kS,kV,kA,kG,kP,kI,kD}` |
| Integrator (collapsed by default) | 2x Text Display (editable) | `/Tuning/<Mechanism>/{iZone,iMaxVolts}` |
| Max acceptable error | Number Slider | `/PumpkinTuner/<Mechanism>/lqr/maxError` |
| Max control effort | Number Slider | `/PumpkinTuner/<Mechanism>/lqr/maxVolts` |
| Mode | ComboBox | `/PumpkinTuner/mode` → `/PumpkinTuner/cmd/setMode` |
| Alerts | Alerts widget | `Alerts` group |

The two stacked graphs are placed adjacently and sized identically **on purpose**: they reproduce the layout of WPILib's own browser tuning tutorials (`{prefix}_plotVals` above `{prefix}_plotVolts`). A student who learned the shape of a good response in the browser sees literally the same shape on their elevator. That continuity is worth more than any feature in this section.

Because the gain widgets bind to `/Tuning/<Mechanism>/...` and the mechanism name is part of the path, the layout is generated per-mechanism at deploy time by `ElasticLayoutGenerator.write(Path)` from the registry, rather than hand-authored. A team adding a mechanism regenerates rather than dragging widgets.

### 12.4 AdvantageScope, for free

No work required. Because gains live at `/Tuning/<Mechanism>/<gain>` as plain NT4 doubles, AdvantageScope's tuning mode (slider icon right of the search bar; purple when active) shows them under the Tuning table and edits them live, and all the `/PumpkinTuner/plot/*` topics graph directly. Teams that prefer AdvantageScope get the full experience minus the wizard narration, with no extra configuration and no AdvantageKit dependency.

### 12.5 What the student actually does — the whole loop

1. Put the robot on blocks or clear the space. Open Elastic. **Enable in Test mode** — this is not a suggestion, it is precondition 7 in §7.3, and `arm()` throws without it. In teleop the wizard will show you every screen and refuse to move.
2. Pick up the **tuning controller** — port 2, not the driver's. If your team only owns one controller, somebody had to write `acknowledgeSharedController("why")` in `RobotContainer` and the reason is on the dashboard right now.
3. Pick `Elevator` from the ComboBox.
4. Read the four sentences in the "What this step teaches" box.
5. Answer the prediction question, if the step has one. It is not a test and nothing is blocked by getting it wrong.
6. Hold the right trigger. Watch the mechanism. Watch the two graphs.
7. Read the result, the plain-language verdict, and whether your prediction was right.
8. Press A to accept, B to retry, Y to skip.
9. Repeat for eleven steps, about eight minutes the first time and about ninety seconds in express mode after that.
10. Press A to save.

No laptop file transfer. No redeploy. No SysId GUI. No retyping numbers.

---

## 13. The teaching content

This is shipped content, not a placeholder. It lives in `org.pumpkinlib.tuning.wizard.Lessons` as `public static final String` constants, is published to `/PumpkinTuner/step/explanation`, and is rendered verbatim in the docs site so the docs and the robot can never disagree. Every string below is the actual text.

Writing rules the content follows, and which CI enforces: no equations in the body, no Greek letters, no jargon that has not been defined in an earlier lesson, every gain gets a concrete FRC-scale example number, and every lesson names the *symptom* a student will see when the gain is wrong.

### 13.1 `Lessons.KS`

> **kS — the stiction tax**
>
> Every mechanism has friction. Before anything moves at all, the motor has to push hard enough to break it loose — like sliding a heavy box across a floor: you push, nothing happens, you push harder, nothing happens, and then suddenly it goes.
>
> kS is how many volts it takes to get to "suddenly." It is measured in volts, and the controller adds it in whichever direction you are trying to move.
>
> On a typical FRC mechanism kS is somewhere between 0.1 V and 0.8 V. A big number means a lot of friction — worth checking your belt tension before you accept it.
>
> **If kS is too small:** small moves never start. The mechanism sits there humming until the error gets big enough.
> **If kS is too big:** the mechanism twitches and buzzes when it should be sitting perfectly still, because it is being pushed one way, then the other, forever.
>
> *In this step, PumpkinLib will slowly increase the voltage from zero until it sees the mechanism move, in both directions, and average the two answers.*

### 13.2 `Lessons.KV`

> **kV — the price of speed**
>
> A spinning motor pushes back. The faster it spins, the more it fights you — that is the same effect that makes a motor work as a generator. So to hold a steady speed, you have to keep paying voltage the whole time.
>
> kV is the price. It is measured in volts per unit of speed: volts per metre-per-second for something that slides, volts per radian-per-second for something that turns. Want to go twice as fast? Pay twice as much.
>
> kV is the single most important number in this whole process. Get kV right and your mechanism almost controls itself — the controller can predict, before it even starts moving, exactly how much voltage this move is going to need.
>
> **If kV is too small:** the mechanism always runs slower than you asked, and the feedback term has to keep making up the difference.
> **If kV is too big:** it overshoots your commanded speed and the feedback has to fight it back down.
>
> *In this step, PumpkinLib will ramp the voltage up very slowly and watch how fast the mechanism goes at each voltage. Slowly, on purpose: if it ramped quickly, some of the voltage would be going into speeding up rather than into holding speed, and we would not be able to tell the two apart.*

### 13.3 `Lessons.KA`

> **kA — the price of getting moving**
>
> Things with mass do not change speed instantly. Pushing an empty shopping trolley up to walking pace is easy; pushing a full one up to the same pace takes more effort, even though once they are both rolling they need about the same push to keep going.
>
> kA is that extra effort: how many extra volts it takes to change speed by one unit every second. A heavy elevator carriage has a big kA. A little turret has a tiny one.
>
> kA is the hardest number to measure well, because it is only visible during the brief moment when the mechanism is actually speeding up or slowing down. Once it is cruising, kA contributes nothing. That is why this step uses a sudden step of voltage rather than a slow ramp — we need to catch the mechanism in the act of accelerating.
>
> **If kA is wrong:** the mechanism lags at the start of every move and overshoots at the end of it, in a way that gets worse the faster you ask it to go.
>
> *Do not be alarmed by this step. It will look and sound more violent than the others. PumpkinLib has worked out a step size that uses less than half your remaining travel and will stop it after a second and a half.*

### 13.4 `Lessons.KG`

> **kG — the holding tax**
>
> Gravity never turns off. Stop pushing on an elevator carriage and it falls. Stop pushing on an arm and it swings down.
>
> kG is exactly how many volts it takes to hold still against gravity and do nothing else. It is measured in volts.
>
> On an elevator, kG is the same everywhere: the carriage weighs the same at the bottom and at the top. On an arm, it depends on the angle. It is biggest when the arm sticks straight out sideways, and it drops to zero when the arm points straight up or straight down — which is why the maths multiplies kG by the cosine of the angle. That is also why your arm's zero angle has to be *horizontal*: if the code thinks zero is somewhere else, the cosine is wrong at every single angle.
>
> **If kG is too small:** the mechanism settles a little low, every time, and the controller sits there holding a steady voltage trying to lift it.
> **If kG is too big:** it settles a little high, or creeps upward when you leave it alone.
> **If kG is right but the arm's zero is wrong:** it droops on one side of its travel and creeps up on the other. That asymmetry is the fingerprint.
>
> *WPILib's own arm guide says you have to get kG right to about four decimal places. That is why PumpkinLib does not ask you to guess and redeploy. In this step it will first let go for half a second to see which way your mechanism falls, then hold it and narrow in on the exact holding voltage by cutting the range in half ten times — about two thousandths of a volt, in five seconds. It never lets the mechanism drift more than a thirtieth of its travel while it does this.*
>
> *Ten halvings, not more, on purpose. The range it starts from comes from your mechanism's own mass and gearing, so it is already close, and going further would be measuring a number more precisely than the encoder can actually see it — while giving the mechanism more chances to run into something.*

### 13.5 `Lessons.P`

> **P — the software spring**
>
> Everything up to now has been the controller *predicting* what voltage a move needs. P is the first term that *reacts* to what actually happened.
>
> P is a spring made of software. The further you are from where you want to be, the harder it pulls you back. Twice the error, twice the push. That is all it does.
>
> Its unit is volts per unit of error — volts per metre for an elevator, volts per radian for an arm. That is also why a kP that is right for an elevator looks nothing like a kP that is right for a turret, and why a kP copied off the internet is almost never right for your robot.
>
> **If kP is too small:** the mechanism is sluggish. It gets there eventually, or stops slightly short and stays there.
> **If kP is too big:** it overshoots and bounces, exactly like a spring that is too stiff. Turn it up further and the bouncing never stops. Turn it up further still and the bouncing gets *bigger* each time, and that is how mechanisms break.
>
> *PumpkinLib does not ask you to guess kP. It already measured kV and kA, which together describe how your mechanism responds to voltage — so it can calculate a starting kP from two questions that actually mean something: how much error can you live with, and how many volts are you willing to spend fixing it. Smaller error, or more volts, gives a bigger kP.*

### 13.5a `Lessons.WHAT_THE_SLIDERS_DO`

Shown in the `LqrSuggestStep` panel, directly under the two sliders (§9.2.2). It exists because the kP step is the one place the wizard was in danger of handing over a number from an oracle, and a number with no story behind it teaches nothing.

> **The two sliders — what you are actually choosing**
>
> PumpkinLib is not guessing your kP. It already measured how your mechanism responds to voltage, so there is a real answer — but the answer depends on what *you* want, and these two sliders are how you say it.
>
> **"How much error can I live with"** is how fussy you are. Tell it a millimetre and it will fight hard for that millimetre. Tell it a centimetre and it will relax.
>
> **"How many volts may I spend"** is how much muscle it is allowed to use getting there. More volts, more push.
>
> Tighten the error or raise the volts, and kP goes up. Both knobs push the same way, and that is not a coincidence: caring more and being allowed to push harder are the same instruction to a controller.
>
> Now watch the two numbers above the gains, because they are what kP actually *means*:
>
> **The bounce rate** is how fast this mechanism would wobble if you knocked it off target. It goes up with the square root of kP — so tripling kP does not make your elevator three times faster, it makes it about 1.7 times faster.
>
> **The damping** is how quickly that wobble dies away. Above about 0.7 you will not see it bounce at all. Below 0.7 you will. Here is the part that catches everyone: raising kP makes the bounce faster *and* the damping worse, at the same time, from the same slider. That is why turning kP up forever does not work, and why the next number you tune is kD.
>
> *Your mechanism's own kV counts as damping too, for free, before kD does anything. That is why a shooter wheel usually needs no kD at all and an elevator usually does.*

### 13.6 `Lessons.D`

> **D — the shock absorber**
>
> D is the damper that goes with P's spring. Think of the arm on a door that stops it slamming.
>
> D does not care where you are. It cares how fast the error is shrinking. If you are rushing at the target too fast, D pushes back and slows you down so that you *arrive* instead of crashing through. That is why P and D go together: P gets you there, D stops the bounce.
>
> **If kD is too small:** you get the overshoot-and-ring behaviour from the P lesson.
> **If kD is too big:** the mechanism gets jittery and buzzy, often at a frequency far too fast for the mechanism to actually be moving that quickly. That is D amplifying the noise in your encoder reading and feeding it straight back into the motor.
>
> *If PumpkinLib tells you it is halving kD because it saw a 14 Hz buzz, that is what happened.*

### 13.7 `Lessons.I` — and why we make it hard to use

> **I — the grudge, and why you almost certainly do not want it**
>
> I keeps a running total of every bit of error you have ever had, and pushes harder the longer you have been wrong. It sounds like exactly what you want when your mechanism stops just short of its target. It is almost always the wrong tool in FRC, and WPILib says so directly: *integral gain is generally not recommended for FRC use.*
>
> Here is why. If your mechanism stops short and stays there, something is pushing against it that nothing in your feedforward is pushing back on. Ninety-nine times out of a hundred that something is friction (fix: raise kS) or gravity (fix: raise kG). Adding I does not remove the force — it just piles up error until it produces enough voltage to cancel it, every single time, from scratch.
>
> And it has a nasty failure mode called windup. While your mechanism is blocked — jammed, or at a hard stop, or waiting for something else to move out of the way — the total keeps growing. When it is finally free, all of that stored-up push comes out at once. That is how arms slam.
>
> PumpkinLib will let you use I. It just will not let you use it carelessly: you have to supply an I-zone (the error band outside which the integrator is switched off) and a voltage cap, in the same breath as kI. There is no plain `withI(kI)`.
>
> *Before you reach for I, look at the number PumpkinLib shows you called "residual volts." That is exactly how much voltage the feedback term is holding, right now, to keep the mechanism where it is. That voltage is the feedforward term you are missing. Add it to kS or kG instead.*

### 13.8 `Lessons.MOTION_PROFILES`

> **Motion profiles — a plan instead of a wish**
>
> A setpoint is a wish. If your elevator is at the bottom and you tell it "be at 1.5 metres," you have asked it to teleport. The error is instantly 1.5 metres, so the P term instantly asks for forty volts you do not have, the motor saturates, and every gain you tuned stops mattering because the controller is just holding the throttle wide open.
>
> A motion profile is a plan. Instead of one impossible target, it hands the controller a new, *reachable* target every twenty milliseconds: a smooth path from where you are to where you want to be, with a speed limit and an acceleration limit that your mechanism can actually meet.
>
> Two things change once you have one. First, the error is never large, so P and D never saturate. Second — and this is the important one — the profile knows what speed you *should* be going at this exact instant, which is precisely what kV and kA need in order to predict the voltage. The feedforward can now do almost all of the work, and P and D are left cleaning up a small difference.
>
> That is the whole reason we tune feedforward first. With a good profile and good kS, kV, kA and kG, your P gain has very little left to do — and a P gain with very little to do is a P gain that cannot shake your robot apart.
>
> **You do not have to guess the speed limit and the acceleration limit.** That is the single most common place students put in a physically impossible number. PumpkinLib builds the profile straight from the kV and kA it just measured, using WPILib's `ExponentialProfile.Constraints.fromCharacteristics(...)`, so the limits are, by construction, exactly what your mechanism can do.

### 13.9 `Lessons.WHY_FEEDFORWARD_FIRST`

> **Why we do it in this order**
>
> Feedforward is the controller's *prediction*: given where you want to go and how fast, here is the voltage that should do it. Feedback is the controller's *correction*: here is a bit extra, because reality did not match the prediction.
>
> If you tune feedback first, you are asking the correction to do the prediction's job. It can — a big enough kP will drag almost anything to almost anywhere — but it does it by being wrong first and then reacting, which is exactly what overshoot and ringing are. And the bigger you make kP to compensate, the closer you get to the point where the mechanism shakes itself apart.
>
> So: kS, then kV, then kA, then kG, then kP, then kD. Prediction first, correction second. Every time.
>
> *(One wrinkle for elevators and arms: we measure gravity before we measure friction. You cannot see friction break loose while the mechanism is falling. So PumpkinLib does a quick gravity pass first, uses it to cancel gravity during the friction and speed measurements, and then re-solves gravity properly at the end using all the data at once. The order you learn the numbers in is still the order above.)*

### 13.10 `Lessons.WHY_UNITS_MATTER`

> **Why your kP is not the same as somebody else's kP**
>
> This trips up everyone, so it is worth thirty seconds.
>
> The number "kP = 50" means nothing on its own. It means volts per *something*, and every system measures that something differently:
>
> - PumpkinLib and WPILib measure error in metres or radians, and output in volts.
> - A Kraken running its own loop measures error in *motor-shaft rotations*, and its output might be volts, or a duty cycle, or amps, depending on which kind of request you send it.
> - A SPARK MAX measures error in rotations and outputs a duty cycle from -1 to 1.
>
> That is why the published starting kP for the *same swerve steer motor* is 0.01 on a SPARK MAX and 50 on a TalonFX. Same mechanism, same behaviour, numbers five thousand times apart.
>
> PumpkinLib fixes this by having exactly one unit system — volts per SI unit — and converting once, at the boundary, inside the code that talks to your motor controller. Every number you see, save, and paste into `Constants.java` is in those units. The number you learn from the WPILib arm tutorial transfers unchanged to your Kraken and to your NEO.
>
> *You can see the exact conversion PumpkinLib is doing for your mechanism in the "Conversion" line on the dashboard, and in the log at boot.*

### 13.11 `Lessons.PRACTICE_MODE`

> **Learn this without a robot**
>
> Everything in this wizard runs in simulation. Type `./gradlew simulateJava`, pick a mechanism, and tune it exactly the same way, with the same plots and the same steps — except that a simulated elevator does not have a real ceiling to hit and a simulated arm does not have real fingers near it.
>
> WPILib publishes reference answers for its own simulated mechanisms, and PumpkinLib ships those same plants as practice targets:
>
> | Practice mechanism | The answer you are looking for |
> |---|---|
> | Flywheel | kV 0.0075, kP 0.1 |
> | Turret | kV 0.15, kP 0.3, kD 0.05 |
> | Vertical arm | kG 1.75, kV 1.95, kP 5, kD 1 |
> | Elevator | kG 2.28, kV 3.07, kA 0.41, kP 2.0 |
>
> Tune them until you can predict what the plot is going to do before it does it. Then turn the noise on (`practice.withNoise(true)`) and do it again — a real encoder is never as clean as a simulated one.
>
> **The wizard keeps score.** Before each of the important steps it asks you what you think is going to happen, in plain language, three options, no trick answers. It is not a test and getting it wrong blocks nothing — but at the end it tells you how many you called correctly, and so does the report you attach to your pull request. That number is the actual point of this whole practice mode. Aim for all of them on the same mechanism twice in a row before you touch the real robot.
>
> Once you can do that, tuning the real robot takes about eight minutes the first time and ninety seconds every time after that.

### 13.12 How the lessons are validated

Documentation rot is a fast abandonment trigger, and an AI-written feedforward page that said *sin* where it should have said *cos* cost one competing library a reviewer's trust in a single forum post. So:

- Every lesson string is referenced by a JUnit test that asserts it is non-empty, under 1800 characters, and contains none of the forbidden jargon tokens (`Laplace`, `pole`, `eigen`, `transfer function`, `s-domain`, `Nyquist`, unescaped Greek letters). The limit was 1400 and moved to 1800 when `WHAT_THE_SLIDERS_DO` was added; it is a budget, not a physical law, and a lesson that needs more words than that is a lesson that needs splitting.
- Every *numeric claim* in a lesson is asserted by a test against the code that produces it, so a constant cannot change without the prose failing the build. The current set:

  | Claim | Asserted against |
  |---|---|
  | The four reference-gain rows in §13.11 | The shipped practice plants |
  | "cutting the range in half **ten** times — about **two thousandths of a volt**, in **five seconds**" (§13.4) | `HoldBisectionStep.ITERATIONS`, the `[0.2x, 1.8x] * kGprior` bracket width, `PROBE_SECONDS`, `SETTLE_SECONDS` |
  | "never lets the mechanism drift more than a **thirtieth** of its travel" (§13.4) | The `0.03 * range` in-window guard |
  | "bounce rate goes up with the **square root** of kP — tripling kP makes it about **1.7 times** faster" (§13.5a) | `wn = sqrt(kP/kA)`; `sqrt(3) = 1.732` |
  | "raising kP makes the bounce faster *and* the damping worse" (§13.5a) | `zeta = (kD + kV) / (2*sqrt(kP*kA))`, monotone decreasing in kP |
  | "above about **0.7** you will not see it bounce" (§13.5a, §9.2.2, `PredictStep`) | The 0.7 threshold in `PredictStep`'s answer key and the `OVERSHOOT_RING` classifier |
  | The `ln(9) * tau` rise-time claim (§10.2) | `StepResponseAnalyzer.expectedRise` |

  A single `LessonsNumericClaimTest` owns this table. When `HoldBisectionStep.ITERATIONS` went from 18 to 10, this test is what was supposed to catch the three places the prose still said "eighteen." It exists because it did not.
- The docs site renders these constants directly from the source file. There is no second copy of this text.
- The physics statements (cosine for arms, constant for elevators, kV as back-EMF, kA as inertia) are each cross-checked against the WPILib feedforward documentation in a review checklist that is part of the release process, not part of anyone's memory.

---

## 14. End-to-end example

Everything below is the *complete* code a team writes. This is a real elevator on a Kraken with the loop running on the motor controller.

### 14.1 The mechanism (written once, by the mechanism domain or by hand)

```java
package frc.robot.subsystems;

import edu.wpi.first.wpilibj2.command.SubsystemBase;
import org.pumpkinlib.control.Gains;
import org.pumpkinlib.mechanism.LinearMechanism;   // domain 03

public class Elevator extends SubsystemBase {

  private final LinearMechanism m_mechanism =
      LinearMechanism.builder("Elevator")
          .talonFX(15, "rio").follower(16, /* opposeMaster= */ true)
          .gearing(45.0)                                   // motor rotations per drum rotation
          .drumCircumference(edu.wpi.first.units.Units.Inches.of(5.5)).stages(2)
          .mass(edu.wpi.first.units.Units.Kilograms.of(6.0))
          .travel(edu.wpi.first.units.Units.Meters.of(0.0), edu.wpi.first.units.Units.Meters.of(1.60))
          .softMargin(edu.wpi.first.units.Units.Meters.of(0.05))   // >= 2% of 1.60 m; 0.03 would throw
          .statorCurrentLimit(edu.wpi.first.units.Units.Amps.of(60))
          .gains(Gains.zero()                              // <- placeholders; the wizard fills these in
              .withFeedforward(0.0, 0.0, 0.0)
              .withGravity(0.0, Gains.GravityType.ELEVATOR_STATIC)
              .withPD(0.0, 0.0)
              .withTolerance(0.010))
          .build();

  public Elevator() {
    org.pumpkinlib.tuning.TuningRegistry.register(m_mechanism.tuningTarget());   // <- ONE LINE
  }

  public void goTo(double metres) { m_mechanism.setGoal(metres); }
  public double height() { return m_mechanism.position(); }

  @Override public void periodic() { m_mechanism.periodic(); }
}
```

The only tuning-related line is `TuningRegistry.register(...)`. Note that the gains are literally zero: the team never types a guessed gain, and the wizard's persistence layer (§11.3) fills them in from `src/main/deploy/pumpkin/gains.json` on the very next boot after the first tuning session.

Note also the soft margin. `TravelLimits` (§3.1) **rejects** anything under 2% of travel, which on this 1.60 m elevator is 0.032 m — so the 0.03 m an earlier draft of this example used would have thrown at construction with a message naming the reason. That is deliberate: the supervisor's abort band has to fit somewhere, and a margin that leaves it no room produces a supervisor that only appears to protect you (§7.1). The mechanism builder surfaces the same throw at `build()` time, in simulation, before any hardware exists.

### 14.2 Robot wiring (written once, for the whole robot)

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.pumpkinlib.tuning.TuningRegistry;
import org.pumpkinlib.tuning.wizard.TuningWizard;

public class RobotContainer {

  private final CommandXboxController m_driver = new CommandXboxController(0);
  private final CommandXboxController m_operator = new CommandXboxController(1);

  /**
   * Port 2, on purpose. The wizard's right trigger authorizes raw voltage to an arm; the driver's
   * right trigger shoots. Those must not be the same physical control. See section 7.4.2.
   */
  private final CommandXboxController m_tuningController = new CommandXboxController(2);

  private final Elevator m_elevator = new Elevator();
  private final Arm m_arm = new Arm();
  private final Shooter m_shooter = new Shooter();

  private final TuningWizard m_tuner = TuningWizard.using(m_tuningController);

  public RobotContainer() {
    // nothing else; each subsystem registered itself
  }

  public void robotPeriodic() {
    TuningRegistry.periodic();    // must run before subsystem periodic
    m_tuner.periodic();           // one branch when idle; cannot command volts outside Test mode
  }
}
```

**That is the entire integration.** Four lines across the whole robot: one `register` per mechanism, one `TuningWizard.using`, and two calls in `robotPeriodic`.

**Two things about `m_tuner.periodic()` in `robotPeriodic()`.** It is called unconditionally, from the same place `DESIGN.md` §10.3 line 889 calls it, and that is safe — but not for the reason the first draft of this design claimed. It is safe because `TuningSupervisor.arm()` throws unless `DriverStation.isTest()` (§7.3 precondition 7). During teleop the wizard's state machine still runs and still publishes narration, and every button on port 2 does exactly nothing to a motor. The FMS hard-return is a second, weaker belt: it stops the wizard from *displaying* during a match, but the property that makes a driver's trigger-pull harmless on a practice field is the mode check, not the FMS check.

**If your team only owns two controllers,** say so out loud and take the friction:

```java
private final TuningWizard m_tuner =
    TuningWizard.using(m_operator).acknowledgeSharedController(
        "Only two controllers at this event; wizard is Test-mode only and the operator is briefed");
```

That string is logged verbatim into every tuning report and shown as a persistent warning alert for the rest of the session. Without it, `TuningWizard.using` sees port 1 in `ControlMap`, raises a `kError` alert naming the conflict, and refuses to leave `IDLE`.

### 14.3 What the team then does

```
$ ./gradlew simulateJava
  # Elastic opens. Pick "Elevator". Hold RT. Eleven steps, about two minutes.
  # Sim promotion recorded. Gains written to src/main/deploy/pumpkin/gains.json.

$ git diff
  src/main/deploy/pumpkin/gains.json | 14 +++++++-------

$ ./gradlew deploy
  # On the real robot: Test mode, pick "Elevator", hold RT.
  # Same eleven steps. Same plots. Real numbers this time.
  # Press A to save -> /home/lvuser/pumpkin/gains.json
```

Total team code written: **four lines.** Total redeploys required to tune: **zero.** Total numbers retyped from a laptop: **zero.**

### 14.4 The same thing without the wizard

A team that just wants live sliders, or that has hand-rolled subsystems and does not want `LinearMechanism`, writes an adapter and gets tunability and the diagnostics for free:

```java
public class Shooter extends SubsystemBase implements org.pumpkinlib.tuning.TuningTarget {

  private final TalonFX m_motor = new TalonFX(25);
  private Gains m_gains = Gains.zero().withFeedforward(0.15, 0.121, 0.004).withPD(0.30, 0.0);
  private final TunableGains m_tunable;

  public Shooter() {
    m_tunable = org.pumpkinlib.tuning.TuningRegistry.gains(this);
    org.pumpkinlib.tuning.TuningRegistry.register(this);
  }

  @Override public String name() { return "Shooter"; }
  @Override public MechanismArchetype archetype() { return MechanismArchetype.FLYWHEEL; }
  @Override public MechanismUnits units() { return MechanismUnits.ROTATIONAL_RADIANS; }
  @Override public void setVoltage(double v) { m_motor.setControl(new VoltageOut(v)); }
  @Override public void stop() { m_motor.setControl(new NeutralOut()); }
  @Override public double getPosition() { return m_motor.getPosition().getValueAsDouble() * 2 * Math.PI; }
  @Override public double getVelocity() { return m_motor.getVelocity().getValueAsDouble() * 2 * Math.PI; }
  @Override public double getAcceleration() { return m_motor.getAcceleration().getValueAsDouble() * 2 * Math.PI; }
  @Override public OptionalDouble getAppliedVolts() { return OptionalDouble.of(m_motor.getMotorVoltage().getValueAsDouble()); }
  @Override public OptionalDouble getStatorCurrentAmps() { return OptionalDouble.of(m_motor.getStatorCurrent().getValueAsDouble()); }

  // A flywheel has no meaningful absolute position, so RotorOnly is correct and isHomed()'s
  // default (true for FLYWHEEL / DRIVE_VELOCITY) is already right. A POSITION archetype could not
  // get away with either of these lines - see section 7.3.1.
  @Override public FeedbackSpec feedbackSpec() { return new FeedbackSpec.RotorOnly(); }

  // Phoenix 6 reports the split directly, so the wizard can tell "raise kS" from "raise kP".
  // Only valid for voltage-output requests; setClosedLoopGoal below uses VelocityVoltage.
  @Override public OptionalDouble getFeedbackVolts() {
    return OptionalDouble.of(
        m_motor.getClosedLoopOutput().getValueAsDouble()
      - m_motor.getClosedLoopFeedForward().getValueAsDouble());
  }

  @Override public TravelLimits limits() { return TravelLimits.unbounded(); }
  @Override public PlantPrior plantPrior() {
    return new PlantPrior(DCMotor.getKrakenX60Foc(2), 1.0, Double.NaN, 0.0021, Double.NaN, 12.0);
  }
  @Override public Gains getGains() { return m_gains; }
  @Override public void applyGains(Gains g) {
    m_gains = g;
    Phoenix6GainSink.of(m_motor, /* siUnitsPerRotation= */ 2 * Math.PI).apply(g);
  }
  @Override public LoopLocation loopLocation() { return LoopLocation.ON_MOTOR; }
  @Override public boolean supportsClosedLoop() { return true; }
  @Override public void setClosedLoopGoal(double radPerSec) {
    m_motor.setControl(new VelocityVoltage(radPerSec / (2 * Math.PI)));
  }
  @Override public Optional<Subsystem> requirement() { return Optional.of(this); }
}
```

About forty lines, all of them things the subsystem already knew. Two of those lines are new obligations from the safety hardening in §7.3 and §9.3, and they are worth naming: `feedbackSpec()` is **not** defaulted, because a mechanism that cannot say how it knows where it is must be forced to think about that at compile time rather than discover it when an arm swings; and `getFeedbackVolts()` *is* defaulted to empty, because guessing at that number is worse than not having it.

This is the on-ramp for a team that wants tuning without adopting PumpkinLib's mechanism layer — and it is deliberately the *first* example in the docs, not a footnote, because burying the escape hatch is exactly the mistake that cost a competing library its users.

---

## 15. What we deliberately do NOT do

Every line here names the existing tool that already does the job, because "we integrate best-in-class tools, we do not reimplement them" is the project's stance and this domain is the one most tempted to violate it.

| We do not build | Already done by | Our relationship to it |
|---|---|---|
| A PID controller | WPILib `PIDController`, `ProfiledPIDController`; Phoenix 6 `Slot0Configs`; REVLib `ClosedLoopConfig` | We compute the numbers that go in them. `Controllers.pid(...)` is a two-line factory, not a controller. |
| Feedforward maths | WPILib `SimpleMotorFeedforward`, `ElevatorFeedforward`, `ArmFeedforward` | We call `calculateWithVelocities(...)` and mutate gains via the existing `setKs/setKv/setKa/setKg` setters. |
| Motion profile generation | WPILib `TrapezoidProfile`, `ExponentialProfile` | We call `ExponentialProfile.Constraints.fromCharacteristics(maxInput, kV, kA)`. That one call is the whole "you never guess a max velocity again" feature. |
| An LQR solver | WPILib `LinearQuadraticRegulator` (+ `latencyCompensate`) | We construct it from `LinearSystemId.identifyPositionSystem/identifyVelocitySystem` and read `getK()`. Twelve lines total. |
| A least-squares decomposition | WPILib `Matrix.solveFullPivHouseholderQr` (EJML underneath) | We accumulate normal equations in `double[][]` and hand a 3x3 or 4x4 to WPILib to solve. |
| The quasistatic/dynamic characterization motion | WPILib `SysIdRoutine` + `SysIdRoutineLog` | We generate the two callbacks and derive a safe config, then run WPILib's own commands. §6.1. |
| The SysId analysis GUI | WPILib SysId | Fully supported as an escape hatch. We write a clean single-routine WPILog so it works first try. We just do not *require* the laptop. |
| A plotting application | AdvantageScope | We publish `/PumpkinTuner/plot/*` as plain NT doubles and get graphing, tuning mode, and log analysis for free. |
| A dashboard | Elastic | We ship a generated layout JSON and use only widgets Elastic already has. |
| A logging framework | AdvantageKit, WPILib `DataLogManager` + Epilogue, CTRE `SignalLogger` | We publish; we never own the logger. Our types are Epilogue-friendly and our tunables can ride AdvantageKit's `LoggedNetworkNumber` when it is present. |
| Deterministic log replay | AdvantageKit | We are replay-*safe* (§5.6) and we consume replayed data for offline refits. We do not implement replay. |
| Physics simulation models | WPILib `ElevatorSim`, `SingleJointedArmSim`, `FlywheelSim`, `DCMotorSim`, `BatterySim`; maple-sim for the field | The sim-first gate runs the recipe against whatever the simulation domain provides. |
| A vendor configuration tool | CTRE Phoenix Tuner X, REV Hardware Client | We never touch firmware, device IDs, or CAN configuration. We write gain slots only, through `GainSink`. |
| Swerve module bring-up (inverts, offsets, direction discovery) | The PumpkinLib bring-up domain; CTRE Tuner X Swerve Generator; YAGSL | We *check* that bring-up was done (§7.5, §8.11) and refuse to tune a wrong-signed mechanism. We do not do the bring-up. |
| A declarative state machine for the wizard | WPILib 2027 Commands v3 ships one | Our wizard is a plain enum-driven loop with no `Command` inheritance, so it will port onto v3 without being a competing framework. |
| Relay (Astrom-Hagglund) autotune | Nobody in FRC ships it, and the community says hand tuning beats it | Deliberately rejected on safety and pedagogy grounds. §9.1. |
| Ziegler-Nichols tuning | Same | Same. It requires driving the loop to sustained oscillation, which is unacceptable on a geared arm. |
| An NT client library | WPILib `ntcore` | We use `DoubleEntry`, `StringPublisher`, `BooleanEntry`. Nothing custom on the wire. |
| A web server | WPILib `edu.wpi.first.net.WebServer` | If the optional v0.2 web UI ships, it is static files served by WPILib's server. |
| Interactive PID teaching simulators | WPILib's four browser tuning tutorials | We do not build a new PID explainer. We build the **bridge**: the same two stacked plots, on the student's actual robot, and a practice mode that ships WPILib's own reference plants (§13.11). |

---

## 16. Testing plan

The single most damaging failure mode for a library like this is a doc example that does not compile or a physics claim that is wrong — one wrong `sin`/`cos` destroyed a competing library's credibility in a single forum post. So the test suite is part of the deliverable, not an afterthought.

### 16.1 Pure-math tests (no HAL, run in CI, milliseconds)

| Test | Asserts |
|---|---|
| `FeedforwardRegressionTest` | Feed synthetic data generated from known kS/kV/kA/kG through `add(...)`; assert recovery to within 0.5%. One case per archetype. Includes a noise case (sigma = 0.05 V) asserting recovery within 3% and R2 > 0.95. |
| `FeedforwardRegressionRankTest` | A pure-ramp dataset with no acceleration content throws `RANK_DEFICIENT` naming the `a` column. |
| `FeedforwardRegressionQualityTest` | The closed-form `SSE = yty - 2 b.Xty + b.XtX.b` identity matches a brute-force residual sum over stored samples, to 1e-9. |
| `FeedbackDesignerTest` | For a known kV/kA, halving `maxAcceptableError` increases kP; doubling `maxControlEffort` increases kP; `latencyCompensate` with a positive delay reduces kP. Monotonicity, not exact values, because the exact Q/R construction is [UNVERIFIED] against SysId. |
| `StepResponseAnalyzerTest` | Synthetic second-order responses at zeta = 0.05 / 0.3 / 0.7 / 1.2 classify as `OSCILLATING` / `OVERSHOOT_RING` / `GOOD` / `SLUGGISH`. A divergent response classifies `UNSTABLE`. A response with a 5% offset classifies `STEADY_STATE_ERROR`. Log-decrement zeta recovery within 0.05 of the true value. |
| `StepResponseMetricsTest` | Rise time, overshoot, settle time and oscillation frequency computed on an analytic second-order step match closed-form values within 2%. |
| `GainsTest` | `withIntegral(kI != 0, ..., iMaxVolts = 0)` throws. `with(GainId, v)` round-trips through `get(GainId)` for **every one of the twelve ids**, in both directions. Critically: `assertDoesNotThrow(() -> Gains.zero().with(GainId.KI, 0.5))` — enabling kI from the review panel of a zeroed gain set must substitute a clamp, not take the wizard down mid-session — and `integralSubstitutionNote(before, after)` is non-empty for exactly that call and empty when `iMaxVolts` was already positive. |
| `TravelLimitsTest` | `new TravelLimits(0, 1.60, 0.03)` throws, and the message contains the computed minimum `0.032`. `TravelLimits.unbounded()` does not throw. |
| `PredictStepTest` | The answer key is computed, never authored: for a plant with known kV/kA, gains giving `zeta < 0.7` must key option (1) "overshoot and bounce", `zeta > 1.2` must key option (2) "slow and short", and between them option (3) "clean". Boundary cases at exactly 0.7 and 1.2 are pinned. Every shipped `Question` has exactly three options and a `whyWrong` entry for each incorrect index. |
| `LqrPanelMathTest` | `wn = sqrt(kP/kA)` and `zeta = (kD + kV)/(2*sqrt(kP*kA))` as rendered in the §9.2.2 panel match an analytic second-order plant with known `wn`/`zeta` to 1e-9. Monotonicity: `zeta` strictly decreases in kP, strictly increases in kD and in kV. |
| `ExpressRecipeTest` | `TuningRecipe.express(archetype)` contains no `PredictStep` and no `StepResponseStep` in `REFINE` mode, and contains the **same** `PreflightStep` instance as `teaching(archetype)`. Express may drop teaching; it may never drop an interlock. |
| `GainConversionTest` | Canonical -> Phoenix -> canonical and canonical -> REV -> canonical round-trip for kP/kV/kA/kS within 1e-9, for both linear and rotational units. Pins the table in §4.2. |
| `PlantPriorTest` | A negative `gearingReduction` throws with a message naming the invert flag. |
| `LessonsTest` | Every lesson non-empty, < 1800 chars, free of forbidden jargon tokens. |
| `LessonsNumericClaimTest` | Every numeric claim in §13.12's table matches the constant in the code that produces it. Owns the iteration count, the bisection bracket width, the 3%-of-travel guard, the 0.7 damping threshold, `sqrt(3) = 1.732`, and `ln(9) * tau`. |
| `RefinementRuleTest` | Every `ResponseClass` maps to exactly one update rule; every rule respects its cap; six iterations from a deliberately bad starting kP converge or terminate without exceeding `8 * kP_lqr`. |

### 16.2 Sim-integration tests (HAL, `SimHooks`-stepped, run in CI)

Run against WPILib's own plants so the answers are checkable:

| Test | Asserts |
|---|---|
| `FlywheelRecipeSimTest` | Full recipe against a `FlywheelSim` built with the WPILib tutorial's plant converges to kV within 15% of the documented 0.0075 and produces a `GOOD` final response. |
| `ElevatorRecipeSimTest` | Same, against `ElevatorSim`; kG within 5% of 2.28, kV within 15% of 3.07, kA within 30% of 0.41. (kA gets the loosest band; it is the hardest gain to measure and SysId's own docs say so.) |
| `ArmRecipeSimTest` | Same, against `SingleJointedArmSim`; kG within 5% of 1.75, kV within 15% of 1.95. Plus a deliberately-offset-zero case asserting the three-angle check reports a phase error within 2 degrees of the injected offset. |
| `TurretRecipeSimTest` | kV within 15% of 0.15; final kP/kD within a factor of 2 of 0.3 / 0.05. |
| `SafetyAbortTest` | One test per `AbortReason`. Each injects the condition into a sim target and asserts the routine stops within 3 loops, the mechanism is neutral, and the published message names the cause. `SENSOR_INCONSISTENT` is injected by freezing the position signal while velocity keeps moving — the `optimizeBusUtilization` trap. |
| `SimPromotionGateTest` | The wizard refuses to arm a real-flagged target with no promotion record; accepts after **all nine** perturbed runs contain the mechanism; refuses again after `configHash` changes. `runsCompleted` must equal 9 or the promotion is void. **And the failing case, which is the point:** a plant with 6x kA inside a 20-degree safe band reaches a hard stop in at least one run, and the gate must **refuse** and publish `worstCaseDescription`. A gate with no failing test case is a gate nobody has checked. |
| `WizardTestModeTest` | With `DriverStation` simulated into teleop-enabled, `TuningSupervisor.arm()` throws and `commandVolts` is never reached, **while every wizard button is being held**. Repeated for autonomous and for disabled. Then in test-enabled, the same sequence arms and moves. This is the test that proves §7.4.1's claim rather than asserting it in prose. |
| `SharedControllerTest` | Constructing `TuningWizard.using(controller on a ControlMap-registered port)` without `acknowledgeSharedController` raises a `kError` alert whose text names the port number, and the wizard never leaves `IDLE`. With the acknowledgement, it proceeds and the reason string appears verbatim in `report()`. |
| `HoldBisectionSafetyTest` | Against `SingleJointedArmSim` with a **deliberately 3x-wrong `PlantPrior` mass**: the arm never leaves a 3% band around its start position across all 10 iterations, no probe exceeds `PROBE_SECONDS`, an aborted probe still narrows the bracket (assert `hi - lo` strictly decreases every iteration regardless of outcome), and `recentre` returns the arm to within `toleranceSi`. Plus an inverted-sign case — a wrist whose positive direction is downward — asserting `gSign == -1` and a negative kG within 5% of truth. |
| `PersistencePrecedenceTest` | All four tiers, per-gain merge, `configHash` mismatch rejection, malformed JSON degrades to a warning rather than a crash, atomic write survives a simulated interrupt. |
| `ReplaySafetyTest` | Scans the `org.pumpkinlib.tuning` package for `Timer.getFPGATimestamp`, `Math.random`, `new Thread`, and raw `HashMap` iteration in output paths. Fails the build on a hit. |
| `TuningAllocationTest` | 1000 loops with tuning disabled: zero allocations attributable to the tunable path. |
| `NtSchemaTest` | Boots the registry with three mechanisms and asserts every topic named in §12.2 exists with the documented type. This is what keeps the shipped Elastic layout from silently breaking. |

### 16.3 Documentation tests

Every fenced Java block in this document that is presented as usable code is extracted at build time from a compiled test source file, not hand-written in prose. CI fails if a snippet does not compile against the pinned WPILib and vendor versions. This is a release blocker, not a nice-to-have.

Two constraints the extraction enforces, both of which this document has already violated once:

- **Java 17, `--release 17`, no preview features.** `HoldBisectionStep.bisect` originally dispatched on `ProbeOutcome` with a pattern-matching `switch`, which is preview in 17 and would not have compiled. It now uses `instanceof` patterns (final since Java 16). Sealed interfaces and records are fine. This matters beyond style: §17 promises the 2027 port is an import rewrite, and a preview feature is the one thing that would make that false.
- **Every example must construct legally.** §14.1's elevator used `softMargin(0.03)` on 1.60 m of travel, which the §3.1 invariant rejects at 0.032. A doc example that throws in its constructor is worse than no example, because the reader assumes the library is broken rather than the doc.

### 16.4 What CI cannot test

Stated honestly, because over-trusting green CI ships confident bugs: CAN latency, real motor saturation under a sagging battery, belt slip, a wire falling out, and the actual feel of a tuned mechanism. The pre-flight health check (§7.5) and the pit-time `TuningHealth.check(...)` (§10.5) are the on-hardware counterparts, and the tuning report (§11.5c) is the human review artefact.

---

## 17. The 2027 port

The port is designed to be mechanical. Concretely:

| 2027 change | Impact on this domain | Mitigation already in the design |
|---|---|---|
| `edu.wpi.first.*` -> `org.wpilib.*` | Every import | Imports only; no re-exported WPILib types in our public API except `Command`, `Subsystem`, and the unit `Measure` types at the boundary. |
| Java 17 -> Java 25 | None | We use records, sealed interfaces and `var` only — all Java 17 safe. No preview features anywhere. |
| Commands v2 -> v3 (coroutines) | `TuningWizard.command()`, `SysIdSweep`'s command factories | The wizard is a plain enum loop driven from `periodic()`. `command()` is a five-line adapter. Every recipe, step, analyzer and solver is command-framework-agnostic. |
| `MotorController.set()` -> `setThrottle()` | None | We only ever command volts, through `TuningTarget.setVoltage`. |
| NT3 removed | None | NT4 only, already. |
| Shuffleboard / SmartDashboard removed | None | Never used. This is why we rejected YAMS's `/SmartDashboard/...` path. |
| `Alert` API "likely to change" | `PumpkinAlerts` facade (domain 07) | Already isolated behind a facade by design. |
| WPILib Tunable API lands | `TunableTransport` gains a third implementation | §5.7. `TunableDouble`'s public surface does not change. |
| `SysIdRoutine` may move/change | `SysIdSweep` | One class, ~200 lines, isolated. Worst case we own the sweep motion outright — the regression, LQR, diagnostics and wizard are unaffected. |
| Field origin / kinematics changes | None | Nothing in this domain touches field frames, poses, or kinematics. Deliberately. |
| Units: mutable `Measure` removed | None | We use immutable `Measure` at the boundary and plain doubles internally. |

Two source trees from one core: `pumpkin-tuning-core` (regression, LQR designer, analyzer, gains, recipes as data, lessons) depends on **wpimath only** and ports with an import rewrite. `pumpkin-tuning-runtime` (registry, NT publisher, supervisor, wizard loop, persistence) is the year-specific half.

---

## 18. Open Questions

1. **Who owns `Gains`?** I have specified `org.pumpkinlib.control.Gains` here because the tuning domain is what produces gain values and needs the canonical unit contract. The mechanism domain is equally plausible as the owner. This must be reconciled with domain 03 before either of us writes a line; a duplicated or divergent gain type would be fatal to the whole "one unit system" argument.

2. **Does `TuningTarget` belong in the tuning domain or the mechanism domain?** Same tension. I have put it here so a team with hand-rolled subsystems can implement it without depending on PumpkinLib's mechanism layer (§14.4), which I believe is the right call — but it means domain 03 depends on domain 02, not the other way round.

3. **The `kD` conversion to REVLib is approximate.** Phoenix's derivative time base is per-rps; REV's is per-second. I have marked the row **[UNVERIFIED]** in §4.2 and required `describeConversion()` to say so. Someone with a NEO on a bench needs to measure whether the scalar conversion is close enough to be useful, or whether the REV sink should refuse to convert kD at all and require it to be tuned natively.

4. **Do the LQR-derived gains match SysId's Feedback Analysis?** The exact Q/R construction inside `sysid` could not be read from source. If they diverge materially, students who cross-check against the official tool will lose trust. Someone should run both on the same kV/kA and publish the comparison before 0.1 ships.

5. **Is a served web UI worth it in v0.2?** Elastic covers everything functionally, but WPILib's browser tutorials are genuinely excellent and reproducing their exact interactive layout on the robot would be a stronger teaching artefact than a dashboard tab. The cost is an NT4 WebSocket client in JavaScript and a second UI to maintain. Also: ports 5800-5810 are conventionally open for team use, but that is **[UNVERIFIED]** against the 2027 game manual and must be re-checked at kickoff.

6. **Refinement on `ON_MOTOR` loops.** When the closed loop runs at 1 kHz on the device, our 50 Hz `SampleBuffer` aliases the response. Rise times below ~60 ms will be measured badly. Options: raise the signal update frequency in sim and on hardware for the duration of a refinement step (CTRE explicitly recommends higher rates plus a `Notifier` for better simulated PID fidelity), or accept the aliasing and widen the `GOOD` thresholds for fast mechanisms. I lean toward the former but it needs measurement.

7. **How should `DRIVE_VELOCITY` handle a robot that genuinely cannot get 3 m?** Some teams tune in a hallway. The on-blocks detector will correctly refuse, but there is no graceful degraded path today. A "short-run mode" that fits kS/kV from a series of short pulses is possible but the kA estimate would be poor.

8. **Backlash compensation.** We *detect* backlash and tell the student to fix it mechanically, which is the right primary answer. But some mechanisms ship with irreducible slop. Do we eventually offer a directional-offset compensation term, or does that cross the line into hiding a mechanical problem in software? My instinct is that we do not, and we say why.

9. **Multi-motor mechanisms with a disagreeing follower.** The regression fits one applied-voltage signal. If a follower is fighting the leader (wrong `opposeMaster`), the fit is quietly wrong and the health check's current test may not catch it. A per-motor current comparison during the sweep would catch it; it needs `TuningTarget` to expose per-motor currents, which complicates the SPI.

10. ~~**Session length versus student attention.**~~ **ANSWERED — see §8.12.** The elevator recipe is eleven steps and about eight minutes with reading, times four mechanisms, on one shared robot, repeated after every mechanical change. That is a queue, not a lesson. We ship `TuningRecipe.express()` (identification only, one narration screen, ~90 s) alongside the full teaching recipe; `TEACHING` is the non-overridable default in simulation, hardware asks once and then remembers the choice per mechanism. Express drops teaching and never drops an interlock, and `ExpressRecipeTest` pins that. The remaining sub-question, which is genuinely open: **does a student who only ever runs express on hardware still learn?** The `Predictions: n/m` line is our instrument for finding out, and we should look at real reports from 8793 and 9143 after one offseason before deciding whether express should be gated behind a completed teaching run.

11. **Does `MechanicalHealthCheck` belong here or in the health domain (07)?** It is used exclusively as tuning pre-flight today, but "is the mechanism still mechanically sound" is a pit-check question. Probably it should live in 07 and be consumed here. Note that §7.6 promoted its `BLOCK` verdict to one of the two headline safety properties of this domain, which raises the stakes on getting the ownership right: a headline safety property should not live in a domain that treats it as a helper.

12. **Does Phoenix 6's `getClosedLoopOutput()` read back in volts for a voltage-output request?** The 26.1 javadoc says only *"Closed loop total output"* and states no units (verified 2026-08-07). Our whole `residualVolts` diagnosis — and therefore every "raise kS, not kI" message the library exists to deliver — assumes it does for `PositionVoltage`/`VelocityVoltage`/`MotionMagicVoltage`. Someone needs a Kraken on a bench, a known kS, and ten minutes: command a voltage-output closed loop, hold it at steady state, and check that `getClosedLoopOutput() - getClosedLoopFeedForward()` is a plausible number of volts rather than a duty cycle. If it is a duty cycle, the adapter multiplies by the supply voltage and this document gets one more conversion row. If it is something else, the Phoenix path falls back to the REVLib-style Java reconstruction (§9.3.1) and we say so in `describeConversion()`.

13. **`LoopLocation` versus `ControlLocation`.** This domain names the axis `LoopLocation{ON_MOTOR, ON_CONTROLLER}`; `DESIGN.md` §10.1's builder names it `ControlLocation{ON_MOTOR_PROFILED, RIO_FULL}`. §9.3.1 records the decision (tuning SPI type vs builder type, one mapping owned by domain 03) but two names for one concept is how a doc example stops compiling. Reconcile with domain 03 at the same time as open questions 1 and 2, and consider collapsing to one type.

---

## Appendix A — File and class inventory

```
org.pumpkinlib.control
    Gains                       record: canonical volts-per-SI gains + GainId + GravityType + ProfileConstraints
    GainSink                    interface: canonical -> vendor conversion and apply
    Controllers                 factory: Feedforward / PIDController / profiles from Gains

org.pumpkinlib.tuning
    TuningTarget                interface: the mechanism SPI
    MechanismArchetype          enum
    MechanismUnits              enum
    LoopLocation                enum
    TravelLimits                record
    PlantPrior                  record
    TuningRegistry              static: tunables, targets, tuning mode, periodic
    TunableDouble               NT-backed double
    TunableGains                the gain set for one mechanism, write-through
    TunableTransport            interface + Nt4TunableTransport + AdvantageKitTunableTransport
    SafetyEnvelope              record
    TuningSupervisor            the voltage choke point
    AbortReason                 enum
    FeedbackDesigner            LQR from kV/kA
    MechanicalHealthCheck       backlash / friction / slip / direction pre-flight
    HealthReport                record
    SimPromotion                record

org.pumpkinlib.tuning.sysid
    SysIdSweep                  generated SysIdRoutine + derived envelope
    FeedforwardRegression       streaming OLS
    FeedforwardFit              record
    IdentificationException
    SampleBuffer                fixed-capacity ring buffer
    CentralDifferenceAccel      velocity -> acceleration filter for targets with no accel signal

org.pumpkinlib.tuning.wizard
    TuningWizard                the state machine
    WizardState                 enum
    WizardInputs                interface (gamepad-free path)
    TuningRecipe                ordered list of steps + envelope + preflight; Mode{TEACHING,EXPRESS}
    TuningStep                  interface
    StepContext                 interface
    StepResult                  record (incl. Optional<Boolean> predictionCorrect)
    Recipes                     the six built-in recipes, each in both modes
    steps/PredictStep           formative assessment; no motion; computed answer key
    steps/PreflightStep
    steps/BreakawayRampStep     kS
    steps/HoldBisectionStep     kG: drift-sign probe, physics bracket, guarded probe, recentre
    steps/SysIdSweepStep        kV, kA
    steps/LqrSuggestStep        kP, kD (+ the wn/zeta interpretation panel)
    steps/StepResponseStep      verify + refine
    Lessons                     the teaching text
    Coach                       verdict -> plain language; branches on prediction right/wrong

org.pumpkinlib.tuning.diagnostics
    StepResponseAnalyzer        pure, HAL-free
    ResponseClass               enum
    ResponseVerdict             record
    TuningHealth                pit-time "still tuned?" check

org.pumpkinlib.tuning.persist
    GainStore                   load precedence, atomic write
    GainsExporter               Java block, JSON baseline, markdown report
    QualityRecord               record

org.pumpkinlib.tuning.ui
    TunerPublisher              every NT topic in section 12.2
    ElasticLayoutGenerator      writes elastic-tuning-layout.json from the registry
```

## Appendix B — Verified API reference

Every WPILib signature this design depends on, confirmed against the WPILib 2026 Javadoc on 2026-08-06:

```java
// Feedforward
ArmFeedforward(double ks, double kg, double kv, double ka, double dtSeconds)
double ArmFeedforward.calculateWithVelocities(double currentAngle, double currentVelocity, double nextVelocity)
void   ArmFeedforward.setKs/setKg/setKv/setKa(double)
double ElevatorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)
double SimpleMotorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)
// DEPRECATED for removal since 2025: calculate(pos, vel, accel) and calculate(angle, curVel, nextVel, dt)

// Plants and LQR
LinearSystem<N2,N1,N2> LinearSystemId.identifyPositionSystem(double kV, double kA)
LinearSystem<N1,N1,N1> LinearSystemId.identifyVelocitySystem(double kV, double kA)
LinearSystem<N2,N1,N2> LinearSystemId.createElevatorSystem(DCMotor motor, double massKg, double radiusMeters, double gearing)
LinearSystem<N2,N1,N2> LinearSystemId.createSingleJointedArmSystem(DCMotor motor, double JKgSquaredMeters, double gearing)
LinearSystem<N1,N1,N1> LinearSystemId.createFlywheelSystem(DCMotor motor, double JKgMetersSquared, double gearing)
LinearQuadraticRegulator(LinearSystem<States,Inputs,Outputs> plant, Vector<States> qelms, Vector<Inputs> relms, double dtSeconds)
void LinearQuadraticRegulator.latencyCompensate(LinearSystem<States,Inputs,Outputs> plant, double dtSeconds, double inputDelaySeconds)
Matrix<Inputs,States> LinearQuadraticRegulator.getK()

// Linear algebra
static <R,C> Matrix<R,C> MatBuilder.fill(Nat<R> rows, Nat<C> cols, double... data)     // row-major
static Vector<N3> VecBuilder.fill(double, double, double)                              // and N1..N10
<R2,C2> Matrix<C,C2> Matrix.solveFullPivHouseholderQr(Matrix<R2,C2> other)
double Matrix.get(int row, int col)
// NOTE: Matrix has NO plusEqu(). Accumulate in double[][].

// Profiles
static ExponentialProfile.Constraints ExponentialProfile.Constraints.fromCharacteristics(double maxInput, double kV, double kA)
static ExponentialProfile.Constraints ExponentialProfile.Constraints.fromStateSpace(double maxInput, double A, double B)

// SysId
SysIdRoutine(SysIdRoutine.Config config, SysIdRoutine.Mechanism mechanism)
SysIdRoutine.Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout, Consumer<SysIdRoutineLog.State> recordState)
SysIdRoutine.Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout)
SysIdRoutine.Config()                                    // 1 V/s, 7 V, 10 s
SysIdRoutine.Mechanism(Consumer<Voltage> drive, Consumer<SysIdRoutineLog> log, Subsystem subsystem, String name)
Command SysIdRoutine.quasistatic(SysIdRoutine.Direction direction)
Command SysIdRoutine.dynamic(SysIdRoutine.Direction direction)

// Persistence and platform
static File Filesystem.getOperatingDirectory()   // /home/lvuser on the roboRIO; pwd in sim
static File Filesystem.getDeployDirectory()      // /home/lvuser/deploy; pwd/src/main/deploy in sim
static void Preferences.initDouble(String key, double value)
static double Preferences.getDouble(String key, double backup)
static void Preferences.setDouble(String key, double value)
static NetworkTable Preferences.getNetworkTable()

// Alerts (package edu.wpi.first.wpilibj) - documented as unstable, used only via a facade
Alert(String group, String text, Alert.AlertType type)   // AlertType: kError, kWarning, kInfo
void Alert.set(boolean active)

// Web
static void WebServer.start(int port, String path)
static void WebServer.stop(int port)
```

**Unverified items, restated in one place so a reviewer can find them:**

1. The exact Q/R construction inside SysId's Feedback Analysis (§9.2) — inferred from WPILib prose plus the public `LinearQuadraticRegulator` API, not read from `sysid` source.
2. Our `voltageFitR2` is not comparable to SysId's simulated-velocity or acceleration r-squared (§6.3); thresholds in §6.4 are PumpkinLib's own.
3. The REVLib `kD` scalar conversion (§4.2) — derivative time base differs between vendors.
4. Whether GradleRIO's deploy task deletes files under `/home/lvuser/deploy` that are absent from the source tree (§11.2). Our design does not depend on the answer.
5. Ports 5800-5810 being open for team use on the field (§12.1) — must be verified against the 2027 game manual at kickoff.
6. The final class and method names of WPILib's 2027 Tunable API (§5.7) — PR #7773 is open, not merged.
7. The **units** of Phoenix 6's `getClosedLoopOutput()` (§9.3.1). The method signatures are verified (`StatusSignal<Double>`, 2026-08-07, [CoreTalonFX](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/hardware/core/CoreTalonFX.html)); the javadoc states no units and "volts for voltage-output requests" is our inference. Open question 12. The adapter returns `empty()` rather than guessing for every other request type.

**Phoenix 6 signatures verified 2026-08-07** (same source):

```java
StatusSignal<Double> CoreTalonFX.getClosedLoopOutput()            // "Closed loop total output"
StatusSignal<Double> CoreTalonFX.getClosedLoopOutput(boolean refresh)
StatusSignal<Double> CoreTalonFX.getClosedLoopFeedForward()       // "Feedforward passed by the user"
StatusSignal<Double> CoreTalonFX.getClosedLoopFeedForward(boolean refresh)
StatusSignal<Double> CoreTalonFX.getClosedLoopError()
StatusSignal<Double> CoreTalonFX.getClosedLoopReference()
// Neither closed-loop signal is in the default subscribed set; subscribe with
// BaseStatusSignal.setUpdateFrequencyForAll(50, ...) only when the target is registered.
```

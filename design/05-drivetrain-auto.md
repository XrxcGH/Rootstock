# PumpkinLib Design 05 — Drivetrain, Autonomous, and Mechanism Action Coordination

**Status:** Design complete, ready to implement
**Owner domain:** drivetrain + autonomous + mechanism-action coordination
**Target:** WPILib 2026.x / Java 17 / GradleRIO 2026.2.1 / Phoenix 6 26.x / REVLib 2026.0.5 / PathPlannerLib 2026.1.2 / ChoreoLib 2026.0.3 / PhotonVision 2026.x / AdvantageKit 2026.x
**Written:** 2026-08-06 (offseason; 2027 kickoff 2027-01-09)

---

## 0. Scope & Responsibilities

### 0.1 What this domain owns

| # | Responsibility | Public entry point |
|---|---|---|
| 1 | One vendor-neutral drivetrain façade with exactly **one actuation funnel** | `org.pumpkinlib.drive.PumpkinDrive` |
| 2 | Backend adapters: CTRE Tuner-X swerve, AdvantageKit swerve template, YAGSL, hand-rolled swerve, differential/tank | `org.pumpkinlib.drive.backend.*` |
| 3 | Traction/slip limiting, skid detection, slew shaping — applied to **teleop and auto identically** | `org.pumpkinlib.drive.traction.*` |
| 4 | Driver-input DSL: deadband, expo, heading lock, aim-at, alliance-relative, drive-to-pose | `org.pumpkinlib.drive.input.DriveInputStream` |
| 5 | **The single owner of alliance handling** — field-centric perspective, pose flipping, path mirroring | `org.pumpkinlib.field.PumpkinField` / `AlliancePerspective` |
| 6 | One auto API over **both** PathPlanner 2026.1.2 and Choreo 2026 | `org.pumpkinlib.auto.PumpkinAuto` |
| 7 | One trigger/composition surface identical across both trajectory sources | `org.pumpkinlib.auto.PumpkinTrajectory` |
| 8 | Mechanism-action coordination during autos (the explicit user ask) | `org.pumpkinlib.auto.PumpkinAutoRoutine` + `AutoStep` |
| 9 | Auto composition, dependent-question selection, per-step budgets, fallbacks, retries | `org.pumpkinlib.auto.PumpkinAutoSelector` |
| 10 | Pathfinding façade with replay determinism and warmup | `org.pumpkinlib.nav.PumpkinNav` |
| 11 | Production drive-to-pose / scoring alignment | `org.pumpkinlib.nav.PumpkinDriveToPose` |
| 12 | Drivetrain characterization + an odometry-error **measurement** report | `org.pumpkinlib.characterization.*` |
| 13 | Headless auto validation harness | `org.pumpkinlib.auto.test.PumpkinAutoTest` |

### 0.2 What this domain explicitly does NOT own

- Motor wrappers, gearing types, mechanism subsystems, PID tuning UI (mechanism + tuner domains).
- Vision pipelines, camera fusion, std-dev models (vision domain). We consume a `VisionObservation` and expose exactly one sink.
- Logging transport (logging domain). We consume a `PumpkinLog` SPI and never call `Logger` or `SignalLogger` directly.
- The superstructure state graph itself (mechanism domain). We define the **request-bus contract** we need from it and nothing more.

### 0.3 Non-negotiable design rules for this domain

1. **We adapt; we do not reimplement.** No PumpkinLib swerve library, no PumpkinLib trajectory generator, no PumpkinLib pathfinder, no PumpkinLib odometry math beyond what WPILib/vendors do not provide.
2. **Every abstraction has a visible escape hatch.** `drive.backend()`, `drive.raw(CommandSwerveDrivetrain.class)`, `auto.pathPlannerBuilderConfigured()`, `auto.choreoFactory()`. A team that outgrows the façade is never trapped.
3. **Zero-mystery failure.** Every guard failure raises a WPILib `Alert` with the *fix*, not just the symptom, and logs to `Pumpkin/Alerts`. We never `throw` from a constructor at boot.
4. **Sim-first.** Every command in this document runs with `RobotBase.isSimulation() == true` and no hardware. `PumpkinAutoTest` runs faster than real time with no HAL sim GUI.
5. **No vendor type in a public signature.** WPILib geometry/kinematics types (`Pose2d`, `ChassisSpeeds`, `SwerveModuleState`) *are* allowed and encouraged — students must learn them, and the 2027 `edu.wpi.first` → `org.wpilib` / `ChassisSpeeds` → `ChassisVelocities` change is a mechanical rename. `PathPlannerPath`, `AutoTrajectory`, `SwerveSample`, `DriveFeedforwards`, `SwerveRequest`, `SwerveDrive` (YAGSL) must **never** appear in a PumpkinLib public signature — those are the types that will break in ways a rename cannot fix. This is a deliberate departure from the "PumpkinLib-owned value types everywhere" suggestion in `deep-drivetrain.json`: hiding `ChassisSpeeds` violates "never hide WPILib" for zero 2027 benefit.
6. **Java 17 subset only.** Records, sealed interfaces, `var`, arrow switch: yes. Pattern-matching `switch`, record patterns: no (preview in 17). No `Math.clamp` (Java 21). We ship `PumpkinMath.clamp`.
7. **Conventions:** private fields `m_name`, constants `kName` or `UPPER_SNAKE_CASE`, WPILib `Units` (`Measure`) types in config APIs with raw-`double` overloads as the escape hatch (8793's team code is raw doubles today — `repo-8793.json` constraints).
8. **One clock.** No code in this domain calls `Timer.getFPGATimestamp()`, and no code in this domain constructs an `edu.wpi.first.wpilibj.Timer`. Time comes from `org.pumpkinlib.core.compat.Clock.now()` and elapsed time comes from `org.pumpkinlib.core.util.PumpkinStopwatch`, which is `Clock`-backed. This is DESIGN.md Principle 9 / D12, `02 §5.6` rule 1, and the ArchUnit hard rule 3 in `06`. A `Timer` instance is **not** an exemption: `Timer` reads the FPGA clock internally, so an AdvantageKit replay run advances it in wall-clock time while the log advances in log time, and every `atTime`/`settleTime`/`deadline` trigger in this document desynchronizes. See §6.2.1.
9. **No hardcoded match constants.** The autonomous period length is `FieldMap.autoPeriodSeconds()`, never a literal `15.0`. Binding decision D15.

---

## 1. Integration Points — what I need from other PumpkinLib domains

These are the **only** cross-domain surfaces this design touches. Each is a small interface I need someone else to own.

### 1.1 From the logging domain — `org.pumpkinlib.log.PumpkinLog`

```java
package org.pumpkinlib.log;

/** Fan-out logging sink. Implementations: AdvantageKitSink, DataLogSink, HootSink, NtSink, NullSink. */
public interface PumpkinLog {
  void put(String key, double value);
  void put(String key, double[] value);
  void put(String key, boolean value);
  void put(String key, String value);
  void put(String key, Pose2d value);
  void put(String key, Pose2d[] value);
  void put(String key, ChassisSpeeds value);
  void put(String key, SwerveModuleState[] value);
  /** Latency attribution; no-op when disabled. */
  void trace(String key, Runnable body);

  static PumpkinLog get();                    // process-wide, defaults to NullSink
  static void set(PumpkinLog sink);           // called once from PumpkinRobot
}
```

**Why I need it:** 8793 logs through CTRE `SignalLogger` + NT (`repo-8793.json` constraint: "Do not design the library around AdvantageKit"); 9143 and 4738 log through AdvantageKit `Logger`. This domain must work with all three and must not force an IO-layer architecture.

### 1.2 From the mechanism/superstructure domain — `org.pumpkinlib.mechanism.GoalBus`

This is the single most important cross-domain contract in this document. Auto mechanism coordination is **impossible to get right** without it, because PathPlanner's documented #1 footgun is that a `NamedCommand` sharing subsystem requirements with the auto group **cancels the auto group** (`deep-auto.json` pitfalls).

```java
package org.pumpkinlib.mechanism;

/**
 * A superstructure exposed as a single asynchronous request target.
 * CONTRACT: the implementation owns the requirements of every mechanism subsystem it
 * coordinates, exactly once, for the whole match. Commands returned from this interface
 * MUST NOT declare those subsystems as requirements — otherwise scheduling a goal from a
 * trajectory trigger will cancel the enclosing auto command group.
 */
public interface GoalBus<G extends Enum<G>> {
  /** Fire-and-forget. Legal to call from a Trigger inside a running auto. Never blocks. */
  void requestAsync(G goal);
  /** A Command that requests `goal` and finishes when the superstructure reports it reached. */
  Command request(G goal);
  /** True when the superstructure has settled at `goal`. */
  Trigger atGoal(G goal);
  /** True when no transition is in flight. */
  boolean isStable();
  /** Best-effort planned duration of the current transition, for auto budget reporting. */
  double plannedTransitionSeconds();
  G currentGoal();
}
```

**Fallback if the mechanism domain slips:** `GoalBus.ofCommands(Map<G, Command>)` adapts a plain enum→Command map, warns once via `Alert` that requirement-collision protection is unavailable, and still works. Auto coordination ships without the state graph.

### 1.3 From the vision domain — `VisionObservation` push

```java
package org.pumpkinlib.vision;

/** Blue-origin pose, FPGA-timebase timestamp, per-axis std devs. */
public record VisionObservation(Pose2d bluePose, double fpgaTimestampSeconds,
                                Matrix<N3, N1> stdDevs, int tagCount, double avgTagDistanceMeters) {}
```

The vision domain **pushes** into `drive.addVisionMeasurement(obs)`. It must never pull a recycled buffer (`repo-8793.json` pain point: `getEstimatedPoses()` returns a shared mutable list). This domain owns the *timebase translation* for CTRE backends (`Utils.fpgaToCurrentTime`) — the vision domain always speaks FPGA time and never needs to know the backend (`repo-8793.json` and `repo-9143.json` both list this as a hard constraint).

I also need, for skid-aware trust arbitration:

```java
/** Vision domain registers this so we can inflate/deflate trust from skid state. */
public interface OdometryTrustListener { void onSkid(SkidReport report); }
```

### 1.4 From the config/tuning domain

```java
org.pumpkinlib.config.Tunable        // TunableDouble/TunableBoolean, NT-backed, no-op when TUNING_MODE==false and FMS attached
org.pumpkinlib.config.PumpkinConfigStore  // read/write deploy/pumpkin/*.json, with a /home/lvuser write path at runtime
org.pumpkinlib.config.PumpkinAlerts   // Alert factory with stable keys; used for every guard in this document
```

Every gain in `PumpkinDriveToPose`, `DriveInputStream.headingLock`, and the path-following controllers is a `Tunable`. This directly addresses the biggest pain point in all three surveyed repos: **zero tunable numbers exist in 8793 or 9143, and 4738 built a tunable stack and then commented out 30+ declarations for competition**.

### 1.5 From the sim domain

```java
org.pumpkinlib.sim.PumpkinSim.field(int year)   // maple-sim world, or a null world
org.pumpkinlib.sim.HeadlessClock                // steps SimHooks + CommandScheduler faster than real time
```

### 1.6 From the field-constants domain

```java
org.pumpkinlib.field.FieldMap.year()            // e.g. 2026
org.pumpkinlib.field.FieldMap.lengthMeters() / widthMeters()
org.pumpkinlib.field.FieldMap.symmetry()        // FieldSymmetry.ROTATIONAL | MIRRORED for this year
org.pumpkinlib.field.FieldMap.tagLayout()       // AprilTagFieldLayout
org.pumpkinlib.field.FieldMap.autoPeriodSeconds()   // 15.0 in 2026 REBUILT. NEVER hardcoded — D15.
```

`autoPeriodSeconds()` is a hard requirement of this domain, not a nicety: `PumpkinAutoRoutine.skipToAfter`, every `AutoStep.deadline` default, and `PumpkinAutoTest`'s period all read it. If the field-constants domain slips, ship it as a one-line constant holder — but ship the *accessor*, because `skipToAfter` is specified in terms of seconds **remaining**, which is meaningless without it.

### 1.7 From the dashboard domain

`PumpkinAutoSelector` publishes stable NT topics; the dashboard domain owns generating the Elastic layout JSON that displays them. Shuffleboard and SmartDashboard are removed in WPILib 2027, so we publish raw NT4 topics + a `SendableChooser`-compatible surface, never `SmartDashboard.putData` from library code.

### 1.8 From the core/compat domain — the clock

```java
package org.pumpkinlib.core.compat;

/**
 * The one time source in PumpkinLib. Under AdvantageKit replay this returns LOG time, so every
 * trigger, timeout and settle window replays bit-identically. Never Timer.getFPGATimestamp().
 */
public final class Clock {
  /** Seconds, monotonic, FPGA timebase on a real robot, log timebase in replay. */
  public static double now();
}
```

> **Naming note for the implementer.** Doc `06`'s `Clock` javadoc and doc `02 §5.6` both name this accessor. If the core domain lands it as `Clock.seconds()`, that is the same method — this document standardizes on **`Clock.now()`**, matching DESIGN §8 ArchUnit hard rule 3 (“`Clock.now()` only”). Pick one name in `core`, and make the other a `@Deprecated` alias so neither doc rots.

This domain also needs one 40-line utility, which lives in `core` because the mechanism and vision domains need it too:

```java
package org.pumpkinlib.core.util;

/**
 * Replay-safe stopwatch. Drop-in shape-compatible with edu.wpi.first.wpilibj.Timer for the five
 * methods PumpkinLib uses, but every read is Clock.now(). PumpkinLib code NEVER constructs a
 * wpilibj Timer — see §0.3 rule 8.
 */
public final class PumpkinStopwatch {
  public void   restart();          // reset + start
  public void   start();
  public void   stop();
  public void   reset();
  public double get();              // seconds accumulated while running
  public boolean isRunning();
  public boolean hasElapsed(double seconds);
}
```

**ArchUnit enforcement (belongs in `06`'s `WpiSurfaceTest`, added by this document's request):**

```java
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@ArchTest
static final ArchRule kOneClock =
    noClasses().that().resideInAPackage("org.pumpkinlib..")
        .should().callMethodWhere(target(name("getFPGATimestamp")))
        .orShould().callConstructorWhere(
            target(owner(assignableTo(edu.wpi.first.wpilibj.Timer.class))))
        .because("Principle 9 / D12: Clock.now() and PumpkinStopwatch only. A wpilibj Timer reads "
               + "the FPGA clock internally and desynchronizes under AdvantageKit replay.");
```

> **Note on the predicate spelling.** The review that produced this rule wrote it as
> `callMethodWhere(nameIs("getFPGATimestamp"))`. `nameIs` is not an ArchUnit predicate — the real
> ones are `HasName.Predicates.name(String)` / `nameMatching(String)`, and a call predicate must be
> lifted onto the call *target* with `JavaCall.Predicates.target(...)`. The rule's intent is
> unchanged; only the spelling is corrected, because a design doc that ships a rule which does not
> compile teaches the implementer to distrust every other snippet in it.
> Verified against ArchUnit's published API surface (`com.tngtech.archunit.core.domain.JavaCall.Predicates`,
> `…core.domain.properties.HasName.Predicates`, `…core.domain.properties.HasOwner.Predicates.With`,
> `…core.domain.JavaClass.Predicates.assignableTo`) — <https://www.archunit.org/userguide/html/000_Index.html>.

`04`'s `PumpkinReplay` runtime tripwire traps the same call at runtime. Both stay: the ArchUnit rule fails the build, the tripwire catches a vendor library dragging it in transitively.

---

## 2. Package layout

```
org.pumpkinlib.drive
  PumpkinDrive              (final class — THE funnel)
  PumpkinDriveConfig        (record)
  DriveGeometry             (record)
  DriveLimits               (record)
  WheelForces               (record)
  ModuleOrder               (enum + validator)
  DiscretizationPolicy      (enum)
  TractionMode              (enum)
  OdometryMode              (enum)
org.pumpkinlib.drive.backend
  DriveBackend              (SPI interface — implement once per vendor)
  CtreSwerveBackend
  AdvantageKitSwerveBackend
  YagslBackend
  HandRolledSwerveBackend
  DifferentialBackend
org.pumpkinlib.drive.traction
  TractionLayer
  SkidDetector, SkidReport
org.pumpkinlib.drive.input
  DriveInputStream
  HeadingController
org.pumpkinlib.field
  PumpkinField, AlliancePerspective, FieldSymmetry, AllianceValue
org.pumpkinlib.auto
  PumpkinAuto, PumpkinTrajectory, PumpkinAutoRoutine, AutoStep, AutoStepResult
  PumpkinAutoSelector, AutoQuestion, AutoResponses
  PumpkinAutoMode           (abstract base, 1678 shape)
org.pumpkinlib.auto.source
  TrajectorySource, TrajectoryHandle, ChoreoSource, PathPlannerSource
org.pumpkinlib.nav
  PumpkinNav, PumpkinDriveToPose, PumpkinConstraints
org.pumpkinlib.characterization
  PumpkinCharacterization, DriveCharacterization (record), OdometryReport
org.pumpkinlib.auto.test
  PumpkinAutoTest, AutoTestResult
org.pumpkinlib.pure.math        (Tier 0: HAL-free, WPILib-geometry-only, unit-testable off-robot)
  PumpkinMath   (clamp, deadband2d, expo, epsilonEquals)
org.pumpkinlib.core.util
  PumpkinStopwatch                (Clock-backed; see §1.8)
```

**`PumpkinMath` — the two shaping helpers this domain depends on.** These live in Tier 0 (`org.pumpkinlib.pure.math`) so they compile without the HAL and port to `org.wpilib` by find/replace.

```java
package org.pumpkinlib.pure.math;

public final class PumpkinMath {

  /** Java 21's Math.clamp, for Java 17. */
  public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

  /**
   * Radial (2-D) deadband. Deadbands the JOYSTICK VECTOR, not each axis, so a diagonal push of
   * 0.09/0.09 (magnitude 0.127) is not silently zeroed while a 0.11 push on one axis survives.
   * Rescales the surviving magnitude to the full [0, 1] range so there is no discontinuity at the
   * band edge.
   *
   * WPILib's MathUtil has applyDeadband(double, double) and applyDeadband(double, double, double)
   * ONLY — there is no 2-D overload in 2026.2.2. This is that missing six lines.
   */
  public static Translation2d deadband2d(double x, double y, double band) {
    double m = Math.hypot(x, y);
    if (m < band) return Translation2d.kZero;
    double scaled = (m - band) / (1.0 - band);
    return new Translation2d(scaled * x / m, scaled * y / m);
  }

  public static boolean epsilonEquals(double a, double b, double eps) { return Math.abs(a - b) < eps; }
}
```

Expo shaping uses WPILib directly — `MathUtil.copySignPow(double value, double exponent)` is real in 2026.2.2 and we do not wrap it.

**Gradle:** PathPlannerLib, ChoreoLib, Phoenix 6, REVLib, YAGSL, AdvantageKit are all `compileOnly` + `testImplementation`. A team that installs only PathPlanner still compiles. Presence is detected once at class-init by `Class.forName` and cached in `Vendors.hasPathPlanner()` / `hasChoreo()` / `hasPhoenix6()` / `hasAdvantageKit()`.

---

## 3. Drivetrain adapter

### 3.1 Core value types

```java
package org.pumpkinlib.drive;

/**
 * Physical layout. Module locations are ALWAYS in PumpkinLib module order:
 * FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT. This matches PathPlanner's output
 * order and WPILib's kinematics contract. Any backend whose native order differs must
 * permute in its adapter, not leak the difference upward.
 */
public record DriveGeometry(
    Translation2d[] moduleLocations,   // length 4 for swerve, length 0 for differential
    double trackWidthMeters,           // differential: wheel-to-wheel; swerve: derived
    double driveBaseRadiusMeters,      // hypot of the farthest module; used by every controller
    double wheelRadiusMeters,
    boolean holonomic) {

  public static DriveGeometry swerve(Translation2d fl, Translation2d fr,
                                     Translation2d bl, Translation2d br,
                                     Distance wheelRadius) { /* computes radius = max ||t|| */ }

  public static DriveGeometry differential(Distance trackWidth, Distance wheelRadius) { /* ... */ }

  public int moduleCount() { return moduleLocations.length; }
}

/** Everything a controller needs to not command the impossible. */
public record DriveLimits(
    double maxLinearVelocityMps,
    double maxAngularVelocityRadPerSec,
    double maxLinearAccelerationMpsSq,
    double maxAngularAccelerationRadPerSecSq,
    double slipForceNewtons) {          // = mass * g * wheelCOF; 0.0 means "unknown"

  public static DriveLimits of(LinearVelocity v, LinearAcceleration a,
                               AngularVelocity w, AngularAcceleration alpha) { /* ... */ }
  /** Derived from a PumpkinLib RobotMass/COF config when the team has run characterization. */
  public DriveLimits withSlipForce(double newtons) { /* ... */ }
}

/**
 * Per-module wheel force feedforwards, robot-relative, newtons, in PumpkinLib module order.
 * This is the neutral shape that both PathPlanner's DriveFeedforwards
 * (robotRelativeForcesXNewtons()/Y) and Choreo's SwerveSample (moduleForcesX()/Y) map onto.
 */
public record WheelForces(double[] xNewtons, double[] yNewtons) {
  public static WheelForces none(int modules) { /* zero arrays */ }
  public boolean isPresent() { /* any non-zero */ }
  public int size() { return xNewtons.length; }
}

/** Who is responsible for calling ChassisSpeeds.discretize. Exactly one party, always. */
public enum DiscretizationPolicy {
  /** Backend already discretizes internally (e.g. CTRE FieldCentric/RobotCentric requests). */
  BACKEND,
  /** PumpkinDrive's funnel must discretize before handing off (e.g. CTRE ApplyRobotSpeeds,
   *  hand-rolled kinematics, YAGSL raw ChassisSpeeds entry). */
  FUNNEL
}

public enum TractionMode { NONE, SLEW_RATE, SETPOINT_GENERATOR }

public enum OdometryMode { NATIVE_250HZ, THREADED_100HZ, LOOP_50HZ }
```

### 3.2 `DriveBackend` — the vendor SPI

This is the *only* thing a new backend implements. It is deliberately dumb: no traction, no discretization decisions, no alliance logic, no logging.

```java
package org.pumpkinlib.drive.backend;

public interface DriveBackend {

  // ---- identity / geometry -------------------------------------------------
  String name();                                  // "CTRE Tuner-X swerve", "AdvantageKit swerve", ...

  /**
   * The Subsystem this backend ALREADY owns, if it has one. Optional on purpose:
   *   - CtreSwerveBackend wraps a CommandSwerveDrivetrain, which IS a Subsystem  -> present
   *   - AdvantageKitSwerveBackend wraps the template's Drive extends SubsystemBase -> present
   *   - YagslBackend wraps swervelib.SwerveDrive, which is NOT a Subsystem       -> EMPTY
   *   - HandRolledSwerveBackend / DifferentialBackend own raw IO objects         -> usually EMPTY
   * When empty, PumpkinDrive creates and registers exactly one synthetic requirement. Never
   * return a Subsystem you did not construct — see §3.3.
   */
  default Optional<Subsystem> existingSubsystem() { return Optional.empty(); }

  DriveGeometry geometry();
  DriveLimits limits();
  DiscretizationPolicy discretization();

  // ---- state ---------------------------------------------------------------
  Pose2d getPose();                               // BLUE-ORIGIN, always
  Rotation2d getGyroHeading();                    // raw gyro, CCW+, blue-origin frame
  ChassisSpeeds getRobotRelativeSpeeds();
  SwerveModuleState[] getModuleStates();          // length 0 when !geometry().holonomic()
  SwerveModulePosition[] getModulePositions();

  // ---- actuation (called at most once per loop, by PumpkinDrive only) -------
  void applyRobotRelative(ChassisSpeeds robotRelative, WheelForces forces);
  void stop();
  void brake();                                   // swerve: X-lock; diff: neutral-brake

  // ---- pose ----------------------------------------------------------------
  void resetPose(Pose2d bluePose);
  /** timestamp is ALWAYS FPGA time. The backend converts if its estimator needs another base. */
  void addVisionMeasurement(Pose2d bluePose, double fpgaTimestampSeconds, Matrix<N3, N1> stdDevs);

  // ---- driver perspective --------------------------------------------------
  /** Called every robotPeriodic by AlliancePerspective. kZero on blue, k180deg on red. */
  void setOperatorPerspectiveForward(Rotation2d blueRelativeForward);

  // ---- characterization (10 lines each, implemented once per backend) -------
  void runCharacterizationVolts(double volts);
  /** Per-module drive rotation in RADIANS, PumpkinLib module order. */
  double[] getWheelRadiusCharacterizationPositionsRad();
  /** Mean absolute drive angular velocity, rad/s. */
  double getFFCharacterizationVelocityRadPerSec();

  // ---- escape hatch --------------------------------------------------------
  Object raw();

  // ---- lifecycle -----------------------------------------------------------
  default void periodic() {}
  default void simulationPeriodic() {}
}
```

### 3.3 `PumpkinDrive` — the one funnel

`PumpkinDrive` is a **final class, not a Subsystem, and not an interface.** This is a deliberate decision:

- 8793's `CommandSwerveDrivetrain extends TunerSwerveDrivetrain implements Subsystem` (`repo-8793.json` constraint). If `PumpkinDrive` were also a `Subsystem`, we would register a *second* subsystem for the same hardware and every requirement calculation would be wrong.
- Instead `PumpkinDrive.requirement()` returns the backend's existing `Subsystem` **when it has one**, and every command factory in this document declares that.

#### 3.3.1 `requirement()` is total — it never returns `null`

Half the backends do not wrap a `Subsystem`. `swervelib.SwerveDrive` is not one; a hand-rolled backend built from raw `SwerveModuleIO`s is not one; a `DifferentialConfig` built from four `DoubleConsumer`s is not one. Returning `null` there would `NullPointerException` inside `m_drive.requirement().setDefaultCommand(...)` — which is the *first line of driver code every team writes* (DESIGN.md §10.3, and §10 of this document). That is unacceptable for a library whose stance is zero-mystery failure.

```java
public final class PumpkinDrive {

  private Subsystem m_synthetic;   // created at most once, lazily

  /**
   * The ONE Subsystem that owns this drivetrain's hardware, for the whole match.
   * Total: never null, always the same instance, always exactly one per PumpkinDrive.
   */
  public Subsystem requirement() {
    return m_backend.existingSubsystem().orElseGet(this::syntheticRequirement);
  }

  private synchronized Subsystem syntheticRequirement() {
    if (m_synthetic == null) {
      // Deliberately NOT SubsystemBase: SubsystemBase registers a Sendable on
      // SmartDashboard, which is removed in WPILib 2027 (§12). We implement the interface
      // directly and register ourselves with the scheduler.
      m_synthetic = new Subsystem() {
        @Override public void periodic()           { }              // PumpkinDrive.periodic() owns this
        @Override public void simulationPeriodic() { }
        @Override public String getName()          { return "PumpkinDrive[" + m_backend.name() + "]"; }
      };
      CommandScheduler.getInstance().registerSubsystem(m_synthetic);
      PumpkinLog.get().put("Pumpkin/Drive/RequirementOwner", "synthetic:" + m_backend.name());
    }
    return m_synthetic;
  }
}
```

When the backend *does* supply one, we log its identity instead:

```java
m_backend.existingSubsystem().ifPresent(s ->
    PumpkinLog.get().put("Pumpkin/Drive/RequirementOwner", "backend:" + s.getName()));
```

`Pumpkin/Drive/RequirementOwner` is a **CRITICAL**-level string topic, so it is present in the match log even at the most aggressive log-filter setting. A double-registration — the classic "I made `Drive extends SubsystemBase` *and* let PumpkinLib synthesize one" mistake — shows up as a `DriveSelfCheck` failure at boot (§3.8 check 8) and as a visible owner name in post-match triage, instead of as a default command that mysteriously never runs.

```java
package org.pumpkinlib.drive;

public final class PumpkinDrive {

  // ---------- construction -------------------------------------------------
  public static PumpkinDrive of(DriveBackend backend) { return of(backend, PumpkinDriveConfig.competition()); }
  public static PumpkinDrive of(DriveBackend backend, PumpkinDriveConfig config);

  /** CTRE Tuner-X generated swerve. `drivetrain` is your CommandSwerveDrivetrain. */
  public static PumpkinDrive fromCtre(SwerveDrivetrain<?, ?, ?> drivetrain, Subsystem requirement,
                                      DriveGeometry geometry, DriveLimits limits);
  /** Convenience for the exact Tuner-X shape 8793 uses. */
  public static PumpkinDrive fromTunerX(Object commandSwerveDrivetrain);   // reflective; see 3.4.1

  public static PumpkinDrive fromAdvantageKit(AdvantageKitSwerveBackend.Adapter adapter);
  public static PumpkinDrive fromYagsl(java.io.File deploySwerveDir);
  public static PumpkinDrive fromYagsl(Object yagslSwerveDrive);
  public static PumpkinDrive swerve(HandRolledSwerveConfig cfg);
  public static PumpkinDrive differential(DifferentialConfig cfg);

  // ---------- state (pass-through) -----------------------------------------
  public Subsystem requirement();
  public DriveGeometry geometry();
  public DriveLimits limits();
  public Pose2d getPose();
  public Rotation2d getHeading();                 // blue-origin field heading
  public ChassisSpeeds getRobotRelativeSpeeds();
  public ChassisSpeeds getFieldRelativeSpeeds();  // fromRobotRelativeSpeeds(speeds, getHeading())
  public SwerveModuleState[] getModuleStates();
  public SwerveModulePosition[] getModulePositions();
  public SkidReport skid();

  // ---------- THE FUNNEL ---------------------------------------------------
  /**
   * Every driver input, every trajectory follower, every align command, every auto,
   * in every backend, goes through exactly this method. Order of operations:
   *   1. sanitize   (NaN guard, desaturate to limits)
   *   2. traction   (SETPOINT_GENERATOR | SLEW_RATE | none)
   *   3. discretize (ONLY if traction != SETPOINT_GENERATOR AND policy == FUNNEL)
   *   4. backend.applyRobotRelative(speeds, forces)
   *   5. log        (Pumpkin/Drive/Commanded*, Pumpkin/Drive/Traction*)
   */
  public void driveRobotRelative(ChassisSpeeds robotRelative, WheelForces forces);
  public void driveRobotRelative(ChassisSpeeds robotRelative);
  /** Converts using the BLUE-origin heading. This is never alliance-aware — see §4. */
  public void driveFieldRelative(ChassisSpeeds blueFieldRelative, WheelForces forces);
  public void driveFieldRelative(ChassisSpeeds blueFieldRelative);
  public void stop();
  public void brake();

  // ---------- pose ---------------------------------------------------------
  public void resetPose(Pose2d bluePose);
  public void addVisionMeasurement(VisionObservation obs);

  // ---------- lifecycle ----------------------------------------------------
  /** Call from robotPeriodic. Runs skid detection, alliance perspective, logging, alerts. */
  public void periodic();

  // ---------- characterization hooks (used by PumpkinCharacterization) ------
  public void runCharacterizationVolts(double volts);
  public double[] getWheelRadiusCharacterizationPositionsRad();
  public double getFFCharacterizationVelocityRadPerSec();

  // ---------- escape hatches -----------------------------------------------
  public DriveBackend backend();
  public <T> T raw(Class<T> type);                // throws IllegalStateException with a fix message
  public TractionLayer traction();                // null when TractionMode.NONE
}
```

**Funnel implementation, verbatim-quality:**

```java
public void driveRobotRelative(ChassisSpeeds desired, WheelForces forces) {
  if (Double.isNaN(desired.vxMetersPerSecond) || Double.isNaN(desired.vyMetersPerSecond)
      || Double.isNaN(desired.omegaRadiansPerSecond)) {
    m_nanAlert.set(true);                       // "Drive received NaN speeds. Check your input suppliers."
    m_backend.stop();
    return;
  }
  ChassisSpeeds speeds = desired;
  WheelForces ff = forces;

  switch (m_config.traction()) {
    case SETPOINT_GENERATOR -> {
      // PathPlanner's SwerveSetpointGenerator discretizes INTERNALLY. Its docs explicitly say
      // "do not discretize speeds before or after using the setpoint generator." So we do not.
      TractionLayer.Result r = m_traction.apply(speeds, m_config.loopPeriodSeconds());
      speeds = r.speeds();
      if (!ff.isPresent()) ff = r.feedforwards();   // path FF wins when the planner supplied one
    }
    case SLEW_RATE -> {
      speeds = m_traction.applySlew(speeds, m_config.loopPeriodSeconds());
      if (m_backend.discretization() == DiscretizationPolicy.FUNNEL) {
        speeds = ChassisSpeeds.discretize(speeds, m_config.loopPeriodSeconds());
      }
    }
    case NONE -> {
      if (m_backend.discretization() == DiscretizationPolicy.FUNNEL) {
        speeds = ChassisSpeeds.discretize(speeds, m_config.loopPeriodSeconds());
      }
    }
  }

  m_backend.applyRobotRelative(speeds, ff);

  PumpkinLog.get().put("Pumpkin/Drive/Commanded", speeds);
  PumpkinLog.get().put("Pumpkin/Drive/CommandedRaw", desired);
  PumpkinLog.get().put("Pumpkin/Drive/FeedforwardPresent", ff.isPresent());
}
```

**This single method is the whole architectural argument for PumpkinLib's drivetrain layer.** Slip limiting, discretization correctness, feedforward plumbing, and NaN safety are applied to teleop and auto identically, on every backend, for free. Most teams get slip limiting only in auto because PathPlanner applies it inside `FollowPathCommand`; here the driver gets it too, which is where a small team feels the biggest gain (`deep-drivetrain.json`, TractionLayer capability).

### 3.4 The backends

#### 3.4.1 `CtreSwerveBackend` — CTRE Tuner-X generated swerve (8793's stack)

```java
package org.pumpkinlib.drive.backend;

public final class CtreSwerveBackend implements DriveBackend {

  private final SwerveDrivetrain<?, ?, ?> m_dt;
  private final Subsystem m_requirement;
  private final SwerveRequest.ApplyRobotSpeeds m_apply =
      new SwerveRequest.ApplyRobotSpeeds()
          .withDriveRequestType(SwerveModule.DriveRequestType.Velocity);
  private final SwerveRequest.SwerveDriveBrake m_brake = new SwerveRequest.SwerveDriveBrake();
  private final SwerveRequest.Idle m_idle = new SwerveRequest.Idle();
  private final SysIdSwerveTranslation m_charReq = new SysIdSwerveTranslation();

  /** `requirement` is your CommandSwerveDrivetrain itself in the Tuner-X shape. May be null:
   *  PumpkinDrive then synthesizes one (§3.3.1). */
  public CtreSwerveBackend(SwerveDrivetrain<?, ?, ?> dt, Subsystem requirement,
                           DriveGeometry geometry, DriveLimits limits) { /* ... */ }

  @Override public Optional<Subsystem> existingSubsystem() { return Optional.ofNullable(m_requirement); }

  /** ApplyRobotSpeeds does NOT discretize. This is the documented CTRE footgun. */
  @Override public DiscretizationPolicy discretization() { return DiscretizationPolicy.FUNNEL; }

  @Override public void applyRobotRelative(ChassisSpeeds speeds, WheelForces ff) {
    m_dt.setControl(m_apply.withSpeeds(speeds)
        .withWheelForceFeedforwardsX(ff.xNewtons())
        .withWheelForceFeedforwardsY(ff.yNewtons()));
  }

  @Override public void brake() { m_dt.setControl(m_brake); }
  @Override public void stop()  { m_dt.setControl(m_idle);  }

  @Override public Pose2d getPose()  { return m_dt.getState().Pose; }
  @Override public ChassisSpeeds getRobotRelativeSpeeds() { return m_dt.getState().Speeds; }
  @Override public SwerveModuleState[] getModuleStates()  { return m_dt.getState().ModuleStates; }
  @Override public SwerveModulePosition[] getModulePositions() { return m_dt.getState().ModulePositions; }

  @Override public void resetPose(Pose2d bluePose) { m_dt.resetPose(bluePose); }

  /** CRITICAL: CTRE's estimator runs on the Phoenix timebase, not FPGA. */
  @Override public void addVisionMeasurement(Pose2d p, double fpgaSeconds, Matrix<N3, N1> sd) {
    m_dt.addVisionMeasurement(p, Utils.fpgaToCurrentTime(fpgaSeconds), sd);
  }

  @Override public void setOperatorPerspectiveForward(Rotation2d fwd) {
    m_dt.setOperatorPerspectiveForward(fwd);
  }

  @Override public void runCharacterizationVolts(double volts) {
    m_dt.setControl(m_charReq.withVolts(Volts.of(volts)));
  }

  @Override public Object raw() { return m_dt; }
}
```

**Notes and guards:**
- 8793 already overrides `addVisionMeasurement` in `CommandSwerveDrivetrain` to do the FPGA→Phoenix conversion. If the wrapped object already converts, we would double-convert. Guard: `CtreSwerveBackend.Builder.assumeCallerConvertsTimestamps(boolean)`, default `false`, and a boot-time `Alert` that names the file to check.
- `getWheelRadiusCharacterizationPositionsRad()` reads `m_dt.getModule(i).getDriveMotor().getPosition().getValueAsDouble()` × 2π ÷ (gear ratio), using the ratio the team supplies in `DriveGeometry`. **[UNVERIFIED]** — `SwerveDrivetrain.getModule(int)` returning a `SwerveModule` with `getDriveMotor()` is the 26.x shape per CTRE's swerve API; confirm the accessor name against `api.ctr-electronics.com/phoenix6/stable` before implementing. Fallback that is definitely correct: read `getState().ModulePositions[i].distanceMeters / wheelRadiusMeters` — this is in radians of wheel rotation and needs no vendor accessor at all. **Ship the fallback.**
- Sim: `updateSimState` must be driven from a **4 ms `Notifier`**, not the 20 ms loop. `CtreSwerveBackend.startSimThread()` does this; if the team's `CommandSwerveDrivetrain` already starts one (8793's does), we detect the duplicate by a static registry keyed on the drivetrain identity and skip, logging `Pumpkin/Drive/SimThread = "external"`.
- 2027: `ApplyRobotSpeeds` becomes `ApplyRobotVelocity` in Phoenix 6 26.50+. This is the *only* file in PumpkinLib that names it.

#### 3.4.2 `AdvantageKitSwerveBackend` — 6328 template (the 9143/template stack)

We do **not** wrap the template. The template is copy-and-own; wrapping it inherits its maintenance. We adapt it by method reference so the team keeps ownership of `Drive.java`.

```java
public final class AdvantageKitSwerveBackend implements DriveBackend {

  /** Every method the 6328 template's Drive.java already has. */
  public record Adapter(
      Subsystem requirement,
      Supplier<Pose2d> getPose,
      Consumer<Pose2d> setPose,
      Supplier<ChassisSpeeds> getChassisSpeeds,
      Consumer<ChassisSpeeds> runVelocity,               // Drive::runVelocity
      Runnable stop,
      Runnable stopWithX,                                // Drive::stopWithX
      DoubleConsumer runCharacterization,                // Drive::runCharacterization
      Supplier<double[]> wheelRadiusPositions,           // Drive::getWheelRadiusCharacterizationPositions
      DoubleSupplier ffCharacterizationVelocity,         // Drive::getFFCharacterizationVelocity
      Supplier<SwerveModuleState[]> moduleStates,
      Supplier<SwerveModulePosition[]> modulePositions,
      Supplier<Rotation2d> rotation,
      VisionSink addVisionMeasurement,                   // Drive::addVisionMeasurement
      DriveGeometry geometry,
      DriveLimits limits) {

    public interface VisionSink { void accept(Pose2d pose, double timestamp, Matrix<N3, N1> stdDevs); }

    /** Reflective auto-wire for the unmodified 6328 template. Falls back with a named Alert. */
    public static Adapter fromTemplateDrive(Subsystem drive);
  }

  /** The 6328 template's Drive extends SubsystemBase, so this is always present. */
  @Override public Optional<Subsystem> existingSubsystem() { return Optional.ofNullable(m_a.requirement()); }

  /** The template's runVelocity() discretizes internally. */
  @Override public DiscretizationPolicy discretization() { return DiscretizationPolicy.BACKEND; }

  /** The template's runVelocity takes no feedforwards. Forces are logged and dropped, with a
   *  one-time Alert naming the 8-line patch that adds them. */
  @Override public void applyRobotRelative(ChassisSpeeds speeds, WheelForces ff) {
    m_a.runVelocity().accept(speeds);
    if (ff.isPresent() && !m_warnedFf) {
      PumpkinAlerts.warn("drive.ak.noWheelForces",
          "Path wheel-force feedforwards are being dropped. Add a runVelocity(ChassisSpeeds, "
        + "double[] fx, double[] fy) overload to Drive.java and pass it to "
        + "AdvantageKitSwerveBackend.Adapter to recover ~10-15% path accuracy under acceleration.");
      m_warnedFf = true;
    }
  }
}
```

`Adapter.withFeedforwards(TriConsumer<ChassisSpeeds, double[], double[]>)` upgrades it once the team patches `Drive.java`.

#### 3.4.3 `YagslBackend`

```java
public final class YagslBackend implements DriveBackend {
  /** Constructed from the YAGSL deploy directory, or from an existing swervelib.SwerveDrive. */
  public static YagslBackend fromDeployDirectory(java.io.File deploySwerveDir);
  public static YagslBackend wrap(Object swerveDrive);   // reflective; keeps YAGSL compileOnly

  /** YAGSL's SwerveDrive.drive(ChassisSpeeds) desaturates but does NOT discretize. */
  @Override public DiscretizationPolicy discretization() { return DiscretizationPolicy.FUNNEL; }

  /** swervelib.SwerveDrive is NOT a Subsystem. PumpkinDrive synthesizes the requirement (§3.3.1).
   *  If your team wrapped it in your own `SwerveSubsystem extends SubsystemBase` (the YAGSL
   *  example-project shape), pass that in so we use yours instead of making a second one. */
  @Override public Optional<Subsystem> existingSubsystem() { return Optional.ofNullable(m_userSubsystem); }
  public YagslBackend withSubsystem(Subsystem s) { m_userSubsystem = s; return this; }
}
```

We deliberately do **not** re-expose `SwerveInputStream`. YAGSL's input DSL is the best in FRC and is exactly why `DriveInputStream` (§3.6) exists — ported to be backend-agnostic so a CTRE or AdvantageKit team gets the same ergonomics.

**Boot-time Alert (informational, not an error):** if the backend is YAGSL *and* every device is CTRE, log
> `YAGSL detected with an all-CTRE drivetrain. YAGSL's own docs recommend Tuner X for this hardware, and YAGSL's odometry runs at the 20 ms loop rate rather than a 100-250 Hz thread. Switching to PumpkinDrive.fromTunerX(...) is a one-line change and typically cuts odometry std dev by ~80%.`

#### 3.4.4 `HandRolledSwerveBackend`

The value here is that PumpkinLib **forces the correct order of operations** so a team cannot get it wrong. WPILib 2026 silently changed `SwerveModuleState.optimize` to a *void mutating instance method* and deprecated the static; teams that half-migrate delete the call and lose optimization.

```java
public record HandRolledSwerveConfig(
    SwerveModuleIO[] modules,          // PumpkinLib module order, length 4
    GyroIO gyro,
    DriveGeometry geometry,
    DriveLimits limits,
    Matrix<N3, N1> odometryStdDevs,    // default VecBuilder.fill(0.1, 0.1, 0.1)
    Matrix<N3, N1> visionStdDevs) {}   // default VecBuilder.fill(0.9, 0.9, 9_999_999)

public final class HandRolledSwerveBackend implements DriveBackend {

  @Override public DiscretizationPolicy discretization() { return DiscretizationPolicy.FUNNEL; }

  @Override public void applyRobotRelative(ChassisSpeeds speeds, WheelForces ff) {
    // Speeds arrive ALREADY discretized by the funnel. Do not discretize again.
    SwerveModuleState[] states = m_kinematics.toSwerveModuleStates(speeds);
    SwerveDriveKinematics.desaturateWheelSpeeds(states, m_limits.maxLinearVelocityMps());
    for (int i = 0; i < states.length; i++) {
      Rotation2d current = m_modules[i].getSteerAngle();
      states[i].optimize(current);        // WPILib 2026: MUTATING INSTANCE METHOD, returns void
      states[i].cosineScale(current);     // WPILib 2026: MUTATING INSTANCE METHOD, returns void
      m_modules[i].setState(states[i], ff.xNewtons()[i], ff.yNewtons()[i]);
    }
  }
}
```

`SwerveModuleIO` / `GyroIO` are PumpkinLib interfaces shaped to be `@AutoLog`-compatible so an AdvantageKit team can drop them in and keep replay. They are defined in the mechanism/hardware domain; this domain only consumes them.

#### 3.4.5 `DifferentialBackend` — first-class tank

Rookie teams run tank, the project brief targets small teams, and **no** maintained swerve library covers it. Differential gets the same autos, the same alliance handling, the same vision fusion, the same characterization, the same drive-to-pose (translation-constrained), and the same auto testing.

```java
public record DifferentialConfig(
    DifferentialDriveKinematics kinematics,
    DoubleConsumer leftVelocityMps,      // your subsystem's closed-loop velocity setter
    DoubleConsumer rightVelocityMps,
    DoubleSupplier leftPositionMeters,
    DoubleSupplier rightPositionMeters,
    DoubleSupplier leftVelocityMpsGetter,
    DoubleSupplier rightVelocityMpsGetter,
    Supplier<Rotation2d> gyro,
    @Nullable Subsystem requirement,     // null is legal: PumpkinDrive synthesizes one (§3.3.1)
    DriveGeometry geometry,
    DriveLimits limits,
    SimpleMotorFeedforward feedforward) {}

public final class DifferentialBackend implements DriveBackend {

  private final DifferentialDrivePoseEstimator m_estimator;

  @Override public DiscretizationPolicy discretization() { return DiscretizationPolicy.BACKEND; }
  // Differential kinematics has no strafe channel; discretization is a no-op. We declare BACKEND
  // so the funnel never wastes a call.

  @Override public void applyRobotRelative(ChassisSpeeds speeds, WheelForces ff) {
    DifferentialDriveWheelSpeeds w = m_cfg.kinematics().toWheelSpeeds(speeds);
    w.desaturate(m_cfg.limits().maxLinearVelocityMps());
    m_cfg.leftVelocityMps().accept(w.leftMetersPerSecond);
    m_cfg.rightVelocityMps().accept(w.rightMetersPerSecond);
  }

  @Override public SwerveModuleState[] getModuleStates() { return new SwerveModuleState[0]; }
  @Override public void brake() { m_cfg.leftVelocityMps().accept(0); m_cfg.rightVelocityMps().accept(0); }
}
```

Differential path following uses **`PPLTVController`** (PathPlanner) and, for hand-rolled following, **`LTVUnicycleController`** — never `RamseteController` (deprecated for removal) and never `RamseteCommand` / `SwerveControllerCommand` / `MecanumControllerCommand` (all three **removed in WPILib 2027**). `LTVUnicycleController` guardrails to respect: default tolerances x=0.0625 m, y=0.125 m, heading=2 rad; default max velocity 9 m/s; throws `IllegalArgumentException` if `maxVelocity <= 0 || >= 15`.

Mecanum: `MecanumBackend` ships in v0.2 with the same shape (`MecanumDriveKinematics` + `MecanumDrivePoseEstimator`). It is deferred, not designed out.

### 3.5 What PumpkinLib actually layers on top

| Layer | Where it lives | Off-the-shelf equivalent | Why we add it |
|---|---|---|---|
| Slip/jerk limiting in **teleop** | `TractionLayer` in the funnel | PathPlanner `SwerveSetpointGenerator` (auto only, by default) | Same generator, applied to driver input. Biggest visible small-team gain. |
| Discretization correctness | `DiscretizationPolicy` + funnel | none | #1 cause of "arcs sideways while rotating"; mirror bug is double-discretization. |
| Skid detection → odometry trust | `SkidDetector` | 1690's algorithm (video only), 135 Consul (unpublished) | No library ships it. See §7.4. |
| Heading lock | `HeadingController` | CTRE `FieldCentricFacingAngle`, YAGSL `headingWhile` | Backend-agnostic, one tuning, `ProfiledPIDController` not raw P. |
| Alliance-aware field-centric | `AlliancePerspective` | CTRE only | See §4 — the entire point. |
| Drive-to-pose | `PumpkinDriveToPose` | 6328's `DriveToPose` (copy-paste) | Packaged, tunable, tolerance/timeout/abort. §8. |
| Wheel-radius characterization | `PumpkinCharacterization` | 6328 (copy-paste), 4738 (copy-paste) | Packaged + writes back to `settings.json`. §7. |
| Setpoint-generator inputs | `PumpkinCharacterization.momentOfInertia` / `.slipCurrent` | nothing | Everyone copies MOI 6.883 from the 6328 template. |

### 3.6 `DriveInputStream` — the driver-feel DSL

Backend-agnostic port of the best idea in YAGSL. Every method returns `this`.

```java
package org.pumpkinlib.drive.input;

public final class DriveInputStream implements Supplier<ChassisSpeeds> {

  public static DriveInputStream of(PumpkinDrive drive, DoubleSupplier x, DoubleSupplier y);

  // shaping
  /** RADIAL deadband on the (x, y) vector — PumpkinMath.deadband2d (§2). There is no 2-D
   *  applyDeadband overload in WPILib 2026.2.2 MathUtil; only applyDeadband(double, double) and
   *  applyDeadband(double, double, double) exist, and per-axis deadbanding a joystick vector
   *  produces the square-corner feel students complain about. The rotation channel uses the
   *  scalar MathUtil.applyDeadband(omega, fraction). */
  public DriveInputStream deadband(double fraction);
  /** MathUtil.copySignPow(value, exponent) — real in WPILib 2026.2.2, used directly, not wrapped. */
  public DriveInputStream expo(double translationPow, double rotationPow);
  public DriveInputStream scaleTranslation(double s);
  public DriveInputStream scaleRotation(double s);
  public DriveInputStream scaleTranslation(DoubleSupplier s); // for speed modes (8793 pattern)
  public DriveInputStream slewRate(double linearMpsPerSec, double angularRadPerSecSq); // OFF by default

  // rotation channel — pick exactly one
  public DriveInputStream withRotationAxis(DoubleSupplier omega);
  public DriveInputStream withHeadingAxis(DoubleSupplier hx, DoubleSupplier hy);

  // frames
  public DriveInputStream robotRelative(BooleanSupplier b);
  /** Field-centric using the OPERATOR perspective (alliance-aware). Default: true. */
  public DriveInputStream allianceRelative(BooleanSupplier b);

  // overrides, highest priority last
  public DriveInputStream headingLock(BooleanSupplier b);
  public DriveInputStream aimAt(Supplier<Translation2d> blueTarget, BooleanSupplier b);
  public DriveInputStream translationOnly(BooleanSupplier b);
  public DriveInputStream omegaOverride(Supplier<OptionalDouble> radPerSec);  // score-on-the-move

  @Override public ChassisSpeeds get();       // ROBOT-RELATIVE, ready for the funnel
  public Command asDefaultCommand();          // run(() -> drive.driveRobotRelative(get())) + requirement
}
```

Default heading controller (mirrors the proven 6328 tuning, exposed as `Tunable`s):

```java
new ProfiledPIDController(5.0, 0.0, 0.4, new TrapezoidProfile.Constraints(8.0, 20.0));
controller.enableContinuousInput(-Math.PI, Math.PI);
```

`slewRate` ships **off**. REV removed the slew limiter from the MAXSwerve template because MAXSwerve 2.0 wheels no longer need it; slew limiting is a traction band-aid that adds latency the driver feels. `TractionMode.SETPOINT_GENERATOR` is the correct fix and is what `competition()` enables.

### 3.7 `PumpkinDriveConfig`

```java
public record PumpkinDriveConfig(
    OdometryMode odometry,
    TractionMode traction,
    double loopPeriodSeconds,
    boolean skidDetection,
    boolean logModuleStates,
    double maxModuleSteerVelocityRadPerSec) {   // for SwerveSetpointGenerator

  public static PumpkinDriveConfig competition() {
    return new PumpkinDriveConfig(OdometryMode.NATIVE_250HZ, TractionMode.SETPOINT_GENERATOR,
                                  0.02, true, true, Units.rotationsToRadians(10.0));
  }
  public static PumpkinDriveConfig rookie() {
    return new PumpkinDriveConfig(OdometryMode.THREADED_100HZ, TractionMode.NONE,
                                  0.02, false, true, Units.rotationsToRadians(10.0));
  }
  public static PumpkinDriveConfig tank() {
    return new PumpkinDriveConfig(OdometryMode.LOOP_50HZ, TractionMode.NONE,
                                  0.02, false, false, 0.0);
  }
}
```

`TractionMode.SETPOINT_GENERATOR` requires a PathPlanner `RobotConfig`. If `RobotConfig.fromGUISettings()` fails or `hasValidConfig()` is false, we **downgrade to `NONE`** and raise:

> `Traction control disabled: deploy/pathplanner/settings.json is missing or invalid. Open the PathPlanner GUI once to generate it, then run PumpkinCharacterization.all(drive) to fill in mass, MOI and wheel COF.`

Similarly `NATIVE_250HZ` without a CANivore degrades to `THREADED_100HZ` with a logged warning — never silently underperform.

### 3.8 Boot-time consistency check (`assertConsistent`)

8793 has **three disagreeing sources of truth** for drivetrain geometry (`tuner-project.json` vs `TunerConstants.java` vs `deploy/pathplanner/settings.json`); 9143-B's `kSpeedAt12Volts = 5.96` disagrees with `settings.json` `maxDriveSpeed = 5.364` and its stator limit 60 A disagrees with `driveCurrentLimit = 100`. This is a silent-wrong-auto bug class.

```java
package org.pumpkinlib.drive;

public final class DriveSelfCheck {
  /** Called automatically from PumpkinDrive.of(...). ~40 lines. Never throws. */
  public static List<String> run(PumpkinDrive drive);
}
```

Checks, each producing one `Alert` naming both values and both files:
1. `DriveGeometry.wheelRadiusMeters` vs `RobotConfig.moduleConfig.wheelRadiusMeters` (tol 1 mm)
2. `DriveGeometry.moduleLocations` vs `RobotConfig.moduleLocations` (tol 5 mm)
3. `DriveLimits.maxLinearVelocityMps` vs `RobotConfig.moduleConfig.maxDriveVelocityMPS` (tol 2 %)
4. `RobotConfig.hasValidConfig()`
5. Module order sanity: FL is +x/+y, FR is +x/−y, BL is −x/+y, BR is −x/−y — a wrong permutation is otherwise invisible until the robot rotates.
6. `deploy/pathplanner/navgrid.json` exists if pathfinding is enabled (it is only created by opening the PathPlanner GUI once — a Choreo-primary team will otherwise fail at the event).
7. If a `.chor` project is present, its robot-config block vs `RobotConfig` (tol 2 %).
8. **Exactly one `Subsystem` claims the drive hardware.** We walk `CommandScheduler.getInstance()`'s registered subsystems and count those that are (a) the backend's `existingSubsystem()`, (b) `PumpkinDrive`'s synthetic requirement, or (c) any registered subsystem whose `getName()` matches the backend's raw object's class simple name. More than one is a hard `Alert`:

   > `Two subsystems claim the drivetrain: "CommandSwerveDrivetrain" (from CtreSwerveBackend) and "DriveSubsystem" (registered separately). Default commands and requirement-based cancellation will behave unpredictably. Pass the one you want into the backend constructor and delete the other.`

   The winning owner is logged once at boot as `Pumpkin/Drive/RequirementOwner` (String, **CRITICAL**) so a double-registration is visible in a match log without reproducing it in the shop.

---

## 4. The alliance-flip trap — one correct, centrally-enforced answer

This is the highest value-per-line item in the whole domain and it costs almost nothing to build.

### 4.1 The three concepts teams conflate

| Concept | Who uses it | What it means | PumpkinLib API |
|---|---|---|---|
| **Pose origin** | odometry, vision, autos, every `Pose2d` in the library | **Always blue.** Never flips. Ever. | implicit — every `Pose2d` in a PumpkinLib signature is blue-origin |
| **Operator perspective** | the human driver's "forward" | `kZero` on blue, `k180deg` on red | `AlliancePerspective.operatorForward()` |
| **Field geometry transform** | authored blue poses → this-alliance poses; trajectory flipping | rotate 180° (rotational field) or mirror over the midline (mirrored field) | `PumpkinField.apply(Pose2d)` |

Every red-alliance-only bug in FRC comes from mixing two of these three.

### 4.2 API

```java
package org.pumpkinlib.field;

public enum FieldSymmetry { ROTATIONAL, MIRRORED }

public final class PumpkinField {

  /** Call once in robotInit, before anything else. Defaults to FieldMap.symmetry() for the year. */
  public static void configure(FieldSymmetry symmetry);
  public static FieldSymmetry symmetry();

  /** True when the DS reports Red. Cached per loop; NEVER polled inside a hot loop.
   *  Defaults to false (Blue) when the DS has not reported, which is the safe shop default. */
  public static boolean isRed();

  /** BLUE-authored pose -> this-alliance pose. Identity on blue. */
  public static Pose2d apply(Pose2d bluePose);
  public static Translation2d apply(Translation2d blueTranslation);
  public static Rotation2d apply(Rotation2d blueRotation);
  /** Explicit, no alliance lookup. Used by trajectory sources and tests. */
  public static Pose2d flip(Pose2d bluePose);
  public static Pose2d mirrorAboutMidline(Pose2d bluePose);   // driver-perspective left<->right variant

  /** Called once per loop from PumpkinDrive.periodic(). Idempotent and cheap. */
  static void updateCache();
}

/** Blue/red pair with a single accessor. Replaces the 4x copy-pasted alliance block in 8793. */
public final class AllianceValue<T> {
  public static <T> AllianceValue<T> of(T blue, T red);
  /** Derives red from blue using the configured field symmetry. Only for Pose2d/Translation2d. */
  public static AllianceValue<Pose2d> mirrored(Pose2d blue);
  public T get();          // alliance-selected, cached
  public T blue();
  public T red();
}

public final class AlliancePerspective {

  /** Call from robotPeriodic (PumpkinDrive.periodic() does it for you). */
  public static void update(PumpkinDrive drive);

  public static Rotation2d operatorForward();   // Rotation2d.kZero | Rotation2d.k180deg

  /**
   * The driver-facing "zero the gyro" button. Resets the OPERATOR PERSPECTIVE ONLY.
   * It does NOT touch the pose. Binding this to a button can never corrupt an auto or vision.
   */
  public static Command zeroDriverHeading(PumpkinDrive drive);

  /**
   * Absolute, blue-origin heading reset. For autos, vision seeding, and the pit only.
   * Deliberately NOT a Command — it must be awkward to bind to a driver button.
   */
  public static void resetFieldRotation(PumpkinDrive drive, Rotation2d blueHeading);
}
```

### 4.3 How it is made impossible to get wrong

1. **Names carry the frame.** Every parameter that is blue-origin is named `bluePose`, `blueTarget`, `blueHeading`. Anything without the prefix is alliance-corrected. There is no unprefixed `Pose2d` parameter anywhere in a PumpkinLib public API.
2. **`driveFieldRelative(ChassisSpeeds)` is blue-origin and documented as such.** The alliance-aware path is `DriveInputStream.allianceRelative(...)`, which applies `operatorForward()` and nothing else.
3. **`resetPose` never flips.** Autos call `trajectory.resetOdometry()`, which flips the *trajectory's* start pose using the source's own flipping and then calls `resetPose` with a blue pose.
4. **One flip supplier, library-wide.** `PumpkinAuto` passes `PumpkinField::isRed` to `AutoBuilder.configure(...)` as `shouldFlipPath`, and `true` to `new AutoFactory(..., useAllianceFlipping, ...)`. A team never writes the lambda.
5. **Symmetry is declared once per season, in one place**, and both libraries are configured from it:
   ```java
   PumpkinField.configure(FieldSymmetry.ROTATIONAL);
   // internally, once:
   FlippingUtil.symmetryType = FlippingUtil.FieldSymmetry.kRotational;  // PathPlanner
   FlippingUtil.fieldSizeX = FieldMap.lengthMeters();
   FlippingUtil.fieldSizeY = FieldMap.widthMeters();
   ```
   Note PathPlanner's `FlippingUtil.flipFeedforwardXs/Ys` and `DriveFeedforwards.flip()` **only do anything under mirrored symmetry** — under rotational symmetry the robot-relative forces are unchanged. PumpkinLib's `WheelForces` conversion respects this automatically because it converts *after* PathPlanner has already flipped.
6. **Startup verification, printed to the log and rendered in AdvantageScope:**
   ```java
   PumpkinAuto.verifyAlliance();
   // logs Pumpkin/Auto/Verify/<autoName>/BlueStart and /RedStart as Pose2d
   // logs Pumpkin/Auto/Verify/<autoName>/BluePath and /RedPath as Pose2d[]
   ```
   Ten seconds of eyeballing in AdvantageScope before an event replaces the entire class of red-alliance-only bugs. This runs automatically for every registered auto during `robotInit` when `TUNING_MODE` is on, and on demand otherwise.

### 4.4 The one thing we forbid

**Duplicated per-alliance trajectory files.** 2910 shipped `BLUE_*` / `RED_*` `.traj` pairs; that doubles regeneration cost and guarantees drift. `PumpkinTrajectory` refuses to load a trajectory whose name starts with `BLUE_` or `RED_` and raises:

> `Trajectory "RED_ScoreLeft" looks alliance-specific. PumpkinLib flips at runtime — author on blue only. Rename to "ScoreLeft" and delete the red variant.`

---

## 5. Path following — one auto API over PathPlanner **and** Choreo

### 5.1 `PumpkinAuto` — three lines of config

```java
package org.pumpkinlib.auto;

public final class PumpkinAuto {

  public static PumpkinAuto of(PumpkinDrive drive);

  /** RobotConfig.fromGUISettings() + AutoBuilder.configure(...) with the correct overload. */
  public PumpkinAuto withPathPlanner();
  public PumpkinAuto withPathPlanner(PIDConstants translation, PIDConstants rotation);
  public PumpkinAuto withPathPlanner(RobotConfig cfg, PIDConstants translation, PIDConstants rotation);

  /** Builds a ChoreoLib AutoFactory bound to the SAME PumpkinDrive. */
  public PumpkinAuto withChoreo();

  /** LocalADStar, or LocalADStarAK when AdvantageKit is on the classpath. Warms up at init. */
  public PumpkinAuto withPathfinding();

  /** Registers a GoalBus so trajectory triggers can fire mechanism requests safely. */
  public <G extends Enum<G>> PumpkinAuto withGoals(GoalBus<G> bus);

  /** Registers named commands into BOTH PathPlanner NamedCommands and Choreo factory.bind(). */
  public PumpkinAuto action(String name, Command command);
  public PumpkinAuto actions(Map<String, Command> commands);

  // ---- trajectory resolution ------------------------------------------------
  /** Resolves from Choreo first, then PathPlanner. Fails loudly and names both search paths. */
  public PumpkinTrajectory traj(String name);
  public PumpkinTrajectory traj(String name, int splitIndex);
  public PumpkinTrajectory choreo(String name);
  public PumpkinTrajectory choreo(String name, int splitIndex);
  public PumpkinTrajectory pathplanner(String pathName);

  // ---- routines -------------------------------------------------------------
  public PumpkinAutoRoutine routine(String name);

  // ---- lifecycle ------------------------------------------------------------
  /** Loads and JIT-warms every trajectory and every registered auto. Call from robotInit. */
  public void warmup();
  public void verifyAlliance();

  // ---- escape hatches -------------------------------------------------------
  public boolean pathPlannerConfigured();
  public Object choreoFactory();          // the real AutoFactory, cast at your own risk
  public SendableChooser<Command> pathPlannerChooser();   // AutoBuilder.buildAutoChooser()
}
```

### 5.2 PathPlanner wiring — exactly what `withPathPlanner()` does

Order is load-bearing. `NamedCommands.registerCommand(...)` must run **before** `AutoBuilder.configure(...)` (8793's `RobotContainer.java` has an explicit comment about this).

```java
private void configurePathPlanner(RobotConfig cfg, PIDConstants trans, PIDConstants rot) {

  // 1. Named commands FIRST. Every one is wrapped so it cannot cancel the auto group.
  m_actions.forEach((name, cmd) -> NamedCommands.registerCommand(name, wrapAction(name, cmd)));

  // 2. Field symmetry, once, from PumpkinField.
  FlippingUtil.symmetryType = (PumpkinField.symmetry() == FieldSymmetry.ROTATIONAL)
      ? FlippingUtil.FieldSymmetry.kRotational : FlippingUtil.FieldSymmetry.kMirrored;
  FlippingUtil.fieldSizeX = FieldMap.lengthMeters();
  FlippingUtil.fieldSizeY = FieldMap.widthMeters();

  // 3. The right overload for the drivetrain type.
  if (m_drive.geometry().holonomic()) {
    AutoBuilder.configure(
        m_drive::getPose,
        m_drive::resetPose,
        m_drive::getRobotRelativeSpeeds,
        (speeds, ff) -> m_drive.driveRobotRelative(
            speeds,
            new WheelForces(ff.robotRelativeForcesXNewtons(), ff.robotRelativeForcesYNewtons())),
        new PPHolonomicDriveController(trans, rot),
        cfg,
        PumpkinField::isRed,                   // <- the ONE flip supplier
        m_drive.requirement());
  } else {
    AutoBuilder.configure(
        m_drive::getPose,
        m_drive::resetPose,
        m_drive::getRobotRelativeSpeeds,
        (Consumer<ChassisSpeeds>) m_drive::driveRobotRelative,
        new PPLTVController(m_config.loopPeriodSeconds()),
        cfg,
        PumpkinField::isRed,
        m_drive.requirement());
  }

  // 4. Logging bridge -> PumpkinLog -> AdvantageScope.
  PathPlannerLogging.setLogActivePathCallback(
      poses -> PumpkinLog.get().put("Pumpkin/Auto/ActivePath", poses.toArray(new Pose2d[0])));
  PathPlannerLogging.setLogTargetPoseCallback(
      p -> PumpkinLog.get().put("Pumpkin/Auto/TargetPose", p));
  PathPlannerLogging.setLogCurrentPoseCallback(
      p -> PumpkinLog.get().put("Pumpkin/Auto/CurrentPose", p));
}
```

**`wrapAction` — the NamedCommands requirement-collision fix.** This is the single most common "my auto stops halfway" bug in FRC.

```java
private Command wrapAction(String name, Command cmd) {
  Set<Subsystem> collisions = new HashSet<>(cmd.getRequirements());
  collisions.retainAll(Set.of(m_drive.requirement()));
  if (!collisions.isEmpty()) {
    PumpkinAlerts.error("auto.namedCommand." + name,
        "NamedCommand \"" + name + "\" requires the drive subsystem. When PathPlanner triggers it "
      + "mid-path it will CANCEL the whole auto. Remove the drive requirement, or express this as "
      + "an auto step instead of an event marker.");
  }
  if (m_goalBus != null && cmd.getRequirements().stream().anyMatch(m_goalBus::owns)) {
    PumpkinAlerts.error("auto.namedCommand.goal." + name,
        "NamedCommand \"" + name + "\" requires a subsystem owned by the GoalBus. Use "
      + "goals.requestAsync(...) or goals.request(...) instead — those never take requirements.");
  }
  return cmd.withName("Pumpkin/Action/" + name);
}
```

### 5.3 Choreo wiring — exactly what `withChoreo()` does

```java
private void configureChoreo() {
  m_choreoFactory = new AutoFactory(
      m_drive::getPose,
      m_drive::resetPose,
      this::followChoreoSample,       // Consumer<SwerveSample> or Consumer<DifferentialSample>
      true,                           // useAllianceFlipping — ALWAYS true; PumpkinField owns policy
      m_drive.requirement(),
      (traj, starting) -> PumpkinLog.get().put(
          "Pumpkin/Auto/ChoreoTraj", traj.getPoses()));

  m_actions.forEach((name, cmd) -> m_choreoFactory.bind(name, wrapAction(name, cmd)));
}

/**
 * Choreo's SwerveSample carries FIELD-relative velocities (vx, vy) and per-module forces.
 * We run a PID feedback layer on top of the sample feedforward — this is exactly the
 * structure 6328 uses for its DriveTrajectory command.
 */
private void followChoreoSample(SwerveSample s) {
  Pose2d pose = m_drive.getPose();
  ChassisSpeeds fieldSpeeds = new ChassisSpeeds(
      s.vx + m_xController.calculate(pose.getX(), s.x),
      s.vy + m_yController.calculate(pose.getY(), s.y),
      s.omega + m_thetaController.calculate(pose.getRotation().getRadians(), s.heading));

  ChassisSpeeds robotRelative =
      ChassisSpeeds.fromFieldRelativeSpeeds(fieldSpeeds, pose.getRotation());

  m_drive.driveRobotRelative(robotRelative, new WheelForces(s.moduleForcesX(), s.moduleForcesY()));
}
```

Controllers, all `Tunable`:

```java
// Plain PIDController — CORRECT here and only here. A Choreo sample already carries the
// motion profile (position AND velocity), so these three are pure error feedback layered on top
// of the sample's feedforward. Adding a second profile would fight the trajectory.
m_xController     = new PIDController(kLinearP.get(), 0.0, kLinearD.get());   // default 5.0 / 0.0
m_yController     = new PIDController(kLinearP.get(), 0.0, kLinearD.get());
m_thetaController = new PIDController(kThetaP.get(), 0.0, kThetaD.get());     // default 5.0 / 0.0
m_thetaController.enableContinuousInput(-Math.PI, Math.PI);
```

> **Do not copy these three lines into `PumpkinDriveToPose`.** Drive-to-pose has no trajectory and therefore must generate its own profile, so §9 uses `ProfiledPIDController` — a *different type* with a *different* `getSetpoint()` return (`TrapezoidProfile.State`, not `double`). Mixing the two is the single most common compile break when a team hand-rolls this. `PumpkinDriveToPose`'s controllers are declared explicitly in §9.1.

Choreo warmup at init: `m_choreoFactory.warmupCmd().schedule()`, PathPlanner: `FollowPathCommand.warmupCommand().schedule()`, pathfinding: `PathfindingCommand.warmupCommand().schedule()`. All three are fired by `PumpkinAuto.warmup()`.

### 5.4 Decision guide — which should a team pick?

Ship this table in the docs *and* print it from `./gradlew pumpkinDoctor`.

| If your situation is… | Use | Why |
|---|---|---|
| First year with autos, no swerve, or tank | **PathPlanner** | GUI-first, `.auto` files need no Java, `buildAutoChooser()` gives you a chooser for free, and event markers are visual. |
| You want the fastest possible auto and your start pose is repeatable | **Choreo** | Time-optimal beats spline-and-profile measurably on the same waypoints. `AutoTrajectory`'s trigger vocabulary is the best mechanism-coordination surface in FRC. |
| The field is dynamic (defenders, on-the-fly targets, "go to nearest scoring pose") | **PathPlanner** | It is the only library with pathfinding (AD*/`LocalADStar` over `navgrid.json`). Choreo has none. |
| Your robot start pose is not repeatable (bumped by a partner, no wall to square on) | **PathPlanner** for the first path | PathPlanner regenerates from the actual start state; Choreo cannot, so a 10 cm start error means the feedforward is wrong from sample 0. |
| You run AdvantageKit replay | either, but install `LocalADStarAK` | Pathfinding is the #1 silent replay divergence. |
| You want zoned events ("while in this region, spin up") | **PathPlanner** | Choreo markers are instants only. PumpkinLib synthesizes zones for Choreo (§6.3) but PathPlanner does it natively. |
| You have a strong Java student and 20 auto variants | **Choreo** + `PumpkinAutoRoutine` | Composition is 100% Java anyway; lazy generation + dependent questions scales. |
| **You cannot decide** | **PathPlanner first, add Choreo for your two fastest autos in week 4** | This is PumpkinLib's official recommendation. `withPathPlanner().withChoreo()` costs nothing, and `auto.traj(name)` resolves from either source, so you can migrate one path at a time. |

**A note we print at boot if only Choreo is configured:** pathfinding requires `deploy/pathplanner/navgrid.json`, which is only created by opening the PathPlanner GUI once. A Choreo-primary team that never opens PathPlanner will have pathfinding fail at the event.

---

## 6. Mechanism actions during autos — the explicit user request

> *"Drive here while raising the elevator, start the intake 0.3 s before arrival, score on arrival, abort to the next piece if we didn't get one."*

This section specifies exactly that, and it is the reason `PumpkinTrajectory` exists.

### 6.1 `PumpkinTrajectory` — one trigger surface, two sources

The two vocabularies are ~90 % isomorphic. Rather than delegate triggers to whichever library is underneath (which would give subtly different semantics), **PumpkinLib implements one trigger engine over a small `TrajectoryHandle` SPI**, so `atTimeBeforeEnd(0.3)` means the identical thing on PathPlanner and Choreo.

```java
package org.pumpkinlib.auto.source;

/** Everything the trigger engine needs. Implemented by ChoreoSource and PathPlannerSource. */
public interface TrajectoryHandle {
  String name();
  String sourceName();                                   // "Choreo" | "PathPlanner"
  double totalTimeSeconds();
  Optional<Pose2d> initialPose();                        // ALLIANCE-CORRECTED at call time
  Optional<Pose2d> finalPose();
  Pose2d[] poses();                                      // for logging/verification
  /** Event marker times, seconds from start. Empty list if the name is unknown. */
  double[] eventTimes(String eventName);
  Pose2d[] eventPoses(String eventName);
  /** The follow command. Declares the drive requirement. */
  Command followCommand();
  /** Resets odometry to the alliance-corrected initial pose. */
  Command resetOdometryCommand();
}
```

```java
package org.pumpkinlib.auto;

/**
 * A trajectory plus a trigger vocabulary. Triggers are bound to the OWNING ROUTINE'S EventLoop,
 * so they are only polled while that routine is running — the same lifetime rule ChoreoLib's
 * AutoRoutine enforces, applied uniformly to PathPlanner trajectories too.
 */
public final class PumpkinTrajectory {

  // ---- running it ---------------------------------------------------------
  public Command cmd();
  public Command resetOdometry();
  public Command spawn();                     // schedules cmd() and finishes immediately

  // ---- state triggers -----------------------------------------------------
  public Trigger active();
  public Trigger inactive();
  public Trigger done();                      // rising edge on completion
  public Trigger doneFor(double seconds);     // done and has stayed done for N seconds
  public Trigger doneDelayed(double seconds); // fires N seconds after done

  // ---- time triggers ------------------------------------------------------
  public Trigger atTime(double secondsFromStart);
  public Trigger atTimeBeforeEnd(double secondsBeforeEnd);   // "intake 0.3s before arrival"
  public Trigger duringTime(double startSec, double endSec); // zone synthesis

  // ---- event-marker triggers ----------------------------------------------
  public Trigger atEvent(String eventName);
  public Trigger beforeEvent(String eventName, double secondsBefore);
  public Trigger afterEvent(String eventName, double secondsAfter);

  // ---- pose triggers (time mechanism actions against POSE, not wall clock) -
  public Trigger atTranslation(Translation2d blueTranslation, double toleranceMeters);
  public Trigger atTranslation(String eventName, double toleranceMeters);
  public Trigger atPose(Pose2d bluePose, double tolMeters, double tolRadians);
  public Trigger atPose(String eventName, double tolMeters, double tolRadians);
  public Trigger withinOfEnd(double meters);

  // ---- geometry -----------------------------------------------------------
  public Optional<Pose2d> initialPose();      // alliance-corrected
  public Optional<Pose2d> finalPose();
  public double totalTimeSeconds();
  public String name();

  // ---- transforms ---------------------------------------------------------
  /** Driver-perspective left<->right variant of the SAME trajectory. */
  public PumpkinTrajectory mirroredAboutMidline();
  /** Chain: this trajectory's end pose seeds the next one's start (Choreo semantics). */
  public PumpkinTrajectory chain(PumpkinTrajectory next);
}
```

**Source mapping:**

| PumpkinLib | Choreo backing | PathPlanner backing |
|---|---|---|
| `totalTimeSeconds()` | `Trajectory.getTotalTime()` | `path.getIdealTrajectory(robotConfig).get().getTotalTimeSeconds()` |
| `initialPose()` | `Trajectory.getInitialPose(PumpkinField.isRed())` | `path.getStartingHolonomicPose()` then `FlippingUtil.flipFieldPose` if red |
| `cmd()` | `AutoFactory.trajectoryCmd(name[, split])` | `AutoBuilder.followPath(path)` |
| `resetOdometry()` | `AutoFactory.resetOdometry(name[, split])` | `AutoBuilder.resetOdom(initialPose())` |
| `eventTimes(n)` | `Trajectory.getEvents(n)` → marker timestamps | parse `path` event markers via `PathPlannerAuto.event` fallback; see note |
| `poses()` | `Trajectory.getPoses()` | `path.getPathPoses()` |
| `atPose/atTranslation` | our engine (uses `drive.getPose()`) | our engine (identical code) |
| `atTime*` | our engine (elapsed timer started by `cmd()`) | our engine (identical code) |

**[UNVERIFIED]** — PathPlannerLib 2026 does not expose a clean public accessor for a single path's event-marker *times* outside of a running `PathPlannerAuto` (the trigger API `PathPlannerAuto.event(String)` / `beforeEvent(String, double)` covers the running case). `PathPlannerSource.eventTimes()` therefore does one of two things, in order: (1) read `path.getEventMarkers()` if that accessor exists in 2026.1.2 and convert waypoint-relative positions to times against `getIdealTrajectory`, or (2) parse `deploy/pathplanner/paths/<name>.path` JSON directly at load time. **Ship (2)** — it is 30 lines, has no vendor-API risk, and it is what the implementer should do unless (1) is confirmed. Mark the parsed source in the log as `Pumpkin/Auto/<name>/EventSource = "json"`.

### 6.2 The trigger engine

```java
import org.pumpkinlib.core.compat.Clock;
import org.pumpkinlib.core.util.PumpkinStopwatch;

final class TrajectoryRuntime {
  private final TrajectoryHandle m_handle;
  private final EventLoop m_loop;              // the owning routine's loop
  private final PumpkinStopwatch m_timer = new PumpkinStopwatch();   // Clock-backed, NOT wpilibj Timer
  private boolean m_active, m_wasActive, m_finished;
  private double m_doneAt = Double.NaN;

  Command wrapFollow() {
    return m_handle.followCommand()
        .beforeStarting(() -> { m_timer.restart(); m_active = true; m_finished = false; })
        .finallyDo(interrupted -> {
          m_active = false;
          m_finished = !interrupted;
          m_doneAt = Clock.now();             // §0.3 rule 8 — never Timer.getFPGATimestamp()
          PumpkinLog.get().put("Pumpkin/Auto/Traj/" + m_handle.name() + "/Completed", !interrupted);
          PumpkinLog.get().put("Pumpkin/Auto/Traj/" + m_handle.name() + "/Elapsed", m_timer.get());
          PumpkinLog.get().put("Pumpkin/Auto/Traj/" + m_handle.name() + "/Planned",
                               m_handle.totalTimeSeconds());
        });
  }

  Trigger atTimeBeforeEnd(double before) {
    double t = Math.max(0.0, m_handle.totalTimeSeconds() - before);
    return atTime(t);
  }

  Trigger atTime(double t) {
    return new Trigger(m_loop, new BooleanSupplier() {
      private boolean m_fired = false;
      @Override public boolean getAsBoolean() {
        if (!m_active) { m_fired = false; return false; }
        if (!m_fired && m_timer.get() >= t) { m_fired = true; return true; }
        return false;                       // one-shot rising edge, like Choreo's atTime
      }
    });
  }

  Trigger atTranslation(Translation2d blue, double tolMeters) {
    Translation2d target = PumpkinField.apply(blue);
    return new Trigger(m_loop, () ->
        m_active && m_pose.get().getTranslation().getDistance(target) <= tolMeters);
  }
}
```

The `EventLoop` scoping is essential and is the pitfall `deep-auto.json` names for ChoreoLib: a `Trigger` on the default loop is polled forever and will fire outside the routine's lifetime. `PumpkinAutoRoutine.poll()` is called from the routine command's `execute()`; nothing else polls that loop.

#### 6.2.1 Why every timer in this document is `PumpkinStopwatch`

`TrajectoryRuntime.m_timer`, `PumpkinDriveToPose.m_timer`, and `PumpkinDriveToPose.m_settleTimer` were all `edu.wpi.first.wpilibj.Timer` in the first draft. They are all `PumpkinStopwatch` now. The reason is not stylistic:

- `Timer` reads the FPGA clock internally on every `get()`. Under AdvantageKit replay the log advances in *log* time while the FPGA clock advances in *wall-clock* time, so `atTimeBeforeEnd(0.30)` fires at a different sample index in replay than it did on the robot. A replay that does not reproduce the match is worse than no replay: it produces confident wrong conclusions.
- `Timer.getFPGATimestamp()` is banned by DESIGN.md Principle 9 / D12, `02 §5.6` rule 1, `06`'s `Clock` javadoc, and DESIGN §8 ArchUnit hard rule 3. Shipping it here would mean the library trips its own determinism guard — `04`'s `PumpkinReplay` runtime tripwire is specified to trap exactly this call.
- The ArchUnit rule in §1.8 bans the constructor too, not just the static, precisely so this cannot come back as "well, an *instance* is fine."

`PumpkinStopwatch` is a 40-line class with the same five methods. There is no reason to keep the WPILib type and every reason not to.

### 6.3 Zone triggers on Choreo

Choreo markers are instants only. `duringTime(a, b)` and a synthesized zone from paired entry/exit markers give a `whileTrue` surface on both sources:

```java
public Trigger duringTime(double startSec, double endSec) {
  return new Trigger(m_loop, () -> m_active && m_timer.get() >= startSec && m_timer.get() <= endSec);
}
/** Convention: markers named "<zone>Start" and "<zone>End" become a zone. */
public Trigger inZone(String zoneName) {
  double[] starts = m_handle.eventTimes(zoneName + "Start");
  double[] ends   = m_handle.eventTimes(zoneName + "End");
  /* validate pairing; Alert if unbalanced, naming the marker names to fix */
}
```

### 6.4 `PumpkinAutoRoutine` and `AutoStep` — the declarative auto DSL

```java
package org.pumpkinlib.auto;

public final class PumpkinAutoRoutine {

  public PumpkinAutoRoutine startAt(PumpkinTrajectory first);  // resetOdometry from its initial pose
  public PumpkinAutoRoutine startAt(Pose2d bluePose);

  public PumpkinAutoRoutine step(String name, Consumer<AutoStep> body);
  public PumpkinAutoRoutine step(Consumer<AutoStep> body);      // name derived from the trajectory

  /** Conditional fork. Both branches are built eagerly; selection is at runtime. */
  public PumpkinAutoRoutine branch(BooleanSupplier condition,
                                   Consumer<PumpkinAutoRoutine> ifTrue,
                                   Consumer<PumpkinAutoRoutine> ifFalse);

  /** Repeat the enclosed steps while the condition holds, up to maxCycles. */
  public PumpkinAutoRoutine cycle(int maxCycles, BooleanSupplier keepGoing,
                                  Consumer<PumpkinAutoRoutine> body);

  /**
   * 254's hard budget, expressed in the unit a driveteam actually reasons in: SECONDS LEFT.
   * When fewer than `secondsRemaining` of the autonomous period remain, abandon the current step
   * and jump to `stepName`. Measured against FieldMap.autoPeriodSeconds() — there is no hardcoded
   * 15.0 anywhere in PumpkinLib (D15), so a 2027 period change is a one-line FieldMap edit.
   *
   * Implementation: the routine records its own start at Clock.now() in the built command's
   * initialize(); the guard fires when
   *   Clock.now() - start >= FieldMap.autoPeriodSeconds() - secondsRemaining.
   * The zero point is the ROUTINE's start, not the FMS autonomous transition — those differ by at
   * most one scheduler loop (20 ms), and using the routine's own start keeps the guard meaningful
   * in PumpkinAutoTest and in a teleop-scheduled dry run, where DriverStation is not in autonomous
   * at all. The 20 ms is logged as Pumpkin/Auto/RoutineStartLatency so it is never a mystery.
   */
  public PumpkinAutoRoutine skipToAfter(double secondsRemaining, String stepName);

  /** One registered skipToAfter guard. Read by build() validation and by the routine at runtime. */
  public record SkipGuard(double secondsRemaining, String stepName) {}
  public List<SkipGuard> skipGuards();

  /** Raw escape hatch for anything the DSL does not express. */
  public PumpkinAutoRoutine raw(Command command);

  /** Always runs, interrupted or not. Use for stow/park/stop-rollers. */
  public PumpkinAutoRoutine onEnd(Command command);

  public Command build();
  public EventLoop loop();
  public String name();
}

public final class AutoStep {

  // ---- what to drive ------------------------------------------------------
  public AutoStep follow(PumpkinTrajectory traj);
  public AutoStep driveTo(Supplier<Pose2d> blueTarget);      // PumpkinDriveToPose
  public AutoStep pathfindTo(Pose2d blueTarget, PathConstraints c);
  public AutoStep hold();                                    // no drive motion this step

  // ---- what the mechanisms do --------------------------------------------
  /** Runs in parallel with the drive motion; cancelled when the step ends. */
  public AutoStep with(Command parallel);
  /** Request a superstructure goal now, fire-and-forget. Never takes requirements. */
  public <G extends Enum<G>> AutoStep goal(G goal);
  /**
   * "start the intake 0.3s before arrival".
   * REQUIRES follow(traj) on the same step — the countdown is measured against the trajectory's
   * own total time. A driveTo()/pathfindTo()/hold() step has no known end time, so this is a
   * BUILD-TIME error, not a silently dead trigger. Use when(...) or whenWithin(...) there.
   */
  public AutoStep before(double secondsBeforeEnd, Command command);
  public <G extends Enum<G>> AutoStep before(double secondsBeforeEnd, G goal);
  /** Time an action against POSE rather than wall clock (the 6328 practice). */
  public AutoStep when(Trigger trigger, Command command);
  public AutoStep whenWithin(double meters, Pose2d blueTarget, Command command);
  /**
   * Vision-assisted fine align at the trajectory's scoring waypoint. Appends a PumpkinDriveToPose
   * AFTER follow() completes and BEFORE then(), so the score command runs from a converged pose
   * rather than from wherever the trajectory happened to leave the robot.
   *
   * This is the highest-leverage single line in the auto DSL. A Choreo trajectory typically lands
   * 4-10 cm from its terminal pose; a scoring mechanism with a 2 cm window misses. Elite autos are
   * not faster trajectories, they are trajectories that hand off to a closed-loop align.
   *
   * The step's deadline still governs: if the align has not converged when the deadline expires,
   * the step fails and successWhen/orElse/orSkipTo handle it exactly as they would a missed piece.
   */
  public AutoStep alignAtEnd(Supplier<Pose2d> blueTarget, Distance tol, Angle angTol,
                             double timeoutSeconds);
  /** Overload: align to the followed trajectory's own final pose. */
  public AutoStep alignAtEnd(Distance tol, Angle angTol, double timeoutSeconds);
  /** Runs after the drive motion completes (and after alignAtEnd, if set); the step does not end
   *  until this does. */
  public AutoStep then(Command command);

  // ---- introspection used by PumpkinAutoRoutine.build() validation ---------
  // Every accessor build() calls is declared here. A validation block that calls methods the
  // type does not expose is how a "validated" DSL ships with the validation commented out.
  public boolean hasBefore();
  public @Nullable PumpkinTrajectory trajectory();
  public String name();
  public boolean hasAlignAtEnd();
  public @Nullable Supplier<Pose2d> alignTarget();
  public Distance alignTolerance();
  public Angle alignAngularTolerance();
  public double alignTimeoutSeconds();
  public int retryCount();
  public boolean retryWasSetExplicitly();
  public @Nullable String skipTarget();
  public double deadlineSeconds();
  public @Nullable Command thenCommand();
  public List<Command> parallels();
  public Command driveCommand();

  // ---- discipline ---------------------------------------------------------
  /** Soft: logs an overrun, does not kill. Defaults to the trajectory's own planned time. */
  public AutoStep budget(double seconds);
  /** Hard: cancels the step. Defaults to budget * 1.5, minimum budget + 1.0 s. */
  public AutoStep deadline(double seconds);
  /** Step is considered successful only if this holds when the drive motion completes. */
  public AutoStep successWhen(BooleanSupplier condition);
  /** On failure, re-run this step up to n times. */
  public AutoStep retry(int times);
  /** On failure (or exhausted retries), run this instead and continue. */
  public AutoStep orElse(Consumer<AutoStep> fallback);
  /** On failure, jump to the named step. */
  public AutoStep orSkipTo(String stepName);
  /** Skip this step entirely if the condition is false at step start. */
  public AutoStep onlyIf(BooleanSupplier condition);
}
```

**`retry(int)` counts RE-runs, not total runs.** `retry(1)` means "run it, and if it fails run it once more" — two attempts. `retry(0)` is therefore the default and a no-op; writing it is a lie that reads like configured behavior, so `build()` rejects it:

> `Step 'get piece 2': retry(0) does nothing — 0 re-runs is the default. Delete it, or write retry(1) if you meant "try twice".`

**`build()` validates the DSL before the match, not during it.** These are `IllegalStateException`s at construction time — the routine is built during `robotInit` warmup (§7.2 rule 2), so a malformed auto fails in the pit, loudly, with the fix in the message:

```java
public Command build() {
  for (AutoStep step : m_steps) {

    // 1. before(t, ...) needs a known end time. driveTo/pathfindTo/hold have none, so the
    //    trigger would be created, bound to the routine loop, and never fire — silently.
    if (step.hasBefore() && step.trajectory() == null) {
      throw new IllegalStateException("Step '" + step.name()
          + "': before(t, ...) requires follow(traj) — it counts backwards from the trajectory's "
          + "total time, and a driveTo()/pathfindTo()/hold() step has no known end time. "
          + "For a driveTo step use when(align.atGoal(), cmd) or whenWithin(meters, target, cmd).");
    }

    // 2. alignAtEnd() with no explicit target needs a trajectory to take the final pose from.
    if (step.hasAlignAtEnd() && step.alignTarget() == null && step.trajectory() == null) {
      throw new IllegalStateException("Step '" + step.name()
          + "': alignAtEnd(tol, angTol, timeout) with no target requires follow(traj). "
          + "Pass an explicit Supplier<Pose2d> target instead.");
    }

    // 3. retry(0) is a no-op that reads like configuration.
    if (step.retryCount() == 0 && step.retryWasSetExplicitly()) {
      throw new IllegalStateException("Step '" + step.name()
          + "': retry(0) does nothing — 0 re-runs is already the default. Delete it, or write "
          + "retry(1) if you meant \"try twice\".");
    }

    // 4. orSkipTo / skipToAfter must name a step that exists.
    if (step.skipTarget() != null && !m_stepNames.contains(step.skipTarget())) {
      throw new IllegalStateException("Step '" + step.name() + "': orSkipTo(\"" + step.skipTarget()
          + "\") names no step in routine '" + m_name + "'. Known steps: " + m_stepNames);
    }
  }
  for (var guard : m_skipToAfterGuards) {
    if (!m_stepNames.contains(guard.stepName())) {
      throw new IllegalStateException("Routine '" + m_name + "': skipToAfter(" + guard.secondsRemaining()
          + ", \"" + guard.stepName() + "\") names no step. Known steps: " + m_stepNames);
    }
    if (guard.secondsRemaining() >= FieldMap.autoPeriodSeconds()) {
      throw new IllegalStateException("Routine '" + m_name + "': skipToAfter takes SECONDS REMAINING, "
          + "not elapsed seconds. " + guard.secondsRemaining() + " >= the "
          + FieldMap.autoPeriodSeconds() + " s auto period, so this would fire immediately. "
          + "Did you mean skipToAfter(" + (FieldMap.autoPeriodSeconds() - guard.secondsRemaining())
          + ", ...)?");
    }
    if (guard.secondsRemaining() <= 0.0) {
      throw new IllegalStateException("Routine '" + m_name + "': skipToAfter("
          + guard.secondsRemaining() + ", \"" + guard.stepName() + "\") can never fire — there is "
          + "no point in the auto period with " + guard.secondsRemaining() + " s remaining. "
          + "Pass the seconds of auto you want to reserve for '" + guard.stepName() + "'.");
    }
  }
  /* ... compile ... */
}
```

Validation rule 4's second clause is the migration guard for the old `skipToAfter(13.0, "park")` signature: a team that copies a pre-revision snippet gets a message that computes the new argument for them instead of a park step that fires on tick one.

**Every step publishes**, for post-match triage:

```
Pumpkin/Auto/Steps/<i>/Name          String
Pumpkin/Auto/Steps/<i>/PlannedSec    double
Pumpkin/Auto/Steps/<i>/ActualSec     double
Pumpkin/Auto/Steps/<i>/Overrun       boolean
Pumpkin/Auto/Steps/<i>/Succeeded     boolean
Pumpkin/Auto/Steps/<i>/Retries       double
Pumpkin/Auto/Steps/<i>/EndPoseError  double   (meters, vs the trajectory's final pose)
Pumpkin/Auto/StepIndex               double
Pumpkin/Auto/StepName                String
Pumpkin/Auto/Name                    String
```

### 6.5 How a step compiles to a `Command`

```java
Command compile(AutoStep s) {
  Command drive = s.driveCommand();                       // traj.cmd() / driveToPose / none

  // Parallel mechanism work ends with the drive motion.
  Command driveWithParallels = drive;
  for (Command p : s.parallels()) {
    driveWithParallels = driveWithParallels.deadlineFor(p);   // drive is the deadline
  }

  // Trigger-scheduled work (before/when/whenWithin) is bound to the routine loop by the
  // trajectory itself, so it is NOT part of this composition and cannot cancel it.
  // It fires goals on the GoalBus, which owns mechanism requirements exactly once.
  // build() has already guaranteed that before(...) only appears on steps that have a
  // trajectory, so there is no such thing as a before() trigger with no clock to hang on.

  Command body = driveWithParallels;

  // Fine align at the scoring waypoint, between the trajectory and the score command.
  // Same drive requirement, so it sequences cleanly with no requirement fight.
  if (s.hasAlignAtEnd()) {
    Supplier<Pose2d> target = s.alignTarget() != null
        ? s.alignTarget()
        : () -> s.trajectory().finalPose().orElseThrow();
    body = body.andThen(
        PumpkinDriveToPose.builder(m_drive)
            .target(target)
            .tolerance(s.alignTolerance(), s.alignAngularTolerance())
            .timeout(s.alignTimeoutSeconds())
            .build()
            .asCommand());
  }

  if (s.thenCommand() != null) body = body.andThen(s.thenCommand());

  Command timed = body.withTimeout(s.deadlineSeconds());

  return Commands.sequence(
      Commands.runOnce(() -> beginStep(s)),
      timed,
      Commands.runOnce(() -> endStep(s)))
    .finallyDo(interrupted -> recordStep(s, interrupted));
}
```

`retry` wraps this in a bounded loop using `Commands.repeatingSequence(...).until(...)` with an explicit counter; `orSkipTo` sets an index on a shared `RoutineState` that the outer `Commands.select(...)` reads.

### 6.6 Score-on-the-move during a path

Both libraries support overriding only the rotation channel while following.

```java
// PathPlanner: static overrides. MUST be cleared in finallyDo or they leak into the next command.
public static Command aimWhileFollowing(PumpkinTrajectory traj, DoubleSupplier omegaFeedback) {
  return traj.cmd()
      .beforeStarting(() -> PPHolonomicDriveController.overrideRotationFeedback(omegaFeedback))
      .finallyDo(interrupted -> PPHolonomicDriveController.clearRotationFeedbackOverride());
}
```

For Choreo, `followChoreoSample` consults `PumpkinAuto`'s `omegaOverride` supplier before adding the theta PID term:

```java
OptionalDouble override = m_omegaOverride.get();
double omega = override.isPresent()
    ? override.getAsDouble()
    : s.omega + m_thetaController.calculate(pose.getRotation().getRadians(), s.heading);
```

`PumpkinAuto.omegaOverride(Supplier<OptionalDouble>)` sets it once; `AutoStep.aimWhileDriving(Supplier<OptionalDouble>)` scopes it to a step with automatic clearing.

Wrapped as a one-liner:

```java
public AutoStep aimWhileDriving(Supplier<OptionalDouble> omegaRadPerSec);
```

This is the 6328 structure (`DriveTrajectory(Trajectory, Supplier<Optional<Double>> omegaOverride, Drive, BooleanSupplier mirror)`) generalized across both sources.

### 6.7 Full working example — the user's exact sentence

> *"Drive here while raising the elevator, start the intake 0.3 s before arrival, score on arrival, abort to the next piece if we didn't get one."*

```java
// ---- one-time setup, in RobotContainer ----------------------------------------
PumpkinDrive drive = PumpkinDrive.fromTunerX(TunerConstants.createDrivetrain());

PumpkinAuto auto = PumpkinAuto.of(drive)
    .withPathPlanner()
    .withChoreo()
    .withPathfinding()
    .withGoals(superstructure);            // GoalBus<Goal>

// ---- the auto ------------------------------------------------------------------
PumpkinTrajectory toReef   = auto.traj("StartToReef");
PumpkinTrajectory toPiece2 = auto.traj("ReefToPiece2");
PumpkinTrajectory retry2   = auto.traj("Piece2Retry");
PumpkinTrajectory toReef2  = auto.traj("Piece2ToReef");

Command threePiece = auto.routine("3pc Left")
    .startAt(toReef)
    .step("score preload", s -> s
        .follow(toReef)
        .goal(Goal.L4_PREP)                                  // raise the elevator while driving
        .before(0.30, intake.runIntake())                    // intake 0.3 s before arrival
        // Hand off from the trajectory to a closed-loop align at the scoring pose. This is what
        // separates an auto that scores 3 from one that scores 3 *reliably*: the trajectory gets
        // us within ~6 cm, the align gets us within 2 cm, and only then do we score.
        .alignAtEnd(() -> FieldPoses.kBlueReefLeftL4, Meters.of(0.02), Degrees.of(1.5), 0.6)
        .budget(toReef.totalTimeSeconds())
        .deadline(toReef.totalTimeSeconds() + 1.6)           // +0.6 align, +1.0 slack
        .then(superstructure.request(Goal.L4_SCORE).withTimeout(0.8)))
    .step("get piece 2", s -> s
        .follow(toPiece2)
        .goal(Goal.INTAKE)
        .successWhen(sensors::hasPiece)                      // did we actually get it?
        .deadline(toPiece2.totalTimeSeconds() + 1.0)
        .orElse(f -> f                                        // abort to the retry sweep
            .follow(retry2)
            .goal(Goal.INTAKE)
            .successWhen(sensors::hasPiece)
            .deadline(2.5)))
    .step("score piece 2", s -> s
        .follow(toReef2)
        .goal(Goal.L4_PREP)
        .onlyIf(sensors::hasPiece)                            // no piece -> skip, don't waste 3 s
        .alignAtEnd(Meters.of(0.02), Degrees.of(1.5), 0.6)    // align to toReef2's own final pose
        .then(superstructure.request(Goal.L4_SCORE).withTimeout(0.8)))
    .skipToAfter(/* seconds left */ 2.0, "park")
    .step("park", s -> s.driveTo(() -> FieldPoses.kBlueParkPose).deadline(2.0))
    .onEnd(superstructure.request(Goal.STOW))
    .build();
```

**What is guaranteed by construction here:**
- `superstructure.request(...)` and `.goal(...)` never take the drive requirement, so they cannot cancel the auto (the PathPlanner NamedCommand footgun is structurally unreachable).
- `before(0.30, ...)` means the same thing whether `StartToReef` is a Choreo `.traj` or a PathPlanner `.path` — and because `before()` is only legal on a step that has a trajectory, it can never be a trigger that silently never fires.
- `alignAtEnd(...)` closes the loop on the scoring pose. The trajectory's job is to get there fast; the align's job is to get there *right*. This is the single change in this document that most raises real-world auto scoring reliability.
- Every pose is blue-authored; red flipping happens once, in `PumpkinField`.
- `skipToAfter(2.0, "park")` reads "with 2 seconds left, go park" and is computed against `FieldMap.autoPeriodSeconds()`. Nothing here knows that 2026 REBUILT's auto is 15 s, so nothing here breaks when 2027's isn't.
- Every step has a hard deadline. A stuck intake costs 1 s, not the match.
- The whole auto emits a per-step timeline you can read in AdvantageScope after the match.

### 6.8 Equivalent trigger-style composition (for teams who prefer Choreo's idiom)

The DSL is not mandatory. Raw trigger composition works and uses the identical trigger vocabulary:

```java
PumpkinAutoRoutine r = auto.routine("3pc trigger style");
PumpkinTrajectory t1 = auto.choreo("StartToReef");
PumpkinTrajectory t2 = auto.choreo("ReefToPiece2");
PumpkinTrajectory t2b = auto.choreo("Piece2Retry");
PumpkinTrajectory t3 = auto.choreo("Piece2ToReef");

r.active().onTrue(Commands.sequence(t1.resetOdometry(), t1.cmd()));
t1.atTimeBeforeEnd(0.30).onTrue(intake.runIntake());
t1.atTime(0.10).onTrue(superstructure.request(Goal.L4_PREP));
t1.done().onTrue(superstructure.request(Goal.L4_SCORE).withTimeout(0.8).andThen(t2.cmd()));
t2.done().and(sensors::hasPiece).onTrue(t3.cmd());
t2.done().and(() -> !sensors.hasPiece()).onTrue(t2b.cmd());
Command threePiece = r.build();
```

---

## 7. Auto composition & selection

### 7.1 `PumpkinAutoMode` — the class-per-auto base (1678 shape)

Dozens of variants become tractable when each auto is a class.

```java
package org.pumpkinlib.auto;

public abstract class PumpkinAutoMode {

  protected final PumpkinAuto auto;
  protected PumpkinAutoMode(PumpkinAuto auto, String name);

  protected PumpkinTrajectory traj(String name);
  protected PumpkinTrajectory traj(String name, int splitIndex);
  protected PumpkinAutoRoutine routine();

  /** Implement this. Called lazily, once, on first selection. */
  protected abstract Command define();

  public final Command asCommand();       // memoized; wraps define() with logging + onEnd stow
  public final String name();
  /** For alliance verification and the test harness. */
  public Optional<Pose2d> expectedStartPose();
  public Optional<Pose2d> expectedEndPose();
  public double expectedScore();          // game pieces; used by PumpkinAutoTest assertions
}
```

### 7.2 `PumpkinAutoSelector` — dependent questions, lazy generation, frozen responses

A flat 40-entry `SendableChooser` is unusable at an event. 6328 publishes up to 6 dependent question dropdowns per routine and freezes them once auto is enabled.

```java
package org.pumpkinlib.auto;

public record AutoQuestion(String prompt, List<String> responses) {
  public static AutoQuestion of(String prompt, String... responses);
  public static <E extends Enum<E>> AutoQuestion of(String prompt, Class<E> options);
}

public final class AutoResponses {
  public String get(int questionIndex);
  public <E extends Enum<E>> E get(int questionIndex, Class<E> type);
  public String key();       // stable, e.g. "MidlineSweep|LEFT|M2|NO" — logged and used as a test id
}

public final class PumpkinAutoSelector {

  public PumpkinAutoSelector(String ntTableName);   // default "Pumpkin/Auto"

  /** Lazy: the Function is only invoked when the variant is first needed. */
  public PumpkinAutoSelector addRoutine(String name, List<AutoQuestion> questions,
                                        Function<AutoResponses, Command> builder);
  public PumpkinAutoSelector addCommand(String name, Supplier<Command> builder);
  public PumpkinAutoSelector addMode(PumpkinAutoMode mode);
  /** Wraps AutoBuilder.getAllAutoNames() so .auto files appear alongside Java routines. */
  public PumpkinAutoSelector importPathPlannerAutos();
  public PumpkinAutoSelector setDefault(String name);

  /** Bind with RobotModeTriggers.autonomous().whileTrue(selector.selectedCommandScheduler()); */
  public Command selectedCommandScheduler();
  public Command selected();

  /** Every (routine x response-combination) pair. Used by warmup and the test harness. */
  public List<AutoVariant> allVariants();

  public record AutoVariant(String name, AutoResponses responses,
                            Supplier<Command> command,
                            Optional<Pose2d> expectedStartPose,
                            Optional<Pose2d> expectedEndPose) {}
}
```

Behaviour rules, all non-negotiable:

1. **Responses freeze on the first autonomous enable.** A dropdown changed mid-match is a lost match.
2. **Everything is force-warmed at `robotInit`.** Lazy generation is a chooser convenience, not a reason to load a `.traj` during autonomous — loading is blocking and can cost seconds on a roboRIO. `PumpkinAuto.warmup()` walks `allVariants()`, builds each command once, discards it, and logs total warmup time.
3. **The resolved variant name is logged** (`Pumpkin/Auto/SelectedKey`) so log replay identifies exactly which auto ran.
4. **NT topics are stable and dashboard-agnostic**: `Pumpkin/Auto/Routine`, `Pumpkin/Auto/Question1..6`, `Pumpkin/Auto/Question1Options..`, `Pumpkin/Auto/SelectedKey`, `Pumpkin/Auto/Frozen`. Never `SmartDashboard.putData` from library code — SmartDashboard and Shuffleboard are removed in WPILib 2027.

### 7.3 Dynamic / conditional autos

Three levels, all supported:

**Level 1 — branch on a sensor** (`.branch(...)` / `.successWhen(...).orElse(...)`, §6.4). Covers "did we get the piece".

**Level 2 — branch on vision at runtime.** `AutoStep.driveTo(Supplier<Pose2d>)` is evaluated at step start, not at build time, so:

```java
.step("score nearest", s -> s
    .driveTo(() -> FieldPoses.nearestScoringPose(drive.getPose()))
    .goal(Goal.L4_PREP)
    .then(superstructure.request(Goal.L4_SCORE)))
```

**Level 3 — a fully dynamic sequence** (254's pattern: a scoring-sequence string from the dashboard drives pathfinding per step).

```java
public PumpkinAutoRoutine dynamic(Supplier<List<String>> stepKeys,
                                  Function<String, Consumer<AutoStep>> stepFactory,
                                  int maxSteps);
```

Implemented with `Commands.defer(...)` over a bounded step count so the command tree is finite and testable.

### 7.4 Testing 20 auto variants quickly in sim — `PumpkinAutoTest`

This is the single highest-value thing in this document that nobody ships. maple-sim provides the physics; nothing provides the assertion layer.

```java
package org.pumpkinlib.auto.test;

public final class PumpkinAutoTest {

  public static Builder simulate(AutoVariant variant);

  public static final class Builder {
    public Builder withField(SimWorld world);
    public Builder withDrive(PumpkinDrive drive);
    /** Inject odometry noise to stress the auto's tolerance to drift. */
    public Builder withOdometryNoise(double metersPerSecondStdDev, Angle headingDriftPerSecond);
    /** Start the robot offset from its nominal pose (bumped by a partner). */
    public Builder withStartOffset(Transform2d offset);
    /** Default: FieldMap.autoPeriodSeconds(). Never a literal — D15. */
    public Builder withAutoPeriodSeconds(double seconds);
    public Builder withRealTimeFactor(double factor);          // default 0 == as fast as possible
    public AutoTestResult run();
  }

  /** Runs every variant and returns a matrix. Used by the Gradle report task. */
  public static Map<String, AutoTestResult> runAll(List<AutoVariant> variants,
                                                   Function<AutoVariant, Builder> configure);
}

public record AutoTestResult(
    String variantKey,
    double elapsedSeconds,
    boolean completed,
    Pose2d finalPose,
    double finalPoseErrorMeters,
    int piecesScored,
    List<StepRecord> steps,
    List<String> alerts,
    Path wpilogPath) {

  public record StepRecord(int index, String name, double plannedSec, double actualSec,
                           boolean overran, boolean succeeded, int retries) {}

  public List<StepRecord> overruns();
}
```

Usage:

```java
class AutoBudgetTest {
  @Test void everyAutoFitsInTheAutoPeriod() {
    double period = FieldMap.autoPeriodSeconds();      // 15.0 in 2026; NEVER a literal here
    for (AutoVariant v : selector.allVariants()) {
      AutoTestResult r = PumpkinAutoTest.simulate(v)
          .withField(PumpkinSim.field(FieldMap.year()))
          .withDrive(simDrive)
          .withOdometryNoise(0.02, Degrees.of(0.5))
          .run();
      assertTrue(r.completed(), v.name() + " did not complete");
      assertTrue(r.elapsedSeconds() < period, v.name() + " took " + r.elapsedSeconds() + "s");
      assertTrue(r.overruns().isEmpty(), v.name() + " overran: " + r.overruns());
      assertTrue(r.finalPoseErrorMeters() < 0.15);
    }
  }

  @Test void everyAutoSurvivesA10cmStartBump() {
    for (AutoVariant v : selector.allVariants()) {
      var r = PumpkinAutoTest.simulate(v)
          .withStartOffset(new Transform2d(0.10, 0.10, Rotation2d.fromDegrees(5)))
          .withField(PumpkinSim.field(FieldMap.year())).withDrive(simDrive).run();
      assertTrue(r.completed());
    }
  }
}
```

Plus a Gradle task:

```
./gradlew pumpkinAutoReport
  -> build/pumpkin/auto-report.html   (variant x {time, completed, pieces, drift, per-step overrun})
  -> build/pumpkin/autotest/<variant>.wpilog   (open in AdvantageScope)
```

Implementation notes:
- `HeadlessClock` steps `SimHooks.stepTiming(0.02)` and `CommandScheduler.getInstance().run()` in a loop; `DriverStationSim.setAutonomous(true)` + `setEnabled(true)`. No sim GUI, no real-time waiting.
- Unit tests construct simulated CAN devices; vendor libs reject duplicate device IDs within one JVM. `build.gradle` template ships `test { forkEvery = 1 }` (9143 already learned this).
- The `PumpkinAutoTest` builder never touches the real `PumpkinDrive` singleton; it takes an injected sim drive.

---

## 8. Odometry accuracy

Odometry sample rate primarily improves **consistency**, not mean accuracy: higher-rate sampling reduces the discretization error accumulated during rotation and acceleration, which is exactly the error that varies run to run. `PumpkinDriveConfig.competition()` defaults to `NATIVE_250HZ` because Phoenix 6 provides it for free on a CANivore and the AdvantageKit TalonFX template uses the same figure (`ODOMETRY_FREQUENCY` 250 Hz on CANivore, else 100 Hz — §8.1). Anything less logs a warning naming what was degraded and why.

**We ship no quantitative before/after claim until `OdometryReport` (§8.5) has measured one on a real robot.** When 8793 and 9143 have each run `OdometryReport.squareTest` at 50 Hz and at 250 Hz on carpet, the numbers — with path length, speed, vision on/off, and drivetrain stated inline — replace this paragraph, and the raw `.wpilog` goes in the repo next to them. Principle 10 says every performance claim is measured; a library that publishes a folklore statistic to justify its own default has already lost the argument it is trying to win.

> **Revision note.** An earlier draft of this section asserted specific mean/std figures for 50 Hz vs 250 Hz with no citation. They are removed. Beyond being uncited, the 250 Hz figure would have been rated `UNTRUSTWORTHY` by this document's own `OdometryReport.Verdict` scale five sections later — an internal contradiction a hostile reader would find immediately, and R4 already names community rejection as a High risk.

### 8.1 `PumpkinCharacterization` — five commands, plus writeback

```java
package org.pumpkinlib.characterization;

public final class PumpkinCharacterization {

  /** 2 s orient at 0 V, then 0.1 V/s ramp; closed-form least-squares fit of kS and kV.
   *  Prints results to the console AND logs to Pumpkin/Char/FF/*. */
  public static Command feedforward(PumpkinDrive drive);

  /** Spin in place, SlewRateLimiter(0.05) to 0.25 rad/s.
   *  r = (gyroDelta * driveBaseRadius) / meanWheelDelta.
   *  REFUSES TO RUN unless the operator confirms the robot is on CARPET — a hard-floor run
   *  gives a wrong radius, and wheel radius scales odometry, path length, and RobotConfig. */
  public static Command wheelRadius(PumpkinDrive drive);

  /** Wall-push voltage ramp; reports stator amps at the velocity break (the slip point).
   *  Set drive current limits BELOW this. CTRE's Kraken swerve example: slip ~130 A,
   *  limits 120 A stator / 70 A supply.
   *  THIS IS A STALL TEST. Gated by CharacterizationSafety.armed(drive, SLIP_CURRENT) and bounded
   *  by Envelope.forRoutine(...): stator ceiling, 4 s ramp cap, motor-temp ceiling, and a
   *  displacement abort if the robot was not actually restrained. See §8.1.1. */
  public static Command slipCurrent(PumpkinDrive drive);

  /** Spin-up torque vs measured angular acceleration -> MOI estimate.
   *  Exists because nobody measures MOI and the entire community copies 6.883 from the
   *  6328 template, which makes SwerveSetpointGenerator either useless or crippling.
   *  Requires a confirmed 2 m clear radius and aborts above the angular-velocity ceiling (§8.1.1). */
  public static Command momentOfInertia(PumpkinDrive drive);

  /** Ramps linear acceleration until wheel-derived velocity diverges from gyro/vision-derived
   *  velocity by a threshold -> effective wheel COF.
   *  Requires a confirmed 8 m clear straight run and a spotter; the robot does NOT self-stop at
   *  the end of the ramp (§8.1.1). */
  public static Command wheelCof(PumpkinDrive drive);

  /** All five, in ASCENDING RISK ORDER, with a 3 s pause and a console banner between each, a
   *  driver-abort binding, and a fresh CharacterizationSafety.armed(...) check before each
   *  routine rather than once at the start. See §8.1.1. */
  public static Command all(PumpkinDrive drive);

  /** WPILib SysId quasistatic fwd/rev + dynamic fwd/rev, chained. For teams who want the
   *  desktop tool. CTRE teams get SysIdSwerveTranslation/Rotation/SteerGains for free. */
  public static Command sysIdAll(PumpkinDrive drive);

  // ---- results -------------------------------------------------------------
  public static Optional<DriveCharacterization> last();
  /** Writes /home/lvuser/pumpkin/drive_characterization.json at runtime;
   *  `./gradlew pumpkinPullConfig` copies it back to src/main/deploy/pumpkin/ for commit. */
  public static void writeResults(DriveCharacterization result);
  /** Patches deploy/pathplanner/settings.json and the .chor robot-config block from the
   *  measured values, then re-runs DriveSelfCheck. Dev-machine only. */
  public static void applyToPlanners(DriveCharacterization result);
}

public record DriveCharacterization(
    double kS, double kV, double kA,
    double wheelRadiusMeters,
    double slipCurrentAmps,
    double massKg, double moiKgM2, double wheelCof,
    double maxLinearVelocityMps,
    long timestampEpochSeconds, String robotName) {}
```

#### 8.1.1 Characterization safety — the preconditions are part of the API, not the docs

Three of these five routines deliberately drive a mechanism toward its limit. `slipCurrent` ramps voltage into a drivetrain pushed against a wall until the wheels break traction — that is a stalled-rotor condition with the current limits raised. `momentOfInertia` spins the robot at increasing angular acceleration. `wheelCof` accelerates in a straight line until traction breaks. Run carelessly, the realistic outcomes are a cooked drive motor, a stripped module gear, or a robot that leaves the test area at speed and hits a person.

A comment in a doc does not prevent any of that. **Every guard below is code, enforced in `Command.initialize()`, and a failed precondition means the command ends immediately without ever applying voltage** — it does not warn and proceed.

```java
package org.pumpkinlib.characterization;

/** Preconditions every characterization command checks before it applies ANY voltage. */
public final class CharacterizationSafety {

  /**
   * Hard gates. Any one false => the command logs a named Alert, publishes
   * Pumpkin/Char/Refused = "<reason>", and ends WITHOUT actuating.
   *
   *  1. PumpkinConfig.tuningMode() is true.
   *  2. DriverStation.isFMSAttached() is FALSE. Characterization never runs at an event on a field.
   *  3. The operator has acknowledged this specific routine's physical setup THIS BOOT
   *     (confirm(...) below). Acknowledgement does not persist across a reboot.
   *  4. Every drive motor reports connected, and no drive motor is above the temperature ceiling.
   *  5. The last OdometryReport verdict is not UNTRUSTWORTHY for the routines that depend on
   *     pose (wheelCof, momentOfInertia). A bad pose makes the measurement wrong AND the abort
   *     conditions wrong.
   */
  public static boolean armed(PumpkinDrive drive, Routine routine);

  public enum Routine { FEEDFORWARD, WHEEL_RADIUS, SLIP_CURRENT, MOMENT_OF_INERTIA, WHEEL_COF }

  /**
   * Operator acknowledgement. Set from the dashboard boolean Pumpkin/Char/<Routine>/Confirm,
   * which PumpkinCharacterization publishes as FALSE at every boot. The prompt text names the
   * physical setup — this is the whole point:
   *
   *   WHEEL_RADIUS      "Robot on CARPET, 1 m clear on all sides. Hard floor gives a wrong radius."
   *   SLIP_CURRENT      "Bumpers SQUARE against a solid wall, robot restrained, NOBODY within 2 m,
   *                      battery > 12.0 V, drive motors below 40 C. This is a stall test."
   *   MOMENT_OF_INERTIA "2 m clear radius, nothing on top of the robot, no tether."
   *   WHEEL_COF         "8 m of clear straight carpet, spotter at the far end, robot will
   *                      accelerate hard and will NOT stop at the end of the ramp on its own."
   *   FEEDFORWARD       "3 m clear straight carpet."
   */
  public static void confirm(Routine routine, boolean acknowledged);

  /** Live abort conditions, polled every loop by every characterization command. */
  public record Envelope(
      double maxStatorAmps,          // slipCurrent: hard ceiling, default 150 A
      double maxSeconds,             // per-routine ramp cap, default 4.0 s for slipCurrent
      double maxMotorCelsius,        // default 70 C, read from the backend
      double minBatteryVolts,        // default 10.0 V; brownout mid-ramp corrupts the fit anyway
      double maxDisplacementMeters,  // slipCurrent: robot must NOT move; default 0.15 m
      double maxAngularVelocityRadPerSec) {  // momentOfInertia ceiling, default 4 rad/s

    public static Envelope forRoutine(Routine r, PumpkinDrive drive);
  }
}
```

Applied to every command in §8.1:

1. **Any abort stops the drivetrain first and reports second.** `end(interrupted)` calls `drive.stop()` on every path, including the exception path, and the partial result is discarded rather than written — a fit from a truncated ramp is worse than no fit, because it looks like data.
2. **The driver always wins.** Every characterization command is `.until(() -> driverStickMoved())` and any joystick deflection past 0.2 aborts it. This is bound by `PumpkinCharacterization.all(drive)` automatically; a team cannot forget it.
3. **`slipCurrent` additionally aborts on movement.** If the robot displaces more than `maxDisplacementMeters`, it was not restrained, and the ramp stops — this is the difference between measuring slip current and launching a robot at a wall.
4. **`all(drive)` runs the routines in ascending risk order** — `feedforward`, `wheelRadius`, `momentOfInertia`, `wheelCof`, `slipCurrent` — with a 3 s pause and a console banner between each, and it re-checks `armed(...)` before *each* routine rather than once at the start. A motor that heated up during `wheelCof` stops `slipCurrent` from running.
5. **A 90 s cool-down is enforced between consecutive `slipCurrent` runs.** Repeated stall ramps are how teams destroy a Kraken in the pit.
6. **Nothing is written back automatically.** `writeResults` stores to `/home/lvuser`; `applyToPlanners` is dev-machine only and refuses to run on a roboRIO. A characterization that silently rewrote `settings.json` mid-event would be a new class of match-losing bug.

**Reviewer pushback:** *no reviewer finding was routed to this section.* **Why we're doing it anyway:** the review's own standard — that a shipped code block is copied verbatim by the implementer — applies with more force here than in §9.1. §9.1's defect drove a robot the wrong way; a `slipCurrent` block with the safety left as prose destroys hardware a small team cannot replace mid-season. The brief's audience is explicitly teams without a spare Kraken.

Shipped starting points (starting points only, never "tuned"):

```java
public enum DrivePreset {
  KRAKEN_X60_MK4I_L2, KRAKEN_X60_MK4I_L3, FALCON500_MK4I_L2,
  NEO_VORTEX_MAXSWERVE_13T, NEO_MAXSWERVE_13T, KRAKEN_X44_MK5I_R2
}
```

Verified reference values to seed the table (from the shipped vendor templates, so they are real, not folklore):
- Tuner X generated `TunerConstants`: steer Slot0 kP=100, kI=0, kD=0.5, kS=0.1, kV=1.91, kA=0; drive Slot0 kP=0.1, kI=0, kD=0, kS=0, kV=0.124; slipCurrent 120 A; speedAt12V 4.69 m/s; coupleRatio 3.818; driveGearRatio 7.3636; steerGearRatio 15.4286; wheelRadius 2.167 in.
- AdvantageKit spark template: maxSpeed 4.8 m/s, odometryFrequency 100 Hz, wheelRadius 1.5 in, driveKp 0.0, driveKs 0.0, driveKv 0.1, turnKp 2.0, robotMassKg 74.088, robotMOI 6.883, wheelCOF 1.2. TalonFX template: `ODOMETRY_FREQUENCY` 250 Hz on CANivore else 100 Hz.
- **[UNVERIFIED / anecdotal]** Kraken X60 on WCP Swerve X at 4.59:1 with a 4π-inch wheel: kV ≈ 0.55, kA ≈ 0.9. Community Chief Delphi figure, **not shipped as a preset** — documented only.

### 8.2 Gyro drift handling

```java
package org.pumpkinlib.drive;

public final class GyroHealth {
  /** Logged every loop as Pumpkin/Drive/Gyro/DriftDegPerMin. */
  public double driftDegreesPerMinute();
  /** True when the gyro has been still for > 2 s and is still reporting rate. */
  public boolean suspectedDrift();
  /** Optional backup gyro. When primary disconnects, swap and Alert. (6328 pattern.) */
  public void setBackup(Supplier<Rotation2d> backupHeading, BooleanSupplier backupConnected);
}
```

Policy, deliberately conservative:
- We do **not** implement a custom pose estimator. `SwerveDrivePoseEstimator` / `DifferentialDrivePoseEstimator` / CTRE's built-in estimator do this well. 6328 wrote their own; a small team does not need to, and rewriting it would violate the "integrate, don't reimplement" stance.
- We **do** correct drift structurally: vision rotation std dev defaults to `9_999_999` so vision never moves heading, and heading is re-seeded from a multi-tag observation only while **disabled** (the MegaTag1-while-disabled pattern 9143 already runs). `AlliancePerspective.resetFieldRotation` is the only other writer.
- We **do** detect and report drift so a team knows their Pigeon is bad before the match, not after.

### 8.3 Vision fusion tuning

This domain provides the sink and the trust arbitration; the vision domain provides the observations.

```java
package org.pumpkinlib.drive;

public final class OdometryTrust {
  /** Base std devs, per-axis, meters/meters/radians. */
  public OdometryTrust withWheelStdDevs(Matrix<N3, N1> base);
  public OdometryTrust withVisionStdDevs(Matrix<N3, N1> base);
  /** Inflate wheel trust when skidding. Multiplier applied to base, clamped [1, 100]. */
  public OdometryTrust withSkidInflation(double maxMultiplier);
  /** Reject a vision observation entirely under these conditions. */
  public OdometryTrust rejectWhenGyroRateAbove(AngularVelocity rate);   // default 720 deg/s
  public OdometryTrust rejectWhenOffFieldBy(Distance margin);           // default 0.5 m
  public OdometryTrust rejectWhenNoTags();
  /** Enforced structurally: vision rotation std dev is pinned to 9_999_999 unless the robot is
   *  disabled AND the observation has >= 2 tags. Prevents the MegaTag2 heading feedback loop. */
}
```

Recommended defaults, matched to what the surveyed repos converged on independently:
- wheel: `VecBuilder.fill(0.1, 0.1, 0.1)`
- vision (enabled): `xy = 0.3 + 0.4 * d^2 / max(1, tagCount)`, `theta = 9_999_999`
- vision (disabled, ≥2 tags): `theta = 0.3`; (disabled, 1 tag): `theta = 0.9`

**Timestamp discipline:** the observation's original capture timestamp is preserved byte-for-byte to the estimator. At 4 m/s a 20 ms timestamp error is 8 cm — larger than most teams' entire alignment tolerance. `PumpkinDrive.addVisionMeasurement` never re-timestamps and never buffers.

### 8.4 Skid detection

```java
package org.pumpkinlib.drive.traction;

public record SkidReport(double skidRatio, boolean skidding, int worstModuleIndex, double timestamp) {
  public static SkidReport none();
}

public final class SkidDetector {
  public SkidDetector(DriveGeometry geometry, double thresholdRatio);   // default 0.15

  /**
   * Math: for a rigid body, module i's velocity is v_i = v_chassis + omega x r_i. Solve for the
   * translational component each module implies by subtracting the gyro-derived rotational term
   * omega x r_i from the measured module velocity vector. On a non-skidding robot all four
   * implied translations are identical. The skid ratio is
   *     stddev(||v_i - omega x r_i||) / max(mean(||v_i - omega x r_i||), epsilon)
   * and the worst module is the largest residual from the mean.
   *
   * Original algorithm published by FRC 1690 (Orbit) in their 2024 software session; this is a
   * PumpkinLib reimplementation, not vendored code. Documented rather than cited-and-copied
   * because there is no canonical open-source Java reference.
   */
  public SkidReport update(SwerveModuleState[] measured, double gyroOmegaRadPerSec);
}
```

Policy: **skid inflates odometry std devs; it never rejects.** Practitioners report hard rejection fights `SwerveDrivePoseEstimator`. Off by default in `rookie()`, on in `competition()`. When `skidding` is true for more than 0.25 s we also log `Pumpkin/Drive/Skid/Sustained` and, if traction is `SETPOINT_GENERATOR`, we do *not* back off — preventing skid is better than reacting to it.

### 8.5 `OdometryReport` — the routine that MEASURES your error

This is what tells a team whether they can trust their pose at all. Modeled on 3061-lib's tuning autos, packaged with assertions.

```java
package org.pumpkinlib.characterization;

public final class OdometryReport {

  /** Drive a closed square of side `side`, vision disabled, and measure the closure error. */
  public static Builder squareTest(PumpkinDrive drive, Distance side);
  /** Drive forward `d`, then back `d`. The classic first check (FRC 334's practice). */
  public static Builder outAndBack(PumpkinDrive drive, Distance d);
  /** Spin in place N full rotations and measure heading closure. */
  public static Builder spinTest(PumpkinDrive drive, int rotations);
  /** Follow an existing trajectory and report pose error vs the trajectory's own samples. */
  public static Builder trajectoryTest(PumpkinDrive drive, PumpkinTrajectory traj);

  public static final class Builder {
    public Builder withVision(boolean enabled);      // default false for the pure-odometry number
    public Builder withSpeed(LinearVelocity v);
    public Builder repeat(int times);
    public Command asCommand();                      // runs it and stores the result
  }

  public record Result(
      String testName,
      double closureErrorMeters,       // distance between start pose and final pose
      double closureErrorPercent,      // closureError / pathLength
      double headingDriftDegrees,
      double suggestedWheelRadiusMeters,   // back-solved from the closure error, if geometrically valid
      double visionDisagreementRmsMeters,  // odometry-only vs vision, when vision was on
      double maxTrajectoryErrorMeters,
      Verdict verdict) {

    public enum Verdict {
      TRUSTWORTHY,        // < 2% closure, < 2 deg heading drift over 12 m: drive-to-pose is safe
      MARGINAL,           // 2-5%: alignment will work but score-on-the-move will not
      UNTRUSTWORTHY       // > 5%: fix wheel radius / module offsets before doing anything else
    }
    public String explain();   // plain-English next action, printed to the console
  }

  public static Optional<Result> last();
  public static void writeMarkdown(Path out);
}
```

`explain()` output is deliberately prescriptive, e.g.:

> `UNTRUSTWORTHY — 12 m square closed 0.94 m off (7.8%), heading drifted 1.2 deg. Closure error is almost entirely radial, which means your wheel radius is too small by about 7%. Run PumpkinCharacterization.wheelRadius(drive) ON CARPET, then re-run this test. Do not tune drive-to-pose until this reads MARGINAL or better.`

**Gating rule:** `PumpkinDriveToPose` and any score-on-the-move solver log a warning when the last `OdometryReport.Verdict` is `UNTRUSTWORTHY` or absent. SOTM on a 30 cm pose error is worse than stopping to score.

---

## 9. Drive-to-pose / scoring alignment

The most reused command in every elite codebase and the one small teams write worst (usually a raw P controller that oscillates). 6328's structure is specific and non-obvious; we package it with tolerance, timeout, and abort.

```java
package org.pumpkinlib.nav;

public final class PumpkinDriveToPose {

  public static Builder builder(PumpkinDrive drive);

  public static final class Builder {
    /** BLUE-origin target, re-evaluated every loop. */
    public Builder target(Supplier<Pose2d> blueTarget);
    public Builder target(Pose2d blueTarget);

    /** Becomes the TrapezoidProfile.Constraints of BOTH ProfiledPIDControllers below. */
    public Builder constraints(LinearVelocity maxV, LinearAcceleration maxA,
                               AngularVelocity maxW, AngularAcceleration maxAlpha);

    /**
     * Gains for the two controllers this command builds. BOTH are ProfiledPIDController — the
     * profile IS the feedforward here, and ProfiledPIDController.getSetpoint() returns a
     * TrapezoidProfile.State whose .velocity is exactly the term we need:
     *
     *   m_linearController = new ProfiledPIDController(linearKp, 0.0, linearKd,
     *       new TrapezoidProfile.Constraints(maxV, maxA));
     *
     *   m_thetaController  = new ProfiledPIDController(thetaKp, 0.0, thetaKd,
     *       new TrapezoidProfile.Constraints(maxW, maxAlpha));
     *   m_thetaController.enableContinuousInput(-Math.PI, Math.PI);
     *
     * Note the type: a plain PIDController's getSetpoint() returns a bare double and will not
     * compile against this code. §5.3's Choreo controllers are plain PIDControllers for a
     * different and correct reason — a Choreo sample already carries its own profile.
     */
    public Builder gains(double linearKp, double linearKd, double thetaKp, double thetaKd);

    /**
     * Feedforward blend, the key 6328 trick. The profile feedforward is scaled by
     *   clamp((error - min) / (max - min), 0, 1)
     * so the trapezoid FF ramps out near the goal instead of slamming or creeping.
     */
    public Builder ffBlend(Distance minRadius, Distance maxRadius);      // default 0.05 m .. 0.60 m
    public Builder thetaFfBlend(Angle min, Angle max);                    // default 8 deg .. 40 deg

    public Builder tolerance(Distance linear, Angle angular);             // default 0.02 m, 1.5 deg
    /** Must hold tolerance this long before atGoal() latches. Kills the "flickers into tolerance" bug. */
    public Builder settleTime(double seconds);                            // default 0.06 s

    public Builder timeout(double seconds);                               // default 3.0 s
    /** Command ends (unsuccessfully) when this becomes true. */
    public Builder abortWhen(BooleanSupplier condition);
    /** Driver keeps partial authority: their stick is added, scaled. Never zero. */
    public Builder driverNudge(DoubleSupplier x, DoubleSupplier y, double scale);
    /** Override only the rotation channel — for score-on-the-move. */
    public Builder omegaOverride(Supplier<OptionalDouble> radPerSec);
    /**
     * Control the MECHANISM's point rather than the robot center (the 254 pattern).
     * The controller drives (robotPose + offset) to the target.
     */
    public Builder controlPointOffset(Transform2d robotToControlPoint);

    public PumpkinDriveToPose build();
  }

  /** The command. Declares drive.requirement(). */
  public Command asCommand();
  /** True when inside tolerance for settleTime. Valid while the command runs. */
  public Trigger atGoal();
  public boolean withinTolerance(double linearMeters, Rotation2d angular);
  public double linearErrorMeters();
  public Rotation2d angularError();
  /** Why it ended: GOAL | TIMEOUT | ABORTED | INTERRUPTED. Logged and queryable. */
  public EndReason lastEndReason();

  public enum EndReason { GOAL, TIMEOUT, ABORTED, INTERRUPTED, RUNNING }

  // ---- one-liners ---------------------------------------------------------
  public static Command to(PumpkinDrive drive, Pose2d blueTarget);
  public static Command to(PumpkinDrive drive, Supplier<Pose2d> blueTarget);
  /** Coarse pathfind + fine align handoff, the composition every elite team writes. */
  public static Command pathfindThenAlign(PumpkinDrive drive, Pose2d blueTarget,
                                          PathConstraints coarse);
}
```

### 9.1 Core loop (implementation)

**Read the sign convention before the code — it is the part everyone gets wrong.**

The controlled scalar is `errorMeters`, the *distance* from the robot to the target, and its goal is `0`. A `ProfiledPIDController` driving a positive measurement to a zero goal produces a **negative** output (`calculate`'s error is `setpoint − measurement`), and its profiled `getSetpoint().velocity` is likewise negative because the distance is decreasing. Therefore the scalar we compute is negative while we are approaching, and it must be applied along a unit vector pointing **from the target back to the robot** — `current − target` — so that the product points at the target.

This is 6328's convention exactly, and it is why their `direction` is `currentPose.minus(targetPose).getAngle()` rather than the intuitive `target − current`. Pairing the intuitive direction with the negative scalar drives the robot *away from the target, accelerating*. If you change one, you must change the other; there is a unit test below whose only job is to catch that.

```java
package org.pumpkinlib.nav;

// Field declarations — the types are load-bearing.
private final ProfiledPIDController m_linearController;   // NOT PIDController: we need
private final ProfiledPIDController m_thetaController;    // getSetpoint().velocity
private final PumpkinStopwatch m_timer       = new PumpkinStopwatch();   // §1.8, Clock-backed
private final PumpkinStopwatch m_settleTimer = new PumpkinStopwatch();
private boolean m_settling = false;
private EndReason m_end = EndReason.RUNNING;

private static final double kEpsilonMeters = 1e-6;

PumpkinDriveToPose(/* built by Builder */) {
  m_linearController = new ProfiledPIDController(
      m_linearKp.get(), 0.0, m_linearKd.get(),
      new TrapezoidProfile.Constraints(m_maxV, m_maxA));
  m_thetaController = new ProfiledPIDController(
      m_thetaKp.get(), 0.0, m_thetaKd.get(),
      new TrapezoidProfile.Constraints(m_maxW, m_maxAlpha));
  m_thetaController.enableContinuousInput(-Math.PI, Math.PI);
}
```

**`initialize()` — seeding is not optional.** A `ProfiledPIDController` whose internal state is stale generates a feedforward that has nothing to do with where the robot actually is or how fast it is actually closing. That happens on every restart, every interrupt-and-rerun, every `alignAtEnd` handoff from a trajectory at 3 m/s, and every time a defender shoves the robot. Seeding it from the measured error and the measured closing speed is what makes the handoff smooth instead of a lurch.

```java
@Override
public void initialize() {
  m_end = EndReason.RUNNING;
  m_settling = false;
  m_settleTimer.stop();
  m_settleTimer.reset();
  m_timer.restart();

  Pose2d current = m_drive.getPose().transformBy(m_controlPointOffset);
  Pose2d target  = PumpkinField.apply(m_blueTarget.get());

  double errorMeters = current.getTranslation().getDistance(target.getTranslation());

  // Robot -> target bearing, used ONLY to project the current velocity onto the closing axis.
  Rotation2d toTarget = target.getTranslation().minus(current.getTranslation()).getAngle();

  ChassisSpeeds fieldSpeeds = m_drive.getFieldRelativeSpeeds();
  Translation2d fieldVel =
      new Translation2d(fieldSpeeds.vxMetersPerSecond, fieldSpeeds.vyMetersPerSecond);

  // d(distance)/dt. Negative when we are already closing on the target. Clamped at 0 so an
  // outbound velocity does not seed the profile with an even larger distance goal.
  double distanceRate = Math.min(0.0, -fieldVel.rotateBy(toTarget.unaryMinus()).getX());

  m_linearController.reset(errorMeters, distanceRate);
  m_thetaController.reset(current.getRotation().getRadians(), fieldSpeeds.omegaRadiansPerSecond);
}
```

**`execute()`:**

```java
@Override
public void execute() {
  Pose2d current = m_drive.getPose().transformBy(m_controlPointOffset);
  Pose2d target  = PumpkinField.apply(m_blueTarget.get());

  // TARGET -> ROBOT. Paired with a negative scalar, this points the robot AT the target.
  // 6328 convention. See the note above this code block before touching it.
  Translation2d fromTarget = current.getTranslation().minus(target.getTranslation());
  double errorMeters = fromTarget.getNorm();
  Rotation2d direction = errorMeters < kEpsilonMeters ? Rotation2d.kZero : fromTarget.getAngle();

  // 1-D profiled controller on the distance-to-goal scalar. Both terms are negative while
  // approaching; the profile IS the feedforward, so there is no standalone TrapezoidProfile
  // and no hand-stepped State to fall out of sync with reality.
  double ffScale = PumpkinMath.clamp(
      (errorMeters - m_ffMinRadius) / (m_ffMaxRadius - m_ffMinRadius), 0.0, 1.0);
  double fbVel = m_linearController.calculate(errorMeters, 0.0);
  double ffVel = m_linearController.getSetpoint().velocity * ffScale;
  double linearVel = fbVel + ffVel;
  if (errorMeters < m_tolMeters) linearVel = 0.0;      // no creeping inside tolerance

  double thetaErrorRad = MathUtil.angleModulus(
      target.getRotation().minus(current.getRotation()).getRadians());
  double thetaFfScale = PumpkinMath.clamp(
      (Math.abs(thetaErrorRad) - m_thetaFfMin) / (m_thetaFfMax - m_thetaFfMin), 0.0, 1.0);
  double omega = m_thetaController.calculate(current.getRotation().getRadians(),
                                             target.getRotation().getRadians())
               + m_thetaController.getSetpoint().velocity * thetaFfScale;

  OptionalDouble override = m_omegaOverride.get();
  if (override.isPresent()) omega = override.getAsDouble();

  // Translation2d(double distance, Rotation2d angle) handles the negative magnitude correctly:
  // x = distance * cos(angle), y = distance * sin(angle).
  Translation2d driveVelocity = new Translation2d(linearVel, direction);

  ChassisSpeeds field = new ChassisSpeeds(
      driveVelocity.getX() + m_nudgeX.getAsDouble() * m_nudgeScale,
      driveVelocity.getY() + m_nudgeY.getAsDouble() * m_nudgeScale,
      omega);

  // ---- settle gating: the timer only runs while we are actually inside tolerance ----------
  // Without this reset, hasElapsed(settleSeconds) is true 0.06 s after the command starts
  // regardless of error, and the command reports GOAL from anywhere on the field.
  if (withinTolerance(m_tolMeters, m_tolRotation)) {
    if (!m_settling) { m_settleTimer.restart(); m_settling = true; }
  } else {
    m_settling = false;
    m_settleTimer.stop();
    m_settleTimer.reset();
  }

  // Goes through THE funnel: traction limiting and discretization apply here too.
  m_drive.driveFieldRelative(field);

  PumpkinLog.get().put("Pumpkin/Align/Target", target);
  PumpkinLog.get().put("Pumpkin/Align/ErrorMeters", errorMeters);
  PumpkinLog.get().put("Pumpkin/Align/ErrorDegrees", Math.toDegrees(thetaErrorRad));
  PumpkinLog.get().put("Pumpkin/Align/CommandedFieldSpeeds", field);
  PumpkinLog.get().put("Pumpkin/Align/ProfileVelocity", m_linearController.getSetpoint().velocity);
  PumpkinLog.get().put("Pumpkin/Align/Settling", m_settling);
  PumpkinLog.get().put("Pumpkin/Align/AtGoal", m_settling && m_settleTimer.hasElapsed(m_settleSeconds));
}

@Override
public boolean isFinished() {
  if (m_abort.getAsBoolean())               { m_end = EndReason.ABORTED; return true; }
  if (m_timer.hasElapsed(m_timeoutSeconds)) { m_end = EndReason.TIMEOUT; return true; }
  if (m_settling && m_settleTimer.hasElapsed(m_settleSeconds)) { m_end = EndReason.GOAL; return true; }
  return false;
}

@Override
public void end(boolean interrupted) {
  m_drive.stop();
  if (interrupted && m_end == EndReason.RUNNING) m_end = EndReason.INTERRUPTED;
  if (m_end == EndReason.TIMEOUT) {
    PumpkinAlerts.warn("align.timeout",
        "Drive-to-pose timed out " + String.format("%.3f", linearErrorMeters()) + " m from target. "
      + "Either the target is unreachable, tolerance is too tight, or odometry is drifting. "
      + "Run OdometryReport.squareTest(drive, Meters.of(3)) to check.");
  }
  PumpkinLog.get().put("Pumpkin/Align/EndReason", m_end.name());
}
```

Every gain (`linearKp`, `linearKd`, `thetaKp`, `thetaKd`, all four constraints, both FF blend pairs, both tolerances) is a `Tunable`, hot-reloadable, FMS-gated off. Changing a `Tunable` gain calls `setPID` on the live controller; it never reconstructs it, because reconstruction would drop the profile state that `initialize()` seeded. Defaults: `linearKp = 4.0`, `linearKd = 0.0`, `thetaKp = 5.0`, `thetaKd = 0.4`, constraints `3.0 m/s / 4.0 m/s² / 540 °/s / 720 °/s²`.

**What changed from the first draft and why it mattered.** The original code paired `direction = target − current` with a negative scalar, which commanded velocity directly away from the target — the robot would have accelerated backwards until it timed out or hit something. It also stepped a standalone `TrapezoidProfile` against an `m_linearProfileState` that was never seeded from the measured error, so the "feedforward" was an arbitrary number, and it read `getSetpoint().velocity` off a controller that §5.3 declared as a plain `PIDController` (whose `getSetpoint()` returns a `double`) — a compile error. And `isFinished()` checked a settle timer that nothing ever reset, so the command returned `GOAL` roughly 0.06 s after starting, from anywhere on the field. Four defects, in the most-copied command in the library, in a block labeled "essentially verbatim". The tests in §9.2 exist specifically because a design document is not a compiler.

### 9.2 The tests that keep §9.1 honest

These are not optional coverage. Each one pins a defect that was actually present in the first draft, and all three run off-robot with no HAL — `PumpkinDriveToPose`'s math depends only on `Pose2d`/`ChassisSpeeds`/`ProfiledPIDController`, so a fake `PumpkinDrive` backed by a mutable pose is enough.

```java
package org.pumpkinlib.nav;

class PumpkinDriveToPoseTest {

  /** THE sign test. Robot 1 m behind the target on +x; the command must drive toward +x. */
  @Test void drivesTowardTheTarget() {
    FakeDrive drive = FakeDrive.at(new Pose2d(0.0, 0.0, Rotation2d.kZero));
    PumpkinDriveToPose cmd = PumpkinDriveToPose.builder(drive)
        .target(new Pose2d(1.0, 0.0, Rotation2d.kZero))
        .build();

    cmd.initialize();
    cmd.execute();

    ChassisSpeeds commanded = drive.lastFieldRelative();
    assertTrue(commanded.vxMetersPerSecond > 0.1,
        "Expected positive vx toward the target, got " + commanded.vxMetersPerSecond
      + " — the direction vector and the profiled-controller sign have been decoupled again. "
      + "See the sign convention note in design/05 §9.1.");
    assertEquals(0.0, commanded.vyMetersPerSecond, 1e-6);
  }

  /** Same test, rotated 135 deg, so a lucky axis sign cannot pass it. */
  @Test void drivesTowardTheTargetOffAxis() {
    FakeDrive drive = FakeDrive.at(new Pose2d(2.0, 3.0, Rotation2d.kZero));
    Pose2d target = new Pose2d(1.0, 4.0, Rotation2d.kZero);
    PumpkinDriveToPose cmd = PumpkinDriveToPose.builder(drive).target(target).build();

    cmd.initialize();
    cmd.execute();

    ChassisSpeeds c = drive.lastFieldRelative();
    // Commanded velocity must have a positive component along the robot->target unit vector.
    Translation2d unit = target.getTranslation().minus(new Translation2d(2.0, 3.0)).div(Math.hypot(1, 1));
    double along = c.vxMetersPerSecond * unit.getX() + c.vyMetersPerSecond * unit.getY();
    assertTrue(along > 0.1, "Commanded velocity points away from the target; along = " + along);
  }

  /** THE settle test. Far from the goal, the command must NOT report success after settleTime. */
  @Test void doesNotFinishWhileOutsideTolerance() {
    FakeDrive drive = FakeDrive.at(new Pose2d(0.0, 0.0, Rotation2d.kZero));
    PumpkinDriveToPose cmd = PumpkinDriveToPose.builder(drive)
        .target(new Pose2d(5.0, 0.0, Rotation2d.kZero))
        .settleTime(0.06)
        .timeout(30.0)
        .build();

    cmd.initialize();
    for (int i = 0; i < 50; i++) {            // 1.0 s of loops, robot pinned in place
      cmd.execute();
      assertFalse(cmd.isFinished(), "Reported GOAL at loop " + i + " while 5 m from the target");
    }
  }

  /** The seeding test. Arriving at 3 m/s must not produce a feedforward that reverses. */
  @Test void seedsTheProfileFromMeasuredClosingVelocity() {
    FakeDrive drive = FakeDrive.at(new Pose2d(0.0, 0.0, Rotation2d.kZero))
        .withFieldSpeeds(new ChassisSpeeds(3.0, 0.0, 0.0));    // already closing fast
    PumpkinDriveToPose cmd = PumpkinDriveToPose.builder(drive)
        .target(new Pose2d(1.0, 0.0, Rotation2d.kZero))
        .build();

    cmd.initialize();
    cmd.execute();
    assertTrue(drive.lastFieldRelative().vxMetersPerSecond > 0.0,
        "An alignAtEnd handoff at speed commanded a reversal — the profile was not seeded.");
  }
}
```

`drivesTowardTheTarget` is the acceptance test for this whole section. If it is deleted or weakened, the sign bug comes back.

### 9.3 Differential drives

`PumpkinDriveToPose` works on differential drives with `constraints(..., maxW, maxAlpha)` respected but strafe unavailable. The builder detects `!geometry().holonomic()` and switches to a two-phase controller: rotate-to-bearing → drive-forward → rotate-to-final-heading, with the same tolerance/timeout/abort surface. This is worse than a holonomic align and we say so in the log, once.

---

## 10. Complete end-to-end example

This is the whole drivetrain + auto surface for a competitive robot. Everything below is code the team actually writes.

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;

public class RobotContainer {

  private final CommandXboxController m_driver = new CommandXboxController(0);

  // ---- 1. Drivetrain: adapt the CTRE Tuner-X project we already have -------------
  private final PumpkinDrive m_drive = PumpkinDrive.of(
      new CtreSwerveBackend(TunerConstants.createDrivetrain(), /* requirement */ null,
          DriveGeometry.swerve(TunerConstants.kFrontLeftLocation, TunerConstants.kFrontRightLocation,
                               TunerConstants.kBackLeftLocation,  TunerConstants.kBackRightLocation,
                               Inches.of(2.167)),
          DriveLimits.of(MetersPerSecond.of(4.69), MetersPerSecondPerSecond.of(8.0),
                         DegreesPerSecond.of(540), DegreesPerSecondPerSecond.of(720))),
      PumpkinDriveConfig.competition());

  private final Superstructure m_superstructure = new Superstructure();   // implements GoalBus<Goal>
  private final Intake m_intake = new Intake();
  private final Sensors m_sensors = new Sensors();

  // ---- 2. Auto: both planners, pathfinding, and the goal bus, in four lines -------
  private final PumpkinAuto m_auto = PumpkinAuto.of(m_drive)
      .withPathPlanner()
      .withChoreo()
      .withPathfinding()
      .withGoals(m_superstructure)
      .actions(Map.of(
          "intake",  m_intake.intakeCommand(),
          "prepL4",  m_superstructure.request(Goal.L4_PREP),
          "score",   m_superstructure.request(Goal.L4_SCORE)));

  private final PumpkinAutoSelector m_selector = new PumpkinAutoSelector("Pumpkin/Auto");

  public RobotContainer() {
    PumpkinField.configure(FieldSymmetry.ROTATIONAL);      // 2026 REBUILT

    // ---- 3. Driver control -------------------------------------------------------
    m_drive.requirement().setDefaultCommand(
        DriveInputStream.of(m_drive, m_driver::getLeftY, m_driver::getLeftX)
            .withRotationAxis(m_driver::getRightX)
            .deadband(0.10)
            .expo(2.0, 2.0)
            .scaleTranslation(() -> m_driver.getHID().getLeftBumperButton() ? 0.35 : 1.0)
            .allianceRelative(() -> true)
            .headingLock(m_driver.rightBumper())
            .asDefaultCommand());

    m_driver.start().onTrue(AlliancePerspective.zeroDriverHeading(m_drive));
    m_driver.x().whileTrue(Commands.run(m_drive::brake, m_drive.requirement()));

    // ---- 4. One-button scoring alignment -----------------------------------------
    m_driver.a().whileTrue(
        PumpkinDriveToPose.builder(m_drive)
            .target(() -> FieldPoses.nearestScoringPose(m_drive.getPose()))
            .tolerance(Meters.of(0.02), Degrees.of(1.5))
            .timeout(2.5)
            .abortWhen(() -> Math.abs(m_driver.getLeftY()) > 0.5)   // driver always wins
            .driverNudge(m_driver::getLeftY, m_driver::getLeftX, 0.25)
            .build()
            .asCommand()
            .alongWith(m_superstructure.request(Goal.L4_PREP)));

    // ---- 5. Autos: three building blocks, twelve variants -------------------------
    m_selector.addRoutine("Midline Sweep",
        List.of(AutoQuestion.of("Start", "LEFT", "CENTER", "RIGHT"),
                AutoQuestion.of("First piece", "M1", "M2", "M3"),
                AutoQuestion.of("Bail to park?", "YES", "NO")),
        this::midlineSweep);
    m_selector.addRoutine("Do Nothing", List.of(), r -> Commands.none());
    m_selector.importPathPlannerAutos();
    m_selector.setDefault("Do Nothing");

    RobotModeTriggers.autonomous().whileTrue(m_selector.selectedCommandScheduler());

    // ---- 6. Characterization, bound behind a tuning-mode gate ---------------------
    if (PumpkinConfig.tuningMode()) {
      m_driver.back().onTrue(PumpkinCharacterization.all(m_drive));
      m_driver.y().onTrue(OdometryReport.squareTest(m_drive, Meters.of(3.0)).asCommand());
    }
  }

  private Command midlineSweep(AutoResponses r) {
    String side  = r.get(0);
    String first = r.get(1);
    boolean bail = r.get(2).equals("YES");

    PumpkinTrajectory start = m_auto.traj(side + "StartTo" + first);
    PumpkinTrajectory back  = m_auto.traj(first + "ToScore");
    PumpkinTrajectory sweep = m_auto.traj("ScoreToSweep" + side);

    return m_auto.routine("Midline " + side + " " + first)
        .startAt(start)
        .step("grab " + first, s -> s
            .follow(start)
            .goal(Goal.INTAKE)
            .before(0.30, m_intake.intakeCommand())
            .successWhen(m_sensors::hasPiece)
            .deadline(start.totalTimeSeconds() + 1.0)
            .retry(1))
        .step("score", s -> s
            .follow(back)
            .goal(Goal.L4_PREP)
            .onlyIf(m_sensors::hasPiece)
            .then(m_superstructure.request(Goal.L4_SCORE).withTimeout(0.8)))
        .step("sweep", s -> s
            .follow(sweep)
            .goal(Goal.INTAKE)
            .aimWhileDriving(() -> m_superstructure.launchOnTheMoveOmega())
            .deadline(sweep.totalTimeSeconds() + 1.0))
        // SECONDS REMAINING, never elapsed seconds, never a literal 15.0 (D15). "Bail to park?"
        // = YES reserves 3.0 s for the park; NO reserves only 0.5 s, which is effectively
        // "park only if the sweep happens to finish early". Both are computed by the routine
        // against FieldMap.autoPeriodSeconds(), so 2027's period is a one-line FieldMap edit.
        .skipToAfter(/* seconds left */ bail ? 3.0 : 0.5, "park")
        .step("park", s -> s.driveTo(() -> FieldPoses.kBluePark).deadline(2.0))
        .onEnd(m_superstructure.request(Goal.STOW))
        .build();
  }

  public void robotInit() {
    m_auto.warmup();            // loads every traj, JIT-warms both followers and the pathfinder
    m_auto.verifyAlliance();    // logs blue + red start poses for every variant
  }

  public void robotPeriodic() {
    m_drive.periodic();         // skid detection, alliance perspective, logging, alerts
  }
}
```

**Line count for a competitive drivetrain + auto stack: ~110 lines**, none of which is `AutoBuilder.configure`'s eight arguments, a hand-written `shouldFlipPath` lambda, a discretization call, a slip limiter, a heading controller, a wheel-force feedforward plumbing block, or a per-alliance trajectory file.

---

## 11. What we deliberately do NOT do

| We do not build | Because this already does it | What we do instead |
|---|---|---|
| A swerve library | CTRE Phoenix 6 `SwerveDrivetrain` (Tuner X), AdvantageKit swerve templates, YAGSL, WPILib `SwerveDriveKinematics` | `DriveBackend` adapters, ~120 lines each |
| Odometry / pose estimation math | WPILib `SwerveDrivePoseEstimator`, `DifferentialDrivePoseEstimator`, CTRE's built-in estimator | Trust arbitration, timestamp discipline, and an error *measurement* report |
| A high-frequency odometry thread | Phoenix 6's native odometry thread; AdvantageKit's `PhoenixOdometryThread`/`SparkOdometryThread` | Select and verify the mode; warn loudly when degraded |
| Trajectory generation | Choreo (TrajoptLib/Sleipnir, time-optimal), PathPlanner (spline + torque-aware profile) | One trigger surface over both |
| Pathfinding | PathPlanner AD* / `LocalADStar` over `navgrid.json` | `PumpkinNav` façade + `LocalADStarAK` for replay determinism + warmup |
| A slip/setpoint generator | PathPlannerLib `SwerveSetpointGenerator` (254-derived, maintained) | Insert it in the funnel so **teleop** gets it too |
| A path-following controller | PathPlanner `PPHolonomicDriveController` / `PPLTVController`; WPILib `LTVUnicycleController` | Wire them correctly, once, with the right overload for the drivetrain type |
| Alliance flipping math | PathPlanner `FlippingUtil`, `PathPlannerPath.flipPath()/mirrorPath()`; Choreo `AutoTrajectory.mirrorX()/mirrorY()`, `Trajectory.flipped()` | Declare symmetry once and configure both from it |
| A physics simulator | maple-sim (dyn4j, 2026 REBUILT field + game pieces) | Select it as the default sim backend; drive the same auto headlessly |
| A log viewer / 3D replay | AdvantageScope | Emit stable, unit-tagged keys it can render |
| A logging framework | AdvantageKit, WPILib DataLog/Epilogue, CTRE SignalLogger | A 10-method `PumpkinLog` SPI that fans out to whichever the team uses |
| SysId | WPILib `SysIdRoutine`; CTRE `SysIdSwerveTranslation`/`Rotation`/`SteerGains` | Wrap them, and add the three things SysId does *not* measure: wheel radius, MOI, COF |
| A graph-search superstructure | 254's `AStarSolver` + `SuperstructureStateMachine`, 6328's JGraphT graph — and the PumpkinLib mechanism domain | Define the `GoalBus` contract; consume it |
| A dashboard | Elastic | Publish stable NT4 topics; the dashboard domain generates the layout |

---

## 12. WPILib 2027 migration plan (this domain)

The 2027 break lands ~5 months from now: `edu.wpi.first` → `org.wpilib`, `ChassisSpeeds` → `ChassisVelocities`, kinematics immutable, `ChassisAccelerations` added, `RamseteCommand`/`SwerveControllerCommand`/`MecanumControllerCommand` removed, Commands v3 (coroutines) alongside a pruned v2, Java 25, Systemcore, NT3 removed, Shuffleboard/SmartDashboard/PathWeaver/RobotBuilder removed. Phoenix 6 renames `ApplyRobotSpeeds` → `ApplyRobotVelocity` and removes `new Device(int, String)`.

What this design does about it, concretely:

1. **Vendor types appear in exactly five files.** `CtreSwerveBackend`, `YagslBackend`, `AdvantageKitSwerveBackend`, `PathPlannerSource`, `ChoreoSource`. Nothing else in this domain imports `com.ctre`, `com.pathplanner`, `choreo`, `swervelib`, or `org.littletonrobotics`. The 2027 port for those five files is bounded and known today.
2. **`PumpkinAuto`, `PumpkinTrajectory`, `AutoStep`, `PumpkinDriveToPose`, `PumpkinField` leak zero vendor types.** A team's `RobotContainer` needs no rewrite.
3. **WPILib types are used freely** and ported by find/replace. `ChassisSpeeds` → `ChassisVelocities` is mechanical; `SwerveModuleState.optimize` is already migrated to the 2026 mutating-instance form.
4. **`SkidDetector`, `TractionLayer` math, `PumpkinMath`, the trigger engine's timing logic, and `OdometryReport`'s error math are HAL-free and command-free** — they live in a `pumpkin-solvers` source set with no WPILib command dependency, so they port byte-for-byte and are unit-testable off-robot today.
5. **Commands are always *returned* from factories, never subclassed by users.** Commands v3's coroutine model changes how commands are *authored*, not how they are *composed*, so a v3 backend is an internal swap.
6. **No `SmartDashboard`/`Shuffleboard`/`SendableChooser`-on-SmartDashboard from library code.** `PumpkinAutoSelector` publishes raw NT4 topics.
7. **Two artifacts from one tree**: `org.pumpkinlib:pumpkin-drive-auto:2026.x` and `:2027.x`, differing only in the five adapter files and the package roots.

---

## 13. Delivery plan

| Phase | Contents | Person-weeks |
|---|---|---|
| **v0.1 (ship first)** | `PumpkinDrive` + funnel; `CtreSwerveBackend`; `DifferentialBackend`; `PumpkinField`/`AlliancePerspective`/`AllianceValue`; `PumpkinAuto.withPathPlanner()`; `PumpkinTrajectory` (PathPlanner source); `PumpkinAutoRoutine`/`AutoStep` with budgets, deadlines, `successWhen`, `orElse`, `retry`; `PumpkinDriveToPose`; `PumpkinCharacterization.feedforward/wheelRadius`; `DriveSelfCheck`; `PumpkinNav` | 4.0 |
| **v0.2** | `ChoreoSource`; `AdvantageKitSwerveBackend`; `HandRolledSwerveBackend`; `DriveInputStream`; `TractionLayer`; `PumpkinAutoSelector` with dependent questions; `OdometryReport` | 3.0 |
| **v0.3** | `YagslBackend`; `SkidDetector` + `OdometryTrust`; `PumpkinCharacterization.slipCurrent/momentOfInertia/wheelCof` + planner writeback; `PumpkinAutoTest` + `pumpkinAutoReport` Gradle task; `MecanumBackend` | 3.5 |
| **v0.4 (2027 branch)** | `org.wpilib` port, Commands v3 backend, Phoenix 6 2027 request renames | 1.5 |

**Total: ~12 person-weeks** for the domain, offseason-realistic at ~8 focused hours/week from Aug through Dec 2026 with one experienced developer.

---

## 14. Open Questions

1. **PathPlanner single-path event-marker times.** Does PathPlannerLib 2026.1.2 expose a public accessor for a `PathPlannerPath`'s event markers and their trajectory times outside a running `PathPlannerAuto`? If yes, `PathPlannerSource.eventTimes()` uses it; if no, we parse `deploy/pathplanner/paths/*.path` JSON. **Ship the JSON parser regardless**, and switch if the accessor is confirmed. Marked **[UNVERIFIED]** in §6.1.
2. **Navgrid hot-swapping.** 254 swaps between `navgrid.json` / `auto_navgrid.json` / `backoff_navgrid.json` by phase, but achieved this by *vendoring* PathPlannerLib. Does 2026.1.2 expose a public API to point `LocalADStar` at a different grid file? If not, `PumpkinNav.useProfile(String)` either ships our own `Pathfinder` implementation (~200 lines reading a selected grid) or is dropped from v0.3. **Needs verification before committing to the API.**
3. **`SwerveDrivetrain` module accessor for wheel-radius characterization.** Confirm whether `getModule(int)`/`getModules()` exist and expose the drive motor in Phoenix 6 26.x. The `ModulePositions[i].distanceMeters / wheelRadiusMeters` fallback is definitely correct and is what we ship; the direct accessor would be marginally more accurate (no wheel-radius circularity). **Low risk either way.**
4. ~~**Auto period length for 2027.**~~ **RESOLVED, and it was a real defect, not an open question.** Every consumer now reads `FieldMap.autoPeriodSeconds()`: `PumpkinAutoTest.Builder.withAutoPeriodSeconds` defaults to it (§7.4), `PumpkinAutoRoutine.skipToAfter` is specified in *seconds remaining* against it and `build()` rejects an argument that is `>= ` the period or `<= 0` (§6.4), and both worked examples pass seconds-remaining (§6.7, §10). D15 is now structurally enforced rather than aspirational. The only remaining action at 2027 kickoff is the one-line `FieldMap` edit this was always supposed to enable.
5. **maple-sim 2027/Systemcore support.** maple-sim is community-maintained and not officially blessed. If it does not ship for 2027 in time, `PumpkinAutoTest` degrades to a kinematic (no-slip, no-collision) sim world. We should design `SimWorld` so the degraded mode is a first-class option, not a failure.
6. **Should `TractionMode.SETPOINT_GENERATOR` be the default for `rookie()`?** Arguments for: it is the single biggest driver-visible improvement. Arguments against: it requires mass, MOI and COF, and a rookie team's guessed MOI makes it either useless or crippling. Current decision: **off in `rookie()`, on in `competition()`, and `PumpkinCharacterization.momentOfInertia` prints "you can now safely enable TractionMode.SETPOINT_GENERATOR" on success.** Revisit after we have real data from 8793 and 9143.
7. **Choreo `DifferentialSample` follow path.** `followChoreoSample` is specified for `SwerveSample`. The differential equivalent needs `DifferentialSample` (schema v2 added `alpha`) fed through `LTVUnicycleController`. The exact field names on `DifferentialSample` are **[UNVERIFIED]** — confirm against `choreo.autos/api/choreolib/java/choreo/trajectory/DifferentialSample.html` before implementing v0.2.
8. **`GoalBus` timing for `AutoStep.budget()`.** `plannedTransitionSeconds()` requires the mechanism domain to have measured transition costs (the 254 pattern). If that slips, budgets fall back to the trajectory's own planned time and mechanism overrun is not attributed. Acceptable for v0.1.
9. **Do we ship a `mecanum` backend at all?** Mecanum is effectively unmaintained community-wise and `MecanumControllerCommand` is removed in 2027. 135's Consul is the only framework with mecanum parity. Current decision: **yes, in v0.3**, because the cost is ~150 lines given the `DriveBackend` SPI and it is a genuine differentiator for a rookie team on a KOP-adjacent chassis. Revisit if v0.3 slips.
10. **`skipToAfter`'s zero point.** The guard measures from the routine command's `initialize()`, not from the FMS autonomous transition (§6.4). On a real field those differ by at most one scheduler loop, and the routine-relative zero is what makes the guard meaningful in `PumpkinAutoTest` and in a teleop-scheduled dry run. But a routine scheduled late — a `Commands.defer` that blocks on a `.traj` load that `warmup()` somehow missed — would shift the whole budget. We log `Pumpkin/Auto/RoutineStartLatency` so the shift is visible. **Open:** should `build()` hard-fail a routine whose start latency exceeded ~100 ms, or is the logged number enough? **Leaning: log only for v0.1**, revisit once we have real match logs from 8793 and 9143.

11. **Where does `WheelForces` module-order validation live?** PathPlanner emits FL, FR, BL, BR. CTRE's `SwerveDrivetrain` module order is whatever Tuner X generated. If a team reorders modules in Tuner X, the force arrays silently fight the robot under acceleration and nothing errors. We can detect it at runtime (correlate commanded force direction against measured module velocity direction during a hard acceleration) — is that worth ~60 lines in `DriveSelfCheck`, or is a documented convention plus the geometry sign check in §3.8 sufficient? **Leaning: add the runtime correlation check as a `PumpkinCharacterization.verifyModuleOrder(drive)` command rather than a boot check.**

---

## 15. Adversarial review log

This document has been through one adversarial review pass. Findings are recorded here rather than silently absorbed, because the *reason* a line reads the way it does is the part that rots first.

| # | Section | Finding | Disposition |
|---|---|---|---|
| 1 | §9.1 `execute()` | Sign error: `direction = target − current` paired with a negative profiled-controller scalar drives the robot **away** from the target, accelerating. | **Applied.** Direction flipped to `current − target` (6328's convention), with the sign contract written out in prose *above* the code and pinned by `drivesTowardTheTarget` / `drivesTowardTheTargetOffAxis` in §9.2. |
| 2 | §9.1 `isFinished()` | Settle timer was never reset while outside tolerance, so the command reported `GOAL` ~0.06 s after start from anywhere on the field. | **Applied.** `m_settling` gate in `execute()`, `isFinished()` requires `m_settling && hasElapsed(...)`, pinned by `doesNotFinishWhileOutsideTolerance`. |
| 3 | §9.1 fields | Standalone `TrapezoidProfile` + `m_linearProfileState` was never seeded, so the feedforward was an arbitrary number; `getSetpoint().velocity` was also read off a type (`PIDController`) whose `getSetpoint()` returns `double`. | **Applied.** Both controllers declared explicitly as `ProfiledPIDController`; the standalone profile is deleted; `initialize()` seeds from measured error and measured closing velocity; pinned by `seedsTheProfileFromMeasuredClosingVelocity`. §5.3 carries a pointer explaining why *its* controllers are correctly plain `PIDController`s. |
| 4 | §6.2 | `m_doneAt = Timer.getFPGATimestamp()` violated Principle 9 / D12 and would have tripped the library's own replay tripwire. | **Applied, and generalized.** Every timer in the domain is `PumpkinStopwatch` (§1.8), rule 8 in §0.3 bans the `Timer` *constructor* as well as the static, §6.2.1 explains why an instance is not an exemption, and §1.8 ships the ArchUnit rule. |
| 5 | §3.6 | `deadband(double)` cited a 2-D `MathUtil.applyDeadband` overload that does not exist in WPILib 2026.2.2. | **Applied.** Implemented as `PumpkinMath.deadband2d` in Tier 0 (§2); §3.6 cites it and keeps `MathUtil.copySignPow` for `expo`. |
| 6 | §3.3 | `requirement()` returned the backend's subsystem, which half the backends do not have — `NullPointerException` on the first line of driver code. | **Applied.** `DriveBackend.existingSubsystem()` returns `Optional`; `requirement()` is total via a lazily-registered synthetic `Subsystem`; owner logged to `Pumpkin/Drive/RequirementOwner` (CRITICAL); double-registration caught by `DriveSelfCheck` check 8. |
| 7 | §8 | Uncited "50 Hz → 0.388 m, 250 Hz → 0.297 m" statistic, in a design whose Principle 10 is "every performance claim is measured" — and 0.297 m would be rated `UNTRUSTWORTHY` by this document's own scale five sections later. | **Applied.** Statistic deleted, replaced with the defensible consistency argument and an explicit promise to ship no number until `OdometryReport` measures one. Revision note left in place so it cannot quietly return. |
| 8 | §6.4 / §6.7 | `before()` silently dead on non-trajectory steps; `skipToAfter` hardcoded the auto period (D15); `.retry(0)` was a no-op that read as configuration; no trajectory→align handoff. | **Applied, all four.** `build()` throws on `before()` without `follow()` and on `retry(0)`; `skipToAfter` respecified as *seconds remaining* against `FieldMap.autoPeriodSeconds()` with a migration-guard error message that computes the new argument; `alignAtEnd(...)` added and used in both worked examples. |

Two things were changed **beyond** what the review asked for, and are flagged as such in place:

- **§1.8** — the review's ArchUnit rule was written with a predicate (`nameIs`) that is not ArchUnit API. The rule's intent is kept; the spelling is corrected to `target(name(...))` / `target(owner(assignableTo(...)))` so it compiles.
- **§8.1.1** — no finding was routed to characterization safety. It was added anyway. `slipCurrent` is a stalled-rotor ramp and `wheelCof` accelerates a robot down 8 m of carpet without self-stopping; the review's own standard is that shipped code blocks get copied verbatim, and that standard bites harder here than in §9.1.

One item was found while applying the review and is not attributable to it: **§10's `midlineSweep` still passed elapsed seconds to `skipToAfter`** (`bail ? 12.0 : 14.5`) after §6.7 had been converted to seconds-remaining. Under the new semantics that reads "park with 14.5 s left", firing on tick one. Fixed, and it is exactly the case validation rule 4's second clause now catches at build time — which is the argument for putting the guard in `build()` rather than in a doc comment.

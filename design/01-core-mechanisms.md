# PumpkinLib Design 01 — Core: Hardware Abstraction, Mechanisms, Config, Superstructure

**Domain:** CORE
**Status:** Design complete (revision 2, post adversarial review), ready to implement
**Target:** WPILib 2026 (`edu.wpi.first.*`, Java 17) as the *build* line; WPILib 2027 (`org.wpilib.*`, Java 25, SystemCore) as the *shipping* line. See §10.
**Verified against:** Phoenix 6 26.1/26.2, REVLib 2026.0.x, WPILib 2026.2.2, PathPlannerLib 2026.1.2.
Every vendor API name in this document was either read out of the vendor javadoc during design or is marked **[UNVERIFIED]** inline.

**Revision 2 changelog** (what an adversarial API-truth / feasibility / pedagogy review changed):

| # | Was | Now | §|
|---|---|---|---|
| 1 | `m_feedforward.calculate(pos, vel)` on a raw WPILib feedforward | `Controllers.Feedforward` (doc 02) over the verified `calculateWithVelocities` overloads | §6.2, §3.8 |
| 2 | `RIO_FULL` ran in user units with volts-per-SI gains (57.3× / 39.37× error) | `RIO_FULL` runs **entirely in SI**; the one place SI conversion happens | §4.4, §6.2 |
| 3 | `GravityArmPositionOffset = +horizontalReference` | **negated**, plus a Tier-1 ±0.25 rot range guard | §3.5 |
| 4 | Expo requests with unset `MotionMagicExpo_kV/_kA` (CTRE defaults 0.12 / 0.1) | derived from measured `kV`/`kA`, Tier-2 alert when unmeasured | §3.5 |
| 5 | `updateInputs` read signals `configureSignals` never subscribed | `SignalSet` — subscribe and read are one declaration; unsubscribed ⇒ `NaN`, never a frozen 0 | §3.5 |
| 6 | `.andThen(trigger::getAsBoolean)` (compiles as `Runnable`, no-op) | `.andThen(Commands.waitUntil(...))` | §9.3 |
| 7 | Flagship elevator: 45:1 gave 0.62 m/s free speed but the config asked for 1.6 m/s | 12:1, all four derived numbers recomputed and CI-asserted against `describe()` | §4.4, §5.4, §5.6, §9.1 |
| 8 | `applyVerified` (blocking, ×5) called from `periodic()` at 10 Hz | `applyFast` (0 s timeout) for gains; `applyVerified` for construction / disabled / self-test only | §3.9 |
| 9 | Setpoint sent once, forever; a device reset silently dropped the mechanism | `hasResetOccurred()` re-arm + 10 Hz setpoint heartbeat + `deviceResetCount` | §3.5 |
| 10 | `.foc(true)` swapped the whole request set and reinterpreted gains as amps | `.foc()` sets only `withEnableFOC`; `outputMode(TORQUE_CURRENT)` is a separate, v0.2, `ConfigError`-guarded field | §3.5 |
| 11 | `MotorInputs implements LoggableInputs` inside `pumpkinlib-mechanism` (ArchUnit violation) | `implements PumpkinInputs`; AdvantageKit adapter owns `LoggableInputs` | §1.1, §3.4 |
| 12 | Tier-1 validation threw from a record constructor → `ExceptionInInitializerError`, dead robot | errors are **collected**, not thrown; `PumpkinRegistry` prints them all and enters **SAFE_MODE** | §5.6 |
| 13 | `AxisGoal.named("L4")` typos failed at button-press time, mid-match | names validated at construction with a "did you mean"; runtime path cannot fail silently; typed `Setpoint` handles are the documented default | §8.9 |
| 14 | Router tested the straight line between two configurations | Router tests the **axis-aligned bounding box** (the true reachable set of two unsynchronized profiles) + `synchronizedAxes` | §8.4 |
| 15 | No transition cost model, no reachability analysis, no honesty about the ceiling | `SuperstructureReport` + `characterizeTransitions()` + an explicit "we are less capable than 254's A*" statement | §8.8, §13 |
| 16 | "Every numeric field auto-registered by field name" (reflection) | explicit allowlist, no reflection; geometry/CAN IDs/sim params deliberately NOT tunable | §1.2 |
| 17 | Six string concatenations per mechanism per loop in `periodic()` | log keys precomputed in the constructor | §6.1, §6.2 |
| 18 | `throw new IllegalStateException("...This is a PumpkinLib bug.")` from `periodic()` | degrade, name itself, latch a no-op flag | §3.5 |
| 19 | `ControlLocation` was a required builder call (an expert question at line 5) | defaulted from the leader's `MotorSpec`, printed with provenance in `describe()` | §5.3 |
| 20 | `setPositionGoal(pos, arbFf)` — no velocity, so field-locked turret was unimplementable | `setPositionGoal(pos, rps, arbFf[, constraintOverride])` | §3.3, §6.4 |

---

## 0. Scope & Responsibilities

### 0.1 What CORE owns

| # | Responsibility | Deliverable |
|---|---|---|
| 1 | **Hardware seam.** One narrow interface between mechanism logic and motor controllers, encoders, gyros, and digital/ranging sensors. Phoenix 6, REVLib, WPILib-generic, and sim backends. | `org.pumpkinlib.hardware` |
| 2 | **Units, gearing, geometry.** One place where "rotor rotations → meters/degrees → SI" is declared, used by control, soft limits, sim, telemetry, and tuning. | `org.pumpkinlib.units` |
| 3 | **Config system.** Immutable records + fluent builders + collected validation + `describe()` + config snapshot logging + SAFE_MODE. The "change a gear ratio in exactly one place" requirement. | `org.pumpkinlib.config` |
| 4 | **Mechanism templates.** `PositionMechanism`, `VelocityMechanism`, `SimpleMechanism`. Gravity comp, profiling, soft/hard limits, homing, goal/setpoint/measured reporting, `atGoal` semantics. | `org.pumpkinlib.mechanism` |
| 5 | **Subsystem & command idiom.** How a mechanism participates in WPILib command-based *without* forcing it, plus the command-factory vocabulary. | `org.pumpkinlib.mechanism` (`Subsystem` facade) |
| 6 | **Superstructure.** Enum goal/request state machine, interlocks, arm-vs-elevator collision avoidance, default-output inversion, static analysis, measured transition costs. | `org.pumpkinlib.superstructure` |

### 0.2 What CORE explicitly does NOT own

* Swerve drivetrain, odometry, path following — **Drivetrain domain** (doc 02 also owns `org.pumpkinlib.control`, see §1.5).
* Vision, pose estimation — **Vision domain** (doc 03).
* Logging backends, replay, dashboards, 3D replay — **Telemetry domain** (doc 04).
* Tunable numbers, PID tuning UI, the tuning wizard, `FeedbackDesigner` — **Tuning domain** (doc 05 / 02-tuning).
* Autonomous composition, `NamedCommands` — **Auto domain** (doc 06).
* Alerts/health monitoring/system check — **Diagnostics domain** (doc 07). CORE *emits* alerts through their API; it does not define the alert framework.
* Mechanism 3D visualization — **Visualization domain** (doc 08). CORE *publishes* an articulation angle; it does not own the `Pose3d` math or the AdvantageScope asset contract.

### 0.3 Design stance (non-negotiable, applies to every decision below)

1. **The seam is at goals, never at voltage.** A wrapper that reads a sensor on the RIO, runs a PID on the RIO, and writes a voltage to a Kraken throws away Motion Magic, FOC, 1 kHz on-motor loops, and StatusSignal batching. PumpkinLib's `MotorIO` sends *positions and velocities in output-shaft rotations* and lets the vendor's controller do the loop. Resolved in full in §1.
2. **Where the loop runs is visible in code, config, diffs, and logs.** `ControlLocation` is an explicit, defaulted-with-provenance, logged, alert-checked field. A team never accidentally moves a loop from a Kraken to the roboRIO.
3. **Every abstraction has a typed escape hatch on page 1.** `elevator.io().as(TalonFXMotorIO.class)` returns the real `TalonFX`. Documented in the first example, not buried.
4. **Zero-mystery debugging.** Every failure names the mechanism, the field, the value, the expected range, and the fix. See §3.6 and §5.6.
5. **Degrade, never crash.** A misconfigured robot **boots**, connects, publishes telemetry, raises alerts, and refuses to move — it does not show red "Robot Code" with a `<clinit>` stack trace. See SAFE_MODE, §5.6.
6. **Sim-first.** Declaring mass/MOI in the config is the *only* thing a team does to get simulation. No second code path.
7. **Config is data; behavior is plain Java.** No JSON, no reflection, no annotation processor of our own.
8. **Nothing is silently frozen.** A value PumpkinLib did not actually measure is `NaN`, never `0.0`.

### 0.4 What this replaces in the user's repos

| Replaced | Where it lives today | Lines killed (approx) |
|---|---|---|
| 10 near-identical `XxxIOTalonFX` config + StatusSignal + sim blocks | `0000-XXXX-Robot-Template/src/main/java/frc/robot/subsystems/{arm,elevator,wrist,turret,shooter,intake,indexer,endeffector,climber}/*IOTalonFX.java` | ~900 |
| 5 verbatim copies of `private enum ControlMode {POSITION, DUTY_CYCLE, NEUTRAL}` + `setManual` + `holdCurrentX` + `isAtTarget` | `0000-XXXX-Robot-Template/.../{Arm,Elevator,Wrist,Turret,Climber}.java` | ~250 |
| 41 inlined `/ 360.0` conversions and `inchesToRotations`/`rotationsToInches` private pairs | `0000-XXXX-Robot-Template/src/main/java/frc/robot/Constants.java` + all IOs | n/a (bug class) |
| Hand-rolled soft-limit derivation with `Math.max/Math.min` sign hacks around a negative gear ratio | `8793-2026-Robot/src/main/java/frc/robot/subsystems/ShooterSubsystem.java:48-89` | ~40 |
| `TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0` and the seven `angle / 360.0 / GEAR_RATIO` sites that depend on two negatives cancelling | `8793-2026-Robot/src/main/java/frc/robot/constants/Constants.java:45,54` | n/a (bug class) |
| Encoder seeding that double-applies the gear ratio under `SensorToMechanismRatio` | `8793-2026-Robot/src/main/java/frc/robot/subsystems/GroundIntakeSubsystem.java:117-119` | latent 18× bug |
| `optimizeBusUtilization()` called without a matching `setUpdateFrequency`, freezing three signals the code still reads | `8793-2026-Robot/.../ShooterSubsystem.java:180,185,189` | bug class, see §3.5 |
| `PositionMechanism` skeleton (3-boolean state, clamp, manual-with-hold-on-release, `isAtTarget`, `resetEncoder`, 4 diagnostic getters) written 3× | `9143-2025-A-Updated/.../Elevator.java`, `.../CorAl.java`, `9143-2025-B-Updated/.../AlLow.java` | ~700 |
| Guarded absolute-encoder re-seed with the non-blocking `setPosition(x, 0)` detail | `9143-2025-A-Updated/src/main/java/frc/robot/subsystems/CorAl.java:286-344` | ~60, promoted to library |
| Hand-wired `simulationPeriodic()` with a hardcoded `0.02` in 5 places | `9143-*/.../{Elevator,CorAl,AlLow}.java`, `0000-XXXX/.../*IOTalonFX.java` | ~150 |
| Collision-avoidance branch tree in `planMove` / `escapeCurrentPose` / `travelAndFinish` | `9143-2025-A-Updated/src/main/java/frc/robot/Superstructure.java` (449 lines) | ~250, replaced by declarative zones |
| Superstructure cases that must each remember to zero every actuator | `0000-XXXX-Robot-Template/src/main/java/frc/robot/Superstructure.java:130-246` | bug class, see §8.5 |

---

## 1. Integration Points — what CORE needs from other domains

CORE compiles against these interfaces. If a domain is absent, CORE degrades to a no-op (never a crash).

### 1.1 From Telemetry (doc 04) — **required**

CORE lives in `pumpkinlib-core` / `pumpkinlib-mechanism`, whose declared dependencies are WPILib + `pumpkinlib-telemetry`. **ArchUnit rule 1 bans `org.littletonrobotics` imports outside `pumpkinlib-advantagekit`**, so CORE cannot name `LoggableInputs` or `LogTable`. The contract is therefore backend-neutral (doc 04 §2.4):

```java
package org.pumpkinlib.telemetry;

/** Backend-neutral replay seam. The AdvantageKit analogue of LoggableInputs, owned by us. */
public interface PumpkinInputs {
  void toLog(LogSink sink);
  void fromLog(LogSource source);
}

public interface LogSink {
  void put(String key, double v);
  void put(String key, boolean v);
  void put(String key, long v);
  void put(String key, String v);
  void put(String key, double[] v);
  void put(String key, boolean[] v);
  <T extends StructSerializable> void put(String key, T v, Struct<T> struct);
}

public interface LogSource {
  double    getDouble(String key, double def);
  boolean   getBoolean(String key, boolean def);
  long      getLong(String key, long def);
  String    getString(String key, String def);
  double[]  getDoubleArray(String key, double[] def);
  boolean[] getBooleanArray(String key, boolean[] def);
}

/** The statics CORE writes through (D9). Backed by AdvantageKit / DogLog / Epilogue / no-op. */
public final class PumpkinLog {
  public static void processInputs(String table, PumpkinInputs inputs);
  public static void put(String key, double v);
  public static void put(String key, boolean v);
  public static void put(String key, String v);
  public static void put(String key, double[] v);
  public static <T extends StructSerializable> void put(String key, T v, Struct<T> struct);

  /** DEBUG-tier gate. Checked by the CALLER so nothing — not even a capturing lambda — is
   *  allocated when the tier is off. See the allocation rule in §1.7.9. */
  public static boolean debugEnabled();

  /** Monotonic loop time. AdvantageKit adapter returns Logger.getTimestamp(); NEVER
   *  getFPGATimestamp(). Replay-safe by contract, not by comment. */
  public static double timestampSeconds();
}
```

The AdvantageKit adapter — and **only** the AdvantageKit adapter — knows `LoggableInputs`:

```java
// pumpkinlib-advantagekit ONLY.
final class AkInputs implements LoggableInputs {
  private final PumpkinInputs m_delegate;
  AkInputs(PumpkinInputs delegate) { m_delegate = delegate; }
  @Override public void toLog(LogTable t)   { m_delegate.toLog(new AkLogSink(t)); }
  @Override public void fromLog(LogTable t) { m_delegate.fromLog(new AkLogSource(t)); }
}

final class AkBackend implements LogBackend {
  // Logger.processInputs REQUIRES a stable instance per key across loops, so we cache.
  private final Map<String, AkInputs> m_wrappers = new HashMap<>();
  @Override public void processInputs(String key, PumpkinInputs in) {
    Logger.processInputs(key, m_wrappers.computeIfAbsent(key, k -> new AkInputs(in)));
  }
}
```

CORE requires from Telemetry:
* `PumpkinLog.timestampSeconds()` to be replay-safe. The `0000-XXXX` template pins this in `Superstructure.java:85-87` and `Vision.java:73-75`; it must be a library guarantee, not a comment.
* A **tiered volume** control (`OFF / COMPETITION / FULL / DEBUG`) reusing the already-owned `org.pumpkinlib.telemetry.Tier {CRITICAL, STANDARD, DEBUG}` — CORE does **not** invent a `TelemetryLevel`.
* `PumpkinLog.debugEnabled()` as a plain boolean gate. Doc 04's earlier `debug(String, Supplier<T>, Struct<T>)` shape is **withdrawn for CORE's use**: any supplier that reads instance state (`() -> m_pose`) is a capturing lambda and allocates on every call *even when the tier is off*, which defeats the mechanism's purpose. The gate is checked by the caller.

CORE provides to Telemetry: `MotorInputs implements PumpkinInputs` with hand-written `toLog`/`fromLog` (D24 — no `@AutoLog`, no `@AutoLogOutput`).

### 1.2 From Tuning (doc 05 / 02-tuning) — **required**

```java
package org.pumpkinlib.tuning;

public interface Tunable {
  double get();
  boolean hasChanged();                 // rising edge since last call, FMS-gated
  static Tunable of(String key, double defaultValue) { ... }
}

/** The gain-set handle a Mechanism holds. Doc 02 §5.4 specifies write-through at 10 Hz. */
public final class TunableGains {
  public boolean anyChanged();          // one call re-pushes a whole gain set
  public Gains gains();
  public MotionConstraints constraints();
}
```

CORE requires:
* `Pumpkin.TUNING_MODE` — a single global. When false, `Tunable.get()` returns the compile-time default with **zero** NetworkTables traffic. Team 4738 commented out 30+ `LoggedTunableNumber` declarations by hand rather than ship them to competition (`reefscape2025/.../Vision.java:41-53`, `Elevator.java:47-52`, and 7 commented `LoggedGainConstants` in `Constants.java`). That must never be a choice a team faces.
* Publication under `/Tuning/<MechanismName>/<field>` so AdvantageScope tuning mode and Elastic both work.
* `hasChanged()` hard-gated on `!DriverStation.isFMSAttached()`.
* Values persisted across redeploy (`Preferences`-backed or equivalent).

**What is auto-registered — an explicit allowlist, no reflection.**

Revision 1 said "every numeric field of every mechanism config is auto-registered by field name." That requires reflecting over record components, which contradicts Principle 7 and DESIGN.md §14 row 6, and it exposes a large set of fields where runtime mutation is meaningless or actively harmful. `Mechanism`'s constructor registers exactly this list and nothing else:

```java
// org.pumpkinlib.mechanism.Mechanism, constructor. Hand-written. One line per key.
m_tunables = TuningRegistry.gains(this);              // kP kI kD kS kV kA kG
TuningRegistry.constraint(this, "maxVelocity",     c.control().constraints()::maxVelocity);
TuningRegistry.constraint(this, "maxAcceleration", c.control().constraints()::maxAcceleration);
TuningRegistry.constraint(this, "jerk",            c.control().constraints()::jerk);
TuningRegistry.scalar(this, "tolerance",           c.control().tolerance());
TuningRegistry.scalar(this, "velocityTolerance",   c.control().velocityTolerance());
TuningRegistry.scalar(this, "goalDebounceSeconds", c.control().goalDebounceSeconds());
TuningRegistry.scalar(this, "manualDeadband",      c.control().manualDeadband());
TuningRegistry.scalar(this, "manualScale",         c.control().manualScale());
for (Setpoint s : c.setpoints()) TuningRegistry.setpoint(this, s);
```

> **Gains, profile constraints, tolerances and setpoints are auto-registered as tunable.
> Geometry, CAN IDs, current limits, feedback specs, homing parameters and sim parameters are
> deliberately NOT tunable.** Changing them at runtime cannot re-derive the device configuration
> they produced — `reduction.rotorPerOutput()` becomes `Feedback.SensorToMechanismRatio`, the two
> soft-limit thresholds, the Motion Magic constraints and the `ElevatorSim` gearing **once**, at
> construction — so a student who drags `/Tuning/Elevator/reduction` gets a mechanism whose
> Java-side units and device-side units disagree. That is precisely the class of bug
> `MechanismUnits` exists to eliminate, and `describe()` would start lying.

`TuningRegistry.tunable(namespace, key, default)` raises a `ConfigError` when `namespace` matches a registered mechanism name and `key` is not in the allowlist above, naming the allowlist in the message.

CORE provides to Tuning: `Mechanism.tunables()` returning the `TunableGains` it registered, and `Mechanism.applyTunedValues()` which re-pushes gains + constraints to hardware **via `PhoenixUtil.applyFast` / `RevUtil.applyFast`** (§3.9 — never the blocking verified path).

### 1.3 From Diagnostics (doc 07) — **required**

```java
package org.pumpkinlib.diagnostics;
public interface AlertSink {
  Handle error(String group, String text);
  Handle warning(String group, String text);
  interface Handle { void set(boolean active); void setText(String text); }
}
```

CORE raises, per mechanism, automatically:
`<name>/motor-disconnected`, `<name>/device-reset`, `<name>/follower-disagrees`, `<name>/over-temperature`, `<name>/config-apply-failed`, `<name>/absolute-encoder-disconnected`, `<name>/absolute-encoder-disagrees`, `<name>/homing-timed-out`, `<name>/soft-limit-clamped-setpoint`, `<name>/goal-unreachable`, `<name>/stalled`, `<name>/control-location-downgraded`, `<name>/unknown-setpoint`, `<name>/internal-routing-fault`.

CORE needs from Diagnostics:
* a `SystemCheck` registry so `PositionMechanism` can contribute a canned range-of-motion check;
* a `CanIdRegistry` that CORE scans **once, globally, in `PumpkinRegistry.addAll(...)`** — *never* as a side effect of a record constructor (see §5.6b);
* `PumpkinTracer.budget(String epoch, Time budget)` so a periodic-time regression is visible rather than inferred from loop overruns.

`9143-2025-A-Updated/src/main/java/frc/robot/Constants.java` documents a CAN ID of 64 that *crashed robot code on boot* because Phoenix IDs stop at 62. That must be a collected, named error that still lets the robot boot into SAFE_MODE — not a stack trace.

### 1.4 From Simulation (doc 09) — **required**

CORE builds the WPILib physics plant itself (`ElevatorSim`, `SingleJointedArmSim`, `FlywheelSim`, `DCMotorSim`) from `SimConfig`. It needs from Simulation:
* `Clock.dt()` — one authoritative timestep, so `0.02` is never hardcoded again (it is hardcoded in five places across the user's repos). `Clock.seconds()` is the monotonic companion used by the setpoint heartbeat in §3.5.
* A registration hook so `simulationPeriodic()` fan-out is automatic, not a hand-maintained list.
* An optional maple-sim bridge for game-piece interaction. Optional — maple-sim is beta and has a documented succession risk; it must never be a hard dependency.

### 1.5 From Drivetrain / Control (doc 02)

Drivetrain **consumes** CORE, not the reverse. It uses `Reduction`, `MotorSpec`, `MotorIO`, `Gains`, the validation/`describe()` machinery, and `CurrentLimits`. It does **not** use `PositionMechanism` — swerve steering has its own continuous-wrap + coupling semantics.

Doc 02 also owns `org.pumpkinlib.control`, and CORE depends on exactly one thing from it — the archetype-dispatching feedforward that makes `RIO_FULL` correct:

```java
package org.pumpkinlib.control;

public final class Controllers {
  /** Sealed over the three WPILib feedforward archetypes. All inputs and outputs are SI:
   *  positions in rad (rotary) or m (linear), velocities in rad/s or m/s, output in volts. */
  public sealed interface Feedforward {
    /** @param positionSi   current position (radians FROM HORIZONTAL for ARM; ignored otherwise)
     *  @param velocitySi   current profile velocity
     *  @param nextVelocitySi profile velocity one Clock.dt() later */
    double calculate(double positionSi, double velocitySi, double nextVelocitySi);
    Gains gains();
  }
  public static Feedforward feedforward(GravityMode archetype, Gains siGains, double dtSeconds);
}
```

Its three implementations wrap the **verified** WPILib 2026.2.2 signatures — this is the API-truth fix for revision 1's `m_feedforward.calculate(position, velocity)`, which does not compile because WPILib has **no common supertype** for the three feedforward classes and only `ArmFeedforward` has a two-argument `(position, velocity)`-shaped overload at all:

| `GravityMode` | Implementation calls | Source |
|---|---|---|
| `COSINE` | `ArmFeedforward.calculateWithVelocities(double currentAngle, double currentVelocity, double nextVelocity)` — *"This angle should be measured from the horizontal (i.e. if the provided angle is 0, the arm should be parallel to the floor)"* | [ArmFeedforward, WPILib 2026.2.1](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/math/controller/ArmFeedforward.html) |
| `CONSTANT` | `ElevatorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)` | [ElevatorFeedforward, WPILib 2026.2.1](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/math/controller/ElevatorFeedforward.html) |
| `NONE` | `SimpleMotorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)` | [SimpleMotorFeedforward, WPILib 2026.2.2](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/math/controller/SimpleMotorFeedforward.html) |

The single-argument `calculate(double velocity)` forms still exist but are the *steady-state* forms; the `WithVelocities` forms are the discrete-control forms and are what a profiled loop must use. The old `calculate(position, velocity, acceleration, dt)` overloads are on the [WPILib deprecated list](https://github.wpilib.org/allwpilib/docs/release/java/deprecated-list.html).

**Cross-domain need:** Drivetrain must reconcile `deploy/pathplanner/settings.json` against the Java `Reduction`/wheel radius. Three of the user's repos have this drift already (`8793` has three disagreeing sources; `9143-B` has `maxDriveSpeed 5.364` in JSON vs `5.96` in Java). CORE supplies the comparison primitive (`Reduction.approxEquals`, `Validation.crossCheck`); Drivetrain supplies the file reader and the alert.

### 1.6 From Auto (doc 06)

CORE guarantees to Auto:
* Every mechanism exposes `Trigger atGoal()`, `Trigger atGoal(double toleranceUnits)`, `Trigger stalled()`, `Trigger homed()`.
* Every mechanism exposes `Command goTo(Setpoint)`, `Command hold()`, `Command home()`, `Command neutral()` — safe to register as `NamedCommands` and safe to re-instantiate every enable.
* `Superstructure.request(State)` returns a fresh `Command` each call (WPILib forbids reusing a composed command; `0000-XXXX/RobotContainer.java` already works around this).
* `Superstructure.plannedTransitionSeconds(from, to)` — backed by **measured** costs when `transition_costs.json` exists, and by a conservative profile-time bound otherwise, with the provenance reported (§8.8). `AutoStep.budget()` defaults from it.

Auto owes CORE: a `NamedCommandRegistry` that **rejects duplicate names**. Team 4738 registered `"Stow"` twice and bound `"PrepL4"` to `superstructure.L2` (`reefscape2025/RobotContainer.java:476,497,498`).

### 1.7 Hard constraints inherited from the dossiers

1. **No `@AutoLog`, no `@AutoLogOutput`.** A vendordep cannot add `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$v"` to a consumer's `build.gradle`. CORE hand-writes `toLog`/`fromLog` on `MotorInputs` against `PumpkinInputs`, not `LoggableInputs`. This also means CORE works identically for DogLog and Epilogue users, and satisfies ArchUnit rule 1.
2. **Java 17 syntax ceiling for shared code.** Records ✔, sealed interfaces ✔, `instanceof` patterns ✔, arrow switch ✔. Pattern-matching `switch` ✘ (Java 21), `Math.clamp` ✘ (Java 21).
3. **Immutable `Measure` at config boundaries only; raw doubles in the hot loop.** `MutableMeasure` is *removed* in WPILib 2027 — never expose it.
4. **No removed-in-2027 HAL.** CAN devices and DIO only. No Relay, AnalogOutput, AnalogGyro, SPI (and no SPI IMUs), DMA, Counter, Ultrasonic, AnalogTrigger, interrupts, Servo, digital glitch filter.
5. **No Shuffleboard / SmartDashboard / NT3.** NetworkTables 4 struct publishing only.
6. **Zero hard vendordep dependencies in the core artifact.** `pumpkinlib-core` depends on WPILib only. `pumpkinlib-phoenix`, `pumpkinlib-rev` and `pumpkinlib-advantagekit` are separate artifacts. A team can install PumpkinLib on kickoff day before CTRE and REV have published.
7. **Config apply must be retried and read-back-verified at construction** — and must **never** run on the periodic path. See the two-path split in §3.9.
8. **Never block the main loop on CAN.** `TalonFX.setPosition(double)` blocks up to 100 ms; the library only ever calls `setPosition(value, 0.0)`. `TalonFXConfigurator.apply(config)` blocks with a default 0.050 s timeout; the periodic path only ever calls `apply(config, 0.0)`.
9. **No per-loop allocation in mechanism `periodic()`.** All vendor control-request objects are pre-allocated at construction; **all log keys are precomputed strings**; no capturing lambdas are constructed on the periodic path. Verified in CI by an allocation counter run against the full §9 example robot — a **G2 gate condition**, not a unit test on a synthetic mechanism.
10. **Nothing is silently frozen.** A signal PumpkinLib did not subscribe reports `NaN`.

---

## 2. Package layout

```
org.pumpkinlib
├── Pumpkin.java                     // global switches: TUNING_MODE, registry(), alerts(), safeMode()
├── Clock.java                       // dt(), seconds()  -- the ONLY source of 0.02
├── units/
│   ├── Reduction.java               // gearbox, built from tooth counts or stages
│   ├── Axis.java                    // sealed: LinearAxis | RotaryAxis  (output rot <-> user <-> SI)
│   ├── LinearAxis.java
│   ├── RotaryAxis.java
│   ├── SiDomain.java                // LINEAR (m) | ANGULAR (rad)              [D3]
│   └── MechanismUnits.java          // the ONE conversion object handed to IO, sim, limits, telemetry
├── config/
│   ├── MotorSpec.java               // sealed: TalonFXSpec | TalonFXSSpec | SparkSpec | GenericSpec | SimSpec
│   ├── OutputMode.java              // VOLTAGE (v0.1) | TORQUE_CURRENT (v0.2)  -- NOT the same as FOC
│   ├── MotorGroup.java              // leader + followers + inversion
│   ├── MotorModel.java              // KRAKEN_X60, KRAKEN_X44, FALCON_500, NEO, NEO_VORTEX, ...
│   ├── MechanismKind.java           // POSITION | VELOCITY | SIMPLE            [was undefined in rev 1]
│   ├── FeedbackSpec.java            // sealed: RotorOnly | FusedCancoder | RemoteCancoder | SparkAbsolute | DioAbsolute
│   ├── Gains.java                   // record, named fields only, VOLTS-PER-SI  [D1]
│   ├── MotionConstraints.java       // USER units per second^n
│   ├── CurrentLimits.java
│   ├── PositionLimits.java
│   ├── ControlConfig.java           // ControlLocation + Gains + constraints + tolerances + gravity
│   ├── SimConfig.java
│   ├── PositionConfig.java          // + PositionConfig.Builder
│   ├── VelocityConfig.java          // + VelocityConfig.Builder
│   ├── SimpleConfig.java            // + SimpleConfig.Builder
│   ├── Setpoint.java                // named, unit-typed preset + RESOLUTION STATE
│   ├── ConfigError.java             // a VALUE, not a Throwable subclass in the happy path
│   └── Validation.java              // localChecks(), crossChecks(), describe(), snapshot struct
├── hardware/
│   ├── MotorIO.java                 // THE seam
│   ├── MotorInputs.java             // PumpkinInputs, hand-written toLog/fromLog
│   ├── MotorCapabilities.java
│   ├── ControlLocation.java
│   ├── AbsoluteEncoderIO.java
│   ├── GyroIO.java
│   ├── DigitalSensorIO.java         // limit switch / beam break / CANrange, unified as level+distance
│   ├── MotorIOFactory.java          // sealed-spec -> IO, one switch
│   ├── phoenix/   TalonFXMotorIO, TalonFXSMotorIO, AbstractPhoenixMotorIO, SignalSet,
│   │              CancoderIO, Pigeon2GyroIO, CanRangeIO, CandiIO, PhoenixUtil
│   ├── rev/       SparkMotorIO, SparkAbsoluteEncoderIO, RevUtil
│   ├── generic/   GenericMotorIO (RIO-side loop over any WPILib MotorController), DioSensorIO,
│   │              DioAbsoluteEncoderIO
│   └── sim/       SimMotorIO, SimGyroIO, SimDigitalSensorIO
├── mechanism/
│   ├── Mechanism.java               // abstract base, implements Subsystem (not SubsystemBase)
│   ├── PositionMechanism.java
│   ├── VelocityMechanism.java
│   ├── SimpleMechanism.java
│   ├── MechanismMode.java
│   ├── HomingStrategy.java          // sealed: AbsoluteSeed | CurrentSpike | LimitSwitch | AssumeAtBoot | Composite
│   ├── ManualControl.java
│   └── PumpkinRegistry.java         // addAll(), onDisable(), SAFE_MODE entry
└── superstructure/
    ├── Superstructure.java
    ├── SuperState.java              // interface a team's enum implements
    ├── AxisGoal.java
    ├── Interlock.java
    ├── SafetyModel.java             // forbidden zones in 2-axis config space
    ├── TransitionPlanner.java
    ├── SuperstructureReport.java    // static analysis, §8.8
    └── TransitionCosts.java         // measured costs, §8.8
```

---


## 3. Brief item 1 — Hardware abstraction layer

### 3.1 The tension, stated precisely

A naive `PumpkinMotor` looks like this and is **wrong**:

```java
// WHAT WE DO NOT BUILD
interface PumpkinMotor {
  double getPosition();
  void setVoltage(double volts);
}
// ...then a RIO-side PIDController reads getPosition() at 50 Hz and writes setVoltage().
```

Everything a Kraken X60 is worth is destroyed by that interface:

| Capability lost | Why it matters |
|---|---|
| Motion Magic / Motion Magic Expo | The profile runs on-motor at 1 kHz. Moving it to a 50 Hz RIO loop makes moves visibly worse and adds a 20 ms transport delay to every correction. |
| FOC | ~15% more torque, and it is a per-control-request flag (`.withEnableFOC(true)`), **not** a config and **not** a change of output units. A voltage-only seam cannot express it. |
| `SensorToMechanismRatio` | The device already does rotor→mechanism conversion. Doing it in Java means every read *and* every write carries a hand-written `/ ratio` — 41 inline `/ 360.0` in `0000-XXXX-Robot-Template`, plus the double-applied-ratio bug in `8793-2026-Robot/.../GroundIntakeSubsystem.java:117-119`. |
| `BaseStatusSignal.refreshAll(...)` batching | One CAN transaction for N signals. Individual `.getValue()` calls without a batched refresh are the difference between healthy and saturated bus utilization. |
| Setpoint latching | Phoenix and REVLib both **latch** the setpoint. `9143-2025-B-Updated/src/test/.../AlLowSimTest.java` exists specifically as a regression net for this ("the original hold-position bug"). A RIO loop that stops writing drops the mechanism. |
| Fused CANcoder | On-motor sensor fusion (`FusedCANcoder`) gives absolute truth at rotor bandwidth. Unreachable through a voltage seam. |
| Dynamic Motion Magic | `DynamicMotionMagicVoltage` changes cruise/accel/jerk **per request**, with no config apply. The only way to switch constraint profiles at runtime without a blocking CAN write. (Requires Phoenix Pro + CANivore — see §6.2.) |
| REVLib 2026 on-controller gravity FF | `FeedForwardConfig` now carries `kG` (constant) and `kCos` (cosine-scaled, multiplied by the cosine of absolute mechanism position). *Verified in REVLib 2026 docs.* A RIO seam re-implements this worse. |

The community rejected the alternative failure too. Oblarg, on a monolithic vendor-normalizing motor controller: *"it attempts to paper over fundamental differences in capability with identical APIs, where a small change in config metadata can/will precipitate a massive change in mechanism behavior."* (`web-smallteam.json`, painPoint 3.)

### 3.2 The resolution: three rules

**Rule 1 — the seam is a *goal*, in output-shaft rotations, not a voltage.**

`MotorIO` takes `setPositionGoal(double outputRotations, double outputRotationsPerSecond, double arbFeedforwardVolts)`. Whether that becomes `MotionMagicVoltage`, `DynamicMotionMagicVoltage`, `SparkClosedLoopController.setSetpoint(..., kMAXMotionPositionControl, ...)`, or a RIO `TrapezoidProfile` + `PIDController` is the backend's business. Nothing above the seam knows or cares.

**Rule 2 — the *location* of the loop is an explicit, defaulted-with-provenance, logged config field.**

```java
package org.pumpkinlib.hardware;

/** WHERE the closed loop executes. This is a first-class, logged, alert-checked decision. */
public enum ControlLocation {
  /** Profile AND feedback on the motor controller. Phoenix Motion Magic / REV MAXMotion.
   *  Setpoint LATCHES: the library re-sends on change and on a 10 Hz heartbeat. Lowest
   *  latency, best behavior. This is the default for every smart controller. */
  ON_MOTOR_PROFILED,

  /** Feedback on the motor controller, no profile. Phoenix PositionVoltage / REV kPosition.
   *  Setpoint LATCHES. Use when you feed your own profile or want a pure hold. */
  ON_MOTOR_DIRECT,

  /** TrapezoidProfile stepped on the robot controller at loop rate; each step is sent to the
   *  motor's ON-BOARD position loop. The 254 / 4738 pattern. Re-sent every loop.
   *  Use when the profile must react to game state (e.g. slow down while carrying a piece)
   *  AND the backend has no dynamic-profile request. */
  RIO_PROFILE_MOTOR_LOOP,

  /** TrapezoidProfile + PIDController + feedforward on the robot controller, ALL IN SI;
   *  voltage to the motor. Required for GenericSpec (PWM / non-smart controllers).
   *  Re-sent every loop. On a TalonFX this is a DOWNGRADE and PumpkinLib says so. */
  RIO_FULL
}
```

A team choosing `RIO_FULL` on a Kraken sees, in the driver station and in the log:

```
[PumpkinLib][WARN] Elevator: ControlLocation.RIO_FULL on a TalonFX (CAN 20) disables Motion Magic
  and the 1 kHz on-motor loop. The mechanism will run a 50 Hz roboRIO loop instead.
  If this is intentional, ignore. If not, use ControlLocation.ON_MOTOR_PROFILED.
```

If a team asks for `ON_MOTOR_PROFILED` on a backend that cannot do it (`GenericSpec`), CORE **downgrades and says so** rather than silently misbehaving:

```
[PumpkinLib][ERROR] Wrist: ControlLocation.ON_MOTOR_PROFILED requested, but MotorSpec.Generic
  (Spark PWM on PWM 3) has no on-board closed loop. Downgraded to RIO_FULL.
  Gains you tuned for the motor controller will NOT transfer; retune with the RIO loop.
```

**Rule 3 — the raw vendor object is on page 1, typed.**

```java
public interface MotorIO {
  /** Typed escape hatch. Returns empty if this IO is not that backend. */
  <T extends MotorIO> Optional<T> as(Class<T> type);
}

// In TalonFXMotorIO:
public TalonFX talonFX();                        // the real device
public TalonFXConfiguration configuration();     // the live config object PumpkinLib built
public void applyRaw(Consumer<TalonFXConfiguration> mutator);  // merge-and-reapply, retried+verified
```

Usage, and it appears in the README's *first* elevator example, not an appendix:

```java
// Anything PumpkinLib does not model, you do yourself, on the real device.
elevator.io().as(TalonFXMotorIO.class).ifPresent(io -> {
    io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false);
    io.talonFX().setControl(new MusicTone(440));
});
```

`applyRaw` uses the **verified** path (§3.9) and is documented as a construction-time / on-demand call, not a periodic one.

### 3.3 `MotorIO` — the seam, in full

```java
package org.pumpkinlib.hardware;

import org.pumpkinlib.config.Gains;
import org.pumpkinlib.config.MotionConstraints;
import java.util.Optional;

/**
 * The single boundary between mechanism logic and a motor controller.
 *
 * UNIT CONTRACT (memorize this; it is the whole point):
 *   every position is in OUTPUT-SHAFT ROTATIONS,
 *   every velocity is in OUTPUT-SHAFT ROTATIONS PER SECOND,
 *   every acceleration is in OUTPUT-SHAFT ROTATIONS PER SECOND SQUARED,
 *   every feedforward is in VOLTS.
 * "Output shaft" = the last shaft before the mechanism's own geometry (the drum, the joint,
 * the turret ring). The gearbox lives INSIDE the backend (Phoenix SensorToMechanismRatio,
 * REV positionConversionFactor). The geometry (drum radius, stages, degrees-per-rotation)
 * lives ABOVE the seam, in MechanismUnits. Neither is ever applied twice.
 */
public interface MotorIO {

  // ---- read ------------------------------------------------------------
  /** Refresh all SUBSCRIBED signals in ONE batched CAN transaction and fill inputs.
   *  Fields backed by an unsubscribed signal are left at Double.NaN. Never a frozen 0. */
  void updateInputs(MotorInputs inputs);

  // ---- write: goals ----------------------------------------------------
  /**
   * Closed-loop position goal.
   *
   * @param outputRotations          where to end up, output-shaft rotations
   * @param outputRotationsPerSecond the velocity the controller should ALSO be tracking at
   *                                 that instant. Zero in the common case. NON-zero for:
   *                                   - RIO_PROFILE_MOTOR_LOOP (the profile step's velocity),
   *                                   - a field-locked turret's chassis-omega counter-rotation
   *                                     (§6.4) -- the whole reason this parameter exists;
   *                                     TurretIONeo in 0000-XXXX-Robot-Template:62-64 silently
   *                                     drops it, and rev 1 of this document made that defect
   *                                     unfixable by omitting the parameter from the seam.
   * @param arbFeedforwardVolts      ADDED on top of whatever gravity term the controller
   *                                 computes from Gains.kG. Pass 0 for on-motor gravity.
   */
  void setPositionGoal(double outputRotations, double outputRotationsPerSecond,
                       double arbFeedforwardVolts);

  /**
   * Same, but with a one-request constraint override -- the runtime constraint-profile path
   * (§6.2 addConstraintProfile). Backends that cannot do this per-request return
   * capabilities().dynamicProfile() == false and PumpkinLib routes the mechanism to
   * RIO_PROFILE_MOTOR_LOOP instead, LOUDLY, at construction.
   */
  void setPositionGoal(double outputRotations, double outputRotationsPerSecond,
                       double arbFeedforwardVolts, MotionConstraints override);

  /** Closed-loop velocity goal. outputRps2 is the profile's acceleration, 0 in the common case. */
  void setVelocityGoal(double outputRps, double outputRps2, double arbFeedforwardVolts);

  /** Open loop, absolute volts (battery-compensated by the backend where available). */
  void setVoltage(double volts);

  /** Open loop, -1..1 duty cycle. */
  void setDutyCycle(double fraction);

  /** Stop applying output. Honors the configured NeutralMode (brake/coast). */
  void setNeutral();

  // ---- write: configuration --------------------------------------------
  /** Push gains to the controller. NON-BLOCKING (zero-timeout apply, no read-back).
   *  Safe to call every loop -- it no-ops if the values are unchanged. This is what makes
   *  live tuning free. See §3.9 for why this is NOT applyVerified. */
  void applyGains(Gains siGains);

  /** Push profile constraints, in USER units per second^n. Converted at the seam.
   *  Same non-blocking contract as applyGains. */
  void applyConstraints(MotionConstraints constraints);

  void setNeutralMode(NeutralMode mode);

  /** Non-blocking. Implementations MUST use a zero timeout -- TalonFX.setPosition(double)
   *  blocks the main loop up to 100 ms waiting for the device ack. */
  void seedPosition(double outputRotations);

  /** Full, verified, retried re-apply of the ENTIRE device configuration. Blocking.
   *  Called at construction, from disabledInit(), from SelfTest, and automatically when
   *  hasResetOccurred() fires. NEVER from an enabled periodic(). */
  void reapplyFullConfigBlocking();

  // ---- introspection ---------------------------------------------------
  MotorCapabilities capabilities();
  String name();
  /** Human-readable derived model, printed at boot and on demand. See Validation.describe(). */
  String describe();

  <T extends MotorIO> Optional<T> as(Class<T> type);

  enum NeutralMode { BRAKE, COAST }
}
```

```java
/** What a backend can actually do. Used to downgrade ControlLocation loudly, and to decide
 *  which signals to subscribe to (so we never pay CAN cost for data we do not read). */
public record MotorCapabilities(
    boolean onBoardPositionLoop,
    boolean onBoardVelocityLoop,
    boolean onBoardProfile,          // Motion Magic / MAXMotion
    boolean dynamicProfile,          // per-REQUEST cruise/accel/jerk (Phoenix Pro + CANivore)
    boolean onBoardGravityFeedforward,
    boolean onBoardCosineGravity,    // Phoenix Arm_Cosine; REVLib 2026 FeedForwardConfig.kCos
    boolean arbitraryFeedforward,    // can an arbitrary volt term ride along with the goal?
    boolean positionGoalVelocity,    // can a velocity ride along with a position goal?
    boolean torqueCurrentControl,    // Phoenix FOC torque-current  (OutputMode.TORQUE_CURRENT)
    boolean fusedAbsoluteEncoder,
    boolean readsTorqueCurrent,
    boolean readsTemperature,
    boolean reportsDeviceReset,
    boolean reportsConnectionHealth) { }
```

### 3.4 `MotorInputs` — hand-written `PumpkinInputs`

```java
package org.pumpkinlib.hardware;

import org.pumpkinlib.telemetry.PumpkinInputs;
import org.pumpkinlib.telemetry.LogSink;
import org.pumpkinlib.telemetry.LogSource;

/**
 * Everything read FROM a motor, once per loop, in OUTPUT-SHAFT units.
 *
 * Implements PumpkinInputs, NOT AdvantageKit's LoggableInputs: this class lives in
 * pumpkinlib-mechanism, whose declared dependencies are {core, telemetry}, and ArchUnit rule 1
 * bans org.littletonrobotics imports outside pumpkinlib-advantagekit. The AdvantageKit adapter
 * wraps this in AkInputs (§1.1). Hand-writing toLog/fromLog also keeps DogLog / Epilogue /
 * no-op users on the identical code path -- a vendordep cannot install akit's annotation
 * processor into a consumer build.
 *
 * NaN DISCIPLINE: every double field backed by a StatusSignal this IO did not subscribe is
 * stamped Double.NaN ONCE at construction and never written again. A frozen zero is a lie;
 * NaN is honest, and it is visibly wrong on an AdvantageScope plot.
 */
public class MotorInputs implements PumpkinInputs {
  public boolean connected            = false;
  public double  positionRot          = Double.NaN;  // output-shaft rotations
  public double  velocityRps          = Double.NaN;  // output-shaft rot/s
  public double  appliedVolts         = Double.NaN;
  public double  supplyCurrentAmps    = Double.NaN;
  public double  statorCurrentAmps    = Double.NaN;
  public double  torqueCurrentAmps    = Double.NaN;
  public double  temperatureCelsius   = Double.NaN;

  /** Hardware limit switch, if wired to the controller. `*Valid` is false when the signal was
   *  not subscribed -- booleans cannot carry NaN, so validity is carried alongside. */
  public boolean forwardLimitTripped  = false;
  public boolean forwardLimitValid    = false;
  public boolean reverseLimitTripped  = false;
  public boolean reverseLimitValid    = false;

  /** What the controller believes its closed-loop target is. Diagnostic gold: if this
   *  disagrees with the mechanism's goal, the setpoint did not land. */
  public double  closedLoopReferenceRot = Double.NaN;

  /** Monotonically increasing count of device resets observed via ParentDevice
   *  .hasResetOccurred(). A nonzero value mid-match explains an elevator that fell. */
  public long    deviceResetCount     = 0L;

  // Followers, flattened. Length 0 when there are none.
  public double[]  followerPositionRot   = new double[0];
  public double[]  followerStatorAmps    = new double[0];
  public double[]  followerTemperatureC  = new double[0];
  public boolean[] followerConnected     = new boolean[0];

  @Override public void toLog(LogSink t) {
    t.put("Connected", connected);
    t.put("PositionRot", positionRot);
    t.put("VelocityRps", velocityRps);
    t.put("AppliedVolts", appliedVolts);
    t.put("SupplyCurrentAmps", supplyCurrentAmps);
    t.put("StatorCurrentAmps", statorCurrentAmps);
    t.put("TorqueCurrentAmps", torqueCurrentAmps);
    t.put("TemperatureCelsius", temperatureCelsius);
    t.put("ForwardLimitTripped", forwardLimitTripped);
    t.put("ForwardLimitValid", forwardLimitValid);
    t.put("ReverseLimitTripped", reverseLimitTripped);
    t.put("ReverseLimitValid", reverseLimitValid);
    t.put("ClosedLoopReferenceRot", closedLoopReferenceRot);
    t.put("DeviceResetCount", deviceResetCount);
    t.put("FollowerPositionRot", followerPositionRot);
    t.put("FollowerStatorAmps", followerStatorAmps);
    t.put("FollowerTemperatureC", followerTemperatureC);
    t.put("FollowerConnected", followerConnected);
  }

  @Override public void fromLog(LogSource t) {
    connected             = t.getBoolean("Connected", connected);
    positionRot           = t.getDouble("PositionRot", positionRot);
    velocityRps           = t.getDouble("VelocityRps", velocityRps);
    appliedVolts          = t.getDouble("AppliedVolts", appliedVolts);
    supplyCurrentAmps     = t.getDouble("SupplyCurrentAmps", supplyCurrentAmps);
    statorCurrentAmps     = t.getDouble("StatorCurrentAmps", statorCurrentAmps);
    torqueCurrentAmps     = t.getDouble("TorqueCurrentAmps", torqueCurrentAmps);
    temperatureCelsius    = t.getDouble("TemperatureCelsius", temperatureCelsius);
    forwardLimitTripped   = t.getBoolean("ForwardLimitTripped", forwardLimitTripped);
    forwardLimitValid     = t.getBoolean("ForwardLimitValid", forwardLimitValid);
    reverseLimitTripped   = t.getBoolean("ReverseLimitTripped", reverseLimitTripped);
    reverseLimitValid     = t.getBoolean("ReverseLimitValid", reverseLimitValid);
    closedLoopReferenceRot= t.getDouble("ClosedLoopReferenceRot", closedLoopReferenceRot);
    deviceResetCount      = t.getLong("DeviceResetCount", deviceResetCount);
    followerPositionRot   = t.getDoubleArray("FollowerPositionRot", followerPositionRot);
    followerStatorAmps    = t.getDoubleArray("FollowerStatorAmps", followerStatorAmps);
    followerTemperatureC  = t.getDoubleArray("FollowerTemperatureC", followerTemperatureC);
    followerConnected     = t.getBooleanArray("FollowerConnected", followerConnected);
  }
}
```

### 3.5 Backend: Phoenix 6 — `TalonFXMotorIO`

This is where 900 lines of the user's template collapse to one class.

#### 3.5.1 `SignalSet` — subscription and read are ONE declaration

Revision 1 shipped, inside the library, byte-for-byte the bug it was written to prevent:
`configureSignals` subscribed `m_supplyCurrent` and `m_closedLoopReference` only `if (level.atLeast(FULL))`, never subscribed the two limit signals at all, then called `optimizeBusUtilization()` — which sets every unrequested signal to 0 Hz. `updateInputs` then read all four unconditionally. Below `FULL` telemetry those four fields would be frozen at their power-on values forever, with no error. That is exactly `8793-2026-Robot/.../ShooterSubsystem.java:180,185,189`, and exactly what doc 02 §7.2 abort condition 11 exists to detect. It also made `SensorSpec.motorLimit(...)`'s advertised *"zero extra CAN traffic — the motor already reports it"* permanently false.

The fix is structural, not a second `if`:

```java
package org.pumpkinlib.hardware.phoenix;

/**
 * You cannot read a signal you did not subscribe, because the ONLY way to read is to iterate
 * the set you declared. Subscribe and read are the same statement.
 */
final class SignalSet {

  enum Channel { POSITION, VELOCITY, APPLIED_VOLTS, STATOR, SUPPLY, TORQUE_CURRENT,
                 TEMPERATURE, CLOSED_LOOP_REFERENCE, FORWARD_LIMIT, REVERSE_LIMIT }

  /** Writes exactly one MotorInputs field from exactly one signal. */
  interface Sink { void accept(MotorInputs in, StatusSignal<?> sig); }

  private final BaseStatusSignal[] m_signals;   // exactly what refreshAll() gets
  private final Sink[]             m_sinks;     // parallel array
  private final EnumSet<Channel>   m_present;

  static Builder builder(ParentDevice device, String owner) { ... }

  interface Builder {
    /** Subscribe AND declare the read, together. hz is per-signal; build() groups by rate. */
    Builder add(Channel ch, StatusSignal<?> sig, double hz, Sink sink);
    /** Stamps every ABSENT channel's MotorInputs field to NaN / valid=false, ONCE.
     *  Then setUpdateFrequency per rate group, then optimizeBusUtilization() -- in that order.
     *  Optimizing first would freeze the signals we just asked for. */
    SignalSet build(MotorInputs stampInto);
  }

  /** ONE batched CAN transaction, then fan out. Allocation-free. */
  StatusCode refreshInto(MotorInputs in) {
    StatusCode ok = BaseStatusSignal.refreshAll(m_signals);
    for (int i = 0; i < m_signals.length; i++) m_sinks[i].accept(in, (StatusSignal<?>) m_signals[i]);
    return ok;
  }

  boolean has(Channel ch) { return m_present.contains(ch); }
  EnumSet<Channel> channels() { return m_present; }
}
```

```java
private SignalSet configureSignals(MechanismKind kind, Tier tier, PositionConfig c) {
  SignalSet.Builder b = SignalSet.builder(m_leader, name());

  // 100 Hz, not 50: a 50 Hz signal sampled by a 50 Hz loop aliases and can add a full 20 ms
  // of latency to the measurement the whole control loop depends on. Position and velocity
  // are the only signals that get the higher rate; everything else stays cheap.
  if (kind != MechanismKind.SIMPLE) {   // rollers have no position loop; pay nothing for it
    b.add(POSITION, m_position, 100.0, (in, s) -> in.positionRot = s.getValueAsDouble());
    b.add(VELOCITY, m_velocity, 100.0, (in, s) -> in.velocityRps = s.getValueAsDouble());
  }
  b.add(APPLIED_VOLTS, m_appliedVolts, 50.0, (in, s) -> in.appliedVolts = s.getValueAsDouble());
  // Stator current is NOT optional: current-spike homing and stall detection are built on it.
  b.add(STATOR, m_statorCurrent, 50.0, (in, s) -> in.statorCurrentAmps = s.getValueAsDouble());
  // Supply current is NOT optional either: it feeds the brownout and power monitors (doc 07).
  b.add(SUPPLY, m_supplyCurrent, 20.0, (in, s) -> in.supplyCurrentAmps = s.getValueAsDouble());
  b.add(TEMPERATURE, m_temperature, 4.0, (in, s) -> in.temperatureCelsius = s.getValueAsDouble());

  // Limit signals: subscribed WHENEVER a SensorSpec.motorLimit is configured, so the
  // "zero extra CAN traffic" claim is true and forward/reverseLimitTripped are real.
  if (c.limits().usesMotorLimit(HardStop.FORWARD)) {
    b.add(FORWARD_LIMIT, m_forwardLimit, 50.0, (in, s) -> {
        in.forwardLimitTripped = ((StatusSignal<ForwardLimitValue>) s).getValue()
                                 == ForwardLimitValue.ClosedToGround;
        in.forwardLimitValid = true; });
  }
  if (c.limits().usesMotorLimit(HardStop.REVERSE)) {
    b.add(REVERSE_LIMIT, m_reverseLimit, 50.0, (in, s) -> {
        in.reverseLimitTripped = ((StatusSignal<ReverseLimitValue>) s).getValue()
                                 == ReverseLimitValue.ClosedToGround;
        in.reverseLimitValid = true; });
  }

  // Genuinely diagnostic-only signals are the ONLY tier-gated ones. We reuse the telemetry
  // Tier we already own (D-telemetry); there is no separate "TelemetryLevel" type.
  if (tier.atLeast(Tier.DEBUG)) {
    b.add(CLOSED_LOOP_REFERENCE, m_closedLoopReference, 50.0,
          (in, s) -> in.closedLoopReferenceRot = s.getValueAsDouble());
    if (m_outputMode == OutputMode.TORQUE_CURRENT || m_foc) {
      b.add(TORQUE_CURRENT, m_torqueCurrent, 50.0,
            (in, s) -> in.torqueCurrentAmps = s.getValueAsDouble());
    }
  }

  // build() stamps absent channels to NaN, sets frequencies, THEN optimizes.
  return b.build(m_inputsPrototype);
}
```

**CAN budget.** Raising position+velocity from 50 to 100 Hz costs 2 extra frames per motor per 20 ms. On the eight motors of the §9 robot that is 800 frames/s of 8-byte payload ≈ 0.6 % of a 1 Mbps `rio` bus and immaterial on CAN FD. `MotorSpec.talonFX(id, bus).signalRateHz(50.0)` exists for a team that measures a problem, and `describe()` prints the chosen rate and the resulting frame budget.

#### 3.5.2 Fields

```java
package org.pumpkinlib.hardware.phoenix;

public final class TalonFXMotorIO implements MotorIO {
  private final TalonFX   m_leader;
  private final TalonFX[] m_followers;
  private final TalonFXConfiguration m_config = new TalonFXConfiguration();
  private final ControlLocation m_location;
  private final MechanismUnits  m_units;
  private final String          m_name;

  /** FOC and OUTPUT MODE are ORTHOGONAL. See "FOC vs output mode" below. */
  private final boolean    m_foc;          // -> ControlRequest.withEnableFOC(...) only
  private final OutputMode m_outputMode;   // VOLTAGE (v0.1) | TORQUE_CURRENT (v0.2)
  private final boolean    m_useExpo;
  private final boolean    m_dynamicCapable;   // Pro-licensed AND on a CANivore

  // Pre-allocated control requests. NEVER allocated in periodic.
  private final MotionMagicVoltage         m_mmVoltage  = new MotionMagicVoltage(0).withSlot(0);
  private final MotionMagicExpoVoltage     m_mmExpo     = new MotionMagicExpoVoltage(0).withSlot(0);
  private final DynamicMotionMagicVoltage  m_mmDynamic  =
      new DynamicMotionMagicVoltage(0, 0, 0, 0).withSlot(0);
  private final PositionVoltage            m_posVoltage = new PositionVoltage(0).withSlot(0);
  private final MotionMagicVelocityVoltage m_mmVel      = new MotionMagicVelocityVoltage(0).withSlot(0);
  private final VelocityVoltage            m_velVoltage = new VelocityVoltage(0).withSlot(0);
  private final VoltageOut                 m_voltageOut = new VoltageOut(0);
  private final DutyCycleOut               m_dutyOut    = new DutyCycleOut(0);
  private final NeutralOut                 m_neutral    = new NeutralOut();

  // Cached signals, refreshed as ONE batch through m_signals.
  private final StatusSignal<Angle>              m_position;
  private final StatusSignal<AngularVelocity>    m_velocity;
  private final StatusSignal<Voltage>            m_appliedVolts;
  private final StatusSignal<Current>            m_supplyCurrent;
  private final StatusSignal<Current>            m_statorCurrent;
  private final StatusSignal<Current>            m_torqueCurrent;
  private final StatusSignal<Temperature>        m_temperature;
  private final StatusSignal<Double>             m_closedLoopReference;
  private final StatusSignal<ForwardLimitValue>  m_forwardLimit;
  private final StatusSignal<ReverseLimitValue>  m_reverseLimit;

  /** NOT final: assigned in configureSignals(...), which is not a constructor.
   *  (Rev 1 declared `private final BaseStatusSignal[] m_allSignals;` and assigned it from
   *  configureSignals -- that does not compile.) */
  private SignalSet m_signals;

  // Change detection / latch state. All declared; rev 1 used three of these undeclared.
  private Gains   m_lastSiGains            = null;
  private MotionConstraints m_lastConstraints = null;
  private double  m_lastGoalRot            = Double.NaN;
  private double  m_lastGoalRps            = Double.NaN;
  private double  m_lastArbFf              = Double.NaN;
  private boolean m_lastRequestWasPosition = false;
  private double  m_lastSendSeconds        = Double.NEGATIVE_INFINITY;
  private long    m_deviceResetCount       = 0L;
  /** Latched once if an internal routing invariant is violated. Makes the offending call a
   *  no-op forever instead of throwing from periodic(). See "no throwing from periodic". */
  private boolean m_routingFault           = false;
}
```

#### 3.5.3 FOC vs output mode — two orthogonal things, two fields

Revision 1 conflated them, and the flagship example broke as a result: `DESIGN.md §10.1`'s elevator used `MotorSpec.talonFX(20,"rio").foc(true)` together with `Gains...withFeedforward(0.15, 0.62, 0.03)` — volts-per-SI numbers that revision 1 would have silently reinterpreted as amps-per-SI. `kV = 0.62 A/(m/s)` is meaningless. Worse, doc 02's `GainSink` conversion table has no torque-current row and `FeedbackDesigner` returns kP in V/m, so the tuning wizard, the LQR designer and the persisted `gains.json` could not serve the library's own recommended elevator config.

```java
/** How the device turns a closed-loop output into a command. NOT the same as FOC. */
public enum OutputMode {
  /** MotionMagicVoltage / PositionVoltage / VoltageOut. Gains are VOLTS-per-SI. v0.1 default. */
  VOLTAGE,
  /** MotionMagicTorqueCurrentFOC / PositionTorqueCurrentFOC / TorqueCurrentFOC.
   *  Gains are AMPS-per-SI -- a DIFFERENT number for every gain. v0.2. */
  TORQUE_CURRENT
}
```

* `MotorSpec.talonFX(id, bus).foc(boolean)` sets **only** `ControlRequest.withEnableFOC(...)` on the voltage requests. It does not touch gain units, does not change which request objects are used, and is `true` by default on a Pro-licensed device (`MotionMagicVoltage.EnableFOC` already defaults true). Keeping `.foc(true)` with volt gains — as `DESIGN.md §10.1` and §9.1 below do — is correct and stays.
* `MotorSpec.talonFX(id, bus).outputMode(OutputMode.TORQUE_CURRENT)` is the separate, explicitly named field. **In v0.1 it raises a `ConfigError`** naming the reason:

```
org.pumpkinlib.config.ConfigError: PumpkinLib config error in "Elevator" (PositionConfig)

  field    motors.leader.outputMode = TORQUE_CURRENT
  expected VOLTAGE  (v0.1)

  PumpkinLib gains are VOLTS-per-SI (V/m, V/(m/s), V). Torque-current gains are AMPS-per-SI,
  which is a different number for every one of kP kI kD kS kV kA kG, and the tuning wizard,
  the LQR feedback designer and the persisted gains.json cannot yet convert between them.
  Shipping TORQUE_CURRENT in v0.1 would mean your tuned gains are silently wrong by the
  motor's torque constant.

  If you want FOC's extra torque today, that is a DIFFERENT switch and it is already on:
      MotorSpec.talonFX(20, "rio").foc(true)          // enables FOC on the voltage requests
  Torque-current output mode lands in v0.2 together with the amps-per-SI GainSink row.
```

`describe()` prints the pair unconditionally:

```
  output mode         VOLTAGE  (gains are volts-per-SI: kP V/m, kV V/(m/s), kG V)
  FOC                 ENABLED  (per-request .withEnableFOC(true); Phoenix Pro detected)
```

#### 3.5.4 Config construction — derived, never hand-typed

Everything below comes from `PositionConfig`; the team types none of it.

```java
private void buildConfig(PositionConfig c, MechanismUnits u) {
  Gains g = c.control().gains();          // VOLTS-PER-SI (V/rad, V/m). D1.
  double siPerRot = u.siPerOutputRotation();   // 2*pi for rotary, metersPerOutputRotation for linear

  // 1. GEARBOX. The single place the reduction is expressed for Phoenix.
  //    After this, every getPosition()/setPosition()/soft-limit/MotionMagic value on this
  //    device is in OUTPUT ROTATIONS. This is why the seam unit is output rotations.
  m_config.Feedback.SensorToMechanismRatio = c.reduction().rotorPerOutput();

  // 2. FEEDBACK SOURCE. Derived from FeedbackSpec; RotorToSensorRatio only when fusing.
  if (c.feedback() instanceof FeedbackSpec.FusedCancoder f) {
    m_config.Feedback.FeedbackSensorSource   = FeedbackSensorSourceValue.FusedCANcoder;
    m_config.Feedback.FeedbackRemoteSensorID = f.cancoderId();
    m_config.Feedback.RotorToSensorRatio     = f.rotorPerSensor();
    m_config.Feedback.SensorToMechanismRatio = f.sensorPerOutput();  // overrides (1) -- see §3.7
  }

  // 3. MOTOR OUTPUT.
  m_config.MotorOutput.Inverted = c.motors().leaderInverted()
      ? InvertedValue.Clockwise_Positive : InvertedValue.CounterClockwise_Positive;
  m_config.MotorOutput.NeutralMode = c.control().neutralMode() == NeutralMode.BRAKE
      ? NeutralModeValue.Brake : NeutralModeValue.Coast;

  // 4. CURRENT LIMITS.
  m_config.CurrentLimits.StatorCurrentLimit       = c.limits().current().statorAmps();
  m_config.CurrentLimits.StatorCurrentLimitEnable = true;
  m_config.CurrentLimits.SupplyCurrentLimit       = c.limits().current().supplyAmps();
  m_config.CurrentLimits.SupplyCurrentLimitEnable = true;
  m_config.CurrentLimits.SupplyCurrentLowerLimit  = c.limits().current().supplyLowerAmps();
  m_config.CurrentLimits.SupplyCurrentLowerTime   = c.limits().current().supplyLowerSeconds();

  // 5. SOFT LIMITS. Converted from user units by MechanismUnits -- never by hand.
  //    Min/max are ORDERED by the library, so a negative-reduction sign hack is impossible.
  m_config.SoftwareLimitSwitch.ForwardSoftLimitThreshold = u.toOutputRotations(c.limits().max());
  m_config.SoftwareLimitSwitch.ForwardSoftLimitEnable    = true;
  m_config.SoftwareLimitSwitch.ReverseSoftLimitThreshold = u.toOutputRotations(c.limits().min());
  m_config.SoftwareLimitSwitch.ReverseSoftLimitEnable    = true;

  // 6. GAINS + GRAVITY.
  //    Gains arrive VOLTS-PER-SI and are converted to Phoenix's volts-per-OUTPUT-ROTATION
  //    here, and ONLY here. This is the same conversion doc 02 §4.2's GainSink performs.
  //      kP_phx [V/rot]   = kP_si [V/rad]      * siPerRot [rad/rot]
  //      kV_phx [V/(rot/s)] = kV_si [V/(rad/s)] * siPerRot
  //      kS, kG are volts on both sides -- NO conversion.
  Slot0Configs s = m_config.Slot0;
  s.kP = g.kP() * siPerRot;
  s.kI = g.kI() * siPerRot;
  s.kD = g.kD() * siPerRot;
  s.kV = g.kV() * siPerRot;
  s.kA = g.kA() * siPerRot;
  s.kS = g.kS();
  s.kG = g.kG();

  // Required by doc 02 §4.2 and omitted by rev 1: kS must oppose the CLOSED-LOOP direction,
  // not the measured-velocity direction, or a mechanism holding station at zero velocity
  // chatters as kS flips sign with sensor noise.
  s.StaticFeedforwardSign = StaticFeedforwardSignValue.UseClosedLoopSign;

  // GravityType is DERIVED from the axis, never typed by the user.
  switch (c.control().gravity()) {
    case NONE     -> s.GravityType = GravityTypeValue.Elevator_Static;  // kG == 0 anyway
    case CONSTANT -> s.GravityType = GravityTypeValue.Elevator_Static;
    case COSINE   -> {
      s.GravityType = GravityTypeValue.Arm_Cosine;
      // Phoenix 26.x javadoc, verbatim: "This is an offset applied to the position of the arm,
      // within (-0.25, 0.25) rot, BEFORE calculating the output of kG." So the device computes
      //     kG * cos(position + GravityArmPositionOffset)
      // We want kG * cos(position - horizontalAt), therefore the offset is NEGATED.
      // Rev 1 wrote `+u.toOutputRotations(...)` and pushed kG the wrong way by 2*horizontalAt.
      s.GravityArmPositionOffset = -u.toOutputRotations(c.axis().horizontalReference());
    }
  }

  // 7. PROFILE, in OUTPUT rotations/s and /s^2.
  m_config.MotionMagic.MotionMagicCruiseVelocity =
      u.toOutputRps(c.control().constraints().maxVelocity());
  m_config.MotionMagic.MotionMagicAcceleration   =
      u.toOutputRps2(c.control().constraints().maxAcceleration());
  m_config.MotionMagic.MotionMagicJerk           = c.control().constraints().jerk();

  // 7b. MOTION MAGIC EXPO. Rev 1 selected MotionMagicExpoVoltage but never set these, so a
  //     team enabling Expo silently inherited CTRE's factory defaults (MotionMagicExpo_kV
  //     = 0.12 V/rps, MotionMagicExpo_kA = 0.1 V/rps^2 -- verified in the 26.x javadoc) and
  //     got a profile shaped by CTRE's arbitrary numbers instead of their measured plant.
  //     Under Expo, MotionMagicAcceleration is UNUSED and MotionMagicCruiseVelocity is only
  //     a ceiling (0 = run to the max velocity Expo_kV allows).
  //     Expo_kV/_kA are ALWAYS in volts (verified), even in torque-current output mode, so
  //     the conversion is the same volts-per-SI -> volts-per-output-rotation factor as Slot0.
  if (c.control().useExpo()) {
    m_config.MotionMagic.MotionMagicExpo_kV = clampExpo(g.kV() * siPerRot, 0.001, 100.0);
    m_config.MotionMagic.MotionMagicExpo_kA = clampExpo(g.kA() * siPerRot, 1e-05, 100.0);
  }

  // 8. CONTINUOUS WRAP, derived from RotaryAxis.continuous().
  m_config.ClosedLoopGeneral.ContinuousWrap = c.axis().isContinuous();

  PhoenixUtil.applyVerified(m_leader, m_config, name());   // CONSTRUCTION path only, §3.9
}
```

> **Note on the reviewer's patch.** The finding proposed
> `MotionMagicExpo_kV = g.kV() / u.userPerOutputRotation()`. That is wrong twice over:
> dividing gives `V·s/m²`, and `userPerOutputRotation()` is **degrees** per rotation for a
> rotary axis while `Gains` is volts-per-**radian**. The correct factor is
> `* u.siPerOutputRotation()`, which is what the same finding's companion item and doc 02
> §4.2's `GainSink` actually use. Verified against
> [MotionMagicConfigs (Phoenix 6 26.x)](https://api.ctr-electronics.com/phoenix6/latest/cpp/classctre_1_1phoenix6_1_1configs_1_1_motion_magic_configs.html):
> `MotionMagicExpo_kV` default 0.12 V/rps, range 0.001–100; `MotionMagicExpo_kA` default
> 0.1 V/rps², range 1e-05–100; `MotionMagicCruiseVelocity` — *"When using Motion Magic Expo
> control modes, setting this to 0 will allow the profile to run to the max possible velocity
> based on Expo_kV."*

**Two new Tier-1 validation rules fall directly out of step 6 and 7b:**

```
org.pumpkinlib.config.ConfigError: PumpkinLib config error in "Arm" (PositionConfig)

  field    axis.horizontalAt = 95.0 deg  ->  0.2639 output rotations
  expected within +/-0.25 rotations (+/-90 deg) of the mechanism zero

  Phoenix Slot0Configs.GravityArmPositionOffset accepts only (-0.25, 0.25) rot. A larger
  offset is clamped by the device with NO error, so kG would be applied at the wrong angle
  over the whole range and the arm would sag on one side and slam on the other.

  Fix: re-zero the CANcoder magnet offset so the encoder reads NEAR 0 with the arm level,
  then set RotaryAxis.arm(Degrees.of(0)). Capture the new offset with `pumpkin zero Arm`.
  (If your encoder physically cannot be re-zeroed, set ControlLocation.RIO_FULL -- WPILib's
  ArmFeedforward has no offset limit -- and PumpkinLib will tell you it downgraded.)
```

```
[PumpkinLib][WARN] Elevator: control.useExpo() is true but gains.kV = 0.00 and gains.kA = 0.00.
  Motion Magic Expo shapes its profile ENTIRELY from measured kV and kA; with them at zero
  PumpkinLib would hand the device CTRE's factory defaults (0.12 V/rps, 0.1 V/rps^2) and the
  profile would have nothing to do with your mechanism.
  Fix: run the tuning wizard's kV/kA steps (`pumpkin tune Elevator --feedforward`), or switch
  to ControlLocation.ON_MOTOR_PROFILED with trapezoid constraints (useExpo(false)).
```

#### 3.5.5 Reads — one batched transaction, plus device-reset detection

```java
@Override public void updateInputs(MotorInputs in) {
  StatusCode ok = m_signals.refreshInto(in);   // subscribed channels only; rest stay NaN
  in.connected = ok.isOK();
  in.deviceResetCount = m_deviceResetCount;

  // A Phoenix 6 device that resets (brownout, CAN glitch, a single motor power-cycling) comes
  // back with its PERSISTED config but NO ACTIVE CONTROL REQUEST, and outputs neutral. An
  // elevator held by Motion Magic silently falls. Nothing in rev 1 re-sent anything, and
  // nothing checked. CTRE ships hasResetOccurred() for exactly this.
  if (m_leader.hasResetOccurred()) {
    m_deviceResetCount++;
    in.deviceResetCount = m_deviceResetCount;
    m_leader.clearStickyFaults(0.0);                       // non-blocking
    PhoenixUtil.applyVerified(m_leader, m_config, m_name); // full config, blocking -- correct here
    m_lastGoalRot = Double.NaN;                            // force the next goal to be re-sent
    m_lastGoalRps = Double.NaN;
    m_lastArbFf   = Double.NaN;
    Pumpkin.alerts().warning(m_name, m_name + ": device reset detected on CAN "
        + m_leader.getDeviceID() + " bus '" + m_bus + "' (reset #" + m_deviceResetCount
        + "). Config and setpoint were re-sent. If this repeats, check the power and CAN"
        + " wiring to that motor -- a mid-match reset drops the mechanism until we notice.")
        .set(true);
  }
  for (int i = 0; i < m_followers.length; i++) {
    if (m_followers[i].hasResetOccurred()) {
      m_deviceResetCount++;
      PhoenixUtil.applyVerified(m_followers[i], m_followerConfigs[i], m_name + "/follower" + i);
      m_followers[i].setControl(m_followerRequests[i]);    // re-send the Follower request
    }
  }
}
```

The reset path is the **one** place a blocking apply is allowed outside construction: it happens at most a handful of times per match, the alternative is a dropped mechanism, and it is logged as an epoch so a `PumpkinTracer` spike is explained rather than mysterious.

#### 3.5.6 Writes — latch-preserving, with a heartbeat

```java
@Override public void setPositionGoal(double outputRot, double outputRps, double arbFfVolts) {
  setPositionGoal(outputRot, outputRps, arbFfVolts, null);
}

@Override public void setPositionGoal(double outputRot, double outputRps, double arbFfVolts,
                                      MotionConstraints override) {
  if (m_routingFault) return;                       // latched no-op; see below

  if (m_location == ControlLocation.RIO_FULL) {
    // RIO_FULL must reach the motor through setVoltage(). Rev 1 threw IllegalStateException
    // here, from a method called every loop from periodic() -- so an internal invariant
    // violation killed the robot mid-match with a stack trace, in direct violation of
    // principle 5 ("degrade, never crash"). Degrade and NAME ITSELF instead.
    m_routingFault = true;
    Pumpkin.alerts().error(m_name, m_name + ": internal routing error -- ControlLocation."
        + "RIO_FULL reached the on-motor goal path. This is a PumpkinLib bug, not your config."
        + " The mechanism is now holding position and this call is a no-op. Please file it"
        + " with `pumpkin doctor --bundle`.").set(true);
    setNeutral();
    return;
  }

  // LATCHING: Phoenix holds the last setpoint, so re-sending an identical goal every loop is
  // pure CAN waste (hard requirement from 9143-2025-*/AlLowSimTest, "the original
  // hold-position bug"). But NEVER re-sending is how a device reset goes unnoticed, so the
  // latch carries a 10 Hz HEARTBEAT: unchanged goals are refreshed at least every 100 ms.
  boolean unchanged = outputRot == m_lastGoalRot
                   && outputRps == m_lastGoalRps
                   && arbFfVolts == m_lastArbFf
                   && override == null
                   && m_lastRequestWasPosition;
  double now = Clock.seconds();
  if (unchanged && now - m_lastSendSeconds < 0.100) return;

  m_lastGoalRot = outputRot; m_lastGoalRps = outputRps; m_lastArbFf = arbFfVolts;
  m_lastRequestWasPosition = true; m_lastSendSeconds = now;

  if (override != null && m_dynamicCapable) {
    m_leader.setControl(m_mmDynamic
        .withPosition(outputRot)
        .withVelocity(outputRps)
        .withVelocity(m_units.toOutputRps(override.maxVelocity()))       // cruise ceiling
        .withAcceleration(m_units.toOutputRps2(override.maxAcceleration()))
        .withJerk(override.jerk())
        .withFeedForward(arbFfVolts)
        .withEnableFOC(m_foc));
    return;
  }

  switch (m_location) {
    case ON_MOTOR_PROFILED -> m_leader.setControl(
        m_useExpo ? m_mmExpo.withPosition(outputRot).withVelocity(outputRps)
                            .withFeedForward(arbFfVolts).withEnableFOC(m_foc)
                  : m_mmVoltage.withPosition(outputRot).withVelocity(outputRps)
                               .withFeedForward(arbFfVolts).withEnableFOC(m_foc));
    case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP -> m_leader.setControl(
        m_posVoltage.withPosition(outputRot).withVelocity(outputRps)
                    .withFeedForward(arbFfVolts).withEnableFOC(m_foc));
    case RIO_FULL -> { /* unreachable; handled above */ }
  }
}
```

`DynamicMotionMagicVoltage` — verified in the Phoenix 6 26.x javadoc — *"requires Phoenix Pro and CANivore"*. `m_dynamicCapable` is computed at construction from the licence state and the bus name, `MotorCapabilities.dynamicProfile()` reports it, and a team on the `rio` bus or without Pro who calls `addConstraintProfile` is routed to `RIO_PROFILE_MOTOR_LOOP` **at construction, loudly** (§6.2). This is a deviation from the review's proposed fix, which assumed `DynamicMotionMagicVoltage` was universally available.

Note `RIO_PROFILE_MOTOR_LOOP` calls the same method every loop with a *different* value, so the change guard never trips — the same code path serves both semantics with no branch in `PositionMechanism`.

**No throwing from `periodic()`.** Every `MotorIO` method reachable from `Mechanism.periodic()` degrades and names itself; none throws. `Mechanism.periodic()` additionally wraps its body in a `try/catch(Throwable)` that raises `<name>/periodic-threw` with the exception's class, message and top stack frame, sets the mechanism neutral, and continues the loop — so one broken mechanism cannot take the scheduler down with it.

> **Reviewer pushback:** the review also asked for an ArchUnit rule "forbidding `throw` of any
> unchecked exception from a method reachable from `Mechanism.periodic()` or `MotorIO.*`."
> **Why we're doing something else:** transitive reachability makes that rule unenforceable —
> every array index, every division, every WPILib and vendor call can throw, so the rule would
> either flag the entire JDK or be silently vacuous. We ship two narrower rules that a build
> can actually enforce: (1) **no explicit `throw` statement** in any class under
> `org.pumpkinlib.mechanism..` or `org.pumpkinlib.hardware..` outside constructors, static
> factories, and `Validation`; (2) the `try/catch(Throwable)` in `Mechanism.periodic()` is
> asserted present by a bytecode test. The invariant that revision 1 threw about is asserted in
> a unit test (`RioFullNeverReachesOnMotorGoalPathTest`), which is where invariants belong.

#### 3.5.7 What `describe()` prints for this backend

```
Elevator motor backend
  leader              TalonFX 20 on bus "rio"   (Kraken X60, Phoenix Pro: yes)
  followers           TalonFX 21, OPPOSED
  ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs
                      Motion Magic on the device at 1 kHz).  To change it: .controlLocation(...)
  output mode         VOLTAGE  (gains are volts-per-SI: kP V/m, kV V/(m/s), kG V)
  FOC                 ENABLED  (per-request .withEnableFOC(true))
  profile             Motion Magic trapezoid (useExpo = false)
  dynamic profile     UNAVAILABLE (DynamicMotionMagicVoltage needs Phoenix Pro + CANivore;
                      this device is on the "rio" bus). Constraint profiles would force
                      ControlLocation.RIO_PROFILE_MOTOR_LOOP.
  signals subscribed  POSITION@100Hz VELOCITY@100Hz APPLIED_VOLTS@50Hz STATOR@50Hz
                      SUPPLY@20Hz TEMPERATURE@4Hz          (10 frames/20ms, ~0.15% of "rio")
  signals NOT read    CLOSED_LOOP_REFERENCE, TORQUE_CURRENT, FORWARD_LIMIT, REVERSE_LIMIT
                      -> those MotorInputs fields are NaN, not 0.0
  gains -> device     kP 80.0 V/m   -> Slot0.kP 22.42 V/rot     (x 0.28029 m/rot)
                      kD  2.0 V/(m/s) -> Slot0.kD  0.561
                      kS  0.22 V     -> Slot0.kS  0.22          (volts, unconverted)
                      kG  0.15 V     -> Slot0.kG  0.15, Elevator_Static
                      StaticFeedforwardSign = UseClosedLoopSign
```


### 3.6 Backend: Phoenix 6 — `TalonFXSMotorIO`

TalonFXS is *not* a TalonFX with a different name. Verified against `TalonFXSConfiguration` (Phoenix 6 26.x javadoc), it has `Commutation` and `ExternalFeedback` config groups where TalonFX has `Feedback`. Concretely:

* `Commutation.MotorArrangement` must be set (brushed / Minion / custom brushless) or the device does nothing. **Required field** on `TalonFXSSpec` — no default, because guessing here silently produces a dead motor.
* Gearing goes in `ExternalFeedback.SensorToMechanismRatio` / `RotorToSensorRatio`, not `Feedback.*`. **[UNVERIFIED]** exact field names within `ExternalFeedbackConfigs`; the config *group* name is verified. The implementer must confirm before writing `buildConfig`.
* `Slot0`, `MotionMagic`, `SoftwareLimitSwitch`, `CurrentLimits`, `MotorOutput`, `HardwareLimitSwitch`, `ClosedLoopGeneral` all exist with the same shapes — **verified**. So ~90% of `TalonFXMotorIO` is shared via a package-private `AbstractPhoenixMotorIO` — including `SignalSet`, the reset detector, the heartbeat latch, and the gain conversion — and only the feedback/commutation block differs.

### 3.7 Backend: REVLib — `SparkMotorIO`

REVLib 2026 changed materially from 2025; the user's 9143 repos will need the new names. Verified from the REVLib javadoc and docs:

| 2025 | REVLib 2026 | Note |
|---|---|---|
| `closedLoopController.setReference(...)` | `setSetpoint(setPoint, ControlType, ClosedLoopSlot)` | Verified in REV's "Closed Loop Control Getting Started". |
| `maxMotion.maxVelocity(...)` | `maxMotion.cruiseVelocity(...)` | Verified in `MAXMotionConfig` javadoc. |
| `maxMotion.allowedClosedLoopError(...)` | `maxMotion.allowedProfileError(...)` | Verified. |
| `closedLoop.velocityFF(...)` | `closedLoop.feedForward.kV(...)` | `FeedForwardConfig` is new: `kS, kV, kA, kG, kCos, kCosRatio`. **Verified.** |

The `kG` / `kCos` addition is the most important 2026 REV change for this library: **REVLib now does gravity feedforward on the controller**, `kG` static for elevators and `kCos` multiplied by the cosine of absolute mechanism position for arms — the exact split Phoenix expresses as `Elevator_Static` / `Arm_Cosine`. That means `GravityMode` maps cleanly to *both* vendors with no RIO-side term, which is the reason `Gains` can be a single shared record (§4.3).

```java
package org.pumpkinlib.hardware.rev;

public final class SparkMotorIO implements MotorIO {
  private final SparkBase m_leader;              // SparkMax or SparkFlex
  private final SparkBase[] m_followers;
  private final SparkBaseConfig m_config;        // SparkMaxConfig | SparkFlexConfig
  private final RelativeEncoder m_encoder;
  private final SparkClosedLoopController m_loop;
  private final MechanismUnits m_units;

  private double m_lastGoalRot = Double.NaN, m_lastGoalRps = Double.NaN, m_lastArbFf = Double.NaN;
  private double m_lastSendSeconds = Double.NEGATIVE_INFINITY;

  private void buildConfig(PositionConfig c, MechanismUnits u) {
    // GEARBOX: conversion factors are the REV equivalent of SensorToMechanismRatio.
    // positionConversionFactor turns motor rotations into OUTPUT rotations.
    m_config.encoder
        .positionConversionFactor(1.0 / c.reduction().rotorPerOutput())
        .velocityConversionFactor(1.0 / c.reduction().rotorPerOutput() / 60.0);  // RPM -> output rot/s

    m_config.inverted(c.motors().leaderInverted())
            .idleMode(c.control().neutralMode() == NeutralMode.BRAKE
                      ? IdleMode.kBrake : IdleMode.kCoast)
            .smartCurrentLimit((int) c.limits().current().statorAmps());

    m_config.softLimit
        .forwardSoftLimit(u.toOutputRotations(c.limits().max())).forwardSoftLimitEnabled(true)
        .reverseSoftLimit(u.toOutputRotations(c.limits().min())).reverseSoftLimitEnabled(true);

    // Gains arrive VOLTS-PER-SI. REV's closed loop is unitless-output (-1..1) unless the
    // FeedForwardConfig terms are used, so we convert to volts-per-output-rotation exactly as
    // on Phoenix and then divide the P/I/D terms by the nominal 12 V bus to reach duty cycle.
    Gains g = c.control().gains();
    double siPerRot = u.siPerOutputRotation();
    m_config.closedLoop
        .pid(g.kP() * siPerRot / 12.0, g.kI() * siPerRot / 12.0, g.kD() * siPerRot / 12.0)
        .outputRange(-1, 1);
    // Gravity: same GravityMode enum, different vendor knob. Nothing above the seam changes.
    switch (c.control().gravity()) {
      case NONE     -> { }
      case CONSTANT -> m_config.closedLoop.feedForward.kG(g.kG());
      case COSINE   -> m_config.closedLoop.feedForward.kCos(g.kG());
    }
    m_config.closedLoop.feedForward
        .kS(g.kS()).kV(g.kV() * siPerRot).kA(g.kA() * siPerRot);

    m_config.closedLoop.maxMotion
        .cruiseVelocity(u.toOutputRps(c.control().constraints().maxVelocity()) * 60.0)  // RPM
        .maxAcceleration(u.toOutputRps2(c.control().constraints().maxAcceleration()) * 60.0);

    if (c.axis().isContinuous()) {
      m_config.closedLoop.positionWrappingEnabled(true).positionWrappingInputRange(0, 1);
    }

    // Declarative-total apply: kResetSafeParameters means "this config, and defaults for
    // everything else". Reproducible after a motor swap. kPersistParameters survives brownout.
    RevUtil.applyVerified(m_leader, m_config,
        ResetMode.kResetSafeParameters, PersistMode.kPersistParameters, name());
  }

  @Override public void setPositionGoal(double outputRot, double outputRps, double arbFfVolts) {
    boolean unchanged = outputRot == m_lastGoalRot && outputRps == m_lastGoalRps
                     && arbFfVolts == m_lastArbFf;
    double now = Clock.seconds();
    if (unchanged && now - m_lastSendSeconds < 0.100) return;   // latch + 10 Hz heartbeat
    m_lastGoalRot = outputRot; m_lastGoalRps = outputRps; m_lastArbFf = arbFfVolts;
    m_lastSendSeconds = now;

    ControlType t = (m_location == ControlLocation.ON_MOTOR_PROFILED)
        ? ControlType.kMAXMotionPositionControl : ControlType.kPosition;
    m_loop.setSetpoint(outputRot, t, ClosedLoopSlot.kSlot0);
    // NOTE: the arbitrary-feedforward overload of setSetpoint is [UNVERIFIED] for REVLib 2026,
    // and there is no REV analogue of PositionVoltage.withVelocity(). SparkMotorIO therefore
    // reports capabilities().positionGoalVelocity() == false and
    // capabilities().arbitraryFeedforward() == false until confirmed, and PositionMechanism
    // FOLDS both terms into an RIO-side voltage trim (§6.4) rather than dropping them --
    // which is precisely what TurretIONeo in 0000-XXXX-Robot-Template:62-64 does silently.
  }

  /** REV has no dynamic MAXMotion. Constraint profiles force RIO_PROFILE_MOTOR_LOOP. */
  @Override public void setPositionGoal(double rot, double rps, double ff, MotionConstraints o) {
    setPositionGoal(rot, rps, ff);   // o is unreachable: capabilities().dynamicProfile()==false
  }
}
```

**Connection health.** Every `*IONeo` in `0000-XXXX-Robot-Template` hardcodes `inputs.connected = true` with the comment "Spark MAX has no cheap connection signal". PumpkinLib does better: `in.connected = !m_leader.hasActiveFault() && m_leader.getFirmwareVersion() != 0` plus a heartbeat check that the warning counter is not stuck. **[UNVERIFIED]** exact REVLib 2026 fault-query method names (`hasActiveFault()` / `getFaults()` / `getStickyFaults()`); implementer confirms against `SparkBase` javadoc. This is a real behavior gap, not cosmetic — a disconnected Spark currently reports healthy on this robot.

**Simulation parity.** The NEO path in `0000-XXXX-Robot-Template` has *no simulation at all*, so "switch one constant to NEO" ships untested code. `SparkMotorIO` implements sim via `SparkMaxSim`/`SparkFlexSim` with `SparkSim.iterate(velocity, vbus, dt)` driven by the same physics plant the Phoenix backend uses. Vendor parity in sim is a release requirement, not a nice-to-have.

### 3.8 Backends: generic and sim

**`GenericMotorIO`** wraps any WPILib `MotorController` plus any `Encoder`/`DutyCycleEncoder`, forces `ControlLocation.RIO_FULL`, and runs a `TrapezoidProfile` + `PIDController` + `Controllers.Feedforward` **entirely in SI** — the identical code path `PositionMechanism` uses for `RIO_FULL` on any backend (§6.2), not a second implementation.

Revision 1 said `GenericMotorIO` runs *"the matching WPILib feedforward (`ElevatorFeedforward` for `CONSTANT`, `ArmFeedforward` for `COSINE`, `SimpleMotorFeedforward` for `NONE`)"* and called it through a `m_feedforward` field. **That does not compile**: those three classes have no common supertype in WPILib 2026, and their `calculate` signatures differ in arity. `Controllers.feedforward(gravityMode, siGains, Clock.dt())` (§1.5) is the sealed dispatcher that makes the sentence true, and it is the *only* place the three WPILib classes are named.

`GenericMotorIO` reports `false` for every on-board capability so the downgrade message in §3.2 fires exactly once at boot.
*2027 note:* `MotorController.set()` becomes `setThrottle()`; `DutyCycleEncoder`'s survival past the 2027 Counter removal is **[UNVERIFIED]**.

**`SimMotorIO`** is a *full* `MotorIO` backed by a WPILib plant plus a RIO-side loop. It is used when `MotorSpec.sim()` is selected, and — critically — it is **not** how the Phoenix/REV backends simulate. Those simulate through their own vendor sim state (`TalonFXSimState`, `SparkMaxSim`) so that simulation exercises the *real* config path, including `SensorToMechanismRatio` and Motion Magic. `SimMotorIO` is for teams with no vendor libs installed at all.

### 3.9 Configuring hardware: two paths, and only two

Team 4738's `DeviceUtil.applyParameter` (retry + read-back verification, short-circuit in sim) is the best config primitive found in the survey, and `0000-XXXX-Robot-Template`'s `PhoenixUtil.tryUntilOk` is the weaker version that was *forgotten* in `ModuleIOTalonFX`, `ModuleIONeo`, and `GyroIOPigeon2`.

Revision 1 made `applyVerified` *"the only way CORE configures hardware"* and then had `MotorIO.applyGains` — documented "safe to call every loop" and invoked from `periodic()` whenever `m_tunables.anyChanged()`, at doc 02 §5.4's specified 10 Hz write-through — go through it. `TalonFXConfigurator.apply(config)` blocks with a default **0.050 s** timeout and `refresh()` blocks too, so up to 5 verified rounds is **500 ms of blocked main loop**, ten times a second, on the exact workflow the library is built around: a student dragging a slider in AdvantageScope. That is a guaranteed loop overrun, shipped in the library.

**The config path splits in two, permanently.**

```java
package org.pumpkinlib.hardware.phoenix;

public final class PhoenixUtil {
  private static final int ATTEMPTS = 5;

  // ------------------------------------------------------------------ CONSTRUCTION PATH
  /**
   * Apply, then read back and compare, up to ATTEMPTS times. BLOCKING (default 0.050 s per
   * apply, plus a refresh). Raises a persistent Alert on failure; never throws.
   *
   * LEGAL CALLERS, exhaustively: a MotorIO constructor, MotorIO.reapplyFullConfigBlocking(),
   * PumpkinRegistry.onDisable(), SelfTest, MotorIO.applyRaw(), and the hasResetOccurred()
   * recovery path in §3.5.5. It is ILLEGAL from an enabled periodic().
   */
  public static void applyVerified(ParentDevice device, Object config, String owner) {
    if (RobotBase.isSimulation()) { apply(device, config); return; }
    for (int i = 1; i <= ATTEMPTS; i++) {
      StatusCode applied   = apply(device, config);
      StatusCode refreshed = refresh(device, readBack);
      if (applied.isOK() && refreshed.isOK() && matches(config, readBack)) {
        if (i > 1) Pumpkin.alerts().warning(owner,
            owner + ": config applied on attempt " + i + " (CAN bus was busy at boot).").set(true);
        return;
      }
    }
    Pumpkin.alerts().error(owner, owner + ": config did NOT apply after " + ATTEMPTS
        + " attempts (device " + device.getDeviceID() + " on bus '" + busName(device) + "'). "
        + "The mechanism will run with WHATEVER was previously on the device. "
        + "Check CAN wiring and device ID.").set(true);
  }

  // ------------------------------------------------------------------ PERIODIC PATH
  /**
   * Fire-and-forget slot apply with a ZERO timeout: the call queues the frame and returns
   * without waiting for the device ack. No read-back, no retry, no blocking.
   * This is the ONLY config write allowed on the periodic path, and it is what makes live
   * gain tuning cost nothing.
   *
   * Losing a single gain frame is harmless -- the tunable is re-pushed on the next change,
   * and TunableGains re-pushes the whole set at 10 Hz while a value is being dragged.
   */
  public static StatusCode applyFast(TalonFX d, Slot0Configs slot) {
    return d.getConfigurator().apply(slot, 0.0);
  }
  public static StatusCode applyFast(TalonFX d, MotionMagicConfigs mm) {
    return d.getConfigurator().apply(mm, 0.0);
  }
}
```

`MotorIO.applyGains` and `MotorIO.applyConstraints` call **only** `applyFast`. `RevUtil` has the same split (`SparkBase.configure(..., ResetMode.kNoResetSafeParameters, PersistMode.kNoPersistParameters)` for the fast path — persisting flash on every slider drag would burn the Spark's flash).

Enforced, not just documented:

```java
// ArchUnit, release-blocking.
noClasses().that().resideInAnyPackage("org.pumpkinlib.mechanism..", "org.pumpkinlib.superstructure..")
           .should().callMethod(PhoenixUtil.class, "applyVerified", ParentDevice.class,
                                Object.class, String.class);

// And a budget, so a regression is visible instead of inferred from loop overruns:
PumpkinTracer.budget("Mechanism/ApplyGains", Milliseconds.of(1.0));
PumpkinTracer.budget("Mechanism/Periodic",   Milliseconds.of(2.0));
```

### 3.10 Encoders

**What is abstracted:** the *question* "what is the mechanism's absolute position, and do I trust it?"
**What is passed through:** everything else.

```java
package org.pumpkinlib.hardware;

public interface AbsoluteEncoderIO {
  void updateInputs(AbsoluteEncoderInputs inputs);
  <T extends AbsoluteEncoderIO> Optional<T> as(Class<T> type);

  class AbsoluteEncoderInputs implements PumpkinInputs {
    public boolean connected = false;
    public double  absolutePositionRot = Double.NaN;  // OUTPUT rotations, offset already applied
    public double  velocityRps         = Double.NaN;
    public double  rawPositionRot      = Double.NaN;  // before offset: what you read at the hard stop
    @Override public void toLog(LogSink t)   { /* one put per field, verbatim names */ }
    @Override public void fromLog(LogSource t) { /* one get per field, same names */ }
  }
}
```

Backed by `FeedbackSpec`, a sealed interface that maps to how the *hardware* is actually plumbed:

```java
public sealed interface FeedbackSpec {
  /** Motor's internal rotor only. Position is relative; requires a homing strategy. */
  record RotorOnly() implements FeedbackSpec {}

  /** CANcoder fused into the TalonFX. THE right answer for a Phoenix arm/turret.
   *  rotorPerSensor = gearing between rotor and CANcoder;
   *  sensorPerOutput = gearing between CANcoder and the output shaft (usually 1.0 when
   *  the CANcoder is on the joint). */
  record FusedCancoder(int cancoderId, String canBus, Angle magnetOffset,
                       double rotorPerSensor, double sensorPerOutput) implements FeedbackSpec {}

  /** CANcoder read remotely without fusion (no Phoenix Pro). 8793 uses this today. */
  record RemoteCancoder(int cancoderId, String canBus, Angle magnetOffset,
                        double rotorPerSensor, double sensorPerOutput) implements FeedbackSpec {}

  /** REV absolute encoder on the SPARK's data port. */
  record SparkAbsolute(Angle zeroOffset, boolean inverted) implements FeedbackSpec {}

  /** REV Through Bore read as a raw duty cycle on DIO. The 9143-A pattern.
   *  This is a SEPARATE device from the motor, so it needs the guarded re-seed (below). */
  record DioAbsolute(int dioChannel, Angle zeroOffset, boolean inverted) implements FeedbackSpec {}
}
```

**The guarded re-seed.** `9143-2025-A-Updated/src/main/java/frc/robot/subsystems/CorAl.java:286-344` contains a genuinely hard-won rule that PumpkinLib owns so nobody rediscovers it:

> Seed the motor's internal sensor from the absolute encoder at boot, and again immediately before starting a move — but **never** during a move, because shifting the reference frame mid-motion makes the mechanism land off target. Re-sync from `periodic()` only when neither closed-loop nor manual control is active. Use `setPosition(value, 0.0)`; the default overload blocks the main loop up to 100 ms.

`PositionMechanism` implements exactly that, for `RemoteCancoder`, `SparkAbsolute`, and `DioAbsolute`. For `FusedCancoder` it does **nothing**, because Phoenix already fuses on-device — and `describe()` says so, so a student can see why the code path differs.

**Disagreement alert.** When both an absolute source and the rotor are available, CORE continuously compares them and raises:

```
[PumpkinLib][ERROR] Arm: absolute encoder and motor sensor disagree by 14.2 deg
  (absolute 41.3 deg, motor 27.1 deg, tolerance 2.0 deg).
  Likely causes: wrong FusedCancoder.rotorPerSensor (currently 34.126), a slipped belt,
  or a magnet offset that was captured at a different mechanical position.
```

### 3.11 Gyros

CORE owns only the minimal seam; Drivetrain (doc 02) owns odometry, high-frequency sampling, and the yaw thread.

```java
public interface GyroIO {
  void updateInputs(GyroInputs inputs);
  void setYaw(Rotation2d yaw);
  <T extends GyroIO> Optional<T> as(Class<T> type);

  class GyroInputs implements PumpkinInputs {
    public boolean connected = false;
    public Rotation2d yaw = Rotation2d.kZero;
    public double yawVelocityRadPerSec = 0.0;
    public Rotation2d pitch = Rotation2d.kZero, roll = Rotation2d.kZero;
    public double accelXG = 0.0, accelYG = 0.0, accelZG = 0.0;
    /** Filled ONLY by Drivetrain's high-frequency thread; empty for CORE consumers. */
    public double[] odometryTimestamps = new double[0];
    public Rotation2d[] odometryYawPositions = new Rotation2d[0];
    @Override public void toLog(LogSink t)   { /* ... */ }
    @Override public void fromLog(LogSource t) { /* ... */ }
  }
}
```

Implementations: `Pigeon2GyroIO` (CAN, `getYaw()`/`getAngularVelocityZWorld()`, batched refresh), `StudicaGyroIO` (navX **over CAN or USB only** — SPI is removed in 2027, so the SPI navX is explicitly unsupported and the config builder rejects it with a message naming the 2027 removal), `SimGyroIO`, and `SystemCoreImuGyroIO` **[UNVERIFIED — SystemCore's onboard IMU API does not exist yet; stub the class, do not guess the method names]**.

CORE's own use of the gyro is narrow: `RotaryAxis.fieldLocked(gyro)` supplies the chassis-omega counter-rotation feedforward that `8793-2026-Robot/.../ShooterSubsystem.java:408-415` computes by hand. It reaches the device through `setPositionGoal(rot, rps, arbFf)`'s **velocity** parameter — the parameter revision 1 omitted, which made this feature unimplementable as designed. See §6.4.

### 3.12 Limit switches, beam breaks, and CANrange

Three physically different sensors answer one logical question, so they get one interface with an honest extra channel:

```java
public interface DigitalSensorIO {
  void updateInputs(DigitalSensorInputs inputs);
  <T extends DigitalSensorIO> Optional<T> as(Class<T> type);

  class DigitalSensorInputs implements PumpkinInputs {
    public boolean connected = false;
    /** THE level signal. Debounced by the library. Never an edge -- 9143-A needed a 5-line
     *  workaround because CorAl's auto-stop fired only on the rising edge. */
    public boolean detected = false;
    /** Meters. NaN when the sensor cannot measure distance (switch/beam break). */
    public double distanceMeters = Double.NaN;
    @Override public void toLog(LogSink t)   { /* ... */ }
    @Override public void fromLog(LogSource t) { /* ... */ }
  }
}
```

Specs and backends:

| Spec | Backend | Notes |
|---|---|---|
| `SensorSpec.dio(channel, invert, debounce)` | `DioSensorIO` | DIO survives 2027. |
| `SensorSpec.motorLimit(FORWARD/REVERSE)` | read from `MotorInputs.forward/reverseLimitTripped` | Zero extra CAN traffic — the motor already reports it. **This claim is now true**: declaring a `motorLimit` is what causes `SignalSet` to subscribe the limit signal (§3.5.1). Before that fix the field was frozen and the claim was false. |
| `SensorSpec.canRange(id, bus, thresholdMeters)` | `CanRangeIO` | Phoenix `CANrange.getDistance()` + `getIsDetected()`, with `CANrangeConfiguration.ProximityParams` set from the threshold. **Verified.** |
| `SensorSpec.candi(id, bus, S1/S2)` | `CandiIO` | Phoenix CANdi digital inputs. **[UNVERIFIED]** exact signal getter names. |
| `SensorSpec.sim(BooleanSupplier)` | `SimDigitalSensorIO` | Driven by the mechanism's simulated position by default. |

**Hard limits vs soft limits.** A hard limit wired *into the motor controller* is configured through `HardwareLimitSwitch` and stops the motor in firmware — always preferred, and PumpkinLib enables it whenever `SensorSpec.motorLimit` is used. A hard limit on the RIO's DIO cannot stop the motor in firmware; PumpkinLib zeroes the output in the same loop and raises a warning at config time explaining the latency difference. Soft limits are configured on the device (§3.5.4 step 5) **and** re-clamped in Java before every goal, so a soft limit is enforced even when the device config failed to apply.

---

## 4. Brief item 6 — Units, conversions, and gearing

*(Presented before config and mechanisms because both depend on it.)*

### 4.1 The FOUR-layer unit contract

Revision 1 drew three layers and then leaked a fourth — SI — into `Gains` without naming it, which is how `RIO_FULL` ended up feeding a V/rad kP into a controller whose error was in degrees (a 57.3× error) and handing `ArmFeedforward` degrees where it contractually requires radians from horizontal. The fourth layer is now explicit:

```
  USER UNITS         SI UNITS            OUTPUT ROTATIONS          ROTOR ROTATIONS
  m / deg     <-->   m / rad     <-->    double (the seam)  <-->   inside the vendor
  what a human       what GAINS and      drum rot, joint rot       Phoenix SensorToMechanismRatio
  types and reads    every RIO-side                                REV positionConversionFactor
                     controller use
       ^                  ^                     ^                          ^
       |                  |                     |                          |
     Axis          MechanismUnits.toSi()   MotorIO seam                Reduction
   (geometry)      (identity for LINEAR;                              (gearbox)
                    deg->rad for ANGULAR)
```

* **`Reduction`** is applied *once*, inside the backend config. Java never multiplies or divides by it. This alone deletes the `angle / 360.0 / GEAR_RATIO` family (7 sites in `8793-2026-Robot/.../ShooterSubsystem.java`) and the double-applied-ratio seeding bug at `GroundIntakeSubsystem.java:117-119`.
* **`Axis`** is applied *once*, in `MechanismUnits`, on the way in and out of the seam. Java never writes `* 360.0` (41 occurrences in `0000-XXXX-Robot-Template`) or `inchesToRotations`.
* **SI** is where every gain and every RIO-side controller lives. `toSi`/`fromSi` are applied in exactly one place — `applyClosedLoop()`'s `RIO_FULL` branch (§6.2) — and in the gain conversions of §3.5.4/§3.7.
* **`Measure`** appears only at config boundaries and in public mechanism signatures. Inside `periodic()` everything is a `double`. `MutableMeasure` is never used (removed in 2027).

**The units table, printed in `describe()` and asserted in a test:**

| Quantity | Type | Units | Where |
|---|---|---|---|
| `Gains.kP` | `double` | **V/m** (linear) or **V/rad** (rotary) | `org.pumpkinlib.config.Gains`, D1 |
| `Gains.kV` | `double` | **V/(m/s)** or **V/(rad/s)** | same |
| `Gains.kS`, `Gains.kG` | `double` | **V** | same |
| `MotionConstraints.maxVelocity` | `double` | **user**/s (m/s or **deg**/s) | `MotionConstraints` |
| `Setpoint.value`, `PositionLimits.min/max`, `tolerance` | `Measure<?>` | typed | config boundary |
| `PositionMechanism.goal()/measured()` | `double` | **user** (m or deg) | public API |
| `MotorIO.setPositionGoal(...)` | `double` | **output rotations**, **output rot/s**, **V** | the seam |

Gains are SI and constraints are user units **on purpose**: gains are machine numbers that the tuning wizard measures and `gains.json` persists, and they must be portable across geometry changes; constraints are numbers a driver reasons about out loud ("make the elevator go 1.6 m/s"). The split is stated once, printed by `describe()`, and asserted by `UnitsContractTest`.

### 4.2 `Reduction`

```java
package org.pumpkinlib.units;

/**
 * A gearbox. Always POSITIVE. Direction is expressed by MotorGroup.leaderInverted(),
 * never by a negative ratio -- which makes `TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0`
 * (8793-2026-Robot/.../Constants.java:45) unrepresentable, along with the
 * "gear ratio is negative, so signs cancel" reasoning it forced.
 */
public final class Reduction {
  private final double m_rotorPerOutput;    // rotor rotations per 1 output rotation
  private final String m_derivation;        // "(58:10) x (58:18) x (42:12) = 34.126:1"

  /** A 9:1 gearbox. */
  public static Reduction of(double rotorPerOutput);

  /** Reduction.ofStages(3.0, 4.0) -> 12.0:1. Matches the 9143 habit of writing
   *  ELEVATOR_GEAR_RATIO = 3.0 * 4.0 -- but machine-checkable and self-describing. */
  public static Reduction ofStages(double... stages);

  /** Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12) -- reproduces
   *  9143-2025-A CORAL_PIVOT_GEAR_RATIO exactly, and prints its own derivation. */
  public static Reduction ofTeeth(int driving, int driven);
  public Reduction then(int driving, int driven);
  public Reduction then(double stage);

  public double rotorPerOutput();
  public double outputPerRotor();
  public String describe();      // "(58:10) x (58:18) x (42:12) = 34.126:1 (rotor per output)"

  /** Cross-check against an external source of truth (PathPlanner settings.json, TunerConstants). */
  public boolean approxEquals(double other, double relativeTolerance);
}
```

### 4.3 `Axis` — geometry, SI domain, and where gravity comes from

```java
package org.pumpkinlib.units;

/** Converts OUTPUT ROTATIONS to the units a human wants, declares the SI domain, and
 *  declares the gravity model. */
public sealed interface Axis permits LinearAxis, RotaryAxis {
  double userPerOutputRotation();     // meters per rot, or DEGREES per rot
  double siPerOutputRotation();       // meters per rot, or RADIANS per rot   <-- gains use THIS
  SiDomain siDomain();                // LINEAR | ANGULAR                         [D3]
  String unitLabel();                 // "m" | "deg"
  String siLabel();                   // "m" | "rad"
  GravityMode gravity();
  boolean isContinuous();             // wrap the closed loop? (turret with >360 travel: false;
                                      // a truly continuous azimuth: true)
  /** For COSINE gravity: the user-unit position at which the mechanism is HORIZONTAL.
   *  Maps to Phoenix Slot0Configs.GravityArmPositionOffset (NEGATED -- see §3.5.4) and to
   *  the angle offset ArmFeedforward requires. Zero for a linear axis. */
  double horizontalReference();
}
```

```java
public enum SiDomain {
  LINEAR  { public double toSi(double user) { return user; }                    // meters in, meters out
            public double fromSi(double si) { return si; }
            public String label() { return "m"; } },
  ANGULAR { public double toSi(double user) { return Math.toRadians(user); }    // degrees -> radians
            public double fromSi(double si) { return Math.toDegrees(si); }
            public String label() { return "rad"; } };
  public abstract double toSi(double user);
  public abstract double fromSi(double si);
  public abstract String label();
}
```

```java
/**
 * A linear axis: a drum/sprocket driving a carriage, optionally through cascade rigging.
 * metersPerOutputRotation = 2*pi*pitchRadius * stages.
 */
public record LinearAxis(Distance drumRadius, int stages) implements Axis {
  public LinearAxis {
    // NOTE: this compact constructor no longer THROWS. It contributes to the config's
    // collected error list instead. See §5.6.
  }
  /** Sprocket by chain pitch and tooth count -- the way the part is actually specified.
   *  Pitch diameter = pitch / sin(pi / teeth); pitch RADIUS is half that.
   *  LinearAxis.sprocket(Inches.of(0.25), 22, 2) == 9143-A's ELEVATOR geometry. */
  public static LinearAxis sprocket(Distance chainPitch, int teeth, int stages);
  /** Belt by pitch and pulley teeth: pitch diameter = pitch * teeth / pi. */
  public static LinearAxis pulley(Distance beltPitch, int teeth, int stages);

  @Override public double userPerOutputRotation() {
    return 2 * Math.PI * drumRadius.in(Meters) * stages;
  }
  @Override public double siPerOutputRotation() { return userPerOutputRotation(); }  // m == m
  @Override public SiDomain siDomain()  { return SiDomain.LINEAR; }
  @Override public String unitLabel()   { return "m"; }
  @Override public String siLabel()     { return "m"; }
  @Override public GravityMode gravity(){ return GravityMode.CONSTANT; }
  @Override public boolean isContinuous(){ return false; }
  @Override public double horizontalReference() { return 0.0; }
}
```

```java
/**
 * A rotary axis: arm, pivot, wrist, turret, hood.
 * horizontalAt is the ONE thing that makes cosine gravity correct, and it is REQUIRED
 * for GravityMode.COSINE -- which removes the landmine documented in
 * 0000-XXXX-Robot-Template/Constants.java ("Arm_Cosine is only correct when 0 deg = arm horizontal").
 */
public record RotaryAxis(GravityMode gravity, Angle horizontalAt, boolean continuous,
                         Optional<GyroIO> fieldLock) implements Axis {
  public static RotaryAxis arm(Angle horizontalAt)   { return new RotaryAxis(COSINE, horizontalAt, false, empty()); }
  public static RotaryAxis pivot(Angle horizontalAt) { return new RotaryAxis(COSINE, horizontalAt, false, empty()); }
  /** A turret is (usually) gravity-neutral. `continuous` is false when travel is limited
   *  to a finite range even if that range exceeds 360 deg -- see ContinuousUnwrap in §6.4. */
  public static RotaryAxis turret(boolean continuous) { return new RotaryAxis(NONE, Degrees.of(0), continuous, empty()); }
  public static RotaryAxis wrist(Angle horizontalAt)  { return new RotaryAxis(COSINE, horizontalAt, false, empty()); }
  /** Field-locked azimuth: the chassis-omega counter-rotation feedforward of §6.4. */
  public RotaryAxis fieldLocked(GyroIO gyro) { return new RotaryAxis(gravity, horizontalAt, continuous, of(gyro)); }

  @Override public double userPerOutputRotation() { return 360.0; }
  @Override public double siPerOutputRotation()   { return 2.0 * Math.PI; }
  @Override public SiDomain siDomain()  { return SiDomain.ANGULAR; }
  @Override public String unitLabel()   { return "deg"; }
  @Override public String siLabel()     { return "rad"; }
  @Override public double horizontalReference() { return horizontalAt.in(Degrees); }
}
```

```java
public enum GravityMode {
  /** No gravity term. Turrets, rollers, flywheels. kG must be 0.
   *  -> Controllers.Feedforward backed by SimpleMotorFeedforward. */
  NONE,
  /** Constant kG volts, always in the same direction. Elevators, linear slides.
   *  -> Phoenix GravityTypeValue.Elevator_Static; REVLib FeedForwardConfig.kG;
   *     Controllers.Feedforward backed by ElevatorFeedforward. */
  CONSTANT,
  /** kG * cos(position - horizontal). Arms, pivots, wrists, hoods.
   *  -> Phoenix GravityTypeValue.Arm_Cosine + NEGATED GravityArmPositionOffset;
   *     REVLib FeedForwardConfig.kCos;
   *     Controllers.Feedforward backed by ArmFeedforward (radians FROM HORIZONTAL). */
  COSINE
}
```

### 4.4 `MechanismUnits` — the object every layer shares

```java
package org.pumpkinlib.units;

/**
 * Built ONCE from (Reduction, Axis). Handed to the MotorIO, the physics sim, the soft-limit
 * derivation, the telemetry namespace, and the tuning UI. There is no other converter
 * in PumpkinLib, and mechanism code never performs a unit conversion by hand.
 */
public final class MechanismUnits {
  public MechanismUnits(Reduction reduction, Axis axis);

  // ---- seam <-> user -------------------------------------------------
  public double toUser(double outputRotations);          // rot -> m or deg
  public double toOutputRotations(double userUnits);
  public double toUserPerSec(double outputRps);
  public double toOutputRps(double userPerSec);
  public double toOutputRps2(double userPerSecSquared);

  // ---- user <-> SI  (the layer rev 1 leaked without naming) -----------
  /** Identity for a LinearAxis (user IS meters); Math.toRadians for a RotaryAxis. */
  public double toSi(double userUnits);
  public double fromSi(double siUnits);
  public double toSiPerSec(double userPerSec);
  public double fromSiPerSec(double siPerSec);
  public double toSiPerSec2(double userPerSecSquared);
  /** Radians per output rotation (2*pi) or meters per output rotation. The ONE factor that
   *  converts volts-per-SI Gains into the vendor's volts-per-output-rotation slot. */
  public double siPerOutputRotation();
  public SiDomain siDomain();
  public String siLabel();

  // ---- convenience for Measure-typed callers ---------------------------
  public double toOutputRotations(Distance d);
  public double toOutputRotations(Angle a);
  public Distance toDistance(double outputRotations);
  public Angle    toAngle(double outputRotations);

  // ---- for sim and for the free-speed sanity check ---------------------
  public double rotorPerOutput();
  public double userPerRotorRotation();
  /** Free speed of the mechanism at the carriage/joint, from the motor model and count. */
  public double freeSpeedUserPerSec(MotorModel model, int motorCount);

  public Reduction reduction();
  public Axis axis();
  public String unitLabel();

  /** Human-readable derived model. Printed at boot, on `describe()`, and in the config snapshot.
   *  ASSERTED VERBATIM by DescribeSnapshotTest against the block below. */
  public String describe();
}
```

`describe()` output for the elevator of §5.4 — **every number below is computed, and `DescribeSnapshotTest` string-matches this block verbatim** (the assertion Principle 11 promises and revision 1 did not have; revision 1's block was internally inconsistent by a factor of 2.9):

```
Elevator geometry
  gearbox             (3:1) x (4:1) = 12.000:1  (rotor rotations per drum rotation)
  sprocket            #25 chain, 0.250 in pitch x 22 teeth
                      pitch dia = 0.250 / sin(pi/22) = 1.7567 in
                      pitch radius = 0.8784 in = 0.022310 m
  rigging             2 stages (cascade) -> the carriage moves 2x the drum surface
  travel per drum rot 0.280293 m  (11.035 in)   = 2*pi * 0.022310 * 2
  travel per rotor rot 0.023358 m  (0.9196 in)
  positive direction  UP (leader CCW-positive, not inverted)
  soft limits         0.000 m .. 1.397 m  ==  0.000 .. 4.984 drum rot
  free speed estimate 2 x Kraken X60 @ 5800 rpm free (FOC) -> 96.67 rotor rot/s
                      -> 8.056 drum rot/s -> 2.26 m/s at the carriage
  cruise requested    1.60 m/s  (71% of free speed)  OK
  accel requested     6.00 m/s^2  (needs ~172 N at the carriage; the pair stalls near 3800 N)
  SI domain           LINEAR  -- gains are volts-per-meter; toSi() is the identity
  gravity             CONSTANT (kG applied always, Phoenix Elevator_Static)
  ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs
                      Motion Magic on the device at 1 kHz).  To change it: .controlLocation(...)
```

That block is the direct answer to joel truher's critique that a flat config "doesn't reveal anything about the attached real-world system" (`web-smallteam.json`, painPoint 2), and to guineawheek's list of questions a CSA must be able to answer at an event.

---


## 5. Brief item 3 — The config system

### 5.1 The decision, and why

**Java records as the immutable value, a fluent builder for authoring, `with*()` copies for per-robot variation, *collected* validation (never a throw from `<clinit>`), `describe()` for the derived physical model, and a flattened struct snapshot logged at boot.**

Rejected alternatives, with reasons:

| Alternative | Rejected because |
|---|---|
| Flat `public static final` constants (AdvantageKit templates, `0000-XXXX-Robot-Template/Constants.java`, `reefscape2025/util/Constants.java`) | Cannot express two of the same mechanism. Cannot be passed as a value. Cannot be validated. Forces copy-paste-and-rename. The 9143 A/B robots and the practice-bot case make this disqualifying. |
| Deploy-directory JSON (YAGSL) | YAGSL's own docs say configuring a module "requires a lot of patience and you will likely never get it working on the first try" and that a wrong value produces "behavior that you won't easily be able to identify". Deploy JSON is also invisible to code review and does not round-trip into replay. |
| Mutable config class embedding the vendor config (254 `ServoMotorSubsystemConfig`) | Excellent for a single-vendor team; but it *is* a `TalonFXConfiguration`, so it cannot serve REV or generic hardware, and it cannot validate (no compact constructor, no immutability). We steal the *negative space* lesson instead: see below. |
| Subclass + void `config*()` setters (Spectrum 3847) | Composes with inheritance, but a config becomes a class rather than a value, so `with*()` variation and struct logging are unavailable, and validation has no natural home. |
| Annotated constants / our own annotation processor | A vendordep cannot install an annotation processor into a consumer build. Also adds exactly the "magic" the community punishes. |

**The negative-space rule we do keep from 254:** do not re-model every vendor knob. PumpkinLib's config models only what is (a) physical, (b) shared across vendors, or (c) required to derive a vendor knob. Everything else is reached through `applyRaw()`. `ControlConfig` has ~11 fields, not 40.

### 5.2 The structure mirrors the physical machine

```
PositionConfig("Elevator")
 ├── motors     MotorGroup      leader MotorSpec + followers + inversion
 ├── reduction  Reduction       the gearbox
 ├── axis       Axis            drum + rigging  (or joint + gravity reference)
 ├── feedback   FeedbackSpec    what actually measures position
 ├── limits     PositionLimits  soft min/max, hard switches, current
 ├── control    ControlConfig   WHERE the loop runs, gains, constraints, tolerance
 ├── homing     HomingStrategy  how position becomes true at boot
 ├── setpoints  Setpoint[]      named goals
 ├── sim        SimConfig       mass/MOI + starting position
 └── errors     List<ConfigError>   COLLECTED, never thrown  (§5.6)
```

One nesting level per real component, named after the real component. Nothing is flat.

### 5.3 The records

```java
package org.pumpkinlib.config;

/** Gains, in VOLTS-PER-SI (D1):
 *    kP  V/m   or V/rad          kD  V/(m/s)   or V/(rad/s)
 *    kV  V/(m/s) or V/(rad/s)    kA  V/(m/s^2) or V/(rad/s^2)
 *    kS  V                       kG  V
 *  Every backend converts to its own units at the seam, ONCE (§3.5.4, §3.7), by multiplying
 *  the position-like terms by MechanismUnits.siPerOutputRotation(). This is why one gain set
 *  is portable across Phoenix, REV, generic and RIO_FULL, and why the tuning wizard's
 *  FeedbackDesigner can hand back a single answer.
 *
 *  NAMED FIELDS ONLY. There is deliberately no multi-double constructor:
 *  reefscape2025/util/custom/GainConstants.java has a LIVE positional-overload bug where
 *  (P,I,D,FF,minOut,maxOut) silently binds to (P,I,D,S,V,G), dropping the feedforward.
 *  Builder-only construction makes that class of bug unrepresentable. */
public record Gains(double kP, double kI, double kD,
                    double kS, double kV, double kA, double kG) {
  public static Gains pid(double kP, double kI, double kD) { return new Gains(kP,kI,kD,0,0,0,0); }
  public Gains withKs(double v) { ... }  public Gains withKv(double v) { ... }
  public Gains withKa(double v) { ... }  public Gains withKg(double v) { ... }
  public Gains withKp(double v) { ... }  public Gains withKd(double v) { ... }

  /** Two readable literals instead of seven ternaries. Replaces the ~50 lines per mechanism
   *  at reefscape2025/util/Constants.java:590-606, :651-667, :741-779. */
  public static Gains realOrSim(Gains real, Gains sim) {
    return RobotBase.isReal() ? real : sim;
  }

  /** Sentinel for tier-3 placeholder detection (§5.6). */
  public static final Gains UNTUNED = new Gains(Double.NaN,0,0,0,0,0,0);
}

/** Profile constraints, in USER units per second^n (m/s, deg/s...). Converted at the seam. */
public record MotionConstraints(double maxVelocity, double maxAcceleration, double jerk) {
  public static MotionConstraints of(double v, double a) { return new MotionConstraints(v, a, 0); }
}

public record CurrentLimits(double statorAmps, double supplyAmps,
                            double supplyLowerAmps, double supplyLowerSeconds) {
  public static CurrentLimits of(Current stator, Current supply) { ... }
  /** Sensible defaults by motor, so a rookie config can omit this entirely. */
  public static CurrentLimits defaultsFor(MotorModel model) { ... }
}

public record PositionLimits(Measure<?> min, Measure<?> max,
                             CurrentLimits current,
                             double overTempCelsius,
                             double followerToleranceRot,
                             Optional<SensorSpec> forwardHardStop,
                             Optional<SensorSpec> reverseHardStop) {
  /** Drives SignalSet's limit-signal subscription (§3.5.1) -- so "zero extra CAN traffic"
   *  is a true statement rather than a frozen field. */
  public boolean usesMotorLimit(HardStop side) { ... }
}

public record ControlConfig(ControlLocation location,
                            ControlLocationSource locationSource,  // EXPLICIT | DEFAULTED
                            Gains gains,                  // volts-per-SI
                            MotionConstraints constraints,// user units per s^n
                            boolean useExpo,
                            GravityMode gravity,          // usually derived from Axis; override here
                            Measure<?> tolerance,         // atGoal position tolerance
                            double velocityTolerance,     // user units/s
                            double goalDebounceSeconds,
                            MotorIO.NeutralMode neutralMode,
                            double manualDeadband,        // stick deadband, 0..1
                            double manualScale) { }       // fraction of full output at full stick

/** The ONLY thing a team writes to get simulation. */
public record SimConfig(Mass carriageMass,               // LinearAxis: moving mass
                        MomentOfInertia momentOfInertia, // RotaryAxis: MOI about the joint
                        Distance armLength,              // RotaryAxis: for SingleJointedArmSim
                        Measure<?> startingPosition,
                        boolean simulateGravity) {
  /** MOI estimate for a uniform bar; wraps SingleJointedArmSim.estimateMOI. */
  public static MomentOfInertia estimateArmMoi(Distance length, Mass mass);
  public static SimConfig arm(Distance length, Mass mass, Angle start);
}
```

**`Setpoint` — a typed handle, and the documented default.**

```java
/**
 * A named, unit-typed goal. Replaces the enum-with-a-double-payload boilerplate that
 * appears 5x across the user's repos with inconsistent accessor names
 * (getHeight() vs getAngle() vs inches()).
 *
 * A Setpoint carries the mechanism it belongs to and whether it RESOLVED. An unresolved
 * Setpoint is what `config.setpoint("L4 ")` returns for a typo: it never throws (a throw from
 * a static initializer is a dead robot, §5.6), it carries the error, and PumpkinRegistry
 * surfaces it before anyone presses a button.
 */
public record Setpoint(String mechanism, String name, Measure<?> value, boolean resolved) {
  public static Setpoint of(String mechanism, String name, Distance d) { ... }
  public static Setpoint of(String mechanism, String name, Angle a)    { ... }
  static Setpoint unresolved(String mechanism, String name)            { ... }
  public boolean isResolved() { return resolved; }
}
```

```java
public record PositionConfig(String name, MotorGroup motors, Reduction reduction, Axis axis,
                             FeedbackSpec feedback, PositionLimits limits, ControlConfig control,
                             HomingStrategy homing, List<Setpoint> setpoints, SimConfig sim,
                             List<ConfigError> errors) {
  /**
   * COMPACT CONSTRUCTOR: pure, local, and NON-THROWING.
   *
   * It performs only checks that need nothing but this config's own fields, and it STORES the
   * result. It does not touch CanIdRegistry, does not read files, does not allocate in the
   * happy path (Validation.localChecks returns the List.of() singleton when clean), and above
   * all does not throw -- because every example declares configs as `public static final` in
   * RobotConfig, so a throw surfaces as ExceptionInInitializerError from
   * frc.robot.RobotConfig.<clinit>, robot code never starts, and the carefully written
   * ConfigError message becomes a *cause* buried under JVM class-init frames. See §5.6.
   */
  public PositionConfig {
    setpoints = List.copyOf(setpoints);
    errors    = Validation.localChecks(name, motors, reduction, axis, feedback,
                                       limits, control, homing, setpoints, sim);
  }

  public static Builder linear(String name)  { ... }
  public static Builder rotary(String name)  { ... }

  // Per-robot variation without copy-paste. Directly serves 9143's A/B robots and the
  // practice-bot case that 3061-lib solves with ~100 abstract getters.
  // Because the compact constructor has NO global side effects, running it again on a
  // with*() copy is free and cannot produce a false "CAN ID conflict" (§5.6b).
  public PositionConfig withMotors(MotorGroup m);
  public PositionConfig withReduction(Reduction r);
  public PositionConfig withGains(Gains g);
  public PositionConfig withLimits(PositionLimits l);
  public PositionConfig withSetpoint(String name, Measure<?> value);

  /** Typed setpoint handle. Never throws; an unknown name yields Setpoint.unresolved(...)
   *  AND appends a ConfigError to this config's error list (surfaced by PumpkinRegistry). */
  public Setpoint setpoint(String name);
  public Optional<Setpoint> findSetpoint(String name);

  public MechanismUnits units();     // memoized
  public String describe();
}
```

**`ControlLocation` is required in the record and *defaulted* in the builder.**

Revision 1 made `.controlLocation(...)` a required builder call. "Required" and "visible" are different properties: as written, the fifth line of a rookie's first config was an expert question — *should the profile and feedback run on the motor controller, or on the roboRIO?* — that they cannot answer, and every wrong answer is silently plausible. The field stays required in `ControlConfig` (so it is always in the log and the snapshot), and the builder fills it in from the leader's `MotorSpec`:

| Leader `MotorSpec` | Default `ControlLocation` |
|---|---|
| `talonFX` / `talonFXS` | `ON_MOTOR_PROFILED` |
| `spark` | `ON_MOTOR_PROFILED` |
| `sim` | `ON_MOTOR_PROFILED` |
| `generic` | `RIO_FULL` (the only thing it can do) |

Visibility is preserved by `ControlLocationSource` and by `describe()` printing it **unconditionally, with provenance**:

```
  ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs
                      Motion Magic on the device at 1 kHz).  To change it: .controlLocation(...)
```
```
  ControlLocation     RIO_FULL (explicit -- you called .controlLocation(RIO_FULL)).
                      On a TalonFX this disables Motion Magic and the 1 kHz on-motor loop.
```

The boot dump, the config snapshot, and the tier-3 checklist all still show it. This removes an unanswerable decision from minute 9 without hiding anything.

### 5.4 A complete elevator config

Two-Kraken cascade elevator, rotor-only feedback with current-spike homing, 12:1, 22-tooth #25 sprocket, 2-stage cascade, 55 in of travel.

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import org.pumpkinlib.config.*;
import org.pumpkinlib.units.*;
import org.pumpkinlib.hardware.ControlLocation;
import org.pumpkinlib.mechanism.HomingStrategy;

public final class RobotConfig {

  public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
      // --- what drives it -------------------------------------------------
      // .foc(true) enables FIELD-ORIENTED CONTROL on the voltage requests. It does NOT
      // change gain units -- gains stay volts-per-SI. (Torque-current output is a separate,
      // v0.2 field: .outputMode(OutputMode.TORQUE_CURRENT).)
      .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                        .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
      // --- the gearbox: ONE place. Change this number and gains, sim, soft limits,
      //     Motion Magic constraints, and telemetry units ALL follow. ----------
      .reduction(Reduction.ofStages(3.0, 4.0))          // 12:1  -> 2.26 m/s free at the carriage
      // --- the geometry ----------------------------------------------------
      .axis(LinearAxis.sprocket(Inches.of(0.25), 22, /* cascade stages */ 2))
      // --- what measures it -------------------------------------------------
      .feedback(new FeedbackSpec.RotorOnly())
      // --- limits -----------------------------------------------------------
      .softLimits(Inches.of(0.0), Inches.of(55.0))
      .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
      // --- control ----------------------------------------------------------
      // ControlLocation is OMITTED on purpose: it defaults to ON_MOTOR_PROFILED because the
      // leader is a TalonFX, and describe() prints that with its provenance. Write
      // .controlLocation(...) when you actually want to override it.
      // GAINS ARE VOLTS-PER-SI: kP V/m, kD V/(m/s), kV V/(m/s), kA V/(m/s^2), kS and kG volts.
      .gains(Gains.realOrSim(
          /* real */ Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.15),
          /* sim  */ Gains.pid(150.0, 0.0, 0.0).withKv(5.00).withKa(0.06).withKg(0.15)))
      .constraints(MotionConstraints.of(/* m/s */ 1.6, /* m/s^2 */ 6.0))
      .tolerance(Inches.of(0.5), /* velocity, m/s */ 0.05, /* debounce s */ 0.06)
      .manualControl(/* deadband */ 0.10, /* scale */ 0.30)
      // --- how position becomes true at boot --------------------------------
      .homing(HomingStrategy.currentSpike()
          .direction(HomingStrategy.Direction.REVERSE)
          .voltage(Volts.of(-1.5))
          .currentThreshold(Amps.of(30))
          .debounce(Seconds.of(0.15))
          .timeout(Seconds.of(4.0))
          .seedTo(Inches.of(0.0)))
      // --- named goals -------------------------------------------------------
      .setpoint("STOW",  Inches.of(0.0))
      .setpoint("L1",    Inches.of(8.0))
      .setpoint("L2",    Inches.of(20.5))
      .setpoint("L3",    Inches.of(37.5))
      .setpoint("L4",    Inches.of(52.5))
      // --- simulation: the ONLY sim code a team writes -----------------------
      .sim(Pounds.of(24.0), /* start */ Inches.of(0.0))
      .build();

  // Typed setpoint handles. THIS is the documented default for superstructure goals --
  // AxisGoal.of(ELEVATOR_L4) is compiler-checked; AxisGoal.named("L4") is the escape hatch.
  public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");
  public static final Setpoint ELEVATOR_L2   = ELEVATOR.setpoint("L2");
  public static final Setpoint ELEVATOR_L3   = ELEVATOR.setpoint("L3");
  public static final Setpoint ELEVATOR_L4   = ELEVATOR.setpoint("L4");
}
```

**Where the numbers come from** (all of these are recomputed by `describe()` and asserted by `DescribeSnapshotTest`; revision 1's version of this config asked for 1.6 m/s on a mechanism whose free speed was 0.62 m/s and would have tripped the library's own Tier-2 alert on the flagship example):

| Quantity | Derivation | Value |
|---|---|---|
| sprocket pitch dia | `0.25 in / sin(π/22)` | 1.7567 in |
| pitch radius | half of that | 0.8784 in = 0.022310 m |
| travel / drum rot | `2π · 0.022310 · 2 stages` | **0.280293 m** (11.035 in) |
| free speed | `5800 rpm ÷ 60 ÷ 12 · 0.280293` | **2.26 m/s** |
| cruise 1.6 m/s | 71 % of free speed | no alert |
| soft max 55 in | `1.397 m ÷ 0.280293` | 4.984 drum rot |
| kV | `≈ 12 V ÷ 2.26 m/s`, less kS headroom | 5.00 V/(m/s) |
| kG | 24 lb carriage, 2:1 cascade, 12:1, Kraken kT ≈ 0.0194 N·m/A, R ≈ 0.0328 Ω, ×2 motors | 0.15 V |
| Slot0.kP on the device | `80 V/m × 0.280293 m/rot` | 22.42 V/rot |

Gains are illustrative starting points for the sim; on a real robot the tuning wizard measures kS/kV/kA and `FeedbackDesigner` produces kP/kD. The derivations are printed so a student can check them.

That is the whole elevator. It replaces, in `0000-XXXX-Robot-Template`: `ElevatorIO.java`, `ElevatorIOTalonFX.java`, `ElevatorIONeo.java`, `Elevator.java`, the `ElevatorConstants` block, the `RobotContainer` ternary, the `Dashboard` constructor arg + publishing lines, and the `AutoLogOutputTest` expected-key entries.

**Change the gear ratio in exactly one place.** Editing `Reduction.ofStages(3.0, 4.0)` to `(3.0, 5.0)` automatically updates:
1. `TalonFXConfiguration.Feedback.SensorToMechanismRatio`
2. both soft-limit thresholds (recomputed from inches)
3. `MotionMagicCruiseVelocity` / `MotionMagicAcceleration` (recomputed from m/s)
4. `MotionMagicExpo_kV` / `_kA` when `useExpo` is on
5. the `ElevatorSim` gearing
6. the position/velocity scaling of every telemetry key and of the tuning UI
7. the free-speed sanity check and its warning threshold
8. `describe()` and the logged config snapshot
9. the homing seed conversion
10. the `atGoal` tolerance conversion

None of those is written by the team. Note that gains do **not** change: they are volts-per-SI, so they describe the *mechanism*, not the gearbox — which is the whole point of D1 and is why the change above is safe.

### 5.5 A complete arm config

Single Kraken pivot with a fused CANcoder on the joint, cosine gravity, hard stops at both ends, 0° defined at the horizontal.

```java
public static final PositionConfig ARM = PositionConfig.rotary("Arm")
    .motors(MotorGroup.leader(MotorSpec.talonFX(22, "rio").inverted(true)))
    // (58:10) x (58:18) x (42:12) -- written as the tooth counts on the actual gears.
    .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))
    // COSINE gravity + WHERE horizontal is. This one field removes the
    // "Arm_Cosine is only correct when 0 deg = arm horizontal" landmine.
    // It MUST be within +/-90 deg of the mechanism zero -- Phoenix clamps
    // Slot0Configs.GravityArmPositionOffset to (-0.25, 0.25) rot with no error (§3.5.4).
    .axis(RotaryAxis.arm(/* the arm is horizontal at */ Degrees.of(0.0)))
    // The CANcoder is ON THE JOINT (sensorPerOutput = 1.0) and sees the rotor through
    // the full 34.126:1. Phoenix fuses on-device, so PumpkinLib does NOT re-seed in Java.
    .feedback(new FeedbackSpec.FusedCancoder(
        /* id */ 23, /* bus */ "rio",
        /* magnetOffset */ Rotations.of(-0.1387),
        /* rotorPerSensor */ 34.126, /* sensorPerOutput */ 1.0))
    .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
    .hardStop(HardStop.REVERSE, SensorSpec.motorLimit(SensorSpec.Limit.REVERSE))
    .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
    // GAINS ARE VOLTS-PER-SI: for a rotary axis that means V/rad and V/(rad/s), NOT per degree.
    //   kP 9.5 V/rad  -> Slot0.kP  = 9.5 * 2*pi = 59.7 V/rot
    //   kV 0.65 V/(rad/s) -> Slot0.kV = 4.08
    //   kG 0.56 V     -> Slot0.kG  = 0.56, Arm_Cosine, GravityArmPositionOffset = -0.0
    .gains(Gains.realOrSim(
        Gains.pid(9.5, 0.0, 0.35).withKs(0.20).withKv(0.65).withKa(0.02).withKg(0.56),
        Gains.pid(20.0, 0.0, 0.0).withKv(0.65).withKa(0.02).withKg(0.56)))
    .constraints(MotionConstraints.of(/* deg/s */ 180.0, /* deg/s^2 */ 540.0))
    .tolerance(Degrees.of(1.5), /* deg/s */ 5.0, /* s */ 0.06)
    .manualControl(0.10, 0.20)
    // Absolute encoder is fused, so homing is a no-op that says so out loud.
    .homing(HomingStrategy.absoluteSeed())
    .setpoint("STOW",    Degrees.of(95.0))
    .setpoint("INTAKE",  Degrees.of(-10.0))
    .setpoint("SCORE",   Degrees.of(35.0))
    .sim(SimConfig.arm(/* length */ Inches.of(21.0), /* mass */ Pounds.of(9.5),
                       /* start */ Degrees.of(95.0)))
    .build();

public static final Setpoint ARM_STOW   = ARM.setpoint("STOW");
public static final Setpoint ARM_INTAKE = ARM.setpoint("INTAKE");
public static final Setpoint ARM_SCORE  = ARM.setpoint("SCORE");
```

kG derivation, printed by `describe()`: a 9.5 lb (4.31 kg) arm with its centre of mass at half of 21 in (0.267 m) makes 11.3 N·m at the joint; through 34.126:1 that is 0.331 N·m at the rotor; at Kraken kT ≈ 0.0194 N·m/A that is 17.1 A; at R ≈ 0.0328 Ω that is **0.56 V**. Free speed 5800 rpm ÷ 34.126 = 2.83 output rot/s = 17.8 rad/s, so kV ≈ 12 ÷ 17.8 = **0.67 V/(rad/s)**; 0.65 leaves headroom for kS.

Note what is **absent**: no `/360.0`, no `GravityTypeValue`, no `SensorToMechanismRatio`, no `RotorToSensorRatio` arithmetic, no `SingleJointedArmSim` construction, no `MotionMagicCruiseVelocity` in rotations, no soft limits in rotations, no `Math.max/Math.min` ordering hack, no `GravityArmPositionOffset` sign reasoning. Each of those exists by hand in at least one of the user's three repos.

### 5.6 Validation: what happens when a value is wrong

**Nothing throws from a static initializer. Ever.**

Revision 1 had Tier-1 validation throw from the record's compact constructor while every example declared configs as `public static final` fields. The result on a real robot: `ExceptionInInitializerError` from `frc.robot.RobotConfig.<clinit>`, robot code never starts, the driver station shows red "Robot Code", and the beautifully written `ConfigError` message is a *cause* nested under a stack trace whose top frames are JVM class-init machinery. Revision 1 also claimed these are *"caught before `robotInit` finishes... never on the field"* — false. A student editing a soft limit at an event hits exactly this, with a dead robot and no obvious message, and there was no safe mode.

The pipeline is now:

```
compact constructor    -> pure, local, non-throwing; STORES List<ConfigError>
PumpkinRegistry.addAll -> collects errors from every config,
                          runs the CROSS-config checks (CAN IDs, setpoint names, bus budget),
                          prints ALL of them at once,
                          and if any are present enters SAFE_MODE
PumpkinRobot           -> completes construction either way; the robot BOOTS
```

```java
package org.pumpkinlib.config;

/** A VALUE, not an exception. Carries everything the message needs. */
public record ConfigError(Severity severity, String owner, String field, String value,
                          String expected, String explanation, String declaredAt) {
  public enum Severity { FATAL, WARNING, PLACEHOLDER }
}
```

```java
package org.pumpkinlib.mechanism;

public final class PumpkinRegistry {
  public void addAll(Object... registrables) {
    List<ConfigError> all = new ArrayList<>();
    for (Object o : registrables) all.addAll(configErrorsOf(o));         // per-config, local
    all.addAll(CanIdRegistry.scanForConflicts(resolvedSpecs(registrables))); // ONE global scan
    all.addAll(SetpointNameChecker.check(registrables));                 // §8.9
    all.addAll(Validation.crossChecks(registrables));                    // bus budget, etc.

    Validation.printAll(all);                     // ALL of them, once -- not one per deploy cycle
    PumpkinLog.put("/Pumpkin/Config/Errors", Validation.toStructArray(all));

    if (all.stream().anyMatch(e -> e.severity() == FATAL)) enterSafeMode(all);
  }
}
```

**SAFE_MODE, in full.** When a FATAL config error exists:

* `PumpkinRobot` **completes construction**. The robot boots, connects to the driver station, and appears on the dashboard.
* The `CommandScheduler` runs. Telemetry runs. Alerts publish. `describe()` still works.
* **Every mechanism is forced to `MechanismMode.NEUTRAL` and refuses every command.** `goTo`, `manual`, `home`, `setVoltage` return `Commands.none()` with a named alert. Nothing moves.
* `/Pumpkin/Driver/SafeMode` is `true`, and `/Pumpkin/Driver/SafeModeErrors` carries the full structured list.
* A persistent `Alert.kError` names the count and the first error verbatim, so the driver station shows a sentence rather than an exception.
* `pumpkin doctor` prints the whole list with fixes.

> A misconfigured robot boots, connects, and tells you what is wrong — instead of showing red
> "Robot Code" with nothing. That is the difference between a five-minute fix in the pits and a
> lost match.

**Tier 1 — structurally impossible: FATAL, collected, robot boots into SAFE_MODE.**

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Elevator" (PositionConfig)

  field    reduction
  value    0.0 (rotor rotations per output rotation)
  expected > 0

  A reduction is how many times the MOTOR turns for one turn of the OUTPUT shaft.
  A 12:1 gearbox is Reduction.of(12.0), not Reduction.of(1.0/12.0).
  If you know the tooth counts, prefer Reduction.ofTeeth(58, 10).then(58, 18).

  declared at frc.robot.RobotConfig.<clinit>(RobotConfig.java:41)
```

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Arm" (PositionConfig)

  field    limits.min = 95.0 deg
  field    limits.max = 10.0 deg
  expected limits.min < limits.max

  These look swapped. PumpkinLib will not order them for you, because on a mechanism with
  an inverted motor "min" and "max" are a real physical claim about which way is positive.
  Check describe() -- it prints which direction is positive -- then fix the call.

  declared at frc.robot.RobotConfig.<clinit>(RobotConfig.java:78)
```

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Arm" (PositionConfig)

  field    control.gravity = COSINE
  field    axis.horizontalAt = <not set>

  Cosine gravity compensation needs to know WHERE the mechanism is horizontal, or kG will be
  applied with the wrong sign over half the range. Use RotaryAxis.arm(Degrees.of(<angle at
  which the arm is level>)). If your encoder reads 0 with the arm level, that is Degrees.of(0).
```

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Arm" (PositionConfig)

  field    axis.horizontalAt = 95.0 deg  ->  0.2639 output rotations
  expected within +/-0.25 rotations (+/-90 deg) of the mechanism zero

  Phoenix Slot0Configs.GravityArmPositionOffset accepts only (-0.25, 0.25) rot. A larger
  offset is clamped by the device with NO error, so kG would be applied at the wrong angle
  over the whole range: the arm sags on one side and slams on the other.

  Fix: re-zero the CANcoder magnet offset so the encoder reads NEAR 0 with the arm level
  (`pumpkin zero Arm` captures it for you), then RotaryAxis.arm(Degrees.of(0)).
  Alternative: ControlLocation.RIO_FULL -- WPILib's ArmFeedforward has no offset limit --
  and PumpkinLib will tell you it downgraded.
```

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Wrist" (PositionConfig)

  field    motors.leader.canId = 64
  expected 1..62 for a Phoenix 6 device

  Constructing a Phoenix device with an out-of-range ID throws and prevents robot code from
  starting. (This exact value is documented as a past field failure in
  9143-2025-A-Updated/src/main/java/frc/robot/Constants.java.)
  The robot has booted into SAFE_MODE so you can read this message on the driver station.
```

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib config error in "Elevator" (PositionConfig)

  field    motors.leader.outputMode = TORQUE_CURRENT
  expected VOLTAGE  (v0.1)

  PumpkinLib gains are VOLTS-per-SI. Torque-current gains are AMPS-per-SI and the tuning
  wizard cannot yet convert them.  If you wanted FOC's extra torque, that is the separate
  and already-enabled switch:  MotorSpec.talonFX(20, "rio").foc(true)
```

### 5.6b Cross-config checks happen exactly once, globally

Revision 1 put the CAN-ID conflict check inside the compact constructor via a `CanIdRegistry` side effect. But **every `with*()` copy re-runs that constructor**, so the per-robot overlay pattern in `design/06 §7.3` — the design's own answer to the 9143 A/B sibling-robot problem — would register CAN ID 22 twice and throw a false "CAN ID conflict" on a *correct* config.

All global state is therefore removed from record constructors. The duplicate-ID scan runs once, in `PumpkinRegistry.addAll(...)`, over the **final resolved** config set:

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib CAN ID conflict

  device id 22 on bus "rio" is claimed by BOTH:
    - "Arm" leader        (TalonFX)   declared at RobotConfig.java:76
    - "Intake" leader     (TalonFX)   declared at RobotConfig.java:103

  Two devices with the same ID on the same bus will fight. Renumber one in Phoenix Tuner X
  AND here.
```

Release-blocking test: `WithCopyDoesNotDoubleRegisterTest` asserts that
`ELEVATOR.withGains(g).withReduction(r).withMotors(m)` produces zero errors and that
`PumpkinRegistry.addAll(that)` reports no conflict.

**Tier 2 — physically implausible: a persistent `Alert`, plus a log entry. Never fatal.** The robot still runs; the team can drive.

```
[PumpkinLib][WARN] Elevator: constraints.maxVelocity = 4.00 m/s, but the free-speed estimate
  is 2.26 m/s. Computed from 2 x Kraken X60 (5800 rpm free with FOC) through 12.000:1 into a
  0.022310 m pitch-radius sprocket with 2 cascade stages (0.280293 m of carriage travel per
  drum rotation). Motion Magic will never reach this cruise velocity, so profiles will
  effectively be acceleration-limited only.
  Suggested: <= 1.81 m/s (80% of free speed).
```

```
[PumpkinLib][WARN] Arm: control.gains.kG = 0.00 V with GravityMode.COSINE. The arm will sag.
  Procedure: disable, hold the arm horizontal, enable, raise kG until the arm just holds
  station with kP = 0. Live-tunable at /Tuning/Arm/kG. Expected magnitude for a 9.5 lb arm
  at 21 in through 34.126:1 is about 0.56 V.
```

```
[PumpkinLib][WARN] Elevator: control.tolerance = 0.500 in is SMALLER than one loop step at
  cruise -- at 1.60 m/s the carriage moves 0.032 m (1.260 in) per 20 ms, 2.5 tolerance bands
  per loop. The mechanism can never be OBSERVED inside the tolerance band while it is still
  moving fast, so atGoal() only latches after the profile decelerates. That is correct, and
  PumpkinLib's velocity gate (|v| <= 0.050 m/s) enforces it -- this warning exists so
  "atGoal took longer than I expected" is already explained.
  If you want an earlier release, use atSetpoint() or a Superstructure earlyRelease
  predicate. Do NOT widen the tolerance.
```

```
[PumpkinLib][WARN] Elevator: control.useExpo() is true but gains.kV = 0.00 and gains.kA = 0.00.
  Motion Magic Expo shapes its profile ENTIRELY from measured kV and kA; with them at zero
  PumpkinLib would hand the device CTRE's factory defaults (0.12 V/rps, 0.1 V/rps^2).
  Fix: run `pumpkin tune Elevator --feedforward`, or set .useExpo(false) and use trapezoid
  constraints.
```

```
[PumpkinLib][WARN] Wrist: HomingStrategy.assumeAtBoot(Degrees.of(90)) assumes the mechanism
  is resting on a known hard stop every time the robot powers on. There is no sensor
  confirming this. If the wrist can be moved by hand while disabled, position will be wrong
  and cosine gravity compensation will push the wrong way.
  Fix: add an absolute encoder (FeedbackSpec.SparkAbsolute / FusedCancoder) or a limit
  switch (HomingStrategy.limitSwitch(...)).
```

That last one directly addresses the boot-seeding assumption baked into six IOs in `0000-XXXX-Robot-Template` (`ArmIOTalonFX.java:76`, `WristIOTalonFX:66`, `IntakeIOTalonFX:48`, `TurretIOTalonFX:62`, `ClimberIOTalonFX:60`, `ElevatorIOTalonFX:83-84`) — the template documents the assumption in a comment; PumpkinLib says it out loud, every boot, on the driver station.

**Tier 3 — placeholder detection.** Any config field left at a PumpkinLib sentinel (`Gains.UNTUNED`, `Reduction.UNMEASURED`) produces a boot-time checklist, reproducing the `ADD`/`VERIFY`/`TUNE` convention of `0000-XXXX-Robot-Template/Constants.java` as machine-checkable state instead of a comment:

```
[PumpkinLib] First-setup checklist -- 3 values still at placeholders:
  VERIFY  Elevator.reduction         Reduction.UNMEASURED   (count the gear teeth)
  TUNE    Arm.control.gains.kG       0.0 V                  (/Tuning/Arm/kG)
  ADD     Turret.feedback            RotorOnly              (no absolute reference)
```

### 5.7 Config in the log

The exact configuration the robot ran with is written to the log once at boot, as a flat struct:

```java
/** Flattened to plain doubles/strings deliberately: whether StructGenerator.genRecord
 *  supports Measure-typed record components is UNVERIFIED, and a config snapshot is
 *  exactly the wrong place to find out. */
public record MechanismConfigSnapshot(
    String name, String kind, String backend, String controlLocation, String controlLocationSource,
    String outputMode, boolean focEnabled,
    int leaderCanId, String canBus, int followerCount,
    double reductionRotorPerOutput, String reductionDerivation,
    double userPerOutputRotation, double siPerOutputRotation, String unitLabel, String siLabel,
    double softMin, double softMax,
    double kP, double kI, double kD, double kS, double kV, double kA, double kG,   // volts-per-SI
    String gravityMode, double horizontalReference, double gravityArmPositionOffsetRot,
    double maxVelocity, double maxAcceleration, double jerk,
    boolean useExpo, double expoKvVoltsPerRps, double expoKaVoltsPerRps2,
    double statorAmps, double supplyAmps,
    String feedbackKind, String homingKind, String subscribedSignals,
    double simMassOrMoi, double simStartPosition,
    int configErrorCount, boolean safeMode,
    String pumpkinLibVersion, String wpilibVersion, String vendorLibVersion
) implements StructSerializable {
  public static final Struct<MechanismConfigSnapshot> struct =
      StructGenerator.genRecord(MechanismConfigSnapshot.class);
}
```

Published to `/Pumpkin/Config/<name>` and to the WPILOG. This makes "is it a bug in the library? a regression between versions?" answerable from a log file alone — the exact question `web-smallteam.json` names as the debuggability acceptance test. `subscribedSignals` in particular makes a `NaN` field self-explaining from the log.

---


## 6. Brief item 2 — Mechanism templates

### 6.1 `Mechanism` — the base

```java
package org.pumpkinlib.mechanism;

import edu.wpi.first.wpilibj2.command.Subsystem;   // 2027: org.wpilib.commands2.Subsystem
import org.pumpkinlib.telemetry.PumpkinLog;
import org.pumpkinlib.tuning.TunableGains;

/**
 * Base for every PumpkinLib mechanism.
 *
 * It `implements Subsystem` (the INTERFACE), not SubsystemBase (the class). That is
 * deliberate and it is what lets one library serve both house styles found in the user's repos:
 *
 *   COMMAND-BASED (8793, 9143):   mechanism.registerWithScheduler();
 *                                 -> CommandScheduler calls periodic(), command factories and
 *                                    requirements work normally, setDefaultCommand works.
 *
 *   STATE-BASED  (0000-XXXX):     do NOT register. Call mechanism.periodic() yourself from
 *                                 Superstructure/Robot. Nothing in the CommandScheduler is
 *                                 involved. `implements Subsystem` costs zero bytes and zero
 *                                 behavior when unregistered -- WPILib's Subsystem interface
 *                                 does not self-register; only SubsystemBase's constructor does.
 *
 * It is also the seam for WPILib 2027 Commands v3, whose core noun is literally "Mechanism".
 */
public abstract class Mechanism implements Subsystem {
  protected final String          m_name;
  protected final MechanismUnits  m_units;
  protected final MotorIO         m_io;
  protected final MotorInputs     m_inputs = new MotorInputs();
  /** D9: there is no TelemetrySink field. CORE writes through the PumpkinLog statics.
   *  D11: the tunable handle is the concrete TunableGains, not a TunableGroup. */
  protected final TunableGains    m_tunables;

  // ---------- log keys, precomputed ONCE in the constructor ----------
  // Rev 1 built every key by concatenation inside periodic():
  //     m_log.putDouble("/Pumpkin/" + m_name + "/Goal", m_goal)
  // -- six String allocations plus StringBuilder churn per mechanism per loop. Four mechanisms
  // at 50 Hz is 1,200 String allocations/second, in direct violation of principle 9 and of
  // DESIGN.md §1.7 constraint 9's "zero bytes allocated in periodic() after warmup".
  protected final String kInputs, kGoal, kSetpoint, kMeasured, kError, kAtGoal, kMode,
                         kFeedbackVolts, kFeedforwardVolts, kActiveProfile, kOpenLoopClamped,
                         kDeviceResets;

  protected Mechanism(String name, MechanismUnits units, MotorIO io, TunableGains tunables) {
    m_name = name; m_units = units; m_io = io; m_tunables = tunables;
    String base       = "/Pumpkin/" + name + "/";
    kInputs           = base + "Inputs";
    kGoal             = base + "Goal";
    kSetpoint         = base + "Setpoint";
    kMeasured         = base + "Measured";
    kError            = base + "Error";
    kAtGoal           = base + "AtGoal";
    kMode             = base + "Mode";
    kFeedbackVolts    = base + "FeedbackVolts";
    kFeedforwardVolts = base + "FeedforwardVolts";
    kActiveProfile    = base + "ActiveProfile";
    kOpenLoopClamped  = base + "OpenLoopClamped";
    kDeviceResets     = base + "DeviceResetCount";
  }

  public final String name();
  public final MotorIO io();                    // page-1 escape hatch
  public final MechanismUnits units();
  public final MotorInputs inputs();            // read-only view for a Superstructure
  public final TunableGains tunables();

  /** Called by CommandScheduler if registered, or by you if not. Idempotent within a loop.
   *  Wraps its body in try/catch(Throwable): a mechanism that fails raises
   *  <name>/periodic-threw, goes neutral, and the scheduler keeps running. Degrade, never crash. */
  @Override public abstract void periodic();
  @Override public void simulationPeriodic();

  /** Registers with the CommandScheduler. Opt-in. */
  public final void registerWithScheduler();

  public final void stop();                     // -> NEUTRAL mode, honors brake/coast
  public final String describe();
  public final Trigger connected();
  public final Trigger stalled();
  public final Trigger overTemperature();

  /** True when a FATAL config error put the robot in SAFE_MODE. Every command factory
   *  returns Commands.none() and every setter is a no-op while this is true (§5.6). */
  public final boolean safeMode();
}
```

```java
public enum MechanismMode { NEUTRAL, OPEN_LOOP, CLOSED_LOOP, MANUAL, HOMING, CHARACTERIZING }
```

Five verbatim copies of `private enum ControlMode {POSITION, DUTY_CYCLE, NEUTRAL}` in `0000-XXXX-Robot-Template` and three hand-rolled 3-boolean state machines in the 9143 repos collapse into this one enum.

### 6.2 `PositionMechanism`

Serves elevator, arm/pivot, wrist, turret, hood, climber — anything whose controlled quantity is position.

```java
package org.pumpkinlib.mechanism;

public final class PositionMechanism extends Mechanism {

  public PositionMechanism(PositionConfig config) { ... }
  public PositionMechanism(PositionConfig config, MotorIO io) { ... }   // inject for tests

  // ---------------- goal / setpoint / measured: three DIFFERENT things ----------------
  /** Where we have been told to end up. In USER units (m or deg). */
  public double goal();
  /** Where the controller is being told to be RIGHT NOW. Equals goal() for ON_MOTOR_*
   *  (the device owns the profile); equals the profile step for RIO_*. User units. */
  public double setpoint();
  /** Where the mechanism actually is. User units. */
  public double measured();
  public double measuredVelocity();

  public Distance measuredDistance();     // typed views; LinearAxis only, else empty Optional
  public Angle    measuredAngle();        // RotaryAxis only

  // ---------------- commanding ----------------
  /** Clamped to soft limits. If clamping occurs, raises <name>/soft-limit-clamped-setpoint
   *  with the requested and applied values -- so a "why won't it go all the way up" question
   *  answers itself. */
  public void setGoal(Distance goal);
  public void setGoal(Angle goal);
  /** The TYPED path, and the documented default. Compiler-checked. */
  public void setGoal(Setpoint named);
  /** The STRING path -- the escape hatch. Unknown names NEVER throw and NEVER silently
   *  no-op: they raise a sticky <name>/unknown-setpoint alert naming the valid set (§8.9). */
  public void setGoal(String setpointName);
  public Optional<Setpoint> setpoint(String name);

  /** Latch the CURRENT measured position as the goal. Distinct from goTo(STOW) --
   *  8793's maintainStateCommand() is named "maintain" but actually slams to zero
   *  (ShooterSubsystem.java:456-462). PumpkinLib keeps these two verbs apart forever. */
  public void holdPosition();

  public void setVoltage(double volts);
  public void setDutyCycle(double fraction);
  public void setNeutral();

  /** Deadband + scale + capture-and-hold on release, from ControlConfig. One implementation
   *  replaces five hand-written copies and the hardcoded 0.1/0.15 magic numbers in
   *  0000-XXXX-Robot-Template/.../Wrist.java:50-51. */
  public void manualControl(double stickInput);

  // ---------------- at-goal semantics ----------------
  public boolean atGoal();
  public boolean atGoal(double toleranceUserUnits);
  public boolean atSetpoint();
  public Trigger atGoalTrigger();
  public Trigger atGoalTrigger(double toleranceUserUnits);

  // ---------------- homing ----------------
  public boolean isHomed();
  public Command homeCommand();
  public void beginHoming();
  public Trigger homed();

  // ---------------- command factories (see §7) ----------------
  public Command goTo(Setpoint s);          // typed, compiler-checked
  public Command goTo(String name);         // escape hatch; unknown -> Commands.none() + kError
  public Command goToAndWait(Setpoint s);
  public Command hold();
  public Command manual(DoubleSupplier stick);
  public Command neutral();
  public Command sysIdQuasistatic(SysIdRoutine.Direction d);
  public Command sysIdDynamic(SysIdRoutine.Direction d);
}
```

**`atGoal` semantics — stated precisely, because everyone gets this wrong.**

```java
private boolean computeAtGoal(double tolerance) {
  boolean nearGoal   = Math.abs(m_goal - m_measured) <= tolerance;
  boolean settled    = Math.abs(m_measuredVel) <= m_config.control().velocityTolerance();
  boolean profileDone = switch (m_config.control().location()) {
      // The device owns the profile. Position + velocity is all we can know, and it is enough.
      case ON_MOTOR_PROFILED, ON_MOTOR_DIRECT -> true;
      // We own the profile, so we know exactly when it is finished.
      case RIO_PROFILE_MOTOR_LOOP, RIO_FULL   -> m_profileFinished;
  };
  return m_goalDebouncer.calculate(nearGoal && settled && profileDone);
}
```

Rules that fall out of this and are enforced:
* `atGoal()` is **false while disabled**, always. A mechanism that has been dragged by hand is not "at goal".
* `atGoal()` is **false in SAFE_MODE**, always.
* `atGoal()` is **false before homing completes** when a `HomingStrategy` other than `absoluteSeed`/`assumeAtBoot` is configured. A mechanism that does not know where it is cannot be at a goal.
* `atGoal()` is **false for at least one loop after `setGoal()`**, even if the new goal equals the measurement, so a `waitUntil(atGoal)` cannot fall through instantly on a no-op move.
* `atSetpoint()` (instantaneous profile tracking) is a **separate**, unlatched, undebounced predicate, used for tracking-quality telemetry and for the early-release suppliers of §8.6.
* The velocity term is what makes `atGoal` mean *arrived*, not *passing through*. Six `Math.abs(inputs.positionX - targetX) <= ALLOWED_ERROR` sites in `0000-XXXX-Robot-Template` omit it and will latch mid-flight.

**`periodic()` in full — zero allocations, precomputed keys, no blocking CAN:**

```java
@Override public void periodic() {
  m_io.updateInputs(m_inputs);
  PumpkinLog.processInputs(kInputs, m_inputs);

  m_measured    = m_units.toUser(m_inputs.positionRot);
  m_measuredVel = m_units.toUserPerSec(m_inputs.velocityRps);

  // 1. Health -> Alerts. Free, automatic, no per-robot wiring.
  m_disconnectedAlert.set(!m_inputs.connected);
  m_overTempAlert.set(m_inputs.temperatureCelsius > m_config.limits().overTempCelsius());
  m_followerAlert.set(followerDisagreement() > m_config.limits().followerToleranceRot());
  m_resetAlert.set(m_inputs.deviceResetCount > 0);

  // 2. Live tuning: push changed gains/constraints to hardware.
  //    applyGains/applyConstraints are the NON-BLOCKING applyFast path (§3.9). Rev 1 routed
  //    this through applyVerified -- up to 5 blocking rounds x 50 ms, ten times a second while
  //    a student drags a slider. Budgeted at 1.0 ms and traced.
  if (m_tunables.anyChanged()) {
    PumpkinTracer.enter("Mechanism/ApplyGains");
    m_io.applyGains(m_tunables.gains());              // volts-per-SI
    m_io.applyConstraints(m_tunables.constraints());  // user units per s^n
    PumpkinTracer.exit("Mechanism/ApplyGains");
  }

  // 3. Absolute-encoder guarded re-seed (only for non-fused sources, only when idle).
  m_absoluteSeeder.maybeReseed(m_mode, m_inputs, m_io);

  // 4. Disabled / SAFE_MODE: never hold a stale setpoint across a disable->enable edge.
  if (DriverStation.isDisabled() || safeMode()) {
    m_mode = MechanismMode.NEUTRAL;
    m_siProfileState.position = m_units.toSi(m_measured);   // re-seed so re-enable never jumps
    m_siProfileState.velocity = m_units.toSiPerSec(m_measuredVel);
    m_goalIsFresh = true;
  }

  // 5. Apply.
  switch (m_mode) {
    case NEUTRAL       -> m_io.setNeutral();
    case OPEN_LOOP,
         MANUAL        -> m_io.setVoltage(clampOpenLoopAgainstSoftLimits(m_openLoopVolts));
    case CLOSED_LOOP   -> applyClosedLoop();
    case HOMING        -> m_homing.periodic(this);
    case CHARACTERIZING-> { /* SysId drives the IO directly */ }
  }

  // 6. Report. Goal / Setpoint / Measured are ALWAYS all three, always in USER units.
  //    Keys are fields, not concatenations. Zero allocation.
  PumpkinLog.put(kGoal,     m_goal);
  PumpkinLog.put(kSetpoint, m_setpointUser);
  PumpkinLog.put(kMeasured, m_measured);
  PumpkinLog.put(kError,    m_goal - m_measured);
  PumpkinLog.put(kAtGoal,   m_atGoal);
  PumpkinLog.put(kMode,     m_modeName);        // MechanismMode.name() cached per enum constant
  PumpkinLog.put(kDeviceResets, (double) m_inputs.deviceResetCount);
}
```

Logging setpoint, measurement, error, and applied output for *every* loop of *every* mechanism, automatically, is the direct answer to the "abstractions hide the intermediate data" criticism: PumpkinLib makes those signals **more** visible than hand-written code, not less. And it does so at zero allocation — the CI allocation test runs against the full §9 example robot and is a **G2 gate condition**.

**`applyClosedLoop()` — where the four control locations diverge, and only here:**

```java
private void applyClosedLoop() {
  double goalRot = m_units.toOutputRotations(m_goal);
  MotionConstraints active = activeConstraints();       // constraint profiles, below

  switch (m_config.control().location()) {

    case ON_MOTOR_PROFILED, ON_MOTOR_DIRECT -> {
      // Gravity is computed ON the controller (Phoenix kG + GravityType; REV kG/kCos),
      // so the arbitrary feedforward is zero. Latching + heartbeat live inside the IO.
      // The velocity term carries the field-locked turret's counter-rotation (§6.4).
      double goalRps = m_units.toOutputRps(fieldLockVelocityUserPerSec());
      if (active == m_baseConstraints) {
        m_io.setPositionGoal(goalRot, goalRps, 0.0);
      } else {
        m_io.setPositionGoal(goalRot, goalRps, 0.0, active);   // DynamicMotionMagicVoltage
      }
      m_profileFinished = true;
      m_setpointUser = m_goal;
    }

    case RIO_PROFILE_MOTOR_LOOP -> {
      // 254 / 4738 pattern: step our own profile, hand the STEP to the on-board position loop.
      // The profile runs in SI so its constraints and the gains agree; only the seam call
      // converts back to output rotations.
      TrapezoidProfile.State goalState =
          new TrapezoidProfile.State(m_units.toSi(m_goal), 0.0);
      m_siProfileState = siProfile(active).calculate(Clock.dt(), m_siProfileState, goalState);
      m_setpointUser = m_units.fromSi(m_siProfileState.position);
      m_io.setPositionGoal(m_units.toOutputRotations(m_setpointUser),
                           m_units.toOutputRps(m_units.fromSiPerSec(m_siProfileState.velocity)),
                           0.0);
      m_profileFinished = siProfile(active).isFinished(0.0);
    }

    case RIO_FULL -> {
      // ===================================================================================
      // THIS IS THE ONE PLACE IN PUMPKINLIB WHERE SI CONVERSION HAPPENS.
      //
      // Gains are volts-per-SI (V/m, V/rad -- D1). Revision 1 ran m_pid.calculate(measured,
      // goal) in USER units, feeding a V/rad kP into a controller whose error was in DEGREES
      // (57.3x) and a V/m kP into an inches-based one (39.37x), and handed ArmFeedforward
      // degrees where it contractually requires RADIANS FROM HORIZONTAL. That is exactly the
      // "10,000x spread" failure that §3 of 02-tuning.md declares this library's reason to
      // exist. Everything below -- profile, PID, feedforward, clamp -- is SI. Only the
      // telemetry lines convert back.
      // ===================================================================================
      double siGoal     = m_units.toSi(m_goal);
      double siMeasured = m_units.toSi(m_measured);

      TrapezoidProfile.State goalState = new TrapezoidProfile.State(siGoal, 0.0);
      TrapezoidProfile p = siProfile(active);          // constraints converted to SI at build
      // `next` FIRST, so isFinished(0.0) refers to the step we actually apply.
      TrapezoidProfile.State next = p.calculate(2.0 * Clock.dt(), m_siProfileState, goalState);
      TrapezoidProfile.State cur  = p.calculate(Clock.dt(),       m_siProfileState, goalState);
      m_siProfileState = cur;

      // m_siPid is a plain PIDController built from the SI gains; enableContinuousInput
      // (-pi, pi) when the axis is continuous.
      double fb = m_siPid.calculate(siMeasured, cur.position);
      // ArmFeedforward wants radians FROM HORIZONTAL, so the horizontal reference is
      // subtracted here and nowhere else. Elevator/Simple ignore the position argument.
      double ff = m_ff.calculate(cur.position - m_siHorizontalReference,
                                 cur.velocity, next.velocity);

      m_io.setVoltage(PumpkinMath.clamp(fb + ff, -12.0, 12.0));
      m_profileFinished = p.isFinished(0.0);
      m_setpointUser = m_units.fromSi(cur.position);

      PumpkinLog.put(kFeedbackVolts, fb);
      PumpkinLog.put(kFeedforwardVolts, ff);
    }
  }
}
```

Built once, in the constructor:

```java
// SI feedforward, dispatched over the gravity archetype. The three WPILib feedforward classes
// have NO common supertype and different calculate() arities, which is why this indirection
// exists and why rev 1's `m_feedforward.calculate(pos, vel)` did not compile. Doc 02 §1.5.
m_ff = Controllers.feedforward(m_config.control().gravity(),
                               m_config.control().gains(),      // volts-per-SI
                               Clock.dt());

m_siPid = new PIDController(g.kP(), g.kI(), g.kD(), Clock.dt());   // SI in, volts out
if (m_config.axis().isContinuous()) m_siPid.enableContinuousInput(-Math.PI, Math.PI);

m_siHorizontalReference = m_units.toSi(m_config.axis().horizontalReference());
```

**Constraint profiles — and the Phoenix reality behind them.**

```java
public void addConstraintProfile(String name, MotionConstraints c, BooleanSupplier when);
// elevator.addConstraintProfile("gentle", SLOW, coral::hasPiece);
```

This reproduces the fast/slow idea from `reefscape2025` (Elevator/Wrist each hold two profiles chosen by a `BooleanSupplier`, so the mechanism automatically moves gently while holding a game piece). Selection is logged (`kActiveProfile`) so "why is it slow right now" is a glance, not an investigation.

**But Motion Magic cruise/accel/jerk are DEVICE CONFIG.** Naively switching them at runtime forces a config apply from `periodic()` every time the supplier flips — the blocking-CAN failure of §3.9. Three cases, resolved at construction and printed by `describe()`:

| Backend | How a constraint profile is served |
|---|---|
| Phoenix, **Pro-licensed and on a CANivore** | `DynamicMotionMagicVoltage` — cruise/accel/jerk ride along with the *request*. Zero config applies. `capabilities().dynamicProfile() == true`. |
| Phoenix on the `rio` bus, or unlicensed | No dynamic request exists. `ControlLocation` is upgraded-in-place to `RIO_PROFILE_MOTOR_LOOP` **at construction**, with an alert. The RIO owns the profile; the device keeps its position loop. |
| REV | REVLib has no dynamic MAXMotion. Same as above: `RIO_PROFILE_MOTOR_LOOP`, at construction, with an alert. |

```
[PumpkinLib][WARN] Elevator: addConstraintProfile("gentle", ...) needs runtime profile changes,
  but DynamicMotionMagicVoltage requires Phoenix Pro AND a CANivore bus, and this TalonFX
  (CAN 20) is on bus "rio". Switching Motion Magic constraints would require a blocking CAN
  config write from periodic(), which PumpkinLib will not do.
  ControlLocation has been changed from ON_MOTOR_PROFILED to RIO_PROFILE_MOTOR_LOOP: the
  roboRIO now steps the profile at 50 Hz and the Kraken still runs the position loop at 1 kHz.
  Behaviour is very close; gains transfer unchanged. describe() shows this permanently.
```

**Gravity compensation, summarized:**

| Axis | `GravityMode` | Phoenix 6 | REVLib 2026 | `RIO_FULL` (all SI) |
|---|---|---|---|---|
| Elevator / linear | `CONSTANT` | `Slot0.kG` + `GravityTypeValue.Elevator_Static` | `closedLoop.feedForward.kG` | `ElevatorFeedforward.calculateWithVelocities(v, vNext)` |
| Arm / pivot / wrist / hood | `COSINE` | `Slot0.kG` + `Arm_Cosine` + **negated** `GravityArmPositionOffset` | `closedLoop.feedForward.kCos` | `ArmFeedforward.calculateWithVelocities(θ−θ₀, v, vNext)` |
| Turret / roller / flywheel | `NONE` | kG = 0 | none | `SimpleMotorFeedforward.calculateWithVelocities(v, vNext)` |

The team writes `kG` once, in volts. Which vendor knob it lands in, and the horizontal-reference offset (and its **sign**), are derived from the `Axis`.

**Soft and hard limits, both layers:**

1. **On the device** (`SoftwareLimitSwitch` / REV `softLimit`) — firmware-enforced, works even if robot code hangs.
2. **In Java**, before every goal — `PumpkinMath.clamp(goal, min, max)`, so the limit holds even if the device config failed to apply, and so the clamp can be *reported*.
3. **Hard stops**: `SensorSpec.motorLimit(...)` wires the controller's own limit-switch input (firmware stop, zero latency) **and subscribes the corresponding limit `StatusSignal`** (§3.5.1), so `forwardLimitTripped` is a real reading rather than a frozen `false`. A DIO switch is checked in `periodic()` and zeroes output that loop, with a config-time warning naming the latency difference.
4. **Open-loop clamp**: `clampOpenLoopAgainstSoftLimits()` zeroes a manual/open-loop command that pushes past a soft limit, which the device's soft limit would do anyway — but doing it in Java means a student pushing the stick sees `/Pumpkin/Arm/OpenLoopClamped = true` instead of "the stick stopped working".

### 6.3 Homing / zeroing

```java
package org.pumpkinlib.mechanism;

public sealed interface HomingStrategy {

  /** Rotor is seeded from an absolute source. For FusedCancoder this is a NO-OP by design
   *  (Phoenix fuses on-device) and describe() says so. For SparkAbsolute / DioAbsolute /
   *  RemoteCancoder it performs the guarded re-seed of §3.10. */
  record AbsoluteSeed(double agreementToleranceUserUnits) implements HomingStrategy {}

  /** Drive slowly into a hard stop; when stator current exceeds `threshold` for `debounce`,
   *  stop and seed. The canonical answer for an elevator with no absolute encoder. */
  record CurrentSpike(Direction direction, double volts, double currentThresholdAmps,
                      double debounceSeconds, double timeoutSeconds,
                      double seedToUserUnits, double backoffUserUnits) implements HomingStrategy {}

  /** Drive until a limit switch / beam break asserts, then seed. */
  record LimitSwitch(Direction direction, double volts, SensorSpec sensor,
                     double timeoutSeconds, double seedToUserUnits) implements HomingStrategy {}

  /** Seed to a constant at boot and hope. Named so it is VISIBLE in a diff, and it always
   *  raises the Tier-2 warning of §5.6. This is what 6 IOs in 0000-XXXX-Robot-Template do
   *  implicitly; PumpkinLib makes it an explicit, audible choice. */
  record AssumeAtBoot(double seedToUserUnits) implements HomingStrategy {}

  /** Try in order; first success wins. e.g. absolute encoder, else current-spike. */
  record Composite(List<HomingStrategy> strategies) implements HomingStrategy {}

  enum Direction { FORWARD, REVERSE }
}
```

Guarantees the library provides for all of them — **this is a mechanism-damage surface, so the guarantees are hard**:

* Homing runs in `MechanismMode.HOMING`; soft limits are **suspended** during homing (you are deliberately driving into a stop) and hard limits are **not**.
* `CurrentSpike.volts` is **magnitude-clamped to 3.0 V** and the stator current limit is **temporarily reduced to `currentThresholdAmps × 1.5`** for the duration of the routine, so a mis-signed direction pushes gently rather than destructively. Both clamps are reported in `describe()`.
* A homing timeout raises `<name>/homing-timed-out` **and leaves `isHomed()` false**, so `atGoal()` stays false and a superstructure interlock can refuse to run. Silent failure is never an option.
* Homing **aborts immediately** on: a device disconnect, a device reset, a follower disagreement, the opposite-direction limit switch asserting, or `DriverStation.isDisabled()`. Every abort names its cause.
* After a successful current-spike home, the mechanism backs off `backoffUserUnits` before seeding, so it is not resting on the stop with kG fighting it.
* `seedPosition` uses the non-blocking `setPosition(value, 0.0)` form. Always.
* Homing while enabled only. `homeCommand()` is not `ignoringDisable`.
* Homing **refuses to start in SAFE_MODE**.
* Sim: `CurrentSpike` works in simulation because `ElevatorSim`/`SingleJointedArmSim` report a current spike at their travel limits, so the routine is testable off-robot. This is the entire reason homing lives in the library rather than in robot code.

Telemetry: `/Pumpkin/<name>/Homing/{Active,Strategy,ElapsedSec,TriggerValue,Succeeded,AbortReason}`.

### 6.4 Continuous / over-360 axes, and the field-locked turret

`8793-2026-Robot/.../ShooterSubsystem.java:349-369` contains a correct, subtle turret unwrap: track the last commanded angle, take the shortest delta across the ±180° discontinuity, and if the unwrapped target would exceed a physical limit, wrap 360° to the other side of the >360° travel range, then clamp. That is universal for any azimuth with more than one turn of travel, and it is exactly what a team writes at 2 am and never revisits.

PumpkinLib owns it:

```java
/** Applied inside setGoal() when RotaryAxis has travel > 360 deg. Stateless conversions,
 *  one piece of state (the last commanded angle), unit-testable with no HAL. */
final class ContinuousUnwrap {
  double unwrap(double requestedDeg, double lastCommandedDeg, double minDeg, double maxDeg);
}
```

**The chassis-omega counter-rotation feedforward**, which the same file computes by hand, is why `MotorIO.setPositionGoal` has a velocity parameter at all. When `RotaryAxis.turret(...).fieldLocked(gyro)` is set:

```java
/** Output-shaft velocity the turret must run at to stay field-locked while the chassis spins.
 *  Called from applyClosedLoop() for the ON_MOTOR_* branches; folded into the SI profile's
 *  velocity for the RIO_* branches. */
private double fieldLockVelocityUserPerSec() {
  if (m_fieldLockGyro.isEmpty()) return 0.0;
  double omegaRadPerSec = m_gyroInputs.yawVelocityRadPerSec;
  return -Math.toDegrees(omegaRadPerSec);     // deg/s at the joint; MechanismUnits does the rest
}
```

* **Phoenix**: reaches the device as `PositionVoltage.withVelocity(...)` / `MotionMagicVoltage.withVelocity(...)` through `setPositionGoal(rot, rps, arbFf)`. Revision 1's seam had no velocity parameter, so this feature was **unimplementable as designed** — while the same document called out `TurretIONeo` silently dropping it as a defect PumpkinLib fixes.
* **REV**: `SparkMotorIO.capabilities().positionGoalVelocity()` is `false`, so `PositionMechanism` converts the term to an equivalent voltage trim (`kV_si × ω_si` volts) and adds it through the arbitrary-feedforward path if available, or through an RIO voltage trim if not. **It is never silently dropped**, and `describe()` says which path is in use. Vendor parity here is a library test (`FieldLockedTurretParityTest`), not a comment.

### 6.5 `VelocityMechanism`

Flywheel, roller under closed-loop velocity, indexer with a speed target.

```java
public final class VelocityMechanism extends Mechanism {
  public VelocityMechanism(VelocityConfig config);

  public double goal();                       // user units/s (deg/s at the output, or m/s)
  public double measured();
  public void setGoal(AngularVelocity v);
  public void setGoal(double userPerSecond);
  public void setNeutral();
  public void setVoltage(double volts);

  /** Two-sided: within tolerance AND settled (|accel| small) AND debounced. A flywheel that
   *  is passing through its setpoint on the way up is not ready to shoot. */
  public boolean atGoal();
  public Trigger atGoalTrigger();
  /** Recovery tracking: how long since the last dip below tolerance. Directly useful for
   *  "wait for spin-up after a shot". */
  public double secondsAtGoal();

  public Command runAt(double userPerSecond);
  public Command runAt(Setpoint s);
  public Command spinUpAndWait(double userPerSecond);
  public Command neutral();
}
```

Backend mapping: `ON_MOTOR_PROFILED` → Phoenix `MotionMagicVelocityVoltage` / REV `kMAXMotionVelocityControl`; `ON_MOTOR_DIRECT` → `VelocityVoltage` / `kVelocity`; `RIO_FULL` → `PIDController` + `Controllers.feedforward(NONE, ...)`, **in SI**, identical to §6.2. Sim plant: `FlywheelSim` built from `LinearSystemId.createFlywheelSystem(gearbox, moi, gearing)`.

Signal subscription for a `VelocityMechanism` excludes position by default (velocity + applied volts + stator + supply + temperature only) — the CAN saving Team 4738's `TelemetryPreference.NO_ENCODER` was meant to deliver but never selected. `MotorInputs.positionRot` is therefore **`NaN`** on such a mechanism, and `describe()` lists it under "signals NOT read".

### 6.6 `SimpleMechanism`

Open-loop rollers, intakes, indexers, feeders — anything commanded as a percentage.

```java
public final class SimpleMechanism extends Mechanism {
  public SimpleMechanism(SimpleConfig config);

  public void set(double dutyCycle);
  public void setVoltage(double volts);
  public void stop();

  /** Stop-on-end is STRUCTURAL. 9143-A had to hand-append `.handleInterrupt(coral::stopIntake)`
   *  to three separate commands with the comment "Never leave rollers running on interrupt";
   *  9143-B's KitBot got it right with startEnd(...) and PumpkinLib makes that the only shape. */
  public Command run(double dutyCycle);
  public Command runFor(double dutyCycle, Time duration);
  public Command runUntil(double dutyCycle, BooleanSupplier stop);
  /** LEVEL signal, not an edge -- so "run until held" works even if the piece was already
   *  present when the command started (the exact 5-line workaround 9143-A needed). */
  public Command intakeUntilHeld(double dutyCycle);
  public Command outtakeFor(double dutyCycle, Time duration);

  public Trigger holding();          // debounced level from the configured SensorSpec
  public Trigger stalled();          // stator current above threshold for a debounce window
}
```

`SimpleConfig` optionally carries a `SensorSpec` (beam break, CANrange, or a stator-current threshold) that becomes `holding()`. Sim plant: `DCMotorSim`.

### 6.7 Simulation — no second code path

```java
@Override public void simulationPeriodic() {
  m_plant.setInputVoltage(m_io.simulatedMotorVoltage());   // from TalonFXSimState / SparkSim
  m_plant.update(Clock.dt());
  m_io.updateSimulatedSensors(
      m_units.toOutputRotations(m_plant.positionUserUnits()),
      m_units.toOutputRps(m_plant.velocityUserUnitsPerSec()));
}
```

The plant is chosen from the `Axis` and built from `SimConfig` — the team writes neither:

| Axis | Plant | Built from |
|---|---|---|
| `LinearAxis` | `ElevatorSim` | `DCMotor` (from `MotorSpec` model × count), `reduction.rotorPerOutput()`, `sim.carriageMass()`, effective drum radius incl. cascade stages, soft limits, `sim.startingPosition()`, `simulateGravity` |
| `RotaryAxis` with gravity | `SingleJointedArmSim` | same, plus `sim.armLength()` and `sim.momentOfInertia()` (or `SimConfig.estimateArmMoi`) |
| `RotaryAxis` no gravity | `DCMotorSim` | `LinearSystemId.createDCMotorSystem(gearbox, moi, gearing)` |
| `VelocityConfig` | `FlywheelSim` | `LinearSystemId.createFlywheelSystem(gearbox, moi, gearing)` |
| `SimpleConfig` | `DCMotorSim` | as above |

The cascade trick from `9143-2025-A-Updated/.../Elevator.java` (model a 2-stage cascade as an *effective* drum radius so `ElevatorSim` sees carriage motion) is applied by `LinearAxis` automatically: `effectiveRadius = pitchRadius * stages`.

Crucially, the Phoenix and REV backends simulate through their **own** vendor sim state, so the simulation exercises the real `SensorToMechanismRatio` / conversion-factor path. A unit-conversion mistake shows up in `simulateJava` rather than on the field. This is why `SimMotorIO` is *not* how the vendor backends simulate.

---


## 7. Brief item 4 — Subsystem base and command factories

### 7.1 How a subsystem is declared

There is no `PumpkinSubsystem` for a team to extend. A mechanism **is** the subsystem:

```java
public class RobotContainer {
  private final PositionMechanism m_elevator = new PositionMechanism(RobotConfig.ELEVATOR);
  private final PositionMechanism m_arm      = new PositionMechanism(RobotConfig.ARM);
  private final SimpleMechanism   m_intake   = new SimpleMechanism(RobotConfig.INTAKE);

  public RobotContainer() {
    Pumpkin.registry().addAll(m_elevator, m_arm, m_intake);   // ONE list, see below
  }
}
```

`Pumpkin.registry()` replaces the seven-place edit that `0000-XXXX-Robot-Template/README.md:438-447` documents as the workflow for adding a mechanism. Registered mechanisms automatically get:

* **collected config validation, the global CAN-ID scan, the setpoint-name check, and SAFE_MODE entry** (§5.6) — this is the only place any of that happens
* `periodic()` and `simulationPeriodic()` fan-out (or CommandScheduler registration, if `registerWithScheduler()` was called)
* stop-on-disable (replacing the hand-maintained `disabledInit()` lists in `9143-*/RobotContainer.java:369-374` and `0000-XXXX/RobotContainer.java:450-459`)
* a **verified** full-config re-apply on `disabledInit()` — the safe place for the blocking path
* telemetry publication under `/Pumpkin/<name>/...`
* alert registration
* tunable registration (the explicit allowlist of §1.2)
* config snapshot logging
* inclusion in `SystemCheck`
* inclusion in the `describe()` boot dump

Adding a mechanism is now: write a config, construct it, add it to the registry. Three lines, one file.

**Backend selection is one call, not a nested ternary.** `0000-XXXX-Robot-Template/RobotContainer.java:154-171` has nine copies of a 3-way nested ternary. `MotorIOFactory` owns it once:

```java
package org.pumpkinlib.hardware;

public final class MotorIOFactory {
  /** REAL/SIM/REPLAY x backend, decided in ONE place from the MotorSpec sealed hierarchy. */
  public static MotorIO create(MotorSpec spec, MechanismUnits units, ControlConfig control,
                               MechanismKind kind, RobotMode mode) {
    if (mode == RobotMode.REPLAY) return new NoOpMotorIO(spec.name());
    if (spec instanceof MotorSpec.TalonFXSpec s)  return new TalonFXMotorIO(s, units, control, kind);
    if (spec instanceof MotorSpec.TalonFXSSpec s) return new TalonFXSMotorIO(s, units, control, kind);
    if (spec instanceof MotorSpec.SparkSpec s)    return new SparkMotorIO(s, units, control, kind);
    if (spec instanceof MotorSpec.GenericSpec s)  return new GenericMotorIO(s, units, control, kind);
    if (spec instanceof MotorSpec.SimSpec s)      return new SimMotorIO(s, units, control, kind);
    return new NoOpMotorIO(spec.name());   // degrade, never crash: SAFE_MODE already has the error
  }
}
```

`MotorSpec` is a **sealed** interface (Java 17 ✔). In 2027 (Java 25) this becomes an exhaustive pattern-matching `switch` with no `default`, so adding a vendor becomes a compile-error-driven checklist. Noted as a planned 2027 improvement; the Java 17 form above ships first.

Sim/real selection is *not* a separate `MotorSpec`. `MotorSpec.talonFX(20, "rio")` in simulation produces a `TalonFXMotorIO` whose sim path is driven by `TalonFXSimState` — so the NEO-has-no-sim gap in `0000-XXXX-Robot-Template` cannot recur.

### 7.2 The command-factory idiom

Every mechanism exposes **command factories, not raw setters**, as the primary surface — the shape `9143-2025-B-Updated/.../KitBot.java` gets right and that 254's `ServoMotorSubsystem` uses. Raw setters still exist (state-based teams need them) but the factories are what the docs show.

```java
// PositionMechanism
public Command goTo(Setpoint s) {
  if (!s.isResolved() || safeMode()) return refuse(s);          // never a silent no-op
  return runOnce(() -> setGoal(s)).withName(m_name + ".goTo(" + s.name() + ")");
}
public Command goToAndWait(Setpoint s) {
  return goTo(s).andThen(Commands.waitUntil(this::atGoal))
                .withName(m_name + ".goToAndWait(" + s.name() + ")");
}
public Command hold() {
  return runOnce(this::holdPosition).andThen(Commands.idle(this)).withName(m_name + ".hold");
}
public Command manual(DoubleSupplier stick) {
  return run(() -> manualControl(stick.getAsDouble())).withName(m_name + ".manual");
}
public Command neutral() {
  return startEnd(this::setNeutral, () -> {}).withName(m_name + ".neutral");
}

/** Unknown setpoint / SAFE_MODE: a NAMED no-op plus a sticky error. Never an exception
 *  (that kills the command and possibly the scheduler), never a quiet nothing. */
private Command refuse(Setpoint s) {
  return Commands.none()
      .beforeStarting(() -> Pumpkin.alerts().error(m_name, unknownSetpointMessage(s)).set(true))
      .withName(m_name + ".goTo(" + s.name() + ")[REFUSED]");
}
```

Three rules the library enforces:

1. **Every factory returns a fresh instance.** WPILib forbids reusing a composed command; `0000-XXXX-Robot-Template/RobotContainer.java:322-443` works around this by storing auto *names* and rebuilding. PumpkinLib never hands out a cached `Command`.
2. **Every factory sets `.withName(...)`.** Commands v3 (2027) gives full scheduler visibility; named commands make that visibility useful today in AdvantageScope and Elastic.
3. **Every factory that starts motion is safe to interrupt.** `SimpleMechanism` factories are `startEnd`/`runEnd` with the stop in the end handler; there is no way to construct one that leaves a roller running.

**Requirements.** A factory built with `run`/`runOnce`/`startEnd` from the mechanism automatically requires it. `Superstructure` (§8) declares the union of its mechanisms' requirements explicitly, so a superstructure command and a direct mechanism command cannot fight.

### 7.3 Default commands

```java
m_elevator.setDefaultCommand(m_elevator.hold());
m_arm.setDefaultCommand(m_arm.hold());
m_intake.setDefaultCommand(m_intake.run(0.0));
```

`hold()` latches the *current* position on first execute and holds it. It is deliberately a different verb from `goTo(ELEVATOR_STOW)`, because `8793-2026-Robot` named the snap-to-zero behavior `maintainStateCommand()` and documented it in `RobotContainer.java:153-154` as "re-apply current state" — it does the opposite, and slams the turret to 0 whenever a mode command ends.

For a state-based team that does not register with the scheduler, the same guarantee comes from the default-output inversion in §8.5, which is stronger.

### 7.4 SysId and characterization

Every mechanism ships SysId for free, from the same config:

```java
public Command sysIdQuasistatic(SysIdRoutine.Direction d);
public Command sysIdDynamic(SysIdRoutine.Direction d);
```

The routine's ramp rate, step voltage, and timeout default from `PositionLimits` and are overridable. SysId **refuses to run** in SAFE_MODE, while disabled, when the mechanism is unhomed, or when `DriverStation.isFMSAttached()`. It aborts on a soft-limit approach, a stall, a device reset, or a follower disagreement — a characterization routine that drives a geared arm into a hard stop at step voltage is a broken gearbox.

In `OutputMode.TORQUE_CURRENT` (v0.2), PumpkinLib emits the amps-not-volts caveat that `reefscape2025/.../Elevator.java:180` handles with the comment "Gaslight SysId since motor is actually running amps instead of volts" — the library states it in `describe()` and in the log rather than requiring the team to know.

Four SysId wrapper methods copy-pasted into three subsystems in `reefscape2025` become zero lines.

---

## 8. Brief item 5 — Superstructure and state machine

### 8.1 Shape

The strongest patterns in the survey are 4738's *orthogonal* sub-states (an arm state + a climb state + a claw state composed into a `SuperState`, which is what keeps 50 states tractable), 9143-A's *deferred planning from live state* with measured-state gates instead of timeouts, and `0000-XXXX`'s *on-entry latch*. PumpkinLib composes all three and fixes the failure mode all three share.

```java
package org.pumpkinlib.superstructure;

/** A team's state enum implements this. The enum is the team's; the machinery is ours. */
public interface SuperState {
  String name();
  /** The goals this state asserts. Anything NOT listed falls back to the mechanism's
   *  declared default -- see the inversion in §8.5. */
  Map<Mechanism, AxisGoal> goals();
  /** Optional: let a downstream mechanism start early instead of waiting for full arrival.
   *  4738's interrupt-supplier idea, generalized. */
  default Map<Mechanism, BooleanSupplier> earlyRelease() { return Map.of(); }
}

public sealed interface AxisGoal {
  record Position(double userUnits) implements AxisGoal {}
  /** TYPED and compiler-checked. THE documented default. */
  record Of(Setpoint setpoint) implements AxisGoal {}
  /** String escape hatch. Validated at construction (§8.9); can never fail silently. */
  record Named(String setpoint) implements AxisGoal {}
  record Velocity(double userPerSecond) implements AxisGoal {}
  record Percent(double dutyCycle) implements AxisGoal {}
  record Neutral() implements AxisGoal {}
  record Hold() implements AxisGoal {}

  static AxisGoal of(Setpoint s)     { return new Of(s); }
  static AxisGoal named(String s)    { return new Named(s); }
  static AxisGoal percent(double d)  { return new Percent(d); }
  static AxisGoal neutral()          { return new Neutral(); }
}
```

```java
public final class Superstructure<S extends Enum<S> & SuperState> {

  public Superstructure(Class<S> stateType, S idleState, SafetyModel safety,
                        List<Interlock<S>> interlocks, Mechanism... mechanisms);

  // ---- requests -------------------------------------------------------
  /** Request a state. Returns a FRESH command every call. Safe as a NamedCommand. */
  public Command request(S state);
  /** Fire-and-forget for state-based teams: sets the requested state; periodic() does the rest. */
  public void setRequested(S state);

  public S requested();      // what the driver asked for
  public S active();         // what we are actually executing (may be a transition waypoint)
  public S previous();
  public boolean isTransitioning();
  public boolean atState();  // active == requested AND every asserted goal reports atGoal

  public Trigger at(S state);
  public Trigger transitioning();

  // ---- planning cost, for Auto ---------------------------------------
  /** Measured when transition_costs.json exists; a conservative profile bound otherwise.
   *  plannedTransitionSource() reports which, so AutoStep.budget() is never a silent guess. */
  public double plannedTransitionSeconds(S from, S to);
  public CostSource plannedTransitionSource(S from, S to);   // MEASURED | PROFILE_BOUND

  // ---- static analysis and characterization (§8.8) --------------------
  public SuperstructureReport report();
  public Command characterizeTransitions();

  // ---- lifecycle ------------------------------------------------------
  public void periodic();
  /** Re-arms the on-entry latch so entry actions fire again on the next enable.
   *  Call from disabledInit. (0000-XXXX-Robot-Template needs this for the climber
   *  hold-position capture; it is easy to forget, so the registry calls it for you.) */
  public void resetStateEntry();
}
```

### 8.2 The transition model

Three concepts, kept separate on purpose:

* **Requested state** — what the operator or auto asked for. Changes instantly.
* **Active state** — what is being executed *right now*. May be an intermediate waypoint inserted by the planner.
* **Transition** — the ordered list of waypoints from active to requested, recomputed **from live measured state** whenever the request changes.

```java
public void periodic() {
  boolean requestChanged = m_requested != m_lastRequested;
  m_lastRequested = m_requested;

  // Plan from LIVE state, not from where we think we are. This is 9143-A's
  // Commands.defer(() -> planMove(...)) insight, made unconditional.
  if (requestChanged || m_plan.isEmpty() || m_plan.isInvalidatedBy(measuredConfiguration())) {
    Optional<Interlock<S>> blocked = firstBlockingInterlock(m_active, m_requested);
    if (blocked.isPresent()) {
      PumpkinLog.put(kBlocked, blocked.get().describe());
      m_blockedAlert.set(true);
      return;                              // hold the current state; do NOT half-execute
    }
    m_blockedAlert.set(false);
    m_plan = m_planner.plan(measuredConfiguration(), m_requested);
    PumpkinLog.put(kPlan, m_plan.waypointNames());
  }

  // Advance on MEASURED arrival (or early-release), never on a timer.
  if (m_plan.currentWaypointSatisfied(this)) m_plan.advance();
  m_active = m_plan.currentState();

  boolean onEntry = m_active != m_previousActive;
  m_previousActive = m_active;

  applyGoals(m_active, onEntry);
}
```

**Why measured-state gates, never timeouts:** `9143-2025-A-Updated/Superstructure.java` uses `Commands.waitUntil(() -> elevator.getCurrentPosition() >= handoff)` throughout, and its ascending escape re-evaluates its ceiling every loop so the elevator target ratchets up as the arm swings — avoiding stop-and-go stutter. A time-based version of the same sequence is both slower and unsafe when the mechanism is loaded or cold. PumpkinLib has **no** time-based waypoint gate. A waypoint may carry a *timeout*, but a timeout only raises `<name>/transition-timed-out` and holds; it never advances.

### 8.3 Interlocks

```java
/** A hard rule about which transitions are permitted. Evaluated BEFORE planning. */
public record Interlock<S extends Enum<S>>(
    String name,
    Predicate<S> fromMatches,
    Predicate<S> toMatches,
    BooleanSupplier permitted,
    String explanation) {

  public String describe();   // "climb-lockout: STOW -> CLIMB_FINAL blocked because
                              //  'coral claw is holding a piece'"
}
```

```java
List.of(
  new Interlock<>("no-climb-with-piece",
      from -> true, to -> to == CLIMB_DEPLOY || to == CLIMB_FINAL,
      () -> !coralClaw.holding().getAsBoolean(),
      "the coral claw is holding a game piece; eject before climbing"),

  new Interlock<>("no-score-until-homed",
      from -> true, to -> to.name().startsWith("SCORE"),
      () -> elevator.isHomed() && arm.isHomed(),
      "the elevator or arm has not homed yet; run the home routine")
)
```

A blocked request is **visible, not silent**: the request is retained (so releasing the interlock completes the move), an alert names the interlock and its `explanation`, and `/Pumpkin/Superstructure/Blocked` carries the string.

Interlocks are also the documented answer for constraints that `SafetyModel`'s two-axis rectangles cannot express — see §13 OQ #7.

### 8.4 Collision avoidance: declarative forbidden zones

Robot-specific geometry, generic machinery. The team declares *forbidden regions of the two-axis configuration space*, not a branch tree.

```java
package org.pumpkinlib.superstructure;

/**
 * Collision avoidance over a 2-axis configuration space (typically elevator height x arm angle).
 * Zones are axis-aligned rectangles in USER UNITS. The planner routes around them.
 * Replaces the ~250-line hand-written branch tree in
 * 9143-2025-A-Updated/src/main/java/frc/robot/Superstructure.java
 * (planMove / escapeCurrentPose / travelAndFinish / avoidClimb / transitionWrist).
 */
public final class SafetyModel {
  public static Builder over(PositionMechanism axisA, PositionMechanism axisB);

  public interface Builder {
    /** The arm cannot swing through the chassis while the elevator is low. */
    Builder forbid(String name, Range axisA, Range axisB, String why);
    /** A region that may only be entered while a condition holds. */
    Builder forbidUnless(String name, Range a, Range b, BooleanSupplier ok, String why);
    /** Preferred travel corridors, used to pick waypoints (e.g. "carry the arm at 90 deg"). */
    Builder corridor(String name, Range axisA, double axisBValue);
    /** Time-scale the faster axis so both arrive together; see "Why the bounding box" below.
     *  Defaults to TRUE for any axis pair covered by a SafetyModel. */
    Builder synchronizedAxes(boolean on);
    SafetyModel build();
  }

  /** Is this exact (a, b) pair legal right now? */
  public boolean isSafe(double a, double b);
  /** Which zone does it violate, and why? Used verbatim in the alert text. */
  public Optional<Zone> violated(double a, double b);
  /** The core routine: a waypoint list from (a0,b0) to (a1,b1) that never enters a zone. */
  public List<double[]> route(double a0, double b0, double a1, double b1);
}
```

```java
static final SafetyModel SAFETY = SafetyModel.over(ELEVATOR, ARM)
    .forbid("arm-through-chassis",
            Range.of(Inches.of(0), Inches.of(9)),      // elevator low
            Range.of(Degrees.of(-15), Degrees.of(40)), // arm swung out/down
            "the arm hits the chassis crossbar below 9 in")
    .forbid("arm-through-funnel",
            Range.of(Inches.of(22), Inches.of(34)),
            Range.of(Degrees.of(60), Degrees.of(105)),
            "the arm hits the coral funnel between 22 and 34 in")
    .corridor("travel-tucked", Range.of(Inches.of(0), Inches.of(55)), /* arm at */ 95.0)
    .build();
```

**Why the bounding box, not the segment.** Revision 1's step 1 asked whether *"the straight line from start to goal"* entered a forbidden zone. That validates a path the robot never takes. The elevator and the arm run **independent** motion profiles with different velocities, accelerations and loads; the actual traversal through (elevator, arm) configuration space is whichever axis finishes first followed by the other — an **L-shaped** path that can pass straight through a rectangle the diagonal misses. That is precisely the geometry the feature exists to prevent, and the failure mode is a destroyed arm on a robot whose logs say the transition was legal.

**Routing algorithm** (deliberately simple, deterministic, and unit-testable with no HAL):

1. Compute the **axis-aligned bounding box** of the segment — `Box.of(a0, b0, a1, b1)` — which is exactly the set of configurations reachable by two unsynchronized profiles, and test `box.intersectsAny(zones)`. If it intersects nothing, the plan is `[goal]`.
2. Otherwise, for each zone the box overlaps, generate the four "escape corners" of that zone expanded by a margin; discard corners that lie inside any zone or outside soft limits.
3. Prefer the corner that (a) moves the *gravity-loaded* axis least, then (b) minimizes total normalized travel. For an elevator×arm pair that means "swing the arm out of the way at the current height" is preferred to "raise the elevator with the arm out".
4. If a declared `corridor` covers the required axis-A span, insert its axis-B value as a waypoint instead of a corner — this reproduces 9143-A's "carry it tucked" behavior declaratively.
5. Each waypoint hop is re-tested with the same bounding-box rule, so the guarantee is inductive over the whole plan, not just the first leg.
6. Emit the waypoint list. Cap at 4 waypoints; if no route is found, refuse the move and raise:

```
[PumpkinLib][ERROR] Superstructure: no safe route from (Elevator 6.2 in, Arm 12.0 deg) to
  (Elevator 52.5 in, Arm 35.0 deg). Blocking zones: arm-through-chassis
  ("the arm hits the chassis crossbar below 9 in").
  The CURRENT position is already inside a forbidden zone -- most likely the mechanism was
  moved by hand while disabled, or homing seeded a wrong value.
  Recovery: run Superstructure.escapeCommand(), or home both axes.
```

7. `escapeCommand()` handles the "we booted inside a forbidden zone" case by moving *only* the axis that most quickly exits, at reduced speed, with an operator confirmation trigger.

**`synchronizedAxes` — recovering the diagonal.** The bounding box is conservative by construction, and on some geometries it forbids transitions that are physically fine. `synchronizedAxes(true)` — the **default** for any pair covered by a `SafetyModel` — time-scales the faster axis's `MotionConstraints` by `min(1, tFast / tSlow)` at waypoint dispatch, so both axes arrive together and the true path *is* the diagonal. `route()` then tests the segment rather than the box for synchronized pairs, restoring the tighter bound with the physics to back it up.

`synchronizedAxes(false)` disables the collision guarantee. It is legal, it is logged, and it raises a **persistent** alert for as long as it is set:

```
[PumpkinLib][WARN] Superstructure: synchronizedAxes(false) is set on the (Elevator, Arm) pair.
  Collision avoidance is now tested against the straight line between configurations, but the
  two axes run independent profiles and the real path is L-shaped. A forbidden zone that the
  diagonal misses can still be entered. This alert stays up until synchronizedAxes(true).
```

The planner also re-evaluates a moving ceiling each loop for the ascending case, exactly as 9143-A does, so a route does not stutter: waypoint targets for the leading axis are recomputed from the trailing axis's live position rather than pinned at plan time.

### 8.5 The default-output inversion (the bug class this kills)

`0000-XXXX-Robot-Template/Superstructure.java:130-246` requires every `case` to remember to reset every actuator. The comments prove the cost: *"Don't leave rollers running at whatever the previous state set"* (MANUAL), *"entering from AIM/SHOOT must spin the flywheels down"* (EJECT), and an `AIM` case that silently forgets `intake.retract()`.

PumpkinLib inverts it:

```java
private void applyGoals(S state, boolean onEntry) {
  // 1. EVERY registered mechanism gets its declared default, every loop, unconditionally.
  for (int i = 0; i < m_mechanisms.length; i++) m_defaults[i].applyTo(m_mechanisms[i]);

  // 2. The active state overrides ONLY what it names.
  state.goals().forEach((mech, goal) -> goal.applyTo(mech));

  // 3. On-entry actions fire once per entry (the 0000-XXXX latch, kept).
  if (onEntry) m_onEntry.getOrDefault(state, NO_OP).run();
}
```

Forgetting to stop a roller becomes structurally impossible, and each state's declaration shrinks to its actual intent. Defaults are declared once:

```java
new Superstructure.Builder<>(SuperState.class, IDLE)
    .defaultFor(elevator, AxisGoal.of(RobotConfig.ELEVATOR_STOW))
    .defaultFor(arm,      AxisGoal.of(RobotConfig.ARM_STOW))
    .defaultFor(intake,   AxisGoal.percent(0.0))
    .defaultFor(flywheel, AxisGoal.neutral())
    ...
```

### 8.6 Early release

4738's interrupt suppliers solve "don't wait for the elevator to finish before spinning the intake". Generalized:

```java
SCORE_L4(Map.of(
    ELEVATOR, AxisGoal.of(RobotConfig.ELEVATOR_L4),
    ARM,      AxisGoal.of(RobotConfig.ARM_SCORE),
    ROLLER,   AxisGoal.percent(0.6)),
  // the roller may start as soon as the elevator is above 40 in, not when it ARRIVES
  Map.of(ROLLER, () -> ELEVATOR.measured() >= Units.inchesToMeters(40)))
```

A mechanism with an `earlyRelease` predicate is commanded as soon as the predicate is true, in parallel with the rest of the transition. The waypoint gate still requires the *position* axes to arrive.

### 8.7 Telemetry

`/Pumpkin/Superstructure/{Requested, Active, Previous, Transitioning, AtState, Plan[], Blocked, BlockedReason, SafeZoneViolation, TimeInStateSec, PlannedSeconds, PlannedSource, Synchronized}`.

`Plan[]` in particular turns "why is the arm moving there first?" from a code-reading exercise into a dashboard glance — which is the difference between a CSA helping you and a CSA giving up. All keys are precomputed strings, as in §6.1.

### 8.8 Static analysis and measured transition costs

Revision 1 omitted the specific mechanisms the dossier ranks as the **#1 elite differentiator**, and nowhere stated the resulting ceiling. 254 measures transition costs on the real robot and persists them (`transition_costs.txt`, `buildCharacterizationCommand()`), auto-generates edges from `allowedNextStates()`, uses gateway states with restricted exits, and precomputes all-pairs routes; 6328 runs BFS over an explicit `DefaultDirectedGraph`. PumpkinLib substituted a greedy corner-escape heuristic capped at four waypoints with **no cost model at all**, so `AutoStep.budget()` and `plannedTransitionSeconds()` were guesses.

Two members close the gap that can be closed, and §13 states the part that cannot.

**(a) `report()` — pure math, no HAL, called automatically at construction and printed in the boot dump.** 0.3 person-weeks.

```java
package org.pumpkinlib.superstructure;

/** A static analysis of the declared state machine. Computed at construction from the states,
 *  the interlocks, the SafetyModel and the mechanisms' soft limits. No hardware, no motion. */
public record SuperstructureReport(
    /** States no sequence of legal transitions can reach from idle. Usually a typo or a
     *  permanently-false interlock. */
    List<String> unreachableStates,
    /** States from which idle is NOT reachable. A robot that can enter one of these is a
     *  robot that cannot stow -- the single most expensive superstructure bug there is. */
    List<String> statesWithNoPathToIdle,
    /** Declared transitions whose bounding box (or diagonal, when synchronizedAxes) crosses a
     *  forbidden zone, with the zone named. These are the moves the router must detour. */
    List<String> transitionsCrossingAZone,
    /** Transitions the router cannot solve within the 4-waypoint cap. These will REFUSE at
     *  runtime, and you want to know that in the shop, not in a match. */
    List<String> unroutableTransitions,
    /** Mechanisms with no declared default -- the §8.5 inversion cannot protect them. */
    List<String> axesWithNoDeclaredDefault,
    /** Setpoint names referenced by a state that its mechanism does not declare (§8.9). */
    List<String> unresolvedSetpointReferences) {

  public boolean clean();
  public String describe();          // printed verbatim in the boot dump
}
```

Any non-empty `statesWithNoPathToIdle`, `unroutableTransitions`, or `unresolvedSetpointReferences` is a **FATAL** `ConfigError` and puts the robot in SAFE_MODE (§5.6). The rest are Tier-2 alerts.

**(b) `characterizeTransitions()` — measured costs. v0.2, 0.5 person-weeks, and heavily safety-gated.**

```java
/**
 * Drives every declared state pair once, times it, and writes
 * /home/lvuser/pumpkin/transition_costs.json. Read back at boot by
 * plannedTransitionSeconds() and by AutoStep.budget() defaults.
 *
 * This routine moves a real, geared, gravity-loaded superstructure through every legal
 * transition on the robot. It is therefore gated harder than anything else in the library.
 */
public Command characterizeTransitions();
```

Non-negotiable safety gates, every one of which aborts the routine and names itself:

1. **Refuses to start** unless: enabled, not FMS-attached, `SuperstructureReport.clean()`, every axis `isHomed()`, no active `kError` alert on any participating mechanism, and not in SAFE_MODE.
2. **Requires a held operator button** for the entire run. Release = abort + neutral. There is no unattended mode.
3. **One pair at a time**, in a deterministic order, with a full return to idle between pairs. Never two transitions in flight.
4. **The router is not bypassed.** Every measured transition is the routed, zone-respecting plan — the same one a match would run. A pair the router refuses is recorded as `UNROUTABLE`, not forced.
5. **Constraints are scaled to 60 %** of the configured maxima for the whole run. A characterization pass is not the place to discover your accel limit.
6. **Aborts immediately** on: any mechanism stall, any stator current above 80 % of its limit for >100 ms, any soft-limit clamp, any device disconnect or reset, any follower disagreement, any `SafetyModel` violation of the *measured* configuration, or a per-pair timeout of `3 × plannedTransitionSeconds`.
7. **On abort**: all mechanisms neutral, the partial results are still written (marked `PARTIAL`), and a sticky alert names the pair and the abort cause.
8. **The written file records provenance**: robot serial, PumpkinLib version, config hash, date, and the constraint scale used. A `transition_costs.json` whose config hash does not match the running config is **ignored** with a warning, never silently trusted — a re-geared mechanism must be re-characterized.

`plannedTransitionSource()` reports `MEASURED` or `PROFILE_BOUND` so an auto routine's timing budget is never a silent guess.

### 8.9 Setpoint names cannot fail at button-press time

`AxisGoal.named("L4")`, `elevator.goTo("L4")` and `.setpoint("L2", ...)` form a string-keyed namespace. Revision 1 specified no validation anywhere, so a typo — `"L4 "`, `"l4"`, `"SCORE"` on a mechanism that declares `"Score"` — produced a failure at the moment a button was pressed, which for `SuperState.L4` is mid-match, and no document said whether that failure was an exception (kills the command, possibly the scheduler), a silent no-op (worst case: the elevator simply does not move and nobody knows why), or an alert. That is the 11-p.m.-before-a-competition failure, on the single most-typed identifier in the API.

Three layers, in order of preference:

**1. The typed form is the documented default.** `PositionConfig.setpoint(String)` returns a `Setpoint` handle a team holds as a `public static final` (§5.4), so `AxisGoal.of(RobotConfig.ELEVATOR_L4)` is checked by the compiler. Every example in this document and in the README uses it. The string form is the escape hatch, not the norm.

**2. Every string is validated at construction, with a suggestion.** `Superstructure.Builder.build()` and `PumpkinRegistry.addAll(...)` resolve every `AxisGoal.named(s)` in every `SuperState`, and every unresolved `Setpoint`, against the declaring mechanism's `List<Setpoint>`, and collect one FATAL `ConfigError` per miss (all of them, at once, into SAFE_MODE):

```
org.pumpkinlib.config.ConfigError [FATAL]: PumpkinLib unresolved setpoint

  SuperState.L4 references Elevator setpoint "L4 " which does not exist.
  Elevator declares: STOW, L1, L2, L3, L4.
  Did you mean "L4"?   (edit distance 1 -- a trailing space)

  Prefer the typed form, which the compiler checks:
      public static final Setpoint ELEVATOR_L4 = RobotConfig.ELEVATOR.setpoint("L4");
      ... AxisGoal.of(RobotConfig.ELEVATOR_L4)

  declared at frc.robot.SuperState.<clinit>(SuperState.java:19)
```

The "did you mean" is a Levenshtein match over the declared names, offered when the distance is ≤ 2.

**3. The runtime path is incapable of failing silently.** Even if a name somehow reaches runtime unvalidated:
* `Mechanism.setpoint(String)` returns `Optional<Setpoint>` — the caller cannot ignore the miss by accident.
* `goTo(String)` with an unknown name returns a **named** `Commands.none()` (`"Elevator.goTo(SCORE)[REFUSED]"`, visible in the scheduler and in AdvantageScope) **and** raises a sticky `kError`:

```
[PumpkinLib][ERROR] Elevator: goTo("SCORE") -- no such setpoint. Elevator declares:
  STOW, L1, L2, L3, L4. The command did nothing and the mechanism is holding position.
  (Did you mean a setpoint on Arm? Arm declares: STOW, INTAKE, SCORE.)
```

Note the last line: the checker searches *sibling* mechanisms too, because "I put the arm's setpoint name on the elevator" is the most common form of this mistake.

---


## 9. End-to-end: a complete two-mechanism scoring robot

Everything a team writes. Elevator + arm + roller, collision avoidance, driver bindings, autonomous hooks, simulation, live tuning. No IO files, no sim code, no telemetry code, no alert code, no unit conversions.

**Every snippet in this section is extracted from a compiled, executed test** (`ExampleRobotCompilesTest`, `ExampleRobotSimTest`, `DescribeSnapshotTest`, `AllocationTest`). That is what turns §9.3's `andThen` bug — which compiled, and did the wrong thing — from a shipped defect into a CI failure.

### 9.1 `RobotConfig.java` — 3 mechanism configs + safety + typed setpoints (one file)

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import org.pumpkinlib.config.*;
import org.pumpkinlib.units.*;
import org.pumpkinlib.hardware.ControlLocation;
import org.pumpkinlib.mechanism.HomingStrategy;
import org.pumpkinlib.superstructure.SafetyModel;

public final class RobotConfig {

  public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
      // .foc(true) = FOC on the voltage requests. Gains stay VOLTS-per-SI.
      .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                        .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
      .reduction(Reduction.ofStages(3.0, 4.0))                  // 12:1 -> 2.26 m/s free
      .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))        // 0.280293 m per drum rot
      .feedback(new FeedbackSpec.RotorOnly())
      .softLimits(Inches.of(0.0), Inches.of(55.0))
      .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
      // ControlLocation omitted -> defaults to ON_MOTOR_PROFILED (TalonFX leader), and
      // describe() prints that with its provenance.
      // Gains: kP V/m, kD V/(m/s), kS V, kV V/(m/s), kA V/(m/s^2), kG V.
      .gains(Gains.realOrSim(
          Gains.pid(80.0, 0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.15),
          Gains.pid(150.0, 0, 0).withKv(5.00).withKa(0.06).withKg(0.15)))
      .constraints(MotionConstraints.of(1.6, 6.0))              // 71% of free speed -- no alert
      .tolerance(Inches.of(0.5), 0.05, 0.06)
      .manualControl(0.10, 0.30)
      .homing(HomingStrategy.currentSpike()
          .direction(HomingStrategy.Direction.REVERSE).voltage(Volts.of(-1.5))
          .currentThreshold(Amps.of(30)).debounce(Seconds.of(0.15))
          .timeout(Seconds.of(4.0)).seedTo(Inches.of(0.0)))
      .setpoint("STOW", Inches.of(0)).setpoint("L2", Inches.of(20.5))
      .setpoint("L3", Inches.of(37.5)).setpoint("L4", Inches.of(52.5))
      .sim(Pounds.of(24.0), Inches.of(0.0))
      .build();

  public static final PositionConfig ARM = PositionConfig.rotary("Arm")
      .motors(MotorGroup.leader(MotorSpec.talonFX(22, "rio").inverted(true)))
      .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))   // 34.126:1
      .axis(RotaryAxis.arm(Degrees.of(0.0)))    // |horizontalAt| <= 90 deg, Phoenix requirement
      .feedback(new FeedbackSpec.FusedCancoder(23, "rio", Rotations.of(-0.1387), 34.126, 1.0))
      .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
      .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
      // Gains: kP V/rad, kD V/(rad/s), kV V/(rad/s), kA V/(rad/s^2), kS and kG volts.
      .gains(Gains.realOrSim(
          Gains.pid(9.5, 0, 0.35).withKs(0.20).withKv(0.65).withKa(0.02).withKg(0.56),
          Gains.pid(20.0, 0, 0).withKv(0.65).withKa(0.02).withKg(0.56)))
      .constraints(MotionConstraints.of(180.0, 540.0))          // deg/s, deg/s^2
      .tolerance(Degrees.of(1.5), 5.0, 0.06)
      .manualControl(0.10, 0.20)
      .homing(HomingStrategy.absoluteSeed())
      .setpoint("STOW", Degrees.of(95)).setpoint("INTAKE", Degrees.of(-10))
      .setpoint("SCORE", Degrees.of(35))
      .sim(SimConfig.arm(Inches.of(21.0), Pounds.of(9.5), Degrees.of(95)))
      .build();

  public static final SimpleConfig ROLLER = SimpleConfig.of("Roller")
      .motors(MotorGroup.leader(MotorSpec.spark(24, SparkModel.MAX_NEO550)))
      .reduction(Reduction.of(4.0))
      .currentLimits(CurrentLimits.of(Amps.of(30), Amps.of(20)))
      .heldSensor(SensorSpec.canRange(25, "rio", Meters.of(0.06)))
      .sim(KilogramSquareMeters.of(0.001))
      .build();

  // ---- typed setpoint handles: compiler-checked, and the documented default ----
  public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");
  public static final Setpoint ELEVATOR_L2   = ELEVATOR.setpoint("L2");
  public static final Setpoint ELEVATOR_L3   = ELEVATOR.setpoint("L3");
  public static final Setpoint ELEVATOR_L4   = ELEVATOR.setpoint("L4");
  public static final Setpoint ARM_STOW      = ARM.setpoint("STOW");
  public static final Setpoint ARM_INTAKE    = ARM.setpoint("INTAKE");
  public static final Setpoint ARM_SCORE     = ARM.setpoint("SCORE");

  public static final SafetyModel SAFETY = SafetyModel.over(ELEVATOR, ARM)
      .forbid("arm-through-chassis",
              Range.of(Inches.of(0), Inches.of(9)), Range.of(Degrees.of(-15), Degrees.of(40)),
              "the arm hits the chassis crossbar below 9 in")
      .corridor("travel-tucked", Range.of(Inches.of(0), Inches.of(55)), 95.0)
      .synchronizedAxes(true)          // default; stated here so the choice is visible
      .build();

  private RobotConfig() {}
}
```

### 9.2 `SuperState.java` — the state machine (one file)

```java
package frc.robot;

import java.util.Map;
import org.pumpkinlib.superstructure.*;
import org.pumpkinlib.mechanism.Mechanism;
import static frc.robot.RobotContainer.*;   // ELEVATOR, ARM, ROLLER
import static frc.robot.RobotConfig.*;      // typed Setpoint handles

public enum SuperState implements org.pumpkinlib.superstructure.SuperState {

  IDLE  (Map.of()),                                  // everything falls back to its default
  INTAKE(Map.of(ELEVATOR, AxisGoal.of(ELEVATOR_STOW),
                ARM,      AxisGoal.of(ARM_INTAKE),
                ROLLER,   AxisGoal.percent(0.8))),
  HOLD  (Map.of(ARM,    AxisGoal.of(ARM_STOW),
                ROLLER, AxisGoal.percent(0.05))),    // light hold current
  L2    (score(ELEVATOR_L2)), L3(score(ELEVATOR_L3)), L4(score(ELEVATOR_L4)),
  EJECT (Map.of(ROLLER, AxisGoal.percent(-1.0)));

  /** Typed, so a level that does not exist is a COMPILE error, not a mid-match no-op. */
  private static Map<Mechanism, AxisGoal> score(Setpoint level) {
    return Map.of(ELEVATOR, AxisGoal.of(level), ARM, AxisGoal.of(ARM_SCORE));
  }

  private final Map<Mechanism, AxisGoal> m_goals;
  SuperState(Map<Mechanism, AxisGoal> goals) { m_goals = goals; }
  @Override public Map<Mechanism, AxisGoal> goals() { return m_goals; }
}
```

### 9.3 `RobotContainer.java` — construction and bindings (one file)

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;              // REQUIRED -- see getAutonomousCommand
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.pumpkinlib.Pumpkin;
import org.pumpkinlib.mechanism.*;
import org.pumpkinlib.hardware.phoenix.TalonFXMotorIO;
import org.pumpkinlib.superstructure.Superstructure;
import org.pumpkinlib.superstructure.AxisGoal;

public class RobotContainer {

  public static final PositionMechanism ELEVATOR = new PositionMechanism(RobotConfig.ELEVATOR);
  public static final PositionMechanism ARM      = new PositionMechanism(RobotConfig.ARM);
  public static final SimpleMechanism   ROLLER   = new SimpleMechanism(RobotConfig.ROLLER);

  private final Superstructure<SuperState> m_super =
      new Superstructure.Builder<>(SuperState.class, SuperState.IDLE)
          .safety(RobotConfig.SAFETY)
          .mechanisms(ELEVATOR, ARM, ROLLER)
          .defaultFor(ELEVATOR, AxisGoal.of(RobotConfig.ELEVATOR_STOW))
          .defaultFor(ARM,      AxisGoal.of(RobotConfig.ARM_STOW))
          .defaultFor(ROLLER,   AxisGoal.percent(0.0))
          .interlock("no-score-until-homed",
                     from -> true, to -> to != SuperState.IDLE,
                     () -> ELEVATOR.isHomed() && ARM.isHomed(),
                     "the elevator has not homed yet; press Start to home")
          .build();     // validates every setpoint reference and runs report() -- §8.8, §8.9

  private final CommandXboxController m_driver = new CommandXboxController(0);

  public RobotContainer() {
    // The ONE place validation, the CAN-ID scan, setpoint-name resolution, and SAFE_MODE live.
    Pumpkin.registry().addAll(ELEVATOR, ARM, ROLLER, m_super);

    m_driver.start().onTrue(ELEVATOR.homeCommand());
    m_driver.leftBumper().whileTrue(m_super.request(SuperState.INTAKE))
                         .onFalse(m_super.request(SuperState.HOLD));
    m_driver.a().onTrue(m_super.request(SuperState.L2));
    m_driver.b().onTrue(m_super.request(SuperState.L3));
    m_driver.y().onTrue(m_super.request(SuperState.L4));
    m_driver.rightBumper().whileTrue(m_super.request(SuperState.EJECT))
                          .onFalse(m_super.request(SuperState.IDLE));
    m_driver.x().onTrue(m_super.request(SuperState.IDLE));

    // Rumble when a piece is held. Level signal, so it works even if the piece was
    // already there when the command started.
    ROLLER.holding().onTrue(rumble(0.4, 0.25));

    // Escape hatch, on page 1: anything PumpkinLib does not model, do on the real device.
    ELEVATOR.io().as(TalonFXMotorIO.class)
        .ifPresent(io -> io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false));
  }

  public Command getAutonomousCommand() {
    return ELEVATOR.homeCommand()
        .andThen(m_super.request(SuperState.L4))
        // Commands.waitUntil(...), NOT `.andThen(trigger::getAsBoolean)`.
        // A BooleanSupplier method reference is assignable to Runnable, so the latter silently
        // binds to Command.andThen(Runnable, Subsystem...): the trigger is evaluated exactly
        // once, the answer is DISCARDED, and the step finishes in the same loop -- ejecting
        // before the elevator has moved. It compiles. It is wrong. It shipped in revision 1,
        // and it is now in the compiled-snippet test set as a direct counter-example to
        // "every documentation snippet is extracted from a compiled, executed test".
        .andThen(Commands.waitUntil(m_super.at(SuperState.L4).and(ELEVATOR.atGoalTrigger())))
        .andThen(m_super.request(SuperState.EJECT).withTimeout(0.5))
        .andThen(m_super.request(SuperState.IDLE));
  }
}
```

### 9.4 `Robot.java` — unchanged WPILib

```java
public class Robot extends TimedRobot {          // or LoggedRobot; PumpkinLib does not care
  private final RobotContainer m_container = new RobotContainer();

  @Override public void robotPeriodic()      { CommandScheduler.getInstance().run(); }
  @Override public void simulationPeriodic() { /* nothing: the registry handles it */ }
  @Override public void disabledInit()       { Pumpkin.registry().onDisable(); }
}
```

`Pumpkin.registry().onDisable()` is where the **verified**, blocking, full-config re-apply happens (§3.9) — the robot is disabled, the loop budget is irrelevant, and it guarantees that anything a mid-match device reset or a live-tuning `applyFast` frame loss left inconsistent is restored before the next enable.

### 9.5 What the team did **not** write

No `ElevatorIO` / `ElevatorIOTalonFX` / `ElevatorIONeo` / `ElevatorIOSim`; no `ArmIO`×4; no `RollerIO`×4 (12 files). No `TalonFXConfiguration` blocks. No `StatusSignal` caching or `optimizeBusUtilization`. No unit conversions. No SI conversions. No soft-limit derivation. No `simulationPeriodic()`. No `ElevatorSim` / `SingleJointedArmSim` construction. No `SmartDashboard`/`Logger.recordOutput` calls. No `Alert` declarations. No tunable plumbing. No SysId wrappers. No `disabledInit()` stop list. No collision-avoidance branch tree. No manual-control deadband code. No homing routine. No `atGoal` implementation. No `hasResetOccurred` handling. No `GravityArmPositionOffset` sign reasoning.

Approximate line count for an equivalent robot in the user's existing style: **~2,400 lines across 22 files**. Above: **~165 lines across 3 files**.

And it runs, with physics, in `./gradlew simulateJava` before the robot exists — including the current-spike homing routine, because `ElevatorSim` produces a real current spike at its travel limit.

### 9.6 What a student changes to tune it

Nothing in code. `/Tuning/Elevator/{kP,kI,kD,kS,kV,kA,kG,maxVelocity,maxAcceleration,jerk,tolerance,velocityTolerance,goalDebounceSeconds,manualDeadband,manualScale}` and `/Tuning/Elevator/Setpoints/{STOW,L2,L3,L4}` are live in AdvantageScope/Elastic whenever `Pumpkin.TUNING_MODE` is true — the explicit allowlist of §1.2, nothing more. `/Tuning/Elevator/reduction` does **not** exist, on purpose.

Changes are pushed to the controller on the next loop via `MotorIO.applyGains`, which is the **non-blocking** `applyFast` path (zero-timeout `apply`, no read-back, no retry) and is budgeted at 1.0 ms and traced. With `TUNING_MODE` false, every one of those reads compiles down to a constant and publishes nothing.

---

## 10. WPILib 2027 migration plan

The 2026 season is over and 2027 is a hard break (`edu.wpi.first.*` → `org.wpilib.*`, Java 25, SystemCore, NT3 removed, Commands v3, no Shuffleboard/SmartDashboard). The offseason build window is now, and everything above is designed so the migration is mechanical.

1. **Two release lines from day one, one source tree.** `pumpkinlib-2026` (`frcYear: "2026"`) and `pumpkinlib-2027` (`wpilibYear: "2027_alphaN"`), built from the same sources with a package-rewrite step. WPILib imports are already narrow: `Measure`/units, `Rotation2d`, `MathUtil`, `TrapezoidProfile`, the three feedforward classes (reached only through `Controllers.Feedforward`), `PIDController`, the four physics sims, `Alert`, `DriverStation`, `Timer`, `Subsystem`/`Command`/`Trigger`/`Commands`.
2. **`Mechanism` is already the noun.** Commands v3 uses "mechanism" for the exclusively-owned resource, and our `Mechanism` deliberately implements the `Subsystem` *interface* only. The v3 facade is one adapter class; nothing above it changes.
3. **Commands v2 is the target; v3 is an adapter.** v2 survives into 2027. The coroutine `yield()` footgun is real and disproportionately hurts the target audience. CORE ships v2 first and a `commands3` adapter artifact second.
4. **Nothing removed in 2027 is used.** No Relay/AnalogOutput/SPI/DMA/Counter/Ultrasonic/AnalogTrigger/interrupts/Servo, no NT3, no Shuffleboard/SmartDashboard, no `MutableMeasure`, no `robotInit()`. The one exposure is `DutyCycleEncoder` for `FeedbackSpec.DioAbsolute` — **[UNVERIFIED]** whether it survives the Counter removal; if not, `DioAbsolute` becomes 2026-only and the config builder says so.
5. **`Math.clamp` / `MathUtil.clamp`** — CORE uses an internal `PumpkinMath.clamp` (Java 17 has no `Math.clamp`, and `MathUtil.clamp` is renamed in 2027). One-line seam, already used in §6.2.
6. **`calculateWithVelocities` is the 2026-and-forward form.** The deprecated `calculate(position, velocity, acceleration, dt)` overloads are not used anywhere, so the 2027 removal is a no-op for us. `Controllers.Feedforward` is the single point of contact.
7. **SystemCore has multiple CAN buses**, which makes `canBus` a first-class field on every `MotorSpec` today rather than a 2027 retrofit. It also makes `m_dynamicCapable`'s bus test (§6.2) forward-compatible.
8. **WPILib 2027 ships first-party `Tunable` and `Telemetry` APIs.** The Tuning and Telemetry domains must be designed to *re-point* at those, not compete. CORE only touches them through `Tunable` / `PumpkinLog` / `PumpkinInputs`, so CORE is unaffected either way — and `PumpkinInputs` (rather than `LoggableInputs`) is what makes that true.

---

## 11. Testing strategy (release-blocking)

1. **Pure-math tests, no HAL.** `Reduction`, `Axis`, `SiDomain`, `MechanismUnits` (including `toSi`/`fromSi`/`siPerOutputRotation`), `ContinuousUnwrap`, `SafetyModel.route`, `SuperstructureReport`, `Superstructure` planning, `atGoal` predicate logic, config validation. These run in CI in milliseconds and cover the entire bug class the user's repos actually hit.
2. **Round-trip unit tests.** For a grid of reductions and geometries: `toUser(toOutputRotations(x)) == x` and `fromSi(toSi(x)) == x`. The 41-inline-conversion bug class dies here.
3. **`UnitsContractTest`.** Asserts the §4.1 table: that a rotary `Gains.kP` of 1.0 V/rad becomes `Slot0.kP == 2π`, that a linear `kP` of 1.0 V/m becomes `Slot0.kP == metersPerOutputRotation`, and that `kS`/`kG` are unconverted. This is the test that would have caught revision 1's 57.3× `RIO_FULL` error.
4. **`RioFullIsSiTest`.** Runs a `RIO_FULL` rotary mechanism in sim with gains in V/rad and asserts it settles within tolerance. Runs the same mechanism with revision 1's user-unit loop and asserts it does **not** — the regression net for the specific defect.
5. **`ArmHoldsStationTest`.** In sim, with `GravityMode.COSINE` and a nonzero `horizontalAt`, the arm holds station at **both +45° and −45°** with `kP = 0`. A sign error in `GravityArmPositionOffset` fails at one of the two angles; revision 1's sign error would fail this test.
6. **`GravityOffsetRangeTest`.** `RotaryAxis.arm(Degrees.of(95))` on a Phoenix backend produces a FATAL `ConfigError` naming ±0.25 rot.
7. **`SignalSubscriptionTest`.** Calls `configureSignals(SIMPLE, Tier.COMPETITION)` and asserts `inputs.supplyCurrentAmps` is a real number, `inputs.positionRot` is **`NaN`** (not `0.0`), and `inputs.closedLoopReferenceRot` is `NaN`. Plus a reflective boot assertion that every field a `Sink` writes is a declared `SignalSet` channel.
8. **`DeviceResetRecoveryTest`.** In sim, force `hasResetOccurred()`, assert the config is re-applied, `deviceResetCount` increments, the setpoint is re-sent within one loop, and an alert is raised.
9. **`SetpointHeartbeatTest`.** With an unchanged goal, assert `setControl` is called at least once per 100 ms and at most once per 100 ms.
10. **`ApplyGainsIsNonBlockingTest`** + the ArchUnit rule of §3.9 + a `PumpkinTracer` budget assertion that `Mechanism/ApplyGains` stays under 1.0 ms at 10 Hz write-through.
11. **Vendor parity tests.** The same `PositionConfig` on `TalonFXMotorIO`, `SparkMotorIO`, `GenericMotorIO`, and `SimMotorIO`, run through the same profile in simulation, must land within the same tolerance. Includes `FieldLockedTurretParityTest`: the chassis-omega feedforward must be applied on **every** backend, by whichever mechanism that backend supports. The NEO path being untestable is a named defect in `0000-XXXX-Robot-Template`.
12. **Config-error snapshot tests.** Every message in §5.6 and §8.9 is asserted verbatim. A docs example that no longer compiles, or an error message that regresses, fails CI.
13. **`SafeModeBootsTest`.** A config with a FATAL error must produce a robot that constructs, runs the scheduler, publishes `/Pumpkin/Driver/SafeMode = true` with the full error list, and refuses every command — **not** an `ExceptionInInitializerError`.
14. **`WithCopyDoesNotDoubleRegisterTest`.** `ELEVATOR.withGains(g).withReduction(r)` produces no CAN-ID conflict.
15. **`DescribeSnapshotTest`.** Constructs `RobotConfig.ELEVATOR` and `RobotConfig.ARM`, calls `describe()`, and **string-matches the §4.4 and §3.5.7 blocks verbatim**. This is the assertion Principle 11 promises; revision 1 shipped a `describe()` block that was internally inconsistent by a factor of 2.9 and nothing caught it.
16. **`RouterBoundingBoxTest.` **A zone the diagonal misses but the L-shaped path enters must be detected. Plus `SynchronizedAxesRestoresDiagonalTest`.
17. **Every documentation snippet is extracted from a compiled, executed test.** Non-negotiable, and it now explicitly includes §9.3's `getAutonomousCommand()`: an AI-written docs error (`sin` where `cos` belonged) destroyed a reviewer's trust in a competing library in a single forum post, and revision 1's `.andThen(trigger::getAsBoolean)` was the same class of error — it compiled.
18. **Allocation test — a G2 gate condition.** A loop of the **full §9 example robot** (three mechanisms + superstructure + telemetry) must allocate zero bytes after warmup. Not a synthetic mechanism. Loop overruns were attributed to competing libraries repeatedly in 2026 and the team response was to disable telemetry entirely.
19. **`forkEvery = 1`** in the test harness, because simulated CAN devices reject duplicate IDs within one JVM — a constraint all three user repos already carry.

---

## 12. What we deliberately do NOT do

| We do not | Because it already exists |
|---|---|
| Write a logging framework or a replay system | **AdvantageKit** (replay), **DogLog** (simplicity), **Epilogue** (`@Logged`, in-box). CORE writes through `PumpkinLog` / `PumpkinInputs` with adapters for all three. |
| Write a swerve library | **YAGSL**, **CTRE `SwerveDrivetrain`**, WPILib kinematics/odometry. Drivetrain (doc 02) composes with the Tuner-X-generated `TunerSwerveDrivetrain`; it does not replace it. |
| Write trajectory generation or path following | **PathPlannerLib 2026.1.2**, **Choreo**. Auto (doc 06) wraps only the four things `AutoBuilder` needs from a drivetrain. |
| Write vision pose estimation or an AprilTag pipeline | **PhotonVision**, **Limelight/LimelightHelpers**, WPILib `SwerveDrivePoseEstimator`. |
| Write motion profiling math | WPILib `TrapezoidProfile` / `ExponentialProfile`, Phoenix **Motion Magic** (incl. Expo and Dynamic), REV **MAXMotion**. CORE picks which one runs and where, and never re-derives one. |
| Write PID or feedforward math | WPILib `PIDController`, `TrapezoidProfile`, `ElevatorFeedforward`, `ArmFeedforward`, `SimpleMotorFeedforward`; Phoenix `Slot0Configs`; REVLib `FeedForwardConfig`. `Controllers.Feedforward` is a **dispatcher**, not an implementation — the three WPILib classes have no common supertype, so something has to choose between them, and it is 40 lines. |
| Write physics simulation | WPILib `ElevatorSim` / `SingleJointedArmSim` / `FlywheelSim` / `DCMotorSim`, and **maple-sim** (optional) for game-piece contact. |
| Write system identification | WPILib `SysIdRoutine`. CORE wires it from the same config and adds the safety gates; it does not implement the regression. |
| Write a declarative state-machine framework for 2027 | **WPILib 2027 ships one** on Commands v3. `Superstructure` is a *mechanism coordinator* with interlocks and collision avoidance — a strictly narrower, robot-shaped thing — and it is designed to sit on top of whichever command framework is present. |
| Write a graph-search superstructure planner | 254 and 6328 do this better and we say so in §13 OQ #7. `SafetyModel` is a 2-axis geometric router, not an A* over a weighted digraph. |
| Write a tunable-number framework inside CORE | Tuning (doc 05) owns it, and must re-point at **WPILib 2027's first-party Tunable API** when it lands rather than compete with it. |
| Write a dashboard | **Elastic**, **AdvantageScope**. CORE publishes NT4 structs and ships layout JSONs. |
| Re-model every vendor config knob | **`TalonFXConfiguration`** and **`SparkMaxConfig`** are the vendors' own fluent configs, maintained by the vendors. CORE models only what is physical, shared, or derivable, and passes everything else through `applyRaw()`. |
| Provide a unified control-execution API across on-motor and on-RIO loops | This is the specific mistake the community rejected. CORE shares *config data* and makes the *location* explicit. |
| Force AdvantageKit, `LoggedRobot`, or an IO-layer architecture | CORE works with plain `TimedRobot` + no logging at all, and composes with AdvantageKit when present. |
| Own field constants, alliance flipping, or game logic | Field/Auto domains. The 2027 field origin moves to the center of the field, so this must live behind an abstraction owned by one domain, not scattered. |

---

## 13. Open questions

1. **`TalonFXS` external feedback field names.** The `ExternalFeedback` config *group* is verified; the field names inside `ExternalFeedbackConfigs` (the `SensorToMechanismRatio` / `RotorToSensorRatio` analogues) are **[UNVERIFIED]**. Must be confirmed against the 26.x javadoc before `TalonFXSMotorIO.buildConfig` is written. Similarly, whether `Commutation.MotorArrangement` covers every motor the user's teams might attach.
2. **REVLib arbitrary feedforward and position-goal velocity.** Does REVLib 2026's `setSetpoint` have an overload taking an arbitrary feedforward and `ArbFFUnits`? Is there any analogue of `PositionVoltage.withVelocity()`? **[UNVERIFIED]**. Until confirmed, `SparkMotorIO` reports both capabilities `false` and `PositionMechanism` folds the terms into an RIO-side voltage trim (§6.4) — never drops them. This also decides whether `RIO_PROFILE_MOTOR_LOOP` is fully equivalent on REV.
3. **REVLib fault/connection API.** Exact 2026 method names for active vs sticky faults on `SparkBase`, and whether REV exposes anything equivalent to `hasResetOccurred()`. The latter is the more important one now: without it, the §3.5.5 reset-recovery guarantee is Phoenix-only, and `describe()` must say so on every REV mechanism.
4. **`DutyCycleEncoder` in 2027.** Does it survive the Counter/interrupt removal? If not, `FeedbackSpec.DioAbsolute` (the REV Through Bore path 9143-A depends on) is 2026-only and teams must move to a CANcoder or a SPARK-attached absolute encoder.
5. **SystemCore onboard IMU and Smart IO APIs** do not exist yet. `SystemCoreImuGyroIO` and any Smart IO digital-sensor backend are stubs. Do not guess method names.
6. **`StructGenerator.genRecord` type support** is undocumented. `MechanismConfigSnapshot` is flattened to primitives to avoid the risk; if `genRecord` turns out to support enums and nested records cleanly, the snapshot could become structured. Needs a test.
7. **Superstructure axis count — and an honest statement of the ceiling.**

   > **PumpkinLib's router is 2-axis and geometric, not a cost-weighted graph search.** It
   > routes over axis-aligned rectangles in a two-dimensional configuration space with a
   > greedy corner-escape heuristic capped at four waypoints. It is deterministic, it is
   > unit-testable with no HAL, it covers every mechanism in the user's three repos, and it
   > is strictly **less capable than 254's A\* over a weighted transition graph and 6328's BFS
   > over an explicit `DefaultDirectedGraph`**. We say so rather than implying parity.
   >
   > For robots whose legal-motion structure is **not** expressible as rectangles in two axes —
   > three or more coupled degrees of freedom, diagonal or curved boundaries, gateway states
   > with restricted exits — model the constraint as `Interlock`s (§8.3) and hand-write the
   > intermediate states. `SuperstructureReport` (§8.8) will tell you at construction which
   > transitions the router cannot solve, so the limitation surfaces in the shop rather than
   > in a match.
   >
   > What we *do* close: `report()` gives the reachability analysis 254 and 6328 have
   > (`unreachableStates`, `statesWithNoPathToIdle`, `unroutableTransitions`), and
   > `characterizeTransitions()` gives the measured cost model (`transition_costs.json`), so
   > `AutoStep.budget()` and `plannedTransitionSeconds()` stop being guesses.

   The remaining question is whether a **pairwise decomposition** over three axes (elevator ×
   arm, arm × wrist, elevator × wrist) is sufficient in practice for a 3-DOF superstructure like
   9143-A's CorAl, or whether true 3-D routing is needed. Pairwise is proposed for v0.2; a real
   robot's zone set should decide it before we commit.
8. **Should `Reduction` know about belt/chain slip?** A `Reduction` measured from tooth counts is exact; a real cascade elevator can differ by a few percent. A `calibrationScale` field would let a team correct measured travel without touching the tooth counts — but it is also a place to hide a wrong ratio. Leaning against; wants a decision.
9. **Follower disagreement threshold.** What is a sane default for a two-motor elevator before `<name>/follower-disagrees` fires? 9143-A has a hand-written "elevator sides out of sync" alert; the threshold there is robot-specific. Proposal: default to 2% of total travel with an override, and only alert while enabled.
10. **`GravityArmPositionOffset` availability on REV.** Phoenix 26.x has it, with a verified (−0.25, 0.25) rot range. REVLib's `kCos` is multiplied by the cosine of *absolute mechanism position* with no offset field mentioned in the docs — if there is no offset, the REV arm path requires the zero of the encoder to be at horizontal, or a RIO-side correction. **[UNVERIFIED]**, and it is the one place where the two vendors may not map cleanly onto one `GravityMode`. Note that the Phoenix ±90° limit already forces teams toward "zero at horizontal", so the two paths may converge in practice.
11. **Naming.** `MotorIO` vs `MotorPort` vs `MotorLink`. `MotorIO` matches the AdvantageKit vocabulary 600+ teams already know, which argues for keeping it even though PumpkinLib does not require AdvantageKit — and `MotorInputs implements PumpkinInputs` (not `LoggableInputs`) keeps the vocabulary without the dependency. Confirm before the API freezes.
12. **Does `Setpoint` need to be tunable per-robot?** A setpoint that differs between the practice bot and the comp bot is common. `PositionConfig.withSetpoint(name, value)` covers it, and setpoints are on the §1.2 tunable allowlist, but persisted per-robot tuning (tune on the practice bot, promote to the comp bot) is a Tuning-domain question that affects the `Setpoint` type's shape.
13. **`OutputMode.TORQUE_CURRENT` in v0.2.** Landing it requires an amps-per-SI row in doc 02 §4.2's `GainSink` table, an amps-per-SI mode in `FeedbackDesigner`, a unit tag in `gains.json`, and a migration path for a team that tuned in volts. That is a coherent chunk of work, not a flag flip, and it should be scoped as one.
14. **Signal rate default.** §3.5.1 raises position/velocity to 100 Hz to avoid aliasing against the 50 Hz loop. On a heavily loaded `rio` bus with a swerve drivetrain already subscribed, is 100 Hz the right default, or should it be 100 Hz for mechanisms and 250 Hz for swerve with a documented bus budget? Needs a measurement on 8793's actual bus before the default is frozen.


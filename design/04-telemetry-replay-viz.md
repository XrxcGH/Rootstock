# PumpkinLib Design 04 — Telemetry, Replay, Visualization, Dashboards, Simulation & Test

**Domain owner:** Logging / Match Replay / 3D + Mechanism Visualization / Dashboards / Simulation / Testing & CI
**Target:** WPILib 2026.2.x (`edu.wpi.first.*`), Java 17, GradleRIO 2026.2.1. 2027 (`org.wpilib.*`, SystemCore, Java 25) is an explicit migration target, not a v0.1 deliverable.
**Status:** Design complete. Implementable as written.
**Date:** 2026-08-06

---

## 0. Scope & Responsibilities

This domain owns **everything between "a number exists inside the robot" and "a human understands what the robot did."** Concretely:

| # | Responsibility | Public surface |
|---|---|---|
| 1 | The logging facade — one API, four backends (AdvantageKit / Epilogue+DataLog / DogLog / plain NT4), selected by config | `org.pumpkinlib.telemetry` |
| 2 | The automatic telemetry schema — every mechanism, vision source and drivetrain logs an identical, documented key set with zero user code | `org.pumpkinlib.telemetry.schema` |
| 3 | Replay safety — what user code must and must not do for AdvantageKit replay to stay valid, enforced by a build-time check and a runtime tripwire | `org.pumpkinlib.telemetry.replay` |
| 4 | Alert **transport and schema** (the alert registry itself is owned by Platform/06 per DESIGN.md D10), plus the loop-time tracer and the byte budget | `org.pumpkinlib.telemetry.health` |
| 5 | 3D component-pose generation from a declared kinematic chain, plus the AdvantageScope asset pipeline (`config.json` + `model_N.glb`) | `org.pumpkinlib.viz` |
| 6 | `LoggedMechanism2d` generation from the same declaration — **AdvantageKit backend only** (§4.7) | `org.pumpkinlib.viz` interface + `pumpkinlib-advantagekit` implementation |
| 7 | Log naming, retention, per-match manifests, and the `pullLogs` toolchain | `org.pumpkinlib.logs` + Gradle plugin |
| 8 | Dashboards — a stable, backend-independent NT4 driver mirror plus prebuilt Elastic and AdvantageScope layouts | `org.pumpkinlib.dashboard` |
| 9 | Simulation — sim-by-default physics for every declared mechanism, vendor sim-state wiring, battery sag, optional maple-sim | `org.pumpkinlib.sim` |
| 10 | Testing — JUnit 5 harness, deterministic time stepping, replay regression assertions, and a GitHub Actions workflow | `org.pumpkinlib.test` (separate artifact) |

### Explicitly NOT in this domain

Mechanism control law, gain storage, tunables, homing, hardware config, vision pose math, path following, superstructure state graphs, LED/rumble policy, and the swerve odometry thread. Those belong to other domains. **This domain consumes their state and publishes it; it never computes it.**

### Design stance (non-negotiable, restated for this domain)

1. **We do not write a log format, a viewer, a replay engine, or a dashboard.** WPILOG, AdvantageScope, AdvantageKit and Elastic exist and are excellent.
2. **The escape hatch is always visible.** Every facade exposes the raw vendor object: `PumpkinLog.akit()`, `MechanismVisualizer.mechanism2d()`, `PumpkinSim.raw(...)`.
3. **Failures name themselves.** No silent degradation. If replay is impossible on the chosen backend, the API says so at configure time, not at 2 AM in the pits.
4. **Sim-first.** Every feature in this document works with no robot present, headless, in CI.
5. **50 Hz is mandatory.** AdvantageKit only supports 50 Hz robots and maple-sim forbids timing overrides when AdvantageKit is present ([maple-sim docs](https://shenzhen-robotics-alliance.github.io/maple-sim/using-the-simulated-arena/)). PumpkinLib never changes the loop period and refuses configurations that would.

---

## 1. Integration Points — what this domain needs from the rest of PumpkinLib

These are contracts. If another domain changes them, this domain breaks.

### 1.1 From the **Mechanisms & Control** domain (`org.pumpkinlib.mechanism`)

Every PumpkinLib mechanism must implement `TelemetrySource`. This is the *entire* coupling — this domain does not know what an elevator is.

```java
package org.pumpkinlib.telemetry;

import edu.wpi.first.units.Unit;

/** Implemented by every PumpkinLib mechanism. This is the only thing the telemetry
 *  layer knows about a mechanism. Implementations must be allocation-free per call. */
public interface TelemetrySource {
  /** Stable, unique, path-safe name. Becomes the log key segment: "Pumpkin/<name>/...". */
  String telemetryName();

  /** Called once at registration. The mechanism describes its own schema. */
  void describe(TelemetryDescriptor d);

  /** Called every cycle, after the mechanism's own periodic(). Push values into the sink. */
  void sample(TelemetrySink sink);
}

public interface TelemetryDescriptor {
  /** Declares the natural unit of this mechanism's position axis (Meters, Radians, ...). */
  TelemetryDescriptor positionUnit(Unit unit);
  TelemetryDescriptor velocityUnit(Unit unit);
  /** Number of motors, for the per-motor array fields. */
  TelemetryDescriptor motorCount(int n);
  /** Optional: names for the state enum so the log has a readable string. */
  TelemetryDescriptor states(Class<? extends Enum<?>> stateEnum);
  /** Optional extra scalars this mechanism wants in the standard block. */
  TelemetryDescriptor extra(String key, Unit unit, Tier tier);
}
```

**What I need from Mechanisms:**
- `TelemetrySource` implemented on the mechanism base classes (`PositionMechanism`, `VelocityMechanism`, `RollerMechanism`) so teams inherit it.
- A `MechanismGeometry` value object (gearing, mass/MOI, length, limits, motor `DCMotor`) that I can read to build the sim model **without the mechanism knowing sim exists**.
- A guarantee that gains are readable at any time (`Gains activeGains()`), so I can log them.

### 1.2 From the **Drivetrain** domain (`org.pumpkinlib.drive`)

```java
public interface DriveTelemetry {
  Pose2d pose();                          // fused estimate
  Pose2d odometryOnlyPose();              // no vision — used for the divergence metric
  ChassisSpeeds measuredSpeeds();         // robot relative
  ChassisSpeeds setpointSpeeds();         // robot relative
  SwerveModuleState[] measuredStates();
  SwerveModuleState[] setpointStates();
  SwerveModulePosition[] modulePositions();
  boolean gyroConnected();
  Rotation2d rawGyroYaw();
}
```

### 1.3 From the **Vision** domain (`org.pumpkinlib.vision`)

```java
public interface VisionTelemetry {
  String cameraName();
  boolean connected();
  Transform3d robotToCamera();
  /** One entry per observation this cycle; empty if none. */
  List<VisionObservation> observations();
}

/** Emitted per observation, accepted or rejected. Rejected observations MUST still be
 *  emitted with a reason — "why did vision not correct my pose" is the #1 triage question. */
public record VisionObservation(
    double timestampSeconds,
    Pose3d estimatedPose,
    int[] tagIds,
    double averageTagDistanceMeters,
    double ambiguity,
    boolean accepted,
    String rejectReason,          // "" when accepted
    double[] stdDevs) {}          // length 3: x, y, theta
```

### 1.4 From the **Autonomous / Path** domain (`org.pumpkinlib.auto`)

The path adapter must call these three methods — nothing else. They exist so that the *ghost pose contract* (§4.5) is filled by whichever path library the team chose.

```java
package org.pumpkinlib.viz;

/** The ghost-pose contract. Named FieldGhosts, not PumpkinField, per DESIGN.md D14:
 *  org.pumpkinlib.field.PumpkinField is owned by Drive/Auto (05) and means alliance
 *  flipping + the 2027 field-origin move. This class is only the three ghost channels. */
public final class FieldGhosts {
  public static void setActiveTrajectory(Pose2d[] poses);   // null/empty clears
  public static void setTrajectorySetpoint(Pose2d setpoint);
  public static void setGoal(Pose2d goal);
}
```

Every pose handed to `FieldGhosts` is already in the blue-origin convention produced by `org.pumpkinlib.field.PumpkinField`. This domain does **not** flip poses; it publishes what it is given. That is the whole reason the two types are separate.

### 1.5 From the **Core / Config** domain (`org.pumpkinlib.core`)

- `PumpkinRobot` (the `LoggedRobot`/`TimedRobot` root) must call, in this order:
  `PumpkinLog.beforeUserPeriodic()` → user code → `PumpkinTelemetry.publishAll()` → `PumpkinLog.afterUserPeriodic()`.
- `RobotIdentity` (comp bot / practice bot / sim) so it lands in log metadata and the manifest.
- `MatchContext` (latched alliance, event, match type/number/replay) — used for log naming, the manifest, and the FMS-aware tier gate. **I do not implement this; I consume it.** (Specified in the Competition-Day design.)
- `BuildConstants` from the `com.peterabeles.gversion` Gradle plugin: `GIT_SHA`, `GIT_BRANCH`, `DIRTY`, `BUILD_DATE`, `MAVEN_NAME`.

### 1.6 What I provide back to everyone

- `PumpkinLog` — the only logging call anyone in PumpkinLib makes.
- `PumpkinTracer` — the only profiling call.
- `PumpkinSim.register(...)` — mechanisms hand me a physics model; I own the tick, battery, and gating.
- `PumpkinTest` — the JUnit harness other domains write their tests against.

**What I do NOT provide (DESIGN.md D10):** there is no `PumpkinFaults`. Alerts are owned by Platform (06) at `org.pumpkinlib.core.alert` — `Alerts.error/warning/info(group, text)` returns a `PumpkinAlert` handle, and a sticky fault is `PumpkinAlert.sticky(true)`. This domain **consumes** `AlertRegistry` and publishes the `Pumpkin/Health/**` schema from it (§3.5). One alert facade for the whole library; I am its transport, not its owner.

---

## 2. The Logging Facade

### 2.1 The honest headline

> **Full deterministic replay is only available on the AdvantageKit backend.**
> Epilogue+DataLog, DogLog, and plain NT4 produce excellent logs you can graph, but you cannot re-run the match through your code. This is not a PumpkinLib limitation; AdvantageKit is the only deterministic replay framework in FRC Java, and CTRE Hoot Replay is explicitly non-deterministic (6328's published comparison shows total divergence within seconds of auto, and — worse — "there is no way to distinguish accurate outputs from the inaccurate, diverged outputs"). PumpkinLib prints this at configure time and refuses `mode = REPLAY` on any other backend with a message naming the fix.

### 2.2 Backend selection

```java
package org.pumpkinlib.telemetry;

public enum Backend {
  /** AdvantageKit. Full replay. Requires the AdvantageKit vendordep and LoggedRobot. */
  ADVANTAGEKIT,
  /** WPILib Epilogue annotation logging + DataLogManager. No replay. Zero extra vendordeps. */
  EPILOGUE,
  /** DogLog. No replay. Best raw ergonomics; requires the DogLog vendordep. */
  DOGLOG,
  /** Raw NetworkTables 4 + DataLogManager, no third-party dependency at all. No replay.
   *  This is the kickoff-week fallback: it works before any vendor has published. */
  NT4,
  /** Chosen automatically: ADVANTAGEKIT if org.littletonrobotics.junction.Logger is on the
   *  classpath, else DOGLOG if dev.doglog.DogLog is, else EPILOGUE, else NT4. */
  AUTO
}

public enum RobotMode { REAL, SIM, REPLAY }

public enum Tier {
  /** Always present in the log, including on FMS. Anything you would need to explain a
   *  lost match. CRITICAL keys are never removed from the schema by any mechanism. */
  CRITICAL,
  /** Present unless the FMS tier gate or an explicit Demotable marking applies. The default. */
  STANDARD,
  /** Dropped when FMS-attached. Reference-typed values are supplier-only so the value is
   *  never computed when the tier is off. */
  DEBUG
}

/**
 * ORTHOGONAL to Tier. Marks a key as eligible for rate reduction (never removal) by the
 * byte-budget governor (§2.7). Default is NO for every key in the library and every key a
 * team logs. The governor may not touch a key that is not explicitly marked YES.
 *
 * A demoted key is published every 5th cycle instead of every cycle. It is never deleted,
 * never silently retyped, and every demotion bumps Pumpkin/Log/SchemaGeneration so replay
 * and the triage tooling can see the boundary.
 */
public enum Demotable { NO, YES }
```

**Why `Demotable` is orthogonal to `Tier` and not just "STANDARD may be dropped":** `Inputs/SupplyCurrentAmps` is CRITICAL — a stalled motor is exactly what explains a lost match — but supply current at 10 Hz still explains it, and supply current is one of the fattest `double[]` topics on the robot. Conversely `Setpoint` is STANDARD-adjacent in size and must never gap, because the §5.5 triage workflow reads `Setpoint` against `Measured` frame by frame. Tier answers *"may this key disappear?"*; `Demotable` answers *"may this key be sampled slower under sustained load?"* Those are different questions and collapsing them is what produced the mid-match schema change the reviewer caught.

```java
public final class LogConfig {
  public Backend backend = Backend.AUTO;
  public RobotMode mode = RobotMode.REAL;      // set from RobotBase.isReal() by PumpkinRobot

  public String wpilogFolder = "/U/logs";      // 2027: "/u/logs"
  public String fallbackFolder = "/home/lvuser/logs";
  public boolean compress = false;             // xz .wpilogxz (6328's trick); see §5.3

  public NtPolicy ntPublish = NtPolicy.OFF_ON_FMS;
  public Tier minimumTier = Tier.DEBUG;        // auto-raised to STANDARD on FMS attach
  public double perCycleByteBudget = 6_000;    // governor threshold; see §2.7

  public boolean captureConsole = true;
  public boolean captureDriverStation = true;
  public boolean ctreSignalLogger = false;     // .hoot sidecar; see §2.9
  public boolean urcl = false;                 // REV sidecar; see §2.9

  public int minFreeMegabytes = 200;           // stop writing + raise a fault below this
  public boolean driverMirror = true;          // publish /Pumpkin/Driver/** (see §6)

  public enum NtPolicy { ALWAYS, OFF_ON_FMS, NEVER }
}
```

```java
public final class PumpkinLog {
  private PumpkinLog() {}

  /** Call exactly once, from the PumpkinRobot constructor, BEFORE any hardware is constructed. */
  public static void configure(java.util.function.Consumer<LogConfig> f);

  /** Starts the backend. After this, recordMetadata is closed. */
  public static void start();

  public static Backend activeBackend();
  public static RobotMode mode();
  /** True only on ADVANTAGEKIT. Anything replay-shaped must check this. */
  public static boolean replayCapable();

  /** Write-once provenance. Must be called between configure() and start(). */
  public static void metadata(String key, String value);

  // ---- Escape hatches. Empty when that backend is not active. ----
  public static java.util.Optional<AkitEscape> akit();       // exposes org.littletonrobotics.junction.Logger
  public static java.util.Optional<edu.wpi.first.util.datalog.DataLog> dataLog();
  public static edu.wpi.first.networktables.NetworkTable ntRoot(); // "/Pumpkin"
}
```

### 2.3 The value API

Three tiers × the value types AdvantageScope can actually render. Nothing else.

```java
public final class PumpkinLog {
  // ---------- CRITICAL ----------
  public static void critical(String key, boolean v);
  public static void critical(String key, long v);
  public static void critical(String key, double v);
  public static void critical(String key, double v, Unit unit);
  public static void critical(String key, String v);
  public static void critical(String key, Enum<?> v);
  public static void critical(String key, double[] v, Unit unit);
  public static void critical(String key, boolean[] v);
  public static void critical(String key, long[] v);
  public static void critical(String key, String[] v);
  public static <T> void critical(String key, T v, Struct<T> struct);
  public static <T> void critical(String key, T[] v, Struct<T> struct);
  public static void critical(String key, Measure<?> v);

  // ---------- STANDARD (identical overload set, named log(...)) ----------
  public static void log(String key, double v, Unit unit);
  /* ... full set as above ... */

  // ---------- Demotable variants ----------
  // Every critical(...) and log(...) overload has a trailing-Demotable twin. Omitting the
  // parameter means Demotable.NO. There is no way to make a key demotable by accident, and
  // no way for the governor to demote a key the author did not mark.
  public static void log(String key, double v, Unit unit, Demotable d);
  public static void log(String key, double[] v, Unit unit, Demotable d);
  public static void critical(String key, double[] v, Unit unit, Demotable d);
  public static <T> void log(String key, T[] v, Struct<T> struct, Demotable d);
  /* ... and so on for every overload; the parameter is always last. */

  // ---------- DEBUG ----------
  // Primitives take values (free to evaluate). Reference types take SUPPLIERS so the value
  // is never constructed when the tier is off. This makes the classic AdvantageKit CPU trap
  // -- "the logger statements were instantiating new Translation2ds every cycle" -- impossible.
  public static void debug(String key, boolean v);
  public static void debug(String key, double v);
  public static void debug(String key, double v, Unit unit);
  public static void debug(String key, DoubleSupplier v, Unit unit);
  public static void debug(String key, Supplier<String> v);
  public static <T> void debug(String key, Supplier<T> v, Struct<T> struct);
  public static <T> void debug(String key, Supplier<T[]> v, Struct<T> struct);

  /** Explicit decimation. Wraps AdvantageKit's Logger.runEveryN when available and
   *  implements the same counter otherwise. */
  public static void everyN(int n, Runnable r);
}
```

**Why flat scalars and WPILib structs, not a PumpkinLib struct-per-mechanism:**

1. AdvantageScope's 2026 unit-aware Line Graph works on scalars with unit metadata. A packed custom struct is one opaque blob on that tab.
2. Elastic's struct-field display is **read-only and does not support arrays**, so a struct cannot back a driver widget.
3. AdvantageKit's docs mark first-log-of-a-new-struct/protobuf/record as a **>100 ms blocking hazard** and mandate doing it while disabled. Every custom struct we ship is one more landmine at match start.
4. Records must be globally uniquely named or they collide, and cannot contain arrays.

Geometry is different: `Pose2d`, `Pose3d`, `Transform3d`, `ChassisSpeeds`, `SwerveModuleState`, `Rotation2d` already have WPILib structs that AdvantageScope decodes natively and that carry native unit information. **Use those, always.** The AdvantageScope docs are explicit that struct publishing is the best way to publish geometry, and field-name-suffix inference is a third-choice fallback.

### 2.4 IO inputs and replay

Replay requires that everything read from hardware passes through a logged inputs struct. PumpkinLib ships its own annotation and processor so this works identically with and without AdvantageKit.

```java
package org.pumpkinlib.telemetry;

/** Put on a plain-data class of public non-final fields. The annotation processor generates
 *  <Name>Logged with toLog/fromLog. When org.littletonrobotics.junction is on the compile
 *  classpath, the generated class ALSO implements LoggableInputs, so it drops straight into
 *  AdvantageKit with no adapter. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AutoInputs {}

/** Backend-neutral inputs contract. Generated classes implement this. */
public interface PumpkinInputs {
  void toLog(LogSink sink);
  void fromLog(LogSource source);
}
```

```java
// Usage — identical whether or not AdvantageKit is installed:
public interface ElevatorIO {
  @AutoInputs
  class ElevatorInputs {
    public boolean[] connected = new boolean[2];
    public double positionMeters = 0.0;
    public double velocityMetersPerSec = 0.0;
    public double[] appliedVolts = new double[2];
    public double[] supplyCurrentAmps = new double[2];
    public double[] statorCurrentAmps = new double[2];
    public double[] tempCelsius = new double[2];
    public boolean limitForward = false;
    public boolean limitReverse = false;
  }
  default void updateInputs(ElevatorInputs inputs) {}
  default void setVoltage(double volts) {}
}

// In the subsystem, once per cycle, ALWAYS as the first two statements:
io.updateInputs(inputs);
PumpkinLog.processInputs("Pumpkin/Elevator", inputs);
// After this line, read ONLY from `inputs`. Never call io.getX(). This is the rule that
// makes replay byte-identical (6328's stated invariant).
```

```java
public final class PumpkinLog {
  /** On ADVANTAGEKIT this delegates to Logger.processInputs (inputs are replayed from the log
   *  in REPLAY mode). On every other backend it writes the fields as outputs and returns. */
  public static void processInputs(String key, PumpkinInputs inputs);
}
```

### 2.5 What deterministic replay requires of user code

This is the contract PumpkinLib enforces. Each rule is stated as a rule, its failure mode, and how PumpkinLib catches it.

| # | Rule | If you break it | Enforcement |
|---|---|---|---|
| R1 | Every hardware read goes through an `@AutoInputs` struct via `processInputs`. Subsystems read only from that struct. | Replay silently re-reads nothing and produces wrong outputs. | Build-time: any call to a known vendor getter (`TalonFX.getPosition`, `SparkMax.getEncoder`, `PhotonCamera.getAllUnreadResults`, `LimelightHelpers.*`, `DriverStation.getJoystick*`) outside a class named `*IO*` or annotated `@IoImplementation` is a **build error**. |
| R2 | Time comes from `Timer.getTimestamp()` (AdvantageKit-injected), never `Timer.getFPGATimestamp()` or `Timer.getMonotonicTimestamp()` in control logic. | Replay clock and control clock diverge. Profiling numbers become nonsense during 50× replay. | Build-time error outside IO impls and outside `PumpkinTracer`. PumpkinLib's own tracer deliberately uses the monotonic clock and is the sole exception. |
| R3 | No `Math.random()`, `new Random()`, `System.currentTimeMillis()`, `Instant.now()`, `UUID.randomUUID()`, filesystem reads, or network reads in control logic. | Different outputs on every replay. | Build-time error. Runtime tripwire in `REPLAY` mode raises a fault naming the class. |
| R4 | No iteration over `HashMap` / `HashSet` where iteration order affects output. | Order varies with JVM and hashing; outputs drift. | Build-time **warning** (cannot be proven), plus `PumpkinCollections.ordered()` helpers in the docs. |
| R5 | No user threads that mutate control state. The odometry thread is the one sanctioned exception and it lives inside PumpkinLib's drivetrain IO. | Nondeterministic interleaving. | Build-time error on `new Thread`, `Executors.*`, `Notifier` outside `@IoImplementation`. |
| R6 | No direct NetworkTables reads for values that affect control. Use `PumpkinTunable` (which is backed by `LoggedNetworkNumber` under `/Tuning` on the AdvantageKit backend, and is therefore recorded and replayed). | Tunables read live NT during replay; you replay against today's dashboard, not the match's. | Build-time error on `NetworkTableInstance`/`SmartDashboard` reads outside `org.pumpkinlib`. |
| R7 | Vendor CAN sidecar loggers (CTRE `.hoot`, URCL, REVLib StatusLogger) are **decorative** with respect to replay. They are recorded for convenience only. | Team assumes a signal in the .hoot is available in replay. It is not. | Configuring `ctreSignalLogger`/`urcl` prints a one-line notice at startup, and the manifest marks those files `replayable: false`. |
| R8 | Large hardware libraries that bypass the IO layer (CTRE `SwerveDrivetrain`, YAGSL) cannot be replayed. | Replay is valid for everything except the drivetrain — the most important thing. | `PumpkinLog.configure` raises a **startup Alert** if `com.ctre.phoenix6.swerve.SwerveDrivetrain` or `swervelib.SwerveDrive` is on the classpath while `replayCapable()`. Not an error — a loud, named warning. |
| R9 | `DriverStation.waitForDsConnection()` is incompatible with AdvantageKit. | Robot hangs or replay breaks. | Build-time error, unconditionally. |
| R10 | The simulation GUI must be disabled for replay. | Replay does not run. | `PumpkinLog` sets `HAL`-side sim GUI off in `REPLAY` mode and logs the fact. |

### 2.6 Enforcement implementation

**Build-time (`pumpkinlib-lint`, a javac annotation processor).** Same mechanism `@AutoLog` and Epilogue already use, so it costs no new tooling. It runs on every `./gradlew build`, including CI.

```
> Task :compileJava
e: Drive.java:142: [PUMPKIN-R2] Timer.getFPGATimestamp() outside an IO implementation
      breaks AdvantageKit replay. Use Timer.getTimestamp(), or move this call into a class
      annotated @IoImplementation, or annotate the method @ReplayExempt("profiling only").
e: Vision.java:88: [PUMPKIN-R6] Direct NetworkTableInstance read. Use PumpkinTunable.of(...)
      so the value is recorded to the log and replayed.
w: Arm.java:12:  [PUMPKIN-R4] Iteration over HashMap<String,?> may affect outputs.
      Use LinkedHashMap or PumpkinCollections.ordered().
```

Escape hatches (both required — teams will hit false positives):
- `@ReplayExempt("reason")` on a method or class, with a mandatory non-empty reason string. The reasons are collected into `build/pumpkin/replay-exemptions.txt` so a mentor can review them.
- `pumpkin { lint { replaySafety = ReplayLint.OFF | WARN | ERROR } }` in `build.gradle`. Default is **`ERROR` when the AdvantageKit backend is configured, `OFF` otherwise** — teams not using replay are never nagged about replay.

**Runtime tripwire (`REPLAY` mode only).** Cheap, no `SecurityManager` (removed in modern JVMs). On `Logger.start()` in REPLAY mode, PumpkinLib:
- Seeds a poisoned `java.util.Random` shim it hands out via `PumpkinRandom` and faults if the real one is constructed (detected via a `-javaagent`-free trick: we simply refuse to provide one, and the lint covers direct construction).
- Records `Thread.activeCount()` at start and faults if it grows.
- Asserts `Logger.getTimestamp()` is monotonically non-decreasing per cycle and that exactly one `processInputs` call occurred per registered IO key per cycle. A missing key means an IO layer was skipped; a duplicate means a subsystem is reading hardware twice.

```java
public final class PumpkinReplay {
  /** Installed automatically in REPLAY mode. Public so tests can assert against it. */
  public static void assertDeterministic();
  public static List<String> violations();
}
```

### 2.7 Tiers and the loop-time budget governor

Every documented 2026 AdvantageKit CPU disaster traces to logging *volume*, not logging *design*: Chief Delphi reports of 80–150 ms loops with the stock vision+swerve templates, fixed by deleting log calls, and one team whose actual root cause was leaving `simMode` on. The governor is the feature that lets a small team log aggressively at home without bricking itself at an event.

```java
public final class PumpkinBudget {
  /** Bytes written this cycle, all tiers. */
  public static int lastCycleBytes();
  /** Rolling p95 over the last 250 cycles. */
  public static int p95CycleBytes();
  /** Per-key attribution, sorted descending. Logged once per second at DEBUG. */
  public static List<Map.Entry<String, Integer>> topKeys(int n);

  /** Every key currently demoted to every-5th-cycle, in demotion order. */
  public static List<String> demotedKeys();
  /** Monotonic. Bumped on every demotion AND every restoration. Mirrors
   *  Pumpkin/Log/SchemaGeneration. Starts at 0. */
  public static long schemaGeneration();
  /** Complete, closed set of keys the governor is permitted to touch. */
  public static Set<String> demotableKeys();
}
```

Runtime behavior:

1. `minimumTier` is raised to `STANDARD` automatically on FMS attach. `DEBUG` keys stop being evaluated at all (they are supplier-gated).

2. **Demotion is opt-in, bounded, and versioned.** If `p95CycleBytes() > perCycleByteBudget` for 50 consecutive cycles, PumpkinLib raises `Alerts.warning("Pumpkin/Log", "LOG_BUDGET_EXCEEDED …")` naming the top three keys by byte cost, and then demotes — to every-5th-cycle publication, never to deletion — the **most expensive keys that are explicitly marked `Demotable.YES`**, one at a time, re-measuring for 50 cycles between each step, until p95 is back under budget or the demotable set is exhausted. When the set is exhausted and p95 is still over, the governor **stops demoting and escalates the Alert to error**. It does not go looking for other keys to cut. A team that logged itself into a hole gets told so; it does not get a quietly mutilated log.

   The library marks exactly five key patterns `Demotable.YES`, and this list is the whole of it:

   | Demotable key | Tier | Why it is safe to sample at 10 Hz |
   |---|---|---|
   | `Pumpkin/<Mech>/Inputs/TempCelsius` | STANDARD | Motor thermal mass is seconds-scale; 10 Hz loses nothing |
   | `Pumpkin/<Mech>/Inputs/SupplyCurrentAmps` | CRITICAL | Fat `double[]`; a stall is hundreds of ms wide, not 20 ms |
   | `Pumpkin/Drive/ModuleStates/SetpointOptimized` | STANDARD | Derived from `ModuleStates/Setpoint`, which is never demotable |
   | `Pumpkin/Drive/ModulePositions` | STANDARD | An output mirror; the replayable copy is in the drive IO inputs |
   | `Pumpkin/Vision/*/StdDevs` | STANDARD | Only meaningful on cycles where `Accepted` is true, which is rarer than 50 Hz anyway |

   Everything else in the library — every `Goal`, `Setpoint`, `Measured`, `Error`, `AtGoal`, `State`, every `Pose2d`/`Pose3d`, every `RejectReason`, every `Pumpkin/Perf/**` — is `Demotable.NO` and structurally untouchable. **Nothing under `Pumpkin/Driver/` is ever demotable**, at any tier, under any load: the driver mirror (§6.2) is ~30 low-rate topics that a human is looking at while the robot is moving, and it is exempt from the governor entirely rather than merely unmarked.

3. **Every demotion and every restoration bumps the schema generation.** `Pumpkin/Log/SchemaGeneration` is a `long`, `CRITICAL`, `Demotable.NO`, published every cycle. Alongside it:

   ```
   Pumpkin/Log/SchemaGeneration        long      CRITICAL   monotonic, starts at 0
   Pumpkin/Log/Governor/Demoted        String[]  CRITICAL   currently-demoted keys
   Pumpkin/Log/Governor/LastChangeSec  double    CRITICAL   timestamp of the last generation bump
   Pumpkin/Log/Governor/Reason         String    CRITICAL   "p95=8214B > budget=6000B for 50 cycles"
   ```

   A generation bump is the *only* legal way for the effective schema to change mid-session. Any other schema change is a bug.

4. **Restoration.** When `p95CycleBytes()` stays below 70 % of `perCycleByteBudget` for 500 consecutive cycles (10 s), the governor restores the most recently demoted key, bumps the generation again, and re-measures. Hysteresis is deliberate: a governor that flaps produces a log with dozens of generations and is worse than one that never restores.

5. **Consumers must honor the generation.** `PumpkinReplayVerify` (§5.6) treats a `SchemaGeneration` change as an **expected discontinuity**, not a diff — see §5.6. The match manifest (§5.2) records `schemaGenerations` and every boundary, and the triage workflow (§5.5) prints "the log's sample rate changed at t=…" instead of leaving a student staring at a trace with holes in it.

6. `Pumpkin/Log/BytesPerCycle`, `Pumpkin/Log/QueuedCycles`, `Pumpkin/Log/SchemaGeneration`, and `Pumpkin/Perf/LoopMs` are always `CRITICAL` and always `Demotable.NO`.

7. **Build-time enforcement.** `pumpkinlib-lint` (§2.6) fails the build if any key under `Pumpkin/Driver/` is passed `Demotable.YES`, and `./gradlew logBudget` prints the full demotable set so a team can see, before an event, exactly which traces can gap and which cannot.

Build-time companion:

```
./gradlew logBudget
  Estimated 4.1 kB/cycle, 205 kB/s, ~123 MB per 10-minute session (uncompressed).
  Top static contributors:
    Pumpkin/Vision/Front/AllTagPoses     1,408 B  (Pose3d[22])   DEBUG, dropped on FMS
    Pumpkin/Drive/ModuleStates/Measured    256 B                 not demotable
  Demotable budget headroom (governor may reclaim, worst case):
    Pumpkin/Elevator/Inputs/SupplyCurrentAmps    16 B -> 3.2 B
    Pumpkin/Arm/Inputs/SupplyCurrentAmps         16 B -> 3.2 B
    Pumpkin/Elevator/Inputs/TempCelsius          16 B -> 3.2 B
    Pumpkin/Drive/ModulePositions               128 B -> 25.6 B
    Pumpkin/Drive/ModuleStates/SetpointOptimized 128 B -> 25.6 B
    Pumpkin/Vision/Front/StdDevs                 24 B -> 4.8 B
    TOTAL RECLAIMABLE                           328 B  (8% of cycle)
  WARN Arm.java:71 — allocation inside a STANDARD log call on the hot path.
```

Printing the reclaimable total is the point: 8 % is not a rescue. If a team is 40 % over budget, the honest answer is "delete log calls or move them to DEBUG," and `logBudget` says so before the event instead of the governor pretending mid-match.

### 2.8 Backend mapping table

| PumpkinLib call | ADVANTAGEKIT | EPILOGUE | DOGLOG | NT4 |
|---|---|---|---|---|
| `configure/start` | `Logger.recordMetadata`, `addDataReceiver(new WPILOGWriter(folder))`, `addDataReceiver(new NT4Publisher())`, `setReplaySource(new WPILOGReader(LogFileUtil.findReplayLog()))`, `Logger.start()` | `DataLogManager.start()`, `Epilogue.configure(cfg -> {cfg.backend = new FileBackend(DataLogManager.getLog()); cfg.root = "Pumpkin"; cfg.minimumImportance = ...; cfg.errorHandler = ...;})`, `Epilogue.bind(robot)` | `DogLog.setOptions(new DogLogOptions().withCaptureDs(true).withLogExtras(true).withCaptureConsole(true).withNtTunables(() -> !DriverStation.isFMSAttached()))`, `DogLog.setPdh(pdh)` | `DataLogManager.start()`, `DriverStation.startDataLog(DataLogManager.getLog())`, `NetworkTableInstance.getDefault()` |
| `log(key, double, unit)` | `Logger.recordOutput(key, v, unit)` | `DoubleLogEntry` + NT `DoublePublisher` with `{"unit":"..."}` metadata | `DogLog.log(key, v, unit)` | `DoublePublisher` + `DoubleLogEntry`, metadata `{"unit":"..."}` |
| `log(key, T, Struct<T>)` | `Logger.recordOutput(key, v)` (struct overload) | `StructLogEntry.create(...)` + `StructPublisher` | `DogLog.log(key, v)` | `StructPublisher` + `StructLogEntry` |
| `processInputs` | `Logger.processInputs` (**replayed**) | fields written as outputs | fields written as outputs | fields written as outputs |
| Faults | outputs + WPILib `Alert` (auto-logged by AKit) | outputs + `Alert` | `DogLog.logFault` + `Alert` | outputs + `Alert` |
| Console capture | built-in | `DataLogManager.log` bridge | `withCaptureConsole(true)` | `DataLogManager.log` bridge |
| Replay | **yes** | no | no | no |

Notes:
- The NT4 root differs per backend: AdvantageKit publishes under `/AdvantageKit/RealOutputs/...`; the others under `/Pumpkin/...`. **This is exactly why the driver dashboard reads a separate stable mirror (§6.2), not the log stream.**
- **There is no Mechanism2d on any backend except ADVANTAGEKIT** (§4.7). WPILib's `Mechanism2d` lives in `edu.wpi.first.wpilibj.smartdashboard`, which is deleted in 2027 and is banned by DESIGN.md Principle 9 and by doc 06's `noDeadWpiApis` ArchUnit rule — PumpkinLib cannot reference it and still pass its own architecture test. On EPILOGUE / DOGLOG / NT4, `MechanismVisualizer.mechanism2d()` returns `Optional.empty()` and PumpkinLib records the reason in `Pumpkin/Log/Caveats`. (Epilogue could not have logged it anyway: its docs state `Mechanism2d` "does not implement the Sendable interface in a way that is compatible with annotation logging.")
- `NtPolicy.OFF_ON_FMS` on the AdvantageKit backend installs an `NT4Publisher` wrapper that no-ops when `DriverStation.isFMSAttached()` — 6328's `NoFMSNT4Publisher` pattern. The driver mirror (§6.2) is *not* suppressed; it is ~30 low-rate topics and the drivers need it.
- **Never run two logging libraries.** DogLog's docs are explicit. `PumpkinLog.configure` throws immediately if two of `{AdvantageKit Logger started, DogLog enabled, Epilogue bound}` are active.

### 2.9 Vendor CAN sidecars

```java
cfg.ctreSignalLogger = true;   // SignalLogger.setPath("/U/ctre_logs"); SignalLogger.start()
cfg.urcl = true;               // ADVANTAGEKIT: Logger.registerURCL(URCL.startExternal(aliasMap))
                               // others:       URCL.start(aliasMap)
```
PumpkinLib treats `.hoot` and `.revlog` strictly as **high-rate CAN sidecars**, never as replay engines. The manifest records their paths so `pullLogs` grabs the whole set, and the triage doc tells you to open them *alongside* the `.wpilog` in AdvantageScope (`File > Add New Log(s)...` auto-aligns timestamps). We do not wrap Hoot Replay.

---

## 3. What Gets Logged Automatically — the schema

**Root:** `Pumpkin/`. On the AdvantageKit backend, outputs land under `RealOutputs/Pumpkin/...` (and `ReplayOutputs/Pumpkin/...` during replay); inputs land under `Pumpkin/<Name>/Inputs/...` at the top level. On other backends everything is under `Pumpkin/...`.

Zero user code produces all of this. A mechanism registers itself once (the mechanism base class does it in its constructor) and the schema follows.

### 3.1 Every mechanism — `Pumpkin/<Name>/`

`<Name>` is `TelemetrySource.telemetryName()`. Units below are the *declared* unit of the mechanism (Meters for an elevator, Radians for an arm); the actual unit is attached as entry metadata so AdvantageScope labels and converts correctly.

**Inputs (replayable):**

The **Dem.** column is `Demotable` (§2.2). `YES` means the byte-budget governor may sample this key at 10 Hz instead of 50 Hz under sustained overload, bumping `Pumpkin/Log/SchemaGeneration` when it does. Blank means the governor can never touch it.

| Key | Type | Unit meta | Tier | Dem. |
|---|---|---|---|---|
| `Inputs/Connected` | `boolean[]` (per motor) | — | CRITICAL | |
| `Inputs/Position` | `double` | position unit | CRITICAL | |
| `Inputs/Velocity` | `double` | velocity unit | CRITICAL | |
| `Inputs/AbsolutePosition` | `double` | position unit | STANDARD | |
| `Inputs/AppliedVolts` | `double[]` | volts | CRITICAL | |
| `Inputs/SupplyCurrentAmps` | `double[]` | amps | CRITICAL | **YES** |
| `Inputs/StatorCurrentAmps` | `double[]` | amps | STANDARD | |
| `Inputs/TempCelsius` | `double[]` | celsius | STANDARD | **YES** |
| `Inputs/LimitForward` / `Inputs/LimitReverse` | `boolean` | — | CRITICAL | |
| `Inputs/StickyFaults` | `String[]` | — | STANDARD | |

**Outputs:**

| Key | Type | Unit meta | Tier | Meaning |
|---|---|---|---|---|
| `Goal` | `double` | position | CRITICAL | Where the mechanism has been told to end up |
| `Setpoint` | `double` | position | CRITICAL | This cycle's profile sample |
| `SetpointVelocity` | `double` | velocity | STANDARD | |
| `Measured` | `double` | position | CRITICAL | Mirror of `Inputs/Position`; present so the four ghost traces graph together |
| `Error` | `double` | position | CRITICAL | `Setpoint - Measured` |
| `GoalError` | `double` | position | STANDARD | `Goal - Measured` |
| `Output` | `double` | volts | CRITICAL | Commanded voltage (or equivalent) |
| `AtSetpoint` | `boolean` | — | CRITICAL | |
| `AtGoal` | `boolean` | — | CRITICAL | |
| `State` | `String` | — | CRITICAL | Mechanism's own enum name |
| `ControlMode` | `String` | — | STANDARD | `POSITION`/`VELOCITY`/`VOLTAGE`/`NEUTRAL`/`HOMING` |
| `Homed` | `boolean` | — | CRITICAL | |
| `SoftLimitMin` / `SoftLimitMax` | `double` | position | STANDARD | Logged so a "why won't it move" is one glance |
| `CurrentLimitAmps` | `double` | amps | STANDARD | |
| `Gains/kP kI kD kS kV kA kG` | `double` | — | STANDARD | Logged on change only; makes "which gains were on the robot in match 42" answerable |
| `Sim/Enabled` | `boolean` | — | CRITICAL | **Non-negotiable.** The 2026 "our robot lagged for 5 seconds" root cause was sim code running on the real robot. |

**None of the outputs above are demotable.** `Setpoint`, `Measured`, `Error` and `AtGoal` are exactly the four traces the §5.5 triage workflow reads frame by frame, and they are the traces a student is looking at while learning to tune. A gap in them under load is a gap at precisely the moment the answer is in them.

### 3.2 Drivetrain — `Pumpkin/Drive/`

| Key | Type | Tier |
|---|---|---|
| `Pose` | `Pose2d` struct | CRITICAL |
| `Pose3d` | `Pose3d` struct | STANDARD |
| `OdometryOnlyPose` | `Pose2d` | STANDARD |
| `VisionDivergenceMeters` | `double` (meters) | CRITICAL |
| `ChassisSpeeds/Measured` | `ChassisSpeeds` struct | CRITICAL |
| `ChassisSpeeds/Setpoint` | `ChassisSpeeds` struct | CRITICAL |
| `ModuleStates/Measured` | `SwerveModuleState[]` | CRITICAL |
| `ModuleStates/Setpoint` | `SwerveModuleState[]` | CRITICAL |
| `ModuleStates/SetpointOptimized` | `SwerveModuleState[]` | STANDARD **(Demotable)** |
| `ModulePositions` | `SwerveModulePosition[]` | STANDARD **(Demotable)** |
| `Gyro/Connected`, `Gyro/YawRad`, `Gyro/YawRateRadPerSec` | `boolean`,`double`,`double` | CRITICAL |
| `SpeedMetersPerSec`, `SkidRatio` | `double` | STANDARD |
| Per module `Module<i>/...` | the full §3.1 mechanism block | STANDARD |

### 3.3 Vision — `Pumpkin/Vision/<Camera>/`

| Key | Type | Tier |
|---|---|---|
| `Connected` | `boolean` | CRITICAL |
| `Fps`, `LatencySec` | `double` | CRITICAL |
| `RobotToCamera` | `Transform3d` struct | STANDARD (logged on change) |
| `TagCount` | `long` | CRITICAL |
| `TagIds` | `long[]` | CRITICAL |
| `EstimatedPose` | `Pose3d` struct | CRITICAL |
| `Accepted` | `boolean` | CRITICAL |
| `RejectReason` | `String` | CRITICAL |
| `AmbiguitY` (`Ambiguity`) | `double` | STANDARD |
| `AvgTagDistanceMeters` | `double` | STANDARD |
| `StdDevs` | `double[3]` | STANDARD **(Demotable)** |
| `AllTagPoses` | `Pose3d[]` | **DEBUG** (supplier-gated — this is the single biggest 2026 loop-time offender) |

Roll-ups at `Pumpkin/Vision/`: `AcceptedThisCycle` (long), `UptimeFraction` (double), `AnyCameraOffline` (boolean).

### 3.4 Field / ghost contract — `Pumpkin/Field/`

Fixed key names so AdvantageScope layouts, the replay-diff tool, and the triage manifest can all key off them. This is the contract `FieldGhosts` (§1.4) fills. None of these keys is demotable.

| Key | Type | AdvantageScope object |
|---|---|---|
| `Robot` | `Pose2d` | Robot |
| `RobotGhost` | `Pose2d` | Ghost (trajectory setpoint this cycle) |
| `Goal` | `Pose2d[]` (length 0 or 1) | Ghost #2. Array so "no goal" logs empty, not stale. |
| `Trajectory` | `Pose2d[]` | Trajectory |
| `VisionPoses` | `Pose2d[]` | Vision Target (child of Robot) |
| `VisionTargets` | `Pose3d[]` | Vision Target (3D) |
| `GamePieces/<Type>` | `Pose3d[]` | Game piece |
| `ErrorMeters`, `ErrorDegrees` | `double` | — |

### 3.5 Alerts, health, and the tracer

**This domain does not own alerts.** Per DESIGN.md D10, Platform (06) owns `org.pumpkinlib.core.alert`. Doc 04's former `PumpkinFaults` is deleted; everything in PumpkinLib raises alerts through:

```java
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.PumpkinAlert;

PumpkinAlert a = Alerts.warning("Pumpkin/Log", "LOG_BUDGET_EXCEEDED: p95=8214B > 6000B");
a.sticky(true);        // a sticky fault -- the old PumpkinFaults semantics, one facade
a.set(false);          // clear
```

What **this** domain owns is the *transport and schema*: `AlertRegistry` (06) is polled every cycle and its contents are published as

```
Pumpkin/Health/Active        String[]   CRITICAL   active alert texts, worst-first
Pumpkin/Health/Seen          String[]   CRITICAL   every alert raised this session
Pumpkin/Health/Counts/<name> long       STANDARD   rising-edge count per alert
Pumpkin/Health/Worst         String     CRITICAL   "ERROR" | "WARNING" | "INFO" | "NONE"
```

plus the WPILib `Alert` group `Pumpkin`, which publishes to `/SmartDashboard/Pumpkin` — an NT *path* that survives the 2027 removal of the SmartDashboard *application*. PumpkinLib writes that path directly; it never touches `edu.wpi.first.wpilibj.smartdashboard`.

DogLog's fault semantics, AdvantageKit's transport, one API. Neither library has both today.

```java
public final class PumpkinTracer {
  public static void reset();                              // call at top of loop
  public static void record(String epoch);                 // marks time since last record
  public static AutoCloseable scope(String epoch);         // try-with-resources
  public static void budget(String epoch, Time limit);     // Alerts.warning("Pumpkin/Perf", "PERF_<epoch> ...") on breach
}
```
Emits `Pumpkin/Perf/<epoch>Ms` with `"milliseconds"` unit metadata, plus `Pumpkin/Perf/LoopMs` and `Pumpkin/Perf/OverrunCount`.

**The tracer uses `Timer.getMonotonicTimestamp()` deliberately** — it must report real wall time, not the injected replay clock, or a 50× replay reports absurd loop times. This is the only sanctioned violation of R2 and it is annotated `@ReplayExempt("tracer measures real wall time by design")`.

### 3.6 Provenance metadata (write-once, before `start()`)

`ProjectName`, `BuildDate`, `GitSHA`, `GitBranch`, `GitDirty` (`"All changes committed"` / `"Uncommitted changes"`), `Hostname`, `Platform`, `RobotIdentity`, `PumpkinLibVersion`, `WpilibVersion`, `Backend`, `Mode`, `ReplayCapable`. Straight from 6328's practice, which makes any log traceable to an exact commit.

---

## 4. 3D Match Replay & Mechanism Visualization

This is the headline user request. It is also the thing AdvantageScope's own docs warn is "complex and time-consuming" and steer you away from. PumpkinLib's job is to make it nearly free.

### 4.1 The two contracts that must never drift

1. **Asset contract.** A folder named exactly `Robot_<NAME>` containing `config.json`, `model.glb` (base, **no components**), and `model_0.glb` … `model_N.glb`.
2. **Pose contract.** A `Pose3d[]` published every cycle, **in the same order as `config.json`'s `components[]`**, each element being the robot-relative pose of that component's joint frame.

Every failure mode here is silent. So PumpkinLib generates **both sides from one declaration** and ships a build task that fails if they disagree.

### 4.2 Declaring the kinematic chain

```java
package org.pumpkinlib.viz;

/** The viz joint axis. Named JointAxis, not Axis, per DESIGN.md D4: org.pumpkinlib.units.Axis
 *  is the canonical sealed geometry type, and two types named Axis collide on any file that
 *  imports both packages. */
public enum JointAxis { X, Y, Z }

/** Named ArticulationSpec, not MechanismSpec, per DESIGN.md D25: this is a viz kinematic
 *  chain, and "MechanismSpec" read like a sibling of MotorSpec/SensorSpec/FeedbackSpec in
 *  the mechanism config domain. */
public final class ArticulationSpec {
  public static Builder named(String assetName);      // -> folder "Robot_<assetName>"

  public interface Builder {
    /** A rigid component that never moves relative to its parent (e.g. a bumper shell). */
    Builder fixed(String id, String parentId, Transform3d parentToJointZero);

    /** Translates along `axis` of its own joint frame. The DoubleSupplier is NOT stored;
     *  values are pushed per-cycle through MechanismVisualizer. */
    Builder prismatic(String id, String parentId, Transform3d parentToJointZero,
                      JointAxis axis, Distance travelMin, Distance travelMax);

    /** Rotates about `axis` of its own joint frame. */
    Builder revolute(String id, String parentId, Transform3d parentToJointZero,
                     JointAxis axis, Angle rangeMin, Angle rangeMax);

    /** Fixed camera mount. Emitted into config.json cameras[] AND published as a
     *  field-relative Pose3d for AdvantageScope's Camera Override. */
    Builder camera(String name, Transform3d robotToCamera,
                   int widthPx, int heightPx, double horizontalFovDegrees);

    /** Robot-relative slot where a held game piece renders. Publishes an empty array when absent. */
    Builder gamePieceSlot(String id, String parentId, Transform3d parentToPiece, String pieceType);

    /** Applied to the whole robot model in config.json ("rotations" then "position"). */
    Builder modelRotation(JointAxis axis, double degrees);
    Builder modelOffset(double x, double y, double z);

    ArticulationSpec build();
  }

  public String assetName();
  public int componentCount();
  /** Index order == config.json components[] order == published Pose3d[] order. */
  public List<String> componentOrder();

  /** Forward kinematics: robot-relative joint pose for each component given joint values. */
  public Pose3d[] solve(java.util.Map<String, Double> jointValues);

  /** The Pose3d[] when every joint is at zero. Published as Pumpkin/Viz/ZeroedComponents. */
  public Pose3d[] zeroedPoses();
}
```

**Parent chain root** is the string `"ROBOT"`, whose frame is the published robot pose — which AdvantageScope places at **floor level (z = 0)**, not the bellypan. This is the single most common calibration mistake and it is documented directly in the builder's Javadoc.

### 4.3 Publishing measured / setpoint / goal

```java
public final class MechanismVisualizer {
  public static MechanismVisualizer of(ArticulationSpec spec);      // logs under "Pumpkin/Viz"
  public static MechanismVisualizer of(ArticulationSpec spec, String logRoot);

  public Channel measured();
  public Channel setpoint();
  public Channel goal();

  /** Call once per cycle, after all channels are set. Emits everything in §4.4. */
  public void log();

  /** Escape hatch: the raw arrays, if you want to do something we didn't think of. */
  public Pose3d[] measuredPoses();

  /** Present ONLY on the ADVANTAGEKIT backend. Empty on EPILOGUE / DOGLOG / NT4 — see §4.7.
   *  On the first empty call PumpkinLib raises Alerts.info naming the AdvantageKit vendordep. */
  public Optional<LoggedMechanism2dHandle> mechanism2d();

  public interface Channel {
    Channel set(String componentId, double value);   // meters for prismatic, radians for revolute
    Channel set(String componentId, Distance value);
    Channel set(String componentId, Angle value);
    Channel holdingPiece(String slotId, boolean present);
  }
}
```

**The auto-wired path — the reason a team gets this nearly free.** If a component id equals a registered `TelemetrySource.telemetryName()`, `MechanismVisualizer` pulls `Measured`, `Setpoint` and `Goal` from that mechanism's telemetry block automatically. A team that names its components after its mechanisms writes **zero** channel calls:

```java
// This is the whole thing when component ids match mechanism names.
private final MechanismVisualizer m_viz = MechanismVisualizer.of(RobotConstants.SPEC).autoBind();
// in periodic(): m_viz.log();
```

### 4.4 Emitted keys

```
Pumpkin/Viz/Measured/Components   Pose3d[]   -> attach to the Robot object, type "Component"
Pumpkin/Viz/Setpoint/Components   Pose3d[]   -> attach to a Ghost object
Pumpkin/Viz/Goal/Components       Pose3d[]   -> attach to a second Ghost
Pumpkin/Viz/ZeroedComponents      Pose3d[]   -> calibration aid; constant
Pumpkin/Viz/Cameras/<Name>        Pose3d     -> field-relative; AdvantageScope Camera Override
Pumpkin/Viz/GamePieces/<Type>     Pose3d[]   -> held pieces, empty array when none
Pumpkin/Viz/Mechanism2d           LoggedMechanism2d  -- ADVANTAGEKIT BACKEND ONLY (see §4.7)
Pumpkin/Viz/ComponentOrder        String[]   -> CRITICAL, logged once; the manifest checks it
```

`ComponentOrder` in the log is what lets `pumpkinAssets --verify` and the triage tooling detect an asset/code mismatch *from a log file*, months later, without the repo.

### 4.5 Ghost semantics

- **Measured** = where the robot actually is.
- **Setpoint** = where the profile says it should be *this cycle*. In AdvantageScope this is a Ghost robot with the setpoint component array. Divergence between the two ghosts is a visual PID readout: a lagging measured ghost is under-gained; an overshooting one is over-gained. This is a teaching tool as much as a debug tool.
- **Goal** = the final commanded target. A goal ghost that never converges is a stuck superstructure.

For the drivetrain, the same three appear on the 2D/3D field via `Pumpkin/Field/Robot`, `Pumpkin/Field/RobotGhost`, `Pumpkin/Field/Goal`.

### 4.6 Asset generation and calibration

**`config.json` is generated, never hand-written.**

```java
public final class AssetExporter {
  public static void write(ArticulationSpec spec, Path assetFolder) throws IOException;
  /** Verifies component count, model_N.glb presence/naming, and JSON validity.
   *  Returns problems; empty list == OK. */
  public static List<String> verify(ArticulationSpec spec, Path assetFolder);
}
```

Repo layout PumpkinLib creates:

```
src/main/deploy/ascope/
  Robot_Pumpkin/
    config.json      <- GENERATED. Do not edit; edit ArticulationSpec.
    model.glb        <- base chassis, NO components
    model_0.glb      <- elevator stage 1
    model_1.glb      <- carriage
    model_2.glb      <- arm
```

Generated `config.json` (real schema, verified against the AdvantageScope docs):

```json
{
  "name": "Pumpkin",
  "isFTC": false,
  "disableSimplification": false,
  "rotations": [{ "axis": "z", "degrees": 90 }],
  "position": [0.0, 0.0, 0.0],
  "cameras": [
    { "name": "Front",
      "rotations": [{ "axis": "y", "degrees": 20 }],
      "position": [0.20, 0.0, 0.80],
      "resolution": [1280, 800],
      "fov": 90 }
  ],
  "components": [
    { "zeroedRotations": [], "zeroedPosition": [0.0, 0.0, -0.100] },
    { "zeroedRotations": [], "zeroedPosition": [0.0, 0.0, -0.250] },
    { "zeroedRotations": [{ "axis": "y", "degrees": -90 }], "zeroedPosition": [-0.15, 0.0, -0.30] }
  ]
}
```

**The rule that makes generation possible** (this is the part every team gets wrong by hand):

> `zeroedRotations` + `zeroedPosition` together are the transform that moves the exported component mesh so that **its joint origin sits at (0,0,0) and its joint axis is canonically oriented**. `rotations` are applied before `position`. Given that, the published `Pose3d[i]` is simply the robot-relative pose of joint *i*'s frame — i.e. exactly what forward kinematics over `ArticulationSpec` produces.
>
> Therefore `zeroedRotations` = the inverse of the joint's zero-configuration orientation, and `zeroedPosition` = the negated, rotated zero-configuration translation. `ArticulationSpec` knows both, so `AssetExporter` emits them and `solve()` emits the matching poses. **They cannot drift.**

**Live calibration** — because CAD export offsets never exactly match the math:

```
./gradlew pumpkinAssetCalibrate
```
Runs the robot program in sim with:
- `Pumpkin/Field/Robot` pinned to `Pose2d.kZero`,
- `Pumpkin/Viz/Measured/Components` = `spec.zeroedPoses()`,
- every component's `zeroedRotations`/`zeroedPosition` exposed as `LoggedNetworkNumber` under **`/Tuning/Assets/<componentId>/...`** (AdvantageScope tuning mode makes exactly the `/Tuning` table editable),
- a `/Tuning/Assets/Write` boolean that, on rising edge, writes the current values back into `config.json` in the deploy folder.

Six-step trial-and-error becomes: open AdvantageScope, drag sliders until it lines up, flip the write toggle.

**Build tasks:**

```
./gradlew pumpkinAssets           # generate config.json, verify, zip -> build/ascope/Robot_Pumpkin.zip
./gradlew pumpkinAssets --verify  # verify only; FAILS the build on mismatch. Wired into `check`.
```
The zip-per-asset output is deliberate: it is simultaneously the AdvantageScope desktop custom-assets folder format **and** the 2027 AdvantageScope Lite `File > Upload Asset` format. One artifact, both eras.

**Documented CAD path:** Onshape → STEP → CAD Assistant → `.glb` with "Merge faces within the same part" enabled. Keep meshes low-poly; AdvantageScope auto-simplifies (escape hatches: `NOSIMPLIFY` in a mesh name, or `"disableSimplification": true`) and AdvantageScope XR degrades badly on heavy scenes.

### 4.7 Mechanism2d — the 2D path, **AdvantageKit backend only**

AdvantageScope's docs recommend a Mechanism2d over articulated 3D components as "the more streamlined approach," and AdvantageScope 2026 can project one onto the 3D field in the **XZ or YZ plane** attached to a robot or ghost. PumpkinLib generates it from the *same* `ArticulationSpec`, so a team gets a usable visualization on day one and upgrades to CAD models later without touching robot code.

**The hard constraint, and why this feature is backend-gated.** WPILib's `Mechanism2d`, `MechanismRoot2d` and `MechanismLigament2d` live in `edu.wpi.first.wpilibj.smartdashboard`. That package is:

- deleted in WPILib 2027 along with the SmartDashboard application,
- banned by **DESIGN.md Principle 9**, by **doc 01 §1.7 constraint 5**, and by this document's own non-goals (§10), and
- concretely forbidden by doc 06's ArchUnit rule `noDeadWpiApis`, which fails the build on any dependency into `edu.wpi.first.wpilibj.smartdashboard..`.

A library cannot ship a fallback that references that package and also pass its own architecture test. **So there is no fallback.** AdvantageKit maintains its own fork of these classes at `org.littletonrobotics.junction.mechanism.{LoggedMechanism2d, LoggedMechanismRoot2d, LoggedMechanismLigament2d}` — a package that is not a WPILib-removed package and that survives the 2027 rename untouched. That fork is the *only* implementation PumpkinLib uses.

Therefore:

```java
package org.pumpkinlib.viz;

/**
 * Handle to a generated Mechanism2d. Obtainable ONLY when the ADVANTAGEKIT backend is
 * active. The interface lives in pumpkinlib-telemetry and has no AdvantageKit types in
 * its signature; the sole implementation lives in pumpkinlib-advantagekit, which is the
 * only artifact allowed to import org.littletonrobotics.
 *
 * There is deliberately NO edu.wpi.first.wpilibj.smartdashboard implementation. That
 * package is removed in 2027 and is fenced off by doc 06's noDeadWpiApis ArchUnit rule.
 */
public interface LoggedMechanism2dHandle {

  /** The raw org.littletonrobotics.junction.mechanism.LoggedMechanism2d. Returned as Object
   *  precisely so PumpkinLib's public API does not hard-depend on AdvantageKit types.
   *  Cast it if you need to. */
  Object raw();

  /** Color8Bit is edu.wpi.first.wpilibj.util.Color8Bit -- the `util` package, which is a
   *  straight org.wpilib.* rename in 2027, not a removal. It is on the compat allowlist. */
  void setLigamentColor(String componentId, Color8Bit color);

  double totalWidthMeters();
  double totalHeightMeters();
}
```

```java
// MechanismVisualizer, on every backend that is not ADVANTAGEKIT:
public Optional<LoggedMechanism2dHandle> mechanism2d() {
  if (PumpkinLog.activeBackend() != Backend.ADVANTAGEKIT) {
    // Raised once, info level, never repeated. Names the exact fix.
    Alerts.info("Pumpkin/Viz",
        "Mechanism2d is unavailable on the " + PumpkinLog.activeBackend() + " backend. "
      + "It requires the AdvantageKit vendordep "
      + "(https://github.com/Mechanical-Advantage/AdvantageKit -- "
      + "org.littletonrobotics.junction.mechanism.LoggedMechanism2d). "
      + "3D component visualization (Pumpkin/Viz/*/Components) works on ALL backends "
      + "and is the recommended path -- see §4.2.");
    return Optional.empty();
  }
  return Optional.of(m_handle);
}
```

**What a non-AdvantageKit team loses, precisely:** the `Pumpkin/Viz/Mechanism2d` key and the AdvantageScope Mechanism2d projection. **What they keep:** all of §4.2–§4.6 — the full articulated 3D robot, all three ghost channels, `ZeroedComponents`, camera overrides, game-piece slots, the generated `config.json`, and `pumpkinAssets --verify`. The 3D path is computed by PumpkinLib's own forward kinematics and published as plain `Pose3d[]` structs, which every backend can write. This is why §4.2 is the primary path and §4.7 is the convenience.

Generation rule: each `ArticulationSpec` component becomes one `LoggedMechanismLigament2d` in the XZ plane, projected from the chain. Prismatic joints vary ligament **length**; revolute joints vary ligament **angle**. Root is at the bottom-center of the mechanism, matching AdvantageScope's stated projection origin.

Two important, non-obvious facts encoded in the implementation:
- You must use `LoggedMechanism2d` / `LoggedMechanismRoot2d` / `LoggedMechanismLigament2d`, **not** the WPILib classes — the WPILib ones will not compile with `Logger.recordOutput`, and referencing them fails `noDeadWpiApis` anyway. The two reasons agree.
- `Logger.recordOutput(key, mech)` snapshots the *current* state, so it must be called every cycle. `MechanismVisualizer.log()` does.

**Required companion change in doc 06.** The `noDeadWpiApis` rule keeps `edu.wpi.first.wpilibj.smartdashboard..` in its banned list with no exemption, and its comment is updated to read:

```java
/** Removed in 2027. Referencing any of these guarantees a rewrite.
 *  NOTE: there is no Mechanism2d exemption. PumpkinLib's 2D visualization uses
 *  AdvantageKit's fork (org.littletonrobotics.junction.mechanism.LoggedMechanism2d)
 *  and is available only on the ADVANTAGEKIT backend -- see doc 04 §4.7. */
@ArchTest static final ArchRule noDeadWpiApis = ...
```

**[UNVERIFIED — resolve at implementation time]** The AdvantageKit docs snippet shows `mechanism.generate3dMechanisms()` (plural); the deep-logging dossier reports the actual v26.0.2 source declares `public synchronized ArrayList<Pose3d> generate3dMechanism()` (**singular**). Do not copy the docs snippet blind. The PumpkinLib adapter must resolve this at compile time against the pinned AdvantageKit version and fail loudly if neither exists. PumpkinLib does not depend on this method for its primary path — §4.2's own forward kinematics is the source of truth for `Pose3d[]` — so a rename in AdvantageKit cannot break 3D visualization.

---

## 5. Log Management & Match Triage

### 5.1 Naming

Backend-dependent, and PumpkinLib normalizes both into the manifest rather than fighting either:

- **AdvantageKit `WPILOGWriter`** writes `akit_<yy-MM-dd_HH-mm-ss>_<event>_<matchtype><n>.wpilog` once FMS supplies a match number (e.g. `akit_25-03-29_14-08-10_ohmv_e5.wpilog`), and a randomized identifier before the DS connects. Constructors: `WPILOGWriter()`, `WPILOGWriter(String path)`, `WPILOGWriter(AdvantageScopeOpenBehavior)`, `WPILOGWriter(String path, AdvantageScopeOpenBehavior)`.
- **WPILib `DataLogManager`** writes `WPILib_TBD_{random}.wpilog` → `WPILib_yyyyMMdd_HHmmss.wpilog` → `WPILib_yyyyMMdd_HHmmss_{event}_{match}.wpilog`.

PumpkinLib does **not** rename files after the fact (renaming an open file on the RIO is how you lose a match log). Instead it writes a sibling manifest and an index.

### 5.2 The per-match manifest

Written on disable-edge and again on `Robot.end()`, next to the log, named `<logstem>.manifest.json`. Everything in it is already in the log; the manifest exists so that **triage is grep-able and scriptable in the pit without opening AdvantageScope**.

```json
{
  "schema": 1,
  "wpilog": "akit_26-03-14_13-02-11_curie_q42.wpilog",
  "sidecars": [
    { "path": "ctre_logs/2026-03-14_13-02-11", "kind": "hoot", "replayable": false },
    { "path": "WPILib_20260314_130211.dslog",  "kind": "dslog", "replayable": false }
  ],
  "event": "curie", "matchType": "Qualification", "matchNumber": 42, "replayNumber": 0,
  "alliance": "Blue", "station": 2, "gameData": "",
  "robotIdentity": "COMP", "backend": "ADVANTAGEKIT", "replayCapable": true,
  "gitSha": "a1b2c3d", "gitBranch": "main", "gitDirty": false, "buildDate": "2026-03-13T21:40:02",
  "componentOrder": ["Stage1", "Carriage", "Arm"],

  "autoName": "ThreePieceLeft",
  "autoDurationSec": 14.8,
  "autoCompleted": false,
  "autoAbortReason": "Command 'ScoreL4' interrupted at t=11.42s",

  "maxLoopMs": 41.2, "cyclesOver20ms": 118, "maxQueuedCycles": 3,
  "schemaGeneration": 1,
  "schemaBoundaries": [
    { "generation": 1, "timeSec": 12.61, "action": "DEMOTED",
      "keys": ["Pumpkin/Drive/ModulePositions"],
      "reason": "p95=8214B > budget=6000B for 50 cycles" }
  ],
  "minBatteryVolts": 8.9, "brownouts": 0, "canUtilizationMax": 0.71,
  "visionUptimeFraction": 0.94,
  "maxPoseDivergenceMeters": 0.31,
  "faults": [ {"name":"CAMERA_OFFLINE","count":2}, {"name":"PERF_Vision/Update","count":9} ],

  "triageHints": [
    "Auto did not complete: ScoreL4 interrupted at t=11.42s (see CommandsAll/ScoreL4).",
    "Pose divergence peaked at 0.31 m at t=10.9s -- check Pumpkin/Vision/Front/RejectReason.",
    "118 cycles exceeded 20 ms; worst offender Pumpkin/Perf/Vision/UpdateMs (14.1 ms p95).",
    "Log schema generation changed to 1 at t=12.61s: Pumpkin/Drive/ModulePositions is sampled at 10 Hz after that point. Gaps in that ONE trace after 12.61s are expected, not data loss. Nothing else changed."
  ]
}
```

`triageHints` is generated by a small, fixed rule set — not machine learning, not heuristics that surprise you. The rules are documented and each one names the log key it fired on.

```java
public final class PumpkinLogSession {
  public static MatchManifest manifestFor(Path wpilog) throws IOException;
  public static List<MatchManifest> index(Path folder) throws IOException;
  /** Writes/refreshes index.json listing every manifest in the folder, newest first. */
  public static void reindex(Path folder) throws IOException;
}
```

### 5.3 Storage

| | 2026 | 2027 |
|---|---|---|
| Primary | `/U/logs` (FAT32 USB stick) | `/u/logs` |
| Fallback | `/home/lvuser/logs` | `/home/systemcore/logs` (user `systemcore`) |

Hard constraints PumpkinLib encodes:
- USB sticks must be **FAT32**; NTFS/exFAT silently do not work, and Windows will not format >32 GB as FAT32. **Use a 32 GB stick.** Startup check: if `/U` is absent or unwritable, raise fault `LOG_USB_MISSING` (`kWarning`) and fall back to `/home/lvuser/logs` — logging never stops silently.
- `DataLogManager` prunes `WPILib_` logs oldest-first below 50 MB free (floor of 10 files). CTRE `SignalLogger` deletes at 50 MB free and **stops logging entirely at 5 MB**. PumpkinLib's own governor stops writing and raises `LOG_DISK_LOW` at `minFreeMegabytes` (default 200 MB) so you never reach either vendor's cliff mid-match.
- `cfg.compress = true` enables an xz-compressed `.wpilogxz` receiver (6328's practice) with a transparent reader for replay and a `pumpkinExtract` task. Off by default in v0.1 — it trades RIO CPU for disk, and CPU is the scarcer resource in 2026.

### 5.4 `./gradlew pullLogs`

This is the biggest genuine gap in the ecosystem. WPILib's DataLogTool is SFTP-only, GUI-driven, one event at a time, and **cannot connect while the field radio firewall is up**.

```
./gradlew pullLogs                                   # new logs since last pull -> logs/<event>/
./gradlew pullLogs --match=q42 --open                # one match, then open in AdvantageScope
./gradlew pullLogs --since=last --event=CURIE
./gradlew pullLogs --delete-remote --keep-free=1GB
./gradlew pullLogs --sidecars                        # also pull .hoot subfolders and .revlog
```

Implementation:
- **FTP first** (AdvantageScope 2026 switched to FTP for 2–4× speed: 25 → 80 Mb/s under high RIO CPU), **SFTP fallback**.
- Skips files already in the local cache by (name, size, mtime).
- Pulls the `.hoot` **subfolder group** with its `.wpilog` (CTRE writes folder-per-session; AdvantageScope 2026 added subfolder download precisely for this).
- Writes/refreshes the manifest and `index.json` locally.
- `--open` hands off using AdvantageKit's own documented mechanism: write the absolute log path to `<java.io.tmpdir>/ascope-log-path.txt` (this is exactly what `WPILOGWriter.end()` does with `AdvantageScopeOpenBehavior.ALWAYS`).
- **Documented prominently: you cannot run this while the robot is on the field.** Tether in the pit.

### 5.5 The documented 5-minute workflow: "why did auto fail in match 42"

This ships as `docs/triage.md` and is printed by `./gradlew pullLogs --help`.

```
0:00  Tether to the robot in the pit (USB or Ethernet).
0:10  ./gradlew pullLogs --match=q42 --open --sidecars
      Downloads the .wpilog + .hoot + .dslog, writes the manifest, opens AdvantageScope.

0:40  Read the manifest FIRST, not the graphs:
        cat logs/curie/akit_26-03-14_13-02-11_curie_q42.manifest.json | jq .triageHints
      In 8 of 10 cases the answer is already there. Three questions, in order:
        autoCompleted == false?  -> look at autoAbortReason.
        faults non-empty?        -> a named subsystem already told you.
        cyclesOver20ms > 50?     -> you had a CPU problem, not a logic problem.
        schemaGeneration > 0?    -> the governor throttled a named key at a named time.
                                    Read schemaBoundaries BEFORE you look at any trace, so
                                    you never mistake a governed 10 Hz key for a dropout.
                                    Only the five keys in §2.7 can ever appear here.

1:30  In AdvantageScope, load the shipped layout:  File > Import Layout >
      pumpkinlib/layouts/AdvantageScope-Triage.json
      Four tabs appear pre-populated:
        [Field]    Pumpkin/Field/Robot + RobotGhost + Trajectory + VisionPoses
        [Auto]     CommandsAll/*, Pumpkin/<mech>/Goal|Setpoint|Measured|AtGoal
        [Vision]   Pumpkin/Vision/*/Accepted, RejectReason, TagCount, Pose divergence
        [Perf]     Pumpkin/Perf/*, LoggedRobot/FullCycleMS, Logger/QueuedCycle, battery

2:00  Scrub the Field tab to the moment the auto went wrong. Every other tab follows --
      AdvantageScope synchronizes the timeline selection across all tabs.

2:30  Classify. Exactly one of these is true and each has a different fix:
      (a) Robot ghost tracked the trajectory but the MECHANISM never reached AtGoal
          -> Auto tab: which mechanism's Measured flat-lined away from Setpoint?
             Check its Inputs/Connected, StatorCurrentAmps, LimitForward, SoftLimit*.
      (b) Robot ghost diverged from the measured robot
          -> drivetrain/odometry. Check Pumpkin/Drive/VisionDivergenceMeters and
             Pumpkin/Vision/*/RejectReason at that instant.
      (c) Everything tracked but a command was interrupted
          -> Auto tab, CommandsAll/*: find the boolean that dropped early.
      (d) Loop time spiked
          -> Perf tab. Fix the logging volume, not the logic.

4:00  If you still don't know, and you are on the AdvantageKit backend, ADD A LOG LINE:
        PumpkinLog.log("Pumpkin/Superstructure/WhyNotScoring", reasonString);
      then:  ./gradlew replayWatch
      Replay re-runs the match at ~50x on your laptop and AdvantageScope refreshes on every
      save, preserving your time range and layout. The new field is now in the match-42 log.
      You can also attach a debugger and set a breakpoint at t=11.4s.

5:00  Write one line in the match log spreadsheet. Fix it. Next match.
```

Step 4:00 is the actual value of replay, and it is worth stating plainly to students: **replay is not a what-if simulator.** Modified outputs cannot change replayed inputs. You can add outputs and re-derive them from the recorded inputs; you cannot make the replayed robot take a different action. Teams who expect otherwise will be disappointed.

### 5.6 Replay regression verification

Turns "is my replay trustworthy?" into a number, in CI, against a checked-in reference log.

```java
public final class PumpkinReplayVerify {
  public static ReplayReport verify(Path referenceLog);

  public interface ReplayReport {
    double cycleMatchFraction();
    List<String> divergedKeys();
    double firstMismatchSeconds(String key);
    double maxAbsoluteError(String key);
    String summary();

    /** Every Pumpkin/Log/SchemaGeneration change found in the reference log. */
    List<SchemaBoundary> schemaBoundaries();
  }

  public record SchemaBoundary(long generation, double timeSec,
                               String action, List<String> keys, String reason) {}

  public static Builder of(Path referenceLog);
  public interface Builder {
    Builder ignoring(String... globs);
    Builder withTolerance(String key, double tol);
    ReplayReport run();
  }
}
```

**Schema generations are expected discontinuities, not diffs.** The byte-budget governor (§2.7) may reduce a marked key from 50 Hz to 10 Hz mid-session. Without special handling that key would appear on cycle *N* and be absent on *N+1..N+4*, and a naive differ would report four false divergences per demoted key per 100 ms — swamping the real signal in exactly the logs that matter most (the ones recorded while the robot was loaded). So the verifier:

1. Reads `Pumpkin/Log/SchemaGeneration` from **both** the recorded and the replayed stream and partitions the timeline at every change.
2. For any key named in a `Pumpkin/Log/Governor/Demoted` array within a generation, compares values **only on cycles where the recorded stream published that key**. A cycle where a demoted key is absent from both streams is a match, not a miss. A cycle where it is absent from one and present in the other **is** a divergence and is reported — that catches a genuine governor bug.
3. Requires the generation *sequence itself* to match: `verify` fails if replay produced a different set of boundaries than the recording, because that means the governor is behaving nondeterministically, which is a real defect.
4. Ships `Pumpkin/Log/**` (including `SchemaGeneration` and `Governor/**`) and `Pumpkin/Perf/**` in the **default** ignore set — governor decisions depend on measured wall-clock byte rates, which are legitimately different at 50× replay speed. Only the *boundary structure* from step 3 is checked, never the byte counts.
5. Prints boundaries in the report, so the human reading a failure sees them:

```
./gradlew replayVerify
  curie_q42.wpilog:  3424/3424 cycles match (100.00%)
    SCHEMA gen 0 -> 1 at t=12.61s  DEMOTED Pumpkin/Drive/ModulePositions
           (expected discontinuity; 342 sparse cycles excluded from the diff)
  PASSED
```

Because the demotable set is a closed, five-entry list fixed by the library (§2.7), the set of keys that can ever land in this path is small, enumerable, and testable — `PumpkinTest` ships a case that forces the governor over budget in sim and asserts `replayVerify` still reports 100 %.

```java
@Test
void replayOfCurieQ42IsStillDeterministic() {
  var r = PumpkinReplayVerify.of(Path.of("src/test/resources/logs/curie_q42.wpilog"))
      // Pumpkin/Perf/** and Pumpkin/Log/** are in the default ignore set; listed here
      // only to make the test self-documenting.
      .ignoring("RealOutputs/Pumpkin/Perf/**", "RealOutputs/SystemStats/**",
                "RealOutputs/Pumpkin/Log/**")
      .withTolerance("RealOutputs/Pumpkin/Drive/Pose", 1e-6)
      .run();
  assertTrue(r.cycleMatchFraction() > 0.9999, r.summary());
  // A governed key gapping is expected. A governed key gapping DIFFERENTLY on replay is not.
  assertEquals(1, r.schemaBoundaries().size(), r.summary());
}
```

```
./gradlew replayVerify
  curie_q42.wpilog:  3421/3424 cycles match (99.91%)
    DIVERGED RealOutputs/Pumpkin/Superstructure/AtGoal   first mismatch t=15.42s
    DIVERGED RealOutputs/Pumpkin/Drive/Pose              max |dx| = 0.031 m
  FAILED (threshold 99.99%)
```

Mechanism: AdvantageKit already writes both `RealOutputs` and `ReplayOutputs` into the replay output file. The diff engine reads it with `edu.wpi.first.util.datalog.DataLogReader`. This is ~300 lines of glue nobody has written, and it is what makes replay usable by a team without a dedicated software mentor.

---

## 6. Dashboards

### 6.1 Position

**Elastic is the driver dashboard. AdvantageScope is the programmer's tool. There is no third.** Shuffleboard, SmartDashboard, PathWeaver and RobotBuilder are all deleted in 2027 and deprecated in 2026. PumpkinLib's public API never mentions a WPILib dashboard type.

### 6.2 The driver mirror — a stable NT4 contract

The full telemetry stream lands under a backend-dependent NT path (`/AdvantageKit/RealOutputs/...` vs `/Pumpkin/...`). A shipped Elastic layout cannot bind to a moving target, and Elastic widgets need Field2d- and SwerveDrive-shaped tables that no logging backend produces.

So PumpkinLib publishes a **separate, small, stable, backend-independent mirror** with raw NT4 publishers at **10 Hz**: about 30 topics, a few hundred bytes per update. It is never suppressed on FMS. It is the *only* thing the shipped Elastic layouts reference.

```
/SmartDashboard/Field            Field2d-shaped table: ".type"="Field2d",
                                 "Robot" double[3] {x, y, degrees},
                                 "Ghost" double[3], "Trajectory" double[3n]
/SmartDashboard/Swerve Drive     ".type"="SwerveDrive",
                                 "Front Left Angle"/"Front Left Velocity" (and FR/BL/BR),
                                 "Robot Angle"
/SmartDashboard/Pumpkin          WPILib Alert group -> Elastic Alerts widget
/SmartDashboard/Match Time       double
/Pumpkin/Driver/Alliance         String
/Pumpkin/Driver/AutoSelected     String
/Pumpkin/Driver/AutoReady        boolean   (auto chosen AND start pose sane AND no kError)
/Pumpkin/Driver/Ready            boolean   (rolls up every health check -- the ONE light)
/Pumpkin/Driver/BatteryVolts     double
/Pumpkin/Driver/CanUtilization   double
/Pumpkin/Driver/SuperstructureState String
/Pumpkin/Driver/HasGamePiece     boolean
/Pumpkin/Driver/VisionOk         boolean
/Pumpkin/Driver/VisionTagCount   double
/Pumpkin/Driver/<Mech>/AtGoal    boolean   (one per registered mechanism)
/Pumpkin/Driver/<Mech>/Measured  double
/Pumpkin/Driver/Faults           String[]
```

Writing the Field2d and SwerveDrive tables by hand (rather than via `SmartDashboard.putData`) is deliberate: it avoids the `Sendable`/`SmartDashboard` API entirely, so nothing here breaks in 2027 — the NT *paths* survive even though the SmartDashboard *application* does not.

```java
public final class PumpkinDashboard {
  /** Called automatically by PumpkinRobot. Starts the layout web server so nobody forgets. */
  public static void init();                      // WebServer.start(5800, Filesystem.getDeployDirectory().getPath())

  public static void selectTab(String tab);
  public static void notify(Notify n);            // rising-edge + rate-limited
  /** Mirrors 06's AlertRegistry into the Elastic notification channel. */
  public static void bridgeAlertsToElastic();
  public static void autoSelectTab(String tab, BooleanSupplier when);
}

/**
 * `level` uses the same three-value vocabulary as Pumpkin/Health/Worst (§3.5):
 * "ERROR" | "WARNING" | "INFO". It is a String and not edu.wpi.first.wpilibj.Alert.AlertType
 * deliberately -- §11's rule is that no WPILib type appears in a PumpkinLib public signature
 * except geometry, Measure/Unit and DCMotor, and ElasticLib's wire format is a string anyway.
 * The three factories are the only intended construction path.
 */
public record Notify(String level, String title, String detail, Time show) {
  public static Notify error(String title, String detail);
  public static Notify warning(String title, String detail);
  public static Notify info(String title, String detail);
  public Notify oncePerSecond();
  public Notify noAutoDismiss();
}
```

PumpkinLib **vendors ElasticLib** (`Elastic.java`, single file, no dependencies, NT topics `/Elastic/RobotNotifications` and `/Elastic/SelectedTab`) inside its jar rather than making every team keep a private copy that drifts. `Notify` reuses a single instance internally to avoid the GC churn ElasticLib's own docs warn about, and rate-limits per (title, level) so calling `notify()` in `periodic()` cannot spam.

By default `PumpkinRobot` calls `PumpkinDashboard.autoSelectTab` on mode transitions: Disabled → `Setup`, Auto → `Autonomous`, Teleop → `Driver`, Test/Utility → `Diagnostics`.

### 6.3 Shipped Elastic layouts

Four `.json` files placed in `src/main/deploy/` by the PumpkinLib project scaffold (they **must** be at the deploy root for remote layout download to work) and re-emitted by `./gradlew pumpkinLayouts`:

| File | Audience | Contents |
|---|---|---|
| `elastic-driver.json` | Drivers, in a match | Field (large), Match Time, `Ready` boolean box, `HasGamePiece`, Superstructure state, Alerts, camera stream |
| `elastic-operator.json` | Operator / coach | Auto chooser, alliance, per-mechanism `AtGoal` boolean boxes and `Measured` number bars, `VisionOk`, battery, Alerts |
| `elastic-tuning.json` | Students tuning a mechanism | `/Tuning/**` editable numbers, live graphs of `Measured`/`Setpoint`/`Error`, `AtGoal`, current draw, a "Run Characterization" command widget |
| `elastic-diagnostics.json` | Programmer in the pit | Every `Inputs/Connected`, temps, CAN utilization, loop time, `Logger/QueuedCycle`, fault list, PDH channels |

Real schema (verified against a working 2026 layout):

```json
{
  "version": 1.0,
  "grid_size": 128,
  "tabs": [
    {
      "name": "Driver",
      "grid_layout": {
        "layouts": [],
        "containers": [
          { "title": "Field", "x": 0.0, "y": 0.0, "width": 1280.0, "height": 896.0,
            "type": "Field",
            "properties": { "topic": "/SmartDashboard/Field", "period": 0.033,
                            "field_game": "Rebuilt", "robot_width": 0.85, "robot_length": 0.85,
                            "show_other_objects": true, "show_trajectories": true,
                            "field_rotation": 0.0, "robot_color": 4294198070,
                            "trajectory_color": 4294967295, "show_robot_outside_widget": true } },
          { "title": "READY", "x": 1280.0, "y": 0.0, "width": 256.0, "height": 256.0,
            "type": "Boolean Box",
            "properties": { "topic": "/Pumpkin/Driver/Ready", "period": 0.033,
                            "true_color": 4283215696, "false_color": 4294198070,
                            "true_icon": "None", "false_icon": "None" } },
          { "title": "Match Time", "x": 1280.0, "y": 256.0, "width": 256.0, "height": 128.0,
            "type": "Match Time",
            "properties": { "topic": "/SmartDashboard/Match Time", "period": 0.033,
                            "time_display_mode": "Minutes and Seconds",
                            "red_start_time": 15, "yellow_start_time": 30 } },
          { "title": "Alerts", "x": 1280.0, "y": 384.0, "width": 256.0, "height": 512.0,
            "type": "Alerts",
            "properties": { "topic": "/SmartDashboard/Pumpkin", "period": 0.033 } }
        ]
      }
    }
  ]
}
```

`./gradlew pumpkinLayouts` generates the *operator*, *tuning* and *diagnostics* tabs programmatically from the registered mechanism list, so adding a mechanism adds its widgets — the layout cannot go stale. A tiny Java emitter (`org.pumpkinlib.dashboard.ElasticLayoutWriter`) builds the JSON above; grid units are 128 px, positions are `double`, and every widget carries `topic` + `period`.

**[UNVERIFIED]** Elastic's layout JSON schema is not published as a formal public contract, so it could change within the 2026.x line. Mitigation, which is not optional: `pumpkinLayouts` emits `"version": 1.0` and PumpkinLib ships the four layouts as **checked-in files too**, so a schema change degrades to "regenerate them once", never to "the drivers have no dashboard."

### 6.4 What the DRIVER sees vs. what the PROGRAMMER sees

This distinction is a design rule, not a suggestion. A driver looking at a PID graph during a match is a lost match.

| | Driver | Programmer |
|---|---|---|
| Tool | Elastic, `Driver` tab | AdvantageScope (live NT, or a downloaded log) |
| Number of things on screen | ≤ 6 | as many as needed |
| Field view | Big, robot + ghost only | Full 3D with components, ghosts, vision poses, trajectories |
| Health | **One** boolean: `Ready`. Green means play. | Every `Connected`, temp, current, CAN %, loop ms |
| Faults | Alerts widget, human-readable text | Fault counts, timestamps, tracer epochs |
| Editable | Auto chooser only | Everything under `/Tuning` (AdvantageScope tuning mode) |
| During a match | Never changes layout | Not looking at a dashboard; looking at the robot |

`/Pumpkin/Driver/Ready` is the rollup, computed off the Platform-owned alert registry (DESIGN.md D10 — there is no `PumpkinFaults`):

```java
// AlertRegistry lives in org.pumpkinlib.core.alert and is owned by Platform (06).
// This domain only READS it. The severity enum's exact spelling is 06's to fix; this
// document depends only on "there is an error-severity predicate".
boolean ready =
       !AlertRegistry.anyActiveAtError()
    && autoReady
    && allMechanismsHomed
    && gyroConnected
    && batteryVolts > 11.5;
```

Its inputs are logged individually so "why is it red" takes one glance at the operator tab. The same rollup is what `Pumpkin/Health/Worst` (§3.5) reports as a string, so the driver light and the log agree by construction.

### 6.5 Shipped AdvantageScope layouts

Placed in `pumpkinlib/layouts/` in the repo scaffold, imported via `File > Import Layout`:

- `AdvantageScope-Triage.json` — the four tabs from §5.5.
- `AdvantageScope-Tuning.json` — line graph of `Measured`/`Setpoint`/`Goal`/`Error`/`Output` for the selected mechanism, plus `/Tuning` in tuning mode.
- `AdvantageScope-Viz.json` — 3D Field with the robot asset, both ghosts, and camera override wired to `Pumpkin/Viz/Cameras/*`.

The repo scaffold also points AdvantageScope's **Use Custom Assets Folder** at `src/main/deploy/ascope`, so robot models are version-controlled next to robot code (an explicitly supported AdvantageScope feature that almost nobody uses).

---

## 7. Simulation

### 7.1 Sim-by-default

The single largest source of duplicated code in every FRC repo is the ~40-line `simulationPeriodic()` glue per mechanism, and it contains a silent correctness trap CTRE documents verbatim: **rotor position/velocity is pre-gear-ratio, `DCMotorSim` returns post-gear-ratio.** Get it backwards and the vendor's onboard PID behaves nothing like reality, which makes tuning in sim worthless — and nothing tells you.

PumpkinLib's rule: **if a mechanism declared its geometry, it has a physics sim. No extra work, no second code path.**

```java
package org.pumpkinlib.sim;

public interface MechanismSim extends AutoCloseable {
  /** Advance the model and write state back into the vendor sim. Called by PumpkinSim.tick(). */
  void update(double dtSeconds);
  double positionMeters();          // or radians for angular sims
  double velocity();                // SI, mechanism side
  double currentDrawAmps();
  /** Raw WPILib model: DCMotorSim | ElevatorSim | SingleJointedArmSim | FlywheelSim. */
  Object raw();
}

public final class PumpkinSim {
  public static ElevatorBuilder elevator();
  public static ArmBuilder      arm();
  public static FlywheelBuilder flywheel();
  public static RollerBuilder   roller();          // DCMotorSim, no gravity, no limits

  /** Registers a sim for automatic ticking and battery aggregation. Idempotent. */
  public static void register(String name, MechanismSim sim);

  /** Called by PumpkinRobot.simulationPeriodic(). Never runs on real hardware. */
  public static void tick();

  /** Prints every registered SimDevice key and field. The escape hatch for vendors with no
   *  dedicated sim class -- SimDeviceSim keys are stringly-typed and the required prefix is
   *  HIDDEN in SimGUI by default ("Show prefix"), so guessing them wastes evenings. */
  public static void dumpDevices();

  /** Runs every registered mechanism at full output simultaneously and reports predicted
   *  battery sag / brownout. Find power problems before the field does. */
  public static StressReport stressTest();
}
```

Builders take **WPILib `Measure` types**, not bare doubles, so wrong units are a compile error rather than a 360× runtime surprise:

```java
var sim = PumpkinSim.elevator()
    .motor(DCMotor.getKrakenX60Foc(2))
    .attachedTo(m_leader)                 // any TalonFX / SparkMax / SparkFlex / SimDevice
    .gearing(10.0)                        // rotor rotations per drum rotation
    .drumRadius(Inches.of(1.0))
    .carriageMass(Kilograms.of(6.0))
    .travel(Meters.of(0.0), Meters.of(1.35))
    .startingHeight(Meters.of(0.0))
    .simulateGravity(true)
    .measurementStdDev(0.0)               // add noise to test your filters
    .build();                             // auto-registers; returns MechanismSim
```

### 7.2 Vendor sim-state wiring — the SPI

```java
public interface SimMotorHandle extends AutoCloseable {
  /** Push bus voltage in (from BatterySim). */
  void setSupplyVoltage(double volts);
  /** Read what the controller decided to apply, AFTER its own closed loop and limits. */
  double appliedVolts();
  /** Write mechanism-side state back. The implementation applies the gear ratio on the
   *  CORRECT side. This method is the entire reason this SPI exists. */
  void setMechanismState(double positionRotations, double velocityRotationsPerSec);
  double statorCurrentAmps();
}

public final class SimMotors {
  /** Auto-detects the controller type. Throws with a message naming the type if unsupported. */
  public static SimMotorHandle of(Object motorController, double rotorToMechanismRatio);
}
```

Implementations:

```java
// ---- Phoenix 6 ----
final class TalonFXSimHandle implements SimMotorHandle {
  private final TalonFXSimState m_sim;      // talon.getSimState()
  private final double m_ratio;             // rotor rotations per mechanism rotation

  public void setSupplyVoltage(double v) { m_sim.setSupplyVoltage(v); }
  public double appliedVolts()           { return m_sim.getMotorVoltageMeasure().in(Volts); }
  public void setMechanismState(double posRot, double velRps) {
    // THE trap: rotor units are PRE-gear-ratio; the physics model is POST-gear-ratio.
    m_sim.setRawRotorPosition(posRot * m_ratio);
    m_sim.setRotorVelocity(velRps * m_ratio);
  }
}

// ---- REVLib ----
final class SparkSimHandle implements SimMotorHandle {
  private final SparkSim m_sim;             // SparkMaxSim | SparkFlexSim
  private double m_busVolts = 12.0;

  public void setSupplyVoltage(double v) { m_busVolts = v; }
  public double appliedVolts()           { return m_sim.getAppliedOutput() * m_busVolts; }
  public void setMechanismState(double posRot, double velRps) {
    m_sim.iterate(velRps * 60.0 * m_unitsPerRotation, m_busVolts, 0.020);
  }
}

// ---- Anything else: SimDeviceSim("<Prefix>:<Device Name>[<index>]") ----
```

**[UNVERIFIED — must be pinned by a test]** REVLib's `SparkSim.iterate(velocity, vbus, dt)` velocity argument: the REV docs example passes *mechanism* RPM (`Units.radiansPerSecondToRotationsPerMinute(armSim.getVelocityRadPerSec())`) with no gear-ratio multiplication, which implies `iterate` expects velocity in the units the configured encoder reports (i.e. after `SparkBaseConfig` conversion factors). PumpkinLib must ship a unit test that drives a known velocity through `SparkSimHandle` and asserts the Spark's own `getEncoder().getVelocity()` matches the physics model. **Do not ship the REV adapter without that test passing.** The Phoenix 6 convention is documented and verified; the REV one is inferred.

Also encoded, from CTRE's docs: in simulation, bump Phoenix signal update frequencies (`signal.setUpdateFrequency(Hertz.of(1000))` when `Utils.isSimulation()`) for better simulated closed-loop fidelity.

### 7.3 Battery sag and brownout — on by default

Nothing in FRC models this unless you wire it yourself, so sim never reproduces the failure mode that actually loses matches. `PumpkinSim.tick()` does it for free:

```java
public static void tick() {
  if (!RobotBase.isSimulation()) return;          // structurally impossible on hardware
  double[] currents = new double[s_sims.size()];
  int i = 0;
  for (MechanismSim s : s_sims.values()) { s.update(0.020); currents[i++] = s.currentDrawAmps(); }
  double v = BatterySim.calculateDefaultBatteryLoadedVoltage(currents);
  RoboRioSim.setVInVoltage(v);
  PumpkinLog.log("Pumpkin/Sim/BatteryVolts", v, Volts);
  PumpkinLog.log("Pumpkin/Sim/TotalCurrentAmps", sum(currents), Amps);
  // DESIGN.md D10: alerts go through the Platform-owned facade. There is no PumpkinFaults.
  s_simBrownout.set(v < 6.75);            // roboRIO 2.0 stage-2 brownout threshold
}

// Constructed once, at class init, NOT per cycle -- Alerts.warning(...) allocates a handle.
private static final PumpkinAlert s_simBrownout =
    Alerts.warning("Pumpkin/Sim", "SIM_BROWNOUT: simulated bus voltage below 6.75 V");
static {
  s_simBrownout.sticky(true);   // a brownout that happened once still explains the run
}
```

### 7.4 Vision sim

PhotonVision's `VisionSystemSim` is the only real camera sim in FRC, and it models calibration error, FPS **and latency distribution** — which means vision *rejection* logic (std-dev scaling, ambiguity gates, stale-measurement rejection) becomes regression-testable headlessly. Limelight has no simulation story at all; `LimelightHelpers` is a static, NetworkTables-only API with no injection seam.

PumpkinLib's answer is the vision domain's `VisionSource` interface (§1.3). The sim implementation is fed from ground truth:

```java
public final class PumpkinVisionSim {
  /** Wraps PhotonVision's VisionSystemSim with sane defaults per camera model. */
  public static PumpkinVisionSim photon(AprilTagFieldLayout layout);
  public PumpkinVisionSim addCamera(String name, Transform3d robotToCamera, CameraPreset preset);
  public void update(Pose2d groundTruth);
  /** Auto-called when headless/CI is detected: cameraSim.enableRawStream(false),
   *  enableProcessedStream(false). OpenCV stream rendering is expensive and pointless in CI. */
  public PumpkinVisionSim headless();

  /** Limelight teams: this feeds the SIM VisionSource directly from ground truth with the
   *  same latency/noise model, so filtering logic is still exercised even though
   *  LimelightHelpers cannot be simulated. */
  public static PumpkinVisionSim syntheticFor(String limelightName);
}
```

### 7.5 maple-sim — optional, never load-bearing

**State as of 2026-08-06, verified:** the only 2026 release is **v0.4.0-beta (2026-01-17)**, flagged prerelease; GitHub `releases/latest` still returns v0.3.14 (2025-08-20). No stable 2026 release exists. The community has flagged succession risk (the primary developer graduated). Its 2026 REBUILT support is real and documented (`RebuiltFuelOnField`, `RebuiltFuelOnFly`, `withHitTargetCallBack`) but the library self-describes as beta with "potential bugs."

**Therefore:** maple-sim is a **separate optional adapter artifact** (`org.pumpkinlib:pumpkinlib-maplesim`), discovered by `ServiceLoader`, never a core dependency. If absent, PumpkinLib falls back to plain WPILib mechanism sims and raises an *informational* Alert. A team can never fail to build because of maple-sim.

```java
package org.pumpkinlib.sim;

public interface FieldSim {                    // implemented by the adapter
  void addRobot(SwerveSimConfig cfg, Pose2d start);
  void addGamePiece(String type, Translation2d at);
  void launchProjectile(ProjectileSpec spec);
  Pose3d[] gamePieces(String type);
  Pose2d groundTruthPose();
  void resetForAuto();
  void tick();
}

public final class PumpkinFieldSim {
  /** Empty when the adapter is not on the classpath. */
  public static Optional<FieldSim> get();
  public static boolean available();
}
```

The adapter owns the two rules that make maple-sim safe, structurally:
1. `SimulatedArena.getInstance().simulationPeriodic()` is called **only** from `PumpkinSim.tick()`, which returns immediately unless `RobotBase.isSimulation()`. maple-sim's docs warn it consumes roboRIO resources on hardware.
2. Any attempt to call `SimulatedArena.overrideSimulationTimings(...)` while AdvantageKit is on the classpath **throws with an actionable message**. maple-sim's docs, verbatim: "DO NOT override the timing if you are using AdvantageKit, as it only supports 50Hz robots."

The adapter also auto-publishes `Pumpkin/Field/GamePieces/<Type>` from `getGamePiecesArrayByType(...)` and wires **odometry reset → arena pose reset in one place**, so the classic "physics sim and odometry didn't align" first-run failure cannot happen.

Honest caveat we document: maple-sim's projectile gravity is a tuned 11 m/s², not 9.81. Treat shooter sweeps as a starting point, not truth.

### 7.6 Swerve sim

WPILib still ships no swerve physics sim — `DifferentialDrivetrainSim` is the only drivetrain sim and the 2027 docs still say "Swerve support for simulation is in the works." PumpkinLib provides `SwerveSim`, a per-module `DCMotorSim` pair + `SwerveDriveKinematics` integration, as the always-available default, and defers to maple-sim's `SwerveDriveSimulation` (with real collisions) when the adapter is present. Same `DriveIO` either way — the drivetrain domain writes one implementation.

---

## 8. Testing & CI

### 8.1 Why teams don't test

Not unwillingness. The preamble is undocumented tribal knowledge scattered across three doc sites, and its failure modes are inscrutable: forget `assert HAL.initialize(500, 0)` and nothing works; forget `close()` and the *next* test fails with a port-allocation error; forget `DriverStationSim.setEnabled(true)` + `DriverStation.notifyNewData()` and every actuator reads 0.0; forget Phoenix's ~100 ms post-construction and ~20 ms post-request settle delays and your assertions are all zero. And **nothing calls `simulationPeriodic()` or `CommandScheduler.run()` for you** — WPILib's own official unit-test example never advances a control loop at all.

### 8.2 The harness

Shipped as a separate artifact `org.pumpkinlib:pumpkinlib-test` (test-scope only; never on the robot).

```java
package org.pumpkinlib.test;

public final class PumpkinTest implements BeforeEachCallback, AfterEachCallback {

  /** HAL init, DS enable + notifyNewData, SimHooks.pauseTiming, CommandScheduler reset,
   *  PumpkinLog into a NullBackend, vendor settle delays. */
  public static PumpkinTest headless();

  /** Waits the documented Phoenix 6 settle time (~100 ms) after construction. Call once
   *  after all devices exist. No-op if Phoenix 6 is not on the classpath. */
  public PumpkinTest afterConstruction();

  /** Anything AutoCloseable registered here is closed in @AfterEach, in reverse order.
   *  This is what prevents leaked HAL ports from poisoning the next test. */
  public <T extends AutoCloseable> T managing(T resource);

  /** Non-Subsystem mechanisms (the state-based style) need explicit ticking. */
  public PumpkinTest ticking(Runnable... periodics);

  // ---- Time ----
  /** Ticks, in this exact order, at 50 Hz:
   *    1. registered periodics
   *    2. CommandScheduler.getInstance().run()      (this calls Subsystem.periodic() and,
   *                                                  in simulation, Subsystem.simulationPeriodic())
   *    3. PumpkinSim.tick()                         (physics + battery)
   *    4. SimHooks.stepTiming(0.020)                (LAST, so timestamps advance after the cycle)
   *  The ordering is unobvious, there is no WPILib helper for it, and every team
   *  reinvents it or gives up. */
  public void stepSeconds(double seconds);
  public void step(int cycles);

  /** Returns true if the condition became true before the timeout. */
  public boolean runUntil(BooleanSupplier condition, double timeoutSeconds);

  /** Applies a control request and waits the documented ~20 ms Phoenix settle. */
  public void applyAndSettle(Runnable request);

  // ---- Assertions ----
  /** Every alert raised through org.pumpkinlib.core.alert during this test, in order.
   *  Named for the alert facade (DESIGN.md D10), not the deleted PumpkinFaults. */
  public List<String> raisedAlerts();
  public double lastLoopMillis();
  /** Every Pumpkin/Log/SchemaGeneration bump the governor made during this test. Empty is
   *  the expected result for any test that is not deliberately driving the budget over. */
  public List<Long> schemaGenerations();
}
```

### 8.3 The golden-path test template

Ships in the scaffold as `src/test/java/frc/robot/ElevatorTest.java`, with narrative comments explaining *which regression each assertion pins*. Teams copy it.

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import static org.junit.jupiter.api.Assertions.*;

import frc.robot.subsystems.elevator.Elevator;
import frc.robot.subsystems.elevator.ElevatorIOSim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pumpkinlib.test.PumpkinTest;

class ElevatorTest {
  @RegisterExtension
  final PumpkinTest t = PumpkinTest.headless();

  private Elevator newElevator() {
    return t.managing(new Elevator(new ElevatorIOSim()));   // closed automatically
  }

  /** Pins: the mechanism converges, and the units in the config are self-consistent.
   *  If someone flips a gear ratio or an inches/meters conversion, this fails. */
  @Test
  void reachesGoalWithinTwoSeconds() {
    var elevator = newElevator();
    elevator.setGoal(Meters.of(1.0));
    assertTrue(t.runUntil(elevator::atGoal, 2.0),
        "elevator did not reach 1.0 m in 2 s; last measured = " + elevator.heightMeters());
    assertEquals(1.0, elevator.heightMeters(), 0.02);
  }

  /** Pins: soft limits are enforced by the mechanism, not just by the motor controller.
   *  Sim has no hard stops, so this would run away if the clamp regressed. */
  @Test
  void clampsAboveSoftLimit() {
    var elevator = newElevator();
    elevator.setGoal(Meters.of(99.0));
    t.stepSeconds(3.0);
    assertTrue(elevator.heightMeters() <= 1.36, "exceeded soft limit: " + elevator.heightMeters());
  }

  /** Pins: gravity is modeled and kG is non-zero. Without this, a "working" elevator in sim
   *  falls on the real robot the moment it is disabled. */
  @Test
  void holdsPositionAgainstGravity() {
    var elevator = newElevator();
    elevator.setGoal(Meters.of(0.8));
    t.runUntil(elevator::atGoal, 2.0);
    double held = elevator.heightMeters();
    t.stepSeconds(2.0);
    assertEquals(held, elevator.heightMeters(), 0.02, "elevator sagged");
  }

  /** Pins: the telemetry schema. If a key is renamed, dashboards and layouts break silently.
   *  This is cheap and catches an entire class of "the graph is empty" problems. */
  @Test
  void publishesTheStandardSchema() {
    var elevator = newElevator();
    t.stepSeconds(0.1);
    assertTelemetry("Pumpkin/Elevator/Measured", "Pumpkin/Elevator/Setpoint",
                    "Pumpkin/Elevator/Goal", "Pumpkin/Elevator/AtGoal",
                    "Pumpkin/Elevator/Inputs/Connected");
  }

  /** Pins: no alert fires during nominal operation. A test that silently raises
   *  CONFIG_APPLY_FAILED every run is a test lying to you. */
  @Test
  void raisesNoAlertsDuringNormalMotion() {
    var elevator = newElevator();
    elevator.setGoal(Meters.of(0.5));
    t.stepSeconds(2.0);
    assertEquals(List.of(), t.raisedAlerts());
  }

  /** Pins: one mechanism at 50 Hz does not push the log over budget, so the governor never
   *  fires and the log's effective schema never changes. If someone adds a fat log call to
   *  the elevator's hot path, this fails BEFORE the trace gaps show up at an event. */
  @Test
  void doesNotTripTheByteBudgetGovernor() {
    var elevator = newElevator();
    elevator.setGoal(Meters.of(0.5));
    t.stepSeconds(2.0);
    assertEquals(List.of(), t.schemaGenerations(),
        "governor demoted a key during nominal motion; check ./gradlew logBudget");
  }
}
```

### 8.4 Whole-robot and replay tests

```java
class RobotSmokeTest {
  @RegisterExtension final PumpkinTest t = PumpkinTest.headless();

  /** Pins: the robot constructs, runs, and rebuilds a FRESH auto command on every enable.
   *  WPILib forbids reusing a composed Command; reuse throws on the second enable and
   *  costs a match. */
  @Test
  void constructsAndProducesAFreshAutoEachEnable() {
    var container = t.managing(new RobotContainer());
    t.stepSeconds(0.1);
    assertNotSame(container.getAutonomousCommand(), container.getAutonomousCommand());
  }
}

class ReplayRegressionTest {
  /** Pins replay determinism against a real, checked-in match log. Runs in CI. */
  @Test
  void curieQ42StillReplaysIdentically() {
    var r = PumpkinReplayVerify.of(Path.of("src/test/resources/logs/curie_q42.wpilog"))
        .ignoring("RealOutputs/Pumpkin/Perf/**", "RealOutputs/SystemStats/**")
        .run();
    assertTrue(r.cycleMatchFraction() > 0.9999, r.summary());
  }
}
```

### 8.5 Gradle wiring

```groovy
// Required for sim to work at all. WPILib warns some vendor libraries crash under
// simulation; ./gradlew pumpkinDoctor checks every vendordep for desktop artifacts.
wpi.java.debugJni = false
includeDesktopSupport = true

test {
  useJUnitPlatform()
  // Simulated CAN devices reject duplicate IDs within one JVM, and AdvantageKit's Logger
  // cannot restart within a JVM. Both force a fresh JVM per test class.
  forkEvery = 1
  testLogging { exceptionFormat = 'full'; events 'failed' }
}

// Fast suite runs pre-deploy; slow sim-integration suite does not.
tasks.register('simTest', Test) {
  useJUnitPlatform { includeTags 'sim' }
  forkEvery = 1
}

// Tests skipped on deploy (so nobody disables them at 2 AM in the pits),
// but MANDATORY in CI.
deploy { skipTests = true }

tasks.register('pumpkinCheck') {
  dependsOn 'build', 'test', 'simTest', 'pumpkinAssets', 'logBudget'
}
```

### 8.6 GitHub Actions

Ships as `.github/workflows/ci.yml` in the scaffold.

```yaml
name: CI
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

env:
  # Tied to PumpkinLib's supported WPILib version. `./gradlew pumpkinDoctor` warns when the
  # local WPILib year and this image year diverge. The 2025 image is still correct for 2026
  # (dependencies unchanged between those years) -- that coincidence will NOT hold for 2027.
  WPILIB_IMAGE: wpilib/roborio-cross-ubuntu:2025-22.04

jobs:
  build:
    runs-on: ubuntu-22.04
    container: ${{ '' }}                       # replaced below; see note
    steps:
      - uses: actions/checkout@v6
      - name: Mark workspace safe for git
        run: git config --global --add safe.directory $GITHUB_WORKSPACE
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      - name: Compile and run unit tests
        run: ./gradlew build
      - name: Run simulation integration tests
        run: ./gradlew simTest
      - name: Verify AdvantageScope assets match ArticulationSpec
        run: ./gradlew pumpkinAssets --verify
      - name: Verify replay determinism against reference logs
        run: ./gradlew replayVerify
      - name: Report telemetry byte budget
        run: ./gradlew logBudget
      - name: Upload test + budget reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            build/reports/tests/**
            build/pumpkin/**
```

> Note: GitHub Actions does not expand `env` in `container:`. The shipped file hard-codes
> `container: wpilib/roborio-cross-ubuntu:2025-22.04` on one line with a comment pointing at
> `pumpkinDoctor`. The `env` block above is the documented single point of truth for the tag.

Everything above must run **headless, with no display, no hardware, no network.** Consequences already encoded: `HAL.initialize(500, 0)` in every test, PhotonVision streams disabled in CI, no test depends on wall-clock timing.

---

## 9. End-to-End Example — what a team actually writes

A complete robot with a swerve drive, an elevator, an arm, one camera, full 3D visualization, deterministic replay, driver + tuning dashboards, physics sim, and CI. **Everything in this section is user code. There is no more.**

### 9.1 `Robot.java`

```java
package frc.robot;

import org.pumpkinlib.core.PumpkinRobot;
import org.pumpkinlib.telemetry.Backend;

public class Robot extends PumpkinRobot {
  public Robot() {
    super(cfg -> {
      cfg.backend = Backend.ADVANTAGEKIT;   // or AUTO; or EPILOGUE if you don't want replay
      cfg.wpilogFolder = "/U/logs";
      cfg.ctreSignalLogger = true;          // .hoot sidecar for CAN forensics
    });
  }
}
```

`PumpkinRobot` does the rest: logger metadata + receivers + replay source, the Elastic layout web server, the driver mirror, the tracer, fault publishing, `PumpkinSim.tick()` gated on `isSimulation()`, and the `publishAll()` call that emits every registered mechanism's schema block.

### 9.2 `RobotConstants.java` — the visualization declaration

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.math.geometry.*;
import org.pumpkinlib.viz.ArticulationSpec;
import org.pumpkinlib.viz.JointAxis;

public final class RobotConstants {
  private RobotConstants() {}

  public static final Transform3d ROBOT_TO_FRONT_CAM =
      new Transform3d(new Translation3d(0.20, 0.0, 0.80),
                      new Rotation3d(0.0, Math.toRadians(-20.0), 0.0));

  /** Component ids match mechanism telemetryName() values, so autoBind() wires the
   *  measured / setpoint / goal channels with zero further code. */
  public static final ArticulationSpec SPEC = ArticulationSpec.named("Pumpkin")
      .prismatic("Elevator", "ROBOT",
                 new Transform3d(0.0, 0.0, 0.10, Rotation3d.kZero),
                 JointAxis.Z, Meters.of(0.0), Meters.of(1.35))
      .prismatic("Carriage", "Elevator",
                 new Transform3d(0.0, 0.0, 0.15, Rotation3d.kZero),
                 JointAxis.Z, Meters.of(0.0), Meters.of(0.60))
      .revolute("Arm", "Carriage",
                new Transform3d(0.15, 0.0, 0.05, Rotation3d.kZero),
                JointAxis.Y, Degrees.of(-95.0), Degrees.of(95.0))
      .camera("Front", ROBOT_TO_FRONT_CAM, 1280, 800, 90.0)
      .gamePieceSlot("Held", "Arm",
                     new Transform3d(0.25, 0.0, 0.0, Rotation3d.kZero), "Fuel")
      .build();
}
```

Note the two imports. `JointAxis` is the **viz joint axis** (§4.2) and is deliberately *not*
`org.pumpkinlib.units.Axis`, the canonical sealed geometry type (DESIGN.md D4). A file that
needs both — and a drivetrain constants file will — imports both without a collision. Likewise
`ArticulationSpec` (D25) does not read as a sibling of the mechanism-config `MotorSpec` /
`SensorSpec` / `FeedbackSpec` family, because it is not one: it describes a *rendered kinematic
chain*, not a control configuration.

### 9.3 `RobotContainer.java`

```java
package frc.robot;

import org.pumpkinlib.viz.MechanismVisualizer;

public class RobotContainer {
  private final Drive m_drive = new Drive(DriveConfig.MK4I_L3_KRAKEN);
  private final Elevator m_elevator = new Elevator(ElevatorConfig.DEFAULT);
  private final Arm m_arm = new Arm(ArmConfig.DEFAULT);
  private final Vision m_vision = new Vision(VisionConfig.photon("front", RobotConstants.ROBOT_TO_FRONT_CAM));

  // The entire 3D visualization. Component ids match mechanism names, so this binds itself.
  private final MechanismVisualizer m_viz =
      MechanismVisualizer.of(RobotConstants.SPEC).autoBind();

  public void periodic() {
    m_viz.log();
  }
}
```

That is **one field and one call** for: `Pumpkin/Viz/Measured/Components`, `.../Setpoint/Components`, `.../Goal/Components`, `ZeroedComponents`, `Cameras/Front`, and `GamePieces/Fuel` — on **every** backend. The example above declares `Backend.ADVANTAGEKIT` (§9.1), so it additionally gets `Pumpkin/Viz/Mechanism2d`. Had it declared `EPILOGUE`, `DOGLOG` or `NT4`, every key in that list would still be published and only the `Mechanism2d` key would be absent, with a one-time info Alert saying why (§4.7).

### 9.4 The elevator subsystem

```java
package frc.robot.subsystems.elevator;

import org.pumpkinlib.mechanism.PositionMechanism;

public class Elevator extends PositionMechanism {
  public Elevator(ElevatorConfig config) {
    super("Elevator", config);   // registers TelemetrySource + builds the sim from geometry
  }
  // No periodic(), no logging, no simulationPeriodic(), no Mechanism2d,
  // no Pose3d math, no dashboard code, no sim glue. The base class owns all of it.
}
```

### 9.5 What the team gets for the code above

| | |
|---|---|
| Log keys | ~140, all documented in §3, unit-tagged, tier-classified |
| Match logs | `/U/logs/akit_*.wpilog` + `.manifest.json` + `.hoot` sidecar, auto-indexed |
| 3D replay | Full articulated robot in AdvantageScope with measured + setpoint + goal ghosts |
| 2D viz | Generated `LoggedMechanism2d`, projectable onto the 3D field — **AdvantageKit backend only** (§4.7); the 3D path above is what every backend gets |
| Assets | `config.json` generated and build-verified against the code |
| Dashboards | Four Elastic layouts, three AdvantageScope layouts, `Ready` rollup light |
| Sim | `./gradlew simulateJava` — full physics, battery sag, brownout, vision, no robot |
| Replay | `./gradlew replayWatch` — add a log line, see it in match 42 in under 10 s |
| CI | Build + unit tests + sim tests + asset verify + replay verify + log budget |
| Triage | The 5-minute workflow in §5.5 |

**Total user code above: 3 files, ~55 lines.** The equivalent hand-rolled implementation in the user's own `0000-XXXX-Robot-Template` is `Dashboard.java` (311 lines) + per-mechanism sim glue (~40 lines × 10) + `Draggables/` index constants + `Robot.java` logger wiring + the elastic layout by hand.

---

## 10. What We Deliberately Do NOT Do

| We don't build | Because this already does it, well |
|---|---|
| A log file format | **WPILOG v1.0** — documented, tiny, trivially parseable, universally supported |
| A log viewer / graphing tool / 3D renderer | **AdvantageScope 26.0.2** — bundled with the WPILib installer, reads `.wpilog`/`.dslog`/`.hoot`/`.revlog`/`.csv`, synchronized timeline across every tab |
| A deterministic replay engine | **AdvantageKit 26.0.2** — the only one in FRC Java. We wrap it and add the determinism *guard* it lacks |
| An annotation-logging framework | **WPILib Epilogue** — in-tree, compile-time, zero reflection. We're a backend option, not a competitor |
| A "simple logging call" API | **DogLog 2026.5.0** — we adopt its fault semantics and expose it as a backend |
| Another tunable-constant system | The **Tuning & Gains** domain owns `PumpkinTunable`; we only guarantee it lands under `/Tuning` (the exact table AdvantageScope tuning mode makes editable) |
| A driver dashboard application | **Elastic 2026.1.2** — WPILib's own recommended replacement for Shuffleboard/SmartDashboard |
| An AR/XR viewer | **AdvantageScope XR** (iOS/iPadOS). Our only lever is shipping low-poly assets so it stays performant |
| A CAN signal logger | **CTRE SignalLogger** (`.hoot`), **REVLib StatusLogger** (`.revlog`), **URCL**. We index them; we never wrap Hoot Replay as a replay engine |
| A general robotics viewer / plugin system | **Foxglove** via AdvantageScope's MCAP export. Documented as an export path only |
| Rigid-body field physics, game pieces, projectiles | **maple-sim** (dyn4j). Optional adapter, never a hard dependency |
| Per-mechanism physics models | **WPILib** `ElevatorSim` / `SingleJointedArmSim` / `FlywheelSim` / `DCMotorSim` / `BatterySim`. We wire them; we don't write the math |
| A camera simulator | **PhotonVision** `VisionSystemSim` — calibration error, FPS, and latency distribution |
| System identification | **WPILib** `SysIdRoutine` + the bundled SysId tool |
| A unit-test framework | **JUnit 5**, already bundled in GradleRIO |
| Anything on Shuffleboard, SmartDashboard, PathWeaver, RobotBuilder, or NT3 | All deleted in 2027 |
| **Monologue** support | Dead. Last commit 2024-06-21, last release v1.0.0-beta6 (2024-03-09), no 2025/2026/2027 vendordep. Migrating teams are told to move to Epilogue or DogLog |

---

## 11. Risks & 2027 Migration

| Risk | Mitigation |
|---|---|
| WPILib 2027 renames every package `edu.wpi.first.*` → `org.wpilib.*` | No WPILib type appears in a PumpkinLib **public signature** except geometry (`Pose2d`, `Pose3d`, `Transform3d`), `Measure`/`Unit`, and `DCMotor`. Everything else is behind a PumpkinLib type. The port becomes a mechanical internal change |
| Shuffleboard/SmartDashboard deleted | Already never used. The `/SmartDashboard/<Group>` NT *path* for Alerts and the Field2d/SwerveDrive widget tables survive; we write those paths by hand, not through the removed API |
| Field origin moves to field-center/+X-away-from-red in 2027 | `org.pumpkinlib.field.PumpkinField.coordinateSystem(FieldOrigin.BLUE_WALL_2026 → CENTER_RED_2027)`, owned by Drive/Auto (05) per DESIGN.md D14. Every pose reaches this domain already converted; `org.pumpkinlib.viz.FieldGhosts` (§1.4) publishes what it is handed and flips nothing. The 2027 origin move is therefore a one-line change in **05**, invisible here |
| AdvantageKit 2027 templates do not exist yet; v27 alpha is SystemCore-restricted | v0.1 targets 2026 only. The facade means a team can drop to `Backend.EPILOGUE` or `Backend.NT4` on 2027 kickoff day and keep every other feature in this document |
| AdvantageKit CPU cost on the roboRIO is real (documented 80–600 ms loops in 2026) | Tiers + the byte-budget governor + `logBudget` + the mandatory `Sim/Enabled` key (one 2026 team's real root cause was `simMode` left on) |
| Protobuf/record/new-struct first log blocks >100 ms | We ship **no** custom structs. We use WPILib's, and warm every one during `disabledInit` before the first enable |
| Elastic layout JSON schema is not a formal public contract | Layouts are checked in as files **and** regenerable; a schema break costs one regeneration, never a working dashboard |
| maple-sim is beta-only for 2026 with succession risk | Optional adapter, `ServiceLoader`-discovered, graceful fallback + Alert |
| Replay determinism can be broken silently by user code | The lint (§2.6) is the single highest-leverage thing in this document. Nothing today does this |
| WPILib `Alert` API is documented as "likely to change in future seasons" | Wrapped behind `org.pumpkinlib.core.alert` (`Alerts` / `PumpkinAlert`), owned by Platform (06) per DESIGN.md D10. No PumpkinLib public signature names `edu.wpi.first.wpilibj.Alert`. Its instability becomes 06's internal migration, not a user break |
| `Mechanism2d` lives in `edu.wpi.first.wpilibj.smartdashboard`, deleted in 2027 and banned by doc 06's `noDeadWpiApis` ArchUnit rule | We reference it **nowhere**. 2D viz uses AdvantageKit's own fork `org.littletonrobotics.junction.mechanism.LoggedMechanism2d` and is gated to the ADVANTAGEKIT backend (§4.7); there is no WPILib fallback to migrate. 3D component viz is PumpkinLib's own forward kinematics published as `Pose3d[]`, and works on all four backends |
| `SimHooks.stepTiming()` can hang (allwpilib #6641) when called from a `SimDeviceSim` value-changed callback with another `SimDeviceSim` present | `PumpkinTest` never registers value-changed callbacks; documented in the harness Javadoc |

---

## 12. Open Questions

1. **Does `LoggedMechanism2d` expose `generate3dMechanism()` (singular) or `generate3dMechanisms()` (plural) in AdvantageKit 26.0.2?** The docs say plural; the dossier's source read at tag v26.0.2 says singular. Resolve by compiling against the pinned vendordep before shipping. Low impact (our own FK is the primary path) but it must not be a runtime surprise.
2. **REVLib `SparkSim.iterate(velocity, vbus, dt)` velocity units.** Mechanism-side or motor-side? The REV doc example implies encoder-reported units after conversion factors. Must be pinned by the test in §7.2 before the REV sim adapter ships. Getting this wrong makes REV sim tuning silently meaningless — exactly the bug class PumpkinLib exists to eliminate.
3. **Is Elastic's layout JSON schema stable across the 2026.x line?** If not, `pumpkinLayouts` needs a version gate. Needs a diff of a layout exported from 2026.0.0 vs 2026.1.2.
4. **Should `cfg.compress` (xz `.wpilogxz`) default on?** It solves USB capacity and download time, but spends roboRIO CPU — the scarcer resource in 2026. Proposal: default **off** in v0.1, measure real CPU cost on a roboRIO 2.0 in the offseason, revisit for v0.2.
5. **Does the driver mirror belong in this domain or in Competition-Day?** It publishes `Ready`, which rolls up health checks that domain owns. Current split — this domain owns the *transport and schema*, that domain owns the *checks*. Confirm with that owner.
6. **AdvantageKit's `AutoLogOutputManager` only discovers `@AutoLogOutput` fields reachable from the robot object graph AND inside `frc.robot`.** PumpkinLib lives in `org.pumpkinlib`, so it must register its own objects explicitly. Verify `AutoLogOutputManager.addObject(...)` accepts a foreign-package object at runtime, or route everything through explicit `recordOutput` calls (safer; currently assumed).
7. **How do we ship reference logs for `replayVerify` without bloating the repo?** A 10-minute match log is tens of MB. Options: Git LFS, a trimmed 20-second slice, or a synthetic log generated in CI. Proposal: ship a **20-second auto-only slice**, which is where replay regressions actually matter.
8. **AdvantageScope's 2026 FRC field model matches the WELDED AprilTag layout.** Poses computed from the AndyMark layout render ~0.5 in off. Should PumpkinLib log which layout was used (`Pumpkin/Vision/TagLayout`) and warn on mismatch? Leaning yes; needs the Vision domain's agreement on where the layout choice lives.
9. **Do we ship `pullLogs` as a Gradle task, a standalone CLI, or both?** Gradle is zero-install for a team that already has the repo; a CLI is nicer for a scouting laptop that does not. Proposal: Gradle task in v0.1, CLI in v0.2.

---

## 13. Delivery Plan

**v0.1 (offseason 2026 — must ship before Jan 9, 2027 kickoff):**
1. `PumpkinLog` facade with all four backends + `@AutoInputs` processor.
2. The §3 automatic schema, driven by `TelemetrySource`.
3. `Pumpkin/Health/**` alert transport (consuming 06's `AlertRegistry`) + `PumpkinTracer` + the byte-budget governor, including `Demotable`, the closed five-key demotable set, and `Pumpkin/Log/SchemaGeneration`.
4. `ArticulationSpec` / `MechanismVisualizer` / `AssetExporter` + `pumpkinAssets`.
5. `LoggedMechanism2d` generation in `pumpkinlib-advantagekit` (AdvantageKit backend only; `Optional.empty()` + info Alert elsewhere).
6. `PumpkinSim` (Phoenix 6 + REV + SimDevice) + battery sag + `dumpDevices` + `stressTest`.
7. `PumpkinTest` + the golden-path template + the CI workflow.
8. Driver mirror + four Elastic layouts + three AdvantageScope layouts.
9. Match manifest + `pullLogs` + `docs/triage.md`.

**v0.2 (in-season, additive only):**
10. `pumpkinlib-lint` replay-safety processor.
11. `PumpkinReplayVerify` + `replayVerify`.
12. `pumpkinAssetCalibrate` live calibration.
13. maple-sim adapter.
14. `logBudget` static analysis.
15. xz compression.

**Deferred past 2027 kickoff:** AdvantageScope Lite integration (2027-only feature), MCAP tooling, a headless whole-match runner, and cycle-time analytics.

**Estimated effort for this domain: 9–12 person-weeks for v0.1, 5–7 for v0.2.**

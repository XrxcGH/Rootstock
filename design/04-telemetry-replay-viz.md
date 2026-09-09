# Rootstock Design 04 — Telemetry, Replay, Visualization, Dashboards, Simulation & Test

**Domain owner:** Logging / Match Replay / 3D + Mechanism Visualization / Dashboards / Simulation / Testing & CI
**Target:** WPILib 2026.2.x (`edu.wpi.first.*`), Java 17, GradleRIO 2026.2.1, **AdvantageKit 26.0.2 (required)**. 2027 (`org.wpilib.*`, SystemCore, Java 25) is milestone **M12**, not a separate release.
**Status:** Design complete. Implementable as written. Revised for maintainer Decisions 1–4, then revised again 2026-08-08 against the independent review (findings B9, B10, M12, M13, M15 and the applicable minor sweep).
**Scheduling:** This document is **not** release-gated. Its content lands across milestones **M5, M6, M8, M19, M20, M21, M23, M24**; [`ROADMAP.md`](../ROADMAP.md) is authoritative for order and dates. §13 maps every deliverable here to its milestone.
**Date:** 2026-08-08

> **Vendor-API verification note for this revision.** Every AdvantageKit and WPILib symbol named below was re-read against a primary source on 2026-08-08 and the sources are cited inline at the point of use. Three previously-asserted claims did **not** survive and are corrected in place: `Timer.getMonotonicTimestamp()` **does not exist** (§2.5A G3), `Logger`'s explicit-struct overload is `recordOutput(String, Struct<T>, T)` — struct **first** (§2.8), and the `Measure` overload is named **`recordOutputMeasure`**, not `recordOutput` (§2.8). Anything still unresolved is tagged **`[UNVERIFIED]`** inline rather than asserted.

---

## 0. Scope & Responsibilities

This domain owns **everything between "a number exists inside the robot" and "a human understands what the robot did."** Concretely:

| # | Responsibility | Public surface |
|---|---|---|
| 1 | The logging facade — one typed API over **one** logging path, AdvantageKit's `Logger`. There is no backend selection and no `LogBackend` SPI | `org.rootstock.telemetry` |
| 2 | The automatic telemetry schema — every mechanism, vision source and drivetrain logs an identical, documented key set with zero user code | `org.rootstock.telemetry.schema` |
| 3 | Replay safety — the library's own **guarantee** (§2.5A), plus what *user* code must avoid (§2.5B), enforced by a build-time annotation processor and a runtime tripwire | `org.rootstock.telemetry.replay` + `rootstock-lint` |
| 4 | Alert **transport and schema** (the alert registry itself is owned by Platform/06 per DESIGN.md D10), plus the loop-time tracer and the byte budget | `org.rootstock.telemetry.health` |
| 5 | 3D component-pose generation from a declared kinematic chain, plus the AdvantageScope asset pipeline (`config.json` + `model_N.glb`) | `org.rootstock.viz` |
| 6 | `LoggedMechanism2d` generation from the same declaration | `org.rootstock.viz` |
| 7 | Log naming, retention, per-match manifests, and the `pullLogs` toolchain | `org.rootstock.logs` + Gradle plugin |
| 8 | Dashboards — a stable, small NT4 driver mirror plus prebuilt Elastic and AdvantageScope layouts | `org.rootstock.dashboard` |
| 9 | Simulation — sim-by-default physics for every declared mechanism, vendor sim-state wiring, battery sag, the maple-sim adapter | `org.rootstock.sim` |
| 10 | Testing — JUnit 5 harness, deterministic time stepping, replay regression assertions, and a GitHub Actions workflow | `org.rootstock.test` (separate artifact) |
| 11 | Desktop-side match analytics computed from a log, at zero robot cost | `org.rootstock.stats` in `rootstock-cli` |

**Three types this domain owns do not live in a `telemetry` package, and row 1's "public surface" column should be read with that footnote.** `LogConfig`, `RobotMode` and `Tier` are declared in **`org.rootstock.core.spi`** — D26's package for behaviorless value types that cross a layer boundary downward — because `org.rootstock.core` names all three in public signatures and ArchUnit rule 9 forbids an arrow out of core. **Ownership did not move with the package:** §2.2 and §2.2b are the sole definition sites for their semantics, and a change to what a `Tier` means is a change to this document. `Demotable` is *not* in that set and stays in `org.rootstock.telemetry`, because no core signature names it.

### Explicitly NOT in this domain

Mechanism control law, gain storage, tunables, homing, hardware config, vision pose math, path following, superstructure state graphs, LED/rumble policy, and the swerve odometry thread. Those belong to other domains. **This domain consumes their state and publishes it; it never computes it.**

### Design stance (non-negotiable, restated for this domain)

1. **We do not write a log format, a viewer, a replay engine, or a dashboard.** WPILOG, AdvantageScope, AdvantageKit and Elastic exist and are excellent.
2. **The escape hatch is always visible.** Every facade exposes the raw vendor object: `MechanismVisualizer.mechanism2d()`, `RootstockSim.raw(...)`, and — because AdvantageKit is a required dependency and its `Logger` is a static class — a documented instruction to call `org.littletonrobotics.junction.Logger` directly whenever the facade is in the way.
3. **Failures name themselves.** No silent degradation. What used to be *"if replay is impossible on the chosen backend, the API says so at configure time"* is now moot: **replay is never impossible.** The configure-time refusal path is deleted because the condition it guarded cannot occur (§2.5A).
4. **Sim-first.** Every feature in this document works with no robot present, headless, in CI.
5. **50 Hz is mandatory, unconditionally.** AdvantageKit only supports 50 Hz robots and maple-sim forbids timing overrides when AdvantageKit is present ([maple-sim docs](https://shenzhen-robotics-alliance.github.io/maple-sim/using-the-simulated-arena/)). Previously this was a property of *one backend*; now it is a property of Rootstock. Rootstock never changes the loop period and refuses configurations that would. **This is a real, permanent restriction and it is listed as such in §11.**
6. **One logging path.** Rootstock does not abstract over loggers. This is Decision 3 and it is a reversal of the prior design; §2.1 states what it buys and §11 states what it costs, without softening either.

---

## 1. Integration Points — what this domain needs from the rest of Rootstock

These are contracts. If another domain changes them, this domain breaks.

### 1.1 From the **Mechanisms & Control** domain (`org.rootstock.mechanism`)

Every Rootstock mechanism must implement `TelemetrySource`. This is the *entire* coupling — this domain does not know what an elevator is.

#### 1.1.0 This domain owns **PUSH**, not pull. `TelemetrySink` is deleted.

The previous revision of this document declared `void sample(TelemetrySink sink)` on `TelemetrySource` and had `RootstockTelemetry.publishAll()` **pull** every mechanism's values once per cycle. `design/01` §6.2 simultaneously **pushed** the same §3.1 keys from `Mechanism.periodic()` through the `RootstockLog` statics, and its D9 note states flatly that there is no `TelemetrySink` field. Those two specifications cannot both be built: either both paths publish (duplicate keys, doubled bytes, a governor attributing the same bytes twice) or `design/01`'s mechanisms do not implement the interface this document requires.

**Resolved, in favor of push.** [`DESIGN.md` §5](../DESIGN.md) D9 — which is binding — says that `org.rootstock.telemetry.RootstockLog` "is still the single static facade **every domain calls** (`critical()/log()/debug()/processInputs()/timestamp()/isReplay()`)". A facade that every domain calls is a push facade. Therefore:

- **`TelemetrySource` is a registration and declaration contract only** — `telemetryName()` + `describe()`, both consumed **once**, at registration.
- **`sample(TelemetrySink)` and the `TelemetrySink` interface are deleted.** There is no per-cycle pull.
- **Mechanisms, drive and vision publish their own §3 key blocks by calling `RootstockLog` from their own `periodic()`.** The keys are a published contract (§3), not a generated one, and §8.3's `publishesTheStandardSchema` asserts them by literal string.
- **`RootstockTelemetry.publishAll()` survives with a different job** (§1.5): it publishes the *aggregate* blocks this domain computes itself, and it runs the per-cycle **schema audit** that makes "zero user code produces §3" a checked claim rather than an architectural one.

**Reviewer pushback:** D9 also contains the clause *"`TelemetrySink` keeps doc 04's meaning unchanged (the per-cycle push target handed to `TelemetrySource.sample`); it was never a backend."* **Why we depart from it:** that clause and D9's own "single static facade every domain calls" sentence are mutually exclusive, and the review's blocking finding B10 is right that shipping both produces duplicate keys. We keep the *operative* clause (the `RootstockLog` facade) and require the parenthetical to be struck. That edit to `DESIGN.md` D9 is listed as a contract request; this document does not make it. Source: [`DESIGN.md` §5.2 D9](../DESIGN.md).

```java
package org.rootstock.telemetry;

import edu.wpi.first.units.Unit;
import org.rootstock.core.spi.Tier;   // Tier is declared in core.spi (§2.2); telemetry
                                       // still owns its semantics. Arrow points INTO core.

/** Implemented by every Rootstock mechanism, drivetrain and vision camera.
 *
 *  REGISTRATION AND DECLARATION ONLY. Both methods are called exactly once, from
 *  RootstockRegistry.addAll(...) (DESIGN.md D27), and never again. There is no per-cycle
 *  callback into this interface, and there is no TelemetrySink: values are PUSHED by the
 *  implementer through the RootstockLog statics (§2.3) during its own periodic(). */
public interface TelemetrySource {
  /** Stable, unique, path-safe name. Becomes the log key segment: "Rootstock/<name>/...".
   *  Registering two sources with the same name is a FATAL ConfigError, not a warning. */
  String telemetryName();

  /** Called once at registration. The mechanism describes its own schema so that this
   *  domain can (a) attach unit metadata, (b) size the per-motor arrays for ./gradlew
   *  logBudget, (c) generate the Elastic/AdvantageScope layouts (§6.3, §6.5), and
   *  (d) build the expected key set for the per-cycle schema audit (§1.5). */
  void describe(TelemetryDescriptor d);
}

public interface TelemetryDescriptor {
  /** Declares the natural unit of this mechanism's position axis. **Meters for a linear axis,
   *  Degrees for a rotary one** — not Radians. This is not a style preference: `Axis`
   *  (`design/01`) defines `userPerOutputRotation()` as degrees-per-rotation and
   *  `unitLabel()` returns `"deg"`, so the stream a rotary mechanism actually publishes is
   *  in degrees. Declaring Radians here would attach unit metadata that is wrong by 57.3x
   *  and AdvantageScope would silently mis-convert every plot. See §3.1. */
  TelemetryDescriptor positionUnit(Unit unit);
  TelemetryDescriptor velocityUnit(Unit unit);
  /** Number of motors, for the per-motor array fields. */
  TelemetryDescriptor motorCount(int n);
  /** Optional: names for the state enum so the log has a readable string. */
  TelemetryDescriptor states(Class<? extends Enum<?>> stateEnum);
  /** Optional extra scalars this mechanism wants in the standard block. Declaring an extra
   *  key here is what makes it legal to publish; the audit rejects undeclared Rootstock/<Name>/
   *  keys so the schema cannot grow silently. */
  TelemetryDescriptor extra(String key, Unit unit, Tier tier);

  /** UNIT-FREE overload, for extras that have no unit at all: counts, Strings, String[],
   *  booleans. Equivalent to the three-argument form except that NO unit metadata entry is
   *  attached — which is the point. Added because three of CORE's five extras
   *  (`DeviceResetCount`, a count; `Blocked`, a String; `Plan`, a String[]) were forced to
   *  pass `edu.wpi.first.units.Units.Value` — a `DimensionlessUnit` — purely to satisfy the
   *  parameter. A String key is not dimensionless; it is unit-less, and the schema should be
   *  able to say so. Resolves `design/01` §13 OQ16b. */
  TelemetryDescriptor extra(String key, Tier tier);
}
```

**On `extra(String, Tier)` vs. `extra(String, Unit, Tier)`.** They are not interchangeable and the audit treats them differently: the three-argument form asserts *"this value is a measurement in this unit"* and writes an entry-metadata unit string that AdvantageScope reads for axis labeling and conversion; the two-argument form asserts *"this value has no unit"* and writes none. A key declared unit-free and later given a unit is a schema change and bumps `Rootstock/Log/SchemaGeneration` like any other. Numeric keys that *do* have a unit must keep using the three-argument form — `FeedbackVolts` and `FeedforwardVolts` stay `Volts`.

**What I need from Mechanisms:**
- `TelemetrySource` implemented on the mechanism base classes (`PositionMechanism`, `VelocityMechanism`, `RollerMechanism`) so teams inherit it.
- **Each base class publishes the complete §3.1 block from its own `periodic()`, using the `RootstockLog` calls in §2.3 and no others.** Specifically: `Error` is `Setpoint − Measured` and `GoalError` is `Goal − Measured` — two separate keys.
  > **CONTRACT REQUEST DISCHARGED, 2026-08-08 — kept as a labeled note, not deleted, because the collapsed-key bug is the one a future editor will re-introduce.** An earlier revision of this bullet read *"`design/01` §6.2 currently publishes `Error = Goal − Measured`, which collapses them; that is a `design/01` edit, listed as a contract request."* **That statement is no longer true.** `design/01` §6.2 now publishes both keys with the two distinct formulas (`kError = setpoint − measured`, `kGoalError = goal − measured`), tracked as its own correction row 42, and `design/01` §11 test 25 (`ErrorSchemaTest`) asserts the pair mid-profile. Verified 2026-08-08 by grep of `design/01`. Nothing is outstanding here; §8.3's `publishesTheStandardSchema` remains the standing guard on both sides.
- **No `RootstockLog.put(...)` and no `RootstockLog.debugEnabled()`-guarded string building.** `put()` does not exist in the surface this document owns (§2.3); `debugEnabled()` does exist, but reference-typed DEBUG values are supplier-gated so the predicate is rarely needed.
- A `MechanismGeometry` value object (gearing, mass/MOI, length, limits, motor `DCMotor`) that I can read to build the sim model **without the mechanism knowing sim exists**.
- A guarantee that gains are readable at any time (`Gains activeGains()`), so I can log them.
- `MotorInputs` implementing **AdvantageKit's `org.littletonrobotics.junction.inputs.LoggableInputs`** directly, with hand-written `toLog`/`fromLog` (DESIGN.md D24 — see §2.4). The former `RootstockInputs` mirror interface is deleted.

### 1.2 From the **Drivetrain** domain (`org.rootstock.drive`)

**Declared by Drive, not here.** [`DESIGN.md`](../DESIGN.md) D16 gives Drive all four drive-facing interfaces in `org.rootstock.drive`; `RootstockDrive implements PoseProvider, AlignableDrive, DriveTelemetry`. The block below is the contract this domain *consumes*, reproduced for readability. **If it disagrees with `design/05`, `design/05` wins.** Two names are fixed by D16/D16a and are not this document's to spell differently: the raw, unreferenced IMU yaw is `getRawGyro()` on `DriveBackend`, and the offset-corrected, blue-origin heading is `getGyroFieldHeading()` on `PoseProvider`. `DriveTelemetry.rawGyroYaw()` below is the *telemetry mirror* of the former and is logged as `Rootstock/Drive/Gyro/RawYawRad` alongside `Gyro/YawRad` (the field heading), so "these two curves are identical" is a one-glance diagnosis of an unseeded offset.

> **consumed-surface mirror — `design/05` §3.3.2 wins.** *(Banner added 2026-08-08. It was missing: this block was labeled as a mirror in prose but did not carry the **literal string** the duplicate-declaration gate matches on, which made it the one unbannered duplicate of the four D16 interfaces and would have failed the gate. `design/03` §2.2/§13.1 already carried it on all three of its mirrors.)* The four D16 interfaces occur **eight** times across the design — **four canonical declarations in `design/05` §3.3.2**, plus **four labeled mirrors** (`PoseProvider`, `VisionConsumer` and `AlignableDrive` in `design/03`; `DriveTelemetry` here). A mirror is **legal if and only if it carries this banner**; the gate counts canonical declarations plus banner-carrying mirrors and fails only on an unbannered duplicate.

```java
/** consumed-surface mirror — design/05 §3.3.2 wins.
 *  Declared in org.rootstock.drive by D16. Reproduced here for readability only. */
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

### 1.3 From the **Vision** domain (`org.rootstock.vision`)

**`VisionObservation` is deleted from this document.** [`DESIGN.md`](../DESIGN.md) **D17** is binding and deletes *both* copies of that record: "`VisionFrame` is the one vision value type. Vision pushes into the drive through `VisionConsumer.accept(Pose2d bluePose, double fpgaTimestampSeconds, Matrix<N3,N1> stdDevs)`." The previous revision of this document re-declared the deleted record, which would have made `design/03`, `design/04` and `design/05` carry three incompatible observation types.

Under the push model (§1.1.0) this domain does not need an observation type at all. **The vision domain publishes the §3.3 key block itself**, from `RootstockLog`, once per camera per cycle — including the rejected observations, because *"why did vision not correct my pose"* is the #1 triage question and a silently dropped rejection makes it unanswerable. What this domain requires is only the registration contract plus the two scalars the driver mirror (§6.2) and the health rollup (§3.5) read:

```java
package org.rootstock.telemetry;

/** Implemented by each vision camera. Registration-and-declaration only, exactly like
 *  TelemetrySource, which it extends: telemetryName() returns the camera name, so the
 *  camera's block lands at Rootstock/Vision/<Camera>/ per §3.3.
 *
 *  There is no observations() accessor and no VisionObservation record (DESIGN.md D17).
 *  Per-observation data reaches the log because the vision domain PUSHES it. */
public interface VisionTelemetry extends TelemetrySource {
  /** Read once per cycle by publishAll() for Rootstock/Vision/AnyCameraOffline and for
   *  /Rootstock/Driver/VisionOk. Cheap, allocation-free, no vendor call. */
  boolean connected();

  /** Read on change only, for Rootstock/Vision/<Camera>/RobotToCamera. */
  Transform3d robotToCamera();
}
```

**The §3.3 keys the vision domain must publish** are listed in §3.3 and are asserted by name in `publishesTheStandardSchema`. The two that are easiest to forget, and that the triage workflow depends on absolutely, are `Accepted` (`boolean`, CRITICAL, every cycle) and `RejectReason` (`String`, CRITICAL, `""` when accepted). A camera that publishes `Accepted=false` with an empty `RejectReason` fails the schema audit.

### 1.4 From the **Autonomous / Path** domain (`org.rootstock.auto`)

The path adapter must call these three methods — nothing else. They exist so that the *ghost pose contract* (§4.5) is filled by whichever path library the team chose.

```java
package org.rootstock.viz;

/** The ghost-pose contract. Named FieldGhosts, not RootstockField, per DESIGN.md D14:
 *  org.rootstock.field.RootstockField is owned by Drive/Auto (05) and means alliance
 *  flipping + the 2027 field-origin move. This class is only the three ghost channels. */
public final class FieldGhosts {
  public static void setActiveTrajectory(Pose2d[] poses);   // null/empty clears
  public static void setTrajectorySetpoint(Pose2d setpoint);
  public static void setGoal(Pose2d goal);
}
```

Every pose handed to `FieldGhosts` is already in the blue-origin convention produced by `org.rootstock.field.RootstockField`. This domain does **not** flip poses; it publishes what it is given. That is the whole reason the two types are separate.

### 1.5 From the **Core / Platform** domain (`org.rootstock.core`)

- **`RootstockRobot extends LoggedRobot`** — one class. The former split (`RootstockRobot extends TimedRobot` in core **+** `RootstockLoggedRobot extends LoggedRobot` in a separate artifact, DESIGN.md D13) has **collapsed** under Decision 3. `RootstockLifecycle` remains public (D29) so manual wiring is still documented first, and `RootstockRobot` remains a ~20-line delegating shim over it.
  It must call, in this order:
  `RootstockLog.beforeUserPeriodic()` → user code → `RootstockTelemetry.publishAll()` → `RootstockLog.afterUserPeriodic()`.

  **What `publishAll()` does, exactly** — this changed with §1.1.0 and the old name survived a change of job, so it is worth being explicit. It does **not** pull values out of mechanisms; by the time it runs, every mechanism, drivetrain and camera has already pushed its own §3 block from its own `periodic()`. `publishAll()` publishes the blocks **this domain computes**, and only those, in this order:

  ```java
  public final class RootstockTelemetry {
    /** Called by RootstockLifecycle after user periodic, before RootstockLog.afterUserPeriodic().
     *  Allocation-free after the first cycle. */
    public static void publishAll();

    /** The registered sources, insertion-ordered (G7). Public for tests and for
     *  ./gradlew rootstockLayouts, which generates widgets from describe() output. */
    public static List<TelemetrySource> sources();

    /** Keys declared by describe() that were NOT published this cycle, and keys published
     *  under Rootstock/<Name>/ that describe() never declared. Empty is the only passing
     *  result. Drives the audit below and RootstockTest.assertTelemetry. */
    public static SchemaAudit lastAudit();
  }
  ```

  1. `Rootstock/Health/**` from Platform's `AlertRegistry` (§3.5).
  2. `Rootstock/Field/**` — the three ghost channels last set through `FieldGhosts` (§1.4).
  3. `Rootstock/Log/**` — `BytesPerCycle`, `QueuedCycles`, `SchemaGeneration`, `Governor/**` (§2.7).
  4. `Rootstock/Perf/**` — the tracer's epochs (§3.5).
  5. The `/Rootstock/Driver/**` NT mirror, at 10 Hz (§6.2).
  6. **The schema audit.** Every key `describe()` declared for every registered source must have been published this cycle, and no key under `Rootstock/<Name>/` may exist that `describe()` did not declare. In `SIM` and under `RootstockTest` a violation is an **assertion failure**; on a real robot it is a one-shot `Alerts.warning("Rootstock/Log", "SCHEMA_INCOMPLETE: <name> declared <key> and did not publish it", MatchImpact.PIT_ONLY)`, never a throw. This is what makes §3's "zero user code produces all of this" a *checked* claim under the push model rather than an architectural assertion — it is the load-bearing replacement for the deleted pull path, and without it a mechanism that silently stops publishing `Setpoint` is invisible until a student is staring at an empty graph at an event.
- `RobotIdentity` (comp bot / practice bot / sim) so it lands in log metadata and the manifest.
- `MatchContext` (latched alliance, event, match type/number/replay) — used for log naming, the manifest, and the FMS-aware tier gate. **I do not implement this; I consume it.** (Specified in doc 06.)
- `compat.Clock.now()` — the single time source, which is `Timer.getTimestamp()` (the AdvantageKit-injected clock). See §2.5A guarantee G2.
- `BuildConstants` from the `com.peterabeles.gversion` Gradle plugin: `GIT_SHA`, `GIT_BRANCH`, `DIRTY`, `BUILD_DATE`, `MAVEN_NAME`.

**On D26 (`org.rootstock.core.spi`).** The `ServiceLoader`-based `LifecycleHook` plumbing existed partly to break a compile cycle and partly to keep the AdvantageKit adapter out of core. The second reason is gone and the first is now handled by package structure (D28 put telemetry, tuning, sim and vision in one jar). Within this domain that means: **telemetry, sim and viz register as explicit, priority-ordered hooks built in code by `RootstockLifecycle.create()`, not via `ServiceLoader`.** `ServiceLoader` survives here for exactly one thing — the genuinely out-of-jar **maple-sim adapter** (§7.5) — where it is load-bearing. `VisionSimHook`, `MechanismGeometrySink`, `MechanismGeometry` and `SimMotorHandle` all survive as interfaces; only the discovery mechanism changed.

### 1.6 What I provide back to everyone

- `RootstockLog` — the only logging call anyone in Rootstock makes.
- `RootstockTracer` — the only profiling call.
- `RootstockSim.register(...)` — mechanisms hand me a physics model; I own the tick, battery, and gating.
- `RootstockTest` — the JUnit harness other domains write their tests against.

**What I do NOT provide (DESIGN.md D10):** there is no `RootstockFaults`. Alerts are owned by Platform (06) at `org.rootstock.core.alert` — `Alerts.error(group, text, impact)`, `Alerts.warning(group, text, impact)` and `Alerts.info(group, text)` each return a `RootstockAlert` handle, and a sticky fault is `RootstockAlert.sticky(true)`. **Note the arity split, which is deliberate and is the C13 resolution:** `info()` takes **no** `MatchImpact`, because `INFO` is `PIT_ONLY` by definition — an informational alert cannot block a match, so a parameter whose only legal value is `PIT_ONLY` is a parameter that only creates the chance to pass something else. `error()` and `warning()` keep it and have no default. This matches `design/06` §8.2's owning declaration (verified 2026-08-08: `public static RootstockAlert info(String group, String text);` — two arguments) and `design/01` and `design/03`'s call sites; `design/05`'s three-argument `info(...)` is the outlier and is a `design/05` edit. Zero call sites in this document are affected — every alert this domain raises is a `warning()`. This domain **consumes** `AlertRegistry` and publishes the `Rootstock/Health/**` schema from it (§3.5). One alert facade for the whole library; I am its transport, not its owner.

---

## 2. The Logging Facade

### 2.1 The honest headline

> **Deterministic replay is a guaranteed property of Rootstock.** Not a mode, not a backend choice, not something you opt into. If you are running Rootstock, `Logger` is running, every Rootstock hardware read is behind `processInputs`, and a WPILOG written by your robot re-runs through your code and produces the same outputs.

That guarantee is bought with a hard dependency, and the price is stated here rather than in a footnote:

- **Rootstock requires AdvantageKit 26.0.2 or later.** `Rootstock.json` declares `requires: [ WPILibNewCommands.json, AdvantageKit.json ]`.
- **The "installable on kickoff morning before any vendor has published" property is gone.** The earlier design had zero *vendor* `requires` and advertised that property, with a real supporting fact behind it: AdvantageKit's own 2026 swerve templates shipped weeks late waiting on vendors. That constraint has not gone away. We have chosen to sit on the wrong side of it.
- **If AdvantageKit does not ship for WPILib 2027, Rootstock does not ship.** Risk R18 is **accepted**, not mitigated — the `LogBackend` escape hatch that used to make it survivable no longer exists. The contingency is written down in advance in §11.1 rather than left as a bare accepted risk.
- **A team already on DogLog or plain Epilogue cannot adopt Rootstock without switching loggers.** That is a hard incompatibility, not a migration path (§11.2).
- **A team with a loop-time problem can no longer escape by choosing a cheaper logger.** This is the CPU consequence and it is the one that will bite soonest; §2.7 and §11.3 deal with it honestly.

Everything else in this section is what the guarantee lets us delete.

For completeness, the reason AdvantageKit and not something else: **AdvantageKit is the only deterministic replay framework in FRC Java**, and CTRE Hoot Replay is explicitly non-deterministic (6328's published comparison shows total divergence within seconds of auto, and — worse — "there is no way to distinguish accurate outputs from the inaccurate, diverged outputs"). There was never a second candidate; the prior multi-backend design was not offering a choice of replay engine, it was offering a choice between *replay* and *no replay*. Decision 3 removes the no-replay option.

### 2.2 Configuration

There is no `Backend` enum. It, `Backend.AUTO`, `activeBackend()`, `replayCapable()` and the whole selection mechanism are **deleted**.

**Where these three enums live, and why two of them are not in `org.rootstock.telemetry`.** `RobotMode` and `Tier` are declared in **`org.rootstock.core.spi`**; `Demotable` is declared in **`org.rootstock.telemetry`**. This is a *package* split, not an ownership split: **telemetry owns the semantics of all three**, this section remains the sole place their meaning is defined, and any change to what a `Tier` means is a change to this document — exactly the arrangement §2.2b already states for `LogConfig`. The reason for the split is ArchUnit rule 9 (*"every dependency arrow points into core"*): `LogConfig` lives in `org.rootstock.core.spi` (§2.2b) and its public signatures name both enums (`mode()`, `withMode()`, `minimumTier()`, `withMinimumTier()`), so leaving them in telemetry left a `core.spi → telemetry` arrow — the same violation the `LogConfig` move was made to fix, one level down. Both are behaviorless, dependency-free value enums, which is precisely the shape **D26** created `org.rootstock.core.spi` to hold (`MechanismGeometry`, `SimMotorHandle`, and now `LogConfig`). **`Demotable` does not move** and must not: it is never named in any `org.rootstock.core` or `org.rootstock.core.spi` signature — `RootstockLog.processInputs` has no `Demotable` overload (§2.2a), `LogConfig` has no demotable field, and the parameter appears only on `RootstockLog.critical(...)`/`log(...)` inside telemetry itself. Rule 9 is indifferent to it, and moving a type that does not cross the boundary would dilute what `core.spi` means.

```java
package org.rootstock.core.spi;   // D26's downward-crossing-value package (rule 9).
                                   // Telemetry (this section) still owns their semantics.

/** Rootstock's view of the run mode. REAL and SIM come from Platform.isReal()
 *  (org.rootstock.core.compat — NOT RobotBase directly; ArchUnit rule 2 confines the
 *  year-volatile WPILib call to compat). REPLAY is true when AdvantageKit has a replay
 *  source installed. There is no "this mode is unavailable" path any more — all three
 *  always work. */
public enum RobotMode { REAL, SIM, REPLAY }

public enum Tier {
  /** Always present in the log, including on FMS. Anything you would need to explain a
   *  lost match. CRITICAL keys are never removed from the schema by any mechanism. */
  CRITICAL,
  /** The default. Present whenever `minimumTier` is STANDARD or DEBUG — which INCLUDES the
   *  FMS-attached case, because the FMS gate raises `minimumTier` to STANDARD and no higher
   *  (§2.7.1 step 1). A STANDARD key therefore SURVIVES an FMS attach; it disappears only if
   *  a team deliberately sets `minimumTier = CRITICAL`, which nothing in the library does.
   *  *(Wording corrected 2026-08-08: this line previously read "present unless the FMS tier
   *  gate ... applies", which reads as though the FMS gate drops STANDARD. It does not, and
   *  `design/01` §1.1b relied on the loose reading when it questioned its own five extra(...)
   *  tiers. The gate drops DEBUG and only DEBUG.)* Also subject to an explicit Demotable
   *  marking, which changes RATE and never presence. */
  STANDARD,
  /** Dropped when FMS-attached. Reference-typed values are supplier-only so the value is
   *  never computed when the tier is off. */
  DEBUG
}
```

```java
package org.rootstock.telemetry;   // Demotable STAYS here: it is never named in a core or
                                    // core.spi signature, so rule 9 does not reach it. No
                                    // import of Tier -- the javadoc references it, the
                                    // declaration does not, and an unused import is a lie
                                    // about the dependency graph.

/**
 * ORTHOGONAL to Tier. Marks a key as eligible for rate reduction (never removal) by the
 * byte-budget governor (§2.7). Default is NO for every key in the library and every key a
 * team logs. The governor may not touch a key that is not explicitly marked YES.
 *
 * A demoted key is published every 5th cycle instead of every cycle. It is never deleted,
 * never silently retyped, and every demotion bumps Rootstock/Log/SchemaGeneration so replay
 * and the triage tooling can see the boundary.
 *
 * THE INPUT INVARIANT (§2.2a). Demotable applies ONLY to outputs. No value that reaches the
 * log through processInputs may ever be marked YES, and the parameter is not reachable from
 * that path: RootstockLog.processInputs has no Demotable overload, and Demotable is not a
 * field, parameter or annotation anywhere on LoggableInputs, LogTable or any *Inputs class.
 * The governor is structurally incapable of touching a replayed input.
 */
public enum Demotable { NO, YES }
```

#### 2.2a The input invariant — **replayed inputs are never demotable**

This is the single most important sentence in this document, because it is the one that keeps §2.5A true:

> **A key that is read back from the log in `REPLAY` mode is never demotable, at any tier, under any load, by any mechanism. `Demotable.YES` applies to outputs only.**

**Why the previous revision was wrong.** It marked `Rootstock/<Mech>/Inputs/SupplyCurrentAmps` (CRITICAL) and `Rootstock/<Mech>/Inputs/TempCelsius` (STANDARD) as `Demotable.YES`. Both are fields of a hand-written `LoggableInputs` struct. Demoting them breaks replay in two independent ways, and Decision 3 — requiring AdvantageKit — was spent precisely to buy the property they break:

1. **The values diverge.** On the real robot, `updateInputs` reads a fresh supply current and temperature every cycle regardless of what the governor is doing to the *log*. In `REPLAY`, `processInputs` calls `fromLog`, so on the four cycles out of five where the value was not written, replay sees a **stale** value while the match saw a fresh one. Everything downstream then diverges — and `design/06`'s power, brownout and thermal monitors consume exactly these keys, and guarantee **G8** requires those monitors to reach the same verdict at 1× and 50×. §5.6's boundary handling excludes the *demoted key itself* from the diff; it does nothing about the outputs derived from it. The byte-identical guarantee in §2.5A is simply false with a demotable input.
2. **There is no mechanism to do it with.** `MotorInputs.toLog` (`design/01` §3.4) writes every field unconditionally into one `LogTable`, and nothing in AdvantageKit, in this document, or in `design/01` lets a governor gate an individual field inside a `LoggableInputs`. The previous revision specified a behavior that had no implementation path.

**What replaces the reclaimed bytes.** Nothing, at runtime — and that is the honest answer. Input bytes are **not** a governor lever. There are exactly two ways to reduce them and both are design-time choices a team makes before an event, both surfaced by `./gradlew logBudget`:

- **Log fewer of them.** `MotorSpec` decides which signals are subscribed; a mechanism that does not subscribe temperature does not log it. This is a schema decision, visible in `describe()`, identical in real and replay.
- **Let the writer's change detection work.** AdvantageKit's `WPILOGWriter` **writes only values that changed since the previous cycle** — verified in `WPILOGWriter.java`, which compares each field against the previous cycle's map and appends only new or updated fields ([source](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/akit/src/main/java/org/littletonrobotics/junction/wpilog/WPILOGWriter.java)). A motor temperature reported to 0.25 °C resolution changes a few times a second, not fifty. **The fattest key the old table wanted to demote was already costing a fraction of what the table claimed**, which is a second, independent reason the entry was a bad trade: it broke replay to reclaim bytes that were largely not being written. `logBudget`'s static estimate is a worst case and now says so (§2.7).

**Enforcement, three layers, so this cannot regress:**
- **Type level:** `processInputs` has no `Demotable` overload (§2.3). There is nothing to pass.
- **Build level:** `rootstock-lint` fails the build if any key literal containing the segment `/Inputs/` is passed `Demotable.YES`, and if `RootstockBudget.demotableKeys()` and the §2.7 table disagree.
- **CI level:** `RootstockReplayVerify`'s standing gate (§2.5A) is re-run at M5, M9, M11 and M24 with the governor **forced over budget in sim**, asserting 100 % cycle match. A demotable input would fail it on the first run.

**Why `Demotable` stays orthogonal to `Tier` anyway,** now that the CRITICAL example is gone — and this justification is genuinely weaker than the one it replaces, so it is stated as such rather than dressed up. Tier answers *"may this key disappear when the FMS gate raises `minimumTier`?"*; `Demotable` answers *"may this key be sampled slower under sustained load?"* They stay separate because both questions have all four answers in the shipped schema:

| | `Demotable.NO` | `Demotable.YES` |
|---|---|---|
| **CRITICAL** | `Rootstock/Drive/ModuleStates/Measured` — a fat `SwerveModuleState[4]` that changes every cycle and that §5.5 reads frame by frame | *(empty in the shipped set — see below)* |
| **STANDARD** | `Inputs/StickyFaults` — a one-cycle fault that must never be missed, and tiny anyway | `Rootstock/Drive/ModuleStates/SetpointOptimized` — derived from a key that is never demotable |

The CRITICAL/YES cell is **empty after the input fix**, and that is worth admitting: the two axes no longer cross in the shipped set, so "just make STANDARD demotable" would produce the same five keys today. We keep them separate for two reasons that are about the future rather than the present — a fat CRITICAL *output* (a `Pose3d[]` component array on a robot with eight articulations) is a legitimate future YES, and collapsing the axes would mean the FMS tier gate and the governor could no longer be reasoned about independently. A reader who finds that unconvincing should note that collapsing them is a one-line change that loses nothing today.

#### 2.2b `LogConfig` — an immutable value, in `org.rootstock.core.spi`

Two things about this type changed on 2026-08-08 and they are independent, so they are stated separately: **where it lives** (a rule-9 fix) and **what shape it has** (a D13a/D29 fix). Telemetry still **owns** the field set and every field's semantics — this section remains the sole definition site, and `design/01` §1.1a is explicitly a reference restatement of it. Only the package and the construction style moved.

**Why the package moved — ArchUnit rule 9.** `RootstockLifecycle` lives in `org.rootstock.core`, and `RootstockLifecycle.create(LogConfig)` is its only factory (D29). With `LogConfig` in `org.rootstock.telemetry`, that public core signature named a telemetry type — a compile-time arrow pointing **out of** core, which rule 9 (*"every dependency arrow points into core"*) forbids. A type in a public core signature is the same violation as a direct call; D28's one-jar packaging hides it from the classpath but does not fix it in the rule. `LogConfig` is a pure immutable value with no behavior, which is exactly the shape of `MechanismGeometry` and `SimMotorHandle` — both already in `org.rootstock.core.spi`, the package **D26** created for *"the types that cross a layer boundary downward"*. So `LogConfig` goes there too — this is `design/01` §13 **OQ18**'s own recommended option, and `design/01` §1.1a's two `import org.rootstock.telemetry.LogConfig;` lines and their rule-9 notes become the stale side of the contract, which is a `design/01` edit. **OQ18 is answered and, as of 2026-08-08, fully discharged:** the answer is *"yes, move it, to `core.spi`"*, and the residue that move originally left — `LogConfig`'s two enum-typed fields still naming telemetry types — was closed by moving `Tier` and `RobotMode` to `core.spi` as well (§2.2). See the RESIDUE note below the block, which is retained with its original analysis intact and a resolution stamp.

**Why the shape changed — D13a and D29.** The declaration below used to be a bag of public mutable fields reached through `RootstockLog.configure(Consumer<LogConfig>)`. `DESIGN.md` **D13a** is binding and later, and requires an immutable value with `with*()` copies — principle 6 and D1a forbid mutable config everywhere else in the library, and a config object that can be mutated after `start()` is a config object that can silently disagree with the log's own provenance metadata. **D29** additionally requires exactly two factories, which is where the Logger-ownership question is answered. This resolves `design/01` §13 **OQ17**.

```java
package org.rootstock.core.spi;             // D26's downward-crossing-value package (rule 9).
                                             // Telemetry still owns the fields and semantics.

// RobotMode and Tier are declared in THIS package (§2.2) as of 2026-08-08, so there is no
// import for them and no arrow out of core. Telemetry still owns what they MEAN.

/** IMMUTABLE. Every with*() returns a new instance; there is no setter, no public field and
 *  no Builder. Two factories and no public constructor — D29 makes the choice between them
 *  the single decision a team must consciously make, because "a double Logger.start() is a
 *  crash". */
public final class LogConfig {

  // ---------------- The two factories (D29). There is no public constructor. --------------

  /** From scratch: RootstockLifecycle CONFIGURES AdvantageKit's Logger and STARTS it.
   *  What a new robot project, and RootstockTemplate, want. */
  public static LogConfig defaults();

  /** Attach: `Logger.start()` has ALREADY run in the team's own code. RootstockLifecycle
   *  configures nothing that would restart it and never calls start() a second time.
   *  This is the one a team adding RootstockLifecycle to an EXISTING LoggedRobot wants;
   *  D29 names `design/01`, §11b and the README as the three places that must say so. */
  public static LogConfig adoptExistingLogger();

  // ---------------- The field set. Unchanged; only the accessors are new. ----------------
  // Reading is by accessor, writing is by copy. One with*() per field, no exceptions.

  public RobotMode mode();                  // default REAL; set from Platform.isReal() by
  public LogConfig withMode(RobotMode m);   //   RootstockLifecycle, not by user code

  public String wpilogFolder();             // default "/U/logs"  (2027: "/u/logs")
  public LogConfig withWpilogFolder(String folder);

  public String fallbackFolder();           // default "/home/lvuser/logs"
  public LogConfig withFallbackFolder(String folder);

  public boolean compress();                // default false; xz .wpilogxz (6328's trick), §5.3
  public LogConfig withCompress(boolean on);

  /** Governs AdvantageKit's NT4Publisher DATA RECEIVER -- i.e. whether the full log stream
   *  is mirrored to NetworkTables. It has nothing to do with the driver mirror (§6.2),
   *  which is written with raw NT publishers and is NEVER suppressed. Default OFF_ON_FMS. */
  public NtPolicy ntPublish();
  public LogConfig withNtPublish(NtPolicy p);

  public Tier minimumTier();                // default DEBUG; auto-raised to STANDARD on FMS
  public LogConfig withMinimumTier(Tier t);

  public double perCycleByteBudget();       // default 6_000; governor threshold, see §2.7
  public LogConfig withPerCycleByteBudget(double bytes);

  public boolean captureConsole();          // default true
  public LogConfig withCaptureConsole(boolean on);

  public boolean captureDriverStation();    // default true
  public LogConfig withCaptureDriverStation(boolean on);

  public boolean ctreSignalLogger();        // default false; .hoot sidecar, see §2.9
  public LogConfig withCtreSignalLogger(boolean on);

  public boolean urcl();                    // default false; REV sidecar, see §2.9
  public LogConfig withUrcl(boolean on);

  public int minFreeMegabytes();            // default 200; stop writing + fault below this
  public LogConfig withMinFreeMegabytes(int mb);

  public boolean driverMirror();            // default true; publish /Rootstock/Driver/**, §6
  public LogConfig withDriverMirror(boolean on);

  public enum NtPolicy { ALWAYS, OFF_ON_FMS, NEVER }
}
```

> **RESIDUE NOTE — RESOLVED 2026-08-08 in this document. Retained verbatim below the stamp, not deleted, because the analysis is the reason the fix took the shape it did, and a future editor who wonders why two telemetry-semantics enums sit in `core.spi` needs to be able to read it.**
>
> **What resolved it.** Step 1 below was executed in §2.2 of this document: `enum Tier` and `enum RobotMode` are now declared in `org.rootstock.core.spi`. Telemetry retains ownership of their semantics — §2.2 is still the sole definition site — and only the package moved. Step 2 stands as written: `Demotable` did **not** move. Step 3 — the sibling-document import fix — was **checked rather than assumed** and is **satisfied**: `design/01` had already applied the move on its own side, and `design/02`/`03`/`05`/`06` name `Tier` unqualified with no import line, so a package move does not reach them. The grep, its exact command and the correction to this note's own blast-radius estimate are recorded in **§12 item 13**. **Within the surface this document owns, rule 9 is green: no `org.rootstock.core` or `org.rootstock.core.spi` public signature declared in this file names an `org.rootstock.telemetry` type.** What this document does **not** claim is that `DESIGN.md` §16 item 11 is therefore closed — that item is scored by the ArchUnit rule over the whole design, and closing it is the tracker's call, not this document's.
>
> ---
>
> **RESIDUE, NAMED RATHER THAN CLAIMED CLOSED — the move fixes `RootstockLifecycle`'s signature and does NOT yet make rule 9 green.** *(This was true of the `LogConfig`-only move; it is what the resolution above answers.)* Two of `LogConfig`'s thirteen fields are typed by enums this document declares in `org.rootstock.telemetry`: `minimumTier` is a `Tier` and `mode` is a `RobotMode` (§2.2). A `core.spi` type importing a `telemetry` type is **the same arrow, one level down** — `core.spi → telemetry` is still an arrow out of core, and it is the same violation the move was made to fix. Moving `LogConfig` alone converts one rule-9 finding into a smaller one; it does not eliminate it, and saying otherwise would be exactly the kind of false closure this document's correction log exists to prevent.
>
> **Why it was left open rather than resolved in that pass.** The mechanical fix is obvious — `Tier` and `RobotMode` are behaviorless enums with no dependencies, which is precisely D26's *"types that cross a layer boundary downward"*, so they follow `LogConfig` into `org.rootstock.core.spi`. What made it not this document's call to make unilaterally *at that time* was blast radius: `Tier` is named in `design/01` §1.1b's five `extra(...)` declarations, in `design/02`'s tunable tiering, in `design/03` §3.3's vision keys, in `design/05`'s drive block and in `design/06`'s health publishing, and a package move that five documents have to absorb is a coordinated edit, not a footnote. *(That coordination has since been scheduled explicitly, which is what unblocked step 1; the blast radius did not shrink, it was budgeted.)*
>
> **Exact remaining work, so the next pass does not have to re-derive it:**
> 1. ~~Move `enum Tier` and `enum RobotMode` from `org.rootstock.telemetry` to `org.rootstock.core.spi`~~ — **DONE 2026-08-08, §2.2.** Declaration site is §2.2 of this document, so this document owned the move.
> 2. `Demotable` does **not** move: it is never named in a core signature (`processInputs` has no `Demotable` overload, §2.2a), so it stays in telemetry and rule 9 is indifferent to it. **Unchanged and still correct.**
> 3. Update the `import` lines in `design/01` §1.1b, `design/02`, `design/03` §3.3, `design/05` and `design/06` in one commit. **CHECKED 2026-08-08 — SATISFIED, and the estimate above was wrong in a way worth recording.** `design/01` had already moved its four import sites to `core.spi` under its own amendment; the other four documents name `Tier` **unqualified**, in prose and key tables, with no import line and no package spelling, so there was nothing in them for a package move to break. The predicted "five documents have to absorb it" was true of one. Command and counts in **§12 item 13**.
> 4. Only then does `DESIGN.md` §16's rule-9 item close. **The declaration side is green and the reference side is verified clean, within `design/`.** Closing §16 item 11 is still the tracker's call and not this document's: the rule is scored by ArchUnit over the whole design, and a domain document asserting its own gate green is exactly the false-closure pattern this log exists to catch.

**Rejected alternative, recorded so it is not re-proposed: a narrow named allowlist for rule 9.** The obvious cheaper fix was to add `Tier` and `RobotMode` to a rule-9 exception list rather than move them. It was rejected on one ground: an allowlist that grows once grows again, and the entire value of rule 9 is that it is mechanically checkable with no judgment calls. A rule with two named exemptions is a rule a reviewer has to argue about; a rule with none is a rule the build enforces.

Thirteen fields, thirteen `with*()` copies, two factories, zero setters. Call sites read exactly as `design/01` §1.1a already writes them:

```java
super(LogConfig.defaults().withWpilogFolder("/U/logs").withCtreSignalLogger(true));
// or, in an existing repo that already called Logger.start():
m_rootstock = RootstockLifecycle.create(LogConfig.adoptExistingLogger());
```

> **SUPERSEDED, 2026-08-08 — recorded rather than deleted, because the mutable form is what a reader will re-propose the first time thirteen `with*()` methods look verbose.** Revisions 1–6 of this section declared `LogConfig` as a `public final class` of **public mutable fields** in `org.rootstock.telemetry`, configured by `RootstockLog.configure(java.util.function.Consumer<LogConfig> f)` — the caller received the live instance and assigned into it. The mutable form is genuinely shorter to write and shorter to read. **It lost on three counts, none of them aesthetic:** (1) `DESIGN.md` **D13a** is binding and later, and D1a/principle 6 forbid mutable config everywhere else in the library — one exception in the *front-door* type is the worst possible place for it; (2) a `Consumer` entry point has no way to express D29's two factories, so `adoptExistingLogger()` had nowhere to live and the double-`Logger.start()` crash had no guard; (3) `RootstockLifecycle.create(LogConfig)` takes the value **by argument** (D29), so a `Consumer`-mutated object and a constructor argument are two different wiring stories for the same field set. `design/01` §1.1a flagged the divergence against D13a rather than papering over it and filed it as its OQ17; this block is the answer, and the divergence note in `design/01` §1.1a is now the stale side.

**A team that wants the old ergonomics has it already.** `LogConfig.defaults()` is the whole default set, and a chain of `with*()` calls on one line is the same keystroke count as a lambda body. Nothing was taken away except the ability to mutate the object after `RootstockLifecycle` has read it — which was never a feature.

```java
// org.rootstock.telemetry.RootstockLog. LogConfig, RobotMode and Tier are all in
// org.rootstock.core.spi (§2.2, §2.2b) and are written fully qualified here so the
// direction of the arrow -- telemetry -> core, never the reverse -- is visible at a glance.
public final class RootstockLog {
  private RootstockLog() {}

  /** Call exactly once, from RootstockLifecycle.create(LogConfig), BEFORE any hardware is
   *  constructed. Takes the IMMUTABLE value by argument (§2.2b) -- there is no
   *  Consumer<LogConfig> overload and no mutation-after-the-fact path. RootstockLifecycle is
   *  the only caller in the library; a team wiring manually calls create(...) and never
   *  reaches this method directly. */
  public static void configure(org.rootstock.core.spi.LogConfig config);

  /** Starts Logger -- UNLESS the config came from LogConfig.adoptExistingLogger(), in which
   *  case Logger.start() has already run in the team's code and calling it again is a crash
   *  at boot. Idempotent for that reason. After this, metadata() is closed. */
  public static void start();

  public static org.rootstock.core.spi.RobotMode mode();

  /** The configuration this facade is running under. Read-only by construction: LogConfig is
   *  immutable, so handing it back cannot let a caller reconfigure a started Logger. */
  public static org.rootstock.core.spi.LogConfig config();

  /** Write-once provenance. Must be called between configure() and start(). */
  public static void metadata(String key, String value);

  /** The driver mirror's NT root, "/Rootstock". This is a NetworkTables handle for the
   *  dashboard contract in §6.2 -- it is NOT a logging escape hatch. */
  public static edu.wpi.first.networktables.NetworkTable ntRoot();

  // ---------- The two predicates DESIGN.md D9 names on this facade ----------

  /** SECONDS, from the AdvantageKit-injected clock. Identical to compat.Clock.now() and to
   *  edu.wpi.first.wpilibj.Timer.getTimestamp(); exposed here so a domain that already
   *  imports RootstockLog does not also have to import Clock. Guarantee G2 applies: this is
   *  the ONLY time source any Rootstock control path may read.
   *
   *  Note the unit difference from AdvantageKit: Logger.getTimestamp() returns a `long` in
   *  MICROSECONDS (verified against Logger.java). This returns a `double` in SECONDS,
   *  matching every WPILib control API. The two are the same instant. */
  public static double timestamp();

  /** True iff a replay source is installed, i.e. mode() == RobotMode.REPLAY.
   *  Delegates to Logger.hasReplaySource() (verified: public static boolean, Logger.java). */
  public static boolean isReplay();

  /** True iff DEBUG-tier calls are currently being evaluated -- false once the FMS gate has
   *  raised minimumTier to STANDARD. Reference-typed debug() overloads are supplier-gated
   *  (§2.3), so this predicate is needed only when the *key set itself* is conditional. */
  public static boolean debugEnabled();
}
```

**`RootstockLog.put(...)` does not exist and will not be added.** D9 enumerates this facade as `critical()/log()/debug()/processInputs()/timestamp()/isReplay()` — no `put()` — and a tier-implicit call is exactly how a key ends up in the wrong tier and vanishes on FMS. A `put(k, v)` is `critical(k, v)` or `log(k, v)` with the tier read off §3.1's table.

> **CONTRACT REQUEST DISCHARGED, 2026-08-08 — the history is kept because "just add a `put`" is a recurring request.** This paragraph previously read *"`design/01` §6.2's `periodic()` currently calls `RootstockLog.put(String, double)` … the reconciliation is `design/01`'s to make … Listed as a contract request."* **The "currently" is no longer true.** `design/01` deleted every `put(...)` call site in its revision 5 (its correction rows 42 and 42a): all fifteen CORE call sites now call `critical(...)` or `log(...)`, `timestampSeconds()` was renamed `timestamp()`, and `design/01` §3.4 carries a standing in-code banner reading *"THERE IS NO RootstockLog.put(...). It never existed in the owning document. … A reappearance of `RootstockLog.put` anywhere is a regression."* Verified 2026-08-08: `grep -n "RootstockLog\.put" design/` returns only historical/labeled mentions in `design/01` (rows 42, 42a, the §3.4 banner, and one revision-4 archaeology comment) and this document's own §1.1 prohibition — **zero live call sites.** No contract request remains open here.

**There is no `akit()` escape hatch, and none is needed.** `org.littletonrobotics.junction.Logger` is a public static class on the compile classpath of every Rootstock consumer. Handing back a wrapper object would add indirection to something already globally reachable. The documented escape hatch is one line in the docs: *"`RootstockLog` is a convenience. Call `Logger.recordOutput(...)` directly whenever it is in your way; the two interleave freely and land in the same stream."*

`RootstockLog.dataLog()` is also deleted. It existed to hand out the `DataLog` that the Epilogue and NT4 backends wrote into. AdvantageKit's `WPILOGWriter` owns its own file and there is no `DataLog` to expose.

**Required companion change in doc 06.** ArchUnit rule 1 previously read *"only `rootstock-advantagekit` may import `org.littletonrobotics`."* That artifact no longer exists. The rule becomes **rule 1c** in [`DESIGN.md` §8](../DESIGN.md), which is authoritative, and it has **two clauses** because AdvantageKit enters the library at two depths:

- **(i) driver types** — `Logger`, `LoggedRobot`, `LoggedNetworkNumber`, `LoggedMechanism2d`, `LogFileUtil`, `WPILOGWriter`/`Reader`, `NT4Publisher` — may be imported only from `org.rootstock.telemetry`, `org.rootstock.core` (the `RootstockRobot`/`RootstockLifecycle` root), `org.rootstock.tuning` (the `LoggedNetworkNumber` backing of `TunableDouble`), and `org.rootstock.viz` (the `LoggedMechanism2d` path). Everywhere else in the library still goes through `RootstockLog`.
- **(ii) schema types** — `LogTable` and `LoggableInputs` only — are additionally legal in **any `..io..` package**, because §2.4 makes every `*Inputs` class in the library implement `LoggableInputs` directly. Without this clause `MotorInputs`, `VisionCameraIOInputs` and the drive inputs are all rule violations on day one.

The rule got weaker, not stronger, and that is a real loss of enforcement: previously the compiler could not even see AdvantageKit from most of the library.

### 2.3 The value API

Three tiers × the value types AdvantageScope can actually render. Nothing else. This is unchanged by Decision 3 — the overload set was never backend-shaped. **It is written out in full below rather than elided**, because §1.1.0 makes this the *only* way any Rootstock domain publishes anything, and "…full set as above…" is not a contract another document can compile against.

**Fifteen value shapes.** Every shape exists as `critical(...)` (CRITICAL tier) and `log(...)` (STANDARD tier); every one of those thirty has a **trailing-`Demotable` twin**; nine have a supplier-shaped `debug(...)`. Parameter order mirrors AdvantageKit's `Logger` exactly — in particular **`Struct<T>` comes before the value**, because `Logger.recordOutput(String, Struct<T>, T)` does (verified in `Logger.java`), and a facade that reverses its delegate's argument order is a transcription bug waiting to happen.

```java
package org.rootstock.telemetry;

import edu.wpi.first.units.Unit;
import edu.wpi.first.units.Measure;
import edu.wpi.first.util.struct.Struct;
import edu.wpi.first.util.struct.StructSerializable;
import edu.wpi.first.util.WPISerializable;
import java.util.function.*;

public final class RootstockLog {

  // ======================= CRITICAL — always present, incl. on FMS =======================
  public static void critical(String key, boolean v);
  public static void critical(String key, long v);
  public static void critical(String key, double v);
  public static void critical(String key, double v, Unit unit);
  public static void critical(String key, String v);
  public static <E extends Enum<E>> void critical(String key, E v);
  public static void critical(String key, boolean[] v);
  public static void critical(String key, long[] v);
  public static void critical(String key, double[] v, Unit unit);
  public static void critical(String key, String[] v);
  public static <U extends Unit> void critical(String key, Measure<U> v);
  public static <T extends WPISerializable> void critical(String key, T v);
  public static <T extends StructSerializable> void critical(String key, T[] v);
  public static <T> void critical(String key, Struct<T> struct, T v);
  public static <T> void critical(String key, Struct<T> struct, T[] v);

  // ======================= STANDARD — the default tier, named log(...) ===================
  public static void log(String key, boolean v);
  public static void log(String key, long v);
  public static void log(String key, double v);
  public static void log(String key, double v, Unit unit);
  public static void log(String key, String v);
  public static <E extends Enum<E>> void log(String key, E v);
  public static void log(String key, boolean[] v);
  public static void log(String key, long[] v);
  public static void log(String key, double[] v, Unit unit);
  public static void log(String key, String[] v);
  public static <U extends Unit> void log(String key, Measure<U> v);
  public static <T extends WPISerializable> void log(String key, T v);
  public static <T extends StructSerializable> void log(String key, T[] v);
  public static <T> void log(String key, Struct<T> struct, T v);
  public static <T> void log(String key, Struct<T> struct, T[] v);

  // ======================= Demotable twins — the parameter is ALWAYS last ================
  // Omitting the parameter means Demotable.NO. There is no way to make a key demotable by
  // accident, and no way for the governor to demote a key the author did not mark.
  // NOTE: there is deliberately NO processInputs(..., Demotable) overload. See §2.2a.
  public static void critical(String key, boolean v, Demotable d);
  public static void critical(String key, long v, Demotable d);
  public static void critical(String key, double v, Demotable d);
  public static void critical(String key, double v, Unit unit, Demotable d);
  public static void critical(String key, String v, Demotable d);
  public static <E extends Enum<E>> void critical(String key, E v, Demotable d);
  public static void critical(String key, boolean[] v, Demotable d);
  public static void critical(String key, long[] v, Demotable d);
  public static void critical(String key, double[] v, Unit unit, Demotable d);
  public static void critical(String key, String[] v, Demotable d);
  public static <U extends Unit> void critical(String key, Measure<U> v, Demotable d);
  public static <T extends WPISerializable> void critical(String key, T v, Demotable d);
  public static <T extends StructSerializable> void critical(String key, T[] v, Demotable d);
  public static <T> void critical(String key, Struct<T> struct, T v, Demotable d);
  public static <T> void critical(String key, Struct<T> struct, T[] v, Demotable d);

  public static void log(String key, boolean v, Demotable d);
  public static void log(String key, long v, Demotable d);
  public static void log(String key, double v, Demotable d);
  public static void log(String key, double v, Unit unit, Demotable d);
  public static void log(String key, String v, Demotable d);
  public static <E extends Enum<E>> void log(String key, E v, Demotable d);
  public static void log(String key, boolean[] v, Demotable d);
  public static void log(String key, long[] v, Demotable d);
  public static void log(String key, double[] v, Unit unit, Demotable d);
  public static void log(String key, String[] v, Demotable d);
  public static <U extends Unit> void log(String key, Measure<U> v, Demotable d);
  public static <T extends WPISerializable> void log(String key, T v, Demotable d);
  public static <T extends StructSerializable> void log(String key, T[] v, Demotable d);
  public static <T> void log(String key, Struct<T> struct, T v, Demotable d);
  public static <T> void log(String key, Struct<T> struct, T[] v, Demotable d);

  // ======================= DEBUG — dropped when FMS-attached ============================
  // Primitives take values (free to evaluate). Reference types take SUPPLIERS so the value
  // is never constructed when the tier is off. This makes the classic AdvantageKit CPU trap
  // -- "the logger statements were instantiating new Translation2ds every cycle" -- impossible.
  // DEBUG keys are never demotable: they are already gone before the governor can matter.
  public static void debug(String key, boolean v);
  public static void debug(String key, long v);
  public static void debug(String key, double v);
  public static void debug(String key, double v, Unit unit);
  public static void debug(String key, BooleanSupplier v);
  public static void debug(String key, LongSupplier v);
  public static void debug(String key, DoubleSupplier v, Unit unit);
  public static void debug(String key, Supplier<String> v);
  public static <T> void debug(String key, Struct<T> struct, Supplier<T> v);
  public static <T> void debug(String key, Struct<T> struct, Supplier<T[]> v);

  /** Explicit decimation. Delegates to Logger.runEveryN(int, Runnable)
   *  (verified: public static void, Logger.java). Unrelated to Demotable: everyN is the
   *  AUTHOR deciding, at the call site, forever; Demotable is the GOVERNOR deciding, at
   *  runtime, reversibly, and only under sustained overload. */
  public static void everyN(int n, Runnable r);
}
```

**Two shapes that look redundant and are not.** `critical(String, T)` for `T extends WPISerializable` is how every WPILib geometry type is logged (`Pose2d`, `Pose3d`, `ChassisSpeeds`, `SwerveModuleState`) without the caller naming a `Struct`; the explicit `critical(String, Struct<T>, T)` form exists for a struct Rootstock does not control. Both delegate to the matching `Logger.recordOutput` overload, and both resolve unambiguously because no WPILib geometry type is also a `String`, an `Enum`, or an array of primitives.

**One shape that carries a name change.** `critical(String, Measure<U>)` delegates to **`Logger.recordOutputMeasure(String, Measure<U>)`** — *not* `recordOutput`. AdvantageKit gave the `Measure` overload a distinct name, and the previous revision of §2.8 mapped it to `recordOutput`. Verified against [`Logger.java`](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/akit/src/main/java/org/littletonrobotics/junction/Logger.java).

Note `everyN`: this used to read *"wraps `Logger.runEveryN` when available and implements the same counter otherwise."* The "otherwise" branch is deleted. Several small pieces of this facade shrink the same way — the `Optional`s, the availability checks, the fallback counters — and collectively that is where most of the saving in §13.2 comes from.

**Why flat scalars and WPILib structs, not a Rootstock struct-per-mechanism:**

1. AdvantageScope's 2026 unit-aware Line Graph works on scalars with unit metadata. A packed custom struct is one opaque blob on that tab.
2. Elastic's struct-field display is **read-only and does not support arrays**, so a struct cannot back a driver widget.
3. AdvantageKit's docs mark first-log-of-a-new-struct/protobuf/record as a **>100 ms blocking hazard** and mandate doing it while disabled. Every custom struct we ship is one more landmine at match start.
4. Records must be globally uniquely named or they collide, and cannot contain arrays.

Geometry is different: `Pose2d`, `Pose3d`, `Transform3d`, `ChassisSpeeds`, `SwerveModuleState`, `Rotation2d` already have WPILib structs that AdvantageScope decodes natively and that carry native unit information. **Use those, always.** The AdvantageScope docs are explicit that struct publishing is the best way to publish geometry, and field-name-suffix inference is a third-choice fallback.

### 2.4 IO inputs — `LoggableInputs` directly

Replay requires that everything read from hardware passes through a logged inputs struct. Under Decision 3 that struct is **AdvantageKit's own `LoggableInputs`**. Rootstock's `@AutoInputs` annotation, its annotation processor, the `RootstockInputs` interface and the `LogSink`/`LogSource` pair are all **deleted**. They existed for exactly one reason — to let an inputs class work identically with and without AdvantageKit — and that reason is gone.

```java
// A Rootstock IO layer. Note: no Rootstock annotation, no generated mirror type.
public interface ElevatorIO {
  class ElevatorInputs implements LoggableInputs {
    public boolean[] connected = new boolean[2];
    public double positionMeters = 0.0;
    public double velocityMetersPerSec = 0.0;
    public double[] appliedVolts = new double[2];
    public double[] supplyCurrentAmps = new double[2];
    public double[] statorCurrentAmps = new double[2];
    public double[] tempCelsius = new double[2];
    public boolean limitForward = false;
    public boolean limitReverse = false;

    @Override public void toLog(LogTable table) {
      table.put("Connected", connected);
      table.put("PositionMeters", positionMeters);
      table.put("VelocityMetersPerSec", velocityMetersPerSec);
      table.put("AppliedVolts", appliedVolts);
      table.put("SupplyCurrentAmps", supplyCurrentAmps);
      table.put("StatorCurrentAmps", statorCurrentAmps);
      table.put("TempCelsius", tempCelsius);
      table.put("LimitForward", limitForward);
      table.put("LimitReverse", limitReverse);
    }

    @Override public void fromLog(LogTable table) {
      connected = table.get("Connected", connected);
      positionMeters = table.get("PositionMeters", positionMeters);
      velocityMetersPerSec = table.get("VelocityMetersPerSec", velocityMetersPerSec);
      appliedVolts = table.get("AppliedVolts", appliedVolts);
      supplyCurrentAmps = table.get("SupplyCurrentAmps", supplyCurrentAmps);
      statorCurrentAmps = table.get("StatorCurrentAmps", statorCurrentAmps);
      tempCelsius = table.get("TempCelsius", tempCelsius);
      limitForward = table.get("LimitForward", limitForward);
      limitReverse = table.get("LimitReverse", limitReverse);
    }
  }
  default void updateInputs(ElevatorInputs inputs) {}
  default void setVoltage(double volts) {}
}

// In the subsystem, once per cycle, ALWAYS as the first two statements:
io.updateInputs(inputs);
RootstockLog.processInputs("Rootstock/Elevator", inputs);   // == Logger.processInputs
// After this line, read ONLY from `inputs`. Never call io.getX(). This is the rule that
// makes replay byte-identical (6328's stated invariant).
```

```java
public final class RootstockLog {
  /** Delegates to Logger.processInputs(String, LoggableInputs). In REPLAY mode the inputs
   *  are read back FROM the log instead of from hardware. There is no second code path.
   *
   *  There is NO Demotable overload and there never will be (§2.2a). Replayed inputs are
   *  structurally outside the governor's reach.
   *
   *  Implementation note that matters for §2.7: this method does not pass `inputs` straight
   *  through. It passes a per-key SizingInputs wrapper whose toLog(LogTable t) calls
   *  inputs.toLog(t) and then, on the attribution cycle only, enumerates t.getAll(true) to
   *  attribute bytes per field. The wrapper is allocated once per key at first call, never
   *  per cycle, and its fromLog is a straight delegate so REPLAY costs nothing. */
  public static void processInputs(String key, LoggableInputs inputs);

  /** Every key registered through processInputs this session, insertion-ordered (G7).
   *  The runtime tripwire (§2.6) asserts exactly one processInputs call per entry per cycle. */
  public static java.util.List<String> inputKeys();
}
```

`RootstockLog.processInputs` survives as a thin alias rather than being deleted outright, for one reason worth stating: it is the hook the runtime tripwire (§2.6) counts, and counting calls that go straight to `Logger` would require bytecode inspection. The lint therefore requires Rootstock-registered IO layers to call `RootstockLog.processInputs`, and documents `Logger.processInputs` as equivalent-but-uncounted.

#### 2.4.1 `@AutoLog` / `@AutoLogOutput` — D24 stands, and here is the verified reason

The task brief asked this to be re-verified rather than assumed, because the prior justification was a package-scope claim. Verified against AdvantageKit's current documentation, 2026-08-07:

| Question | **Verified answer** |
|---|---|
| Does `@AutoLog` work outside `frc.robot`? | **Yes.** There is no package restriction. The processor generates `<Name>AutoLogged` **into the annotated type's own package** — the docs' own example is `frc/lib/example/MyInputs.java` producing `MyInputsAutoLogged`. A class in `org.rootstock.hardware` would generate `org.rootstock.hardware.MotorInputsAutoLogged`. |
| Does `@AutoLogOutput` work outside `frc.robot`? | **Only conditionally.** The docs state the parent class "must be within the same package as `Robot` (or a subpackage)." `AutoLogOutputManager.addPackage("org.rootstock")` or `addObject(this)`, called from `Robot`'s constructor, lifts the package restriction. |
| Is that sufficient for Rootstock? | **No.** The docs add a second, independent requirement: the parent class must be "instantiated within the first loop cycle and be accessible by a **recursive search of the fields of `Robot`**," and they explicitly recommend `Logger.recordOutput` for classes that do not meet it. Rootstock's loggable objects live in `RootstockRegistry` and in static registries, reached from `RootstockLifecycle`, not as reachable fields of the team's `Robot`. Several are constructed lazily. `addObject(this)` would have to be called per-object at registration time, from library code, for every registered object — which is strictly more code than the `recordOutput` call it replaces. |

So the two halves of D24 now have **different** verified statuses, and it is worth being precise instead of restating the old rationale:

- **The package-scope objection to `@AutoLog` is NOT valid.** It was wrong. `@AutoLog` works fine in `org.rootstock.*`, and the *other* historical objection — "a vendordep cannot add an `annotationProcessor` line to a consumer's `build.gradle`" — never applied to Rootstock's **own** build, which is ours to write. Both of the originally stated reasons for banning `@AutoLog` inside the library fail on inspection.
- **The package-scope objection to `@AutoLogOutput` IS valid**, for the reachability reason above, and AdvantageKit's own docs prescribe the alternative we already use.

**D24 is nonetheless kept, on two reasons that survive scrutiny**, and it is right to record that these are *replacement* reasons, not the original ones:

1. **The log schema is a published contract and must not be generated by a third party.** §3 documents ~140 key names; `RootstockReplayVerify`, the four shipped Elastic layouts, the three AdvantageScope layouts, `CycleStats` and the triage manifest all key off them by literal string. `@AutoLog` derives keys from Java field names via a processor we do not own and do not version. A field rename, or a change in AdvantageKit's name-mangling, silently renames a contract key. Hand-written `toLog`/`fromLog` makes the schema ours and lets a single test assert it (§8.3 `publishesTheStandardSchema`).
2. **It removes a build-order coupling in M12.** `akit-autolog` would have to be on the library's own `annotationProcessor` path for every compilation, including the 2027 dual-compile CI that builds the same sources twice against two WPILib lines. That is a place where a generated-source variant plus a second annotation processor interact, and M12 is already the most expensive milestone in the plan at 8.0 pw.

Both reasons are weaker than "it does not work," and honesty requires saying so: **D24 is now a judgment call about schema ownership, not a technical impossibility.** If a future maintainer decides the boilerplate cost of hand-written `toLog`/`fromLog` across `MotorInputs`, `VisionCameraIOInputs`, `GyroInputs`, `DigitalSensorInputs` and `AbsoluteEncoderInputs` exceeds the schema-ownership benefit, reversing D24 for inputs is a legitimate call and is now technically unblocked. It is not reversed here.

**What Decision 2 changes:** `RootstockTemplate`'s `build.gradle` is ours to write, so the template ships the `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitVersion"` line pre-wired **for team code**. A team that forks the template can annotate its own IO layers with `@AutoLog` on day one and never hand-write a `toLog`. That is a template feature, not a library feature, and the distinction is the whole point of D24. A team consuming Rootstock as a bare dependency adds one line themselves; `docs/UPDATING.md` says so.

### 2.5 Replay: the guarantee, and the user-code contract

This section replaces the old "what deterministic replay requires of user code." It now has two halves, because under Decision 3 the library's half is a **guarantee** and only the user's half is a **contract**.

#### 2.5A What Rootstock guarantees

These hold for **all Rootstock code**, unconditionally, with no configuration. Each one is enforced on the library itself, in the library's own CI, by the mechanism named.

> **Naming note, because the collision is real.** `G1`–`G9` here are **replay guarantees**, not release gates. The five *dated* release gates that used to be called G0–G5 (`G0` Sep 5, `G1` Oct 10, `G2` Nov 7, `G3` Nov 28, `G4` Dec 6, `G5` Dec 20) are **deleted** by maintainer decision 1 and replaced by the capability-defined milestones M1–M24 in [`ROADMAP.md` §5](../ROADMAP.md). Any "G*n*" in this document means a guarantee below; any "M*n*" means a milestone. Nothing in this document set uses the old dated-gate names any more.

| # | Guarantee | Enforced by |
|---|---|---|
| G1 | Every hardware read in Rootstock passes through a `LoggableInputs` struct consumed by `processInputs`. No Rootstock class outside an IO implementation calls a vendor getter. | ArchUnit rule (doc 06): no class outside `org.rootstock.*.io..` may depend on `com.ctre..`, `com.revrobotics..`, `org.photonvision..`, or `LimelightHelpers`. |
| G2 | All Rootstock time comes from `compat.Clock.now()` → `Timer.getTimestamp()`, the AdvantageKit-injected clock. (Also reachable as `RootstockLog.timestamp()`, §2.2.) | **ArchUnit rule 3** — *"No `Timer.getFPGATimestamp()` and no `new Timer()` anywhere"* ([`DESIGN.md` §8](../DESIGN.md)). The previous revision cited "rule 11"; rule 11 is the no-`throw` rule and has nothing to do with clocks. Rule 3 must additionally name `RobotController.getFPGATime()` / `getMeasureFPGATime()` and `System.currentTimeMillis` / `System.nanoTime` / `Instant.now`, with `RootstockTracer` as the sole allowed caller of the first pair — see G3. |
| G3 | `RootstockTracer` is the **single** exception to G2 and reads the un-injected hardware clock deliberately, because it must report real wall time or a 50× replay reports absurd loop times. It is annotated `@ReplayExempt("tracer measures real wall time by design")` and its output topics are in `RootstockReplayVerify`'s default ignore set. | The exemption file, reviewed at M24, **plus** the rule-3 clause above. |
| G4 | **Every value that can affect control and does not come from hardware still arrives through a replayed input channel.** No Rootstock class constructs `Random`, calls `UUID.randomUUID()`, or opens a socket from a periodic path. Filesystem reads happen at construction only **and their values are re-published through `processInputs` before any consumer reads them** — see §2.5A.1, which is a real mechanism, not a logging convention. | `rootstock-lint` R3 run over the library's own sources + the M24 zero-allocation gate + `replayIgnoresLocalConfigFiles` (§5.6). |
| G5 | No Rootstock class starts a thread except the swerve odometry thread, which lives inside drive IO; its samples enter control **only** through `processInputs`, timestamped with the FPGA↔Phoenix conversion specified in doc 05. | `rootstock-lint` R5 run over the library's own sources + code review at M9. **There is no ArchUnit thread rule** among the twelve in [`DESIGN.md` §8](../DESIGN.md); the previous revision cited rule 9, which is the `core..` layering rule (D26). Naming a rule that does not enforce the thing it is cited for is how a guarantee becomes decorative. |
| G6 | Every Rootstock value read from NetworkTables that can affect control goes through `TunableDouble` / `TunableBoolean` / `TunableGains`, which are backed by AdvantageKit `LoggedNetworkNumber` / `LoggedNetworkBoolean` under `/Tuning` and are therefore **recorded and replayed**. Replay reads the match's tuning values, not today's dashboard. | Doc 02 at M6 + `rootstock-lint` **R6** (`NetworkTableInstance` reads outside `org.rootstock.tuning` and `org.rootstock.dashboard`). Rule 4 in `DESIGN.md` §8 bans `SmartDashboard`/`Shuffleboard`/NT3, **not** `NetworkTableInstance`, so ArchUnit does not cover this and the lint does. |
| G7 | Iteration order inside Rootstock is deterministic. `RootstockRegistry`, `AlertRegistry`, `TuningRegistry`, the health-monitor slice list, the registered `TelemetrySource` list and the demotable set are insertion-ordered (`LinkedHashMap` / `ArrayList`). No `HashMap`/`HashSet` iteration affects an output. | `rootstock-lint` R4 (warning) + code review + a `RootstockTest` assertion that two runs of the reference robot produce identical output ordering. Not an ArchUnit rule — order-dependence is not provable from types, which is exactly why R4 is a warning and not an error. |
| G8 | Health monitors count **cycles**, not wall-clock time, so a monitor's verdict is the same at 1× and 50×. | Doc 06; asserted by `RootstockTest`. |
| G9 | The governor's decisions are excluded from replay comparison, and its *boundary structure* is required to match (§5.6). A governor that demotes differently on replay is a defect and fails CI. | `RootstockReplayVerify` step 3. |

**The standing gate.** A WPILOG written by a `simulateJava` run replays **byte-identically** through `Logger` in replay mode. This is M1's gate and it is **re-run as a gate at M5, M9, M11 and M24** — the four milestones that add new IO surface. It is the single check that keeps every guarantee above true rather than aspirational. **From this revision it is run twice at each of those milestones: once nominally, and once with the governor forced over budget in sim** (§2.2a), because a demotable-input regression is invisible to the nominal run.

#### 2.5A.1 G4, corrected: logging a file's value is not the same as replaying it

The previous wording of G4 said config file reads *"happen at construction only, and their **values** are logged, so a replay sees what the match saw."* **That is false, and the review was right to call it out.** Logging a value as an *output* makes it **visible** in replay; it does not make replay **use** it. In `REPLAY` the constructor runs again on a developer's laptop and re-reads the developer's copy of the file, which may differ from the robot's copy at match time. AdvantageKit replays **inputs** and recomputes **outputs**; a value that reaches control through a constructor has no input channel and is therefore simply re-read.

Two of the three file-derived values in the library were accidentally safe and one was not:

| Value | Path into control | Replay-safe before this fix? |
|---|---|---|
| `gains.json` gains | `TunableGains` → `LoggedNetworkNumber` under `/Tuning` | **Yes**, by G6 — `LoggedNetworkNumber` *is* a replayed input. Accidental, not designed. |
| `TunedValueStore` setpoints | registered under `/Tuning/<Mech>/Setpoints/<NAME>` per [`DESIGN.md` §5.6](../DESIGN.md), so also `LoggedNetworkNumber` | **Yes**, same accident. |
| `transition_costs.json` | read at boot → `plannedTransitionSeconds` → `AutoStep.budget` defaults (`design/01` §8.8) | **No.** It changes *control flow* — which auto steps time out — with no replayed-input path at all. |

**The mechanism: `RootstockConfig`, a one-shot replayed input channel.** Every file- or environment-derived value that can affect control is registered through it, and the read happens exactly once, in `RootstockLifecycle`, **before any user subsystem is constructed**.

```java
package org.rootstock.telemetry;

/** The boot-time replayed input channel for file-derived control values (G4).
 *
 *  Registration order is insertion order (G7). Every registered value is written into ONE
 *  LoggableInputs struct, pushed through processInputs("Rootstock/Config", ...) in the first
 *  cycle, and read back FROM THE LOG in REPLAY. A mutated file on a developer's laptop
 *  therefore cannot change a replayed output -- which is the entire point, and is asserted
 *  by replayIgnoresLocalConfigFiles (§5.6). */
public final class RootstockConfig {
  /** Registers a file-derived double. `fileRead` is invoked at most once, during load(),
   *  and never in a periodic path. Returns the value the RUN saw: the file's value on a
   *  real/sim run, the LOGGED value in REPLAY. */
  public static double registerDouble(String key, DoubleSupplier fileRead, double fallback);
  public static String registerString(String key, Supplier<String> fileRead, String fallback);
  public static boolean registerBoolean(String key, BooleanSupplier fileRead, boolean fallback);

  /** Called by RootstockLifecycle exactly once, after RootstockLog.start() and before any
   *  user subsystem is constructed. Reads every registered supplier (best-effort: a missing
   *  or malformed file yields the fallback and a named Alert), then calls
   *  RootstockLog.processInputs("Rootstock/Config", INPUTS). After this returns, every
   *  register*() call above returns the replayed value. Calling register*() after load()
   *  is a FATAL ConfigError -- a late registration would be a value replay cannot supply. */
  public static void load();

  /** SHA-256 of the concatenated raw file bytes behind every registered value, logged as
   *  Rootstock/Config/Inputs/ContentHash. If a replay's local files differ from the match's,
   *  this hash differs -- and because it is an INPUT, the replayed value is the match's,
   *  so the report says "your local config differs from the match's; the match's was used"
   *  instead of silently diverging. */
  public static String contentHash();

  static final class ConfigInputs implements LoggableInputs {
    public String[] doubleKeys   = new String[0];
    public double[] doubleValues = new double[0];
    public String[] stringKeys   = new String[0];
    public String[] stringValues = new String[0];
    public String[] boolKeys     = new String[0];
    public boolean[] boolValues  = new boolean[0];
    public String contentHash    = "";

    @Override public void toLog(LogTable t) {
      t.put("DoubleKeys", doubleKeys);   t.put("DoubleValues", doubleValues);
      t.put("StringKeys", stringKeys);   t.put("StringValues", stringValues);
      t.put("BoolKeys", boolKeys);       t.put("BoolValues", boolValues);
      t.put("ContentHash", contentHash);
    }
    @Override public void fromLog(LogTable t) {
      doubleKeys = t.get("DoubleKeys", doubleKeys);   doubleValues = t.get("DoubleValues", doubleValues);
      stringKeys = t.get("StringKeys", stringKeys);   stringValues = t.get("StringValues", stringValues);
      boolKeys   = t.get("BoolKeys", boolKeys);       boolValues   = t.get("BoolValues", boolValues);
      contentHash = t.get("ContentHash", contentHash);
    }
  }
}
```

Parallel `String[]`/`double[]` arrays rather than a map: `LogTable` has no map type, the arrays round-trip through WPILOG natively, and the ordering is registration order, which G7 already fixes. The struct is written **once** — WPILOG's change detection means it costs its full size on cycle 1 and zero bytes thereafter — so the whole channel is free after boot.

**What `design/01` must change** (contract request, not made here): `transition_costs.json` is no longer read directly. `plannedTransitionSeconds(from, to)` reads `RootstockConfig.registerDouble("Superstructure/TransitionSec/" + from + "_" + to, () -> costsFile.get(from, to), DEFAULT_TRANSITION_SEC)`. Any other boot-time file read in any domain that reaches a control decision takes the same route; `rootstock-lint` R3 already errors on filesystem access outside `@IoImplementation`, and `RootstockConfig` is the sanctioned, annotated home for it.

#### 2.5B What user code must still avoid

The library's guarantee does not extend to code the library did not write. These are the rules, each stated as a rule, its failure mode, and how Rootstock catches it.

| # | Rule | If you break it | Enforcement |
|---|---|---|---|
| R1 | Every hardware read goes through a `LoggableInputs` struct via `processInputs`. Subsystems read only from that struct. | Replay silently re-reads nothing and produces wrong outputs. | Build-time: any call to a known vendor getter (`TalonFX.getPosition`, `SparkMax.getEncoder`, `PhotonCamera.getAllUnreadResults`, `LimelightHelpers.*`, `DriverStation.getJoystick*`) outside a class named `*IO*` or annotated `@IoImplementation` is a **build error**. |
| R2 | Time comes from `Timer.getTimestamp()` (or `RootstockLog.timestamp()`), never `Timer.getFPGATimestamp()`, `RobotController.getFPGATime()`, `RobotController.getMeasureFPGATime()`, `System.nanoTime()` or `System.currentTimeMillis()` in control logic. | Replay clock and control clock diverge. Profiling numbers become nonsense during 50× replay. | Build-time error outside IO impls and outside `RootstockTracer`. Rootstock's own tracer is the sole exception (G3). **`Timer.getMonotonicTimestamp()` was named here in the previous revision and does not exist** — see G3. |
| R3 | No `Math.random()`, `new Random()`, `System.currentTimeMillis()`, `Instant.now()`, `UUID.randomUUID()`, filesystem reads, or network reads in control logic. | Different outputs on every replay. | Build-time error. Runtime tripwire in `REPLAY` mode raises a fault naming the class. |
| R4 | No iteration over `HashMap` / `HashSet` where iteration order affects output. | Order varies with JVM and hashing; outputs drift. | Build-time **warning** (cannot be proven), plus `RootstockCollections.ordered()` helpers in the docs. |
| R5 | No user threads that mutate control state. The odometry thread is the one sanctioned exception and it lives inside Rootstock's drivetrain IO. | Nondeterministic interleaving. | Build-time error on `new Thread`, `Executors.*`, `Notifier` outside `@IoImplementation`. |
| R6 | No direct NetworkTables reads for values that affect control. Use `TunableDouble` (backed by `LoggedNetworkNumber` under `/Tuning`, therefore recorded and replayed). | Tunables read live NT during replay; you replay against today's dashboard, not the match's. | Build-time error on `NetworkTableInstance`/`SmartDashboard` reads outside `org.rootstock`. |
| R7 | Vendor CAN sidecar loggers (CTRE `.hoot`, URCL, REVLib StatusLogger) are **decorative** with respect to replay. They are recorded for convenience only. | Team assumes a signal in the .hoot is available in replay. It is not. | Configuring `ctreSignalLogger`/`urcl` prints a one-line notice at startup, and the manifest marks those files `replayable: false`. |
| R8 | Large hardware libraries that bypass the IO layer (CTRE `SwerveDrivetrain`, YAGSL) cannot be replayed. | Replay is valid for everything except the drivetrain — the most important thing. | `RootstockLog.configure` raises a **startup Alert** if `com.ctre.phoenix6.swerve.SwerveDrivetrain` or `swervelib.SwerveDrive` is on the classpath. Not an error — a loud, named warning. Note this warning is now **unconditional**, where it used to fire only when a replay-capable backend was selected. |
| R9 | `DriverStation.waitForDsConnection()` is incompatible with AdvantageKit. | Robot hangs or replay breaks. | Build-time error, unconditionally. |
| R10 | The simulation GUI must be disabled for replay. | Replay does not run. | `RootstockLog` sets the `HAL`-side sim GUI off in `REPLAY` mode and logs the fact. |

**One rule was deleted outright.** The old design also had to warn teams that `mode = REPLAY` would be *refused* on three of four backends. That refusal path, its message, its `replayCapable()` predicate and the `Rootstock/Log/Caveats` topic that recorded which features were unavailable are all gone. Nothing replaces them because nothing needs to.

### 2.6 `rootstock-lint` — the replay-safety processor, specified

Previously deferred to "v0.2." Under Decision 1 nothing is deferred, so it is specified here in full and scheduled as **M20 (2.5 pw)**.

**Artifact.** `dev.rootstock:rootstock-lint`, a **javac annotation processor** — the same mechanism `@AutoLog` and Epilogue already use, so it costs no new tooling. Compile-only, never on the robot, never a runtime dependency.

**Wiring, and the honest limitation.** A vendordep **cannot** add an `annotationProcessor` line to a consumer's `build.gradle`. This is the same constraint behind D24 and it has not changed. What *has* changed is Decision 2: **`RootstockTemplate` owns its `build.gradle`**, so the template ships the line pre-wired:

```groovy
// In RootstockTemplate's build.gradle. This is why M20 is deliverable at all.
annotationProcessor "dev.rootstock:rootstock-lint:$rootstockVersion"
```

**Therefore: a team that forked the template gets build-time enforcement. A team that added Rootstock as a bare dependency gets the runtime tripwire only, and must add that one line themselves to get more.** `docs/UPDATING.md` and the adoption matrix both say this explicitly. It is a real hole in the enforcement story and it is not closable from the library side.

**Rule set.** Exactly the ten rules in §2.5B, by identifier, so a diagnostic and the documentation share a name.

| Rule | Severity | Detection |
|---|---|---|
| R1 vendor getter outside an IO class | ERROR | Method-invocation match against a versioned list of vendor getter signatures, shipped as a resource so it can be corrected without a library release. |
| R2 wrong clock | ERROR | Method-invocation match. |
| R3 nondeterminism | ERROR | Constructor + method-invocation match. |
| R4 unordered iteration | **WARNING** | Type-based: enhanced-for over a declared `HashMap`/`HashSet`. Cannot be proven to affect output, so it is never an error. |
| R5 user thread | ERROR | Constructor match on `Thread`, `Executors.*`, `Notifier`. |
| R6 direct NT read for control | ERROR | Method-invocation match outside `org.rootstock`. |
| R9 `waitForDsConnection` | ERROR | Method-invocation match. |

R7, R8 and R10 are runtime or configuration concerns and have no build-time form; the table above is the complete processor rule set.

**The fence below is SAMPLE COMPILER OUTPUT, not Java.** It is text the processor *prints*; the `Timer.getFPGATimestamp()` and `NetworkTableInstance` spellings inside it are the things being **rejected**, quoted so a student recognizes the message. `DESIGN.md` §16 item 3 flags exactly these two lines as the only `design/04` hits its code-shaped `Timer`-confinement grep cannot classify, and asks for "the marker treatment"; this paragraph and the `text`-tagged fence are it — a grep that skips non-`java` fences now classifies them correctly, and no `@ReplayExempt` is needed because there is no call site here.

```text
> Task :compileJava
e: Drive.java:142: [ROOTSTOCK-R2] Timer.getFPGATimestamp() outside an IO implementation
      breaks AdvantageKit replay. Use Timer.getTimestamp(), or move this call into a class
      annotated @IoImplementation, or annotate the method @ReplayExempt("profiling only").
e: Vision.java:88: [ROOTSTOCK-R6] Direct NetworkTableInstance read. Use TunableDouble.of(...)
      so the value is recorded to the log and replayed.
w: Arm.java:12:  [ROOTSTOCK-R4] Iteration over HashMap<String,?> may affect outputs.
      Use LinkedHashMap or RootstockCollections.ordered().
```

**Escape hatches (both required — teams will hit false positives):**
- `@ReplayExempt("reason")` on a method or class, with a **mandatory non-empty reason string**. An empty reason is itself an error. Reasons are collected into `build/rootstock/replay-exemptions.txt` so a mentor can review them, and the file is a CI artifact.
- `rootstock { lint { replaySafety = ReplayLint.OFF | WARN | ERROR } }` in `build.gradle`. **Default is `ERROR`.** The old default was *"`ERROR` when the AdvantageKit backend is configured, `OFF` otherwise"* — the condition is now always true, so the conditional is deleted.

**The gate that makes it shippable (M20).** *A processor with an unknown false-positive rate is not shippable.* Before M20 completes:
- The processor runs clean against **8793's and 9143's real repositories**, in the state they are in at that milestone.
- The **false-positive rate is measured and published** in `docs/lint.md`: number of `@ReplayExempt` annotations required per thousand lines, per rule, on those two repos.
- Every exemption those repos needed is reviewed. If a rule needs more than a handful of exemptions on real code, that rule is downgraded to WARNING before release, not after.

**Runtime tripwire (`REPLAY` mode only).** Cheap, no `SecurityManager` (removed in modern JVMs). On `Logger.start()` in REPLAY mode, Rootstock:
- Seeds a poisoned `java.util.Random` shim it hands out via `RootstockRandom` and faults if the real one is constructed (detected without a `-javaagent`: we simply refuse to provide one, and the lint covers direct construction).
- Records `Thread.activeCount()` at start and faults if it grows.
- Asserts `Logger.getTimestamp()` is monotonically non-decreasing per cycle and that exactly one `processInputs` call occurred per registered IO key per cycle. A missing key means an IO layer was skipped; a duplicate means a subsystem is reading hardware twice.

```java
public final class RootstockReplay {
  /** Installed automatically in REPLAY mode. Public so tests can assert against it. */
  public static void assertDeterministic();
  public static List<String> violations();
}
```

### 2.7 Tiers and the loop-time budget governor

Every documented 2026 AdvantageKit CPU disaster traces to logging *volume*, not logging *design*: Chief Delphi reports of 80–150 ms loops with the stock vision+swerve templates, fixed by deleting log calls, and one team whose actual root cause was leaving `simMode` on. The governor is the feature that lets a small team log aggressively at home without bricking itself at an event.

**Decision 3 raises the stakes here and it is worth saying plainly.** In the prior design, a team whose roboRIO could not keep up had a blunt but real escape: set `Backend.NT4`, lose replay, keep every other feature in this document, and get their loop time back. **That escape no longer exists.** The tiers, the governor and `./gradlew logBudget` are now the *only* levers, and all three are volume levers — none of them reduces AdvantageKit's own fixed per-cycle cost. See §11.3.

#### 2.7.0 Where the byte numbers come from — the estimator, specified

The previous revision published `RootstockBudget.lastCycleBytes()`, `p95CycleBytes()` and `topKeys(n)` with **no stated mechanism**, and the review is right that this makes both the trigger and the attribution unimplementable as written. AdvantageKit's `Logger` exposes **no** per-key byte accounting (verified: [`Logger.java`](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/akit/src/main/java/org/littletonrobotics/junction/Logger.java) has no size, byte-count or statistics method), and `LogValue` exposes **no** size accessor either (verified: [`LogTable.java`](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/akit/src/main/java/org/littletonrobotics/junction/LogTable.java) — `LogValue` has typed getters plus `getWPILOGType()`/`getNT4Type()` and nothing about length). **Rootstock must compute every byte number itself.** Here is how.

**Three buckets, and only two of them are governable.**

| Bucket | What is in it | Measured how | Governable? |
|---|---|---|---|
| **A — Facade** | Every `RootstockLog.critical/log/debug` call | Sized at the call site, from the static type, before delegating | **Yes** |
| **B — Inputs** | Every field of every `LoggableInputs` passed to `processInputs` | Sized by enumerating the `LogTable` the wrapper just wrote (§2.4) | **No** (§2.2a) — sized so it is *visible*, never demoted |
| **C — Framework** | Console capture, DS capture, AdvantageKit's own `SystemStats`/`DriverStation`/`RealMetadata` topics, `CommandsAll`, the WPILOG entry-start control records, and any direct `Logger.recordOutput` the escape hatch in §2.2 invites | The active log file's byte growth, minus A+B | **No** |

**Bucket A — the per-call size function.** `RootstockLog` computes the WPILOG payload size from the value's static type and adds a fixed record-header constant. Payload encodings are from the [WPILib Data Log Format Specification v1.0](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/wpiutil/doc/datalog.adoc):

| Shape | Payload bytes |
|---|---|
| `boolean` | 1 |
| `long` | 8 |
| `double` | 8 |
| `String` | UTF-8 byte length *(the record header carries the payload size, so no in-payload prefix)* **[UNVERIFIED — the v1.0 spec's `string` row states only "UTF-8 encoded string data"; `string[]` explicitly prefixes. Confirm against a real WPILOG at M5 before the constant is trusted to the byte.]** |
| `Enum<?>` | UTF-8 byte length of `name()` — enums are logged as strings |
| `boolean[]` | `n` |
| `long[]` | `8n` |
| `double[]` | `8n` |
| `String[]` | `4 + Σ(4 + utf8Len(sᵢ))` — verified: 4-byte array count, then 4-byte length per string |
| `Struct<T>` | `struct.getSize()` |
| `Struct<T>[]` | `n × struct.getSize()` |
| `Measure<U>` | 8 (a double, plus one-time unit metadata) |

**Record header constant.** A WPILOG record is a 1-byte length bitfield + a 1–4-byte entry ID + a 1–4-byte payload size + a 1–8-byte timestamp (spec, §"Records"). Rootstock accounts a fixed `HEADER_BYTES = 11`, derived as: 1 (bitfield) + 2 (entry ID — the reference robot has ~500 entries, which needs 2 bytes) + 2 (payload size — the largest key is under 64 kB) + 6 (microsecond timestamps for a match exceed 2³² µs ≈ 71 min only across sessions, so 6 bytes is the steady-state width) = **11**. It is one named constant, printed by `logBudget`, and re-measured against a real WPILOG at **M5**.

**The struct sizes actually used** (WPILib 2026 struct schemas, doubles throughout): `Rotation2d` 8 · `Translation2d` 16 · `Pose2d` 24 · `SwerveModuleState` 16 · `SwerveModulePosition` 16 · `ChassisSpeeds` 24 · `Translation3d` 24 · `Rotation3d` 32 (quaternion) · `Pose3d` 56 · `Transform3d` 56.

**Bucket B — attribution without a second `toLog`.** `RootstockLog.processInputs(key, inputs)` wraps `inputs` in a per-key `SizingInputs` allocated once at first call. Its `toLog(LogTable t)` delegates to `inputs.toLog(t)` and then — **on the attribution cycle only, once per second, and never in `REPLAY`** — walks `t.getAll(true)` (verified: `Map<String, LogValue> getAll(boolean subtableOnly)`), sizing each `LogValue` from `getWPILOGType()` plus the matching typed getter. `getAll(true)` returns exactly the fields this `toLog` wrote, because `Logger.processInputs` hands it `entry.getSubtable(key)`. Iterating that map is a `HashMap` walk, which G7 would normally forbid — it is safe here for a stated reason: the result is an **integer sum**, integer addition is associative and commutative, so iteration order cannot affect the total. `fromLog` is a straight delegate, so `REPLAY` pays nothing.

**Bucket C — the framework bucket, and the honest undercount.** A+B cannot see console capture, DS capture, AdvantageKit's internal topics, or the WPILOG control records that open every new entry. **A+B therefore systematically UNDERCOUNT total file growth, always in the same direction.** Bucket C closes the gap by measurement rather than estimation: once per second, Rootstock stats the active log file and divides the growth since the last sample by the cycles elapsed, then subtracts A+B. The remainder is published as `Rootstock/Log/Bytes/Framework` and printed by `logBudget`.

That file stat is a filesystem read on a periodic path, which G4 otherwise forbids, so it is an **explicit, named exemption**, not an oversight: annotated `@ReplayExempt("byte-budget framework bucket stats the active log file; never affects control")`, listed in the same exemption file as G3's tracer, reviewed at M24, **skipped entirely in `REPLAY`**, and its topics are in `RootstockReplayVerify`'s default ignore set. If a future maintainer decides a periodic `stat` on the RIO is unacceptable, the correct degradation is to drop bucket C and print `Framework: unmeasured` — never to fold an estimate into A+B.

**What the governor triggers on, stated precisely.** `perCycleByteBudget` is a budget on **governable bytes, A+B only**. The governor never demotes a key to compensate for framework bytes it cannot control, because doing so would mutilate the log in response to a number the team cannot act on. Bucket C is reported, alerted on when it exceeds A+B (which means something outside the facade is writing more than Rootstock is), and excluded from every demotion decision.

```java
public final class RootstockBudget {
  /** Governable bytes written this cycle: bucket A + bucket B. */
  public static int lastCycleBytes();
  /** Rolling p95 of lastCycleBytes() over the last 250 cycles. This is the governor trigger. */
  public static int p95CycleBytes();

  /** Bucket A only -- what the RootstockLog facade wrote. */
  public static int lastCycleFacadeBytes();
  /** Bucket B only -- what processInputs wrote. Never demotable (§2.2a); reported so a team
   *  can see that its input schema, not its log calls, is the problem. */
  public static int lastCycleInputBytes();
  /** Bucket C: MEASURED file growth per cycle minus (A + B). Never negative in practice; if
   *  it is, the size function over-counts and that is a bug worth an Alert. Empty until the
   *  first once-per-second sample lands, and always empty in REPLAY. */
  public static OptionalInt lastCycleFrameworkBytes();

  /** Per-key attribution over buckets A and B, sorted descending. Refreshed on the
   *  once-per-second attribution cycle. Logged at DEBUG. Bucket C appears as a single
   *  synthetic entry named "(Framework)" so the list always sums to the measured truth. */
  public static List<Map.Entry<String, Integer>> topKeys(int n);

  /** The accounting constants, exposed so ./gradlew logBudget and the M5 gate print the
   *  same numbers the runtime uses. */
  public static int headerBytes();          // 11, see above

  /** Every key currently demoted to every-5th-cycle, in demotion order. */
  public static List<String> demotedKeys();
  /** Monotonic. Bumped on every demotion AND every restoration. Mirrors
   *  Rootstock/Log/SchemaGeneration. Starts at 0. */
  public static long schemaGeneration();
  /** Complete, closed set of keys the governor is permitted to touch. Contains no key
   *  whose path contains "/Inputs/" -- asserted by rootstock-lint and by RootstockTest. */
  public static Set<String> demotableKeys();
}
```

**One consequence worth stating before a team reads a number and trusts it.** Because `WPILOGWriter` writes only *changed* values, buckets A and B are **upper bounds** on what actually reaches disk: a key whose value is unchanged costs nothing that cycle. The governor deliberately triggers on the upper bound, because the alternative — tracking per-key change rates — is more state, more CPU and a trigger that moves when the robot's behavior changes. The direction of the error is the safe one: the governor fires **earlier** than strictly necessary, never later. `logBudget` says so in its output.

#### 2.7.1 Runtime behavior — the FMS tier gate and the governor

*(Heading added 2026-08-08. `§2.7.1` was already being cited from §11.3's carrying-cost table and is now cited from §2.2 and §3.1 as well; it was a dangling cross-reference into an unheaded list.)*

1. `minimumTier` is raised to `STANDARD` automatically on FMS attach — **to STANDARD and no higher.** CRITICAL and STANDARD keys are both still published; `DEBUG` keys stop being evaluated at all (they are supplier-gated). This is the whole of the gate: it removes one tier, not two. §2.2's `Tier` javadoc is written against this sentence, and §3.1's ruling on `DeviceResetCount` turns on it.

2. **Demotion is opt-in, bounded, and versioned.** If `p95CycleBytes() > perCycleByteBudget` for 50 consecutive cycles, Rootstock raises `Alerts.warning("Rootstock/Log", "LOG_BUDGET_EXCEEDED …", MatchImpact.PIT_ONLY)` naming the top three keys by byte cost, and then demotes — to every-5th-cycle publication, never to deletion — the **most expensive keys that are explicitly marked `Demotable.YES`**, one at a time, re-measuring for 50 cycles between each step, until p95 is back under budget or the demotable set is exhausted. When the set is exhausted and p95 is still over, the governor **stops demoting and escalates the Alert to error**. It does not go looking for other keys to cut. A team that logged itself into a hole gets told so; it does not get a quietly mutilated log.

   The library marks exactly five key patterns `Demotable.YES`, and this list is the whole of it. **Every entry is an OUTPUT.** No `Inputs/` key appears, and none ever can (§2.2a):

   | Demotable key | Kind | Tier | Why it is safe to sample at 10 Hz |
   |---|---|---|---|
   | `Rootstock/Drive/ModuleStates/SetpointOptimized` | output | STANDARD | Purely derived from `ModuleStates/Setpoint`, which is never demotable. Nothing consumes it; it exists to show what optimization did. |
   | `Rootstock/Drive/ModulePositions` | output mirror | STANDARD | The replayable copy lives in the drive IO **inputs** and stays at full rate. This key is a convenience mirror for graphing. |
   | `Rootstock/Drive/Pose3d` | output | STANDARD | Derived from `Pose` (CRITICAL, never demotable) by lifting to 3D. A 56-byte struct that carries no information `Pose` does not. |
   | `Rootstock/Vision/*/StdDevs` | output | STANDARD | Only meaningful on cycles where `Accepted` is true, which is already rarer than 50 Hz. |
   | `Rootstock/Viz/{Setpoint,Goal}/Components` | output | STANDARD | The two *ghost* component arrays. `Viz/Measured/Components` — the real robot — is never demotable. A ghost that updates at 10 Hz is still a legible ghost. |

   **The two keys that used to be on this list and are now permanently off it** are `Rootstock/<Mech>/Inputs/SupplyCurrentAmps` and `Rootstock/<Mech>/Inputs/TempCelsius`. §2.2a is the full argument; the short version is that they are **replay-load-bearing**, `design/06`'s power/brownout/thermal monitors consume them under G8, and demoting them makes §2.5A's byte-identical guarantee false. They stay at full rate. The bytes they cost are real and are now simply reported rather than reclaimed.

   Everything else in the library — every `Goal`, `Setpoint`, `Measured`, `Error`, `AtGoal`, `State`, every `Pose2d`, every `RejectReason`, every `Rootstock/Perf/**`, every `Inputs/**` — is `Demotable.NO` and structurally untouchable. **Nothing under `Rootstock/Driver/` is ever demotable**, at any tier, under any load: the driver mirror (§6.2) is ~30 low-rate topics that a human is looking at while the robot is moving, and it is exempt from the governor entirely rather than merely unmarked.

3. **Every demotion and every restoration bumps the schema generation.** `Rootstock/Log/SchemaGeneration` is a `long`, `CRITICAL`, `Demotable.NO`, published every cycle. Alongside it:

   ```
   Rootstock/Log/SchemaGeneration        long      CRITICAL   monotonic, starts at 0
   Rootstock/Log/Governor/Demoted        String[]  CRITICAL   currently-demoted keys
   Rootstock/Log/Governor/LastChangeSec  double    CRITICAL   timestamp of the last generation bump
   Rootstock/Log/Governor/Reason         String    CRITICAL   "p95=8214B > budget=6000B for 50 cycles"
   Rootstock/Log/Bytes/Facade            long      STANDARD   bucket A, this cycle
   Rootstock/Log/Bytes/Inputs            long      STANDARD   bucket B, this cycle (never demotable)
   Rootstock/Log/Bytes/Framework         long      STANDARD   bucket C, measured; absent in REPLAY
   ```

   The `Reason` string quotes **governable** bytes (A+B), which is what `perCycleByteBudget` bounds. `p95=8214B > budget=6000B` is over by 37 %, and the reclaimable ceiling is 10.6 % — so this example is one where the governor demotes everything it may and still escalates to error, which is the intended and honest outcome.

   A generation bump is the *only* legal way for the effective schema to change mid-session. Any other schema change is a bug.

4. **Restoration.** When `p95CycleBytes()` stays below 70 % of `perCycleByteBudget` for 500 consecutive cycles (10 s), the governor restores the most recently demoted key, bumps the generation again, and re-measures. Hysteresis is deliberate: a governor that flaps produces a log with dozens of generations and is worse than one that never restores.

5. **Consumers must honor the generation.** `RootstockReplayVerify` (§5.6) treats a `SchemaGeneration` change as an **expected discontinuity**, not a diff — see §5.6. The match manifest (§5.2) records `schemaGenerations` and every boundary, and the triage workflow (§5.5) prints "the log's sample rate changed at t=…" instead of leaving a student staring at a trace with holes in it.

6. `Rootstock/Log/BytesPerCycle`, `Rootstock/Log/QueuedCycles`, `Rootstock/Log/SchemaGeneration`, and `Rootstock/Perf/LoopMs` are always `CRITICAL` and always `Demotable.NO`.

7. **Build-time enforcement.** `rootstock-lint` (§2.6) fails the build if any key literal containing `/Inputs/` **or** starting with `Rootstock/Driver/` is passed `Demotable.YES`, and if `RootstockBudget.demotableKeys()` does not match the five patterns in the table above. `./gradlew logBudget` prints the full demotable set so a team can see, before an event, exactly which traces can gap and which cannot.

8. **The governor is a no-op in `REPLAY`.** It reads measured wall-clock byte rates, which are meaningless at 50×; in replay it publishes the recorded generation sequence unchanged so the timeline partitioning in §5.6 still works, and demotes nothing. G9 is what checks that this stayed true.

Build-time companion:

```
./gradlew logBudget
  Accounting: HEADER_BYTES=11, payload per WPILOG v1.0 (§2.7.0).
  Static estimate is a WORST CASE: WPILOGWriter writes only CHANGED values, so real
  file growth is lower. Bucket C (console/DS/akit-internal) is NOT statically estimable
  and is measured at runtime as Rootstock/Log/Bytes/Framework.

  Governable estimate (buckets A+B): 4.1 kB/cycle, 205 kB/s, >=123 MB per 10-minute session.
  Top static contributors:
    Rootstock/Vision/Front/AllTagPoses     1,232 B  (Pose3d[22] @56)  DEBUG, dropped on FMS
    Rootstock/Drive/ModuleStates/Measured      64 B  (SwerveModuleState[4] @16)  not demotable
    Rootstock/Elevator/Inputs/SupplyCurrentAmps 16 B  (double[2])   INPUT - never demotable
    Rootstock/Elevator/Inputs/TempCelsius       16 B  (double[2])   INPUT - never demotable
  Demotable budget headroom (governor may reclaim, worst case, OUTPUTS ONLY):
    Rootstock/Drive/ModulePositions                 64 B -> 12.8 B   (reclaims 51.2 B)
    Rootstock/Drive/ModuleStates/SetpointOptimized  64 B -> 12.8 B   (reclaims 51.2 B)
    Rootstock/Drive/Pose3d                          56 B -> 11.2 B   (reclaims 44.8 B)
    Rootstock/Vision/Front/StdDevs                  24 B ->  4.8 B   (reclaims 19.2 B)
    Rootstock/Viz/Setpoint/Components              168 B -> 33.6 B   (reclaims 134.4 B)
    Rootstock/Viz/Goal/Components                  168 B -> 33.6 B   (reclaims 134.4 B)
    TOTAL DEMOTABLE                              544 B
    TOTAL RECLAIMABLE                            435.2 B  (10.6% of the 4.1 kB estimate)
  WARN Arm.java:71 - allocation inside a STANDARD log call on the hot path.
```

**Derivation of the two totals, because they are load-bearing** and the previous revision's `328 B (8%)` was computed from a demotable set that included two inputs and used a wrong `SwerveModulePosition` size:

- Payload sizes: `SwerveModulePosition` is `{double distance, Rotation2d angle}` = 8 + 8 = **16 B**, so `SwerveModulePosition[4]` = **64 B**. `SwerveModuleState` is `{double speed, Rotation2d angle}` = **16 B**, so `[4]` = **64 B**. `Pose3d` is `Translation3d`(24) + `Rotation3d`(32, a quaternion) = **56 B**, so a 3-component `Pose3d[3]` = **168 B**. `double[3]` = **24 B**.
- Demotable sum: 64 + 64 + 56 + 24 + 168 + 168 = **544 B/cycle**.
- Demotion publishes every 5th cycle, retaining 1/5. Reclaimed = 4/5 × 544 = **435.2 B/cycle**.
- As a fraction of the 4.1 kB (4,100 B) governable estimate: 435.2 / 4100 = 0.1061 → **10.6 %**.
- Session size: 4.1 kB × 50 Hz = 205 kB/s; × 600 s = 123,000 kB = **123 MB**, stated as a floor because bucket C is additive.

Printing the reclaimable total is the point: **10.6 % is not a rescue.** If a team is 40 % over budget, the honest answer is "delete log calls or move them to DEBUG," and `logBudget` says so before the event instead of the governor pretending mid-match. **That answer used to have a fallback — "or switch to a cheaper backend." It does not any more.** And it is now 10.6 % of a number that excludes bucket C entirely, so as a fraction of the *file* the governor's reach is smaller still.

### 2.8 How Rootstock calls map onto AdvantageKit

The four-column backend mapping table is deleted. There is one column.

| Rootstock call | AdvantageKit |
|---|---|
| `configure` / `start` | `Logger.recordMetadata(String, String)` ×n, `Logger.addDataReceiver(new WPILOGWriter(folder))`, `Logger.addDataReceiver(new NT4Publisher())` (wrapped per `NtPolicy`), `Logger.setReplaySource(new WPILOGReader(LogFileUtil.findReplayLog()))` in REPLAY, `Logger.start()` |
| `log(key, double)` | `Logger.recordOutput(String, double)` |
| `log(key, double, unit)` | `Logger.recordOutput(String, double, Unit)` — **verified: this overload exists** |
| `log(key, Measure<U>)` | **`Logger.recordOutputMeasure(String, Measure<U>)`** — a *differently named* method, not a `recordOutput` overload. The previous revision got this wrong. |
| `log(key, T)` where `T extends WPISerializable` | `Logger.recordOutput(String, T)` — the geometry path |
| `log(key, T[])` where `T extends StructSerializable` | `Logger.recordOutput(String, T...)` |
| `log(key, Struct<T>, T)` | `Logger.recordOutput(String, Struct<T>, T)` — **struct first.** The previous revision showed `recordOutput(key, v)` for the explicit-struct form and put `Struct<T>` last in the facade; both were wrong. |
| `log(key, Enum)` | `Logger.recordOutput(String, E)` where `E extends Enum<E>` |
| `processInputs(key, inputs)` | `Logger.processInputs(String, LoggableInputs)` — **replayed from the log in REPLAY mode**; Rootstock interposes the `SizingInputs` wrapper (§2.4) |
| `timestamp()` | `Timer.getTimestamp()` (seconds). `Logger.getTimestamp()` is the same instant as a `long` in **microseconds** |
| `isReplay()` | `Logger.hasReplaySource()` |
| Alerts | `Rootstock/Health/**` outputs (§3.5) + the WPILib `Alert` group `Rootstock`, which AdvantageKit auto-logs |
| Console capture | Built-in and **on by default**; the only control is the opt-**out** `Logger.disableConsoleCapture()`. `LogConfig.defaults().withCaptureConsole(false)` therefore maps to *calling* that method — there is no enable call. |
| `everyN(n, r)` | `Logger.runEveryN(int, Runnable)` |
| Replay | **always available** |

Every signature in the right-hand column was read from [`Logger.java` at `main`](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/akit/src/main/java/org/littletonrobotics/junction/Logger.java) on 2026-08-08. Two methods the previous revision's prose implied do **not** exist and are not used anywhere in this document: `Logger.getRealTimestamp()` and `Logger.disableDeterministicTimestamps()`.

Notes:
- The full log stream lands under `/AdvantageKit/RealOutputs/...` on NetworkTables (and `ReplayOutputs/...` during replay). That path is stable now that there is one logger — but it is still **not** what the shipped Elastic layouts bind to, for the reasons in §6.2, which are now two reasons instead of three.
- `NtPolicy.OFF_ON_FMS` installs an `NT4Publisher` wrapper that no-ops when `MatchContext.isFMSAttached()` — 6328's `NoFMSNT4Publisher` pattern. **`MatchContext`, not `DriverStation` directly:** ArchUnit rule 10 makes `org.rootstock.core.match` the only package in the library permitted to read `DriverStation`, and this wrapper lives in `org.rootstock.telemetry`. *(Corrected 2026-08-08; this line named `DriverStation.isFMSAttached()` and was a live rule-10 violation. `MatchContext.isFMSAttached()` is verified present in `design/06` §10's `MatchContext` declaration — `public static boolean isFMSAttached();`.)* It is also the better behavior: `MatchContext` latches on the DS-connect edge, so a DS that drops out mid-match does not un-suppress the NT stream in the middle of a match. The driver mirror (§6.2) is *not* suppressed; it is ~30 low-rate topics and the drivers need it.
- **Never run two logging libraries.** DogLog's own docs are explicit about this, and it remains true — but it is no longer something Rootstock has to *detect*, because Rootstock no longer offers to be one of the two. The old `RootstockLog.configure` check that threw when two of `{AdvantageKit started, DogLog enabled, Epilogue bound}` were active is deleted. What replaces it is a documentation line in the adoption matrix: *a team on DogLog or Epilogue cannot adopt Rootstock at all* (§11.2). That is a worse outcome for those teams than a runtime error would have been informative — the error at least told them something. The adoption matrix has to carry that weight now.

### 2.9 Vendor CAN sidecars

```java
LogConfig.defaults()
    .withCtreSignalLogger(true)   // SignalLogger.setPath("/U/ctre_logs"); SignalLogger.start()
    .withUrcl(true);              // Logger.registerURCL(URCL.startExternal(aliasMap))
```

The old two-branch form (`ADVANTAGEKIT: registerURCL(startExternal(...))` / `others: URCL.start(...)`) collapses to the AdvantageKit branch. `URCL.startExternal` is the correct call precisely because AdvantageKit owns the data stream and URCL must feed it rather than open its own.

Rootstock treats `.hoot` and `.revlog` strictly as **high-rate CAN sidecars**, never as replay engines. The manifest records their paths so `pullLogs` grabs the whole set, and the triage doc tells you to open them *alongside* the `.wpilog` in AdvantageScope (`File > Add New Log(s)...` auto-aligns timestamps). We do not wrap Hoot Replay.

---

## 3. What Gets Logged Automatically — the schema

**Root:** `Rootstock/`. Outputs land under `RealOutputs/Rootstock/...` (and `ReplayOutputs/Rootstock/...` during replay); inputs land under `Rootstock/<Name>/Inputs/...` at the top level. There is only one layout — the former "on other backends everything is under `Rootstock/...`" caveat is gone, which means the shipped AdvantageScope layouts (§6.5) and `CycleStats` (§5.7) can hard-code full paths instead of probing for a root.

Zero user code produces all of this. A mechanism registers itself once (the mechanism base class does it in its constructor) and the schema follows.

### 3.1 Every mechanism — `Rootstock/<Name>/`

`<Name>` is `TelemetrySource.telemetryName()`. Units below are the *declared* unit of the mechanism — **Meters for an elevator, Degrees for an arm** — and the actual unit is attached as entry metadata so AdvantageScope labels and converts correctly.

**Degrees, not Radians, for a rotary axis — and this is a correction, not a preference.** Earlier revisions of this lead-in said *"Radians for an arm"*. That was a **57.3× mislabel over a degree stream**: `design/01`'s `Axis` defines `userPerOutputRotation()` as **degrees**-per-output-rotation and `Axis.unitLabel()` returns `"deg"`, so what a `PositionMechanism` on a rotary axis actually pushes into `Position`, `Goal`, `Setpoint`, `Measured`, `Error` and the two soft limits is degrees. Declaring Radians here would attach `"radians"` metadata to a degree stream, and AdvantageScope's unit conversion would then compound the error rather than reveal it — a 90° arm setpoint plotted as 5157°. **The fix is the prose; no conversion is added anywhere.** `Units.Degrees` is what a rotary mechanism passes to `TelemetryDescriptor.positionUnit(...)` and `Units.DegreesPerSecond` to `velocityUnit(...)`. This resolves `design/01` §13 **OQ16a** in `design/01`'s favor — CORE was publishing degrees and this document was mislabeling them.

**Inputs (replayable):**

The **Dem.** column is `Demotable` (§2.2). `YES` means the byte-budget governor may sample this key at 10 Hz instead of 50 Hz under sustained overload, bumping `Rootstock/Log/SchemaGeneration` when it does. Blank means the governor can never touch it.

**Every cell in this table is blank, and that is structural, not an oversight.** These are replayed inputs. Per the invariant in §2.2a, no key that reaches the log through `processInputs` is ever demotable, at any tier, under any load: demoting one would make `fromLog` return a stale value on four cycles out of five in `REPLAY` while the real robot read a fresh one, and every output derived from it — including `design/06`'s power, brownout and thermal monitors, which **G8** requires to agree at 1× and 50× — would diverge. The column is kept rather than deleted precisely so a future editor can see it is empty **on purpose**.

| Key | Type | Unit meta | Tier | Dem. |
|---|---|---|---|---|
| `Inputs/Connected` | `boolean[]` (per motor) | — | CRITICAL | |
| `Inputs/Position` | `double` | position unit | CRITICAL | |
| `Inputs/Velocity` | `double` | velocity unit | CRITICAL | |
| `Inputs/AbsolutePosition` | `double` | position unit | STANDARD | |
| `Inputs/AppliedVolts` | `double[]` | volts | CRITICAL | |
| `Inputs/SupplyCurrentAmps` | `double[]` | amps | CRITICAL | |
| `Inputs/StatorCurrentAmps` | `double[]` | amps | STANDARD | |
| `Inputs/TempCelsius` | `double[]` | celsius | STANDARD | |
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

**Extras — the five `describe(...).extra(...)` keys CORE declares, with this document's ruling on each tier.** These are published by `design/01` §6.2 and §8.2 through `TelemetryDescriptor.extra(...)`; they were absent from the table above, so `design/01` §1.1b declared all five `STANDARD` *because that was the tier its existing call site already used* and asked this document to confirm or overrule. **Ruling, 2026-08-08 — four confirmed, one overruled.** Tiers are this document's call, and the schema audit is what keeps the declaration and the publish in step, so an overrule changes **both** the `d.extra(...)` line and the `critical`/`log` call together.

| Extra key | `design/01` declared | **Ruling** | Overload | Why |
|---|---|---|---|---|
| `DeviceResetCount` | STANDARD | **CRITICAL — overruled** | `extra(String, Tier)` (unit-free; it is a count) | See below. |
| `FeedbackVolts` | STANDARD | **STANDARD — confirmed** | `extra(String, Unit, Tier)` with `Volts` | A gain-tuning trace. Useful in the pit, not needed to explain a lost match, and it only exists on `RIO_FULL`/`RIO_PROFILE_MOTOR_LOOP` axes anyway. |
| `FeedforwardVolts` | STANDARD | **STANDARD — confirmed** | `extra(String, Unit, Tier)` with `Volts` | Same. It is the companion trace to the one above and the pair is meaningless split across tiers. |
| `Blocked` | STANDARD | **STANDARD — confirmed** | `extra(String, Tier)` (unit-free; a `String`) | Superstructure diagnostics. A blocked transition is already visible in `State` and in `Rootstock/Health/**`, both CRITICAL, so the FMS log does not lose the fact — only the human-readable reason. |
| `Plan` | STANDARD | **STANDARD — confirmed** | `extra(String, Tier)` (unit-free; a `String[]`) | The planned transition sequence. A `String[]` published every cycle is the single fattest extra in the set, and it is reconstructible from the `State` trace, which is CRITICAL. |

**Why `DeviceResetCount` is CRITICAL and not STANDARD.** It is the key that says *a motor controller rebooted mid-match* — power loss on a CAN device, a brownout deep enough to reset a Talon, a loose Phoenix connector. That is the textbook case of *"anything you would need to explain a lost match"*, which is the CRITICAL tier's own definition (§2.2), and it is a signal you can only ever look at **after** the match that went wrong, from a log pulled off a robot that has since been power-cycled. There is no second copy.

A reader may object that STANDARD already survives the FMS gate — and that is true today: §2.7.1 step 1 raises `minimumTier` to STANDARD and no higher, so a STANDARD key is present in an FMS-attached log. **CRITICAL is chosen anyway, for two reasons that STANDARD cannot give.** First, `minimumTier` is a `LogConfig` field (§2.2b) and a team under a byte-budget squeeze at an event can set it to `CRITICAL` at 11 pm — CRITICAL is the only tier whose presence does not depend on a decision someone might make in a hurry. Second, §2.2 states that *"CRITICAL keys are never removed from the schema by any mechanism"*; a reset counter that can be argued away is a reset counter that will be, and the whole reason `design/01` correction row 9 exists (setpoint sent once, forever, then silently dropped by a device reset) is that this failure is invisible unless something counts it.

**The paired `design/01` edit this overrule requires** — a contract request, not made here, because CORE owns those call sites: `d.extra("DeviceResetCount", Value, Tier.STANDARD)` in `design/01` §1.1b becomes `d.extra("DeviceResetCount", Tier.CRITICAL)` on the new unit-free overload, **and** the matching publish in `design/01` §6.2 changes from `RootstockLog.log(...)` to `RootstockLog.critical(...)`. They must land in the same edit: the schema audit (§1.5) compares declaration against publish and will fail loudly if only one of the two moves — which is exactly the guard this ruling relies on. The other four keys need no edit beyond swapping `Blocked` and `Plan` onto the unit-free overload and dropping their `Units.Value` argument. This resolves `design/01` §13 **OQ16c** and the tier half of its OQ16.

**The ruling is pinned by a test, not by this table.** §8.3's `deviceResetCountSurvivesTheTierGate` raises `minimumTier` to `CRITICAL` — stricter than the FMS gate ever sets it — and asserts `Rootstock/<Name>/DeviceResetCount` is still published. A future editor who moves the key back to STANDARD fails that test, which is the point: the argument above is persuasive, and a persuasive argument is exactly the kind that gets quietly reversed.

### 3.2 Drivetrain — `Rootstock/Drive/`

| Key | Type | Tier |
|---|---|---|
| `Pose` | `Pose2d` struct | CRITICAL |
| `Pose3d` | `Pose3d` struct | STANDARD **(Demotable)** |
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

### 3.3 Vision — `Rootstock/Vision/<Camera>/`

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
| `Ambiguity` | `double` | STANDARD |
| `AvgTagDistanceMeters` | `double` | STANDARD |
| `StdDevs` | `double[3]` | STANDARD **(Demotable)** |
| `AllTagPoses` | `Pose3d[]` | **DEBUG** (supplier-gated — this is the single biggest 2026 loop-time offender) |

Roll-ups at `Rootstock/Vision/`: `AcceptedThisCycle` (long), `UptimeFraction` (double), `AnyCameraOffline` (boolean).

### 3.4 Field / ghost contract — `Rootstock/Field/`

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

**This domain does not own alerts.** Per DESIGN.md D10, Platform (06) owns `org.rootstock.core.alert`. Doc 04's former `RootstockFaults` is deleted; everything in Rootstock raises alerts through:

```java
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.RootstockAlert;

import org.rootstock.core.alert.MatchImpact;

// MatchImpact is REQUIRED on error() and warning() -- no default, no single-argument
// overload (DESIGN.md D10). It is ABSENT from info(), which is a two-argument
// info(String group, String text): INFO is PIT_ONLY by definition, so the parameter would
// have exactly one legal value (contract C13; design/06 §8.2 owns the declaration).
// A log-volume problem never blocks a match, so every alert this domain raises is
// PIT_ONLY. The one exception would be a total loss of logging, and that is not
// match-blocking either: the robot still drives.
RootstockAlert a = Alerts.warning("Rootstock/Log",
    "LOG_BUDGET_EXCEEDED: p95=8214B > 6000B", MatchImpact.PIT_ONLY);
a.sticky(true);        // a sticky fault -- the old RootstockFaults semantics, one facade
a.set(false);          // clear
```

What **this** domain owns is the *transport and schema*: `AlertRegistry` (06) is polled every cycle and its contents are published as

```
Rootstock/Health/Active        String[]   CRITICAL   active alert texts, worst-first
Rootstock/Health/Seen          String[]   CRITICAL   every alert raised this session
Rootstock/Health/Counts/<name> long       STANDARD   rising-edge count per alert
Rootstock/Health/Worst         String     CRITICAL   "ERROR" | "WARNING" | "INFO" | "NONE"
```

plus the WPILib `Alert` group `Rootstock`, which publishes to `/SmartDashboard/Rootstock` — an NT *path* that survives the 2027 removal of the SmartDashboard *application*. Rootstock writes that path directly; it never touches `edu.wpi.first.wpilibj.smartdashboard`.

DogLog's fault semantics over AdvantageKit's transport, one API. (Stated carefully now: we adopted DogLog's *semantic model* for faults. We are not a DogLog backend and never will be — see §11.2.)

```java
public final class RootstockTracer {
  public static void reset();                              // call at top of loop
  public static void record(String epoch);                 // marks time since last record
  public static AutoCloseable scope(String epoch);         // try-with-resources
  /** On breach: Alerts.warning("Rootstock/Perf", "PERF_<epoch> ...", MatchImpact.PIT_ONLY). */
  public static void budget(String epoch, Time limit);
}
```
Emits `Rootstock/Perf/<epoch>Ms` with `"milliseconds"` unit metadata, plus `Rootstock/Perf/LoopMs` and `Rootstock/Perf/OverrunCount`.

**The tracer reads the un-injected hardware clock deliberately** — it must report real wall time, not the injected replay clock, or a 50× replay reports absurd loop times. This is guarantee **G3**: the only sanctioned violation of G2/R2, annotated `@ReplayExempt("tracer measures real wall time by design")`, with its topics in `RootstockReplayVerify`'s default ignore set.

**Corrected API — this was wrong in the previous revision.** That revision said the tracer uses `Timer.getMonotonicTimestamp()`. **`Timer.getMonotonicTimestamp()` does not exist.** Verified against the WPILib 2026.2.2 javadoc for [`edu.wpi.first.wpilibj.Timer`](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/wpilibj/Timer.html): the only clock statics are `getTimestamp()`, `getFPGATimestamp()`, `getMatchTime()` and the two `delay` overloads. `getTimestamp()`'s own javadoc is what makes it the right control clock — *"the return value of this method may be modified to use any time base"* — and it is exactly that modifiability that makes it the **wrong** clock for a tracer.

**What the tracer actually calls:** `edu.wpi.first.wpilibj.RobotController.getFPGATime()`, a `long` in **microseconds**, read straight from the FPGA and never injected (verified against the [`RobotController` javadoc](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/wpilibj/RobotController.html); note `RobotController.getTime()` is the *injectable* sibling and is not what we want). The tracer divides by `1e6` once per epoch and reports seconds.

**This opens a hole in ArchUnit rule 3 and the hole is named rather than exploited.** Rule 3 as written in [`DESIGN.md` §8](../DESIGN.md) bans `Timer.getFPGATimestamp()` and `new Timer()` — it says nothing about `RobotController.getFPGATime()`, so the tracer's call is legal today *by omission*, which is worse than an exemption. **Rule 3 must be extended** to ban `RobotController.getFPGATime` / `getMeasureFPGATime` with `org.rootstock.telemetry.RootstockTracer` as the single named allowed caller. Until that edit lands, R2's build-time check (§2.6) is the only thing enforcing it, and R2 reaches only template users. Listed as a contract request.

### 3.6 Provenance metadata (write-once, before `start()`)

`ProjectName`, `BuildDate`, `GitSHA`, `GitBranch`, `GitDirty` (`"All changes committed"` / `"Uncommitted changes"`), `Hostname`, `Platform`, `RobotIdentity`, `RootstockVersion`, `WpilibVersion`, **`AdvantageKitVersion`**, `Mode`. Straight from 6328's practice, which makes any log traceable to an exact commit.

`Backend` and `ReplayCapable` are **removed** from this list. `Backend` had one possible value and `ReplayCapable` was structurally true. `AdvantageKitVersion` replaces them and is the more useful field anyway: when a replay misbehaves after an upgrade, the first question is which AdvantageKit wrote the log.

---

## 4. 3D Match Replay & Mechanism Visualization

This is the headline user request. It is also the thing AdvantageScope's own docs warn is "complex and time-consuming" and steer you away from. Rootstock's job is to make it nearly free.

**Scope note (Decision 1):** all of §4, including `AssetExporter`, `FieldGhosts` and the `rootstockAssets` drift check, is in scope as **M19 (2.0 pw)**. Nothing here is deferred. Depth lever **L10** in the roadmap would, if fired, ship `ArticulationSpec` + `MechanismVisualizer` and drop `AssetExporter`, `FieldGhosts` and the `rootstockAssets` task for −1.1 pw; that lever is not fired and is recorded only so the reader knows which parts are the expensive ones.

### 4.1 The two contracts that must never drift

1. **Asset contract.** A folder named exactly `Robot_<NAME>` containing `config.json`, `model.glb` (base, **no components**), and `model_0.glb` … `model_N.glb`.
2. **Pose contract.** A `Pose3d[]` published every cycle, **in the same order as `config.json`'s `components[]`**, each element being the robot-relative pose of that component's joint frame.

Every failure mode here is silent. So Rootstock generates **both sides from one declaration** and ships a build task that fails if they disagree.

### 4.2 Declaring the kinematic chain

```java
package org.rootstock.viz;

/** The viz joint axis. Named JointAxis, not Axis, per DESIGN.md D4: org.rootstock.units.Axis
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

  /** The Pose3d[] when every joint is at zero. Published as Rootstock/Viz/ZeroedComponents. */
  public Pose3d[] zeroedPoses();
}
```

**Parent chain root** is the string `"ROBOT"`, whose frame is the published robot pose — which AdvantageScope places at **floor level (z = 0)**, not the bellypan. This is the single most common calibration mistake and it is documented directly in the builder's Javadoc.

### 4.3 Publishing measured / setpoint / goal

```java
public final class MechanismVisualizer {
  public static MechanismVisualizer of(ArticulationSpec spec);      // logs under "Rootstock/Viz"
  public static MechanismVisualizer of(ArticulationSpec spec, String logRoot);

  public Channel measured();
  public Channel setpoint();
  public Channel goal();

  /** Call once per cycle, after all channels are set. Emits everything in §4.4. */
  public void log();

  /** Escape hatch: the raw arrays, if you want to do something we didn't think of. */
  public Pose3d[] measuredPoses();

  /** The generated 2D mechanism. Always present -- see §4.7. Returns the real
   *  org.littletonrobotics.junction.mechanism.LoggedMechanism2d, not an Optional and not
   *  an Object: AdvantageKit is a required dependency, so there is nothing to hide. */
  public LoggedMechanism2d mechanism2d();

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
Rootstock/Viz/Measured/Components   Pose3d[]   -> attach to the Robot object, type "Component"
Rootstock/Viz/Setpoint/Components   Pose3d[]   -> attach to a Ghost object      [Demotable]
Rootstock/Viz/Goal/Components       Pose3d[]   -> attach to a second Ghost      [Demotable]
Rootstock/Viz/ZeroedComponents      Pose3d[]   -> calibration aid; constant
Rootstock/Viz/Cameras/<Name>        Pose3d     -> field-relative; AdvantageScope Camera Override
Rootstock/Viz/GamePieces/<Type>     Pose3d[]   -> held pieces, empty array when none
Rootstock/Viz/Mechanism2d           LoggedMechanism2d
Rootstock/Viz/ComponentOrder        String[]   -> CRITICAL, logged once; the manifest checks it
```

`ComponentOrder` in the log is what lets `rootstockAssets --verify` and the triage tooling detect an asset/code mismatch *from a log file*, months later, without the repo.

### 4.5 Ghost semantics

- **Measured** = where the robot actually is.
- **Setpoint** = where the profile says it should be *this cycle*. In AdvantageScope this is a Ghost robot with the setpoint component array. Divergence between the two ghosts is a visual PID readout: a lagging measured ghost is under-gained; an overshooting one is over-gained. This is a teaching tool as much as a debug tool.
- **Goal** = the final commanded target. A goal ghost that never converges is a stuck superstructure.

For the drivetrain, the same three appear on the 2D/3D field via `Rootstock/Field/Robot`, `Rootstock/Field/RobotGhost`, `Rootstock/Field/Goal`.

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

Repo layout Rootstock creates:

```
src/main/deploy/ascope/
  Robot_Rootstock/
    config.json      <- GENERATED. Do not edit; edit ArticulationSpec.
    model.glb        <- base chassis, NO components
    model_0.glb      <- elevator stage 1
    model_1.glb      <- carriage
    model_2.glb      <- arm
```

Generated `config.json` (real schema, verified against the AdvantageScope docs):

```json
{
  "name": "Rootstock",
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
./gradlew rootstockAssetCalibrate
```
Runs the robot program in sim with:
- `Rootstock/Field/Robot` pinned to `Pose2d.kZero`,
- `Rootstock/Viz/Measured/Components` = `spec.zeroedPoses()`,
- every component's `zeroedRotations`/`zeroedPosition` exposed as `LoggedNetworkNumber` under **`/Tuning/Assets/<componentId>/...`** (AdvantageScope tuning mode makes exactly the `/Tuning` table editable),
- a `/Tuning/Assets/Write` boolean that, on rising edge, writes the current values back into `config.json` in the deploy folder.

Six-step trial-and-error becomes: open AdvantageScope, drag sliders until it lines up, flip the write toggle.

`LoggedNetworkNumber` is an AdvantageKit type, and under the prior design this whole calibration flow was implicitly AdvantageKit-only while being documented as if it were universal. That inconsistency is resolved rather than papered over: it is AdvantageKit-only, AdvantageKit is required, so it is universal.

**Build tasks:**

```
./gradlew rootstockAssets           # generate config.json, verify, zip -> build/ascope/Robot_Rootstock.zip
./gradlew rootstockAssets --verify  # verify only; FAILS the build on mismatch. Wired into `check`.
```
The zip-per-asset output is deliberate: it is simultaneously the AdvantageScope desktop custom-assets folder format **and** the 2027 AdvantageScope Lite `File > Upload Asset` format. One artifact, both eras.

**Documented CAD path:** Onshape → STEP → CAD Assistant → `.glb` with "Merge faces within the same part" enabled. Keep meshes low-poly; AdvantageScope auto-simplifies (escape hatches: `NOSIMPLIFY` in a mesh name, or `"disableSimplification": true`) and AdvantageScope XR degrades badly on heavy scenes.

### 4.7 Mechanism2d — the 2D path, now unconditional

AdvantageScope's docs recommend a Mechanism2d over articulated 3D components as "the more streamlined approach," and AdvantageScope 2026 can project one onto the 3D field in the **XZ or YZ plane** attached to a robot or ghost. Rootstock generates it from the *same* `ArticulationSpec`, so a team gets a usable visualization on day one and upgrades to CAD models later without touching robot code.

**This feature is no longer gated.** Under the prior design it was `Optional`, present only on the ADVANTAGEKIT backend, with a one-time info Alert naming the vendordep on every other backend. All of that — the `Optional`, the `Object raw()` indirection, the alert, the `Rootstock/Log/Caveats` topic, and the four-way caveat matrix in §9.5 — is deleted. **This is the single cleanest thing Decision 3 does to this document.**

**The hard constraint that has NOT changed.** WPILib's `Mechanism2d`, `MechanismRoot2d` and `MechanismLigament2d` live in `edu.wpi.first.wpilibj.smartdashboard`. That package is:

- deleted in WPILib 2027 along with the SmartDashboard application,
- banned by **DESIGN.md Principle 9**, by **doc 01 §1.7 constraint 5**, and by this document's own non-goals (§10), and
- concretely forbidden by doc 06's ArchUnit rule `noDeadWpiApis`, which fails the build on any dependency into `edu.wpi.first.wpilibj.smartdashboard..`.

AdvantageKit maintains its own fork of these classes at `org.littletonrobotics.junction.mechanism.{LoggedMechanism2d, LoggedMechanismRoot2d, LoggedMechanismLigament2d}` — a package that is not a WPILib-removed package and that survives the 2027 rename untouched. That fork is the *only* implementation Rootstock uses, and now it is simply the implementation rather than one of two.

```java
package org.rootstock.viz;

import org.littletonrobotics.junction.mechanism.LoggedMechanism2d;
import edu.wpi.first.wpilibj.util.Color8Bit;

/**
 * The generated 2D mechanism. Built from the same ArticulationSpec as the 3D chain.
 *
 * There is deliberately NO edu.wpi.first.wpilibj.smartdashboard implementation. That
 * package is removed in 2027 and is fenced off by doc 06's noDeadWpiApis ArchUnit rule.
 * We use AdvantageKit's fork, which is on the compile classpath unconditionally.
 *
 * Color8Bit is edu.wpi.first.wpilibj.util -- the `util` package, which is a straight
 * org.wpilib.* rename in 2027, not a removal. It is on the compat allowlist.
 */
public interface LoggedMechanism2dHandle {
  /** The real AdvantageKit object. Previously typed Object, purely to keep AdvantageKit
   *  types out of a public Rootstock signature. That constraint is gone. */
  LoggedMechanism2d raw();

  void setLigamentColor(String componentId, Color8Bit color);

  double totalWidthMeters();
  double totalHeightMeters();
}
```

**What every team gets, with no conditions:** all of §4.2–§4.6 — the full articulated 3D robot, all three ghost channels, `ZeroedComponents`, camera overrides, game-piece slots, the generated `config.json`, `rootstockAssets --verify` — **and** `Rootstock/Viz/Mechanism2d` with the AdvantageScope Mechanism2d projection. §4.2 remains the primary path and §4.7 the convenience, because the 3D path is computed by Rootstock's own forward kinematics and is therefore ours to keep working; §4.7 is one upstream class away from being someone else's problem (see the `[UNVERIFIED]` note below and §11.4).

Generation rule: each `ArticulationSpec` component becomes one `LoggedMechanismLigament2d` in the XZ plane, projected from the chain. Prismatic joints vary ligament **length**; revolute joints vary ligament **angle**. Root is at the bottom-center of the mechanism, matching AdvantageScope's stated projection origin.

Two important, non-obvious facts encoded in the implementation:
- You must use `LoggedMechanism2d` / `LoggedMechanismRoot2d` / `LoggedMechanismLigament2d`, **not** the WPILib classes — the WPILib ones will not compile with `Logger.recordOutput`, and referencing them fails `noDeadWpiApis` anyway. The two reasons agree.
- `Logger.recordOutput(key, mech)` snapshots the *current* state, so it must be called every cycle. `MechanismVisualizer.log()` does.

**Required companion change in doc 06.** The `noDeadWpiApis` rule keeps `edu.wpi.first.wpilibj.smartdashboard..` in its banned list with no exemption, and its comment is updated to read:

```java
/** Removed in 2027. Referencing any of these guarantees a rewrite.
 *  NOTE: there is no Mechanism2d exemption. Rootstock's 2D visualization uses
 *  AdvantageKit's fork (org.littletonrobotics.junction.mechanism.LoggedMechanism2d),
 *  which is available unconditionally because AdvantageKit is a required
 *  dependency (Decision 3) -- see doc 04 §4.7. */
@ArchTest static final ArchRule noDeadWpiApis = ...
```

**[UNVERIFIED — resolve at implementation time]** The AdvantageKit docs snippet shows `mechanism.generate3dMechanisms()` (plural); the deep-logging dossier reports the actual v26.0.2 source declares `public synchronized ArrayList<Pose3d> generate3dMechanism()` (**singular**). Do not copy the docs snippet blind. The Rootstock adapter must resolve this at compile time against the pinned AdvantageKit version and fail loudly if neither exists. Rootstock does not depend on this method for its primary path — §4.2's own forward kinematics is the source of truth for `Pose3d[]` — so a rename in AdvantageKit cannot break 3D visualization. **It can now break 2D visualization with no fallback whatsoever**, because the WPILib fallback is banned; §11.4 records that as an accepted, named consequence.

---

## 5. Log Management & Match Triage

### 5.1 Naming

There is one writer now, but `DataLogManager` still names the DS log, so Rootstock still normalizes both into the manifest rather than fighting either:

- **AdvantageKit `WPILOGWriter`** writes `akit_<yy-MM-dd_HH-mm-ss>_<event>_<matchtype><n>.wpilog` once FMS supplies a match number (e.g. `akit_25-03-29_14-08-10_ohmv_e5.wpilog`), and a randomized identifier before the DS connects. Constructors: `WPILOGWriter()`, `WPILOGWriter(String path)`, `WPILOGWriter(AdvantageScopeOpenBehavior)`, `WPILOGWriter(String path, AdvantageScopeOpenBehavior)`.
- **WPILib `DataLogManager`** writes `WPILib_TBD_{random}.wpilog` → `WPILib_yyyyMMdd_HHmmss.wpilog` → `WPILib_yyyyMMdd_HHmmss_{event}_{match}.wpilog`. Rootstock no longer *writes* through it, but a `.dslog` still appears next to the match log and the manifest indexes it.

Rootstock does **not** rename files after the fact (renaming an open file on the RIO is how you lose a match log). Instead it writes a sibling manifest and an index.

### 5.2 The per-match manifest

Written on disable-edge and again on `Robot.end()`, next to the log, named `<logstem>.manifest.json`. Everything in it is already in the log; the manifest exists so that **triage is grep-able and scriptable in the pit without opening AdvantageScope**.

```json
{
  "schema": 2,
  "wpilog": "akit_26-03-14_13-02-11_curie_q42.wpilog",
  "sidecars": [
    { "path": "ctre_logs/2026-03-14_13-02-11", "kind": "hoot", "replayable": false },
    { "path": "WPILib_20260314_130211.dslog",  "kind": "dslog", "replayable": false }
  ],
  "event": "curie", "matchType": "Qualification", "matchNumber": 42, "replayNumber": 0,
  "alliance": "Blue", "station": 2, "gameData": "",
  "robotIdentity": "COMP",
  "rootstockVersion": "0.1.0", "wpilibVersion": "2026.2.2", "advantageKitVersion": "26.0.2",
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
      "keys": ["Rootstock/Drive/ModulePositions"],
      "reason": "p95=8214B > budget=6000B for 50 cycles" }
  ],
  "minBatteryVolts": 8.9, "brownouts": 0, "canUtilizationMax": 0.71,
  "visionUptimeFraction": 0.94,
  "maxPoseDivergenceMeters": 0.31,
  "faults": [ {"name":"CAMERA_OFFLINE","count":2}, {"name":"PERF_Vision/Update","count":9} ],

  "triageHints": [
    "Auto did not complete: ScoreL4 interrupted at t=11.42s (see CommandsAll/ScoreL4).",
    "Pose divergence peaked at 0.31 m at t=10.9s -- check Rootstock/Vision/Front/RejectReason.",
    "118 cycles exceeded 20 ms; worst offender Rootstock/Perf/Vision/UpdateMs (14.1 ms p95).",
    "Log schema generation changed to 1 at t=12.61s: Rootstock/Drive/ModulePositions is sampled at 10 Hz after that point. Gaps in that ONE trace after 12.61s are expected, not data loss. Nothing else changed."
  ]
}
```

`"schema"` moves to **2** because two fields are removed and one is added: `backend` and `replayCapable` are gone (one had a single value, the other was structurally true) and `advantageKitVersion` takes their place. Any tooling written against schema 1 must be told; there is none outside this repository yet, which is one of the small dividends of not having shipped.

`triageHints` is generated by a small, fixed rule set — not machine learning, not heuristics that surprise you. The rules are documented and each one names the log key it fired on.

```java
public final class RootstockLogSession {
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

Hard constraints Rootstock encodes:
- USB sticks must be **FAT32**; NTFS/exFAT silently do not work, and Windows will not format >32 GB as FAT32. **Use a 32 GB stick.** Startup check: if `/U` is absent or unwritable, raise `Alerts.warning("Rootstock/Log", "LOG_USB_MISSING ...", MatchImpact.PIT_ONLY)` and fall back to `/home/lvuser/logs` — logging never stops silently.
- `DataLogManager` prunes `WPILib_` logs oldest-first below 50 MB free (floor of 10 files). CTRE `SignalLogger` deletes at 50 MB free and **stops logging entirely at 5 MB**. Rootstock's own governor stops writing and raises `Alerts.warning("Rootstock/Log", "LOG_DISK_LOW ...", MatchImpact.PIT_ONLY)` at `minFreeMegabytes` (default 200 MB) so you never reach either vendor's cliff mid-match.
- `withCompress(true)` enables an xz-compressed `.wpilogxz` receiver (6328's practice) with a transparent reader for replay and a `rootstockExtract` task. **Off by default**, and the reason is now sharper than it was: it trades roboRIO CPU for disk, and under Decision 3 CPU is not merely the scarcer resource — it is the resource with no remaining escape hatch (§2.7).

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

`pullLogs` ships as a Gradle task **and** as a `rootstock logs pull` subcommand of `rootstock-cli` (M23), because `CycleStats` (§5.7) put a CLI in the plan anyway and a scouting laptop without the repo should be able to pull. The old "Gradle in v0.1, CLI in v0.2" split is gone with the release split.

### 5.5 The documented 5-minute workflow: "why did auto fail in match 42"

This ships as `docs/triage.md` and is printed by `./gradlew pullLogs --help`.

```
0:00  Tether to the robot in the pit (USB or Ethernet).
0:10  ./gradlew pullLogs --match=q42 --open --sidecars
      Downloads the .wpilog + .hoot + .dslog, writes the manifest, opens AdvantageScope.

0:40  Read the manifest FIRST, not the graphs:
        cat logs/curie/akit_26-03-14_13-02-11_curie_q42.manifest.json | jq .triageHints
      In 8 of 10 cases the answer is already there. Four questions, in order:
        autoCompleted == false?  -> look at autoAbortReason.
        faults non-empty?        -> a named subsystem already told you.
        cyclesOver20ms > 50?     -> you had a CPU problem, not a logic problem.
        schemaGeneration > 0?    -> the governor throttled a named key at a named time.
                                    Read schemaBoundaries BEFORE you look at any trace, so
                                    you never mistake a governed 10 Hz key for a dropout.
                                    Only the five OUTPUT key patterns in §2.7 can
                                    ever appear here; no Inputs/ key can (§2.2a).

1:30  In AdvantageScope, load the shipped layout:  File > Import Layout >
      rootstock/layouts/AdvantageScope-Triage.json
      Four tabs appear pre-populated:
        [Field]    Rootstock/Field/Robot + RobotGhost + Trajectory + VisionPoses
        [Auto]     CommandsAll/*, Rootstock/<mech>/Goal|Setpoint|Measured|AtGoal
        [Vision]   Rootstock/Vision/*/Accepted, RejectReason, TagCount, Pose divergence
        [Perf]     Rootstock/Perf/*, LoggedRobot/FullCycleMS, Logger/QueuedCycle, battery

2:00  Scrub the Field tab to the moment the auto went wrong. Every other tab follows --
      AdvantageScope synchronizes the timeline selection across all tabs.

2:30  Classify. Exactly one of these is true and each has a different fix:
      (a) Robot ghost tracked the trajectory but the MECHANISM never reached AtGoal
          -> Auto tab: which mechanism's Measured flat-lined away from Setpoint?
             Check its Inputs/Connected, StatorCurrentAmps, LimitForward, SoftLimit*.
      (b) Robot ghost diverged from the measured robot
          -> drivetrain/odometry. Check Rootstock/Drive/VisionDivergenceMeters and
             Rootstock/Vision/*/RejectReason at that instant.
      (c) Everything tracked but a command was interrupted
          -> Auto tab, CommandsAll/*: find the boolean that dropped early.
      (d) Loop time spiked
          -> Perf tab. Fix the logging volume, not the logic.

4:00  If you still don't know, ADD A LOG LINE:
        RootstockLog.log("Rootstock/Superstructure/WhyNotScoring", reasonString);
      then:  ./gradlew replayWatch
      Replay re-runs the match at ~50x on your laptop and AdvantageScope refreshes on every
      save, preserving your time range and layout. The new field is now in the match-42 log.
      You can also attach a debugger and set a breakpoint at t=11.4s.

5:00  Write one line in the match log spreadsheet. Fix it. Next match.
```

Step 4:00 used to be prefixed *"if you are on the AdvantageKit backend."* That qualifier is deleted; it is the most user-visible thing Decision 3 buys, and it is worth noticing that the qualifier was in the *documentation a student reads at an event under time pressure* — exactly the worst place for a conditional.

It is still worth stating plainly to students: **replay is not a what-if simulator.** Modified outputs cannot change replayed inputs. You can add outputs and re-derive them from the recorded inputs; you cannot make the replayed robot take a different action. Teams who expect otherwise will be disappointed.

### 5.6 Replay regression verification

Turns "is my replay trustworthy?" into a number, in CI, against a checked-in reference log. Previously deferred to "v0.2"; now **M20**, alongside the lint, because the two answer the same question from opposite ends — the lint prevents divergence, `RootstockReplayVerify` detects it.

```java
public final class RootstockReplayVerify {
  public static ReplayReport verify(Path referenceLog);

  public interface ReplayReport {
    double cycleMatchFraction();
    List<String> divergedKeys();
    double firstMismatchSeconds(String key);
    double maxAbsoluteError(String key);
    String summary();

    /** Every Rootstock/Log/SchemaGeneration change found in the reference log. */
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

1. Reads `Rootstock/Log/SchemaGeneration` from **both** the recorded and the replayed stream and partitions the timeline at every change.
2. For any key named in a `Rootstock/Log/Governor/Demoted` array within a generation, compares values **only on cycles where the recorded stream published that key**. A cycle where a demoted key is absent from both streams is a match, not a miss. A cycle where it is absent from one and present in the other **is** a divergence and is reported — that catches a genuine governor bug.
3. Requires the generation *sequence itself* to match: `verify` fails if replay produced a different set of boundaries than the recording, because that means the governor is behaving nondeterministically, which is a real defect. This is guarantee **G9**.
4. Ships `Rootstock/Log/**` (including `SchemaGeneration` and `Governor/**`) and `Rootstock/Perf/**` in the **default** ignore set — governor decisions depend on measured wall-clock byte rates, which are legitimately different at 50× replay speed, and `Rootstock/Perf/**` comes from the monotonic clock by design (G3). Only the *boundary structure* from step 3 is checked, never the byte counts.
5. Prints boundaries in the report, so the human reading a failure sees them:

```
./gradlew replayVerify
  curie_q42.wpilog:  3424/3424 cycles match (100.00%)
    SCHEMA gen 0 -> 1 at t=12.61s  DEMOTED Rootstock/Drive/ModulePositions
           (expected discontinuity; 342 sparse cycles excluded from the diff)
  PASSED
```

Because the demotable set is a closed, five-pattern, **outputs-only** list fixed by the library (§2.7), the set of keys that can ever land in this path is small, enumerable, and testable — `RootstockTest` ships a case that forces the governor over budget in sim and asserts `replayVerify` still reports 100 %.

```java
@Test
void replayOfCurieQ42IsStillDeterministic() {
  var r = RootstockReplayVerify.of(Path.of("src/test/resources/logs/curie_q42.wpilog"))
      // Rootstock/Perf/** and Rootstock/Log/** are in the default ignore set; listed here
      // only to make the test self-documenting.
      .ignoring("RealOutputs/Rootstock/Perf/**", "RealOutputs/SystemStats/**",
                "RealOutputs/Rootstock/Log/**")
      .withTolerance("RealOutputs/Rootstock/Drive/Pose", 1e-6)
      .run();
  assertTrue(r.cycleMatchFraction() > 0.9999, r.summary());
  // A governed key gapping is expected. A governed key gapping DIFFERENTLY on replay is not.
  assertEquals(1, r.schemaBoundaries().size(), r.summary());
}

/** Pins G4 / §2.5A.1: a file-derived control value is REPLAYED, not re-read. Without
 *  RootstockConfig this test fails -- the constructor would pick up the mutated file and
 *  every AutoStep budget derived from it would diverge. This is the executable form of
 *  the correction the review forced; it did not exist before. */
@Test
void replayIgnoresLocalConfigFiles() throws IOException {
  Path cfg = Path.of("src/test/resources/logs/curie_q42.transition_costs.json");
  Path backup = Files.copy(cfg, tmp.resolve("orig.json"));
  try {
    // Mutate every transition cost by +50% -- enough to change which steps time out.
    Files.writeString(cfg, mutateAllCosts(Files.readString(cfg), 1.5));
    var r = RootstockReplayVerify.of(Path.of("src/test/resources/logs/curie_q42.wpilog")).run();
    assertTrue(r.cycleMatchFraction() > 0.9999,
        "a local config file changed a replayed output; G4 is broken: " + r.summary());
    assertTrue(r.divergedKeys().isEmpty(), r.summary());
  } finally {
    Files.copy(backup, cfg, StandardCopyOption.REPLACE_EXISTING);
  }
}

/** Pins §2.2a: forcing the governor over budget must not change a single replayed output.
 *  Runs the reference robot in sim with perCycleByteBudget set below the measured p95, so
 *  the governor demotes every key it is permitted to, then replays. If any Inputs/ key were
 *  ever marked demotable, this is the test that would catch it. */
@Test
void governorUnderPressureStillReplaysIdentically() {
  var log = RootstockTest.recordReferenceRobot(
      LogConfig.defaults().withPerCycleByteBudget(1_000));   // immutable value, §2.2b
  var r = RootstockReplayVerify.of(log).run();
  assertTrue(r.cycleMatchFraction() > 0.9999, r.summary());
  assertFalse(r.schemaBoundaries().isEmpty(), "governor never fired; the test proved nothing");
  assertTrue(RootstockBudget.demotableKeys().stream().noneMatch(k -> k.contains("/Inputs/")),
      "a replayed input is marked demotable; see §2.2a");
}
```

```
./gradlew replayVerify
  curie_q42.wpilog:  3421/3424 cycles match (99.91%)
    DIVERGED RealOutputs/Rootstock/Superstructure/AtGoal   first mismatch t=15.42s
    DIVERGED RealOutputs/Rootstock/Drive/Pose              max |dx| = 0.031 m
  FAILED (threshold 99.99%)
```

Mechanism: AdvantageKit already writes both `RealOutputs` and `ReplayOutputs` into the replay output file. The diff engine reads it with `edu.wpi.first.util.datalog.DataLogReader`. This is ~300 lines of glue nobody has written, and it is what makes replay usable by a team without a dedicated software mentor.

**`replayVerify` is a release gate at M24**, not merely a test: the guarantee in §2.5A is only credible if something checks it on every commit.

### 5.7 `CycleStats` — match analytics from a log, at zero robot cost

Previously listed under *"deferred past 2027 kickoff: cycle-time analytics."* Decision 1 removes deferral, so it is specified here and scheduled as part of **M23 (1.8 pw, shared with `rootstock gen mechanism`)**.

It lives **desktop-side, in `rootstock-cli`**, and reads topics the library already publishes. It adds **no robot-side schema and no robot-side cost** — that is the whole design constraint, and it is why this could be built late without changing anything upstream of it.

```java
package org.rootstock.stats;

public final class CycleStats {
  /** Parse one .wpilog. Everything below is computed from the §3 schema. */
  public static CycleStats fromLog(Path wpilog) throws IOException;
  /** Aggregate a folder, typically one event. */
  public static CycleStats fromFolder(Path folder) throws IOException;

  public int cycleCount();
  public double medianCycleSeconds();
  public double p90CycleSeconds();
  public double medianAlignSeconds();
  public double alignTimeoutRate();
  public double autoStepSuccessRate();
  /** The superstructure transition with the worst p90, by name. */
  public String slowestTransition();

  public void writeMarkdown(Path out) throws IOException;
}
```

```
rootstock stats logs/ --event curie
  12 matches, 3 practice runs
  Cycle count          median 7   (p10 5, p90 9)
  Cycle time           median 8.4 s   p90 11.2 s
  Align time           median 1.1 s   p90 2.9 s   timeout rate 6%
  Auto step success    91%  (worst: ScoreL4 at 72%)
  Slowest transition   STOW -> L4_SCORE   p90 1.8 s
  -> logs/curie/cycle-report.md
```

Source topics, all already in §3: `Rootstock/Superstructure/State`, `Rootstock/<Mech>/AtGoal`, `Rootstock/Field/Robot`, `Rootstock/Auto/StepName` + `StepResult` (doc 05), and `MatchContext` from the manifest. A cycle is a return to the state that follows a scoring transition; the definition is documented in `docs/stats.md` with the exact state-machine edges, because a metric whose definition is not written down is a metric two people will argue about at an event.

**Why this matters more than its 1.0 pw suggests:** it is the only feedback loop in the entire design that converts a small team's scarce practice time into a measured number. It is scheduled this late only because every topic it reads has to exist first.

---

## 6. Dashboards

### 6.1 Position

**Elastic is the driver dashboard. AdvantageScope is the programmer's tool. There is no third.** Shuffleboard, SmartDashboard, PathWeaver and RobotBuilder are all deleted in 2027 and deprecated in 2026. Rootstock's public API never mentions a WPILib dashboard type.

### 6.2 The driver mirror — a stable NT4 contract

**One of the three original justifications for this feature has died and two survive. The feature survives on the two.** Stating that explicitly, because a design that keeps a component after its headline reason evaporates should have to say why.

| Original justification | Status under Decision 3 |
|---|---|
| "The full telemetry stream lands under a **backend-dependent** NT path (`/AdvantageKit/RealOutputs/...` vs `/Rootstock/...`), and a shipped Elastic layout cannot bind to a moving target." | **Dead.** There is one path now and it is stable. |
| Elastic widgets need `Field2d`- and `SwerveDrive`-shaped NT tables that **no logging framework produces**, in any form, at any path. | **Alive and decisive.** AdvantageKit does not publish a `Field2d` table and never will; that shape is a dashboard contract, not a log contract. |
| The full stream is suppressed on FMS by `NtPolicy.OFF_ON_FMS`, and is thousands of topics at 50 Hz, while the drivers need ~30 topics at 10 Hz that are **never** suppressed. | **Alive.** These are opposite requirements on the same wire and cannot be served by one publisher. |

So Rootstock publishes a **separate, small, stable mirror** with raw NT4 publishers at **10 Hz**: about 30 topics, a few hundred bytes per update. It is never suppressed on FMS. It is the *only* thing the shipped Elastic layouts reference.

**These are NetworkTables topics, not a logging backend.** The distinction matters and is easy to lose: deleting the `LogBackend` SPI deleted nothing here. `NetworkTableInstance` still exists, `/Tuning` still exists (and is how AdvantageScope's tuning mode and `TunableDouble` work), and `/Rootstock/Driver/**` still exists. What was deleted was an abstraction over *log sinks*, and NT is a dashboard transport in this design, not a log sink.

```
/SmartDashboard/Field            Field2d-shaped table: ".type"="Field2d",
                                 "Robot" double[3] {x, y, degrees},
                                 "Ghost" double[3], "Trajectory" double[3n]
/SmartDashboard/Swerve Drive     ".type"="SwerveDrive",
                                 "Front Left Angle"/"Front Left Velocity" (and FR/BL/BR),
                                 "Robot Angle"
/SmartDashboard/Rootstock          WPILib Alert group -> Elastic Alerts widget
/SmartDashboard/Match Time       double
/Rootstock/Driver/Alliance         String
/Rootstock/Driver/AutoSelected     String
/Rootstock/Driver/AutoReady        boolean   (auto chosen AND start pose sane AND no kError)
/Rootstock/Driver/Ready            boolean   (rolls up every health check -- the ONE light)
/Rootstock/Driver/BatteryVolts     double
/Rootstock/Driver/CanUtilization   double
/Rootstock/Driver/SuperstructureState String
/Rootstock/Driver/HasGamePiece     boolean
/Rootstock/Driver/VisionOk         boolean
/Rootstock/Driver/VisionTagCount   double
/Rootstock/Driver/<Mech>/AtGoal    boolean   (one per registered mechanism)
/Rootstock/Driver/<Mech>/Measured  double
/Rootstock/Driver/Faults           String[]
```

Writing the Field2d and SwerveDrive tables by hand (rather than via `SmartDashboard.putData`) is deliberate: it avoids the `Sendable`/`SmartDashboard` API entirely, so nothing here breaks in 2027 — the NT *paths* survive even though the SmartDashboard *application* does not.

```java
public final class RootstockDashboard {
  /** Called automatically by RootstockRobot. Starts the layout web server so nobody forgets. */
  public static void init();                      // WebServer.start(5800, Filesystem.getDeployDirectory().getPath())

  public static void selectTab(String tab);
  public static void notify(Notify n);            // rising-edge + rate-limited
  /** Mirrors 06's AlertRegistry into the Elastic notification channel. */
  public static void bridgeAlertsToElastic();
  public static void autoSelectTab(String tab, BooleanSupplier when);
}

/**
 * `level` uses the same three-value vocabulary as Rootstock/Health/Worst (§3.5):
 * "ERROR" | "WARNING" | "INFO". It is a String and not edu.wpi.first.wpilibj.Alert.AlertType
 * deliberately -- §11's rule is that no WPILib type appears in a Rootstock public signature
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

Rootstock **vendors ElasticLib** (`Elastic.java`, single file, no dependencies, NT topics `/Elastic/RobotNotifications` and `/Elastic/SelectedTab`) inside its jar rather than making every team keep a private copy that drifts. `Notify` reuses a single instance internally to avoid the GC churn ElasticLib's own docs warn about, and rate-limits per (title, level) so calling `notify()` in `periodic()` cannot spam.

By default `RootstockRobot` calls `RootstockDashboard.autoSelectTab` on mode transitions: Disabled → `Setup`, Auto → `Autonomous`, Teleop → `Driver`, Test/Utility → `Diagnostics`.

### 6.3 Shipped Elastic layouts

Four `.json` files placed in `src/main/deploy/` by **RootstockTemplate** (Decision 2 — they **must** be at the deploy root for remote layout download to work) and re-emitted by `./gradlew rootstockLayouts`:

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
            "properties": { "topic": "/Rootstock/Driver/Ready", "period": 0.033,
                            "true_color": 4283215696, "false_color": 4294198070,
                            "true_icon": "None", "false_icon": "None" } },
          { "title": "Match Time", "x": 1280.0, "y": 256.0, "width": 256.0, "height": 128.0,
            "type": "Match Time",
            "properties": { "topic": "/SmartDashboard/Match Time", "period": 0.033,
                            "time_display_mode": "Minutes and Seconds",
                            "red_start_time": 15, "yellow_start_time": 30 } },
          { "title": "Alerts", "x": 1280.0, "y": 384.0, "width": 256.0, "height": 512.0,
            "type": "Alerts",
            "properties": { "topic": "/SmartDashboard/Rootstock", "period": 0.033 } }
        ]
      }
    }
  ]
}
```

`./gradlew rootstockLayouts` generates the *operator*, *tuning* and *diagnostics* tabs programmatically from the registered mechanism list, so adding a mechanism adds its widgets — the layout cannot go stale. A tiny Java emitter (`org.rootstock.dashboard.ElasticLayoutWriter`) builds the JSON above; grid units are 128 px, positions are `double`, and every widget carries `topic` + `period`.

**[UNVERIFIED]** Elastic's layout JSON schema is not published as a formal public contract, so it could change within the 2026.x line. Mitigation, which is not optional: `rootstockLayouts` emits `"version": 1.0` and Rootstock ships the four layouts as **checked-in files too**, so a schema change degrades to "regenerate them once", never to "the drivers have no dashboard."

**Decision 2 adds a maintenance obligation here.** The four layouts live in the template, and the template is regenerated and CI-tested against **every** library release (9 jobs: 3 OS × 3 variants). A layout that references a mechanism topic the library renamed now fails a release gate rather than a driver's Saturday. That is the correct trade, and it is part of the ~0.1 pw per release ongoing template tax the roadmap books.

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

`/Rootstock/Driver/Ready` is the rollup, computed off the Platform-owned alert registry (DESIGN.md D10 — there is no `RootstockFaults`):

```java
// AlertRegistry lives in org.rootstock.core.alert and is owned by Platform (06).
// This domain only READS it. The severity enum's exact spelling is 06's to fix; this
// document depends only on "there is an error-severity predicate".
boolean ready =
       !AlertRegistry.anyActiveAtError()
    && autoReady
    && allMechanismsHomed
    && gyroConnected
    && batteryVolts > 11.5;
```

Its inputs are logged individually so "why is it red" takes one glance at the operator tab. The same rollup is what `Rootstock/Health/Worst` (§3.5) reports as a string, so the driver light and the log agree by construction.

### 6.5 Shipped AdvantageScope layouts

Placed in `rootstock/layouts/` in **RootstockTemplate**, imported via `File > Import Layout`:

- `AdvantageScope-Triage.json` — the four tabs from §5.5.
- `AdvantageScope-Tuning.json` — line graph of `Measured`/`Setpoint`/`Goal`/`Error`/`Output` for the selected mechanism, plus `/Tuning` in tuning mode.
- `AdvantageScope-Viz.json` — 3D Field with the robot asset, both ghosts, and camera override wired to `Rootstock/Viz/Cameras/*`.

These can now hard-code `/AdvantageKit/RealOutputs/Rootstock/...` prefixes, because there is exactly one prefix. Under the prior design they had to be regenerated per backend or written against the driver mirror, which was strictly worse for a programmer's tool.

The template also points AdvantageScope's **Use Custom Assets Folder** at `src/main/deploy/ascope`, so robot models are version-controlled next to robot code (an explicitly supported AdvantageScope feature that almost nobody uses).

---

## 7. Simulation

### 7.1 Sim-by-default

The single largest source of duplicated code in every FRC repo is the ~40-line `simulationPeriodic()` glue per mechanism, and it contains a silent correctness trap CTRE documents verbatim: **rotor position/velocity is pre-gear-ratio, `DCMotorSim` returns post-gear-ratio.** Get it backwards and the vendor's onboard PID behaves nothing like reality, which makes tuning in sim worthless — and nothing tells you.

Rootstock's rule: **if a mechanism declared its geometry, it has a physics sim. No extra work, no second code path.**

```java
package org.rootstock.sim;

public interface MechanismSim extends AutoCloseable {
  /** Advance the model and write state back into the vendor sim. Called by RootstockSim.tick(). */
  void update(double dtSeconds);
  double positionMeters();          // or radians for angular sims
  double velocity();                // SI, mechanism side
  double currentDrawAmps();
  /** Raw WPILib model: DCMotorSim | ElevatorSim | SingleJointedArmSim | FlywheelSim. */
  Object raw();
}

public final class RootstockSim {
  public static ElevatorBuilder elevator();
  public static ArmBuilder      arm();
  public static FlywheelBuilder flywheel();
  public static RollerBuilder   roller();          // DCMotorSim, no gravity, no limits

  /** Registers a sim for automatic ticking and battery aggregation. Idempotent. */
  public static void register(String name, MechanismSim sim);

  /** Called by RootstockRobot.simulationPeriodic(). Never runs on real hardware. */
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
var sim = RootstockSim.elevator()
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
package org.rootstock.core.spi;   // D26's downward-crossing-value package, same as
                                   // MechanismGeometry, LogConfig, RobotMode and Tier.
                                   // The IMPLEMENTATIONS below live in org.rootstock.sim.

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
```

```java
package org.rootstock.sim;   // NOT core.spi: this factory reflects over vendor controller
                              // types, which is behavior, and core.spi holds values only.

import org.rootstock.core.spi.SimMotorHandle;

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

**[UNVERIFIED — must be pinned by a test]** REVLib's `SparkSim.iterate(velocity, vbus, dt)` velocity argument: the REV docs example passes *mechanism* RPM (`Units.radiansPerSecondToRotationsPerMinute(armSim.getVelocityRadPerSec())`) with no gear-ratio multiplication, which implies `iterate` expects velocity in the units the configured encoder reports (i.e. after `SparkBaseConfig` conversion factors). Rootstock must ship a unit test that drives a known velocity through `SparkSimHandle` and asserts the Spark's own `getEncoder().getVelocity()` matches the physics model. **Do not ship the REV adapter without that test passing.** The Phoenix 6 convention is documented and verified; the REV one is inferred. (This is roadmap risk **R3** and is a gate on **M3**, not on this document's milestones.)

Also encoded, from CTRE's docs: in simulation, bump Phoenix signal update frequencies (`signal.setUpdateFrequency(Hertz.of(1000))` when `Utils.isSimulation()`) for better simulated closed-loop fidelity.

### 7.3 Battery sag and brownout — on by default

Nothing in FRC models this unless you wire it yourself, so sim never reproduces the failure mode that actually loses matches. `RootstockSim.tick()` does it for free:

```java
public static void tick() {
  // Platform.isSimulation(), NOT RobotBase.isSimulation(): ArchUnit rule 2
  // (volatileApiIsConfined, design/06 §8) allows year-volatile WPILib API only inside
  // org.rootstock.core.compat, and RootstockSim is in org.rootstock.sim. Same call,
  // one hop through the facade. (Corrected 2026-08-08; this line was a live violation.)
  if (!org.rootstock.core.compat.Platform.isSimulation()) return;   // impossible on hardware
  double[] currents = new double[s_sims.size()];
  int i = 0;
  for (MechanismSim s : s_sims.values()) { s.update(0.020); currents[i++] = s.currentDrawAmps(); }
  double v = BatterySim.calculateDefaultBatteryLoadedVoltage(currents);
  RoboRioSim.setVInVoltage(v);
  RootstockLog.log("Rootstock/Sim/BatteryVolts", v, Volts);
  RootstockLog.log("Rootstock/Sim/TotalCurrentAmps", sum(currents), Amps);
  // DESIGN.md D10: alerts go through the Platform-owned facade. There is no RootstockFaults.
  s_simBrownout.set(v < 6.75);            // roboRIO 2.0 stage-2 brownout threshold
}

// Constructed once, at class init, NOT per cycle -- Alerts.warning(...) allocates a handle.
private static final RootstockAlert s_simBrownout =
    Alerts.warning("Rootstock/Sim", "SIM_BROWNOUT: simulated bus voltage below 6.75 V",
                   MatchImpact.PIT_ONLY);   // sim-only; cannot block a real match
static {
  s_simBrownout.sticky(true);   // a brownout that happened once still explains the run
}
```

`s_sims` is a `LinkedHashMap`, not a `HashMap` — guarantee **G7**. Battery aggregation sums a `double[]` whose order comes from that map, and floating-point addition is not associative, so an unordered map would make sim results non-reproducible run to run. This is exactly the class of bug the lint's R4 warns team code about, and the library has to hold itself to it first.

### 7.4 Vision sim

PhotonVision's `VisionSystemSim` is the only real camera sim in FRC, and it models calibration error, FPS **and latency distribution** — which means vision *rejection* logic (std-dev scaling, ambiguity gates, stale-measurement rejection) becomes regression-testable headlessly.

```java
public final class RootstockVisionSim {
  /** Wraps PhotonVision's VisionSystemSim with sane defaults per camera model. */
  public static RootstockVisionSim photon(AprilTagFieldLayout layout);
  public RootstockVisionSim addCamera(String name, Transform3d robotToCamera, CameraPreset preset);
  public void update(Pose2d groundTruth);
  /** Auto-called when headless/CI is detected: cameraSim.enableRawStream(false),
   *  enableProcessedStream(false). OpenCV stream rendering is expensive and pointless in CI. */
  public RootstockVisionSim headless();
}
```

**On Limelight:** the prior text here said *"Limelight has no simulation story at all"* and offered a `syntheticFor(...)` pose-injection stopgap. Under Decision 1 that is no longer the plan. `SimulatedLimelight` — **wire-format** simulation of the Limelight NT contract, driven by `PhotonCameraSim`, so a Limelight-only team exercises the **production decode path** in sim — is in scope as **M17 (3.5 pw)** and is owned by the Vision domain (doc 03), living in `rootstock-photonvision`. This document consumes it through the same `VisionCameraIO` seam as everything else and has no Limelight-specific code. The R7 gate on M17 (round-trip through the production decoder, validation against a real LL4 on a practice field, and the "do not tune ambiguity thresholds in sim" warning) is doc 03's to satisfy.

### 7.5 maple-sim — an optional adapter, in scope, never load-bearing

Previously *"v0.2."* Now **M21 (2.5 pw, shared with `RootstockAutoTest`)**. Optional at *runtime*, in scope at *build time* — those are different things and the old text conflated them.

**State as of 2026-08-07, verified:** the only 2026 release is **v0.4.0-beta (2026-01-17)**, flagged prerelease; GitHub `releases/latest` still returns v0.3.14 (2025-08-20). No stable 2026 release exists. The community has flagged succession risk (the primary developer graduated). Its 2026 REBUILT support is real and documented (`RebuiltFuelOnField`, `RebuiltFuelOnFly`, `withHitTargetCallBack`) but the library self-describes as beta with "potential bugs."

**Therefore:** maple-sim is a **separate optional adapter artifact** (`dev.rootstock:rootstock-maplesim`), discovered by `ServiceLoader`, never a core dependency. If absent, Rootstock falls back to plain WPILib mechanism sims and raises an *informational* Alert. A team can never fail to build because of maple-sim.

**This is the one surviving `ServiceLoader` use in this domain** (§1.5). The D26 machinery was simplified everywhere else because D28 put telemetry, tuning, sim and vision in one jar; maple-sim is genuinely out-of-jar and genuinely optional, so the discovery mechanism is load-bearing here and stays.

Roadmap risk **R13** makes the degraded path first-class: **a kinematic world with no maple-sim is a documented, supported configuration, not a failure path.** `RootstockAutoTest` (doc 05, M21) must produce a meaningful verdict without it. Depth lever **L5** would drop the adapter entirely for −0.9 pw and ship kinematic-only; it is not fired.

```java
package org.rootstock.sim;

public interface FieldSim {                    // implemented by the adapter
  void addRobot(SwerveSimConfig cfg, Pose2d start);
  void addGamePiece(String type, Translation2d at);
  void launchProjectile(ProjectileSpec spec);
  Pose3d[] gamePieces(String type);
  Pose2d groundTruthPose();
  void resetForAuto();
  void tick();
}

public final class RootstockFieldSim {
  /** Empty when the adapter is not on the classpath. */
  public static Optional<FieldSim> get();
  public static boolean available();
}
```

The adapter owns the two rules that make maple-sim safe, structurally:
1. `SimulatedArena.getInstance().simulationPeriodic()` is called **only** from `RootstockSim.tick()`, which returns immediately unless `Platform.isSimulation()` (the `core.compat` facade — ArchUnit rule 2; `RobotBase.isSimulation()` is not callable from `org.rootstock.sim`). maple-sim's docs warn it consumes roboRIO resources on hardware.
2. Any attempt to call `SimulatedArena.overrideSimulationTimings(...)` **throws with an actionable message**. maple-sim's docs, verbatim: "DO NOT override the timing if you are using AdvantageKit, as it only supports 50Hz robots." Under the prior design this check had to test whether AdvantageKit was on the classpath; **it is now unconditional**, which is one fewer branch and one fewer way to get it wrong. It is also a permanent restriction on Rootstock as a whole (§0 stance 5, §11.5).

The adapter also auto-publishes `Rootstock/Field/GamePieces/<Type>` from `getGamePiecesArrayByType(...)` and wires **odometry reset → arena pose reset in one place**, so the classic "physics sim and odometry didn't align" first-run failure cannot happen.

Honest caveat we document: maple-sim's projectile gravity is a tuned 11 m/s², not 9.81. Treat shooter sweeps as a starting point, not truth.

### 7.6 Swerve sim

WPILib still ships no swerve physics sim — `DifferentialDrivetrainSim` is the only drivetrain sim and the 2027 docs still say "Swerve support for simulation is in the works." Rootstock provides `SwerveSim`, a per-module `DCMotorSim` pair + `SwerveDriveKinematics` integration, as the always-available default, and defers to maple-sim's `SwerveDriveSimulation` (with real collisions) when the adapter is present. Same `DriveIO` either way — the drivetrain domain writes one implementation.

---

## 8. Testing & CI

### 8.1 Why teams don't test

Not unwillingness. The preamble is undocumented tribal knowledge scattered across three doc sites, and its failure modes are inscrutable: forget `assert HAL.initialize(500, 0)` and nothing works; forget `close()` and the *next* test fails with a port-allocation error; forget `DriverStationSim.setEnabled(true)` + `DriverStation.notifyNewData()` and every actuator reads 0.0; forget Phoenix's ~100 ms post-construction and ~20 ms post-request settle delays and your assertions are all zero. And **nothing calls `simulationPeriodic()` or `CommandScheduler.run()` for you** — WPILib's own official unit-test example never advances a control loop at all.

### 8.2 The harness

Shipped as a separate artifact `dev.rootstock:rootstock-test` (test-scope only; never on the robot).

```java
package org.rootstock.test;

import org.rootstock.core.spi.Tier;   // core.spi, not telemetry -- see §2.2

public final class RootstockTest implements BeforeEachCallback, AfterEachCallback {

  /** HAL init, DS enable + notifyNewData, SimHooks.pauseTiming, CommandScheduler reset,
   *  Logger started with NO data receivers and no replay source, vendor settle delays. */
  public static RootstockTest headless();

  /** Waits the documented Phoenix 6 settle time (~100 ms) after construction. Call once
   *  after all devices exist. No-op if Phoenix 6 is not on the classpath. */
  public RootstockTest afterConstruction();

  /** Anything AutoCloseable registered here is closed in @AfterEach, in reverse order.
   *  This is what prevents leaked HAL ports from poisoning the next test. */
  public <T extends AutoCloseable> T managing(T resource);

  /** Non-Subsystem mechanisms (the state-based style) need explicit ticking. */
  public RootstockTest ticking(Runnable... periodics);

  // ---- Time ----
  /** Ticks, in this exact order, at 50 Hz:
   *    1. registered periodics
   *    2. CommandScheduler.getInstance().run()      (this calls Subsystem.periodic() and,
   *                                                  in simulation, Subsystem.simulationPeriodic())
   *    3. RootstockSim.tick()                         (physics + battery)
   *    4. SimHooks.stepTiming(0.020)                (LAST, so timestamps advance after the cycle)
   *  The ordering is unobvious, there is no WPILib helper for it, and every team
   *  reinvents it or gives up. */
  public void stepSeconds(double seconds);
  public void step(int cycles);

  /** Returns true if the condition became true before the timeout. */
  public boolean runUntil(BooleanSupplier condition, double timeoutSeconds);

  /** Applies a control request and waits the documented ~20 ms Phoenix settle. */
  public void applyAndSettle(Runnable request);

  /** Raise the tier gate mid-test, the way the FMS attach does at §2.7.1 step 1 — except
   *  that a test may raise it further than the FMS gate ever does. This is how a test proves
   *  a key survives the gate rather than asserting it from the tier table by eye. There is
   *  no FMS simulator here and there does not need to be: `minimumTier` is the only thing
   *  the gate changes. */
  public RootstockTest withMinimumTier(Tier tier);

  // ---- Assertions ----
  /** Every alert raised through org.rootstock.core.alert during this test, in order.
   *  Named for the alert facade (DESIGN.md D10), not the deleted RootstockFaults. */
  public List<String> raisedAlerts();
  public double lastLoopMillis();
  /** Every Rootstock/Log/SchemaGeneration bump the governor made during this test. Empty is
   *  the expected result for any test that is not deliberately driving the budget over. */
  public List<Long> schemaGenerations();
  /** Assert a set of log keys was published this test. Reads the in-memory LogTable, so it
   *  needs no file and no data receiver. */
  public void assertTelemetry(String... keys);
}
```

**Note the `headless()` change.** It used to read *"`RootstockLog` into a `NullBackend`."* There is no `NullBackend` because there are no backends. What `headless()` does instead is start AdvantageKit's `Logger` with **no data receivers and no replay source** — the log tables are built in memory, `assertTelemetry` reads them, and nothing touches the disk or NetworkTables. That is strictly simpler than a null backend and it also makes the test harness exercise the *same* code path as the robot, which a null backend by definition did not.

### 8.3 The golden-path test template

Ships in **RootstockTemplate** as `src/test/java/frc/robot/ElevatorTest.java`, with narrative comments explaining *which regression each assertion pins*. Teams copy it.

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import static org.junit.jupiter.api.Assertions.*;

import frc.robot.subsystems.elevator.Elevator;
import frc.robot.subsystems.elevator.ElevatorIOSim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.rootstock.core.spi.Tier;   // core.spi as of 2026-08-08, not telemetry -- see §2.2
import org.rootstock.test.RootstockTest;

class ElevatorTest {
  @RegisterExtension
  final RootstockTest t = RootstockTest.headless();

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
   *  This is cheap and catches an entire class of "the graph is empty" problems.
   *  It is also the test that makes D24 worth its boilerplate: the key names are ours,
   *  hand-written in toLog(), and this asserts them literally. */
  @Test
  void publishesTheStandardSchema() {
    var elevator = newElevator();
    t.stepSeconds(0.1);
    t.assertTelemetry("Rootstock/Elevator/Measured", "Rootstock/Elevator/Setpoint",
                      "Rootstock/Elevator/Goal", "Rootstock/Elevator/AtGoal",
                      "Rootstock/Elevator/Inputs/Connected");
  }

  /** Pins §3.1's DeviceResetCount ruling: a motor controller that reboots mid-match must be
   *  visible in an FMS-attached log. Simulates the FMS gate by raising minimumTier the way
   *  §2.7.1 step 1 does, then asserts the key is still there. If a later editor moves
   *  DeviceResetCount back to STANDARD *and* a team raises minimumTier to CRITICAL, the key
   *  vanishes and this test is the only thing that says so. */
  @Test
  void deviceResetCountSurvivesTheTierGate() {
    var elevator = newElevator();
    t.withMinimumTier(Tier.CRITICAL);          // stricter than the FMS gate ever sets
    t.stepSeconds(0.1);
    t.assertTelemetry("Rootstock/Elevator/DeviceResetCount");
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
  @RegisterExtension final RootstockTest t = RootstockTest.headless();

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
  /** Pins replay determinism against a real, checked-in match log. Runs in CI.
   *  This test is the executable form of the §2.5A guarantee. */
  @Test
  void curieQ42StillReplaysIdentically() {
    var r = RootstockReplayVerify.of(Path.of("src/test/resources/logs/curie_q42.wpilog"))
        .ignoring("RealOutputs/Rootstock/Perf/**", "RealOutputs/SystemStats/**")
        .run();
    assertTrue(r.cycleMatchFraction() > 0.9999, r.summary());
  }
}
```

### 8.5 Gradle wiring

```groovy
// Required for sim to work at all. WPILib warns some vendor libraries crash under
// simulation; ./gradlew rootstockDoctor checks every vendordep for desktop artifacts.
wpi.java.debugJni = false
includeDesktopSupport = true

test {
  useJUnitPlatform()
  // Simulated CAN devices reject duplicate IDs within one JVM, and AdvantageKit's Logger
  // cannot restart within a JVM. Both force a fresh JVM per test class. The second reason
  // is now unconditional -- Logger always runs -- so forkEvery = 1 is mandatory, not a
  // consequence of a backend choice.
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

tasks.register('rootstockCheck') {
  dependsOn 'build', 'test', 'simTest', 'rootstockAssets', 'logBudget', 'replayVerify'
}
```

`replayVerify` joins `rootstockCheck` because under Decision 3 it checks a **guarantee**, not a feature. A guarantee that is not in the default check task is a claim.

### 8.6 GitHub Actions

Ships as `.github/workflows/ci.yml` in **RootstockTemplate**.

```yaml
name: CI
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

env:
  # Tied to Rootstock's supported WPILib version. `./gradlew rootstockDoctor` warns when the
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
        run: ./gradlew rootstockAssets --verify
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
            build/rootstock/**
```

> Note: GitHub Actions does not expand `env` in `container:`. The shipped file hard-codes
> `container: wpilib/roborio-cross-ubuntu:2025-22.04` on one line with a comment pointing at
> `rootstockDoctor`. The `env` block above is the documented single point of truth for the tag.

Everything above must run **headless, with no display, no hardware, no network.** Consequences already encoded: `HAL.initialize(500, 0)` in every test, PhotonVision streams disabled in CI, no test depends on wall-clock timing.

**Decision 2 adds a second CI surface: the library's own release matrix.** Every Rootstock release regenerates all three template variants and runs **9 jobs** — 3 OS (Windows / macOS / Linux) × 3 variants (swerve / differential / mechanism-only) — each doing a clean install, `./gradlew build`, and a headless `simulateJava`. This document's checks (`rootstockAssets --verify`, `replayVerify`, `logBudget`) run inside every one of those nine. It is real, recurring cost — ~0.1 pw per release, forever — and it is what makes "a team forks the template and it works" a tested claim rather than a hope.

---

## 9. End-to-End Example — what a team actually writes

A complete robot with a swerve drive, an elevator, an arm, one camera, full 3D visualization, deterministic replay, driver + tuning dashboards, physics sim, and CI. **Everything in this section is user code. There is no more.**

### 9.1 `Robot.java`

```java
package frc.robot;

import org.rootstock.core.RootstockRobot;
import org.rootstock.core.spi.LogConfig;   // core.spi, not telemetry -- see §2.2b

public class Robot extends RootstockRobot {
  public Robot() {
    // LogConfig is an IMMUTABLE value (§2.2b). defaults() then with*() copies; there is no
    // Consumer<LogConfig> lambda. `super()` alone would mean super(LogConfig.defaults()).
    super(LogConfig.defaults()
        .withWpilogFolder("/U/logs")
        .withCtreSignalLogger(true));       // .hoot sidecar for CAN forensics
  }
}
```

*(Corrected 2026-08-08. This block previously read `super(cfg -> { cfg.wpilogFolder = "/U/logs"; cfg.ctreSignalLogger = true; });` — the mutable-`Consumer` form superseded in §2.2b. `design/01` §1.1a's `RootstockRobot(LogConfig)` constructor and its §11 golden-path example are written against the shape above, so this is now the one spelling in both documents.)*

The former `cfg.backend = Backend.ADVANTAGEKIT;` line is gone, along with the `// or AUTO; or EPILOGUE if you don't want replay` comment beside it. That comment was, in retrospect, the whole of the prior design's problem in one line: it offered a choice whose consequences a rookie team could not evaluate, at the exact moment they were least equipped to evaluate it, and the wrong choice silently removed the feature the library exists to provide.

`RootstockRobot extends LoggedRobot` and does the rest: `Logger` metadata + receivers + replay source, the Elastic layout web server, the driver mirror, the tracer, alert publishing, `RootstockSim.tick()` gated on `Platform.isSimulation()` (the `core.compat` facade — ArchUnit rule 2), and the `publishAll()` call that emits every registered mechanism's schema block.

### 9.2 `RobotConstants.java` — the visualization declaration

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.math.geometry.*;
import org.rootstock.viz.ArticulationSpec;
import org.rootstock.viz.JointAxis;

public final class RobotConstants {
  private RobotConstants() {}

  public static final Transform3d ROBOT_TO_FRONT_CAM =
      new Transform3d(new Translation3d(0.20, 0.0, 0.80),
                      new Rotation3d(0.0, Math.toRadians(-20.0), 0.0));

  /** Component ids match mechanism telemetryName() values, so autoBind() wires the
   *  measured / setpoint / goal channels with zero further code. */
  public static final ArticulationSpec SPEC = ArticulationSpec.named("Rootstock")
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
`org.rootstock.units.Axis`, the canonical sealed geometry type (DESIGN.md D4). A file that
needs both — and a drivetrain constants file will — imports both without a collision. Likewise
`ArticulationSpec` (D25) does not read as a sibling of the mechanism-config `MotorSpec` /
`SensorSpec` / `FeedbackSpec` family, because it is not one: it describes a *rendered kinematic
chain*, not a control configuration.

### 9.3 `RobotContainer.java`

```java
package frc.robot;

import org.rootstock.viz.MechanismVisualizer;

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

That is **one field and one call** for: `Rootstock/Viz/Measured/Components`, `.../Setpoint/Components`, `.../Goal/Components`, `ZeroedComponents`, `Cameras/Front`, `GamePieces/Fuel` — **and** `Rootstock/Viz/Mechanism2d`. The four-way caveat that used to close this paragraph (*"had it declared EPILOGUE, DOGLOG or NT4, only the Mechanism2d key would be absent, with a one-time info Alert saying why"*) is deleted.

### 9.4 The elevator subsystem

```java
package frc.robot.subsystems.elevator;

import org.rootstock.mechanism.PositionMechanism;

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
| 2D viz | Generated `LoggedMechanism2d`, projectable onto the 3D field — **unconditional** |
| Assets | `config.json` generated and build-verified against the code |
| Dashboards | Four Elastic layouts, three AdvantageScope layouts, `Ready` rollup light |
| Sim | `./gradlew simulateJava` — full physics, battery sag, brownout, vision, no robot |
| Replay | `./gradlew replayWatch` — add a log line, see it in match 42 in under 10 s. **Guaranteed, not configured.** |
| Replay safety | `rootstock-lint` at build time (template users) + runtime tripwire (everyone) |
| Match analytics | `rootstock stats logs/ --event curie` — cycle time, align time, auto success, at zero robot cost |
| CI | Build + unit tests + sim tests + asset verify + replay verify + log budget |
| Triage | The 5-minute workflow in §5.5 |

**Total user code above: 3 files, ~53 lines** (two fewer than the prior revision — the `Backend` import and the backend assignment). The equivalent hand-rolled implementation in the author's own `0000-XXXX-Robot-Template` is `Dashboard.java` (311 lines) + per-mechanism sim glue (~40 lines × 10) + `Draggables/` index constants + `Robot.java` logger wiring + the elastic layout by hand.

---

## 10. What We Deliberately Do NOT Do

| We don't build | Because this already does it, well |
|---|---|
| A log file format | **WPILOG v1.0** — documented, tiny, trivially parseable, universally supported |
| A log viewer / graphing tool / 3D renderer | **AdvantageScope 26.0.2** — bundled with the WPILib installer, reads `.wpilog`/`.dslog`/`.hoot`/`.revlog`/`.csv`, synchronized timeline across every tab |
| A deterministic replay engine | **AdvantageKit 26.0.2** — the only one in FRC Java. We *require* it and add the determinism **guard** it lacks |
| An abstraction over logging frameworks | **Nothing.** We used to build this and no longer do. It was ~1.0 pw of this domain and it bought optionality we chose to give up (Decision 3). See §13.2 |
| An annotation-logging framework | **WPILib Epilogue** — in-tree, compile-time, zero reflection. We are **not** compatible with it: a team on Epilogue cannot adopt Rootstock without switching to AdvantageKit (§11.2). This is a change from the prior design, in which we were an Epilogue backend |
| A "simple logging call" API | **DogLog 2026.5.0** — we adopted its *fault semantics* as a model. We are **not** a DogLog backend and a team on DogLog cannot adopt Rootstock without switching (§11.2) |
| A per-key byte accountant inside the logger | **Nothing — this one we do have to build.** `Logger` exposes no byte accounting and `LogValue` exposes no size accessor, both verified against source. §2.7.0 is the smallest estimator that makes the governor's trigger and attribution real rather than asserted, and it is the one place in this document where "integrate, never reimplement" had nothing available to integrate |
| Another tunable-constant system | The **Tuning & Gains** domain owns `TunableDouble`; we only guarantee it lands under `/Tuning` (the exact table AdvantageScope tuning mode makes editable) and is backed by `LoggedNetworkNumber` so it replays (G6) |
| A driver dashboard application | **Elastic 2026.1.2** — WPILib's own recommended replacement for Shuffleboard/SmartDashboard |
| An AR/XR viewer | **AdvantageScope XR** (iOS/iPadOS). Our only lever is shipping low-poly assets so it stays performant |
| A CAN signal logger | **CTRE SignalLogger** (`.hoot`), **REVLib StatusLogger** (`.revlog`), **URCL**. We index them; we never wrap Hoot Replay as a replay engine |
| A general robotics viewer / plugin system | **Foxglove** via AdvantageScope's MCAP export. Documented as an export path in `docs/triage.md`; no code |
| Rigid-body field physics, game pieces, projectiles | **maple-sim** (dyn4j). Optional adapter (§7.5), in scope at M21, never a hard dependency |
| Per-mechanism physics models | **WPILib** `ElevatorSim` / `SingleJointedArmSim` / `FlywheelSim` / `DCMotorSim` / `BatterySim`. We wire them; we don't write the math |
| A camera simulator | **PhotonVision** `VisionSystemSim` — calibration error, FPS, and latency distribution. (Limelight *wire-format* sim on top of it is M17, owned by doc 03) |
| System identification | **WPILib** `SysIdRoutine` + the bundled SysId tool |
| A unit-test framework | **JUnit 5**, already bundled in GradleRIO |
| An AdvantageScope Lite integration | Nothing to build. §4.6's zip-per-asset output *is* the Lite `File > Upload Asset` format. One artifact, both eras |
| Anything on Shuffleboard, SmartDashboard, PathWeaver, RobotBuilder, or NT3 | All deleted in 2027 |
| **Monologue** support | Dead. Last commit 2024-06-21, last release v1.0.0-beta6 (2024-03-09), no 2025/2026/2027 vendordep. Migrating teams are told to move to Epilogue or DogLog — neither of which is a Rootstock path either |

---

## 11. Risks, Costs & the 2027 Migration

### 11.1 R18 — AdvantageKit is a single point of failure. Accepted, with a written contingency.

This is the largest consequence of Decision 3 for this domain, and it is **accepted, not mitigated**. The `LogBackend` escape hatch that used to make it survivable is gone by design. Concretely: **if AdvantageKit does not ship for WPILib 2027, Rootstock does not ship.**

The contingency is decided in advance rather than left as a bare accepted risk. It is stated in full in `ROADMAP.md` §4.2; the parts that bind this document:

- **Trigger, armed.** At the WPILib 2027 **beta** (~Dec 2027), if AdvantageKit has no public 2027 branch or has publicly stated it will not port, the contingency fires. Not at the alpha (too early to conclude anything), not at kickoff (too late to act).
- **Tier 1 — contribute.** Offer the port upstream *before* forking. A one-maintainer library forking another one-maintainer library over a platform migration is how a small ecosystem fragments. This is tier **1** because it is what gets executed first.
- **Tier 2 — fork.** Publish `dev.rootstock:rootstock-akit-compat`: a fork of the **minimum AdvantageKit surface this document actually uses** — `LoggedRobot`, `Logger`, `LogTable`, `LoggableInputs`, `WPILOGWriter`/`WPILOGReader`, `LogFileUtil`, the replay driver, `LoggedNetworkNumber`/`LoggedNetworkBoolean`, and `LoggedMechanism2d`/`Root2d`/`Ligament2d` — ported to `org.wpilib.*`, published under our coordinates, with a **stated public intent to delete it the day upstream ships** and upstream credited prominently. **~2.5 pw, budgeted as a contingency line and NOT included in the 74.0 pw or in §13's domain estimate.**

  **License: VERIFIED, and the tier is legal.** The `[UNVERIFIED]` tag that sat here was a verification failure — the plan's largest accepted risk was gated on a question a two-minute check answers. Checked 2026-08-08 against [AdvantageKit's `LICENSE` at `main`](https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/LICENSE): it is the **BSD 3-Clause License**, *"Copyright (c) 2021-2026 Littleton Robotics. All rights reserved."* Redistribution and modification, in source and binary form, with or without modification, **are permitted** subject to the three standard conditions. **Tier 2 exists.**

  **The one real constraint, and what we commit to because of it.** BSD-3's third condition is the non-endorsement clause, and AdvantageKit's copy names the parties explicitly: *"Neither the name of Littleton Robotics, FRC 6328 ('Mechanical Advantage'), AdvantageKit, nor the names of other AdvantageKit contributors may be used to endorse or promote products derived from this software without specific prior written permission."* That bears directly on how the compat artifact is named and described, so the commitments are made now rather than argued about under time pressure in December 2027:
  - The artifact is `rootstock-akit-compat`. `akit` is a **functional abbreviation describing what it is compatible with**, not a claim of endorsement — but if Littleton Robotics objects to it for any reason, we rename it, immediately and without argument. That is a standing commitment, not a negotiating position.
  - Neither the README, the release notes, the Maven description, nor any Rootstock marketing copy will say or imply that AdvantageKit, Littleton Robotics, FRC 6328 or Mechanical Advantage endorse, sponsor, approve or are affiliated with Rootstock or this fork.
  - The copyright notice, the license text and the disclaimer are reproduced verbatim in the source and in the binary distribution, per conditions 1 and 2. Attribution to upstream is prominent and factual: *"contains code derived from AdvantageKit, © 2021-2026 Littleton Robotics, BSD-3-Clause."*
  - **Residual, still open:** this is the license on `main` today. It could change, and the 2027 branch — if one appears — must be re-checked before the fork is published. That re-check is part of the M12 review in open question 10, not a fresh `[UNVERIFIED]`.
- **Tier 3 — wait, and say so first.** If neither works, Rootstock's 2027 line does not ship and the README says so on its first screen, before anyone adopts. Not discovered in January by a team that already forked the template.

**Note the tier numbering changed in this revision.** The previous revision numbered these fork/contribute/wait; `ROADMAP.md` §4.2 and `DECISIONS.md` MD3 agreed with that, while `DESIGN.md` §13.1 had contribute/fork/wait and `DESIGN.md` §5.6 item 8 contradicted §13.1 inside the same file. **Execution order wins: contribute, then fork, then wait**, because that is the order the tiers are actually attempted and a contingency whose numbering does not match its execution order is a contingency people will misread at speed. `ROADMAP.md`, `DECISIONS.md` and `DESIGN.md` §5.6 item 8 need the same edit; listed as contract requests.

**That list is the whole of this domain's exposure**, which is itself the argument for keeping the fork surface small: every additional AdvantageKit type this document reaches for makes tier 2 more expensive. `LoggedMechanism2d` (§4.7) is on that list purely for the 2D convenience path, and if tier 2 ever fires, dropping §4.7 is the first thing to cut.

### 11.2 Teams on DogLog or plain Epilogue cannot adopt. Unmitigated.

| A team currently running | Can adopt Rootstock? |
|---|---|
| AdvantageKit | Yes. This is the intended case. |
| Nothing / `SmartDashboard` only | Yes — but they inherit AdvantageKit's IO-layer discipline and its opinions along with it. A rookie team that wanted a tuning wizard now also gets a logging framework and a hard rule about where hardware reads may live. That is a real pedagogical cost and it is not optional. |
| **DogLog** | **No, not without switching loggers.** DogLog is cheap to adopt and widely used *precisely because* it is cheap; asking a team to leave it for a tuning wizard is a large ask and some will decline. |
| **Plain Epilogue** (WPILib first-party) | **No, not without switching loggers.** This one gets worse every year, because Epilogue is the first-party path and is where WPILib is investing. |

**This document used to be the reason those teams could adopt** — the multi-backend facade in §2 *was* the compatibility story. There is nothing left here to replace it. The honest summary: **Decision 3 buys a guarantee and pays for it with reach**, and the population it reaches shrinks as Epilogue improves. This is roadmap risk **R20** (relevance decay) acting on this domain specifically, and the only real mitigation is shipping sooner, which Decision 1 does not do.

### 11.3 There is no longer a CPU escape hatch. Partially mitigated.

AdvantageKit's roboRIO CPU cost is real and documented: 80–150 ms loops on Chief Delphi with the stock 2026 vision+swerve templates, up to 600 ms in the worst reports. Previously a team in that hole had a blunt but effective escape — `Backend.NT4`, lose replay, keep everything else in this document. **That escape is gone.**

What remains, and what it is honestly worth:

| Lever | Reduces | Does NOT reduce |
|---|---|---|
| `Tier` + the FMS gate (§2.7.1) | DEBUG-tier evaluation and bytes | AdvantageKit's fixed per-cycle cost |
| The byte-budget governor (§2.7) | Up to **10.6 %** of the *governable* (facade + inputs) bytes, and only from five marked **output** patterns — derived in §2.7 | Anything not marked `Demotable.YES`; **every replayed input** (§2.2a); the entire framework bucket (§2.7.0 bucket C) |
| `./gradlew logBudget` (§2.7) | Nothing at runtime — it tells you before the event | Anything |
| Deleting log calls | Bytes, proportionally | The `processInputs` serialization floor |

All four are **volume** levers. None reduces AdvantageKit's own fixed cost: the per-cycle `LogTable` serialization for every registered IO key, plus the writer thread's queue. A team whose problem is the floor rather than the volume has no lever at all.

The mitigation is a **gate, not a feature**: **M5's gate is a measured p95 loop time on a real roboRIO 2** with the reference robot, inside the `DESIGN.md` §12.6 budget — measured, not asserted, and re-measured at M24. If that gate fails, the response is to fix Rootstock, because the team no longer has a way to fix it themselves. That is a heavier obligation than the prior design carried and it should be treated as one.

### 11.4 `Mechanism2d` now has no fallback of any kind.

`Rootstock/Viz/Mechanism2d` depends on `org.littletonrobotics.junction.mechanism.LoggedMechanism2d`, and there is **no** WPILib fallback — `edu.wpi.first.wpilibj.smartdashboard` is removed in 2027 and banned by `noDeadWpiApis`, so a fallback cannot be written and still pass the library's own architecture test. Add the open `[UNVERIFIED]` question about `generate3dMechanism()` vs `generate3dMechanisms()` (§4.7) and the 2D path has a single upstream point of failure with no degraded mode.

Mitigation, partial and honest: **§4.2's 3D path is Rootstock's own forward kinematics**, publishing plain `Pose3d[]` structs, and is unaffected by anything AdvantageKit does to its mechanism classes. If §4.7 breaks, teams lose the 2D projection and keep the articulated 3D robot. That is a real degradation with a real floor, but it is a degradation the library takes rather than one the team can act on.

### 11.5 50 Hz is now a permanent property of Rootstock.

AdvantageKit supports 50 Hz robots only. Under the prior design that constrained one backend; now it constrains the library. Rootstock cannot support a 100 Hz control loop, and cannot support a team that wants one, for as long as Decision 3 stands. This will matter more on SystemCore in 2027+ than it does on a roboRIO 2 in 2026, and there is no mitigation short of reversing Decision 3.

### 11.6 The rest

| Risk | Mitigation |
|---|---|
| WPILib 2027 renames every package `edu.wpi.first.*` → `org.wpilib.*` | No WPILib type appears in a Rootstock **public signature** except geometry (`Pose2d`, `Pose3d`, `Transform3d`), `Measure`/`Unit`, `DCMotor`, and `Color8Bit` (§4.7, `util` package — a rename, not a removal). Everything else is behind a Rootstock type. The port becomes a mechanical internal change — **M12, 8.0 pw, the only date-triggered milestone** |
| Shuffleboard/SmartDashboard deleted | Already never used. The `/SmartDashboard/<Group>` NT *path* for Alerts and the Field2d/SwerveDrive widget tables survive; we write those paths by hand, not through the removed API |
| Field origin moves to field-center/+X-away-from-red in 2027 | `org.rootstock.field.RootstockField.coordinateSystem(FieldOrigin.BLUE_WALL_2026 → CENTER_RED_2027)`, owned by Drive/Auto (05) per DESIGN.md D14. Every pose reaches this domain already converted; `org.rootstock.viz.FieldGhosts` (§1.4) publishes what it is handed and flips nothing. The 2027 origin move is a one-line change in **05**, invisible here |
| **AdvantageKit does not port to 2027** | **§11.1. Accepted risk with a written three-tier contingency (contribute → fork → wait), and the license gating the fork tier is now VERIFIED BSD-3-Clause.** The old mitigation for this row — "drop to `Backend.EPILOGUE` or `Backend.NT4` on 2027 kickoff day and keep every other feature in this document" — is **deleted**, and it was the single most load-bearing sentence Decision 3 removed |
| AdvantageKit CPU cost on the roboRIO is real | **§11.3.** Tiers + governor + `logBudget` + the mandatory `Sim/Enabled` key (one 2026 team's real root cause was `simMode` left on) — and a measured p95 gate at M5 and M24, because there is no longer a cheaper backend to fall back to |
| Protobuf/record/new-struct first log blocks >100 ms | We ship **no** custom structs. We use WPILib's, and warm every one during `disabledInit` before the first enable |
| Elastic layout JSON schema is not a formal public contract | Layouts are checked in as files **and** regenerable; a schema break costs one regeneration, never a working dashboard. Now also covered by the 9-job template CI matrix on every release |
| maple-sim is beta-only for 2026 with succession risk | Optional adapter, `ServiceLoader`-discovered, graceful fallback + Alert. R13 makes the kinematic world a first-class documented configuration, not a failure path |
| Replay determinism can be broken silently by user code | The lint (§2.6), **now in scope as M20** rather than deferred. **No FRC library we surveyed does this.** Its reach is limited to template users and §2.6 says so |
| `rootstock-lint` reaches only template users | Not fully mitigable. A vendordep cannot add an `annotationProcessor` line. Non-template adopters get the runtime tripwire and one documented line to add. Stated in §2.6, `docs/UPDATING.md`, and the adoption matrix |
| WPILib `Alert` API is documented as "likely to change in future seasons" | Wrapped behind `org.rootstock.core.alert` (`Alerts` / `RootstockAlert`), owned by Platform (06) per DESIGN.md D10. No Rootstock public signature names `edu.wpi.first.wpilibj.Alert`. Its instability becomes 06's internal migration, not a user break |
| `Mechanism2d` lives in a package deleted in 2027 and banned by `noDeadWpiApis` | We reference it **nowhere**. 2D viz uses AdvantageKit's fork, unconditionally (§4.7). **§11.4** records the new consequence: no fallback exists |
| ArchUnit rule 1 weakened — AdvantageKit is now visible from four library packages (plus `LogTable`/`LoggableInputs` in every `..io..` package) instead of one artifact | Rule rewritten (§2.2, authoritative text in `DESIGN.md` §8 rule 1c) as a two-clause package allowlist rather than deleted. Weaker enforcement than an artifact boundary, and stated as such |
| `SimHooks.stepTiming()` can hang (allwpilib #6641) when called from a `SimDeviceSim` value-changed callback with another `SimDeviceSim` present | `RootstockTest` never registers value-changed callbacks; documented in the harness Javadoc |
| The byte-budget governor could be pointed at a replayed input again by a later editor | **§2.2a**, enforced three ways: `processInputs` has no `Demotable` overload (type level), `rootstock-lint` errors on `Demotable.YES` for any `/Inputs/` key literal (build level), and `governorUnderPressureStillReplaysIdentically` runs the governor over budget and diffs the replay (CI level, §5.6) |
| The governor's byte numbers are estimates, and `Logger`/`LogValue` expose no byte accounting | **§2.7.0** specifies the estimator in full: per-call sizing from the static type using the WPILOG v1.0 payload table, `LogTable.getAll(true)` enumeration for inputs, and a measured "Framework" bucket for everything outside the facade. The undercount direction is stated (A+B under-count total file growth; the governor fires early, never late) and `HEADER_BYTES=11` is re-measured against a real WPILOG at M5 |
| A boot-time config file changes control flow and is re-read on a developer's laptop during replay | **§2.5A.1** `RootstockConfig` — a one-shot `LoggableInputs` channel processed before any subsystem is constructed, so the replayed run uses the *match's* values. Pinned by `replayIgnoresLocalConfigFiles` with a deliberately mutated `transition_costs.json` |
| Log schema is a hand-written contract (D24) and hand-written `toLog`/`fromLog` can drift from it | `publishesTheStandardSchema` (§8.3) asserts key literals; `RootstockReplayVerify` diffs every key; the docs-as-tests CI at M24 compiles every documented example |

---

## 12. Open Questions

1. **Does `LoggedMechanism2d` expose `generate3dMechanism()` (singular) or `generate3dMechanisms()` (plural) in AdvantageKit 26.0.2?** The docs say plural; the dossier's source read at tag v26.0.2 says singular. Resolve by compiling against the pinned vendordep before shipping. Impact is **higher than it was** — see §11.4 — because there is no non-AdvantageKit fallback any more. Still not fatal: our own FK is the primary 3D path.
2. **REVLib `SparkSim.iterate(velocity, vbus, dt)` velocity units.** Mechanism-side or motor-side? The REV doc example implies encoder-reported units after conversion factors. Must be pinned by the test in §7.2 before the REV sim adapter ships (roadmap R3, gated at M3). Getting this wrong makes REV sim tuning silently meaningless — exactly the bug class Rootstock exists to eliminate.
3. **Is Elastic's layout JSON schema stable across the 2026.x line?** If not, `rootstockLayouts` needs a version gate. Needs a diff of a layout exported from 2026.0.0 vs 2026.1.2.
4. **Should `LogConfig.compress()` (xz `.wpilogxz`) default on?** It solves USB capacity and download time, but spends roboRIO CPU — and under §11.3 CPU is the resource with no escape hatch. Proposal: default **off**, measure real CPU cost on a roboRIO 2.0 alongside the **M5 p95 gate** (the measurement rig exists there anyway), and revisit before the M24 API freeze. There is no "v0.2" to defer it to.
5. **Does the driver mirror belong in this domain or in Competition-Day (06)?** It publishes `Ready`, which rolls up health checks that domain owns. Current split — this domain owns the *transport and schema*, 06 owns the *checks*. Confirm with that owner. Unchanged by Decision 3.
6. **~~Does AdvantageKit's annotation processor work outside `frc.robot`?~~ RESOLVED — see §2.4.1.** Verified 2026-08-07: `@AutoLog` has **no** package restriction and generates into the annotated type's own package. `@AutoLogOutput` **does** have one (same package as `Robot`, or lifted by `AutoLogOutputManager.addPackage`), *and* an independent reachability requirement — the object must be found by a recursive field search from `Robot` — which Rootstock's registry-held objects do not satisfy. **D24 is kept, but on replacement reasons (schema ownership, M12 build simplicity), because the originally stated reasons do not survive verification.** §2.4.1 states this plainly, including that reversing D24 for *inputs* is now technically unblocked if a future maintainer wants it.
7. **How do we ship reference logs for `replayVerify` without bloating the repo?** A 10-minute match log is tens of MB. Options: Git LFS, a trimmed 20-second slice, or a synthetic log generated in CI. Proposal: ship a **20-second auto-only slice**, which is where replay regressions actually matter. More urgent now than before: `replayVerify` is a release gate (§8.5), not an optional check.
8. **AdvantageScope's 2026 FRC field model matches the WELDED AprilTag layout.** Poses computed from the AndyMark layout render ~0.5 in off. Should Rootstock log which layout was used (`Rootstock/Vision/TagLayout`) and warn on mismatch? Leaning yes; needs the Vision domain's agreement on where the layout choice lives. (Doc 03's `FieldLayouts.resolve/fingerprint` at M10 probably already answers this — confirm.)
9. **~~Do we ship `pullLogs` as a Gradle task, a standalone CLI, or both?~~ RESOLVED — both.** `CycleStats` (§5.7) puts `rootstock-cli` in the plan regardless, and a scouting laptop without the repo should be able to pull. The old "Gradle in v0.1, CLI in v0.2" split died with the release split.
10. **Does the fork contingency's surface stay small, and is the license still BSD-3?** §11.1's tier **2** is sized at ~2.5 pw against a specific list of AdvantageKit types. That list should be **re-counted at M12** and again at M24; if it has grown, either trim it or re-price it, because a contingency whose cost is stale is not a contingency. **The same review re-checks the LICENSE file** — it is BSD-3-Clause today (verified 2026-08-08) and that is what makes tier 2 exist, but the 2027 branch is a different tree and must be read before anything is published.
11. **NEW — is `HEADER_BYTES = 11` right?** §2.7.0 derives it from the WPILOG v1.0 record layout for a robot with ~500 entries and microsecond timestamps. It is an accounting constant, not a measurement, and the whole governor trigger scales with it. Measure it against a real match WPILOG at **M5**, alongside the p95 loop-time gate, and publish the measured value in `docs/lint.md` next to the false-positive rate. Also resolve there whether a plain WPILOG `string` payload carries an in-payload length prefix — the v1.0 spec states one explicitly for `string[]` and not for `string`, and §2.7.0 currently assumes none.
12. **NEW — should `RootstockConfig` refuse to run at all if `contentHash()` differs from the replayed log's?** Today it uses the match's values and reports the difference. The alternative — hard-failing the replay — is arguably more honest, but it makes a replay unrunnable on a laptop whose checkout has moved on, which is the normal case at 11 pm in week 4. Leaning "report, don't refuse"; confirm before M20 freezes `RootstockReplayVerify`'s behavior.
13. **NEW — `Tier`/`RobotMode` moved to `org.rootstock.core.spi`; the reference side turned out to need no edit, and the check that established that is recorded here rather than assumed.** The move is settled (§2.2, §2.2b). The §2.2b residue note anticipated a coordinated five-document import fix as the remaining step; **run on 2026-08-08, that step is already satisfied and was smaller than predicted.** `grep -rnE "^\s*import\s+org\.rootstock\.(telemetry|core\.spi)\.(Tier|RobotMode)" design/` returns live import lines in only two documents — `design/01` (already reading `core.spi`, applied in its own amendment at §1.1a/§1.1b/§7.1) and this one — and `grep -rnE "org\.rootstock\.telemetry\.(Tier|RobotMode)" design/` returns **no live reference anywhere**, only two labeled history notes in `design/01` that correctly describe the pre-move state. `design/02` (5 mentions), `design/03` (2), `design/05` (4) and `design/06` (7) name `Tier` **unqualified, in prose and key tables, with no import line and no package spelling**, so a package move does not touch them. **The predicted blast radius was real for `design/01` and notional for the other four**; that is stated plainly because the residue note's cost estimate is the thing a future reader would otherwise trust. **Rule-9 status, stated exactly, with the command that produced it.** The check is *"does any public signature in `org.rootstock.core` or `org.rootstock.core.spi` name an `org.rootstock.telemetry` type?"* Run over this file on 2026-08-08: `grep -nE "^\s*import\s+org\.rootstock\.telemetry" design/04-telemetry-replay-viz.md` returns **zero matches** — no code fence in this document imports a telemetry type at all. *(A looser `grep -n "org\.rootstock\.telemetry\."` does match, at lines 58, 359, 1338 and in this very item, but every one of those is prose or a history note — `RootstockLog`'s own FQN in a D9 quotation, the superseded `LogConfig` location, `RootstockTracer` in the rule-3 discussion, and this row. None is a declaration or a signature, and the distinction is stated here rather than hidden behind a friendlier grep.)* The one `core.spi` declaration block in this document — `LogConfig`, §2.2b — now names `RobotMode` and `Tier` as **same-package** types with no import at all. **Within this document's owned surface rule 9 is green.** It is *not* green project-wide until the five edits above land, and this document does not claim otherwise; `DESIGN.md` §16's rule-9 item closes when they do.

---

## 13. Milestone Mapping & Effort

### 13.1 Where this document's content lands

There is no v0.1/v0.2/v0.3 split. Decision 1 puts **everything here in v0.1**; what used to be a release plan survives only as build order. [`ROADMAP.md`](../ROADMAP.md) is authoritative.

| Milestone | This document's share |
|---|---|
| **M5** — Telemetry, physics sim, test harness · 2.2 pw | `RootstockLog` tiered facade writing to `Logger` directly (§2.2–2.4), including `timestamp()`/`isReplay()`/`debugEnabled()`; `TelemetrySource` as a registration contract + `RootstockTelemetry.publishAll()` and its schema audit (§1.1.0, §1.5); the automatic schema (§3); `Demotable` + the input invariant (§2.2a) + the byte estimator (§2.7.0) + `RootstockBudget` + `SchemaGeneration` (§2.7); `RootstockConfig` (§2.5A.1); the `/Rootstock/Driver` mirror + `Ready` rollup (§6.2, §6.4); `RootstockSim` incl. battery sag and `dumpDevices` (§7.1–7.3); `RootstockTest` (§8.2–8.4). **Gates: measured p95 on a real roboRIO 2 (§11.3), and `HEADER_BYTES` measured against a real match WPILOG (§2.7.0, open question 11).** |
| **M6** — Tunables + Elastic · 1.1 pw | `ElasticLayoutGenerator` / `rootstockLayouts` (§6.3) and the `/Tuning` contract this domain guarantees (G6). Owned by doc 02; consumed here. |
| **M8** — RootstockTemplate, distribution, docs v1 · 2.8 pw | The four Elastic layouts and three AdvantageScope layouts move into the template (§6.3, §6.5); `.github/workflows/ci.yml` (§8.6); `docs/triage.md` (§5.5); the 9-job template CI matrix that runs this document's checks. |
| **M19** — 3D visualization · 2.0 pw | All of §4: `ArticulationSpec`, `JointAxis`, `MechanismVisualizer`, `FieldGhosts`, `AssetExporter`, `rootstockAssets` + drift check, `rootstockAssetCalibrate`, generated `LoggedMechanism2d`. |
| **M20** — Replay safety, enforced · 2.5 pw | `rootstock-lint` (§2.6) with its measured false-positive gate, plus the two §2.2a lint rules (`Demotable.YES` on a `/Inputs/` or `Rootstock/Driver/` key is a build error; `demotableKeys()` must match the §2.7 table); `RootstockReplayVerify` (§5.6) including `replayIgnoresLocalConfigFiles` and `governorUnderPressureStillReplaysIdentically`; the runtime tripwire. |
| **M21** — Headless auto validation + maple-sim · 2.5 pw | The maple-sim `ServiceLoader` adapter (§7.5). `RootstockAutoTest` is doc 05's half. |
| **M23** — Match analytics · 1.8 pw | `CycleStats` + `rootstock stats` (§5.7) and `rootstock logs pull` (§5.4). `rootstock gen mechanism` is doc 01's half. |
| **M24** — Release hardening · 5.1 pw | `replayVerify` as a release gate; the zero-allocation gate; docs-as-tests over every example in this document; the seeded-fault CSA test, which uses §5.5 as its script. |
| **M12** — The WPILib 2027 port · 8.0 pw | `edu.wpi.first.*` → `org.wpilib.*` across this domain; `/U/logs` → `/u/logs`; `/home/lvuser` → `/home/systemcore`; the CI image bump the §8.6 comment warns about. Date-triggered; see `ROADMAP.md` §7. |

Also relevant: `SimulatedLimelight` (**M17**, 3.5 pw) is doc 03's, not this document's, though §7.4 consumes it.

### 13.2 Effort — recomputed, with the multi-backend saving stated separately

**Prior estimate for this domain:** 9–12 pw ("v0.1") + 5–7 pw ("v0.2") = **14–19 pw raw**, plus an unpriced "deferred past 2027 kickoff" bucket containing cycle-time analytics.

| Change | Δ pw | What |
|---|---|---|
| **Decision 3 — drop multi-backend support** | **−1.00** | itemized below |
| Decision 1 — un-defer cycle-time analytics | **+1.00** | `CycleStats` + `rootstock stats` (§5.7), previously "deferred past 2027 kickoff" and unpriced |
| Decision 2 — template obligations | **+0.20** | this domain's share of authoring the layouts/CI/`docs/triage.md` into `RootstockTemplate` and keeping them green across the 9-job release matrix |
| **Review 2026-08-08 — the governor's byte estimator (§2.7.0)** | **+0.10** | Specified from nothing: three buckets, a per-type size function against the WPILOG v1.0 payload table, the `SizingInputs` wrapper, the measured framework bucket and its G4 exemption, plus the M5 measurement that validates `HEADER_BYTES`. Lands in **M5**. |
| **Review 2026-08-08 — `RootstockConfig` (§2.5A.1)** | **+0.05** | One `LoggableInputs` struct, three `register*` methods, `load()` ordering inside `RootstockLifecycle`, the content hash, and `replayIgnoresLocalConfigFiles`. Small because the machinery it reuses (`processInputs`, the replay verifier) already exists. Lands in **M5**, test in **M20**. |
| **New domain estimate** | | **14.35 – 19.35 pw raw, midpoint 16.85** |

**Arithmetic, shown because the previous revision's did not reproduce.** The prior estimate was **14–19 pw raw**. The deltas are −1.00 + 1.00 + 0.20 + 0.10 + 0.05 = **+0.35**. So 14.0 + 0.35 = **14.35** and 19.0 + 0.35 = **19.35**; midpoint (14.35 + 19.35) / 2 = **16.85**. The previous revision applied a net +0.20 and then printed "14.0 – 19.0", which silently dropped it — the range had not moved at all. It has now, by a third of a person-week, which is small but is not zero and should not be rounded into invisibility. **[`ROADMAP.md`](../ROADMAP.md) remains authoritative for the milestone column**, and both of the new rows land inside M5 and M20, which `ROADMAP.md` prices at 2.2 pw and 2.5 pw respectively; if it does not absorb +0.15, the milestone figure is what changes, not this table.

**The multi-backend saving, itemized (−1.00 pw):**

| Deleted | Δ pw |
|---|---|
| The `LogBackend` SPI and its three non-AdvantageKit implementations — `Nt4LogBackend`, `EpilogueLogBackend`, `DogLogLogBackend` — each of which had to implement the full ~30-overload value API of §2.3, plus configure/start, console capture, DS capture, and fault mapping | −0.50 |
| The per-backend test matrix: every schema, tier, governor and `Demotable` test ×4 backends, plus the four-way §2.8 mapping table's own conformance tests | −0.15 |
| `@AutoInputs`, its javac annotation processor, `RootstockInputs`, `LogSink`, `LogSource`, and the generated-class machinery — replaced by using AdvantageKit's `LoggableInputs` directly (§2.4) | −0.25 |
| The `mode = REPLAY` refusal path, `replayCapable()`, `activeBackend()`, `Backend.AUTO` classpath probing, the two-loggers-active detection, `Rootstock/Log/Caveats`, and the `everyN` fallback counter | −0.05 |
| Backend-gating of `Mechanism2d`: the `Optional`, the `Object raw()` indirection, the one-time info Alert, and the four-way caveat matrix in §9.5 | −0.05 |
| **Total** | **−1.00** |

Two honest observations about that table:

1. **The saving is smaller than the deletion looks.** Roughly half of it is the three backend implementations, and those were mechanical — thirty near-identical overloads each. The genuinely valuable saving is the second row: **the per-backend test matrix**, which is the part that would have kept costing forever. A four-way matrix does not merely cost 4× to write, it costs 4× to keep passing across every subsequent milestone, and none of that recurring cost appears in the −1.00 above.
2. **This domain got *bigger*, not smaller.** Decision 3 saved 1.00 pw and Decision 1 spent 1.00 pw un-deferring `CycleStats`, so the two cancel and Decision 2 adds 0.20 on top. Anyone reading "we deleted the multi-backend architecture" and expecting the domain estimate to fall should see the arithmetic instead: **14–19 pw, up from 14–19 pw, with a different composition.** The prior 14–19 was a two-release number that quietly excluded analytics; this one is a single-release number that includes everything.

**Reconciliation with the library-wide plan.** `ROADMAP.md` books Decision 3 at **−1.25 pw library-wide**. The **−1.00** above is this domain's share; the remaining **−0.25** is doc 06's — the `RootstockRobot`/`RootstockLoggedRobot` collapse (D13) and most of D26's `ServiceLoader` plumbing. Likewise the **+1.50 pw** Decision 2 books library-wide is mostly doc 06's (template authoring, `.rootstock/template.lock`, `rootstock update`/`doctor`, `docs/UPDATING.md`); **+0.20** of it is this domain's.

**Not counted anywhere in the numbers above:**
- The **§11.1 fork contingency, ~2.5 pw**, which is a contingency line and is not in the 74.0 pw either.
- The **annual carrying cost**, 2–4 pw per calendar year from M8 onward, of which this domain's share is the template regeneration, the 9-job matrix, the CI image bump, and a new AdvantageScope field/asset entry each season.

**Depth levers touching this domain** (from `ROADMAP.md`; none are fired, listed so the reader knows which parts are the expensive ones): **L10** −1.1 (3D viz ships `ArticulationSpec` + `MechanismVisualizer` only; drop `AssetExporter`, `FieldGhosts`, the `rootstockAssets` task) · **L17** −1.4 (replay-safety lint ships as runtime tripwire only, no javac processor) · **L5** −0.9 (drop the maple-sim adapter; kinematic only) · **L4** −0.9 (power/pneumatics/LED/haptics reduced — doc 06's, but its telemetry lands in §3).




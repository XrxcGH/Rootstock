# Rootstock Domain 03 — Vision

**Status:** design complete, ready to implement — **revision 4, after the synthesized independent expert review of 2026-08-07 ([`REVIEW.md`](../REVIEW.md)).**
**Target:** WPILib 2026.2.2 · Java 17 · Phoenix 6 26.x · REVLib 2026 · PathPlannerLib 2026.1.2 · PhotonVision 2026.3.4 · Limelight OS 2026.1 / LimelightLib-WPIJava 1.14 · **AdvantageKit 26.0.2 (REQUIRED — maintainer decision 3)**
**License:** BSD-3-Clause ([`LICENSE`](../LICENSE)).
**Root package:** `org.rootstock.vision`
**Author's stance:** Rootstock does not reimplement PhotonVision, Limelight, or WPILib pose estimation. It supplies the *seam* that makes them interchangeable, the *filter chain* that explains itself, and the *simulation* that Limelight never shipped.

**Revision 2 (2026-08-07), after adversarial review.** Six findings applied. In dependency order, so a reader who knows the first draft can jump straight to what changed:

| # | Severity | Where | What changed |
|---|---|---|---|
| 1 | blocking | §4.2, §4.3 | `VisionFrame` no longer claims `StructSerializable` — it cannot, because `Optional`/`int[]`/`List` have no fixed `getSize()`. Added the fixed-size `VisionFrameHeader` mirroring the §6.3 wire schema field for field, and replaced `TargetObservation`'s nullable transforms with `hasBest`/`hasAlt` flags plus zeroed `Transform3d` so `pack()` cannot NPE. |
| 2 | blocking | §2.2, §5.2, §9.7 | The gyro contract was self-contradictory and nothing owned the gyro→field offset. `getGyroRotation()` is now `getGyroFieldHeading()`, `DriveBackend` exposes `getRawGyro()`, and the offset has exactly two named writers — `resetPose` and the disabled multi-tag seed, which now refuses gyro-fused sources. **Follow-up, 2026-08-08:** the change list this finding produced (§2.2a, contracts C1–C5) was ordered on `design/05` and sat unapplied through two further reviews. It is applied now, and the ownership question the finding raised has a named answer: **`design/05` §3.3.3 owns the gyro→field offset**; this document owns the frame convention (§2.2) and no longer restates the arithmetic. |
| 3 | major | §2.6, §3, §6.1, §6.2, §14.2 | photonlib is no longer required. Three artifacts: core (WPILib only), `-photonvision`, `-vision-sim`. `.simulated(...)` takes the core-owned `CameraSimProfile`, never photonlib's `SimCameraProperties`, so the split is real rather than nominal — and CI enforces it. |
| 4 | major | §8.0–§8.4, §9.4 | `Double.POSITIVE_INFINITY` is gone from every standard deviation. `UNTRUSTED_SIGMA = 1e6`, the per-camera factor applies *before* the pin, `compute` writes into a caller-owned matrix, and `sanitizeStdDevs` is a choke point no escape hatch bypasses. A `cameraFactor` of `0` used to mean `NaN` pose for the rest of the match. |
| 5 | major | §9.6 (new), §4.4, §9.1 | `maxFramesPerLoop` dropped 20 → 4 and gained a separate `maxAcceptedPerLoop` = 2 with oldest+newest coalescing. The old default did ~800 `updateWithTime` calls in one loop, and only *after* a loop overrun — a positive feedback bomb. |
| 6 | major | §13.3, §13.5, §13.6, §19 | `alignToTag` is now genuine tag-relative control and is built **early** (M10), `CameraArbiter` is specified and is built at M16, and `AlignGains.defaults()` tolerance went 2 cm → 5 cm because a fused-pose controller cannot hold tighter than the vision sigma at scoring range. |

One finding (§6.2, minor) was recorded as "already correct in the draft." **Revision 4 shows that verification was wrong** — see the revision-4 table, row 3.

**Revision 3 (2026-08-07), after the four binding maintainer decisions.** Nothing in this document's vision engineering changed. What changed is scope, packaging and the dependency floor:

| # | Decision | Effect on this document |
|---|---|---|
| A | **1 — everything ships in one release, v0.1** | The v0.1 / v0.2 / v0.3 / v0.4 phase table in §19 is **deleted as a release plan** and survives only as build **order**, remapped onto milestones **M10, M16, M17, M18** in [`ROADMAP.md` §5](../ROADMAP.md). Every "ships in v0.2" / "deferred to v0.4" phrase in this document now means "built at milestone M<n> of the single v0.1 release." No vision capability is deferred out of the release. |
| B | **2 — `RootstockTemplate` is the front door** | Vision is not in the template's worked example, but `Rootstock-PhotonVision.json` is one of the vendordeps a team adds via `rootstock update`/`rootstock doctor`; there is no separate vision vendordep to install (see C). |
| C | **3 — AdvantageKit is REQUIRED** | `RootstockInputs` / `LogSink` / `LogSource` **do not exist**; `VisionCameraIOInputs` implements AdvantageKit's `LoggableInputs` and writes `LogTable` directly. `AdvantageKitCompat` as an *optional* bridge is gone — the bridge is unconditional. Replay of `VisionFrame` is a **guarantee**, not a backend-dependent property. **The "installs on kickoff morning before any vendor has published" argument used in §2.6 is withdrawn** — it is no longer true of any Rootstock artifact. |
| D | **1 + D28 — one core jar** | §2.6's **three vision Maven coordinates are reduced to two, and the group is `dev.rootstock`, not `com.rootstock`.** `rootstock-vision` and `rootstock-vision-sim` do not exist as published artifacts. See §2.6 as rewritten. |
| E | **4 — BSD-3-Clause** | The license is decided; no "TBD" anywhere. The AGPL question in §2.6 concerns *PhotonVision's own model licensing*, not ours, and is unaffected. |

**Revision 4 (2026-08-08), after [`REVIEW.md`](../REVIEW.md).** Six findings were routed to this document (two blocking, one major, three minor). All six are applied, plus the binding-decision conformance sweep those findings exposed. The vision *architecture* is unchanged; two controllers, one vendor-API verification note, and four facade seams were wrong.

| # | Severity | Where | What changed |
|---|---|---|---|
| 1 | **blocking** | §13.4, §13.5, §13.4.1 | **Both terminal controllers commanded velocity away from the target.** A negative profiled scalar was paired with a robot→goal direction vector — the exact pairing [`design/05` §9.1](05-drivetrain-auto.md) names as fatal and pins with regression tests. Both are rewritten to design/05 §9.1's audited convention: direction is **goal→robot**, `ffScale` multiplies the **profile feedforward only** (it used to multiply the feedback, creating a dead zone exactly equal to each command's own tolerance), and the translation channel is a declared, seeded `ProfiledPIDController` instead of an undeclared `m_translationProfileVelocity` field. New §13.4.1 explains why the two channels legitimately look different. Four direction/dead-zone/seeding tests added to §16. |
| 2 | **blocking** | §2.2 (rewritten as an executable contract) | The gyro→field-offset machinery and binding **D17** were specified here and never applied in `design/05`. This document does not edit design/05 — a later contract-reconciliation pass owns that — so §2.2 is restated as an exact, mechanical change list: full signatures, the line to delete, the MegaTag2 orientation convention spelled out, and `DriveSelfCheck` check **nine** (design/05 already has eight; the earlier text said "eighth"). |
| 3 | **blocking (self-found)** | §6.2 | **Revision 2's PhotonVision verification note was false.** It asserted the 3-argument `PhotonPoseEstimator` constructor was *removed* and that "the class lists no deprecated members at all." Verified against the **v2026.3.4 source tag**: the 3-arg constructor is `@Deprecated(forRemoval = true, since = "2026")`, `update()` exists in three deprecated overloads, and `getPrimaryStrategy`/`setPrimaryStrategy`/`setMultiTagFallbackStrategy`/`getReferencePose`/`setReferencePose`/`setLastPose` are all deprecated-but-present. Root cause: the note cited `javadocs.photonvision.org/release/`, which **now serves v2027.0.0-alpha-2**, not 2026.3.4. Every vendor citation in this document is re-pinned to an immutable git tag. |
| 4 | major | §2.4, and every alert call site | Binding **D10** requires `MatchImpact` at every alert site with no default and no single-argument overload. This document used a two-argument `RootstockAlerts.error(group, text)`. `[SUPERSEDED-NAME]` Replaced with `Alerts.error/warning/info(group, text, MatchImpact)` in `org.rootstock.core.alert`, every site annotated, and §11.3a added so vision cannot blow the `AlertBudgetTest` ceiling. |
| 5 | minor | §2.3, §7.2, §11.1 | Binding **D11/D12** made `TuningRegistry.tunable(namespace, key, default, unit)` the single tunable entry point and replaced `TUNING_MODE` with `TuningRegistry.isTuningEnabled()`. The `Tunable` / `RootstockConfig` declarations are deleted. |
| 6 | minor | §19 | The effort table's rows summed to 13.7 pw against a stated total of 12.2. The total row is now **13.7 with the addition written out**, and 12.2 is retired with an explanation of what it was. |
| 7 | minor | §9.7, §11.4, §9.3, §14.4 | ArchUnit rule 10 (`MatchContext` is the only `DriverStation` reader) and the volatile-API confinement rules. `DriverStation.isEnabled()` → `MatchContext.isDisabled()`, `DriverStation.isFMSAttached()` → `MatchContext.isFMSAttached()`, `RobotBase.isSimulation()` → `Platform.isSimulation()`, `Timer.getFPGATimestamp()` → `Clock.now()`. |
| 8 | conformance | §2.1, §7.3, everywhere | Binding **D9/D22**: `org.rootstock.telemetry.RootstockLog` (doc 04) is the facade, with `critical()/log()/debug()/processInputs()/timestamp()/isReplay()` — not a `log`-package `output(...)`. `RootstockTracer` is doc 04's `budget/scope/record/reset`. The vision key namespace moves under `Rootstock/Vision/<camera>/`, which doc 04 §3.3 owns. |
| 9 | correctness (self-found) | §5.2a (new), §6.1, §16, §21 | `LimelightCameraIO` reads `targetpose_cameraspace` to populate `bestCameraToTarget`, which `alignToTag` closes the loop on — but **Limelight camera space is X-right / Y-down / Z-out-of-lens**, not WPILib's X-forward / Y-left / Z-up, and revision 3 specified no basis change. The conversion is now written out, its rotation half is marked `[UNVERIFIED]`, and a runtime cross-check against `targetpose_robotspace` names the failure if we got it wrong. |
| 10 | correctness (self-found) | §13.3, §13.4, §15 | `driveToPose`/`alignToNearest`'s freshness gate read `m_vision`/`m_cameraIndex`, neither of which was a parameter. Both factories now take the `RootstockVision` they gate on, and `RootstockVision` gains `acceptedFramesInWindow(double)` so `VisionFreshness.minAcceptedFramesInWindow` is implementable. |

> **The `[SUPERSEDED-NAME]` marker — why three deleted names still appear in this document (added 2026-08-08).** [`DESIGN.md`](../DESIGN.md) §16 items **6** and **7** carve the names `getGyroHeading`, `VisionObservation` and `RootstockAlerts.` out of their gates using the **same literal marker** [`design/02`](02-tuning.md) §0 defines for its own five-name set. **Marker scope is identical and is defined there, not re-defined here:** the same line; or — where an inline marker would corrupt sample output or a table row — any line of the same **fenced code block**, the same **block-quote**, or the same **markdown table**, plus the block-quote immediately preceding a fence. Every surviving mention of those three names at a **correction site** carries the marker **on the same line** — the strictest reading of scope. **The only place this document leans on a block-level clause is this definition block itself**, whose following two paragraphs name all three literals under the single marker on this line; that is the block-quote clause working as designed, and it is stated rather than left for a reader to discover. `[SUPERSEDED-NAME]`
>
> **This document's seven carved lines**, so the reverse gate has something to check against: §0 row 4 (`RootstockAlerts.error(group, text)`), §2.2a(1)'s HISTORY fence (`getGyroHeading`), §2.2a(4)'s **two** D17 paragraphs (`VisionObservation`, one each), §2.4's revision-4 correction (`RootstockAlerts.`), and §2.7 rows **C1** (`getGyroHeading`) and **C4** (`VisionObservation`). **The gate is stated as a relation between counts, not as a literal**, because a note that quotes its own grep changes the number it quotes — which is how a marker block becomes its own tenth hit. The relation, verified by hand 2026-08-08: **every line matching one of the three carved names also matches the marker on the same line, except the two paragraphs of this definition block that follow the marker — this enumeration and the union clause below — both of which the block-quote clause carves; and every line matching the marker has a carved name in its scope, with no exceptions.** Forward and reverse both clean. **Deleting any of the seven correction sites would delete the record of the correction**, which is the thing the marker mechanism exists to prevent.
>
> **The reverse gate must be defined over the *union* of every carved name set**, not over `design/02`'s five — `design/02` §0 states this as its fourth clause and `DESIGN.md` §16 item 4(f) adopts it. A marker standing over `getGyroHeading`, `VisionObservation` or `RootstockAlerts.` is **correct** and must not be reported as an orphan. One marker literal, one union of carved names, one reverse gate over that union.

**Verification refresh (2026-08-08).** Every vendor claim in §5.2, §6.1, §6.2, §10.1, §12.3 and §14 was re-checked against a primary source on this date; the sources are cited inline at the point of use, pinned to immutable git tags or to the vendor's own reference page rather than to a floating `/release/` URL. Where a claim could not be confirmed it is marked **[UNVERIFIED]** and carries an open question in §21.

---

## 1. Scope & Responsibilities

### 1.1 We own

| # | Responsibility | Why it is ours |
|---|---|---|
| 1 | **One vendor-neutral observation record** (`VisionFrame` / `TargetObservation`) that Limelight MegaTag1, Limelight MegaTag2, PhotonVision (all **eight** 2026 estimator entry points — see §6.2), custom NT coprocessors, and simulation all produce identically. | A camera dies at an event and the only spare is a different brand. That must be a one-line change, not a rewrite. |
| 2 | **Correct latency & timestamping per vendor**, done once, in one place, with the index maps and flush ordering documented in code. | At 4 m/s a 20 ms timestamp error is 8 cm — larger than most alignment tolerances (deep-elite pitfall list). |
| 3 | **A pluggable rejection filter chain where every rejection has a named, logged, counted reason.** | This is the genuine gap. AdvantageKit's template is one boolean expression; a rejected pose loses its reason forever. |
| 4 | **A pluggable standard-deviation model** shipping the three field-proven presets. | Std-dev tuning is where small teams' vision quietly fails. |
| 5 | **Pose-estimator integration** including the FPGA↔Phoenix time-base conversion, odometry-buffer-age guarding, and structural prevention of the MegaTag2 heading feedback loop. | `addVisionMeasurement` silently no-ops on a bad timestamp. Silence is unacceptable. |
| 6 | **Field-layout resolution, WPIcal override loading, and coprocessor/robot layout mismatch detection.** | Welded-vs-AndyMark mismatch is the #1 silent pose bug (deep-vision pitfall #1). |
| 7 | **Object detection** across Limelight neural detector and PhotonVision object detection, unified as `DetectedObject`, with camera→field ground-plane projection. | 6328 publish corner angles and project on the RIO; that is strictly more robust than trusting a coprocessor's 3D sphere estimate. |
| 8 | **Vision-driven command factories**: drive-to-pose, auto-align to nearest scoring location, game-piece auto-intake, aim-while-moving. | This is where a small team's score actually changes. |
| 9 | **Simulation for every source, including Limelight**, on one boolean. | Limelight has *no* first-party sim. A Limelight team cannot test autos without a robot and a field — the two things a small team has least of. |
| 10 | **Setup & diagnostics**: camera transforms in code (pushed to the Limelight, not typed into its web UI), a calibration checklist, and a `VisionDiagnostics` health report that names the problem in English. | The user asked for software that teaches students. |

### 1.2 We explicitly do NOT own

- The drivetrain, the pose estimator instance, or odometry. We consume a `PoseProvider` and push measurements through a `VisionConsumer` (binding **D17**).
- The logging transport. We call the `RootstockLog` facade owned by the telemetry domain (binding **D9**).
- The alert registry. We call `Alerts` in `org.rootstock.core.alert`, owned by the platform domain (binding **D10**).
- The tunable type and its NT plumbing. We call `TuningRegistry` (binding **D11**).
- Shooter lookup tables, hood angles, flywheel RPM. We supply the *virtual target* math (`MovingTargetSolver`); the mechanism domain supplies the numbers.
- Path generation. We compose PathPlanner's `AutoBuilder.pathfindToPose`.

---

## 2. Integration Points (what I need from other Rootstock domains)

Every item below is a hard dependency. Signatures are what Vision calls; the owning domain may add more. **Where this section states a signature that another document currently spells differently, this document does not edit that document** — §2.2a and the summary at the end of §2 list the exact mechanical changes the reconciliation pass must apply, with full signatures, so nothing has to be re-derived.

### 2.1 From the **Telemetry** domain — `org.rootstock.telemetry` (binding D9, D22)

```java
package org.rootstock.telemetry;

/** Tiered facade over AdvantageKit's Logger. AdvantageKit is a REQUIRED dependency
 *  (maintainer decision 3) -- there is no LogBackend SPI and no NT4/DataLog fallback.
 *  Domain 04 owns this class, the tiering, the byte governor and the Rootstock/** schema. */
public final class RootstockLog {
  /** Replay-aware, unconditionally. In REPLAY mode this OVERWRITES the fields of `inputs`
   *  from the log. Replay is a GUARANTEE, not a backend-dependent property. */
  public static void processInputs(String key, LoggableInputs inputs);

  // CRITICAL tier — present in the match log at the most aggressive filter setting.
  public static void critical(String key, boolean v);
  public static void critical(String key, long v);
  public static void critical(String key, double v);
  public static void critical(String key, double v, Unit unit);
  public static void critical(String key, String v);
  public static void critical(String key, String[] v);
  public static void critical(String key, long[] v);
  public static <T> void critical(String key, T v, Struct<T> struct);
  public static <T> void critical(String key, T[] v, Struct<T> struct);

  // STANDARD tier — identical overload set, named log(...), plus trailing-Demotable twins.
  public static void log(String key, double v, Unit unit);
  public static <T> void log(String key, T[] v, Struct<T> struct);
  public static <T> void log(String key, T[] v, Struct<T> struct, Demotable d);

  // DEBUG tier — reference types take SUPPLIERS so the value is never constructed when off.
  public static <T> void debug(String key, Supplier<T[]> v, Struct<T> struct);

  public static boolean isReplay();
  /** Monotonic robot time -- compat.Clock.now(), which IS Timer.getTimestamp(),
   *  the AdvantageKit-injected clock, in every mode. */
  public static double timestamp();
}

// DELETED by maintainer decision 3: the RootstockInputs mirror interface and the RootstockLogTable
// surface. They existed so an inputs class could work identically with and without AdvantageKit.
// AdvantageKit is now required, so every *IOInputs class implements AdvantageKit's own
// org.littletonrobotics.junction.inputs.LoggableInputs and writes
// org.littletonrobotics.junction.LogTable directly. toLog/fromLog stay HAND-WRITTEN (D24 --
// Rootstock uses neither @AutoLog nor @AutoLogOutput; see design/04 §2.4.1 for the
// re-verification of that decision under decision 3).
```

**Revision-4 correction.** Revision 3 declared this facade as `org.rootstock.log.RootstockLog` with an `output(String, Struct<T>, T[])` overload set. Both were wrong against binding **D9**: the package is `org.rootstock.telemetry`, the tier is part of the method name, and **the value comes before the struct** (`critical(String key, T[] v, Struct<T> struct)`). Every call site in this document is rewritten. Vision never imports `org.littletonrobotics.*` except for `LoggableInputs`/`LogTable` in `..io..`, which [`design/04` §2.7](04-telemetry-replay-viz.md) explicitly legalizes.

```java
package org.rootstock.telemetry;

/** Loop-time accounting. Vision declares one budget and one scope; domain 04 owns the rest. */
public final class RootstockTracer {
  public static void reset();
  public static void record(String epoch);
  /** try-with-resources. Emits Rootstock/Perf/<epoch>Ms. */
  public static AutoCloseable scope(String epoch);
  /** Alerts.warning("Rootstock/Perf", "PERF_<epoch> ...", MatchImpact.PIT_ONLY) on breach. */
  public static void budget(String epoch, Time limit);
}
```

Vision declares exactly one budget, at construction:

```java
RootstockTracer.budget("Vision/Consume", Milliseconds.of(2.0));   // section 9.6
```

`Vision/Consume` wraps step 7 of the loop (§9.4) — every `consumer.accept(...)` call this loop, across every camera. That is the one span whose cost is superlinear in frame count and therefore the one span that needs a declared ceiling. It surfaces as `Rootstock/Perf/Vision/ConsumeMs`.

**Why this shape, restated honestly under decision 3.** Revision 3's justification was *"the user's stack spans AdvantageKit (template, 9143) and CTRE SignalLogger with no AdvantageKit at all (8793); vision must be identical in both, and `RootstockLog` is the only thing that knows the difference."* **That argument is withdrawn.** Under maintainer decision 3 there is no no-AdvantageKit configuration; a team running CTRE `SignalLogger` runs it *in addition to* AdvantageKit, not instead of it. The facade survives for the reasons that are still true: three explicit tiers, the byte governor, the `/Rootstock/Driver` mirror, and one schema to keep stable across the 2027 rename.

### 2.2 From the **Drive / Pose** domain — `org.rootstock.drive` (binding D16, D16a, D17)

**Declared by Drive, not here.** Binding **D16** gives Drive all four drive-facing interfaces in `org.rootstock.drive`, and since 2026-08-08 they are declared in [`design/05` §3.3.2](05-drivetrain-auto.md) and **nowhere else in the design**. The block below is the surface this domain *consumes*, mirrored for readability — the same convention `design/04` §1.2 uses for `DriveTelemetry`. **If it disagrees with `design/05`, `design/05` wins.**

> **consumed-surface mirror — `design/05` §3.3.2 wins.** This is the banner the duplicate-declaration gate looks for, and it is a **literal string, not a paraphrase**, for a reason worth stating: the four D16 interfaces occur **eight** times across the design — **four canonical declarations in `design/05` §3.3.2**, plus **four labeled mirrors** (`PoseProvider`, `VisionConsumer` and `AlignableDrive` in this document; `DriveTelemetry` in `design/04` §1.2). A mirror is **legal if and only if it carries this banner**. The gate therefore counts canonical declarations plus banner-carrying mirrors and **fails only on an unbannered duplicate** — which is the shape that catches a genuine second declaration while not punishing a document for correctly restating a surface it consumes.

```java
package org.rootstock.drive;   // MIRROR of design/05 §3.3.2 — not a second declaration

/** consumed-surface mirror — design/05 §3.3.2 wins.
 *  Everything Vision needs to know about robot state. Implemented by RootstockDrive, or hand-written in 6 lines. */
public interface PoseProvider {
  Pose2d getPose();                                   // blue-origin, ALWAYS
  Optional<Pose2d> sampleAt(double timestampSeconds); // delegates to PoseEstimator.sampleAt

  /**
   * Gyro yaw PLUS the gyro->field offset. Blue-origin, CCW-positive, 0 deg faces the RED
   * alliance wall — the frame this document's MegaTag2 / PNP-trig / constrained-solvepnp
   * consumers need, spelled out in the convention paragraph below.
   *
   * <p>NEVER influenced by a vision measurement, so feeding it back into MegaTag2 cannot close
   * a loop. The offset itself, its exactly-two writers and the arithmetic that produces this
   * value are owned by design/05 §3.3.3 and are deliberately NOT restated here.
   */
  Rotation2d getGyroFieldHeading();

  /** False until the offset has been written at least once. Drives GYRO_OFFSET_UNSEEDED (§11.3). */
  boolean gyroFieldOffsetSeeded();

  double getGyroRateRadPerSec();
  ChassisSpeeds getFieldRelativeSpeeds();
}

/** consumed-surface mirror — design/05 §3.3.2 wins.
 *  Exactly AdvantageKit's VisionConsumer signature, so a template port is import-only. Binding D17. */
@FunctionalInterface
public interface VisionConsumer {
  void accept(Pose2d visionRobotPoseMeters, double timestampSeconds, Matrix<N3, N1> stdDevs);
}
```

#### The gyro→field offset contract — **owned by `design/05` §3.3.3**

> **Where the offset lives, stated once so nobody re-derives it.** The gyro→field offset — the constant that separates the raw IMU yaw from the blue-origin field heading — is **owned by `design/05` §3.3.3**, which declares the field, its exactly-two named writers (`RootstockDrive.resetPose` and the disabled multi-tag seed, the second reaching it only by calling the first), the conversion in both directions, and the prohibition that the vision sink never writes it. **This document does not restate that arithmetic**, and it did until 2026-08-08, which is precisely the drift this note exists to end. What this document owns and `design/05` references is the paragraph immediately below: the *frame convention* — what the number written to `robot_orientation_set` has to mean. Two disjoint responsibilities, one arithmetic site.

The earlier draft of this document declared `Rotation2d getGyroRotation(); // RAW GYRO ONLY` and then, in §5.2, required the value written to `robot_orientation_set` to be *"blue-origin, CCW-positive, 0 deg faces the RED wall."* Those two statements are contradictory. A raw IMU yaw is referenced to wherever the gyro happened to be zeroed at power-on — the underside of a cart, a pit table, the wrong alliance wall. MegaTag2 fed a raw gyro yaw produces a confidently wrong translation, and nothing in the system notices. **No component owned the offset.** `design/05` §3.3.3 does now.

**The MegaTag2 orientation convention, spelled out so it cannot be re-derived wrong.** The number written to `robot_orientation_set[0]` is the robot's yaw **in the WPILib blue-origin field frame**, in **degrees**, **CCW-positive**, where **0° means the robot's +x (forward) axis points at the RED alliance wall** — i.e. along the +x axis of the blue-origin field coordinate system, whose origin is the corner of the blue alliance wall and whose +x runs down the long axis of the field toward red. This is the same frame `PoseProvider.getPose().getRotation()` reports, and it is **not** the raw IMU frame and **not** an alliance-relative frame. Red-alliance robots use the identical convention; Rootstock never flips a measured pose (§9.3).

### 2.2a Hard requirement on the Drive domain — the exact change list for `design/05` (**APPLIED 2026-08-08**)

> **Status: the whole set is applied.** The contract-reconciliation pass landed items (1)–(5) in `design/05` on 2026-08-08 (that document's revision 3.2, §15.3 rows 14–15); items (6) and (7) had already landed at its revision 3.1 and were re-verified rather than re-done. `REVIEW.md` **B4** is closed by that work.
>
> **This block is retained as the order that was given and the reason it was given**, not as a live instruction — that is why it still reads in the imperative. It is **not** authoritative for signatures any more: `design/05` §3.3.2 is, for all four D16 interfaces, and `design/05` §3.3.3 is, for the offset. Where the two ever disagree, `design/05` wins and this block is the stale one. This document does **not** edit `design/05-drivetrain-auto.md`.

**(1) `DriveBackend` (`design/05` §3.2) — one line deleted, one line added. APPLIED.**

```java
// HISTORY — this line no longer exists anywhere in the design. It was deleted from
// DriveBackend (design/05 §3.2) on 2026-08-08; kept here so the diff is legible.
//   Rotation2d getGyroHeading();   // raw gyro, CCW+, blue-origin frame  [SUPERSEDED-NAME]
//   ^^^ carved out of DESIGN.md §16 item 6's gate by the marker on that line. The marker,
//   not this comment's prose, is what the grep can see; design/02 §0 defines the scope.

// WHAT REPLACED IT, and what design/05 §3.2 now declares:
  /**
   * The UNMODIFIED IMU yaw. CCW-positive, referenced to wherever the gyro was zeroed at
   * power-on. This is explicitly NOT the blue-origin field frame, and this is the only
   * place in Rootstock where the raw value is legal. Everything else calls
   * RootstockDrive.getGyroFieldHeading().
   */
  Rotation2d getRawGyro();
```

The deleted line is a contradiction inside a single line: a raw gyro is by definition not in the blue-origin frame.

**(2) `RootstockDrive` — the offset itself. APPLIED at [`design/05` §3.3.3](05-drivetrain-auto.md), which is now its single owner.**

The fields, the two named writers, the conversion and the never-writes prohibition all live there, in one place, written out in full. **This document deliberately no longer reproduces them.** There was exactly one place in the library where that arithmetic could drift into a second, disagreeing copy, and this was it — the same argument §8.0 Rule 1 makes for the sentinel, applied to the one constant whose being wrong is undetectable at runtime.

What vision requires from that ownership, and asserts against, is only:

- `PoseProvider.getGyroFieldHeading()` returns raw IMU yaw plus an offset that **only a pose reset may change** (§2.2's mirror), in the frame the convention paragraph above defines;
- `PoseProvider.gyroFieldOffsetSeeded()` is false until that offset has been written at least once, which is what item (3) and §11.3 key off;
- **WRITER 2 OF 2 is this document's** — the disabled multi-tag seed of §9.7. While disabled, a *non-gyro-fused* frame with `tagCount >= 2` that passes `VisionFilters.standard()` and agrees with two prior frames calls `resetPose(...)`, i.e. it invokes writer 1 from a gated path rather than being a third writer. It is gated on `MatchContext.isDisabled()`, it refuses gyro-fused sources (seeding a gyro offset from a gyro-fused solve is circular), and it is a *reset*, not a measurement. This is the only path by which vision may ever touch the offset;
- the vision sink **never** writes the offset. Not on any code path, not behind any flag. There is no opt-out, because an opt-out here is the feedback loop. `design/05` §3.3.3 states this on the sink's javadoc as well; two statements of a prohibition is not the same defect as two statements of a formula.

**(3) `DriveSelfCheck` (`design/05` §3.8) — add check NINE. APPLIED.** `design/05` §3.8 defined checks 1 through 8; an earlier revision of this document said "an eighth check," which would have collided with the existing double-registration check. It is **check 9**, and `design/05` §3.8 now carries it under that number with the alert text below unparaphrased:

```java
// DriveSelfCheck check 9 — GYRO_OFFSET_UNSEEDED
if (anyConfiguredCameraIsGyroFused && !drive.gyroFieldOffsetSeeded()) {
  Alerts.error("Drive",
      "Gyro field offset has never been seeded (still identity) but MegaTag2/PNP-trig cameras "
    + "are configured. robot_orientation_set is being written in the POWER-ON gyro frame, not "
    + "the field frame (blue-origin, CCW-positive, 0 deg facing the RED wall). "
    + "Call drive.resetPose(startingBluePose) or enable the disabled MegaTag1 seed.",
      MatchImpact.BLOCKS_MATCH)
      .set(true);
}
```

`anyConfiguredCameraIsGyroFused` is true when any configured camera reports a `PoseSource` of `MEGATAG_2`, `PNP_DISTANCE_TRIG` or `CONSTRAINED_SOLVEPNP`. `RootstockVision` mirrors the same condition as a `VisionDiagnostics` finding (`GYRO_OFFSET_UNSEEDED`, §11.3) so it is visible from either domain.

**(4) Binding D17 — delete `VisionObservation`. APPLIED, in both documents that declared it.** `[SUPERSEDED-NAME]` `design/05` §1.3 declared the record `VisionObservation(Pose2d bluePose, double fpgaTimestampSeconds, Matrix<N3,N1> stdDevs, int tagCount, double avgTagDistanceMeters)` together with a `drive.addVisionMeasurement(obs)` push, and `design/04` §1.3 declared a second copy. **Both are deleted by D17, and both are now gone.** The replacement is the `@FunctionalInterface VisionConsumer`, whose single method is `void accept(Pose2d visionRobotPoseMeters, double timestampSeconds, Matrix<N3,N1> stdDevs)` — **declared in `design/05` §3.3.2** and mirrored, once, in §2.2 above. It is not re-declared here; one mirror per document is already one more than the ideal.

`DriveBackend.addVisionMeasurement(Pose2d bluePose, double fpgaTimestampSeconds, Matrix<N3,N1> stdDevs)` (`design/05` §3.2) **already matched this shape** and did not change; only the `VisionObservation`-shaped wrapper on `RootstockDrive` went away, replaced by `RootstockDrive.accept(...)`. `[SUPERSEDED-NAME]`

`tagCount` and `avgTagDistanceMeters` do not move to the consumer. They are already folded into the std-dev matrix by the model this domain owns (§8.2), and a drive-side consumer that wants the raw numbers for skid-aware trust arbitration reads them from `RootstockVision.latestAcceptedFrame(int cameraIndex)`, which returns the whole `VisionFrame`. **Pull, not push** — which also removes `design/05`'s own recycled-buffer concern, because `VisionFrame` is an immutable record.

`design/05` §8.3 owns the drive-side spelling of that side channel, `OdometryTrust.withFrameMetadata(IntSupplier tagCount, DoubleSupplier avgTagDistanceMeters)`, and the two are the same mechanism rather than two competing ones: the suppliers are wired to this document's accessor, e.g. `() -> vision.latestAcceptedFrame(i).tagCount()`, so the *evaluation* stays a pull and only the *plumbing* looks like a push. Neither side is part of D17's seam, and `design/05` §8.3 is authoritative for the drive-side signature.

**(5) `AlignableDrive` gains one method, and moves. APPLIED.** This document's §13.1 requires `ChassisSpeeds getRobotRelativeSpeeds();` so `alignToTag` can seed its profiled controller from measured closing velocity (§13.5). `DriveBackend` already had it; it is now also on `AlignableDrive`, which `RootstockDrive` implements. Under D16 the interface itself moved: it is declared in `org.rootstock.drive` at **`design/05` §3.3.2**, and §13.1 of this document keeps a consumed-surface mirror that says so.

**(6) `RootstockDriveToPose` tolerance default. APPLIED at `design/05` revision 3.1** (its §9 builder, its §10 worked example, and `kDefaultTolerance`/`kDefaultAngularTolerance` as named constants); `DESIGN.md` §10B is the one site still outstanding and belongs to that document. `design/05` §9 read `tolerance(Distance linear, Angle angular); // default 0.02 m, 1.5 deg`. It had to become `// default 0.05 m, 2.0 deg`, carrying the Javadoc reason in §13.2 of this document. A 2 cm tolerance on a fused-pose controller is a tolerance the sensor cannot satisfy (§13.2), and the only thing it produces is a timeout alert on every alignment. `DESIGN.md` §10B line 1112 and `design/05` §10's worked example carry the same literal and change with it.

**(7) `UNTRUSTED_SIGMA`. APPLIED at `design/05` revision 3.1**, at all four sites (its §3.4.4, §8.2, §8.3 ×2). `design/05` used a raw `9_999_999` literal where §8.0 Rule 1 of this document mandates `StdDevModels.UNTRUSTED_SIGMA` (`1.0e6`). One constant, one place.

Limelight's own MegaTag2 sample feeds the *fused estimate* back into `SetRobotOrientation`, which is a latent feedback loop the moment anyone lowers the rotation std dev (deep-elite pitfall). We close that loop three ways, all structural: the heading comes from an accessor that is defined as never seeing a vision measurement; the offset has exactly two named writers; and every gyro-fused source gets `sigmaTheta = StdDevModels.UNTRUSTED_SIGMA` (§8), so vision rotation from those sources cannot move the estimate no matter what model a team plugs in.

### 2.3 From the **Tuning** domain — `org.rootstock.tuning` (binding D11, D11a, D12)

```java
package org.rootstock.tuning;

public final class TuningRegistry {
  /** THE tunable entry point for every domain. Publishes at /Tuning/<namespace>/<key>. */
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit);
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit,
                                      double min, double max);
  /** Replaces the deleted RootstockConfig.TUNING_MODE (D12). FMS-gated internally. */
  public static boolean isTuningEnabled();
}

/** Implements DoubleSupplier. get() is a cached field read; there is no NT access on the hot path. */
public final class TunableDouble implements DoubleSupplier { /* design/02 §5.3 */ }
```

**Revision-4 correction.** Revision 3 declared `org.rootstock.config.Tunable.number(key, default)` and `RootstockConfig.TUNING_MODE`. Binding **D11** made `TuningRegistry.tunable(namespace, key, default, unit)` the single tunable entry point with publication at `/Tuning/<namespace>/<key>` and **one** `NetworkTableListenerPoller` for the whole robot; binding **D12** deleted `TUNING_MODE` in favor of `TuningRegistry.isTuningEnabled()`. Both `Tunable` and `RootstockConfig` are deleted from this document. Vision's namespace is `"Vision"`:

```java
private final TunableDouble m_maxTagDistance =
    TuningRegistry.tunable("Vision", "maxAverageTagDistanceMeters", 6.0, "m");
private final TunableDouble m_maxAmbiguity =
    TuningRegistry.tunable("Vision", "maxAmbiguitySingleTag", 0.3, "", 0.0, 1.0);
```

All filter thresholds and std-dev coefficients are `TuningRegistry.tunable(...)`. When tuning is disabled they are a cached primitive read with zero NT traffic and zero allocation. (Both 4738 and the template built tunable stacks and then commented them out; that must not be the choice Rootstock forces.)

**Two things this domain needs from `design/02` that it does not currently declare** — both listed as contracts at the end of §2:

1. **A boolean tunable.** `VisionFilters.enabledWhen(BooleanSupplier)` (§7.2) is the per-camera kill switch a team flips in the pit. `design/02` §5 declares `TunableDouble` and no boolean analog. Required: `TuningRegistry.tunableFlag(String namespace, String key, boolean defaultValue)` returning `TunableBoolean implements BooleanSupplier`, published as a plain boolean topic at `/Tuning/<namespace>/<key>`.
2. **One named exception to D11's "geometry is not tunable" rule.** `CameraMount.pitchFudgeDegrees` (§11.1) is a *measured calibration correction* — 6328 run one camera at −4.5° against its CAD value — and it is the one geometric quantity that cannot be authored and must be measured on the assembled robot. It is published at `/Tuning/Vision/<camera>/pitchFudgeDeg` and persisted through `TunedValueStore` exactly like a gain. Either D11's exclusion list names this exception, or the fudge becomes config-only and the field procedure becomes "edit and redeploy," which is the thing this library exists to stop. **Vision's position: name the exception.**

### 2.4 From the **Platform** domain — `org.rootstock.core.alert` (binding D10) and `org.rootstock.core.match`

```java
package org.rootstock.core.alert;

/** MatchImpact is REQUIRED at every call site. No default. No single-argument overload. (D10) */
public enum MatchImpact { BLOCKS_MATCH, PIT_ONLY }

public final class Alerts {
  public static RootstockAlert error(String group, String text, MatchImpact impact);
  public static RootstockAlert warning(String group, String text, MatchImpact impact);
  public static RootstockAlert info(String group, String text);   // INFO is PIT_ONLY by definition
}
```

```java
package org.rootstock.core.match;

/** THE single DriverStation reader in Rootstock (ArchUnit rule 10). Vision names it, never DriverStation. */
public final class MatchContext {
  public static boolean isDisabled();
  public static boolean isEnabled();
  public static boolean isAutonomous();
  public static boolean isFMSAttached();
  public static Optional<Alliance> alliance();
  public static boolean isRed();
  public static boolean allianceKnown();
}
```

**Revision-4 correction, and it is the one this document dodged hardest.** `[SUPERSEDED-NAME]` Revision 3 declared a two-argument `RootstockAlerts.error(String group, String text)` and used it in roughly ten places. Binding **D10** requires `MatchImpact` at every alert call site *with no default and no single-argument overload*, precisely so that every alert answers the question "does this mean do not take the field." Every alert in this document now answers it. §11.3a states the resulting budget discipline, because a domain that can raise eight `BLOCKS_MATCH` alerts at once has not answered the question either — it has just moved it.

Vision raises alerts for: camera disconnected, timestamps outside the odometry buffer, MegaTag2 configured but `robot_orientation_set` never written, camera transform still `(0,0,0)`, suspected layout mismatch, calibration resolution mismatch, and the Limelight camera-space convention cross-check (§5.2a).

### 2.5 From the **Auto / Field** domain — `org.rootstock.field` (binding D14, D15)

```java
public final class AllianceFlip {
  public static boolean shouldFlip();                    // delegates to PathPlanner FlippingUtil semantics
  public static Pose2d apply(Pose2d bluePose);
  public static Translation3d apply(Translation3d bluePoint);
}
```
Vision **never flips a measured pose**. It only calls `AllianceFlip` when resolving *targets* for alignment commands. See §9.3. `AllianceFlip.shouldFlip()` reads `MatchContext.isRed()` and `MatchContext.allianceKnown()`, never `DriverStation` directly.

### 2.6 Artifact split — vision core carries no camera-vendor dependency

The earlier draft listed `photonlib` as **Required**, with the note *"Nothing works. It is also our simulation engine even for Limelight."* That is a straight violation of two of our own principles: DESIGN.md Principle 5 (*no vendor lock-in, in either direction; Limelight, PhotonVision and custom NT coprocessors are first-class*) and Principle 12 (*a missing vendor is a named Alert, not a `NoClassDefFoundError`*). It also made our ship date hostage to PhotonVision's. A Limelight-only team must never be forced to install PhotonVision.

> **Revision 3 correction, and it is a real one.** The earlier draft also cited *"installs as one vendordep with zero vendor `requires` — a team installs on kickoff morning"* as a third principle being violated. **That argument is withdrawn.** Maintainer decision 3 makes AdvantageKit a required dependency of `rootstock` itself, so no Rootstock artifact is installable before a third-party vendor has published for the season. The vendor-neutrality argument for keeping **photonlib** off the vision path is untouched and still correct; the kickoff-morning argument is not available to us any more and must not be repeated. See [`ROADMAP.md` §4.2](../ROADMAP.md).

**Revision 3 also collapses the packaging.** The earlier draft proposed three vision Maven coordinates under a `com.rootstock` group. Under **D28** (one core jar) and maintainer decisions 1 and 3 there are **two**, under `dev.rootstock`, and they are the same two that appear in [`DESIGN.md` §8](../DESIGN.md) and [`design/06` §3.3](06-platform-compday.md). `rootstock-vision` and `rootstock-vision-sim` **do not exist as published artifacts.** The source-set and package boundaries below are unchanged and are still enforced by ArchUnit at the *package* level — only the publish granularity changed.

| Artifact | Depends on | Contains |
|---|---|---|
| `dev.rootstock:rootstock` (**the core jar** — the vision packages inside it) | WPILib + **AdvantageKit**; **no camera vendor** | `VisionFrame`, `VisionFrameHeader`, `TargetObservation`, `PoseSource`, `CameraMount`, `CameraSimProfile` + `CameraSimProfiles`, `VisionCameraIO`, `ReplayCameraIO`, the whole `filter` package, the whole `stddev` package, `field` (`FieldLayouts`, `LayoutFingerprint`, `TagResidualMonitor`), `objects`, `commands` (including `alignToTag` and `CameraArbiter`), `diag`, `compat`, `CustomNTCameraIO` + schemas, and the **vendored** `LimelightHelpers` + `LimelightCameraIO`. |
| `dev.rootstock:rootstock-photonvision` | `rootstock` + photonlib vendordep | `org.rootstock.vision.photon.PhotonCameraIO`, `PhotonStrategy` — **and** `org.rootstock.vision.sim.*` (`RootstockVisionSim`, `RootstockCameraProps`, `SimulatedLimelight`), which wrap `VisionSystemSim` / `PhotonCameraSim` / `SimCameraProperties`. The former `rootstock-vision-sim` artifact is folded in here: both halves need photonlib and nothing else does, so a second coordinate bought nothing. |
| ~~`rootstock-vision`~~ | — | **Does not exist.** Folded into `rootstock` by D28. |
| ~~`rootstock-vision-sim`~~ | — | **Does not exist.** Folded into `rootstock-photonvision`. |

Consequences, all of them deliberate:

- A Limelight-only team installs **`Rootstock.json`** (which itself pulls `WPILibNewCommands.json` and `AdvantageKit.json`) and gets the filter chain, std-dev models, diagnostics, layout management, object projection, and every command. No photonlib. No PhotonVision install on their coprocessor. No AGPL model licensing question. It does **not** get them out of installing AdvantageKit — nothing does.
- The vision packages compile against WPILib and AdvantageKit alone, so vision ships on our schedule, not PhotonVision's.
- Camera **simulation** for a Limelight-only team costs one extra vendordep (`Rootstock-PhotonVision.json`) and zero code changes — `.simulated(...)` is already on `LimelightCameraIO` in the core jar. **This is the same statement `DESIGN.md` §8 and `README.md` make: `SimulatedLimelight` lives in `rootstock-photonvision`, and that is said up front rather than discovered at runtime.**

  > **This is the constraint that makes the split real, so it gets stated as a rule.** `SimCameraProperties` is a photonlib type. If `LimelightCameraIO.simulated(...)` took one, the core artifact would import `org.photonvision.*` and the whole split would be theater — the ArchUnit test in §3 would go red on day one. So `.simulated(...)` takes **`CameraSimProfile`**, a plain core-owned record of sensor numbers (§14.2). `rootstock-photonvision` converts it to a `SimCameraProperties` at sim-construction time. A Limelight-only team therefore writes `.simulated(CameraSimProfiles.OV9281_1280_800_82DEG())` in `RobotContainer` and that line compiles with **no photonlib on the classpath at all**; it simply does nothing at runtime until `rootstock-photonvision` is present.
- `PhotonCameraIO` moving to its own artifact means the `PhotonStrategy` enum, the `withCalibration` helper and the `estimate*Pose` call sites are the *only* code in Rootstock that imports `org.photonvision.*`. That is one small module to re-verify against each PhotonVision release — and §6.2 documents what happens when that re-verification is done against a floating URL instead of a pinned tag.

**Principle-12 behavior when a module is missing.** `RootstockVision.build()` probes with `Class.forName` and degrades with a named Alert — never a `NoClassDefFoundError`:

```java
private static final boolean SIM_AVAILABLE =
    classExists("org.rootstock.vision.sim.RootstockVisionSim")
        && classExists("org.photonvision.simulation.VisionSystemSim");

// in Builder.build():
if (m_simEnabled && !SIM_AVAILABLE) {
  Alerts.warning("Vision",
      "Camera simulation needs rootstock-photonvision (PhotonVision). "
    + "Running with no simulated cameras.",
      MatchImpact.PIT_ONLY).set(true);        // sim-only; cannot affect a match
  m_simEnabled = false;   // everything else still works; poses simply never arrive in sim
}
if (m_hasPhotonCamera && !classExists("org.photonvision.PhotonCamera")) {
  Alerts.error("Vision",
      "A PhotonCameraIO was configured but photonlib is not on the classpath. "
    + "Add the rootstock-photonvision vendordep. This camera is disabled.",
      MatchImpact.BLOCKS_MATCH).set(true);    // a configured camera is silently dead
}
```

`classExists` is a two-line `try { Class.forName(n); return true; } catch (Throwable t) { return false; }`. The probe runs once, at construction, never in the loop.

**Dependency matrix**

| Dependency | Required? | If absent |
|---|---|---|
| WPILib 2026.2.x | **Required** | This is the floor. The vision packages need no camera vendor beyond it. |
| **AdvantageKit 26.0.2** | **REQUIRED (maintainer decision 3)** | **Rootstock does not install.** `AdvantageKit.json` is a `requires` entry on `Rootstock.json`. `RootstockLog` writes to `Logger` directly; there is no NT4/DataLog fallback and no `LogBackend` SPI. The upside is that replay of `VisionFrame` is a **guarantee**, not a configuration. The cost is that vision — like every other domain — cannot be installed until AdvantageKit has published for the season (R18). |
| `photonlib` (PhotonVision 2026.3.4) | **Required only for PhotonVision cameras and camera simulation** | The vision packages in the core jar are fully functional without it: Limelight, custom NT coprocessors, filters, std devs, diagnostics, commands. `PhotonCameraIO` and `RootstockVisionSim` are simply not on the classpath, and asking for either raises a named `Alert` (above), never a linkage error. |
| `LimelightHelpers` | Vendored inside the core jar | n/a — we ship it verbatim as `org.rootstock.vision.limelight.LimelightHelpers` (BSD-3, single file). No vendordep, no version skew. |
| PathPlannerLib 2026.1.2 | Optional | `VisionCommands.driveToPose` degrades to pure terminal control (no obstacle-avoiding approach phase) and logs a warning once. `alignToTag` (§13.5) never needed it. |
| Phoenix 6 26.x | Optional | Only used for `Utils.fpgaToCurrentTime()` when the consumer is a CTRE `SwerveDrivetrain`. Detected reflectively; see §9.2. |

### 2.7 Summary of cross-document contracts this section requires

Collected here so the reconciliation pass has one list. Every item is stated in full above.

**Status column verified 2026-08-08 by grep against the named documents.** `OPEN` means the receiving document does not yet carry the change; it is not a soft "probably fine."

| # | Document | Exact change | Status |
|---|---|---|---|
| C1 | `design/05` §3.2 | Delete `Rotation2d getGyroHeading(); // raw gyro, CCW+, blue-origin frame` `[SUPERSEDED-NAME]`; add `Rotation2d getRawGyro();` with the javadoc in §2.2a(1). | **APPLIED** 2026-08-08 |
| C2 | `design/05` §3.3.3 | Add `m_gyroFieldOffset` / `m_gyroFieldOffsetSeeded`, the two writers, `getGyroFieldHeading()`, `gyroFieldOffsetSeeded()`. `design/05` §3.3.3 is now the **single owner** of the offset and this document no longer restates the arithmetic — see §2.2a(2). | **APPLIED** 2026-08-08 |
| C3 | `design/05` §3.8 | Add `DriveSelfCheck` check **9** = `GYRO_OFFSET_UNSEEDED` — §2.2a(3) verbatim. Checks 1–8 are unchanged. | **APPLIED** 2026-08-08 |
| C4 | `design/05` §1.3, `design/04` §1.3 | Delete `VisionObservation` `[SUPERSEDED-NAME]` and `addVisionMeasurement(obs)`; the sink is `VisionConsumer.accept(Pose2d, double, Matrix<N3,N1>)` (D17), declared once at `design/05` §3.3.2. `DriveBackend.addVisionMeasurement(Pose2d, double, Matrix<N3,N1>)` already matches and does not change. | **APPLIED** — `design/04` at its own revision, `design/05` 2026-08-08 |
| C5 | `design/05` §3.3.2 / D16 | `AlignableDrive` adds `ChassisSpeeds getRobotRelativeSpeeds();`, and moves to `org.rootstock.drive`; §13.1 of this document keeps a labeled mirror. | **APPLIED** 2026-08-08 |
| C6 | `design/05` §9, `design/05` §10, `DESIGN.md` §10B line 1112 | `tolerance` default `0.02 m / 1.5 deg` → `0.05 m / 2.0 deg`. | **APPLIED in `design/05`** (rev 3.1, both sites, plus named constants). ~~**`DESIGN.md` §10B: OPEN**~~ ~~**§10B reads `.tolerance(Meters.of(0.05), Degrees.of(2.0))`**~~ **`DESIGN.md` §10B: APPLIED, and this cell's own quotation of §10B was one revision stale — corrected 2026-08-08.** §10B no longer spells the tolerance as a literal at all: it reads **`.tolerance(RootstockDriveToPose.kDefaultTolerance, RootstockDriveToPose.kDefaultAngularTolerance)`**, with a comment naming `design/05` §9 as the single declaration site. **Verified by grep 2026-08-08:** `grep -c 'RootstockDriveToPose\.kDefaultTolerance' DESIGN.md` = **3** (§10B's call site, §10B's comment, and §16 item 2's own quotation), and `grep -n 'tolerance(Meters\.of(0\.05)' DESIGN.md` returns **1** line — §16 item 2's correction text, quoting the superseded literal form in order to name it, not a call site. The struck-through quotation above is kept as the record of what this cell used to assert. `DESIGN.md` §16 item 2 records this row's *earlier* "OPEN" as a stale report; this correction records the *literal* quotation as the second staleness in the same cell. |
| C7 | `design/05` | Replace the raw `9_999_999` literal with `StdDevModels.UNTRUSTED_SIGMA` (`1.0e6`). | **APPLIED** (rev 3.1, all four sites) |
| C8 | `design/02` §5.2 | Add `TuningRegistry.tunableFlag(String namespace, String key, boolean defaultValue)` → `TunableBoolean implements BooleanSupplier`. | **APPLIED** 2026-08-08 — `grep -c tunableFlag design/02-tuning.md` = **9** (was 0). `design/02` §5.2 declares `public static TunableBoolean tunableFlag(String namespace, String key, boolean defaultValue)`; §5.3 declares `public final class TunableBoolean implements BooleanSupplier`. It shares the single `NetworkTableListenerPoller` and the one `readQueue()` per loop (**D11a**), is off under FMS by the same default-deny rule (**D11**), round-trips through `TuningInputs.flagKeys`/`flagValues` in replay, and is refused with a FATAL `ConfigError` if its namespace collides with a registered mechanism (which would otherwise put an eighteenth key under `/Tuning/<Mechanism>/` and break `NtSchemaTest`). Regression: `TunableFlagTest`, `design/02` §16.2. |
| C9 | `DESIGN.md` D11 | Name `CameraMount.pitchFudgeDegrees` as the one geometric exception to "geometry is deliberately not tunable," or move it to config-only. | ~~**OPEN** — `grep -c pitchFudgeDegrees DESIGN.md` = 0~~ **APPLIED 2026-08-08 — closed by running the condition, not by declaring it.** `grep -c pitchFudgeDegrees DESIGN.md` now returns **2**. **The load-bearing hit is the `D11` cell in `DESIGN.md` §5.1**, which names `CameraMount.pitchFudgeDegrees` as *"one named geometric exception, and exactly one"* to "geometry is deliberately not tunable," gives the reason (camera pitch is the one geometry value re-trimmed at an event without re-measuring the robot — 6328 run one camera at −4.5° against CAD), states that it publishes at `/Tuning/Vision/<camera>/pitchFudgeDeg` and persists through `TunedValueStore` like a gain, and adds the clause this row did not ask for but wanted: **"No other geometric quantity may claim the exception"** — a second one is a design change, not a config change. The second hit is `DESIGN.md` §16's self-report of that edit; **a §16 self-report cannot satisfy a contract**, which is why the count of 2 is broken out rather than quoted bare. The struck-through original is kept as the record of the state that made the contract necessary. |
| C10 | `design/06` (the `org.rootstock.core.alert` block declaring `MatchImpact` / `RootstockAlert`) vs `DESIGN.md` D10 | The alert factory is spelled `RootstockAlert.error(String group, String text, MatchImpact impact)` in `design/06` and `Alerts.error(String group, String text, MatchImpact impact)` in `DESIGN.md` D10. Both return a `RootstockAlert` handle and both require the `MatchImpact` argument, so the *contract* is agreed and only the class name differs. This document calls `Alerts.*` because `DESIGN.md` §5 is authoritative. One of the two spellings must move; vision does not care which, only that there is one. | **APPLIED** 2026-08-08 — and the closing move was `design/06`'s, not this document's. **The row's premise is now stale: `design/06` no longer spells the factory `RootstockAlert.error(...)`.** It carries an explicit correction — *"The facade class is `Alerts`; the handle type is `RootstockAlert`… An earlier draft of this section hung the statics off `RootstockAlert` itself, which is a fourth spelling of a thing D10 already named once. Corrected"* — and declares `public final class Alerts { public static RootstockAlert error(String group, String text, MatchImpact impact); … }`. **Verified by grep 2026-08-08:** `grep -rn 'RootstockAlert\.\(error\|warning\|info\|of\|when\)(' design/ DESIGN.md README.md ROADMAP.md` returns **1** hit and it is *this cell*, quoting the dead spelling in order to name it — zero declarations and zero call sites anywhere else. All four documents that declare the facade (`design/01` §1.x, `design/03` §2.4, `design/05` §1.4, `design/06` §13.x) hang the statics off `Alerts` and return `RootstockAlert`. **There is one spelling, which is all this row ever asked for.** |
| C13 | `design/05` §1.4 (its labeled `Alerts` mirror) | **(new, 2026-08-08 — surfaced by C10's grep, and deliberately *not* folded into C10, which is about the class name and is closed.)** The `info` factory is declared with **two different arities**: `design/01`, `design/03` §2.4 and `design/06` all declare `public static RootstockAlert info(String group, String text);` with the comment *"INFO is PIT_ONLY by definition — an informational alert cannot stop a match"*; `design/05` §1.4 declares `public static RootstockAlert info (String group, String text, MatchImpact impact);`. `design/06` owns alerts under **D10** and three of four documents agree with it, so the two-argument form is almost certainly right — but `DESIGN.md` §16 item 7's correction text spells the sweep target as `Alerts.error/warning/info(String group, String text, MatchImpact impact)`, i.e. three-argument `info`, so the master and the owning domain disagree in writing and this must be adjudicated rather than guessed. | ~~**OPEN**~~ **APPLIED / CLOSED 2026-08-08 — adjudicated in the master and then applied in the outlier, in that order.** **The adjudication:** `DESIGN.md` §5.2 **D32** (revision 8) rules that **the two-argument `Alerts.info(String group, String text)` wins**, on the ground this row guessed at — `Severity.INFO` is `MatchImpact.PIT_ONLY` **by construction**, so the parameter has one legal value, and *"a parameter with one legal value is noise that trains callers to stop reading the argument, which is the opposite of what D10 exists to buy."* D32 **narrows D10 rather than weakening it**, in exact words: D10's *"required at every call site, no default, no single-argument overload"* **binds `error` and `warning`**, both of which keep the third argument everywhere; **the exemption is `info`'s alone and does not generalize**, `Alerts` may not add a two-argument `error`/`warning`, and a future `Severity` whose impact is not constant takes the three-argument form. **The application:** `design/05` §1.4's mirror now declares `public static RootstockAlert info (String group, String text);` with the same `// INFO is PIT_ONLY by definition` comment as `design/01`, this document §2.4 and `design/06`, and §0's sweep line — which had written the target as `error/warning/info(group, text, MatchImpact)` — is corrected to `error/warning(group, text, MatchImpact)`. **All four documents now declare one arity.** **Verified 2026-08-08 with a *call-shaped* grep, because the bare-string one is not a gate:** `grep -rn 'Alerts\.info([^)]*)[[:space:]]*;' design/ DESIGN.md README.md` returns **zero** — no call site existed then and none exists now, so this closed at zero compile risk. *(The bare `Alerts\.info(` grep returns 4 and rises every time another document correctly describes the signature — D10's cell, D32's cell, `DESIGN.md` §16 item 7 and `design/04` §2 — the same unsatisfiable-gate defect `DESIGN.md` §16 records for items 1, 4(f), 6 and 7.)* **`DESIGN.md` §16 item 7's owed sentence is discharged by D32.** Vision still never calls `info`. |
| C11 | `design/04` §3.3 | Extend the `Rootstock/Vision/<Camera>/` key table with the rows §7.3 of this document publishes (reject taxonomy, coalescing counters, arbiter, seed, align). Doc 04 owns the schema; these are the keys this domain publishes into it. **This document's side of the contract is now EXACT: the twelve outstanding rows are enumerated with key, type, tier and meaning in §2.7a below, and §7.3 is the single source they are read from.** | **PARTIAL, and deliberately left so** — `RejectReason` (the 20-constant enum, §7.1) **is** in `design/04` §3.3; the **twelve** coalescing/arbiter/seed/align rows named in §2.7a are **not**. **The remaining work is `design/04`'s alone and is now mechanically applicable** — copy §2.7a's twelve rows verbatim into `design/04` §3.3's `Rootstock/Vision/` table. **This document cannot close this row**, because a contract is discharged in the receiving document, not in the requesting one; a self-report here would be a false done-claim of exactly the kind `DESIGN.md` §16 exists to catch. |
| C12 | `DESIGN.md` §16 item 3 | Add `design/03` to the list of documents whose `DriverStation` / `RobotBase` / `Timer` call sites are being migrated (this revision migrates them; the item exists so the ArchUnit rules can be turned on without a surprise). | **APPLIED** — `DESIGN.md` §16 item 3 names `design/03` |

#### 2.7a C11's exact remaining shape — the twelve rows `design/04` §3.3 still owes

> **Why this block exists, and why it is not a closure.** C11 has sat at **PARTIAL** across several revisions with a cell that said *"the coalescing counters, arbiter, seed and align rows are not"* — true, but not **actionable**: it named four *categories*, not rows, and a receiving document cannot apply a category. This block makes **this document's half exact** so that a future pass can apply C11 **mechanically, without judgment**, and without reading §7 end to end. **C11 stays PARTIAL.** `design/03` does not edit `design/04`, and marking a contract closed from the requesting side is a false done-claim.

**Already applied — do not re-apply.** `design/04` §3.3 carries **`RejectReason`**, and it is the whole 20-constant enum of §7.1 (`ACCEPTED`, `NO_POSE`, `NO_TAGS`, `TAG_NOT_IN_LAYOUT`, `HIGH_AMBIGUITY`, `OFF_FIELD`, `BAD_Z`, `BAD_TILT`, `GYRO_DISAGREEMENT`, `POSE_JUMP`, `TOO_FAR`, `HIGH_ANGULAR_RATE`, `STALE_TIMESTAMP`, `FUTURE_TIMESTAMP`, `ODOMETRY_BUFFER_MISS`, `AUTO_STARTUP_WINDOW`, `MEGATAG2_NO_ORIENTATION`, `MALFORMED_FRAME`, `DISABLED_SOURCE`, `CUSTOM`), together with the `RejectReasons` / `RejectDetail` / `RejectCounts/<REASON>` rows that carry it on the wire.

**Outstanding — exactly twelve rows, copied from §7.3 without alteration.** Key, WPILOG/AdvantageKit type, tier, and the meaning `design/04` needs in order to write a schema entry:

| # | Key | Type | Tier | Meaning (what `design/04` §3.3's table column needs) |
|---|---|---|---|---|
| 1 | `Rootstock/Vision/<name>/CoalescedCount` | `long` | STANDARD | Accepted frames merged away this loop by `maxAcceptedPerLoop` (§9.6). Cumulative since boot. |
| 2 | `Rootstock/Vision/<name>/DecodeDroppedCount` | `long` | STANDARD | Frames left undecoded by `maxFramesPerLoop` (§9.6). Cumulative since boot. **Distinct from row 1**: dropped-before-decode, not merged-after-accept. |
| 3 | `Rootstock/Vision/Summary/CoalescedCount` | `long` | STANDARD | Robot-wide sum of row 1 across cameras. Not per-camera; **not** under `<name>/`. |
| 4 | `Rootstock/Vision/Arbiter/SelectedCameras` | `long[]` | STANDARD | Camera **indices** that relocalized this loop (§13.6). Index↔name resolves through `Rootstock/Vision/CameraIndex/<name>`. |
| 5 | `Rootstock/Vision/Arbiter/Scores` | `double[]` | STANDARD | Per-camera geometry score (§13.6). **Parallel to the camera index space, not to row 4** — length is the camera count, not the selected count. |
| 6 | `Rootstock/Vision/Arbiter/SuppressedCount` | `long` | STANDARD | Frames the arbiter suppressed this loop. |
| 7 | `Rootstock/Vision/Seed/Count` | `long` | CRITICAL | Disabled multi-tag pose seeds issued (§9.7). Cumulative since boot. |
| 8 | `Rootstock/Vision/Seed/LastSeedPose` | `Pose2d` | CRITICAL | The pose most recently seeded. Blue-origin, like every pose in this document. |
| 9 | `Rootstock/Vision/Seed/LastSeedSource` | `String` | CRITICAL | Which camera/source produced the last seed. |
| 10 | `Rootstock/Vision/Seed/RejectedNoAgree` | `long` | STANDARD | Seed attempts refused because the multi-tag solutions did not agree (§9.7). |
| 11 | `Rootstock/Vision/Align/*` — **eight leaf keys, one group** | see below | see below | The active `alignToTag` lock (§13.5). Enumerated in full below because a `*` in a schema table is not a schema. |
| 12 | `Rootstock/Perf/Vision/ConsumeMs` | `double` | STANDARD | `RootstockTracer` budget, **2.0 ms** (§9.6). **Note the namespace: `Rootstock/Perf/`, not `Rootstock/Vision/`** — it belongs to the perf table, and applying it into the vision table would be wrong. |

**Row 11 expanded — the eight `Align` leaves, since the group is one contract row but eight schema entries:**

```
Rootstock/Vision/Align/TagId                 long           CRITICAL   active alignToTag lock (§13.5)
Rootstock/Vision/Align/ErrorMeters           double         CRITICAL   TAG-RELATIVE error, NOT fused-pose error
Rootstock/Vision/Align/ErrorDegrees          double         CRITICAL   tag-relative, same frame as ErrorMeters
Rootstock/Vision/Align/CommandedRobotSpeeds  ChassisSpeeds  CRITICAL   what we actually sent (§13.4.1)
Rootstock/Vision/Align/ProfileVelocity       double         STANDARD   ProfiledPIDController.getSetpoint().velocity
Rootstock/Vision/Align/ObservationAgeSecs    double         STANDARD   now - frame.timestampSeconds at solve time
Rootstock/Vision/Align/NoSolveLoops          long           STANDARD   consecutive loops with no usable solution
```

*(`NoSolveLoops` is a **counter**, not a boolean — §13.5's stall detection reads the count, so a `boolean NoSolve` would not satisfy this row.)*

**Two shapes a mechanical applier would otherwise get wrong, stated so it cannot.**
1. **`Arbiter/Scores` is not parallel to `Arbiter/SelectedCameras`.** `SelectedCameras` is a *selection* (length ≤ camera count); `Scores` is indexed by *camera index* (length = camera count). Writing them as a parallel pair is the obvious mistake and it makes the AdvantageScope join silently wrong.
2. **`Rootstock/Perf/Vision/ConsumeMs` is not a vision-table key.** It is listed in §7.3 because this domain publishes it, but its namespace is `Rootstock/Perf/`. If `design/04` §3.3's table is scoped to `Rootstock/Vision/`, row 12 belongs in the perf table instead — and that is a placement decision `design/04` owns, which is the one row of the twelve that is not purely mechanical.

**Verification condition for whoever closes C11** (run it in `design/04`, not here): every one of the twelve rows above, with rows 11 and 12 expanded to their eight and one leaf keys respectively, appears in `design/04` §3.3 with the same type and the same tier. **Nineteen leaf keys in total: ten from rows 1–10, eight from row 11, one from row 12.** Until that holds, C11 is PARTIAL and this cell says so.

---

## 3. Package layout

```
--- inside artifact: rootstock   (the core jar: WPILib + AdvantageKit; NO photonlib) -----------
org.rootstock.vision              RootstockVision, VisionFrame, VisionFrameHeader, TargetObservation,
                                   PoseSource, CameraMount, CameraSimProfile, CameraSimProfiles
org.rootstock.vision.io           VisionCameraIO, VisionCameraIOInputs, ReplayCameraIO
org.rootstock.vision.limelight    LimelightHelpers (vendored), LimelightCameraIO, LimelightKeys,
                                   LimelightCameraSpace, SnapScriptChannel
org.rootstock.vision.custom       CustomNTCameraIO, VisionWireSchema, RootstockV1Schema, NorthstarSchema
org.rootstock.vision.filter       VisionFilter, VisionFilters, RejectReason, VisionContext, FilterResult
org.rootstock.vision.stddev       StdDevModel, StdDevModels
org.rootstock.vision.field        FieldLayouts, LayoutFingerprint, TagResidualMonitor
org.rootstock.vision.objects      DetectedObject, ObjectProjection, ObjectTracker
org.rootstock.vision.commands     VisionCommands, AlignGains, MovingTargetSolver, CameraArbiter
org.rootstock.vision.diag         VisionDiagnostics, VisionHealth, Finding
org.rootstock.vision.compat       AdvantageKitCompat  (PoseObservation-shaped views for template porting)

--- artifact: rootstock-photonvision      (+ photonlib vendordep) ----------------------------
org.rootstock.vision.photon       PhotonCameraIO, PhotonStrategy
org.rootstock.vision.sim          RootstockVisionSim, RootstockCameraProps, SimulatedLimelight
```

**Enforced by CI, not by intent.** A Gradle check compiles the core jar against a classpath with photonlib deliberately absent, and an ArchUnit-style test asserts that no class outside `org.rootstock.vision.photon` / `org.rootstock.vision.sim` imports `org.photonvision.*`. If that test ever goes red, the vendor-neutrality claim in §2.6 is a lie and the build says so. **Note that the two `org.photonvision`-bearing packages now live in the same published artifact; the package boundary is what the test checks, and it is unchanged.**

**Other ArchUnit rules this domain must satisfy**, all owned by `design/06` §5.3 and listed here so the vision implementer sees them before writing code rather than after CI does:

- **Rule 10** — only `org.rootstock.core.match` may name `edu.wpi.first.wpilibj.DriverStation`. Vision calls `MatchContext` (§9.7, §11.4, §9.3).
- **Rules 2 and 12** — volatile-API confinement. Vision calls `Platform.isSimulation()`, never `RobotBase.isSimulation()` (§14.4).
- **Rule 3 / guarantee G2** — all time comes from `compat.Clock.now()` (== `Timer.getTimestamp()`), never `Timer.getFPGATimestamp()` (§14.3).
- **Rule 1 with the D13 exception** — `org.littletonrobotics.*` is legal inside core, and `LogTable`/`LoggableInputs` are additionally legal in any `..io..` package (`design/04` §2.7), which is what makes `VisionCameraIOInputs` legal.

**Java-17-safe subset only.** Records, sealed-free interfaces, `var`, arrow switch — yes. Pattern matching for `switch`, record patterns — no (they are preview in 17 and Rootstock must compile unchanged on the 2027 Java-25 branch).

---

## 4. The Unified Vision Interface

### 4.1 `PoseSource`

```java
package org.rootstock.vision;

/** How a robot pose in a VisionFrame was produced. Drives the std-dev model and the filter chain. */
public enum PoseSource {
  /** Limelight MegaTag1 (botpose_wpiblue). Pure PnP, ambiguity-prone on a single tag. */
  MEGATAG_1(false),
  /** Limelight MegaTag2 (botpose_orb_wpiblue). Gyro-fused; rotation is an INPUT, never an output. */
  MEGATAG_2(true),
  /** PhotonVision estimateCoprocMultiTagPose / estimateRioMultiTagPose. */
  MULTI_TAG_COPROC(false),
  /** PhotonVision estimateLowestAmbiguityPose / estimateClosestToCameraHeightPose /
   *  estimateAverageBestTargetsPose / estimateClosestToReferencePose. */
  SINGLE_TAG_PNP(false),
  /** PhotonVision estimatePnpDistanceTrigSolvePose. Requires addHeadingData(); gyro-fused. */
  PNP_DISTANCE_TRIG(true),
  /** PhotonVision estimateConstrainedSolvepnpPose. Gyro-seeded. */
  CONSTRAINED_SOLVEPNP(true),
  /** A custom coprocessor over NT. Trust model is whatever the schema declares. */
  CUSTOM(false),
  /** Produced by RootstockVisionSim. Behaves as MULTI_TAG_COPROC for std-dev purposes. */
  SIM(false);

  private final boolean gyroFused;
  PoseSource(boolean gyroFused) { this.gyroFused = gyroFused; }

  /** True when the robot's heading was an INPUT to this solve. Rotation from such a frame is worthless. */
  public boolean isGyroFused() { return gyroFused; }
}
```

### 4.2 `TargetObservation`

One target seen in one frame. A fiducial **or** a detected object — never both, but one record type so the logging, struct schema, and dashboard are written once.

```java
package org.rootstock.vision;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.util.struct.StructSerializable;

/**
 * One target in one frame, in a normalized convention:
 *   tx is CCW-POSITIVE (target to the LEFT of the principal point is positive)
 *   ty is UP-POSITIVE
 *   bestCameraToTarget / altCameraToTarget are in the WPILIB CAMERA FRAME:
 *       +x out of the lens, +y left, +z up
 * PhotonVision already uses both conventions (getYaw is documented "with left being the positive
 * direction", getPitch "with up being the positive direction"; camera-to-target transforms are
 * WPILib-framed). LimelightCameraIO NEGATES Limelight's tx, which is right-positive, and applies
 * the camera-space basis change in section 5.2a. Both normalizations happen exactly once, at the
 * IO boundary.
 */
public record TargetObservation(
    /** Links this target back to the VisionFrameHeader it came from. See section 4.3. */
    long frameSequence,
    /** AprilTag fiducial id, or -1. */
    int fiducialId,
    /** Neural detector class id, or -1. */
    int objectClassId,
    /** Detector confidence 0..1, or Double.NaN when not applicable. */
    double confidence,
    /** Yaw to target, CCW-positive, principal-point relative. */
    Rotation2d tx,
    /** Pitch to target, up-positive, principal-point relative. */
    Rotation2d ty,
    /** Bounding-box area as a percentage of the image, 0..100. */
    double areaPercent,
    /** PnP ambiguity ratio best:alt, 0..1, or Double.NaN when not applicable. */
    double ambiguity,
    /** Straight-line camera-to-target distance in meters, or Double.NaN. */
    double distanceToCameraMeters,
    /** True when bestCameraToTarget carries a real solve. */
    boolean hasBestCameraToTarget,
    /** Lowest-reprojection-error camera-to-target transform, WPILib camera frame.
     *  Transform3d.kZero when absent — NEVER null. Check hasBestCameraToTarget() first. */
    Transform3d bestCameraToTarget,
    /** True when altCameraToTarget carries a real second PnP solution. */
    boolean hasAltCameraToTarget,
    /** Highest-reprojection-error camera-to-target transform. Transform3d.kZero when absent. */
    Transform3d altCameraToTarget,
    /** Exactly 4 corner yaw angles (CCW-positive radians), or all NaN. */
    double[] cornerTxRad,
    /** Exactly 4 corner pitch angles (up-positive radians), or all NaN. */
    double[] cornerTyRad)
    implements StructSerializable {

  public static final int CORNER_COUNT = 4;
  public static final TargetObservationStruct struct = new TargetObservationStruct();

  public boolean isFiducial()  { return fiducialId >= 0; }
  public boolean isObject()    { return objectClassId >= 0; }
  public boolean hasCorners()  { return !Double.isNaN(cornerTxRad[0]); }

  public static TargetObservation fiducial(
      long frameSequence, int id, Rotation2d tx, Rotation2d ty, double areaPercent,
      double ambiguity, double distMeters,
      Optional<Transform3d> best, Optional<Transform3d> alt) {
    return new TargetObservation(frameSequence, id, -1, Double.NaN, tx, ty, areaPercent, ambiguity,
        distMeters,
        best.isPresent(), best.orElse(Transform3d.kZero),
        alt.isPresent(),  alt.orElse(Transform3d.kZero),
        nanCorners(), nanCorners());
  }

  public static TargetObservation object(
      long frameSequence, int classId, double confidence, Rotation2d tx, Rotation2d ty,
      double areaPercent, double[] cornerTxRad, double[] cornerTyRad) {
    return new TargetObservation(frameSequence, -1, classId, confidence, tx, ty, areaPercent,
        Double.NaN, Double.NaN,
        false, Transform3d.kZero,
        false, Transform3d.kZero,
        cornerTxRad, cornerTyRad);
  }

  private static double[] nanCorners() {
    double[] c = new double[CORNER_COUNT];
    java.util.Arrays.fill(c, Double.NaN);
    return c;
  }
}
```

**Why `boolean hasBest` + a zeroed `Transform3d` instead of a nullable field.** `Struct<T>` calls `pack(ByteBuffer, T)` unconditionally on every component; a `null` `Transform3d` NPEs inside `pack()` on the first object-detection frame, in the logging thread, at an event. Optionality on the wire is a flag byte, never a null — and the `rootstockV1` wire schema in §6.3 already encoded it exactly this way (`uint8 hasBestCameraToTarget`). The in-library record now matches the wire schema field for field, so `TargetObservationStruct` is a mechanical transcription rather than a translation with a hole in it.

`TargetObservation` **is** fixed-size and therefore legitimately `StructSerializable`: every component is a scalar, a `Rotation2d`, a `Transform3d`, or a fixed-length `double[4]` (WPILib's struct schema supports fixed-length arrays, `double cornerTxRad[4]`). `getSize()` is a compile-time constant. Contrast §4.3.

### 4.3 `VisionFrame` (in-memory) and `VisionFrameHeader` (on the wire)

Strict superset of AdvantageKit's `PoseObservation` (dossier: adopt and generalize — this record adds `cameraIndex`, `targets`, `tagSpanMeters`, `tagIds`, and an `Optional` pose so an object-detection-only frame is representable).

> **`VisionFrame` is NOT `StructSerializable`, and this is not an oversight.** WPILib's `Struct<T>` contract requires a fixed `int getSize()`. `Optional<Pose3d>`, `int[] tagIds` and `List<TargetObservation>` are all variable-length; no `getSize()` can exist. The earlier draft declared `implements StructSerializable` with a `public static final VisionFrameStruct struct` on this record, which is simply impossible — even though §6.3 of the same document had already stated the rule (*"WPILib structs cannot be variable-length, so the target list lives on its own topic"*) and had already solved it for the `rootstockV1` wire format. We now apply that same fix to the in-library type, and the in-library header mirrors the wire schema **field for field**, in the same order, so there is exactly one layout to get right. `DESIGN.md` §5.6 records the same conclusion.

```java
package org.rootstock.vision;

/**
 * One frame from one camera, already transformed to the ROBOT frame, blue-origin.
 * PLAIN RECORD — variable-length, in-memory only, never serialized directly.
 *
 * timestampSeconds is FPGA seconds at the MIDDLE OF EXPOSURE. Every IO implementation is
 * responsible for producing that number correctly; see section 5.
 */
public record VisionFrame(
    int cameraIndex,
    String cameraName,
    /** Monotonic per-camera counter. The join key between the two logged topics. */
    long frameSequence,
    double timestampSeconds,
    /** Empty for an object-detection-only frame, or when the source produced no pose. */
    Optional<Pose3d> robotPose,
    PoseSource source,
    int tagCount,
    double averageTagDistanceMeters,
    double tagSpanMeters,
    /** 0.0 for multi-tag and for any gyro-fused source; NaN when unknown. */
    double ambiguity,
    int[] tagIds,
    List<TargetObservation> targets) {

  public boolean hasPose()    { return robotPose.isPresent(); }
  public Pose2d pose2d()      { return robotPose.orElseThrow().toPose2d(); }
  public double ageSeconds(double nowSeconds) { return nowSeconds - timestampSeconds; }

  public List<TargetObservation> fiducials() {
    return targets.stream().filter(TargetObservation::isFiducial).toList();
  }
  public List<TargetObservation> objects() {
    return targets.stream().filter(TargetObservation::isObject).toList();
  }

  /** The fixed-size, serializable projection of this frame. Targets are logged separately. */
  public VisionFrameHeader header() {
    return new VisionFrameHeader(
        frameSequence, timestampSeconds,
        robotPose.isPresent(), robotPose.orElse(Pose3d.kZero),
        (byte) source.ordinal(), tagCount,
        averageTagDistanceMeters, tagSpanMeters, ambiguity);
  }
}
```

```java
package org.rootstock.vision;

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.util.struct.StructSerializable;

/**
 * The fixed-size sibling of VisionFrame. This is what actually goes on a NetworkTables topic,
 * into a DataLog, or into an AdvantageKit LogTable.
 *
 * Layout is IDENTICAL to the `RootstockVisionFrame` wire schema in section 6.3 — same fields, same
 * order, same types — so a rootstockV1 coprocessor's bytes and the robot's own log entries share one
 * schema string and AdvantageScope shows them in the same table.
 *
 * getSize() = 8 + 8 + 1 + Pose3d.struct.getSize() + 1 + 4 + 8 + 8 + 8 — a compile-time constant.
 */
public record VisionFrameHeader(
    long frameSequence,
    double captureTimestampSeconds,
    /** uint8 on the wire. False -> robotPose is Pose3d.kZero and must be ignored. */
    boolean hasPose,
    /** Field-relative, blue origin. Pose3d.kZero when hasPose is false. NEVER null. */
    Pose3d robotPose,
    /** PoseSource ordinal, uint8 on the wire. */
    byte source,
    int tagCount,
    double averageTagDistanceMeters,
    double tagSpanMeters,
    double ambiguity)
    implements StructSerializable {

  public static final VisionFrameHeaderStruct struct = new VisionFrameHeaderStruct();

  public PoseSource poseSource() { return PoseSource.values()[source]; }
}
```

**How a loop of frames is logged.** Two topics per camera, joined by `frameSequence`:

```java
// RootstockVision.periodic(), after filtering camera `name`'s frames this loop:
VisionFrameHeader[] headers = frames.stream().map(VisionFrame::header)
                                    .toArray(VisionFrameHeader[]::new);
TargetObservation[] targets = frames.stream().flatMap(f -> f.targets().stream())
                                    .toArray(TargetObservation[]::new);

// NOTE the argument order: (key, value, struct). design/04 §2.3 owns this facade.
RootstockLog.critical("Rootstock/Vision/" + name + "/Frames",  headers, VisionFrameHeader.struct);
RootstockLog.log     ("Rootstock/Vision/" + name + "/Targets", targets, TargetObservation.struct);
```

Both are `Struct<T>[]`, which `RootstockLog`'s `<T> void critical(String, T[], Struct<T>)` (§2.1) already supports and which NT4 and AdvantageKit both handle natively as variable-length arrays *of fixed-size elements* — the one shape the struct system is designed for. `tagIds` is not in the header; it is recoverable from the `Targets` topic (`fiducialId` of every target with the matching `frameSequence`) and is additionally logged as a plain `long[]` on `Rootstock/Vision/<name>/TagIds` for AdvantageScope convenience, which is also the key `design/04` §3.3 already reserves.

**Struct registration timing.** `DESIGN.md` §5.6 requires both vision structs to be **registered and written once during `robotInit()`**, because AdvantageKit documents the first log of a new struct as a >100 ms blocking cost. `RootstockVision.build()` writes one zero-length array on each of the two topics before returning, so the cost lands at boot and never at match start.

**Reconstruction in replay** is `VisionCameraIOInputs.fromLog(...)`: read both arrays, group targets by `frameSequence`, rebuild each `VisionFrame` with `Optional.of(pose)` when `hasPose` and `Optional.empty()` otherwise. This closes open question #10 (§21): the shape is fixed-size structs on two topics, and no `LogTable` support beyond `Struct<T>[]` is needed.

### 4.4 `VisionCameraIO` and its inputs

```java
package org.rootstock.vision.io;

public interface VisionCameraIO extends AutoCloseable {

  /** Implements AdvantageKit's LoggableInputs directly (maintainer decision 3).
   *  toLog/fromLog are hand-written -- D24 bans @AutoLog in library code. */
  final class VisionCameraIOInputs implements LoggableInputs {
    public boolean connected = false;
    /** Every frame drained this loop, oldest first. NEVER just the newest. */
    public VisionFrame[] frames = new VisionFrame[0];
    /** Frames per second reported by the camera, or -1. */
    public double fps = -1.0;
    /** Coprocessor CPU temperature in Celsius, or NaN. */
    public double cpuTempC = Double.NaN;
    /** Pipeline index the camera reports it is actually running. */
    public int currentPipeline = -1;
    /** Monotonically increasing per-frame counter, or -1 when unsupported. */
    public long heartbeat = -1L;
    /** Seconds since a frame with a pose last arrived. A large FINITE sentinel, not Infinity:
     *  this value is logged, replayed and compared, and §8.0 Rule 1's argument about Infinity
     *  surviving arithmetic applies to every sentinel in the library. 1e6 s is 11.5 days. */
    public double secondsSincePose = 1.0e6;

    @Override public void toLog(LogTable t)   { /* hand-written; see design/04 §2.4 */ }
    @Override public void fromLog(LogTable t) { /* hand-written; every public field, both ways */ }
  }

  void updateInputs(VisionCameraIOInputs inputs);

  /** Camera index assigned by RootstockVision at build time. Stamped into every VisionFrame. */
  void setIndex(int index);
  String name();

  /** Robot→camera transform. Always present for PhotonVision and for Limelight when
   *  pushCameraTransform(true) is used. Empty means "configured in a web UI" — a diagnostic finding. */
  default Optional<Transform3d> robotToCamera() { return Optional.empty(); }

  /** MegaTag2 / PNP_DISTANCE_TRIG / CONSTRAINED_SOLVEPNP feed. Called BEFORE updateInputs, every loop.
   *  `yaw` is PoseProvider.getGyroFieldHeading(): blue-origin, CCW-positive, 0 deg faces the RED wall. */
  default void setRobotOrientation(Rotation2d yaw, double yawRateRadPerSec) {}

  default void setPipeline(int index) {}
  /** True when the camera reports the requested pipeline AND the heartbeat has advanced since the request. */
  default boolean pipelineSettled(int requestedIndex) { return true; }

  default void setTagIdFilter(int[] ids) {}
  default void setThrottle(int skipFrames) {}
  default void setRecording(boolean active) {}

  @Override default void close() {}
}
```

**Every IO drains every frame.** `LimelightCameraIO` uses `DoubleArraySubscriber.readQueue()`; `PhotonCameraIO` uses `getAllUnreadResults()`; `CustomNTCameraIO` uses `readQueue()`. A 120 fps LL4 on a 50 Hz loop throws away >60% of its data otherwise (deep-vision elite practice #1). Note 8793's `VisionSubsystem.java` deliberately uses one frame per camera, walking the batch backwards to the newest frame that has targets, to avoid an overrun spiral — Rootstock solves that differently, with a per-loop **decode** cap (`maxFramesPerLoop`, default **4**) and a separate per-loop **accepted-measurement** cap (`maxAcceptedPerLoop`, default **2**) that coalesces rather than discards. The full reasoning, and why the old default of 20 was a loop-time bomb, is §9.6.

### 4.5 AdvantageKit template compatibility

```java
package org.rootstock.vision.compat;

/** Lets a team port an AdvantageKit vision-template project by changing imports only. */
public final class AdvantageKitCompat {
  public enum PoseObservationType { MEGATAG_1, MEGATAG_2, PHOTONVISION }
  public record PoseObservation(double timestamp, Pose3d pose, double ambiguity,
                                int tagCount, double averageTagDistance, PoseObservationType type) {}
  public record TargetObservation(Rotation2d tx, Rotation2d ty) {}

  public static PoseObservation toPoseObservation(VisionFrame f);
  public static VisionFrame fromPoseObservation(PoseObservation o, int cameraIndex, String name);
}
```

---

## 5. Latency & Timestamping — the exact math, per vendor

This section is the single most consequential page in the document. Get it wrong and everything downstream is confidently wrong.

### 5.1 The invariant

> `VisionFrame.timestampSeconds` is **FPGA seconds** at the **middle of exposure**.

WPILib pose estimators run on FPGA time. AdvantageKit's template deliberately uses FPGA timestamps end to end so no conversion is needed; Rootstock does the same, reading the clock through `RootstockLog.timestamp()` (== `compat.Clock.now()` == `Timer.getTimestamp()`, `design/04` guarantee G2). The *only* place a different time base appears is when the consumer is a CTRE `SwerveDrivetrain` — handled once, in §9.2.

### 5.2 Limelight

**Botpose array packing.** Positional, and undocumented in the LimelightHelpers Javadoc. **Verified element by element on 2026-08-08** against Limelight's own *Complete NetworkTables API* reference (`docs.limelightvision.io/docs/docs-limelight/apis/complete-networktables-api`) and cross-checked against the `getBotPoseEstimate` parser in `LimelightHelpers.java` on the `LimelightVision/limelightlib-wpijava` `main` branch. Both sources agree exactly. Applies identically to `botpose`, `botpose_wpiblue`, `botpose_wpired`, `botpose_orb`, `botpose_orb_wpiblue`, `botpose_orb_wpired`.

| Index | Meaning | Units | Verified against |
|---:|---|---|---|
| 0 | x | meters | docs: *"Translation (X,Y,Z) in meters"* · helpers: `poseArray[0]` |
| 1 | y | meters | as above |
| 2 | z | meters | as above |
| 3 | roll | **degrees** | docs: *"Rotation(Roll,Pitch,Yaw) in degrees"* |
| 4 | pitch | **degrees** | as above |
| 5 | yaw | **degrees** | as above |
| 6 | **total latency (cl + tl)** | **milliseconds** | docs: *"total latency (cl+tl)"* · helpers: `latency = poseArray[6]` |
| 7 | tag count | count | docs: *"tag count"* · helpers: `tagCount = (int) poseArray[7]` |
| 8 | tag span | meters | docs: *"tag span"* · helpers: `tagSpan = poseArray[8]` |
| 9 | average tag distance from camera | meters | docs: *"average tag distance from camera"* · helpers: `tagDist = poseArray[9]` |
| 10 | average tag area | % of image | docs: *"average tag area (percentage of image)"* · helpers: `tagArea = poseArray[10]` |
| `11 + 7k + 0` | tag id (block *k*) | int | helpers: `baseIndex = 11 + (i * 7)`, `id` |
| `11 + 7k + 1` | txnc | degrees, **right-positive** | helpers: `txnc` |
| `11 + 7k + 2` | tync | degrees, up-positive | helpers: `tync` |
| `11 + 7k + 3` | ta | % of image | helpers: `ta` |
| `11 + 7k + 4` | distToCamera | meters | helpers: `distToCamera` |
| `11 + 7k + 5` | distToRobot | meters | helpers: `distToRobot` |
| `11 + 7k + 6` | ambiguity | 0..1 | helpers: `ambiguity` |

`LimelightHelpers` states the stride explicitly as `valsPerFiducial = 7` and the expected length as `expectedTotalVals = 11 + valsPerFiducial * tagCount`, which is the same arithmetic. Therefore **MegaTag1 first-tag ambiguity is index 17** (`11 + 7·0 + 6`). Array length is `11 + 7 * tagCount`; anything shorter than 11 is a malformed sample and is dropped with `RejectReason.MALFORMED_FRAME`.

`tl` and `cl` are **separate NT keys** — the docs define `tl` as *"pipeline latency contribution in milliseconds"* and `cl` as *"capture pipeline latency in milliseconds"*. Index 6 is their **sum**, so neither key alone is the pose latency and using either alone under-subtracts. Do not use them for the pose timestamp.

**Timestamp math.**

```java
// LimelightCameraIO.updateInputs, hot path — raw NT, never LimelightHelpers.getLatestResults()
for (TimestampedDoubleArray sample : m_botposeSub.readQueue()) {
  if (sample.value.length < 11) { m_malformed++; continue; }
  // sample.timestamp is NT-server microseconds. The roboRIO IS the NT server, so NT server
  // time == FPGA time. Subtract the camera-reported total latency (ms -> s).
  double timestampSeconds = (sample.timestamp * 1.0e-6) - (sample.value[6] * 1.0e-3);
  ...
}
```

Two honest caveats we document in the Javadoc and surface in `VisionDiagnostics`:

1. `sample.timestamp` is when the **server received** the value, i.e. *after* network transit. Subtracting only the camera-side latency yields a capture time that is **late by the transit time** (~1–3 ms on a wired network, far worse on a saturated radio). We log `Rootstock/Vision/<name>/NetworkTransitEstimateSecs` as `now - sample.timestamp*1e-6` so a team can see when the number goes bad.
2. Limelight OS 2026.0 changed to **middle-of-exposure** capture timestamps (middle row for rolling shutter). On LLOS ≤ 2025 the convention is start-of-exposure. Rootstock assumes 2026.0+ and raises an info-level finding if it cannot confirm the OS version. **[UNVERIFIED]** — re-checked 2026-08-08 against the complete NetworkTables reference: **there is no key that reports the Limelight OS or firmware version.** The check is a documentation item, not a runtime one. §21 OQ 1.

**MegaTag2 `SetRobotOrientation` + flush ordering.** This is mandatory and order-sensitive. The key's documented layout is *"SET Robot Orientation and angular velocities in degrees and degrees per second [yaw, yawrate, pitch, pitchrate, roll, rollrate]"* (verified 2026-08-08); `LimelightHelpers.SetRobotOrientation(String limelightName, double yaw, double yawRate, double pitch, double pitchRate, double roll, double rollRate)` passes them in that order.

```java
// LimelightCameraIO.setRobotOrientation(...) — called by RootstockVision.periodic() BEFORE updateInputs()
@Override
public void setRobotOrientation(Rotation2d yaw, double yawRateRadPerSec) {
  if (m_mode == LimelightMode.MEGATAG1) return;
  m_orientationEntry.set(new double[] {
      yaw.getDegrees(),                             // blue-origin, CCW-positive, 0 deg faces the RED wall
      Units.radiansToDegrees(yawRateRadPerSec),
      0.0, 0.0,                                     // pitch, pitchRate  — unused by MegaTag2
      0.0, 0.0                                      // roll,  rollRate   — unused by MegaTag2
  });
  // MANDATORY. NT batches by default; without this the camera sees a stale yaw.
  // AdvantageKit's own comment: "Increases network traffic but recommended by Limelight."
  NetworkTableInstance.getDefault().flush();
  m_orientationWrites++;
}
```

Rules enforced in code, not in a README:

- `LimelightCameraIO` counts `m_orientationWrites`. If `m_mode != MEGATAG1` and the count has not advanced in the last 0.5 s while frames are arriving, we raise

  ```java
  Alerts.error("Vision",
      name + ": MegaTag2 selected but robot_orientation_set is not being written. "
    + "MegaTag2 output is garbage without it.",
      MatchImpact.BLOCKS_MATCH).set(true);
  ```

  and set `RejectReason.MEGATAG2_NO_ORIENTATION` on every MT2 frame. Writing it once at init produces a pose that looks fine at startup and drifts silently — that failure now names itself.
- The write happens **before** the read, every loop, with an explicit flush between. There is still an inherent one-frame lag (the frame you read was solved with the yaw you wrote earlier); this is a property of the vendor, not a bug, and is why MegaTag2's `sigmaTheta` is pinned to `StdDevModels.UNTRUSTED_SIGMA` anyway.
- **The yaw comes from `PoseProvider.getGyroFieldHeading()`** — raw IMU yaw plus the field offset latched at the last pose reset (§2.2). This is precisely the frame the key demands: blue-origin, CCW-positive, 0 deg facing the RED wall. A raw IMU yaw would be referenced to power-on and is never correct here; the fused estimate would close a feedback loop. There is exactly one accessor and it is neither of those things.
- `RootstockVision` cannot detect by reflection whether a team wired `getGyroFieldHeading()` to the fused estimate, so we defend by consequence instead: `VisionDiagnostics.GYRO_OFFSET_UNSEEDED` fires when a gyro-fused camera is configured and `PoseProvider.gyroFieldOffsetSeeded()` is false (§2.2a item 3), and `Rootstock/Vision/GyroFieldHeadingDeg` is logged every loop next to `Rootstock/Vision/FusedHeadingDeg` so the two curves being identical is visible in AdvantageScope in one glance.

**LL4 IMU modes.** `imumode_set`, quoted verbatim from the vendor reference (verified 2026-08-08): *"Set the imumode. 0 - use external imu, 1 - use external imu, seed internal imu, 2 - use internal, 3 - use internal with MT1 assisted convergence, 4 - use internal IMU with external IMU assisted convergence."* Set through `LimelightHelpers.SetIMUMode(String, int)`. Rootstock default for LL4 is **mode 4**, set once at construction and re-asserted every 5 s (configs are lost across a camera reboot). `imuassistalpha_set` — *"Complementary filter alpha / strength. Higher values will cause the internal imu to converge on assist source more rapidly"* — via `LimelightHelpers.SetIMUAssistAlpha(String, double)`, default 0.001.

**Limelight also publishes its own std devs.** The `stddevs` key is documented as *"MegaTag Standard Deviations [MT1x, MT1y, MT1z, MT1roll, MT1pitch, MT1Yaw, MT2x, MT2y, MT2z, MT2roll, MT2pitch, MT2yaw]"* — a 12-element array (verified 2026-08-08). We read it into `VisionCameraIOInputs` and expose it via `StdDevModels.limelightReported()` as an *option*, but it is not the default — the distance²/tag-count model is better characterized across the community, and whether these numbers are in the same statistical sense as WPILib's `visionMeasurementStdDevs` is **[UNVERIFIED]** (§21 OQ 9).

**The `imu` key.** Documented as *"IMU data output [robot_yaw, roll, pitch, internal_yaw, roll_rate, pitch_rate, yaw_rate, accel_x, accel_y, accel_z] (10 elements). Angles in degrees, rates in deg/s."* `LimelightCameraIO.imuData()` exposes `LimelightHelpers.IMUData` as an escape hatch; Rootstock does not consume it in the pose path.

### 5.2a Limelight camera space is not WPILib camera space — the basis change, written out

**This is a revision-4 addition and it is load-bearing for `alignToTag`.** `LimelightCameraIO` populates `TargetObservation.bestCameraToTarget` from the `targetpose_cameraspace` key, and §13.5's tag-relative controller closes its loop on exactly that transform. Revision 3 specified the read and specified no basis change. There is one.

Verified 2026-08-08 against Limelight's *AprilTag Coordinate Systems* page and its *Complete NetworkTables API*:

| Frame | Limelight | WPILib |
|---|---|---|
| **Camera space** | *"X+ → Pointing to the right (if you were to embody the camera); Y+ → Pointing downward; Z+ → Pointing out of the camera"* | +x out of the lens, +y left, +z up |
| **Robot space** | *"X+ → Pointing forward (Forward Vector); Y+ → Pointing toward the robot's right (Right Vector); Z+ → Pointing upward"* | +x forward, +y **left**, +z up |

`targetpose_cameraspace` is documented as *"3D transform of the primary in-view AprilTag in the coordinate system of the Camera (array (6)) \[tx, ty, tz, pitch, yaw, roll\] (meters, degrees)"*. Note two traps in one line: the array is **six** elements, and the rotation triple is ordered **pitch, yaw, roll** — not the `roll, pitch, yaw` of the botpose array.

**The translation basis change is unambiguous and is specified now:**

```java
package org.rootstock.vision.limelight;

/** The single translation point between Limelight camera space and WPILib camera space. */
public final class LimelightCameraSpace {

  /**
   * Limelight camera space: X right, Y down, Z out of the lens.
   * WPILib  camera space: X out of the lens, Y left, Z up.
   *
   *     x_wpi =  z_ll
   *     y_wpi = -x_ll
   *     z_wpi = -y_ll
   *
   * Both bases are right-handed, so this is a pure rotation, not a mirror.
   */
  public static Translation3d toWpilib(double txMeters, double tyMeters, double tzMeters) {
    return new Translation3d(tzMeters, -txMeters, -tyMeters);
  }

  /**
   * Rotation half. targetpose_cameraspace[3..5] is [pitch, yaw, roll] in DEGREES, but the vendor
   * documentation does not state the composition order or the handedness of each term, and the
   * basis change above permutes the axes those names refer to.
   *
   * <p><b>[UNVERIFIED]</b> — see section 21 OQ 13. The implementation is the reading consistent
   * with the documented axis definitions (pitch about the Limelight camera's X, yaw about its Y,
   * roll about its Z, recomposed after the basis change). It is NOT confirmed against hardware,
   * and the runtime cross-check below exists precisely because it is not.
   */
  public static Rotation3d toWpilib(double pitchDeg, double yawDeg, double rollDeg) { /* ... */ }
}
```

**The runtime cross-check that names the failure if we got it wrong.** Limelight publishes `targetpose_robotspace` — *"3D transform of the primary in-view AprilTag in the coordinate system of the Robot (array (6)) \[tx, ty, tz, pitch, yaw, roll\]"* — which is the same quantity we compute as `robotToCamera ∘ cameraToTag`. When `pushCameraTransform(true)` is in effect (the default) both numbers exist, and they must agree:

```java
// LimelightCameraIO, once per second, only when a primary tag is in view.
Transform3d ours   = m_robotToCamera.plus(cameraToTagFromCameraSpace);
Transform3d theirs = robotSpaceTransformFromKey();        // targetpose_robotspace
double dxyz = ours.getTranslation().getDistance(theirs.getTranslation());
double ddeg = Math.abs(ours.getRotation().minus(theirs.getRotation()).getAngle());
if (dxyz > 0.03 || ddeg > Units.degreesToRadians(3.0)) {
  Alerts.warning("Vision",
      name + ": our composed robot->tag transform disagrees with the camera's own "
    + "targetpose_robotspace by " + fmt(dxyz) + " m / " + fmtDeg(ddeg) + " deg. "
    + "This is the Limelight camera-space axis convention (design/03 5.2a). alignToTag on this "
    + "camera is not trustworthy until it is resolved.",
      MatchImpact.PIT_ONLY).set(true);
}
```

A sign error on one axis shows up here as a residual of exactly `2·|that component|`, which is both large and diagnostic. The alert is PIT_ONLY because it fires from a *diagnostic* comparison, not from a failure to produce poses — the global pose path (botpose) does not go through camera space at all, so a mis-signed camera-space conversion degrades `alignToTag` and nothing else.

**A second, smaller documentation conflict, recorded rather than resolved.** The *AprilTag Coordinate Systems* page says Limelight **robot** space has *"Y+ → Pointing toward the robot's right"*, while the *LimelightLib* page documents `setCameraPose_RobotSpace`'s second argument as *"Side offset (meters), left of robot center"* — i.e. Y+ **left**, which is WPILib's convention and is what §11.1 writes. The two vendor pages disagree with each other. **[UNVERIFIED]**; §21 OQ 14. §11.1's write is left-positive because that is what the API page that documents the setter says, and the same `targetpose_robotspace` cross-check above catches it if that reading is wrong (a Y-sign error appears as a residual of `2·|y|`).

**Why we do not simply use `targetpose_robotspace` and skip the composition.** Because it is only correct if the camera transform we pushed took effect, and the whole point of §11.1 is that a Limelight's stored transform is the thing most likely to be silently wrong or lost after a reflash. Composing from our own `Transform3d` keeps `alignToTag` correct on a freshly reflashed camera; the vendor's own number is the *check*, not the source. It is also the only formulation that works identically for PhotonVision, which publishes no robot-space transform.

### 5.3 PhotonVision

```java
for (PhotonPipelineResult result : m_camera.getAllUnreadResults()) {
  // Already in the RIO time base: PhotonVision 2026 runs a Time Sync Server and getTimestampSeconds()
  // is the estimated frame-capture time in that time base.
  double timestampSeconds = result.getTimestampSeconds();
  ...
}
```

`PhotonPipelineResult.getTimestampSeconds()` returns `double` and is not deprecated (verified 2026-08-08 against `photon-targeting/src/main/java/org/photonvision/targeting/PhotonPipelineResult.java` at tag `v2026.3.4`; the class has no deprecated members).

No arithmetic. **Do not** recompute from `result.metadata` — PhotonVision already did the sync, and re-deriving it is how teams break it.

`PhotonPoseEstimator` returns an `EstimatedRobotPose` whose `timestampSeconds` field is the same number; use whichever, but never mix.

### 5.4 Custom NT coprocessors

The coprocessor must publish **middle-of-exposure time converted into NT server time**. This is where homebrew systems die. Rootstock's Python helper (`rootstock_vision` on PyPI) exposes:

```python
from rootstock_vision import VisionPublisher, nt_now_us
# nt_now_us() resolves camera monotonic time -> NT server time using the NT4 time-sync offset.
```

On the robot side, a `rootstockV1` frame carries its own `captureTimestampMicros` in NT server time; we convert with `* 1e-6` and do not subtract anything. For the Northstar back-compat decoder we use the NT sample timestamp minus the coprocessor-declared latency, exactly as 6328 do.

`VisionFilters.timestampSane(...)` (§7) is the backstop: a frame whose timestamp is in the future, or older than the odometry buffer, is rejected with a named reason and counted, instead of silently no-op'ing inside `addVisionMeasurement`.

---

## 6. Implementations

### 6.1 `LimelightCameraIO`

```java
package org.rootstock.vision.limelight;

public final class LimelightCameraIO implements VisionCameraIO {

  public enum LimelightMode {
    /** botpose_wpiblue only. Correct while DISABLED (no gyro authority yet) and for tag-relative work. */
    MEGATAG1,
    /** botpose_orb_wpiblue only. The default while enabled. */
    MEGATAG2,
    /** Both, tagged with distinct PoseSource values. The filter chain and std-dev model separate them. */
    BOTH
  }

  // ---- factories -------------------------------------------------------------------------
  public static LimelightCameraIO megaTag2(String ntName, Transform3d robotToCamera);
  public static LimelightCameraIO megaTag1(String ntName, Transform3d robotToCamera);
  /** MegaTag1 while disabled, MegaTag2 while enabled — the 9143 template's proven pattern. */
  public static LimelightCameraIO auto(String ntName, Transform3d robotToCamera);

  // ---- fluent config ---------------------------------------------------------------------
  /** LL4 IMU fusion mode 0..4. Default 4 (INTERNAL_EXTERNAL_ASSIST). Re-asserted every 5 s. */
  public LimelightCameraIO withImuMode(int mode);
  public LimelightCameraIO withImuAssistAlpha(double alpha);        // default 0.001
  /** Writes camerapose_robotspace_set at boot and every 5 s, then reads camerapose_robotspace back
   *  and raises an Alert on mismatch > 1 cm / 1 deg. DEFAULT TRUE. See section 11.1. */
  public LimelightCameraIO pushCameraTransform(boolean push);
  public LimelightCameraIO withTagIdFilter(int... ids);             // fiducial_id_filters_set
  public LimelightCameraIO withDetectorPipeline(int index);         // enables object-detection decode
  public LimelightCameraIO withMaxFramesPerLoop(int max);           // default 4 — see section 9.6
  /**
   * Attach a sim twin. No-op on a real robot, and no-op when rootstock-photonvision is absent
   * (named Alert, §2.6). Takes the CORE-owned CameraSimProfile, never photonlib's
   * SimCameraProperties — that is what keeps this artifact photonlib-free. See §14.2.
   */
  public LimelightCameraIO simulated(CameraSimProfile profile);

  @Override public Optional<Transform3d> robotToCamera();
  public LimelightHelpers.IMUData imuData();                        // escape hatch
  public NetworkTable rawTable();                                   // escape hatch
}
```

**Hot path (raw NT, not `LimelightHelpers`).** Constructed once:

```java
NetworkTable t = NetworkTableInstance.getDefault().getTable(m_ntName);
m_orientationEntry = t.getDoubleArrayTopic("robot_orientation_set").getEntry(new double[6]);
m_mt2Sub  = t.getDoubleArrayTopic("botpose_orb_wpiblue").subscribe(
                new double[0], PubSubOption.keepDuplicates(true), PubSubOption.sendAll(true),
                PubSubOption.pollStorage(20), PubSubOption.periodic(0.01));
m_mt1Sub  = t.getDoubleArrayTopic("botpose_wpiblue").subscribe(new double[0], /* same options */);
m_rawFidSub  = t.getDoubleArrayTopic("rawfiducials").subscribe(new double[0]);
m_rawDetSub  = t.getDoubleArrayTopic("rawdetections").subscribe(new double[0]);
m_stddevsSub = t.getDoubleArrayTopic("stddevs").subscribe(new double[0]);
m_tgtCamSub  = t.getDoubleArrayTopic("targetpose_cameraspace").subscribe(new double[0]);
m_tgtRobotSub= t.getDoubleArrayTopic("targetpose_robotspace").subscribe(new double[0]);
m_hbSub      = t.getDoubleTopic("hb").subscribe(0.0);
m_getpipeSub = t.getIntegerTopic("getpipe").subscribe(-1);
m_pipelinePub= t.getIntegerTopic("pipeline").publish();
```

`getBotPoseEstimate_*()` is **never** used (returns only the newest sample). `getLatestResults()` is **never** used (full JSON parse on the RIO; a well-known loop-overrun source). Both are still reachable through `LimelightHelpers` for teams that want them — visible escape hatch, not the default path.

`hb` is documented as *"heartbeat value. Increases once per frame, resets at 2 billion"* and `getpipe` as *"True active pipeline index of the camera (0 .. 9)"* (verified 2026-08-08). The heartbeat wrap at 2e9 is handled: `pipelineSettled` compares a *delta* and treats a negative delta as "wrapped, therefore advanced."

**Per-tag decode.** From index 11, stride 7, we emit a `TargetObservation.fiducial(...)` per tag with `tx` **negated** (Limelight `txnc` is right-positive; Rootstock is CCW-positive). `bestCameraToTarget` is `Optional.empty()` for botpose-derived targets — which becomes `hasBestCameraToTarget == false` and a zeroed `Transform3d`, never a null (§4.2). When `LimelightCameraIO` needs the transform for `TagResidualMonitor` or for `VisionCommands.alignToTag` it reads `targetpose_cameraspace` **for the primary tag only** (the key is documented as *"the primary in-view AprilTag"*), converts it through `LimelightCameraSpace` (§5.2a), and passes `Optional.of(...)` on that one target. The primary tag is the one `tid` reports.

**Pipeline switching with settle detection.**

```java
@Override public void setPipeline(int index) {
  m_pipelinePub.set(index);
  m_pipelineRequestedAt = RootstockLog.timestamp();
  m_pipelineRequestHeartbeat = m_hbSub.get();
  m_pipelineRequested = index;
}
@Override public boolean pipelineSettled(int requestedIndex) {
  return m_getpipeSub.get() == requestedIndex
      && heartbeatAdvancedBy(SETTLE_FRAMES);   // SETTLE_FRAMES = 3, wrap-safe
}
```

**[UNVERIFIED]** — the exact number of frames a Limelight pipeline switch costs is not documented anywhere (re-checked 2026-08-08). `SETTLE_FRAMES = 3` is a conservative default; `LimelightCameraIO` measures the observed settle time on every switch and logs it to `Rootstock/Vision/<name>/PipelineSettleSecs`, so a team can see the real number for their hardware instead of trusting ours. §21 OQ 3.

**Also note:** `snapshot` is documented as *"Takes a snapshot. Increment this value to trigger a capture (e.g., 0→1→2→3). Rate-limited to once every 10 frames"* (verified 2026-08-08) — a rising-edge counter, not a level. `LimelightCameraIO.triggerSnapshot()` increments; code that sets it to 1 and leaves it captures once and then stops.

**Downscale.** `fiducial_downscale_set` is documented as *"Override AprilTag detection downscale. 0=pipeline control, 1=1x (no downscale), 2=1.5x, 3=2x, 4=3x, 5=4x"* (verified 2026-08-08). `LimelightCameraIO.setFiducialDownscale(int)` writes it; `0` restores pipeline control.

### 6.2 `PhotonCameraIO` — the 2026 API, correctly

PhotonVision 2026 **deprecated** the `PoseStrategy` enum + `PhotonPoseEstimator.update()` path in favor of explicit per-strategy methods.

> ### Verification note — revision 2's claim here was false, and the reason matters
>
> **Revision 2 asserted:** *"`PhotonPoseEstimator(AprilTagFieldLayout, Transform3d)` is the only constructor, and the class lists no deprecated members at all. The 2024/2025 `(layout, PoseStrategy, transform)` form was removed, not deprecated."*
>
> **That is wrong.** Verified 2026-08-08 against the **v2026.3.4 source tag** (`github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-lib/src/main/java/org/photonvision/PhotonPoseEstimator.java`):
>
> - `PhotonPoseEstimator(AprilTagFieldLayout, PoseStrategy, Transform3d)` **exists** and is `@Deprecated(forRemoval = true, since = "2026")`.
> - `update(PhotonPipelineResult)` **exists**, in three overloads, all `@Deprecated`.
> - `getPrimaryStrategy()`, `setPrimaryStrategy(PoseStrategy)`, `setMultiTagFallbackStrategy(PoseStrategy)`, `getReferencePose()`, `setReferencePose(...)` (2 overloads) and `setLastPose(...)` (2 overloads) all **exist** and are all `@Deprecated`.
> - The `PoseStrategy` enum is still declared and is **not itself** annotated deprecated; every live path into it is.
>
> **Root cause, and the process fix.** Revision 2 cited `javadocs.photonvision.org/release/...`. As of 2026-08-08 that URL serves **PhotonVision v2027.0.0-alpha-2**, in which the deprecated members really are gone — so the claim was true of a version we do not target and false of the one we do. A floating `/release/` URL is not a citation. **Every vendor claim in this document is now pinned to an immutable git tag or to a vendor reference page that is itself versioned**, and `[UNVERIFIED]` is used where neither exists. This is the same defect class as the review's B2/B5 findings in `design/01`, arriving through a different door.
>
> **What is unchanged:** the two-argument constructor exists and is the non-deprecated one; the eight explicit estimator entry points exist with exactly the signatures below; `addHeadingData(double, Rotation2d)` / `addHeadingData(double, Rotation3d)` and `resetHeadingData(double, Rotation2d)` / `resetHeadingData(double, Rotation3d)` exist and are not deprecated. **Rootstock's design does not change** — we already used only the non-deprecated surface. What changes is what we may *claim*, and the migration advice we give.

The eight estimation entry points, all returning `Optional<EstimatedRobotPose>`, verified at tag `v2026.3.4`:

`estimateCoprocMultiTagPose(PhotonPipelineResult)` · `estimateRioMultiTagPose(PhotonPipelineResult, Matrix<N3,N3>, Matrix<N8,N1>)` · `estimateLowestAmbiguityPose(PhotonPipelineResult)` · `estimateClosestToCameraHeightPose(PhotonPipelineResult)` · `estimateClosestToReferencePose(PhotonPipelineResult, Pose3d)` · `estimateAverageBestTargetsPose(PhotonPipelineResult)` · `estimatePnpDistanceTrigSolvePose(PhotonPipelineResult)` · `estimateConstrainedSolvepnpPose(PhotonPipelineResult, Matrix<N3,N3>, Matrix<N8,N1>, Pose3d, boolean, double)`.

**One legacy strategy has no explicit replacement, and a migrating team must be told.** `PoseStrategy` declares nine constants; the deprecated `CLOSEST_TO_LAST_POSE` (which `setLastPose` fed) has **no** `estimateClosestToLastPose` method. A team on that strategy migrates to `CLOSEST_TO_REFERENCE` supplying their own last pose — which is what `PhotonStrategy.CLOSEST_TO_REFERENCE` below does, and which carries the self-reinforcement warning it deserves.

`PhotonStrategy` below covers all eight explicit methods — the earlier draft omitted `estimateClosestToReferencePose`, which is now present with a documented reason why it is not a default.

```java
package org.rootstock.vision.photon;

public final class PhotonCameraIO implements VisionCameraIO {

  /** Maps 1:1 onto the 2026 PhotonPoseEstimator methods. No PoseStrategy anywhere. */
  public enum PhotonStrategy {
    /** estimateCoprocMultiTagPose(result) — the default primary. */
    COPROC_MULTITAG,
    /** estimateRioMultiTagPose(result, cameraMatrix, distCoeffs) — needs calibration on the RIO. */
    RIO_MULTITAG,
    /** estimateLowestAmbiguityPose(result) — the default fallback. */
    LOWEST_AMBIGUITY,
    /** estimateClosestToCameraHeightPose(result) */
    CLOSEST_TO_CAMERA_HEIGHT,
    /**
     * estimateClosestToReferencePose(result, Pose3d referencePose) — picks the PnP solution nearest
     * a supplied reference. Rootstock supplies the CURRENT pose estimate at the frame's capture
     * time, i.e. `new Pose3d(ctx.estimateAtCapture().orElse(ctx.currentEstimate()))`. This is also
     * the migration target for the deprecated CLOSEST_TO_LAST_POSE strategy.
     *
     * <p>Not a default, and here is the honest reason: it is a self-reinforcing strategy. If the
     * estimate is already wrong, this picks the solution that agrees with the wrong estimate, and
     * the error is never corrected. `disambiguateAgainstGyro` (section 7.2) does the same job using
     * the GYRO as the reference, which is an independent sensor and therefore cannot self-confirm.
     * We ship CLOSEST_TO_REFERENCE because the API has it and a team may want it, and we document
     * that gyro disambiguation is the better tool.
     */
    CLOSEST_TO_REFERENCE,
    /** estimateAverageBestTargetsPose(result) */
    AVERAGE_BEST_TARGETS,
    /** estimatePnpDistanceTrigSolvePose(result) — REQUIRES addHeadingData() every loop. */
    PNP_DISTANCE_TRIG,
    /** estimateConstrainedSolvepnpPose(...) — REQUIRES addHeadingData() AND camera calibration. */
    CONSTRAINED_SOLVEPNP
  }

  public static PhotonCameraIO coprocMultiTag(String name, Transform3d robotToCamera);
  public static PhotonCameraIO of(String name, Transform3d robotToCamera,
                                  PhotonStrategy primary, PhotonStrategy fallback);

  public PhotonCameraIO withConstrainedParams(boolean headingFree, double headingScaleFactor);
  public PhotonCameraIO withMaxFramesPerLoop(int max);              // default 4 — see section 9.6
  public PhotonCameraIO withObjectDetection(boolean enabled);
  /** Same core-owned value type as LimelightCameraIO, for one uniform call site. See §14.2. */
  public PhotonCameraIO simulated(CameraSimProfile profile);

  public PhotonCamera camera();                                     // escape hatch
  public PhotonPoseEstimator estimator();                           // escape hatch
}
```

Construction and the loop body:

```java
private final PhotonCamera m_camera;
private final PhotonPoseEstimator m_estimator;

PhotonCameraIO(String name, Transform3d robotToCamera, AprilTagFieldLayout layout,
               PhotonStrategy primary, PhotonStrategy fallback) {
  m_camera = new PhotonCamera(name);
  // Verified 2026-08-08 against PhotonVision tag v2026.3.4 (photon-lib .../PhotonPoseEstimator.java):
  // the TWO-argument constructor is the non-deprecated one. The 2024/2025
  // `(layout, PoseStrategy, transform)` form still exists but is
  // @Deprecated(forRemoval = true, since = "2026") and will be gone in 2027 — do not use it.
  m_estimator = new PhotonPoseEstimator(layout, robotToCamera);
}

@Override public void setRobotOrientation(Rotation2d yaw, double yawRateRadPerSec) {
  // Required for PNP_DISTANCE_TRIG and CONSTRAINED_SOLVEPNP; harmless otherwise, so we always feed it.
  // `yaw` is PoseProvider.getGyroFieldHeading() — blue-origin, never the fused estimate (section 2.2).
  // Verified overloads: addHeadingData(double, Rotation2d) and addHeadingData(double, Rotation3d),
  // plus resetHeadingData(double, Rotation2d|Rotation3d) which we call on a pose reset. None deprecated.
  m_estimator.addHeadingData(RootstockLog.timestamp(), yaw);
  m_headingWrites++;
}

@Override public void updateInputs(VisionCameraIOInputs inputs) {
  inputs.connected = m_camera.isConnected();
  inputs.currentPipeline = m_camera.getPipelineIndex();

  List<VisionFrame> out = new ArrayList<>();
  for (PhotonPipelineResult result : m_camera.getAllUnreadResults()) {
    if (out.size() >= m_maxFramesPerLoop) break;
    Optional<EstimatedRobotPose> est = estimate(m_primary, result);
    PoseSource src = sourceOf(m_primary);
    if (est.isEmpty()) { est = estimate(m_fallback, result); src = sourceOf(m_fallback); }
    out.add(toFrame(result, est, src));
  }
  inputs.frames = out.toArray(VisionFrame[]::new);
}

private Optional<EstimatedRobotPose> estimate(PhotonStrategy s, PhotonPipelineResult r) {
  return switch (s) {
    case COPROC_MULTITAG          -> m_estimator.estimateCoprocMultiTagPose(r);
    case LOWEST_AMBIGUITY         -> m_estimator.estimateLowestAmbiguityPose(r);
    case CLOSEST_TO_CAMERA_HEIGHT -> m_estimator.estimateClosestToCameraHeightPose(r);
    case AVERAGE_BEST_TARGETS     -> m_estimator.estimateAverageBestTargetsPose(r);
    case CLOSEST_TO_REFERENCE     -> m_estimator.estimateClosestToReferencePose(r, m_referencePose);
    case PNP_DISTANCE_TRIG        -> m_estimator.estimatePnpDistanceTrigSolvePose(r);
    case RIO_MULTITAG             -> withCalibration(
        (k, d) -> m_estimator.estimateRioMultiTagPose(r, k, d));
    case CONSTRAINED_SOLVEPNP     -> withCalibration(
        (k, d) -> m_estimator.estimateConstrainedSolvepnpPose(
                      r, k, d, m_seedPose, m_headingFree, m_headingScaleFactor));
  };
}

/** camera.getCameraMatrix()/getDistCoeffs() are BOTH Optional and BOTH empty when the camera is
 *  uncalibrated at the current streaming resolution (verified: Optional<Matrix<N3,N3>> and
 *  Optional<Matrix<N8,N1>> at tag v2026.3.4). Missing calibration becomes a named Finding,
 *  not a silent empty Optional. */
private Optional<EstimatedRobotPose> withCalibration(
    BiFunction<Matrix<N3,N3>, Matrix<N8,N1>, Optional<EstimatedRobotPose>> f) {
  Optional<Matrix<N3,N3>> k = m_camera.getCameraMatrix();
  Optional<Matrix<N8,N1>> d = m_camera.getDistCoeffs();
  if (k.isEmpty() || d.isEmpty()) { m_uncalibratedAlert.set(true); return Optional.empty(); }
  m_uncalibratedAlert.set(false);
  return f.apply(k.get(), d.get());
}
```

Per-frame `TargetObservation`s come from `result.getTargets()`:

```java
for (PhotonTrackedTarget t : result.getTargets()) {
  if (t.getFiducialId() >= 0) {
    // Optional.ofNullable, not a bare pass-through: a 3D-disabled or uncalibrated pipeline hands
    // back a zeroed/absent transform, and a null reaching TargetObservationStruct.pack() would NPE
    // on the logging thread. The record stores hasBest/hasAlt flags instead (section 4.2).
    Optional<Transform3d> best = Optional.ofNullable(t.getBestCameraToTarget());
    Optional<Transform3d> alt  = Optional.ofNullable(t.getAlternateCameraToTarget());
    targets.add(TargetObservation.fiducial(
        frameSequence,
        t.getFiducialId(),
        Rotation2d.fromDegrees(t.getYaw()),      // documented "with left being the positive direction"
        Rotation2d.fromDegrees(t.getPitch()),    // documented "with up being the positive direction"
        t.getArea(),
        t.getPoseAmbiguity(),
        best.map(b -> b.getTranslation().getNorm()).orElse(Double.NaN),
        best,
        alt));
  } else if (t.getDetectedObjectClassID() >= 0) {
    targets.add(TargetObservation.object(
        frameSequence,
        t.getDetectedObjectClassID(),            // int, -1 when not applicable
        t.getDetectedObjectConfidence(),         // float, -1 when not applicable
        Rotation2d.fromDegrees(t.getYaw()),
        Rotation2d.fromDegrees(t.getPitch()),
        t.getArea(),
        cornerYaws(t.getDetectedCorners()),      // List<TargetCorner>
        cornerPitches(t.getDetectedCorners())));
  }
}
```

`PhotonTrackedTarget`'s surface was re-verified 2026-08-08: `double getYaw()`, `double getPitch()`, `double getArea()`, `double getSkew()`, `int getFiducialId()`, `int getDetectedObjectClassID()`, `float getDetectedObjectConfidence()`, `double getPoseAmbiguity()`, `List<TargetCorner> getDetectedCorners()`, `List<TargetCorner> getMinAreaRectCorners()`, `Transform3d getBestCameraToTarget()`, `Transform3d getAlternateCameraToTarget()`. **PhotonVision's camera-to-target transforms are already in the WPILib camera frame** — no basis change, which is exactly the asymmetry §5.2a documents for Limelight.

> **PhotonVision 3D mode requires calibration at the exact streaming resolution.** Calibrating at 1280×800 and streaming at 640×400 yields no 3D output. `VisionDiagnostics` checks this explicitly (§11.3).

### 6.3 `CustomNTCameraIO` and the wire schema

```java
package org.rootstock.vision.custom;

public interface VisionWireSchema {
  /** Called once at construction to create subscribers. */
  void bind(NetworkTable table);
  /** Drain everything published since the last call, oldest first, already robot-frame. */
  List<VisionFrame> drain(Transform3d robotToCamera, AprilTagFieldLayout layout);
  String schemaName();

  static VisionWireSchema rootstockV1()      { return new RootstockV1Schema(); }
  static VisionWireSchema northstar2026()  { return new NorthstarSchema(); }
}

public final class CustomNTCameraIO implements VisionCameraIO {
  public CustomNTCameraIO(String tableName, VisionWireSchema schema, Transform3d robotToCamera);
  /** Drop-in for an existing 6328 Northstar / CubVision / GompeiVision coprocessor. */
  public static CustomNTCameraIO northstar(String deviceName, Transform3d robotToCamera);
  /** Pushes camera config DOWN over NT — exposure, gain, resolution, tag layout, throttle. */
  public CustomNTCameraIO withConfig(CoprocessorConfig config);
}
```

**`rootstockV1` NT layout.** Struct-serialized, versioned, self-describing — the thing nobody in FRC has built. WPILib 2026 has first-class `Struct<T>` on NetworkTables; we use it.

```
/rootstock_vision/<name>/schema        string   "rootstock-vision/1"
/rootstock_vision/<name>/frames        struct[]:RootstockVisionFrame     (sendAll, keepDuplicates, pollStorage 20, periodic 0.01)
/rootstock_vision/<name>/targets       struct[]:RootstockTargetObservation
/rootstock_vision/<name>/heartbeat     int
/rootstock_vision/<name>/fps           double
/rootstock_vision/<name>/health        struct:CoprocHealth   (cpuTempC, cpuPct, ramPct, uptimeS)
/rootstock_vision/<name>/layout_hash   string    <- coprocessor's loaded tag layout fingerprint. See section 10.4.
/rootstock_vision/<name>/config/*      published DOWN by robot code
```

Struct schemas (fixed-size — WPILib structs cannot be variable-length, so the target list lives on its own topic and links back by `frameSequence`). **These are the same two schemas the robot logs its own frames with**: `RootstockVisionFrame` ≡ `VisionFrameHeader` (§4.3) and `RootstockTargetObservation` ≡ `TargetObservation` (§4.2), field for field, in this order. One layout, one schema string, one place to get it right.

```
RootstockVisionFrame:      // == org.rootstock.vision.VisionFrameHeader
  int64  frameSequence;
  double captureTimestampSeconds;   // NT server time base, MIDDLE OF EXPOSURE
  uint8  hasPose;
  Pose3d robotPose;                 // field-relative, blue origin; zeros when hasPose == 0
  uint8  source;                    // PoseSource ordinal
  int32  tagCount;
  double averageTagDistanceMeters;
  double tagSpanMeters;
  double ambiguity;

RootstockTargetObservation:  // == org.rootstock.vision.TargetObservation
  int64  frameSequence;             // links to the frame above
  int32  fiducialId;
  int32  objectClassId;
  double confidence;
  double txRad;
  double tyRad;
  double areaPercent;
  double ambiguity;
  double distanceToCameraMeters;
  uint8  hasBestCameraToTarget;
  Transform3d bestCameraToTarget;   // WPILIB camera frame; the coprocessor converts, not us
  uint8  hasAltCameraToTarget;
  Transform3d altCameraToTarget;
  double cornerTxRad[4];
  double cornerTyRad[4];
```

**Northstar back-compat decoder** (from 6328's `Vision.java` + `vision_types.py`; positional):

```
observations double[]:
  [0] = poseCount (0, 1, or 2)
  poseCount == 1 (multi-tag):
      [1]=error0, [2..4]=x,y,z, [5..8]=quaternion(w,x,y,z), tag blocks from index 9
  poseCount == 2 (single tag, BOTH PnP solutions):
      [1]=error0, [2..8]=pose0, [9]=error1, [10..16]=pose1, tag blocks from index 17
  tag blocks: stride 10 = { tag_id, tx0,ty0, tx1,ty1, tx2,ty2, tx3,ty3, distance }
  Poses are CAMERA poses in field space:  robotPose = cameraPose.transformBy(robotToCamera.inverse())

objdetect_observations double[] (published as float[]):
  stride 10 per detection: { class_id, confidence, tx0,ty0, tx1,ty1, tx2,ty2, tx3,ty3 }
```

For `poseCount == 2` we adopt 6328's disambiguation verbatim, which is **strictly better than a plain ambiguity threshold** and is one of the highest-value single ideas in the dossier:

```java
// Accept the frame only if one solution is decisively better...
if (!(error0 < error1 * AMBIGUITY_RATIO || error1 < error0 * AMBIGUITY_RATIO)) {  // 0.4
  return reject(RejectReason.HIGH_AMBIGUITY);
}
// ...then choose the candidate whose ROTATION is closer to the gyro, not the one the solver ranked first.
Rotation2d gyro = ctx.gyroFieldHeading();
Pose3d chosen = Math.abs(pose0.toPose2d().getRotation().minus(gyro).getRadians())
              < Math.abs(pose1.toPose2d().getRotation().minus(gyro).getRadians()) ? pose0 : pose1;
```

`VisionFilters.disambiguateAgainstGyro(double ratio)` exposes the same logic to PhotonVision single-tag frames, using `bestCameraToTarget` / `altCameraToTarget`.

### 6.4 `ReplayCameraIO`

```java
public final class ReplayCameraIO implements VisionCameraIO {
  @Override public void updateInputs(VisionCameraIOInputs inputs) { /* intentionally empty */ }
}
```
All fields are overwritten by `RootstockLog.processInputs` in replay. This is the AdvantageKit `new VisionIO(){}` convention, named so it is discoverable.

---

## 7. The Filter Chain

This is the genuine gap in existing tools and it is where a student's understanding is either created or destroyed. AdvantageKit's template filter is:

```java
// verbatim from the AdvantageKit 2026 vision template — one boolean, no reason retained
observation.tagCount() == 0
  || (observation.tagCount() == 1 && observation.ambiguity() > maxAmbiguity)
  || Math.abs(observation.pose().getZ()) > maxZError
  || observation.pose().getX() < 0.0 || observation.pose().getX() > aprilTagLayout.getFieldLength()
  || observation.pose().getY() < 0.0 || observation.pose().getY() > aprilTagLayout.getFieldWidth()
```

When a small team's robot teleports at an event, that expression tells them nothing. Ours tells them *"camera 2 rejected 340 frames for GYRO_DISAGREEMENT in match 14."*

### 7.1 Types

```java
package org.rootstock.vision.filter;

public enum RejectReason {
  ACCEPTED,
  /** Frame carried no pose at all (object-detection-only frame). Not an error. */
  NO_POSE,
  NO_TAGS,
  /** A tag id in this frame is not in our AprilTagFieldLayout. Almost always a layout mismatch. */
  TAG_NOT_IN_LAYOUT,
  HIGH_AMBIGUITY,
  /** Pose is outside the field rectangle plus the configured margin. */
  OFF_FIELD,
  /** Robot is reported above or below the floor by more than the allowed band. */
  BAD_Z,
  /** Roll or pitch beyond physically plausible limits. */
  BAD_TILT,
  /** Vision heading disagrees with the gyro at capture time by more than the threshold. */
  GYRO_DISAGREEMENT,
  /** Pose is further from our current estimate at capture time than a robot could have moved. */
  POSE_JUMP,
  TOO_FAR,
  /** Gyro rate too high — motion blur and MegaTag2 assist both degrade. */
  HIGH_ANGULAR_RATE,
  STALE_TIMESTAMP,
  FUTURE_TIMESTAMP,
  /** Timestamp is older than the pose estimator's internal buffer; addVisionMeasurement would no-op. */
  ODOMETRY_BUFFER_MISS,
  /** Early autonomous: odometry is trustworthy, vision is at its worst. */
  AUTO_STARTUP_WINDOW,
  /** MegaTag2 selected but robot_orientation_set is not being written. */
  MEGATAG2_NO_ORIENTATION,
  MALFORMED_FRAME,
  DISABLED_SOURCE,
  /** A user-supplied filter rejected it. Carries a custom label in FilterResult. */
  CUSTOM;

  public boolean accepted() { return this == ACCEPTED; }
}

public record FilterResult(RejectReason reason, String detail) {
  public static final FilterResult ACCEPT = new FilterResult(RejectReason.ACCEPTED, "");
  public static FilterResult reject(RejectReason r, String fmt, Object... args) {
    return new FilterResult(r, String.format(fmt, args));
  }
  public boolean accepted() { return reason.accepted(); }
}

/** Everything a filter is allowed to look at. Immutable, rebuilt once per loop. */
public record VisionContext(
    AprilTagFieldLayout layout,
    Pose2d currentEstimate,
    /** Pose at the frame's capture time, from PoseProvider.sampleAt. Empty when out of buffer. */
    Optional<Pose2d> estimateAtCapture,
    /** PoseProvider.getGyroFieldHeading(): raw IMU yaw + the offset latched at the last pose reset,
     *  blue-origin, CCW-positive. Never a vision-influenced value — that is what makes
     *  disambiguateAgainstGyro and maxGyroDisagreement independent checks rather than tautologies. */
    Rotation2d gyroFieldHeading,
    double gyroRateRadPerSec,
    double nowSeconds,
    double odometryBufferSeconds,
    /** From MatchContext, never DriverStation (ArchUnit rule 10). */
    boolean autonomous,
    double secondsSinceAutonomousStart,
    boolean enabled) {}

@FunctionalInterface
public interface VisionFilter {
  FilterResult test(VisionFrame frame, VisionContext ctx);

  /** Short-circuits on the first rejection so the FIRST reason is the one reported. Order matters. */
  default VisionFilter and(VisionFilter next) {
    return (f, c) -> { FilterResult r = test(f, c); return r.accepted() ? next.test(f, c) : r; };
  }
  static VisionFilter of(VisionFilter... filters) {
    return (f, c) -> {
      for (VisionFilter v : filters) { FilterResult r = v.test(f, c); if (!r.accepted()) return r; }
      return FilterResult.ACCEPT;
    };
  }
}
```

### 7.2 The shipped filters

Every threshold is a `TuningRegistry.tunable("Vision", ...)` (§2.3) so it is adjustable at the field without a redeploy, and frozen at competition by `FmsPolicy.tunablesLocked()`.

```java
package org.rootstock.vision.filter;

public final class VisionFilters {

  /** Frame carried no pose. Returns NO_POSE (a non-error rejection; it is not counted as a failure). */
  public static VisionFilter requirePose();

  /** tagCount == 0. */
  public static VisionFilter requireTags();

  /** Every tagId in the frame must exist in the layout. This is the cheapest layout-mismatch canary. */
  public static VisionFilter tagsInLayout();

  /** Single-tag frames only: reject when ambiguity > max. AdvantageKit 0.3; Limelight MT1 docs 0.7;
   *  8793 VisionSubsystem 0.2. Rootstock default 0.3. Multi-tag and gyro-fused frames pass. */
  public static VisionFilter maxAmbiguitySingleTag(double max);

  /** 6328's approach, and better than a bare threshold: require one PnP solution to be decisively
   *  better AND pick the candidate closest in rotation to the gyro. Requires alt/best transforms. */
  public static VisionFilter disambiguateAgainstGyro(double ratio);           // 0.4

  /** Pose inside [ -margin, fieldLength+margin ] x [ -margin, fieldWidth+margin ]. 6328 use 0.5 m. */
  public static VisionFilter withinField(double marginMeters);

  /** 6328: zMin -0.5, zMax 1.0. AdvantageKit: |z| < 0.75. Rootstock default -0.30 .. 0.50. */
  public static VisionFilter zRange(double minZ, double maxZ);

  /** Roll/pitch sanity. A robot is never rolled 40 deg. Default 15 deg each. */
  public static VisionFilter tiltRange(Rotation2d maxRoll, Rotation2d maxPitch);

  /** averageTagDistanceMeters > max. 8793 use 6.0 m. */
  public static VisionFilter maxAverageTagDistance(double meters);

  /** |visionYaw - sampleAt(timestamp).yaw| > max. Skipped for gyro-fused sources (tautological). */
  public static VisionFilter maxGyroDisagreement(Rotation2d max);             // default 12 deg

  /** ||visionXY - sampleAt(timestamp).XY|| > max. Default 1.0 m. */
  public static VisionFilter maxPoseJump(double meters);

  /** Limelight's own MegaTag2 sample rejects when |gyro rate| > 360 deg/s. */
  public static VisionFilter maxAngularRate(double degPerSec);                // default 720

  /** timestamp > now + 0.05 -> FUTURE_TIMESTAMP; now - timestamp > maxAge -> STALE_TIMESTAMP. */
  public static VisionFilter timestampSane(double maxAgeSeconds);             // default 0.5

  /** now - timestamp >= odometryBufferSeconds -> ODOMETRY_BUFFER_MISS.
   *  This is the filter that converts addVisionMeasurement's silent no-op into a visible number. */
  public static VisionFilter withinOdometryBuffer();

  /** 6328 carry autoIgnoreTimeSecs = 2.0. Odometry is trustworthy in the first seconds of auto and
   *  vision is at its worst (motion blur, extreme tag angles); a bad correction wrecks the whole auto.
   *  DESIGN.md §5.6 closes the default: ON, automatically disabled when the selected auto declares
   *  resetOdom == false. */
  public static VisionFilter ignoreEarlyAuto(double seconds);                 // default 2.0, auto-gated

  /** MEGATAG_2 frames from a camera whose robot_orientation_set is not being written. */
  public static VisionFilter requireMegaTag2Orientation();

  /** Per-camera kill switch, wired to TuningRegistry.tunableFlag so a dead camera can be
   *  disabled in the pit without a redeploy. See §2.3 contract C8. */
  public static VisionFilter enabledWhen(BooleanSupplier enabled);

  /** Escape hatch. */
  public static VisionFilter custom(String label, BiPredicate<VisionFrame, VisionContext> accept);

  /**
   * The opinionated stack. Order is deliberate: cheap structural checks first (so the reported
   * reason is the most informative one), physics next, then statistics, then relational checks
   * that require an odometry sample.
   */
  public static VisionFilter standard() {
    return VisionFilter.of(
        requirePose(),
        requireTags(),
        requireMegaTag2Orientation(),
        tagsInLayout(),
        timestampSane(0.5),
        withinOdometryBuffer(),
        withinField(0.5),
        zRange(-0.30, 0.50),
        tiltRange(Rotation2d.fromDegrees(15), Rotation2d.fromDegrees(15)),
        maxAverageTagDistance(6.0),
        maxAmbiguitySingleTag(0.3),
        maxAngularRate(720.0),
        maxGyroDisagreement(Rotation2d.fromDegrees(12)),
        maxPoseJump(1.0),
        ignoreEarlyAuto(2.0));
  }

  /** Everything in standard() plus the aggressive options elite teams run. */
  public static VisionFilter strict() {
    return standard().and(disambiguateAgainstGyro(0.4));
  }
}
```

Two implementation notes that matter:

- `maxGyroDisagreement` and `maxPoseJump` need the pose **at capture time**, not now. They use `VisionContext.estimateAtCapture()`, which comes from `PoseProvider.sampleAt(frame.timestampSeconds())` — signature `Optional<Pose2d> sampleAt(double timestampSeconds)`. When the Optional is empty, `withinOdometryBuffer()` has already rejected the frame, so these filters never fire on missing data.
- `maxGyroDisagreement` **skips gyro-fused sources**. Comparing MegaTag2's yaw against the gyro is comparing the gyro against itself. Testing it anyway produces a filter that never fires and a student who believes it is protecting them.

**`ignoreEarlyAuto` moved from off to on**, which is a behavioral change and is recorded as such: `DESIGN.md` §5.6 closes the open question with *"Default on, automatically disabled when the selected auto declares `resetOdom == false`."* Revision 3 of this document still shipped it off and carried the question open. The gate is read from the Auto domain's selected routine; when no routine has been selected the filter is inert.

### 7.3 What gets logged

Per camera, per loop. The namespace is `Rootstock/Vision/<name>/`, which `design/04` §3.3 owns; `<name>` is `VisionCameraIO.name()`. Tier column is `design/04`'s three-tier model.

```
Rootstock/Vision/<name>/Frames               struct[]:VisionFrameHeader  CRITICAL  fixed-size headers, this loop
Rootstock/Vision/<name>/Targets              struct[]:TargetObservation  STANDARD  joined to Frames by frameSequence
Rootstock/Vision/<name>/TagIds               long[]     CRITICAL   convenience view for AdvantageScope
Rootstock/Vision/<name>/RobotPoses           Pose3d[]   STANDARD   every frame that carried a pose
Rootstock/Vision/<name>/RobotPosesAccepted   Pose3d[]   CRITICAL
Rootstock/Vision/<name>/RobotPosesRejected   Pose3d[]   STANDARD
Rootstock/Vision/<name>/TagPoses             Pose3d[]   DEBUG      layout poses of tags seen this loop (supplier-gated)
Rootstock/Vision/<name>/RejectReasons        String[]   CRITICAL   one per frame, parallel to RobotPoses
Rootstock/Vision/<name>/RejectDetail         String[]   STANDARD   e.g. "z=0.83 m outside [-0.30, 0.50]"
Rootstock/Vision/<name>/RejectCounts/<REASON> long      STANDARD   cumulative since boot
Rootstock/Vision/<name>/AcceptRate           double     STANDARD   rolling 5 s
Rootstock/Vision/<name>/CoalescedCount       long       STANDARD   accepted frames merged away by maxAcceptedPerLoop (9.6)
Rootstock/Vision/<name>/DecodeDroppedCount   long       STANDARD   frames left undecoded by maxFramesPerLoop (9.6)
Rootstock/Vision/<name>/StdDevs              double[3]  STANDARD (Demotable)  sigma actually handed to the estimator
Rootstock/Vision/<name>/Fps                  double     CRITICAL
Rootstock/Vision/<name>/LatencySec           double     CRITICAL   now - frame.timestampSeconds, per accepted frame
Rootstock/Vision/<name>/NetworkTransitEstimateSecs double STANDARD  §5.2 caveat 1
Rootstock/Vision/<name>/PipelineSettleSecs   double     STANDARD   measured, not assumed (§6.1)
Rootstock/Vision/<name>/Connected            boolean    CRITICAL
Rootstock/Vision/<name>/RobotToCamera        Transform3d STANDARD  logged on change
Rootstock/Vision/GyroFieldHeadingDeg         double     CRITICAL   PoseProvider.getGyroFieldHeading()
Rootstock/Vision/FusedHeadingDeg             double     CRITICAL   PoseProvider.getPose().getRotation()
Rootstock/Vision/AcceptedThisCycle           long       CRITICAL
Rootstock/Vision/UptimeFraction              double     STANDARD
Rootstock/Vision/AnyCameraOffline            boolean    CRITICAL
Rootstock/Vision/Summary/CoalescedCount      long       STANDARD
Rootstock/Vision/Summary/RejectCounts/<REASON> long     STANDARD
Rootstock/Vision/Summary/DominantRejectReason String    CRITICAL   most common non-ACCEPTED reason in the last 5 s
Rootstock/Vision/Arbiter/SelectedCameras     long[]     STANDARD   which cameras relocalized this loop (13.6)
Rootstock/Vision/Arbiter/Scores              double[]   STANDARD   per-camera geometry score (13.6)
Rootstock/Vision/Arbiter/SuppressedCount     long       STANDARD
Rootstock/Vision/Seed/Count                  long       CRITICAL   disabled multi-tag pose seeds issued (9.7)
Rootstock/Vision/Seed/LastSeedPose           Pose2d     CRITICAL
Rootstock/Vision/Seed/LastSeedSource         String     CRITICAL
Rootstock/Vision/Seed/RejectedNoAgree        long       STANDARD
Rootstock/Vision/Align/TagId                 long       CRITICAL   active alignToTag lock (13.5)
Rootstock/Vision/Align/ErrorMeters           double     CRITICAL   TAG-RELATIVE error, not fused-pose error
Rootstock/Vision/Align/ErrorDegrees          double     CRITICAL
Rootstock/Vision/Align/CommandedRobotSpeeds  ChassisSpeeds CRITICAL  what we actually sent (§13.4.1)
Rootstock/Vision/Align/ProfileVelocity       double     STANDARD   ProfiledPIDController.getSetpoint().velocity
Rootstock/Vision/Align/ObservationAgeSecs    double     STANDARD
Rootstock/Vision/Align/NoSolveLoops          long       STANDARD
Rootstock/Perf/Vision/ConsumeMs              double     STANDARD   RootstockTracer budget, 2.0 ms (9.6)
```

`Rootstock/Vision/GyroFieldHeadingDeg` and `Rootstock/Vision/FusedHeadingDeg` are logged side by side deliberately: if a team miswires `getGyroFieldHeading()` to the pose estimator, the two traces are identical in AdvantageScope and the MegaTag2 feedback loop is visible in one glance instead of being invisible forever.

**Honest cost of the namespace change.** Revision 3 named these keys `Vision/Camera<i>/...`, *exactly* as the AdvantageKit template does, and claimed existing AdvantageScope layouts would port unchanged. Under binding **D9/D22** the telemetry domain owns the schema and it is `Rootstock/**`, so that claim is now weaker and is restated accurately: **porting an AdvantageKit vision layout is a prefix edit** (`Vision/Camera0/` → `Rootstock/Vision/front-left/`), not a no-op. The leaf names are still identical, which is the part that actually saves work, and `Rootstock/Vision/CameraIndex/<name>` (long) is published once at boot so the index↔name mapping is answerable from the log.

`Rootstock/Vision/<name>/RobotPoses*` and `Rootstock/Vision/Summary` keep the AdvantageKit leaf names for that reason.

The driver-dashboard widget (owned by the telemetry domain, fed by us) shows a per-camera reject-reason histogram. `RootstockVision.rejectCounts(int cameraIndex)` returns `Map<RejectReason, Long>` for anyone who wants it in code.

---

## 8. Standard Deviation Model

### 8.0 Two rules that come before any model

Both of these were review findings, and both are the kind of bug that quietly ends a season.

> **Rule 1: `Double.POSITIVE_INFINITY` never appears in a standard deviation. Anywhere.**
>
> The earlier draft set `sigmaTheta = Double.POSITIVE_INFINITY` for gyro-fused sources and *then* multiplied it by a per-camera tunable factor. A student setting that factor to `0` in the tuning dashboard — the obvious way to "turn this camera off" — produces `Infinity * 0.0 = NaN`. WPILib's `PoseEstimator.setVisionMeasurementStdDevs` then computes `r = NaN*NaN` and `visionK = q / (q + sqrt(q * NaN))` = `NaN`, and **every subsequent `addVisionMeasurement` writes NaN into the pose**. The estimator never recovers without a `resetPosition`. The robot's pose is `NaN` for the rest of the match, every downstream controller is `NaN`, and nothing in the log says why.
>
> ```java
> public final class StdDevModels {
>   /**
>    * The sigma we use to mean "do not trust this axis at all". 1e6 m / 1e6 rad is numerically
>    * inert in WPILib's Kalman update (visionK -> ~1e-12) but is finite, so it survives
>    * multiplication, division, clamping and serialization. Limelight's own MegaTag2 documentation
>    * uses 9999999 for exactly the same purpose; we use 1e6 for the same reason and less typing.
>    */
>   public static final double UNTRUSTED_SIGMA = 1.0e6;
>   /** Floor. Below this the estimator effectively teleports to vision and rejects odometry. */
>   public static final double MIN_SIGMA = 1.0e-4;
> }
> ```
>
> Every place the old text said `POSITIVE_INFINITY` now says `StdDevModels.UNTRUSTED_SIGMA`, including the §9.4 structural override and `withAngularDisabled()`. **`design/05` must adopt the same constant** in place of its raw `9_999_999` literal — §2.7 contract C7.

> **Rule 2: the per-camera factor is applied BEFORE the untrusted-theta override, never after.**
>
> Ordering is not cosmetic. Scaling first and pinning second means the pin is the last word and a factor of `0`, `1e9`, or `NaN` cannot corrupt it. Scaling second means the tunable multiplies a sentinel. The `bySource` / `scaledBy` / `withLatencyPenalty` decorators are therefore all applied **inside** `compute`, and the override is applied **outside**, in `RootstockVision`, where no user code can reorder it.

### 8.1 Interface

The model writes into a caller-owned matrix. It does not allocate.

```java
package org.rootstock.vision.stddev;

@FunctionalInterface
public interface StdDevModel {
  /**
   * Writes [sigmaX, sigmaY, sigmaTheta] (meters, meters, radians) into `out`.
   *
   * <p>OUT-PARAMETER, not a return value, and this is deliberate on two counts. (1) The zero-
   * allocation CI gate: at up to `maxFramesPerLoop` frames per camera per loop, returning a fresh
   * `Matrix<N3,N1>` allocates on the hot path every frame. `RootstockVision` hands each camera a
   * preallocated `Matrix<N3,N1>` and reuses it. (2) Aliasing safety: the previous
   * `Matrix<N3,N1> compute(...)` shape let `withAngularDisabled()` mutate whatever the wrapped
   * model returned — including a cached or shared matrix — which corrupts state for every other
   * caller. A model cannot corrupt shared state it was handed.
   *
   * <p>Implementations MUST write all three elements and MUST NOT retain a reference to `out`.
   */
  void compute(VisionFrame frame, VisionContext ctx, Matrix<N3, N1> out);

  /** Per-camera trust scaling (AdvantageKit's cameraStdDevFactors, 6328's cameraFactor). */
  default StdDevModel scaledBy(DoubleSupplier factor) {
    return (f, c, out) -> {
      compute(f, c, out);
      double k = factor.getAsDouble();
      for (int i = 0; i < 3; i++) out.set(i, 0, out.get(i, 0) * k);
    };
  }

  /**
   * Pin sigmaTheta to StdDevModels.UNTRUSTED_SIGMA. Applied automatically for every gyro-fused
   * source, and applied LAST so no scaling can touch it. Writes into `out`; mutates nothing else.
   */
  default StdDevModel withAngularDisabled() {
    return (f, c, out) -> {
      compute(f, c, out);
      out.set(2, 0, StdDevModels.UNTRUSTED_SIGMA);
    };
  }

  /** Inflate linearly with frame age; guards against a camera that is quietly falling behind. */
  default StdDevModel withLatencyPenalty(double perSecond) {
    return (f, c, out) -> {
      compute(f, c, out);
      double k = 1.0 + perSecond * Math.max(0.0, c.nowSeconds() - f.timestampSeconds());
      for (int i = 0; i < 3; i++) out.set(i, 0, out.get(i, 0) * k);
    };
  }
}
```

### 8.2 The default — `StdDevModels.rootstockDefault()`

Note the order: **scale, then pin.** Never the reverse.

```
d = frame.averageTagDistanceMeters()
n = max(1, frame.tagCount())

base       = d² / n
sigmaXY    = kXY    * base          kXY    = 0.02    (meters at 1 m, 1 tag)
sigmaTheta = kTheta * base          kTheta = 0.06    (radians at 1 m, 1 tag)

if (frame.source().isGyroFused()):          // MEGATAG_2, PNP_DISTANCE_TRIG, CONSTRAINED_SOLVEPNP
    sigmaXY    *= 0.5

# --- per-camera trust factor, applied to FINITE numbers only ---
sigmaXY    *= cameraFactor[i]
sigmaTheta *= cameraFactor[i]

# --- structural override, applied LAST, outside any user-supplied decorator ---
if (frame.source().isGyroFused() and not allowVisionHeading):
    sigmaTheta = StdDevModels.UNTRUSTED_SIGMA
```

**Worked numbers, because §13.2 depends on them and a reader must be able to check the claim.** Two tags on MegaTag2 at 3 m:

```
d = 3.0 m, n = 2
base       = 3.0² / 2 = 9.0 / 2 = 4.5
sigmaXY    = 0.02 * 4.5          = 0.090 m
gyro-fused -> sigmaXY *= 0.5     = 0.045 m   = 4.5 cm
sigmaTheta = UNTRUSTED_SIGMA (pinned; MegaTag2 rotation is an input, never an output)
```

**4.5 cm** is the number §13.2 and §13.3 cite as the floor a fused-pose controller cannot hold below. One tag at 3 m is `0.02 * 9.0 * 0.5 = 0.090 m` — 9 cm — which is why single-tag scoring alignment is not a plan.

This is the AdvantageKit 2026 template's model, with the infinity replaced by a finite sentinel and the multiply moved ahead of the pin. `cameraFactor` defaults to 1.0 and is a per-camera `TuningRegistry.tunable("Vision", "<name>/cameraFactor", 1.0, "")`. Setting it to `0` now means "trust this camera absolutely", which is wrong but *recoverable and visible* — the final guard in §8.3 clamps it to `MIN_SIGMA` and logs the clamp. Setting it to `0` under the old design meant `NaN` forever.

### 8.3 The final guard — the last thing before the estimator sees a number

No matter what model, decorator, tunable, or user escape hatch produced the matrix, the `VisionConsumer` wrapper inside `RootstockVision` checks it. This is the single choke point through which every vision measurement passes.

```java
// RootstockVision, wrapping the team's VisionConsumer. Runs for EVERY accepted frame.
private boolean sanitizeStdDevs(Matrix<N3, N1> sd, int cameraIndex) {
  for (int i = 0; i < 3; i++) {
    double s = sd.get(i, 0);
    if (!Double.isFinite(s) || s <= 0.0) {
      reject(cameraIndex, RejectReason.CUSTOM,
             "std-dev[%d] = %s is not a usable sigma", i, s);
      return false;                     // frame dropped; the estimator is never handed NaN
    }
    sd.set(i, 0, MathUtil.clamp(s, StdDevModels.MIN_SIGMA, StdDevModels.UNTRUSTED_SIGMA));
  }
  return true;
}
```

Three properties worth stating explicitly:

1. **`NaN` and `Infinity` cannot reach `addVisionMeasurement`.** They become a named, counted rejection with the offending value printed, which is exactly the "zero-mystery debugging" contract the rest of this document is built on.
2. **The clamp is silent-but-logged, not silent.** `Rootstock/Vision/<name>/StdDevs` logs the post-clamp vector every accepted frame, so a team that has tuned `cameraFactor` into a corner can see it.
3. **This runs after every escape hatch**, including `allowVisionHeadingFromGyroFusedSources(true)`. There is no supported path around it. `StdDevNaNGuardTest` asserts that a deliberately hostile model returning `{NaN, 0.0, Infinity}` produces a rejection and leaves the estimator's pose finite.

### 8.4 The other shipped presets

```java
public final class StdDevModels {

  public static final double UNTRUSTED_SIGMA = 1.0e6;   // section 8.0 Rule 1
  public static final double MIN_SIGMA       = 1.0e-4;

  /** AdvantageKit 2026 template. d²/n, linear 0.02, angular 0.06; MT2 gets 0.5x linear, and the
   *  angular term is pinned to UNTRUSTED_SIGMA by the structural override in section 9.4. */
  public static StdDevModel advantageKit(double linearBaseline, double angularBaseline);

  /** Rootstock default == advantageKit(0.02, 0.06). */
  public static StdDevModel rootstockDefault() { return advantageKit(0.02, 0.06); }

  /**
   * FRC 6328 "Darwin" 2026. Note n SQUARED — two tags are 4x more trusted, not 2x.
   *   sigmaXY    = xyCoeff    * d² / n²
   *   sigmaTheta = thetaCoeff * d² / n²   (or UNTRUSTED_SIGMA when rotation is untrusted)
   * Defaults xyCoeff 0.01, thetaCoeff 0.03. Noticeably more aggressive than advantageKit.
   */
  public static StdDevModel littletonInverseSquareTagCount(double xyCoeff, double thetaCoeff);

  /** Limelight's own documented MegaTag2 baseline: VecBuilder.fill(0.7, 0.7, 9999999). Constant.
   *  Note that Limelight's own docs already use a large finite sentinel, not infinity — the same
   *  conclusion section 8.0 Rule 1 reaches independently. */
  public static StdDevModel limelightMegaTag2Default();

  /** Uses the `stddevs` NT array the Limelight itself publishes (12 elements, §5.2).
   *  Limelight sources only, and see §21 OQ 9 before making it a default. */
  public static StdDevModel limelightReported();

  /** Standard deviations that grow with the square of tag distance: base * (1.0 + d² / 30.0),
   *  base from a supplied Matrix. Named for the shape of the curve rather than for a team,
   *  because a team migrating from any codebase that shipped this formula wants it unchanged.
   *  One such codebase is the maintainer's own (VisionSubsystem.java:683), which is where the
   *  30.0 came from. */
  public static StdDevModel quadraticGrowth(Matrix<N3,N1> base);

  /** Per-source override table; anything unmapped falls through to `fallback`. */
  public static StdDevModel bySource(Map<PoseSource, StdDevModel> table, StdDevModel fallback);
}
```

**The rule we enforce structurally, not by documentation:** `RootstockVision` wraps whatever model the team supplies so that any `frame.source().isGyroFused()` frame gets `sigmaTheta = StdDevModels.UNTRUSTED_SIGMA`, applied **after** every user-supplied decorator and per-camera factor, regardless of what the model returned. A user cannot accidentally close the MegaTag2 heading feedback loop, and cannot accidentally turn the sentinel into `NaN` by scaling it. There is one opt-out, `RootstockVision.Builder.allowVisionHeadingFromGyroFusedSources(true)`, which logs a warning on every construction and exists only so the escape hatch is real — and even that path still passes through `sanitizeStdDevs` (§8.3).

---

## 9. Pose Estimator Integration

### 9.1 The front door

`RootstockVision` is a `VirtualSubsystem`-style periodic object (not a `SubsystemBase`, so it never participates in command requirements — the template repo is state-based and would reject a mandatory `Subsystem`). It registers with `RootstockRegistry.addAll(...)` like every other component (D27).

```java
package org.rootstock.vision;

public final class RootstockVision {

  public static Builder builder() { return new Builder(); }

  public static final class Builder {
    public Builder layout(AprilTagFieldLayout layout);
    public Builder poseProvider(PoseProvider provider);
    public Builder camera(VisionCameraIO io);
    public Builder camera(VisionCameraIO io, DoubleSupplier stdDevFactor);
    public Builder filter(VisionFilter filter);
    public Builder filterFor(int cameraIndex, VisionFilter filter);
    public Builder stdDevs(StdDevModel model);
    public Builder stdDevsFor(int cameraIndex, StdDevModel model);
    public Builder consumer(VisionConsumer consumer);
    /** Wraps `consumer` with CTRE Utils.fpgaToCurrentTime(). See 9.2. */
    public Builder consumerIsPhoenixSwerve(boolean isPhoenix);
    public Builder odometryBufferSeconds(double seconds);          // default 1.5 (WPILib's default)
    /** Frames DECODED per camera per loop. Default 4. See 9.6. */
    public Builder maxFramesPerLoop(int max);
    /** Accepted MEASUREMENTS handed to the estimator per camera per loop. Default 2. See 9.6. */
    public Builder maxAcceptedPerLoop(int max);
    /** Cross-camera relocalization policy. Default CameraArbiter.all() (feed everything). See 13.6. */
    public Builder arbiter(CameraArbiter arbiter);
    /**
     * The disabled MegaTag1/multi-tag pose seed (9.7). This is the ONLY vision path allowed to
     * write RootstockDrive's gyro->field offset (2.2), so it is a separate, explicitly-named hook
     * that takes `resetPose`, not `addVisionMeasurement`. Pass `m_drive::resetPose`.
     */
    public Builder disabledSeedConsumer(Consumer<Pose2d> resetPose);
    public Builder seedWhileDisabled(boolean enabled);              // default false; requires the hook above
    public Builder simEnabled(boolean enabled);
    public Builder logKey(String key);                             // default "Rootstock/Vision"
    public Builder allowVisionHeadingFromGyroFusedSources(boolean allow);  // default false
    public RootstockVision build();
  }

  /** Call from robotPeriodic (or the state machine's periodic). Order-sensitive: see 9.4. */
  public void periodic();

  // ---- queries ----------------------------------------------------------------------------
  public int cameraCount();
  public String cameraName(int index);
  public boolean isConnected(int index);
  public boolean isFresh(int index, double maxAgeSeconds);
  public boolean hasRecentFix(double maxAgeSeconds);
  /** Accepted measurements handed to the estimator, across ALL cameras, in the last `windowSeconds`.
   *  This is what makes VisionFreshness.minAcceptedFramesInWindow implementable (§13.3). */
  public int acceptedFramesInWindow(double windowSeconds);
  public double secondsSincePose(int index);
  public Map<RejectReason, Long> rejectCounts(int index);
  public Optional<VisionFrame> latestAcceptedFrame(int index);
  public Optional<TargetObservation> bestTag(int index);
  public Optional<TargetObservation> tag(int index, int fiducialId);
  /** This camera's mount transform, straight from VisionCameraIO.robotToCamera(). alignToTag
   *  (13.5) needs it to compose bestCameraToTarget into the robot frame. */
  public Optional<Transform3d> robotToCamera(int index);
  /** Capture timestamp of the frame `tag(index, id)` came from — alignToTag's staleness gate. */
  public double lastTagTimestamp(int index, int fiducialId);
  public List<DetectedObject> objects(int classId);
  public Optional<DetectedObject> bestObject(int classId);

  // ---- control ----------------------------------------------------------------------------
  public Command setPipeline(int cameraIndex, int pipeline, double timeoutSeconds);
  public void setTagIdFilter(int cameraIndex, int... ids);
  /** Returns the filter that was in effect, so alignToTag can restore it on end(). */
  public int[] getTagIdFilter(int cameraIndex);
  public void setEnabled(int cameraIndex, boolean enabled);
  public void setThrottle(int cameraIndex, int skipFrames);
  public void setGlobalThrottle(int skipFrames);
  public void setRecording(boolean active);   // all cameras; auto-driven by FMS attach, see 11.4

  // ---- escape hatches ---------------------------------------------------------------------
  public VisionCameraIO io(int index);
  public AprilTagFieldLayout layout();
}
```

### 9.2 Feeding the estimator, and the Phoenix time-base trap

The consumer is exactly `SwerveDrivePoseEstimator::addVisionMeasurement` or `DifferentialDrivePoseEstimator::addVisionMeasurement` — the signature `(Pose2d, double, Matrix<N3,N1>)` matches both, and matches AdvantageKit's `VisionConsumer` byte for byte. This *is* binding **D17**'s signature; see §2.2a item 4.

```java
// The one line a team writes:
.consumer(m_drive.getPoseEstimator()::addVisionMeasurement)
```

**CTRE trap.** A Phoenix 6 `SwerveDrivetrain` runs its own time base. 8793's `CommandSwerveDrivetrain.java:314-350` overrides all three `addVisionMeasurement`/`samplePoseAt` entry points solely to wrap timestamps in `Utils.fpgaToCurrentTime(...)`. Any library vision path that skips this silently corrupts the Kalman fusion in proportion to robot speed.

```java
// RootstockVision.Builder.consumerIsPhoenixSwerve(true) installs this wrapper:
private static VisionConsumer phoenixWrapped(VisionConsumer inner) {
  return (pose, fpgaTimestamp, stdDevs) ->
      inner.accept(pose, com.ctre.phoenix6.Utils.fpgaToCurrentTime(fpgaTimestamp), stdDevs);
}
```

We detect the common case automatically: if `Class.forName("com.ctre.phoenix6.swerve.SwerveDrivetrain")` resolves **and** the consumer's declaring class is assignable to it, we install the wrapper and log `Rootstock/Vision/PhoenixTimeConversion = true`. If detection is ambiguous we do nothing and raise an info-level Finding telling the team to set the flag explicitly. Never guess silently in a way that changes numbers.

### 9.3 The alliance-flip trap

This is where teams lose a whole event, so it gets a rule, not a paragraph.

> **Rootstock never flips a measured pose, and never mutates an `AprilTagFieldLayout`.**

Specifics:

1. **Always blue origin.** We read `botpose_orb_wpiblue` / `botpose_wpiblue`, never `_wpired`. PhotonVision returns poses in the layout's origin, and we keep the layout at blue origin. The drivetrain's pose is blue-origin at all times, in both alliances, in auto and teleop. Red-alliance drivers see a rotated field on the dashboard; that is correct and is how PathPlanner, Choreo, AdvantageScope and every elite team work.
2. **`AprilTagFieldLayout.setOrigin()` is forbidden.** It mutates a shared object — if two subsystems hold the same instance and one flips it for red, the other silently sees flipped tag poses. `RootstockVision.periodic()` asserts `layout.getOrigin().equals(Pose3d.kZero)` once per second and raises

   ```java
   Alerts.error("Vision",
       "AprilTagFieldLayout.setOrigin() was called on the shared layout. Every tag pose in this "
     + "robot is now mirrored for one of the two consumers. Rootstock never flips a layout; "
     + "flip TARGETS with AllianceFlip instead (design/03 9.3).",
       MatchImpact.BLOCKS_MATCH).set(true);
   ```

   `FieldLayouts` also hands out a defensive copy to any caller that asks for a mutable one.
3. **Flipping happens exactly once, for targets, in the alignment commands.** `VisionCommands.alignToNearest(...)` takes a *blue-authored* list of poses and calls `AllianceFlip.apply(...)` on each when `AllianceFlip.shouldFlip()`. That mirrors 4738's "author blue, derive red" pattern and PathPlanner's `FlippingUtil`, so robot code flips exactly the way the paths do.
4. **Alliance is latched at enable, not polled.** 8793 polls `DriverStation.getAlliance()` every 20 ms in four separate commands. `MatchContext` latches alliance on the DS-connect rising edge (`design/06` §10) and `AllianceFlip` reads `MatchContext.isRed()` / `MatchContext.allianceKnown()`. **Vision never names `DriverStation`** — ArchUnit rule 10.

### 9.4 Loop order

`RootstockVision.periodic()` does the following, in this order, every loop:

```
1. Build VisionContext once (currentEstimate, gyro, gyroRate, now, autonomous, enabled)
   — autonomous/enabled from MatchContext, now from RootstockLog.timestamp().
2. For each camera: io.setRobotOrientation(getGyroFieldHeading(), gyroRate)  -> writes + flushes NT.
3. For each camera: io.updateInputs(inputs);
      RootstockLog.processInputs("Rootstock/Vision/" + io.name(), inputs).
      inputs.frames is already capped at maxFramesPerLoop (default 4) BY THE IO — see 9.6.
4. For each camera, for each frame (oldest first):
      a. ctx.estimateAtCapture = poseProvider.sampleAt(frame.timestampSeconds())
      b. FilterResult r = filter.test(frame, ctx)
      c. log the frame into RobotPoses / Accepted / Rejected / RejectReasons / RejectDetail
      d. if accepted: append to this camera's `accepted` list (do NOT call the consumer yet)
      e. objectTracker.ingest(frame, ctx)
5. COALESCE (9.6): if accepted.size() > maxAcceptedPerLoop (default 2), keep the OLDEST and the
   (maxAcceptedPerLoop - 1) NEWEST; the remainder are logged to CoalescedCount with
   RejectReason.CUSTOM detail "coalesced: N accepted frames this loop exceeded maxAcceptedPerLoop".
6. ARBITRATE (13.6): arbiter.select(accepted, ctx) may drop whole cameras this loop.
7. try (var s = RootstockTracer.scope("Vision/Consume")) {
     For each surviving frame, OLDEST FIRST:
        a. stdDevModel.compute(frame, ctx, m_sd[i])            // out-parameter; m_sd[i] is preallocated
        b. if (frame.source().isGyroFused() && !allowVisionHeading)
               m_sd[i].set(2, 0, StdDevModels.UNTRUSTED_SIGMA)  // finite sentinel; NEVER Infinity (8.0)
        c. if (!sanitizeStdDevs(m_sd[i], i)) continue;          // 8.3 — NaN/Inf/<=0 becomes a rejection
        d. consumer.accept(frame.pose2d(), frame.timestampSeconds(), m_sd[i])
        e. tagResidualMonitor.record(frame, ctx)
   }
8. While DISABLED and seedWhileDisabled: run the multi-tag pose seed (9.7).
9. Update summary counters, freshness, alerts, and the diagnostics cache.
```

Why each ordering constraint exists:

- **Step 2 before step 3** is mandatory (§5.2): the camera must have this loop's yaw before we read its solve.
- **Step 4a before 4b** is mandatory: the relational filters need the capture-time odometry sample.
- **Step 5 before step 7** is the loop-time fix from §9.6. Filtering is cheap and per-frame; `addVisionMeasurement` is expensive and superlinear in the number of calls. We therefore filter *everything* — the "drain every frame" principle is preserved and the diagnostics still see every frame — and only then decide how many measurements the estimator is actually handed.
- **Step 7b after 7a and outside the model** is what makes the MegaTag2 heading rule unbypassable. The per-camera factor and every user decorator ran inside `compute` (§8.2); the pin is applied afterwards, where no user code can reorder it, and it is a *finite* sentinel so a `cameraFactor` of `0` cannot turn it into `NaN`.
- **Step 7c after everything** is the choke point of §8.3. Nothing reaches `addVisionMeasurement` without passing it.
- **Step 7 feeds oldest-first.** WPILib replays its odometry buffer forward from each measurement's timestamp; feeding in increasing timestamp order means each successive replay starts later and does strictly less work than an out-of-order feed would.

`m_sd` is a `Matrix<N3,N1>[]`, one per camera, allocated once in `build()`. Nothing on this path allocates, which is what the zero-allocation CI gate checks.

### 9.5 Differential drive

`DifferentialDrivePoseEstimator.addVisionMeasurement` has the identical signature, so nothing changes. The only difference is the `PoseProvider` implementation. We ship `PoseProviders.fromDifferential(DifferentialDrivePoseEstimator, Gyro)` and `PoseProviders.fromSwerve(SwerveDrivePoseEstimator, Gyro)` as 10-line adapters so a team using raw WPILib does not have to write one. **Both adapters must implement `gyroFieldOffsetSeeded()`** (§2.2), which for a hand-rolled provider means tracking whether `resetPose` has ever been called.

### 9.6 The frame budget — why "drain every frame" needs two caps, not one

This is the section that corrects the most dangerous number in the earlier draft.

**What the earlier draft said.** `maxFramesPerLoop` defaulted to **20 per camera**, and every accepted frame called `consumer.accept(...)` → `addVisionMeasurement`. That was presented as a virtue: we drain every frame instead of using one frame per camera the way 8793's `VisionSubsystem.java` does.

**Why that is a loop-time bomb.** WPILib's `SwerveDrivePoseEstimator.addVisionMeasurement` is not a cheap accumulate. It replays the odometry buffer forward from the measurement's timestamp:

```java
// edu.wpi.first.math.estimator.PoseEstimator.addVisionMeasurement, structurally:
var tail = m_odometryPoseBuffer.getInternalBuffer().tailMap(timestampSeconds);
// ... then, for every entry in that tail:
updateWithTime(entryTimestamp, entryGyroAngle, entryWheelPositions);
```

Each replayed entry runs 4-module inverse kinematics plus a `Pose2d.exp`. Now put real numbers on it. A Phoenix 6 `SwerveDrivetrain` runs its odometry thread at **250 Hz**, and typical vision latency is **~80 ms**, so the tail from any given measurement holds roughly `250 × 0.080 = 20` entries.

| | frames/camera/loop | cameras | `addVisionMeasurement` calls | `updateWithTime` invocations |
|---|---:|---:|---:|---:|
| Old default, after a hiccup | 20 | 2 | 20 × 2 = 40 | 40 × 20 = **800** |
| New default, after a hiccup | 2 (accepted cap) | 2 | 2 × 2 = 4 | 4 × 20 = 80 |
| Steady state, 120 fps LL4 @ 50 Hz | 2.4 | 2 | ~4 (capped) | ~80 |

**The 20-frame path is only ever reached after the loop has already overrun** — that is the only way 20 frames queue up between two iterations. So the old design responded to being behind by doing forty times the work, which puts it further behind, which queues more frames. That is a positive feedback loop triggered by exactly the principle the design was proudest of.

**The fix: cap accepted measurements, not raw frames, and keep both caps.**

```java
.maxFramesPerLoop(4)      // DECODE cap, per camera. Default 4.
.maxAcceptedPerLoop(2)    // MEASUREMENT cap, per camera. Default 2.
```

1. **`maxFramesPerLoop` (default 4) is a decode cap, enforced inside each `VisionCameraIO`.** A 120 fps LL4 on a 50 Hz loop produces `120 / 50 = 2.4` frames/camera/loop in steady state, so 4 is steady state plus `4 / 2.4 − 1 = 67 %` headroom. Frames beyond the cap are **still drained from the NT queue** — we must drain or `pollStorage(20)` overflows and NT starts dropping samples of its own choosing — they are simply not decoded into a `VisionFrame`. The count goes to `Rootstock/Vision/<name>/DecodeDroppedCount`.

   ```java
   // LimelightCameraIO.updateInputs — drain everything, decode at most m_maxFramesPerLoop.
   TimestampedDoubleArray[] queue = m_mt2Sub.readQueue();     // ALWAYS drains fully
   int start = Math.max(0, queue.length - m_maxFramesPerLoop); // keep the NEWEST when over budget
   m_decodeDropped += start;
   for (int i = start; i < queue.length; i++) { decode(queue[i]); }
   ```

2. **`maxAcceptedPerLoop` (default 2) is a measurement cap, enforced in `RootstockVision` after filtering.** Every decoded frame is filtered, logged, counted, and shown in the diagnostics — the zero-mystery contract is untouched. Only the handoff to the estimator is rationed. When more than the cap survive filtering in one loop from one camera, we **keep the oldest and the newest**, and coalesce the middle:

   ```java
   // RootstockVision.periodic() step 5.
   if (accepted.size() > m_maxAcceptedPerLoop) {
     List<VisionFrame> keep = new ArrayList<>(m_maxAcceptedPerLoop);
     keep.add(accepted.get(0));                                   // OLDEST
     for (int k = accepted.size() - (m_maxAcceptedPerLoop - 1); k < accepted.size(); k++) {
       keep.add(accepted.get(k));                                 // the newest (cap - 1)
     }
     int coalesced = accepted.size() - keep.size();
     m_coalescedCount[i] += coalesced;
     logReject(i, RejectReason.CUSTOM,
         "coalesced: %d accepted frames this loop exceeded maxAcceptedPerLoop=%d",
         coalesced, m_maxAcceptedPerLoop);
     accepted = keep;
   }
   ```

   **Why oldest *and* newest, rather than just the newest.** They carry different information and dropping either one loses something real. The **oldest** has the longest odometry tail, so it corrects the most accumulated drift — it is the measurement that actually moves the estimate. The **newest** is the tightest fix on where the robot is right now, which is what an alignment command reads two milliseconds later. Keeping only the newest is what 8793's `results.get(results.size()-1)` does today, and it is why that code's pose snaps rather than converges.

3. **A declared budget, so the failure names itself.** `RootstockTracer.budget("Vision/Consume", Milliseconds.of(2.0))` (§2.1) wraps step 7 of §9.4. Blowing 2 ms on vision consumption in a 20 ms loop is a warning with the frame counts printed, not a mystery overrun in `robotPeriodic`.

**Both caps are `Builder` knobs and neither is secretly clamped.** A team that has measured their own loop and wants `maxAcceptedPerLoop(6)` gets it. The defaults are chosen to be right for the 95 % case, and `Rootstock/Perf/Vision/ConsumeMs` is logged so raising them is an informed decision instead of a guess.

**Reviewer pushback, recorded because it is a fair objection:** capping accepted measurements does mean we discard information the cameras genuinely produced, which sits uneasily beside §4.4's "every IO drains every frame."

**Why we're doing it anyway:** the two claims are compatible once you separate *observation* from *fusion*. We still drain, decode (up to 4), filter, log, and count every frame — every diagnostic, every reject reason, every residual, every object detection sees the full stream, which is the part of "drain every frame" that has evidence behind it. What we ration is the one operation whose cost is superlinear and whose marginal value collapses after the second measurement in a 20 ms window: two measurements 8 ms apart from the same camera see essentially the same tags from essentially the same place. We are trading a redundant Kalman update for the loop time that a fifth camera, or the alignment controller, actually needs.

### 9.7 The disabled multi-tag pose seed

This is the only path in the entire library by which vision may write `RootstockDrive`'s gyro→field offset (§2.2a item 2). It is therefore fenced in on six sides.

```java
// RootstockVision.periodic() step 8. Runs ONLY when all of the following hold.
// MatchContext, never DriverStation: ArchUnit rule 10 (design/06 §5.3).
if (MatchContext.isDisabled()             // 1. disabled only — never during a match
    && m_seedWhileDisabled                // 2. explicitly opted in
    && m_disabledSeedConsumer != null) {  // 3. the team supplied drive::resetPose
  for (VisionFrame f : acceptedThisLoop) {
    if (f.source().isGyroFused()) continue;   // 4. a gyro-fused solve cannot seed the gyro offset
    if (f.tagCount() < 2) continue;           // 5. multi-tag only — no single-tag PnP seeding
    if (!m_seedAgreement.accept(f.pose2d())) continue;  // 6. three consecutive agreeing frames
    m_disabledSeedConsumer.accept(f.pose2d());          // -> RootstockDrive.resetPose(bluePose)
    RootstockLog.critical("Rootstock/Vision/Seed/LastSeedPose", f.pose2d(), Pose2d.struct);
    RootstockLog.critical("Rootstock/Vision/Seed/LastSeedSource", f.source().name());
    RootstockLog.critical("Rootstock/Vision/Seed/Count", ++m_seedCount);
    break;                                    // one seed per loop, maximum
  }
}
```

Constraint 4 is the one that is easy to get wrong and fatal to get wrong. MegaTag2 consumes `getGyroFieldHeading()` to produce its translation. Using a MegaTag2 pose to set the offset that `getGyroFieldHeading()` is built from is a closed loop with a gain of one: it will agree with itself forever, including when it is 180° wrong. Only `MEGATAG_1`, `MULTI_TAG_COPROC`, `SINGLE_TAG_PNP` (excluded anyway by the `tagCount >= 2` rule) and `SIM` may seed.

`m_seedAgreement` is a 3-deep ring: a candidate seeds only if the last three qualifying poses agree within **10 cm and 3°**. One bad multi-tag solve on a robot sitting on a cart in the queue line otherwise reseeds the offset to garbage, and nothing downstream would notice until the match started.

```
Rootstock/Vision/Seed/Count             long     seeds issued since boot
Rootstock/Vision/Seed/LastSeedPose      Pose2d
Rootstock/Vision/Seed/LastSeedSource    String   PoseSource of the frame that seeded
Rootstock/Vision/Seed/RejectedNoAgree   long     candidates that failed the 3-frame agreement gate
```

---

## 10. Tag Layout Management

### 10.1 `FieldLayouts`

```java
package org.rootstock.vision.field;

public final class FieldLayouts {

  /**
   * Resolution order, logged as Rootstock/Vision/Layout/Source:
   *   1. src/main/deploy/rootstock/field-layout.json   (a WPIcal or hand-authored layout)
   *   2. AprilTagFieldLayout.loadField(fallback)
   * Never throws. On a malformed override we fall back, raise an Alert, and keep running.
   */
  public static AprilTagFieldLayout resolve(AprilTagFields fallback);

  /** Loads a WPIcal-emitted WPILib-format layout JSON. */
  public static AprilTagFieldLayout fromWpical(Path json);

  /** Per-tag override on top of a base layout. Logs a delta table for every changed tag. */
  public static AprilTagFieldLayout merge(AprilTagFieldLayout base, Map<Integer, Pose3d> overrides);

  /** Logs per-tag translation/rotation deltas between two layouts. Use it to SEE welded vs andymark. */
  public static void logDeltas(AprilTagFieldLayout a, AprilTagFieldLayout b, String logKey);

  /** A stable content hash of (tagId, pose) pairs plus field dimensions. See 10.4. */
  public static String fingerprint(AprilTagFieldLayout layout);

  /** Defensive copy, for anyone who insists on mutating. */
  public static AprilTagFieldLayout copyOf(AprilTagFieldLayout layout);
}
```

The malformed-override path raises:

```java
Alerts.error("Vision",
    "deploy/rootstock/field-layout.json could not be parsed (" + reason + "). "
  + "Running on the built-in " + fallback.name() + " layout instead — which is NOT the layout "
  + "you deployed. Fix the file or delete it.",
    MatchImpact.BLOCKS_MATCH).set(true);
```

The 2026 REBUILT field ships two layouts and they are **different fields**:

```java
AprilTagFields.k2026RebuiltWelded     // PhotonVision's default
AprilTagFields.k2026RebuiltAndymark
```

**Verified 2026-08-08 against the WPILib 2026.2.2 `AprilTagFields` Javadoc.** The enum constants are exactly, and only: `k2022RapidReact, k2023ChargedUp, k2024Crescendo, k2025ReefscapeWelded, k2025ReefscapeAndyMark, k2026RebuiltWelded, k2026RebuiltAndymark`. Note the inconsistent capitalization the vendor ships: **2025 is `AndyMark`, 2026 is `Andymark`.** `kDefaultField` is a **static field**, not an enum constant — `public static final AprilTagFields kDefaultField`, javadoc *"Alias to the current game"* — alongside `kBaseResourceDir` and the instance field `m_resourceFile`. The distinction matters because `AprilTagFields.values()` does not contain it.

`AprilTagFields.loadAprilTagLayoutField()` is **`@Deprecated(forRemoval = true, since = "2025")`** with the javadoc note *"Use `AprilTagFieldLayout.loadField(AprilTagFields)` instead"* (verified 2026-08-08). Rootstock calls `AprilTagFieldLayout.loadField(...)` exclusively.

**We refuse to default.** `RootstockVision.Builder.layout(...)` has no default value. A team must write which field they are on. `kDefaultField` is an alias whose meaning changes between WPILib releases, and picking it silently is exactly the bug we are trying to prevent.

### 10.2 The deploy convention

```
src/main/deploy/rootstock/field-layout.json      # optional; a WPIcal output or a custom field
src/main/deploy/rootstock/vision.json            # optional; per-camera transform overrides, see 11.1
```

At boot we log:

```
Rootstock/Vision/Layout/Source        "deploy/rootstock/field-layout.json"  |  "k2026RebuiltWelded"
Rootstock/Vision/Layout/Fingerprint   "a1f4c2..."
Rootstock/Vision/Layout/TagCount      22
Rootstock/Vision/Layout/FieldLength   17.548
Rootstock/Vision/Layout/FieldWidth     8.052
```

Putting the fingerprint in the log means "which layout was this match run against?" is answerable from the log file six weeks later, from a hotel room, without the robot.

> **Replay note.** These five values are read from a file at boot, and `design/04` guarantee **G4** requires that any file read which *affects control* pass through a replayed input channel, not merely be logged. The layout affects control (it is the reference for every filter and every residual). `RootstockVision` therefore publishes the resolved layout's **fingerprint and tag poses** into a one-shot `LayoutInputs implements LoggableInputs` struct processed once during `robotInit()`, so a replay uses the layout the match ran on rather than whatever is in the developer's working tree. This is the vision-domain instance of `REVIEW.md` M12.

### 10.3 `TagResidualMonitor` — seeing which tag is wrong

For every accepted frame that carries a `bestCameraToTarget` and whose camera has a known `robotToCamera`:

```java
// Where the layout SAYS the tag is:
Pose3d layoutTag = layout.getTagPose(id).orElseThrow();
// Where our accepted pose + this camera + this observation IMPLIES the tag is:
Pose3d observedTag = new Pose3d(acceptedRobotPose)
        .transformBy(robotToCamera)
        .transformBy(target.bestCameraToTarget());

double residualMeters  = observedTag.getTranslation().getDistance(layoutTag.getTranslation());
double residualDegrees = Math.abs(observedTag.getRotation().toRotation2d()
                                  .minus(layoutTag.getRotation().toRotation2d()).getDegrees());
```

Logged as:

```
Rootstock/Vision/TagHealth/Tag<id>/ResidualMeters     rolling median
Rootstock/Vision/TagHealth/Tag<id>/ResidualDegrees    rolling median
Rootstock/Vision/TagHealth/Tag<id>/SampleCount
Rootstock/Vision/TagHealth/WorstTag                   long
Rootstock/Vision/TagHealth/MedianResidualMeters       across all tags
```

This is the number that turns "vision feels off" into "tag 7's residual is 9 cm and everything else is 1.5 cm — go look at the field."

**On a Limelight camera this monitor is also the second consumer of §5.2a's basis change**, so a systematic residual that is large on *every* tag and identical in magnitude on the Limelight while small on a PhotonVision camera in the same frame is the signature of a camera-space axis error, not a field error. `VisionDiagnostics` says so in those words when it sees that pattern.

### 10.4 Coprocessor / robot layout mismatch — the #1 silent bug

There is no supported NT key on either vendor that reports which field layout the coprocessor loaded. **[UNVERIFIED]** — re-checked 2026-08-08; no such key appears in the Limelight complete-NetworkTables reference, and PhotonVision publishes none. So we detect it by consequence, four ways, and each one names itself.

**(a) `TAG_NOT_IN_LAYOUT`.** If the coprocessor is solving with a different tag set than we hold, some tag ids will not resolve. Cheapest possible canary; already in `VisionFilters.standard()`.

**(b) Systematic residual bias.** A layout mismatch produces a residual that is *systematic across all tags*, unlike a single misplaced tag or a bad calibration (which are localized or random). `TagResidualMonitor` raises:

```java
if (sampleCount >= 200
    && medianResidualAcrossAllTags > LAYOUT_MISMATCH_METERS      // tunable, default 0.04
    && interQuartileRangeOfResiduals < medianResidualAcrossAllTags * 0.5) {
  Alerts.error("Vision",
      "Layout mismatch suspected: median tag residual "
      + fmt(medianResidual) + " m across " + tagCount + " tags is systematic. "
      + "Robot code is on " + layoutSourceName + " (fingerprint " + shortHash + "). "
      + "Check that every coprocessor is on the SAME welded/andymark layout.",
      MatchImpact.BLOCKS_MATCH).set(true);
}
```

**(c) Explicit handshake for custom coprocessors.** `rootstockV1` publishes `/rootstock_vision/<name>/layout_hash`. `CustomNTCameraIO` compares it against `FieldLayouts.fingerprint(ourLayout)` and raises a hard error on mismatch, naming both hashes, `MatchImpact.BLOCKS_MATCH`. This is the only way to get a *certain* answer, and it is a strong argument for the `rootstockV1` schema over the vendor formats. We publish the same key downward under `config/tag_layout` so a `rootstockV1` coprocessor can simply adopt ours.

**(d) A build-time check for PhotonVision.** `FieldLayouts.logDeltas(welded, andymark, "Rootstock/Vision/Layout/WeldedVsAndymark")` runs once at boot and logs the maximum per-tag delta between the two 2026 layouts. If that number is smaller than our residual noise floor, we log an info Finding saying the automatic detector cannot distinguish them on this field and the team must verify by hand. **[UNVERIFIED]** — I do not have the numeric welded-vs-Andymark delta for 2026 REBUILT; for 2025 Reefscape the differences were on the order of 1 inch on some tags, which is comfortably above the noise floor, and I expect the same for 2026. Measure it at boot rather than hardcoding an expectation. §21 OQ 5.

---

## 11. Setup & Diagnostics

### 11.1 Camera transforms live in code

Limelight's camera-to-robot transform lives in the web UI by default. That means it is invisible to sim, invisible to replay, and **gone the moment the camera is reflashed or a spare is swapped in.** Every pose is then wrong by the mount offset, with no error.

Rootstock's rule: **the transform is a `Transform3d` in robot code, and we push it to the camera.**

```java
package org.rootstock.vision;

/** Declares where a camera is. One place, visible to sim, replay, logging and the dashboard. */
public record CameraMount(
    String name,
    /** Robot origin -> camera lens. WPILib convention: +x forward, +y left, +z up. */
    Transform3d robotToCamera,
    /** For a camera on a moving mechanism: returns empty when the mechanism angle at that
     *  timestamp is unknown, which correctly DROPS the frame instead of using a wrong transform.
     *  6328's CameraConfig.poseFunction. Null for a fixed mount. */
    DoubleFunction<Optional<Transform3d>> transformAt,
    /** Mounting is never as-CADded. 6328 run one camera at -4.5 deg. Measured, not authored;
     *  published at /Tuning/Vision/<camera>/pitchFudgeDeg. See §2.3 contract C9. */
    DoubleSupplier pitchFudgeDegrees) {

  public static CameraMount fixed(String name, Transform3d robotToCamera) { ... }
  public static CameraMount onMechanism(String name, DoubleFunction<Optional<Transform3d>> f) { ... }
}
```

`LimelightCameraIO.pushCameraTransform(true)` (default) writes `camerapose_robotspace_set` at boot and every 5 s, then reads `camerapose_robotspace` back and compares:

```java
// LimelightHelpers.setCameraPose_RobotSpace(name, forward, side, up, roll, pitch, yaw).
// The LimelightLib page documents the second argument as "Side offset (meters), left of robot
// center" — i.e. Y+ LEFT, matching WPILib — while the AprilTag-coordinate-systems page says robot
// space is Y+ RIGHT. The two vendor pages disagree; see §5.2a and §21 OQ 14. We write left-positive
// (a straight pass-through of the WPILib Transform3d) because that is what the page documenting
// this setter says, and the targetpose_robotspace cross-check in §5.2a catches it if that is wrong.
double[] want = { t.getX(), t.getY(), t.getZ(),
                  Units.radiansToDegrees(t.getRotation().getX()),   // roll
                  Units.radiansToDegrees(t.getRotation().getY()),   // pitch
                  Units.radiansToDegrees(t.getRotation().getZ()) }; // yaw
m_camPosePub.set(want);
NetworkTableInstance.getDefault().flush();
// ... next loop:
double[] got = m_camPoseSub.get();     // camerapose_robotspace, documented as "array (6)"
if (got.length != 6 || maxAbsDiff(want, got) > 0.01) {
  Alerts.error("Vision", name + ": camera transform did not take. "
      + "Wanted " + fmt(want) + ", camera reports " + fmt(got),
      MatchImpact.BLOCKS_MATCH).set(true);
}
```

We also raise a Finding when `robotToCamera` is `Transform3d.kZero` — a camera at the exact robot origin is always a forgotten default, never a real mount — as `TRANSFORM_ZERO`, `MatchImpact.BLOCKS_MATCH`.

**Mounting guidance we put in the Javadoc** (from the PnP-degenerate-case discussion): mount cameras with deliberate yaw and pitch offset rather than staring straight at a tag. Pose estimation degrades when the camera is normal to the tag plane. 6328 mount at ±70° yaw and 12° pitch.

**LL4 wiring note** in the same Javadoc: LL4 dropped PoE. It is 5–26 V buck-boost via a Weidmuller port, 12 W max. An LL3 PoE injector will not power it.

### 11.2 Calibration checklist

Shipped as `docs/vision-calibration.md` and rendered by `VisionDiagnostics` when a calibration Finding fires.

| Step | What | Pass criterion |
|---|---|---|
| 1 | Calibrate **per camera and per resolution** with a ChArUco board. | 3D output exists at your streaming resolution. |
| 2 | Print the board at exactly 100 %; measure a printed marker with calipers; enter the measured size. | Measured within 0.5 mm of entered. |
| 3 | Mount the board rigidly and flat. Never handheld. | — |
| 4 | ≥ 12 snapshots, varying distance, with tilts up to 45°, filling the frame corners. | ≥ 12 accepted. |
| 5 | Prefer the **mrcal** backend over OpenCV. | — |
| 6 | Check mean reprojection error. | **< 1.0 px** |
| 7 | Check computed FOV against the datasheet. | within **±10°** |
| 8 | Confirm streaming resolution == calibration resolution. | equal |
| 9 | Prefer a **global shutter** sensor for anything that moves (OV9281 on LL4/LL3G, OV2311 on Arducam). Rolling shutter (LL3A, LL3, most cheap USB cameras) smears tags under rotation. | — |
| 10 | *Only after everything above is right*, consider WPIcal field calibration. WPILib is explicit that it "is not a silver bullet" and only corrects minor placement error. | — |

PhotonVision does not support Logitech cameras, built-in webcams, or virtual cameras. That bites teams who buy the cheapest USB camera on the shelf.

### 11.3 `VisionDiagnostics`

```java
package org.rootstock.vision.diag;

public final class VisionDiagnostics {
  public enum Severity { INFO, WARN, ERROR }
  public record Finding(Severity severity, String code, String cameraName,
                        String message, String remedy, MatchImpact impact) {}

  public VisionDiagnostics(RootstockVision vision);
  /** Safe to call in disabledPeriodic. Cheap; caches for 1 s. */
  public List<Finding> run();
  public void publishToNT(String key);
  public String toPlainText();
}
```

Checks, each producing a sentence a student can act on. The `Impact` column is binding **D10**'s required answer for every one of them.

| Code | Impact | Fires when | Message |
|---|---|---|---|
| `CAM_DISCONNECTED` | **BLOCKS_MATCH** (rolled up, §11.3a) | `connected == false` for > 1.5 s | "front-left has not published for 3.2 s. Check power and Ethernet." |
| `CAM_SILENT` | **BLOCKS_MATCH** (rolled up) | connected but zero frames for > 1.5 s | "front-left is on the network but publishing no frames. Wrong pipeline?" |
| `MT2_NO_ORIENTATION` | **BLOCKS_MATCH** | MT2 selected, orientation not written | "MegaTag2 is selected but robot_orientation_set has not been written in 0.8 s. MegaTag2 output is garbage without it." |
| `GYRO_OFFSET_UNSEEDED` | **BLOCKS_MATCH** | a gyro-fused camera is configured and `PoseProvider.gyroFieldOffsetSeeded()` is false | "MegaTag2 is running in the POWER-ON gyro frame, not the field frame. Call drive.resetPose(startingBluePose)." Mirrors `DriveSelfCheck` check 9 (§2.2a). |
| `TRANSFORM_ZERO` | **BLOCKS_MATCH** (rolled up) | `robotToCamera` is `Transform3d.kZero` | "limelight-front's robot-to-camera transform is (0,0,0). Every pose is off by the mount offset." |
| `TRANSFORM_REJECTED` | **BLOCKS_MATCH** (rolled up) | pushed transform readback mismatch | "limelight-front did not accept the camera transform." |
| `LAYOUT_MISMATCH` | **BLOCKS_MATCH** (rolled up) | systematic residual (§10.4) | "Layout mismatch suspected: median residual 6.1 cm is systematic across 9 tags." |
| `LAYOUT_HASH_MISMATCH` | **BLOCKS_MATCH** (rolled up) | `rootstockV1` layout_hash ≠ ours | "northstar-rear loaded layout 7c1e…, robot code is on a1f4c2…." |
| `TIMESTAMP_OUT_OF_BUFFER` | PIT_ONLY | `ODOMETRY_BUFFER_MISS` > 5 % of frames | "back-right's timestamps are outside the 1.5 s odometry buffer. addVisionMeasurement is silently discarding them." |
| `NO_CALIBRATION` | PIT_ONLY | `getCameraMatrix()` empty | "front-left is uncalibrated at its current resolution. 3D mode will not work. Recalibrate at 1280x800." |
| `CALIB_REPROJ_HIGH` | PIT_ONLY | reprojection error > 1.0 px (read from the PhotonVision config JSON) | "front-left's calibration reprojection error is 1.8 px; it should be under 1.0. Recalibrate." |
| `CALIB_FOV_MISMATCH` | PIT_ONLY | computed FOV differs from the declared datasheet FOV by > 10° | "front-left's computed FOV is 63° but you declared 82°. The calibration is wrong." |
| `LATENCY_HIGH` | PIT_ONLY | 95th-percentile frame age > 0.15 s | "back-right's frames are arriving 180 ms late. Check network load and camera FPS." |
| `NETWORK_TRANSIT_HIGH` | PIT_ONLY | per camera: **p95 of `NetworkTransitEstimateSecs` over a 5 s rolling window > 10 ms** (§5.2 caveat 1 logs the quantity every frame) | "back-right's network transit is 14 ms at the 95th percentile; a wired link is 1–3 ms. Every timestamp from this camera is that far too late, which is 4 cm of pose error at 3 m/s. Reduce radio bandwidth: lower the camera stream resolution and FPS, or set the stream to `none` for a camera you are not watching." |
| `HIGH_REJECT_RATE` | PIT_ONLY | accept rate < 50 % over 5 s | "41 % of front-left's frames were rejected for HIGH_AMBIGUITY. You are relying on single tags at range." |
| `TAG_RESIDUAL_OUTLIER` | PIT_ONLY | one tag's residual > 3× the median | "Tag 7's residual is 9 cm; all other tags are under 2 cm. Field or layout problem." |
| `FPS_BELOW_CONFIGURED` | PIT_ONLY | measured fps < 70 % of configured | "front-left is running at 41 fps but is configured for 120. Check exposure and CPU temperature." |
| `CPU_HOT` | PIT_ONLY | coprocessor temp > 75 °C | "front-left is at 81 °C. It will throttle. Increase throttle_set or improve airflow." |
| `LL_CAMERASPACE_CONVENTION` | PIT_ONLY | §5.2a cross-check disagrees | "limelight-front's composed robot→tag transform disagrees with its own targetpose_robotspace by 0.44 m. alignToTag on this camera is not trustworthy." |
| `SIM_OPTIMISTIC` | PIT_ONLY (info) | sim active | "Simulated vision does not reproduce MegaTag2 ambiguity behavior. Do not tune ambiguity thresholds in sim." |

**Twenty checks.** `NETWORK_TRANSIT_HIGH` is the twentieth, added on 2026-08-08 to close `DESIGN.md` §16 item 8. *(That item says "a fifteenth named check" and asks for the count to go "from fourteen to fifteen"; both numbers are stale — the table already carried nineteen rows when the item was written, and the count that was actually stale is §16's, not this section's. The substance of the item — a named check, a threshold, a `MatchImpact` and an English remedy — is what is delivered here.)*

**Where the 10 ms threshold comes from, written out so a team cannot dismiss it as a magic number.** §5.2 caveat 1 is the reason the quantity exists: `sample.timestamp` is when the **server received** the value, so subtracting only camera-side latency leaves the capture time **late by the network transit time**. That is 1–3 ms on wired Ethernet and unbounded on a saturated field radio. The robot loop is 20 ms. At a transit of 10 ms the capture timestamp is understated by **half a loop before the frame even enters the filter chain** — and a half-loop timestamp error is not rejected by anything: the pose is geometrically valid, it is simply attributed to where the robot *was*. At 3 m/s that is **3 cm** of pose error credited to nothing, and it grows linearly with speed. Ten milliseconds is therefore the point at which the number stops being noise (3× the wired worst case) and starts being the explanation.

**This is the one field-only failure mode in the design, which is why it gets an alert rather than just a log key.** A robot on wired Ethernet in the pit measures 1–3 ms and passes every other check in this table; the same robot on a saturated field radio is quietly 10–50 ms late on every frame. Without a named check the team's diagnosis path is "vision seems worse at competition", which is not a diagnosis. `LATENCY_HIGH` does not cover it — that check fires on total frame *age*, which is dominated by camera exposure and pipeline time and is already 40–80 ms on a healthy camera, so a 10 ms transit change is inside its noise.

**Impact is `PIT_ONLY`, deliberately, and it is `Severity.WARN`** — the constant declared by the `Severity` enum above; `DESIGN.md` §16 item 8 writes `Severity.WARNING`, which is not a declared value in this document and would not compile. `PIT_ONLY` because a robot with degraded transit is still a robot that should take the field: the pose is late, not wrong, and the remedy (drop stream resolution, drop FPS, or set the stream to `none`) is a pit action, not a do-not-play condition. Promoting it to `BLOCKS_MATCH` would also spend one of the two blocking alerts §11.3a rations, on a condition a driver can do nothing about between matches.

**It stays inside `VisionDiagnostics` rather than becoming an eighth core monitor type.** The reviewer's alternative was a `NetworkMonitor` in core `health/builtin`, which would make `HealthMonitor.builtinTypeCount()` 8 and `builtinSliceCount()` 9. The quantity is measured, owned and logged by vision, and its remedy is a camera setting, so routing it through core would make core reach into vision for a number vision already has — and the 7/8 counts are CI-asserted in `design/06` §8.5's `BuiltinMonitorCountTest` and quoted in four more documents. **`HealthMonitor.builtinTypeCount() == 7` and `builtinSliceCount() == 8` are unchanged by this row, which is the point.** If a later season shows the failure is NT bandwidth starving telemetry generally rather than vision specifically, the eighth monitor becomes correct and this paragraph is the record of why it was not built first.

### 11.3a Alert budget discipline — how vision stays inside the CI ceiling

Binding **D10** caps the `/Rootstock/Driver` mirror at three simultaneous `BLOCKS_MATCH` alerts and makes `AlertBudgetTest` fail CI if the example robot can raise more than the budget at once. `DESIGN.md` states that budget as **3**; `design/06` argues **5** with a stated rationale, and `REVIEW.md` M14 adjudicates in favor of **5**. **Vision is designed against the tighter of the two**, because a domain that quietly assumes the looser number is how a budget gets blown.

A four-camera robot with a bad layout could raise `CAM_DISCONNECTED` ×4, `TRANSFORM_ZERO` ×4, `LAYOUT_MISMATCH` and `TRANSFORM_REJECTED` — ten blocking alerts from one domain. That is not "answering D10's question", it is refusing to answer it ten times.

**So vision contributes at most two `BLOCKS_MATCH` alerts, ever:**

1. **`MT2_NO_ORIENTATION` / `GYRO_OFFSET_UNSEEDED`** share one alert handle. Both mean the same thing to a driver — *your MegaTag2 pose is being solved in the wrong frame* — and they cannot be true for different reasons at the same time in a way a driver could act on differently.
2. **`VISION_NOT_READY`** is a single roll-up covering every other `BLOCKS_MATCH`-class condition, with live text naming the worst one and how many others there are: *"2 of 4 cameras are not publishing (front-left, rear); 1 other blocking vision finding. Open the pit tab."*

Every other finding in §11.3 is `PIT_ONLY` and appears only on the pit tab, in full, individually, with its remedy. **Nothing is hidden** — the roll-up changes where a finding is displayed, not whether it exists, and `VisionDiagnostics.run()` still returns every one of them. `VisionAlertBudgetTest` asserts that a synthetic four-camera robot with every check failing raises exactly two `BLOCKS_MATCH` alerts.

### 11.4 Match recording

Elite teams record what the camera actually saw, gated on FMS attach. 6328 debounce `DriverStation.isFMSAttached()` by 3 s and push `is_recording`; Limelight OS 2026 ships Rewind on LL4 (always-buffering, `.rwnd` bundles synchronized video + targeting + config, ~0.5–1 ms latency penalty).

```java
// Wired automatically by RootstockVision unless the team calls setRecording() themselves.
// MatchContext, never DriverStation: ArchUnit rule 10.
m_fmsDebounce = new Debouncer(3.0, Debouncer.DebounceType.kBoth);
boolean record = m_fmsDebounce.calculate(MatchContext.isFMSAttached());
for (VisionCameraIO io : m_ios) io.setRecording(record);
```

`LimelightCameraIO.setRecording(true)` sets `rewind_enable_set = 1` (documented as *"controls rewind buffer recording (1 = enabled, 0 = paused)"*); on the disabled edge after a match it fires `capture_rewind` (documented as *"triggers rewind capture with counter and duration parameters"*) with the configured duration. Both keys verified 2026-08-08. `CustomNTCameraIO` writes `config/is_recording`, `config/event_name`, `config/match_type`, `config/match_number`, sourced from `MatchContext.match()`.

### 11.5 Throttling

```java
vision.setThrottle(cameraIndex, skipFrames);   // LL: throttle_set. PV: camera.setFPSLimit(int). Northstar: config/throttle_fps
```

`throttle_set` is documented as *"Sets number of frames to skip between processed frames to reduce temperature rise. Outputs are not zeroed during skipped frames"* (verified 2026-08-08). `PhotonCamera.setFPSLimit(int fps)` exists and is not deprecated (verified at tag `v2026.3.4`). `RootstockVision` exposes `setGlobalThrottle(int)` so a robot-wide "we need CPU elsewhere" signal (6328's `Robot.shouldThrottle()`) reaches every camera at once. Limelight also exposes `fiducial_downscale_set` via `LimelightCameraIO.setFiducialDownscale(int)` (§6.1).

### 11.6 Typed SnapScript channel

The Limelight Python pipeline is the only way a small team gets custom CV without a second coprocessor, and today the robot↔script contract is two untyped double arrays that drift silently. The two keys are documented as `llrobot` — *"NumberArray sent by robot, accessible within Python SnapScripts"* — and `llpython` — *"NumberArray sent by Python scripts, accessible in robot code"* (verified 2026-08-08).

```java
package org.rootstock.vision.limelight;

public final class SnapScriptChannel<TOut, TIn> {
  public static <O, I> SnapScriptChannel<O, I> of(
      String limelightName, Function<O, double[]> encode, Function<double[], Optional<I>> decode);
  /** -> llrobot (LimelightHelpers.setPythonScriptData) */
  public void send(TOut payload);
  /** <- llpython (LimelightHelpers.getPythonScriptData) */
  public Optional<TIn> receive();
  public void logTo(String key);
}
```
Ships with a Python-side stub generator so the array layout is declared once. `runPipeline(image, llrobot)` returns `(largestContour, image, llpython)`.

---

## 12. Object Detection

### 12.1 `DetectedObject`

```java
package org.rootstock.vision.objects;

/**
 * A game piece (or an opposing robot) seen by a neural detector and projected onto the field.
 * 6328 track opposing ROBOTS as class 1 alongside game pieces as class 0 — model both.
 */
public record DetectedObject(
    int cameraIndex,
    String cameraName,
    double timestampSeconds,
    int classId,
    double confidence,
    /** Bearing from the camera, CCW-positive. */
    Rotation2d tx,
    Rotation2d ty,
    double areaPercent,
    /** Field-relative ground position, empty when the ray never intersects the ground plane. */
    Optional<Translation2d> fieldPosition,
    /** Straight-line distance from the ROBOT origin, or NaN when unprojected. */
    double distanceMeters) {

  public static final DetectedObjectStruct struct = new DetectedObjectStruct();
}
```

### 12.2 Ground-plane projection

We deliberately do **not** trust a coprocessor's 3D estimate of a sphere or a cylinder. We take the corner/center bearings and project onto a known ground plane, exactly as 6328 do. This is far more robust and it is pure, unit-testable, HAL-free math.

```java
package org.rootstock.vision.objects;

public final class ObjectProjection {

  /**
   * Intersect the camera ray through (tx, ty) with the horizontal plane z = targetCenterHeightMeters.
   *
   * Camera frame is WPILib's: +x out of the lens, +y left, +z up. tx and ty are the independently
   * measured normalized-image-plane angles both vendors report, so the ray direction is
   * (1, tan(tx), tan(ty)) before rotation into the field frame.
   *
   * @param robotPose               robot pose at the frame's capture time (use PoseProvider.sampleAt)
   * @param robotToCamera           this camera's mount transform
   * @param tx                      CCW-positive bearing (LimelightCameraIO already negated Limelight's tx)
   * @param ty                      up-positive bearing
   * @param targetCenterHeightMeters height of the object's centroid above the carpet
   * @return field-relative ground position, or empty when the ray points away from the plane
   */
  public static Optional<Translation2d> toGround(
      Pose2d robotPose, Transform3d robotToCamera,
      Rotation2d tx, Rotation2d ty, double targetCenterHeightMeters) {

    Pose3d camField = new Pose3d(robotPose).transformBy(robotToCamera);

    Translation3d dirCam = new Translation3d(1.0, tx.getTan(), ty.getTan());
    Translation3d dirField = dirCam.rotateBy(camField.getRotation());

    double dz = dirField.getZ();
    if (Math.abs(dz) < 1e-9) return Optional.empty();          // ray parallel to the plane

    double t = (targetCenterHeightMeters - camField.getZ()) / dz;
    if (t <= 0.0 || !Double.isFinite(t)) return Optional.empty();  // plane is behind the camera

    return Optional.of(new Translation2d(
        camField.getX() + t * dirField.getX(),
        camField.getY() + t * dirField.getY()));
  }

  /** Uses the mean of the four detection corners instead of the box center. More stable when the
   *  object is partially occluded at the frame edge. Falls back to (tx, ty) when corners are absent. */
  public static Optional<Translation2d> toGroundFromCorners(
      Pose2d robotPose, Transform3d robotToCamera, TargetObservation target, double heightMeters);

  /** Bottom-edge projection: for an object resting on the carpet, the bottom of the bounding box
   *  hits z = 0 exactly, which removes the dependence on knowing the object's height. Preferred
   *  when the detector's box bottom is reliable. */
  public static Optional<Translation2d> toGroundFromBottomEdge(
      Pose2d robotPose, Transform3d robotToCamera, TargetObservation target);
}
```

`Rotation2d.getTan()` exists in WPILib 2026 — verified 2026-08-08 against the 2026.2.2 `Rotation2d` Javadoc, which also confirms the preallocated constants this document uses: `kZero`, `kCW_Pi_2`, `kCW_90deg`, `kCCW_Pi_2`, `kCCW_90deg`, `kPi`, `k180deg`. If an implementer prefers, `Math.tan(tx.getRadians())` is identical.

**Sign convention, stated once because this is a season-costing bug:** PhotonVision's `getYaw()` is documented *"with left being the positive direction"* and `getPitch()` *"with up being the positive direction"* — already our convention (verified 2026-08-08). Limelight's `tx`/`txnc` is positive to the **right**. `LimelightCameraIO` negates it at the IO boundary and nowhere else. There is a unit test (`ObjectProjectionTest.limelightAndPhotonAgreeOnTheSameSyntheticScene`) that builds the same synthetic target for both vendors and asserts the projected field positions match to 1 mm.

### 12.3 Source decoding

**Limelight neural detector.** `rawdetections` is documented as *"[id, txnc, tync, ta, corner0x, corner0y, corner1x, corner1y, corner2x, corner2y, corner3x, corner3y, id2.....]"* — stride **12** per detection (verified 2026-08-08, and matched by `LimelightHelpers`' own `RawDetection` fields `classId, txnc, tync, ta, corner0_X, corner0_Y, corner1_X, corner1_Y, corner2_X, corner2_Y, corner3_X, corner3_Y`). `LimelightHelpers.getRawDetections(name)` returns typed `RawDetection[]`; `getRawFiducials(name)` returns `RawFiducial[]` with `id, txnc, tync, ta, distToCamera, distToRobot, ambiguity` at stride **7**, matching the botpose per-tag block exactly. We use the typed accessors for detections (they are low-rate and the array is small) and raw NT for botpose. `getDetectorClass(name)` gives the primary detection's class name; `getDetectorClassIndex(name)` the index.

**PhotonVision object detection.** Verified 2026-08-08 at tag `v2026.3.4`: `int getDetectedObjectClassID()` (−1 when N/A) and `float getDetectedObjectConfidence()` (−1 when N/A) on `PhotonTrackedTarget`. Object detection is only available on specific hardware (Orange Pi 5 / Rubik Pi 3 as of 2026), and PhotonVision's shipped COCO and 2026 FUEL models are AGPLv3 (Ultralytics). PhotonVision supports model *conversion*, not training. Limelight's free trainer produces Limelight-format models tied to Hailo 8 / 8L / Coral / CPU runtimes. **Models are not portable between vendors and Rootstock does not pretend otherwise.**

### 12.4 `ObjectTracker`

A neural detector flickers. Feeding raw per-frame detections into an intake command produces a robot that lunges at noise.

```java
package org.rootstock.vision.objects;

public final class ObjectTracker {
  public ObjectTracker(double associationRadiusMeters,   // default 0.35
                       double persistenceSeconds,        // default 0.5 — keep a track alive this long
                       int minSightingsToPromote);       // default 2

  void ingest(VisionFrame frame, VisionContext ctx);     // called by RootstockVision.periodic

  public List<DetectedObject> tracks(int classId);
  /** Highest-confidence track of this class, breaking ties by proximity to `from`. */
  public Optional<DetectedObject> best(int classId, Translation2d from);
  /** Nearest track of this class within a radius. */
  public Optional<DetectedObject> nearest(int classId, Translation2d from, double maxRadiusMeters);
}
```

Tracks are associated by nearest field position across frames and across **cameras** — two cameras seeing the same fuel produce one track, not two. Logged to `Rootstock/Vision/Objects/Class<n>/Positions` as a `Translation2d[]` so it renders directly in AdvantageScope's 2D field view.

---

## 13. Vision-Driven Actions

These are the ready-to-use command factories that actually change a small team's score. They live in `org.rootstock.vision.commands` and take a `Drive` interface, never a concrete drivetrain.

> ### Revision-4 blocking correction — read this before §13.4 and §13.5
>
> Revision 3's terminal controllers **commanded velocity away from the target.** Both of them. Including `alignToTag`, which this document calls "the command that makes the compete-against-world-class-teams claim honest."
>
> The mechanism is exactly the one [`design/05` §9.1](05-drivetrain-auto.md) already found, fixed, and pinned with four regression tests. WPILib's `PIDController` / `ProfiledPIDController` define error as **setpoint − measurement** (verified 2026-08-08: `ProfiledPIDController.getPositionError()` javadoc, WPILib 2026.2.2 — *"Returns the difference between the setpoint and the measurement"*). So `calculate(errorNorm, 0.0)` — measurement = the distance to the goal, setpoint = zero — is **negative** the whole time the robot is approaching, and so is `getSetpoint().velocity`, because the profiled distance is decreasing. Revision 3 multiplied that negative scalar by a direction vector pointing **robot → goal**. The product points **goal → robot**. The robot accelerates backwards until it times out or hits something.
>
> Two further defects rode along in the same twelve lines:
>
> - **`ffScale` multiplied the feedback term.** `ffScale` is 6328's *feedforward* ramp; it goes to zero at `ffMinRadiusMeters`. Applied to the feedback term it creates a dead zone from `ffMinRadius` inward — and `AlignGains.defaults()` sets `ffMinRadius = 0.05 m` with `toleranceMeters = 0.05 m`, while `tagRelative()` sets both to `0.02 m`. Each command's feedback authority therefore vanished at exactly the radius it was trying to converge inside. `design/05` §9.1 scales only `ffVel`; so do we now.
> - **`m_translationProfileVelocity` was never declared or seeded.** It was a bare field name standing in for a profile state, which is `design/05`'s own review finding #3 reappearing. The translation channel is now a declared `ProfiledPIDController`, seeded in `initialize()` from the measured error and the measured closing velocity, exactly as `design/05` §9.1 requires.
>
> **Both controllers below now follow `design/05` §9.1's sign convention**, and §13.4.1 explains why the translation and theta channels legitimately *look* different so that a future reader does not "fix" the difference back into a bug. §16 gains four tests that fail if any of this regresses: `drivesTowardTheTarget`, `drivesTowardTheTargetOffAxis`, `hasNoDeadZoneAboveTolerance`, `seedsTheProfileFromMeasuredClosingVelocity` — the direct analogs of `design/05` §9.2's suite, which the previously-specified `AlignToTagTest` would not have caught because it only pinned *error computation*, never *commanded-velocity direction*.

### 13.1 The drive seam

**Declared by Drive, not here (D16).** Until 2026-08-08 this section declared `AlignableDrive` in `org.rootstock.vision.commands` while binding **D16** assigned it to `org.rootstock.drive` — open question 14 in `design/05` §14, and the last of the four D16 interfaces to move. It has moved: the declaration is [`design/05` §3.3.2](05-drivetrain-auto.md) and there is no second one. The block below is the surface these commands *consume*. **If it disagrees with `design/05`, `design/05` wins.** Nothing else in §13 changed — the `VisionAlignFactory` seam was designed to be correct either way.

> **consumed-surface mirror — `design/05` §3.3.2 wins.** Same banner, same rule as §2.2: this is one of the **four legal labeled mirrors** of the D16 interfaces, and it is legal *because* it carries this line verbatim. An occurrence of `PoseProvider`, `AlignableDrive`, `DriveTelemetry` or `VisionConsumer` declared **without** this banner is an unbannered duplicate and the gate fails on it.

```java
package org.rootstock.drive;   // D16. MIRROR of design/05 §3.3.2 — not a second declaration.

/** consumed-surface mirror — design/05 §3.3.2 wins.
 *  The only thing the vision commands need from a drivetrain. RootstockDrive implements it (D16). */
public interface AlignableDrive {
  void driveFieldRelative(ChassisSpeeds speeds);
  /**
   * Robot-relative drive: +x forward, +y left, CCW-positive omega.
   *
   * <p>REQUIRED, not defaulted. `alignToTag` (13.5) closes the loop on a camera-to-tag transform,
   * which is a robot-frame quantity — routing it through a field-relative call would reintroduce
   * the fused pose's heading error into a controller whose entire purpose is to not depend on the
   * fused pose. A default implementation that rotated by `getPose().getRotation()` would silently
   * undo the feature, so there isn't one.
   */
  void driveRobotRelative(ChassisSpeeds speeds);

  /** REQUIRED (revision 4). Both terminal controllers seed their profiled controller from the
   *  measured closing velocity in initialize(); without this, an alignAtEnd handoff at 3 m/s
   *  commands a reversal on the first loop. §2.7 contract C5. */
  ChassisSpeeds getRobotRelativeSpeeds();

  void stop();
  Subsystem asSubsystem();          // for addRequirements; may return a no-op Subsystem
  double maxLinearSpeedMps();
  double maxAngularSpeedRadPerSec();
}
```

### 13.2 `AlignGains`

```java
public record AlignGains(
    double translationKp, double translationKd,
    double rotationKp,    double rotationKd,
    double maxVelMps,     double maxAccelMpsSq,
    double maxOmegaRadPerSec, double maxAlphaRadPerSecSq,
    /**
     * Terminal tolerance. READ THIS BEFORE TIGHTENING IT.
     *
     * <p>2 cm alignment requires tag-relative control (`VisionCommands.alignToTag`); a fused-pose
     * controller cannot reliably hold tighter than the vision standard deviation at your scoring
     * distance. With the default std-dev model, sigmaXY = 0.02 * d^2 / n, halved for gyro-fused
     * sources, which is 0.02 * 3.0^2 / 2 * 0.5 = 0.045 m — 4.5 cm — at 3 m with two tags on
     * MegaTag2 (the derivation is written out in §8.2). So a 2 cm tolerance on `driveToPose` or
     * `alignToNearest` is a tolerance the sensor cannot satisfy, and the only thing it produces is
     * a timeout alert on every single alignment. `defaults()` is therefore 5 cm / 2 deg, which is
     * what a fused-pose controller can actually hold. Use `tagRelative()` with `alignToTag` when
     * you need 2 cm.
     */
    double toleranceMeters, Rotation2d toleranceRotation,
    /** 6328's feedforward ramp: scale the PROFILE FEEDFORWARD linearly between these two error
     *  radii so the robot neither slams nor creeps. clamp((err - min) / (max - min), 0, 1).
     *  It scales the feedforward ONLY — see the revision-4 note at the top of §13. */
    double ffMinRadiusMeters, double ffMaxRadiusMeters,
    /** The same ramp for the rotation channel. Mirrors design/05 §9's thetaFfBlend so the two
     *  documents' controllers are numerically identical. */
    Rotation2d ffMinRotation, Rotation2d ffMaxRotation) {

  /**
   * For FUSED-POSE commands (driveToPose, alignToNearest).
   *   translationKp 4.0, translationKd 0.0, rotationKp 5.0, rotationKd 0.4,
   *   3.0 m/s, 4.0 m/s^2, 540 deg/s, 720 deg/s^2,
   *   tolerance 0.05 m / 2.0 deg, ff ramp 0.05 .. 0.60 m, theta ff ramp 8 .. 40 deg.
   *
   * These are byte-for-byte design/05 §9.1's stated defaults, deliberately: two documents
   * specifying the same controller with different gains is how a library grows two controllers.
   * (Revision 3 had rotationKd = 0.0 here against design/05's 0.4; design/05 wins.)
   */
  public static AlignGains defaults();

  /** For TAG-RELATIVE control (alignToTag). Same gains, tolerance 0.02 m / 1.0 deg, ff ramp
   *  0.02 .. 0.30 m, theta ff ramp 4 .. 20 deg — tighter because the feedback signal is the tag
   *  itself, not a fused pose, and its noise does not grow with the robot's distance from the
   *  field origin. */
  public static AlignGains tagRelative();
}
```

> **Cross-domain consequence, and it is a required change, not a suggestion.** `design/05` §9 currently gives `RootstockDriveToPose` a default of `tolerance(Meters.of(0.02), Degrees.of(1.5))`. That default is unreachable for the reason above and must become `tolerance(Meters.of(0.05), Degrees.of(2.0))`, carrying the Javadoc line above. `DESIGN.md` §10B and `design/05` §10's worked example carry the same literal. The Drive domain owns those edits; Vision owns the number and the reason. §2.7 contract C6, and `REVIEW.md` M20. *(Now applied everywhere — verified 2026-08-08: `design/05` §9 reads the change through the named constants `kDefaultTolerance`/`kDefaultAngularTolerance`, `design/05` §10 and `DESIGN.md` §10B both read `0.05 m / 2.0°`. The "currently gives" above describes the pre-correction state and is kept as the record of why the change was ordered.)*

### 13.3 `VisionCommands`

```java
package org.rootstock.vision.commands;

public final class VisionCommands {

  /**
   * Two-phase drive-to-pose. PathPlanner pathfindToPose while the error exceeds handoffMeters
   * (obstacle-aware, gets you across the field), then an internal profiled x/y/theta controller
   * for the terminal approach (PathPlanner is the wrong tool for the last 30 cm).
   *
   * The freshness gate is the point: the robot never finishes an alignment on a 500 ms stale pose.
   * When freshness fails mid-approach the command holds position and logs ALIGN_STALE_VISION
   * rather than driving blind.
   *
   * Degrades gracefully to pure terminal control when PathPlannerLib is not on the classpath.
   *
   * REVISION 4: `vision` is now a parameter. Revision 3's execute() read `m_vision` and
   * `m_cameraIndex`, neither of which this factory received — the freshness gate could not have
   * been implemented as specified. The gate is global (hasRecentFix / acceptedFramesInWindow),
   * not per-camera, because a fused-pose command does not care WHICH camera fixed the pose.
   */
  public static Command driveToPose(
      AlignableDrive drive, PoseProvider pose, RootstockVision vision, Supplier<Pose2d> target,
      PathConstraints approachConstraints, double handoffMeters,
      AlignGains gains, VisionFreshness freshness);

  /**
   * Auto-align to the nearest of a set of BLUE-AUTHORED scoring poses. Alliance flipping is applied
   * here and only here (section 9.3). `offset` is applied in the target's own frame, so
   * "20 cm back from the face, facing it" is expressed once and works at every scoring location.
   */
  public static Command alignToNearest(
      AlignableDrive drive, PoseProvider pose, RootstockVision vision, List<Pose2d> blueTargets,
      Transform2d offset, PathConstraints approach, double handoffMeters,
      AlignGains gains, VisionFreshness freshness);

  /**
   * TAG-RELATIVE terminal alignment. Built at M10 with the vision core, and it is the command that
   * makes the "compete against world-class teams" claim honest.
   *
   * <p>Every other command in this class closes the loop on the FUSED GLOBAL POSE, and is therefore
   * bounded below by the vision standard deviation at scoring range (4.5 cm at 3 m with two tags —
   * §8.2). This one closes the loop on `TargetObservation.bestCameraToTarget()` — the tag as the
   * camera actually sees it — composed with the camera mount transform. Odometry drift, gyro offset
   * error, layout error and alliance-flip mistakes all cancel out of the error term, because both
   * the measurement and the goal are expressed in the tag's own frame. That is why 2 cm is
   * achievable here and nowhere else.
   *
   * <p>This is a CONTROLLER, not new perception. `TargetObservation` already carries everything
   * required, which is why this was pulled forward into M10 for +0.4 person-weeks.
   *
   * @param cameraIndex      which camera owns this alignment; must report a robotToCamera transform
   * @param acceptableTagIds tag ids allowed to drive this alignment; the camera's tag filter is set
   *                         to these on init and RESTORED on end, so a defender's bumper tag or a
   *                         neighboring scoring face cannot steal the solve
   * @param tagRelativeGoal  TAG -> desired ROBOT ORIGIN. "20 cm out from the face, squared up" is
   *                         written once and is correct at every tag on the field.
   */
  public static Command alignToTag(
      AlignableDrive drive, RootstockVision vision, int cameraIndex,
      int[] acceptableTagIds, Transform3d tagRelativeGoal, AlignGains gains);

  /** As above, plus a staleness gate and odometry-based latency compensation. Preferred when the
   *  robot is still moving fast at handoff; see 13.5 for why the compensation matters. */
  public static Command alignToTag(
      AlignableDrive drive, RootstockVision vision, PoseProvider pose, int cameraIndex,
      int[] acceptableTagIds, Transform3d tagRelativeGoal,
      AlignGains gains, VisionFreshness freshness);

  /**
   * Heading-only. The driver keeps full translation authority; we own theta. This is the command
   * that wins matches with a defender on you, because the driver is never locked out.
   */
  public static Command aimAtPoint(
      AlignableDrive drive, PoseProvider pose, Supplier<Translation2d> fieldPoint,
      DoubleSupplier vxSupplier, DoubleSupplier vySupplier, AlignGains gains);

  /** aimAtPoint, but at the MOVING-TARGET solution instead of the static point. See 13.7. */
  public static Command aimWhileMoving(
      AlignableDrive drive, PoseProvider pose, RootstockVision vision, MovingTargetSolver solver,
      Supplier<Translation3d> fieldTarget,
      DoubleSupplier vxSupplier, DoubleSupplier vySupplier, AlignGains gains);

  /**
   * Drive at the highest-confidence tracked object of a class and run `intakeCommand` while
   * approaching. Ends when the intake reports a piece, when maxSearchSeconds elapses, or when the
   * track is lost for longer than the tracker's persistence window.
   */
  public static Command autoIntake(
      AlignableDrive drive, RootstockVision vision, PoseProvider pose,
      int objectClassId, Command intakeCommand, BooleanSupplier hasPiece,
      AlignGains gains, double maxSearchSeconds);

  /** Switch a pipeline and wait for it to actually settle. Five lines that fix a real bug. */
  public static Command setPipeline(VisionCameraIO io, int index, double timeoutSeconds);
}

/** How fresh a vision fix must be for an alignment to be allowed to complete. */
public record VisionFreshness(double maxAgeSeconds, int minAcceptedFramesInWindow) {
  public static VisionFreshness defaults() { return new VisionFreshness(0.30, 2); }
  /** Never gate — for teams whose alignment is odometry-only. A large FINITE age, not Infinity:
   *  this number is compared, logged and occasionally scaled, and §8.0 Rule 1's reasoning about
   *  Infinity surviving arithmetic applies to every sentinel in the library, not just sigmas. */
  public static VisionFreshness none() { return new VisionFreshness(1.0e6, 0); }

  /** Evaluated against RootstockVision, which is why §13.3's factories take one. */
  public boolean satisfiedBy(RootstockVision v) {
    return v.hasRecentFix(maxAgeSeconds)
        && v.acceptedFramesInWindow(maxAgeSeconds) >= minAcceptedFramesInWindow;
  }

  /** False for none(). Named, so no call site has to compare against the 1e6 sentinel by hand. */
  public boolean gated() { return maxAgeSeconds < 1.0e6; }
}
```

### 13.4 The terminal controller — corrected to `design/05` §9.1's convention

**Read the sign convention before the code. It is the part everyone gets wrong, and this document already got it wrong once.**

The controlled scalar is `errorNorm`, the *distance* from the robot to the goal, and its goal is `0`. A `ProfiledPIDController` driving a positive measurement to a zero goal produces a **negative** output, because `calculate`'s error is `setpoint − measurement`; its profiled `getSetpoint().velocity` is likewise negative, because the distance is decreasing. Therefore the scalar we compute is negative while approaching, and it must be applied along a unit vector pointing **from the goal back to the robot** — `current − goal` — so that the product points at the goal.

This is 6328's convention, it is `design/05` §9.1's convention, and it is why `direction` is `current.minus(goal).getAngle()` rather than the intuitive `goal − current`. **If you change one, you must change the other.** §16's `drivesTowardTheTarget` exists for no other reason.

```java
// DriveToPoseCommand — field declarations. The TYPES are load-bearing:
// a plain PIDController's getSetpoint() returns a bare double and will not compile against this.
private final ProfiledPIDController m_translationController;
private final ProfiledPIDController m_thetaController;
private static final double kEpsilonMeters = 1e-6;
private int m_inToleranceLoops = 0;
private boolean m_visionStale = false;

DriveToPoseCommand(/* built by the factory */) {
  m_translationController = new ProfiledPIDController(
      m_gains.translationKp(), 0.0, m_gains.translationKd(),
      new TrapezoidProfile.Constraints(m_gains.maxVelMps(), m_gains.maxAccelMpsSq()));
  m_thetaController = new ProfiledPIDController(
      m_gains.rotationKp(), 0.0, m_gains.rotationKd(),
      new TrapezoidProfile.Constraints(m_gains.maxOmegaRadPerSec(), m_gains.maxAlphaRadPerSecSq()));
  // REQUIRED here (and NOT in §13.5): this channel controls an ABSOLUTE heading.
  m_thetaController.enableContinuousInput(-Math.PI, Math.PI);
}
```

**`initialize()` — seeding is not optional.** A `ProfiledPIDController` whose internal state is stale generates a feedforward that has nothing to do with where the robot actually is or how fast it is actually closing. That happens on every restart, every interrupt-and-rerun, every handoff from `pathfindToPose` at 3 m/s, and every time a defender shoves the robot.

```java
@Override
public void initialize() {
  m_inToleranceLoops = 0;
  m_visionStale = false;

  Pose2d current = m_pose.getPose();
  Pose2d goal    = m_target.get();

  double errorNorm = current.getTranslation().getDistance(goal.getTranslation());

  // Robot -> goal bearing, used ONLY to project the current velocity onto the closing axis.
  Rotation2d toGoal = goal.getTranslation().minus(current.getTranslation()).getAngle();

  ChassisSpeeds fieldSpeeds = m_pose.getFieldRelativeSpeeds();
  Translation2d fieldVel =
      new Translation2d(fieldSpeeds.vxMetersPerSecond, fieldSpeeds.vyMetersPerSecond);

  // d(distance)/dt. Negative when we are already closing. Clamped at 0 so an outbound velocity
  // does not seed the profile with an even larger distance goal.
  double distanceRate = Math.min(0.0, -fieldVel.rotateBy(toGoal.unaryMinus()).getX());

  m_translationController.reset(errorNorm, distanceRate);
  m_thetaController.reset(current.getRotation().getRadians(), fieldSpeeds.omegaRadiansPerSecond);
}
```

**`execute()`:**

```java
@Override
public void execute() {
  Pose2d current = m_pose.getPose();
  Pose2d goal    = m_target.get();

  // GOAL -> ROBOT. Paired with the NEGATIVE profiled scalar, this points the robot AT the goal.
  // design/05 §9.1 convention. Read the note above this block before touching it.
  Translation2d fromGoal = current.getTranslation().minus(goal.getTranslation());
  double errorNorm = fromGoal.getNorm();
  Rotation2d direction = errorNorm < kEpsilonMeters ? Rotation2d.kZero : fromGoal.getAngle();

  // 6328's feedforward ramp. ffScale multiplies ONLY the profile feedforward. Applying it to the
  // feedback term (revision 3's bug) zeroes the feedback inside ffMinRadius, which for
  // AlignGains.defaults() is exactly the command's own 5 cm tolerance.
  double ffScale = MathUtil.clamp(
      (errorNorm - m_gains.ffMinRadiusMeters())
          / (m_gains.ffMaxRadiusMeters() - m_gains.ffMinRadiusMeters()), 0.0, 1.0);

  double fbVel = m_translationController.calculate(errorNorm, 0.0);          // negative while approaching
  double ffVel = m_translationController.getSetpoint().velocity * ffScale;   // also negative; FF only
  double linearVel = fbVel + ffVel;
  if (errorNorm < m_gains.toleranceMeters()) linearVel = 0.0;   // no creeping INSIDE tolerance

  // Translation2d(double distance, Rotation2d angle) handles the negative magnitude correctly:
  // x = distance * cos(angle), y = distance * sin(angle).
  Translation2d velXY = new Translation2d(linearVel, direction);

  double thetaErrorRad = MathUtil.angleModulus(
      goal.getRotation().minus(current.getRotation()).getRadians());
  double thetaFfScale = MathUtil.clamp(
      (Math.abs(thetaErrorRad) - m_gains.ffMinRotation().getRadians())
          / (m_gains.ffMaxRotation().getRadians() - m_gains.ffMinRotation().getRadians()), 0.0, 1.0);
  double omega = m_thetaController.calculate(current.getRotation().getRadians(),
                                             goal.getRotation().getRadians())
               + m_thetaController.getSetpoint().velocity * thetaFfScale;

  ChassisSpeeds field = new ChassisSpeeds(velXY.getX(), velXY.getY(), omega);
  m_drive.driveFieldRelative(field);

  // Freshness gate: if vision has gone stale, hold instead of finishing blind.
  m_visionStale = !m_freshness.satisfiedBy(m_vision);

  // Tolerance latch, reset in the else branch. Without the reset this is design/05's
  // "reports GOAL 0.06 s after starting, from anywhere on the field" bug.
  if (errorNorm < m_gains.toleranceMeters()
      && Math.abs(thetaErrorRad) < m_gains.toleranceRotation().getRadians()
      && !(m_visionStale && m_freshness.gated())) {
    m_inToleranceLoops++;
  } else {
    m_inToleranceLoops = 0;
  }

  RootstockLog.critical("Rootstock/Vision/Align/ErrorMeters", errorNorm);
  RootstockLog.critical("Rootstock/Vision/Align/ErrorDegrees", Math.toDegrees(thetaErrorRad));
  RootstockLog.critical("Rootstock/Vision/Align/CommandedRobotSpeeds", field, ChassisSpeeds.struct);
  RootstockLog.log("Rootstock/Vision/Align/ProfileVelocity",
                 m_translationController.getSetpoint().velocity);
}
```

`isFinished()` is `m_inToleranceLoops >= 2` — both axes inside tolerance, and a live vision fix when freshness is not `none()`, held for two consecutive loops.

Driver override is always available: `driveToPose` and `alignToNearest` accept an optional `abortIf(BooleanSupplier)`. Every automation in Rootstock has a manual escape hatch, because a single-button macro that depends on vision fails the moment a defender occludes the tag. This is the vision-domain instance of binding **D30**'s rule that automation without a manual fallback loses matches.

### 13.4.1 Why the two channels look different, and why that is not the bug

The translation channel needs a direction vector and a sign inversion. The theta channel does not. A reader who "fixes" that asymmetry reintroduces the bug, so it gets written down.

| | translation channel | theta channel |
|---|---|---|
| What is controlled | a **magnitude** — `errorNorm ≥ 0`, driven to `0` | a **signed** angle |
| `calculate(...)` arguments | `(errorNorm, 0.0)` — measurement, then setpoint of zero | `(currentHeading, goalHeading)` in §13.4; `(0.0, errTh)` in §13.5 |
| Sign of the output while converging | **negative** (`setpoint − measurement = 0 − errorNorm`) | **same sign as the error** — positive when the goal is CCW |
| Therefore | needs a direction vector, and it must point **goal → robot** so the negative scalar aims at the goal | needs nothing; the scalar *is* the command |
| `enableContinuousInput` | n/a | **required in §13.4** (absolute headings wrap); **unnecessary in §13.5**, where the measurement is pinned at 0 and `Rotation2d` has already wrapped the error into (−π, π] |

A magnitude has no direction, so one has to be supplied, and the only self-consistent choice is the one that cancels the controller's own sign. A signed scalar carries its own direction and must not be negated. Revision 3 had the theta channels right and the translation channels wrong in both commands, which is precisely the signature of a bug rather than a convention.

### 13.5 The tag-relative controller — `alignToTag`

Twelve lines, and they are the twelve lines that separate "our alignment mostly works" from "our alignment works." **They are also the twelve lines revision 3 got backwards**, so the sign convention is repeated here rather than cross-referenced.

```java
// AlignToTagCommand — fields
private final ProfiledPIDController m_translationController;   // same types as §13.4
private final ProfiledPIDController m_thetaController;         // NO enableContinuousInput here
private static final double kEpsilonMeters = 1e-6;
private int m_lockedTagId = -1;
private int[] m_previousTagFilter = null;
private long m_noSolveLoops = 0;

/** ROBOT -> GOAL, in the ROBOT frame, or empty when there is no live solve this loop. */
private Optional<Transform3d> currentError() {
  Optional<TargetObservation> obs = m_vision.tag(m_cameraIndex, m_lockedTagId);
  Optional<Transform3d> mount     = m_vision.robotToCamera(m_cameraIndex);
  if (obs.isEmpty() || !obs.get().hasBestCameraToTarget() || mount.isEmpty()) return Optional.empty();

  // ROBOT -> TAG, by composition. This is the whole trick: the fused pose appears nowhere.
  Transform3d robotToTag = mount.get().plus(obs.get().bestCameraToTarget());
  // ROBOT -> GOAL. tagRelativeGoal is TAG -> desired robot origin, so the composition is direct.
  return Optional.of(robotToTag.plus(m_tagRelativeGoal));
}
```

```java
@Override
public void initialize() {
  m_noSolveLoops = 0;
  m_lockedTagId = pickHighestAreaAcceptableTag();          // latched, see detail 2
  m_previousTagFilter = m_vision.getTagIdFilter(m_cameraIndex);
  m_vision.setTagIdFilter(m_cameraIndex, m_acceptableTagIds);

  // Seed the profile from the measured error and the measured closing velocity (design/05 §9.1).
  Optional<Transform3d> e = currentError();
  double errorNorm = e.map(t -> t.getTranslation().toTranslation2d().getNorm()).orElse(0.0);
  Rotation2d toGoal = e.map(t -> t.getTranslation().toTranslation2d().getAngle())
                       .orElse(Rotation2d.kZero);

  ChassisSpeeds v = m_drive.getRobotRelativeSpeeds();       // robot frame, same frame as the error
  Translation2d vel = new Translation2d(v.vxMetersPerSecond, v.vyMetersPerSecond);
  double distanceRate = Math.min(0.0, -vel.rotateBy(toGoal.unaryMinus()).getX());

  m_translationController.reset(errorNorm, distanceRate);
  // The theta measurement is pinned at 0 by construction (see execute), so its derivative is 0.
  m_thetaController.reset(0.0, 0.0);
}
```

```java
@Override
public void execute() {
  Optional<Transform3d> maybeError = currentError();
  if (maybeError.isEmpty()) {
    m_drive.stop();
    m_noSolveLoops++;
    return;                        // hold. Never extrapolate a tag we cannot see.
  }
  Transform3d error = maybeError.get();
  m_noSolveLoops = 0;

  Translation2d toGoal = error.getTranslation().toTranslation2d();   // ROBOT -> GOAL, robot frame
  Rotation2d    errTh  = error.getRotation().toRotation2d();

  double errorNorm = toGoal.getNorm();
  // GOAL -> ROBOT, exactly as in §13.4. Paired with the NEGATIVE profiled scalar this drives
  // the robot TOWARD the goal. Revision 3 used toGoal.getAngle() here and drove away from it.
  Rotation2d direction = errorNorm < kEpsilonMeters
      ? Rotation2d.kZero
      : toGoal.unaryMinus().getAngle();

  double ffScale = MathUtil.clamp(
      (errorNorm - m_gains.ffMinRadiusMeters())
          / (m_gains.ffMaxRadiusMeters() - m_gains.ffMinRadiusMeters()), 0.0, 1.0);

  double fbVel = m_translationController.calculate(errorNorm, 0.0);          // negative while approaching
  double ffVel = m_translationController.getSetpoint().velocity * ffScale;   // FF ONLY
  double linearVel = fbVel + ffVel;
  if (errorNorm < m_gains.toleranceMeters()) linearVel = 0.0;

  Translation2d vel = new Translation2d(linearVel, direction);

  // Theta: measurement pinned at 0, goal = the signed robot-frame heading error. No inversion,
  // no continuous input needed — Rotation2d already wrapped errTh into (-pi, pi]. See §13.4.1.
  double thetaFfScale = MathUtil.clamp(
      (Math.abs(errTh.getRadians()) - m_gains.ffMinRotation().getRadians())
          / (m_gains.ffMaxRotation().getRadians() - m_gains.ffMinRotation().getRadians()), 0.0, 1.0);
  double omega = m_thetaController.calculate(0.0, errTh.getRadians())
               + m_thetaController.getSetpoint().velocity * thetaFfScale;

  // ROBOT-relative, because `error` is a robot-frame quantity. This is why AlignableDrive
  // requires driveRobotRelative (13.1).
  ChassisSpeeds cmd = new ChassisSpeeds(vel.getX(), vel.getY(), omega);
  m_drive.driveRobotRelative(cmd);

  RootstockLog.critical("Rootstock/Vision/Align/TagId", (long) m_lockedTagId);
  RootstockLog.critical("Rootstock/Vision/Align/ErrorMeters", errorNorm);
  RootstockLog.critical("Rootstock/Vision/Align/ErrorDegrees", errTh.getDegrees());
  RootstockLog.critical("Rootstock/Vision/Align/CommandedRobotSpeeds", cmd, ChassisSpeeds.struct);
}
```

Six details that are not optional:

1. **`Transform3d.plus` is composition, not addition.** WPILib documents it as *"Composes two transformations. The second transform is applied relative to the orientation of the first"* (verified 2026-08-08) — which is exactly `robot→camera` then `camera→tag`. Writing this with `Translation3d` arithmetic instead is the classic way to get an alignment that is correct only when the robot faces the tag square-on.
2. **The tag id is latched on `initialize()`, not re-picked every loop.** `acceptableTagIds` selects *which* tag we lock to at the start (highest area among the acceptable ids); after that the id is fixed. Re-picking every loop makes the goal jump when a second acceptable tag comes into view mid-approach, and the robot lunges.
3. **The camera's tag filter is set to `acceptableTagIds` on `initialize()` and restored on `end()`.** `setTagIdFilter` costs an NT write and buys immunity to a defender's bumper tag. The previous filter is captured via `RootstockVision.getTagIdFilter(...)` and restored even on interrupt.
4. **A missing solve holds; it never extrapolates.** Limelight only populates `bestCameraToTarget` for the primary tag via `targetpose_cameraspace` (§6.1), so a Limelight alignment that loses its primary tag stops. If `m_noSolveLoops` exceeds 25 (0.5 s) the command ends and raises `ALIGN_TAG_LOST` (`MatchImpact.PIT_ONLY` — it is a *this-attempt* failure, not a do-not-take-the-field one) rather than sitting there.
5. **Latency.** The 6-arg overload does no compensation and documents it: at the ≤ 0.5 m/s terminal speeds where this controller operates, an 80 ms observation age is ≤ 4 cm and the controller converges through it. The `PoseProvider` overload does compensate, transforming the observation forward by the odometry delta between `frame.timestampSeconds()` and now — worth it when the handoff from `driveToPose` happens at speed. §21 OQ 11 asks whether the compensating form should be the default.
6. **On a Limelight, this controller's accuracy is only as good as §5.2a's basis change**, whose rotation half is `[UNVERIFIED]`. The `LL_CAMERASPACE_CONVENTION` finding (§11.3) fires when the cross-check disagrees, and a team seeing it should treat `alignToTag` on that camera as unverified until it clears. PhotonVision cameras are unaffected — their transforms are already WPILib-framed.

`isFinished()` is `errorNorm < toleranceMeters && |errTh| < toleranceRotation`, held two consecutive loops, with a live solve in both. Logged to `Rootstock/Vision/Align/*` (§7.3).

### 13.6 `CameraArbiter` — which cameras get to relocalize

The earlier draft punted this to "an offseason-stretch experiment" on the reasoning that std-dev weighting *probably* dominates. That was not defensible: the dossier records that **2910 shipped automatic selection of the optimal camera to relocalize from**, specifically because feeding every camera into the estimator is not what top teams do, and "probably" is not an argument against shipped evidence from a team that wins. `CameraArbiter` is defined here and is built at **M16**.

```java
package org.rootstock.vision.commands;

/** Chooses which of this loop's accepted frames are handed to the pose estimator. */
@FunctionalInterface
public interface CameraArbiter {
  /** @param acceptedByCamera accepted, coalesced frames per camera index. Return what survives. */
  List<VisionFrame> select(Map<Integer, List<VisionFrame>> acceptedByCamera, VisionContext ctx);

  /** DEFAULT. Feed everything; let the std-dev model weight it. What the 2026 templates do. */
  static CameraArbiter all();

  /**
   * 2910's pattern. Score each camera's best frame this loop and feed only the winner, unless a
   * runner-up scores within `keepWithinFraction` of it (default 0.75), in which case feed both —
   * two genuinely good views from different angles constrain the pose better than either alone.
   *
   *   score = tagCount * tagSpanMeters / max(1e-3, averageTagDistanceMeters^2)
   *
   * Multi-tag beats single-tag, wide baselines beat narrow ones, close beats far, and the
   * distance term is squared to match the std-dev model so the arbiter and the weighting agree
   * rather than fight.
   */
  static CameraArbiter bestByGeometry(double keepWithinFraction);

  /** Hard priority: camera 0 while it has any accepted frame, others only when it does not.
   *  For a robot with one good camera and one desperation camera. */
  static CameraArbiter priority(int... cameraIndexOrder);
}
```

Logged as `Rootstock/Vision/Arbiter/SelectedCameras` (`long[]`), `Rootstock/Vision/Arbiter/Scores` (`double[]`), `Rootstock/Vision/Arbiter/SuppressedCount` (`long`). Which camera relocalized the robot, and why, is answerable from the log.

**Default stays `all()`.** `bestByGeometry` is a real behavior change and we will not flip a default on a hypothesis — but it is a one-line builder call, it is logged well enough to A/B on a practice field, and building it at M16 rather than leaving it as "maybe someday" means a team can actually run that A/B during a season instead of reading an open question.

### 13.7 Aim-while-moving math

The vision-side half of shoot-on-the-move: given the robot's pose, its field-relative velocity, and a static field target, find the **virtual target** you must aim at so the projectile lands on the real one. Pure math, no vision or hardware dependency, fully unit-testable off-robot.

```java
package org.rootstock.vision.commands;

/**
 * Iterative virtual-target solver. Move the TARGET backwards along the robot's drift during flight,
 * and re-solve, because time-of-flight depends on the corrected range. 3-5 iterations converge in
 * practice; 6328 run 20; 8793's ShooterSubsystem runs a hardcoded 10 with no convergence test.
 * Rootstock iterates to a TOLERANCE with a divergence guard, so it cannot sit in a limit cycle.
 */
public final class MovingTargetSolver {

  public record Solution(
      Translation3d virtualTarget,
      /** Field heading from the launch point to the virtual target. */
      Rotation2d aimHeading,
      /** d(aimHeading)/dt — feed this as a velocity feedforward so the turret LEADS the target. */
      double aimHeadingRateRadPerSec,
      double effectiveRangeMeters,
      double timeOfFlightSeconds,
      int iterations,
      boolean converged) {}

  public static Builder builder() { return new Builder(); }

  public static final class Builder {
    /** Range (m) -> time of flight (s). Team-supplied; Rootstock supplies the solver, not the numbers. */
    public Builder timeOfFlight(InterpolatingDoubleTreeMap rangeToTof);
    /** Launcher offset from robot origin. The omega x r term below is what most teams get wrong. */
    public Builder launcherOffset(Translation2d robotRelativeOffset);
    /** Exponential drag on the carried velocity: v_eff = v * (1 - exp(-tof*k)) / (tof*k). k=0 disables. */
    public Builder dragConstant(double k);
    /** Actuator lag. 6328 use 0.030 s. Advances the robot pose by this much before solving. */
    public Builder phaseDelaySeconds(double seconds);
    public Builder maxIterations(int n);                 // default 8
    public Builder toleranceMeters(double m);            // default 0.01
    public MovingTargetSolver build();
  }

  public Solution solve(Pose2d robotPose, ChassisSpeeds fieldRelativeSpeeds, Translation3d target);

  /** Latency-honest variant: solves at the pose the robot ACTUALLY had at `timestampSeconds`. */
  public Solution solveAt(double timestampSeconds, PoseProvider pose,
                          ChassisSpeeds fieldRelativeSpeeds, Translation3d target);
}
```

The algorithm, written out because it is the part everyone gets wrong:

```java
Solution solve(Pose2d robotPose, ChassisSpeeds v, Translation3d target) {
  // 1. Advance the pose by the actuator phase delay.
  Pose2d p = robotPose.exp(new Twist2d(v.vxMetersPerSecond * m_phase,
                                       v.vyMetersPerSecond * m_phase,
                                       v.omegaRadiansPerSecond * m_phase));

  // 2. The LAUNCH POINT's field velocity is NOT the chassis velocity. When the launcher is
  //    off-center, chassis rotation adds a tangential term: v_launch = v_chassis + omega x r.
  double theta = p.getRotation().getRadians();
  double rx = m_launcherOffset.getX(), ry = m_launcherOffset.getY();
  double omega = v.omegaRadiansPerSecond;
  double launchVx = v.vxMetersPerSecond + (-rx * Math.sin(theta) - ry * Math.cos(theta)) * omega;
  double launchVy = v.vyMetersPerSecond + ( rx * Math.cos(theta) - ry * Math.sin(theta)) * omega;

  Translation2d launchPoint = p.getTranslation().plus(m_launcherOffset.rotateBy(p.getRotation()));

  // 3. Fixed-point iteration on time of flight.
  Translation3d virtual = target;
  double tof = 0.0, prevRange = Double.NaN;
  int i = 0; boolean converged = false;
  for (; i < m_maxIterations; i++) {
    double range = launchPoint.getDistance(virtual.toTranslation2d());
    tof = m_tofMap.get(range);

    // Exponential drag: the projectile does not carry the robot's full velocity for the whole flight.
    double carry = (m_drag == 0.0) ? tof
                 : (1.0 - Math.exp(-tof * m_drag)) / m_drag;

    virtual = new Translation3d(target.getX() - launchVx * carry,
                                target.getY() - launchVy * carry,
                                target.getZ());

    if (!Double.isNaN(prevRange) && Math.abs(range - prevRange) < m_toleranceMeters) {
      converged = true; i++; break;
    }
    // Divergence guard: bail rather than oscillate.
    if (!Double.isNaN(prevRange) && Math.abs(range - prevRange) > 5.0) break;
    prevRange = range;
  }

  Translation2d toTarget = virtual.toTranslation2d().minus(launchPoint);
  Rotation2d aim = toTarget.getAngle();
  // Analytic heading rate: d/dt atan2(dy, dx) = (dx*vy' - dy*vx') / |d|^2, with the target static
  // and the launch point moving at (launchVx, launchVy).
  double d2 = toTarget.getNorm() * toTarget.getNorm();
  double aimRate = d2 < 1e-9 ? 0.0
      : (toTarget.getX() * (-launchVy) - toTarget.getY() * (-launchVx)) / d2;

  return new Solution(virtual, aim, aimRate, toTarget.getNorm(), tof, i, converged);
}
```

**Cross-domain seam:** the mechanism domain's shooter takes `Solution.effectiveRangeMeters()` into its own range→hood / range→RPM lookups, and `Solution.aimHeadingRateRadPerSec()` as a turret velocity feedforward. Rootstock Vision owns the geometry; the mechanism owns the ballistics table. If the mechanism domain also defines a `ShotSolver`, it must consume `MovingTargetSolver` rather than re-deriving the `omega x r` term — that is the specific piece both 8793 and the template implemented separately and that most teams get wrong.

**Gate on odometry quality.** Shoot-on-the-move multiplies pose error into miss distance: a 20 cm pose error aims 20 cm wrong at every range. `aimWhileMoving` refuses to engage (and logs `AIM_ODOMETRY_UNTRUSTED`) when `vision.hasRecentFix(0.5)` is false — which is why the factory takes a `RootstockVision`. Stopping to shoot beats confidently missing.

---

## 14. Vision Simulation

### 14.1 Architecture

PhotonVision's `VisionSystemSim` is the only production-grade FRC camera sim: it renders tag corners through real intrinsics and distortion, injects per-pixel noise, models FPS/exposure/latency distributions, and drives the *real* `PhotonCamera` NT topics so production code runs unchanged.

Rootstock routes **every** source through it — including Limelight — so `simEnabled(true)` is one boolean, not a rewrite.

```java
package org.rootstock.vision.sim;

/** Discovered through the core VisionSimHook SPI (D19/D26), never named by RootstockSim. */
public final class RootstockVisionSim implements VisionSimHook {
  /** One process-wide sim world. Every *CameraIO.simulated(...) registers into it. */
  public static RootstockVisionSim global();

  public void addAprilTags(AprilTagFieldLayout layout);
  /** VisionTargetSim(Pose3d pose, TargetModel model, int objDetClassId, float objDetConf) —
   *  verified 4-arg constructor at PhotonVision tag v2026.3.4. */
  public void addGamePiece(String id, Pose3d pose, int classId, double confidence);
  public void moveGamePiece(String id, Pose3d pose);
  public void removeGamePiece(String id);
  public void clearGamePieces();

  /** Call once per loop from simulationPeriodic with the GROUND-TRUTH pose. VisionSimHook. */
  @Override public void update(Pose2d groundTruthRobotPose);

  public Field2d debugField();                 // VisionSystemSim.getDebugField()
  public VisionSystemSim raw();                // escape hatch
}
```

`VisionSystemSim`'s surface was re-verified 2026-08-08 at tag `v2026.3.4`: `VisionSystemSim(String visionSystemName)`, `void addCamera(PhotonCameraSim, Transform3d)`, `void addAprilTags(AprilTagFieldLayout)`, `void addVisionTargets(VisionTargetSim...)`, `void addVisionTargets(String type, VisionTargetSim...)`, `Set<VisionTargetSim> removeVisionTargets(VisionTargetSim...)`, `void clearVisionTargets()`, `void update(Pose2d)` / `update(Pose3d)`, `Field2d getDebugField()`, `Pose3d getRobotPose()`, `void resetRobotPose(Pose2d)`.

### 14.2 Camera presets

PhotonVision's shipped `SimCameraProperties` factories stop at Limelight 2. Verified 2026-08-08 at tag `v2026.3.4`, the complete factory list is `PERFECT_90DEG()`, `PI4_LIFECAM_320_240()`, `PI4_LIFECAM_640_480()`, `LL2_640_480()`, `LL2_960_720()`, `LL2_1280_720()`. We add the sensors teams actually run in 2026.

**The presets live in the CORE artifact, as plain numbers.** `SimCameraProperties` is a photonlib type; if a preset returned one, `RobotContainer`'s `.simulated(...)` line would drag photonlib into every Limelight-only team's classpath and §2.6's artifact split would be fiction. So the presets return a core-owned record and `rootstock-photonvision` converts.

```java
package org.rootstock.vision;   // CORE artifact — WPILib only, no photonlib

/** A sensor model, as numbers. Everything PhotonVision's SimCameraProperties needs, none of its types. */
public record CameraSimProfile(
    int widthPx, int heightPx,
    /** DIAGONAL field of view. Converted to SimCameraProperties.setCalibration(w, h, fovDiag). */
    Rotation2d fovDiagonal,
    double calibErrorAvgPx, double calibErrorStdDevPx,
    double fps, double avgLatencyMs, double latencyStdDevMs,
    /** Fixed so sim runs are reproducible in CI. */
    long randomSeed,
    /** Documentation and diagnostics only — VisionSystemSim does not model shutter smear. */
    boolean rollingShutter) {}

/** The 2026 sensor presets. Core artifact, so these compile with no vendordep beyond WPILib. */
public final class CameraSimProfiles {
  /** OV9281 global shutter, 1280x800 @ 120 fps, H 82 deg / V 56.2 deg. LL4 and LL3G. */
  public static CameraSimProfile OV9281_1280_800_82DEG();
  /** OV9281 at 640x400. LL4 high-rate mode. */
  public static CameraSimProfile OV9281_640_400_82DEG();
  /** LL3G 240 fps mode, 640x480. */
  public static CameraSimProfile OV9281_640_480_240FPS();
  /** OV5647 color ROLLING shutter. LL3A / LL3. Models FPS and latency, NOT the shutter smear. */
  public static CameraSimProfile OV5647_640_480_54DEG();
  /** Arducam OV2311 global shutter, 1600x1200. */
  public static CameraSimProfile ARDUCAM_OV2311_1600_1200();
}
```

```java
package org.rootstock.vision.sim;   // + photonlib

/** The single translation point between our numbers and PhotonVision's type. */
public final class RootstockCameraProps {
  /**
   * Applies, in this order and nowhere else in the library:
   *   setCalibration(int resWidth, int resHeight, Rotation2d fovDiag)
   *   setCalibError(double avgErrorPx, double errorStdDevPx)
   *   setFPS(double fps)
   *   setAvgLatencyMs(double avgLatencyMs)
   *   setLatencyStdDevMs(double latencyStdDevMs)
   *   setRandomSeed(long seed)
   * All six verified at PhotonVision tag v2026.3.4; every setter returns SimCameraProperties
   * for chaining.
   */
  public static SimCameraProperties toPhoton(CameraSimProfile profile);

  /** Read a real PhotonVision config.json so sim uses your ACTUAL calibration. Best fidelity,
   *  and the only preset that cannot be expressed as a CameraSimProfile because it carries a full
   *  intrinsics matrix and distortion coefficients. Wraps the verified
   *  SimCameraProperties(Path path, int width, int height) constructor, which throws IOException. */
  public static SimCameraProperties fromPhotonConfigJson(Path configJson, int width, int height)
      throws IOException;
}
```

### 14.3 Simulating a Limelight — the part nobody has built

Limelight ships **no** simulation. A Limelight team cannot test an auto or an alignment without a robot and a field. Here is how Rootstock fixes that.

`SimulatedLimelight` stands up a hidden `PhotonCameraSim`, drains its results, and **re-encodes them into the Limelight NT wire format** on the table the production `LimelightCameraIO` is already subscribed to. Production code path is exercised unchanged, including the botpose index map and the latency math.

```java
package org.rootstock.vision.sim;

public final class SimulatedLimelight implements AutoCloseable {

  /**
   * @param limelightName the REAL NT table name the robot code reads, e.g. "limelight-front"
   * @param robotToCamera the same Transform3d the real robot uses
   * @param props         the sensor model, e.g. CameraSimProfiles.OV9281_1280_800_82DEG()
   * @param mode          which botpose keys to publish
   */
  public SimulatedLimelight(String limelightName, Transform3d robotToCamera,
                            CameraSimProfile props, LimelightCameraIO.LimelightMode mode,
                            AprilTagFieldLayout layout);   // converts via RootstockCameraProps.toPhoton

  /** Called from RootstockVisionSim.update(). */
  void update(Pose2d groundTruthRobotPose);
}
```

Implementation, in order:

1. Construct a `PhotonCamera` on a **private, unpublished NT table** name (`"__rootstocksim_" + limelightName`) so it never collides with a real camera and never shows up on a dashboard.
2. Construct `new PhotonCameraSim(camera, props, layout)` — the 3-arg constructor `PhotonCameraSim(PhotonCamera, SimCameraProperties, AprilTagFieldLayout)` is **verified at tag `v2026.3.4`** and is what makes `multitagResult` available in sim. Register it into `RootstockVisionSim.global().raw().addCamera(sim, robotToCamera)`.
3. Subscribe to `/<limelightName>/robot_orientation_set`, `/<limelightName>/pipeline`, `/<limelightName>/throttle_set`, `/<limelightName>/fiducial_id_filters_set` — **the sim honors the same control keys the real camera does**, so a pipeline switch or a tag filter behaves identically in sim.
4. Each loop, drain `camera.getAllUnreadResults()`. For each result:

   **MegaTag1 (`botpose_wpiblue`)** — from the coprocessor multitag result when present, else a single-tag PnP:
   ```java
   Optional<Pose3d> mt1 = result.getMultiTagResult()          // Optional<MultiTargetPNPResult>
       .map(m -> new Pose3d().plus(m.estimatedPose.best).relativeTo(layout.getOrigin()))
       .map(camField -> camField.transformBy(robotToCamera.inverse()));
   ```

   **MegaTag2 (`botpose_orb_wpiblue`)** — solved the way MegaTag2 conceptually does it: take the yaw the robot code just wrote into `robot_orientation_set` as *known*, and average the translation implied by each visible tag.
   ```java
   Rotation3d knownRot = new Rotation3d(0, 0, Units.degreesToRadians(orientationSub.get()[0]));
   Rotation3d camRot   = knownRot.rotateBy(robotToCamera.getRotation());
   List<Translation3d> candidates = new ArrayList<>();
   for (PhotonTrackedTarget t : result.getTargets()) {
     Optional<Pose3d> tagPose = layout.getTagPose(t.getFiducialId());
     if (tagPose.isEmpty()) continue;
     Transform3d camToTag = t.getBestCameraToTarget();
     // Field position of the camera implied by this tag, with rotation KNOWN:
     Translation3d camXyz = tagPose.get().getTranslation()
         .minus(camToTag.getTranslation().rotateBy(camRot));
     // Back out to the robot origin:
     candidates.add(camXyz.minus(robotToCamera.getTranslation().rotateBy(knownRot)));
   }
   Translation3d mt2Xyz = mean(candidates);   // MegaTag2 pose = (mt2Xyz, knownRot)
   ```

5. Pack both into the exact `11 + 7n` botpose layout of §5.2 and publish, with:
   ```java
   // Clock.now() == Timer.getTimestamp(), the AdvantageKit-injected clock. NEVER
   // Timer.getFPGATimestamp() — design/04 guarantee G2 / ArchUnit rule 3.
   double latencyMs = (Clock.now() - result.getTimestampSeconds()) * 1000.0;
   arr[6] = latencyMs;
   ```
   so `LimelightCameraIO`'s production timestamp math reconstructs the correct capture time.
6. Also publish `tv`, `tx`, `ty`, `ta`, `tid`, `tl`, `cl`, `hb` (incremented), `getpipe`, `stddevs` (12 elements, §5.2), `rawfiducials` (stride 7), `rawdetections` (stride 12) for any `VisionTargetSim` with `objDetClassId >= 0`, and **`targetpose_cameraspace` / `targetpose_robotspace` for the primary tag, encoded in LIMELIGHT camera and robot space** — i.e. the sim applies §5.2a's basis change in *reverse*. Without this, `alignToTag` has no sim coverage at all on a Limelight.

**Honest limitations, documented in the class Javadoc and surfaced as the `SIM_OPTIMISTIC` info Finding when sim is active:**

- Sim does **not** reproduce MegaTag2's real gyro-fused ambiguity resolution, or MegaTag1's ambiguity flipping. Simulated vision is **optimistic** relative to a real LL4. Do not tune ambiguity thresholds in sim.
- Rolling-shutter smear is not modeled. `OV5647_*` presets model the frame rate and latency of an LL3A but not its motion artifacts.
- Limelight's Hailo neural detector is not simulated as a detector; object detection in sim is driven by `VisionTargetSim` objects you place yourself.
- No sim for `tdist`, `rawocr`, zero-shot classification, or SnapScript pipelines.
- **The `targetpose_cameraspace` round trip is self-consistent, not vendor-verified.** `SimulatedLimelight` encodes with the same convention `LimelightCameraIO` decodes with, so `SimLimelightRoundTripTest` passing proves the two halves agree — it does **not** prove either half matches a real Limelight. §5.2a, §21 OQ 13, and R2's hardware gate.

### 14.4 One-boolean wiring

```java
VisionCameraIO frontIo = LimelightCameraIO.megaTag2("limelight-front", TF_FRONT)
    .withImuMode(4)
    .simulated(CameraSimProfiles.OV9281_1280_800_82DEG());   // no-op on a real robot
```

`.simulated(...)` records the profile — a core-owned record of plain numbers, so **this line compiles with no photonlib on the classpath**. `RootstockVision.build()` inspects `Platform.isSimulation()` (the confined accessor; ArchUnit rules 2 and 12 forbid `RobotBase.isSimulation()` outside `compat`) and, only then, reflectively instantiates `RootstockVisionSim` from `rootstock-photonvision` (§2.6). If that artifact is absent the builder raises the named warning and runs with no simulated cameras. No ternary in `RobotContainer`, no second IO class to write, no code path that exists only in sim, and no vendordep a Limelight-only team did not ask for.

`RootstockVisionSim.global().update(groundTruthPose)` is called by the drive domain's `simulationPeriodic` through the `VisionSimHook` SPI. If the team is using maple-sim, ground truth is the maple-sim robot pose; otherwise it is the drivetrain's own sim pose.

---

## 15. End-to-End Usage Example

A four-camera, three-vendor, fully filtered, fully logged, fully simulated vision system. This is the entire amount of vision code a team writes.

```java
package frc.robot;

import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.math.util.Units;
import org.rootstock.vision.*;
import org.rootstock.vision.commands.*;
import org.rootstock.vision.custom.CustomNTCameraIO;
import org.rootstock.vision.field.FieldLayouts;
import org.rootstock.vision.filter.VisionFilters;
import org.rootstock.vision.limelight.LimelightCameraIO;
import org.rootstock.vision.photon.PhotonCameraIO;      // rootstock-photonvision artifact
import org.rootstock.vision.stddev.StdDevModels;
// NOTE: no org.rootstock.vision.sim import and no org.photonvision import anywhere in this file.
// CameraSimProfiles is in the core artifact (section 14.2), which is what lets a Limelight-only
// team delete the two PhotonCameraIO lines below and drop the photonlib vendordep entirely.

public class RobotContainer {

  // ---- Camera mounts. In code, so sim, replay, logging and the dashboard all agree. -------------
  private static final Transform3d TF_FRONT = new Transform3d(
      new Translation3d(Units.inchesToMeters(11.5), 0.0, Units.inchesToMeters(8.25)),
      new Rotation3d(0.0, Units.degreesToRadians(-12.0), 0.0));

  private static final Transform3d TF_FRONT_LEFT = new Transform3d(
      new Translation3d(Units.inchesToMeters(9.0), Units.inchesToMeters(9.0), Units.inchesToMeters(7.5)),
      new Rotation3d(0.0, Units.degreesToRadians(-12.0), Units.degreesToRadians(70.0)));

  private static final Transform3d TF_FRONT_RIGHT = new Transform3d(
      new Translation3d(Units.inchesToMeters(9.0), Units.inchesToMeters(-9.0), Units.inchesToMeters(7.5)),
      new Rotation3d(0.0, Units.degreesToRadians(-12.0), Units.degreesToRadians(-70.0)));

  private static final Transform3d TF_REAR = new Transform3d(
      new Translation3d(Units.inchesToMeters(-10.0), 0.0, Units.inchesToMeters(9.0)),
      new Rotation3d(0.0, Units.degreesToRadians(-15.0), Math.PI));

  private final Drive m_drive = new Drive();          // your drivetrain; implements PoseProvider
  private final RootstockVision m_vision;

  public RobotContainer() {
    m_vision = RootstockVision.builder()
        // deploy/rootstock/field-layout.json wins if present; otherwise this. No default.
        .layout(FieldLayouts.resolve(AprilTagFields.k2026RebuiltWelded))
        .poseProvider(m_drive)

        // A Limelight 4 doing MegaTag2, with the transform pushed to the camera, IMU mode 4.
        .camera(LimelightCameraIO.megaTag2("limelight-front", TF_FRONT)
                    .withImuMode(4)
                    .simulated(CameraSimProfiles.OV9281_1280_800_82DEG()))

        // Two PhotonVision cameras on the 2026 API.
        .camera(PhotonCameraIO.coprocMultiTag("front-left", TF_FRONT_LEFT)
                    .simulated(CameraSimProfiles.ARDUCAM_OV2311_1600_1200()))
        .camera(PhotonCameraIO.coprocMultiTag("front-right", TF_FRONT_RIGHT)
                    .simulated(CameraSimProfiles.ARDUCAM_OV2311_1600_1200()))

        // A homebrew Orange Pi running Northstar, decoded with zero changes on the Pi.
        .camera(CustomNTCameraIO.northstar("northstar-rear", TF_REAR))

        .filter(VisionFilters.standard())
        .stdDevs(StdDevModels.rootstockDefault())
        // Binding D17: the sink is VisionConsumer.accept(Pose2d, double, Matrix<N3,N1>),
        // which is exactly addVisionMeasurement's signature.
        .consumer(m_drive.getPoseEstimator()::addVisionMeasurement)

        // Frame budget (section 9.6). These are the defaults, written out once so they are visible.
        .maxFramesPerLoop(4)        // decode cap, per camera
        .maxAcceptedPerLoop(2)      // measurements handed to the estimator, per camera

        // Let vision seed the pose (and therefore the gyro->field offset) while DISABLED only.
        // This is the only vision path allowed to touch that offset — section 2.2, section 9.7.
        .disabledSeedConsumer(m_drive::resetPose)
        .seedWhileDisabled(true)

        .simEnabled(Robot.isSimulation())
        .build();
  }

  public void periodic() {
    m_vision.periodic();
  }

  // ---- Vision-driven actions -------------------------------------------------------------------
  private void configureBindings(CommandXboxController driver) {

    // One button: pathfind to the nearest blue-authored scoring pose, hand off to the terminal
    // controller at 1.2 m, refuse to finish on a stale fix.
    driver.rightBumper().whileTrue(
        VisionCommands.alignToNearest(
            m_drive, m_drive, m_vision, FieldTargets.BLUE_SCORING_POSES,
            new Transform2d(0.45, 0.0, Rotation2d.k180deg),   // stand 45 cm off, facing it
            new PathConstraints(3.5, 4.0, Units.degreesToRadians(540), Units.degreesToRadians(720)),
            1.2, AlignGains.defaults(), VisionFreshness.defaults()));

    // Terminal 2 cm alignment, closed on the TAG rather than the fused pose (section 13.5).
    // The 45 cm standoff is written once, in the tag's frame, and is correct at every scoring face.
    // This is the command that gets a small team the same cycle quality as a top team.
    driver.rightTrigger().whileTrue(
        VisionCommands.alignToTag(
            m_drive, m_vision, /*cameraIndex=*/0,
            FieldTargets.BLUE_AND_RED_SCORING_TAGS,
            new Transform3d(new Translation3d(0.45, 0.0, 0.0),
                            new Rotation3d(0.0, 0.0, Math.PI)),   // TAG -> desired robot origin
            AlignGains.tagRelative()));

    // Driver keeps translation; we own heading, leading the target for the shot.
    driver.leftBumper().whileTrue(
        VisionCommands.aimWhileMoving(
            m_drive, m_drive, m_vision, m_shotSolver, () -> FieldTargets.HUB_CENTER,
            () -> -driver.getLeftY(), () -> -driver.getLeftX(), AlignGains.defaults()));

    // Chase the nearest tracked FUEL and run the intake.
    driver.a().whileTrue(
        VisionCommands.autoIntake(
            m_drive, m_vision, m_drive, /*classId=*/0,
            m_intake.intakeCommand(), m_intake::hasPiece,
            AlignGains.defaults(), 3.0));
  }
}
```

Simulation, in `Robot.java`:

```java
@Override public void simulationPeriodic() {
  RootstockVisionSim.global().update(m_drive.getSimGroundTruthPose());
}
```

That is everything. Four cameras, three vendors plus sim, correct timestamps, a named reason for every rejected frame, distance²-scaled std devs with the MegaTag2 heading rule enforced structurally and a finite untrusted sentinel that cannot become `NaN`, a bounded frame budget that cannot spiral, layout-mismatch detection, tag residual health, object tracking, and four ready-to-use commands including tag-relative 2 cm alignment. Adding a fifth camera of a fourth brand is one `.camera(...)` line.

**What a Limelight-only team writes instead:** delete the two `PhotonCameraIO` lines and the `CustomNTCameraIO` line, delete the `rootstock-photonvision` vendordep, keep everything else including `.simulated(...)`. They still get the filter chain, the std-dev models, the diagnostics, layout management, object projection, `alignToTag`, and — with the one extra `rootstock-photonvision` vendordep — a simulated Limelight, which Limelight itself does not ship.

---

## 16. Testing

Pure-math surface is HAL-free and unit-tested off-robot (this was the single highest-leverage constraint in the 8793 dossier — the highest-risk math there is untestable):

| Test | Asserts |
|---|---|
| `BotposeDecodeTest` | A synthetic `11 + 7n` array decodes to the expected `VisionFrame`, index by index against the §5.2 table; a 9-element array is rejected as `MALFORMED_FRAME`; first-tag ambiguity is read from index **17**. |
| `LimelightTimestampTest` | `ts = sampleMicros*1e-6 - latencyMs*1e-3` for a table of known inputs. |
| **`LimelightCameraSpaceConversionTest`** | **(new, revision 4)** A tag placed 2 m ahead, 0.5 m left and 0.3 m below the lens in WPILib camera space encodes to `targetpose_cameraspace = [-0.5, 0.3, 2.0, ...]` in Limelight camera space and decodes back to within 1 mm. Pins `x_wpi = z_ll`, `y_wpi = -x_ll`, `z_wpi = -y_ll`. The **rotation** half is a `@Disabled` placeholder carrying the `[UNVERIFIED]` tag until the M10 hardware gate resolves §21 OQ 13 — a disabled test with a named reason, not a silently missing one. |
| `ObjectProjectionTest` | The same synthetic scene, encoded as a Limelight detection and as a PhotonVision target, projects to field positions within 1 mm. Pins the tx sign convention. |
| `FilterChainTest` | Each filter fires its own `RejectReason` and only its own; `and()` reports the **first** rejection. |
| `StdDevModelTest` | `rootstockDefault()` reproduces the AdvantageKit template's numbers exactly at d ∈ {1, 2, 4} and n ∈ {1, 2, 3}, **including the §8.2 worked case: d = 3, n = 2, gyro-fused → sigmaXY = 0.045 m**; every gyro-fused source yields `sigmaTheta == StdDevModels.UNTRUSTED_SIGMA` (finite, never `Infinity`) even when the supplied model does not. |
| `StdDevNaNGuardTest` | **The season-ending bug, pinned.** `cameraFactor = 0.0` on a gyro-fused frame yields a finite sigma, not `NaN`. A deliberately hostile model writing `{NaN, 0.0, Infinity}` produces a `RejectReason.CUSTOM` rejection, never reaches `addVisionMeasurement`, and leaves the estimator's pose finite. Asserts the ordering too: scaling by `1e9` then pinning leaves the pin intact. |
| `StdDevAllocationTest` | `compute(frame, ctx, out)` allocates zero bytes across 10 000 calls (the zero-allocation CI gate), and `withAngularDisabled()` does not mutate any matrix the wrapped model retains. |
| `FrameBudgetTest` | 20 queued frames on one camera produce at most `maxFramesPerLoop` decodes and at most `maxAcceptedPerLoop` `addVisionMeasurement` calls; the kept frames are the **oldest and the newest**; `CoalescedCount` and `DecodeDroppedCount` account for every dropped frame exactly once; the NT queue is fully drained regardless. |
| **`DriveToPoseDirectionTest.drivesTowardTheTarget`** | **(new, revision 4 — THE sign test for §13.4.)** Robot at `(0,0,0°)`, target at `(1,0,0°)`. After `initialize()` + one `execute()`, the commanded **field-relative** `vx > 0.1` and `vy == 0` to 1e-6. Failure message names the sign convention and points at `design/05` §9.1, exactly as `design/05` §9.2 does. |
| **`DriveToPoseDirectionTest.drivesTowardTheTargetOffAxis`** | **(new)** Robot at `(2,3)`, target at `(1,4)` — a 135° bearing, so no lucky axis sign can pass it. The commanded velocity's component along the unit robot→target vector must be `> 0.1`. |
| **`AlignToTagDirectionTest.drivesTowardTheTag`** | **(new — THE sign test for §13.5, the flagship.)** Synthetic `bestCameraToTarget` placing the tag 1 m directly ahead with an identity mount and a `tagRelativeGoal` of 0.45 m standoff: the commanded **robot-relative** `vx > 0.1`. A rotated variant (tag 30° off the nose) asserts the commanded vector's component along the robot→goal direction is positive. This is the assertion the previously-specified `AlignToTagTest` did not make, and it is the one that would have caught revision 3. |
| **`AlignDeadZoneTest.hasNoDeadZoneAboveTolerance`** | **(new)** For both controllers, at an error of `tolerance + 1 mm` the commanded speed is strictly non-zero; at `tolerance − 1 mm` it is exactly zero. Pins that `ffScale` no longer touches the feedback term — the defect where `ffMinRadius == toleranceMeters` made the command unable to converge to its own tolerance. |
| **`AlignSeedingTest.seedsTheProfileFromMeasuredClosingVelocity`** | **(new)** A drive already closing at 3 m/s, one meter out: after `initialize()` + `execute()` the commanded velocity must not reverse. Pins that `m_translationController.reset(errorNorm, distanceRate)` is called and that `distanceRate` is clamped at 0 for outbound motion. |
| `AlignToTagTest` | (retained, unchanged in intent) A synthetic `bestCameraToTarget` plus a mount transform and a `tagRelativeGoal` produce the correct robot-frame **error**, verified against a hand-computed case at a non-zero robot heading (the case where `Translation3d` arithmetic instead of `Transform3d.plus` silently gives the wrong answer). Error is invariant when the fused pose is perturbed by 1 m — that invariance IS the feature. **This test pins error computation only; the three direction/dead-zone tests above pin the commanded velocity, which is what revision 3 got wrong.** |
| `AlignTagFilterRestoreTest` | The camera's tag-id filter is restored on `end(true)` (interrupt) as well as `end(false)`. |
| `DisabledSeedTest` | A `MEGATAG_2` frame never seeds. A single-tag frame never seeds. Three disagreeing multi-tag frames never seed. Three agreeing ones seed exactly once. Seeding while enabled is impossible. |
| `ArtifactIsolationTest` | Compiles the core jar (`rootstock`) against a classpath with photonlib **absent**, and asserts no class outside `org.rootstock.vision.photon` / `.sim` imports `org.photonvision.*`. Also asserts `.simulated(CameraSimProfiles.OV9281_1280_800_82DEG())` compiles and runs (as a no-op) in that classpath. |
| `VisionArchUnitTest` | No class in `org.rootstock.vision..` names `edu.wpi.first.wpilibj.DriverStation` (rule 10), `RobotBase` (rules 2/12), or `Timer.getFPGATimestamp` (rule 3/G2). This is the test that keeps §9.7, §11.4 and §14.3 honest after the revision-4 migration. |
| **`VisionAlertBudgetTest`** | **(new)** A synthetic four-camera robot with every §11.3 check failing raises exactly **two** `BLOCKS_MATCH` alerts (§11.3a), and every other finding is still present in `VisionDiagnostics.run()`. **All twenty checks are enumerated by this test**, so adding a check without classifying its `MatchImpact` fails the build — which is what makes `NETWORK_TRANSIT_HIGH`'s `PIT_ONLY` a decision rather than a default. |
| **`NetworkTransitCheckTest`** | **(new, `DESIGN.md` §16 item 8)** A camera fed a synthetic `NetworkTransitEstimateSecs` series raises `NETWORK_TRANSIT_HIGH` when the 5 s rolling p95 crosses **10 ms** and not at 9 ms; the finding is `Severity.WARN` / `MatchImpact.PIT_ONLY` and names the camera; it is **per camera**, so one bad camera among four produces exactly one finding; and it raises **zero** `BLOCKS_MATCH` alerts, so `VisionAlertBudgetTest`'s count of two is unchanged. Asserts alongside that `HealthMonitor.builtinTypeCount() == 7` and `builtinSliceCount() == 8` — the check is deliberately *not* an eighth core monitor. |
| `MovingTargetSolverTest` | Zero velocity → virtual target == real target. Convergence within tolerance in ≤ 8 iterations for a realistic ToF map. The `omega x r` term produces the correct launch-point velocity for an off-center launcher (pinned against a hand-computed case). |
| `LayoutFingerprintTest` | Welded and Andymark 2026 layouts produce different fingerprints; reordering tags produces the same fingerprint. |
| `AllianceOriginTest` | No Rootstock code path calls `AprilTagFieldLayout.setOrigin`; the periodic origin assertion fires when a test mutates it. |
| `SimLimelightRoundTripTest` | `SimulatedLimelight` publishes, `LimelightCameraIO` decodes, and the resulting robot pose matches the ground-truth pose within 3 cm on a static scene with 3 tags in view. **Read §14.3's last limitation before trusting it**: it proves the two halves agree with each other, not that either matches a real Limelight. |

`SimLimelightRoundTripTest` is the important one for the sim claim: it proves the sim exercises the *production* decode path, which is the entire justification for the design. **`DriveToPoseDirectionTest` and `AlignToTagDirectionTest` are the important ones for the alignment claim**, and they are the direct analogs of `design/05` §9.2's `drivesTowardTheTarget` / `drivesTowardTheTargetOffAxis`. If any of the four new tests is deleted or weakened, the sign bug comes back — it already came back once, in a document written *after* `design/05` §9.1 fixed it.

---

## 17. What We Deliberately Do NOT Do

| We do not build | Because this already does it | What we do instead |
|---|---|---|
| An AprilTag detector, or any on-RIO CV | **PhotonVision**, **Limelight OS**. WPILib's own guidance and every elite team put detection on a coprocessor; RIO-side detection blows the 20 ms control loop. | Consume results only. |
| A pose estimator / Kalman filter | **WPILib `SwerveDrivePoseEstimator` / `DifferentialDrivePoseEstimator`** | Feed them correctly, guard the buffer, log accepted and rejected. (6328 do run their own; that is a Drive-domain decision, not a Vision one.) |
| A camera calibration tool | **PhotonVision's calibration UI**, **mrcal**, **WPIcal** | Ship a checklist and a `VisionDiagnostics` health check that reads the resulting config and flags reprojection error, FOV mismatch, and resolution mismatch. |
| A field-tag calibration tool | **WPIcal** (ships with WPILib 2026; emits both a WPILib layout JSON and a Limelight `.fmap`) | `FieldLayouts.fromWpical(Path)`, a deploy convention, and `TagResidualMonitor` so you can *see* which tag is off. |
| A camera simulator | **PhotonVision `VisionSystemSim`** — real intrinsics, distortion, per-pixel noise, FPS/latency distributions | Wrap it, add 2026 sensor presets, and route Limelight through it (§14.3). This wrapper is the novel part; the renderer is not. |
| A path planner or pathfinder | **PathPlannerLib** `AutoBuilder.pathfindToPose`, **Choreo** | Compose `pathfindToPose` for the approach phase and add the terminal controller and freshness gate PathPlanner deliberately does not have. |
| A logging framework or a replay engine | **AdvantageKit**, **AdvantageScope**, WPILib DataLog, CTRE SignalLogger | Call `RootstockLog`; log the leaf names AdvantageScope layouts already expect. |
| A dashboard or a 3D viewer | **AdvantageScope**, **Elastic** | Publish stable NT keys and a reject-reason histogram the telemetry domain renders. |
| An alert registry | **`org.rootstock.core.alert`** (domain 06, binding D10) | Call `Alerts.error/warning/info(group, text, MatchImpact)` and answer the match-impact question at every site (§11.3a). |
| A tunable type | **`TuningRegistry`** (domain 02, binding D11) | Call `TuningRegistry.tunable("Vision", key, default, unit)`. |
| A neural-network trainer | **Limelight's free H100-backed trainer**, **PhotonVision's Colab conversion notebook** | Decode both vendors' detector output into one `DetectedObject`. Models are not portable and we say so. |
| A Limelight NT wrapper | **`LimelightHelpers.java`** (LimelightLib-WPIJava 1.14) | Vendor it verbatim so teams stop copy-pasting a 1,900-line file, expose it as an escape hatch, and use raw NT `readQueue()` on the hot path because `getBotPoseEstimate_*` drops frames and `getLatestResults()` parses JSON on the RIO. |
| A ballistics model | The mechanism domain's shot tables; the team's own measurements | `MovingTargetSolver` supplies the virtual-target geometry only. Rootstock supplies the solver and the tuning UI, never the numbers. |
| A replacement for `VisionSystemSim`'s renderer, or a Limelight firmware emulator | — | We re-encode PhotonVision's simulated output into the Limelight wire format. We simulate the *interface*, not the device. |

---

## 18. 2027 Migration

WPILib 2027 renames every Java package `edu.wpi.first.*` → `org.wpilib.*`, drops NT3, moves to SystemCore and Java 25, and replaces the command framework with Commands v3. AdvantageKit's main-branch templates are already on `org.wpilib.*`.

Vision's plan:

1. **The math is already portable.** `MovingTargetSolver`, `ObjectProjection`, `StdDevModels`, the botpose decoder, `LimelightCameraSpace`, the filter predicates, and `LayoutFingerprint` depend only on geometry types and `Matrix`. A package rename is mechanical for them, and they contain no `Command`.
2. **Commands are quarantined.** Only `org.rootstock.vision.commands` imports `edu.wpi.first.wpilibj2.command.*`. That is one package to rewrite for Commands v3, and the terminal-controller math inside it is already a plain class with an `execute()`-shaped method that a coroutine can call.
3. **No NT3 anywhere.** Every publisher/subscriber uses the NT4 typed topic API (`getDoubleArrayTopic(...).subscribe(...)` with `PubSubOption`), which survives.
4. **No Shuffleboard, no SmartDashboard.** `VisionDiagnostics.publishToNT` writes plain NT4 topics. Both of those dashboards are deleted in 2027.
5. **Two artifacts, ONE source line, one source tree.** The vision packages ride inside `rootstock`; `rootstock-photonvision` is the only vision-relevant adapter (§2.6). **Revision 3 correction:** there is no permanent `2026.x` / `2027.x` pair. Under [`ROADMAP.md` §7.2 rule 3](../ROADMAP.md), the 2027 port is **M12**, the generated-source variant and dual-compile CI exist **only** inside M12's transition window, and the 2026 line and the generator are **deleted at the end of M12**. The project is single-line after that, because there are no external users on the 2026 line to protect. What survives from the argument below is the *shape* of the port, not the two-branch plan: the vision packages inside the core jar depend on no camera vendor, so they are a pure mechanical rename; `rootstock-photonvision` is the only piece whose port is blocked on a vendor, and it is one enum, one adapter and three sim classes.
6. **Java-17-safe subset now** so the port is mechanical: no pattern matching for `switch`, no record patterns, no sealed-interface exhaustiveness tricks.
7. Assume **PathPlanner and Choreo both break in 2027** (both deliberately froze 2026). `VisionCommands.driveToPose` touches PathPlanner in exactly one place, behind a `PathfindingBackend` interface with a `NoPathfinding` fallback.
8. **Revision-4 addition: the PhotonVision port is already partly visible and it is not free.** PhotonVision's 2027 alpha has **removed** the members 2026.3.4 merely deprecates — the 3-arg `PhotonPoseEstimator` constructor, `update()`, `setPrimaryStrategy`, `setMultiTagFallbackStrategy`, `setReferencePose`, `setLastPose`. Rootstock already uses none of them (§6.2), so our port is a rename, but **any team escape-hatching through `PhotonCameraIO.estimator()` onto those methods will not compile in 2027**. The `estimator()` javadoc says so.

---

## 19. Effort & Phasing

> **⛔ The four-phase release plan below is DELETED as a release plan (maintainer decision 1).** There is **one** release, `v0.1`, and it contains **all** of this domain — including the custom-coprocessor path and the Python package that the old table called an "offseason stretch." What survives is **build order**, expressed as the milestones in [`ROADMAP.md` §5](../ROADMAP.md), which is authoritative for every date. The old phase names are kept in the left column only so a reader of revision 2 can find their way across.

| Was called | Now built at | Contents | Person-weeks |
|---|---|---|---|
| "v0.1" | **M10 — Vision core: Limelight** | `VisionFrame` + `VisionFrameHeader`, `TargetObservation`, `PoseSource`, `CameraSimProfile`/`CameraSimProfiles`, `VisionCameraIO`, `LimelightCameraIO` (MT1/MT2/BOTH) **+ `LimelightCameraSpace` (§5.2a)**, `ReplayCameraIO`, filter chain + `RejectReason` + `standard()`, `StdDevModels` (3 presets) + the `UNTRUSTED_SIGMA`/`sanitizeStdDevs` guard, `RootstockVision` builder + the §9.6 frame budget + the §9.7 disabled seed, `FieldLayouts.resolve/fingerprint`, the full log key set, `VisionDiagnostics` (11 of 20 checks — `NETWORK_TRANSIT_HIGH` is in this slice, because M10 already builds the `NetworkTransitEstimateSecs` key it reads) + the §11.3a roll-up, **`VisionCommands.alignToTag` + `AlignableDrive` + `AlignGains` + the four §16 direction/dead-zone/seeding tests**. | **4.4** |
| "v0.2" | **M16 — Vision, advanced sources** | `PhotonCameraIO` (2026 API) + `PhotonStrategy`, `CustomNTCameraIO` + `NorthstarSchema`, `DetectedObject` + `ObjectProjection` + `ObjectTracker`, `TagResidualMonitor` + layout-mismatch detection, `VisionCommands.driveToPose` / `alignToNearest` / `aimAtPoint` / `setPipeline`, **`CameraArbiter` (§13.6)**, remaining diagnostics. | **3.3** |
| "v0.3" | **M17 — `SimulatedLimelight` + custom coprocessors** | `RootstockVisionSim` + `RootstockCameraProps` + `SimulatedLimelight`, `RootstockV1Schema` + `Struct` implementations, `SnapScriptChannel`, `AdvantageKitCompat`, match recording, throttling. | **2.5** |
| — | **M18 — Shoot-on-the-move** | `MovingTargetSolver` + `aimWhileMoving`, `autoIntake`. | **1.5** |
| "v0.4 (offseason stretch)" | **M17 (same milestone)** — *no longer a stretch, and no longer optional* | `rootstock_vision` Python package for coprocessors, WPIcal deploy tooling, calibration-JSON ingestion for the reprojection/FOV checks. | **2.0** |
| **Total (this domain's rows, added)** | | `4.4 + 3.3 + 2.5 + 1.5 + 2.0` | **13.7 person-weeks** |

> ### Reconciling this table's arithmetic — revision-4 correction
>
> **Revision 3's total row said 12.2 and its own rows summed to 13.7.** The table a reader would use to check the roll-up did not add up against itself. Fixed by making the total the actual sum, with the addition shown:
>
> ```
> 4.4  (M10)
> 3.3  (M16)
> 2.5  (M17, sim + coprocessors)
> 1.5  (M18)
> 2.0  (M17, Python package + WPIcal tooling)
> ----
> 13.7 person-weeks
> ```
>
> **Against `ROADMAP.md` §5**, which is authoritative for every milestone number and every date:
>
> | Milestone | This table | `ROADMAP.md` §5 | Delta |
> |---|---:|---:|---:|
> | M10 Vision core | 4.4 | 4.0 | +0.4 |
> | M16 Vision advanced | 3.3 | 4.5 | −1.2 |
> | M17 SimulatedLimelight (2.5 + 2.0) | 4.5 | 3.5 | +1.0 |
> | M18 Shoot-on-the-move | 1.5 | 1.5 | 0.0 |
> | **Total** | **13.7** | **13.5** | **+0.2** |
>
> `4.0 + 4.5 + 3.5 + 1.5 = 13.5`, and `+0.4 − 1.2 + 1.0 + 0.0 = +0.2 = 13.7 − 13.5`. The two columns are internally consistent; they allocate the same work differently across milestones, and the 0.2 pw gap is the redistribution of integration savings across all six domains that `ROADMAP.md` §1 performs. **`ROADMAP.md` wins** wherever they differ.
>
> **What happened to 12.2, honestly.** It was revision 2's pre-adversarial-review figure, and it is the number `DESIGN.md` §1's raw roll-up (71–82 pw) consumed. Backing out the two additions revision 2 itself documents — `+0.4` for pulling `alignToTag` into M10 and `+0.3` for specifying `CameraArbiter` — gives `13.7 − 0.4 − 0.3 = 13.0`, not 12.2. The remaining **0.8 pw is unexplained**: no row in any revision of this document reconstructs it. Rather than invent a decomposition, **12.2 is retired.** It should not be cited again; if `DESIGN.md` §1's roll-up needs a vision input it is 13.7 (this document's rows) or 13.5 (`ROADMAP.md`'s allocation), and the difference between those two is 0.2 pw, which is inside anyone's error bar.
>
> **Revision 4's own corrections add no person-weeks to this table.** The controller rewrite (§13.4, §13.5) replaces work already budgeted — a wrong controller is not cheaper to build than a right one — and the four new regression tests, `VisionAlertBudgetTest`, `VisionArchUnitTest` and `LimelightCameraSpaceConversionTest` are roughly 0.1 pw absorbed inside M10's existing 4.4. `LimelightCameraSpace` itself is ~30 lines. Claiming a fix is free would be dishonest if it were large; this one is not.

**Why `alignToTag` is built early (M10) rather than after the rest, for +0.4 person-weeks.** The headline claim is that a small team gets competitive scoring alignment. Without a tag-relative controller, M10 would deliver only fused-pose alignment, which is bounded below by the vision standard deviation at scoring range — `0.02 × 3.0² / 2 × 0.5 = 0.045 m` at 3 m with two tags on MegaTag2 (§8.2). A team would have adopted the vision layer, set a 2 cm tolerance because that is what the mechanism needs, and gotten a timeout alert on every alignment. Shipping perception without the one controller that can consume it correctly is shipping the claim without the capability. `TargetObservation` already carries `bestCameraToTarget` and the mount transform is already in `VisionCameraIO`, so the marginal work is a controller and a test, not new perception. `CameraArbiter` follows at M16 for +0.3.

**When this domain actually lands, not softened.** At solo pace M10 completes **2027-11-05**, M16 **2029-01-06**, M17 **2029-03-04** and M18 **2029-03-29** ([`ROADMAP.md` §5.1](../ROADMAP.md)). At +2 committers those become 2027-03-22, 2027-10-22, 2027-11-20 and 2027-12-03. Revision 2's line — *"v0.1 by late September 2026, v0.3 by mid-November, leaving December for integration and January for the 2027 port"* — was written against a 13-pw first release and a dated December 2026 target. **Both are gone.** Nothing in this domain reaches a tagged release before **M24**; 8793 and 9143 get M10 as an internal snapshot and that is the only 2027-season delivery this domain has.

---

## 20. Risks

1. **The photonlib fence is real complexity, and CI is the only thing keeping it honest.** ~~PhotonVision is a hard dependency even for Limelight-only teams~~ — that was the earlier draft's position and it was wrong (§2.6). It violated Principle 5 and Principle 12, and it made our ship date hostage to PhotonVision's. The fence fixes it, and introduces its own risk: two Maven coordinates and one `CameraSimProfile → SimCameraProperties` translation point that exists only to keep photonlib out of the core jar's type signatures. If nobody maintains that boundary it will rot the first time someone finds it convenient to accept a `SimCameraProperties` in core. Mitigation: `ArtifactIsolationTest` (§16) compiles the core jar against a photonlib-free classpath on every CI run and fails the build on any `org.photonvision.*` import outside the two designated packages. The vendor-neutrality claim is checked mechanically or it is not a claim. Residual risk: a Limelight-only team that wants *camera simulation* still installs one photonlib-bearing vendordep (`Rootstock-PhotonVision.json`). That is an honest, opt-in, sim-only cost, and the alternative is writing a camera renderer, which we will not do. **What the fence no longer buys is a "no third-party `requires`" install story** — `Rootstock.json` requires `AdvantageKit.json` regardless (maintainer decision 3), so the fence is now about *camera* vendor neutrality only.
2. **`SimulatedLimelight` is genuinely novel and therefore genuinely unproven.** The MegaTag2 reconstruction in §14.3 is my own derivation of what MegaTag2 does conceptually, not a vendor-documented algorithm. It will be optimistic relative to the real device. `SimLimelightRoundTripTest` pins the round trip, but only against my own encoder — and revision 4 adds a second thing it cannot prove: §5.2a's camera-space rotation convention, which the sim encodes and decodes with the same `[UNVERIFIED]` reading. Mitigation: publish the limitation prominently; validate against a real LL4 on a practice field — an **explicit, non-negotiable M17 gate** (R7), not a good intention with a month attached — and pull the *camera-space* half of that validation forward to the **M10** gate, because `alignToTag` ships at M10 and depends on it.
3. **The `rootstockV1` wire schema has no adopters on day one.** Its value is the layout-hash handshake and correct timestamping, but a team with a working Northstar has no reason to migrate. Mitigation: ship `NorthstarSchema` first and make `rootstockV1` the *new-coprocessor* path, not a migration ask.
4. **Filter-chain tuning could become the new footgun.** Fifteen filters with fifteen tunable thresholds is more rope than one boolean expression. Mitigation: `standard()` is the documented default and the diagnostics name the *dominant* reject reason, so a team is pushed toward "why is this one reason firing" rather than "let me loosen everything."
5. **Timestamp correctness cannot be verified without hardware.** Every claim in §5 is derived from vendor docs and from AdvantageKit's shipped implementation. A 20 ms systematic error would be invisible in sim and cost 8 cm at 4 m/s on the field. Mitigation: `Rootstock/Vision/<name>/LatencySec` and `NetworkTransitEstimateSecs` are logged every frame so the numbers are auditable in a real log; the odometry-vs-vision disagreement statistic in `VisionDiagnostics` is a direct empirical check; and **as of 2026-08-08 the transit half is no longer log-only — `NETWORK_TRANSIT_HIGH` (§11.3) alerts above a 10 ms p95**, which is the difference between a number a team could have read and a number a team is told about.
6. **~~2027 lands in ~4 months.~~ REWRITTEN under maintainer decision 1 — the risk inverted.** The old risk was "the port slips and Vision ships one usable season." There is now **no 2026 release at all**, so there is no 2026 season to protect and nothing to quarantine *for*. The 2027 port is **M12**, the only date-triggered milestone: it arms at the first confirmed 2027 alpha (~Oct 2027), must complete inside the beta window, and at solo pace **preempts M11** ([`ROADMAP.md` §7.2](../ROADMAP.md)). The 2026 source line and the rename generator are **deleted at the end of M12** — the project is single-line afterwards, not dual-line, because there are no external users on the 2026 line. Do **not** start the port on an unconfirmed alpha. The real residual risk here is the *opposite* one: at solo pace this domain's own milestones (M16–M18) land in 2029, so vision is written twice-removed from the WPILib line it was designed against, and R20 (relevance decay) applies to it directly.
7. **Layout-mismatch detection is statistical, not certain**, for both vendors (§10.4). A team could still run a mismatched layout for a whole event if their residuals are noisy. Mitigation: the `TAG_NOT_IN_LAYOUT` canary catches the common case immediately, and `rootstockV1` makes it certain for custom coprocessors. Push vendors for a layout-identity NT key.
8. **`maxAcceptedPerLoop = 2` is a judgment call made from arithmetic, not from a robot.** The §9.6 reasoning about `addVisionMeasurement`'s odometry replay is structurally sound and the old default of 20 was indefensible, but the claim that the third and later measurements in a 20 ms window add negligible information is an argument, not a measurement. If it is wrong, we are throwing away real corrections at exactly the moment a team most needs them. Mitigation: nothing is hidden — `CoalescedCount` counts every discarded measurement, `Rootstock/Perf/Vision/ConsumeMs` shows what the cap bought, and the cap is one builder call to raise. Measure it on a real robot with a 250 Hz Phoenix odometry thread as part of the M10 gate and revise the default if the data disagrees; the API freeze is not until M24, so the default is still free to move.
9. **`alignToTag` is only as good as `bestCameraToTarget`, and Limelight populates it for the primary tag only** (§6.1). A Limelight team's tag-relative alignment therefore depends on one `targetpose_cameraspace` read and stops the moment the primary tag changes or is occluded. PhotonVision supplies it per target and has no such limitation. Mitigation: the command holds rather than extrapolating, ends with a named `ALIGN_TAG_LOST` after 0.5 s, and the asymmetry is documented rather than smoothed over. A Limelight team that wants robust tag-relative alignment should lock a single tag id via `acceptableTagIds`. **Revision 4 adds a second, sharper edge to the same risk:** that single read also crosses a coordinate-frame boundary whose rotation half is `[UNVERIFIED]` (§5.2a). PhotonVision cameras cross no boundary at all.
10. ***(new, revision 4)*** **Vendor documentation drifts under a stable URL, and this document was already burned by it.** Revision 2's PhotonVision verification note cited `javadocs.photonvision.org/release/`, which by 2026-08-08 serves **v2027.0.0-alpha-2** — so a claim that was true when checked became false about our target version without a single character of this document changing. The same hazard applies to `docs.limelightvision.io`, which is unversioned entirely. Mitigation, applied throughout revision 4: **cite immutable git tags** (`.../photonvision/blob/v2026.3.4/...`) wherever source exists; where only an unversioned vendor page exists, **quote the sentence verbatim and date the check**, so a future reader can tell whether the page moved under us; and treat any citation that cannot do either as `[UNVERIFIED]`. This is a process risk, not a code risk, and it is the one most likely to recur, because it recurs silently.

---

## 21. Open Questions

1. **Limelight OS version reporting.** There is no documented NT key exposing the Limelight OS version (re-checked 2026-08-08 against the complete NetworkTables reference). Without it we cannot verify the middle-of-exposure timestamp convention (2026.0+) at runtime, and we cannot warn a team running 2025 firmware. Is there an undocumented key, or should we detect it by behavior? **[UNVERIFIED]**
2. **Coprocessor field-layout identity.** Neither PhotonVision nor Limelight publishes which layout it loaded. Is there a PhotonVision NT topic under the camera table that carries this? If PhotonVision would add one, it would eliminate the #1 silent bug in FRC vision outright. Worth an upstream PR.
3. **Limelight pipeline settle time.** Undocumented. We measure and log it, but should `SETTLE_FRAMES = 3` be the default, or should the default be "wait for `getpipe` match only" with the heartbeat requirement opt-in? Needs bench measurement on LL3G and LL4. **[UNVERIFIED]**
4. **`tdist` in Limelight OS 2026.1.** The dossier reports a new `tdist` (3D distance to target/POI) key. Re-checked 2026-08-08: it **does not appear** in the complete-NetworkTables reference. If it exists it is a cheap, high-quality distance source for object detection. Needs verification against a real 2026.1 camera. **[UNVERIFIED]**
5. **Welded vs Andymark delta magnitude for 2026 REBUILT.** I do not have the per-tag numbers. If the maximum delta is below our residual noise floor, the statistical mismatch detector is useless for 2026 and the `TAG_NOT_IN_LAYOUT` canary plus the `rootstockV1` handshake are the only real defenses. Measure at boot with `FieldLayouts.logDeltas`. **[UNVERIFIED]**
6. **~~Should `ignoreEarlyAuto` default on?~~ RESOLVED by `DESIGN.md` §5.6.** *"Default on, automatically disabled when the selected auto declares `resetOdom == false`."* Revision 3 of this document still shipped it off and carried the question open; §7.2 now ships it on with the auto-declared gate. What remains is a **contract**, not a question: the Auto domain must expose the selected routine's `resetOdom` flag to the vision filter chain. Named in §2.7 as an implicit dependency of `VisionFilters.standard()`.
7. **~~Multi-camera arbitration.~~ RESOLVED — `CameraArbiter` is specified in §13.6 and is built at M16.** The earlier text punted this to "an offseason-stretch experiment" on the reasoning that std-dev weighting *probably* dominates. That was not a defensible answer to shipped evidence: 2910 built "automatic selection of optimal camera to relocalize from" on purpose, and "probably" does not outrank a team that wins. The design decision is now explicit and split in two: the **default remains `CameraArbiter.all()`**, because flipping a behavioral default on a hypothesis is exactly the mistake we criticize elsewhere; but the alternative is **built, logged and one builder call away** at M16, so the A/B can actually be run on a practice field during the season rather than read as an open question. What genuinely remains open is only the *outcome* of that A/B and whether `bestByGeometry`'s scoring function should weight view angle explicitly in addition to tag span.
8. **Moving-mount transforms and replay.** `CameraMount.transformAt(timestamp)` calls back into a mechanism's position history. In AdvantageKit replay that history must itself have come from logged inputs. Does the mechanism domain guarantee a replayable `TimeInterpolatableBuffer` of mechanism angles? If not, a turret-mounted camera is not replayable and we should say so.
9. **`stddevs` NT key semantics.** Limelight publishes a 12-element MT1/MT2 std-dev array (layout verified 2026-08-08). Are those numbers in the same units and the same statistical sense as WPILib's `visionMeasurementStdDevs`? If yes, `StdDevModels.limelightReported()` becomes attractive as a default for Limelight cameras. **[UNVERIFIED]**
10. **~~`RootstockLog` replay of `VisionFrame[]`.~~ RESOLVED in §4.3 and by `DESIGN.md` §5.6.** `VisionFrame` is a plain record and is never serialized; the fixed-size `VisionFrameHeader` and `TargetObservation` go on two topics joined by `frameSequence`, and both are `Struct<T>[]`, which is the one shape WPILib's struct system is designed for. Both structs are registered and written once during `robotInit()` so AdvantageKit's documented >100 ms first-log cost lands at boot. Nothing further is required of the telemetry domain.
11. **`alignToTag` latency compensation at handoff speed.** The 6-arg overload deliberately does no compensation (§13.5 detail 5) on the argument that an 80 ms observation age is ≤ 4 cm at terminal speeds. That argument holds at ≤ 0.5 m/s and gets weaker fast above it. Should the `PoseProvider` overload become the *default* by making the 6-arg form delegate to it whenever a `PoseProvider` is reachable? Needs a measurement of the actual handoff speed out of `driveToPose` on a real robot before deciding.
12. **`CameraArbiter.bestByGeometry` scoring.** `tagCount * tagSpan / d²` is a defensible first cut chosen to agree with the std-dev model rather than fight it, but it ignores view angle, and a tag seen at 75° off-normal is worth much less than the same tag seen at 20°. `TargetObservation.bestCameraToTarget()` carries enough to compute the incidence angle. Add it, or does the tag-span term already capture most of the effect? **Needs an A/B, and §13.6 is built so the A/B is cheap.**
13. ***(new, revision 4, and it gates `alignToTag` on Limelight)*** **The `targetpose_cameraspace` rotation convention.** The translation basis change is unambiguous and is specified in §5.2a. The rotation half is not: the vendor documents the array as `[tx, ty, tz, pitch, yaw, roll]` in degrees but states neither the composition order nor which axis each name refers to *after* the camera-space basis change. **[UNVERIFIED]** — resolve empirically at the **M10** hardware gate by placing a tag at a known, deliberately asymmetric pose (yaw 30°, pitch 15°, roll 0°) and reading the key, then enable `LimelightCameraSpaceConversionTest`'s currently-`@Disabled` rotation case. Until then, the §5.2a runtime cross-check and the `LL_CAMERASPACE_CONVENTION` finding are the only defense, and §13.5 detail 6 tells a team so.
14. ***(new, revision 4)*** **Limelight robot-space Y sign — the vendor's own two pages disagree.** The *AprilTag Coordinate Systems* page says robot space is *"Y+ → Pointing toward the robot's right"*; the *LimelightLib* page documents `setCameraPose_RobotSpace`'s second argument as *"Side offset (meters), left of robot center."* Those are opposite. §11.1 writes left-positive (a straight pass-through of the WPILib `Transform3d`) because that is the page documenting the setter we call. **[UNVERIFIED]** — resolve at the same M10 gate by pushing a deliberately asymmetric transform (`y = +0.30 m`) and checking whether `targetpose_robotspace` for a tag straight ahead reports the tag to the robot's right or left. Worth an upstream documentation issue either way.
15. ***(new, revision 4)*** **`VisionFreshness` on a per-camera command.** `driveToPose`'s gate is now global (`hasRecentFix` / `acceptedFramesInWindow` across all cameras), which is right for a fused-pose command. But `alignToTag`'s 8-arg overload takes a `VisionFreshness` too, and for that command the meaningful question is *"is THIS camera's solve fresh"*, not "did any camera fix the pose." Should `VisionFreshness` grow a per-camera evaluation (`satisfiedBy(RootstockVision, int cameraIndex)`), or does `alignToTag`'s own `m_noSolveLoops` gate (§13.5 detail 4) already cover it completely? Leaning: `m_noSolveLoops` covers it, and the 8-arg overload's freshness argument should be documented as applying only to the latency-compensation path.

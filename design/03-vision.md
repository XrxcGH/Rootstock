# PumpkinLib Domain 03 — Vision

**Status:** design complete, ready to implement
**Target:** WPILib 2026.2.x · Java 17 · Phoenix 6 26.x · REVLib 2026 · PathPlannerLib 2026.1.2 · PhotonVision 2026.3.4 · Limelight OS 2026.1 / LimelightLib-WPIJava 1.14 · AdvantageKit 2026 (optional)
**Root package:** `org.pumpkinlib.vision`
**Author's stance:** PumpkinLib does not reimplement PhotonVision, Limelight, or WPILib pose estimation. It supplies the *seam* that makes them interchangeable, the *filter chain* that explains itself, and the *simulation* that Limelight never shipped.

**Revision 2 (2026-08-07), after adversarial review.** Six findings applied. In dependency order, so a reader who knows the first draft can jump straight to what changed:

| # | Severity | Where | What changed |
|---|---|---|---|
| 1 | blocking | §4.2, §4.3 | `VisionFrame` no longer claims `StructSerializable` — it cannot, because `Optional`/`int[]`/`List` have no fixed `getSize()`. Added the fixed-size `VisionFrameHeader` mirroring the §6.3 wire schema field for field, and replaced `TargetObservation`'s nullable transforms with `hasBest`/`hasAlt` flags plus zeroed `Transform3d` so `pack()` cannot NPE. |
| 2 | blocking | §2.2, §5.2, §9.7 | The gyro contract was self-contradictory and nothing owned the gyro→field offset. `getGyroRotation()` is now `getGyroFieldHeading()`, `DriveBackend` exposes `getRawGyro()`, and the offset has exactly two named writers — `resetPose` and the disabled multi-tag seed, which now refuses gyro-fused sources. |
| 3 | major | §2.6, §3, §6.1, §6.2, §14.2 | photonlib is no longer required. Three artifacts: core (WPILib only), `-photonvision`, `-vision-sim`. `.simulated(...)` takes the core-owned `CameraSimProfile`, never photonlib's `SimCameraProperties`, so the split is real rather than nominal — and CI enforces it. |
| 4 | major | §8.0–§8.4, §9.4 | `Double.POSITIVE_INFINITY` is gone from every standard deviation. `UNTRUSTED_SIGMA = 1e6`, the per-camera factor applies *before* the pin, `compute` writes into a caller-owned matrix, and `sanitizeStdDevs` is a choke point no escape hatch bypasses. A `cameraFactor` of `0` used to mean `NaN` pose for the rest of the match. |
| 5 | major | §9.6 (new), §4.4, §9.1 | `maxFramesPerLoop` dropped 20 → 4 and gained a separate `maxAcceptedPerLoop` = 2 with oldest+newest coalescing. The old default did ~800 `updateWithTime` calls in one loop, and only *after* a loop overrun — a positive feedback bomb. |
| 6 | major | §13.3, §13.5, §13.6, §19 | `alignToTag` is now genuine tag-relative control and ships in **v0.1**, `CameraArbiter` is specified and ships in v0.2, and `AlignGains.defaults()` tolerance went 2 cm → 5 cm because a fused-pose controller cannot hold tighter than the vision sigma at scoring range. |

One finding (§6.2, minor) was already correct in the draft and is retained with its verification note: PhotonVision 2026.3.4 has exactly one `PhotonPoseEstimator` constructor and no deprecated members.

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

- The drivetrain, the pose estimator instance, or odometry. We consume a `PoseProvider` and push measurements through a `VisionConsumer`.
- The logging transport. We call a `PumpkinLog` facade owned by the logging domain.
- Shooter lookup tables, hood angles, flywheel RPM. We supply the *virtual target* math (`MovingTargetSolver`); the mechanism domain supplies the numbers.
- Path generation. We compose PathPlanner's `AutoBuilder.pathfindToPose`.

---

## 2. Integration Points (what I need from other PumpkinLib domains)

Every item below is a hard dependency. Signatures are what Vision calls; the owning domain may add more.

### 2.1 From the **Logging** domain — `org.pumpkinlib.log`

```java
package org.pumpkinlib.log;

/** Fans out to AdvantageKit Logger when present, otherwise NT4 + WPILib DataLog + CTRE SignalLogger. */
public final class PumpkinLog {
  /** Replay-aware. In REPLAY mode this OVERWRITES the fields of `inputs` from the log. */
  public static void processInputs(String key, PumpkinInputs inputs);

  public static void output(String key, double value);
  public static void output(String key, long value);
  public static void output(String key, boolean value);
  public static void output(String key, String value);
  public static void output(String key, int[] value);
  public static void output(String key, double[] value);
  public static <T> void output(String key, Struct<T> struct, T value);
  public static <T> void output(String key, Struct<T> struct, T[] value);

  public static boolean isReplay();
  /** Monotonic robot time. Delegates to AdvantageKit Timer.getTimestamp() in replay, FPGA otherwise. */
  public static double timestamp();
}

/** Implemented by every PumpkinLib *IOInputs class. Mirrors AdvantageKit LoggableInputs without depending on it. */
public interface PumpkinInputs {
  void toLog(PumpkinLogTable table);
  void fromLog(PumpkinLogTable table);
}

/** Minimal LogTable surface: get/put for double, long, boolean, String, int[], double[], and Struct<T>[]. */
public interface PumpkinLogTable { /* ... */ }

/** Loop-time accounting. Vision declares one budget and one span; the logging domain owns the rest. */
public final class PumpkinTracer {
  /** Declares a named budget. Exceeding it raises PumpkinAlerts.warning once per 5 s and logs the overrun. */
  public static void budget(String key, Measure<Time> budget);
  public static void start(String key);
  public static void stop(String key);
}
```

Vision declares exactly one budget, at construction:

```java
PumpkinTracer.budget("Vision/Consume", Milliseconds.of(2.0));   // section 9.6
```

`Vision/Consume` wraps step 4d of the loop (§9.4) — every `consumer.accept(...)` call this loop, across every camera. That is the one span whose cost is superlinear in frame count and therefore the one span that needs a declared ceiling.

**Why this shape:** the user's stack spans AdvantageKit (template, 9143) *and* CTRE SignalLogger with no AdvantageKit at all (8793, `Telemetry.java` + `HootAutoReplay`). Vision must be identical in both. `PumpkinLog` is the only thing that knows the difference. Vision code never imports `org.littletonrobotics.*`.

### 2.2 From the **Drive / Pose** domain — `org.pumpkinlib.drive`

```java
package org.pumpkinlib.drive;

/** Everything Vision needs to know about robot state. Implemented by PumpkinLib's swerve, or hand-written in 6 lines. */
public interface PoseProvider {
  Pose2d getPose();                                   // blue-origin, ALWAYS
  Optional<Pose2d> sampleAt(double timestampSeconds); // delegates to PoseEstimator.sampleAt

  /**
   * Gyro yaw PLUS the field offset latched at the last pose reset. Blue-origin, CCW-positive,
   * 0 deg faces the RED wall — the same frame every MegaTag2 / PNP-trig / constrained-solvepnp
   * consumer needs.
   *
   * <p>NEVER influenced by a vision measurement. This is the whole point: it is derived from the
   * raw IMU plus a constant offset that only a pose RESET may change, so feeding it back into
   * MegaTag2 cannot close a loop. See the offset-ownership contract below.
   */
  Rotation2d getGyroFieldHeading();

  double getGyroRateRadPerSec();
  ChassisSpeeds getFieldRelativeSpeeds();
}

/** Exactly AdvantageKit's VisionConsumer signature, so a template port is import-only. */
@FunctionalInterface
public interface VisionConsumer {
  void accept(Pose2d visionRobotPoseMeters, double timestampSeconds, Matrix<N3, N1> stdDevs);
}
```

#### The gyro→field offset contract (resolves the blocking review finding)

The earlier draft of this document declared `Rotation2d getGyroRotation(); // RAW GYRO ONLY` and then, in §5.2, required the value written to `robot_orientation_set` to be *"blue-origin, CCW-positive, 0 deg faces the RED wall."* Those two statements are contradictory. A raw IMU yaw is referenced to wherever the gyro happened to be zeroed at power-on — the underside of a cart, a pit table, the wrong alliance wall. MegaTag2 fed a raw gyro yaw produces a confidently wrong translation, and nothing in the system notices. **No component owned the offset.** It does now.

> **Hard requirement on the Drive domain (`design/05` §3.2 and §3.3).**
>
> 1. `DriveBackend` exposes `Rotation2d getRawGyro();` — the unmodified IMU yaw, CCW-positive, referenced to power-on. This is the *only* place the raw value is legal.
> 2. `PumpkinDrive` owns a single field `private Rotation2d m_gyroFieldOffset = Rotation2d.kZero;` and a flag `private boolean m_gyroFieldOffsetSeeded = false;`.
> 3. `m_gyroFieldOffset` is written in **exactly two places**, and nowhere else:
>    - `PumpkinDrive.resetPose(Pose2d bluePose)`:
>      ```java
>      m_gyroFieldOffset = bluePose.getRotation().minus(m_backend.getRawGyro());
>      m_gyroFieldOffsetSeeded = true;
>      ```
>    - the **disabled multi-tag MegaTag1 seed** (§9.7): while disabled, a *non-gyro-fused* frame with `tagCount >= 2` that passes `VisionFilters.standard()` and agrees with two prior frames calls `resetPose(...)`, which runs the line above. This is the only path by which vision may ever touch the offset, it is gated on `!DriverStation.isEnabled()`, it refuses gyro-fused sources (seeding the gyro offset from a gyro-fused solve is circular), and it is a *reset*, not a measurement.
> 4. `addVisionMeasurement` **never** writes `m_gyroFieldOffset`. Not on any code path, not behind any flag. There is no opt-out, because an opt-out here is the feedback loop.
> 5. `PumpkinDrive.getGyroFieldHeading()` returns `m_backend.getRawGyro().plus(m_gyroFieldOffset)`.
> 6. `DriveSelfCheck` gains an **eighth check**: if any configured camera reports a gyro-fused `PoseSource` (`MEGATAG_2`, `PNP_DISTANCE_TRIG`, `CONSTRAINED_SOLVEPNP`) and `m_gyroFieldOffsetSeeded == false`, raise
>    ```java
>    PumpkinAlerts.error("Drive",
>        "Gyro field offset has never been seeded (still identity) but MegaTag2/PNP-trig cameras "
>      + "are configured. robot_orientation_set is being written in the POWER-ON gyro frame, not "
>      + "the field frame. Call drive.resetPose(startingBluePose) or enable the disabled MegaTag1 seed.")
>        .set(true);
>    ```
>    `PumpkinVision` mirrors the same check as a `VisionDiagnostics` finding (`GYRO_OFFSET_UNSEEDED`, §11.3) so it is visible from either domain.
>
> `design/05` §3.2's comment `Rotation2d getGyroHeading(); // raw gyro, CCW+, blue-origin frame` is deleted. It is a contradiction inside a single line: a raw gyro is by definition not in the blue-origin frame.

Limelight's own MegaTag2 sample feeds the *fused estimate* back into `SetRobotOrientation`, which is a latent feedback loop the moment anyone lowers the rotation std dev (deep-elite pitfall). We close that loop three ways, all structural: the heading comes from an accessor that is defined as never seeing a vision measurement; the offset has exactly two named writers; and every gyro-fused source gets `sigmaTheta = StdDevModels.UNTRUSTED_SIGMA` (§8), so vision rotation from those sources cannot move the estimate no matter what model a team plugs in.

### 2.3 From the **Config / Tunables** domain — `org.pumpkinlib.config`

```java
public final class Tunable {
  public static DoubleSupplier number(String key, double defaultValue);   // frozen constant when TUNING_MODE == false
  public static BooleanSupplier flag(String key, boolean defaultValue);
}
public final class PumpkinLibConfig {
  public static final boolean TUNING_MODE;   // one flag; FMS-gated internally
}
```
All filter thresholds, std-dev coefficients, and per-camera pitch fudge factors are `Tunable.number(...)`. In a competition build they compile down to a constant read. (Both 4738 and the template built tunable stacks and then commented them out; that must not be the choice PumpkinLib forces.)

### 2.4 From the **Alerts / Health** domain — `org.pumpkinlib.health`

```java
public final class PumpkinAlerts {
  public static Alert error(String group, String text);   // edu.wpi.first.wpilibj.Alert, AlertType.kError
  public static Alert warning(String group, String text);
  public static Alert info(String group, String text);
}
```
Vision raises alerts for: camera disconnected, timestamps outside the odometry buffer, MegaTag2 configured but `robot_orientation_set` never written, camera transform still `(0,0,0)`, suspected layout mismatch, calibration resolution mismatch.

### 2.5 From the **Auto / Field** domain — `org.pumpkinlib.field`

```java
public final class AllianceFlip {
  public static boolean shouldFlip();                    // delegates to PathPlanner FlippingUtil semantics
  public static Pose2d apply(Pose2d bluePose);
  public static Translation3d apply(Translation3d bluePoint);
}
```
Vision **never flips a measured pose**. It only calls `AllianceFlip` when resolving *targets* for alignment commands. See §7.3.

### 2.6 Artifact split — vision core depends on WPILib ONLY

The earlier draft listed `photonlib` as **Required**, with the note *"Nothing works. It is also our simulation engine even for Limelight."* That is a straight violation of three of our own principles: DESIGN.md Principle 5 (*no vendor lock-in, in either direction; Limelight, PhotonVision and custom NT coprocessors are first-class*), §2 item 10 (*installs as one vendordep... a team installs on kickoff morning*), and Principle 12 (*a missing vendor is a named Alert, not a `NoClassDefFoundError`*). It also made our ship date hostage to PhotonVision's: `pumpkinlib-vision` could not publish until photonlib published. A Limelight-only team must never be forced to install PhotonVision.

**We split the artifact three ways. One source tree, three Gradle modules, three Maven coordinates.**

| Artifact | Depends on | Contains |
|---|---|---|
| `com.pumpkinlib:pumpkinlib-vision` | **WPILib only** | `VisionFrame`, `VisionFrameHeader`, `TargetObservation`, `PoseSource`, `CameraMount`, `CameraSimProfile` + `CameraSimProfiles`, `VisionCameraIO`, `ReplayCameraIO`, the whole `filter` package, the whole `stddev` package, `field` (`FieldLayouts`, `LayoutFingerprint`, `TagResidualMonitor`), `objects`, `commands` (including `alignToTag` and `CameraArbiter`), `diag`, `compat`, `CustomNTCameraIO` + schemas, and the **vendored** `LimelightHelpers` + `LimelightCameraIO`. |
| `com.pumpkinlib:pumpkinlib-photonvision` | `pumpkinlib-vision` + photonlib vendordep | `org.pumpkinlib.vision.photon.PhotonCameraIO`, `PhotonStrategy`. Nothing else. |
| `com.pumpkinlib:pumpkinlib-vision-sim` | `pumpkinlib-vision` + photonlib vendordep | `org.pumpkinlib.vision.sim.*` — `PumpkinVisionSim`, `PumpkinCameraProps`, `SimulatedLimelight`. Wraps `VisionSystemSim` / `PhotonCameraSim` / `SimCameraProperties`. |

Consequences, all of them deliberate:

- A Limelight-only team installs **one** vendordep (`pumpkinlib-vision`) and gets the filter chain, std-dev models, diagnostics, layout management, object projection, and every command. No photonlib. No PhotonVision install on their coprocessor. No AGPL model licensing question.
- `pumpkinlib-vision` compiles and publishes against WPILib alone, so it ships on our schedule, not PhotonVision's.
- Camera **simulation** for a Limelight-only team costs one extra vendordep (`pumpkinlib-vision-sim`) and zero code changes — `.simulated(...)` is already on `LimelightCameraIO` in the core artifact.

  > **This is the constraint that makes the split real, so it gets stated as a rule.** `SimCameraProperties` is a photonlib type. If `LimelightCameraIO.simulated(...)` took one, the core artifact would import `org.photonvision.*` and the whole split would be theatre — the ArchUnit test in §3 would go red on day one. So `.simulated(...)` takes **`CameraSimProfile`**, a plain core-owned record of sensor numbers (§14.2). `pumpkinlib-vision-sim` converts it to a `SimCameraProperties` at sim-construction time. A Limelight-only team therefore writes `.simulated(CameraSimProfiles.OV9281_1280_800_82DEG())` in `RobotContainer` and that line compiles with **no photonlib on the classpath at all**; it simply does nothing at runtime until the sim artifact is present.
- `PhotonCameraIO` moving to its own artifact means the `PhotonStrategy` enum, the `withCalibration` helper and the `estimate*Pose` call sites are the *only* code in PumpkinLib that imports `org.photonvision.*`. That is one small module to re-verify against each PhotonVision release.

**Principle-12 behavior when a module is missing.** `PumpkinVision.build()` probes with `Class.forName` and degrades with a named Alert — never a `NoClassDefFoundError`:

```java
private static final boolean SIM_AVAILABLE =
    classExists("org.pumpkinlib.vision.sim.PumpkinVisionSim")
        && classExists("org.photonvision.simulation.VisionSystemSim");

// in Builder.build():
if (m_simEnabled && !SIM_AVAILABLE) {
  PumpkinAlerts.warning("Vision",
      "Camera simulation needs pumpkinlib-vision-sim (PhotonVision). "
    + "Running with no simulated cameras.").set(true);
  m_simEnabled = false;   // everything else still works; poses simply never arrive in sim
}
if (m_hasPhotonCamera && !classExists("org.photonvision.PhotonCamera")) {
  PumpkinAlerts.error("Vision",
      "A PhotonCameraIO was configured but photonlib is not on the classpath. "
    + "Add the pumpkinlib-photonvision vendordep. This camera is disabled.").set(true);
}
```

`classExists` is a two-line `try { Class.forName(n); return true; } catch (Throwable t) { return false; }`. The probe runs once, at construction, never in the loop.

**Dependency matrix**

| Dependency | Required? | If absent |
|---|---|---|
| WPILib 2026.2.x | **Required** | This is the floor. `pumpkinlib-vision` needs nothing else. |
| `photonlib` (PhotonVision 2026.3.4) | **Required only for PhotonVision cameras and camera simulation** | `pumpkinlib-vision` is fully functional without it: Limelight, custom NT coprocessors, filters, std devs, diagnostics, commands. `PhotonCameraIO` and `PumpkinVisionSim` are simply not on the classpath, and asking for either raises a named `Alert` (above), never a linkage error. |
| `LimelightHelpers` | Vendored inside `pumpkinlib-vision` | n/a — we ship it verbatim as `org.pumpkinlib.vision.limelight.LimelightHelpers` (BSD-3, single file). No vendordep, no version skew. |
| AdvantageKit 2026 | Optional | `PumpkinLog` falls back to NT4 + DataLog. Replay unavailable. |
| PathPlannerLib 2026.1.2 | Optional | `VisionCommands.driveToPose` degrades to pure terminal control (no obstacle-avoiding approach phase) and logs a warning once. `alignToTag` (§13.3) never needed it. |
| Phoenix 6 26.x | Optional | Only used for `Utils.fpgaToCurrentTime()` when the consumer is a CTRE `SwerveDrivetrain`. Detected reflectively; see §9.2. |

---

## 3. Package layout

```
--- artifact: pumpkinlib-vision            (WPILib only; NO photonlib) --------------------------
org.pumpkinlib.vision              PumpkinVision, VisionFrame, VisionFrameHeader, TargetObservation,
                                   PoseSource, CameraMount, CameraSimProfile, CameraSimProfiles
org.pumpkinlib.vision.io           VisionCameraIO, VisionCameraIOInputs, ReplayCameraIO
org.pumpkinlib.vision.limelight    LimelightHelpers (vendored), LimelightCameraIO, LimelightKeys, SnapScriptChannel
org.pumpkinlib.vision.custom       CustomNTCameraIO, VisionWireSchema, PumpkinV1Schema, NorthstarSchema
org.pumpkinlib.vision.filter       VisionFilter, VisionFilters, RejectReason, VisionContext, FilterResult
org.pumpkinlib.vision.stddev       StdDevModel, StdDevModels
org.pumpkinlib.vision.field        FieldLayouts, LayoutFingerprint, TagResidualMonitor
org.pumpkinlib.vision.objects      DetectedObject, ObjectProjection, ObjectTracker
org.pumpkinlib.vision.commands     VisionCommands, AlignGains, MovingTargetSolver, CameraArbiter
org.pumpkinlib.vision.diag         VisionDiagnostics, VisionHealth, Finding
org.pumpkinlib.vision.compat       AdvantageKitCompat  (PoseObservation-shaped views for template porting)

--- artifact: pumpkinlib-photonvision      (+ photonlib vendordep) ----------------------------
org.pumpkinlib.vision.photon       PhotonCameraIO, PhotonStrategy

--- artifact: pumpkinlib-vision-sim        (+ photonlib vendordep) ----------------------------
org.pumpkinlib.vision.sim          PumpkinVisionSim, PumpkinCameraProps, SimulatedLimelight
```

**Enforced by CI, not by intent.** A Gradle check compiles `pumpkinlib-vision` against a classpath with photonlib deliberately absent, and an ArchUnit-style test asserts that no class outside `org.pumpkinlib.vision.photon` / `org.pumpkinlib.vision.sim` imports `org.photonvision.*`. If that test ever goes red, the vendor-neutrality claim in §2.6 is a lie and the build says so.

**Java-17-safe subset only.** Records, sealed-free interfaces, `var`, arrow switch — yes. Pattern matching for `switch`, record patterns — no (they are preview in 17 and PumpkinLib must compile unchanged on the 2027 Java-25 branch).

---

## 4. The Unified Vision Interface

### 4.1 `PoseSource`

```java
package org.pumpkinlib.vision;

/** How a robot pose in a VisionFrame was produced. Drives the std-dev model and the filter chain. */
public enum PoseSource {
  /** Limelight MegaTag1 (botpose_wpiblue). Pure PnP, ambiguity-prone on a single tag. */
  MEGATAG_1(false),
  /** Limelight MegaTag2 (botpose_orb_wpiblue). Gyro-fused; rotation is an INPUT, never an output. */
  MEGATAG_2(true),
  /** PhotonVision estimateCoprocMultiTagPose / estimateRioMultiTagPose. */
  MULTI_TAG_COPROC(false),
  /** PhotonVision estimateLowestAmbiguityPose / estimateClosestToCameraHeightPose / estimateAverageBestTargetsPose. */
  SINGLE_TAG_PNP(false),
  /** PhotonVision estimatePnpDistanceTrigSolvePose. Requires addHeadingData(); gyro-fused. */
  PNP_DISTANCE_TRIG(true),
  /** PhotonVision estimateConstrainedSolvepnpPose. Gyro-seeded. */
  CONSTRAINED_SOLVEPNP(true),
  /** A custom coprocessor over NT. Trust model is whatever the schema declares. */
  CUSTOM(false),
  /** Produced by PumpkinVisionSim. Behaves as MULTI_TAG_COPROC for std-dev purposes. */
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
package org.pumpkinlib.vision;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.util.struct.StructSerializable;

/**
 * One target in one frame, in a normalized convention:
 *   tx is CCW-POSITIVE (target to the LEFT of the principal point is positive)
 *   ty is UP-POSITIVE
 * PhotonVision already uses this convention. LimelightCameraIO NEGATES Limelight's tx,
 * which is right-positive. This normalization happens exactly once, at the IO boundary.
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
    /** Lowest-reprojection-error camera-to-target transform. Transform3d.kZero when absent —
     *  NEVER null. Check hasBestCameraToTarget() first. */
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

**Why `boolean hasBest` + a zeroed `Transform3d` instead of a nullable field.** `Struct<T>` calls `pack(ByteBuffer, T)` unconditionally on every component; a `null` `Transform3d` NPEs inside `pack()` on the first object-detection frame, in the logging thread, at an event. Optionality on the wire is a flag byte, never a null — and the `pumpkinV1` wire schema in §6.3 already encoded it exactly this way (`uint8 hasBestCameraToTarget`). The in-library record now matches the wire schema field for field, so `TargetObservationStruct` is a mechanical transcription rather than a translation with a hole in it.

`TargetObservation` **is** fixed-size and therefore legitimately `StructSerializable`: every component is a scalar, a `Rotation2d`, a `Transform3d`, or a fixed-length `double[4]` (WPILib's struct schema supports fixed-length arrays, `double cornerTxRad[4]`). `getSize()` is a compile-time constant. Contrast §4.3.

### 4.3 `VisionFrame` (in-memory) and `VisionFrameHeader` (on the wire)

Strict superset of AdvantageKit's `PoseObservation` (dossier: adopt and generalize — this record adds `cameraIndex`, `targets`, `tagSpanMeters`, `tagIds`, and an `Optional` pose so an object-detection-only frame is representable).

> **`VisionFrame` is NOT `StructSerializable`, and this is not an oversight.** WPILib's `Struct<T>` contract requires a fixed `int getSize()`. `Optional<Pose3d>`, `int[] tagIds` and `List<TargetObservation>` are all variable-length; no `getSize()` can exist. The earlier draft declared `implements StructSerializable` with a `public static final VisionFrameStruct struct` on this record, which is simply impossible — even though §6.3 of the same document had already stated the rule (*"WPILib structs cannot be variable-length, so the target list lives on its own topic"*) and had already solved it for the `pumpkinV1` wire format. We now apply that same fix to the in-library type, and the in-library header mirrors the wire schema **field for field**, in the same order, so there is exactly one layout to get right.

```java
package org.pumpkinlib.vision;

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
package org.pumpkinlib.vision;

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.util.struct.StructSerializable;

/**
 * The fixed-size sibling of VisionFrame. This is what actually goes on a NetworkTables topic,
 * into a DataLog, or into an AdvantageKit LogTable.
 *
 * Layout is IDENTICAL to the `PumpkinVisionFrame` wire schema in section 6.3 — same fields, same
 * order, same types — so a pumpkinV1 coprocessor's bytes and the robot's own log entries share one
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
// PumpkinVision.periodic(), after filtering camera i's frames this loop:
VisionFrameHeader[] headers = frames.stream().map(VisionFrame::header)
                                    .toArray(VisionFrameHeader[]::new);
TargetObservation[] targets = frames.stream().flatMap(f -> f.targets().stream())
                                    .toArray(TargetObservation[]::new);

PumpkinLog.output("Vision/Camera" + i + "/Frames",  VisionFrameHeader.struct, headers);
PumpkinLog.output("Vision/Camera" + i + "/Targets", TargetObservation.struct, targets);
```

Both are `Struct<T>[]`, which `PumpkinLog.output(String, Struct<T>, T[])` (§2.1) already supports and which NT4 and AdvantageKit both handle natively as variable-length arrays *of fixed-size elements* — the one shape the struct system is designed for. `tagIds` is not in the header; it is recoverable from the `Targets` topic (`fiducialId` of every target with the matching `frameSequence`) and is additionally logged as a plain `int[]` on `Vision/Camera<i>/TagIds` for AdvantageScope convenience.

**Reconstruction in replay** is `VisionCameraIOInputs.fromLog(...)`: read both arrays, group targets by `frameSequence`, rebuild each `VisionFrame` with `Optional.of(pose)` when `hasPose` and `Optional.empty()` otherwise. This closes open question #10 (§21): the shape is fixed-size structs on two topics, and no `PumpkinLogTable` support beyond `Struct<T>[]` is needed.

### 4.4 `VisionCameraIO` and its inputs

```java
package org.pumpkinlib.vision.io;

public interface VisionCameraIO extends AutoCloseable {

  /** Plain POJO — no AdvantageKit types. PumpkinLog handles replay. */
  final class VisionCameraIOInputs implements PumpkinInputs {
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

    @Override public void toLog(PumpkinLogTable t) { /* generated-equivalent, hand-written */ }
    @Override public void fromLog(PumpkinLogTable t) { /* ... */ }
  }

  void updateInputs(VisionCameraIOInputs inputs);

  /** Camera index assigned by PumpkinVision at build time. Stamped into every VisionFrame. */
  void setIndex(int index);
  String name();

  /** Robot→camera transform. Always present for PhotonVision and for Limelight when
   *  pushCameraTransform(true) is used. Empty means "configured in a web UI" — a diagnostic finding. */
  default Optional<Transform3d> robotToCamera() { return Optional.empty(); }

  /** MegaTag2 / PNP_DISTANCE_TRIG / CONSTRAINED_SOLVEPNP feed. Called BEFORE updateInputs, every loop. */
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

**Every IO drains every frame.** `LimelightCameraIO` uses `DoubleArraySubscriber.readQueue()`; `PhotonCameraIO` uses `getAllUnreadResults()`; `CustomNTCameraIO` uses `readQueue()`. A 120 fps LL4 on a 50 Hz loop throws away >60% of its data otherwise (deep-vision elite practice #1). Note 8793's `VisionSubsystem.java` deliberately takes only `results.get(results.size()-1)` to avoid an overrun spiral — PumpkinLib solves that differently, with a per-loop **decode** cap (`maxFramesPerLoop`, default **4**) and a separate per-loop **accepted-measurement** cap (`maxAcceptedPerLoop`, default **2**) that coalesces rather than discards. The full reasoning, and why the old default of 20 was a loop-time bomb, is §9.6.

### 4.5 AdvantageKit template compatibility

```java
package org.pumpkinlib.vision.compat;

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

WPILib pose estimators run on FPGA time. AdvantageKit's template deliberately uses FPGA timestamps end to end so no conversion is needed; PumpkinLib does the same. The *only* place a different time base appears is when the consumer is a CTRE `SwerveDrivetrain` — handled once, in §6.2.

### 5.2 Limelight

**Botpose array packing.** Positional, undocumented in the LimelightHelpers Javadoc, verified against the Limelight NetworkTables API reference and AdvantageKit `VisionIOLimelight`. Applies identically to `botpose`, `botpose_wpiblue`, `botpose_wpired`, `botpose_orb`, `botpose_orb_wpiblue`, `botpose_orb_wpired`.

| Index | Meaning | Units |
|---:|---|---|
| 0 | x | meters |
| 1 | y | meters |
| 2 | z | meters |
| 3 | roll | **degrees** |
| 4 | pitch | **degrees** |
| 5 | yaw | **degrees** |
| 6 | **total latency (capture + targeting)** | **milliseconds** |
| 7 | tag count | count |
| 8 | tag span | meters |
| 9 | average tag distance | meters |
| 10 | average tag area | % of image |
| `11 + 7k + 0` | tag id (block *k*) | int |
| `11 + 7k + 1` | txnc | degrees, **right-positive** |
| `11 + 7k + 2` | tync | degrees, up-positive |
| `11 + 7k + 3` | ta | % of image |
| `11 + 7k + 4` | distToCamera | meters |
| `11 + 7k + 5` | distToRobot | meters |
| `11 + 7k + 6` | ambiguity | 0..1 |

Therefore **MegaTag1 first-tag ambiguity is index 17**. Array length is `11 + 7 * tagCount`; anything shorter than 11 is a malformed sample and is dropped with `RejectReason.MALFORMED_FRAME`.

`tl` and `cl` are **separate NT keys and are not the same number as index 6.** Do not use them for the pose timestamp.

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

1. `sample.timestamp` is when the **server received** the value, i.e. *after* network transit. Subtracting only the camera-side latency yields a capture time that is **late by the transit time** (~1–3 ms on a wired network, far worse on a saturated radio). We log `Vision/Camera<i>/NetworkTransitEstimateSecs` as `now - sample.timestamp*1e-6` so a team can see when the number goes bad.
2. Limelight OS 2026.0 changed to **middle-of-exposure** capture timestamps (middle row for rolling shutter). On LLOS ≤ 2025 the convention is start-of-exposure. PumpkinLib assumes 2026.0+ and raises an info-level finding if it cannot confirm the OS version. **[UNVERIFIED]** — there is no NT key that reports the Limelight OS version; the check is a documentation item, not a runtime one.

**MegaTag2 `SetRobotOrientation` + flush ordering.** This is mandatory and order-sensitive.

```java
// LimelightCameraIO.setRobotOrientation(...) — called by PumpkinVision.periodic() BEFORE updateInputs()
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

- `LimelightCameraIO` counts `m_orientationWrites`. If `m_mode != MEGATAG1` and the count has not advanced in the last 0.5 s while frames are arriving, we raise `PumpkinAlerts.error("Vision", "<name>: MegaTag2 selected but robot_orientation_set is not being written")` and set `RejectReason.MEGATAG2_NO_ORIENTATION` on every MT2 frame. Writing it once at init produces a pose that looks fine at startup and drifts silently — that failure now names itself.
- The write happens **before** the read, every loop, with an explicit flush between. There is still an inherent one-frame lag (the frame you read was solved with the yaw you wrote earlier); this is a property of the vendor, not a bug, and is why MegaTag2's `sigmaTheta` is pinned to `StdDevModels.UNTRUSTED_SIGMA` anyway.
- **The yaw comes from `PoseProvider.getGyroFieldHeading()`** — raw IMU yaw plus the field offset latched at the last pose reset (§2.2). This is precisely the frame the key demands: blue-origin, CCW-positive, 0 deg facing the RED wall. A raw IMU yaw would be referenced to power-on and is never correct here; the fused estimate would close a feedback loop. There is exactly one accessor and it is neither of those things.
- `PumpkinVision` cannot detect by reflection whether a team wired `getGyroFieldHeading()` to the fused estimate, so we defend by consequence instead: `VisionDiagnostics.GYRO_OFFSET_UNSEEDED` fires when a gyro-fused camera is configured and the Drive domain reports the offset has never been seeded (§2.2 item 6), and `Vision/GyroFieldHeadingDeg` is logged every loop next to `Vision/FusedHeadingDeg` so the two curves being identical is visible in AdvantageScope in one glance.

**LL4 IMU modes.** `SetIMUMode(name, int)`: `0` EXTERNAL_ONLY, `1` EXTERNAL_SEED_INTERNAL, `2` INTERNAL_ONLY, `3` INTERNAL_MT1_ASSIST, `4` INTERNAL_EXTERNAL_ASSIST. PumpkinLib default for LL4 is **mode 4**, set once at construction and re-asserted every 5 s (configs are lost across a camera reboot). `SetIMUAssistAlpha(name, double)` defaults to 0.001.

**Limelight also publishes its own std devs.** The `stddevs` NT key is a 12-element array `[MT1 x,y,z,roll,pitch,yaw, MT2 x,y,z,roll,pitch,yaw]`. We read it into `VisionCameraIOInputs` and expose it via `StdDevModels.limelightReported()` as an *option*, but it is not the default — the distance²/tag-count model is better characterized across the community.

### 5.3 PhotonVision

```java
for (PhotonPipelineResult result : m_camera.getAllUnreadResults()) {
  // Already in the RIO time base: PhotonVision 2026 runs a Time Sync Server and getTimestampSeconds()
  // is documented as "the estimated time the frame was taken, in the Time Sync Server's time base".
  double timestampSeconds = result.getTimestampSeconds();
  ...
}
```

No arithmetic. **Do not** recompute from `result.metadata` — PhotonVision already did the sync, and re-deriving it is how teams break it.

`PhotonPoseEstimator` returns an `EstimatedRobotPose` whose `timestampSeconds` field is the same number; use whichever, but never mix.

### 5.4 Custom NT coprocessors

The coprocessor must publish **middle-of-exposure time converted into NT server time**. This is where homebrew systems die. PumpkinLib's Python helper (`pumpkin_vision` on PyPI) exposes:

```python
from pumpkin_vision import VisionPublisher, nt_now_us
# nt_now_us() resolves camera monotonic time -> NT server time using the NT4 time-sync offset.
```

On the robot side, a `pumpkinV1` frame carries its own `captureTimestampMicros` in NT server time; we convert with `* 1e-6` and do not subtract anything. For the Northstar back-compat decoder we use the NT sample timestamp minus the coprocessor-declared latency, exactly as 6328 do.

`VisionFilters.timestampSane(...)` (§7) is the backstop: a frame whose timestamp is in the future, or older than the odometry buffer, is rejected with a named reason and counted, instead of silently no-op'ing inside `addVisionMeasurement`.

---

## 6. Implementations

### 6.1 `LimelightCameraIO`

```java
package org.pumpkinlib.vision.limelight;

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
   * Attach a sim twin. No-op on a real robot, and no-op when pumpkinlib-vision-sim is absent
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
m_hbSub      = t.getDoubleTopic("hb").subscribe(0.0);
m_getpipeSub = t.getIntegerTopic("getpipe").subscribe(-1);
m_pipelinePub= t.getIntegerTopic("pipeline").publish();
```

`getBotPoseEstimate_*()` is **never** used (returns only the newest sample). `getLatestResults()` is **never** used (full JSON parse on the RIO; a well-known loop-overrun source). Both are still reachable through `LimelightHelpers` for teams that want them — visible escape hatch, not the default path.

**Per-tag decode.** From index 11, stride 7, we emit a `TargetObservation.fiducial(...)` per tag with `tx` **negated** (Limelight `txnc` is right-positive; PumpkinLib is CCW-positive). `bestCameraToTarget` is `Optional.empty()` for botpose-derived targets — which becomes `hasBestCameraToTarget == false` and a zeroed `Transform3d`, never a null (§4.2). When `LimelightCameraIO` needs the transform for `TagResidualMonitor` or for `VisionCommands.alignToTag` it reads `targetpose_cameraspace` for the primary tag only, and passes `Optional.of(...)` on that one target.

**Pipeline switching with settle detection.**

```java
@Override public void setPipeline(int index) {
  m_pipelinePub.set(index);
  m_pipelineRequestedAt = PumpkinLog.timestamp();
  m_pipelineRequestHeartbeat = m_hbSub.get();
  m_pipelineRequested = index;
}
@Override public boolean pipelineSettled(int requestedIndex) {
  return m_getpipeSub.get() == requestedIndex
      && m_hbSub.get() >= m_pipelineRequestHeartbeat + SETTLE_FRAMES;   // SETTLE_FRAMES = 3
}
```

**[UNVERIFIED]** — the exact number of frames a Limelight pipeline switch costs is not documented anywhere. `SETTLE_FRAMES = 3` is a conservative default; `LimelightCameraIO` measures the observed settle time on every switch and logs it to `Vision/Camera<i>/PipelineSettleSecs`, so a team can see the real number for their hardware instead of trusting ours.

**Also note:** `snapshot` semantics changed in LLOS 2026.0 from a level to a rising-edge counter (0→1→2→3), rate-limited to one per 10 frames. `LimelightCameraIO.triggerSnapshot()` increments; code that sets it to 1 and leaves it captures once and then stops.

### 6.2 `PhotonCameraIO` — the 2026 API, correctly

PhotonVision 2026 replaced the `PoseStrategy` enum + `PhotonPoseEstimator.update()` path with explicit per-strategy methods.

> **Verified 2026-08-06 against PhotonVision 2026.3.4**, source `https://javadocs.photonvision.org/release/org/photonvision/PhotonPoseEstimator.html`. `PhotonPoseEstimator(AprilTagFieldLayout, Transform3d)` is the **only** constructor, and the class lists **no deprecated members at all**. The 2024/2025 `(layout, PoseStrategy, transform)` form was **removed**, not deprecated, and `PoseStrategy` survives only as a nested enum with no live call site. The estimation entry points are exactly these eight, all returning `Optional<EstimatedRobotPose>`:
> `estimateCoprocMultiTagPose(PhotonPipelineResult)` · `estimateRioMultiTagPose(PhotonPipelineResult, Matrix, Matrix)` · `estimateLowestAmbiguityPose(PhotonPipelineResult)` · `estimateClosestToCameraHeightPose(PhotonPipelineResult)` · `estimateClosestToReferencePose(PhotonPipelineResult, Pose3d)` · `estimateAverageBestTargetsPose(PhotonPipelineResult)` · `estimatePnpDistanceTrigSolvePose(PhotonPipelineResult)` · `estimateConstrainedSolvepnpPose(PhotonPipelineResult, Matrix, Matrix, Pose3d, boolean, double)`.
> Heading feed: `addHeadingData(double, Rotation2d)` / `addHeadingData(double, Rotation3d)`, plus `resetHeadingData(double, Rotation2d)` / `resetHeadingData(double, Rotation3d)`.
> Every 2024/2025 tutorial online is now wrong.

`PhotonStrategy` below covers all eight — the earlier draft omitted `estimateClosestToReferencePose`, which is now present with a documented reason why it is not a default.

```java
package org.pumpkinlib.vision.photon;

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
     * a supplied reference. PumpkinLib supplies the CURRENT pose estimate at the frame's capture
     * time, i.e. `new Pose3d(ctx.estimateAtCapture().orElse(ctx.currentEstimate()))`.
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
  // Verified 2026-08-06 against PhotonVision 2026.3.4
  // (javadocs.photonvision.org/release/org/photonvision/PhotonPoseEstimator.html):
  // `PhotonPoseEstimator(AprilTagFieldLayout, Transform3d)` is the ONLY constructor. The 2024/2025
  // `(layout, PoseStrategy, transform)` form was REMOVED, not deprecated — the class lists no
  // deprecated members at all — and `PoseStrategy` survives only as a nested enum with no live
  // call site. Every 2024/2025 tutorial that constructs with three arguments will not compile.
  m_estimator = new PhotonPoseEstimator(layout, robotToCamera);
}

@Override public void setRobotOrientation(Rotation2d yaw, double yawRateRadPerSec) {
  // Required for PNP_DISTANCE_TRIG and CONSTRAINED_SOLVEPNP; harmless otherwise, so we always feed it.
  // `yaw` is PoseProvider.getGyroFieldHeading() — blue-origin, never the fused estimate (section 2.2).
  // Verified overloads: addHeadingData(double, Rotation2d) and addHeadingData(double, Rotation3d),
  // plus resetHeadingData(double, Rotation2d|Rotation3d) which we call on a pose reset.
  m_estimator.addHeadingData(PumpkinLog.timestamp(), yaw);
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
 *  uncalibrated at the current streaming resolution. Missing calibration becomes a named Finding,
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
        Rotation2d.fromDegrees(t.getYaw()),      // already CCW-positive (left-positive)
        Rotation2d.fromDegrees(t.getPitch()),    // already up-positive
        t.getArea(),
        t.getPoseAmbiguity(),
        best.map(b -> b.getTranslation().getNorm()).orElse(Double.NaN),
        best,
        alt));
  } else if (t.getDetectedObjectClassID() >= 0) {
    targets.add(TargetObservation.object(
        frameSequence,
        t.getDetectedObjectClassID(),
        t.getDetectedObjectConfidence(),
        Rotation2d.fromDegrees(t.getYaw()),
        Rotation2d.fromDegrees(t.getPitch()),
        t.getArea(),
        cornerYaws(t.getDetectedCorners()),
        cornerPitches(t.getDetectedCorners())));
  }
}
```

> **PhotonVision 3D mode requires calibration at the exact streaming resolution.** Calibrating at 1280×800 and streaming at 640×400 yields no 3D output. `VisionDiagnostics` checks this explicitly (§11.3).

### 6.3 `CustomNTCameraIO` and the wire schema

```java
package org.pumpkinlib.vision.custom;

public interface VisionWireSchema {
  /** Called once at construction to create subscribers. */
  void bind(NetworkTable table);
  /** Drain everything published since the last call, oldest first, already robot-frame. */
  List<VisionFrame> drain(Transform3d robotToCamera, AprilTagFieldLayout layout);
  String schemaName();

  static VisionWireSchema pumpkinV1()      { return new PumpkinV1Schema(); }
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

**`pumpkinV1` NT layout.** Struct-serialized, versioned, self-describing — the thing nobody in FRC has built. WPILib 2026 has first-class `Struct<T>` on NetworkTables; we use it.

```
/pumpkin_vision/<name>/schema        string   "pumpkin-vision/1"
/pumpkin_vision/<name>/frames        struct[]:PumpkinVisionFrame     (sendAll, keepDuplicates, pollStorage 20, periodic 0.01)
/pumpkin_vision/<name>/targets       struct[]:PumpkinTargetObservation
/pumpkin_vision/<name>/heartbeat     int
/pumpkin_vision/<name>/fps           double
/pumpkin_vision/<name>/health        struct:CoprocHealth   (cpuTempC, cpuPct, ramPct, uptimeS)
/pumpkin_vision/<name>/layout_hash   string    <- coprocessor's loaded tag layout fingerprint. See section 8.4.
/pumpkin_vision/<name>/config/*      published DOWN by robot code
```

Struct schemas (fixed-size — WPILib structs cannot be variable-length, so the target list lives on its own topic and links back by `frameSequence`). **These are the same two schemas the robot logs its own frames with**: `PumpkinVisionFrame` ≡ `VisionFrameHeader` (§4.3) and `PumpkinTargetObservation` ≡ `TargetObservation` (§4.2), field for field, in this order. One layout, one schema string, one place to get it right.

```
PumpkinVisionFrame:      // == org.pumpkinlib.vision.VisionFrameHeader
  int64  frameSequence;
  double captureTimestampSeconds;   // NT server time base, MIDDLE OF EXPOSURE
  uint8  hasPose;
  Pose3d robotPose;                 // field-relative, blue origin; zeros when hasPose == 0
  uint8  source;                    // PoseSource ordinal
  int32  tagCount;
  double averageTagDistanceMeters;
  double tagSpanMeters;
  double ambiguity;

PumpkinTargetObservation:  // == org.pumpkinlib.vision.TargetObservation
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
  Transform3d bestCameraToTarget;
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
All fields are overwritten by `PumpkinLog.processInputs` in replay. This is the AdvantageKit `new VisionIO(){}` convention, named so it is discoverable.

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
package org.pumpkinlib.vision.filter;

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

Every threshold is a `Tunable.number` so it is adjustable at the field without a redeploy, and frozen at competition.

```java
package org.pumpkinlib.vision.filter;

public final class VisionFilters {

  /** Frame carried no pose. Returns NO_POSE (a non-error rejection; it is not counted as a failure). */
  public static VisionFilter requirePose();

  /** tagCount == 0. */
  public static VisionFilter requireTags();

  /** Every tagId in the frame must exist in the layout. This is the cheapest layout-mismatch canary. */
  public static VisionFilter tagsInLayout();

  /** Single-tag frames only: reject when ambiguity > max. AdvantageKit 0.3; Limelight MT1 docs 0.7;
   *  8793 VisionSubsystem 0.2. PumpkinLib default 0.3. Multi-tag and gyro-fused frames pass. */
  public static VisionFilter maxAmbiguitySingleTag(double max);

  /** 6328's approach, and better than a bare threshold: require one PnP solution to be decisively
   *  better AND pick the candidate closest in rotation to the gyro. Requires alt/best transforms. */
  public static VisionFilter disambiguateAgainstGyro(double ratio);           // 0.4

  /** Pose inside [ -margin, fieldLength+margin ] x [ -margin, fieldWidth+margin ]. 6328 use 0.5 m. */
  public static VisionFilter withinField(double marginMeters);

  /** 6328: zMin -0.5, zMax 1.0. AdvantageKit: |z| < 0.75. PumpkinLib default -0.30 .. 0.50. */
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
   *  vision is at its worst (motion blur, extreme tag angles); a bad correction wrecks the whole auto. */
  public static VisionFilter ignoreEarlyAuto(double seconds);                 // default 0.0 (off)

  /** MEGATAG_2 frames from a camera whose robot_orientation_set is not being written. */
  public static VisionFilter requireMegaTag2Orientation();

  /** Per-camera kill switch, wired to a Tunable so a dead camera can be disabled in the pit. */
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
        maxPoseJump(1.0));
  }

  /** Everything in standard() plus the aggressive options elite teams run. */
  public static VisionFilter strict() {
    return standard()
        .and(disambiguateAgainstGyro(0.4))
        .and(ignoreEarlyAuto(2.0));
  }
}
```

Two implementation notes that matter:

- `maxGyroDisagreement` and `maxPoseJump` need the pose **at capture time**, not now. They use `VisionContext.estimateAtCapture()`, which comes from `PoseProvider.sampleAt(frame.timestampSeconds())` — verified signature `Optional<Pose2d> sampleAt(double timestampSeconds)`. When the Optional is empty, `withinOdometryBuffer()` has already rejected the frame, so these filters never fire on missing data.
- `maxGyroDisagreement` **skips gyro-fused sources**. Comparing MegaTag2's yaw against the gyro is comparing the gyro against itself. Testing it anyway produces a filter that never fires and a student who believes it is protecting them.

### 7.3 What gets logged

Per camera, per loop:

```
Vision/Camera<i>/Frames                struct[]:VisionFrameHeader   fixed-size headers, this loop
Vision/Camera<i>/Targets               struct[]:TargetObservation   joined to Frames by frameSequence
Vision/Camera<i>/TagIds                int[]      convenience view for AdvantageScope
Vision/Camera<i>/RobotPoses            Pose3d[]   every frame that carried a pose
Vision/Camera<i>/RobotPosesAccepted    Pose3d[]
Vision/Camera<i>/RobotPosesRejected    Pose3d[]
Vision/Camera<i>/TagPoses              Pose3d[]   layout poses of tags seen this loop
Vision/Camera<i>/RejectReasons         String[]   one per frame, parallel to RobotPoses
Vision/Camera<i>/RejectDetail          String[]   e.g. "z=0.83 m outside [-0.30, 0.50]"
Vision/Camera<i>/RejectCounts/<REASON> long       cumulative since boot
Vision/Camera<i>/AcceptRate            double     rolling 5 s
Vision/Camera<i>/CoalescedCount        long       accepted frames merged away by maxAcceptedPerLoop (9.6)
Vision/Camera<i>/DecodeDroppedCount    long       frames left undecoded by maxFramesPerLoop (9.6)
Vision/Camera<i>/StdDevXY              double     sigma actually handed to the estimator
Vision/Camera<i>/StdDevTheta           double     sigma actually handed to the estimator
Vision/Camera<i>/Fps                   double
Vision/Camera<i>/LatencySecs           double     now - frame.timestampSeconds, per accepted frame
Vision/Camera<i>/Connected             boolean
Vision/GyroFieldHeadingDeg             double     PoseProvider.getGyroFieldHeading()
Vision/FusedHeadingDeg                 double     PoseProvider.getPose().getRotation()
Vision/Summary/AcceptedCount           long
Vision/Summary/CoalescedCount          long
Vision/Summary/RejectCounts/<REASON>   long
Vision/Summary/DominantRejectReason    String     the most common non-ACCEPTED reason in the last 5 s
Vision/Consume/DurationMs              double     PumpkinTracer budget, 2.0 ms (9.6)
Vision/Arbiter/SelectedCameras         int[]      which cameras relocalized this loop (13.6)
Vision/Arbiter/Scores                  double[]   per-camera geometry score (13.6)
Vision/Arbiter/SuppressedCount         long
Vision/Seed/Count                      long       disabled multi-tag pose seeds issued (9.7)
Vision/Seed/LastSeedPose               Pose2d
Vision/Seed/LastSeedSource             String
Vision/Seed/RejectedNoAgree            long
Vision/Align/TagId                     int        active alignToTag lock (13.5)
Vision/Align/ErrorMeters               double     TAG-RELATIVE error, not fused-pose error
Vision/Align/ErrorDegrees              double
Vision/Align/ObservationAgeSecs        double
Vision/Align/NoSolveLoops              long
```

`Vision/GyroFieldHeadingDeg` and `Vision/FusedHeadingDeg` are logged side by side deliberately: if a team miswires `getGyroFieldHeading()` to the pose estimator, the two traces are identical in AdvantageScope and the MegaTag2 feedback loop is visible in one glance instead of being invisible forever.

`Vision/Camera<i>/RobotPoses*` keys and `Vision/Summary` are named exactly as the AdvantageKit template names them, so existing AdvantageScope layouts port unchanged.

The driver-dashboard widget (owned by the dashboard domain, fed by us) shows a per-camera reject-reason histogram. `PumpkinVision.rejectCounts(int cameraIndex)` returns `Map<RejectReason, Long>` for anyone who wants it in code.

---

## 8. Standard Deviation Model

### 8.0 Two rules that come before any model

Both of these were review findings, and both are the kind of bug that quietly ends a season.

> **Rule 1: `Double.POSITIVE_INFINITY` never appears in a standard deviation. Anywhere.**
>
> The earlier draft set `sigmaTheta = Double.POSITIVE_INFINITY` for gyro-fused sources and *then* multiplied it by a per-camera `Tunable` factor. A student setting that factor to `0` in the tuning dashboard — the obvious way to "turn this camera off" — produces `Infinity * 0.0 = NaN`. WPILib's `PoseEstimator.setVisionMeasurementStdDevs` then computes `r = NaN*NaN` and `visionK = q / (q + sqrt(q * NaN))` = `NaN`, and **every subsequent `addVisionMeasurement` writes NaN into the pose**. The estimator never recovers without a `resetPosition`. The robot's pose is `NaN` for the rest of the match, every downstream controller is `NaN`, and nothing in the log says why.
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
> Every place the old text said `POSITIVE_INFINITY` now says `StdDevModels.UNTRUSTED_SIGMA`, including the §9.4 structural override and `withAngularDisabled()`.

> **Rule 2: the per-camera factor is applied BEFORE the untrusted-theta override, never after.**
>
> Ordering is not cosmetic. Scaling first and pinning second means the pin is the last word and a factor of `0`, `1e9`, or `NaN` cannot corrupt it. Scaling second means the tunable multiplies a sentinel. The `bySource` / `scaledBy` / `withLatencyPenalty` decorators are therefore all applied **inside** `compute`, and the override is applied **outside**, in `PumpkinVision`, where no user code can reorder it.

### 8.1 Interface

The model writes into a caller-owned matrix. It does not allocate.

```java
package org.pumpkinlib.vision.stddev;

@FunctionalInterface
public interface StdDevModel {
  /**
   * Writes [sigmaX, sigmaY, sigmaTheta] (meters, meters, radians) into `out`.
   *
   * <p>OUT-PARAMETER, not a return value, and this is deliberate on two counts. (1) The zero-
   * allocation CI gate: at up to `maxFramesPerLoop` frames per camera per loop, returning a fresh
   * `Matrix<N3,N1>` allocates on the hot path every frame. `PumpkinVision` hands each camera a
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

### 8.2 The default — `StdDevModels.pumpkinDefault()`

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

This is the AdvantageKit 2026 template's model, with the infinity replaced by a finite sentinel and the multiply moved ahead of the pin. `cameraFactor` defaults to 1.0 and is a per-camera `Tunable`. Setting it to `0` now means "trust this camera absolutely", which is wrong but *recoverable and visible* — the final guard in §8.3 clamps it to `MIN_SIGMA` and logs the clamp. Setting it to `0` under the old design meant `NaN` forever.

### 8.3 The final guard — the last thing before the estimator sees a number

No matter what model, decorator, tunable, or user escape hatch produced the matrix, the `VisionConsumer` wrapper inside `PumpkinVision` checks it. This is the single choke point through which every vision measurement passes.

```java
// PumpkinVision, wrapping the team's VisionConsumer. Runs for EVERY accepted frame.
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
2. **The clamp is silent-but-logged, not silent.** `Vision/Camera<i>/StdDevXY` and `StdDevTheta` log the post-clamp values every accepted frame, so a team that has tuned `cameraFactor` into a corner can see it.
3. **This runs after every escape hatch**, including `allowVisionHeadingFromGyroFusedSources(true)`. There is no supported path around it. `StdDevModelTest` asserts that a deliberately hostile model returning `{NaN, 0.0, Infinity}` produces a rejection and leaves the estimator's pose finite.

### 8.4 The other shipped presets

```java
public final class StdDevModels {

  public static final double UNTRUSTED_SIGMA = 1.0e6;   // section 8.0 Rule 1
  public static final double MIN_SIGMA       = 1.0e-4;

  /** AdvantageKit 2026 template. d²/n, linear 0.02, angular 0.06; MT2 gets 0.5x linear, and the
   *  angular term is pinned to UNTRUSTED_SIGMA by the structural override in section 9.4. */
  public static StdDevModel advantageKit(double linearBaseline, double angularBaseline);

  /** PumpkinLib default == advantageKit(0.02, 0.06). */
  public static StdDevModel pumpkinDefault() { return advantageKit(0.02, 0.06); }

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

  /** Uses the `stddevs` NT array the Limelight itself publishes. Limelight sources only. */
  public static StdDevModel limelightReported();

  /** 8793's shipped model, for teams migrating from that codebase without changing behavior:
   *  base * (1.0 + d² / 30.0), base from a supplied Matrix. See 8793 VisionSubsystem.java:322. */
  public static StdDevModel legacy8793(Matrix<N3,N1> base);

  /** Per-source override table; anything unmapped falls through to `fallback`. */
  public static StdDevModel bySource(Map<PoseSource, StdDevModel> table, StdDevModel fallback);
}
```

**The rule we enforce structurally, not by documentation:** `PumpkinVision` wraps whatever model the team supplies so that any `frame.source().isGyroFused()` frame gets `sigmaTheta = StdDevModels.UNTRUSTED_SIGMA`, applied **after** every user-supplied decorator and per-camera factor, regardless of what the model returned. A user cannot accidentally close the MegaTag2 heading feedback loop, and cannot accidentally turn the sentinel into `NaN` by scaling it. There is one opt-out, `PumpkinVision.Builder.allowVisionHeadingFromGyroFusedSources(true)`, which logs a warning on every construction and exists only so the escape hatch is real — and even that path still passes through `sanitizeStdDevs` (§8.3).

---

## 9. Pose Estimator Integration

### 9.1 The front door

`PumpkinVision` is a `VirtualSubsystem`-style periodic object (not a `SubsystemBase`, so it never participates in command requirements — the template repo is state-based and would reject a mandatory `Subsystem`).

```java
package org.pumpkinlib.vision;

public final class PumpkinVision {

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
     * write PumpkinDrive's gyro->field offset (2.2), so it is a separate, explicitly-named hook
     * that takes `resetPose`, not `addVisionMeasurement`. Pass `m_drive::resetPose`.
     */
    public Builder disabledSeedConsumer(Consumer<Pose2d> resetPose);
    public Builder seedWhileDisabled(boolean enabled);              // default false; requires the hook above
    public Builder simEnabled(boolean enabled);
    public Builder logKey(String key);                             // default "Vision"
    public Builder allowVisionHeadingFromGyroFusedSources(boolean allow);  // default false
    public PumpkinVision build();
  }

  /** Call from robotPeriodic (or the state machine's periodic). Order-sensitive: see 9.4. */
  public void periodic();

  // ---- queries ----------------------------------------------------------------------------
  public int cameraCount();
  public String cameraName(int index);
  public boolean isConnected(int index);
  public boolean isFresh(int index, double maxAgeSeconds);
  public boolean hasRecentFix(double maxAgeSeconds);
  public double secondsSincePose(int index);
  public Map<RejectReason, Long> rejectCounts(int index);
  public Optional<VisionFrame> latestAcceptedFrame(int index);
  public Optional<TargetObservation> bestTag(int index);
  public Optional<TargetObservation> tag(int index, int fiducialId);
  /** This camera's mount transform, straight from VisionCameraIO.robotToCamera(). alignToTag
   *  (13.3) needs it to compose bestCameraToTarget into the robot frame. */
  public Optional<Transform3d> robotToCamera(int index);
  /** Capture timestamp of the frame `tag(index, id)` came from — alignToTag's staleness gate. */
  public double lastTagTimestamp(int index, int fiducialId);
  public List<DetectedObject> objects(int classId);
  public Optional<DetectedObject> bestObject(int classId);

  // ---- control ----------------------------------------------------------------------------
  public Command setPipeline(int cameraIndex, int pipeline, double timeoutSeconds);
  public void setTagIdFilter(int cameraIndex, int... ids);
  public void setEnabled(int cameraIndex, boolean enabled);
  public void setThrottle(int cameraIndex, int skipFrames);
  public void setRecording(boolean active);   // all cameras; auto-driven by FMS attach, see 11.4

  // ---- escape hatches ---------------------------------------------------------------------
  public VisionCameraIO io(int index);
  public AprilTagFieldLayout layout();
}
```

### 9.2 Feeding the estimator, and the Phoenix time-base trap

The consumer is exactly `SwerveDrivePoseEstimator::addVisionMeasurement` or `DifferentialDrivePoseEstimator::addVisionMeasurement` — the signature `(Pose2d, double, Matrix<N3,N1>)` matches both, and matches AdvantageKit's `VisionConsumer` byte for byte.

```java
// The one line a team writes:
.consumer(m_drive.getPoseEstimator()::addVisionMeasurement)
```

**CTRE trap.** A Phoenix 6 `SwerveDrivetrain` runs its own time base. 8793's `CommandSwerveDrivetrain.java:314-350` overrides all three `addVisionMeasurement`/`samplePoseAt` entry points solely to wrap timestamps in `Utils.fpgaToCurrentTime(...)`. Any library vision path that skips this silently corrupts the Kalman fusion in proportion to robot speed.

```java
// PumpkinVision.Builder.consumerIsPhoenixSwerve(true) installs this wrapper:
private static VisionConsumer phoenixWrapped(VisionConsumer inner) {
  return (pose, fpgaTimestamp, stdDevs) ->
      inner.accept(pose, com.ctre.phoenix6.Utils.fpgaToCurrentTime(fpgaTimestamp), stdDevs);
}
```

We detect the common case automatically: if `Class.forName("com.ctre.phoenix6.swerve.SwerveDrivetrain")` resolves **and** the consumer's declaring class is assignable to it, we install the wrapper and log `Vision/PhoenixTimeConversion = true`. If detection is ambiguous we do nothing and raise an info-level Finding telling the team to set the flag explicitly. Never guess silently in a way that changes numbers.

### 9.3 The alliance-flip trap

This is where teams lose a whole event, so it gets a rule, not a paragraph.

> **PumpkinLib never flips a measured pose, and never mutates an `AprilTagFieldLayout`.**

Specifics:

1. **Always blue origin.** We read `botpose_orb_wpiblue` / `botpose_wpiblue`, never `_wpired`. PhotonVision returns poses in the layout's origin, and we keep the layout at blue origin. The drivetrain's pose is blue-origin at all times, in both alliances, in auto and teleop. Red-alliance drivers see a rotated field on the dashboard; that is correct and is how PathPlanner, Choreo, AdvantageScope and every elite team work.
2. **`AprilTagFieldLayout.setOrigin()` is forbidden.** It mutates a shared object — if two subsystems hold the same instance and one flips it for red, the other silently sees flipped tag poses. `PumpkinVision.periodic()` asserts `layout.getOrigin().equals(Pose3d.kZero)` once per second and raises `PumpkinAlerts.error` naming `setOrigin` if it ever changes. `FieldLayouts` also hands out a defensive copy to any caller that asks for a mutable one.
3. **Flipping happens exactly once, for targets, in the alignment commands.** `VisionCommands.alignToNearest(...)` takes a *blue-authored* list of poses and calls `AllianceFlip.apply(...)` on each when `AllianceFlip.shouldFlip()`. That mirrors 4738's "author blue, derive red" pattern and PathPlanner's `FlippingUtil`, so robot code flips exactly the way the paths do.
4. **Alliance is latched at enable, not polled.** 8793 polls `DriverStation.getAlliance()` every 20 ms in four separate commands. `AllianceFlip` caches on the disabled→enabled edge and on FMS attach, and logs the value it latched.

### 9.4 Loop order

`PumpkinVision.periodic()` does the following, in this order, every loop:

```
1. Build VisionContext once (currentEstimate, gyro, gyroRate, now, autonomous, enabled).
2. For each camera: io.setRobotOrientation(getGyroFieldHeading(), gyroRate)  -> writes + flushes NT.
3. For each camera: io.updateInputs(inputs); PumpkinLog.processInputs("Vision/Camera<i>", inputs).
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
7. PumpkinTracer.start("Vision/Consume");
   For each surviving frame, OLDEST FIRST:
      a. stdDevModel.compute(frame, ctx, m_sd[i])            // out-parameter; m_sd[i] is preallocated
      b. if (frame.source().isGyroFused() && !allowVisionHeading)
             m_sd[i].set(2, 0, StdDevModels.UNTRUSTED_SIGMA)  // finite sentinel; NEVER Infinity (8.0)
      c. if (!sanitizeStdDevs(m_sd[i], i)) continue;          // 8.3 — NaN/Inf/<=0 becomes a rejection
      d. consumer.accept(frame.pose2d(), frame.timestampSeconds(), m_sd[i])
      e. tagResidualMonitor.record(frame, ctx)
   PumpkinTracer.stop("Vision/Consume");
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

`DifferentialDrivePoseEstimator.addVisionMeasurement` has the identical signature, so nothing changes. The only difference is the `PoseProvider` implementation. We ship `PoseProviders.fromDifferential(DifferentialDrivePoseEstimator, Gyro)` and `PoseProviders.fromSwerve(SwerveDrivePoseEstimator, Gyro)` as 10-line adapters so a team using raw WPILib does not have to write one.

### 9.6 The frame budget — why "drain every frame" needs two caps, not one

This is the section that corrects the most dangerous number in the earlier draft.

**What the earlier draft said.** `maxFramesPerLoop` defaulted to **20 per camera**, and every accepted frame called `consumer.accept(...)` → `addVisionMeasurement`. That was presented as a virtue: we drain every frame instead of taking `results.get(results.size()-1)` the way 8793's `VisionSubsystem.java` does.

**Why that is a loop-time bomb.** WPILib's `SwerveDrivePoseEstimator.addVisionMeasurement` is not a cheap accumulate. It replays the odometry buffer forward from the measurement's timestamp:

```java
// edu.wpi.first.math.estimator.PoseEstimator.addVisionMeasurement, structurally:
var tail = m_odometryPoseBuffer.getInternalBuffer().tailMap(timestampSeconds);
// ... then, for every entry in that tail:
updateWithTime(entryTimestamp, entryGyroAngle, entryWheelPositions);
```

Each replayed entry runs 4-module inverse kinematics plus a `Pose2d.exp`. Now put real numbers on it. A Phoenix 6 `SwerveDrivetrain` runs its odometry thread at **250 Hz**, and typical vision latency is **~80 ms**, so the tail from any given measurement holds roughly **20 entries**.

| | frames/camera/loop | cameras | `addVisionMeasurement` calls | `updateWithTime` invocations |
|---|---:|---:|---:|---:|
| Old default, after a hiccup | 20 | 2 | 40 | **~800** |
| New default, after a hiccup | 2 (accepted cap) | 2 | 4 | ~80 |
| Steady state, 120 fps LL4 @ 50 Hz | 2.4 | 2 | ~4 | ~80 |

**The 20-frame path is only ever reached after the loop has already overrun** — that is the only way 20 frames queue up between two iterations. So the old design responded to being behind by doing forty times the work, which puts it further behind, which queues more frames. That is a positive feedback loop triggered by exactly the principle the design was proudest of.

**The fix: cap accepted measurements, not raw frames, and keep both caps.**

```java
.maxFramesPerLoop(4)      // DECODE cap, per camera. Default 4.
.maxAcceptedPerLoop(2)    // MEASUREMENT cap, per camera. Default 2.
```

1. **`maxFramesPerLoop` (default 4) is a decode cap, enforced inside each `VisionCameraIO`.** A 120 fps LL4 on a 50 Hz loop produces 2.4 frames/camera/loop in steady state, so 4 is steady state plus 65 % headroom. Frames beyond the cap are **still drained from the NT queue** — we must drain or `pollStorage(20)` overflows and NT starts dropping samples of its own choosing — they are simply not decoded into a `VisionFrame`. The count goes to `Vision/Camera<i>/DecodeDroppedCount`.

   ```java
   // LimelightCameraIO.updateInputs — drain everything, decode at most m_maxFramesPerLoop.
   TimestampedDoubleArray[] queue = m_mt2Sub.readQueue();     // ALWAYS drains fully
   int start = Math.max(0, queue.length - m_maxFramesPerLoop); // keep the NEWEST when over budget
   m_decodeDropped += start;
   for (int i = start; i < queue.length; i++) { decode(queue[i]); }
   ```

2. **`maxAcceptedPerLoop` (default 2) is a measurement cap, enforced in `PumpkinVision` after filtering.** Every decoded frame is filtered, logged, counted, and shown in the diagnostics — the zero-mystery contract is untouched. Only the handoff to the estimator is rationed. When more than the cap survive filtering in one loop from one camera, we **keep the oldest and the newest**, and coalesce the middle:

   ```java
   // PumpkinVision.periodic() step 5.
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

3. **A declared budget, so the failure names itself.** `PumpkinTracer.budget("Vision/Consume", Milliseconds.of(2.0))` (§2.1) wraps step 7 of §9.4. Blowing 2 ms on vision consumption in a 20 ms loop is a warning with the frame counts printed, not a mystery overrun in `robotPeriodic`.

**Both caps are `Builder` knobs and neither is secretly clamped.** A team that has measured their own loop and wants `maxAcceptedPerLoop(6)` gets it. The defaults are chosen to be right for the 95 % case, and `Vision/Consume/DurationMs` is logged so raising them is an informed decision instead of a guess.

**Reviewer pushback, recorded because it is a fair objection:** capping accepted measurements does mean we discard information the cameras genuinely produced, which sits uneasily beside §4.4's "every IO drains every frame."

**Why we're doing it anyway:** the two claims are compatible once you separate *observation* from *fusion*. We still drain, decode (up to 4), filter, log, and count every frame — every diagnostic, every reject reason, every residual, every object detection sees the full stream, which is the part of "drain every frame" that has evidence behind it. What we ration is the one operation whose cost is superlinear and whose marginal value collapses after the second measurement in a 20 ms window: two measurements 8 ms apart from the same camera see essentially the same tags from essentially the same place. We are trading a redundant Kalman update for the loop time that a fifth camera, or the alignment controller, actually needs.

### 9.7 The disabled multi-tag pose seed

This is the only path in the entire library by which vision may write `PumpkinDrive`'s gyro→field offset (§2.2 item 3). It is therefore fenced in on five sides.

```java
// PumpkinVision.periodic() step 8. Runs ONLY when all of the following hold.
if (!DriverStation.isEnabled()            // 1. disabled only — never during a match
    && m_seedWhileDisabled                // 2. explicitly opted in
    && m_disabledSeedConsumer != null) {  // 3. the team supplied drive::resetPose
  for (VisionFrame f : acceptedThisLoop) {
    if (f.source().isGyroFused()) continue;   // 4. a gyro-fused solve cannot seed the gyro offset
    if (f.tagCount() < 2) continue;           // 5. multi-tag only — no single-tag PnP seeding
    if (!m_seedAgreement.accept(f.pose2d())) continue;  // 6. three consecutive agreeing frames
    m_disabledSeedConsumer.accept(f.pose2d());          // -> PumpkinDrive.resetPose(bluePose)
    PumpkinLog.output("Vision/Seed/LastSeedPose", Pose2d.struct, f.pose2d());
    PumpkinLog.output("Vision/Seed/Count", ++m_seedCount);
    break;                                    // one seed per loop, maximum
  }
}
```

Constraint 4 is the one that is easy to get wrong and fatal to get wrong. MegaTag2 consumes `getGyroFieldHeading()` to produce its translation. Using a MegaTag2 pose to set the offset that `getGyroFieldHeading()` is built from is a closed loop with a gain of one: it will agree with itself forever, including when it is 180° wrong. Only `MEGATAG_1`, `MULTI_TAG_COPROC`, `SINGLE_TAG_PNP` (excluded anyway by the `tagCount >= 2` rule) and `SIM` may seed.

`m_seedAgreement` is a 3-deep ring: a candidate seeds only if the last three qualifying poses agree within **10 cm and 3°**. One bad multi-tag solve on a robot sitting on a cart in the queue line otherwise reseeds the offset to garbage, and nothing downstream would notice until the match started.

```
Vision/Seed/Count             long     seeds issued since boot
Vision/Seed/LastSeedPose      Pose2d
Vision/Seed/LastSeedSource    String   PoseSource of the frame that seeded
Vision/Seed/RejectedNoAgree   long     candidates that failed the 3-frame agreement gate
```

---

## 10. Tag Layout Management

### 10.1 `FieldLayouts`

```java
package org.pumpkinlib.vision.field;

public final class FieldLayouts {

  /**
   * Resolution order, logged as Vision/Layout/Source:
   *   1. src/main/deploy/pumpkin/field-layout.json   (a WPIcal or hand-authored layout)
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

The 2026 REBUILT field ships two layouts and they are **different fields**:

```java
AprilTagFields.k2026RebuiltWelded     // PhotonVision's default
AprilTagFields.k2026RebuiltAndymark
```
(Verified against the WPILib 2026 `AprilTagFields` Javadoc: constants are `k2022RapidReact, k2023ChargedUp, k2024Crescendo, k2025ReefscapeWelded, k2025ReefscapeAndyMark, k2026RebuiltWelded, k2026RebuiltAndymark`, plus `kDefaultField`. Note the inconsistent capitalization: 2025 is `AndyMark`, 2026 is `Andymark`.)

`AprilTagFields.loadAprilTagLayoutField()` is deprecated; use `AprilTagFieldLayout.loadField(AprilTagFields)`.

**We refuse to default.** `PumpkinVision.Builder.layout(...)` has no default value. A team must write which field they are on. `kDefaultField` is an alias whose meaning changes between WPILib releases, and picking it silently is exactly the bug we are trying to prevent.

### 10.2 The deploy convention

```
src/main/deploy/pumpkin/field-layout.json      # optional; a WPIcal output or a custom field
src/main/deploy/pumpkin/vision.json            # optional; per-camera transform overrides, see 11.1
```

At boot we log:

```
Vision/Layout/Source        "deploy/pumpkin/field-layout.json"  |  "k2026RebuiltWelded"
Vision/Layout/Fingerprint   "a1f4c2..."
Vision/Layout/TagCount      22
Vision/Layout/FieldLength   17.548
Vision/Layout/FieldWidth     8.052
```

Putting the fingerprint in the log means "which layout was this match run against?" is answerable from the log file six weeks later, from a hotel room, without the robot.

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
Vision/TagHealth/Tag<id>/ResidualMeters     rolling median
Vision/TagHealth/Tag<id>/ResidualDegrees    rolling median
Vision/TagHealth/Tag<id>/SampleCount
Vision/TagHealth/WorstTag                   int
Vision/TagHealth/MedianResidualMeters       across all tags
```

This is the number that turns "vision feels off" into "tag 7's residual is 9 cm and everything else is 1.5 cm — go look at the field."

### 10.4 Coprocessor / robot layout mismatch — the #1 silent bug

There is no supported NT key on either vendor that reports which field layout the coprocessor loaded. **[UNVERIFIED]** — I could not find one in the Limelight complete-NetworkTables reference or the PhotonVision docs. So we detect it by consequence, three ways, and each one names itself.

**(a) `TAG_NOT_IN_LAYOUT`.** If the coprocessor is solving with a different tag set than we hold, some tag ids will not resolve. Cheapest possible canary; already in `VisionFilters.standard()`.

**(b) Systematic residual bias.** A layout mismatch produces a residual that is *systematic across all tags*, unlike a single misplaced tag or a bad calibration (which are localized or random). `TagResidualMonitor` raises:

```java
if (sampleCount >= 200
    && medianResidualAcrossAllTags > LAYOUT_MISMATCH_METERS      // Tunable, default 0.04
    && interQuartileRangeOfResiduals < medianResidualAcrossAllTags * 0.5) {
  PumpkinAlerts.error("Vision",
      "Layout mismatch suspected: median tag residual "
      + fmt(medianResidual) + " m across " + tagCount + " tags is systematic. "
      + "Robot code is on " + layoutSourceName + " (fingerprint " + shortHash + "). "
      + "Check that every coprocessor is on the SAME welded/andymark layout.").set(true);
}
```

**(c) Explicit handshake for custom coprocessors.** `pumpkinV1` publishes `/pumpkin_vision/<name>/layout_hash`. `CustomNTCameraIO` compares it against `FieldLayouts.fingerprint(ourLayout)` and raises a hard error on mismatch, naming both hashes. This is the only way to get a *certain* answer, and it is a strong argument for the `pumpkinV1` schema over the vendor formats. We publish the same key downward under `config/tag_layout` so a `pumpkinV1` coprocessor can simply adopt ours.

**(d) A build-time check for PhotonVision.** `FieldLayouts.logDeltas(welded, andymark, "Vision/Layout/WeldedVsAndymark")` runs once at boot and logs the maximum per-tag delta between the two 2026 layouts. If that number is smaller than our residual noise floor, we log an info Finding saying the automatic detector cannot distinguish them on this field and the team must verify by hand. **[UNVERIFIED]** — I do not have the numeric welded-vs-Andymark delta for 2026 REBUILT; for 2025 Reefscape the differences were on the order of 1 inch on some tags, which is comfortably above the noise floor, and I expect the same for 2026. Measure it at boot rather than hardcoding an expectation.

---

## 11. Setup & Diagnostics

### 11.1 Camera transforms live in code

Limelight's camera-to-robot transform lives in the web UI by default. That means it is invisible to sim, invisible to replay, and **gone the moment the camera is reflashed or a spare is swapped in.** Every pose is then wrong by the mount offset, with no error.

PumpkinLib's rule: **the transform is a `Transform3d` in robot code, and we push it to the camera.**

```java
package org.pumpkinlib.vision;

/** Declares where a camera is. One place, visible to sim, replay, logging and the dashboard. */
public record CameraMount(
    String name,
    /** Robot origin -> camera lens. WPILib convention: +x forward, +y left, +z up. */
    Transform3d robotToCamera,
    /** For a camera on a moving mechanism: returns empty when the mechanism angle at that
     *  timestamp is unknown, which correctly DROPS the frame instead of using a wrong transform.
     *  6328's CameraConfig.poseFunction. Null for a fixed mount. */
    DoubleFunction<Optional<Transform3d>> transformAt,
    /** Mounting is never as-CADded. 6328 run one camera at -4.5 deg. Tunable, applied on top. */
    DoubleSupplier pitchFudgeDegrees) {

  public static CameraMount fixed(String name, Transform3d robotToCamera) { ... }
  public static CameraMount onMechanism(String name, DoubleFunction<Optional<Transform3d>> f) { ... }
}
```

`LimelightCameraIO.pushCameraTransform(true)` (default) writes `camerapose_robotspace_set` at boot and every 5 s, then reads `camerapose_robotspace` back and compares:

```java
double[] want = { t.getX(), t.getY(), t.getZ(),
                  Units.radiansToDegrees(t.getRotation().getX()),
                  Units.radiansToDegrees(t.getRotation().getY()),
                  Units.radiansToDegrees(t.getRotation().getZ()) };
m_camPosePub.set(want);
NetworkTableInstance.getDefault().flush();
// ... next loop:
double[] got = m_camPoseSub.get();
if (maxAbsDiff(want, got) > 0.01) {
  PumpkinAlerts.error("Vision", name + ": camera transform did not take. "
      + "Wanted " + fmt(want) + ", camera reports " + fmt(got)).set(true);
}
```

We also raise a Finding when `robotToCamera` is `Transform3d.kZero` — a camera at the exact robot origin is always a forgotten default, never a real mount.

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
package org.pumpkinlib.vision.diag;

public final class VisionDiagnostics {
  public enum Severity { INFO, WARN, ERROR }
  public record Finding(Severity severity, String code, String cameraName,
                        String message, String remedy) {}

  public VisionDiagnostics(PumpkinVision vision);
  /** Safe to call in disabledPeriodic. Cheap; caches for 1 s. */
  public List<Finding> run();
  public void publishToNT(String key);
  public String toPlainText();
}
```

Checks, each producing a sentence a student can act on:

| Code | Fires when | Message |
|---|---|---|
| `CAM_DISCONNECTED` | `connected == false` for > 1.5 s | "front-left has not published for 3.2 s. Check power and Ethernet." |
| `CAM_SILENT` | connected but zero frames for > 1.5 s | "front-left is on the network but publishing no frames. Wrong pipeline?" |
| `TRANSFORM_ZERO` | `robotToCamera` is `Transform3d.kZero` | "limelight-front's robot-to-camera transform is (0,0,0). Every pose is off by the mount offset." |
| `TRANSFORM_REJECTED` | pushed transform readback mismatch | "limelight-front did not accept the camera transform." |
| `MT2_NO_ORIENTATION` | MT2 selected, orientation not written | "MegaTag2 is selected but robot_orientation_set has not been written in 0.8 s. MegaTag2 output is garbage without it." |
| `NO_CALIBRATION` | `getCameraMatrix()` empty | "front-left is uncalibrated at its current resolution. 3D mode will not work. Recalibrate at 1280x800." |
| `CALIB_REPROJ_HIGH` | reprojection error > 1.0 px (read from the PhotonVision config JSON) | "front-left's calibration reprojection error is 1.8 px; it should be under 1.0. Recalibrate." |
| `CALIB_FOV_MISMATCH` | computed FOV differs from the declared datasheet FOV by > 10° | "front-left's computed FOV is 63° but you declared 82°. The calibration is wrong." |
| `LATENCY_HIGH` | 95th-percentile frame age > 0.15 s | "back-right's frames are arriving 180 ms late. Check network load and camera FPS." |
| `TIMESTAMP_OUT_OF_BUFFER` | `ODOMETRY_BUFFER_MISS` > 5 % of frames | "back-right's timestamps are outside the 1.5 s odometry buffer. addVisionMeasurement is silently discarding them." |
| `HIGH_REJECT_RATE` | accept rate < 50 % over 5 s | "41 % of camera 1's frames were rejected for HIGH_AMBIGUITY. You are relying on single tags at range." |
| `LAYOUT_MISMATCH` | systematic residual (§10.4) | "Layout mismatch suspected: median residual 6.1 cm is systematic across 9 tags." |
| `TAG_RESIDUAL_OUTLIER` | one tag's residual > 3× the median | "Tag 7's residual is 9 cm; all other tags are under 2 cm. Field or layout problem." |
| `FPS_BELOW_CONFIGURED` | measured fps < 70 % of configured | "front-left is running at 41 fps but is configured for 120. Check exposure and CPU temperature." |
| `CPU_HOT` | coprocessor temp > 75 °C | "front-left is at 81 °C. It will throttle. Increase throttle_set or improve airflow." |

### 11.4 Match recording

Elite teams record what the camera actually saw, gated on FMS attach. 6328 debounce `DriverStation.isFMSAttached()` by 3 s and push `is_recording`; Limelight OS 2026 ships Rewind on LL4 (always-buffering, `.rwnd` bundles synchronized video + targeting + config, ~0.5–1 ms latency penalty).

```java
// Wired automatically by PumpkinVision unless the team calls setRecording() themselves.
m_fmsDebounce = new Debouncer(3.0, Debouncer.DebounceType.kBoth);
boolean record = m_fmsDebounce.calculate(DriverStation.isFMSAttached());
for (VisionCameraIO io : m_ios) io.setRecording(record);
```

`LimelightCameraIO.setRecording(true)` sets `rewind_enable_set = 1`; on the disabled edge after a match it fires `capture_rewind` with the configured duration. `CustomNTCameraIO` writes `config/is_recording`, `config/event_name`, `config/match_type`, `config/match_number`.

### 11.5 Throttling

```java
vision.setThrottle(cameraIndex, skipFrames);   // LL: throttle_set. PV: camera.setFPSLimit(). Northstar: config/throttle_fps
```
`PumpkinVision` exposes `setGlobalThrottle(int)` so a robot-wide "we need CPU elsewhere" signal (6328's `Robot.shouldThrottle()`) reaches every camera at once. Limelight also exposes `fiducial_downscale_set` via `LimelightCameraIO.setFiducialDownscale(int)`.

### 11.6 Typed SnapScript channel

The Limelight Python pipeline is the only way a small team gets custom CV without a second coprocessor, and today the robot↔script contract is two untyped double arrays that drift silently.

```java
package org.pumpkinlib.vision.limelight;

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
package org.pumpkinlib.vision.objects;

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
package org.pumpkinlib.vision.objects;

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

`Rotation2d.getTan()` exists in WPILib 2026. If an implementer prefers, `Math.tan(tx.getRadians())` is identical.

**Sign convention, stated once because this is a season-costing bug:** PhotonVision's `yaw` is documented "with left being the positive direction" and `pitch` "with up being the positive direction" — already our convention. Limelight's `tx` is positive to the **right**. `LimelightCameraIO` negates it at the IO boundary and nowhere else. There is a unit test (`ObjectProjectionTest.limelightAndPhotonAgreeOnTheSameSyntheticScene`) that builds the same synthetic target for both vendors and asserts the projected field positions match to 1 mm.

### 12.3 Source decoding

**Limelight neural detector.** `rawdetections` is `[classId, txnc, tync, ta, c0x, c0y, c1x, c1y, c2x, c2y, c3x, c3y]` per detection (stride 12). `LimelightHelpers.getRawDetections(name)` returns typed `RawDetection[]` with `classId, txnc, tync, ta, corner0_X .. corner3_Y`. We use the typed accessor for detections (they are low-rate and the array is small) and raw NT for botpose. `getDetectorClass(name)` gives the primary detection's class name; `getDetectorClassIndex(name)` the index.

**PhotonVision object detection.** Verified fields on `PhotonTrackedTarget`: `objDetectId` (int, -1 when N/A), `objDetectConf` (float, -1 when N/A), with accessors `getDetectedObjectClassID()` and `getDetectedObjectConfidence()`. Object detection is only available on specific hardware (Orange Pi 5 / Rubik Pi 3 as of 2026), and PhotonVision's shipped COCO and 2026 FUEL models are AGPLv3 (Ultralytics). PhotonVision supports model *conversion*, not training. Limelight's free trainer produces Limelight-format models tied to Hailo 8 / 8L / Coral / CPU runtimes. **Models are not portable between vendors and PumpkinLib does not pretend otherwise.**

### 12.4 `ObjectTracker`

A neural detector flickers. Feeding raw per-frame detections into an intake command produces a robot that lunges at noise.

```java
package org.pumpkinlib.vision.objects;

public final class ObjectTracker {
  public ObjectTracker(double associationRadiusMeters,   // default 0.35
                       double persistenceSeconds,        // default 0.5 — keep a track alive this long
                       int minSightingsToPromote);       // default 2

  void ingest(VisionFrame frame, VisionContext ctx);     // called by PumpkinVision.periodic

  public List<DetectedObject> tracks(int classId);
  /** Highest-confidence track of this class, breaking ties by proximity to `from`. */
  public Optional<DetectedObject> best(int classId, Translation2d from);
  /** Nearest track of this class within a radius. */
  public Optional<DetectedObject> nearest(int classId, Translation2d from, double maxRadiusMeters);
}
```

Tracks are associated by nearest field position across frames and across **cameras** — two cameras seeing the same fuel produce one track, not two. Logged to `Vision/Objects/Class<n>/Positions` as a `Translation2d[]` so it renders directly in AdvantageScope's 2D field view.

---

## 13. Vision-Driven Actions

These are the ready-to-use command factories that actually change a small team's score. They live in `org.pumpkinlib.vision.commands` and take a `Drive` interface, never a concrete drivetrain.

### 13.1 The drive seam

```java
package org.pumpkinlib.vision.commands;

/** The only thing the vision commands need from a drivetrain. */
public interface AlignableDrive {
  void driveFieldRelative(ChassisSpeeds speeds);
  /**
   * Robot-relative drive: +x forward, +y left, CCW-positive omega.
   *
   * <p>REQUIRED, not defaulted. `alignToTag` (13.3) closes the loop on a camera-to-tag transform,
   * which is a robot-frame quantity — routing it through a field-relative call would reintroduce
   * the fused pose's heading error into a controller whose entire purpose is to not depend on the
   * fused pose. A default implementation that rotated by `getPose().getRotation()` would silently
   * undo the feature, so there isn't one.
   */
  void driveRobotRelative(ChassisSpeeds speeds);
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
     * distance. With the default std-dev model, sigmaXY = 0.02 * d^2 / n, which is about 4.5 cm at
     * 3 m with two tags on MegaTag2 — so a 2 cm tolerance on `driveToPose` or `alignToNearest` is
     * a tolerance the sensor cannot satisfy, and the only thing it produces is a timeout alert on
     * every single alignment. `defaults()` is therefore 5 cm / 2 deg, which is what a fused-pose
     * controller can actually hold. Use `tagRelative()` with `alignToTag` when you need 2 cm.
     */
    double toleranceMeters, Rotation2d toleranceRotation,
    /** 6328's feedforward ramp: scale FF linearly between these two error radii so the robot
     *  neither slams nor creeps. clamp((err - min) / (max - min), 0, 1). */
    double ffMinRadiusMeters, double ffMaxRadiusMeters) {

  /** For FUSED-POSE commands (driveToPose, alignToNearest). 4.0 / 0.0 / 5.0 / 0.0, 3.0 m/s,
   *  4.0 m/s^2, 0.05 m, 2.0 deg, ff ramp 0.05 .. 0.60 m. */
  public static AlignGains defaults();

  /** For TAG-RELATIVE control (alignToTag). Same gains, tolerance 0.02 m / 1.0 deg, ff ramp
   *  0.02 .. 0.30 m — tighter because the feedback signal is the tag itself, not a fused pose,
   *  and its noise does not grow with the robot's distance from the field origin. */
  public static AlignGains tagRelative();
}
```

> **Cross-domain consequence, and it is a required change, not a suggestion.** `design/05` §9 currently gives `PumpkinDriveToPose` a default of `tolerance(Meters.of(0.02), Degrees.of(1.5))`. That default is unreachable for the same reason and must become `tolerance(Meters.of(0.05), Degrees.of(2.0))`, carrying the Javadoc line above. The Drive domain owns that edit; Vision owns the number and the reason.

### 13.3 `VisionCommands`

```java
package org.pumpkinlib.vision.commands;

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
   */
  public static Command driveToPose(
      AlignableDrive drive, PoseProvider pose, Supplier<Pose2d> target,
      PathConstraints approachConstraints, double handoffMeters,
      AlignGains gains, VisionFreshness freshness);

  /**
   * Auto-align to the nearest of a set of BLUE-AUTHORED scoring poses. Alliance flipping is applied
   * here and only here (section 9.3). `offset` is applied in the target's own frame, so
   * "20 cm back from the face, facing it" is expressed once and works at every scoring location.
   */
  public static Command alignToNearest(
      AlignableDrive drive, PoseProvider pose, List<Pose2d> blueTargets,
      Transform2d offset, PathConstraints approach, double handoffMeters,
      AlignGains gains, VisionFreshness freshness);

  /**
   * TAG-RELATIVE terminal alignment. Ships in v0.1, and it is the command that makes the
   * "compete against world-class teams" claim honest.
   *
   * <p>Every other command in this class closes the loop on the FUSED GLOBAL POSE, and is therefore
   * bounded below by the vision standard deviation at scoring range (~4.5 cm at 3 m, two tags).
   * This one closes the loop on `TargetObservation.bestCameraToTarget()` — the tag as the camera
   * actually sees it — composed with the camera mount transform. Odometry drift, gyro offset error,
   * layout error and alliance-flip mistakes all cancel out of the error term, because both the
   * measurement and the goal are expressed in the tag's own frame. That is why 2 cm is achievable
   * here and nowhere else.
   *
   * <p>This is a CONTROLLER, not new perception. `TargetObservation` already carries everything
   * required, which is why this moved from v0.3 into v0.1 for +0.4 person-weeks.
   *
   * @param cameraIndex      which camera owns this alignment; must report a robotToCamera transform
   * @param acceptableTagIds tag ids allowed to drive this alignment; the camera's tag filter is set
   *                         to these on init and RESTORED on end, so a defender's bumper tag or a
   *                         neighbouring scoring face cannot steal the solve
   * @param tagRelativeGoal  TAG -> desired ROBOT ORIGIN. "20 cm out from the face, squared up" is
   *                         written once and is correct at every tag on the field.
   */
  public static Command alignToTag(
      AlignableDrive drive, PumpkinVision vision, int cameraIndex,
      int[] acceptableTagIds, Transform3d tagRelativeGoal, AlignGains gains);

  /** As above, plus a staleness gate and odometry-based latency compensation. Preferred when the
   *  robot is still moving fast at handoff; see 13.5 for why the compensation matters. */
  public static Command alignToTag(
      AlignableDrive drive, PumpkinVision vision, PoseProvider pose, int cameraIndex,
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
      AlignableDrive drive, PoseProvider pose, MovingTargetSolver solver,
      Supplier<Translation3d> fieldTarget,
      DoubleSupplier vxSupplier, DoubleSupplier vySupplier, AlignGains gains);

  /**
   * Drive at the highest-confidence tracked object of a class and run `intakeCommand` while
   * approaching. Ends when the intake reports a piece, when maxSearchSeconds elapses, or when the
   * track is lost for longer than the tracker's persistence window.
   */
  public static Command autoIntake(
      AlignableDrive drive, PumpkinVision vision, PoseProvider pose,
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
}
```

### 13.4 The terminal controller

```java
// Inside DriveToPoseCommand.execute():
Pose2d current = m_pose.getPose();
Pose2d goal    = m_target.get();

Translation2d errorXY = goal.getTranslation().minus(current.getTranslation());
double errorNorm = errorXY.getNorm();

// 6328's feedforward ramp. Without it the robot either slams the last 5 cm or creeps for a second.
double ffScale = MathUtil.clamp(
    (errorNorm - m_gains.ffMinRadiusMeters())
        / (m_gains.ffMaxRadiusMeters() - m_gains.ffMinRadiusMeters()), 0.0, 1.0);

double linearVel = m_translationController.calculate(errorNorm, 0.0) * ffScale
                 + m_translationProfileVelocity;                       // profile FF
Translation2d velXY = new Translation2d(linearVel, errorXY.getAngle());

double omega = m_thetaController.calculate(
    current.getRotation().getRadians(), goal.getRotation().getRadians());

m_drive.driveFieldRelative(new ChassisSpeeds(velXY.getX(), velXY.getY(), omega));

// Freshness gate: if vision has gone stale, hold instead of finishing blind.
m_visionStale = !m_vision.isFresh(m_cameraIndex, m_freshness.maxAgeSeconds());
```

`isFinished()` requires **both** `errorNorm < toleranceMeters` and `|thetaError| < toleranceRotation` and `!m_visionStale` (when freshness is not `none()`), held for two consecutive loops.

Driver override is always available: `driveToPose` and `alignToNearest` accept an optional `abortIf(BooleanSupplier)`. Every automation in PumpkinLib has a manual escape hatch, because a single-button macro that depends on vision fails the moment a defender occludes the tag.

### 13.5 The tag-relative controller — `alignToTag`

Twelve lines, and they are the twelve lines that separate "our alignment mostly works" from "our alignment works."

```java
// AlignToTagCommand.execute()
Optional<TargetObservation> obs = m_vision.tag(m_cameraIndex, m_lockedTagId);
Optional<Transform3d> mount     = m_vision.robotToCamera(m_cameraIndex);

if (obs.isEmpty() || !obs.get().hasBestCameraToTarget() || mount.isEmpty()) {
  m_drive.stop();
  m_noSolveLoops++;
  return;                        // hold. Never extrapolate a tag we cannot see.
}

// ROBOT -> TAG, by composition. This is the whole trick: the fused pose appears nowhere.
Transform3d robotToTag = mount.get().plus(obs.get().bestCameraToTarget());

// ROBOT -> GOAL. tagRelativeGoal is TAG -> desired robot origin, so the composition is direct.
Transform3d error = robotToTag.plus(m_tagRelativeGoal);

Translation2d errXY = error.getTranslation().toTranslation2d();
Rotation2d    errTh = error.getRotation().toRotation2d();

double linearVel = m_translationController.calculate(errXY.getNorm(), 0.0) * ffScale(errXY.getNorm());
Translation2d vel = new Translation2d(linearVel, errXY.getAngle());
double omega = m_thetaController.calculate(0.0, errTh.getRadians());

// ROBOT-relative, because `error` is a robot-frame quantity. This is why AlignableDrive
// requires driveRobotRelative (13.1).
m_drive.driveRobotRelative(new ChassisSpeeds(vel.getX(), vel.getY(), omega));
```

Five details that are not optional:

1. **`Transform3d.plus` is composition, not addition.** `a.plus(b)` is "apply `a`, then `b` in `a`'s frame," which is exactly `robot→camera` then `camera→tag`. Writing this with `Translation3d` arithmetic instead is the classic way to get an alignment that is correct only when the robot faces the tag square-on.
2. **The tag id is latched on `initialize()`, not re-picked every loop.** `acceptableTagIds` selects *which* tag we lock to at the start (highest area among the acceptable ids); after that the id is fixed. Re-picking every loop makes the goal jump when a second acceptable tag comes into view mid-approach, and the robot lunges.
3. **The camera's tag filter is set to `acceptableTagIds` on `initialize()` and restored on `end()`.** `setTagIdFilter` costs an NT write and buys immunity to a defender's bumper tag. The previous filter is captured and restored even on interrupt.
4. **A missing solve holds; it never extrapolates.** Limelight only populates `bestCameraToTarget` for the primary tag via `targetpose_cameraspace` (§6.1), so a Limelight alignment that loses its primary tag stops. If `m_noSolveLoops` exceeds 25 (0.5 s) the command ends and raises `ALIGN_TAG_LOST` rather than sitting there.
5. **Latency.** The 6-arg overload does no compensation and documents it: at the ≤ 0.5 m/s terminal speeds where this controller operates, an 80 ms observation age is ≤ 4 cm and the controller converges through it. The `PoseProvider` overload does compensate, transforming the observation forward by the odometry delta between `frame.timestampSeconds()` and now — worth it when the handoff from `driveToPose` happens at speed.

`isFinished()` is `errXY.getNorm() < toleranceMeters && |errTh| < toleranceRotation`, held two consecutive loops, with a live solve in both. Logged to `Vision/Align/TagId`, `Vision/Align/ErrorMeters`, `Vision/Align/ErrorDegrees`, `Vision/Align/ObservationAgeSecs`, `Vision/Align/NoSolveLoops`.

### 13.6 `CameraArbiter` — which cameras get to relocalize

The earlier draft punted this to "a v0.4 experiment" on the reasoning that std-dev weighting *probably* dominates. That was not defensible: the dossier records that **2910 shipped automatic selection of the optimal camera to relocalize from**, specifically because feeding every camera into the estimator is not what top teams do, and "probably" is not an argument against shipped evidence from a team that wins. `CameraArbiter` is defined here and ships in **v0.2**.

```java
package org.pumpkinlib.vision.commands;

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

Logged as `Vision/Arbiter/SelectedCameras` (`int[]`), `Vision/Arbiter/Scores` (`double[]`), `Vision/Arbiter/SuppressedCount` (`long`). Which camera relocalized the robot, and why, is answerable from the log.

**Default stays `all()`.** `bestByGeometry` is a real behavior change and we will not flip a default on a hypothesis — but it is a one-line builder call, it is logged well enough to A/B on a practice field, and shipping it in v0.2 rather than "maybe v0.4" means a team can actually run that A/B during the season instead of reading an open question.

### 13.7 Aim-while-moving math

The vision-side half of shoot-on-the-move: given the robot's pose, its field-relative velocity, and a static field target, find the **virtual target** you must aim at so the projectile lands on the real one. Pure math, no vision or hardware dependency, fully unit-testable off-robot.

```java
package org.pumpkinlib.vision.commands;

/**
 * Iterative virtual-target solver. Move the TARGET backwards along the robot's drift during flight,
 * and re-solve, because time-of-flight depends on the corrected range. 3-5 iterations converge in
 * practice; 6328 run 20; 8793's ShooterSubsystem runs a hardcoded 10 with no convergence test.
 * PumpkinLib iterates to a TOLERANCE with a divergence guard, so it cannot sit in a limit cycle.
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
    /** Range (m) -> time of flight (s). Team-supplied; PumpkinLib supplies the solver, not the numbers. */
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

**Cross-domain seam:** the mechanism domain's shooter takes `Solution.effectiveRangeMeters()` into its own range→hood / range→RPM lookups, and `Solution.aimHeadingRateRadPerSec()` as a turret velocity feedforward. PumpkinLib Vision owns the geometry; the mechanism owns the ballistics table. If the mechanism domain also defines a `ShotSolver`, it must consume `MovingTargetSolver` rather than re-deriving the `omega x r` term — that is the specific piece both 8793 and the template implemented separately and that most teams get wrong.

**Gate on odometry quality.** Shoot-on-the-move multiplies pose error into miss distance: a 20 cm pose error aims 20 cm wrong at every range. `aimWhileMoving` refuses to engage (and logs `AIM_ODOMETRY_UNTRUSTED`) when `vision.hasRecentFix(0.5)` is false. Stopping to shoot beats confidently missing.

---

## 14. Vision Simulation

### 14.1 Architecture

PhotonVision's `VisionSystemSim` is the only production-grade FRC camera sim: it renders tag corners through real intrinsics and distortion, injects per-pixel noise, models FPS/exposure/latency distributions, and drives the *real* `PhotonCamera` NT topics so production code runs unchanged.

PumpkinLib routes **every** source through it — including Limelight — so `simEnabled(true)` is one boolean, not a rewrite.

```java
package org.pumpkinlib.vision.sim;

public final class PumpkinVisionSim {
  /** One process-wide sim world. Every *CameraIO.simulated(...) registers into it. */
  public static PumpkinVisionSim global();

  public void addAprilTags(AprilTagFieldLayout layout);
  /** VisionTargetSim(Pose3d, TargetModel, int objDetClassId, float objDetConf) — verified 4-arg ctor. */
  public void addGamePiece(String id, Pose3d pose, int classId, double confidence);
  public void moveGamePiece(String id, Pose3d pose);
  public void removeGamePiece(String id);
  public void clearGamePieces();

  /** Call once per loop from simulationPeriodic with the GROUND-TRUTH pose. */
  public void update(Pose2d groundTruthRobotPose);

  public Field2d debugField();                 // VisionSystemSim.getDebugField()
  public VisionSystemSim raw();                // escape hatch
}
```

### 14.2 Camera presets

PhotonVision's shipped `SimCameraProperties` factories stop at Limelight 2 (`PERFECT_90DEG()`, `PI4_LIFECAM_320_240()`, `PI4_LIFECAM_640_480()`, `LL2_640_480()`, `LL2_960_720()`, `LL2_1280_720()`). We add the sensors teams actually run in 2026.

**The presets live in the CORE artifact, as plain numbers.** `SimCameraProperties` is a photonlib type; if a preset returned one, `RobotContainer`'s `.simulated(...)` line would drag photonlib into every Limelight-only team's classpath and §2.6's artifact split would be fiction. So the presets return a core-owned record and `pumpkinlib-vision-sim` converts.

```java
package org.pumpkinlib.vision;   // CORE artifact — WPILib only, no photonlib

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
package org.pumpkinlib.vision.sim;   // SIM artifact — + photonlib

/** The single translation point between our numbers and PhotonVision's type. */
public final class PumpkinCameraProps {
  /** Applies setCalibration(w, h, fovDiag), setCalibError(avg, stdDev), setFPS, setAvgLatencyMs,
   *  setLatencyStdDevMs and setRandomSeed. This is the ONLY place those setters are called. */
  public static SimCameraProperties toPhoton(CameraSimProfile profile);

  /** Read a real PhotonVision config.json so sim uses your ACTUAL calibration. Best fidelity,
   *  and the only preset that cannot be expressed as a CameraSimProfile because it carries a full
   *  intrinsics matrix and distortion coefficients. Sim artifact only, by necessity. */
  public static SimCameraProperties fromPhotonConfigJson(Path configJson, int width, int height);
}
```

### 14.3 Simulating a Limelight — the part nobody has built

Limelight ships **no** simulation. A Limelight team cannot test an auto or an alignment without a robot and a field. Here is how PumpkinLib fixes that.

`SimulatedLimelight` stands up a hidden `PhotonCameraSim`, drains its results, and **re-encodes them into the Limelight NT wire format** on the table the production `LimelightCameraIO` is already subscribed to. Production code path is exercised unchanged, including the botpose index map and the latency math.

```java
package org.pumpkinlib.vision.sim;

public final class SimulatedLimelight implements AutoCloseable {

  /**
   * @param limelightName the REAL NT table name the robot code reads, e.g. "limelight-front"
   * @param robotToCamera the same Transform3d the real robot uses
   * @param props         the sensor model, e.g. CameraSimProfiles.OV9281_1280_800_82DEG()
   * @param mode          which botpose keys to publish
   */
  public SimulatedLimelight(String limelightName, Transform3d robotToCamera,
                            CameraSimProfile props, LimelightCameraIO.LimelightMode mode,
                            AprilTagFieldLayout layout);   // converts via PumpkinCameraProps.toPhoton

  /** Called from PumpkinVisionSim.update(). */
  void update(Pose2d groundTruthRobotPose);
}
```

Implementation, in order:

1. Construct a `PhotonCamera` on a **private, unpublished NT table** name (`"__pumpkinsim_" + limelightName`) so it never collides with a real camera and never shows up on a dashboard.
2. Construct `new PhotonCameraSim(camera, props, layout)` — the 3-arg constructor is verified and is what makes `multitagResult` available in sim. Register it into `PumpkinVisionSim.global().raw().addCamera(sim, robotToCamera)`.
3. Subscribe to `/<limelightName>/robot_orientation_set`, `/<limelightName>/pipeline`, `/<limelightName>/throttle_set`, `/<limelightName>/fiducial_id_filters_set` — **the sim honors the same control keys the real camera does**, so a pipeline switch or a tag filter behaves identically in sim.
4. Each loop, drain `camera.getAllUnreadResults()`. For each result:

   **MegaTag1 (`botpose_wpiblue`)** — from the coprocessor multitag result when present, else a single-tag PnP:
   ```java
   Optional<Pose3d> mt1 = result.getMultiTagResult()
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

5. Pack both into the exact 11 + 7n botpose layout of §5.2 and publish, with:
   ```java
   double latencyMs = (Timer.getFPGATimestamp() - result.getTimestampSeconds()) * 1000.0;
   arr[6] = latencyMs;
   ```
   so `LimelightCameraIO`'s production timestamp math reconstructs the correct capture time.
6. Also publish `tv`, `tx`, `ty`, `ta`, `tid`, `tl`, `cl`, `hb` (incremented), `getpipe`, `stddevs`, `rawfiducials` (stride 7, matching the real key layout), and `rawdetections` (stride 12) for any `VisionTargetSim` with an `objDetClassId >= 0`.

**Honest limitations, documented in the class Javadoc and surfaced as an info Finding when sim is active:**

- Sim does **not** reproduce MegaTag2's real gyro-fused ambiguity resolution, or MegaTag1's ambiguity flipping. Simulated vision is **optimistic** relative to a real LL4. Do not tune ambiguity thresholds in sim.
- Rolling-shutter smear is not modeled. `OV5647_*` presets model the frame rate and latency of an LL3A but not its motion artifacts.
- Limelight's Hailo neural detector is not simulated as a detector; object detection in sim is driven by `VisionTargetSim` objects you place yourself.
- No sim for `tdist`, `rawocr`, zero-shot classification, or SnapScript pipelines.

### 14.4 One-boolean wiring

```java
VisionCameraIO frontIo = LimelightCameraIO.megaTag2("limelight-front", TF_FRONT)
    .withImuMode(4)
    .simulated(CameraSimProfiles.OV9281_1280_800_82DEG());   // no-op on a real robot
```

`.simulated(...)` records the profile — a core-owned record of plain numbers, so **this line compiles with no photonlib on the classpath**. `PumpkinVision.build()` inspects `RobotBase.isSimulation()` and, only then, reflectively instantiates `PumpkinVisionSim` from `pumpkinlib-vision-sim` (§2.6). If that artifact is absent the builder raises the named warning and runs with no simulated cameras. No ternary in `RobotContainer`, no second IO class to write, no code path that exists only in sim, and no vendordep a Limelight-only team did not ask for.

`PumpkinVisionSim.global().update(groundTruthPose)` is called by the drive domain's `simulationPeriodic`. If the team is using maple-sim, ground truth is the maple-sim robot pose; otherwise it is the drivetrain's own sim pose.

---

## 15. End-to-End Usage Example

A four-camera, three-vendor, fully filtered, fully logged, fully simulated vision system. This is the entire amount of vision code a team writes.

```java
package frc.robot;

import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.math.util.Units;
import org.pumpkinlib.vision.*;
import org.pumpkinlib.vision.custom.CustomNTCameraIO;
import org.pumpkinlib.vision.field.FieldLayouts;
import org.pumpkinlib.vision.filter.VisionFilters;
import org.pumpkinlib.vision.limelight.LimelightCameraIO;
import org.pumpkinlib.vision.photon.PhotonCameraIO;      // pumpkinlib-photonvision artifact
import org.pumpkinlib.vision.stddev.StdDevModels;
// NOTE: no org.pumpkinlib.vision.sim import and no org.photonvision import anywhere in this file.
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
  private final PumpkinVision m_vision;

  public RobotContainer() {
    m_vision = PumpkinVision.builder()
        // deploy/pumpkin/field-layout.json wins if present; otherwise this. No default.
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
        .stdDevs(StdDevModels.pumpkinDefault())
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
            m_drive, m_drive, FieldTargets.BLUE_SCORING_POSES,
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
            m_drive, m_drive, m_shotSolver, () -> FieldTargets.HUB_CENTER,
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
  PumpkinVisionSim.global().update(m_drive.getSimGroundTruthPose());
}
```

That is everything. Four cameras, three vendors plus sim, correct timestamps, a named reason for every rejected frame, distance²-scaled std devs with the MegaTag2 heading rule enforced structurally and a finite untrusted sentinel that cannot become `NaN`, a bounded frame budget that cannot spiral, layout-mismatch detection, tag residual health, object tracking, and four ready-to-use commands including tag-relative 2 cm alignment. Adding a fifth camera of a fourth brand is one `.camera(...)` line.

**What a Limelight-only team writes instead:** delete the two `PhotonCameraIO` lines and the `CustomNTCameraIO` line, delete the `pumpkinlib-photonvision` vendordep, keep everything else including `.simulated(...)`. They still get the filter chain, the std-dev models, the diagnostics, layout management, object projection, `alignToTag`, and — with the one extra `pumpkinlib-vision-sim` vendordep — a simulated Limelight, which Limelight itself does not ship.

---

## 16. Testing

Pure-math surface is HAL-free and unit-tested off-robot (this was the single highest-leverage constraint in the 8793 dossier — the highest-risk math there is untestable):

| Test | Asserts |
|---|---|
| `BotposeDecodeTest` | A synthetic 11 + 7n array decodes to the expected `VisionFrame`, and a 9-element array is rejected as `MALFORMED_FRAME`. |
| `LimelightTimestampTest` | `ts = sampleMicros*1e-6 - latencyMs*1e-3` for a table of known inputs. |
| `ObjectProjectionTest` | The same synthetic scene, encoded as a Limelight detection and as a PhotonVision target, projects to field positions within 1 mm. Pins the tx sign convention. |
| `FilterChainTest` | Each filter fires its own `RejectReason` and only its own; `and()` reports the **first** rejection. |
| `StdDevModelTest` | `pumpkinDefault()` reproduces the AdvantageKit template's numbers exactly at d ∈ {1, 2, 4} and n ∈ {1, 2, 3}; every gyro-fused source yields `sigmaTheta == StdDevModels.UNTRUSTED_SIGMA` (finite, never `Infinity`) even when the supplied model does not. |
| `StdDevNaNGuardTest` | **The season-ending bug, pinned.** `cameraFactor = 0.0` on a gyro-fused frame yields a finite sigma, not `NaN`. A deliberately hostile model writing `{NaN, 0.0, Infinity}` produces a `RejectReason.CUSTOM` rejection, never reaches `addVisionMeasurement`, and leaves the estimator's pose finite. Asserts the ordering too: scaling by `1e9` then pinning leaves the pin intact. |
| `StdDevAllocationTest` | `compute(frame, ctx, out)` allocates zero bytes across 10 000 calls (the zero-allocation CI gate), and `withAngularDisabled()` does not mutate any matrix the wrapped model retains. |
| `FrameBudgetTest` | 20 queued frames on one camera produce at most `maxFramesPerLoop` decodes and at most `maxAcceptedPerLoop` `addVisionMeasurement` calls; the kept frames are the **oldest and the newest**; `CoalescedCount` and `DecodeDroppedCount` account for every dropped frame exactly once; the NT queue is fully drained regardless. |
| `AlignToTagTest` | A synthetic `bestCameraToTarget` plus a mount transform and a `tagRelativeGoal` produce the correct robot-frame error, verified against a hand-computed case at a non-zero robot heading (the case where `Translation3d` arithmetic instead of `Transform3d.plus` silently gives the wrong answer). Error is invariant when the fused pose is perturbed by 1 m — that invariance IS the feature. |
| `DisabledSeedTest` | A `MEGATAG_2` frame never seeds. A single-tag frame never seeds. Three disagreeing multi-tag frames never seed. Three agreeing ones seed exactly once. Seeding while enabled is impossible. |
| `ArtifactIsolationTest` | Compiles `pumpkinlib-vision` against a classpath with photonlib **absent**, and asserts no class outside `org.pumpkinlib.vision.photon` / `.sim` imports `org.photonvision.*`. Also asserts `.simulated(CameraSimProfiles.OV9281_1280_800_82DEG())` compiles and runs (as a no-op) in that classpath. |
| `MovingTargetSolverTest` | Zero velocity → virtual target == real target. Convergence within tolerance in ≤ 8 iterations for a realistic ToF map. The `omega x r` term produces the correct launch-point velocity for an off-center launcher (pinned against a hand-computed case). |
| `LayoutFingerprintTest` | Welded and Andymark 2026 layouts produce different fingerprints; reordering tags produces the same fingerprint. |
| `AllianceOriginTest` | No PumpkinLib code path calls `AprilTagFieldLayout.setOrigin`; the periodic origin assertion fires when a test mutates it. |
| `SimLimelightRoundTripTest` | `SimulatedLimelight` publishes, `LimelightCameraIO` decodes, and the resulting robot pose matches the ground-truth pose within 3 cm on a static scene with 3 tags in view. |

`SimLimelightRoundTripTest` is the important one: it proves the sim exercises the *production* decode path, which is the entire justification for the design.

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
| A logging framework or a replay engine | **AdvantageKit**, **AdvantageScope**, WPILib DataLog, CTRE SignalLogger | Call `PumpkinLog`; log the keys AdvantageScope layouts already expect. |
| A dashboard or a 3D viewer | **AdvantageScope**, **Elastic** | Publish stable NT keys and a reject-reason histogram the dashboard domain renders. |
| A neural-network trainer | **Limelight's free H100-backed trainer**, **PhotonVision's Colab conversion notebook** | Decode both vendors' detector output into one `DetectedObject`. Models are not portable and we say so. |
| A Limelight NT wrapper | **`LimelightHelpers.java`** (LimelightLib-WPIJava 1.14) | Vendor it verbatim so teams stop copy-pasting a 1,900-line file, expose it as an escape hatch, and use raw NT `readQueue()` on the hot path because `getBotPoseEstimate_*` drops frames and `getLatestResults()` parses JSON on the RIO. |
| A ballistics model | The mechanism domain's shot tables; the team's own measurements | `MovingTargetSolver` supplies the virtual-target geometry only. PumpkinLib supplies the solver and the tuning UI, never the numbers. |
| A replacement for `VisionSystemSim`'s renderer, or a Limelight firmware emulator | — | We re-encode PhotonVision's simulated output into the Limelight wire format. We simulate the *interface*, not the device. |

---

## 18. 2027 Migration

WPILib 2027 renames every Java package `edu.wpi.first.*` → `org.wpilib.*`, drops NT3, moves to SystemCore and Java 25, and replaces the command framework with Commands v3. AdvantageKit's main-branch templates are already on `org.wpilib.*`.

Vision's plan:

1. **The math is already portable.** `MovingTargetSolver`, `ObjectProjection`, `StdDevModels`, the botpose decoder, the filter predicates, and `LayoutFingerprint` depend only on geometry types and `Matrix`. A package rename is mechanical for them, and they contain no `Command`.
2. **Commands are quarantined.** Only `org.pumpkinlib.vision.commands` imports `edu.wpi.first.wpilibj2.command.*`. That is one package to rewrite for Commands v3, and the terminal-controller math inside it is already a plain class with an `execute()`-shaped method that a coroutine can call.
3. **No NT3 anywhere.** Every publisher/subscriber uses the NT4 typed topic API (`getDoubleArrayTopic(...).subscribe(...)` with `PubSubOption`), which survives.
4. **No Shuffleboard, no SmartDashboard.** `VisionDiagnostics.publishToNT` writes plain NT4 topics. Both of those dashboards are deleted in 2027.
5. **Three artifacts, two branches, one source tree.** `pumpkinlib-vision`, `pumpkinlib-photonvision` and `pumpkinlib-vision-sim` (§2.6) each publish a `2026.x` and a `2027.x`, differing by the import root, the Java level, and the commands package. The split helps the port rather than tripling it: `pumpkinlib-vision` depends only on WPILib, so it is a pure mechanical rename and can be ported and released **before** PhotonVision has a 2027 build at all. The two photonlib-bearing artifacts are the only ones whose port is blocked on a vendor, and together they are one enum, one adapter and three sim classes.
6. **Java-17-safe subset now** so the port is mechanical: no pattern matching for `switch`, no record patterns, no sealed-interface exhaustiveness tricks.
7. Assume **PathPlanner and Choreo both break in 2027** (both deliberately froze 2026). `VisionCommands.driveToPose` touches PathPlanner in exactly one place, behind a `PathfindingBackend` interface with a `NoPathfinding` fallback.

---

## 19. Effort & Phasing

| Phase | Contents | Person-weeks |
|---|---|---|
| **v0.1 (ship first)** | `VisionFrame` + `VisionFrameHeader`, `TargetObservation`, `PoseSource`, `CameraSimProfile`/`CameraSimProfiles`, `VisionCameraIO`, `LimelightCameraIO` (MT1/MT2/BOTH), `PhotonCameraIO` (2026 API), `ReplayCameraIO`, filter chain + `RejectReason` + `standard()`, `StdDevModels` (3 presets) + the `UNTRUSTED_SIGMA`/`sanitizeStdDevs` guard, `PumpkinVision` builder + the §9.6 frame budget + the §9.7 disabled seed, `FieldLayouts.resolve/fingerprint`, the full log key set, `PumpkinVisionSim` + `PumpkinCameraProps` + `SimulatedLimelight`, `VisionDiagnostics` (10 of 14 checks), **`VisionCommands.alignToTag` + `AlignableDrive` + `AlignGains`**. | **4.4** |
| **v0.2** | `CustomNTCameraIO` + `NorthstarSchema`, `DetectedObject` + `ObjectProjection` + `ObjectTracker`, `TagResidualMonitor` + layout-mismatch detection, `VisionCommands.driveToPose` / `alignToNearest` / `aimAtPoint` / `setPipeline`, **`CameraArbiter` (§13.6)**, remaining diagnostics. | **3.3** |
| **v0.3** | `MovingTargetSolver` + `aimWhileMoving`, `autoIntake`, `PumpkinV1Schema` + `Struct` implementations, `SnapScriptChannel`, `AdvantageKitCompat`, match recording, throttling. | **2.5** |
| **v0.4 (offseason stretch)** | `pumpkin_vision` Python package for coprocessors, WPIcal deploy tooling, calibration-JSON ingestion for the reprojection/FOV checks. | **2.0** |
| **Total** | | **12.2 person-weeks** |

**Why `alignToTag` moved from v0.3 into v0.1, for +0.4 person-weeks.** v0.1's headline claim is that a small team gets competitive scoring alignment. Without a tag-relative controller, v0.1 shipped only fused-pose alignment, which is bounded below by the vision standard deviation at scoring range — roughly 4.5 cm at 3 m with two tags on MegaTag2. A team would have installed the library, set a 2 cm tolerance because that is what the mechanism needs, and gotten a timeout alert on every alignment. Shipping perception without the one controller that can consume it correctly is shipping the claim without the capability. `TargetObservation` already carries `bestCameraToTarget` and the mount transform is already in `VisionCameraIO`, so the marginal work is a controller and a test, not new perception. `CameraArbiter` follows in v0.2 for +0.3.

Realistically for one experienced mentor at offseason pace: v0.1 by late September 2026, v0.3 by mid-November, leaving December for integration with the other domains and January for the 2027 port. That is tight but achievable, and v0.1 alone is already better than what any small team has today.

---

## 20. Risks

1. **The three-artifact split is real complexity, and CI is the only thing keeping it honest.** ~~PhotonVision is a hard dependency even for Limelight-only teams~~ — that was the earlier draft's position and it was wrong (§2.6). It violated Principle 5, Principle 12 and our own one-vendordep promise, and it made our ship date hostage to PhotonVision's. The split fixes it, and introduces its own risk: three Gradle modules, three Maven coordinates, and one `CameraSimProfile → SimCameraProperties` translation point that only exists to keep photonlib out of the core artifact's type signatures. If nobody maintains that boundary it will rot the first time someone finds it convenient to accept a `SimCameraProperties` in core. Mitigation: `ArtifactIsolationTest` (§16) compiles the core artifact against a photonlib-free classpath on every CI run and fails the build on any `org.photonvision.*` import outside the two designated packages. The vendor-neutrality claim is checked mechanically or it is not a claim. Residual risk: a Limelight-only team that wants *camera simulation* still installs one photonlib-bearing vendordep (`pumpkinlib-vision-sim`). That is an honest, opt-in, sim-only cost, and the alternative is writing a camera renderer, which we will not do.
2. **`SimulatedLimelight` is genuinely novel and therefore genuinely unproven.** The MegaTag2 reconstruction in §14.3 is my own derivation of what MegaTag2 does conceptually, not a vendor-documented algorithm. It will be optimistic relative to the real device. `SimLimelightRoundTripTest` pins the round trip, but only against my own encoder. Mitigation: publish the limitation prominently; validate against a real LL4 on a practice field in October.
3. **The `pumpkinV1` wire schema has no adopters on day one.** Its value is the layout-hash handshake and correct timestamping, but a team with a working Northstar has no reason to migrate. Mitigation: ship `NorthstarSchema` first and make `pumpkinV1` the *new-coprocessor* path, not a migration ask.
4. **Filter-chain tuning could become the new footgun.** Fifteen filters with fifteen tunable thresholds is more rope than one boolean expression. Mitigation: `standard()` is the documented default and the diagnostics name the *dominant* reject reason, so a team is pushed toward "why is this one reason firing" rather than "let me loosen everything."
5. **Timestamp correctness cannot be verified without hardware.** Every claim in §5 is derived from vendor docs and from AdvantageKit's shipped implementation. A 20 ms systematic error would be invisible in sim and cost 8 cm at 4 m/s on the field. Mitigation: `Vision/Camera<i>/LatencySecs` and `NetworkTransitEstimateSecs` are logged every frame so the numbers are auditable in a real log, and the odometry-vs-vision disagreement statistic in `VisionDiagnostics` is a direct empirical check.
6. **2027 lands in ~4 months.** If the port slips, PumpkinLib Vision ships one usable season. Mitigation: the quarantine strategy in §18, plus starting the `2027` branch against `2027.0.0-alpha-*` in October rather than after the release.
7. **Layout-mismatch detection is statistical, not certain**, for both vendors (§10.4). A team could still run a mismatched layout for a whole event if their residuals are noisy. Mitigation: the `TAG_NOT_IN_LAYOUT` canary catches the common case immediately, and `pumpkinV1` makes it certain for custom coprocessors. Push vendors for a layout-identity NT key.
8. **`maxAcceptedPerLoop = 2` is a judgement call made from arithmetic, not from a robot.** The §9.6 reasoning about `addVisionMeasurement`'s odometry replay is structurally sound and the old default of 20 was indefensible, but the claim that the third and later measurements in a 20 ms window add negligible information is an argument, not a measurement. If it is wrong, we are throwing away real corrections at exactly the moment a team most needs them. Mitigation: nothing is hidden — `CoalescedCount` counts every discarded measurement, `Vision/Consume/DurationMs` shows what the cap bought, and the cap is one builder call to raise. Measure it on a real robot with a 250 Hz Phoenix odometry thread in October and revise the default if the data disagrees.
9. **`alignToTag` is only as good as `bestCameraToTarget`, and Limelight populates it for the primary tag only** (§6.1). A Limelight team's tag-relative alignment therefore depends on one `targetpose_cameraspace` read and stops the moment the primary tag changes or is occluded. PhotonVision supplies it per target and has no such limitation. Mitigation: the command holds rather than extrapolating, ends with a named `ALIGN_TAG_LOST` after 0.5 s, and the asymmetry is documented rather than smoothed over. A Limelight team that wants robust tag-relative alignment should lock a single tag id via `acceptableTagIds`.

---

## 21. Open Questions

1. **Limelight OS version reporting.** There is no documented NT key exposing the Limelight OS version. Without it we cannot verify the middle-of-exposure timestamp convention (2026.0+) at runtime, and we cannot warn a team running 2025 firmware. Is there an undocumented key, or should we detect it by behavior? **[UNVERIFIED]**
2. **Coprocessor field-layout identity.** Neither PhotonVision nor Limelight publishes which layout it loaded. Is there a PhotonVision NT topic under the camera table that carries this? If PhotonVision would add one, it would eliminate the #1 silent bug in FRC vision outright. Worth an upstream PR.
3. **Limelight pipeline settle time.** Undocumented. We measure and log it, but should `SETTLE_FRAMES = 3` be the default, or should the default be "wait for `getpipe` match only" with the heartbeat requirement opt-in? Needs bench measurement on LL3G and LL4. **[UNVERIFIED]**
4. **`tdist` in Limelight OS 2026.1.** The dossier reports a new `tdist` (3D distance to target/POI) key, but it does not appear in the complete-NetworkTables reference I fetched. If it exists it is a cheap, high-quality distance source for object detection. Needs verification against a real 2026.1 camera. **[UNVERIFIED]**
5. **Welded vs Andymark delta magnitude for 2026 REBUILT.** I do not have the per-tag numbers. If the maximum delta is below our residual noise floor, the statistical mismatch detector is useless for 2026 and the `TAG_NOT_IN_LAYOUT` canary plus the `pumpkinV1` handshake are the only real defenses. Measure at boot with `FieldLayouts.logDeltas`. **[UNVERIFIED]**
6. **Should `ignoreEarlyAuto` default on?** 6328 run `autoIgnoreTimeSecs = 2.0`. For a team that resets odometry from the auto's starting pose it is clearly right. For a team that *seeds* odometry from vision at auto start (the template's `hasRecentFix()` pattern) it is wrong. Currently defaults **off**; possibly it should default to on only when the auto declared `resetOdom: true`. Needs a decision jointly with the Auto domain.
7. **~~Multi-camera arbitration.~~ RESOLVED — `CameraArbiter` is specified in §13.6 and ships in v0.2.** The earlier text punted this to "a v0.4 experiment" on the reasoning that std-dev weighting *probably* dominates. That was not a defensible answer to shipped evidence: 2910 built "automatic selection of optimal camera to relocalize from" on purpose, and "probably" does not outrank a team that wins. The design decision is now explicit and split in two: the **default remains `CameraArbiter.all()`**, because flipping a behavioral default on a hypothesis is exactly the mistake we criticize elsewhere; but the alternative is **built, logged and one builder call away** in v0.2, so the A/B can actually be run on a practice field during the season rather than read as an open question. What genuinely remains open is only the *outcome* of that A/B and whether `bestByGeometry`'s scoring function should weight view angle explicitly in addition to tag span.
8. **Moving-mount transforms and replay.** `CameraMount.transformAt(timestamp)` calls back into a mechanism's position history. In AdvantageKit replay that history must itself have come from logged inputs. Does the mechanism domain guarantee a replayable `TimeInterpolatableBuffer` of mechanism angles? If not, a turret-mounted camera is not replayable and we should say so.
9. **`stddevs` NT key semantics.** Limelight publishes a 12-element MT1/MT2 std-dev array. Are those numbers in the same units and the same statistical sense as WPILib's `visionMeasurementStdDevs`? If yes, `StdDevModels.limelightReported()` becomes attractive as a default for Limelight cameras. **[UNVERIFIED]**
10. **~~`PumpkinLog` replay of `VisionFrame[]`.~~ RESOLVED in §4.3 — one confirmation still needed.** `VisionFrame` is a plain record and is never serialized; the fixed-size `VisionFrameHeader` and `TargetObservation` go on two topics joined by `frameSequence`, and both are `Struct<T>[]`, which is the one shape WPILib's struct system is designed for. The only thing left for the Logging domain is to confirm `PumpkinLogTable` supports `Struct<T>[]` get/put — no other capability is required, and no max-frames cap is needed.

11. **`alignToTag` latency compensation at handoff speed.** The 6-arg overload deliberately does no compensation (§13.5 detail 5) on the argument that an 80 ms observation age is ≤ 4 cm at terminal speeds. That argument holds at ≤ 0.5 m/s and gets weaker fast above it. Should the `PoseProvider` overload become the *default* by making the 6-arg form delegate to it whenever a `PoseProvider` is reachable? Needs a measurement of the actual handoff speed out of `driveToPose` on a real robot before deciding.

12. **`CameraArbiter.bestByGeometry` scoring.** `tagCount * tagSpan / d²` is a defensible first cut chosen to agree with the std-dev model rather than fight it, but it ignores view angle, and a tag seen at 75° off-normal is worth much less than the same tag seen at 20°. `TargetObservation.bestCameraToTarget()` carries enough to compute the incidence angle. Add it, or does the tag-span term already capture most of the effect? **Needs an A/B, and §13.6 is built so the A/B is cheap.**

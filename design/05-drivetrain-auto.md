# PumpkinLib Design 05 — Drivetrain, Autonomous, and Mechanism Action Coordination

**Status:** Design complete, ready to implement — **revision 3, after the four binding maintainer decisions of 2026-08-07.**
**Owner domain:** drivetrain + autonomous + mechanism-action coordination
**Target:** WPILib 2026.x / Java 17 / GradleRIO 2026.2.1 / Phoenix 6 26.x / REVLib 2026.0.5 / PathPlannerLib 2026.1.2 / ChoreoLib 2026.0.3 / PhotonVision 2026.x / **AdvantageKit 26.0.2 (REQUIRED — maintainer decision 3)**
**Written:** 2026-08-06 (offseason; 2027 kickoff 2027-01-09)
**License:** BSD-3-Clause ([`LICENSE`](../LICENSE)).

**Revision 3 changelog — what the four decisions did to this document.** No drivetrain or auto engineering changed. Scope, packaging and the dependency floor did:

| # | Decision | Effect here |
|---|---|---|
| A | **1 — one release, v0.1, containing everything** | §13's v0.1 / v0.2 / v0.3 / v0.4 delivery plan is **deleted as a release plan** and survives only as build **order**, remapped onto **M9, M11, M15** (and the port, **M12**) in [`ROADMAP.md` §5](../ROADMAP.md), which is authoritative for every date. Nothing in this domain is deferred *out of* the release. Where this document said "deferred to v0.2/v0.3," read "built at a later milestone of the same release." Two items — `MecanumBackend` and `PumpkinNav.useProfile` — are **not in any milestone** and are therefore *post-v0.1*, which is a genuine reduction and is labelled as one. |
| B | **2 — `PumpkinTemplate` is the front door** | The template's `differential` variant is the reason `DifferentialBackend` is non-optional; it is **not real until M15**, and `pumpkin init --template differential` must fail with a named message before then rather than generate a non-driving project. |
| C | **3 — AdvantageKit is REQUIRED** | The `PumpkinLog` **fan-out SPI in §1.1 is deleted.** There is one logging path (AdvantageKit's `Logger`) and no `AdvantageKitSink` / `DataLogSink` / `HootSink` / `NtSink` / `NullSink` to choose between. Replay determinism — which `LocalADStarAK`, the trigger engine and `OdometryReport` all depend on — is now a **guarantee**, not a configuration. The cost: a team on DogLog or plain Epilogue cannot adopt PumpkinLib at all. |
| D | **1 + §7.2 of the roadmap** | The **two-artifacts-from-one-tree** plan in §12 item 7 is reversed. The project is **single-line** after M12; the 2026 line and the rename generator are deleted at the end of it. |
| E | **4 — BSD-3-Clause** | Licence decided. No "TBD" anywhere. |

**Revision 3.1 changelog — what the 2026-08-07 six-lens design review did to this document.** Five findings were routed here. Three were applied in full at the time; two were cross-domain contract changes that a separate reconciliation pass owned, and were marked in place with `<!-- CONTRACT-PENDING -->` comments rather than edited, so two parallel edits could not collide on the same seam.

**Revision 3.2, 2026-08-08 — the contract-reconciliation pass ran, and R1 and R2 are now APPLIED.** Every `CONTRACT-PENDING` **marker** in this document is resolved and removed. The string still appears in the revision-3.1 paragraph above, in this paragraph, in the R1 and R2 rows below, and in §15.2 rows 9 and 10 — in every case as backtick-quoted prose describing what *used* to be there, never as a marker. **There are no live HTML comments left**: `grep -c '^ *<!-- CONTRACT-PENDING' design/05-drivetrain-auto.md` returns `0`, which is the check that actually distinguishes a marker from a memory of one. The dispositions in the R1/R2 rows below are updated in place and the original wording is kept so the history is legible. One further blocking finding was applied in the same pass and is not attributable to the six-lens review: REVIEW **B10**, this document's `PumpkinLog` call sites, which were written against a facade shape `design/04` does not ship (§1.1).

| # | Finding | Severity | Disposition |
|---|---|---|---|
| R1 | The MegaTag2 gyro→field-offset contract (`design/03` §2.2, D16/D16a) was ordered on this document and never applied: `getGyroHeading()` still ships, no `getRawGyro()`, no `m_gyroFieldOffset`, no `getGyroFieldHeading()`, no ninth `DriveSelfCheck`, no `PoseProvider`/`AlignableDrive`/`VisionConsumer` in `org.pumpkinlib.drive`. `[SUPERSEDED-NAME]` | blocking | ~~**CONTRACT-PENDING.**~~ **APPLIED IN FULL, revision 3.2 (2026-08-08), by the contract-reconciliation pass.** `DriveBackend.getGyroHeading()` is deleted and replaced by `getRawGyro()` (§3.2); the four D16 interfaces are declared in **§3.3.2 and nowhere else in the design**; the offset, its exactly-two writers, the conversion and the never-writes prohibition are owned by **§3.3.3**; `DriveSelfCheck` check **9** is added (§3.8) with `design/03`'s alert text unparaphrased; §2's package layout lists all four types. Revision 3.1 deliberately did not apply this, to avoid a conflicting parallel edit on the highest-stakes seam in the library; that reason expired when the reconciliation pass took the seam. |
| R2 | Binding **D17** deleted `VisionObservation`; this document still defines it and still declares `addVisionMeasurement(VisionObservation)`. `[SUPERSEDED-NAME]` | major | ~~**CONTRACT-PENDING** for the type change~~ — **APPLIED, revision 3.2 (2026-08-08).** The record is deleted from §1.3, `VisionConsumer` is declared in §3.3.2, `PumpkinDrive implements … VisionConsumer` and its sink is `accept(Pose2d, double, Matrix<N3,N1>)` (§3.3), and §8.3's timestamp paragraph names the method. **The part that needed a design answer rather than a mechanical edit was answered in full at revision 3.1** — `tagCount`/`avgTagDistanceMeters` do not survive D17's three-argument signature, and §8.3 says where they go and why widening the signature would be wrong. |
| R3 | The auto DSL claimed **2 cm** from `alignAtEnd`, a fused-pose controller; `design/03` §13.3 states as an invariant that 2 cm "is achievable [in `alignToTag`] and nowhere else," and `alignToTag` was unreachable from `AutoStep`. | major | **APPLIED, both halves.** `AutoStep.alignToTagAtEnd(...)` added (§6.4) with a documented fallback to `alignAtEnd` when vision is absent, wired through `PumpkinAuto.withVision(...)` (§5.1) so `org.pumpkinlib.auto` imports nothing from `org.pumpkinlib.vision`; **and** every 2 cm claim on the fused-pose path is corrected with the arithmetic shown (§6.7, §9). +0.2 pw booked against M11 (§13). |
| R4 | `design/03` §13.2's *"required change, not a suggestion"* — `PumpkinDriveToPose` default `tolerance(0.02 m, 1.5°)` → `tolerance(0.05 m, 2.0°)` — was never made, in the builder or in either worked example. | major | **APPLIED** at §9's builder and §10's example, with `design/03` §13.2's javadoc and the sigma derivation reproduced at the builder (§9). The third site, `DESIGN.md` §10B line 1112, belongs to that document — requested, not edited here. **Extended 2026-08-08 (`DESIGN.md` §16 item 2's named residue, which was this document's one-line edit and is now made):** the number is declared **once**, at §9's `PumpkinDriveToPose.kDefaultTolerance` / `kDefaultAngularTolerance`, and the three sites that used to retype it read it by name instead — §10's worked example, §9's builder-default comment, and §6.5's `alignToTagAtEnd` fused fallback. §6.7.1 still *states* 5 cm / 2.0°, because that is the section that derives it, and it now says so explicitly rather than reading like a second declaration. **Verified 2026-08-08:** `grep -n 'tolerance(Meters\.of(0\.05)' design/05-drivetrain-auto.md` returns **zero**, and `grep -c 'kDefaultTolerance\|kDefaultAngularTolerance' design/05-drivetrain-auto.md` returns **11** lines — the 2-line declaration (§9), the 2-line DSL fused fallback (§6.5), the 2-line builder-default comment (§9), the 2-line §10 call site, §6.7.1's derivation pointer, §15.2 row 12's record, and this cell. **The count is not the gate**; the gate is that the *literal* count is zero, which is the grep above it. `DESIGN.md` §10B was independently closed at that document's revision 7 and also reads the constants by name, so the tolerance is now a literal in exactly one place in the whole design. |
| R5 | Raw literal `9_999_999` for vision heading trust where `design/03` §8.0 Rule 1 establishes `StdDevModels.UNTRUSTED_SIGMA = 1e6` as the library-wide named sentinel. | minor | **APPLIED** at all four sites (§3.4.4, §8.2, §8.3 ×2), and §8.3's competing std-dev *formula* is retired in favour of `design/03` §8.4's model, which that domain owns (§0.2). |

Four further items that name this document elsewhere in the review, and are applied here because nothing else edits this file:

- **REVIEW M5 / binding D10** — `MatchImpact` is required at every alert call site with no default and no single-argument overload. This document used a two-argument `org.pumpkinlib.config.PumpkinAlerts` facade throughout. Swept to `org.pumpkinlib.core.alert.Alerts.error/warning(group, text, MatchImpact)` (§1.4, §3.4.2, §5.2, §9.1's `end()`), with each site's impact chosen and justified. *(Corrected 2026-08-08: this line previously wrote the sweep target as `error/warning/info(group, text, MatchImpact)`. `info` takes **two** arguments — `design/06` owns the facade and `design/01`/`design/03` agree — and §1.4's mirror is corrected to match. `design/03` §2.7 contract **C13**; no call site is affected, because this document raises no INFO alert.)*
- **REVIEW §5 / ArchUnit rule 10** — §0.3 rule 4 named `RobotBase.isSimulation()` and §8.1.1 named `DriverStation.isFMSAttached()` directly. Only `org.pumpkinlib.core.match..` may read `DriverStation`; everything else goes through `MatchContext`/`Platform`. Both corrected.
- **`DESIGN.md` §16 item 2 (a)–(d)** — four corrections that document ordered on this one and tracked as outstanding. All four applied: `PumpkinDriveConfig.v01Competition()` (§3.7), the four-argument `driverNudge` (§9), the `TunerConstants.kFrontLeftXPos`/`kFrontLeftYPos` + four-argument `CtreSwerveBackend` fix to §10's example, and the `OdometryReport`-ships-with-the-funnel note (§13, already M9).
- The `~6 cm` trajectory-terminal-error figure in §6.7 was uncited. Under Principle 10 it is now tagged and pointed at `OdometryReport.trajectoryTest`, the routine in this document that measures it.

> **The `[SUPERSEDED-NAME]` marker — why three deleted names still appear in this document (added 2026-08-08).** `getGyroHeading`, `VisionObservation` and `PumpkinAlerts.` are all dead, and every one of them still occurs here, in a **labelled supersession or review-log site**. `[SUPERSEDED-NAME]` Deleting those occurrences would delete the record of the correction — R1, R2, §15.2 rows 9/10 and §15.3 row 14 exist precisely to say that this document *used to* declare `getGyroHeading()` and *used to* declare the `VisionObservation` record, which is the finding, not a residue of it. So [`DESIGN.md`](../DESIGN.md) §16 items **6** and **7** carve them out with the literal marker above, and this document now carries it.
>
> **Marker scope is [`design/02`](02-tuning.md) §0's, not a second definition:** the same line; or any line of the same **fenced code block**, the same **block-quote**, or the same **markdown table**, plus the block-quote immediately preceding a fence. **This document places every marker on the same line as the name it carves** — the strictest reading — except for this definition block, which relies on the block-quote clause for its own second paragraph.
>
> **Both directions run by hand, 2026-08-08, and stated as a relation rather than as literal counts** — a note that quotes its own grep changes the number it quotes, which is how a marker block becomes its own extra hit. **Forward:** every line matching one of the three carved names also matches the marker **on the same line**, with exactly one exception — the union clause below, which the block-quote clause carves. The ten correction sites are §0 rows R1/R2, §1.3's deletion paragraph, §1.4's revision-3.1 alert note, §3.2's *"deleted, not renamed for taste"* note, §3.3.2's `VisionConsumer` javadoc, §8.3's metadata note, and §15.2 rows 9/10 plus §15.3 row 14. **Reverse:** every line matching the marker has a carved name in its scope. **No unmarked literal, no orphaned marker.**
>
> **The reverse gate must be defined over the *union* of every carved name set**, never over one document's — `design/02` §0 states this and `DESIGN.md` §16 item 4(f) adopts it. A marker standing over `getGyroHeading`, `VisionObservation` or `PumpkinAlerts.` is **correct** and must not be reported as an orphan by a gate written against `design/02`'s five names.

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
- Vision pipelines, camera fusion, std-dev models (vision domain). **We implement `VisionConsumer` and expose exactly one sink**, and consume whatever the vision domain pushes into it. **Std-dev models are theirs, not ours** — `design/03` §8.4 owns the formulas and `StdDevModels.UNTRUSTED_SIGMA`; §8.3 below consumes them and no longer restates a competing model.
- **We DO own the four drive-facing interfaces** — `PoseProvider`, `AlignableDrive`, `DriveTelemetry`, `VisionConsumer` — because binding **D16** puts all four in `org.pumpkinlib.drive`. They are **declared in §3.3.2 of this document and nowhere else**; `design/03` §2.2/§13.1 and `design/04` §1.2 carry consumed-surface mirrors that say so explicitly and defer to this document on disagreement.
- **We own the gyro→field offset (§3.3.3), and nothing else in the library does.** That is the one piece of state D16a assigns by name. `design/03` §2.2 owns the *frame convention* the offset must produce; this document owns the *arithmetic* that produces it. Neither restates the other.

- Logging transport (logging domain). We consume the `PumpkinLog` facade and never call `Logger` or `SignalLogger` directly. *(Revision 3: `PumpkinLog` is a facade over one required backend, not a fan-out SPI — see §1.1.)*
- The superstructure state graph itself (mechanism domain). We define the **request-bus contract** we need from it and nothing more.

### 0.3 Non-negotiable design rules for this domain

1. **We adapt; we do not reimplement.** No PumpkinLib swerve library, no PumpkinLib trajectory generator, no PumpkinLib pathfinder, no PumpkinLib odometry math beyond what WPILib/vendors do not provide.
2. **Every abstraction has a visible escape hatch.** `drive.backend()`, `drive.raw(CommandSwerveDrivetrain.class)`, `auto.pathPlannerBuilderConfigured()`, `auto.choreoFactory()`. A team that outgrows the façade is never trapped.
3. **Zero-mystery failure.** Every guard failure raises a WPILib `Alert` with the *fix*, not just the symptom, and logs to `Pumpkin/Alerts`. We never `throw` from a constructor at boot.
4. **Sim-first.** Every command in this document runs with `Platform.isSimulation() == true` and no hardware. `PumpkinAutoTest` runs faster than real time with no HAL sim GUI. *(Revision 3.1: this rule previously named `RobotBase.isSimulation()`. Domain 06's `volatileApiIsConfined` ArchUnit rule requires `Platform.isSimulation()/isReal()`; `DESIGN.md` §16 item 3 tracks this class of call site.)*
5. **No vendor type in a public signature.** WPILib geometry/kinematics types (`Pose2d`, `ChassisSpeeds`, `SwerveModuleState`) *are* allowed and encouraged — students must learn them, and the 2027 `edu.wpi.first` → `org.wpilib` / `ChassisSpeeds` → `ChassisVelocities` change is a mechanical rename. `PathPlannerPath`, `AutoTrajectory`, `SwerveSample`, `DriveFeedforwards`, `SwerveRequest`, `SwerveDrive` (YAGSL) must **never** appear in a PumpkinLib public signature — those are the types that will break in ways a rename cannot fix. This is a deliberate departure from the "PumpkinLib-owned value types everywhere" suggestion in `deep-drivetrain.json`: hiding `ChassisSpeeds` violates "never hide WPILib" for zero 2027 benefit.
6. **Java 17 subset only.** Records, sealed interfaces, `var`, arrow switch: yes. Pattern-matching `switch`, record patterns: no (preview in 17). No `Math.clamp` (Java 21). We ship `PumpkinMath.clamp`.
7. **Conventions:** private fields `m_name`, constants `kName` or `UPPER_SNAKE_CASE`, WPILib `Units` (`Measure`) types in config APIs with raw-`double` overloads as the escape hatch (8793's team code is raw doubles today — `repo-8793.json` constraints).
8. **One clock.** No code in this domain calls `Timer.getFPGATimestamp()`, and no code in this domain constructs an `edu.wpi.first.wpilibj.Timer`. Time comes from `org.pumpkinlib.core.compat.Clock.now()` and elapsed time comes from `org.pumpkinlib.core.util.PumpkinStopwatch`, which is `Clock`-backed. This is DESIGN.md Principle 9 / D12, `02 §5.6` rule 1, and the ArchUnit hard rule 3 in `06`. A `Timer` instance is **not** an exemption: `Timer` reads the FPGA clock internally, so an AdvantageKit replay run advances it in wall-clock time while the log advances in log time, and every `atTime`/`settleTime`/`deadline` trigger in this document desynchronizes. See §6.2.1.
9. **No hardcoded match constants.** The autonomous period length is `FieldMap.autoPeriodSeconds()`, never a literal `15.0`. Binding decision D15.

---

## 1. Integration Points — what I need from other PumpkinLib domains

These are the **only** cross-domain surfaces this design touches. Each is a small interface I need someone else to own.

### 1.1 From the logging domain — `org.pumpkinlib.telemetry.PumpkinLog`

**Declared by Telemetry, not here.** `design/04` §2.2/§2.3 owns this facade and has now specified it in full. The block below is the *consumed subset* — the calls this document actually makes — reproduced for readability. **If it disagrees with `design/04`, `design/04` wins.**

```java
package org.pumpkinlib.telemetry;

/** Tiered STATIC facade over AdvantageKit's Logger. AdvantageKit is a REQUIRED dependency
 *  (maintainer decision 3), so this is NOT a fan-out SPI: the AdvantageKitSink /
 *  DataLogSink / HootSink / NtSink / NullSink implementations named in revision 2 are
 *  DELETED, along with the LogBackend SPI itself. One path, one behaviour, and
 *  deterministic replay is a GUARANTEE rather than a property of the sink a team picked --
 *  which is what LocalADStarAK (§8), the trigger engine (§6.2) and OdometryReport rely on. */
public final class PumpkinLog {

  // Tier is EXPLICIT at every call site. critical(...) survives the FMS byte governor;
  // log(...) is the STANDARD default; debug(...) is dropped when FMS-attached.
  public static void critical(String key, double v);
  public static void critical(String key, boolean v);
  public static void critical(String key, String v);
  public static <T extends WPISerializable>    void critical(String key, T v);      // Pose2d, ChassisSpeeds
  public static <T extends StructSerializable> void critical(String key, T[] v);    // Pose2d[], SwerveModuleState[]

  public static void log(String key, double v);
  public static void log(String key, boolean v);
  public static void log(String key, String v);
  public static <T extends WPISerializable>    void log(String key, T v);
  public static <T extends StructSerializable> void log(String key, T[] v);
}
```

> **Revision 3.2 correction — this document was calling an API that does not exist.** Every log site here previously read `PumpkinLog.get().put(key, value)`. Both halves of that are wrong under `design/04`'s specified surface: there is no `get()` (the facade is `final class` with static methods, not an `interface` with a process-wide instance), and **`put(...)` does not exist and will not be added** — `design/04` §2.2 states that verbatim, on the grounds that a tier-implicit call is exactly how a key ends up in the wrong tier and vanishes on FMS. Every call site in this document is now `critical(...)` or `log(...)`, with the tier taken from `design/04` §3.2's key table where that table names the key, and chosen here where it does not. The package moved with it: `org.pumpkinlib.log` → `org.pumpkinlib.telemetry`. REVIEW **B10**, `design/05` half.

**Why I need it:** every log key this domain emits — module states, the funnel's four stages, `Pumpkin/Auto/**`, `OdometryReport` output — must land in the same replayable stream as everything else, on one schema, with one budget governor.

> **Revision 3, and it is a reversal worth stating rather than editing away.** Revision 2 justified the fan-out SPI with: *"8793 logs through CTRE `SignalLogger` + NT (`repo-8793.json` constraint: **do not design the library around AdvantageKit**); 9143 and 4738 log through AdvantageKit `Logger`. This domain must work with all three and must not force an IO-layer architecture."* **Maintainer decision 3 overrides that constraint, including 8793's own.** AdvantageKit is required; 8793 migrates. What this buys the drivetrain domain specifically is that `LocalADStarAK`'s replay determinism, the trigger engine's `atTime`/`atPose`/`atEvent` timing, and `PumpkinAutoTest`'s headless reproducibility stop being conditional on a team's logging choice. What it costs is stated in [`ROADMAP.md` §4.2](../ROADMAP.md) and is not softened here: a team on DogLog or plain Epilogue cannot adopt PumpkinLib, and nothing installs before AdvantageKit publishes for the season.

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

### 1.3 From the vision domain — the push seam

**`VisionObservation` is deleted.** `[SUPERSEDED-NAME]` Binding **D17** (`DESIGN.md` §5.3) reads verbatim: *"Both records deleted. `VisionFrame` is the one vision value type. Vision pushes into the drive through `VisionConsumer.accept(Pose2d bluePose, double fpgaTimestampSeconds, Matrix<N3,N1> stdDevs)` — byte-identical to AdvantageKit's signature, so an AdvantageKit template port is import-only."* This document previously declared the record here and `design/04` §1.3 declared a second copy; **both are gone**, and neither document re-declares it. Applied 2026-08-08 by the contract-reconciliation pass (REVIEW **B4** / this document's R2).

The sink is **`VisionConsumer`, declared in §3.3.2 of this document** (D16 puts it in `org.pumpkinlib.drive`) and implemented by `PumpkinDrive` (§3.3). `design/03` §2.2 mirrors it as a consumed surface and says so.

`tagCount` and `avgTagDistanceMeters` do **not** survive into D17's three-argument signature, and that is correct rather than a loss — **§8.3 is where that is answered**, including the documented `OdometryTrust.withFrameMetadata(...)` side channel for teams that genuinely need the raw counts drive-side.

The vision domain **pushes** into the drive's sink. It must never pull a recycled buffer (`repo-8793.json` pain point: `getEstimatedPoses()` returns a shared mutable list). This domain owns the *timebase translation* for CTRE backends (`Utils.fpgaToCurrentTime`) — the vision domain always speaks FPGA time and never needs to know the backend (`repo-8793.json` and `repo-9143.json` both list this as a hard constraint).

I also need, for skid-aware trust arbitration:

```java
/** Vision domain registers this so we can inflate/deflate trust from skid state. */
public interface OdometryTrustListener { void onSkid(SkidReport report); }
```

### 1.4 From the config/tuning domain

```java
org.pumpkinlib.config.Tunable        // TunableDouble/TunableBoolean, NT-backed, no-op when TUNING_MODE==false and FMS attached
org.pumpkinlib.config.PumpkinConfigStore  // read/write deploy/pumpkin/*.json, with a /home/lvuser write path at runtime
```

**Alerts come from the platform domain, not from config.** Binding **D10** (`DESIGN.md` §5.2) puts them at `org.pumpkinlib.core.alert`:

```java
package org.pumpkinlib.core.alert;

public final class Alerts {
  public static PumpkinAlert error  (String group, String text, MatchImpact impact);
  public static PumpkinAlert warning(String group, String text, MatchImpact impact);
  public static PumpkinAlert info   (String group, String text);   // INFO is PIT_ONLY by definition
}
public enum MatchImpact { BLOCKS_MATCH, PIT_ONLY }
```

> **`info` takes TWO arguments, and revision 3.1 of this document was the outlier that said otherwise — corrected 2026-08-08 (`design/03` §2.7 contract **C13**).** This mirror previously declared `info(String group, String text, MatchImpact impact)`, i.e. three arguments. `design/06` **owns** the alert facade under **D10**, and `design/06`, `design/01` and `design/03` §2.4 all declare `public static PumpkinAlert info(String group, String text);` with the same comment: *INFO is PIT_ONLY by definition — an informational alert cannot stop a match.* Three documents to one, and the one is a labelled mirror rather than the declaration site, so **this document conforms.** A `MatchImpact` parameter on `info` is not a question the author can meaningfully answer — the only answer the type permits that is consistent with the severity is `PIT_ONLY` — and a parameter with one legal value is a parameter that teaches a student to type a word instead of make a decision, which is the exact failure D10 exists to prevent on `error`/`warning`.
>
> **This does not weaken D10.** D10's requirement is that `MatchImpact` be *"required at every call site with no default and no single-argument overload"*, and it binds `error` and `warning`, which are the two that can mean *do not take the field*. Both keep the third argument here and everywhere. **`DESIGN.md` §16 item 7 still owes the one sentence that records which way this was adjudicated** — its Required text spells the sweep target as three-argument `info` — and that sentence is `DESIGN.md`'s to write, not this document's; it is named here so the residue is not lost.
>
> **Adjudicated in the master, not just settled here: `DESIGN.md` §5.2 **D32** (revision 8, 2026-08-08) rules for the two-argument form** and narrows D10 explicitly rather than weakening it — *"D10's 'required at every call site, no default, no single-argument overload' **binds `error` and `warning`**… `info` is exempt because its impact is constant, and the exemption is `info`'s alone and does not generalise."* D32 also forbids `Alerts` adding a two-argument `error` or `warning`, and states that a future `Severity` whose impact is *not* constant takes the three-argument form. **`DESIGN.md` §16 item 7's owed sentence is discharged by D32; this document's job was to stop being the outlier, and it has.**
>
> **Zero call sites were affected, verified with a call-shaped grep rather than a bare-string one:** `grep -rn 'Alerts\.info([^)]*)[[:space:]]*;' design/ DESIGN.md README.md` returns **zero** (2026-08-08). *(The bare `Alerts\.info(` grep returns 4 and rising — D10's cell, D32's cell, `DESIGN.md` §16 item 7 and `design/04` §2's ownership sentence — all **prose about the signature**. A gate that counts documents correctly describing a method is not a gate, which is the lesson `DESIGN.md` §16 draws four times over.)* This document raises no INFO alert; every entry in the impact table below is an `error` or a `warning`, and every one carries its `MatchImpact`.

> **Revision 3.1, and it is a correction rather than a rename.** `[SUPERSEDED-NAME]` This document previously used a two-argument `org.pumpkinlib.config.PumpkinAlerts.error/warn(group, text)` facade at every guard. D10 states that **`MatchImpact` is required at every call site with no default and no single-argument overload** — the whole point being that the author of a guard must answer "does this stop us taking the field?" while writing it, rather than leaving it to a triage conversation on Saturday morning. A two-argument facade dodges exactly that question, in five domains at once (REVIEW M5). Every alert in this document now carries an impact and the choice is stated where it is made:

| Call site | Impact | Why |
|---|---|---|
| `drive.selfcheck.twoSubsystems` (§3.8 check 8) | `BLOCKS_MATCH` | Default commands and requirement cancellation are unpredictable; the robot may not respond to the driver. |
| `drive.selfcheck.gyroOffsetUnseeded` (§3.8 check 9) | `BLOCKS_MATCH` | MegaTag2 is being fed a power-on-frame yaw. The pose is confidently wrong, and autos run off it. |
| `auto.namedCommand.<name>` (§5.2) | `BLOCKS_MATCH` | The auto will cancel itself partway through. Detected at `robotInit`, so it is fixable in the pit. |
| `auto.namedCommand.goal.<name>` (§5.2) | `BLOCKS_MATCH` | Same failure, one indirection away. |
| `drive.ak.noWheelForces` (§3.4.2) | `PIT_ONLY` | Path accuracy degrades measurably; the robot still drives and still scores. |
| `align.timeout` (§9.1 `end()`) | `PIT_ONLY` | One alignment did not converge. The match continues; the diagnosis belongs in the pit. |
| `drive.traction.noRobotConfig` (§3.7) | `PIT_ONLY` | Traction control silently unavailable — worth a loud pit finding, not a scratch. |
| `char.refused.<routine>` (§8.1.1) | `PIT_ONLY` | Characterization is a pit activity by construction; it cannot run with FMS attached at all. |

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
  PumpkinDrive              (final class — THE funnel; implements all four interfaces below)
  PoseProvider              (interface — D16; declared in §3.3.2, consumed by design/03)
  AlignableDrive            (interface — D16; declared in §3.3.2, consumed by design/03 §13.1)
  VisionConsumer            (interface — D16/D17; declared in §3.3.2, the one vision→drive sink)
  DriveTelemetry            (interface — D16; declared in §3.3.2, consumed by design/04 §1.2)
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

  /**
   * The UNMODIFIED IMU yaw. CCW-positive, referenced to wherever the gyro happened to be zeroed
   * at power-on — the underside of a cart, a pit table, the wrong alliance wall. This is
   * explicitly NOT the blue-origin field frame, and this is the ONLY place in PumpkinLib where
   * the raw value is legal. It must never be written to robot_orientation_set. Everything else,
   * including every MegaTag2 / PNP-trig / constrained-solvepnp consumer, calls
   * PumpkinDrive.getGyroFieldHeading() — see §3.3.3, which owns the offset that separates them.
   */
  Rotation2d getRawGyro();

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

> **Revision 3.2 — `getGyroHeading()` is deleted, not renamed for taste.** `[SUPERSEDED-NAME]` This interface previously declared `Rotation2d getGyroHeading(); // raw gyro, CCW+, blue-origin frame`. `design/03` §2.2 states verbatim that the line *"is deleted. It is a contradiction inside a single line: a raw gyro is by definition not in the blue-origin frame."* It is now `getRawGyro()`, and the blue-origin field heading is a **different method on a different type** — `PumpkinDrive.getGyroFieldHeading()`, §3.3.3 — precisely so that no backend author can satisfy the type checker by returning the wrong quantity. Binding **D16**/**D16a**; REVIEW **B4**.

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
      PumpkinLog.critical("Pumpkin/Drive/RequirementOwner", "synthetic:" + m_backend.name());
    }
    return m_synthetic;
  }
}
```

When the backend *does* supply one, we log its identity instead:

```java
m_backend.existingSubsystem().ifPresent(s ->
    PumpkinLog.critical("Pumpkin/Drive/RequirementOwner", "backend:" + s.getName()));
```

`Pumpkin/Drive/RequirementOwner` is a **CRITICAL**-level string topic, so it is present in the match log even at the most aggressive log-filter setting. A double-registration — the classic "I made `Drive extends SubsystemBase` *and* let PumpkinLib synthesize one" mistake — shows up as a `DriveSelfCheck` failure at boot (§3.8 check 8) and as a visible owner name in post-match triage, instead of as a default command that mysteriously never runs.

#### 3.3.2 The four drive-facing interfaces — **declared here, and only here**

Binding **D16** (`DESIGN.md` §5.3): *"Drive owns all four, in `org.pumpkinlib.drive`. `PumpkinDrive` implements `PoseProvider`, `AlignableDrive` and `DriveTelemetry`, and accepts a `VisionConsumer`."* This subsection is that declaration. `design/03` §2.2 and §13.1 and `design/04` §1.2 carry consumed-surface mirrors, each labelled as a mirror and each deferring to this document; **there is no second declaration anywhere in the design.**

```java
package org.pumpkinlib.drive;

/** Everything the vision domain needs to know about robot state. Implemented by PumpkinDrive;
 *  hand-writable in about six lines by a team that does not use PumpkinLib's drivetrain. */
public interface PoseProvider {
  Pose2d getPose();                                    // blue-origin, ALWAYS
  Optional<Pose2d> sampleAt(double timestampSeconds);  // delegates to PoseEstimator.sampleAt

  /**
   * Gyro yaw PLUS the gyro->field offset. Blue-origin, CCW-positive, 0 deg faces the RED
   * alliance wall — the frame every MegaTag2 / PNP-trig / constrained-solvepnp consumer needs.
   * `design/03` §2.2 owns that convention and states it in full; it is not restated here.
   *
   * <p>NEVER influenced by a vision measurement. The offset, its exactly-two writers, and the
   * arithmetic that produces this value are specified in §3.3.3 of this document, which is the
   * single owner of all three.
   */
  Rotation2d getGyroFieldHeading();

  /** False until the offset has been written at least once. Drives DriveSelfCheck check 9
   *  (§3.8) and VisionDiagnostics.GYRO_OFFSET_UNSEEDED (design/03 §11.3). */
  boolean gyroFieldOffsetSeeded();

  double getGyroRateRadPerSec();
  ChassisSpeeds getFieldRelativeSpeeds();
}

/** The only thing the vision alignment commands need from a drivetrain (design/03 §13.1, §13.5). */
public interface AlignableDrive {
  void driveFieldRelative(ChassisSpeeds speeds);

  /**
   * Robot-relative drive: +x forward, +y left, CCW-positive omega.
   *
   * <p>REQUIRED, not defaulted. `alignToTag` closes the loop on a camera-to-tag transform, which
   * is a robot-frame quantity — routing it through a field-relative call would reintroduce the
   * fused pose's heading error into a controller whose entire purpose is to not depend on the
   * fused pose. A default implementation that rotated by getPose().getRotation() would silently
   * undo the feature, so there isn't one.
   */
  void driveRobotRelative(ChassisSpeeds speeds);

  /** design/03 §2.7 contract C5. Both terminal controllers seed their profiled controller from
   *  the measured closing velocity in initialize(); without this, an alignAtEnd handoff at
   *  3 m/s commands a reversal on the first loop. */
  ChassisSpeeds getRobotRelativeSpeeds();

  void stop();
  Subsystem asSubsystem();            // for addRequirements; may return a no-op Subsystem
  double maxLinearSpeedMps();
  double maxAngularSpeedRadPerSec();
}

/** THE vision->drive sink. Exactly AdvantageKit's VisionConsumer signature, so porting an
 *  AdvantageKit template is import-only. Binding D17; replaces VisionObservation [SUPERSEDED-NAME],
 *  the deleted record. The dead name is retained on purpose: an implementer arriving from
 *  revision 3.1 searches for it, and this is where that search should land. */
@FunctionalInterface
public interface VisionConsumer {
  void accept(Pose2d visionRobotPoseMeters, double timestampSeconds, Matrix<N3, N1> stdDevs);
}

/** The read-only surface design/04 §1.2 consumes to publish the Pumpkin/Drive/ key block. */
public interface DriveTelemetry {
  Pose2d pose();                          // fused estimate
  Pose2d odometryOnlyPose();              // no vision — used for the divergence metric
  ChassisSpeeds measuredSpeeds();         // robot relative
  ChassisSpeeds setpointSpeeds();         // robot relative
  SwerveModuleState[] measuredStates();
  SwerveModuleState[] setpointStates();
  SwerveModulePosition[] modulePositions();
  boolean gyroConnected();
  /** Telemetry mirror of DriveBackend.getRawGyro(), logged as Pumpkin/Drive/Gyro/RawYawRad
   *  next to Gyro/YawRad (the field heading) — design/04 §1.2. The two traces being identical
   *  is a one-glance diagnosis of an unseeded offset. */
  Rotation2d rawGyroYaw();
}
```

#### 3.3.3 The gyro→field offset — **this document owns it, and nothing else does**

This subsection is the **single ownership site** for the gyro→field offset in all of PumpkinLib. Binding **D16a**. Every other document references it; none restates the arithmetic.

**The problem it exists to solve.** MegaTag2 (and PNP-distance-trig, and constrained-solvepnp) require the robot's yaw *in the blue-origin field frame* — CCW-positive, 0° facing the RED alliance wall. `DriveBackend.getRawGyro()` is contractually the **raw** IMU yaw, referenced to wherever the gyro was zeroed at power-on. Feed the raw value to MegaTag2 and it returns a **confidently wrong** translation that no residual, no ambiguity metric and no std-dev model can detect, because MegaTag2 treats the yaw as *known*. The two frames differ by a constant, and that constant needs an owner. It is here.

```java
// PumpkinDrive — the offset, its two writers, and the conversion. Nothing else touches it.

private Rotation2d m_gyroFieldOffset       = Rotation2d.kZero;
private boolean    m_gyroFieldOffsetSeeded = false;

/**
 * WRITER 1 OF 2, and the only public path.
 * The offset is defined so that raw + offset == the blue-origin heading we were just told we
 * are at, which is the whole conversion:
 *     offset            := bluePose.getRotation() - getRawGyro()
 *     getGyroFieldHeading() == getRawGyro() + offset
 */
public void resetPose(Pose2d bluePose) {
  m_gyroFieldOffset       = bluePose.getRotation().minus(m_backend.getRawGyro());
  m_gyroFieldOffsetSeeded = true;
  m_backend.resetPose(bluePose);
}

/**
 * Gyro yaw expressed in the BLUE-ORIGIN field frame. This is the value written to
 * robot_orientation_set, and the ONLY heading any vision consumer may ever see.
 * The frame convention it produces is design/03 §2.2's and is not restated here.
 */
@Override public Rotation2d getGyroFieldHeading() {
  return m_backend.getRawGyro().plus(m_gyroFieldOffset);
}

@Override public boolean gyroFieldOffsetSeeded() { return m_gyroFieldOffsetSeeded; }
```

**WRITER 2 OF 2 is the disabled multi-tag seed** (`design/03` §9.7) — and it is not a third writer, it is writer 1 invoked from a gated path. It is gated on `!MatchContext.isEnabled()`, requires `tagCount >= 2` and agreement with two prior frames, and **refuses gyro-fused sources**: seeding a gyro offset from a gyro-fused solve is circular, a closed loop with gain one that will agree with itself forever including when it is 180° wrong. Only `MEGATAG_1`, `MULTI_TAG_COPROC` and `SIM` may seed. `AlliancePerspective.resetFieldRotation` (§4) also reaches the offset only through `resetPose`.

**The prohibition, which is the part with teeth.** `accept(Pose2d, double, Matrix<N3,N1>)` — the `VisionConsumer` sink — **never** writes `m_gyroFieldOffset`. Not on any code path, not behind any flag, and **there is no opt-out, because an opt-out here *is* the MegaTag2 feedback loop.** This is stated in the sink's javadoc as well as here, because it is the one invariant a well-meaning patch would remove first.

**`getHeading()` and `getGyroFieldHeading()` are two different quantities and both must exist.** `getHeading()` is the pose estimator's *fused* blue-origin heading; `getGyroFieldHeading()` is gyro-only and never vision-fed. Do not collapse them: `design/03` §7.3 logs `Pumpkin/Vision/GyroFieldHeadingDeg` beside `Pumpkin/Vision/FusedHeadingDeg` precisely so that "these two traces are identical" is a one-glance diagnosis of a miswiring — which requires them to be two traces.

**Detection when nobody seeds it.** An offset that is still identity is caught twice, deliberately: `DriveSelfCheck` check 9 here (§3.8) and `VisionDiagnostics.GYRO_OFFSET_UNSEEDED` in `design/03` §11.3. Two detectors, one fact — a team may have configured only one of the two subsystems.

```java
package org.pumpkinlib.drive;

public final class PumpkinDrive
    implements PoseProvider, AlignableDrive, DriveTelemetry, VisionConsumer {   // D16; §3.3.2

  // ---------- construction -------------------------------------------------
  public static PumpkinDrive of(DriveBackend backend) { return of(backend, PumpkinDriveConfig.v01Competition()); }
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
  public Rotation2d getHeading();                 // blue-origin FUSED field heading (estimator output)

  /** Blue-origin GYRO-ONLY field heading = getRawGyro() + the offset. Never vision-fed.
   *  A different quantity from getHeading(); both exist on purpose — §3.3.3. */
  @Override public Rotation2d getGyroFieldHeading();
  @Override public boolean gyroFieldOffsetSeeded();

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
  /** WRITER 1 OF 2 of the gyro→field offset — §3.3.3, which owns it. */
  public void resetPose(Pose2d bluePose);

  /**
   * THE vision sink (VisionConsumer, D17). `timestampSeconds` is ALWAYS FPGA time; the
   * CTRE Phoenix-timebase conversion happens in the backend (§3.4.1) and is unchanged.
   *
   * <p>This method NEVER writes m_gyroFieldOffset. Not on any code path, not behind any
   * flag, and there is no opt-out — an opt-out here IS the MegaTag2 feedback loop (§3.3.3).
   */
  @Override public void accept(Pose2d bluePose, double fpgaTimestampSeconds,
                               Matrix<N3, N1> stdDevs);

  // ---------- lifecycle ----------------------------------------------------
  /** Call from robotPeriodic. Runs skid detection, alliance perspective, logging, alerts. */
  public void periodic();

  // ---------- characterization hooks (used by PumpkinCharacterization) ------
  public void runCharacterizationVolts(double volts);
  public double[] getWheelRadiusCharacterizationPositionsRad();
  public double getFFCharacterizationVelocityRadPerSec();

  // ---------- inherited from §3.3.2, listed there rather than repeated here ------------
  // PoseProvider   : sampleAt(double), getGyroRateRadPerSec()
  // AlignableDrive : asSubsystem() -> requirement(), maxLinearSpeedMps() and
  //                  maxAngularSpeedRadPerSec() -> limits(), stop(), drive*Relative(...)
  // DriveTelemetry : pose(), odometryOnlyPose(), measuredSpeeds(), setpointSpeeds(),
  //                  measuredStates(), setpointStates(), modulePositions(), gyroConnected(),
  //                  rawGyroYaw() -> m_backend.getRawGyro()
  // All are obligations of the four interfaces; none is a new concept, and none may be
  // satisfied by returning a different quantity than §3.3.2's javadoc names.

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

  PumpkinLog.critical("Pumpkin/Drive/Commanded", speeds);            // == ChassisSpeeds/Setpoint, 04 §3.2
  PumpkinLog.log     ("Pumpkin/Drive/CommandedRaw", desired);        // pre-traction, diagnostic
  PumpkinLog.log     ("Pumpkin/Drive/FeedforwardPresent", ff.isPresent());
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
      Alerts.warning("drive.ak.noWheelForces",
          "Path wheel-force feedforwards are being dropped. Add a runVelocity(ChassisSpeeds, "
        + "double[] fx, double[] fy) overload to Drive.java and pass it to "
        + "AdvantageKitSwerveBackend.Adapter to recover path accuracy under acceleration.",
          MatchImpact.PIT_ONLY);          // D10: impact is required, not defaulted (§1.4)
      m_warnedFf = true;
    }
  }
}
```

`Adapter.withFeedforwards(TriConsumer<ChassisSpeeds, double[], double[]>)` upgrades it once the team patches `Drive.java`.

> **Revision 3.1, small but it is the same rule §8 applies to itself.** The alert text used to promise *"recover ~10-15% path accuracy under acceleration."* That number had no measurement behind it. Principle 10 says every performance claim is measured, and this document already deleted an uncited 50 Hz-vs-250 Hz statistic for exactly this reason (§8, revision note). The alert now says *"recover path accuracy under acceleration,"* which is true and is all we can currently support. `OdometryReport.trajectoryTest(drive, traj)` (§8.5) is the routine that would produce the real figure, run once with the patch and once without.

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
    // Vision heading is NEVER trusted here. The sentinel is the library-wide named one from
    // design/03 §8.0 Rule 1 — StdDevModels.UNTRUSTED_SIGMA == 1.0e6 — not a raw 9_999_999.
    // Rule 1's reasoning: a finite sentinel survives arithmetic (scaling, clamping, logging)
    // where POSITIVE_INFINITY produces NaN, and ONE named constant is what stops the two halves
    // of the same fusion pipeline drifting to two different magic numbers. See §8.3.
    Matrix<N3, N1> visionStdDevs) {}   // default VecBuilder.fill(0.9, 0.9, StdDevModels.UNTRUSTED_SIGMA)

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

Mecanum: `MecanumBackend` would have the same shape (`MecanumDriveKinematics` + `MecanumDrivePoseEstimator`). **Revision 3: it is in no milestone M1–M24 and is therefore outside v0.1** — the `DriveBackend` SPI it would plug into is documented and stable, so a team or a later contributor can write it, but PumpkinLib does not ship one. Designed for, not designed out, and not scheduled (§13, open question 9).

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

  /**
   * The competition profile as it exists AT M9, when the funnel and the CTRE backend ship and
   * `TractionLayer`/`SkidDetector` do not yet (they are M15). Identical to competition() except
   * that traction and skid detection are off, because the code behind them is not written.
   *
   * Every example in this document and in DESIGN.md §10A/§10B uses THIS factory until M15.
   * It is scheduled for deletion at M15, at which point the examples move back to competition().
   * (DESIGN.md §16 item 2(a) ordered this; revision 3.1 applies it.)
   */
  public static PumpkinDriveConfig v01Competition() {
    return new PumpkinDriveConfig(OdometryMode.NATIVE_250HZ, TractionMode.NONE,
                                  0.02, false, true, Units.rotationsToRadians(10.0));
  }

  /** The full competition profile. Requires M15. See the persistent Alert note below. */
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

`TractionMode.SETPOINT_GENERATOR` requires a PathPlanner `RobotConfig`. If `RobotConfig.fromGUISettings()` fails or `hasValidConfig()` is false, we **downgrade to `NONE`** and raise `Alerts.warning("drive.traction.noRobotConfig", …, MatchImpact.PIT_ONLY)`:

> `Traction control disabled: deploy/pathplanner/settings.json is missing or invalid. Open the PathPlanner GUI once to generate it, then run PumpkinCharacterization.all(drive) to fill in mass, MOI and wheel COF.`

**Before M15, `competition()` does not silently downgrade — it complains.** `TractionLayer` and `SkidDetector` are built at M15; the funnel is M9. A `PumpkinDriveConfig` that asks for a capability the installed library has not yet implemented raises a **persistent** `Alerts.warning("drive.config.notYetBuilt", …, MatchImpact.PIT_ONLY)` naming `TractionLayer`/`SkidDetector`, the milestone, and `v01Competition()` as the profile that says what it means. Silent degradation is the failure mode this document's entire `DriveSelfCheck` section exists to prevent, and it would be perverse for the config factory to be the one place that does it. *(DESIGN.md §16 item 2(a).)*

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

9. **The gyro→field offset has been seeded, if anything gyro-fused is configured.** If any configured camera reports a gyro-fused `PoseSource` (`MEGATAG_2`, `PNP_DISTANCE_TRIG`, `CONSTRAINED_SOLVEPNP`) and `drive.gyroFieldOffsetSeeded() == false`, raise:

   ```java
   Alerts.error("drive.selfcheck.gyroOffsetUnseeded",
       "Gyro field offset has never been seeded (still identity) but MegaTag2/PNP-trig "
     + "cameras are configured. robot_orientation_set is being written in the POWER-ON "
     + "gyro frame, not the field frame, so every MegaTag2 pose is confidently wrong. "
     + "Call drive.resetPose(startingBluePose) or enable the disabled MegaTag1 seed.",
       MatchImpact.BLOCKS_MATCH)          // D10; the pose is wrong and the autos run off it
       .set(true);
   ```

   `PumpkinVision` mirrors the same condition as `VisionDiagnostics.GYRO_OFFSET_UNSEEDED` (`design/03` §11.3) so it is visible from either domain. Two detectors, one fact — that is deliberate, because a team may have configured only one of the two subsystems. The offset itself is owned by §3.3.3; this check only reads `gyroFieldOffsetSeeded()`.

> **The number is 9, and the off-by-one is worth recording rather than leaving for the implementer to trip over.** `design/03` §2.2a(3) calls this *"an eighth check,"* because it was written against a version of this list that stopped at seven. This list already had eight — check 8, subsystem ownership, was added by the previous adversarial review — so the new one is **check 9**, and `design/03` §2.2a(3) now says so too. The alert **text** is `design/03`'s, with the `MatchImpact` argument added per D10 and this document's alert-key convention applied; it is deliberately not paraphrased, because the wording *is* the diagnosis. Applied 2026-08-08 by the contract-reconciliation pass (`design/03` §2.7 contract **C3**; REVIEW **B4**).

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

  /**
   * Registers the tag-relative alignment factory that makes AutoStep.alignToTagAtEnd(...) real
   * (§6.4). Optional: without it, alignToTagAtEnd falls back to alignAtEnd with the fused-pose
   * tolerance and logs which path it took, once, per step.
   *
   * This is a FACTORY rather than a direct dependency on purpose. `org.pumpkinlib.auto` imports
   * nothing from `org.pumpkinlib.vision` — a team that runs autos with no vision at all should
   * not have PumpkinVision on their construction path, and the "vision is absent" branch should
   * be structural rather than a null check on a vision object. One line wires it:
   *
   *   auto.withVision((cam, ids, goal) ->
   *       VisionCommands.alignToTag(drive, vision, cam, ids, goal, AlignGains.tagRelative()));
   *
   * All three parameters are WPILib or primitive types, so this seam carries no vendor type and
   * no cross-domain type (§0.3 rule 5).
   */
  public PumpkinAuto withVision(VisionAlignFactory factory);
  public boolean visionAlignAvailable();

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

```java
package org.pumpkinlib.auto;

/**
 * The auto domain's one-method view of tag-relative alignment. Implemented by the team in one
 * lambda over `VisionCommands.alignToTag` (design/03 §13.3); implemented by PumpkinAutoTest with
 * a deterministic fake so an auto that ends in a vision align is still headlessly testable.
 *
 * @param cameraIndex      which camera owns this alignment
 * @param acceptableTagIds tag ids allowed to drive it; the camera's tag filter is set to these on
 *                         init and RESTORED on end, so a defender's bumper tag cannot steal the
 *                         solve. Vision owns that behaviour; auto only supplies the ids.
 * @param tagRelativeGoal  TAG -> desired ROBOT ORIGIN. Written once, correct at every tag on the
 *                         field, and — this is the reason the step exists — expressed in a frame
 *                         where odometry drift, gyro-offset error, AprilTag-layout error and
 *                         alliance-flip mistakes all cancel out of the error term.
 */
@FunctionalInterface
public interface VisionAlignFactory {
  Command alignToTag(int cameraIndex, int[] acceptableTagIds, Transform3d tagRelativeGoal);
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
      poses -> PumpkinLog.log("Pumpkin/Auto/ActivePath", poses.toArray(new Pose2d[0])));
  PathPlannerLogging.setLogTargetPoseCallback(
      p -> PumpkinLog.log("Pumpkin/Auto/TargetPose", p));
  PathPlannerLogging.setLogCurrentPoseCallback(
      p -> PumpkinLog.log("Pumpkin/Auto/CurrentPose", p));
}
```

**`wrapAction` — the NamedCommands requirement-collision fix.** This is the single most common "my auto stops halfway" bug in FRC.

```java
private Command wrapAction(String name, Command cmd) {
  Set<Subsystem> collisions = new HashSet<>(cmd.getRequirements());
  collisions.retainAll(Set.of(m_drive.requirement()));
  if (!collisions.isEmpty()) {
    Alerts.error("auto.namedCommand." + name,
        "NamedCommand \"" + name + "\" requires the drive subsystem. When PathPlanner triggers it "
      + "mid-path it will CANCEL the whole auto. Remove the drive requirement, or express this as "
      + "an auto step instead of an event marker.",
        MatchImpact.BLOCKS_MATCH);     // D10: the auto stops halfway. That is a lost match.
  }
  if (m_goalBus != null && cmd.getRequirements().stream().anyMatch(m_goalBus::owns)) {
    Alerts.error("auto.namedCommand.goal." + name,
        "NamedCommand \"" + name + "\" requires a subsystem owned by the GoalBus. Use "
      + "goals.requestAsync(...) or goals.request(...) instead — those never take requirements.",
        MatchImpact.BLOCKS_MATCH);
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
      (traj, starting) -> PumpkinLog.log(
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
          PumpkinLog.critical("Pumpkin/Auto/Traj/" + m_handle.name() + "/Completed", !interrupted);
          PumpkinLog.critical("Pumpkin/Auto/Traj/" + m_handle.name() + "/Elapsed", m_timer.get());
          PumpkinLog.log     ("Pumpkin/Auto/Traj/" + m_handle.name() + "/Planned",
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
   * FUSED-POSE fine align at the trajectory's scoring waypoint. Appends a PumpkinDriveToPose
   * AFTER follow() completes and BEFORE then(), so the score command runs from a converged pose
   * rather than from wherever the trajectory happened to leave the robot.
   *
   * <p><b>Read the tolerance note before you tighten this.</b> This controller closes the loop on
   * the pose estimator's output, so it is bounded below by the fused-pose error at scoring range,
   * not by the controller's gains. `design/03` §13.2 owns that number and this document does not
   * get to disagree with it: the honest floor is about <b>5 cm / 2 deg</b>, and the builder default
   * in §9 is exactly that. Asking for 2 cm here does not produce 2 cm — it produces a timeout
   * alert on every alignment, which is worse than the 5 cm it replaced because it also burns the
   * step's deadline. <b>If you need 2 cm, use {@link #alignToTagAtEnd}</b>, which closes the loop
   * on the tag itself. See §6.7 for the full arithmetic.
   *
   * <p>alignAtEnd remains the right call when there is no camera on the scoring face, when the
   * target is not a tag at all (a park pose, a cage, a floor position), or when the approach is
   * long enough that PathPlanner/Choreo terminal error dominates the sensor.
   *
   * The step's deadline still governs: if the align has not converged when the deadline expires,
   * the step fails and successWhen/orElse/orSkipTo handle it exactly as they would a missed piece.
   */
  public AutoStep alignAtEnd(Supplier<Pose2d> blueTarget, Distance tol, Angle angTol,
                             double timeoutSeconds);
  /** Overload: align to the followed trajectory's own final pose, at the fused-pose tolerance. */
  public AutoStep alignAtEnd(Distance tol, Angle angTol, double timeoutSeconds);

  /**
   * TAG-RELATIVE fine align at the scoring waypoint. <b>This is the highest-leverage single line
   * in the auto DSL</b>, and revision 3.1 is the revision in which that sentence became true —
   * before it, this step did not exist and the sentence sat on {@link #alignAtEnd}, which cannot
   * deliver what it promised.
   *
   * <p>Appends `VisionCommands.alignToTag` (design/03 §13.3) after follow() and before then(), in
   * exactly the position alignAtEnd occupies and with the same drive requirement, so it sequences
   * identically and there is no requirement fight. Elite autos are not faster trajectories; they
   * are trajectories that hand off to a closed-loop align, and the loop that closes at 2 cm is the
   * one whose feedback signal is the tag.
   *
   * <p><b>Why this reaches 2 cm and alignAtEnd does not.</b> The error term here is
   * `robotToCamera ∘ cameraToTag ∘ tagRelativeGoal` — every quantity in it is measured in the
   * tag's own frame in the same instant. Odometry drift, gyro-offset error, AprilTag-layout error
   * and alliance-flip mistakes are all common to the measurement and the goal, so they cancel.
   * The fused-pose path shares none of that cancellation. design/03 §13.3 states it as an
   * invariant: 2 cm "is achievable here and nowhere else."
   *
   * <p><b>Fallback is structural, not optional.</b> If `PumpkinAuto.withVision(...)` was never
   * called, this step compiles to `alignAtEnd(trajectoryFinalPose, 0.05 m, 2.0 deg, timeout)` and
   * logs `Pumpkin/Auto/Steps/&lt;i&gt;/AlignPath = "fused-fallback"` once. The auto still runs and
   * still scores; it scores at the tolerance the sensor supports. `build()` does NOT reject the
   * missing factory — an auto that refuses to build because a camera is unplugged is a worse
   * failure than an auto that aligns 3 cm loose.
   *
   * @param cameraIndex      which camera owns this alignment; must report a robotToCamera transform
   * @param acceptableTagIds tag ids allowed to drive it. Pass the whole scoring face, not one tag:
   *                         the vision command latches the highest-area acceptable tag on init and
   *                         does not re-pick, so a list is a robustness win with no lunge risk.
   * @param tagRelativeGoal  TAG -> desired ROBOT ORIGIN. "20 cm out from the face, squared up",
   *                         written once, correct at every tag on the field.
   * @param timeoutSeconds   as alignAtEnd. The step's deadline still governs on top of this.
   */
  public AutoStep alignToTagAtEnd(int cameraIndex, int[] acceptableTagIds,
                                  Transform3d tagRelativeGoal, double timeoutSeconds);

  /** Runs after the drive motion completes (and after alignAtEnd/alignToTagAtEnd, if set); the
   *  step does not end until this does. */
  public AutoStep then(Command command);

  // ---- introspection used by PumpkinAutoRoutine.build() validation ---------
  // Every accessor build() calls is declared here. A validation block that calls methods the
  // type does not expose is how a "validated" DSL ships with the validation commented out.
  public boolean hasBefore();
  public @Nullable PumpkinTrajectory trajectory();
  public String name();
  public int index();                    // position in the routine; the log-key prefix
  public boolean hasAlignAtEnd();
  public @Nullable Supplier<Pose2d> alignTarget();
  public Distance alignTolerance();
  public Angle alignAngularTolerance();
  public double alignTimeoutSeconds();
  // alignToTagAtEnd's introspection. Same rule as above: build() calls only accessors declared
  // here, so a validation block can never reference something the type does not expose.
  public boolean hasAlignToTagAtEnd();
  public int alignCameraIndex();
  public int[] alignAcceptableTagIds();
  public @Nullable Transform3d alignTagRelativeGoal();
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

    // 2b. Exactly one terminal align per step. Two would fight for the same drive requirement in
    //     sequence and silently double the step's time budget.
    if (step.hasAlignAtEnd() && step.hasAlignToTagAtEnd()) {
      throw new IllegalStateException("Step '" + step.name()
          + "': alignAtEnd(...) and alignToTagAtEnd(...) are both set. Pick one. alignToTagAtEnd "
          + "is the tighter of the two (2 cm vs 5 cm) and already falls back to alignAtEnd when "
          + "PumpkinAuto.withVision(...) was not called, so setting both is never what you want.");
    }

    // 2c. alignToTagAtEnd's fallback needs a pose to fall back TO. With no trajectory and no
    //     explicit target there is nothing to hand PumpkinDriveToPose if vision is absent, and
    //     "silently does nothing on the practice field" is exactly the failure class this
    //     validation block exists for.
    if (step.hasAlignToTagAtEnd() && step.trajectory() == null && step.alignTarget() == null) {
      throw new IllegalStateException("Step '" + step.name()
          + "': alignToTagAtEnd(...) requires follow(traj) or an explicit alignAtEnd target, "
          + "because without vision it falls back to a fused-pose align and needs a pose to aim "
          + "at. Add follow(traj), or drive there with driveTo(target) and align to that target.");
    }

    // 2d. An empty tag-id list accepts every tag on the field, including a defender's bumper tag.
    if (step.hasAlignToTagAtEnd()
        && (step.alignAcceptableTagIds() == null || step.alignAcceptableTagIds().length == 0)) {
      throw new IllegalStateException("Step '" + step.name()
          + "': alignToTagAtEnd(...) was given an empty acceptableTagIds array. An empty filter "
          + "accepts every tag the camera can see, including one on a defender's bumper. Pass the "
          + "tag ids of the scoring face you are aligning to.");
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
Pumpkin/Auto/Steps/<i>/AlignPath     String   CRITICAL: "tag-relative" | "fused" | "fused-fallback"
Pumpkin/Auto/Steps/<i>/AlignError    double   (meters, at the moment the align ended)
Pumpkin/Auto/StepIndex               double
Pumpkin/Auto/StepName                String
Pumpkin/Auto/Name                    String
```

### 6.5 How a step compiles to a `Command`

```java
// The routine's copy of the factory registered by PumpkinAuto.withVision(...) (§5.1).
// Null when the team never called it, which is the whole "vision is absent" branch below.
private final @Nullable VisionAlignFactory m_visionAlign;

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
  // Same drive requirement in both branches, so it sequences cleanly with no requirement fight.
  if (s.hasAlignToTagAtEnd() && m_visionAlign != null) {
    // Tag-relative: the only path that reaches 2 cm (design/03 §13.3).
    body = body.andThen(
        m_visionAlign.alignToTag(s.alignCameraIndex(), s.alignAcceptableTagIds(),
                                 s.alignTagRelativeGoal())
            .withTimeout(s.alignTimeoutSeconds())
            .beforeStarting(() -> PumpkinLog.critical(
                "Pumpkin/Auto/Steps/" + s.index() + "/AlignPath", "tag-relative")));

  } else if (s.hasAlignToTagAtEnd()) {
    // Vision absent. Fall back to the fused-pose align at the tolerance that path can hold —
    // NOT at the tag-relative tolerance, which would time out on every step. Logged, once.
    body = body.andThen(fusedAlign(s, PumpkinDriveToPose.kDefaultTolerance,
                                      PumpkinDriveToPose.kDefaultAngularTolerance, "fused-fallback"));

  } else if (s.hasAlignAtEnd()) {
    body = body.andThen(fusedAlign(s, s.alignTolerance(), s.alignAngularTolerance(), "fused"));
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

```java
/** The fused-pose branch, shared by alignAtEnd and by alignToTagAtEnd's no-vision fallback. */
private Command fusedAlign(AutoStep s, Distance tol, Angle angTol, String pathLabel) {
  Supplier<Pose2d> target = s.alignTarget() != null
      ? s.alignTarget()
      : () -> s.trajectory().finalPose().orElseThrow();
  return PumpkinDriveToPose.builder(m_drive)
      .target(target)
      .tolerance(tol, angTol)
      .timeout(s.alignTimeoutSeconds())
      .build()
      .asCommand()
      .beforeStarting(() -> PumpkinLog.critical(
          "Pumpkin/Auto/Steps/" + s.index() + "/AlignPath", pathLabel));
}
```

`Pumpkin/Auto/Steps/<i>/AlignPath` is a **CRITICAL** string topic. Which of the three paths a step actually took — `"tag-relative"`, `"fused"`, `"fused-fallback"` — is the first question anyone asks about an auto that scored a few centimetres off, and it must be answerable from a match log rather than by reproducing the camera state in the shop.

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
    .withGoals(superstructure)             // GoalBus<Goal>
    // One line, and it is what makes .alignToTagAtEnd(...) below reach 2 cm instead of 5.
    // Omit it and every alignToTagAtEnd step still runs, at the fused-pose tolerance, logged.
    .withVision((cam, ids, goal) ->
        VisionCommands.alignToTag(drive, vision, cam, ids, goal, AlignGains.tagRelative()));

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
        // Hand off from the trajectory to a closed-loop align at the scoring face. This is what
        // separates an auto that scores 3 from one that scores 3 *reliably*. The align is
        // TAG-RELATIVE, which is the only path that holds 2 cm — see the tolerance budget below.
        .alignToTagAtEnd(kReefCamera, FieldPoses.kBlueReefLeftTagIds,
                         FieldPoses.kReefLeftL4TagRelative, 0.6)
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
        // Same face, same camera, same tag-relative goal. If the camera were dead this degrades
        // to a 5 cm fused align and logs AlignPath = "fused-fallback" rather than failing.
        .alignToTagAtEnd(kReefCamera, FieldPoses.kBlueReefLeftTagIds,
                         FieldPoses.kReefLeftL4TagRelative, 0.6)
        .then(superstructure.request(Goal.L4_SCORE).withTimeout(0.8)))
    .skipToAfter(/* seconds left */ 2.0, "park")
    .step("park", s -> s.driveTo(() -> FieldPoses.kBlueParkPose).deadline(2.0))
    .onEnd(superstructure.request(Goal.STOW))
    .build();
```

**What is guaranteed by construction here:**
- `superstructure.request(...)` and `.goal(...)` never take the drive requirement, so they cannot cancel the auto (the PathPlanner NamedCommand footgun is structurally unreachable).
- `before(0.30, ...)` means the same thing whether `StartToReef` is a Choreo `.traj` or a PathPlanner `.path` — and because `before()` is only legal on a step that has a trajectory, it can never be a trigger that silently never fires.
- `alignToTagAtEnd(...)` closes the loop on the scoring *tag*. The trajectory's job is to get there fast; the align's job is to get there *right*. This is the single change in this document that most raises real-world auto scoring reliability — and §6.7.1 is the arithmetic that says why it has to be the tag and not the pose.
- Every pose is blue-authored; red flipping happens once, in `PumpkinField`.
- `skipToAfter(2.0, "park")` reads "with 2 seconds left, go park" and is computed against `FieldMap.autoPeriodSeconds()`. Nothing here knows that 2026 REBUILT's auto is 15 s, so nothing here breaks when 2027's isn't.
- Every step has a hard deadline. A stuck intake costs 1 s, not the match.
- The whole auto emits a per-step timeline you can read in AdvantageScope after the match, including which align path each step actually took.

#### 6.7.1 The alignment tolerance budget — why the fused-pose path cannot deliver 2 cm

**This subsection exists because revision 3 of this document claimed 2 cm from a controller that cannot produce it.** The old comment read *"the trajectory gets us within ~6 cm, the align gets us within 2 cm."* Both halves were wrong: the 6 cm was uncited, and the 2 cm was a tolerance the *sensor* cannot satisfy on the path the DSL could actually reach. `design/03` §13.3 states the invariant plainly — 2 cm "is achievable [in `alignToTag`] and nowhere else" — and `design/03` §13.2 ordered this document to change its default. Here is the arithmetic behind both.

**Term 1 — vision standard deviation at scoring range.** `design/03` §8.4's `StdDevModels.pumpkinDefault()` is `advantageKit(0.02, 0.06)`: `σ_xy = linearBaseline · d² / n`, with MegaTag2 taking an additional 0.5× on the linear term. At the range `design/03` §13.2 fixes as the reference case — **d = 3 m, n = 2 tags, MegaTag2**:

```
σ_xy = 0.5 · 0.02 · d² / n
     = 0.5 · 0.02 · 3² / 2
     = 0.5 · 0.02 · 4.5
     = 0.045 m  =  4.5 cm
```

That single term is already **2.25× the 2 cm** the old example asked for. Nothing downstream of it can undo that; a tighter tolerance on a noisier measurement buys timeouts, not precision.

The distance dependence is steep enough to be worth writing out, because it is also the honest answer to "but our scoring pose is close to the tags":

| d (m) | n = 1 | n = 2 | σ_xy = 0.5 · 0.02 · d²/n |
|---|---|---|---|
| 0.5 | 0.25 cm | 0.13 cm | |
| 1.0 | 1.0 cm | 0.5 cm | |
| 2.0 | 4.0 cm | 2.0 cm | |
| **3.0** | 9.0 cm | **4.5 cm** | ← `design/03` §13.2's reference case |
| 4.0 | 16.0 cm | 8.0 cm | |

**Term 2 — and this is the one that makes the close-range column misleading — field-layout error.** The fused pose is expressed in the *published* field frame; the scoring pose is authored in that same frame; the tag is a *physical object* that may not be where the layout says. On a fused-pose align, any difference between the published layout and the as-built field is a pure, uncorrectable bias — it moves the goal without moving the estimate. FIRST's own 2025 Team Update 12 documents that on an AndyMark field perimeter the PROCESSOR opening and its AprilTags shift about **2.7 in (6.9 cm)** in X relative to the published layout, and that the CORAL STATION connection varies overall field width and those tags in both X and Y ([Team Update 12](https://firstfrc.blob.core.windows.net/frc2025/Manual/TeamUpdates/TeamUpdate12.pdf)). **[UNVERIFIED — this figure comes from a search summary of the primary PDF; the PDF could not be machine-read in this pass. Re-read it before quoting the number in published docs.]** The existence of WPILib's **WPIcal** tool, and `design/03`'s `FieldLayouts` WPIcal-ingestion + delta-logging path, are themselves evidence that this term is real and non-trivial ([WPIcal docs](https://docs.wpilib.org/en/stable/docs/software/wpilib-tools/wpical/index.html)). On the tag-relative path this term is **exactly zero**: the goal is expressed relative to the tag we are looking at, so a mislocated tag moves the measurement and the goal together.

**Term 3 — heading.** Vision never corrects heading in PumpkinLib: `σ_θ` is pinned to `StdDevModels.UNTRUSTED_SIGMA` for every gyro-fused source (§8.2, §8.3), so field heading is the gyro plus the offset seeded at the last `resetPose`. A residual heading error `ε` displaces a control point offset `r` from the robot centre by `r · sin ε` — at `r = 0.35 m` and `ε = 1.0°` that is `0.35 · sin(1°) = 0.0061 m ≈ 6 mm`. Small, but it is a *bias*, and it is one more term the tag-relative path does not carry, because the tag-relative controller measures its own yaw error against the tag face directly.

**The budget, and the two numbers that come out of it.**

| Path | σ_xy at reference range | Layout bias | Heading bias @ 0.35 m, 1° | Honest tolerance |
|---|---|---|---|---|
| `alignAtEnd` (fused pose) | 4.5 cm | present, uncorrected | 6 mm | **5 cm / 2.0°** |
| `alignToTagAtEnd` (tag-relative) | not applicable — the loop closes on `cameraToTag` | **cancels** | **cancels** | **2 cm / 1.0°** |

So: `PumpkinDriveToPose`'s builder default becomes 5 cm / 2.0°, matching `AlignGains.defaults()`. **This paragraph derives the number; it does not declare it.** §9 declares it exactly once, as `PumpkinDriveToPose.kDefaultTolerance` (`Meters.of(0.05)`) and `kDefaultAngularTolerance` (`Degrees.of(2.0)`), and every other site in this document, in `DESIGN.md` §10B and in the `alignToTagAtEnd` fused fallback reads those two constants **by name** rather than retyping the literal. 2 cm is reserved for `AlignGains.tagRelative()` behind `alignToTagAtEnd`; and both worked examples in this document say which one they are using and why.

**On the deleted "~6 cm" trajectory figure.** It was never measured. The honest statement is that a trajectory's terminal error is a property of the team's own feedforwards, wheel radius and odometry health, which is exactly what `OdometryReport.trajectoryTest(drive, traj)` (§8.5) exists to measure — it reports pose error against the trajectory's own samples, per run, on that robot. **Ship the routine, not the folklore number.** Until a team has run it, the correct thing to say is "the trajectory's terminal error is whatever `trajectoryTest` says it is on your robot, and the align is what makes the step insensitive to it."

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
   * Hard gates. Any one false => the command raises
   * Alerts.warning("char.refused." + routine, "<reason>", MatchImpact.PIT_ONLY), publishes
   * Pumpkin/Char/Refused = "<reason>", and ends WITHOUT actuating.
   *
   *  1. TuningRegistry.isTuningEnabled() is true.   (D12: there is no Pumpkin.TUNING_MODE)
   *  2. MatchContext.isFMSAttached() is FALSE. Characterization never runs at an event on a
   *     field. NOT DriverStation.isFMSAttached() — ArchUnit rule 10 permits only
   *     org.pumpkinlib.core.match.. to name DriverStation, and MatchContext latches the value on
   *     the DS-connect edge, which is also the behaviour we want here: a DS that drops out
   *     mid-ramp must not silently re-arm a stall test. (Revision 3.1; DESIGN.md §16 item 3.)
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
- We **do** correct drift structurally: vision rotation std dev defaults to `StdDevModels.UNTRUSTED_SIGMA` (`1.0e6`, `design/03` §8.0 Rule 1 — a *named, finite* sentinel, never a raw `9_999_999` and never `POSITIVE_INFINITY`) so vision never moves heading, and heading is re-seeded from a multi-tag observation only while **disabled** (the MegaTag1-while-disabled pattern 9143 already runs). `AlliancePerspective.resetFieldRotation` is the only other writer.
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
  /** Enforced structurally: vision rotation std dev is pinned to StdDevModels.UNTRUSTED_SIGMA
   *  unless the robot is disabled AND the observation has >= 2 tags. Prevents the MegaTag2
   *  heading feedback loop. design/03 §9.4 enforces the same pin on its side of the seam, after
   *  every user-supplied decorator and per-camera factor; two enforcement points, one constant. */

  /**
   * WHERE tagCount AND avgTagDistanceMeters WENT, now that D17 is applied (§1.3, §3.3.2).
   *
   * D17's VisionConsumer.accept(Pose2d, double, Matrix<N3,N1>) does NOT carry tagCount or
   * avgTagDistanceMeters, which the deleted VisionObservation [SUPERSEDED-NAME] did and which the trust model
   * below reads. That is CORRECT, and the resolution is not to widen the signature:
   *
   *   - tagCount and distance are INPUTS TO THE STD-DEV MODEL, and design/03 §8 owns that model.
   *     By the time a frame reaches this sink, vision has already folded both into the Matrix.
   *     Re-deriving trust from them here would be a second, disagreeing model on the same
   *     pipeline — the exact drift §8.0 Rule 1 argues against for the sentinel.
   *   - What THIS domain uniquely knows is skid state, which vision cannot see. That stays here,
   *     as a MULTIPLIER on whatever matrix arrives:
   *         effective = arriving ⊙ skidInflation(skidReport)
   *     applied inside accept(...), clamped to [1, 100] by withSkidInflation.
   *   - The reject predicates below (gyro rate, off-field, no tags) are POLICY, not modelling.
   *     rejectWhenNoTags() is expressed as "reject when the arriving σ_xy exceeds the no-tag
   *     threshold", because a zero-tag frame is exactly a frame vision already assigned an
   *     enormous sigma. No extra field is required to implement it.
   *
   * If a team genuinely needs raw tag counts drive-side, the documented path is the secondary
   * hook OdometryTrust.withFrameMetadata(IntSupplier tagCount, DoubleSupplier avgTagDistance),
   * pushed by the vision domain alongside accept(...) and explicitly NOT part of D17's seam.
   */
  public OdometryTrust withFrameMetadata(IntSupplier tagCount, DoubleSupplier avgTagDistanceMeters);
}
```

**Std-dev models are the vision domain's, not this one's (§0.2).** Revision 3 of this section recommended `xy = 0.3 + 0.4 * d² / max(1, tagCount)` — an independently-invented formula that disagrees with `design/03` §8.4's shipped models by more than an order of magnitude at competition range (at `d = 3 m, n = 2` it yields `0.3 + 0.4·9/2 = 2.1 m` against `pumpkinDefault()`'s `4.5 cm`). Two disagreeing models on the two halves of one fusion pipeline is precisely the drift that `design/03` §8.0 Rule 1 argues against for the *sentinel*, and it matters more for the *model*. **That formula is withdrawn.** The canonical values are:

- **wheel/odometry base:** `VecBuilder.fill(0.1, 0.1, 0.1)` — this document's, and unchanged; the surveyed repos converged on it independently.
- **vision, enabled:** whatever `design/03` §8.4's selected `StdDevModel` returns. The default is `StdDevModels.pumpkinDefault()` == `advantageKit(0.02, 0.06)`, i.e. `σ_xy = 0.02·d²/n` with MegaTag2 taking a further 0.5× on the linear term — **4.5 cm at 3 m with two tags**, derived in §6.7.1. `σ_θ` is pinned to `StdDevModels.UNTRUSTED_SIGMA`.
- **vision, disabled, ≥2 tags:** `σ_θ = 0.3`; **disabled, 1 tag:** `σ_θ = 0.9`. This is the one place this domain sets a rotation sigma, because it is the disabled-seed policy (§8.2), not a model.

If a team wants the looser behaviour the withdrawn formula produced, that is `StdDevModels.legacy8793(base)` or a custom `StdDevModel` — selected in the vision domain, in one place, where it is logged.

**Timestamp discipline:** the `fpgaTimestampSeconds` argument handed to `PumpkinDrive.accept(...)` (§3.3.2's `VisionConsumer`) is preserved byte-for-byte to the estimator. At 4 m/s a 20 ms timestamp error is `4.0 · 0.020 = 0.08 m = 8 cm` — larger than the 5 cm fused-pose alignment tolerance §9 defaults to, and 4× the 2 cm tag-relative one. `accept(...)` never re-timestamps and never buffers; the only legal transformation is the CTRE Phoenix-timebase conversion, which happens one layer down in `CtreSwerveBackend` (§3.4.1) and is unchanged by D17.

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

  /** The fused-pose-honest tolerance, named so §6.5's alignToTagAtEnd fallback and the DSL cannot
   *  drift apart from the builder default. Equal to AlignGains.defaults() (design/03 §13.2). */
  public static final Distance kDefaultTolerance        = Meters.of(0.05);
  public static final Angle    kDefaultAngularTolerance = Degrees.of(2.0);

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

    /**
     * Terminal tolerance. READ THIS BEFORE TIGHTENING IT.
     *
     * <p>2 cm alignment requires tag-relative control (`VisionCommands.alignToTag`, reachable
     * from the auto DSL as `AutoStep.alignToTagAtEnd`); a fused-pose controller — which is what
     * this class is — cannot reliably hold tighter than the vision standard deviation at your
     * scoring distance. With the default std-dev model, `σ_xy = 0.5 · 0.02 · d² / n`, which is
     * about 4.5 cm at 3 m with two tags on MegaTag2, before the field-layout and heading biases
     * that the fused-pose path carries and the tag-relative path cancels. A 2 cm tolerance here
     * is a tolerance the sensor cannot satisfy, and the only thing it produces is a timeout alert
     * on every single alignment. The default is therefore 5 cm / 2.0 deg, which is what a
     * fused-pose controller can actually hold, and it matches `AlignGains.defaults()` exactly.
     * Full derivation in §6.7.1; the requirement is `design/03` §13.2.
     */
    public Builder tolerance(Distance linear, Angle angular);             // default: kDefaultTolerance,
                                                                          //          kDefaultAngularTolerance
    /** Must hold tolerance this long before atGoal() latches. Kills the "flickers into tolerance" bug. */
    public Builder settleTime(double seconds);                            // default 0.06 s

    public Builder timeout(double seconds);                               // default 3.0 s
    /** Command ends (unsuccessfully) when this becomes true. */
    public Builder abortWhen(BooleanSupplier condition);
    /**
     * Driver keeps partial authority: their stick is added, scaled. Never zero.
     *
     * <p>Suppliers are in DRIVER-PERSPECTIVE units — <b>forward-positive, left-positive</b> — so
     * the `CommandXboxController` sign inversion happens at the call site, once, visibly, rather
     * than being buried in this class where nobody can see it. The builder applies
     * `PumpkinMath.deadband2d(0.10)` to the pair internally (radial, not per-axis — §2), and when
     * `allianceRelative` is true it rotates the nudge by `AlliancePerspective.operatorForward()`
     * so a red-alliance driver's "forward" nudge pushes the robot the way they are looking.
     *
     * <p>Revision 3.1: this used to be a three-argument `(x, y, scale)` with no stated frame and
     * no alliance handling, which meant the highest-stakes manual override in the library was the
     * one place §4's alliance discipline did not reach. (`DESIGN.md` §16 item 2(b).)
     */
    public Builder driverNudge(DoubleSupplier forward, DoubleSupplier strafe,
                               double scale, boolean allianceRelative);
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

  PumpkinLog.critical("Pumpkin/Align/Target", target);
  PumpkinLog.critical("Pumpkin/Align/ErrorMeters", errorMeters);
  PumpkinLog.critical("Pumpkin/Align/ErrorDegrees", Math.toDegrees(thetaErrorRad));
  PumpkinLog.log     ("Pumpkin/Align/CommandedFieldSpeeds", field);
  PumpkinLog.log     ("Pumpkin/Align/ProfileVelocity", m_linearController.getSetpoint().velocity);
  PumpkinLog.log     ("Pumpkin/Align/Settling", m_settling);
  PumpkinLog.critical("Pumpkin/Align/AtGoal", m_settling && m_settleTimer.hasElapsed(m_settleSeconds));
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
    Alerts.warning("align.timeout",
        "Drive-to-pose timed out " + String.format("%.3f", linearErrorMeters()) + " m from target. "
      + "Either the target is unreachable, tolerance is too tight, or odometry is drifting. "
      + "Run OdometryReport.squareTest(drive, Meters.of(3)) to check. If you tightened tolerance "
      + "below the 0.05 m default, that is the first thing to undo — see design/05 §6.7.1.",
        MatchImpact.PIT_ONLY);       // D10 (§1.4): one alignment missed, the match continues
  }
  PumpkinLog.critical("Pumpkin/Align/EndReason", m_end.name());
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
  // Tuner X generates kFrontLeftXPos / kFrontLeftYPos (both `Distance`) per module, and
  // kSpeedAt12Volts / kWheelRadius at the drivetrain level. There is NO kFrontLeftLocation
  // field — revision 3 of this example invented one. (DESIGN.md §16 item 2(c); the explicit
  // form is DESIGN.md Appendix A.)
  private final CommandSwerveDrivetrain m_dt = TunerConstants.createDrivetrain();

  private final PumpkinDrive m_drive = PumpkinDrive.of(
      new CtreSwerveBackend(
          m_dt,
          /* Subsystem requirement */ m_dt,   // NOT null: CommandSwerveDrivetrain IS the Subsystem,
                                              // and passing null would synthesize a second one (§3.3.1)
          DriveGeometry.swerve(
              new Translation2d(TunerConstants.kFrontLeftXPos,  TunerConstants.kFrontLeftYPos),
              new Translation2d(TunerConstants.kFrontRightXPos, TunerConstants.kFrontRightYPos),
              new Translation2d(TunerConstants.kBackLeftXPos,   TunerConstants.kBackLeftYPos),
              new Translation2d(TunerConstants.kBackRightXPos,  TunerConstants.kBackRightYPos),
              TunerConstants.kWheelRadius),
          DriveLimits.of(TunerConstants.kSpeedAt12Volts, MetersPerSecondPerSecond.of(8.0),
                         DegreesPerSecond.of(540), DegreesPerSecondPerSecond.of(720))),
      PumpkinDriveConfig.v01Competition());   // TractionLayer/SkidDetector are M15 (§3.7)

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
    // FUSED-POSE align: the target is "nearest scoring pose", a multi-metre approach in the field
    // frame, so 5 cm / 2 deg is the honest tolerance and is now also the default (§6.7.1).
    // Asking for 2 cm here is what produced a timeout alert on every alignment in revision 3.
    // Driver nudge suppliers are DRIVER-PERSPECTIVE (forward-positive, left-positive), so the
    // controller's sign inversion happens here, once, visibly.
    m_driver.a().whileTrue(
        PumpkinDriveToPose.builder(m_drive)
            .target(() -> FieldPoses.nearestScoringPose(m_drive.getPose()))
            .tolerance(PumpkinDriveToPose.kDefaultTolerance,
                       PumpkinDriveToPose.kDefaultAngularTolerance)   // §9 declares these ONCE
            .timeout(2.5)
            .abortWhen(() -> Math.abs(m_driver.getLeftY()) > 0.5)   // driver always wins
            .driverNudge(() -> -m_driver.getLeftY(), () -> -m_driver.getLeftX(),
                         /* scale */ 0.25, /* allianceRelative */ true)
            .build()
            .asCommand()
            .alongWith(m_superstructure.request(Goal.L4_PREP)));

    // For the 2 cm version of this button, the tag-relative command is the one to bind — same
    // shape, different feedback signal (design/03 §13.3):
    //   m_driver.b().whileTrue(VisionCommands.alignToTag(
    //       m_drive, m_vision, kReefCamera, FieldPoses.kBlueReefLeftTagIds,
    //       FieldPoses.kReefLeftL4TagRelative, AlignGains.tagRelative()));

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

**Line count for a competitive drivetrain + auto stack: 108 lines** — counted, not estimated: non-blank, non-comment lines in the block above, recounted after revision 3.1's edits. None of them is `AutoBuilder.configure`'s eight arguments, a hand-written `shouldFlipPath` lambda, a discretization call, a slip limiter, a heading controller, a wheel-force feedforward plumbing block, or a per-alliance trajectory file.

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
| A logging framework | **AdvantageKit — a REQUIRED dependency** (maintainer decision 3), not one option among several | A 10-method `PumpkinLog` **facade** over AdvantageKit's `Logger`. ~~fans out to whichever the team uses~~ — there is no fan-out and no `LogBackend` SPI; a team on DogLog or plain Epilogue cannot adopt PumpkinLib without switching loggers |
| SysId | WPILib `SysIdRoutine`; CTRE `SysIdSwerveTranslation`/`Rotation`/`SteerGains` | Wrap them, and add the three things SysId does *not* measure: wheel radius, MOI, COF |
| A graph-search superstructure | 254's `AStarSolver` + `SuperstructureStateMachine`, 6328's JGraphT graph — and the PumpkinLib mechanism domain | Define the `GoalBus` contract; consume it |
| A dashboard | Elastic | Publish stable NT4 topics; the dashboard domain generates the layout |

---

## 12. WPILib 2027 migration plan (this domain)

> **Revision 3 correction to the timing and the shape of this plan.** The 2027 break is **not** "~5 months from now" in any operational sense. Under maintainer decision 1 there is no 2026 release, so nothing in this document ships against WPILib 2026 at all. The port is **M12**, the single date-triggered milestone: it arms at the first *confirmed* 2027 alpha (~Oct 2027), must complete inside the beta window, and at solo pace **preempts M11**. Development stays on 2026.2.2 / Java 17 through M11 and does **not** chase alphas ([`ROADMAP.md` §7.2](../ROADMAP.md)). Item 7 below — *two artifacts from one tree, `:2026.x` and `:2027.x`* — is **reversed**: the project is **single-line** after M12, and the 2026 source line plus the rename generator are **deleted at the end of it**, because there are no external users on the 2026 line to protect and 8793/9143 port with the library.

The 2027 break changes: `edu.wpi.first` → `org.wpilib`, `ChassisSpeeds` → `ChassisVelocities`, kinematics immutable, `ChassisAccelerations` added, `RamseteCommand`/`SwerveControllerCommand`/`MecanumControllerCommand` removed, Commands v3 (coroutines) alongside a pruned v2, Java 25, Systemcore, NT3 removed, Shuffleboard/SmartDashboard/PathWeaver/RobotBuilder removed. Phoenix 6 renames `ApplyRobotSpeeds` → `ApplyRobotVelocity` and removes `new Device(int, String)`.

What this design does about it, concretely:

1. **Vendor types appear in exactly five files.** `CtreSwerveBackend`, `YagslBackend`, `AdvantageKitSwerveBackend`, `PathPlannerSource`, `ChoreoSource`. Nothing else in this domain imports `com.ctre`, `com.pathplanner`, `choreo`, `swervelib`, or `org.littletonrobotics`. The 2027 port for those five files is bounded and known today.
2. **`PumpkinAuto`, `PumpkinTrajectory`, `AutoStep`, `PumpkinDriveToPose`, `PumpkinField` leak zero vendor types.** A team's `RobotContainer` needs no rewrite.
3. **WPILib types are used freely** and ported by find/replace. `ChassisSpeeds` → `ChassisVelocities` is mechanical; `SwerveModuleState.optimize` is already migrated to the 2026 mutating-instance form.
4. **`SkidDetector`, `TractionLayer` math, `PumpkinMath`, the trigger engine's timing logic, and `OdometryReport`'s error math are HAL-free and command-free** — they live in a `pumpkin-solvers` source set with no WPILib command dependency, so they port byte-for-byte and are unit-testable off-robot today.
5. **Commands are always *returned* from factories, never subclassed by users.** Commands v3's coroutine model changes how commands are *authored*, not how they are *composed*, so a v3 backend is an internal swap.
6. **No `SmartDashboard`/`Shuffleboard`/`SendableChooser`-on-SmartDashboard from library code.** `PumpkinAutoSelector` publishes raw NT4 topics.
7. ~~**Two artifacts from one tree**: `org.pumpkinlib:pumpkin-drive-auto:2026.x` and `:2027.x`, differing only in the five adapter files and the package roots.~~ **REVERSED (revision 3, two ways).** (a) There is no `pumpkin-drive-auto` artifact — under **D28** this domain's packages ride inside the single `dev.pumpkinlib:pumpkinlib` jar, with `pumpkinlib-pathplanner` and `pumpkinlib-choreo` as the only separate coordinates it touches. (b) There is no permanent 2026/2027 pair: the generated-source variant and the dual-compile CI exist **only inside M12** and are deleted at its end. Maintaining two source lines for two more years would be a permanent 20–30% tax on every milestone after M12, paid to protect nobody.

---

## 13. Delivery plan

> **⛔ The four-phase release plan below is DELETED as a release plan (maintainer decision 1).** There is **one** release, `v0.1`, and it contains all of this domain except the two items explicitly named as post-v0.1 below. What survives is **build order**, remapped onto the milestones in [`ROADMAP.md` §5](../ROADMAP.md), which is authoritative for every date. Old phase names are kept in the left column only so a revision-2 reader can navigate.

| Was called | Now built at | Contents | Person-weeks |
|---|---|---|---|
| "v0.1" | **M9 — Drive funnel, CTRE backend, field and alliance** | `PumpkinDrive` + funnel; `CtreSwerveBackend`; `PumpkinField`/`AlliancePerspective`/`AllianceValue`; `PumpkinDriveToPose`; `PumpkinCharacterization.feedforward/wheelRadius` + `CharacterizationSafety`; `DriveSelfCheck`; `DriveInputStream`; **`OdometryReport.outAndBack`/`.squareTest` in the SAME milestone as the funnel (R8)** | 4.0 |
| "v0.2" | **M11 — Auto DSL, PathPlanner AND Choreo** | `PumpkinAuto.withPathPlanner()` **and** `.withChoreo()`; `PumpkinTrajectory` + the PumpkinLib trigger engine over `TrajectoryHandle`; `PumpkinAutoRoutine`/`AutoStep` with budgets, deadlines, `successWhen`, `orElse`, `retry`, `skipToAfter`; **`alignAtEnd` + `alignToTagAtEnd` + `VisionAlignFactory` (revision 3.1, +0.2)**; `PumpkinAutoSelector` with dependent questions; `NamedCommandRegistry` | 3.2 |
| "v0.3" | **M15 — Drive backends 2–5, traction, navigation** | `AdvantageKitSwerveBackend`; `HandRolledSwerveBackend`; `YagslBackend`; **`DifferentialBackend`**; `TractionLayer`; `SkidDetector` + `SkidReport`/`OdometryTrust`; `PumpkinCharacterization.slipCurrent/momentOfInertia/wheelCof` + planner writeback; `PumpkinNav` + `LocalADStarAK` + warmup | 3.5 |
| — | **M21** | `PumpkinAutoTest` + `AutoTestResult` + the `pumpkinAutoReport` Gradle task; the maple-sim adapter | (booked in M21) |
| "v0.4 (2027 branch)" | **M12 — the WPILib 2027 port** | `org.wpilib` port, Commands v3 backend, Phoenix 6 2027 request renames. **Date-triggered**, and this domain's share is inside M12's globally budgeted 8.0 pw, not additional to it | 1.5 |

**Total: 12.2 person-weeks** for the domain, recomputed rather than restated: `4.0 (M9) + 3.2 (M11) + 3.5 (M15) + 1.5 (M12) = 12.2`. Revision 3 said 12.0 against an M11 of 3.0; revision 3.1 adds **+0.2 pw to M11** for `alignToTagAtEnd`, `VisionAlignFactory`, the three-way `compile()` branch, the four new `build()` validation rules and the `AlignPath` telemetry. M21's `PumpkinAutoTest` share is booked in M21 and is not in this column, unchanged.

**Why +0.2 and not more:** `VisionCommands.alignToTag` itself is **already built at M10** (`design/03` §19 books it there, +0.4 pw, explicitly *"a CONTROLLER, not new perception"*), and M10 precedes M11. What M11 adds is a one-method factory interface, a step field with four accessors, one branch in `compile()`, one shared `fusedAlign` helper, four validation rules and two log keys. That is a day of work and a day of tests, which is what 0.2 pw buys at this document's rates.

**The "offseason-realistic at ~8 focused hours/week from Aug through Dec 2026" line is withdrawn** — it was written against a December 2026 release that no longer exists. At solo pace M9 completes **2027-08-31**, M11 **2028-01-01** and M15 **2028-10-24** ([`ROADMAP.md` §5.1](../ROADMAP.md)); at +2 committers, 2027-02-17, 2027-04-20 and 2027-09-15. **The +0.2 is inside `ROADMAP.md`'s own ±25% band (R22), so no published date moves**; `ROADMAP.md` §5 should nonetheless carry the M11 line item so the scope change is visible rather than absorbed silently.

> **Reconciling 12.2 with the roadmap.** `ROADMAP.md` books M9 + M11 + M15 at **13.0 pw**; this column's M9 + M11 + M15 is `4.0 + 3.2 + 3.5 = 10.7`, with M12's 1.5 accounted separately inside M12's globally budgeted 8.0. The 12.2 above is this domain's *raw* estimate as it entered the §1 roll-up; the milestone figures are the post-roll-up allocation after integration savings and the adversarial-review additions were redistributed. The two do not reconcile, `DESIGN.md` §16 item 5(a) says so in as many words, and **`ROADMAP.md` remains authoritative** for milestone numbers and dates. Recording the +0.2 here rather than hiding it is the point; the reconciliation is a separate, already-acknowledged debt.

**`OdometryReport` ships with the funnel, not after it.** It is in the M9 row above, in the same milestone as `PumpkinDrive` and in the same package release — `DESIGN.md` §16 item 2(d) ordered this and R8 is the reason: a drive layer whose users cannot measure their own odometry error is a drive layer whose users tune `PumpkinDriveToPose` against a pose they have no evidence for. §8.5's gating rule depends on the report existing from day one.

**Two items are genuinely post-v0.1, and that is a reduction rather than a deferral to a later release.** `MecanumBackend` and `PumpkinNav.useProfile(String)` appear in **no** milestone M1–M24. Since the v0.2/v0.3 release plan is deleted, there is no scheduled version that contains them: they are outside v0.1, and if they are ever built it is after the tag. See open questions 2 and 9.

**One item became non-optional.** `DifferentialBackend` used to be a "v0.1" line item and then a candidate for cutting. Under maintainer decision 2 it is what makes `PumpkinTemplate`'s advertised `differential` variant real, so it cannot be dropped — but it lands at **M15**, which is *after* the template exists at M8. `pumpkin init --template differential` must therefore fail with a named message and a pointer, not generate a project that cannot drive.

---

## 14. Open Questions

1. **PathPlanner single-path event-marker times.** Does PathPlannerLib 2026.1.2 expose a public accessor for a `PathPlannerPath`'s event markers and their trajectory times outside a running `PathPlannerAuto`? If yes, `PathPlannerSource.eventTimes()` uses it; if no, we parse `deploy/pathplanner/paths/*.path` JSON. **Ship the JSON parser regardless**, and switch if the accessor is confirmed. Marked **[UNVERIFIED]** in §6.1.
2. **Navgrid hot-swapping.** 254 swaps between `navgrid.json` / `auto_navgrid.json` / `backoff_navgrid.json` by phase, but achieved this by *vendoring* PathPlannerLib. Does 2026.1.2 expose a public API to point `LocalADStar` at a different grid file? If not, `PumpkinNav.useProfile(String)` either ships our own `Pathfinder` implementation (~200 lines reading a selected grid) or is dropped. **Revision 3: `PumpkinNav.useProfile(String)` is in no milestone and is outside v0.1** — `PumpkinNav` itself is M15 and is depth lever L1's first casualty. **Needs verification before committing to the API.**
3. **`SwerveDrivetrain` module accessor for wheel-radius characterization.** Confirm whether `getModule(int)`/`getModules()` exist and expose the drive motor in Phoenix 6 26.x. The `ModulePositions[i].distanceMeters / wheelRadiusMeters` fallback is definitely correct and is what we ship; the direct accessor would be marginally more accurate (no wheel-radius circularity). **Low risk either way.**
4. ~~**Auto period length for 2027.**~~ **RESOLVED, and it was a real defect, not an open question.** Every consumer now reads `FieldMap.autoPeriodSeconds()`: `PumpkinAutoTest.Builder.withAutoPeriodSeconds` defaults to it (§7.4), `PumpkinAutoRoutine.skipToAfter` is specified in *seconds remaining* against it and `build()` rejects an argument that is `>= ` the period or `<= 0` (§6.4), and both worked examples pass seconds-remaining (§6.7, §10). D15 is now structurally enforced rather than aspirational. The only remaining action at 2027 kickoff is the one-line `FieldMap` edit this was always supposed to enable.
5. **maple-sim 2027/Systemcore support.** maple-sim is community-maintained and not officially blessed. If it does not ship for 2027 in time, `PumpkinAutoTest` degrades to a kinematic (no-slip, no-collision) sim world. We should design `SimWorld` so the degraded mode is a first-class option, not a failure.
6. **Should `TractionMode.SETPOINT_GENERATOR` be the default for `rookie()`?** Arguments for: it is the single biggest driver-visible improvement. Arguments against: it requires mass, MOI and COF, and a rookie team's guessed MOI makes it either useless or crippling. Current decision: **off in `rookie()`, on in `competition()`, and `PumpkinCharacterization.momentOfInertia` prints "you can now safely enable TractionMode.SETPOINT_GENERATOR" on success.** Revisit after we have real data from 8793 and 9143.
7. **Choreo `DifferentialSample` follow path.** `followChoreoSample` is specified for `SwerveSample`. The differential equivalent needs `DifferentialSample` (schema v2 added `alpha`) fed through `LTVUnicycleController`. The exact field names on `DifferentialSample` are **[UNVERIFIED]** — confirm against `choreo.autos/api/choreolib/java/choreo/trajectory/DifferentialSample.html` before implementing it at M15. This sits alongside the other **[UNVERIFIED]** ChoreoLib question the M11 gate must close: `Trajectory.getEvents(String)` / `getTotalTime()` (R12).
8. **`GoalBus` timing for `AutoStep.budget()`.** `plannedTransitionSeconds()` requires the mechanism domain to have measured transition costs (the 254 pattern). If that slips, budgets fall back to the trajectory's own planned time and mechanism overrun is not attributed. Acceptable at M11 — `characterizeTransitions()` is M14, after it, and depth lever L7 may drop it entirely.
9. **Do we ship a `mecanum` backend at all?** Mecanum is effectively unmaintained community-wise and `MecanumControllerCommand` is removed in 2027. 135's Consul is the only framework with mecanum parity. Revision-2 decision was *"yes, in v0.3."* **Revision 3 answer: no.** There is no v0.3, and mecanum is in no milestone M1–M24, so shipping it would mean adding scope to a 74.0 pw plan that is already a 2030 release at solo pace. The cost was never the ~150 lines; it is the vendor-parity test matrix, the sim plant, the template variant question and the permanent maintenance. `MecanumControllerCommand` is removed in 2027 and the community is not maintaining mecanum. **Out of v0.1, with the `DriveBackend` SPI left documented so it is writable by someone who needs it.**
10. **`skipToAfter`'s zero point.** The guard measures from the routine command's `initialize()`, not from the FMS autonomous transition (§6.4). On a real field those differ by at most one scheduler loop, and the routine-relative zero is what makes the guard meaningful in `PumpkinAutoTest` and in a teleop-scheduled dry run. But a routine scheduled late — a `Commands.defer` that blocks on a `.traj` load that `warmup()` somehow missed — would shift the whole budget. We log `Pumpkin/Auto/RoutineStartLatency` so the shift is visible. **Open:** should `build()` hard-fail a routine whose start latency exceeded ~100 ms, or is the logged number enough? **Leaning: log only**, revisit once we have real match logs from 8793 and 9143 — which now arrive at M11 on an internal snapshot, well before the M24 API freeze, so there is time to change the answer.

11. **Where does `WheelForces` module-order validation live?** PathPlanner emits FL, FR, BL, BR. CTRE's `SwerveDrivetrain` module order is whatever Tuner X generated. If a team reorders modules in Tuner X, the force arrays silently fight the robot under acceleration and nothing errors. We can detect it at runtime (correlate commanded force direction against measured module velocity direction during a hard acceleration) — is that worth ~60 lines in `DriveSelfCheck`, or is a documented convention plus the geometry sign check in §3.8 sufficient? **Leaning: add the runtime correlation check as a `PumpkinCharacterization.verifyModuleOrder(drive)` command rather than a boot check.**

12. ~~**Does the auto DSL get a tag-relative alignment step, or do the auto examples retreat to fused-pose-honest 5 cm claims?**~~ **RESOLVED (revision 3.1), and the answer is BOTH, because they are answers to different questions.** The review posed it as an either/or (REVIEW §10 open question 1). It is not one: the 5 cm correction is mandatory regardless — `alignAtEnd` exists, teams will use it on non-tag targets, and a default it cannot hit is a defect whether or not a second step exists. The tag-relative step is *additionally* worth +0.2 pw because the controller it wraps is already built at M10 and the alternative is that the library's own flagship auto example demonstrates the worse of its two alignment paths. So: `alignToTagAtEnd` ships (§6.4), `PumpkinDriveToPose`'s default becomes 5 cm / 2.0° (§9), §6.7.1 carries the arithmetic, and both worked examples say which path they take and why. **Still open, narrowly:** whether `alignToTagAtEnd`'s no-vision fallback should be a build-time *warning* as well as a runtime log. Current answer is log-only — an auto that refuses to build because a camera is unplugged is a worse failure than one that aligns 3 cm loose — but that is a judgement call, and a team that only ever runs with vision might want the louder version.

13. **Should `AutoStep` expose a tag-relative *goal library* rather than raw `Transform3d`s?** `alignToTagAtEnd(cam, ids, tagRelativeGoal, timeout)` is honest but verbose at the call site, and every scoring face on a given field shares a small set of goals ("20 cm out, squared up"; "20 cm out, 15 cm left"). A `TagGoals` holder in the field-constants domain would let a step read `.alignToTagAtEnd(kReefCamera, kReefLeftTags, TagGoals.kL4Left, 0.6)`. That is field-year-specific data, which is the field domain's job and not this one's, so the DSL keeps the raw type and the team keeps the constants file. **Revisit if `FieldMap` grows a scoring-pose table**, at which point the tag-relative goals belong next to the blue poses.

14. ~~**Who declares `AlignableDrive`?**~~ **RESOLVED (revision 3.2, 2026-08-08).** Binding **D16** says `org.pumpkinlib.drive`, and that is now where it is: declared in **§3.3.2** of this document, mirrored as a consumed surface by `design/03` §13.1, which no longer declares it. It was listed here because the `VisionAlignFactory` seam in §5.1 was deliberately designed to be *correct either way* so this document did not block on it — and as predicted, **nothing in §5.1 or §6.4 changed.**

---

## 15. Adversarial review log

This document has been through **two** review passes: one adversarial review (revision 3) and the six-lens design review of 2026-08-07 (revision 3.1). Findings from both are recorded here rather than silently absorbed, because the *reason* a line reads the way it does is the part that rots first.

### 15.1 Pass 1 — the adversarial review (revision 3)

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

### 15.2 Pass 2 — the six-lens design review, 2026-08-07 (revision 3.1)

| # | Section | Finding | Severity | Disposition |
|---|---|---|---|---|
| 9 | §3.2, §3.3, §3.8, §1.3, §0.2, §2 | The MegaTag2 gyro→field-offset contract (D16/D16a) was **specified in `design/03` §2.2 as a hard requirement on this domain and never applied here.** No `getRawGyro()`, no `m_gyroFieldOffset` with its two named writers, no `getGyroFieldHeading()`, no ninth `DriveSelfCheck`, and none of `PoseProvider`/`AlignableDrive`/`VisionConsumer` in `org.pumpkinlib.drive`. `design/03` states verbatim that this document's `Rotation2d getGyroHeading(); // raw gyro, CCW+, blue-origin frame` `[SUPERSEDED-NAME]` "is deleted. It is a contradiction inside a single line." An implementer building the drive domain from its own document recreates the original failure: MegaTag2 fed a power-on-frame yaw, producing confidently wrong translation with nothing noticing. | **blocking** | ~~**CONTRACT-PENDING, deliberately.**~~ **CLOSED at revision 3.2 (2026-08-08).** Revision 3.1 placed `<!-- CONTRACT-PENDING -->` markers at every affected site, each carrying the exact required declaration, the exact required alert text, and the reason, and did **not** apply them because the identical edit lands simultaneously in `design/03`, `design/04` and `DESIGN.md` §16 and a contract applied by two agents in parallel is a contract applied half-way. The contract-reconciliation pass has now applied all of them: §3.2 `getRawGyro()`, §3.3.2 the four D16 interfaces, **§3.3.3 the single ownership site for the gyro→field offset**, §3.3 the `VisionConsumer` sink, §3.8 check 9, §2 the package layout, §0.2 the ownership statement. The off-by-one is recorded at the site and in `design/03` §2.2a(3): `design/03` called it "an eighth check" against a list that already had eight, so it is **check 9**. |
| 10 | §1.3, §3.3, §0.2, §8.3 | Binding **D17** deleted `VisionObservation` `[SUPERSEDED-NAME]`; this document still defined the record and still declared `addVisionMeasurement(VisionObservation)` as the public sink, while `design/03` used the D17-compliant `VisionConsumer`. Two assigned domain docs specifying incompatible shapes for the single vision→drive seam. | **major** | ~~**CONTRACT-PENDING** for the type change~~ — **CLOSED at revision 3.2 (2026-08-08):** the record is deleted from §1.3, `VisionConsumer` is declared once in §3.3.2, and `PumpkinDrive`'s sink is `accept(Pose2d, double, Matrix<N3,N1>)`. **The one part that needed a design answer rather than a mechanical edit was answered at revision 3.1**, in §8.3: `tagCount` and `avgTagDistanceMeters` do not survive into D17's three-argument signature, and the correct resolution is that they should not — they are inputs to the std-dev model, which `design/03` §8 owns and has already folded into the `Matrix` by the time a frame arrives. What this domain uniquely knows is skid state, which stays here as a multiplier. A documented secondary hook, `OdometryTrust.withFrameMetadata(...)`, exists for teams that genuinely need the raw counts, and is explicitly not part of D17's seam. |
| 11 | §6.4, §6.7, §5.1, §6.5, §13 | The auto DSL's scoring handoff — this document's own "highest-leverage single line" — reached only `PumpkinDriveToPose`, a fused-pose controller, and claimed **2 cm**. `design/03` §13.3 states the opposite as a design invariant: fused-pose control is bounded below by the vision sigma, and 2 cm "is achievable [in `alignToTag`] and nowhere else." `alignToTag` appeared **zero times** in this document. The elite-auto capability existed in the vision domain and was not wired into autonomous. | **major** | **Applied, both halves, because the review's either/or was a false choice (§14 OQ 12).** `AutoStep.alignToTagAtEnd(int, int[], Transform3d, double)` added, reached through `PumpkinAuto.withVision(VisionAlignFactory)` so `org.pumpkinlib.auto` imports nothing from `org.pumpkinlib.vision` and the no-vision fallback is structural rather than a null check. Four new `build()` validation rules. `Pumpkin/Auto/Steps/<i>/AlignPath` logged CRITICAL, because "which of the three paths did that step take" is the first question anyone asks about an auto that scored 3 cm off. **+0.2 pw on M11**, derived in §13. |
| 12 | §9, §10, §6.7 | `design/03` §13.2's *"Cross-domain consequence, and it is a required change, not a suggestion"* — `PumpkinDriveToPose`'s default `tolerance(0.02 m, 1.5°)` must become `tolerance(0.05 m, 2.0°)` — **was never made**, not in the builder and not in either worked example. Every team copying the worked example gets a timeout alert on every alignment: the precise failure the vision doc predicts. | **major** | **Applied**, with the arithmetic written out rather than asserted (§6.7.1): `σ_xy = 0.5 · 0.02 · 3²/2 = 0.045 m` at the reference range, against a claimed 2 cm. `kDefaultTolerance`/`kDefaultAngularTolerance` are now named constants so the builder default, the DSL fallback and `AlignGains.defaults()` cannot drift apart. The uncited "~6 cm" trajectory figure that sat next to the 2 cm claim is deleted and pointed at `OdometryReport.trajectoryTest`, per Principle 10 and per the precedent §8 set when it deleted its own uncited odometry statistic. The third site, `DESIGN.md` §10B, belongs to that document. |
| 13 | §3.4.4, §8.2, §8.3 | Raw literal `9_999_999` for vision heading trust, where `design/03` §8.0 Rule 1 establishes `StdDevModels.UNTRUSTED_SIGMA = 1e6` as the library-wide named sentinel with the explicit rationale that every sentinel in the library should follow it. Two magic numbers for one concept across the two halves of one fusion pipeline. | minor | **Applied** at all four sites. **Went further, and it is the more important half of the finding:** §8.3 also carried a *competing std-dev model* — `xy = 0.3 + 0.4·d²/n`, which yields 2.1 m at the range where `design/03`'s shipped model yields 4.5 cm. §0.2 already says the vision domain owns std-dev models; §8.3 now actually defers to them instead of restating a disagreeing one. |

Four items were changed **beyond** the five findings routed here, and are flagged as such in place:

- **§1.4 / D10 `MatchImpact`** — REVIEW M5 found that `MatchImpact`, *"required at every call site with no default and no single-argument overload,"* appears in **zero** of the five domain docs. This document used a two-argument `PumpkinAlerts` facade throughout. Swept, with the impact for each of the eight call sites chosen and tabulated at §1.4 rather than left to the implementer — the point of D10 is that the author of a guard answers "does this stop us taking the field?" while writing it.
- **§0.3 rule 4 and §8.1.1 / ArchUnit rule 10** — `RobotBase.isSimulation()` and `DriverStation.isFMSAttached()` named directly. Only `org.pumpkinlib.core.match..` may read `DriverStation`. Both corrected; the `MatchContext` version is also the *better* behaviour for a stall test, because it latches on the DS-connect edge and a DS that drops out mid-ramp must not silently re-arm.
- **`DESIGN.md` §16 item 2 (a)–(d)** — four corrections that document ordered on this one and has been tracking as outstanding. All four applied: `PumpkinDriveConfig.v01Competition()` with a *complaining* rather than silently-downgrading `competition()`; the four-argument driver-perspective `driverNudge`; §10's non-existent `TunerConstants.kFrontLeftLocation` replaced with the real `kFrontLeftXPos`/`kFrontLeftYPos` and a four-argument `CtreSwerveBackend`; and the `OdometryReport`-ships-in-M9 note made explicit.
- **§3.4.2's dropped "~10-15%"** — an uncited performance number inside an alert string, i.e. a folklore statistic in the one place a team is guaranteed to read it. Deleted, on the same grounds §8 deleted its own.

**What this pass deliberately did NOT do.** It did not touch §9.1's sign-convention prose or §9.2's four regression tests, beyond adding the `MatchImpact` argument to one alert in `end()` and one sentence to that alert's text. The review named that block *"the exact template `design/03`'s controllers must now be held to"* — two other documents are currently being rewritten against it, and editing the template while it is being copied is how a fix becomes a moving target.

### 15.3 Pass 3 — the contract-reconciliation pass, 2026-08-08 (revision 3.2)

Revision 3.1 deliberately left two blocking cross-document contracts unapplied and marked them in place. This pass applied them, plus one further blocking finding from the same review round.

| # | Section | Finding | Severity | Disposition |
|---|---|---|---|---|
| 14 | §0.2, §1.3, §2, §3.2, §3.3, §3.8 | **B4 — the vision↔drive contract.** `design/03` §2.2a stated a full change list on this document (its §2.7 contracts **C1**–**C5**) and it had never landed. `getGyroHeading()` `[SUPERSEDED-NAME]` — *"a contradiction inside a single line"* — still shipped; there was no `getRawGyro()`, no `m_gyroFieldOffset`, no `getGyroFieldHeading()`, no check 9, and none of the four D16 interfaces was declared anywhere in `org.pumpkinlib.drive`. | **blocking** | **APPLIED.** C1 at §3.2, C2 and C5 at §3.3.2/§3.3.3, C3 at §3.8, C4 at §1.3/§3.3.2. C6 and C7 were already applied at revision 3.1 and were re-verified rather than re-done. |
| 15 | §3.3.3 (new) | **The load-bearing half of B4, which two reviews had passed over.** MegaTag2 needs yaw in the blue-origin field frame; `getRawGyro()` is contractually the power-on frame; **no component owned the constant between them.** An offset with no owner is an offset that is written by whichever caller happens to feel responsible, or by none — and the failure is silent, because MegaTag2 treats the yaw as *known* and returns a confidently wrong translation that no residual or ambiguity metric can flag. | **blocking** | **APPLIED, and the ownership is now singular and explicit.** **§3.3.3 of this document owns the gyro→field offset** — the field, its exactly-two writers, the conversion in both directions, and the never-writes prohibition on the vision sink. `design/03` §2.2/§2.2a and `design/04` §1.2 reference §3.3.3 and no longer restate the arithmetic; `design/03` retains ownership of the *frame convention* (what the number means), which this document references and does not restate. Two documents, two disjoint responsibilities, one arithmetic site. |
| 16 | §1.1 and every log call site | **B10 (this document's half).** Every log call read `PumpkinLog.get().put(key, value)`, against a facade `design/04` does not ship: no `get()`, and `put(...)` explicitly *"does not exist and will not be added."* The package was wrong too (`org.pumpkinlib.log` vs `org.pumpkinlib.telemetry`). A design doc whose every logging snippet fails to compile teaches the implementer to distrust the rest of it. | **blocking** | **APPLIED at all 22 call sites.** Each is now `critical(...)` or `log(...)`, with the tier taken from `design/04` §3.2's key table where it names the key and chosen here where it does not. §1.1's block is retagged as a consumed-surface mirror that defers to `design/04`. |

**What this pass deliberately did NOT do.** Same exclusion as pass 2, for the same reason: §9.1's sign-convention prose and §9.2's four regression tests are untouched. It also did not renumber, reword or remove any of §15.1's or §15.2's rows — a superseded disposition is struck through and dated, never deleted, because the reason a line reads the way it does is the part that rots first.

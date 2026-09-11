# Rootstock `design/06` — Platform, Distribution, 2027 Survival, and Competition Day

**Status:** design complete, implementable. **Revised 2026-08-07 for maintainer decisions 1–4; revised 2026-08-08 against the independent expert review ([`REVIEW.md`](../REVIEW.md)).**
**Author:** Platform domain architect.
**Date:** 2026-08-06; revised 2026-08-07; revised 2026-08-08.
**Targets:** WPILib 2026.2.2 (Java 17) as the development baseline through M11 → WPILib 2027 / SystemCore (Java 25) executed **as milestone M12, during development, not after shipping**.
**License:** **BSD-3-Clause** (`LICENSE` at the repo root, `Copyright (c) 2026 Rootstock contributors`). Matching WPILib's own license means a team can vendor a single Rootstock file into their repo with no legal question to answer.

### Maintainer decisions applied in this revision

| # | Decision | What it did to this domain |
|---|---|---|
| **1** | **Everything ships in v0.1. No domain is deferred.** v0.1 lands after the 2027 kickoff — at solo pace, in 2030. | The old "v0.1 = P0–P3, P4–P6 follow" split in §16 is **deleted as a release plan** and survives only as internal build order, remapped onto the milestones M1…M24 in [`ROADMAP.md` §5](../ROADMAP.md). Everything in this document is v0.1 scope. |
| **2** | **Ship a library *and* a template repo, with `RootstockTemplate` as the primary front door.** | §4 is rewritten so the template is the advertised path and the raw vendordep install is the secondary path; §6 now fully specifies the template — contents, three variants, `.rootstock/template.lock`, `rootstock update --library`, `rootstock doctor --template`, `docs/UPDATING.md` — and states its recurring maintenance cost. |
| **3** | **AdvantageKit is a REQUIRED dependency**, not one backend among four. | The core artifact depends on AdvantageKit; `RootstockRobot extends LoggedRobot` (one class, not two); `rootstock-advantagekit` is folded into core and `rootstock-doglog` is deleted; the `TelemetrySink` SPI in `core.nt` is deleted; **the "zero vendor `requires`, installable on kickoff morning" property is LOST** (§3.3, §4.1, §4.2.1) and R18 is now an accepted risk with a written contingency (§4.8). |
| **4** | **BSD-3-Clause.** | §4.3, the published POM, `LICENSE`, and every vendored third-party file's attribution. |

**What did not change, and should not be read as having changed:** every technical contract in §7–§13 — identity resolution, the graded deploy gate, `MatchImpact`, the slice scheduler, the seven built-in monitors, the self-test DSL, `MatchContext` as the single `DriverStation` reader — is unaffected by all four decisions and is reproduced here unchanged.

### Review corrections applied in the 2026-08-08 revision

Each row is a finding from [`REVIEW.md`](../REVIEW.md) or from the working research notes, which stay out of the repository. Nothing is deleted to make a finding go away; where a number changed, the derivation is shown at the site.

| Finding | What changed here |
|---|---|
| **M21** — the withdrawn *"ten health checks"* claim resurfaced | §8.2's alert-site count is rewritten to **eleven built-in alert conditions from seven monitor types**, and the whole arithmetic is recomputed (§8.2). §16's P2 row names `LoopTimeMonitor` as **one of** the seven. §8.5 now names `BuiltinMonitorCountTest` explicitly and gives it a **third** assertion, `builtinConditionCount() == 11`, because §8.2's arithmetic and §8.5's driver-budget sentence both leaned on the number eleven and nothing asserted it. |
| **M16** — phase budgets exceed the milestones they land in | §16.1 is new: a milestone-by-milestone reconciliation with the arithmetic and the date consequence shown. `ROADMAP.md`'s milestone column is authoritative and is stated to be **optimistic** against this domain's bottom-up decomposition, quantified. |
| **M22** — the template's vendordep set is specified two ways | §6.2 now specifies the **grouped, prunable** vendordep set (three unconditional files + four vendor sets of two files each), §6.4 specifies what `rootstock init --vendors` deletes, and §6.5 specifies how a pruned file interacts with the SHA-256 lock manifest so pruning never reads as `MISSING` drift. |
| **M15** — R18 tier numbering inverted; the license gating it was verifiable in one fetch | §4.8's tiers are renumbered to execution order (**1 = contribute, 2 = fork, 3 = wait**) and the license `[UNVERIFIED]` is **closed**: AdvantageKit is BSD-3-Clause. §4.3.1, §15 item 11 and Appendix A follow. |
| **M14** — `AlertBudgetTest` threshold 3 vs 5 | §8.2.2 keeps **5** (the argued number) and adds the clause that distinguishes it from the **3**-row driver-mirror display cap, which is unchanged. |
| Minor — dead domain numbering | §1, §2, §3.2 and §4.6 now cite `design/01`…`design/06` by filename. There are no domains 07, 08 or 09. |
| Minor — `rootstock-core` coordinates survived D28 | §4.3 now names `dev.rootstock:rootstock`, and the publish script no longer implies one artifact per source set. |
| Minor — CLI drift `--vendor` / `--vendors`, undocumented `rootstock sync` | `--vendors` is canonical here (it takes a list) and `rootstock sync` is given a one-line definition in §3.3 and §11.2. Both are listed as contract requests against `README.md` and `DESIGN.md` §8. |
| Minor — decision-3 delta booked as −0.3 against DESIGN §9.4's −0.25 | §16's reconciliation books **−0.25**, with the double-counted D28 saving removed. Derivation shown. |
| **Conformance to `DESIGN.md` §5, found while applying the above** | D10: the facade class is `Alerts` (§8.2); D12: `Clock.dt()` is the one spelling of the timestep (§5.3 Part B); D14: `core.compat.RootstockField` is **deleted** — the one `RootstockField` is `org.rootstock.field` (§5.3 Parts A and B); D26: `org.rootstock.core.spi` is listed in the package tree (§3.4); D27: registration is **one** `RootstockRegistry.addAll(...)` call, and `HealthMonitor.watch` / `SelfTest.register(SelfTestable)` leave the public API (§8.3.1, §8.4, §13); D30: `ControlMap` gains modes and the mandatory `MANUAL` (§9.5). |
| ***(added 2026-08-08, second pass — `DESIGN.md` §16 item 5(e), a pre-M1 blocker)*** **D13a / D29 propagated into §13.** §13's `Robot.java` shipped `super();` with the comment *"No arguments"* — one of the three incompatible spellings D13a exists to collapse. It now passes an **immutable `LogConfig` value** (`LogConfig.defaults().withWpilogFolder(..).withCtreSignalLogger(..)`, `design/04` §2.2's field names) and calls `lifecycle().init()` at the constructor tail. **§13 gains a second, equal-weight snippet: the partial-adoption shape** — a team's own `LoggedRobot` driving `RootstockLifecycle` by hand with `adoptExistingLogger()` — because D29 requires that path to stay open and this document is where a reader looks for the end-to-end code. |
| ***(added 2026-08-08, second pass)*** **§8.1's `robotInit()` contradiction is RESOLVED, not merely flagged.** The blockquote offered two fixes; **the rename won** — D29's method is `init()` and D13a deletes `RootstockRobot`'s override — so ArchUnit **rule 5 is enforceable as written, with no scoping and no named exception**. The blockquote is kept as the record of the choice. |
| ***(added 2026-08-08, second pass)*** **Two stale present-tense claims corrected in place.** §1's *"the `MatchImpact` requirement … is currently unmet outside this document"* was true when `REVIEW.md` M5 wrote it and is not true now — re-grepped: `MatchImpact` occurs 20/14/22/9/17 times in `design/01`–`design/05` and there are **zero** `RootstockAlerts.*(...)` call statements. §5.3 Part B's `Clock.dt()` javadoc said the timestep is captured from **`TimedRobot.getPeriod()`**; under D13 the base class is `LoggedRobot`, which extends **`IterativeRobotBase`**, not `TimedRobot` (verified against `LoggedRobot.java` and the WPILib javadoc). |

**Verification done for this revision, not deferred.** Six vendor facts that were `[UNVERIFIED]` on 2026-08-07 were read from primary sources on 2026-08-08 and are now closed: AdvantageKit's license, its vendordep UUID, its JSON URL, its native-artifact set and `validPlatforms`, Phoenix 6's UUID, and photonlib's 2027-alpha platform triples. Every URL is in Appendix A. What remains `[UNVERIFIED]` is listed there too, and the list is shorter than it was.

---

## 1. Scope & Responsibilities

This domain owns everything that is true of *the robot program as a whole* rather than of any one mechanism. Concretely:

| # | Responsibility | Owned artifacts |
|---|---|---|
| 1 | Gradle project layout, package tree, artifact split | root `build.gradle`, `settings.gradle`, `buildSrc/` |
| 2 | Distribution: **the `RootstockTemplate` repo (the advertised front door)**, vendordep JSONs, Maven hosting, GradleRIO consumption, `vendor-json-repo` listing | `RootstockTemplate` repo, `vendordep/*.json`, `gradle/publish.gradle` |
| 3 | Versioning scheme, branch strategy, and the **2027 `edu.wpi.first` → `org.wpilib` migration, executed as M12 during development** | `org.rootstock.core.compat`, `gradle/wpi-rename-2027.properties`, ArchUnit rules |
| 4 | The template's three variants, drift tooling, and the `rootstock` CLI's project scaffolding and update commands | `RootstockTemplate` repo, `rootstock-cli` |
| 5 | Robot identity + per-robot config overlays, with a build-time deploy gate | `org.rootstock.core.identity`, `rootstockCheckDeploy` task |
| 6 | Alerts, health monitoring, and the automated pre-match self-test | `org.rootstock.core.alert`, `.health`, `.selftest` |
| 7 | Power / current-limit budgeting, pneumatics, LEDs, rumble | `org.rootstock.core.power`, `.pneumatics`, `.led`, `.hid` |
| 8 | FMS / DriverStation derivation (`MatchContext`) | `org.rootstock.core.match` |
| 9 | Deploy metadata (git provenance) and tuned-config backup/restore | `org.rootstock.core.config` |
| 10 | The NetworkTables namespace contract every other domain publishes into | `org.rootstock.core.nt` |
| 11 | The one-line robot-side entry point that wires all of the above | `org.rootstock.core.RootstockRobot` |

**Explicitly NOT in this domain:** mechanism abstraction ([`design/01`](01-core-mechanisms.md)), PID tuning UX ([`design/02`](02-tuning.md)), vision ([`design/03`](03-vision.md)), the telemetry facade ([`design/04`](04-telemetry-replay-viz.md)), swerve/differential drive and autonomous ([`design/05`](05-drivetrain-auto.md)). This domain defines the *seams* those documents plug into and nothing more.

> **Numbering note.** There are **six** domain documents and their numbers are their filenames: `01` core-mechanisms, `02` tuning, `03` vision, `04` telemetry/replay/viz, `05` drivetrain+auto, `06` platform (this one). An earlier draft of §1 and §2 used a pre-revision internal scheme in which mechanism was 02, drive 03, vision 04, telemetry 07 and tuning 08. **There is no `design/07`, `design/08` or `design/09`**, and every reference to one has been rewritten. Canonical list: [`DESIGN.md`](../DESIGN.md) header.

**No longer in this domain, as of decision 3:** *logging backends.* There is no `LogBackend` SPI and no `org.rootstock.core.nt.TelemetrySink` SPI, because there is nothing to choose between. AdvantageKit's `Logger` is the backend; [`design/04`](04-telemetry-replay-viz.md)'s `RootstockLog` writes to it directly. What this domain still owns is the `/Rootstock/` **namespace contract** (§4.6) and the pre-`Logger.start()` metadata hook (§11.1) — both of which survive the collapse unchanged.

> **Name-collision disambiguation, kept as history because it has already caused one misreading — and then stopped being true.** Two different types briefly shared the simple name `TelemetrySink`, and they were deleted by two different decisions, months apart:
>
> 1. **`org.rootstock.core.nt.TelemetrySink`** — revision 1's seven-method *pluggable-backend* interface (old §4.6), this domain's type. **Deleted by maintainer decision 3** (AdvantageKit required; one backend, nothing to plug).
> 2. **`org.rootstock.telemetry.TelemetrySink`** — `design/04`'s per-cycle *push target* handed to `TelemetrySource.sample`. Never a backend, and not this domain's to delete. **Also deleted, subsequently**, by [`DESIGN.md` §5.2 D9 **revision 5**](../DESIGN.md) (contract-reconciliation pass, review finding B10), which struck the parenthetical clause that had preserved it and resolved telemetry in favor of **push**: `TelemetrySource` is now a registration-and-declaration contract (`telemetryName()` + `describe(TelemetryDescriptor)`), `sample()` is gone, and every domain publishes its own schema block through the `RootstockLog` statics from its own `periodic()`. See [`design/04` §1.1.0](04-telemetry-replay-viz.md).
>
> **Net effect, and the sentence that governs today: no type named `TelemetrySink` exists anywhere in Rootstock.** Revisions of this document up to and including revision 4 said *"only the `core.nt` one is gone"* and pointed at D9 as preserving the telemetry-side one. **That is now wrong and is retained above only as the history of how the name was disambiguated.** Wherever this document says "the `TelemetrySink` SPI is deleted" it still means the `core.nt` one specifically — that attribution is unchanged, because the two were deleted for entirely different reasons and only the first one is decision 3's doing.

### 1.1 Design stance restated for this domain

* **Nothing here reimplements a working tool.** WPILib already ships `Alert`, `PowerDistribution`, `RobotController`, `LEDPattern`, `DataLogManager`, `WebServer`. WPILib ships *zero* checks that produce Alerts, *zero* arbitration for LEDs, and *zero* health registry. We supply content and coordination over WPILib's transport. (Dossier `web-ecosystem.json` → "WPILib Alert API as a fault-surfacing channel": *"The transport exists and dashboards already render it — but WPILib ships no actual alerts."*)
* **Zero mystery.** Every alert names the device, the measured value, the threshold, and the config key that set the threshold. Every self-test step names the expectation it failed.
* **Sim-first.** Every class in this domain runs headless with no HAL hardware. `PowerMonitor`, `MatchContext`, `SelfTest`, `LedController` all work under `./gradlew simulateJava` and in JUnit.
* **Escape hatch always.** Every wrapper exposes the raw object: `PowerMonitor.raw()` returns the `PowerDistribution`, `LedController.rawBuffer()` returns the `AddressableLEDBuffer`, `RootstockAlert.raw()` returns the WPILib `Alert`.
* **Replay-deterministic by construction, and now unconditionally.** Every rate gate in this domain counts robot loops, never wall-clock milliseconds (§8.3). Before decision 3 that guarantee was conditional — it held under AdvantageKit and was merely *best effort* under a raw-NT4 or DogLog backend. With AdvantageKit required, "Rootstock platform code replays byte-identically" is an unconditional claim and is gated by M1's exit test. That is the one thing decision 3 makes genuinely better, and it is worth stating plainly next to the things it makes worse.
* **The template is the front door; the library is the substance.** A team's first contact with this domain is `rootstock init` or GitHub's *Use this template* button, not a vendordep URL (§4.0, §6). The vendordep still exists and still works — it is how an in-season fix reaches a team as a dependency bump rather than a merge — but it is documented second.

---

## 2. Integration Points

What this domain **needs from** other Rootstock domains. These are hard contracts; each is a one-method interface so no domain has to depend on this one heavily.

```java
package org.rootstock.core.health;

/** Anything that can report faults. Implemented by mechanisms, drive, vision, LEDs. */
public interface HealthSource {
  /** Stable name, used as the NT subtable and the Alert group. */
  String healthName();

  /**
   * Called by {@link HealthMonitor} at most ONCE PER ROBOT LOOP, and only on the loop whose
   * cycle index selects this source (round-robin — see §8.3). NEVER called for every source
   * in the same loop, and NEVER gated on a wall clock. Must not block; must not allocate
   * per-fault; must complete in well under 1 ms.
   */
  void pollHealth(FaultCollector out);
}
```

```java
package org.rootstock.core.selftest;

/** Anything that can prove it works. Implemented by mechanisms, drive, vision. */
public interface SelfTestable {
  String selfTestName();
  SelfTestRoutine selfTestRoutine();   // see §8.4
}
```

| From | What Platform needs | Why |
|---|---|---|
| **[`design/01`](01-core-mechanisms.md) — mechanisms** | `Mechanism implements HealthSource, SelfTestable`; `Mechanism.declaredStatorLimit()` / `declaredSupplyLimit()` returning `Current`; `Mechanism.resolvedConfig()` returning a `StructSerializable` record; ~~`.expectAbsent(reason)` on mechanism config (§8.3.1)~~ **— the `.expectAbsent(reason)` clause is WITHDRAWN, 2026-08-08; see the labeled note below the table** | Health registry, self-test sequencing, `PowerBudget` summation, config snapshot, expected-absent demotion |
| **[`design/02`](02-tuning.md) — tuning** | Owns `Tunable` / `TuningRegistry`; must consult `org.rootstock.core.match.FmsPolicy.tunablesLocked()` on **every** read, and must register `TuningRegistry.periodic()` as a `SliceScheduler` slice rather than owning its own rate gate (§8.3.1) | DogLog's semantic — tunables are inert when FMS-attached — copied verbatim. *(We copy the semantic; we no longer ship a DogLog backend. The idea was always the valuable part.)* A second rate gate reconstructs the spike §8.3 removed. |
| **[`design/03`](03-vision.md) — vision** | `VisionSystem implements HealthSource` with per-camera connectivity; `SelfTestable` that verifies each camera sees ≥1 tag | "Camera dropped off" must be an Alert, not a silent pose freeze |
| **[`design/04`](04-telemetry-replay-viz.md) — telemetry** | Publishes through `RootstockLog` **directly onto AdvantageKit's `Logger`** (decision 3 — there is no backend SPI to implement); calls `RootstockLifecycle`'s `metadata(String,String)` hook **before** `Logger.start()`; owns the transport and schema under the `/Rootstock/` roots this domain reserves, including the `/Rootstock/Driver` mirror's *plumbing* (D22) | AdvantageKit's `recordMetadata` is write-once pre-`start()`, so `BuildConstants` must be injected there. The match-aware WPILOG rename now lives in core rather than in a `rootstock-advantagekit` adapter, because the adapter no longer exists (§10 item 2) |
| **[`design/05`](05-drivetrain-auto.md) — drive + auto** | `RootstockDrive implements HealthSource, SelfTestable`; a `BooleanSupplier` slot for alliance flipping; auto reads `MatchContext.isRed()` and `MatchContext.allianceKnown()` and publishes its chooser under `/Rootstock/Auto/`; owns `org.rootstock.field.RootstockField` (D14) | We supply `MatchContext::isRed` — this single substitution kills the #1 competition-day auto failure. Auto must refuse to run if alliance is unknown. `RootstockField` is *not* in `core.compat`; D14 gave it to drive/auto and this document no longer declares a second one. |
| **All six** | Publish only under `/Rootstock/<Root>/...` (§4.6). **Every alert call site passes `MatchImpact` explicitly** — `Alerts.error/warning(group, text, MatchImpact)`, no default, no two-argument overload (D10, §8.2) | One namespace so the AdvantageScope/Elastic layouts we ship actually work. And an alert that has not answered "does this stop the robot playing?" is exactly the alert that buries the driver (§8.2). |

> **WITHDRAWN, 2026-08-08 — `.expectAbsent(reason)` on `design/01`'s mechanism config. Recorded as a labeled note, not deleted, because a requirement that was carried for three revisions and never adopted is a fact about this design worth keeping.**
>
> **What was asked.** The `design/01` row above required a builder method `.expectAbsent(reason)` on **mechanism config**, so a team could declare "this device is knowingly not installed" at the config site and have this document's health registry demote its faults to INFO + PIT_ONLY.
>
> **What happened.** It was never adopted. `grep -c expectAbsent design/01-core-mechanisms.md design/02-tuning.md` returns **0** and **0** — **still zero after three revisions of both documents**, tracked the whole time as `DESIGN.md` §16 item 3. **The row carried its own standing instruction for exactly this case: a cross-document requirement that goes three revisions with no adopter is withdrawn rather than re-asserted.** This note executes that instruction. It is not an admission that the idea was bad; it is an admission that **nothing was ever going to compile against it**, and a requirement no document implements is a requirement that makes the design *look* more integrated than it is — which is the specific dishonesty this document's contract table exists to prevent.
>
> **What is NOT withdrawn, so nothing is lost.** `Health.expectAbsent(String healthName, String reason)` — **this document's own static, §8.3.1** — stays exactly as declared, and so does the boot-dump block and `/Rootstock/Health/ExpectedAbsent`. The capability survives in full; a team calls the static directly with the health name. **All that is withdrawn is the ergonomic sugar on `design/01`'s config builder**, i.e. the one part of it that lived in a document that never wrote it. §8.3.1's javadoc *"Set from mechanism config's `.expectAbsent()` (design/01)"* is therefore **now the wrong provenance sentence** and is corrected at the declaration site.
>
> **If a future revision wants it back**, it is a `design/01` config-builder addition plus one delegating call into the static above — a small, well-understood edit, and one that should be made *because a team asked for it*, not because a table row survived by inertia.

**The `MatchImpact` requirement is a hard contract, it *was* unmet outside this document, and as of 2026-08-08 it is met.** The history is worth keeping because it is the reason the contract is stated this loudly: `REVIEW.md` M5 grepped all five other domain docs and found **zero** occurrences of `MatchImpact`, while `design/03` and `design/05` used two-argument `RootstockAlerts.error/warning(group, text)` facades throughout — facades that do not exist. The canonical surface is `Alerts` (§8.2) and the third argument is required. **Re-run by hand 2026-08-08, and this replaces the old present-tense claim rather than deleting it:** `MatchImpact` now occurs 20× in `design/01`, 14× in `design/02`, 22× in `design/03`, 9× in `design/04` and 17× in `design/05`, and `grep -rn 'RootstockAlerts\.\(error\|warning\|info\)([^)]*)[[:space:]]*;' design/` returns **zero** call statements. `DESIGN.md` §16 item 7 is the tracking row. The lesson stands unchanged: *"already reflected"* is exactly the claim that rots, which is why the number above is a grep result with a date on it and not an adjective.

What this domain **gives** every other document: `Alerts` / `RootstockAlert` / `AlertRegistry` / `Severity` / `MatchImpact`, `RobotIdentity`, `MatchContext`, `FmsPolicy`, `DeployInfo`, `RootstockDashboard`, `SliceScheduler`, `RootstockTracer`, `RootstockRegistry`, the `/Rootstock/` namespace, and `RootstockRobot` / `RootstockLifecycle`'s lifecycle callbacks.

---

## 3. Project & Package Structure

### 3.1 Root package: `org.rootstock`

Decided. Rationale:

* Reverse-DNS of `rootstock.dev`, which we will own and which will host the docs, the vendordep JSONs, and the Maven repo. A GitHub-org-derived package (`io.github.<user>`) hard-codes a personal account into every import for a decade.
* Matches WPILib 2027's own move to a non-institutional root (`org.wpilib`), so `import org.rootstock.*` sits visually beside `import org.wpilib.*` and reads as a peer, not a fork.
* Maven group `dev.rootstock` (Central requires a verifiable domain; `dev.rootstock` is verified by DNS TXT on `rootstock.dev`). Group ≠ package root is normal and harmless.

### 3.2 Gradle multi-project layout

Source-set boundaries are unchanged from the original design and are still enforced by ArchUnit at the *package* level. What changed (D28, and decision 3 on top of it) is how many of these source sets become **published artifacts**: one, not eleven. See §3.3.

```
Rootstock/                                   (repo root — the LIBRARY. Not a robot project.)
├── LICENSE                                   # BSD-3-Clause, (c) 2026 Rootstock contributors
├── settings.gradle
├── build.gradle                              # version, java toolchains, spotless, archunit, publishing
├── gradle.properties                          # rootstockVersion, wpilibVersion2026, akitVersion
├── buildSrc/
│   └── src/main/groovy/
│       ├── rootstock.java-conventions.gradle    # Java 17 source level, -Werror, javadoc
│       ├── rootstock.publish-conventions.gradle # maven-publish → Pages repo + Central
│       └── rootstock.wpi-variant.gradle         # THE 2027 SEAM. NOT APPLIED UNTIL M12 (see §5.3)
│
├── rootstock-pure/                # ZERO WPILib imports. Java 17. 100% unit-testable, no HAL.
├── rootstock-core/                # THIS DOMAIN (design/06). WPILib + AdvantageKit. No vendor deps.
├── rootstock-mechanism/           # design/01
├── rootstock-tuning/              # design/02
├── rootstock-vision/              # design/03  (WPILib-only pose math + SPI; no PhotonVision dep)
├── rootstock-telemetry/           # design/04  (RootstockLog tiered facade -> AdvantageKit Logger)
├── rootstock-drive/               # design/05
├── rootstock-auto/                # design/05
├── rootstock-sim/                 # WPILib physics sim wiring; desktop+athena
├── rootstock-testkit/             # JUnit 5 harness: HAL init, SimHooks stepping, scheduler ticking
│                                  #   ^ all ten source sets publish as ONE artifact: `rootstock`
│
├── adapters/                      # each of these IS its own artifact and its own vendordep JSON
│   ├── rootstock-phoenix6/        # requires Phoenix6 vendordep
│   ├── rootstock-revlib/          # requires REVLib vendordep
│   ├── rootstock-photonvision/    # requires photonlib (also hosts SimulatedLimelight)
│   ├── rootstock-pathplanner/     # requires PathplannerLib
│   ├── rootstock-choreo/          # requires ChoreoLib
│   └── rootstock-maplesim/        # requires maple-sim (OPTIONAL, beta upstream)
│                                  # DELETED by decision 3: rootstock-advantagekit (folded into core),
│                                  #   rootstock-doglog (no longer a supported backend).
│                                  # NOT adapters: limelight (vendored helpers) and elastic
│                                  #   (vendored ElasticLib, §9.4) live inside rootstock-core.
│
├── gradle-plugin/               # `dev.rootstock.gradle` — rootstockCheckDeploy, rootstockAssets,
│                                #   generate2027Sources (M12 only), the annotationProcessor wiring
├── tools/
│   └── rootstock-cli/             # desktop-only. NOT deployed. Owns the template flows in §6.
│                                  #   init | update | doctor | gen | stats | pull-config | sync
│
├── vendordep/                   # the published JSON files (§4.2)
├── template/                    # THE TEMPLATE SOURCE OF TRUTH. `RootstockTemplate` is GENERATED
│   ├── common/                  #   from here on every release (§6.7) — it is not hand-edited.
│   ├── swerve/
│   ├── differential/
│   └── mechanism-only/
├── layouts/                     # Elastic + AdvantageScope layout JSONs we ship (§9.4)
└── docs/                        # docusaurus; every snippet extracted from a compiled test (§4.9)
```

Separate repos (deliberately not subprojects):

* **`RootstockTemplate` — the GitHub template robot project, and the advertised front door (§6).** It is a *generated, published artifact of this repo*, not an independently maintained project: `template/` above is the source of truth and CI regenerates and force-pushes `RootstockTemplate` on every library release. That is what keeps the template from drifting away from the library it pins, and it is a recurring cost we state rather than discover (§6.7).
* `rootstock-scout` — TBA/Statbotics desktop CLI (§12). Different release cadence, different dependency stack, must never enter a robot jar. Out of scope permanently, not deferred.

### 3.3 One jar or several? — **One core artifact requiring WPILib + AdvantageKit, six adapters, one Gradle plugin, one CLI.**

Decision: **one published core Maven artifact (`rootstock`), six adapter artifacts each behind its own vendordep JSON, plus `rootstock-gradle` (Plugin Portal) and `rootstock-cli` (desktop only, never deployed).** This is the shape recorded in [`DESIGN.md` §8](../DESIGN.md); it supersedes revision 1's eleven-artifact split, and decision 3 removes two more entries from what is left.

**Why one core artifact and not eleven.** Revision 1 published eleven core jars behind a single vendordep, so no consumer could ever install a subset — zero consumer-visible benefit, against eleven POMs, eleven version bumps, eleven inter-artifact constraints and eleven chances for a botched publish on every release, several of which go out during build season. The **package and source-set boundaries stay exactly as designed** (§3.2) and all twelve ArchUnit rules keep enforcing them at the *package* level, so the split remains available at zero cost if a consumer ever actually asks for it. Precedent, stated honestly: WPILib itself ships as one vendordep-visible unit and splits internally.

**What survives from revision 1's justification, and what does not:**

1. ~~**Kickoff-week vendor lag is the hard constraint.**~~ **This argument is now on the other side of the ledger, and it is the largest single cost of decision 3.** The underlying fact has not changed and is not disputed: in Jan 2026 AdvantageKit's swerve templates shipped *weeks late* because they depended on vendors who had not published yet (`web-ecosystem.json` painPoint #1: jonahb55 — *"[is] not yet released since they depend on libraries from vendors who have not published 2026 versions"*). Revision 1 used that fact to justify a WPILib-only core, and advertised the result: *install Rootstock on kickoff morning before any vendor has published.* **That property is gone.** `rootstock` has a compile dependency on AdvantageKit, so Rootstock cannot be installed until AdvantageKit has published for the season — and AdvantageKit is exactly the project whose own 2026 release was gated on other vendors. We are now downstream of the delay we used to point at. Every claim in the docs that depends on the lost property is rewritten in §4.1 and §4.2.1; the risk it creates is R18, accepted with a written contingency in §4.8.
2. **`requires[]` is an install-failure multiplier — still true, and it still governs the adapters.** YAGSL's live vendordep declares five hard `requires` (REVLib, Phoenix6, Phoenix5, Studica, ThriftyLib) — a REV-only team is forced to install Phoenix 5. Splitting the adapter vendordeps means a REV-only team installs `Rootstock.json` + `Rootstock-REVLib.json` and never sees CTRE. What changed is only the *floor*: the floor used to be zero vendor `requires` and is now exactly one (AdvantageKit). No Rootstock adapter does anything like YAGSL's five.
3. **Multiple source sets ≠ multiple installs.** One vendordep JSON may list many `javaDependencies`; YAGSL ships `YAGSL-java` *and* `org.dyn4j:dyn4j` from one JSON. With one core artifact this is moot for core and still true for the shaded/vendored pieces (ElasticLib, the Limelight helpers) that ride inside it.
4. **Jar size is irrelevant; classpath poisoning is not.** A pure-Java jar of ~1 MB is nothing. But a class that `import com.ctre.phoenix6.hardware.TalonFX` and is *never loaded* costs nothing at runtime — `NoClassDefFoundError` fires at class-load, not at classpath-build. That is exactly why adapters must be separate artifacts *and* why we never reference an adapter class from core. Core discovers adapters via `ServiceLoader`, never `Class.forName` string literals. **`org.littletonrobotics.junction` is removed from ArchUnit rule 1's ban list entirely** — it is a required dependency, not an adapter, so an *artifact* fence around it is meaningless and would leave core in permanent violation. What replaces it is **rule 1c**, a four-package allowlist: `org.rootstock.telemetry`, `org.rootstock.core`, `org.rootstock.tuning` and `org.rootstock.viz`, and nothing else. That is the authoritative wording in [`DESIGN.md` §8](../DESIGN.md) and [`design/04` §2.2](04-telemetry-replay-viz.md), and it is **weaker than the boundary it replaces** — a package allowlist inside one jar, not a compile-classpath fence.

Published artifacts (`dev.rootstock:<artifactId>:<version>`):

| artifactId | Depends on | Vendordep JSON |
|---|---|---|
| **`rootstock`** | **WPILib 2026.2.2 + AdvantageKit 26.0.2**; contains pure, core, mechanism, drive, vision, auto, telemetry, tuning, sim, testkit, the vendored ElasticLib (§9.4) and the vendored Limelight helpers | `Rootstock.json` — `requires`: **`WPILibNewCommands.json` *and* `AdvantageKit.json`** |
| `rootstock-phoenix6` | `rootstock`, Phoenix 6 26.x | `Rootstock-Phoenix6.json` |
| `rootstock-revlib` | `rootstock`, REVLib 2026 | `Rootstock-REVLib.json` |
| `rootstock-photonvision` | `rootstock`, photonlib 2026.3.4 (also hosts `SimulatedLimelight`, which needs `PhotonCameraSim`) | `Rootstock-PhotonVision.json` |
| `rootstock-pathplanner` | `rootstock`, PathplannerLib 2026.1.2 | `Rootstock-PathPlanner.json` |
| `rootstock-choreo` | `rootstock`, ChoreoLib 2026.0.3 | `Rootstock-Choreo.json` |
| `rootstock-maplesim` | `rootstock`, maple-sim (beta) | `Rootstock-MapleSim.json` |
| **`rootstock-gradle`** | — | not a vendordep; Gradle Plugin Portal id `dev.rootstock.gradle`. Supplies `rootstockCheckDeploy`, `rootstockAssets`, the `annotationProcessor` wiring for the M20 replay lint, and (M12 only) `generate2027Sources` |
| **`rootstock-cli`** | — | not a vendordep; desktop only, never deployed. `rootstock init / update / doctor / gen / stats / pull-config / sync` |

**`rootstock sync`, defined once here because no other document defines it.** `rootstock sync` copies the robot's `rootstock/target-state.json` — the `everFMSAttached` flag `MatchContext` persists (§7.4, §11.2) — down to `build/rootstock/` over the same port-5800 web server Elastic uses, so the build-time deploy gate can read it. It is **best-effort with a 500 ms timeout, and an unreachable robot is "no information", never a failure.** `DESIGN.md` §8's CLI row enumerates eight subcommands and omits it; that row needs a ninth entry, and it is listed as a contract request. If the maintainer would rather have eight subcommands, the alternative is to fold this into `rootstock pull-config --state`; what is not acceptable is a subcommand the deploy gate depends on that appears in exactly one document.

**Deleted by decision 3, and the deletions are not reversible without reversing the decision:**

| Artifact | Fate |
|---|---|
| `rootstock-advantagekit` | **Folded into `rootstock`.** Everything it held — the `LoggedRobot` base, the FMS-attach WPILOG rename (§10 item 2), the `LoggableInputs` plumbing — is now core code, because AdvantageKit is now a core dependency. |
| `rootstock-doglog` | **Deleted.** There is no DogLog backend and there will not be one. A team on DogLog cannot adopt Rootstock without switching loggers; that is stated in the adoption matrix in [`ROADMAP.md` §4.2](../ROADMAP.md), not softened. |
| `Rootstock-AdvantageKit.json` | **Deleted.** AdvantageKit is now a `requires` entry on `Rootstock.json`, not an optional adapter JSON. |
| The `TelemetrySink` SPI in `org.rootstock.core.nt` (old §4.6) | **Deleted.** Nothing implements it, because there is one backend. |

### 3.4 Java package tree (this domain only)

```
org.rootstock.core                 Entry point + lifecycle: RootstockRobot (extends LoggedRobot),
                                      RootstockLifecycle (PUBLIC, D29), RootstockRegistry (the ONE
                                      registration call, D27), Rootstock (version, init), RootstockException.
org.rootstock.core.spi             D26, and every arrow points in here: LifecycleHook, VisionSimHook,
                                      MechanismGeometrySink, MechanismGeometry, SimMotorHandle.
                                      ServiceLoader is used ONLY for out-of-jar adapters (D26 as amended);
                                      in-jar hooks are an explicit priority-ordered list built in code.
org.rootstock.core.compat          THE 2027 SEAM. The only package allowed to touch year-volatile WPILib API.
                                      Clock, Platform, Gamepads, MathX, PoseX — FIVE classes. See §5.
                                      (RootstockField is NOT here: D14 gives the one RootstockField to
                                       org.rootstock.field, owned by design/05.)
org.rootstock.core.nt              NT namespace constants + Rootstock*Publisher helpers.
                                      (core.nt.TelemetrySink SPI DELETED by decision 3 — one backend,
                                       nothing to plug. It was a DIFFERENT type from
                                       org.rootstock.telemetry.TelemetrySink, which D9 revision 4 had
                                       preserved — but D9 revision 5 struck that clause and deleted
                                       that one too, so NO TelemetrySink exists anywhere now. See §1.)
org.rootstock.core.identity        RobotId, RobotIdentity, IdentityResolver, Overlay<T>.
org.rootstock.core.match           MatchContext, MatchInfo, FmsPolicy, MatchPhase, MatchSchedule.
org.rootstock.core.alert           Alerts (THE facade, D10), RootstockAlert (the handle), AlertRegistry,
                                      Severity, MatchImpact, AlertBridge.
org.rootstock.core.health          HealthSource, Fault, FaultCollector, HealthMonitor, RobotHealth, Checks,
                                      SliceScheduler (the ONE round-robin, §8.3.1).
org.rootstock.core.health.builtin  CanBusMonitor, BatteryMonitor, RailMonitor, BrownoutMonitor,
                                      DeployMonitor, LoopTimeMonitor, DsMonitor.   <- SEVEN types (§8.5)
org.rootstock.core.diag            RootstockTracer (per-section loop-time budgets, §8.3).
org.rootstock.core.selftest        SelfTestable, SelfTestRoutine, SelfTestStep, SelfTestResult, SelfTest, Expect.
org.rootstock.core.power           PowerMonitor, PowerChannel, PowerBudget, EnergyTracker.
org.rootstock.core.pneumatics      Pneumatic, PneumaticsHealth, CompressorPolicy.
org.rootstock.core.led             LedController, LedBackend, LedState, LedRegion, LedBackends.
org.rootstock.core.hid             Rumble, RumblePattern, RumbleScheduler, ControlMap, ControlBinding.
org.rootstock.core.config          DeployInfo, ConfigRegistry, ConfigSnapshot, PersistentStore, CanIdRegistry.
org.rootstock.core.dashboard       RootstockDashboard, Notify, DashboardTab.
org.rootstock.core.util            EdgeDetector, Debouncer2, Cached<T>, Rate, PeriodicRunner.
```

`CanIdRegistry` lives here rather than in `design/01` because it is a **robot-wide** uniqueness check, scanned once globally inside `RootstockRegistry.addAll(...)` and never as a side effect of a record constructor — `9143-2025-A-Updated/src/main/java/frc/robot/Constants.java` documents a CAN ID of 64 that crashed robot code on boot because Phoenix IDs stop at 62, and that must be a **collected, named `ConfigError` that still lets the robot boot into `SAFE_MODE`**, not a stack trace. `design/01` §1.3 names it as a requirement on this domain; this is where it is met.

`org.rootstock.pure.*` (separate artifact, zero WPILib): `Gearing`, `Interval`, `LookupTable`, `Rolling` (sag/statistics windows), `Fixed` (fixed-point helpers). Everything in `rootstock-pure` is unit-testable with plain JUnit and no HAL.

---

## 4. Distribution

### 4.0 Two paths, and which one is advertised (decision 2)

```
                     ADVERTISED PATH                          SECONDARY PATH
   ┌──────────────────────────────────────┐        ┌──────────────────────────────────┐
   │  GitHub "Use this template"          │        │  WPILib: Manage Vendor Libraries │
   │        — or —                        │        │   → Install new libraries        │
   │  rootstock init --template swerve      │        │   → paste Rootstock.json URL    │
   └──────────────┬───────────────────────┘        └──────────────┬───────────────────┘
                  │                                               │
       a WORKING robot project:                       a WORKING dependency in an
       build.gradle, vendordeps pinned as a           EXISTING robot project. No
       coherent set, gversion, rootstockCheckDeploy,    Robot.java, no gversion, no
       Robot.java, RobotContainer.java, an            rootstockCheckDeploy, no Elastic
       elevator, an intake, a Superstructure,         layout, no CI. The team wires
       Elastic layout, CI, ROBOT.md, CONTROLS.md      those up by hand from docs.
                  │                                               │
                  └────────────────► pins ◄──────────────────────┘
                              dev.rootstock:rootstock:<version>
                              (+ adapters, + AdvantageKit, + WPILibNewCommands)
```

**The template is the front door. The library is the substance.** Both ship; only one is on the first screen of the README.

**Why both, stated as a trade rather than a slogan.**

* A **template alone** cannot be upgraded mid-season. AdvantageKit's own docs say *"Manually updating projects to 2026 is not recommended due to the risk of subtle breaking changes"* — a template-only library asks a team in week 4 of build season to merge upstream commits into a repo they have already rewritten, and they will not do it. A versioned library artifact turns the same fix into `rootstock update --library 2026.0.3`, which is one command and sixty seconds.
* A **library alone** cannot deliver the onboarding experience this design promises. Everything a vendordep structurally *cannot* install — the `gversion` plugin, `rootstockCheckDeploy`, the `annotationProcessor` line the M20 replay lint needs, the Elastic layout, the CI workflow, the one-click strict-deploy task — is exactly the material in §7.4, §9.4 and §11.1 that makes competition day work. Revision 1 answered this with "an `instructions` URL pointing at a one-screen *add these 8 lines* page," which is a documented onboarding failure mode dressed as a solution.
* Both together means **the thing a team starts from is complete, and the thing that fixes their robot in week 4 is a dependency bump.** That is the whole argument, and §6 is the specification.

**The honest cost of running both** is in §6.7: the template must be regenerated and CI-compiled against **every** library release, forever, at roughly 0.1 pw per release and ~0.5 pw per competition season. That cost is real, it recurs, and it is counted in the carrying cost in [`ROADMAP.md` §7](../ROADMAP.md).

### 4.1 Pure Java, zero JNI *of our own* — and the caveat decision 3 introduces

Rootstock ships **no native code**. `jniDependencies: []`, `cppDependencies: []`. Consequences and why this is right:

* Going native means adopting `wpilibsuite/vendor-template`'s three-library structure (Java lib / C-symbols driver lib with an explicit `symbols.txt` / native C++ lib), cross-compiling for 4–5 platform triples, and re-cutting binaries every time the platform set changes — **which it just did**: `linuxathena` → `linuxsystemcore` for 2027. **Verified 2026-08-08:** photonlib's 2027-alpha vendordep (`photonlib.json`, `wpilibYear: "2027_alpha5"`) lists `validPlatforms` = `windowsx86-64, linuxsystemcore, linuxx86-64, osxuniversal` and **`linuxathena` does not appear anywhere in the file.** The rename is not a rumor; it is already shipped in a live vendordep. Source in Appendix A.
* Every proven zero-infrastructure FRC distribution is pure Java: DogLog, YAGSL, ThriftyLib.
* Nothing this library does needs native code. Physics sim, NT publishing, and health polling are all pure JVM work.

**The caveat, which decision 3 creates and which we must not bury.** *We* ship no native code, but as of decision 3 we **require a vendordep that does**. AdvantageKit ships a native conduit alongside its Java jar. The consequences are asymmetric and worth separating:

* **The maintenance burden is still not ours.** We do not cross-compile anything, do not maintain a `symbols.txt`, and do not re-cut binaries when the platform set changes. That half of the argument survives intact.
* **The availability risk now is ours.** When `linuxathena` becomes `linuxsystemcore` for 2027, *someone* has to re-cut AdvantageKit's native artifacts for the new platform triple, and that someone is not us. If that work is late, Rootstock is late, and there is no version of this design in which we can route around it. This is the concrete mechanism by which R18 (§4.8) actually fires — not an abstract "the upstream might not port," but a specific native build for a specific new platform triple on a specific deadline.
* **The exact native surface — VERIFIED 2026-08-08, and this closes the `[UNVERIFIED]` that stood here.** `AdvantageKit.json` v26.0.2 declares:

  ```jsonc
  "javaDependencies": [ { "groupId": "org.littletonrobotics.akit",
                          "artifactId": "akit-java",     "version": "26.0.2" } ],
  "jniDependencies":  [ { "groupId": "org.littletonrobotics.akit",
                          "artifactId": "akit-wpilibio", "version": "26.0.2",
                          "isJar": false, "skipInvalidPlatforms": false,
                          "validPlatforms": [ "linuxathena", "linuxx86-64", "linuxarm64",
                                              "osxuniversal", "windowsx86-64" ] } ],
  "cppDependencies":  []
  ```

  **Exactly one native artifact** (`akit-wpilibio`), no C++ dependencies, and five platform triples. Source in Appendix A.

* **What that means for the open question "can we keep developing in sim through an upstream native-build delay?" — answered, and the answer is *partly*.** `akit-wpilibio` is **not** athena-only: the three desktop triples (`linuxx86-64`, `osxuniversal`, `windowsx86-64`) are in the same `validPlatforms` list, so **a desktop/simulation build consumes a native artifact too** and there is no pure-Java simulation path. But the three desktop triples are **platform-stable** — SystemCore changes the *robot* triple, not the developer's laptop — so the 2027 delta is one new triple (`linuxsystemcore`) replacing one old one (`linuxathena`), against three that already exist and are already built. **The practical consequence, which `rootstock doctor` should print:** if AdvantageKit publishes a 2027 line at all, desktop simulation is very likely to work on day one and only robot deploy is gated on the new triple; if AdvantageKit publishes *nothing* for 2027, nothing works and R18 has fired. That is a materially smaller blast radius than §4.8 assumed on 2026-08-07, and it is the honest reading of the data rather than the optimistic one.
  **`skipInvalidPlatforms` is `false`**, which is worth naming: GradleRIO will not silently skip a platform it cannot resolve. That is the behavior we want (a missing artifact is a named build failure, not a robot that boots without logging), and it is also why the 2027 triple is a hard gate rather than a degradation.
  **Residual `[UNVERIFIED]`:** whether AdvantageKit's *2027* vendordep keeps this exact shape. Re-read it at the 2027 beta; it is the same fetch that arms R18's trigger (§4.8).

**C++ support is out of scope, permanently.** We say so in the README. ~90% of FRC teams use Java; a C++ port doubles the maintenance surface for <10% reach and would make the 2027 `frc::` → `wpi::` migration twice as expensive.

### 4.2 The vendordep JSON — exact schema and skeleton

Schema authority is the validator, not prose docs: `https://raw.githubusercontent.com/wpilibsuite/vendor-json-repo/main/check.py` (verified 2026-08-06).

**Required top-level keys:** `fileName`, `name`, `version`, `uuid`, `mavenUrls`, `jsonUrl`, `javaDependencies`, `jniDependencies`, `cppDependencies`.
**Optional:** `frcYear`, `wpilibYear`, `requires`, `conflictsWith`.

**Year rule (verified from `check.py`, and this corrects a common misreading):**
* Years **2026 and earlier, plus `2027_alpha1`** → use `frcYear`, and the file must **not** contain `wpilibYear`.
* Years **2027 onward** (excluding `2027_alpha1`) → use `wpilibYear`, and the file must **not** contain `frcYear`.
* Exactly one must be present. Both, or neither, fails validation.

Cross-checked against a live 2026 vendordep: PathplannerLib 2026.1.2 carries `"frcYear": "2026"` and no `wpilibYear` (verified by fetching `https://3015rangerrobotics.github.io/pathplannerlib/PathplannerLib.json`, 2026-08-06). DogLog's 2027-alpha carries `"wpilibYear": "2027_alpha5"`.

Sub-object schemas (verified from `check.py`):

* `javaDependencies[]` → `groupId`, `artifactId`, `version` (all required).
* `jniDependencies[]` → the above + `isJar` (bool, required), `validPlatforms` (string[], required), `skipInvalidPlatforms` (bool, opt), `simMode` (string, opt).
* `cppDependencies[]` → the above + `libName` (required), `configuration`, `headerClassifier`, `sourcesClassifier`, `binaryPlatforms`, `skipInvalidPlatforms`, `sharedLibrary`, `simMode` (all opt).
* `requires[]` → `uuid`, `errorMessage`, `offlineFileName`, `onlineUrl` — **all four required**.
* `conflictsWith[]` → `uuid`, `errorMessage`, `offlineFileName` — all three required (no `onlineUrl`).

#### 4.2.1 `Rootstock.json` — the core vendordep

The shape below is the **2026-line development skeleton and the year in it is illustrative**, not a commitment. It is what the internal snapshots (§5.1) are cut against from M8 onward. The first *published* `Rootstock.json` will carry whatever WPILib year is current at M24 — at solo pace, `2030` — and will therefore use `wpilibYear`, not `frcYear`; §5.1 writes that year as `20NN` everywhere it is not knowable today. **No `2026.1.0` tagged release will ever exist** (§5.1: the 2026 line is only ever `2026.0.0-SNAPSHOT-M<n>`), so read `2026.1.0` below as *"whatever `20NN.1.0` turns out to be"*. The structure is identical either way.

```json
{
  "fileName": "Rootstock.json",
  "name": "Rootstock",
  "version": "2026.1.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "frcYear": "2026",
  "jsonUrl": "https://rootstock.dev/vendordep/2026/Rootstock.json",
  "mavenUrls": [
    "https://rootstock.dev/repo",
    "https://repo1.maven.org/maven2"
  ],
  "javaDependencies": [
    { "groupId": "dev.rootstock", "artifactId": "rootstock", "version": "2026.1.0" }
  ],
  "jniDependencies": [],
  "cppDependencies": [],
  "requires": [
    {
      "uuid": "<WPILIBNEWCOMMANDS_UUID>",
      "errorMessage": "Rootstock needs the WPILib New Commands vendordep. It ships offline with the WPILib installer: WPILib: Manage Vendor Libraries -> Install new libraries (offline) -> WPILib-New-Commands.",
      "offlineFileName": "WPILibNewCommands.json",
      "onlineUrl": "<WPILIBNEWCOMMANDS_JSON_URL>"
    },
    {
      "uuid": "d820cc26-74e3-11ec-90d6-0242ac120003",
      "errorMessage": "Rootstock requires AdvantageKit 26.0.2 or newer. Rootstock is built on AdvantageKit's Logger and cannot run without it. Install it from https://docs.advantagekit.org/getting-started/installation/ then re-run Build Robot Code.",
      "offlineFileName": "AdvantageKit.json",
      "onlineUrl": "https://github.com/Mechanical-Advantage/AdvantageKit/releases/latest/download/AdvantageKit.json"
    }
  ]
}
```

**Every `<...>` placeholder is copied verbatim from the upstream project's own published JSON at release-cut time and is never invented** — same rule as §4.2.2, same CI enforcement by `verifyRequiresUuids`.

**AdvantageKit's entry is no longer a placeholder — it was read from the primary source on 2026-08-08** and is reproduced above as fetched. `AdvantageKit.json` v26.0.2 declares `"uuid": "d820cc26-74e3-11ec-90d6-0242ac120003"`, `"frcYear": "2026"`, and `"jsonUrl": "https://github.com/Mechanical-Advantage/AdvantageKit/releases/latest/download/AdvantageKit.json"` — note that upstream's own `jsonUrl` points at `releases/latest/download/`, so it is stable across versions and is the correct `onlineUrl` for our `requires` block. Its single Maven URL is `https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release/`, which is WPILib's own artifactory rather than a personal host — a small but real point in favor of the succession argument in §4.3. Source in Appendix A.

**`<WPILIBNEWCOMMANDS_UUID>` deliberately stays a placeholder.** A secondary source reports it as `111e20f7-815e-48f8-9dd6-e675ce75b266`, and that is very likely right, but **[UNVERIFIED]** — the primary file could not be fetched, and this document's own rule is that a `requires` UUID is read off the upstream file, never transcribed from a search result. Because `WPILibNewCommands.json` **ships offline with the WPILib installer**, reading it is a local file open at release-cut time and costs nothing. `verifyRequiresUuids` is what turns that into a checked fact.

**There are exactly two `requires` entries, and one of them is a third-party vendor.** Both halves of that sentence matter, and they matter for opposite reasons:

* `WPILibNewCommands.json` is **unavoidable and cheap**. `Mechanism implements Subsystem`; every `Command` factory, every `Trigger` and every `SysIdRoutine` needs it. It **ships offline with the WPILib installer**, so it is available on kickoff morning by construction, and requiring it costs a team nothing but one checkbox. It also fixes a real revision-1 failure: the quickstart started from the *Timed Skeleton* template, which does not install New Commands, producing a wall of unresolved symbols with nothing pointing at the cause.
* `AdvantageKit.json` is **avoidable in principle and we chose not to avoid it**. It does *not* ship with the installer. It is a third-party vendordep that must be published for the season before Rootstock can be installed at all.

**The claim revision 1 made here, and its replacement.** Revision 1's note under this skeleton read: *"Note: **no `requires` block.** Core needs nothing but WPILib. This is the whole point."* That note is **withdrawn**, and so is every claim in the docs built on it. The replacement text, which is what the README must say:

> Rootstock requires AdvantageKit. You cannot install Rootstock until AdvantageKit has published for the season. We took this trade deliberately, to make deterministic replay a guarantee rather than an option — and it is a real loss, because AdvantageKit's own 2026 release was itself gated on vendors who had not published.

Two second-order consequences that are easy to miss and cost support tickets if they are not designed for:

1. **A `requires` failure is a *better* error than a `NoClassDefFoundError`, and we should lean on that.** GradleRIO validates `requires` at configuration time and prints our `errorMessage`. Without the `requires` entry, a team missing AdvantageKit would get a link error deep in a build log. So the `errorMessage` above names the version floor and the install URL, and `rootstock doctor` repeats it with the resolved version matrix.
2. **The version floor is a real constraint, not a formality.** `requires[]` has no version field in the schema — it checks presence by UUID only. So an *outdated* AdvantageKit passes the vendordep check and then fails at compile or, worse, at run time. `rootstock doctor` therefore parses `vendordeps/AdvantageKit.json` and raises a named error below our floor, and `Rootstock` re-checks the resolved AdvantageKit version at boot and raises an `ERROR` / `BLOCKS_MATCH` alert on a mismatch. **This check did not need to exist before decision 3** and it is part of that decision's cost.

#### 4.2.2 `Rootstock-Phoenix6.json` — an adapter vendordep

```json
{
  "fileName": "Rootstock-Phoenix6.json",
  "name": "Rootstock-Phoenix6",
  "version": "2026.1.0",
  "uuid": "2e6c9d18-4b77-4f0a-8c53-b1a7e9d2c604",
  "frcYear": "2026",
  "jsonUrl": "https://rootstock.dev/vendordep/2026/Rootstock-Phoenix6.json",
  "mavenUrls": [ "https://rootstock.dev/repo", "https://repo1.maven.org/maven2" ],
  "javaDependencies": [
    { "groupId": "dev.rootstock", "artifactId": "rootstock-phoenix6", "version": "2026.1.0" }
  ],
  "jniDependencies": [],
  "cppDependencies": [],
  "requires": [
    {
      "uuid": "e995de00-2c64-4df5-8831-c1441420ff19",
      "errorMessage": "Rootstock-Phoenix6 needs CTRE Phoenix 6. Install it from https://v6.docs.ctr-electronics.com/en/stable/docs/installation/installation-frc.html then re-run Build Robot Code.",
      "offlineFileName": "Phoenix6-frc2026-latest.json",
      "onlineUrl": "https://maven.ctr-electronics.com/release/com/ctre/phoenix6/latest/Phoenix6-frc2026-latest.json"
    },
    {
      "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
      "errorMessage": "Rootstock-Phoenix6 needs Rootstock core. Install https://rootstock.dev/vendordep/2026/Rootstock.json first.",
      "offlineFileName": "Rootstock.json",
      "onlineUrl": "https://rootstock.dev/vendordep/2026/Rootstock.json"
    },
    {
      "uuid": "d820cc26-74e3-11ec-90d6-0242ac120003",
      "errorMessage": "Rootstock-Phoenix6 needs AdvantageKit, which Rootstock requires. Install it from https://docs.advantagekit.org/getting-started/installation/ then re-run Build Robot Code.",
      "offlineFileName": "AdvantageKit.json",
      "onlineUrl": "https://github.com/Mechanical-Advantage/AdvantageKit/releases/latest/download/AdvantageKit.json"
    }
  ]
}
```

**Every adapter JSON carries the AdvantageKit `requires` entry, and the duplication is deliberate.** Strictly it is redundant: the adapter already requires `Rootstock.json`, which requires AdvantageKit, and GradleRIO validates the whole installed set. We repeat it anyway because the error a team actually sees when a *transitive* requirement is missing names the wrong library, and "Rootstock-Phoenix6 needs Rootstock core" sends a student to install something they already have. The usual objection to duplicated UUIDs — they drift — is neutralized by `verifyRequiresUuids` below, which fetches and compares every one of them on every release.

Every vendor UUID is copied verbatim from that vendor's own published JSON at release-cut time — **never invented**. The table below is the register, with the state of each one as of this revision:

| Vendor | `fileName` | `uuid` | State |
|---|---|---|---|
| CTRE Phoenix 6 | `Phoenix6-frc2026-latest.json` | `e995de00-2c64-4df5-8831-c1441420ff19` | **Verified 2026-08-08** (live JSON, `frcYear` `2026`, `name` `CTRE-Phoenix (v6)`, current `version` `26.3.0`) |
| REVLib | `REVLib.json` | `3f48eb8c-50fe-43a6-9cb7-44c86353c4cb` | **Verified 2026-08-08** (live JSON, `frcYear` `2026`, current `version` `2026.0.5`) |
| PathplannerLib | `PathplannerLib.json` | `1b42324f-17c6-4875-8e77-1c312bc8c786` | **Verified 2026-08-08** (live JSON, `frcYear` `2026`, `version` `2026.1.2`) |
| AdvantageKit | `AdvantageKit.json` | `d820cc26-74e3-11ec-90d6-0242ac120003` | **Verified 2026-08-08** (release asset, `frcYear` `2026`, `version` `26.0.2`) |
| photonlib | `photonlib.json` | `515fe07e-bfc6-11fa-b3de-0242ac130004` | **Verified 2026-08-08** from the **2027-alpha** JSON (`wpilibYear` `2027_alpha5`). **[UNVERIFIED]** that the 2026-line file carries the same UUID — vendors keep UUIDs stable across years by convention, not by schema rule, which is precisely what `verifyRequiresUuids` exists to check. |
| ChoreoLib | *tbd* | — | **[UNVERIFIED]** — not yet read. Needed before `Rootstock-Choreo.json` is cut. |
| maple-sim | *tbd* | — | **[UNVERIFIED]** — not yet read. Optional adapter, beta upstream. |
| WPILib New Commands | `WPILibNewCommands.json` | *placeholder* | **[UNVERIFIED]** — candidate `111e20f7-815e-48f8-9dd6-e675ce75b266` from a secondary source only. Ships offline with the installer, so reading it is a local file open. |

A CI task `verifyRequiresUuids` fetches every `onlineUrl` in every `requires` block and asserts the returned JSON's `uuid` matches — a broken `requires` UUID silently disables the check, which is worse than not having it. **With AdvantageKit now load-bearing, a silently-disabled check on it is a build that compiles and a robot that does not log**, so this task is a release blocker rather than a nicety. It is also the mechanism that keeps the table above from rotting: each row is a fetch the CI already performs.

**UUIDs are assigned once and never change.** They identify the vendordep across versions and years. The 2027 line of `Rootstock.json` reuses `7b3f0a52-…`; only `version`, `wpilibYear`, `jsonUrl`, and the dependency versions change.

#### 4.2.3 The 2027 line

```json
{
  "fileName": "Rootstock.json",
  "name": "Rootstock",
  "version": "2027.0.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "wpilibYear": "2027",
  "jsonUrl": "https://rootstock.dev/vendordep/2027/Rootstock.json",
  "mavenUrls": [ "https://rootstock.dev/repo", "https://repo1.maven.org/maven2" ],
  "javaDependencies": [ { "groupId": "dev.rootstock", "artifactId": "rootstock", "version": "2027.0.0" } ],
  "jniDependencies": [],
  "cppDependencies": [],
  "requires": [ /* WPILibNewCommands + AdvantageKit, 2027 UUIDs, re-verified at the beta */ ]
}
```

Same UUID, different `jsonUrl`, `frcYear` → `wpilibYear`. **One vendordep JSON per (library-year × WPILib-year)**, because the year is baked into the file and validated against the `vendor-json-repo` bundle directory. `jsonUrl` and `mavenUrls` must be stable forever — GradleRIO re-resolves them on every build, and an online-installed vendordep's cache is cleared if the machine does not reconnect within 30 days.

**Two things changed about this file under the new plan, and both follow from shipping late rather than from any technical revision.**

1. **The 2027 line is a *development* line, not a supported release line.** Under revision 1's schedule, 2027.0.0 was the product and `release/2026` was the legacy branch to maintain. Under decision 1 the first tagged release lands years after 2027, so `2027.0.0` exists only as an internal snapshot consumed by 8793 and 9143. Nothing external is on the 2026 line to protect, which is precisely why M12 is a **one-way port** rather than the start of dual-line maintenance (§5.2, §5.3).
2. **The `requires` UUIDs must be re-verified for the new year, not copied forward.** Vendors keep their UUIDs stable across years by convention, not by schema rule, and `verifyRequiresUuids` is what turns that convention into a checked fact. For AdvantageKit specifically this is also the check that tells us whether R18 has fired: **no published 2027 `AdvantageKit.json` at the beta is the trigger condition in §4.8**, and the CI task that fetches the URL is where we find out.

### 4.3 Maven hosting — GitHub Pages static Maven, mirrored to Maven Central

**Decision: publish to a static Maven repo served from GitHub Pages at `https://rootstock.dev/repo`, and mirror every release to Maven Central under `dev.rootstock`.**

Rejected alternatives, with reasons:

* **GitHub Packages — disqualified, not a judgment call.** GitHub's Maven registry requires an access token *even for public packages*; only `ghcr.io` (containers) allows anonymous read. GradleRIO consumers have no way to authenticate. Corroborated negatively: not one major FRC vendordep uses it.
* **JitPack — viable but wrong for us.** DogLog proves it works (`"mavenUrls": ["https://jitpack.io"]`, `com.github.jonahsnider:doglog`). Three reasons we don't: (a) JitPack forces coordinates `com.github.<user>:<repo>`, permanently welding a personal GitHub account into every consumer's build; (b) we publish nine artifacts from a multi-project build (§3.3), which JitPack handles awkwardly; (c) JitPack *rebuilds* the source in its own environment — we want to ship the exact jars CI built and tested.
* **Self-hosted Nexus (PhotonVision's approach) — rejected.** Requires a server, a domain, TLS renewal, and someone to notice when it's down. That is a succession liability for a library whose whole pitch includes "will still work when the maintainer graduates."

Why **Maven Central as a mirror** — this is the differentiating decision and no FRC library does it: Central artifacts are immutable and permanent. If the GitHub org is deleted, the account is banned, or Pages changes policy, `https://repo1.maven.org/maven2` still resolves **`dev.rootstock:rootstock:20NN.1.0`** and every existing team's build keeps working. That is the concrete, technical form of the succession plan the community explicitly asks for (`web-ecosystem.json`: *"Projects like MapleSim are waning in support as the developer has graduated and if you don't have a succession plan this may be more of a hindrance…"*).

**The coordinates in that sentence are load-bearing and were wrong until this revision.** An earlier draft said `dev.rootstock:rootstock-core:2027.1.0`. **There is no `rootstock-core` artifact** — D28 collapsed the eleven core jars into one artifact named **`rootstock`** (§3.3) — and there will be no `2027.1.0` release, because §5.1 makes the 2027 line an internal development line and the first tagged release is `20NN.1.0` at M24. A forker reading a succession plan looks up exactly one string, so that string has to be right: it is `dev.rootstock:rootstock`.

Publishing config:

```groovy
// buildSrc/src/main/groovy/rootstock.publish-conventions.gradle
//
// APPLIED ONLY TO THE PROJECTS THAT ACTUALLY PUBLISH (§3.3): the aggregate `rootstock` jar and
// each of the six adapters. It is NOT applied per source set. The ten source sets under §3.2
// (pure, core, mechanism, tuning, vision, telemetry, drive, auto, sim, testkit) are SOURCE SETS,
// not artifacts -- D28 collapsed them into ONE published artifact, and `artifactId = project.name`
// would resurrect the eleven-POM release process D28 deleted.
apply plugin: 'maven-publish'
apply plugin: 'signing'

publishing {
  publications {
    maven(MavenPublication) {
      groupId = 'dev.rootstock'
      artifactId = project.publishedArtifactId   // rootstock, rootstock-phoenix6, ...
      version = rootProject.rootstockVersion
      from components.java
      pom {
        name = project.publishedArtifactId
        description = 'Rootstock — one-stop FRC library'
        url = 'https://rootstock.dev'
        licenses { license { name = 'BSD-3-Clause'; url = 'https://opensource.org/license/bsd-3-clause' } }
        developers { /* >= 2 named maintainers — required by our own support contract */ }
        scm { url = 'https://github.com/rootstock/Rootstock' }
      }
    }
  }
  repositories {
    // 1) static repo checked into the gh-pages worktree, published by CI
    maven { name = 'pages';   url = "${rootDir}/build/pages/repo" }
    // 2) Maven Central portal (release builds only, CI-signed)
    maven {
      name = 'central'
      url  = 'https://central.sonatype.com/api/v1/publisher/upload'
      credentials(PasswordCredentials)
    }
  }
}
```

CI on a `v*` tag: `./gradlew publishAllPublicationsToPagesRepository`, then commit `build/pages/` to the `gh-pages` branch (which also carries `vendordep/` and `docs/`), then `publishAllPublicationsToCentralRepository`. One tag → Maven repo + vendordep JSONs + docs all updated atomically.

#### 4.3.1 License — BSD-3-Clause (maintainer decision 4)

**Decided and final: BSD-3-Clause.** [`LICENSE`](../LICENSE) at this repository's root carries the standard three-clause text with the copyright line `Copyright (c) 2026 Rootstock contributors`. Every "License: TBD" reference in the documentation set is replaced by that. The reasons, in the order they matter:

1. **It matches WPILib.** WPILib itself is BSD-3-Clause. A team that already reads and accepts WPILib's license has nothing new to evaluate, and — the concrete case that decided it — **a team can vendor a single Rootstock file into their own repo with no legal question to answer.** That is not hypothetical: copying one file out of a library is how FRC teams actually consume `LimelightHelpers`, `ElasticLib` and half a dozen team-repo utilities. Making the *supported* path (the vendordep) the easy one and the *copy-one-file* path legally trivial is strictly better than making the second path ambiguous.
2. **LGPL is a real deterrent.** YAMS is LGPL, and it creates linking questions no high-school mentor should have to answer. We must also never hard-link LGPL code into a distributed jar.
3. **It is half of the answer to R21 (maintainer continuity across three-plus years).** A three-year solo runway is long enough for the project to simply stop. BSD-3-Clause plus Maven Central mirroring means the work is forkable and re-publishable by anyone, without asking. Those are the two real mitigations for R21 and both are now decided rather than pending.

**Consequences we must actually honor, not just declare:**

* **Every vendored third-party file carries its own upstream license header and attribution.** That is `ElasticLib` (§9.4) and the Limelight helpers today. `LICENSE-THIRD-PARTY.md` at the repo root enumerates each vendored file, its upstream URL, its upstream license, and the commit it was vendored from. A CI check fails the build if a file appears under a `vendored/` path without an entry.
* **The published POM's `licenses` block says BSD-3-Clause** (already in the `publish-conventions` script above) — Maven Central requires it, and a wrong or missing entry blocks the release rather than degrading quietly.
* **The R18 fork contingency (§4.8 tier 2) is gated on *AdvantageKit's* license, not ours.** Our being BSD-3-Clause permits us to *be* forked; it says nothing about whether we may fork AdvantageKit. **That question is now CLOSED, and favorably. Verified 2026-08-08: AdvantageKit is BSD-3-Clause** (`Copyright (c) 2021-2026 Littleton Robotics. All rights reserved.`), so redistribution and modification with attribution are permitted and **tier 2 legally exists.** Two consequences we must honor rather than merely note:
  1. **The non-endorsement clause is the only real constraint, and it constrains our *naming and marketing*, not our code.** Verbatim: *"Neither the name of Littleton Robotics, FRC 6328 (\"Mechanical Advantage\"), AdvantageKit, nor the names of other AdvantageKit contributors may be used to endorse or promote products derived from this software without specific prior written permission."* So `rootstock-akit-compat` must credit upstream **as provenance, never as endorsement**: the README and POM say *"contains code derived from AdvantageKit (BSD-3-Clause), © Littleton Robotics"* and must not say or imply *"AdvantageKit-approved"*, *"built with Littleton Robotics"*, or anything a reader could take as a blessing. The artifact name itself contains `akit`, which is descriptive rather than endorsing; if upstream ever objects, the artifact renames and that costs one release.
  2. **BSD-3-Clause requires the copyright notice, the condition list and the disclaimer to be reproduced** in the redistribution — so every vendored file keeps its upstream header and gets an entry in `LICENSE-THIRD-PARTY.md`, exactly as the bullet above already requires for ElasticLib and the Limelight helpers. The CI check that fails the build on a missing entry covers this case with no new machinery.
  **Residual, and it is small:** this is the license on `main` as of 2026-08. The 2027 branch must be re-read at the trigger date (§4.8) — the same fetch that arms the trigger — because a license can change between branches and a contingency gated on a stale reading is not a contingency. Source in Appendix A.

### 4.4 GradleRIO consumption — the secondary path, and what it structurally cannot do

This is the path for a team with an **existing** robot project who is adopting Rootstock incrementally ([`DESIGN.md` §11b](../DESIGN.md)). It is fully supported and it is documented second, after §6's template flow.

* **VS Code:** `WPILib: Manage Vendor Libraries` → `Install new libraries (online)` → paste `https://rootstock.dev/vendordep/2026/Rootstock.json`. GradleRIO will then demand `AdvantageKit.json` and `WPILibNewCommands.json` if they are absent, with the `errorMessage` text from §4.2.1.
* **CLI:** `./gradlew vendordep --url=https://rootstock.dev/vendordep/2026/Rootstock.json`
* **Offline:** drop the JSON into `vendordeps/` and run `./gradlew vendordep --url=FRCLOCAL/Rootstock.json`.

**Three things a vendordep cannot do. This list is the technical core of the argument for decision 2**, because the template can do all three and a vendordep can do none of them:

1. **It cannot add an annotation processor.** Two separate consequences, and they resolve differently:
   * ***`@AutoLog` — D24 stands, and decision 3 did not change it.*** `@AutoLog` requires `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$version"` in the consumer's `build.gradle`, **and** it generates `XxxInputsAutoLogged` *into the same package as the annotated type* — so a library-owned annotated inputs class generates into `org.rootstock.*`, where a team can neither usefully extend nor substitute it. Making AdvantageKit a required dependency removed the first objection and **not the second**. D24 was made for package-scope reasons, the package-scope concern is unresolved, and **Rootstock therefore still hand-writes `toLog()`/`fromLog()` everywhere.** This removes a whole class of "installed the vendordep and nothing logs" support tickets.
   * ***The M20 replay-safety lint is only deliverable because the template exists.*** `rootstock-lint` is a javac annotation processor that runs a build-time replay-safety check on **team** code. A vendordep cannot wire it up; `RootstockTemplate`'s `build.gradle` is ours to write, so it ships the `annotationProcessor` line pre-configured. **A team on the vendordep-only path gets the runtime tripwire and not the build-time check, and the docs must say so at the point of install rather than letting them discover the difference from a missing warning.**
2. **It cannot add a Gradle plugin.** `gversion` (for `BuildConstants`) and our `rootstockCheckDeploy` task require build-script lines. Under decision 2 the answer is no longer "an `instructions` URL pointing at a one-screen *add these 8 lines* page" — that is the documented onboarding failure this design exists to avoid. The answer is: **the template ships them wired**, and the vendordep-only path degrades gracefully and *says* it is degraded. `DeployInfo` reports `UNKNOWN` and raises an `INFO` / `PIT_ONLY` alert rather than failing to compile (it locates the class reflectively; see §11), and the alert text names the template as the fix.
3. **It cannot pin a coherent *set* of versions.** A vendordep pins itself. A team assembling Rootstock + AdvantageKit + Phoenix 6 + PathPlanner by hand can and will end up with a combination we have never compiled. `RootstockTemplate` pins the whole matrix as one set, `.rootstock/template.lock` records it, and `rootstock doctor` diffs the resolved matrix against the last set CI actually built (§6.5). **This is a new problem created by decision 3** — before it, the version matrix had one fewer third-party entry and that entry was the one most likely to move mid-season.

**What the vendordep-only team gives up, stated once, in the install docs, not discovered later:** the strict/dirty deploy tasks, `BuildConstants` provenance, the shipped Elastic layout, the CI workflow, the headless smoke test, the M20 build-time replay lint, and the pinned version set. Everything else — every class in §7 through §11 — works identically.

### 4.5 Listing in the official WPILib vendor picker

To appear in `WPILib: Manage Vendor Libraries → Install new libraries (online)`, open a PR against `wpilibsuite/vendor-json-repo` adding, in the year's bundle directory. **`20NN` below is the WPILib season current when M24's gate passes — it is not knowable today (§5.1) and is deliberately not written as `2026` or `2027`:**

1. `20NN/Rootstock-20NN.1.0.json` — the vendordep file itself (parent directory must match the year value for `wpilibYear` ≥ 2026).
2. An entry in `20NN.json` (the manifest):
```json
{
  "path": "20NN/Rootstock-20NN.1.0.json",
  "name": "Rootstock",
  "version": "20NN.1.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "description": "One-stop FRC library: mechanisms, vision, autos, health monitoring, self-test, tuning. REQUIRES AdvantageKit.",
  "website": "https://rootstock.dev",
  "languages": ["java"],
  "instructions": "https://rootstock.dev/install"
}
```
3. An entry in `20NN_metadata.json`: `{ name, uuid, description, website, instructions }`.

`"languages": ["java"]` only — we ship no C++.

**Timing under decision 1.** This PR is an **M24** item, not an M8 item, and the ordering is deliberate. Nothing is announced publicly before M24 ([`ROADMAP.md` §8](../ROADMAP.md)), and appearing in the official WPILib vendor picker *is* an announcement — arguably the loudest one available, since it puts Rootstock in front of every team who opens the vendor dialog. Listing an internal snapshot there would forfeit all three benefits of not shipping (no API-stability obligation, no in-season support obligation to strangers, no reputational exposure) in exchange for nothing. **The `description` field carries the AdvantageKit requirement in capitals** — shown above, not left as an instruction to a future self — because the picker is where a DogLog team decides whether to click, and the cheapest possible place to tell them not to.

The `instructions` URL points at the **template** page, not at a vendordep install page (decision 2). A team arriving from the picker already has a robot project, so that page must lead with the incremental-adoption path and link the template flow second — the one place in the docs where the ordering inverts, because the audience arrived through the secondary door.

### 4.6 The NetworkTables namespace contract

Every Rootstock domain publishes under `/Rootstock/`. This is a *contract*, not a convention: the Elastic and AdvantageScope layouts we ship (§9.4) hard-code these paths, and an ArchUnit/lint rule fails the build on any NT topic string outside it.

```
/Rootstock/Meta/            Version, GitSha, GitBranch, Dirty, BuildDate, RobotId, WpilibYear
/Rootstock/Match/           Alliance, IsRed, Station, Event, MatchType, MatchNumber, Replay, FmsAttached, Phase
/Rootstock/Driver/          Blocking (string[], MAX 3), BlockingMore (string), Ready (boolean)   <- §8.2.1
/Rootstock/Health/Summary   Worst (string), ActiveCount (int), BlockingCount (int), MatchReady (boolean)
/Rootstock/Health/<Source>/ Ok, Faults (string[]), LastFaultTime
/Rootstock/Health/SweepCycles     int    — loops in one full round-robin sweep (§8.3)
/Rootstock/Health/ExpectedAbsent  string[] — hardware declared absent via Health.expectAbsent() (§8.3.1)
/Rootstock/SelfTest/Summary Text block for the pit display
/Rootstock/SelfTest/<Name>/ Status, Passed, Ran, Detail, DurationSec
/Rootstock/Power/<Channel>/ Amps, Breaker, OverPct
/Rootstock/Power/Total      Amps, Watts, MatchJoules, Voltage, MinVoltage
/Rootstock/Can/<Bus>/       Utilization, BusOff, TxFull, Rec, Tec
/Rootstock/Loop/            PeriodMs, Max20sMs, Overruns, /Domain/<name>Ms
/Rootstock/Mechanisms/…     design/01
/Rootstock/Drive/…          design/05
/Rootstock/Vision/…         design/03
/Rootstock/Auto/…           design/05
/Rootstock/Tuning/…         design/02 (mirrors AdvantageScope's expected /Tuning root via an alias)
/Rootstock/Controls/        Markdown control map (string), for the Elastic text widget
```

**Who owns what inside this tree, because D22 splits it.** This domain owns the **namespace contract** — the `/Rootstock/` root reservation, the platform subtrees above (`Meta`, `Match`, `Driver`, `Health`, `SelfTest`, `Power`, `Can`, `Loop`, `Controls`), and the lint rule that fails the build on an NT topic string outside `/Rootstock/`. [`design/04`](04-telemetry-replay-viz.md) owns the **transport and the per-signal schema** under the domain roots, including the plumbing of the `/Rootstock/Driver` mirror and its `Ready` rollup (D22). The *policy* for that mirror — only `BLOCKS_MATCH`, capped at three, ranked by severity then registration order — is this document's, in §8.2.1. Neither half is useful without the other, and neither may change unilaterally.

WPILib's `Alert` publishes to `/SmartDashboard/<Group>` — an NT3-era path that will read oddly after SmartDashboard's 2027 removal, but it is where Elastic and AdvantageScope look for the Alerts widget. We therefore do **both**: `RootstockAlert` drives a real WPILib `Alert` (so the stock widget works) *and* mirrors a structured summary to `/Rootstock/Health/`. If the Alert NT path moves in 2027, only `org.rootstock.core.alert` changes. **[UNVERIFIED]** whether the `/SmartDashboard/<Group>` alert path is retained in WPILib 2027 — re-check at 2027 kickoff.

**The `org.rootstock.core.nt.TelemetrySink` SPI that used to sit here is deleted (decision 3).** Revision 1 defined a seven-method pluggable-backend interface in `org.rootstock.core.nt` so that AdvantageKit, DogLog, Epilogue and raw NT4 could each supply an implementation. With AdvantageKit required there is exactly one implementation, and an SPI with one implementation is a layer of indirection that costs a redirect on every publish and buys nothing. [`design/04`](04-telemetry-replay-viz.md)'s `RootstockLog` writes to `Logger` directly. *(Again: the type deleted **here, by decision 3** is the `core.nt` one, which was never the same type as `org.rootstock.telemetry.TelemetrySink`. Revision 4 of this sentence added "…which D9 keeps"; that is **no longer true** — D9 revision 5 struck the preserving clause and deleted the telemetry-side `TelemetrySink` and `TelemetrySource.sample()` as well, in favor of push. **No type named `TelemetrySink` exists anywhere in Rootstock.** The attribution above is unchanged: only the `core.nt` deletion is decision 3's. See the disambiguation in §1.)*

What this domain still owns after the collapse — and these are the parts that were actually load-bearing:

* **The namespace contract above.** Unchanged, still enforced by lint, still what the shipped Elastic and AdvantageScope layouts bind to.
* **The pre-`start()` metadata hook.** `Logger.recordMetadata` is write-once before `Logger.start()`, so `DeployInfo` (§11.1) must be resolved and injected in that window. `RootstockLifecycle` owns the ordering; it is one ordered call now instead of an interface method every backend had to promise not to violate.
* **The FMS-attach WPILOG rename** (§10 item 2), which moved from the deleted `rootstock-advantagekit` adapter into core.

**The honest accounting.** This deletion is a genuine simplification — roughly a person-week across domains 06 and 07, one fewer indirection between a `MotorInputs` field and the log, and one fewer thing to explain. It is also **irreversible in practice**: re-adding a backend SPI later means re-threading every publish site in every domain. If the 2029 relevance review concludes Epilogue has closed the replay gap ([`ROADMAP.md` §7.3](../ROADMAP.md) question 2), the cost of reversing decision 3 is paid here, and it is larger than the saving recorded here. That asymmetry should be understood now rather than rediscovered then.

### 4.7 The release gate — a 9-job template matrix plus the vendordep smoke test

Adoption dies at install, not at evaluation (`web-smallteam.json`: *"Cannot Build Robot Code since this is not a WPILib project"*). Under decision 2 the template is the advertised path, so **the template is what the release gate tests first**, and it tests all three variants on all three operating systems.

**The template matrix — 3 OS × 3 variants = 9 jobs, and every one of them gates the release.**

| | `swerve` | `differential` | `mechanism-only` |
|---|---|---|---|
| **Windows** | build + headless sim | build + headless sim | build + headless sim |
| **macOS** | build + headless sim | build + headless sim | build + headless sim |
| **Ubuntu** | build + headless sim | build + headless sim | build + headless sim |

**Before M15 the matrix is six jobs, not nine, and that is not a weakening — it is the same rule §6.3 already states.** `DifferentialBackend` lands in M15, so between M8 and M15 the `differential` variant is **documented-absent**: `rootstock init --template differential` *fails with a named message and a date*. The matrix therefore runs `swerve` and `mechanism-only` on three operating systems (six jobs) and adds a **seventh** job that asserts the differential path fails *in the specified way* — named message, named date, non-zero exit, no partially-generated directory left behind. A gate that demands a green build from a variant three documents say cannot exist yet is not a gate, it is a blocked milestone; a gate that demands the documented-absent behavior be exactly as documented is a real one. At M15 the differential jobs turn on and the assertion job is deleted, and **the M15 gate is where the differential clean-install belongs.** *(This resolves the M8-gate conflict `REVIEW.md` B8 identified; the corresponding edits to `ROADMAP.md` §5's M8 gate, `DESIGN.md` §12.1a and `DECISIONS.md` MD2 are listed as contract requests.)*

Each job runs:

1. Regenerate the variant from `template/` at the release version, and **also** download the published `RootstockTemplate` zip and extract it with the platform's **default** unzip tool (this is exactly what broke AdvantageKit on macOS). Assert the two trees are byte-identical apart from `.git`.
2. `./gradlew build` — must pass, on a clean Gradle cache, resolving `rootstock` and AdvantageKit from the *published* Maven URLs rather than from the local build.
3. `./gradlew simulateJava --tests headless` via `rootstock-testkit`'s headless harness — must reach 500 loop iterations with no `Severity.ERROR` alert, and must pass `AlertBudgetTest` (§8.2.2): **at most five simultaneous `BLOCKS_MATCH` alerts** with no hardware present. *(Five is the CI budget on how many blocking alerts may exist at once; three is the separate cap on how many are **displayed** on the driver tab. The two numbers are different questions — §8.2.2.)*
4. `rootstock doctor --template` on the freshly generated fork must report **every** template-owned file `UNCHANGED` — a fresh clone that already shows drift means the generator and the hash manifest disagree, which would make §6.5's classification meaningless from day one.
5. Assert the resolved dependency matrix equals the matrix recorded in `.rootstock/template.lock`. A template that pins a version set it does not actually resolve to is worse than no template.

**Plus the vendordep path**, which is the secondary door and is tested as such:

6. `./gradlew vendordep --url=https://rootstock.dev/vendordep/<year>/Rootstock.json` against a *bare* WPILib example project **that already has AdvantageKit installed**, then build. And the negative case: the same install against a project **without** AdvantageKit must fail with our `errorMessage` text and no other error — decision 3 makes this the single most likely install failure, so it is asserted rather than assumed.
7. `check.py`-equivalent validation of every published vendordep JSON, plus `verifyRequiresUuids` (§4.2.2).

A release that fails any of the **eleven release checks — the nine matrix jobs (3 OS × 3 variants), plus step 6 and step 7, which run once each** — does not ship. **The cost of this gate is not free and it grows with the variant count** — it is the concrete reason depth lever **L3** ("one template variant instead of three", −0.5 pw) exists in [`ROADMAP.md` §6](../ROADMAP.md), and it is why that lever's stated loss is real: dropping to one variant drops the matrix to three jobs and leaves differential and mechanism-only teams hand-assembling from docs.

### 4.8 R18 — what happens if AdvantageKit does not ship for WPILib 2027

Decision 3 converts R18 from *Medium, mitigated by the `LogBackend` escape hatch* to **High, accepted**. The escape hatch is gone: there is no backend to fall back to, and `RootstockRobot extends LoggedRobot` is a compile-time fact, not a configuration. **If AdvantageKit does not ship for a WPILib line, Rootstock does not ship for that line.** That sentence is the risk, stated without hedging.

An accepted risk with no written response is just an unmanaged risk with better vocabulary, so the response is decided in advance, here, before it is needed.

> **Tier numbering — corrected in this revision, and it is now execution order.** An earlier draft numbered these *fork = 1, contribute = 2* and then printed them in the order contribute-then-fork, so the document's own numbering contradicted its own sequencing, and `ROADMAP.md` §4.2 / `DECISIONS.md` MD3 inherited the inversion while `DESIGN.md` §13.1 used the opposite. **Canonical, everywhere, from now: Tier 1 = contribute · Tier 2 = fork · Tier 3 = wait.** The number is the order you try them in. `REVIEW.md` M15.

**Trigger — armed at the WPILib 2027 beta (~Dec 2027), and at no other time.**
The condition is: *no public 2027 branch, alpha artifact, or stated intent to port from AdvantageKit.* Not at the alpha (~Oct 2027) — that is too early to conclude anything about a project that ports on its own schedule. Not at kickoff — that is too late to do anything about it. The check is mechanical: `verifyRequiresUuids` fails to fetch a 2027 `AdvantageKit.json`, and M12's dual-compile job has nothing to compile against. **The same fetch also re-reads the LICENSE on the 2027 branch**, which is what keeps tier 2's legal basis current rather than three-years stale.

**Tier 1 — contribute, not fork.** Before anything else, offer the port upstream: the `org.wpilib.*` rename over AdvantageKit's own source, submitted as a PR, plus an offer to carry it. A one-maintainer library forking another one-maintainer library over a platform migration is how small ecosystems fragment, and the FRC Java ecosystem is small enough that this matters more than our schedule does. This is tier 1 because it is the one we should actually try first, even though it is the one we control least.

**Tier 2 — the vendored fork, `dev.rootstock:rootstock-akit-compat`.** A fork of the *minimum AdvantageKit surface Rootstock actually uses* — `LoggedRobot`, `Logger`, `LogTable`, `LoggableInputs`, the WPILOG reader/writer, the replay driver — ported to `org.wpilib.*`, published under our coordinates, with upstream credited prominently and a **stated public intent to delete it the day upstream ships**. Distribution shape, since this is a platform-domain question:

* It is a **separate artifact and a separate vendordep JSON**, never folded into `rootstock`, so deleting it later is a `requires` swap rather than a source merge.
* `Rootstock.json`'s `requires` block swaps the AdvantageKit entry for the compat entry **for that year's line only**. Teams see one different vendordep URL, not a different library.
* The runtime version check from §4.2.1 gains a third state: `UPSTREAM` / `COMPAT_FORK` / `MISSING`, published to `/Rootstock/Meta/LoggerProvenance` and printed in the boot dump, so a log from a fork-year robot is identifiable years later.
* **The scope is bounded by ArchUnit rule 1c, not by guesswork.** Rule 1c's allowlist (`DESIGN.md` §8) is what tells us how much of AdvantageKit Rootstock actually names: clause (i) is seven driver types reachable from four packages, clause (ii) is two schema types (`LogTable`, `LoggableInputs`) reachable from any `..io..` package. That list *is* the fork's surface, and keeping the rule green is what keeps this contingency from growing silently. **Scope: ~2.5 pw**, budgeted as a contingency line and **explicitly not included in the 74.0 pw** total. If it fires, the calendar in [`ROADMAP.md` §2](../ROADMAP.md) moves by roughly six calendar weeks at solo pace.
* **Legally available — VERIFIED 2026-08-08, and this was the single most consequential unverified fact in this document.** AdvantageKit's LICENSE on `main` is **BSD-3-Clause**, `Copyright (c) 2021-2026 Littleton Robotics. All rights reserved.` Redistribution and modification are permitted with attribution and reproduction of the notice, condition list and disclaimer. **Tier 2 exists.** The only additional constraint is the non-endorsement clause — *"Neither the name of Littleton Robotics, FRC 6328 (\"Mechanical Advantage\"), AdvantageKit, nor the names of other AdvantageKit contributors may be used to endorse or promote products derived from this software without specific prior written permission"* — which governs how we **name and describe** the compat artifact, not whether we may publish it. Concretely: credit upstream as *provenance* (`"contains code derived from AdvantageKit (BSD-3-Clause), © Littleton Robotics"` in the README, the POM and every vendored file header), never as *endorsement*; do not write "AdvantageKit-approved", "Littleton Robotics-backed", or anything a reader could take as a blessing; and be prepared to rename the artifact on request, which costs one release. Source in Appendix A.
  **Residual `[UNVERIFIED]`, and it is genuinely residual:** the license on the *2027 branch*, at the trigger date. Re-read it then. This is a one-file check bundled into a fetch the trigger already performs, and it is the difference between a live contingency and a stale one.

**Tier 3 — wait, and say so first.** If contribution fails and the fork is somehow not permitted after all: **Rootstock's line for that WPILib year does not ship, and the README says so on its first screen, before anyone adopts.** Not discovered in January by a team that already forked the template and built a season on it. Concretely: a banner on the docs landing page, a pinned note in the template README, and — because the template is the front door — `rootstock doctor` returns a hard error naming the situation and pointing at the pinned last-good line. The team's escape is the same one R15 already ships: pin to the last good snapshot, or run `docs/removing-rootstock.md`.

**What the 2026-08-08 verification changed about R18's severity, stated precisely.** R18 stays **High and ACCEPTED** — the risk was never *"can we legally fork?"*, it was *"will upstream port at all, and can one solo maintainer absorb it if not?"*, and neither of those moved. What changed is that the *middle tier is now known to exist* rather than being a plan that might evaporate on a license read, and (from §4.1) that the 2027 native delta is **one new platform triple against three that already exist**, so an upstream delay is more likely to cost robot deploy than to cost desktop development. Both are improvements in the *shape* of the risk, not in its probability, and the severity is unchanged for that reason.

**What we do not do:** we do not attempt a partial port that logs without replaying, and we do not silently degrade to NT4. Deterministic replay is what we traded the kickoff-morning install property for; a version of Rootstock without it is a worse product than the one we deliberately did not build.

### 4.9 Documentation is a release blocker

Every code snippet in the docs is **extracted at build time from a compiled, executed, asserted test file**. No hand-written code in prose, ever. CI fails if a snippet's source test does not compile against the pinned WPILib/vendor versions. Rationale is direct evidence: a competing library shipped an AI-written doc that said feedforward uses `sin` where it must be `cos`, and a reviewer's conclusion was *"So now I don't even know if I can trust this library to accurately control my mechanism."* One wrong trig identity costs more trust than ten missing pages. We also publish `llms.txt` and `.md` mirrors of every page, because LLMs are now a primary onboarding tool for small teams and hallucinate badly against fast-moving FRC APIs.

**Three pages are release blockers in their own right under decisions 2 and 3**, because each one is where a team learns something that will otherwise cost them a match:

1. **The install page leads with the template** (§4.0), states the AdvantageKit requirement above the fold, and lists what the vendordep-only path does *not* get (§4.4). A team that discovers the AdvantageKit requirement halfway through an install has already formed an opinion.
2. **`docs/UPDATING.md` ships inside every template fork** (§6.7), not only on the website, because the moment a team needs it is the moment they are on venue wifi.
3. **The adoption matrix** carries the DogLog and plain-Epilogue **exclusion** rows verbatim from [`ROADMAP.md` §4.2](../ROADMAP.md). A team on DogLog must be able to find out in ten seconds that this library is not for them, without installing anything. Making that easy is not modesty; it is the difference between "not applicable to us" and "wasted our week 2."

---

## 5. Versioning and the WPILib Ports That Now Happen *During* Development

This is a first-class requirement, and **decision 1 changed its shape completely.** WPILib 2027 renames every Java package `edu.wpi.first.*` → `org.wpilib.*`, removes NT3, moves to SystemCore, requires Java 25, deletes Shuffleboard/SmartDashboard/PathWeaver/RobotBuilder, deletes a long list of HAL features, and changes the field coordinate origin. Peter Johnson (WPILib lead) states plainly that *"it will not be possible to have the same code target both the 2026 and 2027 libraries."*

**What changed.** Revision 1 assumed Rootstock would ship a 2026 release, acquire users on it, and then port — so the port was a *migration of a shipped product*, with a legacy line to maintain and users to protect. Under decision 1, full-scope v0.1 lands at solo pace in 2030. That means:

| | Revision 1 assumed | The plan now |
|---|---|---|
| WPILib majors inside the build window | zero — port after shipping | **at least two** (2027 and 2028), and at solo pace a third (2029) |
| The 2027 port is | a migration of a shipped library with users | **milestone M12, 8.0 pw, in the middle of development** |
| External users on the 2026 line | expected | **none — nothing is announced before M24** |
| After the port, we maintain | 2026 *and* 2027 lines in parallel | **one line. The 2026 line is deleted.** |
| The generator + dual-compile CI | permanent infrastructure from commit 1 | **built at M12, deleted at the end of M12** |

The last two rows are the consequential ones and they are examined in §5.2 and §5.3 Part C.

### 5.1 Versioning scheme: year-anchored `YEAR.MAJOR.MINOR`, with internal snapshots below it

```
2026.0.0-SNAPSHOT-M4     internal milestone snapshot; 8793/9143 only; NOT announced, NOT public
2026.0.0-SNAPSHOT-M11    ditto, the competition-complete internal build
2027.0.0-SNAPSHOT-M13    the same line after M12 ports it to WPILib 2027
20NN.1.0                 THE FIRST TAGGED RELEASE — cut at M24, NN = the WPILib season current then
20NN.1.3                 bugfix on the release line
```

* `YEAR` = the WPILib season the artifact targets. Reading a version tells you which WPILib it runs on with zero lookup. Matches PathPlanner (`2026.1.2`) and YAGSL (`2026.8.05`); rejects Phoenix's `26.x` because two digits are ambiguous.
* `MAJOR` bumps only on a breaking change to Rootstock's own public API. **Frozen between kickoff and championship** — a public, written commitment, because mentors explicitly compare third-party libs unfavorably to WPILib's per-season API freeze. **This freeze binds from M24 onward and not before**, which is the point below.
* `MINOR` = additive features and fixes.
* The Maven `version` string, the vendordep `version`, and the git tag are always identical.

**`-SNAPSHOT-M<n>` is not a pre-release of anything, and the distinction is load-bearing.** A `-beta.N` implies an imminent GA and invites strangers to try it. These snapshots are consumed by exactly two robot programs (8793 and 9143), from a GitHub Pages Maven URL that is not linked from anywhere, with **no API stability obligation whatsoever**. Breaking changes are free, and over three years that freedom is worth several person-weeks — it is the one genuine advantage of not shipping and it should be exploited deliberately rather than accidentally. The moment a `-beta` tag exists, that advantage is gone.

**What year the first release carries is not knowable today.** It is whatever WPILib season is current when M24's gate passes: at +2 committers, plausibly 2028; at solo pace, 2030. The docs must therefore never hard-code "2027.1.0" as the first release, and the vendordep `frcYear`/`wpilibYear` rule in §4.2 is written to work for any year rather than for a specific one.

Support contract published on page 1 of the docs **from M24, when there is finally someone to make it to**: no breaking changes between kickoff and championship; semver within a year line; one full season of deprecation before removal; ≥2 named maintainers; a public matrix of the exact WPILib and vendor versions each release was tested against. Before M24 the honest statement is the opposite and it also belongs in writing: *this is unreleased software consumed by its authors' two teams; there is no support policy because there is no public.*

### 5.2 Branch strategy — one line, and after M12 it is one line *permanently*

```
main            → the ONLY development line. Nothing else exists before M24.
release/20NN    → cut at M24 with the first tagged release, and only then.
```

Revision 1 specified `main` + `release/2026` + `release/2027` maintained in parallel, with bugfixes cherry-picked between them. **That scheme is withdrawn**, and the reason is a genuine (if grim) benefit of shipping late:

* **There are no external users on the 2026 line to protect.** The whole purpose of a maintained legacy branch is that somebody's robot is running it during a season. Nobody's is. 8793 and 9143 move with the port, in the same week, because the same person controls all three repos.
* **Dual-line maintenance is a permanent 20–30% tax on every milestone after M12**, paid to protect nobody. Over M13–M24 that is several person-weeks — larger than any single depth lever in [`ROADMAP.md` §6](../ROADMAP.md) except `SimulatedLimelight`.
* **The cherry-pick discipline decays.** A backport policy with no consumer is the first thing to be skipped under time pressure, at which point the branch is a lie that still costs CI minutes.

So: **at the end of M12, the 2026 line is deleted.** Not frozen, not archived-but-buildable — deleted, along with `src/wpi2026/`, the generator, and the dual-compile CI job. `main` is `org.wpilib.*` from that day forward. The 2028 and 2029 WPILib lines (1.5–2.5 pw each, **not** in the 74.0 pw) are ordinary in-place upgrades of that single line, not new branches.

**The one thing this costs, stated:** if a 2027-line change turns out to be wrong and we want to compare against 2026 behavior, the answer is `git checkout` of the pre-M12 commit, not a maintained branch. That is a worse debugging experience and it is the correct trade, because the alternative is paying a tax every week for three years against a scenario that has arisen zero times in a project with zero users.

### 5.3 The seam — what actually makes the rename mechanical

The naive answers are both wrong. Wrapping every WPILib type is a huge surface, violates "never hide WPILib," and produces exactly the over-abstraction the community punished YASS for. Doing nothing means editing 400 files in January.

The correct seam has **four parts**, and it works because the rename itself is a *pure textual substitution on imports*. What makes it non-mechanical is only (a) types that move subpackage, (b) methods renamed or removed, (c) semantic changes. So we shrink (a)/(b)/(c) to a countable list and automate the rest.

#### Part A — A banned-API list enforced in CI

`rootstock-core`'s test source set contains an ArchUnit suite that is a release blocker.

**Which numbered rule is which — stated once so nobody cites the wrong number again.** [`DESIGN.md` §8](../DESIGN.md) enumerates **twelve numbered rules** (plus the lettered sub-rules 1b and 1c). `WpiSurfaceTest` below implements five of them; the other seven live in `ArchitectureTest` and are not reproduced here. `REVIEW.md`'s minor list found three separate documents citing the wrong rule numbers for exactly these rules, so:

| `WpiSurfaceTest` field | Implements `DESIGN.md` §8 rule |
|---|---|
| `noDeadWpiApis` | **rule 4** (no SmartDashboard / Shuffleboard / NT3) **and rule 5** (2027-removed HAL) |
| `noMutableUnits` | **rule 5**, the `edu.wpi.first.units.measure.Mut*` clause |
| `volatileApiIsConfined` | **rule 2** (no year-volatile WPILib API outside `org.rootstock.core.compat` **and `org.rootstock.field`**) |
| `onlyMatchReadsDriverStation` | **rule 10** |
| `noMandatoryInheritance` | **rule 7** |

Rule 2's allowlist is **`core.compat` + `org.rootstock.field`** in `DESIGN.md`, and the code below adds `core.match` on top of that as this domain's own narrower carve-out for `MatchContext`. An earlier draft of this file omitted `org.rootstock.field` from the predicate entirely, which would have made `RootstockField` (owned by [`design/05`](05-drivetrain-auto.md) under D14) a rule violation on the day it was written — the exact "rule that ships broken and gets `@Disabled` in week two" failure this section opens by warning about.

```java
package org.rootstock.arch;

import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

@AnalyzeClasses(packages = "org.rootstock", importOptions = ImportOption.DoNotIncludeTests.class)
public final class WpiSurfaceTest {

  /** Removed in 2027. Referencing any of these guarantees a rewrite. */
  @ArchTest static final ArchRule noDeadWpiApis = noClasses().should().dependOnClassesThat()
      .resideInAnyPackage(
          "edu.wpi.first.wpilibj.smartdashboard..",
          "edu.wpi.first.wpilibj.shuffleboard..",
          "edu.wpi.first.cameraserver..")
      .orShould().dependOnClassesThat().haveNameMatching(
          ".*\\.(Relay|AnalogOutput|AnalogGyro|AnalogTrigger|SPI|DMA|Counter|Ultrasonic|"
        + "DigitalGlitchFilter|Servo|NidecBrushless|ADIS16448_IMU|ADIS16470_IMU|ADXRS450_Gyro|ADXL345_SPI)");

  /**
   * Mutable units are deleted in 2027 — they must never cross a Rootstock boundary.
   * ONE predicate, matched on the fully-qualified name. Deliberately NOT
   * `resideInAPackage("edu.wpi.first.units.measure..").andShould().haveSimpleNameStartingWith("Mut")`:
   * that is a conjunction on a `noClasses()` predicate, so it only forbids classes satisfying
   * BOTH clauses. It happens to work today because MutDistance/MutAngle live in that package —
   * but it reads as a ban on the whole `measure` package (which every config record depends on),
   * and one upstream package move turns the accident into a build-breaking lie.
   */
  @ArchTest static final ArchRule noMutableUnits = noClasses().should()
      .dependOnClassesThat().haveNameMatching("edu\\.wpi\\.first\\.units\\.measure\\.Mut.*");

  /**
   * DESIGN.md §8 RULE 2. Volatile WPILib API is confined to the compat tier. These five types are
   * the ones whose package, class name, or method set we expect to move in 2027, so exactly THREE
   * packages in the whole library are allowed to name them:
   *   - org.rootstock.core.compat  (Clock, Platform, Gamepads, MathX, PoseX -- FIVE classes)
   *   - org.rootstock.field        (RootstockField, owned by design/05 under D14 -- it absorbs the
   *                                  2027 field-origin move and joins this fenced tier. There is
   *                                  NO core.compat.RootstockField; D14 deleted it.)
   *   - org.rootstock.core.match   (MatchContext -- the single DriverStation reader, §10)
   * Everything else calls a facade. This rule is GREEN on the library as designed; §5.3-E is
   * the call-site conversion table that makes it green, and it is a release blocker.
   */
  @ArchTest static final ArchRule volatileApiIsConfined = classes()
      .that().resideOutsideOfPackages(
          "org.rootstock.core.compat..",
          "org.rootstock.field..",               // <- DESIGN §8 rule 2's own second entry (D14)
          "org.rootstock.core.match..")          // <- this domain's carve-out for MatchContext
      .should().onlyDependOnClassesThat().haveNameNotMatching(
          "edu\\.wpi\\.first\\.wpilibj\\.(Timer|RobotController|RobotBase|Filesystem|DriverStation)");

  /**
   * DriverStation has exactly ONE reader in the whole library: org.rootstock.core.match.
   * Note this list is narrower than volatileApiIsConfined's — `compat` is NOT allowed to name
   * DriverStation either. That is why Platform has no diagnosticsMode(): it would have made
   * compat a second DriverStation reader, and a "single reader" rule with two readers is a lie.
   * MatchContext.isDiagnostics() is the one spelling (§10).
   *
   * If a second package ever needs DriverStation, the answer is a new MatchContext method,
   * not a new allowlist entry.
   */
  @ArchTest static final ArchRule onlyMatchReadsDriverStation = classes()
      .that().resideOutsideOfPackages("org.rootstock.core.match..")
      .should().onlyDependOnClassesThat()
      .haveNameNotMatching("edu\\.wpi\\.first\\.wpilibj\\.DriverStation.*");

  /**
   * DESIGN.md §8 RULE 7. Users must never be forced to extend a Rootstock class.
   * Commands v3 depends on this.
   */
  @ArchTest static final ArchRule noMandatoryInheritance = noClasses()
      .that().resideInAPackage("org.rootstock..")
      .should().beAssignableTo("edu.wpi.first.wpilibj2.command.SubsystemBase");
}
```

> **~~One open contradiction, flagged rather than silently resolved.~~ RESOLVED 2026-08-08 — and the resolution is the second of the two options this note offered, so the note is kept as the record of the choice rather than deleted.** The contradiction was: `DESIGN.md` §8 **rule 5** listed `robotInit()` among the 2027-removed APIs Rootstock "does not use", while `DESIGN.md` §5.5 **D29** gave the public `RootstockLifecycle` a method of exactly that name and §10A.4, §11b and this document's §13 all named it. Rule 5's *intent* was that Rootstock never **overrides `IterativeRobotBase.robotInit`**; its *text* banned the identifier. Two fixes were offered — scope the rule and keep the name, **or** rename the hook and propagate.
> **The rename won.** `DESIGN.md` **D29** now specifies `RootstockLifecycle.init()` — *"renamed from `robotInit()`"*, idempotent, called at the end of the constructor with a lazy first-`beforeUserPeriodic()` fallback — and **D13a** additionally deletes `RootstockRobot`'s override of the WPILib hook: *"`robotInit()` appears nowhere. `RootstockRobot` overrides `robotPeriodic()`, `disabledInit()` and `close()` only."* So **rule 5 is enforceable as written, with no scoping and no named exception**, because the library's exposure to the identifier is now zero. §13 below and [`design/01`](01-core-mechanisms.md) §1.1a are propagated to match; a team's *own* `Robot` may still override `robotInit()`, since rule 5 governs library packages. `REVIEW.md` M3, `DESIGN.md` §16 item 5(e).

`noMandatoryInheritance` matters most: 135's Consul requires `extends SubsystemChecker` and is therefore hostile to Commands v3, which uses `Mechanism` as its noun and composition as its model. **Rootstock requires no inheritance anywhere.** Everything is an interface plus a registry.

`volatileApiIsConfined` matters second-most, and it is the rule that most easily ships broken. A rule that is red on day one gets `@Disabled` in week two and the 2027 seam becomes decorative. So we do not weaken the rule — we fix the call sites, once, and enumerate them in §5.3 Part E so no domain author has to guess.

#### Part B — `org.rootstock.core.compat`, the only package that knows the year

The complete list of known 2027 deltas that are *not* a pure import rename, each with its façade. This list is small on purpose.

```java
package org.rootstock.core.compat;

import edu.wpi.first.units.measure.Frequency;
import edu.wpi.first.units.measure.Time;
import static edu.wpi.first.units.Units.Hertz;
import static edu.wpi.first.units.Units.Seconds;

/**
 * The single place Rootstock reads the robot clock or the loop period.
 * Never Timer.getFPGATimestamp(). Never System.nanoTime(). Never a wall clock for RATE GATING —
 * see periodCycles() and §8.3: time is for measuring, cycles are for scheduling.
 */
public final class Clock {
  private Clock() {}

  /** Replay-safe monotonic seconds. Maps to Timer.getTimestamp(). For MEASURING durations. */
  public static double seconds() { return edu.wpi.first.wpilibj.Timer.getTimestamp(); }
  public static Time now() { return Seconds.of(seconds()); }

  /**
   * THE loop timestep, in seconds. Captured once from IterativeRobotBase.getPeriod() at
   * RootstockRobot construction and never re-read.
   *
   * NOT `TimedRobot.getPeriod()`, which is what revision 5 of this line said and which is a
   * leftover from the pre-D13 base class. Under D13 `RootstockRobot extends LoggedRobot`, and
   * AdvantageKit's `LoggedRobot extends IterativeRobotBase` -- NOT TimedRobot (verified
   * against LoggedRobot.java). `getPeriod()` is declared on IterativeRobotBase as
   * `public double getPeriod()` (verified against the WPILib javadoc), so the call is
   * inherited and correct; only the class named in this comment was wrong.
   *
   * D12 makes `Clock.dt()` the one spelling in the library --
   * it is what replaced the deleted `Rootstock.dt()` god-object accessor, and it is the name
   * design/01 §1.4 asks for. There is deliberately no `periodSeconds()` twin: two spellings of
   * one number is how `0.02` ends up hardcoded in five places again.
   */
  public static double dt();

  /** The same number as a Time, for signatures that take units. */
  public static Time period();

  /** Monotonic robot-loop counter. Increments exactly once per periodic(), from 0. */
  public static long cycle();

  /**
   * How many robot loops make up one period of {@code hz}, minimum 1.
   * {@code periodCycles(Hertz.of(4))} == 13 at 50 Hz: ceil(1 / (4 * 0.02)) = ceil(12.5) = 13.
   * THIS is how every periodic rate in Rootstock is expressed. It is exact under replay,
   * under a 100 Hz robot, and under sim stepping — a wall-clock gate is none of those.
   */
  public static int periodCycles(Frequency hz) {
    return Math.max(1, (int) Math.ceil(1.0 / (hz.in(Hertz) * dt())));
  }

  /** Convert a duration to a whole number of loops, rounded up, minimum 1. Used by debounce(). */
  public static int cyclesFor(Time duration) {
    return Math.max(1, (int) Math.ceil(duration.in(Seconds) / dt()));
  }

  /** True on exactly one loop out of every n, with a stable phase offset derived from the key. */
  public static boolean everyNCycles(int n, String phaseKey) {
    return n <= 1 || (cycle() + Math.floorMod(phaseKey.hashCode(), n)) % n == 0;
  }
}
```

**Why cycles and not seconds — and why decision 3 did *not* make this unnecessary.** Under AdvantageKit replay `Timer.getTimestamp()` *is* the log timestamp, so a seconds-based gate is deterministic there. Revision 1's argument was that Rootstock also supported DogLog, raw NT4 and Epilogue, where it is not — and that argument is now gone, because those backends are gone. **The rule survives anyway, on two independent grounds that decision 3 does not touch:**

* **`SimHooks.stepTiming` and hand-ticked JUnit tests are not AdvantageKit replays.** `rootstock-testkit` steps the scheduler directly; a wall-clock gate in a unit test either never fires or fires nondeterministically, and either way the test is worthless. Every health monitor, every `RootstockAlert.debounce`, and every slice in this domain is asserted from a JUnit test.
* **A 100 Hz robot, or any non-20-ms period, breaks a hard-coded seconds gate silently.** `periodCycles(Hertz.of(4))` re-derives from the actual period; `if (now - last > 0.25)` does not.

So: a cycle counter is deterministic in real time, in replay, in sim stepping and in JUnit; it costs one `long` increment; and it makes `RootstockReplayVerify` diffs meaningful instead of noisy. `everyNCycles` phase-offsets by key so that ten 4 Hz consumers do not all land on cycle 0 and rebuild the very spike we are removing. What changed is only the *justification's* breadth — the discipline is unchanged, and it is now backed by an unconditional replay guarantee rather than a conditional one.

```java
package org.rootstock.core.compat;

import java.nio.file.Path;
import java.util.Optional;

/** Platform facts whose source changes with roboRIO → SystemCore. */
public final class Platform {
  private Platform() {}

  public static boolean isSimulation()  { return edu.wpi.first.wpilibj.RobotBase.isSimulation(); }
  public static boolean isReal()        { return edu.wpi.first.wpilibj.RobotBase.isReal(); }
  public static int     teamNumber()    { return edu.wpi.first.wpilibj.RobotController.getTeamNumber(); }

  /** roboRIO serial. On SystemCore availability is UNVERIFIED — returns empty if unavailable. */
  public static Optional<String> serialNumber() {
    String s = edu.wpi.first.wpilibj.RobotController.getSerialNumber();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /** roboRIO web-dashboard "comments" field. UNVERIFIED on SystemCore. */
  public static Optional<String> comments() {
    String s = edu.wpi.first.wpilibj.RobotController.getComments();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /** Writable, redeploy-surviving directory. /home/lvuser on roboRIO. */
  public static Path persistentDir() {
    return edu.wpi.first.wpilibj.Filesystem.getOperatingDirectory().toPath();
  }

  public static Path deployDir() {
    return edu.wpi.first.wpilibj.Filesystem.getDeployDirectory().toPath();
  }

  // NOTE: there is deliberately NO Platform.diagnosticsMode() here. Test/Utility mode is a
  // DriverStation read, and DriverStation has exactly one reader in this library —
  // org.rootstock.core.match. Callers say MatchContext.isDiagnostics() (§10). Putting it here
  // would have made `compat` a second DriverStation reader and quietly broken the one rule
  // (onlyMatchReadsDriverStation) that keeps the 2027 DS surface to a single file.

  // ---- RobotController surface used by the built-in monitors (§8.5). ----
  // These exist so CanBusMonitor / RailMonitor / BrownoutMonitor / BatteryMonitor can be written
  // in org.rootstock.core.health.builtin without naming RobotController, which the ArchUnit
  // rule forbids outside this package.

  /** RobotController.getCANStatus() -> edu.wpi.first.hal.can.CANStatus, flattened. */
  public static CanStatus canStatus() {
    var s = edu.wpi.first.wpilibj.RobotController.getCANStatus();
    return new CanStatus(s.percentBusUtilization, s.busOffCount, s.txFullCount,
                         s.receiveErrorCount, s.transmitErrorCount);
  }

  /** Flattened CAN status. Same shape as Phoenix 6's CANBusStatus, so one monitor reads both. */
  public record CanStatus(double utilization, int busOff, int txFull, int rec, int tec) {}

  public static double batteryVolts()  { return edu.wpi.first.wpilibj.RobotController.getBatteryVoltage(); }
  public static boolean isBrownedOut() { return edu.wpi.first.wpilibj.RobotController.isBrownedOut(); }
  public static int faultCount5V()     { return edu.wpi.first.wpilibj.RobotController.getFaultCount5V(); }
  public static int faultCount3V3()    { return edu.wpi.first.wpilibj.RobotController.getFaultCount3V3(); }
  public static int faultCount6V()     { return edu.wpi.first.wpilibj.RobotController.getFaultCount6V(); }
}
```

`Platform.canStatus()` returning a flattened record rather than the HAL type is deliberate: `edu.wpi.first.hal.can.CANStatus` is a HAL type on the 2027 move list (`edu.wpi.first.hal.` → `org.wpilib.hardware.hal.`, §5.3 Part C), and it is the *only* HAL type Rootstock would otherwise touch. Flattening it here means `rootstock-core` has zero HAL imports outside `compat`.

**`RootstockField` is NOT in this package. D14 deleted `core.compat.RootstockField`.** Revision 1 of this document declared a field-geometry facade here, and `design/04` and `design/05` each declared one too — three types, one name. [`DESIGN.md` §5.3 D14](../DESIGN.md) resolved it: there is **one `org.rootstock.field.RootstockField`, owned by drive/auto ([`design/05`](05-drivetrain-auto.md))**, and it *joins the ArchUnit-fenced compat tier* (rule 2's second allowlist entry, Part A above) precisely because it is the file that absorbs the 2027 field-origin move — 2026's blue-wall-corner origin becomes 2027's **field-center** origin, a 180° rotation, under which alliance flipping collapses to a negation. That change is still ONE file; it is just not a file this domain owns. `design/04`'s ghost contract was renamed `org.rootstock.viz.FieldGhosts` by the same decision.

What this domain contributes to that seam is the *supplier*, not the geometry: `MatchContext::isRed` and `MatchContext.allianceKnown()` (§10), which is what `RootstockField.forAlliance(...)` and PathPlanner's flip argument consume. Keeping the two apart is what makes "alliance is latched exactly once, by the only `DriverStation` reader" true.

Also in `compat`: `Gamepads` (2027 collapses `XboxController`/`PS4Controller`/`PS5Controller`/`StadiaController` into one `Gamepad`), `MathX.clamp` (2027: `MathUtil.clamp` → `Math.clamp`), and `PoseX.expTwist(pose, twist)` (2027: `Pose2d.exp(Twist)` → `pose.plus(twist.exp())`).

That is the **entire** hand-maintained surface of this package: **5 small classes** — `Clock`, `Platform`, `Gamepads`, `MathX`, `PoseX` — plus `org.rootstock.field.RootstockField` in the same fenced tier but under `design/05`'s ownership. *(An earlier draft said "6 small classes" and counted `RootstockField` among them; that count is corrected here, not just the prose.)*

#### Part C — Generated 2027 sources: built at M12, used inside M12, deleted at the end of M12

**This is the part of the design decision 1 changed most, so read the timing before the mechanism.**

Revision 1 had the generator and the dual-compile CI job existing from the first commit, with CI compiling every PR against both WPILib lines for the whole life of the project. **That is now wrong for a simple reason: there is nothing to compile against.** WPILib 2027's first alpha does not exist until roughly Oct 2027, and at solo pace M1–M11 run from Oct 2026 to Jan 2028. A dual-compile job configured in 2026 would spend a year failing to resolve a dependency, get disabled in week two, and be decorative by the time it mattered — the exact failure mode §5.3's opening paragraph warns about for `volatileApiIsConfined`.

**The rule, therefore:**

| Period | 2027 defense in force |
|---|---|
| **M1 → M11** (2026-10 → 2028-01 solo) | **ArchUnit only.** The five rules in Part A, the five `compat` classes in Part B, `org.rootstock.field` (D14, owned by `design/05` but inside the same fence), and the Part E conversion table. Plus rule 12: **Java 17, no preview features, ever** — a preview feature makes "the port is an import rewrite" false. `gradle/wpi-rename-2027.properties` exists and is edited as facts are confirmed, but **no Gradle task loads it and no CI job uses it.** It is a research artifact, not build infrastructure. |
| **M12 arms** (first confirmed 2027 alpha, ~Oct 2027) | The generator (`generate2027Sources`), the `wpi2027` source set, and the dual-compile CI job are **written**, as the first work item of M12. This is the only period in which they exist. |
| **Inside M12** (must complete in the beta window) | Dual-compile runs on every PR. Every row of the rename map is verified against a real compile rather than believed. `src/wpi2026/java/` and `src/wpi2027/java/` overlays carry the `compat` package's two implementations, and the generator skips them. |
| **End of M12** | **`generate2027Sources`, the `wpi2027` source set, the dual-compile job, `src/wpi2026/java/` and `wpi-rename-2027.properties` are all deleted.** Canonical source becomes `org.wpilib.*` on `main`. See §5.2. |
| **M13 → M24** | Nothing. Single line. The next WPILib major (2028) is an ordinary in-place upgrade at 1.5–2.5 pw, **not** in the 74.0 pw, and it does not resurrect the generator. |

The mechanism itself is unchanged and is reproduced in full below, because it is correct and it is the thing M12 builds:

* Canonical source is written against **2026 names** (`edu.wpi.first.*`, Java 17 syntax).
* The `wpilib2027` Gradle variant applies a **checked-in, reviewed substitution map** into `build/generated/sources/wpi2027/`, then compiles that against WPILib 2027 with `--release 25`.
* Files that cannot be mechanically translated are *not* translated: `src/wpi2026/java/` and `src/wpi2027/java/` overlays carry the `compat` package's two implementations, and the generator skips them.

```groovy
// buildSrc/.../rootstock.wpi-variant.gradle  (abridged, real shape)

// ---------------------------------------------------------------------------
// 1) Load the rename map ONCE, at configuration time, into a deterministic,
//    longest-key-first map. Three separate bugs are being avoided here:
//      * Properties is a Hashtable -> iteration order is UNSPECIFIED. With overlapping
//        prefixes ("edu.wpi.first.hal." and a future "edu.wpi.first.") the shorter key can
//        win and produce "org.wpilib.hal." on one machine and "org.wpilib.hardware.hal."
//        on another. Longest-first ordering makes the result independent of load order.
//      * Re-parsing the file inside filter{} is O(lines x filesize) — minutes on a 40k-line
//        tree — and newReader() without withReader() leaks one file descriptor PER LINE.
//      * The comparator must never return 0 for unequal keys or TreeMap silently drops them.
// ---------------------------------------------------------------------------
def renameMapFile = file("$rootDir/gradle/wpi-rename-2027.properties")   // reviewed, in git
def renameMap = new TreeMap<String, String>({ a, b ->
  (b.length() <=> a.length()) ?: (a <=> b)          // longest key first, then lexicographic
} as Comparator)
renameMapFile.withReader('UTF-8') { r ->
  def p = new Properties()
  p.load(r)
  p.each { k, v -> renameMap.put(k.toString().trim(), v.toString().trim()) }
}

def outDir = layout.buildDirectory.dir("generated/sources/wpi2027")

// Only import/package lines are rewritten. Applying replace() to the whole line rewrites
// string literals, comments, @ReplayExempt reasons, NT topic names, log keys, and the fenced
// Java snippets Principle 11 extracts from tests — all of which must survive verbatim.
def IMPORT_OR_PACKAGE = ~/^\s*(import|package)\s/

tasks.register('generate2027Sources', Copy) {
  inputs.file(renameMapFile)                        // rerun when the map changes
  from("src/main/java")
  into(outDir)
  filter { String line ->
    if (!IMPORT_OR_PACKAGE.matcher(line).find()) return line
    renameMap.inject(line) { acc, k, v -> acc.replace(k, v) }
  }

  // 2) Fail loudly on anything the map missed, rather than shipping a half-renamed tree.
  //    src/wpi2027/java is the hand-written 2027 overlay and is not generated, so it is
  //    outside this check by construction (different directory).
  doLast {
    def bad = []
    fileTree(outDir).matching { include '**/*.java' }.each { f ->
      f.readLines().eachWithIndex { l, i ->
        if (l.contains('edu.wpi.first')) bad << "${f}:${i + 1}: ${l.trim()}"
      }
    }
    if (bad) {
      throw new GradleException(
        "generate2027Sources: ${bad.size()} un-renamed edu.wpi.first reference(s) survived the\n" +
        "rename map. Add the missing prefix to gradle/wpi-rename-2027.properties:\n  " +
        bad.take(20).join("\n  "))
    }
  }
}

sourceSets {
  wpi2027 {
    java.srcDirs = [ outDir, "src/wpi2027/java" ]
    compileClasspath += configurations.wpilib2027
  }
}

// 3) Two toolchains, explicitly. `options.release = 25` requires a JDK >= 25 COMPILER;
//    it is not a cross-compile flag you can pass to javac 17. Gradle's toolchain service
//    provisions both, and CI must have both available (see below).
compileJava {
  javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(17) }
  options.release = 17
}
compileWpi2027Java {
  dependsOn generate2027Sources
  javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
  options.release = 25
}
```

**CI needs both JDKs.** The 2026 line compiles on JDK 17 (GradleRIO 2026 pins it); the 2027 variant compiles on JDK 25 (SystemCore's runtime). The dual-compile job therefore runs:

```yaml
# .github/workflows/ci.yml  (excerpt)
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: |
      17
      25
- run: ./gradlew build compileWpi2027Java
```

and `gradle.properties` sets `org.gradle.java.installations.auto-download=true` so a contributor with only JDK 17 installed still gets a working `compileWpi2027Java` instead of a confusing toolchain error. A contributor with no network gets a named failure: the toolchain resolver's message is one of the few Gradle errors that already says exactly what is missing.

`gradle/wpi-rename-2027.properties` (excerpt — every entry reviewed by a human, none guessed):

```properties
edu.wpi.first.wpilibj.        = org.wpilib.wpilibj.
edu.wpi.first.wpilibj2.command. = org.wpilib.wpilibj2.command.
edu.wpi.first.math.           = org.wpilib.math.
edu.wpi.first.units.          = org.wpilib.units.
edu.wpi.first.networktables.  = org.wpilib.networktables.
edu.wpi.first.util.           = org.wpilib.util.
edu.wpi.first.net.            = org.wpilib.net.
edu.wpi.first.hal.            = org.wpilib.hardware.hal.
```

**[UNVERIFIED]** — the exact 2027 target subpackage for each `edu.wpi.first.*` root. AdvantageKit's main branch already imports `org.wpilib.math.geometry.Rotation2d` (confirmed), and allwpilib's own 2027 test sources show `HAL` at `org.wpilib.hardware.hal.HAL` (per dossier `web-simtest.json`, from a tag diff). **Every row of this map is a guess until a real compile says otherwise, and under the new timing that compile does not happen until M12** — which is fine, and is in fact the argument for not building the generator early: a rename map maintained for a year against no compiler is a year of accumulated wrong guesses that look reviewed.

**Inside M12, CI runs both compiles on every PR.** A change that compiles on 2026 but not 2027 fails the build the day it is written. Outside M12 there is no such job, because outside M12 there is nothing on the other side of it.

**At the end of M12 the generator is deleted, and so is the 2026 line.** Canonical source becomes 2027 (`org.wpilib`), `src/wpi2026/` disappears, and the rename map file is removed from git rather than left as a stale artifact. The generator is a transition bridge, not permanent infrastructure — that is what keeps it from rotting, and under decision 1 it is *also* what keeps M13–M24 from paying a dual-line tax for two more years (§5.2).

#### Part C.1 — How M12 is sequenced against the milestones

M12 is **the only date-triggered milestone in the entire plan.** Everything else is ordered by dependency and completes whenever capacity allows. M12 is ordered by FIRST's calendar, and it does not negotiate:

* **It cannot start earlier than the first confirmed 2027 alpha (~Oct 2027).** There is nothing to port against. Chasing pre-alpha branches converts verified facts into churn.
* **It must not start later than the beta (~Dec 2027).** The alpha/beta window is the only period in which porting questions get *answered* — WPILib maintainers are actively fielding them, other vendors are porting simultaneously and publishing what they find, and the API is still moveable if we find a genuine problem. Porting in March against a frozen release means every surprise is ours alone to absorb.
* **What is in flight when it arms depends on capacity, and the rule differs:**

| Capacity | Where the build is at ~Oct 2027 | M12 behavior |
|---|---|---|
| **+1 or +2 committers** | M11 complete (2027-06-26 / 2027-04-20); M13–M15 in progress | **Insert M12 at the alpha.** Finish the milestone in flight if it is within a week of done, then port. |
| **Solo** | M11 completes 2028-01-01 — *after* the alpha | **M12 PREEMPTS whatever is in flight.** The auto DSL waits. This is not a preference; a solo maintainer who lets the beta window close ports alone against a frozen API and pays for it for a year. |
| **Student team** | M9–M10 in progress | Same preemption rule, and students are removed from the critical path for its duration — M12 is exactly the kind of cross-cutting mechanical-but-subtle work the [`ROADMAP.md` §2](../ROADMAP.md) capacity note says never to hand them. |

* **The template ports with the library, in the same milestone.** All three `RootstockTemplate` variants are regenerated onto the 2027 line, the 9-job matrix (§4.7) runs green on 2027, and `.rootstock/template.lock` records the new matrix. A library that ports and a template that does not is a front door that opens onto nothing.
* **8793's and 9143's repos port with the library, in the same milestone.** That is the M12 exit gate, and it is the rehearsal: the migration is run for real, by its author, on two real robot programs, before any stranger ever runs it. This is the single largest benefit of the port happening during development rather than after shipping, and it should be claimed as such.
* **A 2028 WPILib line lands before M24 at every capacity level, and a 2029 line lands at solo pace.** Each is 1.5–2.5 pw, is **not** in the 74.0 pw, and is charged to the annual carrying cost in [`ROADMAP.md` §7](../ROADMAP.md). Concretely for this domain: a vendor-matrix bump, deprecation removals, a new `FieldLayouts`/AprilTag entry (~0.1 pw, easy to forget — gated by a CI check), a template regeneration, a 9-job matrix run, and CI image bumps.

#### Part D — Behavioral guardrails that make the rename *safe*, not just compilable

* **No `Timer.getFPGATimestamp()` anywhere.** All time via `compat.Clock`. This is also what makes Rootstock replay-safe under AdvantageKit.
* **No 20 ms assumption.** AdvantageKit supports only 50 Hz robots and maple-sim forbids overriding sim timings when AdvantageKit is present; we neither require nor silently change the loop period, and every periodic rate in this domain is expressed in Hz and re-derived from the actual period.
* **CAN devices and DIO only.** No relay, analog, SPI, DMA, counter, servo, ultrasonic, interrupts — all removed on SystemCore.
* **Multi-CAN-bus aware from day one.** SystemCore has multiple buses. `CanBusMonitor` takes a list of named buses, not a singleton (§8.5).
* **Immutable `Measure` at boundaries, raw doubles in the hot loop.** WPILib's own rule: accept `Measure`, return concrete immutable types (`Distance`, never `MutDistance`), use `.in(Unit)` only when bridging to non-units APIs. Config is construction-time, so allocation is irrelevant there; we convert to base-unit doubles once in the constructor.
* **No Commands v2 inheritance requirement, and no Commands v3 dependency.** Behavior is exposed as `Supplier<Command>` factories and `Trigger`s. A v3 adapter is additive.

#### Part E — The call-site conversion table (release blocker, cross-domain)

`volatileApiIsConfined` is only worth shipping if the library is green under it on the first commit. As written across the other design docs it is **not** — six call sites name a confined type directly. Each one has an existing facade; none needs a new concept. This table is normative for the domains named, and P0 does not close until every row is done.

| Doc / section | Today (fails the rule) | Required call | Facade owner |
|---|---|---|---|
| `design/01` §3.9 | `RobotBase.isSimulation()` | `Platform.isSimulation()` | `core.compat.Platform` |
| `design/01` §5.3 `Gains.realOrSim` | `RobotBase.isReal()` | `Platform.isReal()` | `core.compat.Platform` |
| `design/01` §6.2 | `DriverStation.isDisabled()` | `MatchContext.isDisabled()` | `core.match.MatchContext` |
| `design/02` §5.2 | `DriverStation.isFMSAttached()` | `MatchContext.isFMSAttached()` | `core.match.MatchContext` |
| `design/02` §11.2 | `Filesystem.getOperatingDirectory()` / `getDeployDirectory()` | `Platform.persistentDir()` / `Platform.deployDir()` | `core.compat.Platform` |
| `design/05` §6.2 | `Timer.getFPGATimestamp()` / `Timer.getTimestamp()` | `Clock.seconds()` | `core.compat.Clock` |
| ***(added 2026-08-08)*** `design/03` §9.7 | direct `DriverStation` read | the matching `MatchContext` accessor | `core.match.MatchContext` |
| ***(added 2026-08-08)*** `design/03` §11.4 | direct `DriverStation` read | the matching `MatchContext` accessor | `core.match.MatchContext` |
| ***(added 2026-08-08)*** `design/05` §8.1.1 | direct `DriverStation` read | the matching `MatchContext` accessor | `core.match.MatchContext` |
| ***(added 2026-08-08)*** `design/02` §14.2 | minimal-integration path bypasses `RootstockLifecycle` and owns its own rate gate | drive the lifecycle through `RootstockLifecycle`; register periodic work as a `SliceScheduler` slice (§8.3.1) | `core.RootstockLifecycle`, `core.health.SliceScheduler` |

**The last four rows are new and they matter more than their size suggests.** `DESIGN.md` §16 item 3 — the release-blocking checklist for exactly this sweep — enumerates `design/01` §3.9/§5.3/§6.2, `design/02` §5.2/§11.2 and `design/05` §6.2, and **omits** the three additional `DriverStation` readers and the `design/02` §14.2 lifecycle bypass. A sweep executed exactly against that checklist leaves `onlyMatchReadsDriverStation` red in three places and rule 9 / §8.3's single-rate-gate discipline violated in a fourth, which is how a rule ends up `@Disabled`. Adding those four rows to §16 item 3 is a contract request.

**Within this domain**, the same conversion applies, and every row below is already reflected in the
code in §7–§11. It is tabulated rather than left in prose because "already reflected" is exactly the
claim that rots:

| This doc / section | Confined API | Required call | Facade owner |
|---|---|---|---|
| §8.5 `CanBusMonitor.rio()` | `RobotController.getCANStatus()` | `Platform.canStatus()` | `core.compat.Platform` |
| §8.5 `RailMonitor` | `RobotController.getFaultCount5V()` etc. | `Platform.faultCount5V()` / `…3V3()` / `…6V()` | `core.compat.Platform` |
| §8.5 `BrownoutMonitor` | `RobotController.isBrownedOut()` | `Platform.isBrownedOut()` | `core.compat.Platform` |
| §8.5 `BatteryMonitor` | `RobotController.getBatteryVoltage()` | `Platform.batteryVolts()` | `core.compat.Platform` |
| §8.5 / §9.5 `DsMonitor` | `DriverStation.getJoystickName(port)` | `MatchContext.joystickName(port)` | `core.match.MatchContext` |
| §8.4 `SelfTest.runAll()` gate | `DriverStation.isTest()` | `MatchContext.isDiagnostics()` | `core.match.MatchContext` |
| §7.2 `assignCommand`, §11.2 `takeCommand` | `DriverStation.isTest()` | `MatchContext.isDiagnostics()` | `core.match.MatchContext` |
| §9.3 default LED state table | `DriverStation::isEStopped` | `MatchContext::isEStopped` | `core.match.MatchContext` |
| §9.4 `RootstockDashboard.init()` | `Filesystem.getDeployDirectory()` | `Platform.deployDir()` | `core.compat.Platform` |
| §7.1 identity chain | `Filesystem.getOperatingDirectory()` | `Platform.persistentDir()` | `core.compat.Platform` |
| §8.5 `BrownoutMonitor` timestamp | `Timer.getFPGATimestamp()` | `Clock.seconds()` | `core.compat.Clock` |

Every `MatchContext` method named in either table exists in §10's API listing. That is not a
coincidence — §10 was written *from* these two tables, and `AlertBudgetTest`'s sibling
`WpiSurfaceTest` is what keeps them honest.

Two rules that keep this from re-rotting:

1. **A new confined-API need is a new facade method, never a new allowlist entry.** There are exactly two allowlists and they are deliberately different widths: `volatileApiIsConfined` allows `core.compat` + `org.rootstock.field` + `core.match`; `onlyMatchReadsDriverStation` allows `core.match` **only** — `core.compat` is *not* on the second list, and `org.rootstock.field` is on neither, because the field seam has no business reading the DriverStation. All three widths are frozen. If [`design/03`](03-vision.md) needs `DriverStation.getAlliance()`, the answer is that `MatchContext.alliance()` already exists and is *better* (it latches).
2. **The facade is not a wrapper tax.** Every method above is a one-line delegation that the JIT inlines to nothing. The escape hatch survives: nothing stops team code from calling `DriverStation` directly — the rule is scoped to `org.rootstock..`, not to `frc.robot..`. We never hide WPILib from users; we only stop *ourselves* from scattering 2027's rename across 400 files.

---

## 6. `RootstockTemplate` — The Primary Front Door (decision 2)

### 6.1 The decision, and the argument against it, head-on

**Rootstock ships as library + template + CLI. The template is the advertised front door; the library is the substance underneath, published as versioned artifacts; the CLI owns the flows between them.**

Maintainer decision 2 changed the emphasis, not the components. Revision 1 shipped all three and advertised the *vendordep*, with the template as a convenience. The template is now what the README's first screen tells a team to use, and the vendordep install is documented second (§4.0, §4.4). Everything below §6.4 — the lock file, the update commands, the drift classification, the update procedure, the 9-job matrix — is new work created by that change of emphasis, and it is the +1.5 pw in this domain's budget (§16).

The reviewer objection is real and evidence-backed. The strongest signal in the small-team research is that **code generation beat runtime abstraction**: a working CSA said of YAMGen, *"the codegen route is far more useful than YAMS, and comes with fewer footguns,"* and *"The fact that it doesn't use the rest of YAMS is a strength, not a weakness."* CTRE independently shipped `corvus` (JSON → mechanism code) and Tuner X generators. Generated code is debuggable by a stranger at an event, survives the library's abandonment, and does not break when the generator does.

That argument is correct — **for the mechanism layer**. It is wrong for the platform layer, for three reasons:

1. **Cross-cutting infrastructure must be centrally fixable.** A CAN-utilization monitor, a battery-sag detector, an alert registry, and a self-test sequencer are identical on every robot and get *better* over time. Generating 300 copies means 300 divergent copies and zero bug-fix propagation. That is precisely the failure the ecosystem already exhibits: 3061's `FaultReporter` is locked inside a swerve starter repo, and **ElasticLib is distributed as a file you copy into `util/`, so every team runs a divergent private copy and fixes never propagate.** We will not add a 301st copy.
2. **Template-only distribution is a documented onboarding failure.** AdvantageKit's zip-template model produced *"I don't get how the template projects work…"* and *"Cannot Build Robot Code since this is not a WPILib project"* from multiple users on the kickoff thread. And AdvantageKit's own docs say *"Manually updating projects to 2026 is not recommended due to the risk of subtle breaking changes"* — meaning a template-only library cannot be upgraded mid-season at all. A vendordep can: bump one version string. **This is why decision 2 is "template *and* library", not "template instead of library"** — the template is the front door precisely because a versioned artifact is behind it.
3. **The WPILib rename is survivable for a library and brutal for a template.** We ship a new year-line and a team upgrades by editing one line. A team holding generated year-flavored platform code has to hand-migrate all of it. Under decision 1 this now happens *during* development (M12, §5.3 Part C.1), and the template ports in the same milestone — which is the first time anyone will have actually exercised it.

So the split is by *layer*, and we state it in the README:

| Layer | Delivery | Why |
|---|---|---|
| Platform (this domain), vision fusion, auto glue, tuning | **Library artifact, pinned by the template, installable standalone as a vendordep** | Identical everywhere; must be centrally fixable; must survive the rename |
| Mechanism subsystems, `RobotContainer` wiring, constants | **Shipped as worked examples in the template, and generated by `rootstock gen mechanism` into the team's repo with provenance comments** | Team owns it, reads it, edits it, keeps it if Rootstock dies |
| Project skeleton, build.gradle, CI, deploy dir, dashboards, the pinned version set | **Template repo — the advertised front door** | You cannot start from nothing, and a vendordep structurally cannot supply any of it (§4.4) |

### 6.2 What `RootstockTemplate` contains

A GitHub **template repository** (green "Use this template" button — no zip, no unzip-to-the-right-place step) plus a downloadable zip for offline use. It is a *complete, deployable, simulatable robot project* that builds green on clone with zero edits, **and it is generated from `template/` in the library repo (§3.2), never hand-edited.**

```
RootstockTemplate/                       (the `swerve` variant; the other two differ only where noted)
├── LICENSE                            # BSD-3-Clause. The team's own code is theirs; this covers
│                                      #   the template scaffolding they inherited.
├── build.gradle                       # GradleRIO + gversion + rootstockCheckDeploy + the two
│                                      #   annotationProcessors + spotless + JUnit5
├── settings.gradle
├── gradlew, gradlew.bat, gradle/      # committed wrapper, pinned Gradle version
├── .github/workflows/build.yml        # ./gradlew build test + headless sim smoke + rootstockCheckDeploy
├── .vscode/tasks.json                 # "Deploy (strict)" — the safe deploy is ONE CLICK (§7.4)
├── .wpilib/wpilib_preferences.json    # teamNumber placeholder, projectYear
├── .rootstock/
│   └── template.lock                  # THE PIN + THE HASH MANIFEST (§6.5). Committed.
├── docs/
│   ├── UPDATING.md                    # both update procedures, in the fork, readable offline (§6.7)
│   └── template-drift/                # where `rootstock update --template` writes unapplied patches
├── vendordeps/                        # ALL PINNED AS ONE COHERENT SET (§6.5). See §6.2.1 for
│   │                                  #   which files are unconditional and which are prunable.
│   ├── WPILibNewCommands.json         # ── unconditional (3 files) ──
│   ├── AdvantageKit.json              # REQUIRED as of decision 3 — not optional, not removable
│   ├── Rootstock.json
│   ├── Phoenix6-frc2026-latest.json   # ── vendor set `phoenix6` (2 files) ──
│   ├── Rootstock-Phoenix6.json
│   ├── REVLib.json                    # ── vendor set `revlib` (2 files) ──
│   ├── Rootstock-REVLib.json
│   ├── photonlib.json                 # ── vendor set `photonvision` (2 files) ──
│   ├── Rootstock-PhotonVision.json
│   ├── PathplannerLib.json            # ── vendor set `pathplanner` (2 files) ──
│   └── Rootstock-PathPlanner.json
├── src/main/java/frc/robot/
│   ├── Main.java
│   ├── Robot.java                     # extends RootstockRobot (which extends LoggedRobot) — see §13
│   ├── RobotContainer.java            # bindings + subsystem construction + the Superstructure
│   ├── Constants.java
│   ├── BuildConstants.java            # GENERATED by gversion; gitignored
│   ├── robots/
│   │   ├── RobotIds.java              # the RobotIdentity table (§7) + per-robot config overlays
│   │   ├── CompBot.java               # per-robot constant overlay
│   │   ├── PracticeBot.java
│   │   └── SimBot.java
│   └── subsystems/                    # NOT empty — see "worked examples" below
│       ├── Elevator.java              # a full PositionConfig mechanism, gravity + dual limits
│       ├── Intake.java                # a SimpleConfig mechanism with structural stop-on-end
│       ├── Superstructure.java        # enum goal machine with TWO real interlocks
│       └── Drive.java                 # variant-specific: swerve / differential / absent
├── src/main/deploy/
│   ├── elastic-layout.json            # ships a working driver dashboard (§9.4)
│   ├── pathplanner/                   # empty PathPlanner project skeleton
│   └── rootstock/
│       ├── disabled.txt               # EMPTY. The runtime kill switch (R15). Its presence with
│       │                              #   content disables Rootstock at boot. Shipped empty so
│       │                              #   a team that needs it does not have to learn it exists
│       │                              #   at the worst possible moment.
│       ├── gains.json                 # empty, schema-stamped "rootstock.gains/1"
│       └── config-snapshot.json       # the committed tuned-value snapshot (§11.2)
├── src/test/java/frc/robot/
│   └── SmokeTest.java                 # boots headless 500 cycles; no Severity.ERROR, <=5 BLOCKS_MATCH
├── advantagescope/
│   ├── AdvantageScope.json            # debug layout
│   └── AdvantageScopeTuning.json      # tuning layout (3061 ships both; nobody else does)
├── ROBOT.md                           # auto-regenerated: subsystems, CAN map, bindings, robot ids
├── CONTROLS.md                        # auto-generated from ControlMap (§9.5)
└── README.md                          # 10-minute path from clone to a driving robot in sim
```

#### 6.2.1 The vendordep set — grouped, prunable, and why it is not "five JSONs"

**This is the one place four other documents and this one disagreed, so it is settled here in full.** `README.md`, `DECISIONS.md` MD2, `ROADMAP.md` §3.1 and `DESIGN.md` §8 all describe the template's `vendordeps/` as **exactly five files** — `WPILibNewCommands`, `AdvantageKit`, `Rootstock`, `Rootstock-Phoenix6`, `Rootstock-REVLib` — pinned unconditionally. That list is wrong in **two independent ways**, and the second one is a build failure rather than a documentation nit:

1. **It forces a vendor.** A Phoenix-only team gets `Rootstock-REVLib.json`, whose `requires` block then demands REVLib. That is precisely the YAGSL behavior [`DESIGN.md` §8](../DESIGN.md) criticizes ("YAGSL's vendordep forces a REV-only team to install Phoenix 5, and no Rootstock adapter does anything like that") — reproduced by our own front door.
2. **It omits the upstream vendor JSONs entirely, so the template cannot build.** `Rootstock-Phoenix6.json` declares `requires` on Phoenix 6 with `offlineFileName: "Phoenix6-frc2026-latest.json"` (§4.2.2). GradleRIO validates `requires` at configuration time by UUID. A `vendordeps/` directory containing `Rootstock-Phoenix6.json` but **not** `Phoenix6-frc2026-latest.json` fails configuration with our own `errorMessage` — so the template's headline promise, *"builds green on clone with zero edits"*, would have been false on the first clone. Adapters travel in pairs and the five-file list broke every pair.

**The model, canonical from this revision.** `vendordeps/` is **three unconditional files plus four two-file vendor sets**:

| Group | Files | Prunable by `rootstock init`? |
|---|---|---|
| **Unconditional** | `WPILibNewCommands.json`, `AdvantageKit.json`, `Rootstock.json` | **No.** New Commands ships offline with the WPILib installer; AdvantageKit is required by decision 3; `Rootstock.json` is the library. Deleting any of the three is what `disabled.txt` and `docs/removing-rootstock.md` are for, not what `--vendors` is for. |
| Vendor set **`phoenix6`** | `Phoenix6-frc2026-latest.json` + `Rootstock-Phoenix6.json` | Yes |
| Vendor set **`revlib`** | `REVLib.json` + `Rootstock-REVLib.json` | Yes |
| Vendor set **`photonvision`** | `photonlib.json` + `Rootstock-PhotonVision.json` | Yes |
| Vendor set **`pathplanner`** | `PathplannerLib.json` + `Rootstock-PathPlanner.json` | Yes |

Arithmetic: **3 + (4 × 2) = 11 files** in the fully-populated generated template; **3 + 2 = 5 files** in the smallest coherent fork (`mechanism-only --vendors phoenix6`). Neither number is "five JSONs", and the coincidence that the minimum happens to be five is not the reason the other documents say five.

Upstream `fileName` values, **all verified 2026-08-08** against the live vendordeps (sources in Appendix A) because getting one wrong means an unresolvable `offlineFileName`: `Phoenix6-frc2026-latest.json`, `REVLib.json`, `PathplannerLib.json`, `AdvantageKit.json`, `photonlib.json`. *(`photonlib.json` was read from the 2027-alpha file; that the 2026-line file carries the same name is **[UNVERIFIED]** and is one of `verifyRequiresUuids`' fetches.)* `ChoreoLib` and `maple-sim` are **not** template vendor sets — they are adapters a team adds by hand, because neither is on any variant's default path.

**Two entry points, two behaviors, and the difference is stated rather than hidden:**

* **`rootstock init` prunes** (§6.4). The team names its vendor sets and the others are deleted before the directory is ever written. Nothing is forced.
* **GitHub "Use this template" cannot prune**, because a template repository instantiation runs no code. Each of the three published variant repos therefore carries **all four vendor sets**, and the cost is real and named: a Phoenix-only team's first `./gradlew build` resolves REVLib and photonlib too — slower, and two vendordeps they did not ask for. The mitigations are one documented step and one tool row, both of which must exist before the GitHub path is advertised: the template `README.md`'s **step 3** is *"delete the vendor sets you don't use — `vendordeps/REVLib.json` + `vendordeps/Rootstock-REVLib.json`, and so on, in pairs"*, and `rootstock doctor` prints a named row per unused set — *"`vendordeps/Rootstock-REVLib.json` is installed but no REVLib device is declared in `RobotIds.java`; delete it together with `vendordeps/REVLib.json`."*
* **Rejected alternative: publish one GitHub repo per (variant × vendor set) combination.** Three variants × four sets is twelve repositories to regenerate and force-push on every release, against a nine-job matrix that already costs 0.1 pw per release (§6.8). Twelve front doors is not a front door.

**Reviewer pushback:** `REVIEW.md` §10 leaves five-fixed-versus-seven-pruned open for the maintainer, noting that pruning "complicates the lock manifest and the CI matrix" and that the five-fixed model "is simpler and needs one sentence defending the extra vendordep."
**Why we keep pruning:** the five-fixed model cannot be defended in one sentence, because it is not merely carrying one extra vendordep — as written it also **omits the upstream vendor JSONs and therefore does not build** (point 2 above), which no amount of defending fixes. Once the upstream JSONs are added back the file count is 3 + 8 = 11 either way, and at that point "unconditional" means a REV-only rookie team ships two CTRE vendordeps and a PhotonVision one. The lock-manifest complication is genuinely real and is exactly one rule, written out in §6.5 rule 4; the CI-matrix complication is zero, because the matrix builds the *fully-populated* template (worst case) and a pruned fork is strictly a subset of a tree that already passed.

**The worked example mechanisms, and why these four.** Revision 1 shipped `subsystems/` empty and told teams to run `rootstock gen mechanism`. That is wrong for a front door: an empty directory teaches nothing, and the first thing a team does is copy an example. Every example below is a real, compiling, simulating mechanism that the 9-job matrix (§4.7) actually runs:

| File | What it demonstrates | Why it earns its place |
|---|---|---|
| `Elevator.java` | A full `PositionConfig`: reduction, `LinearAxis` geometry, gravity mode, **dual soft + hard limits**, a homing strategy, `atGoal` tolerance, SysId callbacks | It is the archetype the M7 wizard tunes first, and it is where unit-correctness errors actually happen. A team that reads one file should read this one. |
| `Intake.java` | A `SimpleConfig` mechanism with **structural stop-on-end** and a current-limit-based game-piece detector | The cheapest possible mechanism, so the contrast with `Elevator.java` shows what config complexity actually buys. |
| `Superstructure.java` | An enum goal machine with **two real interlocks** ("do not extend the elevator while the intake is deployed", "do not stow while holding") and default-output inversion | Interlocks are the single feature most likely to be skipped by a team who has not seen one written down. Two is the minimum number that shows composition. |
| `Drive.java` | Variant-specific (§6.3): the CTRE swerve backend, the differential backend, or absent entirely | The drivetrain is the thing a team most wants working on day one and most wants to replace by week three. |

Plus a `ControlMap` with a **mandatory `MANUAL` mode**, which is what makes `CONTROLS.md` non-empty and gives the pit a printable poster on day one.

The `build.gradle` lines a vendordep cannot supply, pre-wired:

```groovy
plugins {
  id "java"
  id "edu.wpi.first.GradleRIO" version "2026.2.1"
  id "com.peterabeles.gversion" version "1.10"
  id "dev.rootstock.gradle" version "2026.1.0"   // rootstockCheckDeploy, rootstockAssets
}

// git provenance baked into the deployed jar
project.compileJava.dependsOn(createVersionFile)
gversion {
  srcDir       = "src/main/java/"
  classPackage = "frc.robot"
  className    = "BuildConstants"
  dateFormat   = "yyyy-MM-dd HH:mm:ss z"
  timeZone     = "America/New_York"
  indent       = "  "
}

dependencies {
  // (1) AdvantageKit's @AutoLog processor, for the TEAM's own IO layers. Rootstock itself never
  //     uses @AutoLog (D24 — the generated class lands in the annotated type's package, which for
  //     a library is org.rootstock.* where a team cannot substitute it). Decision 3 did not
  //     change that, but it did mean every template project already has AdvantageKit, so wiring
  //     the processor here costs a team nothing and unblocks them on day one.
  annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitVersion"

  // (2) Rootstock's build-time replay-safety lint (M20). This line is the ONLY reason that lint
  //     is deliverable at all: a vendordep cannot add an annotationProcessor, and a template can.
  //     A team on the vendordep-only path gets the runtime tripwire and not this. Documented.
  annotationProcessor "dev.rootstock:rootstock-lint:$rootstockVersion"
}

// refuse to deploy a SIM identity to real hardware; warn (do not block) on a dirty tree
deploy.targets.roborio.artifacts.frcJava.dependsOn 'rootstockCheckDeploy'

test { useJUnitPlatform() }
```

**[UNVERIFIED]** whether AdvantageKit 26.0.2's installation requires any additional `build.gradle` stanza beyond the `annotationProcessor` line (for example a replay-source configuration or a `libraryDirectories` entry). The template is the one place where getting this wrong is invisible until a team tries to replay, so it must be read off AdvantageKit's own published installation docs at M8 and re-read at every AdvantageKit major bump — **not copied from memory.**

And the one-click safe path, so the *strict* deploy is reachable from the only UI most students use:

```jsonc
// .vscode/tasks.json  (shipped in RootstockTemplate)
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Deploy (strict)",
      "type": "shell",
      "command": "./gradlew deploy -PstrictDeploy",
      "windows": { "command": ".\\gradlew.bat deploy -PstrictDeploy" },
      "group": "build",
      "problemMatcher": [],
      "detail": "Deploy, but FAIL if the working tree is dirty. Use this for the last deploy before a match."
    },
    {
      "label": "Deploy (dirty, allowed)",
      "type": "shell",
      "command": "./gradlew deploy -PallowDirtyDeploy",
      "windows": { "command": ".\\gradlew.bat deploy -PallowDirtyDeploy" },
      "group": "build",
      "problemMatcher": [],
      "detail": "Deploy uncommitted code to a robot that has already played a match. Use at an event when you must fix the robot NOW."
    }
  ]
}
```

`Ctrl+Shift+P → Tasks: Run Task → Deploy (strict)`, or bind it to a key. The stock WPILib `Deploy Robot Code` command remains the everyday path and always works. The second task exists because `-P` properties are unreachable from the F5 workflow, and the one situation where Rootstock *does* block a dirty deploy (§7.4: this robot has been FMS-attached) must still have a one-click escape — otherwise we have rebuilt the bug we just fixed.

### 6.3 The three variants

`RootstockTemplate` ships **three** variants, selected by `rootstock init --template <name>` or by picking the matching GitHub template repo. They share every file except `subsystems/Drive.java`, the drive-related vendordeps, the Elastic layout's drive widgets, and the `RobotContainer` wiring:

| Variant | Drivetrain | Who it is for | Depends on |
|---|---|---|---|
| **`swerve`** | CTRE swerve backend, `RootstockDrive` funnel, `RootstockNav`, alliance flipping wired | The default. Most teams asking for a one-stop library have swerve or want it. | M9 |
| **`differential`** | `DifferentialBackend` through the same funnel — same `RootstockDrive` API, same alliance handling, same `OdometryReport` | **Rookie and low-budget teams**, who are exactly the audience the template exists for and are systematically the last to be served by FRC libraries. | M15 |
| **`mechanism-only`** | none — no `Drive.java`, no drive vendordeps | A team keeping their existing (often heavily-invested) drivetrain and adopting Rootstock for mechanisms, health and tuning. Makes [`DESIGN.md` §11b](../DESIGN.md)'s incremental-adoption path **first-class rather than a documentation paragraph.** | M4 |

Two consequences worth stating:

* **`differential` is not real until M15.** The `DifferentialBackend` is what makes that variant more than a README promise, and M15 sits after M12 in the build order. Before M15 the variant ships as a **documented-absent** entry — `rootstock init --template differential` fails with a named message and a date, rather than generating a project that does not drive. A front door that generates a broken project is worse than one with three doors and a sign on the third.
* **Three variants is what makes the release gate nine jobs** (§4.7), and that is the cost side of depth lever L3.

### 6.4 The "Use this template" flow

Two entry points, and both must work, because they serve different people:

**A — GitHub, for a team that has a browser and a laptop.**

1. Open `github.com/rootstock/RootstockTemplate`, click **Use this template → Create a new repository**. (Not "fork" — a fork carries upstream's history and creates a PR relationship nobody wants. Template instantiation gives a clean repo the team owns.)
2. Clone it, open in VS Code, run `WPILib: Set Team Number`.
3. `./gradlew simulateJava` — **a working robot in simulation, before any edit.** The elevator moves, the Elastic layout loads from the deploy directory, the self-test runs, the alert panel is quiet.
4. Edit `RobotIds.java` and `Constants.java`. That is the first edit, and the README says so.

**B — `rootstock init`, for a team that already has the CLI, or is offline, or wants a non-default variant.**

```
rootstock init --template swerve --team 0000 --vendors phoenix6,pathplanner --dir MyRobot2027
```

Materializes the chosen variant from a **locally cached** template bundle (so it works on venue wifi or none at all), sets the team number in `.wpilib/wpilib_preferences.json`, **prunes the vendor sets not chosen**, seeds `RobotIds.java` with a `PRACTICE`/`COMP`/`SIM` skeleton, writes `.rootstock/template.lock`, and prints the next command. One artifact, one command, smoke-tested in CI on three OSes.

**The flag is `--vendors`, plural, and it takes a comma-separated list.** `README.md` and `DESIGN.md` §11 currently write `--vendor` (singular); that is drift, not a second flag, and the plural is canonical here because the argument is a list. Correcting the two summary docs is a contract request. There is no singular alias — a silently-accepted `--vendor phoenix6,revlib` that only honors the first entry is worse than an unrecognized-flag error.

**Pruning, specified exactly, because "deletes the vendor sets you don't pick" is not a specification (§6.2.1):**

* **Legal values** are the four vendor-set names: `phoenix6`, `revlib`, `photonvision`, `pathplanner`. An unrecognized value is a hard error naming the four, not a warning — a typo that silently produces a project with no motor vendor is a half-hour of a rookie's life.
* **Pruning deletes both files of an unselected set**, always together: `<Vendor>.json` and `Rootstock-<Vendor>.json`. Deleting only one leaves either an adapter whose `requires` cannot resolve or an orphan vendordep, and both are worse than deleting neither.
* **`init` never prunes the three unconditional files** (§6.2.1). `--vendors` has no syntax that could name them.
* **Defaults when `--vendors` is omitted**, so the common case needs no flag: `swerve` and `differential` → `phoenix6,pathplanner`; `mechanism-only` → `phoenix6`. The default is printed in the init summary, so a team that wanted REV finds out in the terminal rather than at the first build failure.
* **`--vendors` with an empty list is legal** and yields the three unconditional files: a valid, buildable, motorless project. It is what a team porting an existing drivetrain wants, and it is what `mechanism-only --vendors ''` means.
* **Pruning happens before the lock file is written**, which is what makes §6.5 rule 4 work: a pruned file was never in this fork's manifest, so it can never be reported as `MISSING`.

**What `rootstock init` must never do:** rewrite the team's existing repo, run `git init` over an existing `.git`, or silently overwrite a non-empty target directory. It refuses, names the directory, and exits non-zero. **It must also never leave a partially-generated tree behind**: generation is write-to-temp-then-rename, so an interrupted or refused `init` leaves either nothing or a complete project. This is also what the seventh CI job asserts for the documented-absent `differential` variant before M15 (§4.7).

### 6.5 How the template pins the library version — `.rootstock/template.lock`

The lock file is the single mechanism that makes both the update path (§6.6) and the drift path (§6.7) possible. It is **committed to the team's repo**, human-readable, and never edited by hand.

```jsonc
{
  "schema": "rootstock.template.lock/1",
  "templateVariant": "swerve",
  "templateVersion": "2026.1.0",       // which template generation this fork came from
  "libraryVersion":  "2026.1.0",       // dev.rootstock:rootstock — what the vendordeps pin
  "createdAt": "2026-10-04T18:20:11Z",
  "lastUpdate": { "command": "rootstock update --library 2026.1.0", "at": "2026-10-04T18:20:11Z" },

  // Which vendor sets (§6.2.1) this fork actually has, and which `rootstock init` pruned.
  // BOTH lists are recorded: `vendorSets` is what must resolve, `prunedVendorSets` is the
  // difference from the generated template. Recording only the first would make a pruned
  // fork indistinguishable from a corrupted one.
  "vendorSets":       ["phoenix6", "pathplanner"],
  "prunedVendorSets": ["revlib", "photonvision"],

  // The exact matrix CI compiled this template against. `rootstock doctor` compares the RESOLVED
  // dependency graph to this and reports any difference by name. This is the antidote to the
  // problem decision 3 made worse: one more third-party pin, and the one most likely to move.
  "matrix": {
    "wpilib": "2026.2.2",
    "gradlerio": "2026.2.1",
    "java": "17",
    "advantagekit": "26.0.2",
    "phoenix6": "26.1.3",
    "pathplanner": "2026.1.2"
  },

  // SHA-256 of every file the TEMPLATE owns, as generated. Files not listed here are the team's
  // and are never touched, never diffed, and never reported. This list is what makes drift
  // classification a fact rather than a guess.
  "files": {
    "build.gradle":                                   "sha256-3a7f…",
    "settings.gradle":                                "sha256-91c2…",
    ".vscode/tasks.json":                             "sha256-c40e…",
    ".github/workflows/build.yml":                    "sha256-77ab…",
    "src/main/java/frc/robot/Robot.java":             "sha256-e11d…",
    "src/main/java/frc/robot/RobotContainer.java":    "sha256-b502…",
    "src/main/java/frc/robot/subsystems/Elevator.java": "sha256-6d3c…",
    "src/main/deploy/elastic-layout.json":            "sha256-af90…",
    "docs/UPDATING.md":                               "sha256-0b45…",

    // Vendordeps ARE template-owned and ARE hashed — they are the pin, so an edited one must
    // show up. Only the sets this fork kept appear; `revlib` and `photonvision` were pruned at
    // init and are therefore absent from this map rather than present-and-missing. Rule 4 below.
    "vendordeps/Rootstock.json":                     "sha256-1e88…",
    "vendordeps/AdvantageKit.json":                   "sha256-4c17…",
    "vendordeps/WPILibNewCommands.json":              "sha256-9b31…",
    "vendordeps/Phoenix6-frc2026-latest.json":        "sha256-d05a…",
    "vendordeps/Rootstock-Phoenix6.json":            "sha256-72fe…",
    "vendordeps/PathplannerLib.json":                 "sha256-a6c4…",
    "vendordeps/Rootstock-PathPlanner.json":         "sha256-38b9…"
  }
}
```

**Four rules that keep this honest:**

1. **The vendordeps are the actual pin; the lock file records it.** `libraryVersion` is not authoritative on its own — `vendordeps/Rootstock*.json` are what Gradle resolves. `rootstock doctor` fails loudly if the two disagree, because a lock file that has drifted from the vendordeps is worse than no lock file.
2. **`BuildConstants.java`, `build/`, `.gradle/` and anything gitignored are never in `files`.** A generated file that hashes differently on every build would make every fork permanently "drifted" and would train teams to ignore the report within a week — the same failure mode as the seventeen-red-alerts problem in §8.2, applied to tooling.
3. **A fresh generation must hash clean.** The 9-job release matrix asserts it (§4.7 step 4). If the generator and the manifest ever disagree, drift classification is meaningless from the first minute.
4. ***(new)*** **A file `rootstock init` pruned is not in `files`, and pruning is therefore not drift.** This is the one rule the vendor-set model costs (§6.2.1), and it is worth writing out because getting it wrong makes the drift report useless for every fork that ever picked a vendor:
   * The manifest is written **after** pruning, over the tree that actually exists. A pruned file was never a member, so `rootstock doctor --template` cannot classify it `MISSING` — there is nothing to miss.
   * `prunedVendorSets` is what makes the difference *auditable* rather than merely invisible: `rootstock doctor --template` prints one informational row, `pruned at init: revlib, photonvision`, so a reader of the report can tell a deliberate choice from a deleted file.
   * **`rootstock update --template` never re-adds a pruned set.** An upstream template that gains a file inside `revlib` classifies as `ADDED` **only** for forks whose `vendorSets` contains `revlib`; for everyone else it is not offered and not mentioned. A template update that silently reinstates a vendor the team removed is exactly the forced-vendor failure §6.2.1 exists to avoid, arriving six months later.
   * **Adding a set back is a first-class command, not a manual copy:** `rootstock update --add-vendors revlib` materializes both files of the pair at the pinned matrix versions, adds them to `files`, and removes the entry from `prunedVendorSets`. Without it, a team that starts Phoenix-only and buys a NEO in week 3 has to hand-assemble a version pair — the exact "combination we have never compiled" problem in §4.4 item 3.
   * **The 9-job matrix builds the fully-populated template** (all four sets), so every pruned fork is a subset of a tree CI already proved green. Pruning cannot introduce a combination that was not tested; it can only remove one.

### 6.6 Taking a library patch release from inside a fork

This is the flow that justifies shipping a library at all, and it must be a single command:

```
rootstock update --library 2026.1.3
```

What it does, in order:

1. Rewrites the `version` field in **every** `vendordeps/Rootstock*.json` **that this fork actually has** — i.e. the sets in `.rootstock/template.lock`'s `vendorSets` — **as one atomic set.** Mismatched adapter versions is the single most likely self-inflicted breakage: a team bumping `Rootstock.json` by hand and forgetting `Rootstock-Phoenix6.json` gets a `NoSuchMethodError` at run time, in a match. It does **not** create files for pruned sets (§6.5 rule 4) — `rootstock update --add-vendors <name>` is the command for that, and it is a separate, deliberate act.
2. Checks the target version's `requires` floor for **AdvantageKit** and, if the pinned `AdvantageKit.json` is below it, stops and says so with the upstream install URL. **This step exists only because of decision 3** and it is the most likely reason an in-season update fails.
3. Updates `.rootstock/template.lock`'s `libraryVersion`, `matrix` and `lastUpdate`.
4. Re-resolves (`./gradlew --refresh-dependencies build`) and runs `rootstockCheckDeploy`.
5. Prints the **changelog delta** between the pinned and target versions — not the whole changelog, the delta — with any entry marked `BEHAVIOR` highlighted.

**It never touches a file under `src/`.** That is the guarantee that makes it safe to run in week 4 of build season, and it is the whole reason the library-plus-template split is worth its cost. Sixty seconds, no merge, no conflict resolution. It is also exactly what the R15 support policy tells a stuck team to do first.

**Rollback is the same command with the old version**, and because nothing under `src/` moved, it actually works.

### 6.7 Drift, and the documented update procedure

The fork diverges from the template the moment a team edits `RobotContainer.java` — which is on day one, by design. **Drift is normal, expected, and is classified rather than prevented.**

```
rootstock doctor --template
```

compares the fork against the template at the pinned `templateVersion` using the `files` manifest, and classifies every template-owned file:

| Classification | Meaning | What the team should do |
|---|---|---|
| `UNCHANGED` | hash matches the generated original | nothing; `rootstock update --template` can merge these automatically |
| `MODIFIED-BY-TEAM` | hash differs | nothing — **this is correct and expected.** It only means upstream changes to this file arrive as a patch to apply by hand. |
| `MISSING` | template-owned file deleted | nothing, unless it is one the library needs (`disabled.txt`, the deploy dir), in which case the row says so |
| `ADDED` | a new template-owned file exists upstream that this fork predates | offered by `rootstock update --template` |

Output is a **table, not a diff dump**, and it is a **report, never a gate**. A team must be able to ignore it forever and keep working; the day `rootstock doctor --template` blocks a build is the day it gets deleted from CI. `rootstock doctor --bundle` embeds the drift table, so a bug report arrives already saying which template-owned files were modified — no back-and-forth.

```
rootstock update --template 2026.2.0
```

three-way merges **only** the files classified `UNCHANGED`. For every `MODIFIED-BY-TEAM` file it writes the upstream patch to `docs/template-drift/<file>.patch` together with a one-line plain-English explanation of what the change does and why, and leaves the team's file untouched. **Template updates are opt-in and never automatic.**

`docs/UPDATING.md` ships **inside the fork**, so it is readable at an event with no internet, and it contains exactly two procedures, in this order:

> **1. I need a Rootstock bug fix (this is what you almost always want).**
> `rootstock update --library <version>` · sixty seconds · touches no file you wrote · rollback is the same command with the old version.
>
> **2. I want new template scaffolding (do this in the offseason, never in week 4).**
> `rootstock doctor --template` to see what diverged, then `rootstock update --template <version>`, then apply anything in `docs/template-drift/` by hand. **This can break your robot code. Do not run it between kickoff and your last event.**

The ordering is the point. A team in trouble reads the first paragraph and stops.

**Graduation is also documented here**, because a front door that cannot be walked back out of is a trap: `docs/removing-rootstock.md` and the `RipOutTest` CI fixture (R15) apply to template forks exactly as they do to vendordep installs, and the runtime kill switch (`src/main/deploy/rootstock/disabled.txt`) ships in the template *empty and already present*, so a team that needs it does not have to learn it exists at the worst possible moment.

### 6.8 The maintenance cost of running a template — stated, not discovered

The template is not free and the cost **recurs forever**:

* **Every library release regenerates `RootstockTemplate` and re-pins the version set.** This cannot be skipped: a template pinned to a version that no longer exists is worse than no template, and a template pinned to a version we never compiled it against is worse still.
* **Every library release runs the 9-job template CI matrix before the tag** (§4.7). It is a release gate, not a post-release check.
* **Every vendor bump — Phoenix 6, REVLib, AdvantageKit, WPILib, GradleRIO — requires a regeneration and a matrix run whether or not the library changed.** AdvantageKit is now on that list because of decision 3, and it is the entry most likely to move mid-season.
* **The template is a second thing to get right during the M12 port** (§5.3 Part C.1): three variants regenerated onto `org.wpilib.*`, nine jobs green, lock matrix rewritten.

Budgeted: **+1.5 pw one-time**, counted in this domain's §16 total and in [`ROADMAP.md` §1](../ROADMAP.md)'s arithmetic, plus **~0.1 pw per release ongoing, forever.** At the in-season cadence the support policy implies — a patch every two to three weeks between January and April — that is **~0.5 pw per season of pure template tax**, which is a real fraction of a solo season's capacity and is counted in the 2–4 pw/calendar-year carrying cost.

**Support surfaces:** a public, search-indexed Chief Delphi thread + GitHub Discussions are canonical, **from M24 and not before** — nothing is announced while the only consumers are 8793 and 9143. Docs readable without an account. Discord is supplementary only — it is blocked in at least one active FRC country.

---

## 7. Robot Identity and Multi-Robot Config

### 7.1 The mechanism — and why the obvious answer is wrong

The obvious answer, "put a `robot-id.txt` in the deploy directory," **does not work**: the deploy directory is deployed *from the laptop*, so every robot receives the same file. Identity must live on the robot.

Resolution order (first match wins). Each step exists to cover a failure of the one before it:

1. **`Platform.persistentDir()/rootstock/robot-id`** — a file on the robot's own filesystem (`/home/lvuser/...` on roboRIO, backed by `Filesystem.getOperatingDirectory()` inside the facade). Survives redeploy; wiped by a reimage. **Rootstock writes it for you** from a dashboard command, so nobody ever SSHs into a robot. *Primary, and specifically because it is the only mechanism guaranteed to exist on SystemCore.*
2. **`Platform.comments()`** — the roboRIO web-dashboard "comments" field, matched case-insensitively against registered substrings. Student-editable through a web page, no code, no SSH. **[UNVERIFIED]** on SystemCore, which is exactly why the read is behind the facade and returns `Optional`.
3. **`Platform.serialNumber()`** — exact match against a registered table. Zero setup once recorded; the string is in the log the first time you boot. **[UNVERIFIED]** on SystemCore.
4. **`Platform.isSimulation()`** → `RobotId.SIM`.
5. **Declared fallback**, plus an `ERROR` / `BLOCKS_MATCH` alert naming every strategy that was tried and what it saw. This is one of the five the alert budget (§8.2.2) reserves: a robot running an unknown identity's constants is running the wrong gearing and the wrong limits, and that genuinely means do not take the field.

All five read through `org.rootstock.core.compat.Platform`; `org.rootstock.core.identity` contains no `edu.wpi.first.wpilibj` import at all. When `getComments()` disappears on SystemCore, strategy 2 becomes `Optional.empty()` in one file and the chain degrades to strategy 1 with a named alert — it does not fail to compile.

We do **not** use a DIO jumper: it consumes a channel, it can fall out, and it is the one mechanism the 2027 removal list makes least attractive to bet on.

### 7.2 API

```java
package org.rootstock.core.identity;

/** Teams extend this set by adding enum constants; nothing in Rootstock hardcodes the members. */
public enum RobotId { COMP, PRACTICE, PROTO, SIM }
```

```java
package org.rootstock.core.identity;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class RobotIdentity {

  /** Declarative registration. Call exactly once, before any config is read. */
  public static Builder configure() { return new Builder(); }

  /** Resolved identity. Throws IllegalStateException if configure() was never called. */
  public static RobotId current();

  /** How it was resolved — logged to /Rootstock/Meta/RobotIdSource and shown in the pit. */
  public static String source();

  /** Pick a value per robot. Missing key -> IllegalArgumentException naming the robot and the caller. */
  public static <T> T pick(Map<RobotId, T> byRobot);

  /** Pick with a base value and per-robot overlays applied on top. */
  public static <T> T overlay(T base, Map<RobotId, Function<T, T>> overrides);

  /** Write the persistent id file on this robot. Bind to a pit button; requires MatchContext.isDiagnostics(). */
  public static edu.wpi.first.wpilibj2.command.Command assignCommand(RobotId id);

  public static final class Builder {
    /** Match Platform.serialNumber() exactly. */
    public Builder bySerial(String serial, RobotId id);
    /** Case-insensitive substring of Platform.comments(). */
    public Builder byComment(String substring, RobotId id);
    /** Contents of Platform.persistentDir()/rootstock/robot-id, trimmed, case-insensitive. On by default. */
    public Builder byPersistentFile(boolean enabled);
    /** Identity used when Platform.isSimulation(). Default SIM. */
    public Builder simIs(RobotId id);
    /** Used when nothing matched. Also raises an ERROR / BLOCKS_MATCH alert. */
    public Builder fallback(RobotId id);
    public void done();
  }
}
```

### 7.3 Per-robot constant overlays

Config objects are immutable records with `withX()` copy methods ([`design/01`](01-core-mechanisms.md)'s convention). Overlays are therefore just functions, and the compiler checks them — no JSON, no reflection, no string keys:

```java
// frc/robot/robots/RobotIds.java
public final class RobotIds {
  public static void register() {
    RobotIdentity.configure()
        .byPersistentFile(true)
        .byComment("practice", RobotId.PRACTICE)
        .bySerial("031b7f8e", RobotId.COMP)
        .simIs(RobotId.SIM)
        .fallback(RobotId.COMP)     // fail SAFE: a mystery robot behaves like the comp bot
        .done();
  }

  // Base config once; only the deltas are per-robot.
  public static final ElevatorConfig kElevator = RobotIdentity.overlay(
      ElevatorConfig.base()
          .withGearing(12.0)
          .withMaxHeight(Inches.of(58))
          .withStatorLimit(Amps.of(60)),
      Map.of(
          RobotId.PRACTICE, c -> c.withMaxHeight(Inches.of(54)).withStatorLimit(Amps.of(40)),
          RobotId.PROTO,    c -> c.withGearing(9.0)));
}
```

Every resolved config is registered with `ConfigRegistry` (§11.2) and logged as a struct into the WPILOG at boot, so "what config did this robot actually run?" is answerable from the log alone — the introspectability win of JSON config without giving up the compiler.

### 7.4 The build-time deploy gate

6328's real safety comes from build-time gates, not from runtime checks, and the gate is the half nobody copies. We ship it in the template — but with **two different severities, because the two checks have opposite failure economics.**

**The design error we are correcting.** A single hard gate on "working tree is dirty" bricks the robot at an event. The VS Code `WPILib: Deploy Robot Code` command and the F5 workflow — the only deploy interfaces most students have ever used — **cannot pass a `-P` property**. So at 8:40 on Saturday, a student who changes one soft limit to stop the arm hitting the chassis cannot deploy the fix at all from the interface they know, and the error message tells them to run git, in a pit, on venue wifi, while the mentor is at the field. That inverts `DESIGN.md` §3 principle 12, *degrade, never crash*: it converts a provenance nicety into a robot-is-dead failure. A library that stops a team from fixing their robot has done more damage than the untraceable commit ever would.

**The graded policy.**

| Check | Default | Rationale |
|---|---|---|
| `fallback(RobotId.SIM)` | **HARD FAIL, always, no override** | This build genuinely cannot produce a working robot. Deploying it wastes the match either way, so blocking costs nothing. |
| Dirty working tree | **WARN + deploy + stamp + persistent robot alert** | The fix reaches the robot; provenance is preserved by other means. |
| Dirty working tree, `-PstrictDeploy` | HARD FAIL | CI, and the team's own pre-event checklist. One click in VS Code (§6.2). |
| Dirty working tree, target has ever been FMS-attached, no `-PallowDirtyDeploy` | HARD FAIL | The robot itself says it has played a match. Now the burden of proof flips. |

```groovy
// applied by the template; task class lives in rootstock's gradle plugin jar
tasks.register('rootstockCheckDeploy') {
  doLast {
    def src = file('src/main/java/frc/robot/robots/RobotIds.java').text

    // ---- 1) HARD, unconditional. A SIM fallback cannot produce a working robot. ----
    if (src =~ /\.fallback\(\s*RobotId\.SIM\s*\)/) {
      throw new GradleException(
        "rootstockCheckDeploy: RobotIds.fallback() is RobotId.SIM. A real robot would run simulation " +
        "config (wrong gearing, wrong limits, wrong offsets). Change it to COMP or PRACTICE in " +
        "src/main/java/frc/robot/robots/RobotIds.java. There is no override for this one.")
    }

    // ---- 2) GRADED. Dirty tree warns and deploys; it blocks only when you asked it to. ----
    def dirty = 'git status --porcelain'.execute().text.trim()
    if (!dirty) return

    // Has this robot ever been FMS-attached? Written by MatchContext into the persistent store
    // and pulled down by `rootstock sync` into build/rootstock/target-state.json. Absent == false,
    // because a missing file must never block a deploy.
    def stateFile = file("$buildDir/rootstock/target-state.json")
    def everFms = stateFile.exists() && new groovy.json.JsonSlurper().parse(stateFile).everFmsAttached

    def strict  = project.hasProperty('strictDeploy')
    def allowed = project.hasProperty('allowDirtyDeploy')

    if (strict || (everFms && !allowed)) {
      throw new GradleException(
        "rootstockCheckDeploy: working tree is dirty, so this build cannot be traced to a commit:\n" +
        dirty + "\n" +
        (strict ? "You asked for -PstrictDeploy.\n"
                : "This robot has been FMS-attached, so Rootstock assumes you are at an event.\n") +
        "Commit, or re-run with -PallowDirtyDeploy.\n" +
        "In VS Code: Tasks: Run Task -> 'Deploy (dirty, allowed)'.")
    }

    logger.warn("""
      ============================================================
      rootstockCheckDeploy: DEPLOYING UNCOMMITTED CODE
      ${dirty}
      This build is not in git. BuildConstants.DIRTY = 1, the robot
      will raise a persistent warning, and /Rootstock/Meta/GitDirty
      will be true. Commit when you are off the field.
      ============================================================""".stripIndent())
  }
}
```

Runtime half of the same policy (this is where the provenance actually gets preserved, and it costs the team nothing at 8:40 on Saturday):

* `BuildConstants.DIRTY == 1` → `/Rootstock/Meta/GitDirty = true`, always, in the shop and at an event.
* A **persistent `WARNING`, `PIT_ONLY`** alert: *"This code is not in git (3 files changed). It cannot be reproduced from a commit."* It is `PIT_ONLY` by §8.2's triage rule — a dirty tree never stops a robot from playing a match, so it must never occupy one of the three driver-visible slots.
* `DeployInfo.summary()` prints `main@a1b2c3d (dirty)` on the pit display and in the log header, so the fact is on screen without being in the way.

`MatchContext` writes `{"everFmsAttached": true, "lastEvent": "CURIE", "at": "..."}` into `Platform.persistentDir()/rootstock/target-state.json` on the FMS-attach rising edge; `rootstock sync` (and the deploy task's pre-step, best-effort with a 500 ms timeout) copies it to `build/rootstock/`. If the robot is unreachable, the file is stale or missing, and the deploy proceeds — **an unreachable robot must never be a reason to refuse to fix the robot.**

A companion `rootstockCheckPullRequest` task (CI only) fails a PR unless tuning mode is off and the fallback is the comp bot. CI also always passes `-PstrictDeploy`, so the "never untraceable" property is enforced exactly where enforcement is free.

---

## 8. Alerts and Self-Test

### 8.1 Why this exists

WPILib ships `Alert` — the *display* primitive — and zero checks. Every check (CAN utilization, motor temp, sticky faults, brownout, encoder sanity, 5 V rail faults) must be written by the team. The two best public implementations are 3061-lib's `FaultReporter` and Team 135's Consul, and both are locked inside team repos: `FaultReporter`'s hardware coverage is a hardcoded overload list (adding a device means editing the class), and Consul requires `extends SubsystemChecker` — inheritance that is actively hostile to Commands v3.

We generalize both with **an open SPI plus adapters**, so a new vendor is a new artifact, never an edit to core.

### 8.2 Alerts — severity is not the same question as "can we play?"

**The failure mode we are designing against is not a missing alert. It is seventeen simultaneous expected ones.**

Count the alert sites on a realistic robot. **Each figure below is the count of *named conditions in a specific document*, and the multipliers are shown, because this paragraph previously carried a resurrected withdrawn claim and an undercounted total:**

| Source | Distinct named conditions | Multiplier on a realistic robot |
|---|---|---|
| This document's built-in monitors, §8.5 | **11 alert conditions**, from **7 monitor types** | ×1 (robot-wide) |
| [`design/01`](01-core-mechanisms.md) §1.3's automatic per-mechanism alerts | **14** (`motor-disconnected`, `device-reset`, `follower-disagrees`, `over-temperature`, `config-apply-failed`, `absolute-encoder-disconnected`, `absolute-encoder-disagrees`, `homing-timed-out`, `soft-limit-clamped-setpoint`, `goal-unreachable`, `stalled`, `control-location-downgraded`, `unknown-setpoint`, `internal-routing-fault`) | **× mechanisms** |
| [`design/03`](03-vision.md)'s `RejectReason` taxonomy | **19** non-`ACCEPTED` values, counted per camera | **× cameras** |
| [`design/01`](01-core-mechanisms.md) §5.6's tier-2 config warnings, plus [`design/05`](05-drivetrain-auto.md)'s drive alerts | unbounded — one per suspicious config field | — |

A four-mechanism robot with swerve and two cameras: **11 + (4 × 14) + (2 × 19) = 11 + 56 + 38 = 105 distinct alert sites**, before tier-2 config warnings and before the drive layer's own. An earlier draft of this paragraph said *"ten built-in monitors"* and *"60+ alert sites"*. **Both were wrong, in opposite directions.** "Ten" is the withdrawn *"ten competition-day health checks"* claim ([`DECISIONS.md`](../DECISIONS.md) line 533, [`DESIGN.md`](../DESIGN.md) revision-2 changelog row 2) resurfacing verbatim in kind — the canonical figures are **seven monitor types, eight registered health slices, eleven alert conditions**, and `BuiltinMonitorCountTest` (§8.5) exists to stop precisely this. "60+" was an undercount by roughly 45.

**And the argument survives the correction with more force, not less.** On a half-built week-2 robot a completely normal simultaneous set is: 4× motor-disconnected, 2× absolute-encoder-disconnected, 3× kG-is-zero, 4× homing-not-done, git-dirty, CAN-utilization, 2× camera-not-seen = 4 + 2 + 3 + 4 + 1 + 1 + 2 = **seventeen red rows in Elastic's Alerts widget, every one of them expected.**

Students learn within a week that the alert panel is noise. At that moment principle 3 — zero-mystery debugging — is dead, and the one real alert on Saturday morning is indistinguishable from the fifteen they have been scrolling past since January. Severity does not fix this: "the elevator encoder is unplugged" and "you haven't tuned kG yet" are both honestly `ERROR`, and only one of them means don't take the field.

So every alert declares a **second, orthogonal axis**: does this mean the robot should not take the field?

```java
package org.rootstock.core.alert;

import edu.wpi.first.wpilibj.Alert.AlertType;

/** Ordered severity. Maps 1:1 to WPILib AlertType; exists so we can do rollup and comparison. */
public enum Severity {
  INFO, WARNING, ERROR;
  public AlertType toWpi();
  public static Severity from(AlertType t);
}
```

```java
package org.rootstock.core.alert;

/**
 * Orthogonal to Severity, and REQUIRED at every call site. There is no default and no
 * single-argument overload: the author of an alert is the only person who knows the answer,
 * and forcing them to type it is the entire mechanism.
 *
 * Deliberately an enum and not the `boolean blocksMatch` a reviewer asked for. `alert(..., true)`
 * at a call site is unreadable and gets copy-pasted wrong; `BLOCKS_MATCH` cannot be. Same
 * requirement, same enforcement, better call sites — this library is read by students.
 */
public enum MatchImpact {
  /** The robot should not take the field like this. Eligible for the driver mirror. */
  BLOCKS_MATCH,
  /** True, worth fixing, not match-stopping. Pit tab only. Never reaches the driver. */
  PIT_ONLY
}
```

**Reviewer pushback:** the review asked for a required `boolean blocksMatch` parameter on
`Alerts.error/warning/info(...)`.
**Why we keep this:** we adopt the requirement in full — required at every call site, no
default, no single-argument overload, and the same five downstream consequences (driver mirror,
`Ready` rollup, CI budget gate, `Health.expectAbsent()`) — but as an enum rather than a `boolean`. A
`boolean` produces call sites that read `Alerts.error("Arm", "leader disconnected", true)`,
which is unreadable at the point it matters most and gets copy-pasted with the wrong literal by the
exact audience this library is for. `MatchImpact.BLOCKS_MATCH` cannot be copy-pasted wrong and
cannot be silently inverted. The enforcement mechanism — you cannot compile without answering the
question — is identical; only the legibility differs, and this library is read by students.

**The facade class is `Alerts`; the handle type is `RootstockAlert`.** [`DESIGN.md` §5.2 D10](../DESIGN.md) names `Alerts.error/warning/info(group, text, MatchImpact)` as the canonical surface and D12 redirects the deleted `Rootstock.alerts()` god-object accessor to it. An earlier draft of this section hung the statics off `RootstockAlert` itself, which is a fourth spelling of a thing D10 already named once. Corrected:

```java
package org.rootstock.core.alert;

import java.util.function.BooleanSupplier;

/**
 * THE alert facade (D10, D12). Every alert in every Rootstock domain is created here.
 *
 * There is no two-argument overload of error() or warning(), and there never will be. The
 * MatchImpact argument is the entire mechanism of §8.2: the author of an alert is the only
 * person who knows whether it means "do not take the field", and the compiler is what makes
 * them say so. An overload that guessed a default would delete the feature.
 */
public final class Alerts {
  private Alerts() {}

  public static RootstockAlert error(String group, String text, MatchImpact impact);
  public static RootstockAlert warning(String group, String text, MatchImpact impact);

  /** INFO is PIT_ONLY by definition — an informational alert cannot stop a match. */
  public static RootstockAlert info(String group, String text);

  public static RootstockAlert of(String group, String text, Severity severity, MatchImpact impact);

  /** Self-driving: evaluated by the alert registry on its round-robin slice. Preferred form. */
  public static RootstockAlert when(
      String group, String text, Severity s, MatchImpact impact, BooleanSupplier cond);
}
```

```java
package org.rootstock.core.alert;

import java.util.List;
import java.util.function.BooleanSupplier;
import edu.wpi.first.units.measure.Time;

/**
 * A registered alert HANDLE, returned by the Alerts facade. Wraps a WPILib Alert (so stock
 * dashboard widgets work) and adds registry membership, cycle-limited text updates,
 * rising-edge notification, stickiness and latching.
 */
public final class RootstockAlert implements AutoCloseable {

  public RootstockAlert set(boolean active);
  public boolean isActive();
  public MatchImpact impact();
  public Severity severity();

  /**
   * Demote to PIT_ONLY and INFO because this hardware is knowingly not installed.
   * Set by Health.expectAbsent(healthName, reason) (§8.3.1) — not by hand at the alert site.
   * (Was "Mechanism config's .expectAbsent()"; that design/01 builder method was withdrawn
   * 2026-08-08 after three revisions with no adopter — see the note under §1's contract table.)
   */
  RootstockAlert demoteExpectedAbsent(String reason);

  /** Live text, e.g. "Arm leader at 84 C (limit 70 C)". Coalesced to one update per 25 cycles. */
  public RootstockAlert text(String text);

  /**
   * Sticky in the device sense: this condition was latched by hardware across boots and must
   * survive being cleared. D10 folds design/04's RootstockFaults in here — "a sticky fault is
   * RootstockAlert.sticky(true)" — so there is one fault type in the library, not two.
   */
  public RootstockAlert sticky(boolean b);
  public boolean isSticky();

  /** Once true, stays true until clearLatched(). For transient events like a brownout. */
  public RootstockAlert latching();
  public RootstockAlert clearLatched();

  /**
   * Suppress until the condition has held this long. Kills flapping alerts.
   * Converted ONCE to a whole number of loops via Clock.cyclesFor(duration) and counted in
   * cycles thereafter, so a replayed log debounces on exactly the cycles the real robot did.
   */
  public RootstockAlert debounce(Time duration);

  /** Send an Elastic notification on the rising edge only (never in periodic). Default true for ERROR. */
  public RootstockAlert notifyDriver(boolean enabled);

  /** Escape hatch. */
  public edu.wpi.first.wpilibj.Alert raw();

  @Override public void close();
}
```

```java
package org.rootstock.core.alert;

import java.util.List;
import java.util.Optional;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/** Answers the one question nobody can currently answer: is the robot OK right now? */
public final class AlertRegistry {
  public static void register(RootstockAlert a);
  public static List<RootstockAlert> active();
  public static Optional<Severity> worst();

  /** Active alerts with impact == BLOCKS_MATCH, ranked: severity desc, then registration order. */
  public static List<RootstockAlert> blocking();

  /**
   * NO active BLOCKS_MATCH alert. This is "this robot can play a match", NOT "nothing anywhere
   * is imperfect". Drives LEDs, the pit display, and the self-test gate.
   */
  public static boolean matchReady();

  public static Trigger anyError();
  public static Trigger anyWarning();
  public static Trigger anyBlocking();

  /** Bridge every rising-edge activation to an Elastic notification. Called by RootstockRobot. */
  public static void bridgeToDashboard();
}
```

#### 8.2.1 The driver mirror — three rows, hard cap

`/Rootstock/Driver/` (the D22 driver mirror) shows **only `BLOCKS_MATCH` alerts, capped at three**, ranked by severity then registration order, plus a rollup row when there are more:

```
/Rootstock/Driver/Blocking      string[]   at most 3 entries
/Rootstock/Driver/BlockingMore  string     "+4 more - see the pit tab"  (empty when none)
/Rootstock/Driver/Ready         boolean    == AlertRegistry.matchReady()
```

Everything else — every `PIT_ONLY` alert, every INFO, every per-camera reject counter — lives on the pit tab only, at `/Rootstock/Health/<Source>/`. The shipped `elastic-layout.json` binds the driver tab's Alerts widget to `/Rootstock/Driver/` and the pit tab's to `/Rootstock/Health/`. Three rows is a number a driver can read in the two seconds between "robot's on the field" and "hands on the sticks"; seventeen is a wall of red that trains people to ignore it.

**Ownership, because D22 splits this seam.** The *policy* above — only `BLOCKS_MATCH`, capped at three, ranked severity-then-registration-order, with a rollup row — is this document's. The *transport* under `/Rootstock/Driver/`, the three topics' schema and the `Ready` rollup's publication are [`design/04`](04-telemetry-replay-viz.md)'s (D22). Nothing under `/Rootstock/Driver/` is ever byte-budget-demotable; that is a build-failing lint in `design/04` and it is load-bearing here, because a demoted driver row is a driver row that silently stops updating.

#### 8.2.2 `AlertBudgetTest` — a CI gate on the noise floor

Budgets that are not enforced are aspirations. `rootstock-testkit` ships the test and the template runs it:

```java
package org.rootstock.testkit;

/**
 * Boots the §13 example robot headless in sim with ALL hardware absent — the worst honest
 * case, and exactly what a week-2 robot looks like — and asserts the driver is not buried.
 */
class AlertBudgetTest {

  @Test
  void bareRobotDoesNotFloodTheDriver() {
    var robot = HeadlessRobot.boot(RobotContainer::new);
    robot.tick(250);                                    // 5 s at 50 Hz; debounces have expired

    var blocking = AlertRegistry.blocking();
    assertThat(blocking)
        .withFailMessage(() ->
            "Alert budget exceeded: " + blocking.size() + " BLOCKS_MATCH alerts with no hardware "
          + "present. Each one of these claims the robot cannot play a match:\n  "
          + blocking.stream().map(RootstockAlert::toString).collect(joining("\n  "))
          + "\nIf one of these is really a 'a programmer should look at this' alert, mark it "
          + "MatchImpact.PIT_ONLY. If the hardware is knowingly not installed, call "
          + "Health.expectAbsent(healthName, reason).")
        .hasSizeLessThanOrEqualTo(5);
  }
}
```

Five, not zero: a robot with no hardware genuinely cannot play a match, and "drive: no motors responded" *should* block. Five is the number of distinct root causes a student can hold in their head. When a new built-in alert pushes it to six, the failure message tells the library author which axis they got wrong — which is the same zero-mystery contract we make to users, applied to ourselves.

**Five and three are two different numbers answering two different questions, and conflating them is what produced two contradictory CI gates.** [`DESIGN.md` §5.2 D10](../DESIGN.md) states the budget as **3**; this document states it as **5**, five times, with the argument above. `REVIEW.md` M14 adjudicated in favor of **5** on the grounds that this document owns the test and argues its number while `DESIGN.md`'s is asserted. Adopted, and the distinction is written out here so it cannot collapse again:

| Number | What it governs | Where it lives | Why that value |
|---|---|---|---|
| **5** | The **CI budget**: how many `BLOCKS_MATCH` alerts may be *simultaneously active* on the bare example robot with no hardware present | `AlertBudgetTest`, above; §4.7 release-gate step 3 | A hardware-free robot *honestly* cannot play a match. Zero would force us to lie about the week-2 case; a sixth means the library has mis-classified something. |
| **3** | The **display cap**: how many `BLOCKS_MATCH` alerts the driver tab *shows*, with a `+N more` rollup for the rest | `/Rootstock/Driver/Blocking`, §8.2.1 | What a human reads in the two seconds before hands go on the sticks. |

The two never need to be equal, and requiring them to be would mean either a driver panel that scrolls or a CI gate that fails on an honest robot. `DESIGN.md` §5.2 D10's "more than 3" clause needs to read "more than 5", with the display cap of 3 left exactly as it is; that is a contract request.

`AlertRegistry` publishes `/Rootstock/Health/Summary/{Worst, ActiveCount, BlockingCount, MatchReady}` and the per-group detail. It is evaluated on the round-robin health slice (§8.3), never at 50 Hz and never on a wall clock.

### 8.3 Health monitoring — slice it, and count cycles not milliseconds

DESIGN.md §3 principle 10 cites Team 135's measurement of **~10 ms/loop for full fault checking** and answered it with "4 Hz (not 50 Hz) health polling." That answer is wrong twice, and both errors are load-bearing.

**Error 1 — it converts a sustained tax into a spike.** Polling everything at 4 Hz does not make the work cheaper; it makes it *bursty*. On a 20 ms budget already carrying ~8 ms of Rootstock overhead plus user code, a 10 ms burst every 250 ms means **every twelfth loop overruns**. Loop overruns that appear once every 250 ms are the single worst thing to debug, because they correlate with nothing a student can see. And `LoopTimeMonitor` (§8.5) would faithfully report a library-caused overrun — from the health monitor whose job is to prevent mystery.

**Error 2 — "4 Hz" against a wall clock is nondeterministic under replay.** The same log replayed at 50× fires the monitors on different cycles than the real robot did, so replayed health outputs do not match recorded ones and `RootstockReplayVerify` reports diffs that are artifacts of the harness. A replay tool that cries wolf is a replay tool nobody runs.

**The fix: `HealthMonitor` runs exactly ONE registered source per robot loop, round-robin.**

```java
// the entire scheduling policy
m_sources.get((int) (m_cycle++ % m_sources.size())).pollHealth(m_collector);
```

Consequences, all of them good:

* **Bounded per-loop cost.** One source per loop, ~1 ms worst case instead of a 10 ms spike. The tail is flat, which is what a 20 ms budget actually requires.
* **A full sweep every `slices.size()` cycles.** The default registration is **eleven slices** — **eight built-in health sources** (`CanBusMonitor.rio()`, `BatteryMonitor`, `RailMonitor.watch5V()`, `RailMonitor.watch3V3()`, `RailMonitor.watch6V()`, `BrownoutMonitor`, `DeployMonitor`, `DsMonitor`) plus **three platform slices** (`AlertRegistry` evaluation, `MatchContext` derived publishing, `TuningRegistry.periodic()`). Eleven slices at 50 Hz is a complete sweep every 11 × 20 ms = **220 ms** — within a rounding error of the 250 ms the 4 Hz gate gave, at roughly a twelfth of the peak cost.
  **Eight slices from seven monitor types, and the difference is `RailMonitor`.** Six of the seven types register one slice each; `RailMonitor` registers three (5 V, 3.3 V, 6 V), and `LoopTimeMonitor` registers **none** — it is measuring loops, so it runs every loop, and its ~30 µs is in its own budget. 6 + 3 = 9 … minus `LoopTimeMonitor`'s absent slice, counted from the seven types: `CanBusMonitor` 1 + `BatteryMonitor` 1 + `RailMonitor` 3 + `BrownoutMonitor` 1 + `DeployMonitor` 1 + `DsMonitor` 1 + `LoopTimeMonitor` 0 = **8**. That is the whole of the "seven types, eight slices" arithmetic, and `BuiltinMonitorCountTest` (§8.5) asserts both halves.
* **A four-mechanism robot is still fine.** Add a swerve drive, two cameras and four mechanisms and you are at ~18 slices — a 360 ms sweep. That is the correct thing to degrade: a check that fires a third of a second later is still a check, whereas a loop overrun corrupts every control loop on the robot.
* **Cycle-deterministic.** Source *k* is polled on cycles ≡ *k* (mod *n*), in real time, in replay, in sim, and in a JUnit test. Replay diffs mean something again.
* **Self-limiting by construction.** Registering a twelfth slice lengthens the sweep from 220 ms to 240 ms; it does not raise the per-loop cost. The cost of "one more check" is latency, not overruns — the right thing to trade.

**This rule is domain-wide.** Every wall-clock rate gate in this domain is deleted; rates are expressed as `everyNCycles(int)` derived from `Clock.periodCycles(Hertz.of(4))`. That includes `MatchContext.poll()` and [`design/02`](02-tuning.md)'s `TuningRegistry.periodic()`, both of which are on the same round-robin (§8.3.1). `design/02` §14.2's minimal-integration path currently bypasses this and owns its own gate; that is row 10 of §5.3 Part E and a contract request. Anything that must run every loop — `LedController`, `RumbleScheduler`, the `RootstockTracer` accumulators — says so explicitly and is budgeted.

```java
package org.rootstock.core.diag;

import edu.wpi.first.units.measure.Time;

/**
 * Per-section loop-time accounting with declared budgets. LoopTimeMonitor (§8.5) turns a
 * budget overrun into a named alert; this is where the budget is declared and the time measured.
 * Cost is one Clock.seconds() pair per section per loop.
 */
public final class RootstockTracer {
  /** Declare a budget. Exceeding it for 5 consecutive sweeps raises a PIT_ONLY WARNING alert. */
  public static void budget(String section, Time perLoop);

  /** try-with-resources timing block. Publishes /Rootstock/Loop/Domain/<section>Ms. */
  public static AutoCloseable section(String section);

  public static Time p95(String section);
  public static Time worst(String section);
}
```

Budgets Rootstock declares for itself at boot, so "is Rootstock causing my overruns?" is answerable without bisecting:

```java
RootstockTracer.budget("Health",    Milliseconds.of(1.5));   // one source per loop, round-robin
RootstockTracer.budget("Alerts",    Milliseconds.of(0.5));
RootstockTracer.budget("Match",     Milliseconds.of(0.2));
RootstockTracer.budget("Power",     Milliseconds.of(0.5));
RootstockTracer.budget("Leds",      Milliseconds.of(1.0));   // every loop by design
RootstockTracer.budget("Telemetry", Milliseconds.of(2.0));
```

#### 8.3.1 The round-robin, precisely

One shared rotation, not one per subsystem, because *n* independent rotations reconstruct the spike:

```java
package org.rootstock.core.health;

/**
 * The single slice scheduler. RootstockRobot calls tick() exactly once per robotPeriodic().
 * Everything periodic-but-not-every-loop in Rootstock registers here; nothing else may
 * implement its own rate gate.
 */
public final class SliceScheduler {
  /** Registered in construction order; order is part of the replay contract, so it is stable. */
  public static void register(String name, Runnable slice);

  /** Runs exactly ONE registered slice: slices.get(cycle % slices.size()). */
  static void tick();

  /** Cycles between two runs of the same slice == number of registered slices. */
  public static int sweepCycles();
}
```

`MatchContext.poll()`, `TuningRegistry.periodic()`, `AlertRegistry` evaluation, and every `HealthSource` are slices. Three things are explicitly **not** slices and run every loop, because their whole value is edge detection: `MatchContext`'s FMS/DS/alliance edge latching (~20 µs of boolean reads — the *derived* publishing is the slice), `RumbleScheduler`, and `LedController`.

```java
package org.rootstock.core.health;

import edu.wpi.first.units.measure.Time;
import org.rootstock.core.alert.Severity;

/** One observed problem. Immutable; produced fresh each poll. */
public record Fault(
    String device,        // "Arm/leader (TalonFX 21)"
    String description,   // "device temperature 84.2 C exceeds warn limit 70.0 C"
    Severity severity,
    boolean sticky) {}    // sticky = latched by the device across boots
```

```java
package org.rootstock.core.health;

/** Passed into pollHealth(); collects without allocating a list per poll. */
public interface FaultCollector {
  void add(Fault f);
  default void error(String device, String description)   { add(new Fault(device, description, Severity.ERROR, false)); }
  default void warn(String device, String description)    { add(new Fault(device, description, Severity.WARNING, false)); }
  default void sticky(String device, String description)  { add(new Fault(device, description, Severity.WARNING, true)); }
}
```

```java
package org.rootstock.core.health;

public final class HealthMonitor {

  /**
   * Registers a source as one slice of the shared round-robin. Idempotent by healthName().
   *
   * PACKAGE-PRIVATE, and that is D27, not an oversight. Revision 1 had four parallel public
   * registration lists over the same objects (RootstockRegistry.addAll, SelfTest.registerAll,
   * TuningRegistry.registerAll, HealthMonitor.watch) with different membership for non-obvious
   * reasons -- inside the example that advertises "ONE list". D27 collapsed them:
   *
   *     RootstockRegistry.addAll(m_drive, m_elevator, m_arm);
   *
   * inspects each argument once and routes it (instanceof HealthSource -> here). Opting OUT is
   * `.excludeFrom(Registry.HEALTH)` on the mechanism's config, which is visible in describe().
   * A team with hand-rolled subsystems is served by the same call, because routing is by
   * interface and not by base class.
   */
  static HealthMonitor watch(HealthSource source);

  /**
   * Poll this source only every Nth time its slot comes up, for genuinely expensive checks
   * (a device config read-back, say). Default 1 == every sweep. There is deliberately NO
   * rate(Frequency) method: a Hz value implies a wall clock, and wall clocks are what §8.3
   * removed. Convert with Clock.periodCycles(Hertz.of(...)) if you are thinking in Hz.
   */
  public HealthMonitor everyNSweeps(int n);

  /** Only poll when MatchContext.isDiagnostics(). Off by default: cheap checks must run in a match. */
  public HealthMonitor onlyInDiagnostics(boolean b);

  /**
   * This device is knowingly not installed on this robot right now (mid-build, swapped out,
   * next year's mechanism). Its disconnect/absence faults are demoted to INFO + PIT_ONLY and
   * printed in the boot dump instead of the alert panel. NEVER guessed by the library.
   *
   * PROVENANCE CORRECTED 2026-08-08. This javadoc used to read "Set from mechanism config's
   * .expectAbsent() (design/01)". That config-builder method was required of design/01 for
   * three revisions, was never written there (grep returns 0), and the requirement is now
   * WITHDRAWN -- see the labeled note under the contract table in section 1. THIS STATIC IS
   * THE ONLY ENTRY POINT: a team calls Health.expectAbsent("Climber/leader", "not built yet,
   * week 2") directly. The capability is unchanged; only the sugar that never existed is gone.
   */
  public static void expectAbsent(String healthName, String reason);

  /** Poll every source once, right now, ignoring the round-robin. Used ONLY by SelfTest. */
  public static void pollAll();

  /** How many loops a full sweep takes right now. Published to /Rootstock/Health/SweepCycles. */
  public static int sweepCycles();

  /** Snapshot of every fault seen since enable, for expectNoNewFaults(). */
  public static java.util.Set<String> faultFingerprints();
}
```

`pollAll()` is the one place the 10 ms burst is acceptable, because `SelfTest` runs disabled in the pit with no control loop to starve. It logs its own duration to `/Rootstock/SelfTest/PollAllMs` so the cost stays visible rather than becoming folklore.

**Boot dump for expected-absent hardware.** `.expectAbsent()` does not silence anything — it *relocates* it. At boot, `RootstockRobot` prints one block to the console and `/Rootstock/Health/ExpectedAbsent`:

```
Rootstock: 3 devices declared absent (alerts demoted to INFO)
  Climber/leader  (TalonFX 31)  - "not built yet, week 2"
  Climber/encoder (CANcoder 32) - "not built yet, week 2"
  Intake/beambreak (DIO 4)      - "sensor on order"
Remove the Health.expectAbsent(...) calls when the hardware lands.
```

*(The dump line used to read "Remove `.expectAbsent()` from ClimberConfig" — that named `design/01`'s config-builder method, which was withdrawn on 2026-08-08 and never existed. The dump now names the call a team actually wrote.)*

That is the difference between suppression and triage: the fact is still on screen, still named, still attributable — it just is not claiming the robot cannot play a match.

```java
package org.rootstock.core.health;

import java.util.List;
import java.util.Optional;
import org.rootstock.core.alert.Severity;

public final class RobotHealth {
  public static Optional<Severity> worst();
  public static List<Fault> active();

  /**
   * THE match-readiness answer, and it rolls up BLOCKS_MATCH alerts EXCLUSIVELY (§8.2).
   * "Ready" means "this robot can play a match", not "nothing anywhere is imperfect".
   * A robot with nine PIT_ONLY warnings and no blocking alerts is Ready, and the LEDs go green,
   * because that is the honest answer and an honest answer is what makes the signal trusted.
   */
  public static boolean isReady();          // delegates to AlertRegistry.matchReady()

  /** Deprecated spelling of isReady(), kept for one season. AlertRegistry.matchReady() is the
   *  canonical implementation and is NOT deprecated — this is the duplicate, not that one. */
  @Deprecated public static boolean matchReady();

  public static boolean hasError();
  public static boolean hasWarning();

  /** True while battery voltage is sagging past the configured limit. Used by CompressorPolicy. */
  public static boolean batterySagging();
}
```

Vendor adapters (each in its own artifact, each a `HealthSource` factory — **no overload list in core**):

```java
package org.rootstock.phoenix6;

import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.hardware.Pigeon2;
import edu.wpi.first.units.measure.Temperature;
import org.rootstock.core.health.HealthSource;

public final class Phoenix6Health {
  public static Builder of(String label, TalonFX motor);
  public static HealthSource of(String label, CANcoder encoder);
  public static HealthSource of(String label, Pigeon2 gyro);

  public static final class Builder {
    /** Checks isConnected(), every getStickyFault_* signal, and getDeviceTemp(). */
    public Builder tempLimits(Temperature warn, Temperature error);
    public Builder clearStickyFaultsOnBoot(boolean b);   // default false: previous-match faults MUST surface
    public HealthSource build();
  }
}
```

`org.rootstock.revlib.RevHealth` mirrors it over `SparkBase.getFaults()`, `getStickyFaults()`, `getWarnings()`, `getStickyWarnings()`, `hasActiveFault()`, `getMotorTemperature()`.

**Sticky faults from a previous boot are surfaced immediately at startup** and never auto-cleared, because that is how you catch a fault that happened in the previous match.

### 8.4 Self-test

135's Consul + Squire proves the workflow — run in Test mode, sequential, per-step pass/fail on a pit screen — but requires inheritance and a separate desktop app. We keep the semantics and drop both.

```java
package org.rootstock.core.selftest;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import edu.wpi.first.units.Unit;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.wpilibj2.command.Command;

/** A named, ordered sequence of steps with expectations. Built with the DSL below. */
public final class SelfTestRoutine {
  public static Builder of(String mechanismName);

  public static final class Builder {
    public StepBuilder step(String name, Command action);
    public Builder expectCurrentBetween(Current min, Current max);
    public Builder expectNoNewFaults();
    /** Steps are aborted and marked FAIL if any registered ERROR alert activates mid-run. */
    public Builder abortOnError(boolean b);
    public SelfTestRoutine build();
  }

  public static final class StepBuilder {
    /** Assert a measured quantity settles within tolerance of a target before the timeout. */
    public <U extends Unit> StepBuilder expect(
        Supplier<edu.wpi.first.units.Measure<U>> measured,
        edu.wpi.first.units.Measure<U> target,
        edu.wpi.first.units.Measure<U> tolerance);
    public StepBuilder expect(DoubleSupplier measured, double target, double tolerance, String unitLabel);
    /** Assert something changed at all — catches a disconnected encoder that reads a constant. */
    public StepBuilder expectMoved(DoubleSupplier measured, double minDelta, String unitLabel);
    public StepBuilder withTimeout(Time t);
    public StepBuilder step(String name, Command action);   // chain to the next step
    public SelfTestRoutine build();
  }
}
```

```java
package org.rootstock.core.selftest;

import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

public final class SelfTest {
  /**
   * Registration is by interface, so a mechanism opts in with no base class — but it is not
   * called directly. PACKAGE-PRIVATE per D27: RootstockRegistry.addAll(...) routes anything
   * `instanceof SelfTestable` here, and `.excludeFrom(Registry.SELFTEST)` is the opt-out.
   */
  static void register(SelfTestable target);

  /**
   * PUBLIC, and deliberately the one surviving direct entry point: a named routine that has no
   * owning object to route. This is the form the §8.4 example below uses. D27 removed the
   * *parallel object lists*, not the ability to register a bare routine.
   */
  public static void register(String name, java.util.function.Supplier<SelfTestRoutine> routine);

  /** Sequences every registration; catches, times, and publishes each. Bind to a pit button. */
  public static Command runAll();
  public static Command run(String name);

  public static List<SelfTestResult> lastResults();

  /** Human-readable block for the Elastic text widget and the pit printout. */
  public static String summary();
}
```

```java
package org.rootstock.core.selftest;

import java.util.List;

public record SelfTestResult(
    String name, boolean passed, double durationSec, List<StepResult> steps, String detail) {

  public record StepResult(String name, boolean passed, String expectation, String observed) {}
}
```

Publishes `/Rootstock/SelfTest/<name>/{Status, Passed, Ran, Detail, DurationSec}` and `/Rootstock/SelfTest/Summary`. Safety: `runAll()` refuses to schedule unless `MatchContext.isDiagnostics()` is true, or `MatchContext.isDisabled() && !MatchContext.isFMSAttached()`, and it raises an `ERROR` / `BLOCKS_MATCH` alert saying exactly why it refused. `abortOnError(true)` aborts on any *blocking* alert activating mid-run, not on any alert at all — otherwise a `PIT_ONLY` kG warning aborts the self-test that would have found the real problem.

The pit workflow is: **plug in, enable Test, press one button, read a green/red list on Elastic.** Ninety seconds, repeatable, no tribal knowledge.

Example self-test written by a team (this is the whole thing):

```java
SelfTest.register("Arm", () -> SelfTestRoutine.of("Arm")
    .step("extend", arm.toAngle(Degrees.of(90)))
        .expect(arm::angle, Degrees.of(90), Degrees.of(2)).withTimeout(Seconds.of(2))
    .step("retract", arm.toAngle(Degrees.of(0)))
        .expect(arm::angle, Degrees.of(0), Degrees.of(2)).withTimeout(Seconds.of(2))
    .expectCurrentBetween(Amps.of(2), Amps.of(35))
    .expectNoNewFaults()
    .build());
```

### 8.5 Built-in monitors — **seven types, eight slices, eleven conditions** — on by default

The vendor adapters are the easy half. These are the checks nobody writes, every one of them backed by a documented WPILib API that most teams have never heard of. **`RootstockRobot` registers all of them automatically**; each can be reconfigured or disabled by name.

> **The three numbers, and the test that pins them.** This document set has already had to withdraw a *"ten competition-day health checks"* claim once ([`DECISIONS.md`](../DECISIONS.md) line 533), and it resurfaced in §8.2 of this very file. So the counts are stated together, derived, and asserted:
>
> * **7 monitor *types*** — the seven classes in `org.rootstock.core.health.builtin`, all declared below: `CanBusMonitor`, `BatteryMonitor`, `RailMonitor`, `BrownoutMonitor`, `DeployMonitor`, `LoopTimeMonitor`, `DsMonitor`.
> * **8 registered health *slices*** — six types register one slice each, `RailMonitor` registers three (5 V / 3.3 V / 6 V), and `LoopTimeMonitor` registers none because it runs every loop (§8.3). 1+1+3+1+1+1+0 = **8**.
> * **11 alert *conditions*** — the rows of the `MatchImpact` table below. Several types declare two conditions at different severities (`CanBusMonitor` >90 % and >70 %; `BatteryMonitor` resting and sag; `RailMonitor` 5 V and 3.3/6 V; `DeployMonitor` dirty and unknown-provenance).
>
> ```java
> // rootstock-testkit. Named in DESIGN.md §9.6 and ROADMAP.md M1; specified here.
> class BuiltinMonitorCountTest {
>   @Test void countsAreWhatEveryDocumentSays() {
>     assertThat(HealthMonitor.builtinTypeCount()).isEqualTo(7);
>     assertThat(HealthMonitor.builtinSliceCount()).isEqualTo(8);
>     assertThat(HealthMonitor.builtinConditionCount()).isEqualTo(11);
>   }
> }
> ```
>
> `builtinConditionCount()` is new in this revision and exists because §8.2's arithmetic and §8.5's driver-budget sentence both depend on the number eleven, and neither was asserted by anything. **Adding an eighth monitor type is therefore a four-document change plus a test edit, by construction** — which is the point, not an obstacle.

```java
package org.rootstock.core.health.builtin;

import edu.wpi.first.units.measure.*;

/**
 * CAN bus utilization + error-counter DELTAS since enable. Absolute counters are meaningless.
 * Multi-bus aware from day one: SystemCore has several buses.
 */
public final class CanBusMonitor {
  /** roboRIO bus, via Platform.canStatus() (which wraps RobotController.getCANStatus()). */
  public static CanBusMonitor rio();
  /**
   * Any bus via a supplier, so the Phoenix adapter can feed CANivore/SystemCore buses using
   * com.ctre.phoenix6.CANBus.getStatus() -> CANBusStatus{Status, BusUtilization, BusOffCount,
   * TxFullCount, REC, TEC}.  (verified against Phoenix 6 26.1.3 Javadoc, 2026-08-06)
   */
  public static CanBusMonitor named(String busName, java.util.function.Supplier<CanSample> sampler);

  public CanBusMonitor warnAbove(double utilizationFraction);   // default 0.70
  public CanBusMonitor errorAbove(double utilizationFraction);  // default 0.90
  public CanBusMonitor watchErrorDeltas(boolean b);             // default true

  public record CanSample(double utilization, int busOff, int txFull, int rec, int tec) {}
}

/** Resting voltage, sag under load, and joules consumed. Correlates sag against PDH total current. */
public final class BatteryMonitor {
  public static BatteryMonitor create();
  public BatteryMonitor restingMin(Voltage v);   // default 12.3 V — below this, rotate the battery out
  public BatteryMonitor sagLimit(Voltage v);     // default 9.0 V
  public BatteryMonitor sagWindow(Time t);       // default 2 s
  public Voltage minSeenSinceEnable();
  public Voltage restingEstimate();
}

/** Platform.faultCount5V() != 0 means sensors browned out and your encoders lied. */
public final class RailMonitor {
  public static RailMonitor watch5V();
  public static RailMonitor watch3V3();
  public static RailMonitor watch6V();
}

/** Platform.isBrownedOut(), latched with a Clock.seconds() stamp so a 40 ms brownout is not missed. */
public final class BrownoutMonitor { public static BrownoutMonitor latching(); }

/** BuildConstants.DIRTY == 1 -> WARNING / PIT_ONLY "deployed code is not in git" (§7.4, §11.1). */
public final class DeployMonitor { public static DeployMonitor create(); }

/**
 * Per-domain loop timing against the budgets declared with RootstockTracer (§8.3).
 * Answers "is Rootstock causing my overruns?" in one glance, with a name attached.
 * Runs EVERY loop (it is measuring loops); its own cost is ~30 us and it is in its own budget.
 */
public final class LoopTimeMonitor {
  public static LoopTimeMonitor create();
  public LoopTimeMonitor budget(Time period);                // default: Clock.period()
  public LoopTimeMonitor warnAbove(double fractionOfBudget); // default 0.80
  /** Delegates to RootstockTracer.section(name); kept here because this is where people look. */
  public static AutoCloseable section(String name);
}

/** Joystick presence and slot verification against the declared ControlMap. */
public final class DsMonitor { public static DsMonitor create(); }
```

**Every built-in monitor declares its `MatchImpact`, and most of them are `PIT_ONLY`.** This table is the difference between a driver panel that gets read and one that gets ignored:

| Monitor | Condition | Severity | Impact |
|---|---|---|---|
| `CanBusMonitor` | utilization > 90%, or bus-off delta > 0 | ERROR | **BLOCKS_MATCH** |
| `CanBusMonitor` | utilization > 70% | WARNING | PIT_ONLY |
| `BatteryMonitor` | resting < 12.3 V at enable | WARNING | **BLOCKS_MATCH** — this is the classic lost match, and it is fixable in 90 seconds |
| `BatteryMonitor` | sag < 9.0 V sustained | WARNING | PIT_ONLY |
| `RailMonitor` | 5 V fault count rising | ERROR | **BLOCKS_MATCH** — your encoders are lying right now |
| `RailMonitor` | 3.3 V / 6 V fault count rising | WARNING | PIT_ONLY |
| `BrownoutMonitor` | latched brownout since enable | ERROR | PIT_ONLY — it already happened; the driver cannot act on it |
| `DeployMonitor` | `DIRTY == 1` | WARNING | PIT_ONLY |
| `DeployMonitor` | provenance UNKNOWN | INFO | PIT_ONLY |
| `LoopTimeMonitor` | over budget 5 sweeps running | WARNING | PIT_ONLY |
| `DsMonitor` | declared HID port empty or wrong controller | ERROR | **BLOCKS_MATCH** — half the bindings are dead |

**Four of the eleven built-in conditions can reach the driver** — CAN utilization > 90 %, battery resting < 12.3 V, 5 V rail faults rising, and a wrong/empty HID slot — and each is something a human can act on in the ninety seconds before a match. Four of eleven is also why `AlertBudgetTest`'s ceiling of five (§8.2.2) is a real constraint rather than a formality: the built-ins alone can supply four of the five on a hardware-free robot, leaving exactly one for every other domain combined. That is deliberate. It is what stops a sixth blocking alert being added without someone deciding which of the existing five was mis-classified.

`LoopTimeMonitor` is a direct response to the strongest recurring criticism of every FRC framework: *"For us, YAMS caused more problems than it solved. The structure of it created a crazy amount of loop overruns… We ended up not using any logging or telemetry at all."* A library whose observability is the first thing dropped under pressure has failed at its main job. We publish `/Rootstock/Loop/Domain/<name>Ms` for every Rootstock domain on by default, so blame is attributable rather than bisected.

Encoder sanity and stall detection belong to the mechanism domain (they need a mechanism's two sensors), but they emit through this registry:

```java
package org.rootstock.core.health;

import edu.wpi.first.units.measure.*;
import java.util.function.DoubleSupplier;

public final class Checks {
  /** Two sensors that must agree. The canonical "CANcoder is on the wrong shaft" detector. */
  public static HealthSource encoderAgreement(
      String label, DoubleSupplier a, DoubleSupplier b, Angle maxDisagreement);

  /** High current + near-zero velocity for a sustained time = something is jammed. */
  public static HealthSource stall(
      String label, DoubleSupplier amps, DoubleSupplier velocity,
      Current currentAbove, double velocityBelow, Time forTime);

  /** Commanded to move but the measurement did not change. Catches an unplugged encoder. */
  public static HealthSource notMoving(
      String label, DoubleSupplier command, DoubleSupplier measured, Time forTime);
}
```

---

## 9. Power, Pneumatics, LEDs, Haptics, Dashboard

### 9.1 Power monitoring and the current-limit budget

`PowerDistribution.getAllCurrents()` returns a `double[]` indexed by bare channel number, so PDH logs are unreadable and nobody notices channel 7 sitting at 45 A on a 40 A breaker until it trips in elims. `getTotalEnergy()` is a perfect per-match battery metric that is essentially never used.

```java
package org.rootstock.core.power;

import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.PowerDistribution.ModuleType;
import edu.wpi.first.units.measure.*;
import edu.wpi.first.wpilibj2.command.Command;

public final class PowerMonitor {
  public static PowerMonitor of(PowerDistribution pdh);
  public static PowerMonitor pdh();                 // new PowerDistribution(1, ModuleType.kRev)
  public static PowerMonitor pdp();                 // new PowerDistribution(0, ModuleType.kCTRE)

  /** Names a channel and declares its breaker. Auto-alerts at 90% of the breaker, sustained 1 s. */
  public PowerMonitor channel(int ch, String name, int breakerAmps);
  public PowerMonitor warnAbove(int ch, Current i, Time sustained);

  public Current total();
  public Voltage voltage();
  /** getTotalEnergy() since enable — the metric that makes a battery rotation discipline possible. */
  public Energy matchEnergy();

  /** PDH only. */
  public Command switchableChannel(boolean on);

  /** Escape hatch. */
  public PowerDistribution raw();
}
```

```java
package org.rootstock.core.power;

import edu.wpi.first.units.measure.Current;

/**
 * Robot-level sum of every mechanism's DECLARED current limit. Nothing in the vendor APIs
 * checks that the sum of six 120 A stator limits exceeds what the battery can deliver.
 */
public final class PowerBudget {
  /** Called automatically by every Rootstock mechanism at construction. */
  public static void declare(String mechanism, Current stator, Current supply);
  public static Current totalDeclaredStator();
  public static Current totalDeclaredSupply();
  /** Logs the whole table and raises WARNING / PIT_ONLY above the threshold. Default 400 A supply. */
  public static void publish(Current supplyWarnThreshold);
}
```

Current limits are a **required** argument of mechanism construction ([`design/01`](01-core-mechanisms.md)'s contract with us), not an optional one. That is deliberate teaching: CTRE's own guidance is that stator limits cap torque and are the more effective brownout guard at the start of acceleration, while supply limits protect the battery and breakers during sustained load. Making the limit a named, unit-typed, required argument teaches the distinction instead of hiding it. We also print CTRE's ordering advice in the `PowerBudget` boot log: **check battery health and PDH/PDP crimps before clamping limits in software** — a high-resistance connection produces brownouts no software limit can fix.

### 9.2 Pneumatics — first-class, but not shoehorned into the motor API

**Direct answer to the question: no, pneumatic actuators must not be abstracted alongside motors.** A `DoubleSolenoid` has no position, no velocity, no current, no feedback, and no continuum of setpoints. Forcing it into a `Mechanism` interface with `setPosition`/`getVelocity`/`currentLimit` produces an abstraction where 80% of the methods throw or return `NaN`, which is strictly worse than no abstraction. Model it as what it is: a discrete-state actuator.

```java
package org.rootstock.core.pneumatics;

import edu.wpi.first.wpilibj.DoubleSolenoid;
import edu.wpi.first.wpilibj.PneumaticsModuleType;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/** A two-state actuator. Deliberately NOT a Mechanism. */
public final class Pneumatic {
  public static Pneumatic doubleActing(String name, PneumaticsModuleType type, int fwd, int rev);
  public static Pneumatic singleActing(String name, PneumaticsModuleType type, int channel);

  public Command extend();
  public Command retract();
  public Command toggle();

  /** Commanded state only — no feedback exists on a solenoid, and we say so. */
  public Trigger isExtended();

  /** Free short-circuit detection via isFwdSolenoidDisabled()/isRevSolenoidDisabled(). */
  public org.rootstock.core.health.HealthSource health();

  public DoubleSolenoid raw();
}
```

```java
package org.rootstock.core.pneumatics;

import edu.wpi.first.wpilibj.Compressor;
import edu.wpi.first.units.measure.Pressure;
import java.util.function.BooleanSupplier;

public final class CompressorPolicy {
  public static CompressorPolicy of(Compressor c);

  public CompressorPolicy digital();                                  // enableDigital()
  public CompressorPolicy analog(Pressure min, Pressure max);         // enableAnalog(min, max)
  public CompressorPolicy hybrid(Pressure min, Pressure max);         // enableHybrid(min, max)

  /** The compressor is a large uncommanded draw that nobody models in a brownout budget. */
  public CompressorPolicy disableWhen(BooleanSupplier condition);

  /** Sensible default policy, applied by RootstockRobot if the user does nothing else. */
  public CompressorPolicy standard();  // disable during auto and while RobotHealth.batterySagging()

  public Compressor raw();
}
```

`PneumaticsHealth.of(hub, compressor)` emits solenoid-short alerts, low-pressure alerts, and compressor over-current.

### 9.3 LEDs — one state machine, two backends

WPILib's `LEDPattern` algebra is genuinely excellent and almost nobody discovers `mask()`, `overlayOn()`, or `progressMaskLayer()`. Meanwhile CTRE moved CANdle into Phoenix 6 for 2026 with a completely incompatible control-request model, so a team that changes LED hardware rewrites all their LED code. And neither provides **arbitration** — the universal failure is a 200-line if/else ladder in `periodic()` where two subsystems fight over the strip. The arbitration layer does not exist anywhere; we build it.

```java
package org.rootstock.core.led;

import edu.wpi.first.wpilibj.LEDPattern;
import edu.wpi.first.wpilibj.LEDReader;
import java.util.function.BooleanSupplier;

/** Writes a resolved buffer to hardware. Implementations: AddressableBackend, CandleBackend. */
public interface LedBackend extends AutoCloseable {
  int length();
  void write(LEDReader buffer);
  @Override void close();
}
```

```java
package org.rootstock.core.led;

import edu.wpi.first.wpilibj.AddressableLED;
import edu.wpi.first.wpilibj.AddressableLEDBuffer;
import edu.wpi.first.wpilibj.LEDPattern;
import edu.wpi.first.wpilibj2.command.Command;
import java.util.function.BooleanSupplier;

/**
 * Declarative, priority-arbitrated robot-state signaling. Resolves the highest-priority
 * active state each cycle and writes ONCE. No if/else ladder anywhere in team code.
 */
public final class LedController {
  public static LedController on(LedBackend backend);

  /** Higher priority wins. Ties broken by registration order. */
  public LedController state(int priority, BooleanSupplier when, LEDPattern show);

  /** Logical segment of one physical strip, via AddressableLEDBuffer.createView(start, end). */
  public LedRegion region(String name, int start, int end, boolean reversed);

  /** Shown when nothing else is active. */
  public LedController idle(LEDPattern p);

  /** Register with the scheduler. RootstockRobot does this for you. */
  public void start();

  public AddressableLEDBuffer rawBuffer();

  public static final class LedRegion {
    public LedRegion state(int priority, BooleanSupplier when, LEDPattern show);
    public LedRegion idle(LEDPattern p);
    public LedController done();
  }
}
```

```java
package org.rootstock.core.led;

import edu.wpi.first.wpilibj.AddressableLED;
import edu.wpi.first.wpilibj.AddressableLEDBuffer;

public final class LedBackends {
  /** The roboRIO can drive exactly ONE AddressableLED at a time. Multiple strips = daisy-chain or Y-cable. */
  public static LedBackend addressable(AddressableLED led, AddressableLEDBuffer buffer);
}
```

```java
package org.rootstock.phoenix6;

import com.ctre.phoenix6.hardware.CANdle;
import org.rootstock.core.led.LedBackend;

/**
 * Renders a resolved LEDPattern buffer onto a CANdle via per-LED SolidColor control requests,
 * and offloads to a device-side animation slot when the resolved pattern is a recognized
 * primitive (solid / strobe / rainbow / larson).  CANdle exposes supply voltage, output current,
 * device temp and 5V-rail/thermal/hardware faults, so the LED controller ITSELF participates in
 * health checks -- a HealthSource comes free.
 *
 * CANdleConfiguration.LED carries StripType (StripTypeValue), BrightnessScalar and
 * LossOfSignalBehavior (verified from CTRE Phoenix 6 26.1.3 docs, 2026-08-06).
 * [UNVERIFIED] the exact per-slot animation request-class set and whether per-LED SolidColor
 * at 50 Hz is CAN-affordable for a 60-LED strip -- MEASURE before shipping; fall back to
 * device-side animations if it is not.
 */
public final class CandleBackend implements LedBackend {
  public static CandleBackend of(CANdle candle, int onboardCount, int stripCount);
  public org.rootstock.core.health.HealthSource health();
  public CANdle raw();
}
```

We map the **common subset** — solid, blink, breathe, scroll, rainbow, progress — and let advanced users drop to the native object. We do not attempt to unify CTRE's 8 animation slots with WPILib's buffer algebra.

Standard state table `RootstockRobot` installs by default (overridable, and this is a *default*, not a lock):

```java
// MatchContext::isEStopped, never DriverStation::isEStopped — core.led is not on the
// DriverStation allowlist (§5.3 Part A, onlyMatchReadsDriverStation), and this exact line is
// row 8 of the in-domain conversion table in §5.3 Part E.
leds.state(100, RobotHealth::hasError,        LEDPattern.solid(Color.kRed).blink(Seconds.of(0.15)))
    .state( 95, MatchContext::isEStopped,     LEDPattern.solid(Color.kRed))
    .state( 90, RobotHealth::hasWarning,      LEDPattern.solid(Color.kOrange).breathe(Seconds.of(1.0)))
    .state( 20, MatchContext::allianceKnownAndRed,  LEDPattern.solid(Color.kRed))
    .state( 20, MatchContext::allianceKnownAndBlue, LEDPattern.solid(Color.kBlue))
    .idle(LEDPattern.rainbow(255, 128)
              .scrollAtAbsoluteSpeed(MetersPerSecond.of(0.5), Meters.of(1.0 / 60.0)));
```

The pit-visible payoff: **green means `SelfTest` passed and `AlertRegistry.matchReady()` is true.** A student across the shop can see whether the robot is ready.

### 9.4 Dashboard facade — raw NT4 only, plus a properly-vendored ElasticLib

**No Rootstock public signature may mention `ShuffleboardTab` or `SmartDashboard`.** Both are deleted in 2027 along with NT3. We publish to raw NT4 under `/Rootstock/`, which Elastic, Glass, and AdvantageScope all consume simultaneously and which survives the transition.

ElasticLib is currently distributed as *a file you copy into your project*, so every team runs a divergent private copy and fixes never propagate. We vendor it properly in `rootstock-elastic` (BSD-compatible, attributed, upstream-tracked) and wrap it.

```java
package org.rootstock.core.dashboard;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import org.rootstock.core.alert.Severity;

public final class RootstockDashboard {
  /**
   * Called by RootstockRobot. Starts the deploy-directory web server on 5800 so Elastic's
   * "Load Layout From Robot" (Ctrl+D) works -- one line nobody ever remembers to add. The path
   * comes from the facade, not from Filesystem directly (§5.3 Part E):
   *   edu.wpi.first.net.WebServer.start(5800, Platform.deployDir().toString());
   * (verified: edu.wpi.first.net.WebServer.start(int port, String path), WPILib 2026;
   *  edu.wpi.first.net is NOT on the confined list, so naming WebServer here is legal.)
   */
  public static void init();

  /** Rate-limited and rising-edge only. Elastic's sendNotification has no dedup; periodic() floods it. */
  public static void notify(Notify n);

  public static void selectTab(String tab);
  public static Trigger tabActive(String tab);
}
```

```java
package org.rootstock.core.dashboard;

import edu.wpi.first.units.measure.Time;
import org.rootstock.core.alert.Severity;

public record Notify(Severity level, String title, String detail, Time show, Time minInterval) {
  public static Notify error(String title, String detail);
  public static Notify warning(String title, String detail);
  public static Notify info(String title, String detail);
  public Notify oncePerSecond();
  public Notify noAutoDismiss();
}
```

We ship, in `layouts/` and copied into the template's deploy root:

* `elastic-layout.json` — a working driver dashboard: match time, alliance, auto chooser, Alerts widget, self-test summary text, battery, CAN utilization, camera streams. **Hand-exported from the Elastic GUI, never generated** — Elastic's layout schema is not published as a stable public contract and generating it is a real risk. **[UNVERIFIED]** whether that schema is stable across the 2026.x line.
* `AdvantageScope.json` and `AdvantageScopeTuning.json` — debug and tuning layouts, so tuning is open-a-file rather than build-a-dashboard.

### 9.5 Haptics and the control map

WPILib gives you exactly `setRumble(RumbleType, double)`: no getter, no duration, no patterns, no arbitration, and no auto-clear on disable. Every team writes the same ad-hoc `startEnd(...).withTimeout(...)`, then two subsystems stomp each other and the rumble is left on at disable.

```java
package org.rootstock.core.hid;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj.GenericHID.RumbleType;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.Command;

public final class Rumble {
  public static Builder on(GenericHID hid);

  public static final class Builder {
    public Builder side(RumbleType t);      // kLeftRumble | kRightRumble | kBothRumble
    public Builder priority(int p);         // highest active wins, per side
    public Command play(RumblePattern p);
  }
}

public final class RumblePattern {
  public static RumblePattern pulse(double intensity, Time duration);
  public static RumblePattern doubleTap();
  public static RumblePattern ramp(double from, double to, Time duration);
  public static RumblePattern sos();
}
```

`RumbleScheduler` zeroes every registered HID on `disabledInit` unconditionally. Left/right are independent, so a team can encode two facts at once ("game piece acquired" left, "aligned to target" right).

```java
package org.rootstock.core.hid;

import java.nio.file.Path;
import java.util.function.Function;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/**
 * Named bindings. `new JoystickButton(board, 7)` is unreadable and impossible to hand to a new
 * operator; elite teams maintain a physical control-map poster, small teams have one student
 * who remembers. Naming the binding once produces the poster for free.
 */
public final class ControlMap<T extends CommandGenericHID> {
  public static <T extends CommandGenericHID> ControlMap<T> of(String role, T hid);

  public ControlMap<T> onTrue(String action, Function<T, Trigger> button, Command c);
  public ControlMap<T> whileTrue(String action, Function<T, Trigger> button, Command c);
  public ControlMap<T> toggleOnTrue(String action, Function<T, Trigger> button, Command c);

  // ---- Modal control (D30). Ships in M1; it is a Trigger.and() wrapper, not a state machine. ----

  /**
   * Every binding registered inside this block is automatically ANDed with inMode(name), so a
   * mode is declared once and no binding site repeats the condition.
   *
   * AT LEAST ONE MODE MUST BE NAMED "MANUAL". If a ControlMap declares modes and none is
   * MANUAL, publish() raises a persistent Alert. The rationale is from the dossier and it is
   * not a style preference: "Automation without a manual mode loses matches. A single-button
   * macro that depends on vision will fail when a tag is occluded by a defender, and if there
   * is no fallback the robot is dead for the match."
   */
  public ControlMap<T> mode(String name, java.util.function.Consumer<ControlMap<T>> bindings);

  /** True while this mode is selected. Public so team code can gate its own logic on it. */
  public Trigger inMode(String name);

  /** Advance to the next declared mode on each rising edge. */
  public ControlMap<T> modeSelector(Trigger next);

  /** Markdown, one table per role, one section per mode. Written by a gradle task into CONTROLS.md. */
  public static String markdown();
  /** Same content to /Rootstock/Controls for an Elastic text widget. The active mode name also
   *  goes to /Rootstock/Driver/Mode, so the driver tab shows which map is live. */
  public static void publish();
  public static void writeTo(Path file);

  public T raw();
}
```

The template ships a `ControlMap` with a **mandatory `MANUAL` mode** already declared (§6.2), which is what makes `CONTROLS.md` non-empty on day one and gives the pit a printable poster before a team has written a binding of its own.

`DsMonitor` cross-checks the declared HID ports against `MatchContext.joystickName(port)` / `MatchContext.joystickIsXbox(port)` — **not** `DriverStation.getJoystickName(port)`, which `core.health.builtin` is not allowed to name (§5.3 Part A) — and raises an `ERROR` / `BLOCKS_MATCH` alert naming the expected and the actual controller when a gamepad lands in the wrong slot. That is a documented, repeated field failure, and it is one of the four conditions allowed to reach the driver, because half the bindings being dead genuinely means do not take the field.

---

## 10. FMS / DriverStation Integration

`DriverStation.getAlliance()` returns an **empty `Optional` until the DS connects**. Reading it in a constructor, in `robotInit`, or in an auto-chooser lambda that evaluates early gives you the wrong alliance and a mirrored auto. Nothing in WPILib caches it at the right moment or warns that you read it too early. This is the highest-frequency competition-day failure in FRC and it costs small teams entire matches.

```java
package org.rootstock.core.match;

import java.util.Optional;
import java.util.OptionalInt;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.DriverStation.MatchType;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/**
 * THE single DriverStation reader in Rootstock (§5.3 Part A, onlyMatchReadsDriverStation).
 * Nothing else in org.rootstock — not even org.rootstock.core.compat — may name DriverStation.
 * When 2027 moves or renames the DS surface, this is the one file that changes.
 *
 * Spellings mirror WPILib exactly (isFMSAttached, not isFmsAttached) so that a student who learns
 * this API has learned the WPILib one. "Never hide WPILib" applies to method names too.
 */
public final class MatchContext {

  /** Latched on the DS-connect rising edge. Never empty after the first connect. */
  public static Optional<Alliance> alliance();

  /** Convenience for path flipping. False (blue) until known; allianceKnown() gates it. */
  public static boolean isRed();
  public static boolean isBlue();
  public static boolean allianceKnown();

  /** Latched-AND-known forms, for the default LED state table (§9.3). */
  public static boolean allianceKnownAndRed();
  public static boolean allianceKnownAndBlue();

  /** Driver station 1/2/3. */
  public static OptionalInt station();

  // ---- Attachment ---------------------------------------------------------------------
  public static boolean isFMSAttached();
  public static boolean isDSAttached();

  /**
   * Has this ROBOT ever been FMS-attached, across boots? Backed by
   * Platform.persistentDir()/rootstock/target-state.json, written on the FMS-attach rising edge.
   * Read by the deploy gate (§7.4) to decide whether a dirty tree blocks. Never throws; a
   * missing or unreadable file is false, because an unreadable file must never block a fix.
   */
  public static boolean everFMSAttached();

  // ---- Enable state. These are the calls that replace DriverStation.* everywhere else. ----
  public static boolean isDisabled();
  public static boolean isEnabled();
  public static boolean isAutonomous();
  public static boolean isTeleop();
  public static boolean isEStopped();

  /**
   * DS "Test" mode in 2026; renamed "Utility" in 2027. Callers say isDiagnostics() and never
   * have to care. This is the ONLY spelling in Rootstock — Platform deliberately does not
   * have a diagnosticsMode() twin (§5.3 Part B).
   */
  public static boolean isDiagnostics();

  // ---- HID slot verification, for DsMonitor (§8.5, §9.5) --------------------------------
  /** DriverStation.getJoystickName(port). Empty string when the slot is empty. */
  public static String joystickName(int port);
  public static boolean joystickIsXbox(int port);
  public static boolean joystickConnected(int port);

  /** Event/match identity, present only when FMS supplies it. */
  public static Optional<MatchInfo> match();

  /** Approximate; DO NOT use for control decisions. Exposed because pit displays want it. */
  public static Time matchTimeRemaining();

  public static MatchPhase phase();          // DISABLED, AUTONOMOUS, TELEOP, DIAGNOSTICS, ESTOPPED

  public static Trigger onDsAttach();
  public static Trigger onFmsAttach();
  public static Trigger onAllianceKnown();
  public static Trigger onMatchStart();      // first enable after FMS attach
  public static Trigger onMatchEnd();

  /** "CURIE_Q34_a1b2c3d" — event, match, git sha. Used for log naming. */
  public static String logFileStem();

  /** Optional, offline-only. Reads deploy/rootstock/schedule.json if present. See §12. */
  public static Optional<MatchInfo> scheduledMatch();

  // ---- Lifecycle. Two methods, two different rates, and the split is deliberate. --------

  /**
   * EVERY loop, called by RootstockRobot. ~20 us of boolean reads plus edge latching: alliance
   * latch, FMS/DS attach edges, everFMSAttached persistence, phase transitions. This is not a
   * slice because its entire value is catching an edge on the cycle it happens.
   */
  static void periodic();

  /**
   * ONE SLICE of the shared round-robin (§8.3.1) — the derived NT publishing under
   * /Rootstock/Match/. Registered as SliceScheduler.register("Match", MatchContext::poll).
   * There is no wall-clock gate here and there must never be one.
   */
  static void poll();
}
```

```java
package org.rootstock.core.match;

import edu.wpi.first.wpilibj.DriverStation.MatchType;

public record MatchInfo(String eventName, MatchType matchType, int matchNumber, int replayNumber) {
  /** "Q34", "SF2m1", "P3". */
  public String shortLabel();
}
```

```java
package org.rootstock.core.match;

/**
 * The single place Rootstock decides what is unsafe at an event.
 * design/02 must consult tunablesLocked() on EVERY tunable read -- DogLog's semantic, copied
 * verbatim, because a stray dashboard edit mid-event is the failure that loses matches.
 * The disabled path must be constant-time (design/02's M6 gate): a lockout that costs a map
 * lookup per read is a lockout somebody eventually removes for performance.
 */
public final class FmsPolicy {
  public static boolean tunablesLocked();          // true when isFMSAttached()
  public static boolean selfTestAllowed();         // false when FMS-attached and enabled
  public static boolean verboseTelemetryAllowed(); // false when FMS-attached, unless overridden
  public static void allowTunablesAtEvent(boolean yesReally);  // loud, logged, alerts
}
```

Everything Rootstock derives from FMS/DS **automatically**, with no user code:

1. **Alliance-aware path flipping.** `AutoBuilder.configure(..., MatchContext::isRed, drive)`. This single substitution eliminates the most common competition-day auto failure. [`design/05`](05-drivetrain-auto.md) wires it; teams never write the lambda.
2. **Match-aware log naming.** WPILib's `DataLogManager` already renames to `FRC_yyyyMMdd_HHmmss_{event}_{match}.wpilog` on FMS attach, but **AdvantageKit's `WPILOGWriter` does not inherit this** and produces hash names like `akit_6497c321bb716896.wpilog`. **Core** renames on the FMS-attach edge to `MatchContext.logFileStem()`. (This lived in `rootstock-advantagekit` before decision 3; that artifact no longer exists, so the rename is now unconditional core behavior rather than something a team got only if they installed the right adapter — a small, genuine improvement.) This is a real gap and it costs you the ability to find "the log from Qual 34."
3. **FMS data as signals, not metadata.** AdvantageKit's `Logger.recordMetadata()` is write-once before `Logger.start()`, and FMS data only arrives when the DS connects — so event/match **cannot** be metadata. We log them as normal signals under `/Rootstock/Match/` and put only build/identity data in metadata.
4. **Tunable lockout** (above).
5. **Self-test lockout** — `SelfTest.runAll()` refuses to schedule while FMS-attached and enabled.
6. **Telemetry tiering** — verbose per-signal logging drops to the competition tier on FMS attach unless explicitly overridden.
7. **Git-dirty alert** — a `WARNING` / `PIT_ONLY` "Deployed code has uncommitted changes" is raised **always**, in the shop and at an event, because §7.4 now lets a dirty build deploy and the alert is the thing that preserves provenance in exchange. `PIT_ONLY` is what keeps it from nagging: it never occupies a driver slot. Only the *Elastic notification* is gated on FMS attach, and only on the rising edge.
8. **Alliance-unknown alert** — `ERROR` / `BLOCKS_MATCH` if the match enables and `allianceKnown()` is still false. Blocking is correct here: every path in the auto is about to be mirrored the wrong way.
9. **Energy/battery reset** — `PowerMonitor.matchEnergy()` resets on the match-start edge, so the per-match number is real.
10. **Rumble/LED policy** — LEDs go alliance-colored when alliance latches; rumble patterns are suppressed while disabled.

---

## 11. Deploy Metadata and Config Backup/Restore

### 11.1 Deploy metadata — "is the code on this robot what is in git?"

`gversion` generates `BuildConstants` with `MAVEN_GROUP`, `MAVEN_NAME`, `VERSION`, `GIT_REVISION`, `GIT_SHA`, `GIT_DATE`, `GIT_BRANCH`, `BUILD_DATE`, `BUILD_UNIX_TIME`, and `DIRTY` (0 clean / 1 uncommitted / −1 error) (verified against the WPILib docs page, 2026-08-06). Teams generate it and then nothing surfaces it.

A vendordep cannot add a Gradle plugin, and `BuildConstants` lives in the *team's* package (`frc.robot`), so Rootstock cannot import it. We resolve it reflectively, once, at boot, and degrade cleanly:

```java
package org.rootstock.core.config;

import java.util.Optional;

/** Git provenance of the running code. Never throws; reports UNKNOWN if gversion is not wired. */
public final class DeployInfo {
  public static String gitSha();        // "a1b2c3d" or "UNKNOWN"
  public static String gitBranch();
  public static String buildDate();
  public static Dirty dirty();          // CLEAN | DIRTY | UNKNOWN
  public static String rootstockVersion();
  public static Optional<Integer> teamNumber();

  /** One line for the pit display and the log header. */
  public static String summary();       // "8793 comp | main@a1b2c3d (dirty) | rootstock 2026.1.0"

  public enum Dirty { CLEAN, DIRTY, UNKNOWN }

  /** Called by RootstockRobot before the logger starts, so this lands in log metadata. */
  static void resolve(String buildConstantsClassName);   // default "frc.robot.BuildConstants"
}
```

Published to `/Rootstock/Meta/` and pushed through **`RootstockLifecycle`'s `metadata(String, String)` hook before `Logger.start()`** — not through any sink, because there is no sink SPI (decision 3, §4.6). `Logger.recordMetadata` is write-once pre-`start()`, so this ordering is a hard constraint rather than a convention, and `RootstockLifecycle` owns it. Alerts:

* `WARNING` / `PIT_ONLY` "Deployed code has uncommitted changes (git dirty) — this build cannot be traced to a commit" whenever `dirty() == DIRTY`. **Unconditional**, not gated on FMS attach: §7.4 deliberately lets a dirty build reach the robot, and this alert plus `/Rootstock/Meta/GitDirty` is the entire consideration we get in return. Gating it on FMS would mean the shop deploy that actually created the untraceable jar is the one that says nothing. `PIT_ONLY` is what makes "always on" affordable — it can never crowd out one of the three driver rows (§8.2.1).
* `MatchContext.onFmsAttach()` additionally fires **one** Elastic notification on the rising edge when dirty, because that is the moment the fact becomes expensive.
* `INFO` / `PIT_ONLY` "Build provenance unavailable — add the gversion plugin (see rootstock.dev/install)" when `UNKNOWN`.

### 11.2 Tuned-config backup and restore

`Preferences` persists on the roboRIO filesystem, **not in git**. A RIO reimage, a robot swap, or a fresh image silently reverts every tuned value with no warning. For a small team with one programmer that is unrecoverable at an event.

```java
package org.rootstock.core.config;

import java.nio.file.Path;
import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

/**
 * Every resolved Rootstock config record registers here at construction, and every Tunable
 * registers its live value. Gives one answer to "what did this robot actually run with?".
 */
public final class ConfigRegistry {
  public static <T extends edu.wpi.first.util.struct.StructSerializable> void register(String path, T config);
  public static void registerTunable(String key, java.util.function.DoubleSupplier live, double compiled);
  /** Logs every registered config as a struct at boot -- the introspectability of JSON, with a compiler. */
  public static void publishAll();
}
```

```java
package org.rootstock.core.config;

import java.nio.file.Path;
import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

public final class ConfigSnapshot {
  /**
   * Writes every Tunable + every WPILib Preference to
   *   <persistentDir>/rootstock/snapshots/<timestamp>_<robotId>_<gitSha>.json
   * AND to the same file name under deploy/rootstock/ on the next `rootstock pull` so it lands in git.
   */
  public static Path take();

  /** Bind to a pit button. Requires MatchContext.isDiagnostics(). */
  public static Command takeCommand();

  /** Reload a snapshot into Preferences and the Tunable store. */
  public static void restore(Path json);

  /** Compares live values to deploy/rootstock/config-snapshot.json (the committed baseline). */
  public static List<Drift> drift();

  public record Drift(String key, double committed, double live) {}
}
```

At boot, if `drift()` is non-empty, a `WARNING` / `PIT_ONLY` alert lists the first three drifted keys by name: *"3 tuned values differ from the committed snapshot: Elevator/kP 45.0 → 52.5, Shooter/kV …"*. That single alert is what turns "the robot behaves differently than the code says" from a mystery into a fact.

CLI side: `rootstock pull-config` downloads the newest snapshot from the robot over the same port-5800 web server Elastic uses, drops it into `src/main/deploy/rootstock/config-snapshot.json`, and prints `git diff`. Tuning at the field becomes a committable artifact.

`rootstock sync` is the same transport used for the other direction of §7.4: it pulls `rootstock/target-state.json` (the `everFMSAttached` flag `MatchContext` persists) into `build/rootstock/` so the deploy gate can read it. Both are best-effort with a 500 ms timeout, and both treat an unreachable robot as "no information", never as a failure — an unreachable robot must never be a reason to refuse to fix the robot.

---

## 12. Scouting, The Blue Alliance, and Statbotics

**Recommendation: this does not belong in a robot-code library. Exclude it.** Three independent, sufficient reasons:

1. **Physics.** During a match the robot is on an isolated FMS field network and cannot reach `thebluealliance.com`. An HTTP client in robot code is dead weight at best.
2. **Loop time.** Any blocking network call in a 20 ms periodic is a brownout-adjacent failure mode, and teams *will* put it in `periodic()`. Shipping the capability is shipping the footgun.
3. **Scope and cadence.** It shares zero dependencies, zero types, and zero lifecycle with robot code. Bundling it inflates the jar, drags an HTTP/JSON stack into the robot classpath, and couples Rootstock's release cadence to two third-party web APIs. Better tools already exist (Lookout, Arcbotics, Pre-ScoutingApp).

The **one** defensible robot-adjacent slice is offline:

```java
package org.rootstock.core.match;

/** Reads deploy/rootstock/schedule.json if present. NO network, NO HTTP dependency, ever. */
public final class MatchSchedule {
  public static void loadFromDeploy();          // called by RootstockRobot; silently no-ops if absent
  public static java.util.Optional<MatchInfo> nextScheduled();
}
```

That file is produced **before** the event by a separate desktop tool, which we ship as its own repo:

* **`rootstock-scout`** — separate GitHub repo, separate versioning, desktop/CLI only, never a robot dependency. Pulls TBA APIv3 (`https://www.thebluealliance.com/api/v3`, `X-TBA-Auth-Key` header) and Statbotics EPA, and emits `schedule.json` into the robot project's deploy directory. **This is out of scope permanently, not deferred** — decision 1 abolished deferral within Rootstock's scope, and `rootstock-scout` is outside that scope by the three arguments above, not queued behind it. If it is ever built it is a different product with a different repo and a different release cadence, and building it costs Rootstock nothing.

We say this explicitly in the README's "What Rootstock is not" section, because a "one-stop shop" claim invites the question and a vague answer invites a bad PR.

---

## 13. End-to-End Example

This is the complete platform-layer code a team writes. Mechanisms come from the template's worked examples (§6.2) or from `rootstock gen mechanism` ([`design/01`](01-core-mechanisms.md), M23); everything below is hand-written and is the entire cost of the platform domain. **A team arriving through the front door does not type any of it** — it is what `Use this template` hands them, already compiling. It is reproduced here because a team on the vendordep-only path does write it, and because a design document that cannot show its own output is not finished.

**`Robot.java` — the full-adoption shape.** Counting only executable code: **one field, three constructor statements, and one empty override.** Everything else in the block is comment. *(Revision 5 headed this snippet "22 lines including comments". That count is not re-asserted, because it no longer matches the block and because a line count in prose is a number nothing checks — the same class of unverified claim this document has already been bitten by once. The statement count above is checkable by reading the fence.)* (Shipped in the template; a team using the vendordep-only path writes it from this snippet in the docs.)

```java
package frc.robot;

import org.rootstock.core.RootstockRobot;
import org.rootstock.core.spi.LogConfig;       // core.spi, NOT telemetry -- ArchUnit rule 9
                                                // (2026-08-08). `design/04` §2.2b still OWNS this
                                                // type: it is the sole definition site and every
                                                // field's meaning is telemetry's. Only the package
                                                // moved, because RootstockRobot(LogConfig) is a core
                                                // signature and rule 9 forbids an arrow out of core.
                                                // Tier and RobotMode moved with it -- LogConfig's
                                                // own minimumTier()/mode() named them, which was the
                                                // same arrow one level down. `design/01` §1.1a.

// RootstockRobot extends AdvantageKit's LoggedRobot (decision 3 — ONE class, not the old
// RootstockRobot/RootstockLoggedRobot split). A team writes exactly this either way; the
// difference is that deterministic replay is now guaranteed rather than backend-dependent.
public class Robot extends RootstockRobot {
  private final RobotContainer m_container;

  public Robot() {
    // Registers: identity resolution, deploy metadata, alert registry, health monitors
    // (CAN, battery, 5V rail, brownout, loop time, git-dirty, DS), power monitor,
    // match context, the AdvantageKit Logger (metadata injected BEFORE start, then the
    // FMS-attach WPILOG rename), Elastic web server on 5800, LED defaults, rumble
    // auto-zero, config drift check, and the /Rootstock NT namespace. All of it.
    //
    // THE ARGUMENT IS AN IMMUTABLE LogConfig VALUE, not a Consumer and not a Builder
    // (DESIGN.md D13a). defaults() means "RootstockLifecycle configures AND starts
    // AdvantageKit's Logger" -- which is right here, because this file did not start it.
    // Revision 5 of this document wrote `super();` with the comment "No arguments"; that
    // was one of the three incompatible spellings D13a exists to collapse, and the bare
    // no-arg form still works (it delegates to this(LogConfig.defaults())) -- it is spelled
    // out because "who starts the Logger" is a decision the design refuses to hide (D29).
    super(LogConfig.defaults()
              .withWpilogFolder("/U/logs")       // `design/04` §2.2 field: wpilogFolder
              .withCtreSignalLogger(true));      // `design/04` §2.2 field: ctreSignalLogger

    m_container = new RobotContainer();          // registers via RootstockRegistry.addAll

    // Last, after your components exist, so the boot dump reflects what you registered.
    // init() is idempotent (D29): omit it and the first beforeUserPeriodic() runs it.
    // There is NO robotInit() in this file and none in RootstockRobot -- D13a deleted the
    // override, D29 renamed the lifecycle method from robotInit() to init(), and ArchUnit
    // rule 5 therefore needs no exception (§8.1, and `design/01` §1.1a's supersession note).
    lifecycle().init();
  }

  @Override
  public void teleopInit() {
    // RootstockRobot has already done its own teleopInit; super is called for you.
  }
}
```

**`Robot.java` — the partial-adoption shape**, for the team that keeps its own base class. This is the path **D29 requires to stay open** and the reason `RootstockLifecycle` is public; it is the `health-only` CI fixture, and `design/01` §1.1a is the matching declaration site.

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.littletonrobotics.junction.LoggedRobot;   // the team's own base class, untouched
import org.rootstock.core.RootstockLifecycle;
import org.rootstock.core.spi.LogConfig;            // core.spi, NOT telemetry -- rule 9, same as
                                                     // the full-adoption shape above. Telemetry
                                                     // still owns the type's meaning; the package
                                                     // is where rule 9 requires it to be.

// No Rootstock type is extended anywhere in this file. Everything §13's first shape gets
// for free is reached here by driving five methods by hand.
public class Robot extends LoggedRobot {            // or TimedRobot -- see the cost below
  private final RootstockLifecycle m_rootstock;
  private final RobotContainer m_container;

  public Robot() {
    // adoptExistingLogger(), NOT defaults(): this repo's own code already called
    // Logger.start(). defaults() would start it a SECOND time, which is a hard boot
    // failure, not a warning. The library does not detect this and does not guess (D29).
    m_rootstock   = RootstockLifecycle.create(LogConfig.adoptExistingLogger());
    m_container = new RobotContainer();                // ...existing construction, unchanged...
    m_rootstock.init();                                // last: after everything is registered
  }

  @Override public void robotPeriodic() {
    m_rootstock.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_rootstock.afterUserPeriodic();
  }
  @Override public void disabledInit() { m_rootstock.disabledInit(); }
  @Override public void close()        { m_rootstock.close(); }
}
```

> **What the second shape costs, stated exactly, because two other documents state it more bluntly.** Keeping your base class gets you the whole M1 platform value line — alerts, the seven health monitors, `SelfTest`, `MatchContext`, `RobotIdentity`, `CanIdRegistry`, `ControlMap`, `SliceScheduler`, the `/Rootstock` namespace. It does **not** get you deterministic replay if your base class is a plain `TimedRobot`, because AdvantageKit's replay driver requires `LoggedRobot` to own the loop and feed it from the log. And it does not avoid the AdvantageKit **dependency**, which is a hard `requires` on `Rootstock.json` either way: **incremental adoption is about your code, not about your dependency graph** (D29). The honest ranking is `RootstockRobot` → your own `LoggedRobot` → `TimedRobot` with replay given up, which is why all four `IncrementalAdoptionTest` fixtures are written as `extends LoggedRobot`.

**`RobotContainer.java` — the platform-relevant parts.**

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.wpilibj.PowerDistribution.ModuleType;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import frc.robot.robots.RobotIds;
import frc.robot.subsystems.Arm;          // generated by `rootstock gen mechanism`
import frc.robot.subsystems.Elevator;     // generated
import frc.robot.subsystems.Drive;        // generated
import org.rootstock.core.RootstockRegistry;
import org.rootstock.core.hid.ControlMap;
import org.rootstock.core.hid.Rumble;
import org.rootstock.core.hid.RumblePattern;
import org.rootstock.core.match.MatchContext;
import org.rootstock.core.power.PowerMonitor;
import org.rootstock.core.selftest.SelfTest;

public class RobotContainer {
  private final CommandXboxController m_driver = new CommandXboxController(0);

  private final Drive    m_drive;
  private final Elevator m_elevator;
  private final Arm      m_arm;

  public RobotContainer() {
    RobotIds.register();                       // one line: identity + per-robot overlays (§7.3)
                                               // CanIdRegistry is scanned once inside
                                               // RootstockRegistry.addAll below, never in a
                                               // record constructor (§3.4).

    m_drive    = new Drive(RobotIds.kDrive);
    m_elevator = new Elevator(RobotIds.kElevator);
    m_arm      = new Arm(RobotIds.kArm);

    // ONE list (D27). RootstockRegistry inspects each component once and routes it:
    //   instanceof TelemetrySource -> telemetry     instanceof HealthSource  -> HealthMonitor
    //   instanceof SelfTestable    -> SelfTest      instanceof TuningTarget  -> TuningRegistry
    //   instanceof Subsystem       -> the scheduler
    // Mechanisms implement all of these already, so health, self-test, telemetry and tuning
    // registration are this one line. Opting out is `.excludeFrom(Registry.TUNING)` on the
    // mechanism's config, which is visible in describe() rather than absent from a call site.
    // It prints a boot summary -- "Registered 3 components: 3 telemetry, 3 health, 3 selftest,
    // 3 tuning" -- so a missing registration is a diff in the boot dump, not a mystery.
    RootstockRegistry.addAll(m_drive, m_elevator, m_arm);

    // Power: name the channels once, get named logs and breaker alerts forever.
    PowerMonitor.pdh()
        .channel(0,  "Drive FL", 40).channel(1,  "Drive FR", 40)
        .channel(2,  "Drive BL", 40).channel(3,  "Drive BR", 40)
        .channel(10, "Elevator leader", 40)
        .channel(11, "Arm", 30);

    configureBindings();
  }

  private void configureBindings() {
    // D30: at least one mode MUST be named MANUAL, or publish() raises a persistent Alert.
    // Every binding inside a mode block is automatically ANDed with inMode(name).
    ControlMap.of("Driver", m_driver)
        .mode("MANUAL", m -> m
            .whileTrue("Elevator up",   c -> c.rightBumper(), m_elevator.manualUp())
            .whileTrue("Elevator down", c -> c.leftBumper(),  m_elevator.manualDown()))
        .mode("AUTOMATED", m -> m
            .whileTrue("Score L4",   c -> c.rightBumper(), m_elevator.toPreset("L4"))
            .whileTrue("Intake",     c -> c.leftTrigger(), m_arm.intake()))
        .modeSelector(m_driver.povUp())
        // Unmoded bindings are always live, in every mode.
        .onTrue   ("Zero gyro",  c -> c.start(),       m_drive.zeroHeading())
        // one button, whole-robot self-test, green/red list on Elastic
        .onTrue   ("Self test",  c -> c.back(),        SelfTest.runAll())
        .publish();

    // Haptics: driver feels the game piece instead of looking away from the field.
    m_arm.hasGamePiece().onTrue(
        Rumble.on(m_driver.getHID()).side(kLeftRumble).priority(50).play(RumblePattern.doubleTap()));
  }
}
```

**What the team did NOT write, and gets anyway:**

* One registration call instead of four parallel lists, with a boot summary that makes a missed registration a diff.
* Robot identity detection, per-robot constants, and a build-time gate against deploying sim config to hardware.
* A CAN-ID uniqueness scan that produces a named `ConfigError` and `SAFE_MODE` instead of a boot crash.
* CAN utilization + error-delta monitoring on every bus, with alerts.
* Battery resting-voltage and sag detection correlated against PDH current.
* 5 V rail fault counting (the "your encoder lied to you" signal).
* Latched brownout detection with a timestamp.
* Git provenance in the log and a "this code is not in git" alert at an event.
* Per-domain loop-time budgets so overruns are attributable.
* Alliance latched on DS connect, with an `ERROR` / `BLOCKS_MATCH` alert if it is still unknown at enable, wired straight into PathPlanner's flip supplier.
* Match-aware WPILOG naming (`CURIE_Q34_a1b2c3d.wpilog`).
* Tunables that go inert on FMS attach.
* A config-drift alert when the robot's tuned values differ from what is committed.
* Elastic layout served from the robot on port 5800.
* Priority-arbitrated LEDs showing robot health, with rumble auto-zeroed on disable.
* A `CONTROLS.md` control-map poster regenerated from the bindings.
* A one-button pre-match self-test with per-step pass/fail on the pit display.
* All of the above working identically in `./gradlew simulateJava` with no robot present.

---

## 14. What We Deliberately Do NOT Do

| We don't build | Because this already does it |
|---|---|
| A logging or replay framework | **AdvantageKit**, and as of maintainer decision 3 it is a **required dependency**, not one option among several. We do not ship a `TelemetrySink` SPI, a DogLog backend, an Epilogue backend or an NT4 backend, and we will not. **The honest cost, restated here so it is visible in the same table as the benefits:** the ecosystem is genuinely split — 254 and 3061 use AdvantageKit, Spectrum 3847 uses DogLog — and hard-wiring one **does** cut the addressable audience, exactly as revision 1 warned. Teams on DogLog or plain Epilogue cannot adopt Rootstock without switching loggers, and that gets worse every year Epilogue improves, because Epilogue is first-party and is where WPILib is investing. What we bought is that deterministic replay, the M20 replay lint and `RootstockReplayVerify` are unconditional guarantees rather than backend-dependent ones. That is a real thing to have bought. It is not obviously worth the price, and the 2029 relevance review ([`ROADMAP.md` §7.3](../ROADMAP.md)) is scheduled to re-ask. |
| A driver dashboard | **Elastic**. We vendor ElasticLib properly and ship a layout; we do not write a dashboard. |
| A log viewer / replay UI | **AdvantageScope**. Our job is to make the logs good: `/Rootstock/…` namespaces and units on every signal. |
| A live-debug UI | **Glass**. It reads the same NT4 keys; nothing to integrate. |
| A pit-display desktop app | Team 135's Squire proves the concept, but a small team should not install a second app. Our self-test reports into Elastic, which they already have open. |
| Anything on Shuffleboard or SmartDashboard | Both **deleted in 2027**, along with NT3. Raw NT4 only. |
| A path planner or trajectory optimizer | **PathPlanner** (2026.1.2) and **Choreo** (2026.0.3). We supply only the alliance-flip supplier and the four-method drivetrain contract. |
| Swerve kinematics or odometry | **WPILib**, **CTRE SwerveDrivetrain**, **YAGSL**. |
| Vision pose estimation | **PhotonVision** (2026.3.4) and **LimelightHelpers** MegaTag2. |
| System identification | **WPILib `SysIdRoutine`** + the bundled SysId tool. |
| Motor control primitives | **Phoenix 6** and **REVLib**. We never wrap on-motor closed loop behind a roboRIO-side controller. |
| Game-piece physics simulation | **maple-sim** — integrated as an *optional* adapter only, because it is beta and carries documented succession risk. Never a required dependency. |
| A whole-robot state machine | **WPILib 2027 Commands v3** ships a declarative state machine API. Building ours would be an immediate, publicly-called-out duplication. |
| Command-group / sequencing utilities | Commands v3's `coroutine.await()` obsoletes most of them. |
| A tunable-number *storage* system independent of WPILib | WPILib 2027 is adding a first-party **Tunable** API. [`design/02`](02-tuning.md)'s `Tunable` is designed to re-point at it. We own only the FMS-lockout *policy* and the snapshot/restore. |
| A scouting app, TBA client, or Statbotics client in robot code | **Lookout**, **Arcbotics**, **Pre-ScoutingApp**. See §12. |
| C++ or Python bindings | Java is ~90% of FRC and the reason help is available at events. |
| Hardware fault polling at 50 Hz — **or at 4 Hz** | 135 measured full fault checking at ~10 ms/loop, over half a 20 ms budget. Polling everything at 4 Hz does not make that work cheaper, it makes it bursty: a 10 ms spike every 250 ms overruns every twelfth loop, and a wall-clock gate is nondeterministic under replay. We run exactly **one** health slice per loop, round-robin, cycle-counted (§8.3). Flat tail, deterministic replay, 11 × 20 ms = 220 ms full sweep. |
| A network / NT-bandwidth monitor — **not yet, and it is an open question rather than a rejection** | `design/03` §5.2 names network transit as a failure mode that only appears at an event, and no built-in monitor watches it (§15 item 14). It is the strongest candidate for an eighth monitor type. It is not in the seven because adding it is a four-document change plus a `BuiltinMonitorCountTest` edit, and because we do not yet know what a *good* threshold is — a monitor that fires on venue wifi every match is the fifteen-red-rows problem with extra steps. |

---

## 15. Open Questions

1. **`RobotController.getSerialNumber()` / `getComments()` on SystemCore.** Both are the cleanest identity mechanisms available on roboRIO and neither is confirmed to exist on SystemCore. Mitigation is already in the design (the persistent-file strategy is primary), but if both survive we should re-order the resolution chain to put `getComments()` first for UX. **Re-verify at the 2027 beta.**
2. **The exact `edu.wpi.first.*` → `org.wpilib.*` subpackage map.** Confirmed for `math`, `hal` (→ `org.wpilib.hardware.hal`). **[UNVERIFIED]** for `wpilibj`, `wpilibj2.command`, `units`, `net`, `util`, `networktables`. Every row of `wpi-rename-2027.properties` is a guess until a 2027 compile says otherwise, and under the new timing that compile is **M12** (§5.3 Part C) — the map is a research note, not build infrastructure, until then. The dual-compile job is the safety net *inside* M12, and the map must be reviewed by a human at the alpha.
3. **Does the `Alert` group NT path stay at `/SmartDashboard/<Group>` in 2027?** It is an NT3-era location and SmartDashboard is gone. If it moves, Elastic's Alerts widget and ours both need updating. Only `org.rootstock.core.alert` is affected, by design.
4. **Is a per-LED `SolidColor` write to a 60-LED CANdle affordable at 50 Hz on a shared CAN bus?** If not, the CANdle backend must offload to device-side animation slots and accept a reduced expressive subset. **Measure before shipping `CandleBackend`.** 8793's `LEDSubsystem` already found that a *software* strobe beats the device-side `StrobeAnimation` because the device animation can persist past its window — that finding should inform the mapping.
5. **Who owns `Tunable` — [`design/02`](02-tuning.md) or platform?** Current split, and it is D11's: tuning owns the type and the NT plumbing; platform owns `FmsPolicy.tunablesLocked()` and `ConfigSnapshot`. This requires the tuning package to depend on core, which is fine and is the direction ArchUnit rule 9 mandates; the reverse dependency (platform's snapshot needs to enumerate live tunables) is solved by a **push**-registration callback — `TuningRegistry` push-registers every tunable with `ConfigRegistry`, per D11 — rather than a query API, precisely so no arrow points out of core. Confirm with `design/02` that push-registration is what it implements.
6. **Does `StructGenerator.genRecord()` support `Measure`-typed record components?** `ConfigRegistry.publishAll()` logging resolved configs as structs depends on it. WPILib's docs do not enumerate supported component types. **Must be tested**; fallback is to log a flattened `double`-only projection of each config.
7. **`vendor-json-repo` listing timing.** Getting into the official picker requires a PR that maintainers merge on their schedule. This is now an **M24** item (§4.5) — listing an unannounced internal snapshot would forfeit every benefit of not shipping. The open question is narrower: in the season we do list, open the PR during the **beta** bundle window so we appear on kickoff day rather than three weeks later.
8. **Maven Central namespace verification for `dev.rootstock`.** Requires owning `rootstock.dev` and a DNS TXT record. If that domain is unavailable, fall back to `io.github.<org>` (Central supports GitHub-org verification) and keep the *package* root `org.rootstock` regardless. **Under a three-to-four-year runway this is also a renewal question:** the domain must be paid for continuously from M1 to M24 and beyond, and a lapsed domain breaks `jsonUrl` and `mavenUrls` for every installed team. Multi-year registration and an auto-renew card are the mitigation, and the Central mirror is the backstop if it fails anyway.
9. ~~**Should `RootstockRobot` extend `TimedRobot` or `LoggedRobot`?**~~ **ANSWERED by maintainer decision 3: `RootstockRobot extends LoggedRobot`, one class.** The D13 split (`RootstockRobot extends TimedRobot` in core plus `RootstockLoggedRobot extends LoggedRobot` in a separate artifact, sharing an internal lifecycle object) is collapsed. `RootstockLifecycle` remains **public** (D29 survives — manual wiring is documented first, and a team may drive the lifecycle from their own `TimedRobot` subclass if they want to), and `RootstockRobot` is a ~20-line delegating shim over it. What replaced this question is §4.8: not *which* base class, but *what we do if the project supplying it does not port.*
10. **Commands v3 adapter scope.** v3 is Java-only, coroutine-based, and has a documented footgun (a missing `coroutine.yield()` is uncheckable at compile time; WPILib concedes *"A watchdog will be necessary"*). We do not build on it before M12. Open question: do we ship the missing watchdog — a scheduler wrapper that raises a named alert when a command exceeds its budget without yielding — as a Rootstock contribution? It is squarely in our "zero-mystery debugging" mission. **Decision 1 changes the calculus:** the v3 adapter is now inside M12 rather than after a shipped v1, so we will be writing v3 code during the beta window when WPILib is still fielding questions — the best possible moment to contribute the watchdog upstream rather than ship our own.
11. ~~***(new)* Does AdvantageKit's license permit the `rootstock-akit-compat` fork in §4.8 tier 1?**~~ **ANSWERED 2026-08-08, and the tier is renumbered: it is tier 2.** AdvantageKit is **BSD-3-Clause** (`Copyright (c) 2021-2026 Littleton Robotics`); redistribution and modification with attribution are permitted, so **tier 2 exists.** The only constraint is the non-endorsement clause, which governs how the compat artifact is *named and described*, not whether it may be published — §4.8 tier 2 and §4.3.1 carry the wording. **Residual:** re-read the LICENSE on the **2027 branch** at the trigger date; a license can differ between branches and a contingency gated on a 2026 reading is not a contingency. This was called "the single most consequential unverified fact in this document" while being a thirty-second fetch, which is the verification failure `REVIEW.md` M15 correctly names.
12. ~~***(new)* Is a simulation-only build of Rootstock possible without AdvantageKit's native artifacts?**~~ **ANSWERED 2026-08-08, and the answer is "no, but that matters less than we feared."** `AdvantageKit.json` declares exactly one JNI dependency (`org.littletonrobotics.akit:akit-wpilibio`, `isJar: false`, `skipInvalidPlatforms: false`) whose `validPlatforms` are `linuxathena, linuxx86-64, linuxarm64, osxuniversal, windowsx86-64`, and `cppDependencies` is empty. So **desktop simulation consumes a native artifact too** — there is no pure-Java sim path — but the three desktop triples are **platform-stable across the SystemCore transition**, so the 2027 delta is one new triple replacing one old one. **What `rootstock doctor` must print, and what R18's blast-radius sentence must say:** if AdvantageKit publishes a 2027 line at all, desktop development is very likely to keep working and only robot deploy is gated on `linuxsystemcore`; if it publishes nothing, nothing works. §4.1 and §4.8 carry this. **Residual:** whether the *2027* vendordep keeps this shape — the same fetch that arms the R18 trigger.
13. ***(new)* Does the `.rootstock/template.lock` hash manifest survive Windows line-ending translation?** A `core.autocrlf=true` clone rewrites every text file's line endings, which changes every hash, which would classify a *pristine* fork as 100% `MODIFIED-BY-TEAM` on Windows — the platform most FRC teams use. The likely answer is hashing normalized content rather than bytes, plus a committed `.gitattributes`, but **this must be tested on a real Windows clone in the 9-job matrix (§4.7 step 4) rather than reasoned about**, because getting it wrong makes the entire drift feature useless for most of the audience on day one.
14. ***(new, 2026-08-08)* Should there be an eighth monitor type watching network transit / NT bandwidth?** `REVIEW.md`'s minor list proposes one, and the case is good: [`design/03`](03-vision.md) §5.2 names network transit as a failure mode that appears **only at an event**, on the venue's radio, under FMS bandwidth limits — which is exactly the class of failure this domain exists to catch, and exactly the class nobody can reproduce in the shop.
    **Reviewer pushback:** the review suggests adding it and updating the count test.
    **Why we keep seven for now:** two reasons, neither of which is "it would be work". First, **we do not know the threshold.** A bandwidth monitor with a wrong threshold fires on every match on venue wifi, which manufactures precisely the red-rows-you-learn-to-ignore problem §8.2 is built to prevent — and unlike CAN utilization or rail faults, there is no vendor-documented healthy band to anchor on. Second, **the count is load-bearing in four documents and one CI test** (`DESIGN.md` §1/§9.6, `ROADMAP.md` M1, `DECISIONS.md` line 533, this §8.5, `BuiltinMonitorCountTest`), and a count that changes without a measurement behind it is how "ten health checks" happened the first time. **The prerequisite is data, not a decision:** measure `/Rootstock/**` NT publish bytes and round-trip latency against 8793's radio at a real event (M9 or later gives us the instrumentation), then choose a threshold, then add `NetworkMonitor` as the eighth type in one commit that touches the four documents and the test together. Logged here rather than dropped, because a monitor we decided not to build for a stated reason is a different thing from one nobody thought of.

---

## 16. Effort, and Where It Lands in the Milestone Plan

**There is no v0.1 subset of this domain any more. Everything in this document is v0.1 scope (maintainer decision 1).** The P0…P6 phases below survive **only as internal build order** — they are how the work is sequenced, not what is released when. Nothing is released until M24. The authoritative schedule, capacity table and dates are in [`ROADMAP.md`](../ROADMAP.md); this section maps this domain's work onto it and states the number.

| Phase (build order only) | Contents | Lands in | Person-weeks |
|---|---|---|---|
| **P0 — skeleton** (must precede all other domains) | Gradle layout, `buildSrc` conventions, all twelve ArchUnit rules (§5.3 Part A maps five of them), the `compat` package (five classes), **`RootstockLifecycle` + `RootstockRobot extends LoggedRobot` (one class)**, **`RootstockRegistry.addAll(...)` with `instanceof` routing and the boot summary (D27)**, `/Rootstock` NT namespace, `core.spi` + `ServiceLoader` **for out-of-jar adapters only**, `ControlMap` with modes and a mandatory `MANUAL` (D30), `rootstock-testkit` headless harness | **M1** | 1.5 |
| **P1 — distribution + the template** | Vendordep JSONs **with the AdvantageKit `requires`**, `verifyRequiresUuids`, GitHub Pages Maven, Central mirroring, CI publish, **`RootstockTemplate` all three variants**, `.rootstock/template.lock` + hash manifest, `rootstock init` **with vendor-set pruning (§6.2.1)**, `rootstock update --library` / `--template` / `--add-vendors`, `rootstock doctor --template`, `docs/UPDATING.md`, the **9-job template CI matrix**, the 3-OS install smoke test, **the AdvantageKit version-floor check in `rootstock doctor` (+0.05, see the reconciliation below)** | **M8** | 2.95 |
| **P2 — compday core** | `Alerts` + `RootstockAlert` + `MatchImpact` + `AlertRegistry` + the 3-row driver mirror, `SliceScheduler`, `HealthSource`/`HealthMonitor`/`RobotHealth`, **all seven built-in monitor types (including `LoopTimeMonitor`, which runs every loop rather than as a slice) + `BuiltinMonitorCountTest`**, `RootstockTracer` budgets, `MatchContext` + `FmsPolicy`, `DeployInfo`, `AlertBudgetTest` | **M1** | 3.0 |
| **P3 — identity & config** | `RobotIdentity` + overlays + `rootstockCheckDeploy` graded gate, `CanIdRegistry`, `ConfigRegistry`, `ConfigSnapshot` + drift alert, `rootstock pull-config`, `rootstock sync` | **M1** 1.0 (identity, CAN-ID registry, config registry/snapshot) / **M8** 0.5 (`rootstockCheckDeploy` with the Gradle plugin, `pull-config`, `sync`) | 1.5 |
| **P4 — self-test & pit** | `SelfTest` sequencer + NT publishing (**M1**, 1.0); `SelfTestRoutine` DSL builder, the shipped Elastic layout and the pit-workflow docs (**M14**, 1.0) | **M1** 1.0 + **M14** 1.0 | 2.0 |
| **P5 — power/pneumatics/LED/haptics** | `PowerMonitor`, `PowerBudget`, `EnergyTracker`, `Pneumatic`/`CompressorPolicy`, `LedController` + both backends, `Rumble` + `RumbleScheduler` | **M22** | 1.5 |
| **P6 — the WPILib 2027 port, platform share** | `wpi-rename-2027.properties` verified against a real compile, `generate2027Sources`, the `wpi2027` source set, dual-compile CI, the `compat` package's 2027 implementations, SystemCore platform facts, Commands v3 lifecycle adapter, the 2027 vendordep line, **all three template variants regenerated onto 2027**, and the **deletion** of the 2026 line and the generator at the end | **M12** — the only date-triggered milestone (§5.3 Part C.1). This 3.0 is **inside M12's global 8.0 pw**, not additional to it. | 3.0 |
| **Total, this domain** | | | **15.45 person-weeks** |
| *of which outside M12* | | | *12.45* |

Phase-column check: 1.5 + 2.95 + 3.0 + 1.5 + 2.0 + 1.5 + 3.0 = **15.45**. Outside M12: 15.45 − 3.0 = **12.45**.

**Reconciliation with the 13.0 pw in revision 1**, item by item, so the change is auditable rather than asserted:

| Change | Δ |
|---|---|
| **Decision 2** — `RootstockTemplate` authoring for three variants, `.rootstock/template.lock` + hash manifest, `rootstock update --library` / `--template`, `rootstock doctor --template`, `docs/UPDATING.md`, the 9-job matrix and its fixtures | **+1.50** |
| **Decision 3** — see the derivation below | **−0.25** |
| **P5 reconciled to `ROADMAP.md` M22's 1.5 pw** — `ControlMap` moved out of P5 into M1 (net zero, it just moved), and the remaining 0.3 is an integration saving **already taken** in `DESIGN.md` §12.1 that must not be counted twice here | **−0.30** |
| **P6 grows from a seam to an executed port** — revision 1 budgeted 1.5 pw to *build a bridge*; decision 1 puts the whole 2027 migration inside the build window, so this domain now carries its share of actually crossing it | **+1.50** |
| | **13.0 + 2.45 = 15.45** |

**The decision-3 row, derived, because it was booked as −0.3 here and −0.25 in two other documents.** `DESIGN.md` §9.4 and [`design/04`](04-telemetry-replay-viz.md) both split decision 3's library-wide **−1.25 pw** as *−1.00 telemetry, −0.25 doc 06*. −1.00 + −0.30 = −1.30 ≠ −1.25, so one of the three lines had to move. **This one moves, to −0.25**, and the reason is that the old decomposition double-counted:

| Component | Δ |
|---|---|
| `RootstockRobot`/`RootstockLoggedRobot` collapse to one class (D13) | −0.20 |
| `org.rootstock.core.nt.TelemetrySink` SPI deleted (§4.6) | −0.05 |
| `rootstock-advantagekit` folded in, `rootstock-doglog` and `Rootstock-AdvantageKit.json` deleted — **two** artifacts and one JSON off the release process | −0.05 |
| New AdvantageKit version-floor check in `rootstock doctor` (P1) and at boot (P2), which exists **only** because of decision 3 (§4.2.1) | **+0.05** |
| | **−0.25** ✓ |

**What was double-counted:** the old row booked **−0.1** for the artifact deletions on the grounds of *"one core artifact instead of eleven to publish and version."* But eleven-to-one is **D28**, a revision-2 decision whose ~0.5 pw saving was already banked in the 13.0 baseline and in `ROADMAP.md` §1's integration-savings line ("single-jar release process (D28) −0.5"). Decision 3 deleted **two** artifacts on top of D28's collapse, not nine; −0.05 is that, and only that. The `+0.05` floor check is booked into **P1's 2.95** rather than netted invisibly into this row, which is why the phase total moved from 15.4 to 15.45 rather than staying put. `DESIGN.md` §16 item 5(a) quotes "15.4" and needs to quote **15.45**; that is a contract request.

### 16.1 Reconciliation against the milestone budgets — and the gap, stated

**`ROADMAP.md` §5's milestone person-weeks are authoritative.** They sum to exactly 74.0 (M1–M8 = 18.6, M9–M24 = 55.4), every date in `ROADMAP.md` §5.1 and §2 is derived from that column, and this document does not get to move it. What this document *can* do is say, with arithmetic, that its own bottom-up decomposition does not fit inside it — which `DESIGN.md` §16 item 5(a) admits at the level of *domain totals* but not at the level of *individual phases exceeding entire milestones*, which is the sharper and more actionable statement.

| Milestone | `ROADMAP.md` global budget | This domain's booked share | What else that milestone must fund | Verdict |
|---|---|---|---|---|
| **M1** | **3.5** | P0 1.5 + P2 3.0 + P3 1.0 + P4 1.0 = **6.5** | **Nothing.** `ROADMAP.md` M1's deliverable list — Gradle multi-project, `buildSrc`, twelve ArchUnit rules, `compat`, `RootstockLifecycle`/`RootstockRobot`, `core.spi`, `RootstockRegistry`, `Alerts`/`MatchImpact`, `MatchContext`/`FmsPolicy`, `RobotIdentity`, `CanIdRegistry`, `SliceScheduler`, seven monitors, `SelfTest`, `RootstockTracer`, `ControlMap`, `rootstock-testkit` — is **entirely** platform-domain work. | **Over by 3.0 pw. M1 is 1.86× light** (6.5 ÷ 3.5). |
| **M8** | **2.8** | P1 2.95 + P3 0.5 = **3.45** | docs v1, `docs/graduation.md`, `docs/removing-rootstock.md` + `RipOutTest`, the four `IncrementalAdoptionTest` fixtures, `rootstock doctor --bundle`, docs-as-tests CI — **none of which is in P1 or P3 and none of which this domain prices.** | **Over by 0.65 pw before those items are priced at all.** |
| **M12** | 8.0 | P6 **3.0** | the other five documents' port shares | Fits. |
| **M14** | 2.0 | P4's M14 half, **1.0** | `design/01`'s superstructure collision router | Fits. |
| **M22** | 1.5 | P5 **1.5** | nothing | Exact. |
| | 17.8 | **15.45** | | |

**The date consequence, computed rather than gestured at.** `ROADMAP.md` §2's solo central rate is 74.0 pw ÷ 174 calendar weeks = **0.4253 pw/wk**. The two milestones this domain can price bottom-up are understated by 3.0 + 0.65 = **3.65 pw**, which is 3.65 ÷ 0.4253 = **8.6 calendar weeks**. Concretely, against `ROADMAP.md`'s own M1–M8 line:

* As published: 18.6 pw ÷ 0.4253 = 43.7 wk from 2026-08-07 = **2027-06-09** ✓ (reproduces `ROADMAP.md` exactly).
* With this domain's decomposition: 18.6 + 3.65 = 22.25 pw ÷ 0.4253 = 52.3 wk = **2027-08-08**.

**So the first internally distributable snapshot is roughly two months later than the milestone column implies, and that is a floor, not an estimate** — it counts only the M8 deliverables this domain owns, and prices none of the docs, adoption-fixture or rip-out work M8 also has to fund.

**Reviewer pushback:** `REVIEW.md` M16 offers two fixes — *"re-derive M1 (3.5) and M8 (2.8) upward (and re-run the date arithmetic), or explicitly reconcile in design/06 §16."*
**Why we keep the bottom-up numbers and disclose rather than deflate:** deflating P0–P4 until they sum to 3.5 would make this document agree with `ROADMAP.md` and be **less true**, and it would destroy the only evidence that M1 is under-budgeted. The milestone column is the *commitment* and the domain decomposition is the *estimate*; when they disagree, the honest move is to publish both and name the direction of the error, not to overwrite the estimate with the commitment. Re-deriving `ROADMAP.md` upward is the better fix and it is a contract request — but it is `ROADMAP.md`'s to make, it moves every date in three documents, and it interacts with `REVIEW.md` M18's separate finding that the stated ±7 % band should be ≥ ±25 %. Note that under a ±25 % band, +3.65 pw on 18.6 is +19.6 % — **inside** the honest band and outside the published one, which is itself an argument for M18's correction.

**What this domain commits to regardless of how that is resolved:** P0 and P2 are on the critical path for every other document, they are the first work done, and if M1 takes 6.5 pw instead of 3.5 the correct response is that **M1 took longer**, not that anything in it was skipped. There is no depth lever inside M1 — the alert budget, the seven monitors and the twelve ArchUnit rules are what every later milestone is built on.

**Two costs deliberately excluded from the 15.45, because they are recurring rather than one-time**, and burying a recurring cost inside a one-time number is how a three-year plan becomes a five-year one:

* **The template tax: ~0.1 pw per library release, forever** (§6.8), which is ~0.5 pw per competition season at the in-season patch cadence.
* **The annual WPILib carrying cost: this domain's share of the 2–4 pw/calendar-year** in [`ROADMAP.md` §7](../ROADMAP.md) — vendor-matrix bumps, deprecation removals, the new `FieldLayouts`/AprilTag entry, CI image bumps, and a template regeneration and 9-job matrix run per line.

Also excluded: the **~2.5 pw R18 tier-2 fork contingency** in §4.8, which is a contingency and not a plan — and which, as of 2026-08-08, is known to be *legally available* (AdvantageKit is BSD-3-Clause) rather than merely hoped for. That does not change the 2.5 pw and does not change R18's severity; it changes whether the money would buy anything.

### What 8793 and 9143 get from this domain, and when

Decision 1 means v0.1 lands after the 2027 kickoff — at solo pace, in 2030. The mechanism that makes that survivable is that **internal milestones are individually usable by the maintainer's own teams even though none of them is a tagged release**, and this domain supplies the largest example:

* **M1 is the highest match-record item in the entire library, and it is the first thing built.** Alerts, the **seven** built-in health monitor types, `MatchContext` (alliance latching), `RobotIdentity`, `CanIdRegistry` and the one-button `SelfTest` **bolt onto existing robot code with no other adoption** — no mechanism conversion, no drivetrain funnel, no vision. A scripted pit check in the queue line converts unwinnable matches into two-minute fixes, in the 2027 season, from a library that will not be released for years.
  **The date on this bullet is the one §16.1 puts most pressure on, so it is stated with its uncertainty rather than as a fact.** `ROADMAP.md`'s M1 budget of 3.5 pw at the solo central rate of 0.4253 pw/wk is 8.2 calendar weeks from 2026-08-07 — **early October 2026**, comfortably before the 2027 kickoff, which is what an earlier draft asserted flatly. This domain's own decomposition says M1 is **6.5 pw**, which is 15.3 weeks — **late November 2026**. Both are before the 2027 kickoff (2027-01-09), so the *claim* survives; the margin does not. At 3.5 pw the buffer is 2026-10-03 → 2027-01-09 = 98 days = **14 weeks**; at 6.5 pw it is 2026-11-22 → 2027-01-09 = 48 days = **7 weeks**. Half the slack, and none of it left for M1 to slip into the season it is meant to serve. That is the practical cost of §16.1's gap, stated where a reader would otherwise take the cheerful version.
* **M8 gives both teams the template and snapshot-artifact consumption.** 9143-B forks the template while 9143-A stays hand-wired — which is exactly the A/B pair the drift tooling in §6.7 needs to be tested against something real. From M8 both teams consume `2026.0.0-SNAPSHOT-M<n>` from the Pages Maven, which means **the in-season upgrade path in §6.6 is exercised for real, two seasons before any stranger relies on it.**
* **M12 ports both team repos with the library** (§5.3 Part C.1), rehearsing the migration on two real robot programs before anyone else runs it.

**A milestone in this domain is not complete until both 8793's and 9143's repos build and pass `simulateJava` against it.** That is a hard gate, and it is what makes [`DESIGN.md` §11b](../DESIGN.md)'s incremental-adoption story true rather than aspirational.

**The honest limit of that story, stated here because this domain is where it is most visible:** M1 through M6 are net-positive on the maintainer's time — each one deletes team code, catches a bug class at construction, or removes a tuning Saturday, and returns more time than integrating it costs. From M7 onward it goes net-negative if the wizard is not yet working, because the teams are then carrying a half-finished dependency through a competition season. **The rule that follows:** if a milestone's integration cost to 8793 or 9143 exceeds its benefit during a season, the teams pin to the last good snapshot and skip it until the offseason. The runtime kill switch (`disabled.txt`, shipped in the template at §6.2) and the rip-out procedure exist for exactly this, and **their first real users are the maintainer's own teams** — which is the correct order, because a safety valve nobody has ever pulled is not a safety valve.

P0 is on the critical path for every other domain and should start immediately.

---

## Appendix A — Sources for load-bearing claims

* **Maintainer decisions 1–4 (2026-08), and the authoritative schedule, capacity table, milestone definitions and depth levers:** `ROADMAP.md`. The 74.0 pw midpoint, the 2029-12-08 solo central date, the M12 date-trigger rule and the R18 contingency tiers all originate there; this document maps them onto the platform domain and must not diverge from them.
* **License:** `LICENSE` at the repo root — BSD-3-Clause, `Copyright (c) 2026 Rootstock contributors`, matching WPILib's own license (decision 4).
#### Closed on 2026-08-08 — facts that were `[UNVERIFIED]` in the previous revision and are now read from primary sources

| Fact | Value | Source (fetched 2026-08-08) |
|---|---|---|
| **AdvantageKit's license** — gated §4.8's fork tier, called "the single most consequential unverified fact in this document" | **BSD-3-Clause.** `Copyright (c) 2021-2026 Littleton Robotics. All rights reserved.` Non-endorsement clause names *Littleton Robotics, FRC 6328 ("Mechanical Advantage"), AdvantageKit,* and other contributors. **Tier 2 is legally available**; the clause constrains naming, not publication. | `https://raw.githubusercontent.com/Mechanical-Advantage/AdvantageKit/main/LICENSE` |
| **AdvantageKit's vendordep UUID and `jsonUrl`** — the `<ADVANTAGEKIT_UUID>` placeholder in §4.2.1 / §4.2.2 | `uuid` `d820cc26-74e3-11ec-90d6-0242ac120003`; `jsonUrl` `https://github.com/Mechanical-Advantage/AdvantageKit/releases/latest/download/AdvantageKit.json`; `fileName` `AdvantageKit.json`; `version` `26.0.2`; `frcYear` `2026`; `mavenUrls` `["https://frcmaven.wpi.edu/artifactory/littletonrobotics-mvn-release/"]` | `https://github.com/Mechanical-Advantage/AdvantageKit/releases/download/v26.0.2/AdvantageKit.json` |
| **AdvantageKit's native artifacts and `validPlatforms`** — §4.1, §15 item 12 | One JNI dep: `org.littletonrobotics.akit:akit-wpilibio:26.0.2`, `isJar: false`, `skipInvalidPlatforms: false`, `validPlatforms` `["linuxathena","linuxx86-64","linuxarm64","osxuniversal","windowsx86-64"]`. `cppDependencies: []`. **Desktop simulation consumes a native artifact; there is no pure-Java sim path.** | same as above |
| **`linuxathena` → `linuxsystemcore` for 2027** — §4.1 | photonlib 2027-alpha (`wpilibYear` `2027_alpha5`, `uuid` `515fe07e-bfc6-11fa-b3de-0242ac130004`) lists `windowsx86-64, linuxsystemcore, linuxx86-64, osxuniversal`; **`linuxathena` does not appear.** Also corroborates §4.2's `wpilibYear`-for-2027 rule. | `https://maven.photonvision.org/repository/internal/org/photonvision/photonlib-json/1.0/photonlib-json-1.0.json` |
| **CTRE Phoenix 6 UUID** — the `<PHOENIX6_UUID>` placeholder in §4.2.2 | `e995de00-2c64-4df5-8831-c1441420ff19`; `fileName` `Phoenix6-frc2026-latest.json`; `name` `CTRE-Phoenix (v6)`; `version` `26.3.0`; `frcYear` `2026` | `https://maven.ctr-electronics.com/release/com/ctre/phoenix6/latest/Phoenix6-frc2026-latest.json` |
| **REVLib `fileName` and UUID** — §4.2.2, §6.2.1 | `fileName` `REVLib.json`; `uuid` `3f48eb8c-50fe-43a6-9cb7-44c86353c4cb`; `version` `2026.0.5`; `frcYear` `2026` | `https://software-metadata.revrobotics.com/REVLib-2026.json` |
| **PathplannerLib `fileName` and UUID** — §4.2.2, §6.2.1 | `fileName` `PathplannerLib.json`; `uuid` `1b42324f-17c6-4875-8e77-1c312bc8c786`; `version` `2026.1.2`; `frcYear` `2026`; java artifact `PathplannerLib-java` | `https://3015rangerrobotics.github.io/pathplannerlib/PathplannerLib.json` |

#### Still `[UNVERIFIED]`, listed here because these can invalidate sections rather than sentences

* **The `WPILibNewCommands.json` UUID.** Candidate `111e20f7-815e-48f8-9dd6-e675ce75b266` from a **secondary** source only; the primary file could not be fetched. It ships offline with the WPILib installer, so reading it is a local file open at release-cut time, and `verifyRequiresUuids` is what turns it into a checked fact. §4.2.1.
* **Whether AdvantageKit's installation requires any `build.gradle` stanza beyond the `annotationProcessor` line** (a replay-source configuration, a `libraryDirectories` entry). `docs.advantagekit.org` was unreachable on 2026-08-08. This is invisible until a team tries to replay, so it must be read off AdvantageKit's own installation docs before M8 and re-read at every AdvantageKit major bump — **not copied from memory.** §6.2.
* **Whether the 2026-line `photonlib` vendordep carries the same UUID and `fileName` as the 2027-alpha one.** Vendors keep UUIDs stable across years by convention, not by schema rule. §4.2.2, §6.2.1.
* **ChoreoLib's and maple-sim's vendordep UUIDs and filenames.** Not yet read; needed before those adapter JSONs are cut. §4.2.2.
* **Whether AdvantageKit's *2027* vendordep and LICENSE keep their current shape.** Re-read both at the 2027 beta — it is the same fetch that arms R18's trigger. §4.8.
* **`RobotController.getSerialNumber()` / `getComments()` on SystemCore**, and whether the WPILib `Alert` group NT path stays at `/SmartDashboard/<Group>` in 2027. §7.1, §4.6, §15 items 1 and 3.
* **The `.rootstock/template.lock` hashing behavior under Windows line-ending translation.** §15 item 13 — must be *tested* in the 9-job matrix, not reasoned about.
* **Every row of the `edu.wpi.first.*` → `org.wpilib.*` rename map**, until M12 compiles it. §5.3 Part C, §15 item 2.
* **Whether per-LED `SolidColor` writes to a 60-LED CANdle are CAN-affordable at 50 Hz.** §9.3, §15 item 4 — measure before shipping `CandleBackend`.

#### Other sources

* Vendordep schema: `https://raw.githubusercontent.com/wpilibsuite/vendor-json-repo/main/check.py` (fetched 2026-08-06).
* Live 2026 vendordep using `frcYear`: `https://3015rangerrobotics.github.io/pathplannerlib/PathplannerLib.json` (fetched 2026-08-06, re-fetched 2026-08-08).
* `WebServer.start(int, String)`: `https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/net/WebServer.html` (fetched 2026-08-06).
* `gversion` snippet and `BuildConstants` field list: `https://docs.wpilib.org/en/stable/docs/software/advanced-gradlerio/deploy-git-data.html` (fetched 2026-08-06).
* `CANBus.getStatus()` → `CANBusStatus{Status, BusUtilization, BusOffCount, TxFullCount, REC, TEC}`: `https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/CANBus.CANBusStatus.html` (fetched 2026-08-06).
* `CANdleConfiguration.LED` carries `StripType`/`BrightnessScalar`/`LossOfSignalBehavior`: `https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/configs/CANdleConfiguration.html`.
* GitHub Packages requires a token for public Maven artifacts: `https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry`.
* 2027 removals: `https://docs.wpilib.org/en/2027/docs/yearly-overview/removed-features.html` and `https://docs.wpilib.org/en/latest/docs/yearly-overview/yearly-changelog.html`.
* Dossiers: the working research notes behind this document. They live in a gitignored `.research/` directory and are not published.
* User repo conventions (`m_field`, `kConstant`, Java 17, GradleRIO 2026.2.1, Phoenix-6-only, no AdvantageKit, hoot replay, `optimizeBusUtilization` everywhere): 8793's 2026 robot code, a separate repository, read 2026-08: `build.gradle`, `src/main/java/frc/robot/Robot.java`, `src/main/java/frc/robot/Telemetry.java`, `src/main/java/frc/robot/subsystems/LEDSubsystem.java`, `CLAUDE.md`.
